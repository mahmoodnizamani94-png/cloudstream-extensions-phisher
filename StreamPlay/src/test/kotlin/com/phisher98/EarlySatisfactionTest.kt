package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class EarlySatisfactionTest {

    private fun createLink(name: String, quality: Int = Qualities.P720.value, url: String = "https://example.com/v.mp4"): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = "TestSource",
            name = name,
            url = url,
            referer = "https://example.com",
            quality = quality,
            type = ExtractorLinkType.VIDEO,
            headers = emptyMap()
        )
    }

    private fun createSubtitle(lang: String = "English", url: String = "https://example.com/sub.srt"): SubtitleFile {
        return SubtitleFile(
            lang = lang,
            url = url
        )
    }

    @Before
    fun setUp() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @Test
    fun testInstantPlaybackTriggeredOnSingleHighQualityStream() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 1,
            qualityThreshold = Qualities.P1080.value,
            tier1DelayMs = 40L
        )
        val controller = EarlySatisfactionController(config)
        val laggingCancelledCount = AtomicInteger(0)

        val tasks = buildList {
            add(PipelinedTask("fast_resolver", LatencyTier.TIER_0) {
                delay(20)
                controller.onLinkEmitted(createLink("Movie [1080p]", Qualities.P1080.value))
            })
            repeat(5) { id ->
                add(PipelinedTask("lagging_$id", LatencyTier.TIER_1) {
                    try {
                        delay(2000)
                    } catch (e: CancellationException) {
                        laggingCancelledCount.incrementAndGet()
                        throw e
                    }
                })
            }
        }

        val start = System.currentTimeMillis()
        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Execution must succeed", result)
        assertTrue("Early satisfaction must be achieved", controller.isSatisfied())
        assertEquals(1, controller.getQualityLinksCount())
        assertTrue("Elapsed time must be sub-1.5s (< 500ms in unit test, was ${elapsed}ms)", elapsed < 500L)
    }

    @Test
    fun testDualStandardStreamsTriggerSatisfaction() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 5 // Not satisfied by quality links alone
        )
        val controller = EarlySatisfactionController(config)

        assertFalse("Initially unsatisfied", controller.isSatisfied())

        // 1st standard stream (720p)
        controller.onLinkEmitted(createLink("Stream 720p", Qualities.P720.value))
        assertFalse("Unsatisfied after 1 standard stream", controller.isSatisfied())
        assertEquals(1, controller.getLinksCount())

        // 2nd standard stream (720p)
        controller.onLinkEmitted(createLink("Stream 720p Mirror", Qualities.P720.value))
        assertTrue("Satisfied after 2 standard streams", controller.isSatisfied())
        assertEquals(2, controller.getLinksCount())
    }

    @Test
    fun testSubtitlePlusLinkSatisfaction() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 3,
            minQualityStreams = 5,
            satisfyWithOneLinkIfSubsFound = true,
            minSubtitles = 1
        )
        val controller = EarlySatisfactionController(config)

        controller.onLinkEmitted(createLink("Stream 720p", Qualities.P720.value))
        assertFalse("1 link without subtitles should not satisfy", controller.isSatisfied())

        controller.onSubtitleEmitted(createSubtitle("English"))
        assertTrue("1 link + 1 subtitle file satisfies", controller.isSatisfied())
    }

    @Test
    fun testStructuredCoroutineCancellationOfLaggingTasks() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 1
        )
        val controller = EarlySatisfactionController(config)
        val cancellationRecorded = AtomicInteger(0)

        val tasks = buildList {
            add(PipelinedTask("fast_task", LatencyTier.TIER_0) {
                delay(15)
                controller.onLinkEmitted(createLink("Direct 1080p", Qualities.P1080.value))
            })
            repeat(8) { id ->
                add(PipelinedTask("slow_task_$id", LatencyTier.TIER_0) {
                    try {
                        delay(3000)
                    } catch (e: CancellationException) {
                        cancellationRecorded.incrementAndGet()
                        throw e
                    }
                })
            }
        }

        SpeculativePipeliner.executePipelined(tasks, config, controller)

        assertTrue("Lagging tasks must receive structured cancellation", cancellationRecorded.get() > 0)
    }

    @Test
    fun testSubtitlePreservationWhenRequired() = runBlocking {
        val config = EarlySatisfactionConfig(
            minVerifiedLinks = 2,
            minQualityStreams = 5,
            requireSubtitles = true,
            minSubtitles = 1
        )
        val controller = EarlySatisfactionController(config)

        controller.onLinkEmitted(createLink("Link 1", Qualities.P720.value))
        controller.onLinkEmitted(createLink("Link 2", Qualities.P720.value))
        assertFalse("Should NOT be satisfied without required subtitles", controller.isSatisfied())

        controller.onSubtitleEmitted(createSubtitle("Spanish"))
        assertTrue("Satisfied once required subtitle is emitted", controller.isSatisfied())
    }

    @Test
    fun testBitrateBasedHighQualitySatisfaction() = runBlocking {
        val config = EarlySatisfactionConfig(
            minQualityStreams = 1,
            highBitrateThresholdKbps = 2500
        )
        val controller = EarlySatisfactionController(config)

        // Stream with 4500 kbps explicit in name
        val highBitrateLink = createLink("FastStream [4500 kbps]", Qualities.Unknown.value)
        assertTrue("Should detect 4500 kbps as high quality", controller.isHighQualityVerifiedStream(highBitrateLink))

        controller.onLinkEmitted(highBitrateLink)
        assertTrue("High bitrate stream must trigger early satisfaction", controller.isSatisfied())
    }

    @Test
    fun testFastCdnEndpointHighQualitySatisfaction() = runBlocking {
        val config = EarlySatisfactionConfig(minQualityStreams = 1)
        val controller = EarlySatisfactionController(config)

        val debridLink = createLink("Real-Debrid Direct", Qualities.P720.value, "https://real-debrid.com/file/direct.mp4")
        assertTrue("Real-Debrid at 720p+ qualifies as verified fast stream", controller.isHighQualityVerifiedStream(debridLink))

        val pixelDrainLink = createLink("PixelDrain Direct", Qualities.P720.value, "https://pixeldrain.com/api/file/xyz")
        assertTrue("PixelDrain at 720p+ qualifies as verified fast stream", controller.isHighQualityVerifiedStream(pixelDrainLink))
    }
}
