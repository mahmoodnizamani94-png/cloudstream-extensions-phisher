package com.phisher98

/**
 * Single source of truth for VidLink CDN per-host `Referer`/`Origin` policy.
 *
 * VidLink's direct-MP4 and manifest CDNs are split across hostname families with opposite rules,
 * verified by live probing (HTTP `206` vs `403`/`429`):
 * - `bcdnxw.hakunaymatata.com` REQUIRES `Referer: https://filmboom.top/` + `Origin: https://filmboom.top`.
 *   A supplied referer is honoured only when it is itself a filmboom referer; any other value
 *   (`vidlink.pro`, embed wrappers) is replaced by the proven default, and a missing one is defaulted.
 * - `bcdn.hakunaymatata.com` / `sacdn.hakunaymatata.com` (every other `hakunaymatata.com` host)
 *   REJECT all `Referer`/`Origin` with HTTP `403` — both headers must be stripped.
 * - Third-party VidLink CDN hosts (proxy/mirror families) keep only genuine third-party referers;
 *   `vidlink.pro`, `filmboom.top`, embed wrappers and blank values are stripped.
 *
 * Every VidLink header surface (extractor `sanitizeVidlinkHeaders`, download
 * `buildDownloadHeaders`, playback `getEffectiveReferer`) must route through this policy so the
 * five former inline copies can never drift apart again.
 */
internal object VidLinkCdnPolicy {
    const val FILMBOOM_REFERER = "https://filmboom.top/"
    const val FILMBOOM_ORIGIN = "https://filmboom.top"

    private const val FILMBOOM_HOST = "filmboom.top"
    private const val VIDLINK_HOST = "vidlink.pro"
    private const val BCDNXW_HOST = "bcdnxw.hakunaymatata.com"
    private const val HAKUNAYMATATA_HOST = "hakunaymatata.com"

    enum class RefererMode { REQUIRE_FILMBOOM, STRIP, THIRD_PARTY }

    fun modeFor(url: String): RefererMode = when {
        url.contains(BCDNXW_HOST, ignoreCase = true) -> RefererMode.REQUIRE_FILMBOOM
        url.contains(HAKUNAYMATATA_HOST, ignoreCase = true) -> RefererMode.STRIP
        else -> RefererMode.THIRD_PARTY
    }

    /** A supplied referer/origin is trustworthy on a REQUIRE_FILMBOOM host only if it is a filmboom URL. */
    fun isFilmboomUrl(value: String): Boolean =
        value.trim().startsWith("http", ignoreCase = true) && value.contains(FILMBOOM_HOST, ignoreCase = true)

    /** Third-party hosts keep only non-blank referers that are not VidLink/embed/filmboom artifacts. */
    fun isAllowedThirdPartyReferer(value: String): Boolean =
        value.isNotBlank() &&
            !value.contains(VIDLINK_HOST, ignoreCase = true) &&
            !value.contains(FILMBOOM_HOST, ignoreCase = true) &&
            !value.contains("embed", ignoreCase = true)

    fun effectiveReferer(url: String, suppliedReferer: String?): String = when (modeFor(url)) {
        RefererMode.REQUIRE_FILMBOOM ->
            suppliedReferer?.trim()?.takeIf { isFilmboomUrl(it) } ?: FILMBOOM_REFERER
        RefererMode.STRIP -> ""
        RefererMode.THIRD_PARTY ->
            suppliedReferer?.trim()?.takeIf { isAllowedThirdPartyReferer(it) } ?: ""
    }

    fun effectiveOrigin(url: String, suppliedOrigin: String?): String = when (modeFor(url)) {
        RefererMode.REQUIRE_FILMBOOM ->
            suppliedOrigin?.trim()?.takeIf { isFilmboomUrl(it) } ?: FILMBOOM_ORIGIN
        RefererMode.STRIP -> ""
        RefererMode.THIRD_PARTY ->
            suppliedOrigin?.trim()?.takeIf { isAllowedThirdPartyReferer(it) } ?: ""
    }

    private fun findHeaderValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) && it.value.isNotBlank() }?.value

    /**
     * Applies the policy in place to a fresh header map for requests targeting [url].
     * [linkReferer]/[linkOrigin] are the referer/origin carried on the link itself
     * (fallback when the header map has none).
     */
    fun apply(
        url: String,
        headers: MutableMap<String, String>,
        linkReferer: String? = null,
        linkOrigin: String? = null
    ) {
        when (modeFor(url)) {
            RefererMode.REQUIRE_FILMBOOM -> {
                val supplied = findHeaderValue(headers, "Referer") ?: linkReferer?.trim()?.takeIf { it.isNotBlank() }
                val suppliedOrigin = findHeaderValue(headers, "Origin") ?: linkOrigin?.trim()?.takeIf { it.isNotBlank() }
                headers["Referer"] = effectiveReferer(url, supplied)
                headers["Origin"] = effectiveOrigin(url, suppliedOrigin)
            }
            RefererMode.STRIP -> {
                headers.entries.removeIf {
                    it.key.equals("Referer", ignoreCase = true) || it.key.equals("Origin", ignoreCase = true)
                }
            }
            RefererMode.THIRD_PARTY -> {
                headers.entries.removeIf { (k, v) ->
                    (k.equals("Referer", ignoreCase = true) || k.equals("Origin", ignoreCase = true)) &&
                        !isAllowedThirdPartyReferer(v)
                }
            }
        }
    }
}
