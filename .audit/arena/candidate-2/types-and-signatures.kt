@file:Suppress("UNUSED_PARAMETER", "MemberVisibilityCanBePrivate")

/*
 * Candidate 2 Type & Signature Sketch — Issue #17 (Spoken Bol-Chal Urdu & Sample-Accurate Sync)
 *
 * Architectural Angle: Algebraic Type-Safe Pipeline & Pure-Core / Thin-Shell
 *
 * Structure of this file:
 *   Part 1 — Pure-JVM Domain Types & Boundary Invariants (shared identically across
 *            `cloudstream-extensions-phisher/shared/subtitles` and `cloudstream/.../subtitles/{sync,urdu}`)
 *   Part 2 — Pure-JVM 16 kHz Speech VAD & Observation-Masked `AlassAligner`
 *   Part 3 — Pure-JVM `UrduQualityGate` (`UrduMtDumpDetector`) & `SceneBolChalLocalizer` (`CPS <= 17`)
 *   Part 4 — Extension Thin Shell (`EpisodeSubtitleSession`, `SubtitlePipeline`, `UrduCasStore`)
 *   Part 5 — Host Thin Shells (`NextlibFfmpegAudioBridge`, `SparseMediaAudioProber`,
 *            `ForwardingPcmAudioSink`, `WarpedSubtitle`, `SubtitleRankingPolicy`, `SidecarReconciler`)
 */

package com.lagradost.cloudstream3.subtitles.core // mirrored verbatim in `package com.phisher98.subtitles`

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

// =================================================================================================
// PART 1: PURE-JVM DOMAIN TYPES & ALGEBRAIC BOUNDARY INVARIANTS (ZERO ANDROID / MEDIA3 IMPORTS)
// =================================================================================================

/** Stable 1-to-1 cue identifier preserving source cue cardinality across normalization & NMT/LLM. */
@JvmInline
value class CueId(val value: Int) : Comparable<CueId> {
    override fun compareTo(other: CueId): Int = value.compareTo(other.value)
}

/** Lowercase 64-hex SHA-256 digest used for content-addressed caching and idempotent manifest checks. */
@JvmInline
value class ContentSha256 private constructor(val hex: String) {
    companion object {
        fun ofBytes(bytes: ByteArray): ContentSha256 = TODO("not implemented: SHA-256 hex digest")
        fun ofUtf8(text: String): ContentSha256 = ofBytes(text.encodeToByteArray())
        fun parseOrNull(raw: String?): ContentSha256? = TODO("not implemented: validate 64 lowercase hex chars")
    }
}

/**
 * Validated source English cue text guaranteed to contain ZERO Arabic/Urdu Nastaliq code points
 * (`U+0600..U+06FF`, `U+0750..U+077F`, `U+FB50..U+FDFF`, `U+FE70..U+FEFF`).
 *
 * Invariant (`P_lang.3`): Structurally prevents pre-translation regexes from splicing Urdu script
 * into `sl=en&tl=ur` NMT requests and corrupting encoder cross-attention.
 */
@JvmInline
value class LatinEnglishText private constructor(val text: String) {
    companion object {
        /**
         * Strips ASS/HTML bloat, inline SDH annotations (`[door slams]`, `(sighs)`, `MAN:`, `♪`),
         * rewrites ambiguous English idioms into plain literal English (`"what on earth"`, `"hold on"`),
         * and fails closed if any Arabic-script character remains.
         */
        fun normalizeFromRawCue(
            rawText: String,
            preserveItalicTags: Boolean = true,
        ): Result<LatinEnglishText> = TODO("not implemented")
    }
}

enum class SubtitleLanguage(val ietfTag: String, val displayName: String) {
    ENGLISH("en", "English"),
    URDU("ur", "Urdu"),
}

/**
 * Single source of truth for SOTA stream-synced video providers (AGENTS.md §2–3).
 * Separates universal Movie/TV priority from Anime-native priority so `ANIME_NATIVE_SOURCE_PRIORITIES`
 * never leaks into live-action anchor selection.
 */
enum class StreamProviderKey(
    val wireId: String,
    val displayLabel: String,
    val universalRank: Int,
    val animeRank: Int,
    val isAnimeExclusive: Boolean = false,
    val cdnHostTokens: List<String> = emptyList(),
) {
    VIDLINK("vidlink", "VidLink", universalRank = 100, animeRank = 114, cdnHostTokens = listOf("vidlink", "megacloud", "megafiles", "hakunaymatata", "vodvidl")),
    MEOWTV("meowtv", "MeowTV", universalRank = 98, animeRank = 98, cdnHostTokens = listOf("meowtv", "hscow", "vidjoy", "fcxmb", "krico443jez", "rezesubs", "fastcloud")),
    CINEBY("cineby", "Cineby", universalRank = 95, animeRank = 113, cdnHostTokens = listOf("cineby", "paleoak", "majorplay", "wecollege", "dramahot", "goldenkite", "finalsignal", "vidy.st")),
    CINESRC("cinesrc", "CineSrc", universalRank = 94, animeRank = 115, cdnHostTokens = listOf("cinesrc", "bright67", "lightspeed")),
    VIDFAST("vidfast", "VidFast", universalRank = 93, animeRank = 93, cdnHostTokens = listOf("vidfast", "zenoak")),
    MEGAPLAY("megaplay", "MegaPlay", universalRank = 92, animeRank = 126, cdnHostTokens = listOf("megaplay", "broforgotsave", "nexabloom", "embermeadow", "ivorysummit")),
    VIDRIFT("vidrift", "VidRift", universalRank = 91, animeRank = 91, cdnHostTokens = listOf("vidrift", "reelvault", "bigbangcymru", "bigbangobserver", "deton444eik")),
    ANIMEPAHE("animepahe", "AnimePahe", universalRank = 88, animeRank = 128, isAnimeExclusive = true, cdnHostTokens = listOf("animepahe", "uwucdn")),
    ANIZONE("anizone", "AniZone", universalRank = 89, animeRank = 130, isAnimeExclusive = true, cdnHostTokens = listOf("anizone", "xin-cdn", "vid-cdn")),
    ZOKOANIME("zokoanime", "ZokoAnime", universalRank = 88, animeRank = 124, isAnimeExclusive = true, cdnHostTokens = listOf("zokoanime")),
    KICKASSANIME("kickassanime", "KickAssAnime", universalRank = 88, animeRank = 122, isAnimeExclusive = true, cdnHostTokens = listOf("kickassanime", "kaalt")),
    ANIMEX("animex", "Animex", universalRank = 87, animeRank = 120, isAnimeExclusive = true, cdnHostTokens = listOf("animex", "krussdomi", "echovideo", "zokoanimevideo", "dpopdrop89", "megavid")),
    FEBBOX_PACK("febbox", "FebBox", universalRank = 96, animeRank = 96, cdnHostTokens = listOf("febbox", "showbox")),
    ;

    fun rankFor(isAnime: Boolean): Int = if (isAnime) animeRank else universalRank
}

/** Canonical independent subtitle catalogues (never treated as stream-synced video anchors). */
enum class CatalogueSourceKey(
    val wireId: String,
    val displayLabel: String,
    val catalogueQualityRank: Int,
    val wireAliases: List<String> = emptyList(),
    val hostTokens: List<String> = emptyList(),
) {
    WYZIE("wyzie", "Wyzie", 110, wireAliases = listOf("wyziesub"), hostTokens = listOf("wyzie")),
    SUBTITLE_API("subtitleapi", "SubtitleAPI", 105, wireAliases = listOf("subtitles"), hostTokens = listOf("subtitleapi")),
    OPENSUBTITLES("opensubtitles", "OpenSubtitles", 104, hostTokens = listOf("opensubtitles")),
    STREMIO("stremio", "Stremio", 104, hostTokens = listOf("stremio")),
    SUBDL("subdl", "SubDL", 104, hostTokens = listOf("subdl", "dlsubdlcom", "apisubdlcom", "api3subdlcom")),
    ADDIC7ED("addic7ed", "Addic7ed", 103, hostTokens = listOf("addic7ed")),
    YIFY("yify", "YIFY", 103, wireAliases = listOf("yifysub", "yts", "yts-subs", "ytssubs"), hostTokens = listOf("yify", "yifysubtitles", "ytssubs")),
    SUBSOURCE("subsource", "SubSource", 103, hostTokens = listOf("subsource", "apisubsourcenet")),
    SUBSENSE("subsense", "SubSense", 103, hostTokens = listOf("subsense", "subsensenepirawcom")),
    WATCHSOMUCH("watchsomuch", "WatchSoMuch", 102, hostTokens = listOf("watchsomuch")),
    SUBF2M("subf2m", "Subf2m", 101, hostTokens = listOf("subf2m", "subf2mco")),
    SUBTITLECAT("subtitlecat", "SubtitleCat", 99, hostTokens = listOf("subtitlecat")),
}

enum class LocalizationTierKey(val wireId: String, val badgeLabel: String, val tierRank: Int) {
    LLM_SCENE("llm-scene", "BolChal AI", tierRank = 3),
    KEYLESS_BOLCHAL_NMT("keyless-bolchal", "BolChal", tierRank = 2),
    MLKIT_OFFLINE("mlkit-offline", "Offline AI", tierRank = 1),
}

/** Orthogonal donor identity carried inside [ProvenanceIdentity.BolChalTranslated]. */
sealed interface DonorProvenance {
    val wireDonorId: String
    val displayLabel: String
    val isStreamSyncedDonor: Boolean

    data class StreamDonor(val provider: StreamProviderKey) : DonorProvenance {
        override val wireDonorId: String get() = "stream:${provider.wireId}"
        override val displayLabel: String get() = provider.displayLabel
        override val isStreamSyncedDonor: Boolean get() = true
    }

    data class CatalogueDonor(val catalogue: CatalogueSourceKey) : DonorProvenance {
        override val wireDonorId: String get() = "cat:${catalogue.wireId}"
        override val displayLabel: String get() = catalogue.displayLabel
        override val isStreamSyncedDonor: Boolean get() = false
    }
}

/**
 * Algebraic provenance identity separating `originKind` from `donor`.
 *
 * Structural guarantee (`P_sync.2` / `P_lang.1`):
 * `BolChalTranslated(CatalogueDonor(WYZIE), ...)` is a distinct sum branch from `HumanCatalogue(WYZIE)`.
 * No ranker, manifest loader, or filename parser can ever promote `UrduAI Wyzie` to `WYZIE (110)`
 * or disguise a translated `SubtitleCat` `-orig.srt` as `HumanCatalogue(SUBTITLECAT)`.
 */
sealed interface ProvenanceIdentity {
    /** Unique diversity bucket key so `UrduAI VidLink` and `VidLink English` stay distinct per language. */
    val diversityKey: String
    /** Safe sidecar file stem suffix that roundtrips losslessly through [ProvenanceCodec]. */
    val sidecarFileBadge: String

    data class StreamProvider(val provider: StreamProviderKey) : ProvenanceIdentity {
        override val diversityKey: String get() = "stream:${provider.wireId}"
        override val sidecarFileBadge: String get() = provider.displayLabel
    }

    data class HumanCatalogue(val catalogue: CatalogueSourceKey) : ProvenanceIdentity {
        override val diversityKey: String get() = "cat:${catalogue.wireId}"
        override val sidecarFileBadge: String get() = catalogue.displayLabel
    }

    data class BolChalTranslated(
        val donor: DonorProvenance,
        val engine: LocalizationTierKey,
    ) : ProvenanceIdentity {
        override val diversityKey: String get() = "bolchal:${donor.wireDonorId}"
        override val sidecarFileBadge: String get() = "UrduAI ${donor.displayLabel}"
    }

    data class UnknownExternal(val registrableHost: String) : ProvenanceIdentity {
        override val diversityKey: String get() = "ext:$registrableHost"
        override val sidecarFileBadge: String get() = registrableHost
    }
}

/**
 * Single owner of wire header & legacy-filename serialization for [ProvenanceIdentity].
 * Eliminates the `markerForText` declaration-order bug where `"UrduAI Wyzie"` matched `"wyzie"` first.
 */
object ProvenanceCodec {
    const val HEADER_SOURCE = "X-StreamPlay-Source"
    const val HEADER_ORIGIN = "X-StreamPlay-Origin"
    const val HEADER_DONOR = "X-StreamPlay-Donor"
    const val HEADER_ENGINE = "X-StreamPlay-Engine"
    const val HEADER_SYNC_CERT = "X-StreamPlay-Sync"

    fun encodeHeaders(
        provenance: ProvenanceIdentity,
        syncProof: AlignmentProof? = null,
    ): Map<String, String> = TODO(
        """
        not implemented:
        1. StreamProvider(p) -> HEADER_SOURCE=p.wireId, HEADER_ORIGIN="stream"
        2. HumanCatalogue(c) -> HEADER_SOURCE=c.wireId, HEADER_ORIGIN="catalogue"
        3. BolChalTranslated(d, e) -> HEADER_SOURCE="urdu-neural-fallback", HEADER_ORIGIN="bolchal",
           HEADER_DONOR=d.wireDonorId, HEADER_ENGINE=e.wireId
        4. Attach compact HEADER_SYNC_CERT when syncProof != null.
        """
    )

    /**
     * Parses structured v2 headers first; when `headers` are empty (e.g., legacy v1 local sidecars),
     * checks `UrduAI` / `urdu-neural-fallback` / `BolChal` prefix FIRST and parses any trailing
     * provider/catalogue token strictly into [ProvenanceIdentity.BolChalTranslated.donor].
     */
    fun decode(
        headers: Map<String, String>,
        displayName: String,
        urlOrFileName: String,
    ): ProvenanceIdentity = TODO("not implemented")
}

/**
 * Structural episode coordinate preventing multi-cour anime with unverified TMDB mappings from
 * querying season-keyed catalogues (`Wyzie`, `Stremio`, `OpenSubtitles`, `SubDL`, `SubSource`)
 * with a fabricated `season = 1` (Phase A Explorer 1 §3.2).
 */
sealed interface EpisodeCoordinate {
    data class VerifiedSeasonEpisode(val season: Int, val episode: Int) : EpisodeCoordinate {
        init {
            require(season >= 0 && episode >= 1) { "Invalid S${season}E${episode}" }
        }
    }

    /** Lacks a `season` property by construction: season-indexed APIs cannot accept this type. */
    data class UnverifiedAnimeCour(
        val courTitles: List<String>,
        val episodeInCour: Int,
        val absoluteEpisode: Int?,
    ) : EpisodeCoordinate {
        init {
            require(courTitles.isNotEmpty() && episodeInCour >= 1)
        }
    }
}

/**
 * Carries runtime, release-lineage hints, and distinct `seriesPremiereYear` vs `seasonAirYear`
 * across `load() -> LinkData -> loadLinks()` and `FallbackSubtitleHarvester.Request`.
 */
sealed interface MediaLookupSpec {
    val imdbId: String?
    val tmdbId: Int?
    val titles: List<String>
    val expectedDurationMs: Long?
    val releaseHints: List<String>
    val isAnime: Boolean

    data class Movie(
        override val imdbId: String?,
        override val tmdbId: Int?,
        override val titles: List<String>,
        val releaseYear: Int?,
        override val expectedDurationMs: Long?,
        override val releaseHints: List<String> = emptyList(),
        override val isAnime: Boolean = false,
    ) : MediaLookupSpec

    data class Episode(
        override val imdbId: String?,
        override val tmdbId: Int?,
        override val titles: List<String>,
        /** Show debut year — required by SubDL & SubSource TV slug matchers on Season 2+ shows! */
        val seriesPremiereYear: Int?,
        val seasonAirYear: Int?,
        override val expectedDurationMs: Long?,
        override val releaseHints: List<String> = emptyList(),
        val coordinate: EpisodeCoordinate,
        override val isAnime: Boolean = false,
    ) : MediaLookupSpec
}

data class CueSpan(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "Cue endMs ($endMs) < startMs ($startMs)" }
    }
    val durationMs: Long get() = endMs - startMs
}

data class CueEntry(
    val id: CueId,
    val span: CueSpan,
    val text: String,
)

data class CueDocument(
    val language: SubtitleLanguage,
    val cues: List<CueEntry>,
    val timingSha256: ContentSha256,
    val contentSha256: ContentSha256,
) {
    companion object {
        fun parse(rawDecodedText: String, language: SubtitleLanguage): Result<CueDocument> =
            TODO("not implemented: parse VTT/SRT/ASS/TTML into sorted non-empty CueEntry list + digests")
    }
}

/**
 * Exact rational framerate ratios (`num / den`) tested by `AlassAligner` prior to piecewise DP.
 * Integer `(timeUs / den) * num + ((timeUs % den) * num) / den` arithmetic yields 0 us float drift.
 */
enum class FpsRatio(val id: String, val num: Long, val den: Long) {
    UNITY("1:1", 1L, 1L),
    FILM_24_TO_NTSC_23976("24/23.976", 1001L, 1000L),
    NTSC_23976_TO_FILM_24("23.976/24", 1000L, 1001L),
    PAL_25_TO_NTSC_23976("25/23.976", 1001L, 960L),
    NTSC_23976_TO_PAL_25("23.976/25", 960L, 1001L),
    PAL_25_TO_FILM_24("25/24", 25L, 24L),
    FILM_24_TO_PAL_25("24/25", 24L, 25L),
    NTSC_2997_TO_NTSC_23976("29.97/23.976", 5L, 4L),
    NTSC_23976_TO_NTSC_2997("23.976/29.97", 4L, 5L),
    ;

    fun scaleUs(timeUs: Long): Long = (timeUs / den) * num + ((timeUs % den) * num) / den
    fun inverseScaleUs(mediaUs: Long): Long = (mediaUs / num) * den + ((mediaUs % num) * den) / num
}

data class WarpKnot(
    val subTimeUs: Long,
    val mediaTimeUs: Long,
)

/**
 * Closed algebraic time-warp shared identically between disk WebVTT baking ([WebVttBaker])
 * and zero-stutter live Media3 playback ([com.lagradost.cloudstream3.subtitles.sync.WarpedSubtitle]).
 *
 * Invariant (`P_sync.3`): Every variant is a strictly increasing bijection `forwardUs` / `inverseUs`,
 * preserving Media3 `Subtitle.getEventTime(i)` sort order across arbitrary commercial splits & PAL speedups.
 */
sealed interface SubtitleTimeWarp {
    val isIdentity: Boolean
    fun forwardUs(subTimeUs: Long): Long
    fun inverseUs(mediaTimeUs: Long): Long
    fun forwardMs(subTimeMs: Long): Long = forwardUs(subTimeMs * 1_000L) / 1_000L
    fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp

    data object Identity : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = true
        override fun forwardUs(subTimeUs: Long): Long = subTimeUs
        override fun inverseUs(mediaTimeUs: Long): Long = mediaTimeUs
        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp =
            if (deltaMs == 0L) Identity else Affine(FpsRatio.UNITY, deltaMs)
    }

    data class Affine(
        val fpsRatio: FpsRatio,
        val offsetMs: Long,
    ) : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = fpsRatio == FpsRatio.UNITY && offsetMs == 0L
        override fun forwardUs(subTimeUs: Long): Long = fpsRatio.scaleUs(subTimeUs) + offsetMs * 1_000L
        override fun inverseUs(mediaTimeUs: Long): Long = fpsRatio.inverseScaleUs(mediaTimeUs - offsetMs * 1_000L)
        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp =
            copy(offsetMs = offsetMs + deltaMs).let { if (it.isIdentity) Identity else it }
    }

    class PiecewiseLinear private constructor(
        val baseFpsRatio: FpsRatio,
        val knots: List<WarpKnot>,
    ) : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = false
        override fun forwardUs(subTimeUs: Long): Long = TODO("not implemented: O(log K) binary search + linear interp, affine tail extrapolation via baseFpsRatio")
        override fun inverseUs(mediaTimeUs: Long): Long = TODO("not implemented: O(log K) binary search on strictly increasing mediaTimeUs")
        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp = TODO("not implemented: shift all knot.mediaTimeUs by deltaMs * 1_000L")

        companion object {
            /**
             * Enforces `knots.size >= 2` and strict bi-monotonicity:
             * `knots[i].subTimeUs < knots[i+1].subTimeUs && knots[i].mediaTimeUs < knots[i+1].mediaTimeUs`.
             */
            fun createValidated(
                baseFpsRatio: FpsRatio,
                knots: List<WarpKnot>,
            ): Result<PiecewiseLinear> = TODO("not implemented")
        }
    }
}

data class BakedWebVtt(
    val utf8Content: String,
    val cueCount: Int,
    val preWarpTimingSha256: ContentSha256,
    val bakedContentSha256: ContentSha256,
    val appliedWarp: SubtitleTimeWarp,
)

object WebVttBaker {
    /**
     * Applies [warp] to [doc], clamps/drops cues shifted outside `[0, videoDurationMs]`,
     * wraps Urdu lines in Unicode RLM `\u200F` (`forceRtl = doc.language == URDU`), and emits UTF-8 WebVTT.
     */
    fun bake(
        doc: CueDocument,
        warp: SubtitleTimeWarp,
        videoDurationMs: Long? = null,
    ): BakedWebVtt = TODO("not implemented")
}

enum class AlignmentAnchorKind {
    NATIVE_STREAM_PACK,          // Sidecar shipped directly by the video stream's CDN pack
    MATCHED_PROVIDER_SIDECAR,    // Aligned against selectedVideo's English/original sidecar
    CROSS_PROVIDER_SIDECAR,      // Aligned against another SOTA stream-provider sidecar
    SPARSE_AUDIO_VAD,            // Aligned against multi-window 16 kHz PCM speech VAD
    LIVE_AUDIO_SINK_VAD,         // Refined progressively from live ExoPlayer ForwardingPcmAudioSink
    UNANCHORED,                  // No anchor or audio available yet
}

data class AlignmentProof(
    val warp: SubtitleTimeWarp,
    val anchorKind: AlignmentAnchorKind,
    val syncConfidence: Double,      // [0.0, 1.0]
    val medianOnsetResidualMs: Int,
    val p95OnsetResidualMs: Int,
    val splitCount: Int,
)

enum class AuthenticityGrade {
    AUTHENTIC_HUMAN,     // mtDumpScore < 30
    POST_EDITED_STIFF,   // mtDumpScore in 30..59
}

enum class MtSignalFamily {
    ZERO_CONTEXT_POLYSEMY_CALQUE, // "دائیں۔", "جرمانہ۔", "ٹھنڈا!", "شہد،", "آپ کا استقبال ہے", "مقدس گندگی"
    NEWSROOM_PASSIVE_AND_COPULA,  // overuse of "کی طرف سے" passive & "یہ ایک ... ہے" copula
    PRONOUN_REGISTER_WHIPLASH,    // adjacent-cue آپ <-> تم <-> تو flips across < 1.8s same-speaker gaps
    CONTINUATION_SHATTERING,      // period-terminated Urdu fragment on unpunctuated English continuation cue
    BOLCHAL_PARTICLE_DEFICIT,     // < 0.4% conversational particles/compound verbs across >= 200 cues
    CATALOGUE_MT_METADATA,        // uploader/comment/flag signals
}

data class MtAuditResult(
    val mtDumpScore: Int, // 0..100 (>= 60 => hard reject as MtDump)
    val triggeredFamilies: Set<MtSignalFamily>,
    val pronounWhiplashRate: Double,
    val bolChalParticleRate: Double,
)

/**
 * Closed sum encoding subtitle admission invariants at system boundaries.
 * Emission sinks, sidecar writers, and rankers accept ONLY [VerifiedSubtitleCandidate.Admitted].
 */
sealed interface VerifiedSubtitleCandidate {

    sealed interface Admitted : VerifiedSubtitleCandidate {
        val language: SubtitleLanguage
        val provenance: ProvenanceIdentity
        val document: CueDocument
        val alignment: AlignmentProof
    }

    /** Native English or verified native Urdu sidecar emitted directly by a SOTA video stream provider. */
    data class StreamSyncedNative internal constructor(
        val provider: StreamProviderKey,
        override val language: SubtitleLanguage,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
    ) : Admitted {
        override val provenance: ProvenanceIdentity get() = ProvenanceIdentity.StreamProvider(provider)
    }

    /** Human catalogue track verified non-HI, non-MT-dump (`mtDumpScore < 60`), and aligned/scored. */
    data class AlignedHumanCatalogue internal constructor(
        val catalogue: CatalogueSourceKey,
        override val language: SubtitleLanguage,
        val authenticity: AuthenticityGrade,
        val mtAudit: MtAuditResult?,
        val releaseAffinityScore: Int,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
    ) : Admitted {
        override val provenance: ProvenanceIdentity get() = ProvenanceIdentity.HumanCatalogue(catalogue)
    }

    /** Spoken bol-chal Urdu track localized from a verified English donor with 1-to-1 `CueId` & `CPS <= 17`. */
    data class BolChalLocalized internal constructor(
        val donor: DonorProvenance,
        val tier: LocalizationTierKey,
        val qualityReport: BolChalQualityReport,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
    ) : Admitted {
        override val language: SubtitleLanguage get() = SubtitleLanguage.URDU
        override val provenance: ProvenanceIdentity
            get() = ProvenanceIdentity.BolChalTranslated(donor, tier)
    }

    /** Explicit typed rejections returned by boundary gates for deterministic telemetry & JUnit assertions. */
    sealed interface Rejected : VerifiedSubtitleCandidate {
        data class MtDump(val audit: MtAuditResult, val uploader: String?) : Rejected
        data class HearingImpaired(val matchedMarker: String) : Rejected
        data class WrongScriptOrBrokenSmt(val reason: String) : Rejected
        data class BannedSourceOrUploader(val identifier: String) : Rejected
        data class EpisodeOrYearMismatch(val expected: String, val actualRelease: String) : Rejected
        data class SyncMismatch(val bestScore: Double, val residualMs: Int) : Rejected
    }
}

// =================================================================================================
// PART 2: PURE-JVM 16 kHz SPEECH VAD & OBSERVATION-MASKED `AlassAligner`
// =================================================================================================

data class ObservedWindow(val startMs: Long, val endMs: Long) {
    init {
        require(endMs > startMs && startMs >= 0L)
    }
    val durationMs: Long get() = endMs - startMs
}

data class SpeechSpan(
    val startMs: Long,
    val endMs: Long,
    /** Down-weighted to `0.25f` on sung anime OP/ED choruses (`dutyCycle > 0.86 && meanSideRatio > 0.40`). */
    val confidenceWeight: Float = 1.0f,
)

/**
 * Bundling [speechSpans] with [observedWindows] makes observation-masked sparse scoring automatic:
 * `AlassAligner` scores `+w` on speech inside [observedWindows], `-0.25*w` on confirmed silence
 * inside [observedWindows], and exact `0` outside [observedWindows].
 */
data class SpeechActivityTimeline(
    val speechSpans: List<SpeechSpan>,
    val observedWindows: List<ObservedWindow>,
    val mediaDurationMs: Long?,
) {
    val totalObservedMs: Long get() = observedWindows.sumOf { it.durationMs }
}

object SpeechTimelineCodec {
    /** Encodes [SpeechActivityTimeline] into a ~2-4 KB Base64 varint blob for `SidecarManifestV2`. */
    fun encodeCompact(timeline: SpeechActivityTimeline): String = TODO("not implemented")
    fun decodeCompact(blob: String): Result<SpeechActivityTimeline> = TODO("not implemented")
}

data class PcmChunk(
    val presentationTimeUs: Long,
    val sampleRateHz: Int,
    val channelCount: Int,
    /** Interleaved little-endian PCM16 samples (recycled by caller after `feedPcm` returns). */
    val pcm16: ShortArray,
    val frameCount: Int,
)

data class SpeechVadConfig(
    val stepMs: Int = 10,                        // 160 samples @ 16 kHz
    val windowSamples16k: Int = 320,             // 20 ms Hann window -> 256-pt radix-2 real FFT
    val preEmphasisAlpha: Float = 0.97f,
    val attackSnrDb: Float = 7.5f,
    val hangoverSnrDb: Float = 4.0f,
    val maxAttackSpectralFlatness: Float = 0.45f,
    val maxHangoverSibilantFlatness: Float = 0.68f,
    val minSyllabicModulation4HzDb: Float = 1.8f,
    val hangoverFrames: Int = 15,                // 150 ms hangover, trim 120 ms tail on release
    val minSpanDurationMs: Long = 90L,
    val mergeGapMs: Long = 110L,
)

/**
 * Allocation-free (post-init) pure-Kotlin 16 kHz speech activity detector.
 *
 * Pipeline per 10 ms hop:
 *   1. Spatial pre-filter: `0.70*FC + 0.15*(FL+FR)` for >= 6ch (+12 dB dialogue/SFX SNR);
 *      stereo Mid/Side energy ratio `rho_side = E_S / (E_M + eps)` for 2ch; decimate to 16 kHz.
 *   2. Pre-emphasis (`0.97`) + 2nd-order Butterworth cascade (`300 Hz` HPF + `3400 Hz` LPF).
 *   3. In-place 256-pt real FFT -> `E_sp` (`312-3375 Hz`), `E_lo` (`62-250 Hz`), `E_hi` (`3.5-7.2 kHz`),
 *      Wiener Spectral Flatness `SFM`, and Rainer Martin `8 x 200 ms` minimum-statistics noise floor.
 *   4. `4.0 Hz` biquad resonator (`Q = 2.0` at 100 Hz envelope rate) -> syllabic modulation depth.
 *   5. Rolling `5.0 s` duty-cycle + `rho_side` tracker down-weights sung anime OP/ED spans (`0.25f`).
 */
class SpeechActivityDetector(
    private val config: SpeechVadConfig = SpeechVadConfig(),
) {
    fun resetWindow(windowStartMs: Long): Unit = TODO("not implemented")

    /** Streaming push entry point used by both `FfmpegAudioDecoder` loops and live ring consumers. */
    fun feedPcm(chunk: PcmChunk): Unit = TODO("not implemented")

    fun finishWindow(windowEndMs: Long): List<SpeechSpan> = TODO("not implemented")

    /** Convenience pure batch entry point for multi-window probes and JUnit synthetic benchmarks. */
    fun analyzeWindows(
        windows: List<Pair<ObservedWindow, Sequence<PcmChunk>>>,
        mediaDurationMs: Long? = null,
    ): SpeechActivityTimeline = TODO("not implemented")
}

data class AlassConfig(
    val tickMs: Int = 10,
    val maxOffsetMs: Long = 150_000L,            // +-150s covers 90s anime OP + cold opens (K = 30,001 ticks)
    val subToSubSplitPenalty: Float = 7.0f,
    val audioVadSplitPenalty: Float = 8.5f,
    val edgeCuePenaltyMultiplier: Float = 1.35f, // First/last 5% cues resist recap/preview false splits
    val jumpRegularizerPerTick: Float = 2e-4f,
    val nonUnityFpsPriorPenalty: Float = 0.015f,
    val sparseSilencePenaltyWeight: Float = 0.25f,
    val preSpeechLeadMs: Long = 35L,             // Aligns cueStartMs to (vadOnsetMs - 35ms)
    val minConfidenceToAdmit: Double = 0.42,
    val hardRejectCeiling: Double = 0.08,
)

sealed interface AlignmentOutcome {
    data class Aligned(val proof: AlignmentProof) : AlignmentOutcome
    data class RejectedMismatch(val bestScore: Double, val residualMs: Int) : AlignmentOutcome
}

/**
 * Pure-Kotlin `kaegi/alass`-grade piecewise + 9-FPS dynamic programming aligner (`< 30 ms` CPU, `< 500 KB` RAM).
 */
object AlassAligner {

    /** Dense subtitle-to-subtitle alignment against a stream-synced provider anchor (`12-25 ms`). */
    fun alignToAnchorCues(
        candidate: CueDocument,
        anchor: CueDocument,
        anchorKind: AlignmentAnchorKind,
        videoDurationMs: Long? = null,
        config: AlassConfig = AlassConfig(),
    ): AlignmentOutcome = TODO(
        """
        not implemented:
        1. Test all 9 exact FpsRatio values via O(N + M + K) RatingDeltaDelta 4-knot trapezoid sweep
           (overlap_scoring, applying nonUnityFpsPriorPenalty to ratio != UNITY).
        2. Run piecewise split DP on UNITY and best non-unity FpsRatio:
           - Two rolling FloatArray(K) buffers (`prev`, `curr`) + RLE backpointers `(startK, endK, prevK)`.
           - Causal monotonicity `o' <= o + gapTicks_i` via forward left-envelope + backward monotonic deque
             in exact O(1) amortized time per offset tick (zero O(K^2) loops).
           - Apply `edgeCuePenaltyMultiplier * splitPenalty` on first/last 5% of cues.
        3. Refine continuous DP segments (>= 12 cues) by median onset residual and construct validated
           `Identity`, `Affine`, or bi-monotonic `PiecewiseLinear` SubtitleTimeWarp.
        """
    )

    /** Observation-masked alignment against sparse multi-window or progressive 16 kHz audio VAD. */
    fun alignToSparseSpeech(
        candidate: CueDocument,
        timeline: SpeechActivityTimeline,
        anchorKind: AlignmentAnchorKind = AlignmentAnchorKind.SPARSE_AUDIO_VAD,
        config: AlassConfig = AlassConfig(),
    ): AlignmentOutcome = TODO(
        """
        not implemented:
        1. Clip candidate cues (under each candidate FpsRatio + shift o) against `timeline.observedWindows`:
           - +confidenceWeight for overlap with `speechSpans` inside observedWindows
           - -0.25 * weight for overlap with confirmed silence inside observedWindows
           - exact 0.0 outside observedWindows (prevents unobserved gaps from inducing false DP splits).
        2. Run 2-candidate piecewise DP + onset median polish to `(vadOnsetMs - config.preSpeechLeadMs)`.
        """
    )
}

// =================================================================================================
// PART 3: PURE-JVM `UrduQualityGate` & `SceneBolChalLocalizer` (`CPS <= 17`)
// =================================================================================================

data class CatalogueCandidateMeta(
    val catalogue: CatalogueSourceKey,
    val language: SubtitleLanguage,
    val titleOrReleaseText: String,
    val uploaderName: String? = null,
    val isHearingImpairedFlag: Boolean = false,
    val isMachineTranslatedFlag: Boolean = false,
    val declaredFps: Double? = null,
    val declaredDurationMs: Long? = null,
    val ratingScore: Double = 0.0,
    val downloadCount: Int = 0,
)

/**
 * Single pre-download candidate filter & release-affinity scorer shared across ALL 8 harvesters
 * (`SubDL`, `OpenSubtitles` v3 & REST, `YIFY`, `SubSource`, `SubSense`, `Subf2m`, `Wyzie`, `SubtitleCat`).
 */
object CatalogueCandidateGate {
    val BANNED_MT_UPLOADERS: Set<String> = setOf(
        "blackgold", "iamdesigora", "akhaliq_655", "saad.jan", "tryray",
        "chatgpt", "gpt-4", "deepl", "googletranslate", "subtitleedit_mt",
    )

    /**
     * Hard-rejects HI/SDH flags/markers, `BANNED_MT_UPLOADERS`, AI/MT metadata flags, and wrong
     * title/episode/`seriesPremiereYear`; returns release/FPS/runtime affinity score for valid candidates.
     */
    fun evaluatePreDownload(
        meta: CatalogueCandidateMeta,
        spec: MediaLookupSpec,
    ): Result<Int> = TODO("not implemented")
}

object UrduQualityGate {
    /** Verifies genuine Urdu orthography & function-word dominance over Arabic/Persian on raw text. */
    fun verifyUrduOrthography(rawDecodedText: String): Result<Unit> = TODO("not implemented")

    /** Scores modern clean-script NMT calque dumps across all 5 [MtSignalFamily] detectors. */
    fun auditMtNaturalness(
        urduDoc: CueDocument,
        referenceEnglishDoc: CueDocument? = null,
        uploaderName: String? = null,
        releaseComment: String? = null,
    ): MtAuditResult = TODO("not implemented")

    /**
     * Sole boundary constructor for [VerifiedSubtitleCandidate.StreamSyncedNative] and
     * [VerifiedSubtitleCandidate.AlignedHumanCatalogue].
     *
     * Rules (`P_lang.2`):
     * - Rejects HI/SDH, banned uploaders, non-Urdu/broken-SMT script, and `mtDumpScore >= 60`.
     * - Admits `< 30` as `AUTHENTIC_HUMAN` and `30..59` as `POST_EDITED_STIFF`.
     */
    fun admitNativeOrCatalogue(
        rawDecodedText: String,
        language: SubtitleLanguage,
        provenance: ProvenanceIdentity,
        alignment: AlignmentProof,
        meta: CatalogueCandidateMeta? = null,
        referenceEnglishDoc: CueDocument? = null,
    ): VerifiedSubtitleCandidate = TODO("not implemented")
}

enum class AddressRegister {
    TUM, // Default spoken register for peers, spouses, siblings, anime classmates
    AAP, // Elders, authority figures, formal strangers
    TU,  // Intimate/street anger or contempt
}

enum class GenderHint { MASCULINE, FEMININE, UNKNOWN }

data class CharacterRegisterProfile(
    val canonicalName: String,
    val urduTransliteration: String,
    val gender: GenderHint,
    val defaultRegisterToPeers: AddressRegister,
)

/** Scene-level memory card passed across adjacent 25-35 cue scene windows. */
data class SpeakerContextCard(
    val characters: Map<String, CharacterRegisterProfile> = emptyMap(),
    val dominantSceneRegister: AddressRegister = AddressRegister.TUM,
    val genreToneHint: String? = null,
)

data class LatinSourceCue(
    val id: CueId,
    val span: CueSpan,
    val maxVisibleChars: Int,
    val text: LatinEnglishText,
    val endsWithRevealEllipsis: Boolean,
)

data class SceneWindow(
    val windowIndex: Int,
    val contextPrev: List<LatinSourceCue>, // Up to 3 read-only preceding cues
    val targetCues: List<LatinSourceCue>,  // 25-35 cues (LLM) or 15-20 cues (Keyless NMT), split at >= 2.5s silence
    val contextNext: List<LatinSourceCue>, // Up to 2 read-only lookahead cues
    val speakerCard: SpeakerContextCard,
)

data class BolChalCueOut(
    val id: CueId,
    val urduText: String,
)

data class BolChalQualityReport(
    val qeScore: Double,
    val cueSurvivalRatio: Double,       // Must be >= 0.98 (eliminates silent 40% cue drops!)
    val maxNormalDialogueCps: Double,   // Target <= 17.0
    val p95VisibleCps: Double,
    val mtResidualScore: Int,
)

data class BolChalConfig(
    val maxComfortCps: Double = 17.0,
    val minCueDurationMs: Long = 833L,
    val minSplittableCueDurationMs: Long = 1_800L,
    val interCueBlinkGapMs: Long = 84L,
    val maxTrailingSilenceExtendMs: Long = 700L,
    val maxLeadingSilenceExtendMs: Long = 100L,
    val minRequiredCueCoverage: Double = 0.98,
    val llmEndpointUrl: String? = null,
    val llmApiKey: String? = null,
    val llmModelId: String? = null,
) {
    val configHash: String get() = TODO("not implemented: deterministic hash excluding raw API secret")
}

/** Pluggable translation transport (Tier 2A OpenAI/Gemini LLM, Tier 2B `⟦id⟧` Google NMT, ML Kit Offline). */
fun interface BolChalTransport {
    suspend fun translateWindow(
        window: SceneWindow,
        config: BolChalConfig,
    ): Result<Pair<Map<CueId, BolChalCueOut>, LocalizationTierKey>>
}

object UrduCpsRefiner {
    /** Computes visible CPS after stripping `\u200F`, `\u200E`, `\u200C`, and formatting tags. */
    fun measureVisibleCps(urduText: String, durationMs: Long): Double = TODO("not implemented")

    /**
     * Enforces `visibleCps <= 17.0` on normal dialogue cues via 3-step waterfall:
     *   1. Extend `endMs` (up to +700ms leaving 84ms blink gap) and `startMs` (up to -100ms) into silence.
     *   2. Condense spoken filler prefixes (`میں یہ کہہ رہا تھا کہ` -> drop, `میرے خیال میں` -> `لگتا ہے`).
     *   3. Split cues `>= 1,800 ms` at central `[0.35..0.65]` punctuation/conjunction/postposition-trailing
     *      boundaries (snapping to intra-cue [speechTimeline] breath valley when available), NEVER splitting
     *      inside compound verbs (`کر رہا ہے`) or between noun and postposition (`گھر | میں`).
     * Also wraps multi-line cues (> 42 visible chars) at phrase/postposition boundaries instead of `len / 2`.
     */
    fun refineTimingsAndLines(
        cues: List<CueEntry>,
        speechTimeline: SpeechActivityTimeline? = null,
        config: BolChalConfig = BolChalConfig(),
    ): List<CueEntry> = TODO("not implemented")
}

sealed interface LocalizationOutcome {
    data class Success(val candidate: VerifiedSubtitleCandidate.BolChalLocalized) : LocalizationOutcome
    data class Failed(val reason: String) : LocalizationOutcome
}

/**
 * Pure scene-windowed English -> Spoken Bol-Chal Urdu localizer shared across extension & host.
 *
 * Structural guarantees (`P_lang.3`):
 *   - 100% [LatinEnglishText] pre-normalization (`0` Urdu Nastaliq characters sent to `sl=en&tl=ur`).
 *   - Strict 1-to-1 [CueId] mapping (zero character-ratio SVO->SOV slicing across `...` twist reveals).
 *   - Bisects failed `⟦id⟧` NMT batches down to 1 cue and rejects `< 98%` survival so partial drops
 *     can never be emitted or persisted to the 7-day CAS cache.
 *   - Emits Window 0 (first 25 cues) progressively via [onWindowZeroReady] in `< 600 ms` on live streams.
 */
class SceneBolChalLocalizer(
    private val primaryTransport: BolChalTransport,
    private val fallbackTransport: BolChalTransport? = null,
) {
    suspend fun localize(
        donor: VerifiedSubtitleCandidate.Admitted,
        config: BolChalConfig = BolChalConfig(),
        speechTimeline: SpeechActivityTimeline? = null,
        onWindowZeroReady: (suspend (BakedWebVtt) -> Unit)? = null,
    ): LocalizationOutcome = TODO("not implemented")
}

// =================================================================================================
// PART 4: EXTENSION THIN SHELL (`cloudstream-extensions-phisher/shared/subtitles`)
// =================================================================================================

enum class SubtitleMode { DEFAULT_ENGLISH, URDU_BOLCHAL, BILINGUAL_DOWNLOAD }

data class RawSubtitleEmission(
    val sourceHint: String,
    val rawLabel: String,
    val url: String,
    val headers: Map<String, String>,
    val preDownloadMeta: CatalogueCandidateMeta? = null,
) {
    companion object {
        fun fromSubtitleFile(
            sourceHint: String,
            raw: com.lagradost.cloudstream3.SubtitleFile,
            preDownloadMeta: CatalogueCandidateMeta? = null,
        ): RawSubtitleEmission = TODO("not implemented")
    }
}

object SubtitlePipeline {
    fun resolveActiveMode(subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit): SubtitleMode =
        TODO("not implemented: inspect BilingualDownloadSubtitleCallback marker + System property / SharedPreferences")

    fun openSession(
        spec: MediaLookupSpec,
        mode: SubtitleMode,
        sink: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        onSubtitleEmitted: (com.lagradost.cloudstream3.SubtitleFile) -> Unit = {},
    ): EpisodeSubtitleSession = TODO("not implemented")
}

object SubtitleCatalogHarvesters {
    /**
     * Dispatches all eligible English/Urdu harvesters for [spec] into [session].
     * Compile-time / structural invariant: when `spec` is `Episode(coordinate = UnverifiedAnimeCour)`,
     * season-keyed catalogue endpoints (`Wyzie`, `Stremio`, `OpenSubtitles`, `SubDL`, `SubSource`) are skipped.
     */
    suspend fun fanOut(spec: MediaLookupSpec, session: EpisodeSubtitleSession): Unit = TODO("not implemented")
}

/**
 * Per-`loadLinks` isolated state owner replacing `UrduSubtitleEngine` globals (`pendingSubtitleCatOrigByMedia`,
 * `pendingSubtitleCatUrduByMedia`) and unifying `StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`,
 * and `SuperStream` (`per separate-before-serializing-shared-state`).
 */
class EpisodeSubtitleSession internal constructor(
    val spec: MediaLookupSpec,
    val mode: SubtitleMode,
    private val casStore: UrduCasStore,
    private val localizer: SceneBolChalLocalizer,
    private val emitToHost: (lang: String, url: String, headers: Map<String, String>) -> Unit,
    private val onAcceptedCountChanged: () -> Unit,
) {
    /**
     * Validates, deduplicates (by content SHA-256 for Urdu so identical SubDL/Wyzie files never
     * double-count toward diversity!), admits native video-provider Urdu sidecars (`StreamSyncedNative`),
     * and buffers English anchors in deterministic `StreamProvider > Catalogue > SubtitleCat` priority order.
     */
    suspend fun ingest(raw: RawSubtitleEmission): VerifiedSubtitleCandidate? = TODO("not implemented")

    /** Records a last-resort `SubtitleCat` `-orig.srt` English donor scoped strictly to THIS session. */
    fun recordSubtitleCatOrigDonor(url: String, releaseTitle: String): Unit = TODO("not implemented")

    fun needsEnglishCompletion(): Boolean = TODO("not implemented")
    fun needsUrduCompletion(): Boolean = TODO("not implemented")

    /**
     * Runs Phase 3 & Phase 4 completion:
     *   1. Aligns buffered `AlignedHumanCatalogue` Urdu tracks against the highest-rank `StreamSyncedNative`
     *      English anchor via [AlassAligner.alignToAnchorCues] (`< 20 ms`) before emitting CAS `.vtt`/`.srt`.
     *   2. If `< 1` high-confidence (`>= 0.75`) human Urdu track exists, localizes the top-ranked
     *      `StreamSyncedNative` English donor (NEVER `SubtitleCat` when a stream provider sidecar exists!)
     *      via [SceneBolChalLocalizer].
     *   3. Emits up to 2 emergency English fallback tracks ONLY if `0` Urdu tracks succeeded.
     */
    suspend fun finishAndEmitRemaining(
        phase3BudgetMs: Long = 4_500L,
        phase4BudgetMs: Long = 8_000L,
    ): Unit = TODO("not implemented")
}

/**
 * Two-tier content-addressed disk store keyed by `(donorContentSha256, season, episode, configHash)`
 * (never bare `enUrl`), isolating active/queued episode files from detail-page prewarm eviction.
 */
class UrduCasStore(
    private val cacheRoot: File,
    private val maxActiveEpisodeFiles: Int = 256,
    private val maxPrewarmFiles: Int = 32,
) {
    fun getOrWrite(
        keyDigest: ContentSha256,
        baked: BakedWebVtt,
        pinForEpisodePlaybackOrDownload: Boolean,
    ): File = TODO("not implemented: atomic .tmp write + rename, never using 'superstream' in filename")
}

// =================================================================================================
// PART 5: HOST THIN SHELLS (`cloudstream`: JNI BRIDGE, AUDIO PROBER, LIVE SINK, RANKER, MANIFEST V2)
// =================================================================================================

/*
 * File: app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/NextlibFfmpegAudioBridge.kt
 * Package-scoped bridge unlocking package-private `FfmpegAudioDecoder` & `FfmpegLibrary.getCodecName`
 * with zero reflection for 100x-realtime offline/sparse-probe software decoding of AAC, E-AC-3, AC-3,
 * Opus, Vorbis, MP3, FLAC, and DTS.
 */
interface DecodedPcmSink {
    fun onPcm16(presentationTimeUs: Long, sampleRateHz: Int, channelCount: Int, pcm16: ShortArray, frames: Int)
}

interface AudioPacketDecoder : AutoCloseable {
    fun decodePacket(encodedBytes: ByteArray, offset: Int, length: Int, ptsUs: Long, sink: DecodedPcmSink)
    fun flush(sink: DecodedPcmSink)
}

/**
 * Lives in `package io.github.anilbeesetti.nextlib.media3ext.ffdecoder` inside `app/src/main/java/...`
 * to access package-private `FfmpegAudioDecoder` and `FfmpegLibrary.getCodecName(mimeType)` directly
 * without reflection; synthesizes 2-byte `AudioSpecificConfig` (`csd-0`) when fed raw MPEG-TS ADTS AAC.
 */
object NextlibFfmpegAudioBridge {
    fun isNativeCodecAvailable(mimeType: String): Boolean = TODO("not implemented")
    fun createDecoderOrNull(
        mimeType: String,
        sampleRateHz: Int,
        channelCount: Int,
        initializationData: List<ByteArray>,
    ): AudioPacketDecoder? = TODO("not implemented")
}

/**
 * Sparse container & HTTP `Range` / HLS audio-rendition prober (`< 700 ms`, `< 600 KB` on demuxed HLS).
 *
 * Timeline invariants (Phase A Explorer 3):
 *   - HLS segments normalize PTS via `rawPtsUs(seg_k) - rawPtsUs(seg_0)` per `#EXT-X-DISCONTINUITY`
 *     sequence, matching Media3 `TimestampAdjuster` to `< 10 ms`.
 *   - MP4 `Range` probes apply `moov/trak/edts/elst` media-time offset and pass all URLs through
 *     `VideoDownloadManager.sanitizeCdnHeaders` with bounded read caps.
 */
object SparseMediaAudioProber {
    fun planDialogueWindows(
        candidateCues: List<CueDocument>,
        durationMs: Long?,
        isAnime: Boolean,
    ): List<ObservedWindow> = TODO("not implemented: 5 windows covering cold-open, post-OP/Act 1, Act 2, Act 3, pre-credits")

    suspend fun probeLocalOrSafVideo(
        fileOrContentUri: String,
        windows: List<ObservedWindow>,
    ): SpeechActivityTimeline? = TODO("not implemented: MediaExtractor.seekTo + NextlibFfmpegAudioBridge / MediaCodec fallback -> SpeechActivityDetector")

    suspend fun probeLiveStream(
        streamUrl: String,
        isM3u8: Boolean,
        rawHeaders: Map<String, String>,
        windows: List<ObservedWindow>,
    ): SpeechActivityTimeline? = TODO("not implemented: prefer #EXT-X-MEDIA:TYPE=AUDIO playlist on HLS; moov+elst+3 Range GETs on MP4")
}

/**
 * Lock-free SPSC ring buffer written by `ForwardingPcmAudioSink.handleBuffer` on `ExoPlayer:Playback`
 * in `< 20 us` (zero heap allocations) and drained asynchronously on `Dispatchers.Default`.
 */
class PcmTapRingBuffer(capacityFrames16kMono: Int = 16_000 * 12) {
    /**
     * Called ONLY when `Util.isEncodingLinearPcm(pcmEncoding)` is true AND `super.handleBuffer(...)`
     * returned `true` (avoiding duplicate writes when `AudioTrack` is full and ignoring HDMI AC3 passthrough).
     */
    fun writeAcceptedLinearPcm(
        readOnlyDuplicate: java.nio.ByteBuffer,
        pcmEncoding: Int,
        sampleRateHz: Int,
        channelCount: Int,
        presentationTimeUs: Long,
    ): Unit = TODO("not implemented")

    fun resetOnFlushOrSeek(): Unit = TODO("not implemented")
    fun drainInto(detector: SpeechActivityDetector): Long? = TODO("not implemented: returns maxObservedPtsUs")
}

sealed interface WarpTransition {
    data object NoChange : WarpTransition
    data class Updated(
        val effectiveWarp: SubtitleTimeWarp,
        /** True ONLY if a previously passed future cue moved forward across `playheadMediaUs`. */
        val requiresTextRendererCursorRefresh: Boolean,
    ) : WarpTransition
}

/**
 * Pure-logic controller driving live `WarpedSubtitle` without `seekTime(-1)` (`P_sync.3`).
 *
 * Enforces:
 *   1. Active-Cue Lock: never mutates `startTimeUs`/`endTimeUs` of any cue visible at `playheadMediaUs`.
 *   2. `45 ms` Hysteresis Deadband: ignores sub-45ms solver jitter.
 *   3. Inter-Cue Silence Slewing: slews `<= 35 ms` per silence gap for minor drift; steps future cues
 *      on `> 80 ms` jumps (`confidence >= 0.55`) or manual user `setSubtitleOffset`.
 */
class LiveSubtitleWarpController {
    val activeWarp: StateFlow<SubtitleTimeWarp> = TODO("not implemented")

    fun resetForNewSubtitle(initialWarp: SubtitleTimeWarp = SubtitleTimeWarp.Identity): Unit = TODO("not implemented")
    fun setManualUserOffsetMs(offsetMs: Long, playheadMediaUs: Long): WarpTransition = TODO("not implemented")
    fun onSolverUpdate(
        proof: AlignmentProof,
        playheadMediaUs: Long,
        activeCueEndMediaUs: Long?,
    ): WarpTransition = TODO("not implemented")
}

/**
 * Explicit total-order rank bands shared identically by `DownloadSubtitlePolicy`, `AppContextUtils.sortSubs`,
 * `RepoLinkGenerator`, `GeneratorPlayer`, and `DownloadFileGenerator` (`single source of truth`).
 */
enum class OriginRankBand(val rank: Int) {
    NATIVE_MATCHED_VIDEO_PROVIDER(700),       // StreamSyncedNative from selectedVideo's exact provider
    NATIVE_OTHER_STREAM_PROVIDER(600),        // StreamSyncedNative from another SOTA stream provider
    ALIGNED_AUTHENTIC_HUMAN_CATALOGUE(550),   // AlignedHumanCatalogue (AUTHENTIC_HUMAN, syncConfidence >= 0.75)
    BOLCHAL_FROM_STREAM_PROVIDER(500),        // BolChalLocalized(StreamDonor) — synced to video stream!
    ALIGNED_STIFF_HUMAN_CATALOGUE(450),       // AlignedHumanCatalogue (POST_EDITED_STIFF, syncConfidence >= 0.70)
    BOLCHAL_FROM_HUMAN_CATALOGUE(400),        // BolChalLocalized(CatalogueDonor) — e.g. UrduAI Wyzie (NEVER 110!)
    UNVERIFIED_HUMAN_CATALOGUE(300),          // Unanchored human catalogue (below stream providers!)
    LOW_CONFIDENCE_FALLBACK(100),             // syncConfidence < 0.55
    BANNED_OR_DEAD(-100),
}

data class SubtitleRankKey(
    val band: OriginRankBand,
    val syncConfidenceBucket: Int,            // (syncConfidence * 100).toInt()
    val providerOrCatalogueRank: Int,         // StreamProviderKey.rankFor(isAnime) or CatalogueSourceKey.catalogueQualityRank
    val localizationTierRank: Int,            // LLM_SCENE (3) > KEYLESS_BOLCHAL_NMT (2) > MLKIT_OFFLINE (1)
    val releaseAffinityScore: Int,
    val qualityPenaltyInverted: Int,
    val stableTieBreaker: String,
) : Comparable<SubtitleRankKey> {
    override fun compareTo(other: SubtitleRankKey): Int = TODO("not implemented: lexicographic descending comparison")
}

/**
 * Schema v2 sidecar manifest persisting `videoProviderKey`, structured `ProvenanceIdentity`,
 * `SubtitleTimeWarp`, and compact `SpeechActivityTimeline` for idempotent offline heals (`make-operations-idempotent`).
 */
data class SidecarManifestEntryV2(
    val fileName: String,
    val language: SubtitleLanguage,
    val provenance: ProvenanceIdentity,
    val authenticityGrade: AuthenticityGrade?,
    val syncConfidence: Double,
    val anchorKind: AlignmentAnchorKind,
    val appliedWarpSpec: String,              // Canonical serialized SubtitleTimeWarp
    val preWarpTimingSha256: ContentSha256,
    val bakedContentSha256: ContentSha256,    // Compared to disk SHA-256 so heals NEVER double-warp!
    val qeScore: Double,
    val originalUrl: String?,
    val updatedAtMs: Long,
)

data class SidecarManifestV2(
    val schemaVersion: Int = 2,
    val videoDisplayName: String,
    val videoProviderKey: StreamProviderKey?, // Preserved across offline heals when selectedVideo == null!
    val videoDurationMs: Long?,
    val videoFileSize: Long?,
    val videoReleaseHint: String?,
    val speechTimelineBlob: String?,          // Cached 3 KB VAD timeline avoids re-decoding 1 GB SAF video!
    val entries: List<SidecarManifestEntryV2>,
    val updatedAtMs: Long,
) {
    fun entriesByLowerFileName(): Map<String, SidecarManifestEntryV2> =
        entries.associateBy { it.fileName.lowercase() }
}

object AudioSpeechExtractor {
    suspend fun extractOrLoadCached(
        context: android.content.Context,
        fileInfo: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo,
        manifestStore: com.lagradost.cloudstream3.utils.downloader.SidecarManifestStore,
    ): SpeechActivityTimeline? = TODO(
        """
        not implemented:
        1. Check manifestStore.loadManifestV2(context, fileInfo)?.speechTimelineBlob -> decodeCompact if valid.
        2. Otherwise plan 5 dialogue windows and call SparseMediaAudioProber.probeLocalOrSafVideo (~0.4-1.1s).
        3. Persist compact blob back into manifestStore so repeat offline heals cost 0 I/O.
        """
    )
}

object SidecarReconciler {
    /**
     * Idempotent bilingual sidecar reconciliation for both initial downloads and offline heals:
     *   - Reserves >= 2 stream-synced English slots on Movies/TV AND Anime (evicting unanchored catalogues).
     *   - Skips re-warping any on-disk `.vtt` whose SHA-256 already equals `entry.bakedContentSha256`.
     *   - Aligns catalogue candidates via `AlassAligner` (anchor cues + [speechTimeline]).
     *   - Fills Urdu quota via `SceneBolChalLocalizer` from the highest-confidence stream-synced English donor.
     */
    suspend fun reconcileBilingualSidecars(
        context: android.content.Context,
        fileInfo: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadedFileInfo,
        selectedVideo: com.lagradost.cloudstream3.utils.ExtractorLink?,
        candidates: List<com.lagradost.cloudstream3.ui.player.SubtitleData>,
        speechTimeline: SpeechActivityTimeline?,
        isAnime: Boolean,
    ): SidecarManifestV2 = TODO("not implemented")
}

data class PlaybackRankingContext(
    val activeVideoProvider: StreamProviderKey?,
    val isAnime: Boolean,
    val manifestEntries: Map<String, SidecarManifestEntryV2> = emptyMap(),
)

object SubtitleRankingPolicy {
    fun rankKey(
        subtitle: com.lagradost.cloudstream3.ui.player.SubtitleData,
        context: PlaybackRankingContext,
    ): SubtitleRankKey = TODO("not implemented")

    fun sortForPlayback(
        candidates: Collection<com.lagradost.cloudstream3.ui.player.SubtitleData>,
        context: PlaybackRankingContext,
    ): List<com.lagradost.cloudstream3.ui.player.SubtitleData> =
        candidates.sortedBy { rankKey(it, context) }
}

/**
 * Media3 `Subtitle` decorator applying `LiveSubtitleWarpController.activeWarp` in O(log K) per frame
 * without re-decoding VTT/ASS bytes or calling `exoPlayer.seekTo(pos - 1)`.
 */
class WarpedSubtitle(
    private val delegate: androidx.media3.extractor.text.Subtitle,
    private val warpController: LiveSubtitleWarpController,
) : androidx.media3.extractor.text.Subtitle {
    override fun getEventTimeCount(): Int = delegate.eventTimeCount
    override fun getEventTime(index: Int): Long =
        warpController.activeWarp.value.forwardUs(delegate.getEventTime(index))
    override fun getNextEventTimeIndex(timeUs: Long): Int =
        delegate.getNextEventTimeIndex(warpController.activeWarp.value.inverseUs(timeUs))
    override fun getCues(timeUs: Long): List<androidx.media3.common.text.Cue> =
        delegate.getCues(warpController.activeWarp.value.inverseUs(timeUs))
}

/**
 * Media3 `ForwardingAudioSink` tapping linear PCM + exact microsecond `presentationTimeUs` ONLY after
 * `super.handleBuffer` returns `true` and ONLY when `Util.isEncodingLinearPcm(format.pcmEncoding)` is true
 * (gracefully no-oping on Android TV HDMI AC-3/E-AC-3 bitstream passthrough).
 */
class ForwardingPcmAudioSink(
    delegate: androidx.media3.exoplayer.audio.AudioSink,
    private val pcmTap: PcmTapRingBuffer,
) : androidx.media3.exoplayer.audio.ForwardingAudioSink(delegate) {
    override fun configure(
        inputFormat: androidx.media3.common.Format,
        specifiedBufferSize: Int,
        outputChannelMap: IntArray?,
    ): Unit = TODO("not implemented: record linear PCM format state, call super.configure")

    override fun handleBuffer(
        buffer: java.nio.ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = TODO("not implemented: duplicate read slice prior to super.handleBuffer; write to pcmTap iff accepted && isLinearPcm")

    override fun flush(): Unit = TODO("not implemented: pcmTap.resetOnFlushOrSeek(); super.flush()")
    override fun reset(): Unit = TODO("not implemented: pcmTap.resetOnFlushOrSeek(); super.reset()")
}

/**
 * Shared factory wiring `ForwardingPcmAudioSink` into BOTH `FixedNextRenderersFactory` (phone/tablet)
 * AND `DefaultRenderersFactory` (Android TV / HW-only mode), preventing the TV live-sync bypass bug.
 */
object AudioSinkTapRenderersFactory {
    fun create(
        context: android.content.Context,
        useExtensionRenderers: Boolean,
        extensionRendererMode: Int,
        pcmTap: PcmTapRingBuffer,
        subtitleDecoderFactory: com.lagradost.cloudstream3.ui.player.CustomSubtitleDecoderFactory,
    ): androidx.media3.exoplayer.RenderersFactory = TODO("not implemented")
}

class LiveSubtitleSyncSession(
    private val scope: CoroutineScope,
    private val onTextCursorRefreshNeeded: () -> Unit,
) {
    val pcmAudioTap: PcmTapRingBuffer = PcmTapRingBuffer()
    val warpController: LiveSubtitleWarpController = LiveSubtitleWarpController()

    fun setManualUserOffsetMs(offsetMs: Long): Unit = TODO("not implemented")

    fun bindActiveStreamAndSubtitle(
        videoLink: com.lagradost.cloudstream3.utils.ExtractorLink?,
        subtitle: com.lagradost.cloudstream3.ui.player.SubtitleData?,
        streamAnchorCues: CueDocument?,
        isAnime: Boolean = false,
    ): Unit = TODO(
        """
        not implemented:
        1. Decode ProvenanceIdentity of `subtitle` and StreamProviderKey of `videoLink`.
        2. If subtitle is native sidecar of active videoLink -> Identity warp immediately.
        3. Else if streamAnchorCues != null -> run AlassAligner.alignToAnchorCues (< 20 ms) immediately.
        4. Launch non-blocking SparseMediaAudioProber.probeLiveStream + incremental PcmTapRingBuffer drain loop,
           feeding AlignmentProof updates into warpController.onSolverUpdate without blocking first video frame.
        """
    )
}
