package com.phisher98

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.subtitles.BolChalConfig
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.StreamProviderKey
import com.phisher98.subtitles.SubtitleModeBridge
import java.util.Locale

const val SUBTITLE_BOLCHAL_PREFS_NAME = SubtitleModeBridge.PREFS_URDU_BOLCHAL
const val SUBTITLE_MODE_PREFS_NAME = SubtitleModeBridge.PREFS_LEGACY_MODE
const val HOST_SUBTITLE_AUTO_SELECT_KEY = "subtitles_auto_select"
const val SUBTITLE_MODE_SYS_PROP = SubtitleModeBridge.PROP_SUBTITLE_MODE
const val SUBTITLE_MODE_KEY = "subtitle_mode"
const val MODE_DEFAULT = "default"
const val MODE_URDU = "urdu"

@Volatile
internal var superStreamAppContextRef: Context? = null

private val SUPERSTREAM_SHARED_PREF_NAMES = listOf(
    SUBTITLE_BOLCHAL_PREFS_NAME,
    SUBTITLE_MODE_PREFS_NAME,
    "SuperStream",
    "StreamPlay",
)

private fun normalizeSubtitleModeValue(raw: String?): String? {
    return when (raw?.trim()?.lowercase(Locale.ROOT)) {
        MODE_URDU, "ur", "urd", "ur-pk", "ur-in" -> MODE_URDU
        MODE_DEFAULT, "en", "eng", "english", "en-us", "en-gb" -> MODE_DEFAULT
        else -> null
    }
}

private fun cacheSubtitleModeProperty(mode: String) {
    runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, mode) }
}

internal fun readSuperStreamNamedPrefString(
    prefsName: String,
    key: String,
    sharedPref: SharedPreferences? = null,
    context: Context? = null,
): String? {
    val ctx = context?.applicationContext ?: context ?: superStreamAppContextRef
    if (ctx != null) {
        val namedVal = runCatching {
            ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)?.getString(key, null)
        }.getOrNull()
        if (!namedVal.isNullOrBlank()) return namedVal.trim()
    }
    val directVal = runCatching { sharedPref?.getString(key, null) }.getOrNull()
    return directVal?.takeIf { it.isNotBlank() }?.trim()
}

fun isUrduSubtitleMode(
    sharedPref: SharedPreferences? = null,
    context: Context? = null,
): Boolean {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val sysProp = runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull()
    normalizeSubtitleModeValue(sysProp)?.let { return it == MODE_URDU }

    val ctx = context?.applicationContext ?: context ?: superStreamAppContextRef
    if (ctx != null) {
        for (prefName in SUPERSTREAM_SHARED_PREF_NAMES) {
            val sharedVal = runCatching {
                ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    ?.getString(SUBTITLE_MODE_KEY, null)
            }.getOrNull()
            normalizeSubtitleModeValue(sharedVal)?.let {
                cacheSubtitleModeProperty(it)
                return it == MODE_URDU
            }
        }
    }

    val prefVal = runCatching { sharedPref?.getString(SUBTITLE_MODE_KEY, null) }.getOrNull()
    normalizeSubtitleModeValue(prefVal)?.let {
        cacheSubtitleModeProperty(it)
        return it == MODE_URDU
    }

    if (context != null || sharedPref != null) {
        cacheSubtitleModeProperty(MODE_DEFAULT)
    }
    return false
}

fun initializeSubtitleMode(context: Context?, sharedPref: SharedPreferences?) {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val urdu = isUrduSubtitleMode(sharedPref, context)
    val resolvedMode = if (urdu) MODE_URDU else MODE_DEFAULT
    val currentSysProp = runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull()
    if (normalizeSubtitleModeValue(currentSysProp) == null) {
        cacheSubtitleModeProperty(resolvedMode)
    }
}

fun setSubtitleMode(mode: String, context: Context?, sharedPref: SharedPreferences?) {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val normalizedMode = normalizeSubtitleModeValue(mode) ?: MODE_DEFAULT
    cacheSubtitleModeProperty(normalizedMode)

    runCatching {
        sharedPref?.edit()?.putString(SUBTITLE_MODE_KEY, normalizedMode)?.apply()
    }

    val ctx = context?.applicationContext ?: context ?: superStreamAppContextRef
    if (ctx != null) {
        for (prefName in SUPERSTREAM_SHARED_PREF_NAMES) {
            runCatching {
                ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                    ?.apply()
            }
        }
        val targetAutoSelect = if (normalizedMode == MODE_URDU) "ur" else "en"
        runCatching {
            ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(HOST_SUBTITLE_AUTO_SELECT_KEY, targetAutoSelect)
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        }
    }
}

fun resolveSuperStreamSessionMode(
    subtitleCallback: Any?,
    sharedPref: SharedPreferences? = null,
    context: Context? = null,
): SessionSubtitleMode {
    if (subtitleCallback != null &&
        (SubtitleModeBridge.isBilingualDownloadCallback(subtitleCallback) ||
            subtitleCallback.javaClass.name.contains("BilingualDownload", ignoreCase = true))
    ) {
        return SessionSubtitleMode.BILINGUAL_DOWNLOAD
    }
    return SubtitleModeBridge.resolveSessionMode(
        subtitleCallback = subtitleCallback,
        pluginFallbackUrduEnabled = isUrduSubtitleMode(sharedPref, context),
        prefsLookup = { prefsName, key ->
            readSuperStreamNamedPrefString(prefsName, key, sharedPref, context)
        },
    )
}

fun resolveSuperStreamBolChalConfig(
    sharedPref: SharedPreferences? = null,
    context: Context? = null,
): BolChalConfig {
    val byok = SubtitleModeBridge.resolveByokLlmConfig { prefsName, key ->
        readSuperStreamNamedPrefString(prefsName, key, sharedPref, context)
    }
    return if (byok != null) {
        BolChalConfig(
            llmEndpointUrl = byok.endpointUrl,
            llmModelId = byok.modelId,
            llmApiKey = byok.apiKey,
        )
    } else {
        BolChalConfig()
    }
}

const val SUPERSTREAM_SOURCE_HEADER = ProvenanceCodec.HEADER_SOURCE

private val DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS = setOf(
    "addic7ed",
    "vidsrc",
    "superstream",
)

private val SUPERSTREAM_URDU_CANONICAL_DISPLAY_NAMES: Map<String, String> = buildMap {
    for (src in CatalogueSourceKey.entries) {
        put(src.wireId, src.displayBadge)
    }
    put("subtitles", "OpenSubtitles")
    for (prov in StreamProviderKey.entries) {
        put(prov.wireId, prov.displayBadge)
    }
    put("urdu-neural-fallback", "UrduAI")
}

fun canonicalSuperStreamUrduSourceKey(raw: String?): String? {
    val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    if (cleaned in DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS) return null
    if (cleaned == "urdu-neural-fallback" || cleaned == "urduai" || cleaned == "bolchal" ||
        cleaned == "neural" || cleaned.contains("urdu-neural") || cleaned.contains("neural-ai") ||
        cleaned.contains("neural ai") || cleaned.contains("spoken-ai") || cleaned.contains("spoken ai") ||
        cleaned.startsWith("urduai ")
    ) {
        return "urdu-neural-fallback"
    }
    if (cleaned == "stremio-os-urdu" || cleaned == "subtitles") {
        return "subtitles"
    }
    CatalogueSourceKey.fromTextOrHost(cleaned)?.let { src ->
        return if (src.wireId in DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS) null else src.wireId
    }
    StreamProviderKey.fromTextOrHost(cleaned)?.let { return it.wireId }
    return null
}

fun superStreamUrduSourceDisplayName(sourceKeyOrBadge: String?): String? {
    val canonical = canonicalSuperStreamUrduSourceKey(sourceKeyOrBadge) ?: return null
    return SUPERSTREAM_URDU_CANONICAL_DISPLAY_NAMES[canonical]
}

fun extractSuperStreamUrduSourceBadge(label: String?): String? {
    if (label.isNullOrBlank()) return null
    val trimmed = label.trim()
    val aiBracket = Regex("""\[\s*UrduAI(?:\s+([^\]]+))?\s*]""", RegexOption.IGNORE_CASE).find(trimmed)
    if (aiBracket != null) {
        val donorSuffix = aiBracket.groupValues[1].trim()
        return if (donorSuffix.isNotEmpty()) "UrduAI $donorSuffix" else "UrduAI"
    }
    val knownBadges = (SUPERSTREAM_URDU_CANONICAL_DISPLAY_NAMES.values + listOf("Neural AI", "Spoken AI")).distinct()
    for (badge in knownBadges) {
        if (trimmed.equals(badge, ignoreCase = true) ||
            trimmed.contains("[$badge]", ignoreCase = true) ||
            trimmed.contains("($badge)", ignoreCase = true) ||
            trimmed.endsWith("- $badge", ignoreCase = true) ||
            trimmed.endsWith("• $badge", ignoreCase = true)
        ) {
            return if (badge.equals("Spoken AI", ignoreCase = true) || badge.equals("Neural AI", ignoreCase = true)) {
                "UrduAI"
            } else {
                badge
            }
        }
    }
    return null
}

fun formatSuperStreamUrduSourceLabel(sourceKeyOrBadge: String?, modifier: String? = null): String {
    val badge = superStreamUrduSourceDisplayName(sourceKeyOrBadge) ?: extractSuperStreamUrduSourceBadge(sourceKeyOrBadge)
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

fun isUrduSubtitleCode(lang: String?): Boolean {
    val normalized = lang?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized == "ur" ||
        normalized == "urd" ||
        normalized.startsWith("ur-") ||
        normalized.startsWith("ur_") ||
        normalized.contains("urdu") ||
        normalized.contains("اردو")
}

fun isEnglishSubtitleCode(lang: String?): Boolean {
    val normalized = lang?.trim()?.lowercase(Locale.ROOT) ?: return false
    if (normalized == "en" ||
        normalized == "eng" ||
        normalized == "en-us" ||
        normalized == "en-gb" ||
        normalized == "english" ||
        normalized.startsWith("en-") ||
        normalized.startsWith("en_") ||
        normalized.contains("english")
    ) {
        return true
    }
    return false
}

fun getLanguage(language: String?): String? {
    language ?: return null
    val normalizedLang = language.substringBefore("-")
    return languageMap.entries.find { it.value.first == normalizedLang || it.value.second == normalizedLang }?.key
}

private val languageMap = mapOf(
    "Afrikaans" to Pair("af", "afr"),
    "Albanian" to Pair("sq", "sqi"),
    "Amharic" to Pair("am", "amh"),
    "Arabic" to Pair("ar", "ara"),
    "Armenian" to Pair("hy", "hye"),
    "Azerbaijani" to Pair("az", "aze"),
    "Basque" to Pair("eu", "eus"),
    "Belarusian" to Pair("be", "bel"),
    "Bengali" to Pair("bn", "ben"),
    "Bosnian" to Pair("bs", "bos"),
    "Bulgarian" to Pair("bg", "bul"),
    "Catalan" to Pair("ca", "cat"),
    "Chinese" to Pair("zh", "zho"),
    "Croatian" to Pair("hr", "hrv"),
    "Czech" to Pair("cs", "ces"),
    "Danish" to Pair("da", "dan"),
    "Dutch" to Pair("nl", "nld"),
    "English" to Pair("en", "eng"),
    "Estonian" to Pair("et", "est"),
    "Filipino" to Pair("tl", "tgl"),
    "Finnish" to Pair("fi", "fin"),
    "French" to Pair("fr", "fra"),
    "Galician" to Pair("gl", "glg"),
    "Georgian" to Pair("ka", "kat"),
    "German" to Pair("de", "deu"),
    "Greek" to Pair("el", "ell"),
    "Gujarati" to Pair("gu", "guj"),
    "Hebrew" to Pair("he", "heb"),
    "Hindi" to Pair("hi", "hin"),
    "Hungarian" to Pair("hu", "hun"),
    "Icelandic" to Pair("is", "isl"),
    "Indonesian" to Pair("id", "ind"),
    "Italian" to Pair("it", "ita"),
    "Japanese" to Pair("ja", "jpn"),
    "Kannada" to Pair("kn", "kan"),
    "Kazakh" to Pair("kk", "kaz"),
    "Korean" to Pair("ko", "kor"),
    "Latvian" to Pair("lv", "lav"),
    "Lithuanian" to Pair("lt", "lit"),
    "Macedonian" to Pair("mk", "mkd"),
    "Malay" to Pair("ms", "msa"),
    "Malayalam" to Pair("ml", "mal"),
    "Maltese" to Pair("mt", "mlt"),
    "Marathi" to Pair("mr", "mar"),
    "Mongolian" to Pair("mn", "mon"),
    "Nepali" to Pair("ne", "nep"),
    "Norwegian" to Pair("no", "nor"),
    "Persian" to Pair("fa", "fas"),
    "Polish" to Pair("pl", "pol"),
    "Portuguese" to Pair("pt", "por"),
    "Punjabi" to Pair("pa", "pan"),
    "Romanian" to Pair("ro", "ron"),
    "Russian" to Pair("ru", "rus"),
    "Serbian" to Pair("sr", "srp"),
    "Sinhala" to Pair("si", "sin"),
    "Slovak" to Pair("sk", "slk"),
    "Slovenian" to Pair("sl", "slv"),
    "Spanish" to Pair("es", "spa"),
    "Swahili" to Pair("sw", "swa"),
    "Swedish" to Pair("sv", "swe"),
    "Tamil" to Pair("ta", "tam"),
    "Telugu" to Pair("te", "tel"),
    "Thai" to Pair("th", "tha"),
    "Turkish" to Pair("tr", "tur"),
    "Ukrainian" to Pair("uk", "ukr"),
    "Urdu" to Pair("ur", "urd"),
    "Uzbek" to Pair("uz", "uzb"),
    "Vietnamese" to Pair("vi", "vie"),
    "Welsh" to Pair("cy", "cym"),
    "Yiddish" to Pair("yi", "yid")
)


private val QUALITY_REGEX = Regex("(\\d{3,4})[pP]")
private val SLUG_WHITESPACE_REGEX = Regex("\\s+")

private val TITLE_EXT_REGEX = Regex("\\.[a-zA-Z0-9]{2,4}$")
private val WEB_DL_REGEX = Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE)
private val WEB_RIP_REGEX = Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE)
private val H265_REGEX = Regex("H[ .]?265", RegexOption.IGNORE_CASE)
private val H264_REGEX = Regex("H[ .]?264", RegexOption.IGNORE_CASE)
private val DDP_REGEX = Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE)

private val SOURCE_TAGS = setOf(
    "WEB-DL", "WEBRIP", "BLURAY", "HDRIP",
    "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
)

private val CODEC_TAGS = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")

private val AUDIO_TAGS = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")

private val AUDIO_EXTRAS = setOf("ATMOS")

private val HDR_TAGS = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        val s = season ?: 0
        val e = episode ?: 0
        (if (s < 10) "0$s" else "$s") to (if (e < 10) "0$e" else "$e")
    }
}

fun getIndexQuality(str: String?): Int {
    return QUALITY_REGEX.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun String?.createSlug(): String? {
    return this?.filter { it.isWhitespace() || it.isLetterOrDigit() }
        ?.trim()
        ?.replace(SLUG_WHITESPACE_REGEX, "-")
        ?.lowercase()
}

fun cleanTitle(title: String): String {
    val name = title.replace(TITLE_EXT_REGEX, "")

    val normalized = name
        .replace(WEB_DL_REGEX, "WEB-DL")
        .replace(WEB_RIP_REGEX, "WEBRIP")
        .replace(H265_REGEX, "H265")
        .replace(H264_REGEX, "H264")
        .replace(DDP_REGEX, "DDP$1")

    val parts = normalized.split(" ", "_", ".")

    val filtered = parts.mapNotNull { part ->
        val p = part.uppercase()

        when {
            SOURCE_TAGS.contains(p) -> p
            CODEC_TAGS.contains(p) -> p
            AUDIO_TAGS.any { p.startsWith(it) } -> p
            AUDIO_EXTRAS.contains(p) -> p
            HDR_TAGS.contains(p) -> {
                when (p) {
                    "DV", "DOLBYVISION" -> "DOLBYVISION"
                    else -> p
                }
            }
            p == "NF" || p == "CR" -> p
            else -> null
        }
    }

    return filtered.distinct().joinToString(" ")
}