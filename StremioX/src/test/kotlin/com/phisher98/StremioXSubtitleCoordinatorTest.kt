package com.phisher98

import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.MediaLookupSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioXSubtitleCoordinatorTest {

    @Test
    fun kitsuAndPrefixedAnimeIdsNeverBecomeVerifiedSeasonNumbers() {
        val kitsuSpec = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            title = "Anime Cour",
            mediaType = "anime",
            isAnime = true,
            streamId = "kitsu:12345:5",
        )
        assertTrue("Expected Episode spec for kitsu:12345:5", kitsuSpec is MediaLookupSpec.Episode)
        val kitsuEp = kitsuSpec as MediaLookupSpec.Episode
        assertTrue(
            "kitsu:12345:5 must produce UnverifiedAnimeCour, not VerifiedSeasonEpisode(12345, 5), got ${kitsuEp.coordinate}",
            kitsuEp.coordinate is EpisodeCoordinate.UnverifiedAnimeCour,
        )
        assertEquals(5, (kitsuEp.coordinate as EpisodeCoordinate.UnverifiedAnimeCour).episodeInCour)

        val kitsuSeriesTypeSpec = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            title = "Anime As Series",
            mediaType = "series",
            isAnime = false,
            streamId = "kitsu:12345:5",
        ) as MediaLookupSpec.Episode
        assertTrue(kitsuSeriesTypeSpec.isAnime)
        assertEquals(
            EpisodeCoordinate.UnverifiedAnimeCour(listOf("Anime As Series"), 5),
            kitsuSeriesTypeSpec.coordinate,
        )

        for (prefix in listOf("anilist", "mal", "anidb")) {
            val spec = StremioXSubtitleCoordinator.buildLookupSpec(
                imdbId = null,
                title = "Prefixed Anime",
                mediaType = "anime",
                streamId = "$prefix:9988:7",
            ) as MediaLookupSpec.Episode
            assertEquals(
                "Prefix $prefix must yield UnverifiedAnimeCour for episode 7",
                EpisodeCoordinate.UnverifiedAnimeCour(listOf("Prefixed Anime"), 7),
                spec.coordinate,
            )
        }
    }

    @Test
    fun twoPartPrefixedCatalogIdNeverTurnsEntityIdIntoEpisode() {
        val kitsuBare = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            title = "Bare Kitsu Show",
            mediaType = "anime",
            isAnime = true,
            streamId = "kitsu:12345",
        ) as MediaLookupSpec.Episode
        assertEquals(
            EpisodeCoordinate.UnverifiedAnimeCour(listOf("Bare Kitsu Show"), 1),
            kitsuBare.coordinate,
        )

        val tmdbBareSeries = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            tmdbId = 94605,
            title = "TMDB Show",
            mediaType = "series",
            streamId = "tmdb:94605",
        ) as MediaLookupSpec.Episode
        assertEquals(
            EpisodeCoordinate.UnverifiedEpisodeOnly(1),
            tmdbBareSeries.coordinate,
        )
    }

    @Test
    fun fourPartPrefixedSeriesIdAndThreePartImdbIdStillParseSeasonAndEpisode() {
        val tmdbEpisode = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            tmdbId = 94605,
            title = "TMDB Series",
            mediaType = "series",
            streamId = "tmdb:94605:2:7",
        ) as MediaLookupSpec.Episode
        assertEquals(
            EpisodeCoordinate.VerifiedSeasonEpisode(2, 7),
            tmdbEpisode.coordinate,
        )

        val imdbEpisode = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = "tt1234567",
            title = "IMDb Series",
            mediaType = "series",
            streamId = "tt1234567:2:7",
        ) as MediaLookupSpec.Episode
        assertEquals(
            EpisodeCoordinate.VerifiedSeasonEpisode(2, 7),
            imdbEpisode.coordinate,
        )
    }

    @Test
    fun resolveProviderDispatchPlanPropagatesParsedStreamIdCoordinatesAndGuardsEpisodeOnlySpecs() {
        val streamIdOnlyEpisode = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = null,
            title = "ID-Only Episode",
            mediaType = "series",
            streamId = "tt1234567:2:5",
        )
        val plan = StremioXSubtitleCoordinator.resolveProviderDispatchPlan(
            lookupSpec = streamIdOnlyEpisode,
            imdbId = null,
            season = null,
            episode = null,
        )
        assertEquals("tt1234567", plan.imdbId)
        assertEquals(2, plan.season)
        assertEquals(5, plan.episode)
        assertTrue(plan.invokeImdbProviders)

        val episodeOnlySeries = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = "tt1234567",
            title = "Episode-Only Series",
            mediaType = "series",
            streamId = "tt1234567:5",
        )
        val episodeOnlyPlan = StremioXSubtitleCoordinator.resolveProviderDispatchPlan(
            lookupSpec = episodeOnlySeries,
            imdbId = "tt1234567",
            season = null,
            episode = null,
        )
        org.junit.Assert.assertFalse(
            "Episode without season must NOT fall back to OpenSubtitles/Watchsomuch movie branch",
            episodeOnlyPlan.invokeImdbProviders,
        )

        val movieSpec = StremioXSubtitleCoordinator.buildLookupSpec(
            imdbId = "tt7654321",
            title = "Actual Movie",
            mediaType = "movie",
        )
        val moviePlan = StremioXSubtitleCoordinator.resolveProviderDispatchPlan(
            lookupSpec = movieSpec,
            imdbId = "tt7654321",
            season = null,
            episode = null,
        )
        assertTrue(moviePlan.invokeImdbProviders)
        assertEquals(null, moviePlan.season)
        assertEquals(null, moviePlan.episode)
    }
}
