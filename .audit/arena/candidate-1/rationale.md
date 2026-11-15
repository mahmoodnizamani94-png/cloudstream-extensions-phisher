# Deep domain-module facade for Urdu Bol-Chal localization and sample-accurate sync

## Problem

Across [cloudstream-extensions-phisher](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf) and [cloudstream](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17), subtitle harvesting, Urdu localization, ranking, and timing synchronization are fragmented across ~7,500 lines of duplicated and conflicting machinery that fail both falsifiable predicates (`P_sync`: `<= 50 ms` median / `<= 90 ms` P95 cue-onset error across lip-sync lag, `60–90 s` OP/cold-open jumps, `23.976 <-> 25` FPS drift, multi-act splits, and noisy audio; `P_lang`: zero public CDN pollution, `100%` NMT-dump rejection, `0` Nastaliq source-splicing, `1:1` `[cue_id]` speaker-register Bol-Chal Urdu at `visibleCps <= 17.0`). On the extension side, [SuperStreamExtractor.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/src/main/kotlin/com/phisher98/SuperStreamExtractor.kt) forks ~2,500 lines of [UrduSubtitleEngine.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt) with divergent bugs; four plugin surfaces duplicate multi-phase callback closures while sharing mutable global maps (`pendingSubtitleCatOrigByMedia`), dropping runtime/season-debut/multi-cour-anime metadata, discarding 100% of native video-provider Urdu sidecars, and translating random `SubtitleCat` cuts ahead of frame-locked `VidLink`/`Cineby`/`AniZone` anchors. On the host side, [DownloadSubtitlePolicy.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadSubtitlePolicy.kt) ranks unverified generic catalogues (`101..110`) above stream-synced providers (`87..100`) on Movies/TV and leaks `UrduAI Wyzie` to priority `110`; [AppContextUtils.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt) discards manifest sync confidence; [FallbackSubtitleHarvester.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/FallbackSubtitleHarvester.kt) uploads formal Google Translate dumps to SubtitleCat's public CDN as priority-`102` tracks; [SubtitleSyncAndManifest.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt) never runs on live streams, blocks shifts `< 1.5 s` or `> 45 s`, vetoes FPS drift, and has zero audio VAD; and [CS3IPlayer.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt) flushes decoders via `seekTime(-1)` on offset changes while bypassing `FixedNextRenderersFactory` on Android TV. The design must honor the plugin `PathClassLoader` boundary, `StreamPlayDynamicBridge` reflection signatures on `StreamPlayExtractor`, Media3 `1.9.3` `TextRenderer` monotonicity, `nextlib` `1.9.3-0.12.0`'s package-private `FfmpegAudioDecoder`, and CDN `Range`/header rules (`sanitizeCdnHeaders`).

## Usage (caller's view)

### Quickstart

- **Extension surfaces (`StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, `SuperStream`)** import `com.phisher98.subtitles.*` from `shared/subtitles/src/main/kotlin`. Each `loadLinks` opens one `SubtitleSessionCoordinator`, hands `session.sinkFor(providerId, isVideoProvider)` to extractors, and awaits `session.completeAndFinalize(...)`.
- **Host download & heal (`VideoDownloadManager`)** prepares one `VideoSyncReference` via `SubtitleTimelineAligner.prepareOfflineReference(...)`, selects tracks via `DownloadSubtitlePolicy.selectDownloadCandidates(...)`, aligns every sidecar via `SubtitleTimelineAligner.alignToWebVtt(...)`, localizes missing Urdu via `BolChalUrduLocalizer.localizeToWebVtt(...)`, and persists `SidecarManifest` v2.
- **Host player & UI (`CS3IPlayer`, `GeneratorPlayer`, `AppContextUtils`, `DownloadFileGenerator`)** bind one `LiveAudioSyncSession` per playback item (wrapping `AudioSink` + decoded `Subtitle` for zero-seek dynamic time warping) and rank tracks everywhere via `DownloadSubtitlePolicy.rankSubtitles(subs, rankingContext)`.

### Call site 1 — Extension `loadLinks` (`StreamPlay`, `StreamPlayAnime`, `SuperStream`)

```kotlin
import com.phisher98.subtitles.RetryableVideoProvider
import com.phisher98.subtitles.SubtitleMediaDescriptor
import com.phisher98.subtitles.SubtitleSessionCoordinator
import com.phisher98.subtitles.SubtitleSurface
import com.phisher98.subtitles.VerifiedTmdbEpisode

// Inside StreamPlayAnime.loadLinks (or StreamPlay / SuperStream):
// Multi-cour anime without a verified TMDB pair passes verifiedTmdb = null;
// guessing season = 1 is structurally impossible.
val descriptor = SubtitleMediaDescriptor.AnimeEpisode(
    anilistId = anilistId,
    malId = malId,
    imdbId = effectiveImdbId,
    tmdbId = tmdbId,
    titleVariants = listOfNotNull(title, englishTitle, romajiTitle, jpTitle).distinct(),
    releaseYear = year,
    episode = episode,
    verifiedTmdb = verifiedTmdbPair?.let { (s, e) -> VerifiedTmdbEpisode(s, e) },
    expectedDurationMs = runtimeMinutes?.let { it * 60_000L },
    releaseHint = null,
)

val subtitleSession = SubtitleSessionCoordinator.open(
    descriptor = descriptor,
    surface = SubtitleSurface.STREAM_PLAY_ANIME,
    downstreamCallback = subtitleCallback,
    sharedPrefs = sharedPref,
    onSubtitleDelivered = { emitted ->
        earlyController.onSubtitleEmitted(emitted)
        dispatcher.onSubtitleReceived()
    },
)

val minSubs = subtitleSession.recommendedEarlyMinSubtitles(defaultEnglishMin = 6)

// Phase 1: Video extractors emit native English & Urdu sidecars into per-provider sinks:
invokeAniZone(
    titles = descriptor.titleVariants,
    episode = episode,
    subtitleCallback = subtitleSession.sinkFor("anizone", isVideoProvider = true),
    callback = ::emitLink,
)
// Optional release enrichment when SuperStream/FebBox discovers exact file metadata:
subtitleSession.recordVideoReleaseHint("superstream", fileList.fileName, durationMs = null)

// Phases 2-5 (catalogue harvest, MT-dump rejection, sidecar alass alignment,
// [cue_id] Bol-Chal localization of best stream-synced anchor, emergency floor) in one call:
subtitleSession.completeAndFinalize(
    concurrency = concurrency,
    retryVideoProviders = unansweredProviders.map { p ->
        RetryableVideoProvider(p.id, FAST_PROVIDER_BOOST[p.id] ?: 0f) { sink ->
            p.invoke(res, sink, phase3VideoCallback)
        }
    },
)
```

### Call site 2 — Host offline download & heal (`VideoDownloadManager`)

```kotlin
import com.lagradost.cloudstream3.subtitles.bolchal.BolChalMediaContext
import com.lagradost.cloudstream3.subtitles.bolchal.BolChalLocalizationOutcome
import com.lagradost.cloudstream3.subtitles.bolchal.BolChalUrduLocalizer
import com.lagradost.cloudstream3.subtitles.sync.OfflineVideoSource
import com.lagradost.cloudstream3.subtitles.sync.SubtitleTimelineAligner
import com.lagradost.cloudstream3.subtitles.sync.SyncOutcome
import com.lagradost.cloudstream3.utils.downloader.DownloadSubtitlePolicy
import com.lagradost.cloudstream3.utils.downloader.SidecarManifestStore
import com.lagradost.cloudstream3.utils.downloader.SubtitleRankingContext

val existingManifest = SidecarManifestStore.loadManifest(context, fileInfo)
val activeProviderKey = selectedVideo?.let(DownloadSubtitlePolicy::videoProviderKeyOf)
    ?: existingManifest?.videoProviderKey

val rankingCtx = SubtitleRankingContext(
    preferredLangTag = targetLangTag,
    activeVideo = selectedVideo,
    activeVideoProviderKey = activeProviderKey,
    isAnime = isAnimeContext,
    releaseHintText = selectedVideo?.name ?: existingManifest?.videoReleaseHint,
)

val chosen = DownloadSubtitlePolicy.selectDownloadCandidates(
    candidates = rawPool,
    context = rankingCtx,
    maxCount = DownloadSubtitlePolicy.targetSubtitlesPerLanguage(isAnimeContext),
)

val syncRef = SubtitleTimelineAligner.prepareOfflineReference(
    context = context,
    source = OfflineVideoSource(
        fileInfo = fileInfo,
        localVideoFile = resolvedLocalFile,
        stagedAudioFile = stagedDemuxedAudioFile,
        expectedDurationMs = measuredVideoDurationMs,
    ),
    providerAnchorVtt = anchorWebVtt,
    providerAnchorKey = activeProviderKey,
    cachedSummary = existingManifest?.audioVadSummary,
    isAnime = isAnimeContext,
)

for (candidate in chosen) {
    val aligned = SubtitleTimelineAligner.alignToWebVtt(
        rawWebVtt = downloadedWebVtt,
        candidateSourceKey = DownloadSubtitlePolicy.resolveProvenance(candidate).effectiveSourceKey,
        reference = syncRef,
    )
    if (aligned is SyncOutcome.RejectedMismatch) continue
    saveSidecarAndRecordManifestV2(fileInfo, candidate, aligned, syncRef, activeProviderKey)
}

// Bol-Chal Urdu localization from the best audio-verified English sidecar:
val localized = BolChalUrduLocalizer.localizeToWebVtt(
    englishWebVttOrSrt = bestVerifiedEnglishVtt,
    mediaContext = BolChalMediaContext(
        title = fileInfo.displayName,
        season = fileInfo.seasonIndex,
        episode = fileInfo.episodeIndex,
        isAnime = isAnimeContext,
        donorProviderKey = bestEnglishDonorKey,
    ),
)
if (localized is BolChalLocalizationOutcome.Success) {
    saveLocalizedUrduSidecarV2(fileInfo, localized, donorProviderKey = bestEnglishDonorKey)
}
```

### Call site 3 — Host live ExoPlayer zero-stutter audio sync (`CS3IPlayer` & `GeneratorPlayer`)

```kotlin
import com.lagradost.cloudstream3.subtitles.sync.LiveAudioSyncSession
import com.lagradost.cloudstream3.ui.player.SyncingDefaultRenderersFactory
import com.lagradost.cloudstream3.ui.player.SyncingNextRenderersFactory
import com.lagradost.cloudstream3.utils.downloader.DownloadSubtitlePolicy
import com.lagradost.cloudstream3.utils.downloader.SubtitleRankingContext

// Inside CS3IPlayer.loadPlayer:
liveSyncSession?.close()
val syncSession = LiveAudioSyncSession(appContext)
liveSyncSession = syncSession

val renderersFactory = if (profile.useExtensionRenderers) {
    SyncingNextRenderersFactory(context, syncSession)
} else {
    SyncingDefaultRenderersFactory(context, syncSession) // Covers Android TV & HW-only mode!
}
val subtitleDecoderFactory = CustomSubtitleDecoderFactory(syncSession)

syncSession.startForMedia(
    link = activeExtractorLink,
    activeSubtitle = currentPreferredSubtitle,
    videoDurationMs = exoPlayer?.duration?.takeIf { it > 0L },
    onTextCursorInvalidateNeeded = ::resetTextRendererCursorOnly, // Zero video/audio flush!
)

// Manual user subtitle delay in CS3IPlayer (replaces CustomDecoder.subtitleOffset + seekTime(-1)):
override fun setSubtitleOffset(offset: Long) {
    liveSyncSession?.setUserOffsetMs(offset)
}

// Unified ranking across GeneratorPlayer, AppContextUtils.sortSubs, and DownloadFileGenerator:
val orderedSubs = DownloadSubtitlePolicy.rankSubtitles(
    candidates = availableSubs,
    context = SubtitleRankingContext(
        preferredLangTag = SubtitleModeManager.effectivePrimaryLanguageTag(context),
        activeVideo = currentSelectedLink,
        isAnime = isAnimeEpisode,
        manifestByFileName = loadedManifestEntries,
    ),
)
```

## Shape

### Core data structures

1. **`SubtitleMediaDescriptor` & `VerifiedTmdbEpisode` (extension boundary input)**: Sealed sum type (`Movie`, `TvEpisode`, `AnimeEpisode`) separating `seriesDebutYear` from `seasonAirYear` on TV episodes and gating season-indexed anime catalogue queries behind `VerifiedTmdbEpisode?` (`per encode-lessons-in-structure`). Runtime clues (`expectedDurationMs`, `releaseHint`) travel from `load()` into `loadLinks()` instead of being dropped.
2. **`SubtitleSessionCoordinator` (extension per-`loadLinks` state owner)**: Encapsulates per-session concurrent maps for priority-ordered English timing anchors (`ConcurrentSkipListMap` ordered by SOTA provider rank `100..87` ahead of generic catalogues, never arrival race order), content-addressed SHA-256 timing fingerprints (preventing `Wyzie` + `SubDL` duplicate `.srt` files from double-counting diversity), and per-session `SubtitleCat` `-orig.srt` fallback donors — eliminating every cross-episode global mutable map (`per separate-before-serializing-shared-state`).
3. **`SubtitleProvenance` & `SubtitleRankingContext` (host ranking model)**: Parses provenance once at boundary ingestion into `(effectiveSourceKey, originKind: STREAM_PROVIDER | HUMAN_CATALOGUE | BOLCHAL_LOCALIZED | UNKNOWN, donorSourceKey, syncConfidence, qeScore, mtDumpScore)` (`per boundary-discipline`). A localized sidecar (`UrduAI Wyzie` or `X-StreamPlay-Source: urdu-neural-fallback` + `X-StreamPlay-Donor-Source: vidlink`) has `originKind = BOLCHAL_LOCALIZED` and cannot inherit raw native catalogue priority; `UrduAI VidLink` outranks `UrduAI Wyzie` via `donorSourceKey`.
4. **`VideoSyncReference`, `SpeechActivitySummary`, & `PiecewiseAffineWarp` (alignment model)**:
   - `SpeechActivitySummary` stores observation windows `windowsMs: List<LongRange>` alongside compact `10 ms`-resolution `SpeechVadSpan(startMs, endMs, onsetConfidence)` entries (`< 12 KB` JSON in `SidecarManifest` v2), enabling observation-masked zero-mean `alass` scoring (`+w` speech overlap, `-0.25w` confirmed-silence overlap inside observed windows, `0` outside observed windows) and idempotent re-use on subsequent heals (`per make-operations-idempotent`).
   - `PiecewiseAffineWarp` represents sorted, monotonically non-decreasing affine segments `(startSubUs, endSubUs, scale, offsetUs)` so any `Subtitle` is transformed in $O(\log S)$ per `getEventTime` / `getCues` call without mutating cue strings or seeking ExoPlayer.
5. **`SceneTranslationWindow` & `SpeakerContextCard` (Bol-Chal localization model)**: Partitions English donor cues at silence gaps `>= 2,500 ms` into `25–35` cue LLM windows (or `15–20` cue keyless NMT paragraphs) with immutable `[cue_id]` (`⟦0014⟧`) tokens, read-only `[ctx_prev]`/`[ctx_next]` context, inferred speaker gender, and locked dyadic pronoun tier (`TU` / `TUM` / `AAP`, defaulting peer dialogue to `تم`).

### Data flow and load-bearing decisions

- **Single shared extension source tree (`shared/subtitles/src/main/kotlin`)**: Wired into both `StreamPlay/build.gradle.kts` and `SuperStream/build.gradle.kts` (`kotlin.directories.add(...)` without a `shared/build.gradle.kts` so `settings.gradle.kts` never packages `shared` as a standalone `.cs3` plugin). Deletes `UrduSubtitleEngine.kt`, `SubtitleSourceLedger.kt`, and ~2,400 lines of forked `SuperStream` Urdu/harvester code (`per laziness-protocol`).
- **Native provider Urdu acceptance + host anchor forwarding**: Video-provider Urdu sidecars (`VidLink`, `MeowTV`, `Cineby`, `CineSrc`, `VidFast`, `VidRift`, `SuperStream`) pass through `UrduMtDumpDetector` + WebVTT/RLM normalization and emit as `STREAM_PROVIDER` Urdu tracks. In Urdu mode, when the host sets `cloudstream.subtitle.vault_v2=true` or passes `BilingualDownloadSubtitleCallback`, `SubtitleSessionCoordinator` also forwards stream-synced English sidecars stamped `X-StreamPlay-Sync-Role: provider-anchor` so `RepoLinkGenerator.subtitleVault` and `DownloadManager` capture frame-locked anchors while `SubtitleModeManager.matchesSubtitleMode` keeps them out of the Urdu UI picker.
- **Unified `alass` + 16 kHz Speech VAD solver**: Both extension sidecar pre-alignment and host `SubtitleTimelineAligner` use the exact `RatingDeltaDelta` ($O(1)$ per overlapping pair) + 9-FPS `align_nosplit` pre-pass + top-2 monotonic-deque $O(1)$-amortized piecewise split DP (`dt = 10 ms`, RLE backpointers `< 500 KB`, `1.35x` outer-5% boundary split guard, `vadOnsetMs - 35 ms` median onset polish). Host offline/sparse-stream extraction decodes 5–6 dialogue-dense windows via `NextlibFfmpegAudioBridge` (in-package `io.github.anilbeesetti.nextlib.media3ext.ffdecoder` bridge to `FfmpegAudioDecoder`, zero reflection, `MediaCodec` fallback) into a zero-allocation 16 kHz formant + Wiener SFM + Rainer Martin noise-floor + 4 Hz syllabic-modulation VAD that rejects explosions and down-weights sung anime OP/ED choruses.
- **Zero-stutter live closed-loop convergence (`LiveAudioSyncSession`)**: Wraps `AudioSink` in both `SyncingNextRenderersFactory` and `SyncingDefaultRenderersFactory` (covering Android TV and HW-only mode), ignoring non-PCM HDMI passthrough (`Util.isEncodingLinearPcm`), decimating accepted PCM (`super.handleBuffer == true`) into a lock-free SPSC ring buffer in `< 20 µs`, combining sparse HLS/MP4 range probes (`elst` and per-discontinuity `rawPtsUs(seg_k) - rawPtsUs(seg_0)` anchored, `sanitizeCdnHeaders` enforced) with live speech spans, and updating `DynamicSyncedSubtitle` under an active-cue freeze + `45 ms` hysteresis + `<= 35 ms` silence-gap slewing.
- **What the system deliberately does not do**: Never calls `publishToSubtitleCatCdn` (`upload_subtitles.php` deleted in both repos); never splices Nastaliq Urdu into English before `sl=en&tl=ur`; never merges cues across ellipses or slices SOV Urdu by English character ratios; never caches or emits partial translations with `< 98%` cue survival; never runs `SimpleCache`-contending full-stream downloads for live audio probing.

### Interface depth judgment

Four deep facades (`SubtitleSessionCoordinator`, `SubtitleTimelineAligner`, `LiveAudioSyncSession`, `BolChalUrduLocalizer` + unified `DownloadSubtitlePolicy`) absorb ~5,800 lines of multi-phase orchestration, 8-catalogue scraping, 5-signal MT-dump scoring, 9-FPS + piecewise DP math, `FfmpegAudioDecoder` JNI bridging, HLS/MP4 container timestamp anchoring, lock-free PCM ring buffering, Media3 `TextRenderer` monotonicity management, and `[cue_id]` scene localization behind **15 public entry signatures total** (`per minimize-reader-load`). All parser helpers, orthography tables, DP buffers, and HTTP transports are `private` to their domain files so callers cannot import internals or bypass boundary invariants (`per boundary-discipline`).

## Synthesis decision

*Pending `arena` orchestrator synthesis across parallel Phase B candidates. Candidate 1 establishes the **Deep Domain-Module Facade** baseline: maximum layer compression via `shared/subtitles` + `SubtitleSessionCoordinator` in the extension repo and `SubtitleTimelineAligner` + `LiveAudioSyncSession` + `BolChalUrduLocalizer` + unified `DownloadSubtitlePolicy` in the host repo.*

## Tradeoffs accepted

- We accept compiling `shared/subtitles/src/main/kotlin` twice (into `:StreamPlay` and `:SuperStream` `.cs3` DEX bundles, adding ~95 KB per plugin) in exchange for eliminating ~2,500 lines of diverged copy-paste without altering CloudStream's single-module `.cs3` plugin packaging contract.
- We accept placing a ~90-line `NextlibFfmpegAudioBridge.kt` inside `package io.github.anilbeesetti.nextlib.media3ext.ffdecoder` in the host APK in exchange for direct, zero-reflection, 100x-realtime software decoding across all 18 FFmpeg audio codecs (`EAC3`, `AC3`, `AAC`, `Opus`, `Vorbis`, `DTS`, `FLAC`, `MP3`) that `nextlib` marks package-private.
- We accept freezing presentation timestamps of already-passed and currently-visible cues inside `DynamicSyncedSubtitle` (deferring sub-second slew to the next silence gap and using a text-only track reset only on `> 80 ms` initial cold-open/OP steps) in exchange for `0` `seekTime(-1)` video/audio decoder flushes and strict Media3 `TextRenderer` event-index monotonicity.
- We accept keeping a synchronized `SubtitleSourceRegistry` constant table in each repository enforced by a cross-repo parity test (`CrossRepoSubtitleRegistryContractTest`) in exchange for keeping the two git repositories independently buildable without cross-repo Gradle composite builds.
- We accept hard-rejecting catalogue Urdu tracks with `mtDumpScore >= 60` (and Tier-1C bulk-MT authors) in exchange for guaranteeing that video-synced `[cue_id]` Bol-Chal localization always wins over unaligned literal Google Translate dumps.

## Alternatives considered

- **Thin shared utility helpers (`UrduStringUtils` / `AlassMathUtils`) leaving orchestration in each caller**: Rejected because it is a textbook shallow-module split — all 4 plugin surfaces (`StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, `SuperStream`) and 3 host callers (`RepoLinkGenerator`, `GeneratorPlayer`, `DownloadManager`) would still hand-coordinate donor ordering, content-hash deduplication, Phase 3–5 fallbacks, and sync invocation, leaving race-order donor bugs and split ownership intact.
- **ExoPlayer `BaseAudioProcessor` (`TeeAudioProcessor`) + rewriting `.vtt` files via `setSubtitleOffset` / `reloadPlayer`**: Rejected because Media3 `1.9.3` `AudioProcessor.queueInput(ByteBuffer)` strips `presentationTimeUs` (drifting across playback speed changes and HLS discontinuities) and sees only `250–750 ms` of PCM ahead of the speaker, while `setSubtitleOffset` / remounting `.vtt` triggers `seekTime(-1)` / `reloadPlayer` stutters; `ForwardingAudioSink` + sparse HTTP/file probing + `DynamicSyncedSubtitle` hides PTS-exact closed-loop sync behind `LiveAudioSyncSession` with zero playback interruption.
- **Cross-cue sentence merging (`<= 1.5 s`) + proportional character-ratio slicing for Bol-Chal fluency**: Rejected because reordering English SVO continuation turns (`"The traitor is..."` | `"...your brother!"`) into a single Urdu SOV sentence moves the reveal object into Cue 1 before the actor speaks it; scene-windowed `[cue_id]` paragraphs give the NMT/LLM encoder full surrounding context while locking every cue's spoken revelation to its exact `[startMs, endMs]` window.

## Open questions and risks

1. When `UrduMtDumpDetector` scores a human-uploaded catalogue track in the middle `StiffEditedMt` band (`mtDumpScore in 30..59`) and a stream-synced English sidecar (`VidLink`/`Cineby`) is available, should `SubtitleSessionCoordinator` and `DownloadManager` emit *both* (ranking Bol-Chal localized `UrduAI <StreamProvider>` above `StiffEditedMt` when an LLM is configured, and `StiffEditedMt` above keyless NMT when `syncConfidence >= 0.85`), or suppress `StiffEditedMt` whenever stream-synced localization succeeds?
2. Should user-configured `BolChalLlmConfig` (`endpointUrl`, `apiKey`, `model`) ship with a UI card inside CloudStream's existing Subtitle Settings sheet in this PR, or stay wired via `SharedPreferences` (`cloudstream_subtitle_mode_prefs`) + `request_secret`-compatible settings keys first so UI layout changes don't expand PR scope?
3. When `nextlib` upgrades past `1.9.3-0.12.0`, could `FfmpegAudioDecoder`'s constructor signature change, and is a compile-time unit test instantiating `NextlibFfmpegAudioBridge` class metadata sufficient to fail the build immediately on any upstream `nextlib` bump?

## Next implementation step

Create the pre-change deterministic JUnit benchmark suites for `P_sync` (5 desync regimes) and `P_lang` (MT-dump detection, Nastaliq-free pre-normalization, `[cue_id]` reveal lock, and `visibleCps <= 17.0`) in both repositories, then scaffold `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/` and `com.lagradost.cloudstream3.subtitles.sync` against `types-and-signatures.kt`.
