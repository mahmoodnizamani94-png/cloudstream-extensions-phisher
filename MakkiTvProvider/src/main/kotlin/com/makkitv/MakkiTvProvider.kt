package com.makkitv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MakkiTvProvider : MainAPI() {
    override var mainUrl = "https://makkitv.com"
    override var name = "Makki TV"
    override val hasMainPage = true
    override var lang = "ur"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "subtitles" to "Latest Episodes",
        "orhan" to "Kurulus Orhan",
        "osman" to "Kurulus Osman",
        "mehmed" to "Mehmed Fetihler",
        "alparslan" to "Alparslan",
        "teskilat" to "Teşkilat",
        "goncalar" to "Kızıl Goncalar"
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val query = request.data
        val url = if (page == 1) {
            "$mainUrl/?s=$query"
        } else {
            "$mainUrl/page/$page/?s=$query"
        }

        val doc = app.get(url, headers = headers).document
        val home = doc.select("article").mapNotNull { element ->
            element.toSearchResult()
        }
        return newHomePageResponse(request.name, home, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.select("h2.entry-title a, h2 a").firstOrNull() ?: return null
        val title = linkElement.text()
        val href = linkElement.attr("href")
        val posterUrl = this.select("img").firstOrNull()?.let { img ->
            img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src")
        }
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val searchQuery = query.ifBlank { "subtitles" }
        val url = if (page == 1) {
            "$mainUrl/?s=$searchQuery"
        } else {
            "$mainUrl/page/$page/?s=$searchQuery"
        }

        val doc = app.get(url, headers = headers).document
        return doc.select("article").mapNotNull { element ->
            element.toSearchResult()
        }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, headers = headers).document

        // Check if this is a direct episode page (contains "episode")
        if (url.contains("episode", ignoreCase = true) || doc.select("iframe[src*='vidmoly']").firstOrNull() != null) {
            // It is an episode page. Let's try to find a parent season/series page link.
            // Look in the entry body (.entry-content a) or metadata for links containing "season" or "category/"
            val parentLink = doc.select(".entry-content a, .entry-meta a, .cat-links a, .breadcrumbs a").firstOrNull { element ->
                val href = element.attr("href")
                (href.contains("season", ignoreCase = true) || href.contains("category/", ignoreCase = true)) &&
                !href.contains("episode", ignoreCase = true) &&
                href.startsWith(mainUrl)
            }?.attr("href")

            if (parentLink != null) {
                // Fetch the parent season page instead!
                val parentDoc = app.get(parentLink, headers = headers).document
                // If parent page actually contains episode links, we use it!
                if (parentDoc.select(".entry-content a").any { it.text().contains("Episode", ignoreCase = true) || it.text().contains("Ep ", ignoreCase = true) }) {
                    doc = parentDoc
                }
            }
        }

        // Now parse title and poster
        val title = doc.select("h1.entry-title, h1").firstOrNull()?.text() 
            ?: doc.select("meta[property='og:title']").firstOrNull()?.attr("content") 
            ?: "Makki TV Show"
        
        val poster = doc.select("meta[property='og:image']").firstOrNull()?.attr("content") 
            ?: doc.select("img.featured-image").firstOrNull()?.attr("src")

        // Parse all episode links in .entry-content a
        val episodes = mutableListOf<Episode>()
        var epCount = 1

        // Find all links whose text contains "Episode" or "Ep" followed by a number
        doc.select(".entry-content a").forEach { a ->
            val href = a.attr("href")
            val text = a.text()
            if (href.startsWith(mainUrl) && (text.contains("Episode", ignoreCase = true) || text.contains("Ep ", ignoreCase = true))) {
                // Extract episode number
                val epNum = Regex("""\b(?:Episode|Ep|E)\s*(\d+)\b""", RegexOption.IGNORE_CASE)
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull() ?: epCount++

                episodes.add(newEpisode(href) {
                    this.name = text
                    this.episode = epNum
                    this.season = 1
                })
            }
        }

        val uniqueEpisodes = episodes.distinctBy { it.data }.toMutableList()

        if (uniqueEpisodes.isEmpty()) {
            // If it's a single page (movie or single episode), add itself as the single episode
            uniqueEpisodes.add(newEpisode(url) {
                this.name = title
                this.episode = 1
                this.season = 1
            })
        } else {
            // Sort episodes by episode number
            uniqueEpisodes.sortBy { it.episode }
        }

        return newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = uniqueEpisodes
        ) {
            this.posterUrl = poster
            this.plot = doc.select(".entry-content p").firstOrNull()?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Fetch the episode page
        val doc = app.get(data, headers = headers).document

        // Locate any vidmoly embed URLs
        val iframes = doc.select("iframe")
        var found = false
        iframes.forEach { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } 
                ?: iframe.attr("data-litespeed-src").takeIf { it.isNotBlank() } 
                ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
            if (src != null && src.contains("vidmoly", ignoreCase = true)) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        // Also search for generic links in the content that might be vidmoly links
        doc.select(".entry-content a").forEach { a ->
            val href = a.attr("href")
            if (href.contains("vidmoly", ignoreCase = true)) {
                loadExtractor(href, mainUrl, subtitleCallback, callback)
                found = true
            }
        }

        return found
    }
}
