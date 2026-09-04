package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private val QUALITY_REGEX = Regex("(\\d{3,4})[pP]")
private val ORG_QUALITY_REGEX = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
private val SOURCES_VAR_REGEX = Regex("""var\s+sources\s*=\s*(\[[\s\S]*?]);""")

object ShowBoxExtractor : ShowBox() {

    private val videoHeaders by lazy {
        mapOf(
            "Accept" to "*/*",
            "Accept-Encoding" to "identity",
            "Accept-Language" to "en-US,en;q=0.8",
            "Connection" to "keep-alive",
            "Referer" to thirdAPI,
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
    }

    private data class CachedShareData(
        val shareKey: String,
        val fids: List<ExternalResponse.Data.FileList>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val shareDataCache = java.util.concurrent.ConcurrentHashMap<String, CachedShareData>()
    private val shareMutex = Mutex()

    private suspend fun getOrResolveShareData(
        mediaId: Int?,
        type: Int?,
        season: Int?,
        episode: Int?
    ): CachedShareData? {
        if (mediaId == null) return null
        val cacheKey = "$mediaId:$type:$season:$episode"
        val cached = shareDataCache[cacheKey]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 10 * 60 * 1000L) {
            return cached
        }

        return shareMutex.withLock {
            val secondCheck = shareDataCache[cacheKey]
            if (secondCheck != null && (System.currentTimeMillis() - secondCheck.timestamp) < 10 * 60 * 1000L) {
                return secondCheck
            }

            val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
            val sharePageResp = app.get(
                "$thirdAPI/mbp/to_share_page?box_type=${type}&mid=$mediaId&json=1",
                timeout = 10L
            ).parsedSafe<ExternalResponse>()?.data

            val shareKey = sharePageResp?.link
                ?: sharePageResp?.shareLink?.substringAfterLast("/")
                ?: return null

            val headers = mapOf("Accept-Language" to "en")
            val shareRes = app.get(
                "$thirdAPI/file/file_share_list?share_key=$shareKey",
                headers = headers,
                timeout = 10L
            ).parsedSafe<ExternalResponse>()?.data ?: return null

            val fids = if (season == null) {
                shareRes.file_list
            } else {
                val parentId =
                    shareRes.file_list?.find { it.file_name.equals("season $season", true) }?.fid
                app.get(
                    "$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1",
                    headers = headers,
                    timeout = 10L
                ).parsedSafe<ExternalResponse>()?.data?.file_list?.filter {
                    it.file_name?.contains("s${seasonSlug}e${episodeSlug}", true) == true
                }
            } ?: return null

            val result = CachedShareData(shareKey, fids)
            shareDataCache[cacheKey] = result
            result
        }
    }

    suspend fun invokeInternalSource(
        id: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        superToken: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        suspend fun LinkList.toExtractorLink(): ExtractorLink? {
            val quality = this.quality
            if (this.path.isNullOrBlank()) return null
            return newExtractorLink(
                "⌜ ShowBox ⌟ Internal",
                "⌜ ShowBox ⌟ Internal [${this.size}]",
                this.path.replace("\\/", ""),
                INFER_TYPE,
            )
            {
                this.quality = getQualityFromName(quality)
                this.headers = videoHeaders
            }
        }
        val query = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_downloadurl_v3","channel":"Website","mid":"$id","lang":"","expired_date":"${getExpiryDate()}","platform":"android","oss":"1","uid":"$superToken","open_udid":"59e139fd173d9045a2b5fc13b40dfd87","group":""}"""
        } else {
            """{"childmode":"0","app_version":"11.5","module":"TV_downloadurl_v3","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","oss":"1","uid":"$superToken","open_udid":"59e139fd173d9045a2b5fc13b40dfd87","appid":"$appId","season":"$season","lang":"en","group":""}"""
        }

        val linkData = queryApiParsed<LinkDataProp>(query)

        linkData.data?.list?.forEach { link ->
            val extractorLink = link.toExtractorLink() ?: return@forEach
            callback.invoke(extractorLink)
        }

        val fid = linkData.data?.list?.firstOrNull { it.fid != null }?.fid

        val subtitleQuery = if (type == ResponseTypes.Movies.value) {
            """{"childmode":"0","fid":"$fid","uid":"","app_version":"11.5","appid":"$appId","module":"Movie_srt_list_v2","channel":"Website","mid":"$id","lang":"en","uid":"$superToken","open_udid":"59e139fd173d9045a2b5fc13b40dfd87","expired_date":"${getExpiryDate()}","platform":"android"}"""
        } else {
            """{"childmode":"0","fid":"$fid","app_version":"11.5","module":"TV_srt_list_v2","channel":"Website","episode":"$episode","expired_date":"${getExpiryDate()}","platform":"android","tid":"$id","uid":"$superToken","open_udid":"59e139fd173d9045a2b5fc13b40dfd87","appid":"$appId","season":"$season","lang":"en"}"""
        }

        val subtitles = queryApiParsed<SubtitleDataProp>(subtitleQuery).data
        subtitles?.list?.forEach { subs ->
            val sub = subs.subtitles.maxByOrNull { it.support_total ?: 0 }
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub?.language ?: sub?.lang ?: return@forEach,
                    sub?.filePath ?: return@forEach
                )
            )
        }
    }


    suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        uitoken: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val shareData = getOrResolveShareData(mediaId, type, season, episode) ?: return
        val shareKey = shareData.shareKey
        val fids = shareData.fids

        coroutineScope {
            fids.mapIndexed { index, fileList ->
                async {
                    try {
                        val superToken = uitoken?.let {
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
                            val size = element.selectFirst(".size")?.text()?.takeIf { it.isNotEmpty() }
                                ?: return@forEach

                            val quality = if (qualityAttr.equals("ORG", ignoreCase = true)) {
                                ORG_QUALITY_REGEX.find(url)?.groupValues?.get(1) ?: "2160p"
                            } else {
                                qualityAttr ?: return@forEach
                            }

                            sourcesWithQualities.add(Triple(url, quality, size))
                        }

                        sourcesWithQualities.forEach { (url, qualityLabel, size) ->
                            callback.invoke(
                                newExtractorLink(
                                    "⌜ ShowBox ⌟ External",
                                    "⌜ ShowBox ⌟ External [Server ${index + 1}] $size",
                                    url.replace("\\/", "/"),
                                    INFER_TYPE
                                ) {
                                    this.headers = videoHeaders
                                    this.quality = getIndexQuality(qualityLabel)
                                }
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("ShowBox", "invokeExternalSource failed for fid ${fileList.fid}: $e")
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun invokeExternalM3u8Source(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        uitoken: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val shareData = getOrResolveShareData(mediaId, type, season, episode) ?: return
        val shareKey = shareData.shareKey
        val fids = shareData.fids

        coroutineScope {
            fids.mapIndexed { index, fileList ->
                async {
                    try {
                        val superToken = uitoken?.let {
                            if (it.startsWith("ui=")) it else "ui=$it"
                        } ?: ""
                        val mediaType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
                        val body = "fid=${fileList.fid}&share_key=$shareKey".toRequestBody(mediaType)
                        val player = app.post(
                            "$thirdAPI/file/player",
                            requestBody = body,
                            headers = mapOf(
                                "Cookie" to superToken,
                                "content-type" to "application/x-www-form-urlencoded; charset=UTF-8"
                            ),
                            timeout = 10L
                        ).text

                        val document = Jsoup.parse(player)

                        val scriptText = document.select("script")
                            .map { it.data() }
                            .firstOrNull { it.contains("var sources") }
                            ?: return@async

                        val sourcesJson = SOURCES_VAR_REGEX
                            .find(scriptText)
                            ?.groupValues
                            ?.get(1)
                            ?: return@async
                        val urls = mutableListOf<String>()

                        val jsonArray = JSONArray(sourcesJson)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val fileUrl = obj.optString("file")
                            if (fileUrl.isNotEmpty()) {
                                urls.add(fileUrl)
                            }
                        }

                        coroutineScope {
                            urls.map { fileUrl ->
                                async {
                                    try {
                                        M3u8Helper.generateM3u8(
                                            "⌜ ShowBox ⌟ External HLS [Server ${index + 1}]",
                                            fileUrl,
                                            "",
                                            headers = videoHeaders
                                        ).forEach(callback)
                                    } catch (e: Exception) {
                                        if (e is CancellationException) throw e
                                    }
                                }
                            }.awaitAll()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e("ShowBox", "invokeExternalM3u8Source failed for fid ${fileList.fid}: $e")
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "$watchSomuchAPI/Watch/ajMovieTorrents.aspx",
            data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            timeout = 10L
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(
            season,
            episode
        )

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        app.get(subUrl, timeout = 10L)
            .parsedSafe<WatchsomuchSubResponses>()?.subtitles
            ?.map { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        sub.label ?: "",
                        fixUrl(sub.url ?: return@map null, watchSomuchAPI)
                    )
                )
            }


    }

    suspend fun invokeOpenSubs(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val slug = if (season == null) {
            "movie/$imdbId"
        } else {
            "series/$imdbId:$season:$episode"
        }
        app.get("${openSubAPI}/subtitles/$slug.json", timeout = 10L)
            .parsedSafe<OsResult>()?.subtitles?.map { sub ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "") ?: sub.lang
                        ?: return@map,
                        sub.url ?: return@map
                    )
                )
            }
    }

    private fun fixUrl(url: String, domain: String): String {
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

    private fun getIndexQuality(str: String?): Int {
        return QUALITY_REGEX.find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }

    private fun getEpisodeSlug(
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
}
