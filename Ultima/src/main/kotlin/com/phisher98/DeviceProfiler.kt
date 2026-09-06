package com.phisher98

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.lagradost.api.Log

/**
 * 3-Tier Device Capability Categorization
 * Maps physical hardware limits to safe coroutine concurrency bounds.
 */
enum class DeviceTier(val maxConcurrency: Int) {
    /**
     * Physical RAM <= 1.5GB, memoryClass <= 192MB, or cores <= 4 on low RAM.
     * Android TV sticks, budget streaming boxes, low-RAM devices.
     */
    LOW_END(12),

    /**
     * Physical RAM <= 3GB, memoryClass <= 384MB, or cores <= 6.
     * Standard 4K TV sticks, mid-tier Android TV / tablets.
     */
    MID_RANGE(36),

    /**
     * Physical RAM > 3GB, memoryClass > 384MB, cores > 6.
     * Flagship devices, NVIDIA Shield TV Pro, emulator, desktop JVM.
     */
    HIGH_END(64);
}

/**
 * Point-in-time snapshot of system and virtual machine memory metrics.
 *
 * @param totalRamBytes Total physical device RAM in bytes.
 * @param availableRamBytes Physical RAM currently available / reclaimable by kernel.
 * @param freeHeapBytes Available JVM/Dalvik heap headroom: maxMemory - (totalMemory - freeMemory).
 * @param maxHeapBytes Maximum heap capacity allocated to process before OutOfMemoryError.
 * @param isLowMemory True if Android OS considers itself in a low-memory state.
 */
data class MemorySnapshot(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val freeHeapBytes: Long,
    val maxHeapBytes: Long,
    val isLowMemory: Boolean
) {
    /**
     * True if available heap headroom is below the 32MB safety threshold or the OS is low on memory.
     */
    val isHeapConstrained: Boolean
        get() = freeHeapBytes < DeviceProfiler.LOW_HEAP_HEADROOM_THRESHOLD_BYTES || isLowMemory

    /**
     * Percentage of physical RAM currently available (0.0% to 100.0%).
     */
    val availableRamPercent: Float
        get() = if (totalRamBytes > 0L) (availableRamBytes.toFloat() / totalRamBytes.toFloat()) * 100f else 0.0f
}

/**
 * Pluggable abstraction for JVM runtime metrics, enabling 100% deterministic unit testing.
 */
interface RuntimeMetricsProvider {
    fun availableProcessors(): Int
    fun maxMemory(): Long
    fun totalMemory(): Long
    fun freeMemory(): Long
}

/**
 * Default runtime metrics provider backed by JVM java.lang.Runtime.
 */
object DefaultRuntimeMetricsProvider : RuntimeMetricsProvider {
    override fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
    override fun maxMemory(): Long = Runtime.getRuntime().maxMemory()
    override fun totalMemory(): Long = Runtime.getRuntime().totalMemory()
    override fun freeMemory(): Long = Runtime.getRuntime().freeMemory()
}

/**
 * Pluggable abstraction for Android ActivityManager system memory metrics.
 */
interface MemoryInfoProvider {
    fun getMemorySnapshot(context: Context?, runtimeMetrics: RuntimeMetricsProvider): MemorySnapshot
    fun getMemoryClass(context: Context?): Int
    fun getLargeMemoryClass(context: Context?): Int
    fun isLowRamDevice(context: Context?): Boolean
}

/**
 * Default memory info provider backed by Android ActivityManager and Context.
 */
object DefaultMemoryInfoProvider : MemoryInfoProvider {
    override fun getMemorySnapshot(
        context: Context?,
        runtimeMetrics: RuntimeMetricsProvider
    ): MemorySnapshot {
        val maxHeap = runtimeMetrics.maxMemory()
        val totalHeap = runtimeMetrics.totalMemory()
        val freeHeap = runtimeMetrics.freeMemory()
        val usedHeap = totalHeap - freeHeap
        val heapHeadroom = if (maxHeap == Long.MAX_VALUE) {
            freeHeap
        } else {
            (maxHeap - usedHeap).coerceAtLeast(0L)
        }

        val activityManager = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            return MemorySnapshot(
                totalRamBytes = memInfo.totalMem,
                availableRamBytes = memInfo.availMem,
                freeHeapBytes = heapHeadroom,
                maxHeapBytes = maxHeap,
                isLowMemory = memInfo.lowMemory
            )
        }

        // Graceful fallback when Android Context is null (e.g. JVM unit tests)
        val estimatedTotalRam = if (maxHeap != Long.MAX_VALUE) maxHeap * 4 else 4L * 1024L * 1024L * 1024L
        return MemorySnapshot(
            totalRamBytes = estimatedTotalRam,
            availableRamBytes = heapHeadroom,
            freeHeapBytes = heapHeadroom,
            maxHeapBytes = maxHeap,
            isLowMemory = false
        )
    }

    override fun getMemoryClass(context: Context?): Int {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.memoryClass ?: 256
    }

    override fun getLargeMemoryClass(context: Context?): Int {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.largeMemoryClass ?: 512
    }

    override fun isLowRamDevice(context: Context?): Boolean {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am?.isLowRamDevice ?: false
    }
}

/**
 * State-Of-The-Art Device Capability Profiler & Real-Time Heap Governor
 *
 * Architecture:
 * 1. 3-Tier Hardware Profiling:
 *    - LOW_END (<= 12): Physical RAM <= 1.5GB, memoryClass <= 192MB, cores <= 4 on low RAM.
 *    - MID_RANGE (<= 36): Physical RAM <= 3GB, memoryClass <= 384MB, or cores <= 6.
 *    - HIGH_END (<= 64): Physical RAM > 3GB, memoryClass > 384MB, cores > 6.
 * 2. Real-Time Heap Governor:
 *    - Computes real-time Dalvik/JVM heap headroom: maxMemory() - (totalMemory() - freeMemory()).
 *    - If available headroom < 32MB or system lowMemory flag is set, clamps concurrency by 50%.
 *    - e.g.: 12 -> 6, 36 -> 18, 64 -> 32.
 * 3. Zero-Overhead Fast-Path:
 *    - Tier categorization cached once per process lifecycle.
 *    - Memory snapshots cached with a 500ms volatile TTL window to eliminate Binder IPC overhead.
 * 4. Deterministic Mockability:
 *    - Fully pluggable RuntimeMetricsProvider and MemoryInfoProvider for pure JUnit 4 testing.
 */
object DeviceProfiler {

    private const val TAG = "DeviceProfiler"

    // 32MB Safety Headroom Threshold
    const val LOW_HEAP_HEADROOM_THRESHOLD_BYTES: Long = 32L * 1024L * 1024L

    // Absolute minimum concurrency floor under severe memory pressure
    const val MIN_CONCURRENCY: Int = 2

    // Memory snapshot cache TTL (500ms) to eliminate Binder IPC thrashing
    const val SNAPSHOT_CACHE_TTL_MS: Long = 500L

    // Nominal 1.5GB RAM threshold in bytes (1536MB)
    const val RAM_THRESHOLD_LOW_END_BYTES: Long = 1536L * 1024L * 1024L

    // Nominal 3.0GB RAM threshold in bytes (3072MB)
    const val RAM_THRESHOLD_MID_RANGE_BYTES: Long = 3072L * 1024L * 1024L

    @Volatile
    private var runtimeMetrics: RuntimeMetricsProvider = DefaultRuntimeMetricsProvider

    @Volatile
    private var memoryInfoProvider: MemoryInfoProvider = DefaultMemoryInfoProvider

    @Volatile
    private var cachedTier: DeviceTier? = null

    @Volatile
    private var cachedSnapshot: MemorySnapshot? = null

    @Volatile
    private var lastSnapshotTimestampMs: Long = 0L

    /**
     * Initializes and warms up the device profile during plugin load.
     */
    fun initialize(context: Context?) {
        val tier = getDeviceTier(context)
        val snapshot = getMemorySnapshot(context, forceRefresh = true)
        Log.d(
            TAG,
            "🚀 DeviceProfiler initialized: Tier=$tier, MaxConcurrency=${tier.maxConcurrency}, " +
                "RAM=${snapshot.totalRamBytes / (1024 * 1024)}MB, Headroom=${snapshot.freeHeapBytes / (1024 * 1024)}MB, " +
                "LowMemory=${snapshot.isLowMemory}"
        )
    }

    /**
     * Detects and returns the hardware capability tier.
     * Invariant across process lifecycle; cached on first evaluation.
     */
    fun getDeviceTier(context: Context? = null): DeviceTier {
        cachedTier?.let { return it }

        synchronized(this) {
            cachedTier?.let { return it }

            val processors = runtimeMetrics.availableProcessors()
            val memClass = memoryInfoProvider.getMemoryClass(context)
            val isLowRam = memoryInfoProvider.isLowRamDevice(context)

            // If context is available, use exact physical RAM from ActivityManager
            val snapshot = memoryInfoProvider.getMemorySnapshot(context, runtimeMetrics)
            val totalRamBytes = snapshot.totalRamBytes
            val totalRamMb = totalRamBytes / (1024L * 1024L)

            val tier = when {
                // Low-End Tier: RAM <= 1.5GB, memoryClass <= 192MB, or cores <= 4 on low RAM
                isLowRam || totalRamBytes <= RAM_THRESHOLD_LOW_END_BYTES || memClass <= 192 || (processors <= 4 && totalRamMb <= 2048L) -> {
                    DeviceTier.LOW_END
                }
                // Mid-Range Tier: RAM <= 3.0GB, memoryClass <= 384MB, or cores <= 6
                totalRamBytes <= RAM_THRESHOLD_MID_RANGE_BYTES || memClass <= 384 || processors <= 6 -> {
                    DeviceTier.MID_RANGE
                }
                // High-End Tier: RAM > 3.0GB, memoryClass > 384MB, cores > 6
                else -> {
                    DeviceTier.HIGH_END
                }
            }

            cachedTier = tier
            return tier
        }
    }

    /**
     * Returns the dynamically governed active concurrency limit.
     *
     * @param context Optional Android Context.
     * @param baseConcurrency Optional base concurrency override. Defaults to tier maxConcurrency.
     * @return Concurrency limit, clamped by 50% if heap headroom < 32MB or system is in lowMemory state.
     */
    fun getActiveConcurrency(context: Context? = null, baseConcurrency: Int? = null): Int {
        val tier = getDeviceTier(context)
        val base = baseConcurrency ?: tier.maxConcurrency
        val snapshot = getMemorySnapshot(context)

        return clampConcurrencyForHeap(base, snapshot)
    }

    /**
     * Clamps a base concurrency value by 50% if the memory snapshot indicates memory constraint.
     */
    fun clampConcurrencyForHeap(base: Int, snapshot: MemorySnapshot): Int {
        return if (snapshot.isHeapConstrained) {
            val clamped = (base / 2).coerceAtLeast(MIN_CONCURRENCY)
            Log.w(
                TAG,
                "⚠️ Heap constrained (headroom=${snapshot.freeHeapBytes / (1024 * 1024)}MB, lowMem=${snapshot.isLowMemory})! " +
                    "Clamping concurrency from $base to $clamped"
            )
            clamped
        } else {
            base
        }
    }

    /**
     * Returns the current memory snapshot with zero-overhead 500ms volatile caching.
     */
    fun getMemorySnapshot(context: Context? = null, forceRefresh: Boolean = false): MemorySnapshot {
        val now = System.currentTimeMillis()
        val current = cachedSnapshot
        if (!forceRefresh && current != null && (now - lastSnapshotTimestampMs) < SNAPSHOT_CACHE_TTL_MS) {
            return current
        }

        val fresh = memoryInfoProvider.getMemorySnapshot(context, runtimeMetrics)
        cachedSnapshot = fresh
        lastSnapshotTimestampMs = now
        return fresh
    }

    /**
     * Returns true if available heap headroom is below 32MB or system is in low-memory state.
     */
    fun isHeapConstrained(context: Context? = null): Boolean {
        return getMemorySnapshot(context).isHeapConstrained
    }

    // ==================== Test Verification Hooks ====================

    @VisibleForTesting
    fun setRuntimeMetricsProviderForTesting(provider: RuntimeMetricsProvider) {
        runtimeMetrics = provider
        resetCache()
    }

    @VisibleForTesting
    fun setMemoryInfoProviderForTesting(provider: MemoryInfoProvider) {
        memoryInfoProvider = provider
        resetCache()
    }

    @VisibleForTesting
    fun setDeviceTierForTesting(tier: DeviceTier?) {
        cachedTier = tier
    }

    @VisibleForTesting
    fun resetForTesting() {
        runtimeMetrics = DefaultRuntimeMetricsProvider
        memoryInfoProvider = DefaultMemoryInfoProvider
        resetCache()
    }

    @VisibleForTesting
    fun resetCacheForTesting() {
        resetCache()
    }

    private fun resetCache() {
        cachedTier = null
        cachedSnapshot = null
        lastSnapshotTimestampMs = 0L
    }
}
