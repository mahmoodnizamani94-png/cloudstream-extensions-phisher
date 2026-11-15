# Module map — Candidate 1 (Deep domain-module facade)

## 1. Repository 1: `cloudstream-extensions-phisher`

Worktree root: `/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf`

### Build wiring invariant (`shared/subtitles` without `shared/build.gradle.kts`)

[settings.gradle.kts:12-19](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/settings.gradle.kts#L12-L19) auto-includes every top-level directory containing a `build.gradle.kts` as a standalone `.cs3` plugin subproject. `shared/subtitles/src/main/kotlin` therefore has **no** `build.gradle.kts` and is mounted directly via AGP `9.1.1` `LibraryExtension.sourceSets` in both plugin modules:

```kotlin
// Added inside `android { ... }` in both StreamPlay/build.gradle.kts and SuperStream/build.gradle.kts:
sourceSets {
    named("main") {
        kotlin.directories.add(rootProject.file("shared/subtitles/src/main/kotlin").path)
    }
}
```

### Files created (`cloudstream-extensions-phisher`)

| File path (relative to repo root) | Public surface (`package com.phisher98.subtitles`) | File-`private` internals hidden |
|---|---|---|
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleMediaDescriptor.kt` | `SubtitleMediaDescriptor` (`Movie`, `TvEpisode`, `AnimeEpisode`), `VerifiedTmdbEpisode`, `SubtitleSurface`, `SubtitleWireHeaders` | Title normalization & year-sanity helpers |
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleModeBridge.kt` | `SubtitleModeBridge`, `BolChalLlmConfig` | Cross-classloader `SharedPreferences` / `System.getProperty` readers |
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/SubtitleSessionCoordinator.kt` | `SubtitleSessionCoordinator`, `RetryableVideoProvider`, `SessionSubtitleStats` | Per-session `anchorDonors` priority map, `uniqueUrduHashes` SHA-256 ledger, `UnifiedSubtitleCatalogues` (all 8 Urdu + 6 English scrapers), `SubtitleCasStore` (`cs_urdu_v2_<sha256_24>.vtt`, 256-file / 64 MiB active CAS separated from metadata prewarm LRU) |
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/AlassTimelineSolver.kt` | `AlassTimelineSolver`, `SubtitleCue` | `RatingDeltaDelta` 96 KB double-prefix buffer, monotonic split deque, RLE backpointer intervals, 9-FPS `alignNoSplit` pre-pass, `35 ms` onset residual polisher |
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/UrduMtDumpDetector.kt` | `UrduMtDumpDetector`, `CatalogueCandidateMeta`, `MtSignalFamily`, `MtRejectReason` | ZIP/GZIP/UTF-16/CP1256 decoder, episode-matching archive selector, pre-normalization Urdu vs Persian/Arabic orthography tables, Tier-1C bulk-MT uploader blacklist, 5-signal NMT calque/whiplash/particle scorers |
| `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/BolChalUrduLocalizer.kt` | `BolChalUrduLocalizer`, `LocalizationBackend` | Plain-English pre-normalizer (`0` Nastaliq chars), SDH stripper, `25–35` cue scene windower, `SpeakerContextCard` (`تم`/`آپ`/`تو` + gender lock), OpenAI/Gemini LLM transport, keyless `⟦cue_id⟧` NMT transport with 1-cue bisection fallback, post-NMT spoken calque/Hindi-loanword rewriter, `visibleCps <= 17.0` gap-extension + clause-split refiner |
| `StreamPlay/src/test/kotlin/com/phisher98/subtitles/SubtitleSyncAndBolChalBenchmarkTest.kt` | JUnit `P_sync` + `P_lang` deterministic benchmark + `CrossRepoSubtitleRegistryContractTest` | Synthetic 5-regime cue fixtures & NMT-dump vs human Urdu fixtures |

### Files deleted (`cloudstream-extensions-phisher`)

| Deleted file path | Lines removed | Replacement |
|---|---|---|
| [StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt) | `-3,858` | `shared/subtitles/src/main/kotlin/com/phisher98/subtitles/*` |
| [StreamPlay/src/main/kotlin/com/Phisher98/SubtitleSourceLedger.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/SubtitleSourceLedger.kt) | `-95` | `SubtitleSessionCoordinator` internal content-hash + provenance ledger |

### Files modified (`cloudstream-extensions-phisher`)

| File path | Exact scope of modification |
|---|---|
| [StreamPlay/build.gradle.kts](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/build.gradle.kts) | Mount `shared/subtitles/src/main/kotlin` in `main` sourceSet; bump monotonic plugin `version`. |
| [SuperStream/build.gradle.kts](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/build.gradle.kts) | Mount `shared/subtitles/src/main/kotlin` in `main` sourceSet; bump monotonic plugin `version`. |
| [StreamPlay/src/main/kotlin/com/Phisher98/StreamPlay.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/StreamPlay.kt) | Carry `runtimeMinutes` & `seriesDebutYear` vs `seasonAirYear` in `LinkData`; replace `prewarmUrduSubtitlesAsync`, `emitSubtitle`, `flushDeferredSubtitles`, and Phase 3–5 loops with `SubtitleSessionCoordinator`. |
| [StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayAnime.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayAnime.kt) | Carry `runtimeMinutes` & `VerifiedTmdbEpisode?` in `LinkData`; kill `effectiveSubSeason = tmdbSeason ?: 1` bug; use `recommendedEarlyMinSubtitles(6)` and `SubtitleSessionCoordinator`. |
| [StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayStremioCatelog.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayStremioCatelog.kt) | Replace `consumeSubtitleCatOrigCandidates()` global wipe, `trackedSubCallback`, and Phase 3–5 block with `SubtitleSessionCoordinator`. |
| [StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayExtractor.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/main/kotlin/com/Phisher98/StreamPlayExtractor.kt) | Delete ~1,050 lines of duplicated catalogue scraper bodies (`2750–3805`), delegating any legacy extractor entrypoints if needed while **strictly preserving** all `invokeVidlink`/`invokeMeowTV`/`invokeCineby`/`invokeCineSrc`/`invokeMegaPlay`/`invokeVidRift`/`invokeAniZone`/`invokeAnimePahe`/`invokeAnimex` JVM method signatures reflected by host `StreamPlayDynamicBridge`; stop spoofing `animepahe`/`meowtv` provenance on `AniZone` companion sidecars. |
| [SuperStream/src/main/kotlin/com/phisher98/SuperStream.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/src/main/kotlin/com/phisher98/SuperStream.kt) | Carry movie/episode `runtimeMinutes` in `LinkData`; replace `clearSubtitleCatDonors()`, `trackedSubtitleCallback`, and Phase 3–5 loops with `SubtitleSessionCoordinator`. |
| [SuperStream/src/main/kotlin/com/phisher98/SuperStreamExtractor.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/src/main/kotlin/com/phisher98/SuperStreamExtractor.kt) & [SuperStreamUtils.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/src/main/kotlin/com/phisher98/SuperStreamUtils.kt) | Delete ~2,450 lines of forked Urdu scrapers, charset decoders, SMT regexes, and NMT batchers; report `fileList.fileName` / `version.name` to `session.recordVideoReleaseHint(...)`. |
| [StreamPlay/src/test/kotlin/com/phisher98/UrduSubtitleSotaTest.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/test/kotlin/com/phisher98/UrduSubtitleSotaTest.kt), [SubtitleCompletionPhaseTest.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/StreamPlay/src/test/kotlin/com/phisher98/SubtitleCompletionPhaseTest.kt), [SuperStreamUrduSubtitleTest.kt](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/SuperStream/src/test/kotlin/com/phisher98/SuperStreamUrduSubtitleTest.kt) | Point tests at `com.phisher98.subtitles.*`; replace any assertions on deleted `publishToSubtitleCatCdn` or `SubtitleCat`-first donor priority. |
| [context.md](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/context.md) & [AGENTS.md](/home/mahmood/.t3/worktrees/cloudstream-extensions-phisher/feat-urdu-bolchal-audio-999f345cb2d84bbf/AGENTS.md) | Synchronize shared subtitle architecture, provenance headers, `alass` alignment, and Bol-Chal contracts with host repo. |

---

## 2. Repository 2: `cloudstream` (Host App)

Worktree root: `/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17`

### Files created (`cloudstream`)

| File path (relative to repo root) | Visibility & public surface | Hidden internals (`private` / package-internal) |
|---|---|---|
| `app/src/main/java/io/github/anilbeesetti/nextlib/media3ext/ffdecoder/NextlibFfmpegAudioBridge.kt` | `internal interface AudioPacketDecoder`, `internal object NextlibFfmpegAudioBridge` | Direct access to package-private `FfmpegAudioDecoder` & `FfmpegLibrary.getCodecName`; ADTS -> 2-byte `AudioSpecificConfig` (`csd-0`) synthesizer |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/PcmSpeechVadEngine.kt` | `internal class PcmSpeechVadEngine` | 5.1 `FC` isolation, stereo Mid/Side ratio, 3:1 FIR decimation to 16 kHz, 300–3400 Hz Butterworth biquads, 256-pt real FFT Wiener SFM, Rainer Martin 8x200ms noise floor, 4 Hz syllabic resonator, sung-OP duty-cycle down-weighter |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/AlassDpCore.kt` | `internal object AlassDpCore` | 9-FPS `RatingDeltaDelta` (`dt = 10 ms`), observation-masked zero-mean sparse-window scoring (`+w` speech, `-0.25w` silence, `0` unobserved), monotonic-deque piecewise DP, RLE backpointers (`< 500 KB`), `vadOnsetMs - 35 ms` median residual polish |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/SparseMediaAudioProber.kt` | `internal object SparseMediaAudioProber` | Offline `MediaExtractor` + `NextlibFfmpegAudioBridge`/`MediaCodec` multi-window seek decoder; live HLS (`#EXT-X-MEDIA:TYPE=AUDIO` preferred, `rawPtsUs(seg_k) - rawPtsUs(seg_0)` per `#EXT-X-DISCONTINUITY`) & MP4 (`moov` + `elst` + 3 bounded `Range` GETs via `sanitizeCdnHeaders`) probers |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/SubtitleTimelineAligner.kt` | `object SubtitleTimelineAligner`, `OfflineVideoSource`, `VideoSyncReference`, `SpeechActivitySummary`, `PiecewiseAffineWarp`, `CommittedTimeWarp`, `SyncOutcome` | Cross-verification of provider anchor VTT against sparse audio VAD; WebVTT timestamp serializer |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/sync/LiveAudioSyncSession.kt` | `class LiveAudioSyncSession` | `private class ForwardingVadAudioSink` (`< 20 µs` SPSC ring copy on `super.handleBuffer == true`, `Util.isEncodingLinearPcm` HDMI guard), `private class DynamicSyncedSubtitle` (active-cue & past-event freeze, `45 ms` hysteresis, `<= 35 ms` silence-gap slewing) |
| `app/src/main/java/com/lagradost/cloudstream3/subtitles/bolchal/BolChalUrduLocalizer.kt` | `object BolChalUrduLocalizer`, `BolChalLlmConfig`, `BolChalMediaContext`, `BolChalLocalizationOutcome`, `UrduAuthenticityReport` | `private object HostUrduMtDumpDetector`, plain-English pre-normalizer, scene windower, `SpeakerContextCard`, Window-0 progressive streamer, LLM + keyless `⟦cue_id⟧` NMT transports, `CPS <= 17.0` waterfall |
| `app/src/test/java/com/lagradost/cloudstream3/subtitles/SubtitleAudioSyncAndBolChalBenchmarkTest.kt` | Deterministic 5-regime `P_sync` + `P_lang` + `DynamicSyncedSubtitle` monotonicity JUnit suite | Pure-JVM synthetic PCM/WAV speech + explosion + sung-OP generator & `CrossRepoSubtitleRegistryContractTest` |

### Files deleted (`cloudstream`)

| Deleted file path | Lines removed | Replacement |
|---|---|---|
| [app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/UrduSubtitleTranslator.kt) | `-842` | `com.lagradost.cloudstream3.subtitles.bolchal.BolChalUrduLocalizer` (`publishToSubtitleCatCdn` permanently deleted) |

### Files modified (`cloudstream`)

| File path | Exact scope of modification |
|---|---|
| [DownloadSubtitlePolicy.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadSubtitlePolicy.kt) | Add `SubtitleProvenance` (`BOLCHAL_LOCALIZED` vs `STREAM_PROVIDER` vs `HUMAN_CATALOGUE`, fixing `UrduAI Wyzie -> 110` leak), `SubtitleRankingContext`, `rankSubtitles(...)` (stream-synced providers outrank generic catalogues on Movies/TV & Anime; `sidecarPriorityForKey` respects `isAnime`), and `selectDownloadCandidates(...)` (`MIN_MOVIE_TV_STREAM_SYNCED_SLOTS = 2`). |
| [SubtitleSyncAndManifest.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/SubtitleSyncAndManifest.kt) | Remove legacy histogram `SubtitleSyncEngine` (`31–502`, replaced by `SubtitleTimelineAligner`); upgrade `SidecarManifest` & `SidecarManifestEntry` to schema v2 (`videoProviderKey`, `isAnime`, `audioVadSummary`, `donorProviderKey`, `fpsRatio`, `piecewiseSplitCount`, `mtDumpScore`). |
| [FallbackSubtitleHarvester.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/FallbackSubtitleHarvester.kt) | Delete SubtitleCat State B translation/CDN upload (`3165–3226`); enforce hard HI/SDH & Tier-1C bulk-MT uploader rejection + FPS/runtime/`WEB-DL` scoring across all 8 Urdu scrapers; run `BolChalUrduLocalizer.evaluateUrduTrackAuthenticity` on SubtitleCat State A `-ur.srt`. |
| [SubtitleModeManager.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/subtitles/SubtitleModeManager.kt) | Stamp `System.setProperty("cloudstream.subtitle.vault_v2", "true")` alongside `cloudstream.subtitle.mode` so plugins forward `provider-anchor` English tracks in Urdu mode. |
| [SubtitleMaterializer.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/subtitles/SubtitleMaterializer.kt) | Invoke `SubtitleTimelineAligner.alignLiveMaterializedWebVtt(...)` when an active stream-synced anchor exists in `RepoLinkGenerator.subtitleVault`. |
| [RepoLinkGenerator.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/RepoLinkGenerator.kt) | Stop clobbering `nameSuffix = "$suffixCount"` (preserve provider badge + `X-StreamPlay-Source`); in `harvestFallbackUrduSubtitles`, localize the best stream-synced English anchor via `BolChalUrduLocalizer` whenever no stream-synced or high-confidence human Urdu track exists. |
| [DownloadFileGenerator.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/DownloadFileGenerator.kt) | Attach `SidecarManifestEntry` v2 provenance/confidence/QE headers onto `SubtitleData` and rank via `DownloadSubtitlePolicy.rankSubtitles` using `manifest.videoProviderKey`. |
| [AppContextUtils.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt) | Delete standalone `SUBTITLE_SORT_COMPARATOR`; delegate `sortSubs` directly to `DownloadSubtitlePolicy.rankSubtitles`. |
| [GeneratorPlayer.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt) | Pass active `ExtractorLink` + anime context into `DownloadSubtitlePolicy.rankSubtitles`; route `harvestOrSynthesizeLiveUrduSubtitle` through `BolChalUrduLocalizer` with Window-0 streaming. |
| [FixedNextRenderersFactory.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt), [CustomSubtitleDecoderFactory.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt), [CS3IPlayer.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt) | Add `SyncingNextRenderersFactory` & `SyncingDefaultRenderersFactory` (covering Android TV & HW-only mode); wire `LiveAudioSyncSession` into `CustomSubtitleDecoderFactory` and `CS3IPlayer` (`setSubtitleOffset` updates `LiveAudioSyncSession` with `0` `seekTime(-1)` calls; cancel session on `releasePlayer` / link switch). |
| [DownloadManager.kt](/home/mahmood/.t3/worktrees/cloudstream/urdu-bolchal-audio-sync-17/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt) | Pass staged demuxed HLS/DASH/SAF audio file into `SubtitleTimelineAligner.prepareOfflineReference`; persist & reload `videoProviderKey` + `audioVadSummary` in `SidecarManifest` v2 during `ensureSubtitlesForDownloadedEpisode`; enforce Movie/TV stream-synced slot reservation (`MIN_MOVIE_TV_STREAM_SYNCED_SLOTS = 2`); localize Urdu via `BolChalUrduLocalizer`. |
| Existing subtitle unit tests (`SubtitleDownloadPipelineTest.kt`, `UrduModeSwitchAndDownloadPipelineTest.kt`, `UrduSubtitleSotaPipelineTest.kt`, `Pr70BilingualReviewRegressionTest.kt`) | Update assertions that previously locked generic-catalogue-over-`VidLink` priority (`wyzie 110 > vidlink 100`) and SubtitleCat State B CDN upload; add regression coverage for `UrduAI Wyzie` vs `UrduAI VidLink` and offline heal provider-anchor retention. |
| `context.md` & `AGENTS.md` | Synchronize host & extension subtitle ranking, `alass` + 16 kHz VAD sync, `SidecarManifest` v2, and Bol-Chal contracts. |

---

## 3. Call-chain depth & design red-flag screening

### Call-chain depth (`<= 3` files from entry to domain work)

- **Extension `loadLinks`**: `StreamPlay.kt` -> `SubtitleSessionCoordinator.kt` -> (`AlassTimelineSolver.kt` / `UrduMtDumpDetector.kt` / `BolChalUrduLocalizer.kt`) = **3 files**.
- **Host offline download/heal**: `DownloadManager.kt` -> `SubtitleTimelineAligner.kt` -> (`SparseMediaAudioProber.kt` / `AlassDpCore.kt`) = **3 files**.
- **Host live audio-subtitle sync**: `CS3IPlayer.kt` -> `LiveAudioSyncSession.kt` -> (`PcmSpeechVadEngine.kt` / `AlassDpCore.kt`) = **3 files**.
- **Host subtitle ranking**: `GeneratorPlayer.kt` (or `AppContextUtils.kt` / `DownloadFileGenerator.kt`) -> `DownloadSubtitlePolicy.kt` = **2 files**.

### Screening against `design-red-flags.md`

| Red flag | Status | Structural enforcement in Candidate 1 |
|---|---|---|
| **Shallow module** | Clear | 4 deep facades (`SubtitleSessionCoordinator`, `SubtitleTimelineAligner`, `LiveAudioSyncSession`, `BolChalUrduLocalizer`) hide ~5,800 lines behind 15 domain methods. |
| **Information leakage** | Clear | Zero HTTP/JSON/JNI/`X-StreamPlay-*` header strings exposed to callers; `SubtitleProvenance` & `SubtitleMediaDescriptor` parse wire/config inputs once at boundaries. |
| **Temporal decomposition** | Clear | Modules are organized by domain ownership (`SessionCoordinator`, `TimelineAligner`, `LiveAudioSyncSession`, `BolChalLocalizer`), not `Loader`/`Validator`/`Transformer`/`Saver` stages. |
| **Pass-through method** | Clear | `UrduSubtitleEngine.kt`, `SubtitleSourceLedger.kt`, `UrduSubtitleTranslator.kt`, and `AppContextUtils.SUBTITLE_SORT_COMPARATOR` are deleted rather than kept as forwarding shells. |
| **Split ownership** | Clear | Per-request donor state owned solely by `SubtitleSessionCoordinator`; ranking owned solely by `DownloadSubtitlePolicy`; live subtitle offset owned solely by `LiveAudioSyncSession`; on-disk sync state owned solely by `SidecarManifestStore` v2. |
| **Two ways to do one task** | Clear | Forked `SuperStream` Urdu engine deleted; legacy histogram `SubtitleSyncEngine` deleted; decode-time `CustomDecoder.subtitleOffset` regex shifting deleted. |
| **Importable internals** | Clear | Extension scraper parsers, orthography tables, DP arrays, and transports are `private` to their `.kt` files; host `NextlibFfmpegAudioBridge`, `PcmSpeechVadEngine`, `AlassDpCore`, and `SparseMediaAudioProber` are `internal` / `private` (`ForwardingVadAudioSink` and `DynamicSyncedSubtitle` are `private` inside `LiveAudioSyncSession.kt`). |
| **Hand-synced list** | Clear | Single `shared/subtitles` source list across `:StreamPlay` and `:SuperStream`; cross-repo source table guarded by `CrossRepoSubtitleRegistryContractTest`. |
