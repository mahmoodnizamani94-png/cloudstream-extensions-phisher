package com.phisher98

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamPlayProviderDefaultsTest {

    private val v18TopTier = setOf("vidlink", "vixsrc", "animepahe")

    @Test
    fun testRemovedSourcesAreAbsentFromProviderList() {
        val providers = buildProviders()

        listOf("vaplayer", "superstream", "vidcore", "rivestream", "vidfast", "videasy", "vidflix", "animegg")
            .forEach { id ->
                val found = providers.find { it.id.equals(id, ignoreCase = true) }
                assertNull("$id must be completely removed from providers list", found)
                assertFalse("$id must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains(id))
            }
    }

    @Test
    fun testDefaultTopTierProvidersContainsExactlyTheThreeCuratedSources() {
        assertEquals(
            "Top tier default providers must contain exactly 3 sources",
            3,
            DEFAULT_TOP_TIER_PROVIDERS.size
        )
        assertEquals("Top tier default providers must match expected IDs", v18TopTier, DEFAULT_TOP_TIER_PROVIDERS)
    }

    @Test
    fun testAllTopTierProvidersExistInProviderList() {
        val providerIds = buildProviders().map { it.id }.toSet()
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertTrue("Top tier provider $topId must exist in buildProviders()", providerIds.contains(topId))
        }
    }

    @Test
    fun testDefaultDisabledProvidersLeavesAllCuratedSourcesActive() {
        val allProviders = buildProviders()
        val disabledIds = getDefaultDisabledProviderIds()

        // All curated sources are enabled by default.
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertFalse("Top tier provider $topId must NOT be in default disabled set", disabledIds.contains(topId))
        }

        // There are no secondary sources any more, so nothing is disabled by default.
        val expectedDisabledCount = allProviders.size - DEFAULT_TOP_TIER_PROVIDERS.size
        assertEquals("No source should be disabled by default", expectedDisabledCount, disabledIds.size)

        val activeProviders = allProviders.filterNot { disabledIds.contains(it.id) }
        assertEquals("All curated sources should be active by default", 3, activeProviders.size)
        assertEquals(
            "Active provider IDs must match DEFAULT_TOP_TIER_PROVIDERS",
            DEFAULT_TOP_TIER_PROVIDERS,
            activeProviders.map { it.id }.toSet()
        )
    }

    @Test
    fun testAllTopTierProvidersAreTier1InColdStartTiers() {
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            val tier = SpeculativePipeliner.STATIC_COLD_START_TIERS[topId]
            assertEquals("Top tier provider $topId must be LatencyTier.TIER_1", LatencyTier.TIER_1, tier)
        }
    }

    // ==================== v18 Migration Idempotency Stress Tests ====================

    @Test
    fun testCleanInstallActivatesExactlyThreeCuratedSourcesIdempotently() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        val initialDisabled = getOrInitializeDisabledProviders(mockPrefs)
        val activeProviders = buildProviders().map { it.id }.filterNot { initialDisabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 3 curated sources", 3, activeProviders.size)
        assertEquals(v18TopTier, activeProviders)
        assertFalse("vidlink must NOT be in disabled set", initialDisabled.contains("vidlink"))
        assertFalse("vixsrc must NOT be in disabled set", initialDisabled.contains("vixsrc"))
        assertFalse("animepahe must NOT be in disabled set", initialDisabled.contains("animepahe"))
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))

        // Repeated invocation must be strictly idempotent.
        val secondDisabled = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Second call must return identical disabled set", initialDisabled, secondDisabled)

        // A user disabling a real curated source must be preserved across later calls.
        mockPrefs.edit().putStringSet("disabled_providers", setOf("animepahe")).apply()
        val userModified = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent calls must honor user preference", setOf("animepahe"), userModified)
    }

    @Test
    fun testUpgradeFromLegacyInstallScrubsEveryDecommissionedProviderId() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        mockPrefs.edit()
            .putStringSet(
                "disabled_providers",
                setOf("vidlink", "rivestream", "vidfast", "videasy", "vidflix", "animegg", "hexasu", "autoembed")
            )
            .putBoolean("streamplay_top_tier_v11_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        REMOVED_PROVIDER_IDS.forEach { legacy ->
            assertFalse("Legacy id $legacy must not survive migration", migratedDisabled.contains(legacy))
        }
        // Curated sources are always re-enabled by a version migration.
        assertFalse("vidlink must be re-enabled", migratedDisabled.contains("vidlink"))
        assertFalse("vixsrc must be re-enabled", migratedDisabled.contains("vixsrc"))

        val active = buildProviders().map { it.id }.filterNot { migratedDisabled.contains(it) }.toSet()
        assertEquals("Exactly the curated sources must be active after migration", v18TopTier, active)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))

        // Idempotency.
        val subsequent = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent call must be idempotent", migratedDisabled, subsequent)
    }

    @Test
    fun testConcurrentInitializationIsThreadSafeAndIdempotent() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentLinkedQueue<Set<String>>()

        repeat(threadCount) {
            executor.submit {
                try {
                    results.add(getOrInitializeDisabledProviders(mockPrefs))
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads must finish without deadlocks", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount, results.size)
        val allProviders = buildProviders()
        for (res in results) {
            val active = allProviders.map { it.id }.filterNot { res.contains(it) }.toSet()
            assertEquals("Every concurrent call must activate exactly the curated sources", v18TopTier, active)
            for (top in v18TopTier) {
                assertFalse("Top tier provider $top must not be disabled", res.contains(top))
            }
        }
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }
}
