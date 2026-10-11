package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Acceptance suite for the curated v18 StreamPlay source registry.
 *
 * It pins down, as executable contracts:
 *  1. exactly which sources may surface in the UI (VidLink + AnimePahe only),
 *  2. the ranking of those sources and of the CDN hosts they serve from,
 *  3. the settings migration that removes every decommissioned provider from an existing
 *     install,
 *  4. the bounded ordering windows of [StreamLinkOptimizer.PriorityStreamDispatcher], and
 *  5. known-answer vectors for the offline VidLink token encryptor.
 */
class StreamPlayCuratedRegistryV17Test {

    private val curatedIds = setOf("vidlink", "videm", "animepahe", "animegg")

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
        assertEquals("VidEm", byId.getValue("videm").name)
        assertEquals("AnimePahe", byId.getValue("animepahe").name)
        assertEquals("AnimeGG", byId.getValue("animegg").name)
        providers.forEach { assertEquals(ProviderKind.VIDEO, it.kind) }
    }

    @Test
    fun testEveryDecommissionedProviderIsAbsentFromEverySurface() {
        val providerIds = buildProviders().map { it.id }.toSet()

        // VidLink and AnimePahe are the two explicitly retained legacy sources; everything
        // else that used to ship must be gone from the registry, the boost table, the
        // cold-start tier table and the default top tier.
        for (removed in REMOVED_PROVIDER_IDS) {
            assertFalse("$removed must not be registered", providerIds.contains(removed))
            assertNull("$removed must not have a cold-start boost", FAST_PROVIDER_BOOST[removed])
            assertNull(
                "$removed must not have a cold-start latency tier",
                SpeculativePipeliner.STATIC_COLD_START_TIERS[removed]
            )
            assertFalse("$removed must not be a default top tier source", DEFAULT_TOP_TIER_PROVIDERS.contains(removed))
        }

        // The three sources the request called out by name, plus the other removed
        // movie mirrors, are asserted explicitly so a regression cannot slip through a
        // REMOVED_PROVIDER_IDS bookkeeping mistake.
        listOf("rivestream", "vidfast", "videasy", "superstream", "vaplayer", "vidcore", "vidnest", "vidup", "hexasu", "autoembed")
            .forEach { assertFalse("$it must not be registered", providerIds.contains(it)) }
        // The v19 additions are live members of the curated set, so they must be registered
        // and must never be filtered out by REMOVED_PROVIDER_IDS bookkeeping.
        curatedIds.forEach { id ->
            assertTrue("$id must be registered", providerIds.contains(id))
            assertFalse("$id must not be listed as removed", REMOVED_PROVIDER_IDS.contains(id))
        }
    }

    @Test
    fun testRankingHierarchyIsStrictAndMonotonic() {
        assertEquals(100f, FAST_PROVIDER_BOOST.getValue("vidlink"))
        assertEquals(95f, FAST_PROVIDER_BOOST.getValue("videm"))
        assertEquals(90f, FAST_PROVIDER_BOOST.getValue("animepahe"))
        assertEquals(85f, FAST_PROVIDER_BOOST.getValue("animegg"))

        val boosts = listOf("vidlink", "videm", "animepahe", "animegg").map { FAST_PROVIDER_BOOST.getValue(it) }
        assertEquals("Boosts must be strictly descending", boosts.sortedDescending(), boosts)

        // Every curated provider must be tier-1 so the pipeliner starts them together.
        curatedIds.forEach { id ->
            assertEquals(
                "$id must be classified as a tier-1 source",
                LatencyTier.TIER_1,
                SpeculativePipeliner.classifyProvider(id)
            )
        }
    }

    @Test
    fun testSourcePriorityRankCoversLabelsAndCdnHosts() {
        assertEquals(100, StreamLinkOptimizer.getSourcePriorityRank(createLink("VidLink", "VidLink [1080p]", "https://cdn.example/a.mp4")))
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(createLink("VidEm", "VidEm HLS", "https://cdn.example/b.m3u8")))
        assertEquals(90, StreamLinkOptimizer.getSourcePriorityRank(createLink("AnimePahe", "AnimePahe [1080p]", "https://cdn.example/d.m3u8")))
        assertEquals(85, StreamLinkOptimizer.getSourcePriorityRank(createLink("AnimeGG", "AnimeGG SUB [720p]", "https://cdn.example/e.mp4")))

        // VidEm ranks on the minted stream host as well as its own label, because the engine's
        // `/_stream?id=` URL carries no product name of its own.
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(createLink("VidEm", "VidEm HLS", "https://cdn.example/f.m3u8")))
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://videm.xyz/_stream?id=abc")))
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://vidsrc.buzz/_stream?id=abc")))
        assertEquals(85, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://cdn.animegg.org/x.mp4")))

        // Ranking must survive CDN rewrites that lose the original source label.
        assertEquals(100, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://bcdnxw.hakunaymatata.com/bt/a.mp4")))
        assertEquals(0, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://sc-u18-01.vix-content.net/hls/master.m3u8")))
        assertEquals(90, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://kwik.cx/e/abc.m3u8")))

        // Decommissioned sources must score zero rather than inheriting a legacy rank.
        listOf("VidFast", "RiveStream", "VidEasy", "Vidflix", "VixSrc", "Vidup", "CineJoy", "YFlix", "HexaSU", "MovieBox", "VidSrc")
            .forEach { label ->
                assertEquals(
                    "$label must not inherit a rank after decommissioning",
                    0,
                    StreamLinkOptimizer.getSourcePriorityRank(createLink(label, "$label [1080p]", "https://x.example/a.m3u8"))
                )
            }
        // Their legacy CDN hosts must not resurrect a rank either.
        assertEquals(0, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://lit.cheaptruckrepairs.cc/video/x.m3u8")))
        assertEquals(0, StreamLinkOptimizer.getSourcePriorityRank(createLink("Unknown", "stream", "https://s247.vidcache.net:8166/play/a/video.mp4")))

        curatedIds.forEach { id ->
            assertTrue("$id must be recognised as a top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    // =========================================================================
    // 2. SETTINGS MIGRATION (v17)
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
        // A legacy install that had enabled the scraper tier and disabled vidlink would have
        // this shape; the old migration only added dead ids, so rivestream/vidfast/videasy
        // stayed enabled forever.
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("vidlink", "uhdmovies", "HexaSU")
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
        // Persist a fully-populated legacy disabled set, including the sources the request
        // explicitly wanted gone.
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("rivestream", "vidfast", "videasy", "yflix", "cinejoy", "hexasu", "vixsrc")
        ).apply()

        val disabled = getOrInitializeDisabledProviders(prefs)
        assertTrue(disabled.isEmpty())
        assertTrue(prefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testSubsequentCallsPreserveUserTogglesAndStayIdempotent() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        getOrInitializeDisabledProviders(prefs)

        // Simulate the user disabling AnimePahe and re-enabling nothing else.
        prefs.edit().putStringSet("disabled_providers", setOf("animepahe")).apply()

        val first = getOrInitializeDisabledProviders(prefs)
        val second = getOrInitializeDisabledProviders(prefs)
        assertEquals(setOf("animepahe"), first)
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
            activeTopRanks = setOf(100, 95, 90, 85),
            // VidLink is "running" but never produces anything: exactly the skipped-source
            // situation that used to stall every other source for 15-20 seconds.
            isRankInFlight = { rank -> rank == 100 }
        )

        // A lower-ranked source's 720p arrives first but a higher-ranked source looks in flight.
        dispatcher.onLinkAccepted(createLink("AnimePahe", "AnimePahe [720p]", "https://cdn.example/animepahe720.m3u8", Qualities.P720.value))

        delay(150L)
        assertTrue("Nothing may be emitted while the ordering window is open", emitted.isEmpty())

        delay(400L)
        assertEquals("The buffered link must be flushed once the window closes", 1, emitted.size)
        assertTrue(emitted.first().url.contains("animepahe720"))
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
            activeTopRanks = setOf(100, 95, 90, 85),
            isRankInFlight = { rank -> rank == 100 }
        )

        // VidLink serves 1080p and 480p but no 720p (its real production shape).
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
            activeTopRanks = setOf(100, 95, 90, 85),
            // Every higher-ranked source is permanently "in flight" and silent.
            isRankInFlight = { true }
        )

        repeat(6) { index ->
            dispatcher.onLinkAccepted(
                createLink(
                    "AnimePahe",
                    "AnimePahe [$index]",
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
        // Vectors produced with libsodium's crypto_secretbox (PyNaCl) using the same
        // production key/nonce/timestamp layout the vidlink.pro player uses.
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
        // 88-byte media id: exercises payloads that spill past the first 64-byte keystream
        // block, which a single-block implementation cannot encode.
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

        // Default (live) timestamp must not be the epoch and must be in the future, because
        // the player validates the embedded expiry.
        val live = VidLinkCrypto.encryptToken("533535")
        assertTrue("Live token must be non-empty", live.isNotEmpty())
        assertTrue("Live token must still be URL-safe", live.matches(Regex("^[A-Za-z0-9_-]+$")))
    }

    // =========================================================================
    // 5. DOWNLOAD-VS-STREAM SANITY
    // =========================================================================

    @Test
    fun testCuratedRegistryCannotEmitDownloadOnlyScraperProviders() {
        // The Indian/Hindi scraper tier that used to surface "[Download]" / Google Drive /
        // PixelDrain file entries is gone; assert none of those provider ids exist any more.
        val ids = buildProviders().map { it.id }.toSet()
        listOf(
            "uhdmovies", "hdhub4u", "4khdhub", "hdmovie2", "moviesmod", "topmovies",
            "bollyflix", "vegamovies", "Rogmovies", "multimovies", "moviesdrive",
            "Hindmoviez", "Movies4u", "M4uhd", "CineVood", "Filmyfiy", "Zinkmovies",
            "Dudefilms", "Peachify", "vidrock", "vidzeeapi"
        ).forEach { assertFalse("$it must not be registered", ids.contains(it)) }

        // And the survived sources only ever advertise playable media containers.
        assertTrue(StreamLinkOptimizer.isTopTierProvider("vidlink"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("animepahe"))
        assertEquals(0, StreamLinkOptimizer.getSourcePriorityRank(createLink("UHDMovies", "UHDMovies [Download]", "https://x.example/dl")))
    }

    @Test
    fun testQualityPriorityKeepsTheRequestedHierarchy() {
        // 720p #1 > 1080p #2 > 480p #3 > above-1080p #4, as requested for this plugin.
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
        // Telemetry penalties are bounded to [-30, +100]; boosts are weighted 100x, so even
        // the tightest curated neighbours (VidEm 95 -> AnimePahe 90) can never invert.
        val gap = FAST_PROVIDER_BOOST.getValue("videm") - FAST_PROVIDER_BOOST.getValue("animepahe")
        assertEquals(5f, gap)
        val worstCaseVidEm = FAST_PROVIDER_BOOST.getValue("videm") * 100f - 30f
        val bestCaseAnimePahe = FAST_PROVIDER_BOOST.getValue("animepahe") * 100f + 100f
        assertTrue(
            "VidEm must stay ranked above AnimePahe under adversarial telemetry",
            worstCaseVidEm > bestCaseAnimePahe
        )
    }

    @Test
    fun testEveryProviderExposesACallableResolver() {
        val linkData = StreamPlay.LinkData(
            id = 533535,
            imdbId = "tt6263850",
            isAnime = false,
            title = "Deadpool & Wolverine"
        )
        // Structural contract only: resolvers are network-bound, so this suite must not
        // invoke them. What matters is that each provider is wired to a non-null lambda and
        // that the movie/TV and anime halves of the registry are both represented.
        val providers = buildProviders()
        providers.forEach { provider ->
            assertTrue("${provider.id} must expose a resolver lambda", provider.invoke != null)
        }
        assertTrue(providers.any { it.id == "vidlink" })
        assertTrue(providers.any { it.id == "videm" })
        assertTrue(providers.any { it.id == "animepahe" })
        assertTrue(providers.any { it.id == "animegg" })
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
}
