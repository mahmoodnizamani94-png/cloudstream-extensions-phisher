package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invokeAnimex
import com.phisher98.StreamPlayExtractor.invokeCineby
import com.phisher98.StreamPlayExtractor.invokeCineSrc
import com.phisher98.StreamPlayExtractor.invokeMegaPlay
import com.phisher98.StreamPlayExtractor.invokeMeowTV
import com.phisher98.StreamPlayExtractor.invokeVidFast
import com.phisher98.StreamPlayExtractor.invokeVidlink
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
 * ===================== StreamPlay SOTA Provider Registry (v24) =====================
 *
 * Eight sources, every one of them verified end-to-end against live playback with plain
 * HTTP (no browser, no JS execution, no Cloudflare interstitial).
 *
 *   1. VidLink   - vidlink.pro       | Universal (Movie/TV/Anime) | Rank 100 (#1, mandatory)
 *   2. Cineby    - api.wecollege.net | Universal (Movie/TV/Anime) | Rank 96  (#2, multi-server + dec-videasy)
 *   3. CineSrc   - cinesrc.st        | Universal (Movie/TV/Anime) | Rank 95  (#3, CSP4 PoW + Nebula/Wave/Surge + subs)
 *   4. VidFast   - vidfast.vc        | Movie/TV (stream-only)     | Rank 94  (#4, enc-vidfast handshake)
 *   5. VidSrcSU  - vidsrc.su         | Movie/TV                   | Rank 93  (#5, hexa protocol)
 *   6. MegaPlay  - megaplay.buzz     | Anime                      | Rank 92  (#6, MAL/AniList + dec-megaplay)
 *   7. MeowTV    - meowtv.ru         | Movie/TV                   | Rank 91  (#7, single-use ticket + dec-meowtv)
 *   8. Animex    - animex.one        | Anime                      | Rank 90  (#8, GraphQL + concurrent multi-provider fan-out)
 *
 * VidRock (`vidrock`, `vidrock.net`, `galacticvoyagers.xyz`, `moonfirekeeper.lol`) and
 * VidSrc (`vidsrc`, `vidsrc.pm`, `cheaptruckrepairs.cc`, `netoda.tech`, `mov2day.xyz`) are
 * permanently removed and banned because VidRock serves index-less non-seekable files and
 * VidSrc stalls/throttles downloads after ~1 MB.
 */
private val providers by lazy {
    listOf(
        Provider("vidlink", "VidLink") { res, subtitleCallback, callback, _, _ ->
            invokeVidlink(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
        },
        Provider("cineby", "Cineby") { res, subtitleCallback, callback, _, _ ->
            invokeCineby(
                tmdbId = res.id,
                season = res.season,
                episode = res.episode,
                subtitleCallback = subtitleCallback,
                callback = callback,
                imdbId = res.imdbId,
                title = res.title,
                year = res.year ?: res.airedYear,
                isAnime = res.isAnime
            )
        },
        Provider("cinesrc", "CineSrc") { res, subtitleCallback, callback, _, _ ->
            invokeCineSrc(
                tmdbId = res.id,
                season = res.season,
                episode = res.episode,
                subtitleCallback = subtitleCallback,
                callback = callback,
                imdbId = res.imdbId,
                title = res.title,
                year = res.year ?: res.airedYear,
                isAnime = res.isAnime
            )
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
        Provider("megaplay", "MegaPlay") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeMegaPlay(
                    malId = ids.malId,
                    anilistId = ids.anilistId,
                    episode = res.episode,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    dubtype = getDubStatus(res)
                )
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
 * The curated v24 SOTA set. Order defines the authoritative source hierarchy:
 * VidLink (100), Cineby (96), and CineSrc (95) are universal primaries (Movie, TV, Anime);
 * VidFast (94) > VidSrcSU (93) > MeowTV (91) serve Movie/TV;
 * MegaPlay (92) > Animex (90) serve Anime alongside VidLink (100), Cineby (96), and CineSrc (95).
 */
val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "cineby",
    "cinesrc",
    "vidfast",
    "vidsrcsu",
    "megaplay",
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
 *
 * Note: `vidrock` and `vidsrc` (`vidsrc.pm`) were completely stripped and moved to
 * [REMOVED_PROVIDER_IDS] because VidRock served non-seekable index-less files and VidSrc
 * throttled/stalled downloads after ~1 MB.
 */
val DOWNLOAD_EXCLUDED_PROVIDER_IDS = setOf("vidfast")

/**
 * Identifiers of every provider that has ever shipped in StreamPlay but is now
 * decommissioned. Retained so that upgrades of existing installs can scrub stale
 * user preferences entries. `vidrock` and `vidsrc` (`vidsrc.pm`) are permanently
 * decommissioned here.
 */
val REMOVED_PROVIDER_IDS = setOf(
    "vidrock", "Vidrock", "VidRock",
    "vidsrc", "Vidsrc", "VidSrc", "vidsrc.pm",
    "rivestream", "RiveStream", "videasy", "VidEasy",
    "vixsrc", "VixSrc",
    "videm", "VidEm", "animegg", "AnimeGG",
    "vidflix", "Vidflix", "Vidflix (Multi)",
    "superstream", "vaplayer", "vidcore", "Vidcore", "vidsrcxyz", "vidsrccc",
    "vidsrcto", "vidnest", "VidNest", "vidup", "Vidup",
    "cinejoy", "CineJoy", "hexasu", "HexaSU", "autoembed", "moviebox", "yflix", "YFlix",
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

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v24_initialized"

/**
 * Provider settings bootstrap + migration to the curated v24 SOTA registry.
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
        "🎯 Migrated to curated SOTA registry v24: VidLink guaranteed active, " +
            "scrubbed ${REMOVED_PROVIDER_IDS.size} decommissioned provider identifiers"
    )
    return finalDisabled
}
