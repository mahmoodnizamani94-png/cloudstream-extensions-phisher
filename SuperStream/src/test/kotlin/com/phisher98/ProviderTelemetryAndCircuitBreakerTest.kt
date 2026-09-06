package com.phisher98

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderTelemetryAndCircuitBreakerTest {

    class TestTimeProvider(var currentTime: Long = 1_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    class MockSharedPreferences : SharedPreferences {
        val map = ConcurrentHashMap<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = (map[key] as? Set<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class Editor(private val prefs: MockSharedPreferences) : SharedPreferences.Editor {
            private val tempMap = HashMap<String, Any?>()
            private val removeKeys = HashSet<String>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removeKeys.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) prefs.map.clear()
                for (key in removeKeys) prefs.map.remove(key)
                prefs.map.putAll(tempMap)
            }
        }
    }

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider(1_000_000L)
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setCircuitBreakerForTesting(circuitBreaker)
        ProviderTelemetryManager.setTimeProviderForTesting(timeProvider)
    }

    @org.junit.After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(SystemTimeProvider)
    }

    // ==================== 1. EWMA Latency Mathematical Precision (alpha = 0.25) ====================

    @Test
    fun testEwmaLatencyColdStartInitialization() {
        val provider = "ProvColdStart"
        // 0 executions -> default score 0.0f
        assertEquals(0.0f, ProviderTelemetryManager.getPriorityScore(provider), 0.001f)

        // Run 1: duration = 1200ms -> First observation seeds EWMA directly (no smoothing with 0)
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1200L)
        assertEquals(1200.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)
    }

    @Test
    fun testEwmaLatencyMultiStepRecurrence() {
        val provider = "ProvRecurrence"

        // Step 1: 1000ms -> EWMA = 1000.0
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1000L)
        assertEquals(1000.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)

        // Step 2: 600ms -> 0.25 * 600 + 0.75 * 1000 = 150 + 750 = 900.0
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 600L)
        assertEquals(900.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)

        // Step 3: 1400ms -> 0.25 * 1400 + 0.75 * 900 = 350 + 675 = 1025.0
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1400L)
        assertEquals(1025.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)

        // Step 4: 800ms -> 0.25 * 800 + 0.75 * 1025 = 200 + 768.75 = 968.75
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 800L)
        assertEquals(968.75f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)
    }

    @Test
    fun testEwmaLatencyClampingBoundaries() {
        val provider = "ProvClamping"

        // Duration below MIN_CLAMPED_DURATION_MS (50ms) -> clamped to 50ms
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 10L)
        assertEquals(50.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.001f)

        // Duration above MAX_CLAMPED_DURATION_MS (30,000ms) -> clamped to 30,000ms
        val provider2 = "ProvMaxClamping"
        ProviderTelemetryManager.recordExecution(provider2, success = true, durationMs = 45_000L)
        assertEquals(30_000.0f, ProviderTelemetryManager.getLatencyEwma(provider2), 0.001f)
    }

    @Test
    fun testTransientLatencySpikeDamping() {
        val provider = "ProvSpike"

        // Baseline 5 runs at 500ms
        repeat(5) {
            ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 500L)
        }
        assertEquals(500.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.01f)

        // Transient network spike of 10,000ms
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 10_000L)
        // Expected: 0.25 * 10000 + 0.75 * 500 = 2500 + 375 = 2875.0
        assertEquals(2875.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.01f)

        // Next run returns to 500ms
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 500L)
        // Expected: 0.25 * 500 + 0.75 * 2875 = 125 + 2156.25 = 2281.25
        assertEquals(2281.25f, ProviderTelemetryManager.getLatencyEwma(provider), 0.01f)
    }

    // ==================== 2. EWMA Success Rate Decay & Composite Priority Scoring ====================

    @Test
    fun testSuccessRateDecaySequence() {
        val provider = "ProvDecay"

        // Run 1: Success -> 1.0f
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1000L)
        assertEquals(1.0f, ProviderTelemetryManager.getSuccessRateEwma(provider), 0.0001f)

        // Run 2: Failure -> 0.25 * 0.0 + 0.75 * 1.0 = 0.75f
        ProviderTelemetryManager.recordExecution(provider, success = false, durationMs = 1000L)
        assertEquals(0.75f, ProviderTelemetryManager.getSuccessRateEwma(provider), 0.0001f)

        // Run 3: Failure -> 0.25 * 0.0 + 0.75 * 0.75 = 0.5625f
        ProviderTelemetryManager.recordExecution(provider, success = false, durationMs = 1000L)
        assertEquals(0.5625f, ProviderTelemetryManager.getSuccessRateEwma(provider), 0.0001f)

        // Run 4: Failure -> 0.25 * 0.0 + 0.75 * 0.5625 = 0.421875f
        ProviderTelemetryManager.recordExecution(provider, success = false, durationMs = 1000L)
        assertEquals(0.421875f, ProviderTelemetryManager.getSuccessRateEwma(provider), 0.0001f)

        // Run 5: Recovery Success -> 0.25 * 1.0 + 0.75 * 0.421875 = 0.25 + 0.31640625 = 0.56640625f
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1000L)
        assertEquals(0.56640625f, ProviderTelemetryManager.getSuccessRateEwma(provider), 0.0001f)
    }

    @Test
    fun testRecentFailuresDecayFasterThanSimpleCumulativeAverage() {
        val providerEwma = "ProvEwmaDecay"

        // 10 consecutive successes followed by 3 failures
        repeat(10) {
            ProviderTelemetryManager.recordExecution(providerEwma, success = true, durationMs = 1000L)
        }
        repeat(3) {
            ProviderTelemetryManager.recordExecution(providerEwma, success = false, durationMs = 1000L)
        }

        val ewmaRate = ProviderTelemetryManager.getSuccessRateEwma(providerEwma)
        val simpleAverage = 10.0f / 13.0f // ~0.769 (76.9%)

        // EWMA after 3 failures from 1.0 is 0.75^3 = 0.421875 (42.2%)
        assertTrue("EWMA ($ewmaRate) must decay faster than simple average ($simpleAverage)", ewmaRate < simpleAverage)
        assertEquals(0.421875f, ewmaRate, 0.001f)
    }

    @Test
    fun testCompositePriorityScoringFormulas() {
        val provider = "ProvScoring"

        // 1. Initial success: 1000ms, success rate 1.0
        // Score = (1.0 * 100) - (1000 / 1000) = 100 - 1 = 99.0f
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1000L)
        assertEquals(99.0f, ProviderTelemetryManager.getPriorityScore(provider), 0.01f)

        // 2. Another success of 2000ms:
        // Latency EWMA = 0.25 * 2000 + 0.75 * 1000 = 1250ms
        // Score = (1.0 * 100) - (1250 / 1000) = 100 - 1.25 = 98.75f
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 2000L)
        assertEquals(98.75f, ProviderTelemetryManager.getPriorityScore(provider), 0.01f)

        // 3. Trip circuit breaker to OPEN (3 consecutive failures)
        repeat(3) {
            ProviderTelemetryManager.recordExecution(provider, success = false, durationMs = 2000L)
        }
        assertTrue("Circuit breaker must be OPEN", ProviderTelemetryManager.isCircuitBroken(provider))
        assertEquals(-1000.0f, ProviderTelemetryManager.getPriorityScore(provider), 0.01f)

        // 4. Elapse 15-minute cooldown -> state becomes HALF_OPEN
        timeProvider.advanceTime(15 * 60 * 1000L)
        assertTrue("Provider must be in HALF_OPEN recovery", ProviderTelemetryManager.isRecovering(provider))
        // Specification requirement: -25f for HALF_OPEN canaries
        assertEquals(-25.0f, ProviderTelemetryManager.getPriorityScore(provider), 0.01f)
    }

    // ==================== 3. Error Classification & Isolation ====================

    @Test
    fun testHttp404SoftFailurePreservesLatencyAndDoesNotTripBreaker() {
        val provider = "Prov404"
        ProviderTelemetryManager.recordExecution(provider, success = true, durationMs = 1000L)
        val initialLatency = ProviderTelemetryManager.getLatencyEwma(provider)

        // 5 consecutive HTTP 404 responses
        repeat(5) {
            ProviderTelemetryManager.recordFailure(provider, durationMs = 120L, Exception("HTTP 404 Not Found"))
        }

        // 404 is content-missing, not scraper-broken: must NOT trip breaker!
        assertFalse("HTTP 404 must not trip circuit breaker", ProviderTelemetryManager.isCircuitBroken(provider))
        assertEquals("Latency must be preserved on 404", initialLatency, ProviderTelemetryManager.getLatencyEwma(provider), 0.01f)
    }

    @Test
    fun testNetworkTimeoutClampedAndTripsCircuitBreaker() {
        val provider = "ProvTimeout"

        // 3 consecutive socket timeouts
        repeat(3) {
            ProviderTelemetryManager.recordFailure(provider, durationMs = 25_000L, SocketTimeoutException("Read timed out"))
        }

        assertTrue("Network timeouts must trip circuit breaker to OPEN", ProviderTelemetryManager.isCircuitBroken(provider))
        // Recorded timeout duration clamped to MAX_TIMEOUT_RECORDED_MS (15,000L)
        assertEquals(15_000.0f, ProviderTelemetryManager.getLatencyEwma(provider), 0.01f)
    }

    @Test
    fun testCancellationExceptionBypassesRecordingAndReleasesCanary() {
        val provider = "ProvCancelled"
        repeat(2) { ProviderTelemetryManager.recordExecution(provider, success = false, durationMs = 1000L) }
        val beforeFailures = ProviderTelemetryManager.getStats(provider).consecutiveFailures

        // CancellationException during user early satisfaction
        ProviderTelemetryManager.recordFailure(provider, durationMs = 500L, CancellationException("Early playback"))

        val afterFailures = ProviderTelemetryManager.getStats(provider).consecutiveFailures
        assertEquals("Cancellation must not increment failure counter", beforeFailures, afterFailures)
        assertFalse("Provider must remain CLOSED", ProviderTelemetryManager.isCircuitBroken(provider))
    }

    // ==================== 4. Single-Key Persistence & Bounded LRU Eviction ====================

    @Test
    fun testSingleKeySerializationAndDeserializationFidelity() {
        val mockPrefs = MockSharedPreferences()

        // Configure 3 providers with distinct states
        ProviderTelemetryManager.recordExecution("prov_alpha", success = true, durationMs = 800L)

        repeat(3) {
            ProviderTelemetryManager.recordExecution("prov_bravo", success = false, durationMs = 5000L)
        }

        ProviderTelemetryManager.recordExecution("prov_charlie", success = true, durationMs = 1500L)

        // Force flush to SharedPreferences
        ProviderTelemetryManager.flushSync(mockPrefs)

        // Verify single-key consolidation
        assertEquals("Exactly one master key must exist in SharedPreferences", 1, mockPrefs.all.size)
        assertTrue("Master key SUPERSTREAM_TELEMETRY_V2 must exist", mockPrefs.contains("SUPERSTREAM_TELEMETRY_V2"))

        val rawJson = mockPrefs.getString("SUPERSTREAM_TELEMETRY_V2", null)
        assertNotNull(rawJson)
        assertTrue(rawJson!!.contains("\"v\":2"))
        assertTrue(rawJson.contains("prov_alpha"))
        assertTrue(rawJson.contains("prov_bravo"))
        assertTrue(rawJson.contains("prov_charlie"))

        // Clear in-memory state
        ProviderTelemetryManager.clearAllForTesting()
        assertEquals(0.0f, ProviderTelemetryManager.getPriorityScore("prov_alpha"), 0.01f)

        // Restore from SharedPreferences
        ProviderTelemetryManager.loadPersistedStats(mockPrefs)

        // Assert fidelity
        assertEquals(800.0f, ProviderTelemetryManager.getLatencyEwma("prov_alpha"), 0.1f)
        assertTrue(ProviderTelemetryManager.isCircuitBroken("prov_bravo"))
        assertEquals(-1000.0f, ProviderTelemetryManager.getPriorityScore("prov_bravo"), 0.01f)
        assertEquals(1500.0f, ProviderTelemetryManager.getLatencyEwma("prov_charlie"), 0.1f)
    }

    @Test
    fun testBoundedLruEvictionAtMax100Entries() {
        // Insert 150 distinct providers
        for (i in 1..150) {
            ProviderTelemetryManager.recordExecution("provider_$i", success = true, durationMs = 500L)
        }

        // Must strictly clamp to MAX_TRACKED_PROVIDERS (100)
        assertEquals(100, ProviderTelemetryManager.getTrackedProvidersCount())

        // Oldest 50 (provider_1 .. provider_50) must be evicted
        for (i in 1..50) {
            assertEquals("Evicted provider must have default 0 score", 0.0f, ProviderTelemetryManager.getPriorityScore("provider_$i"), 0.01f)
        }

        // Newest 100 (provider_51 .. provider_150) must remain
        for (i in 51..150) {
            assertTrue("Recent provider must be present in cache", ProviderTelemetryManager.getPriorityScore("provider_$i") > 0f)
        }
    }

    @Test
    fun testDirtyKeyTrackingAndPersistenceThrottling() {
        val mockPrefs = MockSharedPreferences()

        // 1. Initial state has no dirty keys
        assertTrue(ProviderTelemetryManager.getDirtyKeys().isEmpty())

        // 2. Record execution marks key dirty
        ProviderTelemetryManager.recordExecution("prov_dirty", success = true, durationMs = 600L)
        assertTrue(ProviderTelemetryManager.isDirty)
        assertTrue(ProviderTelemetryManager.isKeyDirty("prov_dirty"))

        // 3. scheduleSave flushes to disk when enough time has elapsed
        ProviderTelemetryManager.scheduleSave(mockPrefs)
        // After flushSync, dirty keys are cleared
        assertFalse(ProviderTelemetryManager.isDirty)
        assertTrue(ProviderTelemetryManager.getDirtyKeys().isEmpty())
        assertTrue(mockPrefs.contains("SUPERSTREAM_TELEMETRY_V2"))
    }

    @Test
    fun testLegacySharedPreferencesMigrationAndCleanup() {
        val mockPrefs = MockSharedPreferences()
        mockPrefs.edit()
            .putString("provider_stats_vidsrc", "10,2,10000,0,0,1000")
            .putString("provider_stats_dead", "0,5,25000,5,1000000,5000")
            .apply()

        assertEquals(2, mockPrefs.all.size)

        // Trigger migration
        ProviderTelemetryManager.loadPersistedStats(mockPrefs)

        // Legacy keys must be purged from SharedPreferences
        assertFalse(mockPrefs.contains("provider_stats_vidsrc"))
        assertFalse(mockPrefs.contains("provider_stats_dead"))
        assertTrue(mockPrefs.contains("SUPERSTREAM_TELEMETRY_V2"))

        // Restored states must reflect migrated data
        val vidsrcStats = ProviderTelemetryManager.getStats("vidsrc")
        assertEquals(10, vidsrcStats.successCount)
        assertEquals(2, vidsrcStats.failureCount)
        assertEquals(1000.0f, vidsrcStats.latencyEwma, 0.1f)

        assertTrue(ProviderTelemetryManager.isCircuitBroken("dead"))
    }

    // ==================== 5. Concurrency & Stress Testing ====================

    @Test
    fun testHighConcurrencyMultiThreadedRecordingStress() {
        val threadCount = 20
        val operationsPerThread = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val providers = (1..10).map { "SharedProv_$it" }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (op in 0 until operationsPerThread) {
                        val prov = providers[op % providers.size]
                        val success = op % 5 != 0 // 80% success rate
                        val duration = (100L + (op % 10) * 100L)
                        ProviderTelemetryManager.recordExecution(prov, success, duration)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads must finish within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Total operations across all providers must equal threadCount * operationsPerThread
        val totalExecutions = providers.sumOf { ProviderTelemetryManager.getStats(it).executionCount }
        assertEquals(threadCount * operationsPerThread, totalExecutions)

        // All providers must have valid EWMA values
        for (prov in providers) {
            val stats = ProviderTelemetryManager.getStats(prov)
            assertTrue("EWMA latency must be positive", stats.latencyEwma > 0f)
            assertTrue("EWMA success rate must be in [0, 1]", stats.successRateEwma in 0.0f..1.0f)
        }
    }
}
