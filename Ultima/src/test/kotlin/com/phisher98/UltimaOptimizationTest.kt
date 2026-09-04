package com.phisher98

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UltimaOptimizationTest {

    @Test
    fun testLruCacheBasicPutGet() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 10, defaultTtlMillis = 10000L)
        cache.put("key1", "val1")
        assertEquals("val1", cache.get("key1"))
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun testLruCacheGetOrPut() {
        val cache = LruCacheWithTtl<String, Int>(maxSize = 10, defaultTtlMillis = 10000L)
        val counter = AtomicInteger(0)

        val val1 = cache.getOrPut("item") { counter.incrementAndGet() }
        assertEquals(1, val1)

        // Second call should return cached value without invoking provider
        val val2 = cache.getOrPut("item") { counter.incrementAndGet() }
        assertEquals(1, val2)
        assertEquals(1, counter.get())
    }

    @Test
    fun testLruCacheExpiration() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 10, defaultTtlMillis = 60L)
        cache.put("temp", "data", ttlMillis = 50L)
        assertEquals("data", cache.get("temp"))

        Thread.sleep(70L)
        assertNull("Entry should expire after TTL", cache.get("temp"))
    }

    @Test
    fun testLruCacheEvictionEldest() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 3, defaultTtlMillis = 60000L)
        cache.put("k1", "v1")
        cache.put("k2", "v2")
        cache.put("k3", "v3")

        // Access k1 so k2 becomes the eldest
        assertEquals("v1", cache.get("k1"))

        // Add k4, which should evict eldest (k2)
        cache.put("k4", "v4")

        assertNull("k2 should have been evicted", cache.get("k2"))
        assertEquals("v1", cache.get("k1"))
        assertEquals("v3", cache.get("k3"))
        assertEquals("v4", cache.get("k4"))
    }

    @Test
    fun testLruCacheThreadSafety() {
        val cache = LruCacheWithTtl<Int, String>(maxSize = 50, defaultTtlMillis = 60000L)
        val threadCount = 8
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = (t * 100 + i) % 75
                        cache.put(key, "val-$key")
                        val retrieved = cache.get(key)
                        if (retrieved != null) {
                            assertEquals("val-$key", retrieved)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("All concurrent operations completed without deadlock", completed)
        assertTrue("Cache size is within bounds", cache.size <= 50)
    }

    @Test
    fun testGetHostUtility() {
        assertEquals("example.com", getHost("https://example.com/path?arg=1"))
        assertEquals("sub.domain.org", getHost("http://sub.domain.org:8080/test"))
        assertEquals("api.mysite.net", UltimaUtils.getHost("https://api.mysite.net/endpoint"))
        assertNull(getHost("not a valid url"))
        assertNull(getHost(""))
    }

    @Test
    fun testGetEnabledPluginNames() {
        val s1 = UltimaUtils.SectionInfo(name = "Popular", url = "/pop", pluginName = "FlixHQ", enabled = true)
        val s2 = UltimaUtils.SectionInfo(name = "Recent", url = "/rec", pluginName = "FlixHQ", enabled = true)
        val s3 = UltimaUtils.SectionInfo(name = "Anime", url = "/ani", pluginName = "Zoro", enabled = false)
        val s4 = UltimaUtils.SectionInfo(name = "Trending", url = "/tr", pluginName = "Sora", enabled = true)

        val ext1 = UltimaUtils.ExtensionInfo(name = "FlixHQ", sections = arrayOf(s1, s2))
        val ext2 = UltimaUtils.ExtensionInfo(name = "Zoro", sections = arrayOf(s3))
        val ext3 = UltimaUtils.ExtensionInfo(name = "Sora", sections = arrayOf(s4))

        try {
            UltimaStorageManager.setCachedExtensionsForTesting(arrayOf(ext1, ext2, ext3))
            val enabledNames = UltimaStorageManager.getEnabledPluginNames()

            assertEquals(setOf("FlixHQ", "Sora"), enabledNames)
            assertFalse(enabledNames.contains("Zoro"))
        } finally {
            UltimaStorageManager.invalidateCache()
        }
    }

    @Test
    fun testLruCacheClearAndRemove() {
        val cache = UltimaUtils.LruCacheWithTtl<String, String>(maxSize = 10)
        cache.put("k1", "v1")
        cache.put("k2", "v2")
        assertEquals("v1", cache.get("k1"))
        assertEquals("v2", cache.get("k2"))

        val removed = cache.remove("k1")
        assertEquals("v1", removed)
        assertNull(cache.get("k1"))
        assertEquals("v2", cache.get("k2"))

        cache.clear()
        assertNull(cache.get("k2"))
        assertEquals(0, cache.size)
    }

    @Test
    fun testInvalidateCacheResetsMemory() {
        val s = UltimaUtils.SectionInfo(name = "Test", url = "/t", pluginName = "TestPlugin", enabled = true)
        val ext = UltimaUtils.ExtensionInfo(name = "TestPlugin", sections = arrayOf(s))

        UltimaStorageManager.setCachedExtensionsForTesting(arrayOf(ext))
        assertEquals(setOf("TestPlugin"), UltimaStorageManager.getEnabledPluginNames())

        UltimaStorageManager.invalidateCache()
        // Without backend SharedPreferences populated, invalidateCache leaves cachedExtensions null, returning emptyArray
        assertEquals(emptySet<String>(), UltimaStorageManager.getEnabledPluginNames())
    }

    @Test
    fun testLruCacheCoroutineStampedeProtection() = runBlocking {
        val cache = LruCacheWithTtl<String, String>(maxSize = 10, defaultTtlMillis = 10000L)
        val computeCount = AtomicInteger(0)
        val concurrency = 100

        val deferreds = (1..concurrency).map {
            async(Dispatchers.Default) {
                cache.getOrPut("stampedeKey") {
                    val count = computeCount.incrementAndGet()
                    Thread.sleep(25L) // Simulate non-trivial computation/fetch latency
                    "computed-value-$count"
                }
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(concurrency, results.size)
        // All 100 coroutines must receive the exact same computed value
        assertEquals("computed-value-1", results[0])
        results.forEach { assertEquals("computed-value-1", it) }
        // The expensive defaultValue lambda must execute exactly once
        assertEquals(1, computeCount.get())
    }

    @Test
    fun testLruCacheParallelCoroutinesHighContention() = runBlocking {
        val cache = LruCacheWithTtl<Int, String>(maxSize = 25, defaultTtlMillis = 60000L)
        val coroutineCount = 50
        val opsPerCoroutine = 200

        val jobs = (0 until coroutineCount).map { id ->
            async(Dispatchers.IO) {
                for (op in 0 until opsPerCoroutine) {
                    val key = (id * 10 + op) % 50
                    when (op % 5) {
                        0 -> cache.put(key, "val-$key")
                        1 -> cache.get(key)
                        2 -> cache.getOrPut(key) { "generated-$key" }
                        3 -> cache.remove(key)
                        4 -> {
                            val s = cache.size
                            assertTrue("Cache size must never exceed maxSize: $s", s <= 25)
                        }
                    }
                }
            }
        }

        jobs.awaitAll()
        assertTrue("Cache size must respect capacity bound", cache.size <= 25)
    }

    @Test
    fun testLruCacheCapacityBoundStrictEviction() = runBlocking {
        val capacity = 10
        val cache = LruCacheWithTtl<Int, String>(maxSize = capacity, defaultTtlMillis = 60000L)

        // Insert 100 entries sequentially
        for (i in 1..100) {
            cache.put(i, "val-$i")
            assertTrue("Cache size must never exceed capacity ($capacity), got ${cache.size}", cache.size <= capacity)
        }
        assertEquals(capacity, cache.size)

        // Verify that only the most recent 10 items (91..100) remain
        for (i in 1..90) {
            assertNull("Item $i should have been evicted", cache.get(i))
        }
        for (i in 91..100) {
            assertEquals("val-$i", cache.get(i))
        }

        // Test LRU access order retention: access 91, then insert 101
        assertEquals("val-91", cache.get(91))
        cache.put(101, "val-101")
        // 92 was the eldest (least recently accessed), so 92 should be evicted, but 91 should remain
        assertNull("Item 92 should be evicted as least recently used", cache.get(92))
        assertEquals("val-91", cache.get(91))
        assertEquals("val-101", cache.get(101))
    }

    @Test
    fun testLruCacheTtlEvictionCorrectness() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 20, defaultTtlMillis = 5000L)

        // Insert short-lived keys (50ms) and long-lived keys (5000ms)
        for (i in 1..5) {
            cache.put("short-$i", "data-$i", ttlMillis = 50L)
            cache.put("long-$i", "data-$i", ttlMillis = 5000L)
        }

        assertEquals(10, cache.size)

        // Sleep past short TTL
        Thread.sleep(75L)

        // Short-lived keys must expire
        for (i in 1..5) {
            assertNull("Short-lived key short-$i must have expired", cache.get("short-$i"))
        }

        // Long-lived keys must still be valid
        for (i in 1..5) {
            assertEquals("data-$i", cache.get("long-$i"))
        }

        // cache.size must reflect cleanExpiredLocked
        assertEquals(5, cache.size)
    }

    @Test
    fun testLruCacheBoundaryTtlAndOverflow() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 10)

        // Test Long.MAX_VALUE does not overflow into negative timestamp
        cache.put("maxKey", "maxValue", ttlMillis = Long.MAX_VALUE)
        assertEquals("maxValue", cache.get("maxKey"))

        // Test zero TTL expires immediately
        cache.put("zeroKey", "zeroValue", ttlMillis = 0L)
        assertNull("Zero TTL key should be expired immediately", cache.get("zeroKey"))

        // Test negative TTL expires immediately
        cache.put("negKey", "negValue", ttlMillis = -100L)
        assertNull("Negative TTL key should be expired immediately", cache.get("negKey"))
    }

    @Test
    fun testLruCacheExceptionSafetyInGetOrPut() {
        val cache = LruCacheWithTtl<String, String>(maxSize = 10)

        // If defaultValue throws an exception, lock must not be held and cache remains healthy
        try {
            cache.getOrPut("failKey") {
                throw IllegalStateException("Simulated computation failure")
            }
            fail("Should have thrown exception")
        } catch (e: IllegalStateException) {
            assertEquals("Simulated computation failure", e.message)
        }

        // Subsequent operation on the cache should proceed normally without deadlock
        cache.put("healthyKey", "healthyValue")
        assertEquals("healthyValue", cache.get("healthyKey"))

        // Retrying getOrPut for the failed key with a working lambda should succeed
        val recovered = cache.getOrPut("failKey") { "recoveredValue" }
        assertEquals("recoveredValue", recovered)
    }
}
