package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object TrackerManager {
    const val TRACKER_TTL_MS = 12 * 60 * 60 * 1000L // 12 hours

    val FALLBACK_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://p4p.arenabg.com:1337/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://open.demonii.com:1337/announce"
    )

    @Volatile
    private var cachedFormattedTrackers: String? = null

    @Volatile
    private var cacheTimestamp: Long = 0L

    private val mutex = Mutex()

    fun getFallbackTrackersFormatted(): String {
        return FALLBACK_TRACKERS.joinToString("") { "&tr=$it" }
    }

    fun parseAndFormatTrackers(rawText: String): String {
        return rawText
            .split("\n")
            .map { it.trim() }
            .filterIndexed { i, _ -> i % 2 == 0 }
            .filter { it.isNotEmpty() }
            .joinToString("") { "&tr=$it" }
    }

    fun clearCache() {
        cachedFormattedTrackers = null
        cacheTimestamp = 0L
    }

    internal fun setCacheForTesting(formatted: String?, timestamp: Long) {
        cachedFormattedTrackers = formatted
        cacheTimestamp = timestamp
    }

    suspend fun getFormattedTrackers(): String {
        val now = System.currentTimeMillis()
        val current = cachedFormattedTrackers
        if (current != null && (now - cacheTimestamp) < TRACKER_TTL_MS) {
            return current
        }

        return mutex.withLock {
            val secondCheck = cachedFormattedTrackers
            val secondNow = System.currentTimeMillis()
            if (secondCheck != null && (secondNow - cacheTimestamp) < TRACKER_TTL_MS) {
                return@withLock secondCheck
            }

            val fetched = try {
                val resp = app.get(StremioAddon.TRACKER_LIST_URL, timeout = 10L).text
                val formatted = parseAndFormatTrackers(resp)
                if (formatted.isNotEmpty()) formatted else getFallbackTrackersFormatted()
            } catch (e: Throwable) {
                Log.e("TrackerManager", "Failed fetching trackers from remote, using fallback: ${e.message}")
                getFallbackTrackersFormatted()
            }

            cachedFormattedTrackers = fetched
            cacheTimestamp = System.currentTimeMillis()
            fetched
        }
    }
}

fun String.fixSourceUrl(): String {
    return this.replace("/manifest.json", "").replace("stremio://", "https://")
}

fun fixSourceName(name: String?, title: String?, description: String?): String {
    val pName = name?.replace("\n", " ")
    val pTitle = title?.replace("\n", " ")

    return when {
        !pName.isNullOrEmpty() && !pTitle.isNullOrEmpty() -> "$pName\n$pTitle"
        !pName.isNullOrEmpty() && !description.isNullOrEmpty() -> "$pName\n$description"
        else -> pTitle ?: description ?: pName ?: ""
    }
}

private val QUALITY_REGEX = Regex("(\\d{3,4}[pP])")

fun extractQualityString(qualities: List<String?>): String? {
    fun String.getQuality(): String? {
        val has = QUALITY_REGEX.find(this)?.groupValues?.getOrNull(1)
        if (has != null) return has
        if (contains("4k", ignoreCase = true)) return "2160p"
        return null
    }
    return qualities.firstNotNullOfOrNull { it?.getQuality() }
}

fun getQuality(qualities: List<String?>): Int {
    val quality = extractQualityString(qualities)
    return getQualityFromName(quality)
}

fun getEpisodeSlug(
    season: Int? = null,
    episode: Int? = null,
): Pair<String, String> {
    return if (season == null && episode == null) {
        "" to ""
    } else {
        (if (season!! < 10) "0$season" else "$season") to (if (episode!! < 10) "0$episode" else "$episode")
    }
}

fun isUpcoming(dateString: String?): Boolean {
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateTime = dateString?.let { format.parse(it)?.time } ?: return false
        unixTimeMS < dateTime
    } catch (t: Throwable) {
        logError(t)
        false
    }

}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

suspend fun fetchTmdbLogoUrl(
    tmdbAPI: String,
    apiKey: String,
    type: TvType,
    tmdbId: Int?,
    appLangCode: String?
): String? {

    if (tmdbId == null) return null

    val url = if (type == TvType.Movie)
        "$tmdbAPI/movie/$tmdbId/images?api_key=$apiKey"
    else
        "$tmdbAPI/tv/$tmdbId/images?api_key=$apiKey"

    val json = runCatching { JSONObject(app.get(url).text) }.getOrNull() ?: return null
    val logos = json.optJSONArray("logos") ?: return null
    if (logos.length() == 0) return null

    val lang = appLangCode?.trim()?.lowercase()?.substringBefore("-")

    fun path(o: JSONObject) = o.optString("file_path")
    fun isSvg(o: JSONObject) = path(o).endsWith(".svg", true)
    fun urlOf(o: JSONObject) = "https://image.tmdb.org/t/p/w500${path(o)}"

    // Language match
    var svgFallback: JSONObject? = null

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        val p = path(logo)
        if (p.isBlank()) continue

        val l = logo.optString("iso_639_1").trim().lowercase()
        if (l == lang) {
            if (!isSvg(logo)) return urlOf(logo)
            if (svgFallback == null) svgFallback = logo
        }
    }
    svgFallback?.let { return urlOf(it) }

    // Highest voted fallback
    var best: JSONObject? = null
    var bestSvg: JSONObject? = null

    fun voted(o: JSONObject) = o.optDouble("vote_average", 0.0) > 0 && o.optInt("vote_count", 0) > 0

    fun better(a: JSONObject?, b: JSONObject): Boolean {
        if (a == null) return true
        val aAvg = a.optDouble("vote_average", 0.0)
        val aCnt = a.optInt("vote_count", 0)
        val bAvg = b.optDouble("vote_average", 0.0)
        val bCnt = b.optInt("vote_count", 0)
        return bAvg > aAvg || (bAvg == aAvg && bCnt > aCnt)
    }

    for (i in 0 until logos.length()) {
        val logo = logos.optJSONObject(i) ?: continue
        if (!voted(logo)) continue

        if (isSvg(logo)) {
            if (better(bestSvg, logo)) bestSvg = logo
        } else {
            if (better(best, logo)) best = logo
        }
    }

    best?.let { return urlOf(it) }
    bestSvg?.let { return urlOf(it) }

    // No language match & no voted logos
    return null
}