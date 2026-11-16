package com.phisher98.subtitles

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class CatalogHttpResponse(
    val statusCode: Int,
    val bodyBytes: ByteArray,
    val finalUrl: String,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccess: Boolean
        get() = statusCode in 200..299 && bodyBytes.isNotEmpty()

    val textUtf8: String
        get() = SubtitleArchiveDecoder.decodeCharsetToString(bodyBytes)

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is CatalogHttpResponse &&
                statusCode == other.statusCode &&
                finalUrl == other.finalUrl &&
                headers == other.headers &&
                bodyBytes.contentEquals(other.bodyBytes)
            )

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + bodyBytes.contentHashCode()
        result = 31 * result + finalUrl.hashCode()
        result = 31 * result + headers.hashCode()
        return result
    }
}

interface CatalogHttpTransport {
    val supportsExtendedLiveScrapers: Boolean
        get() = false

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 6_000L,
    ): Result<CatalogHttpResponse>

    suspend fun postForm(
        url: String,
        formFields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 6_000L,
    ): Result<CatalogHttpResponse> {
        return Result.failure(UnsupportedOperationException("POST form not implemented for $url"))
    }

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 6_000L,
    ): Result<CatalogHttpResponse> {
        return Result.failure(UnsupportedOperationException("POST JSON not implemented for $url"))
    }

    companion object {
        fun fromGet(
            handler: suspend (url: String, headers: Map<String, String>) -> CatalogHttpResponse,
        ): CatalogHttpTransport = object : CatalogHttpTransport {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Long,
            ): Result<CatalogHttpResponse> = try {
                Result.success(handler(url, headers))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

        fun fromGetAndPostJson(
            extendedLiveScrapers: Boolean = true,
            onGet: suspend (url: String, headers: Map<String, String>) -> CatalogHttpResponse,
            onPostJson: suspend (url: String, jsonBody: String, headers: Map<String, String>) -> CatalogHttpResponse,
        ): CatalogHttpTransport = object : CatalogHttpTransport {
            override val supportsExtendedLiveScrapers: Boolean = extendedLiveScrapers

            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutMs: Long,
            ): Result<CatalogHttpResponse> = try {
                Result.success(onGet(url, headers))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

            override suspend fun postJson(
                url: String,
                jsonBody: String,
                headers: Map<String, String>,
                timeoutMs: Long,
            ): Result<CatalogHttpResponse> = try {
                Result.success(onPostJson(url, jsonBody, headers))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
    }
}

class DefaultCatalogHttpTransport(
    val allowPrivateNetworks: Boolean = false,
) : CatalogHttpTransport {
    override val supportsExtendedLiveScrapers: Boolean = true
    private val jsonMapper = ObjectMapper()

    internal fun validateDestinationUrl(rawUrl: String): String {
        val cleanUrl = rawUrl.trim()
        val uri = try {
            URI(cleanUrl)
        } catch (t: Throwable) {
            throw IllegalArgumentException("Invalid catalog HTTP URL: $cleanUrl", t)
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("Unsupported catalog HTTP URL scheme: $cleanUrl")
        }
        val host = uri.host?.trim()?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing host in catalog HTTP URL: $cleanUrl")

        if (!allowPrivateNetworks) {
            if (host.equals("localhost", ignoreCase = true) ||
                host.endsWith(".localhost", ignoreCase = true) ||
                host.endsWith(".local", ignoreCase = true) ||
                host.endsWith(".internal", ignoreCase = true)
            ) {
                throw SecurityException("Refusing private/local catalog host: $host")
            }
            val resolved = InetAddress.getAllByName(host)
            if (resolved.isEmpty()) {
                throw SecurityException("DNS resolved 0 addresses for host: $host")
            }
            for (addr in resolved) {
                if (isBlockedAddress(addr)) {
                    throw SecurityException("Refusing non-public catalog destination IP for host: $host")
                }
            }
        }
        return cleanUrl
    }

    private fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val raw = addr.address
        if (addr is Inet4Address && raw.size == 4) {
            val b0 = raw[0].toInt() and 0xFF
            val b1 = raw[1].toInt() and 0xFF
            if (b0 == 0 || b0 == 127 || b0 == 10) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 100 && (b1 and 0xC0) == 64) return true
        }
        if (addr is Inet6Address && raw.size == 16) {
            val b0 = raw[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 ULA
            // IPv4-mapped IPv6 (::ffff:a.b.c.d)
            val isMappedV4 = (0..9).all { raw[it].toInt() == 0 } &&
                (raw[10].toInt() and 0xFF) == 0xFF &&
                (raw[11].toInt() and 0xFF) == 0xFF
            if (isMappedV4) {
                val v4Bytes = raw.copyOfRange(12, 16)
                return isBlockedAddress(InetAddress.getByAddress(v4Bytes))
            }
        }
        return false
    }

    private fun resolveRedirectUrl(baseUrl: String, locationHeader: String): String {
        val next = URI(baseUrl).resolve(locationHeader.trim()).toString()
        return validateDestinationUrl(next)
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<CatalogHttpResponse> = withContext(Dispatchers.IO) {
        val initialUrl = try {
            validateDestinationUrl(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }

        val effectiveBudgetMs = timeoutMs.coerceIn(250L, 20_000L)
        val deadlineNs = System.nanoTime() + effectiveBudgetMs * 1_000_000L

        val reqHeaders = LinkedHashMap<String, String>(headers.size + 1).apply {
            put("User-Agent", headers["User-Agent"] ?: DEFAULT_BROWSER_UA)
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) put(k, v)
            }
        }

        try {
            val niceResp = withTimeoutOrNull(effectiveBudgetMs) {
                try {
                    var currentUrl = initialUrl
                    var hops = 0
                    var finalNice: CatalogHttpResponse? = null
                    while (true) {
                        val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                        if (remainingMs < 200L) break
                        val timeoutSec = ((remainingMs + 999L) / 1_000L).coerceAtLeast(1L)
                        val resp = app.get(
                            currentUrl,
                            headers = reqHeaders,
                            timeout = timeoutSec,
                            allowRedirects = false,
                        )
                        if (resp.code in REDIRECT_STATUS_CODES) {
                            val loc = resp.headers["location"] ?: resp.headers["Location"]
                            if (loc.isNullOrBlank() || hops >= MAX_REDIRECTS) {
                                finalNice = CatalogHttpResponse(resp.code, ByteArray(0), currentUrl)
                                break
                            }
                            currentUrl = resolveRedirectUrl(currentUrl, loc)
                            hops++
                            continue
                        }
                        val bytes = resp.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                        val outHeaders = LinkedHashMap<String, String>()
                        for (name in resp.headers.names()) {
                            resp.headers[name]?.let { outHeaders[name] = it }
                        }
                        finalNice = CatalogHttpResponse(
                            statusCode = resp.code,
                            bodyBytes = bytes,
                            finalUrl = resp.url,
                            headers = outHeaders,
                        )
                        break
                    }
                    finalNice
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SecurityException) {
                    throw se
                } catch (_: Throwable) {
                    null
                }
            }
            if (niceResp != null) {
                return@withContext if (niceResp.isSuccess) {
                    Result.success(niceResp)
                } else {
                    Result.failure(IllegalStateException("HTTP ${niceResp.statusCode} for $initialUrl"))
                }
            }

            var currentUrl = initialUrl
            var hops = 0
            while (true) {
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainingMs < 400L) {
                    return@withContext Result.failure(
                        SocketTimeoutException("Catalog GET budget exhausted (${effectiveBudgetMs}ms) for $currentUrl")
                    )
                }

                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                    readTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                    reqHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                try {
                    val code = conn.responseCode
                    if (code in REDIRECT_STATUS_CODES) {
                        val loc = conn.getHeaderField("Location")
                        if (loc.isNullOrBlank() || hops >= MAX_REDIRECTS) {
                            return@withContext Result.failure(
                                IllegalStateException("Too many or empty redirects ($code) for $currentUrl")
                            )
                        }
                        currentUrl = resolveRedirectUrl(currentUrl, loc)
                        hops++
                        continue
                    }
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val bytes = stream?.use { readBoundedStream(it, deadlineNs) } ?: ByteArray(0)
                    val resolvedUrl = conn.url?.toString() ?: currentUrl
                    if (code !in 200..299 || bytes.isEmpty()) {
                        return@withContext Result.failure(IllegalStateException("HTTP $code for $currentUrl"))
                    }
                    return@withContext Result.success(
                        CatalogHttpResponse(statusCode = code, bodyBytes = bytes, finalUrl = resolvedUrl)
                    )
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("Unreachable"))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun postForm(
        url: String,
        formFields: Map<String, String>,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<CatalogHttpResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = try {
            validateDestinationUrl(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }

        val effectiveBudgetMs = timeoutMs.coerceIn(250L, 20_000L)
        val deadlineNs = System.nanoTime() + effectiveBudgetMs * 1_000_000L

        val reqHeaders = LinkedHashMap<String, String>(headers.size + 1).apply {
            put("User-Agent", headers["User-Agent"] ?: DEFAULT_BROWSER_UA)
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true)) put(k, v)
            }
        }
        try {
            val niceResp = withTimeoutOrNull(effectiveBudgetMs) {
                try {
                    val timeoutSec = ((effectiveBudgetMs + 999L) / 1_000L).coerceAtLeast(1L)
                    val resp = app.post(
                        cleanUrl,
                        headers = reqHeaders,
                        data = formFields,
                        timeout = timeoutSec,
                        allowRedirects = false,
                    )
                    if (resp.code in REDIRECT_STATUS_CODES) {
                        val loc = resp.headers["location"] ?: resp.headers["Location"]
                        if (loc.isNullOrBlank()) {
                            return@withTimeoutOrNull CatalogHttpResponse(resp.code, ByteArray(0), cleanUrl)
                        }
                        val nextUrl = resolveRedirectUrl(cleanUrl, loc)
                        val redir = app.get(nextUrl, headers = reqHeaders, timeout = timeoutSec, allowRedirects = false)
                        val bytes = redir.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                        return@withTimeoutOrNull CatalogHttpResponse(redir.code, bytes, redir.url)
                    }
                    val bytes = resp.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                    CatalogHttpResponse(
                        statusCode = resp.code,
                        bodyBytes = bytes,
                        finalUrl = resp.url,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SecurityException) {
                    throw se
                } catch (_: Throwable) {
                    null
                }
            }
            if (niceResp != null) {
                return@withContext if (niceResp.isSuccess) {
                    Result.success(niceResp)
                } else {
                    Result.failure(IllegalStateException("HTTP ${niceResp.statusCode} POST $cleanUrl"))
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs < 400L) {
                return@withContext Result.failure(
                    SocketTimeoutException("Catalog POST budget exhausted (${effectiveBudgetMs}ms) for $cleanUrl")
                )
            }

            val formBody = formFields.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }.toByteArray(StandardCharsets.UTF_8)
            val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                readTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                reqHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { it.write(formBody) }
                val code = conn.responseCode
                val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { readBoundedStream(it, deadlineNs) } ?: ByteArray(0)
                if (code !in 200..299 || bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("HTTP $code POST $cleanUrl"))
                }
                Result.success(CatalogHttpResponse(statusCode = code, bodyBytes = bytes, finalUrl = conn.url?.toString() ?: cleanUrl))
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<CatalogHttpResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = try {
            validateDestinationUrl(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return@withContext Result.failure(t)
        }

        val effectiveBudgetMs = timeoutMs.coerceIn(250L, 20_000L)
        val deadlineNs = System.nanoTime() + effectiveBudgetMs * 1_000_000L

        val reqHeaders = LinkedHashMap<String, String>(headers.size + 2).apply {
            put("User-Agent", headers["User-Agent"] ?: DEFAULT_BROWSER_UA)
            put("Content-Type", headers["Content-Type"] ?: "application/json")
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", ignoreCase = true) &&
                    !k.equals("Content-Type", ignoreCase = true)
                ) {
                    put(k, v)
                }
            }
        }
        try {
            val niceResp = withTimeoutOrNull(effectiveBudgetMs) {
                try {
                    val timeoutSec = ((effectiveBudgetMs + 999L) / 1_000L).coerceAtLeast(1L)
                    val bodyMap = jsonMapper.readValue(
                        jsonBody,
                        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {},
                    )
                    val resp = app.post(
                        cleanUrl,
                        headers = reqHeaders,
                        json = bodyMap,
                        timeout = timeoutSec,
                        allowRedirects = false,
                    )
                    val bytes = resp.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                    CatalogHttpResponse(
                        statusCode = resp.code,
                        bodyBytes = bytes,
                        finalUrl = resp.url,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (se: SecurityException) {
                    throw se
                } catch (_: Throwable) {
                    null
                }
            }
            if (niceResp != null) {
                return@withContext if (niceResp.isSuccess) {
                    Result.success(niceResp)
                } else {
                    Result.failure(IllegalStateException("HTTP ${niceResp.statusCode} POST JSON $cleanUrl"))
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs < 400L) {
                return@withContext Result.failure(
                    SocketTimeoutException("Catalog POST JSON budget exhausted (${effectiveBudgetMs}ms) for $cleanUrl")
                )
            }

            val payload = jsonBody.toByteArray(StandardCharsets.UTF_8)
            val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                readTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                reqHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { it.write(payload) }
                val code = conn.responseCode
                val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { readBoundedStream(it, deadlineNs) } ?: ByteArray(0)
                if (code !in 200..299 || bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("HTTP $code POST JSON $cleanUrl"))
                }
                Result.success(CatalogHttpResponse(statusCode = code, bodyBytes = bytes, finalUrl = conn.url?.toString() ?: cleanUrl))
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun readBoundedStream(stream: InputStream, deadlineNs: Long): ByteArray {
        val out = ByteArrayOutputStream(32 * 1024)
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            if (System.nanoTime() > deadlineNs) {
                throw SocketTimeoutException("Catalog HTTP stream read exceeded deadline")
            }
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > SubtitleArchiveDecoder.MAX_DECODED_BYTES) {
                throw IllegalArgumentException("Catalog HTTP payload exceeds ${SubtitleArchiveDecoder.MAX_DECODED_BYTES} bytes")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private const val MAX_REDIRECTS = 5
    }
}

data class CatalogApiKeys(
    val wyzieApiKey: String? = resolveFirstNonBlank(
        System.getProperty("cloudstream.wyzie.key"),
        System.getProperty("wyzie.api.key"),
        System.getenv("WYZIE_API_KEY"),
    ),
    val subDlApiKey: String? = resolveFirstNonBlank(
        System.getProperty("cloudstream.subdl.key"),
        System.getProperty("subdl.api.key"),
        System.getenv("SUBDL_API_KEY"),
    ),
    val subSourceApiKey: String? = resolveFirstNonBlank(
        System.getProperty("cloudstream.subsource.key"),
        System.getProperty("subsource.api.key"),
        System.getenv("SUBSOURCE_API_KEY"),
    ),
) {
    companion object {
        private fun resolveFirstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}

data class CatalogEndpoints(
    val subDlApiV1Url: String = "https://api.subdl.com/api/v1/subtitles",
    val subDlAutoUrl: String = "https://api3.subdl.com/auto",
    val subDlWebHost: String = "https://subdl.com",
    val subDlDownloadHost: String = "https://dl.subdl.com",
    val openSubtitlesStremioHost: String = "https://opensubtitles-v3.strem.io",
    val subtitleApiHost: String = "https://opensubtitles-v3.strem.io",
    val openSubtitlesRestHost: String = "https://rest.opensubtitles.org",
    val wyziePrimaryHost: String = "https://sub.wyzie.io",
    val wyzieFallbackHost: String = "https://sub.wyzie.ru",
    val subSourceApiV1Host: String = "https://api.subsource.net/api/v1",
    val subSourceOriginHost: String = "https://api.subsource.net",
    val subSourceWebHost: String = "https://subsource.net",
    val subSenseBaseUrl: String = DEFAULT_SUBSENSE_URDU_BASE_URL,
    val subf2mHost: String = "https://subf2m.co",
    val ytsSubsHost: String = "https://www.yts-subs.com",
    val ytsSubsZipHost: String = "https://subtitles.yts-subs.com",
    val yifyHost: String = "https://yifysubtitles.ch",
    val addic7edHost: String = "https://www.addic7ed.com",
    val gestdownHost: String = DEFAULT_GESTDOWN_HOST,
    val subtitleCatHost: String = "https://www.subtitlecat.com",
) {
    companion object {
        const val DEFAULT_SUBSENSE_URDU_BASE_URL =
            "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles"
        const val DEFAULT_SUBSENSE_BILINGUAL_BASE_URL =
            "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOlsidXIiLCJlbiJdLCJtYXhTdWJ0aXRsZXMiOjI1fQ/subtitles"
        const val DEFAULT_SUBSENSE_ENGLISH_BASE_URL =
            "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOlsiZW4iXSwibWF4U3VidGl0bGVzIjoyNX0/subtitles"
        const val DEFAULT_GESTDOWN_HOST = "https://api.gestdown.info"
    }
}

data class CatalogDiscoveredCandidate(
    val meta: CatalogueCandidateMeta,
    val downloadUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val preDownloadScore: Int,
    val isSubtitleCatOrigDonor: Boolean = false,
    val preferredZipEntryHint: String? = null,
) {
    val catalogue: CatalogueSourceKey get() = meta.catalogue
    val language: SubtitleLanguage get() = meta.language
}

data class CatalogDiscoveryResult(
    val admittedCandidates: List<CatalogDiscoveredCandidate>,
    val rejectedPreDownload: List<VerifiedSubtitleCandidate.Rejected>,
) {
    fun candidatesFor(language: SubtitleLanguage): List<CatalogDiscoveredCandidate> =
        admittedCandidates.filter { it.language == language }
}

class SubtitleCatalogSources(
    private val transport: CatalogHttpTransport = DefaultCatalogHttpTransport(),
    private val apiKeys: CatalogApiKeys = CatalogApiKeys(),
    private val endpoints: CatalogEndpoints = CatalogEndpoints(),
) {
    private val mapper = ObjectMapper()

    private val subDlSlugMemo = ConcurrentHashMap<String, String>()
    private val subf2mSlugMemo = ConcurrentHashMap<String, String>()

    suspend fun discoverCandidates(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage> = setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH),
        enabledSources: Set<CatalogueSourceKey> = DEFAULT_ACTIVE_SOURCES,
    ): CatalogDiscoveryResult = discoverCandidatesStreaming(
        spec = spec,
        languages = languages,
        enabledSources = enabledSources,
        onBatchDiscovered = null,
    )

    suspend fun discoverCandidatesStreaming(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage> = setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH),
        enabledSources: Set<CatalogueSourceKey> = DEFAULT_ACTIVE_SOURCES,
        onBatchDiscovered: (suspend (CatalogueSourceKey, List<CatalogDiscoveredCandidate>) -> Unit)? = null,
    ): CatalogDiscoveryResult = coroutineScope {
        if (languages.isEmpty() || enabledSources.isEmpty()) {
            return@coroutineScope CatalogDiscoveryResult(emptyList(), emptyList())
        }

        val rejections = Collections.synchronizedList(ArrayList<VerifiedSubtitleCandidate.Rejected>())
        val allAdmitted = Collections.synchronizedList(ArrayList<CatalogDiscoveredCandidate>())
        val emittedStreamingKeys = ConcurrentHashMap.newKeySet<String>()

        suspend fun runSourceScraper(
            sourceKey: CatalogueSourceKey,
            block: suspend (MutableList<CatalogDiscoveredCandidate>) -> Unit,
        ) {
            val localAdmitted = Collections.synchronizedList(ArrayList<CatalogDiscoveredCandidate>())
            try {
                block(localAdmitted)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Individual catalogue failures never abort sibling scrapers.
            }
            if (localAdmitted.isNotEmpty()) {
                allAdmitted.addAll(localAdmitted)
                if (onBatchDiscovered != null) {
                    val freshSorted = finalizeDiscovery(localAdmitted, emptyList()).admittedCandidates
                        .filter { emittedStreamingKeys.add("${it.language.ietfTag}|${it.downloadUrl}") }
                    if (freshSorted.isNotEmpty()) {
                        onBatchDiscovered(sourceKey, freshSorted)
                    }
                }
            }
        }

        if (spec is MediaLookupSpec.Episode && spec.coordinate is EpisodeCoordinate.UnverifiedAnimeCour) {
            val courTasks = buildList {
                if (CatalogueSourceKey.SUBTITLECAT in enabledSources) {
                    add(async {
                        runSourceScraper(CatalogueSourceKey.SUBTITLECAT) { local ->
                            querySubtitleCat(spec, languages, local, rejections)
                        }
                    })
                }
                if (CatalogueSourceKey.SUBF2M in enabledSources &&
                    (transport.supportsExtendedLiveScrapers || enabledSources == setOf(CatalogueSourceKey.SUBF2M))
                ) {
                    add(async {
                        runSourceScraper(CatalogueSourceKey.SUBF2M) { local ->
                            querySubf2mAnimeCour(spec, spec.coordinate, languages, local, rejections)
                        }
                    })
                }
            }
            courTasks.awaitAll()
            return@coroutineScope finalizeDiscovery(allAdmitted, rejections)
        }

        val tasks = buildList {
            if (CatalogueSourceKey.WYZIE in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.WYZIE) { local ->
                        queryWyzie(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.SUBDL in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.SUBDL) { local ->
                        querySubDl(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.SUBSOURCE in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.SUBSOURCE) { local ->
                        querySubSource(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.SUBSENSE in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.SUBSENSE) { local ->
                        querySubSense(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.SUBF2M in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.SUBF2M) { local ->
                        querySubf2m(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.OPENSUBTITLES in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.OPENSUBTITLES) { local ->
                        queryOpenSubtitlesLegacyRest(spec, languages, local, rejections)
                    }
                })
            }
            val wantsStremioV3 = CatalogueSourceKey.STREMIO in enabledSources
            val wantsSubtitleApi = CatalogueSourceKey.SUBTITLE_API in enabledSources
            if (wantsStremioV3 || wantsSubtitleApi) {
                if (wantsStremioV3 && wantsSubtitleApi &&
                    endpoints.openSubtitlesStremioHost.trimEnd('/') == endpoints.subtitleApiHost.trimEnd('/')
                ) {
                    add(async {
                        runSourceScraper(CatalogueSourceKey.SUBTITLE_API) { local ->
                            queryStremioCompatibleIndex(
                                baseHost = endpoints.subtitleApiHost,
                                sourceKey = CatalogueSourceKey.SUBTITLE_API,
                                spec = spec,
                                languages = languages,
                                admitted = local,
                                rejections = rejections,
                            )
                        }
                    })
                } else {
                    if (wantsSubtitleApi) {
                        add(async {
                            runSourceScraper(CatalogueSourceKey.SUBTITLE_API) { local ->
                                queryStremioCompatibleIndex(
                                    baseHost = endpoints.subtitleApiHost,
                                    sourceKey = CatalogueSourceKey.SUBTITLE_API,
                                    spec = spec,
                                    languages = languages,
                                    admitted = local,
                                    rejections = rejections,
                                )
                            }
                        })
                    }
                    if (wantsStremioV3) {
                        add(async {
                            runSourceScraper(CatalogueSourceKey.STREMIO) { local ->
                                queryStremioCompatibleIndex(
                                    baseHost = endpoints.openSubtitlesStremioHost,
                                    sourceKey = CatalogueSourceKey.STREMIO,
                                    spec = spec,
                                    languages = languages,
                                    admitted = local,
                                    rejections = rejections,
                                )
                            }
                        })
                    }
                }
            }
            if (CatalogueSourceKey.YIFY in enabledSources && spec is MediaLookupSpec.Movie) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.YIFY) { local ->
                        queryYifyAndYts(spec, languages, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.ADDIC7ED in enabledSources &&
                SubtitleLanguage.ENGLISH in languages &&
                spec is MediaLookupSpec.Episode &&
                spec.coordinate is EpisodeCoordinate.VerifiedSeasonEpisode
            ) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.ADDIC7ED) { local ->
                        queryAddic7ed(spec, spec.coordinate, local, rejections)
                    }
                })
            }
            if (CatalogueSourceKey.SUBTITLECAT in enabledSources) {
                add(async {
                    runSourceScraper(CatalogueSourceKey.SUBTITLECAT) { local ->
                        querySubtitleCat(spec, languages, local, rejections)
                    }
                })
            }
        }
        tasks.awaitAll()
        finalizeDiscovery(allAdmitted, rejections)
    }

    suspend fun downloadPayload(
        candidate: CatalogDiscoveredCandidate,
        timeoutMs: Long = 8_000L,
    ): Result<ByteArray> =
        transport.get(candidate.downloadUrl, candidate.requestHeaders, timeoutMs).map { it.bodyBytes }

    private fun finalizeDiscovery(
        rawAdmitted: List<CatalogDiscoveredCandidate>,
        rejections: List<VerifiedSubtitleCandidate.Rejected>,
    ): CatalogDiscoveryResult {
        val dedupedByUrlAndLang = LinkedHashMap<String, CatalogDiscoveredCandidate>(rawAdmitted.size)
        val sorted = rawAdmitted.sortedWith(
            compareByDescending<CatalogDiscoveredCandidate> { !it.isSubtitleCatOrigDonor }
                .thenByDescending { it.preDownloadScore }
                .thenByDescending { it.catalogue.intraCatalogueRank }
        )
        for (cand in sorted) {
            val key = "${cand.language.ietfTag}|${cand.downloadUrl}"
            val existing = dedupedByUrlAndLang[key]
            if (existing == null || cand.preDownloadScore > existing.preDownloadScore) {
                dedupedByUrlAndLang[key] = cand
            }
        }
        return CatalogDiscoveryResult(
            admittedCandidates = dedupedByUrlAndLang.values.toList(),
            rejectedPreDownload = rejections.toList(),
        )
    }

    private fun tryAdmitPreDownload(
        meta: CatalogueCandidateMeta,
        spec: MediaLookupSpec,
        downloadUrl: String,
        requestHeaders: Map<String, String>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
        isSubtitleCatOrigDonor: Boolean = false,
        preferredZipEntryHint: String? = null,
    ): Boolean {
        val cleanUrl = downloadUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        val gateResult = CatalogueCandidateGate.evaluatePreDownload(meta, spec)
        val baseScore = gateResult.getOrElse { err ->
            val rej = (err as? CandidateGateRejectedException)?.rejection
                ?: VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader(err.message ?: "gate_rejected")
            rejections.add(rej)
            return false
        }
        val authorBonus = if (meta.catalogue == CatalogueSourceKey.SUBDL && meta.language == SubtitleLanguage.URDU) {
            val uploaderLower = "${meta.uploaderName.orEmpty()} ${meta.releaseComment.orEmpty()}".lowercase(Locale.ROOT)
            if (SUBDL_VERIFIED_HUMAN_AUTHORS.any { uploaderLower.contains(it) }) 25 else 0
        } else {
            0
        }
        val effectiveScore = baseScore + authorBonus - if (isSubtitleCatOrigDonor) 30 else 0
        admitted.add(
            CatalogDiscoveredCandidate(
                meta = meta,
                downloadUrl = cleanUrl,
                requestHeaders = requestHeaders,
                preDownloadScore = effectiveScore,
                isSubtitleCatOrigDonor = isSubtitleCatOrigDonor,
                preferredZipEntryHint = preferredZipEntryHint,
            )
        )
        return true
    }

    // TV catalogues index series slugs by premiere year rather than Season 2+ air year.
    internal fun catalogSlugYearFor(spec: MediaLookupSpec): Int? = when (spec) {
        is MediaLookupSpec.Movie -> spec.releaseYear
        is MediaLookupSpec.Episode -> spec.seriesPremiereYear ?: spec.seasonAirYear
    }

    private fun verifiedSeasonEpisodeOrNull(spec: MediaLookupSpec): Pair<Int, Int>? {
        if (spec !is MediaLookupSpec.Episode) return null
        val coord = spec.coordinate as? EpisodeCoordinate.VerifiedSeasonEpisode ?: return null
        return coord.season to coord.episode
    }

    private fun cleanImdbOrNull(raw: String?): String? =
        raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { IMDB_ID_REGEX.matches(it) }


    private suspend fun queryWyzie(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val key = apiKeys.wyzieApiKey?.takeIf { it.isNotBlank() }
        if (key == null && !transport.supportsExtendedLiveScrapers) return
        val lookupId = cleanImdbOrNull(spec.imdbId) ?: spec.tmdbId?.takeIf { it > 0 }?.toString() ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return

        val baseParams = if (se != null && se.first >= 0) {
            "id=${urlEncode(lookupId)}&season=${se.first}&episode=${se.second}"
        } else {
            "id=${urlEncode(lookupId)}"
        }
        val suffix = if (key != null) "&source=all&key=${urlEncode(key)}" else "&source=all"
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA, "Accept" to "application/json")

        for (lang in languages) {
            val langParam = if (lang == SubtitleLanguage.URDU) "&language=ur&sort=downloads" else "&language=en"
            val path = "/search?$baseParams$langParam$suffix"
            val resp = transport.get("${endpoints.wyziePrimaryHost.trimEnd('/')}$path", headers)
                .recoverCatching {
                    transport.get("${endpoints.wyzieFallbackHost.trimEnd('/')}$path", headers).getOrThrow()
                }.getOrNull() ?: continue

            val root = runCatching { mapper.readTree(resp.textUtf8) }.getOrNull() ?: continue
            val items = when {
                root.isArray -> root
                root.path("subtitles").isArray -> root.path("subtitles")
                root.path("data").isArray -> root.path("data")
                else -> continue
            }
            var countForLang = 0
            for (node in items) {
                val subUrl = node.firstNonBlank("url", "downloadUrl", "link") ?: continue
                val detectedLang = SubtitleLanguage.fromTagOrName(node.firstNonBlank("language", "lang"))
                    ?: SubtitleLanguage.fromTagOrName(node.firstNonBlank("display", "languageName"))
                    ?: lang
                if (detectedLang != lang) continue

                val hiFlag = node.path("isHearingImpaired").asBoolean(false) ||
                    node.path("hearingImpaired").asBoolean(false) ||
                    node.path("hi").asBoolean(false)
                val mtFlag = node.path("aiTranslated").asBoolean(false) ||
                    node.path("machineTranslated").asBoolean(false)
                val releaseText = listOfNotNull(
                    node.firstNonBlank("release", "releaseName", "fileName", "filename"),
                    node.firstNonBlank("display", "title"),
                ).joinToString(" ").ifBlank { "wyzie_${lang.ietfTag}" }

                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.WYZIE,
                    language = lang,
                    titleOrReleaseText = releaseText,
                    uploaderName = node.firstNonBlank("author", "uploader", "user"),
                    isHearingImpairedFlag = hiFlag,
                    isMachineTranslatedFlag = mtFlag,
                    declaredFps = node.path("fps").asDouble(0.0).takeIf { it > 0.0 },
                    downloadCount = node.path("downloads").asInt(0),
                    ratingScore = node.path("rating").asDouble(0.0),
                    releaseComment = node.firstNonBlank("comment", "source"),
                )
                val dlHeaders = headers + ProvenanceCodec.encodeHeaders(
                    ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.WYZIE)
                )
                if (tryAdmitPreDownload(meta, spec, subUrl, dlHeaders, admitted, rejections)) {
                    countForLang++
                    if (countForLang >= MAX_CANDIDATES_PER_SOURCE_LANG) break
                }
            }
        }
    }


    private suspend fun querySubDl(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return
        val slugYear = catalogSlugYearFor(spec)
        val cleanImdb = cleanImdbOrNull(spec.imdbId)
        val primaryTitle = spec.titles.firstOrNull { it.isNotBlank() }?.trim()
        if (cleanImdb == null && primaryTitle == null) return

        val subDlKey = apiKeys.subDlApiKey?.takeIf { it.isNotBlank() }
        var urduAdmittedFromApi = 0
        var englishAdmittedFromApi = 0

        if (subDlKey != null) {
            val langCodes = languages.joinToString(",") { if (it == SubtitleLanguage.URDU) "UR" else "EN" }
            val mediaType = if (se != null && se.first >= 0) "tv" else "movie"
            val apiUrl = buildString {
                append(endpoints.subDlApiV1Url)
                append("?api_key=").append(urlEncode(subDlKey))
                append("&languages=").append(langCodes)
                append("&subs_per_page=12&unpack=1&type=").append(mediaType)
                if (cleanImdb != null) {
                    append("&imdb_id=").append(urlEncode(cleanImdb))
                } else if (primaryTitle != null) {
                    append("&film_name=").append(urlEncode(primaryTitle))
                }
                if (se != null && se.first >= 0) {
                    append("&season_number=").append(se.first)
                    append("&episode_number=").append(se.second)
                }
                if (slugYear != null && slugYear > 0) {
                    append("&year=").append(slugYear)
                }
            }
            val apiHeaders = mapOf(
                "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
                "Accept" to "application/json",
            )
            val resp = transport.get(apiUrl, apiHeaders).getOrNull()
            if (resp != null) {
                val parsedCounts = parseAndGateSubDlApiV1(resp.textUtf8, spec, languages, se, admitted, rejections)
                urduAdmittedFromApi += parsedCounts.first
                englishAdmittedFromApi += parsedCounts.second
            }
        }

        val remainingLangs = buildSet {
            if (SubtitleLanguage.URDU in languages && urduAdmittedFromApi == 0) add(SubtitleLanguage.URDU)
            if (SubtitleLanguage.ENGLISH in languages && englishAdmittedFromApi == 0) add(SubtitleLanguage.ENGLISH)
        }
        if (remainingLangs.isEmpty()) return

        val slugPath = resolveSubDlSlug(cleanImdb, primaryTitle, slugYear) ?: return
        val seasonSegment = if (se != null && se.first > 0) {
            val ord = subDlSeasonOrdinal(se.first) ?: return
            "/$ord"
        } else {
            ""
        }

        val webHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Origin" to endpoints.subDlWebHost,
            "Referer" to "${endpoints.subDlWebHost.trimEnd('/')}/",
        )
        var combinedPageHtml: String? = null
        var combinedPageFetched = false
        for (lang in remainingLangs) {
            val beforeCount = admitted.count { it.language == lang && it.catalogue == CatalogueSourceKey.SUBDL }
            val langPath = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val pageUrl = "${endpoints.subDlWebHost.trimEnd('/')}$slugPath$seasonSegment/$langPath"
            val pageResp = transport.get(pageUrl, webHeaders).getOrNull()
            if (pageResp != null) {
                parseAndGateSubDlHtmlPage(
                    htmlBody = pageResp.textUtf8,
                    spec = spec,
                    language = lang,
                    se = se,
                    admitted = admitted,
                    rejections = rejections,
                    isCombinedLanguagePage = false,
                )
            }
            val afterLangPage = admitted.count { it.language == lang && it.catalogue == CatalogueSourceKey.SUBDL }
            if (afterLangPage == beforeCount && transport.supportsExtendedLiveScrapers) {
                if (!combinedPageFetched) {
                    combinedPageFetched = true
                    val combinedUrl = "${endpoints.subDlWebHost.trimEnd('/')}$slugPath$seasonSegment"
                    combinedPageHtml = transport.get(combinedUrl, webHeaders).getOrNull()?.textUtf8
                }
                if (!combinedPageHtml.isNullOrBlank()) {
                    parseAndGateSubDlHtmlPage(
                        htmlBody = combinedPageHtml!!,
                        spec = spec,
                        language = lang,
                        se = se,
                        admitted = admitted,
                        rejections = rejections,
                        isCombinedLanguagePage = true,
                    )
                }
            }
        }
    }

    private suspend fun resolveSubDlSlug(
        cleanImdb: String?,
        primaryTitle: String?,
        expectedSlugYear: Int?,
    ): String? {
        val memoKey = "${cleanImdb.orEmpty()}|${primaryTitle?.lowercase(Locale.ROOT).orEmpty()}|${expectedSlugYear ?: 0}"
        subDlSlugMemo[memoKey]?.let { cached ->
            return cached.takeUnless { it == NEGATIVE_SENTINEL }
        }
        val headers = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to endpoints.subDlWebHost,
            "Referer" to "${endpoints.subDlWebHost.trimEnd('/')}/",
        )
        val queries = listOfNotNull(cleanImdb, primaryTitle).distinct()
        var anyResponse = false
        for (q in queries) {
            val autoUrl = "${endpoints.subDlAutoUrl}?query=${urlEncode(q)}"
            val resp = transport.get(autoUrl, headers).getOrNull() ?: continue
            anyResponse = true
            val slug = parseSubDlAutoSlugJson(resp.textUtf8, expectedSlugYear)
            if (slug != null) {
                subDlSlugMemo[memoKey] = slug
                return slug
            }
        }
        if (anyResponse) {
            subDlSlugMemo[memoKey] = NEGATIVE_SENTINEL
        }
        return null
    }

    internal fun parseSubDlAutoSlugJson(jsonBody: String, expectedYear: Int?): String? {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return null
        val results = root.path("results")
        if (!results.isArray || results.size() == 0) return null
        var firstValid: String? = null
        for (item in results) {
            val link = item.path("link").asText("").trim()
            if (!link.startsWith("/subtitle/sd")) continue
            if (firstValid == null) firstValid = link.removeSuffix("/")
            val year = item.path("year").asInt(0)
            if (expectedYear != null && year > 0 && abs(year - expectedYear) <= 1) {
                return link.removeSuffix("/")
            }
        }
        return if (expectedYear != null) null else firstValid
    }

    private fun parseAndGateSubDlApiV1(
        jsonBody: String,
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        se: Pair<Int, Int>?,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ): Pair<Int, Int> {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return 0 to 0
        if (root.has("status") && !root.path("status").asBoolean(true)) return 0 to 0
        val arr = when {
            root.path("subtitles").isArray -> root.path("subtitles")
            root.path("data").isArray -> root.path("data")
            else -> return 0 to 0
        }
        var urCount = 0
        var enCount = 0
        val dlHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Referer" to "${endpoints.subDlWebHost.trimEnd('/')}/",
        ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))

        for (item in arr) {
            val lang = SubtitleLanguage.fromTagOrName(item.firstNonBlank("lang", "language")) ?: continue
            if (lang !in languages) continue
            if (lang == SubtitleLanguage.URDU && urCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue
            if (lang == SubtitleLanguage.ENGLISH && enCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue

            val itemSeason = item.path("season").asInt(-1)
            val itemEpisode = item.path("episode").asInt(-1)
            val releaseName = listOfNotNull(
                item.firstNonBlank("release_name", "name", "title"),
            ).joinToString(" ").trim()

            if (se != null && se.first >= 0) {
                if (itemSeason >= 0 && itemSeason != se.first) continue
                if (itemEpisode > 0 && itemEpisode != se.second && !explicitEpisodeOrPackMatches(releaseName, se.first, se.second)) {
                    continue
                }
                if (itemEpisode <= 0 && releaseName.isNotBlank() && !episodeTextOrPackMatches(releaseName, se.first, se.second)) {
                    continue
                }
            }

            val rawUrl = item.firstNonBlank("url", "download_url") ?: continue
            val fullUrl = resolveRelativeUrl(endpoints.subDlDownloadHost, rawUrl)
            val author = item.firstNonBlank("author", "uploader", "username")
            val comment = item.firstNonBlank("comment")
            val hi = item.path("hi").asBoolean(false) || item.path("hearing_impaired").asBoolean(false)
            val mt = item.path("ai_translated").asBoolean(false) ||
                item.path("machine_translated").asBoolean(false) ||
                item.path("auto_translated").asBoolean(false) ||
                SUBDL_AI_MARKER_REGEX.containsMatchIn("$author $comment $releaseName")

            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBDL,
                language = lang,
                titleOrReleaseText = releaseName.ifBlank { fullUrl.substringAfterLast('/') },
                uploaderName = author,
                isHearingImpairedFlag = hi,
                isMachineTranslatedFlag = mt,
                declaredFps = item.path("fps").asDouble(0.0).takeIf { it > 0.0 },
                releaseComment = comment,
            )
            val epHint = se?.let { "S%02dE%02d".format(Locale.ROOT, it.first, it.second) }
            if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                if (lang == SubtitleLanguage.URDU) urCount++ else enCount++
            }
        }
        return urCount to enCount
    }

    internal fun parseAndGateSubDlHtmlPage(
        htmlBody: String,
        spec: MediaLookupSpec,
        language: SubtitleLanguage,
        se: Pair<Int, Int>?,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
        isCombinedLanguagePage: Boolean = false,
    ) {
        if (htmlBody.isBlank()) return
        val doc = Jsoup.parse(htmlBody)
        val dlHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Referer" to "${endpoints.subDlWebHost.trimEnd('/')}/",
        ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))
        val epHint = se?.let { "S%02dE%02d".format(Locale.ROOT, it.first, it.second) }
        val seenUrlsInPage = HashSet<String>()

        var admittedCount = 0
        val nextDataRaw = doc.selectFirst("script#__NEXT_DATA__")?.data()?.trim().orEmpty()
        if (nextDataRaw.isNotEmpty()) {
            val grouped = runCatching {
                mapper.readTree(nextDataRaw).path("props").path("pageProps").path("groupedSubtitles")
            }.getOrNull()
            if (grouped != null && grouped.isObject) {
                val fields = grouped.fields()
                while (fields.hasNext() && admittedCount < MAX_CANDIDATES_PER_SOURCE_LANG) {
                    val (groupKey, itemsNode) = fields.next()
                    if (SubtitleLanguage.fromTagOrName(groupKey) != language || !itemsNode.isArray) continue
                    for (item in itemsNode) {
                        if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) break
                        val rawLink = item.firstNonBlank("link", "url", "download_url") ?: continue
                        val zipMatch = SUBDL_ZIP_PATH_REGEX.find(rawLink)
                        val fullUrl = resolveRelativeUrl(endpoints.subDlDownloadHost, zipMatch?.value ?: rawLink)
                        if (!seenUrlsInPage.add(fullUrl)) continue
                        val releaseText = item.firstNonBlank("title", "release_name", "name").orEmpty().trim()
                        if (se != null && releaseText.isNotBlank() && !episodeTextOrPackMatches(releaseText, se.first, se.second)) {
                            continue
                        }
                        val author = item.firstNonBlank("author", "uploader", "username")
                        val comment = item.firstNonBlank("comment")
                        val hi = item.path("hi").asBoolean(false) || item.path("hearing_impaired").asBoolean(false)
                        val mt = item.path("ai_translated").asBoolean(false) ||
                            item.path("machine_translated").asBoolean(false) ||
                            item.path("auto_translated").asBoolean(false) ||
                            SUBDL_AI_MARKER_REGEX.containsMatchIn("$author $comment $releaseText")
                        val meta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.SUBDL,
                            language = language,
                            titleOrReleaseText = releaseText.ifBlank { fullUrl.substringAfterLast('/') },
                            uploaderName = author,
                            isHearingImpairedFlag = hi,
                            isMachineTranslatedFlag = mt,
                            releaseComment = comment,
                        )
                        if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                            admittedCount++
                        }
                    }
                }
            }
        }
        if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return

        // Astro SSR <li data-row> parser (live SubDL format)
        val astroRows = doc.select("li[data-row]")
        if (astroRows.isNotEmpty()) {
            for (row in astroRows) {
                if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return
                val detectedRowLang = inferSubDlAstroRowLanguage(row)
                if (detectedRowLang != null && detectedRowLang != language) continue
                if (isCombinedLanguagePage && detectedRowLang != language) continue

                val anchor = row.select("a[href]").firstOrNull { SUBDL_ZIP_PATH_REGEX.containsMatchIn(it.attr("href")) }
                    ?: continue
                val zipMatch = SUBDL_ZIP_PATH_REGEX.find(anchor.attr("href").trim()) ?: continue
                val fullUrl = resolveRelativeUrl(endpoints.subDlDownloadHost, zipMatch.value)
                if (!seenUrlsInPage.add(fullUrl)) continue

                val dataTitle = row.selectFirst("a[data-subtitle-title], [data-subtitle-title]")
                    ?.attr("data-subtitle-title")?.trim().orEmpty()
                val rowText = dataTitle.ifBlank { row.text().trim() }
                if (se != null && !episodeTextOrPackMatches(rowText, se.first, se.second)) continue

                val author = row.select("a[data-subtitle-author], [data-subtitle-author], a[href*=/u/], .author")
                    .joinToString(" ") { "${it.attr("data-subtitle-author")} ${it.text()}".trim() }
                    .trim()
                    .takeIf { it.isNotEmpty() }
                val hiAttr = row.attr("data-hi").trim()
                val hi = hiAttr == "1" || hiAttr.equals("true", ignoreCase = true) ||
                    row.selectFirst("[data-hi=1], [data-hi=true], .hi-badge, .hearing-impaired") != null
                val mt = row.selectFirst("span[data-ai-translated], [data-ai-translated], [data-ai-row]") != null ||
                    SUBDL_AI_MARKER_REGEX.containsMatchIn("$author $rowText ${row.outerHtml()}")

                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = language,
                    titleOrReleaseText = rowText.ifBlank { fullUrl.substringAfterLast('/') },
                    uploaderName = author,
                    isHearingImpairedFlag = hi,
                    isMachineTranslatedFlag = mt,
                )
                if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                    admittedCount++
                }
            }
            if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return
        }

        val dataLangElements = doc.select("[data-language]")
        val hasRawDataLang = SUBDL_DATA_LANG_ATTR_REGEX.containsMatchIn(htmlBody)
        val searchRoots = if (dataLangElements.isNotEmpty() || hasRawDataLang) {
            val matching = dataLangElements.filter { el ->
                SubtitleLanguage.fromTagOrName(el.attr("data-language")) == language
            }
            if (matching.isEmpty()) return
            matching
        } else if (isCombinedLanguagePage) {
            findSubDlCombinedLanguageSections(doc, language)
        } else {
            listOf(doc)
        }

        for (root in searchRoots) {
            for (anchor in root.select("a[href]")) {
                if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return
                val href = anchor.attr("href").trim()
                val zipMatch = SUBDL_ZIP_PATH_REGEX.find(href) ?: continue
                val fullUrl = resolveRelativeUrl(endpoints.subDlDownloadHost, zipMatch.value)
                if (!seenUrlsInPage.add(fullUrl)) continue

                val row = anchor.parents().firstOrNull { el ->
                    val tag = el.tagName().lowercase(Locale.ROOT)
                    tag == "li" || tag == "tr" || (tag == "div" && !el.hasAttr("data-language"))
                } ?: anchor.parent()

                val rowHtml = row?.outerHtml().orEmpty()
                val rowText = (row?.text() ?: anchor.text()).trim()
                if (se != null && !episodeTextOrPackMatches(rowText, se.first, se.second)) continue

                val author = row?.select("a[data-subtitle-author], [data-subtitle-author], a[href*=/u/], .author")
                    ?.joinToString(" ") { "${it.attr("data-subtitle-author")} ${it.text()}".trim() }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                val mt = SUBDL_AI_MARKER_REGEX.containsMatchIn("$author $rowText $rowHtml")
                val hiAttr = row?.attr("data-hi")?.trim().orEmpty()
                val hi = hiAttr == "1" || hiAttr.equals("true", ignoreCase = true) ||
                    row?.selectFirst("[data-hi=1], [data-hi=true], .hi-badge, .hearing-impaired") != null
                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = language,
                    titleOrReleaseText = rowText.ifBlank { fullUrl.substringAfterLast('/') },
                    uploaderName = author,
                    isHearingImpairedFlag = hi,
                    isMachineTranslatedFlag = mt,
                )
                if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                    admittedCount++
                }
            }
        }
    }

    private fun inferSubDlAstroRowLanguage(row: org.jsoup.nodes.Element): SubtitleLanguage? {
        row.attr("data-language").takeIf { it.isNotBlank() }?.let { SubtitleLanguage.fromTagOrName(it) }?.let { return it }
        for (ancestor in row.parents()) {
            ancestor.attr("data-language").takeIf { it.isNotBlank() }?.let { SubtitleLanguage.fromTagOrName(it) }?.let {
                return it
            }
            val heading = ancestor.selectFirst("> h2, > h3, > h4, > summary, > div > h2, > div > h3, > button")
                ?.text()?.trim()
            if (!heading.isNullOrBlank()) {
                val headToken = heading.substringBefore('(').substringBefore('·').trim()
                SubtitleLanguage.fromTagOrName(headToken)?.let { return it }
            }
            var prev = ancestor.previousElementSibling()
            var hops = 0
            while (prev != null && hops < 3) {
                val tag = prev.tagName().lowercase(Locale.ROOT)
                if (tag == "h2" || tag == "h3" || tag == "h4") {
                    val headToken = prev.text().substringBefore('(').substringBefore('·').trim()
                    SubtitleLanguage.fromTagOrName(headToken)?.let { return it }
                    break
                }
                prev = prev.previousElementSibling()
                hops++
            }
        }
        return null
    }

    private fun findSubDlCombinedLanguageSections(
        doc: org.jsoup.nodes.Document,
        language: SubtitleLanguage,
    ): List<org.jsoup.nodes.Element> {
        val sections = ArrayList<org.jsoup.nodes.Element>()
        for (heading in doc.select("h2, h3, h4, summary")) {
            val headToken = heading.text().substringBefore('(').substringBefore('·').trim()
            if (SubtitleLanguage.fromTagOrName(headToken) == language) {
                val container = heading.parent() ?: continue
                if (container !in sections) sections.add(container)
            }
        }
        return sections
    }


    private suspend fun querySubSource(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return
        val cleanImdb = cleanImdbOrNull(spec.imdbId)
        val primaryTitle = spec.titles.firstOrNull { it.isNotBlank() }?.trim()
        if (cleanImdb == null && primaryTitle == null) return

        val slugYear = catalogSlugYearFor(spec)
        val seasonAirYear = (spec as? MediaLookupSpec.Episode)?.seasonAirYear
        val key = apiKeys.subSourceApiKey?.takeIf { it.isNotBlank() }
        val beforeCount = admitted.count { it.catalogue == CatalogueSourceKey.SUBSOURCE }

        if (key != null) {
            val headers = mapOf(
                "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
                "Accept" to "application/json",
                "X-API-Key" to key,
                "api-key" to key,
            )

            val searchUrl = buildString {
                append(endpoints.subSourceApiV1Host.trimEnd('/')).append("/movies/search?")
                if (cleanImdb != null) {
                    append("searchType=imdb&imdb=").append(urlEncode(cleanImdb))
                } else if (primaryTitle != null) {
                    append("searchType=text&q=").append(urlEncode(primaryTitle))
                }
                if (spec is MediaLookupSpec.Movie && slugYear != null && slugYear > 0) {
                    append("&year=").append(slugYear)
                }
                if (se != null && se.first >= 0) {
                    append("&season=").append(se.first)
                }
            }

            val searchResp = transport.get(searchUrl, headers).getOrNull()
            val movieId = searchResp?.let {
                parseSubSourceMovieId(
                    jsonBody = it.textUtf8,
                    imdbId = cleanImdb,
                    title = primaryTitle,
                    expectedPremiereYear = slugYear,
                    fallbackSeasonYear = seasonAirYear,
                    season = se?.first,
                )
            }

            for (lang in languages) {
                val langParam = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
                val queryUrls = buildList {
                    if (!movieId.isNullOrBlank()) {
                        add(buildString {
                            append(endpoints.subSourceApiV1Host.trimEnd('/'))
                            append("/subtitles?movieId=").append(urlEncode(movieId))
                            append("&language=").append(langParam)
                            if (se != null && se.first >= 0) {
                                append("&season=").append(se.first)
                                append("&episode=").append(se.second)
                            }
                        })
                    }
                    if (cleanImdb != null) {
                        add(buildString {
                            append(endpoints.subSourceApiV1Host.trimEnd('/'))
                            append("/subtitles?imdb=").append(urlEncode(cleanImdb))
                            append("&language=").append(langParam)
                            if (se != null && se.first >= 0) {
                                append("&season=").append(se.first)
                                append("&episode=").append(se.second)
                            }
                        })
                    }
                }

                var langAdmitted = 0
                for (endpoint in queryUrls) {
                    val subResp = transport.get(endpoint, headers).getOrNull() ?: continue
                    langAdmitted += parseAndGateSubSourceList(subResp.textUtf8, spec, lang, se, headers, admitted, rejections)
                    if (langAdmitted > 0) break
                }
            }
        }

        val afterKeyedCount = admitted.count { it.catalogue == CatalogueSourceKey.SUBSOURCE }
        if (afterKeyedCount == beforeCount && transport.supportsExtendedLiveScrapers) {
            querySubSourceKeyless(
                spec = spec,
                languages = languages,
                se = se,
                cleanImdb = cleanImdb,
                primaryTitle = primaryTitle,
                slugYear = slugYear,
                seasonAirYear = seasonAirYear,
                admitted = admitted,
                rejections = rejections,
            )
        }
    }

    private suspend fun querySubSourceKeyless(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        se: Pair<Int, Int>?,
        cleanImdb: String?,
        primaryTitle: String?,
        slugYear: Int?,
        seasonAirYear: Int?,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val webBase = endpoints.subSourceWebHost.trimEnd('/')
        val apiOrigin = endpoints.subSourceOriginHost.trimEnd('/')
        val searchHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to webBase,
            "Referer" to "$webBase/",
        )
        val queryValue = cleanImdb ?: primaryTitle ?: return@coroutineScope
        val searchPayload = mapper.writeValueAsString(mapOf("query" to queryValue))
        val searchResp = transport.postJson("$apiOrigin/v1/movie/search", searchPayload, searchHeaders).getOrNull()
            ?: return@coroutineScope

        val subLink = parseSubSourceKeylessLink(
            jsonBody = searchResp.textUtf8,
            imdbId = cleanImdb,
            title = primaryTitle,
            expectedPremiereYear = slugYear,
            fallbackSeasonYear = seasonAirYear,
            season = se?.first,
        ) ?: return@coroutineScope

        val rscHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Accept" to "text/x-component, text/html, */*",
            "RSC" to "1",
            "Referer" to "$webBase/",
        )
        val dlHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Origin" to webBase,
            "Referer" to "$webBase/",
        ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSOURCE))
        val epHint = se?.let { "S%02dE%02d".format(Locale.ROOT, it.first, it.second) }

        for (lang in languages) {
            val langSlug = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val listUrl = "$webBase${subLink.removeSuffix("/")}/$langSlug?_rsc=1"
            val listResp = transport.get(listUrl, rscHeaders).getOrNull() ?: continue
            val rowCandidates = parseSubSourceRscSubtitleRows(listResp.textUtf8, subLink, langSlug, se)
            if (rowCandidates.isEmpty()) continue

            val preGated = ArrayList<Pair<SubSourceRscRow, CatalogueCandidateMeta>>()
            for (row in rowCandidates) {
                if (preGated.size >= 4) break
                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBSOURCE,
                    language = lang,
                    titleOrReleaseText = row.releaseText.ifBlank { "subsource_${lang.ietfTag}_${row.subId}" },
                    uploaderName = row.uploader,
                    isHearingImpairedFlag = row.isHi,
                    isMachineTranslatedFlag = row.isMachineTranslated,
                    releaseComment = row.comment,
                )
                val check = CatalogueCandidateGate.evaluatePreDownload(meta, spec)
                if (check.isFailure) {
                    val rej = (check.exceptionOrNull() as? CandidateGateRejectedException)?.rejection
                        ?: VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader("subsource_rsc_rejected")
                    rejections.add(rej)
                    continue
                }
                preGated.add(row to meta)
            }

            val resolvedTokens = preGated.map { (row, meta) ->
                async {
                    val detailUrl = "$webBase${row.detailPath}?_rsc=1"
                    val detailResp = transport.get(detailUrl, rscHeaders).getOrNull() ?: return@async null
                    val token = parseSubSourceDownloadToken(detailResp.textUtf8) ?: return@async null
                    Triple(meta, "$apiOrigin/v1/subtitle/download/$token", epHint)
                }
            }.awaitAll().filterNotNull()

            var count = 0
            for ((meta, dlUrl, hint) in resolvedTokens) {
                if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
                if (tryAdmitPreDownload(meta, spec, dlUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = hint)) {
                    count++
                }
            }
        }
    }

    internal data class SubSourceRscRow(
        val subId: String,
        val detailPath: String,
        val releaseText: String,
        val uploader: String?,
        val comment: String?,
        val isHi: Boolean,
        val isMachineTranslated: Boolean,
    )

    internal fun parseSubSourceKeylessLink(
        jsonBody: String,
        imdbId: String?,
        title: String?,
        expectedPremiereYear: Int?,
        fallbackSeasonYear: Int? = null,
        season: Int? = null,
    ): String? {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return null
        val arr = when {
            root.path("results").isArray -> root.path("results")
            root.path("data").isArray -> root.path("data")
            root.isArray -> root
            else -> return null
        }
        val wantedTitle = title?.let(::normalizeReleaseText)?.takeIf { it.isNotEmpty() }
        val allowedYears = listOfNotNull(expectedPremiereYear, fallbackSeasonYear)

        for (item in arr) {
            val itemImdb = item.firstNonBlank("imdb", "imdbId", "imdb_id")?.lowercase(Locale.ROOT)
            val imdbMatches = imdbId != null && !itemImdb.isNullOrBlank() && itemImdb == imdbId
            if (imdbId != null && !itemImdb.isNullOrBlank() && !imdbMatches) continue

            if (!imdbMatches) {
                val itemTitle = item.firstNonBlank("title", "name", "movieName") ?: continue
                val normItem = normalizeReleaseText(itemTitle)
                if (wantedTitle == null || (normItem != wantedTitle && !normItem.startsWith("$wantedTitle "))) continue
                val itemYear = item.path("releaseYear").asInt(item.path("release_year").asInt(item.path("year").asInt(0)))
                if (allowedYears.isNotEmpty() && itemYear > 0 && allowedYears.none { y -> abs(itemYear - y) <= 1 }) {
                    continue
                }
            }

            if (season != null && season > 0) {
                val seasonsNode = item.path("seasons")
                if (seasonsNode.isArray) {
                    for (sNode in seasonsNode) {
                        val sNum = sNode.path("season").asInt(sNode.path("number").asInt(-1))
                        val sLink = sNode.firstNonBlank("link", "url", "path")
                        if (sNum == season && !sLink.isNullOrBlank()) {
                            return normalizeSubSourceWebPath(sLink, null)
                        }
                    }
                }
            }

            val rawLink = item.firstNonBlank("link", "url", "path") ?: continue
            return normalizeSubSourceWebPath(rawLink, season)
        }
        return null
    }

    private fun normalizeSubSourceWebPath(rawLink: String, season: Int?): String {
        var clean = rawLink.trim().substringBefore('?').removeSuffix("/")
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) {
            clean = runCatching { URI(clean).path }.getOrDefault(clean)
        }
        if (clean.startsWith("/movie/")) clean = "/subtitles/" + clean.removePrefix("/movie/")
        if (clean.startsWith("/series/")) clean = "/subtitles/" + clean.removePrefix("/series/")
        if (!clean.startsWith("/subtitles/")) clean = "/subtitles/${clean.trimStart('/')}"
        if (season != null && season > 0) {
            val ord = subDlSeasonOrdinal(season)
            if (ord != null && !clean.contains("-season", ignoreCase = true) && !clean.endsWith("-$ord", ignoreCase = true)) {
                return "$clean-$ord"
            }
        }
        return clean
    }

    internal fun parseSubSourceRscSubtitleRows(
        rscOrHtmlBody: String,
        subLink: String,
        langSlug: String,
        se: Pair<Int, Int>?,
    ): List<SubSourceRscRow> {
        if (rscOrHtmlBody.isBlank()) return emptyList()
        val normalizedBody = rscOrHtmlBody.replace("\\\"", "\"")
        val cleanBase = subLink.removeSuffix("/")
        val seenIds = HashSet<String>()
        val rows = ArrayList<SubSourceRscRow>()

        for (snippet in extractSubSourceJsonObjects(normalizedBody)) {
            val node = runCatching { mapper.readTree(snippet) }.getOrNull() ?: continue
            val subId = node.firstNonBlank("id", "subtitle_id", "subtitleId") ?: continue
            if (!seenIds.add(subId)) continue
            val linkField = node.firstNonBlank("link", "full_link")
            val detailPath = when {
                !linkField.isNullOrBlank() && linkField.startsWith("/subtitles/") -> linkField.substringBefore('?')
                !linkField.isNullOrBlank() -> "/subtitles/${linkField.trimStart('/')}"
                else -> "$cleanBase/$langSlug/$subId"
            }
            if (!detailPath.contains("/$langSlug/", ignoreCase = true)) continue
            val releasesArr = node.path("releases")
            val releasesJoined = if (releasesArr.isArray) releasesArr.joinToString(" ") { it.asText("") } else ""
            val releaseText = listOfNotNull(
                node.firstNonBlank("release_info", "releaseInfo", "release_name", "title"),
                releasesJoined.takeIf { it.isNotBlank() },
            ).joinToString(" ").trim()
            if (se != null && releaseText.isNotBlank() && !episodeTextOrPackMatches(releaseText, se.first, se.second)) {
                continue
            }
            val uploader = node.path("uploader").firstNonBlank("display_name", "username", "name")
                ?: node.firstNonBlank("author", "uploader", "contributor")
            val comment = node.firstNonBlank("commentary", "comment")
            val isHi = node.path("hearing_impaired").asBoolean(false) ||
                node.path("hi").asBoolean(false) ||
                node.path("hearingImpaired").asBoolean(false)
            val isMt = node.path("ai_translated").asBoolean(false) ||
                node.path("machine_translated").asBoolean(false) ||
                node.path("aiTranslated").asBoolean(false)
            rows.add(SubSourceRscRow(subId, detailPath, releaseText, uploader, comment, isHi, isMt))
        }

        val detailLinkRegex = Regex(
            """(?i)(/subtitles/[a-z0-9_-]+/$langSlug/(\d+))"""
        )
        for (m in detailLinkRegex.findAll(normalizedBody)) {
            val detailPath = m.groupValues[1]
            val subId = m.groupValues[2]
            if (!seenIds.add(subId)) continue
            val winStart = (m.range.first - 360).coerceAtLeast(0)
            val winEnd = (m.range.last + 360).coerceAtMost(normalizedBody.length)
            val window = normalizedBody.substring(winStart, winEnd)
            val releaseMatch = SUBSOURCE_RELEASE_WINDOW_REGEX.find(window)?.groupValues?.getOrNull(1).orEmpty()
            if (se != null && releaseMatch.isNotBlank() && !episodeTextOrPackMatches(releaseMatch, se.first, se.second)) {
                continue
            }
            val isHi = window.contains("\"hearing_impaired\":true", true) || window.contains("\"hi\":true", true)
            val isMt = window.contains("\"ai_translated\":true", true) || window.contains("\"machine_translated\":true", true)
            rows.add(
                SubSourceRscRow(
                    subId = subId,
                    detailPath = detailPath,
                    releaseText = releaseMatch,
                    uploader = null,
                    comment = null,
                    isHi = isHi,
                    isMachineTranslated = isMt,
                )
            )
        }
        return rows
    }

    internal fun parseSubSourceDownloadToken(rscDetailBody: String): String? {
        if (rscDetailBody.isBlank()) return null
        val normalized = rscDetailBody.replace("\\\"", "\"")
        return SUBSOURCE_DOWNLOAD_TOKEN_REGEX.find(normalized)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun parseSubSourceMovieId(
        jsonBody: String,
        imdbId: String?,
        title: String?,
        expectedPremiereYear: Int?,
        fallbackSeasonYear: Int? = null,
        season: Int? = null,
    ): String? {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return null
        val arr = when {
            root.isArray -> root
            root.path("data").isArray -> root.path("data")
            root.path("results").isArray -> root.path("results")
            root.path("movies").isArray -> root.path("movies")
            root.path("data").isObject -> mapper.createArrayNode().add(root.path("data"))
            else -> return null
        }
        val wantedTitle = title?.let(::normalizeReleaseText)?.takeIf { it.isNotEmpty() }
        val allowedYears = listOfNotNull(expectedPremiereYear, fallbackSeasonYear)

        for (item in arr) {
            val id = item.firstNonBlank("movieId", "id", "movie_id") ?: continue
            val itemSeason = item.path("season").asInt(-1)
            if (season != null && season >= 0 && itemSeason >= 0 && itemSeason != season) continue

            val itemImdb = item.firstNonBlank("imdbId", "imdb_id", "imdb")?.lowercase(Locale.ROOT)
            if (imdbId != null && !itemImdb.isNullOrBlank()) {
                if (itemImdb == imdbId) return id
                continue
            }

            val itemTitle = item.firstNonBlank("title", "name", "movieName")
            if (wantedTitle != null && !itemTitle.isNullOrBlank()) {
                val normItem = normalizeReleaseText(itemTitle)
                if (normItem != wantedTitle && !normItem.startsWith("$wantedTitle ")) continue
            } else {
                continue
            }

            val itemYear = item.path("releaseYear").asInt(item.path("year").asInt(0))
            if (allowedYears.isNotEmpty() && itemYear > 0) {
                if (allowedYears.none { y -> abs(itemYear - y) <= 1 }) continue
            } else if (season == null && expectedPremiereYear != null && itemYear <= 0) {
                continue
            }
            return id
        }
        return null
    }

    private fun isTrustedSubSourceDownloadUrl(resolvedUrl: String): Boolean {
        val targetUri = runCatching { URI(resolvedUrl) }.getOrNull() ?: return false
        val targetScheme = targetUri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val targetHost = targetUri.host?.lowercase(Locale.ROOT)?.trim('[', ']').orEmpty()
        if (targetHost.isEmpty()) return false

        val v1Uri = runCatching { URI(endpoints.subSourceApiV1Host) }.getOrNull()
        val originUri = runCatching { URI(endpoints.subSourceOriginHost) }.getOrNull()
        val trustedHosts = setOfNotNull(
            v1Uri?.host?.lowercase(Locale.ROOT)?.trim('[', ']'),
            originUri?.host?.lowercase(Locale.ROOT)?.trim('[', ']'),
        )
        if (targetHost !in trustedHosts) return false
        if (targetScheme == "https") return true
        val isLoopback = targetHost == "127.0.0.1" || targetHost == "localhost" || targetHost == "::1"
        return targetScheme == "http" && isLoopback
    }

    private fun parseAndGateSubSourceList(
        jsonBody: String,
        spec: MediaLookupSpec,
        language: SubtitleLanguage,
        se: Pair<Int, Int>?,
        baseHeaders: Map<String, String>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ): Int {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return 0
        val arr = when {
            root.isArray -> root
            root.path("data").isArray -> root.path("data")
            root.path("subtitles").isArray -> root.path("subtitles")
            root.path("results").isArray -> root.path("results")
            else -> return 0
        }
        val provenanceHeaders = ProvenanceCodec.encodeHeaders(
            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSOURCE)
        )
        val authedDlHeaders = baseHeaders + provenanceHeaders
        val unauthedDlHeaders = baseHeaders.filterKeys { k ->
            !k.equals("X-API-Key", ignoreCase = true) &&
                !k.equals("api-key", ignoreCase = true) &&
                !k.equals("Authorization", ignoreCase = true)
        } + provenanceHeaders
        var count = 0
        for (item in arr) {
            if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
            val itemLang = SubtitleLanguage.fromTagOrName(item.firstNonBlank("language", "lang")) ?: language
            if (itemLang != language) continue

            val releasesNode = item.path("releases")
            val releaseListText = if (releasesNode.isArray) {
                releasesNode.joinToString(" ") { it.asText("") }
            } else {
                ""
            }
            val releaseText = listOfNotNull(
                item.firstNonBlank("releaseInfo", "release_info", "releaseName", "title"),
                releaseListText.takeIf { it.isNotBlank() },
            ).joinToString(" ").trim()

            val itemSeason = item.path("season").asInt(-1)
            val itemEpisode = item.path("episode").asInt(-1)
            if (se != null && se.first >= 0) {
                if (itemSeason >= 0 && itemSeason != se.first) continue
                if (itemEpisode > 0 && itemEpisode != se.second && !explicitEpisodeOrPackMatches(releaseText, se.first, se.second)) {
                    continue
                }
                if (itemEpisode <= 0 && releaseText.isNotBlank() && !episodeTextOrPackMatches(releaseText, se.first, se.second)) {
                    continue
                }
            }

            val directUrl = item.firstNonBlank("downloadUrl", "download_url", "url")
            val subId = item.firstNonBlank("subtitleId", "id", "sub_id")
            val resolvedUrl = when {
                !directUrl.isNullOrBlank() -> resolveRelativeUrl(endpoints.subSourceOriginHost, directUrl)
                !subId.isNullOrBlank() -> "${endpoints.subSourceApiV1Host.trimEnd('/')}/subtitles/$subId/download"
                else -> continue
            }
            val effectiveDlHeaders = if (isTrustedSubSourceDownloadUrl(resolvedUrl)) {
                authedDlHeaders
            } else {
                unauthedDlHeaders
            }

            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBSOURCE,
                language = language,
                titleOrReleaseText = releaseText.ifBlank { "subsource_${language.ietfTag}" },
                uploaderName = item.firstNonBlank("author", "uploader", "contributor"),
                isHearingImpairedFlag = item.path("hearingImpaired").asBoolean(false) || item.path("hi").asBoolean(false),
                isMachineTranslatedFlag = item.path("aiTranslated").asBoolean(false) ||
                    item.path("machineTranslated").asBoolean(false),
                declaredFps = item.path("fps").asDouble(0.0).takeIf { it > 0.0 },
                releaseComment = item.firstNonBlank("comment", "commentary"),
            )
            val epHint = se?.let { "S%02dE%02d".format(Locale.ROOT, it.first, it.second) }
            if (tryAdmitPreDownload(meta, spec, resolvedUrl, effectiveDlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                count++
            }
        }
        return count
    }


    private suspend fun querySubSense(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val cleanImdb = cleanImdbOrNull(spec.imdbId) ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return

        val effectiveBaseUrl = when {
            endpoints.subSenseBaseUrl != CatalogEndpoints.DEFAULT_SUBSENSE_URDU_BASE_URL -> endpoints.subSenseBaseUrl
            !transport.supportsExtendedLiveScrapers -> {
                if (SubtitleLanguage.URDU !in languages) return
                endpoints.subSenseBaseUrl
            }
            SubtitleLanguage.URDU in languages && SubtitleLanguage.ENGLISH in languages ->
                CatalogEndpoints.DEFAULT_SUBSENSE_BILINGUAL_BASE_URL
            SubtitleLanguage.ENGLISH in languages ->
                CatalogEndpoints.DEFAULT_SUBSENSE_ENGLISH_BASE_URL
            else ->
                CatalogEndpoints.DEFAULT_SUBSENSE_URDU_BASE_URL
        }

        val url = if (se != null && se.first >= 0) {
            "${effectiveBaseUrl.trimEnd('/')}/series/$cleanImdb:${se.first}:${se.second}.json"
        } else {
            "${effectiveBaseUrl.trimEnd('/')}/movie/$cleanImdb.json"
        }
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)
        val resp = transport.get(url, headers).getOrNull() ?: return
        val root = runCatching { mapper.readTree(resp.textUtf8) }.getOrNull() ?: return
        val arr = root.path("subtitles")
        if (!arr.isArray) return

        val dlHeaders = headers + ProvenanceCodec.encodeHeaders(
            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSENSE)
        )
        var urCount = 0
        var enCount = 0
        for (obj in arr) {
            val lang = SubtitleLanguage.fromTagOrName(obj.firstNonBlank("lang", "language")) ?: continue
            if (lang !in languages) continue
            if (lang == SubtitleLanguage.URDU && urCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue
            if (lang == SubtitleLanguage.ENGLISH && enCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue

            val rawSubUrl = obj.firstNonBlank("url") ?: continue
            val subUrl = rewriteDeadStremioSubtitleUrl(rawSubUrl)
            val labelText = listOfNotNull(
                obj.firstNonBlank("label"),
                obj.firstNonBlank("title"),
                obj.firstNonBlank("name"),
            ).joinToString(" ").trim()

            if (lang == SubtitleLanguage.URDU && QUEBEC_FALSE_URDU_REGEX.containsMatchIn("$labelText $subUrl")) {
                continue
            }

            val hi = obj.path("hi").asBoolean(false) ||
                obj.path("hearingImpaired").asBoolean(false) ||
                labelText.contains("· HI", ignoreCase = true)
            val mt = obj.path("ai_translated").asBoolean(false) ||
                obj.path("machine_translated").asBoolean(false)

            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBSENSE,
                language = lang,
                titleOrReleaseText = labelText.ifBlank { subUrl.substringAfterLast('/') },
                uploaderName = obj.firstNonBlank("author", "uploader"),
                isHearingImpairedFlag = hi,
                isMachineTranslatedFlag = mt,
            )
            if (tryAdmitPreDownload(meta, spec, subUrl, dlHeaders, admitted, rejections)) {
                if (lang == SubtitleLanguage.URDU) urCount++ else enCount++
            }
        }
    }


    private suspend fun querySubf2m(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return@coroutineScope
        val candidateTitles = spec.titles.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(2)
        if (candidateTitles.isEmpty()) return@coroutineScope
        val slugYear = catalogSlugYearFor(spec)
        val seasonAirYear = (spec as? MediaLookupSpec.Episode)?.seasonAirYear
        val isMovie = spec is MediaLookupSpec.Movie
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)

        var slug: String? = null
        for (title in candidateTitles) {
            slug = resolveSubf2mSlug(
                title = title,
                expectedPremiereYear = slugYear,
                fallbackSeasonYear = seasonAirYear,
                season = se?.first,
                isMovie = isMovie,
                headers = headers,
            )
            if (slug != null) break
        }
        val resolvedSlug = slug ?: return@coroutineScope
        val epHint = se?.let { "S%02dE%02d".format(Locale.ROOT, it.first, it.second) }

        for (lang in languages) {
            val langSegment = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val listUrl = "${endpoints.subf2mHost.trimEnd('/')}/subtitles/$resolvedSlug/$langSegment"
            val listResp = transport.get(listUrl, headers).getOrNull() ?: continue
            val detailRows = parseSubf2mDetailRows(listResp.textUtf8, resolvedSlug, langSegment, se)
            admitSubf2mRowsConcurrently(
                spec = spec,
                lang = lang,
                slug = resolvedSlug,
                detailRows = detailRows,
                headers = headers,
                epHint = epHint,
                admitted = admitted,
                rejections = rejections,
            )
        }
    }

    private suspend fun querySubf2mAnimeCour(
        spec: MediaLookupSpec.Episode,
        coord: EpisodeCoordinate.UnverifiedAnimeCour,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val courTitles = (coord.courTitles + spec.titles)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(2)
        if (courTitles.isEmpty()) return@coroutineScope
        val slugYear = catalogSlugYearFor(spec)
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)

        var slug: String? = null
        for (ct in courTitles) {
            slug = resolveSubf2mSlug(
                title = ct,
                expectedPremiereYear = slugYear,
                fallbackSeasonYear = spec.seasonAirYear,
                season = null,
                isMovie = false,
                headers = headers,
            )
            if (slug != null) break
        }
        val resolvedSlug = slug ?: return@coroutineScope
        val epHint = "E%02d".format(Locale.ROOT, coord.episodeInCour)

        for (lang in languages) {
            val langSegment = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val listUrl = "${endpoints.subf2mHost.trimEnd('/')}/subtitles/$resolvedSlug/$langSegment"
            val listResp = transport.get(listUrl, headers).getOrNull() ?: continue
            val detailRows = parseSubf2mDetailRows(
                htmlBody = listResp.textUtf8,
                slug = resolvedSlug,
                langSegment = langSegment,
                se = null to coord.episodeInCour,
                alternateEpisode = coord.absoluteEpisode,
            )
            admitSubf2mRowsConcurrently(
                spec = spec,
                lang = lang,
                slug = resolvedSlug,
                detailRows = detailRows,
                headers = headers,
                epHint = epHint,
                admitted = admitted,
                rejections = rejections,
            )
        }
    }

    private suspend fun admitSubf2mRowsConcurrently(
        spec: MediaLookupSpec,
        lang: SubtitleLanguage,
        slug: String,
        detailRows: List<Subf2mRowCandidate>,
        headers: Map<String, String>,
        epHint: String?,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val preGated = ArrayList<Pair<Subf2mRowCandidate, CatalogueCandidateMeta>>(4)
        for (row in detailRows) {
            if (preGated.size >= 4) break
            val preMeta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBF2M,
                language = lang,
                titleOrReleaseText = row.releaseText.ifBlank { slug },
                uploaderName = row.authorName,
                isHearingImpairedFlag = row.isHiDom,
                releaseComment = row.commentText,
            )
            val gateCheck = CatalogueCandidateGate.evaluatePreDownload(preMeta, spec)
            if (gateCheck.isFailure) {
                val rej = (gateCheck.exceptionOrNull() as? CandidateGateRejectedException)?.rejection
                    ?: VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader("subf2m_rejected")
                rejections.add(rej)
                continue
            }
            preGated.add(row to preMeta)
        }
        if (preGated.isEmpty()) return@coroutineScope

        val resolvedDetails = preGated.map { (row, preMeta) ->
            async {
                val detailResp = transport.get(row.detailUrl, headers).getOrNull() ?: return@async null
                val dlUrl = parseSubf2mDownloadHref(detailResp.textUtf8) ?: return@async null
                Triple(row, preMeta, dlUrl)
            }
        }.awaitAll().filterNotNull()

        var count = 0
        for ((row, preMeta, dlUrl) in resolvedDetails) {
            if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
            val dlHeaders = headers + mapOf("Referer" to row.detailUrl) +
                ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBF2M))
            if (tryAdmitPreDownload(preMeta, spec, dlUrl, dlHeaders, admitted, rejections, preferredZipEntryHint = epHint)) {
                count++
            }
        }
    }

    private suspend fun resolveSubf2mSlug(
        title: String,
        expectedPremiereYear: Int?,
        fallbackSeasonYear: Int?,
        season: Int?,
        isMovie: Boolean,
        headers: Map<String, String>,
    ): String? {
        val memoKey = "${title.lowercase(Locale.ROOT)}|${expectedPremiereYear ?: 0}|${season ?: 0}|$isMovie"
        subf2mSlugMemo[memoKey]?.let { cached ->
            return cached.takeUnless { it == NEGATIVE_SENTINEL }
        }
        val searchUrl = "${endpoints.subf2mHost.trimEnd('/')}/subtitles/searchbytitle?query=${urlEncode(title)}"
        val resp = transport.get(searchUrl, headers).getOrNull() ?: return null
        val slug = parseSubf2mSearchHtml(
            htmlBody = resp.textUtf8,
            expectedTitle = title,
            expectedPremiereYear = expectedPremiereYear,
            fallbackSeasonYear = fallbackSeasonYear,
            season = season,
            isMovie = isMovie,
        )
        subf2mSlugMemo[memoKey] = slug ?: NEGATIVE_SENTINEL
        return slug
    }

    internal fun parseSubf2mSearchHtml(
        htmlBody: String,
        expectedTitle: String,
        expectedPremiereYear: Int?,
        fallbackSeasonYear: Int? = null,
        season: Int? = null,
        isMovie: Boolean = true,
    ): String? {
        if (htmlBody.isBlank() || expectedTitle.isBlank()) return null
        val wantedTitle = normalizeReleaseText(expectedTitle)
        if (wantedTitle.isBlank()) return null
        val seasonMarkers = if (!isMovie && season != null && season > 0) {
            buildSubf2mSeasonMarkers(season)
        } else {
            emptyList()
        }
        val allowedYears = listOfNotNull(expectedPremiereYear, fallbackSeasonYear)

        val doc = Jsoup.parse(htmlBody)
        var fallbackBaseSlug: String? = null

        for (anchor in doc.select("a[href^=/subtitles/]")) {
            val href = anchor.attr("href").trim().removeSuffix("/")
            val parts = href.trimStart('/').split('/')
            if (parts.size != 2 || !parts[0].equals("subtitles", ignoreCase = true)) continue
            val slug = parts[1]
            if (slug.equals("searchbytitle", ignoreCase = true)) continue

            val rawText = anchor.text().trim()
            val anchorYear = PAREN_YEAR_REGEX.find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: STANDALONE_YEAR_REGEX.find(rawText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (isMovie && expectedPremiereYear != null) {
                if (anchorYear == null || abs(anchorYear - expectedPremiereYear) > 1) continue
            } else if (!isMovie && allowedYears.isNotEmpty() && anchorYear != null) {
                if (allowedYears.none { y -> abs(anchorYear - y) <= 1 }) continue
            }

            val strippedTitle = rawText.replace(PAREN_YEAR_REGEX, "").trim()
            val normCandidate = normalizeReleaseText(strippedTitle)
            val normSlug = normalizeReleaseText(slug)
            if (normCandidate != wantedTitle && !normCandidate.startsWith("$wantedTitle ")) continue

            if (seasonMarkers.isNotEmpty() &&
                seasonMarkers.any { marker ->
                    Regex("""\b${Regex.escape(marker)}\b""").containsMatchIn(normCandidate) ||
                        Regex("""\b${Regex.escape(marker)}\b""").containsMatchIn(normSlug)
                }
            ) {
                return slug
            }
            if (fallbackBaseSlug == null) {
                fallbackBaseSlug = slug
            }
        }
        return fallbackBaseSlug
    }

    private fun buildSubf2mSeasonMarkers(season: Int): List<String> = buildList {
        subDlSeasonOrdinal(season)?.replace('-', ' ')?.let(::add)
        add("season $season")
        add("season %02d".format(Locale.ROOT, season))
        add("s%02d".format(Locale.ROOT, season))
        val numericOrdinal = when {
            season % 100 in 11..13 -> "${season}th season"
            season % 10 == 1 -> "${season}st season"
            season % 10 == 2 -> "${season}nd season"
            season % 10 == 3 -> "${season}rd season"
            else -> "${season}th season"
        }
        add(numericOrdinal)
    }.distinct()

    private data class Subf2mRowCandidate(
        val detailUrl: String,
        val releaseText: String,
        val authorName: String?,
        val commentText: String?,
        val isHiDom: Boolean,
    )

    private fun parseSubf2mDetailRows(
        htmlBody: String,
        slug: String,
        langSegment: String,
        se: Pair<Int?, Int>?,
        alternateEpisode: Int? = null,
    ): List<Subf2mRowCandidate> {
        if (htmlBody.isBlank()) return emptyList()
        val doc = Jsoup.parse(htmlBody)
        val prefix = "/subtitles/$slug/$langSegment/"
        val seen = HashSet<String>()
        val out = ArrayList<Subf2mRowCandidate>()

        for (anchor in doc.select("a[href]")) {
            val href = anchor.attr("href").trim()
            if (!href.contains(prefix, ignoreCase = true)) continue
            val fullUrl = resolveRelativeUrl(endpoints.subf2mHost, href)
            if (!seen.add(fullUrl)) continue

            val row = anchor.parents().firstOrNull { it.tagName().equals("li", true) || it.tagName().equals("tr", true) }
                ?: anchor.parent()
            val scrollText = row?.select("ul.scrolllist li, .comment-col p")?.joinToString(" ") { it.text() }?.trim().orEmpty()
            val rowText = listOfNotNull(
                scrollText.takeIf { it.isNotEmpty() },
                (row?.text() ?: anchor.text()).trim().takeIf { it.isNotEmpty() },
            ).joinToString(" ").trim()
            if (se != null) {
                val primaryMatch = episodeTextOrPackMatches(rowText, se.first, se.second)
                val altMatch = alternateEpisode != null && episodeTextOrPackMatches(rowText, null, alternateEpisode)
                if (!primaryMatch && !altMatch) continue
            }

            val author = row?.selectFirst(".by a, a[href*=/u/], .author")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val comment = row?.select(".comment-col p, .comment, .sub-comment")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val isHi = row?.selectFirst(".hi, .hearing-impaired, [title*=Hearing]") != null
            out.add(Subf2mRowCandidate(fullUrl, rowText, author, comment, isHi))
        }
        return out
    }

    private fun parseSubf2mDownloadHref(detailHtml: String): String? {
        if (detailHtml.isBlank()) return null
        val doc = Jsoup.parse(detailHtml)
        val href = doc.selectFirst("a#downloadButton[href], a.download[href*=/subtitles/]")
            ?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return resolveRelativeUrl(endpoints.subf2mHost, href)
    }


    private suspend fun queryStremioCompatibleIndex(
        baseHost: String,
        sourceKey: CatalogueSourceKey,
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val cleanImdb = cleanImdbOrNull(spec.imdbId) ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return

        val url = if (se != null && se.first >= 0) {
            "${baseHost.trimEnd('/')}/subtitles/series/$cleanImdb:${se.first}:${se.second}.json"
        } else {
            "${baseHost.trimEnd('/')}/subtitles/movie/$cleanImdb.json"
        }
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)
        val resp = transport.get(url, headers).getOrNull() ?: return
        val root = runCatching { mapper.readTree(resp.textUtf8) }.getOrNull() ?: return
        val arr = root.path("subtitles")
        if (!arr.isArray) return

        val dlHeaders = headers + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(sourceKey))
        var urCount = 0
        var enCount = 0

        for (sub in arr) {
            val lang = SubtitleLanguage.fromTagOrName(sub.firstNonBlank("lang", "language")) ?: continue
            if (lang !in languages) continue
            if (lang == SubtitleLanguage.URDU && urCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue
            if (lang == SubtitleLanguage.ENGLISH && enCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue

            val rawSubUrl = sub.firstNonBlank("url") ?: continue
            val subUrl = rewriteDeadStremioSubtitleUrl(rawSubUrl)
            val hi = sub.path("hi").asBoolean(false) || sub.path("hearingImpaired").asBoolean(false)
            val mt = sub.path("ai_translated").asBoolean(false) || sub.path("machine_translated").asBoolean(false)
            val releaseText = listOfNotNull(
                sub.firstNonBlank("title", "name", "release", "label"),
                sub.firstNonBlank("id"),
            ).joinToString(" ").ifBlank { "${sourceKey.wireId}_${lang.ietfTag}" }

            val meta = CatalogueCandidateMeta(
                catalogue = sourceKey,
                language = lang,
                titleOrReleaseText = releaseText,
                uploaderName = sub.firstNonBlank("author", "uploader"),
                isHearingImpairedFlag = hi,
                isMachineTranslatedFlag = mt,
            )
            if (tryAdmitPreDownload(meta, spec, subUrl, dlHeaders, admitted, rejections)) {
                if (lang == SubtitleLanguage.URDU) urCount++ else enCount++
            }
        }
    }


    private suspend fun queryOpenSubtitlesLegacyRest(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return@coroutineScope
        val cleanImdb = cleanImdbOrNull(spec.imdbId)
        val headers = mapOf("User-Agent" to OPENSUBTITLES_REST_UA)
        val dlHeaders = headers + ProvenanceCodec.encodeHeaders(
            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.OPENSUBTITLES)
        )

        languages.map { lang ->
            async {
                val langId = if (lang == SubtitleLanguage.URDU) "urd" else "eng"
                val searchUrls = buildOpenSubtitlesRestUrls(cleanImdb, se, spec.titles, langId)
                var countForLang = 0
                for (searchUrl in searchUrls) {
                    if (countForLang > 0) break
                    val resp = transport.get(searchUrl, headers).getOrNull() ?: continue
                    val arr = runCatching { mapper.readTree(resp.textUtf8) }.getOrNull() ?: continue
                    if (!arr.isArray || arr.size() == 0) continue

                    val sortedItems = arr.toList().sortedWith(
                        compareByDescending<JsonNode> { it.path("SubFromTrusted").asText("") == "1" }
                            .thenByDescending { it.path("SubDownloadsCnt").asLong(0L) }
                            .thenByDescending { it.path("SubRating").asDouble(0.0) }
                    )

                    for (item in sortedItems) {
                        if (countForLang >= MAX_CANDIDATES_PER_SOURCE_LANG) break
                        if (item.path("SubBad").asText("") == "1") continue
                        val itemLang = SubtitleLanguage.fromTagOrName(
                            item.firstNonBlank("SubLanguageID", "ISO639", "LanguageName")
                        ) ?: lang
                        if (itemLang != lang) continue

                        if (se != null) {
                            val subSeason = item.path("SeriesSeason").asInt(-1)
                            val subEpisode = item.path("SeriesEpisode").asInt(0)
                            val fileAndRel = "${item.path("SubFileName").asText("")} ${item.path("MovieReleaseName").asText("")}"
                            if (subEpisode > 0) {
                                if (subEpisode != se.second) continue
                                if (se.first >= 0 && subSeason >= 0 && subSeason != se.first) continue
                            } else if (!episodeTextMatches(normalizeReleaseText(fileAndRel), se.first, se.second)) {
                                continue
                            }
                        }

                        val dlLink = item.firstNonBlank("SubDownloadLink") ?: continue
                        val hiFlag = item.path("SubHearingImpaired").asText("") == "1"
                        val mtFlag = item.path("SubAutoTranslation").asText("") == "1" ||
                            item.path("AiTranslated").asText("") == "1" ||
                            item.path("MachineTranslated").asText("") == "1"
                        val releaseText = listOfNotNull(
                            item.firstNonBlank("MovieReleaseName"),
                            item.firstNonBlank("SubFileName"),
                        ).joinToString(" ").ifBlank { "opensubtitles_${lang.ietfTag}" }

                        val uploader = listOfNotNull(
                            item.firstNonBlank("UserNickName"),
                            item.firstNonBlank("SubTranslator"),
                        ).joinToString(" ").ifBlank { null }

                        val meta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.OPENSUBTITLES,
                            language = lang,
                            titleOrReleaseText = releaseText,
                            uploaderName = uploader,
                            isHearingImpairedFlag = hiFlag,
                            isMachineTranslatedFlag = mtFlag,
                            declaredFps = item.path("MovieFPS").asDouble(0.0).takeIf { it > 0.0 },
                            declaredDurationMs = item.path("MovieTimeMS").asLong(0L).takeIf { it > 0L },
                            ratingScore = item.path("SubRating").asDouble(0.0),
                            downloadCount = item.path("SubDownloadsCnt").asInt(0),
                            releaseComment = item.firstNonBlank("SubAuthorComment"),
                        )
                        if (tryAdmitPreDownload(meta, spec, dlLink, dlHeaders, admitted, rejections)) {
                            countForLang++
                        }
                    }
                }
            }
        }.awaitAll()
    }

    private fun buildOpenSubtitlesRestUrls(
        cleanImdb: String?,
        se: Pair<Int, Int>?,
        titles: List<String>,
        langId: String,
    ): List<String> {
        val base = endpoints.openSubtitlesRestHost.trimEnd('/')
        val cleanQueries = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }.take(3)
        return buildList {
            if (cleanImdb != null) {
                val numericImdb = cleanImdb.removePrefix("tt")
                if (se != null && se.first >= 0) {
                    add("$base/search/imdbid-$numericImdb/season-${se.first}/episode-${se.second}/sublanguageid-$langId")
                } else {
                    add("$base/search/imdbid-$numericImdb/sublanguageid-$langId")
                }
            }
            for (q in cleanQueries) {
                val encoded = urlEncode(q).replace("+", "%20")
                if (se != null && se.first >= 0) {
                    add("$base/search/episode-${se.second}/query-$encoded/season-${se.first}/sublanguageid-$langId")
                } else {
                    add("$base/search/query-$encoded/sublanguageid-$langId")
                }
            }
        }
    }


    private suspend fun queryYifyAndYts(
        spec: MediaLookupSpec.Movie,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val cleanImdb = cleanImdbOrNull(spec.imdbId) ?: return
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)

        // Query yifysubtitles.ch first (live HTTP 200 without Cloudflare challenge), then yts-subs.com as fallback.
        val yifyUrl = "${endpoints.yifyHost.trimEnd('/')}/movie-imdb/$cleanImdb"
        val yifyResp = transport.get(yifyUrl, headers).getOrNull()
        var urAdmitted = 0
        var enAdmitted = 0
        if (yifyResp != null) {
            val counts = parseAndGateYifyTable(
                htmlBody = yifyResp.textUtf8,
                spec = spec,
                languages = languages,
                pageHost = endpoints.yifyHost,
                isYtsMirror = false,
                admitted = admitted,
                rejections = rejections,
            )
            urAdmitted += counts.first
            enAdmitted += counts.second
        }

        val missingLanguages = buildSet {
            if (SubtitleLanguage.URDU in languages && urAdmitted == 0) add(SubtitleLanguage.URDU)
            if (SubtitleLanguage.ENGLISH in languages && enAdmitted == 0) add(SubtitleLanguage.ENGLISH)
        }
        if (missingLanguages.isEmpty()) return

        val ytsUrl = "${endpoints.ytsSubsHost.trimEnd('/')}/movie-imdb/$cleanImdb"
        val ytsResp = transport.get(ytsUrl, headers).getOrNull() ?: return
        parseAndGateYifyTable(
            htmlBody = ytsResp.textUtf8,
            spec = spec,
            languages = missingLanguages,
            pageHost = endpoints.ytsSubsHost,
            isYtsMirror = true,
            admitted = admitted,
            rejections = rejections,
        )
    }

    private fun parseAndGateYifyTable(
        htmlBody: String,
        spec: MediaLookupSpec.Movie,
        languages: Set<SubtitleLanguage>,
        pageHost: String,
        isYtsMirror: Boolean,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ): Pair<Int, Int> {
        if (htmlBody.isBlank()) return 0 to 0
        val doc = Jsoup.parse(htmlBody)
        var urCount = 0
        var enCount = 0

        for (row in doc.select("tr:has(.sub-lang)")) {
            val langText = row.selectFirst(".sub-lang")?.text()?.trim()
            val lang = SubtitleLanguage.fromTagOrName(langText) ?: continue
            if (lang !in languages) continue
            if (lang == SubtitleLanguage.URDU && urCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue
            if (lang == SubtitleLanguage.ENGLISH && enCount >= MAX_CANDIDATES_PER_SOURCE_LANG) continue

            val href = row.selectFirst("a[href*=/subtitles/]")?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: continue
            val referer = resolveRelativeUrl(pageHost, href)
            val zipUrl = if (isYtsMirror) {
                val slug = referer.substringAfter("/subtitles/", "").substringBefore('?').substringBefore('#').trim('/')
                if (slug.isEmpty()) continue
                "${endpoints.ytsSubsZipHost.trimEnd('/')}/subtitles/$slug.zip"
            } else {
                if (!referer.contains("/subtitles/")) continue
                referer.replaceFirst("/subtitles/", "/subtitle/") + ".zip"
            }

            val isHi = row.selectFirst(".hi-subtitle, .hi-install") != null
            val uploader = row.selectFirst(".uploader-cell, a[href*=/user/]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val rating = row.selectFirst(".rating-cell, .label")?.text()?.trim()?.toDoubleOrNull() ?: 0.0
            val releaseText = row.selectFirst("a[href*=/subtitles/]")?.text()?.trim()
                ?.replace(Regex("""(?i)^subtitle\s+"""), "")
                ?.ifBlank { zipUrl.substringAfterLast('/') }
                ?: zipUrl.substringAfterLast('/')

            val dlHeaders = mapOf(
                "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
                "Referer" to referer,
            ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.YIFY))

            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.YIFY,
                language = lang,
                titleOrReleaseText = releaseText,
                uploaderName = uploader,
                isHearingImpairedFlag = isHi,
                ratingScore = rating,
            )
            if (tryAdmitPreDownload(meta, spec, zipUrl, dlHeaders, admitted, rejections)) {
                if (lang == SubtitleLanguage.URDU) urCount++ else enCount++
            }
        }
        return urCount to enCount
    }


    private suspend fun queryAddic7ed(
        spec: MediaLookupSpec.Episode,
        coord: EpisodeCoordinate.VerifiedSeasonEpisode,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        if (coord.season <= 0 || coord.episode <= 0) return
        val title = spec.titles.firstOrNull { it.isNotBlank() }?.trim() ?: return

        if (transport.supportsExtendedLiveScrapers || endpoints.gestdownHost != CatalogEndpoints.DEFAULT_GESTDOWN_HOST) {
            val gestdownAdmitted = queryGestdown(spec, coord, title, admitted, rejections)
            if (gestdownAdmitted > 0) return
        }

        val baseHost = endpoints.addic7edHost.trimEnd('/')
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)
        val dlHeaders = headers + mapOf("Referer" to "$baseHost/") +
            ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.ADDIC7ED))

        val query = urlEncode("$title ${coord.season} ${coord.episode}").replace("+", "%20")
        val searchResp = transport.get("$baseHost/search.php?search=$query&Submit=Search", headers).getOrNull() ?: return
        val finalUrl = searchResp.finalUrl

        if (finalUrl.contains("/show/")) {
            val showId = finalUrl.substringAfterLast('/').substringBefore('?').trim()
            if (showId.isEmpty()) return
            val ajaxResp = transport.get(
                "$baseHost/ajax_loadShow.php?show=$showId&season=${coord.season}&langs=|1|&hd=0&hi=0",
                headers,
            ).getOrNull() ?: return
            val ajaxDoc = Jsoup.parse(ajaxResp.textUtf8)
            var count = 0
            for (row in ajaxDoc.select("#season tbody tr")) {
                if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
                val epNum = row.select("td:eq(1)").text().trim().toIntOrNull()
                if (epNum != coord.episode) continue
                val dlHref = row.selectFirst("a[href*=original/], a[href*=updated/]")?.attr("href")?.trim() ?: continue
                val isHi = row.select("img[title*=Hearing], td:contains(HI)").isNotEmpty()
                val versionText = row.select("td:eq(4)").text().trim().ifBlank {
                    "$title S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode)
                }
                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.ADDIC7ED,
                    language = SubtitleLanguage.ENGLISH,
                    titleOrReleaseText = versionText,
                    isHearingImpairedFlag = isHi,
                )
                if (tryAdmitPreDownload(meta, spec, resolveRelativeUrl(baseHost, dlHref), dlHeaders, admitted, rejections)) {
                    count++
                }
            }
            return
        }

        val seriePage = when {
            finalUrl.contains("/serie/") || finalUrl.contains("/movie/") -> finalUrl
            else -> {
                val searchDoc = Jsoup.parse(searchResp.textUtf8)
                searchDoc.select("table.tabel a[href]")
                    .map { it.attr("href").trim() }
                    .firstOrNull { it.contains("/serie/") || it.startsWith("serie/") }
                    ?.let { resolveRelativeUrl(baseHost, it) }
            }
        } ?: return

        val englishPage = if (seriePage.contains("/serie/")) {
            seriePage.substringBeforeLast('/') + "/1"
        } else {
            seriePage
        }
        val pageResp = if (englishPage == finalUrl) searchResp else transport.get(englishPage, headers).getOrNull() ?: return
        val doc = Jsoup.parse(pageResp.textUtf8)
        var count = 0
        for (row in doc.select(".tabel95 .tabel95 tr:has(.language)")) {
            if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
            if (!row.text().contains("English", ignoreCase = true)) continue
            val tableContainer = row.parents().firstOrNull { it.tagName().equals("table", true) } ?: row.parent()
            val isHi = tableContainer?.select("[title=\"Hearing Impaired\"], img[src*=hi.jpg]")?.isNotEmpty() == true
            val versionHeader = tableContainer?.selectFirst(".NewsTitle")?.text()?.trim()
                ?.ifBlank { "$title S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode) }
                ?: "$title S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode)
            val uploader = tableContainer?.selectFirst("a[href*=/user/]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val dlHref = row.selectFirst("a[href*=original/], a[href*=updated/]")?.attr("href")?.trim() ?: continue
            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.ADDIC7ED,
                language = SubtitleLanguage.ENGLISH,
                titleOrReleaseText = versionHeader,
                uploaderName = uploader,
                isHearingImpairedFlag = isHi,
            )
            if (tryAdmitPreDownload(meta, spec, resolveRelativeUrl(baseHost, dlHref), dlHeaders, admitted, rejections)) {
                count++
            }
        }
    }

    private suspend fun queryGestdown(
        spec: MediaLookupSpec.Episode,
        coord: EpisodeCoordinate.VerifiedSeasonEpisode,
        title: String,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ): Int {
        val gestBase = endpoints.gestdownHost.trimEnd('/')
        val headers = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Accept" to "application/json",
        )
        var showId: String? = null
        if (spec.tvdbId != null && spec.tvdbId > 0) {
            val tvdbResp = transport.get("$gestBase/shows/external/tvdb/${spec.tvdbId}", headers).getOrNull()
            if (tvdbResp != null) {
                showId = parseGestdownShowId(tvdbResp.textUtf8, title, allowUnmatchedFallback = true)
            }
        }
        if (showId == null) {
            val encodedTitle = urlEncode(title).replace("+", "%20")
            val searchResp = transport.get("$gestBase/shows/search/$encodedTitle", headers).getOrNull() ?: return 0
            showId = parseGestdownShowId(searchResp.textUtf8, title, allowUnmatchedFallback = false) ?: return 0
        }

        val subsResp = transport.get(
            "$gestBase/subtitles/get/$showId/${coord.season}/${coord.episode}/en",
            headers,
        ).getOrNull() ?: return 0
        val root = runCatching { mapper.readTree(subsResp.textUtf8) }.getOrNull() ?: return 0
        val arr = when {
            root.path("matchingSubtitles").isArray -> root.path("matchingSubtitles")
            root.path("subtitles").isArray -> root.path("subtitles")
            root.isArray -> root
            else -> return 0
        }

        val dlHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Accept" to "text/srt, text/plain, */*",
        ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.ADDIC7ED))

        var count = 0
        for (sub in arr) {
            if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
            if (sub.has("completed") && !sub.path("completed").asBoolean(true)) continue
            val dlUri = sub.firstNonBlank("downloadUri", "download_uri", "url") ?: continue
            val isHi = sub.path("hearingImpaired").asBoolean(false) || sub.path("hi").asBoolean(false)
            val version = sub.firstNonBlank("version", "release", "title").orEmpty().trim()
            val releaseText = if (version.isNotBlank()) {
                "$title S%02dE%02d $version".format(Locale.ROOT, coord.season, coord.episode)
            } else {
                "$title S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode)
            }
            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.ADDIC7ED,
                language = SubtitleLanguage.ENGLISH,
                titleOrReleaseText = releaseText,
                uploaderName = sub.firstNonBlank("contributor", "author", "uploader"),
                isHearingImpairedFlag = isHi,
                downloadCount = sub.path("downloadCount").asInt(0),
            )
            if (tryAdmitPreDownload(meta, spec, resolveRelativeUrl(gestBase, dlUri), dlHeaders, admitted, rejections)) {
                count++
            }
        }
        return count
    }

    internal fun parseGestdownShowId(
        jsonBody: String,
        expectedTitle: String,
        allowUnmatchedFallback: Boolean = false,
    ): String? {
        val root = runCatching { mapper.readTree(jsonBody) }.getOrNull() ?: return null
        val shows = when {
            root.path("shows").isArray -> root.path("shows")
            root.isArray -> root
            root.has("id") -> mapper.createArrayNode().add(root)
            else -> return null
        }
        val wanted = normalizeReleaseText(expectedTitle)
        var firstId: String? = null
        for (s in shows) {
            val id = s.firstNonBlank("id", "uniqueId") ?: continue
            if (firstId == null) firstId = id
            val name = s.firstNonBlank("name", "title") ?: continue
            val normName = normalizeReleaseText(name)
            if (normName == wanted || normName.startsWith("$wanted ")) {
                return id
            }
        }
        return if (allowUnmatchedFallback) firstId else null
    }


    private suspend fun querySubtitleCat(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) = coroutineScope {
        val queriesAndCoords = buildSubtitleCatSearchPlan(spec)
        if (queriesAndCoords.isEmpty()) return@coroutineScope
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)
        val baseHost = endpoints.subtitleCatHost.trimEnd('/')
        val movieYear = (spec as? MediaLookupSpec.Movie)?.releaseYear

        for (plan in queriesAndCoords) {
            val searchUrl = "$baseHost/index.php?search=${urlEncode(plan.query)}"
            val searchResp = transport.get(searchUrl, headers).getOrNull() ?: continue
            val matchedPages = selectSubtitleCatSearchHits(
                rawHits = parseSubtitleCatSearchHtml(searchResp.textUtf8, baseHost),
                query = plan.query,
                season = plan.season,
                episode = plan.episode,
                alternateEpisode = plan.alternateEpisode,
                movieYear = movieYear,
            )
            if (matchedPages.isEmpty()) continue

            val detailPages = matchedPages.map { hit ->
                async {
                    transport.get(hit.pageUrl, headers).getOrNull()?.let { hit to it.textUtf8 }
                }
            }.awaitAll().filterNotNull()

            var emittedAny = false
            for ((hit, detailHtml) in detailPages) {
                val detailMeta = parseSubtitleCatDetailMeta(detailHtml, hit)
                val urLinks = parseSubtitleCatUrduLinks(detailHtml, baseHost)
                val enDirectUrl = parseSubtitleCatEnglishLink(detailHtml, baseHost)
                val origUrl = parseSubtitleCatOrigLink(detailHtml, baseHost)
                    ?: urLinks.firstOrNull()?.replace(SUBTITLECAT_UR_SUFFIX_REPLACE_REGEX, "-orig.srt")
                        ?.takeIf { it !in urLinks }

                if (SubtitleLanguage.URDU in languages) {
                    for (urUrl in urLinks) {
                        val fileName = urUrl.substringAfterLast('/').substringBefore('?')
                        val urMeta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.SUBTITLECAT,
                            language = SubtitleLanguage.URDU,
                            titleOrReleaseText = "${hit.titleText} $fileName".trim(),
                            uploaderName = detailMeta.uploaderName,
                            isHearingImpairedFlag = detailMeta.isHearingImpaired,
                        )
                        val dlHeaders = headers + ProvenanceCodec.encodeHeaders(
                            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBTITLECAT)
                        )
                        if (tryAdmitPreDownload(urMeta, spec, urUrl, dlHeaders, admitted, rejections)) {
                            emittedAny = true
                        }
                    }
                }

                if (SubtitleLanguage.ENGLISH in languages) {
                    val chosenEnUrl = enDirectUrl ?: origUrl
                    if (chosenEnUrl != null) {
                        val isOrigFallback = enDirectUrl == null || chosenEnUrl.endsWith("-orig.srt", ignoreCase = true)
                        val origHeaderUrl = origUrl ?: chosenEnUrl
                        val origName = origHeaderUrl.substringAfterLast('/').substringBefore('?')
                        val enMeta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.SUBTITLECAT,
                            language = SubtitleLanguage.ENGLISH,
                            titleOrReleaseText = hit.titleText,
                            uploaderName = detailMeta.uploaderName,
                            isHearingImpairedFlag = detailMeta.isHearingImpaired,
                        )
                        val dlHeaders = headers + mapOf(
                            "X-SubtitleCat-Orig-Url" to origHeaderUrl,
                            "X-SubtitleCat-Orig-Name" to origName,
                        ) + ProvenanceCodec.encodeHeaders(
                            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBTITLECAT)
                        )
                        if (tryAdmitPreDownload(
                                meta = enMeta,
                                spec = spec,
                                downloadUrl = chosenEnUrl,
                                requestHeaders = dlHeaders,
                                admitted = admitted,
                                rejections = rejections,
                                isSubtitleCatOrigDonor = isOrigFallback,
                            )
                        ) {
                            emittedAny = true
                        }
                    }
                }
            }
            if (emittedAny) return@coroutineScope
        }
    }

    private data class SubtitleCatDetailMeta(
        val uploaderName: String?,
        val isHearingImpaired: Boolean,
    )

    private fun parseSubtitleCatDetailMeta(
        detailHtml: String,
        hit: SubtitleCatSearchHit,
    ): SubtitleCatDetailMeta {
        val doc = Jsoup.parse(detailHtml)
        val authorElText = doc.selectFirst(
            ".sub-author, [class*=uploader], [class*=author], a[href*=/user/], a[href*=/profile/], a[href*=/uploader/]"
        )?.text()?.trim()
        val uploaderFromRegex = SUBTITLECAT_UPLOADED_BY_REGEX.find(authorElText ?: doc.text())
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val uploaderName = uploaderFromRegex
            ?: authorElText
                ?.replace(Regex("""(?i)^(?:uploaded\s+by|uploader|author|by)\s*[:\-]?\s*"""), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val hasHiDomBadge = doc.select(
            ".hi, .hi-badge, .sdh, .sdh-badge, [class*=hearing], [title*=Hearing Impaired], [title*=hearing impaired], [alt*=Hearing Impaired], [alt*=hearing impaired], img[src*=hi.]"
        ).isNotEmpty()
        val slugText = hit.pageUrl.substringAfterLast('/').substringBefore('?').removeSuffix(".html")
        val metaBannerText = doc.select(".sub-info, .sub-meta, .hi-badge, .badge, h1, h2").text()
        val isHi = hasHiDomBadge ||
            CatalogueCandidateGate.matchHearingImpairedMarker(slugText) != null ||
            CatalogueCandidateGate.matchHearingImpairedMarker(hit.titleText) != null ||
            CatalogueCandidateGate.matchHearingImpairedMarker(metaBannerText) != null

        return SubtitleCatDetailMeta(
            uploaderName = uploaderName,
            isHearingImpaired = isHi,
        )
    }

    private val SUBTITLECAT_UPLOADED_BY_REGEX = Regex(
        """(?i)\b(?:uploaded\s+by|uploader|author|translated\s+by)\s*[:\-]?\s*([A-Za-z0-9_.-]{2,48})"""
    )

    private data class SubtitleCatQueryPlan(
        val query: String,
        val season: Int?,
        val episode: Int?,
        val alternateEpisode: Int? = null,
    )

    private data class SubtitleCatSearchHit(
        val pageUrl: String,
        val titleText: String,
    )

    private fun buildSubtitleCatSearchPlan(spec: MediaLookupSpec): List<SubtitleCatQueryPlan> {
        return when (spec) {
            is MediaLookupSpec.Movie -> {
                spec.titles.asSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase(Locale.ROOT) }
                    .take(2)
                    .map { SubtitleCatQueryPlan(query = it, season = null, episode = null) }
                    .toList()
            }
            is MediaLookupSpec.Episode -> {
                when (val coord = spec.coordinate) {
                    is EpisodeCoordinate.VerifiedSeasonEpisode -> {
                        val cleanTitles = spec.titles.asSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase(Locale.ROOT) }
                            .take(2)
                            .toList()
                        val plans = ArrayList<SubtitleCatQueryPlan>(4)
                        for (t in cleanTitles) {
                            if (coord.season > 0) {
                                plans.add(
                                    SubtitleCatQueryPlan(
                                        query = "$t S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode),
                                        season = coord.season,
                                        episode = coord.episode,
                                    )
                                )
                            }
                            if (spec.isAnime || coord.season == 0) {
                                plans.add(
                                    SubtitleCatQueryPlan(
                                        query = "$t %02d".format(Locale.ROOT, coord.episode),
                                        season = coord.season.takeIf { it > 0 },
                                        episode = coord.episode,
                                    )
                                )
                            }
                        }
                        plans.distinctBy { it.query.lowercase(Locale.ROOT) }.take(3)
                    }
                    is EpisodeCoordinate.UnverifiedAnimeCour -> {
                        val courTitles = (coord.courTitles + spec.titles)
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase(Locale.ROOT) }
                            .take(2)
                        val plans = ArrayList<SubtitleCatQueryPlan>(3)
                        for (ct in courTitles) {
                            plans.add(
                                SubtitleCatQueryPlan(
                                    query = "$ct %02d".format(Locale.ROOT, coord.episodeInCour),
                                    season = null,
                                    episode = coord.episodeInCour,
                                    alternateEpisode = coord.absoluteEpisode,
                                )
                            )
                        }
                        if (coord.absoluteEpisode != null && coord.absoluteEpisode != coord.episodeInCour && courTitles.isNotEmpty()) {
                            plans.add(
                                SubtitleCatQueryPlan(
                                    query = "${courTitles.first()} %02d".format(Locale.ROOT, coord.absoluteEpisode),
                                    season = null,
                                    episode = coord.episodeInCour,
                                    alternateEpisode = coord.absoluteEpisode,
                                )
                            )
                        }
                        plans.distinctBy { it.query.lowercase(Locale.ROOT) }.take(3)
                    }
                    is EpisodeCoordinate.UnverifiedEpisodeOnly -> {
                        val cleanTitles = spec.titles.asSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase(Locale.ROOT) }
                            .take(2)
                            .toList()
                        val plans = ArrayList<SubtitleCatQueryPlan>(4)
                        for (t in cleanTitles) {
                            plans.add(
                                SubtitleCatQueryPlan(
                                    query = "$t E%02d".format(Locale.ROOT, coord.episode),
                                    season = null,
                                    episode = coord.episode,
                                )
                            )
                            plans.add(
                                SubtitleCatQueryPlan(
                                    query = "$t %02d".format(Locale.ROOT, coord.episode),
                                    season = null,
                                    episode = coord.episode,
                                )
                            )
                        }
                        plans.distinctBy { it.query.lowercase(Locale.ROOT) }.take(3)
                    }
                }
            }
        }
    }

    private fun parseSubtitleCatSearchHtml(htmlBody: String, baseHost: String): List<SubtitleCatSearchHit> {
        if (htmlBody.isBlank()) return emptyList()
        val doc = Jsoup.parse(htmlBody)
        val out = ArrayList<SubtitleCatSearchHit>()
        val seen = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href").trim()
            if (!href.substringBefore('?').endsWith(".html", ignoreCase = true)) continue
            val pageUrl = when {
                href.startsWith("http://", true) || href.startsWith("https://", true) -> href
                href.contains("/subs/") || href.startsWith("subs/") -> resolveRelativeUrl(baseHost, href)
                else -> continue
            }
            if (!seen.add(pageUrl)) continue
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            if (text.isNotEmpty()) {
                out.add(SubtitleCatSearchHit(pageUrl, text))
            }
        }
        return out
    }

    private fun selectSubtitleCatSearchHits(
        rawHits: List<SubtitleCatSearchHit>,
        query: String,
        season: Int?,
        episode: Int?,
        alternateEpisode: Int?,
        movieYear: Int?,
    ): List<SubtitleCatSearchHit> {
        val strippedQuery = if (episode != null && episode > 0) {
            query.replace(Regex("""(?i)\s+s\d{1,2}\s*e\d{1,4}\s*$"""), "")
                .replace(Regex("""\s+\d{1,4}\s*$"""), "")
                .trim()
        } else {
            query.trim()
        }
        val wanted = normalizeReleaseText(strippedQuery)
        if (wanted.isBlank()) return emptyList()
        val wantedTokens = wanted.split(' ').filter { it.length >= 3 }
        val requireMovieYear = movieYear != null && season == null && episode == null

        return rawHits.asSequence()
            .filter { hit ->
                val haystackRaw = "${hit.titleText} ${hit.pageUrl.substringAfterLast('/')}"
                val haystack = normalizeReleaseText(haystackRaw)
                val haystackTokens = haystack.split(' ')
                val titleOk = when {
                    haystack.contains(wanted) -> true
                    wanted.contains(' ') -> wantedTokens.isNotEmpty() && wantedTokens.all { it in haystackTokens }
                    else -> wanted in haystackTokens
                }
                if (!titleOk) return@filter false

                if (episode != null && episode > 0) {
                    val primaryMatch = episodeTextMatches(haystack, season, episode)
                    val altMatch = alternateEpisode != null && episodeTextMatches(haystack, null, alternateEpisode)
                    if (!primaryMatch && !altMatch) return@filter false
                }

                if (requireMovieYear) {
                    val foundYear = STANDALONE_YEAR_REGEX.find(haystack)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    if (foundYear == null || abs(foundYear - movieYear) > 1) return@filter false
                }
                true
            }
            .take(2)
            .toList()
    }

    private fun parseSubtitleCatEnglishLink(detailHtml: String, baseHost: String): String? {
        val doc = Jsoup.parse(detailHtml)
        val href = doc.select("a[href]")
            .map { it.attr("href").trim() }
            .firstOrNull { h ->
                val p = h.substringBefore('?').lowercase(Locale.ROOT)
                p.endsWith("-en.srt") || p.endsWith("-eng.srt")
            } ?: return null
        return resolveRelativeUrl(baseHost, href).replace(" ", "%20")
    }

    internal fun parseSubtitleCatOrigLink(detailHtml: String, baseHost: String): String? {
        SUBTITLECAT_ORIG_SRT_REGEX.find(detailHtml)?.let { match ->
            return resolveRelativeUrl(baseHost, match.value.trim()).replace(" ", "%20")
        }
        SUBTITLECAT_TRANSLATE_FOLDER_3ARG_REGEX.find(detailHtml)?.let { match ->
            val fileArg = match.groupValues[1].trim()
            val folderArg = match.groupValues[2].trim().trimEnd('/')
            if (fileArg.isNotEmpty() && folderArg.isNotEmpty()) {
                val origFile = when {
                    fileArg.endsWith("-orig.srt", ignoreCase = true) -> fileArg
                    fileArg.endsWith(".srt", ignoreCase = true) ->
                        fileArg.substring(0, fileArg.length - 4) + "-orig.srt"
                    else -> "$fileArg-orig.srt"
                }
                return resolveRelativeUrl(baseHost, "$folderArg/$origFile").replace(" ", "%20")
            }
        }
        SUBTITLECAT_TRANSLATE_FOLDER_2ARG_REGEX.find(detailHtml)?.let { match ->
            val pathArg = match.groupValues[1].trim()
            if (pathArg.isNotEmpty()) {
                val origPath = when {
                    pathArg.endsWith("-orig.srt", ignoreCase = true) -> pathArg
                    pathArg.endsWith(".srt", ignoreCase = true) ->
                        pathArg.substring(0, pathArg.length - 4) + "-orig.srt"
                    else -> "$pathArg-orig.srt"
                }
                return resolveRelativeUrl(baseHost, origPath).replace(" ", "%20")
            }
        }
        return null
    }

    private fun parseSubtitleCatUrduLinks(detailHtml: String, baseHost: String): List<String> {
        val doc = Jsoup.parse(detailHtml)
        val out = LinkedHashSet<String>()
        doc.select("a#download_ur[href], a#download_urd[href]").forEach { el ->
            val h = el.attr("href").trim()
            if (h.isNotEmpty()) out.add(resolveRelativeUrl(baseHost, h).replace(" ", "%20"))
        }
        for (row in doc.select("div.sub-single, tr")) {
            val labelText = row.select("span, td").firstOrNull()?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (labelText == "urdu" || labelText.startsWith("urdu ")) {
                for (a in row.select("a[href]")) {
                    val h = a.attr("href").trim()
                    if (h.substringBefore('?').endsWith(".srt", ignoreCase = true)) {
                        out.add(resolveRelativeUrl(baseHost, h).replace(" ", "%20"))
                    }
                }
            }
        }
        for (a in doc.select("a[href]")) {
            val h = a.attr("href").trim()
            val p = h.substringBefore('?').lowercase(Locale.ROOT)
            if (p.endsWith("-ur.srt") || p.endsWith("-urd.srt") || p.endsWith(".ur.srt") || p.endsWith(".urdu.srt") || p.endsWith("_urdu.srt")) {
                out.add(resolveRelativeUrl(baseHost, h).replace(" ", "%20"))
            }
        }
        return out.toList()
    }

    companion object {
        private const val NEGATIVE_SENTINEL = "__NONE__"
        private const val MAX_CANDIDATES_PER_SOURCE_LANG = 5
        private const val OPENSUBTITLES_REST_UA = "TemporaryUserAgent"

        val DEFAULT_ACTIVE_SOURCES: Set<CatalogueSourceKey> = setOf(
            CatalogueSourceKey.WYZIE,
            CatalogueSourceKey.SUBTITLE_API,
            CatalogueSourceKey.OPENSUBTITLES,
            CatalogueSourceKey.STREMIO,
            CatalogueSourceKey.SUBDL,
            CatalogueSourceKey.ADDIC7ED,
            CatalogueSourceKey.YIFY,
            CatalogueSourceKey.SUBSOURCE,
            CatalogueSourceKey.SUBSENSE,
            CatalogueSourceKey.SUBF2M,
            CatalogueSourceKey.SUBTITLECAT,
        )

        private val SUBDL_VERIFIED_HUMAN_AUTHORS = listOf(
            "naveshta", "netflix", "amzn", "amazon", "prime", "zee5", "sonyliv",
            "tapmad", "tamasha", "faizybaba", "moorninja", "usmankhadim", "h18o9",
            "sunil kumar", "urdu_official", "malik_rehan", "shabbir_umer", "urdu zone",
        )

        private val SEASON_ORDINALS = mapOf(
            1 to "first-season",
            2 to "second-season",
            3 to "third-season",
            4 to "fourth-season",
            5 to "fifth-season",
            6 to "sixth-season",
            7 to "seventh-season",
            8 to "eighth-season",
            9 to "ninth-season",
            10 to "tenth-season",
            11 to "eleventh-season",
            12 to "twelfth-season",
            13 to "thirteenth-season",
            14 to "fourteenth-season",
            15 to "fifteenth-season",
            16 to "sixteenth-season",
            17 to "seventeenth-season",
            18 to "eighteenth-season",
            19 to "nineteenth-season",
            20 to "twentieth-season",
            21 to "twenty-first-season",
            22 to "twenty-second-season",
            23 to "twenty-third-season",
            24 to "twenty-fourth-season",
            25 to "twenty-fifth-season",
        )

        private val IMDB_ID_REGEX = Regex("""tt\d{5,10}""")
        private val PAREN_YEAR_REGEX = Regex("""\((\d{4})\)""")
        private val STANDALONE_YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        private val SUBDL_DATA_LANG_ATTR_REGEX = Regex("""data-language\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val SUBDL_ZIP_PATH_REGEX = Regex("""https?://dl\.subdl\.com/subtitle/\d+-\d+\.zip|/subtitle/\d+-\d+\.zip""", RegexOption.IGNORE_CASE)
        private val SUBDL_AI_MARKER_REGEX = Regex(
            """(?i)(?:data-ai-translated|data-ai-row|data-ai-subtitles|\bsubdl[\s_]*ai\b|"ai_translated"\s*:\s*true|\bai[\s_]*translated\b|\bauto[\s_]*translated\b|\bmachine[\s_]*translated\b)"""
        )
        private val SUBTITLECAT_ORIG_SRT_REGEX = Regex(
            """(?i)(?:https?://www\.subtitlecat\.com)?/subs/\d+/[^"'\s<>]+?-orig\.srt"""
        )
        private val SUBTITLECAT_TRANSLATE_FOLDER_3ARG_REGEX = Regex(
            """(?i)translate_from_server_folder\s*\(\s*['"][^'"]+['"]\s*,\s*['"]([^'"]+?)['"]\s*,\s*['"]([^'"]*?/subs/\d+/?)['"]\s*\)"""
        )
        private val SUBTITLECAT_TRANSLATE_FOLDER_2ARG_REGEX = Regex(
            """(?i)translate_from_server_folder\s*\(\s*['"][^'"]+['"]\s*,\s*['"]((?:https?://www\.subtitlecat\.com)?/subs/\d+/[^'"]+?)['"]\s*\)"""
        )
        private val SUBTITLECAT_UR_SUFFIX_REPLACE_REGEX = Regex("""(?i)-(?:ur|urd)\.srt$""")
        private val SUBSOURCE_DOWNLOAD_TOKEN_REGEX = Regex(
            """(?i)"download_token"\s*:\s*"([A-Za-z0-9._\-]{8,})""""
        )
        private val SUBSOURCE_RELEASE_WINDOW_REGEX = Regex(
            """(?i)"(?:release_info|releaseInfo|release_name)"\s*:\s*"([^"]{2,180})""""
        )
        private val QUEBEC_FALSE_URDU_REGEX = Regex(
            """(?i)\b(?:quebec|french[\s._-]*canadian|fr[\s._-]*ca|vfq|vff|truefrench)\b"""
        )
        private val DEAD_STREMIO_SUBS_V1_HOST_REGEX = Regex(
            """(?i)^https?://subs-v1\.strem\.io(?=/|$)"""
        )
        private val NON_ALNUM_RELEASE_REGEX = Regex("""[^a-z0-9]+""")

        fun rewriteDeadStremioSubtitleUrl(url: String): String {
            val trimmed = url.trim()
            if (!DEAD_STREMIO_SUBS_V1_HOST_REGEX.containsMatchIn(trimmed)) return trimmed
            return DEAD_STREMIO_SUBS_V1_HOST_REGEX.replace(trimmed, "https://subs5.strem.io")
        }

        internal fun extractSubSourceJsonObjects(text: String): List<String> {
            val results = ArrayList<String>()
            var i = 0
            val len = text.length
            while (i < len) {
                if (text[i] != '{') {
                    i++
                    continue
                }
                val start = i
                var depth = 0
                var inQuote = false
                var escaped = false
                var end = -1
                var j = i
                while (j < len && j - start <= 4_096) {
                    val ch = text[j]
                    if (inQuote) {
                        if (escaped) {
                            escaped = false
                        } else if (ch == '\\') {
                            escaped = true
                        } else if (ch == '"') {
                            inQuote = false
                        }
                    } else {
                        when (ch) {
                            '"' -> inQuote = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    end = j
                                    break
                                }
                            }
                        }
                    }
                    j++
                }
                if (end > start) {
                    val candidate = text.substring(start, end + 1)
                    if ((candidate.contains("\"id\"") || candidate.contains("\"subtitle_id\"") || candidate.contains("\"subtitleId\"")) &&
                        (candidate.contains("\"link\"") || candidate.contains("\"release_info\"") || candidate.contains("\"releases\"") || candidate.contains("\"releaseInfo\""))
                    ) {
                        results.add(candidate)
                        i = end + 1
                        continue
                    }
                }
                i++
            }
            return results
        }

        fun subDlSeasonOrdinal(season: Int): String? = SEASON_ORDINALS[season]

        internal fun normalizeReleaseText(raw: String): String =
            NON_ALNUM_RELEASE_REGEX.replace(raw.lowercase(Locale.ROOT), " ").trim()

        internal fun explicitEpisodeMatches(normalizedOrRaw: String, season: Int?, episode: Int?): Boolean {
            if (episode == null || episode <= 0) return true
            if (season != null && season >= 0) {
                val sxxExx = Regex(
                    """(?i)(?:^|[^a-z0-9])(?:s|season[\s._-]*)0*${season}[\s._-]*(?:e|ep|episode[\s._-]*)0*${episode}(?!\d)"""
                )
                val cross = Regex("""(?i)(?:^|[^0-9])0*${season}x0*${episode}(?!\d)""")
                if (sxxExx.containsMatchIn(normalizedOrRaw) || cross.containsMatchIn(normalizedOrRaw)) {
                    return true
                }
                if (season == 0) {
                    return false
                }
            }
            return Regex("""(?i)(?:^|[^0-9a-z])(?:e|ep|episode[\s._-]*)0*${episode}(?![0-9])""")
                .containsMatchIn(normalizedOrRaw)
        }

        internal fun episodeTextMatches(normalizedOrRaw: String, season: Int?, episode: Int?): Boolean {
            if (explicitEpisodeMatches(normalizedOrRaw, season, episode)) return true
            if (season == 0) return false
            return Regex("""(?i)(?:^|(?<!\d)\.|[^0-9a-z.])0*${episode}(?![0-9])""")
                .containsMatchIn(normalizedOrRaw)
        }

        internal fun explicitEpisodeOrPackMatches(text: String, season: Int?, episode: Int?): Boolean {
            if (episode == null || episode <= 0) return true
            if (text.isBlank()) return false
            if (explicitEpisodeMatches(text, season, episode)) return true
            val lower = text.lowercase(Locale.ROOT)
            return lower.contains("complete") || lower.contains("full season") || lower.contains("season pack")
        }

        internal fun episodeTextOrPackMatches(text: String, season: Int?, episode: Int?): Boolean {
            if (episode == null || episode <= 0 || text.isBlank()) return true
            if (episodeTextMatches(text, season, episode)) return true
            val lower = text.lowercase(Locale.ROOT)
            return lower.contains("complete") || lower.contains("full season") || lower.contains("season pack")
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun resolveRelativeUrl(host: String, hrefOrUrl: String): String {
            val trimmed = hrefOrUrl.trim()
            return when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                trimmed.contains("://") || trimmed.startsWith("file:", ignoreCase = true) ||
                    trimmed.startsWith("javascript:", ignoreCase = true) ||
                    trimmed.startsWith("data:", ignoreCase = true) -> ""
                trimmed.startsWith("/") -> "${host.trimEnd('/')}$trimmed"
                else -> "${host.trimEnd('/')}/$trimmed"
            }
        }

        private fun JsonNode.firstNonBlank(vararg fieldNames: String): String? {
            for (name in fieldNames) {
                val node = path(name)
                if (!node.isMissingNode && !node.isNull) {
                    val txt = node.asText("").trim()
                    if (txt.isNotEmpty() && !txt.equals("null", ignoreCase = true)) {
                        return txt
                    }
                }
            }
            return null
        }
    }
}
