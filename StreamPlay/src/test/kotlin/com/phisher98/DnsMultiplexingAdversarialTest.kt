package com.phisher98

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Verification Test Suite for Milestone 1:
 * DNS Resolution Edge Cases, DoH Fallback, Bogon Filtering,
 * Connection Multiplexing, and SingleFlight Concurrency/Cancellation.
 */
class DnsMultiplexingAdversarialTest {

    private val ipv4A = InetAddress.getByName("192.0.2.1")
    private val ipv4B = InetAddress.getByName("198.51.100.1")
    private val ipv6A = InetAddress.getByName("2001:db8::1")
    private val ipv6B = InetAddress.getByName("2001:db8::2")
    private val loopbackV4 = InetAddress.getByName("127.0.0.1")
    private val anyLocalV4 = InetAddress.getByName("0.0.0.0")
    private val loopbackV6 = InetAddress.getByName("::1")
    private val anyLocalV6 = InetAddress.getByName("::")

    private var originalBaseClient: OkHttpClient? = null

    @Before
    fun setUp() {
        OptimizedDns.clearCacheForTesting()
        SingleFlight.clearGlobal()
        originalBaseClient = app.baseClient
        NetworkOptimizer.resetForTesting(originalBaseClient)
    }

    @After
    fun tearDown() {
        OptimizedDns.clearCacheForTesting()
        SingleFlight.clearGlobal()
        originalBaseClient?.let { NetworkOptimizer.resetForTesting(it) }
    }

    // ─── 1. BOGON FILTERING & DOH FALLBACK ADVERSARIAL TESTS ────────────────

    @Test
    fun testBogonFilteringRejectsAllBogonAndTriggersDoHFallback() {
        // Delegate returns ONLY poisoned bogon addresses (0.0.0.0 and 127.0.0.1)
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(anyLocalV4, loopbackV4)
        }

        val dohCallCount = AtomicInteger(0)
        val mockDoh: (String) -> List<InetAddress> = { host ->
            dohCallCount.incrementAndGet()
            listOf(ipv4A)
        }

        val dns = OptimizedDns(delegate = mockDelegate, dohResolver = mockDoh)

        // Remote host poisoned with bogon addresses
        val resolved = dns.lookup("poisoned-upstream.stream.io")

        // 1. Delegate was called and returned bogons
        assertEquals(1, delegateCount.get())
        // 2. Bogons were stripped -> addresses was empty -> DoH was invoked as fallback
        assertEquals(1, dohCallCount.get())
        // 3. The final resolved list contains ONLY valid DoH IP
        assertEquals(listOf(ipv4A), resolved)
    }

    @Test
    fun testBogonFilteringRejectsIpv6LoopbackAndAnyLocal() {
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(loopbackV6, anyLocalV6, ipv4B)
        }

        val dns = OptimizedDns(delegate = mockDelegate)

        val resolved = dns.lookup("ipv6-poisoned.domain.com")
        assertEquals(1, delegateCount.get())
        // ::1 and :: must be stripped, leaving only ipv4B
        assertEquals(listOf(ipv4B), resolved)
    }

    @Test
    fun testDoHReturnsBogonTriggersCleanUnknownHostException() {
        // Both System DNS and DoH return only bogon IPs
        val mockDelegate = Dns {
            listOf(anyLocalV4)
        }
        val mockDoh: (String) -> List<InetAddress> = {
            listOf(loopbackV4, loopbackV6)
        }

        val dns = OptimizedDns(delegate = mockDelegate, dohResolver = mockDoh)

        try {
            dns.lookup("all-poisoned.com")
            fail("Expected UnknownHostException when all resolvers return bogons")
        } catch (e: UnknownHostException) {
            assertTrue(e.message?.contains("Unable to resolve host") == true)
        }
    }

    @Test
    fun testSystemDnsTimeoutOrNetworkExceptionTriggersDoHFallback() {
        val mockDelegate = Dns {
            throw SocketTimeoutException("DNS query timed out (packet dropped)")
        }

        val dohCallCount = AtomicInteger(0)
        val mockDoh: (String) -> List<InetAddress> = {
            dohCallCount.incrementAndGet()
            listOf(ipv4A)
        }

        val dns = OptimizedDns(delegate = mockDelegate, dohResolver = mockDoh)

        val resolved = dns.lookup("timeout-fallback.org")
        assertEquals(1, dohCallCount.get())
        assertEquals(listOf(ipv4A), resolved)
    }

    @Test
    fun testPreferDohFallbackToSystemDnsOnDoHFailure() {
        // DoH fails with exception
        val dohCallCount = AtomicInteger(0)
        val mockDoh: (String) -> List<InetAddress> = {
            dohCallCount.incrementAndGet()
            throw IOException("DoH TLS Handshake timeout")
        }

        // System DNS succeeds
        val delegateCount = AtomicInteger(0)
        val mockDelegate = Dns {
            delegateCount.incrementAndGet()
            listOf(ipv4A)
        }

        val dns = OptimizedDns(
            delegate = mockDelegate,
            dohResolver = mockDoh,
            preferDoh = true
        )

        val resolved = dns.lookup("doh-fail-sys-fallback.org")
        assertEquals(1, dohCallCount.get())
        assertEquals(1, delegateCount.get())
        assertEquals(listOf(ipv4A), resolved)
    }

    // ─── 2. HAPPY EYEBALLS IP RACING ADVERSARIAL TESTS ──────────────────────

    @Test
    fun testHappyEyeballsIpv6OnlyHostPreserved() {
        val mockDelegate = Dns {
            listOf(ipv6A, ipv6B)
        }
        val dns = OptimizedDns(delegate = mockDelegate)

        val resolved = dns.lookup("ipv6-only.domain.org")
        assertEquals(2, resolved.size)
        assertTrue(resolved[0] is Inet6Address)
        assertTrue(resolved[1] is Inet6Address)
        assertEquals(listOf(ipv6A, ipv6B), resolved)
    }

    @Test
    fun testHappyEyeballsComplexInterleaving() {
        val dns = OptimizedDns()
        val mixed = listOf(ipv6A, ipv4A, ipv6B, ipv4B, anyLocalV4) // anyLocalV4 will be kept by preferIpv4, but sorted
        val sorted = dns.preferIpv4(mixed)

        // All IPv4 must precede all IPv6
        val firstNonIpv4Index = sorted.indexOfFirst { it !is Inet4Address }
        val lastIpv4Index = sorted.indexOfLast { it is Inet4Address }

        assertTrue("All IPv4 addresses must appear before any IPv6 address", firstNonIpv4Index > lastIpv4Index)
        assertEquals(5, sorted.size)
    }

    // ─── 3. LRU CACHE BOUNDS & MULTITHREADED CONCURRENCY ───────────────────

    @Test
    fun testCacheBoundsAndEvictionAtCapacity() {
        val dns = OptimizedDns()

        // Insert 600 entries into LRU cache (limit is DEFAULT_MAX_CACHE_SIZE = 512)
        for (i in 1..600) {
            dns.putCached("host-$i.test.com", listOf(ipv4A), ttlMs = 600_000L)
        }

        assertEquals("Cache size must be capped at 512", OptimizedDns.DEFAULT_MAX_CACHE_SIZE, dns.cacheSize)
        // First 88 entries (host-1 to host-88) should have been evicted
        assertNull("Earliest entry must have been evicted", dns.getCached("host-1.test.com"))
        assertNull("Earliest entry must have been evicted", dns.getCached("host-80.test.com"))
        // Recent entries must still exist
        assertNotNull("Recent entry must be present", dns.getCached("host-600.test.com"))
        assertNotNull("Recent entry must be present", dns.getCached("host-550.test.com"))
    }

    @Test
    fun testConcurrentCacheAccessStress() {
        val dns = OptimizedDns()
        val threads = 30
        val operationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        for (t in 1..threads) {
            executor.submit {
                try {
                    for (op in 1..operationsPerThread) {
                        val host = "concurrent-host-${op % 50}.test"
                        dns.putCached(host, listOf(ipv4A), 60_000L)
                        dns.getCached(host)
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("Zero concurrency errors under multithreaded cache access", 0, errors.get())
    }

    // ─── 4. SINGLEFLIGHT FOLLOWER CANCELLATION & KEY ISOLATION ─────────────

    @Test
    fun testSingleFlightConcurrentFollowerCancellationDoesNotAffectOthers() = runBlocking {
        val flight = SingleFlight<String, String>()
        val totalCallers = 40
        val executionCounter = AtomicInteger(0)

        val completedResults = ConcurrentHashMap<Int, String>()
        val leaderStarted = CountDownLatch(1)
        val leaderFinishLatch = CountDownLatch(1)
        // 1. Start leader explicitly so it is known
        val leaderJob = launch(Dispatchers.IO) {
            val res = flight.execute("stress_shared_key") {
                executionCounter.incrementAndGet()
                leaderStarted.countDown()
                leaderFinishLatch.await(5, TimeUnit.SECONDS) // Coordinated upstream hold
                "coalesced_success_payload"
            }
            completedResults[0] = res
        }

        assertTrue(leaderStarted.await(2, TimeUnit.SECONDS))

        val followersStarted = CountDownLatch(15)
        // 2. Start 39 followers
        val followerJobs = (1 until totalCallers).map { id ->
            val job = launch(Dispatchers.IO) {
                if (id <= 15) {
                    followersStarted.countDown()
                }
                try {
                    val res = flight.execute("stress_shared_key") {
                        executionCounter.incrementAndGet()
                        "follower_should_not_run_if_leader_alive"
                    }
                    completedResults[id] = res
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // Expected for cancelled coroutines
                }
            }
            Pair(id, job)
        }

        assertTrue(followersStarted.await(2, TimeUnit.SECONDS))
        kotlinx.coroutines.yield()

        // 3. Cancel 15 followers while leader is still waiting
        followerJobs.take(15).forEach { (_, job) ->
            job.cancelAndJoin()
        }

        // 4. Release leader to complete
        leaderFinishLatch.countDown()

        // 5. Wait for leader and remaining followers
        leaderJob.join()
        followerJobs.drop(15).forEach { (_, job) ->
            job.join()
        }

        // Leader was never cancelled, so upstream block should execute exactly once
        assertEquals("Upstream must execute exactly once when only followers cancel", 1, executionCounter.get())

        // The remaining callers (leader + 24 uncancelled followers = 25) must all have completed
        assertEquals(25, completedResults.size)
        completedResults.values.forEach { res ->
            assertEquals("coalesced_success_payload", res)
        }
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testSingleFlightLeaderCancellationRecoversAllRemainingFollowers() = runBlocking {
        val flight = SingleFlight<String, String>()
        val totalFollowers = 30
        val executionCounter = AtomicInteger(0)
        val completedResults = ConcurrentHashMap<Int, String>()
        val startedLatch = CountDownLatch(1)

        // 1. Start leader that will be cancelled
        val leaderJob = launch(Dispatchers.IO) {
            try {
                flight.execute("leader_cancel_shared_key") {
                    executionCounter.incrementAndGet()
                    startedLatch.countDown()
                    delay(200)
                    "leader_never_finishes"
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Expected
            }
        }

        assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

        // 2. Start 30 followers awaiting the leader
        val followerJobs = (1..totalFollowers).map { id ->
            val job = launch(Dispatchers.IO) {
                val res = flight.execute("leader_cancel_shared_key") {
                    executionCounter.incrementAndGet()
                    delay(50) // Simulate upstream network call
                    "elected_follower_payload"
                }
                completedResults[id] = res
            }
            Pair(id, job)
        }

        delay(30)
        // 3. Cancel leader while 30 followers are awaiting it
        leaderJob.cancel()
        leaderJob.join()

        // 4. All followers should recover via elected successor
        followerJobs.forEach { (_, job) ->
            job.join()
        }

        // Executions: 1 for cancelled leader + 1 for elected follower successor = 2
        assertEquals("Leader executed once before cancel, successor executed once", 2, executionCounter.get())
        assertEquals("All 30 followers must receive elected payload", 30, completedResults.size)
        completedResults.values.forEach { res ->
            assertEquals("elected_follower_payload", res)
        }
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testSingleFlightMultipleDisjointKeysUnderHeavyLoad() = runBlocking {
        val flight = SingleFlight<String, String>()
        val numKeys = 20
        val callersPerKey = 5
        val totalExecutions = AtomicInteger(0)

        val deferreds = (1..numKeys).flatMap { keyIndex ->
            val key = "key_$keyIndex"
            (1..callersPerKey).map {
                async(Dispatchers.IO) {
                    flight.execute(key) {
                        totalExecutions.incrementAndGet()
                        delay(30)
                        "payload_for_$key"
                    }
                }
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(numKeys * callersPerKey, results.size)
        // Exactly 20 upstream executions occurred (1 per distinct key)
        assertEquals(numKeys, totalExecutions.get())
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testSingleFlightUpstreamFailureAllowsImmediateRetry() = runBlocking {
        val flight = SingleFlight<String, String>()
        val failCounter = AtomicInteger(0)

        // Attempt 1 fails
        val resFail = runCatching {
            flight.execute("transient_key") {
                failCounter.incrementAndGet()
                throw IOException("Temporary Network Glitch")
            }
        }
        assertTrue(resFail.isFailure)
        assertEquals(1, failCounter.get())
        assertEquals(0, flight.activeCount)

        // Immediate retry on same key must succeed
        val successCounter = AtomicInteger(0)
        val resSuccess = flight.execute("transient_key") {
            successCounter.incrementAndGet()
            "recovered_value"
        }
        assertEquals("recovered_value", resSuccess)
        assertEquals(1, successCounter.get())
        assertEquals(0, flight.activeCount)
    }

    // ─── 5. CONNECTION MULTIPLEXING & IDEMPOTENT CONNECTION POOL ───────────

    @Test
    fun testNetworkOptimizer50ConcurrentInitsPreservesConnectionPool() {
        val threads = 50
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val errors = AtomicInteger(0)

        for (i in 1..threads) {
            executor.submit {
                try {
                    NetworkOptimizer.initialize()
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All 50 threads must complete initialization within 5s", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(0, errors.get())
        assertTrue(NetworkOptimizer.isOptimized())

        val pool1 = NetworkOptimizer.getConnectionPool()
        assertNotNull(pool1)

        // Calling initialize again must preserve exact same pool
        NetworkOptimizer.initialize()
        val pool2 = NetworkOptimizer.getConnectionPool()
        assertSame("ConnectionPool instance must be strictly identical across 50 concurrent inits", pool1, pool2)
    }

    @Test
    fun test100ConcurrentRequestsSingleKeyContention() = runBlocking {
        val flight = SingleFlight<String, String>()
        val concurrency = 100
        val executionCounter = AtomicInteger(0)

        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                flight.execute("hot_cache_tmdb_tt1234567") {
                    executionCounter.incrementAndGet()
                    delay(80) // Simulate upstream API response time
                    "payload_tt1234567"
                }
            }
        }

        val results = deferreds.awaitAll()
        assertEquals("All 100 callers must receive a response", 100, results.size)
        assertEquals("Upstream block must be executed exactly once across 100 concurrent requests", 1, executionCounter.get())
        results.forEach { assertEquals("payload_tt1234567", it) }
        assertEquals("In-flight registry must be 0 after completion", 0, flight.activeCount)
    }

    @Test
    fun testLeaderCancellationUnder100ConcurrentCallers() = runBlocking {
        val flight = SingleFlight<String, String>()
        val concurrency = 100
        val executionCounter = AtomicInteger(0)
        val startedLatch = CountDownLatch(1)

        val leaderJob = launch(Dispatchers.IO) {
            flight.execute("leader_cancellation_100_key") {
                executionCounter.incrementAndGet()
                startedLatch.countDown()
                delay(500) // Will be cancelled
                "leader_never_reached"
            }
        }

        // Wait until leader has entered execute block
        assertTrue(startedLatch.await(2, TimeUnit.SECONDS))

        // Launch 99 followers while leader is suspended
        val followers = (1 until concurrency).map {
            async(Dispatchers.IO) {
                flight.execute("leader_cancellation_100_key") {
                    executionCounter.incrementAndGet()
                    delay(40)
                    "new_leader_elected_payload"
                }
            }
        }

        // Allow followers to start waiting on leader
        delay(30)

        // Cancel the leader
        leaderJob.cancel()

        // All 99 followers must receive the elected leader's payload without throwing
        val results = followers.awaitAll()
        assertEquals(99, results.size)
        results.forEach { assertEquals("new_leader_elected_payload", it) }

        // Exactly 2 executions: 1 aborted leader + 1 successful elected leader
        assertEquals("Exactly 2 executions: original leader and newly elected leader", 2, executionCounter.get())
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testMultiThreadedCrossPluginSimulation() {
        val plugins = listOf("StreamPlayPlugin", "UltimaPlugin", "StremioAddonProvider", "SuperStreamPlugin")
        val iterations = 100
        val executor = Executors.newFixedThreadPool(16)
        val latch = CountDownLatch(iterations)
        val errors = AtomicInteger(0)

        // Set unoptimized client initially
        val rawClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).build()
        NetworkOptimizer.resetForTesting(rawClient)
        assertFalse(NetworkOptimizer.isAlreadyOptimized(app.baseClient))

        for (i in 1..iterations) {
            val pluginName = plugins[i % plugins.size]
            executor.submit {
                try {
                    // Simulate plugin lifecycle calling initialize
                    NetworkOptimizer.initialize()
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All plugin initialization threads must finish within 5s", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("Zero errors during cross-plugin concurrent initializations", 0, errors.get())
        assertTrue("Client must be optimized after cross-plugin simulation", NetworkOptimizer.isOptimized())

        val finalPool = NetworkOptimizer.getConnectionPool()
        assertNotNull(finalPool)

        // Repeat initialization sequentially for each plugin
        plugins.forEach { _ ->
            NetworkOptimizer.initialize()
            assertSame("Pool instance must be strictly identical across plugins", finalPool, NetworkOptimizer.getConnectionPool())
            assertSame("app.baseClient pool must match", finalPool, app.baseClient.connectionPool)
        }
    }
}

