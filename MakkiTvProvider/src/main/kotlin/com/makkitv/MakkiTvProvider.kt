package com.makkitv

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class MakkiTvProvider : MainAPI() {
    override var mainUrl = "https://makkitv.com"
    override var name = "Makki TV"
    override val hasMainPage = true
    override var lang = "ur"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,ur;q=0.8",
        "Cache-Control" to "max-age=0"
    )

    override val mainPage = mainPageOf(
        "/" to "Featured",
        "/category/kurulus-orhan-season-1-in-urdu-subtitles/" to "Kurulus Orhan",
        "/category/mehmed-fetihler-sultani-season-3-in-urdu/" to "Mehmed Fetihler",
        "/category/teskilat-season-6-in-urdu-subtitles/" to "Teşkilat",
        "/category/kurulus-osman-season-6-in-urdu-subtitles-makkitv/" to "Kurulus Osman",
        "/category/tasacak-bu-deniz-urdu-subtitles-ishq-ki-aag/" to "Taşacak Bu Deniz",
        "/category/turkish-movies/" to "Turkish Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPagedUrl(request.data, page)
        val document = fastDocument(url)
        val items = document.select("article, main a:has(img), .site-main a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(36)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val url = if (page <= 1) "$mainUrl/?s=$encodedQuery" else "$mainUrl/page/$page/?s=$encodedQuery"
        return fastDocument(url).select("article, main a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fastDocument(url)
        val title = document.selectFirst("h1.entry-title, h1, meta[property=og:title]")?.textOrContent()
            ?.cleanTitle() ?: name
        val poster = document.selectFirst("meta[property=og:image], article img, main img")?.imageUrl()
        val plot = document.select(".entry-content p, article p")
            .firstOrNull { it.text().length > 80 }
            ?.text()
            ?.trim()

        val episodes = collectEpisodes(document, url)
        if (episodes.size <= 1 && isMoviePage(title, document)) {
            return newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.ifEmpty { listOf(singleEpisode(url, title)) }) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = fastDocument(data)
        val links = document.select("iframe[src], iframe[data-src], iframe[data-litespeed-src], a[href]")
            .mapNotNull { element ->
                element.attr("src").ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-litespeed-src") }
                    .ifBlank { element.attr("href") }
                    .trim()
                    .takeIf { it.startsWith("http") }
            }
            .filter { it.isSupportedStreamUrl() }
            .distinct()

        links.forEach { loadExtractor(it, data, subtitleCallback, callback) }
        return links.isNotEmpty()
    }

    private suspend fun fastDocument(url: String): Document = try {
        app.get(url, headers = requestHeaders, timeout = 12_000L).document
    } catch (error: Exception) {
        // Assumption: a slower retry is better than surfacing a blank page when Makki TV or the viewer connection is temporarily slow.
        app.get(url, headers = requestHeaders, timeout = 25_000L).document
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val normalizedPath = path.ifBlank { "/" }
        return when {
            normalizedPath == "/" && page <= 1 -> mainUrl
            normalizedPath == "/" -> "$mainUrl/page/$page/"
            page <= 1 -> fixUrl(normalizedPath)
            else -> fixUrl(normalizedPath.trimEnd('/') + "/page/$page/")
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("h2.entry-title a, .entry-title a, a:has(img), a[href]") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || href == mainUrl) return null

        val title = link.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: link.text().takeIf { it.isNotBlank() }
            ?: selectFirst("h2.entry-title, .entry-title, img[alt]")?.textOrAlt()
            ?: return null

        val posterUrl = selectFirst("img")?.imageUrl()
        return newTvSeriesSearchResponse(title.cleanTitle(), href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private fun collectEpisodes(document: Document, fallbackUrl: String): List<Episode> {
        val episodes = document.select(".entry-content a[href], article a[href], main a[href]")
            .mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val label = link.text().ifBlank { link.selectFirst("img")?.attr("alt") ?: "" }.trim()
                if (!href.startsWith(mainUrl) || !looksLikeEpisode(href, label)) return@mapNotNull null
                val number = episodeNumber(label) ?: episodeNumber(href)
                newEpisode(href) {
                    this.name = label.ifBlank { "Episode ${number ?: 1}" }.cleanTitle()
                    this.episode = number
                    this.season = seasonNumber(label) ?: seasonNumber(href)
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: Int.MAX_VALUE })

        return episodes.ifEmpty { listOf(singleEpisode(fallbackUrl, document.selectFirst("h1")?.text() ?: name)) }
    }

    private fun singleEpisode(url: String, title: String): Episode = newEpisode(url) {
        this.name = title.cleanTitle()
        this.episode = 1
    }

    private fun looksLikeEpisode(url: String, label: String): Boolean {
        val value = "$url $label"
        return value.contains("episode", true) || Regex("\\bep\\s*[-_ ]?\\d+\\b", RegexOption.IGNORE_CASE).containsMatchIn(value)
    }

    private fun episodeNumber(value: String): Int? = Regex("(?:episode|ep|e)\\s*[-_:]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun seasonNumber(value: String): Int? = Regex("(?:season|s)\\s*[-_:]?\\s*(\\d+)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun isMoviePage(title: String, document: Document): Boolean =
        title.contains("movie", true) || document.select(".cat-links a, a[rel=category tag]").any { it.text().contains("movie", true) }

    private fun String.isSupportedStreamUrl(): Boolean = contains("vidmoly", true) || contains("embed", true) || contains("player", true)

    private fun Element.textOrContent(): String? = text().takeIf { it.isNotBlank() } ?: attr("content").takeIf { it.isNotBlank() }
    private fun Element.textOrAlt(): String? = text().takeIf { it.isNotBlank() } ?: attr("alt").takeIf { it.isNotBlank() }
    private fun Element.imageUrl(): String? = fixUrlNull(
        attr("content").ifBlank { attr("data-src") }.ifBlank { attr("data-litespeed-src") }.ifBlank { attr("src") }
    )

    private fun String.cleanTitle(): String = replace(Regex("\\s+[–|-]\\s+Makki\\s*TV.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
