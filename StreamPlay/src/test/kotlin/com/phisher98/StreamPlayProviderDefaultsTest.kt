package com.phisher98

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "HexaSU",
            "autoembed",
            "vidfast",
            "VidEasy",
            "vidsrc"
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
}

