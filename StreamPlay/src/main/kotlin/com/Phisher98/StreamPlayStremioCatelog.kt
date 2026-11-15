package com.phisher98

import com.lagradost.api.Log
import android.content.SharedPreferences
import androidx.core.content.edit
import android.os.Build
import androidx.annotation.RequiresApi
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.imdbUrlToIdNullable
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlay.Companion.dahmerMoviesAPI
import com.phisher98.StreamPlayExtractor.invokeSubtitleAPI
import com.phisher98.StreamPlayExtractor.invokeWYZIESubs
import com.phisher98.StreamPlayExtractor.token
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject


class StreamPlayStremioCatelog(
    override var mainUrl: String,
    override var name: String,
    val sharedPref: SharedPreferences? = null
) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others,TvType.Movie,
        TvType.TvSeries)
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (mainUrl.isEmpty()) {
            throw IllegalArgumentException("Configure in StreamPlay Catalogs Addon in Extension Settings\n")
        }
        mainUrl = mainUrl.fixSourceUrl()

        val pageSize = 100
        val skip = (page - 1) * pageSize

        val manifest = app
            .get("$mainUrl/manifest.json")
            .parsedSafe<Manifest>()

        val lists = mutableListOf<HomePageList>()

        manifest?.catalogs?.amap { catalog ->
            catalog.toHomePageList(
                provider = this,
                skip = skip
            ).let {
                if (it.list.isNotEmpty()) {
                    lists.add(it)
                }
            }
        }

        return newHomePageResponse(
            lists,
            hasNext = true
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.fixSourceUrl()
        val res = app.get("${mainUrl}/manifest.json").parsedSafe<Manifest>()
        val list = mutableListOf<SearchResponse>()
        res?.catalogs?.amap { catalog ->
            list.addAll(catalog.search(query, this))
        }
        return list.distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val json = app.get(url).text
            val metaJson = JSONObject(json).getJSONObject("meta").toString()
            parseJson(metaJson)
        }
        val encodedId = res.id

        var response = app.get("${mainUrl}/meta/${res.type}/$encodedId.json")
            .parsedSafe<CatalogResponse>()

        if (response == null) {
            response = app.get("https://v3-cinemeta.strem.io/meta/${res.type}/$encodedId.json")
                .parsedSafe<CatalogResponse>() ?: throw RuntimeException("Failed to load meta")
        }

        val entry = response.meta
            ?: response.metas?.firstOrNull { it.id == res.id }
            ?: response.metas?.firstOrNull()
            ?: throw RuntimeException("Meta not found")

        return entry.toLoadResponse(this, res.id)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val res = parseJson<LoadData>(data)
        val imdb = res.resolveImdbId()
        val cinemeta = imdb?.let {
            fetchCinemetaMeta(it, res.type)
        }

        val resolved = res.copy(
            imdbId = imdb,
            title = cinemeta?.title ?: res.title
        )

        val disabledProviderIds = getOrInitializeDisabledProviders(sharedPref)
        val providersList = buildProviders().filter { it.id !in disabledProviderIds }
        val activeTopRanks = providersList.mapNotNull {
            val boost = FAST_PROVIDER_BOOST[it.id] ?: 0f
            if (boost >= 85f) boost.toInt() else null
        }.toSet().ifEmpty { setOf(100, 98, 95, 94, 93, 92, 91, 89, 88, 87) }
        val inFlightTopRanks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>().apply {
            addAll(activeTopRanks)
        }
        val runningTopProviders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
            addAll(providersList.filter { (FAST_PROVIDER_BOOST[it.id] ?: 0f) >= 85f }.map { it.id })
        }

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = callback,
            scope = this,
            top720GraceMs = 350L,
            activeTopRanks = activeTopRanks,
            isRankInFlight = { rank -> inFlightTopRanks.contains(rank) }
        )
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator(
            upstreamCallback = { link -> dispatcher.onLinkAccepted(link) },
            onUpgradeCallback = { link -> dispatcher.onLinkAccepted(link) }
        )
        val earlySatisfactionConfig = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 2,
            qualityThreshold = Qualities.P720.value,
            highBitrateThresholdKbps = 2500,
            minSubtitles = 1,
            satisfyWithOneLinkIfSubsFound = false,
            requireSubtitles = true,
            requireDualQualities = false,
            require720p = true,
            adaptiveTierEscalation = true,
            softGracePeriodAfterFirstLinkMs = 4500L,
            maxPipelineTimeoutMs = 18_000L
        )
        val earlyController = EarlySatisfactionController(earlySatisfactionConfig)

        val catalogLinksFound = java.util.concurrent.atomic.AtomicInteger(0)
        val catalogSubsFound = java.util.concurrent.atomic.AtomicInteger(0)
        val topTierDualGuardedKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var episodeSubtitleSessionRef: com.phisher98.subtitles.EpisodeSubtitleSession? = null

        fun emitSingleLinkInternal(link: ExtractorLink): Boolean {
            val optimized = StreamLinkOptimizer.optimize(link)
            earlyController.onCandidateLink(optimized)
            return when (deduplicator.emitDetailed(optimized)) {
                StreamLinkOptimizer.DeduplicationResult.NEW -> {
                    episodeSubtitleSessionRef?.registerActiveVideoProviderHint(
                        optimized.headers["X-StreamPlay-Source"]
                            ?: optimized.source.ifBlank { optimized.name }
                    )
                    catalogLinksFound.incrementAndGet()
                    earlyController.onLinkEmitted(optimized)
                    true
                }
                StreamLinkOptimizer.DeduplicationResult.UPGRADED -> {
                    episodeSubtitleSessionRef?.registerActiveVideoProviderHint(
                        optimized.headers["X-StreamPlay-Source"]
                            ?: optimized.source.ifBlank { optimized.name }
                    )
                    earlyController.onLinkUpgraded(optimized)
                    true
                }
                StreamLinkOptimizer.DeduplicationResult.DROPPED -> {
                    false
                }
            }
        }

        val trackedLinkCallback: (ExtractorLink) -> Unit = { link ->
            StreamLinkOptimizer.processTopTierDualQualityStream(link, topTierDualGuardedKeys) { single ->
                emitSingleLinkInternal(single)
            }
        }

        val effectiveSharedPref = sharedPref ?: StreamPlay.companionSharedPref
        val sessionMode = UrduSubtitleEngine.resolveSessionMode(
            subtitleCallback = subtitleCallback,
            sharedPref = effectiveSharedPref,
        )
        val urduMode = sessionMode != com.phisher98.subtitles.SessionSubtitleMode.DEFAULT
        val subtitleLedger = SubtitleSourceLedger(
            targetEnglishSources = TARGET_ENGLISH_SUBTITLE_SOURCES,
            targetUrduSources = UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES,
        )
        val englishDonorSubs = java.util.concurrent.CopyOnWriteArrayList<SubtitleFile>()
        val rawSubtitleIngestJobs = java.util.concurrent.ConcurrentLinkedQueue<kotlinx.coroutines.Job>()
        val isMovieMedia = resolved.season == null || resolved.season < 0

        fun emitSessionSubtitle(sessionSub: SubtitleFile): Boolean {
            val rawUrl = sessionSub.url.trim()
            val normalizedUrl = when {
                rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true) ->
                    StreamPlayShield.stripTrackingParticles(rawUrl.replace(" ", "%20"))
                rawUrl.startsWith("file:", ignoreCase = true) ->
                    "file:///" + rawUrl.removePrefix("file:").removePrefix("FILE:").trimStart('/')
                rawUrl.startsWith("//") ->
                    StreamPlayShield.stripTrackingParticles("https:${rawUrl.replace(" ", "%20")}")
                else -> return false
            }
            if (!normalizedUrl.startsWith("file://", ignoreCase = true) && StreamPlayShield.isBlockedUrl(normalizedUrl)) {
                StreamPlayShield.countEmissionBlocked()
                return false
            }
            if (!subtitleLedger.markEmitted(sessionSub.lang, normalizedUrl, sessionSub.headers)) {
                return false
            }
            val outSub = SubtitleFile(lang = sessionSub.lang, url = normalizedUrl).apply {
                headers = sessionSub.headers
            }
            subtitleLedger.recordSubtitleFile(outSub)
            catalogSubsFound.incrementAndGet()
            earlyController.onSubtitleEmitted(outSub)
            subtitleCallback(outSub)
            dispatcher.onSubtitleReceived()
            return true
        }

        val safeLookupTitles = listOfNotNull(resolved.title, res.title)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .ifEmpty { listOfNotNull(imdb).ifEmpty { listOf("Media") } }
        val lookupSpec = if (isMovieMedia) {
            com.phisher98.subtitles.MediaLookupSpec.Movie(
                imdbId = imdb,
                tmdbId = cinemeta?.tmdbId,
                titles = safeLookupTitles,
                releaseYear = resolved.year,
                expectedDurationMs = null,
            )
        } else {
            com.phisher98.subtitles.MediaLookupSpec.Episode(
                imdbId = imdb,
                tmdbId = cinemeta?.tmdbId,
                titles = safeLookupTitles,
                seriesPremiereYear = resolved.year,
                seasonAirYear = resolved.year,
                expectedDurationMs = null,
                coordinate = com.phisher98.subtitles.EpisodeCoordinate.VerifiedSeasonEpisode(
                    season = resolved.season,
                    episode = resolved.episode?.takeIf { it > 0 } ?: 1,
                ),
            )
        }

        val episodeSubtitleSession = if (urduMode) {
            UrduSubtitleEngine.createEpisodeSession(
                lookupSpec = lookupSpec,
                mode = sessionMode,
                sharedPref = effectiveSharedPref,
                callbackIdentityToken = subtitleCallback,
            ) { sessionSub ->
                emitSessionSubtitle(sessionSub)
            }.also { episodeSubtitleSessionRef = it }
        } else {
            null
        }
        val catalogSessionRan = java.util.concurrent.atomic.AtomicBoolean(false)

        fun trackedSubCallbackFor(sourceHint: String): (SubtitleFile) -> Unit = subCallback@{ rawSub ->
            val rawUrl = rawSub.url.trim()
            val normalizedUrl = when {
                rawUrl.startsWith("http://", ignoreCase = true) ||
                    rawUrl.startsWith("https://", ignoreCase = true) ->
                    StreamPlayShield.stripTrackingParticles(rawUrl.replace(" ", "%20"))
                rawUrl.startsWith("file:", ignoreCase = true) ->
                    "file:///" + rawUrl.removePrefix("file:").removePrefix("FILE:").trimStart('/')
                rawUrl.startsWith("//") ->
                    StreamPlayShield.stripTrackingParticles("https:${rawUrl.replace(" ", "%20")}")
                else -> return@subCallback
            }
            if (!normalizedUrl.startsWith("file://", ignoreCase = true) && StreamPlayShield.isBlockedUrl(normalizedUrl)) {
                StreamPlayShield.countEmissionBlocked()
                return@subCallback
            }
            val cleanedLang = cleanSubtitleLabel(rawSub.lang)
            val explicitProvenance = rawSub.headers?.entries
                ?.firstOrNull { it.key.equals("X-StreamPlay-Source", ignoreCase = true) }
                ?.value?.trim()?.takeIf { it.isNotBlank() }
            val provenance = explicitProvenance ?: sourceHint.ifBlank { StreamPlayShield.hostOf(normalizedUrl) }

            if (urduMode && episodeSubtitleSession != null) {
                val isUrdu = isUrduSubtitleLabel(cleanedLang)
                val isEnglish = isEnglishSubtitleLabel(cleanedLang)
                if (!isUrdu && !isEnglish) return@subCallback
                val shieldedSub = SubtitleFile(lang = cleanedLang, url = normalizedUrl).apply {
                    headers = (rawSub.headers ?: emptyMap()) + ("X-StreamPlay-Source" to provenance)
                }
                if (isEnglish) {
                    if (sessionMode == com.phisher98.subtitles.SessionSubtitleMode.BILINGUAL_DOWNLOAD) {
                        emitSessionSubtitle(shieldedSub)
                    } else if (englishDonorSubs.size < 4) {
                        val wasEmpty = englishDonorSubs.isEmpty()
                        englishDonorSubs.add(shieldedSub)
                        if (wasEmpty && catalogSubsFound.get() == 0) {
                            earlyController.onSubtitleEmitted(shieldedSub)
                        }
                    }
                }
                val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        episodeSubtitleSession.ingestRawSubtitle(shieldedSub, rawSourceHint = provenance)
                    }
                }
                rawSubtitleIngestJobs.add(job)
                return@subCallback
            }

            if (isEnglishSubtitleLabel(cleanedLang)) {
                if (!subtitleLedger.markEmitted(cleanedLang, normalizedUrl, rawSub.headers)) return@subCallback
                subtitleLedger.recordEnglishDelivery(provenance, sourceHint)
                val normalizedSub = SubtitleFile(lang = cleanedLang, url = normalizedUrl).apply {
                    headers = (rawSub.headers ?: emptyMap()) + ("X-StreamPlay-Source" to provenance)
                }
                catalogSubsFound.incrementAndGet()
                earlyController.onSubtitleEmitted(normalizedSub)
                subtitleCallback(normalizedSub)
                dispatcher.onSubtitleReceived()
            }
        }

        val stremioAddons = StreamPlayStremioAddonSettings.getDynamicStremioMap(
            sharedPref,
            imdb,
            resolved.season,
            resolved.episode,
            trackedSubCallbackFor("stremio"),
            trackedLinkCallback
        )
        val authToken = token

        val tasks = buildList {
            if (urduMode && episodeSubtitleSession != null) {
                add(
                    PipelinedTask(
                        providerId = "urdu-catalog-session",
                        initialTier = LatencyTier.TIER_1,
                        isVideo = false,
                        taskTimeoutMs = 12_000L,
                        priorityBoost = 99f
                    ) {
                        episodeSubtitleSession.discoverAndIngestCatalog()
                        catalogSessionRan.set(true)
                    }
                )
            } else {
                add(PipelinedTask("subtitles", LatencyTier.TIER_1, isVideo = false) {
                    invokeSubtitleAPI(imdb, resolved.season, resolved.episode, trackedSubCallbackFor("subtitleapi"))
                })
                add(PipelinedTask("wyziesub", LatencyTier.TIER_1, isVideo = false) {
                    invokeWYZIESubs(imdb, res.season, res.episode, trackedSubCallbackFor("wyziesub"))
                })
            }
            providersList.forEach { provider ->
                val boost = FAST_PROVIDER_BOOST[provider.id] ?: 0f
                add(PipelinedTask(
                    providerId = provider.id,
                    isVideo = true,
                    priorityBoost = boost
                ) {
                    try {
                        provider.invoke(
                            resolved.toLinkData(),
                            trackedSubCallbackFor(provider.id),
                            trackedLinkCallback,
                            authToken ?: "",
                            dahmerMoviesAPI
                        )
                    } finally {
                        if (boost >= 85f) {
                            runningTopProviders.remove(provider.id)
                            val rank = boost.toInt()
                            val hasRemainingWithRank = runningTopProviders.any { (FAST_PROVIDER_BOOST[it] ?: 0f) == rank.toFloat() }
                            if (!hasRemainingWithRank) {
                                inFlightTopRanks.remove(rank)
                                dispatcher.markRankCompleted(rank)
                            }
                        }
                    }
                })
            }
            stremioAddons.forEach { (addonId, addon) ->
                add(PipelinedTask(
                    providerId = addonId,
                    isVideo = true,
                    taskTimeoutMs = 12_000L
                ) {
                    addon()
                })
            }
        }

        try {
            SpeculativePipeliner.executePipelined(
                tasks = tasks,
                config = earlySatisfactionConfig,
                controller = earlyController
            )

            if (urduMode && episodeSubtitleSession != null) {
                if (!catalogSessionRan.get() && subtitleLedger.nativeOrHumanUrduSourceCount() < UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES) {
                    withTimeoutOrNull(8_000L) {
                        episodeSubtitleSession.discoverAndIngestCatalog()
                    }
                }
                val jobsSnapshot = rawSubtitleIngestJobs.toList()
                val joined = withTimeoutOrNull(6_000L) {
                    jobsSnapshot.joinAll()
                }
                if (joined == null) {
                    jobsSnapshot.forEach { it.cancel() }
                }
                withTimeoutOrNull(20_000L) {
                    episodeSubtitleSession.completeSession()
                }
                if (sessionMode == com.phisher98.subtitles.SessionSubtitleMode.URDU &&
                    subtitleLedger.urduSourceCount() == 0 &&
                    englishDonorSubs.isNotEmpty()
                ) {
                    for (donor in englishDonorSubs.take(2)) {
                        emitSessionSubtitle(donor)
                    }
                }
            }
        } finally {
            englishDonorSubs.clear()
            dispatcher.flush()
        }

        return@coroutineScope catalogLinksFound.get() > 0 || catalogSubsFound.get() > 0
    }

    data class LoadData(
        val type: String? = null,
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val imdbId: String? = null,
        val year: Int? = null,
        val title: String? = null
    )


    private fun LoadData.toLinkData(): StreamPlay.LinkData {
        return StreamPlay.LinkData(
            imdbId = imdbId,
            type = type,
            season = season,
            episode = episode,
            title = title,
            year = year,
        )
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Catalog(
        var name: String?,
        val id: String,
        val type: String?,
        val types: MutableList<String> = mutableListOf()
    ) {
        init {
            if (type != null) types.add(type)
        }

        suspend fun search(query: String, provider: StreamPlayStremioCatelog): List<SearchResponse> {
            val entries = mutableListOf<SearchResponse>()
            types.forEach { type ->
                val res = app.get(
                    "${provider.mainUrl}/catalog/${type}/${id}/search=${query}.json",
                    timeout = 120L
                ).parsedSafe<CatalogResponse>()
                res?.metas?.forEach { entry ->
                    entries.add(entry.toSearchResponse(provider))
                }
            }
            return entries
        }

        suspend fun toHomePageList(provider: StreamPlayStremioCatelog, skip: Int): HomePageList {
            val entries = mutableMapOf<String, SearchResponse>()

            types.forEach { type ->
                val url = if (skip > 0) {
                    "${provider.mainUrl}/catalog/$type/$id/skip=$skip.json"
                } else {
                    "${provider.mainUrl}/catalog/$type/$id.json"
                }

                val res = app.get(
                    url,
                    timeout = 120L
                ).parsedSafe<CatalogResponse>()

                res?.metas?.forEach { entry ->
                    if (!entries.containsKey(entry.id)) {
                        entries[entry.id] = entry.toSearchResponse(provider)
                    }
                }
            }

            return HomePageList(
                name ?: id,
                entries.values.toList()
            )
        }
    }

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)

    private data class Trailer(
        val source: String?,
        val type: String?
    )

    private data class CatalogEntry(
        @param:JsonProperty("name") val name: String,
        @param:JsonProperty("id") val id: String,
        @param:JsonProperty("poster") val poster: String?,
        @param:JsonProperty("background") val background: String?,
        @param:JsonProperty("description") val description: String?,
        @param:JsonProperty("imdbRating") val imdbRating: String?,
        @param:JsonProperty("type") val type: String?,
        @param:JsonProperty("videos") val videos: List<Video>?,
        @param:JsonProperty("genre") val genre: List<String>?,
        @param:JsonProperty("genres") val genres: List<String> = emptyList(),
        @param:JsonProperty("cast") val cast: List<String> = emptyList(),
        @param:JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
        @param:JsonProperty("year") val yearNum: String? = null
    ) {
        fun toSearchResponse(provider: StreamPlayStremioCatelog): SearchResponse {
            return provider.newMovieSearchResponse(
                name,
                this.toJson(),
                TvType.Others
            ) {
                posterUrl = poster
            }
        }

        suspend fun toLoadResponse(provider: StreamPlayStremioCatelog, imdbId: String?): LoadResponse {
            val urduMode = UrduSubtitleEngine.isUrduMode(
                context = null,
                sharedPref = provider.sharedPref ?: StreamPlay.companionSharedPref
            )
            val parsedYear = yearNum?.split("–", "-")?.firstOrNull()?.trim()?.toIntOrNull()
            val isMovieEntry = videos.isNullOrEmpty()
            if (urduMode) {
                val safeTitles = listOfNotNull(name.trim().takeIf { it.isNotBlank() }, imdbId)
                    .ifEmpty { listOf("Media") }
                val prewarmSpec = if (isMovieEntry) {
                    com.phisher98.subtitles.MediaLookupSpec.Movie(
                        imdbId = imdbId,
                        tmdbId = null,
                        titles = safeTitles,
                        releaseYear = parsedYear,
                        expectedDurationMs = null,
                    )
                } else {
                    com.phisher98.subtitles.MediaLookupSpec.Episode(
                        imdbId = imdbId,
                        tmdbId = null,
                        titles = safeTitles,
                        seriesPremiereYear = parsedYear,
                        seasonAirYear = parsedYear,
                        expectedDurationMs = null,
                        coordinate = com.phisher98.subtitles.EpisodeCoordinate.VerifiedSeasonEpisode(
                            season = 1,
                            episode = 1,
                        ),
                    )
                }
                UrduSubtitleEngine.prewarmMetadataAsync(
                    lookupSpec = prewarmSpec,
                    sharedPref = provider.sharedPref ?: StreamPlay.companionSharedPref
                )
            }
            val enrichedTags = (genre ?: genres)
            if (isMovieEntry) {
                return provider.newMovieLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.Movie,
                    LoadData(type, id, imdbId = imdbId, year = parsedYear, title = name)
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = description
                    year = parsedYear
                    tags = enrichedTags
                    addActors(cast)
                    addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" })
                    addImdbId(imdbId)
                }
            } else {
                return provider.newTvSeriesLoadResponse(
                    name,
                    "${provider.mainUrl}/meta/${type}/${id}.json",
                    TvType.TvSeries,
                    videos.map {
                        it.toEpisode(provider, type, imdbId)
                    }
                ) {
                    posterUrl = poster
                    backgroundPosterUrl = background
                    score = Score.from10(imdbRating)
                    plot = description
                    year = parsedYear
                    tags = enrichedTags
                    addActors(cast)
                    addTrailer(trailersSources.map { "https://www.youtube.com/watch?v=${it.source}" }.randomOrNull())
                    addImdbId(imdbId)
                }
            }

        }
    }

    private data class Video(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("season") val seasonNumber: Int? = null,
        @param:JsonProperty("number") val number: Int? = null,
        @param:JsonProperty("episode") val episode: Int? = null,
        @param:JsonProperty("thumbnail") val thumbnail: String? = null,
        @param:JsonProperty("overview") val overview: String? = null,
        @param:JsonProperty("description") val description: String? = null,
    ) {
        fun toEpisode(provider: StreamPlayStremioCatelog, type: String?, imdbId: String?): Episode {
            return provider.newEpisode(
                LoadData(type, id, seasonNumber, episode ?: number, imdbId)
            ) {
                this.name = this@Video.name ?: title
                this.posterUrl = thumbnail
                this.description = overview ?:  this@Video.description
                this.season = seasonNumber
                this.episode =  this@Video.episode ?: number
            }
        }
    }

    suspend fun LoadData.resolveImdbId(): String? {
        val source = imdbId ?: id ?: return null
        val imdb: String? = imdbUrlToIdNullable(source)
        if (imdb != null) return imdb
        return when {
            source.startsWith("tt") -> source
            source.startsWith("tmdb:") -> tmdbToImdb(source.removePrefix("tmdb:"), type)
            source.startsWith("kitsu:") -> kitsuToImdb(source.removePrefix("kitsu:"))
            else -> null
        }
    }




    suspend fun tmdbToImdb(tmdbId: String, type: String?): String? {
        val mediaType = if (type == "series") "tv" else "movie"

        val res = app.get(
            "https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids",
            params = mapOf("api_key" to "98ae14df2b8d8f8f8136499daf79f0e0")
        ).parsedSafe<TmdbExternalIds>()

        return res?.imdb_id
    }

    data class TmdbExternalIds(
        @param:JsonProperty("imdb_id") val imdb_id: String?
    )

    suspend fun kitsuToImdb(kitsuId: String): String? {
        val id = kitsuId.removePrefix("kitsu:")

        val res = app.get(
            "https://api.ani.zip/mappings",
            params = mapOf("kitsu_id" to id)
        ).parsedSafe<AniZipResponse>()

        return res?.mappings?.imdb_id
    }

    data class AniZipResponse(
        val mappings: AniZipMappings?
    )

    data class AniZipMappings(
        val imdb_id: String?
    )

    suspend fun fetchCinemetaMeta(
        imdbId: String,
        type: String?
    ): CinemetaMetaData? {
        val mediaType = if (type == "series") "series" else "movie"

        val res = app.get(
            "https://v3-cinemeta.strem.io/meta/$mediaType/$imdbId.json"
        ).parsedSafe<CinemetaResponse>()

        return res?.meta?.let {
            CinemetaMetaData(
                title = it.name,
                tmdbId = it.links
                    ?.firstOrNull { link -> link.category == "tmdb" }
                    ?.id
                    ?.toIntOrNull()
            )
        }
    }

    data class CinemetaResponse(
        val meta: CinemetaMeta?
    )

    data class CinemetaMeta(
        val name: String?,
        val links: List<CinemetaLink>?
    )

    data class CinemetaLink(
        val category: String?,
        val id: String?
    )

    data class CinemetaMetaData(
        val title: String?,
        val tmdbId: Int?
    )



}
