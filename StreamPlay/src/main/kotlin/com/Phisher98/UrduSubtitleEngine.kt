package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.subtitles.AlignmentAnchorKind
import com.phisher98.subtitles.AlignmentProof
import com.phisher98.subtitles.BolChalConfig
import com.phisher98.subtitles.BolChalTranslationTransport
import com.phisher98.subtitles.CasSubtitleFormat
import com.phisher98.subtitles.CatalogApiKeys
import com.phisher98.subtitles.CatalogHttpTransport
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.CueDocument
import com.phisher98.subtitles.CueEntry
import com.phisher98.subtitles.DefaultBolChalTranslationTransport
import com.phisher98.subtitles.DefaultCatalogHttpTransport
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.EpisodeSubtitleSession
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.PrewarmMetadataSummary
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.StreamProviderKey
import com.phisher98.subtitles.SubtitleArchiveDecoder
import com.phisher98.subtitles.SubtitleCatalogSources
import com.phisher98.subtitles.SubtitleLanguage
import com.phisher98.subtitles.SubtitleModeBridge
import com.phisher98.subtitles.SubtitleTimeWarp
import com.phisher98.subtitles.UrduCasStore
import com.phisher98.subtitles.UrduOrthographyGate
import com.phisher98.subtitles.UrduQualityGate
import com.phisher98.subtitles.VerifiedSubtitleCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object UrduSubtitleEngine {

    const val SUBTITLE_BOLCHAL_PREFS_NAME = SubtitleModeBridge.PREFS_URDU_BOLCHAL
    const val SUBTITLE_MODE_PREFS_NAME = SubtitleModeBridge.PREFS_LEGACY_MODE
    const val STREAMPLAY_PREFS_NAME = "StreamPlay"
    const val SUPERSTREAM_PREFS_NAME = "SuperStream"
    const val SUBTITLE_MODE_KEY = "subtitle_mode"
    const val SUBTITLE_MODE_SYS_PROP = SubtitleModeBridge.PROP_SUBTITLE_MODE
    const val MODE_DEFAULT = "default"
    const val MODE_URDU = "urdu"
    const val TARGET_URDU_SUBTITLE_SOURCES = 2
    const val TOTAL_URDU_SOTA_SOURCES = 8

    private const val PREWARM_COOLDOWN_MS = 10L * 60L * 1000L

    data class UrduSourceDescriptor(
        val canonicalKey: String,
        val displayName: String,
        val tierLabel: String,
    )

    val URDU_SOTA_SOURCES: List<UrduSourceDescriptor> = listOf(
        UrduSourceDescriptor("subdl", "SubDL", "Human Urdu Archive & API v1"),
        UrduSourceDescriptor("subsource", "SubSource", "Post-Subscene Urdu Archive & API v1"),
        UrduSourceDescriptor("wyziesub", "Wyzie", "Multi-Source Edge Aggregator"),
        UrduSourceDescriptor("subsense", "SubSense", "Multi-Archive Stremio Urdu Index"),
        UrduSourceDescriptor("yify", "YIFY", "Keyless IMDb Movie Urdu Archive"),
        UrduSourceDescriptor("subf2m", "Subf2m", "Community Urdu Subtitle Mirror"),
        UrduSourceDescriptor("subtitlecat", "SubtitleCat", "Verified English Donor & Archive Index"),
        UrduSourceDescriptor("opensubtitles", "OpenSubtitles", "OpenSubtitles v3 & REST Urdu Index"),
    )

    val URDU_NEURAL_FALLBACK_DESCRIPTOR = UrduSourceDescriptor(
        canonicalKey = "urdu-neural-fallback",
        displayName = "UrduAI",
        tierLabel = "Scene-Aware Spoken Bol-Chal Localizer",
    )

    private val DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS = setOf(
        "addic7ed",
        "vidsrc",
        "superstream",
    )

    private val URDU_CANONICAL_DISPLAY_NAMES: Map<String, String> = buildMap {
        for (src in CatalogueSourceKey.entries) {
            put(src.wireId, src.displayBadge)
        }
        put("subtitles", "OpenSubtitles")
        for (prov in StreamProviderKey.entries) {
            put(prov.wireId, prov.displayBadge)
        }
        put("urdu-neural-fallback", "UrduAI")
    }

    val URDU_ARABIC_SCRIPT_REGEX: Regex get() = UrduOrthographyGate.URDU_ARABIC_SCRIPT_REGEX
    val URDU_DISTINCTIVE_CHARS_REGEX: Regex get() = UrduOrthographyGate.URDU_DISTINCTIVE_CHARS_REGEX
    val URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX: Regex get() = UrduOrthographyGate.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX
    val RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX: Regex get() = UrduOrthographyGate.RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX
    val PERSIAN_ARABIC_EXCLUSIVE_REGEX: Regex get() = UrduOrthographyGate.PERSIAN_ARABIC_EXCLUSIVE_REGEX
    val PERSIAN_ZWNJ_VERB_PREFIX_REGEX: Regex get() = UrduOrthographyGate.PERSIAN_ZWNJ_VERB_PREFIX_REGEX
    val PERSIAN_YEH_RAW_HEH_REGEX: Regex get() = UrduOrthographyGate.PERSIAN_YEH_RAW_HEH_REGEX
    val ARABIC_ORTHOGRAPHY_REGEX: Regex get() = UrduOrthographyGate.ARABIC_ORTHOGRAPHY_REGEX

    @Volatile
    private var appContextRef: android.content.Context? = null

    private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prewarmHistory = ConcurrentHashMap<String, Long>()

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

    fun canonicalUrduSourceKey(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        if (cleaned in DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS) return null
        if (cleaned == "urdu-neural-fallback" || cleaned == "urduai" || cleaned == "bolchal" ||
            cleaned.contains("urdu-neural") || cleaned.contains("neural-ai") || cleaned.contains("neural ai") ||
            cleaned.contains("spoken-ai") || cleaned.contains("spoken ai") || cleaned.startsWith("urduai ")
        ) {
            return "urdu-neural-fallback"
        }
        if (cleaned == "stremio-os-urdu" || cleaned == "subtitles") {
            return "subtitles"
        }
        CatalogueSourceKey.fromTextOrHost(cleaned)?.let { src ->
            return if (src.wireId in DECOMMISSIONED_OR_ENGLISH_ONLY_KEYS) null else src.wireId
        }
        StreamProviderKey.fromTextOrHost(cleaned)?.let { return it.wireId }
        return null
    }

    fun urduSourceDisplayName(rawKey: String?): String? {
        val canonical = canonicalUrduSourceKey(rawKey) ?: return null
        return URDU_CANONICAL_DISPLAY_NAMES[canonical]
    }

    fun extractUrduSourceBadge(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val trimmed = label.trim()
        val aiBracket = Regex("""\[\s*UrduAI(?:\s+([^\]]+))?\s*]""", RegexOption.IGNORE_CASE).find(trimmed)
        if (aiBracket != null) {
            val donorSuffix = aiBracket.groupValues[1].trim()
            return if (donorSuffix.isNotEmpty()) "UrduAI $donorSuffix" else "UrduAI"
        }
        val knownBadges = (URDU_CANONICAL_DISPLAY_NAMES.values + listOf("Neural AI", "Spoken AI")).distinct()
        for (badge in knownBadges) {
            if (trimmed.equals(badge, ignoreCase = true) ||
                trimmed.contains("[$badge]", ignoreCase = true) ||
                trimmed.contains("($badge)", ignoreCase = true) ||
                trimmed.endsWith("- $badge", ignoreCase = true) ||
                trimmed.endsWith("• $badge", ignoreCase = true)
            ) {
                return if (badge.equals("Spoken AI", ignoreCase = true) || badge.equals("Neural AI", ignoreCase = true)) {
                    "UrduAI"
                } else {
                    badge
                }
            }
        }
        return null
    }

    fun formatUrduSourceLabel(sourceKey: String?, modifier: String? = null): String {
        val badge = urduSourceDisplayName(sourceKey) ?: extractUrduSourceBadge(sourceKey)
        val cleanMod = modifier?.trim()?.removePrefix("[")?.removeSuffix("]")?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Urdu")
            if (badge != null) {
                append(" [").append(badge).append("]")
            }
            if (cleanMod != null && !cleanMod.equals(badge, ignoreCase = true)) {
                append(" [").append(cleanMod).append("]")
            }
        }
    }

    fun sotaSourcesSummary(): String =
        "$TOTAL_URDU_SOTA_SOURCES Urdu sources (SubDL, SubSource, Wyzie, SubSense, YIFY, Subf2m, SubtitleCat, OpenSubtitles)"

    enum class SubtitleMode(val id: String, val displayLabel: String) {
        DEFAULT(MODE_DEFAULT, "English"),
        URDU(MODE_URDU, "Urdu \u2067\u0627\u0631\u062f\u0648\u2069");

        companion object {
            fun fromId(raw: String?): SubtitleMode? {
                return when (raw?.trim()?.lowercase(Locale.ROOT)) {
                    MODE_URDU, "ur", "urd", "ur-pk", "ur-in" -> URDU
                    MODE_DEFAULT, "en", "eng", "english", "en-us", "en-gb" -> DEFAULT
                    else -> null
                }
            }
        }
    }

    fun getSubtitleMode(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): SubtitleMode {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }

        val sysProp = runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull()
        SubtitleMode.fromId(sysProp)?.let { return it }

        val ctx = context?.applicationContext ?: context ?: appContextRef
        if (ctx != null) {
            val prefNames = listOf(
                SUBTITLE_BOLCHAL_PREFS_NAME,
                SUBTITLE_MODE_PREFS_NAME,
                STREAMPLAY_PREFS_NAME,
                SUPERSTREAM_PREFS_NAME,
            )
            for (prefName in prefNames) {
                val sharedModeStr = runCatching {
                    ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                        ?.getString(SUBTITLE_MODE_KEY, null)
                }.getOrNull()
                SubtitleMode.fromId(sharedModeStr)?.let { resolved ->
                    runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, resolved.id) }
                    return resolved
                }
            }
        }

        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        val extModeStr = runCatching { effectivePref?.getString(SUBTITLE_MODE_KEY, null) }.getOrNull()
        SubtitleMode.fromId(extModeStr)?.let { resolved ->
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, resolved.id) }
            return resolved
        }

        if (context != null || sharedPref != null) {
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, SubtitleMode.DEFAULT.id) }
        }
        return SubtitleMode.DEFAULT
    }

    fun isUrduMode(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): Boolean = getSubtitleMode(context, sharedPref) == SubtitleMode.URDU

    fun isUrduSubtitleMode(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): Boolean = isUrduMode(context, sharedPref)

    fun setSubtitleMode(
        mode: SubtitleMode,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ) {
        if (context != null) {
            appContextRef = context.applicationContext ?: context
        }

        runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, mode.id) }

        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        runCatching {
            effectivePref?.edit()?.putString(SUBTITLE_MODE_KEY, mode.id)?.apply()
        }
        if (sharedPref != null && StreamPlay.companionSharedPref != null && sharedPref !== StreamPlay.companionSharedPref) {
            runCatching {
                StreamPlay.companionSharedPref?.edit()?.putString(SUBTITLE_MODE_KEY, mode.id)?.apply()
            }
        }

        val ctx = context?.applicationContext ?: context ?: appContextRef
        if (ctx != null) {
            val prefNames = listOf(
                SUBTITLE_BOLCHAL_PREFS_NAME,
                SUBTITLE_MODE_PREFS_NAME,
                STREAMPLAY_PREFS_NAME,
                SUPERSTREAM_PREFS_NAME,
            )
            for (prefName in prefNames) {
                runCatching {
                    ctx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(SUBTITLE_MODE_KEY, mode.id)
                        ?.apply()
                }
            }
            val targetAutoSelectLang = if (mode == SubtitleMode.URDU) "ur" else "en"
            runCatching {
                ctx.getSharedPreferences("${ctx.packageName}_preferences", android.content.Context.MODE_PRIVATE)
                    ?.edit()
                    ?.putString("subtitles_auto_select", targetAutoSelectLang)
                    ?.putString(SUBTITLE_MODE_KEY, mode.id)
                    ?.apply()
            }
        }
    }

    fun initializeMode(
        context: android.content.Context,
        sharedPref: android.content.SharedPreferences? = null,
    ) {
        appContextRef = context.applicationContext ?: context
        val mode = getSubtitleMode(context, sharedPref)
        if (runCatching { System.getProperty(SUBTITLE_MODE_SYS_PROP) }.getOrNull().isNullOrBlank()) {
            runCatching { System.setProperty(SUBTITLE_MODE_SYS_PROP, mode.id) }
        }
    }

    fun resolveSessionMode(
        subtitleCallback: Any?,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): SessionSubtitleMode {
        if (subtitleCallback != null &&
            (SubtitleModeBridge.isBilingualDownloadCallback(subtitleCallback) ||
                subtitleCallback.javaClass.name.contains("BilingualDownload", ignoreCase = true))
        ) {
            return SessionSubtitleMode.BILINGUAL_DOWNLOAD
        }
        val ctx = context?.applicationContext ?: context ?: appContextRef
        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        return SubtitleModeBridge.resolveSessionMode(
            subtitleCallback = subtitleCallback,
            pluginFallbackUrduEnabled = isUrduSubtitleMode(context, effectivePref),
            prefsLookup = { prefsName, key ->
                readNamedSharedPrefString(prefsName, key, effectivePref, ctx)
            },
        )
    }

    fun resolveBolChalConfig(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): BolChalConfig {
        val ctx = context?.applicationContext ?: context ?: appContextRef
        val effectivePref = sharedPref ?: StreamPlay.companionSharedPref
        val byok = SubtitleModeBridge.resolveByokLlmConfig { prefsName, key ->
            readNamedSharedPrefString(prefsName, key, effectivePref, ctx)
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

    fun resolveWyzieApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("wyzie_key", sharedPref, context),
            readCrossModuleSharedPrefString("wyzie_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.wyzie.key") }.getOrNull(),
            runCatching { System.getProperty("wyzie.api.key") }.getOrNull(),
            runCatching { System.getenv("WYZIE_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveWyzieApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null,
    ): String? = resolveWyzieApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveSubDlApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("subdl_api_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subdl.key") }.getOrNull(),
            runCatching { System.getProperty("subdl.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBDL_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubDlApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null,
    ): String? = resolveSubDlApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveSubSourceApiKey(
        apiKey: String? = null,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null,
    ): String? {
        val candidates = listOfNotNull(
            apiKey,
            readCrossModuleSharedPrefString("subsource_api_key", sharedPref, context),
            readCrossModuleSharedPrefString("subsource_key", sharedPref, context),
            runCatching { System.getProperty("cloudstream.subsource.key") }.getOrNull(),
            runCatching { System.getProperty("subsource.api.key") }.getOrNull(),
            runCatching { System.getenv("SUBSOURCE_API_KEY") }.getOrNull(),
        )
        return candidates.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun resolveSubSourceApiKey(
        context: android.content.Context?,
        sharedPref: android.content.SharedPreferences? = null,
    ): String? = resolveSubSourceApiKey(apiKey = null, sharedPref = sharedPref, context = context)

    fun resolveCatalogApiKeys(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): CatalogApiKeys = CatalogApiKeys(
        wyzieApiKey = resolveWyzieApiKey(context, sharedPref),
        subDlApiKey = resolveSubDlApiKey(context, sharedPref),
        subSourceApiKey = resolveSubSourceApiKey(context, sharedPref),
    )

    fun createCatalogSources(
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): SubtitleCatalogSources = SubtitleCatalogSources(
        transport = httpTransport,
        apiKeys = resolveCatalogApiKeys(context, sharedPref),
    )

    fun createEpisodeSession(
        lookupSpec: MediaLookupSpec,
        mode: SessionSubtitleMode,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
        casFormat: CasSubtitleFormat = CasSubtitleFormat.WEBVTT,
        multiProviderVideoSession: Boolean = true,
        callbackIdentityToken: Any? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): EpisodeSubtitleSession = EpisodeSubtitleSession(
        lookupSpec = lookupSpec,
        mode = mode,
        casStore = casStore,
        httpTransport = httpTransport,
        catalogSources = createCatalogSources(context, sharedPref),
        bolChalConfig = resolveBolChalConfig(context, sharedPref),
        bolChalTransport = bolChalTransport,
        casFormat = casFormat,
        targetUrduDiversityCount = TARGET_URDU_SUBTITLE_SOURCES,
        multiProviderVideoSession = multiProviderVideoSession,
        callbackIdentityToken = callbackIdentityToken ?: subtitleCallback,
        subtitleCallback = subtitleCallback,
    )

    fun prewarmMetadataAsync(
        lookupSpec: MediaLookupSpec,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ) {
        if (!isUrduSubtitleMode(context, sharedPref)) return
        val key = buildPrewarmDedupKey(lookupSpec)
        val now = System.currentTimeMillis()
        val lastRun = prewarmHistory[key]
        if (lastRun != null && (now - lastRun) < PREWARM_COOLDOWN_MS) return
        prewarmHistory[key] = now

        val catalogSources = createCatalogSources(context, sharedPref)
        prewarmScope.launch {
            runCatching {
                withTimeoutOrNull(8_000L) {
                    casStore.prewarmMetadataAsync(lookupSpec, catalogSources)
                }
            }
        }
    }

    suspend fun prewarmMetadataNow(
        lookupSpec: MediaLookupSpec,
        context: android.content.Context? = null,
        sharedPref: android.content.SharedPreferences? = null,
    ): PrewarmMetadataSummary = casStore.prewarmMetadataAsync(
        spec = lookupSpec,
        catalogSources = createCatalogSources(context, sharedPref),
    )

    fun prewarmUrduSubtitlesAsync(
        imdbId: String?,
        tmdbId: Int? = null,
        title: String?,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = season == null,
        sharedPref: android.content.SharedPreferences? = null,
    ) {
        val cleanImdb = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (cleanImdb == null && cleanTitle == null && (tmdbId == null || tmdbId <= 0)) return
        val titles = listOfNotNull(cleanTitle)
        val spec = if (isMovie || season == null || season < 0) {
            MediaLookupSpec.Movie(
                imdbId = cleanImdb,
                tmdbId = tmdbId?.takeIf { it > 0 },
                titles = titles,
                releaseYear = year,
            )
        } else {
            MediaLookupSpec.Episode(
                imdbId = cleanImdb,
                tmdbId = tmdbId?.takeIf { it > 0 },
                titles = titles,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season, episode?.takeIf { it > 0 } ?: 1),
                seriesPremiereYear = year,
                seasonAirYear = year,
            )
        }
        prewarmMetadataAsync(lookupSpec = spec, sharedPref = sharedPref)
    }

    fun normalizeUrduTypography(input: String): String =
        UrduOrthographyGate.normalizeUrduTypography(input)

    fun containsVerifiedUrduScript(rawContent: String): Boolean =
        UrduOrthographyGate.containsVerifiedUrduScript(rawContent)

    fun containsVerifiedUrduScript(cues: List<CueEntry>): Boolean =
        UrduOrthographyGate.containsVerifiedUrduCueTexts(cues.map { it.text })

    fun decodeAndNormalizeUrduBytes(
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

    suspend fun materializeVerifiedUrduSubtitleFile(
        url: String,
        headers: Map<String, String> = emptyMap(),
        season: Int? = null,
        episode: Int? = null,
        sourceTag: String = "subdl",
    ): String? = materializeNormalizedUrduSubtitle(
        url = url,
        headers = headers,
        season = season,
        episode = episode,
        sourceTag = sourceTag,
    )

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

    private fun readNamedSharedPrefString(
        prefsName: String,
        key: String,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null,
    ): String? {
        val appCtx = context?.applicationContext ?: context ?: appContextRef
        if (appCtx != null) {
            val v = runCatching {
                appCtx.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)?.getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) return v.trim()
        }
        return readCrossModuleSharedPrefString(key, sharedPref, context)
    }

    private fun readCrossModuleSharedPrefString(
        key: String,
        sharedPref: android.content.SharedPreferences? = null,
        context: android.content.Context? = null,
    ): String? {
        runCatching { sharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        runCatching { StreamPlay.companionSharedPref?.getString(key, null) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        val appCtx = context?.applicationContext ?: context ?: appContextRef ?: return null
        val prefNames = listOf(
            SUBTITLE_BOLCHAL_PREFS_NAME,
            STREAMPLAY_PREFS_NAME,
            SUPERSTREAM_PREFS_NAME,
            SUBTITLE_MODE_PREFS_NAME,
        )
        for (prefName in prefNames) {
            val v = runCatching {
                appCtx.getSharedPreferences(prefName, android.content.Context.MODE_PRIVATE)?.getString(key, null)
            }.getOrNull()
            if (!v.isNullOrBlank()) return v.trim()
        }
        return null
    }
}
