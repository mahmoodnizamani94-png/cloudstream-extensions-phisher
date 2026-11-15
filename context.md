# Subtitle Source Matrix (extension repo)

This is the extension-side companion to `cloudstream/context.md`. The binding rules live in
`AGENTS.md`; this file records **which catalogues and stream sidecars the extensions query**, how
`shared/subtitles` (`com.phisher98.subtitles`) gates, aligns, and localizes subtitles, and which
catalogues were evaluated and rejected. Update both files together whenever a source changes.

## 1. Why the extensions harvest and align subtitles in-session

A top-tier provider can return a clean 720p stream without an English or Urdu sidecar, or return an
English sidecar from a specific release cut (`NF.WEB-DL`, `BluRay`) while human Urdu archives host a
different rip. Waiting for the host's post-download healing pass is too late for direct playback, so
`:StreamPlay` (`StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`) and `:SuperStream`
(`SuperStream`, `SuperStreamExtractor`) compile the shared `shared/subtitles/src/main/kotlin` source
set (`com.phisher98.subtitles`) and run a per-invocation `EpisodeSubtitleSession`.

Every emitted `SubtitleFile` carries structured provenance headers via `ProvenanceCodec`:
- `X-StreamPlay-Source`: canonical provider or catalogue wire ID (`vidlink`, `febbox`, `subdl`, `urdu-neural-fallback`, etc.)
- `X-StreamPlay-Origin`: `stream`, `catalogue`, or `bolchal`
- `X-StreamPlay-Authenticity`: `AUTHENTIC_HUMAN` or `POST_EDITED_STIFF` (for human catalogue tracks)
- `X-StreamPlay-Sync-Confidence` and `X-StreamPlay-Sync-Residual-Ms`: alignment proof telemetry
- `X-StreamPlay-Sync-Role: provider-anchor`: stamped on the stream-locked English reference track when `SessionSubtitleMode.BILINGUAL_DOWNLOAD` or `cloudstream.subtitle.vault_v2=true` is active

## 1b. Unified `EpisodeSubtitleSession` Modes & Pipeline (`StreamPlay` + `SuperStream`)

`UrduSubtitleEngine` (in `:StreamPlay`) and `SuperStreamUtils` / `SuperStreamExtractor` (in `:SuperStream`)
delegate mode resolution, BYOK LLM credentials, and session execution to `SubtitleModeBridge` and
`EpisodeSubtitleSession`:

1. **`SessionSubtitleMode.DEFAULT` (`"default"`, English)**:
   - Out-of-the-box default. Emits verified English provider sidecars and English catalogue tracks (`TARGET_ENGLISH_SUBTITLE_SOURCES = 5` in `StreamPlay`), filtering hearing-impaired/SDH variants and non-English tracks.
2. **`SessionSubtitleMode.URDU` (`"urdu"`, Urdu `اردو`)**:
   - **Metadata-Only Detail-Page Pre-Warm (`load()`)**: `UrduSubtitleEngine.prewarmUrduSubtitlesAsync` and `SuperStreamExtractor.prewarmSuperStreamUrduSubtitlesAsync` call `UrduCasStore.prewarmMetadataAsync` (10-minute per-title cooldown). Pre-warming populates an isolated in-memory metadata LRU cache only and never writes or evicts aligned subtitle files on disk.
   - **Priority Tier 1: Stream-Native Urdu Sidecars (`VerifiedSubtitleCandidate.StreamSyncedNative`)**: Native Urdu sidecars from stream providers (`StreamProviderKey`: `VidLink`, `MeowTV`, `Cineby`, `CineSrc`, `MegaPlay`, `VidRift`, `AniZone`, `Animex`, `FebBox`, `KickAssAnime`) pass `UrduQualityGate.admitNativeOrCatalogue` and emit **first** (`Urdu [VidLink]`, `Urdu [FebBox]`, etc.), never discarded or overwritten by machine translation.
   - **Priority Tier 2: `AlassAligner`-Synced Human Catalogue Urdu (`VerifiedSubtitleCandidate.AlignedHumanCatalogue`)**: `SubtitleCatalogSources` queries all 8 Urdu-capable catalogues (`SubDL`, `SubSource`, `Wyzie`, `SubSense`, `YIFY`, `Subf2m`, `OpenSubtitles`, plus `SubtitleCat` donor/archive). Each candidate passes:
     - `CatalogueCandidateGate.evaluatePreDownload`: rejects hearing-impaired/SDH tags, banned MT uploaders (`blackgold`, `iamdesigora`, `akhaliq_655`, `saad.jan`, `tryray`, `chatgpt`, `subtitleedit_mt`, SubtitleCat `-ur.srt`), and season/episode or dual-year mismatches (`seriesPremiereYear` and `seasonAirYear`), while boosting release-cut lineage (`NF.WEB-DL`, `BluRay`, `HDTV`), runtime match (`expectedDurationMs`), and declared FPS (`23.976` / `24` / `25` / `29.97`). `SuperStreamExtractor` feeds raw `SuperStream` and `FebBox` filenames into `MediaLookupSpec.releaseHints`.
     - `SubtitleArchiveDecoder`: inflates GZIP and episode-scoped ZIP archives (`8 MiB` ceiling) and decodes `UTF-8`, `UTF-16LE/BE`, `Windows-1256` (`CP1256`) Urdu, and `Windows-1252` English without misclassifying Latin smart quotes.
     - `UrduOrthographyGate`: runs on raw pre-normalization cues before `normalizeUrduTypography` (`NFKC` presentation forms, `\u06CC`, `\u06A9`, `\u06C1`, `\u06D2`), using dual independent counting across `URDU_DISTINCTIVE_CHARS_REGEX`, `URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX` (52 homograph-free raw tokens), `RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX` (146 collision-audited `fa`/`ar` tokens), Persian ZWNJ verb prefixes (`می\u200C`/`نمی\u200C`), Persian `یه`, Arabic hamza (`أ إ ة`, exempting `ؤ/ئ/ء` for formal Urdu loanwords such as `مؤثر`, `جائزہ`, `انشاء`), and broken phrase-SMT sandwiches.
     - `UrduMtDumpDetector`: scores 5 signal families (zero-context polysemy/idiom calques such as `"شہد، آپ کا استقبال ہے"` and `"دائیں؟"`, newsroom passive `"کی طرف سے ... گیا"` and robotic copula `"یہ ایک ... ہے"`, adjacent-cue `آپ`/`تم`/`تو` register whiplash, continuation shattering across unpunctuated English cues, and conversational particle deficit) and rejects `mtDumpScore >= 60` (`VerifiedSubtitleCandidate.Rejected.MtDump`).
     - `AlassAligner`: aligns admitted human catalogue Urdu cues against the stream provider's native English cue anchor (`DONOR_TIER_STREAM_PROVIDER`, `AlignmentAnchorKind.PROVIDER_ENGLISH_CUE`) via dynamic-programming split penalties and rational-FPS drift search (`<= 50 ms` median, `<= 90 ms` P95 onset residual). When no provider English anchor exists (`AlignmentAnchorKind.UNANCHORED`), the host's `AudioSpeechSyncEngine` locks cue onsets directly to the stream's PCM speech-activity envelope on-device.
   - **Priority Tier 3: Scene-Aware Spoken *Bol-Chal* Localization (`SceneBolChalLocalizer`)**: Runs when verified native + human Urdu diversity is `< 2`, selecting the highest-ranked English donor (`StreamDonor` such as `VidLink`/`FebBox` > `CatalogueDonor` > `SubtitleCat`).
     - Pre-normalizes cues into Nastaliq-free `LatinEnglishText` (stripping SDH brackets, speaker tags, and any spliced Arabic/Nastaliq characters).
     - Groups cues into scene windows with `SpeakerContextCard` register tracking (`AddressRegister.TUM` peer default, `AAP` formal, `TU` intimate/anger).
     - Enforces strict 1-to-1 `CueId` survival (`>= 98%`) via JSON `[{"id","ur"}]` on optional BYOK OpenAI-compatible LLMs (`LocalizationTierKey.LLM_SCENE`) or `⟦id⟧` markers with recursive bisection down to single cues in `DefaultBolChalTranslationTransport` (`LocalizationTierKey.KEYLESS_BOLCHAL_NMT`), preserving dramatic `...` pauses without character-ratio twist leaks.
     - `BolChalPurifier` rewrites Hindiisms and literal calques into natural Pakistani/Hindustani spoken *bol-chal* Urdu while retaining natural Urdu-script English loanwords (`فون`, `کال`, `میسج`, `اوکے`, `سوری`, `پلیز`, `ڈاکٹر`, `پولیس`, `آفس`).
     - `UrduCpsRefiner` caps reading speed at `<= 17.0` visible CPS via bounded silence extension, filler condensation, and clause-aware syntactic splitting (`84 ms` blink gap, never splitting noun|postposition or compound verbs).
   - **Content-Addressed Storage (`UrduCasStore`) & Emergency Floor**: Verified tracks are written atomically to `cs_urdu_cas_v2/cs_urdu_v2_<sha256_24>.vtt` (or `.srt`) with SHA-256 readback checks (`superstream_urdu_` filenames and `upload_subtitles.php` uploads do not exist). If all remote Urdu catalogues and translation transports fail offline, up to 2 buffered English donor tracks emit as an emergency floor.
3. **`SessionSubtitleMode.BILINGUAL_DOWNLOAD` (`BilingualDownloadSubtitleCallback` / `cloudstream.subtitle.vault_v2=true`)**:
   - Triggered when the host downloader passes a `BilingualDownloadSubtitleCallback` (`cloudstream.bilingual_download_subtitles=true`) or sets `cloudstream.subtitle.vault_v2=true`. Emits both verified Urdu sidecars and English sidecars (tagging the stream-provider English reference with `X-StreamPlay-Sync-Role: provider-anchor`) for `SubtitleVaultStore` offline persistence.

### Cross-Module Synchronization (0 ms, no app restart)
`UrduSubtitleEngine.setSubtitleMode`, `SuperStreamUtils.setSubtitleMode`, and `SubtitleModeBridge` synchronize across:
1. `System.getProperty("cloudstream.subtitle.mode")` (`"default"` or `"urdu"`)
2. Shared preferences `"cloudstream_urdu_bolchal"` and `"cloudstream_subtitle_mode_prefs"` (`"subtitle_mode"`, plus optional BYOK LLM keys `bolchal_llm_endpoint`, `bolchal_llm_model`, `bolchal_llm_api_key`)
3. Plugin preferences `"StreamPlay"` and `"SuperStream"` (`"subtitle_mode"`, `"wyzie_key"`, `"wyzie_api_key"`, `"subdl_api_key"`, `"subsource_api_key"`)
4. Host `PreferenceManager.getDefaultSharedPreferences(context)` and `<packageName>_preferences` (`"subtitles_auto_select"` = `"en"` or `"ur"`)

## 2. Catalogue Sources Scheduled via `SubtitleCatalogSources`

> **Keyless vs. Optional API Keys:** `subdl` (`api3.subdl.com/auto` + `subdl.com`, with optional `api.subdl.com/api/v1/subtitles`), `subsense`, `subf2m`, `yify`, `opensubtitles`, `subtitles` (`opensubtitles-v3.strem.io`), `addic7ed`, and `subtitlecat` work **keylessly** out of the box. Optional user keys (`wyzie_key`, `subdl_api_key`, `subsource_api_key`) unlock direct API tiers across both `StreamPlay` and `SuperStream`.

| Source Key | Catalogue / Transport | Keying | Coverage | Host Priority |
|---|---|---|---|---|
| `wyziesub` | `SubtitleCatalogSources` (`sub.wyzie.io` + `.ru` fallback) | IMDb/TMDB id + S/E (`wyzie_key`) | Movies, TV, anime (English + Urdu) | 110 |
| `subtitles` | `SubtitleCatalogSources` (`opensubtitles-v3.strem.io`) | IMDb id (keyless) | Movies, TV, anime (English + Urdu) | 105 |
| `opensubtitles` | `SubtitleCatalogSources` (`rest.opensubtitles.org`) | IMDb id + title variants (`eng` + `urd`, keyless GZIP) | Movies, TV, anime (English + Urdu) | 104 |
| `addic7ed` | `SubtitleCatalogSources` / `invokeAddic7ed` | Title + verified TMDB S/E (keyless) | TV, anime (English donor / Default mode) | 103 |
| `yify` | `SubtitleCatalogSources` (`yts-subs.com` + `yifysubtitles.ch`) | IMDb id (keyless ZIP) | Movies, anime movies (English + Urdu) | 103 |
| `subtitlecat` | `SubtitleCatalogSources` (`www.subtitlecat.com`) | Title + `SXXEXX` / `NxNN` / `- XX` (keyless; `-orig.srt` / `-en.srt` donor only; `-ur.srt` MT dumps rejected) | Movies, TV, id-less anime (English donor / Default mode) | 102 |
| `subdl` | `SubtitleCatalogSources` (`api.subdl.com/api/v1` -> `api3.subdl.com/auto` -> `subdl.com`) | IMDb/TMDB id + title + year (keyless + optional key) | Movies, TV, anime (Human Urdu + English donor) | 101 |
| `subsense` | `SubtitleCatalogSources` (`subsense.nepiraw.com`) | IMDb id + S/E (keyless) | Movies, TV, anime (Human Urdu) | 101 |
| `subsource` | `SubtitleCatalogSources` (`api.subsource.net/api/v1`) | IMDb id + title + S/E (`subsource_api_key`) | Movies, TV, anime (Human Urdu + English donor) | 100 |
| `subf2m` | `SubtitleCatalogSources` (`subf2m.co`) | Slugged title + year/episode (keyless ZIP -> `UrduCasStore`) | Movies, TV, anime (Human Urdu) | 100 |
| `urdu-neural-fallback` | `SceneBolChalLocalizer` (`Urdu [UrduAI <Donor>]`) | Highest-ranked aligned English donor -> scene-windowed *bol-chal* localization -> `UrduCasStore` | Movies, TV, anime (when verified Urdu diversity `< 2`) | 99 |

### Anime Title & Coordinate Handling (`StreamPlayAnime.kt` & `SuperStream.kt`)
Anime lookups carry all known title variants (English, Romaji, native, AniList synonyms) plus both `seriesPremiereYear` and `seasonAirYear`. Verified TMDB/Cinemeta mappings use `EpisodeCoordinate.VerifiedSeasonEpisode(season, episode)`; unmapped multi-cour anime uses `EpisodeCoordinate.UnverifiedAnimeCour(courTitles, episodeInCour)` so `S01E{N}` packs from wrong seasons are never mis-selected.

### Stream-Provider Native Sidecars (v27 + `SuperStream`/`FebBox`)

| Provider | Rank | Sidecars | Subtitle Behaviour |
|---|---|---|---|
| `vidlink` | 100 | Yes | Direct MP4 + HLS (`vidlink.pro/api/b/{movie\|tv}/{token}`) with multi-language `.vtt`/`.srt` tracks; native Urdu preserved first (`Urdu [VidLink]`) and native English used as `AlassAligner` timing anchor + top *bol-chal* donor. |
| `meowtv` | 98 | Yes | `api.meowtv.ru` (`/subs/{movie\|tv}`, `rezesubs.com` `.vtt`) ingested as `StreamProviderKey.MEOWTV`. |
| `febbox` | 96 | Yes | `SuperStreamExtractor.invokeSuperstream` (`video_quality_list` `<track>` + JSON sidecars) and `invokeSuperstreamFeb` (`versions[].subtitles` + raw release-cut `versions[].name` hints) ingested as `StreamProviderKey.FEBBOX_PACK`. |
| `cineby` | 95 | Yes | `api.wecollege.net/seed` discrete `720p`/`1080p` HLS + multi-language `.vtt` tracks (`StreamProviderKey.CINEBY`). |
| `cinesrc` | 94 | Yes | `api.wing.st` HLS + `subs.bright67.online` `.srt`/`.vtt` multi-language tracks (`StreamProviderKey.CINESRC`). |
| `megaplay` | 92 | Yes | `megaplay.buzz` `getSources` `.vtt` tracks on `j1i9s.embermeadow.world` / `broforgotsave.online` (`StreamProviderKey.MEGAPLAY`). |
| `vidrift` | 91 | Yes | `embed.vidrift.net` SSR `bigbangSubtitles` + `/api/orion/.../subtitles.json` `.vtt` tracks alongside 720p-only `<= 650 MB` backup streams (`StreamProviderKey.VIDRIFT`). |
| `anizone` | 89 | Yes | `anizone.to` `vidstackPlayer` non-forced, non-SDH `.srt`/`.ass` episode pack sidecars (`StreamProviderKey.ANIZONE`). |
| `animepahe` | 88 | No | Subtitles burned into encode; covered by `SubtitleCatalogSources` + `AudioSpeechSyncEngine`. |
| `kickassanime` | 88 | Yes | Soft-sub `.vtt` tracks harvested in `StreamPlayAnime` (`StreamProviderKey.KICKASSANIME`). |
| `animex` | 87 | Yes | `pp.animex.one/rest/api/sources` + `kaa.lt` `.vtt`/`.srt` sidecars (`StreamProviderKey.ANIMEX`). |

## 3. SubtitleCat Contract (Read-Only English Donor)

* Search: `https://www.subtitlecat.com/index.php?search=<title + episode>`.
* Only detail pages (`subs/{id}/{slug}.html`) matching both title and episode (and free of SDH/CC markers) are inspected, and only the original English file (`-en.srt` / `-eng.srt` / `-orig.srt`) is admitted as a last-resort `DONOR_TIER_SUBTITLECAT_LAST_RESORT` donor.
* SubtitleCat's pre-generated `-ur.srt` files are uncontextualized machine-translation dumps and are rejected by `CatalogueCandidateGate`.
* SubtitleCat is strictly read-only: uploading user or synthesized subtitles (`upload_subtitles.php`, `publishToSubtitleCatCdn`) is permanently forbidden.

## 4. Rejected Catalogues & Forbidden Patterns

| Item | Status | Reason |
|---|---|---|
| Podnapisi | dead | Domain no longer resolves. |
| TVsubtitles | broken | Homepage loads; `search1.php` returns 404. |
| Kitsunekko | rejected | Japanese-language files only. |
| Subf2m (raw remote URLs in Default mode) | rejected for raw URL emission | Signed CDN URLs expire before queued downloads; in Urdu mode `SubtitleCatalogSources` immediately downloads, verifies, aligns, and stores local `UrduCasStore` files. |
| Jimaku | key-gated | Requires an external account/API key. |
| Subscene / Anime Tosho old feed | dead | Shut down / no longer resolves. |
| SubtitleCat `-ur.srt` & `upload_subtitles.php` | banned | Uncontextualized MT dumps (`-ur.srt`) and unauthorized third-party uploads (`upload_subtitles.php`) are permanently banned. |
| VidRock / VidSrc / VidSrcSU / Hexa / EmbedSU / Vaplayer / Riverstream / Videasy | banned | Stripped by `StreamPlayShield` and the host's `isBannedOrDeadSubtitle`. |

## 5. Invariants for New Subtitle Sources

1. Implement inside `shared/subtitles` (`SubtitleCatalogSources` + `CatalogueSourceKey` or `StreamProviderKey`) so `:StreamPlay` and `:SuperStream` share identical behaviour.
2. Keyless plain HTTP GET by default (optional user keys must degrade cleanly when unset).
3. Pass `CatalogueCandidateGate`, `SubtitleArchiveDecoder`, `UrduOrthographyGate`, and `UrduMtDumpDetector` (`mtDumpScore < 60`) before alignment or emission.
4. Bound network timeouts and candidate counts; never block video stream discovery.
5. Stamp provenance via `ProvenanceCodec` and register matching markers in `cloudstream`'s `DownloadSubtitlePolicy`.
6. Cover parser, orthography/MT gating, and `AlassAligner` / `EpisodeSubtitleSession` behaviour with deterministic unit tests in both `:StreamPlay` and `:SuperStream`.
