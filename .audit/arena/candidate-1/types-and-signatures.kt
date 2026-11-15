@file:Suppress("unused", "UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

/*
 * ================================================================================================
 * CANDIDATE 1 — DEEP DOMAIN-MODULE FACADE: TYPES, SIGNATURES & INVARIANTS SKETCH
 * ================================================================================================
 * Multi-file / multi-repo architectural type sketch. Each section specifies its exact repository,
 * file path, and Kotlin `package` header.
 *
 *   PART I  — `cloudstream-extensions-phisher`
 *             `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/*`
 *             Compiled into both `:StreamPlay` and `:SuperStream` via AGP `kotlin.directories`.
 *
 *   PART II — `cloudstream` (Host App)
 *             II.A `io.github.anilbeesetti.nextlib.media3ext.ffdecoder` (`NextlibFfmpegAudioBridge`)
 *             II.B `com.lagradost.cloudstream3.subtitles.sync` (`SubtitleTimelineAligner`, `LiveAudioSyncSession`)
 *             II.C `com.lagradost.cloudstream3.subtitles.bolchal` (`BolChalUrduLocalizer`)
 *             II.D `com.lagradost.cloudstream3.utils.downloader` (`DownloadSubtitlePolicy`, `SidecarManifest` v2)
 *             II.E `com.lagradost.cloudstream3.ui.player` (`Syncing*RenderersFactory`, `CustomSubtitleDecoderFactory`)
 * ================================================================================================
 */

// ================================================================================================
// PART I: EXTENSION REPOSITORY (`cloudstream-extensions-phisher`)
// Target Package: `package com.phisher98.subtitles`
// Target Root:    `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/`
// ================================================================================================

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleMediaDescriptor.kt ---
// package com.phisher98.subtitles

/**
 * Canonical cross-repo wire headers attached to `SubtitleFile.headers` / `SubtitleData.headers`.
 * Never constructed manually by plugin callers (`StreamPlay`, `StreamPlayAnime`, `SuperStream`).
 */
object SubtitleWireHeaders {
    const val SOURCE = "X-StreamPlay-Source"
    const val DONOR_SOURCE = "X-StreamPlay-Donor-Source"
    const val SYNC_ROLE = "X-StreamPlay-Sync-Role"
    const val SYNC_ANCHOR = "X-StreamPlay-Sync-Anchor"
    const val SYNC_CONFIDENCE = "X-StreamPlay-Sync-Confidence"
    const val SYNC_FPS = "X-StreamPlay-Sync-Fps"
    const val MT_SCORE = "X-StreamPlay-MT-Score"

    const val ROLE_PROVIDER_ANCHOR = "provider-anchor"
    const val ROLE_STREAM_NATIVE_URDU = "stream-native-urdu"
    const val ROLE_ALASS_PREALIGNED = "alass-prealigned"
    const val ROLE_CATALOGUE_RAW = "catalogue-raw"
    const val ROLE_BOLCHAL_LOCALIZED = "bolchal-localized"
}

/**
 * Verified TMDB `(season, episode)` pair for multi-cour anime.
 *
 * Invariant (`per encode-lessons-in-structure`): Cannot represent unverified `season = 1` guesses.
 * When `AnimeEpisode.verifiedTmdb == null`, season-indexed TV catalogues (`subdl`, `subsource`,
 * `wyziesub`, `opensubtitles`, `addic7ed`) are structurally skipped instead of fetching Season 1
 * subtitles for a Season 2/3/4 cour.
 */
data class VerifiedTmdbEpisode(val season: Int, val episode: Int) {
    init {
        require(season > 0) { "Verified TMDB season must be > 0, got $season" }
        require(episode > 0) { "Verified TMDB episode must be > 0, got $episode" }
    }
}

/**
 * Boundary-validated media descriptor carried from `load()` -> `LinkData` -> `loadLinks()`.
 *
 * Invariants:
 * 1. `TvEpisode` separates [seriesDebutYear] (used by SubDL / SubSource show-slug verification)
 *    from [seasonAirYear], eliminating Season 2+ year-mismatch drops.
 * 2. [expectedDurationMs] and [releaseHint] preserve TMDB/ani.zip runtime and release filenames
 *    (`NF.WEB-DL`, `BluRay`) so FPS/runtime candidate scoring never uses static guesses.
 */
sealed interface SubtitleMediaDescriptor {
    val imdbId: String?
    val tmdbId: Int?
    val titleVariants: List<String>
    val expectedDurationMs: Long?
    val releaseHint: String?

    data class Movie(
        override val imdbId: String?,
        override val tmdbId: Int?,
        override val titleVariants: List<String>,
        val releaseYear: Int?,
        override val expectedDurationMs: Long? = null,
        override val releaseHint: String? = null,
        val isBollywood: Boolean = false,
        val isAsian: Boolean = false,
    ) : SubtitleMediaDescriptor

    data class TvEpisode(
        override val imdbId: String?,
        override val tmdbId: Int?,
        val tvdbId: Int? = null,
        override val titleVariants: List<String>,
        val seriesDebutYear: Int?,
        val seasonAirYear: Int?,
        val season: Int,
        val episode: Int,
        override val expectedDurationMs: Long? = null,
        override val releaseHint: String? = null,
        val isAsian: Boolean = false,
    ) : SubtitleMediaDescriptor {
        init {
            require(season > 0 && episode > 0) { "TV season/episode must be > 0: S${season}E${episode}" }
        }
    }

    data class AnimeEpisode(
        val anilistId: Int?,
        val malId: Int?,
        override val imdbId: String?,
        override val tmdbId: Int?,
        override val titleVariants: List<String>,
        val releaseYear: Int?,
        val episode: Int,
        val verifiedTmdb: VerifiedTmdbEpisode?,
        override val expectedDurationMs: Long? = null,
        override val releaseHint: String? = null,
        val isMovie: Boolean = false,
    ) : SubtitleMediaDescriptor
}

enum class SubtitleSurface {
    STREAM_PLAY,
    STREAM_PLAY_ANIME,
    STREAM_PLAY_STREMIO,
    SUPER_STREAM,
}

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleModeBridge.kt ---
// package com.phisher98.subtitles

data class BolChalLlmConfig(
    val endpointUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Long = 6_500L,
)

/**
 * Cross-classloader mode, host-capability, and optional LLM config reader shared across all plugins.
 */
object SubtitleModeBridge {
    const val SUBTITLE_MODE_SYS_PROP = "cloudstream.subtitle.mode"
    const val HOST_VAULT_V2_SYS_PROP = "cloudstream.subtitle.vault_v2"
    const val BILINGUAL_CALLBACK_MARKER = "cloudstream.bilingual_download_subtitles=true"
    const val PREFS_NAME = "cloudstream_subtitle_mode_prefs"

    enum class Mode(val id: String, val langTag: String) {
        DEFAULT("default", "en"),
        URDU("urdu", "ur"),
    }

    fun currentMode(sharedPrefs: android.content.SharedPreferences? = null): Mode = TODO("not implemented")
    fun isUrduMode(sharedPrefs: android.content.SharedPreferences? = null): Boolean =
        currentMode(sharedPrefs) == Mode.URDU
    fun setMode(mode: Mode, sharedPrefs: android.content.SharedPreferences? = null): Unit = TODO("not implemented")
    fun isHostVaultV2Active(): Boolean = System.getProperty(HOST_VAULT_V2_SYS_PROP) == "true"
    fun isBilingualDownloadCallback(callback: Any?): Boolean = TODO("not implemented")
    fun readBolChalLlmConfig(sharedPrefs: android.content.SharedPreferences? = null): BolChalLlmConfig? =
        TODO("not implemented")
}

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleSessionCoordinator.kt ---
// package com.phisher98.subtitles

data class RetryableVideoProvider(
    val providerId: String,
    val priorityBoost: Float = 0f,
    val invoke: suspend (subtitleSink: (com.lagradost.cloudstream3.SubtitleFile) -> Unit) -> Unit,
)

data class SessionSubtitleStats(
    val englishSourcesEmitted: Int,
    val uniqueHumanUrduTimingsEmitted: Int,
    val nativeProviderUrduEmitted: Int,
    val bolChalLocalizedEmitted: Int,
    val alassAlignedCount: Int,
)

/**
 * Per-`loadLinks` deep domain coordinator replacing all inline callback closures and Phase 3-5
 * loops across `StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, and `SuperStream`.
 *
 * State Ownership Invariant (`per separate-before-serializing-shared-state`):
 * - Every mutable donor map, `SubtitleCat` `-orig.srt` candidate list, release-hint map, and
 *   content-SHA256 diversity ledger lives inside this session instance.
 * - Zero global mutable maps are read or wiped across concurrent episode downloads.
 */
class SubtitleSessionCoordinator private constructor(
    val descriptor: SubtitleMediaDescriptor,
    val surface: SubtitleSurface,
    val mode: SubtitleModeBridge.Mode,
    private val bilingualCallbackActive: Boolean,
    private val hostVaultV2Active: Boolean,
    private val llmConfig: BolChalLlmConfig?,
    private val downstreamCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
    private val onSubtitleDelivered: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
) {
    companion object {
        fun open(
            descriptor: SubtitleMediaDescriptor,
            surface: SubtitleSurface,
            downstreamCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
            sharedPrefs: android.content.SharedPreferences? = null,
            onSubtitleDelivered: (com.lagradost.cloudstream3.SubtitleFile) -> Unit = {},
        ): SubtitleSessionCoordinator = TODO("not implemented")

        /**
         * Metadata-only detail-page prewarm (warms SubDL/SubSource slug & IMDb->ID resolution in an
         * in-memory LRU without writing or evicting active CAS `.vtt` files needed by queued downloads).
         */
        fun prewarmMetadataAsync(
            descriptor: SubtitleMediaDescriptor,
            sharedPrefs: android.content.SharedPreferences? = null,
        ): Unit = TODO("not implemented")
    }

    /**
     * Returns `1` in `URDU` mode and [defaultEnglishMin] in `DEFAULT` mode so `StreamPlayAnime`
     * `EarlySatisfactionController` never stalls 18s waiting for 6 human Urdu tracks.
     */
    fun recommendedEarlyMinSubtitles(defaultEnglishMin: Int): Int =
        if (mode == SubtitleModeBridge.Mode.URDU) 1 else defaultEnglishMin

    /**
     * Enriches release lineage (`NF.WEB-DL`, `BluRay`, `25fps`) and stream duration discovered
     * mid-flight by video extractors (e.g., `SuperStream` FebBox `fileList.fileName`).
     */
    fun recordVideoReleaseHint(
        providerId: String,
        releaseText: String?,
        durationMs: Long? = null,
    ): Unit = TODO("not implemented")

    /**
     * Returns a thread-safe `(SubtitleFile) -> Unit` sink bound to [providerId].
     *
     * // TODO:
     * // 1. Normalize URL + strip tracking particles + reject `StreamPlayShield.isBlockedUrl`.
     * // 2. Classify language (`English` vs `Urdu`).
     * // 3. If `isVideoProvider && isUrdu`: materialize & evaluate via `UrduMtDumpDetector`; when
     * //    accepted, emit with `X-StreamPlay-Source = providerId` & `X-StreamPlay-Sync-Role = stream-native-urdu`
     * //    (eliminating the 100% drop of `VidLink`/`Cineby`/`CineSrc`/`MeowTV`/`FebBox` native Urdu tracks).
     * // 4. If `isEnglish` in `URDU` mode: store in priority-ordered `anchorDonors` keyed by SOTA
     * //    provider rank (`VidLink 100 > MeowTV 98 > Cineby 95 > CineSrc 94 > MegaPlay 92 > AniZone 89`
     * //    > verified catalogues > `SubtitleCat` strictly LAST — never arrival race order!).
     * //    When `bilingualCallbackActive || (hostVaultV2Active && isVideoProvider)`, ALSO forward
     * //    to `downstreamCallback` stamped `X-StreamPlay-Sync-Role = provider-anchor`.
     * // 5. Deduplicate human Urdu diversity by normalized cue-timing/text SHA-256 (`uniqueUrduHashes`),
     * //    preventing `Wyzie` + `SubDL` serving the same `.srt` from counting as 2 distinct timings.
     */
    fun sinkFor(
        providerId: String,
        isVideoProvider: Boolean = false,
    ): (com.lagradost.cloudstream3.SubtitleFile) -> Unit = TODO("not implemented")

    /**
     * Schedules Phase 1 non-video catalogue harvesters (`UnifiedSubtitleCatalogues` — `private` to
     * `shared/subtitles`), honoring `AnimeEpisode.verifiedTmdb` and suppressing broadcast-cut anime
     * catalogues when anime-native sidecar providers are active.
     */
    suspend fun harvestPrimaryCatalogues(concurrency: Int = 6): Unit = TODO("not implemented")

    /**
     * Terminal idempotent barrier executing Phases 3, 4, and 5:
     * // TODO:
     * // 1. Phase 3: Retry unanswered English (`5` Movie/TV, `6` Anime) or human Urdu (`2` unique
     * //    content-SHA256 timings) tasks + `retryVideoProviders` within bounded wall-clock cap.
     * // 2. Sidecar `alass` pre-alignment: if a stream-synced English anchor exists in `anchorDonors`,
     * //    align any unaligned human catalogue Urdu cues via `AlassTimelineSolver.alignCuesToReference`
     * //    (`< 25 ms`), writing aligned `.vtt` to `SubtitleCasStore` (256-file / 64 MiB active budget,
     * //    never containing `"superstream"` in filename).
     * // 3. Phase 4 (`BolChalUrduLocalizer.localizeDonor`): trigger when `uniqueHumanUrduTimings == 0`
     * //    OR a stream-synced video-provider English anchor exists and all catalogue Urdu candidates
     * //    had `alass` confidence `< 0.55` or `StiffEditedMt` (`30..59`). Translate top stream-synced
     * //    donor FIRST (`SubtitleCat` `-orig.srt` LAST), stamping `X-StreamPlay-Donor-Source`.
     * // 4. Phase 5: If 0 Urdu tracks were emitted, flush top 2 English donors so user never gets 0 subs.
     */
    suspend fun completeAndFinalize(
        concurrency: Int = 6,
        retryVideoProviders: List<RetryableVideoProvider> = emptyList(),
    ): Unit = TODO("not implemented")

    fun snapshotStats(): SessionSubtitleStats = TODO("not implemented")
}

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/AlassTimelineSolver.kt ---
// package com.phisher98.subtitles

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

/**
 * Pure-Kotlin `alass` $O(1)$-amortized piecewise split + 9-FPS drift solver (`dt = 10 ms`).
 * Internal `RatingDeltaDelta` arrays, monotonic split deque, and RLE backpointer tables are `private`.
 */
object AlassTimelineSolver {
    val CANONICAL_FPS_RATIOS: DoubleArray = doubleArrayOf(
        1.0,
        24.0 / 23.976024,
        23.976024 / 24.0,
        25.0 / 23.976024,
        23.976024 / 25.0,
        25.0 / 24.0,
        24.0 / 25.0,
        29.970030 / 23.976024,
        23.976024 / 29.970030,
    )

    data class TimedSpan(val startMs: Long, val endMs: Long, val weight: Float = 1.0f)

    data class AffineSegment(
        val startCueIndex: Int,
        val endCueIndexExclusive: Int,
        val fpsRatio: Double,
        val offsetMs: Long,
    )

    sealed interface AlignmentResult {
        data class Aligned(
            val warpedCues: List<SubtitleCue>,
            val segments: List<AffineSegment>,
            val dominantFpsRatio: Double,
            val medianResidualMs: Long,
            val confidence: Double,
        ) : AlignmentResult

        data class Unchanged(
            val cues: List<SubtitleCue>,
            val confidence: Double,
            val reason: String,
        ) : AlignmentResult

        data class RejectedMismatch(
            val bestScore: Double,
            val reason: String,
        ) : AlignmentResult
    }

    fun alignCuesToReference(
        candidate: List<SubtitleCue>,
        reference: List<TimedSpan>,
        videoDurationMs: Long? = null,
        splitPenalty: Float = 7.0f,
    ): AlignmentResult = TODO("not implemented")
}

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/UrduMtDumpDetector.kt ---
// package com.phisher98.subtitles

enum class MtSignalFamily {
    POLYSEMY_IDIOM_CALQUE,
    PASSIVE_COPULA_SYNTAX,
    PRONOUN_WHIPLASH,
    CONTINUATION_SHATTERING,
    BOLCHAL_PARTICLE_DEFICIT,
    BULK_MT_AUTHOR_OR_FLAG,
}

enum class MtRejectReason {
    HEARING_IMPAIRED_SDH,
    BULK_MT_UPLOADER,
    NON_URDU_SCRIPT,
    BROKEN_ASCII_SMT,
    MODERN_NMT_LITERAL_DUMP,
    EMPTY_OR_CORRUPT,
}

data class CatalogueCandidateMeta(
    val sourceKey: String,
    val releaseName: String = "",
    val uploaderName: String? = null,
    val isHearingImpairedFlag: Boolean = false,
    val isMachineTranslatedFlag: Boolean = false,
    val declaredFps: Double? = null,
    val declaredDurationMs: Long? = null,
    val requestedSeason: Int? = null,
    val requestedEpisode: Int? = null,
)

/**
 * 5-signal-family composite Urdu authenticity & NMT-dump detector (`mtDumpScore < 30` authentic human;
 * `30..59` stiff/edited MT; `>= 60` raw literal Google Translate dump -> rejected).
 */
object UrduMtDumpDetector {
    sealed interface MtAuthenticityVerdict {
        val mtDumpScore: Int

        data class AuthenticHuman(
            override val mtDumpScore: Int,
            val urduScriptRatio: Double,
        ) : MtAuthenticityVerdict

        data class StiffEditedMt(
            override val mtDumpScore: Int,
            val signals: Set<MtSignalFamily>,
        ) : MtAuthenticityVerdict

        data class RejectedDump(
            override val mtDumpScore: Int,
            val reason: MtRejectReason,
            val signals: Set<MtSignalFamily>,
        ) : MtAuthenticityVerdict
    }

    data class DecodedUrduCandidate(
        val verdict: MtAuthenticityVerdict,
        val normalizedCues: List<SubtitleCue>,
        val contentTimingSha256: String,
    )

    fun evaluate(
        rawBytes: ByteArray,
        metadata: CatalogueCandidateMeta,
        englishDonorCues: List<SubtitleCue>? = null,
    ): DecodedUrduCandidate = TODO("not implemented")
}

// --- File: shared/subtitles/src/main/kotlin/com/phisher98/subtitles/BolChalUrduLocalizer.kt ---
// package com.phisher98.subtitles

enum class LocalizationBackend {
    USER_CONFIGURED_LLM,
    KEYLESS_SCENE_PARAGRAPH_NMT,
}

/**
 * Scene-windowed `[cue_id]` (`⟦0014⟧`) Bol-Chal Urdu localizer with speaker-register lock (`تم`/`آپ`/`تو`),
 * Urdu-script everyday code-switching, `0%` Nastaliq pre-splicing, `0%` sub-batch cue drop (`>= 98%`
 * survival gate + composite `(donorHash, season, episode, engineVer)` cache key), and `visibleCps <= 17.0`
 * silence-extension + syntactic split waterfall.
 */
object ExtBolChalUrduLocalizer { // Named `BolChalUrduLocalizer` in `package com.phisher98.subtitles`
    sealed interface LocalizationResult {
        data class Success(
            val webVtt: String,
            val cues: List<SubtitleCue>,
            val backendUsed: LocalizationBackend,
            val qeScore: Double,
            val maxVisibleCps: Double,
        ) : LocalizationResult

        data class RejectedQuality(
            val reason: String,
            val cueSurvivalRatio: Double,
            val qeScore: Double,
        ) : LocalizationResult
    }

    suspend fun localizeDonor(
        donorCues: List<SubtitleCue>,
        descriptor: SubtitleMediaDescriptor,
        llmConfig: BolChalLlmConfig? = null,
    ): LocalizationResult = TODO("not implemented")
}


// ================================================================================================
// PART II: HOST REPOSITORY (`cloudstream`)
// ================================================================================================

// --- File: app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/NextlibFfmpegAudioBridge.kt ---
// package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

/**
 * Internal streaming packet decoder interface hiding `FfmpegAudioDecoder` and `MediaCodec`.
 */
internal interface AudioPacketDecoder : java.io.Closeable {
    val sampleRateHz: Int
    val channelCount: Int

    fun decodePacket(
        data: ByteArray,
        offset: Int,
        length: Int,
        presentationTimeUs: Long,
        onPcmFrame: (
            pcmBytes: java.nio.ByteBuffer,
            framePtsUs: Long,
            sampleRate: Int,
            channels: Int,
            pcmEncoding: Int,
        ) -> Unit,
    )

    fun flushDecoder(
        onPcmFrame: (
            pcmBytes: java.nio.ByteBuffer,
            framePtsUs: Long,
            sampleRate: Int,
            channels: Int,
            pcmEncoding: Int,
        ) -> Unit,
    )
}

/**
 * Same-package zero-reflection bridge to `nextlib`'s package-private `FfmpegAudioDecoder` and
 * `FfmpegLibrary.getCodecName(String)` across all 18 FFmpeg audio codecs (`EAC3`, `AC3`, `AAC`,
 * `Opus`, `Vorbis`, `MP3`, `FLAC`, `DTS`). Synthesizes 2-byte `AudioSpecificConfig` (`csd-0`)
 * when raw MPEG-TS ADTS AAC frames lack `Format.initializationData`.
 */
internal object NextlibFfmpegAudioBridge {
    fun openSoftwareDecoderOrNull(format: androidx.media3.common.Format): AudioPacketDecoder? =
        TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/PcmSpeechVadEngine.kt ---
// package com.lagradost.cloudstream3.subtitles.sync

/**
 * Allocation-free 16 kHz Speech-Formant + Wiener Spectral Flatness + Rainer Martin Noise Floor +
 * 4 Hz Syllabic Modulation VAD (`< 0.03%` CPU, `dt = 10 ms` = 160 samples, 256-pt in-place real FFT).
 *
 * Internal to `com.lagradost.cloudstream3.subtitles.sync` (`Importable internals` guard):
 * - 5.1/7.1 surround (`channels >= 6`): extracts `0.70f * FC + 0.15f * (FL + FR)` (+10-15 dB SNR).
 * - Stereo (`channels == 2`): tracks Mid/Side energy ratio `rhoSide` to down-weight wide-stereo
 *   orchestral score & sung anime OP/ED choruses (`dutyCycle5s > 0.86 && meanSideRatio > 0.40` -> `0.25x`).
 * - Pre-emphasis `0.97` + 2nd-order Butterworth HPF `300 Hz` & LPF `3400 Hz` + `SFM <= 0.45` blast gate.
 * - Attack = 20 ms (`>= 7.5 dB` posterior SNR), Hangover = 150 ms (`>= 4.0 dB`), trims 120 ms tail on release.
 */
internal class PcmSpeechVadEngine {
    fun reset(): Unit = TODO("not implemented")

    fun feedPcmBuffer(
        pcmBuffer: java.nio.ByteBuffer,
        presentationTimeUs: Long,
        sampleRateHz: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ): Unit = TODO("not implemented")

    fun drainCompletedSpans(): List<SpeechVadSpan> = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/SubtitleTimelineAligner.kt ---
// package com.lagradost.cloudstream3.subtitles.sync

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class ObservedWindowMs(
    @com.fasterxml.jackson.annotation.JsonProperty("startMs") val startMs: Long = 0L,
    @com.fasterxml.jackson.annotation.JsonProperty("endMs") val endMs: Long = 0L,
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechVadSpan(
    @com.fasterxml.jackson.annotation.JsonProperty("startMs") val startMs: Long = 0L,
    @com.fasterxml.jackson.annotation.JsonProperty("endMs") val endMs: Long = 0L,
    @com.fasterxml.jackson.annotation.JsonProperty("confidence") val confidence: Float = 1.0f,
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechActivitySummary(
    @com.fasterxml.jackson.annotation.JsonProperty("windows") val windows: List<ObservedWindowMs> = emptyList(),
    @com.fasterxml.jackson.annotation.JsonProperty("speechSpans") val speechSpans: List<SpeechVadSpan> = emptyList(),
    @com.fasterxml.jackson.annotation.JsonProperty("durationMs") val durationMs: Long = 0L,
    @com.fasterxml.jackson.annotation.JsonProperty("audioStreamHash") val audioStreamHash: String = "",
)

enum class SyncReferenceKind {
    AUDIO_VERIFIED_PROVIDER_ANCHOR,
    SPARSE_AUDIO_VAD_ONLY,
    PROVIDER_SIDECAR_ONLY,
    LIVE_AUDIO_SINK_CLOSED_LOOP,
    DURATION_SANITY_ONLY,
}

data class OfflineVideoSource(
    val fileInfo: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo,
    val localVideoFile: java.io.File?,
    val stagedAudioFile: java.io.File? = null,
    val expectedDurationMs: Long? = null,
)

sealed interface VideoSyncReference {
    val kind: SyncReferenceKind
    val videoDurationMs: Long?
    val audioSummary: SpeechActivitySummary?

    data class DenseAndAudioVerified(
        val anchorProviderKey: String,
        val verifiedAnchorSpans: List<SpeechVadSpan>,
        override val audioSummary: SpeechActivitySummary,
        override val videoDurationMs: Long?,
    ) : VideoSyncReference {
        override val kind: SyncReferenceKind = SyncReferenceKind.AUDIO_VERIFIED_PROVIDER_ANCHOR
    }

    data class SparseAudioVad(
        override val audioSummary: SpeechActivitySummary,
        override val videoDurationMs: Long?,
    ) : VideoSyncReference {
        override val kind: SyncReferenceKind = SyncReferenceKind.SPARSE_AUDIO_VAD_ONLY
    }

    data class DenseProviderOnly(
        val anchorProviderKey: String,
        val providerSpans: List<SpeechVadSpan>,
        override val videoDurationMs: Long?,
    ) : VideoSyncReference {
        override val kind: SyncReferenceKind = SyncReferenceKind.PROVIDER_SIDECAR_ONLY
        override val audioSummary: SpeechActivitySummary? = null
    }

    data class DurationOnly(
        override val videoDurationMs: Long?,
    ) : VideoSyncReference {
        override val kind: SyncReferenceKind = SyncReferenceKind.DURATION_SANITY_ONLY
        override val audioSummary: SpeechActivitySummary? = null
    }
}

data class AffineTimeSegment(
    val startSubUs: Long,
    val endSubUsExclusive: Long,
    val scale: Double,
    val offsetUs: Long,
) {
    fun mapSubToMediaUs(subTimeUs: Long): Long =
        (subTimeUs.toDouble() * scale).toLong() + offsetUs
}

/**
 * Monotonically non-decreasing piecewise-affine subtitle timeline map $t_{\text{media}} = \alpha_k t_{\text{sub}} + \beta_k$.
 */
data class PiecewiseAffineWarp(
    val segments: List<AffineTimeSegment>,
    val dominantFpsRatio: Double = 1.0,
    val confidence: Double = 1.0,
) {
    companion object {
        val IDENTITY = PiecewiseAffineWarp(
            segments = listOf(AffineTimeSegment(Long.MIN_VALUE / 4, Long.MAX_VALUE / 4, 1.0, 0L)),
            dominantFpsRatio = 1.0,
            confidence = 1.0,
        )
    }

    fun mapSubToMediaUs(subTimeUs: Long): Long = TODO("not implemented")
    fun inverseMediaToSubUs(mediaTimeUs: Long): Long = TODO("not implemented")
}

data class CommittedTimeWarp(
    val generation: Long,
    val autoWarp: PiecewiseAffineWarp,
    val userOffsetUs: Long,
    val freezeFloorMediaUs: Long,
)

sealed interface SyncOutcome {
    data class Aligned(
        val alignedWebVtt: String,
        val warp: PiecewiseAffineWarp,
        val dominantOffsetMs: Long,
        val fpsRatio: Double,
        val piecewiseSplitCount: Int,
        val syncConfidence: Double,
        val referenceKind: SyncReferenceKind,
    ) : SyncOutcome

    data class VerifiedAsIs(
        val webVtt: String,
        val syncConfidence: Double,
        val referenceKind: SyncReferenceKind,
    ) : SyncOutcome

    data class LowConfidenceUnaligned(
        val webVtt: String,
        val syncConfidence: Double,
        val reason: String,
    ) : SyncOutcome

    data class RejectedMismatch(
        val bestScore: Double,
        val reason: String,
    ) : SyncOutcome
}

/**
 * Deep host alignment facade replacing `SubtitleSyncEngine` across downloads, offline heals, and
 * live pre-mount materialization.
 */
object SubtitleTimelineAligner {
    suspend fun prepareOfflineReference(
        context: android.content.Context,
        source: OfflineVideoSource,
        providerAnchorVtt: String?,
        providerAnchorKey: String?,
        cachedSummary: SpeechActivitySummary? = null,
        isAnime: Boolean = false,
    ): VideoSyncReference = TODO("not implemented")

    /**
     * // TODO:
     * // 1. Dense reference (`DenseAndAudioVerified` / `DenseProviderOnly`): run 9-FPS `RatingDeltaDelta`
     * //    (`dt = 10 ms`) + top-2 monotonic-deque piecewise split DP (`P_split = 7.0`) + onset polish.
     * // 2. Sparse reference (`SparseAudioVad`): run observation-masked zero-mean `alass` scoring
     * //    (`+w` speech overlap inside `windows`, `-0.25w` confirmed-silence overlap inside `windows`,
     * //    exact `0` outside `windows` so unobserved cues never incentivize false splits; `P_split = 8.5`)
     * //    + `vadOnsetMs - 35 ms` median onset residual polish.
     * // 3. Bake warped timestamps into WebVTT and reject tracks with unresolved episode mismatch.
     */
    fun alignToWebVtt(
        rawWebVtt: String,
        candidateSourceKey: String,
        reference: VideoSyncReference,
    ): SyncOutcome = TODO("not implemented")

    suspend fun alignLiveMaterializedWebVtt(
        rawWebVtt: String,
        subtitle: com.lagradost.cloudstream3.ui.player.SubtitleData,
        anchorWebVtt: String?,
        anchorProviderKey: String?,
        videoDurationMs: Long? = null,
    ): SyncOutcome = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/LiveAudioSyncSession.kt ---
// package com.lagradost.cloudstream3.subtitles.sync

/**
 * Per-playback session owned by `CS3IPlayer` unifying:
 * 1. Lock-free `ForwardingAudioSink` real-time PCM tap (`< 20 µs` on `ExoPlayer:Playback` thread,
 *    HDMI bitstream passthrough safe via `Util.isEncodingLinearPcm`, recording `buffer.position()`
 *    before `super.handleBuffer(...)` and reading only when `super.handleBuffer == true`).
 * 2. Background sparse HTTP `Range` (MP4 `moov` + `elst` + 3 windows) & HLS segment (`#EXT-X-MEDIA:TYPE=AUDIO`
 *    preferred, `rawPtsUs(seg_k) - rawPtsUs(seg_0)` per `#EXT-X-DISCONTINUITY`) audio probing via
 *    `VideoDownloadManager.sanitizeCdnHeaders` without contending on `SimpleCache` span locks.
 * 3. Zero-stutter `DynamicSyncedSubtitle` (`WarpedSubtitle` `private` inner class) + manual `userOffsetMs`:
 *    - Freezes event times `<= lastRenderedPositionUs` and currently visible cues (preserving Media3
 *      `TextRenderer.nextSubtitleEventIndex` monotonicity and preventing mid-read text flicker).
 *    - Applies `45 ms` hysteresis deadband and `<= 35 ms` inter-cue silence slewing for fine drift.
 *    - Invokes [onTextCursorInvalidateNeeded] (text-only track toggle, `0` `seekTime(-1)` calls!)
 *      only when a `> 80 ms` initial cold-open/OP step or manual user delay shifts skipped cues forward.
 */
class LiveAudioSyncSession(
    private val appContext: android.content.Context,
) : java.io.Closeable {
    fun wrapAudioSink(delegate: androidx.media3.exoplayer.audio.AudioSink): androidx.media3.exoplayer.audio.AudioSink =
        TODO("not implemented")

    fun wrapDecodedSubtitle(
        delegate: androidx.media3.extractor.text.Subtitle,
        trackFormat: androidx.media3.common.Format,
    ): androidx.media3.extractor.text.Subtitle = TODO("not implemented")

    fun startForMedia(
        link: com.lagradost.cloudstream3.utils.ExtractorLink?,
        activeSubtitle: com.lagradost.cloudstream3.ui.player.SubtitleData?,
        videoDurationMs: Long? = null,
        onTextCursorInvalidateNeeded: () -> Unit,
    ): Unit = TODO("not implemented")

    fun onActiveSubtitleChanged(
        activeSubtitle: com.lagradost.cloudstream3.ui.player.SubtitleData?,
    ): Unit = TODO("not implemented")

    fun setUserOffsetMs(offsetMs: Long): Unit = TODO("not implemented")

    fun getUserOffsetMs(): Long = TODO("not implemented")

    fun currentWarpSnapshot(): CommittedTimeWarp = TODO("not implemented")

    override fun close(): Unit = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/subtitles/bolchal/BolChalUrduLocalizer.kt ---
// package com.lagradost.cloudstream3.subtitles.bolchal

data class HostBolChalLlmConfig( // Named `BolChalLlmConfig` in `package com.lagradost.cloudstream3.subtitles.bolchal`
    val endpointUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Long = 6_500L,
) {
    companion object {
        fun fromContext(context: android.content.Context? = null): HostBolChalLlmConfig? = TODO("not implemented")
    }
}

data class BolChalMediaContext(
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val isAnime: Boolean = false,
    val donorProviderKey: String? = null,
)

data class UrduAuthenticityReport(
    val isVerifiedUrduScript: Boolean,
    val mtDumpScore: Int,
    val isRejectedDump: Boolean,
    val maxVisibleCps: Double,
    val qeScore: Double,
)

sealed interface BolChalLocalizationOutcome {
    data class Success(
        val webVtt: String,
        val cueCount: Int,
        val qeScore: Double,
        val maxVisibleCps: Double,
        val usedLlmBackend: Boolean,
    ) : BolChalLocalizationOutcome

    data class Rejected(
        val reason: String,
        val qeScore: Double,
        val cueSurvivalRatio: Double,
    ) : BolChalLocalizationOutcome
}

/**
 * Replaces `UrduSubtitleTranslator` across `DownloadManager`, `RepoLinkGenerator`, and `GeneratorPlayer`
 * (`publishToSubtitleCatCdn` deleted completely).
 */
object BolChalUrduLocalizer {
    suspend fun localizeToWebVtt(
        englishWebVttOrSrt: String,
        mediaContext: BolChalMediaContext,
        llmConfig: HostBolChalLlmConfig? = HostBolChalLlmConfig.fromContext(),
        onWindowZeroReady: (suspend (partialWebVtt: String) -> Unit)? = null,
    ): BolChalLocalizationOutcome = TODO("not implemented")

    fun evaluateUrduTrackAuthenticity(
        rawUrduText: String,
        releaseLabel: String = "",
        uploader: String? = null,
        englishReferenceWebVtt: String? = null,
    ): UrduAuthenticityReport = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt ---
// package com.lagradost.cloudstream3.utils.downloader

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class SidecarManifestEntry(
    @com.fasterxml.jackson.annotation.JsonProperty("fileName") val fileName: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("providerKey") val providerKey: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("donorProviderKey") val donorProviderKey: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("sourceLabel") val sourceLabel: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("languageTag") val languageTag: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("isTranslated") val isTranslated: Boolean = false,
    @com.fasterxml.jackson.annotation.JsonProperty("timingSha256") val timingSha256: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("contentSha256") val contentSha256: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("syncConfidence") val syncConfidence: Double = 1.0,
    @com.fasterxml.jackson.annotation.JsonProperty("appliedOffsetMs") val appliedOffsetMs: Long = 0L,
    @com.fasterxml.jackson.annotation.JsonProperty("fpsRatio") val fpsRatio: Double = 1.0,
    @com.fasterxml.jackson.annotation.JsonProperty("piecewiseSplitCount") val piecewiseSplitCount: Int = 0,
    @com.fasterxml.jackson.annotation.JsonProperty("syncSource") val syncSource: SyncReferenceKind = SyncReferenceKind.DURATION_SANITY_ONLY,
    @com.fasterxml.jackson.annotation.JsonProperty("qeScore") val qeScore: Double = 1.0,
    @com.fasterxml.jackson.annotation.JsonProperty("mtDumpScore") val mtDumpScore: Int = 0,
    @com.fasterxml.jackson.annotation.JsonProperty("originalUrl") val originalUrl: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("updatedAtMs") val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Schema v2 of `SidecarManifest` (backward-compatible with v1 JSON on disk):
 * Persists [videoProviderKey], [isAnime], and [audioVadSummary] so offline heal
 * (`ensureSubtitlesForDownloadedEpisode`) retains exact video-provider affinity and audio VAD spans.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class SidecarManifest(
    @com.fasterxml.jackson.annotation.JsonProperty("schemaVersion") val schemaVersion: Int = 2,
    @com.fasterxml.jackson.annotation.JsonProperty("videoDisplayName") val videoDisplayName: String = "",
    @com.fasterxml.jackson.annotation.JsonProperty("videoProviderKey") val videoProviderKey: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("isAnime") val isAnime: Boolean = false,
    @com.fasterxml.jackson.annotation.JsonProperty("videoDurationMs") val videoDurationMs: Long? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("videoFileSize") val videoFileSize: Long? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("videoReleaseHint") val videoReleaseHint: String? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("audioVadSummary") val audioVadSummary: SpeechActivitySummary? = null,
    @com.fasterxml.jackson.annotation.JsonProperty("entries") val entries: List<SidecarManifestEntry> = emptyList(),
    @com.fasterxml.jackson.annotation.JsonProperty("updatedAtMs") val updatedAtMs: Long = System.currentTimeMillis(),
)

object SidecarManifestStore {
    fun loadManifest(
        context: android.content.Context,
        fileInfo: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo,
    ): SidecarManifest? = TODO("not implemented")

    fun recordSavedSidecar(
        context: android.content.Context,
        fileInfo: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo,
        entry: SidecarManifestEntry,
        videoProviderKey: String? = null,
        isAnime: Boolean? = null,
        videoDurationMs: Long? = null,
        videoReleaseHint: String? = null,
        audioVadSummary: SpeechActivitySummary? = null,
    ): SidecarManifest = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadSubtitlePolicy.kt ---
// package com.lagradost.cloudstream3.utils.downloader

enum class SubtitleOriginKind {
    STREAM_PROVIDER,     // VidLink, MeowTV, Cineby, CineSrc, VidFast, MegaPlay, VidRift, AniZone, AnimePahe, Animex
    HUMAN_CATALOGUE,     // SubDL, OpenSubtitles, SubtitleAPI, Wyzie, YIFY, SubSource, SubSense, Addic7ed, Subf2m, SubtitleCat
    BOLCHAL_LOCALIZED,   // UrduAI:<donorSourceKey> — NEVER inherits raw native catalogue priority!
    UNKNOWN,
}

data class SubtitleProvenance(
    val effectiveSourceKey: String,
    val originKind: SubtitleOriginKind,
    val donorSourceKey: String?,
    val displayBadge: String,
    val isStreamSyncedLineage: Boolean,
    val isBannedOrDead: Boolean,
)

data class SubtitleRankingContext(
    val preferredLangTag: String,
    val activeVideo: com.lagradost.cloudstream3.utils.ExtractorLink? = null,
    val activeVideoProviderKey: String? = activeVideo?.let(DownloadSubtitlePolicy::videoProviderKeyOf),
    val isAnime: Boolean = false,
    val releaseHintText: String? = null,
    val manifestByFileName: Map<String, SidecarManifestEntry> = emptyMap(),
)

/**
 * Single source of truth for subtitle provenance resolution, total-order sync-first ranking, and
 * stream-synced slot reservation across `DownloadManager`, `GeneratorPlayer`, `AppContextUtils.sortSubs`,
 * `DownloadFileGenerator`, and `RepoLinkGenerator`.
 */
object DownloadSubtitlePolicy {
    const val MIN_MOVIE_TV_STREAM_SYNCED_SLOTS = 2

    fun targetSubtitlesPerLanguage(isAnime: Boolean): Int = if (isAnime) 6 else 5

    fun videoProviderKeyOf(link: com.lagradost.cloudstream3.utils.ExtractorLink): String? =
        TODO("not implemented")

    /**
     * Boundary parser (`per boundary-discipline`): checks `X-StreamPlay-Source` + `X-StreamPlay-Donor-Source`
     * headers -> `SidecarManifestEntry` -> structured `UrduAI <Donor>` prefix BEFORE scanning `SOURCE_MARKERS`,
     * preventing `UrduAI Wyzie` from ever resolving as native `wyzie` (`110`).
     */
    fun resolveProvenance(
        subtitle: com.lagradost.cloudstream3.ui.player.SubtitleData,
        manifestEntry: SidecarManifestEntry? = null,
    ): SubtitleProvenance = TODO("not implemented")

    fun rankSubtitles(
        candidates: Collection<com.lagradost.cloudstream3.ui.player.SubtitleData>,
        context: SubtitleRankingContext,
    ): List<com.lagradost.cloudstream3.ui.player.SubtitleData> = TODO("not implemented")

    fun selectDownloadCandidates(
        candidates: List<com.lagradost.cloudstream3.ui.player.SubtitleData>,
        context: SubtitleRankingContext,
        maxCount: Int = targetSubtitlesPerLanguage(context.isAnime),
    ): List<com.lagradost.cloudstream3.ui.player.SubtitleData> = TODO("not implemented")
}


// --- File: app/src/main/java/com/lagradost/cloudstream3/ui/player/SyncingRenderersFactories.kt ---
// package com.lagradost.cloudstream3.ui.player

/**
 * Phone / Emulator / HW+SW / SW+HW renderer factory (`profile.useExtensionRenderers == true`).
 */
@androidx.media3.common.util.UnstableApi
class SyncingNextRenderersFactory(
    context: android.content.Context,
    private val syncSession: LiveAudioSyncSession,
) : com.lagradost.cloudstream3.ui.player.FixedNextRenderersFactory(context) {
    override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): androidx.media3.exoplayer.audio.AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return syncSession.wrapAudioSink(base)
    }
}

/**
 * Android TV default (`-1`) & HW-only (`1`) renderer factory (`profile.useExtensionRenderers == false`).
 */
@androidx.media3.common.util.UnstableApi
class SyncingDefaultRenderersFactory(
    context: android.content.Context,
    private val syncSession: LiveAudioSyncSession,
) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): androidx.media3.exoplayer.audio.AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return syncSession.wrapAudioSink(base)
    }
}

/**
 * Updated `CustomSubtitleDecoderFactory` wrapping decoded `Subtitle` instances in
 * `syncSession.wrapDecodedSubtitle(decoded, format)` (`DynamicSyncedSubtitle`) instead of mutating
 * static `CustomDecoder.subtitleOffset` + calling `seekTime(-1)`.
 */
@androidx.media3.common.util.UnstableApi
class CustomSubtitleDecoderFactory(
    private val syncSession: LiveAudioSyncSession? = null,
) : androidx.media3.exoplayer.text.SubtitleDecoderFactory {
    override fun supportsFormat(format: androidx.media3.common.Format): Boolean = TODO("not implemented")
    override fun createDecoder(format: androidx.media3.common.Format): androidx.media3.extractor.text.SubtitleDecoder =
        TODO("not implemented")
}
