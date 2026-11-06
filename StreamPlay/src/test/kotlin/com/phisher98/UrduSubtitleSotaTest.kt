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
    fun `sanitizeAndApplyRtlBidi strips unsupported ASS and HTML tags preserves inline tags drops watermarks and wraps Urdu in RLM`() {
        val dirtyCue = "{\\an8}{\\pos(192,220)}<font color=\"#ffff00\"><i>کیا حال ہے؟</i></font>\nSecond English line"
        val sanitized = UrduSubtitleEngine.sanitizeAndApplyRtlBidi(dirtyCue)
        val lines = sanitized.split("\n")

        assertEquals(2, lines.size)
        assertEquals("\u200F{\\an8}<i>کیا حال ہے؟</i>\u200F", lines[0])
        assertEquals("Second English line", lines[1])

        // Watermark / credit cues must be stripped completely
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Translated by @iamdesigora"))
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Subtitles by Black Gold"))
        assertEquals("", UrduSubtitleEngine.sanitizeAndApplyRtlBidi("Netflix سے ڈاؤن لوڈ کیا گیا"))
    }

    @Test
    fun `parseSubtitleCues and formatCuesAsUrduSrt normalize VTT timestamps preserve inline tags and apply RTL wrapping`() {
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
        assertEquals("<i>How are you?</i>", cues[1].text)

        val translatedCues = listOf(
            UrduSubtitleEngine.SrtCue(1, cues[0].timestamp, "ہیلو دنیا!"),
            UrduSubtitleEngine.SrtCue(2, cues[1].timestamp, "<i>آپ کیسے ہیں؟</i>")
        )
        val formattedSrt = UrduSubtitleEngine.formatCuesAsUrduSrt(translatedCues)
        assertTrue(formattedSrt.contains("00:00:01,250 --> 00:00:03,500\n\u200Fہیلو دنیا!\u200F"))
        assertTrue(formattedSrt.contains("00:00:07,000 --> 00:00:09,900\n\u200F<i>آپ کیسے ہیں؟</i>\u200F"))
    }

    @Test
    fun `end-to-end inline tag preservation through parseSubtitleCues translateEnglishSrtToUrdu and normalizeUrduSubtitleText`() = runBlocking {
        val rawEnglishSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            {\an8}{\pos(100,200)}<font color="#ffff00"><i>Where are we?</i></font>
            <b>Stay close!</b> ♪
        """.trimIndent()

        val parsedCues = UrduSubtitleEngine.parseSubtitleCues(rawEnglishSrt)
        assertEquals(1, parsedCues.size)
        assertEquals("{\\an8}<i>Where are we?</i>\n<b>Stay close!</b> ♪", parsedCues[0].text)

        // Verify formatCuesAsUrduSrt(parseSubtitleCues(...)) and normalizeUrduSubtitleText preserve <i>, <b>, {\an8}, ♪
        val rawUrduSrt = """
            1
            00:00:01,000 --> 00:00:04,000
            {\an8}{\pos(100,200)}<font color="#ffff00"><i>ہم کہاں ہیں؟</i></font>
            <b>قریب رہو!</b> ♪
        """.trimIndent()
        val formattedFromParsed = UrduSubtitleEngine.formatCuesAsUrduSrt(UrduSubtitleEngine.parseSubtitleCues(rawUrduSrt))
        val normalizedUrdu = UrduSubtitleEngine.normalizeUrduSubtitleText(rawUrduSrt)
        assertEquals(formattedFromParsed, normalizedUrdu)
        assertTrue(normalizedUrdu.contains("\u200F{\\an8}<i>ہم کہاں ہیں؟</i>\u200F"))
        assertTrue(normalizedUrdu.contains("\u200F<b>قریب رہو!</b> ♪\u200F"))
        assertFalse(normalizedUrdu.contains("<font"))
        assertFalse(normalizedUrdu.contains("\\pos("))

        // Verify translateEnglishSrtToUrdu end-to-end preserves inline tags through placeholder protection & restoration
        val translatedSrt = UrduSubtitleEngine.translateEnglishSrtToUrdu(rawEnglishSrt) { batch ->
            batch.map { text ->
                text.replace("Where are we?", "ہم کہاں ہیں؟")
                    .replace("Stay close!", "قریب رہو!")
            }
        }
        assertNotNull(translatedSrt)
        assertTrue(translatedSrt!!.contains("\u200F{\\an8}<i>ہم کہاں ہیں؟</i>\u200F"))
        assertTrue(translatedSrt.contains("\u200F<b>قریب رہو!</b> ♪\u200F"))

        // Verify materializeNormalizedUrduSubtitle normalizes single-slash file:/ URI and fails closed on missing files
        val validUri = UrduSubtitleEngine.writeUrduLocalCacheFile("inline-tag-e2e", translatedSrt)
        assertNotNull(validUri)
        val singleSlashFileUri = "file:/" + validUri!!.removePrefix("file:///")
        val materializedValid = UrduSubtitleEngine.materializeNormalizedUrduSubtitle(singleSlashFileUri)
        assertEquals(validUri, materializedValid)
        assertNull(
            "materializeNormalizedUrduSubtitle must fail closed on non-existent file URI",
            UrduSubtitleEngine.materializeNormalizedUrduSubtitle("file:/nonexistent/path/missing_urdu.srt")
        )
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

        // Fail-closed year matching in parseSubDlAutoSlug
        val autoJsonWithYears = """
            {
              "results": [
                {
                  "sd_id": 1001,
                  "name": "Dune",
                  "year": 1984,
                  "type": "movie",
                  "link": "/subtitle/sd1001/dune-1984"
                }
              ]
            }
        """.trimIndent()
        assertNull(
            "parseSubDlAutoSlug must fail closed when expectedYear does not match any result",
            UrduSubtitleEngine.parseSubDlAutoSlug(autoJsonWithYears, expectedYear = 2021)
        )
        assertEquals(
            "/subtitle/sd1001/dune-1984",
            UrduSubtitleEngine.parseSubDlAutoSlug(autoJsonWithYears, expectedYear = 1984)
        )
    }

    @Test
    fun `writeUrduLocalCacheFile returns canonical triple-slash file URI and verifies disk existence`() {
        val sampleSrt = "1\n00:00:01,000 --> 00:00:03,000\n\u200Fیہ اردو سب ٹائٹل ہے\u200F\n"
        val uri = UrduSubtitleEngine.writeUrduLocalCacheFile("test-key-p0-1", sampleSrt)
        assertNotNull("Expected non-null local cache URI", uri)
        assertTrue(
            "Local cache URI must start with canonical triple-slash file:/// (actual: $uri)",
            uri!!.startsWith("file:///")
        )
        assertTrue("Cached file must be valid on disk", UrduSubtitleEngine.isCachedSubtitleUriValid(uri))

        val diskFile = java.io.File(uri.removePrefix("file://"))
        assertTrue("Disk file must exist", diskFile.exists())
        val rawBytes = diskFile.readBytes()
        assertTrue(
            "Disk file must start with UTF-8 BOM (EF BB BF)",
            rawBytes.size > 3 &&
                rawBytes[0] == 0xEF.toByte() &&
                rawBytes[1] == 0xBB.toByte() &&
                rawBytes[2] == 0xBF.toByte()
        )

        // Deleting the file on disk must invalidate isCachedSubtitleUriValid
        assertTrue(diskFile.delete())
        assertFalse(
            "Deleted cache file must fail isCachedSubtitleUriValid check",
            UrduSubtitleEngine.isCachedSubtitleUriValid(uri)
        )
    }

    @Test
    fun `publishToSubtitleCatCdn rejects synthetic folder and never emits 404 synthesized URL`() = runBlocking {
        val resultSynthetic = UrduSubtitleEngine.publishToSubtitleCatCdn(
            origFilename = "Movie.2024-orig.srt",
            urduSrtContent = "1\n00:00:01,000 --> 00:00:02,000\nاردو\n",
            subFolder = "/subs/synthesized/"
        )
        assertNull("publishToSubtitleCatCdn must never upload to /subs/synthesized/", resultSynthetic)

        val resultInvalid = UrduSubtitleEngine.publishToSubtitleCatCdn(
            origFilename = "Movie.2024-orig.srt",
            urduSrtContent = "1\n00:00:01,000 --> 00:00:02,000\nاردو\n",
            subFolder = "/invalid/nested/path/"
        )
        assertNull("publishToSubtitleCatCdn must reject non-SubtitleCat folder paths", resultInvalid)
    }

    @Test
    fun `extractBestSubtitleFromZip selects exact requested episode and rejects mismatched episodes`() {
        val ep1Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر ایک\n"
        val ep2Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر دو\n"
        val ep3Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر تین\n"

        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("Season1/Show.S01E01.1080p.Urdu.srt"))
            zos.write((ep1Srt + "\n".repeat(200)).toByteArray(Charsets.UTF_8)) // Make E01 larger than E02
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E02.1080p.Urdu.srt"))
            zos.write(ep2Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E03.1080p.Urdu.srt"))
            zos.write(ep3Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = zipOut.toByteArray()

        // Requesting S01E02 must deterministically pick Show.S01E02 even though S01E01 is larger in bytes
        val decodedEp2 = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(zipBytes, season = 1, episode = 2)
        assertTrue("Expected S01E02 content, got: $decodedEp2", decodedEp2.contains("قسط نمبر دو"))
        assertFalse(decodedEp2.contains("قسط نمبر ایک"))

        // Requesting S01E09 (not in the pack) must reject S01E01..S01E03 and return empty string
        val decodedEp9 = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(zipBytes, season = 1, episode = 9)
        assertEquals("", decodedEp9.trim())
    }

    @Test
    fun `protectSubtitleTags and restoreSubtitleTags preserve inline tags across Google Translate mutations`() {
        val raw = "<i>Hello</i> <b>world</b> {\\an8} ♪ music ♫"
        val (protectedText, tags) = UrduSubtitleEngine.protectSubtitleTags(raw)
        assertEquals(7, tags.size)
        assertTrue(protectedText.contains("\u30160\u3017"))
        assertTrue(protectedText.contains("\u30166\u3017"))

        // Simulate Google Translate mutating fullwidth brackets into 【 】 and [ ] with spaces and RLM marks
        val mutatedTranslation = "【 0 】ہیلو\u30161\u3017 [ \u200F2\u200F ] دنیا【3】 \u30164\u3017 \u30165\u3017 تصویر \u30166\u3017 \u301699\u3017"
        val restored = UrduSubtitleEngine.restoreSubtitleTags(mutatedTranslation, tags)
        assertEquals("<i>ہیلو</i> <b> دنیا</b> {\\an8} ♪ تصویر ♫", restored)
    }

    @Test
    fun `translateEnglishSrtToUrdu bisects failed 250-cue batches into 50-cue sub-batches`() = runBlocking {
        val sb = StringBuilder()
        for (i in 1..260) {
            val secStart = i
            val secEnd = i + 1
            sb.append("$i\n")
            sb.append("00:00:%02d,000 --> 00:00:%02d,500\n".format(secStart % 60, secEnd % 60))
            sb.append("Line number $i\n\n")
        }

        // Mock translator that fails when batch size == 250, but succeeds on sub-batches of <= 50
        val urduSrt = UrduSubtitleEngine.translateEnglishSrtToUrdu(sb.toString()) { batch ->
            if (batch.size > 50) {
                null
            } else {
                batch.map { "اردو ترجمہ: $it" }
            }
        }
        assertNotNull("Expected sub-batch retry to recover failed 250-cue batch", urduSrt)
        val parsedTranslatedCues = UrduSubtitleEngine.parseSubtitleCues(urduSrt!!)
        assertEquals(260, parsedTranslatedCues.size)
        assertTrue(parsedTranslatedCues.first().text.contains("اردو ترجمہ"))
    }

    @Test
    fun `parseGoogleBatchTranslateResponse handles single-cue multi-sentence nested JSONArray`() {
        // When expectedCount == 1 and a single cue has multiple sentences, Google returns:
        // [[["ہم کہاں ہیں؟","Where are we?"],["مجھے نہیں معلوم۔","I don't know."]],null,"en"]
        val multiSentenceSingleCueJson = """
            [[["ہم کہاں ہیں؟","Where are we?"],["مجھے نہیں معلوم۔","I don't know."]],null,"en"]
        """.trimIndent()

        val parsed = UrduSubtitleEngine.parseGoogleBatchTranslateResponse(multiSentenceSingleCueJson, expectedCount = 1)
        assertNotNull("Expected non-null parsed list for single-cue multi-sentence response", parsed)
        assertEquals(1, parsed!!.size)
        assertEquals("ہم کہاں ہیں؟ مجھے نہیں معلوم۔", parsed[0])
    }

    @Test
    fun `parseSubtitleCues skips multi-line VTT NOTE and STYLE blocks and parses flexible timestamps`() {
        val vttWithNotesAndStyles = """
            WEBVTT

            STYLE
            ::cue {
              color: yellow;
              /* 00:00:01.000 --> 00:00:02.000 fake arrow inside STYLE block */
            }

            NOTE This is a multi-line comment
            that even contains 00:00:02.000 --> 00:00:03.000
            and must be completely ignored!

            1
            01:23.45 --> 01:26.5
            <v Speaker><c.yellow>First flexible timestamp cue &amp; entities &#39;quoted&#39;</c></v>

            2
            00:02:10,5 --> 00:02:14
            Karaoke <00:02:11.200>timing tag stripped
        """.trimIndent()

        val cues = UrduSubtitleEngine.parseSubtitleCues(vttWithNotesAndStyles)
        assertEquals("Expected exactly 2 real cues after skipping STYLE and NOTE blocks", 2, cues.size)
        assertEquals("00:01:23,450 --> 00:01:26,500", cues[0].timestamp)
        assertEquals("First flexible timestamp cue & entities 'quoted'", cues[0].text)
        assertEquals("00:02:10,500 --> 00:02:14,000", cues[1].timestamp)
        assertEquals("Karaoke timing tag stripped", cues[1].text)
    }

    @Test
    fun `Windows-1252 English subtitles with smart quotes and Latin accents are not misdetected as Windows-1256`() {
        val englishWithSmartQuotesAndAccents = """
            1
            00:00:01,000 --> 00:00:04,000
            It’s a “naïve” café in Paris — don’t worry about the résumé!
        """.trimIndent()
        val cp1252Bytes = englishWithSmartQuotesAndAccents.toByteArray(Charset.forName("windows-1252"))

        assertFalse(
            "English CP1252 bytes with smart quotes/accents must NOT trigger looksLikeWindows1256",
            UrduSubtitleEngine.looksLikeWindows1256(cp1252Bytes)
        )
        val decoded = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(cp1252Bytes)
        assertTrue("Decoded CP1252 text must preserve 'café' and 'naïve', got: $decoded", decoded.contains("café") && decoded.contains("naïve"))
    }

    @Test
    fun `normalizeUrduTypography decomposes presentation forms and maps Goal Heh and Yeh Barree`() {
        // Contains Arabic Heh (\u0647), Ae (\u06D5), Yeh Barree with Hamza (\u06D3), and presentation form \uFB56 (\u067E)
        val raw = "\uFB56\u0627\u0643\u0633\u062A\u0627\u0646 \u064A\u0647 \u06D5 \u0628\u0691\u06D3"
        val normalized = UrduSubtitleEngine.normalizeUrduTypography(raw)
        assertEquals("\u067E\u0627\u06A9\u0633\u062A\u0627\u0646 \u06CC\u06C1 \u06C1 \u0628\u0691\u06D2", normalized)
    }

    @Test
    fun `SubSource and SubDL API v1 parsers and bounded NEXT_DATA context window`() {
        // 1. SubSource movie search parser
        val subSourceSearchJson = """
            {
              "success": true,
              "data": [
                {"movieId": "9911", "title": "Dune", "releaseYear": 1984, "imdbId": "tt0087182"},
                {"movieId": "9922", "title": "Dune: Part Two", "releaseYear": 2024, "imdbId": "tt15239678"}
              ]
            }
        """.trimIndent()
        assertEquals(
            "9922",
            UrduSubtitleEngine.parseSubSourceSearchMovieId(
                subSourceSearchJson,
                imdbId = "tt15239678",
                title = "Dune: Part Two",
                expectedYear = 2024
            )
        )

        // 2. SubSource subtitles list parser
        val subSourceSubsJson = """
            {
              "data": [
                {"subtitleId": "501", "language": "Urdu", "releaseInfo": "Show.S01E01.1080p"},
                {"subtitleId": "502", "language": "Urdu", "releaseInfo": "Show.S01E02.1080p"},
                {"subtitleId": "503", "language": "English", "releaseInfo": "Show.S01E02.1080p"}
              ]
            }
        """.trimIndent()
        assertEquals(
            listOf("https://api.subsource.net/api/v1/subtitles/502/download"),
            UrduSubtitleEngine.parseSubSourceSubtitlesList(subSourceSubsJson, season = 1, episode = 2)
        )

        // 3. SubDL API v1 parser
        val subDlApiV1Json = """
            {
              "status": true,
              "subtitles": [
                {"url": "/subtitle/111-222.zip", "lang": "urdu", "season": 1, "episode": 1, "release_name": "Show.S01E01"},
                {"url": "/subtitle/333-444.zip", "lang": "urdu", "season": 1, "episode": 2, "release_name": "Show.S01E02"}
              ]
            }
        """.trimIndent()
        assertEquals(
            listOf("https://dl.subdl.com/subtitle/333-444.zip"),
            UrduSubtitleEngine.parseSubDlApiV1Zips(subDlApiV1Json, season = 1, episode = 2)
        )

        // 4. Bounded __NEXT_DATA__ JSON context window prevents sibling episode bleed
        val nextDataHtml = """
            <html><body><script id="__NEXT_DATA__" type="application/json">
            {"props":{"subtitles":[
              {"name":"Show.S01E01.720p.Urdu","link":"https://dl.subdl.com/subtitle/1001-1001.zip"},
              {"name":"Show.S01E02.720p.Urdu","link":"https://dl.subdl.com/subtitle/1002-1002.zip"}
            ]}}
            </script></body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://dl.subdl.com/subtitle/1002-1002.zip"),
            UrduSubtitleEngine.parseSubDlUrduPageZips(nextDataHtml, season = 1, episode = 2)
        )

        // 5. Subf2m fail-closed movie year matching
        val subf2mSearchHtml = """
            <div class="title"><a href="/subtitles/the-batman">The Batman</a></div>
            <div class="title"><a href="/subtitles/the-batman-2022">The Batman (2022)</a></div>
        """.trimIndent()
        assertEquals(
            "the-batman-2022",
            UrduSubtitleEngine.parseSubf2mSearchSlug(subf2mSearchHtml, "The Batman", expectedYear = 2022, isMovie = true)
        )
        assertNull(
            UrduSubtitleEngine.parseSubf2mSearchSlug(subf2mSearchHtml, "The Batman", expectedYear = 1989, isMovie = true)
        )
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
                    UrduSubtitleEngine.synthesizeUrduFallbackFromEnglish(englishUrls = englishUrls) { synth ->
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

    @Test
    fun `8 SOTA Urdu sources registry and canonical source badges reject video and English-only providers`() {
        assertEquals(8, UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES)
        assertEquals(8, UrduSubtitleEngine.URDU_SOTA_SOURCES.size)
        val summary = UrduSubtitleEngine.sotaSourcesSummary()
        assertTrue(summary.contains("8 SOTA Sources"))
        assertTrue(summary.contains("Neural AI"))
        assertTrue(summary.contains("SubDL"))
        assertTrue(summary.contains("SubSource"))
        assertTrue(summary.contains("Wyzie"))
        assertTrue(summary.contains("SubSense"))
        assertTrue(summary.contains("YTS-Subs"))
        assertTrue(summary.contains("Subf2m"))
        assertTrue(summary.contains("SubtitleCat"))
        assertTrue(summary.contains("OpenSubtitles"))

        // Verify all 8 SOTA sources + Neural AI format cleanly and survive cleanSubtitleLabel
        val expectedLabels = mapOf(
            "subdl" to "Urdu [SubDL]",
            "subsource" to "Urdu [SubSource]",
            "wyziesub" to "Urdu [Wyzie]",
            "subsense" to "Urdu [SubSense]",
            "yify" to "Urdu [YTS-Subs]",
            "subf2m" to "Urdu [Subf2m]",
            "subtitlecat" to "Urdu [SubtitleCat]",
            "opensubtitles" to "Urdu [OpenSubtitles]",
            "subtitles" to "Urdu [OpenSubtitles]",
            "urdu-neural-fallback" to "Urdu [Neural AI]"
        )
        for ((key, expectedLabel) in expectedLabels) {
            val formatted = UrduSubtitleEngine.formatUrduSourceLabel(key)
            assertEquals(expectedLabel, formatted)
            assertEquals(expectedLabel, cleanSubtitleLabel(formatted))
            assertNotNull(UrduSubtitleEngine.extractUrduSourceBadge(formatted))
        }

        // Video providers and English-only providers must NEVER be accepted as Urdu sources
        val rejectedSources = listOf(
            "vidlink", "cinesrc", "meowtv", "vidfast", "vidrift", "cineby",
            "addic7ed", "ridoo", "lookmovie", "vidsrc", "superstream", ""
        )
        for (rejected in rejectedSources) {
            assertNull(
                "Provider '$rejected' does not host verified Urdu subtitles and must be rejected",
                UrduSubtitleEngine.canonicalUrduSourceKey(rejected)
            )
        }
    }

    @Test
    fun `containsVerifiedUrduScript accepts genuine Urdu subtitles and rejects mislabeled English subtitles`() {
        val mislabeledEnglishSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            This is actually an English subtitle file mislabeled as Urdu by an upstream scraper.

            2
            00:00:04,000 --> 00:00:06,000
            It contains zero Arabic or Urdu script characters!
        """.trimIndent()
        assertFalse(
            "Mislabeled English subtitle must be rejected by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(mislabeledEnglishSrt)
        )

        val genuineUrduSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            یہ ایک اصلی اردو سب ٹائٹل فائل ہے۔

            2
            00:00:04,000 --> 00:00:06,000
            ہم کہاں جا رہے ہیں؟
        """.trimIndent()
        assertTrue(
            "Genuine Urdu subtitle must be accepted by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(genuineUrduSrt)
        )
    }

    @Test
    fun `containsVerifiedUrduScript accepts genuine Urdu cues and rejects Persian and Arabic multi-cue subtitle files`() {
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript("یہ اردو سب ٹائٹل ہے"))
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript("رُک جاؤ، ورنہ گولی مار دوں گا!"))

        val persianMultiCueSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            راشل، من کارم تموم شد

            2
            00:00:03,500 --> 00:00:05,500
            باشه، فردا میبینمت

            3
            00:00:06,000 --> 00:00:08,500
            کجا بودی؟ چرا انقدر دیر کردی؟

            4
            00:00:09,000 --> 00:00:11,500
            خیلی خوب، اینجا منتظرم باش

            5
            00:00:12,000 --> 00:00:14,500
            من دارم میرم خونه
        """.trimIndent()
        assertFalse(
            "Persian (Farsi) subtitle file using Arabic script must be rejected by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(persianMultiCueSrt)
        )

        val arabicMultiCueSrt = """
            1
            00:00:01,000 --> 00:00:03,500
            مرحبًا بك في هذا العالم، ماذا تفعل هنا؟

            2
            00:00:04,000 --> 00:00:06,500
            هذا هو اليوم الذي ننتظره

            3
            00:00:07,000 --> 00:00:09,500
            لماذا لم تخبرني عن هذه المشكلة؟

            4
            00:00:10,000 --> 00:00:12,500
            التي كانت هناك منذ البداية

            5
            00:00:13,000 --> 00:00:15,500
            ماذا تريد أن تفعل الآن؟
        """.trimIndent()
        assertFalse(
            "Arabic subtitle file using Arabic script must be rejected by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(arabicMultiCueSrt)
        )
    }

    @Test
    fun `refineUrduToSpokenIdiom and adaptEnglishForSpokenUrduTranslation transform calques and idioms into natural spoken Urdu`() {
        assertEquals("یا خدایا!", UrduSubtitleEngine.refineUrduToSpokenIdiom("اوہ ، میرے خدا۔"))
        assertEquals("اف خدایا!", UrduSubtitleEngine.refineUrduToSpokenIdiom("اوہ ، میرے گوش۔"))
        assertEquals("کیا تمہارا دماغ خراب ہو گیا ہے؟", UrduSubtitleEngine.refineUrduToSpokenIdiom("کیا آپ اپنے دماغ سے باہر ہیں؟"))
        assertEquals("یا خدایا!", UrduSubtitleEngine.refineUrduToSpokenIdiom("مسیح!"))
        assertEquals("بیڑا غرق!", UrduSubtitleEngine.refineUrduToSpokenIdiom("گندگی!"))
        assertEquals("عالی جاہ", UrduSubtitleEngine.refineUrduToSpokenIdiom("آپ کا فضل"))
        assertEquals("طبیعت خراب ہو رہی", UrduSubtitleEngine.refineUrduToSpokenIdiom("صحت ناکام ہو رہی"))
        assertEquals("سوچو، سوچو، ذرا سوچو۔", UrduSubtitleEngine.refineUrduToSpokenIdiom("سوچو ، سوچو ، سوچئے۔"))
        assertTrue(UrduSubtitleEngine.refineUrduToSpokenIdiom("سوچو ، سوچو ، سوچئے۔").contains("سوچو، سوچو، ذرا سوچو"))

        // Pre-translation English idiom adaptation
        assertEquals(
            "کیا تمہارا دماغ خراب ہو گیا ہے؟",
            UrduSubtitleEngine.adaptEnglishForSpokenUrduTranslation("Are you out of your mind?")
        )
        assertEquals(
            "یہ تم آخر کر کیا رہے ہو؟",
            UrduSubtitleEngine.adaptEnglishForSpokenUrduTranslation("What the hell are you doing?")
        )
        assertEquals(
            "یا خدایا!",
            UrduSubtitleEngine.adaptEnglishForSpokenUrduTranslation("Oh, my God!")
        )
        assertEquals(
            "ذرا رکو!",
            UrduSubtitleEngine.adaptEnglishForSpokenUrduTranslation("Hold on!")
        )
        assertEquals(
            "کوئی بات نہیں۔",
            UrduSubtitleEngine.adaptEnglishForSpokenUrduTranslation("You're welcome.")
        )
    }

    @Test
    fun `isBrokenSmtUrdu detects 2012-era SMT dumps and scoreUrduSubtitleNaturalness ranks natural spoken Urdu higher than literal calques`() = runBlocking {
        val brokenSmtSrt = """
            1
            00:00:01,000 --> 00:00:03,500
            اس سے پہلے کہ تم یہ جانتے ہو کہ وہ ٹکڑے ٹکڑے کرنے کے ایک دوسرے کے ripping کے رہے

            2
            00:00:04,000 --> 00:00:06,500
            میں نے کبھی نہیں دیکھا ہے wildlings اس طرح ایک کام کرو

            3
            00:00:07,000 --> 00:00:09,500
            اپنے گھوڑے پر واپس حاصل
        """.trimIndent()

        val brokenCues = UrduSubtitleEngine.parseSubtitleCues(brokenSmtSrt)
        assertEquals(3, brokenCues.size)
        assertTrue(
            "2012-era broken SMT dump with embedded English tokens and ungrammatical SMT phrases must be flagged by isBrokenSmtUrdu",
            UrduSubtitleEngine.isBrokenSmtUrdu(brokenCues)
        )

        // Verify broken SMT Urdu is also rejected during materialization when extracted from a ZIP archive
        val brokenZipBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("Broken.2012.SMT.Urdu.srt"))
                zos.write(brokenSmtSrt.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }.toByteArray()
        val tempBrokenZip = java.io.File.createTempFile("broken_smt_test_", ".zip")
        try {
            tempBrokenZip.writeBytes(brokenZipBytes)
            // Use extractBestSubtitleFromZip / decodeAndNormalizeUrduBytes + materialization validation path
            val decodedBroken = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(brokenZipBytes)
            val parsedBroken = UrduSubtitleEngine.parseSubtitleCues(UrduSubtitleEngine.normalizeUrduTypography(decodedBroken))
            assertTrue(UrduSubtitleEngine.isBrokenSmtUrdu(parsedBroken))
        } finally {
            tempBrokenZip.delete()
        }

        val naturalSpokenCues = listOf(
            UrduSubtitleEngine.SrtCue(1, "00:00:01,000 --> 00:00:03,000", "یا خدایا! ذرا رُک جاؤ یار، کیا تمہارا دماغ خراب ہو گیا ہے؟"),
            UrduSubtitleEngine.SrtCue(2, "00:00:04,000 --> 00:00:06,000", "بھئی دیکھو، بالکل ٹھیک ہے، اب چلو یہاں سے۔"),
            UrduSubtitleEngine.SrtCue(3, "00:00:07,000 --> 00:00:09,000", "خبردار! ورنہ بہت برا ہو گا، سنو میری بات۔")
        )
        val literalCalqueCues = listOf(
            UrduSubtitleEngine.SrtCue(1, "00:00:01,000 --> 00:00:03,000", "اوہ ، میرے خدا۔ کیا آپ اپنے دماغ سے باہر ہیں؟"),
            UrduSubtitleEngine.SrtCue(2, "00:00:04,000 --> 00:00:06,000", "اوہ ، میرے گوش۔ آپ کا فضل، صحت ناکام ہو رہی ہے۔"),
            UrduSubtitleEngine.SrtCue(3, "00:00:07,000 --> 00:00:09,000", "میرے پاس بھی نہیں۔")
        )

        assertFalse(UrduSubtitleEngine.isBrokenSmtUrdu(naturalSpokenCues))
        val naturalScore = UrduSubtitleEngine.scoreUrduSubtitleNaturalness(naturalSpokenCues)
        val calqueScore = UrduSubtitleEngine.scoreUrduSubtitleNaturalness(literalCalqueCues)
        val brokenScore = UrduSubtitleEngine.scoreUrduSubtitleNaturalness(brokenCues)

        assertTrue(
            "Natural spoken Urdu ($naturalScore) must score significantly higher than literal calques ($calqueScore)",
            naturalScore > calqueScore + 50
        )
        assertTrue(
            "Broken SMT cues ($brokenScore) must score heavily negative",
            brokenScore <= -500
        )
    }

    @Test
    fun `parseSubDlUrduPageZips scopes strictly to data-language urdu filters out AI rows and ranks Tier-1A uploaders ahead of Tier-1C`() {
        // 1. HTML with persian and indonesian data-language blocks (and SUBDL AI) but no data-language="urdu"
        val nonUrduAstroHtml = """
            <div data-language="persian">
              <div class="sub-row">
                <span>Inception.2010.1080p.Persian</span>
                <a href="https://dl.subdl.com/subtitle/3001-9001.zip">Download</a>
              </div>
            </div>
            <div data-language="indonesian">
              <div class="sub-row">
                <span>Inception.2010.1080p.Indonesian (SUBDL AI)</span>
                <a href="https://dl.subdl.com/subtitle/3002-9002.zip">Download</a>
              </div>
            </div>
        """.trimIndent()
        assertEquals(
            "When data-language blocks exist without data-language='urdu', parseSubDlUrduPageZips must return emptyList()",
            emptyList<String>(),
            UrduSubtitleEngine.parseSubDlUrduPageZips(nonUrduAstroHtml)
        )

        // 2. HTML with data-language="urdu" containing AI rows, Tier-1C bulk uploaders, and Tier-1A human uploaders
        val mixedUrduAstroHtml = """
            <div data-language="persian">
              <div class="sub-row">
                <a href="https://dl.subdl.com/subtitle/1111-1111.zip">Persian Sub</a>
              </div>
            </div>
            <div data-language="urdu">
              <div class="sub-row" data-ai-translated="true">
                <span>Movie.2024.1080p.Urdu</span>
                <a data-subtitle-author="subdl_ai" href="https://dl.subdl.com/subtitle/2222-2222.zip">Download AI 1</a>
              </div>
              <div class="sub-row">
                <span>Movie.2024.1080p.Urdu (SUBDL AI)</span>
                <a href="https://dl.subdl.com/subtitle/2223-2223.zip">Download AI 2</a>
              </div>
              <div class="sub-row">
                <span>Movie.2024.1080p.WEBRip.Urdu</span>
                <a data-subtitle-author="blackgold" href="https://dl.subdl.com/subtitle/3333-3333.zip">Download Tier 1C blackgold</a>
              </div>
              <div class="sub-row">
                <span>Movie.2024.1080p.BluRay.Urdu</span>
                <a data-subtitle-author="akhaliq_655" href="https://dl.subdl.com/subtitle/3334-3334.zip">Download Tier 1C akhaliq</a>
              </div>
              <div class="sub-row">
                <span>Movie.2024.1080p.NF.WEB-DL.Urdu</span>
                <a data-subtitle-author="naveshta" href="https://dl.subdl.com/subtitle/4444-4444.zip">Download Tier 1A naveshta</a>
              </div>
              <div class="sub-row">
                <span>Movie.2024.1080p.AMZN.WEB-DL.Urdu</span>
                <a data-subtitle-author="faizybaba" href="https://dl.subdl.com/subtitle/4445-4445.zip">Download Tier 1A faizybaba</a>
              </div>
            </div>
        """.trimIndent()

        val parsedZips = UrduSubtitleEngine.parseSubDlUrduPageZips(mixedUrduAstroHtml)
        assertFalse("Persian zip must be excluded", parsedZips.contains("https://dl.subdl.com/subtitle/1111-1111.zip"))
        assertFalse("data-ai-translated row must be filtered out", parsedZips.contains("https://dl.subdl.com/subtitle/2222-2222.zip"))
        assertFalse("(SUBDL AI) row must be filtered out", parsedZips.contains("https://dl.subdl.com/subtitle/2223-2223.zip"))
        assertEquals(
            "Tier-1A human uploaders (naveshta, faizybaba) must be ranked ahead of Tier-1C bulk uploaders (blackgold, akhaliq_655)",
            listOf(
                "https://dl.subdl.com/subtitle/4444-4444.zip",
                "https://dl.subdl.com/subtitle/4445-4445.zip",
                "https://dl.subdl.com/subtitle/3333-3333.zip",
                "https://dl.subdl.com/subtitle/3334-3334.zip"
            ),
            parsedZips
        )
    }

    @Test
    fun `cross-cue sentence continuation merging and proportional Urdu splitting preserve exact cue timestamps`() = runBlocking {
        val cue1 = UrduSubtitleEngine.SrtCue(
            index = 1,
            timestamp = "00:01:10,000 --> 00:01:12,500",
            text = "As we begin, I believe you have"
        )
        val cue2 = UrduSubtitleEngine.SrtCue(
            index = 2,
            timestamp = "00:01:12,700 --> 00:01:15,200",
            text = "a statement to read into the record."
        )
        val cue3 = UrduSubtitleEngine.SrtCue(
            index = 3,
            timestamp = "00:01:18,000 --> 00:01:20,000",
            text = "Thank you, Senator."
        )

        assertTrue(
            "Consecutive cues splitting a single sentence with 200ms (< 1500ms) gap must be merged",
            UrduSubtitleEngine.shouldMergeContinuationCues(cue1, cue2)
        )
        assertFalse(
            "Cue ending with terminal period must not merge with next cue",
            UrduSubtitleEngine.shouldMergeContinuationCues(cue2, cue3)
        )

        val fullUrduSentence = "جیسا کہ ہم آغاز کر رہے ہیں، میرا خیال ہے کہ آپ کے پاس ریکارڈ میں پڑھنے کے لیے ایک بیان موجود ہے۔"
        val (part1, part2) = UrduSubtitleEngine.splitTranslatedUrduProportionally(
            translatedText = fullUrduSentence,
            firstSourceLen = cue1.text.length,
            secondSourceLen = cue2.text.length
        )
        assertTrue(part1.isNotBlank())
        assertTrue(part2.isNotBlank())
        assertEquals(fullUrduSentence, "$part1 $part2")

        // Verify end-to-end through translateEnglishSrtToUrdu (which invokes buildContextualTranslationUnits)
        val englishSrt = """
            1
            00:01:10,000 --> 00:01:12,500
            As we begin, I believe you have

            2
            00:01:12,700 --> 00:01:15,200
            a statement to read into the record.

            3
            00:01:18,000 --> 00:01:20,000
            Thank you, Senator.
        """.trimIndent()

        var receivedBatchInputs: List<String> = emptyList()
        val translatedSrt = UrduSubtitleEngine.translateEnglishSrtToUrdu(englishSrt) { batch ->
            receivedBatchInputs = batch
            listOf(
                fullUrduSentence,
                "شکریہ، سینیٹر۔"
            )
        }

        // 3 English cues (where 1 & 2 are continuation cues) must be merged into 2 contextual translation units
        assertEquals(2, receivedBatchInputs.size)
        assertEquals(
            "As we begin, I believe you have a statement to read into the record.",
            receivedBatchInputs[0]
        )

        assertNotNull(translatedSrt)
        val outputCues = UrduSubtitleEngine.parseSubtitleCues(translatedSrt!!)
        assertEquals("Proportionally split output must restore all 3 original cues", 3, outputCues.size)
        assertEquals("00:01:10,000 --> 00:01:12,500", outputCues[0].timestamp)
        assertEquals("00:01:12,700 --> 00:01:15,200", outputCues[1].timestamp)
        assertEquals("00:01:18,000 --> 00:01:20,000", outputCues[2].timestamp)
        assertTrue(outputCues[0].text.contains("جیسا کہ ہم آغاز کر رہے ہیں"))
        assertTrue(outputCues[1].text.contains("ریکارڈ میں پڑھنے کے لیے ایک بیان موجود ہے۔"))
    }

    @Test
    fun `2-cue Persian and Arabic files are deterministically rejected and 3-cue SVO to SOV continuation chains merge cleanly`() = runBlocking {
        // 1. 2-cue Persian (Farsi) with کی (which in Persian means 'when/who') must NOT pass as Urdu
        val twoCuePersian = """
            1
            00:00:01,000 --> 00:00:03,000
            کی اومدی؟ من اینجا منتظرت بودم
            
            2
            00:00:03,500 --> 00:00:05,500
            بگو چرا نرفتی خونه
        """.trimIndent()
        assertFalse(
            "2-cue Persian file must be deterministically rejected even with Persian 'کی'",
            UrduSubtitleEngine.containsVerifiedUrduScript(twoCuePersian)
        )

        // 2. 2-cue Arabic without exclusive regex words must still be rejected because it lacks Urdu distinctive chars and function words
        val twoCueArabic = """
            1
            00:00:01,000 --> 00:00:03,000
            نحن ننتظر في البيت منذ الصباح
            
            2
            00:00:03,500 --> 00:00:05,500
            هل سمعت الخبر الجديد اليوم
        """.trimIndent()
        assertFalse(
            "2-cue Arabic file without Urdu distinctive characters or Urdu function words must be rejected",
            UrduSubtitleEngine.containsVerifiedUrduScript(twoCueArabic)
        )

        // 3. 3-cue continuation chain with 'I' pronoun on cue 2 and lowercase continuation on cue 3
        val threeCueEnglishSrt = """
            1
            00:02:00,000 --> 00:02:02,000
            When the storm finally arrived,
            
            2
            00:02:02,200 --> 00:02:04,200
            I realized that the old bridge
            
            3
            00:02:04,400 --> 00:02:06,500
            had already collapsed into the river.
        """.trimIndent()

        var capturedBatch: List<String> = emptyList()
        val fullThreeCueUrdu = "جب طوفان بالآخر پہنچا تو مجھے احساس ہوا کہ پرانا پل پہلے ہی دریا میں گر چکا تھا۔"
        val translatedThreeCue = UrduSubtitleEngine.translateEnglishSrtToUrdu(threeCueEnglishSrt) { batch ->
            capturedBatch = batch
            listOf(fullThreeCueUrdu)
        }
        assertEquals("3 continuation cues must merge into 1 contextual SVO->SOV unit", 1, capturedBatch.size)
        assertEquals(
            "When the storm finally arrived, I realized that the old bridge had already collapsed into the river.",
            capturedBatch[0]
        )
        assertNotNull(translatedThreeCue)
        val splitCues = UrduSubtitleEngine.parseSubtitleCues(translatedThreeCue!!)
        assertEquals(3, splitCues.size)
        assertEquals("00:02:00,000 --> 00:02:02,000", splitCues[0].timestamp)
        assertEquals("00:02:02,200 --> 00:02:04,200", splitCues[1].timestamp)
        assertEquals("00:02:04,400 --> 00:02:06,500", splitCues[2].timestamp)

        // 4. Content-Addressed Storage (cs_urdu_cas_) deduplicates identical Urdu SRT content across different URLs
        val uriA = UrduSubtitleEngine.writeUrduLocalCacheFile("https://mirror1.example.com/sub.srt|S1E1", translatedThreeCue)
        val uriB = UrduSubtitleEngine.writeUrduLocalCacheFile("https://mirror2.example.com/sub.srt|S1E1", translatedThreeCue)
        assertNotNull(uriA)
        assertEquals("Identical Urdu SRT content from two different URLs must resolve to the same content-addressed cs_urdu_cas_ file", uriA, uriB)
        assertTrue(uriA!!.contains("cs_urdu_cas_"))

        // 5. SubtitleCat candidates are scoped by (imdbId, season, episode)
        UrduSubtitleEngine.consumeSubtitleCatOrigCandidates()
        UrduSubtitleEngine.recordSubtitleCatOrigCandidate(
            origUrl = "https://www.subtitlecat.com/subs/111/Show.S01E01-orig.srt",
            origFilename = "Show.S01E01-orig.srt",
            imdbId = "tt1111111",
            season = 1,
            episode = 1
        )
        UrduSubtitleEngine.recordSubtitleCatOrigCandidate(
            origUrl = "https://www.subtitlecat.com/subs/222/Show.S01E02-orig.srt",
            origFilename = "Show.S01E02-orig.srt",
            imdbId = "tt1111111",
            season = 1,
            episode = 2
        )
        val ep2Candidates = UrduSubtitleEngine.consumeSubtitleCatOrigCandidates(imdbId = "tt1111111", season = 1, episode = 2)
        assertEquals(1, ep2Candidates.size)
        assertEquals("Show.S01E02-orig.srt", ep2Candidates[0].second)

        // 6. LRU Eviction stops at exactly 64 files (never wipes the entire cache) and never touches foreign .srt files
        val tmpDir = java.nio.file.Files.createTempDirectory("urdu_lru_test").toFile()
        try {
            val foreignFile = java.io.File(tmpDir, "foreign_user_subtitle.srt").apply {
                writeText("1\n00:00:01,000 --> 00:00:02,000\nForeign subtitle\n")
            }
            val now = System.currentTimeMillis()
            for (idx in 1..70) {
                val f = java.io.File(tmpDir, "cs_urdu_cas_%04d.srt".format(idx))
                f.writeText("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن $idx ہے\n")
                f.setLastModified(now - (100 - idx) * 1000L)
            }
            UrduSubtitleEngine.evictStaleAndExcessCacheFiles(tmpDir)
            val remainingUrduFiles = tmpDir.listFiles { f -> f.name.startsWith("cs_urdu_") } ?: emptyArray()
            assertEquals("LRU eviction of 70 files must retain exactly 64 newest files", 64, remainingUrduFiles.size)
            assertTrue("Foreign non-cs_urdu_ .srt file must never be deleted", foreignFile.exists())
            assertFalse("Oldest file cs_urdu_cas_0001.srt must be evicted", java.io.File(tmpDir, "cs_urdu_cas_0001.srt").exists())
            assertTrue("Newest file cs_urdu_cas_0070.srt must be retained", java.io.File(tmpDir, "cs_urdu_cas_0070.srt").exists())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `Persian subtitle whose only Urdu evidence is shared homographs is rejected even after typography normalization`() {
        // Gemini round-1 repro: normalizeUrduTypography leaves تو/کجا untouched, and the old gate
        // compared strict inequality, so a Persian file with zero Urdu-distinctive glyphs passed.
        val persianSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nتو کجا رفتی؟\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nمن منتظرت بودم\n")
        }
        assertFalse(
            "Persian homograph-only file must be rejected by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(persianSrt)
        )
    }

    @Test
    fun `Arabic file that normalizes into Urdu homographs is rejected by the raw-orthography scan`() {
        // Gemini round-1 repro: Arabic هو/هم normalize INTO the Urdu words ہو/ہم, so the normalized
        // scan alone cannot catch it; RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX sees the raw orthography.
        val arabicSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nهو في البيت الآن\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nهل سمعت الخبر الجديد اليوم\n")
        }
        assertFalse(
            "Arabic file normalizing into Urdu homographs must be rejected",
            UrduSubtitleEngine.containsVerifiedUrduScript(arabicSrt)
        )
    }

    @Test
    fun `Persian raw orthography (هست) is rejected by the raw scan before normalization`() {
        val persianSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nتو خوب هستی\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nاین خانه بزرگ است\n")
        }
        assertFalse(
            "Persian raw-orthography file must be rejected by containsVerifiedUrduScript",
            UrduSubtitleEngine.containsVerifiedUrduScript(persianSrt)
        )
    }

    @Test
    fun `Token-free Persian padding with one Urdu line is rejected by the evidence-share gate`() {
        // Round-3 finding (GLM): natural colloquial Persian can contain ZERO matches against the
        // curated fa/ar token list, so the list-independent share gate is the only defense. Every
        // padding line below was mechanically verified token-free against the compiled RAW and
        // normalized patterns (0 fa/ar matches, 0 Urdu evidence). The file is 1 Urdu cue over 30
        // such lines: dominance passes (0 fa-leaning), so ONLY the >= 40% evidence-share gate
        // rejects it.
        val padding = listOf(
            "او به مدرسه رفت",
            "سارا نان خرید",
            "او نامه نوشت",
            "حالت چطوره؟",
            "دیر رسید",
            "دوستم تلفن کرد",
            "سارا کتاب خواند",
            "او زود بیدار شد"
        )
        val padded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ٹائٹل ہے\n\n")
            for (i in 2..31) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append(padding[i % padding.size] + "\n\n")
            }
        }
        assertFalse(
            "Token-free Persian padding must be rejected by the evidence-share gate",
            UrduSubtitleEngine.containsVerifiedUrduScript(padded)
        )
    }

    @Test
    fun `Persian homograph vocabulary can no longer earn Urdu-evidence credit`() {
        // Round-3 finding (GLM): Persian هوا (air) normalized into the evidence word ہوا, letting
        // a Persian body earn Urdu-evidence credit. ہوا/ہوئی/ہوئے were removed from the
        // homograph-free evidence list, so an all-هوا Persian file has 0% evidence.
        val persianHava = buildString {
            for (i in 1..30) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("هوا سرد بود و باران می\u200Cبارید\n")
            }
        }
        assertFalse(
            "Persian هوا cues must not earn Urdu-evidence credit",
            UrduSubtitleEngine.containsVerifiedUrduScript(persianHava)
        )
    }

    @Test
    fun `Vocative-dense genuine Urdu files are accepted`() {
        // Round-3 finding (GLM): bare greeting/vocative cues (خدا حافظ، سلام علیکم) share their
        // nouns with Persian/Arabic, and the old exclusive list counted them as fa/ar-leaning,
        // rejecting vocative-dense genuine Urdu. The shared nouns were removed from the list, so
        // such cues are language-neutral and the file passes on the strength of its dialogue cues.
        val vocativeDense = buildString {
            for (i in 1..12) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append(if (i % 2 == 0) "خدا حافظ\n\n" else "سلام علیکم\n\n")
            }
            for (i in 13..24) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ مکالمہ $i ہے اور ہم اسے سمجھ گے\n\n")
            }
        }
        assertTrue(
            "Vocative-dense genuine Urdu must be accepted",
            UrduSubtitleEngine.containsVerifiedUrduScript(vocativeDense)
        )
    }

    @Test
    fun `Genuine Urdu subtitles containing shared homographs are still accepted`() {
        // Urdu fixture deliberately contains homographs (تم، کیا، اور) alongside distinctive glyphs
        // (ٹ، ں، ے، ھ) — the tightened gate must never reject genuine spoken Urdu.
        val urduSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:03,000\nیہ گھر بہت خوبصورت ہے اور تم اسے پسند کرو گے\n\n")
            append("2\n00:00:03,000 --> 00:00:05,000\nکل ہم سب بازار جائیں گے اور ٹھیلوں سے خریداری کریں گے\n")
        }
        assertTrue(
            "Genuine Urdu with shared fa/ar homographs must still be accepted",
            UrduSubtitleEngine.containsVerifiedUrduScript(urduSrt)
        )
    }

    @Test
    fun `Cache writes are atomic with no temporary files left behind`() {
        val urduSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو سبٹائٹل لائن ہے\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nدوسری لائن بھی نرمل اردو میں ہے\n")
        }
        val returnedUri = UrduSubtitleEngine.writeUrduLocalCacheFile("atomic_write_test", urduSrt)
        assertNotNull("writeUrduLocalCacheFile must return a file URI", returnedUri)
        val path = returnedUri!!.removePrefix("file://").removePrefix("file:")
        val file = java.io.File(path)
        try {
            assertTrue("Cached .srt must exist", file.exists() && file.isFile)
            val bytes = file.readBytes()
            assertEquals("Cached .srt must start with the UTF-8 BOM", 0xEF, bytes[0].toInt() and 0xFF)
            assertTrue("Cached .srt must contain the Urdu payload", String(bytes, Charsets.UTF_8).contains("سبٹائٹل"))
            val tmpLeftovers = file.parentFile?.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
            assertEquals("Atomic write must leave no .tmp files behind", 0, tmpLeftovers.size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `Adversarially padded files (one Urdu line over a Persian body) are rejected by per-cue verification`() {
        // Round-2 finding (GLM): the file-level aggregate counts can be defeated by padding a
        // single Urdu-distinctive line over Persian sentences that contain no list-tokens at all.
        // The per-cue gate requires Urdu evidence (distinctive glyphs or homograph-free function
        // words) in a healthy share of the Arabic-script cues.
        val padding = listOf(
            "او به مدرسه رفت", "برادرم دیروز آمد", "او گفت فردا می‌آید", "این کتاب نو است",
            "آن‌ها در پارک قدم می‌زدند", "پدرم چای می‌خورد", "ما به بازار رفتیم", "او نامه نوشت",
            "دوستم تلفن کرد", "هوا سرد شده است", "کتاب روی میز است", "او زود بیدار شد"
        )
        val padded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ٹائٹل ہے\n\n")
            for (i in 2..31) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append(padding[i % padding.size] + "\n\n")
            }
        }
        assertFalse(
            "One padded Urdu line over a Persian body must be rejected by per-cue verification",
            UrduSubtitleEngine.containsVerifiedUrduScript(padded)
        )
        // Genuine Urdu with a few Persian-shared single-word cues must still pass comfortably.
        val genuine = buildString {
            for (i in 1..20) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ اردو مکالمہ $i ہے اور ہم اسے سمجھ گے\n\n")
            }
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(genuine))
    }

    @Test
    fun `Corrupted URL-index cache entries are discarded instead of served`() = runBlocking {
        val urduSrt = buildString {
            for (i in 1..20) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ اردو سبٹائٹل لائن $i ہے، اور یہ بہت خوبصورت ہے\n\n")
            }
        }
        val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/sub.srt") { exchange ->
            fetchCount.incrementAndGet()
            val bytes = urduSrt.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            UrduSubtitleEngine.clearCachesForTest()
            val url = "http://127.0.0.1:${server.address.port}/sub.srt?run=${System.nanoTime()}"
            // Pre-plant a > 64-byte NON-Urdu payload at the exact URL-index path for this cache key.
            val cacheKey = "$url|S0E0"
            val urlCacheName = "cs_urdu_url_${UrduSubtitleEngine.sha256Hex(cacheKey).take(24)}.srt"
            val bogus = java.io.File(System.getProperty("java.io.tmpdir"), urlCacheName)
            bogus.writeText("1\n00:00:01,000 --> 00:00:02,000\nTotally not Urdu at all\n\n2\n00:00:02,000 --> 00:00:03,000\nstill not urdu here\n")
            try {
                val result = UrduSubtitleEngine.materializeNormalizedUrduSubtitle(url = url, sourceTag = "integrity-test")
                assertNotNull("Materialization must fall through to the network", result)
                assertEquals("The network must have been used instead of the corrupt cache", 1, fetchCount.get())
                val served = java.io.File(result!!.removePrefix("file://")).readText()
                assertTrue("Served payload must be the verified Urdu content", served.contains("سبٹائٹل"))
                val indexNow = runCatching { bogus.readText() }.getOrNull().orEmpty()
                assertTrue(
                    "The corrupt entry must have been replaced by the verified Urdu payload",
                    indexNow.contains("سبٹائٹل") && !indexNow.contains("Totally not Urdu")
                )
            } finally {
                java.io.File(System.getProperty("java.io.tmpdir"), urlCacheName).delete()
            }
        } finally {
            server.stop(0)
            UrduSubtitleEngine.clearCachesForTest()
        }
    }

    @Test
    fun `Corrupted CAS cache entries are rewritten on the next write`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nیہ اردو سبٹائٹل لائن ہے اور اچھی ہے\n\n2\n00:00:02,000 --> 00:00:03,000\nدوسری لائن بھی نرمل اردو میں ہے\n"
        val uri = UrduSubtitleEngine.writeUrduLocalCacheFile("cas_corruption_test", srt)
        assertNotNull(uri)
        val file = java.io.File(uri!!.removePrefix("file://"))
        try {
            file.writeText("CORRUPTED GARBAGE PAYLOAD THAT NO LONGER MATCHES THE HASH ".repeat(3))
            val uri2 = UrduSubtitleEngine.writeUrduLocalCacheFile("cas_corruption_test", srt)
            assertEquals("The same CAS path must be returned after the rewrite", uri, uri2)
            assertTrue(
                "The rewritten payload must be the verified Urdu content again",
                java.io.File(uri2!!.removePrefix("file://")).readText().contains("سبٹائٹل")
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `Cached payload trust helper rejects non-Urdu and broken content`() {
        val validUrdu = "1\n00:00:01,000 --> 00:00:02,000\nیہ اردو سبٹائٹل لائن ہے اور اچھی ہے\n\n2\n00:00:02,000 --> 00:00:03,000\nدوسری لائن بھی نرمل اردو میں ہے\n"
        assertTrue(UrduSubtitleEngine.isTrustedCachedUrduContent(validUrdu))
        assertFalse(
            "Non-Urdu content must fail the cache trust gate",
            UrduSubtitleEngine.isTrustedCachedUrduContent("1\n00:00:01,000 --> 00:00:02,000\nplain english text\n")
        )
        assertFalse(
            "Blank payload must fail the cache trust gate",
            UrduSubtitleEngine.isTrustedCachedUrduContent("")
        )
        assertFalse(
            "Non-cue payload must fail the cache trust gate",
            UrduSubtitleEngine.isTrustedCachedUrduContent("جستجوی متن بدون تایم‌استمپ")
        )
    }

    @Test
    fun `Concurrent materializations of the same URL coalesce into a single network fetch`() = runBlocking {
        val urduSrt = buildString {
            for (i in 1..40) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ اردو سبٹائٹل لائن $i ہے، اور یہ بہت خوبصورت ہے\n\n")
            }
        }
        val fetchCount = java.util.concurrent.atomic.AtomicInteger(0)
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/sub.srt") { exchange ->
            fetchCount.incrementAndGet()
            val bytes = urduSrt.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            UrduSubtitleEngine.clearCachesForTest()
            // Unique query string per run so no stale URL-index disk entry from an earlier run
            // can answer the request without touching the network.
            val url = "http://127.0.0.1:${server.address.port}/sub.srt?run=${System.nanoTime()}"
            val results = coroutineScope {
                (1..24).map {
                    async { UrduSubtitleEngine.materializeNormalizedUrduSubtitle(url = url, sourceTag = "coalesce-test") }
                }.awaitAll()
            }
            assertTrue("Every coalesced caller must receive a materialized URI", results.all { it != null && it == results[0] })
            assertEquals(
                "SingleFlight must coalesce 24 concurrent materializations into exactly 1 network fetch",
                1,
                fetchCount.get()
            )
        } finally {
            server.stop(0)
            UrduSubtitleEngine.clearCachesForTest()
        }
    }

    @Test
    fun `Persian raha padding can no longer earn Urdu-evidence credit via the ra-ha homograph`() {
        // Round-4 finding (GLM): normalizeUrduTypography("رها") == "رہا", and رہا was in the
        // evidence list, so one Persian رها per cue flipped a fully-detected Persian file to
        // ACCEPT. The evidence token is now stored with do-chashmi heh (رھا) — which normalization
        // never produces — and the hamza-less Arabic spellings اب/سب were removed outright.
        assertEquals("رہا", UrduSubtitleEngine.normalizeUrduTypography("رها"))
        assertFalse(
            "The evidence list must NOT match the normalized Persian رها",
            UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("رہا")
        )
        assertFalse(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("اب"))
        assertFalse(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("سب"))
        val padded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("رها شد\n\n")
            }
        }
        assertFalse(
            "A رها-padded Persian file must be rejected by the share gate",
            UrduSubtitleEngine.containsVerifiedUrduScript(padded)
        )
    }

    @Test
    fun `Persian ZWNJ verb prefixes are detected structurally without any token list`() {
        // Structural orthography signal (list-independent): Urdu orthography never uses ZWNJ, so
        // می\u200C / نمی\u200C verb prefixes flag Persian regardless of the curated token list.
        val padded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("پدرم چای می\u200Cخورد\n\n")
            }
        }
        assertFalse(
            "A ZWNJ-verb Persian body must be rejected via structural dominance",
            UrduSubtitleEngine.containsVerifiedUrduScript(padded)
        )
    }

    @Test
    fun `Arabic hamza orthography is detected structurally without any token list`() {
        val padded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..7) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("أين أخي الآن؟\n\n")
            }
        }
        assertFalse(
            "An Arabic-hamza body must be rejected via structural dominance",
            UrduSubtitleEngine.containsVerifiedUrduScript(padded)
        )
    }

    @Test
    fun `Short genuine Urdu snippets and vocative-dense files survive the tiered share floor`() {
        // Round-4 finding (GLM): the 40% cliff rejected a 3-cue genuine snippet (1 evidence cue)
        // and vocative-dense files beyond 50% bare-cue share. The floor is now 40% when any cue
        // leans fa/ar and 30% when the file carries no fa/ar signal at all.
        val short3 = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nواہ!\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nخوبصورت ہے\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nشکریہ\n")
        }
        assertTrue(
            "A 3-cue genuine snippet with 1 evidence cue must be accepted",
            UrduSubtitleEngine.containsVerifiedUrduScript(short3)
        )
        val vocativeDense = buildString {
            for (i in 1..20) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append(if (i % 2 == 0) "خدا حافظ\n\n" else "سلام علیکم\n\n")
            }
            for (i in 21..32) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ مکالمہ $i ہے اور ہم اسے سمجھ گے\n\n")
            }
        }
        assertTrue(
            "A 62.5% bare-vocative file (37.5% evidence share, 0 fa/ar-leaning) must be accepted",
            UrduSubtitleEngine.containsVerifiedUrduScript(vocativeDense)
        )
        val short4pad = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..4) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("او به مدرسه رفت\n\n")
            }
        }
        assertFalse(
            "A 4-cue token-free padding file (25% evidence) must still be rejected",
            UrduSubtitleEngine.containsVerifiedUrduScript(short4pad)
        )
    }
}


