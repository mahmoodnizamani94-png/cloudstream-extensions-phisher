package com.phisher98

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thread-safe coroutine request coalescer (SingleFlight deduplication engine).
 * Ensures that among concurrent callers requesting the same key, exactly one
 * coroutine executes the upstream network [block], while all other concurrent
 * callers await and share the single result.
 *
 * If the leader coroutine is cancelled before completing the upstream call,
 * leadership is seamlessly transferred to the first waiting follower without
 * triggering a thundering-herd stampede or duplicate executions.
 *
 * State retention is tied to active waiter reference counting: in-flight entries
 * are only evicted once all registered callers have drained, preventing premature
 * eviction and late-waking follower stampedes.
 */
class SingleFlight<K, V> {

    private class Flight<V> {
        val mutex = Mutex()
        @Volatile var result: Result<V>? = null
        val waiters = AtomicInteger(0)
    }

    private val inFlight = ConcurrentHashMap<K, Flight<V>>()

    /**
     * Executes [block] for the specified [key] or shares the in-flight result
     * if another coroutine is already executing for the same [key].
     */
    suspend fun execute(key: K, block: suspend () -> V): V {
        val newFlight = Flight<V>()
        val existing = inFlight.putIfAbsent(key, newFlight)
        val flight = existing ?: newFlight
        flight.waiters.incrementAndGet()

        try {
            // Fast-path: if a concurrent leader already produced a result, return immediately without locking
            flight.result?.let { return it.getOrThrow() }

            flight.mutex.withLock {
                // Double-checked locking: check if another coroutine completed while acquiring lock
                flight.result?.let { return it.getOrThrow() }

                // We are the leader (or newly elected successor)
                return try {
                    val value = block()
                    flight.result = Result.success(value)
                    value
                } catch (ce: CancellationException) {
                    // Leader was cancelled; do NOT set result so the next follower can execute its block
                    throw ce
                } catch (t: Throwable) {
                    // Non-cancellation exception (e.g. IOException, 503): record failure so all waiting followers receive it
                    flight.result = Result.failure(t)
                    throw t
                }
            }
        } finally {
            // Atomic 2-argument remove: only evict when all active waiters have drained to zero
            if (flight.waiters.decrementAndGet() == 0) {
                inFlight.remove(key, flight)
            }
        }
    }

    val activeCount: Int
        get() = inFlight.size

    fun clear() {
        inFlight.clear()
    }

    companion object {
        private val globalFlight = SingleFlight<Any, Any?>()

        /**
         * Convenience helper to coalesce calls using the global single-flight registry.
         */
        @Suppress("UNCHECKED_CAST")
        suspend fun <T> executeShared(key: Any, block: suspend () -> T): T {
            return globalFlight.execute(key) { block() } as T
        }

        fun clearGlobal() {
            globalFlight.clear()
        }
    }
}
