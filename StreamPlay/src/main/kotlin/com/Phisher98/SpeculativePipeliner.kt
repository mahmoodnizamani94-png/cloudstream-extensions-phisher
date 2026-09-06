package com.phisher98

import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Latency Tier Definitions (§R1)
 */
enum class LatencyTier(val index: Int, val maxLatencyMs: Long) {
    TIER_0(0, 500L),        // Instant Playback / Hot Cache / Direct JSON (<500ms)
    TIER_1(1, 1500L),       // Fast Resolvers (500ms - 1500ms)
    TIER_2(2, 3500L),       // Deep Scrapers (1500ms - 3500ms)
    TIER_3(3, Long.MAX_VALUE); // Edge Fallback / Canary Probes (>3500ms)

    companion object {
        fun fromLatency(latencyMs: Float, isCanary: Boolean = false): LatencyTier {
            if (isCanary) return TIER_3
            return when {
                latencyMs < 500f -> TIER_0
                latencyMs < 1500f -> TIER_1
                latencyMs < 3500f -> TIER_2
                else -> TIER_3
            }
        }
    }
}

/**
 * Pipelined Task Definition
 */
data class PipelinedTask(
    val providerId: String,
    val initialTier: LatencyTier? = null,
    val isVideo: Boolean = true,
    val taskTimeoutMs: Long? = null,
    val priorityBoost: Float = 0f,
    val execute: suspend () -> Unit
)

/**
 * Configuration for Early Satisfaction & Staggered Timing
 */
data class EarlySatisfactionConfig(
    val minVerifiedLinks: Int = 2,
    val minQualityStreams: Int = 1,
    val qualityThreshold: Int = Qualities.P1080.value,
    val highBitrateThresholdKbps: Int = 2500,
    val minSubtitles: Int = 1,
    val satisfyWithOneLinkIfSubsFound: Boolean = true,
    val requireSubtitles: Boolean = false,
    val tier1DelayMs: Long = 150L,
    val tier2DelayMs: Long = 1500L,
    val tier3DelayMs: Long = 3500L,
    val checkIntervalMs: Long = 20L
)

/**
 * Controller tracking stream link acquisitions and judging early satisfaction
 */
class EarlySatisfactionController(
    val config: EarlySatisfactionConfig = EarlySatisfactionConfig()
) {
    private val linksFound = AtomicInteger(0)
    private val qualityLinksFound = AtomicInteger(0)
    private val subtitlesFound = AtomicInteger(0)
    private val satisfied = AtomicBoolean(false)

    fun onLinkEmitted(link: ExtractorLink): Boolean {
        linksFound.incrementAndGet()
        if (isHighQualityVerifiedStream(link)) {
            qualityLinksFound.incrementAndGet()
        }
        checkSatisfaction()
        return isSatisfied()
    }

    fun onSubtitleEmitted(subtitle: SubtitleFile): Boolean {
        subtitlesFound.incrementAndGet()
        checkSatisfaction()
        return isSatisfied()
    }

    fun isHighQualityVerifiedStream(link: ExtractorLink): Boolean {
        val quality = link.quality
        val bitrateKbps = StreamLinkOptimizer.parseBitrateKbpsFromText(link.name) ?: 0L
        val name = link.name
        val url = link.url
        return quality >= config.qualityThreshold ||
            quality >= Qualities.P1080.value ||
            bitrateKbps >= config.highBitrateThresholdKbps ||
            name.contains("1080", ignoreCase = true) ||
            name.contains("4k", ignoreCase = true) ||
            name.contains("2160", ignoreCase = true) ||
            (quality >= Qualities.P720.value && (
                url.contains("pixeldrain", ignoreCase = true) ||
                url.contains("febbox", ignoreCase = true) ||
                url.contains("debrid", ignoreCase = true) ||
                url.contains("real-debrid", ignoreCase = true)
            ))
    }

    fun checkSatisfaction(): Boolean {
        if (satisfied.get()) return true

        val links = linksFound.get()
        val qualityLinks = qualityLinksFound.get()
        val subs = subtitlesFound.get()

        val isEarlySatisfied = when {
            // 1. 1 high-bitrate/quality stream (>=1080p)
            qualityLinks >= config.minQualityStreams -> true
            // 2. 2 verified streams
            links >= config.minVerifiedLinks && (!config.requireSubtitles || subs >= config.minSubtitles) -> true
            // 3. 1 stream + subtitles (if satisfyWithOneLinkIfSubsFound enabled)
            config.satisfyWithOneLinkIfSubsFound && links >= 1 && subs >= config.minSubtitles -> true
            // 4. Hard ceiling
            links >= 4 -> true
            else -> false
        }

        if (isEarlySatisfied) {
            satisfied.compareAndSet(false, true)
        }
        return satisfied.get()
    }

    fun isSatisfied(): Boolean = satisfied.get() || checkSatisfaction()
    fun markSatisfied() { satisfied.set(true) }
    fun getLinksCount(): Int = linksFound.get()
    fun getQualityLinksCount(): Int = qualityLinksFound.get()
    fun getSubtitlesCount(): Int = subtitlesFound.get()
    fun reset() {
        linksFound.set(0)
        qualityLinksFound.set(0)
        subtitlesFound.set(0)
        satisfied.set(false)
    }
}

/**
 * State-Of-The-Art Speculative Scraper Pipelining Engine
 */
object SpeculativePipeliner {
    private const val TAG = "SpeculativePipeliner"

    val STATIC_COLD_START_TIERS: Map<String, LatencyTier> = mapOf(
        // Tier 0: Direct cache / instant player
        "streamplay_cache" to LatencyTier.TIER_0,
        "febbox_player" to LatencyTier.TIER_0,
        "debrid_cache" to LatencyTier.TIER_0,

        // Tier 1: Fast reliable JSON APIs & resolvers
        "vidsrcxyz" to LatencyTier.TIER_1,
        "rivestream" to LatencyTier.TIER_1,
        "vidlink" to LatencyTier.TIER_1,
        "vidfast" to LatencyTier.TIER_1,
        "moviesapi" to LatencyTier.TIER_1,
        "HexaSU" to LatencyTier.TIER_1,
        "superstream" to LatencyTier.TIER_1,
        "SuperStream" to LatencyTier.TIER_1,
        "moviebox" to LatencyTier.TIER_1,
        "vidzeeapi" to LatencyTier.TIER_1,
        "2Embed" to LatencyTier.TIER_1,
        "Hianime" to LatencyTier.TIER_1,
        "hianime" to LatencyTier.TIER_1,
        "Animepahe" to LatencyTier.TIER_1,
        "animepahe" to LatencyTier.TIER_1,
        "Anizone" to LatencyTier.TIER_1,
        "anizone" to LatencyTier.TIER_1,
        "SubtitleAPI" to LatencyTier.TIER_1,
        "WyZIESUB" to LatencyTier.TIER_1,
        "stremio_addon" to LatencyTier.TIER_1,
        "Watchsomuch" to LatencyTier.TIER_1,
        "OpenSubs" to LatencyTier.TIER_1,

        // Tier 2: Multi-step scrapers
        "uhdmovies" to LatencyTier.TIER_2,
        "vegamovies" to LatencyTier.TIER_2,
        "moviesmod" to LatencyTier.TIER_2,
        "topmovies" to LatencyTier.TIER_2,
        "bollyflix" to LatencyTier.TIER_2,
        "hdhub4u" to LatencyTier.TIER_2,
        "4khdhub" to LatencyTier.TIER_2,
        "hdmovie2" to LatencyTier.TIER_2,
        "CinemaCity" to LatencyTier.TIER_2,
        "Movies4u" to LatencyTier.TIER_2,
        "M4uhd" to LatencyTier.TIER_2,
        "CineVood" to LatencyTier.TIER_2,
        "DooFlix" to LatencyTier.TIER_2,
        "vaplayer" to LatencyTier.TIER_2,
        "Dudefilms" to LatencyTier.TIER_2,
        "Zinkmovies" to LatencyTier.TIER_2,
        "Peachify" to LatencyTier.TIER_2,
        "Anikage" to LatencyTier.TIER_2,
        "Anichi" to LatencyTier.TIER_2,
        "KickAssAnime" to LatencyTier.TIER_2,
        "Animex" to LatencyTier.TIER_2,
        "Animetosho" to LatencyTier.TIER_2,
        "ReAnime" to LatencyTier.TIER_2,
        "AllMovieland" to LatencyTier.TIER_2,
        "AllMovielandMediaProvider" to LatencyTier.TIER_2,
        "allmovieland" to LatencyTier.TIER_2,
        "SuperStreamFebbox" to LatencyTier.TIER_2,

        // Tier 3: Slow / edge fallback
        "tokyoinsider" to LatencyTier.TIER_3,
        "kisskh" to LatencyTier.TIER_3,
        "dahmermovies" to LatencyTier.TIER_3,
        "Hindmoviez" to LatencyTier.TIER_3,
        "Filmyfiy" to LatencyTier.TIER_3,
        "Xpass" to LatencyTier.TIER_3,
        "nepu" to LatencyTier.TIER_3,
        "moviesdrive" to LatencyTier.TIER_3,
        "Anineko" to LatencyTier.TIER_3
    )

    fun classifyProvider(providerId: String, initialTier: LatencyTier? = null): LatencyTier {
        if (ProviderTelemetryManager.isRecovering(providerId) || ProviderTelemetryManager.isCanary(providerId)) {
            return LatencyTier.TIER_3
        }
        if (ProviderTelemetryManager.isCircuitBroken(providerId)) {
            return LatencyTier.TIER_3
        }
        val stats = ProviderTelemetryManager.getStats(providerId)
        if (stats.executionCount == 0) {
            return initialTier ?: STATIC_COLD_START_TIERS[providerId] ?: LatencyTier.TIER_1
        }
        if (stats.consecutiveFailures >= 2) {
            return LatencyTier.TIER_3
        }
        return LatencyTier.fromLatency(stats.latencyEwma, isCanary = false)
    }

    suspend fun executePipelined(
        tasks: List<PipelinedTask>,
        config: EarlySatisfactionConfig = EarlySatisfactionConfig(),
        controller: EarlySatisfactionController = EarlySatisfactionController(config),
        context: Context? = null,
        maxConcurrencyOverride: Int? = null
    ): Boolean = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope false

        val activeConcurrency = (maxConcurrencyOverride ?: DeviceProfiler.getActiveConcurrency(context))
            .coerceIn(2, 64)
            .coerceAtMost(tasks.size)
        val semaphore = Semaphore(activeConcurrency)

        // Group tasks into tiers, and sort tasks within each tier by priority score descending
        val classifiedTasks = tasks.map { task ->
            val tier = classifyProvider(task.providerId, task.initialTier)
            val score = ProviderTelemetryManager.getPriorityScore(task.providerId) + task.priorityBoost
            Triple(task, tier, score)
        }

        val tier0Tasks = classifiedTasks.filter { it.second == LatencyTier.TIER_0 }
            .sortedByDescending { it.third }.map { it.first }
        val tier1Tasks = classifiedTasks.filter { it.second == LatencyTier.TIER_1 }
            .sortedByDescending { it.third }.map { it.first }
        val tier2Tasks = classifiedTasks.filter { it.second == LatencyTier.TIER_2 }
            .sortedByDescending { it.third }.map { it.first }
        val tier3Tasks = classifiedTasks.filter { it.second == LatencyTier.TIER_3 }
            .sortedByDescending { it.third }.map { it.first }

        val activeJobs = CopyOnWriteArrayList<Job>()

        fun cancelAllActiveJobs() {
            for (job in activeJobs) {
                if (job.isActive) {
                    job.cancel(CancellationException("Early satisfaction achieved"))
                }
            }
        }

        fun launchTaskGroup(taskList: List<PipelinedTask>): List<Job> {
            if (controller.isSatisfied()) return emptyList()
            return taskList.map { task ->
                launch(Dispatchers.IO) {
                    if (controller.isSatisfied()) return@launch
                    if (!ProviderTelemetryManager.canExecute(task.providerId)) {
                        Log.d(TAG, "Circuit breaker: skipping open provider ${task.providerId}")
                        return@launch
                    }

                    val timeout = task.taskTimeoutMs ?: 25_000L
                    var wasCancelled = false
                    val start = System.currentTimeMillis()
                    var success = false
                    val beforeLinks = controller.getLinksCount()
                    val beforeSubs = controller.getSubtitlesCount()

                    try {
                        semaphore.withPermit {
                            if (controller.isSatisfied()) {
                                throw CancellationException("Early satisfaction achieved")
                            }
                            withTimeoutOrNull(timeout.milliseconds) {
                                task.execute()
                            }
                            success = if (task.isVideo) {
                                controller.getLinksCount() > beforeLinks
                            } else {
                                controller.getSubtitlesCount() > beforeSubs
                            }
                        }
                    } catch (e: CancellationException) {
                        wasCancelled = true
                        throw e
                    } catch (t: Throwable) {
                        success = false
                        Log.w(TAG, "Task ${task.providerId} failed: ${t.message}")
                    } finally {
                        if (!wasCancelled && (!controller.isSatisfied() || success)) {
                            val duration = System.currentTimeMillis() - start
                            ProviderTelemetryManager.recordExecution(task.providerId, success, duration)
                        } else {
                            ProviderTelemetryManager.releaseCanaryPermit(task.providerId)
                        }
                        if (controller.isSatisfied()) {
                            cancelAllActiveJobs()
                        }
                    }
                }.also { activeJobs.add(it) }
            }
        }

        suspend fun delayUnlessSatisfied(delayMs: Long) {
            if (delayMs <= 0L) return
            val step = 10L
            var elapsed = 0L
            while (elapsed < delayMs && !controller.isSatisfied()) {
                val sleepTime = minOf(step, delayMs - elapsed)
                delay(sleepTime.milliseconds)
                elapsed += sleepTime
            }
        }

        val satisfactionWatcher = launch {
            while (isActive && !controller.isSatisfied()) {
                delay(config.checkIntervalMs.milliseconds)
            }
            if (controller.isSatisfied()) {
                cancelAllActiveJobs()
            }
        }

        val pipelineStartTime = System.currentTimeMillis()

        try {
            // Stage 0: Launch Tier 0 immediately (T = 0ms)
            launchTaskGroup(tier0Tasks)

            // Stage 1: Launch Tier 1 after tier1DelayMs (150ms)
            if (!controller.isSatisfied() && tier1Tasks.isNotEmpty()) {
                delayUnlessSatisfied(config.tier1DelayMs)
                if (!controller.isSatisfied()) {
                    launchTaskGroup(tier1Tasks)
                }
            }

            // Stage 2: Launch Tier 2 after tier2DelayMs (1500ms)
            if (!controller.isSatisfied() && tier2Tasks.isNotEmpty()) {
                val elapsed = System.currentTimeMillis() - pipelineStartTime
                val delayToTier2 = (config.tier2DelayMs - elapsed).coerceAtLeast(0L)
                delayUnlessSatisfied(delayToTier2)
                if (!controller.isSatisfied()) {
                    launchTaskGroup(tier2Tasks)
                }
            }

            // Stage 3: Launch Tier 3 after tier3DelayMs (3500ms) only if earlier tiers yielded 0 links!
            if (!controller.isSatisfied() && tier3Tasks.isNotEmpty() && controller.getLinksCount() == 0) {
                val elapsed2 = System.currentTimeMillis() - pipelineStartTime
                val delayToTier3 = (config.tier3DelayMs - elapsed2).coerceAtLeast(0L)
                delayUnlessSatisfied(delayToTier3)
                if (!controller.isSatisfied() && controller.getLinksCount() == 0) {
                    launchTaskGroup(tier3Tasks)
                }
            }

            activeJobs.toList().joinAll()
        } finally {
            satisfactionWatcher.cancel()
            cancelAllActiveJobs()
        }

        controller.getLinksCount() > 0 || controller.getSubtitlesCount() > 0
    }
}
