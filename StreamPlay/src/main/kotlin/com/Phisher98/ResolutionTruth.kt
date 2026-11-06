package com.phisher98

import com.lagradost.cloudstream3.utils.Qualities

/**
 * Single source of truth for pixel-dimension -> canonical quality classification.
 *
 * Every consumer that derives a quality tier from declared `width`/`height` values MUST route
 * through [classifyDimensions] so the manifest layer (`StreamPlayExtractor.normalizeHlsResolutionToQuality`)
 * and the text layer (`StreamLinkOptimizer.extractQualityFromText` exact `WxH` patterns) can never
 * disagree on the same dimensions. The bands are ordered strictest-first: constant-height cinema
 * widescreen encodes (`1332x720`..`1850x720`, `1998x1080`..`2650x1080`, `1200x480`, `864x360`) are
 * classified by their vertical height with a long-dimension guard, then constant-width letterboxed
 * encodes (`1920x800` -> `1080p`, `1280x534` -> `720p`) fall through to the legacy long-dimension
 * bands, and anything unclaimed snaps to the nearest rung of the canonical ladder.
 */
internal object ResolutionTruth {
    private val TIER_LADDER = intArrayOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    /** Snaps a bare pixel height to the nearest canonical ladder rung (`768` -> `720`, `1092` -> `1080`). */
    fun snapToTier(px: Int): Int {
        if (px <= 0) return Qualities.Unknown.value
        return TIER_LADDER.minByOrNull { kotlin.math.abs(it - px) } ?: Qualities.Unknown.value
    }

    /**
     * Classifies a declared `width`x`height` pair. When one dimension is missing (<= 0) the other
     * is snapped directly. Returns a `Qualities` value; `Qualities.Unknown.value` only when both
     * dimensions are absent or non-positive.
     */
    fun classifyDimensions(width: Int?, height: Int?): Int {
        val w = width ?: 0
        val h = height ?: 0
        if (w <= 0 && h <= 0) return Qualities.Unknown.value
        if (w <= 0 || h <= 0) return snapToTier(if (w > 0) w else h)
        val shortDim = minOf(w, h)
        val longDim = maxOf(w, h)
        return when {
            shortDim in 2140..2180 -> Qualities.P2160.value
            shortDim in 1420..1460 -> Qualities.P1440.value
            shortDim in 1040..1100 && longDim <= 2650 -> Qualities.P1080.value
            shortDim in 690..740 && longDim <= 1850 -> Qualities.P720.value
            shortDim in 468..492 && longDim <= 1250 -> Qualities.P480.value
            shortDim in 354..372 && !(longDim in 840..860 && shortDim < 360) && longDim <= 920 -> Qualities.P360.value
            shortDim >= 2000 || longDim >= 3600 -> Qualities.P2160.value
            shortDim >= 1350 || (longDim >= 2400 && shortDim !in 1040..1100) -> Qualities.P1440.value
            shortDim in 950..1200 || longDim in 1800..2100 -> Qualities.P1080.value
            shortDim in 650..750 || longDim in 1200..1350 -> Qualities.P720.value
            shortDim in 430..550 || longDim in 800..950 -> Qualities.P480.value
            shortDim in 320..420 || longDim in 550..750 -> Qualities.P360.value
            shortDim in 200..319 || longDim in 380..549 -> Qualities.P240.value
            shortDim in 100..199 || longDim in 200..379 -> Qualities.P144.value
            else -> snapToTier(shortDim)
        }
    }
}
