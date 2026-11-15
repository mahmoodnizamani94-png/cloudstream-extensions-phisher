# Candidate 2: Algebraic type-safe pipeline & pure-core/thin-shell

## Problem

Issue #17 requires sample-accurate subtitle synchronization (`P_sync`: median onset error `<= 50 ms`, P95 `<= 90 ms` across sub-1.5s lip-sync lag, `60–90 s` cold-open/OP jumps, `23.976 <-> 25` PAL drift, multi-act commercial cuts, and noisy 16 kHz audio VAD, with `0` live `seekTime(-1)` flushes) and authentic spoken *bol-chal* Urdu (`P_lang`: `0` public SubtitleCat uploads, `100%` HI/SDH and modern NMT calque-dump rejection, `100%` Latin pre-translation input, 1-to-1 `[cue_id]` lock without SVO->SOV reveal spoilers or `40%` batch cue drops, locked `تم`/`آپ`/`تو` register, and `visibleCps <= 17.0`) across `cloudstream-extensions-phisher` (`PathClassLoader` plugins) and `cloudstream` (Media3 `1.9.3` + `nextlib` `1.9.3-0.12.0`). The shape is non-obvious because current failures stem from stringly-typed state leaking across boundaries: `DownloadSubtitlePolicy` substring-scans `"UrduAI Wyzie"` into `"wyzie"` (`110`), letting translated catalogue tracks outrank native Urdu and stream-synced `VidLink` (`100`); extension `loadLinks` drops native video-provider Urdu sidecars, wipes global `SubtitleCat` maps across concurrent episodes, places `SubtitleCat` `-orig.srt` ahead of `VidLink` in Phase 4, and queries multi-cour anime with fabricated `season = 1`; `SuperStream` forks ~2,500 diverging lines of `UrduSubtitleEngine`; `SubtitleSyncEngine` never runs on live streams and rejects `< 1.5 s`, `> 45 s`, FPS-drifted, and multi-act tracks; `nextlib`'s `FfmpegAudioDecoder` is package-private (`arm64`/`x86` JNI only, absent on Linux JDK 17 JUnit); and Media3's `TextRenderer` enforces monotonic `getEventTime` cursors while `AudioSink` only buffers `0.25–0.75 s` of PCM ahead of speakers.

## Usage (caller's view)

Callers interact with four deep entry points — `EpisodeSubtitleSession` (extensions), `SidecarReconciler` + `SubtitleRankingPolicy` (host downloads/offline/pickers), `LiveSubtitleSyncSession` (host ExoPlayer), and pure-JVM `AlassAligner` / `SceneBolChalLocalizer` (tests and benchmarks) — never touching raw `X-StreamPlay-*` header strings, DP tables, FFT buffers, or `seekTime(-1)`.

```kotlin
// 1. Extension loadLinks (StreamPlay, StreamPlayAnime, StreamPlayStremioCatelog, SuperStream)
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.RawSubtitleEmission
import com.phisher98.subtitles.SubtitlePipeline

val spec = MediaLookupSpec.Episode(
    imdbId = res.imdbId,
    tmdbId = res.id,
    titles = listOfNotNull(res.title, res.jpTitle).distinct(),
    seriesPremiereYear = res.airedYear ?: res.year, // SubDL/SubSource slug year
    seasonAirYear = res.year,
    expectedDurationMs = res.runtimeMinutes?.times(60_000L),
    releaseHints = emptyList(),
    coordinate = when {
        !res.isAnime -> EpisodeCoordinate.VerifiedSeasonEpisode(res.season!!, res.episode!!)
        verifiedTmdbPair != null -> EpisodeCoordinate.VerifiedSeasonEpisode(verifiedTmdbPair.first, verifiedTmdbPair.second)
        else -> EpisodeCoordinate.UnverifiedAnimeCour(
            courTitles = titleVariants,
            episodeInCour = res.episode ?: 1,
            absoluteEpisode = res.absoluteEpisode,
        )
    },
    isAnime = res.isAnime,
)

val session = SubtitlePipeline.openSession(
    spec = spec,
    mode = SubtitlePipeline.resolveActiveMode(subtitleCallback),
    sink = subtitleCallback,
    onSubtitleEmitted = { earlyController.onSubtitleEmitted(it); dispatcher.onSubtitleReceived() },
)

// Video extractors and unified 8-catalogue harvesters feed raw files into this per-episode session:
val emitSub: suspend (String, SubtitleFile) -> Unit = { providerKey, raw ->
    session.ingest(RawSubtitleEmission.fromSubtitleFile(providerKey, raw))
}
SubtitleCatalogHarvesters.fanOut(spec, session) // UnverifiedAnimeCour cannot hit season=1 APIs!

// Aligns human Urdu against VidLink/Cineby/AniZone English anchor via AlassAligner (< 20 ms)
// or localizes top stream-synced donor via SceneBolChalLocalizer (Window-0 first, 1-to-1 CueId):
session.finishAndEmitRemaining(phase3BudgetMs = 4_500L, phase4BudgetMs = 8_000L)
```

```kotlin
// 2. Host download completion, offline heal, and unified playback sorting
import com.lagradost.cloudstream3.subtitles.sync.AudioSpeechExtractor
import com.lagradost.cloudstream3.subtitles.sync.PlaybackRankingContext
import com.lagradost.cloudstream3.subtitles.sync.SidecarReconciler
import com.lagradost.cloudstream3.subtitles.sync.SubtitleRankingPolicy
import com.lagradost.cloudstream3.utils.downloader.SidecarManifestStore

val speechTimeline = AudioSpeechExtractor.extractOrLoadCached(
    context = context,
    fileInfo = fileInfo,
    manifestStore = SidecarManifestStore, // reuses cached 3 KB timeline on repeat heals!
)

val manifest = SidecarReconciler.reconcileBilingualSidecars(
    context = context,
    fileInfo = fileInfo,
    selectedVideo = selectedVideoLink, // persisted in SidecarManifestV2.videoProviderKey
    candidates = harvestedSubtitles,
    speechTimeline = speechTimeline,
    isAnime = isAnimeContext,
)

// Single ranking source of truth called by DownloadSubtitlePolicy, AppContextUtils.sortSubs,
// RepoLinkGenerator, GeneratorPlayer, and DownloadFileGenerator:
val ordered = SubtitleRankingPolicy.sortForPlayback(
    candidates = subtitles,
    context = PlaybackRankingContext(
        activeVideoProvider = activeProviderKey ?: manifest.videoProviderKey,
        isAnime = isAnimeContent,
        manifestEntries = manifest.entriesByLowerFileName(),
    ),
)
```

```kotlin
// 3. Host live ExoPlayer setup (CS3IPlayer — covers BOTH phone NextRenderersFactory & Android TV!)
import com.lagradost.cloudstream3.subtitles.sync.AudioSinkTapRenderersFactory
import com.lagradost.cloudstream3.subtitles.sync.LiveSubtitleSyncSession

val liveSync = LiveSubtitleSyncSession(
    scope = mediaItemSyncScope,
    onTextCursorRefreshNeeded = { refreshTextRendererCursorOnly() }, // zero seekTime(-1)!
)
val renderersFactory = AudioSinkTapRenderersFactory.create(
    context = context,
    useExtensionRenderers = profile.useExtensionRenderers,
    extensionRendererMode = profile.extensionRendererMode,
    pcmTap = liveSync.pcmAudioTap,
    subtitleDecoderFactory = CustomSubtitleDecoderFactory(liveSync.warpController),
)

liveSync.bindActiveStreamAndSubtitle(
    videoLink = currentExtractorLink,
    subtitle = currentSubtitles,
    streamAnchorCues = subtitleVault.bestStreamProviderCues(currentExtractorLink),
)
// Manual user offset composes onto liveSync without mutating static globals or flushing decoders:
override fun setSubtitleOffset(offset: Long) = liveSync.setManualUserOffsetMs(offset)
```

## Shape

The architecture enforces invariants through closed algebraic data types in a zero-Android pure-Kotlin core shared identically across `shared/subtitles/src/main/kotlin` (compiled into both `:StreamPlay` and `:SuperStream` via `kotlin.srcDir`) and `app/src/main/java/com/lagradost/cloudstream3/subtitles/{sync,urdu}`, wrapped by thin I/O and Media3 shells (`per boundary-discipline` and `encode-lessons-in-structure`):

1. **Boundary-validated sum types eliminate entire bug classes (`per encode-lessons-in-structure`)**:
   - `ProvenanceIdentity` is a closed sum (`StreamProvider` | `HumanCatalogue` | `BolChalTranslated(donor, engine)` | `UnknownExternal`) owned by a single bijective `ProvenanceCodec` (wire headers `X-StreamPlay-Source` + `X-StreamPlay-Origin` + `X-StreamPlay-Donor` + `X-StreamPlay-Sync`, `SidecarManifestV2` JSON, and legacy v1 `"UrduAI <Donor>"` filenames). `SubtitleRankKey` maps `BolChalTranslated` into dedicated translated rank bands and inspects `donor` **only** to order `BolChalTranslated(StreamProvider(VIDLINK))` above `BolChalTranslated(HumanCatalogue(WYZIE))` — making `UrduAI Wyzie -> 110` unrepresentable.
   - `VerifiedSubtitleCandidate` splits into `Admitted` (`StreamSyncedNative`, `AlignedHumanCatalogue`, `BolChalLocalized`) and `Rejected` (`MtDump`, `HearingImpaired`, `WrongScriptOrBrokenSmt`, `BannedSourceOrUploader`, `EpisodeOrYearMismatch`, `SyncMismatch`). Emission sinks, VTT bakers, and rankers accept `Admitted` only; `UrduQualityGate` (`UrduOrthographyGate` + 5-family `UrduMtDumpDetector` + HI/uploader gate) is the sole constructor of `Admitted` Urdu tracks.
   - `SubtitleTimeWarp` (`Identity` | `Affine(fpsRatio: FpsRatio, offsetMs: Long)` | `PiecewiseLinear(knots: List<WarpKnot>)`) enforces strict bi-monotonicity (`subTimeUs` and `mediaTimeUs` strictly increasing) at construction and uses exact rational `FpsRatio(num, den)` arithmetic (`0 µs` float drift). The identical `SubtitleTimeWarp` drives offline `WebVttBaker.bake(...)` and live `WarpedSubtitle` ($O(\log |\text{knots}|)$ `forwardUs`/`inverseUs`).
   - `MediaLookupSpec.Episode` separates `seriesPremiereYear` from `seasonAirYear` and encodes coordinates as `EpisodeCoordinate.VerifiedSeasonEpisode` vs `UnverifiedAnimeCour` (which has no `season` field, preventing multi-cour anime from querying `season = 1` catalogues).
   - `LatinEnglishText` is a boundary-validated value class rejecting any `U+0600..U+06FF` Arabic/Nastaliq code point before `sl=en&tl=ur`, while `SceneBolChalLocalizer` keys output as `Map<CueId, BolChalCueOut>` (requiring `>= 98%` cue coverage with 1-cue bisection fallback so `40%` partial drops never enter CAS cache, locking scene `تم`/`آپ`/`تو` via `SpeakerContextCard`, and refining `visibleCps <= 17.0` via `UrduCpsRefiner`'s silence-gap extension -> filler trim -> clause/VAD-valley split waterfall).

2. **Pure-JVM DSP + `alass` core with thin Media3/HTTP shells (`per boundary-discipline` and `minimize-reader-load`)**:
   - `SpeechActivityDetector` processes `16 kHz` frames (`5.1` center isolation / stereo Mid-Side ratio, `0.97` pre-emphasis, `300–3400 Hz` Butterworth biquads, 256-pt radix-2 FFT Wiener spectral flatness, 8×200ms Martin minimum-statistics noise floor, `4 Hz` syllabic modulation resonator, and `5 s` sung-OP duty-cycle down-weighting) into `SpeechActivityTimeline(speechSpans, observedWindows)`.
   - `AlassAligner` runs 9-FPS `RatingDeltaDelta` $O(N + M + K)$ global sweeps (`< 4 ms`) followed by $O(1)$-amortized monotonic-deque piecewise split DP with RLE backpointers (`< 400 KB` RAM), automatic observation-masked zero-mean scoring over `observedWindows`, and `-35 ms` onset median polish (`< 30 ms` total CPU).
   - Host thin shells stay purely mechanical: `NextlibFfmpegAudioBridge` (in `package io.github.anilbeesetti.nextlib.media3ext.ffdecoder`, unlocking `nextlib`'s package-private `FfmpegAudioDecoder` without reflection), `SparseMediaAudioProber` (demuxed HLS audio renditions, muxed HLS `seg_0` PTS anchoring, MP4 `moov`+`elst` + 3 parallel `Range` reads with `sanitizeCdnHeaders`, and local/SAF `FileDescriptor` seeks), `ForwardingPcmAudioSink` (post-`super.handleBuffer == true` lock-free ring tap with linear-PCM guard for Android TV passthrough), and `LiveSubtitleWarpController` (Active-Cue Lock + `45 ms` hysteresis + silence-gap slewing + text-only cursor refresh, `0` `seekTime(-1)` calls).

3. **Zero shared mutable globals & idempotent reconciliation (`per separate-before-serializing-shared-state` and `make-operations-idempotent`)**:
   - Every `loadLinks` opens an isolated `EpisodeSubtitleSession` instead of mutating `UrduSubtitleEngine` globals; `CustomDecoder.subtitleOffset` static state is replaced by per-player `LiveSubtitleWarpController`.
   - `SidecarManifestV2` stores `ProvenanceIdentity`, `bakedWarp`, `preWarpTimingSha256`, `bakedContentSha256`, `videoProviderKey`, and compact `speechTimelineBlob`, so re-running `SidecarReconciler` after crashes or repeat heals is a no-op on already-baked sidecars and never double-warps timestamps.
   - `publishToSubtitleCatCdn` is deleted in both repos; SubtitleCat `-orig.srt` is strictly a last-resort English donor.

4. **Interface depth judgment**:
   - Callers see 4 entry points totaling ~10 public methods across both repos, hiding 9-ratio rational `alass` DP, 16 kHz spectral/modulation VAD, ISO-BMFF/HLS PTS timeline normalization, package-private JNI audio decoding, Media3 `TextRenderer` cursor invariants, 5-family Urdu MT calque detection, and 1-to-1 bol-chal scene localization. Call chains never exceed 3 hops (`Caller -> Session/Coordinator -> Pure Core`).

## Synthesis decision

[Pending arena synthesis across Phase B candidates.]

## Tradeoffs accepted

- We accept compiling `shared/subtitles/src/main/kotlin` into both `:StreamPlay` and `:SuperStream` `.cs3` DEX bundles (~95 KB bytecode in each plugin) in exchange for zero cross-plugin runtime dependency while deleting ~2,500 lines of diverged `SuperStream` copy-paste (`per laziness-protocol`).
- We accept maintaining pure-core data-type parity across two Git repositories (`cloudstream-extensions-phisher` and `cloudstream`) guarded by deterministic `CrossRepoProvenanceContractTest` assertions in exchange for keeping each repository independently buildable without cross-repo Maven publishing infrastructure.
- We accept `6–9 MB` of bounded parallel HTTP `Range` reads (`< 600 KB` on demuxed HLS audio playlists) in `SparseMediaAudioProber` when no verified stream-provider subtitle anchor exists in exchange for sub-second opening-to-credits PAL/multi-act lock that a `0.5 s`-lookahead `AudioSink` tap alone cannot observe before Act 2.
- We accept rejecting partial translations when `< 98%` of cues survive single-cue bisection (falling back to ML Kit / alternative donor / emergency English floor) in exchange for never persisting 40%-missing dialogue tracks into the 7-day CAS cache.

## Alternatives considered

- **In-place incremental patching of `DownloadSubtitlePolicy` regexes, `UrduSubtitleEngine` globals, and `CustomDecoder.subtitleOffset`**: Exposes internal ordering rules (`SOURCE_MARKERS` list order, `pendingSubtitleCatOrigByMedia` cleanup discipline, `seekTime(-1)` decode-time offset baking) to every caller (`shallow module` + `split ownership` red flags). Rejected because stringly-typed priorities, global maps, and single-scalar decode offsets cannot represent `PiecewiseLinear` warps, bi-level `BolChalTranslated(donor)` provenance, or stutter-free live convergence without recurring regressions.
- **Live-only `TeeAudioProcessor` sync without sparse container probing or `ForwardingAudioSink`**: Slightly smaller Media3 wiring surface, but `AudioProcessor.queueInput` strips `presentationTimeUs` across `SonicAudioProcessor` speed changes and HLS discontinuities, is bypassed completely on Android TV HDMI AC-3/E-AC-3 passthrough, and sees only `0.25–0.75 s` of PCM ahead of speakers — leaving `-65 s` cold-open shifts and `23.976 <-> 25` FPS drift unsolvable until minutes of broken dialogue have already played.
- **Full-file sequential FFmpeg audio decode for offline sync**: Simpler demux loop inside `AudioSpeechExtractor`, but decoding 2 hours of stereo 48 kHz audio sequentially burns `45–80 s` of CPU per episode and risks Android 14+ foreground-service timeouts, whereas 5–6 sparse `MediaExtractor.seekTo` windows with observation-masked `AlassAligner` achieve `< 50 ms` onset accuracy in `0.4–1.1 s`.

## Open questions and risks

1. Should optional Tier-2A LLM settings (`llm_endpoint_url`, `llm_api_key`, `llm_model_id`) live in the existing `"cloudstream_subtitle_mode_prefs"` `SharedPreferences` file alongside `"subtitle_mode"` so a single Subtitle Settings card in `cloudstream` configures both host and plugin localization automatically?
2. When a human catalogue Urdu track scores in the `PostEditedStiff` band (`mtDumpScore in 30..59`) with high sync confidence (`>= 0.80`), should it outrank a keyless Tier-2B `BolChalLocalized(StreamProvider)` track by default, or rank behind Tier-2A LLM `BolChalLocalized(StreamProvider)` and ahead of Tier-2B keyless NMT?
3. On metered cellular connections during live playback when a stream-synced English anchor (`VidLink`/`MeowTV`/`Cineby`) is already available (`syncConfidence >= 0.95`), should `SparseMediaAudioProber` skip muxed MP4 `Range` reads (`~6–9 MB`) and rely solely on the stream anchor + zero-bandwidth `ForwardingPcmAudioSink` verification?

## Next implementation step

Implement the zero-Android pure-Kotlin core (`SubtitleTimeWarp`, `ProvenanceIdentity` + `ProvenanceCodec`, `SpeechActivityDetector`, `AlassAligner`, `UrduQualityGate`, and `SceneBolChalLocalizer`) alongside the deterministic 5-regime `P_sync` and `P_lang` JUnit benchmark suites in `shared/subtitles` and `app/src/test` before wiring the extension and Media3 shells.
