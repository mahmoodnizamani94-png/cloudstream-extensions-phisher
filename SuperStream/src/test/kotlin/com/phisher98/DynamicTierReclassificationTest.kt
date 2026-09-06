package com.phisher98

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DynamicTierReclassificationTest {

    class TestTimeProvider(var currentTime: Long = 1_000_000L) : TimeProvider {
        override fun currentTimeMillis(): Long = currentTime
        fun advanceTime(ms: Long) { currentTime += ms }
    }

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var circuitBreaker: CircuitBreaker

    @Before
    fun setUp() {
        timeProvider = TestTimeProvider(1_000_000L)
        circuitBreaker = CircuitBreaker(timeProvider = timeProvider)
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(timeProvider)
        ProviderTelemetryManager.setCircuitBreakerForTesting(circuitBreaker)
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        ProviderTelemetryManager.setTimeProviderForTesting(SystemTimeProvider)
        DeviceProfiler.resetForTesting()
    }

    @Test
    fun testProviderPromotionFromTier2ToTier1AndTier0() {
        val providerId = "dynamic_promo_provider"

        // 1. Initial slow execution (2500ms)
        ProviderTelemetryManager.recordExecution(providerId, true, 2500L)
        var tier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("Initial 2500ms execution maps to TIER_2", LatencyTier.TIER_2, tier)

        // 2. Perform multiple fast executions (800ms) to decay EWMA into Tier 1 (< 1500ms)
        repeat(6) {
            ProviderTelemetryManager.recordExecution(providerId, true, 800L)
        }
        val ewmaTier1 = ProviderTelemetryManager.getLatencyEwma(providerId)
        tier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("EWMA decayed to $ewmaTier1 maps to TIER_1", LatencyTier.TIER_1, tier)

        // 3. Perform sub-500ms executions (200ms) to decay EWMA into Tier 0 (< 500ms)
        repeat(12) {
            ProviderTelemetryManager.recordExecution(providerId, true, 200L)
        }
        val ewmaTier0 = ProviderTelemetryManager.getLatencyEwma(providerId)
        tier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("EWMA decayed to $ewmaTier0 maps to TIER_0", LatencyTier.TIER_0, tier)
    }

    @Test
    fun testProviderDemotionFromTier0ToTier2UnderDegradedLatency() {
        val providerId = "dynamic_demo_provider"

        // Establish Tier 0 baseline (300ms)
        repeat(10) {
            ProviderTelemetryManager.recordExecution(providerId, true, 300L)
        }
        var tier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("Fast baseline must be TIER_0", LatencyTier.TIER_0, tier)

        // Degrade with high latency executions (2800ms)
        repeat(8) {
            ProviderTelemetryManager.recordExecution(providerId, true, 2800L)
        }
        val degradedEwma = ProviderTelemetryManager.getLatencyEwma(providerId)
        tier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("Degraded EWMA ($degradedEwma) must demote to TIER_2", LatencyTier.TIER_2, tier)
    }

    @Test
    fun testCircuitBreakerHalfOpenCanaryAssignedToTier3() {
        val providerId = "canary_tier3_provider"

        // Fast operational provider (300ms)
        repeat(5) {
            ProviderTelemetryManager.recordExecution(providerId, true, 300L)
        }
        assertEquals("Operational provider is Tier 0", LatencyTier.TIER_0, SpeculativePipeliner.classifyProvider(providerId))

        // Provider trips circuit breaker with 3 consecutive failures
        repeat(3) {
            ProviderTelemetryManager.recordExecution(providerId, false, 5000L)
        }
        assertEquals(CircuitBreaker.CircuitState.OPEN, circuitBreaker.getState(providerId))
        assertEquals("OPEN provider must be classified as TIER_3", LatencyTier.TIER_3, SpeculativePipeliner.classifyProvider(providerId))

        // Advance past cooldown to enter HALF_OPEN
        timeProvider.advanceTime(circuitBreaker.config.baseCooldownMs + 100L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(providerId))

        // In HALF_OPEN state, provider is canary-recovering: MUST be isolated to TIER_3
        val canaryTier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("Canary in HALF_OPEN must be strictly assigned to TIER_3", LatencyTier.TIER_3, canaryTier)
    }

    @Test
    fun testProviderRecoveryPromotesBackToActiveTier() {
        val providerId = "recovering_provider"

        // Trip to OPEN then advance to HALF_OPEN
        repeat(3) {
            ProviderTelemetryManager.recordExecution(providerId, false, 1200L)
        }
        timeProvider.advanceTime(circuitBreaker.config.baseCooldownMs + 100L)
        assertEquals(CircuitBreaker.CircuitState.HALF_OPEN, circuitBreaker.getState(providerId))

        // Canary probe acquires permit and succeeds with fast latency
        assertTrue(circuitBreaker.canExecute(providerId))
        ProviderTelemetryManager.recordExecution(providerId, true, 350L)

        // Circuit breaker closes
        assertEquals(CircuitBreaker.CircuitState.CLOSED, circuitBreaker.getState(providerId))

        // Provider should now be promoted out of Tier 3 based on successful EWMA
        val recoveredTier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("Recovered provider must not be trapped in TIER_3", LatencyTier.TIER_1, recoveredTier)
    }

    @Test
    fun testConsecutiveFailuresDemoteToTier3() {
        val providerId = "unstable_provider"

        // Baseline fast latency
        repeat(5) {
            ProviderTelemetryManager.recordExecution(providerId, true, 400L)
        }
        assertEquals(LatencyTier.TIER_0, SpeculativePipeliner.classifyProvider(providerId))

        // 2 consecutive failures (before circuit trips at 3)
        ProviderTelemetryManager.recordExecution(providerId, false, 400L)
        ProviderTelemetryManager.recordExecution(providerId, false, 400L)

        // 2 consecutive failures must demote to Tier 3 immediately to prevent user lag
        val demotedTier = SpeculativePipeliner.classifyProvider(providerId)
        assertEquals("2 consecutive failures must demote to TIER_3", LatencyTier.TIER_3, demotedTier)
    }

    @Test
    fun testColdStartInitialTierOrStaticMapping() {
        // Cold start provider with explicit initialTier
        val explicitTier = SpeculativePipeliner.classifyProvider("unseen_provider", LatencyTier.TIER_2)
        assertEquals(LatencyTier.TIER_2, explicitTier)

        // Known fast JSON API in static cold start map
        val staticFastTier = SpeculativePipeliner.classifyProvider("vidsrcxyz")
        assertEquals(LatencyTier.TIER_1, staticFastTier)

        // Known hot cache
        val staticCacheTier = SpeculativePipeliner.classifyProvider("streamplay_cache")
        assertEquals(LatencyTier.TIER_0, staticCacheTier)
    }
}
