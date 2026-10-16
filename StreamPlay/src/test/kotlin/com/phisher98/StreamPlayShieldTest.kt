package com.phisher98

import okhttp3.Dns
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Acceptance contract for [StreamPlayShield] — the production ad/tracker policy engine —
 * across all three enforcement tiers:
 *
 *  T1 DNS gate  -> [OptimizedDns.lookup] must refuse blocked hostnames before ANY resolver
 *                  flight (system or DoH) and keep the standard UnknownHostException contract.
 *  T2 requests  -> [StreamPlayShield.evaluateRequest] must block ad/tracker hosts, strip
 *                  tracking particles, and enforce the origin-only cross-host Referer
 *                  policy while preserving pinned extractor referers byte-for-byte.
 *  T3 emission  -> [StreamLinkOptimizer.optimize] must scrub particles, tombstone blocked
 *                  hosts, and [StreamLinkOptimizer.StreamDeduplicator] must drop them with
 *                  metrics — including for flows that bypass the optimizer.
 *
 * The registry's load-bearing invariant is also pinned: the vetted streaming fleet can
 * never collide with the block database, now or after future list growth.
 */
class StreamPlayShieldTest {

    @Before
    fun reset() {
        StreamPlayShield.resetMetrics()
    }

    // =========================================================================
    // 1. Registry integrity
    // =========================================================================

    @Test
    fun testRegistryEntriesAreWellFormed() {
        val registry = StreamPlayShield.BLOCKED_AD_DOMAINS + StreamPlayShield.BLOCKED_TRACKER_DOMAINS
        assertTrue("registry must not be empty", registry.isNotEmpty())
        for (entry in registry) {
            assertTrue(
                "entry '$entry' must be lowercase",
                entry == entry.lowercase()
            )
            assertFalse("entry '$entry' must not contain wildcards", entry.contains('*'))
            assertFalse("entry '$entry' must not be empty", entry.isBlank())
            assertFalse("entry '$entry' must not start with a dot", entry.startsWith("."))
            assertFalse("entry '$entry' must not end with a dot", entry.endsWith("."))
            val labels = entry.split(".")
            assertTrue("entry '$entry' must have at least two labels", labels.size >= 2)
            assertTrue(
                "entry '$entry' must end in an alphabetic TLD",
                labels.last().all { it.isLetter() }
            )
            assertTrue(
                "entry '$entry' must not be a bare public suffix",
                StreamPlayShield.isBlockedHost(entry) // registry drives real decisions
            )
        }
    }

    @Test
    fun testVettedSourceFleetMustNeverBeBlocked() {
        val vetted = StreamPlayShield.VETTED_SOURCE_DOMAINS
        val registry = StreamPlayShield.BLOCKED_AD_DOMAINS + StreamPlayShield.BLOCKED_TRACKER_DOMAINS
        // Exact collision and subdomain-trap: no vetted domain may be blocked, and no registry
        // entry may sit under a vetted domain.
        for (host in vetted) {
            assertEquals("vetted source '$host' must classify ALLOW", StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost(host))
            assertTrue("vetted '$host' must never itself be a registry entry", !registry.contains(host))
        }
        for (entry in registry) {
            val vettedOwner = vetted.any { StreamPlayShield.isDotSuffix(entry, it) }
            assertFalse("registry entry '$entry' must not be a subdomain of a vetted source", vettedOwner)
        }
        // And every subdomain of a vetted source is released too.
        for (sub in listOf("bcdn.hakunaymatata.com", "lit.cheaptruckrepairs.cc", "cdn.vidrock.net", "e1.vidlink.pro")) {
            assertEquals("vetted subdomain '$sub' must be ALLOW", StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost(sub))
        }
    }

    // =========================================================================
    // 2. Host decision matrix
    // =========================================================================

    @Test
    fun testBlockedAdHostMatrix() {
        // Exact entries, subdomains, deep subdomains.
        for (host in listOf(
            "doubleclick.net", "stats.g.doubleclick.net", "securepubads.g.doubleclick.net",
            "pagead2.googlesyndication.com", "ads.rubiconproject.com",
            "px.ads.linkedin.com", "bat.bing.com", "gemius.pl"
        )) {
            assertTrue("$host must be blocked", StreamPlayShield.isBlockedHost(host))
        }
        assertTrue(StreamPlayShield.isBlockedAdHost("pubmatic.com"))
        assertFalse("pubmatic.com is ad-only", StreamPlayShield.isBlockedTrackerHost("pubmatic.com"))
        assertTrue(StreamPlayShield.isBlockedTrackerHost("connect.facebook.net"))
        assertFalse("facebook pixel is tracker-only", StreamPlayShield.isBlockedAdHost("connect.facebook.net"))
    }

    @Test
    fun testLookalikeAndSiblingHostsMustNotBeBlocked() {
        // Suffix walking must never cross a dot the registry does not own.
        for (host in listOf(
            "doubleclick.net.evil.com",       // registry string smuggled mid-chain
            "evil-doubleclick.net",           // sibling subdomain of another registrant
            "doubleclickze.net",              // lookalike brand
            "doubleclick.com.au",             // different registrable domain on another TLD
            "notevildoubleclick.io",          // concat lookalike
            "widget.gemius.co.uk",            // multi-label TLD is NOT registered as such
            "yandex.com",                     // the un-pixel Russian root is not blanket-banned
            "analytics.example.com"           // own-site analytics on a non-registry domain
        )) {
            assertEquals("'$host' must NOT be blocked", StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost(host))
        }
    }

    @Test
    fun testIpLiteralsAndEdgesRelease() {
        assertEquals(StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost("192.168.1.1"))
        assertEquals(StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost("[::1]"))
        assertEquals(StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost(""))
        assertEquals(StreamPlayShield.CATEGORY_ALLOW, StreamPlayShield.classifyHost("1.2.3.4.example-video-cdn.com"))
    }

    @Test
    fun testUppercaseHostsNormalizeToLowerRegistry() {
        assertTrue(StreamPlayShield.isBlockedHost("PAGEAD2.GOOGLESYNDICATION.COM"))
        assertTrue(StreamPlayShield.isBlockedHost("Connect.Facebook.Net"))
        assertFalse("vetted hosts release regardless of case", StreamPlayShield.isBlockedHost("CDN.VIDROCK.NET"))
    }

    // =========================================================================
    // 3. hostOf bounded scanner
    // =========================================================================

    @Test
    fun testHostOfExtractionMatrix() {
        assertEquals("vidlink.pro", StreamPlayShield.hostOf("https://vidlink.pro/bd/token"))
        assertEquals("cdn.vidrock.net", StreamPlayShield.hostOf("https://CDN.vidrock.net"))
        assertEquals("moonfirekeeper.lol", StreamPlayShield.hostOf("https://moonfirekeeper.lol:8443/file2/a.m3u8"))
        assertEquals("usercdn.example.com", StreamPlayShield.hostOf("https://user:pass@usercdn.example.com/api"))
        assertEquals("workers.dev.example", StreamPlayShield.hostOf("workers.dev.example/hls/now"))
        assertEquals("barehost.io", StreamPlayShield.hostOf("barehost.io"))
        assertEquals("", StreamPlayShield.hostOf("https://"))
        assertEquals("", StreamPlayShield.hostOf("/relative/path?query"))
        assertEquals("", StreamPlayShield.hostOf(""))
        assertEquals("m3u8.gr", StreamPlayShield.hostOf("https://m3u8.gr/v?k=1#frag"))
    }

    @Test
    fun testIsBlockedUrl() {
        assertTrue(StreamPlayShield.isBlockedUrl("https://ads.pubmatic.com/openbid/v9/type/b/getuid?pid=36"))
        assertFalse(StreamPlayShield.isBlockedUrl("https://vidrock.net/api/movie/550"))
        assertFalse(StreamPlayShield.isBlockedUrl(""))
    }

    // =========================================================================
    // 4. Tracking-particle hygiene
    // =========================================================================

    @Test
    fun testStripKeepsCleanUrlsWithZeroAllocationFastPath() {
        val url = "https://galacticvoyagers.xyz/file3/abc/master.m3u8"
        assertSame("clean URL must be returned by reference (zero-copy fast path)", url, StreamPlayShield.stripTrackingParticles(url))
        val withEmptyQuery = "$url?"
        assertSame(
            "query-only URL must be returned by reference",
            withEmptyQuery,
            StreamPlayShield.stripTrackingParticles(withEmptyQuery)
        )
        assertFalse(StreamPlayShield.hasTrackingParticles(url))
    }

    @Test
    fun testStripRemovesTheFullParticleFamily() {
        val dirty = "https://media.example.com/h/now/vid.m3u8" +
            "?utm_source=ig&utm_medium=cpc&utm_campaign=tok&fbclid=IwAR9&gclid=EAIaIQ&msclkid=Zr1d" +
            "&ttclid=Cg1&yclid=12k&twclid=T1&dclid=D2&li_fat_id=LI1&gbraid=G1&wbraid=W1" +
            "&gad_source=1&srsltid=A9&m1=x&_gl=1*abc*_ga*MTE&igshid=1a&spm=a2h0k" +
            "&s_kwcid=AL!&_hsenc=p2&_hsmi=3&mc_eid=9&mc_cid=X&vero_conv=V&vero_id=C&oly_anon_id=O&oly_enc_id=E&keep=1"
        val cleaned = StreamPlayShield.stripTrackingParticles(dirty)
        assertEquals("https://media.example.com/h/now/vid.m3u8?m1=x&keep=1", cleaned)
        assertTrue(StreamPlayShield.hasTrackingParticles(dirty))
        assertFalse(StreamPlayShield.hasTrackingParticles(cleaned))
    }

    @Test
    fun testStripNeverTouchesFunctionalOrSignedParameters() {
        val url = "https://api.example.com/stream/id?token=abc123&auth=9&signature=DEAD&" +
            "download=1&q=vid&ref=please&callback=cb&sid=7"
        assertSame(
            "signed/functional params must survive untouched (same-reference fast path)",
            url,
            StreamPlayShield.stripTrackingParticles(url)
        )
    }

    @Test
    fun testStripPreservesFragmentsByteForByte() {
        val dirty = "https://cdn.example.com/master.m3u8?utm_source=x&vid_token=abc#VID=YT2d-Ad-2c&len=1"
        val cleaned = StreamPlayShield.stripTrackingParticles(dirty)
        assertEquals("cleaned fragment routing must be intact", "VID=YT2d-Ad-2c&len=1", cleaned.substringAfter('#'))
        assertEquals("https://cdn.example.com/master.m3u8?vid_token=abc#VID=YT2d-Ad-2c&len=1", cleaned)
    }

    @Test
    fun testStripToleratesQuestionMarksInsideFragments() {
        val dirty = "https://cdn.example.com/master.m3u8?utm_source=y#g=1?keep=2"
        assertEquals("https://cdn.example.com/master.m3u8#g=1?keep=2", StreamPlayShield.stripTrackingParticles(dirty))
    }

    @Test
    fun testStripHandlesParticleOnlyQueries() {
        assertEquals("https://x.example.com/a", StreamPlayShield.stripTrackingParticles("https://x.example.com/a?fbclid=fun"))
        assertEquals("https://x.example.com/a", StreamPlayShield.stripTrackingParticles("https://x.example.com/a?utm_campaign=z"))
    }

    // =========================================================================
    // 5. Tier-2: evaluateRequest (the interceptor's pure decision)
    // =========================================================================

    @Test
    fun testEvaluateRequestBlocksAdAndTrackerEgress() {
        val adRequest = Request.Builder().url("https://ads.pubmatic.com/openbid/v9/getuid").build()
        val trackerRequest = Request.Builder()
            .url("https://google-analytics.com/collect?v=1")
            .header("Referer", "https://somewhere.example/payment?sku=42")
            .build()

        val adDecision = StreamPlayShield.evaluateRequest(adRequest)
        assertTrue(adDecision is StreamPlayShield.ShieldDecision.Blocked)
        val blockedAd = adDecision as StreamPlayShield.ShieldDecision.Blocked
        assertEquals("ads.pubmatic.com", blockedAd.host)
        assertEquals(StreamPlayShield.CATEGORY_AD, blockedAd.category)

        val trackerDecision = StreamPlayShield.evaluateRequest(trackerRequest)
        assertTrue(trackerDecision is StreamPlayShield.ShieldDecision.Blocked)
        val blockedTracker = trackerDecision as StreamPlayShield.ShieldDecision.Blocked
        assertEquals(StreamPlayShield.CATEGORY_TRACKER, blockedTracker.category)

        val m = StreamPlayShield.metrics()
        assertEquals(1, m.blockedAdRequests)
        assertEquals(1, m.blockedTrackerRequests)
    }

    @Test
    fun testEvaluateRequestFastPathIsZeroCopy() {
        val request = Request.Builder().url("https://api.kuonilla.dev/from?user=m").build()
        val decision = StreamPlayShield.evaluateRequest(request)
        assertTrue(decision is StreamPlayShield.ShieldDecision.Allowed)
        assertNull("no rewrite needed", (decision as StreamPlayShield.ShieldDecision.Allowed).rewritten)
        assertEquals(0, StreamPlayShield.metrics().particleScrubs)
    }

    @Test
    fun testEvaluateRequestStripsParticlesViaOkhttpBuilder() {
        val request = Request.Builder()
            .url("https://cdn.example.com/manifest?gclid=EAIaIQ&token=keepme&fbclid=F00d")
            .build()
        val decision = StreamPlayShield.evaluateRequest(request)
        assertTrue(decision is StreamPlayShield.ShieldDecision.Allowed)
        val rewritten = (decision as StreamPlayShield.ShieldDecision.Allowed).rewritten
        assertNotNull(rewritten)
        val url = rewritten!!.url
        assertEquals("token", url.queryParameterNames.firstOrNull() ?: fail("token must remain"))
        assertEquals("keepme", url.queryParameter("token"))
        assertNull("gclid must be gone", url.queryParameter("gclid"))
        assertNull("fbclid must be gone", url.queryParameter("fbclid"))
        assertEquals(1, StreamPlayShield.metrics().particleScrubs)
    }

    @Test
    fun testEvaluateRequestRewritesThirdPartyRefererToOriginOnly() {
        val request = Request.Builder()
            .url("https://media.example.com/ep/12/hls.m3u8")
            .header(
                "Referer",
                "https://discover.somecontent.example/view?sku=994&list=latenight&memo=1234567890"
            )
            .build()
        val decision = StreamPlayShield.evaluateRequest(request)
        val rewritten = (decision as StreamPlayShield.ShieldDecision.Allowed).rewritten
        assertNotNull(rewritten)
        assertEquals(
            "cross-host referer must be reduced to its origin",
            "https://discover.somecontent.example",
            rewritten!!.header("Referer")
        )
        assertEquals(1, StreamPlayShield.metrics().refererLeaned)
    }

    @Test
    fun testEvaluateRequestPreservesSameHostAndPinnedReferers() {
        // Same-host referer: full URL kept (origin policy is cross-host only).
        val sameHost = Request.Builder()
            .url("https://chorusplayer.example/api?x=1")
            .header("Referer", "https://chorusplayer.example/watch/now?id=9")
            .build()
        val sameDecision = StreamPlayShield.evaluateRequest(sameHost)
        assertNull((sameDecision as StreamPlayShield.ShieldDecision.Allowed).rewritten)

        // Pinned extractor referers (vidrock CDN family): byte-for-byte untouched.
        val pinned = Request.Builder()
            .url("https://galacticvoyagers.xyz/file3/abc/master.m3u8")
            .header("Referer", "https://vidrock.net/")
            .build()
        val pinnedDecision = StreamPlayShield.evaluateRequest(pinned)
        val pinnedRewritten = (pinnedDecision as StreamPlayShield.ShieldDecision.Allowed).rewritten
        assertNull("vetted pinned referer must survive untouched", pinnedRewritten)
        assertEquals(0, StreamPlayShield.metrics().refererLeaned)

        // Subdomain source referers also count as pinned (cdn.vidrock.net etc.).
        val pinnedSub = Request.Builder()
            .url("https://media.example.com/x")
            .header("Referer", "https://cdn2.moonfirekeeper.lol/file2/abc/master.m3u8")
            .build()
        assertNull((StreamPlayShield.evaluateRequest(pinnedSub) as StreamPlayShield.ShieldDecision.Allowed).rewritten)
    }

    @Test
    fun testEvaluateRequestKeepsSchemesAndPortsOnRefererRewrite() {
        val request = Request.Builder()
            .url("https://media.example.com/x")
            .header("Referer", "http://internalproxy.example:8080/series/abc?authLeak=shouldVanish")
            .build()
        val rewritten = (StreamPlayShield.evaluateRequest(request) as StreamPlayShield.ShieldDecision.Allowed).rewritten
        assertNotNull(rewritten)
        assertEquals(
            "origin must keep scheme and port, drop the leaking path",
            "http://internalproxy.example:8080",
            rewritten!!.header("Referer")
        )
    }

    @Test
    fun testEvaluateRequestIgnoresNonAbsoluteReferers() {
        val request = Request.Builder()
            .url("https://media.example.com/x")
            .header("Referer", "just-a-page-name")
            .build()
        assertNull((StreamPlayShield.evaluateRequest(request) as StreamPlayShield.ShieldDecision.Allowed).rewritten)
    }

    // =========================================================================
    // 6. Tier-1: the DNS gate
    // =========================================================================

    private class RecordingDelegate : Dns {
        val requested = mutableListOf<String>()
        override fun lookup(hostname: String): List<InetAddress> {
            requested.add(hostname)
            return listOf(InetAddress.getByName("10.9.8.7"))
        }
    }

    @Test
    fun testDnsGateRefusesBlockedHostsWithoutAnyResolverFlight() {
        val blockedHostname = "ssl.google-analytics.com"
        assertTrue("test precondition: hostname must genuinely be registered", StreamPlayShield.isBlockedHost(blockedHostname))

        val delegate = RecordingDelegate()
        val dns = OptimizedDns(
            delegate = delegate,
            dohResolver = { _: String ->
                throw AssertionError("the DoH race must never be entered for a blocked host")
            },
            preferDoh = false
        )
        try {
            dns.lookup(blockedHostname)
            fail("blocked hostname must throw UnknownHostException")
        } catch (expected: UnknownHostException) {
            assertEquals("no system DNS flight may happen", 0, delegate.requested.size)
            assertEquals(1, StreamPlayShield.metrics().dnsGateCalls)
            assertTrue(expected.message!!.contains("StreamPlayShield"))
        }
    }

    @Test
    fun testDnsGateLetsVettedAndCleanHostsResolve() {
        val delegate = RecordingDelegate()
        val dns = OptimizedDns(delegate = delegate)
        val resolved = dns.lookup("vidrock.net")
        assertEquals(listOf(InetAddress.getByName("10.9.8.7")), resolved)
        assertEquals(listOf("vidrock.net"), delegate.requested)
        assertEquals(0, StreamPlayShield.metrics().dnsGateCalls)
    }

    @Test
    fun testDnsGateStillResolvesLiteralIps() {
        val dns = OptimizedDns(delegate = RecordingDelegate())
        val literal = dns.lookup("10.1.2.3")
        assertEquals(listOf(InetAddress.getByName("10.1.2.3")), literal)
    }

    // =========================================================================
    // 7. Tier-3: emission hygiene through optimize() + StreamDeduplicator
    // =========================================================================

    @Suppress("DEPRECATION")
    private fun linkOf(
        url: String,
        source: String = "shield-test",
        name: String = "VidLink [720p]"
    ) = com.lagradost.cloudstream3.utils.ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = "https://source.example/",
        quality = com.lagradost.cloudstream3.utils.Qualities.P720.value,
        type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
    )

    @Test
    fun testOptimizeTombstonesBlockedUrls() {
        val prepared = StreamLinkOptimizer.optimize(linkOf("https://tags.pubmatic.com/x?id=9"))
        assertEquals("blocked URL must reduce to an empty tombstone", "", prepared.url)
        assertEquals(1, StreamPlayShield.metrics().emissionsBlocked)

        // The tombstone must die at the dedup gate and never reach the dispatcher.
        var forwarded = 0
        val dedup = StreamLinkOptimizer.StreamDeduplicator(upstreamCallback = { forwarded++ })
        assertEquals(StreamLinkOptimizer.DeduplicationResult.DROPPED, dedup.emitDetailed(prepared))
        assertEquals(0, forwarded)
        assertEquals("tombstones are dropped by invalid-url semantics, not double counted", 1, StreamPlayShield.metrics().emissionsBlocked)
    }

    @Test
    fun testDedupGateDropsBlockedHostsEvenWithoutOptimizer() {
        var forwarded = 0
        val dedup = StreamLinkOptimizer.StreamDeduplicator(
            upstreamCallback = { forwarded++ },
            onUpgradeCallback = { forwarded++ }
        )
        val before = StreamPlayShield.metrics().emissionsBlocked
        assertEquals(
            StreamLinkOptimizer.DeduplicationResult.DROPPED,
            dedup.emitDetailed(linkOf("https://pixel.quantserve.com/pixel/p-x"))
        )
        assertEquals(0, forwarded)
        assertEquals("direct blocked emission must be counted once", before + 1, StreamPlayShield.metrics().emissionsBlocked)
    }

    @Test
    fun testOptimizeScrubsParticlesFromEmittedLinks() {
        val prepared = StreamLinkOptimizer.optimize(
            linkOf("https://cdn.scrapflow.example/now/hls/master.m3u8?utm_source=feed&gclid=G9&si=allowed")
        )
        assertEquals(
            "gentle particle strip on emission",
            "https://cdn.scrapflow.example/now/hls/master.m3u8?si=allowed",
            prepared.url
        )
    }

    @Test
    fun testVettedSourceUrlsSurviveHygieneByteForByte() {
        val urls = listOf(
            "https://galacticvoyagers.xyz/file3/YTk2/master.m3u8",
            "https://bcdn.hakunaymatata.com/bt/a/101.m3u8",
            "https://lit.cheaptruckrepairs.cc/h/master.m3u8"
        )
        for (url in urls) {
            val prepared = StreamLinkOptimizer.optimize(linkOf(url))
            assertEquals("vetted URL '$url' must be untouched by the shield", url, prepared.url)
        }
    }

    @Test
    fun testMagnetAndNonHttpFlowUnaffected() {
        val magnet = StreamLinkOptimizer.optimize(linkOf("magnet:?xt=urn:btih:abc"))
        assertEquals("magnet:?xt=urn:btih:abc", magnet.url)
    }

    // =========================================================================
    // 8. Metrics plumbing
    // =========================================================================

    @Test
    fun testMetricsSnapshotIsCoherent() {
        StreamPlayShield.evaluateRequest(
            Request.Builder().url("https://doubleclick.net/x?gclid=A").build()
        ) // counted: 1 blocked ad request (particle scrub after block is irrelevant)
        val afterBlock = StreamPlayShield.metrics()
        assertEquals(1, afterBlock.blockedAdRequests)
        assertEquals(0, afterBlock.particleScrubs)
        assertTrue(StreamPlayShield.describe().contains("StreamPlayShield"))
        assertTrue(StreamPlayShield.describe().contains("blocked"))

        StreamPlayShield.resetMetrics()
        assertEquals(0, StreamPlayShield.metrics().blockedAdRequests)
        assertEquals(0, StreamPlayShield.metrics().particleScrubs)
    }

    @Test
    fun testAdBlockedExceptionIsTextuallyAnIoException() {
        val exception = StreamPlayShield.AdBlockedException("ads.example.com", "ad")
        assertTrue("failures must route through the standard IOException network contract", exception is java.io.IOException)
        assertTrue(exception.message!!.contains("ads.example.com"))
        assertEquals("ad", exception.category)
    }

    // =========================================================================
    // 9. Internal daughter's end-to-end fast-path sanity
    // =========================================================================

    @Test
    fun testHostClassificationPerfSanity() {
        // Classification must stay in the nanosecond/low-microsecond regime per call; assert a
        // generous but meaningful ceiling to catch accidental regressions to regex parsing.
        val started = System.nanoTime()
        var allowed = 0
        for (i in 0 until 20_000) {
            if (!StreamPlayShield.isBlockedHost("node101-cdnhost7s.example-streaming-cdn.net")) allowed++
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(20_000, allowed)
        assertTrue(
            "20k classifications must stay well under a second (took ${elapsedMillis}ms)",
            elapsedMillis < 1_000
        )
    }
}
