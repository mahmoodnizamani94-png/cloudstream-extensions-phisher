package com.phisher98

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceProfilerTest {

    class TestRuntimeMetrics(
        var processors: Int = 8,
        var maxHeap: Long = 512L * 1024L * 1024L,
        var totalHeap: Long = 128L * 1024L * 1024L,
        var freeHeap: Long = 64L * 1024L * 1024L
    ) : RuntimeMetricsProvider {
        override fun availableProcessors(): Int = processors
        override fun maxMemory(): Long = maxHeap
        override fun totalMemory(): Long = totalHeap
        override fun freeMemory(): Long = freeHeap
    }

    class TestMemoryInfo(
        var totalRam: Long = 6L * 1024L * 1024L * 1024L,
        var availRam: Long = 3L * 1024L * 1024L * 1024L,
        var memClass: Int = 512,
        var largeMemClass: Int = 512,
        var isLowRam: Boolean = false,
        var isLowMem: Boolean = false,
        var overrideHeadroom: Long? = null
    ) : MemoryInfoProvider {
        override fun getMemorySnapshot(context: Context?, runtimeMetrics: RuntimeMetricsProvider): MemorySnapshot {
            val maxHeap = runtimeMetrics.maxMemory()
            val totalHeap = runtimeMetrics.totalMemory()
            val freeHeap = runtimeMetrics.freeMemory()
            val usedHeap = totalHeap - freeHeap
            val headroom = overrideHeadroom ?: if (maxHeap == Long.MAX_VALUE) freeHeap else (maxHeap - usedHeap).coerceAtLeast(0L)
            return MemorySnapshot(
                totalRamBytes = totalRam,
                availableRamBytes = availRam,
                freeHeapBytes = headroom,
                maxHeapBytes = maxHeap,
                isLowMemory = isLowMem
            )
        }

        override fun getMemoryClass(context: Context?): Int = memClass
        override fun getLargeMemoryClass(context: Context?): Int = largeMemClass
        override fun isLowRamDevice(context: Context?): Boolean = isLowRam
    }

    private lateinit var mockRuntime: TestRuntimeMetrics
    private lateinit var mockMemory: TestMemoryInfo

    @Before
    fun setUp() {
        DeviceProfiler.resetForTesting()
        mockRuntime = TestRuntimeMetrics()
        mockMemory = TestMemoryInfo()
        DeviceProfiler.setRuntimeMetricsProviderForTesting(mockRuntime)
        DeviceProfiler.setMemoryInfoProviderForTesting(mockMemory)
    }

    @After
    fun tearDown() {
        DeviceProfiler.resetForTesting()
    }

    // ==================== Tier Categorization Tests ====================

    @Test
    fun testLowEndCategorizationByTotalRam() {
        // 1GB RAM device
        mockMemory.totalRam = 1024L * 1024 * 1024
        mockMemory.memClass = 128
        mockRuntime.processors = 4

        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testLowEndCategorizationBy1Point5GbRam() {
        // 1.5GB RAM device
        mockMemory.totalRam = 1536L * 1024 * 1024
        mockMemory.memClass = 256
        mockRuntime.processors = 4

        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testLowEndCategorizationByMemoryClass() {
        // High RAM reported but low memory class (<= 192MB)
        mockMemory.totalRam = 4L * 1024 * 1024 * 1024
        mockMemory.memClass = 192
        mockRuntime.processors = 8

        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testLowEndCategorizationByLowRamDeviceFlag() {
        mockMemory.totalRam = 2L * 1024 * 1024 * 1024
        mockMemory.isLowRam = true

        assertEquals(DeviceTier.LOW_END, DeviceProfiler.getDeviceTier())
        assertEquals(12, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testMidRangeCategorizationByRam() {
        // 2.5GB RAM device with 6 cores
        mockMemory.totalRam = 2560L * 1024 * 1024
        mockMemory.memClass = 256
        mockRuntime.processors = 6

        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())
        assertEquals(36, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testMidRangeCategorizationByMemoryClass() {
        // 4GB RAM, 8 cores, but memory class is 384MB
        mockMemory.totalRam = 4096L * 1024 * 1024
        mockMemory.memClass = 384
        mockRuntime.processors = 8

        assertEquals(DeviceTier.MID_RANGE, DeviceProfiler.getDeviceTier())
        assertEquals(36, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testHighEndCategorization() {
        // Flagship device: 6GB RAM, 8 cores, memoryClass 512MB
        mockMemory.totalRam = 6L * 1024 * 1024 * 1024
        mockMemory.memClass = 512
        mockRuntime.processors = 8

        assertEquals(DeviceTier.HIGH_END, DeviceProfiler.getDeviceTier())
        assertEquals(64, DeviceProfiler.getActiveConcurrency())
    }

    // ==================== Real-Time Heap Governor Clamping Tests ====================

    @Test
    fun testHeapGovernorClampsLowEndBy50PercentWhenHeadroomBelow32Mb() {
        mockMemory.totalRam = 1024L * 1024 * 1024
        mockMemory.memClass = 128
        mockRuntime.processors = 4

        // Set headroom to 16MB (< 32MB threshold)
        mockMemory.overrideHeadroom = 16L * 1024 * 1024

        assertTrue(DeviceProfiler.isHeapConstrained())
        // Low-End base is 12; 50% clamp yields 6
        assertEquals(6, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testHeapGovernorClampsMidRangeBy50PercentWhenHeadroomBelow32Mb() {
        mockMemory.totalRam = 2560L * 1024 * 1024
        mockMemory.memClass = 256
        mockRuntime.processors = 6

        // Set headroom to 24MB (< 32MB threshold)
        mockMemory.overrideHeadroom = 24L * 1024 * 1024

        assertTrue(DeviceProfiler.isHeapConstrained())
        // Mid-Range base is 36; 50% clamp yields 18
        assertEquals(18, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testHeapGovernorClampsHighEndBy50PercentWhenHeadroomBelow32Mb() {
        mockMemory.totalRam = 6L * 1024 * 1024 * 1024
        mockMemory.memClass = 512
        mockRuntime.processors = 8

        // Set headroom to 30MB (< 32MB threshold)
        mockMemory.overrideHeadroom = 30L * 1024 * 1024

        assertTrue(DeviceProfiler.isHeapConstrained())
        // High-End base is 64; 50% clamp yields 32
        assertEquals(32, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testHeapGovernorClampsWhenLowMemoryFlagIsTrueEvenWithAdequateHeap() {
        mockMemory.totalRam = 6L * 1024 * 1024 * 1024
        mockMemory.memClass = 512
        mockRuntime.processors = 8

        // Headroom is plenty (200MB), but Android system is in lowMemory state
        mockMemory.overrideHeadroom = 200L * 1024 * 1024
        mockMemory.isLowMem = true

        assertTrue(DeviceProfiler.isHeapConstrained())
        assertEquals(32, DeviceProfiler.getActiveConcurrency())
    }

    @Test
    fun testExactHeadroomBoundaryConditions() {
        mockMemory.totalRam = 4096L * 1024L * 1024L
        DeviceProfiler.setDeviceTierForTesting(DeviceTier.MID_RANGE)

        // Exactly 32MB: max 128MB - used 96MB = exactly 32MB -> NOT constrained (< 32MB constraint)
        val snapshotExact = MemorySnapshot(
            totalRamBytes = 4096L * 1024L * 1024L,
            availableRamBytes = 2048L * 1024L * 1024L,
            freeHeapBytes = 32L * 1024L * 1024L,
            maxHeapBytes = 128L * 1024L * 1024L,
            isLowMemory = false
        )
        assertFalse(snapshotExact.isHeapConstrained)
        assertEquals(36, DeviceProfiler.clampConcurrencyForHeap(36, snapshotExact))

        // 32MB - 1 byte -> CONSTRAINED
        val snapshotConstrained = snapshotExact.copy(freeHeapBytes = (32L * 1024L * 1024L) - 1L)
        assertTrue(snapshotConstrained.isHeapConstrained)
        assertEquals(18, DeviceProfiler.clampConcurrencyForHeap(36, snapshotConstrained))
    }

    @Test
    fun testCustomBaseConcurrencyClamping() {
        mockMemory.totalRam = 4L * 1024 * 1024 * 1024
        mockMemory.memClass = 512
        mockRuntime.processors = 8

        // Healthy state with custom base of 40
        mockMemory.overrideHeadroom = 100L * 1024 * 1024
        assertEquals(40, DeviceProfiler.getActiveConcurrency(baseConcurrency = 40))

        // Constrained state: 40 -> 20
        DeviceProfiler.resetCacheForTesting()
        mockMemory.overrideHeadroom = 10L * 1024 * 1024
        assertEquals(20, DeviceProfiler.getActiveConcurrency(baseConcurrency = 40))
    }

    @Test
    fun testMinimumConcurrencyFloor() {
        val snapshot = MemorySnapshot(
            totalRamBytes = 1024L * 1024L * 1024L,
            availableRamBytes = 200L * 1024L * 1024L,
            freeHeapBytes = 1L * 1024L * 1024L, // severe constraint
            maxHeapBytes = 64L * 1024L * 1024L,
            isLowMemory = true
        )
        // 2 / 2 = 1, coerced to MIN_CONCURRENCY (2)
        assertEquals(2, DeviceProfiler.clampConcurrencyForHeap(2, snapshot))
    }

    @Test
    fun testGracefulFallbackWhenContextIsNull() {
        DeviceProfiler.resetForTesting()
        // No mocks, pure default providers with context == null
        val tier = DeviceProfiler.getDeviceTier(null)
        val concurrency = DeviceProfiler.getActiveConcurrency(null)

        assertTrue(tier in DeviceTier.values())
        assertTrue(concurrency in 2..64)
    }

    @Test
    fun testConcurrentSnapshotQueriesThreadSafety() {
        val threads = 16
        val queriesPerThread = 500
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)
        val errors = AtomicInteger(0)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until queriesPerThread) {
                        val concurrency = DeviceProfiler.getActiveConcurrency(null)
                        if (concurrency < DeviceProfiler.MIN_CONCURRENCY || concurrency > DeviceTier.HIGH_END.maxConcurrency) {
                            errors.incrementAndGet()
                        }
                    }
                } catch (_: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("No concurrency errors under multi-threaded queries", 0, errors.get())
    }
}
