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
 * Acceptance suite for the curated v25 SOTA StreamPlay source registry.
 *
 * It pins down, as executable contracts:
 *  1. exactly which sources may surface in the UI (VidLink, Cineby, CineSrc, VidFast, MegaPlay,
 *     MeowTV, AniZone, AnimePahe, Animex) and their strict launch hierarchy,
 *  2. the ranking of those sources and of the CDN hosts they serve from, plus VidRock's and
 *     VidSrc's complete decommissioning,
 *  3. the settings migration that removes every decommissioned provider (including VidRock and
 *     VidSrc) from an existing install while enabling the v25 SOTA sources,
 *  4. the bounded ordering windows of [StreamLinkOptimizer.PriorityStreamDispatcher],
 *  5. known-answer vectors for the offline VidLink token encryptor,
 *  6. protocol parsers for Cineby, CineSrc (CSP4 PoW + Nebula/Wave/Surge), MegaPlay, Animex,
 *     AniZone and AnimePahe (dedicated v25 suite), and HLS Master Variant + Size Estimation,
 *  7. the exception gates that keep the Movie/TV, Anime, and Universal segments of the registry
 *     properly partitioned, and
 *  8. the stream-only download-exclusion contract (`DOWNLOAD_EXCLUDED_PROVIDER_IDS = setOf("vidfast")`).
 */
class StreamPlayCuratedRegistryV25Test {

    private val curatedIds = setOf(
        "vidlink",
        "cineby",
        "cinesrc",
        "vidfast",
        "megaplay",
        "meowtv",
        "anizone",
        "animepahe",
        "animex"
    )

    /** Authoritative launch hierarchy: strictly descending boost per provider id. */
    private val boostByProvider = linkedMapOf(
        "vidlink" to 100f,
        "cineby" to 95f,
        "cinesrc" to 94f,
        "vidfast" to 93f,
        "megaplay" to 92f,
        "meowtv" to 91f,
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
        "cineby" to LatencyTier.TIER_1,
        "cinesrc" to LatencyTier.TIER_1,
        "vidfast" to LatencyTier.TIER_1,
        "megaplay" to LatencyTier.TIER_1,
        "meowtv" to LatencyTier.TIER_1,
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
        assertEquals("Cineby", byId.getValue("cineby").name)
        assertEquals("CineSrc", byId.getValue("cinesrc").name)
        assertEquals("VidFast", byId.getValue("vidfast").name)
        assertEquals("MegaPlay", byId.getValue("megaplay").name)
        assertEquals("MeowTV", byId.getValue("meowtv").name)
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

        // The v25 additions are live members of the curated set.
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
        assertEquals(95, rankOf("Cineby", "Cineby Tampa [1080p]", "https://sun.paleoak.top/x.m3u8"))
        assertEquals(94, rankOf("CineSrc", "CineSrc Nebula [720p]", "https://nebula.bright67.online/hls2/master.txt"))
        assertEquals(93, rankOf("VidFast", "VidFast [1080p]", "https://cdn.example/c.m3u8"))
        assertEquals(92, rankOf("MegaPlay", "MegaPlay [Sub] [Original]", "https://fetch.nexabloom.top/e.m3u8"))
        assertEquals(91, rankOf("MeowTV", "MeowTV [1080p]", "https://cdn.example/m.m3u8"))
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
        assertEquals(95, rankOf("Unknown", "stream", "https://sun.paleoak.top/file2/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://e2e.majorplay.net/hls/master.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://nebula.bright67.online/hls2/01/master.txt"))
        assertEquals(94, rankOf("Unknown", "stream", "https://ice.bright67.online/Deadpool/720p/playlist.jpg"))
        assertEquals(94, rankOf("Unknown", "stream", "https://cinesrc.st/api/v2/stream"))
        assertEquals(0, rankOf("Unknown", "stream", "https://vidsrc.buzz/_stream?id=abc"))
        assertEquals(93, rankOf("Unknown", "stream", "https://vidfast.vc/e/abc.m3u8"))
        assertEquals(93, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://theemoviedb.hexa.su/api/tmdb/movie/550/images"))
        assertEquals(92, rankOf("Unknown", "stream", "https://fetch.nexabloom.top/hls/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://megaplay.buzz/stream/s-2/123/sub"))
        assertEquals(91, rankOf("Unknown", "stream", "https://img1.hscow.com/thumb.jpg"))
        assertEquals(91, rankOf("Unknown", "stream", "https://cdn.vidjoy.org/hls/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://i-arch-400.krico443jez.com/x.m3u8"))
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
    // 2. SETTINGS MIGRATION (v25)
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
    fun testPlaybackOrderingIsStrictlyRankDominatedAcrossTheV25Registry() {
        boostByProvider.keys.toList().let { hierarchy ->
            for (i in 0 until hierarchy.size - 1) {
                val higher = createLink(
                    hierarchy[i],
                    "${hierarchy[i]} 360p",
                    "https://cdn.example/sd$i.m3u8",
                    Qualities.P360.value
                )
                val lower = createLink(
                    hierarchy[i + 1],
                    "${hierarchy[i + 1]} 720p",
                    "https://cdn.example/hd$i.m3u8",
                    Qualities.P720.value
                )
                assertTrue(
                    "${hierarchy[i]} 360p must outrank ${hierarchy[i + 1]} 720p",
                    StreamLinkOptimizer.getStreamCompositeScore(higher) >
                        StreamLinkOptimizer.getStreamCompositeScore(lower)
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
        val vidlink360 = createLink("Vidlink", "Vidlink 360p", "https://bcdn.hakunaymatata.com/bt/360.mp4", Qualities.P360.value)
        val cineby1080 = createLink("Cineby", "Cineby 1080p", "https://sun.paleoak.top/file2/1080.m3u8", Qualities.P1080.value)
        val cinesrc1080 = createLink("CineSrc", "CineSrc 1080p", "https://nebula.bright67.online/hls2/1080/master.txt", Qualities.P1080.value)
        val animex1080 = createLink("Animex", "Animex 1080p", "https://animex.one/e/1080.m3u8", Qualities.P1080.value)

        val scoreVidlink360 = StreamLinkOptimizer.getStreamCompositeScore(vidlink360)
        val scoreCineby1080 = StreamLinkOptimizer.getStreamCompositeScore(cineby1080)
        val scoreCineSrc1080 = StreamLinkOptimizer.getStreamCompositeScore(cinesrc1080)
        val scoreAnimex1080 = StreamLinkOptimizer.getStreamCompositeScore(animex1080)

        assertTrue("VidLink composite score must dominate Cineby", scoreVidlink360 > scoreCineby1080)
        assertTrue("Cineby composite score must dominate CineSrc", scoreCineby1080 > scoreCineSrc1080)
        assertTrue("VidLink composite score must dominate Animex", scoreVidlink360 > scoreAnimex1080)
        assertTrue("VidLink must score >= 1,000,000", scoreVidlink360 >= 1_000_000f)
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
        listOf("vidfast", "meowtv").forEach { id ->
            assertTrue("$id must be a NON_ANIME provider", NON_ANIME_PROVIDERS.contains(id))
            assertFalse("$id must not be an ANIME_ONLY provider", ANIME_ONLY_PROVIDERS.contains(id))
        }
        assertFalse("Decommissioned vidsrcsu must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains("vidsrcsu"))
        // Dedicated Anime SOTA sources must only run on anime titles.
        listOf("megaplay", "anizone", "animepahe", "animex").forEach { id ->
            assertTrue("$id must be an ANIME_ONLY provider", ANIME_ONLY_PROVIDERS.contains(id))
            assertFalse("$id must not be in NON_ANIME_PROVIDERS", NON_ANIME_PROVIDERS.contains(id))
        }
        // Universal SOTA sources (VidLink, Cineby, CineSrc) run on Movie, TV, and Anime.
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
        assertEquals(95, rankOf("Unknown", "stream", "https://sun.paleoak.top/file2/master.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://nebula.bright67.online/hls2/master.txt"))
        assertEquals(93, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://fetch.nexabloom.top/hls/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://img1.hscow.com/i/master.m3u8"))
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
                "api.wecollege.net", "player.videasy.to", "sun.paleoak.top", "e2e.majorplay.net",
                "cinesrc.st", "nebula.bright67.online", "ice.bright67.online", "subs.bright67.online", "api.wing.st",
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
}
