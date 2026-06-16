package com.tokusatsu.ultimate

import com.fasterxml.jackson.annotation.JsonProperty

// Data classes for parsing JSON responses from tokusatsu sources
data class TokusatsuSearchResult(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("original_title") val originalTitle: String? = null,
    @param:JsonProperty("poster") val poster: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("description") val description: String? = null
)

data class TokusatsuDetail(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("original_title") val originalTitle: String? = null,
    @param:JsonProperty("poster") val poster: String? = null,
    @param:JsonProperty("banner") val banner: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("year") val year: Int? = null,
    @param:JsonProperty("description") val description: String? = null,
    @param:JsonProperty("status") val status: String? = null,
    @param:JsonProperty("genres") val genres: List<String>? = null,
    @param:JsonProperty("episodes") val episodes: List<TokusatsuEpisode>? = null,
    @param:JsonProperty("rating") val rating: String? = null
)

data class TokusatsuEpisode(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("title") val title: String? = null,
    @param:JsonProperty("episode_number") val episodeNumber: Int? = null,
    @param:JsonProperty("season_number") val seasonNumber: Int? = null,
    @param:JsonProperty("description") val description: String? = null,
    @param:JsonProperty("thumbnail") val thumbnail: String? = null,
    @param:JsonProperty("air_date") val airDate: String? = null
)

data class TokusatsuLinks(
    @param:JsonProperty("id") val id: String? = null,
    @param:JsonProperty("links") val links: List<TokusatsuVideoLink>? = null,
    @param:JsonProperty("subtitles") val subtitles: List<TokusatsuSubtitle>? = null
)

data class TokusatsuVideoLink(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("quality") val quality: String? = null,
    @param:JsonProperty("type") val type: String? = null,
    @param:JsonProperty("is_m3u8") val isM3u8: Boolean? = null
)

data class TokusatsuSubtitle(
    @param:JsonProperty("url") val url: String? = null,
    @param:JsonProperty("language") val language: String? = null,
    @param:JsonProperty("format") val format: String? = null
)