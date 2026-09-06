package com.phisher98

import com.lagradost.cloudstream3.app
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkOptimizerTest {

    private var originalClient: OkHttpClient? = null

    @Before
    fun setUp() {
        originalClient = app.baseClient
        NetworkOptimizer.resetForTesting(originalClient)
    }

    @After
    fun tearDown() {
        originalClient?.let { NetworkOptimizer.resetForTesting(it) }
    }

    @Test
    fun testNetworkOptimizerInitializationAppliesSettings() {
        // Confirm before initialization
        val unoptimizedClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        NetworkOptimizer.resetForTesting(unoptimizedClient)

        assertFalse(NetworkOptimizer.isAlreadyOptimized(app.baseClient))

        // Execute NetworkOptimizer initialization
        NetworkOptimizer.initialize()

        // Assert client is marked and optimized
        assertTrue(NetworkOptimizer.isOptimized())
        assertTrue(NetworkOptimizer.isAlreadyOptimized(app.baseClient))

        // Assert dispatcher tuning (128 concurrent, 32 per host)
        assertEquals(128, app.baseClient.dispatcher.maxRequests)
        assertEquals(32, app.baseClient.dispatcher.maxRequestsPerHost)

        // Assert socket timeout tuning (6s connect, 10s read, 10s write)
        assertEquals(6000, app.baseClient.connectTimeoutMillis)
        assertEquals(10000, app.baseClient.readTimeoutMillis)
        assertEquals(10000, app.baseClient.writeTimeoutMillis)

        // Assert DNS resolution is routed through OptimizedDns
        assertTrue(app.baseClient.dns.javaClass.name.contains("OptimizedDns"))

        // Assert marker interceptor is present
        assertTrue(app.baseClient.interceptors.any { it.javaClass.name.endsWith("NetworkOptimizerMarker") })
    }

    @Test
    fun testIdempotencePreservesConnectionPoolAcrossMultipleInits() {
        NetworkOptimizer.initialize()
        val firstPool = NetworkOptimizer.getConnectionPool()
        assertNotNull(firstPool)

        // Simulate subsequent plugin initializations (e.g. StreamPlay, then Ultima, then StremioAddon)
        NetworkOptimizer.initialize()
        val secondPool = NetworkOptimizer.getConnectionPool()

        NetworkOptimizer.initialize()
        val thirdPool = NetworkOptimizer.getConnectionPool()

        // STRICT IDENTITY ASSERTION: The connection pool instance MUST NOT be replaced
        assertSame("ConnectionPool instance must be strictly preserved across repeated initialize() calls", firstPool, secondPool)
        assertSame("ConnectionPool instance must be strictly preserved across repeated initialize() calls", firstPool, thirdPool)
        assertSame("app.baseClient.connectionPool must match NetworkOptimizer.getConnectionPool()", firstPool, app.baseClient.connectionPool)
    }

    private class CustomTestInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return chain.proceed(chain.request())
        }
    }

    @Test
    fun testPreservesPreExistingInterceptors() {
        val clientWithCustom = OkHttpClient.Builder()
            .addInterceptor(CustomTestInterceptor())
            .build()

        NetworkOptimizer.resetForTesting(clientWithCustom)
        NetworkOptimizer.initialize()

        // Verify that CustomTestInterceptor is still present along with NetworkOptimizerMarker
        val interceptors = app.baseClient.interceptors
        assertTrue("Pre-existing custom interceptors must be preserved", interceptors.any { it is CustomTestInterceptor })
        assertTrue("NetworkOptimizerMarker must be added", interceptors.any { it is NetworkOptimizerMarker })
    }

    @Test
    fun testThreadSafeConcurrentInitialization() {
        val threads = 20
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)
        val failureCount = AtomicInteger(0)

        for (i in 1..threads) {
            executor.submit {
                try {
                    NetworkOptimizer.initialize()
                } catch (_: Throwable) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All threads should complete within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No thread should throw during concurrent initialization", 0, failureCount.get())
        assertTrue("Client must be optimized after concurrent runs", NetworkOptimizer.isOptimized())
        assertNotNull(NetworkOptimizer.getConnectionPool())
    }

    @Test
    fun testIsAlreadyOptimizedDetectsUnoptimizedOrPartialClients() {
        // Raw client without marker or tuning
        val rawClient = OkHttpClient.Builder().build()
        assertFalse(NetworkOptimizer.isAlreadyOptimized(rawClient))

        // Client with marker only, but without dispatcher tuning
        val partialClient1 = OkHttpClient.Builder()
            .addInterceptor(NetworkOptimizerMarker())
            .build()
        assertFalse(NetworkOptimizer.isAlreadyOptimized(partialClient1))

        // Client with marker and dispatcher, but wrong DNS
        val partialClient2 = OkHttpClient.Builder()
            .addInterceptor(NetworkOptimizerMarker())
            .dispatcher(Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            })
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
        assertFalse(NetworkOptimizer.isAlreadyOptimized(partialClient2))
    }

    @Test
    fun testNetworkOptimizerMarkerProceedsChain() {
        val marker = NetworkOptimizerMarker()
        val request = Request.Builder().url("https://example.com").build()
        var proceedCalled = false

        val mockChain = object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                proceedCalled = true
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody())
                    .build()
            }
            override fun connection() = null
            override fun call() = throw UnsupportedOperationException()
            override fun connectTimeoutMillis() = 1000
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
            override fun readTimeoutMillis() = 1000
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
            override fun writeTimeoutMillis() = 1000
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        }

        val response = marker.intercept(mockChain)
        assertTrue(proceedCalled)
        assertEquals(200, response.code)
    }
}
