package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * State-Of-The-Art Universal Stream Link Optimizer Engine for CloudStream Extensions
 *
 * Milestone 2 Engine (R3):
 * 1. Anti-throttling HTTP header injection (`Accept-Encoding: identity`, desktop Chrome UA,
 *    Sec-Fetch suite, conflicting header stripping, host-aware Referer/Origin dispatch).
 * 2. Direct unthrottled CDN endpoint rewrites (PixelDrain `/api/file/{id}?download`,
 *    StreamTape `&stream=1`, DoodStream unescaping).
 * 3. Container & manifest auto-detection (M3U8 HLS, DASH MPD, direct MP4/MKV byte-streams,
 *    Azure/AWS media patterns, MIME hints).
 * 4. Bitrate calculation `(sizeBytes * 8) / (durationSec * 1000)` and quality inference.
 * 5. Audio track normalization badges (`[Atmos]`, `[TrueHD]`, `[DTS-HD]`, `[DTS]`, `[5.1]`,
 *    `[7.1]`, `[Dual Audio]`, `[Multi Audio]`, `[AAC]`, `[MP3]`).
 * 6. Multi-CDN mirror deduplication via canonical stream key generation stripping 32+ transient
 *    tokens, CDN cluster normalization (`normalizeHostCluster`), magnet infohash extraction,
 *    and atomic CAS lock-free `StreamDeduplicator`.
 */
object StreamLinkOptimizer {

    // ==================== Header Constants ====================
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_ACCEPT = "Accept"
    const val HEADER_ACCEPT_ENCODING = "Accept-Encoding"
    const val HEADER_CONNECTION = "Connection"
    const val HEADER_REFERER = "Referer"
    const val HEADER_ORIGIN = "Origin"
    const val HEADER_SEC_FETCH_DEST = "Sec-Fetch-Dest"
    const val HEADER_SEC_FETCH_MODE = "Sec-Fetch-Mode"
    const val HEADER_SEC_FETCH_SITE = "Sec-Fetch-Site"
    const val HEADER_SEC_FETCH_STORAGE_ACCESS = "Sec-Fetch-Storage-Access"
    const val HEADER_SEC_GPC = "Sec-GPC"
    const val HEADER_SEC_CH_UA = "sec-ch-ua"
    const val HEADER_SEC_CH_UA_MOBILE = "sec-ch-ua-mobile"
    const val HEADER_SEC_CH_UA_PLATFORM = "sec-ch-ua-platform"

    const val MODERN_DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private const val DEFAULT_BROWSER_UA = USER_AGENT

    // ==================== Static Regex Hoisting ====================
    private val PIXELDRAIN_VIEW_REGEX = Regex(
        """(?:https?://)?(?:www\.)?(?:pixeldrain\.(?:com|dev)|pd\.cybar\.xyz)/u/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val PIXELDRAIN_API_REGEX = Regex(
        """(?:https?://)?(?:www\.)?(?:pixeldrain\.(?:com|dev)|pd\.cybar\.xyz)/api/file/([a-zA-Z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )
    private val STREAMTAPE_GET_VIDEO_REGEX = Regex(
        """(?:streamtape\.com|streamta\.pe|strtape\.[a-z]+|strcloud\.[a-z]+)/get_video""",
        RegexOption.IGNORE_CASE
    )
    private val STREAMTAPE_HOST_REGEX = Regex(
        """(?:streamtape\.com|streamta\.pe|tapecontent\.net|strtape\.[a-z]+|strcloud\.[a-z]+)""",
        RegexOption.IGNORE_CASE
    )
    private val DOOD_HOST_REGEX = Regex(
        """(?:dood\.[a-z]+|ds2play\.[a-z]+|do0od\.[a-z]+|d000d\.[a-z]+|doodstream\.[a-z]+)""",
        RegexOption.IGNORE_CASE
    )
    private val HUBCLOUD_HOST_REGEX = Regex(
        """(?:hubcloud\.[a-z]+|shikshakdaak\.com|gamerxyt\.com)""",
        RegexOption.IGNORE_CASE
    )
    private val GDFLIX_HOST_REGEX = Regex(
        """(?:[a-zA-Z0-9_-]+\.)?gdflix\.[a-z]+""",
        RegexOption.IGNORE_CASE
    )
    private val MIXDROP_HOST_REGEX = Regex(
        """(?:mixdrop\.(?:co|to|sx|bz|ch|ag|vc))""",
        RegexOption.IGNORE_CASE
    )
    private val FILEMOON_HOST_REGEX = Regex(
        """(?:filemoon\.(?:sx|to|in|top))""",
        RegexOption.IGNORE_CASE
    )
    private val VIDHIDE_HOST_REGEX = Regex(
        """(?:vidhide\.(?:com|org)|vidhidepro\.(?:com|org)|vidhideplus\.(?:com|org)|vidhidepre\.(?:com|org)|streamhide\.(?:to|com))""",
        RegexOption.IGNORE_CASE
    )
    private val STREAMWISH_HOST_REGEX = Regex(
        """(?:streamwish\.(?:to|com)|strwish\.(?:com|xyz)|wishembed\.(?:pro|com))""",
        RegexOption.IGNORE_CASE
    )
    private val VOE_HOST_REGEX = Regex(
        """(?:voe\.(?:sx|net)|voe-network\.net|audaciousdefaulthouse\.com|jilliandesitewildly\.com)""",
        RegexOption.IGNORE_CASE
    )
    private val MP4UPLOAD_HOST_REGEX = Regex(
        """(?:mp4upload\.(?:com|org))""",
        RegexOption.IGNORE_CASE
    )
    private val FASTSTREAM_HOST_REGEX = Regex(
        """(?:faststream\.(?:org|co|to|net))""",
        RegexOption.IGNORE_CASE
    )
    private val STREAMRUBY_HOST_REGEX = Regex(
        """(?:streamruby\.(?:com|net)|rubystream\.(?:net|org))""",
        RegexOption.IGNORE_CASE
    )
    private val EMBEDRISE_HOST_REGEX = Regex(
        """(?:embedrise\.(?:org|com))""",
        RegexOption.IGNORE_CASE
    )
    private val RIDOO_HOST_REGEX = Regex(
        """(?:ridoo\.(?:net|com))""",
        RegexOption.IGNORE_CASE
    )
    private val ASNWISH_HOST_REGEX = Regex(
        """(?:asnwish\.(?:com|net))""",
        RegexOption.IGNORE_CASE
    )
    private val LULUVDO_HOST_REGEX = Regex(
        """(?:luluvdo\.(?:com|net)|luluvstream\.(?:com|net))""",
        RegexOption.IGNORE_CASE
    )
    private val STREAMVID_HOST_REGEX = Regex(
        """(?:streamvid\.(?:net|io))""",
        RegexOption.IGNORE_CASE
    )
    private val DROPLOAD_HOST_REGEX = Regex(
        """(?:dropload\.(?:io|com)|dropgalaxy\.(?:com|co))""",
        RegexOption.IGNORE_CASE
    )
    private val FILELIONS_HOST_REGEX = Regex(
        """(?:filelions\.(?:to|online|site|co))""",
        RegexOption.IGNORE_CASE
    )
    private val TWOEMBED_HOST_REGEX = Regex(
        """(?:2embed\.(?:cc|to|skin|me))""",
        RegexOption.IGNORE_CASE
    )
    private val UPSTREAM_HOST_REGEX = Regex(
        """(?:upstream\.(?:to|org))""",
        RegexOption.IGNORE_CASE
    )
    private val VEXSTREAM_HOST_REGEX = Regex(
        """(?:vexstream\.(?:org|net))""",
        RegexOption.IGNORE_CASE
    )
    private val MULTIEMBED_HOST_REGEX = Regex(
        """(?:multiembed\.(?:mov|cc))""",
        RegexOption.IGNORE_CASE
    )

    // Container & Manifest Regexes
    private val DIRECT_VIDEO_EXT_REGEX = Regex("""\.(mp4|mkv|webm|avi|mov|flv|ts|m4v|3gp|wmv|ogv|divx)$""", RegexOption.IGNORE_CASE)
    private val HLS_EXT_REGEX = Regex("""\.(m3u8|m3u)$""", RegexOption.IGNORE_CASE)
    private val DASH_EXT_REGEX = Regex("""\.mpd$""", RegexOption.IGNORE_CASE)
    private val TORRENT_EXT_REGEX = Regex("""\.torrent$""", RegexOption.IGNORE_CASE)
    private val AZURE_HLS_REGEX = Regex("""/manifest\(format=m3u8""", RegexOption.IGNORE_CASE)
    private val AZURE_DASH_REGEX = Regex("""/manifest\(format=mpd""", RegexOption.IGNORE_CASE)
    private val TEXT_PLAYLIST_REGEX = Regex("""/(?:master|playlist|index|list)\.txt$""", RegexOption.IGNORE_CASE)

    // Quality Tag Regexes
    private val QUALITY_EXPLICIT_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(2160|1440|1080|720|576|540|480|360)\s*[pP]?(?:[^a-zA-Z0-9]|$)""")
    private val FOUR_K_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(4K|UHD|2160P?|3840[xX]2160|ULTRA[\s-_.]?HD)(?:[^a-zA-Z0-9]|$)""")
    private val QHD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(1440P?|2560[xX]1440|QHD|2K)(?:[^a-zA-Z0-9]|$)""")
    private val FHD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(FHD|1080P?|1920[xX]1080|FULL[\s-_.]?HD)(?:[^a-zA-Z0-9]|$)""")
    private val HD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(720P?|1280[xX]720|HD)(?:[^a-zA-Z0-9]|$)""")
    private val SD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(480P?|854[xX]480|SD)(?:[^a-zA-Z0-9]|$)""")
    private val P360_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(360P?|640[xX]360)(?:[^a-zA-Z0-9]|$)""")
    private val EXPLICIT_QUALITY_QUERY_REGEX = Regex("""(?i)(?:[?&]|^)(?:quality|res|resolution|height|q)=([0-9]{3,4})[pP]?(?:&|$)""")
    private val EMBEDDED_MEDIA_URL_QUERY_REGEX = Regex("""(?i)(?:[?&]|^)(?:file|url|path|src)=([^&]+)""")

    // Bitrate & Size Regexes
    private val SIZE_REGEX = Regex("""\b(\d+(?:\.\d+)?)\s*(GB|GIB|MB|MIB|KB|KIB|B)\b""", RegexOption.IGNORE_CASE)
    private val BITRATE_TEXT_REGEX = Regex("""\b(\d+(?:\.\d+)?)\s*(kbps|mbps|kb/s|mb/s)\b""", RegexOption.IGNORE_CASE)
    private val BITRATE_K_REGEX = Regex("""\b(\d{3,5})\s*k\b""", RegexOption.IGNORE_CASE)

    // Audio Track & Codec Regexes
    private val AUDIO_ATMOS_REGEX = Regex("""\b(dolby[\s-.]?atmos|atmos)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_TRUEHD_REGEX = Regex("""\b(dolby[\s-.]?truehd|truehd|true-hd)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_DTS_HD_REGEX = Regex("""\b(dts[\s-.]?hd[\s-.]?ma|dts[\s-.]?hd|dts[\s-.:]?x)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_DTS_REGEX = Regex("""\b(dts)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_5_1_REGEX = Regex("""\b(5\.1(?:ch)?|6ch|ddp?[ .]?5\.1|dd[ .]?5\.1|ac3[ .]?5\.1|eac3[ .]?5\.1)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_7_1_REGEX = Regex("""\b(7\.1(?:ch)?|8ch|ddp?[ .]?7\.1|truehd[ .]?7\.1)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_DUAL_REGEX = Regex("""\b(dual[\s-_.]?audio|dual)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_MULTI_REGEX = Regex("""\b(multi[\s-_.]?audio|multi[\s-_.]?lang(?:uage)?)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_AAC_REGEX = Regex("""\b(aac(?:[ .]?[0-9]\.[0-9])?|he-aac)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_MP3_REGEX = Regex("""\b(mp3)\b""", RegexOption.IGNORE_CASE)

    // Video Codec, HDR, Color & Source Regexes (§SOTA Visual Badging Engine)
    private val VIDEO_DV_REGEX = Regex("""\b(dolby[\s-.]?vision|dv|dovi)\b""", RegexOption.IGNORE_CASE)
    private val VIDEO_HDR10_PLUS_REGEX = Regex("""(?:\bhdr10\+|\bhdr10plus\b)""", RegexOption.IGNORE_CASE)
    private val VIDEO_HDR_REGEX = Regex("""\b(hdr10|hdr)\b""", RegexOption.IGNORE_CASE)
    private val VIDEO_10BIT_REGEX = Regex("""\b(10[\s-.]?bit|hi10p)\b""", RegexOption.IGNORE_CASE)
    private val VIDEO_HEVC_REGEX = Regex("""\b(hevc|h[\s-.]?265|x265)\b""", RegexOption.IGNORE_CASE)
    private val VIDEO_AV1_REGEX = Regex("""\b(av1)\b""", RegexOption.IGNORE_CASE)
    private val VIDEO_AVC_REGEX = Regex("""\b(avc|h[\s-.]?264|x264)\b""", RegexOption.IGNORE_CASE)
    private val SOURCE_REMUX_REGEX = Regex("""\b(remux)\b""", RegexOption.IGNORE_CASE)
    private val SOURCE_BLURAY_REGEX = Regex("""\b(bluray|bdrip|brrip)\b""", RegexOption.IGNORE_CASE)
    private val SOURCE_WEBDL_REGEX = Regex("""\b(web[\s-.]?dl)\b""", RegexOption.IGNORE_CASE)
    private val SOURCE_WEBRIP_REGEX = Regex("""\b(webrip)\b""", RegexOption.IGNORE_CASE)

    // CDN Cluster Edge Pattern
    private val CDN_SUBDOMAIN_PATTERN = Regex(
        """^(?:[a-z]{2,4}[-_])?(?:cdn|edge|node|srv|server|store|mirror|stream|storage|video|fs|play|worker|hls|s|v)[-_0-9a-z]*\d*$|^(?:[a-z]{2,3}\d+)$""",
        RegexOption.IGNORE_CASE
    )

    // 40+ Transient Query Parameters to Strip for Canonical Deduplication
    private val TRANSIENT_QUERY_PARAMS = setOf(
        // Timestamps & Expirations (12)
        "t", "_", "ts", "timestamp", "exp", "expire", "expires", "expiry", "deadline", "valid", "validity", "time",
        // Signatures, Hashes & Nonces (21)
        "sig", "signature", "sign", "h", "hash", "md5", "key", "auth", "auth_key", "verify", "verification", "hmac", "token", "st", "nonce", "csrf", "xsrf", "auth_token", "access_token", "play_token", "download_token",
        // Session & Request Tracking (9)
        "session", "session_id", "sid", "sessionid", "req_id", "request_id", "client_id", "uuid", "state",
        // IP & Geo-Locking (8)
        "ip", "ip_token", "user_ip", "client_ip", "geo", "country", "asn", "wsiphost",
        // Cache Busters & Stream Routing (8)
        "cb", "rand", "rnd", "random", "nocache", "cache_buster", "stream_id", "sub_id", "hls_key", "dl", "direct"
    )

    // ==================== Primary Optimization Entry Points ====================

    /**
     * Enhances an ExtractorLink with unthrottled streaming and download headers,
     * normalized stream typing, cleaned labels, direct endpoint rewrites, and accurate qualities.
     */
    fun optimize(link: ExtractorLink): ExtractorLink = optimize(link, durationSec = null, sizeBytes = null)

    /**
     * Overloaded optimize supporting duration and size for bitrate estimation and quality inference.
     */
    fun optimize(
        link: ExtractorLink,
        durationSec: Double? = null,
        sizeBytes: Long? = null
    ): ExtractorLink {
        val rawUrl = link.url.trim()
        if (rawUrl.isEmpty()) return link

        // Magnet / non-HTTP links pass through with formatting only
        if (rawUrl.startsWith("magnet:", ignoreCase = true) ||
            (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true))
        ) {
            val resolvedQuality = if (link.quality <= 0 || link.quality == Qualities.Unknown.value) {
                extractQualityFromText(link.name, rawUrl)
            } else {
                link.quality
            }
            val audioBadges = extractAudioBadges(link.name, link.extractorData)
            val videoBadges = extractVideoBadges(link.name, link.extractorData)
            val formattedName = formatLinkName(
                currentName = link.name,
                quality = resolvedQuality,
                url = rawUrl,
                bitrateKbps = null,
                audioBadges = audioBadges,
                videoBadges = videoBadges
            )
            @Suppress("DEPRECATION")
            return ExtractorLink(
                source = link.source,
                name = formattedName,
                url = rawUrl,
                referer = link.referer,
                quality = resolvedQuality,
                type = if (rawUrl.startsWith("magnet:", ignoreCase = true)) ExtractorLinkType.MAGNET else link.type,
                headers = link.headers,
                extractorData = link.extractorData
            )
        }

        // 1. Direct CDN / endpoint rewrites
        val optimizedUrl = resolveDirectStreamUrl(rawUrl)

        // 2. Container & manifest auto-detection
        val resolvedType = resolveCorrectLinkType(optimizedUrl, link.type, headers = link.headers)

        // 3. Size & bitrate estimation
        val effectiveSizeBytes = sizeBytes ?: parseSizeBytes(link.name)
        val estimatedBitrate = if (effectiveSizeBytes != null && durationSec != null && durationSec > 0.0) {
            calculateBitrateKbps(effectiveSizeBytes, durationSec)
        } else {
            parseBitrateKbpsFromText(link.name)
        }

        // 4. Quality resolution
        val extractedQuality = extractQualityFromText(link.name, optimizedUrl)
        val resolvedQuality = when {
            link.quality > 0 && link.quality != Qualities.Unknown.value -> link.quality
            extractedQuality > 0 && extractedQuality != Qualities.Unknown.value -> extractedQuality
            estimatedBitrate != null -> inferQualityFromBitrate(estimatedBitrate)
            else -> Qualities.Unknown.value
        }

        // 5. Audio and video badging
        val audioBadges = extractAudioBadges(link.name, link.extractorData)
        val videoBadges = extractVideoBadges(link.name, link.extractorData)

        // 6. Name formatting
        val formattedName = formatLinkName(
            currentName = link.name,
            quality = resolvedQuality,
            url = optimizedUrl,
            bitrateKbps = estimatedBitrate,
            audioBadges = audioBadges,
            videoBadges = videoBadges
        )

        // 7. Optimized headers
        val optimizedHeaders = buildDownloadHeaders(
            existingHeaders = link.headers,
            url = optimizedUrl,
            referer = link.referer,
            linkType = resolvedType,
            source = link.source,
            name = link.name
        )

        // 8. Effective referer
        val effectiveReferer = getEffectiveReferer(
            optimizedUrl,
            link.referer,
            optimizedHeaders,
            source = link.source,
            name = link.name
        )

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

    // ==================== Direct Endpoint Rewriting Engine ====================

    /**
     * Rewrites file host viewer URLs into direct high-speed unthrottled API / streaming endpoints.
     */
    fun resolveDirectStreamUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("magnet:", ignoreCase = true)) {
            return trimmed
        }

        // Clean JSON-escaped slashes (\/ -> /)
        var cleanedUrl = if (trimmed.contains("""\/""")) trimmed.replace("""\/""", "/") else trimmed

        // 1. PixelDrain viewer rewrite: /u/{id} -> /api/file/{id}?download
        val pdViewMatch = PIXELDRAIN_VIEW_REGEX.find(cleanedUrl)
        if (pdViewMatch != null) {
            val fileId = pdViewMatch.groupValues[1]
            return "https://pixeldrain.com/api/file/$fileId?download"
        }

        // 1b. PixelDrain API file URL without download parameter
        if (PIXELDRAIN_API_REGEX.containsMatchIn(cleanedUrl)) {
            if (!cleanedUrl.contains("download", ignoreCase = true)) {
                return if (cleanedUrl.contains("?")) "$cleanedUrl&download" else "$cleanedUrl?download"
            }
            return cleanedUrl
        }

        // 2. StreamTape progressive streaming parameter injection
        if (STREAMTAPE_GET_VIDEO_REGEX.containsMatchIn(cleanedUrl) && !cleanedUrl.contains("stream=1", ignoreCase = true)) {
            cleanedUrl = if (cleanedUrl.contains("?")) "$cleanedUrl&stream=1" else "$cleanedUrl?stream=1"
        }

        return cleanedUrl
    }

    // ==================== Anti-Throttling Header Engine ====================

    /**
     * Builds HTTP headers tuned for maximum multi-connection chunk download speed
     * and anti-throttling across video CDNs.
     */
    fun buildDownloadHeaders(
        existingHeaders: Map<String, String>,
        url: String,
        referer: String?,
        linkType: ExtractorLinkType = ExtractorLinkType.VIDEO,
        source: String? = null,
        name: String? = null
    ): Map<String, String> {
        if (url.startsWith("magnet:", ignoreCase = true) ||
            (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true))
        ) {
            return existingHeaders
        }

        val headers = existingHeaders.toMutableMap()

        // 1. Ensure modern browser User-Agent
        if (!headers.keys.any { it.equals(HEADER_USER_AGENT, ignoreCase = true) }) {
            headers[HEADER_USER_AGENT] = DEFAULT_BROWSER_UA
        }

        // 2. Accept any content stream
        if (!headers.keys.any { it.equals(HEADER_ACCEPT, ignoreCase = true) }) {
            headers[HEADER_ACCEPT] = "*/*"
        }

        // 3. Crucial: identity encoding prevents gzip/deflate on byte ranges,
        // preventing corrupt chunk downloads and broken video seeking
        headers.entries.removeIf { it.key.equals(HEADER_ACCEPT_ENCODING, ignoreCase = true) }
        headers[HEADER_ACCEPT_ENCODING] = "identity"

        // 4. TCP socket reuse across chunk requests
        if (!headers.keys.any { it.equals(HEADER_CONNECTION, ignoreCase = true) }) {
            headers[HEADER_CONNECTION] = "keep-alive"
        }

        // 5. Strip dangerous/conflicting headers
        headers.entries.removeIf { it.key.equals("Range", ignoreCase = true) }
        headers.entries.removeIf { it.key.equals("Host", ignoreCase = true) }
        headers.entries.removeIf { it.key.equals("Content-Length", ignoreCase = true) }

        // 6. Modern Sec-Fetch request metadata (bypasses Cloudflare bot detection on media)
        if (!headers.keys.any { it.equals(HEADER_SEC_FETCH_DEST, ignoreCase = true) }) {
            headers[HEADER_SEC_FETCH_DEST] = "video"
        }
        if (!headers.keys.any { it.equals(HEADER_SEC_FETCH_MODE, ignoreCase = true) }) {
            headers[HEADER_SEC_FETCH_MODE] = "no-cors"
        }
        if (!headers.keys.any { it.equals(HEADER_SEC_FETCH_SITE, ignoreCase = true) }) {
            headers[HEADER_SEC_FETCH_SITE] = "cross-site"
        }

        // 7. Host-specific download optimizations and Referer / Origin handling
        val lowerUrl = url.lowercase(Locale.ROOT)
        val hadVidlinkHeader = headers.entries.any {
            (it.key.equals(HEADER_REFERER, ignoreCase = true) || it.key.equals(HEADER_ORIGIN, ignoreCase = true)) &&
                it.value.contains("vidlink.pro", ignoreCase = true)
        } || referer?.contains("vidlink.pro", ignoreCase = true) == true
        val isVidlink = source?.contains("vidlink", ignoreCase = true) == true ||
            name?.contains("vidlink", ignoreCase = true) == true ||
            lowerUrl.contains("vidlink.pro") ||
            lowerUrl.contains("hakunaymatata") ||
            hadVidlinkHeader
        val isYFlix = source?.contains("yflix", ignoreCase = true) == true ||
            name?.contains("yflix", ignoreCase = true) == true ||
            source?.contains("moviesflix", ignoreCase = true) == true ||
            name?.contains("moviesflix", ignoreCase = true) == true ||
            lowerUrl.contains("yflix.to") ||
            lowerUrl.contains("moviesflix") ||
            referer?.contains("yflix.to", ignoreCase = true) == true
        val isVidcore = source?.contains("vidcore", ignoreCase = true) == true ||
            name?.contains("vidcore", ignoreCase = true) == true ||
            lowerUrl.contains("vidcore.io") ||
            (lowerUrl.contains("quietridge.top") && source?.contains("vidup", ignoreCase = true) != true && name?.contains("vidup", ignoreCase = true) != true) ||
            referer?.contains("vidcore.io", ignoreCase = true) == true
        val isVidup = source?.contains("vidup", ignoreCase = true) == true ||
            name?.contains("vidup", ignoreCase = true) == true ||
            lowerUrl.contains("vidup.to") ||
            lowerUrl.contains("keenanchor.top") ||
            referer?.contains("vidup.to", ignoreCase = true) == true
        val isCineJoy = source?.contains("cinejoy", ignoreCase = true) == true ||
            name?.contains("cinejoy", ignoreCase = true) == true ||
            lowerUrl.contains("cinejoy.to") ||
            lowerUrl.contains("cinejoy.pk") ||
            lowerUrl.contains("solarpanelcleaning") ||
            lowerUrl.contains("api.wing.st") ||
            referer?.contains("cinejoy.to", ignoreCase = true) == true ||
            referer?.contains("cinejoy.pk", ignoreCase = true) == true
        val isHexa = source?.contains("hexa", ignoreCase = true) == true ||
            name?.contains("hexa", ignoreCase = true) == true ||
            source?.contains("embedsu", ignoreCase = true) == true ||
            name?.contains("embedsu", ignoreCase = true) == true ||
            source?.contains("embed.su", ignoreCase = true) == true ||
            name?.contains("embed.su", ignoreCase = true) == true ||
            source?.contains("flixer", ignoreCase = true) == true ||
            name?.contains("flixer", ignoreCase = true) == true ||
            lowerUrl.contains("hexa.su") ||
            lowerUrl.contains("embed.su") ||
            lowerUrl.contains("flixer.su")
        val isAutoembed = source?.contains("autoembed", ignoreCase = true) == true ||
            name?.contains("autoembed", ignoreCase = true) == true ||
            lowerUrl.contains("autoembed.cc") ||
            lowerUrl.contains("player.autoembed.cc") ||
            lowerUrl.contains("autoembed.to") ||
            lowerUrl.contains("autoembed.co")
        val isVideasy = source?.contains("videasy", ignoreCase = true) == true ||
            name?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("cineby", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby", ignoreCase = true) ||
                    it.value.contains("speedracelight", ignoreCase = true)
            }
        val isVidfast = (source?.contains("vidfast", ignoreCase = true) == true ||
            name?.contains("vidfast", ignoreCase = true) == true ||
            lowerUrl.contains("vidfast.pro") ||
            lowerUrl.contains("vidfast.vc") ||
            lowerUrl.contains("hypergate.top") ||
            (lowerUrl.contains("peakstorm.top") && !isVideasy)) && !isVideasy
        val isVidSrc = source?.contains("vidsrc", ignoreCase = true) == true ||
            name?.contains("vidsrc", ignoreCase = true) == true ||
            lowerUrl.contains("vidsrc.cc") ||
            lowerUrl.contains("vidsrc.to") ||
            lowerUrl.contains("vidsrc2.to") ||
            lowerUrl.contains("vidsrc.xyz") ||
            lowerUrl.contains("vidsrc.me") ||
            lowerUrl.contains("vidsrc.in") ||
            lowerUrl.contains("vidsrc.pm") ||
            lowerUrl.contains("vidsrc.net")
        when {
            lowerUrl.contains("pixeldrain.com") || lowerUrl.contains("pixeldrain.dev") || lowerUrl.contains("pd.cybar.xyz") -> {
                headers.entries.removeIf { it.key.equals(HEADER_REFERER, ignoreCase = true) }
                headers.entries.removeIf { it.key.equals(HEADER_ORIGIN, ignoreCase = true) }
            }
            isVidlink -> {
                headers.entries.removeIf { (k, v) ->
                    (k.equals(HEADER_REFERER, ignoreCase = true) || k.equals(HEADER_ORIGIN, ignoreCase = true)) &&
                    (v.contains("vidlink.pro", ignoreCase = true) || v.contains("embed", ignoreCase = true) || v.isBlank())
                }
                headers[HEADER_USER_AGENT] = "com.community.oneroom/50020115 (Linux; U; Android 15; en_US; OPPO CPH2579; Build/AP3A.240905.015.A2; Cronet/140.0.7339.51)"
                headers[HEADER_ACCEPT] = "*/*"
            }
            STREAMTAPE_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://streamtape.com/"
                headers[HEADER_ORIGIN] = "https://streamtape.com"
            }
            DOOD_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                val doodHost = getHostUrl(url) ?: "https://dood.re"
                val effectiveDoodReferer = if (doodHost.endsWith("/")) doodHost else "$doodHost/"
                headers[HEADER_REFERER] = effectiveDoodReferer
                headers[HEADER_ORIGIN] = doodHost.removeSuffix("/")
            }
            HUBCLOUD_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://hubcloud.one/"
                headers[HEADER_ORIGIN] = "https://hubcloud.one"
            }
            GDFLIX_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://gdflix.top/"
                headers[HEADER_ORIGIN] = "https://gdflix.top"
            }
            MIXDROP_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://mixdrop.ag/"
                headers[HEADER_ORIGIN] = "https://mixdrop.ag"
            }
            FILEMOON_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://filemoon.sx/"
                headers[HEADER_ORIGIN] = "https://filemoon.sx"
            }
            VIDHIDE_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://vidhide.com/"
                headers[HEADER_ORIGIN] = "https://vidhide.com"
            }
            STREAMWISH_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://streamwish.to/"
                headers[HEADER_ORIGIN] = "https://streamwish.to"
            }
            VOE_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://voe.sx/"
                headers[HEADER_ORIGIN] = "https://voe.sx"
            }
            MP4UPLOAD_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://www.mp4upload.com/"
                headers[HEADER_ORIGIN] = "https://www.mp4upload.com"
            }
            FASTSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://faststream.org/"
                headers[HEADER_ORIGIN] = "https://faststream.org"
            }
            STREAMRUBY_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://streamruby.com/"
                headers[HEADER_ORIGIN] = "https://streamruby.com"
            }
            EMBEDRISE_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://embedrise.org/"
                headers[HEADER_ORIGIN] = "https://embedrise.org"
            }
            RIDOO_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://ridoo.net/"
                headers[HEADER_ORIGIN] = "https://ridoo.net"
            }
            ASNWISH_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://asnwish.com/"
                headers[HEADER_ORIGIN] = "https://asnwish.com"
            }
            LULUVDO_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://luluvdo.com/"
                headers[HEADER_ORIGIN] = "https://luluvdo.com"
            }
            STREAMVID_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://streamvid.net/"
                headers[HEADER_ORIGIN] = "https://streamvid.net"
            }
            DROPLOAD_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://dropload.io/"
                headers[HEADER_ORIGIN] = "https://dropload.io"
            }
            FILELIONS_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://filelions.to/"
                headers[HEADER_ORIGIN] = "https://filelions.to"
            }
            TWOEMBED_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://2embed.cc/"
                headers[HEADER_ORIGIN] = "https://2embed.cc"
            }
            UPSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://upstream.to/"
                headers[HEADER_ORIGIN] = "https://upstream.to"
            }
            VEXSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://vexstream.org/"
                headers[HEADER_ORIGIN] = "https://vexstream.org"
            }
            MULTIEMBED_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                headers[HEADER_REFERER] = "https://multiembed.mov/"
                headers[HEADER_ORIGIN] = "https://multiembed.mov"
            }
            lowerUrl.contains("gofile.io") -> {
                headers[HEADER_REFERER] = "https://gofile.io/"
                headers[HEADER_ORIGIN] = "https://gofile.io"
            }
            lowerUrl.contains("yflix.to") || isYFlix -> {
                headers[HEADER_REFERER] = "https://yflix.to/"
                headers[HEADER_ORIGIN] = "https://yflix.to"
            }
            lowerUrl.contains("vidcore.io") || isVidcore -> {
                headers[HEADER_REFERER] = "https://vidcore.io/"
                headers[HEADER_ORIGIN] = "https://vidcore.io"
            }
            lowerUrl.contains("vidup.to") || isVidup -> {
                headers[HEADER_REFERER] = "https://vidup.to/"
                headers[HEADER_ORIGIN] = "https://vidup.to"
            }
            lowerUrl.contains("solarpanelcleaning") || referer?.contains("solarpanelcleaning") == true || isCineJoy -> {
                headers[HEADER_REFERER] = "https://solarpanelcleaning.cc/"
                headers[HEADER_ORIGIN] = "https://solarpanelcleaning.cc"
            }
            lowerUrl.contains("cinejoy.pk") || lowerUrl.contains("cinejoy.to") -> {
                headers[HEADER_REFERER] = "https://cinejoy.pk/"
                headers[HEADER_ORIGIN] = "https://cinejoy.pk"
            }
            lowerUrl.contains("embed.su") || lowerUrl.contains("hexa.su") || lowerUrl.contains("flixer.su") || isHexa -> {
                val hexaHost = when {
                    lowerUrl.contains("embed.su") || referer?.contains("embed.su") == true ||
                        source?.contains("embedsu", ignoreCase = true) == true || source?.contains("embed.su", ignoreCase = true) == true ||
                        name?.contains("embedsu", ignoreCase = true) == true || name?.contains("embed.su", ignoreCase = true) == true -> "https://embed.su/"
                    lowerUrl.contains("flixer.su") || lowerUrl.contains("flixer") || referer?.contains("flixer.su") == true || referer?.contains("flixer") == true ||
                        source?.contains("flixersu", ignoreCase = true) == true || source?.contains("flixer", ignoreCase = true) == true ||
                        name?.contains("flixersu", ignoreCase = true) == true || name?.contains("flixer", ignoreCase = true) == true -> "https://flixer.su/"
                    else -> "https://hexa.su/"
                }
                headers[HEADER_REFERER] = hexaHost
                headers[HEADER_ORIGIN] = hexaHost.removeSuffix("/")
            }
            lowerUrl.contains("autoembed.cc") || lowerUrl.contains("player.autoembed.cc") || isAutoembed -> {
                headers[HEADER_REFERER] = "https://player.autoembed.cc/"
                headers[HEADER_ORIGIN] = "https://player.autoembed.cc"
            }
            (lowerUrl.contains("peakstorm.top") || lowerUrl.contains("hypergate.top") ||
            lowerUrl.contains("vidfast.pro") || lowerUrl.contains("vidfast.vc") || isVidfast) && !isVideasy -> {
                headers[HEADER_REFERER] = "https://vidfast.vc/"
                headers[HEADER_ORIGIN] = "https://vidfast.vc"
            }
            isVideasy && (lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc")) -> {
                headers[HEADER_REFERER] = "https://www.cineby.sc/"
                headers[HEADER_ORIGIN] = "https://www.cineby.sc"
            }
            isVideasy || lowerUrl.contains("speedracelight.com") || lowerUrl.contains("videasy.to") ||
            (lowerUrl.contains("videasy") && !lowerUrl.contains("videasy.net")) ||
            (lowerUrl.contains("peakstorm.top") && isVideasy) -> {
                headers[HEADER_REFERER] = "https://player.videasy.to/"
                headers[HEADER_ORIGIN] = "https://player.videasy.to"
            }
            lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc") -> {
                headers[HEADER_REFERER] = "https://www.cineby.sc/"
                headers[HEADER_ORIGIN] = "https://www.cineby.sc"
            }
            lowerUrl.contains("febbox.com") -> {
                headers[HEADER_REFERER] = "https://www.febbox.com/"
                headers[HEADER_ORIGIN] = "https://www.febbox.com"
                headers["Accept-Ranges"] = "bytes"
            }
            lowerUrl.contains("vidsrc.cc") -> {
                headers[HEADER_REFERER] = "https://vidsrc.cc/"
                headers[HEADER_ORIGIN] = "https://vidsrc.cc"
            }
            lowerUrl.contains("vidsrc.to") || lowerUrl.contains("vidsrc2.to") || lowerUrl.contains("vidsrc.net") -> {
                headers[HEADER_REFERER] = "https://vidsrc.to/"
                headers[HEADER_ORIGIN] = "https://vidsrc.to"
            }
            lowerUrl.contains("vidsrc.xyz") -> {
                headers[HEADER_REFERER] = "https://vidsrc.xyz/"
                headers[HEADER_ORIGIN] = "https://vidsrc.xyz"
            }
            lowerUrl.contains("vidsrc.me") -> {
                headers[HEADER_REFERER] = "https://vidsrc.me/"
                headers[HEADER_ORIGIN] = "https://vidsrc.me"
            }
            lowerUrl.contains("vidsrc.in") -> {
                headers[HEADER_REFERER] = "https://vidsrc.in/"
                headers[HEADER_ORIGIN] = "https://vidsrc.in"
            }
            lowerUrl.contains("vidsrc.pm") -> {
                headers[HEADER_REFERER] = "https://vidsrc.pm/"
                headers[HEADER_ORIGIN] = "https://vidsrc.pm"
            }
            lowerUrl.contains("shadowlandschronicles.com") -> {
                headers[HEADER_REFERER] = "https://shadowlandschronicles.com/"
                headers[HEADER_ORIGIN] = "https://shadowlandschronicles.com"
            }
            lowerUrl.contains("cloudnestra.com") -> {
                headers[HEADER_REFERER] = "https://cloudnestra.com/"
                headers[HEADER_ORIGIN] = "https://cloudnestra.com"
            }
            lowerUrl.contains("thepixelpioneer.com") -> {
                headers[HEADER_REFERER] = "https://thepixelpioneer.com/"
                headers[HEADER_ORIGIN] = "https://thepixelpioneer.com"
            }
            lowerUrl.contains("putgate.org") -> {
                headers[HEADER_REFERER] = "https://putgate.org/"
                headers[HEADER_ORIGIN] = "https://putgate.org"
            }
            lowerUrl.contains("whisperingpineslifestyle.com") -> {
                headers[HEADER_REFERER] = "https://whisperingpineslifestyle.com/"
                headers[HEADER_ORIGIN] = "https://whisperingpineslifestyle.com"
            }
            isVidSrc -> {
                val vidsrcHost = when {
                    source?.contains("vidsrccc", ignoreCase = true) == true || name?.contains("vidsrc cc", ignoreCase = true) == true -> "https://vidsrc.cc/"
                    source?.contains("vidsrcto", ignoreCase = true) == true || name?.contains("vidsrc to", ignoreCase = true) == true ||
                        source?.contains("vidsrcnet", ignoreCase = true) == true || name?.contains("vidsrc net", ignoreCase = true) == true -> "https://vidsrc.to/"
                    source?.contains("vidsrcme", ignoreCase = true) == true || name?.contains("vidsrc me", ignoreCase = true) == true -> "https://vidsrc.me/"
                    source?.contains("vidsrcin", ignoreCase = true) == true || name?.contains("vidsrc in", ignoreCase = true) == true -> "https://vidsrc.in/"
                    source?.contains("vidsrcpm", ignoreCase = true) == true || name?.contains("vidsrc pm", ignoreCase = true) == true -> "https://vidsrc.pm/"
                    !referer.isNullOrBlank() && referer.contains("vidsrc", ignoreCase = true) -> referer
                    else -> "https://vidsrc.xyz/"
                }
                headers[HEADER_REFERER] = vidsrcHost
                headers[HEADER_ORIGIN] = vidsrcHost.removeSuffix("/")
            }
            else -> {
                val effectiveReferer = when {
                    !referer.isNullOrBlank() -> referer
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

        if (linkType == ExtractorLinkType.VIDEO && !isVidlink) {
            if (!headers.keys.any { it.equals("Accept-Ranges", ignoreCase = true) }) {
                headers["Accept-Ranges"] = "bytes"
            }
        }

        return headers
    }

    /**
     * Computes the effective Referer header string for ExtractorLink.referer.
     */
    fun getEffectiveReferer(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        source: String? = null,
        name: String? = null
    ): String {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val isVideasy = source?.contains("videasy", ignoreCase = true) == true ||
            name?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("cineby", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby", ignoreCase = true) ||
                    it.value.contains("speedracelight", ignoreCase = true)
            }
        val hadVidlinkHeader = headers.entries.any {
            (it.key.equals(HEADER_REFERER, ignoreCase = true) || it.key.equals(HEADER_ORIGIN, ignoreCase = true)) &&
                it.value.contains("vidlink.pro", ignoreCase = true)
        }
        val isVidlink = source?.contains("vidlink", ignoreCase = true) == true ||
            name?.contains("vidlink", ignoreCase = true) == true ||
            lowerUrl.contains("vidlink.pro") ||
            lowerUrl.contains("hakunaymatata") ||
            referer?.contains("vidlink.pro", ignoreCase = true) == true ||
            hadVidlinkHeader
        val isYFlix = source?.contains("yflix", ignoreCase = true) == true ||
            name?.contains("yflix", ignoreCase = true) == true ||
            source?.contains("moviesflix", ignoreCase = true) == true ||
            name?.contains("moviesflix", ignoreCase = true) == true ||
            lowerUrl.contains("yflix.to") ||
            lowerUrl.contains("moviesflix") ||
            referer?.contains("yflix.to", ignoreCase = true) == true
        val isVidcore = source?.contains("vidcore", ignoreCase = true) == true ||
            name?.contains("vidcore", ignoreCase = true) == true ||
            lowerUrl.contains("vidcore.io") ||
            (lowerUrl.contains("quietridge.top") && source?.contains("vidup", ignoreCase = true) != true && name?.contains("vidup", ignoreCase = true) != true) ||
            referer?.contains("vidcore.io", ignoreCase = true) == true
        val isVidup = source?.contains("vidup", ignoreCase = true) == true ||
            name?.contains("vidup", ignoreCase = true) == true ||
            lowerUrl.contains("vidup.to") ||
            lowerUrl.contains("keenanchor.top") ||
            referer?.contains("vidup.to", ignoreCase = true) == true
        val isCineJoy = source?.contains("cinejoy", ignoreCase = true) == true ||
            name?.contains("cinejoy", ignoreCase = true) == true ||
            lowerUrl.contains("cinejoy.to") ||
            lowerUrl.contains("cinejoy.pk") ||
            lowerUrl.contains("solarpanelcleaning") ||
            lowerUrl.contains("api.wing.st") ||
            referer?.contains("cinejoy.to", ignoreCase = true) == true ||
            referer?.contains("cinejoy.pk", ignoreCase = true) == true
        val isHexa = source?.contains("hexa", ignoreCase = true) == true ||
            name?.contains("hexa", ignoreCase = true) == true ||
            source?.contains("embedsu", ignoreCase = true) == true ||
            name?.contains("embedsu", ignoreCase = true) == true ||
            source?.contains("embed.su", ignoreCase = true) == true ||
            name?.contains("embed.su", ignoreCase = true) == true ||
            source?.contains("flixer", ignoreCase = true) == true ||
            name?.contains("flixer", ignoreCase = true) == true ||
            lowerUrl.contains("hexa.su") ||
            lowerUrl.contains("embed.su") ||
            lowerUrl.contains("flixer.su")
        val isAutoembed = source?.contains("autoembed", ignoreCase = true) == true ||
            name?.contains("autoembed", ignoreCase = true) == true ||
            lowerUrl.contains("autoembed.cc") ||
            lowerUrl.contains("player.autoembed.cc") ||
            lowerUrl.contains("autoembed.to") ||
            lowerUrl.contains("autoembed.co")
        val isVidfast = (source?.contains("vidfast", ignoreCase = true) == true ||
            name?.contains("vidfast", ignoreCase = true) == true ||
            lowerUrl.contains("vidfast.pro") ||
            lowerUrl.contains("vidfast.vc") ||
            lowerUrl.contains("hypergate.top") ||
            (lowerUrl.contains("peakstorm.top") && !isVideasy)) && !isVideasy
        val isVidSrc = source?.contains("vidsrc", ignoreCase = true) == true ||
            name?.contains("vidsrc", ignoreCase = true) == true ||
            lowerUrl.contains("vidsrc.cc") ||
            lowerUrl.contains("vidsrc.to") ||
            lowerUrl.contains("vidsrc2.to") ||
            lowerUrl.contains("vidsrc.xyz") ||
            lowerUrl.contains("vidsrc.me") ||
            lowerUrl.contains("vidsrc.in") ||
            lowerUrl.contains("vidsrc.pm") ||
            lowerUrl.contains("vidsrc.net")
        return when {
            lowerUrl.contains("pixeldrain.com") || lowerUrl.contains("pixeldrain.dev") || lowerUrl.contains("pd.cybar.xyz") -> ""
            isVidlink -> {
                headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) &&
                    !it.value.contains("vidlink.pro", ignoreCase = true) &&
                    !it.value.contains("embed", ignoreCase = true)
                }?.value ?: ""
            }
            STREAMTAPE_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://streamtape.com/"
            DOOD_HOST_REGEX.containsMatchIn(lowerUrl) -> {
                val host = getHostUrl(url) ?: "https://dood.re"
                if (host.endsWith("/")) host else "$host/"
            }
            HUBCLOUD_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://hubcloud.one/"
            GDFLIX_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://gdflix.top/"
            MIXDROP_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://mixdrop.ag/"
            FILEMOON_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://filemoon.sx/"
            VIDHIDE_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://vidhide.com/"
            STREAMWISH_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://streamwish.to/"
            VOE_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://voe.sx/"
            MP4UPLOAD_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://www.mp4upload.com/"
            FASTSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://faststream.org/"
            STREAMRUBY_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://streamruby.com/"
            EMBEDRISE_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://embedrise.org/"
            RIDOO_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://ridoo.net/"
            ASNWISH_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://asnwish.com/"
            LULUVDO_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://luluvdo.com/"
            STREAMVID_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://streamvid.net/"
            DROPLOAD_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://dropload.io/"
            FILELIONS_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://filelions.to/"
            TWOEMBED_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://2embed.cc/"
            UPSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://upstream.to/"
            VEXSTREAM_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://vexstream.org/"
            MULTIEMBED_HOST_REGEX.containsMatchIn(lowerUrl) -> "https://multiembed.mov/"
            lowerUrl.contains("gofile.io") -> "https://gofile.io/"
            lowerUrl.contains("yflix.to") || isYFlix -> "https://yflix.to/"
            lowerUrl.contains("vidcore.io") || isVidcore -> "https://vidcore.io/"
            lowerUrl.contains("vidup.to") || isVidup -> "https://vidup.to/"
            lowerUrl.contains("solarpanelcleaning") || referer?.contains("solarpanelcleaning") == true || isCineJoy -> "https://solarpanelcleaning.cc/"
            lowerUrl.contains("cinejoy.pk") || lowerUrl.contains("cinejoy.to") -> "https://cinejoy.pk/"
            lowerUrl.contains("embed.su") || lowerUrl.contains("hexa.su") || lowerUrl.contains("flixer.su") || isHexa -> {
                when {
                    lowerUrl.contains("embed.su") || referer?.contains("embed.su") == true ||
                        source?.contains("embedsu", ignoreCase = true) == true || source?.contains("embed.su", ignoreCase = true) == true ||
                        name?.contains("embedsu", ignoreCase = true) == true || name?.contains("embed.su", ignoreCase = true) == true -> "https://embed.su/"
                    lowerUrl.contains("flixer.su") || lowerUrl.contains("flixer") || referer?.contains("flixer.su") == true || referer?.contains("flixer") == true ||
                        source?.contains("flixersu", ignoreCase = true) == true || source?.contains("flixer", ignoreCase = true) == true ||
                        name?.contains("flixersu", ignoreCase = true) == true || name?.contains("flixer", ignoreCase = true) == true -> "https://flixer.su/"
                    else -> "https://hexa.su/"
                }
            }
            lowerUrl.contains("autoembed.cc") || lowerUrl.contains("player.autoembed.cc") || isAutoembed -> "https://player.autoembed.cc/"
            (lowerUrl.contains("peakstorm.top") || lowerUrl.contains("hypergate.top") ||
            lowerUrl.contains("vidfast.pro") || lowerUrl.contains("vidfast.vc") || isVidfast) && !isVideasy -> "https://vidfast.vc/"
            isVideasy && (lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc")) -> "https://www.cineby.sc/"
            isVideasy || lowerUrl.contains("speedracelight.com") || lowerUrl.contains("videasy.to") ||
            (lowerUrl.contains("videasy") && !lowerUrl.contains("videasy.net")) ||
            (lowerUrl.contains("peakstorm.top") && isVideasy) -> "https://player.videasy.to/"
            lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc") -> "https://www.cineby.sc/"
            lowerUrl.contains("vidsrc.cc") -> "https://vidsrc.cc/"
            lowerUrl.contains("vidsrc.to") || lowerUrl.contains("vidsrc2.to") || lowerUrl.contains("vidsrc.net") -> "https://vidsrc.to/"
            lowerUrl.contains("vidsrc.xyz") -> "https://vidsrc.xyz/"
            lowerUrl.contains("vidsrc.me") -> "https://vidsrc.me/"
            lowerUrl.contains("vidsrc.in") -> "https://vidsrc.in/"
            lowerUrl.contains("vidsrc.pm") -> "https://vidsrc.pm/"
            lowerUrl.contains("shadowlandschronicles.com") -> "https://shadowlandschronicles.com/"
            lowerUrl.contains("cloudnestra.com") -> "https://cloudnestra.com/"
            lowerUrl.contains("thepixelpioneer.com") -> "https://thepixelpioneer.com/"
            lowerUrl.contains("putgate.org") -> "https://putgate.org/"
            lowerUrl.contains("whisperingpineslifestyle.com") -> "https://whisperingpineslifestyle.com/"
            isVidSrc -> {
                when {
                    source?.contains("vidsrccc", ignoreCase = true) == true || name?.contains("vidsrc cc", ignoreCase = true) == true -> "https://vidsrc.cc/"
                    source?.contains("vidsrcto", ignoreCase = true) == true || name?.contains("vidsrc to", ignoreCase = true) == true ||
                        source?.contains("vidsrcnet", ignoreCase = true) == true || name?.contains("vidsrc net", ignoreCase = true) == true -> "https://vidsrc.to/"
                    source?.contains("vidsrcme", ignoreCase = true) == true || name?.contains("vidsrc me", ignoreCase = true) == true -> "https://vidsrc.me/"
                    source?.contains("vidsrcin", ignoreCase = true) == true || name?.contains("vidsrc in", ignoreCase = true) == true -> "https://vidsrc.in/"
                    source?.contains("vidsrcpm", ignoreCase = true) == true || name?.contains("vidsrc pm", ignoreCase = true) == true -> "https://vidsrc.pm/"
                    !referer.isNullOrBlank() && referer.contains("vidsrc", ignoreCase = true) -> referer
                    else -> "https://vidsrc.xyz/"
                }
            }
            !referer.isNullOrBlank() -> referer
            headers.entries.any { it.key.equals(HEADER_REFERER, ignoreCase = true) } -> {
                headers.entries.firstOrNull { it.key.equals(HEADER_REFERER, ignoreCase = true) }?.value ?: ""
            }
            else -> getHostUrl(url) ?: ""
        }
    }

    /**
     * Extracts scheme://host[:port] with robust fallback for malformed/unescaped URLs.
     */
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

        // Fallback regex for unescaped characters / spaces in URLs
        return runCatching {
            val scheme = if (trimmed.startsWith("http://", ignoreCase = true)) "http" else "https"
            val hostMatch = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            val host = hostMatch?.groupValues?.get(1) ?: return null
            "$scheme://$host"
        }.getOrNull()
    }

    // ==================== Stream Type Resolution ====================

    /**
     * Resolves accurate stream type (M3U8, DASH, VIDEO, MAGNET, TORRENT)
     * inspecting protocol scheme, clean URL path, Azure/AWS media manifests,
     * query parameters, and Content-Type / MIME hints.
     */
    fun resolveCorrectLinkType(
        url: String,
        currentType: ExtractorLinkType = ExtractorLinkType.VIDEO,
        mimeType: String? = null,
        headers: Map<String, String>? = null
    ): ExtractorLinkType {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return currentType

        // 1. Protocol scheme check
        if (trimmedUrl.startsWith("magnet:", ignoreCase = true)) {
            return ExtractorLinkType.MAGNET
        }

        // 2. Content-Type / MIME hint analysis
        val effectiveMime = mimeType?.substringBefore(";")?.trim()?.lowercase(Locale.ROOT)
            ?: headers?.entries?.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.substringBefore(";")?.trim()?.lowercase(Locale.ROOT)

        if (effectiveMime != null) {
            when {
                effectiveMime.contains("mpegurl") -> return ExtractorLinkType.M3U8
                effectiveMime.contains("dash+xml") || effectiveMime.contains("dash.mpd") -> return ExtractorLinkType.DASH
                effectiveMime.startsWith("video/") || effectiveMime == "application/octet-stream" -> {
                    // Direct video mime confirmed unless path has explicit manifest extension
                    if (!trimmedUrl.contains(".m3u8", ignoreCase = true) && !trimmedUrl.contains(".mpd", ignoreCase = true)) {
                        return ExtractorLinkType.VIDEO
                    }
                }
            }
        }

        // 3. Clean path inspection (stripping query parameters and fragments)
        val cleanPath = trimmedUrl.substringBefore("?").substringBefore("#").trim().lowercase(Locale.ROOT)

        // Priority 3a: Direct video extensions ALWAYS take precedence at path tail
        // (Prevents URLs like "https://hls.server.com/film.mkv" from false-flagging as M3U8)
        if (DIRECT_VIDEO_EXT_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.VIDEO
        }

        // Priority 3b: Manifest extensions at path tail
        if (HLS_EXT_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.M3U8
        }
        if (DASH_EXT_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.DASH
        }
        if (TORRENT_EXT_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.TORRENT
        }

        // Priority 3c: Media services manifest URL patterns (Azure / AWS / Elemental)
        if (AZURE_HLS_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.M3U8
        }
        if (AZURE_DASH_REGEX.containsMatchIn(cleanPath)) {
            return ExtractorLinkType.DASH
        }

        // Priority 3d: Masked/Text playlists (/master.txt, /playlist.txt)
        if (TEXT_PLAYLIST_REGEX.containsMatchIn(cleanPath)) {
            if (cleanPath.contains("/hls/") || cleanPath.contains("/m3u8/")) return ExtractorLinkType.M3U8
            if (cleanPath.contains("/dash/") || cleanPath.contains("/mpd/")) return ExtractorLinkType.DASH
        }

        // 4. Query Parameter Inspection
        val queryString = trimmedUrl.substringAfter("?", "").lowercase(Locale.ROOT)
        if (queryString.isNotEmpty()) {
            val queryParams = queryString.split("&")
            for (param in queryParams) {
                val key = param.substringBefore("=")
                val value = param.substringAfter("=", "")
                if (key in listOf("format", "type", "manifest", "ext", "output", "stream_type", "m")) {
                    when (value) {
                        "m3u8", "hls", "m3u8-aapl" -> return ExtractorLinkType.M3U8
                        "mpd", "dash", "mpd-time-csf" -> return ExtractorLinkType.DASH
                        "mp4", "mkv", "webm" -> return ExtractorLinkType.VIDEO
                    }
                }
            }
        }

        // 5. Broad substring fallback
        val fullLowerUrl = trimmedUrl.lowercase(Locale.ROOT)
        if (fullLowerUrl.contains(".m3u8")) {
            return ExtractorLinkType.M3U8
        }
        if (fullLowerUrl.contains(".mpd")) {
            return ExtractorLinkType.DASH
        }

        // 6. Default pass-through
        return currentType
    }

    // ==================== Bitrate Estimation & Quality Classification ====================

    /**
     * Computes bitrate in kbps given content size in bytes and duration in seconds:
     * Formula: (sizeBytes * 8) / (durationSec * 1000)
     */
    fun calculateBitrateKbps(sizeBytes: Long, durationSec: Double): Long? {
        if (sizeBytes <= 0L || durationSec <= 0.0) return null
        val totalBits = sizeBytes * 8.0
        val durationMs = durationSec * 1000.0
        val kbps = totalBits / durationMs
        return if (kbps.isFinite() && kbps > 0.0) kbps.toLong() else null
    }

    fun calculateBitrateKbps(sizeBytes: Long, durationSec: Long): Long? =
        calculateBitrateKbps(sizeBytes, durationSec.toDouble())

    /**
     * Parses content size in bytes from strings like "1.45 GB", "750 MB", "2.1 GiB".
     */
    fun parseSizeBytes(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val match = SIZE_REGEX.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].uppercase(Locale.ROOT)
        return when (unit) {
            "GB", "GIB" -> (value * 1024.0 * 1024.0 * 1024.0).toLong()
            "MB", "MIB" -> (value * 1024.0 * 1024.0).toLong()
            "KB", "KIB" -> (value * 1024.0).toLong()
            "B" -> value.toLong()
            else -> null
        }
    }

    /**
     * Parses explicit bitrate in kbps from strings like "4500 kbps", "4.5 Mbps", "2500k".
     */
    fun parseBitrateKbpsFromText(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val match = BITRATE_TEXT_REGEX.find(text)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: return null
            val unit = match.groupValues[2].lowercase(Locale.ROOT)
            return when {
                unit.startsWith("m") -> (value * 1000.0).toLong()
                unit.startsWith("k") -> value.toLong()
                else -> null
            }
        }
        val kMatch = BITRATE_K_REGEX.find(text)
        if (kMatch != null) {
            return kMatch.groupValues[1].toLongOrNull()
        }
        return null
    }

    /**
     * Formats bitrate kbps into a standardized human-readable string (e.g. "4.5 Mbps", "750 kbps").
     */
    fun formatBitrate(bitrateKbps: Long): String {
        return if (bitrateKbps >= 1000L) {
            val mbps = bitrateKbps / 1000.0
            if (mbps == mbps.toLong().toDouble()) {
                "${mbps.toLong()} Mbps"
            } else {
                String.format(Locale.ROOT, "%.1f Mbps", mbps)
            }
        } else {
            "$bitrateKbps kbps"
        }
    }

    /**
     * Extracts standard CloudStream quality integer from texts (title, description, URL).
     */
    fun extractQualityFromText(vararg texts: String?): Int {
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            val detected = extractQualityFromSingleText(text)
            if (detected > 0 && detected != Qualities.Unknown.value) {
                return detected
            }
        }
        return Qualities.Unknown.value
    }

    private fun extractQualityFromSingleText(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Qualities.Unknown.value

        val isUrl = trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.contains("://")

        if (isUrl) {
            val pathPart = trimmed.substringBefore('?')
            val pathQuality = matchQualityRegexes(pathPart)
            if (pathQuality > 0 && pathQuality != Qualities.Unknown.value) {
                return pathQuality
            }

            // Path has no quality; check query string ONLY for explicit quality keys or embedded media files
            if (trimmed.contains('?')) {
                val queryPart = trimmed.substringAfter('?')
                val queryMatch = EXPLICIT_QUALITY_QUERY_REGEX.find(queryPart)
                if (queryMatch != null) {
                    val qNum = queryMatch.groupValues[1].toIntOrNull()
                    if (qNum != null && qNum > 0) {
                        return when (qNum) {
                            2160 -> Qualities.P2160.value
                            1440 -> Qualities.P1440.value
                            1080 -> Qualities.P1080.value
                            720 -> Qualities.P720.value
                            576 -> 576
                            540 -> 540
                            480 -> Qualities.P480.value
                            360 -> Qualities.P360.value
                            240 -> 240
                            else -> qNum
                        }
                    }
                }

                // Check embedded media url parameter (e.g. ?file=video_720p.m3u8)
                val embeddedMatch = EMBEDDED_MEDIA_URL_QUERY_REGEX.find(queryPart)
                if (embeddedMatch != null) {
                    val decoded = runCatching { URLDecoder.decode(embeddedMatch.groupValues[1], "UTF-8") }.getOrNull() ?: embeddedMatch.groupValues[1]
                    val embeddedQuality = matchQualityRegexes(decoded)
                    if (embeddedQuality > 0 && embeddedQuality != Qualities.Unknown.value) {
                        return embeddedQuality
                    }
                }
            }
            return Qualities.Unknown.value
        }

        return matchQualityRegexes(trimmed)
    }

    private fun matchQualityRegexes(text: String): Int {
        val qualityMatch = QUALITY_EXPLICIT_REGEX.find(text)
        if (qualityMatch != null) {
            return qualityMatch.groupValues[1].toIntOrNull() ?: Qualities.Unknown.value
        }
        if (FOUR_K_REGEX.containsMatchIn(text)) return Qualities.P2160.value
        if (QHD_REGEX.containsMatchIn(text)) return Qualities.P1440.value
        if (FHD_REGEX.containsMatchIn(text)) return Qualities.P1080.value
        if (HD_REGEX.containsMatchIn(text)) return Qualities.P720.value
        if (SD_REGEX.containsMatchIn(text)) return Qualities.P480.value
        if (P360_REGEX.containsMatchIn(text)) return Qualities.P360.value
        return Qualities.Unknown.value
    }

    /**
     * Infers quality integer from estimated bitrate kbps when explicit resolution is absent.
     */
    fun inferQualityFromBitrate(bitrateKbps: Long): Int {
        return when {
            bitrateKbps >= 15_000L -> Qualities.P2160.value
            bitrateKbps >= 3_500L -> Qualities.P1080.value
            bitrateKbps >= 1_500L -> Qualities.P720.value
            bitrateKbps >= 700L -> Qualities.P480.value
            else -> Qualities.P360.value
        }
    }

    // ==================== Audio Track Normalization ====================

    /**
     * Parses audio codec and track indicators from text metadata, returning
     * standardized badge strings (e.g. [Atmos], [5.1], [Dual Audio], [TrueHD]).
     */
    fun extractAudioBadges(vararg texts: String?): List<String> {
        val combined = texts.filterNotNull().joinToString(" ")
        if (combined.isBlank()) return emptyList()

        val badges = mutableListOf<String>()

        // 1. Dolby Atmos
        if (AUDIO_ATMOS_REGEX.containsMatchIn(combined)) {
            badges.add("[Atmos]")
        }

        // 2. Dolby TrueHD
        if (AUDIO_TRUEHD_REGEX.containsMatchIn(combined)) {
            badges.add("[TrueHD]")
        }

        // 3. DTS-HD / DTS
        if (AUDIO_DTS_HD_REGEX.containsMatchIn(combined)) {
            badges.add("[DTS-HD]")
        } else if (AUDIO_DTS_REGEX.containsMatchIn(combined)) {
            badges.add("[DTS]")
        }

        // 4. Surround Channels (7.1, 5.1)
        if (AUDIO_7_1_REGEX.containsMatchIn(combined)) {
            badges.add("[7.1]")
        } else if (AUDIO_5_1_REGEX.containsMatchIn(combined)) {
            badges.add("[5.1]")
        }

        // 5. Dual Audio / Multi Audio
        if (AUDIO_DUAL_REGEX.containsMatchIn(combined)) {
            badges.add("[Dual Audio]")
        } else if (AUDIO_MULTI_REGEX.containsMatchIn(combined)) {
            badges.add("[Multi Audio]")
        }

        // 6. Secondary Codecs (AAC, MP3) when no surround/lossless codec was badged
        if (badges.none { it in listOf("[Atmos]", "[TrueHD]", "[DTS-HD]", "[DTS]") }) {
            if (AUDIO_AAC_REGEX.containsMatchIn(combined)) {
                badges.add("[AAC]")
            } else if (AUDIO_MP3_REGEX.containsMatchIn(combined)) {
                badges.add("[MP3]")
            }
        }

        return badges.distinct()
    }

    // ==================== Video Track & Source Badging ====================

    /**
     * Parses video codec, dynamic range (HDR/DV), color depth, and release source
     * from text metadata, returning standardized badge strings (e.g. [REMUX], [DV], [HDR10+], [HDR], [HEVC], [10-bit]).
     */
    fun extractVideoBadges(vararg texts: String?): List<String> {
        val combined = texts.filterNotNull().joinToString(" ")
        if (combined.isBlank()) return emptyList()

        val badges = mutableListOf<String>()

        // 1. Source format (Remux > BluRay > WEB-DL > WEBRip)
        if (SOURCE_REMUX_REGEX.containsMatchIn(combined)) {
            badges.add("[REMUX]")
        } else if (SOURCE_BLURAY_REGEX.containsMatchIn(combined)) {
            badges.add("[BluRay]")
        } else if (SOURCE_WEBDL_REGEX.containsMatchIn(combined)) {
            badges.add("[WEB-DL]")
        } else if (SOURCE_WEBRIP_REGEX.containsMatchIn(combined)) {
            badges.add("[WEBRip]")
        }

        // 2. Dynamic Range (Dolby Vision > HDR10+ > HDR)
        if (VIDEO_DV_REGEX.containsMatchIn(combined)) {
            badges.add("[DV]")
        } else if (VIDEO_HDR10_PLUS_REGEX.containsMatchIn(combined)) {
            badges.add("[HDR10+]")
        } else if (VIDEO_HDR_REGEX.containsMatchIn(combined)) {
            badges.add("[HDR]")
        }

        // 3. Color Depth (10-bit)
        if (VIDEO_10BIT_REGEX.containsMatchIn(combined)) {
            badges.add("[10-bit]")
        }

        // 4. Video Codec (HEVC, AV1, AVC)
        if (VIDEO_HEVC_REGEX.containsMatchIn(combined)) {
            badges.add("[HEVC]")
        } else if (VIDEO_AV1_REGEX.containsMatchIn(combined)) {
            badges.add("[AV1]")
        } else if (VIDEO_AVC_REGEX.containsMatchIn(combined)) {
            badges.add("[AVC]")
        }

        return badges.distinct()
    }

    // ==================== Stream Name Formatting & Badging ====================

    /**
     * Formats stream name injecting quality, bitrate, and audio badges while
     * strictly avoiding duplicate badges and preserving upstream provider identity.
     */
    fun formatLinkName(
        currentName: String,
        quality: Int,
        url: String,
        bitrateKbps: Long? = null,
        audioBadges: List<String> = emptyList(),
        videoBadges: List<String> = emptyList()
    ): String {
        var baseName = currentName.trim()
        if (baseName.isBlank()) {
            baseName = getHostUrl(url)?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.") ?: "Stream"
        }

        val prefixBadges = mutableListOf<String>()

        // 1. Resolution Badge
        val qualityTag = when (quality) {
            Qualities.P2160.value -> "4K"
            Qualities.P1440.value -> "1440p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            else -> {
                val isMasterOrM3u8 = url.contains(".m3u8", ignoreCase = true) ||
                    url.contains(".mpd", ignoreCase = true) ||
                    url.contains("master", ignoreCase = true) ||
                    url.contains("manifest", ignoreCase = true) ||
                    url.contains("/hls/", ignoreCase = true) ||
                    baseName.contains("HLS", ignoreCase = true) ||
                    baseName.contains("m3u8", ignoreCase = true)
                if (isMasterOrM3u8) "Auto" else null
            }
        }
        if (qualityTag != null && !baseName.contains("[$qualityTag]", ignoreCase = true)) {
            prefixBadges.add("[$qualityTag]")
        }

        // 2. Video & Source Badges (REMUX, BluRay, DV, HDR, HEVC, etc.)
        for (badge in videoBadges) {
            if (!baseName.contains(badge, ignoreCase = true)) {
                prefixBadges.add(badge)
            }
        }

        // 3. Bitrate Badge
        if (bitrateKbps != null && bitrateKbps > 0) {
            val formattedBitrate = formatBitrate(bitrateKbps)
            if (!baseName.contains("[$formattedBitrate]", ignoreCase = true)) {
                prefixBadges.add("[$formattedBitrate]")
            }
        }

        // 4. Audio Badges
        for (badge in audioBadges) {
            if (!baseName.contains(badge, ignoreCase = true)) {
                prefixBadges.add(badge)
            }
        }

        return if (prefixBadges.isEmpty()) {
            baseName
        } else {
            "${prefixBadges.joinToString(" ")} $baseName"
        }
    }

    /**
     * Checks if a stream link belongs to one of the top-tier zero-setup primary sources
     * (VidLink 100 > HexaSU 90 > AutoEmbed 80 > VidFast 70 > VidEasy 60 > VidSrc 55).
     */
    fun isTopTierSource(link: ExtractorLink): Boolean = getSourcePriorityRank(link) >= 70

    /**
     * Checks if a provider ID matches one of the top-tier primary sources.
     */
    fun isTopTierProvider(providerId: String): Boolean {
        val p = providerId.lowercase(Locale.ROOT)
        return p.contains("vidlink") ||
            p.contains("vidcore") ||
            p.contains("vidup") ||
            p.contains("rivestream") ||
            p.contains("cinejoy") ||
            p.contains("peachify") ||
            p.contains("videasy")
    }

    /**
     * Creates a quality-specialized companion ExtractorLink (e.g. 720p or 1080p) from an existing link.
     * Deprecated: synthetic companion generation is eliminated to prevent fabricated quality labels.
     */
    @Deprecated("Synthetic companion generation is eliminated to prevent fabricated quality labels.", ReplaceWith("link"))
    fun createQualityCompanion(link: ExtractorLink, targetQuality: Int, targetUrl: String? = null): ExtractorLink {
        val targetTag = when (targetQuality) {
            Qualities.P720.value -> "720p"
            Qualities.P1080.value -> "1080p"
            Qualities.P480.value -> "480p"
            Qualities.P1440.value -> "1440p"
            Qualities.P2160.value -> "4K"
            else -> "${targetQuality}p"
        }
        val cleanName = link.name
            .replace(Regex("""\[(2160|1440|1080|720|480|360|4K|UHD|FHD|HD|SD)p?\]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(2160|1440|1080|720|480|360)p\b""", RegexOption.IGNORE_CASE), "")
            .trim()
        val newName = "$cleanName [$targetTag]".trim()
        val extData = link.extractorData?.let { if (it.contains("sota_dual_quality")) it else "$it;sota_dual_quality" } ?: "sota_dual_quality"
        val finalUrl = targetUrl?.takeIf { it.isNotBlank() } ?: link.url
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = link.source,
            name = newName,
            url = finalUrl,
            referer = link.referer,
            quality = targetQuality,
            type = link.type,
            headers = link.headers,
            extractorData = extData
        )
    }

    fun is720p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.is720p(link)
    fun is1080p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.is1080p(link)
    fun isBelow720p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.isBelow720p(link)
    fun isAbove1080p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.isAbove1080p(link)

    /**
     * Executes the Universal Top-Tier Stream Guard on any stream link:
     * Validates top-tier streams (VidLink 100 > HexaSU 90 > AutoEmbed 80 > VidFast 70 > VidEasy 60 > VidSrc 55)
     * and forwards them cleanly to emitAction without creating artificial or duplicated companion links.
     *
     * @param link The incoming stream link.
     * @param guardedKeys Thread-safe set of canonical stream keys that have already been emitted.
     * @param emitAction Callback to emit each processed ExtractorLink.
     * @return true if the link was handled as a top-tier stream, false otherwise.
     */
    fun processTopTierDualQualityStream(
        link: ExtractorLink,
        guardedKeys: MutableSet<String>,
        emitAction: (ExtractorLink) -> Unit
    ): Boolean {
        if (!isTopTierSource(link) || link.extractorData?.contains("sota_dual_quality") == true) {
            emitAction(link)
            return false
        }

        val optimized = optimize(link)
        val baseKey = canonicalStreamKey(optimized).substringBefore("#")
        if (baseKey.isBlank() || !guardedKeys.add(baseKey)) {
            emitAction(link)
            return false
        }

        emitAction(link)
        return true
    }

    /**
     * Authentic Stream Emission for Top-Tier Sources (VidLink, HexaSU, AutoEmbed, VidFast, VidEasy, VidSrc).
     * Emits only genuine stream links and parsed manifest variants without synthetic companion duplication:
     * - If generatedLinks is provided: emits genuine variants parsed from the manifest sorted by stream priority.
     * - If generatedLinks is null or empty: emits exactly ONE ExtractorLink with its authentic detected quality,
     *   tagging unparsed M3U8 master streams with [Auto] and quality Qualities.Unknown.value.
     */
    fun emitTopTierDualQualityStreamLinks(
        source: String,
        baseName: String,
        url: String,
        referer: String,
        headers: Map<String, String> = emptyMap(),
        streamType: ExtractorLinkType? = ExtractorLinkType.M3U8,
        generatedLinks: List<ExtractorLink>? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!generatedLinks.isNullOrEmpty()) {
            val finalLinks = generatedLinks.map { link ->
                val detectedQ = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url).takeIf { it > 0 && it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                }
                val extData = link.extractorData?.let { if (it.contains("sota_dual_quality")) it else "$it;sota_dual_quality" } ?: "sota_dual_quality"
                @Suppress("DEPRECATION")
                ExtractorLink(
                    source = link.source,
                    name = link.name,
                    url = link.url,
                    referer = link.referer,
                    quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else link.quality,
                    type = link.type,
                    headers = link.headers,
                    extractorData = extData
                )
            }

            // Emit all genuine variants sorted strictly by SOTA stream priority (720p #1 > 1080p #2 > 480p #3 > 4K #4)
            finalLinks.sortedWith(STREAM_PRIORITY_COMPARATOR).forEach(callback)
        } else if (url.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
            val cleanBaseName = baseName
                .replace(Regex("""\[(2160|1440|1080|720|480|360|4K|UHD|FHD|HD|SD)p?\]""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\b(2160|1440|1080|720|480|360)p\b""", RegexOption.IGNORE_CASE), "")
                .trim()

            val isM3u8 = (streamType == ExtractorLinkType.M3U8) || url.contains(".m3u8", ignoreCase = true)
            val detectedQ = extractQualityFromText(baseName, url)

            val (finalQuality, finalName) = if (isM3u8 && (detectedQ <= 0 || detectedQ == Qualities.Unknown.value)) {
                val autoName = if (cleanBaseName.contains("[Auto]", ignoreCase = true)) cleanBaseName else "$cleanBaseName [Auto]"
                Qualities.Unknown.value to autoName
            } else {
                val trueQ = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                val qTag = when (trueQ) {
                    Qualities.P2160.value -> "4K"
                    Qualities.P1440.value -> "1440p"
                    Qualities.P1080.value -> "1080p"
                    Qualities.P720.value -> "720p"
                    Qualities.P480.value -> "480p"
                    Qualities.P360.value -> "360p"
                    else -> null
                }
                val qName = if (qTag != null && !cleanBaseName.contains("[$qTag]", ignoreCase = true)) {
                    "$cleanBaseName [$qTag]"
                } else {
                    cleanBaseName
                }
                trueQ to qName
            }

            @Suppress("DEPRECATION")
            callback(
                ExtractorLink(
                    source = source,
                    name = finalName.trim(),
                    url = url,
                    referer = referer,
                    quality = finalQuality,
                    type = streamType ?: (if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO),
                    headers = headers,
                    extractorData = "sota_dual_quality"
                )
            )
        }
    }

    // ==================== Deduplication Canonical Key ====================

    /**
     * Normalizes rotating CDN host subdomains (e.g. cdn1.example.com, edge2.example.com -> cdn-cluster.example.com).
     */
    fun normalizeHostCluster(rawHost: String): String {
        val host = rawHost.lowercase(Locale.ROOT).trim()
        if (host.isEmpty()) return ""
        val parts = host.split(".")
        if (parts.size < 3) return host

        val secondLevelTlds = setOf("co", "com", "net", "org", "edu", "gov")
        val isTwoPartTld = parts.size >= 3 && parts[parts.size - 2] in secondLevelTlds && parts[parts.size - 1].length <= 3
        val rootCount = if (isTwoPartTld) 3 else 2
        if (parts.size <= rootCount) return host

        val subdomains = parts.dropLast(rootCount)
        val rootDomain = parts.takeLast(rootCount).joinToString(".")

        val normalizedSubdomains = subdomains.map { sub ->
            if (CDN_SUBDOMAIN_PATTERN.containsMatchIn(sub)) {
                "cdn-cluster"
            } else {
                sub
            }
        }

        return "${normalizedSubdomains.joinToString(".")}.$rootDomain"
    }

    /**
     * Generates a canonical stream deduplication key stripping transient/ephemeral
     * query parameters and normalizing rotating CDN subdomains and BitTorrent infohashes.
     */
    fun canonicalStreamKey(link: ExtractorLink): String {
        val rawUrl = link.url.trim()
        if (rawUrl.isEmpty()) return ""

        if (rawUrl.startsWith("magnet:", ignoreCase = true)) {
            val xt = Regex("""xt=urn:btih:([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(rawUrl)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            return "magnet:${xt ?: rawUrl}"
        }

        val baseKey = runCatching {
            val uri = URI(rawUrl)
            val rawHost = uri.host ?: ""
            val normalizedHost = normalizeHostCluster(rawHost)
            val path = uri.path?.trimEnd('/') ?: ""

            val filteredQuery = uri.rawQuery?.split("&")?.filterNot { param ->
                val key = param.substringBefore("=").lowercase(Locale.ROOT)
                key in TRANSIENT_QUERY_PARAMS || key.startsWith("utm_")
            }?.sorted()?.joinToString("&")

            val queryPart = if (filteredQuery.isNullOrEmpty()) "" else "?$filteredQuery"
            "$normalizedHost$path$queryPart"
        }.getOrElse {
            val hostMatch = Regex("""^https?://([^/?#:]+)""", RegexOption.IGNORE_CASE).find(rawUrl)
            val host = normalizeHostCluster(hostMatch?.groupValues?.get(1) ?: "")
            val pathAndQuery = rawUrl.substringAfter("://").substringAfter("/", "").substringBefore("#")
            val path = if (pathAndQuery.contains("?")) pathAndQuery.substringBefore("?") else pathAndQuery
            val query = if (pathAndQuery.contains("?")) pathAndQuery.substringAfter("?") else null

            val filteredQuery = query?.split("&")?.filterNot { param ->
                val key = param.substringBefore("=").lowercase(Locale.ROOT)
                key in TRANSIENT_QUERY_PARAMS || key.startsWith("utm_")
            }?.sorted()?.joinToString("&")

            val queryPart = if (filteredQuery.isNullOrEmpty()) "" else "?$filteredQuery"
            val normalizedPath = if (path.isNotEmpty() && !path.startsWith("/")) "/$path" else path
            "$host$normalizedPath$queryPart"
        }

        // Differentiate variants by resolution quality for genuine adaptive manifests (M3U8/DASH)
        // Direct video files (MP4/MKV) sharing the exact same canonical URL are strictly deduplicated
        val isDirectVideo = link.type == ExtractorLinkType.VIDEO ||
            rawUrl.contains(".mp4", ignoreCase = true) ||
            rawUrl.contains(".mkv", ignoreCase = true) ||
            rawUrl.contains(".webm", ignoreCase = true)
        val isM3u8 = link.type == ExtractorLinkType.M3U8 || rawUrl.contains(".m3u8", ignoreCase = true)
        val isAdaptive = !isDirectVideo && (
            isM3u8 ||
            link.type == ExtractorLinkType.DASH ||
            rawUrl.contains(".mpd", ignoreCase = true) ||
            rawUrl.contains("/hls/", ignoreCase = true) ||
            rawUrl.contains("manifest", ignoreCase = true)
        )

        return if (isAdaptive) {
            val q = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
                link.quality
            } else {
                extractQualityFromText(link.name, rawUrl)
            }
            if (q > 0 && q != Qualities.Unknown.value) "$baseKey#$q" else baseKey
        } else {
            baseKey
        }
    }

    // ==================== Stream Comparison & Deduplicator ====================

    /**
     * Determines whether candidate ExtractorLink is strictly superior to existing ExtractorLink:
     * 1. Quality priority score (720p > 1080p > 480p > intermediate SD > 360p/240p > 1440p/4K > Unknown)
     * 2. Top-tier source priority rank (VidLink > YFlix/HexaSU > CineJoy/AutoEmbed > VidFast > VidEasy > VidSrc > Secondary)
     * 3. Genuine non-synthetic streams over synthetic companions
     * 4. Higher bitrate (kbps) (when quality score and source rank are equivalent)
     * 5. Direct endpoint rewrites (?download / &stream=1)
     * 6. Video source and HDR/codec score (REMUX > BluRay > WEB-DL; DV > HDR10+ > HDR)
     * 7. Direct byte stream over HLS for equal tier sources
     * 8. Audio format and channel score (Atmos > TrueHD > DTS-HD > DTS > 7.1 > 5.1)
     * 9. Anti-throttling header completeness
     * 10. Intra-bucket resolution tiebreaker (e.g. 720p vs 718p widescreen when all other metrics are equal)
     */
    fun isBetterThan(candidate: ExtractorLink, current: ExtractorLink): Boolean {
        // 1. Resolution Quality comparison: user-priority score strictly takes precedence (720p > 1080p > 480p > SD > UHD > Unknown)
        val rawQ1 = if (candidate.quality > 0 && candidate.quality != Qualities.Unknown.value) candidate.quality else extractQualityFromText(candidate.name, candidate.url)
        val rawQ2 = if (current.quality > 0 && current.quality != Qualities.Unknown.value) current.quality else extractQualityFromText(current.name, current.url)
        val q1 = if (rawQ1 == Qualities.Unknown.value) 0 else rawQ1
        val q2 = if (rawQ2 == Qualities.Unknown.value) 0 else rawQ2
        val score1 = getQualityPriorityScore(q1)
        val score2 = getQualityPriorityScore(q2)
        if (score1 != score2) {
            return score1 > score2
        }

        // 2. Top-Tier Source Priority Rank (VidLink > YFlix/HexaSU > CineJoy/AutoEmbed > VidFast > VidEasy > VidSrc > Secondary)
        // Strictly prevents lower-tier sources (e.g. VidFast, VidEasy, or scrapers) from overriding top-tier sources at equivalent resolution
        val sourceRank1 = getSourcePriorityRank(candidate)
        val sourceRank2 = getSourcePriorityRank(current)
        if (sourceRank1 != sourceRank2) {
            return sourceRank1 > sourceRank2
        }

        // 3. Genuine non-synthetic streams strictly prioritize over synthetic companions
        val candidateNonSynthetic = candidate.extractorData?.contains("sota_dual_quality") != true
        val currentNonSynthetic = current.extractorData?.contains("sota_dual_quality") != true
        if (candidateNonSynthetic != currentNonSynthetic) {
            return candidateNonSynthetic
        }

        // 4. Bitrate comparison (when resolutions and top-tier source ranks are equivalent)
        val b1 = parseBitrateKbpsFromText(candidate.name)
        val b2 = parseBitrateKbpsFromText(current.name)
        if (b1 != null && b2 != null && b1 != b2) {
            return b1 > b2
        }
        if (b1 != null && b2 == null) {
            return true
        }
        if (b1 == null && b2 != null) {
            return false
        }

        // 5. Direct endpoint score
        val endpointScore1 = if (candidate.url.contains("?download") || candidate.url.contains("&stream=1")) 10 else 0
        val endpointScore2 = if (current.url.contains("?download") || current.url.contains("&stream=1")) 10 else 0
        if (endpointScore1 != endpointScore2) {
            return endpointScore1 > endpointScore2
        }

        // 6. Video source & HDR score comparison
        val videoScore1 = calculateVideoScore(candidate)
        val videoScore2 = calculateVideoScore(current)
        if (videoScore1 != videoScore2) {
            return videoScore1 > videoScore2
        }

        // 7. Direct byte stream over HLS for equal tier sources (Range request chunking for downloads)
        val isDirect1 = candidate.type == ExtractorLinkType.VIDEO || candidate.url.endsWith(".mp4", ignoreCase = true)
        val isDirect2 = current.type == ExtractorLinkType.VIDEO || current.url.endsWith(".mp4", ignoreCase = true)
        if (isDirect1 != isDirect2) {
            return isDirect1
        }

        // 8. Audio score comparison
        val audioScore1 = calculateAudioScore(candidate)
        val audioScore2 = calculateAudioScore(current)
        if (audioScore1 != audioScore2) {
            return audioScore1 > audioScore2
        }

        // 9. Header score comparison
        val hScore1 = calculateHeaderScore(candidate)
        val hScore2 = calculateHeaderScore(current)
        if (hScore1 != hScore2) {
            return hScore1 > hScore2
        }

        // 10. Intra-bucket resolution tiebreaker (e.g. 720p vs 718p widescreen when all other metrics are equal)
        if (q1 != q2) {
            return q1 > q2
        }

        return false
    }

    /**
     * Definitive top-tier zero-setup source priority ranking:
     * 1. VidLink (api.vidlink.pro fast HLS/m3u8 & direct Cronet CDN streams) -> 100
     * 2. HexaSU / embed.su (multi-cluster HLS resolver) -> 90
     * 3. AutoEmbed (player.autoembed.cc high reliability HLS/MP4 streams) -> 80
     * 4. VidFast (vidfast.vc stream resolver) -> 70
     * 5. VidEasy (api.videasy.net multi-server resolver) -> 60
     * Secondary scrapers:
     * 6. MovieBox (strict fallback) -> 50
     * 7. RiveStream (strict fallback) -> 40
     * 8. Vidrock (strict fallback) -> 30
     * 9. MoviesAPI (strict fallback) -> 20
     */
    fun getSourcePriorityRank(link: ExtractorLink): Int {
        val s = link.source.lowercase(Locale.ROOT)
        val n = link.name.lowercase(Locale.ROOT)
        val u = link.url.lowercase(Locale.ROOT)
        return when {
            s.contains("vidlink") || n.contains("vidlink") || u.contains("vidlink.pro") || u.contains("hakunaymatata") -> 100
            (s.contains("vidcore") || n.contains("vidcore") || u.contains("vidcore.io") ||
                (u.contains("quietridge.top") && !s.contains("vidup") && !n.contains("vidup"))) -> 95
            s.contains("vidup") || n.contains("vidup") || u.contains("vidup.to") || u.contains("keenanchor.top") -> 92
            s.contains("rivestream") || n.contains("rivestream") || u.contains("rivestream") -> 90
            s.contains("cinejoy") || n.contains("cinejoy") || u.contains("cinejoy") || u.contains("solarpanelcleaning") || u.contains("api.wing.st") -> 88
            s.contains("peachify") || n.contains("peachify") || u.contains("peachify") -> 80
            s.contains("videasy") || n.contains("videasy") || u.contains("videasy") || u.contains("speedracelight.com") || u.contains("videasy.to") || u.contains("videasy.net") || u.contains("cineby.sc") ||
                (u.contains("peakstorm.top") && (s.contains("videasy") || n.contains("videasy"))) -> 70
            s.contains("yflix") || s.contains("moviesflix") || n.contains("yflix") || n.contains("moviesflix") || u.contains("yflix") || u.contains("moviesflix") ||
                s.contains("hexasu") || s.contains("hexa.su") || s.contains("embedsu") || s.contains("embed.su") || s.contains("hexa") || s.contains("flixer") ||
                n.contains("hexasu") || n.contains("embedsu") || n.contains("embed.su") || n.contains("hexa") || n.contains("flixer") ||
                u.contains("hexa.su") || u.contains("embed.su") || u.contains("flixer.su") || u.contains("flixer") -> 45
            s.contains("autoembed") || n.contains("autoembed") || u.contains("autoembed.cc") || u.contains("player.autoembed.cc") || u.contains("autoembed.to") || u.contains("autoembed.co") -> 40
            s.contains("moviebox") || n.contains("moviebox") || u.contains("moviebox") -> 35
            s.contains("4khdhub") || n.contains("4khdhub") || u.contains("4khdhub") -> 35
            s.contains("multimovies") || n.contains("multimovies") || u.contains("multimovies") -> 35
            s.contains("uhdmovies") || n.contains("uhdmovies") || u.contains("uhdmovies") -> 35
            s.contains("vidfast") || n.contains("vidfast") || u.contains("vidfast.pro") || u.contains("vidfast.vc") ||
                ((u.contains("peakstorm.top") || u.contains("hypergate.top")) && !s.contains("videasy") && !n.contains("videasy")) -> 30
            s.contains("vidsrc") || n.contains("vidsrc") || u.contains("vidsrc") || u.contains("cloudnestra") || u.contains("shadowlandschronicles") ||
                u.contains("thepixelpioneer") || u.contains("putgate") || u.contains("whisperingpines") || u.contains("vidsrc.in") || u.contains("vidsrc.pm") || u.contains("vidsrc.net") -> 25
            s.contains("vidrock") || n.contains("vidrock") || u.contains("vidrock") -> 20
            s.contains("moviesapi") || n.contains("moviesapi") || u.contains("moviesapi") -> 15
            s.contains("vidzee") || n.contains("vidzee") || u.contains("vidzee") -> 12
            s.contains("2embed") || n.contains("2embed") || u.contains("2embed") -> 10
            else -> 0
        }
    }

    /**
     * Definitive quality priority ranking (§SOTA Quality Hierarchy):
     * 1. 720p (Tier 1: HD - HIGHEST PRIORITY #1) -> Score 10000
     * 2. 1080p (Tier 1: FHD - Priority #2) -> Score 9000
     *    Users must receive 720 as #1 priority, then 1080.
     * 3. Below 720p (Priority #3):
     *    - 480p (Tier 2: SD) -> Score 7000
     *    - Intermediate SD (e.g. 576p, 540p) -> Score 6500
     *    - Lower SD (e.g. 360p, 240p) -> Score 5000 + quality (e.g. 360p = 5360, 240p = 5240)
     * 4. Above 1080p (Tier 4: 1440p, 2160p/4K, 4320p/8K - Priority #4) -> Score 4000 - (quality - 1080) [clamped to min 1000]
     *    (1440p = 3640, 2160p/4K = 2920)
     * 5. Unknown -> Score 0
     *
     * Strict User Monotonicity: 720p > 1080p > below 720p (480p > 576p > 360p > 240p) > above 1080p (1440p > 4K) > Unknown
     */
    fun getQualityPriorityScore(quality: Int): Int {
        return when {
            quality <= 0 || quality == Qualities.Unknown.value -> 0
            quality == Qualities.P720.value || (quality in 700..749) -> 10000
            quality == Qualities.P1080.value -> 9000
            quality in 750..1088 -> 8500 + minOf(499, quality - 750)
            quality == Qualities.P480.value -> 7000
            quality in (Qualities.P480.value + 1) until 700 -> 6500
            quality in 1 until Qualities.P480.value -> 5000 + quality
            quality > 1088 -> maxOf(1000, 4000 - (quality - 1080))
            else -> 0
        }
    }

    /**
     * Checks if resolution is within the top-prioritized user tier (720p or 1080p).
     */
    fun isQualityPrioritized(quality: Int): Boolean {
        return quality == Qualities.P720.value || quality == Qualities.P1080.value || quality in 700..1088
    }

    /**
     * Computes the overall stream score combining:
     * 1. Top-tier source priority rank and resolution quality hierarchy:
     *    VidLink 100 with 720 > HexaSU 90 with 720 > AutoEmbed 80 with 720 > VidFast 70 with 720 > VidEasy 60 with 720 > VidSrc 55 with 720
     *    then > VidLink with 1080 > HexaSU with 1080 > AutoEmbed with 1080 > VidFast with 1080 > VidEasy with 1080 > VidSrc with 1080
     *    then the rest of the qualities (480p > other SD > 1440p > 4K > Unknown) for top sources
     *    followed by secondary sources.
     * 2. Micro-tiebreakers (bitrate, codecs, audio badges, headers) strictly bounded to < 10.0f
     *    so they break ties between equivalent streams without ever inverting source or quality tiers.
     */
    fun getStreamCompositeScore(link: ExtractorLink): Float {
        val sourceRank = getSourcePriorityRank(link)
        val quality = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
            link.quality
        } else {
            extractQualityFromText(link.name, link.url)
        }
        val isTopTier = isTopTierSource(link) // sourceRank >= 55
        val is720 = is720p(link) || quality == Qualities.P720.value || quality in 700..749
        val is1080 = !is720 && (is1080p(link) || quality == Qualities.P1080.value || (quality in 750..1088 && quality !in 700..749))
        val isBelow720 = !is720 && !is1080 && isBelow720p(link)
        val isAbove1080 = !is720 && !is1080 && isAbove1080p(link)

        val bitrate = parseBitrateKbpsFromText(link.name) ?: 0L
        val videoScore = calculateVideoScore(link)
        val audioScore = calculateAudioScore(link)
        val headerScore = calculateHeaderScore(link)
        // Normalized tiebreaker (0.0f .. 9.9f) strictly prevents inverting source or quality tiers:
        val tiebreaker = minOf(9.9f, (minOf(50_000L, bitrate) / 10_000f) + (videoScore * 0.05f) + (audioScore * 0.05f) + (headerScore * 0.02f))

        val baseScore = if (isTopTier) {
            when {
                // Tier 1: Highest priority sources with 720p (#1 Priority)
                // VidLink (100) -> 11,000 | HexaSU (90) -> 10,900 | AutoEmbed (80) -> 10,800 | VidFast (70) -> 10,700 | VidEasy (60) -> 10,600 | VidSrc (55) -> 10,550
                is720 -> 10_000f + (sourceRank * 10f)

                // Tier 2: Highest priority sources with 1080p (#2 Priority)
                // VidLink (100) -> 9,000 | HexaSU (90) -> 8,900 | AutoEmbed (80) -> 8,800 | VidFast (70) -> 8,700 | VidEasy (60) -> 8,600 | VidSrc (55) -> 8,550
                is1080 -> 8_000f + (sourceRank * 10f)

                // Tier 3: Rest of the qualities for highest priority sources (720 -> 1080 -> then the rest)
                // 3A: 480p (Tier 2 SD) -> 7,000 down to 6,550
                quality == Qualities.P480.value -> 6_000f + (sourceRank * 10f)

                // 3B: Other SD (576p > 540p > 360p > 240p) -> ~5,200 down to ~4,600
                isBelow720 -> {
                    val sdOffset = if (quality in (Qualities.P480.value + 1) until 700) 200f else (minOf(479, maxOf(1, quality)) / 4.79f)
                    4_000f + (sourceRank * 10f) + sdOffset
                }

                // 3C: Above 1080p (1440p > 4K / 2160p > 8K) -> ~3,200 down to ~2,600
                isAbove1080 -> {
                    val uhdOffset = when {
                        quality == Qualities.P1440.value || (quality in 1089..1800) -> 200f
                        quality == Qualities.P2160.value || (quality in 1801..3000) -> 100f
                        else -> 50f
                    }
                    2_000f + (sourceRank * 10f) + uhdOffset
                }

                // 3D: Unknown or unspecified quality
                else -> 1_200f + (sourceRank * 5f)
            }
        } else {
            // Secondary / Fallback sources (< 55)
            when {
                is720 -> 800f + (sourceRank * 2f)
                is1080 -> 600f + (sourceRank * 2f)
                quality == Qualities.P480.value -> 400f + (sourceRank * 2f)
                isBelow720 -> 200f + (sourceRank * 2f) + (minOf(479, maxOf(1, quality)) / 4.79f * 0.5f)
                isAbove1080 -> 100f + (sourceRank * 2f)
                else -> sourceRank * 2f
            }
        }

        return baseScore + tiebreaker
    }

    val STREAM_PRIORITY_COMPARATOR = Comparator<ExtractorLink> { a, b ->
        getStreamCompositeScore(b).compareTo(getStreamCompositeScore(a))
    }

    /**
     * Determines whether candidate stream is strictly higher priority for user playback than current stream,
     * enforcing the user priority hierarchy (720p > 1080p > 480p > above 1080p > below 480p).
     */
    fun isStreamBetter(candidate: ExtractorLink, current: ExtractorLink): Boolean {
        return getStreamCompositeScore(candidate) > getStreamCompositeScore(current)
    }

    private fun calculateVideoScore(link: ExtractorLink): Int {
        var score = 0
        val name = link.name
        if (name.contains("[REMUX]", ignoreCase = true)) score += 40
        else if (name.contains("[BluRay]", ignoreCase = true)) score += 30
        else if (name.contains("[WEB-DL]", ignoreCase = true)) score += 20
        else if (name.contains("[WEBRip]", ignoreCase = true)) score += 10

        if (name.contains("[DV]", ignoreCase = true)) score += 25
        else if (name.contains("[HDR10+]", ignoreCase = true)) score += 20
        else if (name.contains("[HDR]", ignoreCase = true)) score += 15

        if (name.contains("[HEVC]", ignoreCase = true)) score += 10
        if (name.contains("[AV1]", ignoreCase = true)) score += 10
        if (name.contains("[10-bit]", ignoreCase = true)) score += 5
        return score
    }

    private fun calculateAudioScore(link: ExtractorLink): Int {
        var score = 0
        val name = link.name
        if (name.contains("[TrueHD]", ignoreCase = true)) score += 30
        else if (name.contains("[DTS-HD]", ignoreCase = true)) score += 25
        else if (name.contains("[Atmos]", ignoreCase = true)) score += 20
        else if (name.contains("[DTS]", ignoreCase = true)) score += 15

        if (name.contains("[7.1]", ignoreCase = true)) score += 15
        else if (name.contains("[5.1]", ignoreCase = true)) score += 10

        if (name.contains("[Dual Audio]", ignoreCase = true)) score += 10
        if (name.contains("[Multi Audio]", ignoreCase = true)) score += 10
        return score
    }

    private fun calculateHeaderScore(link: ExtractorLink): Int {
        var score = 0
        val headers = link.headers
        if (headers.containsKey(HEADER_USER_AGENT)) score += 5
        if (headers.containsKey(HEADER_ACCEPT)) score += 5
        if (headers[HEADER_ACCEPT_ENCODING] == "identity") score += 10
        if (headers.containsKey(HEADER_CONNECTION)) score += 5
        if (headers.containsKey(HEADER_SEC_FETCH_DEST)) score += 5
        return score
    }

    /**
     * Validates that a stream URL is syntactically valid and not an empty or error artifact.
     */
    fun isValidStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true) &&
            !trimmed.startsWith("magnet:", ignoreCase = true)
        ) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("undefined") ||
            lower.contains("/null") ||
            lower.endsWith(".html") ||
            lower == "about:blank"
        ) return false
        return true
    }

    enum class DeduplicationResult {
        NEW,
        UPGRADED,
        DROPPED
    }

    /**
     * Lock-free atomic stream deduplicator.
     * Colliding streams are compared; superior streams atomically upgrade existing entries
     * and are emitted to CloudStream, while inferior or identical streams are dropped.
     */
    class StreamDeduplicator(
        private val upstreamCallback: (ExtractorLink) -> Unit,
        private val onUpgradeCallback: ((ExtractorLink) -> Unit)? = null
    ) {
        constructor(upstreamCallback: (ExtractorLink) -> Unit) : this(upstreamCallback, null)

        private val emittedStreams = ConcurrentHashMap<String, ExtractorLink>()

        fun emit(link: ExtractorLink): Boolean {
            return emitDetailed(link) != DeduplicationResult.DROPPED
        }

        fun emitDetailed(link: ExtractorLink): DeduplicationResult {
            if (!isValidStreamUrl(link.url)) return DeduplicationResult.DROPPED
            val key = canonicalStreamKey(link)
            if (key.isBlank()) return DeduplicationResult.DROPPED
            while (true) {
                val existing = emittedStreams[key]
                if (existing == null) {
                    if (emittedStreams.putIfAbsent(key, link) == null) {
                        upstreamCallback(link)
                        return DeduplicationResult.NEW
                    }
                } else {
                    if (isBetterThan(link, existing)) {
                        if (emittedStreams.replace(key, existing, link)) {
                            if (onUpgradeCallback != null) {
                                onUpgradeCallback.invoke(link)
                            } else {
                                upstreamCallback(link)
                            }
                            return DeduplicationResult.UPGRADED
                        }
                    } else {
                        return DeduplicationResult.DROPPED
                    }
                }
            }
        }

        fun getEmittedCount(): Int = emittedStreams.size
        fun getEmittedLinks(): Collection<ExtractorLink> = emittedStreams.values
        fun clear() = emittedStreams.clear()
    }

    /**
     * State-Of-The-Art Priority-Ordered Stream Dispatcher for CloudStream.
     * Guarantees that users receive 720p as #1 priority along with subtitles,
     * followed by 1080p, 480p, above 1080p (4K), etc., while strictly preserving
     * source priority order (VidLink 100 > HexaSU 90 > AutoEmbed 80 > VidFast 70 > VidEasy 60 > VidSrc 55).
     */
    class PriorityStreamDispatcher(
        private val upstreamCallback: (ExtractorLink) -> Unit,
        private val scope: CoroutineScope,
        private val stageWindowMs: Long = 400L,
        private val subtitleGraceMs: Long = 350L,
        private val topSourceGraceMs: Long = 1200L,
        private val top720GraceMs: Long = 0L,
        private val fhdGraceMs: Long = 250L,
        private val sdGraceMs: Long = 200L,
        private val activeTopRanks: Set<Int>? = null,
        private val isRankInFlight: ((Int) -> Boolean)? = null
    ) {
        private val lock = Any()
        private val stagedLinks = mutableListOf<ExtractorLink>()
        private val pendingTop720Links = mutableListOf<ExtractorLink>()
        private val pending1080Links = mutableListOf<ExtractorLink>()
        private val pendingBelowFhdLinks = mutableListOf<ExtractorLink>()
        private val pendingAbove1080Links = mutableListOf<ExtractorLink>()
        private val pendingSecondaryLinks = mutableListOf<ExtractorLink>()
        private val emittedKeys = ConcurrentHashMap.newKeySet<String>()
        private val completedTop720Ranks = mutableSetOf<Int>()
        @Volatile
        private var hasSubtitles = false
        @Volatile
        private var hasEmittedTopStream = false
        @Volatile
        private var hasEmitted1080p = false
        @Volatile
        private var hasEmittedBelow720p = false
        @Volatile
        private var topSourceGraceExpired = false
        @Volatile
        private var top720GraceExpired = false
        @Volatile
        private var fhdGraceExpired = false
        @Volatile
        private var sdGraceExpired = false
        private var stageTimerJob: Job? = null
        private var topSourceTimerJob: Job? = null
        private var top720TimerJob: Job? = null
        private var fhdTimerJob: Job? = null
        private var sdTimerJob: Job? = null

        fun onSubtitleReceived() {
            synchronized(lock) {
                hasSubtitles = true
                if (!hasEmittedTopStream) {
                    val best720p = stagedLinks.filter { is720p(it) }.sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull()
                    if (best720p != null) {
                        dispatchOrStageBest(best720p)
                    }
                }
            }
        }

        private fun dispatchOrStageBest(best720p: ExtractorLink) {
            val rank = getSourcePriorityRank(best720p)
            val maxRank = activeTopRanks?.maxOrNull() ?: 100
            if (rank >= maxRank || !hasHigherPendingTop720Rank(rank) || topSourceGraceMs <= 0L || topSourceGraceExpired) {
                // Pinnacle active source rank + 720p + subtitles -> dispatch IMMEDIATELY as #1!
                stageTimerJob?.cancel()
                stageTimerJob = null
                stagedLinks.remove(best720p)
                emitTopStreamAndAdvance(best720p)
            } else {
                // Subtitles ready with 720p, but from lower tier (HexaSU 90, AutoEmbed 80, VidFast 70, VidEasy 60, VidSrc 55):
                // stage in pendingTop720Links and wait for higher priority sources
                stagedLinks.remove(best720p)
                pendingTop720Links.add(best720p)
                startTopSourceGraceTimer()
            }
        }

        fun onLinkAccepted(link: ExtractorLink) {
            synchronized(lock) {
                val key = canonicalStreamKey(link)
                if (emittedKeys.contains(key)) return

                val linkIs720 = is720p(link)
                val linkIs1080 = is1080p(link)
                val linkIsBelow720 = isBelow720p(link)
                val linkIsTopTier = isTopTierSource(link)

                if (hasEmittedTopStream) {
                    // Secondary sources (< 55 rank) buffered while top tier streams are active/pending
                    if (!linkIsTopTier) {
                        if (!hasEmitted1080p || (!top720GraceExpired && top720GraceMs > 0L) || !sdGraceExpired || pendingSecondaryLinks.isNotEmpty() || pendingTop720Links.isNotEmpty() || pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty()) {
                            pendingSecondaryLinks.add(link)
                            return
                        }
                        emitSingleLink(link)
                        return
                    }

                    // Tier 1: 720p from top-tier sources
                    if (linkIs720) {
                        val linkRank = getSourcePriorityRank(link)
                        if (hasHigherPendingTop720Rank(linkRank) && top720GraceMs > 0L && !top720GraceExpired) {
                            pendingTop720Links.add(link)
                            startTop720GraceTimer()
                            return
                        } else {
                            emitSingleLink(link)
                            completedTop720Ranks.add(linkRank)
                            drainPendingTop720()
                            return
                        }
                    }

                    // Tier 2: 1080p from top-tier sources
                    if (linkIs1080) {
                        val shouldHold1080 = pendingTop720Links.isNotEmpty() ||
                            pending1080Links.isNotEmpty() ||
                            (isRankInFlight != null && hasHigherPendingTop720Rank(0) && top720GraceMs > 0L && !top720GraceExpired) ||
                            (isRankInFlight == null && top720GraceMs > 0L && !top720GraceExpired)
                        if (shouldHold1080) {
                            pending1080Links.add(link)
                            startTop720GraceTimer()
                            return
                        }
                        emitSingleLink(link)
                        hasEmitted1080p = true
                        fhdTimerJob?.cancel()
                        fhdTimerJob = null
                        flushPendingBelowFhd()
                        return
                    }

                    // 1080p has not arrived/emitted yet: stage waiting for 1080p
                    if (!hasEmitted1080p && !fhdGraceExpired) {
                        if (linkIsBelow720) {
                            pendingBelowFhdLinks.add(link)
                        } else {
                            pendingAbove1080Links.add(link)
                        }
                        startFhdGraceTimer()
                        return
                    }

                    // Tier 3: below 720p (480p, SD) links emit immediately as priority #3
                    if (linkIsBelow720) {
                        hasEmittedBelow720p = true
                        emitSingleLink(link)
                        sdTimerJob?.cancel()
                        sdTimerJob = null
                        flushPendingAbove1080()
                        return
                    }

                    // Above 1080p (4K, 1440p): stage waiting for 480p if 480p hasn't emitted yet
                    if (!hasEmittedBelow720p && !sdGraceExpired) {
                        pendingAbove1080Links.add(link)
                        startSdGraceTimer()
                        return
                    }

                    // 480p already emitted or SD grace expired: emit in priority order
                    emitSingleLink(link)
                    return
                }

                val shouldHoldInitial1080 = !hasEmittedTopStream && linkIs1080 && (
                    pending1080Links.isNotEmpty() ||
                    (isRankInFlight != null && hasHigherPendingTop720Rank(0) && top720GraceMs > 0L && !top720GraceExpired) ||
                    (isRankInFlight == null && top720TimerJob?.isActive == true)
                )
                if (shouldHoldInitial1080) {
                    pending1080Links.add(link)
                    startTop720GraceTimer()
                    return
                }

                stagedLinks.add(link)

                // If 720p link arrived and subtitles are ready
                if (linkIs720 && linkIsTopTier && hasSubtitles) {
                    val best720p = (stagedLinks.filter { is720p(it) && isTopTierSource(it) } + listOf(link))
                        .sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull() ?: link
                    val rank = getSourcePriorityRank(best720p)
                    val maxRank = activeTopRanks?.maxOrNull() ?: 100
                    if (rank >= maxRank || !hasHigherPendingTop720Rank(rank) || topSourceGraceMs <= 0L || topSourceGraceExpired) {
                        // Pinnacle active source rank + 720p + subtitles -> dispatch IMMEDIATELY as #1!
                        stageTimerJob?.cancel()
                        stageTimerJob = null
                        stagedLinks.remove(best720p)
                        emitTopStreamAndAdvance(best720p)
                        return
                    } else {
                        // Subtitles ready with 720p, but from lower tier: stage in pendingTop720Links
                        stagedLinks.remove(best720p)
                        pendingTop720Links.add(best720p)
                        startTopSourceGraceTimer()
                        return
                    }
                }

                // If 720p arrived without subtitles yet, start short subtitle grace timer
                if (linkIs720 && !hasSubtitles) {
                    if (stageTimerJob == null) {
                        stageTimerJob = scope.launch {
                            delay(subtitleGraceMs)
                            synchronized(lock) {
                                if (!hasEmittedTopStream) {
                                    val best720p = stagedLinks.filter { is720p(it) }.sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull()
                                        ?: stagedLinks.sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull()
                                    if (best720p != null) {
                                        emitTopStreamAndAdvance(best720p)
                                    }
                                }
                            }
                        }
                    }
                    return
                }

                // If non-720p link (1080p, 480p, etc.) arrives and no 720p has arrived yet, stage and wait briefly for 720p
                if (!linkIs720 && stageTimerJob == null) {
                    stageTimerJob = scope.launch {
                        delay(stageWindowMs)
                        synchronized(lock) {
                            stageTimerJob = null
                            if (!hasEmittedTopStream) {
                                val best720 = stagedLinks.filter { is720p(it) }.sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull()
                                if (best720 != null) {
                                    emitTopStreamAndAdvance(best720)
                                } else {
                                    val bestStream = stagedLinks.sortedWith(STREAM_PRIORITY_COMPARATOR).firstOrNull()
                                    if (bestStream != null) {
                                        val shouldHold1080 = is1080p(bestStream) && (
                                            pending1080Links.isNotEmpty() ||
                                            (isRankInFlight != null && hasHigherPendingTop720Rank(0) && top720GraceMs > 0L && !top720GraceExpired) ||
                                            (isRankInFlight == null && top720TimerJob?.isActive == true)
                                        )
                                        if (shouldHold1080) {
                                            stagedLinks.remove(bestStream)
                                            pending1080Links.add(bestStream)
                                            val other1080 = stagedLinks.filter { is1080p(it) }
                                            stagedLinks.removeAll(other1080)
                                            pending1080Links.addAll(other1080)
                                            startTop720GraceTimer()
                                        } else {
                                            emitTopStreamAndAdvance(bestStream)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun hasHigherPendingTop720Rank(rank: Int): Boolean {
            val hasHigherQueued = (pendingTop720Links + stagedLinks).any {
                is720p(it) && isTopTierSource(it) && getSourcePriorityRank(it) > rank && canonicalStreamKey(it) !in emittedKeys
            }
            if (hasHigherQueued) return true

            if (isRankInFlight != null) {
                val ranksToCheck = if (activeTopRanks != null) {
                    TOP_TIER_RANKS.filter { it in activeTopRanks }
                } else {
                    val seenRanks = (completedTop720Ranks + (pendingTop720Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                    TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                }
                return ranksToCheck.any { it > rank && it !in completedTop720Ranks && isRankInFlight.invoke(it) }
            }

            if (activeTopRanks != null) {
                return activeTopRanks.any { it > rank && it !in completedTop720Ranks }
            }

            // Standalone without activeTopRanks and without isRankInFlight:
            val isGraceActive = (!hasEmittedTopStream && topSourceGraceMs > 0L && !topSourceGraceExpired) ||
                (hasEmittedTopStream && top720GraceMs > 0L && !top720GraceExpired)

            if (isGraceActive) {
                val seenRanks = (completedTop720Ranks + (pendingTop720Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                val ranksToWait = TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                return ranksToWait.any { it > rank && it !in completedTop720Ranks }
            }

            return false
        }

        fun markRankCompleted(rank: Int) {
            synchronized(lock) {
                completedTop720Ranks.add(rank)
                drainPendingTop720()
            }
        }

        fun markProviderCompleted(providerId: String) {
            val rank = when {
                providerId.contains("vidlink", ignoreCase = true) -> 100
                providerId.contains("vidcore", ignoreCase = true) -> 95
                providerId.contains("vidup", ignoreCase = true) -> 92
                providerId.contains("rivestream", ignoreCase = true) -> 90
                providerId.contains("cinejoy", ignoreCase = true) -> 88
                providerId.contains("peachify", ignoreCase = true) -> 80
                providerId.contains("videasy", ignoreCase = true) -> 70
                providerId.contains("yflix", ignoreCase = true) || providerId.contains("moviesflix", ignoreCase = true) -> 45
                providerId.contains("hexa", ignoreCase = true) -> 45
                providerId.contains("autoembed", ignoreCase = true) -> 40
                providerId.contains("moviebox", ignoreCase = true) -> 35
                providerId.contains("vidfast", ignoreCase = true) -> 30
                providerId.contains("vidsrc", ignoreCase = true) -> 25
                else -> 0
            }
            if (rank > 0) {
                markRankCompleted(rank)
            }
        }

        private fun drainPendingTop720() {
            pendingTop720Links.removeAll { canonicalStreamKey(it) in emittedKeys }
            var emittedAny = true
            while (emittedAny && pendingTop720Links.isNotEmpty()) {
                emittedAny = false
                val nextCandidate = pendingTop720Links
                    .filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                    .firstOrNull() ?: break

                val candRank = getSourcePriorityRank(nextCandidate)
                if (!hasHigherPendingTop720Rank(candRank) || topSourceGraceExpired || top720GraceExpired) {
                    pendingTop720Links.remove(nextCandidate)
                    if (!hasEmittedTopStream) {
                        emitTopStreamAndAdvance(nextCandidate)
                    } else {
                        emitSingleLink(nextCandidate)
                        completedTop720Ranks.add(candRank)
                    }
                    emittedAny = true
                }
            }
            val canFlush1080Early = if (isRankInFlight != null) {
                !hasHigherPendingTop720Rank(0)
            } else {
                top720GraceMs <= 0L || top720GraceExpired
            }
            if (pendingTop720Links.isEmpty() && canFlush1080Early) {
                flushPending1080()
            }
        }

        private fun startTopSourceGraceTimer() {
            if (topSourceTimerJob == null && topSourceGraceMs > 0L && !topSourceGraceExpired) {
                topSourceTimerJob = scope.launch {
                    delay(topSourceGraceMs)
                    synchronized(lock) {
                        topSourceTimerJob = null
                        topSourceGraceExpired = true
                        drainPendingTop720()
                    }
                }
            }
        }

        private fun startTop720GraceTimer() {
            if (top720TimerJob == null && top720GraceMs > 0L && !top720GraceExpired) {
                top720TimerJob = scope.launch {
                    delay(top720GraceMs)
                    synchronized(lock) {
                        top720TimerJob = null
                        flushPending1080()
                    }
                }
            }
        }

        private fun startFhdGraceTimer() {
            if (fhdGraceMs <= 0L) {
                flushPendingBelowFhd()
                return
            }
            if (fhdTimerJob == null) {
                fhdTimerJob = scope.launch {
                    delay(fhdGraceMs)
                    synchronized(lock) {
                        fhdTimerJob = null
                        flushPendingBelowFhd()
                    }
                }
            }
        }

        private fun startSdGraceTimer() {
            if (sdGraceMs <= 0L) {
                flushPendingAbove1080()
                return
            }
            if (sdTimerJob == null) {
                sdTimerJob = scope.launch {
                    delay(sdGraceMs)
                    synchronized(lock) {
                        sdTimerJob = null
                        flushPendingAbove1080()
                    }
                }
            }
        }

        private fun emitTopStreamAndAdvance(topStream: ExtractorLink) {
            emitSingleLink(topStream)
            hasEmittedTopStream = true
            val topRank = getSourcePriorityRank(topStream)
            completedTop720Ranks.add(topRank)
            if (is1080p(topStream)) {
                hasEmitted1080p = true
            }

            // Partition remaining staged links
            val remaining = stagedLinks.filter { canonicalStreamKey(it) !in emittedKeys }
                .sortedWith(STREAM_PRIORITY_COMPARATOR)
            stagedLinks.clear()

            val (remainingTopTier, remainingSecondary) = remaining.partition { isTopTierSource(it) }

            val remaining720 = remainingTopTier.filter { is720p(it) }
            val remaining1080 = remainingTopTier.filter { is1080p(it) }
            val remainingBelow720 = remainingTopTier.filter { isBelow720p(it) }
            val remainingAbove1080 = remainingTopTier.filter { isAbove1080p(it) }
            val remainingOther = remainingTopTier.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isAbove1080p(it) }

            // #1: Top-tier 720p links
            pendingTop720Links.addAll(remaining720)
            drainPendingTop720()

            // #2: Top-tier 1080p links
            val shouldHold1080 = pendingTop720Links.isNotEmpty() ||
                pending1080Links.isNotEmpty() ||
                (isRankInFlight != null && hasHigherPendingTop720Rank(0) && top720GraceMs > 0L && !top720GraceExpired) ||
                (isRankInFlight == null && top720GraceMs > 0L && !top720GraceExpired)
            if (shouldHold1080) {
                pending1080Links.addAll(remaining1080)
                startTop720GraceTimer()
            } else {
                for (link in remaining1080) {
                    hasEmitted1080p = true
                    emitSingleLink(link)
                }
            }

            pendingBelowFhdLinks.addAll(remainingBelow720)
            pendingAbove1080Links.addAll(remainingAbove1080)
            pendingSecondaryLinks.addAll(remainingSecondary)
            pendingAbove1080Links.addAll(remainingOther)

            if (hasEmitted1080p || fhdGraceExpired) {
                flushPendingBelowFhd()
            } else {
                startFhdGraceTimer()
            }
        }

        private fun flushPendingTop720() {
            top720GraceExpired = true
            if (pendingTop720Links.isNotEmpty()) {
                val sorted = pendingTop720Links.filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                pendingTop720Links.clear()
                for (link in sorted) {
                    hasEmittedTopStream = true
                    emitSingleLink(link)
                    completedTop720Ranks.add(getSourcePriorityRank(link))
                }
            }
        }

        private fun flushPending1080() {
            top720GraceExpired = true
            top720TimerJob?.cancel()
            top720TimerJob = null
            flushPendingTop720()

            if (pending1080Links.isNotEmpty()) {
                val sorted = pending1080Links.filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                pending1080Links.clear()
                for (link in sorted) {
                    hasEmittedTopStream = true
                    emitSingleLink(link)
                }
                hasEmitted1080p = true
            }

            if (hasEmitted1080p || fhdGraceExpired) {
                flushPendingBelowFhd()
            } else {
                startFhdGraceTimer()
            }
        }

        private fun flushPendingBelowFhd() {
            fhdGraceExpired = true
            fhdTimerJob?.cancel()
            fhdTimerJob = null
            if (pendingBelowFhdLinks.isNotEmpty()) {
                val sorted = pendingBelowFhdLinks.filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                pendingBelowFhdLinks.clear()

                for (link in sorted) {
                    hasEmittedBelow720p = true
                    emitSingleLink(link)
                }

                if (hasEmittedBelow720p || pendingAbove1080Links.isEmpty() || sdGraceExpired) {
                    flushPendingAbove1080()
                } else {
                    startSdGraceTimer()
                }
            } else {
                if (pendingAbove1080Links.isNotEmpty()) {
                    if (sdGraceExpired) {
                        flushPendingAbove1080()
                    } else {
                        startSdGraceTimer()
                    }
                } else if (pendingSecondaryLinks.isNotEmpty()) {
                    if (sdGraceExpired || sdGraceMs <= 0L) {
                        flushPendingSecondary()
                    } else {
                        startSdGraceTimer()
                    }
                }
            }
        }

        private fun flushPendingAbove1080() {
            sdGraceExpired = true
            sdTimerJob?.cancel()
            sdTimerJob = null
            if (pendingAbove1080Links.isNotEmpty()) {
                val sorted = pendingAbove1080Links.filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                pendingAbove1080Links.clear()
                for (link in sorted) {
                    emitSingleLink(link)
                }
            }
            flushPendingSecondary()
        }

        private fun flushPendingSecondary() {
            if (pendingSecondaryLinks.isNotEmpty()) {
                val sorted = pendingSecondaryLinks.filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)
                pendingSecondaryLinks.clear()
                for (link in sorted) {
                    emitSingleLink(link)
                }
            }
        }

        private fun emitSingleLink(link: ExtractorLink) {
            val key = canonicalStreamKey(link)
            if (emittedKeys.add(key)) {
                upstreamCallback(link)
            }
        }

        fun flush() {
            synchronized(lock) {
                stageTimerJob?.cancel()
                stageTimerJob = null
                top720TimerJob?.cancel()
                top720TimerJob = null
                fhdTimerJob?.cancel()
                fhdTimerJob = null
                sdTimerJob?.cancel()
                sdTimerJob = null

                val allRemaining = (stagedLinks + pendingTop720Links + pending1080Links + pendingBelowFhdLinks + pendingAbove1080Links + pendingSecondaryLinks)
                    .filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(STREAM_PRIORITY_COMPARATOR)

                for (link in allRemaining) {
                    emitSingleLink(link)
                }
                stagedLinks.clear()
                pendingTop720Links.clear()
                pending1080Links.clear()
                pendingBelowFhdLinks.clear()
                pendingAbove1080Links.clear()
                pendingSecondaryLinks.clear()

                hasEmittedTopStream = true
                hasEmitted1080p = true
                hasEmittedBelow720p = true
                top720GraceExpired = true
                fhdGraceExpired = true
                sdGraceExpired = true
            }
        }

        fun hasTopStreamEmitted(): Boolean = hasEmittedTopStream

        companion object {
            private val LEGACY_TOP_TIER_RANKS = listOf(100, 95, 92, 90, 88, 70)
            private val TOP_TIER_RANKS = listOf(100, 95, 92, 90, 88, 70)
            fun is720p(link: ExtractorLink): Boolean {
                val hasExplicit = link.quality > 0 && link.quality != Qualities.Unknown.value
                val q = if (hasExplicit) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url)
                }
                return if (hasExplicit) {
                    q == Qualities.P720.value || q in 700..749
                } else {
                    q == Qualities.P720.value || q in 700..749 || extractQualityFromText(link.name, link.url) == Qualities.P720.value
                }
            }

            fun is1080p(link: ExtractorLink): Boolean {
                val hasExplicit = link.quality > 0 && link.quality != Qualities.Unknown.value
                val q = if (hasExplicit) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url)
                }
                return if (hasExplicit) {
                    q == Qualities.P1080.value || (q in 750..1088 && q !in 700..749)
                } else {
                    q == Qualities.P1080.value || (q in 750..1088 && q !in 700..749) || extractQualityFromText(link.name, link.url) == Qualities.P1080.value
                }
            }

            fun isBelow720p(link: ExtractorLink): Boolean {
                val q = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url)
                }
                return q > 0 && q < 700 && q != Qualities.Unknown.value && !is720p(link)
            }

            fun isAbove1080p(link: ExtractorLink): Boolean {
                val q = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url)
                }
                return q > 1088 && !is1080p(link)
            }
        }
    }
}

/**
 * Backward compatibility alias for StreamPlay existing callers.
 */
typealias StreamPlayLinkOptimizer = StreamLinkOptimizer
