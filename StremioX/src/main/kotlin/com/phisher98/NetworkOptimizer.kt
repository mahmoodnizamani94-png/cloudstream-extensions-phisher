package com.phisher98

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Marker interceptor to deterministically identify whether app.baseClient
 * has been tuned by NetworkOptimizer across independent plugin ClassLoaders.
 */
class NetworkOptimizerMarker : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request())
    }
}

/**
 * SOTA Idempotent Network Connection & Client Optimizer.
 *
 * Configures CloudStream's app.baseClient with:
 * - OptimizedDns (in-memory LRU cache, Happy Eyeballs IP racing, Cloudflare/Google DoH fallback)
 * - High-capacity ConnectionPool (64 max idle connections, 5 min keep-alive)
 * - High-concurrency Dispatcher (128 max concurrent requests, 32 per host)
 * - Optimized socket timeouts (6s connect, 10s read, 10s write)
 *
 * Thread-safe and cross-plugin idempotent: If multiple plugins (StreamPlay,
 * Ultima, StremioAddon, SuperStream) call initialize(), the existing ConnectionPool
 * is strictly preserved and NEVER re-allocated, preventing the severance of active
 * HTTP/2 multiplexed sockets.
 */
object NetworkOptimizer {
    private const val TAG = "NetworkOptimizer"

    @Volatile
    private var isLocallyInitialized: Boolean = false

    /**
     * Inspects the given OkHttpClient to determine if it has already been optimized.
     * Uses cross-ClassLoader string matching for NetworkOptimizerMarker and OptimizedDns,
     * and verifies high-throughput dispatcher settings and optimized timeouts.
     */
    fun isAlreadyOptimized(client: OkHttpClient): Boolean {
        val hasMarker = client.interceptors.any { interceptor ->
            interceptor.javaClass.name.endsWith("NetworkOptimizerMarker")
        }
        val hasOptimizedDispatcher = client.dispatcher.maxRequests >= 128 &&
                client.dispatcher.maxRequestsPerHost >= 32
        val hasOptimizedTimeouts = client.connectTimeoutMillis <= 6000
        val hasOptimizedDns = client.dns.javaClass.name.contains("OptimizedDns")

        return hasMarker && hasOptimizedDispatcher && hasOptimizedTimeouts && hasOptimizedDns
    }

    /**
     * Idempotently configures app.baseClient with OptimizedDns and tuned connection pool.
     */
    fun initialize(context: Context? = null) {
        if (isLocallyInitialized && isAlreadyOptimized(app.baseClient)) {
            return
        }

        synchronized(app) {
            val currentClient = app.baseClient

            if (isAlreadyOptimized(currentClient)) {
                isLocallyInitialized = true
                Log.d(TAG, "⚡ app.baseClient already optimized. Preserving active HTTP/2 connection pool.")
                OptimizedDns.preResolveTopDomains()
                return
            }

            try {
                val dispatcher = Dispatcher().apply {
                    maxRequests = 128
                    maxRequestsPerHost = 32
                }

                val connectionPool = ConnectionPool(64, 5, TimeUnit.MINUTES)

                val newClient = currentClient.newBuilder()
                    .dns(OptimizedDns)
                    .dispatcher(dispatcher)
                    .connectionPool(connectionPool)
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)
                    .addInterceptor(NetworkOptimizerMarker())
                    .build()

                app.baseClient = newClient
                isLocallyInitialized = true
                Log.d(TAG, "🚀 NetworkOptimizer initialized: pool=64, maxReq=128, maxPerHost=32, connect=6s, read=10s, dns=OptimizedDns")

                OptimizedDns.preResolveTopDomains()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize NetworkOptimizer: ${e.message}")
            }
        }
    }

    fun isOptimized(): Boolean = isAlreadyOptimized(app.baseClient)

    fun getConnectionPool(): ConnectionPool = app.baseClient.connectionPool

    @VisibleForTesting
    fun resetForTesting(originalClient: OkHttpClient? = null) {
        synchronized(app) {
            isLocallyInitialized = false
            if (originalClient != null) {
                app.baseClient = originalClient
            }
        }
    }
}
