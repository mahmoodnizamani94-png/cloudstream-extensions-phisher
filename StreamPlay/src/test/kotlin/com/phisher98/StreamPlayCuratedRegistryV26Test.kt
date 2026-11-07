package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.animexParseSourceJson
import com.phisher98.StreamPlayExtractor.cinebyParseDecryptedResult
import com.phisher98.StreamPlayExtractor.cinesrcParseDecryptedResult
import com.phisher98.StreamPlayExtractor.cinesrcSolveStage1
import com.phisher98.StreamPlayExtractor.cinesrcSolveStage2
import com.phisher98.StreamPlayExtractor.megaplayExtractFileId
import com.phisher98.StreamPlayExtractor.megaplayParseSourcesPayload
import com.phisher98.StreamPlayExtractor.parseHlsMasterVariants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * Acceptance suite for the curated v26 SOTA StreamPlay source registry.
 *
 * It pins down, as executable contracts:
 *  1. exactly which sources may surface in the UI (VidLink, VidRift, Cineby, CineSrc, VidFast,
 *     MegaPlay, MeowTV, AniZone, AnimePahe, Animex) and their strict launch hierarchy,
 *  2. the ranking of those sources and of the CDN hosts they serve from, plus VidRock's and
 *     VidSrc's complete decommissioning,
 *  3. the settings migration that removes every decommissioned provider (including VidRock and
 *     VidSrc) from an existing install while enabling the v26 SOTA sources,
 *  4. the bounded ordering windows of [StreamLinkOptimizer.PriorityStreamDispatcher],
 *  5. known-answer vectors for the offline VidLink token encryptor,
 *  6. protocol parsers for Cineby, CineSrc (CSP4 PoW + Nebula/Wave/Surge), MegaPlay, Animex,
 *     AniZone and AnimePahe (dedicated v26 anime suite), and HLS Master Variant + Size Estimation,
 *  7. the exception gates that keep the Movie/TV, Anime, and Universal segments of the registry
 *     properly partitioned, and
 *  8. the stream-only download-exclusion contract (`DOWNLOAD_EXCLUDED_PROVIDER_IDS = setOf("vidfast")`).
 */
class StreamPlayCuratedRegistryV26Test {

    private val curatedIds = setOf(
        "vidlink",
        "meowtv",
        "cineby",
        "cinesrc",
        "vidfast",
        "megaplay",
        "vidrift",
        "anizone",
        "animepahe",
        "animex"
    )

    /** Authoritative launch hierarchy: strictly descending boost per provider id. */
    private val boostByProvider = linkedMapOf(
        "vidlink" to 100f,
        "meowtv" to 98f,
        "cineby" to 95f,
        "cinesrc" to 94f,
        "vidfast" to 93f,
        "megaplay" to 92f,
        "vidrift" to 91f,
        "anizone" to 89f,
        "animepahe" to 88f,
        "animex" to 87f
    )

    /**
     * The honest cold-start tier of every curated source.
     *
     * AniZone is one search page plus one episode page over plain HTTP, so it starts with the fast
     * group. AnimePahe is search -> release -> play -> Kwik and may have to answer a Cloudflare
     * challenge first, so it is honestly classified slower instead of pretending to be instant.
     */
    private val tierByProvider = mapOf(
        "vidlink" to LatencyTier.TIER_1,
        "meowtv" to LatencyTier.TIER_1,
        "cineby" to LatencyTier.TIER_1,
        "cinesrc" to LatencyTier.TIER_1,
        "vidfast" to LatencyTier.TIER_1,
        "megaplay" to LatencyTier.TIER_1,
        "vidrift" to LatencyTier.TIER_1,
        "anizone" to LatencyTier.TIER_1,
        "animepahe" to LatencyTier.TIER_2,
        "animex" to LatencyTier.TIER_1
    )

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

    private fun rankOf(source: String, name: String, url: String): Int =
        StreamLinkOptimizer.getSourcePriorityRank(createLink(source, name, url))

    // =========================================================================
    // 1. REGISTRY SHAPE
    // =========================================================================

    @Test
    fun testRegistryContainsExactlyTheCuratedSources() {
        val providers = buildProviders()
        assertEquals("Registry must expose exactly the curated sources", curatedIds, providers.map { it.id }.toSet())
        assertEquals(curatedIds, DEFAULT_TOP_TIER_PROVIDERS)

        val byId = providers.associateBy { it.id }
        assertEquals("VidLink", byId.getValue("vidlink").name)
        assertEquals("MeowTV", byId.getValue("meowtv").name)
        assertEquals("Cineby", byId.getValue("cineby").name)
        assertEquals("CineSrc", byId.getValue("cinesrc").name)
        assertEquals("VidFast", byId.getValue("vidfast").name)
        assertEquals("MegaPlay", byId.getValue("megaplay").name)
        assertEquals("VidRift", byId.getValue("vidrift").name)
        assertEquals("AniZone", byId.getValue("anizone").name)
        assertEquals("AnimePahe", byId.getValue("animepahe").name)
        assertEquals("Animex", byId.getValue("animex").name)
        providers.forEach { assertEquals(ProviderKind.VIDEO, it.kind) }
    }

    @Test
    fun testEveryDecommissionedProviderIsAbsentFromEverySurface() {
        val providerIds = buildProviders().map { it.id }.toSet()

        for (removed in REMOVED_PROVIDER_IDS) {
            assertFalse("$removed must not be registered", providerIds.contains(removed))
            if (removed != "Animex") {
                assertNull("$removed must not have a cold-start boost", FAST_PROVIDER_BOOST[removed])
                assertNull(
                    "$removed must not have a cold-start latency tier",
                    SpeculativePipeliner.STATIC_COLD_START_TIERS[removed]
                )
            }
            assertFalse("$removed must not be a default top tier source", DEFAULT_TOP_TIER_PROVIDERS.contains(removed))
        }

        // VidRock, VidSrc, and VidSrcSU are explicitly decommissioned.
        listOf(
            "vidrock", "Vidrock", "VidRock",
            "vidsrc", "Vidsrc", "VidSrc", "vidsrc.pm",
            "vidsrcsu", "Vidsrcsu", "VidSrcSU", "vidsrc.su", "themoviedb.vidsrc.su", "theemoviedb.hexa.su", "dragonballzfans",
            "rivestream", "videasy", "superstream", "vaplayer", "vidcore", "vidnest",
            "vidup", "hexasu", "autoembed", "videm", "animegg"
        ).forEach {
            assertFalse("$it must not be registered", providerIds.contains(it))
            assertTrue("$it must be listed in REMOVED_PROVIDER_IDS", REMOVED_PROVIDER_IDS.contains(it))
        }

        // The v26 additions are live members of the curated set.
        curatedIds.forEach { id ->
            assertTrue("$id must be registered", providerIds.contains(id))
            assertFalse("$id must not be listed as removed", REMOVED_PROVIDER_IDS.contains(id))
        }
    }

    @Test
    fun testRankingHierarchyIsStrictAndMonotonic() {
        boostByProvider.forEach { (id, boost) ->
            assertEquals("$id must launch at boost $boost", boost, FAST_PROVIDER_BOOST.getValue(id))
        }

        val boosts = boostByProvider.keys.map { FAST_PROVIDER_BOOST.getValue(it) }
        assertEquals("Boosts must be strictly descending", boosts.sortedDescending(), boosts)
        assertEquals("Every curated provider must have a unique boost", boosts.size, boosts.toSet().size)

        // Every curated provider must be classified at its honest cold-start tier.
        curatedIds.forEach { id ->
            assertEquals(
                "$id must be classified at its declared cold-start tier",
                tierByProvider.getValue(id),
                SpeculativePipeliner.classifyProvider(id)
            )
        }
    }

    @Test
    fun testSourcePriorityRankCoversLabelsAndCdnHosts() {
        assertEquals(100, rankOf("VidLink", "VidLink [1080p]", "https://cdn.example/a.mp4"))
        assertEquals(98, rankOf("MeowTV", "MeowTV [1080p]", "https://cdn.example/m.m3u8"))
        assertEquals(95, rankOf("Cineby", "Cineby Boise [1080p]", "https://moon.goldenkite.top/x.m3u8"))
        assertEquals(94, rankOf("CineSrc", "CineSrc Nebula [720p]", "https://nebula.bright67.online/hls2/master.txt"))
        assertEquals(93, rankOf("VidFast", "VidFast [1080p]", "https://cdn.example/c.m3u8"))
        assertEquals(92, rankOf("MegaPlay", "MegaPlay [Sub] [Original]", "https://fetch.nexabloom.top/e.m3u8"))
        assertEquals(91, rankOf("VidRift", "VidRift Titan [720p]", "https://img1.hscow.com/v4/720/index_287.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://edge.reelvault.click/proxy?u=abc"))
        assertEquals(91, rankOf("Unknown", "stream", "https://media.reelvault.click/vexon/media/index.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://bigbang.cymru/media/master.m3u8.png"))
        assertEquals(91, rankOf("Unknown", "stream", "https://i-arch-400.deton444eik.com/stream2/index.m3u8"))
        assertEquals(89, rankOf("AniZone", "AniZone [Sub] [Original]", "https://cdn.example/z.m3u8"))
        assertEquals(88, rankOf("AnimePahe", "AnimePahe [Sub] [Original]", "https://cdn.example/p.m3u8"))
        assertEquals(87, rankOf("Animex", "Animex [Sub] [Original]", "https://cdn.example/x.m3u8"))

        // Both VidSrc and VidSrcSU are decommissioned and score 0.
        assertEquals(0, rankOf("VidSrcSU", "VidSrcSU HLS", "https://cdn.example/su.m3u8"))
        assertEquals(0, rankOf("VidSrc", "VidSrc HLS", "https://lit.cheaptruckrepairs.cc/b.m3u8"))
        assertEquals(0, rankOf("VidSrc", "VidSrc HLS", "https://netoda.tech/f.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://vidsrc.pm/vidora/stream.m3u8"))
        assertEquals(0, rankOf("VidSrc", "VidSrc 720p", "https://mov2day.xyz/proxy?url=https%3A%2F%2Fbcdn.hakunaymatata.com%2Fbt%2F720.mp4"))

        // Every provider also ranks on the CDN hosts it actually serves from.
        assertEquals(95, rankOf("Unknown", "stream", "https://moon.goldenkite.top/file2/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://sun.paleoak.top/file2/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://e2e.majorplay.net/hls/master.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://nebula.bright67.online/hls2/01/master.txt"))
        assertEquals(94, rankOf("Unknown", "stream", "https://ice.bright67.online/Deadpool/720p/playlist.jpg"))
        assertEquals(94, rankOf("Unknown", "stream", "https://lightspeed.st/hls2/05/00038/abc/master.txt"))
        assertEquals(94, rankOf("Unknown", "stream", "https://cinesrc.st/api/v2/stream"))
        assertEquals(0, rankOf("Unknown", "stream", "https://vidsrc.buzz/_stream?id=abc"))
        assertEquals(93, rankOf("Unknown", "stream", "https://vidfast.vc/e/abc.m3u8"))
        assertEquals(93, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://theemoviedb.hexa.su/api/tmdb/movie/550/images"))
        assertEquals(92, rankOf("Unknown", "stream", "https://fetch.nexabloom.top/hls/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://megaplay.buzz/stream/s-2/123/sub"))
        assertEquals(98, rankOf("Unknown", "stream", "https://img1.hscow.com/thumb.jpg"))
        assertEquals(98, rankOf("Unknown", "stream", "https://cdn.vidjoy.org/hls/master.m3u8"))
        assertEquals(98, rankOf("Unknown", "stream", "https://i-arch-400.krico443jez.com/x.m3u8"))
        assertEquals(89, rankOf("Unknown", "stream", "https://anizone.to/anime/eu8bdgty/1"))
        assertEquals(89, rankOf("Unknown", "stream", "https://suzaku.xin-cdn.xyz/abc/master.m3u8"))
        assertEquals(89, rankOf("Unknown", "stream", "https://seiryuu.vid-cdn.xyz/abc/master.m3u8"))
        assertEquals(88, rankOf("Unknown", "stream", "https://animepahe.pw/play/abc/def"))
        assertEquals(88, rankOf("Unknown", "stream", "https://vault-01.uwucdn.top/stream/abc/uwu.m3u8"))
        assertEquals(88, rankOf("Unknown", "stream", "https://vault-12.owocdn.top/stream/abc/uwu.m3u8"))
        assertEquals(88, rankOf("Unknown", "stream", "https://kwik.si/e/abc"))
        assertEquals(87, rankOf("Unknown", "stream", "https://animex.one/embed/x"))
        assertEquals(87, rankOf("Unknown", "stream", "https://aniwatchtv.site/e/abc.m3u8"))
        assertEquals(87, rankOf("Unknown", "stream", "https://hls.krussdomi.com/manifest/master.m3u8"))
        assertEquals(87, rankOf("Unknown", "stream", "https://hlsxst2.echovideo.to/manifest/master.m3u8"))

        // Ranking must survive CDN rewrites that lose the original source label.
        assertEquals(100, rankOf("Unknown", "stream", "https://bcdnxw.hakunaymatata.com/bt/a.mp4"))
        assertEquals(0, rankOf("Unknown", "stream", "https://sc-u18-01.vix-content.net/hls/master.m3u8"))

        // Decommissioned VidRock, VidSrc, and VidSrcSU and their CDNs must score 0.
        assertEquals(0, rankOf("VidRock", "VidRock [1080p]", "https://vidrock.net/api/movie/550"))
        assertEquals(0, rankOf("Unknown", "stream", "https://galacticvoyagers.xyz/x.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://moonfirekeeper.lol/file2/master.m3u8"))
        assertFalse("VidRock must not be top-tier", StreamLinkOptimizer.isTopTierProvider("vidrock"))
        assertFalse("VidSrc must not be top-tier", StreamLinkOptimizer.isTopTierProvider("vidsrc"))
        assertFalse("VidSrcSU must not be top-tier", StreamLinkOptimizer.isTopTierProvider("vidsrcsu"))

        // Decommissioned sources must score zero rather than inheriting a legacy rank.
        listOf(
            "VidRock", "VidSrc", "VidSrcSU", "RiveStream", "VidEasy", "Vidflix", "VixSrc", "Vidup", "CineJoy", "YFlix",
            "HexaSU", "MovieBox", "VidEm"
        ).forEach { label ->
            assertEquals(
                "$label must not inherit a rank after decommissioning",
                0,
                rankOf(label, "$label [1080p]", "https://x.example/a.m3u8")
            )
        }

        curatedIds.forEach { id ->
            assertTrue("$id must be recognised as a top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    // =========================================================================
    // 2. SETTINGS MIGRATION (v26)
    // =========================================================================

    @Test
    fun testCleanInstallEnablesAllCuratedSources() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        assertFalse(prefs.contains(PREFS_TOP_TIER_INITIALIZED))

        val disabled = getOrInitializeDisabledProviders(prefs)
        val active = buildProviders().map { it.id }.filterNot { disabled.contains(it) }.toSet()

        assertEquals(curatedIds, active)
        assertTrue(prefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testUpgradeFromLegacyInstallScrubsDecommissionedProviderIds() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("vidlink", "vidrock", "VidRock", "uhdmovies", "HexaSU", "animepahe", "AnimePahe")
        ).apply()

        val disabled = getOrInitializeDisabledProviders(prefs)

        REMOVED_PROVIDER_IDS.forEach { legacy ->
            assertFalse("Legacy id $legacy must not be carried into disabled_providers", disabled.contains(legacy))
        }
        buildProviders().forEach { provider ->
            assertFalse("Curated source ${provider.id} must be active after migration", disabled.contains(provider.id))
        }

        val active = buildProviders().map { it.id }.filterNot { disabled.contains(it) }.toSet()
        assertEquals(curatedIds, active)
    }

    @Test
    fun testMigrationNeverReadmitsAProviderThatWasRemovedUpstream() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("vidrock", "rivestream", "vidfast", "videasy", "yflix", "cinejoy", "hexasu", "vixsrc", "animepahe", "AnimePahe", "Animex")
        ).apply()

        val disabled = getOrInitializeDisabledProviders(prefs)
        assertTrue(disabled.isEmpty())
        assertTrue(prefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testSubsequentCallsPreserveUserTogglesAndStayIdempotent() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        getOrInitializeDisabledProviders(prefs)

        prefs.edit().putStringSet("disabled_providers", setOf("meowtv")).apply()

        val first = getOrInitializeDisabledProviders(prefs)
        val second = getOrInitializeDisabledProviders(prefs)
        assertEquals(setOf("meowtv"), first)
        assertEquals(first, second)
    }

    @Test
    fun testConcurrentInitializationIsRaceFree() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        val threadCount = 16
        val latch = CountDownLatch(threadCount)
        val startGate = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<String>()
        val observed = ConcurrentLinkedQueue<Set<String>>()

        repeat(threadCount) {
            Thread {
                try {
                    startGate.await()
                    observed.add(getOrInitializeDisabledProviders(prefs))
                } catch (t: Throwable) {
                    failures.add(t.toString())
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        startGate.countDown()
        assertTrue("Concurrent initialization must finish", latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("No thread may fail: $failures", failures.isEmpty())
        assertEquals("All threads must observe an identical setting", 1, observed.toSet().size)
        assertTrue("Disabled set must be empty for the curated registry", observed.first().isEmpty())
    }

    @Test
    fun testNullPreferencesFallBackToRegistryDefaults() {
        val disabled = getOrInitializeDisabledProviders(null)
        assertTrue("With no preferences every curated source is enabled", disabled.isEmpty())
        assertEquals(disabled, getDefaultDisabledProviderIds())
    }

    // =========================================================================
    // 3. DISPATCHER: BOUNDED ORDERING WINDOWS
    // =========================================================================

    @Test
    fun testLowerRankedSourceIsFlushedWhenHigherRankNeverAnswers() = runBlocking {
        val emitted = ConcurrentLinkedQueue<ExtractorLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = scope,
            stageWindowMs = 100L,
            subtitleGraceMs = 100L,
            topSourceGraceMs = 300L,
            top720GraceMs = 300L,
            activeTopRanks = setOf(100, 96, 95, 94, 93, 92, 91, 90),
            isRankInFlight = { rank -> rank == 100 }
        )

        dispatcher.onLinkAccepted(createLink("Animex", "Animex [720p]", "https://cdn.example/animex720.m3u8", Qualities.P720.value))

        delay(150L)
        assertTrue("Nothing may be emitted while the ordering window is open", emitted.isEmpty())

        delay(400L)
        assertEquals("The buffered link must be flushed once the window closes", 1, emitted.size)
        assertTrue(emitted.first().url.contains("animex720"))
        scope.cancel()
    }

    @Test
    fun test1080pIsEmittedBeforeSdFromTheSameSource() = runBlocking {
        val emitted = ConcurrentLinkedQueue<ExtractorLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = scope,
            stageWindowMs = 80L,
            subtitleGraceMs = 80L,
            topSourceGraceMs = 250L,
            top720GraceMs = 250L,
            activeTopRanks = setOf(100, 96, 95, 94, 93, 92, 91, 90),
            isRankInFlight = { rank -> rank == 100 }
        )

        dispatcher.onLinkAccepted(createLink("VidLink", "VidLink [1080p]", "https://cdn.example/vidlink1080.mp4", Qualities.P1080.value))
        dispatcher.onLinkAccepted(createLink("VidLink", "VidLink [480p]", "https://cdn.example/vidlink480.mp4", Qualities.P480.value))

        delay(900L)
        dispatcher.flush()
        scope.cancel()

        val order = emitted.map { it.url }
        assertEquals("Both VidLink variants must survive", 2, order.size)
        assertTrue("1080p must be ranked ahead of 480p, got $order", order.indexOfFirst { it.contains("1080") } < order.indexOfFirst { it.contains("480") })
    }

    @Test
    fun testFlushNeverDropsBufferedLinks() = runBlocking {
        val emitted = ConcurrentLinkedQueue<ExtractorLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = scope,
            stageWindowMs = 50L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 60_000L,
            top720GraceMs = 60_000L,
            activeTopRanks = setOf(100, 96, 95, 94, 93, 92, 91, 90),
            isRankInFlight = { true }
        )

        repeat(6) { index ->
            dispatcher.onLinkAccepted(
                createLink(
                    "Animex",
                    "Animex [$index]",
                    "https://cdn.example/stream$index.m3u8",
                    Qualities.P720.value
                )
            )
        }
        delay(200L)
        assertTrue("Holds must be bounded even when every higher rank is stuck", emitted.size <= 6)

        dispatcher.flush()
        assertEquals("flush() must release every buffered link", 6, emitted.size)
        assertEquals("flush() must not duplicate links", 6, emitted.map { it.url }.toSet().size)
        scope.cancel()
    }

    @Test
    fun testDispatcherDeduplicatesIdenticalUrls() = runBlocking {
        val emitted = ConcurrentLinkedQueue<ExtractorLink>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = scope,
            activeTopRanks = setOf(100),
            isRankInFlight = { false }
        )

        val link = createLink("VidLink", "VidLink [1080p]", "https://cdn.example/same.mp4", Qualities.P1080.value)
        dispatcher.onLinkAccepted(link)
        dispatcher.onLinkAccepted(link)
        dispatcher.onLinkAccepted(createLink("VidLink", "VidLink [1080p]", "https://cdn.example/same.mp4", Qualities.P1080.value))

        delay(600L)
        dispatcher.flush()
        scope.cancel()

        assertEquals("An identical URL must only ever be emitted once", 1, emitted.size)
    }

    // =========================================================================
    // 4. VIDLINK TOKEN CRYPTOGRAPHY (KNOWN-ANSWER VECTORS)
    // =========================================================================

    @Test
    fun testVidLinkTokenMatchesProductionKnownAnswerVectors() {
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAa2WWlTJyIaoYbqtNhzcjP8WAC8t-dVYZ2cQv21c4",
            VidLinkCrypto.encryptToken("533535", 1_700_000_000L)
        )
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAATR0P9NNAM-CloFsopZKLisGAAcdNQFYZqPv5iA",
            VidLinkCrypto.encryptToken("1399", 1_900_000_000L)
        )
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-Wim3cG-b0n-sLqjZFHnk8WGCP5NQFYZ2cRK",
            VidLinkCrypto.encryptToken("550", 0L)
        )
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMqJmn7LLIgQXaqX33iwj_cGDDcx5eFYZ2cQ9vTI5",
            VidLinkCrypto.encryptToken("105248", 2_000_000_001L)
        )
    }

    @Test
    fun testVidLinkTokenStreamsAcrossMultipleKeystreamBlocks() {
        val longId = "tt0903624-an-extremely-long-imdb-style-identifier-used-to-exercise-multi-block-keystream"
        assertEquals(88, longId.length)
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAc3Wgv_F08OBgdrJU9x7KaoTHCMd9c2Ar7ekr5otdr_hW78TdZcbiLtO0ucLSmTxa5UUVnah9bzLR-xm4nf9bZGZNkii22cI5l8C07M8HqwsU8svMF3ywvjec70Czer9WndMXZKiDXtvU0pZpq6SgGw",
            VidLinkCrypto.encryptToken(longId, 1_750_000_000L)
        )
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlWopBGAPRW021ilt2uxquPCzOP4lDreZ",
            VidLinkCrypto.encryptToken("", 1_750_000_000L)
        )
    }

    @Test
    fun testVidLinkTokenIsUrlSafeAndTimestampSensitive() {
        val token = VidLinkCrypto.encryptToken("533535", 1_700_000_000L)
        assertTrue("Token must be URL-safe base64 without padding", token.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertNotNull(token)
        assertTrue("Token must remain reasonably short", token.length < 256)

        val other = VidLinkCrypto.encryptToken("533535", 1_700_000_001L)
        assertFalse("A different timestamp must produce a different token", token == other)

        val live = VidLinkCrypto.encryptToken("533535")
        assertTrue("Live token must be non-empty", live.isNotEmpty())
        assertTrue("Live token must still be URL-safe", live.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    // =========================================================================
    // 5. DOWNLOAD-VS-STREAM SANITY
    // =========================================================================

    @Test
    fun testCuratedRegistryCannotEmitDownloadOnlyScraperProviders() {
        val ids = buildProviders().map { it.id }.toSet()
        listOf(
            "uhdmovies", "hdhub4u", "4khdhub", "hdmovie2", "moviesmod", "topmovies",
            "bollyflix", "vegamovies", "Rogmovies", "multimovies", "moviesdrive",
            "Hindmoviez", "Movies4u", "M4uhd", "CineVood", "Filmyfiy", "Zinkmovies",
            "Dudefilms", "Peachify", "vidzeeapi", "vidrock"
        ).forEach { assertFalse("$it must not be registered", ids.contains(it)) }

        assertTrue(StreamLinkOptimizer.isTopTierProvider("vidlink"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("cineby"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("megaplay"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("anizone"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("animepahe"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("animex"))
        assertFalse("VidRock was decommissioned and must not report as top tier", StreamLinkOptimizer.isTopTierProvider("vidrock"))
        assertFalse("VidSrc was decommissioned and must not report as top tier", StreamLinkOptimizer.isTopTierProvider("vidsrc"))
        assertEquals(0, rankOf("UHDMovies", "UHDMovies [Download]", "https://x.example/dl"))
    }

    @Test
    fun testQualityPriorityKeepsTheRequestedHierarchy() {
        val hd = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P720.value)
        val fhd = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P1080.value)
        val sd = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P480.value)
        val uhd = StreamLinkOptimizer.getQualityPriorityScore(Qualities.P2160.value)
        assertTrue("720p must outrank 1080p", hd > fhd)
        assertTrue("1080p must outrank 480p", fhd > sd)
        assertTrue("480p must outrank 4K per the requested hierarchy", sd > uhd)
    }

    @Test
    fun testTopTierOrderingCannotBeInvertedByTelemetryVariance() {
        val worstCaseVidLink = FAST_PROVIDER_BOOST.getValue("vidlink") * 100f - 30f
        val bestCaseRunnerUp = DEFAULT_TOP_TIER_PROVIDERS
            .filter { it != "vidlink" }
            .maxOf { FAST_PROVIDER_BOOST.getValue(it) } * 100f + 100f
        assertTrue(
            "VidLink must hold launch priority #1 under adversarial telemetry",
            worstCaseVidLink > bestCaseRunnerUp
        )

        val worstTopTier = createLink(
            "Animex",
            "Animex [Sub] 360p",
            "https://animex.one/e/sd.m3u8",
            Qualities.P360.value
        )
        val bestSecondary = createLink(
            "Unknown",
            "Unknown [REMUX][DV][HEVC][10-bit][TrueHD] 720p 50000 kbps",
            "https://cdn.example/best.m3u8",
            Qualities.P720.value
        )
        assertTrue(
            "The lowest-ranked top-tier stream must always out-score the best secondary stream",
            StreamLinkOptimizer.getStreamCompositeScore(worstTopTier) >
                StreamLinkOptimizer.getStreamCompositeScore(bestSecondary)
        )
    }

    @Test
    fun testPlaybackOrderingIsStrictlyRankDominatedAcrossTheV26Registry() {
        boostByProvider.keys.toList().let { hierarchy ->
            for (i in 0 until hierarchy.size - 1) {
                val higher720 = createLink(
                    hierarchy[i],
                    "${hierarchy[i]} 720p",
                    "https://cdn.example/hd_high_$i.m3u8",
                    Qualities.P720.value
                )
                val lower720 = createLink(
                    hierarchy[i + 1],
                    "${hierarchy[i + 1]} 720p",
                    "https://cdn.example/hd_low_$i.m3u8",
                    Qualities.P720.value
                )
                assertTrue(
                    "${hierarchy[i]} 720p must outrank ${hierarchy[i + 1]} 720p",
                    StreamLinkOptimizer.getStreamCompositeScore(higher720) >
                        StreamLinkOptimizer.getStreamCompositeScore(lower720)
                )

                val higher1080 = createLink(
                    hierarchy[i],
                    "${hierarchy[i]} 1080p",
                    "https://cdn.example/fhd_high_$i.m3u8",
                    Qualities.P1080.value
                )
                val lower1080 = createLink(
                    hierarchy[i + 1],
                    "${hierarchy[i + 1]} 1080p",
                    "https://cdn.example/fhd_low_$i.m3u8",
                    Qualities.P1080.value
                )
                if (hierarchy[i] != "vidrift" && hierarchy[i + 1] != "vidrift") {
                    assertTrue(
                        "${hierarchy[i]} 1080p must outrank ${hierarchy[i + 1]} 1080p",
                        StreamLinkOptimizer.getStreamCompositeScore(higher1080) >
                            StreamLinkOptimizer.getStreamCompositeScore(lower1080)
                    )
                }

                val higher360 = createLink(
                    hierarchy[i],
                    "${hierarchy[i]} 360p",
                    "https://cdn.example/sd_high_$i.m3u8",
                    Qualities.P360.value
                )
                val lower360 = createLink(
                    hierarchy[i + 1],
                    "${hierarchy[i + 1]} 360p",
                    "https://cdn.example/sd_low_$i.m3u8",
                    Qualities.P360.value
                )
                assertTrue(
                    "${hierarchy[i]} 360p must outrank ${hierarchy[i + 1]} 360p",
                    StreamLinkOptimizer.getStreamCompositeScore(higher360) >
                        StreamLinkOptimizer.getStreamCompositeScore(lower360)
                )
            }
        }
    }

    @Test
    fun testEveryProviderExposesACallableResolver() {
        val linkData = StreamPlay.LinkData(
            id = 533535,
            imdbId = "tt6263850",
            isAnime = false,
            title = "Deadpool & Wolverine"
        )
        val providers = buildProviders()
        curatedIds.forEach { id ->
            val provider = providers.firstOrNull { it.id == id }
            assertNotNull("$id must be registered", provider)
            assertEquals(id, provider!!.id)
        }
        assertTrue("Movie titles must remain resolvable", linkData.isAnime.not())
    }

    @Test
    fun testVidLinkDownloadHeadersStripsRefererAndOrigin() {
        val headers = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf(
                "Referer" to "https://filmboom.top/",
                "Origin" to "https://filmboom.top"
            ),
            url = "https://bcdn.hakunaymatata.com/bt/test.mp4",
            referer = "https://filmboom.top/",
            linkType = ExtractorLinkType.VIDEO,
            source = "Vidlink",
            name = "Vidlink 1080p"
        )

        assertNull("Referer must be stripped for VidLink to prevent CDN 429", headers["Referer"])
        assertNull("Origin must be stripped for VidLink to prevent CDN 429", headers["Origin"])
        assertEquals("*/*", headers["Accept"])
        assertEquals("bytes", headers["Accept-Ranges"])
        assertTrue("User-Agent must be non-empty", headers["User-Agent"]?.isNotEmpty() == true)
    }

    @Test
    fun testVidLinkEffectiveRefererIsEmpty() {
        val effective = StreamLinkOptimizer.getEffectiveReferer(
            url = "https://bcdn.hakunaymatata.com/bt/test.mp4",
            referer = "https://filmboom.top/",
            headers = mapOf("Referer" to "https://filmboom.top/"),
            source = "Vidlink",
            name = "Vidlink 1080p"
        )
        assertEquals("VidLink effective referer must be empty", "", effective)
    }

    @Test
    fun testVidLinkCinebyAndCineSrcAreUniversalAcrossMovieTvAndAnime() {
        val providers = buildProviders()
        assertNotNull(providers.first { it.id == "vidlink" })
        assertNotNull(providers.first { it.id == "cineby" })
        assertNotNull(providers.first { it.id == "cinesrc" })

        assertFalse("VidLink must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains("vidlink"))
        assertFalse("VidLink must not be in ANIME_ONLY_PROVIDERS", ANIME_ONLY_PROVIDERS.contains("vidlink"))
        assertFalse("Cineby must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains("cineby"))
        assertFalse("Cineby must not be in ANIME_ONLY_PROVIDERS", ANIME_ONLY_PROVIDERS.contains("cineby"))
        assertFalse("CineSrc must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains("cinesrc"))
        assertFalse("CineSrc must not be in ANIME_ONLY_PROVIDERS", ANIME_ONLY_PROVIDERS.contains("cinesrc"))

        assertEquals(100f, FAST_PROVIDER_BOOST.getValue("vidlink"))
        assertEquals(95f, FAST_PROVIDER_BOOST.getValue("cineby"))
        assertEquals(94f, FAST_PROVIDER_BOOST.getValue("cinesrc"))
    }

    @Test
    fun testVidLinkOutranksEveryOtherSourceInCompositeScore() {
        val vidlink720 = createLink("Vidlink", "Vidlink 720p", "https://bcdn.hakunaymatata.com/bt/720.mp4", Qualities.P720.value)
        val meow720 = createLink("MeowTV", "MeowTV 720p", "https://img1.hscow.com/v4/720/index.m3u8", Qualities.P720.value)
        val vidlink1080 = createLink("Vidlink", "Vidlink 1080p", "https://bcdn.hakunaymatata.com/bt/1080.mp4", Qualities.P1080.value)
        val cineby720 = createLink("Cineby", "Cineby 720p", "https://sun.paleoak.top/file2/720.m3u8", Qualities.P720.value)
        val cineby1080 = createLink("Cineby", "Cineby 1080p", "https://sun.paleoak.top/file2/1080.m3u8", Qualities.P1080.value)
        val cinesrc1080 = createLink("CineSrc", "CineSrc 1080p", "https://nebula.bright67.online/hls2/1080/master.txt", Qualities.P1080.value)
        val animex1080 = createLink("Animex", "Animex 1080p", "https://animex.one/e/1080.m3u8", Qualities.P1080.value)

        val scoreVidlink720 = StreamLinkOptimizer.getStreamCompositeScore(vidlink720)
        val scoreMeow720 = StreamLinkOptimizer.getStreamCompositeScore(meow720)
        val scoreVidlink1080 = StreamLinkOptimizer.getStreamCompositeScore(vidlink1080)
        val scoreCineby720 = StreamLinkOptimizer.getStreamCompositeScore(cineby720)
        val scoreCineby1080 = StreamLinkOptimizer.getStreamCompositeScore(cineby1080)
        val scoreCineSrc1080 = StreamLinkOptimizer.getStreamCompositeScore(cinesrc1080)
        val scoreAnimex1080 = StreamLinkOptimizer.getStreamCompositeScore(animex1080)

        assertTrue("VidLink 720p must dominate MeowTV 720p", scoreVidlink720 > scoreMeow720)
        assertTrue("MeowTV 720p must dominate VidLink 1080p", scoreMeow720 > scoreVidlink1080)
        assertTrue("VidLink 1080p must dominate Cineby 720p", scoreVidlink1080 > scoreCineby720)
        assertTrue("Cineby 720p must dominate Cineby 1080p", scoreCineby720 > scoreCineby1080)
        assertTrue("Cineby 1080p must dominate CineSrc 1080p", scoreCineby1080 > scoreCineSrc1080)
        assertTrue("CineSrc 1080p must dominate Animex 1080p", scoreCineSrc1080 > scoreAnimex1080)
        assertTrue("VidLink 720p must score >= 1,000,000", scoreVidlink720 >= 1_000_000f)
    }

    @Test
    fun testVidLinkQualityLadderOrdering() {
        val vidlink720 = createLink("Vidlink", "Vidlink 720p", "https://bcdn.hakunaymatata.com/bt/720.mp4", Qualities.P720.value)
        val vidlink1080 = createLink("Vidlink", "Vidlink 1080p", "https://bcdn.hakunaymatata.com/bt/1080.mp4", Qualities.P1080.value)
        val vidlink480 = createLink("Vidlink", "Vidlink 480p", "https://bcdn.hakunaymatata.com/bt/480.mp4", Qualities.P480.value)
        val vidlink360 = createLink("Vidlink", "Vidlink 360p", "https://bcdn.hakunaymatata.com/bt/360.mp4", Qualities.P360.value)

        val score720 = StreamLinkOptimizer.getStreamCompositeScore(vidlink720)
        val score1080 = StreamLinkOptimizer.getStreamCompositeScore(vidlink1080)
        val score480 = StreamLinkOptimizer.getStreamCompositeScore(vidlink480)
        val score360 = StreamLinkOptimizer.getStreamCompositeScore(vidlink360)

        assertTrue("720p must score higher than 1080p", score720 > score1080)
        assertTrue("1080p must score higher than 480p", score1080 > score480)
        assertTrue("480p must score higher than 360p", score480 > score360)
    }

    @Test
    fun testVidLinkDirectImdbTokenEncryption() {
        val imdbToken = VidLinkCrypto.encryptToken("tt0137523")
        assertNotNull(imdbToken)
        assertTrue("Token for IMDb ID must be URL-safe", imdbToken.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue("Token must be non-empty", imdbToken.isNotEmpty())
    }

    // =========================================================================
    // 6. CINEBY, CINESRC, MEGAPLAY, ANIMEX & HLS MASTER PARSER CONTRACTS
    // =========================================================================

    @Test
    fun testCineSrcPowSolversAndDecryptedPayloadParsing() {
        // Stage 1: CSP4 custom stream hash PoW (28-byte config with difficulty=2 bits, rounds=4, memBits=4, sI=0, pI=0)
        val stage1Config = ByteArray(28)
        stage1Config[21] = 1 // mode
        stage1Config[22] = 2 // difficulty bits
        stage1Config[23] = 0 // v
        stage1Config[24] = 4 // rounds
        stage1Config[25] = 4 // mB (size = 16)
        stage1Config[26] = 0 // sI
        stage1Config[27] = 0 // pI
        val wB64 = StreamPlayExtractor.cinesrcBase64UrlEncode(stage1Config)
        val seedHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val stage1Solution = StreamPlayExtractor.cinesrcSolveStage1(wB64, seedHex)
        assertTrue("Stage 1 PoW solution must start with m4.", stage1Solution.startsWith("m4."))

        // Stage 2: SHA-256 PoW (difficulty = 4 bits -> width = 1 hex char, key = "3")
        val salt = "cinesrc_salt_"
        val expectedKey = "3"
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val targetHex = sha256.digest((salt + expectedKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val innerJsonB64 = StreamPlayExtractor.cinesrcBase64UrlEncode("""{"d":4}""".toByteArray(Charsets.UTF_8))
        val bodyB64 = StreamPlayExtractor.cinesrcBase64UrlEncode("hdr.$innerJsonB64".toByteArray(Charsets.UTF_8))
        val rToken = "v1.$bodyB64.sig"
        val pack = listOf(targetHex.reversed(), "unused1", "unused2", salt.reversed(), rToken.reversed())
        val solvedKey = StreamPlayExtractor.cinesrcSolveStage2(pack)
        assertEquals(expectedKey, solvedKey)

        // Wave / Surge multi-quality array format (`url` array with discrete `source` quality keys)
        val wavePayload = """{
            "result": {
                "url": [
                    {"url": "https://ice.bright67.online/Deadpool/720p/playlist.jpg", "source": "720p"},
                    {"url": "https://ice.bright67.online/Deadpool/1080p/playlist.jpg", "source": "1080p"}
                ]
            }
        }"""
        val waveParsed = StreamPlayExtractor.cinesrcParseDecryptedResult(wavePayload)
        assertNotNull(waveParsed)
        assertEquals(2, waveParsed!!.entries.size)
        val entry720 = waveParsed.entries.first { it.url.contains("720p") }
        val entry1080 = waveParsed.entries.first { it.url.contains("1080p") }
        assertEquals(Qualities.P720.value, entry720.quality)
        assertEquals(Qualities.P1080.value, entry1080.quality)
        // No size metadata in this payload: sizes must stay null instead of being fabricated per quality.
        assertNull(entry720.sizeMb)
        assertNull(entry1080.sizeMb)

        // Real payload metadata is preserved (both byte counts and human labels).
        val sizedPayload = """{
            "result": {
                "url": [
                    {"url": "https://ice.bright67.online/Deadpool/720p/playlist.jpg", "source": "720p", "size": "612 MB"},
                    {"url": "https://ice.bright67.online/Deadpool/1080p/playlist.jpg", "source": "1080p", "sizeBytes": 1572864000}
                ]
            }
        }"""
        val sizedParsed = StreamPlayExtractor.cinesrcParseDecryptedResult(sizedPayload)
        assertNotNull(sizedParsed)
        assertEquals(612, sizedParsed!!.entries.first { it.url.contains("720p") }.sizeMb)
        assertEquals(1500, sizedParsed.entries.first { it.url.contains("1080p") }.sizeMb)

        // Nebula master manifest string format (`url` string)
        val nebulaPayload = """{"result": {"url": "https://nebula.bright67.online/hls2/01/00105/abc/playlist.jpg"}}"""
        val nebulaParsed = StreamPlayExtractor.cinesrcParseDecryptedResult(nebulaPayload)
        assertNotNull(nebulaParsed)
        assertEquals(1, nebulaParsed!!.entries.size)
        assertEquals("https://nebula.bright67.online/hls2/01/00105/abc/playlist.jpg", nebulaParsed.entries[0].url)
    }

    @Test
    fun testCinebyDecryptedPayloadParsing() {
        val payload = """{
            "sources": [
                {"url": "https://sun.paleoak.top/file2/abc/master.m3u8", "quality": "1080p"},
                {"url": "https://e2e.majorplay.net/hls/720/index.m3u8", "quality": "720p"}
            ],
            "subtitles": [
                {"url": "https://sub.videasy.net/en.vtt", "language": "English", "lang": "en"}
            ]
        }"""
        val res = cinebyParseDecryptedResult(payload)
        assertNotNull(res)
        assertEquals(2, res!!.sources.size)
        assertEquals("https://sun.paleoak.top/file2/abc/master.m3u8", res.sources[0].url)
        assertEquals(Qualities.P1080.value, res.sources[0].quality)
        assertEquals(Qualities.P720.value, res.sources[1].quality)
        assertEquals(1, res.subtitles.size)
        assertEquals("English", res.subtitles[0].first)
        assertEquals("https://sub.videasy.net/en.vtt", res.subtitles[0].second)
    }

    @Test
    fun testMegaPlayFileIdAndSourcesParsing() {
        val html = """<div class="megaplay-player" data-id="948512" data-ep="1"></div>"""
        assertEquals("948512", megaplayExtractFileId(html))

        val decryptedJson = """{
            "sources": [{"file": "https://fetch.nexabloom.top/hls/948512/master.m3u8", "type": "hls"}],
            "tracks": [
                {"file": "https://broforgotsave.online/subs/en.vtt", "label": "English", "kind": "captions"},
                {"file": "https://broforgotsave.online/thumbs/sprite.vtt", "kind": "thumbnails"}
            ]
        }"""
        val parsed = megaplayParseSourcesPayload(decryptedJson)
        assertNotNull(parsed)
        assertEquals(listOf("https://fetch.nexabloom.top/hls/948512/master.m3u8"), parsed!!.directUrls)
        assertEquals(1, parsed.tracks.size)
        assertEquals("English", parsed.tracks[0].first)
        assertEquals("https://broforgotsave.online/subs/en.vtt", parsed.tracks[0].second)

        // Live 2026-10-24 MegaPlay getSources ("enc" + embermeadow.world tracks) and dec-megaplay ("result.file")
        val liveGetSourcesJson = """{
            "enc": "cb0f026fb01fa50df9e488dc97a63e22948512",
            "tracks": [
                {"file": "https://j1i9s.embermeadow.world/_v7/abc/subtitles/eng-2.vtt", "label": "English", "kind": "captions", "default": true},
                {"file": "https://j1i9s.embermeadow.world/_v7/abc/thumbnails/sprite.vtt", "kind": "thumbnails"}
            ]
        }"""
        val liveInitial = megaplayParseSourcesPayload(liveGetSourcesJson)
        assertNotNull(liveInitial)
        assertEquals("cb0f026fb01fa50df9e488dc97a63e22948512", liveInitial!!.encryptedSources)
        assertTrue(liveInitial.directUrls.isEmpty())
        assertEquals(1, liveInitial.tracks.size)
        assertEquals("English", liveInitial.tracks[0].first)
        assertEquals("https://j1i9s.embermeadow.world/_v7/abc/subtitles/eng-2.vtt", liveInitial.tracks[0].second)

        val liveDecryptedJson = """{
            "status": 200,
            "result": {
                "file": "https://fetch.nexabloom.top/anime/abc123/master.m3u8?token=xyz"
            }
        }"""
        val liveDecrypted = megaplayParseSourcesPayload(liveDecryptedJson)
        assertNotNull(liveDecrypted)
        assertEquals(listOf("https://fetch.nexabloom.top/anime/abc123/master.m3u8?token=xyz"), liveDecrypted!!.directUrls)
    }

    @Test
    fun testAnimexSourceJsonParsesTrackUrlAndFileFields() {
        val json = """{
            "sources": [{"url": "https://hls.krussdomi.com/manifest/master.m3u8", "quality": "1080p", "isM3U8": true}],
            "tracks": [
                {"url": "https://subst.krussdomi.com/sub/en.vtt", "lang": "English", "kind": "captions"},
                {"file": "https://subst.krussdomi.com/sub/ja.vtt", "label": "Japanese", "kind": "subtitles"}
            ]
        }"""
        val parsed = animexParseSourceJson(json)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.sources?.size)
        assertEquals(2, parsed.tracks?.size)
        assertEquals("https://subst.krussdomi.com/sub/en.vtt", parsed.tracks?.get(0)?.url)
        assertEquals("English", parsed.tracks?.get(0)?.lang)
        assertEquals("https://subst.krussdomi.com/sub/ja.vtt", parsed.tracks?.get(1)?.file)
        assertEquals("Japanese", parsed.tracks?.get(1)?.label)
    }

    @Test
    fun testHlsMasterVariantsPreservesMasterUrlWhenExternalAudioPresent() {
        val masterWithExternalAudio = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Japanese",DEFAULT=YES,URI="audio/ja.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,AVERAGE-BANDWIDTH=1200000,RESOLUTION=1280x720,AUDIO="audio"
            video/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,AVERAGE-BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO="audio"
            video/1080p.m3u8
        """.trimIndent()
        val variants = parseHlsMasterVariants(
            masterUrl = "https://sun.paleoak.top/hls/master.m3u8",
            manifestText = masterWithExternalAudio
        )
        assertEquals(2, variants.size)
        val v720 = variants.first { it.quality == Qualities.P720.value }
        assertEquals("https://sun.paleoak.top/hls/master.m3u8#vid=720p", v720.url)
        assertTrue("720p variant must have positive estimated MB", (v720.estimatedSizeMb ?: 0) > 0)
    }

    // =========================================================================
    // 7. EXCEPTION GATES: ANIME VS MOVIE/TV
    // =========================================================================

    @Test
    fun testSegmentGatesForMovieTvAndAnime() {
        // Movie/TV-only sources must never race an anime title.
        listOf("vidrift", "vidfast", "meowtv").forEach { id ->
            assertTrue("$id must be a NON_ANIME provider", NON_ANIME_PROVIDERS.contains(id))
            assertFalse("$id must not be an ANIME_ONLY provider", ANIME_ONLY_PROVIDERS.contains(id))
        }
        assertFalse("Decommissioned vidsrcsu must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains("vidsrcsu"))
        // Dedicated Anime SOTA sources must only run on anime titles.
        listOf("megaplay", "anizone", "animepahe", "animex").forEach { id ->
            assertTrue("$id must be an ANIME_ONLY provider", ANIME_ONLY_PROVIDERS.contains(id))
            assertFalse("$id must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains(id))
        }
        // Universal SOTA sources (VidLink, VidRift, Cineby, CineSrc) run on Movie, TV, and Anime.
        listOf("vidlink", "cineby", "cinesrc").forEach { id ->
            assertFalse("$id must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains(id))
            assertFalse("$id must not be in ANIME_ONLY_PROVIDERS", ANIME_ONLY_PROVIDERS.contains(id))
        }

        curatedIds.forEach { id ->
            assertTrue("$id must be recognised as a top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    @Test
    fun testCuratedRanksMeetTheTopTierBoostThreshold() {
        val lowest = DEFAULT_TOP_TIER_PROVIDERS.minOf { FAST_PROVIDER_BOOST.getValue(it) }
        assertTrue(
            "Lowest curated boost $lowest must clear the top-tier threshold",
            lowest >= TOP_TIER_BOOST_THRESHOLD
        )
    }

    @Test
    fun testEveryProviderCdnHostCarriesRankForRewrappedLinks() {
        assertEquals(100, rankOf("Unknown", "stream", "https://bcdn.hakunaymatata.com/bt/master.m3u8"))
        assertEquals(98, rankOf("Unknown", "stream", "https://img1.hscow.com/i/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://moon.goldenkite.top/file2/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://sun.paleoak.top/file2/master.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://nebula.bright67.online/hls2/master.txt"))
        assertEquals(94, rankOf("Unknown", "stream", "https://lightspeed.st/hls2/master.txt"))
        assertEquals(93, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://fetch.nexabloom.top/hls/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://edge.reelvault.click/proxy?u=abc"))
        assertEquals(89, rankOf("Unknown", "stream", "https://suzaku.xin-cdn.xyz/e/master.m3u8"))
        assertEquals(88, rankOf("Unknown", "stream", "https://animepahe.pw/e/master.m3u8"))
        assertEquals(87, rankOf("Unknown", "stream", "https://hls.krussdomi.com/e/master.m3u8"))
    }

    @Test
    fun testDnsBootstrapResolversCoverFourIndependentOperators() {
        assertEquals(4, OptimizedDns.CLOUDFLARE_BOOTSTRAP_IPS.size)
        assertTrue(OptimizedDns.CLOUDFLARE_BOOTSTRAP_IPS.contains("1.1.1.1"))
        assertTrue(OptimizedDns.GOOGLE_BOOTSTRAP_IPS.contains("8.8.8.8"))
        assertTrue("Quad9 bootstrap must be present for the resolver race", OptimizedDns.QUAD9_BOOTSTRAP_IPS.contains("9.9.9.10"))
        assertTrue(OptimizedDns.QUAD9_BOOTSTRAP_IPS.contains("149.112.112.10"))
        assertTrue("AdGuard bootstrap must be present for the resolver race", OptimizedDns.ADGUARD_BOOTSTRAP_IPS.contains("94.140.14.140"))
        assertTrue(OptimizedDns.ADGUARD_BOOTSTRAP_IPS.contains("94.140.14.141"))

        OptimizedDns.TOP_PREDICTIVE_DOMAINS.let { domains ->
            listOf(
                "vidlink.pro", "bcdn.hakunaymatata.com",
                "vidrift.net", "embed.vidrift.net", "edge.reelvault.click", "media.reelvault.click", "bigbang.cymru",
                "api.wecollege.net", "vidy.st", "player.videasy.to", "moon.goldenkite.top", "sun.paleoak.top", "e2e.majorplay.net",
                "cinesrc.st", "nebula.bright67.online", "ice.bright67.online", "lightspeed.st", "subs.bright67.online", "api.wing.st",
                "vidfast.vc", "moon.zenoak.top",
                "megaplay.buzz", "fetch.nexabloom.top", "broforgotsave.online",
                "meowtv.ru", "api.meowtv.ru", "img1.hscow.com", "img1.fcxmb.com",
                "cdn.vidjoy.org", "cdn2.vidjoy.org", "i-arch-400.krico443jez.com",
                "graphql.animex.one", "pp.animex.one", "hls.krussdomi.com", "subst.krussdomi.com", "hlsxst2.echovideo.to",
                "anizone.to", "suzaku.xin-cdn.xyz", "seiryuu.vid-cdn.xyz", "animepahe.pw", "animepahe.com",
                "kwik.cx", "kwik.si", "uwucdn.top", "owocdn.top"
            ).forEach { domain ->
                assertTrue("$domain must be in TOP_PREDICTIVE_DOMAINS", domains.contains(domain))
            }
            assertTrue(
                "Decommissioned VidRock domains must not be warmed",
                domains.none { it.contains("vidrock") || it.contains("galacticvoyagers") || it.contains("moonfirekeeper") }
            )
            assertTrue(
                "Decommissioned VidSrc and VidSrcSU domains must not be warmed",
                domains.none {
                    it == "vidsrc.pm" || it == "vidsrc.su" || it.contains("themoviedb.vidsrc.su") ||
                        it.contains("theemoviedb.hexa.su") || it.contains("dragonballzfans") ||
                        it.contains("cheaptruckrepairs") || it.contains("netoda.tech")
                }
            )
            assertTrue(
                "AniZone and AnimePahe must be warmed: they are the anime subtitle/coverage sources",
                domains.contains("anizone.to") && domains.contains("animepahe.pw")
            )
        }
    }

    // =========================================================================
    // 8. STREAM-ONLY CONTRACT: DOWNLOAD-EXCLUDED PROVIDERS
    // =========================================================================

    @Test
    fun testVidFastIsTheDeclaredStreamOnlyProvider() {
        assertEquals(
            "The stream-only declaration must be exactly VidFast",
            setOf("vidfast"),
            DOWNLOAD_EXCLUDED_PROVIDER_IDS
        )

        val providerIds = buildProviders().map { it.id }.toSet()
        DOWNLOAD_EXCLUDED_PROVIDER_IDS.forEach { id ->
            assertTrue("Stream-only id $id must be a registered provider", providerIds.contains(id))
            assertTrue("Stream-only id $id must be part of the curated registry", DEFAULT_TOP_TIER_PROVIDERS.contains(id))
            assertFalse("Stream-only id $id must not be listed as removed", REMOVED_PROVIDER_IDS.contains(id))
            assertNotNull("Stream-only id $id must keep its streaming boost", FAST_PROVIDER_BOOST[id])
        }
    }

    @Test
    fun testStreamOnlyProvidersAreNeverDisabledByTheSettingsPipeline() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        prefs.edit().putStringSet("disabled_providers", setOf("vidfast", "cinesrc")).apply()

        val disabled = getOrInitializeDisabledProviders(prefs)
        DOWNLOAD_EXCLUDED_PROVIDER_IDS.forEach { id ->
            assertFalse("Stream-only $id must be active after migration", disabled.contains(id))
        }
        assertFalse("CineSrc must be active after migration", disabled.contains("cinesrc"))
    }

    @Test
    fun testDownloadExclusionNeverDemotesStreamRank() {
        for ((id, boost) in boostByProvider) {
            if (!DOWNLOAD_EXCLUDED_PROVIDER_IDS.contains(id)) continue
            assertEquals("Stream-only $id must keep its full launch boost", boost, FAST_PROVIDER_BOOST.getValue(id))
            assertEquals(
                "Stream-only $id must stay tier-1 for streaming",
                LatencyTier.TIER_1,
                SpeculativePipeliner.classifyProvider(id)
            )
            assertTrue("Stream-only $id must stay a recognised top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }

        assertEquals(93, rankOf("VidFast", "VidFast [720p]", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(93, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))

        val vidfast = createLink("VidFast", "VidFast [720p]", "https://moon.zenoak.top/hls/master.m3u8", Qualities.P720.value)
        val megaplay = createLink("MegaPlay", "MegaPlay [720p]", "https://fetch.nexabloom.top/x/master.m3u8", Qualities.P720.value)
        assertTrue(
            "VidFast must still outrank MegaPlay for playback",
            StreamLinkOptimizer.getStreamCompositeScore(vidfast) > StreamLinkOptimizer.getStreamCompositeScore(megaplay)
        )
    }

    @Test
    fun testIsUpcomingTimezoneSafetyBufferPreventsFalseComingSoon() {
        assertFalse("Past dates must never be upcoming", isUpcoming("2020-01-01"))
        assertFalse("Blank dates must never be upcoming", isUpcoming(""))
        assertFalse("Null dates must never be upcoming", isUpcoming(null))
        assertTrue("Far future dates must still be marked upcoming", isUpcoming("2099-12-31"))
    }

    @Test
    fun testVidLinkDashMpdParsingAndWidescreen720pExtraction() {
        val mpdXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2H28M7.5S">
              <Period id="0" start="PT0.0S">
                <AdaptationSet id="0" contentType="video">
                  <Representation id="0" mimeType="video/mp4" codecs="hev1.1.6.L120.90" bandwidth="1592836" width="1920" height="1080" />
                  <Representation id="1" mimeType="video/mp4" codecs="hev1.1.6.L120.90" bandwidth="882679" width="1920" height="1080" />
                  <Representation id="2" mimeType="video/mp4" codecs="hev1.1.6.L93.90" bandwidth="1008565" width="1720" height="720" />
                  <Representation id="3" mimeType="video/mp4" codecs="hev1.1.6.L93.90" bandwidth="558380" width="1720" height="720" />
                  <Representation id="4" mimeType="video/mp4" codecs="hev1.1.6.L90.90" bandwidth="233591" width="854" height="480" />
                </AdaptationSet>
                <AdaptationSet id="1" contentType="audio" lang="eng">
                  <Representation id="5" mimeType="audio/mp4" codecs="mp4a.40.2" bandwidth="128000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val masterUrl = "https://sacdn.hakunaymatata.com/dash/movie_test/index_web.mpd"
        val variants = StreamPlayExtractor.parseDashMasterVariants(masterUrl, mpdXml)
        assertEquals("Must extract 480p, 720p, and 1080p unique tiers", 3, variants.size)

        val v720 = variants.firstOrNull { it.quality == Qualities.P720.value }
        assertNotNull("720p variant must be extracted from 1720x720 DASH representation", v720)
        assertEquals("$masterUrl#vid=720p", v720!!.url)
        assertEquals(558380L, v720.bandwidthBps)
        assertTrue("720p estimated size must be positive and include audio", (v720.estimatedSizeMb ?: 0) in 700..760)

        val v1080 = variants.firstOrNull { it.quality == Qualities.P1080.value }
        assertNotNull("1080p variant must be extracted", v1080)
        assertEquals("$masterUrl#vid=1080p", v1080!!.url)
        assertTrue("1080p estimated size must exceed 720p estimated size", (v1080.estimatedSizeMb ?: 0) > (v720.estimatedSizeMb ?: 0))
    }

    @Test
    fun testVidLinkBcdnxwPreservesFilmboomRefererWhileBcdnAndSacdnStripReferer() {
        val bcdnxwUrl = "https://bcdnxw.hakunaymatata.com/resource/abc123.mp4?Expires=1793973608"
        val bcdnxwHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf(
                "Referer" to "https://filmboom.top/",
                "Origin" to "https://filmboom.top"
            ),
            url = bcdnxwUrl,
            referer = "https://filmboom.top/",
            linkType = ExtractorLinkType.VIDEO,
            source = "Vidlink",
            name = "Vidlink 720p"
        )
        assertEquals("https://filmboom.top/", bcdnxwHeaders["Referer"])
        assertEquals("https://filmboom.top", bcdnxwHeaders["Origin"])
        assertEquals(
            "https://filmboom.top/",
            StreamLinkOptimizer.getEffectiveReferer(
                url = bcdnxwUrl,
                referer = "https://filmboom.top/",
                headers = bcdnxwHeaders,
                source = "Vidlink",
                name = "Vidlink 720p"
            )
        )

        val sacdnUrl = "https://sacdn.hakunaymatata.com/dash/movie/index_web.mpd#vid=720p"
        val sacdnHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf(
                "Cookie" to "CloudFront-Policy=abc;CloudFront-Signature=def",
                "Referer" to "https://vidlink.pro/"
            ),
            url = sacdnUrl,
            referer = "",
            linkType = ExtractorLinkType.DASH,
            source = "Vidlink",
            name = "Vidlink DASH [720p]"
        )
        assertNull("sacdn must strip Referer", sacdnHeaders["Referer"])
        assertNull("sacdn must strip Origin", sacdnHeaders["Origin"])
        assertEquals("CloudFront-Policy=abc;CloudFront-Signature=def", sacdnHeaders["Cookie"])
        assertEquals("", StreamLinkOptimizer.getEffectiveReferer(sacdnUrl, "", sacdnHeaders, "Vidlink", "Vidlink DASH [720p]"))
    }

    @Test
    fun testConstantHeightAndConstantWidthWidescreenResolutionNormalization() {
        // Constant-height widescreen (e.g. Gladiator II 1720x720, 1800x720, 2580x1080, 1200x480, 864x360)
        assertEquals(Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1720, 720, "v720.m3u8"))
        assertEquals(Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1800, 720, "v720.m3u8"))
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(2580, 1080, "v1080.m3u8"))
        assertEquals(Qualities.P480.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1200, 480, "v480.m3u8"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(864, 360, "v360.m3u8"))

        // Constant-width widescreen (e.g. 1280x534, 1920x800, 854x356, 640x268)
        assertEquals(Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1280, 534, "v720.m3u8"))
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1920, 800, "v1080.m3u8"))
        assertEquals(Qualities.P480.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(854, 356, "v480.m3u8"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(640, 268, "v360.m3u8"))

        // Text extraction for cinema widescreen dimensions
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Stream 1280x534"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Stream 1720x720"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Stream 1920x800"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Stream 2580x1080"))
    }

    @Test
    fun testAnimeTargetEnglishSubtitleSourcesContract() {
        assertEquals("Movie/TV target English subtitle sources must remain 5", 5, TARGET_ENGLISH_SUBTITLE_SOURCES)
        assertEquals("Anime target English subtitle sources must be 6 (5-6 distinct sources)", 6, ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES)
    }

    @Test
    fun testEndToEndMovieTvAndAnime720pTopSourceHierarchy() {
        // Movie/TV hierarchy when VidLink 720p comes from dash-hevc (.mpd#vid=720p) alongside direct 1080p
        val vidlinkDash720 = createLink(
            source = "Vidlink",
            name = "Vidlink DASH [720p] [727 MB]",
            url = "https://sacdn.hakunaymatata.com/dash/movie_27205/index_web.mpd#vid=720p",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.DASH
        )
        val meow720 = createLink(
            source = "MeowTV",
            name = "MeowTV English [720p] [690 MB]",
            url = "https://img1.hscow.com/hls_mps/210544560/720/index_287.m3u8",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )
        val vidlinkDirect1080 = createLink(
            source = "Vidlink",
            name = "Vidlink 1080p [2.56 GB]",
            url = "https://bcdnxw.hakunaymatata.com/resource/1080.mp4",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.VIDEO
        )
        val cineby720 = createLink(
            source = "Cineby",
            name = "Cineby Yeti [720p] [650 MB]",
            url = "https://sun.paleoak.top/file2/720.m3u8",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )
        val cinesrc720 = createLink(
            source = "CineSrc",
            name = "CineSrc [720p] [640 MB]",
            url = "https://nebula.bright67.online/hls2/720/master.txt",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )
        val vidrift720 = createLink(
            source = "VidRift",
            name = "VidRift Direct [720p] [600 MB]",
            url = "https://edge.reelvault.click/selfhost/720.m3u8",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )

        val movieSorted = listOf(vidrift720, cinesrc720, cineby720, vidlinkDirect1080, meow720, vidlinkDash720)
            .sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)
        assertEquals(
            listOf(
                "Vidlink DASH [720p] [727 MB]",
                "MeowTV English [720p] [690 MB]",
                "Vidlink 1080p [2.56 GB]",
                "Cineby Yeti [720p] [650 MB]",
                "CineSrc [720p] [640 MB]",
                "VidRift Direct [720p] [600 MB]"
            ),
            movieSorted.map { it.name }
        )

        // Anime hierarchy: all top-tier 720p sources strictly beat all 1080p sources in exact chain order
        fun createAnimeLink(source: String, name: String, url: String, quality: Int): ExtractorLink {
            @Suppress("DEPRECATION")
            return ExtractorLink(
                source = source,
                name = name,
                url = url,
                referer = "",
                quality = quality,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("X-StreamPlay-Anime" to "true")
            )
        }

        val animeVidlink720 = createAnimeLink("Vidlink", "Vidlink [Sub] [Original] [720p] [220 MB]", "https://sacdn.hakunaymatata.com/dash/tv/index_web.mpd#vid=720p", Qualities.P720.value)
        val animeCineby720 = createAnimeLink("Cineby", "Cineby [Sub] [Original] [720p] [210 MB]", "https://sun.paleoak.top/file2/720.m3u8", Qualities.P720.value)
        val animeCineSrc720 = createAnimeLink("CineSrc", "CineSrc [Sub] [Original] [720p] [205 MB]", "https://nebula.bright67.online/hls2/720/master.txt", Qualities.P720.value)
        val animeMegaPlay720 = createAnimeLink("MegaPlay", "MegaPlay [Sub] [Original] [720p] [200 MB]", "https://fetch.nexabloom.top/hls/720.m3u8", Qualities.P720.value)
        val animeAniZone720 = createAnimeLink("AniZone", "AniZone [Sub] [Original] [720p] [195 MB]", "https://suzaku.xin-cdn.xyz/e/720.m3u8", Qualities.P720.value)
        val animePahe720 = createAnimeLink("AnimePahe", "AnimePahe [Sub] [Original] [720p] [190 MB]", "https://uwucdn.top/e/720.m3u8", Qualities.P720.value)
        val animeAnimex720 = createAnimeLink("Animex", "Animex [Sub] [Original] [720p] [185 MB]", "https://hls.krussdomi.com/e/720.m3u8", Qualities.P720.value)
        val animeVidlink1080 = createAnimeLink("Vidlink", "Vidlink [Sub] [Original] [1080p] [420 MB]", "https://bcdn.hakunaymatata.com/bt/1080.mp4", Qualities.P1080.value)

        val animeSorted = listOf(
            animeVidlink1080,
            animeAnimex720,
            animePahe720,
            animeAniZone720,
            animeMegaPlay720,
            animeCineSrc720,
            animeCineby720,
            animeVidlink720
        ).sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR)

        assertEquals(
            listOf(
                "Vidlink",
                "Cineby",
                "CineSrc",
                "MegaPlay",
                "AniZone",
                "AnimePahe",
                "Animex",
                "Vidlink"
            ),
            animeSorted.map { it.source }
        )
        assertEquals(Qualities.P720.value, animeSorted.first().quality)
        assertEquals(Qualities.P1080.value, animeSorted.last().quality)
    }

    @Test
    fun testDashRobustnessBranches() {
        // 1. Self-closing `<AdaptationSet/>` + bare representations outside paired sets + single-quoted
        //    attributes + per-Period duration summation + median audio bandwidth pairing. No root
        //    mediaPresentationDuration: the total is the SUM of the declared Period durations (45 min).
        val selfClosingMpd = """
            <?xml version='1.0' encoding='UTF-8'?>
            <MPD>
              <Period id='0' duration='PT20M'>
                <AdaptationSet contentType='video' width='1920' height='800'/>
                <Representation id='v1' bandwidth='2200000' width='1920' height='800'/>
                <Representation id='v2' bandwidth='1100000' width='1720' height='720'/>
              </Period>
              <Period id='1' duration='PT25M'>
                <AdaptationSet mimeType='audio/mp4' lang='en'>
                  <Representation id='a1' bandwidth='96000'/>
                  <Representation id='a2' bandwidth='128000'/>
                  <Representation id='a3' bandwidth='192000'/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val selfClosingVariants = StreamPlayExtractor.parseDashMasterVariants(
            "https://sacdn.hakunaymatata.com/dash/self/index.mpd",
            selfClosingMpd
        )
        val sc720 = selfClosingVariants.firstOrNull { it.quality == Qualities.P720.value }
        val sc1080 = selfClosingVariants.firstOrNull { it.quality == Qualities.P1080.value }
        assertNotNull("Bare representation outside a paired set must still be parsed (1720x720)", sc720)
        assertNotNull("Bare representation outside a paired set must still be parsed (1920x800)", sc1080)
        assertTrue("Single-quoted attributes must be parsed", selfClosingVariants.isNotEmpty())
        // Median audio bandwidth of {96, 128, 192} kbps = 128 kbps paired with each video rep.
        // Total presentation = 20 min + 25 min = 45 min.
        val expectedMb = ((1_100_000L + 128_000L) / 8.0) * (45 * 60) / (1024.0 * 1024.0)
        assertEquals("Per-Period duration sum (45 min) must drive the size estimate", expectedMb.toInt(), sc720!!.estimatedSizeMb)
        // Median, not max, audio: max-audio pairing would add 192 kbps and inflate the estimate.
        val maxAudioMb = ((1_100_000L + 192_000L) / 8.0) * (45 * 60) / (1024.0 * 1024.0)
        assertTrue("Median audio bandwidth must not inflate estimates like max-audio pairing", sc720.estimatedSizeMb!! < maxAudioMb.toInt())

        // 2. Multi-Period MPD without a root duration: per-Period durations are summed.
        val multiPeriodMpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD>
              <Period id="0" duration="PT15M0S"/>
              <Period id="1" duration="PT15M0S"/>
              <Period id="2" duration="PT10M0S"/>
              <Period id="3">
                <AdaptationSet mimeType="video/mp4" width="1720" height="720">
                  <Representation id="v720" bandwidth="1100000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val multiPeriodVariants = StreamPlayExtractor.parseDashMasterVariants(
            "https://sacdn.hakunaymatata.com/dash/multi/index.mpd",
            multiPeriodMpd
        )
        val mp720 = multiPeriodVariants.firstOrNull { it.quality == Qualities.P720.value }
        assertNotNull("Multi-Period duration sum must yield the 720p variant", mp720)
        val multiExpectedMb = (1_100_000L / 8.0) * (40 * 60) / (1024.0 * 1024.0)
        assertEquals("15+15+10 minutes = 40 minutes must drive the size estimate", multiExpectedMb.toInt(), mp720!!.estimatedSizeMb)

        // 3. SegmentTemplate fallback: attribute order swapped (duration BEFORE timescale) and
        //    `<S>` repeat counts scoped per SegmentTimeline (max timeline, not a cross-set sum).
        val segmentTemplateMpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD>
              <Period id="0">
                <AdaptationSet mimeType="video/mp4" width="1720" height="720">
                  <Representation id="v720" bandwidth="1100000">
                    <SegmentTemplate duration="4000" timescale="1000" initialization="init-v720.mp4">
                      <SegmentTimeline>
                        <S d="4000" r="359"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation id="a0" bandwidth="128000">
                    <SegmentTemplate duration="4000" timescale="48000">
                      <SegmentTimeline>
                        <S d="4000" r="47"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val stVariants = StreamPlayExtractor.parseDashMasterVariants(
            "https://sacdn.hakunaymatata.com/dash/tpl/index.mpd",
            segmentTemplateMpd,
            durationHintSeconds = 0.0
        )
        val st720 = stVariants.firstOrNull { it.quality == Qualities.P720.value }
        assertNotNull("SegmentTemplate fallback must yield the 720p variant", st720)
        // 360 video segments x 4000/1000 = 1440 s (the video timeline; the 48-segment audio
        // timeline is ignored), paired with the 128 kbps audio representation.
        val stExpectedMb = ((1_100_000L + 128_000L) / 8.0) * 1440 / (1024.0 * 1024.0)
        assertEquals("Swapped attribute order + max-timeline scoping must compute 24 minutes", stExpectedMb.toInt(), st720!!.estimatedSizeMb)

        // 4b. Template WITHOUT a `duration` attribute: exact timing derives from Σ(r+1)*d over
        //     the timeline's own `<S>` entries, including entries with no `r` (implicit r=0).
        val noDurationTemplateMpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD>
              <Period id="0">
                <AdaptationSet mimeType="video/mp4" width="1720" height="720">
                  <Representation id="v720" bandwidth="1100000">
                    <SegmentTemplate timescale="1000" initialization="init.mp4">
                      <SegmentTimeline>
                        <S d="4000" r="99"/>
                        <S d="2000"/>
                      </SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val ndVariants = StreamPlayExtractor.parseDashMasterVariants(
            "https://sacdn.hakunaymatata.com/dash/nodur/index.mpd",
            noDurationTemplateMpd,
            durationHintSeconds = 0.0
        )
        val nd720 = ndVariants.firstOrNull { it.quality == Qualities.P720.value }
        assertNotNull("Σ(r+1)*d timeline arithmetic must yield the 720p variant", nd720)
        // (100 x 4000 + 1 x 2000) / 1000 = 402 s.
        val ndExpectedMb = (1_100_000L / 8.0) * 402 / (1024.0 * 1024.0)
        assertEquals("Template without duration must derive timing from per-S d values", ndExpectedMb.toInt(), nd720!!.estimatedSizeMb)
    }

    @Test
    fun testUrduModeLedgerSemanticsAreIndependentOfEnglishDiversity() {
        // Urdu mode: English deliveries must never satisfy or suppress the Urdu completion walk,
        // and vice versa — the two ledgers are independent per AGENTS.md §6.
        val ledger = SubtitleSourceLedger(
            targetEnglishSources = TARGET_ENGLISH_SUBTITLE_SOURCES,
            targetUrduSources = UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES
        )
        fun deliver(url: String, lang: String, provenance: String, sourceKey: String) {
            val cleanedLang = cleanSubtitleLabel(lang)
            if (ledger.markEmitted(cleanedLang, url)) {
                val normalized = ledger.normalizeProvenance(provenance).orEmpty()
                if (isEnglishSubtitleLabel(cleanedLang)) ledger.recordEnglishDelivery(normalized, sourceKey)
                if (isUrduSubtitleLabel(cleanedLang)) ledger.recordUrduDelivery(normalized, sourceKey)
            }
        }

        assertTrue("Urdu mode starts with pending completion", ledger.needsUrduCompletion())

        // English sidecars arrive first: they count toward ENGLISH diversity only.
        deliver("https://sub.wyzie.ru/s/en.vtt", "English", "wyzie", "wyzie")
        deliver("https://api.subtitleapi.io/en.vtt", "English", "subtitleapi", "subtitles")
        deliver("https://rest.opensubtitles.org/en.srt", "English", "opensubtitles", "opensubtitles")
        assertEquals(3, ledger.englishSubtitleSources.size)
        assertTrue("English deliveries never satisfy the Urdu target", ledger.needsUrduCompletion())
        assertTrue(
            "An English-only provider is NOT suppressed from Urdu re-querying",
            !ledger.isUrduProviderCompleted("wyzie") && !ledger.isUrduSource("wyzie")
        )

        // Distinct Urdu provenances speak; the walk completes at exactly TARGET_URDU_SUBTITLE_SOURCES.
        val urduTarget = UrduSubtitleEngine.TARGET_URDU_SUBTITLE_SOURCES
        deliver("https://urducdn.example/1.srt", "Urdu", "subtitles", "subtitles")
        assertEquals(minOf(1, urduTarget), ledger.urduSubtitleSources.size)
        assertEquals("Completion verdict tracks the target", ledger.urduSubtitleSources.size < urduTarget, ledger.needsUrduCompletion())

        deliver("https://urducdn.example/2.srt", "Urdu (AI Translated)", "yifysub", "yifysub")
        assertEquals(minOf(2, urduTarget), ledger.urduSubtitleSources.size)

        // The same Urdu sidecar re-offered under a #fragment must not double-count.
        deliver("https://urducdn.example/1.srt#extra", "Urdu", "addic7ed", "addic7ed")
        assertEquals("Fragment-spoofed duplicate must not inflate Urdu diversity", minOf(2, urduTarget), ledger.urduSubtitleSources.size)

        // Extra distinct Urdu provenances keep recording (bonus diversity) without breaking the verdict.
        deliver("https://urducdn.example/3.srt", "اردو", "subtitlecat", "subtitlecat")
        assertFalse("Urdu target stays satisfied once met", ledger.needsUrduCompletion())
        assertTrue(
            "A provider that delivered Urdu is suppressed from Urdu re-querying",
            ledger.isUrduProviderCompleted("subtitlecat") && ledger.isUrduSource("subtitlecat")
        )

        // Cross-mode isolation: completing Urdu must not flip the English walk's verdicts.
        assertTrue(
            "English-only completion is judged independently of Urdu deliveries",
            ledger.isEnglishProviderCompleted("wyzie") && !ledger.isEnglishSource("subtitlecat")
        )
    }

    @Test
    fun testResolutionTruthClassifierIndependentProperties() {
        // Classifier-independent properties that do NOT re-derive the band table:
        // 1. Dimensional symmetry: classify(w, h) == classify(h, w).
        // 2. Ladder closure: every classification is one of the canonical rungs.
        // 3. Absolute anchors: standard broadcast/cinema dimensions resolve to their standard
        //    tiers — derived from the formats themselves, not from the band table.
        val canonicalTiers = setOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
        val widths = listOf(256, 320, 344, 426, 480, 640, 700, 720, 854, 960, 1024, 1280, 1332, 1366, 1720, 1800, 1850, 1920, 1998, 2048, 2560, 2650, 3440, 3840)
        val heights = listOf(144, 180, 240, 268, 270, 356, 360, 400, 480, 534, 540, 576, 600, 720, 768, 800, 1038, 1080, 1440, 2160)
        for (w in widths) {
            for (h in heights) {
                val forward = ResolutionTruth.classifyDimensions(w, h)
                assertEquals("classify(${w}, ${h}) must equal classify(${h}, ${w})", forward, ResolutionTruth.classifyDimensions(h, w))
                assertTrue(
                    "classify($w, $h) = $forward must be a canonical ladder rung",
                    forward in canonicalTiers || forward == Qualities.Unknown.value
                )
            }
        }
        // Absolute anchors (format-derived truth, not table-derived):
        val anchors = mapOf(
            "3840x2160" to 2160, "2560x1440" to 1440, "1920x1080" to 1080, "1280x720" to 720,
            "854x480" to 480, "640x360" to 360, "426x240" to 240, "256x144" to 144,
            "1998x1080" to 1080, "2048x1080" to 1080, "2048x858" to 1080, "1720x720" to 720,
            "1332x720" to 720, "1850x720" to 720, "2650x1080" to 1080, "1200x480" to 480,
            "864x360" to 360, "1920x800" to 1080, "1280x534" to 720, "854x356" to 480,
            "640x268" to 360, "768x576" to 480
        )
        for ((label, expectedTier) in anchors) {
            val (w, h) = label.split("x").map { it.toInt() }
            assertEquals("Standard dimension $label must resolve to ${expectedTier}p", expectedTier, ResolutionTruth.classifyDimensions(w, h))
            assertEquals("Standard dimension $label must hold for the swapped order", expectedTier, ResolutionTruth.classifyDimensions(h, w))
        }
    }

    @Test
    fun testDashEmptyAttrsAdaptationSetShape() {
        // 4. Empty-attrs paired `<AdaptationSet>` (a shape the contract names explicitly) and a
        //    self-closing set directly before it — neither may swallow the other's representations.
        val emptyAttrsMpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD mediaPresentationDuration="PT30M">
              <Period id="0">
                <AdaptationSet mimeType="video/mp4"/>
                <Representation id="v720" bandwidth="1100000" width="1720" height="720"/>
                <Representation id="v1080" bandwidth="2200000" width="1920" height="800"/>
                <AdaptationSet mimeType="audio/mp4" lang="en">
                  <Representation id="a0" bandwidth="128000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val emptyAttrsVariants = StreamPlayExtractor.parseDashMasterVariants(
            "https://sacdn.hakunaymatata.com/dash/empty/index.mpd",
            emptyAttrsMpd
        )
        assertNotNull("Empty-attrs AdaptationSet reps must be parsed (720p)", emptyAttrsVariants.firstOrNull { it.quality == Qualities.P720.value })
        assertNotNull("Empty-attrs AdaptationSet reps must be parsed (1080p)", emptyAttrsVariants.firstOrNull { it.quality == Qualities.P1080.value })
        val ea720 = emptyAttrsVariants.firstOrNull { it.quality == Qualities.P720.value }!!
        // 30 min root duration, video 1.1 Mbps + 128 kbps median audio.
        val eaExpectedMb = ((1_100_000L + 128_000L) / 8.0) * (30 * 60) / (1024.0 * 1024.0)
        assertEquals("Root duration + median audio pairing must hold for the empty-attrs shape", eaExpectedMb.toInt(), ea720.estimatedSizeMb)
    }

    @Test
    fun testResolutionTruthExhaustiveShortDimSweepAgreesWithTextLayer() {
        // Exhaustive short-dimension sweep across every band and edge: for each shortDim in
        // 100..2200 at three aspect shapes (square, 1.85:1, 2.39:1), the text layer must classify
        // exactly what ResolutionTruth classifies (delegation leaves no room for drift).
        for (shortDim in 100..2200) {
            val longDims = listOf(shortDim, (shortDim * 185 / 100), (shortDim * 239 / 100))
            for (longDim in longDims) {
                // classifyDimensions returns Unknown only when both dims are <= 0, so every pair
                // in this sweep has a defined tier and all THREE consumers must agree exactly.
                val expected = ResolutionTruth.classifyDimensions(longDim, shortDim)
                assertEquals("Text layer must classify ${longDim}x${shortDim} through ResolutionTruth", expected, StreamLinkOptimizer.extractQualityFromText("${longDim}x${shortDim}"))
                assertEquals("Provider-label layer must classify ${longDim}x${shortDim} through ResolutionTruth", expected, StreamPlayExtractor.providerLabelQuality("${longDim}x${shortDim}"))
            }
        }

        // Sub-480p regression (Qualities.Unknown.value == 400): a WxH claim classifying below 480p
        // must never fall through to the height regex, which can match the WIDTH token ("720x360"
        // would otherwise inflate to a false 720p).
        assertEquals(Qualities.P360.value, StreamPlayExtractor.providerLabelQuality("720x360"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.providerLabelQuality("720x304"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.providerLabelQuality("640x268"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.providerLabelQuality("480x360"))
        assertEquals(Qualities.P360.value, StreamPlayExtractor.providerLabelQuality("854x360"))
        assertEquals(Qualities.P144.value, StreamPlayExtractor.providerLabelQuality("320x180"))
        assertEquals(Qualities.P240.value, StreamPlayExtractor.providerLabelQuality("480x270"))
    }

    @Test
    fun testTtlBoundedCacheEvictionPolicy() {
        var now = 1_000_000L
        val cache = TtlBoundedCache<String, Int>(ttlMs = 100L, maxEntries = 3, clock = { now })

        cache.put("a", 1)
        cache.put("b", 2)
        assertEquals(1, cache.get("a"))
        assertEquals(2, cache.get("b"))

        // TTL boundary: at exactly ttlMs the entry is expired; just below it is live.
        now += 99L
        assertEquals("Entry one tick inside the TTL must still be live", 1, cache.get("a"))
        now += 1L
        assertNull("Entry at exactly ttlMs must be expired", cache.get("a"))

        // Expired entries are swept before live ones when the cache overflows.
        now += 1_000L
        cache.put("c", 3)
        cache.put("d", 4)
        assertEquals(2, cache.size)

        // Oldest-live entries are trimmed when nothing is expired.
        cache.clear()
        now = 2_000_000L
        cache.put("k1", 1)
        now += 1
        cache.put("k2", 2)
        now += 1
        cache.put("k3", 3)
        now += 1
        cache.put("k4", 4)
        assertEquals("Overflow trims down to maxEntries", 3, cache.size)
        assertNull("Oldest entry (k1) is trimmed first", cache.get("k1"))
        assertEquals(2, cache.get("k2"))
        assertEquals(4, cache.get("k4"))
    }

    @Test
    fun testDashAdaptationSetAttributeInheritanceAndDayPrefixDuration() {
        val mpdWithInheritedAttrs = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="P0DT1H30M0S">
              <Period id="0">
                <AdaptationSet id="0" mimeType="video/mp4" width="1920" height="800">
                  <Representation id="v1080" bandwidth="2200000" />
                </AdaptationSet>
                <AdaptationSet id="1" mimeType="video/mp4" width="1720" height="720">
                  <Representation id="v720" bandwidth="1100000" />
                </AdaptationSet>
                <AdaptationSet id="2" mimeType="audio/mp4" lang="en">
                  <Representation id="a0" bandwidth="128000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val masterUrl = "https://sacdn.hakunaymatata.com/dash/movie_test2/index_web.mpd"
        val variants = StreamPlayExtractor.parseDashMasterVariants(masterUrl, mpdWithInheritedAttrs)
        assertEquals(2, variants.size)
        val v720 = variants.firstOrNull { it.quality == Qualities.P720.value }
        val v1080 = variants.firstOrNull { it.quality == Qualities.P1080.value }
        assertNotNull("720p inherited from AdaptationSet must be extracted", v720)
        assertNotNull("1080p inherited from AdaptationSet must be extracted", v1080)
        assertEquals("$masterUrl#vid=720p", v720!!.url)
        assertEquals("$masterUrl#vid=1080p", v1080!!.url)
        assertTrue("P0DT1H30M0S duration must produce positive estimated size", (v720.estimatedSizeMb ?: 0) > 700)
        assertTrue("1080p estimated size must exceed 720p estimated size", (v1080.estimatedSizeMb ?: 0) > (v720.estimatedSizeMb ?: 0))
    }

    @Test
    fun testAllCinemaWidescreenAspectRatiosResolveAccurately() {
        // 1.85:1, 2.00:1, 2.20:1, 2.39:1 in both normalizeHlsResolutionToQuality and extractQualityFromText
        val fhdDims = listOf(1920 to 1038, 1998 to 1080, 2048 to 1080, 2160 to 1080, 2376 to 1080, 2580 to 1080)
        for ((w, h) in fhdDims) {
            assertEquals("Dimension ${w}x$h must normalize to 1080p", Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(w, h, "stream.m3u8"))
            assertEquals("Text ${w}x$h must extract 1080p", Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Movie ${w}x$h"))
        }

        val hdDims = listOf(1280 to 692, 1332 to 720, 1440 to 720, 1584 to 720, 1720 to 720, 1800 to 720)
        for ((w, h) in hdDims) {
            assertEquals("Dimension ${w}x$h must normalize to 720p", Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(w, h, "stream.m3u8"))
            assertEquals("Text ${w}x$h must extract 720p", Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie ${w}x$h"))
        }
    }

    @Test
    fun testAnimeEnglishSubtitlesCollectSixTopSources() {
        assertEquals("Movie/TV target English subtitle sources must remain 5", 5, TARGET_ENGLISH_SUBTITLE_SOURCES)
        assertEquals("Anime target English subtitle sources must be 6", 6, ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES)

        // Exercises the PRODUCTION SubtitleSourceLedger wired into StreamPlay + StreamPlayAnime —
        // the exact markEmitted/recordEnglishDelivery calls the emission closures make — so the
        // 6-source contract is proven against the real dedupe/provenance/completion logic, not a
        // local transcription of it.
        val ledger = SubtitleSourceLedger(targetEnglishSources = ANIME_TARGET_ENGLISH_SUBTITLE_SOURCES)

        fun deliverSubtitle(url: String, lang: String, provenanceHeader: String?, sourceKey: String) {
            val cleanedLang = cleanSubtitleLabel(lang)
            if (ledger.markEmitted(cleanedLang, url)) {
                val provenance = ledger.normalizeProvenance(
                    provenanceHeader ?: sourceKey.ifBlank { StreamPlayShield.hostOf(url) }
                ).orEmpty()
                if (isEnglishSubtitleLabel(cleanedLang)) {
                    ledger.recordEnglishDelivery(provenance, sourceKey)
                }
            }
        }

        // Duplicate URL with a #fragment routing suffix must be deduplicated by the ledger key
        deliverSubtitle("https://sub.wyzie.ru/s/1.vtt", "English [Wyzie]", "wyzie", "wyzie")
        deliverSubtitle("https://sub.wyzie.ru/s/1.vtt#animepahe", "English [Wyzie]", "animepahe", "animepahe")
        assertEquals("Fragment-spoofed duplicate subtitle URL must not inflate source count", 1, ledger.englishSubtitleSources.size)
        assertTrue(ledger.englishSubtitleSources.contains("wyzie"))

        // Animex emitting ZokoAnime must count 1 provenance ("zokoanime") toward diversity while
        // the container provider ("animex") is tracked separately for completion suppression.
        deliverSubtitle("https://zoko.example/sub1.vtt", "English [ZokoAnime]", "zokoanime", "animex")
        assertEquals("Single subtitle with explicit provenance must add exactly 1 source", 2, ledger.englishSubtitleSources.size)
        assertTrue(ledger.isEnglishSource("zokoanime"))
        assertFalse("Container provider key must not double-count toward diversity", ledger.isEnglishSource("animex"))
        assertTrue("Container provider key must suppress re-querying", ledger.isEnglishProviderCompleted("animex"))

        // Provenance normalization is lowercase and case-insensitive across surfaces
        deliverSubtitle("https://anizone.to/sub/1.ass", "English (ASS) [AniZone]", "AniZone", "anizone")
        assertTrue(ledger.isEnglishSource("anizone"))
        assertFalse("Raw case variant must never be stored in the normalized source set", ledger.englishSubtitleSources.contains("AniZone"))
        assertEquals(3, ledger.englishSubtitleSources.size)

        // Collect remaining distinct anime English subtitle sources up to 6
        deliverSubtitle("https://megaplay.buzz/sub/1.vtt", "English [MegaPlay]", "megaplay", "megaplay")
        deliverSubtitle("https://kaa.lt/sub/1.vtt", "English [KickAssAnime]", "kickassanime", "animex")
        deliverSubtitle("https://subs.bright67.online/sub/1.srt", "English [CineSrc]", "cinesrc", "cinesrc")
        assertEquals("Six distinct anime English subtitle sources must be collected", 6, ledger.englishSubtitleSources.size)
        assertFalse("6 sources must satisfy the anime completion target", ledger.needsEnglishCompletion())

        // A provider whose fan-out delivered is never re-queried during completion
        val providers = listOf("anizone", "megaplay", "animex", "cinesrc", "wyzie", "zokoanime", "vidlink", "kickassanime")
        val pending = providers.filter { !ledger.isEnglishSource(it) && !ledger.isEnglishProviderCompleted(it) }
        assertEquals("Only providers that never delivered English may be re-queried", listOf("vidlink"), pending)
    }

    @Test
    fun testVidlinkDashMpd720pVariantExtractionAndSizing() {
        testVidLinkDashMpdParsingAndWidescreen720pExtraction()
        testDashAdaptationSetAttributeInheritanceAndDayPrefixDuration()
    }

    @Test
    fun testVidlinkBcdnxwPreservesRefererWhileBcdnAndSacdnStripReferer() {
        testVidLinkBcdnxwPreservesFilmboomRefererWhileBcdnAndSacdnStripReferer()
    }

    @Test
    fun testConstantHeightCinemaWidescreen720pAnd1080pClassification() {
        testConstantHeightAndConstantWidthWidescreenResolutionNormalization()
        testAllCinemaWidescreenAspectRatiosResolveAccurately()
    }

    @Test
    fun testResolutionTruthIsTheSingleMappingForManifestAndTextLayers() {
        // The manifest layer (StreamPlayExtractor.normalizeHlsResolutionToQuality) and the text
        // layer (StreamLinkOptimizer.extractQualityFromText on exact `WxH` claims) must classify
        // every dimension pair through the SAME ResolutionTruth bands — there is no second table
        // left to drift apart (the former hand-maintained FHD/HD/SD/P360 WxH regex families were
        // retired in favour of delegation).
        val widths = listOf(
            128, 344, 480, 640, 641, 659, 660, 700, 800, 840, 854, 860, 861, 900, 919, 920, 921,
            1000, 1024, 1200, 1249, 1250, 1280, 1281, 1329, 1330, 1366, 1400, 1584, 1720, 1800,
            1849, 1850, 1851, 1920, 1921, 1989, 1990, 1998, 2048, 2560, 2649, 2650, 2651, 2700,
            3440, 3840
        )
        val heights = listOf(
            144, 200, 240, 268, 320, 350, 353, 354, 356, 360, 372, 373, 400, 430, 466, 467, 468,
            480, 492, 493, 534, 540, 550, 576, 600, 650, 689, 690, 700, 720, 740, 741, 768, 800,
            950, 1038, 1039, 1040, 1080, 1100, 1101, 1350, 1419, 1420, 1440, 1460, 1461, 2000,
            2139, 2140, 2160, 2180, 2181
        )
        for (w in widths) {
            for (h in heights) {
                val dimension = StreamPlayExtractor.normalizeHlsResolutionToQuality(w, h, "", null)
                // `x` is alphanumeric, so the explicit-numeric regex never fires inside a WxH
                // claim: the text layer classifies EVERY exact dimension through ResolutionTruth,
                // the same bands the manifest layer uses.
                assertEquals(
                    "Text layer and manifest layer must agree through ResolutionTruth for ${w}x${h}",
                    dimension,
                    StreamLinkOptimizer.extractQualityFromText("${w}x${h}")
                )
            }
        }
        // Explicit numeric tokens outside a WxH pair still fire before delegation.
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("abc 720"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie 720p 1280x534"))
        assertEquals(Qualities.P360.value, StreamLinkOptimizer.extractQualityFromText("Movie 360p"))

        // Acceptance endpoints that the former regex families missed (1850x720, 2650x1080,
        // DCI-scope 2048x1038, 856x480, 3440x1440) and the formerly conflicting regions
        // (800-920x350-353, 1200-1249x460-467) now classify identically in both layers.
        assertEquals(Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1850, 720, ""))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("1850x720"))
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(2650, 1080, ""))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("2650x1080"))
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(2048, 1038, ""))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("2048x1038"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("1998x1038"))
        assertEquals(Qualities.P480.value, StreamLinkOptimizer.extractQualityFromText("856x480"))
        assertEquals(Qualities.P1440.value, StreamLinkOptimizer.extractQualityFromText("3440x1440"))
        for (w in listOf(800, 854, 900, 920)) {
            for (h in listOf(350, 351, 352, 353)) {
                assertEquals("Letterbox 360/480 conflict region must agree for ${w}x$h", StreamPlayExtractor.normalizeHlsResolutionToQuality(w, h, ""), StreamLinkOptimizer.extractQualityFromText("${w}x${h}"))
            }
        }
        for (w in listOf(1200, 1249)) {
            for (h in listOf(460, 461, 466, 467)) {
                assertEquals("480/720 conflict region must agree for ${w}x$h", StreamPlayExtractor.normalizeHlsResolutionToQuality(w, h, ""), StreamLinkOptimizer.extractQualityFromText("${w}x${h}"))
            }
        }
        // True-height 360 encodes at 854-width stay 360p; the 2.4:1 letterbox 356 stays 480p.
        assertEquals(Qualities.P360.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(854, 360, ""))
        assertEquals(Qualities.P360.value, StreamLinkOptimizer.extractQualityFromText("854x360"))
        assertEquals(Qualities.P480.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(854, 356, ""))
        assertEquals(Qualities.P480.value, StreamLinkOptimizer.extractQualityFromText("854x356"))
        // Ultrawide 1080p beyond the 2650 guard snaps back to the 1080p rung, not 1440p.
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(2700, 1080, ""))
        assertEquals(Qualities.P720.value, StreamPlayExtractor.normalizeHlsResolutionToQuality(1366, 768, ""))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("1366x768"))
    }

    @Test
    fun testCrossServerQualityGateDedupesKnownQualitiesAcrossServers() {
        val gate = CrossServerQualityGate()
        fun meowLink(quality: Int, url: String, name: String): ExtractorLink = createLink(
            source = "MeowTV",
            name = name,
            url = url,
            quality = quality,
            type = ExtractorLinkType.M3U8
        )

        assertTrue(gate.allow(meowLink(Qualities.P480.value, "https://srv-a/480.m3u8", "MeowTV English [480p]")))
        assertFalse("480p mirror from a later server must be deduplicated", gate.allow(meowLink(Qualities.P480.value, "https://srv-b/480.m3u8", "MeowTV English [480p]")))
        assertTrue(gate.allow(meowLink(Qualities.P1080.value, "https://srv-a/1080.m3u8", "MeowTV English [1080p]")))
        assertFalse("1080p mirror from a later server must be deduplicated", gate.allow(meowLink(Qualities.P1080.value, "https://srv-b/1080.m3u8", "MeowTV English [1080p]")))
        assertTrue("720p always passes", gate.allow(meowLink(Qualities.P720.value, "https://srv-b/720.m3u8", "MeowTV English [720p]")))
        assertTrue("a second 720p mirror must still pass (smallest-size selection)", gate.allow(meowLink(Qualities.P720.value, "https://srv-c/720.m3u8", "MeowTV English [720p]")))
        assertTrue("Unknown/[Auto] lanes always pass", gate.allow(meowLink(Qualities.Unknown.value, "https://srv-d/master.m3u8", "MeowTV English [Auto]")))
        assertTrue("a second Unknown lane must also pass", gate.allow(meowLink(Qualities.Unknown.value, "https://srv-e/master.m3u8", "MeowTV Auto")))
    }

    @Test
    fun testVidLinkCdnPolicyIsTheSingleRefererSourceOfTruth() {
        // Policy classification
        assertEquals(VidLinkCdnPolicy.RefererMode.REQUIRE_FILMBOOM, VidLinkCdnPolicy.modeFor("https://bcdnxw.hakunaymatata.com/resource/720.mp4"))
        assertEquals(VidLinkCdnPolicy.RefererMode.STRIP, VidLinkCdnPolicy.modeFor("https://sacdn.hakunaymatata.com/dash/i/index_web.mpd"))
        assertEquals(VidLinkCdnPolicy.RefererMode.STRIP, VidLinkCdnPolicy.modeFor("https://bcdn.hakunaymatata.com/resource/1080.mp4"))
        assertEquals(VidLinkCdnPolicy.RefererMode.THIRD_PARTY, VidLinkCdnPolicy.modeFor("https://proxy.vendor.example/v.mp4"))

        // bcdnxw: filmboom proven default overrides vidlink/embed artifacts, keeps genuine filmboom referers
        assertEquals("https://filmboom.top/", VidLinkCdnPolicy.effectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", null))
        assertEquals("https://filmboom.top/", VidLinkCdnPolicy.effectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", "https://vidlink.pro/"))
        assertEquals("https://filmboom.top/", VidLinkCdnPolicy.effectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", "https://embed.example/x"))
        assertEquals("https://filmboom.top/", VidLinkCdnPolicy.effectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", "https://filmboom.top/"))

        // bcdn/sacdn strip everything; third-party keeps only genuine third-party referers
        assertEquals("", VidLinkCdnPolicy.effectiveReferer("https://sacdn.hakunaymatata.com/x.mpd", "https://filmboom.top/"))
        assertEquals("", VidLinkCdnPolicy.effectiveReferer("https://proxy.vendor.example/v.mp4", "https://vidlink.pro/"))
        assertEquals("", VidLinkCdnPolicy.effectiveReferer("https://proxy.vendor.example/v.mp4", ""))
        assertEquals("https://mirror.example/v.mp4", VidLinkCdnPolicy.effectiveReferer("https://proxy.vendor.example/v.mp4", "https://mirror.example/v.mp4"))

        // apply() end-to-end: REQUIRE_FILMBOOM, STRIP and THIRD_PARTY header rewrites
        val filmboomHeaders = mutableMapOf("Referer" to "https://vidlink.pro/", "Origin" to "https://vidlink.pro")
        VidLinkCdnPolicy.apply("https://bcdnxw.hakunaymatata.com/1080.mp4", filmboomHeaders)
        assertEquals("https://filmboom.top/", filmboomHeaders["Referer"])
        assertEquals("https://filmboom.top", filmboomHeaders["Origin"])

        val stripHeaders = mutableMapOf("Referer" to "https://filmboom.top/", "Origin" to "https://filmboom.top", "Cookie" to "CloudFront-Policy=a")
        VidLinkCdnPolicy.apply("https://sacdn.hakunaymatata.com/dash/x.mpd", stripHeaders)
        assertFalse(stripHeaders.containsKey("Referer"))
        assertFalse(stripHeaders.containsKey("Origin"))
        assertEquals("CloudFront-Policy=a", stripHeaders["Cookie"])

        val thirdPartyHeaders = mutableMapOf("Referer" to "https://filmboom.top/", "Origin" to "https://x.example")
        VidLinkCdnPolicy.apply("https://proxy.vendor.example/v.mp4", thirdPartyHeaders)
        assertFalse(thirdPartyHeaders.containsKey("Referer"))
        assertEquals("https://x.example", thirdPartyHeaders["Origin"])

        // Download + playback surfaces route through the SAME policy outcomes
        assertEquals(
            "https://filmboom.top/",
            StreamLinkOptimizer.getEffectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", "https://vidlink.pro/", emptyMap(), "vidlink", "Vidlink")
        )
        assertEquals(
            "",
            StreamLinkOptimizer.getEffectiveReferer("https://sacdn.hakunaymatata.com/x.mpd", "https://filmboom.top/", emptyMap(), "vidlink", "Vidlink")
        )
        val downloadHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf("Referer" to "https://vidlink.pro/"),
            url = "https://bcdnxw.hakunaymatata.com/720.mp4",
            referer = "https://vidlink.pro/",
            linkType = ExtractorLinkType.VIDEO,
            source = "vidlink",
            name = "Vidlink [720p]"
        )
        assertEquals("https://filmboom.top/", downloadHeaders["Referer"])
        assertEquals("https://filmboom.top", downloadHeaders["Origin"])
    }
}

