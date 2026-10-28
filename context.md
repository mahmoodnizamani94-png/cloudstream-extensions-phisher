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

## 2. Sources the extension schedules

### `StreamPlay.kt` (movies and TV)

| Task id | Method | Key | Covers | Priority |
|---|---|---|---|---|
| `wyziesub` | `invokeWYZIESubs` | IMDb/TMDB id + season/episode | Movies, TV, anime | 110 (host marker) |
| `subtitles` | `invokeSubtitleAPI` | IMDb id | Movies, TV | 105 |
| `opensubtitles` | `invokeOpenSubtitlesLegacy` | IMDb id, then every known title | Movies, TV, anime | 104 |
| `addic7ed` | `invokeAddic7ed` | title + season + episode | TV | 103 |
| `subtitlecat` | `invokeSubtitleCat` | release text: title + `SXXEXX` / `NxNN` / `- XX` | Movies, TV, anime | 102 |
| `yifysub` | `invokeYifySubs` | IMDb id | Movies | 103 |

### `StreamPlayAnime.kt` (anime)

| Task id | Method | Gate |
|---|---|---|
| `wyziesub` | `invokeWYZIESubs` | TMDB-linked entry |
| `subtitles` | `invokeSubtitleAPI` | IMDb id resolved |
| `opensubtitles` | `invokeOpenSubtitlesLegacy` | IMDb id or any known title |
| `addic7ed` | `invokeAddic7ed` | verified TMDB season/episode |
| `subtitlecat` | `invokeSubtitleCat` | always (title + episode; no id needed) |

Anime is searched by every known name (English, Romaji, native, AniList synonyms) because a
Japanese-only query frequently returns nothing even when an English sidecar exists. The names travel
through the episode `LinkData` as `alternativeTitles`, so the host's fallback pass sees them too.
`subtitlecat` receives the verified TMDB season/episode when available and the cour-relative episode
otherwise, and queries both `SXXEXX` and the bare `- XX` form.

`subtitlecat` is deliberately **not** gated on `canUseTmdbProviders`: it needs only a title and an
episode number, so anime that could not be mapped to TMDB no longer ends up with zero subtitle
sources (the previous anime fallbacks were all TMDB-gated).

### Provider-sidecar Movie/TV & Anime sources (v26)

| Provider | Rank | Sidecars | Behaviour |
|---|---|---|---|
| `vidrift` | 98 | Yes | `embed.vidrift.net/embed/{movie\|tv}/{tmdbId}` SSR `embed-meta` (`bigbangSubtitles` + `/api/orion/s0/subtitles.json` `.vtt` tracks) alongside `Orion`, `BigBang`, `Vexon`, `Evion`, `Titan`, `Atlas`, `Vela`, and `Lyra` HLS streams. Tagged `X-StreamPlay-Source: vidrift` (host marker priority 98). |

### Provider-sidecar anime sources (v26)

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
| Subf2m | rejected | Downloads redirect to short-lived signed CDN URLs that expire before a queued download. |
| SubSource / SubDL / Jimaku / Wyzie direct | key-gated | Need an API key or account. |
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
