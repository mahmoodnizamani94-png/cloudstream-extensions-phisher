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
private const val SUBDL_WEB_HOST = "https://subdl.com"
private const val SUBDL_DL_HOST = "https://dl.subdl.com"

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

private val SUPERSTREAM_URDU_SCRIPT_REGEX =
    Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")
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

    private val synthesizedUrduCache = ConcurrentHashMap<String, String>()
    private val materializedUrduCache = ConcurrentHashMap<String, String>()
    private val translationBatchSemaphore = Semaphore(4)

    internal fun clearCachesForTest() {
        synthesizedUrduCache.clear()
        materializedUrduCache.clear()
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
            val urduSubs = allSubs.filter { isUrduSubtitleCode(it.lang) || isUrduSubtitleCode(getLanguage(it.lang)) }
            val englishSubs = allSubs.filter {
                !isUrduSubtitleCode(it.lang) && (isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)))
            }
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
                    subtitleCallback.invoke(newSubtitleFile("Urdu", materializedUri))
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
                        )
                    )
                }
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
        val urduMode = isUrduSubtitleMode(sharedPref)
        val wyzieBases = listOf("https://sub.wyzie.io", "https://sub.wyzie.ru")
        for (base in wyzieBases) {
            try {
                val baseQuery = if (season == null) {
                    "$base/search?id=$cleanId"
                } else {
                    "$base/search?id=$cleanId&season=$season&episode=$episode"
                }
                if (urduMode) {
                    try {
                        val urText = app.get("$baseQuery&language=ur", timeout = 8L).text
                        val urSubs: List<WyZIESUB> = GSON.fromJson(urText, WYZIESUB_LIST_TYPE) ?: emptyList()
                        for (it in urSubs) {
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
                                subtitleCallback.invoke(newSubtitleFile("Urdu", materializedUri))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }

                val res = app.get(baseQuery, timeout = 10L).text
                val subtitles: List<WyZIESUB> = GSON.fromJson(res, WYZIESUB_LIST_TYPE) ?: emptyList()
                if (subtitles.isEmpty()) continue
                val ordered = if (urduMode) {
                    val urList = subtitles.filter {
                        isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)
                    }
                    val enList = subtitles.filter {
                        !(isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)) &&
                            (isEnglishSubtitleCode(it.display) || isEnglishSubtitleCode(it.language))
                    }
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
                            val materializedUri = materializeNormalizedUrduSubtitle(
                                url = suburl,
                                headers = SUBTITLE_HEADERS,
                                season = season,
                                episode = episode,
                                sourceTag = "wyzie"
                            ) ?: continue
                            subtitleCallback.invoke(newSubtitleFile("Urdu", materializedUri))
                        } else {
                            subtitleCallback.invoke(newSubtitleFile("English", suburl))
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
                var emitted = false
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
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
                        subtitleCallback.invoke(newSubtitleFile("Urdu", materializedUri))
                        emitted = true
                    }
                }
                if (emitted) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Keyless SubDL Urdu Harvester (`invokeSubDlUrduKeyless`):
     * Queries `api3.subdl.com/auto` (with fail-closed year check when `expectedYear != null`),
     * fetches `subdl.com/subtitle/{sd_id}/{slug}/urdu`, extracts episode-matched `.zip` URLs,
     * and materializes them via [materializeNormalizedUrduSubtitle].
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
            val headers = mapOf(
                "User-Agent" to (SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0"),
                "Accept" to "application/json, text/plain, */*",
                "Origin" to SUBDL_WEB_HOST,
                "Referer" to "$SUBDL_WEB_HOST/"
            )
            val autoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(query, "UTF-8")}"
            val autoBody = runCatching {
                app.get(autoUrl, headers = headers, timeout = 5L).text
            }.getOrNull()
            var slugLink = autoBody?.let { parseSubDlAutoSlug(it, expectedYear) }
            if (slugLink == null && cleanImdb != null && cleanTitle != null) {
                val fallbackAutoUrl = "$SUBDL_AUTO_API?query=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                val fallbackBody = runCatching {
                    app.get(fallbackAutoUrl, headers = headers, timeout = 5L).text
                }.getOrNull()
                slugLink = fallbackBody?.let { parseSubDlAutoSlug(it, expectedYear) }
            }
            if (slugLink.isNullOrBlank()) return

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
            for (zipUrl in zipUrls) {
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
                subtitleCallback.invoke(newSubtitleFile("Urdu", materializedUri))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
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

    internal fun parseSubDlUrduPageZips(
        htmlBody: String,
        season: Int? = null,
        episode: Int? = null
    ): List<String> {
        if (htmlBody.isBlank()) return emptyList()
        val results = LinkedHashSet<String>()
        val zipRegex = Regex("""https?://dl\.subdl\.com/subtitle/\d+-\d+\.zip|/subtitle/\d+-\d+\.zip""", RegexOption.IGNORE_CASE)

        val doc = Jsoup.parse(htmlBody)
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
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (englishSubs.isEmpty()) return@withContext false
        for (candidate in englishSubs.take(3)) {
            val enUrl = candidate.url.trim()
            if (!enUrl.startsWith("http", ignoreCase = true)) continue
            val cacheKey = "$enUrl|S${season ?: 0}E${episode ?: 0}"
            synthesizedUrduCache[cacheKey]?.let { cachedUri ->
                if (isCachedSubtitleUriValid(cachedUri)) {
                    subtitleCallback(newSubtitleFile("Urdu", cachedUri))
                    return@withContext true
                } else {
                    synthesizedUrduCache.remove(cacheKey)
                }
            }
            val rawBytes = try {
                app.get(enUrl, headers = SUBTITLE_HEADERS, timeout = 6L).okhttpResponse.body.bytes()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val decodedEnglish = decodeAndNormalizeSubtitleBytes(rawBytes, season = season, episode = episode)
            if (decodedEnglish.length < 32) continue
            val urduSrt = translateEnglishSrtToUrdu(decodedEnglish) ?: continue
            val fileUri = writeLocalUrduSrtFile(cacheKey, urduSrt) ?: continue
            synthesizedUrduCache[cacheKey] = fileUri
            subtitleCallback(newSubtitleFile("Urdu", fileUri))
            return@withContext true
        }
        false
    }

    /**
     * Downloads, unpacks (GZIP/episode-aware ZIP), decodes (`Windows-1256`, `UTF-16LE/BE`, `UTF-8`),
     * normalizes Urdu typography + RTL BiDi, validates cues, and materializes a human Urdu subtitle URL
     * into a UTF-8-BOM local `.srt` file (`file:///...`). Fails closed (`null`) if download or cue validation fails.
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

        val rawBytes = try {
            app.get(cleanUrl, headers = headers, timeout = 8L).okhttpResponse.body.bytes()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext null

        if (rawBytes.isEmpty()) return@withContext null
        val decoded = decodeAndNormalizeSubtitleBytes(rawBytes, season = season, episode = episode)
        if (decoded.isBlank()) return@withContext null
        val normalized = normalizeUrduSubtitleText(decoded)
        if (parseSuperStreamCues(normalized).isEmpty()) return@withContext null
        val localUri = writeLocalUrduSrtFile("$sourceTag|$cacheKey", normalized) ?: return@withContext null
        materializedUrduCache[cacheKey] = localUri
        localUri
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
        return SUPERSTREAM_WATERMARK_PATTERNS.any { pattern -> lower.contains(pattern.lowercase(Locale.ROOT)) }
    }

    internal fun sanitizeAndApplyRtlBidi(cueText: String): String {
        if (cueText.isBlank()) return ""
        val normalized = normalizeUrduTypography(cueText)

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
            val stripped = rawLine.trim().trim(SUPERSTREAM_RLM, '\u200E', '\u202A', '\u202B', '\u202C')
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

        suspend fun translateChunk(chunkCues: List<SuperStreamSrtCue>): List<SuperStreamSrtCue>? {
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

    private fun translateBatchViaGoogle(endpoint: String, texts: List<String>): List<String>? {
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
            app.baseClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body.string()
                parseGoogleBatchResponse(body, expectedCount = texts.size)
            }
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

    internal fun writeLocalUrduSrtFile(keySeed: String, urduSrtContent: String): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(keySeed.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val baseDir = superStreamAppContextRef?.cacheDir
                ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!baseDir.exists()) baseDir.mkdirs()
            val file = File(baseDir, "superstream_urdu_$digest.srt")
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            file.writeBytes(bom + urduSrtContent.toByteArray(StandardCharsets.UTF_8))
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
