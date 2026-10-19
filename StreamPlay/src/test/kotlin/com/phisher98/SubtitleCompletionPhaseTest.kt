package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The subtitle completion phase contract.
 *
 * The host app downloads one English subtitle per source so that at least one candidate is in sync
 * with the release that was downloaded. Reaching that guarantee needs the extension to surface
 * subtitles from enough distinct sources, so this phase exists to run the sources the main sweep
 * never got to - and to end the moment enough of them have answered.
 */
class SubtitleCompletionPhaseTest {

    @Suppress("DEPRECATION")
    private fun createSubtitle(lang: String, url: String): SubtitleFile =
        SubtitleFile(lang = lang, url = url)

    @Test
    fun `english labels and tags are recognised`() {
        val english = listOf("English", "english", "English [SDH]", "English (Forced)", "en", "EN", "en-US", "eng")
        for (label in english) {
            assertTrue("'$label' must count as English", isEnglishSubtitleLabel(label))
        }

        val other = listOf("Spanish", "es", "spa", "French [Forced]", "Portuguese (BR)", "Japanese", "ja", "", null)
        for (label in other) {
            assertFalse("'$label' must not count as English", isEnglishSubtitleLabel(label))
        }
    }

    @Test
    fun `a missing label defaults to English like the rest of the pipeline`() {
        // cleanSubtitleLabel maps a null language to "English", which is the contract the app's
        // downloader relies on: an unlabelled track is an English track.
        assertTrue(isEnglishSubtitleLabel(cleanSubtitleLabel(null)))
    }

    @Test
    fun `the extra condition satisfies the completion controller`() {
        val englishSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val satisfiedCallbacks = AtomicInteger(0)

        val controller = EarlySatisfactionController(
            EarlySatisfactionConfig(
                // Link/quality satisfaction deliberately unreachable, exactly as in the phase.
                minVerifiedLinks = Int.MAX_VALUE,
                minQualityStreams = Int.MAX_VALUE
            ),
            extraCondition = { englishSources.size >= TARGET_ENGLISH_SUBTITLE_SOURCES }
        )
        controller.onSatisfiedCallback = { satisfiedCallbacks.incrementAndGet() }

        assertFalse(controller.isSatisfied())

        englishSources.addAll(listOf("vidlink", "cineby", "cinesrc", "megaplay"))
        assertFalse("Four sources are not enough", controller.isSatisfied())

        englishSources.add("animex")
        assertTrue("Five distinct sources satisfy the phase", controller.isSatisfied())
        assertEquals("The satisfaction callback fires exactly once", 1, satisfiedCallbacks.get())

        assertTrue(controller.checkSatisfaction())
        assertEquals("Repeat checks must not re-fire the callback", 1, satisfiedCallbacks.get())
    }

    @Test
    fun `the completion phase cancels sources that are still running`() = runBlocking {
        val englishSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val cancelled = AtomicInteger(0)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = Int.MAX_VALUE,
            minQualityStreams = Int.MAX_VALUE,
            minSubtitles = 1,
            requireSubtitles = false,
            satisfyWithOneLinkIfSubsFound = false,
            tier1DelayMs = 0L,
            maxPipelineTimeoutMs = 15_000L,
            postSatisfactionGraceMs = 0L
        )
        val controller = EarlySatisfactionController(
            config,
            extraCondition = { englishSources.size >= TARGET_ENGLISH_SUBTITLE_SOURCES }
        )

        val tasks = buildList {
            add(PipelinedTask("fast_subtitle_source", LatencyTier.TIER_1, isVideo = false, priorityBoost = 100f) {
                repeat(TARGET_ENGLISH_SUBTITLE_SOURCES) { index ->
                    englishSources.add("source$index")
                }
                controller.onSubtitleEmitted(createSubtitle("English", "https://example.com/sub.vtt"))
            })
            repeat(4) { id ->
                add(PipelinedTask("lagging_$id", LatencyTier.TIER_1) {
                    try {
                        delay(3_000)
                    } catch (e: CancellationException) {
                        cancelled.incrementAndGet()
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
        assertTrue("Enough English sources satisfy the phase", controller.isSatisfied())
        assertTrue(
            "The phase must end as soon as enough sources answered (took ${elapsed}ms)",
            elapsed < 1_500L
        )
    }

    @Test
    fun `an unsatisfied completion phase runs to its timeout with the remaining sources`() = runBlocking {
        val englishSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val executed = AtomicInteger(0)

        val config = EarlySatisfactionConfig(
            minVerifiedLinks = Int.MAX_VALUE,
            minQualityStreams = Int.MAX_VALUE,
            minSubtitles = 1,
            requireSubtitles = false,
            tier1DelayMs = 0L,
            maxPipelineTimeoutMs = 1_500L,
            postSatisfactionGraceMs = 0L
        )
        val controller = EarlySatisfactionController(
            config,
            extraCondition = { englishSources.size >= TARGET_ENGLISH_SUBTITLE_SOURCES }
        )

        val tasks = listOf(
            PipelinedTask("vidlink", LatencyTier.TIER_1, isVideo = false, priorityBoost = 100f) {
                executed.incrementAndGet()
                englishSources.add("vidlink")
                controller.onSubtitleEmitted(createSubtitle("Spanish", "https://example.com/es1.vtt"))
            },
            PipelinedTask("cineby", LatencyTier.TIER_1, isVideo = false, priorityBoost = 95f) {
                executed.incrementAndGet()
                englishSources.add("cineby")
                controller.onSubtitleEmitted(createSubtitle("Spanish", "https://example.com/es2.vtt"))
            }
        )

        val result = SpeculativePipeliner.executePipelined(
            tasks = tasks,
            config = config,
            controller = controller
        )

        assertTrue("Execution must succeed", result)
        assertEquals("Every remaining source is asked once", 2, executed.get())
        assertFalse("Two sources do not satisfy the five source target", controller.isSatisfied())
    }
}
