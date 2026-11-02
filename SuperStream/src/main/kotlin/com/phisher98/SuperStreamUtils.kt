package com.phisher98

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.Qualities
import java.util.Locale

const val SUBTITLE_MODE_PREFS_NAME = "cloudstream_subtitle_mode_prefs"
const val HOST_SUBTITLE_AUTO_SELECT_KEY = "subtitles_auto_select"
const val SUBTITLE_MODE_SYS_PROP = "cloudstream.subtitle.mode"
const val SUBTITLE_MODE_KEY = "subtitle_mode"
const val MODE_DEFAULT = "default"
const val MODE_URDU = "urdu"

@Volatile
private var superStreamAppContextRef: Context? = null

private fun normalizeSubtitleModeValue(raw: String?): String? {
    return when (raw?.trim()?.lowercase(Locale.ROOT)) {
        MODE_URDU, "ur", "urd", "ur-pk", "ur-in" -> MODE_URDU
        MODE_DEFAULT, "en", "eng", "english", "en-us", "en-gb" -> MODE_DEFAULT
        else -> null
    }
}

fun isUrduSubtitleMode(
    sharedPref: SharedPreferences? = null,
    context: Context? = null
): Boolean {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val sysProp = try {
        System.getProperty(SUBTITLE_MODE_SYS_PROP)
    } catch (_: Throwable) {
        null
    }
    normalizeSubtitleModeValue(sysProp)?.let { return it == MODE_URDU }

    val ctx = context?.applicationContext ?: context ?: superStreamAppContextRef
    if (ctx != null) {
        val sharedVal = try {
            ctx.getSharedPreferences(SUBTITLE_MODE_PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(SUBTITLE_MODE_KEY, null)
        } catch (_: Throwable) {
            null
        }
        normalizeSubtitleModeValue(sharedVal)?.let { return it == MODE_URDU }
    }

    val prefVal = try {
        sharedPref?.getString(SUBTITLE_MODE_KEY, null)
    } catch (_: Throwable) {
        null
    }
    normalizeSubtitleModeValue(prefVal)?.let { return it == MODE_URDU }

    if (ctx != null) {
        val streamPlayVal = try {
            ctx.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                ?.getString(SUBTITLE_MODE_KEY, null)
        } catch (_: Throwable) {
            null
        }
        normalizeSubtitleModeValue(streamPlayVal)?.let { return it == MODE_URDU }
    }

    return false
}

fun initializeSubtitleMode(context: Context?, sharedPref: SharedPreferences?) {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val urdu = isUrduSubtitleMode(sharedPref, context)
    val resolvedMode = if (urdu) MODE_URDU else MODE_DEFAULT
    val currentSysProp = try {
        System.getProperty(SUBTITLE_MODE_SYS_PROP)
    } catch (_: Throwable) {
        null
    }
    if (normalizeSubtitleModeValue(currentSysProp) == null) {
        try {
            System.setProperty(SUBTITLE_MODE_SYS_PROP, resolvedMode)
        } catch (_: Throwable) {
        }
    }
}

@Suppress("DEPRECATION")
fun setSubtitleMode(mode: String, context: Context?, sharedPref: SharedPreferences?) {
    if (context != null) {
        superStreamAppContextRef = context.applicationContext ?: context
    }
    val normalizedMode = normalizeSubtitleModeValue(mode) ?: MODE_DEFAULT
    try {
        System.setProperty(SUBTITLE_MODE_SYS_PROP, normalizedMode)
    } catch (_: Throwable) {
    }

    try {
        sharedPref?.edit()?.putString(SUBTITLE_MODE_KEY, normalizedMode)?.apply()
    } catch (_: Throwable) {
    }

    val ctx = context?.applicationContext ?: context ?: superStreamAppContextRef
    if (ctx != null) {
        try {
            ctx.getSharedPreferences(SUBTITLE_MODE_PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        } catch (_: Throwable) {
        }
        try {
            ctx.getSharedPreferences("SuperStream", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        } catch (_: Throwable) {
        }
        try {
            ctx.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        } catch (_: Throwable) {
        }
        val targetAutoSelect = if (normalizedMode == MODE_URDU) "ur" else "en"
        try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                ?.edit()
                ?.putString(HOST_SUBTITLE_AUTO_SELECT_KEY, targetAutoSelect)
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        } catch (_: Throwable) {
        }
        try {
            ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(HOST_SUBTITLE_AUTO_SELECT_KEY, targetAutoSelect)
                ?.putString(SUBTITLE_MODE_KEY, normalizedMode)
                ?.apply()
        } catch (_: Throwable) {
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


//Catflix

fun CathexToBinary(hex: String): String {
    val binary = StringBuilder()
    for (i in hex.indices step 2) {
        val hexPair = hex.substring(i, i + 2)
        val charValue = hexPair.toInt(16).toChar()
        binary.append(charValue)
    }
    return binary.toString()
}

fun CatxorDecrypt(binary: String, key: String): String {
    val decrypted = StringBuilder()
    val keyLength = key.length

    for (i in binary.indices) {
        val decryptedChar = binary[i].code xor key[i % keyLength].code
        decrypted.append(decryptedChar.toChar())
    }

    return decrypted.toString()
}

fun CatdecryptHexWithKey(hex: String, key: String): String {
    val binary = CathexToBinary(hex)
    return CatxorDecrypt(binary, key)
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