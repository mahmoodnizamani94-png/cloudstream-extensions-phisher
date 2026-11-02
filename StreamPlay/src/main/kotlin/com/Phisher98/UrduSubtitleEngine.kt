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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 * 1. Human Urdu subtitles from SubDL, SubSource, SubSense, Subf2m, OpenSubtitles, YTS-Subs, and SubtitleCat
 *    are harvested concurrently without API keys (with optional API v1 acceleration for SubDL and SubSource).
 * 2. Legacy Urdu encodings (`Windows-1256` / `CP1256`, `UTF-16LE/BE`, BOM-prefixed `UTF-8`),
 *    compressed payloads (`GZIP`, multi-entry `ZIP` with season/episode-aware selection),
 *    Arabic-vs-Urdu glyph variants (`\u064A`/`\u0649` -> `\u06CC`, `\u0643` -> `\u06A9`,
 *    `\u0647`/`\u06D5` -> `\u06C1`, `\u06D3` -> `\u06D2`, presentation forms NFKC decomposition),
 *    and mixed-script RTL punctuation order (`\u200F` RLM) are normalized deterministically.
 * 3. When no human Urdu subtitle exists for a movie or episode, the highest-priority English `.srt`/`.vtt`
 *    is translated into timing-preserved Urdu via batched neural translation (`250` cues/batch with 50-cue
 *    bisect retry and inline tag protection, 1-to-1 cue mapping with zero timestamp drift) and served
 *    via local `.srt` cache (`file:///...`) or real SubtitleCat CDN folders.
 */
object UrduSubtitleEngine {

    private const val TAG = "UrduSubtitleEngine"
    private const val MAX_INFLATE_BYTES = 8 * 1024 * 1024 // 8 MiB safety ceiling
    private const val TRANSLATION_BATCH_SIZE = 250
    private const val TRANSLATION_SUB_BATCH_SIZE = 50
    private const val RLM = '\u200F'

    const val SUBTITLE_MODE_PREFS_NAME = "cloudstream_subtitle_mode_prefs"
    const val STREAMPLAY_PREFS_NAME = "StreamPlay"
    const val SUPERSTREAM_PREFS_NAME = "SuperStream"
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
     * 3. `StreamPlay` / `SuperStream` `sharedPref` (`"subtitle_mode"`).
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

        // 2. Host/Extension shared mode preferences ("cloudstream_subtitle_mode_prefs", "StreamPlay", "SuperStream")
        val ctx = context?.applicationContext ?: context ?: appContextRef
        if (ctx != null) {
            for (prefName in listOf(SUBTITLE_MODE_PREFS_NAME, STREAMPLAY_PREFS_NAME, SUPERSTREAM_PREFS_NAME)) {
                val sharedModeStr = runCatching {
                    ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                        ?.getString(SUBTITLE_MODE_KEY, null)
                }.getOrNull()
                SubtitleMode.fromId(sharedModeStr)?.let { return it }
            }
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
     * 2. Persists `"subtitle_mode"` in `sharedPref`, `"StreamPlay"`, `"SuperStream"`, and `"cloudstream_subtitle_mode_prefs"`.
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
            for (prefName in listOf(SUBTITLE_MODE_PREFS_NAME, STREAMPLAY_PREFS_NAME, SUPERSTREAM_PREFS_NAME)) {
                runCatching {
                    ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(SUBTITLE_MODE_KEY, mode.id)
                        ?.apply()
                }
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
    private const val SUBDL_API_V1 = "https://api.subdl.com/api/v1/subtitles"
    private const val SUBDL_WEB_HOST = "https://subdl.com"
    private const val SUBDL_DL_HOST = "https://dl.subdl.com"

    private const val SUBSOURCE_API_HOST = "https://api.subsource.net"
    private const val SUBSOURCE_API_V1 = "https://api.subsource.net/api/v1"

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

    private val ARABIC_PRESENTATION_FORMS_REGEX =
        Regex("""[\uFB50-\uFDFF\uFE70-\uFEFF]+""")

    private val ASS_TAG_REGEX = Regex("""\{\\[^}]*\}""")
    private val HTML_TAG_REGEX =
        Regex("""</?(?:font|b|i|u|s|span|div|p|br|v|c|lang|ruby|rt)(?:\b|[.\s])[^>]*>""", RegexOption.IGNORE_CASE)
    private val VTT_KARAOKE_TAG_REGEX =
        Regex("""<\d{1,2}:\d{2}(?::\d{2})?[,.]\d{1,3}>""")

    private val EXPLICIT_EPISODE_TOKEN_REGEX = Regex(
        """(?i)(?:^|[^a-z0-9])(?:s\d{1,2}[\s._-]*e\d{1,4}|\d{1,2}x\d{1,4}|(?:e|ep|episode)[\s._-]*\d{1,4})(?!\d)"""
    )

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
        "subsource.net",
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

    // Cache of materialized human Urdu subtitle file:/// URIs keyed by "$url|S${season ?: 0}E${episode ?: 0}"
    private val materializedUrduCache = ConcurrentHashMap<String, String>()

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

    internal fun clearCachesForTest() {
        synthesizedUrduCache.clear()
        materializedUrduCache.clear()
        pendingSubtitleCatOrigList.clear()
    }

    // =========================================================================
    // 1. Archive Inflation, Encoding Detection & Urdu Typography Normalization
    // =========================================================================

    /**
     * Safely inflates GZIP (`0x1F 0x8B`) or ZIP (`0x50 0x4B 0x03 0x04`) subtitle archives
     * up to [MAX_INFLATE_BYTES] (8 MiB), selecting the best `.srt`/`.vtt`/`.ass`/`.ssa` entry
     * inside ZIPs (with season/episode matching for TV packs) while skipping `.nfo`, `.txt`, and directories.
     */
    fun inflateArchiveIfNeeded(
        rawBytes: ByteArray,
        season: Int? = null,
        episode: Int? = null
    ): ByteArray {
        if (rawBytes.size < 4) return rawBytes
        val b0 = rawBytes[0].toInt() and 0xFF
        val b1 = rawBytes[1].toInt() and 0xFF
        val b2 = rawBytes[2].toInt() and 0xFF
        val b3 = rawBytes[3].toInt() and 0xFF

        // GZIP magic: 1F 8B
        if (b0 == 0x1F && b1 == 0x8B) {
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    readBoundedBytes(gis, MAX_INFLATE_BYTES)
                }
            }.getOrDefault(rawBytes)
        }

        // ZIP magic: 50 4B 03 04
        if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
            return runCatching {
                extractBestSubtitleFromZip(rawBytes, season, episode) ?: ByteArray(0)
            }.getOrDefault(ByteArray(0))
        }

        return rawBytes
    }

    internal fun extractBestSubtitleFromZip(
        zipBytes: ByteArray,
        season: Int? = null,
        episode: Int? = null
    ): ByteArray? {
        var bestBytes: ByteArray? = null
        var bestScore = -1

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = runCatching { zis.nextEntry }.getOrNull() ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val rawEntryName = entry.name
                val name = rawEntryName.lowercase(Locale.ROOT)
                val baseName = name.substringAfterLast('/')
                if (baseName.startsWith(".") || baseName.startsWith("__macosx") || name.contains("__macosx/")) {
                    zis.closeEntry()
                    continue
                }
                if (baseName.endsWith(".nfo") || baseName.endsWith(".txt") ||
                    baseName.endsWith(".jpg") || baseName.endsWith(".png") ||
                    baseName.endsWith(".url") || baseName.endsWith(".exe") ||
                    baseName.endsWith(".db") || baseName.endsWith(".ini")
                ) {
                    zis.closeEntry()
                    continue
                }

                var episodeBonus = 0
                if (episode != null && episode > 0) {
                    val normBase = StreamPlayExtractor.normalizeSubtitleReleaseText(baseName)
                    val normFull = StreamPlayExtractor.normalizeSubtitleReleaseText(rawEntryName)
                    val baseHasEpToken = EXPLICIT_EPISODE_TOKEN_REGEX.containsMatchIn(baseName)
                    val fullHasEpToken = EXPLICIT_EPISODE_TOKEN_REGEX.containsMatchIn(rawEntryName)
                    val baseMatches = StreamPlayExtractor.subtitleCatEpisodeMatches(normBase, season, episode)
                    val fullMatches = !baseHasEpToken && StreamPlayExtractor.subtitleCatEpisodeMatches(normFull, season, episode)
                    val matchesRequestedEpisode = baseMatches || fullMatches

                    if (!matchesRequestedEpisode && (baseHasEpToken || fullHasEpToken)) {
                        // Entry explicitly targets a different episode (e.g. S01E01 when S01E02 was requested): reject!
                        zis.closeEntry()
                        continue
                    }
                    if (matchesRequestedEpisode || subDlEpisodeMatches(rawEntryName, season, episode)) {
                        if (matchesRequestedEpisode) {
                            episodeBonus = 500
                        }
                    }
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

                    val score = episodeBonus + extScore + langBonus + (unpacked.size.coerceAtMost(500_000) / 10_000)
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
     * and normalizes Arabic characters (`\u064A`/`\u0649` -> `\u06CC`, `\u0643` -> `\u06A9`,
     * `\u0647`/`\u06D5` -> `\u06C1`, `\u06D3` -> `\u06D2`, presentation forms NFKC)
     * into canonical Urdu NFC Unicode.
     */
    fun decodeAndNormalizeUrduBytes(
        rawBytes: ByteArray,
        season: Int? = null,
        episode: Int? = null
    ): String {
        if (rawBytes.isEmpty()) return ""
        val bytes = inflateArchiveIfNeeded(rawBytes, season = season, episode = episode)
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

        // Fallback to Windows-1252 (for English/Latin subtitles with smart quotes or accents) if high bytes exist, else UTF-8
        val cp1252 = runCatching { Charset.forName("windows-1252") }.getOrNull()
            ?: runCatching { Charset.forName("Cp1252") }.getOrNull()
            ?: StandardCharsets.ISO_8859_1
        return if (bytes.any { (it.toInt() and 0xFF) >= 0x80 }) {
            String(bytes, cp1252)
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
        var asciiLatinCount = 0
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u in 0x41..0x5A || u in 0x61..0x7A) {
                asciiLatinCount++
            } else if (u >= 0x80) {
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
        // Disambiguate from Windows-1252 / ISO-8859-1 English/Latin subtitles that have occasional
        // smart quotes (0x92, 0x93, 0x94) or accented Latin characters (café 0xE9, naïve 0xEF).
        if (asciiLatinCount > highByteCount * 4) return false
        return urduSpecificCount > 0 || (arabicRangeCount * 2 >= highByteCount)
    }

    /**
     * Normalizes Arabic script variants and Presentation Forms to canonical Urdu code points:
     * - Arabic Presentation Forms (`\uFB50-\uFDFF`, `\uFE70-\uFEFF`) -> NFKC decomposed
     * - Arabic Yeh (`\u064A` ي) & Alef Maksura (`\u0649` ى) -> Urdu Farsi Yeh (`\u06CC` ی)
     * - Arabic Kaf (`\u0643` ك) -> Urdu Keheh (`\u06A9` ک)
     * - Arabic Heh (`\u0647` ه) & Ae (`\u06D5` ە) -> Urdu Goal Heh (`\u06C1` ہ)
     * - Yeh Barree with Hamza (`\u06D3` ۓ) -> Urdu Yeh Barree (`\u06D2` ے)
     * - Strips BOM (`\uFEFF`) and normalizes line endings to `\n` + Unicode NFC.
     */
    fun normalizeUrduTypography(input: String): String {
        if (input.isEmpty()) return ""
        val preCleaned = input
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u06D3', '\u06D2')
        val decomposed = if (ARABIC_PRESENTATION_FORMS_REGEX.containsMatchIn(preCleaned)) {
            ARABIC_PRESENTATION_FORMS_REGEX.replace(preCleaned) { match ->
                Normalizer.normalize(match.value, Normalizer.Form.NFKC)
            }
        } else {
            preCleaned
        }
        val cleaned = decomposed
            .replace('\u064A', '\u06CC') // Arabic Yeh -> Urdu Yeh
            .replace('\u0649', '\u06CC') // Alef Maksura -> Urdu Yeh
            .replace('\u0643', '\u06A9') // Arabic Kaf -> Urdu Keheh
            .replace('\u0647', '\u06C1') // Arabic Heh -> Urdu Goal Heh
            .replace('\u06D5', '\u06C1') // Arabic Ae -> Urdu Goal Heh
            .replace('\u06D3', '\u06D2') // Yeh Barree with Hamza -> Urdu Yeh Barree
        return Normalizer.normalize(cleaned, Normalizer.Form.NFC)
    }

    internal fun decodeSubtitleHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&#160;", " ")
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&#60;", "<")
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&#62;", ">")
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&#38;", "&")
    }

    fun isUrduLanguageTag(raw: String?): Boolean = isUrduSubtitleLabel(raw)

    /**
     * Sanitizes a subtitle cue and applies stateless Right-to-Left Marks (`\u200F` RLM):
     * - Preserves inline formatting tags (`<i>`, `</i>`, `<b>`, `</b>`, `<u>`, `</u>`, `<s>`, `</s>`, `{\an1}`..`{\an9}`, `♪`, `♫`)
     *   while stripping unsupported ASS override tags (`{\pos(x,y)}`, etc.), VTT voice/class/karaoke tags, and HTML `<font>`/style tags.
     * - Decodes HTML entities (`&nbsp;`, `&amp;`, `&quot;`, `&#39;`, `&apos;`, `&lt;`, `&gt;`).
     * - Drops spam/watermark credit cues (`@iamdesigora`, `black gold`, `subtitles by`, `downloaded from`, etc.).
     * - Normalizes Urdu typography (`ی`, `ک`, `ہ`, `ے`).
     * - Wraps every line containing Urdu/Arabic script characters with `\u200F` (`"\u200F$stripped\u200F"`)
     *   so ExoPlayer renders mixed English/numbers and trailing punctuation (`.`, `?`, `!`) in true RTL order.
     */
    fun sanitizeAndApplyRtlBidi(cueText: String): String {
        if (cueText.isBlank()) return ""
        val normalized = normalizeUrduTypography(cueText)

        // Protect supported inline tags before stripping unsupported ASS, VTT karaoke/voice/class, and HTML tags
        val (protectedText, inlineTags) = protectSubtitleTags(normalized)
        val strippedProtected = decodeSubtitleHtmlEntities(
            protectedText
                .replace(ASS_TAG_REGEX, "")
                .replace(VTT_KARAOKE_TAG_REGEX, "")
                .replace(HTML_TAG_REGEX, "")
        ).trim()

        val withoutTags = restoreSubtitleTags(strippedProtected, inlineTags)
        if (withoutTags.isEmpty()) return ""

        // Check if entire cue is a watermark/credit cue
        val plainForCheck = STRAY_TAG_PLACEHOLDER_REGEX.replace(strippedProtected, "").trim()
        if (plainForCheck.isEmpty() || isWatermarkCue(plainForCheck) || isWatermarkCue(withoutTags)) return ""

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

    /**
     * Normalizes an entire Urdu subtitle document (`.srt` or `.vtt`):
     * Parses structured cues when present and formats them as canonical RTL Urdu `.srt`
     * (filtering watermark cues and wrapping Urdu lines in `\u200F` RLM); otherwise normalizes line-by-line.
     */
    fun normalizeUrduSubtitleText(rawText: String): String {
        if (rawText.isBlank()) return ""
        val normalizedRaw = normalizeUrduTypography(rawText)
        val cues = parseSubtitleCues(normalizedRaw)
        if (cues.isNotEmpty()) {
            return formatCuesAsUrduSrt(cues)
        }
        return normalizedRaw
            .lineSequence()
            .map { line -> sanitizeAndApplyRtlBidi(line) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
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

    private const val FLEXIBLE_TIME_TOKEN = """(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?"""

    private val SRT_TIMESTAMP_REGEX = Regex(
        """^\s*($FLEXIBLE_TIME_TOKEN)\s*-->\s*($FLEXIBLE_TIME_TOKEN)(.*)$"""
    )

    private val VTT_METADATA_BLOCK_REGEX = Regex(
        """^(?:NOTE|STYLE|REGION)(?:\s.*)?$""",
        RegexOption.IGNORE_CASE
    )

    private val PROTECTED_SUBTITLE_TOKEN_REGEX = Regex(
        """(?i)</?(?:i|b|u|s)\b[^>]*>|\{\\an[1-9]\}|[♪♫]+"""
    )

    private val RESTORE_TAG_PLACEHOLDER_REGEX = Regex(
        """(?:\u3016|\u3010|\[)\s*[\u200E\u200F]*(\d+)[\u200E\u200F]*\s*(?:\u3017|\u3011|\])"""
    )

    private val STRAY_TAG_PLACEHOLDER_REGEX = Regex(
        """(?:\u3016|\u3010)\s*[\u200E\u200F]*\d+[\u200E\u200F]*\s*(?:\u3017|\u3011)"""
    )

    private val translationBatchSemaphore = Semaphore(4)

    /**
     * Protects inline formatting tags (`<i>`, `</i>`, `<b>`, `</b>`, `<u>`, `</u>`, `<s>`, `</s>`, `{\an1}`..`{\an9}`, `♪`, `♫`)
     * with fullwidth lenticular bracket placeholders (`〖0〗`, `〖1〗`, ...) before tag stripping or neural translation.
     */
    internal fun protectSubtitleTags(text: String): Pair<String, List<String>> {
        if (text.isEmpty()) return "" to emptyList()
        val tags = mutableListOf<String>()
        val protectedText = PROTECTED_SUBTITLE_TOKEN_REGEX.replace(text) { match ->
            val idx = tags.size
            tags.add(match.value)
            "\u3016$idx\u3017"
        }
        return protectedText to tags
    }

    /**
     * Restores inline formatting tags from placeholders (`〖0〗`, `【0】`, `[0]`, with optional spaces
     * or BiDi marks inserted by translation engines) and cleans up any stray unreferenced `〖\d+〗` tokens.
     */
    internal fun restoreSubtitleTags(translated: String, tags: List<String>): String {
        if (translated.isEmpty()) return ""
        val restored = if (tags.isEmpty()) {
            translated
        } else {
            RESTORE_TAG_PLACEHOLDER_REGEX.replace(translated) { match ->
                val idx = match.groupValues[1].toIntOrNull()
                if (idx != null && idx in tags.indices) {
                    tags[idx]
                } else if (match.value.startsWith("[") && match.value.endsWith("]")) {
                    match.value
                } else {
                    ""
                }
            }
        }
        return STRAY_TAG_PLACEHOLDER_REGEX.replace(restored, "").trim()
    }

    /**
     * Parses either SubRip (`.srt`) or WebVTT (`.vtt`) content into structured [SrtCue]s,
     * skipping multi-line `NOTE`/`STYLE`/`REGION` blocks, preserving inline formatting tags
     * (`<i>`, `<b>`, `<u>`, `<s>`, `{\an1}`..`{\an9}`, `♪`, `♫`) while stripping unsupported
     * ASS/VTT/HTML tags, decoding HTML entities, and normalizing timestamps to canonical SRT format.
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
            if (line.isEmpty()) {
                i++
                continue
            }
            if (VTT_METADATA_BLOCK_REGEX.matches(line)) {
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    i++
                }
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
                val (protectedLine, lineTags) = protectSubtitleTags(lines[i])
                val cleanedProtected = decodeSubtitleHtmlEntities(
                    protectedLine
                        .replace(ASS_TAG_REGEX, "")
                        .replace(VTT_KARAOKE_TAG_REGEX, "")
                        .replace(HTML_TAG_REGEX, "")
                ).trim()
                val cueLine = restoreSubtitleTags(cleanedProtected, lineTags)
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
        val mainAndMs = commaFixed.split(',', limit = 2)
        val timePart = mainAndMs[0]
        val msPart = if (mainAndMs.size > 1) {
            mainAndMs[1].padEnd(3, '0').take(3)
        } else {
            "000"
        }
        val parts = timePart.split(':')
        val hms = when (parts.size) {
            2 -> "00:${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}"
            3 -> "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}:${parts[2].padStart(2, '0')}"
            else -> timePart
        }
        return "$hms,$msPart"
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
     * by batching cues (`250` cues per batch, bounded by `Semaphore(4)`) in parallel via
     * `clients5.google.com` (`dict-chrome-ex`) with automatic fallback to `translate.google.com` (`client=at`).
     * If a 250-cue batch fails, bisects/splits into 50-cue sub-batches and retries, succeeding
     * as long as at least 60% of cues are translated.
     */
    suspend fun translateEnglishSrtToUrdu(
        englishSrtOrVtt: String,
        batchTranslator: (suspend (List<String>) -> List<String>?)? = null
    ): String? = coroutineScope {
        val cues = parseSubtitleCues(englishSrtOrVtt)
        if (cues.isEmpty()) return@coroutineScope null

        val translator: suspend (List<String>) -> List<String>? =
            batchTranslator ?: { batch -> translateBatchToUrdu(batch) }

        suspend fun translateChunk(chunkCues: List<SrtCue>): List<SrtCue>? {
            if (chunkCues.isEmpty()) return emptyList()
            val protectedPairs = chunkCues.map { protectSubtitleTags(it.text) }
            val sourceTexts = protectedPairs.map { it.first }
            val translatedTexts = translationBatchSemaphore.withPermit {
                translator(sourceTexts)
            }
            if (translatedTexts != null && translatedTexts.size == chunkCues.size) {
                return chunkCues.mapIndexed { idx, cue ->
                    val restored = restoreSubtitleTags(translatedTexts[idx], protectedPairs[idx].second)
                    cue.copy(text = restored)
                }
            }
            return null
        }

        val batches = cues.chunked(TRANSLATION_BATCH_SIZE)
        val translatedBatches = batches.map { batchCues ->
            async(Dispatchers.IO) {
                translateChunk(batchCues) ?: run {
                    if (batchCues.size <= 1) return@run emptyList<SrtCue>()
                    val subChunkSize = if (batchCues.size > TRANSLATION_SUB_BATCH_SIZE) {
                        TRANSLATION_SUB_BATCH_SIZE
                    } else {
                        maxOf(1, (batchCues.size + 1) / 2)
                    }
                    val subChunks = batchCues.chunked(subChunkSize)
                    val recovered = mutableListOf<SrtCue>()
                    for (sub in subChunks) {
                        val subRes = translateChunk(sub)
                        if (subRes != null) {
                            recovered.addAll(subRes)
                        }
                    }
                    recovered
                }
            }
        }.awaitAll()

        val mergedCues = translatedBatches.flatten()
        // Require at least 60% of cues to be translated
        if (mergedCues.isEmpty() || mergedCues.size * 100 < cues.size * 60) {
            return@coroutineScope null
        }
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

    private fun joinSentenceSegments(sentenceArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until sentenceArray.length()) {
            val elem = sentenceArray.opt(i)
            val seg = when (elem) {
                is JSONArray -> elem.optString(0)
                is String -> if (i == 0) elem else ""
                else -> ""
            }
            if (seg.isEmpty()) continue
            if (sb.isNotEmpty() && !sb.last().isWhitespace() && !seg.first().isWhitespace()) {
                sb.append(' ')
            }
            sb.append(seg)
        }
        return sb.toString()
    }

    private fun extractGoogleTranslateItemText(item: Any?): String {
        return when (item) {
            null, JSONObject.NULL -> ""
            is String -> item
            is JSONArray -> {
                if (item.length() == 0) return ""
                val first = item.opt(0)
                if (first is JSONArray) {
                    if (first.opt(0) is JSONArray) {
                        joinSentenceSegments(first)
                    } else {
                        joinSentenceSegments(item)
                    }
                } else {
                    item.optString(0)
                }
            }
            else -> item.toString()
        }
    }

    /**
     * Parses the JSON array response from `translate_a/t` (`["ur_1", "ur_2", ...]`,
     * nested `[["ur_1", "en_1"], ...]`, or single-cue multi-sentence
     * `[[["ہم کہاں ہیں؟","Where are we?"],["مجھے نہیں معلوم۔","I don't know."]],null,"en"]`).
     */
    internal fun parseGoogleBatchTranslateResponse(
        jsonBody: String,
        expectedCount: Int
    ): List<String>? {
        return try {
            val trimmed = jsonBody.trim()
            if (!trimmed.startsWith("[")) return null
            val arr = JSONArray(trimmed)
            if (expectedCount == 1 && arr.length() >= 1) {
                val first = arr.get(0)
                val text = when (first) {
                    is String -> first
                    is JSONArray -> {
                        if (first.opt(0) is JSONArray) {
                            joinSentenceSegments(first)
                        } else {
                            first.optString(0)
                        }
                    }
                    else -> first.toString()
                }
                return if (text.isNotEmpty()) listOf(text) else null
            }
            if (arr.length() != expectedCount) {
                return null
            }
            val result = ArrayList<String>(expectedCount)
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                val translated = extractGoogleTranslateItemText(item)
                result.add(translated)
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    // =========================================================================
    // 3. Delivery, Materialization & Caching (Local File Cache + SubtitleCat CDN)
    // =========================================================================

    private val REAL_SUBTITLECAT_FOLDER_REGEX = Regex("""^/subs/[^/]+/$""", RegexOption.IGNORE_CASE)

    /**
     * Publishes a translated Urdu `.srt` to SubtitleCat's permanent CDN via
     * `POST https://www.subtitlecat.com/upload_subtitles.php` and returns the resulting
     * `https://www.subtitlecat.com/subs/.../...-ur.srt` URL when successful.
     *
     * Never uploads to the synthetic `"/subs/synthesized/"` folder (which does not exist on
     * SubtitleCat and produces a 404 URL if `upload_subtitles.php` returns `"ok"`).
     */
    suspend fun publishToSubtitleCatCdn(
        origFilename: String,
        urduSrtContent: String,
        subFolder: String = "/subs/synthesized/"
    ): String? = withContext(Dispatchers.IO) {
        if (origFilename.isBlank() || urduSrtContent.isBlank()) return@withContext null
        val normalizedFolder = "/" + subFolder.trim('/').ifBlank { "subs" } + "/"
        if (normalizedFolder.equals("/subs/synthesized/", ignoreCase = true) ||
            !REAL_SUBTITLECAT_FOLDER_REGEX.matches(normalizedFolder)
        ) {
            return@withContext null
        }

        try {
            val cleanBaseName = origFilename.substringAfterLast('/')
                .removeSuffix("-orig.srt")
                .removeSuffix("-en.srt")
                .removeSuffix("-eng.srt")
                .removeSuffix(".srt")
                .trim()
                .ifBlank { "streamplay" }

            val targetFilename = "$cleanBaseName-ur.srt"

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
     * Verifies whether a cached `file://` or `file:` URI still points to an existing non-empty
     * subtitle file on disk.
     */
    internal fun isCachedSubtitleUriValid(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (uri.startsWith("file:", ignoreCase = true)) {
            val filePath = uri.removePrefix("file://").removePrefix("file:")
            val f = File(filePath)
            return f.exists() && f.isFile && f.length() > 16L
        }
        return uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
    }

    /**
     * Writes the normalized/translated Urdu `.srt` to the local cache directory as a canonical
     * triple-slash `file:///...` URI (so `StreamPlay.emitSubtitle`'s `startsWith("file://")` check
     * and ExoPlayer's `FileDataSource` both accept it unconditionally).
     */
    fun writeUrduLocalCacheFile(keySeed: String, urduSrtContent: String): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(keySeed.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val baseDir = appContextRef?.cacheDir
                ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!baseDir.exists()) baseDir.mkdirs()
            val file = File(baseDir, "streamplay_urdu_$digest.srt")
            // Include UTF-8 BOM so ExoPlayer / external players unconditionally detect UTF-8
            val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            val contentBytes = urduSrtContent.toByteArray(StandardCharsets.UTF_8)
            file.writeBytes(bomBytes + contentBytes)
            val absPath = file.absolutePath
            val normalizedPath = if (absPath.startsWith("/")) absPath else "/$absPath"
            "file://$normalizedPath"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads, extracts (season/episode-aware for `.zip`/`.gz` archives), decodes (`Windows-1256` /
     * `UTF-16` / `UTF-8`), normalizes Urdu typography + RTL BiDi, and materializes a human Urdu
     * subtitle URL into a local UTF-8-BOM `.srt` (`file:///...`).
     *
     * Fails closed (`null`) whenever download, decoding, or cue validation fails so dead or
     * corrupted URLs are never emitted to ExoPlayer.
     */
    suspend fun materializeNormalizedUrduSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
        sourceTag: String = "urdu-human"
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return@withContext null
        if (cleanUrl.startsWith("file:", ignoreCase = true)) {
            val canonicalFileUri = "file:///" + cleanUrl.substring(5).trimStart('/')
            return@withContext if (isCachedSubtitleUriValid(canonicalFileUri)) canonicalFileUri else null
        }

        val cacheKey = "$cleanUrl|S${season ?: 0}E${episode ?: 0}"
        materializedUrduCache[cacheKey]?.let { cachedUri ->
            if (isCachedSubtitleUriValid(cachedUri)) {
                return@withContext cachedUri
            } else {
                materializedUrduCache.remove(cacheKey)
            }
        }

        val rawBytes = fetchRawBytes(cleanUrl, headers = headers, timeoutSec = 8L)
            ?: return@withContext null
        if (rawBytes.isEmpty()) return@withContext null

        val decoded = decodeAndNormalizeUrduBytes(rawBytes, season = season, episode = episode)
        if (decoded.isBlank()) return@withContext null

        val normalized = normalizeUrduSubtitleText(decoded)
        if (parseSubtitleCues(normalized).isEmpty()) return@withContext null

        val localUri = writeUrduLocalCacheFile("$sourceTag|$cacheKey", normalized)
            ?: return@withContext null
        materializedUrduCache[cacheKey] = localUri
        localUri
    }

    /**
     * End-to-end neural Urdu fallback synthesis:
     * Fetches the best available English `.srt`/`.vtt`/`.gz`/`.zip` candidate (preferring
     * SubtitleCat `-orig.srt` candidates passed via [subtitleCatOrigCandidates], [englishSubtitleFiles] /
     * [candidateEnglishSubs] headers, or recorded in `pendingSubtitleCatOrigList`), translates it to
     * timing-preserved RTL Urdu, and emits `Urdu` with `X-StreamPlay-Source: urdu-neural-fallback`.
     */
    suspend fun synthesizeUrduFallbackFromEnglish(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        englishUrls: Collection<String> = emptyList(),
        englishSubtitleFiles: Collection<SubtitleFile> = emptyList(),
        candidateEnglishSubs: Collection<SubtitleFile> = emptyList(),
        subtitleCatOrigCandidates: List<Pair<String, String>> = emptyList(),
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val allSubtitleFiles = buildList {
            addAll(englishSubtitleFiles)
            addAll(candidateEnglishSubs)
        }
        val subCatOrigs = buildList<Pair<String, String>> {
            addAll(subtitleCatOrigCandidates)
            for (sf in allSubtitleFiles) {
                val origUrl = sf.headers?.entries
                    ?.firstOrNull { it.key.equals("X-SubtitleCat-Orig-Url", ignoreCase = true) }
                    ?.value?.trim()
                val origName = sf.headers?.entries
                    ?.firstOrNull { it.key.equals("X-SubtitleCat-Orig-Name", ignoreCase = true) }
                    ?.value?.trim()
                if (!origUrl.isNullOrBlank()) {
                    val name = if (!origName.isNullOrBlank()) origName else origUrl.substringAfterLast('/')
                    add(origUrl to name)
                }
            }
            addAll(consumeSubtitleCatOrigCandidates())
        }

        val orderedCandidates = buildList<Pair<String, String>> {
            subCatOrigs.forEach { (url, name) ->
                val clean = url.trim()
                if (clean.isNotBlank() && none { it.first == clean }) {
                    add(clean to name)
                }
            }
            allSubtitleFiles.forEach { sf ->
                val clean = sf.url.trim()
                if (clean.isNotBlank() && none { it.first == clean }) {
                    add(clean to clean.substringAfterLast('/').substringBefore('?').ifBlank { "english.srt" })
                }
            }
            englishUrls.forEach { url ->
                val clean = url.trim()
                if (clean.isNotBlank() && none { it.first == clean }) {
                    add(clean to clean.substringAfterLast('/').substringBefore('?').ifBlank { "english.srt" })
                }
            }
        }.take(3)

        if (orderedCandidates.isEmpty()) return@withContext false

        val imdbPrefix = imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { "$it|" }.orEmpty()
        for ((enUrl, origName) in orderedCandidates) {
            val cacheKey = if (season != null || episode != null || imdbPrefix.isNotEmpty()) {
                "$imdbPrefix$enUrl|S${season ?: 0}E${episode ?: 0}"
            } else {
                enUrl
            }
            val cachedUrl = synthesizedUrduCache[cacheKey] ?: synthesizedUrduCache[enUrl]
            if (cachedUrl != null) {
                if (isCachedSubtitleUriValid(cachedUrl)) {
                    subtitleCallback(
                        newSubtitleFile("Urdu", cachedUrl) {
                            headers = mapOf("X-StreamPlay-Source" to "urdu-neural-fallback")
                        }
                    )
                    return@withContext true
                } else {
                    synthesizedUrduCache.remove(cacheKey)
                    synthesizedUrduCache.remove(enUrl)
                }
            }

            val rawBytes = fetchRawBytes(enUrl, timeoutSec = 5L) ?: continue
            val decodedEnglish = decodeAndNormalizeUrduBytes(rawBytes, season = season, episode = episode)
            if (decodedEnglish.length < 32) continue

            val urduSrt = translateEnglishSrtToUrdu(decodedEnglish) ?: continue
            val subFolder = if (enUrl.contains("subtitlecat.com/subs/", ignoreCase = true)) {
                val path = enUrl.substringAfter("subtitlecat.com").substringBeforeLast('/') + "/"
                path
            } else {
                "/subs/synthesized/"
            }

            // Prefer local file:/// cache whenever writable so a dead 404 CDN URL is never emitted;
            // only fall back to SubtitleCat CDN when subFolder is a real SubtitleCat path.
            val localUrl = writeUrduLocalCacheFile(cacheKey, urduSrt)
            val cdnUrl = if (localUrl == null) {
                publishToSubtitleCatCdn(origName, urduSrt, subFolder)
            } else {
                null
            }
            val deliveryUrl = localUrl ?: cdnUrl ?: continue

            synthesizedUrduCache[cacheKey] = deliveryUrl
            synthesizedUrduCache[enUrl] = deliveryUrl
            subtitleCallback(
                newSubtitleFile("Urdu", deliveryUrl) {
                    headers = mapOf("X-StreamPlay-Source" to "urdu-neural-fallback")
                }
            )
            return@withContext true
        }
        false
    }

    internal suspend fun fetchRawBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 6L
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (url.startsWith("file:", ignoreCase = true)) {
            return@withContext runCatching {
                val path = url.removePrefix("file://").removePrefix("file:")
                val f = File(path)
                if (f.exists() && f.isFile) f.readBytes() else null
            }.getOrNull()
        }
        try {
            val client = app.baseClient.newBuilder()
                .callTimeout(timeoutSec * 2L, TimeUnit.SECONDS)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", headers["User-Agent"] ?: BROWSER_USER_AGENT)
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            client.newCall(reqBuilder.build()).execute().use { resp ->
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
    // 4. Human Urdu Harvesters (SubDL Keyless + API v1, SubSource API v1, SubSense, Subf2m)
    // =========================================================================

    /**
     * Maps a 1-indexed season number (`1..20`) to SubDL's URL slug ordinal
     * (`first-season`, `second-season`, ..., `twentieth-season`).
     */
    fun subDlSeasonOrdinal(season: Int): String? = SEASON_ORDINALS[season]

    /**
     * Parses SubDL's autocomplete JSON response (`{"results":[{"link":"/subtitle/sd12345/slug", ...}]}`)
     * and returns the matching `/subtitle/sd{id}/{slug}` path.
     *
     * When [expectedYear] is non-null, fails closed (`null`) if no item in `results` matches
     * `abs(year - expectedYear) <= 1`.
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
                if (expectedYear != null) {
                    if (year > 0 && kotlin.math.abs(year - expectedYear) <= 1) {
                        return link.removeSuffix("/")
                    }
                }
            }
            if (expectedYear != null) {
                null
            } else {
                firstLink?.removeSuffix("/")
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts a bounded context window around [matchRange] inside raw HTML or `__NEXT_DATA__` JSON,
     * stopping at the enclosing `{...}` JSON object boundaries or `<tr>`/`<li>`/`<div>` tags so
     * sibling JSON objects never bleed episode numbers across items.
     */
    internal fun extractBoundedSubDlContextWindow(text: String, matchRange: IntRange): String {
        val minStart = (matchRange.first - 320).coerceAtLeast(0)
        val maxEnd = (matchRange.last + 320).coerceAtMost(text.length)

        var start = matchRange.first
        var braceDepth = 0
        while (start > minStart) {
            val ch = text[start - 1]
            if (ch == '}') braceDepth++
            if (ch == '{') {
                if (braceDepth == 0) {
                    start--
                    break
                } else {
                    braceDepth--
                }
            }
            start--
        }

        var end = matchRange.last + 1
        braceDepth = 0
        while (end < maxEnd) {
            val ch = text[end]
            if (ch == '{') braceDepth++
            if (ch == '}') {
                if (braceDepth == 0) {
                    end++
                    break
                } else {
                    braceDepth--
                }
            }
            end++
        }

        return text.substring(start, end)
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
                    val window = extractBoundedSubDlContextWindow(htmlBody, match.range)
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
     * Parses Official SubDL API v1 JSON (`{"status":true,"subtitles":[{"url":"/subtitle/123-456.zip","lang":"urdu",...}]}`)
     * and returns direct `.zip` URLs matching [season] and [episode].
     */
    internal fun parseSubDlApiV1Zips(
        json: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            if (root.has("status") && !root.optBoolean("status", true)) return emptyList()
            val arr = root.optJSONArray("subtitles") ?: root.optJSONArray("data") ?: return emptyList()
            val urls = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val lang = (item.optString("lang").ifBlank { item.optString("language") })
                    .trim().lowercase(Locale.ROOT)
                if (lang.isNotBlank() && lang != "ur" && lang != "urd" && lang != "urdu" && !lang.startsWith("ur")) {
                    continue
                }
                val itemSeason = item.optInt("season", -1)
                val itemEpisode = item.optInt("episode", -1)
                val releaseName = buildString {
                    append(item.optString("release_name")).append(' ')
                    append(item.optString("name")).append(' ')
                    append(item.optString("title"))
                }.trim()

                if (season != null && season > 0 && itemSeason > 0 && itemSeason != season) {
                    continue
                }
                if (episode != null && episode > 0) {
                    val explicitEpMatches = itemEpisode == episode
                    val textMatches = releaseName.isNotBlank() && subDlEpisodeMatches(releaseName, season, episode)
                    if (itemEpisode > 0 && !explicitEpMatches && !textMatches) {
                        continue
                    }
                    if (itemEpisode <= 0 && releaseName.isNotBlank() && !textMatches) {
                        continue
                    }
                }

                val rawUrl = item.optString("url").ifBlank { item.optString("download_url") }.trim()
                if (rawUrl.isBlank()) continue
                val fullUrl = when {
                    rawUrl.startsWith("http", ignoreCase = true) -> rawUrl
                    rawUrl.startsWith("/") -> "$SUBDL_DL_HOST$rawUrl"
                    else -> "$SUBDL_DL_HOST/$rawUrl"
                }
                urls.add(fullUrl)
            }
            urls.take(5).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveSubDlApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            runCatching { sharedPref?.getString("subdl_api_key", null) }.getOrNull(),
            runCatching { StreamPlay.companionSharedPref?.getString("subdl_api_key", null) }.getOrNull(),
            runCatching { System.getProperty("subdl.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBDL_API_KEY") }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * Queries Official SubDL API v1 (`https://api.subdl.com/api/v1/subtitles`) when an API key
     * is available, materializes the resulting `.zip` into a normalized UTF-8-BOM `.srt`, and
     * returns the number of emitted subtitles.
     */
    suspend fun invokeSubDlApiV1Urdu(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Int {
        val resolvedKey = resolveSubDlApiKey(apiKey, sharedPref) ?: return 0
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null) return 0

        val type = if (season != null && season > 0) "tv" else "movie"
        val url = buildString {
            append("$SUBDL_API_V1?api_key=${URLEncoder.encode(resolvedKey, "UTF-8")}")
            append("&languages=UR&subs_per_page=10&type=$type")
            if (cleanImdb != null) {
                append("&imdb_id=${URLEncoder.encode(cleanImdb, "UTF-8")}")
            } else if (cleanTitle != null) {
                append("&film_name=${URLEncoder.encode(cleanTitle, "UTF-8")}")
            }
            if (season != null && season > 0) append("&season_number=$season")
            if (episode != null && episode > 0) append("&episode_number=$episode")
            if (year != null && year > 0) append("&year=$year")
        }

        val headers = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "application/json"
        )
        val json = withTimeoutOrNull(5_000L) {
            StreamPlayExtractor.cancellableGetText(url, headers = headers, timeoutSec = 5L)
        } ?: return 0

        val zipUrls = parseSubDlApiV1Zips(json, season, episode)
        var emitted = 0
        for (zipUrl in zipUrls) {
            val dlHeaders = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Referer" to "$SUBDL_WEB_HOST/",
                "X-StreamPlay-Source" to "subdl-api-v1"
            )
            val materializedUri = materializeNormalizedUrduSubtitle(
                url = zipUrl,
                headers = dlHeaders,
                season = season,
                episode = episode,
                sourceTag = "subdl-api-v1"
            ) ?: continue
            subtitleCallback(
                newSubtitleFile("Urdu", materializedUri) {
                    this.headers = dlHeaders
                }
            )
            emitted++
        }
        return emitted
    }

    /**
     * SubDL Urdu Harvester (`invokeSubDlUrduKeyless`):
     * 1. Queries Official SubDL API v1 first if an API key is configured.
     * 2. Seamlessly falls back to keyless `https://api3.subdl.com/auto?query=...` + `/urdu` page
     *    harvesting, and materializes `.zip` Urdu archives into playable UTF-8-BOM `.srt` files.
     */
    suspend fun invokeSubDlUrduKeyless(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val query = cleanImdb ?: cleanTitle ?: return

        try {
            // 1. Try Official SubDL API v1 first if key is available
            val apiEmitted = invokeSubDlApiV1Urdu(
                imdbId = cleanImdb,
                title = cleanTitle,
                season = season,
                episode = episode,
                year = year,
                apiKey = apiKey,
                sharedPref = sharedPref,
                subtitleCallback = subtitleCallback
            )
            if (apiEmitted > 0) return

            // 2. Keyless fallback via api3.subdl.com/auto + HTML/__NEXT_DATA__
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
                val dlHeaders = mapOf(
                    "User-Agent" to BROWSER_USER_AGENT,
                    "Referer" to "$SUBDL_WEB_HOST/",
                    "X-StreamPlay-Source" to "subdl"
                )
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = zipUrl,
                    headers = dlHeaders,
                    season = season,
                    episode = episode,
                    sourceTag = "subdl"
                ) ?: continue
                subtitleCallback(
                    newSubtitleFile("Urdu", materializedUri) {
                        this.headers = dlHeaders
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
     * Parses SubSource API v1 `/api/v1/movies/search` JSON response and extracts the matching
     * `movieId` / `id` for [imdbId], [title], [expectedYear], and [season].
     */
    internal fun parseSubSourceSearchMovieId(
        json: String,
        imdbId: String? = null,
        title: String? = null,
        expectedYear: Int? = null,
        season: Int? = null
    ): String? {
        if (json.isBlank()) return null
        return try {
            val trimmed = json.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: root.optJSONArray("movies")
                    ?: root.optJSONObject("data")?.let { JSONArray().put(it) }
                    ?: return null
            }
            if (arr.length() == 0) return null

            val cleanImdb = imdbId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.startsWith("tt") }
            val normalizedWantedTitle = title?.let { StreamPlayExtractor.normalizeSubtitleReleaseText(it) }
                ?.takeIf { it.isNotBlank() }

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = (
                    item.optString("movieId")
                        .ifBlank { item.optString("id") }
                        .ifBlank { item.optString("movie_id") }
                    ).trim()
                if (id.isBlank()) continue

                val itemImdb = (
                    item.optString("imdbId")
                        .ifBlank { item.optString("imdb_id") }
                        .ifBlank { item.optString("imdb") }
                    ).trim().lowercase(Locale.ROOT)
                val itemTitle = (
                    item.optString("title")
                        .ifBlank { item.optString("name") }
                        .ifBlank { item.optString("movieName") }
                    ).trim()
                val itemYear = item.optInt("releaseYear", item.optInt("year", 0))
                val itemSeason = item.optInt("season", 0)

                if (season != null && season > 0 && itemSeason > 0 && itemSeason != season) {
                    continue
                }

                if (cleanImdb != null && itemImdb.isNotBlank()) {
                    if (itemImdb != cleanImdb) continue
                    return id
                }

                if (normalizedWantedTitle != null && itemTitle.isNotBlank()) {
                    val normCandidate = StreamPlayExtractor.normalizeSubtitleReleaseText(itemTitle)
                    if (normCandidate != normalizedWantedTitle && !normCandidate.startsWith("$normalizedWantedTitle ")) {
                        continue
                    }
                }

                if (expectedYear != null && season == null) {
                    if (itemYear <= 0 || kotlin.math.abs(itemYear - expectedYear) > 1) {
                        continue
                    }
                }

                return id
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses SubSource API v1 `/api/v1/subtitles` JSON response and returns download URLs
     * (`https://api.subsource.net/api/v1/subtitles/{id}/download` or direct `downloadUrl`) for
     * Urdu subtitles matching [season] and [episode].
     */
    internal fun parseSubSourceSubtitlesList(
        json: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val trimmed = json.trim()
            val arr = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val root = JSONObject(trimmed)
                root.optJSONArray("data")
                    ?: root.optJSONArray("subtitles")
                    ?: root.optJSONArray("results")
                    ?: return emptyList()
            }
            val urls = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val lang = (
                    item.optString("language")
                        .ifBlank { item.optString("lang") }
                    ).trim().lowercase(Locale.ROOT)
                if (lang.isNotBlank() && lang != "urdu" && lang != "ur" && lang != "urd" && !lang.startsWith("ur")) {
                    continue
                }

                val releaseText = buildString {
                    append(item.optString("releaseInfo")).append(' ')
                    append(item.optString("release_info")).append(' ')
                    append(item.optString("releaseName")).append(' ')
                    append(item.optString("title")).append(' ')
                    val releasesArr = item.optJSONArray("releases")
                    if (releasesArr != null) {
                        for (r in 0 until releasesArr.length()) {
                            append(releasesArr.optString(r)).append(' ')
                        }
                    }
                }.trim()

                val itemSeason = item.optInt("season", -1)
                val itemEpisode = item.optInt("episode", -1)
                if (season != null && season > 0 && itemSeason > 0 && itemSeason != season) {
                    continue
                }
                if (episode != null && episode > 0) {
                    val explicitEpMatches = itemEpisode == episode
                    val textMatches = releaseText.isNotBlank() && subDlEpisodeMatches(releaseText, season, episode)
                    if (itemEpisode > 0 && !explicitEpMatches && !textMatches) {
                        continue
                    }
                    if (itemEpisode <= 0 && releaseText.isNotBlank() && !textMatches) {
                        continue
                    }
                }

                val directDownload = (
                    item.optString("downloadUrl")
                        .ifBlank { item.optString("download_url") }
                        .ifBlank { item.optString("url") }
                    ).trim()
                val subId = (
                    item.optString("subtitleId")
                        .ifBlank { item.optString("id") }
                        .ifBlank { item.optString("sub_id") }
                    ).trim()

                val resolvedUrl = when {
                    directDownload.startsWith("http", ignoreCase = true) -> directDownload
                    directDownload.startsWith("/") -> "$SUBSOURCE_API_HOST$directDownload"
                    subId.isNotBlank() -> "$SUBSOURCE_API_V1/subtitles/$subId/download"
                    else -> continue
                }
                urls.add(resolvedUrl)
            }
            urls.take(5).toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun resolveSubSourceApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            runCatching { sharedPref?.getString("subsource_api_key", null) }.getOrNull(),
            runCatching { StreamPlay.companionSharedPref?.getString("subsource_api_key", null) }.getOrNull(),
            runCatching { System.getProperty("subsource.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBSOURCE_API_KEY") }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    /**
     * Official SubSource API v1 + Public Keyless Urdu Harvester (`invokeSubSourceUrdu`):
     * Searches SubSource by `imdbId` or `title`, fetches Urdu subtitle entries, downloads and
     * materializes each subtitle (frequently `Windows-1256` `.zip`) via [materializeNormalizedUrduSubtitle],
     * and emits `Urdu` with `X-StreamPlay-Source: subsource`.
     */
    suspend fun invokeSubSourceUrdu(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        isMovie: Boolean = season == null,
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null) return

        val resolvedKey = resolveSubSourceApiKey(apiKey, sharedPref)
        val headers = buildMap {
            put("User-Agent", BROWSER_USER_AGENT)
            put("Accept", "application/json")
            if (!resolvedKey.isNullOrBlank()) {
                put("X-API-Key", resolvedKey)
                put("api-key", resolvedKey)
            }
        }

        try {
            var movieId: String? = null
            // 1. Search Movie ID via /api/v1/movies/search
            val searchUrl = buildString {
                append("$SUBSOURCE_API_V1/movies/search?")
                if (cleanImdb != null) {
                    append("searchType=imdb&imdb=${URLEncoder.encode(cleanImdb, "UTF-8")}")
                } else if (cleanTitle != null) {
                    append("searchType=text&q=${URLEncoder.encode(cleanTitle, "UTF-8")}")
                }
                if (year != null && year > 0 && isMovie) append("&year=$year")
                if (season != null && season > 0) append("&season=$season")
            }
            val searchJson = withTimeoutOrNull(5_000L) {
                StreamPlayExtractor.cancellableGetText(searchUrl, headers = headers, timeoutSec = 5L)
            }
            if (!searchJson.isNullOrBlank()) {
                movieId = parseSubSourceSearchMovieId(searchJson, cleanImdb, cleanTitle, year, season)
            }

            // 2. Query Subtitles list via /api/v1/subtitles (and keyless public fallback endpoints)
            val subtitleCandidateEndpoints = buildList {
                if (!movieId.isNullOrBlank()) {
                    add(
                        buildString {
                            append("$SUBSOURCE_API_V1/subtitles?movieId=${URLEncoder.encode(movieId, "UTF-8")}&language=urdu")
                            if (season != null && season > 0) append("&season=$season")
                            if (episode != null && episode > 0) append("&episode=$episode")
                        }
                    )
                }
                if (cleanImdb != null) {
                    add(
                        buildString {
                            append("$SUBSOURCE_API_V1/subtitles?imdb=${URLEncoder.encode(cleanImdb, "UTF-8")}&language=urdu")
                            if (season != null && season > 0) append("&season=$season")
                            if (episode != null && episode > 0) append("&episode=$episode")
                        }
                    )
                    add(
                        buildString {
                            append("$SUBSOURCE_API_HOST/v1/subtitles?imdb=${URLEncoder.encode(cleanImdb, "UTF-8")}&language=urdu")
                            if (season != null && season > 0) append("&season=$season")
                            if (episode != null && episode > 0) append("&episode=$episode")
                        }
                    )
                }
            }

            val downloadUrls = LinkedHashSet<String>()
            for (endpoint in subtitleCandidateEndpoints) {
                val subJson = withTimeoutOrNull(5_000L) {
                    StreamPlayExtractor.cancellableGetText(endpoint, headers = headers, timeoutSec = 5L)
                } ?: continue
                val parsed = parseSubSourceSubtitlesList(subJson, season, episode)
                if (parsed.isNotEmpty()) {
                    downloadUrls.addAll(parsed)
                    break
                }
            }

            for (dlUrl in downloadUrls) {
                val dlHeaders = buildMap {
                    putAll(headers)
                    put("X-StreamPlay-Source", "subsource")
                }
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = dlUrl,
                    headers = dlHeaders,
                    season = season,
                    episode = episode,
                    sourceTag = "subsource"
                ) ?: continue
                subtitleCallback(
                    newSubtitleFile("Urdu", materializedUri) {
                        this.headers = dlHeaders
                    }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubSourceUrdu failed: ${e.message}")
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
     * Queries `https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles/$type/$stremioId.json`,
     * materializes each Urdu subtitle via [materializeNormalizedUrduSubtitle], and emits verified
     * Urdu subtitles with `X-StreamPlay-Source: subsense`.
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
                val dlHeaders = mapOf(
                    "User-Agent" to BROWSER_USER_AGENT,
                    "X-StreamPlay-Source" to "subsense"
                )
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = subUrl,
                    headers = dlHeaders,
                    season = season,
                    episode = episode,
                    sourceTag = "subsense"
                ) ?: continue
                subtitleCallback(
                    newSubtitleFile("Urdu", materializedUri) {
                        this.headers = dlHeaders
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
     * normalized title (and release year for movies, failing closed if missing or mismatched) matches.
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
                if (yearMatch == null || kotlin.math.abs(yearMatch - expectedYear) > 1) continue
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
     * `<a id="downloadButton" href="...">`, materializes the archive via [materializeNormalizedUrduSubtitle],
     * and emits `Urdu` with `X-StreamPlay-Source: subf2m`.
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
                val dlHeaders = mapOf(
                    "User-Agent" to BROWSER_USER_AGENT,
                    "Referer" to detailUrl,
                    "X-StreamPlay-Source" to "subf2m"
                )
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = dlUrl,
                    headers = dlHeaders,
                    season = season,
                    episode = episode,
                    sourceTag = "subf2m"
                ) ?: continue
                subtitleCallback(
                    newSubtitleFile("Urdu", materializedUri) {
                        this.headers = dlHeaders
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

