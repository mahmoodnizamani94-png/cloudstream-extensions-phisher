package com.phisher98

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
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SuperStreamUrduSubtitleTest {

    @Before
    fun setUp() {
        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        SuperStreamExtractor.clearCachesForTest()
    }

    @After
    fun tearDown() {
        setSubtitleMode(MODE_DEFAULT, null, null)
        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        SuperStreamExtractor.clearCachesForTest()
    }

    @Test
    fun `Default English mode is active out of the box and switches to Urdu and back with zero latency`() {
        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        assertFalse("Default mode out-of-the-box must NOT be Urdu mode", isUrduSubtitleMode())

        setSubtitleMode(MODE_URDU, null, null)
        assertTrue("Expected isUrduSubtitleMode() == true immediately after switching to URDU", isUrduSubtitleMode())
        assertEquals(MODE_URDU, System.getProperty(SUBTITLE_MODE_SYS_PROP))

        setSubtitleMode(MODE_DEFAULT, null, null)
        assertFalse("Expected isUrduSubtitleMode() == false immediately after switching back to DEFAULT", isUrduSubtitleMode())
        assertEquals(MODE_DEFAULT, System.getProperty(SUBTITLE_MODE_SYS_PROP))
    }

    @Test
    fun `SuperStreamUtils recognizes Urdu and English language codes`() {
        val urduInputs = listOf("ur", "urd", "ur-PK", "ur_IN", "Urdu", "urdu", "اردو", "Pakistani Urdu")
        for (input in urduInputs) {
            assertTrue("Expected '$input' to be recognized as Urdu", isUrduSubtitleCode(input))
            assertFalse("Expected '$input' NOT to be recognized as English", isEnglishSubtitleCode(input))
        }

        val englishInputs = listOf("en", "eng", "en-US", "en-GB", "en_US", "English", "english")
        for (input in englishInputs) {
            assertTrue("Expected '$input' to be recognized as English", isEnglishSubtitleCode(input))
            assertFalse("Expected '$input' NOT to be recognized as Urdu", isUrduSubtitleCode(input))
        }

        assertFalse(isUrduSubtitleCode(null))
        assertFalse(isUrduSubtitleCode(""))
        assertFalse(isEnglishSubtitleCode(null))
        assertFalse(isEnglishSubtitleCode(""))
    }

    @Test
    fun `extractBestSubtitleFromZip selects exact requested episode S01E02 and never falls back to raw PKZIP bytes`() {
        val ep1Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر ایک\n"
        val ep2Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر دو\n"
        val ep3Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر تین\n"

        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("info.nfo"))
            zos.write("Release notes".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E01.1080p.Urdu.srt"))
            zos.write((ep1Srt + "\n".repeat(250)).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E02.1080p.Urdu.srt"))
            zos.write(ep2Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E03.1080p.Urdu.srt"))
            zos.write(ep3Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = zipOut.toByteArray()

        val decodedEp2 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(zipBytes, season = 1, episode = 2)
        assertTrue("Expected S01E02 content, got: $decodedEp2", decodedEp2.contains("قسط نمبر دو"))
        assertFalse(decodedEp2.contains("قسط نمبر ایک"))

        val extractedEp9 = SuperStreamExtractor.extractBestSubtitleFromZip(zipBytes, season = 1, episode = 9)
        assertNull("Mismatched episode ZIP must return null rather than raw PKZIP bytes", extractedEp9)
        val decodedEp9 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(zipBytes, season = 1, episode = 9)
        assertEquals("", decodedEp9)
    }

    @Test
    fun `decodeSubtitleCharset handles GZIP UTF-8 BOM UTF-16LE UTF-16BE Windows-1256 Urdu and Windows-1252 English`() {
        val sampleUrdu = "1\n00:00:01,000 --> 00:00:04,000\nمیں تم سے محبت کرتا ہوں"

        // 1. GZIP + UTF-8 BOM
        val gzipOut = ByteArrayOutputStream()
        GZIPOutputStream(gzipOut).use {
            it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sampleUrdu.toByteArray(Charsets.UTF_8))
        }
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(gzipOut.toByteArray()))

        // 2. UTF-16LE with BOM & UTF-16BE without BOM
        val utf16LeWithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sampleUrdu.toByteArray(Charsets.UTF_16LE)
        val utf16BeNoBom = sampleUrdu.toByteArray(Charsets.UTF_16BE)
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(utf16LeWithBom))
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(utf16BeNoBom))

        // 3. Legacy Windows-1256 (CP1256) Urdu
        val cp1256Raw = "1\n00:00:02,000 --> 00:00:05,000\nپاکستان ميں ٹرين چل رہي ہے، گاڑي اور پھول بڑي خوبصورت ہيں"
        val cp1256Bytes = cp1256Raw.toByteArray(Charset.forName("windows-1256"))
        assertTrue(SuperStreamExtractor.looksLikeWindows1256(cp1256Bytes))
        val decodedCp1256 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(cp1256Bytes)
        assertTrue(decodedCp1256.contains("پاکستان میں ٹرین چل رہی ہے"))
        assertTrue(decodedCp1256.contains("گاڑی اور پھول بڑی خوبصورت ہیں"))

        // 4. Windows-1252 English with smart quotes and Latin accents (4:1 ratio disambiguation)
        val englishCp1252 = """
            1
            00:00:01,000 --> 00:00:04,000
            It’s a “naïve” café in Paris — don’t worry about the résumé!
        """.trimIndent()
        val cp1252Bytes = englishCp1252.toByteArray(Charset.forName("windows-1252"))
        assertFalse(
            "English CP1252 must NOT be misclassified as Windows-1256",
            SuperStreamExtractor.looksLikeWindows1256(cp1252Bytes)
        )
        val decodedCp1252 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(cp1252Bytes)
        assertTrue(decodedCp1252.contains("café") && decodedCp1252.contains("naïve"))
    }

    @Test
    fun `normalizeUrduTypography and sanitizeAndApplyRtlBidi normalize glyphs remove watermarks preserve inline tags and wrap RLM`() {
        // Presentation form \uFB56 (\u067E), Arabic Kaf (\u0643), Arabic Yeh (\u064A), Alef Maksura (\u0649),
        // Arabic Heh (\u0647), Ae (\u06D5), and Yeh Barree with Hamza (\u06D3)
        val rawGlyphs = "\uFB56\u0627\u0643\u0633\u062A\u0627\u0646 \u064A\u0647 \u0627\u0639\u0644\u0649 \u06D5 \u0628\u0691\u06D3"
        val normalized = SuperStreamExtractor.normalizeUrduTypography(rawGlyphs)
        assertEquals("پاکستان یہ اعلی ہ بڑے", normalized)

        // Inline tag preservation (<i>...</i>, {\an8}) + RLM wrapping + non-inline tag stripping ({\pos}, <font>)
        val cueWithTags = "{\\an8}{\\pos(192,220)}<font color=\"#ffff00\"><i>کیا حال ہے؟</i></font>\nSecond English line"
        val sanitized = SuperStreamExtractor.sanitizeAndApplyRtlBidi(cueWithTags)
        val lines = sanitized.split("\n")
        assertEquals(2, lines.size)
        assertTrue("Line must start with RLM (\\u200F)", lines[0].startsWith("\u200F"))
        assertTrue("Line must preserve {\\an8} and <i>...</i>", lines[0].contains("{\\an8}<i>کیا حال ہے؟</i>"))
        assertFalse("Line must strip {\\pos} and <font>", lines[0].contains("\\pos") || lines[0].contains("<font"))
        assertEquals("Second English line", lines[1])

        // Promotional watermark removal
        assertEquals("", SuperStreamExtractor.sanitizeAndApplyRtlBidi("Translated by @iamdesigora"))
        assertEquals("", SuperStreamExtractor.sanitizeAndApplyRtlBidi("Subtitles by Black Gold"))
        assertEquals("", SuperStreamExtractor.sanitizeAndApplyRtlBidi("Downloaded from subdl.com"))
    }

    @Test
    fun `protectSubtitleTags and restoreSubtitleTags preserve inline tags across neural translation`() {
        val raw = "<i>Hello</i> <b>world</b> {\\an8} ♪ music ♫"
        val (protectedText, tags) = SuperStreamExtractor.protectSubtitleTags(raw)
        assertEquals(7, tags.size)

        val mutatedTranslation = "【 0 】ہیلو〖1〗 [ \u200F2\u200F ] دنیا【3】 〖4〗 〖5〗 تصویر 〖6〗 〖99〗"
        val restored = SuperStreamExtractor.restoreSubtitleTags(mutatedTranslation, tags)
        assertEquals("<i>ہیلو</i> <b> دنیا</b> {\\an8} ♪ تصویر ♫", restored)
    }

    @Test
    fun `parseSuperStreamCues handles VTT multi-line NOTE STYLE REGION blocks flexible timestamps VTT voice class tags and HTML entities`() {
        val vttContent = """
            WEBVTT

            STYLE
            ::cue {
              color: yellow;
              /* 00:00:01.000 --> 00:00:02.000 fake cue in STYLE */
            }

            REGION
            id:region1
            width:50%

            NOTE Multi-line note block
            with fake 00:00:02.000 --> 00:00:03.000 timestamp

            1
            1:05.5 --> 1:08.25
            <v Speaker><c.yellow><i>Hello &amp; welcome &#39;friend&#39;</i></c></v>

            2
            00:02:10,5 --> 00:02:14
            {\an8}Karaoke <00:02:11.200>line &quot;two&quot;
        """.trimIndent()

        val cues = SuperStreamExtractor.parseSuperStreamCues(vttContent)
        assertEquals(2, cues.size)
        assertEquals("00:01:05,500 --> 00:01:08,250", cues[0].timestamp)
        assertEquals("<i>Hello & welcome 'friend'</i>", cues[0].text)
        assertEquals("00:02:10,500 --> 00:02:14,000", cues[1].timestamp)
        assertEquals("{\\an8}Karaoke line \"two\"", cues[1].text)
    }

    @Test
    fun `parseGoogleBatchResponse handles single-cue multi-sentence nested JSONArray and batch responses`() {
        val multiSentenceSingleCueJson = """
            [[["ہم کہاں ہیں؟","Where are we?"],["مجھے نہیں معلوم۔","I don't know."]],null,"en"]
        """.trimIndent()

        val singleParsed = SuperStreamExtractor.parseGoogleBatchResponse(multiSentenceSingleCueJson, expectedCount = 1)
        assertNotNull(singleParsed)
        assertEquals(1, singleParsed!!.size)
        assertEquals("ہم کہاں ہیں؟ مجھے نہیں معلوم۔", singleParsed[0])

        val multiCueJson = """
            ["پہلا جملہ", "دوسرا جملہ"]
        """.trimIndent()
        val multiParsed = SuperStreamExtractor.parseGoogleBatchResponse(multiCueJson, expectedCount = 2)
        assertEquals(listOf("پہلا جملہ", "دوسرا جملہ"), multiParsed)
    }

    @Test
    fun `translateEnglishSrtToUrdu preserves inline tags and bisects failed 250-cue batches into 50-cue sub-batches`() = runBlocking {
        val sb = StringBuilder()
        for (i in 1..260) {
            sb.append("$i\n")
            sb.append("00:00:%02d,000 --> 00:00:%02d,500\n".format(i % 60, (i + 1) % 60))
            sb.append("{\\an8}<i>Line number $i</i>\n\n")
        }

        val urduSrt = SuperStreamExtractor.translateEnglishSrtToUrdu(sb.toString()) { batch ->
            if (batch.size > 50) {
                null
            } else {
                batch.map { "اردو ترجمہ: $it" }
            }
        }
        assertNotNull("Expected 50-cue sub-batch bisection to recover failed 250-cue batch", urduSrt)
        assertTrue(urduSrt!!.contains("{\\an8}"))
        assertTrue(urduSrt.contains("<i>"))
        assertTrue(urduSrt.contains("\u200F"))
        val parsed = SuperStreamExtractor.parseSuperStreamCues(urduSrt)
        assertEquals(260, parsed.size)
    }

    @Test
    fun `writeLocalUrduSrtFile creates canonical triple-slash file URI with UTF-8 BOM and validates disk existence`() {
        val sampleSrt = "1\n00:00:01,000 --> 00:00:03,000\n\u200Fیہ اردو سب ٹائٹل ہے\u200F\n"
        val uri = SuperStreamExtractor.writeLocalUrduSrtFile("superstream-test-key", sampleSrt)
        assertNotNull(uri)
        assertTrue("URI must start with canonical file:/// (actual: $uri)", uri!!.startsWith("file:///"))
        assertTrue(SuperStreamExtractor.isCachedSubtitleUriValid(uri))

        val diskFile = File(uri.removePrefix("file://"))
        assertTrue(diskFile.exists())
        val rawBytes = diskFile.readBytes()
        assertTrue(
            "File must begin with UTF-8 BOM (EF BB BF)",
            rawBytes.size > 3 &&
                rawBytes[0] == 0xEF.toByte() &&
                rawBytes[1] == 0xBB.toByte() &&
                rawBytes[2] == 0xBF.toByte()
        )

        assertTrue(diskFile.delete())
        assertFalse(SuperStreamExtractor.isCachedSubtitleUriValid(uri))
    }

    @Test
    fun `SubDL keyless autocomplete fail-closed year check and episode zip extraction`() {
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
        assertNull(SuperStreamExtractor.parseSubDlAutoSlug(autoJsonWithYears, expectedYear = 2021))
        assertEquals("/subtitle/sd1001/dune-1984", SuperStreamExtractor.parseSubDlAutoSlug(autoJsonWithYears, expectedYear = 1984))

        val subDlHtml = """
            <div class="sub-row">
              <span>Show.S01E01.720p.Urdu</span>
              <a href="https://dl.subdl.com/subtitle/3100001-8200001.zip">Download</a>
            </div>
            <div class="sub-row">
              <span>Show.S01E02.720p.Urdu</span>
              <a href="https://dl.subdl.com/subtitle/3100002-8200002.zip">Download</a>
            </div>
        """.trimIndent()
        assertEquals(
            listOf("https://dl.subdl.com/subtitle/3100002-8200002.zip"),
            SuperStreamExtractor.parseSubDlUrduPageZips(subDlHtml, season = 1, episode = 2)
        )
    }

    @Test
    fun `SuperStream Urdu source labels cs_urdu cache filename and containsVerifiedUrduScript`() {
        assertEquals("Urdu [SubDL]", formatSuperStreamUrduSourceLabel("subdl"))
        assertEquals("Urdu [Wyzie]", formatSuperStreamUrduSourceLabel("wyziesub"))
        assertEquals("Urdu [SubSense]", formatSuperStreamUrduSourceLabel("subsense"))
        assertEquals("Urdu [OpenSubtitles]", formatSuperStreamUrduSourceLabel("subtitles"))
        assertEquals("Urdu [Neural AI]", formatSuperStreamUrduSourceLabel("urdu-neural-fallback"))
        assertNull(canonicalSuperStreamUrduSourceKey("vidlink"))
        assertNull(canonicalSuperStreamUrduSourceKey("addic7ed"))

        // Verify local cache filename uses cs_urdu_ and never contains "superstream" (which DownloadSubtitlePolicy bans)
        val sampleSrt = "1\n00:00:01,000 --> 00:00:03,000\n\u200Fیہ اردو سب ٹائٹل ہے\u200F\n"
        val uri = SuperStreamExtractor.writeLocalUrduSrtFile("filename-check", sampleSrt)
        assertNotNull(uri)
        assertFalse(
            "Local Urdu cache URI must never contain 'superstream' so DownloadSubtitlePolicy does not ban it: $uri",
            uri!!.lowercase().contains("superstream")
        )
        assertTrue(uri.contains("cs_urdu_"))
        File(uri.removePrefix("file://")).delete()

        // Verify containsVerifiedUrduScript rejects English-only files
        val englishOnly = "1\n00:00:01,000 --> 00:00:03,000\nHello world\n\n2\n00:00:04,000 --> 00:00:06,000\nEnglish text\n"
        assertFalse(SuperStreamExtractor.containsVerifiedUrduScript(englishOnly))
        assertTrue(SuperStreamExtractor.containsVerifiedUrduScript(sampleSrt))
    }
}
