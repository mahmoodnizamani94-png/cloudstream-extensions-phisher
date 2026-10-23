package com.phisher98

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release-text subtitle fallback contract.
 *
 * SubtitleCat is the one independent catalogue the extension can query without an id, so it is the
 * source that still answers when the IMDb-keyed indexes miss a release. Because it ranks results by
 * bag-of-words relevance, every candidate has to be verified against the requested title *and*
 * episode before its English file is emitted - otherwise the app would download a perfectly
 * well-formed subtitle for a different show.
 */
class StreamPlaySubtitleSourceMatrixTest {

    @Test
    fun `queries use season form for tv and bare form for anime`() {
        assertEquals(
            listOf("Severance S02E05"),
            StreamPlayExtractor.buildSubtitleCatQueries(
                titles = listOf("Severance"),
                season = 2,
                episode = 5,
                isMovie = false,
                isAnime = false,
            )
        )
        assertEquals(
            listOf("Sousou no Frieren S02E05", "Sousou no Frieren 05"),
            StreamPlayExtractor.buildSubtitleCatQueries(
                titles = listOf("Sousou no Frieren"),
                season = 2,
                episode = 5,
                isMovie = false,
                isAnime = true,
            )
        )
        assertEquals(
            listOf("Sousou no Frieren 05"),
            StreamPlayExtractor.buildSubtitleCatQueries(
                titles = listOf("Sousou no Frieren"),
                season = null,
                episode = 5,
                isMovie = false,
                isAnime = true,
            )
        )
        assertEquals(
            listOf("The Matrix"),
            StreamPlayExtractor.buildSubtitleCatQueries(
                titles = listOf("The Matrix"),
                season = null,
                episode = null,
                isMovie = true,
                isAnime = false,
            )
        )
    }

    @Test
    fun `queries are deduplicated and capped so the fallback stays bounded`() {
        val queries = StreamPlayExtractor.buildSubtitleCatQueries(
            titles = listOf("Frieren", "Frieren", "Sousou no Frieren", "葬送のフリーレン"),
            season = null,
            episode = 3,
            isMovie = false,
            isAnime = true,
        )
        assertEquals(listOf("Frieren 03", "Sousou no Frieren 03"), queries)
        assertTrue(
            "An empty title list must not build a query",
            StreamPlayExtractor.buildSubtitleCatQueries(
                titles = listOf("", "   "),
                season = 1,
                episode = 1,
                isMovie = false,
                isAnime = false,
            ).isEmpty()
        )
    }

    @Test
    fun `search parsing keeps detail pages and rejects direct srt links`() {
        val body = """
            <table>
              <tr><td><a href="subs/1687/The.Matrix.1999.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265.html">The.Matrix.1999.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265</a> (translated from English)</td></tr>
              <tr><td><a href="/subs/1699/The.Matrix.1999.1080p.BluRay-en.srt">download</a></td></tr>
              <tr><td><a href="https://www.subtitlecat.com/subs/1030/The.Matrix.Resurrections.2021.2160p.html">The.Matrix.Resurrections.2021.2160p</a></td></tr>
              <tr><td><a href="/login.html">Sign in</a></td></tr>
            </table>
        """.trimIndent()

        val parsed = StreamPlayExtractor.parseSubtitleCatSearch(body)

        assertEquals(
            listOf(
                "https://www.subtitlecat.com/subs/1687/The.Matrix.1999.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265.html",
                "https://www.subtitlecat.com/subs/1030/The.Matrix.Resurrections.2021.2160p.html",
            ),
            parsed.map { it.pageUrl }
        )
        assertTrue(StreamPlayExtractor.parseSubtitleCatSearch("not html").isEmpty())
    }

    @Test
    fun `candidate selection requires the title and the episode`() {
        val candidates = listOf(
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/1352/Severance%20S02E05-eng.html",
                text = "Severance S02E05-eng"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/1044/Severance.2022.S02E05.ATVP.WEB-DL-NTb.en%5Bcc%5D.html",
                text = "Severance.2022.S02E05.ATVP.WEB-DL-NTb.en[cc]"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/1628/Vadhandhi.S02E05.TAMIL.WEB-DL.en.html",
                text = "Vadhandhi.S02E05.A.Severed.Hand.Points.South.TAMIL.AMZN.WEB-DL.H.264-RUDR.en"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/1400/Severance.S02E04.1080p.html",
                text = "Severance.S02E04.1080p.WEB.H264-SuccessfulCrab.eng"
            ),
        )

        val selected = StreamPlayExtractor.selectSubtitleCatCandidates(
            candidates = candidates,
            query = "Severance S02E05",
            season = 2,
            episode = 5,
        )

        assertEquals(1, selected.size)
        assertEquals("Severance S02E05-eng", selected.first().text)
    }

    @Test
    fun `candidate selection accepts alternate episode spellings`() {
        val candidates = listOf(
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/2000/Severance.2x05.1080p.html",
                text = "Severance.2x05.1080p.WEB-DL"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/2001/Severance.Season.2.Episode.5.html",
                text = "Severance Season 2 Episode 5 1080p WEB-DL"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/2002/Other.Show.2x05.html",
                text = "Other.Show.2x05.1080p.WEB-DL"
            ),
        )

        val selected = StreamPlayExtractor.selectSubtitleCatCandidates(
            candidates = candidates,
            query = "Severance S02E05",
            season = 2,
            episode = 5,
        )

        assertEquals(2, selected.size)
        assertTrue(selected.all { it.text.startsWith("Severance") })
    }

    @Test
    fun `candidate selection accepts anime numbering without accepting another episode`() {
        val candidates = listOf(
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/690/Frieren-01-480p.html",
                text = "[SubtitleTools.com] [Erai-raws] Sousou no Frieren - 01 [480p][Multiple Subtitle]_track3_[eng]"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/700/Frieren-02-1080p.html",
                text = "Sousou no Frieren - 02 [1080p][Multiple Subtitle]_track3_[eng]"
            ),
        )

        val selected = StreamPlayExtractor.selectSubtitleCatCandidates(
            candidates = candidates,
            query = "Sousou no Frieren 01",
            season = null,
            episode = 1,
        )

        assertEquals(1, selected.size)
        assertTrue(selected.first().text.contains("- 01"))
    }

    @Test
    fun `movie selection requires the release year`() {
        val candidates = listOf(
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/578/The%20MATRIX%20is%20COLLAPSING.en.html",
                text = "The MATRIX is COLLAPSING! Worldwide CHAOS as the DARK ONES lose control of TIMELINES! EARTH1111.en"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/265/Through%20the%20Wormhole%20S06E04.html",
                text = "Through the Wormhole S06E04 Do We Live in the Matrix"
            ),
            StreamPlayExtractor.SubtitleCatCandidate(
                pageUrl = "https://www.subtitlecat.com/subs/1687/The.Matrix.1999.1080p.BluRay.html",
                text = "The.Matrix.1999.1080p.BluRay.DDP5.1.x265.10bit-GalaxyRG265"
            ),
        )

        val selected = StreamPlayExtractor.selectSubtitleCatCandidates(
            candidates = candidates,
            query = "The Matrix",
            season = null,
            episode = null,
            year = 1999,
        )

        assertEquals(1, selected.size)
        assertTrue(selected.first().text.contains("1999"))
    }

    @Test
    fun `detail parsing returns the stable english srt only`() {
        val englishPage = """
            <div class="subs">
              <a href="/subs/1687/The.Matrix.1999.1080p.BluRay-sq.srt">Albanian</a>
              <a href="/subs/1699/The.Matrix.1999.1080p.BluRay-en.srt">English</a>
              <a href="/subs/1711/The.Matrix.1999.1080p.BluRay-fr.srt">French</a>
            </div>
        """.trimIndent()
        assertEquals(
            "https://www.subtitlecat.com/subs/1699/The.Matrix.1999.1080p.BluRay-en.srt",
            StreamPlayExtractor.parseSubtitleCatDetail(englishPage)
        )

        val nonEnglishPage = """
            <div class="subs">
              <a href="/subs/1687/Movie-sq.srt">Albanian</a>
              <a href="/subs/1711/Movie-es.srt">Spanish</a>
            </div>
        """.trimIndent()
        assertNull(StreamPlayExtractor.parseSubtitleCatDetail(nonEnglishPage))

        // Anime releases use spaces in the file name; the emitted URL must be valid as-is.
        val spacedPage = """
            <a href="/subs/1538/Sousou no Frieren_S2_01_1920_1080 eng-en.srt">English</a>
        """.trimIndent()
        assertEquals(
            "https://www.subtitlecat.com/subs/1538/Sousou%20no%20Frieren_S2_01_1920_1080%20eng-en.srt",
            StreamPlayExtractor.parseSubtitleCatDetail(spacedPage)
        )
    }

    @Test
    fun `episode matching rejects seasons and resolutions as episodes`() {
        assertTrue(StreamPlayExtractor.subtitleCatEpisodeMatches("severance s02e05", 2, 5))
        assertTrue(StreamPlayExtractor.subtitleCatEpisodeMatches("show 2x05", 2, 5))
        assertTrue(StreamPlayExtractor.subtitleCatEpisodeMatches("frieren 05", null, 5))
        assertTrue(!StreamPlayExtractor.subtitleCatEpisodeMatches("severance s02e15", 2, 5))
        assertTrue(!StreamPlayExtractor.subtitleCatEpisodeMatches("show 1080p web dl", null, 1))
    }
}
