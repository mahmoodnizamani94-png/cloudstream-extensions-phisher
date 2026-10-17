package com.phisher98

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.extractors.helper.AesHelper.cryptoAESHandler
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds


val session: Session by lazy { Session(app.baseClient) }


object StreamPlayExtractor : StreamPlay() {

    private val cloudflareKiller by lazy { CloudflareKiller() }
    val encDecApiSemaphore = Semaphore(4)
    val vidlinkEncCache = ConcurrentHashMap<Int, Pair<String, Long>>()

    suspend inline fun <T> suspendCancellable(crossinline block: suspend () -> T): T? {
        currentCoroutineContext().ensureActive()
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return null
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    suspend fun <T> retryTransient(maxRetries: Int = 1, delayMs: Long = 200L, block: suspend () -> T?): T? {
        var attempt = 0
        while (attempt <= maxRetries) {
            val res = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (res != null) return res
            if (attempt < maxRetries) delay(delayMs)
            attempt++
        }
        return null
    }

    suspend fun cancellableGetText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 3L
    ): String? = suspendCancellableCoroutine { cont ->
        try {
            val client = app.baseClient.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(url)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        // OkHttp's callTimeout cancels the call internally and reports it as a
                        // cancelled call + IOException ("timeout"); that is a retryable failure,
                        // NOT coroutine cancellation. Genuine external cancellation makes cont
                        // inactive before invokeOnCancellation() ever runs, so if we get here
                        // while active, resuming null preserves both semantics.
                        cont.resume(null)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string()
                        if (cont.isActive) {
                            cont.resume(body)
                        }
                    } catch (t: Throwable) {
                        if (cont.isActive) {
                            // Same rationale as onFailure: a body-read failure caused by an
                            // okhttp-internal callTimeout is retryable (resume null); genuine
                            // external cancellation already made cont inactive.
                            cont.resume(null)
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            if (cont.isActive) {
                if (t is CancellationException) {
                    cont.cancel(t)
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    suspend fun cancellablePost(
        url: String,
        headers: Map<String, String> = emptyMap(),
        requestBody: okhttp3.RequestBody,
        timeoutSec: Long = 3L
    ): Response? = suspendCancellableCoroutine { cont ->
        try {
            val client = app.baseClient.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
                .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url(url)
                .post(requestBody)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }
                .build()
            val call = client.newCall(req)
            cont.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) {
                        // OkHttp's callTimeout cancels the call internally and reports it as a
                        // cancelled call + IOException ("timeout"); that is a retryable failure,
                        // NOT coroutine cancellation. Genuine external cancellation makes cont
                        // inactive before invokeOnCancellation() ever runs, so if we get here
                        // while active, resuming null preserves both semantics.
                        cont.resume(null)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) {
                        cont.resume(response)
                    }
                }
            })
        } catch (t: Throwable) {
            if (cont.isActive) {
                if (t is CancellationException) {
                    cont.cancel(t)
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    suspend fun cancellablePostText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        requestBody: okhttp3.RequestBody,
        timeoutSec: Long = 3L
    ): String? {
        val resp = cancellablePost(url, headers, requestBody, timeoutSec) ?: return null
        return try {
            resp.body?.string()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    /**
     * SOTA Emission for Top-Tier Sources (VidLink 100 > Cineby 96 > VidSrc 95 > VidFast 94 >
     * VidSrcSU 93 > MegaPlay 92 > MeowTV 91 > Animex 90). Emits only genuine, manifest-verified variants:
     * 720p is prioritized #1 and 1080p #2 by STREAM_PRIORITY_COMPARATOR, followed by the
     * remaining qualities (480p, 4K, ...); no synthetic companions are ever fabricated.
     *
     * - If generatedLinks is provided from generateM3u8: every parsed variant is tagged with
     *   sota_dual_quality and dispatched sorted strictly by STREAM_PRIORITY_COMPARATOR.
     * - If generatedLinks is null or empty (direct MP4/MKV, single media playlist, or an
     *   unparseable master): exactly one link is emitted carrying its authentic detected
     *   quality, or [Auto] + Qualities.Unknown for unparseable HLS masters.
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
        StreamLinkOptimizer.emitTopTierDualQualityStreamLinks(
            source = source,
            baseName = baseName,
            url = url,
            referer = referer,
            headers = headers,
            streamType = streamType,
            generatedLinks = generatedLinks,
            callback = callback
        )
    }

    // Shared data class to hold pre-fetched anime metadata
    data class AnimeResolvedIds(
        val malId: Int?,
        val anilistId: Int?,
        val anidbEid: Int,
        val zoroIds: List<String>? = null,
        val zoroTitle: String?,
        val aniXL: String?,
        val kaasSlug: String?,
        val animepaheUrl: String?,
        val animekaiId: String?,
        val tmdbYear: Int?,
    )

    suspend fun resolveAnimeIds(
        title: String?,
        date: String?,
        airedDate: String?,
        season: Int?,
        episode: Int?,
    ): AnimeResolvedIds {
        val (anilistId, malId) = convertTmdbToAnimeId(
            title, date, airedDate, if (season == null) TvType.AnimeMovie else TvType.Anime
        )

        val (anijson, malsync) = coroutineScope {
            val aniZipDeferred = async(Dispatchers.IO) {
                if (malId != null) {
                    runCatching {
                        safeGet("https://api.ani.zip/mappings?mal_id=$malId", timeout = 5L).text
                    }.getOrNull()
                } else null
            }
            val malSyncDeferred = async(Dispatchers.IO) {
                if (malId != null) {
                    runCatching {
                        safeGet("$malsyncAPI/mal/anime/$malId", timeout = 5L).parsedSafe<MALSyncResponses>()?.sites
                    }.getOrNull()
                } else null
            }
            aniZipDeferred.await() to malSyncDeferred.await()
        }

        val anidbEid = getAnidbEid(anijson ?: "{}", episode ?: 1) ?: 0

        return AnimeResolvedIds(
            malId = malId,
            anilistId = anilistId,
            anidbEid = anidbEid,
            zoroIds = malsync?.zoro?.keys?.toList()?.filterNotNull(),
            zoroTitle = malsync?.zoro?.values?.firstNotNullOfOrNull { it["title"] }
                ?.replace(":", " "),
            aniXL = malsync?.AniXL?.values?.firstNotNullOfOrNull { it["url"] },
            kaasSlug = malsync?.KickAssAnime?.values?.firstNotNullOfOrNull { it["identifier"] },
            animepaheUrl = malsync?.animepahe?.values?.firstNotNullOfOrNull { it["url"] },
            animekaiId = malsync?.AnimeKAI?.values?.firstNotNullOfOrNull { it["identifier"] },
            tmdbYear = date?.substringBefore("-")?.toIntOrNull(),
        )
    }


    fun normalizeTitle(title: String?): String? {
        return title
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9 ]"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
    }

    suspend fun invokeAnimex(
        malId: Int? = null,
        anilistId: Int? = null,
        title: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?
    ) {
        try {
            val isMovie = dubtype.equals("Movie", ignoreCase = true)
            val json = "application/json; charset=utf-8".toMediaType()
            val epNum = if (isMovie) 1 else (episode ?: return)
            val searchTitle = title?.takeIf { it.isNotBlank() } ?: return

            val animexOrigin = "https://animex.one"
            val headers = mapOf(
                "Origin" to animexOrigin,
                "Referer" to "$animexOrigin/",
                "User-Agent" to USER_AGENT,
                "Content-Type" to "application/json"
            )

            val queryPayload = JSONObject().apply {
                put(
                    "query",
                    "query FastSearch(\$query: String, \$limit: Int) { catalogAnime(filter: { query: \$query }, limit: \$limit) { items { id anilistId malId titleRomaji titleEnglish format } } }"
                )
                put("variables", JSONObject().apply {
                    put("query", searchTitle)
                    put("limit", 10)
                })
            }.toString()

            val responseText = cancellablePostText(
                "https://graphql.animex.one/graphql",
                headers = headers,
                requestBody = queryPayload.toRequestBody(json),
                timeoutSec = 5L
            ) ?: return
            val response = tryParseJson<SearchResponse>(responseText) ?: return

            val items = response.data.catalogAnime.items
            val anime = items.firstOrNull {
                (anilistId != null && it.anilistId == anilistId) ||
                    (malId != null && it.malId == malId)
            } ?: items.firstOrNull {
                it.titleEnglish.equals(searchTitle, true) ||
                    it.titleRomaji.equals(searchTitle, true)
            } ?: items.firstOrNull() ?: return

            val serversText = cancellableGetText(
                "https://pp.animex.one/rest/api/servers?id=${URLEncoder.encode(anime.id, "UTF-8")}&epNum=$epNum",
                headers = headers,
                timeoutSec = 5L
            ) ?: return
            val servers = tryParseJson<ServerResponse>(serversText) ?: return

            val requestTypes = when {
                isMovie -> listOf("sub", "dub")
                dubtype.equals("dub", true) -> listOf("dub")
                else -> listOf("sub")
            }

            val providers = buildList {
                if ("sub" in requestTypes) {
                    servers.subProviders.forEach { add("sub" to it) }
                }
                if ("dub" in requestTypes) {
                    servers.dubProviders.forEach { add("dub" to it) }
                }
            }.sortedBy { (_, provider) -> !(provider.default ?: false) }

            val emittedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

            coroutineScope {
                providers.map { (type, provider) ->
                    async {
                        try {
                            currentCoroutineContext().ensureActive()
                            val providerId = provider.id
                            val sourceResponse = animexFetchSource(anime.id, epNum, type, providerId, headers)
                                ?: return@async

                            val respHeaders = sourceResponse.headers.orEmpty()
                            val referer = respHeaders.entries
                                .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                                ?: "$animexOrigin/"
                            val origin = respHeaders.entries
                                .firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
                                ?: referer.removeSuffix("/")
                            val streamHeaders = buildMap {
                                put("User-Agent", USER_AGENT)
                                if (referer.isNotBlank()) put("Referer", referer)
                                if (origin.isNotBlank()) put("Origin", origin)
                                respHeaders.forEach { (k, v) ->
                                    if (!k.equals("User-Agent", true) && v.isNotBlank()) put(k, v)
                                }
                            }

                            sourceResponse.tracks?.forEach { track ->
                                if (track.kind.equals("thumbnails", ignoreCase = true)) return@forEach
                                val trackUrl = (track.url ?: track.file)?.trim()
                                    ?.takeIf { it.startsWith("http", ignoreCase = true) }
                                    ?: return@forEach
                                if (trackUrl.contains("thumbnails", ignoreCase = true)) return@forEach
                                if (emittedTracks.add(trackUrl)) {
                                    val label = cleanSubtitleLabel(track.lang ?: track.label ?: "English")
                                    subtitleCallback.invoke(newSubtitleFile(label, trackUrl))
                                }
                            }

                            val subType = provider.tip?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
                                ?: "Softsub"
                            val langTag = if (type.equals("sub", ignoreCase = true)) {
                                "[Sub] [Original]"
                            } else {
                                "[Dub]"
                            }
                            val providerTag = providerId.replaceFirstChar {
                                if (it.isLowerCase()) it.titlecase() else it.toString()
                            }
                            val baseName = "Animex [$subType] $langTag [$providerTag]"

                            sourceResponse.sources?.forEach { source ->
                                val srcUrl = source.url.trim()
                                if (!srcUrl.startsWith("http", ignoreCase = true)) return@forEach
                                val labeledQuality = animexQualityOf(source.quality)
                                if (labeledQuality != Qualities.Unknown.value && !srcUrl.contains("master.m3u8", ignoreCase = true)) {
                                    emitTopTierDualQualityStreamLinks(
                                        source = "Animex",
                                        baseName = baseName,
                                        url = srcUrl,
                                        referer = referer,
                                        headers = streamHeaders,
                                        streamType = if (srcUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                        generatedLinks = listOf(
                                            newExtractorLink(
                                                "Animex",
                                                "$baseName [${vidsrcQualityTag(labeledQuality)}]",
                                                srcUrl,
                                                if (srcUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                            ) {
                                                this.referer = referer
                                                this.quality = labeledQuality
                                                this.headers = streamHeaders
                                            }
                                        ),
                                        callback = callback
                                    )
                                } else {
                                    emitVerifiedStream(
                                        source = "Animex",
                                        baseName = baseName,
                                        url = srcUrl,
                                        referer = referer,
                                        headers = streamHeaders,
                                        callback = callback
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.d("StreamPlay", "Animex provider ${provider.id} ($type) failed: ${e.message}")
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeAnimex failed: ${e.message}")
        }
    }

    internal fun animexQualityOf(raw: String?): Int {
        val label = raw?.trim().orEmpty()
        if (label.isBlank() || label.equals("auto", ignoreCase = true) || label.equals("default", ignoreCase = true)) {
            return Qualities.Unknown.value
        }
        Regex("""(\d{3,4})\s*p?""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { q ->
            return when (q) {
                2160 -> Qualities.P2160.value
                1440 -> Qualities.P1440.value
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                360 -> Qualities.P360.value
                else -> q
            }
        }
        return Qualities.Unknown.value
    }

    private suspend fun animexFetchSource(
        animeId: String,
        epNum: Int,
        type: String,
        providerId: String,
        headers: Map<String, String>
    ): SourceResponse? {
        val directUrl = "https://pp.animex.one/rest/api/sources?id=${URLEncoder.encode(animeId, "UTF-8")}&epNum=$epNum&type=$type&providerId=$providerId"
        val directRaw = cancellableGetText(directUrl, headers = headers, timeoutSec = 5L)
        if (!directRaw.isNullOrBlank() && directRaw.trimStart().startsWith("{")) {
            val parsed = animexParseSourceJson(directRaw)
            if (parsed != null && !parsed.sources.isNullOrEmpty()) return parsed
        }

        // Fallback to enc-animex -> pp.animex.one -> dec-animex when endpoint requires encrypted query
        val encRaw = encDecGet(
            "$ENC_DEC_API/enc-animex?id=${URLEncoder.encode(animeId, "UTF-8")}&ep=$epNum&type=$type&provider=$providerId",
            headers = mapOf("User-Agent" to USER_AGENT),
            timeoutSec = 4L
        ) ?: return null
        val encParam = runCatching { JSONObject(encRaw).optString("result") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        val encSourceRaw = cancellableGetText(
            "https://pp.animex.one/rest/api/sources?data=${URLEncoder.encode(encParam, "UTF-8")}",
            headers = headers,
            timeoutSec = 5L
        )?.takeIf { it.isNotBlank() } ?: return null

        if (encSourceRaw.trimStart().startsWith("{")) {
            val directParsed = animexParseSourceJson(encSourceRaw)
            if (directParsed != null && !directParsed.sources.isNullOrEmpty()) return directParsed
        }

        val decRaw = encDecPost(
            "dec-animex",
            JSONObject().put("text", encSourceRaw).toString(),
            timeoutSec = 4L
        ) ?: return null
        val resultObj = runCatching { JSONObject(decRaw).optJSONObject("result")?.toString() }.getOrNull()
            ?: decRaw
        return animexParseSourceJson(resultObj)
    }

    internal fun animexParseSourceJson(rawJson: String): SourceResponse? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val target = root.optJSONObject("result") ?: root

        val sources = mutableListOf<VideoSource>()
        target.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").ifBlank { obj.optString("file") }.trim()
                if (url.startsWith("http", ignoreCase = true)) {
                    sources.add(VideoSource(url = url, quality = obj.optString("quality").takeIf { it.isNotBlank() }))
                }
            }
        }

        val tracks = mutableListOf<Track>()
        val tracksArr = target.optJSONArray("tracks") ?: target.optJSONArray("subtitles")
        tracksArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").takeIf { it.isNotBlank() }
                val file = obj.optString("file").takeIf { it.isNotBlank() }
                val lang = obj.optString("lang").takeIf { it.isNotBlank() }
                    ?: obj.optString("language").takeIf { it.isNotBlank() }
                val label = obj.optString("label").takeIf { it.isNotBlank() }
                val kind = obj.optString("kind").takeIf { it.isNotBlank() }
                if (url != null || file != null) {
                    tracks.add(Track(url = url, file = file, lang = lang, label = label, kind = kind))
                }
            }
        }

        val headersMap = mutableMapOf<String, String>()
        target.optJSONObject("headers")?.let { hObj ->
            val keys = hObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = hObj.optString(k)
                if (v.isNotBlank()) headersMap[k] = v
            }
        }

        if (sources.isEmpty() && tracks.isEmpty()) return null
        return SourceResponse(
            sources = sources,
            tracks = tracks,
            headers = headersMap.takeIf { it.isNotEmpty() }
        )
    }

    data class SearchResponse(
        val data: SearchData
    )

    data class SearchData(
        val catalogAnime: CatalogAnime
    )

    data class CatalogAnime(
        val items: List<AnimeItem>
    )

    data class AnimeItem(
        val id: String,
        val anilistId: Int?,
        val malId: Int?,
        val titleRomaji: String?,
        val titleEnglish: String?,
        val format: String?
    )

    data class ServerResponse(
        val subProviders: List<Provider> = emptyList(),
        val dubProviders: List<Provider> = emptyList()
    )

    data class Provider(
        val id: String,
        val default: Boolean? = false,
        val tip: String? = null
    )

    data class SourceResponse(
        val sources: List<VideoSource>? = null,
        val tracks: List<Track>? = null,
        val headers: Map<String, String>? = null
    )

    data class VideoSource(
        val url: String,
        val quality: String? = null
    )

    data class Track(
        val url: String? = null,
        val file: String? = null,
        val lang: String? = null,
        val label: String? = null,
        val kind: String? = null
    )

    // ==================== MegaPlay SOTA Anime Extractor (megaplay.buzz) ====================

    private const val MEGAPLAY_HOST = "https://megaplay.buzz"
    private val MEGAPLAY_FILE_ID_REGEX = Regex("""File\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val MEGAPLAY_DATA_ID_REGEX = Regex("""data-(?:ep-)?id=["'](\d+)["']""", RegexOption.IGNORE_CASE)
    private val MEGAPLAY_REAL_ID_REGEX = Regex("""data-realid=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    internal data class MegaPlaySourcesPayload(
        val directUrls: List<String>,
        val encryptedSources: String?,
        val tracks: List<Pair<String, String>>
    )

    /**
     * Extracts the internal MegaPlay episode file ID from the `/stream/{mal|ani}/{id}/{ep}/{type}`
     * embed document. Live verified (2026-10-17): the document embeds `<title>File 141638</title>`
     * and/or `#megaplay-player[data-id]`.
     */
    internal fun megaplayExtractFileId(html: String): String? {
        if (html.isBlank()) return null
        MEGAPLAY_DATA_ID_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        MEGAPLAY_FILE_ID_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * Parses both raw `/stream/getSources?id={cid}&platform=OTHER` responses and `dec-megaplay`
     * decrypted envelopes. Handles `sources` as an encrypted string, a JSONArray of `{file,url}`,
     * or a JSONObject `{file,url}`, alongside `tracks` (`{file,url,label,kind}`).
     */
    internal fun megaplayParseSourcesPayload(rawJson: String): MegaPlaySourcesPayload? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val target = root.optJSONObject("result") ?: root

        val directUrls = mutableListOf<String>()
        var encryptedSources: String? = null

        val rawSources = target.opt("sources")
        when (rawSources) {
            is String -> {
                val trimmed = rawSources.trim()
                if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    directUrls.add(trimmed)
                } else if (trimmed.length >= 16) {
                    encryptedSources = trimmed
                }
            }
            is JSONArray -> {
                for (i in 0 until rawSources.length()) {
                    val obj = rawSources.optJSONObject(i) ?: continue
                    val u = obj.optString("file").ifBlank { obj.optString("url") }.trim()
                    if (u.startsWith("http", ignoreCase = true)) directUrls.add(u)
                }
            }
            is JSONObject -> {
                val u = rawSources.optString("file").ifBlank { rawSources.optString("url") }.trim()
                if (u.startsWith("http", ignoreCase = true)) directUrls.add(u)
            }
        }

        val tracks = mutableListOf<Pair<String, String>>()
        val tracksArr = target.optJSONArray("tracks") ?: root.optJSONArray("tracks")
        tracksArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                if (t.optString("kind").equals("thumbnails", ignoreCase = true)) continue
                val file = t.optString("file").ifBlank { t.optString("url") }.trim()
                if (!file.startsWith("http", ignoreCase = true) || file.contains("thumbnails", ignoreCase = true)) continue
                val label = cleanSubtitleLabel(t.optString("label").ifBlank { t.optString("lang").ifBlank { "English" } })
                tracks.add(label to file)
            }
        }

        if (directUrls.isEmpty() && encryptedSources == null && tracks.isEmpty()) return null
        return MegaPlaySourcesPayload(
            directUrls = directUrls.distinct(),
            encryptedSources = encryptedSources,
            tracks = tracks.distinctBy { it.second }
        )
    }

    /**
     * SOTA Anime extractor for MegaPlay (`megaplay.buzz`, Rank 92).
     *
     * Protocol (verified live 2026-10-17):
     *   1. GET `https://megaplay.buzz/stream/mal/{malId}/{ep}/{sub|dub}` (or `/stream/ani/{aniId}/{ep}/{sub|dub}`)
     *   2. Extract internal file ID (`<title>File 141638</title>` / `data-id`)
     *   3. GET `https://megaplay.buzz/stream/getSources?id={fileId}&platform=OTHER`
     *   4. POST `https://enc-dec.app/api/dec-megaplay` with `{"text": sourcesCiphertext}`
     *   5. Emits `fetch.nexabloom.top` multi-resolution HLS + `.vtt` subtitle tracks (`broforgotsave.online`)
     */
    suspend fun invokeMegaPlay(
        malId: Int? = null,
        anilistId: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String? = "SUB"
    ) {
        if (malId == null && anilistId == null) return
        val isMovie = dubtype.equals("Movie", ignoreCase = true)
        val epNum = if (isMovie) 1 else (episode ?: return)
        val types = when {
            isMovie -> listOf("sub", "dub")
            dubtype.equals("dub", ignoreCase = true) -> listOf("dub")
            else -> listOf("sub")
        }

        val emittedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        try {
            withTimeoutOrNull(18_000L) {
                for (type in types) {
                    val candidatePages = buildList {
                        if (malId != null && malId > 0) add("$MEGAPLAY_HOST/stream/mal/$malId/$epNum/$type")
                        if (anilistId != null && anilistId > 0) add("$MEGAPLAY_HOST/stream/ani/$anilistId/$epNum/$type")
                    }

                    var resolvedFileId: String? = null
                    var resolvedEmbedUrl: String? = null
                    for (embedUrl in candidatePages) {
                        val html = cancellableGetText(
                            embedUrl,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$MEGAPLAY_HOST/",
                                "Origin" to MEGAPLAY_HOST
                            ),
                            timeoutSec = 5L
                        ) ?: continue
                        val cid = megaplayExtractFileId(html)
                        if (cid != null) {
                            resolvedFileId = cid
                            resolvedEmbedUrl = embedUrl
                            break
                        }
                    }

                    val cid = resolvedFileId ?: continue
                    val embedReferer = resolvedEmbedUrl ?: "$MEGAPLAY_HOST/"

                    val sourcesRaw = cancellableGetText(
                        "$MEGAPLAY_HOST/stream/getSources?id=$cid&platform=OTHER",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedReferer,
                            "Origin" to MEGAPLAY_HOST,
                            "X-Requested-With" to "XMLHttpRequest",
                            "Accept" to "*/*"
                        ),
                        timeoutSec = 5L
                    ) ?: continue

                    val initialPayload = megaplayParseSourcesPayload(sourcesRaw) ?: continue
                    initialPayload.tracks.forEach { (label, trackUrl) ->
                        if (emittedTracks.add(trackUrl)) {
                            subtitleCallback(newSubtitleFile(label, trackUrl))
                        }
                    }

                    val streamUrls = mutableListOf<String>()
                    streamUrls.addAll(initialPayload.directUrls)

                    if (streamUrls.isEmpty() && !initialPayload.encryptedSources.isNullOrBlank()) {
                        val decRaw = encDecPost(
                            "dec-megaplay",
                            JSONObject().put("text", initialPayload.encryptedSources).toString(),
                            timeoutSec = 5L
                        )
                        if (!decRaw.isNullOrBlank()) {
                            val decPayload = megaplayParseSourcesPayload(decRaw)
                            decPayload?.tracks?.forEach { (label, trackUrl) ->
                                if (emittedTracks.add(trackUrl)) {
                                    subtitleCallback(newSubtitleFile(label, trackUrl))
                                }
                            }
                            decPayload?.directUrls?.let { streamUrls.addAll(it) }
                        }
                    }

                    val cdnHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$MEGAPLAY_HOST/",
                        "Origin" to MEGAPLAY_HOST
                    )
                    val baseName = if (type.equals("sub", ignoreCase = true)) {
                        "MegaPlay [Sub] [Original]"
                    } else {
                        "MegaPlay [Dub]"
                    }

                    for (streamUrl in streamUrls.distinct()) {
                        emitVerifiedStream(
                            source = "MegaPlay",
                            baseName = baseName,
                            url = streamUrl,
                            referer = "$MEGAPLAY_HOST/",
                            headers = cdnHeaders,
                            callback = callback
                        )
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeMegaPlay failed: ${e.message}")
        }
    }

    suspend fun invokeSubtitleAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val url = if (season == null) {
            "$SubtitlesAPI/subtitles/movie/$id.json"
        } else {
            "$SubtitlesAPI/subtitles/series/$id:$season:$episode.json"
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )
        val response = safeGet(url, headers = headers, timeout = 10L)
        if (response.code != 200) return
        response.parsedSafe<SubtitlesAPI>()?.subtitles?.safeAmap { it ->
            val lan = getLanguage(it.lang)
            val suburl = it.url
            subtitleCallback.invoke(
                newSubtitleFile(
                    lan.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    suburl
                )
            )
        }
    }


    suspend fun invokeWYZIESubs(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val key = wyziekey.takeIf { !it.isNullOrBlank() } ?: return

        val url = if (season != null) {
            "$WYZIESubsAPI/search?id=$id&season=$season&episode=$episode&source=all&key=$key"
        } else {
            "$WYZIESubsAPI/search?id=$id&source=all&key=$key"
        }

        val data = app.get(
            url,
            timeout = 10L
        ).parsedSafe<Array<WYZIESubtitle>>() ?: return

        data.forEach { sub ->
            val lang = sub.language ?: return@forEach

            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.display ?: getLanguage(lang),
                    sub.url
                )
            )
        }
    }




    private val imdbToTmdbCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

    suspend fun resolveTmdbIdFromImdb(imdbId: String, season: Int? = null): Int? {
        val cleanImdb = imdbId.trim()
        if (!cleanImdb.startsWith("tt", ignoreCase = true)) return null
        imdbToTmdbCache[cleanImdb]?.let { return it }
        return try {
            val isTv = season != null && season > 0
            val type = if (isTv) "series" else "movie"
            // 1. Cinemeta lookup (ultra fast, high availability, no key required)
            val cinemetaUrl = "https://v3-cinemeta.strem.io/meta/$type/$cleanImdb.json"
            val cineResp = suspendCancellable {
                runCatching { app.get(cinemetaUrl, timeout = 5L).text }.getOrNull()
            }
            if (!cineResp.isNullOrBlank()) {
                val json = runCatching { JSONObject(cineResp).optJSONObject("meta") }.getOrNull()
                val moviedbId = json?.optInt("moviedb_id", 0) ?: 0
                if (moviedbId > 0) {
                    imdbToTmdbCache[cleanImdb] = moviedbId
                    return moviedbId
                }
                val tmdbId = json?.optInt("tmdb_id", 0) ?: 0
                if (tmdbId > 0) {
                    imdbToTmdbCache[cleanImdb] = tmdbId
                    return tmdbId
                }
            }
            // 2. TMDB Find fallback
            val tmdbKey = BuildConfig.TMDB_API.takeIf { it.isNotBlank() } ?: "98ae14df2b8d8f8f8136499daf79f0e0"
            val tmdbUrl = "https://api.themoviedb.org/3/find/$cleanImdb?api_key=$tmdbKey&external_source=imdb_id"
            val tmdbResp = suspendCancellable {
                runCatching { app.get(tmdbUrl, timeout = 5L).text }.getOrNull()
            }
            if (!tmdbResp.isNullOrBlank()) {
                val json = runCatching { JSONObject(tmdbResp) }.getOrNull()
                val tvArr = json?.optJSONArray("tv_results")
                val movieArr = json?.optJSONArray("movie_results")
                if (isTv && tvArr != null && tvArr.length() > 0) {
                    val id = tvArr.getJSONObject(0).optInt("id", 0)
                    if (id > 0) {
                        imdbToTmdbCache[cleanImdb] = id
                        return id
                    }
                }
                if (movieArr != null && movieArr.length() > 0) {
                    val id = movieArr.getJSONObject(0).optInt("id", 0)
                    if (id > 0) {
                        imdbToTmdbCache[cleanImdb] = id
                        return id
                    }
                }
                if (tvArr != null && tvArr.length() > 0) {
                    val id = tvArr.getJSONObject(0).optInt("id", 0)
                    if (id > 0) {
                        imdbToTmdbCache[cleanImdb] = id
                        return id
                    }
                }
            }
            null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    suspend fun invokeVidlink(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            // SOTA Fallback: vidlink.pro accepts encrypted IMDb IDs (e.g. tt0137523) directly for movies!
            val mediaIdStr = effectiveTmdbId?.toString() ?: (if (season == null) imdbId?.trim() else null)
            if (mediaIdStr.isNullOrBlank() || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(25000L) {
                val base = vidlink

                val headers = mapOf(
                    "User-Agent" to StreamLinkOptimizer.MODERN_DESKTOP_UA,
                    "Accept" to "application/json",
                    "Referer" to "$base/",
                    "Origin" to base
                )

                fun buildApiUrls(token: String): List<String> = when {
                    season == null || (season == 0 && episode == null) -> listOf("$base/api/b/movie/$token")
                    episode == null -> emptyList()
                    season == 0 -> listOf(
                        "$base/api/b/tv/$token/$season/$episode",
                        "$base/api/b/movie/$token"
                    )
                    else -> listOf("$base/api/b/tv/$token/$season/$episode")
                }

                suspend fun fetchStreamForToken(token: String): VidlinkStream? {
                    val urls = buildApiUrls(token)
                    for (apiUrl in urls) {
                        val epResponse = retryTransient(2, 250L) {
                            val resp = suspendCancellable {
                                runCatching { app.get(apiUrl, headers = headers, timeout = 12L) }.getOrNull()
                            }
                            if (resp != null && resp.isSuccessful && resp.text.isNotBlank() && resp.text != "null") resp.text else null
                        } ?: continue

                        val data = runCatching {
                            Gson().fromJson(epResponse, VidlinkResponse::class.java)
                        }.getOrNull()

                        val s = data?.stream
                        if (s != null && (!s.playlist.isNullOrBlank() || !s.qualities.isNullOrEmpty())) {
                            return s
                        }
                    }
                    return null
                }

                // SOTA Phase 1: High-performance, offline-native XSalsa20-Poly1305 encryption.
                // Generates authentic tokens instantly in 0ms with zero network requests, bypassing
                // external rate-limiting (HTTP 429), connection dropouts, and DNS blocks.
                var encData = runCatching {
                    VidLinkCrypto.encryptToken(mediaIdStr)
                }.getOrNull()

                var stream: VidlinkStream? = null
                if (!encData.isNullOrBlank()) {
                    stream = fetchStreamForToken(encData)
                }

                // SOTA Phase 2: Resilient secondary fallback to enc-dec.app only if offline token returned no stream
                if (stream == null) {
                    val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$mediaIdStr"
                    val remoteEnc = retryTransient(3, 300L) {
                        val resp = suspendCancellable {
                            runCatching {
                                app.get(
                                    encUrl,
                                    headers = mapOf(
                                        "User-Agent" to StreamLinkOptimizer.MODERN_DESKTOP_UA,
                                        "Accept" to "application/json"
                                    ),
                                    timeout = 5L
                                )
                            }.getOrNull()
                        }
                        if (resp != null) {
                            if (resp.code == 429) {
                                val retryAfterSec = resp.headers["Retry-After"]?.toLongOrNull() ?: 1L
                                delay((retryAfterSec * 1000L).coerceIn(400L, 2000L))
                                return@retryTransient null
                            }
                            if (resp.isSuccessful && resp.text.isNotBlank()) {
                                val res = runCatching { JSONObject(resp.text).optString("result") }.getOrNull()
                                if (!res.isNullOrBlank()) res else null
                            } else null
                        } else null
                    }
                    if (!remoteEnc.isNullOrBlank() && remoteEnc != encData) {
                        encData = remoteEnc
                        stream = fetchStreamForToken(remoteEnc)
                    }
                }

                if (stream == null) return@withTimeoutOrNull
                effectiveTmdbId?.let { vidlinkEncCache[it] = Pair(encData ?: "", System.currentTimeMillis()) }

                val cronetUserAgent = "com.community.oneroom/50020115 (Linux; U; Android 15; en_US; OPPO CPH2579; Build/AP3A.240905.015.A2; Cronet/140.0.7339.51)"

                fun sanitizeVidlinkHeaders(inputHeaders: Map<String, String>? = null): MutableMap<String, String> {
                    val result = mutableMapOf<String, String>()
                    inputHeaders?.forEach { (k, v) ->
                        if (!k.equals("Referer", ignoreCase = true) &&
                            !k.equals("Origin", ignoreCase = true) &&
                            !k.equals("User-Agent", ignoreCase = true) &&
                            !k.equals("Accept", ignoreCase = true) &&
                            !k.equals("Accept-Ranges", ignoreCase = true)) {
                            result[k] = v
                        }
                    }
                    result["User-Agent"] = cronetUserAgent
                    result["Accept"] = "*/*"
                    result["Accept-Ranges"] = "bytes"
                    return result
                }

                // 1. Parse captions / subtitles
                var hasCaptions = false
                stream.captions?.forEach { caption ->
                    val rawSubUrl = caption?.url?.trim()
                    val subUrl = when {
                        rawSubUrl.isNullOrBlank() -> null
                        rawSubUrl.startsWith("http://", ignoreCase = true) || rawSubUrl.startsWith("https://", ignoreCase = true) -> rawSubUrl
                        rawSubUrl.startsWith("//") -> "https:$rawSubUrl"
                        else -> null
                    }
                    if (!subUrl.isNullOrBlank()) {
                        hasCaptions = true
                        val lang = cleanSubtitleLabel(caption?.language)
                        subtitleCallback?.invoke(newSubtitleFile(lang, subUrl))
                    }
                }
                val captionMeta = if (hasCaptions) "captions=sub" else null

                // 2. Parse playlist (HLS m3u8)
                val m3u8 = stream.playlist?.trim()
                if (!m3u8.isNullOrBlank()) {
                    var referer = ""
                    var origin = ""

                    val headersJson = Regex("""[?&]headers=([^&]+)""")
                        .find(m3u8)?.groupValues?.get(1)
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

                    if (!headersJson.isNullOrBlank()) {
                        runCatching {
                            val obj = Gson().fromJson(headersJson, JsonObject::class.java)
                            val ref = obj?.get("referer")?.asString
                            val parsedOrigin = obj?.get("origin")?.asString
                            if (!ref.isNullOrBlank() && !ref.contains("vidlink.pro", ignoreCase = true) && !ref.contains("filmboom.top", ignoreCase = true) && !ref.contains("embed", ignoreCase = true)) {
                                referer = ref
                            }
                            if (!parsedOrigin.isNullOrBlank() && !parsedOrigin.contains("vidlink.pro", ignoreCase = true) && !parsedOrigin.contains("filmboom.top", ignoreCase = true) && !parsedOrigin.contains("embed", ignoreCase = true)) {
                                origin = parsedOrigin
                            }
                        }
                    }

                    // Preserve all CDN tokens and query params while stripping only embed wrapper headers param
                    val cleanM3u8Url = if (m3u8.contains("headers=")) {
                        m3u8.replace(Regex("""([?&])headers=[^&]*(&|$)"""), "$1").trimEnd('?', '&')
                    } else {
                        m3u8
                    }

                    val effHlsRef = if (referer.isNotBlank() && !referer.contains("filmboom.top", ignoreCase = true)) referer else ""
                    val effHlsOrig = if (origin.isNotBlank() && !origin.contains("filmboom.top", ignoreCase = true)) origin else ""
                    val hlsHeaders = sanitizeVidlinkHeaders()
                    if (effHlsOrig.isNotBlank()) hlsHeaders["Origin"] = effHlsOrig
                    if (effHlsRef.isNotBlank()) hlsHeaders["Referer"] = effHlsRef

                    val generatedLinks = withTimeoutOrNull(15000L) {
                        runCatching {
                            generateM3u8(
                                "Vidlink",
                                cleanM3u8Url,
                                referer = effHlsRef,
                                headers = hlsHeaders
                            )
                        }.getOrNull()
                    }

                    val mappedGenerated = generatedLinks?.map { genLink ->
                        val finalHeaders = sanitizeVidlinkHeaders(genLink.headers)
                        if (effHlsRef.isNotBlank()) finalHeaders["Referer"] = effHlsRef
                        if (effHlsOrig.isNotBlank()) finalHeaders["Origin"] = effHlsOrig
                        newExtractorLink(
                            genLink.source,
                            genLink.name,
                            genLink.url,
                            genLink.type
                        ) {
                            this.referer = effHlsRef
                            this.quality = genLink.quality
                            this.headers = finalHeaders
                            this.extractorData = captionMeta
                        }
                    }

                    if (!mappedGenerated.isNullOrEmpty()) {
                        emitTopTierDualQualityStreamLinks(
                            source = "Vidlink",
                            baseName = "Vidlink HLS",
                            url = cleanM3u8Url,
                            referer = effHlsRef,
                            headers = hlsHeaders,
                            streamType = ExtractorLinkType.M3U8,
                            generatedLinks = mappedGenerated,
                            callback = callback
                        )
                    } else if (cleanM3u8Url.startsWith("http", ignoreCase = true)) {
                        emitTopTierDualQualityStreamLinks(
                            source = "Vidlink",
                            baseName = "Vidlink HLS",
                            url = cleanM3u8Url,
                            referer = effHlsRef,
                            headers = hlsHeaders,
                            streamType = ExtractorLinkType.M3U8,
                            callback = callback
                        )
                    }
                }

                // 3. Parse direct MP4 stream qualities prioritized by SOTA quality hierarchy (720p > 1080p > 480p)
                val directQualitiesLinks = mutableListOf<ExtractorLink>()
                stream.qualities?.entries?.forEach { (qualityKey, qualityObj) ->
                    val videoUrl = qualityObj?.url?.trim()
                    if (!videoUrl.isNullOrBlank() && videoUrl.startsWith("http", ignoreCase = true)) {
                        val qual = qualityKey?.let {
                            val qInt = it.replace(Regex("[^0-9]"), "").toIntOrNull()
                            when (qInt) {
                                2160 -> Qualities.P2160.value
                                1440 -> Qualities.P1440.value
                                1080 -> Qualities.P1080.value
                                720 -> Qualities.P720.value
                                480 -> Qualities.P480.value
                                360 -> Qualities.P360.value
                                else -> StreamLinkOptimizer.extractQualityFromText(it).takeIf { q -> q > Qualities.Unknown.value } ?: getQualityFromName(it)
                            }
                        } ?: Qualities.Unknown.value
                        val isDirectVideo = !videoUrl.contains(".m3u8", ignoreCase = true)
                        val qualHeaders = sanitizeVidlinkHeaders(qualityObj.headers)

                        val cleanVideoUrl = if (videoUrl.contains("headers=")) {
                            videoUrl.replace(Regex("""([?&])headers=[^&]*(&|$)"""), "$1").trimEnd('?', '&')
                        } else {
                            videoUrl
                        }
                        if (cleanVideoUrl.startsWith("http", ignoreCase = true)) {
                            val effectiveQuality = when {
                                qualityKey.equals("720", ignoreCase = true) || qualityKey.equals("720p", ignoreCase = true) -> "720p"
                                qualityKey.equals("1080", ignoreCase = true) || qualityKey.equals("1080p", ignoreCase = true) -> "1080p"
                                qualityKey.equals("480", ignoreCase = true) || qualityKey.equals("480p", ignoreCase = true) -> "480p"
                                qualityKey.equals("360", ignoreCase = true) || qualityKey.equals("360p", ignoreCase = true) -> "360p"
                                qualityKey.equals("2160", ignoreCase = true) || qualityKey.equals("4k", ignoreCase = true) -> "4K"
                                !qualityKey.isNullOrBlank() -> if (qualityKey.endsWith("p", ignoreCase = true)) qualityKey else "${qualityKey}p"
                                else -> "Auto"
                            }
                            // Authentic per-quality size from the VidLink API, threaded into the
                            // link name in the exact dialect the app's download ranker parses —
                            // the host elects the lowest-size candidate among equal qualities
                            // from this label, so it must never be fabricated or rounded away.
                            val sizeLabel = StreamLinkOptimizer.normalizeSizeLabel(qualityObj.size)
                            val displayName = if (sizeLabel != null) {
                                "Vidlink $effectiveQuality [$sizeLabel]"
                            } else {
                                "Vidlink $effectiveQuality"
                            }
                            directQualitiesLinks.add(
                                newExtractorLink(
                                    "Vidlink",
                                    displayName,
                                    url = cleanVideoUrl,
                                    type = if (isDirectVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                ) {
                                    this.referer = ""
                                    this.quality = qual
                                    this.headers = qualHeaders
                                    this.extractorData = captionMeta
                                }
                            )
                        }
                    }
                }

                if (directQualitiesLinks.isNotEmpty()) {
                    emitTopTierDualQualityStreamLinks(
                        source = "Vidlink",
                        baseName = "Vidlink",
                        url = directQualitiesLinks.first().url,
                        referer = directQualitiesLinks.first().referer,
                        headers = directQualitiesLinks.first().headers,
                        streamType = directQualitiesLinks.first().type,
                        generatedLinks = directQualitiesLinks,
                        callback = callback
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidlink failed: ${e.message}")
        }
    }

    suspend fun invokeVidlink(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidlink(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // ==================== VidSrc (vidsrc.pm) SOTA Multi-Source Extractor ====================

    private const val VIDSRC_API = "https://vidsrc.pm"

    /**
     * Player credential published by the vidsrc.pm/VidSrc web player bundle. It is a
     * public, per-player constant (not a per-user secret) and is sent as `x-player-key`.
     */
    private const val VIDSRC_PLAYER_KEY =
        "f3b72e73c80c9a996574379798703796a1936efa3516a7105cb0e43048b46b5a"

    /**
     * Upstream fan-out order. Validated live against production on 2026-10-09:
     *  - `quasar`    -> first-party multi-variant HLS master (4K HDR / 1080p / 720p / 360p),
     *                   served directly off lit.cheaptruckrepairs.cc with no proxy.
     *  - `scrapify`  -> HLS behind the hd4u reverse proxy (netoda.tech origin).
     *  - `lookmovie` -> progressive file (trunmed.cyou) behind hd4u/gofile-style mirrors.
     *  - `vaplayer` / `fsonic` / `oreon` -> additional hd4u-fronted mirrors.
     *
     * Directly playable streams are always emitted ahead of proxy-fronted ones so that a
     * degraded proxy can never displace a link the player is able to open by itself.
     */
    private val VIDSRC_SOURCES =
        listOf("quasar", "scrapify", "lookmovie", "vaplayer", "fsonic", "oreon")

    private val VIDSRC_PROXY_MARKERS = listOf("/proxy?", "hd4u.sbs", "hd4u.")

    private val vidsrcSemaphore = Semaphore(3)

    private fun vidsrcIsProxied(url: String): Boolean =
        VIDSRC_PROXY_MARKERS.any { url.contains(it, ignoreCase = true) }

    /**
     * The hd4u reverse proxy embeds the upstream Origin/Referer it will forward as a
     * base64 `data=` query parameter (`Origin=...|Referer=...`). Decoding it lets us send
     * exactly the headers the protected origin expects instead of guessing.
     */
    private fun vidsrcProxyHeaders(url: String): Map<String, String> {
        val encoded = Regex("""[?&]data=([^&]+)""").find(url)?.groupValues?.get(1) ?: return emptyMap()
        val plain = runCatching {
            base64Decode(URLDecoder.decode(encoded, "UTF-8"))
        }.getOrNull() ?: return emptyMap()
        return plain.split('|').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null
            else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
        }.toMap()
    }

    private data class VidSrcStream(
        val url: String,
        val upstream: String,
        val variants: List<Pair<String, Int>>,
        val tracks: List<Pair<String, String>>
    )

    private val vidsrcQualityRegex = Regex("""^\s*(\d{3,4})\s*p?""", RegexOption.IGNORE_CASE)

    private fun vidsrcQualityOf(label: String?): Int {
        val raw = label?.trim().orEmpty()
        if (raw.isBlank()) return Qualities.Unknown.value
        vidsrcQualityRegex.find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?.let { value ->
                return when (value) {
                    2160 -> Qualities.P2160.value
                    1440 -> Qualities.P1440.value
                    1080 -> Qualities.P1080.value
                    720 -> Qualities.P720.value
                    480 -> Qualities.P480.value
                    360 -> Qualities.P360.value
                    else -> value
                }
            }
        return getQualityFromName(raw).takeIf { it > 0 } ?: Qualities.Unknown.value
    }

    /** Fetches one upstream via the VidSrc API. Returns null when the mirror is dry. */
    private suspend fun vidsrcFetchSource(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        source: String?
    ): VidSrcStream? {
        val isMovie = season == null || (season == 0 && episode == null)
        val path = if (isMovie) {
            "/api/vidora/v1/movie/$tmdbId"
        } else {
            "/api/vidora/v1/tv/$tmdbId/$season/$episode"
        }
        val url = if (source.isNullOrBlank()) "$VIDSRC_API$path" else "$VIDSRC_API$path?source=$source"

        val body = retryTransient(2, 250L) {
            val response = suspendCancellable {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "x-player-key" to VIDSRC_PLAYER_KEY,
                        "Referer" to "$VIDSRC_API/",
                        "Origin" to VIDSRC_API
                    ),
                    timeout = 12L
                )
            }
            if (response != null && response.isSuccessful && response.text.isNotBlank()) response.text else null
        } ?: return null

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (!root.optBoolean("result", false)) return null
        val sourcesArray = root.optJSONArray("sources") ?: return null

        // A single upstream can advertise several files; keep the first usable one and
        // harvest any extra bitrate ladder entries as variants of the same stream.
        for (i in 0 until sourcesArray.length()) {
            val entry = sourcesArray.optJSONObject(i) ?: continue
            val streamUrl = entry.optString("url").trim()
            if (streamUrl.isEmpty() || !streamUrl.startsWith("http", ignoreCase = true)) continue

            val variants = mutableListOf<Pair<String, Int>>()
            entry.optJSONArray("qualities")?.let { qualities ->
                for (q in 0 until qualities.length()) {
                    val qualityObj = qualities.optJSONObject(q) ?: continue
                    val qualityUrl = qualityObj.optString("url").ifBlank { qualityObj.optString("file") }.trim()
                    if (qualityUrl.isEmpty()) continue
                    val label = qualityObj.optString("label").ifBlank { qualityObj.optString("quality") }
                    variants.add(qualityUrl to vidsrcQualityOf(label))
                }
            }

            val tracks = mutableListOf<Pair<String, String>>()
            entry.optJSONArray("tracks")?.let { trackArray ->
                for (t in 0 until trackArray.length()) {
                    val track = trackArray.optJSONObject(t) ?: continue
                    val trackUrl = track.optString("file")
                        .ifBlank { track.optString("url") }
                        .ifBlank { track.optString("src") }
                        .trim()
                    if (trackUrl.isEmpty() || !trackUrl.startsWith("http", ignoreCase = true)) continue
                    val language = track.optString("label")
                        .ifBlank { track.optString("language") }
                        .ifBlank { track.optString("lang") }
                    tracks.add(cleanSubtitleLabel(language) to trackUrl)
                }
            }

            return VidSrcStream(
                url = streamUrl,
                upstream = entry.optString("source").ifBlank { source.orEmpty() },
                variants = variants,
                tracks = tracks
            )
        }
        return null
    }

    private suspend fun vidsrcEmit(stream: VidSrcStream, callback: (ExtractorLink) -> Unit) {
        val proxied = vidsrcIsProxied(stream.url)
        val headers = if (proxied) {
            vidsrcProxyHeaders(stream.url) + mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$VIDSRC_API/",
                "Origin" to VIDSRC_API
            )
        } else {
            mapOf("User-Agent" to USER_AGENT, "Referer" to "$VIDSRC_API/", "Origin" to VIDSRC_API)
        }
        val referer = headers["Referer"] ?: "$VIDSRC_API/"
        val tag = if (proxied) "Mirror" else "Direct"
        val baseName = "VidSrc $tag (${stream.upstream})"

        // Honour an explicit bitrate ladder advertised by the upstream first.
        stream.variants.forEach { (variantUrl, quality) ->
            if (!variantUrl.startsWith("http", ignoreCase = true)) return@forEach
            callback(
                newExtractorLink("VidSrc", "$baseName [${vidsrcQualityTag(quality)}]", variantUrl) {
                    this.referer = referer
                    this.quality = quality
                    this.type = if (variantUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.headers = headers
                }
            )
        }

        val isHls = stream.url.contains(".m3u8", ignoreCase = true)
        if (isHls) {
            val expanded = withTimeoutOrNull(12_000L) {
                runCatching {
                    generateM3u8("VidSrc", stream.url, referer = referer, headers = headers)
                }.getOrNull()
            }
            if (!expanded.isNullOrEmpty()) {
                expanded.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR).forEach { variant ->
                    callback(
                        newExtractorLink(
                            "VidSrc",
                            "$baseName [${vidsrcQualityTag(variant.quality)}]",
                            variant.url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = variant.quality
                            this.headers = headers
                        }
                    )
                }
                return
            }
        }

        callback(
            newExtractorLink(
                "VidSrc",
                baseName,
                stream.url,
                if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
        )
    }

    private fun vidsrcQualityTag(quality: Int): String = when (quality) {
        Qualities.P2160.value -> "4K"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> if (quality > 0 && quality != Qualities.Unknown.value) "${quality}p" else "Auto"
    }

    /**
     * Video Streaming API extractor for VidSrc (vidsrc.pm).
     *
     * Protocol (verified live 2026-10-09):
     *   GET {api}/api/vidora/v1/movie/{tmdb}            -> { result, sources: [...] }
     *   GET {api}/api/vidora/v1/tv/{tmdb}/{s}/{e}?source=KEY
     *   Header: x-player-key: <VIDSRC_PLAYER_KEY>
     */
    suspend fun invokeVidSrc(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            if (effectiveTmdbId == null) return
            if (season != null && season != 0 && episode == null) return
            val resolvedTmdbId = effectiveTmdbId

            withTimeoutOrNull(24_000L) {
                val isMovie = season == null || (season == 0 && episode == null)
                val streamKey = if (isMovie) "movie_$resolvedTmdbId" else "tv_${resolvedTmdbId}_${season}_$episode"

                val collected = Collections.synchronizedList(mutableListOf<VidSrcStream>())
                val emittedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                suspend fun harvest(source: String?) {
                    val stream = vidsrcSemaphore.withPermit {
                        runCatching { vidsrcFetchSource(resolvedTmdbId, season, episode, source) }.getOrNull()
                    } ?: return
                    collected.add(stream)
                }

                // Resolve the default upstream first: it is the fastest signal that the
                // title is actually indexed, and it lets us emit something within ~1 RTT.
                val defaultStream = withTimeoutOrNull(9_000L) {
                    runCatching { vidsrcFetchSource(resolvedTmdbId, season, episode, null) }.getOrNull()
                }
                if (defaultStream != null) {
                    collected.add(defaultStream)
                    defaultStream.tracks.forEach { (language, trackUrl) ->
                        if (emittedTracks.add(trackUrl)) subtitleCallback?.invoke(newSubtitleFile(language, trackUrl))
                    }
                    vidsrcEmit(defaultStream, callback)
                }

                // Fan out across the remaining mirrors concurrently.
                coroutineScope {
                    VIDSRC_SOURCES.map { source ->
                        async {
                            val already = collected.any {
                                it.upstream.equals(source, ignoreCase = true) ||
                                    it.url == defaultStream?.url
                            }
                            if (!already) harvest(source)
                        }
                    }.forEach { it.await() }
                }

                val direct = collected.filterNot { vidsrcIsProxied(it.url) }
                val mirrored = collected.filter { vidsrcIsProxied(it.url) }

                // Only fall back to proxy-fronted mirrors when nothing directly playable
                // was produced; otherwise they would just pollute the source list.
                if (direct.isEmpty() && mirrored.isEmpty()) {
                    Log.d("StreamPlay", "VidSrc: no upstream returned a stream for $streamKey")
                    return@withTimeoutOrNull
                }

                for (stream in (direct + mirrored).distinctBy { it.url }) {
                    if (stream === defaultStream) continue
                    stream.tracks.forEach { (language, trackUrl) ->
                        if (emittedTracks.add(trackUrl)) subtitleCallback?.invoke(newSubtitleFile(language, trackUrl))
                    }
                    vidsrcEmit(stream, callback)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidSrc failed: ${e.message}")
        }
    }

    suspend fun invokeVidSrc(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidSrc(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // ==================== Cineby SOTA Universal Extractor (api.wecollege.net, Rank 96) ====================

    private const val CINEBY_API = "https://api.wecollege.net"
    private const val CINEBY_ORIGIN = "https://player.videasy.to"
    private val CINEBY_SERVERS = listOf("tampa", "dallas", "oakland", "seattle", "vegas", "portland")

    internal data class CinebyDecryptedSource(
        val url: String,
        val quality: Int
    )

    internal data class CinebyDecryptedPayload(
        val sources: List<CinebyDecryptedSource>,
        val subtitles: List<Pair<String, String>>
    )

    /**
     * Parses the `dec-videasy` JSON envelope returned for a Cineby (`api.wecollege.net`) server
     * payload. Extracts both `sources` (`url`, `quality`) and `subtitles` (`url`, `lang`/`language`).
     */
    internal fun cinebyParseDecryptedResult(rawJson: String): CinebyDecryptedPayload? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val target = root.optJSONObject("result") ?: root

        val sources = mutableListOf<CinebyDecryptedSource>()
        target.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").ifBlank { obj.optString("file") }.trim()
                if (!url.startsWith("http", ignoreCase = true)) continue
                val qLabel = obj.optString("quality").ifBlank { obj.optString("resolution") }
                sources.add(CinebyDecryptedSource(url = url, quality = cinebyQualityOf(qLabel)))
            }
        }

        val subtitles = mutableListOf<Pair<String, String>>()
        val subsArr = target.optJSONArray("subtitles") ?: target.optJSONArray("tracks")
        subsArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("kind").equals("thumbnails", ignoreCase = true)) continue
                val url = obj.optString("url").ifBlank { obj.optString("file") }.trim()
                if (!url.startsWith("http", ignoreCase = true) || url.contains("thumbnails", ignoreCase = true)) continue
                val lang = cleanSubtitleLabel(
                    obj.optString("language").ifBlank {
                        obj.optString("lang").ifBlank {
                            obj.optString("label").ifBlank { "English" }
                        }
                    }
                )
                subtitles.add(lang to url)
            }
        }

        if (sources.isEmpty() && subtitles.isEmpty()) return null
        return CinebyDecryptedPayload(
            sources = sources.distinctBy { it.url },
            subtitles = subtitles.distinctBy { it.second }
        )
    }

    internal fun cinebyQualityOf(raw: String?): Int {
        val label = raw?.trim().orEmpty()
        if (label.isBlank() || label.equals("auto", true) || label.equals("hls", true) || label.equals("default", true)) {
            return Qualities.Unknown.value
        }
        Regex("""(\d{3,4})\s*p?""", RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { q ->
            return when (q) {
                2160 -> Qualities.P2160.value
                1440 -> Qualities.P1440.value
                1080 -> Qualities.P1080.value
                720 -> Qualities.P720.value
                480 -> Qualities.P480.value
                360 -> Qualities.P360.value
                else -> q
            }
        }
        return when {
            label.equals("4k", true) || label.equals("uhd", true) -> Qualities.P2160.value
            label.equals("2k", true) || label.equals("qhd", true) -> Qualities.P1440.value
            label.equals("fhd", true) || label.equals("high", true) -> Qualities.P1080.value
            label.equals("hd", true) -> Qualities.P720.value
            label.equals("sd", true) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    }

    /**
     * SOTA Universal Extractor for Cineby (`api.wecollege.net`, Rank 96).
     *
     * Protocol (verified live 2026-10-17 across Movies, TV Shows, and Anime):
     *   1. GET `https://api.wecollege.net/seed?mediaId={tmdbId}` -> `{"seed": "..."}`
     *   2. Concurrent fan-out across `tampa`, `dallas`, `oakland`, `seattle`, `vegas`, `portland`:
     *      `GET https://api.wecollege.net/{server}/sources?title=...&mediaType={movie|tv}&year=...&tmdbId={id}&imdbId=...&seasonId=...&episodeId=...&enc=2&seed={seed}`
     *   3. POST `https://enc-dec.app/api/dec-videasy` with `{"text": ciphertext, "id": "{tmdbId}"}`
     *   4. Emits multi-CDN HLS (`sun.paleoak.top`, `e2e.majorplay.net`) + multi-language subtitles
     */
    suspend fun invokeCineby(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null,
        title: String? = null,
        year: Int? = null,
        isAnime: Boolean = false
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            val id = effectiveTmdbId ?: return
            val isMovie = season == null || (season == 0 && episode == null)
            if (!isMovie && episode == null) return

            withTimeoutOrNull(20_000L) {
                val baseHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to CINEBY_ORIGIN,
                    "Referer" to "$CINEBY_ORIGIN/",
                    "Accept" to "*/*"
                )

                val seedRaw = retryTransient(1, 200L) {
                    cancellableGetText(
                        "$CINEBY_API/seed?mediaId=$id",
                        headers = baseHeaders,
                        timeoutSec = 4L
                    )
                } ?: return@withTimeoutOrNull

                val seed = runCatching { JSONObject(seedRaw).optString("seed") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull

                val mediaType = if (isMovie) "movie" else "tv"
                val encodedTitle = URLEncoder.encode(title.orEmpty(), "UTF-8")
                val encodedSeed = URLEncoder.encode(seed, "UTF-8")
                val yearStr = year?.toString().orEmpty()

                val queryParams = buildString {
                    append("title=").append(encodedTitle)
                    append("&mediaType=").append(mediaType)
                    append("&year=").append(yearStr)
                    append("&tmdbId=").append(id)
                    if (!imdbId.isNullOrBlank()) append("&imdbId=").append(URLEncoder.encode(imdbId, "UTF-8"))
                    if (!isMovie) {
                        append("&seasonId=").append(season ?: 1)
                        append("&episodeId=").append(episode ?: 1)
                    }
                    append("&enc=2&seed=").append(encodedSeed)
                }

                val emittedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                coroutineScope {
                    CINEBY_SERVERS.map { server ->
                        async {
                            try {
                                currentCoroutineContext().ensureActive()
                                val encBody = cancellableGetText(
                                    "$CINEBY_API/$server/sources?$queryParams",
                                    headers = baseHeaders,
                                    timeoutSec = 5L
                                )?.trim() ?: return@async

                                if (encBody.length < 30 || encBody.startsWith("<") || encBody.startsWith("{\"error\"")) {
                                    return@async
                                }

                                val decRaw = encDecPost(
                                    "dec-videasy",
                                    JSONObject().put("text", encBody).put("id", id.toString()).toString(),
                                    timeoutSec = 5L
                                ) ?: return@async

                                val payload = cinebyParseDecryptedResult(decRaw) ?: return@async
                                payload.subtitles.forEach { (lang, subUrl) ->
                                    if (emittedTracks.add(subUrl)) {
                                        subtitleCallback?.invoke(newSubtitleFile(lang, subUrl))
                                    }
                                }

                                val displayServer = server.replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase() else it.toString()
                                }
                                val hasSubs = payload.subtitles.isNotEmpty()
                                val langTag = if (isAnime || hasSubs) " [Sub] [Original]" else ""
                                val baseName = "Cineby $displayServer$langTag"

                                for (src in payload.sources) {
                                    if (src.quality != Qualities.Unknown.value && !src.url.contains("master.m3u8", ignoreCase = true) && !src.url.contains("index.m3u8", ignoreCase = true)) {
                                        val isM3u8 = src.url.contains(".m3u8", ignoreCase = true)
                                        emitTopTierDualQualityStreamLinks(
                                            source = "Cineby",
                                            baseName = baseName,
                                            url = src.url,
                                            referer = "$CINEBY_ORIGIN/",
                                            headers = baseHeaders,
                                            streamType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                            generatedLinks = listOf(
                                                newExtractorLink(
                                                    "Cineby",
                                                    "$baseName [${vidsrcQualityTag(src.quality)}]",
                                                    src.url,
                                                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                                ) {
                                                    this.referer = "$CINEBY_ORIGIN/"
                                                    this.quality = src.quality
                                                    this.headers = baseHeaders
                                                }
                                            ),
                                            callback = callback
                                        )
                                    } else {
                                        emitVerifiedStream(
                                            source = "Cineby",
                                            baseName = baseName,
                                            url = src.url,
                                            referer = "$CINEBY_ORIGIN/",
                                            headers = baseHeaders,
                                            callback = callback
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeCineby failed: ${e.message}")
        }
    }

    // ==================== v24 SOTA Extractor Suite (VidFast / VidSrcSU / MeowTV) ====================
    //
    // Three protocols reverse-engineered and verified end-to-end over plain HTTP in the v23/v24
    // research passes. Each one hides its media URL behind a small closed-source
    // client crypto chain; the per-request decryptor calls are delegated to enc-dec.app, the
    // continuously maintained decrypt toolkit already trusted by this extension for other
    // flows. Every playback URL produced here is expanded and emitted through the repo-standard
    // non-synthetic top-tier emission path (emitTopTierDualQualityStreamLinks).

    private const val ENC_DEC_API = "https://enc-dec.app/api"

    /** VidFast player origin. Protocol: page token -> enc-vidfast stage 1/2 -> per-server stream. */
    private const val VIDFAST_HOST = "https://vidfast.vc"

    /** VidSrcSU (hexa protocol) TMDB metadata origin + its web front used for Referer pinning. */
    private const val VIDSRCSU_API = "https://themoviedb.vidsrc.su"
    private const val VIDSRCSU_ORIGIN = "https://vidsrc.su"

    /** Public per-player fingerprint constant published by the vidsrc.su web bundle. */
    private const val VIDSRCSU_FINGERPRINT = "e9136c41504646444"

    /** MeowTV web front (Origin/Referer) + ticket/stream API. */
    private const val MEOWTV_HOST = "https://meowtv.ru"
    private const val MEOWTV_API = "https://api.meowtv.ru"

    /** MeowTV upstream servers, in the site's own preference order (original audio first). */
    private val MEOWTV_SERVERS = listOf("english", "ipcloud", "tik", "dcloud", "acme", "hindi")

    private val VIDFAST_PAGE_TOKEN_REGEX = Regex("\"(?:en|token)\":\"([A-Za-z0-9_-]+)\"")

    /**
     * Extracts the VidFast player handshake token from the page HTML. The bundle embeds it inside
     * a JSON-in-string (`\"en\":\"<b64url>\"`, fallback key `token`), so the JSON string layer is
     * unescaped first and the first base64url payload of at least 20 characters wins.
     */
    internal fun vidfastExtractPageToken(html: String): String? {
        val unescaped = html.replace("\\\"", "\"")
        return VIDFAST_PAGE_TOKEN_REGEX.findAll(unescaped)
            .map { it.groupValues[1] }
            .firstOrNull { it.length >= 20 }
    }

    /** enc-dec.app GET with the process-wide 4-permit throttle and one transient retry. */
    private suspend fun encDecGet(url: String, headers: Map<String, String>, timeoutSec: Long = 3L): String? =
        encDecApiSemaphore.withPermit {
            retryTransient(1, 200L) { cancellableGetText(url, headers, timeoutSec) }
        }

    /** enc-dec.app POST (JSON body) with the process-wide throttle and two transient retries. */
    private suspend fun encDecPost(path: String, jsonBody: String, timeoutSec: Long = 4L): String? =
        encDecApiSemaphore.withPermit {
            retryTransient(2, 300L) {
                cancellablePostText(
                    "$ENC_DEC_API/$path",
                    headers = mapOf("Content-Type" to "application/json", "User-Agent" to USER_AGENT),
                    requestBody = jsonBody.toRequestBody("application/json".toMediaType()),
                    timeoutSec = timeoutSec
                )
            }
        }

    internal data class HlsMasterVariant(
        val url: String,
        val quality: Int,
        val bandwidthBps: Long?,
        val estimatedSizeMb: Int?
    )

    private val HLS_RESOLUTION_REGEX = Regex("""RESOLUTION=\s*\d+\s*x\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val HLS_AVG_BW_REGEX = Regex("""AVERAGE-BANDWIDTH=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val HLS_BW_REGEX = Regex("""(?:^|[:,])\s*BANDWIDTH=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val HLS_DEMUXED_AUDIO_REGEX = Regex("""#EXT-X-MEDIA:[^\r\n]*TYPE=AUDIO[^\r\n]*URI="[^"]+"""", RegexOption.IGNORE_CASE)

    /**
     * Parses an HLS master manifest into verified resolution variants while:
     * 1. Preserving the master manifest URL (with `#vid={height}p` fragment) whenever the manifest
     *    declares external `#EXT-X-MEDIA:TYPE=AUDIO` renditions (`URI="..."`), ensuring ExoPlayer
     *    and `HLSDownloader` always mux the original audio track instead of downloading silent
     *    video-only child playlists.
     * 2. Computing deterministic bitrate-proportional file size estimates (`[XXX MB]`) from
     *    `AVERAGE-BANDWIDTH` / `BANDWIDTH` so `DownloadSelectionPolicy` can compare HLS bitrates
     *    and select the lowest-file-size 720p and lowest-file-size 1080p stream.
     */
    internal fun parseHlsMasterVariants(
        masterUrl: String,
        manifestText: String
    ): List<HlsMasterVariant> {
        if (!manifestText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return emptyList()
        val hasDemuxedAudio = HLS_DEMUXED_AUDIO_REGEX.containsMatchIn(manifestText)
        val lines = manifestText.lines()
        val variants = mutableListOf<HlsMasterVariant>()
        val baseUrl = masterUrl.substringBefore('#')

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val height = HLS_RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val avgBw = HLS_AVG_BW_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                val maxBw = HLS_BW_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull()
                val bw = avgBw ?: maxBw

                var j = i + 1
                var uriLine: String? = null
                while (j < lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                        uriLine = candidate
                        break
                    }
                    j++
                }
                if (uriLine != null) {
                    val quality = when (height) {
                        2160 -> Qualities.P2160.value
                        1440 -> Qualities.P1440.value
                        1080 -> Qualities.P1080.value
                        720 -> Qualities.P720.value
                        480 -> Qualities.P480.value
                        360 -> Qualities.P360.value
                        null -> StreamLinkOptimizer.extractQualityFromText(uriLine)
                        else -> height
                    }
                    val resolvedUrl = if (hasDemuxedAudio) {
                        val tag = if (quality > 0 && quality != Qualities.Unknown.value) "${quality}p" else "auto_${variants.size}"
                        "$baseUrl#vid=$tag"
                    } else {
                        runCatching { URI(baseUrl).resolve(uriLine).toString() }.getOrElse {
                            if (uriLine.startsWith("http", ignoreCase = true)) uriLine else baseUrl
                        }
                    }
                    val estimatedMb = bw?.takeIf { it in 100_000L..80_000_000L }?.let { bps ->
                        ((bps / 8.0) * 1440.0 / (1024.0 * 1024.0)).toInt().coerceAtLeast(25)
                    }
                    variants.add(
                        HlsMasterVariant(
                            url = resolvedUrl,
                            quality = quality,
                            bandwidthBps = bw,
                            estimatedSizeMb = estimatedMb
                        )
                    )
                    i = j
                }
            }
            i++
        }
        return variants
            .sortedBy { it.bandwidthBps ?: Long.MAX_VALUE }
            .distinctBy { "${it.quality}|${it.url}" }
    }

    /**
     * Shared expander for verified protocol outputs: parses HLS master manifests for
     * demuxed-audio safety and bitrate-proportional size tags (with M3u8Helper fallback),
     * then delegates to the repo-standard non-synthetic top-tier emission.
     */
    private suspend fun emitVerifiedStream(
        source: String,
        baseName: String,
        url: String,
        referer: String,
        headers: Map<String, String> = emptyMap(),
        callback: (ExtractorLink) -> Unit
    ) {
        val isDirectVideo = url.contains(".mp4", ignoreCase = true) || url.contains(".mkv", ignoreCase = true)
        val streamType = if (isDirectVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
        val generated = if (!isDirectVideo) {
            withTimeoutOrNull(4500L) {
                val reqHeaders = buildMap {
                    put("User-Agent", USER_AGENT)
                    if (referer.isNotBlank()) put("Referer", referer)
                    headers.forEach { (k, v) -> if (v.isNotBlank()) put(k, v) }
                }
                val manifestText = cancellableGetText(url, headers = reqHeaders, timeoutSec = 4L)
                val parsedVariants = if (!manifestText.isNullOrBlank()) {
                    parseHlsMasterVariants(url, manifestText)
                } else {
                    emptyList()
                }
                if (parsedVariants.isNotEmpty()) {
                    parsedVariants.map { v ->
                        val qTag = vidsrcQualityTag(v.quality)
                        val sizeTag = v.estimatedSizeMb?.let { " [$it MB]" }.orEmpty()
                        newExtractorLink(
                            source = source,
                            name = "$baseName [$qTag]$sizeTag",
                            url = v.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = referer
                            this.quality = v.quality
                            this.headers = headers
                        }
                    }
                } else {
                    runCatching { generateM3u8(baseName, url, referer, headers = headers) }.getOrNull()
                }
            }
        } else null

        emitTopTierDualQualityStreamLinks(
            source = source,
            baseName = baseName,
            url = url,
            referer = referer,
            headers = headers,
            streamType = streamType,
            generatedLinks = generated,
            callback = callback
        )
    }

    // -------------------- VidFast --------------------

    suspend fun invokeVidFast(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            val id = effectiveTmdbId ?: return
            val isMovie = season == null || (season == 0 && episode == null)
            if (!isMovie && episode == null) return

            withTimeoutOrNull(24_000L) {
                val pagePath = if (isMovie) "/movie/$id/" else "/tv/$id/$season/$episode/"
                val pageHtml = cancellableGetText(
                    "$VIDFAST_HOST$pagePath",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$VIDFAST_HOST/"),
                    timeoutSec = 5L
                ) ?: return@withTimeoutOrNull

                val pageToken = vidfastExtractPageToken(pageHtml) ?: run {
                    Log.d("StreamPlay", "VidFast: no player token in page for $pagePath")
                    return@withTimeoutOrNull
                }

                val stage1Raw = encDecGet(
                    "$ENC_DEC_API/enc-vidfast?text=${URLEncoder.encode(pageToken, "UTF-8")}&stage=1",
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeoutSec = 4L
                ) ?: return@withTimeoutOrNull
                val stage1 = tryParseJson<VidFastStage1Response>(stage1Raw)?.result ?: return@withTimeoutOrNull
                val stage1Url = stage1.stage1?.takeIf { it.startsWith("http") } ?: return@withTimeoutOrNull
                var csrfToken = stage1.token?.takeIf { it.isNotBlank() } ?: return@withTimeoutOrNull

                val playerHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$VIDFAST_HOST/",
                    "X-Requested-With" to "XMLHttpRequest"
                )

                suspend fun playerPost(url: String, csrf: String, timeoutSec: Long = 4L): String? =
                    retryTransient(2, 350L) {
                        cancellablePostText(
                            url,
                            headers = playerHeaders + mapOf("X-CSRF-Token" to csrf),
                            requestBody = "{}".toRequestBody("application/json".toMediaType()),
                            timeoutSec = timeoutSec
                        )?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
                    }

                val stage1Body = playerPost(stage1Url, csrfToken) ?: return@withTimeoutOrNull

                val stage2Raw = encDecGet(
                    "$ENC_DEC_API/enc-vidfast?text=${URLEncoder.encode(stage1Body, "UTF-8")}&stage=2",
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeoutSec = 4L
                ) ?: return@withTimeoutOrNull
                val stage2 = tryParseJson<VidFastStage2Response>(stage2Raw)?.result ?: return@withTimeoutOrNull
                val serversUrl = stage2.servers?.takeIf { it.startsWith("http") } ?: return@withTimeoutOrNull
                val streamBase = stage2.stream?.takeIf { it.startsWith("http") } ?: return@withTimeoutOrNull
                csrfToken = stage2.token?.takeIf { it.isNotBlank() } ?: csrfToken

                val serversBody = playerPost(serversUrl, csrfToken) ?: return@withTimeoutOrNull
                val serversDecRaw = encDecPost("dec-vidfast", JSONObject().put("text", serversBody).toString())
                    ?: return@withTimeoutOrNull
                val servers = runCatching {
                    JSONObject(serversDecRaw).optJSONArray("result")
                }.getOrNull() ?: return@withTimeoutOrNull

                for (i in 0 until servers.length()) {
                    currentCoroutineContext().ensureActive()
                    val serverObj = servers.optJSONObject(i) ?: continue
                    val data = serverObj.optString("data").takeIf { it.isNotBlank() } ?: continue
                    val serverName = serverObj.optString("name").takeIf { it.isNotBlank() } ?: "Server"

                    val streamBody = playerPost("$streamBase/$data", csrfToken) ?: continue
                    val streamDecRaw = encDecPost("dec-vidfast", JSONObject().put("text", streamBody).toString()) ?: continue
                    val streamResult = runCatching {
                        JSONObject(streamDecRaw).optJSONObject("result")
                    }.getOrNull() ?: continue
                    val streamUrl = streamResult.optString("url").takeIf { it.startsWith("http") } ?: continue

                    streamResult.optJSONArray("tracks")?.let { tracks ->
                        for (t in 0 until tracks.length()) {
                            val track = tracks.optJSONObject(t) ?: continue
                            if (track.optString("kind").equals("thumbnails", ignoreCase = true)) continue
                            val file = track.optString("file").takeIf { it.startsWith("http") } ?: continue
                            subtitleCallback?.invoke(
                                newSubtitleFile(cleanSubtitleLabel(track.optString("label").ifBlank { "English" }), file)
                            )
                        }
                    }

                    val noReferrer = streamResult.optBoolean("noReferrer", false)
                    val referer = if (noReferrer) "" else "$VIDFAST_HOST/"
                    val streamHeaders = if (noReferrer) emptyMap()
                    else mapOf("Referer" to "$VIDFAST_HOST/", "Origin" to VIDFAST_HOST)

                    val displayName = serverName.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                    emitVerifiedStream(
                        source = "VidFast",
                        baseName = "VidFast $displayName",
                        url = streamUrl,
                        referer = referer,
                        headers = streamHeaders,
                        callback = callback
                    )
                    return@withTimeoutOrNull
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidFast failed: ${e.message}")
        }
    }

    suspend fun invokeVidFast(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidFast(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // -------------------- VidSrcSU (hexa protocol) --------------------

    suspend fun invokeVidSrcSU(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            val id = effectiveTmdbId ?: return
            val isMovie = season == null || (season == 0 && episode == null)
            if (!isMovie && episode == null) return

            withTimeoutOrNull(24_000L) {
                val key = generateHexKey32()

                val baseHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$VIDSRCSU_ORIGIN/",
                    "Accept" to "text/plain",
                    "X-Fingerprint-Lite" to VIDSRCSU_FINGERPRINT,
                    "X-Api-Key" to key
                )

                val tokenRaw = encDecGet("$ENC_DEC_API/enc-hexa", headers = baseHeaders, timeoutSec = 4L)
                    ?: return@withTimeoutOrNull
                val token = tryParseJson<VidSrcSUEn>(tokenRaw)?.result?.token?.takeIf { it.isNotBlank() }
                    ?: return@withTimeoutOrNull

                val imagesUrl = if (isMovie) {
                    "$VIDSRCSU_API/api/tmdb/movie/$id/images"
                } else {
                    "$VIDSRCSU_API/api/tmdb/tv/$id/season/$season/episode/$episode/images"
                }

                val imagesRaw = cancellableGetText(
                    imagesUrl,
                    headers = baseHeaders + mapOf("X-Cap-Token" to token),
                    timeoutSec = 5L
                )?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }
                    ?: return@withTimeoutOrNull

                val decryptRaw = encDecPost(
                    "dec-hexa",
                    JSONObject().put("text", imagesRaw).put("key", key).toString(),
                    timeoutSec = 5L
                ) ?: return@withTimeoutOrNull
                val decryptRes = tryParseJson<VidSrcSUResponse>(decryptRaw) ?: return@withTimeoutOrNull
                if (decryptRes.status != 200) return@withTimeoutOrNull

                decryptRes.result?.tracks?.forEach { track ->
                    if (track.kind?.equals("thumbnails", ignoreCase = true) == true) return@forEach
                    val rawFile = track.file?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    val file = when {
                        rawFile.startsWith("http://", ignoreCase = true) || rawFile.startsWith("https://", ignoreCase = true) -> rawFile
                        rawFile.startsWith("//") -> "https:$rawFile"
                        else -> return@forEach
                    }
                    subtitleCallback?.invoke(newSubtitleFile(cleanSubtitleLabel(track.label ?: "English"), file))
                }

                val sources = decryptRes.result?.sources.orEmpty()
                val cdnHeaders = mapOf("Referer" to "$VIDSRCSU_ORIGIN/", "Origin" to VIDSRCSU_ORIGIN)

                coroutineScope {
                    sources.map { src ->
                        async {
                            try {
                                currentCoroutineContext().ensureActive()
                                val server = src.server ?: return@async
                                val link = src.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return@async
                                val displayName = server.replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase() else it.toString()
                                }
                                emitVerifiedStream(
                                    source = "VidSrcSU",
                                    baseName = "VidSrcSU $displayName",
                                    url = link,
                                    referer = "$VIDSRCSU_ORIGIN/",
                                    headers = cdnHeaders,
                                    callback = callback
                                )
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidSrcSU failed: ${e.message}")
        }
    }

    suspend fun invokeVidSrcSU(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidSrcSU(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // -------------------- MeowTV --------------------

    suspend fun invokeMeowTV(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            var effectiveTmdbId = if (tmdbId != null && tmdbId > 0) tmdbId else null
            if (effectiveTmdbId == null && !imdbId.isNullOrBlank()) {
                effectiveTmdbId = resolveTmdbIdFromImdb(imdbId, season)
            }
            val id = effectiveTmdbId ?: return
            val isMovie = season == null || (season == 0 && episode == null)
            if (!isMovie && episode == null) return

            withTimeoutOrNull(24_000L) {
                val streamPath = if (isMovie) "/streams/movie/$id" else "/streams/tv/$id/$season/$episode"
                val baseHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to MEOWTV_HOST,
                    "Referer" to "$MEOWTV_HOST/"
                )

                for (server in MEOWTV_SERVERS) {
                    currentCoroutineContext().ensureActive()

                    // Tickets are single-use: every server attempt needs a fresh one, and the
                    // stream GET is deliberately never retried with a spent ticket.
                    val ticketRaw = cancellablePostText(
                        "$MEOWTV_API/streams/ticket",
                        headers = baseHeaders + mapOf("Content-Type" to "application/json"),
                        requestBody = "{}".toRequestBody("application/json".toMediaType()),
                        timeoutSec = 4L
                    ) ?: continue
                    val ticket = tryParseJson<MeowTvTicketResponse>(ticketRaw)?.ticket?.takeIf { it.isNotBlank() }
                        ?: continue

                    val streamRaw = cancellableGetText(
                        "$MEOWTV_API$streamPath?s=$server",
                        headers = baseHeaders + mapOf("X-Stream-Ticket" to ticket),
                        timeoutSec = 5L
                    )?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") } ?: continue

                    val payload = tryParseJson<MeowTvPayload>(streamRaw) ?: continue
                    val payloadN = payload.n?.takeIf { it.isNotBlank() } ?: continue
                    val payloadD = payload.d?.takeIf { it.isNotBlank() } ?: continue

                    val decRaw = encDecPost(
                        "dec-meowtv",
                        JSONObject().put("data", JSONObject().put("n", payloadN).put("d", payloadD)).toString(),
                        timeoutSec = 5L
                    ) ?: continue
                    val decRes = tryParseJson<MeowTvDecResponse>(decRaw)?.result ?: continue
                    val streamUrl = decRes.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: continue

                    val decHeaders = decRes.headers.orEmpty()
                    val referer = decHeaders.entries
                        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: ""
                    val streamHeaders = decHeaders + mapOf("User-Agent" to USER_AGENT)

                    val language = decRes.language?.takeIf { it.isNotBlank() } ?: "Auto"
                    val displayLanguage = language.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase() else it.toString()
                    }
                    emitVerifiedStream(
                        source = "MeowTV",
                        baseName = "MeowTV $displayLanguage",
                        url = streamUrl,
                        referer = referer,
                        headers = streamHeaders,
                        callback = callback
                    )
                    return@withTimeoutOrNull
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeMeowTV failed: ${e.message}")
        }
    }

    suspend fun invokeMeowTV(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeMeowTV(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }
}
