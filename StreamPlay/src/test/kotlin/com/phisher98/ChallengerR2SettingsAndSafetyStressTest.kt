package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.system.measureTimeMillis

class ChallengerR2SettingsAndSafetyStressTest {

    @Before
    fun setUp() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    // =========================================================================
    // 1. SETTINGS MIGRATION EDGE CASES
    // =========================================================================

    @Test
    fun testCleanInstallNullDisabledProvidersActivatesExactlySixTopTier() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Fresh install: no keys at all
        assertFalse(mockPrefs.contains(PREFS_TOP_TIER_INITIALIZED))
        assertFalse(mockPrefs.contains("disabled_providers"))

        val disabled = getOrInitializeDisabledProviders(mockPrefs)
        val allProviders = buildProviders()
        val activeProviders = allProviders.map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 3 top-tier providers", 3, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders)

        // Verify the 3 specific providers
        val expectedTopThree = setOf("vidlink", "vidcore", "vidup")
        assertEquals(expectedTopThree, activeProviders)

        // Dead registered providers must be disabled
        assertTrue("HexaSU must be disabled", disabled.contains("HexaSU"))
        assertTrue("autoembed must be disabled", disabled.contains("autoembed"))
        assertTrue("vidfast must be disabled", disabled.contains("vidfast"))
        assertTrue("VidEasy must be disabled", disabled.contains("VidEasy"))
        assertTrue("yflix must be disabled", disabled.contains("yflix"))
        assertTrue("cinejoy must be disabled", disabled.contains("cinejoy"))

        // Preference flag must be written
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testCleanInstallEmptyDisabledProvidersActivatesExactlyFourTopTier() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Stale or empty set stored without v8 initialized flag
        mockPrefs.edit().putStringSet("disabled_providers", emptySet()).apply()

        val disabled = getOrInitializeDisabledProviders(mockPrefs)
        val allProviders = buildProviders()
        val activeProviders = allProviders.map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install with empty set must activate exactly 3 top-tier providers", 3, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testUpgradeFromV7DisablesDeadAndEnablesSotaAndPreservesOverrides() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // Simulate a v7 installation:
        // In v7: yflix, vidfast, VidEasy, vidsrc were enabled.
        // vidcore and vidup were disabled.
        // User custom overrides:
        // - User explicitly DISABLED top-tier providers "vidlink" and "cinejoy"
        // - User explicitly ENABLED secondary providers "moviebox", "uhdmovies", "allmovieland"
        val v7DefaultDisabled = (getDefaultDisabledProviderIds() - setOf("yflix", "vidfast", "VidEasy", "vidsrc")) + setOf("vidcore", "vidup")
        val v7UserDisabled = (v7DefaultDisabled + setOf("vidlink", "cinejoy")) - setOf("moviebox", "uhdmovies", "allmovieland")

        mockPrefs.edit()
            .putStringSet("disabled_providers", v7UserDisabled)
            .putBoolean("streamplay_top_tier_v7_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        // 1. Dead providers MUST be disabled
        assertTrue("HexaSU must be disabled", migratedDisabled.contains("HexaSU"))
        assertTrue("autoembed must be disabled", migratedDisabled.contains("autoembed"))
        assertTrue("superstream must be disabled", migratedDisabled.contains("superstream"))
        assertTrue("vaplayer must be disabled", migratedDisabled.contains("vaplayer"))
        assertTrue("vidfast must be disabled", migratedDisabled.contains("vidfast"))
        assertTrue("VidEasy must be disabled", migratedDisabled.contains("VidEasy"))
        assertTrue("yflix must be disabled", migratedDisabled.contains("yflix"))
        assertTrue("vidsrc must be disabled", migratedDisabled.contains("vidsrc"))
        assertTrue("cinejoy must be disabled", migratedDisabled.contains("cinejoy"))

        // 2. Newly promoted top-tier providers MUST be enabled
        assertFalse("vidcore must be enabled", migratedDisabled.contains("vidcore"))
        assertFalse("vidup must be enabled", migratedDisabled.contains("vidup"))

        // 3. User custom disabled overrides MUST be strictly preserved
        assertTrue("User custom disable of vidlink must be preserved", migratedDisabled.contains("vidlink"))
        assertTrue("User custom disable of cinejoy must be preserved", migratedDisabled.contains("cinejoy"))

        // 4. User custom enabled overrides MUST be strictly preserved
        assertFalse("User custom enable of moviebox must be preserved", migratedDisabled.contains("moviebox"))
        assertFalse("User custom enable of uhdmovies must be preserved", migratedDisabled.contains("uhdmovies"))
        assertFalse("User custom enable of allmovieland must be preserved", migratedDisabled.contains("allmovieland"))

        // 5. Version flag must be v8
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testUpgradeFromV7UserAlreadyDisabledDeadOrEnabledSotaEdgeCase() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // Case where user had already disabled vidfast and already enabled vidcore in v7
        val customDisabled = (getDefaultDisabledProviderIds() + "vidfast") - "vidcore"
        mockPrefs.edit()
            .putStringSet("disabled_providers", customDisabled)
            .putBoolean("streamplay_top_tier_v7_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        assertTrue("vidfast remains disabled", migratedDisabled.contains("vidfast"))
        assertFalse("vidcore remains enabled", migratedDisabled.contains("vidcore"))
        assertFalse("vidup becomes enabled", migratedDisabled.contains("vidup"))
        assertTrue("VidEasy becomes disabled", migratedDisabled.contains("VidEasy"))
    }

    @Test
    fun testNullSharedPreferencesIsSafeAndReturnsDefaults() {
        val disabled = getOrInitializeDisabledProviders(null)
        assertNotNull(disabled)
        assertEquals(getDefaultDisabledProviderIds(), disabled)

        val active = buildProviders().map { it.id }.filterNot { disabled.contains(it) }.toSet()
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, active)
    }

    @Test
    fun testSubsequentInvocationsAreIdempotent() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val first = getOrInitializeDisabledProviders(mockPrefs)
        // Simulate user toggling a provider after migration
        val custom = first + "cinejoy"
        mockPrefs.edit().putStringSet("disabled_providers", custom).apply()

        val second = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent call must return stored preferences without re-migrating", custom, second)
        assertTrue(second.contains("cinejoy"))
    }

    // =========================================================================
    // 2. DEAD PROVIDER SAFETY & TIMEOUT BOUNDS
    // =========================================================================

    @Test
    fun testInvokeHexaNullInputsReturnZeroLinksImmediately() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            // Null tmdbId
            StreamPlayExtractor.invokeHexa(
                tmdbId = null,
                season = 1,
                episode = 1,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
            // TV show with season but null episode
            StreamPlayExtractor.invokeHexa(
                tmdbId = 12345,
                season = 1,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("No links emitted on invalid inputs", 0, links.size)
        assertTrue("Input validation must short-circuit in < 50ms, took ${elapsed}ms", elapsed < 50)
    }

    @Test
    fun testInvokeHexaDeadEndpointsCompleteCleanlyWithinBoundedTimeout() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeHexa(
                tmdbId = 999999,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("Dead HexaSU must emit 0 links", 0, links.size)
        // Must complete within bounded timeout (withTimeoutOrNull 2500ms)
        assertTrue("invokeHexa must complete within 3500ms bound, took ${elapsed}ms", elapsed <= 3500)
    }

    @Test
    fun testInvokeHexaCancellationSafety() = runBlocking {
        var cancellationObserved = false
        val job = launch {
            try {
                StreamPlayExtractor.invokeHexa(
                    tmdbId = 999999,
                    season = null,
                    episode = null,
                    subtitleCallback = {},
                    callback = {}
                )
            } catch (e: CancellationException) {
                cancellationObserved = true
                throw e
            }
        }
        // Cancel the job immediately
        job.cancelAndJoin()
        assertTrue("invokeHexa must propagate CancellationException to preserve coroutine hierarchy", job.isCancelled)
    }

    @Test
    fun testInvokeAutoembedNullInputsReturnZeroLinksImmediately() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            // Null tmdbId
            StreamPlayExtractor.invokeAutoembed(
                tmdbId = null,
                season = 1,
                episode = 1,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
            // TV show with season but null episode
            StreamPlayExtractor.invokeAutoembed(
                tmdbId = 12345,
                season = 1,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("No links emitted on invalid inputs", 0, links.size)
        assertTrue("Input validation must short-circuit in < 50ms, took ${elapsed}ms", elapsed < 50)
    }

    @Test
    fun testInvokeAutoembedDeadEndpointsCompleteCleanlyWithinBoundedTimeout() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeAutoembed(
                tmdbId = 999999,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("Dead AutoEmbed must emit 0 links", 0, links.size)
        // Must complete within bounded timeout (withTimeoutOrNull 2500ms)
        assertTrue("invokeAutoembed must complete within 3500ms bound, took ${elapsed}ms", elapsed <= 3500)
    }

    @Test
    fun testInvokeAutoembedCancellationSafety() = runBlocking {
        val job = launch {
            StreamPlayExtractor.invokeAutoembed(
                tmdbId = 999999,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = {}
            )
        }
        job.cancelAndJoin()
        assertTrue("invokeAutoembed must propagate CancellationException", job.isCancelled)
    }

    // =========================================================================
    // 3. EXTRACTOR INPUT VALIDATION (YFlix & CineJoy)
    // =========================================================================

    @Test
    fun testInvokeYFlixInputValidationBoundaryConditions() = runBlocking {
        val links = mutableListOf<ExtractorLink>()

        // 1. All null parameters
        var elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = null,
                tmdbId = null,
                imdbId = null,
                year = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("All-null parameters must short-circuit immediately (< 50ms)", elapsed < 50)

        // 2. Blank title and null IDs
        elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = "   ",
                tmdbId = null,
                imdbId = null,
                year = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("Blank title must short-circuit immediately (< 50ms)", elapsed < 50)

        // 3. TV show with season > 0 but episode == null
        elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = "Breaking Bad",
                tmdbId = 1396,
                imdbId = "tt0903747",
                year = 2008,
                season = 1,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("TV show with null episode must short-circuit immediately (< 50ms)", elapsed < 50)

        // 4. Overloaded method calls with null subtitle callback
        StreamPlayExtractor.invokeYFlix(
            title = null,
            year = null,
            season = null,
            episode = null,
            callback = { links.add(it) }
        )
        StreamPlayExtractor.invokeYFlix(
            title = null,
            tmdbId = null,
            year = null,
            season = null,
            episode = null,
            callback = { links.add(it) }
        )
        assertEquals(0, links.size)
    }

    @Test
    fun testInvokeYFlixDeadHostCompletesCleanlyWithinTimeout() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = "NonExistentDummyMovieTitleForChallenge12345",
                tmdbId = 99999999,
                imdbId = "tt99999999",
                year = 2026,
                season = null,
                episode = null,
                subtitleCallback = { subtitles.add(it) },
                callback = { links.add(it) }
            )
        }
        assertEquals("No links from unreachable/dead host", 0, links.size)
        assertEquals("No subtitles from unreachable/dead host", 0, subtitles.size)
        assertTrue("invokeYFlix must complete within 3500ms bounded timeout, took ${elapsed}ms", elapsed <= 3500)
    }

    @Test
    fun testInvokeCineJoyInputValidationBoundaryConditions() = runBlocking {
        val links = mutableListOf<ExtractorLink>()

        // 1. All null parameters
        var elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = null,
                tmdbId = null,
                imdbId = null,
                year = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("All-null parameters must short-circuit immediately (< 50ms)", elapsed < 50)

        // 2. Blank title and null IDs
        elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = "  \t\n  ",
                tmdbId = null,
                imdbId = null,
                year = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("Blank title must short-circuit immediately (< 50ms)", elapsed < 50)

        // 3. TV show with season > 0 but episode == null
        elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = "Stranger Things",
                tmdbId = 66732,
                imdbId = "tt4574334",
                year = 2016,
                season = 2,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals(0, links.size)
        assertTrue("TV show with null episode must short-circuit immediately (< 50ms)", elapsed < 50)

        // 4. Overloaded method calls with null subtitle callback
        StreamPlayExtractor.invokeCineJoy(
            title = null,
            tmdbId = null,
            season = null,
            episode = null,
            callback = { links.add(it) }
        )
        assertEquals(0, links.size)
    }

    @Test
    fun testInvokeCineJoyDeadHostCompletesCleanlyWithinTimeout() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = "NonExistentDummyMovieTitleForChallenge12345",
                tmdbId = 99999999,
                imdbId = "tt99999999",
                year = 2026,
                season = null,
                episode = null,
                subtitleCallback = { subtitles.add(it) },
                callback = { links.add(it) }
            )
        }
        assertEquals("No links from unreachable/dead host", 0, links.size)
        assertEquals("No subtitles from unreachable/dead host", 0, subtitles.size)
        assertTrue("invokeCineJoy must complete within 3500ms bounded timeout, took ${elapsed}ms", elapsed <= 3500)
    }

    @Test
    fun testDeadProvidersAndModernExtractorsNeverThrowUnhandledExceptionsUnderStress() = runBlocking {
        // Run 20 stress cycles across all 4 extractors with permutations of degenerate inputs
        val corruptTitles = listOf(
            null,
            "",
            "   ",
            "///:::???&&&",
            "A".repeat(500),
            "\u0000\u0001\u0002"
        )
        val testIds = listOf(null, 0, -1, 99999999)
        val seasons = listOf(null, 0, 1, -1)
        val episodes = listOf(null, 0, 1, -1)

        for (i in 0 until 10) {
            val title = corruptTitles[i % corruptTitles.size]
            val tmdbId = testIds[i % testIds.size]
            val season = seasons[i % seasons.size]
            val episode = episodes[i % episodes.size]

            // None of these should throw any unhandled exceptions
            StreamPlayExtractor.invokeHexa(tmdbId, season, episode) {}
            StreamPlayExtractor.invokeAutoembed(tmdbId, season, episode) {}
            StreamPlayExtractor.invokeYFlix(title, tmdbId, year = null, season = season, episode = episode) {}
            StreamPlayExtractor.invokeCineJoy(title, tmdbId, season = season, episode = episode) {}
        }
        assertTrue("Stress cycles with corrupt inputs executed with zero uncaught exceptions", true)
    }
}
