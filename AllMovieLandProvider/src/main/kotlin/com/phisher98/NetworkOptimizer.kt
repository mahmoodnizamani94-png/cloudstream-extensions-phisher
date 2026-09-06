package com.phisher98

import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import okhttp3.ConnectionPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NetworkOptimizer {
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        try {
            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            }
            app.baseClient = app.baseClient.newBuilder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(64, 5, TimeUnit.MINUTES))
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            Log.d("NetworkOptimizer", "Successfully configured baseClient connection pool, dispatcher, and socket timeouts.")
        } catch (e: Exception) {
            Log.e("NetworkOptimizer", "Failed to optimize OkHttpClient: ${e.message}")
        }
    }
}
