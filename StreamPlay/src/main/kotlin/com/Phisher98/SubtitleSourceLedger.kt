package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.ProvenanceIdentity
import com.phisher98.subtitles.StreamProviderKey
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SubtitleSourceLedger(
    val targetEnglishSources: Int,
    val targetUrduSources: Int = 0,
) {
    private val emittedSubtitleKeys = ConcurrentHashMap.newKeySet<String>()
    val englishSubtitleSources = ConcurrentHashMap.newKeySet<String>()
    val urduSubtitleSources = ConcurrentHashMap.newKeySet<String>()
    val nativeOrHumanUrduSources = ConcurrentHashMap.newKeySet<String>()
    val completedEnglishSubtitleProviders = ConcurrentHashMap.newKeySet<String>()
    val completedUrduSubtitleProviders = ConcurrentHashMap.newKeySet<String>()
    val providerAnchorSources = ConcurrentHashMap.newKeySet<String>()

    private fun wireTokenOf(identity: ProvenanceIdentity): String = when (identity) {
        is ProvenanceIdentity.StreamProvider -> identity.provider.wireId
        is ProvenanceIdentity.HumanCatalogue -> identity.catalogue.wireId
        is ProvenanceIdentity.BolChalTranslated -> "bolchal:${identity.donor.wireDonorId}"
        is ProvenanceIdentity.UnknownExternal -> identity.registrableHost.ifBlank { "external" }
    }

    fun normalizeProvenance(raw: String?): String? {
        val cleaned = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        CatalogueSourceKey.fromTextOrHost(cleaned)?.let { return it.wireId }
        StreamProviderKey.fromTextOrHost(cleaned)?.let { return it.wireId }
        return cleaned
    }

    fun resolveProvenanceIdentity(
        headers: Map<String, String>?,
        fallbackSourceKey: String? = null,
        displayName: String = "",
        urlOrFileName: String = "",
    ): ProvenanceIdentity {
        val decoded = ProvenanceCodec.decode(
            headers = headers.orEmpty(),
            displayName = displayName,
            urlOrFileName = urlOrFileName,
        )
        if (decoded !is ProvenanceIdentity.UnknownExternal) {
            return decoded
        }
        val hint = fallbackSourceKey?.trim()?.takeIf { it.isNotEmpty() } ?: return decoded
        StreamProviderKey.fromTextOrHost(hint)?.let { return ProvenanceIdentity.StreamProvider(it) }
        CatalogueSourceKey.fromTextOrHost(hint)?.let { return ProvenanceIdentity.HumanCatalogue(it) }
        return ProvenanceIdentity.UnknownExternal(hint.lowercase(Locale.ROOT))
    }

    fun isProviderAnchor(headers: Map<String, String>?): Boolean =
        headers != null && ProvenanceCodec.isProviderAnchorHeader(headers)

    fun canonicalSubtitleUrl(url: String): String = url.substringBefore('#').trim()

    fun subtitleDedupeKey(lang: String, url: String): String =
        "${lang.trim().lowercase(Locale.ROOT)}|${canonicalSubtitleUrl(url)}"

    fun subtitleDedupeKey(lang: String, url: String, headers: Map<String, String>?): String {
        if (isProviderAnchor(headers)) {
            val provKey = wireTokenOf(resolveProvenanceIdentity(headers, displayName = lang, urlOrFileName = url))
            return "anchor|$provKey|${canonicalSubtitleUrl(url)}"
        }
        return subtitleDedupeKey(lang, url)
    }

    fun markEmitted(lang: String, url: String): Boolean =
        emittedSubtitleKeys.add(subtitleDedupeKey(lang, url))

    fun markEmitted(lang: String, url: String, headers: Map<String, String>?): Boolean =
        emittedSubtitleKeys.add(subtitleDedupeKey(lang, url, headers))

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
            val isTranslated = p == "urdu-neural-fallback" || p.startsWith("bolchal:")
            if (!isTranslated) {
                nativeOrHumanUrduSources.add(p)
            }
        }
        normalizeProvenance(sourceKey)?.let { completedUrduSubtitleProviders.add(it) }
    }

    fun recordSubtitleFile(sub: SubtitleFile, fallbackSourceKey: String? = null) {
        val headers = sub.headers
        val identity = resolveProvenanceIdentity(
            headers = headers,
            fallbackSourceKey = fallbackSourceKey,
            displayName = sub.lang,
            urlOrFileName = sub.url,
        )
        val wireToken = wireTokenOf(identity)
        if (isProviderAnchor(headers)) {
            providerAnchorSources.add(wireToken)
            return
        }
        when {
            isUrduSubtitleLabel(sub.lang) -> {
                urduSubtitleSources.add(wireToken)
                completedUrduSubtitleProviders.add(wireToken)
                if (identity is ProvenanceIdentity.StreamProvider || identity is ProvenanceIdentity.HumanCatalogue) {
                    nativeOrHumanUrduSources.add(wireToken)
                }
                normalizeProvenance(fallbackSourceKey)?.let { completedUrduSubtitleProviders.add(it) }
            }
            isEnglishSubtitleLabel(sub.lang) -> {
                recordEnglishDelivery(wireToken, fallbackSourceKey)
            }
        }
    }

    fun englishSourceCount(): Int = englishSubtitleSources.size
    fun urduSourceCount(): Int = urduSubtitleSources.size
    fun nativeOrHumanUrduSourceCount(): Int = nativeOrHumanUrduSources.size

    fun needsEnglishCompletion(): Boolean = englishSubtitleSources.size < targetEnglishSources
    fun needsUrduCompletion(): Boolean = targetUrduSources > 0 && nativeOrHumanUrduSources.size < targetUrduSources

    fun isEnglishProviderCompleted(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in completedEnglishSubtitleProviders
    }

    fun isUrduProviderCompleted(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in completedUrduSubtitleProviders
    }

    fun isEnglishSource(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in englishSubtitleSources
    }

    fun isUrduSource(providerId: String): Boolean {
        val id = normalizeProvenance(providerId) ?: return false
        return id in urduSubtitleSources
    }
}
