package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.util.Locale

/**
 * State-Of-The-Art Link Optimizer for StreamPlay & CloudStream
 *
 * Maximizes download throughput, eliminates CDN anti-leech throttling, enforces
 * identity-encoded byte streams for rock-solid multi-threaded chunk downloading,
 * and normalizes stream types and quality tags.
 */
object StreamPlayLinkOptimizer {

    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_ACCEPT = "Accept"
    const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
    const val HEADER_CONNECTION = "Connection"
    const val HEADER_REFERER = "Referer"
    const val HEADER_ORIGIN = "Origin"

    private const val DEFAULT_BROWSER_UA = USER_AGENT

    private val QUALITY_REGEX = Regex("""\b(2160|1440|1080|720|480|360)\s*[pP]\b""")
    private val FOUR_K_REGEX = Regex("""\b(4K|UHD|2160P|ULTRA[\s-_.]?HD)\b""", RegexOption.IGNORE_CASE)
    private val FHD_REGEX = Regex("""\b(FHD|1080P|FULL[\s-_.]?HD)\b""", RegexOption.IGNORE_CASE)
    private val HD_REGEX = Regex("""\b(HD|720P)\b""", RegexOption.IGNORE_CASE)
    private val SD_REGEX = Regex("""\b(SD|480P)\b""", RegexOption.IGNORE_CASE)

    private val PIXELDRAIN_VIEW_REGEX = Regex("""pixeldrain\.com/u/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)

    /**
     * Enhances an ExtractorLink with unthrottled streaming and download headers,
     * normalized stream typing, cleaned labels, and accurate qualities.
     */
    fun optimize(link: ExtractorLink): ExtractorLink {
        val rawUrl = link.url.trim()
        if (rawUrl.isEmpty()) return link

        // 1. Resolve direct download / high-speed streaming endpoint
        val optimizedUrl = resolveDirectStreamUrl(rawUrl)

        // 2. Resolve accurate link type (detect M3U8 vs direct MP4/MKV)
        val resolvedType = resolveCorrectLinkType(optimizedUrl, link.type)

        // 3. Build optimized download headers
        val optimizedHeaders = buildDownloadHeaders(
            existingHeaders = link.headers,
            url = optimizedUrl,
            referer = link.referer,
            linkType = resolvedType
        )

        // 4. Resolve quality tag
        val resolvedQuality = if (link.quality <= Qualities.Unknown.value) {
            extractQualityFromText(link.name, optimizedUrl)
        } else {
            link.quality
        }

        // 5. Clean and format name / badge
        val formattedName = formatLinkName(link.name, resolvedQuality, optimizedUrl)

        val lowerUrl = optimizedUrl.lowercase(Locale.ROOT)
        val effectiveReferer = when {
            optimizedHeaders.containsKey(HEADER_REFERER) -> optimizedHeaders[HEADER_REFERER] ?: ""
            lowerUrl.contains("pixeldrain.com") -> ""
            link.referer.isNotBlank() -> link.referer
            else -> ""
        }

        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = link.source,
            name = formattedName,
            url = optimizedUrl,
            referer = effectiveReferer,
            quality = resolvedQuality,
            type = resolvedType,
            headers = optimizedHeaders,
            extractorData = link.extractorData
        )
    }

    /**
     * Resolves direct download endpoints for hosts that have separate download API routes
     * or query flags needed to trigger max-speed downloads.
     */
    fun resolveDirectStreamUrl(url: String): String {
        val pixeldrainMatch = PIXELDRAIN_VIEW_REGEX.find(url)
        if (pixeldrainMatch != null) {
            val fileId = pixeldrainMatch.groupValues[1]
            return "https://pixeldrain.com/api/file/$fileId?download"
        }
        if (url.contains("pixeldrain.com/api/file/", ignoreCase = true) && !url.contains("download", ignoreCase = true)) {
            return if (url.contains("?")) "$url&download" else "$url?download"
        }
        return url
    }

    /**
     * Ensures M3U8 vs VIDEO vs DASH types are strictly accurate for player and downloader pipelines
     */
    fun resolveCorrectLinkType(url: String, currentType: ExtractorLinkType): ExtractorLinkType {
        val cleanUrl = url.substringBefore("?").substringBefore("#").lowercase(Locale.ROOT)
        val fullLowerUrl = url.lowercase(Locale.ROOT)

        return when {
            // Direct video files take precedence when file extension is explicitly a video container
            cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".mkv") || cleanUrl.endsWith(".webm") ||
            cleanUrl.endsWith(".avi") || cleanUrl.endsWith(".mov") || cleanUrl.endsWith(".flv") -> {
                ExtractorLinkType.VIDEO
            }
            // HLS playlists: check extension or manifest pattern
            cleanUrl.endsWith(".m3u8") || cleanUrl.endsWith("/master.txt") ||
            (cleanUrl.contains("/hls/") && cleanUrl.endsWith(".txt")) ||
            fullLowerUrl.contains(".m3u8") -> {
                ExtractorLinkType.M3U8
            }
            // DASH manifests
            cleanUrl.endsWith(".mpd") || fullLowerUrl.contains(".mpd") -> {
                ExtractorLinkType.DASH
            }
            else -> currentType
        }
    }

    /**
     * Builds HTTP headers tuned for maximum multi-connection chunk download speed
     * and anti-throttling on video CDNs.
     */
    fun buildDownloadHeaders(
        existingHeaders: Map<String, String>,
        url: String,
        referer: String?,
        linkType: ExtractorLinkType = ExtractorLinkType.VIDEO
    ): Map<String, String> {
        val headers = existingHeaders.toMutableMap()

        // 1. Ensure modern browser User-Agent
        if (!headers.keys.any { it.equals(HEADER_USER_AGENT, ignoreCase = true) }) {
            headers[HEADER_USER_AGENT] = DEFAULT_BROWSER_UA
        }

        // 2. Accept any content stream
        if (!headers.keys.any { it.equals(HEADER_ACCEPT, ignoreCase = true) }) {
            headers[HEADER_ACCEPT] = "*/*"
        }

        // 3. Crucial for downloads: identity encoding prevents gzip/deflate on byte ranges
        // which corrupts chunk calculations in multi-threaded downloaders
        if (!headers.keys.any { it.equals(HEADER_ACCEPT_ENCODING, ignoreCase = true) }) {
            headers[HEADER_ACCEPT_ENCODING] = "identity"
        }

        // 4. TCP socket reuse across chunk requests
        if (!headers.keys.any { it.equals(HEADER_CONNECTION, ignoreCase = true) }) {
            headers[HEADER_CONNECTION] = "keep-alive"
        }

        // 5. Never allow static "Range: bytes=0-" in headers: downloaders and ExoPlayer manage byte ranges dynamically
        headers.entries.removeIf { it.key.equals("Range", ignoreCase = true) }

        // 6. Host-specific download optimizations and Referer handling
        val lowerUrl = url.lowercase(Locale.ROOT)
        when {
            lowerUrl.contains("gofile.io") -> {
                headers[HEADER_REFERER] = "https://gofile.io/"
                headers[HEADER_ORIGIN] = "https://gofile.io"
            }
            lowerUrl.contains("pixeldrain.com") -> {
                // PixelDrain direct downloads must NOT send third-party or arbitrary referers
                headers.entries.removeIf { it.key.equals(HEADER_REFERER, ignoreCase = true) }
                headers.entries.removeIf { it.key.equals(HEADER_ORIGIN, ignoreCase = true) }
            }
            else -> {
                val effectiveReferer = when {
                    referer != null && referer.isNotBlank() -> referer
                    headers.keys.any { it.equals(HEADER_REFERER, ignoreCase = true) } -> {
                        headers.entries.firstOrNull { it.key.equals(HEADER_REFERER, ignoreCase = true) }?.value
                    }
                    else -> getHostUrl(url)
                }

                if (!effectiveReferer.isNullOrBlank()) {
                    if (!headers.keys.any { it.equals(HEADER_REFERER, ignoreCase = true) }) {
                        headers[HEADER_REFERER] = effectiveReferer
                    }
                    if (!headers.keys.any { it.equals(HEADER_ORIGIN, ignoreCase = true) }) {
                        val hostOrigin = getHostUrl(effectiveReferer) ?: getHostUrl(url)
                        if (hostOrigin != null) {
                            headers[HEADER_ORIGIN] = hostOrigin
                        }
                    }
                }
            }
        }

        return headers
    }

    fun getHostUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        val parsedUri = runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return null
            val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull()

        if (parsedUri != null) return parsedUri

        // Fallback for unescaped characters / spaces in URLs
        return runCatching {
            val scheme = if (trimmed.startsWith("http://", ignoreCase = true)) "http" else "https"
            val hostMatch = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            val host = hostMatch?.groupValues?.get(1) ?: return null
            "$scheme://$host"
        }.getOrNull()
    }

    fun extractQualityFromText(vararg texts: String?): Int {
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val qualityMatch = QUALITY_REGEX.find(text)
            if (qualityMatch != null) {
                return qualityMatch.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
            }
            if (FOUR_K_REGEX.containsMatchIn(text)) {
                return Qualities.P2160.value
            }
            if (FHD_REGEX.containsMatchIn(text)) {
                return Qualities.P1080.value
            }
            if (HD_REGEX.containsMatchIn(text)) {
                return Qualities.P720.value
            }
        }
        return Qualities.Unknown.value
    }

    fun formatLinkName(currentName: String, quality: Int, url: String): String {
        var name = currentName.trim()
        if (name.isBlank()) {
            name = getHostUrl(url)?.removePrefix("https://")?.removePrefix("http://") ?: "Stream"
        }

        val qualityTag = when (quality) {
            Qualities.P2160.value -> "4K"
            Qualities.P1440.value -> "1440p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            else -> null
        }

        if (qualityTag != null && !name.contains(qualityTag, ignoreCase = true) && !name.contains("${quality}p", ignoreCase = true)) {
            name = "[$qualityTag] $name"
        }

        return name
    }

    /**
     * Canonical deduplication key that normalizes mirrors and strips volatile tokens
     */
    fun canonicalStreamKey(link: ExtractorLink): String {
        val rawUrl = link.url.trim()
        val cleanUrl = runCatching {
            val uri = URI(rawUrl)
            val host = uri.host?.lowercase(Locale.ROOT) ?: ""
            val path = uri.path ?: ""
            val queryParams = uri.rawQuery?.split("&")?.filterNot { param ->
                val key = param.substringBefore("=").lowercase(Locale.ROOT)
                key in listOf("t", "_", "ts", "timestamp", "nonce", "session", "session_id", "cb", "rand")
            }?.sorted()?.joinToString("&")

            if (queryParams.isNullOrEmpty()) "$host$path" else "$host$path?$queryParams"
        }.getOrElse {
            // Fallback for unencoded spaces / special characters
            val host = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: ""
            val pathAndQuery = rawUrl.substringAfter("://").substringAfter("/", "")
            "$host/$pathAndQuery"
        }

        return "$cleanUrl|${link.quality}|${link.type}"
    }
}
