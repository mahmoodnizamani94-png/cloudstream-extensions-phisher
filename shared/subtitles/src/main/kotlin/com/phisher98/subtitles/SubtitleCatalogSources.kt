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
        return Result.failure(UnsupportedOperationException("POST not implemented for $url"))
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
    }
}

class DefaultCatalogHttpTransport : CatalogHttpTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<CatalogHttpResponse> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
            !cleanUrl.startsWith("http://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported catalog HTTP URL scheme: $cleanUrl")
            )
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
                    val resp = app.get(cleanUrl, headers = reqHeaders, timeout = timeoutSec)
                    val bytes = resp.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                    val outHeaders = LinkedHashMap<String, String>()
                    for (name in resp.headers.names()) {
                        resp.headers[name]?.let { outHeaders[name] = it }
                    }
                    CatalogHttpResponse(
                        statusCode = resp.code,
                        bodyBytes = bytes,
                        finalUrl = resp.url,
                        headers = outHeaders,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
            }
            if (niceResp != null) {
                return@withContext if (niceResp.isSuccess) {
                    Result.success(niceResp)
                } else {
                    Result.failure(IllegalStateException("HTTP ${niceResp.statusCode} for $cleanUrl"))
                }
            }

            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs < 400L) {
                return@withContext Result.failure(
                    SocketTimeoutException("Catalog GET budget exhausted (${effectiveBudgetMs}ms) for $cleanUrl")
                )
            }

            val conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                readTimeout = remainingMs.coerceIn(400L, 15_000L).toInt()
                reqHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { readBoundedStream(it, deadlineNs) } ?: ByteArray(0)
                val resolvedUrl = conn.url?.toString() ?: cleanUrl
                if (code !in 200..299 || bytes.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("HTTP $code for $cleanUrl"))
                }
                Result.success(CatalogHttpResponse(statusCode = code, bodyBytes = bytes, finalUrl = resolvedUrl))
            } finally {
                conn.disconnect()
            }
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
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("https://", ignoreCase = true) &&
            !cleanUrl.startsWith("http://", ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalArgumentException("Unsupported catalog POST URL scheme: $cleanUrl")
            )
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
                    val resp = app.post(cleanUrl, headers = reqHeaders, data = formFields, timeout = timeoutSec)
                    val bytes = resp.body.byteStream().use { readBoundedStream(it, deadlineNs) }
                    CatalogHttpResponse(
                        statusCode = resp.code,
                        bodyBytes = bytes,
                        finalUrl = resp.url,
                    )
                } catch (ce: CancellationException) {
                    throw ce
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
    val subSenseBaseUrl: String = "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles",
    val subf2mHost: String = "https://subf2m.co",
    val ytsSubsHost: String = "https://www.yts-subs.com",
    val ytsSubsZipHost: String = "https://subtitles.yts-subs.com",
    val yifyHost: String = "https://yifysubtitles.ch",
    val addic7edHost: String = "https://www.addic7ed.com",
    val subtitleCatHost: String = "https://www.subtitlecat.com",
)

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
    ): CatalogDiscoveryResult = coroutineScope {
        if (languages.isEmpty() || enabledSources.isEmpty()) {
            return@coroutineScope CatalogDiscoveryResult(emptyList(), emptyList())
        }

        val rejections = Collections.synchronizedList(ArrayList<VerifiedSubtitleCandidate.Rejected>())
        val admitted = Collections.synchronizedList(ArrayList<CatalogDiscoveredCandidate>())

        if (spec is MediaLookupSpec.Episode && spec.coordinate is EpisodeCoordinate.UnverifiedAnimeCour) {
            if (CatalogueSourceKey.SUBTITLECAT in enabledSources) {
                querySubtitleCat(spec, languages, admitted, rejections)
            }
            return@coroutineScope finalizeDiscovery(admitted, rejections)
        }

        val tasks = buildList {
            if (CatalogueSourceKey.WYZIE in enabledSources) {
                add(async { queryWyzie(spec, languages, admitted, rejections) })
            }
            if (CatalogueSourceKey.SUBDL in enabledSources) {
                add(async { querySubDl(spec, languages, admitted, rejections) })
            }
            if (CatalogueSourceKey.SUBSOURCE in enabledSources) {
                add(async { querySubSource(spec, languages, admitted, rejections) })
            }
            if (CatalogueSourceKey.SUBSENSE in enabledSources && SubtitleLanguage.URDU in languages) {
                add(async { querySubSense(spec, admitted, rejections) })
            }
            if (CatalogueSourceKey.SUBF2M in enabledSources) {
                add(async { querySubf2m(spec, languages, admitted, rejections) })
            }
            if (CatalogueSourceKey.OPENSUBTITLES in enabledSources) {
                add(async { queryOpenSubtitlesLegacyRest(spec, languages, admitted, rejections) })
            }
            val wantsStremioV3 = CatalogueSourceKey.STREMIO in enabledSources
            val wantsSubtitleApi = CatalogueSourceKey.SUBTITLE_API in enabledSources
            if (wantsStremioV3 || wantsSubtitleApi) {
                if (wantsStremioV3 && wantsSubtitleApi &&
                    endpoints.openSubtitlesStremioHost.trimEnd('/') == endpoints.subtitleApiHost.trimEnd('/')
                ) {
                    add(async {
                        queryStremioCompatibleIndex(
                            baseHost = endpoints.subtitleApiHost,
                            sourceKey = CatalogueSourceKey.SUBTITLE_API,
                            spec = spec,
                            languages = languages,
                            admitted = admitted,
                            rejections = rejections,
                        )
                    })
                } else {
                    if (wantsSubtitleApi) {
                        add(async {
                            queryStremioCompatibleIndex(
                                baseHost = endpoints.subtitleApiHost,
                                sourceKey = CatalogueSourceKey.SUBTITLE_API,
                                spec = spec,
                                languages = languages,
                                admitted = admitted,
                                rejections = rejections,
                            )
                        })
                    }
                    if (wantsStremioV3) {
                        add(async {
                            queryStremioCompatibleIndex(
                                baseHost = endpoints.openSubtitlesStremioHost,
                                sourceKey = CatalogueSourceKey.STREMIO,
                                spec = spec,
                                languages = languages,
                                admitted = admitted,
                                rejections = rejections,
                            )
                        })
                    }
                }
            }
            if (CatalogueSourceKey.YIFY in enabledSources && spec is MediaLookupSpec.Movie) {
                add(async { queryYifyAndYts(spec, languages, admitted, rejections) })
            }
            if (CatalogueSourceKey.ADDIC7ED in enabledSources &&
                SubtitleLanguage.ENGLISH in languages &&
                spec is MediaLookupSpec.Episode &&
                spec.coordinate is EpisodeCoordinate.VerifiedSeasonEpisode
            ) {
                add(async { queryAddic7ed(spec, spec.coordinate, admitted, rejections) })
            }
            if (CatalogueSourceKey.SUBTITLECAT in enabledSources) {
                add(async { querySubtitleCat(spec, languages, admitted, rejections) })
            }
        }
        tasks.awaitAll()
        finalizeDiscovery(admitted, rejections)
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
        val key = apiKeys.wyzieApiKey?.takeIf { it.isNotBlank() } ?: return
        val lookupId = cleanImdbOrNull(spec.imdbId) ?: spec.tmdbId?.takeIf { it > 0 }?.toString() ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return

        val baseParams = if (se != null && se.first >= 0) {
            "id=${urlEncode(lookupId)}&season=${se.first}&episode=${se.second}"
        } else {
            "id=${urlEncode(lookupId)}"
        }
        val suffix = "&source=all&key=${urlEncode(key)}"
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
        for (lang in remainingLangs) {
            val langPath = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val pageUrl = "${endpoints.subDlWebHost.trimEnd('/')}$slugPath$seasonSegment/$langPath"
            val pageResp = transport.get(pageUrl, webHeaders).getOrNull() ?: continue
            parseAndGateSubDlHtmlPage(pageResp.textUtf8, spec, lang, se, admitted, rejections)
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
                if (itemEpisode > 0 && itemEpisode != se.second && !episodeTextOrPackMatches(releaseName, se.first, se.second)) {
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
            if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections)) {
                if (lang == SubtitleLanguage.URDU) urCount++ else enCount++
            }
        }
        return urCount to enCount
    }

    private fun parseAndGateSubDlHtmlPage(
        htmlBody: String,
        spec: MediaLookupSpec,
        language: SubtitleLanguage,
        se: Pair<Int, Int>?,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        if (htmlBody.isBlank()) return
        val doc = Jsoup.parse(htmlBody)
        val dlHeaders = mapOf(
            "User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA,
            "Referer" to "${endpoints.subDlWebHost.trimEnd('/')}/",
        ) + ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))

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
                        if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections)) {
                            admittedCount++
                        }
                    }
                }
            }
        }
        if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return

        val dataLangElements = doc.select("[data-language]")
        val hasRawDataLang = SUBDL_DATA_LANG_ATTR_REGEX.containsMatchIn(htmlBody)
        val searchRoots = if (dataLangElements.isNotEmpty() || hasRawDataLang) {
            val matching = dataLangElements.filter { el ->
                SubtitleLanguage.fromTagOrName(el.attr("data-language")) == language
            }
            if (matching.isEmpty()) return
            matching
        } else {
            listOf(doc)
        }

        for (root in searchRoots) {
            for (anchor in root.select("a[href]")) {
                if (admittedCount >= MAX_CANDIDATES_PER_SOURCE_LANG) return
                val href = anchor.attr("href").trim()
                val zipMatch = SUBDL_ZIP_PATH_REGEX.find(href) ?: continue
                val fullUrl = resolveRelativeUrl(endpoints.subDlDownloadHost, zipMatch.value)

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
                val hi = row?.selectFirst("[data-hi=true], .hi-badge, .hearing-impaired") != null
                val meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = language,
                    titleOrReleaseText = rowText.ifBlank { fullUrl.substringAfterLast('/') },
                    uploaderName = author,
                    isHearingImpairedFlag = hi,
                    isMachineTranslatedFlag = mt,
                )
                if (tryAdmitPreDownload(meta, spec, fullUrl, dlHeaders, admitted, rejections)) {
                    admittedCount++
                }
            }
        }
    }


    private suspend fun querySubSource(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val key = apiKeys.subSourceApiKey?.takeIf { it.isNotBlank() } ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return
        val cleanImdb = cleanImdbOrNull(spec.imdbId)
        val primaryTitle = spec.titles.firstOrNull { it.isNotBlank() }?.trim()
        if (cleanImdb == null && primaryTitle == null) return

        val slugYear = catalogSlugYearFor(spec)
        val seasonAirYear = (spec as? MediaLookupSpec.Episode)?.seasonAirYear
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
                if (itemEpisode > 0 && itemEpisode != se.second && !episodeTextOrPackMatches(releaseText, se.first, se.second)) {
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
            if (tryAdmitPreDownload(meta, spec, resolvedUrl, effectiveDlHeaders, admitted, rejections)) {
                count++
            }
        }
        return count
    }


    private suspend fun querySubSense(
        spec: MediaLookupSpec,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val cleanImdb = cleanImdbOrNull(spec.imdbId) ?: return
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return

        val url = if (se != null && se.first >= 0) {
            "${endpoints.subSenseBaseUrl.trimEnd('/')}/series/$cleanImdb:${se.first}:${se.second}.json"
        } else {
            "${endpoints.subSenseBaseUrl.trimEnd('/')}/movie/$cleanImdb.json"
        }
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)
        val resp = transport.get(url, headers).getOrNull() ?: return
        val root = runCatching { mapper.readTree(resp.textUtf8) }.getOrNull() ?: return
        val arr = root.path("subtitles")
        if (!arr.isArray) return

        val dlHeaders = headers + ProvenanceCodec.encodeHeaders(
            ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSENSE)
        )
        var count = 0
        for (obj in arr) {
            if (count >= MAX_CANDIDATES_PER_SOURCE_LANG) break
            val lang = SubtitleLanguage.fromTagOrName(obj.firstNonBlank("lang", "language")) ?: continue
            if (lang != SubtitleLanguage.URDU) continue
            val subUrl = obj.firstNonBlank("url") ?: continue
            val labelText = listOfNotNull(
                obj.firstNonBlank("label"),
                obj.firstNonBlank("title"),
                obj.firstNonBlank("name"),
            ).joinToString(" ").trim()

            val hi = obj.path("hi").asBoolean(false) ||
                obj.path("hearingImpaired").asBoolean(false) ||
                labelText.contains("· HI", ignoreCase = true)
            val mt = obj.path("ai_translated").asBoolean(false) ||
                obj.path("machine_translated").asBoolean(false)

            val meta = CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBSENSE,
                language = SubtitleLanguage.URDU,
                titleOrReleaseText = labelText.ifBlank { subUrl.substringAfterLast('/') },
                uploaderName = obj.firstNonBlank("author", "uploader"),
                isHearingImpairedFlag = hi,
                isMachineTranslatedFlag = mt,
            )
            if (tryAdmitPreDownload(meta, spec, subUrl, dlHeaders, admitted, rejections)) {
                count++
            }
        }
    }


    private suspend fun querySubf2m(
        spec: MediaLookupSpec,
        languages: Set<SubtitleLanguage>,
        admitted: MutableList<CatalogDiscoveredCandidate>,
        rejections: MutableList<VerifiedSubtitleCandidate.Rejected>,
    ) {
        val se = verifiedSeasonEpisodeOrNull(spec)
        if (spec is MediaLookupSpec.Episode && se == null) return
        val primaryTitle = spec.titles.firstOrNull { it.isNotBlank() }?.trim() ?: return
        val slugYear = catalogSlugYearFor(spec)
        val seasonAirYear = (spec as? MediaLookupSpec.Episode)?.seasonAirYear
        val isMovie = spec is MediaLookupSpec.Movie
        val headers = mapOf("User-Agent" to DefaultCatalogHttpTransport.DEFAULT_BROWSER_UA)

        val slug = resolveSubf2mSlug(
            title = primaryTitle,
            expectedPremiereYear = slugYear,
            fallbackSeasonYear = seasonAirYear,
            season = se?.first,
            isMovie = isMovie,
            headers = headers,
        ) ?: return

        for (lang in languages) {
            val langSegment = if (lang == SubtitleLanguage.URDU) "urdu" else "english"
            val listUrl = "${endpoints.subf2mHost.trimEnd('/')}/subtitles/$slug/$langSegment"
            val listResp = transport.get(listUrl, headers).getOrNull() ?: continue
            val detailRows = parseSubf2mDetailRows(listResp.textUtf8, slug, langSegment, se)
            var count = 0
            for (row in detailRows) {
                if (count >= 2) break
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
                val detailResp = transport.get(row.detailUrl, headers).getOrNull() ?: continue
                val dlUrl = parseSubf2mDownloadHref(detailResp.textUtf8) ?: continue
                val dlHeaders = headers + mapOf("Referer" to row.detailUrl) +
                    ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBF2M))
                if (tryAdmitPreDownload(preMeta, spec, dlUrl, dlHeaders, admitted, rejections)) {
                    count++
                }
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
        val seasonOrdinalWords = season?.let(::subDlSeasonOrdinal)?.replace('-', ' ')
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
            if (normCandidate != wantedTitle && !normCandidate.startsWith("$wantedTitle ")) continue

            if (!isMovie && seasonOrdinalWords != null && normCandidate.contains(seasonOrdinalWords)) {
                return slug
            }
            if (fallbackBaseSlug == null) {
                fallbackBaseSlug = slug
            }
        }
        return fallbackBaseSlug
    }

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
        se: Pair<Int, Int>?,
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
            val rowText = (row?.text() ?: anchor.text()).trim()
            if (se != null && !episodeTextMatches(normalizeReleaseText(rowText), se.first, se.second)) continue

            val author = row?.selectFirst(".by a, a[href*=/u/], .author")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val comment = row?.selectFirst(".comment, .sub-comment")?.text()?.trim()?.takeIf { it.isNotEmpty() }
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

            val subUrl = sub.firstNonBlank("url") ?: continue
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

        val ytsUrl = "${endpoints.ytsSubsHost.trimEnd('/')}/movie-imdb/$cleanImdb"
        val ytsResp = transport.get(ytsUrl, headers).getOrNull()
        var urAdmitted = 0
        var enAdmitted = 0
        if (ytsResp != null) {
            val counts = parseAndGateYifyTable(
                htmlBody = ytsResp.textUtf8,
                spec = spec,
                languages = languages,
                pageHost = endpoints.ytsSubsHost,
                isYtsMirror = true,
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

        val yifyUrl = "${endpoints.yifyHost.trimEnd('/')}/movie-imdb/$cleanImdb"
        val yifyResp = transport.get(yifyUrl, headers).getOrNull() ?: return
        parseAndGateYifyTable(
            htmlBody = yifyResp.textUtf8,
            spec = spec,
            languages = missingLanguages,
            pageHost = endpoints.yifyHost,
            isYtsMirror = false,
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

    private fun parseSubtitleCatOrigLink(detailHtml: String, baseHost: String): String? {
        val match = SUBTITLECAT_ORIG_SRT_REGEX.find(detailHtml) ?: return null
        val rawPath = match.value.trim()
        return resolveRelativeUrl(baseHost, rawPath).replace(" ", "%20")
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
        private const val MAX_CANDIDATES_PER_SOURCE_LANG = 3
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
        private val SUBTITLECAT_UR_SUFFIX_REPLACE_REGEX = Regex("""(?i)-(?:ur|urd)\.srt$""")
        private val NON_ALNUM_RELEASE_REGEX = Regex("""[^a-z0-9]+""")

        fun subDlSeasonOrdinal(season: Int): String? = SEASON_ORDINALS[season]

        internal fun normalizeReleaseText(raw: String): String =
            NON_ALNUM_RELEASE_REGEX.replace(raw.lowercase(Locale.ROOT), " ").trim()

        internal fun episodeTextMatches(normalizedOrRaw: String, season: Int?, episode: Int?): Boolean {
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
            return Regex("""(?i)(?:^|[^0-9a-z])(?:e|ep|episode[\s._-]*)?0*${episode}(?![0-9])""")
                .containsMatchIn(normalizedOrRaw)
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
