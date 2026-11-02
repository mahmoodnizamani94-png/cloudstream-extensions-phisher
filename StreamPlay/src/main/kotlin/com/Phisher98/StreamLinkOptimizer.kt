package com.phisher98

import com.lagradost.api.Log
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
 * 4. Bitrate calculation `(sizeBytes * 8) / (durationSec * 1000)` as a display/ranking hint only;
 *    resolutions are never inferred from bitrate (see the honesty contract in `optimize`).
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
    private val TEXT_PLAYLIST_REGEX = Regex("""/(?:master|playlist|index|list)\.(?:txt|jpg|png)$|\.m3u8\.(?:png|jpg|txt)$""", RegexOption.IGNORE_CASE)

    // Quality Tag Regexes
    private val QUALITY_EXPLICIT_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(2160|1440|1080|720|576|540|480|360)\s*[pP]?(?:[^a-zA-Z0-9]|$)""")
    private val FOUR_K_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(4K|UHD|2160P?|3840[xX]2160|ULTRA[\s-_.]?HD)(?:[^a-zA-Z0-9]|$)""")
    private val QHD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(1440P?|2560[xX]1440|QHD|2K)(?:[^a-zA-Z0-9]|$)""")
    private val FHD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(FHD|1080P?|1920[xX]1080|FULL[\s-_.]?HD)(?:[^a-zA-Z0-9]|$)""")
    // Bare "HD"/"SD" are deliberately absent: they are marketing grades, not resolutions, and
    // mapping them to 720p/480p is exactly how a 360p file ends up labelled as 720p. Only an
    // explicit numeric height (handled by QUALITY_EXPLICIT_REGEX) or an exact pixel dimension
    // proves a resolution, and FHD/QHD/4K are kept because those acronyms have a single meaning.
    private val HD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(1280[xX]720)(?:[^a-zA-Z0-9]|$)""")
    private val SD_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(854[xX]480)(?:[^a-zA-Z0-9]|$)""")
    private val P360_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(640[xX]360)(?:[^a-zA-Z0-9]|$)""")
    private val EXPLICIT_QUALITY_QUERY_REGEX = Regex("""(?i)(?:[?&]|^)(?:quality|res|resolution|height|q)=([0-9]{3,4})[pP]?(?:&|$)""")
    private val EMBEDDED_MEDIA_URL_QUERY_REGEX = Regex("""(?i)(?:[?&]|^)(?:file|url|path|src)=([^&]+)""")

    // Bitrate & Size Regexes
    private val SIZE_REGEX = Regex("""\b(\d+(?:\.\d+)?)\s*(GB|GIB|MB|MIB|KB|KIB|B)\b""", RegexOption.IGNORE_CASE)

    /**
     * Whole-string matcher for source-reported size labels ("1.4 GB", "1,4gb", "1536 MB").
     * Used by [normalizeSizeLabel] to canonicalise them into the exact dialect the app's
     * download ranker parses.
     */
    private val SIZE_UNIT_LABEL_REGEX = Regex("""^(\d+(?:[.,]\d+)?)\s*(GB|GIB|MB|MIB|KB|KIB)$""", RegexOption.IGNORE_CASE)
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
     * Overloaded optimize supporting duration and size for the bitrate display hint.
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

        // Tier-3 shield gate: a resolved playback URL pointing at known ad/tracker
        // infrastructure is reduced to a tombstone (empty url) that the StreamDeduplicator
        // drops — the player must never be handed a blocked host.
        if (StreamPlayShield.isBlockedUrl(rawUrl)) {
            StreamPlayShield.countEmissionBlocked()
            Log.d(
                "StreamPlayShield",
                "🛡 dropped blocked URL from ${link.source} via ${StreamPlayShield.hostOf(rawUrl)}"
            )
            @Suppress("DEPRECATION")
            return ExtractorLink(
                source = link.source,
                name = link.name,
                url = "",
                referer = link.referer,
                quality = link.quality,
                type = link.type,
                headers = link.headers,
                extractorData = link.extractorData
            )
        }

        // Tier-3 hygiene: strip tracking particles (utm_*/fbclid/gclid/...) inherited from
        // redirect chains / player payloads before any rewrite or format stage sees them.
        val shieldCleanUrl = StreamPlayShield.stripTrackingParticles(rawUrl)

        // 1. Direct CDN / endpoint rewrites
        val optimizedUrl = resolveDirectStreamUrl(shieldCleanUrl)

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
        //
        // Honesty contract: a resolution label must be proven by an explicit numeric height in the
        // source name/URL, or declared by the extractor itself. Bitrate is never converted into a
        // quality label - a 1.5 Mbps stream can be low-bitrate 1080p or high-bitrate 480p, and the
        // old inference here was a primary source of fake 720p links. Bitrate stays a ranking and
        // display hint only; the host measures the real resolution before downloading.
        val extractedQuality = extractQualityFromText(link.name, optimizedUrl)
        val resolvedQuality = when {
            link.quality > 0 && link.quality != Qualities.Unknown.value -> link.quality
            extractedQuality > 0 && extractedQuality != Qualities.Unknown.value -> extractedQuality
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
            name = link.name,
            extractorData = link.extractorData
        )

        // 8. Effective referer
        val effectiveReferer = getEffectiveReferer(
            optimizedUrl,
            link.referer,
            optimizedHeaders,
            source = link.source,
            name = link.name,
            extractorData = link.extractorData
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

    private fun hasExplicitVidRiftProvenance(
        source: String?,
        name: String?,
        referer: String?,
        extractorData: String?,
        headers: Map<String, String>
    ): Boolean {
        return source?.contains("vidrift", ignoreCase = true) == true ||
            name?.contains("vidrift", ignoreCase = true) == true ||
            referer?.contains("vidrift.net", ignoreCase = true) == true ||
            extractorData?.contains("vidrift_lane=", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("vidrift", ignoreCase = true)
            }
    }

    private fun hasExplicitMeowTvProvenance(
        source: String?,
        name: String?,
        referer: String?,
        headers: Map<String, String>
    ): Boolean {
        return source?.contains("meowtv", ignoreCase = true) == true ||
            name?.contains("meowtv", ignoreCase = true) == true ||
            referer?.contains("meowtv.ru", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("meowtv", ignoreCase = true)
            }
    }

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
        name: String? = null,
        extractorData: String? = null
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
                (it.value.contains("vidlink.pro", ignoreCase = true) || it.value.contains("filmboom.top", ignoreCase = true))
        } || referer?.contains("vidlink.pro", ignoreCase = true) == true || referer?.contains("filmboom.top", ignoreCase = true) == true
        val isVidlink = (source?.contains("vidlink", ignoreCase = true) == true ||
            name?.contains("vidlink", ignoreCase = true) == true ||
            lowerUrl.contains("vidlink.pro") ||
            lowerUrl.contains("hakunaymatata") ||
            lowerUrl.contains("filmboom.top") ||
            hadVidlinkHeader) &&
            !lowerUrl.contains("mov2day") &&
            !lowerUrl.contains("/proxy?") &&
            !lowerUrl.contains("hd4u.") &&
            source?.equals("vidsrc", ignoreCase = true) != true
        val isVixSrc = source?.contains("vixsrc", ignoreCase = true) == true ||
            name?.contains("vixsrc", ignoreCase = true) == true ||
            lowerUrl.contains("vixsrc.to") ||
            referer?.contains("vixsrc", ignoreCase = true) == true
        val isVidEm = source?.contains("videm", ignoreCase = true) == true ||
            name?.contains("videm", ignoreCase = true) == true ||
            lowerUrl.contains("videm.xyz") ||
            lowerUrl.contains("vidsrc.buzz") ||
            referer?.contains("videm.xyz", ignoreCase = true) == true ||
            referer?.contains("vidsrc.buzz", ignoreCase = true) == true
        val hasExplicitVidRiftProvenance = hasExplicitVidRiftProvenance(
            source = source,
            name = name,
            referer = referer,
            extractorData = extractorData,
            headers = headers
        )
        val hasExplicitMeowTvProvenance = hasExplicitMeowTvProvenance(
            source = source,
            name = name,
            referer = referer,
            headers = headers
        )
        val isMeowTv = hasExplicitMeowTvProvenance ||
            (!hasExplicitVidRiftProvenance && (
                lowerUrl.contains("meowtv.ru") ||
                    lowerUrl.contains("hscow.com") ||
                    lowerUrl.contains("fcxmb.com") ||
                    lowerUrl.contains("vidjoy.org") ||
                    lowerUrl.contains("krico443jez.com") ||
                    lowerUrl.contains("fastcloud.live")
                ))
        val isVidRift = !hasExplicitMeowTvProvenance && (
            hasExplicitVidRiftProvenance ||
                lowerUrl.contains("vidrift.net") ||
                lowerUrl.contains("reelvault.click") ||
                lowerUrl.contains("bigbang.cymru") ||
                lowerUrl.contains("bigbang.observer") ||
                lowerUrl.contains("deton444eik.com") ||
                lowerUrl.contains("husvu445bey.com")
            )
        val isCineby = source?.contains("cineby", ignoreCase = true) == true ||
            name?.contains("cineby", ignoreCase = true) == true ||
            lowerUrl.contains("wecollege.net") ||
            lowerUrl.contains("goldenkite.top") ||
            lowerUrl.contains("paleoak.top") ||
            lowerUrl.contains("majorplay.net") ||
            lowerUrl.contains("finalsignal.top") ||
            lowerUrl.contains("falseparadise.workers.dev") ||
            lowerUrl.contains("wellsrobertsonkmlt.workers.dev") ||
            referer?.contains("vidy.st", ignoreCase = true) == true ||
            referer?.contains("player.videasy.to", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("cineby", ignoreCase = true)
            }
        val isMegaPlay = source?.contains("megaplay", ignoreCase = true) == true ||
            name?.contains("megaplay", ignoreCase = true) == true ||
            lowerUrl.contains("megaplay.buzz") ||
            lowerUrl.contains("nexabloom.top") ||
            lowerUrl.contains("broforgotsave.online") ||
            lowerUrl.contains("embermeadow.world") ||
            referer?.contains("megaplay.buzz", ignoreCase = true) == true
        // AniZone serves every episode from a rotating soft-sub CDN (`*.xin-cdn.xyz`,
        // `*.vid-cdn.xyz`). The packs are hotlink-protected by an Origin/Referer check, so the
        // canonical page origin has to be attached to both playback and download requests.
        val isAniZone = source?.contains("anizone", ignoreCase = true) == true ||
            name?.contains("anizone", ignoreCase = true) == true ||
            lowerUrl.contains("anizone.to") ||
            lowerUrl.contains("xin-cdn.xyz") ||
            lowerUrl.contains("vid-cdn.xyz") ||
            referer?.contains("anizone.to", ignoreCase = true) == true
        val isAnimegg = source?.contains("animegg", ignoreCase = true) == true ||
            name?.contains("animegg", ignoreCase = true) == true ||
            lowerUrl.contains("animegg.org") ||
            referer?.contains("animegg.org", ignoreCase = true) == true
        val isVidNest = source?.contains("vidnest", ignoreCase = true) == true ||
            name?.contains("vidnest", ignoreCase = true) == true ||
            lowerUrl.contains("vidnest.fun") ||
            lowerUrl.contains("vidnest") ||
            referer?.contains("vidnest", ignoreCase = true) == true
        val isAnimepahe = source?.contains("animepahe", ignoreCase = true) == true ||
            name?.contains("animepahe", ignoreCase = true) == true ||
            lowerUrl.contains("animepahe") ||
            referer?.contains("animepahe", ignoreCase = true) == true ||
            lowerUrl.contains("kwik.cx") ||
            lowerUrl.contains("kwik.si") ||
            lowerUrl.contains("uwucdn.top") ||
            lowerUrl.contains("owocdn.top")
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
        val explicitCineSrc = source?.contains("cinesrc", ignoreCase = true) == true ||
            name?.contains("cinesrc", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("cinesrc", ignoreCase = true)
            }
        val explicitCineJoy = !explicitCineSrc && (
            source?.contains("cinejoy", ignoreCase = true) == true ||
                name?.contains("cinejoy", ignoreCase = true) == true ||
                referer?.contains("cinejoy", ignoreCase = true) == true ||
                lowerUrl.contains("cinejoy.to") ||
                lowerUrl.contains("cinejoy.pk") ||
                lowerUrl.contains("solarpanelcleaning")
            )
        val isCineSrc = explicitCineSrc ||
            (!explicitCineJoy && (
                lowerUrl.contains("cinesrc.st") ||
                    lowerUrl.contains("api.wing.st") ||
                    lowerUrl.contains("bright67.online") ||
                    lowerUrl.contains("lightspeed.st") ||
                    referer?.contains("cinesrc.st", ignoreCase = true) == true ||
                    headers.entries.any { it.value.contains("cinesrc.st", ignoreCase = true) }
                ))
        val isCineJoy = explicitCineJoy
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
            referer?.contains("cineby.sc", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby.sc", ignoreCase = true) ||
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
                headers.entries.removeIf { it.key.equals(HEADER_REFERER, ignoreCase = true) }
                headers.entries.removeIf { it.key.equals(HEADER_ORIGIN, ignoreCase = true) }
                headers[HEADER_USER_AGENT] = "com.community.oneroom/50020115 (Linux; U; Android 15; en_US; OPPO CPH2579; Build/AP3A.240905.015.A2; Cronet/140.0.7339.51)"
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isMeowTv -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (supplied != null && supplied.startsWith("http", ignoreCase = true)) {
                    supplied
                } else {
                    "https://meowtv.ru/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = getHostUrl(eff) ?: "https://meowtv.ru"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isVidRift -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (supplied != null && supplied.startsWith("http", ignoreCase = true)) {
                    supplied
                } else {
                    "https://embed.vidrift.net/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = getHostUrl(eff) ?: "https://embed.vidrift.net"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isCineby -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (supplied != null &&
                    (supplied.contains("vidy.st", ignoreCase = true) || supplied.contains("player.videasy.to", ignoreCase = true))
                ) {
                    supplied
                } else {
                    "https://vidy.st/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = getHostUrl(eff) ?: "https://vidy.st"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
            }
            isMegaPlay -> {
                headers[HEADER_REFERER] = "https://megaplay.buzz/"
                headers[HEADER_ORIGIN] = "https://megaplay.buzz"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
            }
            isVixSrc || lowerUrl.contains("vixsrc.to") -> {
                headers[HEADER_REFERER] = "https://vixsrc.to/"
                headers[HEADER_ORIGIN] = "https://vixsrc.to"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isVidEm -> {
                // The engine mints origin-bound `/_stream` URLs whose manifest only answers to the
                // embed page that produced them, so an already-correct referer is preserved and
                // anything else is pinned back to the canonical origin.
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (supplied != null &&
                    (supplied.contains("videm.xyz", ignoreCase = true) || supplied.contains("vidsrc.buzz", ignoreCase = true))
                ) {
                    supplied
                } else {
                    "https://videm.xyz/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = getHostUrl(eff) ?: "https://videm.xyz"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isAnimegg -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (supplied != null && supplied.contains("animegg.org", ignoreCase = true)) {
                    supplied
                } else {
                    "https://www.animegg.org/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = getHostUrl(eff) ?: "https://www.animegg.org"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isVidNest || lowerUrl.contains("vidnest.fun") || lowerUrl.contains("vidnest") -> {
                headers[HEADER_REFERER] = "https://vidnest.fun/"
                headers[HEADER_ORIGIN] = "https://vidnest.fun"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isAnimepahe -> {
                val supplied = referer?.takeIf { it.contains("kwik", ignoreCase = true) }
                val animeRef = supplied ?: when {
                    lowerUrl.contains("kwik.si") -> "https://kwik.si/"
                    lowerUrl.contains("kwik.cx") || lowerUrl.contains("uwucdn.top") || lowerUrl.contains("owocdn.top") -> "https://kwik.cx/"
                    else -> "https://animepahe.pw/"
                }
                headers[HEADER_REFERER] = animeRef
                headers[HEADER_ORIGIN] = animeRef.removeSuffix("/")
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
            }
            isAniZone -> {
                headers[HEADER_REFERER] = "https://anizone.to/"
                headers[HEADER_ORIGIN] = "https://anizone.to"
                headers[HEADER_USER_AGENT] = MODERN_DESKTOP_UA
                headers[HEADER_ACCEPT] = "*/*"
                headers["Accept-Ranges"] = "bytes"
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
            lowerUrl.contains("solarpanelcleaning") || referer?.contains("solarpanelcleaning") == true -> {
                headers[HEADER_REFERER] = "https://solarpanelcleaning.cc/"
                headers[HEADER_ORIGIN] = "https://solarpanelcleaning.cc"
            }
            lowerUrl.contains("cinesrc.st") || isCineSrc -> {
                val suppliedRef = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                val eff = if (
                    suppliedRef?.contains("cinejoy.pk", ignoreCase = true) == true ||
                    lowerUrl.contains("lightspeed.st")
                ) {
                    "https://cinejoy.pk/"
                } else {
                    "https://cinesrc.st/"
                }
                headers[HEADER_REFERER] = eff
                headers[HEADER_ORIGIN] = eff.removeSuffix("/")
            }
            lowerUrl.contains("cinejoy.pk") || lowerUrl.contains("cinejoy.to") || lowerUrl.contains("bright67.online") || lowerUrl.contains("lightspeed.st") || isCineJoy -> {
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

        if (linkType == ExtractorLinkType.VIDEO) {
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
        name: String? = null,
        extractorData: String? = null
    ): String {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val isVideasy = source?.contains("videasy", ignoreCase = true) == true ||
            name?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("videasy", ignoreCase = true) == true ||
            referer?.contains("cineby.sc", ignoreCase = true) == true ||
            referer?.contains("speedracelight", ignoreCase = true) == true ||
            headers.entries.any {
                it.value.contains("videasy", ignoreCase = true) ||
                    it.value.contains("cineby.sc", ignoreCase = true) ||
                    it.value.contains("speedracelight", ignoreCase = true)
            }
        val hadVidlinkHeader = headers.entries.any {
            (it.key.equals(HEADER_REFERER, ignoreCase = true) || it.key.equals(HEADER_ORIGIN, ignoreCase = true)) &&
                (it.value.contains("vidlink.pro", ignoreCase = true) || it.value.contains("filmboom.top", ignoreCase = true))
        } || referer?.contains("vidlink.pro", ignoreCase = true) == true || referer?.contains("filmboom.top", ignoreCase = true) == true
        val isVidlink = (source?.contains("vidlink", ignoreCase = true) == true ||
            name?.contains("vidlink", ignoreCase = true) == true ||
            lowerUrl.contains("vidlink.pro") ||
            lowerUrl.contains("hakunaymatata") ||
            lowerUrl.contains("filmboom.top") ||
            hadVidlinkHeader) &&
            !lowerUrl.contains("mov2day") &&
            !lowerUrl.contains("/proxy?") &&
            !lowerUrl.contains("hd4u.") &&
            source?.equals("vidsrc", ignoreCase = true) != true
        val isVixSrc = source?.contains("vixsrc", ignoreCase = true) == true ||
            name?.contains("vixsrc", ignoreCase = true) == true ||
            lowerUrl.contains("vixsrc.to") ||
            referer?.contains("vixsrc", ignoreCase = true) == true
        val isVidEm = source?.contains("videm", ignoreCase = true) == true ||
            name?.contains("videm", ignoreCase = true) == true ||
            lowerUrl.contains("videm.xyz") ||
            lowerUrl.contains("vidsrc.buzz") ||
            referer?.contains("videm.xyz", ignoreCase = true) == true ||
            referer?.contains("vidsrc.buzz", ignoreCase = true) == true
        val hasExplicitVidRiftProvenance = hasExplicitVidRiftProvenance(
            source = source,
            name = name,
            referer = referer,
            extractorData = extractorData,
            headers = headers
        )
        val hasExplicitMeowTvProvenance = hasExplicitMeowTvProvenance(
            source = source,
            name = name,
            referer = referer,
            headers = headers
        )
        val isMeowTv = hasExplicitMeowTvProvenance ||
            (!hasExplicitVidRiftProvenance && (
                lowerUrl.contains("meowtv.ru") ||
                    lowerUrl.contains("hscow.com") ||
                    lowerUrl.contains("fcxmb.com") ||
                    lowerUrl.contains("vidjoy.org") ||
                    lowerUrl.contains("krico443jez.com") ||
                    lowerUrl.contains("fastcloud.live")
                ))
        val isVidRift = !hasExplicitMeowTvProvenance && (
            hasExplicitVidRiftProvenance ||
                lowerUrl.contains("vidrift.net") ||
                lowerUrl.contains("reelvault.click") ||
                lowerUrl.contains("bigbang.cymru") ||
                lowerUrl.contains("bigbang.observer") ||
                lowerUrl.contains("deton444eik.com") ||
                lowerUrl.contains("husvu445bey.com")
            )
        val isCineby = source?.contains("cineby", ignoreCase = true) == true ||
            name?.contains("cineby", ignoreCase = true) == true ||
            lowerUrl.contains("wecollege.net") ||
            lowerUrl.contains("goldenkite.top") ||
            lowerUrl.contains("paleoak.top") ||
            lowerUrl.contains("majorplay.net") ||
            lowerUrl.contains("finalsignal.top") ||
            lowerUrl.contains("falseparadise.workers.dev") ||
            lowerUrl.contains("wellsrobertsonkmlt.workers.dev") ||
            referer?.contains("vidy.st", ignoreCase = true) == true ||
            referer?.contains("player.videasy.to", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("cineby", ignoreCase = true)
            }
        val isMegaPlay = source?.contains("megaplay", ignoreCase = true) == true ||
            name?.contains("megaplay", ignoreCase = true) == true ||
            lowerUrl.contains("megaplay.buzz") ||
            lowerUrl.contains("nexabloom.top") ||
            lowerUrl.contains("broforgotsave.online") ||
            lowerUrl.contains("embermeadow.world") ||
            referer?.contains("megaplay.buzz", ignoreCase = true) == true
        // AniZone serves every episode from a rotating soft-sub CDN (`*.xin-cdn.xyz`,
        // `*.vid-cdn.xyz`). The packs are hotlink-protected by an Origin/Referer check, so the
        // canonical page origin has to be attached to both playback and download requests.
        val isAniZone = source?.contains("anizone", ignoreCase = true) == true ||
            name?.contains("anizone", ignoreCase = true) == true ||
            lowerUrl.contains("anizone.to") ||
            lowerUrl.contains("xin-cdn.xyz") ||
            lowerUrl.contains("vid-cdn.xyz") ||
            referer?.contains("anizone.to", ignoreCase = true) == true
        val isAnimegg = source?.contains("animegg", ignoreCase = true) == true ||
            name?.contains("animegg", ignoreCase = true) == true ||
            lowerUrl.contains("animegg.org") ||
            referer?.contains("animegg.org", ignoreCase = true) == true
        val isVidNest = source?.contains("vidnest", ignoreCase = true) == true ||
            name?.contains("vidnest", ignoreCase = true) == true ||
            lowerUrl.contains("vidnest.fun") ||
            lowerUrl.contains("vidnest") ||
            referer?.contains("vidnest", ignoreCase = true) == true
        val isAnimepahe = source?.contains("animepahe", ignoreCase = true) == true ||
            name?.contains("animepahe", ignoreCase = true) == true ||
            lowerUrl.contains("animepahe") ||
            referer?.contains("animepahe", ignoreCase = true) == true ||
            lowerUrl.contains("kwik.cx") ||
            lowerUrl.contains("kwik.si") ||
            lowerUrl.contains("uwucdn.top") ||
            lowerUrl.contains("owocdn.top")
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
        val explicitCineSrc = source?.contains("cinesrc", ignoreCase = true) == true ||
            name?.contains("cinesrc", ignoreCase = true) == true ||
            headers.entries.any {
                it.key.equals("X-StreamPlay-Source", ignoreCase = true) &&
                    it.value.equals("cinesrc", ignoreCase = true)
            }
        val explicitCineJoy = !explicitCineSrc && (
            source?.contains("cinejoy", ignoreCase = true) == true ||
                name?.contains("cinejoy", ignoreCase = true) == true ||
                referer?.contains("cinejoy", ignoreCase = true) == true ||
                lowerUrl.contains("cinejoy.to") ||
                lowerUrl.contains("cinejoy.pk") ||
                lowerUrl.contains("solarpanelcleaning")
            )
        val isCineSrc = explicitCineSrc ||
            (!explicitCineJoy && (
                lowerUrl.contains("cinesrc.st") ||
                    lowerUrl.contains("api.wing.st") ||
                    lowerUrl.contains("bright67.online") ||
                    lowerUrl.contains("lightspeed.st") ||
                    referer?.contains("cinesrc.st", ignoreCase = true) == true ||
                    headers.entries.any { it.value.contains("cinesrc.st", ignoreCase = true) }
                ))
        val isCineJoy = explicitCineJoy
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
            isVidlink -> ""
            isMeowTv -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (supplied != null && supplied.startsWith("http", ignoreCase = true)) {
                    supplied
                } else {
                    "https://meowtv.ru/"
                }
            }
            isVidRift -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (supplied != null && supplied.startsWith("http", ignoreCase = true)) {
                    supplied
                } else {
                    "https://embed.vidrift.net/"
                }
            }
            isCineby -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (supplied != null &&
                    (supplied.contains("vidy.st", ignoreCase = true) || supplied.contains("player.videasy.to", ignoreCase = true))
                ) {
                    supplied
                } else {
                    "https://vidy.st/"
                }
            }
            isMegaPlay -> "https://megaplay.buzz/"
            isVixSrc || lowerUrl.contains("vixsrc.to") -> "https://vixsrc.to/"
            isVidEm -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (supplied != null &&
                    (supplied.contains("videm.xyz", ignoreCase = true) || supplied.contains("vidsrc.buzz", ignoreCase = true))
                ) {
                    supplied
                } else {
                    "https://videm.xyz/"
                }
            }
            isAnimegg -> {
                val supplied = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (supplied != null && supplied.contains("animegg.org", ignoreCase = true)) supplied
                else "https://www.animegg.org/"
            }
            isVidNest || lowerUrl.contains("vidnest.fun") || lowerUrl.contains("vidnest") -> "https://vidnest.fun/"
            isAnimepahe -> {
                referer?.takeIf { it.contains("kwik", ignoreCase = true) } ?: when {
                    lowerUrl.contains("kwik.si") -> "https://kwik.si/"
                    lowerUrl.contains("kwik.cx") || lowerUrl.contains("uwucdn.top") || lowerUrl.contains("owocdn.top") -> "https://kwik.cx/"
                    else -> "https://animepahe.pw/"
                }
            }
            isAniZone -> "https://anizone.to/"
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
            lowerUrl.contains("solarpanelcleaning") || referer?.contains("solarpanelcleaning") == true -> "https://solarpanelcleaning.cc/"
            lowerUrl.contains("cinesrc.st") || isCineSrc -> {
                val suppliedRef = headers.entries.firstOrNull {
                    it.key.equals(HEADER_REFERER, ignoreCase = true) && it.value.isNotBlank()
                }?.value ?: referer?.takeIf { it.isNotBlank() }
                if (
                    suppliedRef?.contains("cinejoy.pk", ignoreCase = true) == true ||
                    lowerUrl.contains("lightspeed.st")
                ) {
                    "https://cinejoy.pk/"
                } else {
                    "https://cinesrc.st/"
                }
            }
            lowerUrl.contains("cinejoy.pk") || lowerUrl.contains("cinejoy.to") || lowerUrl.contains("bright67.online") || lowerUrl.contains("lightspeed.st") || isCineJoy -> "https://cinejoy.pk/"
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

        // Priority 3d: Masked/Text playlists (/master.txt, /playlist.txt, /playlist.jpg, *.m3u8.png)
        if (TEXT_PLAYLIST_REGEX.containsMatchIn(cleanPath)) {
            if (cleanPath.contains("/hls/") || cleanPath.contains("/m3u8/") ||
                cleanPath.endsWith("/playlist.jpg") || cleanPath.endsWith("/master.txt") ||
                cleanPath.endsWith("/playlist.txt") || cleanPath.contains("bright67.online") ||
                cleanPath.contains(".m3u8.")
            ) return ExtractorLinkType.M3U8
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
     * Canonical size-label dialect shared with the CloudStream download ranker (the app
     * parses "1.42 GB" / "750 MB" out of link names to elect the smallest same-quality
     * candidate). Emits GB/MB/KB only — exactly the units the ranker's FILE_SIZE_REGEX
     * accepts — so nothing else may ever be attached to a name.
     */
    fun formatSizeLabel(bytes: Long): String {
        val gb = 1024.0 * 1024.0 * 1024.0
        val mb = 1024.0 * 1024.0
        val kb = 1024.0
        val value = bytes.coerceAtLeast(0L).toDouble()
        return when {
            value >= gb -> String.format(Locale.ROOT, "%.2f GB", value / gb)
            value >= mb -> String.format(Locale.ROOT, "%.0f MB", value / mb)
            else -> String.format(Locale.ROOT, "%.0f KB", value / kb)
        }
    }

    /**
     * Canonicalises a source-reported size string ("1.4 GB", "1,4gb", "1536 MB", "1610612736")
     * into the [formatSizeLabel] dialect, or null when the text is not a size. The number is
     * preserved verbatim for explicit-unit input — no unit conversion, so candidate ranking
     * never loses precision to rounding; bare byte counts are formatted once.
     */
    fun normalizeSizeLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        SIZE_UNIT_LABEL_REGEX.matchEntire(trimmed)?.let { match ->
            val value = match.groupValues[1].replace(',', '.')
            val unit = match.groupValues[2].uppercase(Locale.ROOT).replace("I", "")
            return "$value $unit"
        }
        val bytes = trimmed.toLongOrNull() ?: return null
        if (bytes <= 0L) return null
        return formatSizeLabel(bytes)
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
     * Checks if a stream link belongs to one of the curated top-tier primary sources
     * (VidLink 100 > MeowTV 98 > Cineby 95 > CineSrc 94 > VidFast 93 > MegaPlay 92 > VidRift 91 >
     * AniZone 89 > AnimePahe 88 > Animex 87).
     */
    fun isTopTierSource(link: ExtractorLink): Boolean = getSourcePriorityRank(link) >= 85

    /**
     * Checks if a provider ID matches one of the curated top-tier primary sources.
     */
    fun isTopTierProvider(providerId: String): Boolean {
        val p = providerId.lowercase(Locale.ROOT)
        return p.contains("vidlink") ||
            p.contains("vidrift") ||
            p.contains("cineby") ||
            p.contains("cinesrc") ||
            p.contains("vidfast") ||
            p.contains("megaplay") ||
            p.contains("meowtv") ||
            p.contains("anizone") ||
            p.contains("animepahe") ||
            p.contains("animex")
    }

    fun is720p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.is720p(link)
    fun is1080p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.is1080p(link)
    fun isBelow720p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.isBelow720p(link)
    fun isAbove1080p(link: ExtractorLink): Boolean = PriorityStreamDispatcher.isAbove1080p(link)

    /**
     * Executes the Universal Top-Tier Stream Guard on any stream link:
     * Validates top-tier streams (VidLink 100 > MeowTV 98 > Cineby 95 > CineSrc 94 > VidFast 93 >
     * MegaPlay 92 > VidRift 91 > AniZone 89 > AnimePahe 88 > Animex 87) and forwards them cleanly to
     * emitAction without creating artificial or duplicated companion links.
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
     * Authentic stream emission for the curated v26 top-tier sources.
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
        extractorDataTag: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        fun mergeExtractorData(existing: String?): String {
            return listOfNotNull(existing, extractorDataTag, "sota_dual_quality")
                .flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(";")
        }
        if (!generatedLinks.isNullOrEmpty()) {
            val finalLinks = generatedLinks.map { link ->
                val detectedQ = if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
                    link.quality
                } else {
                    extractQualityFromText(link.name, link.url).takeIf { it > 0 && it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                }
                val extData = mergeExtractorData(link.extractorData)
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
                    extractorData = mergeExtractorData(null)
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
            if (q > 0 && q != Qualities.Unknown.value) {
                "$baseKey#$q"
            } else {
                // A demuxed-audio master is emitted once per genuine variant behind a routing
                // fragment (`#vid=720p`, `#vid=bw_1200000`, or `#vid=auto_2` when the manifest
                // declares neither a height nor a bandwidth). When no height is known the fragment
                // is the only thing that tells the variants apart; dropping it collapsed an entire
                // unlabelled ladder onto the lowest-bandwidth rendition, which is exactly the 360p
                // stream a 720p download must never silently become.
                val variantRoute = rawUrl.substringAfterLast('#', "")
                    .takeIf { it.startsWith("vid=", ignoreCase = true) }
                if (variantRoute != null) "$baseKey#$variantRoute" else baseKey
            }
        } else {
            baseKey
        }
    }

    // ==================== Stream Comparison & Deduplicator ====================

    const val VIDRIFT_SOURCE_RANK = 91
    const val VIDRIFT_MAX_720P_BACKUP_BYTES = 650L * 1024L * 1024L
    const val MAX_REASONABLE_STREAM_BYTES = 2_500L * 1024L * 1024L

    private val REGEX_EXPLICIT_400P = Regex("""\b400p\b|\b\d{3,4}\s*[xX×]\s*400\b""", RegexOption.IGNORE_CASE)

    fun getEffectiveQuality(link: ExtractorLink): Int {
        return if (link.quality > 0 && link.quality != Qualities.Unknown.value) {
            link.quality
        } else {
            extractQualityFromText(link.name, link.url)
        }
    }

    fun isUnknownQuality(link: ExtractorLink, q: Int = getEffectiveQuality(link)): Boolean {
        return q <= 0 || (q == Qualities.Unknown.value && !REGEX_EXPLICIT_400P.containsMatchIn(link.name))
    }

    fun isAnimeStream(link: ExtractorLink): Boolean {
        val n = link.name
        val ext = link.extractorData.orEmpty()
        val hdr = link.headers["X-StreamPlay-Anime"]
        return n.contains("[Sub] [Original]", ignoreCase = true) ||
            ext.contains("is_anime=true", ignoreCase = true) ||
            hdr.equals("true", ignoreCase = true)
    }

    fun isDirectMp4Stream(link: ExtractorLink): Boolean {
        val u = link.url.lowercase(Locale.ROOT)
        val cleanPath = u.substringBefore('?').substringBefore('#')
        if (link.type == ExtractorLinkType.M3U8 || link.type == ExtractorLinkType.DASH) return false
        if (u.contains(".m3u8") || u.contains(".mpd") || u.contains("master.txt")) return false
        if (cleanPath.endsWith(".mp4") || cleanPath.endsWith(".mkv") || cleanPath.endsWith(".webm")) return true
        if (u.contains("/hls/") || u.contains("/hls2/")) return false
        return link.type == ExtractorLinkType.VIDEO ||
            u.contains(".mp4") ||
            u.contains(".mkv") ||
            u.contains(".webm")
    }

    /**
     * Canonical SOTA stream priority tier multiplier (higher is strictly better):
     *
     * Movie & TV (`isAnime == false`):
     * - `100`    -> #1: `VidLink 720p` (Rank 100, 720p)
     * - `99`     -> #2: `MeowTV 720p` (Rank 98, 720p)
     * - `98`     -> #3: `VidLink 1080p` (Rank 100, 1080p)
     * - `85..95` -> #4: Other top-tier `720p` (`Cineby 95 > CineSrc 94 > VidFast 93 > MegaPlay 92 > VidRift 91 (<= 650 MB) > AniZone 89 > AnimePahe 88 > Animex 87`)
     * - `72..83` -> #5: Non-VidLink curated top-tier `1080p` (`MeowTV 1080p = 83 > Cineby 1080p = 80 > CineSrc 1080p = 79 > VidFast 1080p = 78 > MegaPlay 1080p = 77 > AniZone 1080p = 74 > AnimePahe 1080p = 73 > Animex 1080p = 72`)
     * - `71`     -> Demoted oversized `VidRift 720p` (`> 650 MB`)
     * - `70`     -> Demoted `VidRift 1080p` (excluded from curated 1080p chain)
     * - `54..69` -> #6: Top-tier sub-720p / Unknown / above-1080p (`sourceRank - 31`)
     * - `0`      -> #7: Secondary / fallback sources
     *
     * Anime (`isAnime == true`):
     * - `87..100` -> #1: All top-tier `720p` across `ANIME_DOWNLOAD_CHAIN` (`VidLink 100 > Cineby 95 > CineSrc 94 > MegaPlay 92 > AniZone 89 > AnimePahe 88 > Animex 87`)
     * - `72..85`  -> #2: All top-tier `1080p` across `ANIME_DOWNLOAD_CHAIN` (`VidLink 85 > Cineby 80 > CineSrc 79 > MegaPlay 77 > AniZone 74 > AnimePahe 73 > Animex 72`)
     */
    fun getPrimaryStreamTierMultiplier(
        sourceRank: Int,
        isTopTier: Boolean = sourceRank >= 85,
        is720: Boolean,
        is1080: Boolean,
        isBelow720: Boolean = false,
        isAbove1080: Boolean = false,
        link: ExtractorLink? = null,
        isAnime: Boolean = link?.let { isAnimeStream(it) } ?: false
    ): Int {
        if (!isTopTier || sourceRank < 85) return 0
        if (sourceRank == VIDRIFT_SOURCE_RANK) {
            val sizeBytes = link?.let { parseSizeBytes(it.name) } ?: 0L
            val isOversizedVidRift = sizeBytes > VIDRIFT_MAX_720P_BACKUP_BYTES
            return when {
                is720 && !isOversizedVidRift -> VIDRIFT_SOURCE_RANK // 91: healthy <= 650 MB 720p backup
                is720 && isOversizedVidRift -> 71                   // Demoted below curated 720p (87..100) and curated 1080p (72..83)
                is1080 -> 70                                        // Excluded from curated 1080p chain (72..83)
                else -> sourceRank - 31                             // 60: SD / Unknown / above-1080p
            }
        }
        if (isAnime) {
            return when {
                is720 -> sourceRank        // Anime 720p across ANIME_DOWNLOAD_CHAIN (100, 95, 94, 92, 89, 88, 87)
                is1080 -> sourceRank - 15  // Anime 1080p across ANIME_DOWNLOAD_CHAIN (85, 80, 79, 77, 74, 73, 72)
                else -> sourceRank - 31    // Anime SD / Unknown / above-1080p (69 .. 56)
            }
        }
        return when {
            sourceRank == 100 && is720 -> 100 // #1: VidLink 720p
            sourceRank == 98 && is720 -> 99   // #2: MeowTV 720p
            sourceRank == 100 && is1080 -> 98 // #3: VidLink 1080p
            is720 -> sourceRank               // #4: Other top-tier 720p (Cineby 95 .. Animex 87)
            is1080 -> sourceRank - 15         // #5: Non-VidLink top-tier 1080p (MeowTV 83 .. Animex 72)
            else -> sourceRank - 31           // #6: Top-tier SD / Unknown / above-1080p (69 .. 56)
        }
    }

    @JvmOverloads
    fun isBetterThan(
        candidate: ExtractorLink,
        current: ExtractorLink,
        isAnime: Boolean = isAnimeStream(candidate) && isAnimeStream(current)
    ): Boolean {
        val sourceRank1 = getSourcePriorityRank(candidate)
        val sourceRank2 = getSourcePriorityRank(current)
        val rawQ1 = getEffectiveQuality(candidate)
        val rawQ2 = getEffectiveQuality(current)
        val isUnknown1 = isUnknownQuality(candidate, rawQ1)
        val isUnknown2 = isUnknownQuality(current, rawQ2)
        val q1 = if (isUnknown1) (if (candidate.quality < 0) -1 else 0) else rawQ1
        val q2 = if (isUnknown2) (if (current.quality < 0) -1 else 0) else rawQ2
        val is720_1 = is720p(candidate) || q1 == Qualities.P720.value || q1 in 700..749
        val is720_2 = is720p(current) || q2 == Qualities.P720.value || q2 in 700..749
        val is1080_1 = !is720_1 && (is1080p(candidate) || q1 == Qualities.P1080.value || (q1 in 750..1088 && q1 !in 700..749))
        val is1080_2 = !is720_2 && (is1080p(current) || q2 == Qualities.P1080.value || (q2 in 750..1088 && q2 !in 700..749))
        val isBelow720_1 = !is720_1 && !is1080_1 && !isUnknown1 && isBelow720p(candidate)
        val isBelow720_2 = !is720_2 && !is1080_2 && !isUnknown2 && isBelow720p(current)
        val isAbove1080_1 = !is720_1 && !is1080_1 && !isUnknown1 && isAbove1080p(candidate)
        val isAbove1080_2 = !is720_2 && !is1080_2 && !isUnknown2 && isAbove1080p(current)

        val tierMult1 = getPrimaryStreamTierMultiplier(
            sourceRank = sourceRank1,
            isTopTier = isTopTierSource(candidate),
            is720 = is720_1,
            is1080 = is1080_1,
            isBelow720 = isBelow720_1,
            isAbove1080 = isAbove1080_1,
            link = candidate,
            isAnime = isAnime
        )
        val tierMult2 = getPrimaryStreamTierMultiplier(
            sourceRank = sourceRank2,
            isTopTier = isTopTierSource(current),
            is720 = is720_2,
            is1080 = is1080_2,
            isBelow720 = isBelow720_2,
            isAbove1080 = isAbove1080_2,
            link = current,
            isAnime = isAnime
        )
        if (tierMult1 != tierMult2) {
            return tierMult1 > tierMult2
        }
        if (sourceRank1 != sourceRank2) {
            return sourceRank1 > sourceRank2
        }

        // 2. Resolution Quality comparison within the same tier bucket (720p > 1080p > 480p > 360p > Unknown > 1440p > 2160p)
        val score1 = getQualityPriorityScore(q1)
        val score2 = getQualityPriorityScore(q2)
        if (score1 != score2) {
            return score1 > score2
        }

        // 2b. Intra-VidRift health tier & lowest-file-size hierarchy within Rank 91:
        if (sourceRank1 == VIDRIFT_SOURCE_RANK) {
            val vrCmp = compareVidRiftSameQuality(candidate, current)
            if (vrCmp != 0) {
                return vrCmp < 0
            }
        } else {
            // 2c. Intra-tier lowest verified file size (hasKnownSize DESC, sizeBytes ASC) across all providers:
            val size1 = parseSizeBytes(candidate.name)?.takeIf { it in 1L..MAX_REASONABLE_STREAM_BYTES }
            val size2 = parseSizeBytes(current.name)?.takeIf { it in 1L..MAX_REASONABLE_STREAM_BYTES }
            val hasSize1 = size1 != null
            val hasSize2 = size2 != null
            if (hasSize1 != hasSize2) {
                return hasSize1
            }
            if (size1 != null && size2 != null && size1 != size2) {
                return size1 < size2
            }

            // 2d. Direct MP4 (ExtractorLinkType.VIDEO) strictly beats HLS (ExtractorLinkType.M3U8):
            val isDirect1 = isDirectMp4Stream(candidate)
            val isDirect2 = isDirectMp4Stream(current)
            if (isDirect1 != isDirect2) {
                return isDirect1
            }
        }

        // 3. Genuine non-synthetic streams strictly prioritize over synthetic companions
        val candidateNonSynthetic = candidate.extractorData?.contains("sota_dual_quality") != true
        val currentNonSynthetic = current.extractorData?.contains("sota_dual_quality") != true
        if (candidateNonSynthetic != currentNonSynthetic) {
            return candidateNonSynthetic
        }

        // 4. Composite score alignment so isBetterThan and STREAM_PRIORITY_COMPARATOR never disagree
        val comp1 = getStreamCompositeScore(candidate, isAnime)
        val comp2 = getStreamCompositeScore(current, isAnime)
        if (comp1 != comp2) {
            return comp1 > comp2
        }

        // 5. Bitrate comparison (when resolutions and top-tier source ranks are equivalent)
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

        // 6. Direct endpoint score
        val endpointScore1 = if (candidate.url.contains("?download") || candidate.url.contains("&stream=1")) 10 else 0
        val endpointScore2 = if (current.url.contains("?download") || current.url.contains("&stream=1")) 10 else 0
        if (endpointScore1 != endpointScore2) {
            return endpointScore1 > endpointScore2
        }

        // 7. Video source & HDR score comparison
        val videoScore1 = calculateVideoScore(candidate)
        val videoScore2 = calculateVideoScore(current)
        if (videoScore1 != videoScore2) {
            return videoScore1 > videoScore2
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

    internal val VIDRIFT_REGIONAL_DUB_REGEX = Regex(
        """\b(hindi|tamil|telugu|bangla|bengali|malayalam|kannada|marathi|punjabi|gujarati|urdu|arabic|french|spanish|portuguese|russian|turkish|indonesian|thai|vietnamese|german|italian|polish|dutch|czech|ukrainian|farsi|persian|hebrew|greek|romanian|hungarian|tagalog|filipino|malay|latino|latin|castellano|korean|chinese|mandarin|cantonese|japanese|swedish|norwegian|danish|finnish)\b""",
        RegexOption.IGNORE_CASE
    )

    private val VIDRIFT_LANE_TAG_REGEX = Regex(
        """(?:^|;)vidrift_lane=([a-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    internal fun isVidRiftRegionalDub(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return VIDRIFT_REGIONAL_DUB_REGEX.containsMatchIn(text)
    }

    /**
     * Intra-VidRift lane health & CDN throughput score within Rank 91:
     *
     *  - `Direct` (`selfhost`): +60
     *  - `Vexon` / `Evion` (`media.reelvault.click/vexon|evion` / `bigbang.observer` — owned Cloudflare R2 H.264 MPEG-TS CDN): +55
     *  - `Titan (English)` (`img1.hscow.com` — 9.5 Mbps viewer-direct H.264 MPEG-TS): +48
     *  - `Atlas` (`edge.reelvault.click` — 8.5 Mbps adaptive 1080p/720p H.264 MPEG-TS): +44
     *  - `Titan (Multi-audio)` / generic non-dub `Titan`: +36
     *  - `Vela` (`edge.reelvault.click` adaptive H.264 MPEG-TS): +24
     *  - `Lyra (Proxied)` (`edge.reelvault.click/proxy` — proxied adaptive H.264): +20
     *  - `Titan` regional dubs: +12
     *  - Neutral / generic `VidRift`: 0
     *  - `Orion` (`/api/orion/` — demuxed fMP4 HEVC with B-frame CTS jitter & 1.54 MB child playlists): -30 (-40 for dubs)
     *  - `BigBang` (`bigbang.cymru` — same fMP4 HEVC encode & 1.54 MB child playlists): -35
     *  - `Lyra (Unproxied)` (`deton444eik.com` / `husvu445bey.com` — IP-locked upstream mirror): -45
     */
    internal fun getVidRiftLaneScore(link: ExtractorLink): Int {
        val n = link.name.lowercase(Locale.ROOT)
        val u = link.url.lowercase(Locale.ROOT)
        val isRegionalDub = isVidRiftRegionalDub(n)
        val isUnproxiedIpLockedHost = u.contains("deton444eik.com") || u.contains("husvu445bey.com")
        val explicitLane = VIDRIFT_LANE_TAG_REGEX.find(link.extractorData.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase(Locale.ROOT)
        if (explicitLane != null) {
            return when (explicitLane) {
                "direct", "selfhost" -> 60
                "vexon", "evion" -> 55
                "titan" -> when {
                    isRegionalDub -> 12
                    (n.contains("english") || n.contains("original")) && !n.contains("multi") && !isRegionalDub -> 48
                    else -> 36
                }
                "atlas" -> 44
                "vela" -> 24
                "lyra" -> if (isUnproxiedIpLockedHost) -45 else 20
                "orion" -> if (isRegionalDub) -40 else -30
                "bigbang" -> -35
                else -> 0
            }
        }
        return when {
            n.contains("vidrift direct") || u.contains("/selfhost") -> 60
            n.contains("vexon") || u.contains("/vexon/") || u.contains("bigbang.observer") ||
                n.contains("evion") || u.contains("/evion/") -> 55
            (n.contains("titan") || u.contains("hscow.com") || u.contains("fcxmb.com")) &&
                (n.contains("english") || n.contains("original")) &&
                !n.contains("multi") &&
                !isRegionalDub -> 48
            n.contains("atlas") -> 44
            (n.contains("titan") || u.contains("hscow.com") || u.contains("fcxmb.com")) && !isRegionalDub -> 36
            n.contains("vela") -> 24
            n.contains("lyra") && !isUnproxiedIpLockedHost && u.contains("reelvault.click") -> 20
            (n.contains("titan") || u.contains("hscow.com") || u.contains("fcxmb.com")) && isRegionalDub -> 12
            n.contains("orion") || u.contains("/api/orion/") -> if (isRegionalDub) -40 else -30
            n.contains("bigbang") || u.contains("bigbang.cymru") -> -35
            n.contains("lyra") || isUnproxiedIpLockedHost -> -45
            else -> 0
        }
    }

    /**
     * Partition of VidRift lanes into strict health tiers:
     *  - `2`: Healthy primary H.264 MPEG-TS lanes (`Direct`, `Vexon`, `Evion`, `Titan English`,
     *         `Atlas`, `Titan Multi-audio`, `Vela`, proxied `Lyra`).
     *  - `1`: Healthy regional dub H.264 lanes (`Titan` regional dubs).
     *  - `0`: Neutral / unlabelled VidRift lane.
     *  - `< 0`: Degraded fMP4 HEVC (`Orion` -30/-40, `BigBang` -35) and unproxied IP-locked `Lyra` (-45).
     */
    internal fun getVidRiftHealthTier(link: ExtractorLink): Int {
        val laneScore = getVidRiftLaneScore(link)
        return when {
            laneScore >= 20 -> 2
            laneScore in 1..19 -> 1
            laneScore == 0 -> 0
            else -> laneScore
        }
    }

    /**
     * Honest same-quality comparator for VidRift (`AGENTS.md` §1 & §5):
     *  1. `healthTier DESC` (healthy primary H.264 Tier 2 > regional dub Tier 1 > neutral Tier 0 > degraded HEVC/IP-locked < 0)
     *  2. Within healthy tiers (`healthTier >= 1`): streams with genuine known file size outrank unsized streams (`hasKnownSize DESC`),
     *     and among sized streams, lowest file size wins first (`sizeBytes ASC`).
     *  3. `laneScore DESC` breaks equal-size or both-unsized ties without any synthetic per-lane MB lookup table.
     */
    internal fun compareVidRiftSameQuality(a: ExtractorLink, b: ExtractorLink): Int {
        val health1 = getVidRiftHealthTier(a)
        val health2 = getVidRiftHealthTier(b)
        if (health1 != health2) {
            return health2.compareTo(health1)
        }
        if (health1 >= 1) {
            val size1 = parseSizeBytes(a.name)?.takeIf { it > 0L }
            val size2 = parseSizeBytes(b.name)?.takeIf { it > 0L }
            val hasSize1 = size1 != null
            val hasSize2 = size2 != null
            if (hasSize1 != hasSize2) {
                return if (hasSize1) -1 else 1
            }
            if (size1 != null && size2 != null && size1 != size2) {
                return size1.compareTo(size2)
            }
        }
        val lane1 = getVidRiftLaneScore(a)
        val lane2 = getVidRiftLaneScore(b)
        if (lane1 != lane2) {
            return lane2.compareTo(lane1)
        }
        return 0
    }

    private fun vidRiftLaneOrdinal(laneScore: Int): Int = when {
        laneScore >= 60 -> 7
        laneScore >= 55 -> 6
        laneScore >= 48 -> 5
        laneScore >= 44 -> 4
        laneScore >= 36 -> 3
        laneScore >= 24 -> 2
        laneScore >= 20 -> 1
        else -> 0
    }

    /**
     * Definitive source priority ranking for the curated v26 SOTA registry:
     *
     *   1. VidLink   (vidlink.pro, XSalsa20 token API, direct MP4 ladder + captions)          -> 100
     *   2. MeowTV    (meowtv.ru, single-use tickets, hscow/vidjoy/fcxmb HLS)                  -> 98
     *   3. Cineby    (api.wecollege.net + mvm1, goldenkite/paleoak/majorplay/finalsignal HLS) -> 95
     *   4. CineSrc   (cinesrc.st, CSP4 PoW + Nebula/secret/Wave/Surge bright67/lightspeed HLS)-> 94
     *   5. VidFast   (vidfast.vc, enc-vidfast stage handshake, moon.zenoak.top HLS)           -> 93
     *   6. MegaPlay  (megaplay.buzz + dec-megaplay, fetch.nexabloom.top HLS + VTT subs)       -> 92
     *   7. VidRift   (embed.vidrift.net, 720p-only Movie/TV backup + WebVTT subs)             -> 91
     *   8. AniZone   (anizone.to, xin-/vid-cdn HLS ladder + real English SRT/ASS sidecars)     -> 89
     *   9. AnimePahe (animepahe.pw, Cloudflare-gated Kwik HLS, deepest airing/rare catalog)   -> 88
     *  10. Animex    (animex.one, GraphQL catalog, multi-provider soft-subbed HLS)            -> 87
     *
     * The anime-only sources are ordered by how reliably they carry English subtitles and the
     * original Japanese audio: AniZone ships both as first-class artefacts of the episode pack,
     * AnimePahe has the widest catalogue but no sidecars, and Animex is the fallback of last resort.
     * That ordering only breaks ties between anime mirrors whose real sizes are equal or unknown,
     * so it never overrides the host's 720p-first, lowest-real-size download walk.
     *
     * VidRock (`vidrock`), VidSrc (`vidsrc`, `vidsrc.pm`, `cheaptruckrepairs`, `netoda.tech`,
     * `mov2day.xyz`, `vidora`), and VidSrcSU (`vidsrcsu`, `vidsrc.su`, `themoviedb.vidsrc.su`,
     * `theemoviedb.hexa.su`, `dragonballzfans.xyz`) are decommissioned and score 0.
     *
     * Matching is done on the source label as well as on the CDN hosts each source
     * actually serves from, so a link keeps its ranking even when it is re-wrapped by the
     * deduplicator or rewritten to a direct CDN endpoint.
     */
    fun getSourcePriorityRank(link: ExtractorLink): Int {
        val s = link.source.lowercase(Locale.ROOT)
        val n = link.name.lowercase(Locale.ROOT)
        val u = link.url.lowercase(Locale.ROOT)
        val r = link.referer.lowercase(Locale.ROOT)
        val explicitSourceHeader = link.headers.entries
            .firstOrNull { it.key.equals("X-StreamPlay-Source", ignoreCase = true) }
            ?.value?.trim()?.lowercase(Locale.ROOT)
        val explicitMeowTv = hasExplicitMeowTvProvenance(
            source = link.source,
            name = link.name,
            referer = link.referer,
            headers = link.headers
        )
        val explicitVidRift = hasExplicitVidRiftProvenance(
            source = link.source,
            name = link.name,
            referer = link.referer,
            extractorData = link.extractorData,
            headers = link.headers
        )
        return when {
            explicitSourceHeader == "vidlink" ||
                ((s.contains("vidlink") || n.contains("vidlink") ||
                    u.contains("vidlink.pro") || u.contains("hakunaymatata")) &&
                    !u.contains("mov2day") && !u.contains("/proxy?") && !u.contains("hd4u.") &&
                    !s.contains("vidsrc")) -> 100
            explicitMeowTv -> 98
            explicitVidRift -> VIDRIFT_SOURCE_RANK
            explicitSourceHeader == "cineby" -> 95
            explicitSourceHeader == "cinesrc" -> 94
            explicitSourceHeader == "vidfast" -> 93
            explicitSourceHeader == "megaplay" -> 92
            explicitSourceHeader == "anizone" -> 89
            explicitSourceHeader == "animepahe" -> 88
            explicitSourceHeader == "animex" -> 87
            u.contains("meowtv.ru") || u.contains("hscow.com") || u.contains("fcxmb.com") ||
                u.contains("vidjoy.org") || u.contains("krico443jez.com") || u.contains("fastcloud.live") -> 98
            u.contains("vidrift.net") || u.contains("reelvault.click") ||
                u.contains("bigbang.cymru") || u.contains("bigbang.observer") ||
                u.contains("deton444eik.com") || u.contains("husvu445bey.com") -> VIDRIFT_SOURCE_RANK
            s.contains("cineby") || n.contains("cineby") ||
                u.contains("wecollege.net") || u.contains("goldenkite.top") || u.contains("paleoak.top") ||
                u.contains("majorplay.net") || u.contains("finalsignal.top") || u.contains("falseparadise.workers.dev") ||
                u.contains("wellsrobertsonkmlt.workers.dev") ||
                r.contains("vidy.st") || r.contains("player.videasy.to") -> 95
            s.contains("cinesrc") || n.contains("cinesrc") ||
                u.contains("cinesrc.st") || u.contains("bright67.online") ||
                u.contains("api.wing.st") || u.contains("lightspeed.st") -> 94
            s.contains("vidfast") || n.contains("vidfast") ||
                u.contains("vidfast.vc") || u.contains("zenoak") -> 93
            s.contains("megaplay") || n.contains("megaplay") ||
                u.contains("megaplay.buzz") || u.contains("nexabloom.top") ||
                u.contains("broforgotsave.online") || u.contains("embermeadow.world") -> 92
            s.contains("anizone") || n.contains("anizone") ||
                u.contains("anizone.to") || u.contains("xin-cdn.xyz") ||
                u.contains("vid-cdn.xyz") -> 89
            s.contains("animepahe") || n.contains("animepahe") ||
                u.contains("animepahe.pw") || u.contains("animepahe.com") ||
                u.contains("kwik.cx") || u.contains("kwik.si") ||
                r.contains("kwik.cx") || r.contains("kwik.si") ||
                u.contains("uwucdn.top") || u.contains("owocdn.top") -> 88
            s.contains("animex") || n.contains("animex") ||
                u.contains("animex.one") || u.contains("aniwatchtv.site") ||
                u.contains("krussdomi.com") || u.contains("echovideo.to") ||
                u.contains("echovideo.ru") || u.contains("kaa.lt") ||
                u.contains("megavid.se") || u.contains("dramahot.top") ||
                u.contains("zokoanime.video") || u.contains("dpopdrop89.store") -> 87
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
            quality < 0 -> 0
            quality == 0 || quality == Qualities.Unknown.value -> 4500
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
     * Computes the overall stream score as a strict hierarchy:
     *
     * 1. TIER MULTIPLIER IS PRIMARY (`tierMultiplier * 10,000f`).
     *    Movie/TV (`isAnime == false`):
     *      VidLink 720p (100) > MeowTV 720p (99) > VidLink 1080p (98) >
     *      Other Top-Tier 720p (95..87) > Non-VidLink Top-Tier 1080p (83..72) >
     *      Demoted VidRift (71/70) > Top-Tier SD/Unknown/UHD (69..56) > Secondary (0).
     *    Anime (`isAnime == true`):
     *      Top-Tier 720p (100..87) > Top-Tier 1080p (85..72) > Top-Tier SD/Unknown/UHD (69..56) > Secondary (0).
     *
     * 2. QUALITY IS SECONDARY (`qualityTerm`):
     *    720p (9,600f) > 1080p (8,000f) > 480p (6,000f) > 576p (4,200f) > 360p (3,612.73f) >
     *    Unknown (3,000f) > 1440p (2,600f) > 2160p/4K (2,100f) > 8K (1,600f) > Invalid (1,000f).
     *
     * 3. INTRA-TIER FILE SIZE & DIRECT MP4 BONUS (`laneBonus`):
     *    Verified file size (`1..2500 MB`) outranks unsized streams and rewards smaller files first;
     *    Direct MP4 (`ExtractorLinkType.VIDEO`) outranks HLS (`ExtractorLinkType.M3U8`).
     */
    @JvmOverloads
    fun getStreamCompositeScore(
        link: ExtractorLink,
        isAnime: Boolean = isAnimeStream(link)
    ): Float {
        val sourceRank = getSourcePriorityRank(link)
        val rawQuality = getEffectiveQuality(link)
        val isUnknown = isUnknownQuality(link, rawQuality)
        val quality = if (isUnknown) (if (link.quality < 0) -1 else 0) else rawQuality
        val isTopTier = isTopTierSource(link)
        val is720 = is720p(link) || quality == Qualities.P720.value || quality in 700..749
        val is1080 = !is720 && (is1080p(link) || quality == Qualities.P1080.value || (quality in 750..1088 && quality !in 700..749))
        val isBelow720 = !is720 && !is1080 && !isUnknown && isBelow720p(link)
        val isAbove1080 = !is720 && !is1080 && !isUnknown && isAbove1080p(link)

        val bitrate = parseBitrateKbpsFromText(link.name) ?: 0L
        val videoScore = calculateVideoScore(link)
        val audioScore = calculateAudioScore(link)
        val headerScore = calculateHeaderScore(link)
        // Normalized tiebreaker (0.0f .. 9.9f) strictly prevents inverting source or quality tiers:
        val tiebreaker = minOf(9.9f, (minOf(50_000L, bitrate) / 10_000f) + (videoScore * 0.05f) + (audioScore * 0.05f) + (headerScore * 0.02f))

        val tierMultiplier = getPrimaryStreamTierMultiplier(
            sourceRank = sourceRank,
            isTopTier = isTopTier,
            is720 = is720,
            is1080 = is1080,
            isBelow720 = isBelow720,
            isAbove1080 = isAbove1080,
            link = link,
            isAnime = isAnime
        )
        val verifiedSizeBytes = parseSizeBytes(link.name)?.takeIf { it in 1L..MAX_REASONABLE_STREAM_BYTES }
        val isDirect = isDirectMp4Stream(link)

        val laneBonus = if (sourceRank == VIDRIFT_SOURCE_RANK) {
            val healthTier = getVidRiftHealthTier(link)
            val laneScore = getVidRiftLaneScore(link)
            val laneOrd = vidRiftLaneOrdinal(laneScore)
            val sizeBytes = parseSizeBytes(link.name)?.takeIf { it > 0L }
            when {
                healthTier >= 2 -> {
                    if (sizeBytes != null) {
                        val clampedMb = ((sizeBytes + (512L * 1024L)) / (1024L * 1024L)).toInt().coerceIn(1, 750)
                        val steps = 176 + (8 * (750 - clampedMb)) + laneOrd
                        steps * 0.0625f
                    } else {
                        (160 + laneOrd) * 0.0625f
                    }
                }
                healthTier == 1 -> {
                    if (sizeBytes != null) {
                        val clampedMb = ((sizeBytes + (512L * 1024L)) / (1024L * 1024L)).toInt().coerceIn(1, 750)
                        (16 + ((750 - clampedMb) / 6)) * 0.0625f
                    } else {
                        8 * 0.0625f
                    }
                }
                healthTier == 0 -> 0.0f
                else -> laneScore * 0.5f
            }
        } else if (verifiedSizeBytes != null) {
            val clampedMb = ((verifiedSizeBytes + (512L * 1024L)) / (1024L * 1024L)).toInt().coerceIn(1, 2500)
            if (tierMultiplier > 0) {
                // Exact IEEE-754 dyadic multiples of 0.0625f (32.0f .. 344.4375f):
                // strictly above unsized Direct MP4 (16.0f + 9.9f = 25.9f) and unsized HLS (9.9f),
                // and strictly below any quality bucket gap (>= 400f).
                (512 + ((2500 - clampedMb) * 2) + (if (isDirect) 1 else 0)) * 0.0625f
            } else {
                16.0f + ((2500 - clampedMb) * 0.01f) + (if (isDirect) 0.005f else 0.0f)
            }
        } else if (isDirect) {
            if (tierMultiplier > 0) 16.0f else 4.0f
        } else {
            0f
        }

        val qualityTerm = when {
            is720 -> 9_600f
            is1080 -> 8_000f
            quality == Qualities.P480.value -> 6_000f
            isBelow720 -> {
                if (quality in (Qualities.P480.value + 1) until 700) {
                    4_200f
                } else {
                    val clampedSd = minOf(479, maxOf(1, quality))
                    3_500f + (clampedSd / 4.79f * 1.5f)
                }
            }
            isUnknown && quality >= 0 -> 3_000f
            isAbove1080 -> {
                when {
                    quality == Qualities.P1440.value || (quality in 1089..1800) -> 2_600f
                    quality == Qualities.P2160.value || (quality in 1801..3000) -> 2_100f
                    else -> 1_600f
                }
            }
            else -> 1_000f
        }

        val baseScore = if (tierMultiplier > 0) {
            (tierMultiplier * 10_000f) + qualityTerm + laneBonus
        } else {
            // Secondary / fallback sources stay strictly below every top-tier stream.
            val secQualityTerm = when {
                is720 -> 800f
                is1080 -> 600f
                quality == Qualities.P480.value -> 400f
                isBelow720 -> 200f + (minOf(479, maxOf(1, quality)) / 4.79f * 0.5f)
                isUnknown && quality >= 0 -> 150f
                isAbove1080 -> 100f
                else -> 0f
            }
            secQualityTerm + laneBonus
        }

        val effectiveTiebreaker = if (sourceRank == VIDRIFT_SOURCE_RANK || verifiedSizeBytes != null) 0f else tiebreaker
        return baseScore + effectiveTiebreaker
    }

    fun streamPriorityComparator(isAnime: Boolean = false): Comparator<ExtractorLink> = Comparator { a, b ->
        when {
            isBetterThan(a, b, isAnime) -> -1
            isBetterThan(b, a, isAnime) -> 1
            else -> 0
        }
    }

    val STREAM_PRIORITY_COMPARATOR: Comparator<ExtractorLink> = streamPriorityComparator(isAnime = false)

    /**
     * Determines whether candidate stream is strictly higher priority for user playback than current stream,
     * enforcing the user priority hierarchy (720p > 1080p > 480p > 360p > Unknown > 2160p).
     */
    @JvmOverloads
    fun isStreamBetter(
        candidate: ExtractorLink,
        current: ExtractorLink,
        isAnime: Boolean = isAnimeStream(candidate) && isAnimeStream(current)
    ): Boolean {
        return isBetterThan(candidate, current, isAnime)
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
        private val onUpgradeCallback: ((ExtractorLink) -> Unit)? = null,
        private val isAnime: Boolean = false
    ) {
        constructor(upstreamCallback: (ExtractorLink) -> Unit) : this(upstreamCallback, null, false)
        constructor(
            upstreamCallback: (ExtractorLink) -> Unit,
            onUpgradeCallback: ((ExtractorLink) -> Unit)?
        ) : this(upstreamCallback, onUpgradeCallback, false)

        private val emittedStreams = ConcurrentHashMap<String, ExtractorLink>()

        fun emit(link: ExtractorLink): Boolean {
            return emitDetailed(link) != DeduplicationResult.DROPPED
        }

        fun emitDetailed(link: ExtractorLink): DeduplicationResult {
            // Shield gate: never forward, deduplicate or upgrade a link pointing at known
            // ad/tracker infrastructure (covers flows that bypass the optimizer).
            if (StreamPlayShield.isBlockedUrl(link.url)) {
                StreamPlayShield.countEmissionBlocked()
                return DeduplicationResult.DROPPED
            }
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
                    if (isBetterThan(link, existing, isAnime || (isAnimeStream(link) && isAnimeStream(existing)))) {
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
     *
     * Guarantees that the highest-ranked source is always the #1 result (VidLink 100 >
     * MeowTV 98 > Cineby 95 > CineSrc 94 > VidFast 93 > MegaPlay 92 > VidRift 91 > AniZone 89 >
     * AnimePahe 88 > Animex 87), and only then orders that source's own variants by the
     * quality hierarchy (720p > 1080p > 480p > above-1080p).
     *
     * Every hold in this dispatcher is *bounded*: it is an ordering window that exists to
     * let a higher-ranked source finish its round-trip, never a gate that depends on a
     * source answering. When a window expires the buffered links are always flushed in
     * strict comparator order, so a provider that is down can only ever cost the user the
     * window duration, never a missing stream.
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
        private val isRankInFlight: ((Int) -> Boolean)? = null,
        private val isAnime: Boolean = false
    ) {
        private val lock = Any()
        private val comparator: Comparator<ExtractorLink> = streamPriorityComparator(isAnime)
        private val stagedLinks = mutableListOf<ExtractorLink>()
        private val pendingTop720Links = mutableListOf<ExtractorLink>()
        private val pending1080Links = mutableListOf<ExtractorLink>()
        private val pendingBelowFhdLinks = mutableListOf<ExtractorLink>()
        private val pendingAbove1080Links = mutableListOf<ExtractorLink>()
        private val pendingSecondaryLinks = mutableListOf<ExtractorLink>()
        private val emittedKeys = ConcurrentHashMap.newKeySet<String>()
        private val completedTop720Ranks = mutableSetOf<Int>()
        private val completedTop1080Ranks = mutableSetOf<Int>()
        private val completedProviderRanks = mutableSetOf<Int>()
        @Volatile
        private var hasSubtitles = false
        @Volatile
        private var hasEmittedTopStream = false
        @Volatile
        private var hasEmitted1080p = false
        @Volatile
        private var hasEmittedBelow720p = false
        @Volatile
        private var subtitleGraceElapsed = false
        @Volatile
        private var topSourceGraceExpired = false
        @Volatile
        private var top720GraceExpired = false
        @Volatile
        private var top720WindowElapsed = false
        @Volatile
        private var fhdGraceExpired = false
        @Volatile
        private var sdGraceExpired = false
        private var stageTimerJob: Job? = null
        private var topSourceTimerJob: Job? = null
        private var top720TimerJob: Job? = null
        private var fhdTimerJob: Job? = null
        private var sdTimerJob: Job? = null

        /**
         * Highest-ranked link not yet emitted, considering *every* held bucket. A link
         * parked in a pending quality bucket still competes for the #1 slot, so a staged
         * lower variant can never be dispatched while a better one (e.g. VidLink 1080p vs
         * a lower-ranked source's 720p, or VidLink 480p vs VidLink's own 1080p) is still
         * buffered. The composite score is source-dominant, so this naturally yields the
         * highest-ranked source's best available variant.
         */
        private fun bestUnemittedCandidate(): ExtractorLink? {
            return (stagedLinks + pendingTop720Links + pending1080Links)
                .filter { canonicalStreamKey(it) !in emittedKeys }
                .sortedWith(comparator)
                .firstOrNull()
        }

        /** Removes a link from every bucket so it can be re-homed without duplication. */
        private fun detachFromBuckets(link: ExtractorLink) {
            stagedLinks.remove(link)
            pendingTop720Links.remove(link)
            pending1080Links.remove(link)
            pendingBelowFhdLinks.remove(link)
            pendingAbove1080Links.remove(link)
            pendingSecondaryLinks.remove(link)
        }

        private fun getLinkTierMultiplier(link: ExtractorLink): Int {
            val rank = getSourcePriorityRank(link)
            return getPrimaryStreamTierMultiplier(
                sourceRank = rank,
                isTopTier = isTopTierSource(link),
                is720 = is720p(link),
                is1080 = is1080p(link),
                isBelow720 = isBelow720p(link),
                isAbove1080 = isAbove1080p(link),
                link = link,
                isAnime = isAnime || isAnimeStream(link)
            )
        }

        /**
         * True when a higher top-tier rank [higherRank] has satisfied its ordering window relative
         * to a `720p` candidate of [candidate720Rank]:
         * - Every `higherRank > candidate720Rank` must complete its `720p` first.
         * - In Movie/TV mode (`!isAnime`), `VidLink` (`100`) also has a `1080p` (Tier #3, mult `98`)
         *   that outranks lower-tier (`< 98`) `720p` sources (`VidLink 720p -> MeowTV 720p -> VidLink 1080p -> Other Top 720p`).
         * - For a non-top-tier (`< 85`) `720p` candidate (`tierMultiplier = 0`), every top-tier rank (`>= 85`)
         *   has both `720p` (`85..100`) and `1080p` (`70..98`) above `tierMultiplier = 0`, so top-tier ranks
         *   must complete both their `720p` and `1080p` before a non-top-tier `720p` candidate can drain.
         * - In Anime mode (`isAnime`), ALL top-tier `720p` sources precede `VidLink 1080p`.
         */
        private fun isRankSatisfiedFor720Candidate(higherRank: Int, candidate720Rank: Int): Boolean {
            if (higherRank in completedProviderRanks) return true
            if (candidate720Rank < 85 && higherRank >= 85) {
                return isRankOrderingSatisfied(higherRank)
            }
            val has720 = higherRank in completedTop720Ranks ||
                (isRankInFlight == null && higherRank in completedTop1080Ranks)
            if (!has720) return false
            if (!isAnime && higherRank == 100 && candidate720Rank < 98) {
                if (100 in completedTop1080Ranks) return true
                return isRankInFlight != null && !isRankInFlight.invoke(100) &&
                    pending1080Links.none { getSourcePriorityRank(it) == 100 && canonicalStreamKey(it) !in emittedKeys }
            }
            return true
        }

        private fun isRankOrderingSatisfied(rank: Int): Boolean {
            if (rank in completedProviderRanks) return true
            if (rank !in completedTop720Ranks) return false
            if (rank in completedTop1080Ranks || rank == VIDRIFT_SOURCE_RANK) return true
            return (isRankInFlight == null || !isRankInFlight.invoke(rank)) && pending1080Links.isEmpty()
        }

        private fun hasRemainingTopTierHdOrFhdWork(): Boolean {
            if (pendingTop720Links.any { canonicalStreamKey(it) !in emittedKeys } ||
                pending1080Links.any { canonicalStreamKey(it) !in emittedKeys }
            ) {
                return true
            }
            if (isRankInFlight != null && isCrossSourceGraceStillActive()) {
                val ranksToCheck = if (activeTopRanks != null) {
                    (TOP_TIER_RANKS.filter { it in activeTopRanks } + activeTopRanks).distinct()
                } else {
                    val seenRanks = (completedTop720Ranks + completedTop1080Ranks +
                        (pendingTop720Links + pending1080Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                    TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                }
                return ranksToCheck.any { r ->
                    r >= 85 && !isRankOrderingSatisfied(r) && isRankInFlight.invoke(r)
                }
            }
            if (isRankInFlight == null && activeTopRanks != null && top720GraceMs > 0L && !top720GraceExpired) {
                return activeTopRanks.any { r ->
                    r >= 85 && !isRankOrderingSatisfied(r)
                }
            }
            if (isRankInFlight == null && activeTopRanks == null && top720GraceMs > 0L && !top720GraceExpired) {
                return LEGACY_TOP_TIER_RANKS.any { r ->
                    r !in completedTop720Ranks && r !in completedProviderRanks
                }
            }
            return false
        }

        private fun hasAnyHigherInFlightRankThanBestReady(): Boolean {
            val inFlightCheck = isRankInFlight ?: return false
            if (topSourceGraceExpired) return false
            val bestBuffered720Rank = (pendingTop720Links + stagedLinks.filter { is720p(it) })
                .maxOfOrNull { getSourcePriorityRank(it) } ?: 0
            val candidateRanks = if (activeTopRanks != null) {
                (TOP_TIER_RANKS.filter { it in activeTopRanks } + activeTopRanks).distinct()
            } else {
                TOP_TIER_RANKS
            }
            return candidateRanks.any { rank ->
                rank > bestBuffered720Rank && !isRankSatisfiedFor720Candidate(rank, bestBuffered720Rank) && inFlightCheck(rank)
            }
        }

        private fun isCrossSourceGraceStillActive(): Boolean {
            if (topSourceGraceMs > top720GraceMs) {
                return topSourceGraceMs > 0L && !topSourceGraceExpired
            }
            if (top720GraceMs > 0L && !top720GraceExpired) return true
            if (topSourceGraceMs > 0L && !topSourceGraceExpired) return true
            return false
        }

        /**
         * Determines whether a `1080p` candidate from [linkRank] must be held in [pending1080Links]:
         * - In Movie/TV mode (`!isAnime`), for `VidLink 1080p` (`linkRank >= 100`, #3 overall, tierMultiplier 98):
         *   holds only for `VidLink 720p` (`100`) and `MeowTV 720p` (`98`), never for lower-ranked `720p` sources (`< 98`).
         * - In Anime mode (`isAnime`), or for non-VidLink `1080p` (`linkRank < 100`): holds while ANY top-tier `720p`
         *   (`>= 85`) or higher-ranked `1080p` (`> linkRank`) is queued or in flight within the grace window.
         */
        private fun shouldHold1080ForRank(linkRank: Int, link: ExtractorLink? = null): Boolean {
            val effectiveIsAnime = isAnime || (link != null && isAnimeStream(link))
            val candidateMult = getPrimaryStreamTierMultiplier(
                sourceRank = linkRank,
                isTopTier = linkRank >= 85,
                is720 = false,
                is1080 = true,
                link = link,
                isAnime = effectiveIsAnime
            )
            val hasBetterQueued = (pendingTop720Links + pending1080Links + stagedLinks).any {
                canonicalStreamKey(it) !in emittedKeys && getLinkTierMultiplier(it) > candidateMult
            }
            if (hasBetterQueued) return true

            if (!effectiveIsAnime && linkRank >= 100) {
                val meowPending720 = 98 !in completedTop720Ranks && 98 !in completedProviderRanks && (
                    if (isRankInFlight != null) {
                        (activeTopRanks == null || 98 in activeTopRanks) && isRankInFlight.invoke(98)
                    } else {
                        activeTopRanks != null && 98 in activeTopRanks
                    }
                )
                if (meowPending720) {
                    if (isRankInFlight != null) {
                        if (isCrossSourceGraceStillActive()) return true
                    } else if (top720GraceMs > 0L && !top720GraceExpired) {
                        return true
                    }
                }
                if (100 in completedTop720Ranks || 100 in completedProviderRanks) return false
                if (isRankInFlight != null && isRankInFlight.invoke(100)) {
                    if (top720GraceMs > 0L && !top720GraceExpired && !top720WindowElapsed) return true
                    if (top720GraceMs <= 0L && topSourceGraceMs > 0L && !topSourceGraceExpired) return true
                }
                return false
            }

            // Anime 1080p (including VidLink 1080p) and Non-VidLink 1080p (linkRank < 100):
            // yields to ALL top-tier 720p sources and higher-ranked 1080p sources.
            if (isRankInFlight != null && isCrossSourceGraceStillActive()) {
                val ranksToCheck = if (activeTopRanks != null) {
                    (TOP_TIER_RANKS.filter { it in activeTopRanks } + activeTopRanks).distinct()
                } else {
                    val seenRanks = (completedTop720Ranks + completedTop1080Ranks + (pendingTop720Links + pending1080Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                    TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                }
                if (ranksToCheck.any { it > linkRank && !isRankOrderingSatisfied(it) && isRankInFlight.invoke(it) }) {
                    return true
                }
                if (ranksToCheck.any { it >= 85 && it !in completedTop720Ranks && it !in completedProviderRanks && isRankInFlight.invoke(it) }) {
                    return true
                }
            } else if (isRankInFlight == null && activeTopRanks != null && top720GraceMs > 0L && !top720GraceExpired) {
                if (activeTopRanks.any { it > linkRank && !isRankOrderingSatisfied(it) }) {
                    return true
                }
                if (activeTopRanks.any { it >= 85 && it !in completedTop720Ranks && it !in completedProviderRanks }) {
                    return true
                }
            } else if (isRankInFlight == null && activeTopRanks == null && top720GraceMs > 0L && !top720GraceExpired) {
                if (LEGACY_TOP_TIER_RANKS.any { it !in completedTop720Ranks && it !in completedProviderRanks } || hasHigherPendingTop720Rank(0)) {
                    return true
                }
            }
            if (top720GraceMs <= 0L || top720GraceExpired || top720WindowElapsed) return false
            if (linkRank >= 85) {
                if (linkRank in completedTop720Ranks || linkRank in completedProviderRanks) return false
                return isRankInFlight == null || isRankInFlight.invoke(linkRank)
            }
            return isRankInFlight == null
        }

        private fun scheduleTimersForPendingTopTier() {
            val hasAnyPendingWork = pendingTop720Links.isNotEmpty() ||
                pending1080Links.isNotEmpty() ||
                ((pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty() || pendingSecondaryLinks.isNotEmpty()) && hasRemainingTopTierHdOrFhdWork())
            if (!hasAnyPendingWork) return
            val hasCrossSourceWait = isRankInFlight != null && (
                hasAnyHigherInFlightRankThanBestReady() ||
                    pendingTop720Links.any { hasHigherPendingTop720Rank(getSourcePriorityRank(it), it) } ||
                    pending1080Links.any { shouldHold1080ForRank(getSourcePriorityRank(it), it) } ||
                    ((pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty() || pendingSecondaryLinks.isNotEmpty()) && hasRemainingTopTierHdOrFhdWork())
                )
            if (hasCrossSourceWait &&
                topSourceGraceMs > 0L &&
                !topSourceGraceExpired &&
                (topSourceGraceMs > top720GraceMs || pendingTop720Links.isNotEmpty() || pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty() || pendingSecondaryLinks.isNotEmpty())
            ) {
                startTopSourceGraceTimer()
            }
            val hasFrontier1080WaitingForSameRank720 = pending1080Links.any {
                val r = getSourcePriorityRank(it)
                !hasHigherPendingTop720Rank(r, it) || isRankInFlight == null || topSourceGraceMs <= top720GraceMs
            }
            val needs720TimerForStandalone = (isRankInFlight == null || topSourceGraceMs <= top720GraceMs) &&
                hasAnyPendingWork
            if ((hasFrontier1080WaitingForSameRank720 || needs720TimerForStandalone) &&
                top720GraceMs > 0L &&
                !top720GraceExpired &&
                (!top720WindowElapsed || topSourceGraceMs <= top720GraceMs)
            ) {
                startTop720GraceTimer()
            }
        }

        fun onSubtitleReceived() {
            synchronized(lock) {
                hasSubtitles = true
                if (!hasEmittedTopStream) {
                    val best = bestUnemittedCandidate()
                    if (best != null && is720p(best)) {
                        dispatchOrStageBest(best)
                    }
                }
            }
        }

        private fun dispatchOrStageBest(best: ExtractorLink) {
            val rank = getSourcePriorityRank(best)
            val maxRank = activeTopRanks?.maxOrNull() ?: 100
            detachFromBuckets(best)
            val canDispatchNow = when {
                is1080p(best) -> !shouldHold1080ForRank(rank, best) || topSourceGraceMs <= 0L || topSourceGraceExpired
                is720p(best) -> rank >= maxRank || !hasHigherPendingTop720Rank(rank, best) || topSourceGraceMs <= 0L || topSourceGraceExpired
                else -> !hasRemainingTopTierHdOrFhdWork() && (topSourceGraceMs <= 0L || topSourceGraceExpired)
            }
            if (canDispatchNow) {
                // Highest-ranked source (or nothing better is running) -> dispatch IMMEDIATELY as #1!
                stageTimerJob?.cancel()
                stageTimerJob = null
                emitTopStreamAndAdvance(best)
            } else {
                // A higher-ranked source is still in flight: hold this link in the appropriate bucket.
                if (is1080p(best)) {
                    pending1080Links.add(best)
                } else if (isBelow720p(best) || isUnknownQuality(best)) {
                    pendingBelowFhdLinks.add(best)
                } else if (isAbove1080p(best)) {
                    pendingAbove1080Links.add(best)
                } else {
                    pendingTop720Links.add(best)
                }
                scheduleTimersForPendingTopTier()
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
                val linkIsUnknown = isUnknownQuality(link)
                val linkIsTopTier = isTopTierSource(link)

                if (hasEmittedTopStream) {
                    // Secondary sources (< 85 rank) buffered while top tier streams are active/pending
                    if (!linkIsTopTier && linkRankIsSecondary(link)) {
                        val topTierPendingOrInFlight = hasRemainingTopTierHdOrFhdWork() ||
                            (!top720GraceExpired && top720GraceMs > 0L && hasHigherPendingTop720Rank(0)) ||
                            (isRankInFlight != null && !topSourceGraceExpired && topSourceGraceMs > 0L && hasHigherPendingTop720Rank(0))
                        if (!hasEmitted1080p || topTierPendingOrInFlight || !sdGraceExpired || pendingSecondaryLinks.isNotEmpty() || pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty() || pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty()) {
                            pendingSecondaryLinks.add(link)
                            if (topTierPendingOrInFlight || pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty()) {
                                scheduleTimersForPendingTopTier()
                                if (topSourceTimerJob == null && top720TimerJob == null && top720GraceMs > 0L && !top720GraceExpired) {
                                    startTop720GraceTimer()
                                }
                            } else if (!hasEmitted1080p && !fhdGraceExpired) {
                                startFhdGraceTimer()
                            } else if (!sdGraceExpired) {
                                startSdGraceTimer()
                            }
                            return
                        }
                        emitSingleLink(link)
                        return
                    }

                    // Tier 1: 720p from top-tier sources
                    if (linkIs720) {
                        val linkRank = getSourcePriorityRank(link)
                        val shouldHoldLower720 = hasHigherPendingTop720Rank(linkRank, link) && (
                            (isRankInFlight != null && isCrossSourceGraceStillActive()) ||
                                (isRankInFlight == null && top720GraceMs > 0L && !top720GraceExpired)
                            )
                        if (shouldHoldLower720) {
                            pendingTop720Links.add(link)
                            scheduleTimersForPendingTopTier()
                            return
                        } else {
                            emitSingleLink(link)
                            completedTop720Ranks.add(linkRank)
                            drainPendingTop720()
                            return
                        }
                    }

                    // Tier 2: 1080p from top-tier sources (or when 1080p arrives in standalone mode)
                    if (linkIs1080) {
                        val linkRank = getSourcePriorityRank(link)
                        if (shouldHold1080ForRank(linkRank, link)) {
                            pending1080Links.add(link)
                            scheduleTimersForPendingTopTier()
                            return
                        }
                        emitSingleLink(link)
                        completedTop1080Ranks.add(linkRank)
                        if (isAnime || linkRank < 100 || !hasRemainingTopTierHdOrFhdWork()) {
                            hasEmitted1080p = true
                            fhdTimerJob?.cancel()
                            fhdTimerJob = null
                        }
                        drainPendingTop720()
                        if (!hasRemainingTopTierHdOrFhdWork() && (hasEmitted1080p || fhdGraceExpired)) {
                            flushPendingBelowFhd()
                        } else if (hasRemainingTopTierHdOrFhdWork()) {
                            scheduleTimersForPendingTopTier()
                        }
                        return
                    }

                    // Top-tier HD/FHD work is still in flight or post-720p 1080p has not emitted yet: stage sub-FHD/4K
                    if (hasRemainingTopTierHdOrFhdWork() || (!hasEmitted1080p && !fhdGraceExpired)) {
                        if (linkIsBelow720 || linkIsUnknown) {
                            pendingBelowFhdLinks.add(link)
                        } else {
                            pendingAbove1080Links.add(link)
                        }
                        if (hasRemainingTopTierHdOrFhdWork()) {
                            scheduleTimersForPendingTopTier()
                        } else {
                            startFhdGraceTimer()
                        }
                        return
                    }

                    // Tier 3: below 720p (480p, 360p, Unknown) links emit after HD/FHD tiers
                    if (linkIsBelow720 || linkIsUnknown) {
                        if (pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty() || hasRemainingTopTierHdOrFhdWork()) {
                            pendingBelowFhdLinks.add(link)
                            scheduleTimersForPendingTopTier()
                            return
                        }
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

                val linkRank = getSourcePriorityRank(link)
                val shouldHoldInitial1080 = !hasEmittedTopStream && linkIs1080 && (
                    pending1080Links.isNotEmpty() ||
                    (isRankInFlight != null && shouldHold1080ForRank(linkRank, link)) ||
                    (isRankInFlight == null && top720TimerJob?.isActive == true)
                )
                if (shouldHoldInitial1080) {
                    pending1080Links.add(link)
                    scheduleTimersForPendingTopTier()
                    return
                }

                if (!hasEmittedTopStream && !linkIs720 && !linkIs1080) {
                    val hasHdOrFhdWork = pendingTop720Links.isNotEmpty() ||
                        pending1080Links.isNotEmpty() ||
                        stagedLinks.any { is720p(it) || is1080p(it) } ||
                        hasRemainingTopTierHdOrFhdWork()
                    if (hasHdOrFhdWork) {
                        if (!linkIsTopTier && linkRankIsSecondary(link)) {
                            pendingSecondaryLinks.add(link)
                        } else if (linkIsBelow720 || linkIsUnknown) {
                            pendingBelowFhdLinks.add(link)
                        } else {
                            pendingAbove1080Links.add(link)
                        }
                        scheduleTimersForPendingTopTier()
                        return
                    }
                }

                stagedLinks.add(link)

                // If a link arrived and subtitles are ready (or subtitleGraceMs already elapsed),
                // dispatch the composite-best candidate, falling back to the link itself.
                if (linkIs720 && (hasSubtitles || subtitleGraceElapsed || subtitleGraceMs <= 0L)) {
                    val best = bestUnemittedCandidate() ?: link
                    dispatchOrStageBest(best)
                    return
                }

                // If 720p arrived without subtitles yet, start short subtitle grace timer
                if (linkIs720 && !hasSubtitles) {
                    if (stageTimerJob == null) {
                        stageTimerJob = scope.launch {
                            delay(subtitleGraceMs)
                            synchronized(lock) {
                                stageTimerJob = null
                                subtitleGraceElapsed = true
                                if (!hasEmittedTopStream) {
                                    val best = bestUnemittedCandidate()
                                    if (best != null) {
                                        val rank = getSourcePriorityRank(best)
                                        val maxRank = activeTopRanks?.maxOrNull() ?: 100
                                        if (is1080p(best)) {
                                            val hold1080 = pending1080Links.any { it != best && canonicalStreamKey(it) !in emittedKeys } ||
                                                (isRankInFlight != null && shouldHold1080ForRank(rank, best)) ||
                                                (isRankInFlight == null && top720TimerJob?.isActive == true)
                                            if (hold1080) {
                                                detachFromBuckets(best)
                                                pending1080Links.add(best)
                                                val staged720 = stagedLinks.filter { is720p(it) }
                                                val staged1080 = stagedLinks.filter { is1080p(it) }
                                                val stagedBelow = stagedLinks.filter { isBelow720p(it) || isUnknownQuality(it) }
                                                val stagedOther = stagedLinks.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isUnknownQuality(it) }
                                                stagedLinks.clear()
                                                pendingTop720Links.addAll(staged720)
                                                pending1080Links.addAll(staged1080)
                                                pendingBelowFhdLinks.addAll(stagedBelow)
                                                pendingAbove1080Links.addAll(stagedOther)
                                                val ready720 = pendingTop720Links
                                                    .filter { canonicalStreamKey(it) !in emittedKeys }
                                                    .sortedWith(comparator)
                                                    .firstOrNull { cand ->
                                                        val cRank = getSourcePriorityRank(cand)
                                                        cRank >= maxRank || !hasHigherPendingTop720Rank(cRank, cand) || topSourceGraceMs <= 0L || topSourceGraceExpired
                                                    }
                                                if (ready720 != null) {
                                                    pendingTop720Links.remove(ready720)
                                                    emitTopStreamAndAdvance(ready720)
                                                } else {
                                                    scheduleTimersForPendingTopTier()
                                                }
                                            } else {
                                                detachFromBuckets(best)
                                                emitTopStreamAndAdvance(best)
                                            }
                                        } else {
                                            val shouldHoldForHigherInFlight = isRankInFlight != null &&
                                                rank < maxRank &&
                                                hasHigherPendingTop720Rank(rank, best) &&
                                                topSourceGraceMs > 0L &&
                                                !topSourceGraceExpired
                                            if (shouldHoldForHigherInFlight) {
                                                detachFromBuckets(best)
                                                if (is720p(best)) {
                                                    pendingTop720Links.add(best)
                                                } else if (isBelow720p(best) || isUnknownQuality(best)) {
                                                    pendingBelowFhdLinks.add(best)
                                                } else {
                                                    pendingAbove1080Links.add(best)
                                                }
                                                val staged720 = stagedLinks.filter { is720p(it) }
                                                val staged1080 = stagedLinks.filter { is1080p(it) }
                                                val stagedBelow = stagedLinks.filter { isBelow720p(it) || isUnknownQuality(it) }
                                                val stagedOther = stagedLinks.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isUnknownQuality(it) }
                                                stagedLinks.clear()
                                                pendingTop720Links.addAll(staged720)
                                                pending1080Links.addAll(staged1080)
                                                pendingBelowFhdLinks.addAll(stagedBelow)
                                                pendingAbove1080Links.addAll(stagedOther)
                                                scheduleTimersForPendingTopTier()
                                                startTopSourceGraceTimer()
                                            } else {
                                                detachFromBuckets(best)
                                                emitTopStreamAndAdvance(best)
                                            }
                                        }
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
                                val best = bestUnemittedCandidate()
                                if (best != null) {
                                    val bestRank = getSourcePriorityRank(best)
                                    val maxRank = activeTopRanks?.maxOrNull() ?: 100
                                    val shouldHoldBestForHigherInFlight = isRankInFlight != null &&
                                        bestRank < maxRank &&
                                        hasHigherPendingTop720Rank(bestRank, best) &&
                                        isCrossSourceGraceStillActive()
                                    if (shouldHoldBestForHigherInFlight) {
                                        val staged720 = stagedLinks.filter { is720p(it) }
                                        val staged1080 = stagedLinks.filter { is1080p(it) }
                                        val stagedBelow = stagedLinks.filter { isBelow720p(it) || isUnknownQuality(it) }
                                        val stagedOther = stagedLinks.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isUnknownQuality(it) }
                                        stagedLinks.clear()
                                        pendingTop720Links.addAll(staged720)
                                        pending1080Links.addAll(staged1080)
                                        pendingBelowFhdLinks.addAll(stagedBelow)
                                        pendingAbove1080Links.addAll(stagedOther)
                                        scheduleTimersForPendingTopTier()
                                    } else if (is1080p(best) && (
                                        pending1080Links.isNotEmpty() ||
                                        (isRankInFlight != null && shouldHold1080ForRank(bestRank, best)) ||
                                        (isRankInFlight == null && top720TimerJob?.isActive == true)
                                    )) {
                                        detachFromBuckets(best)
                                        pending1080Links.add(best)
                                        val other1080 = stagedLinks.filter { is1080p(it) }
                                        stagedLinks.removeAll(other1080)
                                        pending1080Links.addAll(other1080)
                                        val stagedNonHd = stagedLinks.filter { !is720p(it) }
                                        stagedLinks.removeAll(stagedNonHd)
                                        pendingBelowFhdLinks.addAll(stagedNonHd)
                                        scheduleTimersForPendingTopTier()
                                    } else if ((pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty()) && !is720p(best) && !is1080p(best)) {
                                        val stagedBelow = stagedLinks.filter { isBelow720p(it) || isUnknownQuality(it) }
                                        val stagedOther = stagedLinks.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isUnknownQuality(it) }
                                        stagedLinks.removeAll(stagedBelow + stagedOther)
                                        pendingBelowFhdLinks.addAll(stagedBelow)
                                        pendingAbove1080Links.addAll(stagedOther)
                                        scheduleTimersForPendingTopTier()
                                    } else {
                                        dispatchOrStageBest(best)
                                    }
                                } else if (pending1080Links.isNotEmpty()) {
                                    val stagedNonHd = stagedLinks.filter { !is720p(it) }
                                    stagedLinks.removeAll(stagedNonHd)
                                    pendingBelowFhdLinks.addAll(stagedNonHd)
                                    scheduleTimersForPendingTopTier()
                                } else {
                                    val bestStream = stagedLinks.sortedWith(comparator).firstOrNull()
                                    if (bestStream != null) {
                                        val rank = getSourcePriorityRank(bestStream)
                                        val maxRank = activeTopRanks?.maxOrNull() ?: 100
                                        val hasHigherRankInFlight = rank < maxRank && hasHigherPendingTop720Rank(rank, bestStream) && topSourceGraceMs > 0L && !topSourceGraceExpired
                                        if (hasHigherRankInFlight) {
                                            if (is1080p(bestStream)) {
                                                stagedLinks.remove(bestStream)
                                                pending1080Links.add(bestStream)
                                                scheduleTimersForPendingTopTier()
                                            } else if (isBelow720p(bestStream) || isUnknownQuality(bestStream)) {
                                                stagedLinks.remove(bestStream)
                                                pendingBelowFhdLinks.add(bestStream)
                                                startTopSourceGraceTimer()
                                            } else {
                                                stagedLinks.remove(bestStream)
                                                pendingAbove1080Links.add(bestStream)
                                                startTopSourceGraceTimer()
                                            }
                                        } else {
                                            val shouldHold1080 = is1080p(bestStream) && (
                                                pending1080Links.isNotEmpty() ||
                                                (isRankInFlight != null && shouldHold1080ForRank(rank, bestStream)) ||
                                                (isRankInFlight == null && top720TimerJob?.isActive == true)
                                            )
                                            if (shouldHold1080) {
                                                stagedLinks.remove(bestStream)
                                                pending1080Links.add(bestStream)
                                                val other1080 = stagedLinks.filter { is1080p(it) }
                                                stagedLinks.removeAll(other1080)
                                                pending1080Links.addAll(other1080)
                                                scheduleTimersForPendingTopTier()
                                            } else {
                                                stagedLinks.remove(bestStream)
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
        }

        private fun linkRankIsSecondary(link: ExtractorLink): Boolean {
            return !isTopTierSource(link) && !(is1080p(link) && isRankInFlight != null && activeTopRanks != null && activeTopRanks.none { it >= 85 })
        }

        private fun hasHigherPendingTop720Rank(rank: Int, link: ExtractorLink? = null): Boolean {
            val effectiveIsAnime = isAnime || (link != null && isAnimeStream(link))
            val candidateMult = if (rank >= 85) {
                getPrimaryStreamTierMultiplier(
                    sourceRank = rank,
                    isTopTier = true,
                    is720 = true,
                    is1080 = false,
                    link = link,
                    isAnime = effectiveIsAnime
                )
            } else {
                0
            }
            val hasHigherQueued = (pendingTop720Links + pending1080Links + stagedLinks).any {
                canonicalStreamKey(it) !in emittedKeys && getLinkTierMultiplier(it) > candidateMult
            }
            if (hasHigherQueued) return true

            if (isRankInFlight != null) {
                val ranksToCheck = if (activeTopRanks != null) {
                    (TOP_TIER_RANKS.filter { it in activeTopRanks } + activeTopRanks).distinct()
                } else {
                    val seenRanks = (completedTop720Ranks + completedTop1080Ranks + (pendingTop720Links + pending1080Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                    TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                }
                return ranksToCheck.any { r ->
                    r > rank && !isRankSatisfiedFor720Candidate(r, rank) && isRankInFlight.invoke(r)
                }
            }

            if (activeTopRanks != null) {
                return activeTopRanks.any { r ->
                    r > rank && !isRankSatisfiedFor720Candidate(r, rank)
                }
            }

            // Standalone without activeTopRanks and without isRankInFlight:
            val isGraceActive = (!hasEmittedTopStream && topSourceGraceMs > 0L && !topSourceGraceExpired) ||
                (hasEmittedTopStream && top720GraceMs > 0L && !top720GraceExpired)

            if (isGraceActive) {
                val seenRanks = (completedTop720Ranks + completedTop1080Ranks + (pendingTop720Links + pending1080Links + stagedLinks).map { getSourcePriorityRank(it) }).toSet()
                val ranksToWait = TOP_TIER_RANKS.filter { r -> r in LEGACY_TOP_TIER_RANKS || r in seenRanks }
                return ranksToWait.any { r ->
                    r > rank && !isRankSatisfiedFor720Candidate(r, rank)
                }
            }

            return false
        }

        fun markRankCompleted(rank: Int) {
            synchronized(lock) {
                completedProviderRanks.add(rank)
                completedTop720Ranks.add(rank)
                completedTop1080Ranks.add(rank)
                drainPendingTop720()
                scheduleTimersForPendingTopTier()
            }
        }

        private fun drainPendingTop720() {
            pendingTop720Links.removeAll { canonicalStreamKey(it) in emittedKeys }
            pending1080Links.removeAll { canonicalStreamKey(it) in emittedKeys }
            var emittedAny = true
            while (emittedAny) {
                emittedAny = false
                val nextCandidate = (pendingTop720Links + pending1080Links)
                    .filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(comparator)
                    .firstOrNull() ?: break

                val candRank = getSourcePriorityRank(nextCandidate)
                if (is720p(nextCandidate)) {
                    val higherInFlight = hasHigherPendingTop720Rank(candRank, nextCandidate)
                    val canDrain = if (!hasEmittedTopStream) {
                        !higherInFlight || topSourceGraceExpired
                    } else if (isRankInFlight != null && topSourceGraceMs > top720GraceMs) {
                        !higherInFlight || topSourceGraceExpired
                    } else {
                        !higherInFlight || top720GraceExpired || topSourceGraceExpired
                    }
                    if (canDrain) {
                        pendingTop720Links.remove(nextCandidate)
                        if (hasEmittedTopStream && !isTopTierSource(nextCandidate) && linkRankIsSecondary(nextCandidate) &&
                            (pendingBelowFhdLinks.isNotEmpty() || pendingAbove1080Links.isNotEmpty())
                        ) {
                            pendingSecondaryLinks.add(nextCandidate)
                            emittedAny = true
                        } else {
                            if (!hasEmittedTopStream) {
                                emitTopStreamAndAdvance(nextCandidate)
                            } else {
                                emitSingleLink(nextCandidate)
                                completedTop720Ranks.add(candRank)
                            }
                            emittedAny = true
                        }
                    } else {
                        break
                    }
                } else if (is1080p(nextCandidate)) {
                    val canDrain1080 = !shouldHold1080ForRank(candRank, nextCandidate)
                    if (canDrain1080) {
                        pending1080Links.remove(nextCandidate)
                        if (!hasEmittedTopStream) {
                            emitTopStreamAndAdvance(nextCandidate)
                        } else {
                            emitSingleLink(nextCandidate)
                            completedTop1080Ranks.add(candRank)
                            if (isAnime || candRank < 100 || !hasRemainingTopTierHdOrFhdWork()) {
                                hasEmitted1080p = true
                                fhdTimerJob?.cancel()
                                fhdTimerJob = null
                            }
                        }
                        emittedAny = true
                    } else {
                        break
                    }
                } else {
                    pendingTop720Links.remove(nextCandidate)
                    pending1080Links.remove(nextCandidate)
                    emitSingleLink(nextCandidate)
                    emittedAny = true
                }
            }
            if (!hasRemainingTopTierHdOrFhdWork() && completedTop1080Ranks.isNotEmpty()) {
                hasEmitted1080p = true
                fhdTimerJob?.cancel()
                fhdTimerJob = null
            }
            val canFlush1080Early = if (isRankInFlight != null) {
                val ranksToCheck = if (activeTopRanks != null) {
                    (TOP_TIER_RANKS.filter { it in activeTopRanks } + activeTopRanks).distinct()
                } else {
                    TOP_TIER_RANKS
                }
                ranksToCheck.none { isRankInFlight.invoke(it) }
            } else {
                top720GraceMs <= 0L || top720GraceExpired
            }
            if (pendingTop720Links.isEmpty() && canFlush1080Early) {
                flushPending1080()
            } else if (!hasRemainingTopTierHdOrFhdWork() && (hasEmitted1080p || fhdGraceExpired) && pendingTop720Links.isEmpty() && pending1080Links.isEmpty()) {
                flushPendingBelowFhd()
            }
        }

        private fun startTopSourceGraceTimer() {
            if (topSourceTimerJob == null && topSourceGraceMs > 0L && !topSourceGraceExpired) {
                topSourceTimerJob = scope.launch {
                    delay(topSourceGraceMs)
                    synchronized(lock) {
                        topSourceTimerJob = null
                        topSourceGraceExpired = true
                        if (top720WindowElapsed || topSourceGraceMs >= top720GraceMs) {
                            top720WindowElapsed = true
                        }
                        drainPendingTop720()
                        if (topSourceGraceMs >= top720GraceMs && (pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty())) {
                            flushPending1080()
                        } else {
                            scheduleTimersForPendingTopTier()
                        }
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
                        top720WindowElapsed = true
                        val hasHigherInFlight = isRankInFlight != null &&
                            topSourceGraceMs > top720GraceMs &&
                            !topSourceGraceExpired &&
                            (hasAnyHigherInFlightRankThanBestReady() ||
                                pendingTop720Links.any { hasHigherPendingTop720Rank(getSourcePriorityRank(it), it) } ||
                                pending1080Links.any { hasHigherPendingTop720Rank(getSourcePriorityRank(it), it) })
                        if (hasHigherInFlight) {
                            drainPendingTop720()
                            scheduleTimersForPendingTopTier()
                        } else {
                            flushPending1080()
                        }
                    }
                }
            }
        }

        private fun startFhdGraceTimer() {
            if (hasRemainingTopTierHdOrFhdWork()) {
                scheduleTimersForPendingTopTier()
                return
            }
            if (fhdGraceMs <= 0L) {
                if (pendingTop720Links.isEmpty() && pending1080Links.isEmpty() && !hasRemainingTopTierHdOrFhdWork()) {
                    flushPendingBelowFhd()
                }
                return
            }
            if (fhdTimerJob == null) {
                fhdTimerJob = scope.launch {
                    delay(fhdGraceMs)
                    synchronized(lock) {
                        fhdTimerJob = null
                        if (pendingTop720Links.isEmpty() && pending1080Links.isEmpty() && !hasRemainingTopTierHdOrFhdWork()) {
                            flushPendingBelowFhd()
                        } else if (hasRemainingTopTierHdOrFhdWork()) {
                            scheduleTimersForPendingTopTier()
                        }
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
            if (is720p(topStream)) {
                completedTop720Ranks.add(topRank)
            }
            if (is1080p(topStream)) {
                completedTop1080Ranks.add(topRank)
                if (isAnime || topRank < 100 || !hasRemainingTopTierHdOrFhdWork()) {
                    hasEmitted1080p = true
                }
            }

            // Partition remaining staged links
            val remaining = stagedLinks.filter { canonicalStreamKey(it) !in emittedKeys }
                .sortedWith(comparator)
            stagedLinks.clear()

            val (remainingTopTier, remainingSecondary) = remaining.partition { isTopTierSource(it) }

            val remaining720 = remainingTopTier.filter { is720p(it) }
            val remaining1080 = remainingTopTier.filter { is1080p(it) }
            val remainingBelow720 = remainingTopTier.filter { isBelow720p(it) || isUnknownQuality(it) }
            val remainingAbove1080 = remainingTopTier.filter { isAbove1080p(it) }
            val remainingOther = remainingTopTier.filter { !is720p(it) && !is1080p(it) && !isBelow720p(it) && !isUnknownQuality(it) && !isAbove1080p(it) }

            // #1 & #2: Top-tier 720p and 1080p links drained in strict source-first comparator order
            pendingTop720Links.addAll(remaining720)
            pending1080Links.addAll(remaining1080)
            pendingBelowFhdLinks.addAll(remainingBelow720)
            pendingAbove1080Links.addAll(remainingAbove1080)
            pendingSecondaryLinks.addAll(remainingSecondary)
            pendingAbove1080Links.addAll(remainingOther)

            drainPendingTop720()
            if (pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty() || hasRemainingTopTierHdOrFhdWork()) {
                scheduleTimersForPendingTopTier()
            } else if (hasEmitted1080p || fhdGraceExpired) {
                flushPendingBelowFhd()
            } else {
                startFhdGraceTimer()
            }
        }

        private fun flushPendingTop720() {
            top720GraceExpired = true
            if (pendingTop720Links.isNotEmpty() || pending1080Links.isNotEmpty()) {
                val sorted = (pendingTop720Links + pending1080Links)
                    .filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(comparator)
                pendingTop720Links.clear()
                pending1080Links.clear()
                for (link in sorted) {
                    hasEmittedTopStream = true
                    emitSingleLink(link)
                    val linkRank = getSourcePriorityRank(link)
                    if (is720p(link)) {
                        completedTop720Ranks.add(linkRank)
                    }
                    if (is1080p(link)) {
                        completedTop1080Ranks.add(linkRank)
                        hasEmitted1080p = true
                    }
                }
            }
        }

        private fun flushPending1080() {
            top720GraceExpired = true
            top720TimerJob?.cancel()
            top720TimerJob = null
            flushPendingTop720()

            if (!hasRemainingTopTierHdOrFhdWork() && (hasEmitted1080p || fhdGraceExpired)) {
                flushPendingBelowFhd()
            } else if (hasRemainingTopTierHdOrFhdWork()) {
                scheduleTimersForPendingTopTier()
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
                    .sortedWith(comparator)
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
                    .sortedWith(comparator)
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
                    .sortedWith(comparator)
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
                topSourceTimerJob?.cancel()
                topSourceTimerJob = null
                top720TimerJob?.cancel()
                top720TimerJob = null
                fhdTimerJob?.cancel()
                fhdTimerJob = null
                sdTimerJob?.cancel()
                sdTimerJob = null

                val allRemaining = (stagedLinks + pendingTop720Links + pending1080Links + pendingBelowFhdLinks + pendingAbove1080Links + pendingSecondaryLinks)
                    .filter { canonicalStreamKey(it) !in emittedKeys }
                    .sortedWith(comparator)

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
                topSourceGraceExpired = true
                top720WindowElapsed = true
                top720GraceExpired = true
                fhdGraceExpired = true
                sdGraceExpired = true
            }
        }

        fun hasTopStreamEmitted(): Boolean = hasEmittedTopStream

        companion object {
            /**
             * The v22-era top-tier ranks, kept only for the standalone fallback path of
             * [hasHigherPendingTop720Rank] (no [activeTopRanks] and no [isRankInFlight]):
             * in that mode the dispatcher knows nothing about which v26 registry sources
             * are live, so it waits only for these historic ranks plus any rank it has
             * actually observed in the current session. Every production caller passes
             * [activeTopRanks]/[isRankInFlight], where the full v26 [TOP_TIER_RANKS] set
             * is used against the live provider picture instead.
             */
            private val LEGACY_TOP_TIER_RANKS = listOf(100, 94, 90, 85)

            /**
             * The complete set of top-tier ranks in the curated v26 registry. Kept as an
             * explicit list (rather than derived from the string-keyed boost map) so the
             * dispatcher can reason about "is a higher-ranked source still running?"
             * without having to know any provider names.
             */
            private val TOP_TIER_RANKS = listOf(100, 98, 95, 94, 93, 92, 91, 89, 88, 87)

            fun isUnknownQuality(link: ExtractorLink): Boolean = StreamLinkOptimizer.isUnknownQuality(link)

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
                val q = getEffectiveQuality(link)
                if (StreamLinkOptimizer.isUnknownQuality(link, q)) return false
                return q in 1..699 && !is720p(link)
            }

            fun isAbove1080p(link: ExtractorLink): Boolean {
                val q = getEffectiveQuality(link)
                return q > 1088 && !is1080p(link)
            }
        }
    }
}

/**
 * Backward compatibility alias for StreamPlay existing callers.
 */
typealias StreamPlayLinkOptimizer = StreamLinkOptimizer
