package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invokeAnimePahe
import com.phisher98.StreamPlayExtractor.invokeAnimex
import com.phisher98.StreamPlayExtractor.invokeAniZone
import com.phisher98.StreamPlayExtractor.invokeCineby
import com.phisher98.StreamPlayExtractor.invokeCineSrc
import com.phisher98.StreamPlayExtractor.invokeMegaPlay
import com.phisher98.StreamPlayExtractor.invokeMeowTV
import com.phisher98.StreamPlayExtractor.invokeVidFast
import com.phisher98.StreamPlayExtractor.invokeVidRift
import com.phisher98.StreamPlayExtractor.invokeVidlink
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

/**
 * Every name a title is known by, for the sources that can only be queried by name.
 *
 * AnimePahe and AniZone index a show under its English, Romaji, native or synonym release title,
 * and neither exposes an AniList/MAL id lookup. Searching only `title` is why a localized show
 * used to produce no result at all. For Season 2+ or multi-cour continuations inside `StreamPlay`,
 * the resolved cour/season title (`resolvedSeasonTitle`) and season-qualified title variants are
 * placed first so Season 2+ never collides with Season 1 in name-keyed catalogues.
 */
internal fun StreamPlay.LinkData.animeSourceTitles(
    resolvedSeasonTitle: String? = null
): List<String> {
    val seasonNum = season ?: 1
    val baseTitles = listOfNotNull(title, jpTitle, orgTitle, alttitle, nametitle)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    val seasonQualified = if (seasonNum >= 2) {
        baseTitles.flatMap { base ->
            if (Regex("""(?i)\b(?:season|part|cour)\s*\d+\b|\b\d+(?:st|nd|rd|th)\s+season\b""").containsMatchIn(base)) {
                listOf(base)
            } else {
                val ordinal = when (seasonNum) {
                    2 -> "2nd Season"
                    3 -> "3rd Season"
                    else -> "${seasonNum}th Season"
                }
                listOf("$base Season $seasonNum", "$base $ordinal")
            }
        }
    } else {
        emptyList()
    }
    return (listOfNotNull(resolvedSeasonTitle?.trim()?.takeIf { it.isNotBlank() }) + seasonQualified + baseTitles)
        .distinctBy { it.lowercase() }
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
        tmdbYear = tmdbYear,
        resolvedEpisode = resolvedEpisode
    )
}

private suspend fun getAnimeIds(res: StreamPlay.LinkData): StreamPlayExtractor.AnimeResolvedIds {
    val directAniId = res.aniId?.toIntOrNull()
    val directMalId = res.malId
    val mayNeedCourWalk = (res.season ?: 1) >= 2 || (res.episode ?: 1) > 12
    if ((directAniId != null || directMalId != null) && !mayNeedCourWalk) {
        return StreamPlayExtractor.AnimeResolvedIds(
            malId = directMalId,
            anilistId = directAniId,
            anidbEid = res.anidbEid ?: 0,
            zoroIds = null,
            zoroTitle = res.title,
            aniXL = null,
            kaasSlug = null,
            animepaheUrl = null,
            animekaiId = null,
            tmdbYear = res.year ?: res.airedYear,
            resolvedEpisode = res.episode
        )
    }

    val cacheKey = "${res.id ?: 0}_${res.imdbId.orEmpty()}_${res.title}_${res.date ?: res.airedDate}_${res.season ?: 0}_${res.episode ?: 0}"

    val cached = StreamPlayCache.getCachedAnimeIds(cacheKey)
    if (cached != null) {
        return cached.toResolvedAnimeIds()
    }

    val ids = animeIdResolveMutex.withLock {
        StreamPlayCache.getCachedAnimeIds(cacheKey)?.let { cachedAfterWait ->
            return@withLock cachedAfterWait.toResolvedAnimeIds()
        }

        resolveAnimeIds(
            title = res.title,
            date = res.date,
            airedDate = res.airedDate,
            season = res.season,
            episode = res.episode,
            tmdbId = res.id,
            imdbId = res.imdbId,
            initialAniId = directAniId,
            initialMalId = directMalId
        )
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
            tmdbYear = ids.tmdbYear,
            resolvedEpisode = ids.resolvedEpisode
        )
    )

    return ids
}

/**
 * ===================== StreamPlay SOTA Provider Registry (v26) =====================
 *
 * Ten sources, every one of them verified live at the protocol/origin level with plain HTTP
 * (no browser and no interactive Cloudflare interstitial). AnimePahe's origin is Cloudflare-gated;
 * its challenge is answered by the app's anti-bot interceptor, exactly like its standalone
 * provider, and the `.com` -> `.pw` redirect was verified on 2026-10-23.
 *
 *   1. VidLink   - vidlink.pro       | Universal (Movie/TV/Anime) | Rank 100 (#1, mandatory)
 *   2. VidRift   - embed.vidrift.net | Movie/TV                   | Rank 98  (#2, Titan 720p direct + Atlas/Orion/BigBang/Vexon + subs)
 *   3. Cineby    - api.wecollege.net | Universal (Movie/TV/Anime) | Rank 95  (#3, multi-server + offline mvm1)
 *   4. CineSrc   - cinesrc.st        | Universal (Movie/TV/Anime) | Rank 94  (#4, CSP4 PoW + Nebula/Wave/Surge + subs)
 *   5. VidFast   - vidfast.vc        | Movie/TV (stream-only)     | Rank 93  (#5, enc-vidfast handshake)
 *   6. MegaPlay  - megaplay.buzz     | Anime                      | Rank 92  (#6, MAL/AniList + offline AES-CBC/HMAC)
 *   7. MeowTV    - meowtv.ru         | Movie/TV                   | Rank 91  (#7, single-use ticket + offline SHA-256 XOR)
 *   8. AniZone   - anizone.to        | Anime                      | Rank 89  (#8, soft-sub HLS ladder + English SRT/ASS sidecars)
 *   9. AnimePahe - animepahe.pw      | Anime                      | Rank 88  (#9, Cloudflare-gated Kwik HLS, deepest airing/rare catalogue)
 *  10. Animex    - animex.one        | Anime                      | Rank 87  (#10, GraphQL + SSR + kaa.lt multi-provider fan-out)
 *
 * VidRift (`embed.vidrift.net`) is the dedicated Movie/TV 720p-first powerhouse: Wave 1A resolves
 * owned Cloudflare R2 H.264 MPEG-TS lanes (`Direct`, `Vexon`, `Evion`), Wave 1B co-stages `Titan`
 * (viewer-direct 720p muxed H.264/AAC HLS fast-path) and `Atlas` (adaptive 720p+1080p H.264),
 * Wave 2 resolves `Vela` and proxied `Lyra` (rejecting unproxied server-IP-locked `Lyra` URLs),
 * and Wave 3 isolates `Orion` and `BigBang` (fMP4 HEVC) as a zero-link fallback while always
 * harvesting synchronized WebVTT sidecar subtitles.
 *
 * VidRock (`vidrock`, `vidrock.net`, `galacticvoyagers.xyz`, `moonfirekeeper.lol`),
 * VidSrc (`vidsrc`, `vidsrc.pm`, `cheaptruckrepairs.cc`, `netoda.tech`, `mov2day.xyz`), and
 * VidSrcSU (`vidsrcsu`, `vidsrc.su`, `themoviedb.vidsrc.su`, `theemoviedb.hexa.su`, `dragonballzfans.xyz`)
 * are permanently removed and banned.
 */
private val providers by lazy {
    listOf(
        Provider("vidlink", "VidLink") { res, subtitleCallback, callback, _, _ ->
            invokeVidlink(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId, isAnime = res.isAnime)
        },
        Provider("vidrift", "VidRift") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) {
                invokeVidRift(res.id, res.season, res.episode, subtitleCallback, callback, res.imdbId)
            }
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
        Provider("megaplay", "MegaPlay") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeMegaPlay(
                    malId = ids.malId,
                    anilistId = ids.anilistId,
                    episode = ids.resolvedEpisode ?: res.episode,
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
        Provider("anizone", "AniZone") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = if ((res.season ?: 1) >= 2 || (res.episode ?: 1) > 12) getAnimeIds(res) else null
                invokeAniZone(
                    titles = res.animeSourceTitles(ids?.zoroTitle),
                    year = res.year ?: res.airedYear,
                    episode = ids?.resolvedEpisode ?: res.episode,
                    isMovie = res.isMovie == true,
                    dubtype = getDubStatus(res),
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        },
        Provider("animepahe", "AnimePahe") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = if ((res.season ?: 1) >= 2 || (res.episode ?: 1) > 12) getAnimeIds(res) else null
                invokeAnimePahe(
                    titles = res.animeSourceTitles(ids?.zoroTitle),
                    year = res.year ?: res.airedYear,
                    episode = ids?.resolvedEpisode ?: res.episode,
                    isMovie = res.isMovie == true,
                    dubtype = getDubStatus(res),
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        },
        Provider("animex", "Animex") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeAnimex(
                    malId = ids.malId,
                    anilistId = ids.anilistId,
                    title = ids.zoroTitle ?: res.title,
                    episode = ids.resolvedEpisode ?: res.episode,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    dubtype = getDubStatus(res),
                    jpTitle = res.jpTitle ?: res.title
                )
            }
        }
    )
}

/**
 * The curated v26 SOTA set. Order defines the authoritative source hierarchy:
 * VidLink (100), Cineby (95), and CineSrc (94) are universal primaries (Movie, TV, Anime);
 * VidRift (98) > VidFast (93) > MeowTV (91) serve Movie/TV;
 * MegaPlay (92) > AniZone (89) > AnimePahe (88) > Animex (87) serve Anime alongside
 * VidLink (100), Cineby (95), and CineSrc (94).
 */
val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "vidrift",
    "cineby",
    "cinesrc",
    "vidfast",
    "megaplay",
    "meowtv",
    "anizone",
    "animepahe",
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
 * Note: `vidrock`, `vidsrc` (`vidsrc.pm`), and `vidsrcsu` (`vidsrc.su`) were completely stripped
 * and moved to [REMOVED_PROVIDER_IDS].
 */
val DOWNLOAD_EXCLUDED_PROVIDER_IDS = setOf("vidfast")

/**
 * Identifiers of every provider that has ever shipped in StreamPlay but is now
 * decommissioned. Retained so that upgrades of existing installs can scrub stale
 * user preferences entries. `vidrock`, `vidsrc`, and `vidsrcsu` are permanently
 * decommissioned here.
 */
val REMOVED_PROVIDER_IDS = setOf(
    "vidrock", "Vidrock", "VidRock",
    "vidsrc", "Vidsrc", "VidSrc", "vidsrc.pm",
    "vidsrcsu", "Vidsrcsu", "VidSrcSU", "vidsrc.su", "themoviedb.vidsrc.su", "theemoviedb.hexa.su", "dragonballzfans",
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
    "anichi", "anikage", "anineko", "tokyoinsider", "watchsomuch", "ninetv",
    "allmovieland", "zshow", "kisskh", "dahmermovies", "CinemaCity", "Hindmoviez",
    "Movies4u", "M4uhd", "MappleTV", "WyZIESUB", "SubtitleAPI", "CineVood", "Filmyfiy",
    "DooFlix", "Xpass", "Dudefilms", "Zinkmovies", "Filmyfiy", "OpenSubs"
)

fun getDefaultDisabledProviderIds(): Set<String> =
    buildProviders().map { it.id }.filterNot { it in DEFAULT_TOP_TIER_PROVIDERS }.toSet()

fun buildProviders(): List<Provider> = providers

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v26_initialized"

/**
 * Provider settings bootstrap + migration to the curated v26 SOTA registry.
 *
 * Clean installs: enable exactly [DEFAULT_TOP_TIER_PROVIDERS], disable nothing else.
 * VidLink is the pinnacle #1 provider and cannot be disabled.
 *
 * The v26 key is deliberately new: VidRift (`vidrift`) is the new 720p-first Movie/TV source,
 * and users upgrading from v25 must receive it enabled even if their persisted disabled set was
 * written by the previous migration.
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
        "🎯 Migrated to curated SOTA registry v26: VidLink guaranteed active, " +
            "VidRift enabled for Movie/TV, AniZone + AnimePahe enabled for anime, " +
            "scrubbed ${REMOVED_PROVIDER_IDS.size} decommissioned provider identifiers"
    )
    return finalDisabled
}
