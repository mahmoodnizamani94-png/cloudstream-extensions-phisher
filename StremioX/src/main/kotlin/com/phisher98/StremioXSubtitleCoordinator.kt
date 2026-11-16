package com.phisher98

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.SubsExtractors.invokeOpenSubs
import com.phisher98.SubsExtractors.invokeWatchsomuch
import com.phisher98.subtitles.BolChalConfig
import com.phisher98.subtitles.BolChalTranslationTransport
import com.phisher98.subtitles.CasSubtitleFormat
import com.phisher98.subtitles.CatalogApiKeys
import com.phisher98.subtitles.CatalogHttpTransport
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.DefaultBolChalTranslationTransport
import com.phisher98.subtitles.DefaultCatalogHttpTransport
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.EpisodeSubtitleSession
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.SubtitleCatalogSources
import com.phisher98.subtitles.SubtitleModeBridge
import com.phisher98.subtitles.UrduCasStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

internal object StremioXSubtitleCoordinator {
    const val TARGET_URDU_SUBTITLE_SOURCES = 6
    const val TARGET_ENGLISH_SUBTITLE_SOURCES = 6
    private const val PREWARM_COOLDOWN_MS = 10L * 60L * 1000L
    private const val PREWARM_TIMEOUT_MS = 25_000L

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var pluginPrefsRef: SharedPreferences? = null

    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prewarmHistory = ConcurrentHashMap<String, Long>()
    private val subtitlePlaybackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackJobLock = Any()
    private var activePlaybackJob: Job? = null

    val httpTransport: CatalogHttpTransport by lazy {
        DefaultCatalogHttpTransport()
    }

    val bolChalTransport: BolChalTranslationTransport by lazy {
        DefaultBolChalTranslationTransport()
    }

    @Volatile
    private var cachedCasRootPath: String? = null

    @Volatile
    private var cachedCasStore: UrduCasStore? = null

    val casStore: UrduCasStore
        get() {
            val targetRoot = File(resolveCacheBaseDir(), "cs_urdu_cas_v2")
            val targetPath = targetRoot.absolutePath
            val existing = cachedCasStore
            if (existing != null && cachedCasRootPath == targetPath) {
                return existing
            }
            return synchronized(this) {
                val recheck = cachedCasStore
                if (recheck != null && cachedCasRootPath == targetPath) {
                    recheck
                } else {
                    UrduCasStore(rootDir = targetRoot).also {
                        cachedCasRootPath = targetPath
                        cachedCasStore = it
                    }
                }
            }
        }

    fun initialize(context: Context?, sharedPref: SharedPreferences? = null) {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }
        if (sharedPref != null) {
            pluginPrefsRef = sharedPref
        }
    }

    fun buildLookupSpec(
        imdbId: String?,
        tmdbId: Int? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        runtimeMinutes: Int? = null,
        isAnime: Boolean = false,
    ): MediaLookupSpec {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTmdb = tmdbId?.takeIf { it > 0 }
        val titles = listOfNotNull(title?.trim()?.takeIf { it.isNotBlank() })
        val expectedDurationMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }

        return if (season == null || season < 0) {
            MediaLookupSpec.Movie(
                imdbId = cleanImdb,
                tmdbId = cleanTmdb,
                titles = titles,
                releaseYear = year,
                expectedDurationMs = expectedDurationMs,
                isAnime = isAnime,
            )
        } else {
            val coordinate = if (isAnime && cleanImdb == null && cleanTmdb == null) {
                EpisodeCoordinate.UnverifiedAnimeCour(
                    courTitles = titles.ifEmpty { listOf("Anime") },
                    episodeInCour = (episode ?: 1).coerceAtLeast(1),
                )
            } else {
                EpisodeCoordinate.VerifiedSeasonEpisode(
                    season = season,
                    episode = (episode ?: 1).coerceAtLeast(1),
                )
            }
            MediaLookupSpec.Episode(
                imdbId = cleanImdb,
                tmdbId = cleanTmdb,
                titles = titles,
                coordinate = coordinate,
                seriesPremiereYear = year,
                seasonAirYear = year,
                expectedDurationMs = expectedDurationMs,
                isAnime = isAnime,
            )
        }
    }

    fun prewarmAsync(
        lookupSpec: MediaLookupSpec,
        sharedPref: SharedPreferences? = pluginPrefsRef,
    ) {
        val hasIdentifier = when (lookupSpec) {
            is MediaLookupSpec.Movie ->
                !lookupSpec.imdbId.isNullOrBlank() || lookupSpec.tmdbId != null || lookupSpec.titles.isNotEmpty()
            is MediaLookupSpec.Episode ->
                !lookupSpec.imdbId.isNullOrBlank() || lookupSpec.tmdbId != null || lookupSpec.titles.isNotEmpty()
        }
        if (!hasIdentifier) return

        val key = buildPrewarmDedupKey(lookupSpec)
        val now = System.currentTimeMillis()
        val lastRun = prewarmHistory[key]
        if (lastRun != null && (now - lastRun) < PREWARM_COOLDOWN_MS) return
        prewarmHistory[key] = now

        val catalogSources = createCatalogSources(sharedPref)
        val bolChalConfig = resolveBolChalConfig(sharedPref)
        prewarmScope.launch {
            try {
                withTimeoutOrNull(PREWARM_TIMEOUT_MS) {
                    casStore.prewarmSubtitlesAsync(
                        spec = lookupSpec,
                        catalogSources = catalogSources,
                        bolChalConfig = bolChalConfig,
                        bolChalTransport = bolChalTransport,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    fun startPlaybackSession(
        lookupSpec: MediaLookupSpec,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        videoExtractionDone: CompletableDeferred<Unit>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): (SubtitleFile) -> Unit {
        val emittedDedupeKeys: MutableSet<String> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val sidecarIngestJobs = ConcurrentLinkedQueue<Job>()

        val sessionMode = resolveSessionMode(subtitleCallback, pluginPrefsRef)
        val session = EpisodeSubtitleSession(
            lookupSpec = lookupSpec,
            mode = sessionMode,
            casStore = casStore,
            httpTransport = httpTransport,
            catalogSources = createCatalogSources(pluginPrefsRef),
            bolChalConfig = resolveBolChalConfig(pluginPrefsRef),
            bolChalTransport = bolChalTransport,
            casFormat = CasSubtitleFormat.WEBVTT,
            targetUrduDiversityCount = TARGET_URDU_SUBTITLE_SOURCES,
            targetEnglishCatalogCount = TARGET_ENGLISH_SUBTITLE_SOURCES,
            progressiveBilingualMode = true,
            callbackIdentityToken = subtitleCallback,
        ) { sessionSub ->
            val cleanUrl = sessionSub.url.trim()
            if (cleanUrl.isNotEmpty() &&
                emittedDedupeKeys.add("${sessionSub.lang.trim().lowercase()}|$cleanUrl")
            ) {
                subtitleCallback(sessionSub)
            }
        }

        val playbackScope = openPlaybackScope()
        val sidecarIngestCallback: (SubtitleFile) -> Unit = { rawSub ->
            val job = launchOn(playbackScope) {
                session.ingestRawSubtitle(
                    sub = rawSub,
                    rawSourceHint = CatalogueSourceKey.STREMIO.wireId,
                )
            }
            sidecarIngestJobs.add(job)
        }

        launchOn(playbackScope) {
            val catalogJob = launchOn(playbackScope) {
                session.discoverAndIngestCatalog()
            }
            val watchSoMuchJob = launchOn(playbackScope) {
                invokeWatchsomuch(imdbId, season, episode) { rawSub ->
                    val job = launchOn(playbackScope) {
                        session.ingestRawSubtitle(
                            sub = rawSub,
                            rawSourceHint = CatalogueSourceKey.WATCHSOMUCH.wireId,
                        )
                    }
                    sidecarIngestJobs.add(job)
                }
            }
            val openSubsJob = launchOn(playbackScope) {
                invokeOpenSubs(imdbId, season, episode) { rawSub ->
                    val job = launchOn(playbackScope) {
                        session.ingestRawSubtitle(
                            sub = rawSub,
                            rawSourceHint = CatalogueSourceKey.OPENSUBTITLES.wireId,
                        )
                    }
                    sidecarIngestJobs.add(job)
                }
            }

            try {
                videoExtractionDone.await()
                watchSoMuchJob.join()
                openSubsJob.join()
                var guard = 0
                while (guard < 12) {
                    val pending = sidecarIngestJobs.filter { !it.isCompleted }
                    if (pending.isEmpty()) break
                    pending.joinAll()
                    guard++
                }
                catalogJob.join()
                session.completeSession()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }

        return sidecarIngestCallback
    }

    private fun openPlaybackScope(): CoroutineScope {
        synchronized(playbackJobLock) {
            activePlaybackJob?.cancel()
            val playbackJob = SupervisorJob(subtitlePlaybackScope.coroutineContext[Job])
            activePlaybackJob = playbackJob
            return CoroutineScope(subtitlePlaybackScope.coroutineContext + playbackJob)
        }
    }

    private fun launchOn(
        scope: CoroutineScope,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun resolveSessionMode(
        subtitleCallback: Any?,
        sharedPref: SharedPreferences? = null,
    ): SessionSubtitleMode {
        val fallbackUrdu = readPluginUrduFallback(sharedPref)
        return SubtitleModeBridge.resolveSessionMode(
            subtitleCallback = subtitleCallback,
            pluginFallbackUrduEnabled = fallbackUrdu,
            prefsLookup = { prefsName, key ->
                readCrossModulePrefString(prefsName, key, sharedPref)
            },
        )
    }

    private fun resolveBolChalConfig(sharedPref: SharedPreferences?): BolChalConfig {
        val byok = SubtitleModeBridge.resolveByokLlmConfig { prefsName, key ->
            readCrossModulePrefString(prefsName, key, sharedPref)
        }
        return if (byok != null) {
            BolChalConfig(
                llmEndpointUrl = byok.endpointUrl,
                llmModelId = byok.modelId,
                llmApiKey = byok.apiKey,
            )
        } else {
            BolChalConfig()
        }
    }

    private fun createCatalogSources(sharedPref: SharedPreferences?): SubtitleCatalogSources =
        SubtitleCatalogSources(
            transport = httpTransport,
            apiKeys = CatalogApiKeys(
                wyzieApiKey = resolveKey(sharedPref, listOf("wyzie_key", "wyzie_api_key"), "cloudstream.wyzie.key", "WYZIE_API_KEY"),
                subDlApiKey = resolveKey(sharedPref, listOf("subdl_api_key", "subdl_key"), "cloudstream.subdl.key", "SUBDL_API_KEY"),
                subSourceApiKey = resolveKey(sharedPref, listOf("subsource_api_key", "subsource_key"), "cloudstream.subsource.key", "SUBSOURCE_API_KEY"),
            ),
        )

    private fun resolveKey(
        sharedPref: SharedPreferences?,
        prefKeys: List<String>,
        sysPropKey: String,
        envKey: String,
    ): String? {
        for (prefKey in prefKeys) {
            val v = readAnyCrossModulePrefString(prefKey, sharedPref)
            if (!v.isNullOrBlank()) return v.trim()
        }
        runCatching { System.getProperty(sysPropKey) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        runCatching { System.getenv(envKey) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return null
    }

    private fun readPluginUrduFallback(sharedPref: SharedPreferences?): Boolean {
        val sysVal = runCatching { System.getProperty(SubtitleModeBridge.PROP_SUBTITLE_MODE) }.getOrNull()
        parseUrduModeFlag(sysVal)?.let { return it }
        val raw = readAnyCrossModulePrefString("subtitle_mode", sharedPref)
        return parseUrduModeFlag(raw) ?: false
    }

    private fun parseUrduModeFlag(raw: String?): Boolean? {
        return when (raw?.trim()?.lowercase(Locale.ROOT)) {
            "urdu", "ur", "urd", "ur-pk", "ur-in" -> true
            "default", "en", "eng", "english", "en-us", "en-gb" -> false
            else -> null
        }
    }

    private fun readCrossModulePrefString(
        prefsName: String,
        key: String,
        sharedPref: SharedPreferences?,
    ): String? {
        val ctx = appContextRef
        if (ctx != null) {
            val named = runCatching {
                ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)?.getString(key, null)
            }.getOrNull()
            if (!named.isNullOrBlank()) return named.trim()
        }
        return runCatching { (sharedPref ?: pluginPrefsRef)?.getString(key, null) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.trim()
    }

    private fun readAnyCrossModulePrefString(key: String, sharedPref: SharedPreferences?): String? {
        runCatching { (sharedPref ?: pluginPrefsRef)?.getString(key, null) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val ctx = appContextRef ?: return null
        val prefNames = listOf(
            SubtitleModeBridge.PREFS_URDU_BOLCHAL,
            SubtitleModeBridge.PREFS_LEGACY_MODE,
            "StremioX",
            "SuperStream",
            "StreamPlay",
        )
        for (prefName in prefNames) {
            val v = runCatching {
                ctx.getSharedPreferences(prefName, Context.MODE_PRIVATE)?.getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }

    private fun resolveCacheBaseDir(): File {
        val baseDir = appContextRef?.cacheDir
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }

    private fun buildPrewarmDedupKey(spec: MediaLookupSpec): String = when (spec) {
        is MediaLookupSpec.Movie ->
            "M|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|${spec.titles.firstOrNull().orEmpty()}|${spec.releaseYear ?: 0}"
        is MediaLookupSpec.Episode -> {
            val coordPart = when (val c = spec.coordinate) {
                is EpisodeCoordinate.VerifiedSeasonEpisode -> "S${c.season}E${c.episode}"
                is EpisodeCoordinate.UnverifiedAnimeCour -> "C:${c.courTitles.firstOrNull().orEmpty()}:E${c.episodeInCour}"
            }
            "E|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|$coordPart|${spec.seriesPremiereYear ?: 0}"
        }
    }
}
