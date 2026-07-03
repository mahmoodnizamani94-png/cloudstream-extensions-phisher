package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeAsync
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlin.coroutines.resume
import org.jsoup.Jsoup
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import com.lagradost.cloudstream3.network.CloudflareKiller

class AnimePahe : MainAPI() {
    companion object {
        // Base headers – CloudflareKiller matches these.
        val headers: Map<String, String> get() {
            val context = com.lagradost.api.getContext() as? android.content.Context
            val ua = com.lagradost.cloudstream3.network.WebViewResolver.webViewUserAgent
                ?: context?.let { runCatching { android.webkit.WebSettings.getDefaultUserAgent(it) }.getOrNull() }
                ?: "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.0.0 Mobile Safari/537.36"
            return mapOf(
                "Cookie"          to "__ddg2_=1234567890",
                "User-Agent"      to ua,
                "Accept-Language" to "en-US,en;q=0.9"
            )
        }

        /** Headers used for fetching images/posters (includes dynamic cf_clearance, referer, and exact UA) */
        val cfHeaders: Map<String, String> get() {
            val map = headers.toMutableMap()
            map["referer"] = "${AnimePaheProviderPlugin.currentAnimepaheServer}/"

            val cm = android.webkit.CookieManager.getInstance()
            val cookies = cm.getCookie(AnimePaheProviderPlugin.currentAnimepaheServer) ?: ""
            if (cookies.isNotEmpty()) {
                map["Cookie"] = "__ddg2_=1234567890; $cookies"
            }
            return map
        }

        private fun getType(t: String): TvType {
            return if (t.contains("OVA") || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie")) TvType.AnimeMovie
            else TvType.Anime
        }

        private val cfKiller = CloudflareKiller()

        suspend fun appGet(url: String, customHeaders: Map<String, String> = headers): com.lagradost.nicehttp.NiceResponse {
            return app.get(url, headers = customHeaders, interceptor = cfKiller)
        }
    }

    override var mainUrl = AnimePaheProviderPlugin.currentAnimepaheServer
    override var name = "AnimePahe"
    override val hasQuickSearch = false
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.AnimeMovie,
        TvType.Anime,
        TvType.OVA
    )

    override val mainPage = listOf(MainPageData("Latest Releases", "$mainUrl/api?m=airing&page=", true))

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        data class Data(
            @JsonProperty("anime_title") val animeTitle: String,
            val episode: Int?,
            val snapshot: String?,
            @JsonProperty("created_at") val createdAt: String?,
            @JsonProperty("anime_session") val animeSession: String,
        )

        data class AnimePaheLatestReleases(
            val total: Int,
            val data: List<Data>
        )
        // appGet handles CF bypass automatically using CFBypassInterceptor
        val response = appGet(request.data + page, headers).text
        val episodes = tryParseJson<AnimePaheLatestReleases>(response)?.data?.map {
            newAnimeSearchResponse(
                it.animeTitle,
                LoadData(it.animeSession, unixTime, it.animeTitle).toJson(),
                fix = false
            ) {
                this.posterUrl = it.snapshot
                this.posterHeaders = cfHeaders
                addDubStatus(DubStatus.Subbed, it.episode)
            }
        }
        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = episodes ?: emptyList(),
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    data class AnimePaheSearchData(
        val id: Int?,
        val slug: String?,
        val title: String,
        val type: String?,
        val episodes: Int?,
        val status: String?,
        val season: String?,
        val year: Int?,
        val score: Double?,
        val poster: String?,
        val session: String,
        val relevance: String?
    )

    data class AnimePaheSearch(
        val total: Int,
        val data: List<AnimePaheSearchData>
    )


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api?m=search&l=8&q=$query"
        val searchHeaders = headers + mapOf("referer" to "$mainUrl/")
        val req = appGet(url, searchHeaders).text
        val data = tryParseJson<AnimePaheSearch>(req)

        return data?.data?.map {
            newAnimeSearchResponse(
                it.title,
                LoadData(it.session, unixTime, it.title).toJson(),
                fix = false
            ) {
                this.posterUrl = it.poster
                this.posterHeaders = cfHeaders
                addDubStatus(DubStatus.Subbed, it.episodes)
            }
        } ?: emptyList()
    }

    private data class AnimeData(
        val id: Int,
        @JsonProperty("anime_id") val animeId: Int,
        val episode: Int,
        val title: String,
        val snapshot: String,
        val session: String,
        val filler: Int,
        @JsonProperty("created_at") val createdAt: String
    )

    private data class AnimePaheAnimeData(
        val total: Int,
        @JsonProperty("per_page") val perPage: Int,
        @JsonProperty("current_page") val currentPage: Int,
        @JsonProperty("last_page") val lastPage: Int,
        @JsonProperty("next_page_url") val nextPageUrl: String?,
        @JsonProperty("prev_page_url") val prevPageUrl: String?,
        val from: Int,
        val to: Int,
        val data: List<AnimeData>
    )

    data class LinkLoadData(
        val mainUrl: String,
        @JsonProperty("is_play_page") val isPlayPage: Boolean,
        @JsonProperty("episode_num") val episodeNum: Int,
        val page: Int,
        val session: String,
        @JsonProperty("episode_session") val episodeSession: String,
    ) {
        private val headers = mapOf("Cookie" to "__ddg2_=1234567890")
        suspend fun getUrl(): String? {
            return if (isPlayPage) {
                "$mainUrl/play/${session}/${episodeSession}"
            } else {
                val url = "$mainUrl/api?m=release&id=${session}&sort=episode_asc&page=${page + 1}"
                val jsonResponse = appGet(url, headers).parsedSafe<AnimePaheAnimeData>() ?: return null
                val episode = jsonResponse.data.firstOrNull { it.episode == episodeNum }?.session
                    ?: return null
                "$mainUrl/play/${session}/${episode}"
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private suspend fun generateListOfEpisodes(
        session: String,
        metaEpisodes: Map<String, MetaEpisode>?
    ): ArrayList<Episode> {
        val episodes = ArrayList<Episode>()
        val semaphore = Semaphore(5) // Limit to 5 concurrent requests (adjust based on server capability)

        try {
            val uri = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=1"
            val req = appGet(uri, headers).text
            val data = tryParseJson<AnimePaheAnimeData>(req) ?: return arrayListOf()

            val lastPage = data.lastPage
            val perPage = data.perPage
            val total = data.total
            var currentEpisode = 1

            fun getEpisodeTitle(episodeData: AnimeData): String {
                return episodeData.title.ifEmpty { "Episode ${episodeData.episode}" }
            }
            // If only one page, process all episodes in that page
            if (lastPage == 1 && perPage > total) {
                data.data.forEach { episodeData ->
                    val epNum = episodeData.episode.toString()
                    val meta = metaEpisodes?.get(epNum)
                    episodes.add(
                        newEpisode(
                            LinkLoadData(
                                mainUrl,
                                true,
                                0,
                                0,
                                session,
                                episodeData.session
                            ).toJson()
                        ) {
                            addDate(episodeData.createdAt)
                            this.name = meta?.title?.get("en") ?: getEpisodeTitle(episodeData)
                            this.posterUrl = meta?.image ?: episodeData.snapshot
                            this.description = meta?.overview
                            this.score = Score.from10(meta?.rating)
                            this.runTime = meta?.runtime
                        }
                    )
                }
            } else {
                // Fetch multiple pages concurrently with limited threads
                val deferredResults = (1..lastPage).map { page ->
                    GlobalScope.async {
                        semaphore.withPermit {
                            try {
                                val pageUri = "$mainUrl/api?m=release&id=$session&sort=episode_asc&page=$page"
                                val pageReq = appGet(pageUri, headers).text
                                val pageData = tryParseJson<AnimePaheAnimeData>(pageReq)
                                pageData?.data?.map { episodeData ->
                                    val epNum = episodeData.episode.toString()
                                    val meta = metaEpisodes?.get(epNum)
                                    newEpisode(
                                        LinkLoadData(
                                            mainUrl,
                                            true,
                                            currentEpisode++,
                                            page,
                                            session,
                                            episodeData.session
                                        ).toJson()
                                    ) {
                                        addDate(episodeData.createdAt)
                                        this.name = meta?.title?.get("en") ?: getEpisodeTitle(episodeData)
                                        this.posterUrl = meta?.image ?: episodeData.snapshot
                                        this.description = meta?.overview
                                        this.score = Score.from10(meta?.rating)
                                        this.runTime = meta?.runtime
                                    }
                                } ?: emptyList<Episode>()
                            } catch (e: Exception) {
                                Log.e("generateListOfEpisodes", "Error on page $page: ${e.message}")
                                emptyList<Episode>()
                            }
                        }
                    }
                }

                // Wait for all pages to load and combine results
                episodes.addAll(deferredResults.awaitAll().flatten())
            }

        } catch (e: Exception) {
            Log.e("generateListOfEpisodes", "Error generating episodes: ${e.message}")
        }
        return episodes
    }
    /**
     * Required to make bookmarks work with a session system
     **/
    data class LoadData(val session: String, val sessionDate: Long, val name: String)

    override suspend fun load(url: String): LoadResponse? {
        return safeAsync {
            val session = tryParseJson<LoadData>(url)?.let { data ->
                // Outdated
                if (data.sessionDate + 60 * 10 < unixTime) {
                    tryParseJson<LoadData>(
                        search(data.name).firstOrNull()?.url ?: return@let null
                    )?.session
                } else {
                    data.session
                }
            } ?: return@safeAsync null
            val html = appGet("$mainUrl/anime/$session", headers).text
            val doc = Jsoup.parse(html)
            val japTitle = doc.selectFirst("h2.japanese")?.text()
            val animeTitle = doc.selectFirst("span.sr-only.unselectable")?.text()
            val poster = doc.selectFirst(".anime-poster a")?.attr("href")

            val tvType = doc.selectFirst("""a[href*="/anime/type/"]""")?.text()

            val recommendations = doc.select("div.anime-recommendation div.row").mapNotNull { it ->
                val title = it.select("a").attr("title")
                val rawHref = it.select("a").attr("href")

                val session = rawHref.substringAfter("/anime/", "")
                    .takeIf { it.isNotBlank() } ?: return@mapNotNull null

                val json = LoadData(
                    session = session,
                    name = title,
                    sessionDate = unixTime
                ).toJson()

                val posterurl = it.select("img").attr("data-src").ifEmpty {
                    it.select("img").attr("src")
                }

                newMovieSearchResponse(title, json, TvType.TvSeries) {
                    this.posterUrl = posterurl
                    this.posterHeaders = cfHeaders
                }
            }

            val year = Regex("""<strong>Aired:</strong>[^,]*, (\d+)""")
                .find(html)?.destructured?.component1()
                ?.toIntOrNull()

            val status =
                if (doc.selectFirst("a[href='/anime/airing']") != null)
                    ShowStatus.Ongoing
                else if (doc.selectFirst("a[href='/anime/completed']") != null)
                    ShowStatus.Completed
                else null

            val synopsis = doc.selectFirst(".anime-synopsis")?.text()

            var anilistId: Int? = null
            var malId: Int? = null

            doc.select(".external-links > a").forEach { aTag ->
                val split = aTag.attr("href").split("/")

                if (aTag.attr("href").contains("anilist.co")) {
                    anilistId = split[split.size - 1].toIntOrNull()
                } else if (aTag.attr("href").contains("myanimelist.net")) {
                    malId = split[split.size - 1].toIntOrNull()
                }
            }

            val syncMetaData = app.get("https://api.ani.zip/mappings?mal_id=$malId").text
            val animeMetaData = parseAnimeData(syncMetaData)
            val backgroundposter = animeMetaData?.images?.find { it.coverType == "Fanart" }?.url

            val episodes = generateListOfEpisodes(session, animeMetaData?.episodes)

            newAnimeLoadResponse(animeTitle ?: japTitle ?: "", url, getType(tvType.toString())) {
                engName = animeTitle
                japName = japTitle
                this.posterUrl = poster
                this.posterHeaders = cfHeaders
                this.backgroundPosterUrl = backgroundposter ?: poster
                this.year = year
                addEpisodes(DubStatus.Subbed, episodes)
                this.showStatus = status
                plot = synopsis
                tags = if (!doc.select(".anime-genre > ul a").isEmpty()) {
                    ArrayList(doc.select(".anime-genre > ul a").map { it.text() })
                } else {
                    null
                }
                this.recommendations = recommendations
                addMalId(malId)
                addAniListId(anilistId)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = tryParseJson<LinkLoadData>(data) ?: return false
        val episodeUrl = parsed.getUrl() ?: ""
        val document = appGet(episodeUrl, headers).document
        document.select("#resolutionMenu button")

            .forEach {
                val dubText = it.select("span").text().lowercase()
                val type = if ("eng" in dubText) "DUB" else "SUB"

                val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4}p)""")
                val text = it.text()
                val match = qualityRegex.find(text)
                val source = match?.groupValues?.getOrNull(1)?.trim() ?: "Unknown"
                val quality = match?.groupValues?.getOrNull(2)?.substringBefore("p")?.toIntOrNull()
                    ?: Qualities.Unknown.value

                val href = it.attr("data-src")
                if ("kwik" in href) {
                    loadCustomExtractor(
                        "Animepahe $source [$type]",
                        href,
                        mainUrl,
                        subtitleCallback,
                        callback,
                        quality
                    )
                }
            }


        document.select("div#pickDownload > a").amap {
            val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4}p)""")
            val href = it.attr("href")
            var type = "SUB"
            if(it.select("span").text().contains("eng"))
                type="DUB"
            val text = it.text()
            val match = qualityRegex.find(text)
            val source = match?.groupValues?.getOrNull(1) ?: "Unknown"
            val quality = match?.groupValues?.getOrNull(2)?.substringBefore("p") ?: "Unknown"

            loadCustomExtractor(
                "Animepahe Pahe $source [$type]",
                href,
                mainUrl,
                subtitleCallback,
                callback,
                quality.toIntOrNull()
            )
        }
        return true
    }
}
