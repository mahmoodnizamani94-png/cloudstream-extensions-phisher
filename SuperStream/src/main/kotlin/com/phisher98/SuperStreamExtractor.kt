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

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

private val ORG_QUALITY_REGEX = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)

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

object SuperStreamExtractor : SuperStream() {

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

        coroutineScope {
            fids.mapIndexed { index, fileList ->
                async {
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
                            return@async
                        }
                        val htmlContent = json.optString("html", "")
                        if (htmlContent.isEmpty()) return@async

                        val document: Document = Jsoup.parse(htmlContent)
                        val sourcesWithQualities = mutableListOf<Triple<String, String, String>>()

                        document.select("div.file_quality").forEach { element ->
                            val url = element.attr("data-url").takeIf { it.isNotEmpty() } ?: return@forEach
                            val qualityAttr = element.attr("data-quality").takeIf { it.isNotEmpty() }
                            val size = element.selectFirst(".size")?.text()?.takeIf { it.isNotEmpty() } ?: return@forEach

                            val quality = if (qualityAttr.equals("ORG", ignoreCase = true)) {
                                ORG_QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "2160p"
                            } else {
                                qualityAttr ?: return@forEach
                            }

                            sourcesWithQualities.add(Triple(url, quality, size))
                        }

                        sourcesWithQualities.forEach { (url, label, size) ->
                            val format = ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    "⌜ SuperStream ⌟",
                                    "⌜ SuperStream ⌟ [Server ${index + 1}] $size",
                                    url.replace("\\/", "/"),
                                    format
                                ) {
                                    this.quality = getIndexQuality(label)
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("SuperStream", "invokeExternalSource failed: $e")
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
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$id.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$id:$season:$episode.json"
        }
        app.get(url, headers = SUBTITLE_HEADERS, timeout = 10L)
            .parsedSafe<SubtitlesAPI>()?.subtitles?.amap {
                val lan = getLanguage(it.lang) ?: "Unknown"
                val suburl = it.url
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lan.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                        suburl
                    )
                )
            }
    }


    suspend fun invokeWyZIESUBAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val WyZIESUBAPI = "https://sub.wyzie.ru"
        val url = if (season == null) {
            "$WyZIESUBAPI/search?id=$id"
        } else {
            "$WyZIESUBAPI/search?id=$id&season=$season&episode=$episode"
        }

        val res = app.get(url, timeout = 10L).text
        val gson = Gson()
        val listType = object : TypeToken<List<WyZIESUB>>() {}.type
        val subtitles: List<WyZIESUB> = gson.fromJson(res, listType)
        subtitles.map {
            val lan = it.display
            val suburl = it.url
            subtitleCallback.invoke(
                newSubtitleFile(
                    lan.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    suburl
                )
            )
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
