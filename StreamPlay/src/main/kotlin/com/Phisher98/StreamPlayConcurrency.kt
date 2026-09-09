package com.phisher98

import android.app.ActivityManager
import android.content.Context
import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

/**
 * State-Of-The-Art Concurrency Management for StreamPlay
 * - Adaptive supervisor with early satisfaction short-circuiting (zero latency hangs)
 * - Dynamic device profiling (RAM & CPU core awareness)
 * - Adaptive provider timeout decay
 * - Fast graceful cancellation of lagging/dead scrapers
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
                LOW_END -> 12
                MID_RANGE -> 36
                HIGH_END -> 64
            }
    }

    private var detectedProfile: DeviceProfile? = null

    /**
     * Detect device capabilities and return appropriate profile
     */
    fun detectDeviceProfile(context: Context? = null): DeviceProfile {
        val tier = DeviceProfiler.getDeviceTier(context)
        return when (tier) {
            DeviceTier.LOW_END -> DeviceProfile.LOW_END
            DeviceTier.MID_RANGE -> DeviceProfile.MID_RANGE
            DeviceTier.HIGH_END -> DeviceProfile.HIGH_END
        }
    }

    // ==================== Supervised Concurrency Engine ====================

    /**
     * Executes tasks with bounded concurrency and real-time satisfaction monitoring.
     * When [isSatisfied] returns true (e.g. abundant high-quality links are found),
     * pending and stalled tasks are cancelled immediately so the player never hangs.
     */
    suspend fun runSupervisedLimitedAsync(
        concurrency: Int = DeviceProfiler.getActiveConcurrency(),
        taskTimeoutMs: Long = 25_000L,
        isSatisfied: (() -> Boolean)? = null,
        tasks: List<suspend () -> Unit>
    ) = kotlinx.coroutines.supervisorScope {
        if (tasks.isEmpty()) return@supervisorScope

        val activeConcurrency = normalizeConcurrency(concurrency).coerceAtMost(tasks.size)
        val iterator = tasks.iterator()
        val queueLock = Any()

        fun nextTask(): (suspend () -> Unit)? {
            if (isSatisfied?.invoke() == true) return null
            synchronized(queueLock) {
                return if (iterator.hasNext()) iterator.next() else null
            }
        }

        val workerJobs = (0 until activeConcurrency).map {
            launch(Dispatchers.IO) {
                while (isActive) {
                    if (isSatisfied?.invoke() == true) break
                    val task = nextTask() ?: break
                    try {
                        withTimeoutOrNull(taskTimeoutMs.milliseconds) {
                            task()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Task failed: ${e.message}")
                    }
                }
            }
        }

        val monitorJob = if (isSatisfied != null) {
            launch {
                while (isActive) {
                    delay(30.milliseconds)
                    if (isSatisfied.invoke()) {
                        Log.d(TAG, "⚡ Early completion condition satisfied; cancelling trailing stalled tasks.")
                        workerJobs.forEach { job ->
                            if (job.isActive) {
                                job.cancel()
                            }
                        }
                        break
                    }
                }
            }
        } else null

        try {
            workerJobs.forEach { it.join() }
        } finally {
            monitorJob?.cancel()
            workerJobs.forEach { if (it.isActive) it.cancel() }
        }
    }

    /**
     * Backwards-compatible runLimitedAsync using the supervised concurrency engine
     */
    suspend fun runLimitedAsync(
        concurrency: Int = 5,
        taskTimeoutMs: Long = 25_000L,
        vararg tasks: suspend () -> Unit
    ) {
        runSupervisedLimitedAsync(
            concurrency = concurrency,
            taskTimeoutMs = taskTimeoutMs,
            isSatisfied = null,
            tasks = tasks.toList()
        )
    }

    /**
     * Adaptive timeout based on provider history
     */
    fun getAdaptiveTimeout(providerId: String, baseTimeoutMs: Long = 15000): Long {
        val stats = StreamPlayCache.getProviderStats(providerId)

        if (stats.successCount == 0) return baseTimeoutMs
        if (stats.isCircuitBroken) return 4000L

        val avgTime = stats.avgTimeMs
        return when {
            avgTime == 0L -> baseTimeoutMs
            avgTime < 1500 -> max(avgTime + 1500, 3500)
            avgTime < 6000 -> avgTime + 3000
            avgTime < 15000 -> avgTime + 5000
            else -> min(avgTime + 6000, baseTimeoutMs * 2)
        }
    }

    fun getProviderExecutionTimeout(providerId: String): Long {
        val stats = StreamPlayCache.getProviderStats(providerId)
        if (stats.isCircuitBroken) return 4_000L
        if (stats.isRecovering) return 10_000L
        if (stats.successCount + stats.failureCount == 0) return 20_000L

        val historyBasedTimeout = when {
            stats.avgTimeMs <= 0L -> 20_000L
            stats.avgTimeMs < 2_000L -> 7_000L
            stats.avgTimeMs < 8_000L -> stats.avgTimeMs + 6_000L
            else -> stats.avgTimeMs + 8_000L
        }

        return historyBasedTimeout.coerceIn(5_000L, 28_000L)
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
        if (linksFound < 6) return false
        if (providersCompleted < min(totalProviders, 8)) return false
        return subtitlesFound >= 1 || providersCompleted >= min(totalProviders, 16)
    }
}
