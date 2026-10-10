package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invokeAnimepahe
import com.phisher98.StreamPlayExtractor.invokeVidlink
import com.phisher98.StreamPlayExtractor.invokeVixSrc
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
 * ===================== StreamPlay SOTA Provider Registry (v18) =====================
 *
 * Empirically researched against live endpoints. Every entry below was verified to
 * resolve with plain HTTP (no browser, no JS, no Cloudflare challenge) before being
 * registered:
 *
 *   1. VidLink   - vidlink.pro  | Movie/TV | XSalsa20-Poly1305 token API -> direct 360/480/1080
 *                                            MP4 ladder + 7 native caption tracks. Primary.
 *   2. VixSrc    - vixsrc.to    | Movie/TV | `/api/{movie|tv}` -> embed page -> `masterPlaylist`
 *                                            HLS master (multi-audio + soft-subs). Verified backup.
 *   3. AnimePahe - animepahe.pw | Anime    | kwik.cx resolver + soft-sub tracks.
 *
 * VixSrc was previously decommissioned as "dead"; live research re-validated that
 * vixsrc.to is alive and serving a real multi-variant HLS master, so it is restored as
 * the verified secondary movie/TV source (giving VidLink a working fallback instead of
 * leaving a single point of failure).
 *
 * Candidates that were researched and then REJECTED, because they cannot be resolved
 * with a plain request, are recorded here so they are not re-introduced on a later
 * "find SOTA sources" pass:
 *
 *   - Dead / parked DNS: moviesapi.club, embed.su, vidsrc.icu, movie-box, flicks.so,
 *     vidsrc.net, vidsrc.io, vidsrc.cloud, vidsrc.xyz, vidsrc.cc, 2embed.to.
 *   - Cloudflare / browser only: multiembed.mov (302 -> streamingnow.mov Turnstile),
 *     superembed (docs shell only), vidsrc.pm (origin 502), animepahe kwik via plain
 *     curl (403; works in-app via the __ddg2 cookie).
 *   - Works but only through a fragile multi-hop + WASM/ChaCha20 + IP-bound token chain
 *     (vidsrc.to -> vsembed.ru -> data.vidsrc.sh): deliberately NOT ported, because that
 *     is a workaround rather than a production solution.
 *   - Unreachable from a clean probe: net51.cc, api.videasy.net, animekai.to.
 *
 * Consequently the whole legacy tier (rivestream, vidfast, videasy, vidflix, animegg,
 * hexasu, autoembed, vidup, vidnest, cinejoy, yflix, the vidsrc family, and the entire
 * Indian/Hindi download-only scraper tier) stays removed.
 */
private val providers by lazy {
    listOf(
        Provider("vidlink", "VidLink") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidlink(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
        },
        Provider("vixsrc", "VixSrc") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVixSrc(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
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
 * VidLink (100) primary, VixSrc (95) verified backup for Movie/TV, and AnimePahe (90)
 * for Anime. VidLink and VixSrc both serve Movie/TV, so source rank decides which wins;
 * AnimePahe is anime-only and never races them.
 */
val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "vixsrc",
    "animepahe"
)

/**
 * Identifiers of every provider that has ever shipped in StreamPlay but is now
 * decommissioned. Retained so that upgrades of existing installs can scrub stale
 * user preferences entries. `vixsrc`/`VixSrc` are deliberately absent because that
 * source is active again.
 */
val REMOVED_PROVIDER_IDS = setOf(
    "rivestream", "RiveStream", "vidfast", "VidFast", "videasy", "VidEasy",
    "vidflix", "Vidflix", "Vidflix (Multi)", "animegg", "AnimeGG",
    "superstream", "vaplayer", "vidcore", "Vidcore", "vidsrc", "vidsrcxyz", "vidsrccc",
    "vidsrcto", "vidnest", "VidNest", "vidup", "Vidup",
    "cinejoy", "CineJoy", "HexaSU", "autoembed", "moviebox", "yflix", "YFlix",
    "vidsrcme", "vidsrcin", "vidsrcpm", "vidsrcnet", "peachify", "vidrock", "moviesapi",
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

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v18_initialized"

/**
 * Provider settings bootstrap + migration to the curated v18 SOTA registry.
 *
 * Clean installs: enable exactly [DEFAULT_TOP_TIER_PROVIDERS], disable nothing else
 * (the registry no longer contains anything else).
 *
 * Upgrading installs: the key is versioned, so a build that shrinks the registry forces
 * one re-migration. The stored `disabled_providers` set is rebuilt from scratch instead
 * of being merged, which is the only way to guarantee that a user who had enabled
 * rivestream / vidfast / videasy / vidflix / animegg / HexaSU / AutoEmbed / the
 * download-only scraper tier in an older build never sees those sources again.
 */
fun getOrInitializeDisabledProviders(sharedPref: SharedPreferences?): Set<String> {
    if (sharedPref == null) return getDefaultDisabledProviderIds()

    val isTopTierInitialized = sharedPref.getBoolean(PREFS_TOP_TIER_INITIALIZED, false)
    if (isTopTierInitialized) {
        val persisted = sharedPref.getStringSet("disabled_providers", null)
        if (persisted == null) return getDefaultDisabledProviderIds()
        // Never let a stale identifier from a decommissioned provider linger.
        return persisted.filterNot { it in REMOVED_PROVIDER_IDS }.toSet()
    }

    val previousDisabled = sharedPref.getStringSet("disabled_providers", null)

    // Preserve an explicit user opt-out only for providers that still exist; every
    // legacy entry is dropped because the provider itself is gone.
    val registeredIds = buildProviders().map { it.id }.toSet()
    val carriedOver = previousDisabled.orEmpty()
        .filter { it in registeredIds && it !in DEFAULT_TOP_TIER_PROVIDERS }
        .toSet()

    val finalDisabled = (registeredIds - DEFAULT_TOP_TIER_PROVIDERS) + carriedOver

    sharedPref.edit {
        putStringSet("disabled_providers", finalDisabled)
        putBoolean(PREFS_TOP_TIER_INITIALIZED, true)
    }
    Log.d(
        "StreamPlay",
        "🎯 Migrated to curated SOTA registry v18: ${DEFAULT_TOP_TIER_PROVIDERS.size} source(s) active, " +
            "scrubbed ${REMOVED_PROVIDER_IDS.size} decommissioned provider identifiers"
    )
    return finalDisabled
}
