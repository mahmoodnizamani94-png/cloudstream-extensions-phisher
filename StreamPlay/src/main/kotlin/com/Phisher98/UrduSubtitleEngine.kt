package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
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
 * Spoken-Urdu Linguistic Quality Scoring, Idiomatic Dialogue Adaptation,
 * RTL BiDi Sanitization, and Timing-Preserved Neural Translation Pipeline.
 *
 * Designed for StreamPlay so that:
 * 1. Human Urdu subtitles from SubDL, SubSource, Wyzie, SubSense, Subf2m, OpenSubtitles, YTS-Subs, and SubtitleCat
 *    are harvested concurrently (with AI/SMT filtering and Spoken-Human Uploader Quality Ranking).
 * 2. Legacy Urdu encodings (`Windows-1256` / `CP1256`, `UTF-16LE/BE`, BOM-prefixed `UTF-8`),
 *    compressed payloads (`GZIP`, multi-entry `ZIP` with season/episode-aware and naturalness-scored selection),
 *    Arabic-vs-Urdu glyph variants (`\u064A`/`\u0649` -> `\u06CC`, `\u0643` -> `\u06A9`,
 *    `\u0647`/`\u06D5` -> `\u06C1`, `\u06D3` -> `\u06D2`, presentation forms NFKC decomposition),
 *    spoken-Urdu idiomatic refinement (`refineUrduToSpokenIdiom`), and mixed-script RTL punctuation order (`\u200F` RLM)
 *    are normalized deterministically.
 * 3. When no human Urdu subtitle exists for a movie or episode, the highest-priority English `.srt`/`.vtt`
 *    is adapted for spoken Urdu idioms, contextually sentence-merged across SVO->SOV continuation cues,
 *    translated into timing-preserved Urdu via batched neural translation (`250` cues/batch with 50-cue
 *    bisect retry and inline tag protection, 1-to-1 cue mapping with zero timestamp drift) and served
 *    via local `.srt` cache (`file:///...`).
 */
object UrduSubtitleEngine {

    private const val TAG = "UrduSubtitleEngine"
    private const val MAX_INFLATE_BYTES = 8 * 1024 * 1024 // 8 MiB safety ceiling
    private const val TRANSLATION_BATCH_SIZE = 250
    private const val TRANSLATION_SUB_BATCH_SIZE = 50
    private const val RLM = '\u200F'
    private const val PERSISTENT_URL_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days
    private const val PREWARM_COOLDOWN_MS = 10L * 60L * 1000L // 10 minutes
    private const val NEGATIVE_SLUG_SENTINEL = "__NONE__"

    const val SUBTITLE_MODE_PREFS_NAME = "cloudstream_subtitle_mode_prefs"
    const val STREAMPLAY_PREFS_NAME = "StreamPlay"
    const val SUPERSTREAM_PREFS_NAME = "SuperStream"
    const val SUBTITLE_MODE_KEY = "subtitle_mode"
    const val SUBTITLE_MODE_SYS_PROP = "cloudstream.subtitle.mode"
    const val MODE_DEFAULT = "default"
    const val MODE_URDU = "urdu"
    const val TARGET_URDU_SUBTITLE_SOURCES = 2
    const val TOTAL_URDU_SOTA_SOURCES = 8

    data class UrduSourceDescriptor(
        val canonicalKey: String,
        val displayName: String,
        val tierLabel: String
    )

    /**
     * Canonical registry of the 8 State-of-the-Art (SOTA) Human/Indexed Urdu Subtitle Sources
     * for Hollywood Movies & TV Series, plus the Tier-2 Timing-Preserved Neural AI Fallback.
     */
    val URDU_SOTA_SOURCES: List<UrduSourceDescriptor> = listOf(
        UrduSourceDescriptor("subdl", "SubDL", "Tier 1 • Human Urdu Archive & API v1 (unpack=1)"),
        UrduSourceDescriptor("subsource", "SubSource", "Tier 1 • Post-Subscene Urdu Archive & API v1"),
        UrduSourceDescriptor("wyziesub", "Wyzie", "Tier 1 • Multi-Source Edge Aggregator (sub.wyzie.io)"),
        UrduSourceDescriptor("subsense", "SubSense", "Tier 1 • Multi-Archive Stremio Urdu Aggregator"),
        UrduSourceDescriptor("yify", "YTS-Subs", "Tier 1 • Keyless IMDb Hollywood Urdu Archive (~700 Films)"),
        UrduSourceDescriptor("subf2m", "Subf2m", "Tier 1 • Independent Community Urdu Mirror"),
        UrduSourceDescriptor("subtitlecat", "SubtitleCat", "Tier 1 • 16.87M+ Catalog Verified English Donor Index"),
        UrduSourceDescriptor("opensubtitles", "OpenSubtitles", "Tier 1 • OpenSubtitles v3 & Legacy REST Urdu Index")
    )

    val URDU_NEURAL_FALLBACK_DESCRIPTOR = UrduSourceDescriptor(
        canonicalKey = "urdu-neural-fallback",
        displayName = "Neural AI",
        tierLabel = "Tier 2 • Timing-Preserved Spoken-Urdu Neural Translator (100% Coverage)"
    )

    private val URDU_SOURCE_KEY_ALIASES: Map<String, String> = mapOf(
        "subdl" to "subdl",
        "subdl-api-v1" to "subdl",
        "subdl-urdu" to "subdl",
        "subdl.com" to "subdl",
        "subsource" to "subsource",
        "subsource-urdu" to "subsource",
        "subsource.net" to "subsource",
        "wyzie" to "wyziesub",
        "wyziesub" to "wyziesub",
        "wyzie-urdu" to "wyziesub",
        "wyzie.io" to "wyziesub",
        "subsense" to "subsense",
        "subsense-urdu" to "subsense",
        "nepiraw" to "subsense",
        "yify" to "yify",
        "yts" to "yify",
        "yts-subs" to "yify",
        "yts-urdu" to "yify",
        "yifysubtitles" to "yify",
        "subf2m" to "subf2m",
        "subf2m-urdu" to "subf2m",
        "subf2m.co" to "subf2m",
        "subtitlecat" to "subtitlecat",
        "subtitlecat-urdu" to "subtitlecat",
        "subtitlecat.com" to "subtitlecat",
        "opensubtitles" to "opensubtitles",
        "opensubtitles-urdu" to "opensubtitles",
        "stremio-os-urdu" to "subtitles",
        "subtitles" to "subtitles",
        "urdu-neural-fallback" to "urdu-neural-fallback",
        "neural-ai" to "urdu-neural-fallback",
        "spoken-ai" to "urdu-neural-fallback",
        "urdu-synth" to "urdu-neural-fallback",
        "urdu-fallback" to "urdu-neural-fallback"
    )

    private val URDU_CANONICAL_DISPLAY_NAMES: Map<String, String> = mapOf(
        "subdl" to "SubDL",
        "subsource" to "SubSource",
        "wyziesub" to "Wyzie",
        "subsense" to "SubSense",
        "yify" to "YTS-Subs",
        "subf2m" to "Subf2m",
        "subtitlecat" to "SubtitleCat",
        "opensubtitles" to "OpenSubtitles",
        "subtitles" to "OpenSubtitles",
        "urdu-neural-fallback" to "Neural AI"
    )

    /**
     * Normalizes a raw source identifier or `X-StreamPlay-Source` header into one of the canonical
     * Urdu source keys recognized by both StreamPlay and the CloudStream host app's `DownloadSubtitlePolicy`.
     * Returns `null` if [raw] is a video stream extractor (`vidlink`, `cinesrc`, `meowtv`, `vidrift`, etc.)
     * or an English-only subtitle scraper (`addic7ed`).
     */
    fun canonicalUrduSourceKey(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        URDU_SOURCE_KEY_ALIASES[cleaned]?.let { return it }
        return when {
            cleaned.contains("urdu-neural") || cleaned.contains("neural-ai") || cleaned.contains("neural ai") ||
                cleaned.contains("spoken-ai") || cleaned.contains("spoken ai") || cleaned == "neural" -> "urdu-neural-fallback"
            cleaned.contains("subdl") -> "subdl"
            cleaned.contains("subsource") -> "subsource"
            cleaned.contains("wyzie") -> "wyziesub"
            cleaned.contains("subsense") || cleaned.contains("nepiraw") -> "subsense"
            cleaned.contains("yts") || cleaned.contains("yify") -> "yify"
            cleaned.contains("subf2m") -> "subf2m"
            cleaned.contains("subtitlecat") -> "subtitlecat"
            cleaned.contains("opensubtitles") -> "opensubtitles"
            else -> null
        }
    }

    /**
     * Returns the human-readable Urdu source badge name (e.g. `"SubDL"`, `"SubSource"`, `"Wyzie"`,
     * `"SubSense"`, `"YTS-Subs"`, `"Subf2m"`, `"SubtitleCat"`, `"OpenSubtitles"`, `"Neural AI"`),
     * or `null` if [rawKey] is not a verified Urdu subtitle source.
     */
    fun urduSourceDisplayName(rawKey: String?): String? {
        val canonical = canonicalUrduSourceKey(rawKey) ?: return null
        return URDU_CANONICAL_DISPLAY_NAMES[canonical]
    }

    /**
     * Extracts a known Urdu source badge from an existing subtitle label (e.g. `"Urdu [SubDL]"` -> `"SubDL"`).
     */
    fun extractUrduSourceBadge(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val trimmed = label.trim()
        for (badge in (URDU_CANONICAL_DISPLAY_NAMES.values + listOf("Spoken AI")).distinct()) {
            if (trimmed.equals(badge, ignoreCase = true) ||
                trimmed.contains("[$badge]", ignoreCase = true) ||
                trimmed.contains("($badge)", ignoreCase = true) ||
                trimmed.endsWith("- $badge", ignoreCase = true) ||
                trimmed.endsWith("• $badge", ignoreCase = true)
            ) {
                return if (badge.equals("Spoken AI", ignoreCase = true)) "Neural AI" else badge
            }
        }
        return null
    }

    /**
     * Formats an unmistakable Urdu track label for the CloudStream subtitle picker and downloader
     * (e.g. `"Urdu [SubDL]"`, `"Urdu [SubSource]"`, `"Urdu [Wyzie]"`, `"Urdu [YTS-Subs]"`, `"Urdu [Neural AI]"`).
     * This ensures that even when CloudStream's `RepoLinkGenerator` overwrites `nameSuffix` with a numeric
     * index (`• 1`, `• 2`), the user clearly sees the exact verified Urdu source (`Urdu [SubDL] • 1`)
     * and never sees random or English video stream sources.
     */
    fun formatUrduSourceLabel(sourceKey: String?, modifier: String? = null): String {
        val badge = urduSourceDisplayName(sourceKey) ?: extractUrduSourceBadge(sourceKey)
        val cleanMod = modifier?.trim()?.removePrefix("[")?.removeSuffix("]")?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Urdu")
            if (badge != null) {
                append(" [").append(badge).append("]")
            }
            if (cleanMod != null && !cleanMod.equals(badge, ignoreCase = true)) {
                append(" [").append(cleanMod).append("]")
            }
        }
    }

    /**
     * Returns a concise summary of the active SOTA Urdu architecture for UI dialogs and subtitles settings.
     */
    fun sotaSourcesSummary(): String =
        "$TOTAL_URDU_SOTA_SOURCES SOTA Sources (SubDL, SubSource, Wyzie, SubSense, YTS-Subs, Subf2m, SubtitleCat, OpenSubtitles) + Neural AI"

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
                SubtitleMode.fromId(sharedModeStr)?.let { resolved ->
                    runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, resolved.id) }
                    return resolved
                }
            }
        }

        // 3. StreamPlay's own SharedPreferences
        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        val extModeStr = runCatching { effectivePref?.getString(SUBTITLE_MODE_KEY, null) }.getOrNull()
        SubtitleMode.fromId(extModeStr)?.let { resolved ->
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, resolved.id) }
            return resolved
        }

        // 4. Default is always Default (English)
        if (context != null || sharedPref != null) {
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, SubtitleMode.DEFAULT.id) }
        }
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

    // Shared pre-built OkHttpClient pool (zero per-request allocation)
    internal val fastHttpClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .callTimeout(8L, TimeUnit.SECONDS)
            .connectTimeout(4L, TimeUnit.SECONDS)
            .readTimeout(5L, TimeUnit.SECONDS)
            .build()
    }

    internal val standardHttpClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .callTimeout(12L, TimeUnit.SECONDS)
            .connectTimeout(6L, TimeUnit.SECONDS)
            .readTimeout(6L, TimeUnit.SECONDS)
            .build()
    }

    internal val downloadHttpClient: OkHttpClient by lazy {
        app.baseClient.newBuilder()
            .callTimeout(16L, TimeUnit.SECONDS)
            .connectTimeout(8L, TimeUnit.SECONDS)
            .readTimeout(8L, TimeUnit.SECONDS)
            .build()
    }

    internal fun httpClientForTimeout(timeoutSec: Long): OkHttpClient = when {
        timeoutSec <= 4L -> fastHttpClient
        timeoutSec <= 6L -> standardHttpClient
        else -> downloadHttpClient
    }

    val URDU_ARABIC_SCRIPT_REGEX =
        Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")

    // Letters that exist in Urdu but NOT in standard Arabic or Persian (ٹ, ڈ, ڑ, ں, ے, ھ)
    val URDU_DISTINCTIVE_CHARS_REGEX =
        Regex("""[\u0679\u0688\u0691\u06BA\u06D2\u06BE]""")

    // Urdu function words that have NO Persian/Arabic homograph. Homographs shared with Persian and
    // Arabic (تو / یا / اگر / مگر / لیکن / پر / ہو / ہم / تم — identical after `normalizeUrduTypography`
    // collapses ه/ك/ي/ى into ہ/ک/ی) are deliberately excluded so a homograph-only match can never
    // satisfy the script gate on its own.
    val URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،۔!؟"'\(\)\[\]\-])(ہے|ہیں|تھا|تھی|تھے|نہیں|میں|کے|کا|کو|سے|اور|یہ|وہ|کیا|بھی|تاکہ|کیونکہ|ہوں|گے|گی|گا|آپ|گیا|گئی|گئے|رہا|رہی|رہے|ہوئی|ہوا|ہوئے|اپنا|اپنی|اپنے|کچھ|کوئی|جب|اب|پھر|ساتھ|لیے|پہلے|کیوں|کیسے|کہاں|جائیں|کیجئے)(?:$|[\s\u200F\u200E،۔!؟"'\(\)\[\]\-])""")

    // Exclusive Persian (fa) and Arabic (ar) markers (recognizing normalized ی/ک/ہ variants as well)
    val PERSIAN_ARABIC_EXCLUSIVE_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،.!?])(می|نمی|باشه|باشہ|چرا|خیلی|دارم|داری|داره|دارہ|اینجا|آنجا|میبینمت|تموم|خونه|خونہ|کجا|بودی|فردا|انقدر|ہست|نیست|مرحبًا|مرحبا|هذا|ہذا|هذه|ہذہ|ذلك|ذلک|تلك|تلک|الذي|الذی|التي|التی|ماذا|لماذا|نحن|أين|این|کیف|ربما|غدًا|غدا)(?:$|[\s\u200F\u200E،.!?])""")

    // Persian/Arabic orthography as it appears in RAW (pre-normalization) cue text: ه ك ي ى spellings
    // plus core Arabic/Persian function words. `normalizeUrduTypography` rewrites ه/ك/ي/ى into Urdu
    // glyphs (ہ/ک/ی), which would otherwise turn Arabic هو/هم into the Urdu words ہو/ہم and mask the
    // source language; this regex is therefore applied to the raw text BEFORE normalization.
    val RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،,.!?;:"'\(\)])(است|نیست|هست|هستم|هستی|هستند|می\u200C|نمی\u200C|های|هایی|برای|خیلی|چون|چرا|کجا|اینجا|آنجا|شما|باشد|باید|شاید|دارم|داری|دارد|دارند|شود|بشه|میشه|نمیشه|میخوام|خانه|همه|هیچ|وقتی|الان|امروز|دیروز|فردا|بزرگ|کوچک|هذا|هذه|ذلك|تلك|الذي|التي|الذين|ربما|لماذا|كيف|أين|نحن|أنتم|كان|كانت|كانوا|يكون|تكون|على|إلى|عن|في|أن|إن|قد|لكن|هو|هي|هن|أنت|أنا|عند|قبل|حتى|منذ|سوف|كل|بعض|مثل|فقط|جدا|الآن|هنا|هناك|كذلك|أيضا|ليلة|ساعة|أسبوع|أمس|غدا|يوم|عندما|ليس|ليست|أو)(?:$|[\s\u200F\u200E،,.!?;:"'\(\)])""")

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
        "میرا پیج لایک کرو",
        "tryray.app",
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
        "subf2m.co",
        "addic7ed.com",
        "سے ڈاؤن لوڈ",
        "ترجمہ کار"
    )

    private val WATERMARK_REGEX_PATTERNS = listOf(
        Regex("""(?i)tryray\.app"""),
        Regex("""200\+\s*زبانیں"""),
        Regex("""(?i)@?iamdesigora"""),
        Regex("""میرا\s+پیج\s+لا[ئی]ک\s+کرو"""),
        Regex("""(?i)subdl\.com"""),
        Regex("""(?i)subsource\.net"""),
        Regex("""(?i)subf2m\.co""")
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

    // SingleFlight deduplication for concurrent materialization of the same URL/episode
    private val materializationInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    // Series/Movie Slug & Discovery Caches + Negative Cache ("__NONE__")
    private val subDlSlugCache = ConcurrentHashMap<String, String>()
    private val subf2mSlugCache = ConcurrentHashMap<String, String>()

    // Speculative Pre-Warming Scope & Cooldown Guard
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prewarmHistory = ConcurrentHashMap<String, Long>()

    // Pending SubtitleCat original SRT candidates recorded during SubtitleCat harvesting:
    // Pair(origSrtAbsoluteUrl, origFilename) + media-scoped map for concurrent episode safety
    private val pendingSubtitleCatOrigList = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val pendingSubtitleCatOrigByMedia = ConcurrentHashMap<String, MutableSet<Pair<String, String>>>()

    // Pending SubtitleCat pre-cached -ur.srt fallback URLs (never emitted as Phase 1 human tracks;
    // only refined via Spoken-Urdu Idiomatic Engine in Phase 4 fallback if needed)
    private val pendingSubtitleCatUrduFallbackByMedia = ConcurrentHashMap<String, MutableSet<String>>()

    private fun buildMediaDonorKey(imdbId: String? = null, season: Int? = null, episode: Int? = null): String {
        val cleanImdb = imdbId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "global"
        return "$cleanImdb|S${season ?: 0}E${episode ?: 0}"
    }

    fun recordSubtitleCatOrigCandidate(
        origUrl: String,
        origFilename: String,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ) {
        if (origUrl.isNotBlank() && origFilename.isNotBlank()) {
            val pair = origUrl.trim() to origFilename.trim()
            pendingSubtitleCatOrigList.add(pair)
            val mediaKey = buildMediaDonorKey(imdbId, season, episode)
            pendingSubtitleCatOrigByMedia.computeIfAbsent(mediaKey) { ConcurrentHashMap.newKeySet() }.add(pair)
            if (imdbId != null) {
                val globalKey = buildMediaDonorKey(null, season, episode)
                pendingSubtitleCatOrigByMedia.computeIfAbsent(globalKey) { ConcurrentHashMap.newKeySet() }.add(pair)
            }
        }
    }

    fun recordSubtitleCatPreCachedUrduCandidate(
        urduUrl: String,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ) {
        if (urduUrl.isNotBlank()) {
            val cleanUrl = urduUrl.trim()
            val mediaKey = buildMediaDonorKey(imdbId, season, episode)
            pendingSubtitleCatUrduFallbackByMedia.computeIfAbsent(mediaKey) { ConcurrentHashMap.newKeySet() }
                .add(cleanUrl)
            if (imdbId != null) {
                val globalKey = buildMediaDonorKey(null, season, episode)
                pendingSubtitleCatUrduFallbackByMedia.computeIfAbsent(globalKey) { ConcurrentHashMap.newKeySet() }
                    .add(cleanUrl)
            }
        }
    }

    fun consumeSubtitleCatOrigCandidates(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<Pair<String, String>> {
        val result = LinkedHashSet<Pair<String, String>>()
        val hasScope = !imdbId.isNullOrBlank() || season != null || episode != null
        if (hasScope) {
            val mediaKey = buildMediaDonorKey(imdbId, season, episode)
            pendingSubtitleCatOrigByMedia.remove(mediaKey)?.let { result.addAll(it) }
            val globalEpisodeKey = buildMediaDonorKey(null, season, episode)
            pendingSubtitleCatOrigByMedia.remove(globalEpisodeKey)?.let { result.addAll(it) }
            if (result.isNotEmpty()) {
                pendingSubtitleCatOrigList.removeAll(result)
                return result.toList()
            }
            // Fallback to unscoped global list only if no scoped candidates existed for this episode
            val unscopedKey = buildMediaDonorKey(null, null, null)
            pendingSubtitleCatOrigByMedia.remove(unscopedKey)?.let {
                result.addAll(it)
                pendingSubtitleCatOrigList.removeAll(it)
            }
            return result.toList()
        }
        result.addAll(pendingSubtitleCatOrigList)
        pendingSubtitleCatOrigList.clear()
        val allKeys = pendingSubtitleCatOrigByMedia.keys().toList()
        for (k in allKeys) {
            pendingSubtitleCatOrigByMedia.remove(k)?.let { result.addAll(it) }
        }
        return result.toList()
    }

    internal fun consumeSubtitleCatPreCachedUrduCandidates(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        val result = LinkedHashSet<String>()
        val hasScope = !imdbId.isNullOrBlank() || season != null || episode != null
        if (hasScope) {
            val mediaKey = buildMediaDonorKey(imdbId, season, episode)
            pendingSubtitleCatUrduFallbackByMedia.remove(mediaKey)?.let { result.addAll(it) }
            val globalEpisodeKey = buildMediaDonorKey(null, season, episode)
            pendingSubtitleCatUrduFallbackByMedia.remove(globalEpisodeKey)?.let { result.addAll(it) }
            if (result.isNotEmpty()) {
                return result.toList()
            }
            val unscopedKey = buildMediaDonorKey(null, null, null)
            pendingSubtitleCatUrduFallbackByMedia.remove(unscopedKey)?.let { result.addAll(it) }
            return result.toList()
        }
        val allKeys = pendingSubtitleCatUrduFallbackByMedia.keys().toList()
        for (k in allKeys) {
            pendingSubtitleCatUrduFallbackByMedia.remove(k)?.let { result.addAll(it) }
        }
        return result.toList()
    }

    internal fun clearCachesForTest() {
        synthesizedUrduCache.clear()
        materializedUrduCache.clear()
        materializationInFlight.clear()
        subDlSlugCache.clear()
        subf2mSlugCache.clear()
        prewarmHistory.clear()
        pendingSubtitleCatOrigList.clear()
        pendingSubtitleCatOrigByMedia.clear()
        pendingSubtitleCatUrduFallbackByMedia.clear()
    }

    internal fun sha256Hex(input: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun resolveCacheBaseDir(): File {
        val baseDir = appContextRef?.cacheDir
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }

    /**
     * Speculative Detail-Page Pre-Warming Entry Point (`prewarmUrduSubtitlesAsync`):
     * Uses a bounded background `CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a
     * `ConcurrentHashMap<String, Long>` deduplication guard (10-minute cooldown per media key)
     * to speculatively warm the SubDL, SubSense, OpenSubtitles, and YTS-Subs (movies) Urdu subtitle
     * caches in the background when the user opens a Movie/Series detail page (`load()`) while
     * `isUrduMode()` is active.
     */
    fun prewarmUrduSubtitlesAsync(
        imdbId: String?,
        tmdbId: Int? = null,
        title: String?,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = season == null,
        sharedPref: android.content.SharedPreferences? = null
    ) {
        if (!isUrduMode(sharedPref = sharedPref)) return
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null && (tmdbId == null || tmdbId <= 0)) return

        val mediaKey = "${cleanImdb ?: cleanTitle ?: tmdbId}|Y${year ?: 0}|S${season ?: 0}E${episode ?: 0}"
        val now = System.currentTimeMillis()
        val lastRun = prewarmHistory[mediaKey]
        if (lastRun != null && (now - lastRun) < PREWARM_COOLDOWN_MS) return
        prewarmHistory[mediaKey] = now

        val targetSeason = if (!isMovie && season != null && season > 0) season else null
        val targetEpisode = if (targetSeason != null) (episode?.takeIf { it > 0 } ?: 1) else null

        prewarmScope.launch {
            try {
                withTimeoutOrNull(8_000L) {
                    coroutineScope {
                        launch {
                            invokeSubDlUrduKeyless(
                                imdbId = cleanImdb,
                                title = cleanTitle,
                                season = targetSeason,
                                episode = targetEpisode,
                                year = year
                            ) {}
                        }
                        if (cleanImdb != null) {
                            launch {
                                invokeSubSenseUrdu(
                                    imdbId = cleanImdb,
                                    season = targetSeason,
                                    episode = targetEpisode
                                ) {}
                            }
                            launch {
                                StreamPlayExtractor.invokeOpenSubtitlesLegacy(
                                    id = cleanImdb,
                                    season = targetSeason,
                                    episode = targetEpisode,
                                    title = cleanTitle
                                ) {}
                            }
                            if (targetSeason == null) {
                                launch {
                                    StreamPlayExtractor.invokeYifySubs(
                                        imdbId = cleanImdb
                                    ) {}
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    // =========================================================================
    // 1. Archive Inflation, Encoding Detection & Urdu Typography Normalization
    // =========================================================================

    /**
     * Safely inflates GZIP (`0x1F 0x8B`) or ZIP (`0x50 0x4B 0x03 0x04`) subtitle archives
     * up to [MAX_INFLATE_BYTES] (8 MiB), selecting the best `.srt`/`.vtt`/`.ass`/`.ssa` entry
     * inside ZIPs (with season/episode matching and Spoken-Urdu naturalness scoring) while skipping `.nfo`, `.txt`, and directories.
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
        var bestScore = Int.MIN_VALUE

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

                    // Score naturalness across candidate subtitle entries in multi-SRT archives
                    val naturalnessBonus = runCatching {
                        val previewDecoded = normalizeUrduTypography(decodeSubtitleCharset(unpacked))
                        val previewCues = parseSubtitleCues(previewDecoded)
                        if (previewCues.isNotEmpty() && URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(previewDecoded)) {
                            if (isBrokenSmtUrdu(previewCues)) -400 else scoreUrduSubtitleNaturalness(previewCues)
                        } else {
                            0
                        }
                    }.getOrDefault(0)

                    val score = episodeBonus + extScore + langBonus + naturalnessBonus + (unpacked.size.coerceAtMost(500_000) / 10_000)
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
            val strippedRaw = rawLine.trim().trim(RLM, '\u200E', '\u202A', '\u202B', '\u202C')
            if (strippedRaw.isEmpty()) return@mapNotNull null
            if (isWatermarkCue(strippedRaw)) return@mapNotNull null
            val stripped = if (URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(strippedRaw)) {
                refineUrduToSpokenIdiom(strippedRaw).trim()
            } else {
                strippedRaw
            }
            if (stripped.isEmpty()) return@mapNotNull null
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
        if (WATERMARK_PATTERNS.any { pattern -> lower.contains(pattern.lowercase(Locale.ROOT)) }) {
            return true
        }
        return WATERMARK_REGEX_PATTERNS.any { regex -> regex.containsMatchIn(text) }
    }

    // =========================================================================
    // 1B. Spoken-Urdu Linguistic Quality Scorer & Idiomatic Dialogue Engine
    // =========================================================================

    private val BROKEN_SMT_EMBEDDED_ASCII_REGEX =
        Regex("""[\u0600-\u06FF]+\s+[a-z]{3,}\s+[\u0600-\u06FF]+""")

    private val BROKEN_SMT_INLINE_ASCII_VERB_NOUN_REGEX =
        Regex("""(?:[\u0600-\u06FF]{2,}\s+[a-z]{3,}|[a-z]{3,}\s+[\u0600-\u06FF]{2,})""")

    private val BROKEN_SMT_UNGRAMMATICAL_TOKENS = listOf(
        "ہںس",
        "واپس سربراہ چاہئے",
        "واپس حاصل",
        "سر واپس چاہئے",
        "کے ripping",
        "ripping کے",
        "wildlings اس"
    )

    private val SPOKEN_CONVERSATIONAL_URDU_MARKERS = listOf(
        "یار",
        "بھئی",
        "ذرا",
        "بھلا",
        "بالکل",
        "ورنہ",
        "خبردار",
        "رُک جاؤ",
        "رک جاؤ",
        "بکواس",
        "دماغ خراب",
        "پاگل",
        "یا خدایا",
        "یا اللہ",
        "معاف",
        "چلو",
        "دیکھو",
        "سنو",
        "بتاؤ",
        "خاموش",
        "چپ کرو",
        "حوصلہ",
        "ٹھیک ہے",
        "کیا بات ہے"
    )

    private val LITERAL_CALQUE_PENALTY_PATTERNS = listOf(
        "اپنے دماغ سے باہر",
        "اوہ، میرے خدا",
        "اوہ ، میرے خدا",
        "اوہ، میرے گوش",
        "اوہ ، میرے گوش",
        "آپ کا فضل",
        "صحت ناکام ہو رہی",
        "میرے پاس بھی نہیں"
    )

    private val SPOKEN_URDU_IDIOM_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("""اوہ\s*[،,]?\s*میرے\s+خدا[!۔.]?""") to "یا خدایا!",
        Regex("""اوہ\s*[،,]?\s*میرے\s+گوش[!۔.]?""") to "اف خدایا!",
        Regex("""کیا\s+(?:آپ|تم)\s+اپنے\s+دماغ\s+سے\s+باہر\s+(?:ہیں|ہو)\s*[؟?]?""") to "کیا تمہارا دماغ خراب ہو گیا ہے؟",
        Regex("""اپنے\s+دماغ\s+سے\s+باہر""") to "حواس کھو بیٹھے",
        Regex("""^(?:[\u200F\s\-]*)(?:یسوع\s+)?مسیح\s*[!۔.]?$""") to "یا خدایا!",
        Regex("""^(?:[\u200F\s\-]*)(?:شٹ|گندگی|لعنت\s+ہے)\s*[!۔.]?$""") to "بیڑا غرق!",
        Regex("""^(?:[\u200F\s\-]*)(?:بل\s*شٹ|بیل\s+کی\s+گندگی)\s*[!۔.]?$""") to "سب بکواس ہے!",
        Regex("""آپ\s+کا\s+استقبال\s+ہے""") to "کوئی بات نہیں",
        Regex("""کوئی\s+راستہ\s+نہیں\s*[!۔.]?""") to "ہرگز نہیں!",
        Regex("""کوئی\s+دماغ\s+نہیں""") to "چلو چھوڑو",
        Regex("""صحت\s+ناکام\s+ہو\s+رہی""") to "طبیعت خراب ہو رہی",
        Regex("""آپ\s+کا\s+فضل""") to "عالی جاہ",
        Regex("""^(?:[\u200F\s\-]*)پکڑو\s*[!۔.]?$""") to "ذرا رکو!",
        Regex("""^(?:[\u200F\s\-]*)دھکا\s*[!۔.]?$""") to "زور لگاؤ!",
        Regex("""^(?:[\u200F\s\-]*)اور\s+دھکا\s*[!۔.]?$""") to "اور زور لگاؤ!",
        Regex("""سوچو\s*[،,]\s*سوچو\s*[،,]\s*سوچئے""") to "سوچو، سوچو، ذرا سوچو"
    )

    private val DETACHED_URDU_PUNCTUATION_REGEX = Regex("""\s+([،۔!؟])""")

    /**
     * Transforms literal English-to-Urdu calques into natural spoken Pakistani/Hindustani Urdu
     * dialogue and fixes detached Urdu punctuation spacing (`\s+([،۔!؟])` -> `$1`).
     */
    fun refineUrduToSpokenIdiom(text: String): String {
        if (text.isBlank()) return text
        var current = normalizeUrduTypography(text)
        val (protectedText, inlineTags) = protectSubtitleTags(current)
        val refinedLines = protectedText.split('\n').map { line ->
            var working = line.trim()
            for ((regex, replacement) in SPOKEN_URDU_IDIOM_REPLACEMENTS) {
                working = regex.replace(working, replacement)
            }
            working = DETACHED_URDU_PUNCTUATION_REGEX.replace(working, "$1")
            working
        }
        current = restoreSubtitleTags(refinedLines.joinToString("\n"), inlineTags)
        return DETACHED_URDU_PUNCTUATION_REGEX.replace(current, "$1")
    }

    /**
     * Detects broken Statistical Machine Translation (SMT) corruption (such as `akhaliq_655`'s
     * `"کے ripping کے رہے"`, `"wildlings اس طرح"`, `"(بچوں ہںس)"`, `"واپس سربراہ چاہئے"`):
     * Rejects when:
     * - Even 1 cue contains an explicit ungrammatical SMT signature (`BROKEN_SMT_UNGRAMMATICAL_TOKENS`)
     * - For short snippets (`<= 4` cues), at least 2 cues contain embedded lowercase ASCII words or inline ASCII+Urdu corruption
     * - Or `>= 3` cues / `>= 4%` of cues contain lowercase ASCII words embedded inside Urdu phrases.
     */
    fun isBrokenSmtUrdu(cues: List<SrtCue>): Boolean {
        if (cues.isEmpty()) return false
        var embeddedAsciiCount = 0
        var inlineAsciiCount = 0
        var explicitSmtCount = 0
        for (cue in cues) {
            val (protectedText, _) = protectSubtitleTags(cue.text)
            val text = protectedText
            if (BROKEN_SMT_UNGRAMMATICAL_TOKENS.any { token -> text.contains(token, ignoreCase = true) }) {
                explicitSmtCount++
            }
            if (BROKEN_SMT_EMBEDDED_ASCII_REGEX.containsMatchIn(text)) {
                embeddedAsciiCount++
            } else if (BROKEN_SMT_INLINE_ASCII_VERB_NOUN_REGEX.containsMatchIn(text)) {
                inlineAsciiCount++
            }
        }
        if (explicitSmtCount >= 1 && (embeddedAsciiCount + inlineAsciiCount + explicitSmtCount) >= 2) {
            return true
        }
        if (cues.size <= 4 && (embeddedAsciiCount >= 1 && (embeddedAsciiCount + inlineAsciiCount + explicitSmtCount) >= 2)) {
            return true
        }
        val totalCorrupted = embeddedAsciiCount + explicitSmtCount
        return totalCorrupted >= 3 || (cues.size >= 5 && totalCorrupted * 100 >= cues.size * 4)
    }

    /**
     * Computes a linguistic naturalness score for a candidate Urdu subtitle cue list:
     * - `+10` for each spoken conversational Urdu marker (`یار`, `بھئی`, `ذرا`, `بالکل`, `یا خدایا`, etc.)
     * - `-15` for each literal English calque (`اپنے دماغ سے باہر`, `اوہ، میرے خدا`, `اوہ ، میرے گوش`, `آپ کا فضل`, etc.)
     * - `-500` penalty if broken SMT corruption (`isBrokenSmtUrdu`) is detected.
     */
    fun scoreUrduSubtitleNaturalness(cues: List<SrtCue>): Int {
        if (cues.isEmpty()) return 0
        var score = if (isBrokenSmtUrdu(cues)) -500 else 0
        for (cue in cues) {
            val text = normalizeUrduTypography(cue.text)
            for (marker in SPOKEN_CONVERSATIONAL_URDU_MARKERS) {
                if (text.contains(marker)) {
                    score += 10
                }
            }
            for (calque in LITERAL_CALQUE_PENALTY_PATTERNS) {
                if (text.contains(calque)) {
                    score -= 15
                }
            }
            if (BROKEN_SMT_EMBEDDED_ASCII_REGEX.containsMatchIn(text)) {
                score -= 25
            }
        }
        return score
    }

    private val ENGLISH_SPOKEN_IDIOM_ADAPTATIONS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)^[\s\-]*(?:oh\s*,?\s*my\s+god|omg)\s*[!.]*$""") to "یا خدایا!",
        Regex("""(?i)\b(?:oh\s*,?\s*my\s+god|omg)\b[!.,]*""") to "یا خدایا،",
        Regex("""(?i)^[\s\-]*oh\s*,?\s*my\s+gosh\s*[!.]*$""") to "اف خدایا!",
        Regex("""(?i)\boh\s*,?\s*my\s+gosh\b[!.,]*""") to "اف خدایا،",
        Regex("""(?i)^[\s\-]*(?:jesus\s+christ|jesus|christ)\s*[!.]*$""") to "یا خدایا!",
        Regex("""(?i)^[\s\-]*(?:are\s+you\s+out\s+of\s+your\s+mind|have\s+you\s+lost\s+your\s+mind)\s*[?!.]*$""") to "کیا تمہارا دماغ خراب ہو گیا ہے؟",
        Regex("""(?i)\b(?:are\s+you\s+out\s+of\s+your\s+mind|have\s+you\s+lost\s+your\s+mind)\s*\??""") to "کیا تمہارا دماغ خراب ہو گیا ہے؟",
        Regex("""(?i)^[\s\-]*what\s+the\s+hell\s+are\s+you\s+doing\s*[?!.]*$""") to "یہ تم آخر کر کیا رہے ہو؟",
        Regex("""(?i)\bwhat\s+the\s+hell\s+are\s+you\s+doing\s*\??""") to "یہ تم آخر کر کیا رہے ہو؟",
        Regex("""(?i)\bwhat\s+the\s+hell\b""") to "آخر یہ کیا",
        Regex("""(?i)^[\s\-]*shut\s+up\s*[!.]*$""") to "بکواس بند کرو!",
        Regex("""(?i)^[\s\-]*bullshit\s*[!.]*$""") to "سب بکواس ہے!",
        Regex("""(?i)^[\s\-]*(?:goddamn\s+it|damn\s+it|dammit|shit)\s*[!.]*$""") to "بیڑا غرق!",
        Regex("""(?i)^[\s\-]*(?:hold\s+on|hang\s+on)\s*[!.]*$""") to "ذرا رکو!",
        Regex("""(?i)^[\s\-]*(?:watch\s+out|look\s+out)\s*[!.]*$""") to "دھیان سے!",
        Regex("""(?i)^[\s\-]*get\s+out\s+of\s+here\s*[!.]*$""") to "دفع ہو جاؤ یہاں سے!",
        Regex("""(?i)^[\s\-]*no\s+way\s*[!.]*$""") to "ہو ہی نہیں سکتا!",
        Regex("""(?i)^[\s\-]*never\s+mind\s*[!.]*$""") to "چلو چھوڑو۔",
        Regex("""(?i)^[\s\-]*(?:take\s+it\s+easy|calm\s+down)\s*[!.]*$""") to "حوصلہ رکھو۔",
        Regex("""(?i)^[\s\-]*you(?:'re|\s+are)\s+welcome\s*[!.]*$""") to "کوئی بات نہیں۔"
    )

    /**
     * Pre-adapts English idioms and exclamations into natural spoken Pakistani/Hindustani Urdu
     * equivalents before neural translation so Google NMT never emits literal word-by-word calques.
     */
    fun adaptEnglishForSpokenUrduTranslation(text: String): String {
        if (text.isBlank()) return text
        val (protectedText, inlineTags) = protectSubtitleTags(text)
        val adaptedLines = protectedText.split('\n').map { rawLine ->
            var working = rawLine
            for ((regex, replacement) in ENGLISH_SPOKEN_IDIOM_ADAPTATIONS) {
                working = regex.replace(working, replacement)
            }
            working
        }
        return restoreSubtitleTags(adaptedLines.joinToString("\n"), inlineTags)
    }

    // =========================================================================
    // 2. High-Throughput Timing-Preserved Neural Translation (EN -> UR)
    // =========================================================================

    data class SrtCue(
        val index: Int,
        val timestamp: String,
        val text: String
    )

    typealias SubtitleCue = SrtCue

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

    internal fun parseSrtTimeToMillis(ts: String): Long? {
        return try {
            val normalized = normalizeSrtTimestamp(ts)
            val mainAndMs = normalized.split(',')
            val hms = mainAndMs[0].split(':')
            if (hms.size != 3) return null
            val h = hms[0].toLong()
            val m = hms[1].toLong()
            val s = hms[2].toLong()
            val ms = mainAndMs.getOrNull(1)?.toLongOrNull() ?: 0L
            ((h * 3600L + m * 60L + s) * 1000L) + ms
        } catch (_: Exception) {
            null
        }
    }

    internal fun cueGapMillis(firstCue: SrtCue, secondCue: SrtCue): Long? {
        val firstParts = firstCue.timestamp.split("-->")
        val secondParts = secondCue.timestamp.split("-->")
        if (firstParts.size != 2 || secondParts.size != 2) return null
        val endFirst = parseSrtTimeToMillis(firstParts[1]) ?: return null
        val startSecond = parseSrtTimeToMillis(secondParts[0]) ?: return null
        return startSecond - endFirst
    }

    private val TERMINAL_PUNCTUATION_CHARS = setOf('.', '!', '?', '"', '\'', '…', '—', ']', ')', ':', ';', '۔', '؟', '♪', '♫')

    private val CONTINUATION_TRAILING_ELLIPSIS_REGEX = Regex("""(?:\.\.\.|…)\s*$""")
    private val CONTINUATION_LEADING_ELLIPSIS_REGEX = Regex("""^(?:\.\.\.|…)\s*""")

    private val COMMON_PROPER_NOUN_CONTINUATIONS = setOf(
        "I", "I'm", "I've", "I'll", "I'd", "God", "Lord", "Mr.", "Mrs.", "Ms.", "Dr."
    )

    private fun stripInlineFormattingForContinuationCheck(text: String): String {
        return PROTECTED_SUBTITLE_TOKEN_REGEX.replace(text, "").trim()
    }

    /**
     * Checks whether [firstCue] and [secondCue] form a single split English SVO sentence:
     * - [firstCue] does not end with terminal punctuation (or ends with a continuation ellipsis `...`/`…` or comma `,`),
     * - [secondCue] is not a multi-speaker dialogue turn (`- `) and starts with a lowercase ASCII letter (`a..z`),
     *   a continuation ellipsis (`...`), or a first-person pronoun (`I`, `I'm`, `I'll`, `I've`, `I'd`) after a comma/conjunction,
     * - The gap between [firstCue] end and [secondCue] start is in `0L..1500L` ms (rejects negative overlapping cues).
     */
    internal fun shouldMergeContinuationCues(firstCue: SrtCue, secondCue: SrtCue): Boolean {
        val plainFirstRaw = stripInlineFormattingForContinuationCheck(firstCue.text)
        val plainSecondRaw = stripInlineFormattingForContinuationCheck(secondCue.text)
        if (plainFirstRaw.isEmpty() || plainSecondRaw.isEmpty()) return false
        if (plainSecondRaw.startsWith("-") || plainFirstRaw.startsWith("-")) return false

        val gap = cueGapMillis(firstCue, secondCue) ?: return false
        if (gap < 0L || gap > 1500L) return false

        val firstHasTrailingEllipsis = CONTINUATION_TRAILING_ELLIPSIS_REGEX.containsMatchIn(plainFirstRaw)
        val plainFirst = plainFirstRaw.replace(CONTINUATION_TRAILING_ELLIPSIS_REGEX, "").trim()
        val plainSecond = plainSecondRaw.replace(CONTINUATION_LEADING_ELLIPSIS_REGEX, "").trim()
        if (plainFirst.isEmpty() || plainSecond.isEmpty()) return false

        val lastChar = plainFirst.last()
        if (!firstHasTrailingEllipsis && lastChar in TERMINAL_PUNCTUATION_CHARS) return false

        val firstCharSecond = plainSecond.first()
        val firstWordSecond = plainSecond.substringBefore(' ').trimEnd(',', '.', '!', '?')
        val validContinuationStart = firstCharSecond in 'a'..'z' ||
            (lastChar == ',' && firstWordSecond in COMMON_PROPER_NOUN_CONTINUATIONS)
        return validContinuationStart
    }

    /**
     * Splits a translated Urdu sentence proportionally across 2 or more original continuation cues
     * according to their relative character lengths (splitting at the nearest word boundary
     * or comma near the proportional split points) while preserving original timestamps.
     */
    internal fun splitTranslatedUrduProportionally(
        translatedText: String,
        sourceLengths: List<Int>
    ): List<String> {
        if (sourceLengths.isEmpty()) return emptyList()
        val cleaned = translatedText.trim()
        if (sourceLengths.size == 1) return listOf(cleaned)
        val words = cleaned.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return List(sourceLengths.size) { "" }
        if (words.size < sourceLengths.size) {
            return List(sourceLengths.size) { idx -> words.getOrElse(idx) { words.last() } }
        }

        val safeLengths = sourceLengths.map { it.coerceAtLeast(1) }
        val totalSource = safeLengths.sum().coerceAtLeast(1)
        val segments = ArrayList<String>(sourceLengths.size)
        var startWordIdx = 0
        var cumulativeSource = 0

        for (segIdx in 0 until sourceLengths.size - 1) {
            cumulativeSource += safeLengths[segIdx]
            val remainingSegments = sourceLengths.size - 1 - segIdx
            val minSplit = startWordIdx + 1
            val maxSplit = (words.size - remainingSegments).coerceAtLeast(minSplit)
            val ratio = cumulativeSource.toDouble() / totalSource.toDouble()
            val targetSplit = (words.size * ratio).toInt().coerceIn(minSplit, maxSplit)

            var bestSplit = targetSplit
            for (offset in listOf(0, -1, 1)) {
                val candidate = targetSplit + offset
                if (candidate in minSplit..maxSplit) {
                    val prevWord = words[candidate - 1]
                    if (prevWord.endsWith("،") || prevWord.endsWith(",")) {
                        bestSplit = candidate
                        break
                    }
                }
            }
            segments.add(words.subList(startWordIdx, bestSplit).joinToString(" "))
            startWordIdx = bestSplit
        }
        segments.add(words.subList(startWordIdx, words.size).joinToString(" "))
        return segments
    }

    internal fun splitTranslatedUrduProportionally(
        translatedText: String,
        firstSourceLen: Int,
        secondSourceLen: Int
    ): Pair<String, String> {
        val parts = splitTranslatedUrduProportionally(
            translatedText,
            listOf(firstSourceLen, secondSourceLen)
        )
        return (parts.getOrNull(0) ?: translatedText) to (parts.getOrNull(1) ?: translatedText)
    }

    /**
     * Reconstructs a valid `.srt` document from translated cues, applying Urdu typography
     * normalization, spoken-Urdu idiomatic refinement, and stateless `\u200F` RLM wrapping on every Urdu line.
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

    private sealed class TranslationUnit {
        abstract val syntheticCue: SrtCue

        data class Single(val cue: SrtCue) : TranslationUnit() {
            override val syntheticCue: SrtCue = cue
        }

        data class MergedChain(
            val cues: List<SrtCue>,
            override val syntheticCue: SrtCue,
            val plainLengths: List<Int>
        ) : TranslationUnit()
    }

    private fun extractLeadingAndTrailingTags(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val leadingMatch = Regex("""^(?:(?i)<(?:i|b|u|s)\b[^>]*>|\{\\an[1-9]\})+""").find(trimmed)
        val trailingMatch = Regex("""(?:(?i)</(?:i|b|u|s)>)+$""").find(trimmed)
        return (leadingMatch?.value ?: "") to (trailingMatch?.value ?: "")
    }

    private fun buildContextualTranslationUnits(cues: List<SrtCue>): List<TranslationUnit> {
        val units = ArrayList<TranslationUnit>(cues.size)
        var i = 0
        while (i < cues.size) {
            val current = cues[i]
            if (i + 1 < cues.size && shouldMergeContinuationCues(current, cues[i + 1])) {
                val chain = mutableListOf(current, cues[i + 1])
                var nextIdx = i + 2
                // Allow up to 3-cue sentence continuation chains when total plain length <= 220 chars
                while (
                    chain.size < 3 &&
                    nextIdx < cues.size &&
                    shouldMergeContinuationCues(chain.last(), cues[nextIdx]) &&
                    chain.sumOf { stripInlineFormattingForContinuationCheck(it.text).length } +
                        stripInlineFormattingForContinuationCheck(cues[nextIdx].text).length <= 220
                ) {
                    chain.add(cues[nextIdx])
                    nextIdx++
                }
                val combinedText = chain.joinToString(" ") { cue ->
                    adaptEnglishForSpokenUrduTranslation(
                        cue.text
                            .replace(CONTINUATION_TRAILING_ELLIPSIS_REGEX, "")
                            .replace(CONTINUATION_LEADING_ELLIPSIS_REGEX, "")
                    ).replace('\n', ' ').trim()
                }.replace(Regex("""\s{2,}"""), " ").trim()
                val plainLengths = chain.map {
                    stripInlineFormattingForContinuationCheck(it.text).length.coerceAtLeast(1)
                }
                units.add(
                    TranslationUnit.MergedChain(
                        cues = chain,
                        syntheticCue = current.copy(text = combinedText),
                        plainLengths = plainLengths
                    )
                )
                i += chain.size
            } else {
                val adapted = adaptEnglishForSpokenUrduTranslation(current.text)
                units.add(TranslationUnit.Single(current.copy(text = adapted)))
                i++
            }
        }
        return units
    }

    /**
     * Translates an English `.srt` or `.vtt` subtitle string into a timing-preserved Urdu `.srt`
     * with Spoken-English Idiom Pre-Adaptation (`adaptEnglishForSpokenUrduTranslation`),
     * Cross-Cue Sentence Continuation Merging (2-cue and 3-cue SVO -> SOV context windowing with
     * proportional Urdu word splitting and outer tag preservation), and batched neural translation
     * (`250` cues per batch, bounded by `Semaphore(4)`) via `clients5.google.com` (`dict-chrome-ex`)
     * with fallback to `translate.google.com` (`client=at`).
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

        val units = buildContextualTranslationUnits(cues)

        suspend fun translateUnitChunk(chunkUnits: List<TranslationUnit>): List<SrtCue>? {
            if (chunkUnits.isEmpty()) return emptyList()
            val protectedPairs = chunkUnits.map { protectSubtitleTags(it.syntheticCue.text) }
            val sourceTexts = protectedPairs.map { it.first }
            val translatedTexts = translationBatchSemaphore.withPermit {
                translator(sourceTexts)
            }
            if (translatedTexts != null && translatedTexts.size == chunkUnits.size) {
                val outCues = ArrayList<SrtCue>(chunkUnits.size * 2)
                for (idx in chunkUnits.indices) {
                    val unit = chunkUnits[idx]
                    val restored = restoreSubtitleTags(translatedTexts[idx], protectedPairs[idx].second)
                    val refined = refineUrduToSpokenIdiom(restored)
                    when (unit) {
                        is TranslationUnit.Single -> {
                            outCues.add(unit.cue.copy(text = refined))
                        }
                        is TranslationUnit.MergedChain -> {
                            val plainTranslated = PROTECTED_SUBTITLE_TOKEN_REGEX.replace(refined, "").trim()
                            val splitParts = splitTranslatedUrduProportionally(
                                plainTranslated,
                                unit.plainLengths
                            )
                            for (cIdx in unit.cues.indices) {
                                val origCue = unit.cues[cIdx]
                                val partText = splitParts.getOrElse(cIdx) { plainTranslated }
                                val (leadTag, trailTag) = extractLeadingAndTrailingTags(origCue.text)
                                val wrappedPart = if (leadTag.isNotEmpty() || trailTag.isNotEmpty()) {
                                    "$leadTag$partText$trailTag"
                                } else {
                                    partText
                                }
                                outCues.add(origCue.copy(text = wrappedPart))
                            }
                        }
                    }
                }
                return outCues
            }
            return null
        }

        val batches = units.chunked(TRANSLATION_BATCH_SIZE)
        val translatedBatches = batches.map { batchUnits ->
            async(Dispatchers.IO) {
                translateUnitChunk(batchUnits) ?: run {
                    if (batchUnits.size <= 1) return@run emptyList<SrtCue>()
                    val subChunkSize = if (batchUnits.size > TRANSLATION_SUB_BATCH_SIZE) {
                        TRANSLATION_SUB_BATCH_SIZE
                    } else {
                        maxOf(1, (batchUnits.size + 1) / 2)
                    }
                    val subChunks = batchUnits.chunked(subChunkSize)
                    val recovered = mutableListOf<SrtCue>()
                    for (sub in subChunks) {
                        val subRes = translateUnitChunk(sub)
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
    // 3. Delivery, Materialization & Two-Tier Content/URL-Addressed Caching
    // =========================================================================

    private val REAL_SUBTITLECAT_FOLDER_REGEX = Regex("""^/subs/[^/]+/$""", RegexOption.IGNORE_CASE)

    /**
     * Publishes a translated Urdu `.srt` to SubtitleCat's permanent CDN via
     * `POST https://www.subtitlecat.com/upload_subtitles.php` when explicitly invoked with a real folder.
     * Note: `synthesizeUrduFallbackFromEnglish` never calls this method so machine-translated `.srt`
     * files never pollute SubtitleCat's public servers as fake "State A" human tracks.
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

    private fun toCanonicalFileUri(file: File): String {
        val absPath = file.absolutePath
        val normalizedPath = if (absPath.startsWith("/")) absPath else "/$absPath"
        return "file://$normalizedPath"
    }

    private const val MAX_DISK_CACHE_FILES = 64
    private const val MAX_DISK_CACHE_BYTES = 16L * 1024L * 1024L

    /**
     * Evicts expired (`> 7` days old) or excess (`> 64` files or `> 16 MiB`) cached `.srt` files
     * from [baseDir] using Least-Recently-Modified ordering so disk usage stays bounded.
     */
    internal fun evictStaleAndExcessCacheFiles(baseDir: File) {
        try {
            val now = System.currentTimeMillis()
            val srtFiles = baseDir.listFiles { file ->
                file.isFile &&
                    file.name.startsWith("cs_urdu_", ignoreCase = true) &&
                    file.name.endsWith(".srt", ignoreCase = true)
            }?.toMutableList() ?: return

            val iterator = srtFiles.iterator()
            while (iterator.hasNext()) {
                val f = iterator.next()
                val age = (now - f.lastModified()).coerceAtLeast(0L)
                if (age > PERSISTENT_URL_CACHE_TTL_MS || f.length() == 0L) {
                    runCatching { f.delete() }
                    iterator.remove()
                }
            }

            var remainingCount = srtFiles.size
            var totalBytes = srtFiles.sumOf { it.length() }
            if (remainingCount <= MAX_DISK_CACHE_FILES && totalBytes <= MAX_DISK_CACHE_BYTES) {
                return
            }

            srtFiles.sortBy { it.lastModified() }
            for (f in srtFiles) {
                if (remainingCount <= MAX_DISK_CACHE_FILES && totalBytes <= MAX_DISK_CACHE_BYTES) {
                    break
                }
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    remainingCount--
                    totalBytes = (totalBytes - len).coerceAtLeast(0L)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Writes the normalized/translated Urdu `.srt` to the local cache directory as a canonical
     * triple-slash `file:///...` URI (so `StreamPlay.emitSubtitle`'s `startsWith("file://")` check
     * and ExoPlayer's `FileDataSource` both accept it unconditionally).
     *
     * When [customFileName] is null, uses True Content-Addressed Storage (`cs_urdu_cas_${sha256Hex(urduSrtContent).take(24)}.srt`)
     * keyed by the SHA-256 of the normalized Urdu `.srt` payload so identical subtitle payloads downloaded
     * from different mirrors deduplicate to a single on-disk `.srt` file.
     */
    /**
     * Atomic single-shot cache write: payloads land in a unique `.tmp` sibling first and are then
     * moved into place with `ATOMIC_MOVE` (falling back to `renameTo` on filesystems without
     * atomic rename support), so a concurrent reader — or the other module sharing this cache
     * directory — can never observe a partially written `.srt`. Returns false without leaving
     * any temporary file behind when the write fails.
     */
    private fun writeUrduCacheFileAtomically(file: File, payloadBytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        return try {
            tmp.writeBytes(payloadBytes)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                if (!tmp.renameTo(file)) {
                    runCatching { tmp.delete() }
                    return false
                }
            }
            true
        } catch (_: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    fun writeUrduLocalCacheFile(keySeed: String, urduSrtContent: String, customFileName: String? = null): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val baseDir = resolveCacheBaseDir()
            val contentDigest = sha256Hex(urduSrtContent).take(24)
            val fileName = customFileName ?: "cs_urdu_cas_$contentDigest.srt"
            val file = File(baseDir, fileName)
            if (file.exists() && file.isFile && file.length() > 64L) {
                runCatching { file.setLastModified(System.currentTimeMillis()) }
                return toCanonicalFileUri(file)
            }
            // Include UTF-8 BOM so ExoPlayer / external players unconditionally detect UTF-8
            val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            val contentBytes = urduSrtContent.toByteArray(StandardCharsets.UTF_8)
            if (!writeUrduCacheFileAtomically(file, bomBytes + contentBytes)) return null
            evictStaleAndExcessCacheFiles(baseDir)
            toCanonicalFileUri(file)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True Urdu Script & Language Verification (`containsVerifiedUrduScript`):
     * - Requires `>= 15%` (or at least 1 cue for short test snippets `<= 4` cues) to contain `URDU_ARABIC_SCRIPT_REGEX`.
     * - Deterministically blocks Persian (`fa`) and Arabic (`ar`) false positives across ALL file sizes:
     *   - Requires at least 1 match of `URDU_DISTINCTIVE_CHARS_REGEX` (`ٹ`, `ڈ`, `ڑ`, `ں`, `ے`, `ھ`) OR
     *     at least 1 match of `URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX` — Urdu function words with no
     *     Persian/Arabic homograph (`ہے`, `ہیں`, `تھا`, `نہیں`, `میں`, `کے`, `کا`, `کو`, `سے`, `اور`, `یہ`, `وہ`, `کیا`, ...).
     *     Shared fa/ar homographs (`تو`, `یا`, `اگر`, `پر`, `ہو`, `ہم`, ...) that normalize identically
     *     to Urdu words can never satisfy the gate on their own.
     *   - Rejects when `RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX` matches the RAW cue text (pre-normalization
     *     `ه ك ي ى` orthography, incl. Arabic `هو`/`هم`/`في` and Persian `هست`/`خانه`) and there are zero
     *     Urdu-distinctive characters.
     *   - Rejects when `PERSIAN_ARABIC_EXCLUSIVE_REGEX` matches and there are zero distinctive Urdu
     *     characters, or when Persian/Arabic exclusive tokens outnumber Urdu markers
     *     (`persianArabicExclusiveCount > urduDistinctiveCount + urduUnambiguousFunctionCount`).
     */
    fun containsVerifiedUrduScript(cues: List<SrtCue>): Boolean {
        if (cues.isEmpty()) return false
        val urduCues = cues.count { URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(it.text) }
        if (urduCues < 1 || (cues.size > 4 && urduCues * 100 < cues.size * 15)) {
            return false
        }

        val rawText = cues.joinToString("\n") { it.text }
        val combinedText = normalizeUrduTypography(rawText)
        val urduDistinctiveCount = URDU_DISTINCTIVE_CHARS_REGEX.findAll(combinedText).count()
        val urduUnambiguousFunctionCount = URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(combinedText).count()
        val persianArabicExclusiveCount = PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(combinedText).count()
        val rawPersianArabicExclusiveCount = RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(rawText).count()

        val hasUrduMarkers = urduDistinctiveCount >= 1 || urduUnambiguousFunctionCount >= 1
        if (!hasUrduMarkers) {
            return false
        }

        // Persian/Arabic orthography visible in the raw text (before typography normalization) with zero
        // Urdu-distinctive glyphs is deterministically not Urdu (Arabic هو/هم normalize INTO Urdu ہو/ہم,
        // so the raw scan is the only trustworthy signal for shared-glyph languages).
        if (rawPersianArabicExclusiveCount >= 1 && urduDistinctiveCount == 0) {
            return false
        }

        // If Persian/Arabic exclusive words outnumber Urdu markers, reject deterministically; a
        // zero-distinctive file can never outweigh even one exclusive fa/ar marker.
        if (persianArabicExclusiveCount >= 1 &&
            (urduDistinctiveCount == 0 || persianArabicExclusiveCount > (urduDistinctiveCount + urduUnambiguousFunctionCount))
        ) {
            return false
        }

        return true
    }

    fun containsVerifiedUrduScript(rawContent: String): Boolean {
        if (rawContent.isBlank()) return false
        val cues = parseSubtitleCues(rawContent)
        if (cues.isNotEmpty()) {
            return containsVerifiedUrduScript(cues)
        }
        val normalized = normalizeUrduTypography(rawContent)
        if (!URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(normalized)) return false
        val urduDistinctiveCount = URDU_DISTINCTIVE_CHARS_REGEX.findAll(normalized).count()
        val urduUnambiguousFunctionCount = URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(normalized).count()
        val persianArabicExclusiveCount = PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(normalized).count()
        val rawPersianArabicExclusiveCount = RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(rawContent).count()
        val hasUrduMarkers = urduDistinctiveCount >= 1 || urduUnambiguousFunctionCount >= 1
        if (!hasUrduMarkers) return false
        if (rawPersianArabicExclusiveCount >= 1 && urduDistinctiveCount == 0) {
            return false
        }
        if (persianArabicExclusiveCount >= 1 &&
            (urduDistinctiveCount == 0 || persianArabicExclusiveCount > (urduDistinctiveCount + urduUnambiguousFunctionCount))
        ) {
            return false
        }
        return true
    }

    /**
     * Two-Tier Persistent Content-Addressed & URL-Addressed Materialization with SingleFlight Coalescing:
     * 1. Checks in-memory `materializedUrduCache[cacheKey]`.
     * 2. Performs Disk-First URL Lookup (`cs_urdu_url_${urlCacheKey.take(24)}.srt`) before any network fetch:
     *    if the file exists on disk, is `< 7` days old, and `length() > 64`, updates its LRU timestamp and
     *    returns its canonical `file:///...` URI in `< 1ms` with zero HTTP requests.
     * 3. Uses `materializationInFlight` (`CompletableDeferred`) so concurrent harvesters requesting
     *    the same URL/episode coalesce into a single download & verification pass.
     * 4. Rejects broken SMT corruptions (`isBrokenSmtUrdu(parsedCues)`) and non-Urdu scripts (`containsVerifiedUrduScript`).
     * 5. Deduplicates identical subtitle payloads across mirrors via Content-Addressed Storage
     *    (`cs_urdu_cas_${sha256Hex(normalized).take(24)}.srt`) while maintaining the URL-index file for
     *    zero-network-call cold-start lookups.
     */
    suspend fun materializeVerifiedUrduSubtitleFile(
        url: String,
        headers: Map<String, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
        sourceTag: String = "urdu-human"
    ): String? = materializeNormalizedUrduSubtitle(
        url = url,
        headers = headers,
        season = season,
        episode = episode,
        sourceTag = sourceTag
    )

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

        val urlCacheKey = sha256Hex(cacheKey)
        val urlCacheFileName = "cs_urdu_url_${urlCacheKey.take(24)}.srt"
        val urlDiskFile = File(resolveCacheBaseDir(), urlCacheFileName)
        if (urlDiskFile.exists() && urlDiskFile.isFile && urlDiskFile.length() > 64L) {
            val ageMs = (System.currentTimeMillis() - urlDiskFile.lastModified()).coerceAtLeast(0L)
            if (ageMs < PERSISTENT_URL_CACHE_TTL_MS) {
                runCatching { urlDiskFile.setLastModified(System.currentTimeMillis()) }
                val diskUri = toCanonicalFileUri(urlDiskFile)
                materializedUrduCache[cacheKey] = diskUri
                return@withContext diskUri
            }
        }

        val newDeferred = CompletableDeferred<String?>()
        val existingDeferred = materializationInFlight.putIfAbsent(cacheKey, newDeferred)
        if (existingDeferred != null) {
            return@withContext existingDeferred.await()
        }

        try {
            val rawBytes = fetchRawBytes(cleanUrl, headers = headers, timeoutSec = 8L)
            if (rawBytes == null || rawBytes.isEmpty()) {
                newDeferred.complete(null)
                return@withContext null
            }

            val decoded = decodeAndNormalizeUrduBytes(rawBytes, season = season, episode = episode)
            if (decoded.isBlank()) {
                newDeferred.complete(null)
                return@withContext null
            }

            // Validate raw parsed cues before and after normalization so broken SMT files are rejected
            val rawCues = parseSubtitleCues(normalizeUrduTypography(decoded))
            if (rawCues.isEmpty() || isBrokenSmtUrdu(rawCues) || !containsVerifiedUrduScript(rawCues)) {
                newDeferred.complete(null)
                return@withContext null
            }

            val normalized = normalizeUrduSubtitleText(decoded)
            val parsedCues = parseSubtitleCues(normalized)
            if (!containsVerifiedUrduScript(parsedCues) || isBrokenSmtUrdu(parsedCues)) {
                newDeferred.complete(null)
                return@withContext null
            }

            // Persist to both Content-Addressed Storage (`cs_urdu_cas_<sha256(normalized)>.srt`)
            // and URL-Addressed L2 index (`cs_urdu_url_<sha256(url|S|E)>.srt`)
            val contentUri = writeUrduLocalCacheFile(cacheKey, normalized)
            val urlDiskUri = writeUrduLocalCacheFile(cacheKey, normalized, customFileName = urlCacheFileName)
            val localUri = contentUri ?: urlDiskUri
            if (localUri != null) {
                materializedUrduCache[cacheKey] = localUri
            }
            newDeferred.complete(localUri)
            localUri
        } catch (e: CancellationException) {
            newDeferred.cancel(e)
            throw e
        } catch (_: Exception) {
            newDeferred.complete(null)
            null
        } finally {
            materializationInFlight.remove(cacheKey, newDeferred)
        }
    }

    /**
     * End-to-end neural Urdu fallback synthesis:
     * Fetches the best available English `.srt`/`.vtt`/`.gz`/`.zip` candidate (preferring
     * SubtitleCat `-orig.srt` candidates passed via [subtitleCatOrigCandidates], [englishSubtitleFiles] /
     * [candidateEnglishSubs] headers, or recorded in `pendingSubtitleCatOrigByMedia`), translates it to
     * timing-preserved Spoken Urdu, and emits `Urdu [Neural AI]` with `X-StreamPlay-Source: urdu-neural-fallback`.
     *
     * Never calls `publishToSubtitleCatCdn` so machine-translated `.srt` files never pollute
     * SubtitleCat's public servers.
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
            addAll(consumeSubtitleCatOrigCandidates(imdbId = imdbId, season = season, episode = episode))
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

        val neuralLabel = formatUrduSourceLabel("urdu-neural-fallback")
        val imdbPrefix = imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { "$it|" }.orEmpty()
        for ((enUrl, _) in orderedCandidates) {
            val cacheKey = if (season != null || episode != null || imdbPrefix.isNotEmpty()) {
                "$imdbPrefix$enUrl|S${season ?: 0}E${episode ?: 0}"
            } else {
                enUrl
            }
            val cachedUrl = synthesizedUrduCache[cacheKey] ?: synthesizedUrduCache[enUrl]
            if (cachedUrl != null) {
                if (isCachedSubtitleUriValid(cachedUrl)) {
                    subtitleCallback(
                        newSubtitleFile(neuralLabel, cachedUrl) {
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
            val synthesizedCues = parseSubtitleCues(urduSrt)
            if (synthesizedCues.isEmpty() || !containsVerifiedUrduScript(synthesizedCues) || isBrokenSmtUrdu(synthesizedCues)) {
                continue
            }

            // Always materialize locally to the verified UTF-8 .srt cache file (file:///...);
            // never upload machine-translated subtitles back onto SubtitleCat's public servers.
            val deliveryUrl = writeUrduLocalCacheFile(cacheKey, urduSrt) ?: continue

            synthesizedUrduCache[cacheKey] = deliveryUrl
            synthesizedUrduCache[enUrl] = deliveryUrl

            subtitleCallback(
                newSubtitleFile(neuralLabel, deliveryUrl) {
                    headers = mapOf("X-StreamPlay-Source" to "urdu-neural-fallback")
                }
            )
            return@withContext true
        }

        // Last-resort Phase 4 fallback: if SubtitleCat had a pre-cached -ur.srt and no English donor
        // was available, refine it through refineUrduToSpokenIdiom and emit as urdu-neural-fallback
        val preCachedUrUrls = consumeSubtitleCatPreCachedUrduCandidates(imdbId = imdbId, season = season, episode = episode)
        for (urUrl in preCachedUrUrls.take(2)) {
            val rawBytes = fetchRawBytes(urUrl, timeoutSec = 5L) ?: continue
            val decodedUr = decodeAndNormalizeUrduBytes(rawBytes, season = season, episode = episode)
            if (decodedUr.isBlank()) continue
            val normalizedUr = normalizeUrduSubtitleText(decodedUr)
            val cues = parseSubtitleCues(normalizedUr)
            if (!containsVerifiedUrduScript(cues) || isBrokenSmtUrdu(cues)) continue
            val cacheKey = "${imdbPrefix}subcat_ur_fallback|$urUrl|S${season ?: 0}E${episode ?: 0}"
            val deliveryUrl = writeUrduLocalCacheFile(cacheKey, normalizedUr) ?: continue
            synthesizedUrduCache[cacheKey] = deliveryUrl
            subtitleCallback(
                newSubtitleFile(neuralLabel, deliveryUrl) {
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
            val client = httpClientForTimeout(timeoutSec)
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

    private val SUBDL_DATA_LANGUAGE_ATTR_REGEX =
        Regex("""data-language\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val SUBDL_AI_MARKER_REGEX = Regex(
        """(?i)(?:data-ai-translated|data-ai-row|data-ai-subtitles|\bsubdl[\s_]*ai\b|"ai_translated"\s*:\s*true|\bai[\s_]*translated\b|\bauto[\s_]*translated\b|\bmachine[\s_]*translated\b|\bchatgpt\b|\bgoogle[\s_]*translate\b)"""
    )

    private val SUBDL_TIER_1A_AUTHORS = listOf(
        "naveshta",
        "netflix",
        "amzn",
        "amazon",
        "prime",
        "zee5",
        "sonyliv",
        "tapmad",
        "tamasha",
        "faizybaba",
        "moorninja",
        "usmankhadim",
        "h18o9",
        "sunil kumar",
        "urdu_official",
        "malik_rehan",
        "shabbir_umer",
        "urdu zone"
    )

    private val SUBDL_TIER_1C_MACHINE_AUTHORS = listOf(
        "blackgold",
        "black gold",
        "iamdesigora",
        "akhaliq_655",
        "saad.jan",
        "tryray"
    )

    /**
     * Scores a SubDL candidate row/entry by Verified Spoken-Human Uploader Quality:
     * - Tier 1A (`+100`): Official streaming & verified spoken-Urdu translators (`naveshta`, `netflix`, `amzn`, `faizybaba`, `moorninja`, `usmankhadim`, `h18o9`, `sunil kumar`, `urdu_official`, `malik_rehan`, `shabbir_umer`, `urdu zone`, etc.)
     * - Tier 1C (`-40`): Known bulk machine/SMT uploaders (`blackgold`, `iamdesigora`, `akhaliq_655`, `saad.jan`, `tryray`)
     * - Tier 1B (`+50`): Standard community human uploaders
     */
    internal fun scoreSubDlAuthorTier(authorAndContext: String): Int {
        val lower = authorAndContext.lowercase(Locale.ROOT)
        if (SUBDL_TIER_1C_MACHINE_AUTHORS.any { lower.contains(it) }) {
            return -40
        }
        if (SUBDL_TIER_1A_AUTHORS.any { lower.contains(it) }) {
            return 100
        }
        return 50
    }

    internal fun isSubDlAiEntry(contextOrHtml: String): Boolean {
        return SUBDL_AI_MARKER_REGEX.containsMatchIn(contextOrHtml)
    }

    /**
     * Extracts direct `https://dl.subdl.com/subtitle/\d+-\d+\.zip` download URLs from a SubDL
     * `/urdu` HTML page (including Astro SSR `<div data-language="urdu">`, Next.js `__NEXT_DATA__` JSON,
     * and anchor tags):
     * 1. Scopes strictly to `data-language="urdu"` when `data-language=` sections are present
     *    (returning `emptyList()` if `data-language=` exists without `data-language="urdu"`).
     * 2. Rejects any row/block marked as AI-generated (`SUBDL AI`, `data-ai-translated`, `ai_translated":true`, etc.).
     * 3. Ranks candidates in descending order of Verified Spoken-Human Uploader Quality (`scoreSubDlAuthorTier`).
     */
    internal fun parseSubDlUrduPageZips(
        htmlBody: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (htmlBody.isBlank()) return emptyList()
        val zipRegex = Regex("""https?://dl\.subdl\.com/subtitle/\d+-\d+\.zip|/subtitle/\d+-\d+\.zip""", RegexOption.IGNORE_CASE)

        val doc = Jsoup.parse(htmlBody)
        val dataLangElements = doc.select("[data-language]")
        val hasRawDataLangAttr = SUBDL_DATA_LANGUAGE_ATTR_REGEX.containsMatchIn(htmlBody)

        val searchRoots = if (dataLangElements.isNotEmpty() || hasRawDataLangAttr) {
            val urduContainers = dataLangElements.filter { el ->
                val langAttr = el.attr("data-language").trim().lowercase(Locale.ROOT)
                langAttr == "urdu" || langAttr == "ur" || langAttr == "urd"
            }
            if (urduContainers.isEmpty()) {
                return emptyList()
            }
            urduContainers
        } else {
            listOf(doc)
        }

        val scoredCandidates = LinkedHashMap<String, Int>()

        // 1. Anchor tags with surrounding row/container text and uploader attributes
        for (root in searchRoots) {
            for (anchor in root.select("a[href]")) {
                val href = anchor.attr("href").trim()
                val match = zipRegex.find(href) ?: continue
                val fullUrl = if (match.value.startsWith("http", ignoreCase = true)) {
                    match.value
                } else {
                    "$SUBDL_DL_HOST${match.value}"
                }

                // Walk up to enclosing row (`li`, `tr`, `div`) to inspect AI attributes & author metadata
                val rowElement = anchor.parents().firstOrNull { el ->
                    val tag = el.tagName().lowercase(Locale.ROOT)
                    tag == "li" || tag == "tr" || (tag == "div" && !el.hasAttr("data-language"))
                } ?: anchor.parent()

                val rowOuterHtml = rowElement?.outerHtml().orEmpty()
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

                val authorAttr = buildString {
                    rowElement?.select("a[data-subtitle-author], [data-subtitle-author], a[href*=/u/], .author, [class*=author]")
                        ?.forEach { el ->
                            append(el.attr("data-subtitle-author")).append(' ')
                            append(el.text()).append(' ')
                        }
                    append(contextText).append(' ')
                    append(rowOuterHtml)
                }

                if (isSubDlAiEntry(authorAttr)) {
                    continue
                }

                if (subDlEpisodeMatches(contextText, season, episode)) {
                    val tierScore = scoreSubDlAuthorTier(authorAttr)
                    val prev = scoredCandidates[fullUrl]
                    if (prev == null || tierScore > prev) {
                        scoredCandidates[fullUrl] = tierScore
                    }
                }
            }
        }

        // 2. Also scan raw HTML / __NEXT_DATA__ JSON when no data-language DOM exclusion occurred
        if (scoredCandidates.isEmpty() && dataLangElements.isEmpty() && !hasRawDataLangAttr) {
            for (match in zipRegex.findAll(htmlBody)) {
                val raw = match.value
                val fullUrl = if (raw.startsWith("http", ignoreCase = true)) raw else "$SUBDL_DL_HOST$raw"
                val window = extractBoundedSubDlContextWindow(htmlBody, match.range)
                if (isSubDlAiEntry(window)) continue
                if (episode == null || episode <= 0 || subDlEpisodeMatches(window, season, episode)) {
                    val tierScore = scoreSubDlAuthorTier(window)
                    val prev = scoredCandidates[fullUrl]
                    if (prev == null || tierScore > prev) {
                        scoredCandidates[fullUrl] = tierScore
                    }
                }
            }
        }

        return scoredCandidates.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(5)
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
     * Parses Official SubDL API v1 JSON (`{"status":true,"subtitles":[{"url":"/subtitle/123-456.zip","lang":"urdu",...}]}`),
     * filters out AI-generated subtitles, and returns direct `.zip` URLs matching [season] and [episode]
     * ranked in descending order of Verified Spoken-Human Uploader Quality (`scoreSubDlAuthorTier`).
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
            val scoredUrls = LinkedHashMap<String, Int>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val lang = (item.optString("lang").ifBlank { item.optString("language") })
                    .trim().lowercase(Locale.ROOT)
                if (lang.isNotBlank() && lang != "ur" && lang != "urd" && lang != "urdu" && !lang.startsWith("ur")) {
                    continue
                }
                if (item.optBoolean("ai_translated", false) ||
                    item.optBoolean("machine_translated", false) ||
                    item.optBoolean("auto_translated", false)
                ) {
                    continue
                }

                val authorText = buildString {
                    append(item.optString("author")).append(' ')
                    append(item.optString("uploader")).append(' ')
                    append(item.optString("username")).append(' ')
                    append(item.optString("comment")).append(' ')
                    append(item.optString("release_name")).append(' ')
                    append(item.optString("name")).append(' ')
                    append(item.optString("title"))
                }.trim()

                if (isSubDlAiEntry(authorText) || isSubDlAiEntry(item.toString())) {
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
                val tierScore = scoreSubDlAuthorTier(authorText)
                val prev = scoredUrls[fullUrl]
                if (prev == null || tierScore > prev) {
                    scoredUrls[fullUrl] = tierScore
                }
            }
            scoredUrls.entries
                .sortedByDescending { it.value }
                .map { it.key }
                .take(5)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readCrossModuleSharedPrefString(
        key: String,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null
    ): String? {
        runCatching { sharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        runCatching { StreamPlay.companionSharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val appCtx = context?.applicationContext ?: context ?: appContextRef ?: return null
        for (prefName in listOf("StreamPlay", "SuperStream", "cloudstream_subtitle_mode_prefs")) {
            val v = runCatching {
                appCtx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE).getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    fun resolveWyzieApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("wyzie_key", sharedPref, context),
            readCrossModuleSharedPrefString("wyzie_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.wyzie.key") }.getOrNull(),
            runCatching { System.getProperty("wyzie.api.key") }.getOrNull(),
            runCatching { System.getenv("WYZIE_API_KEY") }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveWyzieApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null
    ): String? = resolveWyzieApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveSubDlApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("subdl_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subdl.key") }.getOrNull(),
            runCatching { System.getProperty("subdl.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBDL_API_KEY") }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubDlApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null
    ): String? = resolveSubDlApiKey(apiKey = null, sharedPref = sharedPref, context = context)

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
            append("&languages=UR&subs_per_page=10&unpack=1&type=$type")
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
        val subDlLabel = formatUrduSourceLabel("subdl")
        var emitted = 0
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
                newSubtitleFile(subDlLabel, materializedUri) {
                    this.headers = dlHeaders
                }
            )
            emitted++
            if (emitted >= 2) break
        }
        return emitted
    }

    /**
     * SubDL Urdu Harvester (`invokeSubDlUrduKeyless`):
     * 1. Queries Official SubDL API v1 first if an API key is configured.
     * 2. Seamlessly falls back to keyless `https://api3.subdl.com/auto?query=...` + `/urdu` page
     *    harvesting (caching `imdbId|title|year -> slug` + negative sentinel across episodes),
     *    and materializes `.zip` Urdu archives into playable UTF-8-BOM `.srt` files.
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

            // 2. Keyless fallback via api3.subdl.com/auto + HTML/__NEXT_DATA__ with slug cache
            val headers = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Accept" to "application/json, text/plain, */*",
                "Origin" to SUBDL_WEB_HOST,
                "Referer" to "$SUBDL_WEB_HOST/"
            )
            val slugCacheKey = "${cleanImdb ?: ""}|${cleanTitle?.lowercase(Locale.ROOT) ?: ""}|${year ?: 0}"
            val cachedSlug = subDlSlugCache[slugCacheKey]
            val slugLink = when {
                cachedSlug == NEGATIVE_SLUG_SENTINEL -> return
                !cachedSlug.isNullOrBlank() -> cachedSlug
                else -> {
                    val autoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(query, "UTF-8")}"
                    val autoBody = withTimeoutOrNull(5_000L) {
                        StreamPlayExtractor.cancellableGetText(autoUrl, headers = headers, timeoutSec = 5L)
                    }
                    var resolved = autoBody?.let { parseSubDlAutoSlug(it, year) }
                    if (resolved == null && cleanImdb != null && cleanTitle != null) {
                        val fallbackAutoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                        val fallbackBody = withTimeoutOrNull(5_000L) {
                            StreamPlayExtractor.cancellableGetText(fallbackAutoUrl, headers = headers, timeoutSec = 5L)
                        }
                        resolved = fallbackBody?.let { parseSubDlAutoSlug(it, year) }
                    }
                    if (!resolved.isNullOrBlank()) {
                        subDlSlugCache[slugCacheKey] = resolved
                        if (cleanImdb != null) subDlSlugCache[cleanImdb] = resolved
                    } else if (autoBody != null) {
                        subDlSlugCache[slugCacheKey] = NEGATIVE_SLUG_SENTINEL
                    }
                    resolved
                }
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
            val subDlLabel = formatUrduSourceLabel("subdl")
            var emitted = 0
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
                    newSubtitleFile(subDlLabel, materializedUri) {
                        this.headers = dlHeaders
                    }
                )
                emitted++
                if (emitted >= 2) break
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

    fun resolveSubSourceApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("subsource_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subsource.key") }.getOrNull(),
            runCatching { System.getProperty("subsource.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBSOURCE_API_KEY") }.getOrNull()
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubSourceApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null
    ): String? = resolveSubSourceApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    /**
     * Official SubSource API v1 Urdu Harvester (`invokeSubSourceUrdu`):
     * Note: `api.subsource.net/api/v1` returns `401 Unauthorized` without a key; returns `0` / exits
     * immediately when `resolvedKey == null` so we never waste 3 sequential 5-second requests hitting 401s.
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
    ): Int {
        val resolvedKey = resolveSubSourceApiKey(apiKey, sharedPref) ?: return 0
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null) return 0

        val headers = mapOf(
            "User-Agent" to BROWSER_USER_AGENT,
            "Accept" to "application/json",
            "X-API-Key" to resolvedKey,
            "api-key" to resolvedKey
        )

        return try {
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

            // 2. Query Subtitles list via /api/v1/subtitles
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

            val subSourceLabel = formatUrduSourceLabel("subsource")
            var emitted = 0
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
                    newSubtitleFile(subSourceLabel, materializedUri) {
                        this.headers = dlHeaders
                    }
                )
                emitted++
                if (emitted >= 2) break
            }
            emitted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubSourceUrdu failed: ${e.message}")
            0
        }
    }

    private val SUBSENSE_HI_OR_AI_LABEL_REGEX = Regex(
        """(?i)(?:·\s*HI\b|\[HI\]|\bSDH\b|AI[\s_]*Translated|Auto[\s_]*Translated|Machine[\s_]*Translated)"""
    )

    /**
     * Parses SubSense Stremio JSON payload and extracts Urdu subtitle URLs where `lang` is
     * `"ur"`, `"urd"`, or `"urdu"`, skipping Hearing-Impaired (`hi` / `hearingImpaired` / `· HI` / `[HI]` / `SDH`)
     * and AI/Auto-translated entries.
     */
    internal fun parseSubSenseUrduJson(jsonBody: String): List<String> {
        if (jsonBody.isBlank()) return emptyList()
        return try {
            val root = JSONObject(jsonBody)
            val arr = root.optJSONArray("subtitles") ?: return emptyList()
            val urls = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optBoolean("hi", false) ||
                    obj.optBoolean("hearingImpaired", false) ||
                    obj.optBoolean("ai_translated", false)
                ) {
                    continue
                }
                val labelOrTitle = buildString {
                    append(obj.optString("label")).append(' ')
                    append(obj.optString("title")).append(' ')
                    append(obj.optString("name"))
                }.trim()
                if (labelOrTitle.isNotEmpty() && SUBSENSE_HI_OR_AI_LABEL_REGEX.containsMatchIn(labelOrTitle)) {
                    continue
                }
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
     * Urdu subtitles (`Urdu [SubSense]`) with `X-StreamPlay-Source: subsense`.
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

            val subSenseLabel = formatUrduSourceLabel("subsense")
            var emitted = 0
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
                    newSubtitleFile(subSenseLabel, materializedUri) {
                        this.headers = dlHeaders
                    }
                )
                emitted++
                if (emitted >= 2) break
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
     * Searches `https://subf2m.co/subtitles/searchbytitle?query=...` (with `title|year|isMovie -> slug`
     * + negative caching), verifies exact normalized title (and year for movies), fetches `/subtitles/${slug}/urdu`,
     * resolves top detail page `<a id="downloadButton" href="...">`, materializes the archive via
     * [materializeNormalizedUrduSubtitle], and emits `Urdu [Subf2m]` with `X-StreamPlay-Source: subf2m`.
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
            val headers = mapOf("User-Agent" to BROWSER_USER_AGENT)
            val slugCacheKey = "${cleanTitle.lowercase(Locale.ROOT)}|${year ?: 0}|$isMovie"
            val cachedSlug = subf2mSlugCache[slugCacheKey]
            val slug = when {
                cachedSlug == NEGATIVE_SLUG_SENTINEL -> return
                !cachedSlug.isNullOrBlank() -> cachedSlug
                else -> {
                    val searchUrl = "$SUBF2M_HOST/subtitles/searchbytitle?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                    val searchHtml = withTimeoutOrNull(5_000L) {
                        StreamPlayExtractor.cancellableGetText(searchUrl, headers = headers, timeoutSec = 5L)
                    } ?: return
                    val resolved = parseSubf2mSearchSlug(searchHtml, cleanTitle, year, isMovie)
                    if (!resolved.isNullOrBlank()) {
                        subf2mSlugCache[slugCacheKey] = resolved
                    } else {
                        subf2mSlugCache[slugCacheKey] = NEGATIVE_SLUG_SENTINEL
                    }
                    resolved
                }
            } ?: return

            val urduListUrl = "$SUBF2M_HOST/subtitles/$slug/urdu"
            val listHtml = withTimeoutOrNull(5_000L) {
                StreamPlayExtractor.cancellableGetText(urduListUrl, headers = headers, timeoutSec = 5L)
            } ?: return

            val detailUrls = parseSubf2mUrduDetailLinks(listHtml, slug, season, episode)
            val subf2mLabel = formatUrduSourceLabel("subf2m")
            var emitted = 0
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
                    newSubtitleFile(subf2mLabel, materializedUri) {
                        this.headers = dlHeaders
                    }
                )
                emitted++
                if (emitted >= 2) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invokeSubf2mUrdu failed: ${e.message}")
        }
    }
}
