package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPlayTopTierSourceHierarchyTest {

    private fun createLink(
        source: String,
        name: String,
        url: String,
        quality: Int = Qualities.P1080.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = "https://example.com/",
            quality = quality,
            type = type,
            headers = emptyMap()
        )
    }

    @Test
    fun testDefinitiveTopTierSourcesOrderAndRegistration() {
        val expectedOrder = listOf(
            "superstream",
            "vidlink",
            "HexaSU",
            "vidfast",
            "autoembed",
            "VidEasy"
        )
        val expectedSet = expectedOrder.toSet()

        // 1. Verify DEFAULT_TOP_TIER_PROVIDERS contains all 6 sources
        assertEquals(6, DEFAULT_TOP_TIER_PROVIDERS.size)
        assertEquals(expectedSet, DEFAULT_TOP_TIER_PROVIDERS)

        // 2. Verify all are registered in buildProviders()
        val allProviders = buildProviders()
        val providerMap = allProviders.associateBy { it.id }

        for (providerId in expectedOrder) {
            assertTrue("Provider '$providerId' must be registered", providerMap.containsKey(providerId))
        }

        // 3. Verify AutoEmbed provider details
        val autoembed = providerMap["autoembed"]
        assertNotNull("AutoEmbed provider must exist", autoembed)
        assertEquals("AutoEmbed", autoembed?.name)
        assertEquals(ProviderKind.VIDEO, autoembed?.kind)
    }

    @Test
    fun testVaplayerIsCompletelyAbsent() {
        val allProviders = buildProviders()
        val vaplayer = allProviders.find {
            it.id.equals("vaplayer", ignoreCase = true) || it.name.equals("Vaplayer", ignoreCase = true)
        }
        assertNull("Vaplayer must be completely absent from providers list", vaplayer)
        assertFalse("Vaplayer must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains("vaplayer"))
    }

    @Test
    fun testDefaultDisabledProvidersLeavesExactlyTopTierActive() {
        val allProviders = buildProviders()
        val disabledIds = getDefaultDisabledProviderIds()

        // Top tier must never be in disabled set
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertFalse("Top tier provider '$topId' must NOT be disabled", disabledIds.contains(topId))
        }

        // Active providers must be exactly the 6 top-tier providers
        val activeProviders = allProviders.filterNot { disabledIds.contains(it.id) }
        assertEquals(6, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders.map { it.id }.toSet())
    }

    @Test
    fun testColdStartLatencyTiersMatchPriority() {
        val topTierSources = listOf("superstream", "vidlink", "HexaSU", "vidfast", "autoembed", "VidEasy")

        for (source in topTierSources) {
            val tier = SpeculativePipeliner.STATIC_COLD_START_TIERS[source]
            assertEquals("Source '$source' must be assigned LatencyTier.TIER_1", LatencyTier.TIER_1, tier)
        }
    }

    @Test
    fun testFastProviderBoostStrictRankOrder() {
        val superstreamBoost = FAST_PROVIDER_BOOST["superstream"] ?: 0f
        val vidlinkBoost = FAST_PROVIDER_BOOST["vidlink"] ?: 0f
        val hexaBoost = FAST_PROVIDER_BOOST["HexaSU"] ?: 0f
        val vidfastBoost = FAST_PROVIDER_BOOST["vidfast"] ?: 0f
        val autoembedBoost = FAST_PROVIDER_BOOST["autoembed"] ?: 0f
        val videasyBoost = FAST_PROVIDER_BOOST["VidEasy"] ?: 0f

        // Check exact target scores
        assertEquals(70f, superstreamBoost, 0.001f)
        assertEquals(65f, vidlinkBoost, 0.001f)
        assertEquals(60f, hexaBoost, 0.001f)
        assertEquals(55f, vidfastBoost, 0.001f)
        assertEquals(50f, autoembedBoost, 0.001f)
        assertEquals(45f, videasyBoost, 0.001f)

        // Strict monotonicity check: SuperStream > VidLink > HexaSU > VidFast > AutoEmbed > VidEasy
        assertTrue(superstreamBoost > vidlinkBoost)
        assertTrue(vidlinkBoost > hexaBoost)
        assertTrue(hexaBoost > vidfastBoost)
        assertTrue(vidfastBoost > autoembedBoost)
        assertTrue(autoembedBoost > videasyBoost)

        // Secondary providers must never outrank top-tier providers (must be < 45f)
        val secondaryProviders = listOf("vidsrcxyz", "rivestream", "moviesapi", "moviebox", "vidzeeapi", "2Embed")
        for (sec in secondaryProviders) {
            val secBoost = FAST_PROVIDER_BOOST[sec] ?: 0f
            assertTrue("Secondary provider '$sec' ($secBoost) must be strictly lower than VidEasy ($videasyBoost)", secBoost < videasyBoost)
        }
    }

    @Test
    fun testStreamLinkOptimizerSourcePriorityRanks() {
        val superstreamLink = createLink("SuperStream", "SuperStream [1080p]", "https://www.febbox.com/file/stream.mp4")
        val vidlinkLink = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream/master.m3u8", type = ExtractorLinkType.M3U8)
        val hexaLink = createLink("HexaSU", "HexaSU Server 1 [1080p]", "https://hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val embedSuLink = createLink("HexaSU", "HexaSU Server 2 [1080p]", "https://embed.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastLink = createLink("VidFast", "VidFast Server 1 [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedLink = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyLink = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val secondaryLink = createLink("UnknownProvider", "Scraped Link [1080p]", "https://secondary.example.com/video.mp4")

        val rSuper = StreamLinkOptimizer.getSourcePriorityRank(superstreamLink)
        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(vidlinkLink)
        val rHexa = StreamLinkOptimizer.getSourcePriorityRank(hexaLink)
        val rEmbedSu = StreamLinkOptimizer.getSourcePriorityRank(embedSuLink)
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(vidfastLink)
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(autoembedLink)
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(videasyLink)
        val rSecondary = StreamLinkOptimizer.getSourcePriorityRank(secondaryLink)

        // Strict hierarchy check
        assertTrue("SuperStream ($rSuper) > Vidlink ($rVidlink)", rSuper > rVidlink)
        assertTrue("Vidlink ($rVidlink) > HexaSU ($rHexa)", rVidlink > rHexa)
        assertEquals("embed.su has same rank as HexaSU", rHexa, rEmbedSu)
        assertTrue("HexaSU ($rHexa) > VidFast ($rVidfast)", rHexa > rVidfast)
        assertTrue("VidFast ($rVidfast) > AutoEmbed ($rAutoembed)", rVidfast > rAutoembed)
        assertTrue("AutoEmbed ($rAutoembed) > VidEasy ($rVideasy)", rAutoembed > rVideasy)
        assertTrue("VidEasy ($rVideasy) > Secondary ($rSecondary)", rVideasy > rSecondary)
    }

    @Test
    fun testIsBetterThanPrefersTopTierDirectStreams() {
        val superstreamDirect = createLink("SuperStream", "SuperStream [1080p]", "https://www.febbox.com/file/stream.mp4")
        val vidlinkStream = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream/master.m3u8", type = ExtractorLinkType.M3U8)
        val hexaStream = createLink("HexaSU", "HexaSU [1080p]", "https://theemoviedb.hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastStream = createLink("VidFast", "VidFast [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedStream = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyStream = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)

        // Direct Febbox CDN MP4 beats HLS resolver
        assertTrue("SuperStream direct Febbox CDN MP4 beats Vidlink", StreamLinkOptimizer.isBetterThan(superstreamDirect, vidlinkStream))
        assertFalse("Vidlink does not beat SuperStream direct CDN MP4", StreamLinkOptimizer.isBetterThan(vidlinkStream, superstreamDirect))

        // Vidlink beats HexaSU
        assertTrue("Vidlink beats HexaSU", StreamLinkOptimizer.isBetterThan(vidlinkStream, hexaStream))
        assertFalse("HexaSU does not beat Vidlink", StreamLinkOptimizer.isBetterThan(hexaStream, vidlinkStream))

        // HexaSU beats VidFast
        assertTrue("HexaSU beats VidFast", StreamLinkOptimizer.isBetterThan(hexaStream, vidfastStream))
        assertFalse("VidFast does not beat HexaSU", StreamLinkOptimizer.isBetterThan(vidfastStream, hexaStream))

        // VidFast beats AutoEmbed
        assertTrue("VidFast beats AutoEmbed", StreamLinkOptimizer.isBetterThan(vidfastStream, autoembedStream))
        assertFalse("AutoEmbed does not beat VidFast", StreamLinkOptimizer.isBetterThan(autoembedStream, vidfastStream))

        // AutoEmbed beats VidEasy
        assertTrue("AutoEmbed beats VidEasy", StreamLinkOptimizer.isBetterThan(autoembedStream, videasyStream))
        assertFalse("VidEasy does not beat AutoEmbed", StreamLinkOptimizer.isBetterThan(videasyStream, autoembedStream))
    }

    @Test
    fun testTopTierSourceTakesPrecedenceOverVideoScrapeBadges() {
        // SuperStream with no title badges must strictly beat secondary scraper or lower-tier sources with [REMUX] or [WEB-DL]
        val superstreamPlain = createLink("SuperStream", "SuperStream [1080p]", "https://www.febbox.com/file/stream.mp4")
        val videasyRemux = createLink("VidEasy", "VidEasy [REMUX] [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val secondaryWebDl = createLink("SecondaryScraper", "SecondaryScraper [WEB-DL] [1080p]", "https://secondary.com/video.mp4")

        assertTrue("SuperStream beats VidEasy even with [REMUX] badge", StreamLinkOptimizer.isBetterThan(superstreamPlain, videasyRemux))
        assertFalse("VidEasy [REMUX] does not beat SuperStream", StreamLinkOptimizer.isBetterThan(videasyRemux, superstreamPlain))

        assertTrue("SuperStream beats SecondaryScraper [WEB-DL]", StreamLinkOptimizer.isBetterThan(superstreamPlain, secondaryWebDl))
        assertFalse("SecondaryScraper [WEB-DL] does not beat SuperStream", StreamLinkOptimizer.isBetterThan(secondaryWebDl, superstreamPlain))

        // VidFast beats AutoEmbed even if AutoEmbed has [WEB-DL]
        val vidfastPlain = createLink("VidFast", "VidFast [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedWebDl = createLink("AutoEmbed", "AutoEmbed [WEB-DL] [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertTrue("VidFast beats AutoEmbed with [WEB-DL]", StreamLinkOptimizer.isBetterThan(vidfastPlain, autoembedWebDl))

        // AutoEmbed beats VidEasy even if VidEasy has [WEB-DL]
        val autoembedPlain = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyWebDl = createLink("VidEasy", "VidEasy [WEB-DL] [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertTrue("AutoEmbed beats VidEasy with [WEB-DL]", StreamLinkOptimizer.isBetterThan(autoembedPlain, videasyWebDl))
    }

    @Test
    fun testDirectVideoContainerPreferredOverHlsForEqualTier() {
        // Direct MP4 (ExtractorLinkType.VIDEO) beats HLS for chunked Range-request downloads when source tier is identical
        val directMp4 = createLink("SuperStream", "SuperStream [1080p]", "https://www.febbox.com/file/stream.mp4", type = ExtractorLinkType.VIDEO)
        val hlsStream = createLink("SuperStream", "SuperStream [1080p]", "https://www.febbox.com/file/master.m3u8", type = ExtractorLinkType.M3U8)

        assertTrue("Direct MP4 beats HLS for download chunking on same tier", StreamLinkOptimizer.isBetterThan(directMp4, hlsStream))
        assertFalse("HLS does not beat direct MP4 on same tier", StreamLinkOptimizer.isBetterThan(hlsStream, directMp4))
    }

    @Test
    fun testInitializationForCleanInstallLeavesExactlyTopTierActive() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val disabled = getOrInitializeDisabledProviders(mockPrefs)

        val allProviders = buildProviders()
        val active = allProviders.map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 6 top-tier providers", 6, active.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue(mockPrefs.getBoolean("streamplay_top5_defaults_initialized", false))
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testMigrationForUpgradingUserEnablesAutoembed() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Simulate legacy user who had old top-5 defaults initialized (autoembed was disabled by default)
        val oldDisabled = getDefaultDisabledProviderIds() + "autoembed"
        mockPrefs.edit()
            .putStringSet("disabled_providers", oldDisabled)
            .putBoolean("streamplay_top5_defaults_initialized", true)
            .apply()

        // Run unified initialization/migration
        val finalDisabled = getOrInitializeDisabledProviders(mockPrefs)
        val allProviders = buildProviders()
        val active = allProviders.map { it.id }.filterNot { finalDisabled.contains(it) }.toSet()

        // AutoEmbed must be enabled (removed from disabled_providers)
        assertFalse("AutoEmbed must not be disabled after migration", finalDisabled.contains("autoembed"))
        assertEquals("All 6 top-tier providers must be active after migration", DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testDomainConstantsAreDefined() {
        assertEquals("https://theemoviedb.hexa.su", StreamPlay.hexaSU)
        assertEquals("https://embed.su", StreamPlay.embedSU)
        assertEquals("https://player.autoembed.cc", StreamPlay.autoembedPlayer)
        assertEquals("https://autoembed.cc", StreamPlay.autoembedDomain)
    }
}
