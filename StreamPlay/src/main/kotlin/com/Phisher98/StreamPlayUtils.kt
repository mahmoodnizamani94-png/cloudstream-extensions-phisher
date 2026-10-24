package com.phisher98

import android.content.SharedPreferences
import androidx.core.net.toUri
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.gson.Gson
import java.text.Normalizer
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.amapIndexed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.RequestBodyTypes
import com.phisher98.StreamPlay.Companion.anilistAPI
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

val sharedPref: SharedPreferences? = null
private const val SAFE_GET_MAX_CONCURRENCY = 64
val appGlobalSemaphore = Semaphore(SAFE_GET_MAX_CONCURRENCY)
private val sharedObjectMapper by lazy { ObjectMapper() }
private val sharedGson by lazy { Gson() }
private val tmdbDateFormatter = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue(): SimpleDateFormat {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
}

private fun getTmdbDateFormatter(): SimpleDateFormat = tmdbDateFormatter.get()!!

suspend fun convertTmdbToAnimeId(
    title: String?,
    date: String?,
    airedDate: String?,
    type: TvType
): AniIds {
    val sDate = date?.split("-")
    val sAiredDate = airedDate?.split("-")

    val year = sDate?.firstOrNull()?.toIntOrNull()
    val airedYear = sAiredDate?.firstOrNull()?.toIntOrNull()
    val season = getSeason(sDate?.getOrNull(1)?.toIntOrNull())
    val airedSeason = getSeason(sAiredDate?.getOrNull(1)?.toIntOrNull())

    return if (type == TvType.AnimeMovie) {
        val byAired = tmdbToAnimeId(title, airedYear ?: year, "", type)
        if (byAired.id == null && byAired.idMal == null && (airedYear != null || year != null)) {
            tmdbToAnimeId(title, null, "", type)
        } else {
            byAired
        }
    } else {
        val ids = tmdbToAnimeId(title, year, season, type)
        val afterAired = if (ids.id == null && ids.idMal == null && airedYear != null && airedYear != year) {
            tmdbToAnimeId(title, airedYear, airedSeason, type)
        } else {
            ids
        }
        if (afterAired.id == null && afterAired.idMal == null && (year != null || airedYear != null)) {
            tmdbToAnimeId(title, null, null, type)
        } else {
            afterAired
        }
    }
}

suspend fun tmdbToAnimeId(title: String?, year: Int?, season: String?, type: TvType): AniIds {
    val query = """
        query (
          ${'$'}page: Int = 1
          ${'$'}search: String
          ${'$'}sort: [MediaSort] = [POPULARITY_DESC, SCORE_DESC]
          ${'$'}type: MediaType
          ${'$'}seasonYear: Int
          ${'$'}format: [MediaFormat]
        ) {
          Page(page: ${'$'}page, perPage: 20) {
            media(
              search: ${'$'}search
              sort: ${'$'}sort
              type: ${'$'}type
              seasonYear: ${'$'}seasonYear
              format_in: ${'$'}format
            ) {
              id
              idMal
            }
          }
        }
    """.trimIndent().trim()

    val variables = mapOf(
        "search" to title,
        "sort" to "SEARCH_MATCH",
        "type" to "ANIME",
        //"season" to season?.uppercase(),
        "seasonYear" to year,
        "format" to listOf(if (type == TvType.AnimeMovie) "MOVIE" else "TV", "ONA")
    ).filterValues { value -> value != null && value.toString().isNotEmpty() }
    val data = mapOf(
        "query" to query,
        "variables" to variables
    ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
    val res = app.post(anilistAPI, requestBody = data)
        .parsedSafe<AniSearch>()?.data?.Page?.media?.firstOrNull()
    return AniIds(res?.id, res?.idMal)

}


suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    quality: Int? = null,
    size: String = ""
) {
    val provider = source.trim().takeIf { it.isNotBlank() }
    val sizePart = size.trim().takeIf { it.isNotBlank() }

    loadExtractor(url, referer, subtitleCallback) { link ->
        val label = buildString {
            provider?.let { append(it) }
            if (link.name.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append(link.name)
            }
            sizePart?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }

        @Suppress("DEPRECATION")
        val rawLink = ExtractorLink(
            source = link.source,
            name = label,
            url = link.url,
            referer = link.referer,
            quality = quality ?: link.quality,
            type = link.type,
            headers = link.headers,
            extractorData = link.extractorData
        )
        callback(StreamPlayLinkOptimizer.optimize(rawLink))
    }
}


fun getSeason(month: Int?): String? {
    val seasons = arrayOf(
        "Winter", "Winter", "Spring", "Spring", "Spring", "Summer",
        "Summer", "Summer", "Fall", "Fall", "Fall", "Winter"
    )
    if (month == null) return null
    return seasons[month - 1]
}

fun getIndexQuality(str: String?): Int {
    val opt = StreamLinkOptimizer.extractQualityFromText(str)
    if (opt > 0 && opt != Qualities.Unknown.value) return opt
    return Regex("""(?i)(?:^|[^a-zA-Z0-9])(2160|1440|1080|720|576|540|480|360)\s*[pP]?(?:[^a-zA-Z0-9]|$)""").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return runCatching {
        URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }.getOrDefault(url.substringBefore("/", ""))
}


fun String.getHost(): String {
    return runCatching {
        fixTitle(URI(this).host.substringBeforeLast(".").substringAfterLast("."))
    }.getOrDefault("Source")
}

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = getTmdbDateFormatter()
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        // 36-hour buffer accounts for global release timezone differences (e.g. JST UTC+9 vs UTC-12)
        // so movies and anime episodes released today are never falsely gated as Upcoming/Coming Soon.
        unixTimeMS < (dateTime - 36L * 3600L * 1000L)
    } catch (t: Throwable) {
        logError(t)
        false
    }
}

fun getDate(): TmdbDate {
    val formatter = getTmdbDateFormatter()
    val calendar = Calendar.getInstance()

    // Today
    val today = formatter.format(calendar.time)

    // Next week
    calendar.add(Calendar.WEEK_OF_YEAR, 1)
    val nextWeek = formatter.format(calendar.time)

    // Last week's Monday
    calendar.time = Date()
    calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    calendar.add(Calendar.WEEK_OF_YEAR, -1)
    val lastWeekStart = formatter.format(calendar.time)

    // Start of current month
    calendar.time = Date()
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    val monthStart = formatter.format(calendar.time)

    return TmdbDate(today, nextWeek, lastWeekStart, monthStart)
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

fun Int.toRomanNumeral(): String = Symbol.closestBelow(this)
    .let { symbol ->
        if (symbol != null) {
            "$symbol${(this - symbol.decimalValue).toRomanNumeral()}"
        } else {
            ""
        }
    }

private enum class Symbol(val decimalValue: Int) {
    I(1),
    IV(4),
    V(5),
    IX(9),
    X(10);

    companion object {
        fun closestBelow(value: Int) =
            entries.toTypedArray()
                .sortedByDescending { it.decimalValue }
                .firstOrNull { value >= it.decimalValue }
    }
}

/**
 * Conforming with CryptoJS AES method
 */
// see https://gist.github.com/thackerronak/554c985c3001b16810af5fc0eb5c358f
@Suppress("unused", "FunctionName", "SameParameterValue")
object CryptoJS {

    private const val KEY_SIZE = 256
    private const val IV_SIZE = 128
    private const val HASH_CIPHER = "AES/CBC/PKCS7Padding"
    private const val AES = "AES"
    private const val KDF_DIGEST = "MD5"

    // Seriously crypto-js, what's wrong with you?
    private const val APPEND = "Salted__"

    /**
     * Encrypt
     * @param password passphrase
     * @param plainText plain string
     */
    fun encrypt(password: String, plainText: String): String {
        val saltBytes = generateSalt(8)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val keyS = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keyS, ivSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray())
        // Thanks kientux for this: https://gist.github.com/kientux/bb48259c6f2133e628ad
        // Create CryptoJS-like encrypted!
        val sBytes = APPEND.toByteArray()
        val b = ByteArray(sBytes.size + saltBytes.size + cipherText.size)
        System.arraycopy(sBytes, 0, b, 0, sBytes.size)
        System.arraycopy(saltBytes, 0, b, sBytes.size, saltBytes.size)
        System.arraycopy(cipherText, 0, b, sBytes.size + saltBytes.size, cipherText.size)
        val bEncode = base64Encode(b).encodeToByteArray()
        return String(bEncode)
    }

    /**
     * Decrypt
     * Thanks Artjom B. for this: http://stackoverflow.com/a/29152379/4405051
     * @param password passphrase
     * @param cipherText encrypted string
     */
    fun decrypt(password: String, cipherText: String): String {
        val ctBytes = base64DecodeArray(cipherText)
        val saltBytes = Arrays.copyOfRange(ctBytes, 8, 16)
        val cipherTextBytes = Arrays.copyOfRange(ctBytes, 16, ctBytes.size)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val keyS = SecretKeySpec(key, AES)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))
        val plainText = cipher.doFinal(cipherTextBytes)
        return String(plainText)
    }

    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        resultKey: ByteArray,
        resultIv: ByteArray
    ): ByteArray {
        return EvpKDF(password, keySize, ivSize, salt, 1, KDF_DIGEST, resultKey, resultIv)
    }

    @Suppress("NAME_SHADOWING")
    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        iterations: Int,
        hashAlgorithm: String,
        resultKey: ByteArray,
        resultIv: ByteArray
    ): ByteArray {
        val keySize = keySize / 32
        val ivSize = ivSize / 32
        val targetKeySize = keySize + ivSize
        val derivedBytes = ByteArray(targetKeySize * 4)
        var numberOfDerivedWords = 0
        var block: ByteArray? = null
        val hash = MessageDigest.getInstance(hashAlgorithm)
        while (numberOfDerivedWords < targetKeySize) {
            if (block != null) {
                hash.update(block)
            }
            hash.update(password)
            block = hash.digest(salt)
            hash.reset()
            // Iterations
            for (i in 1 until iterations) {
                block = hash.digest(block!!)
                hash.reset()
            }
            System.arraycopy(
                block!!, 0, derivedBytes, numberOfDerivedWords * 4,
                min(block.size, (targetKeySize - numberOfDerivedWords) * 4)
            )
            numberOfDerivedWords += block.size / 4
        }
        System.arraycopy(derivedBytes, 0, resultKey, 0, keySize * 4)
        System.arraycopy(derivedBytes, keySize * 4, resultIv, 0, ivSize * 4)
        return derivedBytes // key + iv
    }

    private fun generateSalt(length: Int): ByteArray {
        return ByteArray(length).apply {
            SecureRandom().nextBytes(this)
        }
    }
}

val languageMap: Map<String, Set<String>> = mapOf(
    "Afrikaans"   to setOf("af", "afr"),
    "Albanian"    to setOf("sq", "sqi", "alb"),
    "Amharic"     to setOf("am", "amh"),
    "Arabic"      to setOf("ar", "ara"),
    "Armenian"    to setOf("hy", "hye", "arm"),
    "Azerbaijani" to setOf("az", "aze"),
    "Basque"      to setOf("eu", "eus", "baq"),
    "Belarusian"  to setOf("be", "bel"),
    "Bengali"     to setOf("bn", "ben"),
    "Bosnian"     to setOf("bs", "bos"),
    "Bulgarian"   to setOf("bg", "bul"),
    "Catalan"     to setOf("ca", "cat"),
    "Chinese"     to setOf("zh", "zho", "chi"),
    "Croatian"    to setOf("hr", "hrv", "scr"),
    "Czech"       to setOf("cs", "ces", "cze"),
    "Danish"      to setOf("da", "dan"),
    "Dutch"       to setOf("nl", "nld", "dut"),
    "English"     to setOf("en", "eng"),
    "Estonian"    to setOf("et", "est"),
    "Filipino"    to setOf("tl", "tgl", "fil"),
    "Finnish"     to setOf("fi", "fin"),
    "French"      to setOf("fr", "fra", "fre"),
    "Galician"    to setOf("gl", "glg"),
    "Georgian"    to setOf("ka", "kat", "geo"),
    "German"      to setOf("de", "deu", "ger"),
    "Greek"       to setOf("el", "ell", "gre"),
    "Gujarati"    to setOf("gu", "guj"),
    "Hebrew"      to setOf("he", "heb"),
    "Hindi"       to setOf("hi", "hin"),
    "Hungarian"   to setOf("hu", "hun"),
    "Icelandic"   to setOf("is", "isl", "ice"),
    "Indonesian"  to setOf("id", "ind"),
    "Italian"     to setOf("it", "ita"),
    "Japanese"    to setOf("ja", "jpn"),
    "Kannada"     to setOf("kn", "kan"),
    "Kazakh"      to setOf("kk", "kaz"),
    "Korean"      to setOf("ko", "kor"),
    "Latvian"     to setOf("lv", "lav"),
    "Lithuanian"  to setOf("lt", "lit"),
    "Macedonian"  to setOf("mk", "mkd", "mac"),
    "Malay"       to setOf("ms", "msa", "may"),
    "Malayalam"   to setOf("ml", "mal"),
    "Maltese"     to setOf("mt", "mlt"),
    "Marathi"     to setOf("mr", "mar"),
    "Mongolian"   to setOf("mn", "mon"),
    "Nepali"      to setOf("ne", "nep"),
    "Norwegian"   to setOf("no", "nor"),
    "Persian"     to setOf("fa", "fas", "per"),
    "Polish"      to setOf("pl", "pol"),
    "Portuguese"  to setOf("pt", "por"),
    "Punjabi"     to setOf("pa", "pan"),
    "Romanian"    to setOf("ro", "ron", "rum"),
    "Russian"     to setOf("ru", "rus"),
    "Serbian"     to setOf("sr", "srp", "scc"),
    "Sinhala"     to setOf("si", "sin"),
    "Slovak"      to setOf("sk", "slk", "slo"),
    "Slovenian"   to setOf("sl", "slv"),
    "Spanish"     to setOf("es", "spa"),
    "Swahili"     to setOf("sw", "swa"),
    "Swedish"     to setOf("sv", "swe"),
    "Tamil"       to setOf("ta", "tam"),
    "Telugu"      to setOf("te", "tel"),
    "Thai"        to setOf("th", "tha"),
    "Turkish"     to setOf("tr", "tur"),
    "Ukrainian"   to setOf("uk", "ukr"),
    "Urdu"        to setOf("ur", "urd"),
    "Uzbek"       to setOf("uz", "uzb"),
    "Vietnamese"  to setOf("vi", "vie"),
    "Welsh"       to setOf("cy", "cym", "wel"),
    "Yiddish"     to setOf("yi", "yid")
)

val nativeToStandardLanguage: Map<String, String> = mapOf(
    "español" to "Spanish",
    "espanol" to "Spanish",
    "castellano" to "Spanish",
    "español (latinoamérica)" to "Spanish [Latin America]",
    "español latino" to "Spanish [Latin America]",
    "spanish (latin america)" to "Spanish [Latin America]",
    "français" to "French",
    "francais" to "French",
    "deutsch" to "German",
    "italiano" to "Italian",
    "português" to "Portuguese",
    "portugues" to "Portuguese",
    "português (brasil)" to "Portuguese [Brazil]",
    "português do brasil" to "Portuguese [Brazil]",
    "portuguese (brazil)" to "Portuguese [Brazil]",
    "русский" to "Russian",
    "polski" to "Polish",
    "nederlands" to "Dutch",
    "türkçe" to "Turkish",
    "turkce" to "Turkish",
    "svenska" to "Swedish",
    "norsk" to "Norwegian",
    "dansk" to "Danish",
    "suomi" to "Finnish",
    "magyar" to "Hungarian",
    "čeština" to "Czech",
    "cestina" to "Czech",
    "română" to "Romanian",
    "romana" to "Romanian",
    "ελληνικά" to "Greek",
    "ellinika" to "Greek",
    "日本語" to "Japanese",
    "nihongo" to "Japanese",
    "한국어" to "Korean",
    "hangugeo" to "Korean",
    "中文" to "Chinese",
    "chinese (simplified)" to "Chinese [Simplified]",
    "chinese (traditional)" to "Chinese [Traditional]",
    "العربية" to "Arabic",
    "عربي" to "Arabic",
    "हिन्दी" to "Hindi",
    "हिंदी" to "Hindi",
    "বাংলা" to "Bengali",
    "தமிழ்" to "Tamil",
    "తెలుగు" to "Telugu",
    "മലയാളം" to "Malayalam",
    "فارسی" to "Persian",
    "עברית" to "Hebrew",
    "ไทย" to "Thai",
    "ภาษาไทย" to "Thai",
    "tiếng việt" to "Vietnamese",
    "bahasa indonesia" to "Indonesian",
    "українська" to "Ukrainian"
)

private val bcp47LanguageMap: Map<String, String> = mapOf(
    "es-419" to "Spanish [Latin America]",
    "pt-br" to "Portuguese [Brazil]",
    "pt-pt" to "Portuguese [Portugal]",
    "zh-hans" to "Chinese [Simplified]",
    "zh-cn" to "Chinese [Simplified]",
    "zh-sg" to "Chinese [Simplified]",
    "zh-hant" to "Chinese [Traditional]",
    "zh-tw" to "Chinese [Traditional]",
    "zh-hk" to "Chinese [Traditional]",
    "en-us" to "English",
    "en-gb" to "English",
    "en-ca" to "English",
    "en-au" to "English",
    "es-es" to "Spanish",
    "es-mx" to "Spanish",
    "es-ar" to "Spanish"
)

fun getLanguage(code: String): String {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return "English"
    val lower = trimmed.lowercase(Locale.ROOT)
    val bcpMatch = bcp47LanguageMap[lower]
    if (bcpMatch != null) return bcpMatch
    val match = languageMap.entries.firstOrNull { it.key.equals(trimmed, ignoreCase = true) || lower in it.value }?.key
    if (match != null) return match
    val nativeMatch = nativeToStandardLanguage[lower]
    if (nativeMatch != null) return nativeMatch
    if (lower.contains('-') || lower.contains('_')) {
        val baseCode = lower.substringBefore('-').substringBefore('_')
        val baseMatch = languageMap.entries.firstOrNull { baseCode in it.value }?.key
        if (baseMatch != null) return baseMatch
    }
    return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

/**
 * State-of-the-art subtitle label and language normalizer:
 * 1. Strips UTF-8 BOM (\uFEFF) and zero-width spaces (\u200B, \u200C, \u200D, \u2060).
 * 2. Decodes named, decimal, and hex HTML character entities (&amp;, &ntilde;, &#39;, &#xE9;, etc.)
 *    and URL percent-encoded characters (%20, %5B, etc.).
 * 3. Normalizes Unicode characters to Canonical Decomposition/Composition (NFC).
 * 4. Resolves BCP-47 and ISO language codes to standardized names while preserving [Forced], [SDH], [CC], [HI] badges.
 */
fun cleanSubtitleLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "English"
    var text = raw.trim()

    // URL percent-decode if encoded
    if (text.contains("%")) {
        text = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
    }

    // Strip UTF-8 BOM and invisible zero-width characters
    text = text.replace("\uFEFF", "")
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\u2060", "")
        .replace("\u00A0", " ")
        .trim()

    // Strip non-informative generic badges like [Default] or (Full)
    text = text.replace(Regex("""\s*[\(\[](?:default|full)[\)\]]""", RegexOption.IGNORE_CASE), "").trim()

    // Decode HTML entities (named & numeric decimal/hex)
    if (text.contains("&")) {
        text = text
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&ntilde;", "ñ", ignoreCase = true)
            .replace("&Ntilde;", "Ñ", ignoreCase = true)
            .replace("&eacute;", "é", ignoreCase = true)
            .replace("&Eacute;", "É", ignoreCase = true)
            .replace("&aacute;", "á", ignoreCase = true)
            .replace("&Aacute;", "Á", ignoreCase = true)
            .replace("&iacute;", "í", ignoreCase = true)
            .replace("&Iacute;", "Í", ignoreCase = true)
            .replace("&oacute;", "ó", ignoreCase = true)
            .replace("&Oacute;", "Ó", ignoreCase = true)
            .replace("&uacute;", "ú", ignoreCase = true)
            .replace("&Uacute;", "Ú", ignoreCase = true)
            .replace("&ccedil;", "ç", ignoreCase = true)
            .replace("&Ccedil;", "Ç", ignoreCase = true)
            .replace("&uuml;", "ü", ignoreCase = true)
            .replace("&Uuml;", "Ü", ignoreCase = true)
            .replace("&ouml;", "ö", ignoreCase = true)
            .replace("&Ouml;", "Ö", ignoreCase = true)
            .replace("&auml;", "ä", ignoreCase = true)
            .replace("&Auml;", "Ä", ignoreCase = true)
            .replace("&egrave;", "è", ignoreCase = true)
            .replace("&Egrave;", "È", ignoreCase = true)
            .replace("&agrave;", "à", ignoreCase = true)
            .replace("&Agrave;", "À", ignoreCase = true)

        text = Regex("""&#(\d+);?""").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        text = Regex("""&#x([0-9a-fA-F]+);?""").replace(text) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
    }

    text = Normalizer.normalize(text, Normalizer.Form.NFC).trim()

    val modifierMatch = Regex("""(\[(?:forced|sdh|cc|hi|hearing\s+impaired)\]|\((?:forced|sdh|cc|hi|hearing\s+impaired)\))""", RegexOption.IGNORE_CASE).find(text)
    val modifier = modifierMatch?.groupValues?.get(1)?.replace("(", "[")?.replace(")", "]")?.let {
        when {
            it.equals("[forced]", ignoreCase = true) -> "[Forced]"
            it.equals("[sdh]", ignoreCase = true) -> "[SDH]"
            it.equals("[cc]", ignoreCase = true) -> "[CC]"
            it.equals("[hi]", ignoreCase = true) || it.contains("hearing", ignoreCase = true) -> "[HI]"
            else -> it
        }
    }
    val rawBase = if (modifier != null) text.replace(modifierMatch.value, "") else text
    val cleanBase = rawBase.trim().trimEnd('-', ':', '|', ' ', '[', ']').trimStart('[', ']', ' ')

    val resolvedLang = getLanguage(cleanBase)
    val finalLang = if (resolvedLang != "UnKnown") {
        resolvedLang
    } else {
        cleanBase.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

    return if (modifier != null) "$finalLang $modifier" else finalLang
}

/**
 * Whether a subtitle label or language tag denotes English.
 *
 * The app keeps several English subtitles per episode — one per source — so that at least one is
 * in sync with the release that was downloaded. That is only possible when the extension actually
 * emits enough distinct English subtitles, so the completion phase counts them with this predicate:
 * it accepts `English`, `English [SDH]`, `en-US`, `eng` and similar spellings.
 */
fun isEnglishSubtitleLabel(raw: String?): Boolean {
    if (raw.isNullOrBlank()) return false

    val cleaned = cleanSubtitleLabel(raw).lowercase(Locale.ROOT)
    if (cleaned.isEmpty()) return false
    if (cleaned.contains("english")) return true

    val code = cleaned.substringBefore(' ')
        .substringBefore('(')
        .substringBefore('[')
        .substringBefore('-')
        .substringBefore('_')
        .trim()
    return code == "en" || code == "eng"
}


fun getAnidbEid(jsonString: String, episodeNumber: Int?): Int? {
    if (episodeNumber == null) return null

    return try {
        val jsonObject = JSONObject(jsonString)
        val episodes = jsonObject.optJSONObject("episodes") ?: return null

        episodes.optJSONObject(episodeNumber.toString())
            ?.optInt("anidbEid", -1)
            ?.takeIf { it != -1 }
    } catch (e: Exception) {
        e.printStackTrace() // Logs the error but prevents breaking the app
        null
    }
}

fun parseAnimeData(jsonString: String): MetaAnimeData? {
    return try {
        sharedObjectMapper.readValue(jsonString, MetaAnimeData::class.java)
    } catch (_: Exception) {
        null // Return null for invalid JSON instead of crashing
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @param:JsonProperty("themoviedb_id") val themoviedbId: String? = null,
    @param:JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @param:JsonProperty("imdb_id") val imdbId: String? = null,
    @param:JsonProperty("mal_id") val malId: Int? = null,
    @param:JsonProperty("anilist_id") val anilistId: Int? = null,
    @param:JsonProperty("kitsu_id") val kitsuid: String? = null,
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class ImageData(
    @param:JsonProperty("coverType") val coverType: String?,
    @param:JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @param:JsonProperty("episode") val episode: String? = null,
    @param:JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @param:JsonProperty("episodeNumber") val episodeNumber: Int? = null,
    @param:JsonProperty("absoluteEpisodeNumber") val absoluteEpisodeNumber: Int? = null,
    @param:JsonProperty("anidbEid") val anidbEid: Int? = null,
    @param:JsonProperty("airdate") val airdate: String? = null,
    @param:JsonProperty("airDate") val airDate: String? = null,
    @param:JsonProperty("airDateUtc") val airDateUtc: String? = null,
    @param:JsonProperty("length") val length: Int? = null,
    @param:JsonProperty("runtime") val runtime: Int? = null,
    @param:JsonProperty("image") val image: String? = null,
    @param:JsonProperty("title") val title: Map<String, String>? = null,
    @param:JsonProperty("overview") val overview: String? = null,
    @param:JsonProperty("rating") val rating: String? = null,
    @param:JsonProperty("finaleType") val finaleType: String? = null
)


@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @param:JsonProperty("titles") val titles: Map<String, String>? = null,
    @param:JsonProperty("images") val images: List<ImageData>? = null,
    @param:JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null,
    @param:JsonProperty("episodeCount") val episodeCount: Int? = null,
    @param:JsonProperty("specialCount") val specialCount: Int? = null,
    @param:JsonProperty("mappings") val mappings: MetaMappings? = null
)

fun cleanTitle(title: String): String {

    val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")

    val patterns = listOf(
        Regex("(WEB[-_. ]?DL|WEB[-_. ]?RIP|BLURAY|HDRIP|BDRIP|BRRIP|DVDRIP|HDTV|CAM|TS)", RegexOption.IGNORE_CASE),
        Regex("(H[ .]?264|H[ .]?265|X264|X265|HEVC|AVC)", RegexOption.IGNORE_CASE),
        Regex("(DDP[ .]?[0-9]\\.[0-9]|DD[ .]?[0-9]\\.[0-9]|AAC[ .]?[0-9]\\.[0-9]|AC3|DTS|EAC3|FLAC|MP3)", RegexOption.IGNORE_CASE),
        Regex("(ATMOS|DUAL)", RegexOption.IGNORE_CASE),
        Regex("(HDR10\\+?|HDR|DV|DOLBY[ .]?VISION)", RegexOption.IGNORE_CASE),
        Regex("\\b(NF|AMZN|DSNP|HULU|CRAV|ATVP)\\b", RegexOption.IGNORE_CASE)
    )

    val results = linkedSetOf<String>()

    for (pattern in patterns) {
        pattern.findAll(name).forEach { match ->
            var value = match.value.uppercase()
            value = value
                .replace(Regex("WEB[-_. ]?DL"), "WEB-DL")
                .replace(Regex("WEB[-_. ]?RIP"), "WEBRIP")
                .replace(Regex("H[ .]?265"), "H265")
                .replace(Regex("H[ .]?264"), "H264")
                .replace(Regex("DOLBY[ .]?VISION"), "DOLBYVISION")
                .replace("2160P", "4K")
            results.add(value)
        }
    }
    return results.joinToString(" ")
}



//Anichi

fun String.fixUrlPath(): String {
    return if (this.contains(".json?")) "https://allanime.day$this"
    else "https://allanime.day" + URI(this).path + ".json?" + URI(this).query
}


fun decrypthex(inputStr: String): String {
    val hexString = if (inputStr.startsWith("-")) {
        inputStr.substringAfterLast("-")
    } else {
        inputStr
    }

    val bytes = ByteArray(hexString.length / 2) { i ->
        val hexByte = hexString.substring(i * 2, i * 2 + 2)
        (hexByte.toInt(16) and 0xFF).toByte()
    }

    return bytes.joinToString("") { (it.toInt() xor 56).toChar().toString() }
}

suspend fun getM3u8Qualities(
    m3u8Link: String,
    referer: String,
    qualityName: String,
): List<ExtractorLink> {
    return M3u8Helper.generateM3u8(
        qualityName,
        m3u8Link,
        referer
    )
}


private fun md5(input: ByteArray): String {
    return MessageDigest.getInstance("MD5").digest(input)
        .joinToString("") { "%02x".format(it) }
}


/**
 * Run multiple suspend functions concurrently with a limit on simultaneous executions.
 *
 * OPTIMIZED: Now uses StreamPlayConcurrency for better performance
 *
 * @param concurrency Limit of concurrent tasks.
 * @param tasks Vararg of suspend functions to execute.
 */
suspend fun runLimitedAsync(
    concurrency: Int = 5,
    taskTimeoutMs: Long = 25_000L,
    vararg tasks: suspend () -> Unit
) = StreamPlayConcurrency.runLimitedAsync(concurrency, taskTimeoutMs, *tasks)



private val JSON = "application/json; charset=utf-8".toMediaType()


fun String.fixSourceUrl(): String {
    return this.replace("/manifest.json", "").replace("stremio://", "https://")
}

fun generateHexKey32(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}



suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()?.substringBefore("-")

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    // Language match
    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    // Highest voted fallback
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    // No language match & no voted logos
    return null
}

fun String?.fixTitle(): String? {
    return this?.replace(Regex("[!%:']|( &)"), "")?.replace(" ", "-")?.lowercase()
        ?.replace("-–-", "-")
}
private val FILE_SIZE_REGEX = """(\d+(?:\.\d+)?\s?(?:MB|GB))""".toRegex(RegexOption.IGNORE_CASE)

private val BROWSER_FINGERPRINT by lazy {
    val raw = listOf(
        "1920x1080x24",
        "Asia/Kolkata",
        "en-US",
        "Win32",
        "8",
        "8",
        "canvas_stub_xdmovies",
        "ANGLE (NVIDIA)",
        "no_touch",
        "3",
        "true",
        "unset"
    ).joinToString("|||")
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)
}

fun generateBrowserFingerprint(): String {
    return BROWSER_FINGERPRINT
}


suspend fun bypassXD(url: String): String? {
    // Follow initial redirect to get actual bypass URL
    val redirect = app.get(url, allowRedirects = false)
        .headers["location"] ?: return null

    val baseUrl = getBaseUrl(redirect)
    val code = redirect.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: return null
    val fingerprint = generateBrowserFingerprint()

    val mouseData = mapOf(
        "eventCount"    to 220,
        "moveCount"     to 185,
        "clickCount"    to 3,
        "totalDistance" to 3800,
        "hasMovement"   to true,
        "duration"      to 27000
    )

    val baseHeaders = mapOf(
        "User-Agent"      to USER_AGENT,
        "Accept"          to "*/*",
        "Origin"          to baseUrl,
        "Referer"         to "$baseUrl/r/$code",
        "sec-fetch-site"  to "same-origin",
        "sec-fetch-mode"  to "cors",
        "sec-fetch-dest"  to "empty"
    )

    // ── STEP 1: Create session ────────────────────────────────────────────────
    val sessionJson = try {
        JSONObject(
            app.post(
                "$baseUrl/api/session",
                json = mapOf(
                    "code"        to code,
                    "fingerprint" to fingerprint,
                    "mouseData"   to mouseData
                ),
                headers = baseHeaders
            ).text
        )
    } catch (_: Exception) { return null }

    val sessionId  = sessionJson.optString("sessionId").takeIf { it.isNotEmpty() } ?: return null

    val cookieHeaders = baseHeaders + mapOf("Cookie" to "sid=$sessionId")

    // ── STEP 2: Rebind (simulates step-2 page reload) ────────────────────────
    val rebindJson = try {
        JSONObject(
            app.post(
                "$baseUrl/api/session/rebind",
                json = mapOf("fingerprint" to fingerprint),
                headers = cookieHeaders
            ).text
        )
    } catch (_: Exception) { return null }

    val rebindToken = rebindJson.optString("token").takeIf { it.isNotEmpty() } ?: return null

    // ── STEP 3: WebSocket heartbeats ─────────────────────────────────────────
    // Server only advances visible-time counter when it receives
    // "heartbeat" events over the Socket.IO WebSocket while
    // visibility is "visible". A plain delay() does nothing.
    val wsBaseUrl = baseUrl
        .replace("https://", "wss://")
        .replace("http://",  "ws://")

    val visibleTimeDone = CompletableDeferred<Unit>()

    val wsRequest = Request.Builder()
        .url("$wsBaseUrl/socket.io/?EIO=4&transport=websocket")
        .addHeader("Origin",     baseUrl)
        .addHeader("Cookie",     "sid=$sessionId")
        .addHeader("User-Agent", USER_AGENT)
        .build()

    val callerScope = CoroutineScope(coroutineContext)
    var heartbeatJob: kotlinx.coroutines.Job? = null

    val webSocket = app.baseClient.newWebSocket(wsRequest, object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Socket.IO: connect to default namespace
            webSocket.send("40")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when {
                // Socket.IO ping → reply with pong to keep connection alive
                text == "2" -> webSocket.send("3")

                // Namespace connected → bind session + mark visible + start heartbeats
                text.startsWith("40") -> {
                    webSocket.send("""42["bind","$rebindToken"]""")
                    webSocket.send("""42["visibility","visible"]""")

                    heartbeatJob = callerScope.launch {
                        var elapsed = 0
                        while (elapsed < 28) {
                            delay(1000)
                            elapsed++

                            webSocket.send("""42["heartbeat"]""")
                            webSocket.send(
                                """42["mouseActivity",${
                                    JSONObject(
                                        mouseData.toMutableMap().apply {
                                            put("duration", elapsed * 1000)
                                        }
                                    )
                                }]"""
                            )
                        }
                        visibleTimeDone.complete(Unit)
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            visibleTimeDone.completeExceptionally(t)
        }
    })

    // Wait for 28 heartbeats (≈ 28 seconds of visible time)
    try {
        withTimeout(40_000) { visibleTimeDone.await() }
    } catch (_: Exception) {
        return null
    } finally {
        heartbeatJob?.cancel()
        webSocket.close(1000, null)
    }

    // ── STEP 4: Complete session — retry until token returned ─────────────────
    var finalToken: String? = null

    repeat(5) { attempt ->
        if (finalToken != null) return@repeat
        try {
            val json = JSONObject(
                app.post(
                    "$baseUrl/api/session/complete",
                    json = mapOf(
                        "fingerprint" to fingerprint,
                        "mouseData"   to mouseData.toMutableMap().apply {
                            put("duration", 28000 + attempt * 2000)
                        },
                        "honeypot"    to ""   // must be empty — bots fill this
                    ),
                    headers = cookieHeaders
                ).text
            )
            json.optString("token").takeIf { it.isNotEmpty() }?.let { finalToken = it }
        } catch (_: Exception) { }

        if (finalToken == null) delay(2000)
    }

    val token = finalToken ?: return null

    // ── STEP 5: Final redirect ────────────────────────────────────────────────
    return app.get(
        "$baseUrl/go/$sessionId?t=$token",
        allowRedirects = false,
        headers = cookieHeaders
    ).headers["location"]
}

suspend fun safeGet(
    url: String,
    headers: Map<String, String>? = null,
    referer: String? = null,
    timeout: Long? = null,
    interceptor: Interceptor? = null,
    allowRedirects: Boolean = true, // kept for compatibility
    cacheTime: Int = 0              // default added
) = appGlobalSemaphore.withPermit {
    app.get(
        url = url,
        headers = headers ?: emptyMap(),
        referer = referer,
        timeout = timeout ?: 10L,
        interceptor = interceptor,
        allowRedirects = allowRedirects,
        cacheTime = cacheTime
    )
}

suspend inline fun <A, B> Iterable<A>.safeAmap(
    concurrency: Int = 7,
    crossinline f: suspend (A) -> B?
): Result<List<B>> = runCatching {
    coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))

        map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        f(item)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("safeMap", "Request failed for $item $e")
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
}.onFailure { if (it is CancellationException) throw it }

suspend fun <T> retry(
    times: Int = 3,
    delayMs: Long = 1000,
    block: suspend () -> T?
): T? {

    repeat(times - 1) {
        runCatching {
            block()
        }.getOrNull()?.let {
            return it
        }

        delay(delayMs)
    }

    return runCatching {
        block()
    }.getOrNull()
}

fun b64UrlDecode(data: String): ByteArray {

    val fixed = data
        .replace('-', '+')
        .replace('_', '/')
        .let {
            it + "=".repeat(
                (4 - it.length % 4) % 4
            )
        }

    return base64DecodeArray(fixed)
}
