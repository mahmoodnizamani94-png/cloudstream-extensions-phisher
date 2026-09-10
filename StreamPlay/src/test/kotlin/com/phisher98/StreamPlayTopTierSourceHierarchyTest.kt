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
}
