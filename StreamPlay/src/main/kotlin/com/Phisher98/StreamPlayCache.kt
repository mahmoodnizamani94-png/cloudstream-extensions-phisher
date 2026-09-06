package com.phisher98

import android.content.SharedPreferences
import com.lagradost.api.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * State-Of-The-Art Intelligent Caching & Provider Telemetry Engine for StreamPlay
 * - True O(1) LRU bounded caches with TTL expiration (Zero-allocation eviction)
 * - TTL-based API endpoint caching
 * - Thread-safe anime ID mappings
 * - Provider health tracking with half-open circuit breaker and decay scoring
 */
object StreamPlayCache {

    private const val TAG = "StreamPlayCache"

    // ==================== Generic High-Speed LRU Cache with TTL ====================

    open class LruCacheWithTtl<K, V>(
        val maxSize: Int = 256,
        val defaultTtlMillis: Long = 30 * 60 * 1000L
    ) {
        private data class CacheEntry<V>(val value: V, val expiresAt: Long)

        private val map = object : LinkedHashMap<K, CacheEntry<V>>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, CacheEntry<V>>?): Boolean {
                return size > maxSize
            }
        }

        val size: Int
            get() = synchronized(this) {
                cleanExpiredLocked()
                map.size
            }

        private fun cleanExpiredLocked() {
            val now = System.currentTimeMillis()
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now >= entry.value.expiresAt) {
                    iterator.remove()
                }
            }
        }

        fun get(key: K): V? = synchronized(this) {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() >= entry.expiresAt) {
                map.remove(key)
                return null
            }
            return entry.value
        }

        fun put(key: K, value: V, ttlMillis: Long = defaultTtlMillis) = synchronized(this) {
            val now = System.currentTimeMillis()
            val expiresAt = if (ttlMillis >= Long.MAX_VALUE - now) {
                Long.MAX_VALUE
            } else {
                now + ttlMillis
            }
            map[key] = CacheEntry(value, expiresAt)
        }

        fun getOrPut(key: K, ttlMillis: Long = defaultTtlMillis, defaultValue: () -> V): V = synchronized(this) {
            val cached = get(key)
            if (cached != null) return cached
            val computed = defaultValue()
            put(key, computed, ttlMillis)
            return computed
        }

        fun remove(key: K): V? = synchronized(this) {
            return map.remove(key)?.value
        }

        fun clear() = synchronized(this) {
            map.clear()
        }
    }

    // ==================== API Base Caching ====================

    data class ApiCacheEntry(
        val url: String,
        val timestamp: Long,
        val successCount: Int = 0,
        val failureCount: Int = 0
    )

    private var apiCacheEntry: ApiCacheEntry? = null
    private val apiCacheMutex = Mutex()
    private const val API_CACHE_TTL_MS = 10 * 60 * 1000L // 10 minutes
    private const val API_CACHE_SHORT_TTL_MS = 2 * 60 * 1000L // 2 minutes on failures

    /**
     * Get cached API base if still valid
     */
    suspend fun getCachedApiBase(): String? = apiCacheMutex.withLock {
        apiCacheEntry?.let { entry ->
            val age = System.currentTimeMillis() - entry.timestamp
            val ttl = if (entry.failureCount > 0) API_CACHE_SHORT_TTL_MS else API_CACHE_TTL_MS

            if (age < ttl) {
                Log.d(TAG, "✅ Using cached API base: ${entry.url} (age: ${age / 1000}s)")
                entry.url
            } else {
                Log.d(TAG, "⏰ API cache expired (age: ${age / 1000}s, TTL: ${ttl / 1000}s)")
                null
            }
        }
    }

    /**
     * Cache a successful API base
     */
    suspend fun cacheApiBase(url: String, success: Boolean = true) = apiCacheMutex.withLock {
        val current = apiCacheEntry
        apiCacheEntry = if (success) {
            ApiCacheEntry(
                url = url,
                timestamp = System.currentTimeMillis(),
                successCount = (current?.successCount ?: 0) + 1,
                failureCount = 0
            )
        } else {
            ApiCacheEntry(
                url = url,
                timestamp = System.currentTimeMillis(),
                successCount = current?.successCount ?: 0,
                failureCount = (current?.failureCount ?: 0) + 1
            )
        }
        Log.d(TAG, "📦 Cached API base: $url (success: $success)")
    }

    // ==================== Anime ID Caching ====================

    data class AnimeIdMapping(
        val anilistId: String? = null,
        val malId: String? = null,
        val kitsuId: String? = null,
        val zoroId: String? = null,
        val anidbEid: Int = 0,
        val zoroTitle: String? = null,
        val aniXL: String? = null,
        val kaasSlug: String? = null,
        val animepaheUrl: String? = null,
        val animekaiId: String? = null,
        val tmdbYear: Int? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val ANIME_ID_CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val ANIME_ID_CACHE_MAX_SIZE = 500

    private val animeIdCache = LruCacheWithTtl<String, AnimeIdMapping>(
        maxSize = ANIME_ID_CACHE_MAX_SIZE,
        defaultTtlMillis = ANIME_ID_CACHE_TTL_MS
    )

    /**
     * Get cached anime ID mapping
     */
    fun getCachedAnimeIds(key: String): AnimeIdMapping? {
        val mapping = animeIdCache.get(key)
        if (mapping != null) {
            Log.d(TAG, "✅ Anime ID cache hit: $key")
        }
        return mapping
    }

    /**
     * Cache anime ID mapping with O(1) LRU eviction
     */
    fun cacheAnimeIds(key: String, mapping: AnimeIdMapping) {
        animeIdCache.put(key, mapping)
        Log.d(TAG, "📦 Cached anime ID: $key (cache size: ${animeIdCache.size})")
    }

    // ==================== Provider Performance Tracking ====================

    data class ProviderStats(
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val totalTimeMs: Long = 0,
        val lastExecutionMs: Long = 0,
        val consecutiveFailures: Int = 0,
        val lastFailureAtMs: Long = 0L,
        val isCircuitBroken: Boolean = false,
        val isRecovering: Boolean = false
    ) {
        val successRate: Float
            get() = if (successCount + failureCount == 0) 0f
            else successCount.toFloat() / (successCount + failureCount)

        val avgTimeMs: Long
            get() = if (successCount == 0) 0L else totalTimeMs / successCount
    }

    private const val CIRCUIT_BREAKER_COOLDOWN_MS = 15 * 60 * 1000L
    private val loadedPrefs = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /**
     * Get provider statistics
     */
    fun getProviderStats(providerId: String): ProviderStats {
        val stats = ProviderTelemetryManager.getStats(providerId)
        return ProviderStats(
            successCount = stats.successCount,
            failureCount = stats.failureCount,
            totalTimeMs = (stats.latencyEwma * stats.successCount).toLong(),
            lastExecutionMs = stats.lastExecutionMs,
            consecutiveFailures = stats.consecutiveFailures,
            lastFailureAtMs = stats.lastFailureAtMs,
            isCircuitBroken = ProviderTelemetryManager.isCircuitBroken(providerId),
            isRecovering = ProviderTelemetryManager.isRecovering(providerId)
        )
    }

    /**
     * Record provider execution result with atomic compute and EWMA scoring
     */
    fun recordProviderExecution(providerId: String, success: Boolean, durationMs: Long) {
        val wasBroken = ProviderTelemetryManager.isCircuitBroken(providerId)
        ProviderTelemetryManager.recordExecution(providerId, success, durationMs)
        val isBroken = ProviderTelemetryManager.isCircuitBroken(providerId)

        if (isBroken && !wasBroken) {
            Log.w(TAG, "📉 Provider moved to low priority / circuit-broken: $providerId")
        } else if (!isBroken && wasBroken) {
            Log.d(TAG, "✅ Provider recovered: $providerId")
        }
    }

    fun getProviderPriorityScore(providerId: String): Float {
        return ProviderTelemetryManager.getPriorityScore(providerId)
    }

    // ==================== Metadata Caching ====================

    data class MetadataCache(
        val data: String, // JSON string
        val timestamp: Long = System.currentTimeMillis()
    )

    private const val METADATA_CACHE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    private const val METADATA_CACHE_MAX_SIZE = 100

    private val metadataCache = LruCacheWithTtl<String, String>(
        maxSize = METADATA_CACHE_MAX_SIZE,
        defaultTtlMillis = METADATA_CACHE_TTL_MS
    )

    /**
     * Get cached metadata
     */
    fun getCachedMetadata(key: String): String? {
        val data = metadataCache.get(key)
        if (data != null) {
            Log.d(TAG, "✅ Metadata cache hit: $key")
        }
        return data
    }

    /**
     * Cache metadata with O(1) LRU eviction
     */
    fun cacheMetadata(key: String, data: String) {
        metadataCache.put(key, data)
        Log.d(TAG, "📦 Cached metadata: $key (cache size: ${metadataCache.size})")
    }

    // ==================== Persistence ====================

    fun saveProviderStats(prefs: SharedPreferences?) {
        ProviderTelemetryManager.scheduleSave(prefs)
    }

    fun flushProviderStats(prefs: SharedPreferences?) {
        ProviderTelemetryManager.flushSync(prefs)
    }

    fun loadProviderStatsOnce(prefs: SharedPreferences?) {
        if (prefs == null) return
        val identity = System.identityHashCode(prefs)
        if (loadedPrefs.add(identity)) {
            loadProviderStats(prefs)
        }
    }

    /**
     * Load provider stats from SharedPreferences
     */
    fun loadProviderStats(prefs: SharedPreferences?) {
        ProviderTelemetryManager.loadPersistedStats(prefs)
    }

    fun clearAllCachesForTesting() {
        animeIdCache.clear()
        metadataCache.clear()
        ProviderTelemetryManager.clearAllForTesting()
        apiCacheEntry = null
    }
}
