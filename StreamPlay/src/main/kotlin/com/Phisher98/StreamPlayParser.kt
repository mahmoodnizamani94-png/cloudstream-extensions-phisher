package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName

//Anichi

data class AnichiRoot(
    val data: AnichiData,
)

data class AnichiData(
    val shows: AnichiShows,
)

data class AnichiShows(
    val pageInfo: PageInfo,
    val edges: List<Edge>,
)

data class PageInfo(
    val total: Long,
)

data class Edge(
    @param:JsonProperty("_id")
    val id: String,
    val name: String,
    val englishName: String,
    val nativeName: String,
)

//Anichi Ep Parser

data class AnichiEP(
    val data: AnichiEPData,
)

data class AnichiEPData(
    val episode: AnichiEpisode,
)

data class AnichiEpisode(
    val sourceUrls: List<SourceUrl>,
)

data class SourceUrl(
    val sourceUrl: String,
    val sourceName: String,
    val downloads: AnichiDownloads?,
)

data class AnichiDownloads(
    val sourceName: String,
    val downloadUrl: String,
)

data class AniIds(var id: Int? = null, var idMal: Int? = null)

data class TmdbDate(
    val today: String,
    val nextWeek: String,
    val lastWeekStart: String,
    val monthStart: String
)
data class AniMedia(
    @param:JsonProperty("id") var id: Int? = null,
    @param:JsonProperty("idMal") var idMal: Int? = null
)

data class AniPage(
    @param:JsonProperty("media") var media: ArrayList<AniMedia> = arrayListOf()
)

data class AniData(
    @param:JsonProperty("Page") var Page: AniPage? = null,
    @param:JsonProperty("media") var media: ArrayList<AniMedia>? = null
)

data class AniSearch(
    @param:JsonProperty("data") var data: AniData? = null
)


//WyZIESUBAPI

data class WYZIESubtitle(
    @JsonProperty("url")
    val url: String,
    @JsonProperty("display")
    val display: String? = null,
    @JsonProperty("language")
    val language: String? = null,
)

//anime animepahe parser

data class animepahe(
    val total: Long,
    @param:JsonProperty("per_page")
    val perPage: Long,
    @param:JsonProperty("current_page")
    val currentPage: Long,
    @param:JsonProperty("last_page")
    val lastPage: Long,
    @param:JsonProperty("next_page_url")
    val nextPageUrl: Any?,
    @param:JsonProperty("prev_page_url")
    val prevPageUrl: Any?,
    val from: Long,
    val to: Long,
    val data: List<Daum>,
)

data class Daum(
    val id: Long,
    @param:JsonProperty("anime_id")
    val animeId: Long,
    val episode: Int,
    val episode2: Long,
    val edition: String,
    val title: String,
    val snapshot: String,
    val disc: String,
    val audio: String,
    val duration: String,
    val session: String,
    val filler: Long,
    @param:JsonProperty("created_at")
    val createdAt: String,
)


data class MALSyncSites(
    @param:JsonProperty("AniXL") val AniXL: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("Zoro") val zoro: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("9anime") val nineAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("animepahe") val animepahe: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("KickAssAnime") val KickAssAnime: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
    @param:JsonProperty("AnimeKAI") val AnimeKAI: HashMap<String?, HashMap<String, String?>>? = hashMapOf(),
)
data class MALSyncResponses(
    @param:JsonProperty("Sites") val sites: MALSyncSites? = null,
)
data class SubtitlesAPI(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long,
)

data class Subtitle(
    val id: String,
    val url: String,
    @param:JsonProperty("SubEncoding")
    val subEncoding: String,
    val lang: String,
    val m: String,
    val g: String,
)
data class EpisoderesponseKAA(
    val slug: String,
    val title: String,
    val duration_ms: Long,
    val episode_number: Number,
    val episode_string: String,
    val thumbnail: ThumbnailKAA
)

data class ThumbnailKAA(
    val formats: List<String>,
    val sm: String,
    val aspectRatio: Double,
    val hq: String
)


data class ServersResKAA(
    val servers: List<ServerKAA>,

    )

data class ServerKAA(
    val name: String,
    val shortName: String,
    val src: String,
)


data class EncryptedKAA(
    val data: String,
)


data class m3u8KAA(
    val hls: String,
    val subtitles: List<SubtitleKAA>,
    val key: String,
)

data class SubtitleKAA(
    val language: String,
    val name: String,
    val src: String,
)

data class AnichiStream(
    @param:JsonProperty("format") val format: String? = null,
    @param:JsonProperty("audio_lang") val audio_lang: String? = null,
    @param:JsonProperty("hardsub_lang") val hardsub_lang: String? = null,
    @param:JsonProperty("url") val url: String? = null,
)

data class PortData(
    @param:JsonProperty("streams") val streams: ArrayList<AnichiStream>? = arrayListOf(),
)

data class AnichiSubtitles(
    @param:JsonProperty("lang") val lang: String?,
    @param:JsonProperty("label") val label: String?,
    @param:JsonProperty("src") val src: String?,
)

data class AnichiLinks(
    @param:JsonProperty("link") val link: String,
    @param:JsonProperty("hls") val hls: Boolean? = null,
    @param:JsonProperty("resolutionStr") val resolutionStr: String,
    @param:JsonProperty("src") val src: String? = null,
    @param:JsonProperty("headers") val headers: Headers? = null,
    @param:JsonProperty("portData") val portData: PortData? = null,
    @param:JsonProperty("subtitles") val subtitles: ArrayList<AnichiSubtitles>? = arrayListOf(),
)

data class Headers(
    @param:JsonProperty("Referer") val referer: String? = null,
    @param:JsonProperty("Origin") val origin: String? = null,
    @param:JsonProperty("user-agent") val userAgent: String? = null,
)


data class AnichiVideoApiResponse(@param:JsonProperty("links") val links: List<AnichiLinks>)

//Domains Parser

data class DomainsParser(
    val moviesdrive: String,
    @param:JsonProperty("HDHUB4u")
    val hdhub4u: String,
    @param:JsonProperty("4khdhub")
    val n4khdhub: String,
    @param:JsonProperty("MultiMovies")
    val multiMovies: String,
    val bollyflix: String,
    @param:JsonProperty("UHDMovies")
    val uhdmovies: String,
    val moviesmod: String,
    val topMovies: String,
    val hdmovie2: String,
    val vegamovies: String,
    val rogmovies: String,
    val luxmovies: String,
    val movierulzhd: String,
    val extramovies: String,
    val banglaplex: String,
    val toonstream: String,
    val telugumv: String,
    val filmycab: String,
    val tellyhd: String,
    val filmyfiy: String,
    val hindmoviez: String,
    val tamilblasters: String,
    val hubcloud: String,
    val movienestbd: String,
    val movies4u: String,
    val cinevood: String,
    val dudefilms: String,
    val fibwatch: String,
    val fibtoon: String,
    val fibdrama: String,
    val xprimehub: String,
    val m4ufree: String,
    val zinkmovies: String,
)


// CinemetaRes

data class CinemetaRes(
    val meta: Meta? = null
) {

    data class Meta(
        val id: String? = null,
        val type: String? = null,
        val name: String? = null,

        @param:JsonProperty("imdb_id")
        val imdbId: String? = null,

        val slug: String? = null,

        val director: String? = null,
        val writer: String? = null,

        val description: String? = null,
        val year: String? = null,
        val releaseInfo: String? = null,
        val released: String? = null,
        val runtime: String? = null,
        val status: String? = null,
        val country: String? = null,
        val imdbRating: String? = null,
        val genres: List<String>? = null,
        val poster: String? = null,
        @param:JsonProperty("_rawPosterUrl")
        val rawPosterUrl: String? = null,

        val background: String? = null,
        val logo: String? = null,

        val videos: List<Video>? = null,
        val trailers: List<Trailer>? = null,
        val trailerStreams: List<TrailerStream>? = null,
        val links: List<Link>? = null,

        val behaviorHints: BehaviorHints? = null,

        @param:JsonProperty("app_extras")
        val appExtras: AppExtras? = null,
    ) {

        data class BehaviorHints(
            val defaultVideoId: Any? = null,
            val hasScheduledVideos: Boolean? = null
        )

        data class Link(
            val name: String? = null,
            val category: String? = null,
            val url: String? = null
        )

        data class Trailer(
            val source: String? = null,
            val type: String? = null,
            val name: String? = null
        )

        data class TrailerStream(
            val ytId: String? = null,
            val title: String? = null
        )

        data class Video(
            val id: String? = null,
            val title: String? = null,
            val season: Int? = null,
            val episode: Int? = null,
            val thumbnail: String? = null,
            val overview: String? = null,
            val released: String? = null,
            val available: Boolean? = null,
            val runtime: String? = null
        )

        data class AppExtras(
            val cast: List<Cast>? = null,
            val directors: List<Any?>? = null,
            val writers: List<Any?>? = null,
            val seasonPosters: List<String?>? = null,
            val certification: String? = null
        )

        data class Cast(
            val name: String? = null,
            val character: String? = null,
            val photo: String? = null
        )
    }
}

//Vidlink
data class VidlinkResponse(
    @SerializedName("stream") val stream: VidlinkStream? = null
)

data class VidlinkStream(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("playlist") val playlist: String? = null,
    @SerializedName("qualities") val qualities: Map<String, VidlinkQuality>? = null,
    @SerializedName("captions") val captions: List<VidlinkCaption>? = null
)

data class VidlinkQuality(
    @SerializedName("type") val type: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("codecName") val codecName: String? = null,
    @SerializedName("size") val size: String? = null,
    @SerializedName("resourceId") val resourceId: String? = null,
    @SerializedName("linkType") val linkType: Int? = null,
    @SerializedName("requiresProxy") val requiresProxy: Boolean? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null
)

data class VidlinkCaption(
    @SerializedName("id") val id: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("language") val language: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("hasCorsRestrictions") val hasCorsRestrictions: Boolean? = null
)

class SearchData : ArrayList<SearchData.SearchDataItem>() {
    data class SearchDataItem(
        val audio_languages: String,
        val exact_match: Int,
        val id: Int,
        val path: String,
        val poster: String,
        val qualities: List<String>,
        val release_year: String,
        val title: String,
        val tmdb_id: Int,
        val type: String
    )
}

//Dooflix

data class Dooflix(
    val id: Long,
    val links: List<DooflixLink>,
)

data class DooflixLink(
    val id: Long,
    @param:JsonProperty("movie_id")
    val movieId: Long,
    val host: String,
    val url: String,
    val quality: String,
    val size: String,
    val order: Long,
    @param:JsonProperty("created_at")
    val createdAt: String,
    @param:JsonProperty("updated_at")
    val updatedAt: String,
)

//VidSrcSU (hexa protocol)

data class VidSrcSUEn(
    val status: Long? = null,
    val result: VidSrcSUEnResult? = null,
)

data class VidSrcSUEnResult(
    val token: String? = null,
    val expires: String? = null,
)

data class VidSrcSUResponse(
    val status: Int? = null,
    val result: VidSrcSUResult? = null
)

data class VidSrcSUResult(
    val sources: List<VidSrcSUSource>? = null,
    val tracks: List<VidSrcSUTrack>? = null,
    val skipTime: Any? = null
)

data class VidSrcSUTrack(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null
)

data class VidSrcSUSource(
    val server: String? = null,
    val url: String? = null
)

//VidFast

data class VidFastStage1Response(
    val status: Int? = null,
    val result: VidFastStage1? = null,
)

data class VidFastStage1(
    val stage1: String? = null,
    val token: String? = null,
)

data class VidFastStage2Response(
    val status: Int? = null,
    val result: VidFastStage2? = null,
)

data class VidFastStage2(
    val servers: String? = null,
    val stream: String? = null,
    val token: String? = null,
)

//MeowTV

data class MeowTvTicketResponse(
    val ticket: String? = null,
    val exp: Long? = null,
)

data class MeowTvPayload(
    val n: String? = null,
    val d: String? = null,
)

data class MeowTvDecResponse(
    val status: Int? = null,
    val result: MeowTvDecResult? = null,
)

data class MeowTvDecResult(
    val language: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
)

//MegaPlaybuzz HiAnime

data class HiAnimeSourcesResponse(
    val sources: HiAnimeSources?,
    val tracks: List<HiAnimeTrack>?,
    val t: Long?,
    val server: Long?,
)

data class HiAnimeSources(
    val file: String?,
)

data class HiAnimeTrack(
    val file: String?,
    val label: String?,
    val kind: String?,
    val default: Boolean?
)

//ReAnime

data class ReAnime(
    val success: Boolean,
    val servers: List<ReAnimeServer>,
)

data class ReAnimeServer(
    @param:JsonProperty($$"$id")
    val id: String,
    val serverName: String,
    val dataLink: String,
    val dataType: String,
    @param:JsonProperty("continue")
    val continue_field: Boolean,
    val softsub: Boolean,
)

data class ResolvedReAnime(
    val result: ResolvedReAnimeResult,
)

data class ResolvedReAnimeResult(
    val token: String,
    val state: ResolvedReAnimeState,
)

data class ResolvedReAnimeState(
    val token: String,
)

data class ReAnimeStream(
    val result: ReAnimeStreamResult,
)

data class ReAnimeStreamResult(
    val stream: String,
)

// Anikage

data class AnikageSearchResponse(
    val results: List<AnikageSearchResult>? = null
)

data class AnikageSearchResult(
    val id: Int? = null,
    val anilistId: Int? = null,
    val slug: String? = null
)

data class AnikageEpisodesResponse(
    val episodes: List<AnikageEpisode>? = null
)

data class AnikageEpisode(
    val number: Int? = null,
    val episode: Int? = null
)

data class AnikageServersResponse(
    val servers: List<AnikageServer>? = null
)

data class AnikageServer(
    val id: String? = null,
    val name: String? = null
)

data class AnikageSourcesResponse(
    val sources: List<AnikageSource>? = null,
    val subtitles: List<AnikageSubtitle>? = null,
    val embeds: List<AnikageEmbed>? = null
)

data class AnikageSource(
    val url: String? = null,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
    val type: String? = null
)

data class AnikageSubtitle(
    val file: String? = null,
    val label: String? = null
)

data class AnikageEmbed(
    val url: String? = null
)