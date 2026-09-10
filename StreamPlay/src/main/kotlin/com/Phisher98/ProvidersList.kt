package com.phisher98

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.phisher98.StreamPlayExtractor.invoke2embed
import com.phisher98.StreamPlayExtractor.invoke4khdhub
import com.phisher98.StreamPlayExtractor.invokeAllMovieland
import com.phisher98.StreamPlayExtractor.invokeAnichi
import com.phisher98.StreamPlayExtractor.invokeAnikage
import com.phisher98.StreamPlayExtractor.invokeAnineko
import com.phisher98.StreamPlayExtractor.invokeAnimepahe
import com.phisher98.StreamPlayExtractor.invokeAnimetosho
import com.phisher98.StreamPlayExtractor.invokeAnimex
import com.phisher98.StreamPlayExtractor.invokeAnizone
import com.phisher98.StreamPlayExtractor.invokeAutoembed
import com.phisher98.StreamPlayExtractor.invokeBollyflix
import com.phisher98.StreamPlayExtractor.invokeCineVood
import com.phisher98.StreamPlayExtractor.invokeDahmerMovies
import com.phisher98.StreamPlayExtractor.invokeDooflix
import com.phisher98.StreamPlayExtractor.invokeDudefilms
import com.phisher98.StreamPlayExtractor.invokeFilmyfiy
import com.phisher98.StreamPlayExtractor.invokeHdmovie2
import com.phisher98.StreamPlayExtractor.invokeHexa
import com.phisher98.StreamPlayExtractor.invokeHianime
import com.phisher98.StreamPlayExtractor.invokeHindmoviez
import com.phisher98.StreamPlayExtractor.invokeKickAssAnime
import com.phisher98.StreamPlayExtractor.invokeKisskh
import com.phisher98.StreamPlayExtractor.invokeM4uhd
import com.phisher98.StreamPlayExtractor.invokeMapple
import com.phisher98.StreamPlayExtractor.invokeMovieBox
import com.phisher98.StreamPlayExtractor.invokeMovies4u
import com.phisher98.StreamPlayExtractor.invokeMoviesApi
import com.phisher98.StreamPlayExtractor.invokeMoviesdrive
import com.phisher98.StreamPlayExtractor.invokeMoviesmod
import com.phisher98.StreamPlayExtractor.invokeMultimovies
import com.phisher98.StreamPlayExtractor.invokeNepu
import com.phisher98.StreamPlayExtractor.invokeNinetv
import com.phisher98.StreamPlayExtractor.invokePeachify
import com.phisher98.StreamPlayExtractor.invokeReAnime
import com.phisher98.StreamPlayExtractor.invokeRiveStream
import com.phisher98.StreamPlayExtractor.invokeRogmovies
import com.phisher98.StreamPlayExtractor.invokeSubtitleAPI
import com.phisher98.StreamPlayExtractor.invokeTokyoInsider
import com.phisher98.StreamPlayExtractor.invokeTopMovies
import com.phisher98.StreamPlayExtractor.invokeUhdmovies
import com.phisher98.StreamPlayExtractor.invokeVegamovies
import com.phisher98.StreamPlayExtractor.invokeVidFast
import com.phisher98.StreamPlayExtractor.invokeVidSrcXyz
import com.phisher98.StreamPlayExtractor.invokeVideasy
import com.phisher98.StreamPlayExtractor.invokeVidlink
import com.phisher98.StreamPlayExtractor.invokeVidzee
import com.phisher98.StreamPlayExtractor.invokeWatchsomuch
import com.phisher98.StreamPlayExtractor.invokeWYZIESubs
import com.phisher98.StreamPlayExtractor.invokeXpass
import com.phisher98.StreamPlayExtractor.invokeZinkmovies
import com.phisher98.StreamPlayExtractor.invokeZshow
import com.phisher98.StreamPlayExtractor.invokecinemacity
import com.phisher98.StreamPlayExtractor.invokehdhub4u
import com.phisher98.StreamPlayExtractor.invokevidrock
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

private val providers by lazy {
    listOf(
        Provider("vidlink", "Vidlink") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeVidlink(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("HexaSU", "HexaSU") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeHexa(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("autoembed", "AutoEmbed") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeAutoembed(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("vidfast", "VidFast") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeVidFast(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("VidEasy", "VidEasy") { res, subtitleCallback, callback, _, _ ->
            val titleToUse = res.title ?: res.orgTitle ?: res.nametitle
            if (!res.isAnime) invokeVideasy(titleToUse, res.id, res.imdbId, res.year, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("uhdmovies", "UHD Movies") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeUhdmovies(res.title, res.year, res.season, res.episode, callback, subtitleCallback)
        },
        Provider("hianime", "HiAnime") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.malId?.let { invokeHianime(it, res.episode, subtitleCallback, callback, getDubStatus(res)) }
            }
        },
        Provider("animetosho", "AnimeTosho") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.malId?.let { invokeAnimetosho(
                    subtitleCallback, callback, getDubStatus(res), ids.anidbEid) }
            }
        },
        Provider("ReAnime", "ReAnime") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.anilistId?.let { invokeReAnime(it, res.episode, subtitleCallback, callback, getDubStatus(res)) }
            }
        },
        Provider("Animex", "Animex") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeAnimex(ids.malId, ids.anilistId, res.jpTitle, res.episode, subtitleCallback, callback, getDubStatus(res))
            }
        },
        Provider("kickass", "KickAssAnime") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.kaasSlug?.let { invokeKickAssAnime(res.title, it, res.episode, subtitleCallback, callback, getDubStatus(res)) }
            }
        },
        Provider("animepahe", "AnimePahe") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                ids.animepaheUrl?.let { invokeAnimepahe(it, res.episode, subtitleCallback, callback, getDubStatus(res)) }
            }
        },
        Provider("anichi", "Anichi / AllAnime") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeAnichi(res.jpTitle, res.title, ids.tmdbYear, res.episode, subtitleCallback, callback, getDubStatus(res))
            }
        },
        Provider("anikage", "Anikage") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                val ids = getAnimeIds(res)
                invokeAnikage(ids.anilistId, res.title ?: res.jpTitle, res.episode, subtitleCallback, callback, getDubStatus(res))
            }
        },
        Provider("anineko", "AniNeko") { res, subtitleCallback, callback, _, _ ->
            if (res.isAnime) {
                invokeAnineko(res.title, res.jpTitle, res.episode, subtitleCallback, callback, getDubStatus(res))
            }
        },
        Provider("tokyoinsider", "Tokyo Insider") { res, _, callback, _, _ ->
            if (res.isAnime) {
                invokeTokyoInsider(res.jpTitle, res.title, res.episode, callback, getDubStatus(res))
            }
        },
        Provider("anizone", "AniZone") { res, subtitleCallback, callback, _, _ ->
            Log.d("Phisher",res.jpTitle.toString())
            if (res.isAnime) {
                invokeAnizone(res.jpTitle, res.episode, subtitleCallback , callback, getDubStatus(res))
            }
        },
        Provider("topmovies", "Top Movies") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeTopMovies(res.imdbId,
                res.season, res.episode, subtitleCallback, callback)
        },
        Provider("moviesmod", "MoviesMod") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeMoviesmod(
                res.imdbId,
                res.season, res.episode, subtitleCallback, callback)
        },
        Provider("bollyflix", "Bollyflix") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeBollyflix(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("watchsomuch", "WatchSoMuch", ProviderKind.SUBTITLE) { res, subtitleCallback, _, _, _ ->
            if (!res.isAnime) invokeWatchsomuch(res.imdbId, res.season, res.episode, subtitleCallback)
        },
        Provider("ninetv", "NineTV") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeNinetv(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("allmovieland", "AllMovieland") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeAllMovieland(res.imdbId, res.season, res.episode, callback)
        },
        Provider("vegamovies", "VegaMovies") { res, subtitleCallback, callback, _, _ ->
            if (!res.isBollywood) invokeVegamovies(res.title, res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("Rogmovies", "RogMovies") { res, subtitleCallback, callback, _, _ ->
            if (res.isBollywood) invokeRogmovies(res.title, res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("multimovies", "MultiMovies") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeMultimovies(res.title, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("zshow", "ZShow") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeZshow(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("nepu", "Nepu") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeNepu(res.title, res.airedYear ?: res.year, res.season, res.episode, callback)
        },
        Provider("moviesdrive", "MoviesDrive") { res, subtitleCallback, callback, _, _ ->
            invokeMoviesdrive(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("vidsrcxyz", "VidSrcXyz") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeVidSrcXyz(res.imdbId, res.season, res.episode, callback)
        },
        Provider("vidzeeapi", "Vidzee API") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeVidzee(res.id, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("4khdhub", "4kHdhub (Multi)") { res, subtitleCallback, callback, _, _ ->
            invoke4khdhub(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("hdhub4u", "Hdhub4u (Multi)") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokehdhub4u(res.imdbId, res.title, res.year, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("hdmovie2", "Hdmovie2") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeHdmovie2(res.title, res.year,
                res.episode, subtitleCallback, callback)
        },
        Provider("rivestream", "RiveStream") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeRiveStream(res.id, res.season, res.episode, callback)
        },
        Provider("moviebox", "MovieBox (Multi)") { res, subtitleCallback, callback, _, _ ->
            invokeMovieBox(res.title, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("vidrock", "Vidrock") { res, _, callback, _, _ ->
            if (!res.isAnime) invokevidrock(res.id, res.season, res.episode, callback)
        },
        Provider("kisskh", "KissKH (Asian Drama)") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeKisskh(res.title, res.season, res.episode, res.lastSeason, subtitleCallback, callback)
        },
        Provider("dahmermovies", "DahmerMovies") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeDahmerMovies(res.title, res.year, res.season, res.episode, callback)
        },
        Provider("moviesapi", "MoviesApi Club") { res, _, callback, _, _ ->
            invokeMoviesApi(res.id, res.season, res.episode, callback)
        },
        Provider("CinemaCity", "CinemaCity") { res, _, callback, _, _ ->
            invokecinemacity(res.imdbId, res.season,res.episode,  callback)
        },
        Provider("Hindmoviez", "HindMoviez") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeHindmoviez(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("Movies4u", "Movies4u") { res, subtitleCallback, callback, _, _ ->
            invokeMovies4u(res.imdbId, res.title,res.year, res.season, res.episode, subtitleCallback ,callback)
        },
        Provider("M4uhd", "M4uhd") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeM4uhd(res.title,
                res.season, res.episode, subtitleCallback ,callback)
        },
        Provider("MappleTV", "MappleTV") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeMapple(res.id, res.season, res.episode ,callback)
        },
        Provider("WyZIESUB", "WyZIESUB (Subtitles)", ProviderKind.SUBTITLE) { res, subtitleCallback, _, _, _ ->
            invokeWYZIESubs(res.imdbId, res.season, res.episode, subtitleCallback)
        },
        Provider("SubtitleAPI", "SubtitleAPI (Subtitles)", ProviderKind.SUBTITLE) { res, subtitleCallback, _, _, _ ->
            invokeSubtitleAPI(res.imdbId, res.season, res.episode, subtitleCallback)
        },
        Provider("CineVood", "CineVood (Movies Only)") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeCineVood(res.imdbId, subtitleCallback, callback)
        },
        Provider("Filmyfiy", "Filmyfiy (Movies Only)") { res, sub, cb, _, _ ->
            if (!res.isAnime && res.season == null) invokeFilmyfiy(res.title, sub, cb)
        },
        Provider("2Embed", "2Embed") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invoke2embed(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("DooFlix", "DooFlix") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeDooflix(res.id, res.season, res.episode, callback)
        },
        Provider("Xpass", "Xpass") { res, _, callback, _, _ ->
            if (!res.isAnime) invokeXpass(res.id, res.season, res.episode, callback, )
        },
        Provider("Dudefilms", "Dudefilms") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeDudefilms(res.imdbId, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("Zinkmovies", "Zinkmovies") { res, subtitleCallback, callback, _, _ ->
            if (!res.isAnime) invokeZinkmovies(res.title, res.year, res.season, res.episode, subtitleCallback, callback)
        },
        Provider("Peachify", "Peachify") { res, _, callback, _, _ ->
            if (!res.isAnime) invokePeachify(res.id, res.season, res.episode, callback)
        }
    )
}

val DEFAULT_TOP_TIER_PROVIDERS = setOf(
    "vidlink",
    "HexaSU",
    "autoembed",
    "vidfast",
    "VidEasy"
)

fun getDefaultDisabledProviderIds(): Set<String> =
    buildProviders().map { it.id }.filterNot { it in DEFAULT_TOP_TIER_PROVIDERS }.toSet()

fun buildProviders(): List<Provider> = providers

const val PREFS_TOP_TIER_INITIALIZED = "streamplay_top_tier_v5_initialized"

/**
 * Ensures clean installs enable DEFAULT_TOP_TIER_PROVIDERS (VidLink > HexaSU > AutoEmbed > VidFast > VidEasy)
 * with all secondary sources disabled by default, and seamlessly migrates upgrading users so newly
 * promoted primary sources are enabled while SuperStream is completely purged and disabled.
 */
fun getOrInitializeDisabledProviders(sharedPref: SharedPreferences?): Set<String> {
    if (sharedPref == null) return getDefaultDisabledProviderIds()

    val isTopTierInitialized = sharedPref.getBoolean(PREFS_TOP_TIER_INITIALIZED, false)
    if (!isTopTierInitialized) {
        val defaultDisabled = getDefaultDisabledProviderIds()
        val existingDisabled = sharedPref.getStringSet("disabled_providers", null)
        val finalDisabled = if (existingDisabled.isNullOrEmpty()) {
            defaultDisabled
        } else {
            ((existingDisabled + defaultDisabled) - DEFAULT_TOP_TIER_PROVIDERS) + "superstream"
        }
        sharedPref.edit {
            putStringSet("disabled_providers", finalDisabled)
            putBoolean("streamplay_top5_defaults_initialized", true)
            putBoolean("streamplay_top_tier_v2_initialized", true)
            putBoolean("streamplay_top_tier_v4_initialized", true)
            putBoolean(PREFS_TOP_TIER_INITIALIZED, true)
        }
        Log.d("StreamPlay", "🎯 Initialized top-tier provider defaults: ${DEFAULT_TOP_TIER_PROVIDERS.size} active, ${finalDisabled.size} disabled")
        return finalDisabled
    }
    return sharedPref.getStringSet("disabled_providers", null) ?: getDefaultDisabledProviderIds()
}
