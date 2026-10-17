package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invokeAnimex
import com.phisher98.StreamPlayExtractor.invokeMeowTV
import com.phisher98.StreamPlayExtractor.invokeVidFast
import com.phisher98.StreamPlayExtractor.invokeVidlink
import com.phisher98.StreamPlayExtractor.invokeVidrock
import com.phisher98.StreamPlayExtractor.invokeVidSrc
import com.phisher98.StreamPlayExtractor.invokeVidSrcSU
import com.phisher98.StreamPlayExtractor.resolveAnimeIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ProviderKind {
    VIDEO,
    SUBTITLE,
    MIXED
}

data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderKind = ProviderKind.VIDEO,
    val invoke: suspend (
        res: StreamPlay.LinkData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        token: String,
        dahmerMoviesAPI: String
    ) -> Unit
)

private fun getDubStatus(res: StreamPlay.LinkData): String {
    return when {
        res.isMovie == true -> "Movie"
        res.isDub -> "DUB"
        else -> "SUB"
    }
}

private val animeIdResolveMutex = Mutex()

private fun StreamPlayCache.AnimeIdMapping.toResolvedAnimeIds(): StreamPlayExtractor.AnimeResolvedIds {
    return StreamPlayExtractor.AnimeResolvedIds(
        malId = malId?.toIntOrNull(),
        anilistId = anilistId?.toIntOrNull(),
        anidbEid = anidbEid,
        zoroIds = zoroId?.split(",")?.filter { it.isNotBlank() },
        zoroTitle = zoroTitle,
        aniXL = aniXL,
        kaasSlug = kaasSlug,
        animepaheUrl = animepaheUrl,
        animekaiId = animekaiId,
        tmdbYear = tmdbYear
    )
}

private suspend fun getAnimeIds(res: StreamPlay.LinkData): StreamPlayExtractor.AnimeResolvedIds {
    val cacheKey = "${res.title}_${res.date ?: res.airedDate}_${res.season ?: 0}"

    val cached = StreamPlayCache.getCachedAnimeIds(cacheKey)
    if (cached != null) {
        return cached.toResolvedAnimeIds()
    }

    val ids = animeIdResolveMutex.withLock {
        StreamPlayCache.getCachedAnimeIds(cacheKey)?.let { cachedAfterWait ->
            return@withLock cachedAfterWait.toResolvedAnimeIds()
        }

        resolveAnimeIds(res.title, res.date, res.airedDate, res.season, res.episode)
    }

    StreamPlayCache.cacheAnimeIds(
        cacheKey,
        StreamPlayCache.AnimeIdMapping(
            anilistId = ids.anilistId?.toString(),
            malId = ids.malId?.toString(),
            kitsuId = null,
            zoroId = ids.zoroIds?.joinToString(","),
            anidbEid = ids.anidbEid,
            zoroTitle = ids.zoroTitle,
            aniXL = ids.aniXL,
            kaasSlug = ids.kaasSlug,
            animepaheUrl = ids.animepaheUrl,
            animekaiId = ids.animekaiId,
            tmdbYear = ids.tmdbYear
        )
    )

    return ids
}

/**
 * ===================== StreamPlay SOTA Provider Registry (v23) =====================
 *
 * Seven sources, every one of them verified end-to-end against live playback with plain
 * HTTP (no browser, no JS execution, no Cloudflare interstitial) during the v22/v23
 * research passes. VidLink is the retained universal primary; VidSrc and VidRock are the
 * v22 discoveries; VidFast, VidSrcSU and MeowTV were reverse-engineered and integrated
 * from scratch in the v23 pass (2026-10-16); Animex is the dedicated anime source.
 *
 *   1. VidLink   - vidlink.pro | Movie/TV/Universal | native JSON API + direct HLS masters.
 *                                                   Ranking #1, cannot be disabled.
 *   2. VidSrc    - vidsrc.pm   | Movie/TV | Vidora JSON API (`x-player-key`) -> multi-CDN
 *                                          HLS masters directly behind 1 RTT. Ranking #2.
 *   3. VidFast   - vidfast.vc  | Movie/TV | page token -> enc-vidfast stage 1/2 handshake ->
 *                                          per-server stream() decrypt -> moon.zenoak.top HLS
 *                                          ladder (2160/1080/720/480). Ranking #3.
 *   4. VidSrcSU  - vidsrc.su   | Movie/TV | X-Api-Key + X-Cap-Token images() metadata ->
 *                                          dec-hexa -> multi-CDN HLS (dragonballzfans.xyz).
 *                                          Ranking #4. Checked BEFORE VidSrc (name collision).
 *   5. VidRock   - vidrock.net | Movie/TV | lane API + offline AES-256-GCM unwrap ->
 *                                          multi-CDN HLS ladder; every media request is
 *                                          Origin/Referer pinned to vidrock.net. Rank #5.
 *   6. MeowTV    - meowtv.ru   | Movie/TV | single-use stream ticket -> per-server stream
 *                                          payload -> dec-meowtv -> hscow/vidjoy/fcxmb HLS.
 *                                          Ranking #6.
 *   7. Animex    - animex.one  | Anime    | GraphQL catalog + nero provider HLS with soft
 *                                          subs. Ranking #7, Anime-gated.
 *
 * Ordering note: the Movie/TV tier is VidLink (100) > VidSrc (95) > VidFast (94) >
 * VidSrcSU (93) > VidRock (92) > MeowTV (91) and Animex (90) is Anime-only. VidFast and
 * VidSrcSU rank above VidRock because both complete their handshake from a single resolver
 * round trip, while MeowTV trades one extra ticket round trip for its own CDN failover set;
 * all five were live-verified on 2026-10-15/16.
 *
 * Candidates that were researched and REJECTED, recorded so they are not re-introduced on a
 * later "find SOTA sources" pass:
 *
 *   - AnimePahe (animepahe.ru/.pw/.com/.org/.si): REMOVED in v23. The .ru front now injects a
 *     base64 redirect to `https://qyvir.com/go/2954557?...` (ad gateway, not a player); .pw,
 *     .com and .org fronts answer 403 behind Cloudflare; .si no longer resolves. The kwik.cx
 *     conveyor and its cached URL plumbing are retained for other extractors, but no
 *     registry entry depends on AnimePahe any more.
 *   - Dead / parked / hijacked DNS: moviesapi.club, embed.su, vidjoy.pro (obfuscated shell),
 *     vidjoy.to, vidplay.stream, vidbinge.com + api.vidbinge.com (parked on
 *     router.parklogic.com), peachify.to, smashystream.com, vidsrc.icu, vidsrc.cc,
 *     vidsrc.net (redirect shell), 2embed.to, autoembed.cc, rivestream.org, vidlink.cc,
 *     vidlink.live, vidjoy.pro, 2embed.cc.
 *   - Cloudflare / browser only: multiembed.mov (302 -> Turnstile), superembed, viduki.net
 *     (disable-devtool + fubuki.js), api.allanime.day ("Just a moment..." challenge).
 *   - Works but only through a fragile multi-hop + WASM/ChaCha20 + IP-bound token chain
 *     (vidsrc.to -> vsembed.ru -> stellarconductornexus -> data.vidsrc.sh; the wasm rotates
 *     its root key every 5 minutes): deliberately NOT ported, because that is a workaround
 *     rather than a production solution.
 *   - Requires re-implementing an obfuscated client proxy: miruro.tv (VITE_PROXY_OBF_KEY +
 *     s1/s2.keeply.top), 2embed.cc (streamsrcs.swish indirection), vidapi.cloud
 *     (metadata only; stream endpoint not exposed).
 *   - Removed API dependency: api.consumet.org now 301s away and its Railway deployment is
 *     gone ("Application not found"), so no Consumet-backed provider is registerable.
 *   - Superseded in-extractor mirrors: videm.xyz / vidsrc.buzz (VidEm) and animegg.org were
 *     both verified live during the v21 pass, but VidEm serves a single mintage origin with
 *     no CDN failover and AnimeGG leeches upstream mirrors; VidSrc + VidRock verified faster
 *     and more redundant in the v22 pass, so both entries were decommissioned.
 *
 * Consequently the whole legacy tier (rivestream, videasy, vidflix, vixsrc, hexasu,
 * autoembed, vidup, vidnest, cinejoy, yflix, the vidsrc family, and the entire
 * Indian/Hindi download-only scraper tier) stays removed. Note that `vidfast` was
 * decommissioned after the v19 pass and has been re-issued to the new v23 VidFast extractor:
 * it is deliberately NOT in REMOVED_PROVIDER_IDS so upgraded installs get it re-enabled.
 * VidFast and VidRock anchor the stream-only contract: both are declared in
 * DOWNLOAD_EXCLUDED_PROVIDER_IDS, so the host keeps them fully ranked for playback while
 * never routing them into downloads. VidRock is barred because its direct mp4 lanes serve
 * index-less, non-seekable containers (raw stream payloads behind .mp4 URLs) — a downloaded
 * copy cannot be scrubbed, so the provider stays a live streaming-only member.
 */
private val providers by lazy {
    listOf(
        Provider("vidlink", "VidLink") { res, subtitleCallback, callback, _, _ ->
            invokeVidlink(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
        },
        Provider("vidsrc", "VidSrc") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidSrc(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("vidfast", "VidFast") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidFast(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("vidsrcsu", "VidSrcSU") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidSrcSU(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("vidrock", "VidRock") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidrock(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("meowtv", "MeowTV") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeMeowTV(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("animex", "Animex") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeAnimex(
                    malId = ids.malId,
                    anilistId = ids.anilistId,
                    title = res.title,
                    episode = res.episode,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    dubtype = getDubStatus(res)
                )
            }
        }
    )
}

/**
 * The curated SOTA set. Order defines the authoritative source hierarchy:
 * VidLink (100) universal primary, VidSrc (95) > VidFast (94) > VidSrcSU (93) > VidRock (92)
 * > MeowTV (91) verified backups for Movie/TV, Animex (90) primary for Anime. The Movie/TV
 * set and the Anime source never race each other because each source is gated on the
 * request's `isAnime` classification, so source rank only ever decides between alternatives
 * that both serve the same content.
 */
val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "vidsrc",
    "vidfast",
    "vidsrcsu",
    "vidrock",
    "meowtv",
    "animex"
)

/**
 * Stream-only providers: fully live members of the curated streaming hierarchy above whose
 * delivery chain cannot produce a scrubbable library file, so they must NEVER be used as a
 * download source.
 *
 * Rationale per member:
 *  - `vidfast`: HLS-only delivery chain serves download-hostile renditions.
 *  - `vidrock`: direct mp4 lanes serve index-less, non-seekable containers (raw stream
 *    payloads behind .mp4 URLs); a downloaded copy cannot be scrubbed on any player.
 *
 * Contract with the CloudStream host: the host reads this set through its provider bridge
 * (`StreamPlayDynamicBridge`; the bridge also carries a static fallback for the current
 * members, so the ban holds even before the plugin snapshot loads) and funnels every
 * download stage — automatic selection, queue refreshes, and the manual download-mirror
 * picker — through it. Declaring an id here keeps the provider at full stream rank
 * (playback, fallbacks and mirror hunting are untouched) while making it permanently
 * ineligible for downloads, regardless of link ranking.
 *
 * Invariants enforced by the test suite:
 *  - every id must be a registered provider of the curated registry (excluding an
 *    unregistered id would be dead bookkeeping),
 *  - every id must be absent from [REMOVED_PROVIDER_IDS] (a stream-only source is live,
 *    not decommissioned),
 *  - and the download exclusion must never demote the provider's stream rank.
 */
val DOWNLOAD_EXCLUDED_PROVIDER_IDS = setOf("vidfast", "vidrock")

/**
 * Identifiers of every provider that has ever shipped in StreamPlay but is now
 * decommissioned. Retained so that upgrades of existing installs can scrub stale
 * user preferences entries. The exact ids `vidsrc`, `vidrock` and `vidfast` are
 * intentionally NOT listed: those identifiers were re-issued to the v22/v23 SOTA
 * sources, and listing them here would make the freshly registered providers
 * impossible to disable. `animepahe` (and its display-name spellings) is listed
 * because the v23 pass removed AnimePahe after its fronts were hijacked.
 */
val REMOVED_PROVIDER_IDS = setOf(
    "rivestream", "RiveStream", "videasy", "VidEasy",
    "vixsrc", "VixSrc",
    "videm", "VidEm", "animegg", "AnimeGG",
    "vidflix", "Vidflix", "Vidflix (Multi)",
    "superstream", "vaplayer", "vidcore", "Vidcore", "vidsrcxyz", "vidsrccc",
    "vidsrcto", "vidnest", "VidNest", "vidup", "Vidup",
    "cinejoy", "CineJoy", "HexaSU", "autoembed", "moviebox", "yflix", "YFlix",
    "vidsrcme", "vidsrcin", "vidsrcpm", "vidsrcnet", "peachify", "moviesapi",
    "vidzeeapi", "2Embed", "2embed", "uhdmovies", "multimovies", "4khdhub", "hdhub4u",
    "hdmovie2", "topmovies", "moviesmod", "bollyflix", "vegamovies", "Rogmovies",
    "nepu", "moviesdrive", "hianime", "animetosho", "ReAnime", "Animex", "kickass",
    "anichi", "anikage", "anineko", "tokyoinsider", "anizone", "watchsomuch", "ninetv",
    "allmovieland", "zshow", "kisskh", "dahmermovies", "CinemaCity", "Hindmoviez",
    "Movies4u", "M4uhd", "MappleTV", "WyZIESUB", "SubtitleAPI", "CineVood", "Filmyfiy",
    "DooFlix", "Xpass", "Dudefilms", "Zinkmovies", "Filmyfiy", "OpenSubs",
    "animepahe", "Animepahe", "AnimePahe"
)

fun getDefaultDisabledProviderIds(): Set<String> =
    buildProviders().map { it.id }.filterNot { it in DEFAULT_TOP_TIER_PROVIDERS }.toSet()

fun buildProviders(): List<Provider> = providers

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v23_initialized"

/**
 * Provider settings bootstrap + migration to the curated v23 SOTA registry.
 *
 * Clean installs: enable exactly [DEFAULT_TOP_TIER_PROVIDERS], disable nothing else.
 * VidLink is the pinnacle #1 provider and cannot be disabled.
 */
fun getOrInitializeDisabledProviders(sharedPref: SharedPreferences?): Set<String> {
    if (sharedPref == null) return getDefaultDisabledProviderIds().filterNot { it.equals("vidlink", ignoreCase = true) }.toSet()

    val isTopTierInitialized = sharedPref.getBoolean(PREFS_TOP_TIER_INITIALIZED, false)
    if (isTopTierInitialized) {
        val persisted = sharedPref.getStringSet("disabled_providers", null)
        if (persisted == null) return getDefaultDisabledProviderIds().filterNot { it.equals("vidlink", ignoreCase = true) }.toSet()
        // Never let a stale identifier from a decommissioned provider linger, and NEVER let vidlink be disabled
        val scrubbed = persisted.filterNot { it in REMOVED_PROVIDER_IDS || it.equals("vidlink", ignoreCase = true) }.toSet()
        if (scrubbed.size != persisted.size) {
            sharedPref.edit { putStringSet("disabled_providers", scrubbed) }
        }
        return scrubbed
    }

    val previousDisabled = sharedPref.getStringSet("disabled_providers", null)

    // Preserve an explicit user opt-out only for providers that still exist (except VidLink, which is mandatory)
    val registeredIds = buildProviders().map { it.id }.toSet()
    val carriedOver = previousDisabled.orEmpty()
        .filter { it in registeredIds && it !in DEFAULT_TOP_TIER_PROVIDERS && !it.equals("vidlink", ignoreCase = true) }
        .toSet()

    val finalDisabled = ((registeredIds - DEFAULT_TOP_TIER_PROVIDERS) + carriedOver)
        .filterNot { it.equals("vidlink", ignoreCase = true) }
        .toSet()

    sharedPref.edit {
        putStringSet("disabled_providers", finalDisabled)
        putBoolean(PREFS_TOP_TIER_INITIALIZED, true)
    }
    Log.d(
        "StreamPlay",
        "🎯 Migrated to curated SOTA registry v23: VidLink guaranteed active, " +
            "scrubbed ${REMOVED_PROVIDER_IDS.size} decommissioned provider identifiers"
    )
    return finalDisabled
}
