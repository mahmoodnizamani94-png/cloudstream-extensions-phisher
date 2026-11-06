package com.phisher98

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.amapIndexed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.phisher98.BuildConfig.SUPERSTREAM_FOURTH_API
import com.phisher98.BuildConfig.SUPERSTREAM_THIRD_API
import com.phisher98.BuildConfig.NuvFeb
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private val ORG_QUALITY_REGEX = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
private val GSON = Gson()
private val WYZIESUB_LIST_TYPE = object : TypeToken<List<WyZIESUB>>() {}.type

private val VIDEO_HEADERS = mapOf(
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Accept-Language" to "en-US,en;q=0.8",
    "Connection" to "keep-alive",
    "Referer" to SUPERSTREAM_THIRD_API,
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
    "Sec-Fetch-Storage-Access" to "none",
    "Sec-GPC" to "1",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
    "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Brave\";v=\"139\", \"Chromium\";v=\"139\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\""
)
private val LANG_HEADERS = mapOf("Accept-Language" to "en")
private val SUBTITLE_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
)

private const val SUBDL_AUTO_API = "https://api3.subdl.com/auto"
private const val SUBDL_API_V1 = "https://api.subdl.com/api/v1/subtitles"
private const val SUBDL_WEB_HOST = "https://subdl.com"
private const val SUBDL_DL_HOST = "https://dl.subdl.com"
private const val SUBSOURCE_API_HOST = "https://api.subsource.net"
private const val SUBSOURCE_API_V1 = "$SUBSOURCE_API_HOST/api/v1"
private const val SUBF2M_HOST = "https://subf2m.co"

private const val SUBSENSE_URDU_BASE =
    "https://subsense.nepiraw.com/n2s6143949c45/subtitles"
private const val SUBSENSE_URDU_ALT_BASE =
    "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles"
private const val GOOGLE_TRANSLATE_CLIENTS5_URL =
    "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ur"
private const val GOOGLE_TRANSLATE_FALLBACK_URL =
    "https://translate.google.com/translate_a/t?client=at&sl=en&tl=ur"
private const val SUPERSTREAM_RLM = '\u200F'
private const val SUPERSTREAM_MAX_INFLATE_BYTES = 8 * 1024 * 1024
private const val SUPERSTREAM_TRANSLATION_BATCH_SIZE = 250
private const val SUPERSTREAM_TRANSLATION_SUB_BATCH_SIZE = 50
private const val SUPERSTREAM_PERSISTENT_URL_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L // 7 days

private val SUPERSTREAM_URDU_SCRIPT_REGEX =
    Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")
// Letters that exist in Urdu but NOT in standard Arabic or Persian (ٹ, ڈ, ڑ, ں, ے, ھ)
private val SUPERSTREAM_URDU_DISTINCTIVE_CHARS_REGEX =
    Regex("""[\u0679\u0688\u0691\u06BA\u06D2\u06BE]""")
private val SUPERSTREAM_URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX = Regex(
    """(?:^|[\s\u200F\u200E"،۔؟!…])(ہے|ہیں|تھا|تھی|تھے|نہیں|میں|آپ|کا|کے|کو|سے|اور|یہ|وہ|کیا|کیوں|کیسے|کہاں|جب|اب|پھر|ساتھ|لیے|پہلے|کچھ|سب|کوئی|اپنے|اپنی|اپنا|گیا|گئی|گئے|رہا|رہی|رہے|ہوا|ہوئی|کریں|کرو|جاؤ|آؤ|بھی|ہوں)(?=$|[\s\u200F\u200E"،۔؟!…])"""
)
private val SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX = Regex(
    """(?:^|[\s\u200F\u200E"،۔؟!…])(است|نیست|هست|هستم|هستی|هستند|می\u200C|نمی\u200C|های|هایی|برای|خیلی|چون|چرا|کجا|اینجا|آنجا|شما|آنها|ایشان|دیگه|همین|الان|باید|شاید|دارم|داری|دارد|داریم|دارند|کردم|کردی|کردیم|کردند|شود|شدند|بشه|میشه|نمیشه|نمیدونم|میدونم|میخوام|میخوای|میخواد|منم|توی|روی|چیه|چیزی|وقتی|اون|اونا|اینو|اونو|بابا|مامان|آقا|خانم|بله|آره|نه|چشم|مرسی|ممنون|بفرمایید|پدر|مادر|برادر|خواهر|پسرم|دخترم|کنم|کنی|کند|کنیم|کنند|گفتم|گفتی|گفتند|رفتم|رفتی|آمدم|آمدی|دیدم|دیدی|بدون|همه|هیچ|کدام|چطور|چقدر|هرگز|البته|حتما|واقعا|درست|بزرگ|کوچک|قشنگ|خوشگل|امروز|دیروز|فردا|شب|روز|ساعت|دقیقه|سال|ماه|هفته|زندگی|مرگ|عشق|دوست|دشمن|جنگ|صلح|خانه|اتاق|ماشین|خیابان|شهر|کشور|دنیا|جهان|خدا|مردم|بچه|پسر|دختر|زن|مرد|پیر|جوان|آب|نان|غذا|کار|پول|دست|پا|سر|چشم|دل|قلب|نام|اسم|حرف|سخن|صدا|نگاه|راه|در|دیوار|پنجره|آسمان|زمین|دریا|کوه|جنگل|درخت|گل|باران|برف|باد|آفتاب|مهتاب|ستاره|آتش|خاک|سنگ|آهن|طلا|نقره|رنگ|سفید|سیاه|سرخ|قرمز|آبی|سبز|زرد|هذا|هذه|ذلك|تلك|الذي|التي|الذين|ربما|لماذا|كيف|أين|نحن|أنتم|هم|ليس|ليست|كان|كانت|يكون|تكون|على|إلى|عن|مع|في|من|أن|إن|قد|لم|لن|لا|ما|هو|هي|هم|هن|أنت|أنا|عند|تحت|فوق|بين|قبل|بعد|حتى|منذ|خلال|حول|ضد|نحو|دون|سوف|كل|بعض|غير|أي|مثل|كذلك|أيضا|فقط|رب|إله|ناس|رجل|امرأة|ولد|بنت|طفل|بيت|منزل|سيارة|طريق|مدينة|بلد|عالم|حياة|موت|حب|حرب|سلام|ماء|طعام|عمل|مال|يد|رجل|رأس|عين|قلب|اسم|كلمة|صوت|يوم|ليلة|ساعة|سنة|شهر|أسبوع|أمس|غدا|الآن|هنا|هناك|جدا|كثيرا|قليلا|طبعا|فعلا|حقا|أبدا|دائما|أحيانا)(?=$|[\s\u200F\u200E"،۔؟!…])"""
)
// Same fa/ar marker set, but expressed in the glyphs `normalizeUrduTypography` produces
// (ه→ہ ك→ک ي/ى→ی) so the normalized-text scan stays in exact 1:1 correspondence with the raw scan.
private val SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_NORMALIZED_REGEX = Regex(
    SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX.pattern
        .replace("ه", "ہ")
        .replace("ك", "ک")
        .replace("ي", "ی")
        .replace("ى", "ی")
)
private val SUPERSTREAM_ARABIC_PRESENTATION_FORMS_REGEX =
    Regex("""[\uFB50-\uFDFF\uFE70-\uFEFF]+""")
private val SUPERSTREAM_NON_INLINE_ASS_TAG_REGEX =
    Regex("""\{\\(?!an[1-9]\})[^}]*\}""", RegexOption.IGNORE_CASE)
private val SUPERSTREAM_NON_INLINE_HTML_TAG_REGEX =
    Regex("""</?(?:font|span|div|p|br|v|c|lang|ruby|rt)(?:\b|[.\s])[^>]*>""", RegexOption.IGNORE_CASE)
private val SUPERSTREAM_INLINE_TAG_ONLY_REGEX =
    Regex("""(?i)</?(?:i|b|u|s)\b[^>]*>|\{\\an[1-9]\}""")
private val SUPERSTREAM_VTT_KARAOKE_TAG_REGEX =
    Regex("""<\d{1,2}:\d{2}(?::\d{2})?[,.]\d{1,3}>""")
private val SUPERSTREAM_EXPLICIT_EPISODE_TOKEN_REGEX = Regex(
    """(?i)(?:^|[^a-z0-9])(?:s\d{1,2}[\s._-]*e\d{1,4}|\d{1,2}x\d{1,4}|(?:e|ep|episode)[\s._-]*\d{1,4})(?!\d)"""
)

private const val SUPERSTREAM_FLEXIBLE_TIME_TOKEN = """(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?"""
private val SUPERSTREAM_SRT_TIMESTAMP_REGEX = Regex(
    """^\s*($SUPERSTREAM_FLEXIBLE_TIME_TOKEN)\s*-->\s*($SUPERSTREAM_FLEXIBLE_TIME_TOKEN)(.*)$"""
)
private val SUPERSTREAM_VTT_METADATA_BLOCK_REGEX = Regex(
    """^(?:NOTE|STYLE|REGION)(?:\s.*)?$""",
    RegexOption.IGNORE_CASE
)
private val SUPERSTREAM_PROTECTED_SUBTITLE_TOKEN_REGEX = Regex(
    """(?i)</?(?:i|b|u|s|font)\b[^>]*>|\{\\an[1-9]\}|[♪♫]+"""
)
private val SUPERSTREAM_RESTORE_TAG_PLACEHOLDER_REGEX = Regex(
    """(?:\u3016|\u3010|\[)\s*[\u200E\u200F]*(\d+)[\u200E\u200F]*\s*(?:\u3017|\u3011|\])"""
)
private val SUPERSTREAM_STRAY_TAG_PLACEHOLDER_REGEX = Regex(
    """(?:\u3016|\u3010)\s*[\u200E\u200F]*\d+[\u200E\u200F]*\s*(?:\u3017|\u3011)"""
)

private val SUPERSTREAM_WATERMARK_PATTERNS = listOf(
    "@iamdesigora",
    "iamdesigora",
    "black gold",
    "tryray.app",
    "ray - watch",
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
    "ترجمہ کار",
    "میرا پیج لایک کرو"
)

private val SUPERSTREAM_WATERMARK_REGEX_PATTERNS = listOf(
    Regex("""(?i)tryray\.app"""),
    Regex("""(?i)200\+\s*(?:زبانیں|languages)"""),
    Regex("""(?i)@iamdesigora"""),
    Regex("""میرا\s*پیج\s*لا[ئی]ک\s*کرو"""),
    Regex("""(?i)\b(?:subdl\.com|subsource\.net|subf2m\.co|opensubtitles\.org)\b""")
)

private val SUPERSTREAM_SEASON_ORDINALS = mapOf(
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

object SuperStreamExtractor : SuperStream() {

    private const val SUBDL_SLUG_NONE_SENTINEL = "__NONE__"
    private val synthesizedUrduCache = ConcurrentHashMap<String, String>()
    private val materializedUrduCache = ConcurrentHashMap<String, String>()
    private val materializationInFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()
    private val subDlSlugCache = ConcurrentHashMap<String, String>()
    private val translationBatchSemaphore = Semaphore(4)

    // Deferred SubtitleCat pre-cached `-ur.srt` candidates, recorded by `invokeSuperStreamSubtitleCatDonor`
    // and consumed ONLY as a verified last-resort Phase 4 fallback (never a Phase 1 human Urdu track),
    // scoped by (imdbId, season, episode) exactly like StreamPlay's `pendingSubtitleCatUrduFallbackByMedia`.
    private val pendingSubtitleCatUrduFallbackByMedia = ConcurrentHashMap<String, MutableSet<String>>()

    private fun superStreamMediaDonorKey(imdbId: String?, season: Int?, episode: Int?): String {
        val cleanImdb = imdbId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "global"
        return "$cleanImdb|S${season ?: 0}E${episode ?: 0}"
    }

    fun recordSuperStreamSubtitleCatPreCachedUrduCandidate(
        urduUrl: String,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ) {
        if (urduUrl.isBlank()) return
        val cleanUrl = urduUrl.trim()
        pendingSubtitleCatUrduFallbackByMedia
            .computeIfAbsent(superStreamMediaDonorKey(imdbId, season, episode)) { ConcurrentHashMap.newKeySet() }
            .add(cleanUrl)
        if (!imdbId.isNullOrBlank()) {
            pendingSubtitleCatUrduFallbackByMedia
                .computeIfAbsent(superStreamMediaDonorKey(null, season, episode)) { ConcurrentHashMap.newKeySet() }
                .add(cleanUrl)
        }
    }

    internal fun consumeSuperStreamSubtitleCatPreCachedUrduCandidates(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        val result = LinkedHashSet<String>()
        val mediaKey = superStreamMediaDonorKey(imdbId, season, episode)
        pendingSubtitleCatUrduFallbackByMedia.remove(mediaKey)?.let { result.addAll(it) }
        if (!imdbId.isNullOrBlank()) {
            pendingSubtitleCatUrduFallbackByMedia
                .remove(superStreamMediaDonorKey(null, season, episode))
                ?.let { result.addAll(it) }
        }
        return result.toList()
    }

    internal fun clearCachesForTest() {
        synthesizedUrduCache.clear()
        materializedUrduCache.clear()
        materializationInFlight.clear()
        subDlSlugCache.clear()
        pendingSubtitleCatUrduFallbackByMedia.clear()
    }

    suspend fun invokeSuperstream(
        token: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "$SUPERSTREAM_FOURTH_API/search?keyword=$imdbId"
        val href = app.get(searchUrl, timeout = 10L).document.selectFirst("h2.film-name a")?.attr("href")
            ?.let { SUPERSTREAM_FOURTH_API + it }
        val mediaId = href?.let {
            app.get(it, timeout = 10L).document.selectFirst("h2.heading-name a")?.attr("href")
                ?.substringAfterLast("/")?.toIntOrNull()
        }
        mediaId?.let {
            invokeExternalSource(it, if (season == null) 1 else 2, season, episode, callback, token)
        }
    }

    private suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        token: String? = null
    ) {
        val thirdAPI = SUPERSTREAM_THIRD_API
        val fourthAPI = SUPERSTREAM_FOURTH_API
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val shareKey =
            app.get("$fourthAPI/index/share_link?id=${mediaId}&type=$type", headers = LANG_HEADERS, timeout = 10L)
                .parsedSafe<ER>()?.data?.link?.substringAfterLast("/") ?: return

        val shareRes =
            app.get("$thirdAPI/file/file_share_list?share_key=$shareKey", headers = LANG_HEADERS, timeout = 10L)
                .parsedSafe<ExternalResponse>()?.data ?: return
        val fids = if (season == null) {
            shareRes.fileList
        } else {
            shareRes.fileList?.find {
                it.fileName.equals(
                    "season $season",
                    true
                )
            }?.fid?.let { parentId ->
                app.get(
                    "$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1",
                    headers = LANG_HEADERS,
                    timeout = 10L
                )
                    .parsedSafe<ExternalResponse>()?.data?.fileList?.filter {
                        it.fileName?.contains("s${seasonSlug}e${episodeSlug}", true) == true
                    }
            }
        } ?: return

        val semaphore = Semaphore(DeviceProfiler.getActiveConcurrency().coerceAtMost(8))
        coroutineScope {
            fids.mapIndexed { index, fileList ->
                async {
                    semaphore.withPermit {
                        try {
                            val superToken = token?.let {
                                if (it.startsWith("ui=")) it else "ui=$it"
                            } ?: ""
                            val player = app.get(
                                "$thirdAPI/console/video_quality_list?fid=${fileList.fid}&share_key=$shareKey",
                                headers = mapOf("Cookie" to superToken),
                                timeout = 10L
                            ).text
                            val json = try {
                                JSONObject(player)
                            } catch (e: Exception) {
                                Log.e("Error:", "Invalid JSON response $e")
                                return@withPermit
                            }
                            val htmlContent = json.optString("html", "")
                            if (htmlContent.isEmpty()) return@withPermit

                            val sourcesWithQualities = ZeroAllocParser.extractSuperStreamQualities(htmlContent)

                            sourcesWithQualities.forEach { item ->
                                val format = ExtractorLinkType.VIDEO
                                callback.invoke(
                                    newExtractorLink(
                                        "⌜ SuperStream ⌟",
                                        "⌜ SuperStream ⌟ [Server ${index + 1}] ${item.size}",
                                        item.url,
                                        format
                                    ) {
                                        this.quality = getIndexQuality(item.quality)
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e("SuperStream", "invokeExternalSource failed: $e")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun invokeSubtitleAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = id?.trim()?.takeIf { it.isNotBlank() } ?: return
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$cleanId.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$cleanId:$season:$episode.json"
        }
        val urduMode = isUrduSubtitleMode(sharedPref)
        val allSubs = try {
            app.get(url, headers = SUBTITLE_HEADERS, timeout = 10L)
                .parsedSafe<SubtitlesAPI>()?.subtitles.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val orderedSubs = if (urduMode) {
            val urduSubs = allSubs.filter { isUrduSubtitleCode(it.lang) || isUrduSubtitleCode(getLanguage(it.lang)) }.take(2)
            val englishSubs = allSubs.filter {
                !isUrduSubtitleCode(it.lang) && (isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)))
            }.take(3)
            urduSubs + englishSubs
        } else {
            allSubs.filter { isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)) }
        }
        orderedSubs.amap {
            val subUrl = it.url.trim()
            if (subUrl.startsWith("http", ignoreCase = true)) {
                val isUr = isUrduSubtitleCode(it.lang) || isUrduSubtitleCode(getLanguage(it.lang))
                if (isUr) {
                    val materializedUri = materializeNormalizedUrduSubtitle(
                        url = subUrl,
                        headers = SUBTITLE_HEADERS,
                        season = season,
                        episode = episode,
                        sourceTag = "opensubtitles-v3"
                    ) ?: return@amap
                    subtitleCallback.invoke(
                        newSubtitleFile(formatSuperStreamUrduSourceLabel("subtitles"), materializedUri) {
                            this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subtitles")
                        }
                    )
                } else {
                    val lan = if (isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang))) {
                        "English"
                    } else {
                        getLanguage(it.lang) ?: "English"
                    }
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lan.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString() },
                            subUrl
                        ) {
                            this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subtitles")
                        }
                    )
                }
            }
        }
    }

    private const val PREWARM_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
    private val prewarmCooldownMap = ConcurrentHashMap<String, Long>()
    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun readCrossModuleSharedPref(vararg keys: String): String? {
        for (k in keys) {
            val v = runCatching { sharedPref?.getString(k, null) }.getOrNull()?.trim()
            if (!v.isNullOrBlank()) return v
        }
        val ctx = superStreamAppContextRef ?: return null
        val prefNames = listOf("StreamPlay", "SuperStream", "cloudstream_subtitle_mode_prefs")
        for (name in prefNames) {
            for (k in keys) {
                val valFromPref = runCatching {
                    val sp = ctx.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                    sp.getString(k, null)?.trim()
                }.getOrNull()
                if (!valFromPref.isNullOrBlank()) return valFromPref
            }
        }
        return null
    }

    internal fun resolveWyzieApiKey(): String? {
        val sysKey = System.getProperty("cloudstream.wyzie.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("wyzie.api.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { System.getenv("WYZIE_API_KEY") }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (sysKey != null) return sysKey
        return readCrossModuleSharedPref("wyzie_key", "wyzie_api_key", "wyziekey")
    }

    internal fun resolveSubDlApiKey(): String? {
        val sysKey = System.getProperty("cloudstream.subdl.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("subdl.api.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { System.getenv("SUBDL_API_KEY") }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (sysKey != null) return sysKey
        return readCrossModuleSharedPref("subdl_api_key")
    }

    internal fun resolveSubSourceApiKey(): String? {
        val sysKey = System.getProperty("cloudstream.subsource.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("subsource.api.key")?.trim()?.takeIf { it.isNotBlank() }
            ?: runCatching { System.getenv("SUBSOURCE_API_KEY") }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (sysKey != null) return sysKey
        return readCrossModuleSharedPref("subsource_api_key")
    }

    fun prewarmSuperStreamUrduSubtitlesAsync(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        expectedYear: Int? = null
    ) {
        if (!isUrduSubtitleMode(sharedPref)) return
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null) return
        val prewarmKey = "${cleanImdb ?: ""}|${cleanTitle?.lowercase(Locale.ROOT) ?: ""}|S${season ?: 0}E${episode ?: 0}"
        val now = System.currentTimeMillis()
        val prev = prewarmCooldownMap[prewarmKey]
        if (prev != null && (now - prev) < PREWARM_COOLDOWN_MS) return
        prewarmCooldownMap[prewarmKey] = now

        prewarmScope.launch {
            try {
                withTimeoutOrNull(5_000L) {
                    coroutineScope {
                        launch {
                            invokeSubDlUrduKeyless(
                                imdbId = cleanImdb,
                                title = cleanTitle,
                                season = season,
                                episode = episode,
                                expectedYear = expectedYear,
                                subtitleCallback = {}
                            )
                        }
                        if (cleanImdb != null) {
                            launch {
                                invokeSubSenseUrdu(
                                    imdbId = cleanImdb,
                                    season = season,
                                    episode = episode,
                                    subtitleCallback = {}
                                )
                            }
                            launch {
                                invokeSuperStreamOpenSubtitlesUrdu(
                                    imdbId = cleanImdb,
                                    season = season,
                                    episode = episode,
                                    subtitleCallback = {}
                                )
                            }
                            if (season == null || season <= 0) {
                                launch {
                                    invokeSuperStreamYifyUrdu(
                                        imdbId = cleanImdb,
                                        subtitleCallback = {}
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun invokeWyZIESUBAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = id?.trim()?.takeIf { it.isNotBlank() } ?: return
        // Wyzie API (/search) strictly requires an API key (`&key=...`) and returns HTTP 401 without one.
        val wyzieKey = resolveWyzieApiKey() ?: return
        val keySuffix = "&source=all&key=${URLEncoder.encode(wyzieKey, "UTF-8")}"
        val urduMode = isUrduSubtitleMode(sharedPref)
        val wyzieBases = listOf("https://sub.wyzie.io", "https://sub.wyzie.ru")
        for (base in wyzieBases) {
            try {
                val baseQuery = if (season == null) {
                    "$base/search?id=$cleanId"
                } else {
                    "$base/search?id=$cleanId&season=$season&episode=$episode"
                }
                var emittedUrdu = 0
                if (urduMode) {
                    try {
                        val urText = app.get("$baseQuery&language=ur&sort=downloads$keySuffix", timeout = 8L).text
                        val urSubs: List<WyZIESUB> = GSON.fromJson(urText, WYZIESUB_LIST_TYPE) ?: emptyList()
                        for (it in urSubs) {
                            if (emittedUrdu >= 2) break
                            val suburl = it.url.trim()
                            if (suburl.startsWith("http", ignoreCase = true) &&
                                (isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language))
                            ) {
                                val materializedUri = materializeNormalizedUrduSubtitle(
                                    url = suburl,
                                    headers = SUBTITLE_HEADERS,
                                    season = season,
                                    episode = episode,
                                    sourceTag = "wyzie-ur"
                                ) ?: continue
                                subtitleCallback.invoke(
                                    newSubtitleFile(formatSuperStreamUrduSourceLabel("wyziesub"), materializedUri) {
                                        this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "wyziesub")
                                    }
                                )
                                emittedUrdu++
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    if (emittedUrdu >= 2) return
                }

                val res = app.get("$baseQuery$keySuffix", timeout = 10L).text
                val subtitles: List<WyZIESUB> = GSON.fromJson(res, WYZIESUB_LIST_TYPE) ?: emptyList()
                if (subtitles.isEmpty()) continue
                val ordered = if (urduMode) {
                    val urList = subtitles.filter {
                        isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)
                    }.take(2)
                    val enList = subtitles.filter {
                        !(isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)) &&
                            (isEnglishSubtitleCode(it.display) || isEnglishSubtitleCode(it.language))
                    }.take(3)
                    urList + enList
                } else {
                    subtitles.filter {
                        !(isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)) &&
                            (isEnglishSubtitleCode(it.display) || isEnglishSubtitleCode(it.language))
                    }
                }
                for (it in ordered) {
                    val suburl = it.url.trim()
                    if (suburl.startsWith("http", ignoreCase = true)) {
                        val isUr = isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)
                        if (isUr) {
                            if (emittedUrdu >= 2) continue
                            val materializedUri = materializeNormalizedUrduSubtitle(
                                url = suburl,
                                headers = SUBTITLE_HEADERS,
                                season = season,
                                episode = episode,
                                sourceTag = "wyzie"
                            ) ?: continue
                            subtitleCallback.invoke(
                                newSubtitleFile(formatSuperStreamUrduSourceLabel("wyziesub"), materializedUri) {
                                    this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "wyziesub")
                                }
                            )
                            emittedUrdu++
                        } else {
                            subtitleCallback.invoke(
                                newSubtitleFile("English", suburl) {
                                    this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "wyziesub")
                                }
                            )
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    suspend fun invokeSubSenseUrdu(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return
        if (!isUrduSubtitleMode(sharedPref)) return
        val path = if (season == null || season <= 0) {
            "movie/$cleanId.json"
        } else {
            "series/$cleanId:$season:${episode ?: 1}.json"
        }
        val urls = listOf(
            "$SUBSENSE_URDU_BASE/$path",
            "$SUBSENSE_URDU_ALT_BASE/$path"
        )
        for (url in urls) {
            try {
                val body = app.get(url, headers = SUBTITLE_HEADERS, timeout = 6L).text
                if (body.isBlank()) continue
                val root = JSONObject(body)
                val arr = root.optJSONArray("subtitles") ?: continue
                var emittedCount = 0
                for (i in 0 until arr.length()) {
                    if (emittedCount >= 2) break
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optBoolean("hi", false) ||
                        obj.optBoolean("hearingImpaired", false) ||
                        obj.optBoolean("ai_translated", false) ||
                        obj.optBoolean("aiTranslated", false)
                    ) {
                        continue
                    }
                    val labelOrTitle = "${obj.optString("label")} ${obj.optString("title")}"
                    if (labelOrTitle.contains("· HI", ignoreCase = true) ||
                        labelOrTitle.contains("[HI]", ignoreCase = true) ||
                        labelOrTitle.contains("SDH", ignoreCase = true) ||
                        labelOrTitle.contains("AI Translated", ignoreCase = true) ||
                        labelOrTitle.contains("Auto Translated", ignoreCase = true)
                    ) {
                        continue
                    }
                    val lang = obj.optString("lang").trim()
                    val subUrl = obj.optString("url").trim()
                    if (subUrl.startsWith("http", ignoreCase = true) &&
                        (lang.isEmpty() || isUrduSubtitleCode(lang))
                    ) {
                        val materializedUri = materializeNormalizedUrduSubtitle(
                            url = subUrl,
                            headers = SUBTITLE_HEADERS,
                            season = season,
                            episode = episode,
                            sourceTag = "subsense"
                        ) ?: continue
                        subtitleCallback.invoke(
                            newSubtitleFile(formatSuperStreamUrduSourceLabel("subsense"), materializedUri) {
                                this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subsense")
                            }
                        )
                        emittedCount++
                    }
                }
                if (emittedCount > 0) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * SubDL Urdu Harvester (`invokeSubDlUrduKeyless`):
     * 1. Queries Official SubDL API v1 (`https://api.subdl.com/api/v1/subtitles`) when `subdl_api_key` is configured.
     * 2. Seamlessly falls back to `api3.subdl.com/auto` (with fail-closed year check when `expectedYear != null`),
     *    fetches `subdl.com/subtitle/{sd_id}/{slug}/urdu`, extracts episode-matched `.zip` URLs ranked by
     *    Verified Spoken-Human Uploader Quality, and materializes them via [materializeNormalizedUrduSubtitle].
     */
    suspend fun invokeSubDlUrduKeyless(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        expectedYear: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!isUrduSubtitleMode(sharedPref)) return
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val query = cleanImdb ?: cleanTitle ?: return

        try {
            // 1. Official SubDL API v1 when subdl_api_key is configured
            val apiKey = resolveSubDlApiKey()
            if (!apiKey.isNullOrBlank()) {
                val type = if (season != null && season > 0) "tv" else "movie"
                val apiUrl = buildString {
                    append("$SUBDL_API_V1?api_key=${URLEncoder.encode(apiKey, "UTF-8")}")
                    append("&languages=UR&subs_per_page=10&unpack=1&type=$type")
                    if (cleanImdb != null) {
                        append("&imdb_id=${URLEncoder.encode(cleanImdb, "UTF-8")}")
                    } else if (cleanTitle != null) {
                        append("&film_name=${URLEncoder.encode(cleanTitle, "UTF-8")}")
                    }
                    if (season != null && season > 0) append("&season_number=$season")
                    if (episode != null && episode > 0) append("&episode_number=$episode")
                    if (expectedYear != null && expectedYear > 0) append("&year=$expectedYear")
                }
                val apiJson = runCatching {
                    app.get(apiUrl, headers = SUBTITLE_HEADERS, timeout = 5L).text
                }.getOrNull()
                if (!apiJson.isNullOrBlank()) {
                    val rankedUrls = parseSuperStreamSubDlApiV1Candidates(apiJson, season, episode)
                    var apiEmitted = 0
                    for (fullUrl in rankedUrls) {
                        if (apiEmitted >= 2) break
                        val dlHeaders = mapOf(
                            "User-Agent" to (SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                            "Referer" to "$SUBDL_WEB_HOST/"
                        )
                        val materializedUri = materializeNormalizedUrduSubtitle(
                            url = fullUrl,
                            headers = dlHeaders,
                            season = season,
                            episode = episode,
                            sourceTag = "subdl"
                        ) ?: continue
                        subtitleCallback.invoke(
                            newSubtitleFile(formatSuperStreamUrduSourceLabel("subdl"), materializedUri) {
                                this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subdl")
                            }
                        )
                        apiEmitted++
                    }
                    if (apiEmitted > 0) return
                }
            }

            // 2. Keyless fallback via api3.subdl.com/auto + HTML/__NEXT_DATA__
            val headers = mapOf(
                "User-Agent" to (SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                "Accept" to "application/json, text/plain, */*",
                "Origin" to SUBDL_WEB_HOST,
                "Referer" to "$SUBDL_WEB_HOST/"
            )
            val slugCacheKey = "${cleanImdb ?: ""}|${cleanTitle?.lowercase(Locale.ROOT) ?: ""}|${expectedYear ?: 0}"
            var slugLink: String? = subDlSlugCache[slugCacheKey]
            if (slugLink == SUBDL_SLUG_NONE_SENTINEL) return
            if (slugLink == null) {
                val autoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(query, "UTF-8")}"
                val autoBody = runCatching {
                    app.get(autoUrl, headers = headers, timeout = 5L).text
                }.getOrNull()
                slugLink = autoBody?.let { parseSubDlAutoSlug(it, expectedYear) }
                if (slugLink == null && cleanImdb != null && cleanTitle != null) {
                    val fallbackAutoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                    val fallbackBody = runCatching {
                        app.get(fallbackAutoUrl, headers = headers, timeout = 5L).text
                    }.getOrNull()
                    slugLink = fallbackBody?.let { parseSubDlAutoSlug(it, expectedYear) }
                }
                if (slugLink.isNullOrBlank()) {
                    subDlSlugCache[slugCacheKey] = SUBDL_SLUG_NONE_SENTINEL
                    return
                }
                subDlSlugCache[slugCacheKey] = slugLink
            }

            val pageUrl = if (season != null && season > 0) {
                val ordinal = subDlSeasonOrdinal(season) ?: return
                "$SUBDL_WEB_HOST$slugLink/$ordinal/urdu"
            } else {
                "$SUBDL_WEB_HOST$slugLink/urdu"
            }

            val html = runCatching {
                app.get(pageUrl, headers = headers, timeout = 6L).text
            }.getOrNull() ?: return

            val zipUrls = parseSubDlUrduPageZips(html, season, episode)
            var emittedCount = 0
            for (zipUrl in zipUrls) {
                if (emittedCount >= 2) break
                val dlHeaders = mapOf(
                    "User-Agent" to (SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                    "Referer" to "$SUBDL_WEB_HOST/"
                )
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = zipUrl,
                    headers = dlHeaders,
                    season = season,
                    episode = episode,
                    sourceTag = "subdl"
                ) ?: continue
                subtitleCallback.invoke(
                    newSubtitleFile(formatSuperStreamUrduSourceLabel("subdl"), materializedUri) {
                        this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subdl")
                    }
                )
                emittedCount++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun invokeSuperStreamSubSourceUrdu(
        imdbId: String? = null,
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        expectedYear: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!isUrduSubtitleMode(sharedPref)) return
        val apiKey = resolveSubSourceApiKey() ?: return
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        val query = cleanImdb ?: cleanTitle ?: return
        val headers = mapOf(
            "User-Agent" to (SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
            "Accept" to "application/json",
            "X-API-Key" to apiKey,
            "api-key" to apiKey
        )
        try {
            val searchType = if (cleanImdb != null) "imdb" else "text"
            val paramName = if (cleanImdb != null) "imdb" else "q"
            val searchUrl = buildString {
                append("$SUBSOURCE_API_V1/movies/search?searchType=$searchType&$paramName=${URLEncoder.encode(query, "UTF-8")}")
                if (expectedYear != null && expectedYear > 0 && season == null) append("&year=$expectedYear")
            }
            val searchJson = app.get(searchUrl, headers = headers, timeout = 5L).text
            val root = JSONObject(searchJson)
            val dataArr = root.optJSONArray("data") ?: root.optJSONArray("results") ?: return
            var movieId: String? = null
            for (i in 0 until dataArr.length()) {
                val item = dataArr.optJSONObject(i) ?: continue
                val id = item.optString("movieId").ifBlank { item.optString("id") }.trim()
                if (id.isBlank()) continue
                val itemSeason = item.optInt("season", 0)
                if (season != null && season > 0 && itemSeason > 0 && itemSeason != season) continue
                movieId = id
                break
            }
            if (movieId.isNullOrBlank()) return
            val subsUrl = buildString {
                append("$SUBSOURCE_API_V1/subtitles?movieId=${URLEncoder.encode(movieId, "UTF-8")}&language=urdu")
                if (season != null && season > 0) append("&season=$season")
                if (episode != null && episode > 0) append("&episode=$episode")
            }
            val subsJson = app.get(subsUrl, headers = headers, timeout = 5L).text
            val subsArr = JSONObject(subsJson).let { it.optJSONArray("data") ?: it.optJSONArray("subtitles") } ?: return
            var emitted = 0
            for (i in 0 until subsArr.length()) {
                if (emitted >= 2) break
                val item = subsArr.optJSONObject(i) ?: continue
                val subId = item.optString("subtitleId").ifBlank { item.optString("id") }.trim()
                val directDl = item.optString("downloadUrl").ifBlank { item.optString("url") }.trim()
                val dlUrl = when {
                    directDl.startsWith("http", ignoreCase = true) -> directDl
                    directDl.startsWith("/") -> "$SUBSOURCE_API_HOST$directDl"
                    subId.isNotBlank() -> "$SUBSOURCE_API_V1/subtitles/$subId/download"
                    else -> continue
                }
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = dlUrl,
                    headers = headers,
                    season = season,
                    episode = episode,
                    sourceTag = "subsource"
                ) ?: continue
                subtitleCallback.invoke(
                    newSubtitleFile(formatSuperStreamUrduSourceLabel("subsource"), materializedUri) {
                        this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subsource")
                    }
                )
                emitted++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun invokeSuperStreamSubf2mUrdu(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        expectedYear: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!isUrduSubtitleMode(sharedPref)) return
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return
        try {
            val searchUrl = "$SUBF2M_HOST/subtitles/searchbytitle?query=${URLEncoder.encode(cleanTitle, "UTF-8")}&l="
            val searchDoc = app.get(searchUrl, headers = SUBTITLE_HEADERS, timeout = 5L).document
            val slugHref = searchDoc.select("div.title a[href*=/subtitles/], li a[href*=/subtitles/]")
                .firstNotNullOfOrNull { a ->
                    val href = a.attr("href").trim().removeSuffix("/")
                    val text = a.text().trim()
                    if (!href.startsWith("/subtitles/")) return@firstNotNullOfOrNull null
                    if (expectedYear != null && season == null && !text.contains(expectedYear.toString())) return@firstNotNullOfOrNull null
                    href
                } ?: return
            val detailDoc = app.get("$SUBF2M_HOST$slugHref/urdu", headers = SUBTITLE_HEADERS, timeout = 5L).document
            val detailLinks = detailDoc.select("a.download[href*=/subtitles/], li.item a[href*=/urdu/]")
                .mapNotNull { a -> a.attr("href").trim().takeIf { it.startsWith("/subtitles/") } }
                .distinct()
                .take(3)
            var emitted = 0
            for (detailPath in detailLinks) {
                if (emitted >= 2) break
                val dlDoc = app.get("$SUBF2M_HOST$detailPath", headers = SUBTITLE_HEADERS, timeout = 5L).document
                val zipHref = dlDoc.selectFirst("a#downloadButton[href], a[href*=/ Hint/], a.download[href]")
                    ?.attr("href")?.trim() ?: continue
                val fullZipUrl = if (zipHref.startsWith("http", ignoreCase = true)) zipHref else "$SUBF2M_HOST$zipHref"
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = fullZipUrl,
                    headers = SUBTITLE_HEADERS + ("Referer" to "$SUBF2M_HOST$detailPath"),
                    season = season,
                    episode = episode,
                    sourceTag = "subf2m"
                ) ?: continue
                subtitleCallback.invoke(
                    newSubtitleFile(formatSuperStreamUrduSourceLabel("subf2m"), materializedUri) {
                        this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subf2m")
                    }
                )
                emitted++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Maps a YTS/YIFY row link to the correct zip download URL for its host: `yts-subs.com`
     * serves zips from `subtitles.yts-subs.com/subtitles/<slug>.zip` while `yifysubtitles.ch`
     * serves them from `yifysubtitles.ch/subtitle/<slug>.zip`. The two hosts do not share
     * download paths, so the source host decides the shape.
     */
    internal fun buildSuperStreamYtsZipUrl(sourceHost: String, href: String): String? {
        val cleanHref = href.trim()
        if (!cleanHref.startsWith("/subtitles/")) return null
        val slug = cleanHref.removePrefix("/subtitles/").removeSuffix("/").trim()
        if (slug.isBlank()) return null
        return if (sourceHost.trim().endsWith("yifysubtitles.ch")) {
            "https://yifysubtitles.ch/subtitle/$slug.zip"
        } else {
            "https://subtitles.yts-subs.com/subtitles/$slug.zip"
        }
    }

    suspend fun invokeSuperStreamYifyUrdu(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!isUrduSubtitleMode(sharedPref) || (season != null && season > 0)) return
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return
        val hosts = listOf("https://yts-subs.com", "https://yifysubtitles.ch")
        for (host in hosts) {
            try {
                val doc = app.get("$host/movie-imdb/$cleanImdb", headers = SUBTITLE_HEADERS, timeout = 5L).document
                val rows = doc.select("tr.high-rating, table.other-subs tbody tr, tbody tr")
                var emitted = 0
                for (row in rows) {
                    if (emitted >= 2) break
                    val langText = row.select("span.sub-lang, td.flag-cell").text().trim()
                    if (!isUrduSubtitleCode(langText)) continue
                    val href = row.selectFirst("a[href*=/subtitles/]")?.attr("href")?.trim() ?: continue
                    val zipUrl = buildSuperStreamYtsZipUrl(host, href) ?: continue
                    val materializedUri = materializeNormalizedUrduSubtitle(
                        url = zipUrl,
                        headers = SUBTITLE_HEADERS,
                        sourceTag = "yify"
                    ) ?: continue
                    subtitleCallback.invoke(
                        newSubtitleFile(formatSuperStreamUrduSourceLabel("yify"), materializedUri) {
                            this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "yify")
                        }
                    )
                    emitted++
                }
                if (emitted > 0) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private val SUPERSTREAM_OPENSUBTITLES_AI_AUTHOR_REGEX = Regex(
        """(?i)\b(ai[\s._-]*translated|auto[\s._-]*translated|machine[\s._-]*translated|subdl[\s._-]*ai|chatgpt|gpt[\s._-]*4|google[\s._-]*translate|deepl|black[\s._-]*gold|iamdesigora|tryray)\b"""
    )

    suspend fun invokeSuperStreamOpenSubtitlesUrdu(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        if (!isUrduSubtitleMode(sharedPref)) return
        val cleanImdb = imdbId?.trim()?.removePrefix("tt")?.takeIf { it.all(Char::isDigit) } ?: return
        val url = buildString {
            append("https://rest.opensubtitles.org/search")
            if (episode != null && episode > 0) append("/episode-$episode")
            append("/imdbid-$cleanImdb")
            if (season != null && season > 0) append("/season-$season")
            append("/sublanguageid-urd")
        }
        try {
            val headers = SUBTITLE_HEADERS + ("X-User-Agent" to "trailers.to-UA")
            val json = app.get(url, headers = headers, timeout = 6L).text
            if (!json.trim().startsWith("[")) return
            val arr = JSONArray(json)
            data class Candidate(
                val dlUrl: String,
                val fromTrusted: Int,
                val downloads: Long,
                val rating: Double
            )
            val candidates = mutableListOf<Candidate>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("SubAutoTranslation") == "1" || item.optInt("SubAutoTranslation", 0) == 1) continue
                if (item.optString("AiTranslated") == "1" || item.optInt("AiTranslated", 0) == 1) continue
                if (item.optString("MachineTranslated") == "1" || item.optInt("MachineTranslated", 0) == 1) continue
                if (item.optString("SubHearingImpaired") == "1" || item.optInt("SubHearingImpaired", 0) == 1) continue
                if (item.optString("SubBad") == "1" || item.optInt("SubBad", 0) == 1) continue
                val metaText = buildString {
                    append(item.optString("UserNickName")).append(' ')
                    append(item.optString("SubTranslator")).append(' ')
                    append(item.optString("SubAuthorComment")).append(' ')
                    append(item.optString("SubFileName")).append(' ')
                    append(item.optString("MovieReleaseName"))
                }
                if (SUPERSTREAM_OPENSUBTITLES_AI_AUTHOR_REGEX.containsMatchIn(metaText)) continue
                val dlUrl = item.optString("SubDownloadLink").trim()
                if (!dlUrl.startsWith("http", ignoreCase = true)) continue
                val trusted = if (item.optString("SubFromTrusted") == "1" || item.optInt("SubFromTrusted", 0) == 1) 1 else 0
                val downloads = item.optString("SubDownloadsCnt").toLongOrNull() ?: item.optLong("SubDownloadsCnt", 0L)
                val rating = item.optString("SubRating").toDoubleOrNull() ?: item.optDouble("SubRating", 0.0)
                candidates.add(Candidate(dlUrl, trusted, downloads, rating))
            }
            val sortedCandidates = candidates.sortedWith(
                compareByDescending<Candidate> { it.fromTrusted }
                    .thenByDescending { it.downloads }
                    .thenByDescending { it.rating }
            ).take(5)
            var emitted = 0
            for (cand in sortedCandidates) {
                if (emitted >= 2) break
                val materializedUri = materializeNormalizedUrduSubtitle(
                    url = cand.dlUrl,
                    headers = headers,
                    season = season,
                    episode = episode,
                    sourceTag = "opensubtitles"
                ) ?: continue
                subtitleCallback.invoke(
                    newSubtitleFile(formatSuperStreamUrduSourceLabel("opensubtitles"), materializedUri) {
                        this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "opensubtitles")
                    }
                )
                emitted++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * SubtitleCat donor harvester. Emits the verified English `-orig.srt` donor immediately
     * (feeding Phase 4 neural translation) and records SubtitleCat's pre-cached `-ur.srt` via
     * `recordSuperStreamSubtitleCatPreCachedUrduCandidate` as a VERIFIED LAST-RESORT Phase 4
     * fallback only — never as a Phase 1 human Urdu track (the `-ur.srt` files are literal
     * Google-Translate dumps until proven otherwise by `containsVerifiedUrduScript` +
     * `isBrokenSmtUrdu` at consumption time). When [expectedYear] is provided, search rows whose
     * text carries the year are preferred so multi-version titles resolve to the right cut.
     */
    suspend fun invokeSuperStreamSubtitleCatDonor(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        expectedYear: Int? = null,
        imdbId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: return
        try {
            val query = if (season != null && season > 0 && episode != null && episode > 0) {
                "$cleanTitle S${"%02d".format(season)}E${"%02d".format(episode)}"
            } else {
                cleanTitle
            }
            val searchUrl = "https://www.subtitlecat.com/index.php?search=${URLEncoder.encode(query, "UTF-8")}"
            val doc = app.get(searchUrl, headers = SUBTITLE_HEADERS, timeout = 5L).document
            val rows = doc.select("table.sub-table tbody tr a[href^=subs/], a[href^=/subs/]").toList()
            val episodeMatching = rows.filter { subtitleEpisodeMatches(it.text(), season, episode) }
            val candidates = if (episodeMatching.isNotEmpty()) {
                if (expectedYear != null && expectedYear > 0) {
                    val yearMatching = episodeMatching.filter { it.text().contains(expectedYear.toString()) }
                    if (yearMatching.isNotEmpty()) yearMatching else episodeMatching
                } else {
                    episodeMatching
                }
            } else {
                emptyList()
            }
            val detailPath = candidates.firstOrNull()
                ?.attr("href")?.trim()?.trimStart('/') ?: return
            val detailDoc = app.get("https://www.subtitlecat.com/$detailPath", headers = SUBTITLE_HEADERS, timeout = 5L).document
            val origHref = detailDoc.selectFirst("a[id=download_orig][href], a[href$=-orig.srt]")
                ?.attr("href")?.trim() ?: return
            val fullOrigUrl = if (origHref.startsWith("http", ignoreCase = true)) {
                origHref
            } else {
                "https://www.subtitlecat.com/${origHref.trimStart('/')}"
            }
            // Record the pre-cached `-ur.srt` (State A) strictly as a last-resort Phase 4 candidate.
            parseSuperStreamSubtitleCatUrduDetail(detailDoc.html())?.let { urUrl ->
                recordSuperStreamSubtitleCatPreCachedUrduCandidate(
                    urduUrl = urUrl,
                    imdbId = imdbId,
                    season = season,
                    episode = episode
                )
            }
            subtitleCallback.invoke(
                newSubtitleFile("English [SubtitleCat]", fullOrigUrl) {
                    this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "subtitlecat")
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * Extracts SubtitleCat's pre-cached Urdu `-ur.srt`/`-urd.srt` link from a detail page,
     * mirroring StreamPlay's `parseSubtitleCatUrduDetail` selectors.
     */
    internal fun parseSuperStreamSubtitleCatUrduDetail(body: String): String? {
        val document = org.jsoup.Jsoup.parse(body)
        val directUr = document.selectFirst("a#download_ur[href], a#download_urd[href]")
            ?.attr("href")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val href = directUr ?: document.select("a[href]")
            .asSequence()
            .map { it.attr("href").trim() }
            .firstOrNull { value ->
                val path = value.substringBefore('?').lowercase(Locale.ROOT)
                path.endsWith("-ur.srt") || path.endsWith("-urd.srt")
            }
            ?: return null
        val absolute = if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            href
        } else {
            "https://www.subtitlecat.com/${href.removePrefix("/")}"
        }
        return absolute.replace(" ", "%20")
    }

    /**
     * Parses SubDL Official API v1 JSON and returns ranked download URLs, best human-uploader tier
     * first: drops `ai_translated`/`machine_translated` entries, drops AI/bulk-SMT uploaders via
     * `isSubDlAiEntry`, keeps only releases matching the requested season/episode
     * (`subDlEpisodeMatches`), and ranks survivors by `scoreSubDlAuthorTier`. Mirrors the
     * filtering StreamPlay applies in `parseSubDlApiV1Zips` so both surfaces behave identically.
     */
    internal fun parseSuperStreamSubDlApiV1Candidates(
        jsonBody: String,
        season: Int?,
        episode: Int?
    ): List<String> {
        if (jsonBody.isBlank()) return emptyList()
        return try {
            val apiRoot = JSONObject(jsonBody)
            val subsArr = apiRoot.optJSONArray("subtitles") ?: apiRoot.optJSONArray("data") ?: return emptyList()
            val scored = LinkedHashMap<String, Int>()
            for (i in 0 until subsArr.length()) {
                val item = subsArr.optJSONObject(i) ?: continue
                if (item.optBoolean("ai_translated", false) || item.optBoolean("machine_translated", false)) continue
                val rawUrl = item.optString("url").ifBlank { item.optString("download_url") }.trim()
                if (rawUrl.isBlank()) continue
                val contextText = buildString {
                    for (key in listOf("author", "user", "uploader", "release_name", "name", "title")) {
                        val value = item.optString(key)
                        if (!value.isNullOrBlank()) {
                            append(value)
                            append(' ')
                        }
                    }
                }.trim()
                if (isSubDlAiEntry(contextText)) continue
                if (!subDlEpisodeMatches(contextText, season, episode)) continue
                val fullUrl = if (rawUrl.startsWith("http", ignoreCase = true)) {
                    rawUrl
                } else {
                    "$SUBDL_DL_HOST/${rawUrl.trimStart('/')}"
                }
                val tierScore = scoreSubDlAuthorTier(contextText)
                val existing = scored[fullUrl]
                if (existing == null || tierScore > existing) scored[fullUrl] = tierScore
            }
            scored.entries.sortedByDescending { it.value }.map { it.key }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun subDlSeasonOrdinal(season: Int): String? = SUPERSTREAM_SEASON_ORDINALS[season]

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

    private val SUBDL_AI_AUTHOR_REGEX = Regex(
        """(?i)\b(subdl[\s_]*ai|ai[\s_]*translated|auto[\s_]*translated|machine[\s_]*translated|chatgpt|google[\s_]*translate)\b"""
    )

    private val SUPERSTREAM_SUBDL_TIER_1A_AUTHORS = listOf(
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
        "malik rehan",
        "shabbir_umer",
        "shabbir umer",
        "urdu zone",
        "urduzone"
    )

    private val SUPERSTREAM_SUBDL_TIER_1C_MACHINE_AUTHORS = listOf(
        "blackgold",
        "black gold",
        "iamdesigora",
        "desigora",
        "akhaliq_655",
        "saad.jan",
        "tryray"
    )

    internal fun isSubDlAiEntry(contextText: String, rawRowHtml: String = ""): Boolean {
        val combined = "$contextText $rawRowHtml"
        if (combined.contains("data-ai-translated", ignoreCase = true) ||
            combined.contains("data-ai-row", ignoreCase = true) ||
            combined.contains("data-ai-subtitles", ignoreCase = true) ||
            combined.contains("ai_translated\":true", ignoreCase = true) ||
            combined.contains("ai_translated\": true", ignoreCase = true) ||
            combined.contains("subdl_ai", ignoreCase = true)
        ) {
            return true
        }
        return SUBDL_AI_AUTHOR_REGEX.containsMatchIn(combined)
    }

    internal fun scoreSubDlAuthorTier(text: String): Int {
        val lower = text.lowercase(Locale.ROOT)
        if (SUPERSTREAM_SUBDL_TIER_1C_MACHINE_AUTHORS.any { lower.contains(it) }) {
            return -40
        }
        if (SUPERSTREAM_SUBDL_TIER_1A_AUTHORS.any { lower.contains(it) }) {
            return 100
        }
        return 50
    }

    internal fun parseSubDlUrduPageZips(
        htmlBody: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (htmlBody.isBlank()) return emptyList()
        val zipRegex = Regex("""https?://dl\.subdl\.com/subtitle/\d+-\d+\.zip|/subtitle/\d+-\d+\.zip""", RegexOption.IGNORE_CASE)

        val doc = Jsoup.parse(htmlBody)
        val hasLanguageBlocks = doc.selectFirst("[data-language]") != null ||
            htmlBody.contains("data-language=", ignoreCase = true)
        val urduElements = if (hasLanguageBlocks) {
            val selected = doc.select("[data-language~=(?i)^urdu$]")
            if (selected.isEmpty()) return emptyList()
            selected
        } else {
            null
        }

        val scoredCandidates = LinkedHashMap<String, Int>()
        val anchors = urduElements?.select("a[href]") ?: doc.select("a[href]")

        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            val match = zipRegex.find(href) ?: continue
            val fullUrl = if (match.value.startsWith("http", ignoreCase = true)) {
                match.value
            } else {
                "$SUBDL_DL_HOST${match.value}"
            }
            var ancestor = anchor.parent()
            var hops = 0
            var isAiBlock = false
            val rowHtmlSb = StringBuilder()
            while (ancestor != null && hops < 5) {
                val attrs = ancestor.attributes().html()
                if (attrs.contains("data-ai-translated", ignoreCase = true) ||
                    attrs.contains("data-ai-row", ignoreCase = true) ||
                    attrs.contains("data-ai-subtitles", ignoreCase = true)
                ) {
                    isAiBlock = true
                    break
                }
                if (hops <= 2) {
                    rowHtmlSb.append(attrs).append(' ')
                }
                ancestor = ancestor.parent()
                hops++
            }
            if (isAiBlock) continue

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
            if (isSubDlAiEntry(contextText, rowHtmlSb.toString())) continue

            if (subDlEpisodeMatches(contextText, season, episode)) {
                val tierScore = scoreSubDlAuthorTier(contextText)
                val existing = scoredCandidates[fullUrl]
                if (existing == null || tierScore > existing) {
                    scoredCandidates[fullUrl] = tierScore
                }
            }
        }

        if (scoredCandidates.isEmpty() && !hasLanguageBlocks) {
            for (match in zipRegex.findAll(htmlBody)) {
                val raw = match.value
                val fullUrl = if (raw.startsWith("http", ignoreCase = true)) raw else "$SUBDL_DL_HOST$raw"
                val window = extractBoundedSubDlContextWindow(htmlBody, match.range)
                if (isSubDlAiEntry(window)) continue
                if (episode == null || episode <= 0 || subDlEpisodeMatches(window, season, episode)) {
                    val tierScore = scoreSubDlAuthorTier(window)
                    val existing = scoredCandidates[fullUrl]
                    if (existing == null || tierScore > existing) {
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

    internal fun normalizeSubtitleReleaseText(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), " ").trim()

    internal fun subtitleEpisodeMatches(text: String, season: Int?, episode: Int?): Boolean {
        if (episode == null || episode <= 0) return true
        if (season != null && season > 0) {
            val seasonRegex = Regex(
                """(?i)(?:^|[^a-z0-9])(?:s|season[\s._-]*)0*${season}[\s._-]*(?:e|ep|episode[\s._-]*)0*${episode}(?!\d)"""
            )
            val crossRegex = Regex("""(?i)(?:^|[^0-9])0*${season}x0*${episode}(?!\d)""")
            if (seasonRegex.containsMatchIn(text) || crossRegex.containsMatchIn(text)) return true
        }
        return Regex("""(?i)(?:^|[^0-9a-z])(?:e|ep|episode[\s._-]*)?0*${episode}(?![0-9])""")
            .containsMatchIn(text)
    }

    internal fun subDlEpisodeMatches(text: String, season: Int?, episode: Int?): Boolean {
        if (episode == null || episode <= 0) return true
        if (text.isBlank()) return true
        val normalized = text.lowercase(Locale.ROOT)
        if (subtitleEpisodeMatches(normalized, season, episode)) return true
        if (normalized.contains("complete") || normalized.contains("full season") || normalized.contains("season pack")) {
            return true
        }
        return false
    }

    suspend fun synthesizeSuperStreamUrduFallback(
        englishSubs: List<SubtitleFile>,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        for (candidate in englishSubs.take(3)) {
            val enUrl = candidate.url.trim()
            if (!enUrl.startsWith("http", ignoreCase = true)) continue
            val cacheKey = "synth|$enUrl|S${season ?: 0}E${episode ?: 0}"
            synthesizedUrduCache[cacheKey]?.let { cachedUri ->
                if (isCachedSubtitleUriValid(cachedUri)) {
                    subtitleCallback(
                        newSubtitleFile(formatSuperStreamUrduSourceLabel("urdu-neural-fallback"), cachedUri) {
                            this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "urdu-neural-fallback")
                        }
                    )
                    return@withContext true
                } else {
                    synthesizedUrduCache.remove(cacheKey)
                }
            }
            val diskUrlFile = resolvePersistentUrlCacheFile(cacheKey)
            if (diskUrlFile != null && diskUrlFile.exists() && diskUrlFile.isFile && diskUrlFile.length() > 64L) {
                val ageMs = System.currentTimeMillis() - diskUrlFile.lastModified()
                if (ageMs in 0..SUPERSTREAM_PERSISTENT_URL_CACHE_TTL_MS) {
                    val diskUri = "file:///" + diskUrlFile.absolutePath.trimStart('/')
                    synthesizedUrduCache[cacheKey] = diskUri
                    subtitleCallback(
                        newSubtitleFile(formatSuperStreamUrduSourceLabel("urdu-neural-fallback"), diskUri) {
                            this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "urdu-neural-fallback")
                        }
                    )
                    return@withContext true
                }
            }
            val rawBytes = try {
                app.get(enUrl, headers = SUBTITLE_HEADERS, timeout = 6L).okhttpResponse.body.byteStream().use { stream ->
                    readBoundedStreamOrOverflow(stream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val decodedEnglish = decodeAndNormalizeSubtitleBytes(rawBytes, season = season, episode = episode)
            if (decodedEnglish.length < 32) continue
            val urduSrt = translateEnglishSrtToUrdu(decodedEnglish) ?: continue
            val synthesizedCues = parseSuperStreamCues(urduSrt)
            if (synthesizedCues.isEmpty() || !containsVerifiedUrduScript(synthesizedCues) || isBrokenSmtUrdu(synthesizedCues)) {
                continue
            }
            val fileUri = writeLocalUrduSrtFile(urduSrt, cacheKey) ?: continue
            synthesizedUrduCache[cacheKey] = fileUri
            subtitleCallback(
                newSubtitleFile(formatSuperStreamUrduSourceLabel("urdu-neural-fallback"), fileUri) {
                    this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "urdu-neural-fallback")
                }
            )
            return@withContext true
        }
        // Last-resort Phase 4 fallback: if SubtitleCat had a pre-cached `-ur.srt` for this title,
        // refine and emit it — but only after full Urdu-script + broken-SMT verification (a raw
        // Google-Translate dump never reaches the player). Runs even without English donors so
        // the Urdu floor holds offline of donor availability, mirroring StreamPlay's Phase 4.
        val preCachedUrUrls = consumeSuperStreamSubtitleCatPreCachedUrduCandidates(
            imdbId = imdbId,
            season = season,
            episode = episode
        )
        for (urUrl in preCachedUrUrls.take(2)) {
            val rawBytes = try {
                app.get(urUrl, headers = SUBTITLE_HEADERS, timeout = 5L).okhttpResponse.body.byteStream().use { stream ->
                    readBoundedStreamOrOverflow(stream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val decodedUr = decodeAndNormalizeSubtitleBytes(rawBytes, season = season, episode = episode)
            if (decodedUr.isBlank()) continue
            val normalizedUr = normalizeUrduSubtitleText(decodedUr)
            val cues = parseSuperStreamCues(normalizedUr)
            if (!containsVerifiedUrduScript(cues) || isBrokenSmtUrdu(cues)) continue
            val cacheKey = "subcat_ur_fallback|$urUrl|S${season ?: 0}E${episode ?: 0}"
            val fileUri = writeLocalUrduSrtFile(normalizedUr, cacheKey) ?: continue
            synthesizedUrduCache[cacheKey] = fileUri
            subtitleCallback(
                newSubtitleFile(formatSuperStreamUrduSourceLabel("urdu-neural-fallback"), fileUri) {
                    this.headers = mapOf(SUPERSTREAM_SOURCE_HEADER to "urdu-neural-fallback")
                }
            )
            return@withContext true
        }
        false
    }

    internal fun containsVerifiedUrduScript(content: String, minUrduCues: Int = 2): Boolean {
        val cues = parseSuperStreamCues(content)
        if (cues.isNotEmpty()) {
            return containsVerifiedUrduScript(cues, minUrduCues)
        }
        val normalized = normalizeUrduTypography(content)
        if (!SUPERSTREAM_URDU_SCRIPT_REGEX.containsMatchIn(normalized)) return false
        val urduDistinctiveCount = SUPERSTREAM_URDU_DISTINCTIVE_CHARS_REGEX.findAll(normalized).count()
        val urduUnambiguousFunctionCount = SUPERSTREAM_URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(normalized).count()
        val persianArabicExclusiveCount = SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_NORMALIZED_REGEX.findAll(normalized).count()
        val rawPersianArabicExclusiveCount = SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(content).count()
        if (urduDistinctiveCount == 0 && urduUnambiguousFunctionCount == 0) return false
        if (rawPersianArabicExclusiveCount >= 1 && urduDistinctiveCount == 0) return false
        if (persianArabicExclusiveCount >= 1 &&
            (urduDistinctiveCount == 0 || persianArabicExclusiveCount > (urduDistinctiveCount + urduUnambiguousFunctionCount))
        ) {
            return false
        }
        return true
    }

    internal fun containsVerifiedUrduScript(cues: List<SuperStreamSrtCue>, minUrduCues: Int = 2): Boolean {
        if (cues.isEmpty()) return false
        val urduCueCount = cues.count { SUPERSTREAM_URDU_SCRIPT_REGEX.containsMatchIn(it.text) }
        val required = if (cues.size <= 4) 1 else minOf(minUrduCues, cues.size)
        if (urduCueCount < required || (cues.size > 4 && urduCueCount * 100 < cues.size * 15)) {
            return false
        }

        val rawText = cues.joinToString("\n") { it.text }
        val combinedText = normalizeUrduTypography(rawText)
        val urduDistinctiveCount = SUPERSTREAM_URDU_DISTINCTIVE_CHARS_REGEX.findAll(combinedText).count()
        val urduUnambiguousFunctionCount = SUPERSTREAM_URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(combinedText).count()
        val persianArabicMatches = SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_NORMALIZED_REGEX.findAll(combinedText).count()
        val rawPersianArabicMatches = SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(rawText).count()

        // Deterministically reject Persian (fa) and Arabic (ar) across all cue counts.
        // Genuine Urdu almost always carries distinctive glyphs (ٹ ڈ ڑ ں ے ھ); fa/ar never do, so
        // evidence built purely from shared homographs (ہو/ہم/تو/اگر/... after normalization) or
        // from raw fa/ar orthography (هو/هم/هست/...) with zero distinctive glyphs is not Urdu.
        if (urduDistinctiveCount == 0 && urduUnambiguousFunctionCount == 0) {
            return false
        }
        if (rawPersianArabicMatches >= 1 && urduDistinctiveCount == 0) {
            return false
        }
        if (persianArabicMatches >= 1 &&
            (urduDistinctiveCount == 0 || persianArabicMatches > (urduDistinctiveCount + urduUnambiguousFunctionCount))
        ) {
            return false
        }
        return true
    }

    private fun resolvePersistentUrlCacheFile(urlCacheKey: String): File? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(urlCacheKey.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(24)
            val baseDir = superStreamAppContextRef?.cacheDir
                ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!baseDir.exists()) baseDir.mkdirs()
            File(baseDir, "cs_urdu_url_$digest.srt")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads, unpacks (GZIP/episode-aware ZIP), decodes (`Windows-1256`, `UTF-16LE/BE`, `UTF-8`),
     * normalizes Urdu typography + RTL BiDi, validates cues and genuine Urdu script, and materializes a human Urdu subtitle URL
     * into a UTF-8-BOM local `.srt` file (`file:///...`). Fails closed (`null`) if download, cue validation, or Urdu script check fails.
     */
    internal suspend fun materializeNormalizedUrduSubtitle(
        url: String,
        headers: Map<String, String> = SUBTITLE_HEADERS,
        season: Int? = null,
        episode: Int? = null,
        sourceTag: String = "urdu-human"
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return@withContext null
        if (cleanUrl.startsWith("file://", ignoreCase = true)) {
            return@withContext if (isCachedSubtitleUriValid(cleanUrl)) cleanUrl else null
        }

        val cacheKey = "$cleanUrl|S${season ?: 0}E${episode ?: 0}"
        materializedUrduCache[cacheKey]?.let { cachedUri ->
            if (isCachedSubtitleUriValid(cachedUri)) {
                return@withContext cachedUri
            } else {
                materializedUrduCache.remove(cacheKey)
            }
        }

        // Disk-first URL lookup (<1ms across app restarts, 7-day TTL)
        val diskUrlFile = resolvePersistentUrlCacheFile(cacheKey)
        if (diskUrlFile != null && diskUrlFile.exists() && diskUrlFile.isFile && diskUrlFile.length() > 64L) {
            val ageMs = System.currentTimeMillis() - diskUrlFile.lastModified()
            if (ageMs in 0..SUPERSTREAM_PERSISTENT_URL_CACHE_TTL_MS) {
                val diskUri = "file:///" + diskUrlFile.absolutePath.trimStart('/')
                materializedUrduCache[cacheKey] = diskUri
                return@withContext diskUri
            }
        }

        // SingleFlight coalescing so concurrent duplicate requests share one network/ZIP decode
        val deferred = CompletableDeferred<String?>()
        val existingDeferred = materializationInFlight.putIfAbsent(cacheKey, deferred)
        if (existingDeferred != null) {
            return@withContext existingDeferred.await()
        }

        try {
            val rawBytes = try {
                app.get(cleanUrl, headers = headers, timeout = 8L).okhttpResponse.body.byteStream().use { stream ->
                    readBoundedStreamOrOverflow(stream)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (rawBytes == null || rawBytes.isEmpty()) {
                deferred.complete(null)
                return@withContext null
            }

            val decoded = decodeAndNormalizeSubtitleBytes(rawBytes, season = season, episode = episode)
            if (decoded.isBlank()) {
                deferred.complete(null)
                return@withContext null
            }
            val normalized = normalizeUrduSubtitleText(decoded)
            if (!containsVerifiedUrduScript(normalized)) {
                deferred.complete(null)
                return@withContext null
            }
            val parsedCues = parseSuperStreamCues(normalized)
            if (isBrokenSmtUrdu(parsedCues)) {
                deferred.complete(null)
                return@withContext null
            }
            val localUri = writeLocalUrduSrtFile(normalized, cacheKey)
            if (localUri != null) {
                materializedUrduCache[cacheKey] = localUri
            }
            deferred.complete(localUri)
            localUri
        } catch (t: Throwable) {
            deferred.complete(null)
            if (t is CancellationException) throw t
            null
        } finally {
            materializationInFlight.remove(cacheKey, deferred)
        }
    }

    internal fun inflateArchiveIfNeeded(
        rawBytes: ByteArray,
        season: Int? = null,
        episode: Int? = null
    ): ByteArray {
        if (rawBytes.size < 2) return rawBytes
        val b0 = rawBytes[0].toInt() and 0xFF
        val b1 = rawBytes[1].toInt() and 0xFF

        if (b0 == 0x1F && b1 == 0x8B) {
            return runCatching {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gis ->
                    readBoundedStream(gis)
                }
            }.getOrDefault(ByteArray(0))
        }

        if (rawBytes.size >= 4) {
            val b2 = rawBytes[2].toInt() and 0xFF
            val b3 = rawBytes[3].toInt() and 0xFF
            if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
                return runCatching {
                    extractBestSubtitleFromZip(rawBytes, season, episode) ?: ByteArray(0)
                }.getOrDefault(ByteArray(0))
            }
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

                val extScore = when {
                    baseName.endsWith(".srt") -> 100
                    baseName.endsWith(".vtt") -> 90
                    baseName.endsWith(".ass") || baseName.endsWith(".ssa") -> 80
                    baseName.endsWith(".sub") -> 60
                    else -> {
                        zis.closeEntry()
                        continue
                    }
                }

                var episodeBonus = 0
                if (episode != null && episode > 0) {
                    val normBase = normalizeSubtitleReleaseText(baseName)
                    val normFull = normalizeSubtitleReleaseText(rawEntryName)
                    val baseHasEpToken = SUPERSTREAM_EXPLICIT_EPISODE_TOKEN_REGEX.containsMatchIn(baseName)
                    val fullHasEpToken = SUPERSTREAM_EXPLICIT_EPISODE_TOKEN_REGEX.containsMatchIn(rawEntryName)
                    val baseMatches = subtitleEpisodeMatches(normBase, season, episode)
                    val fullMatches = !baseHasEpToken && subtitleEpisodeMatches(normFull, season, episode)
                    val matchesRequestedEpisode = baseMatches || fullMatches

                    if (!matchesRequestedEpisode && (baseHasEpToken || fullHasEpToken)) {
                        zis.closeEntry()
                        continue
                    }
                    if (matchesRequestedEpisode) {
                        episodeBonus = 500
                    }
                }

                val langBonus = if (baseName.contains("ur") || baseName.contains("urdu")) 25 else 0
                val releaseTierBonus = when {
                    baseName.contains("nf.web") || baseName.contains("netflix") ||
                        baseName.contains("amzn") || baseName.contains("web-dl") ||
                        baseName.contains("bluray") -> 30
                    else -> 0
                }
                val entryBytes = readBoundedStream(zis)
                zis.closeEntry()

                if (entryBytes.isNotEmpty()) {
                    val unpacked = if (entryBytes.size >= 2 &&
                        (entryBytes[0].toInt() and 0xFF) == 0x1F &&
                        (entryBytes[1].toInt() and 0xFF) == 0x8B
                    ) {
                        runCatching {
                            GZIPInputStream(ByteArrayInputStream(entryBytes)).use {
                                readBoundedStream(it)
                            }
                        }.getOrDefault(entryBytes)
                    } else {
                        entryBytes
                    }

                    val sampleDecoded = runCatching {
                        normalizeUrduTypography(decodeSubtitleCharset(unpacked))
                    }.getOrDefault("")
                    val sampleCues = if (sampleDecoded.isNotEmpty()) parseSuperStreamCues(sampleDecoded) else emptyList()
                    val naturalnessBonus = if (sampleCues.isNotEmpty()) {
                        val natScore = scoreUrduSubtitleNaturalness(sampleCues)
                        if (isBrokenSmtUrdu(sampleCues)) -400 else natScore
                    } else {
                        0
                    }

                    val score = episodeBonus + extScore + langBonus + releaseTierBonus +
                        naturalnessBonus + (unpacked.size.coerceAtMost(500_000) / 10_000)
                    if (score > bestScore) {
                        bestScore = score
                        bestBytes = unpacked
                    }
                }
            }
        }
        return bestBytes
    }

    internal fun decodeAndNormalizeSubtitleBytes(
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

    internal fun inflateAndDecodeSubtitleBytes(
        raw: ByteArray,
        season: Int? = null,
        episode: Int? = null
    ): String = decodeAndNormalizeSubtitleBytes(raw, season, episode)

    internal fun decodeSubtitleCharset(bytes: ByteArray): String {
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

        val utf8Decoded = tryStrictUtf8(bytes)
        if (utf8Decoded != null) {
            return utf8Decoded
        }

        if (looksLikeWindows1256(bytes)) {
            val cp1256 = runCatching { Charset.forName("windows-1256") }.getOrNull()
                ?: runCatching { Charset.forName("Cp1256") }.getOrNull()
            if (cp1256 != null) {
                return String(bytes, cp1256)
            }
        }

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
        if (asciiLatinCount > highByteCount * 4) return false
        return urduSpecificCount > 0 || (arabicRangeCount * 2 >= highByteCount)
    }

    internal fun normalizeUrduTypography(input: String): String {
        if (input.isEmpty()) return ""
        val preCleaned = input
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u06D3', '\u06D2')
        val decomposed = if (SUPERSTREAM_ARABIC_PRESENTATION_FORMS_REGEX.containsMatchIn(preCleaned)) {
            SUPERSTREAM_ARABIC_PRESENTATION_FORMS_REGEX.replace(preCleaned) { match ->
                Normalizer.normalize(match.value, Normalizer.Form.NFKC)
            }
        } else {
            preCleaned
        }
        val cleaned = decomposed
            .replace('\u064A', '\u06CC')
            .replace('\u0649', '\u06CC')
            .replace('\u0643', '\u06A9')
            .replace('\u0647', '\u06C1')
            .replace('\u06D5', '\u06C1')
            .replace('\u06D3', '\u06D2')
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

    internal fun isWatermarkCue(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        if (SUPERSTREAM_WATERMARK_PATTERNS.any { pattern -> lower.contains(pattern.lowercase(Locale.ROOT)) }) {
            return true
        }
        return SUPERSTREAM_WATERMARK_REGEX_PATTERNS.any { regex -> regex.containsMatchIn(text) }
    }

    private val URDU_SPOKEN_IDIOM_REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("""اوہ\s*[،,]?\s*میرے\s*گوش[۔.!؟]*""") to "یا اللہ!",
        Regex("""میرے\s*گوش""") to "یا اللہ",
        Regex("""مقدس\s*(?:گندگی|بھڑوا|شٹ|تپش)""") to "یا الٰہی",
        Regex("""یہ\s*ایک\s*لپیٹ\s*ہے""") to "بس کام ختم ہوا",
        Regex("""ایک\s*ٹانگ\s*توڑ(?:\s*دو)?""") to "کامیاب رہو",
        Regex("""گولی\s*مارو\s*اسے""") to "چھوڑو اسے",
        Regex("""بھاڑ\s*میں\s*جاؤ\s*تم""") to "دفع ہو جاؤ",
        Regex("""تم\s*مجھ\s*سے\s*مذاق\s*کر\s*رہے\s*ہو\s*نا""") to "مذاق تو نہیں کر رہے نا",
        Regex("""آپ\s*مجھ\s*سے\s*مذاق\s*کر\s*رہے\s*ہیں""") to "مذاق تو نہیں کر رہے",
        Regex("""کوئی\s*راستہ\s*نہیں(?=[۔.!؟\s]|$)""") to "ہرگز نہیں",
        Regex("""میرے\s*بستر\s*کے\s*نیچے\s*راکشس""") to "میرے بستر کے نیچے بلا",
        Regex("""راکشسوں""") to "بلاؤں",
        Regex("""راکشس""") to "بلا",
        Regex("""(?<=^|[\s\u200F"،۔؟!])بھگوان(?=$|[\s\u200F"،۔؟!])""") to "خدا",
        Regex("""(?<=^|[\s\u200F"،۔؟!])پرتھوی(?=$|[\s\u200F"،۔؟!])""") to "زمین",
        Regex("""(?<=^|[\s\u200F"،۔؟!])شانتی(?=$|[\s\u200F"،۔؟!])""") to "سکون",
        Regex("""(?<=^|[\s\u200F"،۔؟!])وشواس(?=$|[\s\u200F"،۔؟!])""") to "یقین",
        Regex("""(?<=^|[\s\u200F"،۔؟!])آشیرواد(?=$|[\s\u200F"،۔؟!])""") to "دعا",
        Regex("""(?<=^|[\s\u200F"،۔؟!])نمسکار(?=$|[\s\u200F"،۔؟!])""") to "آداب",
        Regex("""(?<=^|[\s\u200F"،۔؟!])نمستے(?=$|[\s\u200F"،۔؟!])""") to "سلام",
        Regex("""(?<=^|[\s\u200F"،۔؟!])دھنیواد(?=$|[\s\u200F"،۔؟!])""") to "شکریہ",
        Regex("""(?<=^|[\s\u200F"،۔؟!])کرپیا(?=$|[\s\u200F"،۔؟!])""") to "براہِ کرم",
        Regex("""(?<=^|[\s\u200F"،۔؟!])سواگت(?=$|[\s\u200F"،۔؟!])""") to "خوش آمدید",
        Regex("""(?<=^|[\s\u200F"،۔؟!])اپرادھ(?=$|[\s\u200F"،۔؟!])""") to "جرم",
        Regex("""(?<=^|[\s\u200F"،۔؟!])سندیش(?=$|[\s\u200F"،۔؟!])""") to "پیغام",
        Regex("""(?<=^|[\s\u200F"،۔؟!])سمرپت(?=$|[\s\u200F"،۔؟!])""") to "وقف",
        Regex("""^[—–-]\s*(?:مسیح|یسوع)\s*[!؟۔.]*$""") to "یا خدا!",
        Regex("""^(?:مسیح|یسوع)\s*[!؟۔.]+$""") to "یا خدا!",
        Regex("""^[—–-]\s*گندگی\s*[!؟۔.]*$""") to "— لعنت ہے!",
        Regex("""^گندگی\s*[!؟۔.]+$""") to "لعنت ہے!",
        Regex("""^[—–-]\s*بھڑوا\s*[!؟۔.]*$""") to "— کمینہ!",
        Regex("""^بھڑوا\s*[!؟۔.]+$""") to "کمینہ!"
    )

    private val URDU_LITERAL_MT_ARTIFACT_REGEX = Regex(
        """(میرے\s*گوش|مقدس\s*گندگی|یہ\s*ایک\s*لپیٹ\s*ہے|راکشس|بھگوان|وشواس|آشیرواد|دھنیواد|کرپیا|نمسکار|نمستے|^گندگی[!۔.]*$)"""
    )

    private val URDU_SPOKEN_DISCOURSE_MARKERS_REGEX = Regex(
        """(?:^|[\s\u200F"،۔؟!])(دیکھو|سنو|چلو|بھئی|یار|خیر|ویسے|اچھا|واقعی|بالکل|شاید|یعنی|پتہ\s*ہے|لگتا\s*ہے|سمجھے|چھوڑو|رہنے\s*دو|جلدی\s*کرو|فکر\s*نہ\s*کرو|کیا\s*ہوا|کچھ\s*نہیں|سب\s*ٹھیک\s*ہے)(?=$|[\s\u200F"،۔؟!])"""
    )

    internal fun refineUrduToSpokenIdiom(input: String): String {
        if (input.isBlank()) return input
        var text = input
        for ((pattern, replacement) in URDU_SPOKEN_IDIOM_REWRITES) {
            text = pattern.replace(text, replacement)
        }
        text = text
            .replace(Regex("""\s+([۔،؟!])"""), "$1")
            .replace(Regex("""([۔؟!]){2,}"""), "$1")
            .replace(Regex("""،\s*،+"""), "،")
            .replace(Regex("""[ \t]{2,}"""), " ")
        return text.trim()
    }

    internal fun isBrokenSmtUrdu(cues: List<SuperStreamSrtCue>): Boolean {
        if (cues.isEmpty()) return false
        val sampleSize = minOf(cues.size, 80)
        var ungrammaticalTerminalCount = 0
        var danglingPrepositionEndCount = 0
        var isolatedLatinLeakCount = 0
        var severeSmtCueCount = 0

        val ungrammaticalPattern = Regex("""(کے\s*ہے|کی\s*ہیں|کا\s*ہیں|میں\s*سے\s*ہے\s*کہ\s*میں|کرنے\s*کا\s*ہے\s*کی|کا\s*کے\s*لیے\s*ہے)""")
        val danglingEndPattern = Regex("""(?:^|\s)(کا|کی|کے|کو|سے|پر|میں)\s*[۔.؟!]?\s*$""")
        val latinLeakPattern = Regex("""[A-Za-z]{4,}""")
        val embeddedSmtLeakPattern = Regex("""([\u0600-\u06FF]+\s+[A-Za-z]{4,}\s+[\u0600-\u06FF]+|__+|\[\s*\d+\s*\]|\{\s*\d+\s*\})""")

        for (i in 0 until sampleSize) {
            val raw = cues[i].text.trim().trim(SUPERSTREAM_RLM, '\u200E')
            val strippedTags = raw.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "")
            val hasUngrammatical = ungrammaticalPattern.containsMatchIn(strippedTags)
            val hasDangling = danglingEndPattern.containsMatchIn(strippedTags)
            val hasLatin = latinLeakPattern.containsMatchIn(strippedTags)
            val hasEmbeddedLeak = embeddedSmtLeakPattern.containsMatchIn(strippedTags)
            if (hasUngrammatical) ungrammaticalTerminalCount++
            if (hasDangling) danglingPrepositionEndCount++
            if (hasLatin) isolatedLatinLeakCount++
            if (hasUngrammatical || hasEmbeddedLeak || (hasDangling && hasLatin)) {
                severeSmtCueCount++
            }
        }
        if (sampleSize < 10) {
            return severeSmtCueCount * 2 >= sampleSize ||
                ungrammaticalTerminalCount >= 1 ||
                (danglingPrepositionEndCount >= 2 && isolatedLatinLeakCount >= 1)
        }
        return (ungrammaticalTerminalCount * 100 / sampleSize >= 15) ||
            (danglingPrepositionEndCount * 100 / sampleSize >= 35) ||
            (isolatedLatinLeakCount * 100 / sampleSize >= 45) ||
            (severeSmtCueCount * 100 / sampleSize >= 25)
    }

    internal fun scoreUrduSubtitleNaturalness(cues: List<SuperStreamSrtCue>): Int {
        if (cues.isEmpty()) return -100
        val sampleSize = minOf(cues.size, 100)
        var score = 0
        var spokenMarkers = 0
        var literalArtifacts = 0
        var naturalTerminals = 0

        val naturalTerminalRegex = Regex("""(ہے|ہیں|تھا|تھی|تھے|گا|گی|گے|ہوں|ہو|نا|لو|دو|جاؤ|آؤ|کرو|کریں|نہیں)[۔؟!…"]*\s*$""")
        for (i in 0 until sampleSize) {
            val line = cues[i].text.trim().trim(SUPERSTREAM_RLM, '\u200E')
            if (URDU_SPOKEN_DISCOURSE_MARKERS_REGEX.containsMatchIn(line)) spokenMarkers++
            if (URDU_LITERAL_MT_ARTIFACT_REGEX.containsMatchIn(line)) literalArtifacts++
            if (naturalTerminalRegex.containsMatchIn(line)) naturalTerminals++
        }
        score += (spokenMarkers * 8).coerceAtMost(80)
        score += (naturalTerminals * 100 / sampleSize).coerceAtMost(60)
        score -= (literalArtifacts * 25)
        if (isBrokenSmtUrdu(cues)) score -= 200
        return score
    }

    internal fun sanitizeAndApplyRtlBidi(cueText: String): String {
        if (cueText.isBlank()) return ""
        val normalized = refineUrduToSpokenIdiom(normalizeUrduTypography(cueText))

        val withoutNonInlineTags = decodeSubtitleHtmlEntities(
            normalized
                .replace(SUPERSTREAM_NON_INLINE_ASS_TAG_REGEX, "")
                .replace(SUPERSTREAM_VTT_KARAOKE_TAG_REGEX, "")
                .replace(SUPERSTREAM_NON_INLINE_HTML_TAG_REGEX, "")
        ).trim()

        if (withoutNonInlineTags.isEmpty()) return ""
        val plainTextCheck = withoutNonInlineTags.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim()
        if (plainTextCheck.isEmpty() || isWatermarkCue(plainTextCheck)) return ""

        val lines = withoutNonInlineTags.split('\n')
        val processedLines = lines.mapNotNull { rawLine ->
            val stripped = refineUrduToSpokenIdiom(
                rawLine.trim().trim(SUPERSTREAM_RLM, '\u200E', '\u202A', '\u202B', '\u202C')
            )
            if (stripped.isEmpty()) return@mapNotNull null
            val plainLine = stripped.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim()
            if (plainLine.isEmpty() || isWatermarkCue(plainLine)) return@mapNotNull null
            if (SUPERSTREAM_URDU_SCRIPT_REGEX.containsMatchIn(stripped)) {
                "$SUPERSTREAM_RLM$stripped$SUPERSTREAM_RLM"
            } else {
                stripped
            }
        }

        return processedLines.joinToString("\n")
    }

    internal fun normalizeUrduSubtitleText(rawText: String): String {
        if (rawText.isBlank()) return ""
        val normalizedRaw = normalizeUrduTypography(rawText)
        val cues = parseSuperStreamCues(normalizedRaw)
        if (cues.isNotEmpty()) {
            return formatCuesAsUrduSrt(cues)
        }
        return normalizedRaw
            .lineSequence()
            .map { line -> sanitizeAndApplyRtlBidi(line) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    internal fun protectSubtitleTags(text: String): Pair<String, List<String>> {
        if (text.isEmpty()) return "" to emptyList()
        val tags = mutableListOf<String>()
        val protectedText = SUPERSTREAM_PROTECTED_SUBTITLE_TOKEN_REGEX.replace(text) { match ->
            val idx = tags.size
            tags.add(match.value)
            "\u3016$idx\u3017"
        }
        return protectedText to tags
    }

    internal fun restoreSubtitleTags(translated: String, tags: List<String>): String {
        if (translated.isEmpty()) return ""
        val restored = if (tags.isEmpty()) {
            translated
        } else {
            SUPERSTREAM_RESTORE_TAG_PLACEHOLDER_REGEX.replace(translated) { match ->
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
        return SUPERSTREAM_STRAY_TAG_PLACEHOLDER_REGEX.replace(restored, "").trim()
    }

    private fun readBoundedStream(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            val allowed = (SUPERSTREAM_MAX_INFLATE_BYTES - total).coerceAtMost(read)
            if (allowed > 0) {
                out.write(buf, 0, allowed)
                total += allowed
            }
            if (total >= SUPERSTREAM_MAX_INFLATE_BYTES) break
        }
        return out.toByteArray()
    }

    /**
     * Strict variant of `readBoundedStream` for raw subtitle downloads: returns null when the
     * response exceeds `SUPERSTREAM_MAX_INFLATE_BYTES` instead of silently truncating (a
     * truncated `.srt`/`.zip` payload would be decoded into a corrupted subtitle), so oversized
     * responses are rejected without ever buffering more than the cap into heap memory.
     */
    private fun readBoundedStreamOrOverflow(input: java.io.InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) return out.toByteArray()
            total += read
            if (total > SUPERSTREAM_MAX_INFLATE_BYTES) return null
            out.write(buf, 0, read)
        }
    }

    internal data class SuperStreamSrtCue(val timestamp: String, val text: String)

    internal fun parseSuperStreamCues(rawContent: String): List<SuperStreamSrtCue> {
        val content = rawContent
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val cues = mutableListOf<SuperStreamSrtCue>()
        var i = 0
        if (lines.firstOrNull()?.trim()?.startsWith("WEBVTT", ignoreCase = true) == true) {
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty()) i++
        }
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            if (SUPERSTREAM_VTT_METADATA_BLOCK_REGEX.matches(line)) {
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    i++
                }
                continue
            }
            var tsMatch = SUPERSTREAM_SRT_TIMESTAMP_REGEX.matchEntire(line)
            if (tsMatch == null && i + 1 < lines.size) {
                val nextMatch = SUPERSTREAM_SRT_TIMESTAMP_REGEX.matchEntire(lines[i + 1].trim())
                if (nextMatch != null) {
                    tsMatch = nextMatch
                    i += 2
                } else {
                    i++
                    continue
                }
            } else if (tsMatch != null) {
                i++
            } else {
                i++
                continue
            }
            val start = normalizeTimestamp(tsMatch.groupValues[1])
            val end = normalizeTimestamp(tsMatch.groupValues[2])
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                val cleaned = decodeSubtitleHtmlEntities(
                    lines[i]
                        .replace(SUPERSTREAM_NON_INLINE_ASS_TAG_REGEX, "")
                        .replace(SUPERSTREAM_VTT_KARAOKE_TAG_REGEX, "")
                        .replace(SUPERSTREAM_NON_INLINE_HTML_TAG_REGEX, "")
                ).trim()
                if (cleaned.isNotEmpty()) textLines.add(cleaned)
                i++
            }
            val combined = textLines.joinToString("\n").trim()
            val plainCombined = combined.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim()
            if (plainCombined.isNotEmpty() && !isWatermarkCue(plainCombined)) {
                cues.add(SuperStreamSrtCue("$start --> $end", combined))
            }
        }
        return cues
    }

    internal fun normalizeTimestamp(ts: String): String {
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

    internal fun formatCuesAsUrduSrt(cues: List<SuperStreamSrtCue>): String {
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
        return sb.toString().trimEnd().takeIf { it.isNotBlank() }?.plus("\n") ?: ""
    }

    private val ENGLISH_SPOKEN_IDIOM_PRE_ADAPTATIONS: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\boh,?\s*my\s*(?:gosh|god|goodness|lord)\b""") to "Oh God",
        Regex("""(?i)\bholy\s*(?:shit|crap|cow|hell|moly)\b""") to "Oh my God",
        Regex("""(?i)\bwhat\s+the\s+(?:hell|fuck|heck)\b""") to "what on earth",
        Regex("""(?i)^(\s*[—–-]?\s*)(?:christ|jesus|jesus\s+christ)\s*([!?.]*)\s*$""") to "$1Oh God$2",
        Regex("""(?i)^(\s*[—–-]?\s*)(?:shit|damn\s*it|dammit|fuck)\s*([!?.]*)\s*$""") to "$1Curse it$2",
        Regex("""(?i)\bbreak\s+a\s+leg\b""") to "wishing you success",
        Regex("""(?i)\bpiece\s+of\s+cake\b""") to "very easy",
        Regex("""(?i)\bit'?s\s+a\s+wrap\b""") to "the work is finished",
        Regex("""(?i)\bhit\s+the\s+sack\b""") to "go to sleep",
        Regex("""(?i)\bunder\s+the\s+weather\b""") to "feeling unwell",
        Regex("""(?i)\bspill\s+the\s+beans\b""") to "reveal the secret",
        Regex("""(?i)\bcost\s+an\s+arm\s+and\s+a\s+leg\b""") to "cost a fortune",
        Regex("""(?i)\bbite\s+the\s+bullet\b""") to "face the difficulty bravely",
        Regex("""(?i)\bcut\s+me\s+some\s+slack\b""") to "give me a break",
        Regex("""(?i)\bgive\s+me\s+a\s+break\b""") to "please stop exaggerating",
        Regex("""(?i)\byou(?:'re|\s+are)\s+kidding(?:\s+me)?\b""") to "you must be joking",
        Regex("""(?i)\bno\s+way\s*([!.]*)$""") to "absolutely not$1",
        Regex("""(?i)\bmy\s+bad\b""") to "my mistake",
        Regex("""(?i)\bhang\s+in\s+there\b""") to "stay strong",
        Regex("""(?i)\bcall\s+it\s+a\s+day\b""") to "stop working for today",
        Regex("""(?i)\bget\s+out\s+of\s+hand\b""") to "get out of control",
        Regex("""(?i)\bpull\s+yourself\s+together\b""") to "calm yourself down",
        Regex("""(?i)\bbeat\s+around\s+the\s+bush\b""") to "avoid the main point"
    )

    internal fun adaptEnglishForSpokenUrduTranslation(englishText: String): String {
        if (englishText.isBlank()) return englishText
        var adapted = englishText
        for ((regex, replacement) in ENGLISH_SPOKEN_IDIOM_PRE_ADAPTATIONS) {
            adapted = regex.replace(adapted, replacement)
        }
        return adapted
    }

    private fun parseCueEndMs(timestamp: String): Long? {
        val parts = timestamp.split("-->")
        if (parts.size < 2) return null
        return parseSrtTimeMs(parts[1].trim())
    }

    private fun parseCueStartMs(timestamp: String): Long? {
        val parts = timestamp.split("-->")
        if (parts.isEmpty()) return null
        return parseSrtTimeMs(parts[0].trim())
    }

    private fun parseSrtTimeMs(ts: String): Long? {
        return try {
            val mainAndMs = ts.replace('.', ',').split(',')
            val hms = mainAndMs[0].split(':')
            if (hms.size != 3) return null
            val h = hms[0].toLong()
            val m = hms[1].toLong()
            val s = hms[2].toLong()
            val ms = mainAndMs.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
            ((h * 3600L + m * 60L + s) * 1000L) + ms
        } catch (_: Exception) {
            null
        }
    }

    private val CONTINUATION_STARTER_WORD_REGEX = Regex(
        """^(?:I|I'm|I've|I'd|I'll|and|but|or|so|because|that|which|who|whom|whose|where|when|while|if|unless|until|since|though|although|even|as|than|to|for|with|from|into|onto|upon|about|after|before|without|within|between|through|during)\b"""
    )

    internal fun shouldMergeContinuationCues(firstCue: SuperStreamSrtCue, secondCue: SuperStreamSrtCue): Boolean {
        val aEnd = parseCueEndMs(firstCue.timestamp)
        val bStart = parseCueStartMs(secondCue.timestamp)
        if (aEnd != null && bStart != null) {
            val gap = bStart - aEnd
            if (gap < 0L || gap > 1500L) return false
        }
        return shouldMergeContinuationCues(firstCue.text, secondCue.text)
    }

    internal fun shouldMergeContinuationCues(firstCueText: String, secondCueText: String): Boolean {
        val a = firstCueText.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim()
        val b = secondCueText.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.startsWith("-") || a.startsWith("—") || b.startsWith("-") || b.startsWith("—")) return false
        if (a.startsWith("[") || a.endsWith("]") || b.startsWith("[") || b.endsWith("]")) return false
        val lastChar = a.last()
        if (lastChar == '.' && !a.endsWith("...")) return false
        if (lastChar == '!' || lastChar == '?' || lastChar == ':' || lastChar == ';' || lastChar == '"') return false
        val bClean = b.removePrefix("...").trimStart()
        val firstAlpha = bClean.firstOrNull { it.isLetter() } ?: return false
        val aEndsContinuation = a.endsWith("...") || a.endsWith("…") || a.endsWith(",") || lastChar.isLetterOrDigit()
        val bStartsContinuation = firstAlpha.isLowerCase() ||
            a.endsWith("...") ||
            a.endsWith("…") ||
            CONTINUATION_STARTER_WORD_REGEX.containsMatchIn(bClean)
        return aEndsContinuation && bStartsContinuation
    }

    private fun extractLeadingAndTrailingTags(text: String): Pair<String, String> {
        val trimmed = text.trim()
        val leadingMatch = Regex("""^(?:(?i)<(?:i|b|u|s)\b[^>]*>|\{\\an[1-9]\})+""").find(trimmed)
        val trailingMatch = Regex("""(?:(?i)</(?:i|b|u|s)>)+$""").find(trimmed)
        return (leadingMatch?.value ?: "") to (trailingMatch?.value ?: "")
    }

    internal fun splitTranslatedUrduProportionally(translated: String, parts: Int, weights: List<Int>): List<String> {
        if (parts <= 1) return listOf(translated)
        val clean = translated.trim()
        val (openTag, closeTag) = extractLeadingAndTrailingTags(clean)
        val innerClean = clean.removePrefix(openTag).removeSuffix(closeTag).trim()

        val words = innerClean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size < parts) {
            return List(parts) { idx ->
                val body = if (idx == 0) innerClean else words.lastOrNull() ?: innerClean
                if (openTag.isNotEmpty() || closeTag.isNotEmpty()) "$openTag$body$closeTag" else body
            }
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val result = ArrayList<String>(parts)
        var wordCursor = 0
        var accumulatedWeight = 0
        for (i in 0 until parts) {
            val segment = if (i == parts - 1) {
                val tail = words.subList(wordCursor, words.size).joinToString(" ")
                tail.ifBlank { words.last() }
            } else {
                accumulatedWeight += weights.getOrElse(i) { 1 }
                val minSplit = wordCursor + 1
                val maxSplit = (words.size - (parts - 1 - i)).coerceAtLeast(minSplit)
                val targetEnd = ((words.size.toDouble() * accumulatedWeight / totalWeight).toInt())
                    .coerceIn(minSplit, maxSplit)
                var bestEnd = targetEnd
                for (offset in listOf(0, -1, 1)) {
                    val cand = targetEnd + offset
                    if (cand in minSplit..maxSplit) {
                        val prevWord = words[cand - 1]
                        if (prevWord.endsWith("،") || prevWord.endsWith(",")) {
                            bestEnd = cand
                            break
                        }
                    }
                }
                val slice = words.subList(wordCursor, bestEnd).joinToString(" ")
                wordCursor = bestEnd
                slice
            }
            result.add(if (openTag.isNotEmpty() || closeTag.isNotEmpty()) "$openTag$segment$closeTag" else segment)
        }
        return result
    }

    internal suspend fun translateEnglishSrtToUrdu(
        englishContent: String,
        batchTranslator: (suspend (List<String>) -> List<String>?)? = null
    ): String? = coroutineScope {
        val cues = parseSuperStreamCues(englishContent)
        if (cues.isEmpty()) return@coroutineScope null

        val translator: suspend (List<String>) -> List<String>? =
            batchTranslator ?: { texts ->
                translateBatchViaGoogle(GOOGLE_TRANSLATE_CLIENTS5_URL, texts)
                    ?: translateBatchViaGoogle(GOOGLE_TRANSLATE_FALLBACK_URL, texts)
            }

        data class TranslationUnit(val cueIndices: List<Int>, val combinedEnglish: String, val weights: List<Int>)

        fun buildUnits(chunkCues: List<SuperStreamSrtCue>): List<TranslationUnit> {
            val units = ArrayList<TranslationUnit>(chunkCues.size)
            var i = 0
            while (i < chunkCues.size) {
                if (i + 1 < chunkCues.size && shouldMergeContinuationCues(chunkCues[i], chunkCues[i + 1])) {
                    val indices = mutableListOf(i, i + 1)
                    val texts = mutableListOf(chunkCues[i].text, chunkCues[i + 1].text)
                    val combined12 = SuperStreamSrtCue(
                        timestamp = "${chunkCues[i].timestamp.substringBefore("-->").trim()} --> ${chunkCues[i + 1].timestamp.substringAfter("-->").trim()}",
                        text = "${chunkCues[i].text.trim().removeSuffix("...")} ${chunkCues[i + 1].text.trim().removePrefix("...")}"
                    )
                    if (i + 2 < chunkCues.size &&
                        shouldMergeContinuationCues(combined12, chunkCues[i + 2]) &&
                        (texts[0].length + texts[1].length + chunkCues[i + 2].text.length) <= 220
                    ) {
                        indices.add(i + 2)
                        texts.add(chunkCues[i + 2].text)
                        i += 3
                    } else {
                        i += 2
                    }
                    val combined = texts.joinToString(" ") {
                        it.trim().removeSuffix("...").removePrefix("...").trim()
                    }.replace(Regex("""\s+"""), " ")
                    val weights = texts.map {
                        it.replace(SUPERSTREAM_INLINE_TAG_ONLY_REGEX, "").trim().length.coerceAtLeast(1)
                    }
                    units.add(TranslationUnit(indices, adaptEnglishForSpokenUrduTranslation(combined), weights))
                } else {
                    val adapted = adaptEnglishForSpokenUrduTranslation(chunkCues[i].text)
                    units.add(TranslationUnit(listOf(i), adapted, listOf(adapted.length.coerceAtLeast(1))))
                    i++
                }
            }
            return units
        }

        suspend fun translateChunk(chunkCues: List<SuperStreamSrtCue>): List<SuperStreamSrtCue>? {
            if (chunkCues.isEmpty()) return emptyList()
            val units = buildUnits(chunkCues)
            val protectedPairs = units.map { protectSubtitleTags(it.combinedEnglish) }
            val sourceTexts = protectedPairs.map { it.first }
            val translatedTexts = translationBatchSemaphore.withPermit {
                translator(sourceTexts)
            }
            if (translatedTexts != null && translatedTexts.size == units.size) {
                val outCues = Array<SuperStreamSrtCue?>(chunkCues.size) { null }
                for (uIdx in units.indices) {
                    val unit = units[uIdx]
                    val restored = refineUrduToSpokenIdiom(
                        restoreSubtitleTags(translatedTexts[uIdx], protectedPairs[uIdx].second)
                    )
                    if (unit.cueIndices.size == 1) {
                        val cIdx = unit.cueIndices[0]
                        outCues[cIdx] = chunkCues[cIdx].copy(text = restored)
                    } else {
                        val plainRestored = SUPERSTREAM_PROTECTED_SUBTITLE_TOKEN_REGEX.replace(restored, "").trim()
                        val splitParts = splitTranslatedUrduProportionally(plainRestored, unit.cueIndices.size, unit.weights)
                        for (pIdx in unit.cueIndices.indices) {
                            val cIdx = unit.cueIndices[pIdx]
                            val origCue = chunkCues[cIdx]
                            val (leadTag, trailTag) = extractLeadingAndTrailingTags(origCue.text)
                            val partText = splitParts[pIdx]
                            val wrapped = if (leadTag.isNotEmpty() || trailTag.isNotEmpty()) "$leadTag$partText$trailTag" else partText
                            outCues[cIdx] = origCue.copy(text = wrapped)
                        }
                    }
                }
                return outCues.filterNotNull()
            }
            return null
        }

        val batches = cues.chunked(SUPERSTREAM_TRANSLATION_BATCH_SIZE)
        val translatedBatches = batches.map { batch ->
            async(Dispatchers.IO) {
                translateChunk(batch) ?: run {
                    if (batch.size <= 1) return@run emptyList<SuperStreamSrtCue>()
                    val subChunkSize = if (batch.size > SUPERSTREAM_TRANSLATION_SUB_BATCH_SIZE) {
                        SUPERSTREAM_TRANSLATION_SUB_BATCH_SIZE
                    } else {
                        maxOf(1, (batch.size + 1) / 2)
                    }
                    val subChunks = batch.chunked(subChunkSize)
                    val recovered = mutableListOf<SuperStreamSrtCue>()
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

        val merged = translatedBatches.flatten()
        if (merged.isEmpty() || merged.size * 100 < cues.size * 60) {
            return@coroutineScope null
        }
        formatCuesAsUrduSrt(merged).takeIf { it.isNotBlank() }
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

    internal fun parseGoogleBatchResponse(
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
                result.add(extractGoogleTranslateItemText(item))
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cancellable batch translation: the HTTP call is enqueued (never a blocking `execute()` on
     * the caller thread) and the in-flight call is cancelled when the awaiting coroutine is
     * cancelled, so `withTimeoutOrNull` expiry in Phase 4 tears the socket down promptly instead
     * of leaking a blocked read for the remainder of the request timeout.
     */
    private suspend fun translateBatchViaGoogle(endpoint: String, texts: List<String>): List<String>? {
        return try {
            val formBuilder = FormBody.Builder(Charsets.UTF_8)
            for (text in texts) {
                formBuilder.add("q", text)
            }
            val req = okhttp3.Request.Builder()
                .url(endpoint)
                .post(formBuilder.build())
                .header("User-Agent", SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            suspendCancellableCoroutine { cont ->
                val call = app.baseClient.newCall(req)
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : okhttp3.Callback {
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use { resp ->
                            val parsed = if (!resp.isSuccessful) {
                                null
                            } else {
                                runCatching {
                                    parseGoogleBatchResponse(resp.body.string(), expectedCount = texts.size)
                                }.getOrNull()
                            }
                            if (cont.isActive) cont.resume(parsed, onCancellation = null)
                        }
                    }

                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (cont.isActive) cont.resume(null, onCancellation = null)
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal fun isCachedSubtitleUriValid(uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        if (uri.startsWith("file:", ignoreCase = true)) {
            val filePath = uri.removePrefix("file://").removePrefix("file:")
            val f = File(filePath)
            return f.exists() && f.isFile && f.length() > 16L
        }
        return uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)
    }

    private const val MAX_DISK_CACHE_FILES = 64
    private const val MAX_DISK_CACHE_BYTES = 16L * 1024L * 1024L

    internal fun evictStaleAndExcessCacheFiles(baseDir: File) {
        try {
            val now = System.currentTimeMillis()
            val files = baseDir.listFiles { f ->
                f.isFile && f.name.startsWith("cs_urdu_", ignoreCase = true) && f.name.endsWith(".srt", ignoreCase = true)
            }?.toMutableList() ?: return

            val iterator = files.iterator()
            while (iterator.hasNext()) {
                val f = iterator.next()
                val age = (now - f.lastModified()).coerceAtLeast(0L)
                if (age > SUPERSTREAM_PERSISTENT_URL_CACHE_TTL_MS || f.length() == 0L) {
                    runCatching { f.delete() }
                    iterator.remove()
                }
            }

            var remainingCount = files.size
            var totalBytes = files.sumOf { it.length() }
            if (remainingCount > MAX_DISK_CACHE_FILES || totalBytes > MAX_DISK_CACHE_BYTES) {
                files.sortBy { it.lastModified() }
                for (f in files) {
                    if (remainingCount <= MAX_DISK_CACHE_FILES && totalBytes <= MAX_DISK_CACHE_BYTES) break
                    val len = f.length()
                    if (runCatching { f.delete() }.getOrDefault(false)) {
                        remainingCount--
                        totalBytes = (totalBytes - len).coerceAtLeast(0L)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Atomic single-shot cache write (`.tmp` sibling + `ATOMIC_MOVE` rename, `renameTo` fallback)
     * so readers sharing this cache directory — including StreamPlay's engine — can never observe
     * a partially written `.srt`. Returns false without leaving temporary files behind on failure.
     */
    private fun writeSuperStreamCacheFileAtomically(file: File, payloadBytes: ByteArray): Boolean {
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

    internal fun writeLocalUrduSrtFile(urduSrtContent: String, urlCacheKey: String? = null): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(urduSrtContent.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(24)
            val baseDir = superStreamAppContextRef?.cacheDir
                ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!baseDir.exists()) baseDir.mkdirs()
            val file = File(baseDir, "cs_urdu_cas_$digest.srt")
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            val payloadBytes = bom + urduSrtContent.toByteArray(StandardCharsets.UTF_8)
            if (!file.exists() || file.length() <= 64L) {
                if (!writeSuperStreamCacheFileAtomically(file, payloadBytes)) return null
            } else {
                runCatching { file.setLastModified(System.currentTimeMillis()) }
            }
            if (!urlCacheKey.isNullOrBlank()) {
                resolvePersistentUrlCacheFile(urlCacheKey)?.let { urlFile ->
                    if (urlFile.exists() && urlFile.isFile && urlFile.length() > 64L) {
                        runCatching { urlFile.setLastModified(System.currentTimeMillis()) }
                    } else {
                        writeSuperStreamCacheFileAtomically(urlFile, payloadBytes)
                    }
                }
            }
            evictStaleAndExcessCacheFiles(baseDir)
            "file:///" + file.absolutePath.trimStart('/')
        } catch (_: Exception) {
            null
        }
    }


    suspend fun invokeSuperstreamFeb(
        token: String? = null,
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (token.isNullOrEmpty()) return

        val url = if (season == null) {
            "$NuvFeb/api/media/movie/$id?cookie=${URLEncoder.encode(token, "UTF-8")}"
        } else {
            "$NuvFeb/api/media/tv/$id/$season/$episode?cookie=${URLEncoder.encode(token, "UTF-8")}"
        }

        var parsed: FebResponse? = null

        try {
            for (attempt in 0..1) {
                val response = app.get(url, timeout = 10L)
                if (response.code == 500 && attempt == 0) {
                    delay(1000L)
                } else {
                    parsed = response.parsedSafe<FebResponse>()
                    break
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("SuperStream", "invokeSuperstreamFeb failed: $e")
            return
        }

        parsed ?: return

        parsed.versions.orEmpty().forEach { version ->
            version.links.orEmpty().forEach { link ->
                val streamUrl = link.url ?: return@forEach

                val title = version.name
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::cleanTitle)
                    ?: "Stream"

                val qualityName = link.quality.orEmpty()

                callback.invoke(
                    newExtractorLink(
                        source = "SuperStream",
                        name = buildString {
                            append("SuperStream • ")
                            append(title)
                            if (qualityName.equals("ORG", ignoreCase = true)) {
                                append(" • ORG")
                            }
                        },
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        quality = getQualityFromName(qualityName)
                    }
                )
            }
        }
    }
}
