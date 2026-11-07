package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-server verified-quality gate for multi-server providers (`MeowTV`, `VidFast`).
 *
 * Providers that fan out over backup servers must keep probing until at least one verified `720p`
 * stream is emitted, while never emitting the same known quality twice across all servers and
 * payload lanes. The gate enforces exactly that:
 * - a verified `720p` candidate always passes (multiple 720p mirrors keep the lowest-size
 *   selection meaningful; the caller stops probing after the first one);
 * - an `Unknown`/`[Auto]` lane always passes (it feeds the runtime-resolution-truth machinery, and
 *   every server's unparsed master contributes fallback depth regardless of what earlier servers
 *   emitted);
 * - any other known quality passes exactly once across the whole fan-out, so `1080p`/`480p`
 *   mirrors from later servers cannot flood the walk.
 */
class CrossServerQualityGate {
    private val seenQualities = ConcurrentHashMap.newKeySet<Int>()

    fun allow(link: ExtractorLink): Boolean {
        val is720 = StreamLinkOptimizer.is720p(link) || link.quality == Qualities.P720.value
        val isUnknown = link.quality <= 0 || link.quality == Qualities.Unknown.value
        return is720 || isUnknown || seenQualities.add(link.quality)
    }
}
