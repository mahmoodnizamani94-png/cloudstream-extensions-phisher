package com.phisher98

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addKitsuId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.RequestBodyTypes
import com.phisher98.StreamPlay.Companion.anilistAPI
import com.phisher98.StreamPlayExtractor.invokeAnimePahe
import com.phisher98.StreamPlayExtractor.invokeAnimex
import com.phisher98.StreamPlayExtractor.invokeAniZone
import com.phisher98.StreamPlayExtractor.invokeCineby
import com.phisher98.StreamPlayExtractor.invokeCineSrc
import com.phisher98.StreamPlayExtractor.invokeMegaPlay
import com.phisher98.StreamPlayExtractor.invokeVidlink
import com.phisher98.StreamPlayExtractor.resolveTmdbIdByTitle
import com.phisher98.StreamPlayExtractor.resolveTmdbIdFromImdb
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar

class StreamPlayAnime : MainAPI() {
    override var name = "StreamPlay-Anime"
    override var mainUrl = "https://anilist.co"
    override var supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "en"
    override val supportedSyncNames = setOf(SyncIdName.Anilist, SyncIdName.MyAnimeList)
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val repo = SyncRepo(AccountManager.aniListApi)
    private val apiUrl = "https://graphql.anilist.co"
    private val mediaLimit = 20
    private val isAdult = false
    private val headerJSON =
        mapOf("Accept" to "application/json", "Content-Type" to "application/json")

    protected fun Any.toStringData(): String {
        return mapper.writeValueAsString(this)
    }

    private suspend fun anilistAPICall(query: String): AnilistAPIResponse {
        val data = mapOf("query" to query)
        val test = app.post(apiUrl, headers = headerJSON, data = data)
        return test.parsedSafe<AnilistAPIResponse>()
            ?: throw Exception("Unable to fetch or parse Anilist api response")
    }

    private fun Media.toSearchResponse(): SearchResponse {
        val title = this.title?.english ?: this.title?.romaji ?: ""
        val url = "$mainUrl/anime/${this.id}"
        val posterUrl = this.coverImage?.large ?: this.coverImage?.extraLarge ?: this.coverImage?.medium
        val rating = this.averageScore
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.score = Score.from100(rating)
        }
    }

    private suspend fun MainPageRequest.toSearchResponseList(
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val res = anilistAPICall(this.data.replace("###", "$page"))
        val data =
            res.data?.page?.media?.map { it.toSearchResponse() }
                ?: throw Exception("Unable to read media data")
        val hasNextPage = res.data.page.pageInfo?.hasNextPage ?: false
        return data to hasNextPage
    }

    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)

    private fun getCurrentSeason(): String {
        return when (Calendar.getInstance().get(Calendar.MONTH)) {
            Calendar.JANUARY, Calendar.FEBRUARY, Calendar.MARCH -> "WINTER"
            Calendar.APRIL, Calendar.MAY, Calendar.JUNE -> "SPRING"
            Calendar.JULY, Calendar.AUGUST, Calendar.SEPTEMBER -> "SUMMER"
            else -> "FALL"
        }
    }

    override val mainPage =
        mainPageOf(
            "query (\$page: Int = ###, \$sort: [MediaSort] = [TRENDING_DESC, POPULARITY_DESC], \$isAdult: Boolean = $isAdult) { Page(page: \$page, perPage: $mediaLimit) { pageInfo { total perPage currentPage lastPage hasNextPage } media(sort: \$sort, isAdult: \$isAdult, type: ANIME) { id idMal season seasonYear format episodes chapters averageScore title { english romaji } coverImage { extraLarge large medium } synonyms nextAiringEpisode { timeUntilAiring episode } } } }" to
                    "Trending Now",
            "query (\$page: Int = ###, \$seasonYear: Int = $currentYear, \$sort: [MediaSort] = [TRENDING_DESC, POPULARITY_DESC], \$isAdult: Boolean = $isAdult) { Page(page: \$page, perPage: $mediaLimit) { pageInfo { total perPage currentPage lastPage hasNextPage } media(sort: \$sort, seasonYear: \$seasonYear, season: ${getCurrentSeason()}, isAdult: \$isAdult, type: ANIME) { id idMal season seasonYear format episodes chapters averageScore title { english romaji } coverImage { extraLarge large medium } synonyms nextAiringEpisode { timeUntilAiring episode } } } }" to
                    "Popular This Season",
            "query (\$page: Int = ###, \$sort: [MediaSort] = [POPULARITY_DESC], \$isAdult: Boolean = $isAdult) { Page(page: \$page, perPage: $mediaLimit) { pageInfo { total perPage currentPage lastPage hasNextPage } media(sort: \$sort, isAdult: \$isAdult, type: ANIME) { id idMal season seasonYear format episodes chapters averageScore title { english romaji } coverImage { extraLarge large medium } synonyms nextAiringEpisode { timeUntilAiring episode } } } }" to
                    "All Time Popular",
            "query (\$page: Int = ###, \$sort: [MediaSort] = [SCORE_DESC], \$isAdult: Boolean = $isAdult) { Page(page: \$page, perPage: $mediaLimit) { pageInfo { total perPage currentPage lastPage hasNextPage } media(sort: \$sort, isAdult: \$isAdult, type: ANIME) { id idMal season seasonYear format episodes chapters averageScore title { english romaji } coverImage { extraLarge large medium } synonyms nextAiringEpisode { timeUntilAiring episode } } } }" to
                    "Top 100 Anime",
            "Personal" to "Personal"
        )

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cacheKey = "anime_search_${query.trim().lowercase()}_$page"
        StreamPlayCache.getCachedSearchResponse(cacheKey)?.let { return it }

        val results = SingleFlight.executeShared("anilist:search:$cacheKey") {
            val res = anilistAPICall(
                "query (\$search: String = \"$query\") { Page(page: $page, perPage: $mediaLimit) { pageInfo { total perPage currentPage lastPage hasNextPage } media(search: \$search, isAdult: $isAdult, type: ANIME) { id idMal season seasonYear format episodes chapters title { english romaji } coverImage { extraLarge large medium } synonyms nextAiringEpisode { timeUntilAiring episode } } } }"
            )
            res.data?.page?.media?.map { it.toSearchResponse() }?.toNewSearchResponseList()
        }
        if (results != null) {
            StreamPlayCache.cacheSearchResponse(cacheKey, results)
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("Personal")) {
            repo.authUser()
                ?: return newHomePageResponse(
                    "Login required for personal content.",
                    emptyList<SearchResponse>(),
                    false
                )
            val homePageList =
                repo.library().getOrThrow()!!.allLibraryLists.mapNotNull {
                    if (it.items.isEmpty()) return@mapNotNull null
                    val libraryName =
                        it.name.asString(activity ?: return@mapNotNull null)
                    HomePageList("${request.name}: $libraryName", it.items)
                }
            return newHomePageResponse(homePageList, false)
        } else {
            val data = request.toSearchResponseList(page)
            return newHomePageResponse(request.name, data.first, data.second)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.removeSuffix("/").substringAfterLast("/")
        val urduMode = UrduSubtitleEngine.isUrduMode(context = null, sharedPref = StreamPlay.companionSharedPref)
        val subtitleModeBadge = if (urduMode) {
            "🇵🇰 Subtitles: Urdu (اردو • SOTA)"
        } else {
            "🇬🇧 Subtitles: English (Default)"
        }
        val cacheKey = "anime_load_${id}_${if (urduMode) "urdu" else "en"}"
        StreamPlayCache.getCachedLoadResponse(cacheKey)?.let { return it }

        val response = SingleFlight.executeShared("anilist:load:$cacheKey") {
            val data = anilistAPICall(
                "query (\$id: Int = $id) { Media(id: \$id, type: ANIME) { id idMal title { romaji english native } synonyms startDate { year } genres description averageScore status bannerImage coverImage { extraLarge large medium } episodes format nextAiringEpisode { episode } airingSchedule { nodes { episode } } recommendations { edges { node { id mediaRecommendation { id title { romaji english } coverImage { extraLarge large medium } } } } } } }"
            ).data?.media ?: throw Exception("Unable to fetch media details")

            val anititle = data.getTitle()
            val aniyear = data.startDate?.year
            val posterurl = data.coverImage?.extraLarge ?: data.coverImage?.large ?: data.coverImage?.medium
            val backgroundUrl = data.bannerImage
            val formatStr = data.format.orEmpty()
            val isMovie = formatStr.contains("MOVIE", ignoreCase = true)

            val anitype = if (isMovie) TvType.AnimeMovie else TvType.TvSeries
            val ids = if (data.idMal != null) {
                AniIds(data.id, data.idMal)
            } else {
                val resolved = tmdbToAnimeId(anititle, aniyear, anitype)
                AniIds(resolved.id ?: data.id, resolved.idMal)
            }

            val jpTitle = data.title?.romaji
            // Subtitle catalogues index anime under different names. Keep every name AniList
            // knows (English, Romaji, native, synonyms) so the subtitle harvester can try the
            // English release title first and still fall back to a localized query.
            val subtitleTitles = listOfNotNull(
                anititle,
                data.title?.english,
                jpTitle,
                data.title?.native,
            ) + data.synonyms.orEmpty()
            val cleanSubtitleTitles = subtitleTitles
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }

            val aniZipKey = "anizip_${ids.id ?: id}"
            val syncMetaData = StreamPlayCache.getCachedAniZip(aniZipKey) ?: run {
                val byAni = runCatching {
                    app.get("https://api.ani.zip/mappings?anilist_id=${ids.id ?: id}").text
                }.getOrNull().orEmpty()
                val fetched = if (byAni.isNotBlank() && byAni.contains("\"mappings\"")) {
                    byAni
                } else if (ids.idMal != null) {
                    runCatching {
                        app.get("https://api.ani.zip/mappings?mal_id=${ids.idMal}").text
                    }.getOrNull().orEmpty().ifBlank { byAni }
                } else {
                    byAni
                }
                if (fetched.isNotBlank() && fetched.startsWith("{")) {
                    StreamPlayCache.cacheAniZip(aniZipKey, fetched)
                }
                fetched
            }
            val animeMetaData = parseAnimeData(syncMetaData)
            val rawImdbId = animeMetaData?.mappings?.imdbId?.trim()?.takeIf { it.isNotBlank() }
            val kitsuid = animeMetaData?.mappings?.kitsuid
            val resolvedMalId = ids.idMal ?: animeMetaData?.mappings?.malId
            val resolvedAniId = ids.id ?: animeMetaData?.mappings?.anilistId ?: id.toIntOrNull()

            val tmdbid = animeMetaData?.mappings?.themoviedbId?.toIntOrNull()
                ?: rawImdbId?.let { resolveTmdbIdFromImdb(it, if (isMovie) null else 1) }
                ?: resolveTmdbIdByTitle(anititle, aniyear, isMovie)
                ?: resolveTmdbIdByTitle(jpTitle, aniyear, isMovie)

            val imdbid = rawImdbId ?: tmdbid?.let { StreamPlayExtractor.resolveImdbIdFromTmdb(it, isMovie) }

            val type = if (isMovie) TvType.Movie else TvType.TvSeries

            val logoUrl = fetchTmdbLogoUrl(
                tmdbAPI = "https://api.themoviedb.org/3",
                apiKey = "98ae14df2b8d8f8f8136499daf79f0e0",
                type = type,
                tmdbId = tmdbid,
                appLangCode = "en"
            )

            val aniZipEpCount = animeMetaData?.episodeCount?.takeIf { it > 0 }
                ?: animeMetaData?.episodes?.keys?.mapNotNull { it.toIntOrNull() }?.maxOrNull()
                ?: 0
            val totalEps = data.totalEpisodes().takeIf { it > 0 }
                ?: aniZipEpCount.takeIf { it > 0 }
                ?: if (isMovie) 1 else 0

            val anidbEidMap: Map<Int, Int> = (1..totalEps).associateWith { getAnidbEid(syncMetaData, it) ?: 0 }
            val epMetaMap: Map<Int, MetaEpisode?> = (1..totalEps).associateWith { animeMetaData?.episodes?.get(it.toString()) }

            val fallbackPoster = animeMetaData?.images?.firstOrNull()?.url ?: posterurl.orEmpty()
            val fallbackTitle = animeMetaData?.titles?.get("en")
                ?: animeMetaData?.titles?.get("ja")
                ?: animeMetaData?.titles?.get("x-jat")
                ?: anititle

            fun resolveTitle(epNum: Int, epData: MetaEpisode?): String {
                val jsonTitle = epData?.title?.get("en")
                    ?: epData?.title?.get("ja")
                    ?: epData?.title?.get("x-jat")
                return if (!jsonTitle.isNullOrBlank()) jsonTitle else "Episode ${epData?.episode ?: epNum}"
            }

            val tmdbEpMappings = if (isMovie) {
                emptyMap()
            } else {
                StreamPlayExtractor.resolveAccurateTmdbSeriesMapping(
                    tmdbId = tmdbid,
                    aniTitle = anititle,
                    jpTitle = jpTitle,
                    aniYear = aniyear,
                    totalEps = totalEps,
                    epMetaMap = epMetaMap
                )
            }

            val anidbEid = anidbEidMap[1] ?: 0
            val firstVerifiedTmdb = tmdbEpMappings[1]
            val href = LinkData(
                malId = resolvedMalId,
                aniId = resolvedAniId,
                tmdbId = tmdbid,
                imdbId = imdbid,
                title = anititle,
                jpTitle = jpTitle,
                alternativeTitles = cleanSubtitleTitles,
                year = aniyear,
                isAnime = true,
                anidbEid = anidbEid,
                season = if (isMovie) null else 1,
                episode = 1,
                tmdbSeason = if (isMovie) null else (firstVerifiedTmdb?.first ?: -1),
                tmdbEpisode = if (isMovie) 1 else (firstVerifiedTmdb?.second ?: -1)
            ).toStringData()

            val episodes = (1..totalEps).map { i ->
                val epData = epMetaMap[i]
                val verifiedTmdb = tmdbEpMappings[i]
                val tmdbSeason = verifiedTmdb?.first ?: -1
                val tmdbEpisode = verifiedTmdb?.second ?: -1
                val linkData = LinkData(
                    malId = resolvedMalId,
                    aniId = resolvedAniId,
                    tmdbId = tmdbid,
                    imdbId = imdbid,
                    title = anititle,
                    jpTitle = jpTitle,
                    alternativeTitles = cleanSubtitleTitles,
                    year = aniyear,
                    season = 1,
                    episode = i,
                    tmdbSeason = tmdbSeason,
                    tmdbEpisode = tmdbEpisode,
                    isAnime = true,
                    isDub = false,
                    anidbEid = anidbEidMap[i] ?: 0
                ).toStringData()

                newEpisode(linkData) {
                    this.season = 1
                    this.episode = i
                    this.name = resolveTitle(i, epData)
                    this.posterUrl = epData?.image ?: fallbackPoster
                    this.description = epData?.overview ?: "No summary available"
                    this.score = Score.from10(epData?.rating)
                    this.runTime = epData?.runtime
                    this.addDate(epData?.airdate ?: epData?.airDate)
                }
            }

            val episodesDub = episodes.mapIndexed { idx, ep ->
                val i = idx + 1
                val verifiedTmdb = tmdbEpMappings[i]
                val tmdbSeason = verifiedTmdb?.first ?: -1
                val tmdbEpisode = verifiedTmdb?.second ?: -1
                val dubLinkData = LinkData(
                    malId = resolvedMalId,
                    aniId = resolvedAniId,
                    tmdbId = tmdbid,
                    imdbId = imdbid,
                    title = anititle,
                    jpTitle = jpTitle,
                    alternativeTitles = cleanSubtitleTitles,
                    year = aniyear,
                    season = 1,
                    episode = i,
                    tmdbSeason = tmdbSeason,
                    tmdbEpisode = tmdbEpisode,
                    isAnime = true,
                    isDub = true,
                    anidbEid = anidbEidMap[i] ?: 0
                ).toStringData()
                ep.copy(data = dubLinkData)
            }

            val hasReleasedYear = aniyear != null && aniyear < java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val isUnreleased = data.status.equals("NOT_YET_RELEASED", ignoreCase = true) &&
                !hasReleasedYear &&
                animeMetaData?.episodes.isNullOrEmpty()

            if (urduMode) {
                UrduSubtitleEngine.prewarmUrduSubtitlesAsync(
                    imdbId = imdbid,
                    title = anititle.ifBlank { fallbackTitle },
                    season = if (isMovie) null else (firstVerifiedTmdb?.first?.takeIf { it > 0 } ?: 1),
                    episode = if (isMovie) null else (firstVerifiedTmdb?.second?.takeIf { it > 0 } ?: 1),
                    year = aniyear,
                    isMovie = isMovie,
                    sharedPref = StreamPlay.companionSharedPref
                )
            }

            val enrichedAnimeTags = buildList {
                add(subtitleModeBadge)
                addAll(data.genres.orEmpty())
            }

            if (isMovie) {
                newMovieLoadResponse(anititle, url, TvType.AnimeMovie, href) {
                    resolvedAniId?.let { addAniListId(it) }
                    addMalId(resolvedMalId)
                    try { addKitsuId(kitsuid) } catch (_: Throwable) {}
                    this.comingSoon = isUnreleased
                    this.year = aniyear
                    this.plot = data.description
                    this.backgroundPosterUrl = backgroundUrl ?: animeMetaData?.images?.firstOrNull { it.coverType == "Fanart" }?.url ?: data.bannerImage
                    this.posterUrl = posterurl ?: data.getCoverImage() ?: animeMetaData?.images
                        ?.firstOrNull { it.coverType.equals("Poster", ignoreCase = true) }?.url
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    this.tags = enrichedAnimeTags
                    this.score = Score.from100(data.averageScore)
                }
            } else {
                newAnimeLoadResponse(anititle, url, TvType.Anime) {
                    resolvedAniId?.let { addAniListId(it) }
                    addMalId(resolvedMalId)
                    try { addKitsuId(kitsuid) } catch (_: Throwable) {}
                    addEpisodes(DubStatus.Subbed, episodes)
                    addEpisodes(DubStatus.Dubbed, episodesDub)
                    this.comingSoon = if (episodes.isNotEmpty()) false else isUnreleased
                    try { this.logoUrl = logoUrl } catch (_: Throwable) {}
                    this.year = aniyear
                    this.plot = data.description
                    this.backgroundPosterUrl = animeMetaData?.images?.firstOrNull { it.coverType == "Fanart" }?.url ?: data.bannerImage
                    this.posterUrl = data.getCoverImage() ?: animeMetaData?.images
                        ?.firstOrNull { it.coverType.equals("Poster", ignoreCase = true) }?.url
                    this.tags = enrichedAnimeTags
                    this.score = Score.from100(data.averageScore)
                    this.showStatus = getStatus(data.status)
                    this.recommendations = data.recommendations?.edges
                        ?.mapNotNull { edge ->
                            val recommendation = edge.node?.mediaRecommendation ?: return@mapNotNull null
                            val recId = recommendation.id ?: return@mapNotNull null
                            val title = recommendation.title?.english ?: recommendation.title?.romaji ?: "Unknown"
                            newAnimeSearchResponse(title, "$mainUrl/anime/$recId", TvType.Anime).apply {
                                this.posterUrl = recommendation.coverImage?.large ?: recommendation.coverImage?.extraLarge
                            }
                        }
                }
            }
        }
        StreamPlayCache.cacheLoadResponse(cacheKey, response)
        return response
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val mediaData = AppUtils.parseJson<LinkData>(data)
        val malId = mediaData.malId
        val episode = mediaData.episode
        val jpTitle = mediaData.jpTitle
        val anititle = mediaData.title
        val aniid = mediaData.aniId
        val year = mediaData.year
        val isMovie = mediaData.season == null

        val effectiveTmdbId = mediaData.tmdbId
            ?: mediaData.imdbId?.let { resolveTmdbIdFromImdb(it, if (isMovie) null else 1) }
            ?: resolveTmdbIdByTitle(anititle, year, isMovie)
            ?: resolveTmdbIdByTitle(jpTitle, year, isMovie)

        val verifiedTmdbPair = if (isMovie) {
            null
        } else {
            StreamPlayExtractor.resolveVerifiedTmdbEpisodeForAnime(
                tmdbId = effectiveTmdbId,
                aniId = aniid,
                malId = malId,
                aniTitle = anititle,
                jpTitle = jpTitle,
                aniYear = year,
                courEpisode = episode,
                rawTmdbSeason = mediaData.tmdbSeason,
                rawTmdbEpisode = mediaData.tmdbEpisode
            )
        }
        val tmdbSeason = if (isMovie) null else verifiedTmdbPair?.first
        val tmdbEpisode = if (isMovie) null else verifiedTmdbPair?.second
        val canUseTmdbProviders = isMovie || (tmdbSeason != null && tmdbSeason > 0 && tmdbEpisode != null && tmdbEpisode > 0)

        val animeTopRanks = setOf(100, 95, 94, 92, 89, 88, 87)
        val runningTopRanks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().apply {
            addAll(animeTopRanks)
        }
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = callback,
            scope = this,
            topSourceGraceMs = 2_000L,
            top720GraceMs = 1_100L,
            activeTopRanks = animeTopRanks,
            isRankInFlight = { rank -> runningTopRanks.contains(rank) },
            isAnime = true
        )
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator(
            upstreamCallback = { link -> dispatcher.onLinkAccepted(link) },
            onUpgradeCallback = { link -> dispatcher.onLinkAccepted(link) },
            isAnime = true
        )
        val topTierDualGuardedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        // Real bookkeeping behind the anime 6-source English subtitle contract (SubtitleSourceLedger):
        // distinct provenances count toward diversity, container providers that delivered are
        // suppressed from re-querying, and `lang|canonical-url` (fragment-stripped) deduplication
        // keeps one tracking entry per sidecar.
        val subtitleLedger = SubtitleSourceLedger(
            targetEnglishSources = ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES
        )
        val englishSubtitleCount = java.util.concurrent.atomic.AtomicInteger(0)
        val urduMode = UrduSubtitleEngine.isUrduMode(context = null, sharedPref = StreamPlay.companionSharedPref)
        val englishDonorSubs = java.util.concurrent.CopyOnWriteArrayList<SubtitleFile>()

        val dubStatus: String =
            if (isMovie) "Movie"
            else if (mediaData.isDub) "DUB"
            else "SUB"

        val earlySatisfactionConfig = EarlySatisfactionConfig(
            minVerifiedLinks = 10,
            minQualityStreams = 4,
            qualityThreshold = Qualities.P720.value,
            highBitrateThresholdKbps = 2500,
            minSubtitles = ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES,
            satisfyWithOneLinkIfSubsFound = false,
            requireSubtitles = true,
            requireDualQualities = false,
            require720p = false,
            adaptiveTierEscalation = true,
            softGracePeriodAfterFirstLinkMs = 0L,
            maxPipelineTimeoutMs = 18_000L,
            postSatisfactionGraceMs = if (urduMode) 2_500L else 7_000L
        )
        val earlyController = EarlySatisfactionController(earlySatisfactionConfig)

        val animeLinksFound = java.util.concurrent.atomic.AtomicInteger(0)
        fun emitSingleOptimized(link: ExtractorLink) {
            val taggedLink = if (link.headers["X-StreamPlay-Anime"]?.equals("true", ignoreCase = true) != true) {
                @Suppress("DEPRECATION")
                ExtractorLink(
                    source = link.source,
                    name = link.name,
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality,
                    type = link.type,
                    headers = link.headers + ("X-StreamPlay-Anime" to "true"),
                    extractorData = link.extractorData
                )
            } else {
                link
            }
            val optimized = StreamLinkOptimizer.optimize(taggedLink)
            earlyController.onCandidateLink(optimized)
            when (deduplicator.emitDetailed(optimized)) {
                StreamLinkOptimizer.DeduplicationResult.NEW -> {
                    animeLinksFound.incrementAndGet()
                    earlyController.onLinkEmitted(optimized)
                }
                StreamLinkOptimizer.DeduplicationResult.UPGRADED -> {
                    earlyController.onLinkUpgraded(optimized)
                }
                StreamLinkOptimizer.DeduplicationResult.DROPPED -> {}
            }
        }

        val trackedCallback: (ExtractorLink) -> Unit = { link ->
            StreamLinkOptimizer.processTopTierDualQualityStream(link, topTierDualGuardedKeys) { single ->
                emitSingleOptimized(single)
            }
        }
        /**
         * Builds the subtitle sink for one source, so the provenance of every emitted track stays
         * known (a URL alone cannot say which provider surfaced it).
         */
        fun subtitleSinkFor(sourceKey: String): (SubtitleFile) -> Unit = sink@{ sub ->
            val rawUrl = sub.url.trim()
            val normalizedUrl = when {
                rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true) ->
                    StreamPlayShield.stripTrackingParticles(rawUrl.replace(" ", "%20"))
                rawUrl.startsWith("file:", ignoreCase = true) ->
                    "file:///" + rawUrl.removePrefix("file:").removePrefix("FILE:").trimStart('/')
                rawUrl.startsWith("//", ignoreCase = true) ->
                    StreamPlayShield.stripTrackingParticles("https:${rawUrl.replace(" ", "%20")}")
                else -> return@sink
            }
            if (!normalizedUrl.startsWith("file://", ignoreCase = true) && StreamPlayShield.isBlockedUrl(normalizedUrl)) {
                StreamPlayShield.countEmissionBlocked()
                Log.d("StreamPlayShield", "\uD83D\uDEE1 dropped blocked subtitle URL via ${StreamPlayShield.hostOf(normalizedUrl)}")
                return@sink
            }

            val cleanedLang = cleanSubtitleLabel(sub.lang)
            val explicitProvenance = sub.headers?.entries
                ?.firstOrNull { it.key.equals("X-StreamPlay-Source", ignoreCase = true) }
                ?.value?.trim()?.takeIf { it.isNotBlank() }
            val provenance = explicitProvenance ?: sourceKey.ifBlank { StreamPlayShield.hostOf(normalizedUrl) }

            if (!urduMode) {
                val cleanedLang = cleanSubtitleLabel(sub.lang)
                if (subtitleLedger.markEmitted(cleanedLang, normalizedUrl)) {
                    if (isEnglishSubtitleLabel(cleanedLang)) {
                        englishSubtitleCount.incrementAndGet()
                        subtitleLedger.recordEnglishDelivery(provenance, sourceKey)
                    }
                    @Suppress("DEPRECATION")
                    val shieldedSubtitle = SubtitleFile(lang = cleanedLang, url = normalizedUrl).apply {
                        // Preserve the provider identity through shared CDN hosts. The app uses
                        // this for five-source download diversity and sidecar labels.
                        headers = (sub.headers ?: emptyMap()) +
                            ("X-StreamPlay-Source" to provenance)
                    }
                    earlyController.onSubtitleEmitted(shieldedSubtitle)
                    subtitleCallback(shieldedSubtitle)
                    dispatcher.onSubtitleReceived()
                }
            } else {
                when {
                    isUrduSubtitleLabel(cleanedLang) -> {
                        val canonicalUrduSource = UrduSubtitleEngine.canonicalUrduSourceKey(provenance)
                            ?: UrduSubtitleEngine.canonicalUrduSourceKey(cleanedLang)
                        if (canonicalUrduSource != null) {
                            val formattedLang = UrduSubtitleEngine.formatUrduSourceLabel(canonicalUrduSource)
                            if (subtitleLedger.markEmitted(formattedLang, normalizedUrl)) {
                                subtitleLedger.recordUrduDelivery(canonicalUrduSource, sourceKey)
                                @Suppress("DEPRECATION")
                                val urSub = SubtitleFile(lang = formattedLang, url = normalizedUrl).apply {
                                    headers = (sub.headers ?: emptyMap()) +
                                        ("X-StreamPlay-Source" to canonicalUrduSource)
                                }
                                earlyController.onSubtitleEmitted(urSub)
                                subtitleCallback(urSub)
                                dispatcher.onSubtitleReceived()
                            }
                        }
                    }
                    isEnglishSubtitleLabel(cleanedLang) -> {
                        if (subtitleLedger.markEmitted(cleanedLang, normalizedUrl)) {
                            englishSubtitleCount.incrementAndGet()
                            subtitleLedger.recordEnglishDelivery(provenance, sourceKey)
                            @Suppress("DEPRECATION")
                            val donorSub = SubtitleFile(lang = cleanedLang, url = normalizedUrl).apply {
                                headers = (sub.headers ?: emptyMap()) +
                                    ("X-StreamPlay-Source" to provenance)
                            }
                            englishDonorSubs.add(donorSub)
                        }
                    }
                }
            }
        }

        /**
         * One SOTA anime source: the id/rank the pipeliner orders and tracks it by, plus how to
         * invoke it. Declared once so the main sweep and the subtitle completion pass can never
         * drift apart.
         */
        data class AnimeSource(
            val id: String,
            val boost: Float,
            /** Rank registered with the priority dispatcher, or null for subtitle-only sources. */
            val topRank: Int? = null,
            val invoke: suspend (
                onSubtitle: (SubtitleFile) -> Unit,
                onLink: (ExtractorLink) -> Unit
            ) -> Unit
        )

        val animeImdbId = mediaData.imdbId?.trim()?.takeIf { it.isNotBlank() }
            ?: effectiveTmdbId?.let { StreamPlayExtractor.resolveImdbIdFromTmdb(it, isMovie) }
        val subLookupId = animeImdbId ?: effectiveTmdbId?.toString()

        // Every name the show is released under. AnimePahe and AniZone are searched by title, and
        // a currently airing or localized show is frequently indexed under a name that is neither
        // the TMDB title nor the Romaji one.
        val animeTitleQueries = (listOfNotNull(anititle, jpTitle, mediaData.orgTitle) +
            mediaData.alternativeTitles.orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        val videoSources = listOf(
            AnimeSource("vidlink", 100f, topRank = 100) { onSubtitle, onLink ->
                if (canUseTmdbProviders && (effectiveTmdbId != null || !animeImdbId.isNullOrBlank())) {
                    invokeVidlink(
                        tmdbId = effectiveTmdbId,
                        season = tmdbSeason,
                        episode = tmdbEpisode,
                        subtitleCallback = onSubtitle,
                        callback = onLink,
                        imdbId = animeImdbId,
                        isAnime = true
                    )
                }
            },
            AnimeSource("cineby", 95f, topRank = 95) { onSubtitle, onLink ->
                if (canUseTmdbProviders && (effectiveTmdbId != null || !animeImdbId.isNullOrBlank())) {
                    invokeCineby(
                        tmdbId = effectiveTmdbId,
                        season = tmdbSeason,
                        episode = tmdbEpisode,
                        subtitleCallback = onSubtitle,
                        callback = onLink,
                        imdbId = animeImdbId,
                        title = anititle ?: jpTitle,
                        year = year,
                        isAnime = true
                    )
                }
            },
            AnimeSource("cinesrc", 94f, topRank = 94) { onSubtitle, onLink ->
                if (canUseTmdbProviders && (effectiveTmdbId != null || !animeImdbId.isNullOrBlank())) {
                    invokeCineSrc(
                        tmdbId = effectiveTmdbId,
                        season = tmdbSeason,
                        episode = tmdbEpisode,
                        subtitleCallback = onSubtitle,
                        callback = onLink,
                        imdbId = animeImdbId,
                        title = anititle ?: jpTitle,
                        year = year,
                        isAnime = true
                    )
                }
            },
            AnimeSource("megaplay", 92f, topRank = 92) { onSubtitle, onLink ->
                invokeMegaPlay(malId, aniid, episode, onSubtitle, onLink, dubStatus)
            },
            // AniZone is the subtitle anchor of the anime chain: its episode pack carries a
            // 360/720/1080 ladder with demuxed Japanese audio plus real English SRT/ASS tracks, so
            // both playback and download keep the original audio and a usable subtitle.
            AnimeSource("anizone", 89f, topRank = 89) { onSubtitle, onLink ->
                invokeAniZone(
                    titles = animeTitleQueries,
                    year = year,
                    episode = episode,
                    isMovie = isMovie,
                    dubtype = dubStatus,
                    subtitleCallback = onSubtitle,
                    callback = onLink
                )
            },
            // AnimePahe is the coverage source: currently airing episodes, very old shows and the
            // long tail the TMDB/AniList-mapped sources never index.
            AnimeSource("animepahe", 88f, topRank = 88) { onSubtitle, onLink ->
                invokeAnimePahe(
                    titles = animeTitleQueries,
                    year = year,
                    episode = episode,
                    isMovie = isMovie,
                    dubtype = dubStatus,
                    subtitleCallback = onSubtitle,
                    callback = onLink
                )
            },
            AnimeSource("animex", 87f, topRank = 87) { onSubtitle, onLink ->
                invokeAnimex(
                    malId,
                    aniid,
                    anititle ?: jpTitle,
                    episode,
                    onSubtitle,
                    onLink,
                    dubStatus,
                    jpTitle = jpTitle
                )
            }
        )

        // Dedicated anime soft-sub harvest sources. Generic movie/TV subtitle catalogues
        // (SubtitleCat, Addic7ed, WyZIESUB, SubtitleAPI, OpenSubtitles) are intentionally excluded
        // on anime in Default mode because they serve machine-translated, TV-broadcast-cut, or
        // wrong-episode tracks that desynchronize from Japanese web-rip/CR masters. Instead,
        // KickAssAnime (VidStreaming + BirdStream soft-sub VTTs) runs alongside AniZone, AnimePahe,
        // MegaPlay, Animex/ZokoAnime, and verified CineSrc/Cineby/VidLink tracks.
        // In Urdu mode, we also schedule SubDL Urdu and SubSense Urdu so human Urdu anime tracks
        // are harvested before falling back to spoken-Urdu neural translation of the anime English sidecars.
        val primaryAnimeTitle = anititle ?: jpTitle ?: mediaData.orgTitle
        val effectiveSubSeason = tmdbSeason ?: if (isMovie) null else 1
        val effectiveSubEpisode = tmdbEpisode ?: episode
        val subtitleOnlySources = buildList {
            if (animeTitleQueries.isNotEmpty()) {
                add(
                    AnimeSource("kickassanime", 90f) { onSubtitle, _ ->
                        StreamPlayExtractor.invokeKickAssAnime(
                            titles = animeTitleQueries,
                            year = year,
                            episode = episode,
                            isMovie = isMovie,
                            dubtype = dubStatus,
                            subtitleCallback = onSubtitle
                        )
                    }
                )
            }
            if (urduMode) {
                if (!subLookupId.isNullOrBlank()) {
                    add(
                        AnimeSource("wyziesub", 99f) { onSubtitle, _ ->
                            StreamPlayExtractor.invokeWYZIESubs(
                                id = subLookupId,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                }
                if (!animeImdbId.isNullOrBlank()) {
                    add(
                        AnimeSource("subtitles", 98f) { onSubtitle, _ ->
                            StreamPlayExtractor.invokeSubtitleAPI(
                                id = animeImdbId,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                    add(
                        AnimeSource("subsense", 96f) { onSubtitle, _ ->
                            UrduSubtitleEngine.invokeSubSenseUrdu(
                                imdbId = animeImdbId,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                    if (isMovie) {
                        add(
                            AnimeSource("yifysub", 96f) { onSubtitle, _ ->
                                StreamPlayExtractor.invokeYifySubs(
                                    imdbId = animeImdbId,
                                    subtitleCallback = onSubtitle
                                )
                            }
                        )
                    }
                }
                if (!animeImdbId.isNullOrBlank() || animeTitleQueries.isNotEmpty()) {
                    add(
                        AnimeSource("opensubtitles", 97f) { onSubtitle, _ ->
                            StreamPlayExtractor.invokeOpenSubtitlesLegacy(
                                id = animeImdbId,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                title = animeTitleQueries.firstOrNull(),
                                titles = animeTitleQueries.drop(1),
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                }
                if (!animeImdbId.isNullOrBlank() || !primaryAnimeTitle.isNullOrBlank()) {
                    add(
                        AnimeSource("subdl", 96f) { onSubtitle, _ ->
                            UrduSubtitleEngine.invokeSubDlUrduKeyless(
                                imdbId = animeImdbId,
                                title = primaryAnimeTitle,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                year = year,
                                sharedPref = StreamPlay.companionSharedPref,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                    add(
                        AnimeSource("subsource", 96f) { onSubtitle, _ ->
                            UrduSubtitleEngine.invokeSubSourceUrdu(
                                imdbId = animeImdbId,
                                title = primaryAnimeTitle,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                year = year,
                                isMovie = isMovie,
                                sharedPref = StreamPlay.companionSharedPref,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                }
                if (!primaryAnimeTitle.isNullOrBlank()) {
                    add(
                        AnimeSource("subf2m", 95f) { onSubtitle, _ ->
                            UrduSubtitleEngine.invokeSubf2mUrdu(
                                title = primaryAnimeTitle,
                                year = year,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                isMovie = isMovie,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                }
                if (animeTitleQueries.isNotEmpty()) {
                    add(
                        AnimeSource("subtitlecat", 95f) { onSubtitle, _ ->
                            StreamPlayExtractor.invokeSubtitleCat(
                                titles = animeTitleQueries,
                                season = effectiveSubSeason,
                                episode = effectiveSubEpisode,
                                year = year,
                                isMovie = isMovie,
                                isAnime = true,
                                imdbId = animeImdbId,
                                subtitleCallback = onSubtitle
                            )
                        }
                    )
                }
            }
        }

        fun taskFor(source: AnimeSource) = PipelinedTask(
            providerId = source.id,
            initialTier = LatencyTier.TIER_1,
            isVideo = source.topRank != null,
            // Multi-step subtitle catalogues (Addic7ed, SubtitleCat) cannot finish inside the old
            // 10s cap when a search is followed by a detail page. Use the same 16s budget as the
            // non-anime completion phase so anime gets the same fallback coverage.
            taskTimeoutMs = if (source.topRank != null) null else SUBTITLE_COMPLETION_TASK_TIMEOUT_MS,
            priorityBoost = source.boost
        ) {
            try {
                source.invoke(subtitleSinkFor(source.id), trackedCallback)
            } finally {
                source.topRank?.let { rank ->
                    runningTopRanks.remove(rank)
                    dispatcher.markRankCompleted(rank)
                }
            }
        }

        val tasks = (videoSources + subtitleOnlySources).map { taskFor(it) }

        // Verified human Urdu archives exclude the neural fallback and the SubtitleCat chain;
        // the ledger stores canonical Urdu source keys (e.g. "yify" for the yifysub task).
        fun countVerifiedHumanUrduSources(): Int =
            subtitleLedger.urduSubtitleSources.count { it != "urdu-neural-fallback" && it != "subtitlecat" }

        try {
            SpeculativePipeliner.executePipelined(
                tasks = tasks,
                config = earlySatisfactionConfig,
                controller = earlyController
            )

            // Subtitle completion. The app keeps one English subtitle per source so at least one is
            // in sync with the release that was downloaded, and anime must keep its original audio
            // with subtitles. When the sweep was satisfied before enough distinct sources spoke, the
            // remaining ones are asked again — subtitle-first, but with the normal emission paths, so
            // a source that also has a link to offer still contributes it.
            val humanUrduBeforeCompletion = countVerifiedHumanUrduSources()
            if (!urduMode && subtitleLedger.needsEnglishCompletion()) {
                val completionSources = (videoSources + subtitleOnlySources)
                    .filter { source ->
                        !subtitleLedger.isEnglishSource(source.id) && !subtitleLedger.isEnglishProviderCompleted(source.id)
                    }
                    .take(MAX_SUBTITLE_COMPLETION_PROVIDERS)

                if (completionSources.isNotEmpty()) {
                    Log.d(
                        "StreamPlayAnime",
                        "📝 Anime subtitle completion: ${subtitleLedger.englishSubtitleSources.size}/$ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES " +
                            "English sources; querying ${completionSources.size} more " +
                            "(${completionSources.joinToString { it.id }})"
                    )

                    val completionConfig = EarlySatisfactionConfig(
                        // Link/quality satisfaction is deliberately unreachable here: the phase ends
                        // when enough distinct English subtitle sources have answered, or on timeout.
                        minVerifiedLinks = Int.MAX_VALUE,
                        minQualityStreams = Int.MAX_VALUE,
                        minSubtitles = 1,
                        requireSubtitles = false,
                        require720p = false,
                        requireDualQualities = false,
                        satisfyWithOneLinkIfSubsFound = false,
                        tier1DelayMs = 0L,
                        tier2DelayMs = 150L,
                        tier3DelayMs = 500L,
                        adaptiveTierEscalation = false,
                        maxPipelineTimeoutMs = SUBTITLE_COMPLETION_TIMEOUT_MS,
                        postSatisfactionGraceMs = 0L
                    )
                    val completionController = EarlySatisfactionController(
                        completionConfig,
                        extraCondition = { subtitleLedger.englishSubtitleSources.size >= ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES }
                    )

                    SpeculativePipeliner.executePipelined(
                        tasks = completionSources.map { taskFor(it) },
                        config = completionConfig,
                        controller = completionController
                    )

                    Log.d(
                        "StreamPlayAnime",
                        "📝 Anime subtitle completion finished: ${englishSubtitleCount.get()} English " +
                            "subtitles from ${subtitleLedger.englishSubtitleSources.size} sources"
                    )
                }
            } else if (urduMode && humanUrduBeforeCompletion < UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES) {
                val retryUrduSources = subtitleOnlySources.filter { source ->
                    source.id != "kickassanime" &&
                        source.id != "subtitlecat" &&
                        !subtitleLedger.isUrduSource(source.id) &&
                        !subtitleLedger.isUrduProviderCompleted(source.id)
                }
                if (retryUrduSources.isNotEmpty()) {
                    val urduCompletionConfig = EarlySatisfactionConfig(
                        minVerifiedLinks = Int.MAX_VALUE,
                        minQualityStreams = Int.MAX_VALUE,
                        minSubtitles = 1,
                        requireSubtitles = false,
                        require720p = false,
                        requireDualQualities = false,
                        satisfyWithOneLinkIfSubsFound = false,
                        tier1DelayMs = 0L,
                        tier2DelayMs = 150L,
                        tier3DelayMs = 500L,
                        adaptiveTierEscalation = false,
                        maxPipelineTimeoutMs = 4_500L,
                        postSatisfactionGraceMs = 0L
                    )
                    val urduCompletionController = EarlySatisfactionController(
                        urduCompletionConfig,
                        extraCondition = { countVerifiedHumanUrduSources() >= UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES }
                    )
                    SpeculativePipeliner.executePipelined(
                        tasks = retryUrduSources.map { taskFor(it).copy(taskTimeoutMs = 4_500L) },
                        config = urduCompletionConfig,
                        controller = urduCompletionController
                    )
                }
            }

            if (urduMode && countVerifiedHumanUrduSources() == 0 && (englishDonorSubs.isNotEmpty() || animeTitleQueries.isNotEmpty())) {
                kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                    UrduSubtitleEngine.synthesizeUrduFallbackFromEnglish(
                        imdbId = animeImdbId,
                        season = effectiveSubSeason,
                        episode = effectiveSubEpisode,
                        englishSubtitleFiles = englishDonorSubs.toList(),
                        subtitleCallback = subtitleSinkFor("urdu-neural-fallback")
                    )
                }
            }

            // Guaranteed non-zero subtitle floor: if both human Urdu harvesting and Phase 4
            // neural synthesis failed, emit up to 2 deferred English donor tracks.
            if (urduMode && subtitleLedger.urduSubtitleSources.isEmpty() && englishDonorSubs.isNotEmpty()) {
                for (donor in englishDonorSubs.take(2)) {
                    earlyController.onSubtitleEmitted(donor)
                    subtitleCallback(donor)
                    dispatcher.onSubtitleReceived()
                }
            }
        } finally {
            englishDonorSubs.clear()
            dispatcher.flush()
        }
        animeLinksFound.get() > 0 ||
            (if (urduMode) subtitleLedger.urduSubtitleSources.isNotEmpty() else subtitleLedger.hasAnyEmittedSubtitle())
    }

    fun getStatus(t: String?): ShowStatus {
        return when {
            t?.contains("Returning", ignoreCase = true) == true -> ShowStatus.Ongoing
            t?.contains("RELEASING", ignoreCase = true) == true -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniTitle(
        @param:JsonProperty("english") val english: String? = null,
        @param:JsonProperty("romaji") val romaji: String? = null,
        @param:JsonProperty("native") val native: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniCoverImage(
        @param:JsonProperty("extraLarge") val extraLarge: String? = null,
        @param:JsonProperty("large") val large: String? = null,
        @param:JsonProperty("medium") val medium: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniPageInfo(
        @param:JsonProperty("total") val total: Int? = null,
        @param:JsonProperty("perPage") val perPage: Int? = null,
        @param:JsonProperty("currentPage") val currentPage: Int? = null,
        @param:JsonProperty("lastPage") val lastPage: Int? = null,
        @param:JsonProperty("hasNextPage") val hasNextPage: Boolean? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniNextAiringEpisode(
        @param:JsonProperty("timeUntilAiring") val timeUntilAiring: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniRecommendedMedia(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("title") val title: AniTitle? = null,
        @param:JsonProperty("coverImage") val coverImage: AniCoverImage? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniRecommendationNode(
        @param:JsonProperty("id") val id: Int? = null,
        @param:JsonProperty("mediaRecommendation") val mediaRecommendation: AniRecommendedMedia? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniRecommendationEdge(
        @param:JsonProperty("node") val node: AniRecommendationNode? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AniRecommendationConnection(
        @param:JsonProperty("edges") val edges: List<AniRecommendationEdge>? = emptyList(),
        @param:JsonProperty("nodes") val nodes: List<AniRecommendationNode>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AnilistAPIResponse(
        @param:JsonProperty("data") val data: AnilistData? = null,
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class AnilistData(
            @param:JsonProperty("Page") val page: AnilistPage? = null,
            @param:JsonProperty("Media") val media: anilistMedia? = null,
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class AnilistPage(
                @param:JsonProperty("pageInfo") val pageInfo: AniPageInfo? = null,
                @param:JsonProperty("media") val media: List<Media>? = emptyList(),
            )
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class anilistMedia(
            @param:JsonProperty("id") val id: Int,
            @param:JsonProperty("idMal") val idMal: Int? = null,
            @param:JsonProperty("startDate") val startDate: StartDate? = null,
            @param:JsonProperty("episodes") val episodes: Int? = null,
            @param:JsonProperty("title") val title: AniTitle? = null,
            @param:JsonProperty("season") val season: String? = null,
            @param:JsonProperty("genres") val genres: List<String>? = emptyList(),
            @param:JsonProperty("averageScore") val averageScore: Int? = null,
            @param:JsonProperty("status") val status: String? = null,
            @param:JsonProperty("description") val description: String? = null,
            @param:JsonProperty("coverImage") val coverImage: AniCoverImage? = null,
            @param:JsonProperty("bannerImage") val bannerImage: String? = null,
            @param:JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniNextAiringEpisode? = null,
            @param:JsonProperty("airingSchedule") val airingSchedule: AiringScheduleNodes? = null,
            @param:JsonProperty("recommendations") val recommendations: AniRecommendationConnection? = null,
            @param:JsonProperty("format") val format: String? = null,
            @param:JsonProperty("synonyms") val synonyms: List<String>? = emptyList(),
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class StartDate(@param:JsonProperty("year") val year: Int? = null)

            @JsonIgnoreProperties(ignoreUnknown = true)
            data class AiringScheduleNodes(
                @param:JsonProperty("nodes") val nodes: List<AniNextAiringEpisode>? = emptyList()
            )

            fun totalEpisodes(): Int {
                return nextAiringEpisode?.episode?.minus(1)
                    ?: episodes
                    ?: airingSchedule?.nodes?.getOrNull(0)?.episode
                    ?: 0
            }

            fun getTitle(): String {
                return title?.english
                    ?: title?.romaji
                    ?: title?.native
                    ?: "Anime $id"
            }

            fun getCoverImage(): String? {
                return coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LinkData(
        @param:JsonProperty("simklId") val simklId: Int? = null,
        @param:JsonProperty("traktId") val traktId: Int? = null,
        @param:JsonProperty("imdbId") val imdbId: String? = null,
        @param:JsonProperty("tmdbId") val tmdbId: Int? = null,
        @param:JsonProperty("tvdbId") val tvdbId: Int? = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("season") val season: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
        @param:JsonProperty("tmdbSeason") val tmdbSeason: Int? = null,
        @param:JsonProperty("tmdbEpisode") val tmdbEpisode: Int? = null,
        @param:JsonProperty("aniId") val aniId: Int? = null,
        @param:JsonProperty("malId") val malId: Int? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("year") val year: Int? = null,
        @param:JsonProperty("orgTitle") val orgTitle: String? = null,
        @param:JsonProperty("isAnime") val isAnime: Boolean = false,
        @param:JsonProperty("airedYear") val airedYear: Int? = null,
        @param:JsonProperty("lastSeason") val lastSeason: Int? = null,
        @param:JsonProperty("epsTitle") val epsTitle: String? = null,
        @param:JsonProperty("jpTitle") val jpTitle: String? = null,
        @param:JsonProperty("date") val date: String? = null,
        @param:JsonProperty("airedDate") val airedDate: String? = null,
        @param:JsonProperty("isAsian") val isAsian: Boolean = false,
        @param:JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @param:JsonProperty("isCartoon") val isCartoon: Boolean = false,
        @param:JsonProperty("isDub") val isDub: Boolean = false,
        @param:JsonProperty("anidbEid") val anidbEid: Int? = null,
        /**
         * Every other name AniList knows for the show (English, Romaji, native, synonyms).
         *
         * Subtitle catalogues index anime by release title, and the released name is frequently
         * neither the provider title nor the Romaji one. The host reads this key when it builds a
         * fallback subtitle request, so the query is not limited to a single localized name.
         */
        @param:JsonProperty("alternativeTitles") val alternativeTitles: List<String>? = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Media(
        @param:JsonProperty("id") val id: Int,
        @param:JsonProperty("idMal") val idMal: Int? = null,
        @param:JsonProperty("season") val season: String? = null,
        @param:JsonProperty("seasonYear") val seasonYear: Int? = null,
        @param:JsonProperty("format") val format: String? = null,
        @param:JsonProperty("averageScore") val averageScore: Int? = null,
        @param:JsonProperty("episodes") val episodes: Int? = null,
        @param:JsonProperty("title") val title: AniTitle? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("coverImage") val coverImage: AniCoverImage? = null,
        @param:JsonProperty("synonyms") val synonyms: List<String>? = emptyList(),
        @param:JsonProperty("nextAiringEpisode") val nextAiringEpisode: AniNextAiringEpisode? = null,
    )

    private suspend fun tmdbToAnimeId(title: String?, year: Int?, type: TvType): AniIds {
        if (title.isNullOrBlank()) return AniIds(null, null)

        val query = """
        query (
          ${'$'}page: Int = 1
          ${'$'}search: String
          ${'$'}sort: [MediaSort] = [POPULARITY_DESC, SCORE_DESC]
          ${'$'}type: MediaType
          ${'$'}season: MediaSeason
          ${'$'}seasonYear: Int
          ${'$'}format: [MediaFormat]
        ) {
          Page(page: ${'$'}page, perPage: 20) {
            media(
              search: ${'$'}search
              sort: ${'$'}sort
              type: ${'$'}type
              season: ${'$'}season
              seasonYear: ${'$'}seasonYear
              format_in: ${'$'}format
            ) {
              id
              idMal
            }
          }
        }
    """.trimIndent()

        val variables = mutableMapOf(
            "search" to title,
            "sort" to listOf("SEARCH_MATCH"),
            "type" to "ANIME",
            "format" to listOf(
                if (type == TvType.AnimeMovie) "MOVIE" else "TV",
                "ONA",
                "OVA"
            )
        )

        val data = mapOf(
            "query" to query,
            "variables" to variables
        ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

        val res = app.post(anilistAPI, requestBody = data)
            .parsedSafe<AniSearch>()
            ?.data
            ?.let { it.Page?.media ?: it.media }
            ?.firstOrNull()

        return AniIds(res?.id, res?.idMal)
    }
}