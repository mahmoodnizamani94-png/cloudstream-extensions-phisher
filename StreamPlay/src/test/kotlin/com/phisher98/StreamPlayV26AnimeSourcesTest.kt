package com.phisher98

import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser and matching contracts for the two anime sources of the current registry.
 *
 * These tests are deliberately network-free: they pin the exact payload shapes the extractors
 * consume (AnimePahe's JSON API + `#resolutionMenu` + Kwik packer, AniZone's Livewire
 * `items: JSON.parse('...')` catalogue and `vidstackPlayer(JSON.parse('...'))` episode payload).
 * The live endpoints are probed separately; a parser regression must fail here first.\n */
class StreamPlayV26AnimeSourcesTest {

    // =========================================================================================
    // AnimePahe: search / release / play page / Kwik
    // =========================================================================================

    private val animePaheSearchJson = """
        {
          "total": 3,
          "data": [
            {"id": 1, "slug": "sousou-no-frieren", "title": "Sousou no Frieren", "type": "TV", "episodes": 28, "year": 2023, "session": "frieren-romaji"},
            {"id": 2, "slug": "frieren-beyond-journeys-end", "title": "Frieren: Beyond Journey's End", "type": "TV", "episodes": 28, "year": 2023, "session": "frieren-english"},
            {"id": 3, "slug": "frieren-movie", "title": "Sousou no Frieren Movie", "type": "Movie", "episodes": 1, "year": 2023, "session": "frieren-movie"}
          ]
        }
    """.trimIndent()

    @Test
    fun `animepahe search keeps only well-formed hits`() {
        val hits = StreamPlayExtractor.animePaheParseSearch(animePaheSearchJson)
        assertEquals(3, hits.size)
        assertEquals("frieren-romaji", hits[0].session)
        assertEquals("Sousou no Frieren", hits[0].title)
        assertEquals(2023, hits[0].year)
        assertEquals("TV", hits[0].mediaType)
        assertEquals(28, hits[0].episodes)

        assertTrue(StreamPlayExtractor.animePaheParseSearch("not json").isEmpty())
        assertTrue(StreamPlayExtractor.animePaheParseSearch("""{"total":0}""").isEmpty())
        assertTrue(
            StreamPlayExtractor.animePaheParseSearch(
                """{"data":[{"session":"","title":"No session"},{"session":"ok","title":"  "},{"session":"valid","title":"Valid"}]}"""
            ).map { it.session } == listOf("valid")
        )
    }

    @Test
    fun `animepahe release pages map episode numbers to play sessions`() {
        val page = StreamPlayExtractor.animePaheParseReleasePage(
            """
            {
              "total": 45,
              "per_page": 30,
              "current_page": 1,
              "last_page": 2,
              "data": [
                {"episode": 1, "session": "ep-1"},
                {"episode": 2, "session": "ep-2"},
                {"episode": 30, "session": "ep-30"},
                {"episode": 0, "session": "special"},
                {"episode": 31, "session": ""}
              ]
            }
            """.trimIndent()
        )
        assertNotNull(page)
        assertEquals(30, page!!.perPage)
        assertEquals(2, page.lastPage)
        assertEquals("ep-1", page.sessions[1])
        assertEquals("ep-30", page.sessions[30])
        assertNull("specials are not episode numbers", page.sessions[0])
        assertNull("blank sessions are not playable", page.sessions[31])
        assertNull(StreamPlayExtractor.animePaheParseReleasePage("{}"))
    }

    @Test
    fun `animepahe play page reads provider resolution audio and fansub`() {
        val sources = StreamPlayExtractor.animePaheParsePlaySources(
            """
            <html><body>
              <div id="resolutionMenu">
                <button class="dropdown-item" data-src="https://kwik.cx/e/jpn1080"
                        data-resolution="1080" data-audio="jpn" data-fansub="SubsPlease">SubsPlease · 1080p <span>jpn</span></button>
                <button class="dropdown-item" data-src="https://kwik.cx/e/eng720"
                        data-resolution="720" data-audio="eng">English · 720p <span>eng</span></button>
                <button class="dropdown-item" data-src="https://kwik.cx/e/labelonly"
                        data-audio="jpn">Audio only · 480p</button>
                <button class="dropdown-item" data-src="javascript:void(0)" data-resolution="1080">Broken</button>
              </div>
            </body></html>
            """.trimIndent()
        )

        assertEquals(3, sources.size)
        assertEquals(Qualities.P1080.value, sources[0].quality)
        assertEquals("jpn", sources[0].audio)
        assertEquals("SubsPlease", sources[0].fansub)
        assertTrue(sources[0].isOriginalAudio)
        assertFalse(sources[0].isDub)

        assertEquals(Qualities.P720.value, sources[1].quality)
        assertTrue(sources[1].isDub)
        assertFalse(sources[1].isOriginalAudio)

        // No data-resolution: the explicit `480p` label is the only honest source of quality.
        assertEquals(Qualities.P480.value, sources[2].quality)
        assertTrue(sources[2].isOriginalAudio)

        assertTrue(StreamPlayExtractor.animePaheParsePlaySources("<html></html>").isEmpty())
    }

    @Test
    fun `animepahe kwik parser finds the manifest inside packed or plain scripts`() {
        val plain = StreamPlayExtractor.animePaheParseKwikStream(
            """<html><script>const source = 'https://vault-01.uwucdn.top/stream/abc/uwu.m3u8';</script></html>"""
        )
        assertEquals("https://vault-01.uwucdn.top/stream/abc/uwu.m3u8", plain)

        val escaped = StreamPlayExtractor.animePaheParseKwikStream(
            """<html><script>sources:[{file:"https:\/\/suzaku.xin-cdn.xyz\/abc\/master.m3u8"}]</script></html>"""
        )
        assertEquals("https://suzaku.xin-cdn.xyz/abc/master.m3u8", escaped)

        assertNull(StreamPlayExtractor.animePaheParseKwikStream("<html><script>const source = null;</script></html>"))
        assertNull(StreamPlayExtractor.animePaheParseKwikStream(""))
    }

    @Test
    fun `animepahe hit selection prefers the exact title over a sequel with a matching year`() {
        val hits = StreamPlayExtractor.animePaheParseSearch(animePaheSearchJson)
        val exact = StreamPlayExtractor.pickAnimePaheHit(
            hits,
            listOf("Frieren: Beyond Journey's End"),
            year = 2023,
            isMovie = false
        )
        assertEquals("frieren-english", exact?.session)

        val romaji = StreamPlayExtractor.pickAnimePaheHit(
            hits,
            listOf("Sousou no Frieren"),
            year = 2023,
            isMovie = false
        )
        assertEquals("frieren-romaji", romaji?.session)

        val movie = StreamPlayExtractor.pickAnimePaheHit(
            hits,
            listOf("Sousou no Frieren Movie"),
            year = 2023,
            isMovie = true
        )
        assertEquals("frieren-movie", movie?.session)

        assertNull(
            "an unrelated title must not clear the match floor",
            StreamPlayExtractor.pickAnimePaheHit(hits, listOf("Bleach"), year = 2023, isMovie = false)
        )
        assertNull(
            "a movie request must not fall back to the TV entry",
            StreamPlayExtractor.pickAnimePaheHit(
                hits.filter { it.mediaType != "Movie" },
                listOf("Sousou no Frieren Movie"),
                year = 2023,
                isMovie = true
            )
        )
    }

    @Test
    fun `title matching is normalized and never lets a year bonus beat an exact title`() {
        assertEquals(100, StreamPlayExtractor.animeTitleMatchScore("Sousou no Frieren", "Sousou no Frieren 2nd Season"))
        assertEquals(82, StreamPlayExtractor.animeTitleMatchScore("Frieren", "Frieren: Beyond Journey's End"))
        assertEquals(70, StreamPlayExtractor.animeTitleMatchScore("Frieren", "Sousou no Frieren"))
        assertEquals(0, StreamPlayExtractor.animeTitleMatchScore("Bleach", "Sousou no Frieren"))
        assertEquals(0, StreamPlayExtractor.animeTitleMatchScore("", "Sousou no Frieren"))

        // The year is a tiebreaker only: an exact title with a year mismatch must still win over a
        // loose partial match with the exact year.
        val exactButDifferentYear = StreamPlayExtractor.AnimePaheSearchHit(
            session = "exact",
            title = "Frieren: Beyond Journey's End",
            year = 2010,
            mediaType = "TV",
            episodes = 28
        )
        val partialWithExactYear = StreamPlayExtractor.AnimePaheSearchHit(
            session = "partial",
            title = "Frieren: Beyond Journey's End - Specials",
            year = 2023,
            mediaType = "TV",
            episodes = 28
        )
        val picked = StreamPlayExtractor.pickAnimePaheHit(
            listOf(partialWithExactYear, exactButDifferentYear),
            listOf("Frieren: Beyond Journey's End"),
            year = 2023,
            isMovie = false
        )
        assertEquals("exact", picked?.session)
    }

    @Test
    fun `declared anime quality is mapped without inventing resolutions`() {
        assertEquals(Qualities.P720.value, StreamPlayExtractor.animeDeclaredQuality(720))
        assertEquals(Qualities.P1080.value, StreamPlayExtractor.animeDeclaredQuality(1080))
        assertEquals(Qualities.P480.value, StreamPlayExtractor.animeDeclaredQuality(480))
        assertEquals(Qualities.P2160.value, StreamPlayExtractor.animeDeclaredQuality(2160))
        assertNull(StreamPlayExtractor.animeDeclaredQuality(null))
        assertNull(StreamPlayExtractor.animeDeclaredQuality(0))
        assertNull(StreamPlayExtractor.animeDeclaredQuality(100))
        assertNull(StreamPlayExtractor.animeDeclaredQuality(9999))
    }

    // =========================================================================================
    // AniZone: catalogue payload / player payload / subtitle selection
    // =========================================================================================

    @Test
    fun `anizone js string decode resolves unicode escapes and slashes exactly once`() {
        assertEquals("""[{"a":"b/c"}]""", StreamPlayExtractor.aniZoneDecodeJsString("""[{\u0022a\u0022:\u0022b\/c\u0022}]"""))
        assertEquals("line\nbreak", StreamPlayExtractor.aniZoneDecodeJsString("""line\nbreak"""))
        assertEquals("""quote " and ' apostrophe""", StreamPlayExtractor.aniZoneDecodeJsString("""quote \u0022 and \' apostrophe"""))
        assertEquals("", StreamPlayExtractor.aniZoneDecodeJsString(""))
    }

    @Test
    fun `anizone catalogue parses every localized title and the release year`() {
        val html = """
            <html><body>
              <div x-data="{ items: JSON.parse('[{\u0022slug\u0022:\u0022eu8bdgty\u0022,\u0022url\u0022:\u0022https:\/\/anizone.to\/anime\/eu8bdgty\u0022,\u0022main_title\u0022:\u0022Solo Leveling\u0022,\u0022title_list\u0022:{\u00221\u0022:\u0022Solo Leveling\u0022,\u00222\u0022:\u0022Ore dake Level Up na Ken\u0022},\u0022start_year\u0022:2024,\u0022type\u0022:\u0022TV\u0022,\u0022episode_count\u0022:12}]') }"></div>
            </body></html>
        """.trimIndent()

        val hits = StreamPlayExtractor.aniZoneParseSearchItems(html)
        assertEquals(1, hits.size)
        assertEquals("eu8bdgty", hits[0].slug)
        assertEquals("https://anizone.to/anime/eu8bdgty", hits[0].url)
        assertEquals("Solo Leveling", hits[0].title)
        assertEquals(listOf("Solo Leveling", "Ore dake Level Up na Ken"), hits[0].aliases)
        assertEquals(2024, hits[0].year)
        assertEquals("TV", hits[0].mediaType)
        assertEquals(12, hits[0].episodes)

        assertTrue(StreamPlayExtractor.aniZoneParseSearchItems("<html></html>").isEmpty())
    }

    @Test
    fun `anizone episode payload yields the original-audio manifest and its sidecars`() {
        val html = """
            <html><body>
              <div x-data="vidstackPlayer(JSON.parse('{\u0022src\u0022:\u0022https://suzaku.xin-cdn.xyz/abc/master.m3u8\u0022,\u0022subtitles\u0022:[{\u0022title\u0022:\u0022English\u0022,\u0022format\u0022:\u0022srt\u0022,\u0022language\u0022:\u0022en\u0022,\u0022forced\u0022:\u0022no\u0022,\u0022file\u0022:\u0022https://suzaku.xin-cdn.xyz/abc/subtitles/1_en.srt\u0022},{\u0022title\u0022:\u0022English - Forced\u0022,\u0022format\u0022:\u0022ass\u0022,\u0022language\u0022:\u0022en\u0022,\u0022forced\u0022:\u0022yes\u0022,\u0022file\u0022:\u0022https://suzaku.xin-cdn.xyz/abc/subtitles/0_en.ass\u0022},{\u0022title\u0022:\u0022English - SDH\u0022,\u0022format\u0022:\u0022srt\u0022,\u0022language\u0022:\u0022en\u0022,\u0022forced\u0022:\u0022no\u0022,\u0022file\u0022:\u0022https://suzaku.xin-cdn.xyz/abc/subtitles/2_en.srt\u0022}]}'))"></div>
            </body></html>
        """.trimIndent()

        val player = StreamPlayExtractor.aniZoneParsePlayer(html)
        assertNotNull(player)
        assertEquals("https://suzaku.xin-cdn.xyz/abc/master.m3u8", player!!.streamUrl)
        assertEquals(3, player.subtitles.size)

        val english = player.subtitles.filter(StreamPlayExtractor::aniZoneIsEnglishSubtitle)
        assertEquals(1, english.size)
        assertEquals("https://suzaku.xin-cdn.xyz/abc/subtitles/1_en.srt", english.single().url)
        assertEquals("srt", english.single().format)

        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(player.subtitles[1]))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(player.subtitles[2]))
        assertNull(StreamPlayExtractor.aniZoneParsePlayer("<html></html>"))
    }

    @Test
    fun `anizone hit selection matches aliases and respects the media type`() {
        val hits = listOf(
            StreamPlayExtractor.AniZoneSearchHit(
                slug = "solo",
                url = "https://anizone.to/anime/solo",
                title = "Ore dake Level Up na Ken",
                aliases = listOf("Solo Leveling"),
                year = 2024,
                mediaType = "TV",
                episodes = 12
            ),
            StreamPlayExtractor.AniZoneSearchHit(
                slug = "solo-movie",
                url = "https://anizone.to/anime/solo-movie",
                title = "Solo Leveling Movie",
                aliases = emptyList(),
                year = 2024,
                mediaType = "Movie",
                episodes = 1
            )
        )

        val series = StreamPlayExtractor.pickAniZoneHit(hits, listOf("Solo Leveling"), year = 2024, isMovie = false)
        assertEquals("solo", series?.slug)

        val movie = StreamPlayExtractor.pickAniZoneHit(hits, listOf("Solo Leveling Movie"), year = 2024, isMovie = true)
        assertEquals("solo-movie", movie?.slug)

        assertNull(StreamPlayExtractor.pickAniZoneHit(hits, listOf("Bleach"), year = 2024, isMovie = false))
        assertNull(
            "a movie request must not fall back to a TV entry",
            StreamPlayExtractor.pickAniZoneHit(
                hits.filter { it.mediaType != "Movie" },
                listOf("Solo Leveling Movie"),
                year = 2024,
                isMovie = true
            )
        )
    }

    @Test
    fun `anizone subtitle selection is English-only non-forced and non-SDH`() {
        fun track(title: String, language: String? = "en", forced: Boolean = false, isDefault: Boolean = false) =
            StreamPlayExtractor.AniZoneSubtitleTrack(
                label = title,
                language = language,
                format = "srt",
                forced = forced,
                url = "https://suzaku.xin-cdn.xyz/subtitles/$title.srt",
                isDefault = isDefault
            )

        assertTrue(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English")))
        assertTrue(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English (US)")))
        assertTrue(
            "Default full-dialogue English tracks mistakenly flagged forced=yes (like Frieren S01E01 3_en.ass) must be accepted",
            StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English (US)", forced = true, isDefault = true))
        )
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English - Forced", forced = true, isDefault = true)))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English - Forced", forced = true)))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English - SDH")))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English (US) (CC)")))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("English - Hearing Impaired")))
        assertFalse(StreamPlayExtractor.aniZoneIsEnglishSubtitle(track("Japanese", language = "ja")))

        assertEquals(
            "English",
            StreamPlayExtractor.aniZoneNormalizeEnglishLabel(track("Full Subtitles [Kaizoku]", "en"))
        )
        assertEquals(
            "English (US)",
            StreamPlayExtractor.aniZoneNormalizeEnglishLabel(track("English (US)", "en"))
        )
    }

    @Test
    fun `animepahe play page parses explicit MB sizes from pickDownload menu`() {
        val html = """
            <html><body>
              <div id="resolutionMenu">
                <button data-src="https://kwik.cx/e/sub720" data-resolution="720" data-audio="jpn" data-fansub="SubsPlease">SubsPlease · 720p</button>
                <button data-src="https://kwik.cx/e/sub1080" data-resolution="1080" data-audio="jpn" data-fansub="SubsPlease">SubsPlease · 1080p</button>
              </div>
              <div id="pickDownload">
                <a href="https://pahe.win/dl720" class="dropdown-item">SubsPlease · 720p (64 MB)</a>
                <a href="https://pahe.win/dl1080" class="dropdown-item">SubsPlease · 1080p (138 MB)</a>
              </div>
            </body></html>
        """.trimIndent()

        val sources = StreamPlayExtractor.animePaheParsePlaySources(html)
        assertEquals(2, sources.size)
        assertEquals(64, sources.first { it.quality == Qualities.P720.value }.sizeMb)
        assertEquals(138, sources.first { it.quality == Qualities.P1080.value }.sizeMb)
    }

    @Test
    fun `kaa direct fallback parses search episodes and cat-player props with English subtitles`() {
        val searchJson = """[
            {"slug":"frieren-beyond-journeys-end-f638","title":"Sousou no Frieren","title_en":"Frieren: Beyond Journey's End","year":2023,"type":"TV"}
        ]"""
        val hits = StreamPlayExtractor.kaaParseSearch(searchJson)
        assertEquals(1, hits.size)
        val picked = StreamPlayExtractor.pickKaaHit(hits, listOf("Frieren: Beyond Journey's End"), 2023, isMovie = false)
        assertNotNull(picked)
        assertEquals("frieren-beyond-journeys-end-f638", picked!!.slug)

        val episodesJson = """{
            "pages": [{"number": 1}],
            "result": [
                {"episode_number": 1, "slug": "90bf29"},
                {"episode_number": 2, "slug": "a12bc3"}
            ]
        }"""
        assertEquals("90bf29", StreamPlayExtractor.kaaParseEpisodeSlug(episodesJson, 1))

        val episodeDetailJson = """{
            "servers": [
                {"name": "VidStreaming", "shortName": "Vid", "src": "https://krussdomi.com/vidstreaming/player.php?id=6516fd5ad9835969ff592f7b&ln=ja-JP"}
            ]
        }"""
        val subUrls = StreamPlayExtractor.kaaParseEpisodeCatPlayerUrls(episodeDetailJson)
        assertEquals(listOf("https://krussdomi.com/cat-player/player?id=6516fd5ad9835969ff592f7b"), subUrls)

        val catHtml = """
            <html><body>
            <astro-island props='{&quot;manifest&quot;:[0,&quot;//hls.krussdomi.com/manifest/6516fd5ad9835969ff592f7b/master.m3u8&quot;],&quot;subtitles&quot;:[1,[[0,{&quot;language&quot;:[0,&quot;en&quot;],&quot;name&quot;:[0,&quot;English&quot;],&quot;src&quot;:[0,&quot;//subst.krussdomi.com/subtitle/6516fd5ad9835969ff592f7b/en.vtt&quot;]}],[0,{&quot;language&quot;:[0,&quot;es&quot;],&quot;label&quot;:[0,&quot;Spanish&quot;],&quot;src&quot;:[0,&quot;//subst.krussdomi.com/subtitle/6516fd5ad9835969ff592f7b/es.vtt&quot;]}]]]}'></astro-island>
            </body></html>
        """.trimIndent()
        val parsed = StreamPlayExtractor.kaaParseCatPlayerHtml(catHtml)
        assertNotNull(parsed)
        assertEquals("https://hls.krussdomi.com/manifest/6516fd5ad9835969ff592f7b/master.m3u8", parsed!!.sources?.firstOrNull()?.url)
        assertEquals(2, parsed.tracks?.size)
        val enTrack = parsed.tracks!!.first { it.lang == "en" }
        assertEquals("English", enTrack.label)
        assertEquals("https://subst.krussdomi.com/subtitle/6516fd5ad9835969ff592f7b/en.vtt", enTrack.url)

        assertEquals("Filipino", getLanguage("fil"))
    }

    @Test
    fun `animex plyr slug extraction skips provider names and maps subtitle provenance accurately`() {
        val plyrHtml = """
            <script>
            self.__next_f.push([1,"{\"initialProvider\":\"nero\",\"episodeId\":\"dan-da-dan-khp5m-episode-1\",\"animeSlug\":\"dan-da-dan-khp5m\"}"])
            </script>
        """.trimIndent()
        assertEquals("dan-da-dan-khp5m", StreamPlayExtractor.animexExtractPlyrSlug(plyrHtml))

        assertEquals("zokoanime", StreamPlayExtractor.animexSubtitleProvenance("zuna"))
        assertEquals("kickassanime", StreamPlayExtractor.animexSubtitleProvenance("sora"))
        assertEquals("kickassanime", StreamPlayExtractor.animexSubtitleProvenance("kaa"))
        assertEquals("animex", StreamPlayExtractor.animexSubtitleProvenance("yuki"))
        assertEquals("animex", StreamPlayExtractor.animexSubtitleProvenance("loli"))
    }

    @Test
    fun `opensubtitles legacy episode filter rejects wrong episodes and builds episode-scoped URLs`() {
        val urls = StreamPlayExtractor.buildOpenSubtitlesLegacyUrls(
            id = "tt21209876",
            season = 1,
            episode = 3,
            titles = listOf("Frieren: Beyond Journey's End")
        )
        assertTrue(urls.first().contains("/imdbid-21209876/season-1/episode-3/sublanguageid-eng"))
        assertTrue(urls[1].contains("/episode-3/query-Frieren%3A%20Beyond%20Journey%27s%20End/season-1/sublanguageid-eng"))

        val ep1Sub = OpenSubtitlesLegacySubtitle(
            downloadLink = "https://dl.opensubtitles.org/en/download/src-api/vrf-1/file/111.gz",
            languageName = "English",
            seriesSeason = "1",
            seriesEpisode = "1",
            movieKind = "episode"
        )
        val ep3Sub = OpenSubtitlesLegacySubtitle(
            downloadLink = "https://dl.opensubtitles.org/en/download/src-api/vrf-1/file/333.gz",
            languageName = "English",
            seriesSeason = "1",
            seriesEpisode = "3",
            movieKind = "episode"
        )
        val movieSub = OpenSubtitlesLegacySubtitle(
            downloadLink = "https://dl.opensubtitles.org/en/download/src-api/vrf-1/file/999.gz",
            languageName = "English",
            seriesSeason = "0",
            seriesEpisode = "0",
            movieKind = "movie"
        )

        assertFalse(StreamPlayExtractor.openSubtitlesEpisodeMatches(ep1Sub, season = 1, episode = 3))
        assertTrue(StreamPlayExtractor.openSubtitlesEpisodeMatches(ep3Sub, season = 1, episode = 3))
        assertFalse(StreamPlayExtractor.openSubtitlesEpisodeMatches(movieSub, season = 1, episode = 3))
        assertTrue(StreamPlayExtractor.openSubtitlesEpisodeMatches(movieSub, season = null, episode = null))
    }

    @Test
    fun `megaplay offline AES-CBC decryption and HMAC-SHA256 CDN token signing work without enc-dec app`() {
        val rawUrl = "https://fetch.nexabloom.top/anime/bb6d2babd7797d94d8f4a8600bc9b44e/b7d51fb7e838ee9b60dcdb34b953bc07/master.m3u8"
        val plainJson = """{"file":"$rawUrl"}"""

        val keyBytes = ByteArray(32)
        val prefix = "i?LMTAx0Q6,:}50U".toByteArray(Charsets.UTF_8)
        System.arraycopy(prefix, 0, keyBytes, 0, prefix.size)
        val ivBytes = "W0;27ToaUpl_P%'c".toByteArray(Charsets.UTF_8)

        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
            javax.crypto.spec.IvParameterSpec(ivBytes)
        )
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8)))

        val decryptedSigned = StreamPlayExtractor.megaplayDecryptEnc(
            enc = enc,
            ttlSeconds = 14_400L,
            nowEpochSec = 1_700_000_000L
        )
        assertNotNull(decryptedSigned)
        assertTrue(decryptedSigned!!.startsWith("$rawUrl?token="))

        val tokenPart = decryptedSigned.substringAfter("?token=")
        val segments = tokenPart.split(".")
        assertEquals(2, segments.size)
        val decodedPayload = String(java.util.Base64.getUrlDecoder().decode(segments[0]), Charsets.UTF_8)
        assertEquals(
            "1700014400|bb6d2babd7797d94d8f4a8600bc9b44e/b7d51fb7e838ee9b60dcdb34b953bc07",
            decodedPayload
        )
    }

    @Test
    fun `meowtv offline WASM keystream decrypts live api meowtv ru payload without enc-dec app`() {
        val n = "600b0f7173992381de71bfcf1b4e1e2c"
        val expectedPlain = """{"language":"Auto","url":"https://cdn2.fastcloud.live/movie/550/data/en/ae96bd4699e081987444c6df26a87cb7/1080/bigtits.m3u8","headers":{}}"""
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("10eE3wyyhkF38rwLFwisRYgwP2bbTL_Y" + n).toByteArray(Charsets.UTF_8))
        val plainBytes = expectedPlain.toByteArray(Charsets.UTF_8)
        val cipherBytes = ByteArray(plainBytes.size) { i ->
            (plainBytes[i].toInt() xor key[i and 31].toInt()).toByte()
        }
        val d = java.util.Base64.getEncoder().encodeToString(cipherBytes)

        val res = StreamPlayExtractor.meowTvDecryptOffline(n, d)
        assertNotNull(res)
        assertEquals("Auto", res!!.language)
        assertEquals(
            "https://cdn2.fastcloud.live/movie/550/data/en/ae96bd4699e081987444c6df26a87cb7/1080/bigtits.m3u8",
            res.url
        )
        assertNull(StreamPlayExtractor.meowTvDecryptOffline("", d))
        assertNull(StreamPlayExtractor.meowTvDecryptOffline(n, "not-valid-cipher"))
    }

    @Test
    fun `animeSourceTitles prepends resolved cour title and season-qualified names for Season 2+`() {
        val s2Link = StreamPlay.LinkData(
            id = 95479,
            title = "Jujutsu Kaisen",
            jpTitle = "Jujutsu Kaisen",
            season = 2,
            episode = 3,
            isAnime = true
        )
        val candidates = s2Link.animeSourceTitles(resolvedSeasonTitle = "Jujutsu Kaisen 2nd Season")
        assertEquals("Jujutsu Kaisen 2nd Season", candidates[0])
        assertEquals("Jujutsu Kaisen Season 2", candidates[1])
        assertEquals("Jujutsu Kaisen", candidates[2])

        val s1Link = StreamPlay.LinkData(
            id = 95479,
            title = "Jujutsu Kaisen",
            jpTitle = "Sorcery Fight",
            season = 1,
            episode = 3,
            isAnime = true
        )
        assertEquals(listOf("Jujutsu Kaisen", "Sorcery Fight"), s1Link.animeSourceTitles())
    }
}
