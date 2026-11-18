package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.DonorProvenance
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.LatinEnglishText
import com.phisher98.subtitles.LocalizationTierKey
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.ProvenanceIdentity
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.StreamProviderKey
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
        UrduSubtitleEngine.clearCachesForTest()
    }

    @After
    fun tearDown() {
        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        System.clearProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP)
        UrduSubtitleEngine.clearCachesForTest()
    }

    @Test
    fun `Default English mode is active out of the box and switches to Urdu and back with zero latency`() {
        System.clearProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP)
        assertEquals(UrduSubtitleEngine.SubtitleMode.DEFAULT, UrduSubtitleEngine.getSubtitleMode())
        assertFalse("Default mode out-of-the-box must NOT be Urdu mode", UrduSubtitleEngine.isUrduMode())

        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.URDU)
        assertEquals(UrduSubtitleEngine.SubtitleMode.URDU, UrduSubtitleEngine.getSubtitleMode())
        assertTrue("Expected isUrduMode() == true immediately after switching to URDU", UrduSubtitleEngine.isUrduMode())
        assertEquals("urdu", System.getProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP))

        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        assertEquals(UrduSubtitleEngine.SubtitleMode.DEFAULT, UrduSubtitleEngine.getSubtitleMode())
        assertFalse("Expected isUrduMode() == false immediately after switching back to DEFAULT", UrduSubtitleEngine.isUrduMode())
        assertEquals("default", System.getProperty(UrduSubtitleEngine.SUBTITLE_MODE_SYS_PROP))
    }

    @Test
    fun `resolveSessionMode distinguishes DEFAULT URDU and BILINGUAL_DOWNLOAD callbacks`() {
        class BilingualDownloadSubtitleCallback : (SubtitleFile) -> Unit {
            override fun invoke(p1: SubtitleFile) = Unit
        }
        val plainCallback: (SubtitleFile) -> Unit = {}
        val bilingualCallback = BilingualDownloadSubtitleCallback()

        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.DEFAULT)
        assertEquals(SessionSubtitleMode.DEFAULT, UrduSubtitleEngine.resolveSessionMode(plainCallback))
        assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, UrduSubtitleEngine.resolveSessionMode(bilingualCallback))

        UrduSubtitleEngine.setSubtitleMode(UrduSubtitleEngine.SubtitleMode.URDU)
        assertEquals(SessionSubtitleMode.URDU, UrduSubtitleEngine.resolveSessionMode(plainCallback))
        assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, UrduSubtitleEngine.resolveSessionMode(bilingualCallback))
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
            "Urdu [UrduAI]",
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

        assertEquals(sample, decodedLe.trim())
        assertEquals(sample, decodedBe.trim())
    }

    @Test
    fun `decodeAndNormalizeUrduBytes detects and decodes legacy Windows-1256 Urdu bytes without misdetecting Windows-1252`() {
        val cp1256RawInput = "1\n00:00:02,000 --> 00:00:05,000\nپاکستان ميں ٹرين چل رہي ہے، گاڑي اور پھول بڑي خوبصورت ہيں"
        val cp1256Bytes = cp1256RawInput.toByteArray(Charset.forName("windows-1256"))

        val decodedUrdu = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(cp1256Bytes)
        assertTrue("Decoded CP1256 text must preserve Urdu Pe/Te/Che/Gaf/BariYeh", decodedUrdu.contains("پاکستان"))
        assertTrue(decodedUrdu.contains("ٹرین چل رہی ہے"))
        assertTrue(decodedUrdu.contains("گاڑی اور پھول بڑی خوبصورت ہیں"))

        val englishWithSmartQuotesAndAccents = """
            1
            00:00:01,000 --> 00:00:04,000
            It’s a “naïve” café in Paris — don’t worry about the résumé!
        """.trimIndent()
        val cp1252Bytes = englishWithSmartQuotesAndAccents.toByteArray(Charset.forName("windows-1252"))
        val decodedEnglish = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(cp1252Bytes, normalizeTypography = false)
        assertTrue("Decoded CP1252 text must preserve 'café' and 'naïve', got: $decodedEnglish", decodedEnglish.contains("café") && decodedEnglish.contains("naïve"))
    }

    @Test
    fun `decodeAndNormalizeUrduBytes inflates GZIP and multi-entry ZIP selecting exact episode and rejecting mismatched packs`() {
        val srtContent = "1\n00:00:01,000 --> 00:00:02,500\nیہ ایک اردو سب ٹائٹل ہے\n"

        val gzipOut = ByteArrayOutputStream()
        GZIPOutputStream(gzipOut).use { it.write(srtContent.toByteArray(Charsets.UTF_8)) }
        val decodedGzip = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(gzipOut.toByteArray())
        assertEquals(srtContent.trim(), decodedGzip.trim())

        val ep1Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر ایک\n"
        val ep2Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر دو\n"
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("release_info.nfo"))
            zos.write("Release notes that must be ignored".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E01.1080p.Urdu.srt"))
            zos.write((ep1Srt + "\n".repeat(200)).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E02.1080p.Urdu.srt"))
            zos.write(ep2Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = zipOut.toByteArray()

        val decodedEp2 = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(zipBytes, season = 1, episode = 2)
        assertTrue("Expected S01E02 content, got: $decodedEp2", decodedEp2.contains("قسط نمبر دو"))
        assertFalse(decodedEp2.contains("قسط نمبر ایک"))

        val decodedEp9 = UrduSubtitleEngine.decodeAndNormalizeUrduBytes(zipBytes, season = 1, episode = 9)
        assertEquals("", decodedEp9.trim())
    }

    @Test
    fun `normalizeUrduTypography decomposes presentation forms and maps Goal Heh and Yeh Barree`() {
        val raw = "\uFB56\u0627\u0643\u0633\u062A\u0627\u0646 \u064A\u0647 \u06D5 \u0628\u0691\u06D3"
        val normalized = UrduSubtitleEngine.normalizeUrduTypography(raw)
        assertEquals("\u067E\u0627\u06A9\u0633\u062A\u0627\u0646 \u06CC\u06C1 \u06C1 \u0628\u0691\u06D2", normalized)
    }

    @Test
    fun `LatinEnglishText strips spliced Nastaliq and fails closed on non-Latin cues before NMT`() {
        val sanitized = LatinEnglishText.sanitize("<b>Hold on!</b> [sighs] Where are we?")
        assertFalse(sanitized.text.contains("[sighs]"))
        assertFalse(LatinEnglishText.containsArabicOrNastaliq(sanitized.text))

        val mixed = LatinEnglishText.sanitize("ذرا رکو Where are we?")
        assertFalse(
            "Spliced Nastaliq must never reach LatinEnglishText NMT input",
            LatinEnglishText.containsArabicOrNastaliq(mixed.text)
        )
        assertEquals("Where are we?", mixed.text)

        val pureNastaliq = LatinEnglishText.normalizeFromRawCue("ذرا رکو")
        assertTrue("Pure Nastaliq cue must fail normalizeFromRawCue", pureNastaliq.isFailure)
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

        val urduRows = StreamPlayExtractor.parseYtsSubsRowsForLanguage(html, "Urdu")
        assertEquals(1, urduRows.size)
        assertEquals(
            "https://subtitles.yts-subs.com/subtitles/inception-2010-urdu-yify-9003.zip",
            urduRows[0].url
        )
    }

    @Test
    fun `parseSubtitleCatUrduDetail and parseSubtitleCatOrigSrt handle State A and State B without upload endpoints`() {
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
    }

    @Test
    fun `Urdu sources registry admits verified catalogues and native stream providers while rejecting English-only keys`() {
        assertEquals(8, UrduSubtitleEngine.TOTAL_URDU_SOTA_SOURCES)
        assertEquals(8, UrduSubtitleEngine.URDU_SOTA_SOURCES.size)
        val summary = UrduSubtitleEngine.sotaSourcesSummary()
        assertTrue(summary.contains("8 Urdu sources"))
        assertFalse(summary.contains("SOTA"))

        val expectedCatalogueAndNativeLabels = mapOf(
            "subdl" to "Urdu [SubDL]",
            "subsource" to "Urdu [SubSource]",
            "wyziesub" to "Urdu [Wyzie]",
            "subsense" to "Urdu [SubSense]",
            "yify" to "Urdu [YIFY]",
            "subf2m" to "Urdu [Subf2m]",
            "subtitlecat" to "Urdu [SubtitleCat]",
            "opensubtitles" to "Urdu [OpenSubtitles]",
            "subtitles" to "Urdu [OpenSubtitles]",
            "vidlink" to "Urdu [VidLink]",
            "anizone" to "Urdu [AniZone]",
            "urdu-neural-fallback" to "Urdu [UrduAI]"
        )
        for ((key, expectedLabel) in expectedCatalogueAndNativeLabels) {
            val formatted = UrduSubtitleEngine.formatUrduSourceLabel(key)
            assertEquals("Unexpected label for key '$key'", expectedLabel, formatted)
            assertEquals(expectedLabel, cleanSubtitleLabel(formatted))
            assertNotNull("Expected badge extraction for '$formatted'", UrduSubtitleEngine.extractUrduSourceBadge(formatted))
        }

        for (rejected in listOf("addic7ed", "vidsrc", "superstream", "")) {
            assertNull(
                "Decommissioned or English-only key '$rejected' must be rejected",
                UrduSubtitleEngine.canonicalUrduSourceKey(rejected)
            )
        }
    }

    @Test
    fun `SubtitleSourceLedger counts native and catalogue Urdu toward human diversity and isolates provider-anchor emissions`() = runBlocking {
        val ledger = SubtitleSourceLedger(targetEnglishSources = 6, targetUrduSources = 2)

        val nativeVidLinkUrdu = com.lagradost.cloudstream3.newSubtitleFile(lang = "Urdu [VidLink]", url = "file:///tmp/vidlink_ur.vtt") {
            this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.StreamProvider(StreamProviderKey.VIDLINK))
        }
        val translatedBolChalUrdu = com.lagradost.cloudstream3.newSubtitleFile(lang = "Urdu [UrduAI Wyzie]", url = "file:///tmp/bolchal_ur.vtt") {
            this.headers = ProvenanceCodec.encodeHeaders(
                ProvenanceIdentity.BolChalTranslated(
                    donor = DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                    engine = LocalizationTierKey.KEYLESS_BOLCHAL_NMT,
                )
            )
        }
        val providerAnchorEn = com.lagradost.cloudstream3.newSubtitleFile(lang = "English [VidLink]", url = "https://cdn.example.com/en.vtt") {
            this.headers = ProvenanceCodec.encodeHeaders(
                provenance = ProvenanceIdentity.StreamProvider(StreamProviderKey.VIDLINK),
                syncRole = ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR,
            )
        }
        val visibleEnSameUrl = com.lagradost.cloudstream3.newSubtitleFile(lang = "English [VidLink]", url = "https://cdn.example.com/en.vtt") {
            this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.StreamProvider(StreamProviderKey.VIDLINK))
        }

        assertTrue(ledger.markEmitted(nativeVidLinkUrdu.lang, nativeVidLinkUrdu.url, nativeVidLinkUrdu.headers))
        ledger.recordSubtitleFile(nativeVidLinkUrdu)
        assertEquals(1, ledger.urduSourceCount())
        assertEquals(1, ledger.nativeOrHumanUrduSourceCount())

        assertTrue(ledger.markEmitted(translatedBolChalUrdu.lang, translatedBolChalUrdu.url, translatedBolChalUrdu.headers))
        ledger.recordSubtitleFile(translatedBolChalUrdu)
        assertEquals(2, ledger.urduSourceCount())
        assertEquals("Translated Urdu must not inflate nativeOrHumanUrduSourceCount", 1, ledger.nativeOrHumanUrduSourceCount())

        assertTrue(ledger.markEmitted(providerAnchorEn.lang, providerAnchorEn.url, providerAnchorEn.headers))
        assertTrue(
            "Visible English subtitle sharing URL with provider-anchor must still emit independently",
            ledger.markEmitted(visibleEnSameUrl.lang, visibleEnSameUrl.url, visibleEnSameUrl.headers)
        )
    }

    @Test
    fun `StreamPlayAnime buildAnimeMediaLookupSpec uses UnverifiedAnimeCour when TMDB mapping is unverified`() {
        val unmappedCour2 = StreamPlayAnime.LinkData(
            title = "Bleach: Thousand-Year Blood War - The Conflict",
            jpTitle = "Bleach: Sennen Kessen-hen - Soukoku-tan",
            year = 2024,
            airedYear = 2022,
            season = 1,
            episode = 5,
            tmdbSeason = -1,
            tmdbEpisode = -1,
            isAnime = true,
            runtimeMinutes = 24,
        )

        val unmappedSpec = StreamPlayAnime.buildAnimeMediaLookupSpec(
            mediaData = unmappedCour2,
            effectiveTmdbId = 30984,
            animeImdbId = "tt0434665",
            animeTitleQueries = listOf("Bleach: Thousand-Year Blood War - The Conflict"),
            tmdbSeason = null,
            tmdbEpisode = null,
            canUseTmdbProviders = false,
        )
        assertTrue(unmappedSpec is MediaLookupSpec.Episode)
        val epSpec = unmappedSpec as MediaLookupSpec.Episode
        assertTrue(
            "Unmapped multi-cour anime must use UnverifiedAnimeCour instead of defaulting to S01",
            epSpec.coordinate is EpisodeCoordinate.UnverifiedAnimeCour
        )
        val courCoord = epSpec.coordinate as EpisodeCoordinate.UnverifiedAnimeCour
        assertEquals(5, courCoord.episodeInCour)
        assertEquals(24L * 60_000L, epSpec.expectedDurationMs)

        val mappedSpec = StreamPlayAnime.buildAnimeMediaLookupSpec(
            mediaData = unmappedCour2.copy(tmdbSeason = 2, tmdbEpisode = 31),
            effectiveTmdbId = 30984,
            animeImdbId = "tt0434665",
            animeTitleQueries = listOf("Bleach: Thousand-Year Blood War - The Conflict"),
            tmdbSeason = 2,
            tmdbEpisode = 31,
            canUseTmdbProviders = true,
        ) as MediaLookupSpec.Episode
        assertEquals(EpisodeCoordinate.VerifiedSeasonEpisode(2, 31), mappedSpec.coordinate)
    }

    @Test
    fun `containsVerifiedUrduScript accepts genuine Urdu subtitles and rejects mislabeled English Persian and Arabic subtitles`() {
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
        """.trimIndent()
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(persianMultiCueSrt))

        val arabicMultiCueSrt = """
            1
            00:00:01,000 --> 00:00:03,500
            مرحبًا بك في هذا العالم، ماذا تفعل هنا؟

            2
            00:00:04,000 --> 00:00:06,500
            هذا هو اليوم الذي ننتظره
        """.trimIndent()
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(arabicMultiCueSrt))
    }

    @Test
    fun `Persian subtitle whose only Urdu evidence is shared homographs is rejected even after typography normalization`() {
        val persianSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nتو کجا رفتی؟\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nمن منتظرت بودم\n")
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(persianSrt))
    }

    @Test
    fun `Arabic file that normalizes into Urdu homographs is rejected by the raw-orthography scan`() {
        val arabicSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nهو في البيت الآن\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nهل سمعت الخبر الجديد اليوم\n")
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(arabicSrt))
    }

    @Test
    fun `Persian raw orthography (هست) is rejected by the raw scan before normalization`() {
        val persianSrt = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nتو خوب هستی\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nاین خانه بزرگ است\n")
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(persianSrt))
    }

    @Test
    fun `Token-free Persian padding with one Urdu line is rejected by the evidence-share gate`() {
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
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(padded))
    }

    @Test
    fun `Persian homograph vocabulary and raha padding cannot earn Urdu-evidence credit`() {
        val persianHava = buildString {
            for (i in 1..30) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("هوا سرد بود و باران می\u200Cبارید\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(persianHava))

        assertEquals("رہا", UrduSubtitleEngine.normalizeUrduTypography("رها"))
        assertTrue(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("رہا"))
        assertFalse(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("رها"))
        assertFalse(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("اب"))
        assertFalse(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn("سب"))

        val rahaPadded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("رها شد\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(rahaPadded))
    }

    @Test
    fun `Persian ZWNJ verb prefixes and Arabic hamza orthography are detected structurally`() {
        val zwnjPadded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("پدرم چای می\u200Cخورد\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(zwnjPadded))

        val hamzaPadded = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..7) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("أين أخي الآن؟\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(hamzaPadded))
    }

    @Test
    fun `Short genuine Urdu snippets vocative-dense files and standard gol-heh orthography are accepted`() {
        val short3 = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nواہ!\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nخوبصورت ہے\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nشکریہ\n")
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(short3))

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
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(vocativeDense))

        val standard = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nمیں پہلے ہی کہہ چکا تھا کہ میں نہیں آؤں گا\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nتم بھی وہی کہو گے جو سب کہتے ہیں\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nہم نے پھر بھی اپنا فیصلہ سنایا\n\n")
            append("4\n00:00:04,000 --> 00:00:05,000\nآخرکار سب کو میری بات ماننی پڑی\n")
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(standard))
    }

    @Test
    fun `Adversarial colloquial-Persian corpus and dual-counting probes are rejected while genuine Urdu corpus is accepted`() {
        val persianCorpus = listOf(
            "یه لحظه صبر کن", "یه شب تمام روید", "یه جایی می‌رم که هیچکس نباشه",
            "است یه بار دیگه فکر کن", "پدرم چای می‌خورد و روزنامه می‌خوند",
            "او به مدرسه رفت و کتابش را جا گذاشت", "این خانه بزرگ‌تر از آن یکی است",
            "أين أخي الآن؟ هل سمعت الخبر؟", "حالت چطوره؟ دیر رسیدی",
            "دوستم تلفن کرد و گفت بیا", "سارا نان خرید و رفت", "رها شد و رفت"
        )
        val persianFile = buildString {
            persianCorpus.forEachIndexed { idx, line ->
                append("${idx + 1}\n00:00:${"%02d".format(idx + 1)},000 --> 00:00:${"%02d".format(idx + 1)},500\n")
                append(line + "\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(persianFile))

        val urduCorpus = listOf(
            "یہ گھر بہت خوبصورت ہے اور تم اسے پسند کرو گے",
            "کل ہم سب بازار جائیں گے اور ٹھیلوں سے خریداری کریں گے",
            "میں نہیں جاؤں گا، تم یہاں رہو",
            "وہ رہا ہے اور ہم اس کا انتظار کر رہے ہیں",
            "یہ سؤال مؤثر ہے اور جائزہ لیا گیا",
            "خدا حافظ، کل ملیں گے",
            "بہت شکریہ، آپ کی مدد کے لیے",
            "واہ! کیا بات ہے"
        )
        val urduFile = buildString {
            urduCorpus.forEachIndexed { idx, line ->
                append("${idx + 1}\n00:00:${"%02d".format(idx + 1)},000 --> 00:00:${"%02d".format(idx + 1)},500\n")
                append(line + "\n\n")
            }
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(urduFile))

        val inoculated = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("است یه\n\n")
            }
        }
        assertFalse(UrduSubtitleEngine.containsVerifiedUrduScript(inoculated))
    }

    private fun tokensOf(pattern: String): List<String> {
        val start = pattern.indexOf("])(") + 3
        val end = listOf(pattern.lastIndexOf(")(?:$|"), pattern.lastIndexOf(")(?=$|")).filter { it >= 0 }.max()
        return pattern.substring(start, end).split('|')
            .filter { it.isNotBlank() }
            .map { token ->
                Regex("\\\\u([0-9A-Fa-f]{4})").replace(token) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }
            }
    }

    @Test
    fun `Every curated token matches its own regex and no fa-ar token collides with common Urdu vocabulary`() {
        val faTokens = tokensOf(UrduSubtitleEngine.RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.pattern)
        assertTrue(faTokens.size > 100)
        for (token in faTokens) {
            assertTrue(
                "Raw fa/ar token must match its own regex: $token",
                UrduSubtitleEngine.RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.containsMatchIn(token)
            )
        }
        val evTokens = tokensOf(UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.pattern)
        assertTrue(evTokens.size > 40)
        for (token in evTokens) {
            assertTrue(
                "Evidence token must match its own regex: $token",
                UrduSubtitleEngine.URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn(token)
            )
        }

        val urduVocabulary = setOf(
            "آؤ", "آسمان", "آنا", "آنسو", "آپ", "آگے", "آہستہ", "البتہ", "امن", "امید",
            "امیر", "اندر", "اوپر", "اٹھنا", "اپنی", "اپنے", "اچھا", "بائیں", "بات", "باتیں",
            "باد", "بادشاہ", "بادل", "بارش", "بالکل", "باہر", "بتاؤ", "بددلی", "بدصورت", "برا",
            "برادر", "براہ", "برسات", "بعد", "بلہ", "بندوق", "بندہ", "بولنا", "بوڑھا", "بچنا",
            "بچہ", "بڑا", "بکواس", "بھئی", "بھائی", "بھابھی", "بھاگنا", "بھوک", "بھیجنا", "بہار",
            "بہت", "بہن", "بہنوئی", "بیت", "بیماری", "بین", "بیوقوف", "بیٹا", "بیٹھنا", "بیٹی",
            "تبہ", "تعجب", "تلوار", "تلک", "تم", "تمہاری", "تن", "تھا", "تھوڑا", "تھی", "تھے",
            "تیز", "جاؤ", "جائزہ", "جائیں", "جانا", "جانم", "جاگنا", "جاہل", "جج", "جدا", "جزء",
            "جسم", "جنگ", "جواب", "جوان", "جھوٹ", "جی", "جینا", "حاضر", "حبیب", "حتما", "حتی",
            "حسد", "حوصلہ", "حیا", "حیرت", "خالہ", "خاموش", "خاندان", "خانہ", "خبر", "خبردار",
            "خدا", "خدایا", "خزاں", "خواب", "خواهر", "خوب", "خوبصورت", "خوشگل", "خوشی", "خوف",
            "خیال", "خیانت", "خیلی", "دائما", "دائیں", "دادا", "دادی", "دار", "دانشمند", "درد",
            "دریا", "دست", "دشمن", "دعا", "دل", "دلبر", "دماغ", "دن", "دنیا", "دوائی", "دور",
            "دوست", "دکھ", "دھوپ", "دھوکا", "دھکا", "دینا", "دیوانہ", "دیکھنا", "دیکھو", "ذرا",
            "رات", "رب", "رشتہ", "رشتے", "رفیق", "روتا", "روز", "رونا", "رکو", "رہا", "رہی", "رہے",
            "زخم", "زم", "زمین", "زن", "زندگی", "زور", "زیادہ", "سؤال", "ساتھی", "ساس", "سال",
            "سب", "ستارے", "سجن", "سر", "سردی", "سلام", "سمجھنا", "سننا", "سنو", "سنگتی", "سوال",
            "سورج", "سونا", "سوچو", "سپاہی", "سچ", "سکھ", "شام", "شب", "شرم", "شفقت", "شک", "شہر",
            "شیء", "صبح", "صحت", "صدا", "صنم", "ضرورت", "طاقت", "طبعا", "طبیعت", "طریق", "عاجزی",
            "عاشق", "عالم", "عدالت", "عزت", "عزیز", "عشق", "عقلمند", "علاج", "علی", "عوام", "عورت",
            "عین", "غریب", "غصہ", "غلام", "غم", "غیر", "فعلا", "فقط", "فوجی", "فوق", "فی", "فیصلہ",
            "قانون", "قبل", "قریب", "قیدی", "كيف", "لمبا", "لونڈا", "لڑائی", "لڑکا", "لڑکی", "لکھنا",
            "لینا", "لیکن", "مؤثر", "ما", "ماء", "مادر", "ماموں", "ماہ", "محبت", "محبوب", "مخلوق",
            "مدد", "مرد", "مردم", "مرنا", "مسکراہٹ", "مضبوط", "مع", "معاف", "معشوق", "ملنا", "ممتا",
            "موسم", "مگر", "می", "میری", "نام", "نانا", "نانی", "ناچنا", "نحو", "نفرت", "نفسم",
            "نہ", "نہیں", "نیا", "نیند", "نیچے", "واقعا", "والے", "ورنہ", "وزیر", "وفا", "وقار",
            "وقت", "وقتی", "ولد", "وہ", "ٹوٹا", "ٹھنڈی", "ٹھیک", "پانی", "پاگل", "پدر", "پرانا",
            "پوتا", "پوتی", "پولیس", "پڑھنا", "پکڑنا", "پکڑو", "پھر", "پہلے", "پہنچنا", "پیاس",
            "پیچھے", "چاند", "چاچا", "چرا", "چلنا", "چلو", "چون", "چوڑا", "چپ", "چھوٹا", "چھوڑنا",
            "کا", "کائنات", "کار", "کان", "کرنا", "کرو", "کریں", "کل", "کم", "کمزور", "کند", "کو",
            "کوئی", "کوچک", "کچھ", "کھانا", "کھیلنا", "کہ", "کہنا", "کی", "کیجئے", "کیف", "کے",
            "گانا", "گرمی", "گواہ", "گھر", "ہست", "ہفتہ", "ہم", "ہمارا", "ہمہ", "ہمین", "ہن",
            "ہنسنا", "ہو", "ہوا", "ہونا", "ہی", "ہیں", "ہے", "یار", "یقین", "یوم", "یہ"
        )
        for (token in faTokens) {
            assertFalse("fa/ar token must not BE an Urdu word: $token", token in urduVocabulary)
            assertFalse(
                "fa/ar token must not normalize into an Urdu word: $token -> ${UrduSubtitleEngine.normalizeUrduTypography(token)}",
                UrduSubtitleEngine.normalizeUrduTypography(token) in urduVocabulary
            )
        }
    }

    @Test
    fun `Pipeline-level probes are rejected and genuine Urdu is materialized via UrduCasStore`() = runBlocking {
        val probes = listOf(
            buildString {
                val lines = listOf("یه لحظه صبر", "یه شب رفت", "یه جایی میرم", "یه سال گذشت")
                for ((idx, line) in lines.withIndex()) {
                    append("${idx + 1}\n00:00:${"%02d".format(idx + 1)},000 --> 00:00:${"%02d".format(idx + 1)},500\n")
                    append(line + "\n\n")
                }
            },
            buildString {
                append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
                for (i in 2..13) {
                    append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                    append("رها شد\n\n")
                }
            },
            buildString {
                append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
                for (i in 2..13) {
                    append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                    append("است یه\n\n")
                }
            }
        )
        for ((probeIdx, srt) in probes.withIndex()) {
            val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/probe.srt") { exchange ->
                val bytes = srt.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}/probe.srt?probe=$probeIdx&run=${System.nanoTime()}"
                val result = UrduSubtitleEngine.materializeNormalizedUrduSubtitle(
                    url = url,
                    sourceTag = "subdl",
                    transport = com.phisher98.subtitles.DefaultCatalogHttpTransport(allowPrivateNetworks = true),
                )
                assertNull("Pipeline probe #$probeIdx must be REJECTED through materialize", result)
            } finally {
                server.stop(0)
            }
        }

        val validUrduSrt = buildString {
            for (i in 1..20) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\n")
                append("یہ اردو سب ٹائٹل لائن $i ہے اور ہم یہاں جا رہے ہیں\n\n")
            }
        }
        val goodServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        goodServer.createContext("/valid.srt") { exchange ->
            val bytes = validUrduSrt.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        goodServer.start()
        try {
            val url = "http://127.0.0.1:${goodServer.address.port}/valid.srt?run=${System.nanoTime()}"
            val resultUri = UrduSubtitleEngine.materializeNormalizedUrduSubtitle(
                url = url,
                sourceTag = "subdl",
                transport = com.phisher98.subtitles.DefaultCatalogHttpTransport(allowPrivateNetworks = true),
            )
            assertNotNull("Valid multi-cue Urdu SRT must materialize into UrduCasStore", resultUri)
            assertTrue("Expected file: URI, got: $resultUri", resultUri!!.startsWith("file:/"))
            val outFile = java.io.File(java.net.URI(resultUri))
            assertTrue(outFile.exists() && outFile.length() > 64)
        } finally {
            goodServer.stop(0)
        }
    }

    @Test
    fun `Formal Urdu with clashing loanwords and standalone hamza exclamations is accepted`() {
        val formal = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nاجلاس بعد از ظہر ہوگا\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nفقط ارکان شرکت کریں گے\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nعلی گڑھ کی تحریک الگ ہے\n\n")
            append("4\n00:00:04,000 --> 00:00:05,000\nکل ہم نے یہ فیصلہ کیا تھا\n\n")
            append("5\n00:00:05,000 --> 00:00:06,000\nہر نہ نہیں، مگر ہمت نہ ہارو\n\n")
            append("6\n00:00:06,000 --> 00:00:07,000\nیہ ہفتہ قبل سے طے تھا\n\n")
            append("7\n00:00:07,000 --> 00:00:08,000\nخدا حافظ، کل ملیں گے\n\n")
            append("8\n00:00:08,000 --> 00:00:09,000\nشکریہ\n")
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(formal))

        val hamzaDiscriminative = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nانشاء اللہ!\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nماشاء اللہ!\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nیہ بہت اچھا ہے\n")
        }
        assertTrue(UrduSubtitleEngine.containsVerifiedUrduScript(hamzaDiscriminative))
    }
}