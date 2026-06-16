package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.TvType


data class LoadData(
    val name: String,
    val posterurl: String,
    val type: TvType,
    val id: String,
    val userid: String,
)

//JellyFin


data class Authparser(
    @param:JsonProperty("AccessToken")
    val accessToken: String,
    @param:JsonProperty("ServerId")
    val serverId: String,
    @param:JsonProperty("User")
    val user: User,
)

data class User(
    @param:JsonProperty("Name")
    val name: String,
    @param:JsonProperty("ServerId")
    val serverId: String,
    @param:JsonProperty("Id")
    val id: String,
)

//Homepage

data class Home(
    @param:JsonProperty("Items")
    val items: List<HomeItem>,
    @param:JsonProperty("TotalRecordCount")
    val totalRecordCount: Long,
    @param:JsonProperty("StartIndex")
    val startIndex: Long
)

data class HomeItem(
    @param:JsonProperty("Name")
    val name: String,
    @param:JsonProperty("Id")
    val id: String,
    @param:JsonProperty("IsFolder")
    val isFolder: Boolean = false,
    @param:JsonProperty("Type")
    val type: String? = null,
    @param:JsonProperty("ProductionYear")
    val productionYear: Int? = null,
    @param:JsonProperty("PremiereDate")
    val premiereDate: String? = null,
    @param:JsonProperty("ImageTags")
    val imageTags: Map<String, String>? = null,
)

//LoadURL

data class LoadURL(
    @param:JsonProperty("MediaSources")
    val mediaSources: List<MediaSource>,
    @param:JsonProperty("PlaySessionId")
    val playSessionId: String,
)

data class MediaSource(
    @param:JsonProperty("TranscodingUrl")
    val transcodingUrl: String? = null,
    @param:JsonProperty("Path")
    val path: String? = null,
    @param:JsonProperty("SupportsDirectPlay")
    val supportsDirectPlay: Boolean = false,
    @param:JsonProperty("Protocol")
    val protocol: String = "",
    @param:JsonProperty("SupportsTranscoding")
    val supportsTranscoding: Boolean = false,
    @param:JsonProperty("SupportsDirectStream")
    val supportsDirectStream: Boolean = false,
    @param:JsonProperty("Id")
    val id: String? = null,
    @param:JsonProperty("Container")
    val container: String? = null,
    @param:JsonProperty("DefaultAudioStreamIndex")
    val defaultAudioStreamIndex: Int? = null
)

data class SeriesInfo(
    @param:JsonProperty("Id") val id: String,
    @param:JsonProperty("ParentId") val parentId: String?,
    @param:JsonProperty("Name") val name: String,
    @param:JsonProperty("Overview") val overview: String?,
    @param:JsonProperty("ProductionYear") val productionYear: Int?,
    @param:JsonProperty("ImageTags") val imageTags: ImageTags?,
    @param:JsonProperty("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double?,
    @param:JsonProperty("People") val people: List<Person> = emptyList()
)

data class ImageTags(
    @param:JsonProperty("Primary")
    val primary: String?
)

data class Person(
    @param:JsonProperty("Name")
    val name: String,

    @param:JsonProperty("Role")
    val role: String,

    @param:JsonProperty("Type")
    val type: String
)

data class SeasonItem(
    @param:JsonProperty("Id") val id: String,
    @param:JsonProperty("Name") val name: String
)

data class SeasonResponse(
    @param:JsonProperty("Items") val items: List<SeasonItem> = emptyList()
)


//Episodes

data class EpisodeJson(
    @param:JsonProperty("Items")
    val items: List<EpisodeItem>? = emptyList()
)

data class EpisodeItem(
    @param:JsonProperty("Id")
    val id: String,
    @param:JsonProperty("Name")
    val name: String,
    @param:JsonProperty("IndexNumber")
    val indexNumber: Int? = null,
    @param:JsonProperty("SeasonName")
    val seasonName: String? = null,
    @param:JsonProperty("ImageTags")
    val imageTags: EpisodeImageTags? = null
)

data class EpisodeImageTags(
    @param:JsonProperty("Primary")
    val primary: String?
)

//Metadata
data class MovieMetadata(
    @param:JsonProperty("People")
    val people: List<MovieMetadataPerson> = emptyList(),

    @param:JsonProperty("Name")
    val name: String,

    @param:JsonProperty("OriginalTitle")
    val originalTitle: String,

    @param:JsonProperty("Overview")
    val overview: String,

    @param:JsonProperty("ProductionYear")
    val productionYear: Long,

    @param:JsonProperty("ProviderIds")
    val providerIds: ProviderIds,

    @param:JsonProperty("ExternalUrls")
    val externalUrls: List<ExternalUrl>,

    @param:JsonProperty("CommunityRating")
    val communityRating: Double,

    @param:JsonProperty("Genres")
    val genres: List<String>,

    @param:JsonProperty("Id")
    val id: String,

    @param:JsonProperty("ImageTags")
    val imageTags: MovieMetadataImageTags,

    @param:JsonProperty("RemoteTrailers")
    val remoteTrailers: List<RemoteTrailer>
)

data class ProviderIds(
    @param:JsonProperty("Tmdb")
    val tmdb: String? = null,

    @param:JsonProperty("Imdb")
    val imdb: String? = null,

    @param:JsonProperty("Tvdb")
    val tvdb: String? = null
)

data class ExternalUrl(
    @param:JsonProperty("Name")
    val name: String,

    @param:JsonProperty("Url")
    val url: String
)

data class MovieMetadataImageTags(
    @param:JsonProperty("Primary")
    val primary: String? = null
)

data class RemoteTrailer(
    @param:JsonProperty("Url")
    val url: String
)

data class MovieMetadataPerson(
    @param:JsonProperty("Name")
    val name: String,

    @param:JsonProperty("Id")
    val id: String,

    @param:JsonProperty("Role")
    val role: String,

    @param:JsonProperty("Type")
    val type: String,

    @param:JsonProperty("PrimaryImageTag")
    val primaryImageTag: String? = null,

    @param:JsonProperty("ImageBlurHashes")
    val imageBlurHashes: ImageBlurHashesWrapper? = null
)

data class ImageBlurHashesWrapper(
    @param:JsonProperty("Primary")
    val primary: Map<String, String> = emptyMap()
)

//Search

data class SearchResult(
    @param:JsonProperty("Items")
    val items: List<SearchItem>,

    @param:JsonProperty("TotalRecordCount")
    val totalRecordCount: Int,

    @param:JsonProperty("StartIndex")
    val startIndex: Int
)

data class SearchItem(
    @param:JsonProperty("Name")
    val name: String,

    @param:JsonProperty("Id")
    val id: String,

    @param:JsonProperty("ServerId")
    val serverId: String? = null,

    @param:JsonProperty("Type")
    val type: String? = null,

    @param:JsonProperty("ProductionYear")
    val productionYear: Int? = null,

    @param:JsonProperty("PremiereDate")
    val premiereDate: String? = null,

    @param:JsonProperty("ImageTags")
    val imageTags: SearchImageTags? = null,

    @param:JsonProperty("BackdropImageTags")
    val backdropImageTags: List<String>? = null,

    @param:JsonProperty("HasSubtitles")
    val hasSubtitles: Boolean? = null,

    @param:JsonProperty("RunTimeTicks")
    val runTimeTicks: Long? = null,

    @param:JsonProperty("MediaType")
    val mediaType: String? = null,

    @param:JsonProperty("IsFolder")
    val isFolder: Boolean = false,

    @param:JsonProperty("Container")
    val container: String? = null,

    @param:JsonProperty("CommunityRating")
    val communityRating: Double? = null,

    @param:JsonProperty("OfficialRating")
    val officialRating: String? = null,

    @param:JsonProperty("UserData")
    val userData: UserData? = null
)

data class SearchImageTags(
    @param:JsonProperty("Primary")
    val primary: String? = null,

    @param:JsonProperty("Logo")
    val logo: String? = null
)

data class UserData(
    @param:JsonProperty("PlaybackPositionTicks")
    val playbackPositionTicks: Long? = null,

    @param:JsonProperty("PlayCount")
    val playCount: Int? = null,

    @param:JsonProperty("IsFavorite")
    val isFavorite: Boolean? = null,

    @param:JsonProperty("Played")
    val played: Boolean? = null
)