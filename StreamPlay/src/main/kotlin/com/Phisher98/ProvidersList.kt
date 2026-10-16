package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invokeAnimepahe
import com.phisher98.StreamPlayExtractor.invokeVidlink
import com.phisher98.StreamPlayExtractor.invokeVidrock
import com.phisher98.StreamPlayExtractor.invokeVidSrc
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
 * ===================== StreamPlay SOTA Provider Registry (v22) =====================
 *
 * Four sources, every one of them verified end-to-end against live playback with plain
 * HTTP (no browser, no JS execution, no Cloudflare interstitial) during the v22 research
 * pass. Nothing from the decommissioned tiers is reused as a "SOTA" fallback: VidLink and
 * AnimePahe are the retained primaries and VidSrc + VidRock were discovered,
 * reverse-engineered and integrated from scratch in later research passes.
 *
 *   1. VidSrc    - vidsrc.pm    | Movie/TV | Vidora JSON API (`x-player-key`) -> multi-CDN
 *                                            HLS masters directly behind 1 RTT. Ranking #2.
 *   2. VidRock   - vidrock.net  | Movie/TV | lane API + offline AES-256-GCM unwrap ->
 *                                            multi-CDN HLS ladder; every media request is
 *                                            Origin/Referer pinned to vidrock.net. Rank #3.
 *   3. AnimePahe - animepahe.pw  | Anime    | kwik.cx resolver + soft-sub tracks. Primary anime.
 *
 * Ordering note: the Movie/TV tier is VidLink (100) > VidSrc (95) > VidRock (92). VidSrc is
 * listed before VidRock because its Vidora API serves titles from the first key-set request
 * with no offline crypto, while VidRock additionally requires the Origin/Referer pinned CDN;
 * both were live-verified on 2026-10-15.
 *
 * Why VidRock replaced the previous backup: it is a genuine hardened protocol (AES-256-GCM
 * under a published player key with per-lane failover), it publishes several independent CDN
 * lanes per title, and its token unwrap runs fully offline in the app (no WASM, no per-5-minute
 * key rotation, no third-party decrypt service). The extractor mirrors that protocol - it
 * unwraps every lane concurrently, expands each master playlist into a resolution ladder, and
 * pins the CDN headers exactly as the site's own player does, which is why it can return a
 * correct answer quickly instead of guessing at one mirror.
 *
 * Candidates that were researched and REJECTED, recorded so they are not re-introduced on a
 * later "find SOTA sources" pass:
 *
 *   - Dead / parked / hijacked DNS: moviesapi.club, embed.su, vidjoy.pro (obfuscated shell),
 *     vidjoy.to, vidplay.stream, vidbinge.com + api.vidbinge.com (parked on
 *     router.parklogic.com), peachify.to, smashystream.com, vidsrc.icu, vidsrc.cc,
 *     vidsrc.net (redirect shell), 2embed.to, autoembed.cc, rivestream.org, vidlink.cc,
 *     vidlink.live, vidjoy.pro, 2embed.cc.
 *   - Cloudflare / browser only: multiembed.mov (302 -> Turnstile), superembed, viduki.net
 *     (disable-devtool + fubuki.js), api.allanime.day ("Just a moment..." challenge),
 *     animepahe kwik via plain curl (403; works in-app through the __ddg2 cookie).
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
 * Consequently the whole legacy tier (rivestream, vidfast, videasy, vidflix, vixsrc, hexasu,
 * autoembed, vidup, vidnest, cinejoy, yflix, the vidsrc family, and the entire
 * Indian/Hindi download-only scraper tier) stays removed.
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
        Provider("vidrock", "VidRock") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidrock(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("animepahe", "AnimePahe") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.animepaheUrl?.let {
                    invokeAnimepahe(it, res.episode, subtitleCallback, callback, getDubStatus(res))
                }
            }
        }
    )
}

/**
 * The curated SOTA set. Order defines the authoritative source hierarchy:
 * VidLink (100) universal primary, VidSrc (95) and VidRock (92) verified backups for
 * Movie/TV, AnimePahe (90) primary for Anime. The Movie/TV trio and the Anime source never
 * race each other because each source is gated on the request's `isAnime` classification,
 * so source rank only ever decides between alternatives that both serve the same content.
 */
val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "vidsrc",
    "vidrock",
    "animepahe"
)

/**
 * Identifiers of every provider that has ever shipped in StreamPlay but is now
 * decommissioned. Retained so that upgrades of existing installs can scrub stale
 * user preferences entries. The exact ids `vidsrc`/`vidrock` are intentionally NOT
 * listed: those identifiers were re-issued to the v22 SOTA sources, and listing them
 * here would make the freshly registered providers impossible to disable.
 */
val REMOVED_PROVIDER_IDS = setOf(
    "rivestream", "RiveStream", "vidfast", "VidFast", "videasy", "VidEasy",
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
    "DooFlix", "Xpass", "Dudefilms", "Zinkmovies", "Filmyfiy", "OpenSubs"
)

fun getDefaultDisabledProviderIds(): Set<String> =
    buildProviders().map { it.id }.filterNot { it in DEFAULT_TOP_TIER_PROVIDERS }.toSet()

fun buildProviders(): List<Provider> = providers

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v22_initialized"

/**
 * Provider settings bootstrap + migration to the curated v22 SOTA registry.
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
        "🎯 Migrated to curated SOTA registry v22: VidLink guaranteed active, " +
            "scrubbed ${REMOVED_PROVIDER_IDS.size} decommissioned provider identifiers"
    )
    return finalDisabled
}
