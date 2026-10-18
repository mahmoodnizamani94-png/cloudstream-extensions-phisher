# StreamPlay & CloudStream SOTA Architecture Invariants (AGENTS.md)

This document defines the non-negotiable architectural invariants and priorities across `cloudstream-extensions-phisher` and the sibling host app `cloudstream`. Read and enforce these rules whenever modifying providers, extractors, rankings, playback dispatch, or download selection.

## 1. Core Quality & File-Size Priority (Non-Negotiable)
1. **720p with the Lowest File Size Possible is Priority #1**:
   - Both playback and downloads prioritize **720p HD** (`getQualityPriorityScore(720) = 10_000`) above all other resolutions.
   - Within 720p, downloads and streams **must** select the candidate with the **lowest file size possible** (derived from HTTP `Content-Length`, explicit `[XXX MB]` metadata, or HLS `#EXT-X-STREAM-INF` `AVERAGE-BANDWIDTH`/`BANDWIDTH` estimation).
2. **1080p with the Lowest File Size Possible is Priority #2**:
   - When 720p is unavailable, **1080p Full HD** (`getQualityPriorityScore(1080) = 9_000`) is the immediate next tier, strictly preferring the **lowest file size possible** first.
3. **Fallback Ladder**:
   - `720p (lowest size)` > `1080p (lowest size)` > `480p` > `360p` > `2160p`.

## 2. Curated v24 State-of-the-Art (SOTA) Source Registry (8 Sources)
All streams and downloads for Movies, TV Series, and Anime **must** come exclusively from the vetted SOTA registry (`TOP_TIER_RANKS = listOf(100, 96, 95, 94, 93, 92, 91, 90)` in `StreamLinkOptimizer`):

| Rank | ID | Display Name | Scope | Downloadable | Protocol & CDN Infrastructure |
|---|---|---|---|---|---|
| **100** | `vidlink` | **VidLink** | Universal (Movie, TV, Anime) | Yes | Offline `XSalsa20-Poly1305` (`VidLinkCrypto.encryptToken`) -> `vidlink.pro/api/b/{movie\|tv}/{token}` -> direct multi-resolution MP4 & HLS with native byte sizes and subtitles |
| **96** | `cineby` | **Cineby** | Universal (Movie, TV, Anime) | Yes | `api.wecollege.net/seed` + multi-server (`tampa`, `dallas`, `oakland`, `seattle`, `vegas`, `portland`) -> `dec-videasy` -> `sun.paleoak.top` / `e2e.majorplay.net` HLS + multi-language subtitles |
| **95** | `cinesrc` | **CineSrc** | Universal (Movie, TV, Anime) | Yes | `cinesrc.st` CSP4 + SHA-256 PoW (`wave`, `nebula`, `surge`) + `api.wing.st` (`enc-cinejoy`/`dec-cinejoy`) -> `ice.bright67.online` / `nebula.bright67.online` multi-variant HLS + `subs.bright67.online` multi-language subtitles |
| **94** | `vidfast` | **VidFast** | Movie, TV | Stream-only (`DOWNLOAD_EXCLUDED_PROVIDER_IDS`) | `vidfast.vc` page token -> `enc-vidfast` stage 1/2 -> `dec-vidfast` -> `moon.zenoak.top` HLS ladder |
| **93** | `vidsrcsu` | **VidSrcSU** | Movie, TV | Yes | `themoviedb.vidsrc.su` / `theemoviedb.hexa.su` (`X-Api-Key` + `X-Cap-Token` `images()`) -> `dec-hexa` -> `dragonballzfans.xyz` multi-CDN HLS + subtitles |
| **92** | `megaplay` | **MegaPlay** | Anime | Yes | `megaplay.buzz/stream/{mal\|ani}/{id}/{ep}/sub` -> `getSources?id={cid}&platform=OTHER` -> `dec-megaplay` -> `fetch.nexabloom.top` HLS + `broforgotsave.online` `.vtt` subtitles |
| **91** | `meowtv` | **MeowTV** | Movie, TV | Yes | `api.meowtv.ru` single-use ticket -> `dec-meowtv` -> `hscow.com` / `vidjoy.org` / `fcxmb.com` / `krico443jez.com` HLS |
| **90** | `animex` | **Animex** | Anime | Yes | `animex.one` GraphQL + `enc-animex` concurrent fan-out (`yuki`, `sora`, `loli`, `nero`) -> `fetch.nexabloom.top` / `hls.krussdomi.com` / `hlsxst2.echovideo.to` HLS + `subst.krussdomi.com` / `broforgotsave.online` `.vtt` subtitles |

## 3. Anime Playback & Download Contract
- When playing or downloading Anime (both in `StreamPlay` when `isAnime == true` and in `StreamPlay-Anime` / `StreamPlayAnime.kt`), streams and downloads **must** come from the top SOTA Anime sources:
  - **`VidLink` (100)** -> **`Cineby` (96)** -> **`CineSrc` (95)** -> **`MegaPlay` (92)** -> **`Animex` (90)**
- Anime streams and downloads **must** preserve **original language (Japanese / Original Audio)** and **subtitles**:
  - Subtitled original-audio anime streams are badged with `[Sub] [Original]` and accompanied by `.vtt`/`.srt` `SubtitleFile` emissions so `DownloadSelectionPolicy` (`likelyOriginalLanguage` and `hasEmbeddedSubtitles`) prioritizes them above dubbed or unsubtitled streams.
  - HLS master manifests containing demuxed `#EXT-X-MEDIA:TYPE=AUDIO` renditions with external `URI=` preserve the master manifest URL (using `#vid={quality}p` fragments for distinct quality routing) so ExoPlayer and `HLSDownloader` always mux the original audio track instead of downloading silent video-only segments.

## 4. Permanently Banned / Decommissioned Sources
- **VidRock (`vidrock`, `vidrock.net`, `galacticvoyagers.xyz`, `moonfirekeeper.lol`) and VidSrc (`vidsrc`, `vidsrc.pm`, `cheaptruckrepairs.cc`, `netoda.tech`, `mov2day.xyz`, `vidora`) are permanently banned and stripped**:
  - VidRock serves non-seekable, index-less files and VidSrc stalls/throttles downloads after ~1 MB; neither may ever be re-added or used for downloading. Both reside permanently in `REMOVED_PROVIDER_IDS` (migration scrub), score rank `0` in `StreamLinkOptimizer.getSourcePriorityRank`, and are rejected by the host download pipeline (`StreamPlayDynamicBridge` static exclusions -> `DownloadSelectionPolicy` terminal stage `999`).
- Legacy dead/hijacked providers (`animepahe`, `yflix`, `cinejoy`, `videasy`, `rivestream`, `videm`, `animegg`, `anizone`, `anichi`, `anikage`, `hianime`, etc.) remain decommissioned; as of v24 their extractor implementations, anime fan-out tasks, and dedicated tests are fully stripped from the codebase (only `REMOVED_PROVIDER_IDS` migration entries survive).

## 5. Cross-Repo Synchronization
- Keep `cloudstream-extensions-phisher` (`ProvidersList.kt`, `StreamLinkOptimizer.kt`, `SpeculativePipeliner.kt`, `OptimizedDns.kt`, `StreamPlayShield.kt`) and `cloudstream` (`StreamPlayDynamicBridge.kt`, `DownloadSelectionPolicy.kt`, `StreamPlaySanitizer.kt`) strictly synchronized.
