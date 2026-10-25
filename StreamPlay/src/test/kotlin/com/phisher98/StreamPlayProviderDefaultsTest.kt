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

    private val v25TopTier = setOf(
        "vidlink", "vidrift", "cineby", "cinesrc", "vidfast", "megaplay", "meowtv",
        "anizone", "animepahe", "animex"
    )

    @Test
    fun testRemovedSourcesAreAbsentFromProviderList() {
        val providers = buildProviders()

        listOf("vidrock", "vidsrc", "vidsrcsu", "vaplayer", "superstream", "vidcore", "rivestream", "videasy", "vidflix", "vixsrc")
            .forEach { id ->
                val found = providers.find { it.id.equals(id, ignoreCase = true) }
                assertNull("$id must be completely removed from providers list", found)
                assertFalse("$id must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains(id))
            }
    }

    @Test
    fun testDefaultTopTierProvidersContainsExactlyTheNineCuratedSources() {
        assertEquals(
            "Top tier default providers must contain exactly 10 sources",
            10,
            DEFAULT_TOP_TIER_PROVIDERS.size
        )
        assertEquals("Top tier default providers must match expected IDs", v25TopTier, DEFAULT_TOP_TIER_PROVIDERS)
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
        assertEquals("All curated sources should be active by default", 10, activeProviders.size)
        assertEquals(
            "Active provider IDs must match DEFAULT_TOP_TIER_PROVIDERS",
            DEFAULT_TOP_TIER_PROVIDERS,
            activeProviders.map { it.id }.toSet()
        )
    }

    @Test
    fun testAllTopTierProvidersDeclareTheirHonestColdStartTier() {
        // AniZone is a two-request plain-HTTP source and starts with the fast group; AnimePahe has
        // to walk search -> release -> play -> Kwik (and may answer a Cloudflare challenge), so it
        // is honestly classified one tier slower instead of pretending to be instant.
        val expectedTier = mapOf(
            "vidlink" to LatencyTier.TIER_1,
            "vidrift" to LatencyTier.TIER_1,
            "cineby" to LatencyTier.TIER_1,
            "cinesrc" to LatencyTier.TIER_1,
            "vidfast" to LatencyTier.TIER_1,
            "megaplay" to LatencyTier.TIER_1,
            "meowtv" to LatencyTier.TIER_1,
            "anizone" to LatencyTier.TIER_1,
            "animepahe" to LatencyTier.TIER_2,
            "animex" to LatencyTier.TIER_1
        )
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            val tier = SpeculativePipeliner.STATIC_COLD_START_TIERS[topId]
            assertEquals("Top tier provider $topId must declare its honest tier", expectedTier.getValue(topId), tier)
        }
    }

    // ==================== v18 Migration Idempotency Stress Tests ====================

    @Test
    fun testCleanInstallActivatesExactlyNineCuratedSourcesIdempotently() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        val initialDisabled = getOrInitializeDisabledProviders(mockPrefs)
        val activeProviders = buildProviders().map { it.id }.filterNot { initialDisabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 10 curated sources", 10, activeProviders.size)
        assertEquals(v25TopTier, activeProviders)
        v25TopTier.forEach { id ->
            assertFalse("$id must NOT be in disabled set", initialDisabled.contains(id))
        }
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))

        // Repeated invocation must be strictly idempotent.
        val secondDisabled = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Second call must return identical disabled set", initialDisabled, secondDisabled)

        // A user disabling a real curated source must be preserved across later calls.
        mockPrefs.edit().putStringSet("disabled_providers", setOf("meowtv")).apply()
        val userModified = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent calls must honor user preference", setOf("meowtv"), userModified)
    }

    @Test
    fun testUpgradeFromLegacyInstallScrubsEveryDecommissionedProviderId() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        mockPrefs.edit()
            .putStringSet(
                "disabled_providers",
                setOf("vidlink", "rivestream", "vidfast", "videasy", "vidflix", "vixsrc", "hexasu", "autoembed")
            )
            .putBoolean("streamplay_top_tier_v11_initialized", true)
            .apply()

        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        REMOVED_PROVIDER_IDS.forEach { legacy ->
            assertFalse("Legacy id $legacy must not survive migration", migratedDisabled.contains(legacy))
        }
        // Curated sources are always re-enabled by a version migration.
        assertFalse("vidlink must be re-enabled", migratedDisabled.contains("vidlink"))
        assertFalse("cinesrc must be re-enabled", migratedDisabled.contains("cinesrc"))

        val active = buildProviders().map { it.id }.filterNot { migratedDisabled.contains(it) }.toSet()
        assertEquals("Exactly the curated sources must be active after migration", v25TopTier, active)
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
            assertEquals("Every concurrent call must activate exactly the curated sources", v25TopTier, active)
            for (top in v25TopTier) {
                assertFalse("Top tier provider $top must not be disabled", res.contains(top))
            }
        }
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }
}
