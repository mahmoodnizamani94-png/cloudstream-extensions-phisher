package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * State-of-the-Art (SOTA) Urdu Subtitle Harvesting, Encoding/Typography Normalization,
 * RTL BiDi Sanitization, and Timing-Preserved Neural Translation Pipeline.
 *
 * Designed for StreamPlay so that:
 * 1. Human Urdu subtitles from SubDL, SubSense, Subf2m, OpenSubtitles, YTS-Subs, and SubtitleCat
 *    are harvested concurrently without API keys.
 * 2. Legacy Urdu encodings (`Windows-1256` / `CP1256`, `UTF-16LE/BE`, BOM-prefixed `UTF-8`),
 *    compressed payloads (`GZIP`, multi-entry `ZIP`), Arabic-vs-Urdu glyph variants (`\u064A`/`\u0649` -> `\u06CC`,
 *    `\u0643` -> `\u06A9`), and mixed-script RTL punctuation order (`\u200F` RLM) are normalized deterministically.
 * 3. When no human Urdu subtitle exists for a movie or episode, the highest-priority English `.srt`/`.vtt`
 *    is translated into timing-preserved Urdu via batched neural translation (`250` cues/batch, 1-to-1 cue
 *    mapping with zero timestamp drift) and served via SubtitleCat CDN or local `.srt` cache.
 */
object UrduSubtitleEngine {

    private const val TAG = "UrduSubtitleEngine"
    private const val MAX_INFLATE_BYTES = 8 * 1024 * 1024 // 8 MiB safety ceiling
    private const val TRANSLATION_BATCH_SIZE = 250
    private const val RLM = '\u200F'

    const val SUBTITLE_MODE_PREFS_NAME = "cloudstream_subtitle_mode_prefs"
    const val SUBTITLE_MODE_KEY = "subtitle_mode"
    const val SUBTITLE_MODE_SYS_PROP = "cloudstream.subtitle.mode"
    const val MODE_DEFAULT = "default"
    const val MODE_URDU = "urdu"
    const val TARGET_URDU_SUBTITLE_SOURCES = 3

    @Volatile
    private var appContextRef: android.content.Context? = null

    enum class SubtitleMode(val id: String, val displayLabel: String) {
        DEFAULT(MODE_DEFAULT, "Default (English)"),
        URDU(MODE_URDU, "Urdu (\u0627\u0631\u062f\u0648)");

        companion object {
            fun fromId(raw: String?): SubtitleMode? {
                return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                    MODE_URDU, "ur", "urd", "ur-pk", "ur-in" -> URDU
                    MODE_DEFAULT, "en", "eng", "english", "en-us", "en-gb" -> DEFAULT
                    else -> null
                }
            }
        }
    }

    /**
     * Resolves the active [SubtitleMode] in 0ms without requiring an app restart.
     *
     * Priority order:
     * 1. `System.getProperty("cloudstream.subtitle.mode")` (instant in-process sync between host app and extension).
     * 2. Shared `"cloudstream_subtitle_mode_prefs"` (`"subtitle_mode"`).
     * 3. `StreamPlay` `sharedPref` (`"subtitle_mode"`).
     * 4. Defaults to [SubtitleMode.DEFAULT] (English).
     */
    fun getSubtitleMode(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null
    ): SubtitleMode {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }

        // 1. JVM System Property (0ms cross-classloader sync)
        val sysProp = runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull()
        SubtitleMode.fromId(sysProp)?.let { return it }

        // 2. Host/Extension shared mode preferences ("cloudstream_subtitle_mode_prefs")
        val ctx = context?.applicationContext ?: context ?: appContextRef
        if (ctx != null) {
            val sharedModeStr = runCatching {
                ctx.getSharedPreferences(SUBTITLE_MODE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    ?.getString(SUBTITLE_MODE_KEY, null)
            }.getOrNull()
            SubtitleMode.fromId(sharedModeStr)?.let { return it }
        }

        // 3. StreamPlay's own SharedPreferences
        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        val extModeStr = runCatching { effectivePref?.getString(SUBTITLE_MODE_KEY, null) }.getOrNull()
        SubtitleMode.fromId(extModeStr)?.let { return it }

        // 4. Default is always Default (English)
        return SubtitleMode.DEFAULT
    }

    fun isUrduMode(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null
    ): Boolean = getSubtitleMode(context, sharedPref) == SubtitleMode.URDU

    /**
     * Switches between [SubtitleMode.DEFAULT] (English) and [SubtitleMode.URDU] (Urdu) with immediate effect:
     * 1. Updates `System.setProperty("cloudstream.subtitle.mode", mode.id)` for 0ms cross-classloader visibility.
     * 2. Persists `"subtitle_mode"` in both `sharedPref` and `"cloudstream_subtitle_mode_prefs"`.
     * 3. Synchronizes the host app's default preferences (`"subtitles_auto_select"` = `"ur"` or `"en"`)
     *    so both streaming (ExoPlayer track selection) and downloading immediately use the chosen mode.
     */
    @Suppress("DEPRECATION")
    fun setSubtitleMode(
        mode: SubtitleMode,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null
    ) {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }

        runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, mode.id) }

        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        runCatching {
            effectivePref?.edit()?.putString(SUBTITLE_MODE_KEY, mode.id)?.apply()
        }
        if (sharedPref != null && StreamPlay.companionSharedPref != null && sharedPref !== StreamPlay.companionSharedPref) {
            runCatching {
                StreamPlay.companionSharedPref?.edit()?.putString(SUBTITLE_MODE_KEY, mode.id)?.apply()
            }
        }

        val ctx = context?.applicationContext ?: context ?: appContextRef
        if (ctx != null) {
            runCatching {
                ctx.getSharedPreferences(SUBTITLE_MODE_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(SUBTITLE_MODE_KEY, mode.id)
                    ?.apply()
            }
            val targetAutoSelectLang = if (mode == SubtitleMode.URDU) "ur" else "en"
            runCatching {
                android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                    ?.edit()
                    ?.putString("subtitles_auto_select", targetAutoSelectLang)
                    ?.putString(SUBTITLE_MODE_KEY, mode.id)
                    ?.apply()
            }
            runCatching {
                ctx.getSharedPreferences("${ctx.packageName}_preferences", android.content.Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString("subtitles_auto_select", targetAutoSelectLang)
                    ?.putString(SUBTITLE_MODE_KEY, mode.id)
                    ?.apply()
            }
        }
    }

    /**
     * Called on plugin initialization to capture `applicationContext` and warm the JVM property
     * from persisted preferences if not already set in the current process.
     */
    fun initializeMode(
        context: android.content.Context,
        sharedPref: android.content.SharedPreferences? = null
    ) {
        appContextRef = context.applicationContext ?: context
        val mode = getSubtitleMode(context, sharedPref)
        if (runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull().isNullOrBlank()) {
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, mode.id) }
        }
    }

    private const val SUBDL_AUTO_API = "https://api3.subdl.com/auto"
    private const val SUBDL_WEB_HOST = "https://subdl.com"
    private const val SUBDL_DL_HOST = "https://dl.subdl.com"

    private const val SUBSENSE_BASE =
        "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles"

    private const val SUBF2M_HOST = "https://subf2m.co"
    private const val SUBTITLECAT_UPLOAD_URL = "https://www.subtitlecat.com/upload_subtitles.php"
    private const val SUBTITLECAT_HOST = "https://www.subtitlecat.com"

    private const val GOOGLE_TRANSLATE_CLIENTS5 =
        "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ur"
    private const val GOOGLE_TRANSLATE_FALLBACK =
        "https://translate.google.com/translate_a/t?client=at&sl=en&tl=ur"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val URDU_ARABIC_SCRIPT_REGEX =
        Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")

    private val ASS_TAG_REGEX = Regex("""\{\\[^}]*\}""")
    private val HTML_TAG_REGEX = Regex("""</?(?:font|b|i|u|s|span|div|p|br)\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val WATERMARK_PATTERNS = listOf(
        "@iamdesigora",
        "iamdesigora",
        "black gold",
        "subtitles by",
        "subtitle by",
        "downloaded from",
        "opensubtitles.org",
        "yts-subs.com",
        "yifysubtitles",
        "subscene.com",
        "subdl.com",
        "addic7ed.com",
        "سے ڈاؤن لوڈ",
        "ترجمہ کار"
    )

    private val SEASON_ORDINALS = mapOf(
        1 to "first-season",
        2 to "second-season",
        3 to "third-season",
        4 to "fourth-season",
        5 to "fifth-season",
        6 to "sixth-season",
        7 to "seventh-season",
        8 to "eighth-season",
        9 to "ninth-season",
        10 to "tenth-season",
        11 to "eleventh-season",
        12 to "twelfth-season",
        13 to "thirteenth-season",
        14 to "fourteenth-season",
        15 to "fifteenth-season",
        16 to "sixteenth-season",
        17 to "seventeenth-season",
        18 to "eighteenth-season",
        19 to "nineteenth-season",
        20 to "twentieth-season"
    )

    // Cache of synthesized Urdu subtitle URLs keyed by normalized English subtitle URL
    private val synthesizedUrduCache = ConcurrentHashMap<String, String>()

    // Pending SubtitleCat original SRT candidates recorded during SubtitleCat harvesting:
    // Pair(origSrtAbsoluteUrl, origFilename)
    private val pendingSubtitleCatOrigList = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    fun recordSubtitleCatOrigCandidate(origUrl: String, origFilename: String) {
        if (origUrl.isNotBlank() && origFilename.isNotBlank()) {
            pendingSubtitleCatOrigList.add(origUrl.trim() to origFilename.trim())
        }
    }

    fun consumeSubtitleCatOrigCandidates(): List<Pair<String, String>> {
        val list = pendingSubtitleCatOrigList.toList()
        pendingSubtitleCatOrigList.clear()
        return list
    }

    // =========================================================================
    // 1. Archive Inflation, Encoding Detection & Urdu Typography Normalization
    // =========================================================================

    /**
     * Safely inflates GZIP (`0x1F 0x8B`) or ZIP (`0x50 0x4B 0x03 0x04`) subtitle archives
     * up to [MAX_INFLATE_BYTES] (8 MiB), selecting the best `.srt`/`.vtt`/`.ass`/`.ssa` entry
     * inside ZIPs while skipping `.nfo`, `.txt`, and directories.
     */
    fun inflateArchiveIfNeeded(raw: ByteArray): ByteArray {
        if (raw.size < 4) return raw
        val b0 = raw[0].toInt() and 0xFF
        val b1 = raw[1].toInt() and 0xFF
        val b2 = raw[2].toInt() and 0xFF
        val b3 = raw[3].toInt() and 0xFF

        // GZIP magic: 1F 8B
        if (b0 == 0x1F && b1 == 0x8B) {
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(raw)).use { gis ->
                    readBoundedBytes(gis, MAX_INFLATE_BYTES)
                }
            }.getOrDefault(raw)
        }

        // ZIP magic: 50 4B 03 04
        if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
            return runCatching {
                extractBestSubtitleFromZip(raw) ?: raw
            }.getOrDefault(raw)
        }

        return raw
    }

    private fun extractBestSubtitleFromZip(zipBytes: ByteArray): ByteArray? {
        var bestBytes: ByteArray? = null
        var bestScore = -1

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = runCatching { zis.nextEntry }.getOrNull() ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val name = entry.name.lowercase(Locale.ROOT)
                val baseName = name.substringAfterLast('/')
                if (baseName.startsWith(".") || baseName.startsWith("__macosx")) {
                    zis.closeEntry()
                    continue
                }
                if (baseName.endsWith(".nfo") || baseName.endsWith(".txt") ||
                    baseName.endsWith(".jpg") || baseName.endsWith(".png") ||
                    baseName.endsWith(".url") || baseName.endsWith(".exe")
                ) {
                    zis.closeEntry()
                    continue
                }

                val extScore = when {
                    baseName.endsWith(".srt") -> 100
                    baseName.endsWith(".vtt") -> 90
                    baseName.endsWith(".ass") || baseName.endsWith(".ssa") -> 80
                    baseName.endsWith(".sub") -> 60
                    else -> 10
                }
                val langBonus = if (baseName.contains("ur") || baseName.contains("urdu")) 25 else 0
                val entryBytes = readBoundedBytes(zis, MAX_INFLATE_BYTES)
                zis.closeEntry()

                if (entryBytes.isNotEmpty()) {
                    // Support nested GZIP inside ZIP if present
                    val unpacked = if (entryBytes.size >= 2 &&
                        (entryBytes[0].toInt() and 0xFF) == 0x1F &&
                        (entryBytes[1].toInt() and 0xFF) == 0x8B
                    ) {
                        runCatching {
                            GZIPInputStream(ByteArrayInputStream(entryBytes)).use {
                                readBoundedBytes(it, MAX_INFLATE_BYTES)
                            }
                        }.getOrDefault(entryBytes)
                    } else {
                        entryBytes
                    }

                    val score = extScore + langBonus + (unpacked.size.coerceAtMost(500_000) / 10_000)
                    if (score > bestScore) {
                        bestScore = score
                        bestBytes = unpacked
                    }
                }
            }
        }
        return bestBytes
    }

    private fun readBoundedBytes(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            val allowed = (maxBytes - total).coerceAtMost(read)
            if (allowed > 0) {
                out.write(buf, 0, allowed)
                total += allowed
            }
            if (total >= maxBytes) break
        }
        return out.toByteArray()
    }

    /**
     * Decodes raw subtitle bytes (including GZIP/ZIP payloads, UTF-8/UTF-16LE/UTF-16BE BOMs,
     * UTF-16 null strides, and legacy `Windows-1256` / `CP1256` single-byte Urdu encodings)
     * and normalizes Arabic characters (`\u064A`/`\u0649` -> `\u06CC`, `\u0643` -> `\u06A9`)
     * into canonical Urdu NFC Unicode.
     */
    fun decodeAndNormalizeUrduBytes(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        val bytes = inflateArchiveIfNeeded(raw)
        if (bytes.isEmpty()) return ""

        val decoded = decodeSubtitleCharset(bytes)
        return normalizeUrduTypography(decoded)
    }

    internal fun decodeSubtitleCharset(bytes: ByteArray): String {
        // 1. Check explicit BOMs
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
            }
        }

        // 2. Heuristic UTF-16LE / UTF-16BE null-byte stride detection (no BOM)
        if (bytes.size >= 8 && bytes.size % 2 == 0) {
            val samplePairs = (bytes.size / 2).coerceAtMost(64)
            var evenZeroes = 0
            var oddZeroes = 0
            for (i in 0 until samplePairs) {
                if (bytes[i * 2].toInt() == 0) evenZeroes++
                if (bytes[i * 2 + 1].toInt() == 0) oddZeroes++
            }
            if (oddZeroes >= samplePairs / 2 && evenZeroes <= samplePairs / 8) {
                return String(bytes, StandardCharsets.UTF_16LE)
            }
            if (evenZeroes >= samplePairs / 2 && oddZeroes <= samplePairs / 8) {
                return String(bytes, StandardCharsets.UTF_16BE)
            }
        }

        // 3. Validate strict UTF-8
        val utf8Decoded = tryStrictUtf8(bytes)
        if (utf8Decoded != null) {
            return utf8Decoded
        }

        // 4. Detect Windows-1256 (CP1256) legacy Urdu/Arabic single-byte encoding:
        //    0x80..0x9F contains Urdu-specific letters (پ 0x81, ٹ 0x8A, چ 0x8D, ڈ 0x8E, ڈ/ڑ 0x8F/0x9A, ژ 0x9E, ک 0x98, گ 0x90, ں 0x9F, ے 0xFF)
        //    and 0xC1..0xFF contains core Arabic/Urdu letters.
        if (looksLikeWindows1256(bytes)) {
            val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrNull()
                ?: runCatching { Charset.forName("Cp1256") }.getOrNull()
            if (cp1256 != null) {
                return String(bytes, cp1256)
            }
        }

        // Fallback to Windows-1256 if high bytes exist, else UTF-8 replacement
        val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrNull()
        return if (cp1256 != null && bytes.any { (it.toInt() and 0xFF) >= 0x80 }) {
            String(bytes, cp1256)
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun tryStrictUtf8(bytes: ByteArray): String? {
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val charBuf: CharBuffer = decoder.decode(ByteBuffer.wrap(bytes))
            charBuf.toString()
        } catch (_: Exception) {
            null
        }
    }

    internal fun looksLikeWindows1256(bytes: ByteArray): Boolean {
        var urduSpecificCount = 0
        var arabicRangeCount = 0
        var highByteCount = 0
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u >= 0x80) {
                highByteCount++
                when (u) {
                    // CP1256 Urdu-specific code points:
                    // 0x81 (پ), 0x8A (ٹ), 0x8D (چ), 0x8E (ژ), 0x8F (ڈ), 0x90 (گ),
                    // 0x98 (ک), 0x9A (ڑ), 0x9F (ں), 0xAA (ھ), 0xC0 (ہ), 0xFF (ے)
                    0x81, 0x8A, 0x8D, 0x8E, 0x8F, 0x90, 0x98, 0x9A, 0x9F, 0xAA, 0xC0, 0xFF -> {
                        urduSpecificCount++
                    }
                    in 0xC1..0xFE -> {
                        arabicRangeCount++
                    }
                }
            }
        }
        if (highByteCount == 0) return false
        return urduSpecificCount > 0 || (arabicRangeCount * 2 >= highByteCount)
    }

    /**
     * Normalizes Arabic script variants to canonical Urdu code points:
     * - Arabic Yeh (`\u064A` ي) & Alef Maksura (`\u0649` ى) -> Urdu Farsi Yeh (`\u06CC` ی)
     * - Arabic Kaf (`\u0643` ك) -> Urdu Keheh (`\u06A9` ک)
     * - Strips BOM (`\uFEFF`) and normalizes line endings to `\n` + Unicode NFC.
     */
    fun normalizeUrduTypography(input: String): String {
        if (input.isEmpty()) return ""
        val cleaned = input
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u064A', '\u06CC') // Arabic Yeh -> Urdu Yeh
            .replace('\u0649', '\u06CC') // Alef Maksura -> Urdu Yeh
            .replace('\u0643', '\u06A9') // Arabic Kaf -> Urdu Keheh
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
    }

    /**
     * Sanitizes a subtitle cue and applies stateless Right-to-Left Marks (`\u200F` RLM):
     * - Strips ASS override tags (`{\an8}`, `{\pos(x,y)}`, etc.) and HTML font/style tags.
     * - Drops spam/watermark credit cues (`@iamdesigora`, `black gold`, `subtitles by`, `downloaded from`, etc.).
     * - Normalizes Urdu typography (`ی`, `ک`).
     * - Wraps every line containing Urdu/Arabic script characters with `\u200F` (`"\u200F$stripped\u200F"`)
     *   so ExoPlayer renders mixed English/numbers and trailing punctuation (`.`, `?`, `!`) in true RTL order.
     */
    fun sanitizeAndApplyRtlBidi(cueText: String): String {
        if (cueText.isBlank()) return ""
        val normalized = normalizeUrduTypography(cueText)

        // Strip ASS override tags and HTML tags
        val withoutTags = normalized
            .replace(ASS_TAG_REGEX, "")
            .replace(HTML_TAG_REGEX, "")
            .trim()

        if (withoutTags.isEmpty()) return ""

        // Check if entire cue is a watermark/credit cue
        if (isWatermarkCue(withoutTags)) return ""

        val lines = withoutTags.split('\n')
        val processedLines = lines.mapNotNull { rawLine ->
            val stripped = rawLine.trim().trim(RLM, '\u200E', '\u202A', '\u202B', '\u202C')
            if (stripped.isEmpty()) return@mapNotNull null
            if (isWatermarkCue(stripped)) return@mapNotNull null
            if (URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(stripped)) {
                "$RLM$stripped$RLM"
            } else {
                stripped
            }
        }

        return processedLines.joinToString("\n")
    }

    internal fun isWatermarkCue(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return WATERMARK_PATTERNS.any { pattern -> lower.contains(pattern.lowercase(Locale.ROOT)) }
    }

    // =========================================================================
    // 2. High-Throughput Timing-Preserved Neural Translation (EN -> UR)
    // =========================================================================

    data class SrtCue(
        val index: Int,
        val timestamp: String,
        val text: String
    )

    private val SRT_TIMESTAMP_REGEX = Regex(
        """^\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})(.*)$"""
    )

    /**
     * Parses either SubRip (`.srt`) or WebVTT (`.vtt`) content into structured [SrtCue]s,
     * normalizing timestamps to canonical SRT format (`HH:MM:SS,mmm --> HH:MM:SS,mmm`).
     */
    fun parseSubtitleCues(rawContent: String): List<SrtCue> {
        val content = rawContent
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (content.isEmpty()) return emptyList()

        val lines = content.split('\n')
        val cues = mutableListOf<SrtCue>()
        var i = 0
        var cueCounter = 1

        // Skip WEBVTT header if present
        if (lines.firstOrNull()?.trim()?.startsWith("WEBVTT", ignoreCase = true) == true) {
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                i++
            }
        }

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("NOTE", ignoreCase = true) || line.startsWith("STYLE", ignoreCase = true)) {
                i++
                continue
            }

            // Optional numeric cue index line
            var timestampMatch = SRT_TIMESTAMP_REGEX.matchEntire(line)
            if (timestampMatch == null && i + 1 < lines.size) {
                val nextLine = lines[i + 1].trim()
                val nextMatch = SRT_TIMESTAMP_REGEX.matchEntire(nextLine)
                if (nextMatch != null) {
                    timestampMatch = nextMatch
                    i += 2
                } else {
                    i++
                    continue
                }
            } else if (timestampMatch != null) {
                i++
            } else {
                i++
                continue
            }

            val startRaw = normalizeSrtTimestamp(timestampMatch.groupValues[1])
            val endRaw = normalizeSrtTimestamp(timestampMatch.groupValues[2])
            val canonicalTimestamp = "$startRaw --> $endRaw"

            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                val cueLine = lines[i]
                    .replace(ASS_TAG_REGEX, "")
                    .replace(HTML_TAG_REGEX, "")
                    .trim()
                if (cueLine.isNotEmpty()) {
                    textLines.add(cueLine)
                }
                i++
            }

            val combinedText = textLines.joinToString("\n").trim()
            if (combinedText.isNotEmpty() && !isWatermarkCue(combinedText)) {
                cues.add(
                    SrtCue(
                        index = cueCounter++,
                        timestamp = canonicalTimestamp,
                        text = combinedText
                    )
                )
            }
        }
        return cues
    }

    internal fun normalizeSrtTimestamp(ts: String): String {
        val commaFixed = ts.trim().replace('.', ',')
        val parts = commaFixed.split(':')
        return when (parts.size) {
            2 -> "00:${parts[0].padStart(2, '0')}:${parts[1]}"
            3 -> "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}:${parts[2]}"
            else -> commaFixed
        }
    }

    /**
     * Reconstructs a valid `.srt` document from translated cues, applying Urdu typography
     * normalization and stateless `\u200F` RLM wrapping on every Urdu line.
     */
    fun formatCuesAsUrduSrt(cues: List<SrtCue>): String {
        if (cues.isEmpty()) return ""
        val sb = StringBuilder(cues.size * 64)
        var outIndex = 1
        for (cue in cues) {
            val sanitized = sanitizeAndApplyRtlBidi(cue.text)
            if (sanitized.isBlank()) continue
            sb.append(outIndex++).append('\n')
            sb.append(cue.timestamp).append('\n')
            sb.append(sanitized).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    /**
     * Translates an English `.srt` or `.vtt` subtitle string into a timing-preserved Urdu `.srt`
     * by batching cues (`250` cues per batch) in parallel via `clients5.google.com` (`dict-chrome-ex`)
     * with automatic fallback to `translate.google.com` (`client=at`).
     */
    suspend fun translateEnglishSrtToUrdu(
        englishSrtOrVtt: String,
        batchTranslator: (suspend (List<String>) -> List<String>?)? = null
    ): String? = coroutineScope {
        val cues = parseSubtitleCues(englishSrtOrVtt)
        if (cues.isEmpty()) return@coroutineScope null

        val translator: suspend (List<String>) -> List<String>? =
            batchTranslator ?: { batch -> translateBatchToUrdu(batch) }

        val batches = cues.chunked(TRANSLATION_BATCH_SIZE)
        val translatedBatches = batches.map { batchCues ->
            async(Dispatchers.IO) {
                val sourceTexts = batchCues.map { it.text }
                val translatedTexts = translator(sourceTexts)
                if (translatedTexts != null && translatedTexts.size == batchCues.size) {
                    batchCues.mapIndexed { idx, cue ->
                        cue.copy(text = translatedTexts[idx])
                    }
                } else {
                    null
                }
            }
        }.awaitAll()

        if (translatedBatches.any { it == null }) return@coroutineScope null
        val mergedCues = translatedBatches.filterNotNull().flatten()
        formatCuesAsUrduSrt(mergedCues).takeIf { it.isNotBlank() }
    }

    internal suspend fun translateBatchToUrdu(texts: List<String>): List<String>? {
        if (texts.isEmpty()) return emptyList()
        // Primary: clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ur
        translateBatchViaEndpoint(GOOGLE_TRANSLATE_CLIENTS5, texts)?.let { return it }
        // Fallback: translate.google.com/translate_a/t?client=at&sl=en&tl=ur
        return translateBatchViaEndpoint(GOOGLE_TRANSLATE_FALLBACK, texts)
    }

    internal suspend fun translateBatchViaEndpoint(
        endpointUrl: String,
        texts: List<String>
    ): List<String>? = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder(Charsets.UTF_8)
            for (text in texts) {
                formBuilder.add("q", text)
            }
            val headers = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Accept" to "application/json, text/plain, */*"
            )
            val response = StreamPlayExtractor.cancellablePost(
                url = endpointUrl,
                headers = headers,
                requestBody = formBuilder.build(),
                timeoutSec = 5L
            ) ?: return@withContext null

            response.use { resp ->
                if (resp.code != 200) return@withContext null
                val body = resp.body.string()
                parseGoogleBatchTranslateResponse(body, expectedCount = texts.size)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the JSON array response from `translate_a/t` (`["ur_1", "ur_2", ...]` or
     * nested `[["ur_1", "en_1"], ...]`).
     */
    internal fun parseGoogleBatchTranslateResponse(
        jsonBody: String,
        expectedCount: Int
    ): List<String>? {
        return try {
            val trimmed = jsonBody.trim()
            if (!trimmed.startsWith("[")) return null
            val arr = JSONArray(trimmed)
            if (arr.length() != expectedCount) {
                // Single-element edge case where response may be `["translated"]`
                if (expectedCount == 1 && arr.length() >= 1) {
                    val first = arr.get(0)
                    val text = when (first) {
                        is String -> first
                        is JSONArray -> first.optString(0)
                        else -> first.toString()
                    }
                    return listOf(text)
                }
                return null
            }
            val result = ArrayList<String>(expectedCount)
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                val translated = when (item) {
                    is String -> item
                    is JSONArray -> item.optString(0)
                    else -> item.toString()
                }
                result.add(translated)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 3. Delivery & Caching (SubtitleCat CDN + Local File Cache)
    // =========================================================================

    /**
     * Publishes a translated Urdu `.srt` to SubtitleCat's permanent CDN via
     * `POST https://www.subtitlecat.com/upload_subtitles.php` and returns the resulting
     * `https://www.subtitlecat.com/subs/.../...-ur.srt` URL when successful.
     */
    suspend fun publishToSubtitleCatCdn(
        origFilename: String,
        urduSrtContent: String,
        subFolder: String = "/subs/synthesized/"
    ): String? = withContext(Dispatchers.IO) {
        if (origFilename.isBlank() || urduSrtContent.isBlank()) return@withContext null
        try {
            val cleanBaseName = origFilename.substringAfterLast('/')
                .removeSuffix("-orig.srt")
                .removeSuffix("-en.srt")
                .removeSuffix("-eng.srt")
                .removeSuffix(".srt")
                .trim()
                .ifBlank { "streamplay" }

            val targetFilename = "$cleanBaseName-ur.srt"
            val normalizedFolder = "/" + subFolder.trim('/').ifBlank { "subs" } + "/"

            val formBody = FormBody.Builder(Charsets.UTF_8)
                .add("filename", targetFilename)
                .add("content", urduSrtContent)
                .add("language", "ur")
                .add("orig_language", "en")
                .add("private_id", "0")
                .add("folder", normalizedFolder)
                .build()

            val headers = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Origin" to SUBTITLECAT_HOST,
                "Referer" to "$SUBTITLECAT_HOST/"
            )

            val response = StreamPlayExtractor.cancellablePost(
                url = SUBTITLECAT_UPLOAD_URL,
                headers = headers,
                requestBody = formBody,
                timeoutSec = 4L
            ) ?: return@withContext null

            response.use { resp ->
                if (resp.code !in 200..299) return@withContext null
                val body = resp.body.string().trim()
                val directMatch = Regex("""(?i)(https?://www\.subtitlecat\.com/subs/[^\s"'<>]+-(?:ur|urd)\.srt|/subs/[^\s"'<>]+-(?:ur|urd)\.srt)""")
                    .find(body)?.value
                if (directMatch != null) {
                    return@withContext if (directMatch.startsWith("http", ignoreCase = true)) {
                        directMatch
                    } else {
                        "$SUBTITLECAT_HOST$directMatch"
                    }
                }
                if (body.equals("ok", ignoreCase = true) || body.equals("1", ignoreCase = true) || body.contains("success", ignoreCase = true)) {
                    val candidateUrl = "$SUBTITLECAT_HOST${normalizedFolder}${targetFilename.replace(" ", "%20")}"
                    return@withContext candidateUrl
                }
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Writes the translated Urdu `.srt` to the local temporary directory as a `file://` URI
     * fallback when offline or when CDN publishing is unavailable.
     */
    fun writeUrduLocalCacheFile(keySeed: String, urduSrtContent: String): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(keySeed.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!tmpDir.exists()) tmpDir.mkdirs()
            val file = File(tmpDir, "streamplay_urdu_$digest.srt")
            // Include UTF-8 BOM so ExoPlayer / external players unconditionally detect UTF-8
            val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            val contentBytes = urduSrtContent.toByteArray(StandardCharsets.UTF_8)
            file.writeBytes(bomBytes + contentBytes)
            file.toURI().toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * End-to-end neural Urdu fallback synthesis:
     * Fetches the best available English `.srt`/`.vtt`/`.gz`/`.zip` candidate (preferring
     * SubtitleCat `-orig.srt` candidates if recorded so the translation can also be cached on
     * SubtitleCat's CDN), translates it to timing-preserved RTL Urdu, and emits
     * `Urdu (AI Translated)` with `X-StreamPlay-Source: urdu-neural-fallback`.
     */
    suspend fun synthesizeUrduFallbackFromEnglish(
        englishUrls: Collection<String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val subCatOrigs = consumeSubtitleCatOrigCandidates()
        val orderedCandidates = buildList {
            subCatOrigs.forEach { (url, name) -> add(url to name) }
            englishUrls.forEach { url ->
                val clean = url.trim()
                if (clean.isNotBlank() && none { it.first == clean }) {
                    add(clean to clean.substringAfterLast('/').substringBefore('?').ifBlank { "english.srt" })
                }
            }
        }.take(3)

        if (orderedCandidates.isEmpty()) return@withContext false

        for ((enUrl, origName) in orderedCandidates) {
            synthesizedUrduCache[enUrl]?.let { cachedUrl ->
                subtitleCallback(
                    newSubtitleFile("Urdu (AI Translated)", cachedUrl) {
                        headers = mapOf("X-StreamPlay-Source" to "urdu-neural-fallback")
                    }
                )
                return@withContext true
            }

            val rawBytes = fetchSubtitleBytes(enUrl, timeoutSec = 4L) ?: continue
            val decodedEnglish = decodeAndNormalizeUrduBytes(rawBytes)
            if (decodedEnglish.length < 32) continue

            val urduSrt = translateEnglishSrtToUrdu(decodedEnglish) ?: continue
            val subFolder = if (enUrl.contains("subtitlecat.com/subs/", ignoreCase = true)) {
                val path = enUrl.substringAfter("subtitlecat.com").substringBeforeLast('/') + "/"
                path
            } else {
                "/subs/synthesized/"
            }

            val deliveryUrl = publishToSubtitleCatCdn(origName, urduSrt, subFolder)
                ?: writeUrduLocalCacheFile(enUrl, urduSrt)
                ?: continue

            synthesizedUrduCache[enUrl] = deliveryUrl
            subtitleCallback(
                newSubtitleFile("Urdu (AI Translated)", deliveryUrl) {
                    headers = mapOf("X-StreamPlay-Source" to "urdu-neural-fallback")
                }
            )
            return@withContext true
        }
        false
    }

    private suspend fun fetchSubtitleBytes(url: String, timeoutSec: Long = 4L): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val client = app.baseClient.newBuilder()
                    .callTimeout(timeoutSec * 2L, TimeUnit.SECONDS)
                    .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                    .readTimeout(timeoutSec, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body.byteStream().use { stream ->
                        readBoundedBytes(stream, MAX_INFLATE_BYTES)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    // =========================================================================
    // 4. Keyless Human Urdu Harvesters (SubDL, SubSense, Subf2m)
    // =========================================================================

    /**
     * Maps a 1-indexed season number (`1..20`) to SubDL's URL slug ordinal
     * (`first-season`, `second-season`, ..., `twentieth-season`).
     */
    fun subDlSeasonOrdinal(season: Int): String? = SEASON_ORDINALS[season]

    /**
     * Parses SubDL's autocomplete JSON response (`{"results":[{"link":"/subtitle/sd12345/slug", ...}]}`)
     * and returns the matching `/subtitle/sd{id}/{slug}` path.
     */
    internal fun parseSubDlAutoSlug(jsonBody: String, expectedYear: Int? = null): String? {
        return try {
            val root = JSONObject(jsonBody)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            var firstLink: String? = null
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val link = item.optString("link").trim()
                if (!link.startsWith("/subtitle/sd")) continue
                if (firstLink == null) firstLink = link
                val year = item.optInt("year", 0)
                if (expectedYear != null && year > 0 && kotlin.math.abs(year - expectedYear) <= 1) {
                    return link.removeSuffix("/")
                }
            }
            firstLink?.removeSuffix("/")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts direct `https://dl.subdl.com/subtitle/\d+-\d+\.zip` download URLs from a SubDL
     * `/urdu` HTML page (including Next.js `__NEXT_DATA__` JSON and anchor tags).
     * For TV episodes (`episode != null`), filters surrounding release/row text for episode match
     * (`S01E02`, `E02`, or season pack).
     */
    internal fun parseSubDlUrduPageZips(
        htmlBody: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (htmlBody.isBlank()) return emptyList()
        val results = LinkedHashSet<String>()
        val zipRegex = Regex("""https?://dl\.subdl\.com/subtitle/\d+-\d+\.zip|/subtitle/\d+-\d+\.zip""", RegexOption.IGNORE_CASE)

        val doc = Jsoup.parse(htmlBody)
        // 1. Anchor tags with surrounding row/container text
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            val match = zipRegex.find(href) ?: continue
            val fullUrl = if (match.value.startsWith("http", ignoreCase = true)) {
                match.value
            } else {
                "$SUBDL_DL_HOST${match.value}"
            }
            val selfAndParentText = buildString {
                append(anchor.text()).append(' ')
                append(anchor.attr("title")).append(' ')
                anchor.parent()?.takeIf { it.tagName().lowercase(Locale.ROOT) !in setOf("body", "html") }
                    ?.text()?.let { append(it) }
            }.trim()
            val contextText = if (selfAndParentText.length > 12) {
                selfAndParentText
            } else {
                buildString {
                    append(selfAndParentText).append(' ')
                    anchor.parent()?.parent()
                        ?.takeIf { it.tagName().lowercase(Locale.ROOT) !in setOf("body", "html") }
                        ?.text()?.let { append(it) }
                }.trim()
            }
            if (subDlEpisodeMatches(contextText, season, episode)) {
                results.add(fullUrl)
            }
        }

        // 2. Also scan raw HTML / __NEXT_DATA__ JSON for dl.subdl.com/subtitle/\d+-\d+\.zip
        if (results.isEmpty()) {
            for (match in zipRegex.findAll(htmlBody)) {
                val raw = match.value
                val fullUrl = if (raw.startsWith("http", ignoreCase = true)) raw else "$SUBDL_DL_HOST$raw"
                if (episode == null || episode <= 0) {
                    results.add(fullUrl)
                } else {
                    val start = (match.range.first - 300).coerceAtLeast(0)
                    val end = (match.range.last + 300).coerceAtMost(htmlBody.length)
                    val window = htmlBody.substring(start, end)
                    if (subDlEpisodeMatches(window, season, episode)) {
                        results.add(fullUrl)
                    }
                }
            }
        }

        return results.take(5).toList()
    }

    internal fun subDlEpisodeMatches(text: String, season: Int?, episode: Int?): Boolean {
        if (episode == null || episode <= 0) return true
        if (text.isBlank()) return true
        val normalized = text.lowercase(Locale.ROOT)
        if (StreamPlayExtractor.subtitleCatEpisodeMatches(normalized, season, episode)) return true
        // Accept full season packs on season-scoped SubDL pages
        if (normalized.contains("complete") || normalized.contains("full season") || normalized.contains("season pack")) {
            return true
        }
        return false
    }

    /**
     * Keyless SubDL Urdu Harvester (`invokeSubDlUrduKeyless`):
     * Resolves the SubDL slug via `https://api3.subdl.com/auto?query=...`, fetches the `/urdu`
     * (or `/${ordinal}-season/urdu`) page, and emits `.zip` Urdu subtitle links.
     */
    suspend fun invokeSubDlUrduKeyless(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val query = cleanImdb ?: cleanTitle ?: return

        try {
            val autoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(query, "UTF-8")}"
            val headers = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Origin" to SUBDL_WEB_HOST,
                "Referer" to "$SUBDL_WEB_HOST/"
            )
            val autoBody = withTimeoutOrNull(5_000L) {
                StreamPlayExtractor.cancellableGetText(autoUrl, headers = headers, timeoutSec = 5L)
            }
            var slugLink = autoBody?.let { parseSubDlAutoSlug(it, year) }
            if (slugLink == null && cleanImdb != null && cleanTitle != null) {
                val fallbackAutoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                val fallbackBody = withTimeoutOrNull(5_000L) {
                    StreamPlayExtractor.cancellableGetText(fallbackAutoUrl, headers = headers, timeoutSec = 5L)
                }
                slugLink = fallbackBody?.let { parseSubDlAutoSlug(it, year) }
            }
            if (slugLink.isNullOrBlank()) return

            val pageUrl = if (season != null && season > 0) {
                val ordinal = subDlSeasonOrdinal(season) ?: return
                "$SUBDL_WEB_HOST$slugLink/$ordinal/urdu"
            } else {
                "$SUBDL_WEB_HOST$slugLink/urdu"
            }

            val html = withTimeoutOrNull(6_000L) {
                StreamPlayExtractor.cancellableGetText(pageUrl, headers = headers, timeoutSec = 5L)
            } ?: return

            val zipUrls = parseSubDlUrduPageZips(html, season, episode)
            for (zipUrl in zipUrls) {
                subtitleCallback(
                    newSubtitleFile("Urdu", zipUrl) {
                        this.headers = mapOf(
                            "User-Agent" to BROWSER_USER_AGENT,
                            "Referer" to "$SUBDL_WEB_HOST/",
                            "X-StreamPlay-Source" to "subdl"
                        )
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubDlUrduKeyless failed: ${e.message}")
        }
    }

    /**
     * Parses SubSense Stremio JSON payload and extracts Urdu subtitle URLs where `lang` is
     * `"ur"`, `"urd"`, or `"urdu"`.
     */
    internal fun parseSubSenseUrduJson(jsonBody: String): List<String> {
        if (jsonBody.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonBody)
            val arr = root.optJSONArray("subtitles") ?: return emptyList()
            val urls = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lang = obj.optString("lang").trim().lowercase(Locale.ROOT)
                val url = obj.optString("url").trim()
                if (url.startsWith("http", ignoreCase = true) &&
                    (lang == "ur" || lang == "urd" || lang == "urdu" || lang.startsWith("ur-"))
                ) {
                    urls.add(url)
                }
            }
            urls.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Keyless SubSense Urdu Harvester (`invokeSubSenseUrdu`):
     * Queries `https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles/$type/$stremioId.json`
     * and emits verified Urdu subtitles with `X-StreamPlay-Source: subsense`.
     */
    suspend fun invokeSubSenseUrdu(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return
        try {
            val url = if (season == null || season <= 0) {
                "$SUBSENSE_BASE/movie/$cleanId.json"
            } else {
                "$SUBSENSE_BASE/series/$cleanId:$season:${episode ?: 1}.json"
            }
            val headers = mapOf("User-Agent" to BROWSER_USER_AGENT)
            val body = withTimeoutOrNull(6_000L) {
                StreamPlayExtractor.cancellableGetText(url, headers = headers, timeoutSec = 5L)
            } ?: return

            for (subUrl in parseSubSenseUrduJson(body)) {
                subtitleCallback(
                    newSubtitleFile("Urdu", subUrl) {
                        this.headers = mapOf(
                            "User-Agent" to BROWSER_USER_AGENT,
                            "X-StreamPlay-Source" to "subsense"
                        )
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubSenseUrdu failed: ${e.message}")
        }
    }

    /**
     * Parses Subf2m search results HTML and returns the `/subtitles/{slug}` path only when the
     * normalized title (and release year for movies) matches.
     */
    internal fun parseSubf2mSearchSlug(
        htmlBody: String,
        expectedTitle: String,
        expectedYear: Int? = null,
        isMovie: Boolean = true
    ): String? {
        if (htmlBody.isBlank() || expectedTitle.isBlank()) return null
        val wantedTitle = StreamPlayExtractor.normalizeSubtitleReleaseText(expectedTitle)
        if (wantedTitle.isBlank()) return null

        val doc = Jsoup.parse(htmlBody)
        for (anchor in doc.select("a[href^=/subtitles/]")) {
            val href = anchor.attr("href").trim().removeSuffix("/")
            val segments = href.trimStart('/').split('/')
            if (segments.size != 2 || segments[0] != "subtitles") continue
            val slug = segments[1]
            if (slug.equals("searchbytitle", ignoreCase = true)) continue

            val rawText = anchor.text().trim()
            val titlePart = rawText.replace(Regex("""\(\d{4}\)\s*$"""), "").trim()
            val normalizedCandidate = StreamPlayExtractor.normalizeSubtitleReleaseText(titlePart)
            if (normalizedCandidate != wantedTitle && !normalizedCandidate.startsWith("$wantedTitle ")) continue

            if (isMovie && expectedYear != null) {
                val yearMatch = Regex("""\((\d{4})\)""").find(rawText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: StreamPlayExtractor.subtitleCatYearOf(rawText)
                if (yearMatch != null && kotlin.math.abs(yearMatch - expectedYear) > 1) continue
            }
            return slug
        }
        return null
    }

    internal fun parseSubf2mUrduDetailLinks(
        htmlBody: String,
        slug: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (htmlBody.isBlank()) return emptyList()
        val doc = Jsoup.parse(htmlBody)
        val links = LinkedHashSet<String>()
        val prefix = "/subtitles/$slug/urdu/"
        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (!href.contains(prefix, ignoreCase = true)) continue
            val rowText = anchor.parent()?.parent()?.text() ?: anchor.parent()?.text() ?: anchor.text()
            if (episode != null && episode > 0) {
                val normalized = StreamPlayExtractor.normalizeSubtitleReleaseText(rowText)
                if (!StreamPlayExtractor.subtitleCatEpisodeMatches(normalized, season, episode)) continue
            }
            val fullUrl = if (href.startsWith("http", ignoreCase = true)) href else "$SUBF2M_HOST/${href.removePrefix("/")}"
            links.add(fullUrl)
        }
        return links.take(2).toList()
    }

    internal fun parseSubf2mDownloadUrl(detailHtml: String): String? {
        if (detailHtml.isBlank()) return null
        val doc = Jsoup.parse(detailHtml)
        val href = doc.selectFirst("a#downloadButton[href], a.download[href*=/subtitles/]")
            ?.attr("href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return if (href.startsWith("http", ignoreCase = true)) href else "$SUBF2M_HOST/${href.removePrefix("/")}"
    }

    /**
     * Keyless Subf2m Urdu Harvester (`invokeSubf2mUrdu`):
     * Searches `https://subf2m.co/subtitles/searchbytitle?query=...`, verifies exact normalized
     * title (and year for movies), fetches `/subtitles/${slug}/urdu`, resolves top detail page
     * `<a id="downloadButton" href="...">`, and emits `Urdu` with `X-StreamPlay-Source: subf2m`.
     */
    suspend fun invokeSubf2mUrdu(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        isMovie: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return
        try {
            val searchUrl = "$SUBF2M_HOST/subtitles/searchbytitle?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
            val headers = mapOf("User-Agent" to BROWSER_USER_AGENT)
            val searchHtml = withTimeoutOrNull(5_000L) {
                StreamPlayExtractor.cancellableGetText(searchUrl, headers = headers, timeoutSec = 5L)
            } ?: return

            val slug = parseSubf2mSearchSlug(searchHtml, cleanTitle, year, isMovie) ?: return
            val urduListUrl = "$SUBF2M_HOST/subtitles/$slug/urdu"
            val listHtml = withTimeoutOrNull(5_000L) {
                StreamPlayExtractor.cancellableGetText(urduListUrl, headers = headers, timeoutSec = 5L)
            } ?: return

            val detailUrls = parseSubf2mUrduDetailLinks(listHtml, slug, season, episode)
            for (detailUrl in detailUrls) {
                val detailHtml = withTimeoutOrNull(5_000L) {
                    StreamPlayExtractor.cancellableGetText(detailUrl, headers = headers, timeoutSec = 5L)
                } ?: continue
                val dlUrl = parseSubf2mDownloadUrl(detailHtml) ?: continue
                subtitleCallback(
                    newSubtitleFile("Urdu", dlUrl) {
                        this.headers = mapOf(
                            "User-Agent" to BROWSER_USER_AGENT,
                            "Referer" to detailUrl,
                            "X-StreamPlay-Source" to "subf2m"
                        )
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubf2mUrdu failed: ${e.message}")
        }
    }
}
