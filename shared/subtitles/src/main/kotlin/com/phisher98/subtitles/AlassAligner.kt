package com.phisher98.subtitles

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private object Rfc4648Base64 {
    private const val ENCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        for (i in ENCODE_ALPHABET.indices) {
            this[ENCODE_ALPHABET[i].code] = i
        }
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ENCODE_ALPHABET[(n ushr 18) and 0x3F])
            out.append(ENCODE_ALPHABET[(n ushr 12) and 0x3F])
            out.append(ENCODE_ALPHABET[(n ushr 6) and 0x3F])
            out.append(ENCODE_ALPHABET[n and 0x3F])
            i += 3
        }
        val rem = bytes.size - i
        if (rem == 1) {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            out.append(ENCODE_ALPHABET[(n ushr 18) and 0x3F])
            out.append(ENCODE_ALPHABET[(n ushr 12) and 0x3F])
            out.append("==")
        } else if (rem == 2) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out.append(ENCODE_ALPHABET[(n ushr 18) and 0x3F])
            out.append(ENCODE_ALPHABET[(n ushr 12) and 0x3F])
            out.append(ENCODE_ALPHABET[(n ushr 6) and 0x3F])
            out.append('=')
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        val cleaned = input.trim()
        if (cleaned.isEmpty()) return ByteArray(0)
        require(cleaned.length % 4 != 1) { "Invalid Base64 length: ${cleaned.length}" }
        val out = ByteArrayOutputStream((cleaned.length * 3) / 4)
        var buffer = 0
        var sextets = 0
        var seenPad = false
        for (idx in cleaned.indices) {
            val ch = cleaned[idx]
            if (ch == '=') {
                seenPad = true
                continue
            }
            require(!seenPad) { "Invalid character after Base64 padding at $idx" }
            val code = ch.code
            val v = if (code in 0..127) DECODE_TABLE[code] else -1
            require(v >= 0) { "Illegal Base64 character '$ch' at $idx" }
            buffer = (buffer shl 6) or v
            sextets++
            if (sextets == 4) {
                out.write((buffer ushr 16) and 0xFF)
                out.write((buffer ushr 8) and 0xFF)
                out.write(buffer and 0xFF)
                buffer = 0
                sextets = 0
            }
        }
        when (sextets) {
            1 -> throw IllegalArgumentException("Truncated Base64 quantum")
            2 -> out.write((buffer ushr 4) and 0xFF)
            3 -> {
                out.write((buffer ushr 10) and 0xFF)
                out.write((buffer ushr 2) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

data class ObservedWindow(val startMs: Long, val endMs: Long) {
    init {
        require(endMs > startMs && startMs >= 0L) {
            "Invalid ObservedWindow [$startMs, $endMs)"
        }
    }

    val durationMs: Long get() = endMs - startMs
}

data class SpeechSpan(
    val startMs: Long,
    val endMs: Long,
    val confidenceWeight: Float = 1.0f,
) {
    init {
        require(endMs >= startMs && startMs >= 0L) {
            "Invalid SpeechSpan [$startMs, $endMs)"
        }
    }
}

data class SpeechActivityTimeline(
    val speechSpans: List<SpeechSpan>,
    val observedWindows: List<ObservedWindow>,
    val mediaDurationMs: Long? = null,
) {
    val totalObservedMs: Long get() = observedWindows.sumOf { it.durationMs }
}

object SpeechTimelineCodec {
    private const val WIRE_VERSION: Int = 1
    private const val FLAG_HAS_DURATION: Int = 1

    private fun writeVarLong(out: ByteArrayOutputStream, value: Long) {
        var v = value.coerceAtLeast(0L)
        while ((v and -0x80L) != 0L) {
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
        out.write(v.toInt() and 0x7F)
    }

    private class VarReader(private val bytes: ByteArray) {
        var pos: Int = 0

        fun readVarLong(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw IllegalArgumentException("Truncated varint in SpeechTimelineCodec")
        }
    }

    private fun normalizeObservedWindows(windows: List<ObservedWindow>): List<ObservedWindow> {
        if (windows.size <= 1) return windows
        val sorted = windows.sortedBy { it.startMs }
        val merged = ArrayList<ObservedWindow>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            cur = if (next.startMs <= cur.endMs) {
                ObservedWindow(cur.startMs, max(cur.endMs, next.endMs))
            } else {
                merged.add(cur)
                next
            }
        }
        merged.add(cur)
        return merged
    }

    fun encodeCompact(timeline: SpeechActivityTimeline): String {
        val out = ByteArrayOutputStream(256)
        out.write(WIRE_VERSION)
        val mediaDur = timeline.mediaDurationMs
        val hasDuration = mediaDur != null && mediaDur >= 0L
        out.write(if (hasDuration) FLAG_HAS_DURATION else 0)
        if (mediaDur != null && mediaDur >= 0L) {
            writeVarLong(out, mediaDur)
        }
        val sortedWindows = normalizeObservedWindows(timeline.observedWindows)
        writeVarLong(out, sortedWindows.size.toLong())
        var prevWinEnd = 0L
        for (win in sortedWindows) {
            writeVarLong(out, (win.startMs - prevWinEnd).coerceAtLeast(0L))
            writeVarLong(out, win.durationMs)
            prevWinEnd = win.endMs
        }
        val sortedSpans = timeline.speechSpans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        writeVarLong(out, sortedSpans.size.toLong())
        var prevSpanStart = 0L
        for (sp in sortedSpans) {
            writeVarLong(out, (sp.startMs - prevSpanStart).coerceAtLeast(0L))
            writeVarLong(out, sp.endMs - sp.startMs)
            val qWeight = (sp.confidenceWeight.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            out.write(qWeight)
            prevSpanStart = sp.startMs
        }
        return Rfc4648Base64.encode(out.toByteArray())
    }

    fun decodeCompact(blob: String): Result<SpeechActivityTimeline> = runCatching {
        val trimmed = blob.trim()
        require(trimmed.isNotEmpty()) { "Empty speech timeline payload" }
        val raw = Rfc4648Base64.decode(trimmed)
        require(raw.size >= 2 && (raw[0].toInt() and 0xFF) == WIRE_VERSION) {
            "Unsupported speech timeline version"
        }
        val flags = raw[1].toInt() and 0xFF
        val reader = VarReader(raw).also { it.pos = 2 }
        val mediaDurationMs = if ((flags and FLAG_HAS_DURATION) != 0) reader.readVarLong() else null
        val winCount = reader.readVarLong().toInt()
        require(winCount in 0..10_000) { "Invalid window count $winCount" }
        val windows = ArrayList<ObservedWindow>(winCount)
        var prevWinEnd = 0L
        repeat(winCount) {
            val start = prevWinEnd + reader.readVarLong()
            val dur = reader.readVarLong()
            val end = start + dur
            if (end > start) windows.add(ObservedWindow(start, end))
            prevWinEnd = end
        }
        val spanCount = reader.readVarLong().toInt()
        require(spanCount in 0..200_000) { "Invalid span count $spanCount" }
        val spans = ArrayList<SpeechSpan>(spanCount)
        var prevSpanStart = 0L
        repeat(spanCount) {
            val start = prevSpanStart + reader.readVarLong()
            val dur = reader.readVarLong()
            require(reader.pos < raw.size) { "Truncated weight byte" }
            val weight = (raw[reader.pos++].toInt() and 0xFF) / 255f
            val end = start + dur
            if (end > start) spans.add(SpeechSpan(start, end, weight))
            prevSpanStart = start
        }
        SpeechActivityTimeline(speechSpans = spans, observedWindows = windows, mediaDurationMs = mediaDurationMs)
    }
}

data class AlassConfig(
    val tickMs: Int = 10,
    val maxOffsetMs: Long = 200_000L,
    val subToSubSplitPenalty: Float = 7.0f,
    val audioVadSplitPenalty: Float = 8.5f,
    val edgeCuePenaltyMultiplier: Float = 1.35f,
    val jumpRegularizerPerTick: Float = 2e-4f,
    val nonUnityFpsPriorPenalty: Float = 0.015f,
    val sparseSilencePenaltyWeight: Float = 0.25f,
    val preSpeechLeadMs: Long = 35L,
    val minSegmentCuesForOnsetPolish: Int = 8,
    val minConfidenceToAdmit: Double = 0.42,
    val hardRejectCeiling: Double = 0.08,
)

sealed interface AlignmentOutcome {
    data class Aligned(val proof: AlignmentProof) : AlignmentOutcome
    data class RejectedMismatch(val bestScore: Double, val residualMs: Int) : AlignmentOutcome
}

data class NoSplitFpsResult(
    val fpsRatio: FpsRatio,
    val bestOffsetMs: Long,
    val rawScore: Double,
    val penalizedNormalizedScore: Double,
)

object AlassAligner {
    private const val FP_UNIT: Double = 1_048_576.0
    private const val DP_TICK_STRIDE: Int = 10
    private const val HIST_BIN_MS: Long = 50L
    private const val MAX_CANDIDATE_OFFSETS: Int = 48

    private class WeightedTickSpans(
        val startTick: IntArray,
        val endTick: IntArray,
        val weight: FloatArray,
        val onsetMs: LongArray,
        val isPositiveSpeech: BooleanArray,
    ) {
        val size: Int get() = startTick.size
    }

    private data class DpSegment(
        val firstCueIdx: Int,
        val lastCueIdx: Int,
        val coarseOffsetMs: Long,
        var refinedOffsetMs: Long,
        val meanCueScore: Double,
    ) {
        val cueCount: Int get() = lastCueIdx - firstCueIdx + 1
    }

    private data class DpCandidateSolution(
        val fpsRatio: FpsRatio,
        val rawTotalScore: Double,
        val regularizedScore: Double,
        val cueOffsetsMs: LongArray,
        val perCueScore: FloatArray,
    )

    private data class CoarseSweepProfile(
        val result: NoSplitFpsResult,
        val topPeakOffsetsMs: LongArray,
    )

    private fun addRampFp(
        dd: IntArray,
        gridSize: Int,
        knotIdx: Int,
        slopeFp: Int,
        v0Acc: LongArray,
    ) {
        if (slopeFp == 0 || knotIdx >= gridSize - 1) return
        if (knotIdx >= 0) {
            dd[knotIdx + 1] += slopeFp
        } else {
            v0Acc[0] += (-knotIdx).toLong() * slopeFp.toLong()
            if (gridSize > 1) {
                dd[1] += slopeFp
            }
        }
    }

    private fun accumulatePairTrapezoidFp(
        dd: IntArray,
        v0Acc: LongArray,
        gridSize: Int,
        maxOffsetTicks: Int,
        candStart: Int,
        candEnd: Int,
        refStart: Int,
        refEnd: Int,
        slopeFp: Int,
    ) {
        if (slopeFp == 0) return
        val x1 = refStart - candEnd
        val x4 = refEnd - candStart
        if (x4 <= -maxOffsetTicks || x1 >= maxOffsetTicks) return
        val x2 = min(refStart - candStart, refEnd - candEnd)
        if (x2 <= x1) return
        val x3 = max(refStart - candStart, refEnd - candEnd)

        addRampFp(dd, gridSize, x1 + maxOffsetTicks, slopeFp, v0Acc)
        addRampFp(dd, gridSize, x2 + maxOffsetTicks, -slopeFp, v0Acc)
        addRampFp(dd, gridSize, x3 + maxOffsetTicks, -slopeFp, v0Acc)
        addRampFp(dd, gridSize, x4 + maxOffsetTicks, slopeFp, v0Acc)
    }

    fun alignNoSplit(
        candidate: CueDocument,
        anchor: CueDocument,
        config: AlassConfig = AlassConfig(),
    ): List<NoSplitFpsResult> {
        val refSpans = buildAnchorTickSpans(anchor, config.tickMs)
        return sweepAllFpsNoSplit(candidate, refSpans, observedWindows = null, config = config)
    }

    fun alignToAnchorCues(
        candidate: CueDocument,
        anchor: CueDocument,
        anchorKind: AlignmentAnchorKind = AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR,
        videoDurationMs: Long? = null,
        config: AlassConfig = AlassConfig(),
    ): AlignmentOutcome {
        if (candidate.cues.isEmpty() || anchor.cues.isEmpty()) {
            return AlignmentOutcome.RejectedMismatch(0.0, Int.MAX_VALUE)
        }
        val refSpans = buildAnchorTickSpans(anchor, config.tickMs)
        return runFullAlignment(
            candidate = candidate,
            refSpans = refSpans,
            observedWindows = null,
            anchorKind = anchorKind,
            splitPenalty = config.subToSubSplitPenalty,
            regularizerScale = 1.0f,
            overlapModeInDp = false,
            config = config,
        )
    }

    fun alignToSparseSpeech(
        candidate: CueDocument,
        timeline: SpeechActivityTimeline,
        anchorKind: AlignmentAnchorKind = AlignmentAnchorKind.SPARSE_AUDIO_VAD,
        config: AlassConfig = AlassConfig(),
    ): AlignmentOutcome {
        if (candidate.cues.isEmpty() || timeline.observedWindows.isEmpty() || timeline.speechSpans.isEmpty()) {
            return AlignmentOutcome.RejectedMismatch(0.0, Int.MAX_VALUE)
        }
        val mergedWindows = mergeObservedWindows(timeline.observedWindows)
        val refSpans = buildObservationMaskedSpeechSpans(
            speechSpans = timeline.speechSpans,
            observedWindows = mergedWindows,
            tickMs = config.tickMs,
            silencePenaltyWeight = config.sparseSilencePenaltyWeight,
            preSpeechLeadMs = config.preSpeechLeadMs,
        )
        if (refSpans.size == 0) {
            return AlignmentOutcome.RejectedMismatch(0.0, Int.MAX_VALUE)
        }
        val lastCueEndMs = candidate.cues.maxOfOrNull { it.span.endMs } ?: 0L
        val denomDurationMs = max(timeline.mediaDurationMs ?: 0L, lastCueEndMs).coerceAtLeast(1L)
        val observedMs = mergedWindows.sumOf { it.durationMs }
        val observedRatio = (observedMs.toDouble() / denomDurationMs.toDouble()).toFloat().coerceIn(0.05f, 1.0f)
        val effectiveSplitPenalty = if (observedRatio < 0.85f) {
            max(1.2f, config.audioVadSplitPenalty * observedRatio)
        } else {
            config.audioVadSplitPenalty
        }
        return runFullAlignment(
            candidate = candidate,
            refSpans = refSpans,
            observedWindows = mergedWindows,
            anchorKind = anchorKind,
            splitPenalty = effectiveSplitPenalty,
            regularizerScale = observedRatio,
            overlapModeInDp = true,
            config = config,
        )
    }

    private fun downsampleTickSpans(src: WeightedTickSpans, stride: Int): WeightedTickSpans {
        if (stride <= 1) return src
        val n = src.size
        val starts = IntArray(n)
        val ends = IntArray(n)
        for (i in 0 until n) {
            val s = src.startTick[i] / stride
            val e = max(s + 1, (src.endTick[i] + stride - 1) / stride)
            starts[i] = s
            ends[i] = e
        }
        return WeightedTickSpans(starts, ends, src.weight, src.onsetMs, src.isPositiveSpeech)
    }

    private fun arePositiveRefSpansDisjointUnitWeight(spans: WeightedTickSpans): Boolean {
        var prevEnd = Int.MIN_VALUE
        for (i in 0 until spans.size) {
            if (!spans.isPositiveSpeech[i]) continue
            if (spans.weight[i] > 1.0001f || spans.startTick[i] < prevEnd) return false
            prevEnd = spans.endTick[i]
        }
        return true
    }

    private fun tryCertifyOptimalNoSplit(
        candidate: CueDocument,
        bestNoSplit: NoSplitFpsResult,
        refSpans: WeightedTickSpans,
        splitPenalty: Float,
        overlapMode: Boolean,
        config: AlassConfig,
    ): DpCandidateSolution? {
        val cues = candidate.cues
        val n = cues.size
        if (n == 0 || !arePositiveRefSpansDisjointUnitWeight(refSpans)) return null
        val tickMs = config.tickMs.coerceAtLeast(1)
        val sTicks = IntArray(n)
        val eTicks = IntArray(n)
        val tickUs = tickMs * 1_000L
        for (i in 0 until n) {
            val s = (bestNoSplit.fpsRatio.scaleUs(cues[i].span.startMs * 1_000L) / tickUs).toInt()
            val e = max(s + 1, ((bestNoSplit.fpsRatio.scaleUs(cues[i].span.endMs * 1_000L) + tickUs - 1L) / tickUs).toInt())
            sTicks[i] = s
            eTicks[i] = e
        }
        val pathMs = LongArray(n) { bestNoSplit.bestOffsetMs }
        val perCue = computePerCueChosenScoresMs(
            sTicks = sTicks,
            eTicks = eTicks,
            chosenOffsetsMs = pathMs,
            tickMs = tickMs,
            refSpans = refSpans,
            overlapMode = overlapMode,
        )
        var rawSum = 0.0
        for (v in perCue) rawSum += v.toDouble()
        val fpsPenalty = if (bestNoSplit.fpsRatio == FpsRatio.UNITY) {
            0.0
        } else {
            config.nonUnityFpsPriorPenalty.toDouble() * n.toDouble()
        }
        val regScore = rawSum - fpsPenalty
        val maxPossibleSplitScore = n.toDouble() - splitPenalty.toDouble()
        if (regScore <= maxPossibleSplitScore + 0.05) return null
        return DpCandidateSolution(
            fpsRatio = bestNoSplit.fpsRatio,
            rawTotalScore = rawSum,
            regularizedScore = regScore,
            cueOffsetsMs = pathMs,
            perCueScore = perCue,
        )
    }

    private fun subsampleCuesForFpsScreen(cues: List<CueEntry>, maxSample: Int = 180): List<CueEntry> {
        if (cues.size <= maxSample) return cues
        val out = ArrayList<CueEntry>(maxSample)
        val last = cues.size - 1
        for (k in 0 until maxSample) {
            val idx = ((k.toLong() * last.toLong()) / (maxSample - 1).toLong()).toInt()
            out.add(cues[idx])
        }
        return out
    }

    private fun sweepSingleFpsProfile(
        cues: List<CueEntry>,
        fps: FpsRatio,
        refSpans: WeightedTickSpans,
        observedWindows: List<ObservedWindow>?,
        tickMs: Int,
        maxOffsetMs: Long,
        nonUnityPriorPenalty: Float,
        dd: IntArray,
        collectTopPeaks: Int = 0,
    ): CoarseSweepProfile {
        val maxOffsetTicks = (maxOffsetMs / tickMs).toInt().coerceAtLeast(1)
        val gridSize = 2 * maxOffsetTicks + 1
        val v0Acc = LongArray(1)
        val tickUs = tickMs * 1_000L
        val refStarts = refSpans.startTick
        val refEnds = refSpans.endTick
        val refWeights = refSpans.weight
        val refSize = refSpans.size
        val unitWeightOnly = observedWindows == null
        var rStart = 0
        for (i in cues.indices) {
            val cue = cues[i]
            val sTick = (fps.scaleUs(cue.span.startMs * 1_000L) / tickUs).toInt()
            val eTick = max(sTick + 1, (fps.scaleUs(cue.span.endMs * 1_000L) / tickUs).toInt())
            val ls = eTick - sTick
            val unitSlope = (FP_UNIT / ls.toDouble()).toFloat()
            val fixedSlopeFp = if (unitWeightOnly) unitSlope.roundToInt() else 0
            val minRefEnd = sTick - maxOffsetTicks
            val maxRefStart = eTick + maxOffsetTicks
            while (rStart < refSize && refEnds[rStart] <= minRefEnd) {
                rStart++
            }
            var j = rStart
            while (j < refSize && refStarts[j] < maxRefStart) {
                val rs = refStarts[j]
                val re = refEnds[j]
                if (re > rs) {
                    val slopeFp = if (unitWeightOnly) fixedSlopeFp else (refWeights[j] * unitSlope).roundToInt()
                    accumulatePairTrapezoidFp(
                        dd = dd,
                        v0Acc = v0Acc,
                        gridSize = gridSize,
                        maxOffsetTicks = maxOffsetTicks,
                        candStart = sTick,
                        candEnd = eTick,
                        refStart = rs,
                        refEnd = re,
                        slopeFp = slopeFp,
                    )
                }
                j++
            }
        }

        val profile = if (collectTopPeaks > 0) LongArray(gridSize) else null
        var v = v0Acc[0]
        var d = 0L
        dd[0] = 0
        if (profile != null) profile[0] = v
        var bestValFp = v
        var bestK = 0
        var bestDist = abs(0 - maxOffsetTicks)
        for (k in 1 until gridSize) {
            d += dd[k].toLong()
            dd[k] = 0
            v += d
            if (profile != null) profile[k] = v
            val dist = abs(k - maxOffsetTicks)
            if (v > bestValFp || (v == bestValFp && dist < bestDist)) {
                bestValFp = v
                bestK = k
                bestDist = dist
            }
        }

        val rawScore = (bestValFp.toDouble() / FP_UNIT).coerceAtLeast(0.0)
        val bestOffsetMs = (bestK - maxOffsetTicks).toLong() * tickMs.toLong()
        val normDenom = countObservedCuesAtOffset(cues, fps, bestOffsetMs, observedWindows)
            .coerceAtLeast(1).toDouble()
        val normalized = (rawScore / normDenom).coerceIn(0.0, 1.0)
        val penalized = if (fps == FpsRatio.UNITY) {
            normalized
        } else {
            normalized - nonUnityPriorPenalty.toDouble()
        }
        val peaks = if (profile != null && collectTopPeaks > 0) {
            extractTopLocalPeakOffsetsMs(profile, maxOffsetTicks, tickMs.toLong(), collectTopPeaks)
        } else {
            LongArray(0)
        }
        return CoarseSweepProfile(
            result = NoSplitFpsResult(
                fpsRatio = fps,
                bestOffsetMs = bestOffsetMs,
                rawScore = rawScore,
                penalizedNormalizedScore = penalized,
            ),
            topPeakOffsetsMs = peaks,
        )
    }

    private fun extractTopLocalPeakOffsetsMs(
        profile: LongArray,
        maxOffsetTicks: Int,
        tickMs: Long,
        limit: Int,
    ): LongArray {
        val n = profile.size
        if (n == 0 || limit <= 0) return LongArray(0)
        val candIdx = ArrayList<Int>(64)
        for (k in 0 until n) {
            val v = profile[k]
            if (v <= 0L) continue
            val leftOk = k == 0 || v >= profile[k - 1]
            val rightOk = k == n - 1 || v >= profile[k + 1]
            if (leftOk && rightOk) {
                candIdx.add(k)
            }
        }
        candIdx.sortByDescending { profile[it] }
        val chosen = ArrayList<Long>(limit)
        val chosenTicks = IntArray(limit)
        var count = 0
        for (k in candIdx) {
            if (count >= limit) break
            var tooClose = false
            for (c in 0 until count) {
                if (abs(chosenTicks[c] - k) <= 1) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose) {
                chosenTicks[count++] = k
                chosen.add((k - maxOffsetTicks).toLong() * tickMs)
            }
        }
        return chosen.toLongArray()
    }

    private fun runFullAlignment(
        candidate: CueDocument,
        refSpans: WeightedTickSpans,
        observedWindows: List<ObservedWindow>?,
        anchorKind: AlignmentAnchorKind,
        splitPenalty: Float,
        regularizerScale: Float,
        overlapModeInDp: Boolean,
        config: AlassConfig,
    ): AlignmentOutcome {
        val tickMs = config.tickMs.coerceAtLeast(1)
        val dpTickMs = tickMs * DP_TICK_STRIDE
        val dpMaxOffsetTicks = (config.maxOffsetMs / dpTickMs).toInt().coerceAtLeast(1)
        val dpGridSize = 2 * dpMaxOffsetTicks + 1
        val dpRefSpans = downsampleTickSpans(refSpans, DP_TICK_STRIDE)
        val dpDd = IntArray(dpGridSize)

        val screenCues = subsampleCuesForFpsScreen(candidate.cues, maxSample = 120)
        val unityProfile = sweepSingleFpsProfile(
            cues = screenCues,
            fps = FpsRatio.UNITY,
            refSpans = dpRefSpans,
            observedWindows = observedWindows,
            tickMs = dpTickMs,
            maxOffsetMs = config.maxOffsetMs,
            nonUnityPriorPenalty = config.nonUnityFpsPriorPenalty,
            dd = dpDd,
            collectTopPeaks = 10,
        )
        var bestNonUnityScreen: NoSplitFpsResult? = null
        for (fps in FpsRatio.entries) {
            if (fps == FpsRatio.UNITY) continue
            val prof = sweepSingleFpsProfile(
                cues = screenCues,
                fps = fps,
                refSpans = dpRefSpans,
                observedWindows = observedWindows,
                tickMs = dpTickMs,
                maxOffsetMs = config.maxOffsetMs,
                nonUnityPriorPenalty = config.nonUnityFpsPriorPenalty,
                dd = dpDd,
                collectTopPeaks = 0,
            ).result
            if (bestNonUnityScreen == null || prof.penalizedNormalizedScore > bestNonUnityScreen.penalizedNormalizedScore) {
                bestNonUnityScreen = prof
            }
        }

        val bestNonUnityProfile = if (bestNonUnityScreen != null &&
            bestNonUnityScreen.penalizedNormalizedScore > unityProfile.result.penalizedNormalizedScore
        ) {
            sweepSingleFpsProfile(
                cues = screenCues,
                fps = bestNonUnityScreen.fpsRatio,
                refSpans = dpRefSpans,
                observedWindows = observedWindows,
                tickMs = dpTickMs,
                maxOffsetMs = config.maxOffsetMs,
                nonUnityPriorPenalty = config.nonUnityFpsPriorPenalty,
                dd = dpDd,
                collectTopPeaks = 10,
            )
        } else {
            null
        }

        val unityNoSplit = unityProfile.result
        val bestNonUnity = bestNonUnityProfile?.result
        val bestNoSplit = if (bestNonUnity != null && bestNonUnity.penalizedNormalizedScore > unityNoSplit.penalizedNormalizedScore) {
            bestNonUnity
        } else {
            unityNoSplit
        }

        val certifiedNoSplit = if (observedWindows == null) {
            tryCertifyOptimalNoSplit(
                candidate = candidate,
                bestNoSplit = bestNoSplit,
                refSpans = refSpans,
                splitPenalty = splitPenalty,
                overlapMode = overlapModeInDp,
                config = config,
            )
        } else {
            null
        }

        val positiveOnsets = extractSortedPositiveOnsetsMs(refSpans)
        val chosenSol = if (certifiedNoSplit != null) {
            certifiedNoSplit
        } else {
            val unityScore = unityNoSplit.penalizedNormalizedScore
            val nonUnityScore = bestNonUnity?.penalizedNormalizedScore ?: Double.NEGATIVE_INFINITY

            fun solveForProfile(prof: CoarseSweepProfile): DpCandidateSolution {
                val candOffsets = discoverCandidateOffsetsMs(
                    candidate = candidate,
                    fpsRatio = prof.result.fpsRatio,
                    positiveOnsets = positiveOnsets,
                    observedWindows = observedWindows,
                    coarsePeaksMs = prof.topPeakOffsetsMs,
                    bestCoarseOffsetMs = prof.result.bestOffsetMs,
                    config = config,
                )
                return runPrunedPiecewiseDp(
                    candidate = candidate,
                    fpsRatio = prof.result.fpsRatio,
                    candOffsetsMs = candOffsets,
                    refSpans = refSpans,
                    observedWindows = observedWindows,
                    splitPenalty = splitPenalty,
                    regularizerScale = regularizerScale,
                    overlapMode = overlapModeInDp,
                    config = config,
                )
            }

            when {
                bestNonUnityProfile == null -> {
                    solveForProfile(unityProfile)
                }
                nonUnityScore > unityScore + 0.03 -> {
                    solveForProfile(bestNonUnityProfile)
                }
                else -> {
                    val unitySol = solveForProfile(unityProfile)
                    val nonUnitySol = solveForProfile(bestNonUnityProfile)
                    if (nonUnitySol.regularizedScore > unitySol.regularizedScore) nonUnitySol else unitySol
                }
            }
        }

        val segments = extractAndPolishSegments(
            candidate = candidate,
            solution = chosenSol,
            positiveOnsets = positiveOnsets,
            observedWindows = observedWindows,
            config = config,
        )

        val warp = buildWarpFromSegments(candidate, chosenSol.fpsRatio, segments)
        val metrics = evaluatePostWarpMetrics(
            candidate = candidate,
            warp = warp,
            refSpans = refSpans,
            positiveOnsets = positiveOnsets,
            observedWindows = observedWindows,
            tickMs = tickMs,
        )

        if (metrics.confidence < config.hardRejectCeiling ||
            (metrics.evaluatedCueCount >= 6 && metrics.confidence < config.minConfidenceToAdmit)
        ) {
            return AlignmentOutcome.RejectedMismatch(
                bestScore = metrics.confidence,
                residualMs = metrics.medianResidualMs,
            )
        }

        val splitCount = (segments.size - 1).coerceAtLeast(0)
        return AlignmentOutcome.Aligned(
            AlignmentProof(
                warp = warp,
                anchorKind = anchorKind,
                syncConfidence = metrics.confidence,
                medianOnsetResidualMs = metrics.medianResidualMs,
                p95OnsetResidualMs = metrics.p95ResidualMs,
                splitCount = splitCount,
            )
        )
    }

    private fun sweepAllFpsNoSplit(
        candidate: CueDocument,
        refSpans: WeightedTickSpans,
        observedWindows: List<ObservedWindow>?,
        config: AlassConfig,
        sharedDd: IntArray? = null,
    ): List<NoSplitFpsResult> {
        val tickMs = config.tickMs.coerceAtLeast(1)
        val maxOffsetTicks = (config.maxOffsetMs / tickMs).toInt().coerceAtLeast(1)
        val gridSize = 2 * maxOffsetTicks + 1
        val dd = sharedDd ?: IntArray(gridSize)
        val results = ArrayList<NoSplitFpsResult>(FpsRatio.entries.size)
        for (fps in FpsRatio.entries) {
            results.add(
                sweepSingleFpsProfile(
                    cues = candidate.cues,
                    fps = fps,
                    refSpans = refSpans,
                    observedWindows = observedWindows,
                    tickMs = tickMs,
                    maxOffsetMs = config.maxOffsetMs,
                    nonUnityPriorPenalty = config.nonUnityFpsPriorPenalty,
                    dd = dd,
                    collectTopPeaks = 0,
                ).result
            )
        }
        return results
    }

    private fun discoverCandidateOffsetsMs(
        candidate: CueDocument,
        fpsRatio: FpsRatio,
        positiveOnsets: LongArray,
        observedWindows: List<ObservedWindow>?,
        coarsePeaksMs: LongArray,
        bestCoarseOffsetMs: Long,
        config: AlassConfig,
    ): LongArray {
        val cues = candidate.cues
        val n = cues.size
        val maxOff = config.maxOffsetMs
        val halfBins = (maxOff / HIST_BIN_MS).toInt().coerceAtLeast(1)
        val numBins = 2 * halfBins + 1
        val globalHist = IntArray(numBins)
        val localHist = IntArray(numBins)

        val scaledStartsMs = LongArray(n)
        for (i in 0 until n) {
            scaledStartsMs[i] = (fpsRatio.scaleUs(cues[i].span.startMs * 1_000L) + 500L).floorDiv(1_000L)
        }

        val rawCandidates = ArrayList<Long>(256)
        fun pushClamped(ms: Long) {
            rawCandidates.add(ms.coerceIn(-maxOff, maxOff))
        }
        pushClamped(0L)
        pushClamped(bestCoarseOffsetMs)
        pushClamped(bestCoarseOffsetMs - HIST_BIN_MS)
        pushClamped(bestCoarseOffsetMs + HIST_BIN_MS)
        for (p in coarsePeaksMs) {
            pushClamped(p)
            pushClamped(p - HIST_BIN_MS)
            pushClamped(p + HIST_BIN_MS)
        }

        fun collectLocalPeaksFromHist(hist: IntArray, limit: Int, addNeighbors: Boolean) {
            val peakBins = ArrayList<Int>(32)
            for (b in 0 until numBins) {
                val v = hist[b]
                if (v <= 0) continue
                val leftOk = b == 0 || v >= hist[b - 1]
                val rightOk = b == numBins - 1 || v >= hist[b + 1]
                if (leftOk && rightOk) peakBins.add(b)
            }
            peakBins.sortByDescending { hist[it] }
            val picked = IntArray(limit)
            var count = 0
            for (b in peakBins) {
                if (count >= limit) break
                var near = false
                for (p in 0 until count) {
                    if (abs(picked[p] - b) <= 2) {
                        near = true
                        break
                    }
                }
                if (!near) {
                    picked[count++] = b
                    val centerMs = (b - halfBins).toLong() * HIST_BIN_MS
                    pushClamped(centerMs)
                    if (addNeighbors) {
                        pushClamped(centerMs - HIST_BIN_MS)
                        pushClamped(centerMs + HIST_BIN_MS)
                    }
                }
            }
        }

        if (positiveOnsets.isNotEmpty() && n > 0) {
            val sliceCount = min(8, max(1, n / 12))
            val cueStep = max(1, n / 150)
            var rStart = 0
            for (s in 0 until sliceCount) {
                val sIdx = (s * n) / sliceCount
                val eIdx = if (s == sliceCount - 1) n else ((s + 1) * n) / sliceCount
                localHist.fill(0)
                var i = sIdx
                while (i < eIdx) {
                    val cs = scaledStartsMs[i]
                    val minOnset = cs - maxOff
                    val maxOnset = cs + maxOff
                    while (rStart > 0 && positiveOnsets[rStart - 1] >= minOnset) rStart--
                    while (rStart < positiveOnsets.size && positiveOnsets[rStart] < minOnset) rStart++
                    var j = rStart
                    while (j < positiveOnsets.size && positiveOnsets[j] <= maxOnset) {
                        val diff = positiveOnsets[j] - cs
                        val b = ((diff + maxOff + (HIST_BIN_MS / 2L)) / HIST_BIN_MS).toInt().coerceIn(0, numBins - 1)
                        localHist[b] += 3
                        if (b > 0) localHist[b - 1] += 1
                        if (b + 1 < numBins) localHist[b + 1] += 1
                        globalHist[b] += 3
                        if (b > 0) globalHist[b - 1] += 1
                        if (b + 1 < numBins) globalHist[b + 1] += 1
                        j++
                    }
                    i += cueStep
                }
                collectLocalPeaksFromHist(localHist, limit = 6, addNeighbors = false)
            }
            collectLocalPeaksFromHist(globalHist, limit = 16, addNeighbors = true)

            if (observedWindows != null) {
                for (win in observedWindows) {
                    localHist.fill(0)
                    var hasAny = false
                    var oIdx = positiveOnsets.binarySearch(win.startMs - 50L).let { if (it < 0) -it - 1 else it }
                    while (oIdx < positiveOnsets.size && positiveOnsets[oIdx] <= win.endMs + 50L) {
                        val onset = positiveOnsets[oIdx]
                        val minCue = onset - maxOff
                        val maxCue = onset + maxOff
                        var cIdx = scaledStartsMs.binarySearch(minCue).let { if (it < 0) -it - 1 else it }
                        while (cIdx < n && scaledStartsMs[cIdx] <= maxCue) {
                            val diff = onset - scaledStartsMs[cIdx]
                            val b = ((diff + maxOff + (HIST_BIN_MS / 2L)) / HIST_BIN_MS).toInt().coerceIn(0, numBins - 1)
                            localHist[b] += 3
                            if (b > 0) localHist[b - 1] += 1
                            if (b + 1 < numBins) localHist[b + 1] += 1
                            hasAny = true
                            cIdx++
                        }
                        oIdx++
                    }
                    if (hasAny) {
                        collectLocalPeaksFromHist(localHist, limit = 6, addNeighbors = true)
                    }
                }
            }
        }

        val sortedAll = rawCandidates.toLongArray().also { it.sort() }
        val dedup = ArrayList<Long>(sortedAll.size)
        for (v in sortedAll) {
            if (dedup.isEmpty() || dedup.last() != v) {
                dedup.add(v)
            }
        }
        if (dedup.size <= MAX_CANDIDATE_OFFSETS) {
            return dedup.toLongArray()
        }

        val mandatory = HashSet<Long>()
        mandatory.add(0L)
        mandatory.add(bestCoarseOffsetMs.coerceIn(-maxOff, maxOff))
        for (p in coarsePeaksMs) mandatory.add(p.coerceIn(-maxOff, maxOff))

        val ranked = dedup.sortedByDescending { off ->
            val b = ((off + maxOff + (HIST_BIN_MS / 2L)) / HIST_BIN_MS).toInt().coerceIn(0, numBins - 1)
            (if (off in mandatory) 1_000_000 else 0) + globalHist[b]
        }.take(MAX_CANDIDATE_OFFSETS).toLongArray()
        ranked.sort()
        return ranked
    }

    private fun runPrunedPiecewiseDp(
        candidate: CueDocument,
        fpsRatio: FpsRatio,
        candOffsetsMs: LongArray,
        refSpans: WeightedTickSpans,
        observedWindows: List<ObservedWindow>?,
        splitPenalty: Float,
        regularizerScale: Float,
        overlapMode: Boolean,
        config: AlassConfig,
    ): DpCandidateSolution {
        val cues = candidate.cues
        val n = cues.size
        val mCount = candOffsetsMs.size
        val tickMs = config.tickMs.coerceAtLeast(1)
        val tickUs = tickMs * 1_000L
        val lambdaPerMs = (config.jumpRegularizerPerTick / tickMs.toFloat()) * regularizerScale

        val sTicks = IntArray(n)
        val eTicks = IntArray(n)
        val sMsArr = LongArray(n)
        val eMsArr = LongArray(n)
        for (i in 0 until n) {
            val sUs = fpsRatio.scaleUs(cues[i].span.startMs * 1_000L)
            val eUs = fpsRatio.scaleUs(cues[i].span.endMs * 1_000L)
            sMsArr[i] = (sUs + 500L).floorDiv(1_000L)
            eMsArr[i] = max(sMsArr[i] + 1L, (eUs + 500L).floorDiv(1_000L))
            sTicks[i] = (sUs / tickUs).toInt()
            eTicks[i] = max(sTicks[i] + 1, ((eUs + tickUs - 1L) / tickUs).toInt())
        }

        val candTicks = IntArray(mCount)
        val candOffsetsFloat = FloatArray(mCount)
        for (m in 0 until mCount) {
            candTicks[m] = (candOffsetsMs[m] / tickMs.toLong()).toInt()
            candOffsetsFloat[m] = candOffsetsMs[m].toFloat()
        }

        val refStarts = refSpans.startTick
        val refEnds = refSpans.endTick
        val refWeights = refSpans.weight
        val refSize = refSpans.size

        val edgeCount = if (observedWindows != null) 1 else max(1, (n * 0.05f).toInt())
        val backptr = IntArray(n * mCount)
        var activePrev = FloatArray(mCount)
        var activeCurr = FloatArray(mCount)
        val rightBestIdxBuf = IntArray(mCount)
        val dequeIdx = IntArray(mCount)
        val dequeKey = FloatArray(mCount)
        var baseRefStart = 0

        for (i in 0 until n) {
            val sTick = sTicks[i]
            val eTick = eTicks[i]
            val ls = max(1, eTick - sTick)
            val invLs = 1.0f / ls.toFloat()
            val minPossibleCs = sTick + candTicks[0]
            while (baseRefStart > 0 && refEnds[baseRefStart - 1] > minPossibleCs) {
                baseRefStart--
            }
            while (baseRefStart < refSize && refEnds[baseRefStart] <= minPossibleCs) {
                baseRefStart++
            }

            var rHint = baseRefStart
            for (m in 0 until mCount) {
                val shift = candTicks[m]
                val cs = sTick + shift
                val ce = eTick + shift
                while (rHint < refSize && refEnds[rHint] <= cs) {
                    rHint++
                }
                var score = 0f
                var j = rHint
                while (j < refSize && refStarts[j] < ce) {
                    val rs = refStarts[j]
                    val re = refEnds[j]
                    val ov = min(ce, re) - max(cs, rs)
                    if (ov > 0) {
                        if (overlapMode) {
                            score += refWeights[j] * (ov.toFloat() * invLs)
                        } else {
                            val lr = max(ls, re - rs)
                            score += refWeights[j] * (ov.toFloat() / lr.toFloat())
                        }
                    }
                    j++
                }
                activeCurr[m] = score
            }

            val rowOffset = i * mCount
            if (i == 0) {
                for (m in 0 until mCount) backptr[rowOffset + m] = m
            } else {
                val gapMs = (sMsArr[i] - eMsArr[i - 1]).coerceAtLeast(0L)
                val baseSplitPenalty = if (i <= edgeCount || i >= n - edgeCount) {
                    splitPenalty * config.edgeCuePenaltyMultiplier
                } else {
                    splitPenalty
                }
                val gapTieFactor = 1.0f - 0.04f * min(1.0f, gapMs.toFloat() / 8_000f)
                val effSplitPenalty = baseSplitPenalty * gapTieFactor

                rightBestIdxBuf.fill(-1)
                if (gapMs > 0L && mCount > 1) {
                    var head = 0
                    var tail = 0
                    for (m in mCount - 2 downTo 0) {
                        val enter = m + 1
                        val enterKey = activePrev[enter] - lambdaPerMs * candOffsetsFloat[enter]
                        while (head < tail && dequeKey[tail - 1] <= enterKey) {
                            tail--
                        }
                        dequeIdx[tail] = enter
                        dequeKey[tail] = enterKey
                        tail++
                        val maxAllowedMs = candOffsetsMs[m] + gapMs
                        while (head < tail && candOffsetsMs[dequeIdx[head]] > maxAllowedMs) {
                            head++
                        }
                        if (head < tail) {
                            val rScore = dequeKey[head] + lambdaPerMs * candOffsetsFloat[m] - effSplitPenalty
                            if (rScore > activePrev[m]) {
                                rightBestIdxBuf[m] = dequeIdx[head]
                            }
                        }
                    }
                }

                var leftBestKey = Float.NEGATIVE_INFINITY
                var leftBestIdx = 0
                for (m in 0 until mCount) {
                    val offF = candOffsetsFloat[m]
                    var bestVal = activePrev[m]
                    var bestIdx = m

                    val rIdx = rightBestIdxBuf[m]
                    if (rIdx >= 0) {
                        val rVal = activePrev[rIdx] - lambdaPerMs * (candOffsetsFloat[rIdx] - offF) - effSplitPenalty
                        if (rVal > bestVal) {
                            bestVal = rVal
                            bestIdx = rIdx
                        }
                    }

                    if (m > 0) {
                        val lVal = leftBestKey - lambdaPerMs * offF - effSplitPenalty
                        if (lVal > bestVal) {
                            bestVal = lVal
                            bestIdx = leftBestIdx
                        }
                    }

                    val enterLeftKey = activePrev[m] + lambdaPerMs * offF
                    if (m == 0 || enterLeftKey >= leftBestKey) {
                        leftBestKey = enterLeftKey
                        leftBestIdx = m
                    }

                    activeCurr[m] += bestVal
                    backptr[rowOffset + m] = bestIdx
                }
            }

            val tmp = activePrev
            activePrev = activeCurr
            activeCurr = tmp
        }

        var bestFinalVal = Float.NEGATIVE_INFINITY
        var bestFinalM = 0
        var bestFinalDist = Long.MAX_VALUE
        for (m in 0 until mCount) {
            val score = activePrev[m]
            val dist = abs(candOffsetsMs[m])
            if (score > bestFinalVal + 1e-5f || (abs(score - bestFinalVal) <= 1e-5f && dist < bestFinalDist)) {
                bestFinalVal = score
                bestFinalM = m
                bestFinalDist = dist
            }
        }

        val pathMs = LongArray(n)
        var curM = bestFinalM
        for (i in n - 1 downTo 0) {
            pathMs[i] = candOffsetsMs[curM]
            curM = backptr[i * mCount + curM]
        }

        val perCueScore = computePerCueChosenScoresMs(
            sTicks = sTicks,
            eTicks = eTicks,
            chosenOffsetsMs = pathMs,
            tickMs = tickMs,
            refSpans = refSpans,
            overlapMode = overlapMode,
        )

        val sampleOffsetMs = pathMs[n / 2]
        val effectiveCueCount = countObservedCuesAtOffset(cues, fpsRatio, sampleOffsetMs, observedWindows)
            .coerceAtLeast(1)
        val fpsPenalty = if (fpsRatio == FpsRatio.UNITY) {
            0.0
        } else {
            config.nonUnityFpsPriorPenalty.toDouble() * effectiveCueCount.toDouble()
        }

        return DpCandidateSolution(
            fpsRatio = fpsRatio,
            rawTotalScore = bestFinalVal.toDouble(),
            regularizedScore = bestFinalVal.toDouble() - fpsPenalty,
            cueOffsetsMs = pathMs,
            perCueScore = perCueScore,
        )
    }

    private fun computePerCueChosenScoresMs(
        sTicks: IntArray,
        eTicks: IntArray,
        chosenOffsetsMs: LongArray,
        tickMs: Int,
        refSpans: WeightedTickSpans,
        overlapMode: Boolean,
    ): FloatArray {
        val n = sTicks.size
        val scores = FloatArray(n)
        val tick = tickMs.coerceAtLeast(1).toLong()
        var rHint = 0
        for (i in 0 until n) {
            val shift = (chosenOffsetsMs[i] / tick).toInt()
            val cs = sTicks[i] + shift
            val ce = eTicks[i] + shift
            val ls = max(1, ce - cs)
            while (rHint > 0 && refSpans.endTick[rHint - 1] > cs) {
                rHint--
            }
            while (rHint < refSpans.size && refSpans.endTick[rHint] <= cs) {
                rHint++
            }
            var total = 0.0
            var j = rHint
            while (j < refSpans.size && refSpans.startTick[j] < ce) {
                val ov = min(ce, refSpans.endTick[j]) - max(cs, refSpans.startTick[j])
                if (ov > 0) {
                    val lr = max(1, refSpans.endTick[j] - refSpans.startTick[j])
                    val denom = if (overlapMode) ls.toDouble() else max(ls, lr).toDouble()
                    total += refSpans.weight[j].toDouble() * (ov.toDouble() / denom)
                }
                j++
            }
            scores[i] = total.toFloat()
        }
        return scores
    }

    private fun extractAndPolishSegments(
        candidate: CueDocument,
        solution: DpCandidateSolution,
        positiveOnsets: LongArray,
        observedWindows: List<ObservedWindow>?,
        config: AlassConfig,
    ): List<DpSegment> {
        val cues = candidate.cues
        val jitterMergeMs = 90L

        val rawSegments = ArrayList<DpSegment>()
        var segStart = 0
        for (i in 1..cues.size) {
            if (i == cues.size || solution.cueOffsetsMs[i] != solution.cueOffsetsMs[segStart]) {
                val endIdx = i - 1
                val coarseMs = solution.cueOffsetsMs[segStart]
                var sumScore = 0.0
                var obsCount = 0
                for (c in segStart..endIdx) {
                    sumScore += solution.perCueScore[c].toDouble()
                    if (observedWindows != null) {
                        val ws = solution.fpsRatio.scaleUs(cues[c].span.startMs * 1_000L) / 1_000L + coarseMs
                        val we = solution.fpsRatio.scaleUs(cues[c].span.endMs * 1_000L) / 1_000L + coarseMs
                        if (overlapsObservedWindows(ws, we, observedWindows)) obsCount++
                    }
                }
                val denom = if (observedWindows != null) obsCount.coerceAtLeast(1) else (endIdx - segStart + 1)
                val meanScore = sumScore / denom.toDouble()
                rawSegments.add(
                    DpSegment(
                        firstCueIdx = segStart,
                        lastCueIdx = endIdx,
                        coarseOffsetMs = coarseMs,
                        refinedOffsetMs = coarseMs,
                        meanCueScore = meanScore,
                    )
                )
                segStart = i
            }
        }

        val merged = ArrayList<DpSegment>(rawSegments.size)
        for (seg in rawSegments) {
            val prev = merged.lastOrNull()
            if (prev != null && abs(prev.coarseOffsetMs - seg.coarseOffsetMs) <= jitterMergeMs) {
                val totalCues = prev.cueCount + seg.cueCount
                val weightedOffset = if (prev.cueCount >= seg.cueCount) prev.coarseOffsetMs else seg.coarseOffsetMs
                val combinedMean = (prev.meanCueScore * prev.cueCount + seg.meanCueScore * seg.cueCount) / totalCues
                merged[merged.lastIndex] = DpSegment(
                    firstCueIdx = prev.firstCueIdx,
                    lastCueIdx = seg.lastCueIdx,
                    coarseOffsetMs = weightedOffset,
                    refinedOffsetMs = weightedOffset,
                    meanCueScore = combinedMean,
                )
            } else {
                merged.add(seg)
            }
        }

        val stubThreshold = if (observedWindows != null) 0.20 else 0.35
        if (merged.size >= 2) {
            val first = merged.first()
            val second = merged[1]
            if (first.cueCount < config.minSegmentCuesForOnsetPolish &&
                first.meanCueScore < stubThreshold &&
                second.meanCueScore >= 0.35
            ) {
                merged[1] = second.copy(firstCueIdx = first.firstCueIdx)
                merged.removeAt(0)
            }
        }
        if (merged.size >= 2) {
            val last = merged.last()
            val prev = merged[merged.size - 2]
            if (last.cueCount < config.minSegmentCuesForOnsetPolish &&
                last.meanCueScore < stubThreshold &&
                prev.meanCueScore >= 0.35
            ) {
                merged[merged.size - 2] = prev.copy(lastCueIdx = last.lastCueIdx)
                merged.removeAt(merged.lastIndex)
            }
        }

        if (positiveOnsets.isNotEmpty()) {
            val minPolishCues = if (observedWindows != null) 2 else min(4, config.minSegmentCuesForOnsetPolish)
            for (seg in merged) {
                val eligible = seg.cueCount >= minPolishCues ||
                    (merged.size == 1 && seg.cueCount >= 1)
                if (!eligible) continue

                val residuals = ArrayList<Long>(seg.cueCount)
                for (c in seg.firstCueIdx..seg.lastCueIdx) {
                    val scaledStartMs = (solution.fpsRatio.scaleUs(cues[c].span.startMs * 1_000L) + 500L)
                        .floorDiv(1_000L)
                    val roughStartMs = scaledStartMs + seg.coarseOffsetMs
                    if (observedWindows != null && !isInsideObservedWindows(roughStartMs, observedWindows)) {
                        continue
                    }
                    val nearestOnset = findNearestOnsetMs(positiveOnsets, roughStartMs)
                    val diff = nearestOnset - roughStartMs
                    if (abs(diff) <= 350L) {
                        residuals.add(diff)
                    }
                }
                val minMatches = when {
                    merged.size == 1 -> 1
                    observedWindows != null -> 2
                    seg.cueCount >= config.minSegmentCuesForOnsetPolish -> 3
                    else -> 2
                }
                if (residuals.size >= minMatches) {
                    residuals.sort()
                    val mid = residuals.size / 2
                    val medianDelta = if (residuals.size % 2 == 1) {
                        residuals[mid]
                    } else {
                        (residuals[mid - 1] + residuals[mid]) / 2L
                    }
                    seg.refinedOffsetMs = seg.coarseOffsetMs + medianDelta
                }
            }
        }

        return merged
    }

    private fun buildWarpFromSegments(
        candidate: CueDocument,
        fpsRatio: FpsRatio,
        segments: List<DpSegment>,
    ): SubtitleTimeWarp {
        if (segments.isEmpty()) return SubtitleTimeWarp.Identity
        if (segments.size == 1) {
            val offset = segments[0].refinedOffsetMs
            return if (fpsRatio == FpsRatio.UNITY && offset == 0L) {
                SubtitleTimeWarp.Identity
            } else {
                SubtitleTimeWarp.Affine(fpsRatio, offset)
            }
        }

        val cues = candidate.cues
        val rawKnots = ArrayList<WarpKnot>(segments.size * 2)
        for (seg in segments) {
            val firstSubUs = cues[seg.firstCueIdx].span.startMs * 1_000L
            val lastSubUs = max(firstSubUs + 1_000L, cues[seg.lastCueIdx].span.endMs * 1_000L)
            val offsetUs = seg.refinedOffsetMs * 1_000L
            val firstMediaUs = fpsRatio.scaleUs(firstSubUs) + offsetUs
            val lastMediaUs = fpsRatio.scaleUs(lastSubUs) + offsetUs
            rawKnots.add(WarpKnot(firstSubUs, firstMediaUs))
            rawKnots.add(WarpKnot(lastSubUs, lastMediaUs))
        }

        val sanitized = ArrayList<WarpKnot>(rawKnots.size)
        for (knot in rawKnots) {
            val prev = sanitized.lastOrNull()
            if (prev == null) {
                sanitized.add(knot)
            } else {
                val adjSubUs = max(prev.subTimeUs + 1_000L, knot.subTimeUs)
                val adjMediaUs = max(prev.mediaTimeUs + 1_000L, knot.mediaTimeUs)
                sanitized.add(WarpKnot(adjSubUs, adjMediaUs))
            }
        }

        return SubtitleTimeWarp.PiecewiseLinear.createValidated(fpsRatio, sanitized)
            .getOrElse {
                val dominant = segments.maxByOrNull { it.cueCount } ?: segments.first()
                val fallback = SubtitleTimeWarp.Affine(fpsRatio, dominant.refinedOffsetMs)
                if (fallback.isIdentity) SubtitleTimeWarp.Identity else fallback
            }
    }

    private data class PostWarpMetrics(
        val confidence: Double,
        val medianResidualMs: Int,
        val p95ResidualMs: Int,
        val evaluatedCueCount: Int,
    )

    private fun evaluatePostWarpMetrics(
        candidate: CueDocument,
        warp: SubtitleTimeWarp,
        refSpans: WeightedTickSpans,
        positiveOnsets: LongArray,
        observedWindows: List<ObservedWindow>?,
        tickMs: Int,
    ): PostWarpMetrics {
        val cues = candidate.cues
        val absResiduals = ArrayList<Int>(cues.size)
        var evaluated = 0
        var overlapScoreSum = 0.0
        var hitWithin250Ms = 0
        var rHint = 0

        for (cue in cues) {
            val wsMs = (warp.forwardUs(cue.span.startMs * 1_000L) + 500L).floorDiv(1_000L)
            val weMs = (warp.forwardUs(cue.span.endMs * 1_000L) + 500L).floorDiv(1_000L)
            if (weMs <= 0L || weMs <= wsMs) continue
            if (observedWindows != null && !overlapsObservedWindows(wsMs, weMs, observedWindows)) {
                continue
            }
            evaluated++

            val csTick = (wsMs / tickMs).toInt()
            val ceTick = max(csTick + 1, (weTick(weMs, tickMs)))
            val ls = max(1, ceTick - csTick)
            while (rHint > 0 && refSpans.endTick[rHint - 1] > csTick) rHint--
            while (rHint < refSpans.size && refSpans.endTick[rHint] <= csTick) rHint++

            var cuePosOverlap = 0.0
            var j = rHint
            while (j < refSpans.size && refSpans.startTick[j] < ceTick) {
                if (refSpans.isPositiveSpeech[j]) {
                    val ov = min(ceTick, refSpans.endTick[j]) - max(csTick, refSpans.startTick[j])
                    if (ov > 0) {
                        cuePosOverlap += refSpans.weight[j].toDouble() * (ov.toDouble() / ls.toDouble())
                    }
                }
                j++
            }
            val clampedCueOverlap = cuePosOverlap.coerceIn(0.0, 1.0)
            overlapScoreSum += clampedCueOverlap

            if (positiveOnsets.isNotEmpty()) {
                val nearest = findNearestOnsetMs(positiveOnsets, wsMs)
                val absDiff = abs(nearest - wsMs).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                if (absDiff <= 250 || clampedCueOverlap >= 0.50) {
                    hitWithin250Ms++
                }
                if (absDiff <= 2_500 || clampedCueOverlap > 0.15) {
                    absResiduals.add(absDiff)
                }
            }
        }

        if (evaluated == 0) {
            return PostWarpMetrics(0.0, Int.MAX_VALUE, Int.MAX_VALUE, 0)
        }
        val meanOverlap = overlapScoreSum / evaluated.toDouble()
        val hitRate = hitWithin250Ms.toDouble() / evaluated.toDouble()
        val rawConfidence = (0.55 * meanOverlap + 0.45 * hitRate).coerceIn(0.0, 1.0)
        val sampleFactor = if (evaluated < 4) 0.85 else 1.0
        val confidence = (rawConfidence * sampleFactor).coerceIn(0.0, 1.0)

        absResiduals.sort()
        val medianMs = if (absResiduals.isEmpty()) {
            0
        } else {
            absResiduals[absResiduals.size / 2]
        }
        val p95Ms = if (absResiduals.isEmpty()) {
            0
        } else {
            val idx95 = ((absResiduals.size - 1) * 0.95).toInt().coerceIn(0, absResiduals.size - 1)
            absResiduals[idx95]
        }
        return PostWarpMetrics(
            confidence = confidence,
            medianResidualMs = medianMs,
            p95ResidualMs = p95Ms,
            evaluatedCueCount = evaluated,
        )
    }

    private fun weTick(weMs: Long, tickMs: Int): Int = ((weMs + tickMs - 1) / tickMs).toInt()

    private fun buildAnchorTickSpans(anchor: CueDocument, tickMs: Int): WeightedTickSpans {
        val cues = anchor.cues
        val n = cues.size
        val starts = IntArray(n)
        val ends = IntArray(n)
        val weights = FloatArray(n)
        val onsets = LongArray(n)
        val pos = BooleanArray(n)
        val tick = tickMs.coerceAtLeast(1).toLong()
        for (i in 0 until n) {
            val c = cues[i]
            val s = (c.span.startMs / tick).toInt()
            val e = max(s + 1, ((c.span.endMs + tick - 1L) / tick).toInt())
            starts[i] = s
            ends[i] = e
            weights[i] = 1.0f
            onsets[i] = c.span.startMs
            pos[i] = true
        }
        return WeightedTickSpans(starts, ends, weights, onsets, pos)
    }

    private fun mergeObservedWindows(windows: List<ObservedWindow>): List<ObservedWindow> {
        if (windows.size <= 1) return windows
        val sorted = windows.sortedBy { it.startMs }
        val merged = ArrayList<ObservedWindow>(sorted.size)
        var cur = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            cur = if (next.startMs <= cur.endMs) {
                ObservedWindow(cur.startMs, max(cur.endMs, next.endMs))
            } else {
                merged.add(cur)
                next
            }
        }
        merged.add(cur)
        return merged
    }

    private fun buildObservationMaskedSpeechSpans(
        speechSpans: List<SpeechSpan>,
        observedWindows: List<ObservedWindow>,
        tickMs: Int,
        silencePenaltyWeight: Float,
        preSpeechLeadMs: Long,
    ): WeightedTickSpans {
        val sortedSpeech = speechSpans
            .filter { it.endMs > it.startMs && it.confidenceWeight > 0f }
            .sortedBy { it.startMs }
        val starts = ArrayList<Int>(sortedSpeech.size * 2 + observedWindows.size)
        val ends = ArrayList<Int>(sortedSpeech.size * 2 + observedWindows.size)
        val weights = ArrayList<Float>(sortedSpeech.size * 2 + observedWindows.size)
        val onsets = ArrayList<Long>(sortedSpeech.size * 2 + observedWindows.size)
        val isPositive = ArrayList<Boolean>(sortedSpeech.size * 2 + observedWindows.size)
        val tick = tickMs.coerceAtLeast(1).toLong()

        fun pushSpan(startMs: Long, endMs: Long, w: Float, onsetMs: Long, pos: Boolean) {
            if (endMs <= startMs || w == 0f) return
            val s = (startMs / tick).toInt()
            val e = max(s + 1, ((endMs + tick - 1L) / tick).toInt())
            starts.add(s)
            ends.add(e)
            weights.add(w)
            onsets.add(onsetMs)
            isPositive.add(pos)
        }

        var spIdx = 0
        for (win in observedWindows) {
            while (spIdx < sortedSpeech.size && sortedSpeech[spIdx].endMs <= win.startMs) {
                spIdx++
            }
            var cursorMs = win.startMs
            var j = spIdx
            while (j < sortedSpeech.size && sortedSpeech[j].startMs < win.endMs) {
                val sp = sortedSpeech[j]
                val clipStart = max(win.startMs, sp.startMs)
                val clipEnd = min(win.endMs, sp.endMs)
                if (clipEnd > clipStart) {
                    if (clipStart > cursorMs) {
                        pushSpan(cursorMs, clipStart, -silencePenaltyWeight, cursorMs, false)
                    }
                    val targetOnset = (sp.startMs - preSpeechLeadMs).coerceAtLeast(0L)
                    pushSpan(clipStart, clipEnd, sp.confidenceWeight, targetOnset, true)
                    cursorMs = max(cursorMs, clipEnd)
                }
                j++
            }
            if (cursorMs < win.endMs) {
                pushSpan(cursorMs, win.endMs, -silencePenaltyWeight, cursorMs, false)
            }
        }

        return WeightedTickSpans(
            startTick = starts.toIntArray(),
            endTick = ends.toIntArray(),
            weight = weights.toFloatArray(),
            onsetMs = onsets.toLongArray(),
            isPositiveSpeech = isPositive.toBooleanArray(),
        )
    }

    private fun extractSortedPositiveOnsetsMs(refSpans: WeightedTickSpans): LongArray {
        var count = 0
        for (i in 0 until refSpans.size) {
            if (refSpans.isPositiveSpeech[i]) count++
        }
        val out = LongArray(count)
        var k = 0
        for (i in 0 until refSpans.size) {
            if (refSpans.isPositiveSpeech[i]) {
                out[k++] = refSpans.onsetMs[i]
            }
        }
        out.sort()
        return out
    }

    private fun findNearestOnsetMs(sortedOnsets: LongArray, targetMs: Long): Long {
        var lo = 0
        var hi = sortedOnsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedOnsets[mid]
            when {
                v < targetMs -> lo = mid + 1
                v > targetMs -> hi = mid - 1
                else -> return v
            }
        }
        val right = if (lo < sortedOnsets.size) sortedOnsets[lo] else Long.MAX_VALUE
        val left = if (lo > 0) sortedOnsets[lo - 1] else Long.MIN_VALUE
        if (left == Long.MIN_VALUE) return right
        if (right == Long.MAX_VALUE) return left
        return if (targetMs - left <= right - targetMs) left else right
    }

    private fun isInsideObservedWindows(timeMs: Long, windows: List<ObservedWindow>): Boolean {
        for (w in windows) {
            if (timeMs >= w.startMs && timeMs <= w.endMs) return true
        }
        return false
    }

    private fun overlapsObservedWindows(startMs: Long, endMs: Long, windows: List<ObservedWindow>): Boolean {
        for (w in windows) {
            if (endMs > w.startMs && startMs < w.endMs) return true
        }
        return false
    }

    private fun countObservedCuesAtOffset(
        cues: List<CueEntry>,
        fpsRatio: FpsRatio,
        offsetMs: Long,
        observedWindows: List<ObservedWindow>?,
    ): Int {
        if (observedWindows == null) return cues.size
        var count = 0
        for (cue in cues) {
            val ws = fpsRatio.scaleUs(cue.span.startMs * 1_000L) / 1_000L + offsetMs
            val we = fpsRatio.scaleUs(cue.span.endMs * 1_000L) / 1_000L + offsetMs
            if (overlapsObservedWindows(ws, we, observedWindows)) count++
        }
        return count
    }
}
