package com.phisher98

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.amapIndexed
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.phisher98.BuildConfig.SUPERSTREAM_FOURTH_API
import com.phisher98.BuildConfig.SUPERSTREAM_THIRD_API
import com.phisher98.BuildConfig.NuvFeb
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

private val ORG_QUALITY_REGEX = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE)
private val GSON = Gson()
private val WYZIESUB_LIST_TYPE = object : TypeToken<List<WyZIESUB>>() {}.type

private val VIDEO_HEADERS = mapOf(
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Accept-Language" to "en-US,en;q=0.8",
    "Connection" to "keep-alive",
    "Referer" to SUPERSTREAM_THIRD_API,
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
    "Sec-Fetch-Storage-Access" to "none",
    "Sec-GPC" to "1",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36",
    "sec-ch-ua" to "\"Not;A=Brand\";v=\"99\", \"Brave\";v=\"139\", \"Chromium\";v=\"139\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\""
)
private val LANG_HEADERS = mapOf("Accept-Language" to "en")
private val SUBTITLE_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
)

private const val SUBSENSE_URDU_BASE =
    "https://subsense.nepiraw.com/n2s6143949c45/subtitles"
private const val SUBSENSE_URDU_ALT_BASE =
    "https://subsense.nepiraw.com/12345678-eyJsYW5ndWFnZXMiOiBbInVyIl19/subtitles"
private const val GOOGLE_TRANSLATE_CLIENTS5_URL =
    "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=en&tl=ur"
private const val GOOGLE_TRANSLATE_FALLBACK_URL =
    "https://translate.google.com/translate_a/t?client=at&sl=en&tl=ur"
private const val SUPERSTREAM_RLM = '\u200F'
private const val SUPERSTREAM_MAX_INFLATE_BYTES = 8 * 1024 * 1024
private val SUPERSTREAM_URDU_SCRIPT_REGEX =
    Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")
private val SUPERSTREAM_ASS_TAG_REGEX = Regex("""\{\\[^}]*\}""")
private val SUPERSTREAM_HTML_TAG_REGEX =
    Regex("""</?(?:font|b|i|u|s|span|div|p|br)\b[^>]*>""", RegexOption.IGNORE_CASE)
private val SUPERSTREAM_SRT_TIMESTAMP_REGEX = Regex(
    """^\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[,.]\d{3}|\d{2}:\d{2}[,.]\d{3})(.*)$"""
)

object SuperStreamExtractor : SuperStream() {

    private val synthesizedUrduCache = ConcurrentHashMap<String, String>()

    suspend fun invokeSuperstream(
        token: String? = null,
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit
    ) {
        val searchUrl = "$SUPERSTREAM_FOURTH_API/search?keyword=$imdbId"
        val href = app.get(searchUrl, timeout = 10L).document.selectFirst("h2.film-name a")?.attr("href")
            ?.let { SUPERSTREAM_FOURTH_API + it }
        val mediaId = href?.let {
            app.get(it, timeout = 10L).document.selectFirst("h2.heading-name a")?.attr("href")
                ?.substringAfterLast("/")?.toIntOrNull()
        }
        mediaId?.let {
            invokeExternalSource(it, if (season == null) 1 else 2, season, episode, callback, token)
        }
    }

    private suspend fun invokeExternalSource(
        mediaId: Int? = null,
        type: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
        token: String? = null
    ) {
        val thirdAPI = SUPERSTREAM_THIRD_API
        val fourthAPI = SUPERSTREAM_FOURTH_API
        val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

        val shareKey =
            app.get("$fourthAPI/index/share_link?id=${mediaId}&type=$type", headers = LANG_HEADERS, timeout = 10L)
                .parsedSafe<ER>()?.data?.link?.substringAfterLast("/") ?: return

        val shareRes =
            app.get("$thirdAPI/file/file_share_list?share_key=$shareKey", headers = LANG_HEADERS, timeout = 10L)
                .parsedSafe<ExternalResponse>()?.data ?: return
        val fids = if (season == null) {
            shareRes.fileList
        } else {
            shareRes.fileList?.find {
                it.fileName.equals(
                    "season $season",
                    true
                )
            }?.fid?.let { parentId ->
                app.get(
                    "$thirdAPI/file/file_share_list?share_key=$shareKey&parent_id=$parentId&page=1",
                    headers = LANG_HEADERS,
                    timeout = 10L
                )
                    .parsedSafe<ExternalResponse>()?.data?.fileList?.filter {
                        it.fileName?.contains("s${seasonSlug}e${episodeSlug}", true) == true
                    }
            }
        } ?: return

        val semaphore = Semaphore(DeviceProfiler.getActiveConcurrency().coerceAtMost(8))
        coroutineScope {
            fids.mapIndexed { index, fileList ->
                async {
                    semaphore.withPermit {
                        try {
                            val superToken = token?.let {
                                if (it.startsWith("ui=")) it else "ui=$it"
                            } ?: ""
                            val player = app.get(
                                "$thirdAPI/console/video_quality_list?fid=${fileList.fid}&share_key=$shareKey",
                                headers = mapOf("Cookie" to superToken),
                                timeout = 10L
                            ).text
                            val json = try {
                                JSONObject(player)
                            } catch (e: Exception) {
                                Log.e("Error:", "Invalid JSON response $e")
                                return@withPermit
                            }
                            val htmlContent = json.optString("html", "")
                            if (htmlContent.isEmpty()) return@withPermit

                            val sourcesWithQualities = ZeroAllocParser.extractSuperStreamQualities(htmlContent)

                            sourcesWithQualities.forEach { item ->
                                val format = ExtractorLinkType.VIDEO
                                callback.invoke(
                                    newExtractorLink(
                                        "⌜ SuperStream ⌟",
                                        "⌜ SuperStream ⌟ [Server ${index + 1}] ${item.size}",
                                        item.url,
                                        format
                                    ) {
                                        this.quality = getIndexQuality(item.quality)
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e("SuperStream", "invokeExternalSource failed: $e")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun invokeSubtitleAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = id?.trim()?.takeIf { it.isNotBlank() } ?: return
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$cleanId.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$cleanId:$season:$episode.json"
        }
        val urduMode = isUrduSubtitleMode(sharedPref)
        val allSubs = try {
            app.get(url, headers = SUBTITLE_HEADERS, timeout = 10L)
                .parsedSafe<SubtitlesAPI>()?.subtitles.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        val orderedSubs = if (urduMode) {
            val urduSubs = allSubs.filter { isUrduSubtitleCode(it.lang) || isUrduSubtitleCode(getLanguage(it.lang)) }
            val englishSubs = allSubs.filter {
                !isUrduSubtitleCode(it.lang) && (isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)))
            }
            urduSubs + englishSubs
        } else {
            allSubs.filter { isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)) }
        }
        orderedSubs.amap {
            val subUrl = it.url.trim()
            if (subUrl.startsWith("http", ignoreCase = true)) {
                val lan = when {
                    isUrduSubtitleCode(it.lang) || isUrduSubtitleCode(getLanguage(it.lang)) -> "Urdu"
                    isEnglishSubtitleCode(it.lang) || isEnglishSubtitleCode(getLanguage(it.lang)) -> "English"
                    else -> getLanguage(it.lang) ?: "English"
                }
                subtitleCallback.invoke(
                    newSubtitleFile(
                        lan.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString() },
                        subUrl
                    )
                )
            }
        }
    }


    suspend fun invokeWyZIESUBAPI(
        id: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = id?.trim()?.takeIf { it.isNotBlank() } ?: return
        val urduMode = isUrduSubtitleMode(sharedPref)
        val wyzieBases = listOf("https://sub.wyzie.io", "https://sub.wyzie.ru")
        for (base in wyzieBases) {
            try {
                val baseQuery = if (season == null) {
                    "$base/search?id=$cleanId"
                } else {
                    "$base/search?id=$cleanId&season=$season&episode=$episode"
                }
                if (urduMode) {
                    try {
                        val urText = app.get("$baseQuery&language=ur", timeout = 8L).text
                        val urSubs: List<WyZIESUB> = GSON.fromJson(urText, WYZIESUB_LIST_TYPE) ?: emptyList()
                        urSubs.forEach {
                            val suburl = it.url.trim()
                            if (suburl.startsWith("http", ignoreCase = true) &&
                                (isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language) || it.language.isBlank())
                            ) {
                                subtitleCallback.invoke(newSubtitleFile("Urdu", suburl))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }

                val res = app.get(baseQuery, timeout = 10L).text
                val subtitles: List<WyZIESUB> = GSON.fromJson(res, WYZIESUB_LIST_TYPE) ?: emptyList()
                if (subtitles.isEmpty()) continue
                val ordered = if (urduMode) {
                    val urList = subtitles.filter {
                        isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)
                    }
                    val enList = subtitles.filter {
                        !(isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)) &&
                            (isEnglishSubtitleCode(it.display) || isEnglishSubtitleCode(it.language))
                    }
                    urList + enList
                } else {
                    subtitles.filter {
                        !(isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)) &&
                            (isEnglishSubtitleCode(it.display) || isEnglishSubtitleCode(it.language))
                    }
                }
                ordered.forEach {
                    val suburl = it.url.trim()
                    if (suburl.startsWith("http", ignoreCase = true)) {
                        val isUr = isUrduSubtitleCode(it.display) || isUrduSubtitleCode(it.language)
                        val lan = if (isUr) "Urdu" else "English"
                        subtitleCallback.invoke(newSubtitleFile(lan, suburl))
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    suspend fun invokeSubSenseUrdu(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val cleanId = imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) } ?: return
        if (!isUrduSubtitleMode(sharedPref)) return
        val path = if (season == null || season <= 0) {
            "movie/$cleanId.json"
        } else {
            "series/$cleanId:$season:${episode ?: 1}.json"
        }
        val urls = listOf(
            "$SUBSENSE_URDU_BASE/$path",
            "$SUBSENSE_URDU_ALT_BASE/$path"
        )
        for (url in urls) {
            try {
                val body = app.get(url, headers = SUBTITLE_HEADERS, timeout = 6L).text
                if (body.isBlank()) continue
                val root = JSONObject(body)
                val arr = root.optJSONArray("subtitles") ?: continue
                var emitted = false
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val lang = obj.optString("lang").trim()
                    val subUrl = obj.optString("url").trim()
                    if (subUrl.startsWith("http", ignoreCase = true) &&
                        (lang.isEmpty() || isUrduSubtitleCode(lang))
                    ) {
                        subtitleCallback.invoke(newSubtitleFile("Urdu", subUrl))
                        emitted = true
                    }
                }
                if (emitted) return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    suspend fun synthesizeSuperStreamUrduFallback(
        englishSubs: List<SubtitleFile>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        if (englishSubs.isEmpty()) return@withContext false
        for (candidate in englishSubs.take(3)) {
            val enUrl = candidate.url.trim()
            if (!enUrl.startsWith("http", ignoreCase = true)) continue
            synthesizedUrduCache[enUrl]?.let { cachedUri ->
                subtitleCallback(newSubtitleFile("Urdu", cachedUri))
                return@withContext true
            }
            val rawBytes = try {
                app.get(enUrl, headers = SUBTITLE_HEADERS, timeout = 6L).okhttpResponse.body.bytes()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue
            val decodedEnglish = inflateAndDecodeSubtitleBytes(rawBytes)
            if (decodedEnglish.length < 32) continue
            val urduSrt = translateEnglishSrtToUrdu(decodedEnglish) ?: continue
            val fileUri = writeLocalUrduSrtFile(enUrl, urduSrt) ?: continue
            synthesizedUrduCache[enUrl] = fileUri
            subtitleCallback(newSubtitleFile("Urdu", fileUri))
            return@withContext true
        }
        false
    }

    private fun inflateAndDecodeSubtitleBytes(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        val bytes = when {
            raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x1F && (raw[1].toInt() and 0xFF) == 0x8B -> {
                runCatching {
                    GZIPInputStream(ByteArrayInputStream(raw)).use { gis ->
                        readBoundedStream(gis)
                    }
                }.getOrDefault(raw)
            }
            raw.size >= 4 &&
                (raw[0].toInt() and 0xFF) == 0x50 &&
                (raw[1].toInt() and 0xFF) == 0x4B &&
                (raw[2].toInt() and 0xFF) == 0x03 &&
                (raw[3].toInt() and 0xFF) == 0x04 -> {
                runCatching {
                    ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
                        var best: ByteArray? = null
                        while (true) {
                            val entry = zis.nextEntry ?: break
                            if (!entry.isDirectory &&
                                (entry.name.endsWith(".srt", ignoreCase = true) ||
                                    entry.name.endsWith(".vtt", ignoreCase = true))
                            ) {
                                best = readBoundedStream(zis)
                                zis.closeEntry()
                                break
                            }
                            zis.closeEntry()
                        }
                        best ?: raw
                    }
                }.getOrDefault(raw)
            }
            else -> raw
        }
        val text = if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        } else {
            String(bytes, StandardCharsets.UTF_8)
        }
        return Normalizer.normalize(
            text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n'),
            Normalizer.Form.NFC
        )
    }

    private fun readBoundedStream(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            val allowed = (SUPERSTREAM_MAX_INFLATE_BYTES - total).coerceAtMost(read)
            if (allowed > 0) {
                out.write(buf, 0, allowed)
                total += allowed
            }
            if (total >= SUPERSTREAM_MAX_INFLATE_BYTES) break
        }
        return out.toByteArray()
    }

    private data class SuperStreamSrtCue(val timestamp: String, val text: String)

    private fun parseSuperStreamCues(rawContent: String): List<SuperStreamSrtCue> {
        val lines = rawContent.trim().split('\n')
        if (lines.isEmpty()) return emptyList()
        val cues = mutableListOf<SuperStreamSrtCue>()
        var i = 0
        if (lines.first().trim().startsWith("WEBVTT", ignoreCase = true)) {
            i++
            while (i < lines.size && lines[i].trim().isNotEmpty()) i++
        }
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("NOTE", ignoreCase = true) || line.startsWith("STYLE", ignoreCase = true)) {
                i++
                continue
            }
            var tsMatch = SUPERSTREAM_SRT_TIMESTAMP_REGEX.matchEntire(line)
            if (tsMatch == null && i + 1 < lines.size) {
                val nextMatch = SUPERSTREAM_SRT_TIMESTAMP_REGEX.matchEntire(lines[i + 1].trim())
                if (nextMatch != null) {
                    tsMatch = nextMatch
                    i += 2
                } else {
                    i++
                    continue
                }
            } else if (tsMatch != null) {
                i++
            } else {
                i++
                continue
            }
            val start = normalizeTimestamp(tsMatch.groupValues[1])
            val end = normalizeTimestamp(tsMatch.groupValues[2])
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().isNotEmpty()) {
                val cleaned = lines[i]
                    .replace(SUPERSTREAM_ASS_TAG_REGEX, "")
                    .replace(SUPERSTREAM_HTML_TAG_REGEX, "")
                    .trim()
                if (cleaned.isNotEmpty()) textLines.add(cleaned)
                i++
            }
            val combined = textLines.joinToString("\n").trim()
            if (combined.isNotEmpty()) {
                cues.add(SuperStreamSrtCue("$start --> $end", combined))
            }
        }
        return cues
    }

    private fun normalizeTimestamp(ts: String): String {
        val commaFixed = ts.trim().replace('.', ',')
        val parts = commaFixed.split(':')
        return when (parts.size) {
            2 -> "00:${parts[0].padStart(2, '0')}:${parts[1]}"
            3 -> "${parts[0].padStart(2, '0')}:${parts[1].padStart(2, '0')}:${parts[2]}"
            else -> commaFixed
        }
    }

    private suspend fun translateEnglishSrtToUrdu(englishContent: String): String? = coroutineScope {
        val cues = parseSuperStreamCues(englishContent)
        if (cues.isEmpty()) return@coroutineScope null
        val batches = cues.chunked(250)
        val translatedBatches = batches.map { batch ->
            async(Dispatchers.IO) {
                val texts = batch.map { it.text }
                val translated = translateBatchViaGoogle(GOOGLE_TRANSLATE_CLIENTS5_URL, texts)
                    ?: translateBatchViaGoogle(GOOGLE_TRANSLATE_FALLBACK_URL, texts)
                if (translated != null && translated.size == batch.size) {
                    batch.mapIndexed { idx, cue -> cue.copy(text = translated[idx]) }
                } else {
                    null
                }
            }
        }.awaitAll()
        if (translatedBatches.any { it == null }) return@coroutineScope null
        val merged = translatedBatches.filterNotNull().flatten()
        val sb = StringBuilder(merged.size * 64)
        var index = 1
        for (cue in merged) {
            val normalizedText = cue.text
                .replace('\u064A', '\u06CC')
                .replace('\u0649', '\u06CC')
                .replace('\u0643', '\u06A9')
            val wrappedLines = normalizedText.split('\n').mapNotNull { line ->
                val stripped = line.trim().trim(SUPERSTREAM_RLM, '\u200E')
                if (stripped.isEmpty()) null
                else if (SUPERSTREAM_URDU_SCRIPT_REGEX.containsMatchIn(stripped)) {
                    "$SUPERSTREAM_RLM$stripped$SUPERSTREAM_RLM"
                } else {
                    stripped
                }
            }.joinToString("\n")
            if (wrappedLines.isBlank()) continue
            sb.append(index++).append('\n')
            sb.append(cue.timestamp).append('\n')
            sb.append(wrappedLines).append("\n\n")
        }
        sb.toString().trimEnd().takeIf { it.isNotBlank() }?.plus("\n")
    }

    private fun translateBatchViaGoogle(endpoint: String, texts: List<String>): List<String>? {
        return try {
            val formBuilder = FormBody.Builder(Charsets.UTF_8)
            for (text in texts) {
                formBuilder.add("q", text)
            }
            val req = okhttp3.Request.Builder()
                .url(endpoint)
                .post(formBuilder.build())
                .header("User-Agent", SUBTITLE_HEADERS["User-Agent"] ?: "Mozilla/5.0")
                .header("Accept", "application/json, text/plain, */*")
                .build()
            app.baseClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body.string().trim()
                if (!body.startsWith("[")) return null
                val arr = JSONArray(body)
                if (arr.length() != texts.size) {
                    if (texts.size == 1 && arr.length() >= 1) {
                        val first = arr.get(0)
                        val single = when (first) {
                            is String -> first
                            is JSONArray -> first.optString(0)
                            else -> first.toString()
                        }
                        return listOf(single)
                    }
                    return null
                }
                val result = ArrayList<String>(texts.size)
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    val t = when (item) {
                        is String -> item
                        is JSONArray -> item.optString(0)
                        else -> item.toString()
                    }
                    result.add(t)
                }
                result
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLocalUrduSrtFile(keySeed: String, urduSrtContent: String): String? {
        if (urduSrtContent.isBlank()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(keySeed.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val tmpDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            if (!tmpDir.exists()) tmpDir.mkdirs()
            val file = File(tmpDir, "superstream_urdu_$digest.srt")
            val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            file.writeBytes(bom + urduSrtContent.toByteArray(StandardCharsets.UTF_8))
            "file:///" + file.absolutePath.trimStart('/')
        } catch (_: Exception) {
            null
        }
    }


    suspend fun invokeSuperstreamFeb(
        token: String? = null,
        id: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (token.isNullOrEmpty()) return

        val url = if (season == null) {
            "$NuvFeb/api/media/movie/$id?cookie=${URLEncoder.encode(token, "UTF-8")}"
        } else {
            "$NuvFeb/api/media/tv/$id/$season/$episode?cookie=${URLEncoder.encode(token, "UTF-8")}"
        }

        var parsed: FebResponse? = null

        try {
            for (attempt in 0..1) {
                val response = app.get(url, timeout = 10L)
                if (response.code == 500 && attempt == 0) {
                    delay(1000L)
                } else {
                    parsed = response.parsedSafe<FebResponse>()
                    break
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("SuperStream", "invokeSuperstreamFeb failed: $e")
            return
        }

        parsed ?: return

        parsed.versions.orEmpty().forEach { version ->
            version.links.orEmpty().forEach { link ->
                val streamUrl = link.url ?: return@forEach

                val title = version.name
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::cleanTitle)
                    ?: "Stream"

                val qualityName = link.quality.orEmpty()

                callback.invoke(
                    newExtractorLink(
                        source = "SuperStream",
                        name = buildString {
                            append("SuperStream • ")
                            append(title)
                            if (qualityName.equals("ORG", ignoreCase = true)) {
                                append(" • ORG")
                            }
                        },
                        url = streamUrl,
                        type = INFER_TYPE
                    ) {
                        quality = getQualityFromName(qualityName)
                    }
                )
            }
        }
    }
}
