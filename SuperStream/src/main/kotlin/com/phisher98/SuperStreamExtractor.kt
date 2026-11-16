package com.phisher98

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.phisher98.BuildConfig.NuvFeb
import com.phisher98.BuildConfig.SUPERSTREAM_FOURTH_API
import com.phisher98.BuildConfig.SUPERSTREAM_THIRD_API
import com.phisher98.subtitles.AlignmentAnchorKind
import com.phisher98.subtitles.AlignmentProof
import com.phisher98.subtitles.BolChalTranslationTransport
import com.phisher98.subtitles.CasSubtitleFormat
import com.phisher98.subtitles.CatalogApiKeys
import com.phisher98.subtitles.CatalogHttpTransport
import com.phisher98.subtitles.CueDocument
import com.phisher98.subtitles.CueEntry
import com.phisher98.subtitles.DefaultBolChalTranslationTransport
import com.phisher98.subtitles.DefaultCatalogHttpTransport
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.EpisodeSubtitleSession
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.PrewarmMetadataSummary
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.ProvenanceIdentity
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.StreamProviderKey
import com.phisher98.subtitles.SubtitleArchiveDecoder
import com.phisher98.subtitles.SubtitleCatalogSources
import com.phisher98.subtitles.SubtitleLanguage
import com.phisher98.subtitles.SubtitleTimeWarp
import com.phisher98.subtitles.UrduCasStore
import com.phisher98.subtitles.UrduOrthographyGate
import com.phisher98.subtitles.UrduQualityGate
import com.phisher98.subtitles.VerifiedSubtitleCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    "sec-ch-ua-platform" to "\"Windows\"",
)
private val LANG_HEADERS = mapOf("Accept-Language" to "en")

private val HTML_TRACK_TAG_REGEX = Regex("""<track\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HTML_ATTR_SRC_REGEX = Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val HTML_ATTR_SRCLANG_REGEX = Regex("""\bsrclang\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val HTML_ATTR_LABEL_REGEX = Regex("""\blabel\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
private val SIDECAR_URDU_TOKEN_REGEX = Regex("""(?:^|[._\-\s\[(])(?:ur|urd|urdu)(?:$|[._\-\s\])])""", RegexOption.IGNORE_CASE)
private val SIDECAR_ENGLISH_TOKEN_REGEX = Regex("""(?:^|[._\-\s\[(])(?:en|eng|english|sdh)(?:$|[._\-\s\])])""", RegexOption.IGNORE_CASE)

internal val SUPERSTREAM_URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX: Regex
    get() = UrduOrthographyGate.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX
internal val SUPERSTREAM_PERSIAN_ZWNJ_VERB_PREFIX_REGEX: Regex
    get() = UrduOrthographyGate.PERSIAN_ZWNJ_VERB_PREFIX_REGEX
internal val SUPERSTREAM_PERSIAN_YEH_RAW_HEH_REGEX: Regex
    get() = UrduOrthographyGate.PERSIAN_YEH_RAW_HEH_REGEX
internal val SUPERSTREAM_ARABIC_ORTHOGRAPHY_REGEX: Regex
    get() = UrduOrthographyGate.ARABIC_ORTHOGRAPHY_REGEX
internal val SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX: Regex
    get() = UrduOrthographyGate.RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX

object SuperStreamExtractor : SuperStream() {

    const val TARGET_URDU_SUBTITLE_SOURCES = 6
    const val TARGET_ENGLISH_SUBTITLE_SOURCES = 6
    private const val PREWARM_COOLDOWN_MS = 10L * 60L * 1000L
    private const val PREWARM_TIMEOUT_MS = 25_000L

    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prewarmHistory = ConcurrentHashMap<String, Long>()
    private val subtitlePlaybackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackJobLock = Any()
    private var activePlaybackJob: kotlinx.coroutines.Job? = null

    internal fun cancelActivePlaybackJob() {
        cancelPlayback(null)
    }

    internal fun cancelPlayback(scope: CoroutineScope? = null) {
        val owner = scope?.coroutineContext?.get(kotlinx.coroutines.Job)
        synchronized(playbackJobLock) {
            if (owner == null) {
                activePlaybackJob?.cancel()
                activePlaybackJob = null
            } else {
                owner.cancel()
                if (activePlaybackJob === owner) {
                    activePlaybackJob = null
                }
            }
        }
    }

    internal fun openPlaybackScope(isDownloadRequest: Boolean = false): CoroutineScope {
        val playbackJob = SupervisorJob(subtitlePlaybackScope.coroutineContext[kotlinx.coroutines.Job])
        val scope = CoroutineScope(subtitlePlaybackScope.coroutineContext + playbackJob)
        if (!isDownloadRequest) {
            synchronized(playbackJobLock) {
                activePlaybackJob?.cancel()
                activePlaybackJob = playbackJob
            }
        }
        return scope
    }

    internal fun launchOn(
        scope: CoroutineScope,
        block: suspend CoroutineScope.() -> Unit,
    ): kotlinx.coroutines.Job {
        return scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

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

    private val febBoxProvenanceHeaders: Map<String, String> by lazy {
        ProvenanceCodec.encodeHeaders(
            ProvenanceIdentity.StreamProvider(StreamProviderKey.FEBBOX_PACK),
        )
    }

    fun resolveWyzieApiKey(
        apiKey: String? = null,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModulePrefString("wyzie_key", sharedPref, context),
            readCrossModulePrefString("wyzie_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.wyzie.key") }.getOrNull(),
            runCatching { System.getProperty("wyzie.api.key") }.getOrNull(),
            runCatching { System.getenv("WYZIE_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveWyzieApiKey(
        context: Context?,
        sharedPref: SharedPreferences? = null,
    ): String? = resolveWyzieApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveSubDlApiKey(
        apiKey: String? = null,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModulePrefString("subdl_api_key", sharedPref, context),
            readCrossModulePrefString("subdl_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subdl.key") }.getOrNull(),
            runCatching { System.getProperty("subdl.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBDL_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubDlApiKey(
        context: Context?,
        sharedPref: SharedPreferences? = null,
    ): String? = resolveSubDlApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveSubSourceApiKey(
        apiKey: String? = null,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModulePrefString("subsource_api_key", sharedPref, context),
            readCrossModulePrefString("subsource_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subsource.key") }.getOrNull(),
            runCatching { System.getProperty("subsource.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBSOURCE_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubSourceApiKey(
        context: Context?,
        sharedPref: SharedPreferences? = null,
    ): String? = resolveSubSourceApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveCatalogApiKeys(
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): CatalogApiKeys = CatalogApiKeys(
        wyzieApiKey = resolveWyzieApiKey(context, sharedPref),
        subDlApiKey = resolveSubDlApiKey(context, sharedPref),
        subSourceApiKey = resolveSubSourceApiKey(context, sharedPref),
    )

    fun createCatalogSources(
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): SubtitleCatalogSources = SubtitleCatalogSources(
        transport = httpTransport,
        apiKeys = resolveCatalogApiKeys(sharedPref, context),
    )

    fun createEpisodeSession(
        lookupSpec: MediaLookupSpec,
        mode: SessionSubtitleMode,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
        casFormat: CasSubtitleFormat = CasSubtitleFormat.WEBVTT,
        callbackIdentityToken: Any? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): EpisodeSubtitleSession = EpisodeSubtitleSession(
        lookupSpec = lookupSpec,
        mode = mode,
        casStore = casStore,
        httpTransport = httpTransport,
        catalogSources = createCatalogSources(sharedPref, context),
        bolChalConfig = resolveSuperStreamBolChalConfig(sharedPref, context),
        bolChalTransport = bolChalTransport,
        casFormat = casFormat,
        targetUrduDiversityCount = TARGET_URDU_SUBTITLE_SOURCES,
        targetEnglishCatalogCount = TARGET_ENGLISH_SUBTITLE_SOURCES,
        progressiveBilingualMode = true,
        callbackIdentityToken = callbackIdentityToken ?: subtitleCallback,
        subtitleCallback = subtitleCallback,
    )

    fun prewarmMetadataAsync(
        lookupSpec: MediaLookupSpec,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ) {
        val key = buildPrewarmDedupKey(lookupSpec)
        val now = System.currentTimeMillis()
        val lastRun = prewarmHistory[key]
        if (lastRun != null && (now - lastRun) < PREWARM_COOLDOWN_MS) return
        prewarmHistory[key] = now

        val catalogSources = createCatalogSources(sharedPref, context)
        val bolChalConfig = resolveSuperStreamBolChalConfig(sharedPref, context)
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

    suspend fun prewarmMetadataNow(
        lookupSpec: MediaLookupSpec,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): PrewarmMetadataSummary = casStore.prewarmMetadataAsync(
        spec = lookupSpec,
        catalogSources = createCatalogSources(sharedPref, context),
    )

    fun prewarmSuperStreamUrduSubtitlesAsync(
        imdbId: String?,
        tmdbId: Int? = null,
        title: String?,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        runtimeMinutes: Int? = null,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null && (tmdbId == null || tmdbId <= 0)) return
        val titles = listOfNotNull(cleanTitle)
        val spec = if (season == null || season < 0) {
            MediaLookupSpec.Movie(
                imdbId = cleanImdb,
                tmdbId = tmdbId?.takeIf { it > 0 },
                titles = titles,
                releaseYear = year,
                expectedDurationMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
            )
        } else {
            MediaLookupSpec.Episode(
                imdbId = cleanImdb,
                tmdbId = tmdbId?.takeIf { it > 0 },
                titles = titles,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season, episode?.takeIf { it > 0 } ?: 1),
                seriesPremiereYear = year,
                seasonAirYear = year,
                expectedDurationMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
            )
        }
        prewarmMetadataAsync(lookupSpec = spec, sharedPref = sharedPref, context = context)
    }

    fun normalizeUrduTypography(input: String): String =
        UrduOrthographyGate.normalizeUrduTypography(input)

    fun containsVerifiedUrduScript(rawContent: String): Boolean =
        UrduOrthographyGate.containsVerifiedUrduScript(rawContent)

    fun containsVerifiedUrduScript(cues: List<CueEntry>): Boolean =
        UrduOrthographyGate.containsVerifiedUrduCueTexts(cues.map { it.text })

    fun decodeAndNormalizeSubtitleBytes(
        rawBytes: ByteArray,
        season: Int? = null,
        episode: Int? = null,
        normalizeTypography: Boolean = true,
    ): String {
        if (rawBytes.isEmpty()) return ""
        val spec = if (season != null && season >= 0 && episode != null && episode > 0) {
            MediaLookupSpec.Episode(
                imdbId = null,
                tmdbId = null,
                titles = emptyList(),
                seriesPremiereYear = null,
                seasonAirYear = null,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season, episode),
            )
        } else {
            null
        }
        val decoded = SubtitleArchiveDecoder.decode(rawBytes, spec).getOrElse { return "" }
        return if (normalizeTypography) normalizeUrduTypography(decoded) else decoded
    }

    suspend fun materializeNormalizedUrduSubtitle(
        url: String,
        headers: Map<String, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
        sourceTag: String = "subdl",
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return@withContext null
        val rawBytes = if (cleanUrl.startsWith("file:", ignoreCase = true)) {
            casStore.readOwnedCasFileBytesOrNull(cleanUrl)
        } else {
            httpTransport.get(cleanUrl, headers).getOrNull()?.bodyBytes
        } ?: return@withContext null
        val lookupSpec = if (season != null && season >= 0 && episode != null && episode > 0) {
            MediaLookupSpec.Episode(
                imdbId = null,
                tmdbId = null,
                titles = emptyList(),
                seriesPremiereYear = null,
                seasonAirYear = null,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season, episode),
            )
        } else {
            null
        }
        val decoded = SubtitleArchiveDecoder.decode(rawBytes, lookupSpec).getOrElse { return@withContext null }
        val provenance = ProvenanceCodec.decode(
            headers = headers + (ProvenanceCodec.HEADER_SOURCE to sourceTag),
            urlOrFileName = cleanUrl,
        )
        val admitted = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = decoded,
            language = SubtitleLanguage.URDU,
            provenance = provenance,
            alignment = AlignmentProof(
                warp = SubtitleTimeWarp.Identity,
                anchorKind = AlignmentAnchorKind.UNANCHORED,
                syncConfidence = 1.0,
                medianOnsetResidualMs = 0,
                p95OnsetResidualMs = 0,
                splitCount = 0,
            ),
            lookupSpec = lookupSpec,
        )
        val doc: CueDocument = when (admitted) {
            is VerifiedSubtitleCandidate.Admitted -> admitted.document
            is VerifiedSubtitleCandidate.Rejected -> return@withContext null
        }
        casStore.writeAlignedSubtitle(doc = doc, format = CasSubtitleFormat.WEBVTT).fileUri
    }

    internal fun clearCachesForTest() {
        prewarmHistory.clear()
    }

    internal suspend fun emitFebBoxSidecar(
        rawLang: String?,
        rawLabelOrFileName: String?,
        rawUrl: String?,
        extraHeaders: Map<String, String> = emptyMap(),
        subtitleCallback: ((SubtitleFile) -> Unit)?,
    ) {
        if (subtitleCallback == null) return
        val cleanUrl = rawUrl?.trim()?.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        } ?: return

        val combinedProbe = listOfNotNull(rawLang, rawLabelOrFileName, cleanUrl.substringAfterLast('/'))
            .joinToString(" ")
        val resolvedLangLabel = when {
            isUrduSubtitleCode(rawLang) ||
                isUrduSubtitleCode(rawLabelOrFileName) ||
                SIDECAR_URDU_TOKEN_REGEX.containsMatchIn(combinedProbe) -> "Urdu"
            isEnglishSubtitleCode(rawLang) ||
                isEnglishSubtitleCode(rawLabelOrFileName) ||
                SIDECAR_ENGLISH_TOKEN_REGEX.containsMatchIn(combinedProbe) -> "English"
            else -> return
        }

        val mergedHeaders = LinkedHashMap<String, String>()
        mergedHeaders.putAll(extraHeaders)
        mergedHeaders.putAll(febBoxProvenanceHeaders)
        try {
            subtitleCallback.invoke(
                newSubtitleFile(resolvedLangLabel, cleanUrl) {
                    this.headers = mergedHeaders
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    internal suspend fun extractSuperStreamHtmlSidecars(
        htmlContent: String,
        extraHeaders: Map<String, String> = VIDEO_HEADERS,
        subtitleCallback: ((SubtitleFile) -> Unit)?,
    ) {
        if (subtitleCallback == null || htmlContent.isBlank()) return
        for (trackMatch in HTML_TRACK_TAG_REGEX.findAll(htmlContent)) {
            val tag = trackMatch.value
            val rawSrc = HTML_ATTR_SRC_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val src = com.phisher98.subtitles.LatinEnglishText.decodeHtmlEntities(rawSrc).trim()
            val srclang = HTML_ATTR_SRCLANG_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim()
            val label = HTML_ATTR_LABEL_REGEX.find(tag)?.groupValues?.getOrNull(1)?.trim()
            emitFebBoxSidecar(
                rawLang = srclang,
                rawLabelOrFileName = label,
                rawUrl = src,
                extraHeaders = extraHeaders,
                subtitleCallback = subtitleCallback,
            )
        }
    }

    internal suspend fun extractFebBoxPayloadHintsAndSidecars(
        rawJson: String,
        subtitleCallback: ((SubtitleFile) -> Unit)?,
        onReleaseHint: ((String) -> Unit)? = null,
    ) {
        if (rawJson.isBlank()) return
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return

        suspend fun inspectSubArray(arr: JSONArray?) {
            if (arr == null || subtitleCallback == null) return
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val subUrl = item.optString("url").ifBlank {
                    item.optString("file").ifBlank {
                        item.optString("src").ifBlank { item.optString("sub_url") }
                    }
                }
                val lang = item.optString("lang").ifBlank {
                    item.optString("language").ifBlank { item.optString("srclang") }
                }
                val label = item.optString("label").ifBlank {
                    item.optString("name").ifBlank { item.optString("title") }
                }
                emitFebBoxSidecar(
                    rawLang = lang,
                    rawLabelOrFileName = label,
                    rawUrl = subUrl,
                    subtitleCallback = subtitleCallback,
                )
            }
        }

        inspectSubArray(root.optJSONArray("subtitles"))
        inspectSubArray(root.optJSONArray("tracks"))
        inspectSubArray(root.optJSONArray("subs"))

        val versions = root.optJSONArray("versions") ?: return
        for (i in 0 until versions.length()) {
            val verObj = versions.optJSONObject(i) ?: continue
            verObj.optString("name").trim().takeIf { it.isNotBlank() }?.let { rawName ->
                onReleaseHint?.invoke(rawName)
            }
            inspectSubArray(verObj.optJSONArray("subtitles"))
            inspectSubArray(verObj.optJSONArray("tracks"))
            inspectSubArray(verObj.optJSONArray("subs"))
            val links = verObj.optJSONArray("links") ?: continue
            for (j in 0 until links.length()) {
                val linkObj = links.optJSONObject(j) ?: continue
                inspectSubArray(linkObj.optJSONArray("subtitles"))
                inspectSubArray(linkObj.optJSONArray("tracks"))
            }
        }
    }

    suspend fun invokeSuperstream(
        token: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeSuperstream(
            token = token,
            imdbId = imdbId,
            season = season,
            episode = episode,
            subtitleCallback = null,
            onReleaseHint = null,
            callback = callback,
        )
    }

    suspend fun invokeSuperstream(
        token: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        onReleaseHint: ((String) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val searchUrl = "$SUPERSTREAM_FOURTH_API/search?keyword=$imdbId"
        val href = app.get(searchUrl, timeout = 10L).document.selectFirst("h2.film-name a")?.attr("href")
            ?.let { SUPERSTREAM_FOURTH_API + it }
        val mediaId = href?.let {
            app.get(it, timeout = 10L).document.selectFirst("h2.heading-name a")?.attr("href")
                ?.substringAfterLast("/")?.toIntOrNull()
        }
        mediaId?.let {
            invokeExternalSource(
                mediaId = it,
                type = if (season == null) 1 else 2,
                season = season,
                episode = episode,
                callback = callback,
                token = token,
                subtitleCallback = subtitleCallback,
                onReleaseHint = onReleaseHint,
            )
        }
    }

    private suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        token: String? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        onReleaseHint: ((String) -> Unit)? = null,
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
                it.fileName.equals("season $season", true)
            }?.fid?.let { parentId ->
                app.get(
                    "$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1",
                    headers = LANG_HEADERS,
                    timeout = 10L,
                )
                    .parsedSafe<ExternalResponse>()?.data?.fileList?.filter {
                        it.fileName?.contains("s${seasonSlug}e${episodeSlug}", true) == true
                    }
            }
        } ?: return

        fids.forEach { entry ->
            entry.fileName?.trim()?.takeIf { it.isNotBlank() }?.let { rawFileName ->
                onReleaseHint?.invoke(rawFileName)
            }
        }

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
                                timeout = 10L,
                            ).text
                            val json = try {
                                JSONObject(player)
                            } catch (e: Exception) {
                                Log.e("Error:", "Invalid JSON response $e")
                                return@withPermit
                            }
                            extractFebBoxPayloadHintsAndSidecars(
                                rawJson = player,
                                subtitleCallback = subtitleCallback,
                                onReleaseHint = onReleaseHint,
                            )
                            val htmlContent = json.optString("html", "")
                            if (htmlContent.isEmpty()) return@withPermit

                            extractSuperStreamHtmlSidecars(
                                htmlContent = htmlContent,
                                extraHeaders = VIDEO_HEADERS,
                                subtitleCallback = subtitleCallback,
                            )

                            val sourcesWithQualities = ZeroAllocParser.extractSuperStreamQualities(htmlContent)
                            sourcesWithQualities.forEach { item ->
                                callback.invoke(
                                    newExtractorLink(
                                        "⌜ SuperStream ⌟",
                                        "⌜ SuperStream ⌟ [Server ${index + 1}] ${item.size}",
                                        item.url,
                                        ExtractorLinkType.VIDEO,
                                    ) {
                                        this.quality = getIndexQuality(item.quality)
                                        this.headers = VIDEO_HEADERS
                                    },
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

    suspend fun invokeSuperstreamFeb(
        token: String? = null,
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        invokeSuperstreamFeb(
            token = token,
            id = id,
            season = season,
            episode = episode,
            subtitleCallback = null,
            onReleaseHint = null,
            callback = callback,
        )
    }

    suspend fun invokeSuperstreamFeb(
        token: String? = null,
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        onReleaseHint: ((String) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (token.isNullOrEmpty()) return

        val url = if (season == null) {
            "$NuvFeb/api/media/movie/$id?cookie=${URLEncoder.encode(token, "UTF-8")}"
        } else {
            "$NuvFeb/api/media/tv/$id/$season/$episode?cookie=${URLEncoder.encode(token, "UTF-8")}"
        }

        var parsed: FebResponse? = null
        var rawText = ""

        try {
            for (attempt in 0..1) {
                val response = app.get(url, timeout = 10L)
                if (response.code == 500 && attempt == 0) {
                    delay(1000L)
                } else {
                    rawText = response.text
                    parsed = response.parsedSafe<FebResponse>()
                    break
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("SuperStream", "invokeSuperstreamFeb failed: $e")
            return
        }

        extractFebBoxPayloadHintsAndSidecars(
            rawJson = rawText,
            subtitleCallback = subtitleCallback,
            onReleaseHint = onReleaseHint,
        )

        parsed ?: return

        parsed.versions.orEmpty().forEach { version ->
            version.name?.trim()?.takeIf { it.isNotBlank() }?.let { rawName ->
                onReleaseHint?.invoke(rawName)
            }
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
                        type = INFER_TYPE,
                    ) {
                        quality = getQualityFromName(qualityName)
                    },
                )
            }
        }
    }

    private fun resolveCacheBaseDir(): File {
        val baseDir = superStreamAppContextRef?.cacheDir
            ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        if (!baseDir.exists()) baseDir.mkdirs()
        return baseDir
    }

    private fun buildPrewarmDedupKey(spec: MediaLookupSpec): String = when (spec) {
        is MediaLookupSpec.Movie -> {
            val normTitle = spec.titles.firstOrNull().orEmpty().trim().lowercase(Locale.ROOT)
            "M|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|$normTitle|${spec.releaseYear ?: 0}"
        }
        is MediaLookupSpec.Episode -> {
            val coordPart = when (val c = spec.coordinate) {
                is EpisodeCoordinate.VerifiedSeasonEpisode -> "S${c.season}E${c.episode}"
                is EpisodeCoordinate.UnverifiedEpisodeOnly -> "E${c.episode}"
                is EpisodeCoordinate.UnverifiedAnimeCour -> "C:${c.courTitles.firstOrNull().orEmpty().trim().lowercase(Locale.ROOT)}:E${c.episodeInCour}"
            }
            val normTitle = spec.titles.firstOrNull().orEmpty().trim().lowercase(Locale.ROOT)
            "E|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|$normTitle|$coordPart|${spec.seriesPremiereYear ?: 0}"
        }
    }

    private fun readCrossModulePrefString(
        key: String,
        sharedPref: SharedPreferences? = null,
        context: Context? = null,
    ): String? {
        runCatching { sharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        runCatching { SuperStream.companionSharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val appCtx = context?.applicationContext ?: context ?: superStreamAppContextRef ?: return null
        val prefNames = listOf(
            SUBTITLE_BOLCHAL_PREFS_NAME,
            "SuperStream",
            "StreamPlay",
            SUBTITLE_MODE_PREFS_NAME,
        )
        for (prefName in prefNames) {
            val v = runCatching {
                appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)?.getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }
}
