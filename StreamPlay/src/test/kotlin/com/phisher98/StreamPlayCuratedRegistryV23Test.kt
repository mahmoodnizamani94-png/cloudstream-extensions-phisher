package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.vidrockUnwrap
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
 * Acceptance suite for the curated v23 SOTA StreamPlay source registry.
 *
 * It pins down, as executable contracts:
 *  1. exactly which sources may surface in the UI (VidLink, VidSrc, VidFast, VidSrcSU,
 *     VidRock, MeowTV, Animex) and their strict launch hierarchy,
 *  2. the ranking of those sources and of the CDN hosts they serve from,
 *  3. the settings migration that removes every decommissioned provider from an existing
 *     install while re-admitting the re-issued `vidfast` identifier,
 *  4. the bounded ordering windows of [StreamLinkOptimizer.PriorityStreamDispatcher],
 *  5. known-answer vectors for the offline VidLink token encryptor,
 *  6. known-answer vectors for the VidRock AES-256-GCM lane unwrap, and
 *  7. the exception gates that keep the Movie/TV and Anime halves of the registry apart.
 */
class StreamPlayCuratedRegistryV23Test {

    private val curatedIds = setOf("vidlink", "vidsrc", "vidfast", "vidsrcsu", "vidrock", "meowtv", "animex")

    /** Authoritative launch hierarchy: strictly descending boost per provider id. */
    private val boostByProvider = linkedMapOf(
        "vidlink" to 100f,
        "vidsrc" to 95f,
        "vidfast" to 94f,
        "vidsrcsu" to 93f,
        "vidrock" to 92f,
        "meowtv" to 91f,
        "animex" to 90f
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
        assertEquals("VidSrc", byId.getValue("vidsrc").name)
        assertEquals("VidFast", byId.getValue("vidfast").name)
        assertEquals("VidSrcSU", byId.getValue("vidsrcsu").name)
        assertEquals("VidRock", byId.getValue("vidrock").name)
        assertEquals("MeowTV", byId.getValue("meowtv").name)
        assertEquals("Animex", byId.getValue("animex").name)
        providers.forEach { assertEquals(ProviderKind.VIDEO, it.kind) }
    }

    @Test
    fun testEveryDecommissionedProviderIsAbsentFromEverySurface() {
        val providerIds = buildProviders().map { it.id }.toSet()

        // Every identifier that has ever shipped but is now decommissioned must be gone
        // from the registry, the boost table, the cold-start tier table and the default
        // top tier. The single deliberate exception is the capitalised legacy spelling
        // `Animex`: the live provider ships as lowercase `animex`, and the capitalised
        // alias is intentionally retained in the boost/tier tables so any stale capitalised
        // label still resolves to rank 90 instead of silently falling back to 0.
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

        // The most consequential removals are asserted explicitly so a regression cannot
        // slip through a REMOVED_PROVIDER_IDS bookkeeping mistake. `vidfast` is no longer
        // in this list (the identifier was re-issued to the v23 VidFast extractor), while
        // `animepahe` was added after its fronts were hijacked.
        listOf(
            "rivestream", "videasy", "superstream", "vaplayer", "vidcore", "vidnest",
            "vidup", "hexasu", "autoembed", "videm", "animegg", "animepahe", "AnimePahe"
        ).forEach { assertFalse("$it must not be registered", providerIds.contains(it)) }

        // The v23 additions are live members of the curated set, so they must be registered
        // and must never be filtered out by REMOVED_PROVIDER_IDS bookkeeping.
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
        assertEquals(100, rankOf("VidLink", "VidLink [1080p]", "https://cdn.example/a.mp4"))
        assertEquals(95, rankOf("VidSrc", "VidSrc HLS", "https://lit.cheaptruckrepairs.cc/b.m3u8"))
        assertEquals(94, rankOf("VidFast", "VidFast [1080p]", "https://cdn.example/c.m3u8"))
        assertEquals(93, rankOf("VidSrcSU", "VidSrcSU [1080p]", "https://cdn.example/su.m3u8"))
        assertEquals(92, rankOf("VidRock", "VidRock SUB [720p]", "https://galacticvoyagers.xyz/e.m3u8"))
        assertEquals(91, rankOf("MeowTV", "MeowTV [1080p]", "https://cdn.example/m.m3u8"))
        assertEquals(90, rankOf("Animex", "Animex [Sub] [HD-1]", "https://cdn.example/x.m3u8"))

        // VidSrcSU must be matched before the generic `vidsrc` substring, otherwise its own
        // label would inherit VidSrc's 95 instead of its 93.
        assertEquals(93, rankOf("VidSrcSU", "VidSrcSU HLS", "https://cdn.example/su.m3u8"))

        // Every provider also ranks on the CDN hosts it actually serves from, so a link
        // keeps its tier when the deduplicator rewraps it away from its label.
        assertEquals(95, rankOf("VidSrc", "VidSrc HLS", "https://netoda.tech/f.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://vidsrc.pm/vidora/stream.m3u8"))
        // vidsrc.buzz was the VidEm mirror domain and stays decommissioned.
        assertEquals(0, rankOf("Unknown", "stream", "https://vidsrc.buzz/_stream?id=abc"))
        assertEquals(94, rankOf("Unknown", "stream", "https://vidfast.vc/e/abc.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(93, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://galacticvoyagers.xyz/x.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://moonfirekeeper.lol/file2/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://img1.hscow.com/thumb.jpg"))
        assertEquals(91, rankOf("Unknown", "stream", "https://cdn.vidjoy.org/hls/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://i-arch-400.krico443jez.com/x.m3u8"))
        assertEquals(90, rankOf("Unknown", "stream", "https://animex.one/embed/x"))
        assertEquals(90, rankOf("Unknown", "stream", "https://aniwatchtv.site/e/abc.m3u8"))

        // Ranking must survive CDN rewrites that lose the original source label.
        assertEquals(100, rankOf("Unknown", "stream", "https://bcdnxw.hakunaymatata.com/bt/a.mp4"))
        assertEquals(0, rankOf("Unknown", "stream", "https://sc-u18-01.vix-content.net/hls/master.m3u8"))

        // The AnimePahe/Kwik mapping was dropped together with the decommissioned source:
        // a rewrapped kwik.cx URL must no longer resurrect a rank.
        assertEquals(0, rankOf("Unknown", "stream", "https://kwik.cx/e/abc.m3u8"))
        assertEquals(0, rankOf("AnimePahe", "AnimePahe [1080p]", "https://kwik.cx/e/abc.m3u8"))

        // Decommissioned sources must score zero rather than inheriting a legacy rank.
        listOf(
            "RiveStream", "VidEasy", "Vidflix", "VixSrc", "Vidup", "CineJoy", "YFlix",
            "HexaSU", "MovieBox", "VidEm", "AnimePahe"
        ).forEach { label ->
            assertEquals(
                "$label must not inherit a rank after decommissioning",
                0,
                rankOf(label, "$label [1080p]", "https://x.example/a.m3u8")
            )
        }
        // Their legacy CDN hosts must not resurrect a rank either (the VidSrc/VidRock/VidFast/
        // MeowTV CDNs, by contrast, DO carry rank: see the host-level assertions above).
        assertEquals(0, rankOf("Unknown", "stream", "https://videm.xyz/video/x.m3u8"))
        assertEquals(0, rankOf("Unknown", "stream", "https://s247.vidcache.net:8166/play/a/video.mp4"))

        curatedIds.forEach { id ->
            assertTrue("$id must be recognised as a top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    // =========================================================================
    // 2. SETTINGS MIGRATION (v23)
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
        // stayed enabled forever and animepahe stayed disabled but referenced a dead host.
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("vidlink", "uhdmovies", "HexaSU", "animepahe", "AnimePahe")
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
        // explicitly wanted gone. `vidfast` appears because it WAS decommissioned in v19 and
        // later re-issued: a stale opt-out must not survive into the v23 registry, and the
        // genuinely dead ids must not reappear in any shape.
        prefs.edit().putStringSet(
            "disabled_providers",
            setOf("rivestream", "vidfast", "videasy", "yflix", "cinejoy", "hexasu", "vixsrc", "animepahe", "AnimePahe", "Animex")
        ).apply()

        val disabled = getOrInitializeDisabledProviders(prefs)
        assertTrue(disabled.isEmpty())
        assertTrue(prefs.getBoolean(PREFS_TOP_TIER_INITIALIZED, false))
    }

    @Test
    fun testSubsequentCallsPreserveUserTogglesAndStayIdempotent() {
        val prefs = ProviderTelemetryAndCircuitBreakerTest.MockSharedPreferences()
        getOrInitializeDisabledProviders(prefs)

        // Simulate the user disabling MeowTV and re-enabling nothing else.
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
            activeTopRanks = setOf(100, 95, 94, 93, 92, 91, 90),
            // VidLink is "running" but never produces anything: exactly the skipped-source
            // situation that used to stall every other source for 15-20 seconds.
            isRankInFlight = { rank -> rank == 100 }
        )

        // A lower-ranked source's 720p arrives first but a higher-ranked source looks in flight.
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
            activeTopRanks = setOf(100, 95, 94, 93, 92, 91, 90),
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
            activeTopRanks = setOf(100, 95, 94, 93, 92, 91, 90),
            // Every higher-ranked source is permanently "in flight" and silent.
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
            "Dudefilms", "Peachify", "vidzeeapi"
        ).forEach { assertFalse("$it must not be registered", ids.contains(it)) }

        // And the survived sources only ever advertise playable media containers.
        assertTrue(StreamLinkOptimizer.isTopTierProvider("vidlink"))
        assertTrue(StreamLinkOptimizer.isTopTierProvider("animex"))
        assertFalse("AnimePahe was decommissioned and must not report as top tier", StreamLinkOptimizer.isTopTierProvider("animepahe"))
        assertEquals(0, rankOf("UHDMovies", "UHDMovies [Download]", "https://x.example/dl"))
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
        // Telemetry perturbation is bounded to [-30, +100] and only ever enters the
        // launch-priority score (boost * 100 + telemetry). The worst possible swing is 130
        // points, while VidLink sits 500 points above its closest runner-up (VidSrc 95), so
        // the #1 source can never be displaced during scraper launch:
        val worstCaseVidLink = FAST_PROVIDER_BOOST.getValue("vidlink") * 100f - 30f
        val bestCaseRunnerUp = DEFAULT_TOP_TIER_PROVIDERS
            .filter { it != "vidlink" }
            .maxOf { FAST_PROVIDER_BOOST.getValue(it) } * 100f + 100f
        assertTrue(
            "VidLink must hold launch priority #1 under adversarial telemetry",
            worstCaseVidLink > bestCaseRunnerUp
        )

        // Playback ordering (getStreamCompositeScore) is telemetry-free: it is
        // rank * 10,000 + a quality term bounded below 10,000, so every top-tier stream
        // (rank >= 90 -> >= 900,000) strictly out-scores every secondary stream (capped
        // below 810) no matter what telemetry recorded. The adversarial secondary stream
        // below carries every micro-tiebreaker bit the scorer understands.
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
    fun testPlaybackOrderingIsStrictlyRankDominatedAcrossTheV23Registry() {
        // In playback ordering, source rank dominates quality: the SD variant of a
        // higher-ranked source must always out-score the HD variant of the next source down.
        // This is what keeps the launch hierarchy and the playback hierarchy locked together.
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
        // Structural contract only: resolvers are network-bound, so this suite must not
        // invoke them. What matters is that every curated provider is wired to a non-null
        // lambda and that the movie/TV and anime halves of the registry are both represented.
        val providers = buildProviders()
        curatedIds.forEach { id ->
            val provider = providers.firstOrNull { it.id == id }
            assertNotNull("$id must be registered", provider)
            assertTrue("$id must expose a resolver lambda", provider!!.invoke != null)
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
    fun testVidLinkIsUniversalAcrossMovieTvAndAnime() {
        val providers = buildProviders()
        val vidlinkProvider = providers.first { it.id == "vidlink" }
        assertNotNull("VidLink provider must exist in registry", vidlinkProvider)

        // Verify VidLink is not in NON_ANIME_PROVIDERS
        assertFalse(
            "VidLink must be universally applicable and not gated out for anime",
            NON_ANIME_PROVIDERS.contains("vidlink")
        )

        // Verify VidLink boost is 100f (highest tier in the engine)
        assertEquals(100f, FAST_PROVIDER_BOOST.getValue("vidlink"))
    }

    @Test
    fun testVidLinkOutranksEveryOtherSourceInCompositeScore() {
        // Even the lowest SD variant of VidLink (e.g. 360p) must outrank any 1080p from a
        // secondary or lower source.
        val vidlink360 = createLink("Vidlink", "Vidlink 360p", "https://bcdn.hakunaymatata.com/bt/360.mp4", Qualities.P360.value)
        val vidsrc1080 = createLink("VidSrc", "VidSrc 1080p", "https://vidsrc.pm/stream/1080.mp4", Qualities.P1080.value)
        val animex1080 = createLink("Animex", "Animex 1080p", "https://animex.one/e/1080.m3u8", Qualities.P1080.value)

        val scoreVidlink360 = StreamLinkOptimizer.getStreamCompositeScore(vidlink360)
        val scoreVidSrc1080 = StreamLinkOptimizer.getStreamCompositeScore(vidsrc1080)
        val scoreAnimex1080 = StreamLinkOptimizer.getStreamCompositeScore(animex1080)

        assertTrue("VidLink composite score must dominate VidSrc", scoreVidlink360 > scoreVidSrc1080)
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

        // Strict SOTA ladder: 720p #1 > 1080p #2 > 480p #3 > 360p #4
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
    // 6. VIDROCK AES-256-GCM UNWRAP (KNOWN-ANSWER VECTORS)
    // =========================================================================

    /** KAT vectors stored as hex of raw token bytes; wire format is their base64url encoding. */
    private fun tokenFromHex(hex: String): String =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[2 * i], 16) shl 4) + Character.digit(hex[2 * i + 1], 16)).toByte()
        }.toB64Url()

    private fun ByteArray.toB64Url(): String {
        val b64 = String(java.util.Base64.getEncoder().encode(this))
        return b64.replace('+', '-').replace('/', '_').trimEnd('=')
    }

    @Test
    fun testVidrockUnwrapMatchesProductionKnownAnswerVectors() {
        // Vectors generated with Node's crypto (AES-256-GCM, key = VIDROCK_KEY_HEX,
        // wire format = base64url(nonce[12] || ciphertext || tag[16])) exactly as the
        // vidrock.net player decodes its API lanes.
        assertEquals(
            "ciphertext + tag must unwrap to the direct manifest URL",
            "https://galacticvoyagers.xyz/file3/YTk2ZjNkZGY4YzUwZmUwZDc5NGExMjE0YTNkOWVmNTExNzkyMDgxNDg5NDEzNTM3/master.m3u8",
            vidrockUnwrap(
                tokenFromHex(
                    "b47427e5ee92c8a1adb71dc2830f4a0d738cd55a85bfc8a3b95408af356044501ece7bbfee5166d3f2afa8959a95e10e65fd6b4b5a8971a2bdef7dd5372b71734065719494ffade70704cfc4d28c91b1d1e5f202227f1ce9a2f403b51b2bd4c2cb4f02a6904367528e0380432836510a940c899655d717c130a432c600da7b7bebb7d7ce6fede69b1dad8c"
                )
            )
        )
        assertEquals(
            "the mirrored CDN host must unwrap identically",
            "https://moonfirekeeper.lol/file2/NTdlZGRjZjZmNGRkNjZhOTkyOTgwNDg1ZTVmNzVhNzIxNzg5NjQyMjQ1OTUyNDM5/master.m3u8",
            vidrockUnwrap(
                tokenFromHex(
                    "a01bee0d7fbe316973e25fd2ef627fbb25a4ee0c897eacd3b1a4c82e21943b4fc757d619643a5ad90ad46fbd0647ef8c85855a10b6ccc9f283c2328418a30de601afeff3ac384994fc21eb9ab98e556be3d3059902b76e9118f53b2e1427caaa0904cc6327180e42f2881db40f4a1b7784ec8b18f97c0ce7259ab66a6b77bab545b5af0eb08a523156"
                )
            )
        )
        assertEquals(
            "a progressive mp4 lane must unwrap identically",
            "https://galacticvoyagers.xyz/mp4/AbCdEf/sample.mp4",
            vidrockUnwrap(
                tokenFromHex(
                    "79a5e67f9651c54e7ef1c1cf5893901b656960c5be477c37ca59b97fc542c3b0ace163be32604e74d8483de987a71c89dda8b6daa13988c78402d10e55f2a2894dac727571220aaa434497f97dc4"
                )
            )
        )
    }

    @Test
    fun testVidrockUnwrapDecodesTheJsonVariantLadder() {
        val unwrapped = vidrockUnwrap(
            tokenFromHex(
                "234cfb1d857d36b76f95e862881455ff3053fc0561e262a39814d2575ddff45def777b2d1613ed0dfd7bffa123d7cec5ccf15ea174b87704dc055c887e5fcc3acbc9444c3e4dea797723389e52e02d27b7f6df97922bb471e9c1f839febad51c8237d970927701f8e30c968756d2ff6ad8564aade3e6c1e7090125d62c71c5cfcf4f7b3e34b2b1c0077e76b958e8d5a13e7aeadb72327dceb3c6d56fd43b6918046158ee68893db9444104c6bb90ae701e48f5ca58a3c1188a0b3f2014d547dd2818c8b4ee5a6215e5c1996833e49ee045bb961ea283f887c5303066c69bdf31ef2ff4bfe942408d20e8797dccf0ac091c500310d640e59d374558acebba491178b75c21082b21600877e52c90741e3935dabd4be02d58d2ec1e973e43d3c0741c59b8d33d057c7394d16256fb879b2a49a9d83618f9"
            )
        )!!
        // The plaintext is a JSON array of {name,url} ladder entries; the token decode is
        // what the lane parser then feeds into JSONArray. Validate both steps.
        assertTrue("Plaintext must be a JSON array", unwrapped.trimStart().startsWith("["))
        val entryCount = Regex("\"url\"").findAll(unwrapped).count()
        assertEquals("Ladder must contain the three advertised variants", 3, entryCount)
    }

    @Test
    fun testVidrockUnwrapRejectsTamperedAndShortTokens() {
        val valid = vidrockUnwrap(
            tokenFromHex(
                "b47427e5ee92c8a1adb71dc2830f4a0d738cd55a85bfc8a3b95408af356044501ece7bbfee5166d3f2afa8959a95e10e65fd6b4b5a8971a2bdef7dd5372b71734065719494ffade70704cfc4d28c91b1d1e5f202227f1ce9a2f403b51b2bd4c2cb4f02a6904367528e0380432836510a940c899655d717c130a432c600da7b7bebb7d7ce6fede69b1dad8c"
            )
        )
        assertTrue(valid?.startsWith("https://galacticvoyagers.xyz") == true)

        // AEAD integrity rejects tampering; non-token inputs must degrade to null, never throw.
        assertNull("A blank token must unwrap to null", vidrockUnwrap(""))
        assertNull("An unparseable token must unwrap to null", vidrockUnwrap("!!!!not-base64url!!!!"))
        assertNull("A truncated token must unwrap to null", vidrockUnwrap("AAAA"))
    }

    // =========================================================================
    // 7. EXCEPTION GATES: ANIME VS MOVIE/TV
    // =========================================================================

    @Test
    fun testVidrockAndVidSrcAreGatedToNonAnimeAndVidLinkIsUniversal() {
        // Every Movie/TV source must never race an anime title.
        listOf("vidsrc", "vidfast", "vidsrcsu", "vidrock", "meowtv").forEach { id ->
            assertTrue("$id must be a NON_ANIME provider", NON_ANIME_PROVIDERS.contains(id))
        }
        assertFalse("Animex is anime-only", NON_ANIME_PROVIDERS.contains("animex"))

        // VidLink is the universal #1: it must never be gated in either direction.
        assertFalse("VidLink must be universally applicable and not gated out for anime", NON_ANIME_PROVIDERS.contains("vidlink"))
        assertTrue(
            "VidLink must be the highest-ranked top tier provider at all times",
            StreamLinkOptimizer.isTopTierProvider("vidlink")
        )
        assertEquals(100, FAST_PROVIDER_BOOST.getValue("vidlink").toInt())

        // Every curated id must be recognised as top-tier by the provider gate too.
        curatedIds.forEach { id ->
            assertTrue("$id must be recognised as a top-tier provider", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    @Test
    fun testCuratedRanksMeetTheTopTierBoostThreshold() {
        // Every registered providerId boost must clear the dispatcher's top-tier threshold so
        // the loadLinks machinery treats all seven sources as primary. The lowest bar is
        // Animex (90) so an accidental demotion fails here, not silently at runtime.
        val lowest = DEFAULT_TOP_TIER_PROVIDERS.minOf { FAST_PROVIDER_BOOST.getValue(it) }
        assertTrue(
            "Lowest curated boost $lowest must clear the top-tier threshold",
            lowest >= TOP_TIER_BOOST_THRESHOLD
        )
    }

    @Test
    fun testEveryProviderCdnHostCarriesRankForRewrappedLinks() {
        // The deduplicator can rewrite a lane link to its CDN endpoint (losing the provider
        // label), so the ranker must recover the tier from every provider's CDN host.
        assertEquals(100, rankOf("Unknown", "stream", "https://bcdn.hakunaymatata.com/bt/master.m3u8"))
        assertEquals(95, rankOf("Unknown", "stream", "https://lit.cheaptruckrepairs.cc/h/master.m3u8"))
        assertEquals(94, rankOf("Unknown", "stream", "https://moon.zenoak.top/hls/master.m3u8"))
        assertEquals(93, rankOf("Unknown", "stream", "https://dragonballzfans.xyz/stream/master.m3u8"))
        assertEquals(92, rankOf("Unknown", "stream", "https://moonfirekeeper.lol/file2/master.m3u8"))
        assertEquals(91, rankOf("Unknown", "stream", "https://img1.hscow.com/i/master.m3u8"))
        assertEquals(90, rankOf("Unknown", "stream", "https://aniwatchtv.site/e/master.m3u8"))
    }

    @Test
    fun testDnsBootstrapResolversCoverFourIndependentOperators() {
        // The DoH race must span four operators so a poisoned/blackholed resolver cannot
        // produce "no links found" on hostile networks (Quad9 and AdGuard were added to
        // Cloudflare and Google in the v22 pass - both verified over RFC8484 POST).
        assertEquals(4, OptimizedDns.CLOUDFLARE_BOOTSTRAP_IPS.size)
        assertTrue(OptimizedDns.CLOUDFLARE_BOOTSTRAP_IPS.contains("1.1.1.1"))
        assertTrue(OptimizedDns.GOOGLE_BOOTSTRAP_IPS.contains("8.8.8.8"))
        assertTrue("Quad9 bootstrap must be present for the resolver race", OptimizedDns.QUAD9_BOOTSTRAP_IPS.contains("9.9.9.10"))
        assertTrue(OptimizedDns.QUAD9_BOOTSTRAP_IPS.contains("149.112.112.10"))
        assertTrue("AdGuard bootstrap must be present for the resolver race", OptimizedDns.ADGUARD_BOOTSTRAP_IPS.contains("94.140.14.140"))
        assertTrue(OptimizedDns.ADGUARD_BOOTSTRAP_IPS.contains("94.140.14.141"))

        // Every one of the v23 streaming-tier domains must be warmed predictively: each of
        // the seven providers' API hosts plus the live verified CDNs they serve from.
        OptimizedDns.TOP_PREDICTIVE_DOMAINS.let { domains ->
            listOf(
                "vidlink.pro", "bcdn.hakunaymatata.com",
                "vidsrc.pm", "lit.cheaptruckrepairs.cc",
                "vidfast.vc", "moon.zenoak.top",
                "vidsrc.su", "themoviedb.vidsrc.su", "dragonballzfans.xyz",
                "vidrock.net", "galacticvoyagers.xyz", "moonfirekeeper.lol",
                "meowtv.ru", "api.meowtv.ru", "img1.hscow.com", "img1.fcxmb.com",
                "cdn.vidjoy.org", "cdn2.vidjoy.org", "i-arch-400.krico443jez.com",
                "graphql.animex.one", "pp.animex.one",
                "kwik.cx"
            ).forEach { domain ->
                assertTrue("$domain must be in TOP_PREDICTIVE_DOMAINS", domains.contains(domain))
            }
            assertTrue(
                "Decommissioned AnimePahe domains must not be warmed",
                domains.none { it.contains("animepahe") }
            )
        }
    }
}
