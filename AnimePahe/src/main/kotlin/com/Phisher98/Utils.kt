package com.phisher98

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response


suspend fun loadCustomExtractor(
    name: String? = null,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    quality: Int? = null,
) {
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    name ?: link.source,
                    link.name,
                    link.url,
                ) {
                    this.quality = when {
                        else -> quality ?: link.quality
                    }
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}

class Kwik : ExtractorApi() {
    override val name            = "Kwik"
    override val mainUrl         = "https://kwik.cx"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val res = app.get(url,referer="${AnimePaheProviderPlugin.currentAnimepaheServer}/")

        val title = res.document.title()

        val script = res.document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()

        val unpacked = getAndUnpack(script ?: return)
        val m3u8 =Regex("source=\\s*'(.*?m3u8.*?)'").find(unpacked)?.groupValues?.getOrNull(1) ?:""

        val fileName = title.substringBeforeLast(".mp4") + ".mp4"

        val mp4Url = m3u8
            .replace("/stream/", "/mp4/")
            .substringBeforeLast("/")
            .let { "$it?file=${java.net.URLEncoder.encode(fileName, "UTF-8")}" }

        callback.invoke(
            newExtractorLink(
                name,
                name,
                url = m3u8,
                INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(title)
                this.headers= mapOf("origin" to mainUrl)
            }
        )

        callback(
            newExtractorLink(
                name,
                "$name [Download]",
                mp4Url,
                ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = getQualityFromName(fileName)
                this.headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl
                )
            }
        )
    }
}

//Credit Thanks to https://github.com/SaurabhKaperwan/CSX/blob/7256fe183966412b2323beb15d03331009bfb80f/CineStream/src/main/kotlin/com/megix/Extractors.kt#L108
class Pahe : ExtractorApi() {
    override val name = "Pahe"
    override val mainUrl = "https://pahe.win"
    override val requiresReferer = true
    private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
    private val kwikDUrl = Regex("action=\"([^\"]+)\"")
    private val kwikDToken = Regex("value=\"([^\"]+)\"")

    companion object {
        private val noRedirectsClient: OkHttpClient by lazy {
            app.baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }

    private val client: OkHttpClient
        get() = app.baseClient

    private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
        val keyIndexMap = key.withIndex().associate { it.value to it.index }
        val sb = StringBuilder()
        var i = 0
        val toFind = key[v2]

        while (i < fullString.length) {
            val nextIndex = fullString.indexOf(toFind, i)
            val decodedCharStr = buildString {
                for (j in i until nextIndex) {
                    append(keyIndexMap[fullString[j]] ?: -1)
                }
            }

            i = nextIndex + 1

            val decodedChar = (decodedCharStr.toInt(v2) - v1).toChar()
            sb.append(decodedChar)
        }

        return sb.toString()
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val initialRequest = Request.Builder()
            .url("$url/i")
            .get()
            .build()

        val kwikUrl = noRedirectsClient.newCall(initialRequest).execute().use { response ->
            response.header("location")?.let { location ->
                if (location.startsWith("http")) location else "https://${location.substringAfterLast("https://")}"
            } ?: return
        }

        val fContentRequest = Request.Builder()
            .url(kwikUrl)
            .header("referer", "https://kwik.cx/")
            .get()
            .build()

        var kwikCookie = ""
        var kwikReferer = kwikUrl
        val fContentString = client.newCall(fContentRequest).execute().use { response ->
            if (!response.isSuccessful) return
            kwikReferer = response.request.url.toString()
            kwikCookie = response.headers("set-cookie").firstOrNull().orEmpty()
            response.body?.string() ?: return
        }

        val (fullString, key, v1, v2) = kwikParamsRegex.find(fContentString)?.destructured ?: return
        val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())

        val uri = kwikDUrl.find(decrypted)?.destructured?.component1() ?: return
        val tok = kwikDToken.find(decrypted)?.destructured?.component1() ?: return

        var code = 419
        var tries = 0
        var content: Response? = null

        while (code != 302 && tries < 20) {
            val formBody = FormBody.Builder()
                .add("_token", tok)
                .build()

            val postRequest = Request.Builder()
                .url(uri)
                .header("user-agent", " Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("referer", kwikReferer)
                .header("cookie", kwikCookie)
                .post(formBody)
                .build()

            content?.close()
            content = noRedirectsClient.newCall(postRequest).execute()
            code = content.code
            tries++
        }

        val location = content?.use { response -> response.header("location") } ?: return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                url = location,
                INFER_TYPE
            ) {
                this.referer = "https://kwik.cx/"
                this.quality = getQualityFromName("")
            }
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaImage(
    @param:JsonProperty("coverType") val coverType: String?,
    @param:JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @param:JsonProperty("episode") val episode: String?,
    @param:JsonProperty("airDateUtc") val airDateUtc: String?,  // Keeping only one field
    @param:JsonProperty("runtime") val runtime: Int?,     // Keeping only one field
    @param:JsonProperty("image") val image: String?,
    @param:JsonProperty("title") val title: Map<String, String>?,
    @param:JsonProperty("overview") val overview: String?,
    @param:JsonProperty("rating") val rating: String?,
    @param:JsonProperty("finaleType") val finaleType: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @param:JsonProperty("titles") val titles: Map<String, String>?,
    @param:JsonProperty("images") val images: List<MetaImage>?,
    @param:JsonProperty("episodes") val episodes: Map<String, MetaEpisode>?,
    @param:JsonProperty("mappings") val mappings: MetaMappings? = null
)


@JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @param:JsonProperty("themoviedb_id") val themoviedbId: Int? = null,
    @param:JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @param:JsonProperty("imdb_id") val imdbId: String? = null,
    @param:JsonProperty("mal_id") val malId: Int? = null,
    @param:JsonProperty("anilist_id") val anilistId: Int? = null,
    @param:JsonProperty("kitsu_id") val kitsuid: String? = null,
)

fun parseAnimeData(jsonString: String): MetaAnimeData? {
    return try {
        val objectMapper = ObjectMapper()
        objectMapper.readValue(jsonString, MetaAnimeData::class.java)
    } catch (_: Exception) {
        null // Return null for invalid JSON instead of crashing
    }
}