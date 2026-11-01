package com.phisher98

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UrduSubtitleSotaTest {

    @Before
    fun setUp() {
        System.clearProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP)
        UrduSubtitleEngine.consumeSubtitleCatOrigCandidates()
    }

    @After
    fun tearDown() {
        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        System.clearProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP)
        UrduSubtitleEngine.consumeSubtitleCatOrigCandidates()
    }

    @Test
    fun `Default English mode is active out of the box and switches to Urdu and back with zero latency`() {
        // 1. Out of the box (no system property or preferences set), Default (English) mode is active
        System.clearProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP)
        assertEquals(UrduSubtitleEngine.SubtitleMode.DEFAULT, UrduSubtitleEngine.getSubtitleMode())
        assertFalse("Default mode out-of-the-box must NOT be Urdu mode", UrduSubtitleEngine.isUrduMode())

        // 2. Switching to Urdu mode takes immediate effect
        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.URDU)
        assertEquals(UrduSubtitleEngine.SubtitleMode.URDU, UrduSubtitleEngine.getSubtitleMode())
        assertTrue("Expected isUrduMode() == true immediately after switching to URDU", UrduSubtitleEngine.isUrduMode())
        assertEquals("urdu", System.getProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP))

        // 3. Switching back to Default (English) mode takes immediate effect
        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        assertEquals(UrduSubtitleEngine.SubtitleMode.DEFAULT, UrduSubtitleEngine.getSubtitleMode())
        assertFalse("Expected isUrduMode() == false immediately after switching back to DEFAULT", UrduSubtitleEngine.isUrduMode())
        assertEquals("default", System.getProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP))
    }

    @Test
    fun `StreamPlayUtils recognizes Urdu language codes and labels`() {
        assertEquals("Urdu", getLanguage("ur"))
        assertEquals("Urdu", getLanguage("urd"))
        assertEquals("Urdu", getLanguage("ur-PK"))
        assertEquals("Urdu", getLanguage("ur-IN"))
        assertEquals("Urdu", getLanguage("Urdu"))
        assertEquals("Urdu", getLanguage("اردو"))

        val urduLabels = listOf(
            "Urdu",
            "urdu",
            "ur",
            "urd",
            "ur-PK",
            "ur-IN",
            "اردو",
            "Urdu (AI Translated)",
            "Urdu [Forced]",
            "Pakistani Urdu"
        )
        for (label in urduLabels) {
            assertTrue("Expected '$label' to be recognized as Urdu", isUrduSubtitleLabel(label))
        }

        val nonUrduLabels = listOf("English", "en", "eng", "Arabic", "ar", "Hindi", "hi", "", null)
        for (label in nonUrduLabels) {
            assertFalse("Expected '$label' NOT to be recognized as Urdu", isUrduSubtitleLabel(label))
        }
    }

    @Test
    fun `decodeAndNormalizeUrduBytes strips UTF-8 BOM and normalizes Arabic Yeh and Kaf to Urdu`() {
        // Contains Arabic Kaf (\u0643), Arabic Yeh (\u064A), and Alef Maksura (\u0649)
        val rawText = "1\r\n00:00:01,000 --> 00:00:03,000\r\n\u0643\u064A\u0627 \u0627\u0639\u0644\u0649 \u06C1\u06D2"
        val utf8WithBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            rawText.toByteArray(Charsets.UTF_8)

        val decoded = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(utf8WithBom)
        assertFalse("UTF-8 BOM must be stripped", decoded.startsWith("\uFEFF"))
        assertFalse("CRLF must be normalized to LF", decoded.contains("\r"))
        assertTrue(
            "Arabic Kaf (\\u0643) and Yeh (\\u064A/\\u0649) must be normalized to Urdu Keheh (\\u06A9) and Farsi Yeh (\\u06CC)",
            decoded.contains("\u06A9\u06CC\u0627 \u0627\u0639\u0644\u06CC \u06C1\u06D2")
        )
    }

    @Test
    fun `decodeAndNormalizeUrduBytes decodes UTF-16LE and UTF-16BE with and without BOM`() {
        val sample = "1\n00:00:01,000 --> 00:00:04,000\nمیں تم سے محبت کرتا ہوں"
        val utf16LeWithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            sample.toByteArray(Charsets.UTF_16LE)
        val utf16BeNoBom = sample.toByteArray(Charsets.UTF_16BE)

        val decodedLe = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(utf16LeWithBom)
        val decodedBe = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(utf16BeNoBom)

        assertEquals(sample, decodedLe)
        assertEquals(sample, decodedBe)
    }

    @Test
    fun `decodeAndNormalizeUrduBytes detects and decodes legacy Windows-1256 Urdu bytes`() {
        // Legacy Windows-1256 (CP1256) stores Yeh as 0xED (\u064A) alongside Urdu letters: پ ٹ چ ڈ ڑ ژ ک گ ں ہ ھ ے
        val cp1256RawInput = "1\n00:00:02,000 --> 00:00:05,000\nپاکستان ميں ٹرين چل رہي ہے، گاڑي اور پھول بڑي خوبصورت ہيں"
        val cp1256Bytes = cp1256RawInput.toByteArray(Charset.forName("windows-1256"))

        val decoded = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(cp1256Bytes)
        // Decoded output must both decode CP1256 Urdu letters AND normalize 0xED (\u064A) -> Urdu Farsi Yeh (\u06CC ی)
        assertTrue("Decoded CP1256 text must preserve Urdu Pe/Te/Che/Gaf/BariYeh", decoded.contains("پاکستان"))
        assertTrue(decoded.contains("ٹرین چل رہی ہے"))
        assertTrue(decoded.contains("گاڑی اور پھول بڑی خوبصورت ہیں"))
    }

    @Test
    fun `decodeAndNormalizeUrduBytes inflates GZIP and multi-entry ZIP skipping nfo and txt`() {
        val srtContent = "1\n00:00:01,000 --> 00:00:02,500\nیہ ایک اردو سب ٹائٹل ہے\n"

        // 1. GZIP payload
        val gzipOut = ByteArrayOutputStream()
        GZIPOutputStream(gzipOut).use { it.write(srtContent.toByteArray(Charsets.UTF_8)) }
        val decodedGzip = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(gzipOut.toByteArray())
        assertEquals(srtContent.trim(), decodedGzip.trim())

        // 2. Multi-entry ZIP with .nfo, .txt, and .srt inside a subdirectory
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("release_info.nfo"))
            zos.write("Release notes that must be ignored".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("Downloaded from subdl.com".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subs/Movie.2024.1080p.Urdu.srt"))
            zos.write(srtContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val decodedZip = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(zipOut.toByteArray())
        assertEquals(srtContent.trim(), decodedZip.trim())
    }

    @Test
    fun `sanitizeAndApplyRtlBidi strips ASS and HTML tags drops watermarks and wraps Urdu in RLM`() {
        val dirtyCue = "{\\an8}{\\pos(192,220)}<font color=\"#ffff00\"><i>کیا حال ہے؟</i></font>\nSecond English line"
        val sanitized = UrduSubtitleEngine.sanitizeAndApplyRtlBidi(dirtyCue)
        val lines = sanitized.split("\n")

        assertEquals(2, lines.size)
        assertEquals("\u200Fکیا حال ہے؟\u200F", lines[0])
        assertEquals("Second English line", lines[1])

        // Watermark / credit cues must be stripped completely
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Translated by @iamdesigora"))
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Subtitles by Black Gold"))
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Netflix سے ڈاؤن لوڈ کیا گیا"))
    }

    @Test
    fun `parseSubtitleCues and formatCuesAsUrduSrt normalize VTT timestamps and apply RTL wrapping`() {
        val rawVtt = """
            WEBVTT

            1
            00:00:01.250 --> 00:00:03.500 align:start position:0%
            Hello world!

            00:00:04.100 --> 00:00:06.800
            Subtitles by someone

            00:00:07.000 --> 00:00:09.900
            <i>How are you?</i>
        """.trimIndent()

        val cues = UrduSubtitleEngine.parseSubtitleCues(rawVtt)
        assertEquals("Watermark cue must be filtered out leaving 2 cues", 2, cues.size)
        assertEquals("00:00:01,250 --> 00:00:03,500", cues[0].timestamp)
        assertEquals("Hello world!", cues[0].text)
        assertEquals("00:00:07,000 --> 00:00:09,900", cues[1].timestamp)
        assertEquals("How are you?", cues[1].text)

        val translatedCues = listOf(
            UrduSubtitleEngine.SrtCue(1, cues[0].timestamp, "ہیلو دنیا!"),
            UrduSubtitleEngine.SrtCue(2, cues[1].timestamp, "آپ کیسے ہیں؟")
        )
        val formattedSrt = UrduSubtitleEngine.formatCuesAsUrduSrt(translatedCues)
        assertTrue(formattedSrt.contains("00:00:01,250 --> 00:00:03,500\n\u200Fہیلو دنیا!\u200F"))
        assertTrue(formattedSrt.contains("00:00:07,000 --> 00:00:09,900\n\u200Fآپ کیسے ہیں؟\u200F"))
    }

    @Test
    fun `parseYtsSubsRowsForLanguage parses both English and Urdu rows and builds yts-subs zip URLs`() {
        val html = """
            <table class="table other-subs">
              <tbody>
                <tr data-id="9001">
                  <td class=" rating-cell"><span class="label label-success">12</span></td>
                  <td class="flag-cell"><span class="sub-lang">English</span></td>
                  <td style="max-width: 500px;"><a href="/subtitles/inception-2010-english-yify-9001"><span class="text-muted">subtitle</span> Inception.2010.1080p.BluRay</a></td>
                  <td class="other-cell"><span class="hi-install">HI</span></td>
                </tr>
                <tr data-id="9002">
                  <td class=" rating-cell"><span class="label label-success">8</span></td>
                  <td class="flag-cell"><span class="sub-lang">English</span></td>
                  <td style="max-width: 500px;"><a href="/subtitles/inception-2010-english-yify-9002"><span class="text-muted">subtitle</span> Inception.2010.720p.BluRay</a></td>
                  <td class="other-cell"></td>
                </tr>
                <tr data-id="9003">
                  <td class=" rating-cell"><span class="label label-success">5</span></td>
                  <td class="flag-cell"><span class="sub-lang">Urdu</span></td>
                  <td style="max-width: 500px;"><a href="/subtitles/inception-2010-urdu-yify-9003"><span class="text-muted">subtitle</span> Inception.2010.1080p.Urdu</a></td>
                  <td class="other-cell"></td>
                </tr>
              </tbody>
            </table>
        """.trimIndent()

        val englishRows = StreamPlayExtractor.parseYtsSubsRowsForLanguage(html, "English")
        assertEquals(1, englishRows.size)
        assertEquals(
            "https://subtitles.yts-subs.com/subtitles/inception-2010-english-yify-9002.zip",
            englishRows[0].url
        )
        assertEquals(
            "https://www.yts-subs.com/subtitles/inception-2010-english-yify-9002",
            englishRows[0].referer
        )

        val urduRows = StreamPlayExtractor.parseYtsSubsRowsForLanguage(html, "Urdu")
        assertEquals(1, urduRows.size)
        assertEquals(
            "https://subtitles.yts-subs.com/subtitles/inception-2010-urdu-yify-9003.zip",
            urduRows[0].url
        )
        assertEquals(
            "https://www.yts-subs.com/subtitles/inception-2010-urdu-yify-9003",
            urduRows[0].referer
        )
    }

    @Test
    fun `parseSubtitleCatUrduDetail and parseSubtitleCatOrigSrt handle State A and State B`() {
        // State A: Pre-cached Urdu -ur.srt link on a different /subs/{id}/ directory
        val stateAHtml = """
            <div class="sub-single">
              <span>Urdu</span>
              <a id="download_ur" href="/subs/605/Interstellar.2014.1080p-ur.srt" class="green-link">Download</a>
            </div>
        """.trimIndent()
        assertEquals(
            "https://www.subtitlecat.com/subs/605/Interstellar.2014.1080p-ur.srt",
            StreamPlayExtractor.parseSubtitleCatUrduDetail(stateAHtml)
        )
        assertNull(StreamPlayExtractor.parseSubtitleCatOrigSrt(stateAHtml))

        // State B: Uncached Urdu button calling translate_from_server_folder('ur', ...)
        val stateBHtml = """
            <div class="sub-single">
              <span>Urdu</span>
              <button onclick="translate_from_server_folder('ur', 'Oppenheimer.2023.1080p-orig.srt', '/subs/1240/')" class="yellow-link">Translate</button>
            </div>
        """.trimIndent()
        assertNull(StreamPlayExtractor.parseSubtitleCatUrduDetail(stateBHtml))
        val origPair = StreamPlayExtractor.parseSubtitleCatOrigSrt(stateBHtml)
        assertNotNull(origPair)
        assertEquals(
            "https://www.subtitlecat.com/subs/1240/Oppenheimer.2023.1080p-orig.srt",
            origPair!!.first
        )
        assertEquals("Oppenheimer.2023.1080p-orig.srt", origPair.second)
    }

    @Test
    fun `buildOpenSubtitlesLegacyUrlsForLanguage builds urd and eng REST endpoints`() {
        val urdUrls = StreamPlayExtractor.buildOpenSubtitlesLegacyUrlsForLanguage(
            id = "tt0816692",
            season = null,
            episode = null,
            titles = listOf("Interstellar"),
            langId = "urd"
        )
        assertEquals(
            listOf(
                "https://rest.opensubtitles.org/search/imdbid-0816692/sublanguageid-urd",
                "https://rest.opensubtitles.org/search/query-Interstellar/sublanguageid-urd"
            ),
            urdUrls
        )

        val engSeriesUrls = StreamPlayExtractor.buildOpenSubtitlesLegacyUrlsForLanguage(
            id = "tt0903747",
            season = 1,
            episode = 3,
            titles = listOf("Breaking Bad"),
            langId = "eng"
        )
        assertEquals(
            listOf(
                "https://rest.opensubtitles.org/search/imdbid-0903747/season-1/episode-3/sublanguageid-eng",
                "https://rest.opensubtitles.org/search/episode-3/query-Breaking%20Bad/season-1/sublanguageid-eng"
            ),
            engSeriesUrls
        )
    }

    @Test
    fun `SubDL keyless autocomplete season ordinal and Urdu zip parser`() {
        assertEquals("first-season", UrduSubtitleEngine.subDlSeasonOrdinal(1))
        assertEquals("second-season", UrduSubtitleEngine.subDlSeasonOrdinal(2))
        assertEquals("tenth-season", UrduSubtitleEngine.subDlSeasonOrdinal(10))
        assertEquals("twentieth-season", UrduSubtitleEngine.subDlSeasonOrdinal(20))
        assertNull(UrduSubtitleEngine.subDlSeasonOrdinal(0))

        val autoJson = """
            {
              "results": [
                {
                  "sd_id": 45123,
                  "name": "Inception",
                  "type": "movie",
                  "link": "/subtitle/sd45123/inception"
                }
              ]
            }
        """.trimIndent()
        assertEquals("/subtitle/sd45123/inception", UrduSubtitleEngine.parseSubDlAutoSlug(autoJson))

        val subDlUrduHtml = """
            <div class="sub-row">
              <span>Breaking.Bad.S01E01.720p.BluRay.Urdu</span>
              <a href="https://dl.subdl.com/subtitle/3100001-8200001.zip">Download</a>
            </div>
            <div class="sub-row">
              <span>Breaking.Bad.S01E02.720p.BluRay.Urdu</span>
              <a href="https://dl.subdl.com/subtitle/3100002-8200002.zip">Download</a>
            </div>
        """.trimIndent()

        val ep1Zips = UrduSubtitleEngine.parseSubDlUrduPageZips(subDlUrduHtml, season = 1, episode = 1)
        assertEquals(listOf("https://dl.subdl.com/subtitle/3100001-8200001.zip"), ep1Zips)
    }

    @Test
    fun `SubSense Urdu JSON parser filters Urdu entries only`() {
        val json = """
            {
              "subtitles": [
                {"id": "1", "url": "https://subsense.nepiraw.com/dl/ur1.srt", "lang": "ur"},
                {"id": "2", "url": "https://subsense.nepiraw.com/dl/en1.srt", "lang": "en"},
                {"id": "3", "url": "https://subsense.nepiraw.com/dl/ur2.srt", "lang": "Urdu"}
              ]
            }
        """.trimIndent()

        val urls = UrduSubtitleEngine.parseSubSenseUrduJson(json)
        assertEquals(
            listOf(
                "https://subsense.nepiraw.com/dl/ur1.srt",
                "https://subsense.nepiraw.com/dl/ur2.srt"
            ),
            urls
        )
    }

    @Test
    fun `live end-to-end Urdu subtitle verification across 5 Hollywood movies and TV episodes`() {
        if (System.getenv("LIVE_URDU_E2E") != "1") return
        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.URDU)

        data class BenchmarkTitle(
            val label: String,
            val imdbId: String,
            val title: String,
            val year: Int,
            val season: Int? = null,
            val episode: Int? = null
        )

        val titles = listOf(
            BenchmarkTitle("Dune: Part Two (2024)", "tt15239678", "Dune: Part Two", 2024),
            BenchmarkTitle("The Dark Knight (2008)", "tt0468569", "The Dark Knight", 2008),
            BenchmarkTitle("Inception (2010)", "tt1375666", "Inception", 2010),
            BenchmarkTitle("House of the Dragon S01E01", "tt11198330", "House of the Dragon", 2022, season = 1, episode = 1),
            BenchmarkTitle("Severance S02E05", "tt11280740", "Severance", 2025, season = 2, episode = 5)
        )

        runBlocking {
            for (item in titles) {
                val collectedSubs = java.util.Collections.synchronizedList(mutableListOf<com.lagradost.cloudstream3.SubtitleFile>())
                UrduSubtitleEngine.consumeSubtitleCatOrigCandidates()

                coroutineScope {
                    listOf(
                        async {
                            UrduSubtitleEngine.invokeSubDlUrduKeyless(
                                imdbId = item.imdbId,
                                title = item.title,
                                season = item.season,
                                episode = item.episode,
                                year = item.year
                            ) { collectedSubs.add(it) }
                        },
                        async {
                            UrduSubtitleEngine.invokeSubSenseUrdu(
                                imdbId = item.imdbId,
                                season = item.season,
                                episode = item.episode
                            ) { collectedSubs.add(it) }
                        },
                        async {
                            UrduSubtitleEngine.invokeSubf2mUrdu(
                                title = item.title,
                                season = item.season,
                                episode = item.episode,
                                year = item.year,
                                isMovie = item.season == null
                            ) { collectedSubs.add(it) }
                        },
                        async {
                            StreamPlayExtractor.invokeOpenSubtitlesLegacy(
                                id = item.imdbId,
                                season = item.season,
                                episode = item.episode,
                                title = item.title,
                                subtitleCallback = { collectedSubs.add(it) }
                            )
                        },
                        async {
                            if (item.season == null) {
                                StreamPlayExtractor.invokeYifySubs(
                                    imdbId = item.imdbId,
                                    subtitleCallback = { collectedSubs.add(it) }
                                )
                            }
                        },
                        async {
                            StreamPlayExtractor.invokeSubtitleCat(
                                titles = listOf(item.title),
                                year = item.year,
                                season = item.season,
                                episode = item.episode,
                                isMovie = item.season == null,
                                subtitleCallback = { collectedSubs.add(it) }
                            )
                        }
                    ).awaitAll()
                }

                val urduSubs = collectedSubs.filter { isUrduSubtitleLabel(it.lang) }.distinctBy { it.url }.toMutableList()
                val englishUrls = collectedSubs.filter {
                    it.lang.startsWith("English", ignoreCase = true) || it.lang.equals("en", ignoreCase = true)
                }.map { it.url }.distinct()

                if (urduSubs.isEmpty() && englishUrls.isNotEmpty()) {
                    UrduSubtitleEngine.synthesizeUrduFallbackFromEnglish(englishUrls) { synth ->
                        urduSubs.add(synth)
                    }
                }

                val sources = urduSubs.map { it.headers?.get("X-StreamPlay-Source") ?: "catalog" }.distinct()
                println(
                    "LIVE_STREAMPLAY_URDU | ${item.label} (${item.imdbId}) | urduCount=${urduSubs.size} | " +
                        "englishCount=${englishUrls.size} | sources=$sources | sampleUrl=${urduSubs.firstOrNull()?.url}"
                )
                assertTrue("Expected at least 1 Urdu subtitle for ${item.label} (${item.imdbId})", urduSubs.isNotEmpty())
            }
        }
    }
}

