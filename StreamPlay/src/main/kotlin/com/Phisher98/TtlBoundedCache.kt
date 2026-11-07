package com.phisher98

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded TTL cache with frontier eviction semantics, extracted so the policy is pure logic
 * under test (the AniZone episode-player cache uses it):
 * - reads older than [ttlMs] return null (expired entries are lazily ignored, then swept);
 * - when [put] would exceed [maxEntries], expired entries are swept FIRST and, only if the
 *   cache still overflows, the OLDEST entries are trimmed — never a cliff `clear()` that
 *   would throw away every warm entry;
 * - the clock is injectable so TTL boundaries and trimming are deterministically testable;
 * - failures are never stored (callers simply do not put failed resolutions).
 */
class TtlBoundedCache<K : Any, V : Any>(
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val map = ConcurrentHashMap<K, Pair<V, Long>>()

    val size: Int
        get() = map.size

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        return if (clock() - entry.second < ttlMs) entry.first else null
    }

    fun clear() = map.clear()

    fun put(key: K, value: V) {
        if (map.size >= maxEntries) {
            val now = clock()
            map.entries.removeIf { now - it.value.second >= ttlMs }
            if (map.size >= maxEntries) {
                // Trim the oldest entries so the incoming put lands within maxEntries.
                map.entries
                    .sortedBy { it.value.second }
                    .take(map.size - maxEntries + 1)
                    .forEach { map.remove(it.key) }
            }
        }
        map[key] = value to clock()
    }
}
