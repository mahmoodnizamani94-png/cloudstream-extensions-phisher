package com.phisher98

import android.app.ActivityManager
import android.content.Context
import com.lagradost.api.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * Enhanced concurrency management for StreamPlay
 * - Device-adaptive concurrency
 * - Priority-based execution
 * - Progressive result streaming
 * - Memory-aware throttling
 */
object StreamPlayConcurrency {

    private const val TAG = "StreamPlayConcurrency"

    // ==================== Device Profile Detection ====================

    const val MIN_PROVIDER_CONCURRENCY = 4
    const val MAX_PROVIDER_CONCURRENCY = 96

    enum class DeviceProfile {
        LOW_END,    // < 2GB RAM, < 4 cores
        MID_RANGE,  // 2-4GB RAM, 4-6 cores
        HIGH_END;   // > 4GB RAM, > 6 cores

        val recommendedConcurrency: Int
            get() = when (this) {
                LOW_END -> 8
                MID_RANGE -> 32
                HIGH_END -> 64
            }

    }

    private var detectedProfile: DeviceProfile? = null

    /**
     * Detect device capabilities and return appropriate profile
     */
    fun detectDeviceProfile(context: Context): DeviceProfile {
        if (detectedProfile != null) return detectedProfile!!

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val totalRamMB = memoryInfo.totalMem / (1024 * 1024)
        val availableProcessors = Runtime.getRuntime().availableProcessors()

        val profile = when {
            totalRamMB < 2048 || availableProcessors < 4 -> DeviceProfile.LOW_END
            totalRamMB < 4096 || availableProcessors < 6 -> DeviceProfile.MID_RANGE
            else -> DeviceProfile.HIGH_END
        }

        detectedProfile = profile
        Log.d(TAG, "🔍 Detected device: $profile (RAM: ${totalRamMB}MB, Cores: $availableProcessors)")
        return profile
    }

    // ==================== Enhanced runLimitedAsync ====================

    /**
     * Run tasks with limited concurrency
     */
    suspend fun runLimitedAsync(
        concurrency: Int = 5,
        taskTimeoutMs: Long = 25_000L,
        vararg tasks: suspend () -> Unit
    ) = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(concurrency.coerceIn(1, tasks.size))

        tasks.map { task ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val completed = withTimeoutOrNull(taskTimeoutMs.milliseconds) {
                            task()
                            true
                        } ?: false
                        if (!completed) Log.w(TAG, "Task timed out after ${taskTimeoutMs}ms")
                    } catch (e: Exception) {
                        Log.e(TAG, "Task failed: ${e.message}")
                    }
                }
            }
        }.awaitAll()
    }

    /**
     * Adaptive timeout based on provider history
     */
    fun getAdaptiveTimeout(providerId: String, baseTimeoutMs: Long = 15000): Long {
        val stats = StreamPlayCache.getProviderStats(providerId)

        if (stats.successCount == 0) return baseTimeoutMs

        if (stats.isCircuitBroken) return 5000L

        val avgTime = stats.avgTimeMs
        return when {
            avgTime == 0L -> baseTimeoutMs
            avgTime < 1500 -> max(avgTime + 1500, 3500)
            avgTime < 6000 -> avgTime + 3500
            avgTime < 15000 -> avgTime + 6000
            else -> min(avgTime + 8000, baseTimeoutMs * 2)
        }
    }

    fun getProviderExecutionTimeout(providerId: String): Long {
        val stats = StreamPlayCache.getProviderStats(providerId)
        if (stats.isCircuitBroken) return 5_000L
        if (stats.isRecovering) return 12_000L
        if (stats.successCount + stats.failureCount == 0) return 22_000L

        val historyBasedTimeout = when {
            stats.avgTimeMs <= 0L -> 22_000L
            stats.avgTimeMs < 2_000L -> 8_000L
            stats.avgTimeMs < 8_000L -> stats.avgTimeMs + 8_000L
            else -> stats.avgTimeMs + 12_000L
        }

        return historyBasedTimeout.coerceIn(6_000L, 35_000L)
    }

    fun normalizeConcurrency(value: Int): Int =
        value.coerceIn(MIN_PROVIDER_CONCURRENCY, MAX_PROVIDER_CONCURRENCY)

    fun concurrencyLabel(value: Int): String = when {
        value <= 12 -> "Slow internet saver"
        value <= 32 -> "Balanced"
        value <= 64 -> "Fast"
        else -> "Max speed"
    }

    fun shouldStopSlowInternetSearch(
        linksFound: Int,
        subtitlesFound: Int,
        providersCompleted: Int,
        totalProviders: Int
    ): Boolean {
        if (linksFound < 8) return false
        if (providersCompleted < min(totalProviders, 12)) return false
        return subtitlesFound >= 2 || providersCompleted >= min(totalProviders, 20)
    }
}
