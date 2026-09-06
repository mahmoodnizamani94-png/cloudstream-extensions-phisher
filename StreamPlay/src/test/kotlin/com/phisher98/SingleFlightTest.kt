package com.phisher98

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {

    private lateinit var flight: SingleFlight<String, String>

    @Before
    fun setUp() {
        flight = SingleFlight()
        SingleFlight.clearGlobal()
    }

    @After
    fun tearDown() {
        flight.clear()
        SingleFlight.clearGlobal()
    }

    @Test
    fun test50ConcurrentRequestsCoalesceToSingleExecution() = runBlocking {
        val executionCounter = AtomicInteger(0)
        val concurrency = 50

        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                flight.execute("shared_metadata_key") {
                    executionCounter.incrementAndGet()
                    delay(50) // Simulate upstream network latency
                    "tmdb_media_payload_12345"
                }
            }
        }

        val results = deferreds.awaitAll()

        assertEquals("All callers must receive a result", concurrency, results.size)
        // CRITICAL PROOF: Exactly 1 upstream network execution occurred across 50 concurrent calls
        assertEquals("Upstream block must be executed exactly once", 1, executionCounter.get())
        results.forEach { result ->
            assertEquals("tmdb_media_payload_12345", result)
        }
        assertEquals("In-flight map must be empty after all finish", 0, flight.activeCount)
    }

    @Test
    fun testIndependentKeysExecuteInParallel() = runBlocking {
        val counterA = AtomicInteger(0)
        val counterB = AtomicInteger(0)
        val counterC = AtomicInteger(0)

        val jobA = async(Dispatchers.IO) {
            flight.execute("keyA") {
                counterA.incrementAndGet()
                delay(30)
                "resA"
            }
        }
        val jobB = async(Dispatchers.IO) {
            flight.execute("keyB") {
                counterB.incrementAndGet()
                delay(30)
                "resB"
            }
        }
        val jobC = async(Dispatchers.IO) {
            flight.execute("keyC") {
                counterC.incrementAndGet()
                delay(30)
                "resC"
            }
        }

        val results = listOf(jobA, jobB, jobC).awaitAll()
        assertEquals(listOf("resA", "resB", "resC"), results)
        assertEquals(1, counterA.get())
        assertEquals(1, counterB.get())
        assertEquals(1, counterC.get())
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testSequentialRequestsExecuteAnewAfterCompletion() = runBlocking {
        val counter = AtomicInteger(0)

        val first = flight.execute("reusableKey") {
            counter.incrementAndGet()
            "first_result"
        }
        assertEquals("first_result", first)
        assertEquals(1, counter.get())

        // Second call happens after first is complete -> must invoke block again (not cached)
        val second = flight.execute("reusableKey") {
            counter.incrementAndGet()
            "second_result"
        }
        assertEquals("second_result", second)
        assertEquals(2, counter.get())
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testExceptionPropagationToAllWaitingCallers() = runBlocking {
        val counter = AtomicInteger(0)
        val concurrency = 25

        val deferreds = (1..concurrency).map {
            async(Dispatchers.IO) {
                runCatching {
                    flight.execute("failing_upstream_key") {
                        counter.incrementAndGet()
                        delay(40)
                        throw IOException("HTTP 503 Upstream TMDB Unavailable")
                    }
                }
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(concurrency, results.size)
        // Upstream was hit only once
        assertEquals(1, counter.get())

        // All 25 callers received the exact IOException
        results.forEach { res ->
            assertTrue(res.isFailure)
            val exception = res.exceptionOrNull()
            assertTrue(exception is IOException)
            assertEquals("HTTP 503 Upstream TMDB Unavailable", exception?.message)
        }

        // Key is removed from inFlight; subsequent call can retry cleanly
        assertEquals(0, flight.activeCount)
        val recovery = flight.execute("failing_upstream_key") { "recovered_value" }
        assertEquals("recovered_value", recovery)
    }

    @Test
    fun testLeaderCancellationAllowsActiveFollowerToRecover() = runBlocking {
        val counter = AtomicInteger(0)

        val leaderJob = launch(Dispatchers.IO) {
            flight.execute("leader_cancel_key") {
                counter.incrementAndGet()
                delay(200) // Will be cancelled while in delay
                "leader_result"
            }
        }

        delay(30) // Ensure leader has registered

        val followerJob = async(Dispatchers.IO) {
            flight.execute("leader_cancel_key") {
                counter.incrementAndGet()
                "follower_elected_result"
            }
        }

        delay(30)
        leaderJob.cancel() // Cancel the leader

        val followerResult = followerJob.await()
        assertEquals("follower_elected_result", followerResult)
        assertEquals(2, counter.get())
        assertEquals(0, flight.activeCount)
    }

    @Test
    fun testGlobalSingleFlightHelper() = runBlocking {
        val counter = AtomicInteger(0)
        val deferreds = (1..30).map {
            async(Dispatchers.IO) {
                SingleFlight.executeShared("global_cinemeta_tt0944947") {
                    counter.incrementAndGet()
                    delay(30)
                    12345
                }
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(30, results.size)
        assertEquals(1, counter.get())
        results.forEach { assertEquals(12345, it) }
    }
}
