package com.PublicSportsIPTV

import com.fasterxml.jackson.annotation.JsonProperty

data class Root(
    @param:JsonProperty("Author")
    val author: String,
    val name: String,
    @param:JsonProperty("last_updated")
    val lastUpdated: String,
    val headers: Headers,
    @param:JsonProperty("total_matches")
    val totalMatches: Long,
    @param:JsonProperty("live_matches")
    val liveMatches: Long,
    @param:JsonProperty("upcoming_matches")
    val upcomingMatches: Long,
    val matches: List<Match>,
)

data class Headers(
    @param:JsonProperty("User-Agent")
    val userAgent: String,
    @param:JsonProperty("Referer")
    val referer: String,
)

data class Match(
    val category: String,
    val title: String,
    val tournament: String,
    @param:JsonProperty("match_id")
    val matchId: Long,
    val status: String,
    val streamingStatus: String,
    val startTime: String,
    val startDate: String,
    val image: String,
    @param:JsonProperty("image_cdn")
    val imageCdn: ImageCdn,
    val teams: List<Team>,
    val language: String,
    @param:JsonProperty("adfree_stream")
    val adfreeStream: String?,
    @param:JsonProperty("dai_stream")
    val daiStream: String?,
    @param:JsonProperty("STREAMING_CDN")
    val streamingCdn: StreamingCdn,
)

data class ImageCdn(
    @param:JsonProperty("TATAPLAY")
    val tataplay: String,
    @param:JsonProperty("APP")
    val app: String,
    @param:JsonProperty("PLAYBACK")
    val playback: String?,
    @param:JsonProperty("LOGO")
    val logo: String,
    @param:JsonProperty("SPORTS")
    val sports: String,
    @param:JsonProperty("BG_IMAGE")
    val bgImage: String,
    @param:JsonProperty("SPORT_BY_IMAGE")
    val sportByImage: String,
    @param:JsonProperty("CLOUDFARE")
    val cloudfare: String?,
)

data class Team(
    val name: String,
    val shortName: String,
    val flag: Flag,
    val isWinner: Boolean?,
    val color: String,
    val cricketScore: List<CricketScore>?,
    val kabaddiScore: Any?,
    val footballScore: Any?,
    val basketBallScore: Any?,
    val hockeyScore: Any?,
    val status: Status?,
)

data class Flag(
    val src: String,
)

data class CricketScore(
    val runs: Long,
    val overs: String,
    val balls: String,
    val status: String,
    val wickets: Long,
)

data class Status(
    val cricket: Cricket,
)

data class Cricket(
    val isBatting: Boolean,
)

data class StreamingCdn(
    val language: String,
    @param:JsonProperty("Primary_Playback_URL")
    val primaryPlaybackUrl: String?,
    @param:JsonProperty("fancode_cdn")
    val fancodeCdn: String?,
    @param:JsonProperty("dai_google_cdn")
    val daiGoogleCdn: String?,
    @param:JsonProperty("cloudfront_cdn")
    val cloudfrontCdn: String?,
    @param:JsonProperty("sony_cdn")
    val sonyCdn: String?,
)


data class LoadURL(
    @param:JsonProperty("Primary_Playback_URL")
    val primaryPlaybackUrl: String?,
    @param:JsonProperty("fancode_cdn")
    val fancodeCdn: String?,
    @param:JsonProperty("dai_google_cdn")
    val daiGoogleCdn: String?,
    @param:JsonProperty("cloudfront_cdn")
    val cloudfrontCdn: String?,
    @param:JsonProperty("title")
    val title: String?,
    @param:JsonProperty("tournament")
    val tournament: String?,
    @param:JsonProperty("poster")
    val poster: String?,
)