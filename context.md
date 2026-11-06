# Subtitle Source Matrix (extension repo)

This is the extension-side companion to `cloudstream/context.md`. The binding rules live in
`AGENTS.md`; this file records **which catalogues the extension queries**, with what key, and which
catalogues were evaluated and rejected. Update both files together whenever a source changes.

## 1. Why the extension harvests subtitles itself

A top-tier provider can return a perfect 720p stream and no English sidecar. Waiting for the host's
download-healing pass is too late for direct playback, so `StreamPlay` and `StreamPlay-Anime` run a
subtitle-first phase: the provider sweep and the dedicated catalogues run in parallel, and the
completion phase re-runs whatever did not answer so the app can keep one English subtitle per source
(target: five distinct sources, `TARGET_ENGLISH_SUBTITLE_SOURCES`).

Every emitted `SubtitleFile` carries `X-StreamPlay-Source: <sourceKey>`, which the host uses for
download diversity and for the sidecar file name. The URL alone cannot identify the source because
several providers share CDN hosts.

## 1b. Instant Subtitle Mode Switch: Default (English) <-> Urdu (اردو) across StreamPlay & SuperStream

`UrduSubtitleEngine` (in `StreamPlay`) and `SuperStreamUtils` (in `SuperStream`) provide a zero-latency, cross-classloader subtitle mode switch between:
- **`SubtitleMode.DEFAULT` (`"default"`, `"Default (English)"`)** — the out-of-the-box default:
  - `StreamPlay`, `StreamPlayAnime`, `StreamPlayExtractor`, `StreamPlayStremioCatelog`, `SuperStream`, and `SuperStreamExtractor` strictly filter and emit **English-only** subtitles (`TARGET_ENGLISH_SUBTITLE_SOURCES = 5`).
  - All non-English subtitles (including Urdu) are rejected in `emitSubtitle` / `trackedSubCallback`, and Urdu-only scrapers (`subdl`, `subsense`, `subf2m`) plus `synthesizeUrduFallbackFromEnglish` are never scheduled.
- **`SubtitleMode.URDU` (`"urdu"`, `"Urdu (اردو)"`)** across `StreamPlay`, `StreamPlay-Anime` (`StreamPlayAnime.kt`), `StreamPlayStremioCatelog`, and `SuperStream`:
  - **Speculative Detail-Page Pre-Warming (`load()`)**: While the user views a Movie, TV Series, Anime, or Stremio Catalog detail page in Urdu mode, `UrduSubtitleEngine.prewarmUrduSubtitlesAsync` (in `StreamPlay`, `StreamPlayAnime`, and `StreamPlayStremioCatelog`) and `SuperStreamExtractor.prewarmSuperStreamUrduSubtitlesAsync` (in `SuperStream`) warm the `SubDL`, `SubSense`, `OpenSubtitles`, and `YTS-Subs` (movies) Urdu subtitle caches in the background (with a 10-minute per-title cooldown), and all four surfaces (`StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, `SuperStream`) prepend an active mode badge (`"🇵🇰 Subtitles: Urdu (اردو • SOTA)"` vs `"🇬🇧 Subtitles: English (Default)"`) to detail-page tags.
  - **Parallel 8-Source Human Urdu Harvesting Across All Surfaces**: Harvests all 8 verified Urdu subtitle sources in parallel across `StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, and `SuperStream` (`subdl` Urdu via Official API v1 + `api3.subdl.com/auto` + `subdl.com`, `subsense` Urdu, `subf2m` Urdu, `yts-subs` Urdu, `opensubtitles` `urd`, `subsource` Urdu, `wyzie` `ur`, and `subtitlecat` `-orig.srt` donor scoped by `(imdbId, season, episode)` with its pre-cached `-ur.srt` recorded strictly as a verified last-resort Phase 4 candidate — never a Phase 1 human Urdu track) while recording English donor URLs for fallback synthesis.
  - **In-Flight Cue Normalization, Content-Addressed L1/L2 Cache & Bounded LRU/TTL Eviction**: Both `UrduSubtitleEngine.materializeNormalizedUrduSubtitle` (in `StreamPlay`) and `SuperStreamExtractor.materializeNormalizedUrduSubtitle` (in `SuperStream`) unpack GZIP and season/episode-aware ZIP archives, detect `Windows-1256` (`CP1256`) vs `Windows-1252` and `UTF-16LE/BE`, normalize Arabic/Urdu glyphs (`NFKC` presentation forms, `\u06CC`, `\u06A9`, `\u06C1`, `\u06D2`), deterministically reject Persian (`fa`), Arabic (`ar`), and 2012-era broken SMT dumps across all cue counts — via one shared fa/ar exclusive token list (172 fa/ar-distinctive tokens only: function words, verb morphology, and colloquialisms; ALL Urdu-shared vocabulary — nouns, greetings, scenery, loanword function words (`بعد`، `قبل`، `فقط`، `جدا`، `کل`/`كل`، `نہ`/`نه`، `ہفتہ`/`هفته`، `ماہ`/`ماه`، `البته`، `علی`/`على`، `فی`/`في`، `من`، `لا`، `قد`، `رب`، `عین`، `ضد`، `مثل`، `در`، `حب`، `حول`، `تحت`، `بلد`، `بنت`، `روی`، `عن`، `خانہ`، `زن`، `برادر`، `مادر`، `حرب`، `طعام`، `طفل`، `طریق`، `غیر`، `خانه`، `بین`، `بیت`، `یوم`، `فوق`، `مع`، `ما`، `چون` — enforced by a CI collision-sweep self-test whose vocabulary covers Arabic-yeh (U+064A) spellings too) is excluded so bare-noun cues and genuine Urdu never lean fa/ar on either surface, structural Persian/Arabic orthography signals (`می\u200C`/`نمی\u200C` ZWNJ verb prefixes — Urdu never uses ZWNJ — Arabic-specific hamza letters `أ إ ة` (`ؤ/ئ` exempted for سؤال/مؤثر/جائزہ loanwords, `ء` for شیء/جزء/انشاء), and Persian colloquial `یه` word-initial or cue-final raw-heh `ی` + U+0647) scanned on the RAW pre-normalization text, one shared 52-token homograph-free Urdu function-word evidence list (`URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX` — mechanically audited under the normalization map and matched on RAW cue text ONLY, so normalization twins are structurally impossible (Persian `رها`/`یه` can never match raw-scan tokens `رھا`/`یہ`); tokens with a real fa/ar orthographic twin are stored with do-chashmi heh (`رھا`/`رھی`/`رھے`), twin-free tokens keep BOTH standard and do-chashmi spellings (`نہیں`/`نھیں`، `پہلے`/`پھلے`) so professional gol-heh orthography retains full evidence recall, hamza-less Arabic spellings (`اب`←أب، `سب`←سَبّ، `جب`←جَبّ) and shared homographs (`تو`/`اگر`/`پر`/`ہو`/`ہم`/`ہوا`) are excluded, and per-cue language verification uses DUAL INDEPENDENT counting — a cue can be evidence-bearing AND fa/ar-leaning simultaneously (no precedence bucket can inoculate a fa/ar marker) — applied to every multi-cue file. STAGE SEPARATION: the language gate runs on RAW decoded cues BEFORE `normalizeUrduTypography` in both materialization pipelines and the Phase 4 SubtitleCat fallback (decode with `normalizeTypography = false` → raw-cue gate → normalize → normalized re-verify), so orthography signals are intact at verification time. Derived-data self-tests in CI: every curated token must match its own regex (catches construction typos) and no token may collide with the embedded common-Urdu vocabulary in raw or normalized form (≥ 2 cues): (a) dominance — evidence-bearing cues must outnumber fa/ar-leaning cues, and (b) share — at least 40% of the file's Arabic-script cues must carry Urdu evidence when any cue leans fa/ar, and 30% when the file carries no fa/ar signal at all (defeats one-padded-Urdu-line padding over token-free Persian bodies while accepting genuine vocative/interjection-dense Urdu), strip promotional watermarks while preserving inline tags (`<i>`, `<b>`, `<u>`, `<s>`, `{\an1}`..`{\an9}`), wrap RTL lines with `\u200F` (`RLM`), and materialize verified cues into True Content-Addressed Storage (`cs_urdu_cas_<sha256_24>.srt`) paired with a URL-addressed L2 index (`cs_urdu_url_<sha256_24>.srt`) and bounded LRU/TTL disk eviction (`<= 64` files / `<= 16 MiB` / `7-day` TTL). All writes are atomic (`.tmp` sibling + `ATOMIC_MOVE` rename), and every cache hit is integrity re-verified before serving: CAS entries are re-hashed against their content-hashed name, and URL-index payloads must still parse as cues passing `containsVerifiedUrduScript` + `!isBrokenSmtUrdu`; corrupted entries are deleted and re-fetched instead of served.
  - **Phase 3 Urdu Subtitle Completion (`StreamPlay.kt`, `StreamPlayAnime.kt`, `StreamPlayStremioCatelog.kt`, `SuperStream.kt`)**: Checks `humanUrduCount < 2` (fewer than 2 verified human Urdu sources arrived in Phase 1) and retries unfinished human Urdu subtitle harvesters (`!urduSubtitleSources.contains(task.providerId)`) with a fast, bounded timeout of `4_500L` (4.5s), breaking out immediately as soon as `humanUrduCount >= 2` — event-driven in all four surfaces (StreamPlay via `EarlySatisfactionController.extraCondition`, SuperStream via a `select` race between harvester completion and the `urduFloorSignal` `CompletableDeferred` completed by the tracked subtitle callback).
  - **Phase 4 Spoken-Urdu Contextual Neural Fallback (`synthesizeUrduFallbackFromEnglish` / `synthesizeSuperStreamUrduFallback`)**: Runs **only when `humanUrduCount == 0`** (`urduSubtitleSources.isEmpty()`, bounded by `withTimeoutOrNull(8_000L)`), translating the top English donor `.srt`/`.vtt` across `StreamPlay`, `StreamPlayAnime`, `StreamPlayStremioCatelog`, and `SuperStream`. Uses 2-cue and 3-cue cross-cue sentence-continuation merging (preserving complete English SVO sentences before translation so Urdu SOV word order is grammatically intact) and spoken Pakistani/Hindustani Urdu idiom adaptation (`adaptEnglishForSpokenUrduTranslation` + `refineUrduToSpokenIdiom`) via batched `clients5.google.com/translate_a/t` (`dict-chrome-ex`, 250-cue batches with 50-cue bisection retry and `Semaphore(4)`) into a UTF-8 BOM + `\u200F` RLM `.srt` written to canonical `file:///...` content-addressed local cache. After the neural pass, both engines consume the recorded SubtitleCat pre-cached `-ur.srt` candidates as a VERIFIED LAST-RESORT fallback (fetched, normalized, and admitted only when `containsVerifiedUrduScript` passes and `isBrokenSmtUrdu` rejects it) so the Urdu floor holds even when no English donor is available.
  - **Phase 5 Emergency English Donor Floor & Strict Source Attribution**: Emits **pure Urdu subtitles** to `subtitleCallback` whenever at least one Urdu source (human or synthesized) is available; if both human scrapers and neural translation yield 0 Urdu tracks (e.g. offline/rate-limited), `flushDeferredSubtitles()` / Phase 5 emits up to 2 buffered English donor subtitles (`englishDonorSubs.take(2)`) so the viewer is never left with zero subtitles. `emitSubtitle` normalizes any `file:/...` URI to canonical triple-slash `file:///...` and `cleanSubtitleLabel` preserves verified source badges (`Urdu [SubDL]`, `Urdu [YTS-Subs]`, `Urdu [Subf2m]`, `Urdu [OpenSubtitles]`, `Urdu [SubSense]`, `Urdu [SubSource]`, `Urdu [Wyzie]`, `Urdu [SubtitleCat]`, `Urdu [Neural AI]`) without falsely attributing unknown labels.

### Zero-Latency Synchronization (0 ms, no app restart)
Because the host app (`com.lagradost.cloudstream3`), `StreamPlay`, and `SuperStream` run in the same JVM process, both `UrduSubtitleEngine.setSubtitleMode` and `SuperStreamUtils.setSubtitleMode` read and write:
1. `System.getProperty("cloudstream.subtitle.mode")` (`"default"` or `"urdu"`)
2. Shared preferences `"cloudstream_subtitle_mode_prefs"` (`"subtitle_mode"`)
3. Both plugin preferences (`"StreamPlay"` and `"SuperStream"` `"subtitle_mode"`, plus cross-module API keys `"wyzie_key"`, `"wyzie_api_key"`, `"subdl_api_key"`, and `"subsource_api_key"`)
4. Host `PreferenceManager.getDefaultSharedPreferences(context)` and `<packageName>_preferences` (`"subtitles_auto_select"` = `"en"` or `"ur"`)

Users can toggle the mode at any time from either **StreamPlay Settings** or **SuperStream Settings -> Subtitle Mode (`Default (English)` / `Urdu (اردو)`)** and the very next stream or download in either plugin immediately uses the selected subtitle language.

## 2. Sources the extension schedules

> **Note on Keyless vs. Key-Gated Sources:** `subdl` (`api.subdl.com/api/v1/subtitles` + `api3.subdl.com/auto` + `subdl.com`), `subsense`, `subf2m`, `yifysub`, `opensubtitles`, `subtitles` (`opensubtitles-v3.strem.io`), and `subtitlecat` operate **keylessly** out of the box. Direct API queries to `wyziesub` and `subsource` read user-configured API keys across `"StreamPlay"`, `"SuperStream"`, and `"cloudstream_subtitle_mode_prefs"` (`wyzie_key` / `wyzie_api_key` / `subdl_api_key` / `subsource_api_key`) and gracefully no-op when unconfigured.

### `StreamPlay.kt`, `StreamPlayAnime.kt`, `StreamPlayStremioCatelog.kt` & `SuperStream.kt` (movies, TV, anime & catalog)

| Task id | Method | Key | Covers | Priority |
|---|---|---|---|---|
| `wyziesub` | `invokeWYZIESubs` / `invokeWyZIESUBAPI` | IMDb/TMDB id + season/episode (`sub.wyzie.io` + `.ru` fallback; cross-module `wyzie_key` / `wyzie_api_key`) | Movies, TV, anime (English in Default; Urdu + English donor in Urdu mode) | 110 (host marker) |
| `subtitles` | `invokeSubtitleAPI` | IMDb id (`opensubtitles-v3.strem.io`, keyless) | Movies, TV, anime (English in Default; Urdu + English donor in Urdu mode) | 105 |
| `opensubtitles` | `invokeOpenSubtitlesLegacy` / `invokeSuperStreamOpenSubtitlesUrdu` | IMDb id, then every known title (`eng` + `urd`, keyless) | Movies, TV, anime (English + Urdu) | 104 |
| `addic7ed` | `invokeAddic7ed` | title + season + episode (keyless) | TV, anime (Default English mode) | 103 |
| `subtitlecat` | `invokeSubtitleCat` / `invokeSuperStreamSubtitleCatDonor` | release text: title + `SXXEXX` / `NxNN` / `- XX` scoped by `(imdbId, season, episode)` (keyless) | Movies, TV, anime (English in Default; `-orig.srt` English donor + `-ur.srt` recorded as verified last-resort Phase 4 fallback in Urdu mode) | 102 |
| `yifysub` | `invokeYifySubs` / `invokeSuperStreamYifyUrdu` | IMDb id (`yts-subs.com` + `yifysubtitles.ch` fallback, keyless) | Movies, anime movies (English + Urdu) | 103 |
| `subdl` | `UrduSubtitleEngine.invokeSubDlUrduKeyless` / `SuperStreamExtractor.invokeSubDlUrduKeyless` | IMDb id / title + year (`api.subdl.com/api/v1/subtitles` -> `api3.subdl.com/auto` -> `subdl.com/.../urdu`, Tier-1A human author ranking + AI filtering) | Movies, TV, anime (Urdu `.zip`, Urdu mode only) | 101 |
| `subsense` | `UrduSubtitleEngine.invokeSubSenseUrdu` / `SuperStreamExtractor.invokeSubSenseUrdu` | IMDb id (`subsense.nepiraw.com` Stremio Urdu endpoints, keyless) | Movies, TV, anime (Urdu, Urdu mode only) | 101 |
| `subf2m` | `UrduSubtitleEngine.invokeSubf2mUrdu` / `SuperStreamExtractor.invokeSuperStreamSubf2mUrdu` | Normalized title + year/episode + `isMovie` (`subf2m.co/subtitles/.../urdu`, keyless) | Movies, TV, anime (Urdu, Urdu mode only) | 100 |
| `subsource` | `UrduSubtitleEngine.invokeSubSourceUrdu` / `SuperStreamExtractor.invokeSuperStreamSubSourceUrdu` | IMDb id + title + season/episode (cross-module `subsource_api_key`) | Movies, TV, anime (Urdu, Urdu mode only) | 100 |
| `urdu-neural-fallback` | `synthesizeUrduFallbackFromEnglish` / `synthesizeSuperStreamUrduFallback` | Spoken-Urdu contextual neural fallback (`clients5.google.com` 2- and 3-cue sentence merging + SOV/idiom refinement + `cs_urdu_cas_` local cache; runs only when `humanUrduCount == 0`) | Movies, TV, anime (Zero-gap RTL Urdu fallback in StreamPlay, StreamPlayAnime, StremioCatalog, and SuperStream) | 99 |

### `StreamPlayAnime.kt` (anime)

| Task id | Method | Gate |
|---|---|---|
| `kickassanime` | `invokeKickAssAnime` | any known anime title (English/multi-lang soft-sub VTTs; English donor in Urdu mode) |
| `subdl`, `subsense`, `subf2m`, `subsource`, `yifysub`, `opensubtitles`, `wyziesub`, `subtitlecat` | Full 8-source Urdu harvesting + Phase 3 human Urdu completion (`< 2`) | Urdu mode |
| `urdu-neural-fallback` | `UrduSubtitleEngine.synthesizeUrduFallbackFromEnglish` + emergency English donor floor | Urdu mode + `urduSubtitleSources.isEmpty()` |

Anime is searched by every known name (English, Romaji, native, AniList synonyms) because a
Japanese-only query frequently returns nothing even when an English sidecar exists. The names travel
through the episode `LinkData` as `alternativeTitles`, so the host's fallback pass sees them too.
`subtitlecat` receives the verified TMDB season/episode when available and the cour-relative episode
otherwise, and queries both `SXXEXX` and the bare `- XX` form.

`subtitlecat` is deliberately **not** gated on `canUseTmdbProviders`: it needs only a title and an
episode number, so anime that could not be mapped to TMDB no longer ends up with zero subtitle
sources (the previous anime fallbacks were all TMDB-gated).

### Provider-sidecar Movie/TV & Anime sources (v27)

| Provider | Rank | Sidecars | Behaviour |
|---|---|---|---|
| `vidlink` | 100 | Yes | Offline `XSalsa20-Poly1305` (`VidLinkCrypto.encryptToken`) -> `vidlink.pro/api/b/{movie\|tv}/{token}` -> emits direct MP4 (`stream.qualities` sorted `720p` -> `1080p`) immediately before secondary HLS manifest fetch, plus multi-language `.vtt`/`.srt` subtitles. |
| `meowtv` | 98 | Yes | `api.meowtv.ru` single-use ticket (`english` -> `dcloud` -> `acme?list=1`) -> offline `meowTvDecryptStreamsOffline` / `meowTvDecryptOffline` (`SHA-256` + XOR) + `/subs/{movie\|tv}` (`rezesubs.com` `.vtt`) -> `cdn2.fastcloud.live` / `hscow.com` / `vidjoy.org` / `fcxmb.com` / `krico443jez.com` HLS (`720p` -> `1080p`). |
| `cineby` | 95 | Yes | `api.wecollege.net/seed` (`Origin: https://vidy.st`) + multi-server (`oakland`, `reno`, `dallas`, `boise`, `miami`, `vegas`, `atlanta`, `phoenix`) -> offline `cinebyDecryptMvm1` (`dec-videasy` fallback) -> `moon.goldenkite.top` / `sun.paleoak.top` / `e2e.majorplay.net` / `finalsignal.top` / `wellsrobertsonkmlt.workers.dev` discrete `720p`/`1080p` HLS + `.vtt` subtitles. |
| `cinesrc` | 94 | Yes | `cinesrc.st` CSP4 + SHA-256 PoW (`wave`, `nebula`, `surge`, staggered 350ms) + `api.wing.st` (`secret` + `Nebula` via `enc-cinejoy`/`dec-cinejoy` `cinesrcParseWingDecryptedResult` handling both `JSONObject` and `JSONArray` `result.data.stream`) -> `ice.bright67.online` / `nebula.bright67.online` / `lightspeed.st` HLS + `subs.bright67.online` `.srt`/`.vtt` subtitles. |
| `vidrift` | 91 | Yes | `embed.vidrift.net/embed/{movie\|tv}/{tmdbId}` SSR `embed-meta` (`bigbangSubtitles` + `/api/orion/.../subtitles.json` `.vtt` tracks) alongside strictly 720p-only backup emission (`isEligibleVidRift720BackupLink`, `<= 650 MB`, measuring single-quality leaf playlists via `measureHlsMediaPlaylistSizeMb` and suppressing all non-720p, unsized, and oversized 720p variants) across Wave 1A (`Direct`, `Vexon`, `Evion`), Wave 1B 600ms co-staged (`Titan`, `Atlas`), Wave 2 (`Vela`, proxied `Lyra`), and Wave 3 zero-link fallback (`Orion`, `BigBang`). Tagged `X-StreamPlay-Source: vidrift`. |

### Provider-sidecar anime sources (v27)

| Provider | Rank | Sidecars | Behaviour |
|---|---|---|---|
| `megaplay` | 92 | Yes | Headless B2B anime embed API (`megaplay.buzz/stream/{mal\|ani}/{id}/{ep}/sub` -> `getSources?id={cid}&platform=OTHER`). Verified live 2026-10-24: `getSources` returns `{"enc":"<ciphertext>","tracks":[...]}` with `.vtt` sidecars on `j1i9s.embermeadow.world` / `broforgotsave.online`. Decrypted 100% offline in Kotlin (`megaplayDecryptEnc`, AES-256-CBC + `megaplaySignCdnUrl` 4-hour HMAC-SHA256 `MpCdnT0k3n` token) with `dec-megaplay` as fallback only. |
| `anizone` | 89 | Yes | `vidstackPlayer(JSON.parse('...'))` lists English `.srt`/`.ass` tracks beside the `*.xin-cdn.xyz` / `*.vid-cdn.xyz` HLS master. Only non-forced, non-SDH English tracks are emitted, with `cleanSubtitleLabel` names. Verified live 2026-10-23: `https://anizone.to/anime/eu8bdgty/1` returns `1_en.srt`, `0_en.ass` (forced) and `2_en.srt` (SDH), so the forced/SDH pair is filtered. |
| `animepahe` | 88 | No | The `#resolutionMenu` buttons expose `data-resolution` and `data-audio` (`jpn`/`eng`) alongside explicit `(XXX MB)` size tags in `#pickDownload`, and the Kwik packer exposes a single HLS manifest; subtitles are part of the encode. It is kept for airing/rare-series coverage, so the catalogue fallback above fills the English gap. Origin verified 2026-10-23: `animepahe.com` answers 301 -> `https://animepahe.pw/`; the `.pw` API is Cloudflare/DDoS-Guard-gated and is answered by `CloudflareKiller` + `__ddg2_` cookie. Current Kwik hosts are `kwik.si`/`kwik.cx` and the resolved CDN is `*.uwucdn.top` / `*.owocdn.top`. |
| `animex` | 87 | Yes | `graphql.animex.one` (with `plyr.animex.one/e/{aniId}/{ep}` SSR slug fallback) + plaintext `pp.animex.one/rest/api/sources` (`nero`, `yuki`, `zuna`, `loli`, `sora` — zero `enc-dec.app` dependency) plus direct `kaa.lt` (`krussdomi.com/cat-player/player` Astro island) fallback when `animex.one` is missing an episode. Serves multi-variant HLS (`s-91.megavid.se/.../deeznuts.txt`, `hls.dramahot.top`, `hls.krussdomi.com`, `bl.krussdomi.com`, `fetch.nexabloom.top`, `echovideo.to`/`.ru`) and `.vtt`/`.srt` sidecars (`subst.krussdomi.com`, `subbl.krussdomi.com`, `hls.dramahot.top`, `j1i9s.embermeadow.world`, `broforgotsave.online`). |

AniZone's marker is registered in the host `DownloadSubtitlePolicy` as `anizone` (priority 89,
hosts `anizone`, `xin-cdn`, `vid-cdn`), so its sidecars count as a distinct English source and are
named accordingly. AnimePahe emits no sidecar and therefore has no subtitle marker.

## 3. SubtitleCat contract

* Search: `https://www.subtitlecat.com/index.php?search=<title + episode>`.
* The search page links to **detail pages** (`subs/{id}/{slug}.html`), not to files, and each
  language has its own id, so the English link cannot be built from the search hit.
* Only the English file (`-en.srt` / `-eng.srt`) is emitted. A detail page without one is skipped.
* A candidate is accepted only when **both** the title and the episode match. Episode matching
  accepts `S02E05`, `2x05`, `Season 2 Episode 5` and a bare zero-padded `05` token; a bare number
  only matches a complete token, so `1080p`, `2005` and `S05` never satisfy it.
* SDH/CC releases are dropped. At most two title variants and two detail pages are fetched per
  lookup, so the fallback stays inside the pipeline budget.
* English `.srt` links are stable (no signed expiry), unlike some mirrors' download URLs.

## 4. Rejected catalogues (do not re-add without new evidence)

| Catalogue | Status | Reason |
|---|---|---|
| Podnapisi | dead | Domain no longer resolves. |
| TVsubtitles | broken | Homepage loads; `search1.php` returns 404. |
| Kitsunekko | rejected | Japanese-language files only; cannot satisfy an English-only contract. |
| Subf2m (raw remote URLs in Default mode) | rejected for raw URL emission | Remote download links redirect to short-lived signed CDN URLs that expire before a queued download (in Urdu mode, `invokeSubf2mUrdu` immediately downloads, unpacks, and materializes the `.zip` into a local `file:///...` `.srt` during harvesting). |
| Jimaku | key-gated | Requires an API key/account (note: `SubSource` and `Wyzie` are also key-gated via optional `subsource_api_key` / `wyziekey`, whereas `SubDL` Urdu operates keylessly via `api3.subdl.com/auto` + `subdl.com` HTML/ZIP materialization). |
| Subscene | dead | Shut down. |
| Anime Tosho feed | dead | Old feed endpoint no longer resolves. |
| VidRock / VidSrc / VidSrcSU / Hexa / EmbedSU / Superstream / Vaplayer / Riverstream / Videasy | banned | Stripped by `StreamPlayShield` and the host's `isBannedOrDeadSubtitle`. |

## 5. Invariants for new sources

1. Keyless plain HTTP GET from a device; no API key that the app cannot supply.
2. English-only, non-SDH, direct file URL (or a stable page that yields one).
3. Verifiable identity: a candidate must prove it matches the requested title and episode before it
   is emitted.
4. Bounded: a per-lookup timeout, a per-source result cap, and no retries that can stall the
   pipeline.
5. Tagged with `X-StreamPlay-Source` and registered in the host's `DownloadSubtitlePolicy`
   `SOURCE_MARKERS` so download diversity and sidecar naming work.
6. Tests for the parser, the matcher and the source marker.
