# Arena Synthesis: Issue #17 Unified Cross-Repo Design

## 1. Decision Summary

- **Selected Base**: **Candidate 2** (`Algebraic Type-Safe Pipeline & Pure-Core/Thin-Shell`, `.audit/arena/candidate-2/`).
- **Grafted from Candidate 1** (`Deep Domain-Module Facade`, `.audit/arena/candidate-1/`):
  1. `cloudstream.subtitle.vault_v2=true` + `X-StreamPlay-Sync-Role: provider-anchor` so live streaming in Urdu mode forwards stream-synced English anchors into `RepoLinkGenerator.subtitleVault` while `SubtitleModeManager.matchesSubtitleMode` keeps them hidden from the Urdu picker.
  2. Metadata-only detail-page prewarm (`prewarmMetadataAsync`) + two-tier `UrduCasStore` so browsing detail pages never evicts active/queued episode `.vtt`/`.srt` files.
  3. Staged-audio file hook in `DownloadManager` (`stagedAudioFile` during HLS/DASH/SAF download verification) feeding `AudioSpeechExtractor` without re-demuxing completed video files.
  4. `Window-0` progressive live bol-chal localization (`onWindowZeroReady` for the first 25 cues in `< 600 ms`) while background windows finish.

## 2. Critical Reconciliations Against Existing Repo Contracts & Tests

1. **Extension `StreamPlaySettingsUiUxSotaTest` Guardrails**:
   - `StreamPlaySettingsUiUxSotaTest.kt:187` asserts `StreamPlay/src/main/kotlin/com/Phisher98/UrduSubtitleEngine.kt` exists on disk and scans `StreamPlay.kt`, `StreamPlayAnime.kt`, `StreamPlayStremioCatelog.kt`, `StreamPlayConcurrency.kt`, and `UrduSubtitleEngine.kt` against `bannedTokens` (including `"Harvesters"`, `"Neural fallback"`, `"0ms switch"`, `"JSON tiers"`, flag emojis, and off-palette hexes).
   - Therefore:
     - Keep `UrduSubtitleEngine.kt` as a slim mode/facade delegating to `com.phisher98.subtitles.*` (deleting `publishToSubtitleCatCdn`, `splitTranslatedUrduProportionally`, `ENGLISH_PRE_TRANSLATION_IDIOM_ADAPTERS` Nastaliq splicers, `isBrokenSmtUrdu`, and global `pendingSubtitleCat*` maps).
     - Name the shared catalogue client `SubtitleCatalogSources.kt` (never containing `"Harvesters"` in any identifier imported by `StreamPlay*.kt` or `UrduSubtitleEngine.kt`).
2. **Host `Pr70BilingualReviewRegressionTest`, `FallbackSubtitleHarvesterTest`, & `AnimeDownloadPolicyTest` Guardrails**:
   - Keep `DownloadSubtitlePolicy.subtitleSourcePriority(data)` returning the intra-registry marker scores (`wyzie=110`, `subtitleapi=105`, `stremio=104`, `opensubtitles=104`, `addic7ed=103`, `yify=103`, `subtitlecat=102`, `vidlink=100`, `meowtv=98`, `cineby=95`, `cinesrc=94`), while fixing `markerForText` via `ProvenanceCodec` so `UrduAI <X>` / `urdu-neural-fallback:<x>` ALWAYS resolves to `urdu-neural-fallback` (`priority = 80`, `sidecarPriorityForKey = 0`), never `<X>` (`110`).
   - Route candidate ordering across `DownloadSubtitlePolicy.selectDownloadSubtitleData`, `AppContextUtils.sortSubs`, `RepoLinkGenerator`, `GeneratorPlayer`, and `DownloadFileGenerator` through `SubtitleRankingPolicy` (`OriginRankBand` first: `NATIVE_MATCHED_VIDEO_PROVIDER > NATIVE_OTHER_STREAM_PROVIDER > ALIGNED_AUTHENTIC_HUMAN_CATALOGUE > BOLCHAL_FROM_STREAM_PROVIDER > ALIGNED_STIFF_HUMAN_CATALOGUE > BOLCHAL_FROM_HUMAN_CATALOGUE > UNVERIFIED_HUMAN_CATALOGUE > LOW_CONFIDENCE_FALLBACK`), plus `isAnime`-aware provider ranking so `ANIME_NATIVE_SOURCE_PRIORITIES` never leaks onto live-action Movies/TV.
   - Keep `SubtitleSyncEngine` (in `SubtitleSyncAndManifest.kt`) and `UrduSubtitleTranslator` as clean host facades delegating directly to `AlassAligner` / `WebVttBaker` and `SceneBolChalLocalizer` / `UrduQualityGate`, and upgrade `SidecarManifest` + `SidecarManifestEntry` in-place to `schemaVersion = 2` (`videoProviderKey`, `bakedContentSha256`, `preWarpTimingSha256`, `appliedWarpSpec`, `anchorKind`, `speechTimelineBlob`, `mtDumpScore`) with default field values so all PR #70 callers and tests remain binary/source compatible.
3. **`WarpedSubtitle` Exact Microsecond Monotonicity Invariant (`P_sync.3`)**:
   - Integer truncation in `warp.inverseUs(timeUs)` at exact knot/event boundaries can violate Media3 `Subtitle.getNextEventTimeIndex(timeUs)`'s postcondition `getEventTime(result) > timeUs`.
   - `WarpedSubtitle.getNextEventTimeIndex(timeUs)` MUST binary-search `0 until delegate.eventTimeCount` directly on `getEventTime(mid) > timeUs` (returning `C.INDEX_UNSET` if none), and `getCues(timeUs)` MUST locate the active interval `idx = upperBound(timeUs) - 1` via `getEventTime` and query `delegate.getCues(delegate.getEventTime(idx))` when `idx >= 0` (`emptyList()` when `idx < 0`), guaranteeing zero boundary rounding mismatch.

## 3. Shared Wire & Persistence Contracts (Locked Across Both Repos)

- **Provenance Wire Headers (`ProvenanceCodec`)**:
  - `X-StreamPlay-Source`: canonical provider or catalogue wire id (`vidlink`, `meowtv`, `cineby`, `cinesrc`, `vidfast`, `megaplay`, `vidrift`, `anizone`, `animepahe`, `animex`, `febbox`, `wyzie`, `subtitleapi`, `opensubtitles`, `stremio`, `subdl`, `addic7ed`, `yify`, `subsource`, `subsense`, `subf2m`, `subtitlecat`, or `urdu-neural-fallback` for all translated tracks).
  - `X-StreamPlay-Origin`: `stream` | `catalogue` | `bolchal`.
  - `X-StreamPlay-Donor`: `stream:<provider>` | `cat:<catalogue>` (present when `X-StreamPlay-Origin == bolchal`).
  - `X-StreamPlay-Engine`: `llm-scene` | `keyless-bolchal` | `mlkit-offline`.
  - `X-StreamPlay-Sync-Role`: `provider-anchor` (emitted on stream-synced English anchors when `BilingualDownloadSubtitleCallback` or `cloudstream.subtitle.vault_v2=true` is active so host `subtitleVault` captures them without polluting the Urdu UI picker).
  - `X-StreamPlay-Sync`: compact alignment proof (`anchorKind|confidence|warpSpec`).
- **System Properties / Preferences**:
  - `cloudstream.subtitle.mode` (`default` | `urdu`)
  - `cloudstream.subtitle.vault_v2` (`true` when host supports vault-only `provider-anchor` ingestion)
  - Optional BYOK Bol-Chal LLM keys read identically from `SharedPreferences("cloudstream_urdu_bolchal")` / system properties (`cloudstream.urdu.llm.endpoint`, `cloudstream.urdu.llm.model`, `cloudstream.urdu.llm.key` — never logged or baked into CAS keys directly; only non-secret model/mode config hash enters CAS keys).
