package com.phisher98

import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.AnimePaheSearchHit
import com.phisher98.StreamPlayExtractor.AniZoneSearchHit
import com.phisher98.StreamPlayExtractor.animePaheParseKwikStream
import com.phisher98.StreamPlayExtractor.animePaheParsePlaySources
import com.phisher98.StreamPlayExtractor.animePaheParseReleasePage
import com.phisher98.StreamPlayExtractor.animePaheParseSearch
import com.phisher98.StreamPlayExtractor.aniZoneDecodeJsString
import com.phisher98.StreamPlayExtractor.aniZoneIsEnglishSubtitle
import com.phisher98.StreamPlayExtractor.aniZoneParsePlayer
import com.phisher98.StreamPlayExtractor.aniZoneParseSearchItems
import com.phisher98.StreamPlayExtractor.pickAnimePaheHit
import com.phisher98.StreamPlayExtractor.pickAniZoneHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser contracts for the two SOTA anime sources.
 *
 * The fixtures are the exact escape layout the live sites serve on 2026-10-23: AniZone embeds its
 * catalogues as `JSON.parse('...')` inside an Alpine `x-data` attribute with every quote written as
 * `\u0022` and every slash as `\\\/`, and AnimePahe serves plain JSON plus a Dean Edwards packed
 * Kwik embed. These tests fail if either site changes shape, which is what makes the sources safe
 * to keep in the curated registry.
 */
class StreamPlayAnimeSourceParsersTest {

    // ---------------------------------------------------------------------------------------
    // AniZone
    // ---------------------------------------------------------------------------------------

    private val aniZoneSearchHtml = """
        <div
            wire:id="3eyw0Ccj74bo7CX8Eect"
            x-data="{
                items: JSON.parse('[{\u0022slug\u0022:\u0022eu8bdgty\u0022,\u0022url\u0022:\u0022http:\\\/\\\/anizone.to\\\/anime\\\/eu8bdgty\u0022,\u0022cover\u0022:\u0022https:\\\/\\\/anizone.to\\\/images\\\/a.jpg\u0022,\u0022main_title\u0022:\u0022Ore dake Level Up na Ken\u0022,\u0022title_list\u0022:{\u00225\u0022:\u0022Ore dake Level Up na Ken\u0022,\u00228\u0022:\u0022\u4ffa\u3060\u3051\u30ec\u30d9\u30eb\u30a2\u30c3\u30d7\u306a\u4ef6\u0022,\u00221\u0022:\u0022Solo Leveling\u0022,\u002251\u0022:\u0022Na Honjaman Rebereop\u0022},\u0022start_year\u0022:2024,\u0022type\u0022:\u0022TV\u0022,\u0022episode_count\u0022:12},{\u0022slug\u0022:\u0022solo-leveling-reawakening\u0022,\u0022url\u0022:\u0022http:\\\/\\\/anizone.to\\\/anime\\\/solo-leveling-reawakening\u0022,\u0022main_title\u0022:\u0022Solo Leveling: ReAwakening\u0022,\u0022title_list\u0022:{},\u0022start_year\u0022:2024,\u0022type\u0022:\u0022Movie\u0022,\u0022episode_count\u0022:1}]')
            }">
        </div>
    """.trimIndent()

    private val aniZoneEpisodeHtml = """
        <div
            x-data="vidstackPlayer(JSON.parse('{\u0022src\u0022:\u0022https:\\\/\\\/suzaku.xin-cdn.xyz\\\/5593fe30\\\/master.m3u8\u0022,\u0022storage\u0022:\u0022v-5593fe30\u0022,\u0022subtitles\u0022:[{\u0022title\u0022:\u0022English - Forced\u0022,\u0022format\u0022:\u0022ass\u0022,\u0022language\u0022:\u0022en\u0022,\u0022default\u0022:false,\u0022forced\u0022:\u0022yes\u0022,\u0022file\u0022:\u0022https:\\\/\\\/suzaku.xin-cdn.xyz\\\/5593fe30\\\/subtitles\\\/0_en.ass\u0022},{\u0022title\u0022:\u0022English\u0022,\u0022format\u0022:\u0022srt\u0022,\u0022language\u0022:\u0022en\u0022,\u0022default\u0022:true,\u0022forced\u0022:\u0022no\u0022,\u0022file\u0022:\u0022https:\\\/\\\/suzaku.xin-cdn.xyz\\\/5593fe30\\\/subtitles\\\/1_en.srt\u0022},{\u0022title\u0022:\u0022English - SDH\u0022,\u0022format\u0022:\u0022srt\u0022,\u0022language\u0022:\u0022en\u0022,\u0022default\u0022:false,\u0022forced\u0022:\u0022no\u0022,\u0022file\u0022:\u0022https:\\\/\\\/suzaku.xin-cdn.xyz\\\/5593fe30\\\/subtitles\\\/2_en.srt\u0022},{\u0022title\u0022:\u0022Japanese\u0022,\u0022format\u0022:\u0022ass\u0022,\u0022language\u0022:\u0022ja\u0022,\u0022default\u0022:false,\u0022forced\u0022:\u0022no\u0022,\u0022file\u0022:\u0022https:\\\/\\\/suzaku.xin-cdn.xyz\\\/5593fe30\\\/subtitles\\\/3_ja.ass\u0022}]}'))">
        </div>
    """.trimIndent()

    @Test
    fun testAniZoneJsStringDecodeResolvesQuotesSlashesAndUnicodeEscapes() {
        assertEquals("{\"src\":\"https://a/b.m3u8\"}", aniZoneDecodeJsString("{\\u0022src\\u0022:\\u0022https:\\/\\/a\\/b.m3u8\\u0022}"))
        // The live attribute writes every URL slash as \\\/, which decodes to \/ for JSON.parse.
        assertEquals("https:\\/\\/a", aniZoneDecodeJsString("https:\\\\\\/\\\\\\/a"))
        // Unknown escapes are consumed once and never duplicated.
        assertEquals("ax", aniZoneDecodeJsString("a\\x"))
        assertEquals("", aniZoneDecodeJsString(""))
    }

    @Test
    fun testAniZoneSearchParsesEveryTitleVariantAndHonestMetadata() {
        val hits = aniZoneParseSearchItems(aniZoneSearchHtml)
        assertEquals(2, hits.size)

        val show = hits[0]
        assertEquals("eu8bdgty", show.slug)
        assertEquals("http://anizone.to/anime/eu8bdgty", show.url)
        assertEquals("Ore dake Level Up na Ken", show.title)
        assertEquals(2024, show.year)
        assertEquals("TV", show.mediaType)
        assertEquals(12, show.episodes)
        assertTrue("English alias must be parsed", show.aliases.contains("Solo Leveling"))
        assertTrue("Romaji alias must be parsed", show.aliases.contains("Na Honjaman Rebereop"))
        assertTrue("Native alias must survive \\u escapes", show.aliases.any { it.contains("\u4ffa\u3060\u3051") })

        assertEquals("Movie", hits[1].mediaType)
    }

    @Test
    fun testAniZoneSearchIgnoresUnrelatedAlpinePayloadsAndMalformedData() {
        assertEquals(emptyList<AniZoneSearchHit>(), aniZoneParseSearchItems("<div x-data=\"{ open: false }\"></div>"))
        assertEquals(emptyList<AniZoneSearchHit>(), aniZoneParseSearchItems("<div x-data=\"items: JSON.parse('not json')\"></div>"))
        assertEquals(emptyList<AniZoneSearchHit>(), aniZoneParseSearchItems(""))
    }

    @Test
    fun testAniZonePlayerParsesMasterUrlAndEveryTrackHonestly() {
        val player = aniZoneParsePlayer(aniZoneEpisodeHtml)
        assertNotNull(player)
        assertEquals("https://suzaku.xin-cdn.xyz/5593fe30/master.m3u8", player!!.streamUrl)
        assertEquals(4, player.subtitles.size)

        val forced = player.subtitles[0]
        assertEquals("English - Forced", forced.label)
        assertEquals("ass", forced.format)
        assertTrue(forced.forced)

        val plain = player.subtitles[1]
        assertEquals("https://suzaku.xin-cdn.xyz/5593fe30/subtitles/1_en.srt", plain.url)
        assertEquals("en", plain.language)
        assertEquals("srt", plain.format)
        assertEquals(false, plain.forced)
    }

    @Test
    fun testAniZonePlayerRejectsPagesWithoutAPlayablePayload() {
        assertNull(aniZoneParsePlayer("<div>nothing here</div>"))
        // A payload without a stream URL is not a playable episode.
        assertNull(aniZoneParsePlayer("""<div x-data="vidstackPlayer(JSON.parse('{\u0022src\u0022:\u0022\u0022}'))"></div>"""))
    }

    @Test
    fun testAniZoneEnglishSelectorKeepsPlainTrackAndDropsForcedAndSdh() {
        val player = aniZoneParsePlayer(aniZoneEpisodeHtml)!!
        val english = player.subtitles.filter(::aniZoneIsEnglishSubtitle)
        assertEquals(1, english.size)
        assertEquals("https://suzaku.xin-cdn.xyz/5593fe30/subtitles/1_en.srt", english.single().url)
        // Japanese is never emitted as an English sidecar.
        assertEquals(1, player.subtitles.count { it.language == "ja" })
    }

    @Test
    fun testAniZonePickUsesAliasesYearsAndMediaTypeInsteadOfResultOrder() {
        val hits = aniZoneParseSearchItems(aniZoneSearchHtml)

        // The series request must resolve the Romaji-titled entry by its English alias.
        val series = pickAniZoneHit(hits, listOf("Solo Leveling"), 2024, isMovie = false)
        assertEquals("eu8bdgty", series?.slug)

        // The movie request must resolve the movie entry, never episode 1 of the series.
        val movie = pickAniZoneHit(hits, listOf("Solo Leveling ReAwakening"), 2024, isMovie = true)
        assertEquals("solo-leveling-reawakening", movie?.slug)

        // An unrelated title is an honest miss rather than a wrong show.
        assertNull(pickAniZoneHit(hits, listOf("Frieren Beyond Journeys End"), 2023, isMovie = false))
    }

    // ---------------------------------------------------------------------------------------
    // AnimePahe
    // ---------------------------------------------------------------------------------------

    @Test
    fun testAnimePaheSearchParsesCatalogueHits() {
        val json = """
            {"total":1,"per_page":8,"data":[{"id":42,"title":"Solo Leveling","type":"TV","year":2024,"episodes":12,"session":"abc123","fansub":"SubsPlease"}]}
        """.trimIndent()
        val hits = animePaheParseSearch(json)
        assertEquals(1, hits.size)
        assertEquals("abc123", hits[0].session)
        assertEquals("Solo Leveling", hits[0].title)
        assertEquals(2024, hits[0].year)
        assertEquals("TV", hits[0].mediaType)
        assertEquals(12, hits[0].episodes)

        assertEquals(emptyList<AnimePaheSearchHit>(), animePaheParseSearch("not json"))
        assertEquals(emptyList<AnimePaheSearchHit>(), animePaheParseSearch("{\"data\":[]}"))
    }

    @Test
    fun testAnimePaheReleasePageMapsEveryEpisodeSessionAndPaging() {
        val json = """
            {"per_page":30,"last_page":2,"data":[{"episode":1,"session":"e1"},{"episode":2,"session":"e2"},{"episode":31,"session":"e31"}]}
        """.trimIndent()
        val page = animePaheParseReleasePage(json)
        assertNotNull(page)
        assertEquals(30, page!!.perPage)
        assertEquals(2, page.lastPage)
        assertEquals("e1", page.sessions[1])
        assertEquals("e31", page.sessions[31])
        assertNull(animePaheParseReleasePage("{\"data\":[]}")?.sessions?.get(3))
        assertNull(animePaheParseReleasePage("garbage"))
    }

    @Test
    fun testAnimePahePlaySourcesKeepDeclaredResolutionAndAudio() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.cx/e/abc" data-resolution="1080" data-audio="jpn" data-fansub="SubsPlease">1080p</button>
                <button data-src="https://kwik.si/e/def" data-resolution="720" data-audio="eng" data-fansub="SubsPlease">720p</button>
                <button data-src="https://kwik.cx/e/ghi" data-audio="jpn">auto</button>
            </div>
        """.trimIndent()
        val sources = animePaheParsePlaySources(html)
        assertEquals(3, sources.size)
        assertEquals(Qualities.P1080.value, sources[0].quality)
        assertEquals("jpn", sources[0].audio)
        assertTrue(sources[0].isOriginalAudio)
        assertEquals(Qualities.P720.value, sources[1].quality)
        assertTrue(sources[1].isDub)
        // No declared height: the quality stays Unknown instead of being invented.
        assertEquals(Qualities.Unknown.value, sources[2].quality)
        assertEquals("SubsPlease", sources[0].fansub)
    }

    @Test
    fun testAnimePahePlaySourcesDeduplicateAndIgnoreNonHttpButtons() {
        val html = """
            <div id="resolutionMenu">
                <button data-src="https://kwik.cx/e/abc" data-resolution="720" data-audio="jpn">720p</button>
                <button data-src="https://kwik.cx/e/abc" data-resolution="720" data-audio="jpn">720p</button>
                <button data-src="/play/relative" data-resolution="1080" data-audio="jpn">1080p</button>
            </div>
        """.trimIndent()
        val sources = animePaheParsePlaySources(html)
        assertEquals(1, sources.size)
        assertTrue(animePaheParsePlaySources("").isEmpty())
    }

    @Test
    fun testAnimePahePickHonoursTitleYearAndMediaType() {
        val hits = listOf(
            AnimePaheSearchHit("movie", "Solo Leveling: ReAwakening", 2024, "Movie", 1),
            AnimePaheSearchHit("series", "Solo Leveling", 2024, "TV", 12),
            AnimePaheSearchHit("old", "Solo Leveling", 2019, "TV", 12),
        )

        assertEquals("series", pickAnimePaheHit(hits, listOf("Solo Leveling"), 2024, isMovie = false)?.session)
        // A movie request must never accept a TV hit: episode 1 of an unrelated series is worse
        // than an honest miss.
        assertEquals("movie", pickAnimePaheHit(hits, listOf("Solo Leveling ReAwakening"), 2024, isMovie = true)?.session)
        assertNull(pickAnimePaheHit(hits.filter { it.mediaType != "Movie" }, listOf("Solo Leveling ReAwakening"), 2024, isMovie = true))
        assertNull(pickAnimePaheHit(hits, listOf("Frieren"), 2024, isMovie = false))
    }

    @Test
    fun testAnimePaheKwikHandlesAlreadyUnpackedMarkupAndUnescapesSlashes() {
        val unpacked = """
            <script>
            var source: 'https:\/\/vault-01.uwucdn.top\/stream\/abc\/uwu.m3u8';
            </script>
        """.trimIndent()
        assertEquals("https://vault-01.uwucdn.top/stream/abc/uwu.m3u8", animePaheParseKwikStream(unpacked))
        assertNull(animePaheParseKwikStream(""))
        assertNull(animePaheParseKwikStream("<html><body>File not found</body></html>"))
    }
}
