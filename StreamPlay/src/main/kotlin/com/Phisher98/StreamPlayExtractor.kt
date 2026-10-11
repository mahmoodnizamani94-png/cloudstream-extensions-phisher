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

    private val random = SecureRandom()

    fun generateDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    val deviceId = generateDeviceId()

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
                val now = System.currentTimeMillis()
                val cached = vidlinkEncCache[effectiveTmdbId]
                var encData = if (cached != null && (now - cached.second) < 45_000L) {
                    cached.first
                } else null

                if (encData.isNullOrBlank()) {
                    val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$effectiveTmdbId"
                    val fetchBlock: suspend () -> String? = {
                        retryTransient(4, 350L) {
                            val resp = suspendCancellable {
                                runCatching {
                                    app.get(
                                        encUrl,
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Accept" to "application/json"
                                        ),
                                        timeout = 10L
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
                    }
                    val fetched = withTimeoutOrNull(3500L) {
                        encDecApiSemaphore.withPermit { fetchBlock() }
                    } ?: fetchBlock()

                    if (!fetched.isNullOrBlank()) {
                        vidlinkEncCache[effectiveTmdbId] = Pair(fetched, System.currentTimeMillis())
                        encData = fetched
                    }
                }

                if (encData.isNullOrBlank()) return@withTimeoutOrNull

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

                // Resilient fallback: fetch a brand new token from enc-dec.app bypassing cache if initial stream failed
                if (stream == null) {
                    val encUrl = "https://enc-dec.app/api/enc-vidlink?text=$effectiveTmdbId"
                    val remoteEnc = retryTransient(3, 300L) {
                        val resp = suspendCancellable {
                            runCatching {
                                app.get(
                                    encUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "application/json"
                                    ),
                                    timeout = 10L
                                )
                            }.getOrNull()
                        }
                        if (resp != null && resp.isSuccessful && resp.text.isNotBlank()) {
                            runCatching { JSONObject(resp.text).optString("result") }.getOrNull()?.takeIf { it.isNotBlank() }
                        } else null
                    }
                    if (!remoteEnc.isNullOrBlank() && remoteEnc != encData) {
                        encData = remoteEnc
                        vidlinkEncCache[effectiveTmdbId] = Pair(remoteEnc, System.currentTimeMillis())
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
                vidlinkEncCache[effectiveTmdbId] = Pair(encData, System.currentTimeMillis())

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
                            directQualitiesLinks.add(
                                newExtractorLink(
                                    "Vidlink",
                                    "Vidlink $effectiveQuality",
                                    url = cleanVideoUrl,
                                    type = if (isDirectVideo) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                                ) {
                                    this.referer = ""
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

    // ==================== VidEm / VidSrc.buzz SOTA Extractor ====================

    /**
     * One deployment of the VID ("VidEm") playback engine.
     *
     * The engine is a server-rendered player: `GET {embedBase}/embed/{movie|tv}/...` inlines the
     * full player state as `var Q = {...}` (type/id/token plus a pre-warmed `ssr.servers` list),
     * and every subsequent call goes through a relative `api.php` that resolves against the page's
     * `<base href>`. Mirror #2 therefore lives under a path prefix (`/pl/`), not at its root.
     */
    private data class VidEmMirror(
        val origin: String,
        val apiBase: String
    )

    /**
     * Verified live 2026-10-10. `videm.xyz` is the canonical origin; `vidsrc.buzz` runs the same
     * engine behind a `/pl/` prefix and is used purely as a failover origin when the canonical
     * origin is unreachable.
     */
    private val VIDEM_MIRRORS = listOf(
        VidEmMirror("https://videm.xyz", "https://videm.xyz/api.php"),
        VidEmMirror("https://vidsrc.buzz", "https://vidsrc.buzz/pl/api.php")
    )

    private const val VIDEM_STREAM_NAME = "VidEm"
    private const val VIDEM_MINT_CONCURRENCY = 8
    private const val VIDEM_MINT_TIMEOUT_MS = 9_000L
    private const val VIDEM_RACE_BUDGET_MS = 11_000L
    private const val VIDEM_VALIDATE_TIMEOUT_MS = 6_000L
    private const val VIDEM_SERVER_POLL_TIMEOUT_MS = 12_000L

    /**
     * Brace-matches the server-rendered `var Q = {...}` player state out of an embed page.
     *
     * A regex is unusable here: `Q` nests objects and arrays and embeds quoted HTML (the `<iframe>`
     * share snippet), so the only correct parse is a string-aware brace scan.
     */
    internal fun videmExtractState(html: String): JSONObject? {
        val marker = html.indexOf("var Q")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return runCatching { JSONObject(html.substring(start, i + 1)) }.getOrNull()
                        }
                    }
                }
            }
        }
        return null
    }

    private fun videmUrlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The exact query string the player itself sends: `type&id&s&e&t`. */
    internal fun videmQuery(state: JSONObject): String {
        return "type=${videmUrlEncode(state.optString("type"))}" +
            "&id=${videmUrlEncode(state.optString("id"))}" +
            "&s=${state.optInt("s", 0)}" +
            "&e=${state.optInt("e", 0)}" +
            "&t=${videmUrlEncode(state.optString("t"))}"
    }

    /** Server handles pre-warmed into the embed response, newest first. */
    internal fun videmPreWarmedHandles(state: JSONObject): List<String> {
        val servers = state.optJSONObject("ssr")?.optJSONArray("servers") ?: return emptyList()
        return (0 until servers.length()).mapNotNull { index ->
            servers.optJSONObject(index)?.optString("ref")?.takeIf { it.isNotBlank() }
        }
    }

    internal fun videmHandlesFromPayload(body: String): List<String> {
        val servers = runCatching { JSONObject(body).optJSONArray("servers") }.getOrNull() ?: return emptyList()
        return (0 until servers.length()).mapNotNull { index ->
            servers.optJSONObject(index)?.optString("ref")?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun videmGet(url: String, referer: String, timeout: Long): String? {
        val response = suspendCancellable {
            runCatching {
                app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), timeout = timeout)
            }.getOrNull()
        }
        return response?.takeIf { it.isSuccessful }?.text?.takeIf { it.isNotBlank() }
    }

    /**
     * Mints a playable stream URL for one server handle.
     *
     * The engine answers `200 {"error":"unavailable"}` for handles whose backing scraper is dry,
     * so a non-JSON or `url`-less body is a normal miss rather than an error condition.
     */
    private suspend fun videmMint(
        mirror: VidEmMirror,
        handle: String,
        token: String,
        embedUrl: String
    ): String? {
        val url = "${mirror.apiBase}?a=play&ref=${videmUrlEncode(handle)}&t=${videmUrlEncode(token)}"
        val body = videmGet(url, embedUrl, VIDEM_MINT_TIMEOUT_MS) ?: return null
        if (!body.trimStart().startsWith("{")) return null

        val raw = runCatching { JSONObject(body).optString("url") }.getOrNull()
        if (raw.isNullOrBlank()) return null
        return when {
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("/") -> mirror.origin + raw
            else -> "${mirror.origin}/$raw"
        }
    }

    /**
     * Confirms a minted URL really hands back a manifest/segment stream instead of an error shell.
     * A dead handle can still mint a signed URL, so this check is what keeps broken entries out of
     * the user's source list.
     */
    private suspend fun videmIsPlayable(url: String, referer: String): Boolean {
        val response = suspendCancellable {
            runCatching {
                app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer), timeout = VIDEM_VALIDATE_TIMEOUT_MS)
            }.getOrNull()
        } ?: return false
        if (!response.isSuccessful) return false

        val contentType = response.headers["Content-Type"].orEmpty().lowercase(Locale.ROOT)
        if (contentType.startsWith("video/") || contentType.contains("mpegurl")) return true

        val body = response.text.take(2048)
        return body.contains("#EXTM3U") || body.contains("#EXT-X-")
    }

    /**
     * Races every server handle concurrently and returns the first minted URL that actually plays.
     *
     * The engine's own player does exactly this (`raceStart` + `probeCands`), because individual
     * handles are independently healthy or dry at any moment. Racing is therefore the intended
     * protocol rather than a workaround, and it is what makes the first result fast.
     */
    private suspend fun videmRaceFirstPlayable(
        mirror: VidEmMirror,
        handles: List<String>,
        token: String,
        embedUrl: String
    ): String? {
        val candidates = handles.distinct().take(VIDEM_MINT_CONCURRENCY)
        if (candidates.isEmpty()) return null

        val minted = Channel<String>(Channel.UNLIMITED)
        val validations = Semaphore(4)
        var winner: String? = null

        coroutineScope {
            val jobs = candidates.map { handle ->
                launch(Dispatchers.IO) {
                    val url = videmMint(mirror, handle, token, embedUrl) ?: return@launch
                    minted.send(url)
                }
            }
            try {
                withTimeoutOrNull(VIDEM_RACE_BUDGET_MS) {
                    while (true) {
                        val candidate = minted.receive()
                        val playable = withContext(Dispatchers.IO) {
                            validations.withPermit { videmIsPlayable(candidate, embedUrl) }
                        }
                        if (playable) {
                            winner = candidate
                            return@withTimeoutOrNull
                        }
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
                minted.close()
            }
        }

        return winner
    }

    private suspend fun videmEmitSubtitles(
        mirror: VidEmMirror,
        query: String,
        embedUrl: String,
        subtitleCallback: ((SubtitleFile) -> Unit)?
    ) {
        if (subtitleCallback == null) return
        val body = videmGet("${mirror.apiBase}?a=subs&$query", embedUrl, 8_000L) ?: return
        val subs = runCatching { JSONObject(body).optJSONArray("subs") }.getOrNull() ?: return
        for (index in 0 until subs.length()) {
            val entry = subs.optJSONObject(index) ?: continue
            val handle = entry.optString("ref")
            if (handle.isBlank()) continue
            val label = cleanSubtitleLabel(entry.optString("label").ifBlank { "Subtitle" })
            // The engine proxies each caption track through `a=sub`; emitting the proxy keeps the
            // track reachable from the same origin the player itself uses.
            val subUrl = "${mirror.apiBase}?a=sub&ref=${videmUrlEncode(handle)}"
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    /**
     * Resolves the "VidEm" engine (videm.xyz, mirrored on vidsrc.buzz) for movies and TV.
     *
     * Protocol (all endpoints verified live 2026-10-10 against real playback):
     *   GET /embed/movie/{tmdb}            -> `var Q = {type,id,token,ssr.servers}`
     *   GET /embed/tv/{tmdb}/{season}/{ep} -> `var Q = {type,id,token,ssr.servers}`
     *   GET /api.php?a=sources&type&id&s&e&t  -> `{status,servers:[{ref}],more}` (refresh pass)
     *   GET /api.php?a=play&ref&t             -> `{url:"/_stream?id=...",type:"hls"}`
     *   GET /api.php?a=subs&type&id&s&e&t     -> `{subs:[{label,lang,cc,ref}]}`
     *   GET /_stream?id=...                   -> multi-variant HLS master playlist
     */
    suspend fun invokeVidEm(
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

            val isEpisode = season != null && season > 0 && episode != null && episode > 0

            withTimeoutOrNull(28_000L) {
                for (mirror in VIDEM_MIRRORS) {
                    val path = if (isEpisode) {
                        "/embed/tv/$effectiveTmdbId/$season/$episode"
                    } else {
                        "/embed/movie/$effectiveTmdbId"
                    }
                    val embedUrl = mirror.origin + path
                    val embedHtml = videmGet(embedUrl, "${mirror.origin}/", 10_000L) ?: continue
                    val state = videmExtractState(embedHtml) ?: continue

                    val token = state.optString("t")
                    if (token.isBlank()) continue
                    val query = videmQuery(state)

                    var handles = videmPreWarmedHandles(state)
                    var streamUrl = videmRaceFirstPlayable(mirror, handles, token, embedUrl)

                    // Pre-warmed handles can all be stale; the engine's refresh pass is the
                    // documented way to pull a fresh set, so one bounded retry is issued.
                    if (streamUrl == null) {
                        val refreshed = videmGet(
                            "${mirror.apiBase}?a=sources&$query&refresh=1",
                            embedUrl,
                            VIDEM_SERVER_POLL_TIMEOUT_MS
                        )
                        if (refreshed != null) {
                            handles = videmHandlesFromPayload(refreshed).ifEmpty { handles }
                            streamUrl = videmRaceFirstPlayable(mirror, handles, token, embedUrl)
                        }
                    }

                    if (streamUrl == null) continue

                    videmEmitSubtitles(mirror, query, embedUrl, subtitleCallback)

                    val headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to mirror.origin
                    )

                    val generated = withTimeoutOrNull(9_000L) {
                        runCatching {
                            generateM3u8(
                                VIDEM_STREAM_NAME,
                                streamUrl,
                                referer = embedUrl,
                                headers = headers
                            )
                        }.getOrNull()
                    }

                    emitTopTierDualQualityStreamLinks(
                        source = VIDEM_STREAM_NAME,
                        baseName = "VidEm HLS",
                        url = streamUrl,
                        referer = embedUrl,
                        headers = headers,
                        streamType = ExtractorLinkType.M3U8,
                        generatedLinks = generated,
                        callback = callback
                    )
                    return@withTimeoutOrNull
                }
                Log.d("StreamPlay", "VidEm: no playable server for tmdb=$effectiveTmdbId s=$season e=$episode")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("StreamPlay", "invokeVidEm failed: ${e.message}")
        }
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
