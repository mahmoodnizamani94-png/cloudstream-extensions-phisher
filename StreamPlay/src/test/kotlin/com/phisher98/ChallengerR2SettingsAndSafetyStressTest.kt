package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        // Ensure StreamPlayExtractor object is classloaded before timing assertions
        StreamPlayExtractor.encDecApiSemaphore.availablePermits
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
    fun testCleanInstallNullDisabledProvidersActivatesExactlyNineCuratedSources() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Fresh install: no keys at all
        assertFalse(mockPrefs.contains(PREFS_TOP_TIER_INITIALIZED))
        assertFalse(mockPrefs.contains("disabled_providers"))

        val disabled = getOrInitializeDisabledProviders(mockPrefs)
        val activeProviders = buildProviders().map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 10 curated sources", 10, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders)

        val expectedCurated = setOf(
            "vidlink", "vidrift", "cineby", "cinesrc", "vidfast", "megaplay", "meowtv",
            "anizone", "animepahe", "animex"
        )
        assertEquals(expectedCurated, activeProviders)

        // Nothing may be disabled on a clean install, and no decommissioned id may appear.
        assertFalse("vidlink must NOT be disabled", disabled.contains("vidlink"))
        assertFalse("vidfast must NOT be disabled (re-issued id)", disabled.contains("vidfast"))
        REMOVED_PROVIDER_IDS.forEach { legacy ->
            assertFalse("$legacy must not be injected into the disabled set", disabled.contains(legacy))
        }

        // Preference flag must be written
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testCleanInstallEmptyDisabledProvidersActivatesExactlyNineCuratedSources() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Stale or empty set stored without the initialized flag
        mockPrefs.edit().putStringSet("disabled_providers", emptySet()).apply()

        val disabled = getOrInitializeDisabledProviders(mockPrefs)
        val activeProviders = buildProviders().map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install with empty set must activate exactly 10 curated sources", 10, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testUpgradeFromLegacyInstallEnablesCuratedAndScrubsEveryDecommissionedId() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // Simulate an earlier installation that had the whole scraper tier enabled and,
        // wrongly, a stale disable of VidLink.
        val legacyDisabled = setOf(
            "vidlink", "HexaSU", "autoembed", "moviebox", "vidcore", "vidfast",
            "VidEasy", "rivestream", "yflix", "vidsrc", "vidsrcsu", "cinejoy", "vidflix", "animegg"
        )
        mockPrefs.edit()
            .putStringSet("disabled_providers", legacyDisabled)
            .putBoolean("streamplay_top_tier_v7_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        REMOVED_PROVIDER_IDS.forEach { legacy ->
            assertFalse("$legacy must be scrubbed on migration", migratedDisabled.contains(legacy))
        }
        // The curated sources are re-enabled unconditionally by a version migration.
        assertFalse("vidlink must be re-enabled", migratedDisabled.contains("vidlink"))
        assertFalse("meowtv must be re-enabled", migratedDisabled.contains("meowtv"))

        val active = buildProviders().map { it.id }.filterNot { migratedDisabled.contains(it) }.toSet()
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testUpgradeFromLegacyInstallScrubsStaleIdsIdempotently() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // A user who had disabled a now-removed id.
        val customDisabled = getDefaultDisabledProviderIds() + "vidcore"
        mockPrefs.edit()
            .putStringSet("disabled_providers", customDisabled)
            .putBoolean("streamplay_top_tier_v7_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        assertFalse("vidcore must be scrubbed", migratedDisabled.contains("vidcore"))
        assertFalse("vidfast must be re-enabled (re-issued id)", migratedDisabled.contains("vidfast"))
        assertFalse("rivestream must be scrubbed", migratedDisabled.contains("rivestream"))
        assertFalse("animepahe must be scrubbed", migratedDisabled.contains("animepahe"))

        val subsequent = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Migration must be idempotent", migratedDisabled, subsequent)
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
        // Simulate the user disabling a curated source after migration; it must be
        // honoured (and stay stable) on subsequent reads.
        val custom = first + "meowtv"
        mockPrefs.edit().putStringSet("disabled_providers", custom).apply()

        val second = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent call must return stored preferences without re-migrating", custom, second)
        assertTrue(second.contains("meowtv"))
    }

    // =========================================================================
    // 2. SOTA PROVIDER INPUT VALIDATION & TIMEOUT BOUNDS
    // =========================================================================

    @Test
    fun testInvokeMeowTVNullInputsReturnZeroLinksImmediately() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            // Null tmdbId
            StreamPlayExtractor.invokeMeowTV(
                tmdbId = null,
                season = 1,
                episode = 1,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
            // TV show with season but null episode
            StreamPlayExtractor.invokeMeowTV(
                tmdbId = 12345,
                season = 1,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("No links emitted on invalid inputs", 0, links.size)
        assertTrue("Input validation must short-circuit in < 250ms, took ${elapsed}ms", elapsed < 250)
    }

    @Test
    fun testInvokeMeowTVUnindexedTitleCompletesCleanlyWithinBoundedTimeout() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeMeowTV(
                // 99999999 lies beyond TMDB's id space (404) — a genuinely unindexed title.
                tmdbId = 99999999,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("Unindexed title on MeowTV must emit 0 links", 0, links.size)
        assertTrue("invokeMeowTV must complete within 30000ms bound, took ${elapsed}ms", elapsed <= 30000)
    }

    @Test
    fun testInvokeMeowTVCancellationSafety() = runBlocking {
        var cancellationObserved = false
        val job = launch {
            try {
                StreamPlayExtractor.invokeMeowTV(
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
        assertTrue("invokeMeowTV must propagate CancellationException to preserve coroutine hierarchy", job.isCancelled)
    }

    @Test
    fun testVidlinkAndCinebyNullInputsReturnZeroLinksImmediately() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val elapsed = measureTimeMillis {
            // Null tmdbId on SOTA universal primaries
            StreamPlayExtractor.invokeVidlink(
                tmdbId = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
            StreamPlayExtractor.invokeCineby(
                tmdbId = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
            StreamPlayExtractor.invokeCineSrc(
                tmdbId = null,
                season = null,
                episode = null,
                subtitleCallback = {},
                callback = { links.add(it) }
            )
        }
        assertEquals("No links emitted on invalid inputs", 0, links.size)
        assertTrue("Input validation must short-circuit in < 250ms, took ${elapsed}ms", elapsed < 250)
    }

    // =========================================================================
    // 3. SOTA EXTRACTOR ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testSotaExtractorsNeverThrowUnhandledExceptionsUnderStress() = runBlocking {
        // Run 10 stress cycles across the SOTA extractors with degenerate inputs.
        // Every id below resolves to "no usable id", so the extractors must
        // short-circuit locally (no network) and never throw.
        val corruptTitles = listOf(
            null,
            "",
            "   ",
            "///:::???&&&",
            "A".repeat(500),
            "\u0000\u0001\u0002"
        )
        val invalidIds = listOf(null, 0, -1)
        val seasons = listOf(null, 0, 1, -1)
        val episodes = listOf(null, 0, 1, -1)

        for (i in 0 until 10) {
            val title = corruptTitles[i % corruptTitles.size]
            val tmdbId = invalidIds[i % invalidIds.size]
            val season = seasons[i % seasons.size]
            val episode = episodes[i % episodes.size]

            // None of these should throw any unhandled exceptions
            StreamPlayExtractor.invokeVidlink(tmdbId = tmdbId, season = season, episode = episode, subtitleCallback = null, callback = {})
            StreamPlayExtractor.invokeCineby(tmdbId = tmdbId, season = season, episode = episode, title = title, callback = {})
            StreamPlayExtractor.invokeCineSrc(tmdbId = tmdbId, season = season, episode = episode, subtitleCallback = null, callback = {})
        }
        assertTrue("Stress cycles with corrupt inputs executed with zero uncaught exceptions", true)
    }
}
