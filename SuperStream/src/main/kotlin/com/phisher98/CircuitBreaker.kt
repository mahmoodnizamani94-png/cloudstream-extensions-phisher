package com.phisher98

import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * State-Of-The-Art Self-Healing Circuit Breaker Engine
 *
 * Implements a high-throughput, lock-free 3-state Finite State Machine (FSM):
 * - CLOSED: Normal operation. Scrapers execute freely.
 * - OPEN: Fast-fail isolation. Disables broken providers immediately (0ms latency).
 * - HALF-OPEN: Non-blocking single-flight canary probing to test provider recovery.
 *
 * Features:
 * - Exponential backoff cooldown: 15m -> 30m -> 60m -> clamped 120m (2 hours).
 * - Automatic reset to base 15m cooldown upon successful canary recovery.
 * - Single-flight canary admission: eliminates the thundering herd problem.
 * - Watchdog lease expiration preventing permanent HALF-OPEN deadlocks.
 * - Coroutine cancellation awareness: prevents false trips during early satisfaction aborts.
 * - Zero-allocation fast path via AtomicReference CAS state transitions.
 * - Pluggable TimeProvider for 100% deterministic, instant unit testing.
 */
class CircuitBreaker(
    val config: CircuitBreakerConfig = CircuitBreakerConfig(),
    val timeProvider: TimeProvider = SystemTimeProvider
) {

    companion object {
        private const val TAG = "CircuitBreaker"

        // Default global instance for cross-plugin convenience
        val defaultInstance by lazy { CircuitBreaker() }
    }

    // ==================== Configuration & Data Structures ====================

    data class CircuitBreakerConfig(
        val failureThreshold: Int = 3,
        val minSampleRequests: Int = 5,
        val errorRateThreshold: Float = 0.60f, // 60% failure rate triggers trip
        val baseCooldownMs: Long = 15 * 60 * 1000L, // 15 minutes
        val maxCooldownMs: Long = 120 * 60 * 1000L, // 2 hours clamped
        val canaryLeaseTimeoutMs: Long = 30 * 1000L, // 30s watchdog
        val canaryExecutionTimeoutMs: Long = 6 * 1000L // Clamped timeout for canary probe
    )

    enum class CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    /**
     * Immutable snapshot of an individual provider's circuit state.
     * All state transitions produce a new immutable instance swapped via CAS.
     */
    data class CircuitSnapshot(
        val state: CircuitState = CircuitState.CLOSED,
        val consecutiveFailures: Int = 0,
        val tripCount: Int = 0,
        val lastTripTimestampMs: Long = 0L,
        val canaryInFlight: Boolean = false,
        val canaryStartedAtMs: Long = 0L,
        val totalRequests: Long = 0L,
        val totalFailures: Long = 0L,
        val lastExecutionDurationMs: Long = 0L
    ) {
        val failureRate: Float
            get() = if (totalRequests == 0L) 0f else totalFailures.toFloat() / totalRequests

        /**
         * Resolves the effective state based on the current timestamp without mutating state.
         */
        fun resolveEffectiveState(now: Long, config: CircuitBreakerConfig): CircuitState {
            if (state == CircuitState.OPEN) {
                val cooldown = calculateCooldownMs(tripCount, config)
                if (now - lastTripTimestampMs >= cooldown) {
                    return CircuitState.HALF_OPEN
                }
            }
            return state
        }

        fun calculateCooldownRemainingMs(now: Long, config: CircuitBreakerConfig): Long {
            if (state != CircuitState.OPEN) return 0L
            val cooldown = calculateCooldownMs(tripCount, config)
            val elapsed = now - lastTripTimestampMs
            return (cooldown - elapsed).coerceAtLeast(0L)
        }
    }

    // Provider ID -> AtomicReference<CircuitSnapshot>
    private val circuits = ConcurrentHashMap<String, AtomicReference<CircuitSnapshot>>()

    private fun getCircuitRef(providerId: String): AtomicReference<CircuitSnapshot> {
        return circuits.computeIfAbsent(providerId) {
            AtomicReference(CircuitSnapshot())
        }
    }

    // ==================== Public FSM & Execution Queries ====================

    /**
     * Fast-path check: returns true if provider is permitted to execute.
     * - In CLOSED: returns true.
     * - In OPEN: checks if cooldown elapsed. If not, returns false (0ms fast-fail).
     * - In HALF-OPEN: atomically attempts to acquire the exclusive single-flight canary permit.
     *   If acquired, returns true; all concurrent callers receive false (no thundering herd).
     */
    fun canExecute(providerId: String): Boolean {
        val ref = getCircuitRef(providerId)
        while (true) {
            val current = ref.get()
            val now = timeProvider.currentTimeMillis()
            val effectiveState = current.resolveEffectiveState(now, config)

            when (effectiveState) {
                CircuitState.CLOSED -> {
                    // If transitioning back or normal closed
                    if (current.state != CircuitState.CLOSED) {
                        val updated = current.copy(state = CircuitState.CLOSED, canaryInFlight = false)
                        if (ref.compareAndSet(current, updated)) return true
                        continue
                    }
                    return true
                }
                CircuitState.OPEN -> {
                    // Cooldown has not elapsed; fast-fail immediately
                    return false
                }
                CircuitState.HALF_OPEN -> {
                    // Check watchdog lease expiration
                    val leaseExpired = current.canaryInFlight &&
                        (now - current.canaryStartedAtMs >= config.canaryLeaseTimeoutMs)

                    if (current.canaryInFlight && !leaseExpired) {
                        // Canary is actively in-flight; reject concurrent caller to prevent thundering herd
                        return false
                    }

                    // Attempt to acquire exclusive single-flight canary permit via CAS
                    val updated = current.copy(
                        state = CircuitState.HALF_OPEN,
                        canaryInFlight = true,
                        canaryStartedAtMs = now
                    )
                    if (ref.compareAndSet(current, updated)) {
                        Log.d(TAG, "Canary permit acquired for provider: $providerId (Trip count: ${current.tripCount})")
                        return true
                    }
                    // CAS contended; retry loop
                }
            }
        }
    }

    /**
     * Checks if a provider is currently executing in half-open canary mode.
     */
    fun isCanaryActive(providerId: String): Boolean {
        val snapshot = circuits[providerId]?.get() ?: return false
        val now = timeProvider.currentTimeMillis()
        val effective = snapshot.resolveEffectiveState(now, config)
        return effective == CircuitState.HALF_OPEN && snapshot.canaryInFlight
    }

    /**
     * Explicitly attempts to acquire canary permit without executing regular calls.
     */
    fun tryAcquireCanary(providerId: String): Boolean {
        val ref = getCircuitRef(providerId)
        val now = timeProvider.currentTimeMillis()
        while (true) {
            val current = ref.get()
            val effective = current.resolveEffectiveState(now, config)
            if (effective != CircuitState.HALF_OPEN) return false

            val leaseExpired = current.canaryInFlight &&
                (now - current.canaryStartedAtMs >= config.canaryLeaseTimeoutMs)
            if (current.canaryInFlight && !leaseExpired) return false

            val updated = current.copy(
                state = CircuitState.HALF_OPEN,
                canaryInFlight = true,
                canaryStartedAtMs = now
            )
            if (ref.compareAndSet(current, updated)) return true
        }
    }

    /**
     * Releases the canary permit in case of coroutine cancellation, preventing orphaned locks.
     */
    fun releaseCanaryPermit(providerId: String) {
        val ref = circuits[providerId] ?: return
        while (true) {
            val current = ref.get()
            if (!current.canaryInFlight) return
            val updated = current.copy(canaryInFlight = false, canaryStartedAtMs = 0L)
            if (ref.compareAndSet(current, updated)) {
                Log.d(TAG, "Released canary permit for provider: $providerId")
                return
            }
        }
    }

    // ==================== Outcome Recording ====================

    /**
     * Records a successful execution.
     * In HALF-OPEN: triggers recovery to CLOSED, resets tripCount to 0, resets cooldown to base 15m.
     * In CLOSED: resets consecutiveFailures to 0.
     */
    fun recordSuccess(providerId: String, durationMs: Long) {
        val ref = getCircuitRef(providerId)
        while (true) {
            val current = ref.get()
            val now = timeProvider.currentTimeMillis()
            val wasHalfOpen = current.state == CircuitState.HALF_OPEN ||
                (current.state == CircuitState.OPEN && now - current.lastTripTimestampMs >= calculateCooldownMs(current.tripCount, config))

            val updated = current.copy(
                state = CircuitState.CLOSED,
                consecutiveFailures = 0,
                tripCount = 0, // Reset exponential backoff on recovery!
                lastTripTimestampMs = 0L,
                canaryInFlight = false,
                canaryStartedAtMs = 0L,
                totalRequests = if (wasHalfOpen) 1L else current.totalRequests + 1,
                totalFailures = if (wasHalfOpen) 0L else current.totalFailures,
                lastExecutionDurationMs = durationMs
            )

            if (ref.compareAndSet(current, updated)) {
                if (wasHalfOpen) {
                    Log.d(TAG, "Provider RECOVERED to CLOSED: $providerId (Cooldown reset to base ${config.baseCooldownMs / 60000}m)")
                }
                return
            }
        }
    }

    /**
     * Records a failure (network timeout, HTTP 5xx, or empty/dead scraper error).
     * In CLOSED: increments consecutive failures. Trips to OPEN if threshold exceeded.
     * In HALF-OPEN: increments trip count, doubles exponential cooldown, transitions back to OPEN.
     */
    fun recordFailure(providerId: String, durationMs: Long, error: Throwable? = null) {
        val ref = getCircuitRef(providerId)
        val now = timeProvider.currentTimeMillis()

        while (true) {
            val current = ref.get()
            val effective = current.resolveEffectiveState(now, config)

            val newConsecutiveFailures = current.consecutiveFailures + 1
            val newTotalRequests = current.totalRequests + 1
            val newTotalFailures = current.totalFailures + 1
            val newFailureRate = newTotalFailures.toFloat() / newTotalRequests

            val shouldTrip = when (effective) {
                CircuitState.CLOSED -> {
                    newConsecutiveFailures >= config.failureThreshold ||
                        (newTotalRequests >= config.minSampleRequests && newFailureRate >= config.errorRateThreshold)
                }
                CircuitState.HALF_OPEN -> {
                    // Any failure during canary probe trips immediately back to OPEN
                    true
                }
                CircuitState.OPEN -> {
                    true
                }
            }

            val nextTripCount = if (shouldTrip) {
                if (effective == CircuitState.HALF_OPEN || effective == CircuitState.OPEN) {
                    current.tripCount + 1
                } else {
                    1 // Initial trip from CLOSED
                }
            } else {
                current.tripCount
            }

            val nextState = if (shouldTrip) CircuitState.OPEN else CircuitState.CLOSED
            val nextTripTimestamp = if (shouldTrip) now else current.lastTripTimestampMs

            val updated = current.copy(
                state = nextState,
                consecutiveFailures = newConsecutiveFailures,
                tripCount = nextTripCount,
                lastTripTimestampMs = nextTripTimestamp,
                canaryInFlight = false,
                canaryStartedAtMs = 0L,
                totalRequests = newTotalRequests,
                totalFailures = newTotalFailures,
                lastExecutionDurationMs = durationMs
            )

            if (ref.compareAndSet(current, updated)) {
                if (shouldTrip) {
                    val cooldownMs = calculateCooldownMs(nextTripCount, config)
                    Log.w(
                        TAG,
                        "Circuit TRIPPED to OPEN for $providerId: tripCount=$nextTripCount, " +
                            "consecutiveFailures=$newConsecutiveFailures, cooldown=${cooldownMs / 60000}m " +
                            "(${cooldownMs}ms). Error: ${error?.message ?: "timeout/failure"}"
                    )
                }
                return
            }
        }
    }

    // ==================== Safe Coroutine Execution Wrapper ====================

    /**
     * Executes the suspending [block] protected by the circuit breaker.
     * - Returns null if the circuit is OPEN or if another canary is already in flight.
     * - Safely releases canary permit on coroutine cancellation without counting as failure.
     * - Records success or failure automatically upon completion.
     */
    suspend fun <T> executeWithBreaker(
        providerId: String,
        block: suspend () -> T
    ): Result<T>? {
        if (!canExecute(providerId)) {
            return null // Fast-fail or throttled canary
        }

        val startTime = timeProvider.currentTimeMillis()
        return try {
            val result = block()
            val duration = timeProvider.currentTimeMillis() - startTime
            recordSuccess(providerId, duration)
            Result.success(result)
        } catch (ce: CancellationException) {
            // Coroutine cancelled by parent supervisor (e.g. early satisfaction)
            // MUST release canary permit without treating as a provider failure!
            releaseCanaryPermit(providerId)
            throw ce
        } catch (t: Throwable) {
            val duration = timeProvider.currentTimeMillis() - startTime
            recordFailure(providerId, duration, t)
            Result.failure(t)
        }
    }

    // ==================== Inspection & Helper Methods ====================

    fun getState(providerId: String): CircuitState {
        val snapshot = circuits[providerId]?.get() ?: return CircuitState.CLOSED
        return snapshot.resolveEffectiveState(timeProvider.currentTimeMillis(), config)
    }

    fun getConsecutiveFailures(providerId: String): Int =
        circuits[providerId]?.get()?.consecutiveFailures ?: 0

    fun getTripCount(providerId: String): Int =
        circuits[providerId]?.get()?.tripCount ?: 0

    fun getCooldownRemainingMs(providerId: String): Long {
        val snapshot = circuits[providerId]?.get() ?: return 0L
        return snapshot.calculateCooldownRemainingMs(timeProvider.currentTimeMillis(), config)
    }

    /**
     * Returns optimal timeout budget for this provider.
     * Canary probes are clamped to canaryExecutionTimeoutMs (6s) so they never stall user playback.
     */
    fun getExecutionTimeoutMs(providerId: String, defaultTimeoutMs: Long = 20_000L): Long {
        return when (getState(providerId)) {
            CircuitState.OPEN -> 0L
            CircuitState.HALF_OPEN -> config.canaryExecutionTimeoutMs
            CircuitState.CLOSED -> defaultTimeoutMs
        }
    }

    fun reset(providerId: String) {
        circuits[providerId]?.set(CircuitSnapshot())
        Log.d(TAG, "Circuit reset for provider: $providerId")
    }

    fun resetAll() {
        circuits.clear()
        Log.d(TAG, "All provider circuits reset")
    }

    // ==================== Persistence Hooks ====================

    fun getSnapshot(providerId: String): CircuitSnapshot {
        return circuits[providerId]?.get() ?: CircuitSnapshot()
    }

    fun getAllSnapshots(): Map<String, CircuitSnapshot> {
        return circuits.mapValues { it.value.get() }
    }

    fun restoreSnapshot(providerId: String, snapshot: CircuitSnapshot) {
        // Guard: never restore a persisted snapshot with canaryInFlight = true
        val sanitized = snapshot.copy(canaryInFlight = false, canaryStartedAtMs = 0L)
        getCircuitRef(providerId).set(sanitized)
    }
}

// ==================== Mathematical Utilities & Time Abstraction ====================

/**
 * Calculates exponential backoff cooldown:
 * Base 15m -> 30m -> 60m -> clamped 120m (2 hours).
 */
fun calculateCooldownMs(tripCount: Int, config: CircuitBreaker.CircuitBreakerConfig): Long {
    if (tripCount <= 1) return config.baseCooldownMs
    val shift = (tripCount - 1).coerceIn(0, 30)
    val multiplier = 1L shl shift
    val candidate = try {
        Math.multiplyExact(config.baseCooldownMs, multiplier)
    } catch (_: ArithmeticException) {
        config.maxCooldownMs
    }
    return minOf(candidate, config.maxCooldownMs)
}

/**
 * Pluggable time interface for deterministic testing without Thread.sleep()
 */
interface TimeProvider {
    fun currentTimeMillis(): Long
}

object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
