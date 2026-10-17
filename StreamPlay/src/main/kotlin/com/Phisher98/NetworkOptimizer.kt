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
 * - OptimizedDns (in-memory LRU cache, Happy Eyeballs IP racing, 4-operator DoH race)
 * - StreamPlayShield application interceptor (ad/tracker block, tracking particles, referer
 *   policy, final-response verification)
 * - StreamPlayShield network hop guard (per-redirect-hop ad/tracker veto)
 * - High-capacity ConnectionPool (64 max idle connections, 5 min keep-alive)
 * - High-concurrency Dispatcher (128 max concurrent requests, 32 per host)
 * - Optimized socket timeouts (6s connect, 10s read, 10s write)
 *
 * Thread-safe, cross-plugin idempotent AND upgrade-safe: if multiple plugins (StreamPlay,
 * Ultima, StremioAddon, SuperStream) call initialize(), the existing ConnectionPool
 * is strictly preserved and NEVER re-allocated, preventing the severance of active
 * HTTP/2 multiplexed sockets. A client that already carries an earlier shield release
 * receives only the missing enforcement tier — interceptors are never stacked twice
 * across plugin reload generations.
 */
object NetworkOptimizer {
    private const val TAG = "NetworkOptimizer"

    @Volatile
    private var isLocallyInitialized: Boolean = false

    /**
     * Inspects the given OkHttpClient to determine if it has already been optimized.
     * Uses cross-ClassLoader string matching for NetworkOptimizerMarker, ShieldRequestInterceptor
     * (application tier), ShieldHopGuardInterceptor (network tier) and OptimizedDns,
     * and verifies high-throughput dispatcher settings and optimized timeouts.
     */
    fun isAlreadyOptimized(client: OkHttpClient): Boolean {
        val hasMarker = client.interceptors.any { interceptor ->
            interceptor.javaClass.name.endsWith("NetworkOptimizerMarker")
        }
        val hasShield = client.interceptors.any { interceptor ->
            interceptor.javaClass.name.endsWith("ShieldRequestInterceptor")
        }
        val hasHopGuard = client.networkInterceptors.any { interceptor ->
            interceptor.javaClass.name.endsWith("ShieldHopGuardInterceptor")
        }
        val hasOptimizedDispatcher = client.dispatcher.maxRequests >= 128 &&
                client.dispatcher.maxRequestsPerHost >= 32
        val hasOptimizedTimeouts = client.connectTimeoutMillis <= 6000
        val hasOptimizedDns = client.dns.javaClass.name.contains("OptimizedDns")

        return hasMarker && hasShield && hasHopGuard && hasOptimizedDispatcher &&
                hasOptimizedTimeouts && hasOptimizedDns
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
                // Per-tier detection: a client that already carries an earlier release of the
                // shield receives only the missing tiers — interceptors must never be stacked
                // twice across plugin reload generations.
                val hasMarker = currentClient.interceptors.any { it.javaClass.name.endsWith("NetworkOptimizerMarker") }
                val hasShield = currentClient.interceptors.any { it.javaClass.name.endsWith("ShieldRequestInterceptor") }
                val hasHopGuard = currentClient.networkInterceptors.any { it.javaClass.name.endsWith("ShieldHopGuardInterceptor") }

                val builder = currentClient.newBuilder()
                    .dns(OptimizedDns)
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .writeTimeout(10, TimeUnit.SECONDS)

                if (!hasMarker) {
                    // First touch of a foreign client: allocate our own high-capacity pool and
                    // dispatcher. Upgrade generations (marker already present) keep the live
                    // pool so no in-flight HTTP/2 socket is ever severed by a plugin update.
                    builder.dispatcher(Dispatcher().apply {
                        maxRequests = 128
                        maxRequestsPerHost = 32
                    })
                    builder.connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
                    builder.addInterceptor(NetworkOptimizerMarker())
                }
                if (!hasShield) builder.addInterceptor(StreamPlayShield.newRequestInterceptor())
                if (!hasHopGuard) builder.addNetworkInterceptor(StreamPlayShield.newHopGuardInterceptor())

                app.baseClient = builder.build()
                isLocallyInitialized = true
                Log.d(TAG, "🚀 NetworkOptimizer initialized: pool=64, maxReq=128, maxPerHost=32, connect=6s, read=10s, dns=OptimizedDns, shield=StreamPlayShield, hopGuard=on")

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
