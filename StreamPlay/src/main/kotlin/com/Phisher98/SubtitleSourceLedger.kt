package com.phisher98

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Real bookkeeping behind the English/Urdu subtitle source-diversity contract (AGENTS.md §6),
 * shared verbatim by the Movie/TV surface (`StreamPlay`) and the Anime surface (`StreamPlayAnime`).
 *
 * The contract decomposes into two independent tracking problems that were previously conflated:
 * - **Source diversity** counts distinct lowercase *provenances* (`X-StreamPlay-Source` marker, or
 *   the subtitle host when no marker exists) that actually delivered an English subtitle. An
 *   episode is complete when [targetEnglishSources] distinct provenances have spoken — 6 for anime,
 *   5 for movies/TV.
 * - **Re-query suppression** tracks *container providers* whose fan-out already completed, so the
 *   subtitle completion phase never re-queries a provider that delivered (or was satisfied) even
 *   when its provenance differs from the container id (e.g. ZokoAnime subtitles under the Animex
 *   container), while still re-querying providers whose fan-out produced nothing.
 *
 * URL-level deduplication is `language|canonical-url` with the `#fragment` routing suffix stripped
 * before comparison, so the same sidecar offered twice under different routing fragments (e.g.
 * `url#animepahe`) cannot double-count toward diversity.
 */
class SubtitleSourceLedger(
    val targetEnglishSources: Int,
    val targetUrduSources: Int = 0
) {
    private val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
    val englishSubtitleSources = ConcurrentHashMap.newKeySet<String>()
    val urduSubtitleSources = ConcurrentHashMap.newKeySet<String>()
    val completedEnglishSubtitleProviders = ConcurrentHashMap.newKeySet<String>()
    val completedUrduSubtitleProviders = ConcurrentHashMap.newKeySet<String>()

    fun normalizeProvenance(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun canonicalSubtitleUrl(url: String): String = url.substringBefore('#').trim()

    fun subtitleDedupeKey(lang: String, url: String): String =
        "${lang.trim().lowercase(Locale.ROOT)}|${canonicalSubtitleUrl(url)}"

    /** Returns true when this exact language + canonical-URL subtitle has never been emitted before. */
    fun markEmitted(lang: String, url: String): Boolean = emittedSubtitleKeys.add(subtitleDedupeKey(lang, url))

    /** True when at least one subtitle (any language) was emitted on this episode. */
    fun hasAnyEmittedSubtitle(): Boolean = emittedSubtitleKeys.isNotEmpty()

    fun recordEnglishDelivery(provenance: String?, sourceKey: String?) {
        val p = normalizeProvenance(provenance)
        if (p != null) {
            englishSubtitleSources.add(p)
            completedEnglishSubtitleProviders.add(p)
        }
        normalizeProvenance(sourceKey)?.let { completedEnglishSubtitleProviders.add(it) }
    }

    fun recordUrduDelivery(provenance: String?, sourceKey: String?) {
        val p = normalizeProvenance(provenance)
        if (p != null) {
            urduSubtitleSources.add(p)
            completedUrduSubtitleProviders.add(p)
        }
        normalizeProvenance(sourceKey)?.let { completedUrduSubtitleProviders.add(it) }
    }

    fun englishSourceCount(): Int = englishSubtitleSources.size
    fun urduSourceCount(): Int = urduSubtitleSources.size

    fun needsEnglishCompletion(): Boolean = englishSubtitleSources.size < targetEnglishSources
    fun needsUrduCompletion(): Boolean = targetUrduSources > 0 && urduSubtitleSources.size < targetUrduSources

    /** True when [providerId]'s fan-out already delivered an English subtitle on this episode. */
    fun isEnglishProviderCompleted(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in completedEnglishSubtitleProviders
    }

    /** True when [providerId]'s fan-out already delivered an Urdu subtitle on this episode. */
    fun isUrduProviderCompleted(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in completedUrduSubtitleProviders
    }

    /** True when [providerId] is a distinct provenance already counted toward English diversity. */
    fun isEnglishSource(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in englishSubtitleSources
    }

    /** True when [providerId] is a distinct provenance already counted toward Urdu diversity. */
    fun isUrduSource(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in urduSubtitleSources
    }
}
