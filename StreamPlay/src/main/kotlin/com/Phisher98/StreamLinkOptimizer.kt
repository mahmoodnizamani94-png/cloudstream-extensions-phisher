package com.phisher98

import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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

    // Container & Manifest Regexes
    private val DIRECT_VIDEO_EXT_REGEX = Regex("""\.(mp4|mkv|webm|avi|mov|flv|ts|m4v|3gp|wmv|ogv|divx)$""", RegexOption.IGNORE_CASE)
    private val HLS_EXT_REGEX = Regex("""\.(m3u8|m3u)$""", RegexOption.IGNORE_CASE)
    private val DASH_EXT_REGEX = Regex("""\.mpd$""", RegexOption.IGNORE_CASE)
    private val TORRENT_EXT_REGEX = Regex("""\.torrent$""", RegexOption.IGNORE_CASE)
    private val AZURE_HLS_REGEX = Regex("""/manifest\(format=m3u8""", RegexOption.IGNORE_CASE)
    private val AZURE_DASH_REGEX = Regex("""/manifest\(format=mpd""", RegexOption.IGNORE_CASE)
    private val TEXT_PLAYLIST_REGEX = Regex("""/(?:master|playlist|index|list)\.txt$""", RegexOption.IGNORE_CASE)

    // Quality Tag Regexes
    private val QUALITY_EXPLICIT_REGEX = Regex("""\b(2160|1440|1080|720|480|360)\s*[pP]\b""")
    private val FOUR_K_REGEX = Regex("""\b(4K|UHD|2160P|3840[xX]2160|ULTRA[\s-_.]?HD)\b""", RegexOption.IGNORE_CASE)
    private val QHD_REGEX = Regex("""\b(1440P|2560[xX]1440|QHD|2K)\b""", RegexOption.IGNORE_CASE)
    private val FHD_REGEX = Regex("""\b(FHD|1080P|1920[xX]1080|FULL[\s-_.]?HD)\b""", RegexOption.IGNORE_CASE)
    private val HD_REGEX = Regex("""\b(720P|1280[xX]720|\bHD\b)\b""", RegexOption.IGNORE_CASE)
    private val SD_REGEX = Regex("""\b(480P|854[xX]480|\bSD\b)\b""", RegexOption.IGNORE_CASE)
    private val P360_REGEX = Regex("""\b(360P|640[xX]360)\b""", RegexOption.IGNORE_CASE)

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
            val resolvedQuality = if (link.quality <= Qualities.Unknown.value) {
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
            link.quality > Qualities.Unknown.value -> link.quality
            extractedQuality > Qualities.Unknown.value -> extractedQuality
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
            linkType = resolvedType
        )

        // 8. Effective referer
        val effectiveReferer = getEffectiveReferer(optimizedUrl, link.referer, optimizedHeaders)

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
        linkType: ExtractorLinkType = ExtractorLinkType.VIDEO
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
        val isVideasy = referer?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("cineby", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby", ignoreCase = true) ||
                    it.value.contains("speedracelight", ignoreCase = true)
            }
        when {
            lowerUrl.contains("pixeldrain.com") || lowerUrl.contains("pixeldrain.dev") || lowerUrl.contains("pd.cybar.xyz") ||
            lowerUrl.contains("hakunaymatata") || lowerUrl.contains("vidlink.pro") ||
            hadVidlinkHeader -> {
                // PixelDrain & Hakunaymatata/VidLink streams must NOT send third-party or arbitrary referers (stripping vidlink.pro / embed wrappers)
                headers.entries.removeIf { it.key.equals(HEADER_REFERER, ignoreCase = true) }
                headers.entries.removeIf { it.key.equals(HEADER_ORIGIN, ignoreCase = true) }
                if (lowerUrl.contains("hakunaymatata") || lowerUrl.contains("vidlink.pro") ||
                    hadVidlinkHeader ||
                    linkType == ExtractorLinkType.VIDEO || lowerUrl.contains(".mp4")) {
                    headers[HEADER_USER_AGENT] = "com.community.oneroom/50020115 (Linux; U; Android 15; en_US; OPPO CPH2579; Build/AP3A.240905.015.A2; Cronet/140.0.7339.51)"
                    headers[HEADER_ACCEPT] = "*/*"
                }
            }
            lowerUrl.contains("embed.su") -> {
                headers[HEADER_REFERER] = "https://embed.su/"
                headers[HEADER_ORIGIN] = "https://embed.su"
            }
            lowerUrl.contains("hexa.su") -> {
                headers[HEADER_REFERER] = "https://hexa.su/"
                headers[HEADER_ORIGIN] = "https://hexa.su"
            }
            lowerUrl.contains("flixer.su") -> {
                headers[HEADER_REFERER] = "https://flixer.su/"
                headers[HEADER_ORIGIN] = "https://flixer.su"
            }
            lowerUrl.contains("autoembed.cc") || lowerUrl.contains("player.autoembed.cc") -> {
                headers[HEADER_REFERER] = "https://player.autoembed.cc/"
                headers[HEADER_ORIGIN] = "https://player.autoembed.cc"
            }
            (lowerUrl.contains("peakstorm.top") || lowerUrl.contains("hypergate.top") ||
            lowerUrl.contains("vidfast.pro") || lowerUrl.contains("vidfast.vc")) && !isVideasy -> {
                headers[HEADER_REFERER] = "https://vidfast.vc/"
                headers[HEADER_ORIGIN] = "https://vidfast.vc"
            }
            lowerUrl.contains("speedracelight.com") || lowerUrl.contains("videasy.to") ||
            (lowerUrl.contains("peakstorm.top") && isVideasy) -> {
                headers[HEADER_REFERER] = "https://player.videasy.to/"
                headers[HEADER_ORIGIN] = "https://player.videasy.to"
            }
            lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc") -> {
                headers[HEADER_REFERER] = "https://www.cineby.sc/"
                headers[HEADER_ORIGIN] = "https://www.cineby.sc"
            }
            lowerUrl.contains("gofile.io") -> {
                headers[HEADER_REFERER] = "https://gofile.io/"
                headers[HEADER_ORIGIN] = "https://gofile.io"
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
            lowerUrl.contains("febbox.com") -> {
                headers[HEADER_REFERER] = "https://www.febbox.com/"
                headers[HEADER_ORIGIN] = "https://www.febbox.com"
                headers["Accept-Ranges"] = "bytes"
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

        return headers
    }

    /**
     * Computes the effective Referer header string for ExtractorLink.referer.
     */
    fun getEffectiveReferer(url: String, referer: String?, headers: Map<String, String>): String {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val isVideasy = referer?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("cineby", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby", ignoreCase = true) ||
                    it.value.contains("speedracelight", ignoreCase = true)
            }
        return when {
            lowerUrl.contains("pixeldrain.com") || lowerUrl.contains("pixeldrain.dev") || lowerUrl.contains("pd.cybar.xyz") ||
            lowerUrl.contains("hakunaymatata") || lowerUrl.contains("vidlink.pro") ||
            referer?.contains("vidlink.pro", ignoreCase = true) == true ||
            headers.entries.any {
                (it.key.equals(HEADER_REFERER, ignoreCase = true) || it.key.equals(HEADER_ORIGIN, ignoreCase = true)) &&
                    it.value.contains("vidlink.pro", ignoreCase = true)
            } -> ""
            lowerUrl.contains("embed.su") -> "https://embed.su/"
            lowerUrl.contains("hexa.su") -> "https://hexa.su/"
            lowerUrl.contains("flixer.su") -> "https://flixer.su/"
            lowerUrl.contains("autoembed.cc") || lowerUrl.contains("player.autoembed.cc") -> "https://player.autoembed.cc/"
            (lowerUrl.contains("peakstorm.top") || lowerUrl.contains("hypergate.top") ||
            lowerUrl.contains("vidfast.pro") || lowerUrl.contains("vidfast.vc")) && !isVideasy -> "https://vidfast.vc/"
            lowerUrl.contains("speedracelight.com") || lowerUrl.contains("videasy.to") ||
            (lowerUrl.contains("peakstorm.top") && isVideasy) -> "https://player.videasy.to/"
            lowerUrl.contains("videasy.net") || lowerUrl.contains("cineby.sc") -> "https://www.cineby.sc/"
            lowerUrl.contains("gofile.io") -> "https://gofile.io/"
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
        }
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
            else -> null
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

        return runCatching {
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
    }

    // ==================== Stream Comparison & Deduplicator ====================

    /**
     * Determines whether candidate ExtractorLink is strictly superior to existing ExtractorLink:
     * 1. Higher resolution quality
     * 2. Top-tier source priority rank (VidLink > HexaSU > AutoEmbed > VidFast > VidEasy > Secondary)
     * 3. Higher bitrate (kbps) (when resolutions and top-tier source ranks are equivalent)
     * 4. Direct endpoint rewrites
     * 5. Video source and HDR/codec score
     * 6. Direct byte stream over HLS for equal tier sources
     * 7. Audio format and channel score
     * 8. Anti-throttling header completeness
     */
    fun isBetterThan(candidate: ExtractorLink, current: ExtractorLink): Boolean {
        // 1. Resolution Quality comparison: higher resolution quality strictly takes precedence
        val q1 = if (candidate.quality > Qualities.Unknown.value) candidate.quality else extractQualityFromText(candidate.name, candidate.url)
        val q2 = if (current.quality > Qualities.Unknown.value) current.quality else extractQualityFromText(current.name, current.url)
        if (q1 != q2) {
            return q1 > q2
        }

        // 2. Top-Tier Source Priority Rank (VidLink > HexaSU > AutoEmbed > VidFast > VidEasy > Secondary)
        // Strictly prevents lower-tier sources (e.g. VidFast, VidEasy, or scrapers) from overriding top-tier sources at equivalent resolution
        val sourceRank1 = getSourcePriorityRank(candidate)
        val sourceRank2 = getSourcePriorityRank(current)
        if (sourceRank1 != sourceRank2) {
            return sourceRank1 > sourceRank2
        }

        // 3. Bitrate comparison (when resolutions and top-tier source ranks are equivalent)
        val b1 = parseBitrateKbpsFromText(candidate.name)
        val b2 = parseBitrateKbpsFromText(current.name)
        if (b1 != null && b2 != null && b1 != b2) {
            return b1 > b2
        }
        if (b1 != null && b2 == null) {
            return true
        }

        // 4. Direct endpoint score
        val endpointScore1 = if (candidate.url.contains("?download") || candidate.url.contains("&stream=1")) 10 else 0
        val endpointScore2 = if (current.url.contains("?download") || current.url.contains("&stream=1")) 10 else 0
        if (endpointScore1 != endpointScore2) {
            return endpointScore1 > endpointScore2
        }

        // 5. Video source & HDR score comparison
        val videoScore1 = calculateVideoScore(candidate)
        val videoScore2 = calculateVideoScore(current)
        if (videoScore1 != videoScore2) {
            return videoScore1 > videoScore2
        }

        // 6. Direct byte stream over HLS for equal tier sources (Range request chunking for downloads)
        val isDirect1 = candidate.type == ExtractorLinkType.VIDEO || candidate.url.endsWith(".mp4", ignoreCase = true)
        val isDirect2 = current.type == ExtractorLinkType.VIDEO || current.url.endsWith(".mp4", ignoreCase = true)
        if (isDirect1 != isDirect2) {
            return isDirect1
        }

        // 7. Audio score comparison
        val audioScore1 = calculateAudioScore(candidate)
        val audioScore2 = calculateAudioScore(current)
        if (audioScore1 != audioScore2) {
            return audioScore1 > audioScore2
        }

        // 8. Header score comparison
        val score1 = calculateHeaderScore(candidate)
        val score2 = calculateHeaderScore(current)
        if (score1 != score2) {
            return score1 > score2
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
            s.contains("hexasu") || s.contains("hexa.su") || s.contains("embedsu") || s.contains("embed.su") || s.contains("hexa") || s.contains("flixer.su") ||
                n.contains("hexasu") || n.contains("embedsu") || n.contains("embed.su") || n.contains("hexa") || n.contains("flixer.su") ||
                u.contains("hexa.su") || u.contains("embed.su") || u.contains("flixer.su") -> 90
            s.contains("autoembed") || n.contains("autoembed") || u.contains("autoembed.cc") || u.contains("player.autoembed.cc") -> 80
            s.contains("vidfast") || n.contains("vidfast") || u.contains("vidfast.pro") || u.contains("vidfast.vc") ||
                ((u.contains("peakstorm.top") || u.contains("hypergate.top")) && !s.contains("videasy") && !n.contains("videasy")) -> 70
            s.contains("videasy") || n.contains("videasy") || u.contains("videasy") || u.contains("speedracelight.com") || u.contains("videasy.to") || u.contains("videasy.net") || u.contains("cineby.sc") ||
                (u.contains("peakstorm.top") && (s.contains("videasy") || n.contains("videasy"))) -> 60
            s.contains("moviebox") || n.contains("moviebox") -> 50
            s.contains("rivestream") || n.contains("rivestream") -> 40
            s.contains("vidrock") || n.contains("vidrock") -> 30
            s.contains("moviesapi") || n.contains("moviesapi") -> 20
            else -> 0
        }
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
        return score
    }

    private fun calculateHeaderScore(link: ExtractorLink): Int {
        var score = 0
        if (link.headers[HEADER_ACCEPT_ENCODING] == "identity") score += 10
        if (link.headers.containsKey(HEADER_USER_AGENT)) score += 5
        if (link.headers.containsKey(HEADER_REFERER) || link.headers.containsKey(HEADER_ORIGIN)) score += 5
        if (link.headers.containsKey(HEADER_SEC_FETCH_DEST)) score += 5
        return score
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
            val key = canonicalStreamKey(link)
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
}

/**
 * Backward compatibility alias for StreamPlay existing callers.
 */
typealias StreamPlayLinkOptimizer = StreamLinkOptimizer
