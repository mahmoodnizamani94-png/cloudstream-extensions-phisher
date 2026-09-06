package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Verification Test Suite for Milestone 2 (R3):
 * Multi-CDN Deduplication & Direct Endpoint Rewriting Challenger.
 *
 * Scenarios tested:
 * 1. Rotating edge subdomains (cdn1, edge-us-2, s12.streamtape.com, srv-store5.gofile.io, etc.)
 *    collapsing to canonical mirror keys.
 * 2. Complete stripping of all 32+ transient query tokens (signatures, timestamps, session nonces, IP bindings).
 * 3. Content differentiating query parameters (format, quality, id, file, sub) preserved and sorted.
 * 4. Magnet InfoHash normalization regardless of tracker list ordering, casing, or extra URI parameters.
 * 5. PixelDrain endpoint rewriting (/u/{id} -> /api/file/{id}?download) and Referer/Origin purging.
 * 6. Gofile header injection (Referer: https://gofile.io/, Origin: https://gofile.io).
 * 7. StreamTape progressive stream injection (&stream=1) and Referer/Origin headers.
 * 8. Lock-free atomic CAS deduplication under high concurrent thread contention.
 */
class StreamDeduplicationAndCdnAdversarialTest {

    private fun createLink(
        source: String = "TestSrc",
        name: String = "TestLink",
        url: String,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "",
        quality: Int = Qualities.Unknown.value,
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
    // 1. Rotating Edge Subdomains & Host Normalization
    // =========================================================================

    @Test
    fun testRotatingEdgeSubdomainsCollapseToCanonicalMirrorKey() {
        // Rotating edge nodes on standard domain
        val cdn1 = createLink(url = "https://cdn1.example.com/video/master.m3u8")
        val edgeUs2 = createLink(url = "https://edge-us-2.example.com/video/master.m3u8")
        val nodeEu1 = createLink(url = "https://node-eu-1.example.com/video/master.m3u8")
        val server99 = createLink(url = "https://server99.example.com/video/master.m3u8")

        val key1 = StreamLinkOptimizer.canonicalStreamKey(cdn1)
        val key2 = StreamLinkOptimizer.canonicalStreamKey(edgeUs2)
        val key3 = StreamLinkOptimizer.canonicalStreamKey(nodeEu1)
        val key4 = StreamLinkOptimizer.canonicalStreamKey(server99)

        assertEquals("cdn-cluster.example.com/video/master.m3u8", key1)
        assertEquals("cdn1 and edge-us-2 must collapse to same key", key1, key2)
        assertEquals("edge-us-2 and node-eu-1 must collapse to same key", key2, key3)
        assertEquals("node-eu-1 and server99 must collapse to same key", key3, key4)
    }

    @Test
    fun testStreamTapeEdgeSubdomainsCollapse() {
        val s12 = createLink(url = "https://s12.streamtape.com/get_video?id=TapeFile123&token=nonceA&expires=1700000000")
        val s15 = createLink(url = "https://s15.streamtape.com/get_video?id=TapeFile123&token=nonceB&expires=1700000500")
        val s99 = createLink(url = "https://s99.streamtape.com/get_video?id=TapeFile123&token=nonceC&expires=1700000999")

        val opt12 = StreamLinkOptimizer.optimize(s12)
        val opt15 = StreamLinkOptimizer.optimize(s15)
        val opt99 = StreamLinkOptimizer.optimize(s99)

        val key12 = StreamLinkOptimizer.canonicalStreamKey(opt12)
        val key15 = StreamLinkOptimizer.canonicalStreamKey(opt15)
        val key99 = StreamLinkOptimizer.canonicalStreamKey(opt99)

        assertEquals("cdn-cluster.streamtape.com/get_video?id=TapeFile123&stream=1", key12)
        assertEquals("s12 and s15 must collapse", key12, key15)
        assertEquals("s15 and s99 must collapse", key15, key99)
    }

    @Test
    fun testGofileStorageEdgeSubdomainsCollapse() {
        val store5 = createLink(url = "https://srv-store5.gofile.io/download/direct/token123/file.mp4?ip=1.1.1.1")
        val store6 = createLink(url = "https://srv-store6.gofile.io/download/direct/token123/file.mp4?ip=2.2.2.2")
        val store1 = createLink(url = "https://srv-store1.gofile.io/download/direct/token123/file.mp4?ip=3.3.3.3")

        val key5 = StreamLinkOptimizer.canonicalStreamKey(store5)
        val key6 = StreamLinkOptimizer.canonicalStreamKey(store6)
        val key1 = StreamLinkOptimizer.canonicalStreamKey(store1)

        assertEquals("cdn-cluster.gofile.io/download/direct/token123/file.mp4", key5)
        assertEquals("srv-store5 and srv-store6 must collapse", key5, key6)
        assertEquals("srv-store6 and srv-store1 must collapse", key6, key1)
    }

    @Test
    fun testTwoPartTldSubdomainsCollapse() {
        val uk1 = createLink(url = "https://cdn1.fastmedia.co.uk/movie.mp4")
        val uk2 = createLink(url = "https://edge2.fastmedia.co.uk/movie.mp4")

        val keyUk1 = StreamLinkOptimizer.canonicalStreamKey(uk1)
        val keyUk2 = StreamLinkOptimizer.canonicalStreamKey(uk2)

        assertEquals("cdn-cluster.fastmedia.co.uk/movie.mp4", keyUk1)
        assertEquals(keyUk1, keyUk2)
    }

    @Test
    fun testMalformedUriFallbackInCanonicalStreamKey() {
        // URL with illegal unencoded characters that would fail URI constructor
        val malformedUrl = "https://cdn1.example.com/path with spaces/file[1].mp4?id=100&token=abc"
        val link = createLink(url = malformedUrl)
        val key = StreamLinkOptimizer.canonicalStreamKey(link)

        assertTrue("Fallback must normalize host cluster", key.startsWith("cdn-cluster.example.com/"))
        assertTrue("Fallback must preserve id parameter", key.contains("id=100"))
        assertFalse("Fallback must strip token parameter", key.contains("token=abc"))
    }

    // =========================================================================
    // 2. All 32+ (All 51) Transient Query Parameter Stripping
    // =========================================================================

    @Test
    fun testAllTransientQueryTokensStrippedIndividually() {
        val all51TransientTokens = listOf(
            // Timestamps & Expirations (12)
            "t", "_", "ts", "timestamp", "exp", "expire", "expires", "expiry", "deadline", "valid", "validity", "time",
            // Signatures, Hashes & Nonces (17)
            "sig", "signature", "sign", "h", "hash", "md5", "key", "auth", "auth_key", "verify", "verification", "hmac", "token", "st", "nonce", "csrf", "xsrf",
            // Session & Request Tracking (9)
            "session", "session_id", "sid", "sessionid", "req_id", "request_id", "client_id", "uuid", "state",
            // IP & Geo-Locking (7)
            "ip", "ip_token", "user_ip", "client_ip", "geo", "country", "asn",
            // Cache Busters (6)
            "cb", "rand", "rnd", "random", "nocache", "cache_buster"
        )

        // Baseline link with content param only
        val baselineLink = createLink(url = "https://cdn.example.com/video.mp4?id=video123")
        val baselineKey = StreamLinkOptimizer.canonicalStreamKey(baselineLink)
        assertEquals("cdn-cluster.example.com/video.mp4?id=video123", baselineKey)

        // Verify each individual transient parameter is stripped and produces the baseline key
        for (token in all51TransientTokens) {
            val testLink = createLink(url = "https://cdn.example.com/video.mp4?id=video123&$token=transientValue999")
            val testKey = StreamLinkOptimizer.canonicalStreamKey(testLink)
            assertEquals("Parameter '$token' must be stripped", baselineKey, testKey)
        }
    }

    @Test
    fun testAll51TransientTokensSimultaneouslyStripped() {
        val all51Tokens = listOf(
            "t=1", "_=2", "ts=3", "timestamp=4", "exp=5", "expire=6", "expires=7", "expiry=8", "deadline=9", "valid=10", "validity=11", "time=12",
            "sig=13", "signature=14", "sign=15", "h=16", "hash=17", "md5=18", "key=19", "auth=20", "auth_key=21", "verify=22", "verification=23", "hmac=24", "token=25", "st=26", "nonce=27", "csrf=28", "xsrf=29",
            "session=30", "session_id=31", "sid=32", "sessionid=33", "req_id=34", "request_id=35", "client_id=36", "uuid=37", "state=38",
            "ip=39", "ip_token=40", "user_ip=41", "client_ip=42", "geo=43", "country=44", "asn=45",
            "cb=46", "rand=47", "rnd=48", "random=49", "nocache=50", "cache_buster=51",
            "utm_source=google", "utm_medium=cpc", "utm_campaign=winter"
        )

        val fullQuery = (all51Tokens + listOf("format=mp4", "quality=1080p", "id=42")).joinToString("&")
        val link = createLink(url = "https://cdn1.example.com/stream.mp4?$fullQuery")
        val key = StreamLinkOptimizer.canonicalStreamKey(link)

        // All 51 tokens + 3 utm params must be stripped; only format, id, quality remain, sorted alphabetically
        assertEquals("cdn-cluster.example.com/stream.mp4?format=mp4&id=42&quality=1080p", key)
    }

    // =========================================================================
    // 3. Content Differentiating Query Parameter Preservation
    // =========================================================================

    @Test
    fun testContentDifferentiatingQueryParamsPreservedAndSorted() {
        // Parameters provided in reverse alphabetical order with transient tokens interleaved
        val linkA = createLink(url = "https://cdn.example.com/v.mp4?sub=en&quality=720p&id=99&format=m3u8&file=ep1.mp4&token=abc")
        val linkB = createLink(url = "https://cdn.example.com/v.mp4?format=m3u8&file=ep1.mp4&id=99&sub=en&quality=720p&expires=999")

        val keyA = StreamLinkOptimizer.canonicalStreamKey(linkA)
        val keyB = StreamLinkOptimizer.canonicalStreamKey(linkB)

        // Must preserve all content parameters sorted
        val expected = "cdn-cluster.example.com/v.mp4?file=ep1.mp4&format=m3u8&id=99&quality=720p&sub=en"
        assertEquals(expected, keyA)
        assertEquals("Different query parameter orderings must produce identical sorted canonical key", keyA, keyB)
    }

    @Test
    fun testDistinctContentParamsProduceDistinctKeys() {
        val link1080 = createLink(url = "https://cdn.example.com/v.mp4?quality=1080p&id=1")
        val link720 = createLink(url = "https://cdn.example.com/v.mp4?quality=720p&id=1")
        val linkId2 = createLink(url = "https://cdn.example.com/v.mp4?quality=1080p&id=2")

        val key1080 = StreamLinkOptimizer.canonicalStreamKey(link1080)
        val key720 = StreamLinkOptimizer.canonicalStreamKey(link720)
        val keyId2 = StreamLinkOptimizer.canonicalStreamKey(linkId2)

        assertNotEquals("1080p and 720p must have distinct keys", key1080, key720)
        assertNotEquals("id=1 and id=2 must have distinct keys", key1080, keyId2)
    }

    // =========================================================================
    // 4. Magnet InfoHash Normalization & Tracker Ordering
    // =========================================================================

    @Test
    fun testMagnetInfoHashNormalizationAcrossDiverseTrackersAndOrdering() {
        val hash = "3f4e5d6c7b8a901234567890abcdef1234567890"

        // 1. Standard format: xt first, then dn, then trackers
        val magnet1 = createLink(url = "magnet:?xt=urn:btih:$hash&dn=TestMovie.2024&tr=udp://tracker.open.org:1337&tr=udp://tracker.co:6969")

        // 2. Inverted order: trackers first, then dn, then xt in UPPERCASE
        val magnet2 = createLink(url = "magnet:?tr=udp://tracker.co:6969&dn=DifferentTitle&tr=udp://tracker.open.org:1337&xt=urn:btih:${hash.uppercase()}&ws=http://seed.org")

        // 3. Middle placement with extra BitTorrent extension parameters
        val magnet3 = createLink(url = "magnet:?dn=TestMovie&so=0,1,2&xt=urn:btih:$hash&xl=1500000000")

        val key1 = StreamLinkOptimizer.canonicalStreamKey(magnet1)
        val key2 = StreamLinkOptimizer.canonicalStreamKey(magnet2)
        val key3 = StreamLinkOptimizer.canonicalStreamKey(magnet3)

        val expectedKey = "magnet:$hash"
        assertEquals("Standard magnet canonical key", expectedKey, key1)
        assertEquals("Trackers before xt with uppercase hash must match", key1, key2)
        assertEquals("Extra magnet params must not alter infohash key", key1, key3)
    }

    // =========================================================================
    // 5. PixelDrain Direct Rewrites and Strict Header Isolation
    // =========================================================================

    @Test
    fun testPixelDrainViewerUrlRewritingAcrossAllDomains() {
        val hosts = listOf(
            "https://pixeldrain.com/u/abc123XYZ",
            "http://pixeldrain.com/u/abc123XYZ",
            "https://www.pixeldrain.com/u/abc123XYZ",
            "https://pixeldrain.dev/u/abc123XYZ",
            "https://pd.cybar.xyz/u/abc123XYZ"
        )

        for (u in hosts) {
            val link = createLink(url = u)
            val opt = StreamLinkOptimizer.optimize(link)
            assertEquals("Must rewrite to official direct download endpoint", "https://pixeldrain.com/api/file/abc123XYZ?download", opt.url)
        }
    }

    @Test
    fun testPixelDrainFileEndpointEnsuresSingleDownloadParam() {
        // Without download param
        val linkNoDownload = createLink(url = "https://pixeldrain.com/api/file/testId123")
        val opt1 = StreamLinkOptimizer.optimize(linkNoDownload)
        assertEquals("https://pixeldrain.com/api/file/testId123?download", opt1.url)

        // With existing download param (must not become ?download&download)
        val linkWithDownload = createLink(url = "https://pixeldrain.com/api/file/testId123?download")
        val opt2 = StreamLinkOptimizer.optimize(linkWithDownload)
        assertEquals("https://pixeldrain.com/api/file/testId123?download", opt2.url)

        // With existing other query params
        val linkWithQuery = createLink(url = "https://pixeldrain.com/api/file/testId123?format=raw")
        val opt3 = StreamLinkOptimizer.optimize(linkWithQuery)
        assertEquals("https://pixeldrain.com/api/file/testId123?format=raw&download", opt3.url)
    }

    @Test
    fun testPixelDrainPurgesHostileAndInheritedRefererAndOrigin() {
        val link = createLink(
            url = "https://pixeldrain.com/u/secretId",
            referer = "https://untrusted-referer.xyz/watch",
            headers = mapOf(
                "Referer" to "https://untrusted-referer.xyz/watch",
                "Origin" to "https://untrusted-referer.xyz",
                "User-Agent" to "CustomUA/1.0"
            )
        )

        val opt = StreamLinkOptimizer.optimize(link)

        // PixelDrain must NOT send Referer or Origin headers
        assertFalse("PixelDrain headers must not contain Referer", opt.headers.containsKey("Referer"))
        assertFalse("PixelDrain headers must not contain Origin", opt.headers.containsKey("Origin"))
        assertFalse("Case-insensitive referer check", opt.headers.keys.any { it.equals("referer", ignoreCase = true) })
        assertFalse("Case-insensitive origin check", opt.headers.keys.any { it.equals("origin", ignoreCase = true) })
        assertEquals("Effective referer on ExtractorLink must be empty", "", opt.referer)
    }

    // =========================================================================
    // 6. Gofile Rewrites & Required Header Injection
    // =========================================================================

    @Test
    fun testGofileHeadersAndRefererInjection() {
        val urls = listOf(
            "https://srv-store5.gofile.io/download/direct/token123/movie.mp4",
            "https://store1.gofile.io/download/web/abc.mp4",
            "https://gofile.io/d/folder123"
        )

        for (u in urls) {
            val link = createLink(url = u, referer = "https://wrongsite.com")
            val opt = StreamLinkOptimizer.optimize(link)

            assertEquals("Referer header must be https://gofile.io/", "https://gofile.io/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
            assertEquals("Origin header must be https://gofile.io", "https://gofile.io", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
            assertEquals("Effective referer property must be https://gofile.io/", "https://gofile.io/", opt.referer)
        }
    }

    // =========================================================================
    // 7. StreamTape Direct Parameter Injection & Required Headers
    // =========================================================================

    @Test
    fun testStreamTapeStreamParameterInjectionAndHeaders() {
        val domains = listOf(
            "https://streamtape.com/get_video?id=TapeId456&token=tok&expires=1700000000",
            "https://streamta.pe/get_video?id=TapeId456&token=tok",
            "https://strcloud.link/get_video?id=TapeId456"
        )

        for (u in domains) {
            val link = createLink(url = u, referer = "https://thirdparty.org")
            val opt = StreamLinkOptimizer.optimize(link)

            assertTrue("URL must contain stream=1 parameter: ${opt.url}", opt.url.contains("&stream=1") || opt.url.contains("?stream=1"))
            assertEquals("Referer header must be https://streamtape.com/", "https://streamtape.com/", opt.headers[StreamLinkOptimizer.HEADER_REFERER])
            assertEquals("Origin header must be https://streamtape.com", "https://streamtape.com", opt.headers[StreamLinkOptimizer.HEADER_ORIGIN])
            assertEquals("Effective referer property must be https://streamtape.com/", "https://streamtape.com/", opt.referer)
        }
    }

    @Test
    fun testStreamTapeDoesNotDuplicateStreamParam() {
        val url = "https://streamtape.com/get_video?id=TapeId456&stream=1"
        val link = createLink(url = url)
        val opt = StreamLinkOptimizer.optimize(link)

        val countStream1 = Regex("""stream=1""", RegexOption.IGNORE_CASE).findAll(opt.url).count()
        assertEquals("stream=1 must only occur once", 1, countStream1)
    }

    // =========================================================================
    // 8. StreamDeduplicator Atomic CAS & High-Concurrency Verification
    // =========================================================================

    @Test
    fun testDeduplicatorPrefersHigherQualityAndBitrateAcrossSubdomains() {
        val emitted = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emitted.add(it) }

        // Three CDN mirrors of the same video with increasing quality/bitrates
        val mirrorCdn1 = createLink(
            name = "[720p] [1500 kbps] Stream",
            url = "https://cdn1.example.com/movie.mp4?token=token1",
            quality = Qualities.P720.value
        )
        val mirrorEdge2 = createLink(
            name = "[1080p] [4500 kbps] Stream",
            url = "https://edge2.example.com/movie.mp4?token=token2",
            quality = Qualities.P1080.value
        )
        val mirrorSrv3 = createLink(
            name = "[1080p] [2000 kbps] Stream",
            url = "https://srv3.example.com/movie.mp4?token=token3",
            quality = Qualities.P1080.value
        )

        // 1. Emit 720p 1500 kbps
        assertTrue("720p emitted", deduplicator.emit(mirrorCdn1))
        // 2. Emit 1080p 4500 kbps (should upgrade)
        assertTrue("1080p 4500kbps upgrades 720p", deduplicator.emit(mirrorEdge2))
        // 3. Emit 1080p 2000 kbps (should be discarded as inferior bitrate)
        assertFalse("1080p 2000kbps discarded as inferior to 4500kbps", deduplicator.emit(mirrorSrv3))

        assertEquals("Total emitted count across mirrors", 2, emitted.size)
        assertEquals("Final retained stream is 1080p 4500kbps", mirrorEdge2, deduplicator.getEmittedLinks().first())
        assertEquals("Final internal map has exactly 1 deduplicated entry", 1, deduplicator.getEmittedCount())
    }

    @Test
    fun testDeduplicatorCollapsesViewerAndDirectEndpointsWhenOptimized() {
        val emitted = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator { emitted.add(it) }

        val viewerLink = createLink(
            name = "PixelDrain Stream",
            url = "https://pixeldrain.com/u/file123"
        )
        val directLink = createLink(
            name = "PixelDrain Stream",
            url = "https://pixeldrain.com/api/file/file123?download"
        )

        val optViewer = StreamLinkOptimizer.optimize(viewerLink)
        val optDirect = StreamLinkOptimizer.optimize(directLink)

        // Both optimize to the direct endpoint
        assertEquals("https://pixeldrain.com/api/file/file123?download", optViewer.url)
        assertEquals("https://pixeldrain.com/api/file/file123?download", optDirect.url)

        // Emit direct link first
        assertTrue(deduplicator.emit(optDirect))
        // Attempt to emit optimized viewer link (collides with direct link)
        assertFalse("Optimized viewer link is duplicate of direct link", deduplicator.emit(optViewer))

        assertEquals(1, deduplicator.getEmittedCount())
        assertEquals(optDirect, deduplicator.getEmittedLinks().first())
    }

    @Test
    fun testIsBetterThanPrefersDirectEndpointWhenOtherMetricsEqual() {
        val rawLink = createLink(url = "https://example.com/stream.mp4")
        val directLink = createLink(url = "https://example.com/stream.mp4?download")

        assertTrue("Direct endpoint should be better than raw link", StreamLinkOptimizer.isBetterThan(directLink, rawLink))
        assertFalse("Raw link should not be better than direct endpoint", StreamLinkOptimizer.isBetterThan(rawLink, directLink))
    }

    @Test
    fun testDeduplicatorHighContentionConcurrentRace() {
        val totalThreads = 100
        val emittedList = mutableListOf<ExtractorLink>()
        val deduplicator = StreamLinkOptimizer.StreamDeduplicator {
            synchronized(emittedList) {
                emittedList.add(it)
            }
        }

        val executor = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(totalThreads)
        val winnerIndex = 42

        for (i in 0 until totalThreads) {
            val q = if (i == winnerIndex) Qualities.P2160.value else Qualities.P720.value
            val bitrate = if (i == winnerIndex) 20000L else 2000L
            val host = if (i % 2 == 0) "cdn1.example.com" else "edge-us-2.example.com"

            executor.submit {
                try {
                    val link = createLink(
                        name = "[$q] [$bitrate kbps] Video",
                        url = "https://$host/stream.mp4?token=t$i&expires=${1700000000 + i}",
                        quality = q
                    )
                    deduplicator.emit(link)
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads must complete in 10s", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Verify that regardless of race order, only 1 canonical entry remains in the deduplicator
        assertEquals("Canonical deduplication must collapse all 100 emissions into 1 entry", 1, deduplicator.getEmittedCount())
        val finalLink = deduplicator.getEmittedLinks().first()
        assertEquals("Highest quality 4K stream must be the final retained entry", Qualities.P2160.value, finalLink.quality)
    }
}
