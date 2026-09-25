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

    @Test
    fun testSuperStreamAndVaplayerAreCompletelyRemovedFromProviderList() {
        val providers = buildProviders()
        val vaplayer = providers.find { it.id.equals("vaplayer", ignoreCase = true) || it.name.equals("Vaplayer", ignoreCase = true) }
        assertNull("Vaplayer must be completely removed from providers list", vaplayer)
        assertFalse("Vaplayer id must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains("vaplayer"))

        val superstream = providers.find { it.id.equals("superstream", ignoreCase = true) || it.name.equals("SuperStream", ignoreCase = true) }
        assertNull("SuperStream must be completely removed from providers list", superstream)
        assertFalse("SuperStream id must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains("superstream"))
    }

    @Test
    fun testDefaultTopTierProvidersContainsExpectedSources() {
        val expected = setOf(
            "vidlink",
            "vidcore",
            "vidup",
            "rivestream",
            "cinejoy",
            "VidEasy"
        )
        assertEquals("Top tier default providers must contain exactly 6 sources", 6, DEFAULT_TOP_TIER_PROVIDERS.size)
        assertEquals("Top tier default providers must match expected IDs", expected, DEFAULT_TOP_TIER_PROVIDERS)
    }

    @Test
    fun testAllTopTierProvidersExistInProviderList() {
        val allProviders = buildProviders()
        val providerIds = allProviders.map { it.id }.toSet()

        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertTrue("Top tier provider $topId must exist in buildProviders()", providerIds.contains(topId))
        }
    }

    @Test
    fun testDefaultDisabledProvidersDisablesAllSecondarySources() {
        val allProviders = buildProviders()
        val disabledIds = getDefaultDisabledProviderIds()

        // Verify none of the top tier providers are disabled
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertFalse("Top tier provider $topId must NOT be in default disabled set", disabledIds.contains(topId))
        }

        // Verify that every provider not in top tier is in disabled set
        val expectedDisabledCount = allProviders.size - DEFAULT_TOP_TIER_PROVIDERS.size
        assertEquals("All non-top-tier sources must be disabled by default", expectedDisabledCount, disabledIds.size)

        // Active providers when applying default disabled set must be exactly top tier
        val activeProviders = allProviders.filterNot { disabledIds.contains(it.id) }
        assertEquals("Only top tier providers should be active by default", 6, activeProviders.size)
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

    // ==================== v11 Migration Idempotency Stress Tests ====================

    @Test
    fun testV11MigrationCleanInstallActivatesExactlyTopTierIdempotently() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // 1. Initial clean install execution
        val initialDisabled = getOrInitializeDisabledProviders(mockPrefs)
        val allProviders = buildProviders()
        val activeProviders = allProviders.map { it.id }.filterNot { initialDisabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 6 top-tier providers", 6, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders)
        assertTrue("HexaSU must be in disabled set", initialDisabled.contains("HexaSU"))
        assertTrue("autoembed must be in disabled set", initialDisabled.contains("autoembed"))
        assertTrue("vidfast must be in disabled set", initialDisabled.contains("vidfast"))
        assertFalse("VidEasy must NOT be in disabled set", initialDisabled.contains("VidEasy"))
        assertTrue("yflix must be in disabled set", initialDisabled.contains("yflix"))
        assertTrue("vidsrc must be in disabled set", initialDisabled.contains("vidsrc"))
        assertFalse("cinejoy must NOT be in disabled set", initialDisabled.contains("cinejoy"))
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))

        // 2. Repeated invocation must be strictly idempotent
        val secondDisabled = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Second call must return identical disabled set", initialDisabled, secondDisabled)

        // 3. User custom modifications after migration must NOT be overwritten by subsequent calls
        val userModified = secondDisabled + "vidup" // User disables vidup
        mockPrefs.edit().putStringSet("disabled_providers", userModified).apply()

        val thirdDisabled = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent calls must honor user preference and not re-initialize", userModified, thirdDisabled)
        assertTrue(thirdDisabled.contains("vidup"))
    }

    @Test
    fun testV11MigrationUpgradeFromV10PreservesOverridesAndIsIdempotent() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()

        // Setup legacy state with custom user overrides (user disabled vidlink, user enabled moviebox)
        val v10Disabled = (getDefaultDisabledProviderIds() + setOf("vidlink")) - "moviebox"
        mockPrefs.edit()
            .putStringSet("disabled_providers", v10Disabled)
            .putBoolean("streamplay_top_tier_v10_initialized", true)
            .apply()

        // Run v11 migration
        val migratedDisabled = getOrInitializeDisabledProviders(mockPrefs)

        // Dead providers must be disabled (including vidfast, vidsrc)
        assertTrue("HexaSU must be disabled", migratedDisabled.contains("HexaSU"))
        assertTrue("autoembed must be disabled", migratedDisabled.contains("autoembed"))
        assertTrue("superstream must be disabled", migratedDisabled.contains("superstream"))
        assertTrue("vaplayer must be disabled", migratedDisabled.contains("vaplayer"))
        assertTrue("vidfast must be disabled", migratedDisabled.contains("vidfast"))
        assertFalse("VidEasy must be enabled", migratedDisabled.contains("VidEasy"))
        assertTrue("yflix must be disabled", migratedDisabled.contains("yflix"))
        assertTrue("vidsrc must be disabled", migratedDisabled.contains("vidsrc"))
        assertFalse("cinejoy must be enabled", migratedDisabled.contains("cinejoy"))

        // Promoted SOTA providers must be enabled
        assertFalse("vidcore must be enabled", migratedDisabled.contains("vidcore"))
        assertFalse("vidup must be enabled", migratedDisabled.contains("vidup"))
        assertFalse("rivestream must be enabled", migratedDisabled.contains("rivestream"))

        // User overrides must be preserved
        assertTrue("User custom disable of vidlink must be preserved", migratedDisabled.contains("vidlink"))
        assertFalse("User custom enable of moviebox must be preserved", migratedDisabled.contains("moviebox"))

        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))

        // Idempotency: second call returns same set
        val subsequent = getOrInitializeDisabledProviders(mockPrefs)
        assertEquals("Subsequent call must be idempotent", migratedDisabled, subsequent)
    }

    @Test
    fun testV10MigrationConcurrentInitializationIsThreadSafeAndIdempotent() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val threadCount = 20
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val results = ConcurrentLinkedQueue<Set<String>>()

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    val res = getOrInitializeDisabledProviders(mockPrefs)
                    results.add(res)
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
            assertEquals("Every concurrent call must activate exactly 6 top-tier providers", DEFAULT_TOP_TIER_PROVIDERS, active)
            for (top in DEFAULT_TOP_TIER_PROVIDERS) {
                assertFalse("Top tier provider $top must not be disabled", res.contains(top))
            }
        }
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }
}

