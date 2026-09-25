package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random

/**
 * Adversarial Empirical Verification Suite for Milestone 1.
 * Author: Challenger 2
 *
 * Adversarial Requirements:
 * 1. High-concurrency race condition testing: simulate concurrent emission of duplicate canonical keys
 *    across 50-100 coroutines with random interleavings of 720p, 1080p, 480p, and 4K streams.
 * 2. Verify that regardless of thread interleaving, the retained stream in StreamDeduplicator is always
 *    720p (or highest priority quality present).
 * 3. Test PriorityStreamDispatcher: verify that 1080p streams are strictly held during top720GraceMs (2500ms)
 *    until 720p completes, and that 720p dispatches as link #1.
 */
class Milestone1Challenger2EmpiricalTest {

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
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type,
            headers = headers,
            extractorData = extractorData
        )
    }

    // =========================================================================
    // 1. High-concurrency race condition testing (100 coroutines, same canonical key)
    // =========================================================================

    @Test
    fun testEmpiricalHighConcurrency100CoroutinesSameKeyRandomInterleaving() = runBlocking {
        val totalCoroutines = 100
        val emittedHistory = ConcurrentLinkedQueue<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator(
            upstreamCallback = { emittedHistory.add(it) },
            onUpgradeCallback = { emittedHistory.add(it) }
        )

        val qualities = listOf(
            Qualities.P720.value,
            Qualities.P1080.value,
            Qualities.P480.value,
            Qualities.P2160.value
        )

        // Coroutine 47 will be the designated high-bitrate 720p stream
        val targetPinnacleBitrate = 9500L

        val deferreds = (0 until totalCoroutines).map { idx ->
            async(Dispatchers.Default) {
                val q = when {
                    idx == 47 -> Qualities.P720.value
                    else -> qualities[idx % qualities.size]
                }
                val bitrate = when {
                    idx == 47 -> targetPinnacleBitrate
                    q == Qualities.P720.value -> 1500L + (idx * 20L)
                    q == Qualities.P1080.value -> 5000L + (idx * 100L) // Significantly higher bitrate!
                    q == Qualities.P2160.value -> 35000L + (idx * 200L) // Massive 4K bitrate!
                    else -> 800L + (idx * 10L)
                }

                // Introduce random micro-jitter to maximize interleaving and CAS contention
                if (idx % 3 == 0) {
                    kotlinx.coroutines.yield()
                }

                val link = createLink(
                    source = "VidLink",
                    name = "VidLink Stream [${q}p] [${bitrate}kbps]",
                    url = "https://cdn.example.com/movie/stream_master.mp4?token=tok_$idx&exp=${System.currentTimeMillis() + idx}",
                    quality = q
                )
                deduplicator.emit(link)
            }
        }

        deferreds.awaitAll()

        // 1. Exactly 1 unique stream retained in deduplicator
        assertEquals("Deduplicator must hold exactly 1 canonical stream", 1, deduplicator.getEmittedCount())
        val retained = deduplicator.getEmittedLinks().first()

        // 2. Retained stream MUST be 720p despite 1080p and 4K having far higher bitrates
        assertEquals(
            "Empirical Requirement: Retained stream MUST be 720p regardless of thread interleaving",
            Qualities.P720.value,
            retained.quality
        )

        // 3. Retained stream MUST have the highest 720p bitrate
        assertTrue(
            "Retained stream must be upgraded to highest 720p bitrate ($targetPinnacleBitrate kbps)",
            retained.name.contains("${targetPinnacleBitrate}kbps")
        )
    }

    // =========================================================================
    // 2. High-concurrency multi-key contention race (100 coroutines, 10 keys)
    // =========================================================================

    @Test
    fun testEmpiricalHighConcurrency100CoroutinesMultiKeyVariableQualities() = runBlocking {
        val totalKeys = 10
        val streamsPerKey = 10
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { }

        val deferreds = (0 until (totalKeys * streamsPerKey)).map { i ->
            async(Dispatchers.Default) {
                val keyId = i % totalKeys
                val variant = i / totalKeys

                // For keyId 0..5: includes 720p, 1080p, 480p, 4K -> Expected winner: 720p
                // For keyId 6..7: includes 1080p, 480p, 4K (NO 720p) -> Expected winner: 1080p
                // For keyId 8..9: includes 480p, 4K (NO 720p, NO 1080p) -> Expected winner: 480p
                val quality = when {
                    keyId <= 5 -> {
                        when (variant) {
                            0, 1 -> Qualities.P720.value
                            2, 3, 4 -> Qualities.P1080.value
                            5, 6 -> Qualities.P480.value
                            else -> Qualities.P2160.value
                        }
                    }
                    keyId in 6..7 -> {
                        when (variant) {
                            0, 1, 2, 3 -> Qualities.P1080.value
                            4, 5, 6 -> Qualities.P480.value
                            else -> Qualities.P2160.value
                        }
                    }
                    else -> {
                        when (variant) {
                            0, 1, 2, 3, 4 -> Qualities.P480.value
                            else -> Qualities.P2160.value
                        }
                    }
                }

                val bitrate = when (quality) {
                    Qualities.P720.value -> 2000L + (variant * 50L)
                    Qualities.P1080.value -> 6000L + (variant * 100L)
                    Qualities.P2160.value -> 40000L + (variant * 500L)
                    else -> 900L + (variant * 20L)
                }

                val link = createLink(
                    source = "VidLink",
                    name = "VidLink Stream $keyId [${quality}p] [${bitrate}kbps]",
                    url = "https://node-$variant.streamhub.com/media/asset_$keyId.mp4?auth=nonce_$i&exp=${1700000000 + i}",
                    quality = quality
                )

                if (i % 2 == 0) kotlinx.coroutines.yield()
                deduplicator.emit(link)
            }
        }

        deferreds.awaitAll()

        assertEquals("Deduplicator must contain exactly $totalKeys canonical keys", totalKeys, deduplicator.getEmittedCount())

        val retainedMap = deduplicator.getEmittedLinks().associateBy {
            // Extract key id from name
            Regex("""Stream (\d+)""").find(it.name)?.groupValues?.get(1)?.toInt() ?: -1
        }

        for (k in 0 until totalKeys) {
            val link = retainedMap[k]
            assertNotNull("Link for key $k must be present", link)
            when {
                k <= 5 -> assertEquals("Key $k must retain 720p as priority #1", Qualities.P720.value, link!!.quality)
                k in 6..7 -> assertEquals("Key $k must retain 1080p when 720p absent", Qualities.P1080.value, link!!.quality)
                else -> assertEquals("Key $k must retain 480p over 4K when both 720p and 1080p absent", Qualities.P480.value, link!!.quality)
            }
        }
    }

    // =========================================================================
    // 3. Exhaustive 24 Permutations of Arrival Order for Deduplication
    // =========================================================================

    @Test
    fun testEmpiricalExhaustivePermutationsQualityRetention() {
        val q720 = createLink("VidLink", "VidLink [720p] [2000kbps]", "https://cdn.example.com/stream.mp4", Qualities.P720.value)
        val q1080 = createLink("VidLink", "VidLink [1080p] [8000kbps]", "https://cdn.example.com/stream.mp4", Qualities.P1080.value)
        val q480 = createLink("VidLink", "VidLink [480p] [1000kbps]", "https://cdn.example.com/stream.mp4", Qualities.P480.value)
        val q4k = createLink("VidLink", "VidLink [4K] [35000kbps]", "https://cdn.example.com/stream.mp4", Qualities.P2160.value)

        val allItems = listOf(q720, q1080, q480, q4k)

        fun <T> permutations(list: List<T>): List<List<T>> {
            if (list.isEmpty()) return listOf(emptyList())
            val result = mutableListOf<List<T>>()
            for (i in list.indices) {
                val item = list[i]
                val rest = list.subList(0, i) + list.subList(i + 1, list.size)
                for (sub in permutations(rest)) {
                    result.add(listOf(item) + sub)
                }
            }
            return result
        }

        val all24 = permutations(allItems)
        assertEquals("Must generate 24 permutations", 24, all24.size)

        for ((index, perm) in all24.withIndex()) {
            val deduplicator = StreamLinkOptimizer.StreamDeduplicator { }
            for (link in perm) {
                deduplicator.emit(link)
            }
            assertEquals(
                "Permutation #$index (${perm.map { it.quality }}) must end with 1 entry",
                1,
                deduplicator.getEmittedCount()
            )
            val retained = deduplicator.getEmittedLinks().first()
            assertEquals(
                "Permutation #$index (${perm.map { it.quality }}) MUST retain 720p",
                Qualities.P720.value,
                retained.quality
            )
        }
    }

    // =========================================================================
    // 4. PriorityStreamDispatcher: 1080p held during top720GraceMs (2500ms)
    // =========================================================================

    @Test
    fun testPriorityStreamDispatcher_VidFast1080pHeldDuring2500msGraceUntil720pCompletes() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = ConcurrentHashMap.newKeySet<Int>()
        inFlightRanks.addAll(listOf(100, 95, 90, 70))

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 100L,
            topSourceGraceMs = 200L,
            top720GraceMs = 2500L, // Exact 2500ms specified in prompt
            activeTopRanks = setOf(100, 95, 90, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://videasy.net/1080.m3u8", Qualities.P1080.value)
        val rivestream720 = createLink("RiveStream", "RiveStream [720p]", "https://rivestream.org/720.m3u8", Qualities.P720.value)

        // t = 0: VidEasy 1080p arrives. Higher ranks (100 VidLink, 95 Vidcore, 90 RiveStream) are in flight.
        dispatcher.onLinkAccepted(videasy1080)
        inFlightRanks.remove(70)
        dispatcher.markRankCompleted(70)

        // Verify that after 300ms (past stageWindowMs 200ms), 1080p is NOT emitted
        delay(350L)
        assertTrue("VidEasy 1080p must be strictly held during top720GraceMs (2500ms)", emitted.isEmpty())

        // t = 500ms: RiveStream 720p arrives (well within 2500ms grace window)
        delay(150L)
        dispatcher.onLinkAccepted(rivestream720)
        inFlightRanks.remove(90)
        dispatcher.markRankCompleted(90)

        // VidLink (100) and Vidcore (95) complete with no links
        inFlightRanks.clear()
        dispatcher.markRankCompleted(100)
        dispatcher.markRankCompleted(95)

        // Check emissions
        assertEquals("Both streams must be emitted", 2, emitted.size)
        assertEquals("720p MUST be dispatched as link #1", rivestream720, emitted[0])
        assertEquals("1080p MUST follow 720p as link #2", videasy1080, emitted[1])
    }

    @Test
    fun testPriorityStreamDispatcher_VidLink1080pArrivesFirst_MustHoldFor720p() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = ConcurrentHashMap.newKeySet<Int>()
        inFlightRanks.addAll(listOf(100, 95, 90))

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 200L,
            subtitleGraceMs = 100L,
            topSourceGraceMs = 200L,
            top720GraceMs = 2500L,
            activeTopRanks = setOf(100, 95, 90),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val vidlink1080 = createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value)
        val rivestream720 = createLink("RiveStream", "RiveStream [720p]", "https://rivestream.org/720.m3u8", Qualities.P720.value)

        // t = 0: VidLink emits 1080p, but VidLink 720p or RiveStream 720p is still resolving/in-flight
        dispatcher.onLinkAccepted(vidlink1080)

        // Check after stageWindowMs (200ms)
        delay(350L)

        // t = 500ms: RiveStream 720p arrives
        dispatcher.onLinkAccepted(rivestream720)
        inFlightRanks.clear()
        dispatcher.markRankCompleted(100)
        dispatcher.markRankCompleted(95)
        dispatcher.markRankCompleted(90)

        delay(100L)
        dispatcher.flush()

        assertTrue("Emitted list must contain at least 2 streams", emitted.size >= 2)
        assertEquals("720p MUST be dispatched as link #1", Qualities.P720.value, emitted[0].quality)
        assertEquals("1080p MUST be link #2", Qualities.P1080.value, emitted[1].quality)
    }

    @Test
    fun testPriorityStreamDispatcher_1080pHeldForFullGraceTimeoutWhenNo720p() = runBlocking {
        val emitted = mutableListOf<ExtractorLink>()
        val inFlightRanks = ConcurrentHashMap.newKeySet<Int>()
        inFlightRanks.addAll(listOf(88, 70))

        val dispatcher = StreamLinkOptimizer.PriorityStreamDispatcher(
            upstreamCallback = { emitted.add(it) },
            scope = this,
            stageWindowMs = 100L,
            subtitleGraceMs = 50L,
            topSourceGraceMs = 50L,
            top720GraceMs = 500L, // Scaled down for fast unit test execution (500ms grace)
            activeTopRanks = setOf(88, 70),
            isRankInFlight = { inFlightRanks.contains(it) }
        )

        dispatcher.onSubtitleReceived()

        val videasy1080 = createLink("VidEasy", "VidEasy [1080p]", "https://videasy.net/1080.m3u8", Qualities.P1080.value)

        dispatcher.onLinkAccepted(videasy1080)
        inFlightRanks.remove(70)
        dispatcher.markRankCompleted(70)

        // At t = 200ms: within 500ms grace window, 1080p must NOT be emitted
        delay(200L)
        assertTrue("1080p must be held while grace timer is active and higher rank in flight", emitted.isEmpty())

        // At t = 650ms: after grace timer expires (500ms + margin), 1080p should be released as fallback
        delay(450L)
        assertEquals("1080p must be released after grace timer expires", 1, emitted.size)
        assertEquals(videasy1080, emitted[0])
    }
}
