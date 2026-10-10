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
import com.lagradost.cloudstream3.utils.loadExtractor
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
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.milliseconds

private val DAHMER_QUALITY_REGEX = Regex("(?i)(1080p|2160p)")


val session: Session by lazy { Session(app.baseClient) }

val webMutex = Mutex()
private val streamPlayExtractorMapper by lazy { jacksonObjectMapper() }
private val normalizeAlphaNumSpaceRegex = Regex("[^a-z0-9 ]")
private val normalizeAlphaNumRegex = Regex("[^a-z0-9]")


object StreamPlayExtractor : StreamPlay() {

    private val cloudflareKiller by lazy { CloudflareKiller() }
    val encDecApiSemaphore = Semaphore(4)
    val vidlinkEncCache = ConcurrentHashMap<Int, String>()

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
                        if (call.isCanceled()) {
                            cont.cancel(CancellationException(e.message, e))
                        } else {
                            cont.resume(null)
                        }
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
                            if (call.isCanceled() || t is CancellationException) {
                                cont.cancel(CancellationException(t.message, t))
                            } else {
                                cont.resume(null)
                            }
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
                        if (call.isCanceled()) {
                            cont.cancel(CancellationException(e.message, e))
                        } else {
                            cont.resume(null)
                        }
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

    suspend fun isValidM3u8(url: String?, headers: Map<String, String> = emptyMap()): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return false
        return try {
            val res = app.get(url, headers = headers, timeout = 5L)
            if (!res.isSuccessful) return false
            val text = res.text
            if (!text.contains("#EXTM3U", ignoreCase = true)) return false
            val hasStreams = text.contains("#EXT-X-STREAM-INF", ignoreCase = true)
            val hasSegments = text.contains("#EXTINF", ignoreCase = true)
            if (!hasStreams && !hasSegments) return false
            if (hasSegments && !hasStreams) {
                val totalDuration = Regex("""#EXTINF:([0-9.]+)""").findAll(text)
                    .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                    .sum()
                if (totalDuration <= 0.0) return false
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    /**
     * SOTA Emission for Top-Tier Sources (VidLink 100 > HexaSU 90 > AutoEmbed 80 > VidFast 70 > VidEasy 60 > VidSrc 55).
     * Guarantees that EVERY top-tier stream provides BOTH 720p and 1080p, with 720p strictly prioritized as #1
     * and 1080p as #2, followed by other qualities (480p, 4K, etc.) using STREAM_PRIORITY_COMPARATOR.
     *
     * - If generatedLinks is provided from generateM3u8:
     *   1. Tags each link with sota_dual_quality.
     *   2. Inspects the links for 720p and 1080p.
     *   3. If 720p is missing, synthesizes a 720p companion from the best available link/master stream.
     *   4. If 1080p is missing, synthesizes a 1080p companion from the best available link/master stream.
     *   5. Dispatches all links sorted by STREAM_PRIORITY_COMPARATOR (guaranteeing 720p is emitted first, then 1080p).
     *
     * - If generatedLinks is null or empty (e.g. direct MP4/MKV video or fallback M3U8):
     *   1. Emits 720p variant first (#1).
     *   2. Emits 1080p variant second (#2).
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

    suspend fun invoke2embed(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (imdbId.isNullOrBlank()) return

        val url = if (season == null) {
            "$twoEmbedAPI/embed/$imdbId"
        } else {
            "$twoEmbedAPI/embedtv/$imdbId&s=$season&e=$episode"
        }

        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to url,
        )

        val framesrc = app.post(
            url = url,
            data = mapOf("pls" to "pls"),
            headers = headers
        ).document.selectFirst("iframe#iframesrc")?.attr("data-src") ?: return

        val ref = getBaseUrl(framesrc)
        val id = framesrc.substringAfter("id=", "")
            .substringBefore("&")
            .takeIf { it.isNotBlank() } ?: return

        loadExtractor(
            "https://uqloads.xyz/e/$id",
            "$ref/",
            subtitleCallback,
            callback
        )
    }

    suspend fun invokeMultimovies(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val multimoviesApi = getDomains()?.multiMovies ?: return
        if (title.isNullOrBlank()) return

        val fixTitle = title.createSlug() ?: return

        val url = if (season == null) {
            "$multimoviesApi/movies/$fixTitle"
        } else {
            "$multimoviesApi/episodes/$fixTitle-${season}x${episode}"
        }

        val response = webMutex.withLock {
            safeGet(url, interceptor = cloudflareKiller)
        }

        if (response.code != 200) return

        val req = response.document
        if (req.text().contains("Just a moment", ignoreCase = true)) return

        val playerOptions = req.select("ul#playeroptionsul li").map {
            Triple(
                it.attr("data-post"),
                it.attr("data-nume"),
                it.attr("data-type")
            )
        }

        playerOptions.safeAmap { (postId, nume, type) ->
            if (nume.contains("trailer", ignoreCase = true)) return@safeAmap

            runCatching {
                val postResponse = app.post(
                    url = "$multimoviesApi/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to postId,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
                if (postResponse.code != 200) return@runCatching

                val responseData = tryParseJson<ResponseHash>(postResponse.text) ?: return@runCatching
                val embedUrl = responseData.embed_url

                val link = embedUrl
                    .trim()
                    .removeSurrounding("\"")
                    .takeIf { it.startsWith("http") }
                    ?: return@runCatching

                if (!link.contains("youtube", ignoreCase = true)) {
                    loadSourceNameExtractor(
                        "Multimovies",
                        link,
                        "$multimoviesApi/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }

    suspend fun invokeZshow(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug() ?: return
        val url = if (season == null) {
            "$zshowAPI/movie/$fixTitle-$year"
        } else {
            "$zshowAPI/episode/$fixTitle-season-$season-episode-$episode"
        }
        val response = safeGet(url)
        if (response.code != 200) return
        invokeWpmovies("ZShow", url, subtitleCallback, callback, encrypt = true)
    }

    private suspend fun invokeWpmovies(
        name: String? = null,
        url: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        fixIframe: Boolean = false,
        encrypt: Boolean = false,
        hasCloudflare: Boolean = false,
        interceptor: Interceptor? = null,
    ) {
        fun String.fixBloat(): String {
            return this.replace("\"", "").replace("\\", "")
        }

        val res = safeGet(
            url ?: return,
            interceptor = if (hasCloudflare) interceptor else null
        )
        val referer = getBaseUrl(res.url)
        val document = res.document
        document.select("ul#playeroptionsul > li").map {
            Triple(it.attr("data-post"), it.attr("data-nume"), it.attr("data-type"))
        }.safeAmap { (id, nume, type) ->
            val json = app.post(
                url = "$referer/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                headers = mapOf(
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                referer = url,
                interceptor = if (hasCloudflare) interceptor else null
            )
            val source = tryParseJson<ResponseHash>(json.text)?.let {
                when {
                    encrypt -> {
                        val meta =
                            tryParseJson<ZShowEmbed>(it.embed_url)?.meta ?: return@safeAmap
                        val key = generateWpKey(it.key ?: return@safeAmap, meta)
                        cryptoAESHandler(
                            it.embed_url,
                            key.toByteArray(),
                            false
                        )?.fixBloat() ?: return@safeAmap
                    }

                    fixIframe -> Jsoup.parse(it.embed_url).select("IFRAME").attr("SRC")
                    else -> it.embed_url
                }
            } ?: return@safeAmap
            when {
                !source.contains("youtube") -> {
                    loadDisplaySourceNameExtractor(
                        name,
                        name,
                        source,
                        "$referer/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }

    suspend fun invokeTokyoInsider(
        jptitle: String? = null,
        title: String? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        dubtype: String? = null
    ) {
        if (dubtype == null || (!dubtype.equals(
                "SUB",
                ignoreCase = true
            ) && !dubtype.equals("Movie", ignoreCase = true))
        ) return

        fun formatString(input: String?) = input?.replace(" ", "_").orEmpty()

        val jpFixTitle = formatString(jptitle)
        val fixTitle = formatString(title)
        val ep = episode ?: ""

        if (jpFixTitle.isBlank() && fixTitle.isBlank()) return

        var response =
            safeGet("https://www.tokyoinsider.com/anime/S/${jpFixTitle}_(TV)/episode/$ep")
        if (response.code != 200) return
        var doc = response.document

        if (doc.select("div.c_h2").text().contains("We don't have any files for this episode")) {
            response = safeGet("https://www.tokyoinsider.com/anime/S/${fixTitle}_(TV)/episode/$ep")
            if (response.code != 200) return
            doc = response.document
        }

        val href = doc.select("div.c_h2 > div:nth-child(1) > a").attr("href")
        if (href.isNotBlank()) {
            callback.invoke(
                newExtractorLink(
                    "TokyoInsider",
                    "TokyoInsider",
                    url = href,
                    INFER_TYPE
                ) {
                    this.referer = ""
                    val detectedQ = StreamLinkOptimizer.extractQualityFromText(href)
                    this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                }
            )
        }
    }

    suspend fun invokeAnizone(
        title: String? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?
    ) {

        if (dubtype == null || (!dubtype.equals(
                "SUB",
                ignoreCase = true
            ) && !dubtype.equals("Movie", ignoreCase = true))
        ) return

        val url = "https://anizone.to/anime?search=$title"

        val link = app.get(url).document.select("div.truncate > a").firstOrNull {
            it.text().contains(title.toString(), ignoreCase = true)
        }?.attr("href") ?: return

        val document = app.get("$link/$episode").document

        document.select("track").forEach {
            subtitleCallback.invoke(
                newSubtitleFile(
                    getLanguage(it.attr("label")),
                    it.attr("src")
                )
            )
        }

        val source = document.select("media-player").attr("src")
        callback.invoke(
            newExtractorLink(
                "Anizone",
                "⌜ Anizone ⌟ Multi Audio 🌐",
                source,
                type = ExtractorLinkType.M3U8,
            ) {
                val detectedQ = StreamLinkOptimizer.extractQualityFromText(source)
                this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
            }
        )
    }


    suspend fun invokeKisskh(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        lastSeason: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = title.createSlug() ?: return
        val type = if (season == null) "2" else "1"

        val searchResponse = safeGet(
            "$kissKhAPI/api/DramaList/Search?q=$title&type=$type",
            referer = "$kissKhAPI/"
        )
        if (searchResponse.code != 200) return

        val res = tryParseJson<ArrayList<KisskhResults>>(searchResponse.text) ?: return
        if (res.isEmpty()) return

        val (id, contentTitle) = if (res.size == 1) {
            res.first().id to res.first().title
        } else {
            val data = res.find {
                val slugTitle = it.title.createSlug() ?: return@find false
                when {
                    season == null -> slugTitle == slug
                    lastSeason == 1 -> slugTitle.contains(slug)
                    else -> slugTitle.contains(slug) && it.title?.contains(
                        "Season $season",
                        true
                    ) == true
                }
            } ?: res.find { it.title.equals(title) }
            data?.id to data?.title
        }

        if (id == null) return

        val detailResponse = safeGet(
            "$kissKhAPI/api/DramaList/Drama/$id?isq=false",
            referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}?id=$id"
        )
        if (detailResponse.code != 200) return

        val resDetail = detailResponse.parsedSafe<KisskhDetail>() ?: return
        val epsId = if (season == null) {
            resDetail.episodes?.firstOrNull()?.id
        } else {
            resDetail.episodes?.find { it.number == episode }?.id
        } ?: return

        val (kkey, kkey1) = coroutineScope {
            val videoKeyDeferred = async {
                try {
                    safeGet("${BuildConfig.KissKh}$epsId&version=2.8.10", timeout = 10L)
                        .parsedSafe<KisskhKey>()?.key
                } catch (_: Exception) {
                    null
                }
            }

            val subtitleKeyDeferred = async {
                try {
                    safeGet("${BuildConfig.KisskhSub}$epsId&version=2.8.10", timeout = 10L)
                        .parsedSafe<KisskhKey>()?.key
                } catch (_: Exception) {
                    null
                }
            }

            videoKeyDeferred.await() to subtitleKeyDeferred.await()
        }

        if (kkey == null || kkey1 == null) return

        val (sourcesData, subResponse) = coroutineScope {
            val sourcesDeferred = async {
                try {
                    safeGet(
                        "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkey",
                        referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}/Episode-${episode ?: 0}?id=$id&ep=$epsId&page=0&pageSize=100"
                    ).parsedSafe<KisskhSources>()
                } catch (_: Exception) {
                    null
                }
            }

            val subDeferred = async {
                try {
                    tryParseJson<List<KisskhSubtitle>>(safeGet("$kissKhAPI/api/Sub/$epsId&kkey=$kkey1").text)
                } catch (_: Exception) {
                    null
                }
            }

            sourcesDeferred.await() to subDeferred.await()
        }

        sourcesData?.let { source ->
            listOf(source.video, source.thirdParty).forEach { link ->
                val safeLink = link ?: return@forEach
                when {
                    safeLink.contains(".m3u8") || safeLink.contains(".mp4") -> {
                        val safe = safeLink.takeIf { it.startsWith("http") } ?: return@forEach

                        callback.invoke(
                            newExtractorLink(
                                "Kisskh",
                                "Kisskh",
                                safe,
                                INFER_TYPE
                            ) {
                                referer = kissKhAPI
                                val detectedQ = StreamLinkOptimizer.extractQualityFromText(safe)
                                quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                                headers = mapOf("Origin" to kissKhAPI)
                            }
                        )
                    }
                    else -> {
                        val cleanedLink = safeLink.substringBefore("?").takeIf { it.isNotBlank() }
                            ?: return@forEach
                        val detectedQ = StreamLinkOptimizer.extractQualityFromText(cleanedLink)
                        val resolvedQ = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                        loadSourceNameExtractor(
                            "Kisskh",
                            fixUrl(cleanedLink, kissKhAPI),
                            "$kissKhAPI/",
                            subtitleCallback,
                            callback,
                            resolvedQ
                        )
                    }
                }
            }
        }

        subResponse?.forEach { sub ->
            val lang = getLanguage(sub.label ?: "UnKnown")
            subtitleCallback.invoke(newSubtitleFile(lang, sub.src ?: return@forEach))
        }
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

    suspend fun invokeAnichi(
        name: String?,
        engtitle: String?,
        year: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {

        val isMovie = dubtype == "Movie"
        val privatereferer = "https://allmanga.to"
        val ephash = "d405d0edd690624b66baba3068e0edc3ac90f1597d898a1ec8db4e5c43c00fec"
        val queryhash = "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
        val type = if (episode == null) "Movie" else "TV"

        val normalizedName = normalizeTitle(name)
        val normalizedEngTitle = normalizeTitle(engtitle)

        val variables = if (isMovie) {
            """{"search":{"query":"$name"},"limit":26,"page":1,"translationType":"sub","countryOrigin":"ALL"}"""
        } else {
            """{"search":{"types":["$type"],"year":$year,"query":"$name"},"limit":26,"page":1,"translationType":"sub","countryOrigin":"ALL"}"""
        }

        val query =
            "${BuildConfig.ANICHI_API}?variables=$variables&extensions={\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"$queryhash\"}}"
        val response = safeGet(query, referer = privatereferer)
            .parsedSafe<AnichiRoot>()
            ?.data?.shows?.edges ?: return

        val matched = response.find { item ->
            val itemName = normalizeTitle(item.name)
            val itemEnglishName = normalizeTitle(item.englishName)

            (normalizedName != null && (itemName == normalizedName || itemEnglishName == normalizedName)) ||
                    (normalizedEngTitle != null && (itemName == normalizedEngTitle || itemEnglishName == normalizedEngTitle))
        } ?: return

        val id = matched.id
        val langTypes = listOf("sub", "dub")
        val headers =
            mapOf(
                "app-version" to "android_c-247",
                "from-app" to BuildConfig.ANICHI_APP,
                "platformstr" to "android_c",
                "Referer" to "https://allmanga.to"
            )

        langTypes.safeAmap { lang ->
            if (isMovie || (dubtype != null && lang.contains(dubtype, ignoreCase = true))) {
                val epQuery =
                    """${BuildConfig.ANICHI_API}?variables={"showId":"$id","translationType":"$lang","episodeString":"${episode ?: 1}"}&extensions={"persistedQuery":{"version":1,"sha256Hash":"$ephash"}}"""
                val episodeLinks = safeGet(epQuery, referer = privatereferer, headers = headers)
                    .parsedSafe<AnichiEP>()
                    ?.data?.episode?.sourceUrls ?: return@safeAmap

                episodeLinks.safeAmap { source ->
                    safeApiCall {
                        val sourceUrl = source.sourceUrl

                        if (sourceUrl.startsWith("http")) {
                            val host = sourceUrl.getHost()
                            loadDisplaySourceNameExtractor(
                                "Allanime",
                                "⌜ Allanime ⌟ | $host | [${lang.uppercase()}]",
                                sourceUrl,
                                "",
                                subtitleCallback,
                                callback
                            )
                            return@safeApiCall
                        }

                        if (URI(sourceUrl).isAbsolute || sourceUrl.startsWith("//")) {
                            val fixedLink =
                                if (sourceUrl.startsWith("//")) "https:$sourceUrl" else sourceUrl
                            val host = fixedLink.getHost()

                            loadDisplaySourceNameExtractor(
                                "Allanime",
                                "⌜ Allanime ⌟ | $host | [${lang.uppercase()}]",
                                fixedLink,
                                "",
                                subtitleCallback,
                                callback
                            )

                            return@safeApiCall
                        }

                        val decoded =
                            if (sourceUrl.startsWith("--")) decrypthex(sourceUrl) else sourceUrl
                        val fixedLink = decoded.fixUrlPath()
                        val links = try {
                            safeGet(fixedLink, headers = headers)
                                .parsedSafe<AnichiVideoApiResponse>()
                                ?.links ?: emptyList()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            return@safeApiCall
                        }

                        links.forEach { server ->
                            val host = server.link.getHost()
                            when {
                                source.sourceName.contains("Default") && server.resolutionStr in listOf(
                                    "SUB",
                                    "Alt vo_SUB"
                                ) -> {
                                    getM3u8Qualities(
                                        server.link,
                                        "https://static.crunchyroll.com/",
                                        host
                                    ).forEach(callback)
                                }


                                source.sourceName.contains("Uns") -> {
                                    loadDisplaySourceNameExtractor(
                                        "Allanime VidStack",
                                        "⌜ Allanime VidStack ⌟ | $host | [${lang.uppercase()}]",
                                        sourceUrl,
                                        "",
                                        subtitleCallback,
                                        callback
                                    )
                                }

                                server.hls == null -> {
                                    callback.invoke(
                                        newExtractorLink(
                                            "Allanime",
                                            "⌜ Allanime ⌟ | ${host.capitalize()} | [${lang.uppercase()}]",
                                            server.link,
                                            INFER_TYPE
                                        ) {
                                            val detectedQ = StreamLinkOptimizer.extractQualityFromText(server.link)
                                            this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                                        }
                                    )
                                }


                                server.hls -> {
                                    val endpoint = "https://allanime.day/player?uri=" +
                                            if (URI(server.link).host.isNotEmpty()) server.link
                                            else "https://allanime.day${URI(server.link).path}"
                                    getM3u8Qualities(
                                        server.link,
                                        server.headers?.referer ?: endpoint,
                                        host
                                    ).forEach(callback)
                                }

                                else -> {
                                    server.subtitles?.forEach { sub ->
                                        val langName =
                                            SubtitleHelper.fromTagToEnglishLanguageName(
                                                sub.lang ?: ""
                                            )
                                                ?: sub.lang.orEmpty()
                                        val src = sub.src ?: return@forEach
                                        subtitleCallback(newSubtitleFile(langName, httpsify(src)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    suspend fun invokeAnineko(
        title: String?,
        jpTitle: String?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        if (title == null || episode == null) return
        val isDub = dubtype == "DUB"
        
        var slug: String? = null
        val searchTitles = listOfNotNull(jpTitle, title).filter { it.isNotBlank() }
        for (searchTitle in searchTitles) {
            val searchUrl = "$anineko/browser?keyword=$searchTitle"
            val searchDoc = app.get(searchUrl).document
            val firstResult = searchDoc.selectFirst("a.nv-anime-thumb")
            val href = firstResult?.attr("href")
            if (href != null) {
                slug = href.substringAfterLast("/watch/").substringBefore("?")
                break
            }
        }
        if (slug == null) return

        val url = "$anineko/watch/$slug/ep-$episode"

        val doc = app.get(url).document

        val panels = doc.select(".nv-server-grid")
        val targetPanels = if (panels.isNotEmpty()) {
            panels.filter {
                val dataId = it.attr("data-id").lowercase()
                if (isDub) dataId.contains("dub") else !dataId.contains("dub")
            }
        } else {
            listOf(doc)
        }

        targetPanels.forEach { panel ->
            panel.select(".server-video").forEach { serverBtn ->
                val videoUrl = serverBtn.attr("data-video")
                val serverName = serverBtn.ownText().trim()
                val typeName = serverBtn.selectFirst("span")?.text()

                val subMatch = Regex("""(?:sub|caption_1|c1_file)=([^&]+)""").find(videoUrl)
                if (subMatch != null) {
                    val subUrl = subMatch.groupValues[1]
                    val subLang = Regex("""(?:sub_1|c1_label)=([^&]+)""").find(videoUrl)?.groupValues?.get(1) ?: "English"
                    subtitleCallback.invoke(newSubtitleFile(subLang, subUrl))
                }

                val finalUrl = if (videoUrl.startsWith("//")) "https:$videoUrl" else videoUrl
                val embedDoc = app.get(finalUrl, headers = mapOf("Referer" to "$anineko/")).text

                val hlsRegexes = listOf(
                    Regex("""const\s+src\s*=\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""["'](https?://[^"']+/master\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                    Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
                )

                var m3u8Url: String? = null
                for (regex in hlsRegexes) {
                    val match = regex.find(embedDoc)
                    if (match != null) {
                        m3u8Url = match.groupValues[1]
                        break
                    }
                }

                if (m3u8Url != null) {
                    val sourceName = if (typeName != null) "$serverName - $typeName" else serverName
                    generateM3u8(
                        sourceName,
                        m3u8Url,
                        finalUrl
                    ).forEach { link ->
                        val newLink = newExtractorLink(
                            source = "AniNeko",
                            name = "⌜ AniNeko ⌟ " + link.name,
                            url = link.url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                        callback.invoke(newLink)
                    }
                } else if (serverName.contains("HD-")) {
                    val host = Regex("""https?://([^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""
                    val extractor = object : com.lagradost.cloudstream3.extractors.StreamWishExtractor() {
                        override var mainUrl = "https://$host"
                        override var name = serverName
                    }
                    val links = mutableListOf<ExtractorLink>()
                    extractor.getUrl(finalUrl, "https://$host/", subtitleCallback) { link ->
                        links.add(link)
                    }
                    links.forEach { link ->
                        val newLink = newExtractorLink(
                            source = "AniNeko",
                            name = "⌜ AniNeko ⌟ " + link.name + if (typeName != null) " - $typeName" else "",
                            url = link.url,
                            type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                        callback.invoke(newLink)
                    }
                } else {
                    val links = mutableListOf<ExtractorLink>()
                    loadExtractor(finalUrl, "$anineko/", subtitleCallback) { link ->
                        links.add(link)
                    }
                    links.forEach { link ->
                        val newLink = newExtractorLink(
                            source = "AniNeko",
                            name = "⌜ AniNeko ⌟ " + link.name + if (typeName != null) " - $typeName" else "",
                            url = link.url,
                            type = if (link.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.quality = link.quality
                            this.headers = link.headers
                            this.extractorData = link.extractorData
                        }
                        callback.invoke(newLink)
                    }
                }
            }
        }
    }

    private fun normalizeAnimePaheUrl(url: String): String {
        val legacyHosts = listOf("animepahe.si", "animepahe.com", "animepahe.org", "animepahe.ru", "animepahe.su")
        return legacyHosts.fold(url) { normalized, host ->
            normalized.replace(host, "animepahe.pw", ignoreCase = true)
        }
    }


    suspend fun invokeAnimepahe(
        url: String,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        val isMovie = dubtype == "Movie"
        val headers = mapOf("Cookie" to "__ddg2_=1234567890")

        val id = safeGet(normalizeAnimePaheUrl(url), headers)
            .document.selectFirst("meta[property=og:url]")
            ?.attr("content").toString().substringAfterLast("/")

        val animeData = safeGet(
            "$animepaheAPI/api?m=release&id=$id&sort=episode_asc&page=1",
            headers
        ).parsedSafe<animepahe>()?.data.orEmpty()

        val targetIndex = (episode ?: 1) - 1
        if (targetIndex !in animeData.indices) return
        val session = animeData[targetIndex].session

        val document = safeGet(
            "$animepaheAPI/play/$id/$session",
            headers
        ).document

        document.select("#resolutionMenu button").safeAmap {
            val dubText = it.select("span").text().lowercase()
            val type = if ("eng" in dubText) "DUB" else "SUB"

            val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4}p)""")
            val text = it.text()
            val match = qualityRegex.find(text)

            val source = match?.groupValues?.getOrNull(1)?.trim() ?: "Unknown"
            val quality = match?.groupValues?.getOrNull(2)?.substringBefore("p")?.toIntOrNull()
                ?: Qualities.Unknown.value

            val href = it.attr("data-src")
            if ("kwik" in href && (isMovie || (dubtype != null && type.contains(
                    dubtype,
                    ignoreCase = true
                )))
            ) {
                loadDisplaySourceNameExtractor(
                    "Animepahe",
                    "⌜ Animepahe ⌟ $source | [$type]",
                    href,
                    "",
                    subtitleCallback,
                    callback,
                    quality
                )
            }
        }

        document.select("div#pickDownload > a").safeAmap {
            val qualityRegex = Regex("""(.+?)\s+·\s+(\d{3,4}p)""")

            val href = it.attr("href")
            var type = "SUB"
            if (it.select("span").text().contains("eng", ignoreCase = true))
                type = "DUB"

            val text = it.text()
            val match = qualityRegex.find(text)
            val source = match?.groupValues?.getOrNull(1) ?: "Unknown"
            val quality = match?.groupValues?.getOrNull(2)?.substringBefore("p") ?: "Unknown"
            if (isMovie || (dubtype != null && type.contains(dubtype, ignoreCase = true))) {
                loadDisplaySourceNameExtractor(
                    "Animepahe Pahe",
                    "⌜ Animepahe ⌟ Pahe $source | [$type]",
                    href,
                    "",
                    subtitleCallback,
                    callback,
                    quality.toIntOrNull()
                )
            }
        }
    }

    //AnimetoSho
    private data class ServerLink(
        val url: String,
        val size: String,
        val qualityIndex: Int,
        val group: String,
        val meta: ReleaseMeta
    )

    data class ReleaseMeta(
        val group: String,
        val resolution: Int?,
        val codec: String?,
        val platform: String?,
        val source: String?,
        val audio: String?,
        val subtitles: String?,
        val bitDepth: Int? = null,
        val audioCodec: String? = null
    )

    fun parseReleaseMeta(title: String): ReleaseMeta =
        ReleaseMeta(
            group = GROUP_REGEX.find(title)?.groupValues?.get(1).orEmpty(),
            resolution = RES_REGEX.find(title)?.groupValues?.get(1)?.toIntOrNull(),
            codec = CODEC_REGEX.find(title)?.value?.uppercase(),
            platform = PLATFORM_REGEX.find(title)?.value?.uppercase(),
            source = SOURCE_REGEX.find(title)?.value?.replace(" ", "-")?.uppercase(),
            audio = AUDIO_REGEX.find(title)?.value,
            subtitles = SUB_REGEX.find(title)?.value,
            bitDepth = BIT_DEPTH_REGEX.find(title)
                ?.value
                ?.filter { it.isDigit() }
                ?.toIntOrNull(),
            audioCodec = AUDIO_CODEC_REGEX.find(title)?.value?.uppercase()
        )


    private val GROUP_REGEX = Regex("""^\[(.*?)]""")

    private val HOST_REGEX =
        Regex("""KrakenFiles|GoFile|AkiraBox|BuzzHeavier""", RegexOption.IGNORE_CASE)

    private val RES_REGEX = Regex("""(4320|2160|1440|1080|720|480)p""", RegexOption.IGNORE_CASE)

    private val CODEC_REGEX =
        Regex("""AV1|HEVC|H\.?265|x265|H\.?264|x264""", RegexOption.IGNORE_CASE)

    private val PLATFORM_REGEX = Regex("""AMZN|NF|CR|BILI|IQIYI""", RegexOption.IGNORE_CASE)

    private val SOURCE_REGEX = Regex("""WEB[- .]?DL|WEB[- .]?Rip""", RegexOption.IGNORE_CASE)

    private val AUDIO_REGEX =
        Regex("""Dual[- ]?Audio|Eng(lish)?[- ]?Dub|Japanese""", RegexOption.IGNORE_CASE)

    private val AUDIO_CODEC_REGEX =
        Regex("""AAC(\d\.\d)?|DDP(\d\.\d)?|OPUS|FLAC""", RegexOption.IGNORE_CASE)

    private val BIT_DEPTH_REGEX = Regex("""10[- ]?bit|10bits|8[- ]?bit""", RegexOption.IGNORE_CASE)

    private val SUB_REGEX = Regex(
        """Multi[- ]?Subs?|MultiSub|Multiple Subtitles|Eng(lish)?[- ]?Sub|ESub|Subbed""",
        RegexOption.IGNORE_CASE
    )


    private fun Elements.getLinks(): List<ServerLink> =
        flatMap { ele ->
            val title = ele.select("div.link a").text()
            val meta = parseReleaseMeta(title)

            val size = ele.select("div.size").text()
            val quality = meta.resolution ?: Qualities.Unknown.value

            ele.select("div.links a:matches(${HOST_REGEX.pattern})").map { a ->
                ServerLink(
                    url = a.attr("href"),
                    size = size,
                    qualityIndex = quality,
                    group = meta.group,
                    meta = meta
                )
            }
        }

    suspend fun invokeAnimetosho(
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
        anidbEid: Int?
    ) {
        if (dubtype !in setOf("SUB", "Movie")) return

        val url = "$animetoshoAPI/episode/$anidbEid"

        val res = safeGet(url).document

        res.select("div.home_list_entry:has(div.links)")
            .getLinks()
            .asSequence()
            .filter {
                it.qualityIndex == Qualities.P1080.value ||
                        it.qualityIndex == Qualities.P720.value
            }
            .forEach { it ->
                val displayName = buildString {
                    append("⌜ Animetosho ⌟")

                    if (it.meta.group.isNotBlank())
                        append(" ${it.meta.group}")

                    val sourceBlock = listOfNotNull(
                        it.meta.platform,
                        it.meta.source
                    ).joinToString(" ")

                    listOfNotNull(
                        sourceBlock.takeIf { it.isNotBlank() },
                        it.meta.audio,
                        it.meta.subtitles,
                        it.meta.codec,
                        it.size.takeIf { it.isNotBlank() }
                    ).joinToString(" | ").let {
                        if (it.isNotBlank()) append(" | $it")
                    }
                }

                loadDisplaySourceNameExtractor(
                    "Animetosho ${it.meta.group}",
                    displayName,
                    it.url,
                    "$animetoshoAPI/",
                    subtitleCallback,
                    callback,
                    "${it.meta.resolution}".toIntOrNull()
                )
            }
    }


    suspend fun invokeReAnime(
        anilistId: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        val type = when (dubtype) {
            "DUB" -> "dub"
            else -> "sub"
        }

        val res = app.get("$reanime/api/flix/$anilistId/$episode")
            .parsedSafe<ReAnime>()
            ?.servers
            ?.filter { it.dataType.equals(type, true) }
            ?: return

        res.forEach {
            loadDisplaySourceNameExtractor(
                "ReAnime ${it.serverName}",
                "ReAnime ${it.serverName} [${it.dataType.capitalize()}]",
                it.dataLink,
                "$reanime/",
                subtitleCallback,
                callback
            )
        }
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
        val isMovie = dubtype == "Movie"
        val json = "application/json; charset=utf-8".toMediaType()
        val epNum = if (isMovie) 1 else (episode ?: return)
        val searchTitle = title ?: return

        val headers = mapOf(
            "Origin" to base64Decode("aHR0cHM6Ly9hbmltZXgub25l"),
            "Referer" to base64Decode("aHR0cHM6Ly9hbmltZXgub25lLw=="),
            "User-Agent" to USER_AGENT,
            "Content-Type" to "application/json"
        )

        val body = """
        {
          "query":"query FastSearch(${'$'}query: String, ${'$'}limit: Int) { catalogAnime(filter: { query: ${'$'}query }, limit: ${'$'}limit) { items { id anilistId malId titleRomaji titleEnglish format } } }",
          "variables":{
            "query":"$searchTitle",
            "limit":10
          }
        }
    """.trimIndent()

        val response = app.post(
            base64Decode("aHR0cHM6Ly9ncmFwaHFsLmFuaW1leC5vbmUvZ3JhcGhxbA=="),
            requestBody = body.toRequestBody(json),
            headers = headers
        ).parsedSafe<SearchResponse>() ?: return

        val anime = response.data.catalogAnime.items.firstOrNull {
            when {
                anilistId != null -> it.anilistId == anilistId
                malId != null -> it.malId == malId
                else -> false
            }
        } ?: response.data.catalogAnime.items.firstOrNull {
            it.titleEnglish.equals(searchTitle, true) ||
                    it.titleRomaji.equals(searchTitle, true)
        } ?: response.data.catalogAnime.items.firstOrNull() ?: return

        val servers = app.get(
            base64Decode("aHR0cHM6Ly9wcC5hbmltZXgub25lL3Jlc3QvYXBpL3NlcnZlcnM="),
            params = mapOf(
                "id" to anime.id,
                "epNum" to epNum.toString()
            ),
            headers = headers
        ).parsedSafe<ServerResponse>() ?: return

        val requestTypes = when {
            isMovie -> listOf("sub", "dub")
            dubtype.equals("dub", true) -> listOf("dub")
            else -> listOf("sub")
        }

        val providers = buildList {

            if ("sub" in requestTypes) {
                servers.subProviders.forEach {
                    add("sub" to it.id)
                }
            }

            if ("dub" in requestTypes) {
                servers.dubProviders.forEach {
                    add("dub" to it.id)
                }
            }
        }.sortedBy { (type, id) ->

            val provider = when (type) {
                "sub" -> servers.subProviders.find { it.id == id }
                else -> servers.dubProviders.find { it.id == id }
            }

            !(provider?.default ?: false)
        }

        for ((type, providerId) in providers) {

            delay((400L..900L).random().milliseconds)

            runCatching {

                val sourceResponse = app.get(
                    base64Decode("aHR0cHM6Ly9wcC5hbmltZXgub25lL3Jlc3QvYXBpL3NvdXJjZXM="),
                    params = mapOf(
                        "id" to anime.id,
                        "epNum" to epNum.toString(),
                        "type" to type,
                        "providerId" to providerId
                    ),
                    headers = headers
                ).parsedSafe<SourceResponse>() ?: return@runCatching

                val referer = sourceResponse.headers?.get("Referer")
                    ?: "https://animex.one/"


                sourceResponse.sources?.forEach { source ->
                    val providerInfo = when (type) {
                        "sub" -> servers.subProviders.find { it.id == providerId }
                        else -> servers.dubProviders.find { it.id == providerId }
                    }

                    val subType = providerInfo?.tip
                        ?.substringBefore(",")
                        ?.trim()
                        ?: "Unknown"

                    callback.invoke(
                        newExtractorLink(
                            "Animex",
                            "Animex [$subType] [${type.replaceFirstChar(Char::uppercase)}] [${providerId.replaceFirstChar(Char::uppercase)}]",
                            source.url
                        ) {
                            this.referer = referer
                            quality = getQualityFromName(source.quality)
                                .takeIf { it != Qualities.Unknown.value }
                                ?: Qualities.Unknown.value
                        }
                    )
                }

                sourceResponse.tracks?.forEach { track ->
                    track.file?.let {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                track.label ?: "Unknown",
                                it
                            )
                        )
                    }
                }

            }.onFailure {
                println("Animex failed [$type][$providerId] : ${it.message}")
            }
        }
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
        val quality: String? =null
    )

    data class Track(
        val file: String? = null,
        val label: String? = null
    )

    //Broken for now
    suspend fun invokeHianime(
        malId: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        if (malId == null || episode == null) return

        val name = "HiAnime"
        val megaBase = "https://megaplay.buzz"
        val vidwishBase = "https://vidwish.live"
        val megacloudbloggy = "https://megacloud.bloggy.click"
        val type = if (dubtype.equals("sub", true)) "sub" else "dub"

        val megaUrl = "$megaBase/stream/mal/$malId/$episode/$type"

        val doc = try {
            app.get(megaUrl, referer = megaUrl, timeout = 10L).document
        } catch (_: Exception) {
            return
        }

        val player = doc.selectFirst("div.fix-area#megaplay-player") ?: return
        val dataId = player.attr("data-id").takeIf(String::isNotBlank)
        val realId = player.attr("data-realid").takeIf(String::isNotBlank)

        suspend fun process(
            displayName: String,
            apiUrl: String,
            referer: String,
            origin: String
        ) {

            val mainHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
                "Accept" to "*/*",
                "Origin" to origin,
                "Referer" to origin,
                "Connection" to "keep-alive",
                "Pragma" to "no-cache",
                "Cache-Control" to "no-cache"
            )

            try {
                val json = app.get(
                    apiUrl,
                    referer = referer,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    timeout = 10L
                ).parsedSafe<HiAnimeSourcesResponse>() ?: return

                val file = json.sources?.file
                if (!file.isNullOrBlank()) {
                    generateM3u8(
                        displayName,
                        file,
                        origin,
                        headers = mainHeaders
                    ).forEach(callback)
                }

                val tracks = json.tracks
                if (!tracks.isNullOrEmpty()) {
                    tracks.forEach {
                        val sub = it.file ?: return@forEach
                        subtitleCallback(
                            newSubtitleFile(it.label ?: "Unknown", sub)
                            {
                                this.headers = mapOf(
                                    "Referer" to "$origin/"
                                )
                            }
                        )
                    }
                }

            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        coroutineScope {
            // -------- MegaPlay --------
            val megaDeferred = async {
                dataId?.let {
                    process(
                        displayName = "[$name] MegaPlay",
                        apiUrl = "$megaBase/stream/getSources?id=$it&id=$it",
                        referer = megaUrl,
                        origin = "$megaBase/"
                    )
                }
            }

            // -------- Vidwish --------
            val vidwishDeferred = async {
                realId?.let { rid ->
                    val vidPage = "$vidwishBase/stream/s-2/$rid/$type"

                    try {
                        val vidDoc = app.get(vidPage, referer = megaUrl, timeout = 10L).document

                        val vidPlayer = vidDoc.selectFirst("div.fix-area#megaplay-player")
                        val vidDataId = vidPlayer?.attr("data-id")?.takeIf(String::isNotBlank)

                        vidDataId?.let { vidId ->
                            process(
                                displayName = "[$name] Vidwish",
                                apiUrl = "$vidwishBase/stream/getSources?id=$vidId&id=$vidId",
                                referer = vidPage,
                                origin = "$vidwishBase/"
                            )
                        }

                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
            }

            // -------- Megacloudbloggy --------
            val megacloudDeferred = async {
                realId?.let { rid ->
                    val megacloudPage = "$megacloudbloggy/stream/s-3/$rid/$type"

                    try {
                        val megacloudDoc = app.get(megacloudPage, referer = megaUrl, timeout = 10L).document

                        val megacloudPlayer = megacloudDoc.selectFirst("div.fix-area#megaplay-player")
                        val megacloudDataId = megacloudPlayer?.attr("data-id")?.takeIf(String::isNotBlank)

                        megacloudDataId?.let { vidId ->
                            process(
                                displayName = "[$name] MegaCloud",
                                apiUrl = "$megacloudbloggy/stream/getSources?id=$vidId&id=$vidId",
                                referer = megacloudbloggy,
                                origin = "$megacloudbloggy/"
                            )
                        }

                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }
            }

            megaDeferred.await()
            vidwishDeferred.await()
            megacloudDeferred.await()
        }
    }

    suspend fun invokeKickAssAnime(
        engtitle: String?,
        slugid: String?,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        val isMovie = dubtype == "Movie"
        var slug = slugid
        if (slug.isNullOrBlank()) {
            if (engtitle.isNullOrBlank()) return

            val host = "https://kickass-anime.ro/"

            val json = """
                {
                    "page": 1,
                    "query": "$engtitle"
                }
            """.trimIndent()

            val requestBody = json.toRequestBody("application/json".toMediaType())

            val headers = mapOf(
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Content-Type" to "application/json",
                "x-origin" to "kickass-anime.ru"
            )

            val searchJson = app
                .post("${host}api/fsearch", requestBody = requestBody, headers = headers)
                .toString()

            val resultArray = JSONObject(searchJson).optJSONArray("result") ?: return

            for (i in 0 until resultArray.length()) {
                val item = resultArray.getJSONObject(i)
                if (item.optString("title_en", "")
                        .equals(engtitle, ignoreCase = true)
                ) {
                    slug = item.optString("slug")
                    break
                }
            }
        }

        val locales: List<String> = when {
            isMovie -> listOf("en-US", "ja-JP") // BOTH
            dubtype.equals("DUB", ignoreCase = true) -> listOf("en-US")
            dubtype.equals("SUB", ignoreCase = true) -> listOf("ja-JP")
            else -> return // null or invalid → block
        }

        locales.safeAmap { locale ->
            val json = safeGet(
                "$KickassAPI/api/show/$slug/episodes?ep=1&lang=$locale",
                timeout = 5L
            ).toString()

            val jsonresponse = parseJsonToEpisodes(json)

            val matchedSlug = jsonresponse.firstOrNull {
                it.episode_number.toString()
                    .substringBefore(".")
                    .toIntOrNull() == episode
            }?.slug ?: return@safeAmap

            val href = "$KickassAPI/api/show/$slug/episode/ep-$episode-$matchedSlug"
            val servers = safeGet(href).parsedSafe<ServersResKAA>()?.servers ?: return@safeAmap

            servers.safeAmap { server ->
                if (server.name.contains("VidStreaming")) {
                    val host = getBaseUrl(server.src)
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    )

                    val key = "e13d38099bf562e8b9851a652d2043d3".toByteArray()
                    val query = server.src.substringAfter("?id=").substringBefore("&")
                    val html = safeGet(server.src).toString()

                    //If HTML have m3u8
                    if (html.contains(".m3u8", ignoreCase = true)) {
                        val match =
                            Regex("""(https?:)?//[^\s"'<>]+\.m3u8""", RegexOption.IGNORE_CASE)
                                .find(html)

                        val videoheaders = mapOf(
                            "Accept" to "*/*",
                            "Accept-Language" to "en-US,en;q=0.5",
                            "Origin" to host,
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site"
                        )

                        match?.value?.let { url ->
                            val m3u8Url = if (url.startsWith("//")) "https:$url" else url
                            callback.invoke(
                                newExtractorLink(
                                    "VidStreaming",
                                    "VidStreaming",
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                )
                                {
                                    val detectedQ = StreamLinkOptimizer.extractQualityFromText(m3u8Url)
                                    this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                                    this.headers = videoheaders
                                }
                            )
                        }
                    }

                    val (sig, timeStamp, route) = getSignature(html, server.name, query, key)
                        ?: return@safeAmap
                    val sourceUrl = "$host$route?id=$query&e=$timeStamp&s=$sig"

                    val encJson =
                        safeGet(sourceUrl, headers = headers).parsedSafe<EncryptedKAA>()?.data
                            ?: return@safeAmap

                    val (encryptedData, ivHex) = encJson
                        .substringAfter(":\"")
                        .substringBefore('"')
                        .split(":")
                    val decrypted = tryParseJson<m3u8KAA>(
                        CryptoAES.decrypt(encryptedData, key, ivHex.decodeHex()).toJson()
                    ) ?: return@safeAmap

                    val m3u8 = httpsify(decrypted.hls)
                    val videoHeaders = mapOf(
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.5",
                        "Origin" to host,
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )

                    callback.invoke(
                        newExtractorLink(
                            server.name,
                            server.name,
                            m3u8,
                            ExtractorLinkType.M3U8
                        )
                        {
                            val detectedQ = StreamLinkOptimizer.extractQualityFromText(server.name, m3u8)
                            this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                            this.headers = videoHeaders
                        }
                    )

                    decrypted.subtitles.forEach { subtitle ->
                        subtitleCallback(newSubtitleFile(subtitle.name, httpsify(subtitle.src)))
                    }
                } else if (server.name.contains("CatStream")) {
                    val baseurl = getBaseUrl(server.src)
                    val headers = mapOf(
                        "Origin" to baseurl,
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    )

                    val res = safeGet(
                        server.src,
                        headers = headers
                    ).text

                    val regex = Regex("""props="(.*?)"""")
                    val match = regex.find(res)
                    val encodedJson = match?.groupValues?.get(1)

                    if (encodedJson != null) {
                        val unescapedJson = Parser.unescapeEntities(encodedJson, false)
                        val json = JSONObject(unescapedJson)

                        val videoUrl = "https:" + json.getJSONArray("manifest").getString(1)
                        callback.invoke(
                            newExtractorLink(
                                "CatStream",
                                "CatStream HLS",
                                videoUrl,
                                ExtractorLinkType.M3U8
                            )
                            {
                                val detectedQ = StreamLinkOptimizer.extractQualityFromText(videoUrl)
                                this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                                this.headers = headers
                            }
                        )

                        val subtitleArray = json.getJSONArray("subtitles").getJSONArray(1)

                        for (i in 0 until subtitleArray.length()) {
                            val sub = subtitleArray.getJSONArray(i).getJSONObject(1)

                            val src = sub.getJSONArray("src").getString(1)
                            val name = sub.getJSONArray("name").getString(1)
                            subtitleCallback.invoke(
                                newSubtitleFile(
                                    name,  // Use label for the name
                                    httpsify(src)    // Use extracted URL
                                )
                            )
                        }

                    } else {
                        println("Could not find embedded JSON in props attribute")
                    }
                }
            }
        }
    }


    suspend fun invokeUhdmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val domain = getDomains()?.uhdmovies ?: return

        val query = title
            ?.replace("-", " ")
            ?.replace(":", " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        val searchUrl = "$domain/search/${
            withContext(Dispatchers.IO) {
                URLEncoder.encode("$query $year", "UTF-8")
            }
        }"

        val redirectRegex = Regex("""window\.location\.replace\(["'](.*?)["']\)""")

        val pageUrl = safeGet(searchUrl).document
            .selectFirst("article div.entry-image a")
            ?.attr("href")
            ?: return

        val doc = safeGet(pageUrl).document

        val selector = if (season == null) {
            "div.entry-content p:matches($year)"
        } else {
            "div.entry-content p:matches((?i)(S0?$season|Season 0?$season))"
        }

        val epSelector = if (season == null) {
            "a:matches((?i)Download)"
        } else {
            "a:matches((?i)Episode $episode)"
        }

        val links = doc.select(selector)
            .asSequence()
            .mapNotNull {
                it.nextElementSibling()
                    ?.select(epSelector)
                    ?.attr("href")
            }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        links.safeAmap { link ->
            val driveLink = try {
                if (link.contains("driveleech", true) || link.contains("driveseed", true)) {
                    val text = safeGet(link).text
                    val fileId = redirectRegex.find(text)?.groupValues?.getOrNull(1)
                        ?: return@safeAmap
                    getBaseUrl(link) + fileId
                } else {
                    bypassHrefli(link) ?: return@safeAmap
                }
            } catch (_: Exception) {
                return@safeAmap
            }

            loadSourceNameExtractor(
                "UHDMovies",
                driveLink,
                "",
                subtitleCallback,
                callback
            )
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


    suspend fun invokeMapple(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (tmdbId == null) return

        val base = mappleAPI.removeSuffix("/")

        val mediaType = if (season == null) "movie" else "tv"
        val tvSlug = if (season != null && episode != null) "$season-$episode" else ""

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$base/",
            "Origin" to base,
            "Accept" to "*/*",
            "Content-Type" to "application/json"
        )

        val watchUrl = if (mediaType == "movie") {
            "$base/watch/movie/$tmdbId"
        } else {
            "$base/watch/tv/$tmdbId/$tvSlug"
        }

        val page = safeGet(watchUrl, headers = headers).text
        val tokenRegex = Regex("""window\.__REQUEST_TOKEN__\s*=\s*"([^"]+)"""")
        val requestToken = tokenRegex.find(page)?.groupValues?.get(1) ?: return

        val body = """
        {
            "mediaId": $tmdbId,
            "mediaType": "$mediaType",
            "requestToken": "$requestToken"
        }
    """.trimIndent()

        val tokenRes1 = JSONObject(
            app.post("$base/api/stream-token", requestBody = body.toRequestBody(), headers = headers).text
        )

        if (!tokenRes1.optBoolean("success")) return

        val finalToken = if (tokenRes1.optBoolean("requiresPow")) {
            val pow = tokenRes1.getJSONObject("pow")

            val nonce = solvePowChallenge(
                pow.getString("challenge"),
                pow.getInt("difficulty")
            ) ?: return

            val body2 = """
            {
                "mediaId": $tmdbId,
                "mediaType": "$mediaType",
                "requestToken": "$requestToken",
                "pow": {
                    "challengeId": "${pow.getString("challengeId")}",
                    "nonce": "$nonce"
                }
            }
        """.trimIndent()

            val tokenRes2 = JSONObject(
                app.post("$base/api/stream-token", requestBody = body2.toRequestBody(), headers = headers).text
            )

            if (!tokenRes2.optBoolean("success")) return
            tokenRes2.getString("token")
        } else {
            tokenRes1.getString("token")
        }

        val sources = listOf(
            "mapple",
            "willow",
            "cherry",
            "pines",
            "oak",
            "sequoia",
            "sakura",
            "magnolia"
        )

        sources.safeAmap { source ->
            try {
                val streamUrl =
                    "$base/api/stream?mediaId=$tmdbId&mediaType=$mediaType&tv_slug=$tvSlug" +
                            "&source=$source&apikey=mptv_sk_a8f29c4e7b3d1f" +
                            "&requestToken=$requestToken&token=$finalToken"

                val streamRes = JSONObject(safeGet(streamUrl, headers = headers).text)

                if (!streamRes.optBoolean("success")) return@safeAmap

                val m3u8 = streamRes
                    .getJSONObject("data")
                    .optString("stream_url")

                if (m3u8.isNotEmpty()) {
                    generateM3u8(
                        "Mapple [${source.uppercase()}]",
                        m3u8,
                        "$base/",
                        headers = headers
                    ).forEach(callback)
                }

            } catch (_: Exception) {
            }
        }
    }





    suspend fun invokeVidzee(
        id: Int?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val secret = base64Decode("cGxlYXNlZG9udHNjcmFwZW1lc2F5d2FsbGFoaQ==")
        val keyBytes = secret.padEnd(32, '\u0000').toByteArray(Charsets.UTF_8)
        val defaultReferer = "https://player.vidzee.wtf/"

        (1..8).toList().safeAmap { sr ->
            try {
                val apiUrl = if (season == null) {
                    "https://player.vidzee.wtf/api/server?id=$id&sr=$sr"
                } else {
                    "https://player.vidzee.wtf/api/server?id=$id&sr=$sr&ss=$season&ep=$episode"
                }

                val response = safeGet(apiUrl).text
                val json = JSONObject(response)

                val globalHeaders = mutableMapOf<String, String>()
                json.optJSONObject("headers")?.let { headersObj ->
                    headersObj.keys().forEach { key ->
                        globalHeaders[key] = headersObj.getString(key)
                    }
                }

                val urls = json.optJSONArray("url") ?: JSONArray()
                for (i in 0 until urls.length()) {
                    val obj = urls.getJSONObject(i)
                    val encryptedLink = obj.optString("link")
                    val name = obj.optString("name", "Vidzee")
                    val type = obj.optString("type", "hls")
                    val lang = obj.optString("lang", "Unknown")
                    val flag = obj.optString("flag", "")

                    if (encryptedLink.isNotBlank()) {
                        val finalUrl = try {
                            decryptVidzeeUrl(encryptedLink, keyBytes)
                        } catch (e: Exception) {
                            Log.e("VidzeeDecrypt", "Failed to decrypt link: ${e.message}")
                            encryptedLink
                        }

                        try {
                            URI(finalUrl) // Validate URL
                            val headersMap = mutableMapOf<String, String>()
                            headersMap.putAll(globalHeaders)
                            val referer = headersMap["referer"] ?: defaultReferer
                            val displayName =
                                if (flag.isNotBlank()) "VidZee $name ($lang - $flag)" else "VidZee $name ($lang)"

                            callback.invoke(
                                newExtractorLink(
                                    "VidZee",
                                    displayName,
                                    finalUrl,
                                    if (type.equals("hls", ignoreCase = true))
                                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer
                                    this.headers = headersMap
                                    val detectedQ = StreamLinkOptimizer.extractQualityFromText(displayName, finalUrl)
                                    this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("VidzeeUrl", "Invalid URL: $finalUrl - ${e.message}")
                        }
                    }
                }

                val subs = json.optJSONArray("tracks") ?: JSONArray()
                for (i in 0 until subs.length()) {
                    val sub = subs.getJSONObject(i)
                    val subLang = sub.optString("lang", "Unknown")
                    val subUrl = sub.optString("url")
                    if (subUrl.isNotBlank()) subtitleCallback(newSubtitleFile(subLang, subUrl))
                }

            } catch (e: Exception) {
                Log.e("VidzeeApi", "Failed sr=$sr: ${e.message}")
            }
        }
    }

    fun decryptVidzeeUrl(encryptedUrl: String, keyBytes: ByteArray): String {
        try {
            val decoded = String(base64DecodeArray(encryptedUrl))
            val parts = decoded.split(":", limit = 2)
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid encrypted URL format")
            }

            val ivB64 = parts[0]
            val ciphertextB64 = parts[1]

            val iv = base64DecodeArray(ivB64)
            val ciphertext = base64DecodeArray(ciphertextB64)

            val keySpec = SecretKeySpec(keyBytes, 0, keyBytes.size, "AES")
            val ivSpec = IvParameterSpec(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            val decrypted = cipher.doFinal(ciphertext)

            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("VidzeeDecrypt", "Decryption failed: ${e.message}")
            throw e
        }
    }


    suspend fun invokeTopMovies(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val topmoviesAPI = getDomains()?.topMovies ?: return

        imdbId ?: return

        val url = if (season == null) {
            "$topmoviesAPI/search/${imdbId}"
        } else {
            "$topmoviesAPI/search/${imdbId} Season $season"
        }

        val hrefpattern = runCatching {
            safeGet(url).document.select("#content_box article a")
                .firstOrNull()?.attr("href")?.takeIf(String::isNotBlank)
        }.getOrNull() ?: return

        val res = runCatching {
            safeGet(
                hrefpattern,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0"
                ),
                interceptor = wpRedisInterceptor
            ).document
        }.getOrNull() ?: return

        if (season == null) {
            val detailPageUrls = res.select("a.maxbutton-download-links")
                .mapNotNull { it.attr("href").takeIf { it.isNotBlank() } }

            detailPageUrls.safeAmap { detailPageUrl ->
                val detailPageDocument =
                    runCatching { safeGet(detailPageUrl).document }.getOrNull() ?: return@safeAmap

                val driveLinks = detailPageDocument.select("a.maxbutton-fast-server-gdrive")
                    .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }

                driveLinks.safeAmap { driveLink ->
                    val finalLink = if (driveLink.contains("unblockedgames")) {
                        bypassHrefli(driveLink) ?: return@safeAmap
                    } else {
                        driveLink
                    }

                    loadSourceNameExtractor(
                        "TopMovies",
                        finalLink,
                        "$topmoviesAPI/",
                        subtitleCallback,
                        callback
                    )
                }
            }
        } else {
            val detailPageUrls = res.select("a.maxbutton-g-drive")
                .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }

            detailPageUrls.safeAmap { detailPageUrl ->
                val detailPageDocument =
                    runCatching { safeGet(detailPageUrl).document }.getOrNull() ?: return@safeAmap

                val episodeLink = detailPageDocument.select("span strong")
                    .firstOrNull {
                        it.text().matches(Regex(".*Episode\\s+$episode.*", RegexOption.IGNORE_CASE))
                    }
                    ?.parent()?.closest("a")?.attr("href")
                    ?.takeIf(String::isNotBlank) ?: return@safeAmap

                val finalLink = if (episodeLink.contains("unblockedgames")) {
                    bypassHrefli(episodeLink) ?: return@safeAmap
                } else {
                    episodeLink
                }

                loadSourceNameExtractor(
                    "TopMovies",
                    finalLink,
                    "$topmoviesAPI/",
                    subtitleCallback,
                    callback
                )
            }
        }
    }


    suspend fun invokeMoviesmod(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val MoviesmodAPI = getDomains()?.moviesmod ?: return
        invokeModflix(
            imdbId,
            season,
            episode,
            subtitleCallback,
            callback,
            MoviesmodAPI
        )
    }


    suspend fun invokeModflix(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        api: String
    ) {
        val url: String = if (season == null) {
            "$api/search/$id"
        } else {
            "$api/search/$id $season"
        }
        val href = safeGet(url).document.selectFirst("#content_box article > a")?.attr("href")

        val hTag = if (season == null) "h4" else "h3"
        val aTag = if (season == null) "Download" else "Episode"
        val sTag = if (season == null) "" else "(S0$season|Season $season)"
        val res = app.get(
            href ?: return,
        ).document

        val entries = res.select("div.thecontent $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
            .filter { element ->
                val text = element.text()
                !text.contains("MoviesMod", true)
            }

        entries.safeAmap {
            val link =
                it.nextElementSibling()?.select("a:contains($aTag)")?.attr("href")
                    ?.substringAfter("=") ?: ""
            val selector =
                if (season == null) "p a.maxbutton" else "h3 a:matches(Episode $episode)"

            if (link.isNotEmpty()) {
                val source = app.get(link).document.selectFirst(selector)?.attr("href") ?: return@safeAmap
                val bypassedLink = bypassHrefli(source).toString()
                loadSourceNameExtractor("Moviesmod", bypassedLink, "", subtitleCallback, callback)
            }
        }
    }

    private val vegaHeaders by lazy {
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "sec-ch-ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Microsoft Edge\";v=\"120\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Linux\"",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "cookie" to "xla=s4t"
        )
    }

    suspend fun invokeVegamovies(
        title: String?=null,
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val api = getDomains()?.vegamovies ?: return
        val imdb = id ?: return

        val headers = vegaHeaders // move to top-level val if reused

        suspend fun fetchResults(query: String): List<VegamoviesDocument> {
            val url = "$api/search.php?q=${query.replace(" ", "%20")}"
            return safeGet(url, referer = api, headers = headers)
                .parsedSafe<VegamoviesResponse>()?.hits
                ?.mapNotNull { it.document }
                ?: emptyList()
        }

        val imdbMatch = fetchResults(imdb).firstOrNull {
            it.imdb_id.equals(imdb, true)
        }

        val match = imdbMatch ?: run {
            val t = title ?: return
            val results = fetchResults(t)

            results.firstOrNull {
                it.post_title?.contains(t, ignoreCase = true) == true
            } ?: results.firstOrNull()
        } ?: return

        val permalink = match.permalink ?: return
        val mainDoc = safeGet(api + permalink, referer = api, headers = headers).document


        if (season == null) {
            mainDoc.select("button.dwd-button")
                .mapNotNull { it.parent()?.attr("href") }
                .filter { it.isNotBlank() }
                .distinct()
                .safeAmap { page ->
                    val doc = runCatching {
                        safeGet(page, referer = api, headers = headers).document
                    }.getOrNull() ?: return@safeAmap

                    val sources = doc.select("button.btn:matches((?i)(V-Cloud))")
                        .mapNotNull { it.parent()?.attr("href") }
                        .filter { it.isNotBlank() }

                    sources.forEach { source ->
                        loadSourceNameExtractor(
                            "VegaMovies",
                            source,
                            "",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            return
        }

        val seasonRegex = Regex("(?i)Season $season")
        val episodeRegex = Regex("(?i)Episodes?:\\s*$episode")

        mainDoc.select("h3,h5")
            .asSequence()
            .filter { it.text().contains(seasonRegex) || it.text().contains("Episode", true) }
            .flatMap {
                generateSequence(it.nextElementSibling()) { el -> el.nextElementSibling() }
                    .takeWhile { el -> el.tagName() !in listOf("h3", "h5", "h4") }
                    .flatMap { el ->
                        el.select("a:matches((?i)(V-Cloud|Single|Episode))").asSequence()
                    }
            }
            .map { it.attr("href") }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .safeAmap { page ->
                val doc = runCatching {
                    safeGet(page, referer = api, headers = headers).document
                }.getOrNull() ?: return@safeAmap

                val epNode = doc.select("h4")
                    .firstOrNull { it.text().contains(episodeRegex) }
                    ?: return@safeAmap

                val links = epNode.nextElementSibling()
                    ?.select("a:matches((?i)(V-Cloud|Single|Episode))")
                    ?.map { it.attr("href") }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                links.forEach { link ->
                    loadSourceNameExtractor(
                        "VegaMovies",
                        link,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            }
    }


    suspend fun invokeRogmovies(
        title: String? = null,
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val api = getDomains()?.rogmovies ?: return
        val headers = vegaHeaders

        suspend fun search(query: String): List<VegamoviesDocument> {
            return safeGet("$api/search.php?q=$query", referer = api, headers = headers)
                .parsedSafe<VegamoviesResponse>()?.hits
                ?.mapNotNull { it.document }
                ?: emptyList()
        }

        val results = when {
            !id.isNullOrBlank() -> search(id)
            !title.isNullOrBlank() -> search(title)
            else -> return
        }

        if (results.isEmpty()) return

        val keywords = title
            ?.lowercase()
            ?.replace(normalizeAlphaNumSpaceRegex, "")
            ?.split(" ")
            ?.filter { it.length > 2 }
            ?: emptyList()

        val match = results.firstOrNull {
            !id.isNullOrBlank() && it.imdb_id.equals(id, true)
        } ?: results.firstOrNull { doc ->
            val docTitle = doc.post_title
                ?.lowercase()
                ?.replace(normalizeAlphaNumSpaceRegex, "")
                ?: return@firstOrNull false

            keywords.any { docTitle.contains(it) }
        } ?: results.firstOrNull() ?: return

        val permalink = match.permalink ?: return
        val mainDoc = safeGet(api + permalink, referer = api, headers = headers).document

        if (season == null) {
            mainDoc.select("button.dwd-button")
                .mapNotNull { it.parent()?.attr("href") }
                .filter { it.isNotBlank() }
                .distinct()
                .safeAmap { page ->
                    val doc = runCatching {
                        safeGet(page, referer = api, headers = headers).document
                    }.getOrNull() ?: return@safeAmap

                    val sources = doc.select("button.btn:matches((?i)(V-Cloud|G-Direct))")
                        .mapNotNull { it.parent()?.attr("href") }
                        .filter { it.isNotBlank() }
                    sources.forEach { source ->
                        loadSourceNameExtractor(
                            "RogMovies",
                            source,
                            "",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            return
        }

        val seasonRegex = Regex("(?i)Season $season")
        val episodeText = "Episode $episode"

        mainDoc.select("h3, h5")
            .asSequence()
            .filter { it.text().contains(seasonRegex) }
            .flatMap {
                generateSequence(it.nextElementSibling()) { el -> el.nextElementSibling() }
                    .takeWhile { it.tagName() !in listOf("h3", "h5") }
                    .flatMap {
                        it.select("a:matches((?i)(V-Cloud|Single|Episode|G-Direct))").asSequence()
                    }
            }
            .map { it.attr("href") }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .safeAmap { page ->
                val doc = runCatching {
                    safeGet(page, referer = api, headers = headers).document
                }.getOrNull() ?: return@safeAmap

                val epNode = doc.select("h4")
                    .firstOrNull { it.text().contains(episodeText, true) }
                    ?: return@safeAmap

                val links = epNode.nextElementSibling()
                    ?.select("a:matches((?i)(V-Cloud|Single|Episode|G-Direct))")
                    ?.map { it.attr("href") }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                links.forEach { link ->
                    loadSourceNameExtractor(
                        "RogMovies",
                        link,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
        }
    }

    suspend fun invokeNepu(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = title?.createSlug() ?: return
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest"
        )

        val data = try {
            val res = safeGet(
                "$nepuAPI/ajax/posts?q=$title",
                headers = headers,
                referer = "$nepuAPI/"
            )
            if (res.code != 200) return
            res.parsedSafe<NepuSearch>()?.data
        } catch (e: Exception) {
            Log.e("Nepu", "Search request failed: ${e.localizedMessage}")
            return
        }

        val media =
            data?.find { it.url?.startsWith(if (season == null) "/movie/$slug-$year-" else "/serie/$slug-$year-") == true }
                ?: data?.find {
                    it.name.equals(
                        title,
                        true
                    ) && it.type == if (season == null) "Movie" else "Serie"
                }

        val mediaUrl = media?.url ?: return
        val fullMediaUrl =
            if (season == null) mediaUrl else "$mediaUrl/season/$season/episode/$episode"

        val dataId = try {
            val pageRes = safeGet(fixUrl(fullMediaUrl, nepuAPI))
            if (pageRes.code != 200) return
            pageRes.document.selectFirst("a[data-embed]")?.attr("data-embed")
        } catch (e: Exception) {
            Log.e("Nepu", "Media page request failed: ${e.localizedMessage}")
            return
        } ?: return

        val res = try {
            val postRes = app.post(
                "$nepuAPI/ajax/embed",
                data = mapOf("id" to dataId),
                referer = fullMediaUrl,
                headers = headers
            )
            if (postRes.code != 200) return
            postRes.text
        } catch (e: Exception) {
            Log.e("Nepu", "Embed request failed: ${e.localizedMessage}")
            return
        }

        val m3u8 = "(http[^\"]+)".toRegex().find(res)?.groupValues?.get(1) ?: return

        callback.invoke(
            newExtractorLink(
                "Nepu",
                "Nepu",
                url = m3u8,
                INFER_TYPE
            ) {
                this.referer = "$nepuAPI/"
                val detectedQ = StreamLinkOptimizer.extractQualityFromText(m3u8)
                this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
            }
        )
    }

    // only subs
    suspend fun invokeWatchsomuch(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val id = imdbId?.removePrefix("tt")
        val epsId = app.post(
            "$watchSomuchAPI/Watch/ajMovieTorrents.aspx",
            data = mapOf(
                "index" to "0",
                "mid" to "$id",
                "wsk" to "30fb68aa-1c71-4b8c-b5d4-4ca9222cfb45",
                "lid" to "",
                "liu" to ""
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<WatchsomuchResponses>()?.movie?.torrents?.let { eps ->
            if (season == null) {
                eps.firstOrNull()?.id
            } else {
                eps.find { it.episode == episode && it.season == season }?.id
            }
        } ?: return

        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val subUrl = if (season == null) {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part="
        } else {
            "$watchSomuchAPI/Watch/ajMovieSubtitles.aspx?mid=$id&tid=$epsId&part=S${seasonSlug}E${episodeSlug}"
        }

        safeGet(subUrl).parsedSafe<WatchsomuchSubResponses>()?.subtitles?.map { sub ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    sub.label?.substringBefore("&nbsp") ?: "", fixUrl(
                        sub.url
                            ?: return@map null, watchSomuchAPI
                    )
                )
            )
        }
    }

    suspend fun invokeDahmerMovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        val url = if (season == null) {
            "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
        } else {
            "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
        }
        val request = safeGet(url, timeout = 6L)
        if (!request.isSuccessful) return
        val paths = request.document.select("a").map {
            it.text() to it.attr("href")
        }.filter {
            if (season == null) {
                it.first.contains(DAHMER_QUALITY_REGEX)
            } else {
                val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
                it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
            }
        }.ifEmpty { return }

        paths.forEach {
            val quality = getIndexQuality(it.first)
            val tags = getIndexQualityTags(it.first)
            val href =
                if (it.second.contains(dahmerMoviesAPI)) it.second else (dahmerMoviesAPI + it.second)

            callback.invoke(
                newExtractorLink(
                    "DahmerMovies",
                    "DahmerMovies $tags",
                    url = href,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }
    }

    suspend fun invokeNinetv(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = if (season == null) {
            "$nineTvAPI/movie/$tmdbId"
        } else {
            "$nineTvAPI/tv/$tmdbId-$season-$episode"
        }

        val response = safeGet(url, referer = "https://pressplay.top/")
        if (response.code != 200) return
        val iframe = response.document.selectFirst("iframe")?.attr("src") ?: return
        loadExtractor(iframe, "$nineTvAPI/", subtitleCallback, callback)
    }

    suspend fun invokeAllMovieland(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        runCatching {
            val playerResponse = safeGet("https://allmovieland.link/player.js?v=60%20128")
            if (playerResponse.code != 200) return@runCatching
            val playerScript = playerResponse.toString()
            val domainRegex = Regex("const AwsIndStreamDomain.*'(.*)';")
            val host =
                domainRegex.find(playerScript)?.groupValues?.getOrNull(1) ?: return@runCatching

            val resResponse = safeGet("$host/play/$imdbId", referer = "$allmovielandAPI/")
            if (resResponse.code != 200) return@runCatching
            val resData =
                resResponse.document.selectFirst("script:containsData(playlist)")?.data()
                    ?.substringAfter("{")?.substringBefore(";")?.substringBefore(")")
                    ?: return@runCatching

            val json = tryParseJson<AllMovielandPlaylist>("{$resData}") ?: return@runCatching
            val headers = mapOf("X-CSRF-TOKEN" to "${json.key}")
            val jsonfile =
                if (json.file?.startsWith("http") == true) json.file else host + json.file

            val serverResponse = safeGet(jsonfile, headers = headers, referer = "$allmovielandAPI/")
            if (serverResponse.code != 200) return@runCatching
            val serverJson = serverResponse.text.replace(Regex(""",\s*/"""), "")
            val cleanedJson = serverJson.replace(Regex(",\\s*\\[\\s*]"), "")

            val servers = tryParseJson<ArrayList<AllMovielandServer>>(cleanedJson)?.let { list ->
                if (season == null) {
                    list.mapNotNull { it.file?.let { file -> file to it.title.orEmpty() } }
                } else {
                    list.find { it.id == season.toString() }
                        ?.folder?.find { it.episode == episode.toString() }
                        ?.folder?.mapNotNull { it.file?.let { file -> file to it.title.orEmpty() } }
                }
            } ?: return@runCatching

            servers.safeAmap { (server, lang) ->
                runCatching {
                    val playlistResponse = app.post(
                        "$host/playlist/$server.txt",
                        headers = headers,
                        referer = "$allmovielandAPI/"
                    )
                    if (playlistResponse.code != 200) return@runCatching
                    val playlistUrl = playlistResponse.text
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
                        "Accept" to "*/*",
                        "Referer" to allmovielandAPI,
                        "Origin" to allmovielandAPI
                    )

                    generateM3u8(
                        "AllMovieLand-$lang",
                        playlistUrl,
                        allmovielandAPI,
                        headers = headers
                    ).forEach(callback)
                }.onFailure { it.printStackTrace() }
            }
        }.onFailure { it.printStackTrace() }
    }

    suspend fun invokeMoviesdrive(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val domain = getDomains()?.moviesdrive ?: return
        val id = imdbId ?: return

        val searchUrl = "$domain/search.php?q=$id"
        val root = runCatching {
            JSONObject(safeGet(searchUrl, interceptor = wpRedisInterceptor).text)
        }.getOrNull() ?: return

        val hits = root.optJSONArray("hits") ?: return

        val match = (0 until hits.length())
            .asSequence()
            .mapNotNull { hits.optJSONObject(it)?.optJSONObject("document") }
            .firstOrNull { it.optString("imdb_id") == id }
            ?: return

        val permalink = match.optString("permalink")
        if (permalink.isBlank()) return
        val href = if (permalink.startsWith("http")) permalink else domain + permalink
        val mainDoc = safeGet(href).document

        if (season == null) {
            val seenUrls = Collections.synchronizedSet(mutableSetOf<String>())

            mainDoc.select("h5 > a")
                .map { it.attr("href") }
                .filter { it.isNotBlank() }
                .distinct()
                .safeAmap { href ->
                    val servers = extractMdrive(href)
                    servers.forEach { server ->
                        if (seenUrls.add(server)) {
                            loadSourceNameExtractor("MoviesDrive", server, "", subtitleCallback, callback)
                        }
                    }
                }
            return
        } else {
            val (sSlug, eSlug) = getEpisodeSlug(season, episode)
            val stag = "Season $season|S$sSlug"
            val sep = "Ep$eSlug|Ep$episode"
            val entries = mainDoc.select("h5:matches((?i)$stag)")
            entries.safeAmap { entry ->
                val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""

                if (href.isNotBlank()) {
                    val doc = app.get(href).document
                    val fEp = doc.selectFirst("h5:matches((?i)$sep)")
                    val linklist = mutableListOf<String>()
                    val source1 = fEp?.nextElementSibling()?.selectFirst("a")?.attr("href")
                    val source2 = fEp?.nextElementSibling()?.nextElementSibling()?.selectFirst("a")?.attr("href")
                    if (source1 != null) linklist.add(source1)
                    if (source2 != null) linklist.add(source2)

                    linklist.safeAmap { url ->
                        loadSourceNameExtractor(
                            "MoviesDrive",
                            url,
                            "",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        }
    }

    suspend fun invokeBollyflix(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val bollyflixAPI = getDomains()?.bollyflix ?: return
        val res1 = safeGet("$bollyflixAPI/search/$id", timeout = 10L).document

        res1.select("div > article > a").forEach {
            val url = it.attr("href")
            val res = safeGet(url).document
            val hTag = if (season == null) "h5" else "h4"
            val sTag = if (season == null) "" else "Season $season"
            val entries =
                res.select("div.thecontent.clearfix > $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
                    .filter { element -> !element.text().contains("Download", true) }.takeLast(4)

            entries.forEach {
                var href = it.nextElementSibling()?.select("a")?.attr("href") ?: return@forEach

                if(href.contains("id=")) {
                    val token = href.substringAfter("id=")
                    val encodedurl =
                        app.get("https://web.sidexfee.com/?id=$token").text.substringAfter("link\":\"")
                            .substringBefore("\"};")
                    href = base64Decode(encodedurl)
                }

                if (season == null) {
                    loadSourceNameExtractor("Bollyflix", href , "", subtitleCallback, callback)
                } else {
                    val episodeText = "Episode " + episode.toString().padStart(2, '0')
                    val link =
                        app.get(href).document.selectFirst("article h3 a:contains($episodeText)")!!
                            .attr("href")
                    loadSourceNameExtractor("Bollyflix", link , "", subtitleCallback, callback)
                }
            }
        }
    }


    private fun normalizeVidSrcUrl(rawUrl: String, contextUrl: String): String {
        val trimmed = rawUrl.replace("\\/", "/").trim().trim('"', '\'')
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> getBaseUrl(contextUrl) + trimmed
            else -> getBaseUrl(contextUrl) + "/" + trimmed
        }
    }

    suspend fun invokeVidSrcXyz(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int? = null
    ) {
        withTimeoutOrNull(9000L) {
            try {
                if (id == null && tmdbId == null) return@withTimeoutOrNull

                val domains = listOf(
                    Vidsrcxyz,
                    "https://vidsrc.xyz",
                    "https://vidsrc.me",
                    "https://vidsrc.in",
                    "https://vidsrc.pm"
                ).distinct()

                coroutineScope {
                    val jobs = domains.map { domain ->
                        async {
                            try {
                                val candidateUrls = mutableListOf<String>()
                                if (season == null || (season == 0 && episode == null)) {
                                    if (id != null) {
                                        candidateUrls.add("$domain/embed/movie?imdb=$id")
                                        candidateUrls.add("$domain/embed/movie/$id")
                                    }
                                    if (tmdbId != null) {
                                        candidateUrls.add("$domain/embed/movie?tmdb=$tmdbId")
                                        candidateUrls.add("$domain/embed/movie/$tmdbId")
                                    }
                                } else {
                                    if (episode != null) {
                                        if (id != null) {
                                            candidateUrls.add("$domain/embed/tv?imdb=$id&season=$season&episode=$episode")
                                            candidateUrls.add("$domain/embed/tv/$id/$season/$episode")
                                        }
                                        if (tmdbId != null) {
                                            candidateUrls.add("$domain/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
                                            candidateUrls.add("$domain/embed/tv/$tmdbId/$season/$episode")
                                        }
                                    }
                                    // TV Special fallback to movie endpoints
                                    if (season == 0) {
                                        if (id != null) {
                                            candidateUrls.add("$domain/embed/movie?imdb=$id")
                                            candidateUrls.add("$domain/embed/movie/$id")
                                        }
                                        if (tmdbId != null) {
                                            candidateUrls.add("$domain/embed/movie?tmdb=$tmdbId")
                                            candidateUrls.add("$domain/embed/movie/$tmdbId")
                                        }
                                    }
                                }

                                for (url in candidateUrls) {
                                    val iframeUrl = extractIframeUrl(url) ?: continue
                                    val prorcpUrl = extractProrcpUrl(iframeUrl) ?: continue
                                    val decryptedSource = extractAndDecryptSource(prorcpUrl, iframeUrl, subtitleCallback) ?: continue

                                    val referer = "${getBaseUrl(prorcpUrl)}/"
                                    val streamHeaders = mapOf(
                                        "Referer" to referer,
                                        "Origin" to referer.removeSuffix("/")
                                    )
                                    var found = false
                                    decryptedSource.forEach { (version, rawStreamUrl) ->
                                        val streamUrl = rawStreamUrl.trim()
                                        if (streamUrl.isBlank() || !streamUrl.startsWith("http", ignoreCase = true)) return@forEach

                                        val formattedVersion = version.replaceFirstChar {
                                            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                                        }
                                        val serverLabel = "VidSrc Server $formattedVersion"
                                        val isHls = streamUrl.contains(".m3u8", ignoreCase = true)
                                        val isDirectVideo = streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains(".mkv", ignoreCase = true)
                                        val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                                        val m3u8Links = if (isHls) {
                                            withTimeoutOrNull(4000L) {
                                                runCatching {
                                                    generateM3u8(serverLabel, streamUrl, referer, headers = streamHeaders)
                                                }.getOrNull()
                                            }
                                        } else null

                                        if (!m3u8Links.isNullOrEmpty()) {
                                            found = true
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc",
                                                baseName = serverLabel,
                                                url = streamUrl,
                                                referer = referer,
                                                headers = streamHeaders,
                                                streamType = streamType,
                                                generatedLinks = m3u8Links,
                                                callback = callback
                                            )
                                        } else if (streamUrl.startsWith("http", ignoreCase = true)) {
                                            found = true
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc",
                                                baseName = serverLabel,
                                                url = streamUrl,
                                                referer = referer,
                                                headers = streamHeaders,
                                                streamType = streamType,
                                                callback = callback
                                            )
                                        }
                                    }
                                    if (found) return@async true
                                }
                                false
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.d("StreamPlay", "VidSrcXyz domain $domain failed: ${e.message}")
                                false
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("StreamPlay", "invokeVidSrcXyz error: ${e.message}")
            }
        }
    }

    suspend fun invokeVidSrcXyz(
        id: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int?
    ) {
        invokeVidSrcXyz(id, season, episode, subtitleCallback = null, callback = callback, tmdbId = tmdbId)
    }

    private suspend fun extractIframeUrl(url: String): String? {
        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to getBaseUrl(url),
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val response = runCatching { safeGet(url, headers = baseHeaders, timeout = 5L) }.getOrNull() ?: return null
        val doc = response.document

        val iframe = doc.selectFirst("iframe#player_iframe") ?: doc.selectFirst("iframe")

        // 1. Check data-api attribute (used by modern vidsrc-embed.su)
        val dataApi = iframe?.attr("data-api")?.takeIf { it.isNotBlank() }
            ?: Regex("""data-api="([^"]+)"""").find(response.text)?.groupValues?.get(1)
            ?: Regex("""['"](/vs_src\.php\?[^'"]+)['"]""").find(response.text)?.groupValues?.get(1)

        if (!dataApi.isNullOrBlank()) {
            val fullApiUrl = normalizeVidSrcUrl(dataApi.replace("&amp;", "&"), url)
            val apiHeaders = baseHeaders + mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "*/*"
            )
            val apiJson = runCatching {
                safeGet(fullApiUrl, headers = apiHeaders, timeout = 4L).text
            }.getOrNull()

            if (!apiJson.isNullOrBlank()) {
                val parsedSrc = runCatching {
                    val json = JSONObject(apiJson)
                    json.optString("src").takeIf { it.isNotBlank() }
                        ?: json.optString("url").takeIf { it.isNotBlank() }
                        ?: json.optString("file").takeIf { it.isNotBlank() }
                        ?: json.optString("source").takeIf { it.isNotBlank() }
                }.getOrNull()

                if (parsedSrc != null) {
                    return normalizeVidSrcUrl(parsedSrc, fullApiUrl)
                }
            }
        }

        // 2. Check iframe src attribute
        val src = iframe?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("about:blank") }
        if (src != null) {
            return normalizeVidSrcUrl(src, url)
        }

        // 3. Fallback: regex search on HTML
        val regexSrc = Regex("""(?:data-src|src)\s*[:=]\s*['"]([^'"]+)['"]""").find(response.text)?.groupValues?.get(1)
        if (!regexSrc.isNullOrBlank() && !regexSrc.startsWith("about:blank")) {
            return normalizeVidSrcUrl(regexSrc, url)
        }

        return null
    }

    private suspend fun extractProrcpUrl(iframeUrl: String): String? {
        if (iframeUrl.contains("/rcp/") || iframeUrl.contains("/prorcp/")) {
            return normalizeVidSrcUrl(iframeUrl, iframeUrl)
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to iframeUrl
        )
        val doc = runCatching { safeGet(iframeUrl, headers = headers, referer = iframeUrl, timeout = 5L).document }.getOrNull() ?: return null
        val html = doc.html()
        val regex = Regex("""src:\s*['"](.*?)['"]""")
        val matchedSrc = regex.find(html)?.groupValues?.get(1)
            ?: Regex("""['"](/p?rcp/[a-zA-Z0-9_-]+)['"]""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: return null

        return normalizeVidSrcUrl(matchedSrc, iframeUrl)
    }

    private suspend fun extractAndDecryptSource(
        prorcpUrl: String,
        referer: String,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null
    ): List<Pair<String, String>>? {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Referer" to referer
        )
        val responseText = runCatching { safeGet(prorcpUrl, headers = headers, referer = referer, timeout = 5L).text }.getOrNull() ?: return null

        if (subtitleCallback != null) {
            val doc = runCatching { Jsoup.parse(responseText) }.getOrNull()
            doc?.select("track[src]")?.forEach { track ->
                if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                val src = track.attr("src")
                val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                if (src.isNotBlank()) {
                    val subUrl = normalizeVidSrcUrl(src, prorcpUrl)
                    val label = cleanSubtitleLabel(rawLabel)
                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                }
            }
            val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            subtitleRegex.findAll(responseText).forEach { match ->
                val subVal = match.groupValues[1]
                if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                    for (part in subVal.split(",")) {
                        val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                        val subUrl = normalizeVidSrcUrl(if (part.contains("]")) part.substringAfter("]") else part, prorcpUrl)
                        if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                            val label = cleanSubtitleLabel(rawLang)
                            subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                        }
                    }
                }
            }
        }

        val playerJsRegex = Regex("""Playerjs\(\{.*?file:"(.*?)".*?\}\)""")
        val temp = playerJsRegex.find(responseText)?.groupValues?.get(1)

        val encryptedURLNode: Pair<String, String>? = if (!temp.isNullOrEmpty()) {
            "playerjs" to temp
        } else {
            val document = Jsoup.parse(responseText)
            val reporting = document.selectFirst("#reporting_content")
            val node = reporting?.nextElementSibling()
            if (node != null && node.attr("id").isNotEmpty() && node.text().isNotEmpty()) {
                node.attr("id") to node.text()
            } else {
                val hiddenDiv = document.select("div[id]").firstOrNull {
                    it.id().length in 8..16 && it.text().length > 20
                }
                if (hiddenDiv != null) {
                    hiddenDiv.id() to hiddenDiv.text()
                } else {
                    val fallbackMatch = Regex("""id="([a-zA-Z0-9]{8,16})"[^>]*>([a-zA-Z0-9+/=_-]{20,})<""").find(responseText)
                    if (fallbackMatch != null) {
                        fallbackMatch.groupValues[1] to fallbackMatch.groupValues[2]
                    } else null
                }
            }
        }

        val id = encryptedURLNode?.first ?: return null
        val content = encryptedURLNode.second

        // 1. Direct lookup in native decryptMethods
        var decrypted: String? = decryptMethods[id]?.invoke(content)?.takeIf {
            it.isNotBlank() && !it.startsWith("Failed to decode") && (it.contains("http") || it.contains("//") || it.contains("{v") || it.contains(".m3u8"))
        }

        // 2. Ultra-fast (0.05ms) in-memory native cipher trial across all known decryptMethods
        if (decrypted == null) {
            for ((_, method) in decryptMethods) {
                val candidate = runCatching { method(content) }.getOrNull()
                if (candidate != null && !candidate.startsWith("Failed to decode") &&
                    (candidate.contains("http") || candidate.contains("//") || candidate.contains("{v") || candidate.contains(".m3u8"))) {
                    decrypted = candidate
                    break
                }
            }
        }

        // 3. Remote fallback via enc-dec.app only if local native decoders could not decrypt
        if (decrypted == null) {
            val encDecResp = runCatching {
                app.post(
                    "https://enc-dec.app/api/dec-cloudnestra",
                    json = mapOf("text" to content, "div_id" to id),
                    timeout = 4L
                ).text
            }.getOrNull()

            if (!encDecResp.isNullOrBlank()) {
                val encDecJson = runCatching { JSONObject(encDecResp) }.getOrNull()
                val result = encDecJson?.optString("result")
                if (!result.isNullOrBlank() && (result.contains("http") || result.contains("//") || result.contains("{v") || result.contains(".m3u8"))) {
                    decrypted = result
                }
            }
        }

        if (decrypted.isNullOrBlank()) return null

        // Domain mapping
        val vSubs = mapOf(
            "v1" to "shadowlandschronicles.com",
            "v2" to "cloudnestra.com",
            "v3" to "thepixelpioneer.com",
            "v4" to "putgate.org",
            "v5" to "whisperingpineslifestyle.com"
        )
        val placeholderRegex = "\\{(v\\d+)\\}".toRegex()
        val mirrors: List<Pair<String, String>> = decrypted
            .split(" or ")
            .map { it.trim() }
            .filter { it.startsWith("http") || it.startsWith("//") || placeholderRegex.containsMatchIn(it) }
            .map { rawUrl ->
                val match = placeholderRegex.find(rawUrl)
                val version = match?.groupValues?.get(1) ?: "v1"
                val domain = vSubs[version] ?: "cloudnestra.com"

                val replacedUrl = if (placeholderRegex.containsMatchIn(rawUrl)) {
                    placeholderRegex.replace(rawUrl) { domain }
                } else {
                    rawUrl
                }

                val finalUrl = normalizeVidSrcUrl(replacedUrl, prorcpUrl)
                version to finalUrl
            }

        return mirrors.ifEmpty { null }
    }

    suspend fun invokeVidSrcCc(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int? = null
    ) {
        withTimeoutOrNull(8000L) {
            try {
                if (id == null && tmdbId == null) return@withTimeoutOrNull

                val candidateIds = listOfNotNull(id, tmdbId?.toString()).distinct()
                val domains = listOf("https://vidsrc.cc", "https://vidsrc.in", "https://vidsrc.pm")
                val mobileHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.97 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                coroutineScope {
                    val jobs = domains.map { domain ->
                        async {
                            try {
                                for (targetId in candidateIds) {
                                    val embedUrls = if (season == null || (season == 0 && episode == null)) {
                                        listOf(
                                            "$domain/v2/embed/movie/$targetId",
                                            "$domain/v3/embed/movie/$targetId",
                                            "$domain/embed/movie/$targetId"
                                        )
                                    } else {
                                        val list = mutableListOf<String>()
                                        if (episode != null) {
                                            list.add("$domain/v2/embed/tv/$targetId/$season/$episode")
                                            list.add("$domain/v3/embed/tv/$targetId/$season/$episode")
                                            list.add("$domain/embed/tv/$targetId/$season/$episode")
                                        }
                                        if (season == 0) {
                                            list.add("$domain/v2/embed/movie/$targetId")
                                            list.add("$domain/v3/embed/movie/$targetId")
                                            list.add("$domain/embed/movie/$targetId")
                                        }
                                        list
                                    }

                                    for (embedUrl in embedUrls) {
                                        val resp = runCatching {
                                            safeGet(embedUrl, headers = mobileHeaders + ("Referer" to "$domain/"), timeout = 4L)
                                        }.getOrNull() ?: continue

                                        if (subtitleCallback != null) {
                                            resp.document.select("track[src]").forEach { track ->
                                                if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                                                val src = track.attr("src")
                                                val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                                                if (src.isNotBlank()) {
                                                    val subUrl = normalizeVidSrcUrl(src, embedUrl)
                                                    val label = cleanSubtitleLabel(rawLabel)
                                                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                }
                                            }
                                            val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                            subtitleRegex.findAll(resp.text).forEach { match ->
                                                val subVal = match.groupValues[1]
                                                if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                                                    for (part in subVal.split(",")) {
                                                        val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                                                        val subUrl = normalizeVidSrcUrl(if (part.contains("]")) part.substringAfter("]") else part, embedUrl)
                                                        if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                                                            val label = cleanSubtitleLabel(rawLang)
                                                            subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        val streamHeaders = mapOf(
                                            "Referer" to embedUrl,
                                            "Origin" to getBaseUrl(embedUrl)
                                        )

                                        // 1. Direct stream match in page HTML
                                        val directMatch = Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4|mkv)[^'"]*)['"]""").find(resp.text)?.groupValues?.get(1)
                                            ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(resp.text)?.groupValues?.get(1)

                                        if (!directMatch.isNullOrBlank() && (directMatch.contains(".m3u8", ignoreCase = true) || directMatch.contains(".mp4", ignoreCase = true) || directMatch.contains(".mkv", ignoreCase = true))) {
                                            val normalizedStream = normalizeVidSrcUrl(directMatch, embedUrl)
                                            val isHls = normalizedStream.contains(".m3u8", ignoreCase = true)
                                            val isDirectVideo = normalizedStream.contains(".mp4", ignoreCase = true) || normalizedStream.contains(".mkv", ignoreCase = true)
                                            val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                                            val m3u8Links = if (isHls) {
                                                withTimeoutOrNull(4000L) {
                                                    runCatching { generateM3u8("VidSrc CC", normalizedStream, embedUrl, headers = streamHeaders) }.getOrNull()
                                                }
                                            } else null

                                            if (!m3u8Links.isNullOrEmpty()) {
                                                emitTopTierDualQualityStreamLinks(
                                                    source = "VidSrc CC",
                                                    baseName = "VidSrc CC",
                                                    url = normalizedStream,
                                                    referer = embedUrl,
                                                    headers = streamHeaders,
                                                    streamType = streamType,
                                                    generatedLinks = m3u8Links,
                                                    callback = callback
                                                )
                                            } else if (normalizedStream.startsWith("http", ignoreCase = true)) {
                                                emitTopTierDualQualityStreamLinks(
                                                    source = "VidSrc CC",
                                                    baseName = "VidSrc CC",
                                                    url = normalizedStream,
                                                    referer = embedUrl,
                                                    headers = streamHeaders,
                                                    streamType = streamType,
                                                    callback = callback
                                                )
                                            }
                                            return@async true
                                        }

                                        // 2. Nested iframe match
                                        val iframeSrc = resp.document.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                                            ?: Regex("""iframe\s+src=['"]([^'"]+)['"]""").find(resp.text)?.groupValues?.get(1)
                                            ?: continue

                                        val fullIframeUrl = normalizeVidSrcUrl(iframeSrc, embedUrl)
                                        val iframeResp = runCatching {
                                            safeGet(fullIframeUrl, headers = mobileHeaders + ("Referer" to embedUrl), timeout = 4L)
                                        }.getOrNull() ?: continue

                                        if (subtitleCallback != null) {
                                            iframeResp.document.select("track[src]").forEach { track ->
                                                if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                                                val src = track.attr("src")
                                                val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                                                if (src.isNotBlank()) {
                                                    val subUrl = normalizeVidSrcUrl(src, fullIframeUrl)
                                                    val label = cleanSubtitleLabel(rawLabel)
                                                    subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                }
                                            }
                                            val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                            subtitleRegex.findAll(iframeResp.text).forEach { match ->
                                                val subVal = match.groupValues[1]
                                                if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                                                    for (part in subVal.split(",")) {
                                                        val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                                                        val subUrl = normalizeVidSrcUrl(if (part.contains("]")) part.substringAfter("]") else part, fullIframeUrl)
                                                        if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                                                            val label = cleanSubtitleLabel(rawLang)
                                                            subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        val iframeStreamHeaders = mapOf(
                                            "Referer" to fullIframeUrl,
                                            "Origin" to getBaseUrl(fullIframeUrl)
                                        )

                                        val m3u8Match = Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4|mkv)[^'"]*)['"]""").find(iframeResp.text)?.groupValues?.get(1)
                                            ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(iframeResp.text)?.groupValues?.get(1)

                                        if (!m3u8Match.isNullOrBlank() && (m3u8Match.contains(".m3u8", ignoreCase = true) || m3u8Match.contains(".mp4", ignoreCase = true) || m3u8Match.contains(".mkv", ignoreCase = true))) {
                                            val normalizedStream = normalizeVidSrcUrl(m3u8Match, fullIframeUrl)
                                            val isHls = normalizedStream.contains(".m3u8", ignoreCase = true)
                                            val isDirectVideo = normalizedStream.contains(".mp4", ignoreCase = true) || normalizedStream.contains(".mkv", ignoreCase = true)
                                            val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                                            val m3u8Links = if (isHls) {
                                                withTimeoutOrNull(4000L) {
                                                    runCatching { generateM3u8("VidSrc CC", normalizedStream, fullIframeUrl, headers = iframeStreamHeaders) }.getOrNull()
                                                }
                                            } else null

                                             if (!m3u8Links.isNullOrEmpty()) {
                                                emitTopTierDualQualityStreamLinks(
                                                    source = "VidSrc CC",
                                                    baseName = "VidSrc CC",
                                                    url = normalizedStream,
                                                    referer = fullIframeUrl,
                                                    headers = iframeStreamHeaders,
                                                    streamType = streamType,
                                                    generatedLinks = m3u8Links,
                                                    callback = callback
                                                )
                                            } else if (normalizedStream.startsWith("http", ignoreCase = true)) {
                                                emitTopTierDualQualityStreamLinks(
                                                    source = "VidSrc CC",
                                                    baseName = "VidSrc CC",
                                                    url = normalizedStream,
                                                    referer = fullIframeUrl,
                                                    headers = iframeStreamHeaders,
                                                    streamType = streamType,
                                                    callback = callback
                                                )
                                            }
                                            return@async true
                                        }
                                    }
                                }
                                false
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.d("StreamPlay", "VidSrc CC domain $domain error: ${e.message}")
                                false
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("StreamPlay", "invokeVidSrcCc error: ${e.message}")
            }
        }
    }

    suspend fun invokeVidSrcCc(
        id: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int?
    ) {
        invokeVidSrcCc(id, season, episode, subtitleCallback = null, callback = callback, tmdbId = tmdbId)
    }

    suspend fun invokeVidSrcTo(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int? = null
    ) {
        withTimeoutOrNull(8000L) {
            try {
                if (id == null && tmdbId == null) return@withTimeoutOrNull

                val domains = listOf("https://vidsrc.to", "https://vidsrc2.to", "https://vidsrc.net")
                val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                coroutineScope {
                    val jobs = domains.map { domain ->
                        async {
                            try {
                                val candidateUrls = mutableListOf<String>()
                                if (season == null || (season == 0 && episode == null)) {
                                    if (!id.isNullOrBlank()) {
                                        candidateUrls.add("$domain/embed/movie/$id")
                                        candidateUrls.add("$domain/embed/movie?imdb=$id")
                                    }
                                    if (tmdbId != null) {
                                        candidateUrls.add("$domain/embed/movie/$tmdbId")
                                        candidateUrls.add("$domain/embed/movie?tmdb=$tmdbId")
                                    }
                                } else {
                                    if (episode != null) {
                                        if (!id.isNullOrBlank()) {
                                            candidateUrls.add("$domain/embed/tv/$id/$season/$episode")
                                            candidateUrls.add("$domain/embed/tv?imdb=$id&season=$season&episode=$episode")
                                        }
                                        if (tmdbId != null) {
                                            candidateUrls.add("$domain/embed/tv/$tmdbId/$season/$episode")
                                            candidateUrls.add("$domain/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode")
                                        }
                                    }
                                    if (season == 0) {
                                        if (!id.isNullOrBlank()) {
                                            candidateUrls.add("$domain/embed/movie/$id")
                                            candidateUrls.add("$domain/embed/movie?imdb=$id")
                                        }
                                        if (tmdbId != null) {
                                            candidateUrls.add("$domain/embed/movie/$tmdbId")
                                            candidateUrls.add("$domain/embed/movie?tmdb=$tmdbId")
                                        }
                                    }
                                }

                                for (embedUrl in candidateUrls.distinct()) {
                                    val resp = runCatching {
                                        safeGet(embedUrl, headers = headers + ("Referer" to "$domain/"), timeout = 4L)
                                    }.getOrNull() ?: continue

                                    if (subtitleCallback != null) {
                                        resp.document.select("track[src]").forEach { track ->
                                            if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                                            val src = track.attr("src")
                                            val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                                            if (src.isNotBlank()) {
                                                val subUrl = normalizeVidSrcUrl(src, embedUrl)
                                                val label = cleanSubtitleLabel(rawLabel)
                                                subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                            }
                                        }
                                        val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                        subtitleRegex.findAll(resp.text).forEach { match ->
                                            val subVal = match.groupValues[1]
                                            if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                                                for (part in subVal.split(",")) {
                                                    val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                                                    val subUrl = normalizeVidSrcUrl(if (part.contains("]")) part.substringAfter("]") else part, embedUrl)
                                                    if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                                                        val label = cleanSubtitleLabel(rawLang)
                                                        subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val streamHeaders = mapOf(
                                        "Referer" to embedUrl,
                                        "Origin" to getBaseUrl(embedUrl)
                                    )

                                    // 1. Direct stream match
                                    val directMatch = Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4|mkv)[^'"]*)['"]""").find(resp.text)?.groupValues?.get(1)
                                        ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(resp.text)?.groupValues?.get(1)

                                    if (!directMatch.isNullOrBlank() && (directMatch.contains(".m3u8", ignoreCase = true) || directMatch.contains(".mp4", ignoreCase = true) || directMatch.contains(".mkv", ignoreCase = true))) {
                                        val normalizedStream = normalizeVidSrcUrl(directMatch, embedUrl)
                                        val isHls = normalizedStream.contains(".m3u8", ignoreCase = true)
                                        val isDirectVideo = normalizedStream.contains(".mp4", ignoreCase = true) || normalizedStream.contains(".mkv", ignoreCase = true)
                                        val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                                        val m3u8Links = if (isHls) {
                                            withTimeoutOrNull(4000L) {
                                                runCatching { generateM3u8("VidSrc To", normalizedStream, embedUrl, headers = streamHeaders) }.getOrNull()
                                            }
                                        } else null

                                        if (!m3u8Links.isNullOrEmpty()) {
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc To",
                                                baseName = "VidSrc To",
                                                url = normalizedStream,
                                                referer = embedUrl,
                                                headers = streamHeaders,
                                                streamType = streamType,
                                                generatedLinks = m3u8Links,
                                                callback = callback
                                            )
                                        } else if (normalizedStream.startsWith("http", ignoreCase = true)) {
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc To",
                                                baseName = "VidSrc To",
                                                url = normalizedStream,
                                                referer = embedUrl,
                                                headers = streamHeaders,
                                                streamType = streamType,
                                                callback = callback
                                            )
                                        }
                                        return@async true
                                    }

                                    // 2. Nested iframe match
                                    val iframeSrc = resp.document.selectFirst("iframe")?.attr("src")?.takeIf { it.isNotBlank() }
                                        ?: Regex("""iframe\s+src=['"]([^'"]+)['"]""").find(resp.text)?.groupValues?.get(1)
                                        ?: continue

                                    val fullIframeUrl = normalizeVidSrcUrl(iframeSrc, embedUrl)
                                    val iframeResp = runCatching {
                                        safeGet(fullIframeUrl, headers = headers + ("Referer" to embedUrl), timeout = 4L)
                                    }.getOrNull() ?: continue

                                    if (subtitleCallback != null) {
                                        iframeResp.document.select("track[src]").forEach { track ->
                                            if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                                            val src = track.attr("src")
                                            val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                                            if (src.isNotBlank()) {
                                                val subUrl = normalizeVidSrcUrl(src, fullIframeUrl)
                                                val label = cleanSubtitleLabel(rawLabel)
                                                subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                            }
                                        }
                                        val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                        subtitleRegex.findAll(iframeResp.text).forEach { match ->
                                            val subVal = match.groupValues[1]
                                            if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                                                for (part in subVal.split(",")) {
                                                    val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                                                    val subUrl = normalizeVidSrcUrl(if (part.contains("]")) part.substringAfter("]") else part, fullIframeUrl)
                                                    if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                                                        val label = cleanSubtitleLabel(rawLang)
                                                        subtitleCallback.invoke(newSubtitleFile(label, subUrl))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    val iframeStreamHeaders = mapOf(
                                        "Referer" to fullIframeUrl,
                                        "Origin" to getBaseUrl(fullIframeUrl)
                                    )

                                    val m3u8Match = Regex("""['"](https?://[^'"]+\.(?:m3u8|mp4|mkv)[^'"]*)['"]""").find(iframeResp.text)?.groupValues?.get(1)
                                        ?: Regex("""file\s*:\s*['"]([^'"]+)['"]""").find(iframeResp.text)?.groupValues?.get(1)

                                    if (!m3u8Match.isNullOrBlank() && (m3u8Match.contains(".m3u8", ignoreCase = true) || m3u8Match.contains(".mp4", ignoreCase = true) || m3u8Match.contains(".mkv", ignoreCase = true))) {
                                        val normalizedStream = normalizeVidSrcUrl(m3u8Match, fullIframeUrl)
                                        val isHls = normalizedStream.contains(".m3u8", ignoreCase = true)
                                        val isDirectVideo = normalizedStream.contains(".mp4", ignoreCase = true) || normalizedStream.contains(".mkv", ignoreCase = true)
                                        val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                                        val m3u8Links = if (isHls) {
                                            withTimeoutOrNull(4000L) {
                                                runCatching { generateM3u8("VidSrc To", normalizedStream, fullIframeUrl, headers = iframeStreamHeaders) }.getOrNull()
                                            }
                                        } else null

                                        if (!m3u8Links.isNullOrEmpty()) {
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc To",
                                                baseName = "VidSrc To",
                                                url = normalizedStream,
                                                referer = fullIframeUrl,
                                                headers = iframeStreamHeaders,
                                                streamType = streamType,
                                                generatedLinks = m3u8Links,
                                                callback = callback
                                            )
                                        } else if (normalizedStream.startsWith("http", ignoreCase = true)) {
                                            emitTopTierDualQualityStreamLinks(
                                                source = "VidSrc To",
                                                baseName = "VidSrc To",
                                                url = normalizedStream,
                                                referer = fullIframeUrl,
                                                headers = iframeStreamHeaders,
                                                streamType = streamType,
                                                callback = callback
                                            )
                                        }
                                        return@async true
                                    }
                                }
                                false
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.d("StreamPlay", "VidSrc To domain $domain error: ${e.message}")
                                false
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("StreamPlay", "invokeVidSrcTo error: ${e.message}")
            }
        }
    }

    suspend fun invokeVidSrcTo(
        id: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int?
    ) {
        invokeVidSrcTo(id, season, episode, subtitleCallback = null, callback = callback, tmdbId = tmdbId)
    }

    suspend fun invokeVidSrc(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int? = null
    ) {
        withTimeoutOrNull(10000L) {
            coroutineScope {
                // High priority: invokeVidSrcXyz runs immediately with in-memory decoders
                val j1 = async {
                    try {
                        invokeVidSrcXyz(id, season, episode, subtitleCallback, callback, tmdbId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("StreamPlay", "VidSrcXyz sub-job failed: ${e.message}")
                    }
                }
                // Mitigate concurrent request storm: stagger secondary mirrors
                val j2 = async {
                    try {
                        delay(350L)
                        invokeVidSrcCc(id, season, episode, subtitleCallback, callback, tmdbId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("StreamPlay", "VidSrcCc sub-job failed: ${e.message}")
                    }
                }
                val j3 = async {
                    try {
                        delay(700L)
                        invokeVidSrcTo(id, season, episode, subtitleCallback, callback, tmdbId)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("StreamPlay", "VidSrcTo sub-job failed: ${e.message}")
                    }
                }
                j1.await()
                j2.await()
                j3.await()
            }
        }
    }

    suspend fun invokeVidSrc(
        id: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit,
        tmdbId: Int?
    ) {
        invokeVidSrc(id, season, episode, subtitleCallback = null, callback = callback, tmdbId = tmdbId)
    }



    suspend fun invoke4khdhub(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val domain = getDomains()?.n4khdhub ?: return
        val query = title?.takeIf { it.isNotBlank() } ?: return

        val searchDoc = safeGet("$domain/?s=${
            withContext(Dispatchers.IO) {
                URLEncoder.encode(query, "UTF-8")
            }
        }").document
        val normalizedTitle = query.lowercase().trim()
        val yearStr = year?.toString()

        val elements = searchDoc.select("div.card-grid > a.movie-card")

        fun extractContent(el: Element): String {
            return el.selectFirst("div.movie-card-content")
                ?.text()
                ?.lowercase()
                .orEmpty()
        }

        val matched = elements.firstOrNull { el ->
            val content = extractContent(el)
            content.contains(normalizedTitle) &&
                    (yearStr == null || content.contains(yearStr))
        } ?: elements.firstOrNull { el ->
            extractContent(el).contains(normalizedTitle)
        } ?: return

        val link = matched.attr("href")
        val url = if (link.startsWith("http")) link else "$domain$link"

        val doc = safeGet(url).document

        if (season == null) {
            doc.select("div.download-item a")
                .map { it.attr("href") }
                .distinct()
                .safeAmap { href ->
                    val source = getRedirectLinks(href) ?: href
                    loadSourceNameExtractor("4Khdhub", source, "", subtitleCallback, callback)
                }
            return
        }
        else
        {
            val seasonText = "S${season.toString().padStart(2, '0')}"
            val episodeText = episode?.let { "E${it.toString().padStart(2, '0')}" }

            doc.select("div.episode-download-item")
                .asSequence()
                .filter {
                    val text = it.text()
                    text.contains(seasonText, true) &&
                            (episodeText == null || text.contains(episodeText, true))
                }
                .flatMap { it.select("div.episode-links > a").asSequence() }
                .map { it.attr("href") }
                .distinct()
                .toList()
                .safeAmap { href ->
                    val source = getRedirectLinks(href) ?: href
                    loadSourceNameExtractor("4KHDHub", source, "", subtitleCallback, callback)
            }
        }
    }


    suspend fun invokehdhub4u(
        imdbId: String?,
        title: String?,
        year: Int?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val baseUrl = getDomains()?.hdhub4u
        if (title.isNullOrBlank()) return

        val response = safeGet(
            "https://search.pingora.fyi/collections/post/documents/search" +
                    "?q=$title" +
                    "&query_by=post_title,category" +
                    "&query_by_weights=4,2" +
                    "&sort_by=sort_by_date:desc" +
                    "&limit=20" +
                    "&highlight_fields=none" +
                    "&use_cache=true" +
                    "&page=1",
            referer = baseUrl
        )

        val json = try {
            JSONObject(response.text)
        } catch (e: Exception) {
            Log.d("HDhub4u", "Failed to parse JSON: ${e.message}")
            return
        }
        val hits = json.optJSONArray("hits") ?: return

        val normalizedTitle = title.lowercase().replace(normalizeAlphaNumRegex, "")
        val seasonText = season?.let { "season $it" }

        val posts = mutableListOf<String>()

        for (i in 0 until hits.length()) {
            val document = hits.optJSONObject(i)
                ?.optJSONObject("document")
                ?: continue

            val postTitle = document.optString("post_title").lowercase()
            val rawPermalink = document.optString("permalink")

            val permalink = if (rawPermalink.startsWith("http", ignoreCase = true)) {
                rawPermalink
            } else {
                baseUrl + rawPermalink
            }

            if (postTitle.isBlank() || permalink.isBlank()) continue

            val cleanTitle = postTitle.replace(normalizeAlphaNumRegex, "")

            val matches = when {
                season != null ->
                    cleanTitle.contains(normalizedTitle) &&
                            postTitle.contains(seasonText!!, ignoreCase = true)

                year != null ->
                    cleanTitle.contains(normalizedTitle) &&
                            postTitle.contains(year.toString())

                else ->
                    cleanTitle.contains(normalizedTitle)
            }

            if (matches) {
                posts += permalink
            }
        }

        val matchedPosts = if (!imdbId.isNullOrBlank()) {
            val matched = posts.mapNotNull { postUrl ->
                val postDoc = safeGet(postUrl).document
                val imdbHref = postDoc
                    .selectFirst("""a[href*="imdb.com/title/$imdbId"]""")
                    ?.attr("href")
                    ?: return@mapNotNull null

                val foundImdbId = imdbHref
                    .substringAfter("/tt")
                    .substringBefore("/")
                    .let { "tt$it" }

                if (foundImdbId == imdbId) postUrl else null
            }

            matched.ifEmpty { posts }
        } else posts


        matchedPosts.safeAmap { el ->
            val doc = safeGet(el).document

            if (season == null) {
                val qualityLinks =
                    doc.select("h3 a:matches(480|720|1080|2160|4K), h4 a:matches(480|720|1080|2160|4K)")
                for (linkEl in qualityLinks) {
                    val resolvedLink = linkEl.attr("href")
                    val resolvedWatch = if ("id=" in resolvedLink) {
                        runCatching { getRedirectLinks(resolvedLink) ?: resolvedLink }.getOrDefault(resolvedLink)
                    } else resolvedLink
                    loadSourceNameExtractor(
                        "HDhub4u",
                        resolvedWatch,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            } else {
                val episodeRegex = Regex("episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                val h3s = doc.select("h3")

                for (h3 in h3s) {
                    val links = h3.select("a[href]")
                    val episodeLink = links.find { it.text().contains("episode", true) }
                    val watchLink = links.find { it.text().equals("watch", true) }

                    val episodeNum = episodeRegex.find(episodeLink?.text().orEmpty())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()

                    if (episodeNum != null && (episode == null || episode == episodeNum)) {
                        episodeLink?.absUrl("href")?.let { href ->
                            val resolved = if ("id=" in href) getRedirectLinks(href) else href
                            val episodeDoc =
                                runCatching { safeGet(resolved ?: href).document }.getOrNull()
                                    ?: return@let

                            episodeDoc.select("h3 a[href], h4 a[href], h5 a[href]")
                                .mapNotNull { it.absUrl("href").takeIf { url -> url.isNotBlank() } }
                                .forEach { resolvedLink ->
                                    val resolvedWatch = if ("id=" in resolvedLink) {
                                        runCatching { getRedirectLinks(resolvedLink) ?:resolvedLink }.getOrDefault(resolvedLink)
                                    } else resolvedLink
                                    loadSourceNameExtractor(
                                        "HDhub4u",
                                        resolvedWatch,
                                        "",
                                        subtitleCallback,
                                        callback
                                    )
                                }
                        }

                        watchLink?.absUrl("href")?.let { watchHref ->
                            val resolvedWatch =
                                if ("id=" in watchHref) getRedirectLinks(watchHref) ?: watchHref else watchHref
                            loadSourceNameExtractor(
                                "HDhub4u",
                                resolvedWatch,
                                "",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun invokeHdmovie2(
        title: String? = null,
        year: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val hdmovie2API = getDomains()?.hdmovie2 ?: return
        val slug = title?.createSlug() ?: return
        val url = "$hdmovie2API/movies/$slug-$year"

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )

        val document = safeGet(url, headers = headers, allowRedirects = true).document
        val ajaxUrl = "$hdmovie2API/wp-admin/admin-ajax.php"

        val commonHeaders = headers + mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest"
        )

        fun String.getIframe(): String = ZeroAllocParser.extractIframeSrc(this) ?: ""

        suspend fun fetchSource(post: String, nume: String, type: String): String {
            val response = app.post(
                url = ajaxUrl,
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to post,
                    "nume" to nume,
                    "type" to type
                ),
                referer = hdmovie2API,
                headers = commonHeaders
            ).parsed<ResponseHash>()
            return response.embed_url.getIframe()
        }

        var link: String? = null

        if (episode != null) {
            document.select("ul#playeroptionsul > li").getOrNull(1)?.let { ep ->
                val post = ep.attr("data-post")
                val nume = (episode + 1).toString()
                link = fetchSource(post, nume, "movie")
            }
        } else {
            document.select("ul#playeroptionsul > li")
                .firstOrNull {
                    it.text().contains("v2", ignoreCase = true) || it.text()
                        .contains("v3", ignoreCase = true)
                }
                ?.let { mv ->
                    val post = mv.attr("data-post")
                    val nume = mv.attr("data-nume")
                    link = fetchSource(post, nume, "movie")
                }

        }

        // If ajax link failed, fallback to legacy anchors
        if (link.isNullOrEmpty()) {
            val type = if (episode != null) "(Combined)" else ""
            document.select("a[href*=dwo]").safeAmap { anchor ->
                val innerDoc = safeGet(anchor.attr("href")).document
                innerDoc.select("div > p > a").safeAmap {
                    val href = it.attr("href")
                    if (href.contains("GDFlix")) {
                        val redirectedUrl = (1..10).firstNotNullOfOrNull {
                            safeGet(href, allowRedirects = false).headers["location"]
                        } ?: href

                        loadSourceNameExtractor(
                            "Hdmovie2$type",
                            redirectedUrl,
                            "",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
        } else {
            loadSourceNameExtractor(
                "Hdmovie2",
                link,
                hdmovie2API,
                subtitleCallback,
                callback
            )
        }
    }

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    val deviceId = generateDeviceId()
    suspend fun invokeMovieBox(
        title: String?,
        season: Int? = 0,
        episode: Int? = 0,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (title.isNullOrBlank()) return false

            val url = "$movieBox/wefeed-mobile-bff/subject-api/search/v2"
            val jsonBody = """{"page":1,"perPage":10,"keyword":"$title"}"""
            val xClientToken = generateXClientToken()
            val xTrSignature = generateXTrSignature(
                "POST", "application/json", "application/json; charset=utf-8", url, jsonBody
            )
            val headers = mapOf(
                "user-agent" to "com.community.oneroom/50020088 (Linux; U; Android 13; en_US; Subsystem for Android(TM); Build/TQ3A.230901.001; Cronet/145.0.7582.0)",
                "accept" to "application/json",
                "content-type" to "application/json",
                "x-client-token" to xClientToken,
                "x-tr-signature" to xTrSignature,
                "x-client-info" to """{"package_name":"com.community.oneroom","version_name":"3.0.13.0325.03","version_code":50020088,"os":"android","os_version":"13","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d","brand":"Windows","model":"Subsystem for Android(TM)","system_language":"en","net":"NETWORK_WIFI","region":"US","timezone":"Asia/Calcutta","sp_code":"","X-Play-Mode":"1","X-Idle-Data":"1","X-Family-Mode":"0","X-Content-Mode":"0"}""".trimIndent(),
                "x-client-status" to "0"
            )

            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val response = runCatching { app.post(url, headers = headers, requestBody = requestBody, timeout = 10L) }.getOrNull() ?: return false
            if (response.code != 200) return false

            val mapper = streamPlayExtractorMapper
            val root = mapper.readTree(response.text)
            val results = root["data"]?.get("results") ?: return false

            val matchingIds = mutableListOf<String>()
            for (result in results) {
                val subjects = result["subjects"] ?: continue
                for (subject in subjects) {
                    val name = subject["title"]?.asText() ?: continue
                    val id = subject["subjectId"]?.asText() ?: continue
                    val type = subject["subjectType"]?.asInt() ?: 0
                    if (name.contains(title, ignoreCase = true) && (type == 1 || type == 2)) {
                        matchingIds.add(id)
                    }
                }
            }
            if (matchingIds.isEmpty()) return false

            var foundLinks = false

            matchingIds.safeAmap { id ->
                try {
                    val subjectUrl = "$movieBox/wefeed-mobile-bff/subject-api/get?subjectId=$id"
                    val subjectXToken = generateXClientToken()
                    val subjectXSign = generateXTrSignature(
                        "GET",
                        "application/json",
                        "application/json",
                        subjectUrl
                    )
                    val subjectHeaders = headers + mapOf(
                        "x-client-token" to subjectXToken,
                        "x-tr-signature" to subjectXSign
                    )
                    val subjectRes = safeGet(subjectUrl, headers = subjectHeaders, timeout = 8L)

                    val xUserHeader = subjectRes.headers["x-user"]

                    var authtoken: String? = null

                    if (!xUserHeader.isNullOrBlank()) {
                        val xUserJson = mapper.readTree(xUserHeader)
                        authtoken = xUserJson["token"]?.asText()
                    }

                    if (subjectRes.code != 200) return@safeAmap

                    val subjectJson = mapper.readTree(subjectRes.text)
                    val subjectData = subjectJson["data"]
                    val subjectIds = mutableListOf<Pair<String, String>>()
                    var originalLanguageName = "Original"

                    // handle dubs
                    val dubs = subjectData?.get("dubs")
                    if (dubs != null && dubs.isArray) {
                        for (dub in dubs) {
                            val dubId = dub["subjectId"]?.asText()
                            val lanName = dub["lanName"]?.asText()
                            if (dubId != null && lanName != null) {
                                if (dubId == id) {
                                    originalLanguageName = lanName
                                } else {
                                    subjectIds.add(Pair(dubId, lanName))
                                }
                            }
                        }
                    }
                    subjectIds.add(0, Pair(id, originalLanguageName))

                    coroutineScope {
                        subjectIds.map { (subjectId, language) ->
                            async {
                                try {
                                    val playUrl =
                                        "$movieBox/wefeed-mobile-bff/subject-api/play-info?subjectId=$subjectId&se=${season ?: 0}&ep=${episode ?: 0}"
                                    val token = generateXClientToken()
                                    val sign = generateXTrSignature(
                                        "GET",
                                        "application/json",
                                        "application/json",
                                        playUrl
                                    )
                                    val playHeaders = headers + mapOf("x-client-token" to token, "x-tr-signature" to sign)

                                    val playRes = safeGet(playUrl, headers = playHeaders, timeout = 10L)
                                    if (playRes.code != 200) return@async

                                    val playRoot = mapper.readTree(playRes.text)
                                    val streams = playRoot["data"]?.get("streams") ?: return@async
                                    if (!streams.isArray) return@async

                                    for (stream in streams) {
                                        val streamId = stream["id"]?.asText() ?: "$subjectId|$season|$episode"
                                        val format = stream["format"]?.asText() ?: ""
                                        val signCookie =
                                            stream["signCookie"]?.asText()?.takeIf { it.isNotEmpty() }

                                        val playList = stream["playList"]
                                        if (playList != null && playList.isArray) {
                                            for (play in playList) {
                                                val playUrl = play["url"]?.asText() ?: continue
                                                val quality = play["quality"]?.asText() ?: ""
                                                val playFormat = play["format"]?.asText() ?: ""

                                                callback.invoke(
                                                    newExtractorLink(
                                                        source = "MovieBox ${language.replace("dub", "Audio")}",
                                                        name = "MovieBox (${language.replace("dub", "Audio")})",
                                                        url = playUrl,
                                                        type = when {
                                                            playUrl.startsWith(
                                                                "magnet:",
                                                                true
                                                            ) -> ExtractorLinkType.MAGNET

                                                            playUrl.endsWith(
                                                                ".mpd",
                                                                true
                                                            ) -> ExtractorLinkType.DASH

                                                            playUrl.endsWith(
                                                                ".torrent",
                                                                true
                                                            ) -> ExtractorLinkType.TORRENT

                                                            playFormat.equals(
                                                                "HLS",
                                                                true
                                                            ) || playUrl.endsWith(
                                                                ".m3u8",
                                                                true
                                                            ) -> ExtractorLinkType.M3U8

                                                            else -> INFER_TYPE
                                                        }
                                                    ) {
                                                        this.headers = mapOf("Referer" to movieBox) +
                                                                (if (signCookie != null) mapOf("Cookie" to signCookie) else emptyMap())
                                                        this.quality = getQualityFromName("$quality")
                                                    }
                                                )
                                                foundLinks = true
                                            }
                                        } else {
                                            // fallback single url
                                            val singleUrl = stream["url"]?.asText() ?: continue
                                            val resText = stream["resolutions"]?.asText() ?: ""

                                            callback.invoke(
                                                newExtractorLink(
                                                    source = "MovieBox ${language.replace("dub", "Audio")}",
                                                    name = "MovieBox (${language.replace("dub", "Audio")})",
                                                    url = singleUrl,
                                                    type = when {
                                                        singleUrl.startsWith(
                                                            "magnet:",
                                                            true
                                                        ) -> ExtractorLinkType.MAGNET

                                                        singleUrl.endsWith(
                                                            ".mpd",
                                                            true
                                                        ) -> ExtractorLinkType.DASH

                                                        singleUrl.endsWith(
                                                            ".torrent",
                                                            true
                                                        ) -> ExtractorLinkType.TORRENT

                                                        format.equals(
                                                            "HLS",
                                                            true
                                                        ) || singleUrl.endsWith(
                                                            ".m3u8",
                                                            true
                                                        ) -> ExtractorLinkType.M3U8

                                                        else -> INFER_TYPE
                                                    }
                                                ) {
                                                    this.headers = mapOf("Referer" to movieBox) +
                                                            (if (signCookie != null) mapOf("Cookie" to signCookie) else emptyMap())
                                                    this.quality = getQualityFromName(resText)
                                                }
                                            )
                                            foundLinks = true
                                        }

                                        // subtitles
                                        val subLinks = listOf(
                                            "$movieBox/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$subjectId&streamId=$streamId",
                                            "$movieBox/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$subjectId&resourceId=$streamId&episode=${episode ?: 0}"
                                        )

                                        for (subLink in subLinks) {
                                            val subToken = generateXClientToken()
                                            val subSign = generateXTrSignature("GET", "", "", subLink)

                                            val subHeaders = mapOf(
                                                "User-Agent" to "com.community.mbox.in/50020042 (Linux; U; Android 16; en_IN; sdk_gphone64_x86_64; Build/BP22.250325.006; Cronet/133.0.6876.3)",
                                                "Accept" to "",
                                                "Content-Type" to "",
                                                "X-Client-Info" to """{"package_name":"com.community.mbox.in","version_name":"3.0.03.0529.03","version_code":50020042,"os":"android","os_version":"16","device_id":"da2b99c821e6ea023e4be55b54d5f7d8","install_store":"ps","gaid":"d7578036d13336cc","brand":"google","model":"sdk_gphone64_x86_64","system_language":"en","net":"NETWORK_WIFI","sp_code":""}""",
                                                "X-Client-Status" to "0",
                                                "x-client-token" to subToken,
                                                "x-tr-signature" to subSign
                                            )

                                            val subRes = safeGet(subLink, headers = subHeaders, timeout = 10L)
                                            if (subRes.code != 200) continue

                                            val subRoot = mapper.readTree(subRes.text)
                                            val captions = subRoot["data"]?.get("extCaptions")
                                            if (captions != null && captions.isArray) {
                                                for (caption in captions) {
                                                    val captionUrl = caption["url"]?.asText() ?: continue
                                                    val lang = caption["language"]?.asText()
                                                        ?: caption["lanName"]?.asText()
                                                        ?: caption["lan"]?.asText()
                                                        ?: "Unknown"
                                                    subtitleCallback.invoke(
                                                        newSubtitleFile(
                                                            url = captionUrl,
                                                            lang = "$lang (${language.replace("dub", "Audio")})"
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                }
                            }
                        }.awaitAll()
                    }
                } catch (_: Exception) {
                    return@safeAmap
                }
            }

            return foundLinks
        } catch (_: Exception) {
            return false
        }
    }

    suspend fun invokevidrock(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val type = if (season == null) "movie" else "tv"
            val encoded = vidrockEncode(tmdbId, type, season, episode)
            val response = runCatching { safeGet("$vidrock/api/$type/$encoded", timeout = 8L).text }.getOrNull() ?: return
            val sourcesJson = runCatching { JSONObject(response) }.getOrNull() ?: return

            val vidrockHeaders = mapOf(
                "Origin" to vidrock
            )

            sourcesJson.keys().asSequence().toList().safeAmap { key ->
                try {
                    val sourceObj = sourcesJson.optJSONObject(key) ?: return@safeAmap

                    val rawUrl = sourceObj.optString("url", "")
                    val lang = sourceObj.optString("language", "Unknown")
                    if (rawUrl.isBlank() || rawUrl == "null") return@safeAmap

                    val safeUrl = if (rawUrl.contains("%")) {
                        URLDecoder.decode(rawUrl, "UTF-8")
                    } else rawUrl

                    val displayName = "Vidrock [$key] $lang"

                    when {
                        safeUrl.contains("/playlist/") -> {
                            val playlistResponse = runCatching { safeGet(safeUrl, headers = vidrockHeaders, timeout = 8L).text }.getOrNull() ?: return@safeAmap
                            val playlistArray = runCatching { JSONArray(playlistResponse) }.getOrNull() ?: return@safeAmap

                            for (j in 0 until playlistArray.length()) {
                                val item = playlistArray.optJSONObject(j) ?: continue
                                val itemUrl = item.optString("url", "") ?: continue
                                val res = item.optInt("resolution", 0)

                                callback.invoke(
                                    newExtractorLink(
                                        source = "Vidrock-$key",
                                        name = displayName,
                                        url = itemUrl,
                                        type = INFER_TYPE
                                    ) {
                                        this.headers = vidrockHeaders
                                        this.quality = getQualityFromName("$res")
                                    }
                                )
                            }
                        }

                        safeUrl.contains(".mp4", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Vidrock-$key",
                                    name = "$displayName MP4",
                                    url = safeUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.headers = vidrockHeaders
                                }
                            )
                        }

                        safeUrl.contains(".m3u8", ignoreCase = true) -> {
                            runCatching {
                                generateM3u8(
                                    source = "Vidrock-$key",
                                    streamUrl = safeUrl,
                                    referer = "",
                                    quality = Qualities.Unknown.value,
                                    headers = vidrockHeaders
                                ).forEach(callback)
                            }
                        }

                        else -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Vidrock-$key",
                                    name = displayName,
                                    url = safeUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.headers = vidrockHeaders
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("StreamPlay", "Vidrock source $key failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokevidrock failed: ${e.message}")
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
            if (effectiveTmdbId == null || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(25000L) {
                var encData = runCatching {
                    VidLinkCrypto.encryptToken(effectiveTmdbId.toString())
                }.getOrNull() ?: vidlinkEncCache[effectiveTmdbId] ?: run {
                    val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$effectiveTmdbId"
                    val fetchBlock: suspend () -> String? = {
                        retryTransient(4, 350L) {
                            val resp = runCatching { app.get(encUrl, timeout = 15L) }.getOrNull()
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
                    }
                    val fetched = withTimeoutOrNull(2500L) {
                        encDecApiSemaphore.withPermit { fetchBlock() }
                    } ?: fetchBlock()

                    if (!fetched.isNullOrBlank()) {
                        vidlinkEncCache[effectiveTmdbId] = fetched
                    }
                    fetched
                } ?: return@withTimeoutOrNull

                val base = vidlink

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Connection" to "keep-alive",
                    "Referer" to "$base/",
                    "Origin" to base
                )

                val apiUrls = if (season == null || (season == 0 && episode == null)) {
                    listOf("$base/api/b/movie/$encData")
                } else {
                    if (episode == null) return@withTimeoutOrNull
                    if (season == 0) {
                        listOf(
                            "$base/api/b/tv/$encData/$season/$episode",
                            "$base/api/b/movie/$encData"
                        )
                    } else {
                        listOf("$base/api/b/tv/$encData/$season/$episode")
                    }
                }

                var stream: VidlinkStream? = null
                for (apiUrl in apiUrls) {
                    val epResponse = retryTransient(2, 250L) {
                        val resp = suspendCancellable {
                            runCatching { app.get(apiUrl, headers = headers, timeout = 15L) }.getOrNull()
                        }
                        if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) resp.text else null
                    } ?: continue

                    val data = runCatching {
                        Gson().fromJson(epResponse, VidlinkResponse::class.java)
                    }.getOrNull()

                    val s = data?.stream
                    if (s != null && (!s.playlist.isNullOrBlank() || !s.qualities.isNullOrEmpty())) {
                        stream = s
                        break
                    }
                }

                // Resilient network fallback: if primary encrypted token didn't yield a stream, fallback to enc-dec.app
                if (stream == null) {
                    val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$effectiveTmdbId"
                    val remoteEnc = retryTransient(3, 300L) {
                        val resp = runCatching { app.get(encUrl, timeout = 10L) }.getOrNull()
                        if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) {
                            runCatching { JSONObject(resp.text).optString("result") }.getOrNull()?.takeIf { it.isNotBlank() }
                        } else null
                    }
                    if (!remoteEnc.isNullOrBlank() && remoteEnc != encData) {
                        encData = remoteEnc
                        vidlinkEncCache[effectiveTmdbId] = remoteEnc
                        val fallbackUrls = if (season == null || (season == 0 && episode == null)) {
                            listOf("$base/api/b/movie/$remoteEnc")
                        } else {
                            listOf("$base/api/b/tv/$remoteEnc/$season/$episode")
                        }
                        for (apiUrl in fallbackUrls) {
                            val epResponse = retryTransient(2, 250L) {
                                val resp = suspendCancellable {
                                    runCatching { app.get(apiUrl, headers = headers, timeout = 15L) }.getOrNull()
                                }
                                if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) resp.text else null
                            } ?: continue

                            val data = runCatching {
                                Gson().fromJson(epResponse, VidlinkResponse::class.java)
                            }.getOrNull()

                            val s = data?.stream
                            if (s != null && (!s.playlist.isNullOrBlank() || !s.qualities.isNullOrEmpty())) {
                                stream = s
                                break
                            }
                        }
                    }
                }

                if (stream == null) return@withTimeoutOrNull
                vidlinkEncCache[effectiveTmdbId] = encData

                val cronetUserAgent = "com.community.oneroom/50020115 (Linux; U; Android 15; en_US; OPPO CPH2579; Build/AP3A.240905.015.A2; Cronet/140.0.7339.51)"

                fun sanitizeVidlinkHeaders(inputHeaders: Map<String, String>? = null): MutableMap<String, String> {
                    val result = mutableMapOf<String, String>()
                    var foundReferer: String? = null
                    var foundOrigin: String? = null
                    inputHeaders?.forEach { (k, v) ->
                        if (k.equals("Referer", ignoreCase = true)) {
                            if (!v.contains("vidlink.pro", ignoreCase = true) && !v.contains("embed", ignoreCase = true) && v.isNotBlank()) {
                                foundReferer = v
                            }
                        } else if (k.equals("Origin", ignoreCase = true)) {
                            if (!v.contains("vidlink.pro", ignoreCase = true) && !v.contains("embed", ignoreCase = true) && v.isNotBlank()) {
                                foundOrigin = v
                            }
                        } else if (!k.equals("User-Agent", ignoreCase = true) &&
                                   !k.equals("Accept", ignoreCase = true) &&
                                   !k.equals("Accept-Ranges", ignoreCase = true)) {
                            result[k] = v
                        }
                    }
                    val effRef = foundReferer ?: "https://filmboom.top/"
                    val effOrig = foundOrigin ?: "https://filmboom.top"
                    result["Referer"] = effRef
                    result["Origin"] = effOrig
                    result["User-Agent"] = cronetUserAgent
                    result["Accept"] = "*/*"
                    result["Accept-Ranges"] = "bytes"
                    return result
                }

                // 1. Parse captions / subtitles
                stream.captions?.forEach { caption ->
                    val rawSubUrl = caption?.url?.trim()
                    val subUrl = when {
                        rawSubUrl.isNullOrBlank() -> null
                        rawSubUrl.startsWith("http://", ignoreCase = true) || rawSubUrl.startsWith("https://", ignoreCase = true) -> rawSubUrl
                        rawSubUrl.startsWith("//") -> "https:$rawSubUrl"
                        else -> null
                    }
                    if (!subUrl.isNullOrBlank()) {
                        val lang = cleanSubtitleLabel(caption?.language)
                        subtitleCallback?.invoke(newSubtitleFile(lang, subUrl))
                    }
                }

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
                            if (!ref.isNullOrBlank() && !ref.contains("vidlink.pro", ignoreCase = true) && !ref.contains("embed", ignoreCase = true)) {
                                referer = ref
                            }
                            if (!parsedOrigin.isNullOrBlank() && !parsedOrigin.contains("vidlink.pro", ignoreCase = true) && !parsedOrigin.contains("embed", ignoreCase = true)) {
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

                    val effHlsRef = if (referer.isNotBlank()) referer else "https://filmboom.top/"
                    val effHlsOrig = if (origin.isNotBlank()) origin else "https://filmboom.top"
                    val hlsHeaders = sanitizeVidlinkHeaders()
                    hlsHeaders["Origin"] = effHlsOrig
                    hlsHeaders["Referer"] = effHlsRef

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
                        finalHeaders["Referer"] = effHlsRef
                        finalHeaders["Origin"] = effHlsOrig
                        newExtractorLink(
                            genLink.source,
                            genLink.name,
                            genLink.url,
                            genLink.type
                        ) {
                            this.referer = effHlsRef
                            this.quality = genLink.quality
                            this.headers = finalHeaders
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
                            val effectiveRef = qualHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value ?: "https://filmboom.top/"
                            val effectiveOrig = qualHeaders.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value ?: StreamLinkOptimizer.getHostUrl(effectiveRef) ?: "https://filmboom.top"
                            qualHeaders["Referer"] = effectiveRef
                            qualHeaders["Origin"] = effectiveOrig
                            val effectiveQuality = when {
                                qualityKey.equals("720", ignoreCase = true) || qualityKey.equals("720p", ignoreCase = true) -> "720p"
                                qualityKey.equals("1080", ignoreCase = true) || qualityKey.equals("1080p", ignoreCase = true) -> "1080p"
                                qualityKey.equals("480", ignoreCase = true) || qualityKey.equals("480p", ignoreCase = true) -> "480p"
                                qualityKey.equals("360", ignoreCase = true) || qualityKey.equals("360p", ignoreCase = true) -> "360p"
                                qualityKey.equals("2160", ignoreCase = true) || qualityKey.equals("4k", ignoreCase = true) -> "4K"
                                !qualityKey.isNullOrBlank() -> if (qualityKey.endsWith("p", ignoreCase = true)) qualityKey else "${qualityKey}p"
                                else -> "Auto"
                            }
                            directQualitiesLinks.add(
                                newExtractorLink(
                                    "Vidlink",
                                    "Vidlink $effectiveQuality",
                                    url = cleanVideoUrl,
                                    type = if (isDirectVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                ) {
                                    this.referer = effectiveRef
                                    this.quality = qual
                                    this.headers = qualHeaders
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

    // ==================== Vidflix (vidsrc.pm) SOTA Multi-Source Extractor ====================

    private const val VIDFLIX_API = "https://vidsrc.pm"

    /**
     * Player credential published by the vidsrc.pm/Vidflix web player bundle. It is a
     * public, per-player constant (not a per-user secret) and is sent as `x-player-key`.
     */
    private const val VIDFLIX_PLAYER_KEY =
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
    private val VIDFLIX_SOURCES =
        listOf("quasar", "scrapify", "lookmovie", "vaplayer", "fsonic", "oreon")

    private val VIDFLIX_PROXY_MARKERS = listOf("/proxy?", "hd4u.sbs", "hd4u.")

    private val vidflixSemaphore = Semaphore(3)

    private fun vidflixIsProxied(url: String): Boolean =
        VIDFLIX_PROXY_MARKERS.any { url.contains(it, ignoreCase = true) }

    /**
     * The hd4u reverse proxy embeds the upstream Origin/Referer it will forward as a
     * base64 `data=` query parameter (`Origin=...|Referer=...`). Decoding it lets us send
     * exactly the headers the protected origin expects instead of guessing.
     */
    private fun vidflixProxyHeaders(url: String): Map<String, String> {
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

    private data class VidflixStream(
        val url: String,
        val upstream: String,
        val variants: List<Pair<String, Int>>,
        val tracks: List<Pair<String, String>>
    )

    private val vidflixQualityRegex = Regex("""^\s*(\d{3,4})\s*p?""", RegexOption.IGNORE_CASE)

    private fun vidflixQualityOf(label: String?): Int {
        val raw = label?.trim().orEmpty()
        if (raw.isBlank()) return Qualities.Unknown.value
        vidflixQualityRegex.find(raw)?.groupValues?.get(1)?.toIntOrNull()
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

    /** Fetches one upstream via the Vidflix API. Returns null when the mirror is dry. */
    private suspend fun vidflixFetchSource(
        tmdbId: Int,
        season: Int?,
        episode: Int?,
        source: String?
    ): VidflixStream? {
        val isMovie = season == null || (season == 0 && episode == null)
        val path = if (isMovie) {
            "/api/vidora/v1/movie/$tmdbId"
        } else {
            "/api/vidora/v1/tv/$tmdbId/$season/$episode"
        }
        val url = if (source.isNullOrBlank()) "$VIDFLIX_API$path" else "$VIDFLIX_API$path?source=$source"

        val body = retryTransient(2, 250L) {
            val response = suspendCancellable {
                app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, text/plain, */*",
                        "x-player-key" to VIDFLIX_PLAYER_KEY,
                        "Referer" to "$VIDFLIX_API/",
                        "Origin" to VIDFLIX_API
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
                    variants.add(qualityUrl to vidflixQualityOf(label))
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

            return VidflixStream(
                url = streamUrl,
                upstream = entry.optString("source").ifBlank { source.orEmpty() },
                variants = variants,
                tracks = tracks
            )
        }
        return null
    }

    private suspend fun vidflixEmit(stream: VidflixStream, callback: (ExtractorLink) -> Unit) {
        val proxied = vidflixIsProxied(stream.url)
        val headers = if (proxied) {
            vidflixProxyHeaders(stream.url) + mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$VIDFLIX_API/",
                "Origin" to VIDFLIX_API
            )
        } else {
            mapOf("User-Agent" to USER_AGENT, "Referer" to "$VIDFLIX_API/", "Origin" to VIDFLIX_API)
        }
        val referer = headers["Referer"] ?: "$VIDFLIX_API/"
        val tag = if (proxied) "Mirror" else "Direct"
        val baseName = "Vidflix $tag (${stream.upstream})"

        // Honour an explicit bitrate ladder advertised by the upstream first.
        stream.variants.forEach { (variantUrl, quality) ->
            if (!variantUrl.startsWith("http", ignoreCase = true)) return@forEach
            callback(
                newExtractorLink("Vidflix", "$baseName [${vidflixQualityTag(quality)}]", variantUrl) {
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
                    generateM3u8("Vidflix", stream.url, referer = referer, headers = headers)
                }.getOrNull()
            }
            if (!expanded.isNullOrEmpty()) {
                expanded.sortedWith(StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR).forEach { variant ->
                    callback(
                        newExtractorLink(
                            "Vidflix",
                            "$baseName [${vidflixQualityTag(variant.quality)}]",
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
                "Vidflix",
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

    private fun vidflixQualityTag(quality: Int): String = when (quality) {
        Qualities.P2160.value -> "4K"
        Qualities.P1440.value -> "1440p"
        Qualities.P1080.value -> "1080p"
        Qualities.P720.value -> "720p"
        Qualities.P480.value -> "480p"
        Qualities.P360.value -> "360p"
        else -> if (quality > 0 && quality != Qualities.Unknown.value) "${quality}p" else "Auto"
    }

    /**
     * Video Streaming API extractor for Vidflix (vidsrc.pm).
     *
     * Protocol (verified live 2026-10-09):
     *   GET {api}/api/vidora/v1/movie/{tmdb}            -> { result, sources: [...] }
     *   GET {api}/api/vidora/v1/tv/{tmdb}/{s}/{e}?source=KEY
     *   Header: x-player-key: <VIDFLIX_PLAYER_KEY>
     */
    suspend fun invokeVidflix(
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

                val collected = Collections.synchronizedList(mutableListOf<VidflixStream>())
                val emittedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                suspend fun harvest(source: String?) {
                    val stream = vidflixSemaphore.withPermit {
                        runCatching { vidflixFetchSource(resolvedTmdbId, season, episode, source) }.getOrNull()
                    } ?: return
                    collected.add(stream)
                }

                // Resolve the default upstream first: it is the fastest signal that the
                // title is actually indexed, and it lets us emit something within ~1 RTT.
                val defaultStream = withTimeoutOrNull(9_000L) {
                    runCatching { vidflixFetchSource(resolvedTmdbId, season, episode, null) }.getOrNull()
                }
                if (defaultStream != null) {
                    collected.add(defaultStream)
                    defaultStream.tracks.forEach { (language, trackUrl) ->
                        if (emittedTracks.add(trackUrl)) subtitleCallback?.invoke(newSubtitleFile(language, trackUrl))
                    }
                    vidflixEmit(defaultStream, callback)
                }

                // Fan out across the remaining mirrors concurrently.
                coroutineScope {
                    VIDFLIX_SOURCES.map { source ->
                        async {
                            val already = collected.any {
                                it.upstream.equals(source, ignoreCase = true) ||
                                    it.url == defaultStream?.url
                            }
                            if (!already) harvest(source)
                        }
                    }.forEach { it.await() }
                }

                val direct = collected.filterNot { vidflixIsProxied(it.url) }
                val mirrored = collected.filter { vidflixIsProxied(it.url) }

                // Only fall back to proxy-fronted mirrors when nothing directly playable
                // was produced; otherwise they would just pollute the source list.
                if (direct.isEmpty() && mirrored.isEmpty()) {
                    Log.d("StreamPlay", "Vidflix: no upstream returned a stream for $streamKey")
                    return@withTimeoutOrNull
                }

                for (stream in (direct + mirrored).distinctBy { it.url }) {
                    if (stream === defaultStream) continue
                    stream.tracks.forEach { (language, trackUrl) ->
                        if (emittedTracks.add(trackUrl)) subtitleCallback?.invoke(newSubtitleFile(language, trackUrl))
                    }
                    vidflixEmit(stream, callback)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidflix failed: ${e.message}")
        }
    }

    suspend fun invokeVidflix(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidflix(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // ==================== AnimeGG SOTA Extractor ====================

    private const val ANIMEGG_API = "https://www.animegg.org"

    private fun animeggNormalizeTitle(value: String?): String =
        value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""\(\s*(dub|sub|uncensored)\s*\)"""), " ")
            .replace(normalizeAlphaNumSpaceRegex, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /**
     * Resolves the best AnimeGG series slug for the requested title.
     *
     * AnimeGG exposes `/series/{slug}` pages plus dedicated `-dub` variants; `dub` targets
     * prefer the dubbed slug when it exists.
     */
    private suspend fun animeggResolveSlug(
        title: String?,
        jpTitle: String?,
        dub: Boolean
    ): String? {
        val candidates = listOfNotNull(title, jpTitle)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (candidates.isEmpty()) return null

        val wanted = candidates.map { animeggNormalizeTitle(it) }.filter { it.isNotEmpty() }

        for (query in candidates) {
            val searchUrl = "$ANIMEGG_API/search/?q=${URLEncoder.encode(query, "UTF-8")}"
            val html = retryTransient(2, 200L) {
                val response = suspendCancellable {
                    app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 12L)
                }
                if (response != null && response.isSuccessful && response.text.isNotBlank()) response.text else null
            } ?: continue

            val document = runCatching { Jsoup.parse(html, ANIMEGG_API) }.getOrNull() ?: continue
            val parsed = document.select("a.mse").mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                if (!href.contains("/series/")) return@mapNotNull null
                val slug = href.substringAfter("/series/").trim('/').substringBefore('#').substringBefore('?')
                if (slug.isEmpty()) return@mapNotNull null
                val name = anchor.selectFirst("h2")?.text()?.trim()
                    ?: anchor.attr("title").trim()
                slug to name
            }
            if (parsed.isEmpty()) continue

            val exact = parsed.firstOrNull { (slug, name) ->
                val normalizedName = animeggNormalizeTitle(name.ifBlank { slug.replace('-', ' ') })
                normalizedName.isNotEmpty() && normalizedName in wanted
            }
            val prefix = parsed.firstOrNull { (slug, name) ->
                val normalizedName = animeggNormalizeTitle(name.ifBlank { slug.replace('-', ' ') })
                normalizedName.isNotEmpty() && wanted.any { w ->
                    normalizedName.startsWith(w) || w.startsWith(normalizedName)
                }
            }
            val match = exact ?: prefix ?: parsed.first()

            val baseSlug = match.first.removeSuffix("-dub")
            val resolved = if (dub) {
                if (match.first.endsWith("-dub")) match.first else "$baseSlug-dub".takeIf { it.isNotBlank() }
            } else {
                match.first
            }
            return resolved ?: match.first
        }
        return null
    }

    /**
     * Extracts the subtitle/dub mirror iframe from an AnimeGG episode page.
     * The page renders both `data-version="subbed"` and `data-version="dubbed"` panes.
     */
    private fun animeggPickEmbed(document: org.jsoup.nodes.Document, dub: Boolean): String? {
        val order = if (dub) listOf("dubbed", "subbed") else listOf("subbed", "dubbed")
        for (version in order) {
            val pane = document.selectFirst("""[data-version="$version"]""") ?: continue
            val frame = pane.selectFirst("iframe[src]") ?: pane.parent()?.selectFirst("iframe[src]")
            val src = frame?.attr("src")?.trim().orEmpty()
            if (src.isNotEmpty()) {
                return if (src.startsWith("http", ignoreCase = true)) src
                else if (src.startsWith("//")) "https:$src"
                else "$ANIMEGG_API${if (src.startsWith("/")) src else "/$src"}"
            }
        }
        val fallback = document.selectFirst("iframe[src*=embed]")?.attr("src")?.trim().orEmpty()
        if (fallback.isEmpty()) return null
        return if (fallback.startsWith("http", ignoreCase = true)) fallback
        else if (fallback.startsWith("//")) "https:$fallback"
        else "$ANIMEGG_API${if (fallback.startsWith("/")) fallback else "/$fallback"}"
    }

    private data class AnimeggSource(val url: String, val quality: Int, val label: String, val backup: String?)

    private val animeggSourceBlockRegex = Regex("""videoSources\s*=\s*(\[[\s\S]*?])\s*;""")
    private val animeggEntryRegex = Regex("""\{[^{}]*}["']?""")

    /** Parses the player-embedded `videoSources` JS array (unquoted keys, not JSON). */
    private fun animeggParseSources(embedHtml: String): List<AnimeggSource> {
        val block = animeggSourceBlockRegex.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        return animeggEntryRegex.findAll(block).mapNotNull { match ->
            val entry = match.value
            fun field(key: String): String? =
                Regex("""\b$key\s*:\s*['"]([^'"]+)['"]""").find(entry)?.groupValues?.get(1)?.trim()

            val file = field("file") ?: field("src") ?: field("url") ?: return@mapNotNull null
            val resolved = when {
                file.startsWith("http", ignoreCase = true) -> file
                file.startsWith("//") -> "https:$file"
                file.startsWith("/") -> "$ANIMEGG_API$file"
                else -> "$ANIMEGG_API/$file"
            }
            val label = field("label") ?: field("quality") ?: field("resolution") ?: ""
            val backup = field("bk")?.let { encoded ->
                runCatching {
                    val plain = base64Decode(URLDecoder.decode(encoded, "UTF-8"))
                    plain.takeIf { it.startsWith("http", ignoreCase = true) }
                }.getOrNull()
            }
            AnimeggSource(
                url = resolved,
                quality = vidflixQualityOf(label),
                label = label,
                backup = backup
            )
        }.toList()
    }

    /**
     * Video Streaming API extractor for AnimeGG (animegg.org).
     *
     * Protocol (verified live 2026-10-09):
     *   /search/?q={title}                      -> `/series/{slug}` results
     *   /{slug}-episode-{n}  (dub: /{slug}-dub-episode-{n})  -> `/embed/{id}` iframes
     *   /embed/{id}                             -> videoSources = [{file,label,bk}]
     *   /play/{id}/video.mp4?for={token}        -> 302 -> CDN byte stream (video/mp4)
     */
    suspend fun invokeAnimegg(
        title: String? = null,
        jpTitle: String? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        dubStatus: String? = null
    ) {
        try {
            val episodeNumber = episode?.takeIf { it > 0 } ?: 1
            val dub = dubStatus?.contains("dub", ignoreCase = true) == true

            withTimeoutOrNull(25_000L) {
                val slug = withTimeoutOrNull(12_000L) {
                    animeggResolveSlug(title, jpTitle, dub)
                }
                if (slug.isNullOrBlank()) {
                    Log.d("StreamPlay", "AnimeGG: no series match for '${title ?: jpTitle}'")
                    return@withTimeoutOrNull
                }

                val episodeUrl = "$ANIMEGG_API/$slug-episode-$episodeNumber"
                val episodeHtml = retryTransient(2, 200L) {
                    val response = suspendCancellable {
                        app.get(episodeUrl, headers = mapOf("User-Agent" to USER_AGENT), timeout = 12L)
                    }
                    if (response != null && response.isSuccessful && response.text.isNotBlank()) response.text else null
                }
                if (episodeHtml.isNullOrBlank()) {
                    Log.d("StreamPlay", "AnimeGG: episode page missing for $episodeUrl")
                    return@withTimeoutOrNull
                }

                val document = runCatching { Jsoup.parse(episodeHtml, ANIMEGG_API) }.getOrNull()
                    ?: return@withTimeoutOrNull
                val embedUrl = animeggPickEmbed(document, dub)
                if (embedUrl.isNullOrBlank()) {
                    Log.d("StreamPlay", "AnimeGG: no embed mirror for episode $episodeNumber")
                    return@withTimeoutOrNull
                }

                val embedHtml = retryTransient(2, 200L) {
                    val response = suspendCancellable {
                        app.get(
                            embedUrl,
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to episodeUrl),
                            timeout = 12L
                        )
                    }
                    if (response != null && response.isSuccessful && response.text.isNotBlank()) response.text else null
                } ?: return@withTimeoutOrNull

                val parsedSources = animeggParseSources(embedHtml)
                if (parsedSources.isEmpty()) {
                    Log.d("StreamPlay", "AnimeGG: no playable source in $embedUrl")
                    return@withTimeoutOrNull
                }

                val version = if (dub) "DUB" else "SUB"
                parsedSources
                    .sortedByDescending { StreamLinkOptimizer.getQualityPriorityScore(it.quality) }
                    .forEach { source ->
                        val qualityTag = vidflixQualityTag(source.quality)
                        callback(
                            newExtractorLink(
                                "AnimeGG",
                                "AnimeGG · $version [$qualityTag]",
                                source.url,
                                if (source.url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = embedUrl
                                this.quality = source.quality
                                this.headers = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to embedUrl
                                )
                            }
                        )

                        // AnimeGG publishes a mirrored host (typically mp4upload) alongside
                        // the first-party stream; resolve it through the registered
                        // extractor registry instead of inventing playback metadata.
                        source.backup?.let { backupUrl ->
                            runCatching {
                                loadExtractor(backupUrl, embedUrl, subtitleCallback ?: {}, callback)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeAnimegg failed: ${e.message}")
        }
    }

    // ==================== VixSrc SOTA Extractor ====================

    suspend fun invokeVixSrc(
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
            if (effectiveTmdbId == null || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(20000L) {
                val base = "https://vixsrc.to"
                val isMovie = season == null || (season == 0 && episode == null)
                val apiUrl = if (isMovie) {
                    "$base/api/movie/$effectiveTmdbId"
                } else {
                    "$base/api/tv/$effectiveTmdbId/$season/$episode"
                }

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/"
                )

                val apiResp = retryTransient(3, 200L) {
                    val resp = suspendCancellable {
                        runCatching { app.get(apiUrl, headers = headers, timeout = 10L) }.getOrNull()
                    }
                    if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) resp else null
                } ?: return@withTimeoutOrNull

                val srcPath = runCatching { JSONObject(apiResp.text).optString("src") }.getOrNull()
                if (srcPath.isNullOrBlank()) return@withTimeoutOrNull
                val embedUrl = if (srcPath.startsWith("http", ignoreCase = true)) srcPath else "$base$srcPath"

                val embedResp = retryTransient(3, 200L) {
                    val resp = suspendCancellable {
                        runCatching { app.get(embedUrl, headers = headers, timeout = 10L) }.getOrNull()
                    }
                    if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) resp else null
                } ?: return@withTimeoutOrNull

                val html = embedResp.text
                val tokenMatch = Regex("""'token':\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: return@withTimeoutOrNull
                val expiresMatch = Regex("""'expires':\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: return@withTimeoutOrNull
                val plUrl = Regex("""url:\s*'([^']+)'""").find(html)?.groupValues?.get(1) ?: "$base/playlist"
                val masterPlaylistUrl = "$plUrl?token=$tokenMatch&expires=$expiresMatch&h=1&lang=en"

                val m3u8Headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to base
                )

                // Parse and emit subtitles directly from master playlist
                val plResp = suspendCancellable {
                    runCatching { app.get(masterPlaylistUrl, headers = m3u8Headers, timeout = 8L) }.getOrNull()
                }
                if (plResp != null && plResp.isSuccessful) {
                    Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="([^"]+)".*?URI="([^"]+)"""")
                        .findAll(plResp.text)
                        .forEach { match ->
                            val subName = cleanSubtitleLabel(match.groupValues[1])
                            val subUri = match.groupValues[2]
                            val fullSubUri = if (subUri.startsWith("http", ignoreCase = true)) subUri else "$base$subUri"
                            subtitleCallback?.invoke(newSubtitleFile(subName, fullSubUri))
                        }
                }

                val generatedLinks = withTimeoutOrNull(15000L) {
                    runCatching {
                        generateM3u8(
                            "VixSrc",
                            masterPlaylistUrl,
                            referer = embedUrl,
                            headers = m3u8Headers
                        )
                    }.getOrNull()
                }

                if (!generatedLinks.isNullOrEmpty()) {
                    emitTopTierDualQualityStreamLinks(
                        source = "VixSrc",
                        baseName = "VixSrc HLS",
                        url = masterPlaylistUrl,
                        referer = embedUrl,
                        headers = m3u8Headers,
                        streamType = ExtractorLinkType.M3U8,
                        generatedLinks = generatedLinks,
                        callback = callback
                    )
                } else {
                    emitTopTierDualQualityStreamLinks(
                        source = "VixSrc",
                        baseName = "VixSrc HLS",
                        url = masterPlaylistUrl,
                        referer = embedUrl,
                        headers = m3u8Headers,
                        streamType = ExtractorLinkType.M3U8,
                        callback = callback
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVixSrc failed: ${e.message}")
        }
    }

    suspend fun invokeVixSrc(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVixSrc(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    // ==================== VidNest SOTA Extractor ====================

    private const val VIDNEST_ALPHABET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
    private val VIDNEST_DECODE_MAP = IntArray(256) { -1 }.apply {
        for (i in VIDNEST_ALPHABET.indices) {
            val c = VIDNEST_ALPHABET[i].code
            if (c in 0..255) {
                this[c] = i
            }
        }
    }

    fun decryptVidNestCipher(cipherText: String): String? {
        val len = cipherText.length
        if (len == 0) return null
        val bytes = ByteArray(len * 3 / 4 + 4)
        var byteCount = 0
        var i = 0
        while (i < len) {
            val o0 = cipherText.getOrNull(i)?.code ?: 61
            val o1 = cipherText.getOrNull(i + 1)?.code ?: 61
            val o2 = cipherText.getOrNull(i + 2)?.code ?: 61
            val o3 = cipherText.getOrNull(i + 3)?.code ?: 61

            val l0 = if (o0 in 0..255 && VIDNEST_DECODE_MAP[o0] != -1) VIDNEST_DECODE_MAP[o0] else 64
            val l1 = if (o1 in 0..255 && VIDNEST_DECODE_MAP[o1] != -1) VIDNEST_DECODE_MAP[o1] else 64
            val l2 = if (o2 in 0..255 && VIDNEST_DECODE_MAP[o2] != -1) VIDNEST_DECODE_MAP[o2] else 64
            val l3 = if (o3 in 0..255 && VIDNEST_DECODE_MAP[o3] != -1) VIDNEST_DECODE_MAP[o3] else 64

            bytes[byteCount++] = ((l0 shl 2) or (l1 ushr 4)).toByte()
            if (l2 != 64) {
                bytes[byteCount++] = (((l1 and 15) shl 4) or (l2 ushr 2)).toByte()
            }
            if (l3 != 64) {
                bytes[byteCount++] = (((l2 and 3) shl 6) or l3).toByte()
            }
            i += 4
        }
        return runCatching { String(bytes, 0, byteCount, Charsets.UTF_8) }.getOrNull()
    }

    suspend fun invokeVidNest(
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
            if (effectiveTmdbId == null || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(22000L) {
                val eC = "https://new.vidnest.fun"
                val isMovie = season == null || (season == 0 && episode == null)
                val serverPaths = if (isMovie) {
                    listOf(
                        "superstream/movie/$effectiveTmdbId",
                        "nextgencloudfabric/movie/$effectiveTmdbId",
                        "rpmvid/movie/$effectiveTmdbId",
                        "vidzee/movie/$effectiveTmdbId"
                    )
                } else {
                    listOf(
                        "superstream/tv/$effectiveTmdbId/$season/$episode",
                        "nextgencloudfabric/tv/$effectiveTmdbId/$season/$episode",
                        "rpmvid/tv/$effectiveTmdbId/$season/$episode",
                        "vidzee/tv/$effectiveTmdbId/$season/$episode"
                    )
                }

                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "https://vidnest.fun/"
                )

                for (path in serverPaths) {
                    val url = "$eC/$path"
                    val resp = retryTransient(2, 200L) {
                        val r = suspendCancellable {
                            runCatching { app.get(url, headers = headers, timeout = 9L) }.getOrNull()
                        }
                        if (r != null && r.isSuccessful && r.text.isNotBlank()) r else null
                    } ?: continue

                    val body = runCatching { JSONObject(resp.text) }.getOrNull() ?: continue
                    val encryptedData = body.optString("data")
                    if (encryptedData.isBlank()) continue

                    val decryptedJson = decryptVidNestCipher(encryptedData) ?: continue
                    val decryptedObj = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: continue

                    // Parse subtitles if present
                    val subsArray = decryptedObj.optJSONArray("subtitles")
                    if (subsArray != null) {
                        for (idx in 0 until subsArray.length()) {
                            val subObj = subsArray.optJSONObject(idx) ?: continue
                            val subUrl = subObj.optString("url")
                            val lang = cleanSubtitleLabel(subObj.optString("lang").takeIf { it.isNotBlank() } ?: subObj.optString("label"))
                            if (subUrl.startsWith("http", ignoreCase = true)) {
                                subtitleCallback?.invoke(newSubtitleFile(lang, subUrl))
                            }
                        }
                    }

                    // Parse streams or direct url
                    val streamsArray = decryptedObj.optJSONArray("streams")
                    val directUrl = decryptedObj.optString("url")

                    val extractedLinks = mutableListOf<ExtractorLink>()

                    if (streamsArray != null && streamsArray.length() > 0) {
                        for (idx in 0 until streamsArray.length()) {
                            val sObj = streamsArray.optJSONObject(idx) ?: continue
                            val streamUrl = sObj.optString("url")
                            if (!streamUrl.startsWith("http", ignoreCase = true)) continue
                            val streamType = sObj.optString("type")
                            val isHls = streamType.contains("hls", ignoreCase = true) || streamUrl.contains(".m3u8", ignoreCase = true)
                            val qualStr = sObj.optString("quality").takeIf { it.isNotBlank() } ?: sObj.optString("language")
                            val quality = when {
                                qualStr.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                                qualStr.contains("720", ignoreCase = true) -> Qualities.P720.value
                                qualStr.contains("480", ignoreCase = true) -> Qualities.P480.value
                                qualStr.contains("360", ignoreCase = true) -> Qualities.P360.value
                                else -> StreamLinkOptimizer.extractQualityFromText(qualStr).takeIf { q -> q > Qualities.Unknown.value } ?: Qualities.P720.value
                            }
                            val sHeadersObj = sObj.optJSONObject("headers")
                            val streamHeaders = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to "https://vidnest.fun/")
                            if (sHeadersObj != null) {
                                val keys = sHeadersObj.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    streamHeaders[k] = sHeadersObj.optString(k)
                                }
                            }

                            if (isHls) {
                                val gen = withTimeoutOrNull(10000L) {
                                    runCatching {
                                        generateM3u8("VidNest", streamUrl, referer = streamHeaders["Referer"] ?: "https://vidnest.fun/", headers = streamHeaders)
                                    }.getOrNull()
                                }
                                if (!gen.isNullOrEmpty()) {
                                    extractedLinks.addAll(gen)
                                } else {
                                    extractedLinks.add(
                                        newExtractorLink("VidNest", "VidNest HLS", streamUrl, ExtractorLinkType.M3U8) {
                                            this.referer = streamHeaders["Referer"] ?: "https://vidnest.fun/"
                                            this.quality = quality
                                            this.headers = streamHeaders
                                        }
                                    )
                                }
                            } else {
                                extractedLinks.add(
                                    newExtractorLink("VidNest", "VidNest [${qualStr.ifBlank { "HD" }}]", streamUrl, ExtractorLinkType.VIDEO) {
                                        this.referer = streamHeaders["Referer"] ?: "https://vidnest.fun/"
                                        this.quality = quality
                                        this.headers = streamHeaders
                                    }
                                )
                            }
                        }
                    } else if (directUrl.startsWith("http", ignoreCase = true)) {
                        val headerObj = decryptedObj.optJSONObject("headers")
                        val streamHeaders = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to "https://vidnest.fun/")
                        if (headerObj != null) {
                            val keys = headerObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                streamHeaders[k] = headerObj.optString(k)
                            }
                        }
                        val isHls = decryptedObj.optBoolean("hls", true) || directUrl.contains(".m3u8", ignoreCase = true)
                        if (isHls) {
                            val gen = withTimeoutOrNull(10000L) {
                                runCatching {
                                    generateM3u8("VidNest", directUrl, referer = streamHeaders["Referer"] ?: "https://vidnest.fun/", headers = streamHeaders)
                                }.getOrNull()
                            }
                            if (!gen.isNullOrEmpty()) {
                                extractedLinks.addAll(gen)
                            } else {
                                extractedLinks.add(
                                    newExtractorLink("VidNest", "VidNest HLS", directUrl, ExtractorLinkType.M3U8) {
                                        this.referer = streamHeaders["Referer"] ?: "https://vidnest.fun/"
                                        this.quality = Qualities.P720.value
                                        this.headers = streamHeaders
                                    }
                                )
                            }
                        } else {
                            extractedLinks.add(
                                newExtractorLink("VidNest", "VidNest Direct", directUrl, ExtractorLinkType.VIDEO) {
                                    this.referer = streamHeaders["Referer"] ?: "https://vidnest.fun/"
                                    this.quality = Qualities.P720.value
                                    this.headers = streamHeaders
                                }
                            )
                        }
                    }

                    if (extractedLinks.isNotEmpty()) {
                        emitTopTierDualQualityStreamLinks(
                            source = "VidNest",
                            baseName = "VidNest",
                            url = extractedLinks.first().url,
                            referer = extractedLinks.first().referer,
                            headers = extractedLinks.first().headers,
                            streamType = extractedLinks.first().type,
                            generatedLinks = extractedLinks,
                            callback = callback
                        )
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidNest failed: ${e.message}")
        }
    }

    suspend fun invokeVidNest(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidNest(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }


    private fun base64UrlDecodeSafe(input: String): ByteArray {
        return try {
            java.util.Base64.getUrlDecoder().decode(input)
        } catch (_: Throwable) {
            val fixed = input.replace('-', '+').replace('_', '/')
            val pad = (4 - fixed.length % 4) % 4
            base64DecodeArray(fixed + "=".repeat(pad))
        }
    }

    private fun base64UrlEncodeSafe(input: ByteArray): String {
        return try {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(input)
        } catch (_: Throwable) {
            base64Encode(input).replace('+', '-').replace('/', '_').replace("=", "")
        }
    }

    @Deprecated("Vidcore has been decommissioned and removed due to oversized stream files causing crashes.")
    suspend fun invokeVidcore(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        // No-op: Vidcore decommissioned to prevent playback crashes from oversized files
    }

    @Deprecated("Vidcore has been decommissioned and removed due to oversized stream files causing crashes.")
    suspend fun invokeVidcore(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        // No-op: Vidcore decommissioned to prevent playback crashes from oversized files
    }

    suspend fun invokeVidup(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit,
        imdbId: String? = null
    ) {
        try {
            if ((tmdbId == null && imdbId.isNullOrBlank()) || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(25000L) {
                val base = "https://vidup.to"
                val api = "https://enc-dec.app/api"

                val idCandidates = mutableListOf<String>()
                if (tmdbId != null) idCandidates.add(tmdbId.toString())
                if (!imdbId.isNullOrBlank() && imdbId != tmdbId?.toString()) idCandidates.add(imdbId)

                val requestUrls = mutableListOf<String>()
                for (mediaId in idCandidates) {
                    if (season == null || (season == 0 && episode == null)) {
                        requestUrls.add("$base/movie/$mediaId")
                    } else {
                        if (episode == null) continue
                        if (season == 0) {
                            requestUrls.add("$base/tv/$mediaId/$season/$episode/")
                            requestUrls.add("$base/movie/$mediaId")
                        } else {
                            requestUrls.add("$base/tv/$mediaId/$season/$episode/")
                        }
                    }
                }
                if (requestUrls.isEmpty()) return@withTimeoutOrNull

                val baseHeaders = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$base/",
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Ch-Ua" to "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\""
                )

                var encodedToken: String? = null
                var matchedUrl: String? = null
                for (requestUrl in requestUrls) {
                    val pageResp = retryTransient(2, 200L) {
                        runCatching {
                            app.get(requestUrl, headers = baseHeaders, timeout = 5L)
                        }.getOrNull()
                    } ?: continue

                    val pageText = pageResp.text
                    val token = Regex("""\\?"(?:en|token)\\?"\s*:\s*\\?"([^"\\]+)""").find(pageText)?.groupValues?.get(1)

                    if (!token.isNullOrBlank()) {
                        encodedToken = token
                        matchedUrl = requestUrl
                        val cookieHeader = if (pageResp.cookies.isNotEmpty()) {
                            pageResp.cookies.map { "${it.key}=${it.value}" }.joinToString("; ")
                        } else {
                            pageResp.headers.values("Set-Cookie").mapNotNull {
                                it.substringBefore(";").trim().takeIf { c -> c.isNotEmpty() }
                            }.joinToString("; ")
                        }
                        if (cookieHeader.isNotBlank()) {
                            baseHeaders["Cookie"] = cookieHeader
                        }
                        break
                    }
                }

                if (encodedToken.isNullOrBlank()) return@withTimeoutOrNull

                baseHeaders["Origin"] = base
                if (matchedUrl != null) {
                    baseHeaders["Referer"] = matchedUrl
                }

                val encodedParam = URLEncoder.encode(encodedToken, "UTF-8")
                val encJson = encDecApiSemaphore.withPermit {
                    retryTransient(2, 300L) {
                        runCatching {
                            app.get("$api/enc-vidup?text=$encodedParam", timeout = 6L)
                                .parsedSafe<VidcoreEncResponse>()
                        }.getOrNull()
                    }
                } ?: return@withTimeoutOrNull

                val encResult = encJson.result ?: return@withTimeoutOrNull
                val serversUrl = encResult.servers
                val streamBase = encResult.stream
                val csrfToken = encResult.token

                if (serversUrl.isBlank() || streamBase.isBlank()) return@withTimeoutOrNull

                if (csrfToken.isNotBlank()) {
                    baseHeaders["X-CSRF-Token"] = csrfToken
                }
                baseHeaders["X-Requested-With"] = "XMLHttpRequest"

                val serversEncrypted = retryTransient(2, 250L) {
                    runCatching {
                        val resp = app.post(serversUrl, headers = baseHeaders, timeout = 6L)
                        if (resp.isSuccessful && resp.text.isNotBlank()) resp.text else null
                    }.getOrNull()
                } ?: return@withTimeoutOrNull

                val serversRoot = encDecApiSemaphore.withPermit {
                    retryTransient(2, 300L) {
                        runCatching {
                            app.post(
                                "$api/dec-vidup",
                                json = mapOf("text" to serversEncrypted),
                                timeout = 6L
                            ).parsedSafe<VidcoreServersResponse>()
                        }.getOrNull()
                    }
                } ?: return@withTimeoutOrNull

                val serversList = serversRoot.result
                if (serversList.isEmpty()) return@withTimeoutOrNull

                val vidupServerSemaphore = Semaphore(2)
                coroutineScope {
                    serversList.take(3).mapIndexed { index, server ->
                        async {
                            try {
                                val name = server.name.ifBlank { "Server ${index + 1}" }
                                val data = server.data
                                if (data.isBlank()) return@async

                                val streamUrl = "$streamBase/$data"
                                val streamEncrypted = retryTransient(1, 150L) {
                                    runCatching {
                                        val resp = app.post(streamUrl, headers = baseHeaders, timeout = 5L)
                                        if (resp.isSuccessful && resp.text.isNotBlank()) resp.text else null
                                    }.getOrNull()
                                } ?: return@async

                                val streamRoot = vidupServerSemaphore.withPermit {
                                    encDecApiSemaphore.withPermit {
                                        retryTransient(2, 250L) {
                                            runCatching {
                                                app.post(
                                                    "$api/dec-vidup",
                                                    json = mapOf("text" to streamEncrypted),
                                                    timeout = 6L
                                                ).parsedSafe<VidcoreStreamResponse>()
                                            }.getOrNull()
                                        }
                                    }
                                } ?: return@async

                                val streamResult = streamRoot.result ?: return@async
                                val finalUrl = streamResult.url?.trim() ?: return@async
                                if (!finalUrl.startsWith("http", ignoreCase = true)) return@async

                                // Extract subtitles
                                streamResult.tracks?.forEach { track ->
                                    val subUrl = track.file?.trim()
                                    val subLabel = track.label?.trim() ?: "English"
                                    if (!subUrl.isNullOrBlank() && subUrl.startsWith("http", ignoreCase = true)) {
                                        subtitleCallback?.invoke(newSubtitleFile(cleanSubtitleLabel(subLabel), subUrl))
                                    }
                                }

                                val streamHeaders = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "$base/",
                                    "Origin" to base
                                )

                                val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                                val variants = if (isM3u8) {
                                    runCatching {
                                        generateM3u8("Vidup", finalUrl, referer = "$base/", headers = streamHeaders)
                                    }.getOrNull()
                                } else null

                                emitTopTierDualQualityStreamLinks(
                                    source = "Vidup",
                                    baseName = "Vidup $name",
                                    url = finalUrl,
                                    referer = "$base/",
                                    headers = streamHeaders,
                                    streamType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    generatedLinks = variants,
                                    callback = callback
                                )
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.w("StreamPlay", "Vidup server ${server.name} failed: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidup failed: ${e.message}")
        }
    }

    suspend fun invokeVidup(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeVidup(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }


    private data class YFlixCandidateResult(
        val domain: String,
        val videoUrl: String,
        val rawJson: String
    )

    private data class CineJoyCandidateResult(
        val streamUrl: String,
        val json: JSONObject? = null,
        val referer: String = "https://solarpanelcleaning.cc/",
        val headers: Map<String, String>? = null,
        val captions: List<SubtitleFile> = emptyList(),
        val server: String = ""
    )

    suspend fun invokeYFlix(
        title: String?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (title.isNullOrBlank() && tmdbId == null && imdbId.isNullOrBlank()) return
            if (season != null && season != 0 && episode == null) return

            withTimeoutOrNull(2800L) {
                val domainTargets = listOf("https://yflix.to", "https://moviesflix.to", "https://yflix.cc")
                val cleanTitle = title?.replace(Regex("[^a-zA-Z0-9\\s]"), " ")?.trim()?.replace(Regex("\\s+"), "-")?.lowercase(Locale.ROOT) ?: ""

                val isTv = (season != null && season > 0) || (episode != null && episode > 0)
                val paths = if (isTv) {
                    listOf(
                        if (tmdbId != null) "/api/source/tmdb/tv/$tmdbId/$season/$episode" else null,
                        if (!imdbId.isNullOrBlank()) "/api/source/imdb/tv/$imdbId/$season/$episode" else null,
                        "/api/stream/tv?title=$cleanTitle&season=$season&episode=$episode&year=${year ?: ""}",
                        "/api/v1/watch?title=$cleanTitle&season=$season&episode=$episode"
                    ).filterNotNull()
                } else {
                    listOf(
                        if (tmdbId != null) "/api/source/tmdb/movie/$tmdbId" else null,
                        if (!imdbId.isNullOrBlank()) "/api/source/imdb/movie/$imdbId" else null,
                        "/api/stream/movie?title=$cleanTitle&year=${year ?: ""}",
                        "/api/v1/watch?title=$cleanTitle&year=${year ?: ""}"
                    ).filterNotNull()
                }

                val candidates = domainTargets.flatMap { domain -> paths.map { path -> domain to path } }
                if (candidates.isEmpty()) return@withTimeoutOrNull

                val winnerDeferred = CompletableDeferred<YFlixCandidateResult?>()

                val winner = coroutineScope {
                    val jobs = candidates.map { (domain, path) ->
                        launch(Dispatchers.IO) {
                            if (winnerDeferred.isCompleted) return@launch
                            try {
                                val apiUrl = "$domain$path"
                                val yflixHeaders = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "$domain/",
                                    "Origin" to domain,
                                    "Accept" to "application/json, text/plain, */*"
                                )
                                val responseText = withTimeoutOrNull(2200L) {
                                    cancellableGetText(apiUrl, headers = yflixHeaders, timeoutSec = 2L)
                                } ?: return@launch

                                if (responseText.isBlank() || responseText.contains("404 Not Found", ignoreCase = true)) return@launch

                                var rawJson = responseText
                                if (responseText.startsWith("{") && !responseText.contains("\"url\"") && responseText.contains("\"data\"")) {
                                    val encData = runCatching { JSONObject(responseText).optString("data") }.getOrNull()
                                    if (!encData.isNullOrBlank()) {
                                        val decrypted = yflixDecodeReverse(encData)
                                        if (decrypted.isNotBlank()) rawJson = decrypted
                                    }
                                } else if (!responseText.startsWith("{")) {
                                    val decoded = yflixDecodeReverse(responseText.trim())
                                    if (decoded.isNotBlank() && decoded.startsWith("{")) {
                                        rawJson = decoded
                                    }
                                }

                                if (rawJson.startsWith("{")) {
                                    var videoUrl = runCatching { yflixextractVideoUrlFromJson(rawJson) }.getOrNull()
                                    if (videoUrl.isNullOrBlank()) {
                                        val json = runCatching { JSONObject(rawJson) }.getOrNull()
                                        videoUrl = json?.optString("streamUrl")?.takeIf { it.isNotBlank() }
                                            ?: json?.optString("file")?.takeIf { it.isNotBlank() }
                                            ?: json?.optString("url")?.takeIf { it.isNotBlank() }
                                    }

                                    if (!videoUrl.isNullOrBlank()) {
                                        winnerDeferred.complete(YFlixCandidateResult(domain, videoUrl, rawJson))
                                    }
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                            }
                        }
                    }

                    val supervisor = launch {
                        jobs.joinAll()
                        winnerDeferred.complete(null)
                    }

                    try {
                        winnerDeferred.await()
                    } finally {
                        jobs.forEach { it.cancel() }
                        supervisor.cancel()
                    }
                }

                if (winner != null) {
                    val (domain, videoUrl, rawJson) = winner

                    val subsArray = runCatching {
                        JSONObject(rawJson).optJSONArray("subtitles") ?: JSONObject(rawJson).optJSONArray("tracks")
                    }.getOrNull()
                    if (subsArray != null && subtitleCallback != null) {
                        for (i in 0 until subsArray.length()) {
                            val subObj = subsArray.optJSONObject(i) ?: continue
                            val subFile = subObj.optString("file").takeIf { it.isNotBlank() }
                                ?: subObj.optString("url").takeIf { it.isNotBlank() } ?: continue
                            val subLang = subObj.optString("label").takeIf { it.isNotBlank() }
                                ?: subObj.optString("lang").takeIf { it.isNotBlank() } ?: "English"
                            if (!subFile.contains("thumbnail", ignoreCase = true)) {
                                subtitleCallback(
                                    newSubtitleFile(
                                        cleanSubtitleLabel(subLang),
                                        subFile
                                    )
                                )
                            }
                        }
                    }

                    val streamHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$domain/",
                        "Origin" to domain
                    )
                    val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                    val streamType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val variants = if (isM3u8) {
                        runCatching {
                            generateM3u8("YFlix", videoUrl, "$domain/", headers = streamHeaders)
                        }.getOrNull()
                    } else null

                    emitTopTierDualQualityStreamLinks(
                        source = "YFlix",
                        baseName = "YFlix",
                        url = videoUrl,
                        referer = "$domain/",
                        headers = streamHeaders,
                        streamType = streamType,
                        generatedLinks = variants,
                        callback = callback
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeYFlix failed: ${e.message}")
        }
    }

    suspend fun invokeYFlix(
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeYFlix(title, tmdbId = null, imdbId = null, year = year, season = season, episode = episode, subtitleCallback = subtitleCallback, callback = callback)
    }

    suspend fun invokeYFlix(
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeYFlix(title, tmdbId = null, imdbId = null, year = year, season = season, episode = episode, subtitleCallback = null, callback = callback)
    }

    suspend fun invokeYFlix(
        title: String?,
        tmdbId: Int?,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeYFlix(title, tmdbId = tmdbId, imdbId = null, year = year, season = season, episode = episode, subtitleCallback = subtitleCallback, callback = callback)
    }

    suspend fun invokeYFlix(
        title: String?,
        tmdbId: Int?,
        year: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeYFlix(title, tmdbId = tmdbId, imdbId = null, year = year, season = season, episode = episode, subtitleCallback = null, callback = callback)
    }

    suspend fun invokeCineJoy(
        title: String?,
        tmdbId: Int? = null,
        imdbId: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (title.isNullOrBlank() && tmdbId == null && imdbId.isNullOrBlank()) return
            if (season != null && season != 0 && episode == null) return

            withTimeoutOrNull(2200L) {
                val isTv = (season != null && season > 0) || (episode != null && episode > 0)
                val encodedTitle = URLEncoder.encode(title ?: "", "UTF-8")
                val wingType = if (isTv) "series" else "movie"
                val seasonStr = if (isTv && season != null) season.toString() else ""
                val episodeStr = if (isTv && episode != null) episode.toString() else ""
                val yearStr = year?.toString() ?: ""
                val imdbStr = imdbId ?: ""
                val tmdbStr = tmdbId?.toString() ?: ""

                val servers = listOf("Lisbon", "Nebula", "Solara")
                val winnerDeferred = CompletableDeferred<CineJoyCandidateResult?>()

                val winner = coroutineScope {
                    val serverJobs = servers.map { server ->
                        launch(Dispatchers.IO) {
                            if (winnerDeferred.isCompleted) return@launch
                            try {
                                currentCoroutineContext().ensureActive()
                                val targetUrl = "https://api.wing.st/?title=$encodedTitle&type=$wingType&year=$yearStr&imdb=$imdbStr&tmdb=$tmdbStr&server=$server&season=$seasonStr&episode=$episodeStr"
                                val encUrl = "https://enc-dec.app/api/enc-cinejoy?url=${URLEncoder.encode(targetUrl, "UTF-8")}"

                                val encResp = encDecApiSemaphore.withPermit {
                                    currentCoroutineContext().ensureActive()
                                    withTimeoutOrNull(1800L) {
                                        cancellableGetText(encUrl, timeoutSec = 2L)
                                    }
                                } ?: return@launch
                                currentCoroutineContext().ensureActive()

                                if (encResp.isBlank()) return@launch
                                val encJson = runCatching { JSONObject(encResp) }.getOrNull() ?: return@launch

                                // Support direct mock or fallback response with direct url
                                val directUrl = encJson.optString("url").takeIf { it.isNotBlank() }
                                    ?: encJson.optString("streamUrl").takeIf { it.isNotBlank() }
                                if (directUrl != null) {
                                    winnerDeferred.complete(
                                        CineJoyCandidateResult(
                                            streamUrl = directUrl,
                                            json = encJson,
                                            referer = "https://cinejoy.pk/",
                                            server = server
                                        )
                                    )
                                    return@launch
                                }

                                val resObj = encJson.optJSONObject("result") ?: return@launch
                                val encData = resObj.optString("data").takeIf { it.isNotBlank() } ?: return@launch
                                val stateObj = resObj.optJSONObject("state") ?: return@launch

                                val binaryData = runCatching { base64UrlDecodeSafe(encData) }.getOrNull() ?: return@launch

                                val wingHeaders = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://cinejoy.pk/",
                                    "Origin" to "https://cinejoy.pk"
                                )

                                currentCoroutineContext().ensureActive()
                                val gResp = withTimeoutOrNull(1800L) {
                                    cancellablePost(
                                        "https://api.wing.st/g",
                                        headers = wingHeaders,
                                        requestBody = binaryData.toRequestBody("application/octet-stream".toMediaType()),
                                        timeoutSec = 2L
                                    )
                                } ?: return@launch
                                currentCoroutineContext().ensureActive()

                                if (!gResp.isSuccessful) {
                                    gResp.close()
                                    return@launch
                                }
                                val gBytes = runCatching { gResp.body?.bytes() }.getOrNull() ?: run {
                                    gResp.close()
                                    return@launch
                                }
                                gResp.close()
                                if (gBytes.isEmpty()) return@launch

                                val gB64 = base64UrlEncodeSafe(gBytes)
                                val decReqBody = JSONObject().apply {
                                    put("text", gB64)
                                    put("state", stateObj)
                                }.toString()

                                currentCoroutineContext().ensureActive()
                                val decResp = encDecApiSemaphore.withPermit {
                                    currentCoroutineContext().ensureActive()
                                    withTimeoutOrNull(1800L) {
                                        cancellablePostText(
                                            "https://enc-dec.app/api/dec-cinejoy",
                                            headers = mapOf("Content-Type" to "application/json", "User-Agent" to USER_AGENT),
                                            requestBody = decReqBody.toRequestBody("application/json".toMediaType()),
                                            timeoutSec = 2L
                                        )
                                    }
                                } ?: return@launch
                                currentCoroutineContext().ensureActive()

                                if (decResp.isBlank()) return@launch
                                val decJson = runCatching { JSONObject(decResp) }.getOrNull() ?: return@launch
                                val dataObj = decJson.optJSONObject("result")?.optJSONObject("data") ?: return@launch
                                val streamArray = dataObj.optJSONArray("stream") ?: return@launch
                                if (streamArray.length() == 0) return@launch

                                val primaryStream = streamArray.optJSONObject(0) ?: return@launch
                                val playlistUrl = primaryStream.optString("playlist").takeIf { it.isNotBlank() } ?: return@launch

                                val cinejoyHeaders = mapOf(
                                    "User-Agent" to USER_AGENT,
                                    "Referer" to "https://cinejoy.pk/",
                                    "Origin" to "https://cinejoy.pk"
                                )

                                val captionsList = mutableListOf<SubtitleFile>()
                                val captionsArray = primaryStream.optJSONArray("captions")
                                if (captionsArray != null) {
                                    for (cIdx in 0 until captionsArray.length()) {
                                        val capObj = captionsArray.optJSONObject(cIdx) ?: continue
                                        val capUrl = capObj.optString("url").takeIf { it.isNotBlank() }
                                            ?: capObj.optString("file").takeIf { it.isNotBlank() } ?: continue
                                        val capLang = capObj.optString("language").takeIf { it.isNotBlank() }
                                            ?: capObj.optString("label").takeIf { it.isNotBlank() } ?: "English"
                                        captionsList.add(newSubtitleFile(cleanSubtitleLabel(capLang), capUrl))
                                    }
                                }

                                winnerDeferred.complete(
                                    CineJoyCandidateResult(
                                        streamUrl = playlistUrl,
                                        json = null,
                                        referer = "https://cinejoy.pk/",
                                        headers = cinejoyHeaders,
                                        captions = captionsList,
                                        server = server
                                    )
                                )
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                            }
                        }
                    }

                    val supervisor = launch {
                        serverJobs.joinAll()
                        winnerDeferred.complete(null)
                    }

                    try {
                        winnerDeferred.await()
                    } finally {
                        serverJobs.forEach { it.cancel() }
                        supervisor.cancel()
                    }
                }

                if (winner != null) {
                    winner.captions.forEach { subtitleCallback?.invoke(it) }

                    if (winner.json != null) {
                        val tracksArray = winner.json.optJSONArray("subtitles") ?: winner.json.optJSONArray("tracks")
                        if (tracksArray != null && subtitleCallback != null) {
                            for (i in 0 until tracksArray.length()) {
                                val trackObj = tracksArray.optJSONObject(i) ?: continue
                                val trackUrl = trackObj.optString("file").takeIf { it.isNotBlank() }
                                    ?: trackObj.optString("url").takeIf { it.isNotBlank() } ?: continue
                                val trackLabel = trackObj.optString("label").takeIf { it.isNotBlank() }
                                    ?: trackObj.optString("lang").takeIf { it.isNotBlank() } ?: "English"
                                val trackKind = trackObj.optString("kind", "subtitles")
                                if (!trackUrl.contains("thumbnail", ignoreCase = true) && !trackKind.equals("thumbnails", ignoreCase = true)) {
                                    subtitleCallback(newSubtitleFile(cleanSubtitleLabel(trackLabel), trackUrl))
                                }
                            }
                        }
                    }

                    val streamHeaders = winner.headers ?: mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to winner.referer,
                        "Origin" to winner.referer.removeSuffix("/")
                    )

                    val isM3u8 = winner.streamUrl.contains(".m3u8", ignoreCase = true)
                    val streamType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    val variants = if (isM3u8) {
                        runCatching {
                            generateM3u8("CineJoy", winner.streamUrl, winner.referer, headers = streamHeaders)
                        }.getOrNull()
                    } else null

                    emitTopTierDualQualityStreamLinks(
                        source = "CineJoy",
                        baseName = "CineJoy",
                        url = winner.streamUrl,
                        referer = winner.referer,
                        headers = streamHeaders,
                        streamType = streamType,
                        generatedLinks = variants,
                        callback = callback
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeCineJoy failed: ${e.message}")
        }
    }

    suspend fun invokeCineJoy(
        title: String?,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeCineJoy(title, tmdbId = tmdbId, imdbId = null, year = null, season = season, episode = episode, subtitleCallback = subtitleCallback, callback = callback)
    }

    suspend fun invokeCineJoy(
        title: String?,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeCineJoy(title, tmdbId = tmdbId, imdbId = null, year = null, season = season, episode = episode, subtitleCallback = null, callback = callback)
    }

    suspend fun invokeMoviesApi(
        id: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        if (id == null) return

        val href =
            if (season == null) "$moviesClubApi/movie/$id" else "$moviesClubApi/tv/$id-$season-$episode"
        val pageDoc = runCatching { safeGet(href).document }.getOrNull() ?: return
        val iframeElement = pageDoc.selectFirst("iframe[src], iframe[data-src]") ?: return
        val iframeSrc = iframeElement.attr("src").ifEmpty { iframeElement.attr("data-src") }
        if (iframeSrc.isEmpty()) return
        val iframeDoc = runCatching { safeGet(iframeSrc).document }.getOrNull() ?: return
        val scriptData = iframeDoc.select("script")
            .firstOrNull { e ->
                val d = e.data()
                d.contains("function(p,a,c,k,e,d)")
            }?.data() ?: iframeDoc.selectFirst("script")?.data() ?: return
        val unPacked = runCatching { getAndUnpack(scriptData) }.getOrNull() ?: scriptData
        val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)

        if (m3u8 != null) {
            generateM3u8(
                "MoviesApi Club",
                m3u8,
                iframeSrc,
                headers = mapOf("Referer" to iframeSrc)
            ).forEach(callback)
        }
    }

    suspend fun invokecinemacity(
        imdbId: String?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (imdbId.isNullOrBlank()) return

        val headers = mapOf(
            "Cookie" to base64Decode("ZGxlX3VzZXJfaWQ9MzI3Mjk7IGRsZV9wYXNzd29yZD04OTQxNzFjNmE4ZGFiMThlZTU5NGQ1YzY1MjAwOWEzNTs=")
        )

        val searchUrl = "$cinemacity/?do=search&subaction=search&search_start=0&full_search=0&story=$imdbId"

        val pageUrl = safeGet(searchUrl, headers, interceptor = CloudflareKiller())
            .document
            .selectFirst("div.dar-short_item > a")
            ?.attr("href")
            ?: return

        val script = safeGet(pageUrl, headers)
            .document
            .select("script:containsData(atob)")
            .getOrNull(1)
            ?.data()
            ?: return

        val playerJson = JSONObject(
            base64Decode(
                script.substringAfter("atob(\"").substringBefore("\")")
            ).substringAfter("new Playerjs(").substringBeforeLast(");")
        )


        val fileArray = JSONArray(playerJson.getString("file"))

        fun extractQuality(url: String): Int {
            return when {
                url.contains("2160p") -> Qualities.P2160.value
                url.contains("1440p") -> Qualities.P1440.value
                url.contains("1080p") -> Qualities.P1080.value
                url.contains("720p") -> Qualities.P720.value
                url.contains("480p") -> Qualities.P480.value
                url.contains("360p") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        val subtitleTracks = cinemacityparseSubtitles(
            playerJson.optString("subtitle")
        )

        suspend fun emitExtractorLinks(files: String,seasonNum: Int? = null, episodeNum: Int? = null) {

            callback.invoke(
                newExtractorLink(
                    "CineCity",
                    "CineCity Multi-Audio",
                    files,
                    INFER_TYPE
                ) {
                    referer = pageUrl
                    quality = extractQuality(files)
                }
            )

            val parts = files.split(",")
            val audioFiles = parts.filter { it.endsWith(".m4a") }

            audioFiles.forEachIndexed { index, _ ->

                val downloads = cinemacitybuildDownloadLinks(
                    base = files,
                    subtitles = subtitleTracks,
                    selectedAudioIndex = index,
                    title = "CineCity",
                    season = seasonNum,
                    episode = episodeNum
                )

                downloads.forEach { (dlUrl, quality, lang) ->

                    callback.invoke(
                        newExtractorLink(
                            "CineCity",
                            "CineCity • $lang • Download",
                            dlUrl,
                            INFER_TYPE
                        ) {
                            referer = pageUrl
                            this.quality = quality
                        }
                    )
                }
            }
        }

        val first = fileArray.getJSONObject(0)

        // MOVIE
        if (!first.has("folder")) {
            emitExtractorLinks(
                files = first.getString("file"),
                seasonNum = null,
                episodeNum = null
            )
            return
        }

        // SERIES
        for (i in 0 until fileArray.length()) {
            val seasonJson = fileArray.getJSONObject(i)

            val seasonNumber = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(seasonJson.optString("title"))
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: continue

            if (season != null && seasonNumber != season) continue

            val episodes = seasonJson.getJSONArray("folder")
            for (j in 0 until episodes.length()) {
                val epJson = episodes.getJSONObject(j)

                val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epJson.optString("title"))
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?: continue

                if (episode != null && episodeNumber != episode) continue

                emitExtractorLinks(
                    files = epJson.getString("file"),
                    seasonNum = seasonNumber,
                    episodeNum = episodeNumber
                )
            }
        }
    }

    suspend fun invokeHexa(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: ((SubtitleFile) -> Unit)? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (tmdbId == null || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(2500L) {
                val key = generateHexKey32()

                val baseHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to "https://hexa.su/",
                    "Accept" to "text/plain",
                    "X-Fingerprint-Lite" to "e9136c41504646444",
                    "X-Api-Key" to key
                )

                val apiBase = "https://enc-dec.app/api"

                val token = encDecApiSemaphore.withPermit {
                    retryTransient(1, 100L) {
                        safeGet("$apiBase/enc-hexa", headers = baseHeaders, timeout = 2L).parsedSafe<HexaEn>()
                    }
                }?.result?.token ?: return@withTimeoutOrNull

                val headers = baseHeaders + mapOf(
                    "X-Cap-Token" to token
                )

                val paths = if (season == null || (season == 0 && episode == null)) {
                    listOf("/api/tmdb/movie/$tmdbId/images")
                } else {
                    if (episode == null) return@withTimeoutOrNull
                    if (season == 0) {
                        listOf(
                            "/api/tmdb/tv/$tmdbId/season/$season/episode/$episode/images",
                            "/api/tmdb/movie/$tmdbId/images"
                        )
                    } else {
                        listOf("/api/tmdb/tv/$tmdbId/season/$season/episode/$episode/images")
                    }
                }

                val domainTargets = listOf(
                    hexaSU to "https://hexa.su/",
                    flixerSU to "https://flixer.su/",
                    embedSU to "https://embed.su/"
                )

                var encrypted = ""
                var chosenReferer = "https://hexa.su/"

                for (path in paths) {
                    for ((domain, referer) in domainTargets) {
                        val url = "$domain$path"
                        val targetHeaders = headers + mapOf(
                            "Referer" to referer,
                            "Origin" to referer.removeSuffix("/")
                        )
                        val response = suspendCancellable {
                            withTimeoutOrNull(1800L) {
                                safeGet(url, targetHeaders, timeout = 2L)
                            }
                        }
                        if (response != null && response.isSuccessful && response.code !in listOf(403, 404, 500, 502, 503, 521, 522) && response.text.isNotBlank()) {
                            encrypted = response.text
                            chosenReferer = referer
                            break
                        } else {
                            android.util.Log.d("StreamPlay", "HexaSU primary $domain unavailable (code: ${response?.code}), fast failover to next endpoint")
                        }
                    }
                    if (encrypted.isNotEmpty()) break
                }

                if (encrypted.isEmpty()) return@withTimeoutOrNull

                val jsonBody = JSONObject().put("text", encrypted).put("key", key).toString()
                    .toRequestBody("application/json".toMediaType())

                val decryptRes = encDecApiSemaphore.withPermit {
                    retryTransient(2, 400L) {
                        suspendCancellable {
                            app.post(
                                "$apiBase/dec-hexa",
                                headers = mapOf("Content-Type" to "application/json"),
                                requestBody = jsonBody,
                                timeout = 4L
                            ).parsedSafe<HexaResponse>()
                        }
                    }
                } ?: return@withTimeoutOrNull

                if (decryptRes.status != 200) return@withTimeoutOrNull

                // 1. Emit subtitles if available
                decryptRes.result?.tracks?.forEach { track ->
                    if (track.kind?.equals("thumbnails", ignoreCase = true) == true) return@forEach
                    val rawFile = track.file?.trim()
                    val rawLabel = track.label ?: "English"
                    val file = when {
                        rawFile.isNullOrBlank() -> null
                        rawFile.startsWith("http://", ignoreCase = true) || rawFile.startsWith("https://", ignoreCase = true) -> rawFile
                        rawFile.startsWith("//") -> "https:$rawFile"
                        else -> null
                    }
                    if (!file.isNullOrBlank()) {
                        val label = cleanSubtitleLabel(rawLabel)
                        subtitleCallback?.invoke(newSubtitleFile(label, file))
                    }
                }

                val sources = decryptRes.result?.sources ?: return@withTimeoutOrNull

                coroutineScope {
                    sources.map { src ->
                        async {
                            try {
                                val server = src.server ?: return@async
                                val link = src.url ?: return@async

                                if (link.isEmpty()) return@async

                                val name = server.replaceFirstChar {
                                    if (it.isLowerCase()) it.titlecase() else it.toString()
                                }

                                val linkHeaders = mapOf("Referer" to chosenReferer, "Origin" to chosenReferer.removeSuffix("/"))
                                val isDirectVideo = link.contains(".mp4", ignoreCase = true) || link.contains(".mkv", ignoreCase = true)
                                val streamType = if (isDirectVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8

                                val generated = if (!isDirectVideo) {
                                    withTimeoutOrNull(4500L) {
                                        runCatching {
                                            generateM3u8(
                                                "HexaSU $name",
                                                link,
                                                chosenReferer,
                                                headers = linkHeaders
                                            )
                                        }.getOrNull()
                                    }
                                } else null

                                if (!generated.isNullOrEmpty()) {
                                    emitTopTierDualQualityStreamLinks(
                                        source = "HexaSU",
                                        baseName = "HexaSU $name",
                                        url = link,
                                        referer = chosenReferer,
                                        headers = linkHeaders,
                                        streamType = streamType,
                                        generatedLinks = generated,
                                        callback = callback
                                    )
                                } else if (link.isNotBlank() && link.startsWith("http", ignoreCase = true)) {
                                    emitTopTierDualQualityStreamLinks(
                                        source = "HexaSU",
                                        baseName = "HexaSU $name",
                                        url = link,
                                        referer = chosenReferer,
                                        headers = linkHeaders,
                                        streamType = streamType,
                                        callback = callback
                                    )
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
            Log.w("StreamPlay", "invokeHexa failed: ${e.message}")
        }
    }

    suspend fun invokeHexa(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeHexa(tmdbId, season, episode, subtitleCallback = null, callback = callback)
    }

    suspend fun invokeAutoembed(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            if (tmdbId == null || (season != null && season != 0 && episode == null)) return

            withTimeoutOrNull(25000L) {
                val paths = if (season == null || (season == 0 && episode == null)) {
                    listOf("/embed/movie/$tmdbId", "/movie/$tmdbId")
                } else {
                    if (episode == null) return@withTimeoutOrNull
                    if (season == 0) {
                        listOf(
                            "/embed/tv/$tmdbId/$season/$episode",
                            "/tv/$tmdbId/$season/$episode",
                            "/embed/movie/$tmdbId",
                            "/movie/$tmdbId"
                        )
                    } else {
                        listOf("/embed/tv/$tmdbId/$season/$episode", "/tv/$tmdbId/$season/$episode")
                    }
                }

            val domainTargets = listOf(
                autoembedPlayer,
                autoembedDomain,
                "https://autoembed.to",
                "https://autoembed.co"
            )

            val baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5"
            )

            val streamRegex = Regex("""(?:"|')(https?:\\?/\\?/[^"'\s<>]+\.(?:m3u8|mp4)(?:[^"'\s<>]*)?)(?:"|')""", RegexOption.IGNORE_CASE)
            val playerJsFileRegex = Regex("""(?:file|source)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val subtitleRegex = Regex("""(?:subtitle|track|caption)\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

            fun extractQuality(text: String): Int {
                return when {
                    text.contains("2160", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                    text.contains("1440", ignoreCase = true) -> Qualities.P1440.value
                    text.contains("1080", ignoreCase = true) -> Qualities.P1080.value
                    text.contains("720", ignoreCase = true) -> Qualities.P720.value
                    text.contains("480", ignoreCase = true) -> Qualities.P480.value
                    text.contains("360", ignoreCase = true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
            }

            fun normalizeUrl(rawUrl: String, domain: String): String {
                val unescaped = rawUrl.replace("\\/", "/").trim().trim('"', '\'')
                return when {
                    unescaped.startsWith("//") -> "https:$unescaped"
                    unescaped.startsWith("/") -> "$domain$unescaped"
                    else -> unescaped
                }
            }

            suspend fun emitStreamLink(streamUrl: String, domain: String, refererUrl: String, qualityHint: Int? = null) {
                val cleanedUrl = normalizeUrl(streamUrl, domain)
                if (cleanedUrl.isBlank() || cleanedUrl.contains("favicon", ignoreCase = true)) return

                val isHls = cleanedUrl.contains(".m3u8", ignoreCase = true)
                val isDirectVideo = cleanedUrl.contains(".mp4", ignoreCase = true) || cleanedUrl.contains(".mkv", ignoreCase = true)
                val streamType = if (isHls) ExtractorLinkType.M3U8 else if (isDirectVideo) ExtractorLinkType.VIDEO else INFER_TYPE

                val linkHeaders = mapOf(
                    "Referer" to refererUrl,
                    "Origin" to domain
                )

                if (isHls) {
                    val m3u8Links = withTimeoutOrNull(12000L) {
                        runCatching {
                            generateM3u8(
                                "AutoEmbed",
                                cleanedUrl,
                                refererUrl,
                                headers = linkHeaders
                            )
                        }.getOrNull()
                    }

                    if (!m3u8Links.isNullOrEmpty()) {
                        emitTopTierDualQualityStreamLinks(
                            source = "AutoEmbed",
                            baseName = "AutoEmbed",
                            url = cleanedUrl,
                            referer = refererUrl,
                            headers = linkHeaders,
                            streamType = ExtractorLinkType.M3U8,
                            generatedLinks = m3u8Links,
                            callback = callback
                        )
                    } else if (cleanedUrl.startsWith("http", ignoreCase = true)) {
                        emitTopTierDualQualityStreamLinks(
                            source = "AutoEmbed",
                            baseName = "AutoEmbed",
                            url = cleanedUrl,
                            referer = refererUrl,
                            headers = linkHeaders,
                            streamType = ExtractorLinkType.M3U8,
                            callback = callback
                        )
                    }
                } else if (cleanedUrl.startsWith("http", ignoreCase = true)) {
                    emitTopTierDualQualityStreamLinks(
                        source = "AutoEmbed",
                        baseName = "AutoEmbed",
                        url = cleanedUrl,
                        referer = refererUrl,
                        headers = linkHeaders,
                        streamType = streamType,
                        callback = callback
                    )
                }
            }

            for (domain in domainTargets) {
                var foundOnDomain = false
                for (path in paths) {
                    val url = "$domain$path"
                    val headers = baseHeaders + mapOf("Referer" to "$domain/")
                    val response = suspendCancellable {
                        withTimeoutOrNull(5000L) {
                            safeGet(url, headers = headers, timeout = 5L)
                        }
                    }
                    if (response == null) {
                        // Unreachable or timed out on this host, failover immediately without wasting budget on more paths
                        break
                    }
                    if (response.code in listOf(403, 500, 502, 503, 521, 522)) {
                        // Host is blocking or down, failover immediately to next domain
                        break
                    }
                    if (!response.isSuccessful || response.text.isBlank()) continue

                    val pageText = response.text
                    val unescapedPage = pageText.replace("\\/", "/")
                    val doc = response.document

                    // 1. Parse subtitles from <track> elements
                    doc.select("track[src]").forEach { track ->
                        if (track.attr("kind").equals("thumbnails", ignoreCase = true)) return@forEach
                        val src = track.attr("src")
                        val rawLabel = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "English" }
                        if (src.isNotBlank()) {
                            val subUrl = normalizeUrl(src, domain)
                            val label = cleanSubtitleLabel(rawLabel)
                            subtitleCallback(newSubtitleFile(label, subUrl))
                        }
                    }

                    // 2. Parse subtitle string configs (e.g. subtitle: "[English]https://...")
                    subtitleRegex.findAll(unescapedPage).forEach { match ->
                        val subVal = match.groupValues[1]
                        if (subVal.contains("http") || subVal.contains(".vtt") || subVal.contains(".srt")) {
                            val subParts = subVal.split(",")
                            for (part in subParts) {
                                val rawLang = if (part.startsWith("[")) part.substringAfter("[").substringBefore("]") else "English"
                                val subUrl = normalizeUrl(if (part.contains("]")) part.substringAfter("]") else part, domain)
                                if (subUrl.startsWith("http") && !rawLang.equals("thumbnails", ignoreCase = true) && !subUrl.contains("thumbnails", ignoreCase = true)) {
                                    val lang = cleanSubtitleLabel(rawLang)
                                    subtitleCallback(newSubtitleFile(lang, subUrl))
                                }
                            }
                        }
                    }

                    // 3. Direct stream / playlist URL in HTML or script blocks
                    streamRegex.findAll(pageText).forEach { match ->
                        val rawStream = match.groupValues[1]
                        emitStreamLink(rawStream, domain, "$domain/")
                        foundOnDomain = true
                    }

                    // 4. Playerjs style configs (e.g. file: "[1080p]https://...,[720p]https://...")
                    playerJsFileRegex.findAll(unescapedPage).forEach { match ->
                        val fileContent = match.groupValues[1]
                        if (fileContent.contains("[")) {
                            val fileParts = fileContent.split(",")
                            for (part in fileParts) {
                                val qTag = part.substringAfter("[").substringBefore("]")
                                val partUrl = part.substringAfter("]")
                                val q = extractQuality(qTag)
                                emitStreamLink(partUrl, domain, "$domain/", qualityHint = q)
                                foundOnDomain = true
                            }
                        } else if (fileContent.startsWith("http") || fileContent.contains(".m3u8") || fileContent.contains(".mp4")) {
                            emitStreamLink(fileContent, domain, "$domain/")
                            foundOnDomain = true
                        }
                    }

                    // 5. Unpack obfuscated / packed scripts (eval(function(p,a,c,k,e,d)...))
                    val packedScripts = doc.select("script")
                        .map { it.data() }
                        .filter { it.contains("function(p,a,c,k,e,d)") }

                    for (packed in packedScripts) {
                        val unpacked = runCatching { getAndUnpack(packed) }.getOrNull() ?: continue
                        val unescapedUnpacked = unpacked.replace("\\/", "/")
                        streamRegex.findAll(unpacked).forEach { match ->
                            emitStreamLink(match.groupValues[1], domain, "$domain/")
                            foundOnDomain = true
                        }
                        playerJsFileRegex.findAll(unescapedUnpacked).forEach { match ->
                            emitStreamLink(match.groupValues[1], domain, "$domain/")
                            foundOnDomain = true
                        }
                    }

                    // 6. Follow embedded iframes and server buttons/links
                    val iframeSources = mutableListOf<String>()
                    doc.select("iframe[src], iframe[data-src]").forEach {
                        val s = it.attr("src").ifBlank { it.attr("data-src") }
                        if (s.isNotBlank()) iframeSources.add(normalizeUrl(s, domain))
                    }
                    doc.select("div.server[data-url], button[data-src], button[data-url], a.server[href]").forEach {
                        val s = it.attr("data-url").ifBlank { it.attr("data-src") }.ifBlank { it.attr("href") }
                        if (s.isNotBlank() && !s.startsWith("#") && !s.startsWith("javascript:")) {
                            iframeSources.add(normalizeUrl(s, domain))
                        }
                    }

                    for (iframeSrc in iframeSources.distinct()) {
                        val resolvedByExtractor = runCatching {
                            loadExtractor(iframeSrc, "$domain/", subtitleCallback = subtitleCallback) { link ->
                                @Suppress("DEPRECATION")
                                val taggedLink = if (!link.name.contains("AutoEmbed", ignoreCase = true) && !link.source.contains("AutoEmbed", ignoreCase = true)) {
                                    ExtractorLink(
                                        source = "AutoEmbed",
                                        name = "AutoEmbed [${link.name}]",
                                        url = link.url,
                                        referer = link.referer.ifBlank { "$domain/" },
                                        quality = link.quality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                } else {
                                    link
                                }
                                emitTopTierDualQualityStreamLinks(
                                    source = "AutoEmbed",
                                    baseName = taggedLink.name,
                                    url = taggedLink.url,
                                    referer = taggedLink.referer,
                                    headers = taggedLink.headers,
                                    streamType = taggedLink.type,
                                    generatedLinks = listOf(taggedLink),
                                    callback = callback
                                )
                            }
                        }.getOrDefault(false)

                        if (!resolvedByExtractor) {
                            val iframeResp = suspendCancellable { safeGet(iframeSrc, headers = mapOf("Referer" to "$domain/"), timeout = 3L) }
                            if (iframeResp != null && iframeResp.isSuccessful && iframeResp.text.isNotBlank()) {
                                val iframeText = iframeResp.text
                                streamRegex.findAll(iframeText).forEach { match ->
                                    emitStreamLink(match.groupValues[1], domain, iframeSrc)
                                    foundOnDomain = true
                                }
                            }
                        } else {
                            foundOnDomain = true
                        }
                    }

                    if (foundOnDomain) break
                }
                if (foundOnDomain) break
            }
        }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeAutoembed failed: ${e.message}")
        }
    }

    suspend fun invokeAutoembed(
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        invokeAutoembed(tmdbId, season, episode, subtitleCallback = {}, callback = callback)
    }

    suspend fun invokeHindmoviez(
        id: String?,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val api = getDomains()?.hindmoviez ?: return
        if (id.isNullOrBlank()) return
        val url = "$api/?s=$id"
        var response = safeGet(url, timeout = 5L)
        if (response.text.contains("Just a moment", true)) {
            response = webMutex.withLock {
                safeGet(
                    url,
                    timeout = 5L,
                    interceptor = cloudflareKiller
                )
            }
        }

        val searchDoc = response.document
        val entries = searchDoc.select("h2.entry-title > a")

        entries.safeAmap { entry ->
            val pageDoc = safeGet(entry.attr("href"), timeout = 5L).document
            val buttons = pageDoc.select("a.maxbutton")

            if (episode == null) {
                // Movie
                buttons.safeAmap { btn ->
                    val intermediateDoc = safeGet(btn.attr("href"), timeout = 5L).document
                    val link = intermediateDoc.selectFirst("a.get-link-btn")
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { href ->
                            val baseurl=href.substringBefore("/?id=")
                            val rawId = href.substringAfter("id=")
                            hindmoviezsignHShare(rawId, baseurl)
                        }
                        ?: return@safeAmap
                    Log.d("Phisher 1",link)
                    getHindMoviezLinks("Hindmoviez", link, subtitleCallback, callback)
                }
            } else {
                // TV Episode
                buttons.safeAmap { btn ->
                    val headerText = btn.parent()
                        ?.parent()
                        ?.previousElementSibling()
                        ?.text()
                        .orEmpty()

                    if (!headerText.contains("Season $season", ignoreCase = true)) return@safeAmap

                    val episodeDoc = safeGet(btn.attr("href"), timeout = 5L).document
                    val episodeLink = episodeDoc
                        .select("h3 > a")
                        .getOrNull(episode - 1)
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { href ->
                            val baseurl = href.substringBefore("/?id=")
                            val rawId = href.substringAfter("id=")
                            hindmoviezsignHShare(rawId, baseurl)
                        }
                        ?: return@safeAmap

                    getHindMoviezLinks("Hindmoviez", episodeLink, subtitleCallback, callback)
                }
            }
        }
    }

    suspend fun invokeMovies4u(
        id: String? = null,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val searchQuery = "$title $year".trim()
        val movies4uAPI = getDomains()?.movies4u ?: return
        val searchUrl = "$movies4uAPI/?s=$searchQuery"

        val searchDoc = safeGet(searchUrl).document
        val links = searchDoc.select("article h2 a,article h3 a")

        links.safeAmap { element ->
            val postUrl = element.attr("href")
            val postDoc = safeGet(postUrl).document
            val imdbId = postDoc.select("p a:contains(IMDb Rating)").attr("href")
                .substringAfter("title/").substringBefore("/")

            if (imdbId != id.toString()) {
                return@safeAmap
            }

            if (season == null) {
                val innerUrl = postDoc.select("div.download-links-div a.btn").attr("href")
                val innerDoc = safeGet(innerUrl).document
                val sourceButtons = innerDoc.select("div.downloads-btns-div a.btn")
                sourceButtons.safeAmap { sourceButton ->
                    val sourceLink = sourceButton.attr("href")
                    loadSourceNameExtractor(
                        "Movies4u",
                        sourceLink,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            } else {
                val seasonBlocks = postDoc.select("div.downloads-btns-div")
                seasonBlocks.safeAmap { block ->
                    val headerText = block.previousElementSibling()?.text().orEmpty()
                    if (headerText.contains("Season $season", ignoreCase = true)) {
                        val seasonLink = block.select("a.btn").firstOrNull { !it.text().contains("zip", true) }?.attr("href") ?: return@safeAmap

                        val episodeDoc = safeGet(seasonLink).document
                        val episodeBlocks = episodeDoc.select("div.downloads-btns-div")

                        if (episode != null && episode in 1..episodeBlocks.size) {
                            val episodeBlock = episodeBlocks[episode - 1]
                            val episodeLinks = episodeBlock.select("a.btn")

                            episodeLinks.safeAmap { epLink ->
                                val sourceLink = epLink.attr("href")
                                loadSourceNameExtractor(
                                    "Movies4u",
                                    sourceLink,
                                    "",
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun invokeM4uhd(
        title: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val m4uhdAPI = getDomains()?.m4ufree ?: return

        try {
            val safeTitle = title?.fixTitle() ?: return

            val res = safeGet(
                "$m4uhdAPI/search/$safeTitle",
                timeout = 5L
            ).document

            val scriptData = res.select("div.item").mapNotNull { element ->
                val href = element.selectFirst("a")?.attr("href")
                if (href.isNullOrBlank()) return@mapNotNull null

                Triple(
                    element.selectFirst("img.imagecover")?.attr("alt"),
                    element.selectFirst(".jt-info")?.text(),
                    href
                )
            }

            if (scriptData.isEmpty()) {
                Log.e("M4uhd", "No search results")
                return
            }

            val cleanTitle = title
                .lowercase()
                .replace(Regex("[^a-z0-9]"), "")

            val script = scriptData.firstOrNull { triple ->

                val siteTitle = triple.first
                    ?.lowercase()
                    ?.replace(Regex("[^a-z0-9]"), "")
                    ?: return@firstOrNull false

                siteTitle.contains(cleanTitle)

            } ?: scriptData.firstOrNull()

            val scriptUrl = script?.third
            if (scriptUrl.isNullOrBlank()) {
                Log.e("M4uhd", "Script URL null")
                return
            }

            val link = fixUrl(scriptUrl, m4uhdAPI)

            val request = safeGet(link)
            val doc = request.document

            // direct iframe
            val directIframe = doc
                .selectFirst("#myplayer iframe")
                ?.attr("src")

            if (!directIframe.isNullOrBlank()) {
                loadExtractor(
                    fixUrl(directIframe, link),
                    m4uhdAPI,
                    subtitleCallback,
                    callback
                )
                return
            }

            val cookies = request.cookies

            val token = doc
                .selectFirst("meta[name=csrf-token]")
                ?.attr("content")

            if (token.isNullOrBlank()) {
                Log.e("M4uhd", "Token missing")
                return
            }

            val m4uData = if (season == null) {

                doc.selectFirst("span.singlemv.active, span#fem")
                    ?.attr("data")

            } else {

                val epCode = "S%02d-E%02d".format(season, episode ?: return)

                val episodeBtn = doc.select("button.episode")
                    .firstOrNull {
                        it.text().trim().equals(epCode, true)
                    } ?: return

                val idepisode = episodeBtn.attr("idepisode")

                if (idepisode.isBlank()) return

                val embed = app.post(
                    "$m4uhdAPI/ajaxtv",
                    data = mapOf(
                        "idepisode" to idepisode,
                        "_token" to token
                    ),
                    referer = link,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    cookies = cookies
                ).document

                embed.selectFirst("span.singlemv.active, span#fem")
                    ?.attr("data")
            }

            if (m4uData.isNullOrBlank()) {
                Log.e("M4uhd", "m4uData missing")
                return
            }

            val iframe = app.post(
                "$m4uhdAPI/ajax",
                data = mapOf(
                    "m4u" to m4uData,
                    "_token" to token
                ),
                referer = link,
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                ),
                cookies = cookies
            ).document
                .selectFirst("iframe")
                ?.attr("src")

            if (iframe.isNullOrBlank()) {
                Log.e("M4uhd", "iframe missing")
                return
            }

            loadSourceNameExtractor(
                "M4uhd",
                fixUrl(iframe, link),
                m4uhdAPI,
                subtitleCallback,
                callback
            )

        } catch (_: Exception) {
            Log.e("M4uhd", "invokeM4uhd crash")
        }
    }

    suspend fun invokeCineVood(
        imdbId: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val baseUrl = getDomains()?.cinevood ?: return
        if (imdbId == null) return

        val searchRes = safeGet("$baseUrl/?s=$imdbId")

        val searchDoc = if (searchRes.text.contains("Just a moment", true)) {
            webMutex.withLock {
                safeGet("$baseUrl/?s=$imdbId", interceptor = cloudflareKiller)
            }.document
        } else {
            searchRes.document
        }

        searchDoc.select("article a[href]")
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
            .distinct()
            .safeAmap { postUrl ->

                val postRes = safeGet(postUrl)
                val postDoc = if (postRes.text.contains("Just a moment", true)) {
                    webMutex.withLock {
                        safeGet(postUrl, interceptor = cloudflareKiller)
                    }.document
                } else {
                    postRes.document
                }

                postDoc.select("a.maxbutton[href]")
                    .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
                    .safeAmap { link ->
                        loadSourceNameExtractor("CineVood", link, "", subtitleCallback, callback)
                    }
            }
    }


    suspend fun invokeFilmyfiy(
        title: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {

        fun cleanTitle(input: String): String {
            return input
                .lowercase()
                .replace(Regex("\\(.*?\\)"), "") // remove (2012), (Hindi...)
                .replace(Regex("[^a-z0-9 ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
        val baseUrl = getDomains()?.filmyfiy ?: return
        val rawQuery = title?.trim() ?: return
        val query = rawQuery.lowercase().replace(Regex("[^a-z0-9]"), "")
        val searchDoc = safeGet(
            "$baseUrl/site-1.html?to-search=${
                withContext(Dispatchers.IO) {
                    URLEncoder.encode(
                        rawQuery,
                        "UTF-8"
                    )
                }
            }"
        ).document

        val queryClean = cleanTitle(rawQuery)

        searchDoc.select("div.A2 > a:nth-child(2)[href]").mapNotNull { a ->
            val href = a.attr("href").takeIf(String::isNotBlank)
                ?.let { if (it.startsWith("http")) it else baseUrl + it }
                ?: return@mapNotNull null

            val titleText = cleanTitle(a.text())

            val isMatch = titleText == queryClean ||
                    titleText.startsWith("$queryClean ") ||
                    titleText.contains(" $queryClean ")

            if (isMatch) href else null
        }.distinct().safeAmap { postUrl ->
            val postDoc = safeGet(postUrl).document
            postDoc.select("div.dlbtn a[href]")
                .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }.distinct()
                .safeAmap { dlBtnUrl ->
                    val dlDoc = safeGet(dlBtnUrl).document
                    dlDoc.select("div.dlink a[href]")
                        .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }.distinct()
                        .safeAmap { finalUrl ->
                            loadExtractor(finalUrl, baseUrl, subtitleCallback, callback)
                        }
                }
        }
    }

    suspend fun invokeDooflix(
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseApi = "https://panel.watchkaroabhi.com"
        val apiKey = "qNhKLJiZVyoKdi9NCQGz8CIGrpUijujE"

        if (tmdbId == null) return

        val requestUrl = if (season == null) {
            "$baseApi/api/3/movie/$tmdbId/links?api_key=$apiKey"
        } else {
            "$baseApi/api/3/tv/$tmdbId/season/$season/episode/$episode/links?api_key=$apiKey"
        }

        val headers = mapOf(
            base64Decode("WC1QYWNrYWdlLU5hbWU=") to base64Decode("Y29tLmtpbmcubW9qYQ=="),
            base64Decode("VXNlci1BZ2VudA==") to base64Decode("ZG9vZmxpeA=="),
            base64Decode("WC1BcHAtVmVyc2lvbg==") to base64Decode("MzA1")
        )

        val response = safeGet(requestUrl, headers).parsedSafe<Dooflix>() ?: return
        val links = response.links

        links.safeAmap { link ->
            val streamurl = safeGet(
                link.url,
                referer = "https://molop.art/",
                allowRedirects = false
            ).headers["location"] ?: return@safeAmap
            callback.invoke(
                newExtractorLink(
                    "Dooflix",
                    link.host,
                    streamurl
                )
                {
                    this.referer = "https://molop.art/"
                    val detectedQ = StreamLinkOptimizer.extractQualityFromText(streamurl)
                    this.quality = if (detectedQ > 0 && detectedQ != Qualities.Unknown.value) detectedQ else Qualities.Unknown.value
                }
            )
        }
    }

    private fun normalizeTitleFast(title: String?): String {
        if (title == null) return ""
        val sb = StringBuilder(title.length)
        for (c in title.lowercase()) {
            if (c.isLetterOrDigit() || c == ' ') sb.append(c)
        }
        return sb.toString().trim()
    }

    suspend fun invokeXpass(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseRef = "$xpassAPI/"
        val embedUrl = if (season == null) {
            "$xpassAPI/e/movie/$tmdbId"
        } else {
            "$xpassAPI/e/tv/$tmdbId/$season/$episode"
        }

        val html = app.get(embedUrl, referer = baseRef).text
        val backups = extractXpassBackups(html)

        Log.d("Xpass", "backups: $backups")

        backups.safeAmap { (name, url) ->
            val fullUrl = if (url.startsWith("http")) url else xpassAPI + url
            Log.d("Xpass", "fullUrl: $fullUrl")

            val json = runCatching { app.get(fullUrl).text }.getOrNull() ?: return@safeAmap

            val sources = runCatching {
                JSONObject(json)
                    .optJSONArray("playlist")
                    ?.optJSONObject(0)
                    ?.optJSONArray("sources")
            }.getOrNull() ?: return@safeAmap

            val sourceCount = sources.length()

            for (i in 0 until sourceCount) {
                val source = sources.optJSONObject(i) ?: continue

                val file = source.optString("file").takeIf {
                    it.isNotBlank() && it.startsWith("http")
                } ?: continue

                val type = source.optString("type")
                val isM3u8 = type.contains("hls", true) || file.contains(".m3u8")

                if (isM3u8) {
                    generateM3u8(
                        "Xpass [$name]",
                        file,
                        baseRef
                    ).forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            "Xpass [$name]",
                            "Xpass [$name]",
                            file
                        ) {
                            this.referer = baseRef
                        }
                    )
                }
            }
        }
    }

    suspend fun invokeDudefilms(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val dudefilmsAPI = getDomains()?.dudefilms ?: return
        if(imdbId == null) return
        val urls = app.get("$dudefilmsAPI/?s=$imdbId").document.select("a.simple-grid-grid-post-thumbnail-link")

        urls.safeAmap { it ->
            val url = it.attr("href")
            val doc = app.get(url).document

            if(season == null && episode == null) {
                doc.select("a.maxbutton").safeAmap { link ->
                    val href = link.attr("href")
                    val document = app.get(href).document
                    document.select("a.maxbutton").safeAmap { source ->
                        loadSourceNameExtractor("Dudefilms", source.attr("href"), "", subtitleCallback, callback)
                    }
                }
            } else {
                val matchingH4Tags = doc.select("h4").filter {
                    Regex("""Season\s*0*$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
                }

                if(matchingH4Tags.isEmpty()) return@safeAmap

                matchingH4Tags.safeAmap { h4Tag ->
                    var currentSibling = h4Tag.nextElementSibling()
                    while (currentSibling != null) {
                        val tagName = currentSibling.tagName()

                        if(tagName != "p") return@safeAmap

                        currentSibling.select("a").safeAmap{ aTag ->
                            val source = aTag.attr("href")
                            Log.d("Dudefilms", "source: $source")
                            val epSource = app.get(source).document
                                .select("a.maxbutton")
                                .find { Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() == episode }
                                ?.attr("href") ?: return@safeAmap

                            loadSourceNameExtractor("Dudefilms", epSource, "", subtitleCallback, callback)
                        }

                        currentSibling = currentSibling.nextElementSibling()

                    }
                }
            }
        }
    }

    suspend fun invokeZinkmovies(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val domain = getDomains()?.zinkmovies ?: return
        val searchDoc = app.get("$domain/?s=${title} $year").document
        val typeSpan = if (season != null) "span.tvshows" else "span.movies"

        val matchUrls = searchDoc.select("div.result-item article")
            .filter { article ->
                article.selectFirst(typeSpan) != null &&
                        article.selectFirst("div.title a")
                            ?.text()
                            ?.replace(":", "")
                            ?.replace("-", " ")
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()
                            ?.contains(
                                title
                                    ?.replace(":", "")
                                    ?.replace("-", " ")
                                    ?.replace(Regex("\\s+"), " ")
                                    ?.trim() ?: "",
                                ignoreCase = true
                            ) == true &&
                        (
                                year == null ||
                                        article.selectFirst("span.year")
                                            ?.text()
                                            ?.trim() == year.toString()
                                )
            }
            .mapNotNull {
                it.selectFirst("div.title a")?.attr("href")
            }.distinct()

        if (matchUrls.isEmpty()) return

        matchUrls.safeAmap { matchUrl ->
            val detailDoc = app.get(matchUrl).document
            val content = detailDoc.selectFirst("div.wp-content") ?: return@safeAmap

            if (season != null && episode != null) {
                extractSeasonLinks(content, season).safeAmap { seasonBtnUrl ->

                    val episodeDoc = app.get(seasonBtnUrl).document
                    val episodeUrl = episodeDoc.select("a.maxbutton-download-now")
                        .firstOrNull { a ->
                            Regex("""EPISODE\s*-\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                                .find(a.text())?.groupValues?.get(1)?.toIntOrNull() == episode
                        }?.attr("href") ?: return@safeAmap

                    getZinkLinks(episodeUrl, subtitleCallback, callback)
                }
            } else {
                content.select("div.movie-button-container a.movie-simple-button")
                    .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
                    .safeAmap {
                        getZinkLinks(it, subtitleCallback, callback)
                    }
            }
        }
    }


    fun extractSeasonLinks(content: Element, season: Int): List<String> {
        val links = mutableListOf<String>()
        var inTargetSeason = false
        content.children().forEach { child ->
            when {
                child.hasClass("lgtagmessage") -> {
                    inTargetSeason = Regex("""Season\s+0*$season\b""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(child.text())
                }
                child.hasClass("movie-button-container") && inTargetSeason -> {
                    child.selectFirst("a.movie-simple-button")
                        ?.attr("href")
                        ?.takeIf(String::isNotBlank)
                        ?.let { links.add(it) }
                }
            }
        }
        return links
    }

    suspend fun generateZinkLinks(url: String): List<ZinkLink> {
        return runCatching {

            val firstDoc = app.get(url).document
            val title = firstDoc.select("h1.file-title").text()
            val firstHtml = firstDoc.html()

            val randomId = Regex("""generateDownloadLink\(['"]([^'"]+)""")
                .find(firstHtml)
                ?.groupValues
                ?.getOrNull(1)
                ?: return emptyList()

            val ajaxEndpoint = Regex("""https://[^"'\\s]+ajax_generate_token\.php""")
                .find(firstHtml)
                ?.value
                ?: return emptyList()

            val downloadBase = Regex("""https://[^"'\\s]+/dl/""")
                .find(firstHtml)
                ?.value
                ?: return emptyList()

            val token = retry  { app.post(
                url = "$ajaxEndpoint?random_id=$randomId",
                data = mapOf(
                    "random_id" to randomId
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).parsedSafe<ZinkTokenResponse>()
                ?.token

            } ?: return emptyList()

            val generatedUrl = downloadBase + token

            val generatedDoc = app.get(generatedUrl).document

            val results = generatedDoc
                .select("#mirror-buttons a[href]")
                .mapNotNull { element ->

                    val href = element.attr("href").trim()

                    if (href.isBlank()) return@mapNotNull null

                    ZinkLink(
                        name = element.text()
                            .replace("Generate", "", true)
                            .trim(),
                        url = href,
                        title = title,
                    )
                }
                .toMutableList()

            generatedDoc.selectFirst("#worker-btn")?.let { btn: Element ->

                val workerId = Regex("""handleServerRequest\(['"]worker['"]\s*,\s*['"]([^'"]+)""")
                    .find(btn.attr("onclick"))
                    ?.groupValues
                    ?.getOrNull(1)

                val serverHandler = Regex("""SERVER_HANDLER_URL\s*=\s*["']([^"']+)""")
                    .find(generatedDoc.html())
                    ?.groupValues
                    ?.getOrNull(1)

                if (
                    !workerId.isNullOrBlank() &&
                    !serverHandler.isNullOrBlank()
                ) {

                    runCatching {

                        val workerJson = JSONObject(
                            app.post(
                                url = serverHandler,
                                requestBody = """
                                {
                                    "server":"worker",
                                    "random_id":"$workerId"
                                }
                            """.trimIndent().toRequestBody(),
                                headers = mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Content-Type" to "application/json",
                                    "Origin" to generatedUrl.substringBefore("/dl/"),
                                    "Referer" to generatedUrl
                                )
                            ).text
                        )

                        workerJson.optString("url")
                            .ifBlank {
                                workerJson.optString("download")
                            }
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                results += ZinkLink(
                                    name = "WORKER",
                                    url = it,
                                    title = title,
                                )
                            }

                    }
                }
            }

            results.distinctBy { it.url }

        }.getOrElse {
            emptyList()
        }
    }


    suspend fun getZinkLinks(
        source: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        generateZinkLinks(source).safeAmap { link ->

            val simplifiedTitle = cleanTitle(link.title)

            if (link.name.contains("worker", true)) {
                callback(
                    newExtractorLink(
                        source = "Zinkmovies Worker",
                        name = "Zinkmovies Worker $simplifiedTitle",
                        url = link.url
                    ) {
                        this.quality = getIndexQuality(link.title)
                    }
                )
            } else {
                loadSourceNameExtractor(
                    "Zinkmovies",
                    link.url,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    suspend fun invokePeachify(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {

        data class ProxyData(
            val url: String,
            val headers: Map<String, String>
        )

        val requestHeaders = mapOf(
            "Accept"          to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Origin"          to peachifyAPI,
            "Referer"         to "$peachifyAPI/",
            "Sec-Fetch-Dest"  to "empty",
            "Sec-Fetch-Mode"  to "cors",
            "Sec-Fetch-Site"  to "cross-site",
            "User-Agent"      to "Mozilla/5.0 (X11; Linux x86_64; rv:139.0) Gecko/20100101 Firefox/139.0"
        )

        val servers = listOf(
            "https://usa.eat-peach.sbs/holly",
            "https://usa.eat-peach.sbs/multi",
            "https://usa.eat-peach.sbs/ice",
            "https://usa.eat-peach.sbs/air",
            "https://usa.eat-peach.sbs/net",
            "https://uwu.peachify.top/moviebox"
        )

        servers.safeAmap { server ->

            val apiUrl = if (season == null) {
                "$server/movie/$tmdbId"
            } else {
                "$server/tv/$tmdbId/$season/$episode"
            }

            val text = app.get(
                apiUrl,
                headers = requestHeaders
            ).text

            val encrypt = JSONObject(text)
                .optString("data")
                .ifEmpty { return@safeAmap }

            val decrypted = peachifyDecrypt(encrypt)
                ?: return@safeAmap

            val json = JSONObject(decrypted)

            val provider = json.optString(
                "providerName",
                "Peachify"
            )

            val sources = json.optJSONArray("sources")
                ?: return@safeAmap

            for (i in 0 until sources.length()) {

                val src = sources.getJSONObject(i)

                val rawUrl = src
                    .optString("url")
                    .ifEmpty { continue }

                val dub = src.optString("dub", "")

                val srcType = src.optString(
                    "type",
                    "hls"
                )

                val quality = src.optInt(
                    "quality",
                    0
                )

                val srcHeaders = src.optJSONObject("headers")

                val isProxy =
                    rawUrl.contains("/m3u8-proxy") ||
                            rawUrl.contains("/mp4-proxy")

                val proxyData = if (isProxy) {

                    val query = rawUrl
                        .substringAfter("?", "")
                        .split("&")
                        .mapNotNull { param ->

                            val parts = param.split("=", limit = 2)

                            if (parts.size != 2) {
                                return@mapNotNull null
                            }

                            val key = runCatching {
                                URLDecoder.decode(parts[0], "UTF-8")
                            }.getOrNull() ?: return@mapNotNull null

                            val value = runCatching {
                                URLDecoder.decode(parts[1], "UTF-8")
                            }.getOrNull() ?: return@mapNotNull null

                            key to value
                        }
                        .toMap()

                    val finalUrl = query["url"] ?: rawUrl

                    val proxyHeaders = query["headers"]
                        ?.let { headersJson ->

                            runCatching {
                                tryParseJson<Map<String, String>>(headersJson)
                            }.getOrNull()

                        }
                        ?: emptyMap()

                    ProxyData(
                        url = finalUrl,
                        headers = proxyHeaders
                    )

                } else {

                    ProxyData(
                        url = rawUrl,
                        headers = buildMap {

                            srcHeaders?.keys()?.forEach { key ->

                                put(
                                    key,
                                    srcHeaders.optString(key)
                                )
                            }
                        }
                    )
                }

                val finalUrl = proxyData.url
                val proxyHeaders = proxyData.headers

                val finalReferer =
                    proxyHeaders["referer"]
                        ?: srcHeaders?.optString("referer")
                        ?: "$peachifyAPI/"

                val finalOrigin =
                    proxyHeaders["origin"]
                        ?: srcHeaders?.optString("origin")
                        ?: peachifyAPI

                val finalUA =
                    proxyHeaders["user-agent"]
                        ?: srcHeaders?.optString("user-agent")
                        ?: USER_AGENT

                val name = buildString {

                    append(
                        "Peachify [${provider.capitalize()}]"
                    )

                    if (dub.isNotEmpty()) {
                        append(" • $dub")
                    }
                }

                val type = if (srcType == "hls") {
                    ExtractorLinkType.M3U8
                } else {
                    INFER_TYPE
                }

                callback.invoke(
                    newExtractorLink(
                        source = "Peachify",
                        name = name,
                        url = finalUrl,
                        type = type
                    ) {

                        this.headers = mapOf(
                            "Origin"     to finalOrigin,
                            "Referer"    to finalReferer,
                            "User-Agent" to finalUA
                        )

                        this.quality = quality
                    }
                )
            }
        }
    }
    suspend fun invokeAnikage(
        anilistId: Int?,
        query: String?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        dubtype: String?,
    ) {
        if (query.isNullOrBlank()) return

        val searchUrl = "https://anikage.cc/api/media/anime/advanced-search?per_page=25&page=1&query=${query.replace(" ", "%20")}"
        val searchRes = tryParseJson<AnikageSearchResponse>(app.get(searchUrl).text) ?: return
        
        val results = searchRes.results ?: return
        var slug: String? = null
        for ((i, element) in results.withIndex()) {
            val res = element
            val id = res.id ?: res.anilistId
            if (anilistId != null && id == anilistId) {
                slug = res.slug
                break
            }
            if (anilistId == null && i == 0) {
                slug = res.slug
            }
        }
        
        if (slug == null) return

        val episodesUrl = "https://anikage.cc/api/media/anime/$slug/episodes"
        val episodesRaw = app.get(episodesUrl).text
        val episodesList = tryParseJson<AnikageEpisodesResponse>(episodesRaw)?.episodes 
                           ?: tryParseJson<List<AnikageEpisode>>(episodesRaw) 
                           ?: return
        
        var episodeNumberFound = false
        for (i in episodesList.indices) {
            val epObj = episodesList[i]
            val epNum = epObj.number ?: epObj.episode
            if (epNum == episode) {
                episodeNumberFound = true
                break
            }
        }
        
        if (!episodeNumberFound) return

        val lang = if (dubtype == "DUB") "dub" else "sub"
        val serverUrl = "https://anikage.cc/api/media/anime/$slug/episodes/$episode/servers?lang=$lang"
        val serverRes = tryParseJson<AnikageServersResponse>(app.get(serverUrl).text)
        val serversArr = serverRes?.servers
        
        val providerIds = mutableListOf<String>()
        if (serversArr != null) {
            for (i in serversArr.indices) {
                val sId = serversArr[i].id
                if (!sId.isNullOrBlank()) providerIds.add(sId)
            }
        }
        if (providerIds.isEmpty()) {
            providerIds.addAll(listOf("megg", "miko", "anya", "verse", "neko"))
        }

        coroutineScope {
            providerIds.map { provider ->
                async {
                    val sourceUrl = "https://anikage.cc/api/media/anime/$slug/episodes/$episode/sources?lang=$lang&provider=$provider"
                    val serverData = tryParseJson<AnikageSourcesResponse>(app.get(sourceUrl).text) ?: return@async
                    
                    val subtitles = serverData.subtitles
                    if (subtitles != null) {
                        for (i in subtitles.indices) {
                            val subObj = subtitles[i]
                            val label = subObj.label ?: lang
                            val file = subObj.file
                            if (file.isNullOrBlank()) continue
                            subtitleCallback(newSubtitleFile(label, "https://prox.anikage.cc/vtt/$file"))
                        }
                    }

                    val sources = serverData.sources
                    if (sources != null) {
                        val subTypeStr = if (lang == "sub") {
                            if (!subtitles.isNullOrEmpty()) "Softsub" else "Hardsub"
                        } else ""
                        val baseNameStr = "Anikage $subTypeStr".trim()

                        for (i in sources.indices) {
                            val srcObj = sources[i]
                            val isM3u8 = srcObj.isM3U8 ?: false
                            val srcUrl = srcObj.url
                            if (srcUrl.isNullOrBlank()) continue
                            
                            val qualityStr = srcObj.quality?.takeIf { it.isNotBlank() }
                            val typeStr = srcObj.type?.takeIf { it.isNotBlank() }

                            val videoUrl = "https://prox.anikage.cc/${if(isM3u8) "m3u8" else "stream"}/$srcUrl"
                            val nameStr = "$baseNameStr ${qualityStr?.replaceFirstChar { it.uppercase() } ?: typeStr?.replaceFirstChar { it.uppercase() } ?: ""}".trim()

                            callback(
                                newExtractorLink(
                                    "Anikage",
                                    nameStr,
                                    videoUrl,
                                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = getQualityFromName(qualityStr)
                                    this.referer = "https://anikage.cc/"
                                    this.headers = mapOf("Origin" to "https://anikage.cc/")
                                }
                            )
                        }
                    }

                    val embeds = serverData.embeds
                    if (embeds != null) {
                        for (i in embeds.indices) {
                            val embedObj = embeds[i]
                            val embedUrl = embedObj.url
                            if (embedUrl.isNullOrBlank()) continue
                            loadExtractor(embedUrl, "https://anikage.cc/", subtitleCallback, callback)
                        }
                    }
                }
            }.forEach { it.await() }
        }
    }

}
