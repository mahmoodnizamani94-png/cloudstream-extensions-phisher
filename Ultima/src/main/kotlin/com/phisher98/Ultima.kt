package com.phisher98

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.phisher98.UltimaUtils.SectionInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Ultima(val plugin: UltimaPlugin) : MainAPI() {
    override var name = "Ultima"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    private val sm = UltimaStorageManager

    private val sectionInfoCache = UltimaUtils.LruCacheWithTtl<String, SectionInfo>(maxSize = 128)

    private val mapper = jacksonObjectMapper()
    private var sectionNamesList: List<String> = emptyList()

    private fun loadSections(): List<MainPageData> {
        val tempSectionNames = mutableListOf<String>()

        val result = mutableListOf<MainPageData>()
        val savedPlugins = sm.currentExtensions

        result += mainPageOf("" to "watch_sync")

        val enabledSections = savedPlugins
            .flatMap { it.sections?.asList() ?: emptyList() }
            .filter { it.enabled }
            .sortedByDescending { it.priority }

        enabledSections.forEach { section ->
            try {
                val sectionKey = mapper.writeValueAsString(section)
                val sectionName = buildSectionName(section, tempSectionNames)
                result += mainPageOf(sectionKey to sectionName)
            } catch (e: Exception) {
                Log.e("loadSections", "Failed to load section ${section.name}: ${e.message}")
            }
        }

        sectionNamesList = tempSectionNames

        return if (result.size <= 1) mainPageOf("" to "") else result
    }


    private fun buildSectionName(section: SectionInfo, names: MutableList<String>): String {
        val name = if (sm.extNameOnHome) {
            "${section.pluginName}: ${section.name}"
        } else if (names.contains(section.name)) {
            "${section.name} ${names.count { it.startsWith(section.name) } + 1}"
        } else {
            section.name
        }
        names += name
        return name
    }


    override val mainPage get() = loadSections()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.name.isEmpty()) {
            throw ErrorLoadingException("Select sections from the extension's settings page to show here.")
        }

        return try {
            if (request.name == "watch_sync") {
                val homeSections = ArrayList<HomePageList>()

                try {
                    val payload = UltimaSettingsSyncUtils.fetchCategory(SyncCategory.RESUME_WATCHING)
                    if (payload != null && payload.data.isNotBlank()) {
                        val backupFile = try {
                            mapper.readValue<BackupFile>(payload.data)
                        } catch (_: Exception) {
                            null
                        }

                        if (backupFile != null) {
                            val resumeWatchingKey = backupFile.datastore.string?.keys?.find { it.contains("result_resume_watching") }
                            val resumeWatchingJson = resumeWatchingKey?.let { backupFile.datastore.string[it] }
                            val resumeWatchingList = resumeWatchingJson?.let {
                                try {
                                    mapper.readValue<List<com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult>>(it)
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            if (!resumeWatchingList.isNullOrEmpty()) {
                                homeSections += HomePageList("Continue from Cloud", resumeWatchingList)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("getMainPage", "Error loading watch_sync: ${e.message}")
                }

                newHomePageResponse(homeSections, false)
            } else {
                val section = sectionInfoCache.getOrPut(request.data) { AppUtils.parseJson(request.data) }
                val provider = UltimaUtils.getAllProviders().find { it.name == section.pluginName }
                    ?: throw ErrorLoadingException("Provider '${section.pluginName}' is not available.")

                val liveData = provider.mainPage
                    .find { it.name.equals(section.name, ignoreCase = true) }
                    ?.data
                    ?: section.url

                val response = provider.getMainPage(
                    page,
                    MainPageRequest(
                        name = section.name,
                        data = liveData,
                        horizontalImages = request.horizontalImages
                    )
                ) ?: return null

                newHomePageResponse(
                    response.items.map { list ->
                        HomePageList(request.name, list.list, list.isHorizontalImages)
                    },
                    response.hasNext
                )
            }
        } catch (e: Throwable) {
            Log.e("getMainPage", "Error loading main page: ${e.message}")
            e.printStackTrace()
            null
        }
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val enabledPlugins = sm.getEnabledPluginNames()
        val providersToSearch = UltimaUtils.getAllProviders().filter { it.name in enabledPlugins }

        val tasks = mutableListOf<suspend () -> List<SearchResponse>>()

        for (provider in providersToSearch) {
            val pluginName = provider.name
            tasks += suspend {
                try {
                    when (val result = provider.search(query)) {
                        is List<*> -> {
                            result.map { item ->
                                when (item) {
                                    is MovieSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is AnimeSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    is TvSeriesSearchResponse -> item.copy(name = "[$pluginName] ${item.name}")
                                    else -> item
                                }
                            }
                        }
                        else -> emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("search", "Search failed for provider $pluginName: ${e.message}")
                    emptyList()
                }
            }
        }

        return runLimitedParallel(limit = 4, tasks).flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        val enabledPlugins = sm.getEnabledPluginNames()
        val providersToTry = UltimaUtils.getAllProviders().filter { it.name in enabledPlugins }

        if (providersToTry.isEmpty()) {
            return newMovieLoadResponse("Welcome to Ultima", "", TvType.Others, "")
        }

        // Tier 1: Host Affinity
        val targetHost = UltimaUtils.getHost(url)
        var affinityProvider: MainAPI? = null
        if (!targetHost.isNullOrBlank()) {
            affinityProvider = providersToTry.find { provider ->
                val providerHost = UltimaUtils.getHost(provider.mainUrl)
                if (providerHost.isNullOrBlank()) {
                    false
                } else {
                    providerHost.equals(targetHost, ignoreCase = true) ||
                        providerHost.removePrefix("www.").equals(targetHost.removePrefix("www."), ignoreCase = true)
                }
            }
        }

        if (affinityProvider != null) {
            try {
                val response = withTimeoutOrNull(3000L) {
                    affinityProvider.load(url)
                }
                if (response != null &&
                    response.name.isNotBlank() &&
                    !response.posterUrl.isNullOrBlank()
                ) {
                    return response
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.e("Ultima load", "Tier 1 affinity failed for ${affinityProvider.name}: ${e.message}")
            }
        }

        // Tier 2: Bounded Speculative Racing
        val remainingProviders = if (affinityProvider != null) {
            providersToTry.filter { it != affinityProvider }
        } else {
            providersToTry
        }

        if (remainingProviders.isNotEmpty()) {
            val racedResponse = speculativeRaceLoad(remainingProviders, url)
            if (racedResponse != null) {
                return racedResponse
            }
        }

        return newMovieLoadResponse("Welcome to Ultima", "", TvType.Others, "")
    }

    private suspend fun speculativeRaceLoad(
        providers: List<MainAPI>,
        url: String
    ): LoadResponse? = supervisorScope {
        val semaphore = Semaphore(DeviceProfiler.getActiveConcurrency())
        val winnerDeferred = CompletableDeferred<LoadResponse>()

        val jobs = providers.map { provider ->
            launch(Dispatchers.IO) {
                semaphore.withPermit {
                    if (winnerDeferred.isCompleted) return@withPermit
                    try {
                        val response = provider.load(url)
                        if (response != null &&
                            response.name.isNotBlank() &&
                            !response.posterUrl.isNullOrBlank()
                        ) {
                            winnerDeferred.complete(response)
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Log.e("Ultima load", "Speculative load failed for ${provider.name}: ${e.message}")
                    }
                }
            }
        }

        val joinerJob = launch {
            jobs.joinAll()
            if (!winnerDeferred.isCompleted) {
                winnerDeferred.cancel()
            }
        }

        val result = try {
            winnerDeferred.await()
        } catch (_: Throwable) {
            null
        }

        // Early cancellation of sibling coroutines upon finding first valid result
        jobs.forEach { it.cancel() }
        joinerJob.cancel()

        result
    }


}
