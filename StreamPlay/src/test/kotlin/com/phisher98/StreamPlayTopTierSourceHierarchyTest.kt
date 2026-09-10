package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class StreamPlayTopTierSourceHierarchyTest {

    private fun createLink(
        source: String,
        name: String,
        url: String,
        quality: Int = Qualities.P1080.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "https://example.com/",
        headers: Map<String, String> = emptyMap()
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type,
            headers = headers
        )
    }

    @Test
    fun testDefinitiveTopTierSourcesOrderAndRegistration() {
        val expectedOrder = listOf(
            "vidlink",
            "HexaSU",
            "autoembed",
            "vidfast",
            "VidEasy"
        )
        val expectedSet = expectedOrder.toSet()

        // 1. Verify DEFAULT_TOP_TIER_PROVIDERS contains all 5 sources
        assertEquals(5, DEFAULT_TOP_TIER_PROVIDERS.size)
        assertEquals(expectedSet, DEFAULT_TOP_TIER_PROVIDERS)

        // 2. Verify all are registered in buildProviders()
        val allProviders = buildProviders()
        val providerMap = allProviders.associateBy { it.id }

        for (providerId in expectedOrder) {
            assertTrue("Provider '$providerId' must be registered", providerMap.containsKey(providerId))
        }

        // 3. Verify SuperStream is NOT registered
        assertFalse("SuperStream must NOT be registered in buildProviders()", providerMap.containsKey("superstream"))

        // 4. Verify AutoEmbed provider details
        val autoembed = providerMap["autoembed"]
        assertNotNull("AutoEmbed provider must exist", autoembed)
        assertEquals("AutoEmbed", autoembed?.name)
        assertEquals(ProviderKind.VIDEO, autoembed?.kind)
    }

    @Test
    fun testVaplayerAndSuperStreamAreCompletelyAbsent() {
        val allProviders = buildProviders()
        val vaplayer = allProviders.find {
            it.id.equals("vaplayer", ignoreCase = true) || it.name.equals("Vaplayer", ignoreCase = true)
        }
        assertNull("Vaplayer must be completely absent from providers list", vaplayer)
        assertFalse("Vaplayer must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains("vaplayer"))

        val superstream = allProviders.find {
            it.id.equals("superstream", ignoreCase = true) || it.name.equals("SuperStream", ignoreCase = true)
        }
        assertNull("SuperStream must be completely absent from providers list", superstream)
        assertFalse("SuperStream must not be in default top tier providers", DEFAULT_TOP_TIER_PROVIDERS.contains("superstream"))

        assertNull("Vaplayer must not have an entry in FAST_PROVIDER_BOOST", FAST_PROVIDER_BOOST["vaplayer"])
        assertNull("SuperStream must not have an entry in FAST_PROVIDER_BOOST", FAST_PROVIDER_BOOST["superstream"])
        assertNull("Vaplayer must not exist in static cold start tiers", SpeculativePipeliner.STATIC_COLD_START_TIERS["vaplayer"])
        assertNull("Superstream must not exist in static cold start tiers", SpeculativePipeliner.STATIC_COLD_START_TIERS["superstream"])
    }

    @Test
    fun testDefaultDisabledProvidersLeavesExactlyTopTierActive() {
        val allProviders = buildProviders()
        val disabledIds = getDefaultDisabledProviderIds()

        // Top tier must never be in disabled set
        for (topId in DEFAULT_TOP_TIER_PROVIDERS) {
            assertFalse("Top tier provider '$topId' must NOT be disabled", disabledIds.contains(topId))
        }

        // Active providers must be exactly the 5 top-tier providers
        val activeProviders = allProviders.filterNot { disabledIds.contains(it.id) }
        assertEquals(5, activeProviders.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, activeProviders.map { it.id }.toSet())
    }

    @Test
    fun testColdStartLatencyTiersMatchPriority() {
        val topTierSources = listOf("vidlink", "HexaSU", "autoembed", "vidfast", "VidEasy")

        for (source in topTierSources) {
            val tier = SpeculativePipeliner.STATIC_COLD_START_TIERS[source]
            assertEquals("Source '$source' must be assigned LatencyTier.TIER_1", LatencyTier.TIER_1, tier)
        }

        assertNull("Superstream must not exist in static cold start tiers", SpeculativePipeliner.STATIC_COLD_START_TIERS["superstream"])
    }

    @Test
    fun testFastProviderBoostStrictRankOrder() {
        val vidlinkBoost = FAST_PROVIDER_BOOST["vidlink"] ?: 0f
        val hexaBoost = FAST_PROVIDER_BOOST["HexaSU"] ?: 0f
        val autoembedBoost = FAST_PROVIDER_BOOST["autoembed"] ?: 0f
        val vidfastBoost = FAST_PROVIDER_BOOST["vidfast"] ?: 0f
        val videasyBoost = FAST_PROVIDER_BOOST["VidEasy"] ?: 0f
        val superstreamBoost = FAST_PROVIDER_BOOST["superstream"]

        assertNull("SuperStream must not have an entry in FAST_PROVIDER_BOOST", superstreamBoost)

        // Check exact target scores
        assertEquals(100f, vidlinkBoost, 0.001f)
        assertEquals(90f, hexaBoost, 0.001f)
        assertEquals(80f, autoembedBoost, 0.001f)
        assertEquals(70f, vidfastBoost, 0.001f)
        assertEquals(60f, videasyBoost, 0.001f)

        // Strict monotonicity check: VidLink > HexaSU > AutoEmbed > VidFast > VidEasy
        assertTrue(vidlinkBoost > hexaBoost)
        assertTrue(hexaBoost > autoembedBoost)
        assertTrue(autoembedBoost > vidfastBoost)
        assertTrue(vidfastBoost > videasyBoost)

        // Secondary providers must never outrank top-tier providers (must be < 60f)
        val secondaryProviders = listOf("vidsrcxyz", "rivestream", "moviesapi", "moviebox", "vidzeeapi", "2Embed")
        for (sec in secondaryProviders) {
            val secBoost = FAST_PROVIDER_BOOST[sec] ?: 0f
            assertTrue("Secondary provider '$sec' ($secBoost) must be strictly lower than VidEasy ($videasyBoost)", secBoost < videasyBoost)
        }
    }

    @Test
    fun testStreamLinkOptimizerSourcePriorityRanks() {
        val vidlinkLink = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream/master.m3u8", type = ExtractorLinkType.M3U8)
        val hexaLink = createLink("HexaSU", "HexaSU Server 1 [1080p]", "https://hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val embedSuLink = createLink("HexaSU", "HexaSU Server 2 [1080p]", "https://embed.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedLink = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastLink = createLink("VidFast", "VidFast Server 1 [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyLink = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val secondaryLink = createLink("UnknownProvider", "Scraped Link [1080p]", "https://secondary.example.com/video.mp4")

        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(vidlinkLink)
        val rHexa = StreamLinkOptimizer.getSourcePriorityRank(hexaLink)
        val rEmbedSu = StreamLinkOptimizer.getSourcePriorityRank(embedSuLink)
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(autoembedLink)
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(vidfastLink)
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(videasyLink)
        val rSecondary = StreamLinkOptimizer.getSourcePriorityRank(secondaryLink)

        // Strict hierarchy check
        assertEquals("VidLink rank is 100", 100, rVidlink)
        assertEquals("HexaSU rank is 90", 90, rHexa)
        assertEquals("embed.su has same rank as HexaSU (90)", 90, rEmbedSu)
        assertEquals("AutoEmbed rank is 80", 80, rAutoembed)
        assertEquals("VidFast rank is 70", 70, rVidfast)
        assertEquals("VidEasy rank is 60", 60, rVideasy)

        assertTrue("Vidlink ($rVidlink) > HexaSU ($rHexa)", rVidlink > rHexa)
        assertTrue("HexaSU ($rHexa) > AutoEmbed ($rAutoembed)", rHexa > rAutoembed)
        assertTrue("AutoEmbed ($rAutoembed) > VidFast ($rVidfast)", rAutoembed > rVidfast)
        assertTrue("VidFast ($rVidfast) > VidEasy ($rVideasy)", rVidfast > rVideasy)
        assertTrue("VidEasy ($rVideasy) > Secondary ($rSecondary)", rVideasy > rSecondary)
    }

    @Test
    fun testIsBetterThanPrefersTopTierDirectStreams() {
        val vidlinkStream = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream/master.m3u8", type = ExtractorLinkType.M3U8)
        val hexaStream = createLink("HexaSU", "HexaSU [1080p]", "https://theemoviedb.hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedStream = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastStream = createLink("VidFast", "VidFast [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyStream = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)

        // Vidlink beats HexaSU
        assertTrue("Vidlink beats HexaSU", StreamLinkOptimizer.isBetterThan(vidlinkStream, hexaStream))
        assertFalse("HexaSU does not beat Vidlink", StreamLinkOptimizer.isBetterThan(hexaStream, vidlinkStream))

        // HexaSU beats AutoEmbed
        assertTrue("HexaSU beats AutoEmbed", StreamLinkOptimizer.isBetterThan(hexaStream, autoembedStream))
        assertFalse("AutoEmbed does not beat HexaSU", StreamLinkOptimizer.isBetterThan(autoembedStream, hexaStream))

        // AutoEmbed beats VidFast
        assertTrue("AutoEmbed beats VidFast", StreamLinkOptimizer.isBetterThan(autoembedStream, vidfastStream))
        assertFalse("VidFast does not beat AutoEmbed", StreamLinkOptimizer.isBetterThan(vidfastStream, autoembedStream))

        // VidFast beats VidEasy
        assertTrue("VidFast beats VidEasy", StreamLinkOptimizer.isBetterThan(vidfastStream, videasyStream))
        assertFalse("VidEasy does not beat VidFast", StreamLinkOptimizer.isBetterThan(videasyStream, vidfastStream))
    }

    @Test
    fun testTopTierSourceTakesPrecedenceOverVideoScrapeBadges() {
        val videasyPlain = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val secondaryRemux = createLink("SecondaryScraper", "SecondaryScraper [REMUX] [1080p]", "https://secondary.com/video.mp4")

        assertTrue("VidEasy beats Secondary even with [REMUX] badge", StreamLinkOptimizer.isBetterThan(videasyPlain, secondaryRemux))
        assertFalse("Secondary [REMUX] does not beat VidEasy", StreamLinkOptimizer.isBetterThan(secondaryRemux, videasyPlain))

        // AutoEmbed beats VidFast even if VidFast has [WEB-DL]
        val autoembedPlain = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastWebDl = createLink("VidFast", "VidFast [WEB-DL] [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertTrue("AutoEmbed beats VidFast with [WEB-DL]", StreamLinkOptimizer.isBetterThan(autoembedPlain, vidfastWebDl))

        // VidFast beats VidEasy even if VidEasy has [WEB-DL]
        val vidfastPlain = createLink("VidFast", "VidFast [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyWebDl = createLink("VidEasy", "VidEasy [WEB-DL] [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertTrue("VidFast beats VidEasy with [WEB-DL]", StreamLinkOptimizer.isBetterThan(vidfastPlain, videasyWebDl))
    }

    @Test
    fun testDirectVideoContainerPreferredOverHlsForEqualTier() {
        // Direct MP4 (ExtractorLinkType.VIDEO) beats HLS for chunked Range-request downloads when source tier is identical
        val directMp4 = createLink("Vidlink", "Vidlink [1080p]", "https://hakunaymatata.pro/stream.mp4", type = ExtractorLinkType.VIDEO)
        val hlsStream = createLink("Vidlink", "Vidlink [1080p]", "https://hakunaymatata.pro/master.m3u8", type = ExtractorLinkType.M3U8)

        assertTrue("Direct MP4 beats HLS for download chunking on same tier", StreamLinkOptimizer.isBetterThan(directMp4, hlsStream))
        assertFalse("HLS does not beat direct MP4 on same tier", StreamLinkOptimizer.isBetterThan(hlsStream, directMp4))
    }

    @Test
    fun testInitializationForCleanInstallLeavesExactlyTopTierActive() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val disabled = getOrInitializeDisabledProviders(mockPrefs)

        val allProviders = buildProviders()
        val active = allProviders.map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals("Clean install must activate exactly 5 top-tier providers", 5, active.size)
        assertEquals(DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue(mockPrefs.getBoolean("streamplay_top5_defaults_initialized", false))
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testMigrationForUpgradingUserEnablesAutoembedAndDisablesSuperStream() {
        val mockPrefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        // Simulate legacy user who had old defaults initialized (autoembed disabled, superstream present)
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
        // SuperStream must be explicitly disabled in migration
        assertTrue("SuperStream must be in disabled_providers after migration", finalDisabled.contains("superstream"))
        assertEquals("All 5 top-tier providers must be active after migration", DEFAULT_TOP_TIER_PROVIDERS, active)
        assertTrue(mockPrefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testHakunaymatataVidlinkHeaderContract() {
        val url = "https://sub.hakunaymatata.com/video.mp4"
        val initialHeaders = mapOf("Referer" to "https://vidlink.pro/", "Origin" to "https://vidlink.pro")

        val effectiveReferer = StreamLinkOptimizer.getEffectiveReferer(url, "https://vidlink.pro/", initialHeaders)
        assertEquals("Hakunaymatata referer must be stripped to empty string", "", effectiveReferer)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(initialHeaders, url, "https://vidlink.pro/")
        assertTrue("Must inject mobile Cronet User-Agent", downloadHeaders["User-Agent"]?.contains("Cronet") == true)
        assertEquals("*/*", downloadHeaders["Accept"])
        assertFalse("Referer must NOT be present in Hakunaymatata headers", downloadHeaders.containsKey("Referer"))
        assertFalse("Origin must NOT be present in Hakunaymatata headers", downloadHeaders.containsKey("Origin"))
    }

    @Test
    fun testVidfastCdnHeaderContract() {
        val url = "https://stream.vidfast.vc/master.m3u8"
        val effectiveReferer = StreamLinkOptimizer.getEffectiveReferer(url, "", emptyMap())
        assertEquals("https://vidfast.vc/", effectiveReferer)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), url, "")
        assertEquals("https://vidfast.vc/", downloadHeaders["Referer"])
        assertEquals("https://vidfast.vc", downloadHeaders["Origin"])
    }

    @Test
    fun testVidfastCdnPeakstormAndHypergateHeaders() {
        for (host in listOf("https://stream.peakstorm.top/stream.m3u8", "https://cdn.hypergate.top/stream.m3u8")) {
            val effectiveReferer = StreamLinkOptimizer.getEffectiveReferer(host, "", emptyMap())
            assertEquals("https://vidfast.vc/", effectiveReferer)

            val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), host, "")
            assertEquals("https://vidfast.vc/", downloadHeaders["Referer"])
            assertEquals("https://vidfast.vc", downloadHeaders["Origin"])

            val link = createLink("UnknownSource", "Stream 1080p", host, type = ExtractorLinkType.M3U8)
            assertEquals("Peakstorm/Hypergate streams must be recognized as VidFast tier (70)", 70, StreamLinkOptimizer.getSourcePriorityRank(link))
        }
    }

    @Test
    fun testVidlinkDirectMp4StrippingVidlinkProReferer() {
        val directMp4Url = "https://sub.hakunaymatata.com/video.mp4"
        val headersWithVidlinkPro = mapOf(
            "Referer" to "https://vidlink.pro/",
            "Origin" to "https://vidlink.pro",
            "User-Agent" to "Desktop Browser UA"
        )

        val effectiveReferer = StreamLinkOptimizer.getEffectiveReferer(directMp4Url, "https://vidlink.pro/", headersWithVidlinkPro)
        assertEquals("Referer for direct Hakunaymatata MP4 must be empty", "", effectiveReferer)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(headersWithVidlinkPro, directMp4Url, "https://vidlink.pro/", ExtractorLinkType.VIDEO)
        assertTrue("Must inject mobile Cronet User-Agent", downloadHeaders["User-Agent"]?.contains("Cronet") == true)
        assertEquals("*/*", downloadHeaders["Accept"])
        assertFalse("Referer pointing to vidlink.pro must be stripped", downloadHeaders.containsKey("Referer"))
        assertFalse("Origin pointing to vidlink.pro must be stripped", downloadHeaders.containsKey("Origin"))
    }

    @Test
    fun testEarlySatisfactionControllerTracksMaxEmittedPriority() {
        val config = EarlySatisfactionConfig()
        val controller = EarlySatisfactionController(config)
        assertEquals(0f, controller.getMaxEmittedPriority(), 0.001f)

        val vidfastLink = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/stream.m3u8", type = ExtractorLinkType.M3U8)
        controller.onLinkEmitted(vidfastLink)
        assertEquals(70f, controller.getMaxEmittedPriority(), 0.001f)

        val vidlinkLink = createLink("Vidlink", "Vidlink [1080p]", "https://hakunaymatata.com/stream.mp4", type = ExtractorLinkType.VIDEO)
        controller.onLinkEmitted(vidlinkLink)
        assertEquals(100f, controller.getMaxEmittedPriority(), 0.001f)

        controller.reset()
        assertEquals(0f, controller.getMaxEmittedPriority(), 0.001f)
    }

    @Test
    fun testDomainConstantsAreDefined() {
        assertEquals("https://theemoviedb.hexa.su", StreamPlay.hexaSU)
        assertEquals("https://embed.su", StreamPlay.embedSU)
        assertEquals("https://player.autoembed.cc", StreamPlay.autoembedPlayer)
        assertEquals("https://autoembed.cc", StreamPlay.autoembedDomain)
    }

    @Test
    fun testVideasyCdnContract() {
        for (host in listOf("https://api.videasy.net/stream.m3u8", "https://cdn.cineby.sc/video.mp4")) {
            val effectiveReferer = StreamLinkOptimizer.getEffectiveReferer(host, "", emptyMap())
            assertEquals("https://www.cineby.sc/", effectiveReferer)

            val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), host, "")
            assertEquals("https://www.cineby.sc/", downloadHeaders["Referer"])
            assertEquals("https://www.cineby.sc", downloadHeaders["Origin"])

            val link = createLink("UnknownSource", "Stream 1080p", host, type = ExtractorLinkType.M3U8)
            assertEquals("Videasy/Cineby streams must be recognized as VidEasy tier (60)", 60, StreamLinkOptimizer.getSourcePriorityRank(link))
        }
    }

    @Test
    fun testHexaWordMatchesHexaTier() {
        val link1 = createLink("Hexa", "Hexa Server 1 [1080p]", "https://example.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val link2 = createLink("HexaSU", "HexaSU [1080p]", "https://example.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertEquals(90, StreamLinkOptimizer.getSourcePriorityRank(link1))
        assertEquals(90, StreamLinkOptimizer.getSourcePriorityRank(link2))
    }

    @Test
    fun testAutoembedStrictlyDominatesVidfastEvenWithBitrateTags() {
        val autoembedStream = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastWithBitrate = createLink("VidFast", "VidFast [1080p] (4500 kbps)", "https://vidfast.vc/stream.m3u8", type = ExtractorLinkType.M3U8)

        // AutoEmbed MUST beat VidFast even if VidFast claims higher bitrate
        assertTrue("AutoEmbed beats VidFast with bitrate", StreamLinkOptimizer.isBetterThan(autoembedStream, vidfastWithBitrate))
        assertFalse("VidFast with bitrate must NOT beat AutoEmbed", StreamLinkOptimizer.isBetterThan(vidfastWithBitrate, autoembedStream))

        // VidFast MUST beat VidEasy even if VidEasy claims higher bitrate
        val videasyWithBitrate = createLink("VidEasy", "VidEasy [1080p] (5000 kbps)", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastPlain = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertTrue("VidFast beats VidEasy with bitrate", StreamLinkOptimizer.isBetterThan(vidfastPlain, videasyWithBitrate))
        assertFalse("VidEasy with bitrate must NOT beat VidFast", StreamLinkOptimizer.isBetterThan(videasyWithBitrate, vidfastPlain))
    }

    @Test
    fun testFullFiveTierStrictMonotonicity() {
        val vidlink = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val hexasu = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembed = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfast = createLink("VidFast", "VidFast [1080p]", "https://vidfast.vc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasy = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)

        val links = listOf(vidlink, hexasu, autoembed, vidfast, videasy)
        val ranks = links.map { StreamLinkOptimizer.getSourcePriorityRank(it) }

        assertEquals(listOf(100, 90, 80, 70, 60), ranks)

        // Verify pairwise transitive dominance: link[i] beats link[j] for all i < j
        for (i in 0 until links.size) {
            for (j in (i + 1) until links.size) {
                assertTrue("${links[i].name} must beat ${links[j].name}", StreamLinkOptimizer.isBetterThan(links[i], links[j]))
                assertFalse("${links[j].name} must NOT beat ${links[i].name}", StreamLinkOptimizer.isBetterThan(links[j], links[i]))
            }
        }
    }

    @Test
    fun testVidlinkProUrlStrippedToEmptyRefererUnderAllConditions() {
        val urls = listOf(
            "https://vidlink.pro/stream/playlist.m3u8",
            "https://vidlink.pro/video/movie.mp4",
            "https://cdn.example.com/stream.mp4?origin=vidlink.pro",
            "https://sub.hakunaymatata.com/video.mp4"
        )
        for (u in urls) {
            val effRef = StreamLinkOptimizer.getEffectiveReferer(u, "https://vidlink.pro/", mapOf("Referer" to "https://vidlink.pro/"))
            assertEquals("Effective referer must be empty for vidlink.pro stream: $u", "", effRef)

            val headers = StreamLinkOptimizer.buildDownloadHeaders(
                mapOf("Referer" to "https://vidlink.pro/", "Origin" to "https://vidlink.pro"),
                u,
                "https://vidlink.pro/",
                ExtractorLinkType.VIDEO
            )
            assertFalse("Referer must be stripped from $u", headers.containsKey("Referer"))
            assertFalse("Origin must be stripped from $u", headers.containsKey("Origin"))
            assertTrue("Mobile Cronet UA must be attached to $u", headers["User-Agent"]?.contains("Cronet") == true)
        }
    }

    @Test
    fun testInFlightHigherPriorityTasksPreservedUnderTelemetryVariance() = kotlinx.coroutines.runBlocking {
        val higherPriorityFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val lowerTierSiblingCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val lowerPriorityFinished = java.util.concurrent.atomic.AtomicBoolean(false)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            tier1DelayMs = 0L,
            tier2DelayMs = 0L
        )
        val controller = EarlySatisfactionController(config)

        // Give videasy an artificial telemetry boost
        ProviderTelemetryManager.recordExecution("videasy", true, 200L)
        ProviderTelemetryManager.recordExecution("videasy", true, 200L)

        val tasks = listOf(
            // High priority task: VidLink (100)
            PipelinedTask("vidlink", LatencyTier.TIER_0, isVideo = true, priorityBoost = 100f) {
                kotlinx.coroutines.delay(80)
                controller.onLinkEmitted(createLink("Vidlink", "Vidlink 1080p", "https://hakunaymatata.com/stream.mp4", Qualities.P1080.value, ExtractorLinkType.VIDEO))
                higherPriorityFinished.set(true)
            },
            // Lower priority task: VidEasy (60) finishes at 10ms and satisfies controller early
            PipelinedTask("videasy", LatencyTier.TIER_0, isVideo = true, priorityBoost = 60f) {
                kotlinx.coroutines.delay(10)
                controller.onLinkEmitted(createLink("VidEasy", "VidEasy 1080p", "https://api.videasy.net/stream.m3u8", Qualities.P1080.value, ExtractorLinkType.M3U8))
                lowerPriorityFinished.set(true)
            },
            // Secondary scraper: MovieBox (40) should be cancelled
            PipelinedTask("moviebox", LatencyTier.TIER_0, isVideo = true, priorityBoost = 40f) {
                try {
                    kotlinx.coroutines.delay(120)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    lowerTierSiblingCancelled.set(true)
                    throw e
                }
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        assertTrue("Execution should succeed", result)
        assertTrue("Lower priority task (VidEasy) should finish", lowerPriorityFinished.get())
        assertTrue("Higher priority task (VidLink) must NOT be cancelled despite VidEasy telemetry", higherPriorityFinished.get())
        assertTrue("Lower priority secondary task (MovieBox) should be cancelled", lowerTierSiblingCancelled.get())
    }

    @Test
    fun testOperationalProvidersStrictSortingPreservedRegardlessOfTelemetry() {
        val providers = listOf(
            Provider("vidlink", "Vidlink") { _, _, _, _, _ -> },
            Provider("HexaSU", "HexaSU") { _, _, _, _, _ -> },
            Provider("autoembed", "AutoEmbed") { _, _, _, _, _ -> },
            Provider("vidfast", "VidFast") { _, _, _, _, _ -> },
            Provider("VidEasy", "VidEasy") { _, _, _, _, _ -> },
            Provider("moviebox", "MovieBox") { _, _, _, _, _ -> }
        )

        // Give low tier providers high telemetry
        ProviderTelemetryManager.recordExecution("VidEasy", true, 100L)
        ProviderTelemetryManager.recordExecution("vidfast", true, 100L)
        ProviderTelemetryManager.recordExecution("moviebox", true, 100L)

        val sorted = providers.sortedByDescending { provider ->
            val boost = FAST_PROVIDER_BOOST[provider.id] ?: 0f
            val score = ProviderTelemetryManager.getPriorityScore(provider.id)
            if (score <= -500f) score else (boost * 100f + score)
        }

        val expectedOrder = listOf("vidlink", "HexaSU", "autoembed", "vidfast", "VidEasy", "moviebox")
        assertEquals("Operational providers must strictly preserve priority order", expectedOrder, sorted.map { it.id })
    }

    @Test
    fun testAllFiveTopTierSourcesInNonAnimeSet() {
        val topFive = listOf("vidlink", "HexaSU", "autoembed", "vidfast", "VidEasy")
        for (id in topFive) {
            assertTrue("Top-tier provider '$id' must be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains(id))
        }
    }

    @Test
    fun testAnimeFilterPreventsTopTierExecution() = kotlinx.coroutines.runBlocking {
        val animeData = StreamPlay.LinkData(
            id = 12345,
            title = "Attack on Titan",
            year = 2013,
            isAnime = true
        )
        val allProviders = buildProviders().associateBy { it.id }
        val topFive = listOf("vidlink", "HexaSU", "autoembed", "vidfast", "VidEasy")

        for (id in topFive) {
            val provider = allProviders[id]
            assertNotNull("Provider '$id' must exist", provider)
            var invoked = false
            provider?.invoke(
                animeData,
                { _ -> invoked = true },
                { _ -> invoked = true },
                "",
                ""
            )
            assertFalse("Provider '$id' must NOT execute on anime title", invoked)
        }
    }

    @Test
    fun testVideasyNullTmdbIdEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        var subEmitted = false
        StreamPlayExtractor.invokeVideasy(
            title = "Test Movie",
            tmdbId = null,
            imdbId = "tt1234567",
            year = 2024,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVideasy must not emit links when tmdbId is null", linkEmitted)
        assertFalse("invokeVideasy must not emit subtitles when tmdbId is null", subEmitted)
    }

    @Test
    fun testAutoembedNullTmdbIdEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        StreamPlayExtractor.invokeAutoembed(
            tmdbId = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeAutoembed must not emit links when tmdbId is null", linkEmitted)
    }

    @Test
    fun testHexaNullTmdbIdOrMissingEpisodeEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        var subEmitted = false
        StreamPlayExtractor.invokeHexa(
            tmdbId = null,
            season = null,
            episode = null,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeHexa must not emit links when tmdbId is null", linkEmitted)
        assertFalse("invokeHexa must not emit subs when tmdbId is null", subEmitted)

        linkEmitted = false
        subEmitted = false
        StreamPlayExtractor.invokeHexa(
            tmdbId = 12345,
            season = 1,
            episode = null,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeHexa must not emit links when TV episode is null", linkEmitted)
        assertFalse("invokeHexa must not emit subs when TV episode is null", subEmitted)
    }

    @Test
    fun testVidFastNullTmdbIdOrMissingEpisodeEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        var subEmitted = false
        StreamPlayExtractor.invokeVidFast(
            tmdbId = null,
            season = null,
            episode = null,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVidFast must not emit links when tmdbId is null", linkEmitted)
        assertFalse("invokeVidFast must not emit subs when tmdbId is null", subEmitted)

        linkEmitted = false
        subEmitted = false
        StreamPlayExtractor.invokeVidFast(
            tmdbId = 12345,
            season = 1,
            episode = null,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVidFast must not emit links when TV episode is null", linkEmitted)
        assertFalse("invokeVidFast must not emit subs when TV episode is null", subEmitted)
    }

    @Test
    fun testAutoembedMissingEpisodeEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        StreamPlayExtractor.invokeAutoembed(
            tmdbId = 12345,
            season = 1,
            episode = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeAutoembed must not emit links when TV episode is null", linkEmitted)
    }

    @Test
    fun testVideasyMissingEpisodeEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        var subEmitted = false
        StreamPlayExtractor.invokeVideasy(
            title = "Test Series",
            tmdbId = 12345,
            imdbId = "tt1234567",
            year = 2024,
            season = 1,
            episode = null,
            subtitleCallback = { subEmitted = true },
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVideasy must not emit links when TV episode is null", linkEmitted)
        assertFalse("invokeVideasy must not emit subs when TV episode is null", subEmitted)
    }

    @Test
    fun testVidlinkMissingEpisodeEarlyReturn() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        StreamPlayExtractor.invokeVidlink(
            tmdbId = 12345,
            season = 1,
            episode = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVidlink must not emit links when TV episode is null", linkEmitted)
    }

    @Test
    fun testBuildDownloadHeadersWithVidlinkHeaderWithoutUrlOrRefererArg() {
        val cdnUrl = "https://cdn.example.org/stream.m3u8"
        val headersWithVidlink = mapOf(
            "referer" to "https://vidlink.pro/embed/movie/123",
            "origin" to "https://vidlink.pro"
        )
        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(headersWithVidlink, cdnUrl, null)

        assertFalse("Referer must be stripped", downloadHeaders.containsKey("Referer") || downloadHeaders.containsKey("referer"))
        assertFalse("Origin must be stripped", downloadHeaders.containsKey("Origin") || downloadHeaders.containsKey("origin"))
        assertTrue("Mobile Cronet UA must be attached", downloadHeaders["User-Agent"]?.contains("Cronet") == true)
        assertEquals("*/*", downloadHeaders["Accept"])
    }

    @Test
    fun testGetEffectiveRefererCaseInsensitiveVidlink() {
        val cdnUrl = "https://cdn.example.org/stream.m3u8"
        val headersLower = mapOf("referer" to "https://vidlink.pro/embed/movie/123")
        val effRef = StreamLinkOptimizer.getEffectiveReferer(cdnUrl, null, headersLower)
        assertEquals("Effective referer must be empty for lowercase referer containing vidlink.pro", "", effRef)

        val headersOrigin = mapOf("origin" to "https://vidlink.pro")
        val effRefOrigin = StreamLinkOptimizer.getEffectiveReferer(cdnUrl, null, headersOrigin)
        assertEquals("Effective referer must be empty for lowercase origin containing vidlink.pro", "", effRefOrigin)
    }

    @Test
    fun testTopTierProvidersOrderInProvidersList() {
        val allProviders = buildProviders()
        val top5 = allProviders.take(5).map { it.id }
        val expected = listOf("vidlink", "HexaSU", "autoembed", "vidfast", "VidEasy")
        assertEquals("ProvidersList must define top-tier providers in strict priority order", expected, top5)
    }

    @Test
    fun testGetEffectiveRefererPreservesTopTierContractsWithExistingHeaders() {
        val conflictingHeaders = mapOf(
            "Referer" to "https://conflicting.example.com/wrapper",
            "referer" to "https://conflicting.example.com/wrapper"
        )

        // 1. AutoEmbed
        assertEquals(
            "https://player.autoembed.cc/",
            StreamLinkOptimizer.getEffectiveReferer("https://player.autoembed.cc/video.m3u8", null, conflictingHeaders)
        )
        assertEquals(
            "https://player.autoembed.cc/",
            StreamLinkOptimizer.getEffectiveReferer("https://autoembed.cc/video.m3u8", null, conflictingHeaders)
        )

        // 2. HexaSU & EmbedSU
        assertEquals(
            "https://hexa.su/",
            StreamLinkOptimizer.getEffectiveReferer("https://theemoviedb.hexa.su/stream.m3u8", null, conflictingHeaders)
        )
        assertEquals(
            "https://embed.su/",
            StreamLinkOptimizer.getEffectiveReferer("https://embed.su/stream.m3u8", null, conflictingHeaders)
        )

        // 3. VidFast
        assertEquals(
            "https://vidfast.vc/",
            StreamLinkOptimizer.getEffectiveReferer("https://vidfast.vc/stream.m3u8", null, conflictingHeaders)
        )

        // 4. VidEasy & Cineby
        assertEquals(
            "https://www.cineby.sc/",
            StreamLinkOptimizer.getEffectiveReferer("https://api.videasy.net/stream.m3u8", null, conflictingHeaders)
        )
        assertEquals(
            "https://www.cineby.sc/",
            StreamLinkOptimizer.getEffectiveReferer("https://cdn.cineby.sc/stream.m3u8", null, conflictingHeaders)
        )
    }

    @Test
    fun testInvokeVideasyOverloadWithoutSubtitleCallback() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        StreamPlayExtractor.invokeVideasy(
            title = "Inception",
            tmdbId = null,
            imdbId = "tt1375666",
            year = 2010,
            season = null,
            episode = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVideasy without subtitleCallback must return safely when tmdbId is null", linkEmitted)
    }

    @Test
    fun testVidEasyPeakstormRankAndRefererContract() {
        val videasyCdn = createLink("VidEasy", "VidEasy [CDN]", "https://moon.peakstorm.top/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyM4u = createLink("VidEasy", "VidEasy [M4UHD]", "https://moon.peakstorm.top/hls/master.m3u8", type = ExtractorLinkType.M3U8)
        val videasyNameOnly = createLink("CDN", "VidEasy stream", "https://moon.peakstorm.top/video.mp4", type = ExtractorLinkType.VIDEO)

        // Must be classified as rank 60 (VidEasy) and NOT rank 70 (VidFast)
        assertEquals("VidEasy CDN on moon.peakstorm.top must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(videasyCdn))
        assertEquals("VidEasy M4UHD on moon.peakstorm.top must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(videasyM4u))
        assertEquals("Stream with VidEasy in name on moon.peakstorm.top must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(videasyNameOnly))

        // Headers & Referer routing: VidEasy on peakstorm.top must receive player.videasy.to
        val headersWithVideasy = mapOf("Referer" to "https://player.videasy.to/", "Origin" to "https://player.videasy.to")
        val effectiveRef = StreamLinkOptimizer.getEffectiveReferer("https://moon.peakstorm.top/stream.m3u8", "https://player.videasy.to/", headersWithVideasy)
        assertEquals("VidEasy peakstorm.top stream must have player.videasy.to referer", "https://player.videasy.to/", effectiveRef)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(headersWithVideasy, "https://moon.peakstorm.top/stream.m3u8", "https://player.videasy.to/")
        assertEquals("https://player.videasy.to/", downloadHeaders["Referer"])
        assertEquals("https://player.videasy.to", downloadHeaders["Origin"])
    }

    @Test
    fun testVidFastPeakstormRankAndRefererContract() {
        val vidfastLink = createLink("VidFast", "VidFast [1080p]", "https://moon.peakstorm.top/stream.m3u8", type = ExtractorLinkType.M3U8)
        val unknownLink = createLink("UnknownSource", "Stream 1080p", "https://moon.peakstorm.top/stream.m3u8", type = ExtractorLinkType.M3U8)

        // Must be classified as rank 70 (VidFast)
        assertEquals("VidFast on moon.peakstorm.top must have rank 70", 70, StreamLinkOptimizer.getSourcePriorityRank(vidfastLink))
        assertEquals("Unknown link on moon.peakstorm.top must have rank 70", 70, StreamLinkOptimizer.getSourcePriorityRank(unknownLink))

        // Headers & Referer routing: VidFast on peakstorm.top must receive vidfast.vc
        val effectiveRef = StreamLinkOptimizer.getEffectiveReferer("https://moon.peakstorm.top/stream.m3u8", null, emptyMap())
        assertEquals("VidFast peakstorm.top stream must have vidfast.vc referer", "https://vidfast.vc/", effectiveRef)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), "https://moon.peakstorm.top/stream.m3u8", null)
        assertEquals("https://vidfast.vc/", downloadHeaders["Referer"])
        assertEquals("https://vidfast.vc", downloadHeaders["Origin"])
    }

    @Test
    fun testSpeedracelightAndVideasyToRankAndRefererContract() {
        val speedracelightLink = createLink("UnknownSource", "Stream 1080p", "https://api.speedracelight.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyToLink = createLink("UnknownSource", "Stream 1080p", "https://videasy.to/video.mp4", type = ExtractorLinkType.VIDEO)
        val playerVideasyLink = createLink("UnknownSource", "Stream 1080p", "https://player.videasy.to/stream.m3u8", type = ExtractorLinkType.M3U8)

        // Must be classified as rank 60 (VidEasy)
        assertEquals("speedracelight.com stream must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(speedracelightLink))
        assertEquals("videasy.to stream must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(videasyToLink))
        assertEquals("player.videasy.to stream must have rank 60", 60, StreamLinkOptimizer.getSourcePriorityRank(playerVideasyLink))

        // Headers & Referer routing: speedracelight.com and videasy.to must receive player.videasy.to referer and origin
        for (url in listOf("https://api.speedracelight.com/stream.m3u8", "https://videasy.to/stream.m3u8")) {
            val effRef = StreamLinkOptimizer.getEffectiveReferer(url, null, emptyMap())
            assertEquals("Effective referer for $url must be https://player.videasy.to/", "https://player.videasy.to/", effRef)

            val headers = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), url, null)
            assertEquals("https://player.videasy.to/", headers["Referer"])
            assertEquals("https://player.videasy.to", headers["Origin"])
        }
    }

    @Test
    fun testStrictOrderFiveTopTierProvidersOrderAndDomination() {
        val vidlink = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val hexasu = createLink("HexaSU", "HexaSU [1080p]", "https://hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembed = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfast = createLink("VidFast", "VidFast [1080p]", "https://moon.peakstorm.top/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasy = createLink("VidEasy", "VidEasy [1080p]", "https://moon.peakstorm.top/stream.m3u8", type = ExtractorLinkType.M3U8)

        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(vidlink)
        val rHexasu = StreamLinkOptimizer.getSourcePriorityRank(hexasu)
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(autoembed)
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(vidfast)
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(videasy)

        assertEquals(100, rVidlink)
        assertEquals(90, rHexasu)
        assertEquals(80, rAutoembed)
        assertEquals(70, rVidfast)
        assertEquals(60, rVideasy)

        assertTrue("VidLink (100) > HexaSU (90)", rVidlink > rHexasu)
        assertTrue("HexaSU (90) > AutoEmbed (80)", rHexasu > rAutoembed)
        assertTrue("AutoEmbed (80) > VidFast (70)", rAutoembed > rVidfast)
        assertTrue("VidFast (70) > VidEasy (60)", rVidfast > rVideasy)

        // Pairwise isBetterThan transitive checks
        assertTrue("VidLink beats HexaSU", StreamLinkOptimizer.isBetterThan(vidlink, hexasu))
        assertTrue("HexaSU beats AutoEmbed", StreamLinkOptimizer.isBetterThan(hexasu, autoembed))
        assertTrue("AutoEmbed beats VidFast", StreamLinkOptimizer.isBetterThan(autoembed, vidfast))
        assertTrue("VidFast beats VidEasy", StreamLinkOptimizer.isBetterThan(vidfast, videasy))
    }

    @Test
    fun testVidFastTokenRegexHardening() {
        val payloadWithToken = """\"token\":\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\""""
        val payloadWithEn = """\"en\":\"ZGF0YXRlc3RlbmNyeXB0ZWQxMjM0NQ==\""""
        val payloadUnescapedToken = """{"token":"eyJhbGciOiJIUzI1NiJ9"}"""
        val payloadUnescapedEn = """{"en":"dGVzdDEyMzQ1"}"""

        val regex = Regex("""(?:\\\"|")(?:en|token)(?:\\\"|")\s*:\s*(?:\\\"|")([^"\\]+)(?:\\\"|)""")
        val fallbackRegex = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""")

        val matchToken = regex.find(payloadWithToken) ?: fallbackRegex.find(payloadWithToken)
        assertNotNull("Must match payload with token key", matchToken)
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", matchToken?.groupValues?.get(1))

        val matchEn = regex.find(payloadWithEn) ?: fallbackRegex.find(payloadWithEn)
        assertNotNull("Must match payload with en key", matchEn)
        assertEquals("ZGF0YXRlc3RlbmNyeXB0ZWQxMjM0NQ==", matchEn?.groupValues?.get(1))

        val matchUnescapedToken = regex.find(payloadUnescapedToken) ?: fallbackRegex.find(payloadUnescapedToken)
        assertNotNull("Must match unescaped json with token key", matchUnescapedToken)
        assertEquals("eyJhbGciOiJIUzI1NiJ9", matchUnescapedToken?.groupValues?.get(1))

        val matchUnescapedEn = regex.find(payloadUnescapedEn) ?: fallbackRegex.find(payloadUnescapedEn)
        assertNotNull("Must match unescaped json with en key", matchUnescapedEn)
        assertEquals("dGVzdDEyMzQ1", matchUnescapedEn?.groupValues?.get(1))
    }

    @Test
    fun testHexaSUConstantsAndFlixerSUMirror() {
        assertEquals("https://theemoviedb.hexa.su", StreamPlay.hexaSU)
        assertEquals("https://flixer.su", StreamPlay.flixerSU)
        assertEquals("https://embed.su", StreamPlay.embedSU)
        assertEquals("https://api.speedracelight.com", StreamPlay.videasyAPI)
        assertEquals("https://api.videasy.net", StreamPlay.videasyFallbackAPI)

        val flixerLink = createLink("HexaSU", "HexaSU [1080p]", "https://flixer.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        assertEquals("flixer.su stream must be classified as rank 90", 90, StreamLinkOptimizer.getSourcePriorityRank(flixerLink))

        val effRef = StreamLinkOptimizer.getEffectiveReferer("https://flixer.su/stream.m3u8", null, emptyMap())
        assertEquals("https://flixer.su/", effRef)

        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), "https://flixer.su/stream.m3u8", null)
        assertEquals("https://flixer.su/", downloadHeaders["Referer"])
        assertEquals("https://flixer.su", downloadHeaders["Origin"])
    }

    @Test
    fun testAutoembedFaultToleranceAndGracefulTimeout() = kotlinx.coroutines.runBlocking {
        // invokeAutoembed must gracefully complete even with non-existent IDs without blocking or throwing unhandled errors
        var linkEmitted = false
        StreamPlayExtractor.invokeAutoembed(
            tmdbId = 999999999,
            season = null,
            episode = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeAutoembed must not emit links for non-existent content", linkEmitted)
    }

    @Test
    fun testVidSrcRankAndHierarchyPlacement() {
        val vidlinkLink = createLink("Vidlink", "Vidlink [1080p]", "https://vidlink.pro/stream/master.m3u8", type = ExtractorLinkType.M3U8)
        val hexaLink = createLink("HexaSU", "HexaSU [1080p]", "https://theemoviedb.hexa.su/stream.m3u8", type = ExtractorLinkType.M3U8)
        val autoembedLink = createLink("AutoEmbed", "AutoEmbed [1080p]", "https://player.autoembed.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidfastLink = createLink("VidFast", "VidFast [1080p]", "https://vidfast.pro/stream.m3u8", type = ExtractorLinkType.M3U8)
        val videasyLink = createLink("VidEasy", "VidEasy [1080p]", "https://api.videasy.net/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidsrcLink = createLink("VidSrc", "VidSrc Server V1 [1080p]", "https://shadowlandschronicles.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidsrcCcLink = createLink("VidSrc CC", "VidSrc CC [1080p]", "https://vidsrc.cc/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidsrcToLink = createLink("VidSrc To", "VidSrc To [1080p]", "https://vidsrc.to/stream.m3u8", type = ExtractorLinkType.M3U8)
        val vidsrcMeLink = createLink("VidSrc Me", "VidSrc Me [1080p]", "https://vidsrc.me/stream.m3u8", type = ExtractorLinkType.M3U8)
        val cloudnestraLink = createLink("VidSrc", "VidSrc Server V2 [1080p]", "https://cloudnestra.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val pixelPioneerLink = createLink("VidSrc", "VidSrc Server V3 [1080p]", "https://thepixelpioneer.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val putgateLink = createLink("VidSrc", "VidSrc Server V4 [1080p]", "https://putgate.org/stream.m3u8", type = ExtractorLinkType.M3U8)
        val whisperingPinesLink = createLink("VidSrc", "VidSrc Server V5 [1080p]", "https://whisperingpineslifestyle.com/stream.m3u8", type = ExtractorLinkType.M3U8)
        val movieboxLink = createLink("MovieBox", "MovieBox [1080p]", "https://moviebox.example/stream.mp4")
        val rivestreamLink = createLink("RiveStream", "RiveStream [1080p]", "https://rivestream.example/stream.mp4")
        val vidrockLink = createLink("Vidrock", "Vidrock [1080p]", "https://vidrock.example/stream.mp4")
        val moviesapiLink = createLink("MoviesApi", "MoviesApi [1080p]", "https://moviesapi.example/stream.mp4")
        val secondaryLink = createLink("UnknownProvider", "Scraped Link [1080p]", "https://secondary.example.com/video.mp4")

        val rVidlink = StreamLinkOptimizer.getSourcePriorityRank(vidlinkLink)
        val rHexa = StreamLinkOptimizer.getSourcePriorityRank(hexaLink)
        val rAutoembed = StreamLinkOptimizer.getSourcePriorityRank(autoembedLink)
        val rVidfast = StreamLinkOptimizer.getSourcePriorityRank(vidfastLink)
        val rVideasy = StreamLinkOptimizer.getSourcePriorityRank(videasyLink)
        val rVidSrc = StreamLinkOptimizer.getSourcePriorityRank(vidsrcLink)
        val rVidSrcCc = StreamLinkOptimizer.getSourcePriorityRank(vidsrcCcLink)
        val rVidSrcTo = StreamLinkOptimizer.getSourcePriorityRank(vidsrcToLink)
        val rVidSrcMe = StreamLinkOptimizer.getSourcePriorityRank(vidsrcMeLink)
        val rCloudnestra = StreamLinkOptimizer.getSourcePriorityRank(cloudnestraLink)
        val rPixelPioneer = StreamLinkOptimizer.getSourcePriorityRank(pixelPioneerLink)
        val rPutgate = StreamLinkOptimizer.getSourcePriorityRank(putgateLink)
        val rWhisperingPines = StreamLinkOptimizer.getSourcePriorityRank(whisperingPinesLink)
        val rMoviebox = StreamLinkOptimizer.getSourcePriorityRank(movieboxLink)
        val rRivestream = StreamLinkOptimizer.getSourcePriorityRank(rivestreamLink)
        val rVidrock = StreamLinkOptimizer.getSourcePriorityRank(vidrockLink)
        val rMoviesapi = StreamLinkOptimizer.getSourcePriorityRank(moviesapiLink)
        val rSecondary = StreamLinkOptimizer.getSourcePriorityRank(secondaryLink)

        // Exact rank validation
        assertEquals("VidLink rank is 100", 100, rVidlink)
        assertEquals("HexaSU rank is 90", 90, rHexa)
        assertEquals("AutoEmbed rank is 80", 80, rAutoembed)
        assertEquals("VidFast rank is 70", 70, rVidfast)
        assertEquals("VidEasy rank is 60", 60, rVideasy)
        assertEquals("VidSrc rank must be exactly 55 (King of Fallbacks)", 55, rVidSrc)
        assertEquals("VidSrc CC rank must be exactly 55", 55, rVidSrcCc)
        assertEquals("VidSrc To rank must be exactly 55", 55, rVidSrcTo)
        assertEquals("VidSrc Me rank must be exactly 55", 55, rVidSrcMe)
        assertEquals("Cloudnestra mirror rank must be exactly 55", 55, rCloudnestra)
        assertEquals("PixelPioneer mirror rank must be exactly 55", 55, rPixelPioneer)
        assertEquals("Putgate mirror rank must be exactly 55", 55, rPutgate)
        assertEquals("WhisperingPines mirror rank must be exactly 55", 55, rWhisperingPines)
        assertEquals("MovieBox rank is 50", 50, rMoviebox)
        assertEquals("RiveStream rank is 40", 40, rRivestream)
        assertEquals("Vidrock rank is 30", 30, rVidrock)
        assertEquals("MoviesApi rank is 20", 20, rMoviesapi)
        assertEquals("Secondary rank is 0", 0, rSecondary)

        // Strict monotonicity check: VidLink (100) > HexaSU (90) > AutoEmbed (80) > VidFast (70) > VidEasy (60) > VidSrc (55) > MovieBox (50) > RiveStream (40) > Vidrock (30) > MoviesAPI (20)
        assertTrue(rVidlink > rHexa)
        assertTrue(rHexa > rAutoembed)
        assertTrue(rAutoembed > rVidfast)
        assertTrue(rVidfast > rVideasy)
        assertTrue(rVideasy > rVidSrc)
        assertTrue(rVidSrc > rMoviebox)
        assertTrue(rMoviebox > rRivestream)
        assertTrue(rRivestream > rVidrock)
        assertTrue(rVidrock > rMoviesapi)
        assertTrue(rMoviesapi > rSecondary)

        // FAST_PROVIDER_BOOST checks
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrc"] ?: 0f, 0.001f)
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrcxyz"] ?: 0f, 0.001f)
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrccc"] ?: 0f, 0.001f)
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrcto"] ?: 0f, 0.001f)
        assertEquals(55f, FAST_PROVIDER_BOOST["vidsrcme"] ?: 0f, 0.001f)
        assertEquals(50f, FAST_PROVIDER_BOOST["moviebox"] ?: 0f, 0.001f)
        assertEquals(40f, FAST_PROVIDER_BOOST["rivestream"] ?: 0f, 0.001f)
        assertEquals(30f, FAST_PROVIDER_BOOST["vidrock"] ?: 0f, 0.001f)
        assertEquals(20f, FAST_PROVIDER_BOOST["moviesapi"] ?: 0f, 0.001f)

        // SpeculativePipeliner tier check
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidsrc"])
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidsrcxyz"])
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidsrccc"])
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidsrcto"])
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidsrcme"])
        assertEquals(LatencyTier.TIER_1, SpeculativePipeliner.STATIC_COLD_START_TIERS["vidrock"])
    }

    @Test
    fun testVidSrcGracefulFailureAndFaultIsolation() = kotlinx.coroutines.runBlocking {
        var linkEmitted = false
        StreamPlayExtractor.invokeVidSrc(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            callback = { linkEmitted = true }
        )
        assertFalse("invokeVidSrc must complete gracefully without emitting links for nonexistent id", linkEmitted)

        var toEmitted = false
        StreamPlayExtractor.invokeVidSrcTo(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            callback = { toEmitted = true }
        )
        assertFalse("invokeVidSrcTo must complete gracefully without emitting links for nonexistent id", toEmitted)

        var ccEmitted = false
        StreamPlayExtractor.invokeVidSrcCc(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            callback = { ccEmitted = true }
        )
        assertFalse("invokeVidSrcCc must complete gracefully without emitting links for nonexistent id", ccEmitted)

        var xyzEmitted = false
        StreamPlayExtractor.invokeVidSrcXyz(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            callback = { xyzEmitted = true }
        )
        assertFalse("invokeVidSrcXyz must complete gracefully without emitting links for nonexistent id", xyzEmitted)
    }

    @Test
    fun testVidEasyPeakstormStreamOptimizerProtection() {
        // Raw VidEasy link with empty initial referer and headers
        val videasyLink = createLink(
            source = "VidEasy",
            name = "VidEasy [CDN]",
            url = "https://moon.peakstorm.top/vd/abc/index-s1080p-v1-a1.m3u8",
            referer = "",
            headers = emptyMap(),
            type = ExtractorLinkType.M3U8
        )

        val optimized = StreamLinkOptimizer.optimize(videasyLink)

        // Must retain VidEasy rank 60
        assertEquals(60, StreamLinkOptimizer.getSourcePriorityRank(optimized))

        // Must NOT be hijacked by VidFast referer (vidfast.vc)
        assertEquals("https://player.videasy.to/", optimized.referer)
        assertEquals("https://player.videasy.to/", optimized.headers["Referer"])
        assertEquals("https://player.videasy.to", optimized.headers["Origin"])
        assertNotEquals("https://vidfast.vc/", optimized.referer)
        assertNotEquals("https://vidfast.vc/", optimized.headers["Referer"])
    }

    @Test
    fun testVidFastPeakstormStreamOptimizerRouting() {
        // Raw VidFast link with empty initial referer and headers
        val vidfastLink = createLink(
            source = "VidFast",
            name = "VidFast [1080p]",
            url = "https://moon.peakstorm.top/vd/xyz/index-s1080p-v1-a1.m3u8",
            referer = "",
            headers = emptyMap(),
            type = ExtractorLinkType.M3U8
        )

        val optimized = StreamLinkOptimizer.optimize(vidfastLink)

        // Must have VidFast rank 70
        assertEquals(70, StreamLinkOptimizer.getSourcePriorityRank(optimized))

        // Must be routed to vidfast.vc
        assertEquals("https://vidfast.vc/", optimized.referer)
        assertEquals("https://vidfast.vc/", optimized.headers["Referer"])
        assertEquals("https://vidfast.vc", optimized.headers["Origin"])
    }

    @Test
    fun testBuildDownloadHeadersAndEffectiveRefererWithSource() {
        val peakstormUrl = "https://moon.peakstorm.top/stream.m3u8"

        // Passing source = "VidEasy"
        val videasyRef = StreamLinkOptimizer.getEffectiveReferer(peakstormUrl, null, emptyMap(), source = "VidEasy")
        assertEquals("https://player.videasy.to/", videasyRef)

        val videasyHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), peakstormUrl, null, source = "VidEasy")
        assertEquals("https://player.videasy.to/", videasyHeaders["Referer"])
        assertEquals("https://player.videasy.to", videasyHeaders["Origin"])

        // Passing source = "VidFast"
        val vidfastRef = StreamLinkOptimizer.getEffectiveReferer(peakstormUrl, null, emptyMap(), source = "VidFast")
        assertEquals("https://vidfast.vc/", vidfastRef)

        val vidfastHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), peakstormUrl, null, source = "VidFast")
        assertEquals("https://vidfast.vc/", vidfastHeaders["Referer"])
        assertEquals("https://vidfast.vc", vidfastHeaders["Origin"])
    }

    @Test
    fun testCleanSubtitleLabelEncodingAndEntityDecoding() {
        // 1. HTML named entities
        assertEquals("Spanish", cleanSubtitleLabel("Espa&ntilde;ol"))
        assertEquals("French", cleanSubtitleLabel("Fran&ccedil;ais"))
        assertEquals("Spanish [Forced]", cleanSubtitleLabel("Espa&ntilde;ol [Forced]"))
        assertEquals("Español & English [Forced]", cleanSubtitleLabel("Espa&ntilde;ol &amp; English [Forced]"))

        // 2. Numeric entities (decimal & hex)
        assertEquals("Spanish", cleanSubtitleLabel("Espa&#241;ol"))
        assertEquals("French", cleanSubtitleLabel("Fran&#xE7;ais"))
        assertEquals("English", cleanSubtitleLabel("English"))
        assertEquals("It's English", cleanSubtitleLabel("It&#39;s English"))

        // 3. BOM and zero-width spaces removal
        assertEquals("English", cleanSubtitleLabel("\uFEFF\u200BEnglish\u200C\u200D"))
        assertEquals("English [SDH]", cleanSubtitleLabel("\uFEFFEnglish (SDH)"))

        // 4. ISO language codes resolution with modifier preservation
        assertEquals("English [Forced]", cleanSubtitleLabel("en [forced]"))
        assertEquals("Spanish [SDH]", cleanSubtitleLabel("spa (sdh)"))
        assertEquals("French [CC]", cleanSubtitleLabel("fre [cc]"))
        assertEquals("German", cleanSubtitleLabel("de"))
        assertEquals("Italian", cleanSubtitleLabel("ita"))

        // 5. Blank / null fallback
        assertEquals("English", cleanSubtitleLabel(null))
        assertEquals("English", cleanSubtitleLabel(""))
        assertEquals("English", cleanSubtitleLabel("   "))
    }

    @Test
    fun testVidSrcCdnRefererAndOriginHeadersRouting() {
        val testCases = listOf(
            "https://vidsrc.cc/stream/sub/index.m3u8" to "https://vidsrc.cc/",
            "https://vidsrc.to/stream/master.m3u8" to "https://vidsrc.to/",
            "https://vidsrc.xyz/stream/playlist.m3u8" to "https://vidsrc.xyz/",
            "https://vidsrc.me/stream/master.m3u8" to "https://vidsrc.me/",
            "https://cloudnestra.com/stream/file.mp4" to "https://cloudnestra.com/",
            "https://thepixelpioneer.com/hls/test.m3u8" to "https://thepixelpioneer.com/",
            "https://shadowlandschronicles.com/video.m3u8" to "https://shadowlandschronicles.com/",
            "https://putgate.org/embed/123.m3u8" to "https://putgate.org/",
            "https://whisperingpineslifestyle.com/hls/index.m3u8" to "https://whisperingpineslifestyle.com/"
        )

        for ((url, expectedReferer) in testCases) {
            val effRef = StreamLinkOptimizer.getEffectiveReferer(url, null, emptyMap(), source = "VidSrc")
            assertEquals("Effective Referer mismatch for $url", expectedReferer, effRef)

            val dlHeaders = StreamLinkOptimizer.buildDownloadHeaders(emptyMap(), url, null, source = "VidSrc")
            assertEquals("Download Referer mismatch for $url", expectedReferer, dlHeaders["Referer"])
            assertEquals("Download Origin mismatch for $url", expectedReferer.removeSuffix("/"), dlHeaders["Origin"])
        }
    }

    @Test
    fun testStreamDeduplicatorDropsBlankUrlsAndKeys() {
        var emittedCount = 0
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emittedCount++ }

        // 1. Blank URL should be dropped
        val blankLink = createLink(source = "VidSrc", name = "Blank", url = "   ")
        deduplicator.emitDetailed(blankLink)
        assertEquals(0, emittedCount)

        // 2. Valid link emits
        val validLink1 = createLink(source = "VidSrc", name = "Valid 1", url = "https://cdn.example.com/stream.m3u8?token=123")
        deduplicator.emitDetailed(validLink1)
        assertEquals(1, emittedCount)

        // 3. Duplicate canonical key (same host and path, different token) is dropped
        val validLink2 = createLink(source = "VidSrc", name = "Valid 2", url = "https://cdn.example.com/stream.m3u8?token=456")
        deduplicator.emitDetailed(validLink2)
        assertEquals(1, emittedCount)

        // 4. Distinct stream emits
        val validLink3 = createLink(source = "VidSrc", name = "Valid 3", url = "https://cdn.example.com/stream_720.m3u8")
        deduplicator.emitDetailed(validLink3)
        assertEquals(2, emittedCount)
    }

    @Test
    fun testFullProvidersHierarchyOrdering() {
        val expectedTiers = listOf(
            "vidlink" to 100,
            "HexaSU" to 90,
            "autoembed" to 80,
            "vidfast" to 70,
            "VidEasy" to 60,
            "vidsrc" to 55,
            "moviebox" to 50,
            "rivestream" to 40,
            "vidrock" to 30,
            "moviesapi" to 20
        )

        for (i in 0 until expectedTiers.size - 1) {
            val (higherId, higherRank) = expectedTiers[i]
            val (lowerId, lowerRank) = expectedTiers[i + 1]

            assertTrue(
                "Rank of $higherId ($higherRank) must be greater than $lowerId ($lowerRank)",
                higherRank > lowerRank
            )
        }
    }

    @Test
    fun testSourceAwareEffectiveReferer() {
        // 1. VidLink source strips referer unconditionally
        val vidlinkRef = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://cf.stream.example/master.m3u8",
            referer = "https://arbitrary.com/",
            headers = emptyMap(),
            source = "VidLink",
            name = "VidLink [HLS]"
        )
        assertEquals("", vidlinkRef)

        // 2. Hexa source sets https://hexa.su/
        val hexaRef = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://cdn.example.com/hls.m3u8",
            referer = null,
            headers = emptyMap(),
            source = "HexaSU",
            name = "HexaSU Server 1"
        )
        assertEquals("https://hexa.su/", hexaRef)

        // 3. AutoEmbed source sets https://player.autoembed.cc/
        val autoembedRef = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://stream.example/play.mp4",
            referer = null,
            headers = emptyMap(),
            source = "AutoEmbed",
            name = "AutoEmbed"
        )
        assertEquals("https://player.autoembed.cc/", autoembedRef)

        // 4. VidFast source sets https://vidfast.vc/
        val vidfastRef = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://node.example/live.m3u8",
            referer = null,
            headers = emptyMap(),
            source = "VidFast",
            name = "VidFast [Server 1]"
        )
        assertEquals("https://vidfast.vc/", vidfastRef)

        // 5. VidSrc domains return respective VidSrc host
        val vidsrcRef = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://vidsrc.xyz/embed/movie/550",
            referer = null,
            headers = emptyMap()
        )
        assertEquals("https://vidsrc.xyz/", vidsrcRef)
    }

    @Test
    fun testCandidateLinksCountInEarlySatisfactionController() {
        val controller = EarlySatisfactionController()
        assertEquals(0, controller.getCandidateLinksCount())
        assertEquals(0, controller.getLinksCount())

        val link1 = createLink(source = "VidLink", name = "Link 1", url = "https://cdn.com/1.m3u8")
        val link2 = createLink(source = "HexaSU", name = "Link 2", url = "https://cdn.com/1.m3u8") // duplicate url

        controller.onCandidateLink(link1)
        controller.onLinkEmitted(link1)
        assertEquals(1, controller.getCandidateLinksCount())
        assertEquals(1, controller.getLinksCount())

        // Link 2 is a candidate link but dropped by deduplicator
        controller.onCandidateLink(link2)
        assertEquals(2, controller.getCandidateLinksCount())
        assertEquals(1, controller.getLinksCount())

        controller.reset()
        assertEquals(0, controller.getCandidateLinksCount())
        assertEquals(0, controller.getLinksCount())
    }

    @Test
    fun testVidSrcOverloadsAndSubtitleSupport() = runBlocking {
        // Test invocation of invokeVidSrcTo with subtitleCallback
        var subReceived = false
        var linkReceived = false
        StreamPlayExtractor.invokeVidSrcTo(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            subtitleCallback = { subReceived = true },
            callback = { linkReceived = true },
            tmdbId = null
        )
        assertFalse(linkReceived)

        // Test invokeVidSrcCc with subtitleCallback
        StreamPlayExtractor.invokeVidSrcCc(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            subtitleCallback = { subReceived = true },
            callback = { linkReceived = true },
            tmdbId = null
        )
        assertFalse(linkReceived)

        // Test invokeVidSrcXyz with subtitleCallback
        StreamPlayExtractor.invokeVidSrcXyz(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            subtitleCallback = { subReceived = true },
            callback = { linkReceived = true },
            tmdbId = null
        )
        assertFalse(linkReceived)

        // Test invokeVidSrc (Unified) with subtitleCallback
        StreamPlayExtractor.invokeVidSrc(
            id = "tt9999999999nonexistent",
            season = null,
            episode = null,
            subtitleCallback = { subReceived = true },
            callback = { linkReceived = true },
            tmdbId = null
        )
        assertFalse(linkReceived)
    }

    @Test
    fun testVidEasyMediaTitleFallbackGraceful() = runBlocking {
        var linkEmitted = false
        // Calling invokeVideasy with null/blank title should use "Media" fallback and not crash
        StreamPlayExtractor.invokeVideasy(
            title = null,
            tmdbId = 999999999,
            imdbId = null,
            year = null,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { linkEmitted = true }
        )
        assertFalse(linkEmitted)
    }
}
