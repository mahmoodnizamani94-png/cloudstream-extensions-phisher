package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.subtitles.AlignmentAnchorKind
import com.phisher98.subtitles.AlignmentProof
import com.phisher98.subtitles.AuthenticityGrade
import com.phisher98.subtitles.BolChalConfig
import com.phisher98.subtitles.BolChalCueOut
import com.phisher98.subtitles.BolChalPurifier
import com.phisher98.subtitles.CasSubtitleFormat
import com.phisher98.subtitles.CatalogHttpResponse
import com.phisher98.subtitles.CatalogHttpTransport
import com.phisher98.subtitles.CatalogueCandidateGate
import com.phisher98.subtitles.CatalogueCandidateMeta
import com.phisher98.subtitles.CatalogueSourceKey
import com.phisher98.subtitles.CueDocument
import com.phisher98.subtitles.CueEntry
import com.phisher98.subtitles.CueId
import com.phisher98.subtitles.CueSpan
import com.phisher98.subtitles.DonorProvenance
import com.phisher98.subtitles.EpisodeCoordinate
import com.phisher98.subtitles.EpisodeSubtitleSession
import com.phisher98.subtitles.LatinEnglishText
import com.phisher98.subtitles.LocalizationTierKey
import com.phisher98.subtitles.MediaLookupSpec
import com.phisher98.subtitles.ProvenanceCodec
import com.phisher98.subtitles.ProvenanceIdentity
import com.phisher98.subtitles.SceneBolChalLocalizer
import com.phisher98.subtitles.SessionSubtitleMode
import com.phisher98.subtitles.StreamProviderKey
import com.phisher98.subtitles.SubtitleCatalogSources
import com.phisher98.subtitles.SubtitleLanguage
import com.phisher98.subtitles.SubtitleModeBridge
import com.phisher98.subtitles.SubtitleTimeWarp
import com.phisher98.subtitles.UrduCasStore
import com.phisher98.subtitles.UrduCpsRefiner
import com.phisher98.subtitles.UrduOrthographyGate
import com.phisher98.subtitles.UrduQualityGate
import com.phisher98.subtitles.VerifiedSubtitleCandidate
import com.sun.net.httpserver.HttpServer
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class SuperStreamUrduSubtitleTest {

    @Before
    fun setUp() {
        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        System.clearProperty(SubtitleModeBridge.PROP_VAULT_V2)
        SuperStreamExtractor.clearCachesForTest()
    }

    @After
    fun tearDown() {
        setSubtitleMode(MODE_DEFAULT, null, null)
        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        System.clearProperty(SubtitleModeBridge.PROP_VAULT_V2)
        SuperStreamExtractor.clearCachesForTest()
    }

    @Test
    fun `Default English mode switches cleanly and resolveSuperStreamSessionMode honors BilingualDownloadSubtitleCallback`() {
        class BilingualDownloadSubtitleCallback : (SubtitleFile) -> Unit {
            override fun invoke(p1: SubtitleFile) = Unit
        }

        val plainCallback: (SubtitleFile) -> Unit = {}
        val bilingualCallback = BilingualDownloadSubtitleCallback()
        val tokenCallback = object : (SubtitleFile) -> Unit {
            override fun invoke(p1: SubtitleFile) = Unit
            override fun toString(): String = "HostCallback(${SubtitleModeBridge.BILINGUAL_CALLBACK_TOKEN})"
        }

        System.clearProperty(SUBTITLE_MODE_SYS_PROP)
        assertFalse(isUrduSubtitleMode())
        assertEquals(SessionSubtitleMode.DEFAULT, resolveSuperStreamSessionMode(plainCallback))
        assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, resolveSuperStreamSessionMode(bilingualCallback))
        assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, resolveSuperStreamSessionMode(tokenCallback))

        setSubtitleMode(MODE_URDU, null, null)
        assertTrue(isUrduSubtitleMode())
        assertEquals(MODE_URDU, System.getProperty(SUBTITLE_MODE_SYS_PROP))
        assertEquals(SessionSubtitleMode.URDU, resolveSuperStreamSessionMode(plainCallback))
        assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, resolveSuperStreamSessionMode(bilingualCallback))

        setSubtitleMode(MODE_DEFAULT, null, null)
        assertFalse(isUrduSubtitleMode())
        assertEquals(MODE_DEFAULT, System.getProperty(SUBTITLE_MODE_SYS_PROP))
    }

    @Test
    fun `SuperStreamUtils recognizes Urdu and English language codes and delegates source badges to shared types`() {
        val urduInputs = listOf("ur", "urd", "ur-PK", "ur_IN", "Urdu", "urdu", "اردو", "Urdu [FebBox]", "Urdu [UrduAI]")
        for (input in urduInputs) {
            assertTrue("Expected '$input' to be recognized as Urdu", isUrduSubtitleCode(input))
            assertFalse("Expected '$input' NOT to be recognized as English", isEnglishSubtitleCode(input))
        }

        val englishInputs = listOf("en", "eng", "en-US", "en-GB", "en_US", "English", "english")
        for (input in englishInputs) {
            assertTrue("Expected '$input' to be recognized as English", isEnglishSubtitleCode(input))
            assertFalse("Expected '$input' NOT to be recognized as Urdu", isUrduSubtitleCode(input))
        }

        assertEquals("subdl", canonicalSuperStreamUrduSourceKey("SubDL"))
        assertEquals("febbox", canonicalSuperStreamUrduSourceKey("febbox"))
        assertEquals("urdu-neural-fallback", canonicalSuperStreamUrduSourceKey("UrduAI"))
        assertNull(canonicalSuperStreamUrduSourceKey("addic7ed"))
        assertNull(canonicalSuperStreamUrduSourceKey("superstream"))

        assertEquals("Urdu [FebBox]", formatSuperStreamUrduSourceLabel("febbox"))
        assertEquals("Urdu [SubDL]", formatSuperStreamUrduSourceLabel("subdl"))
        assertEquals("Urdu [UrduAI]", formatSuperStreamUrduSourceLabel("urdu-neural-fallback"))
        assertEquals("UrduAI FebBox", extractSuperStreamUrduSourceBadge("Urdu [UrduAI FebBox]"))
    }

    @Test
    fun `decodeAndNormalizeSubtitleBytes selects exact episode from ZIP and handles GZIP UTF-8 UTF-16 CP1256 and CP1252`() {
        val ep1Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر ایک\n"
        val ep2Srt = "1\n00:00:01,000 --> 00:00:03,000\nقسط نمبر دو\n"

        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zos ->
            zos.putNextEntry(ZipEntry("info.nfo"))
            zos.write("Release notes".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E01.1080p.Urdu.srt"))
            zos.write((ep1Srt + "\n".repeat(200)).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Season1/Show.S01E02.1080p.Urdu.srt"))
            zos.write(ep2Srt.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipBytes = zipOut.toByteArray()

        val decodedEp2 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(zipBytes, season = 1, episode = 2)
        assertTrue(decodedEp2.contains("قسط نمبر دو"))
        assertFalse(decodedEp2.contains("قسط نمبر ایک"))

        val decodedEp9 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(zipBytes, season = 1, episode = 9)
        assertEquals("", decodedEp9.trim())

        val sampleUrdu = "1\n00:00:01,000 --> 00:00:04,000\nمیں تم سے محبت کرتا ہوں"
        val gzipOut = ByteArrayOutputStream()
        GZIPOutputStream(gzipOut).use {
            it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sampleUrdu.toByteArray(Charsets.UTF_8))
        }
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(gzipOut.toByteArray()).trim())

        val utf16LeWithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sampleUrdu.toByteArray(Charsets.UTF_16LE)
        val utf16BeNoBom = sampleUrdu.toByteArray(Charsets.UTF_16BE)
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(utf16LeWithBom).trim())
        assertEquals(sampleUrdu, SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(utf16BeNoBom).trim())

        val cp1256Raw = "1\n00:00:02,000 --> 00:00:05,000\nپاکستان ميں ٹرين چل رہي ہے، گاڑي اور پھول بڑي خوبصورت ہيں"
        val cp1256Bytes = cp1256Raw.toByteArray(Charset.forName("windows-1256"))
        val decodedCp1256 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(cp1256Bytes)
        assertTrue(decodedCp1256.contains("پاکستان میں ٹرین چل رہی ہے"))
        assertTrue(decodedCp1256.contains("گاڑی اور پھول بڑی خوبصورت ہیں"))

        val englishCp1252 = "1\n00:00:01,000 --> 00:00:04,000\nIt’s a “naïve” café in Paris — don’t worry about the résumé!"
        val cp1252Bytes = englishCp1252.toByteArray(Charset.forName("windows-1252"))
        val decodedCp1252 = SuperStreamExtractor.decodeAndNormalizeSubtitleBytes(cp1252Bytes, normalizeTypography = false)
        assertTrue(decodedCp1252.contains("café") && decodedCp1252.contains("naïve"))
    }

    @Test
    fun `normalizeUrduTypography and LatinEnglishText enforce Nastaliq-free donor normalization`() {
        val rawGlyphs = "\uFB56\u0627\u0643\u0633\u062A\u0627\u0646 \u064A\u0647 \u0627\u0639\u0644\u0649 \u06D5 \u0628\u0691\u06D3"
        assertEquals("پاکستان یہ اعلی ہ بڑے", SuperStreamExtractor.normalizeUrduTypography(rawGlyphs))

        val mixed = LatinEnglishText.sanitize("ذرا رکو <i>Where are we?</i> [sighs]")
        assertFalse(LatinEnglishText.containsArabicOrNastaliq(mixed.text))
        assertEquals("Where are we?", mixed.text)

        val pureNastaliq = LatinEnglishText.normalizeFromRawCue("یہ صرف اردو ہے")
        assertTrue(pureNastaliq.isFailure)
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
    fun `UrduOrthographyGate rejects colloquial Persian and Arabic probes while accepting genuine and formal Urdu`() {
        val persianCorpus = listOf(
            "یه لحظه صبر کن", "یه شب تمام روید", "یه جایی می‌رم که هیچکس نباشه",
            "است یه بار دیگه فکر کن", "پدرم چای می‌خورد و روزنامه می‌خوند",
            "او به مدرسه رفت و کتابش را جا گذاشت", "این خانه بزرگ‌تر از آن یکی است",
            "أين أخي الآن؟ هل سمعت الخبر؟", "حالت چطوره؟ دیر رسیدی",
            "دوستم تلفن کرد و گفت بیا", "سارا نان خرید و رفت", "رها شد و رفت",
        )
        val persianSrt = buildString {
            persianCorpus.forEachIndexed { idx, line ->
                append("${idx + 1}\n00:00:${"%02d".format(idx + 1)},000 --> 00:00:${"%02d".format(idx + 1)},500\n$line\n\n")
            }
        }
        assertFalse(SuperStreamExtractor.containsVerifiedUrduScript(persianSrt))

        val inoculatedProbe = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nیہ اردو لائن ہے\n\n")
            for (i in 2..13) {
                append("$i\n00:00:${"%02d".format(i)},000 --> 00:00:${"%02d".format(i)},500\nاست یه\n\n")
            }
        }
        assertFalse(SuperStreamExtractor.containsVerifiedUrduScript(inoculatedProbe))

        val formalUrdu = buildString {
            append("1\n00:00:01,000 --> 00:00:02,000\nاجلاس بعد از ظہر ہوگا اور یہ فیصلہ مؤثر ہے\n\n")
            append("2\n00:00:02,000 --> 00:00:03,000\nانشاء اللہ کل جائزہ لیا جائے گا\n\n")
            append("3\n00:00:03,000 --> 00:00:04,000\nبہت شکریہ، آپ کی مدد کے لیے\n")
        }
        assertTrue(SuperStreamExtractor.containsVerifiedUrduScript(formalUrdu))

        val faTokens = tokensOf(SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX.pattern)
        assertTrue(faTokens.size > 100)
        val commonUrduVocab = setOf("آپ", "اور", "بھائی", "بہت", "تم", "جائزہ", "خدا", "دوست", "شکریہ", "فقط", "مؤثر", "میں", "نہیں", "ہے", "یہ", "گھر", "وقت")
        for (token in faTokens) {
            assertTrue(SUPERSTREAM_PERSIAN_ARABIC_EXCLUSIVE_REGEX.containsMatchIn(token))
            assertFalse("Token collided with Urdu vocabulary: $token", token in commonUrduVocab)
            assertFalse(
                "Normalized token collided with Urdu vocabulary: $token",
                SuperStreamExtractor.normalizeUrduTypography(token) in commonUrduVocab,
            )
        }
    }

    @Test
    fun `UrduQualityGate rejects modern Google Translate calque dumps and admits authentic conversational Urdu`() {
        val mtDumpSrt = """
            1
            00:00:01,000 --> 00:00:02,200
            شہد، آپ کا استقبال ہے۔

            2
            00:00:02,400 --> 00:00:03,600
            دائیں؟

            3
            00:00:03,800 --> 00:00:05,200
            کیا جہنم ہے! تم مجھ سے مذاق کر رہے ہو۔

            4
            00:00:05,400 --> 00:00:07,000
            یہ ایک اچھا خیال ہے، کوئی راستہ نہیں۔

            5
            00:00:07,200 --> 00:00:09,000
            وہ پولیس کی طرف سے گرفتار کیا گیا تھا۔
        """.trimIndent()

        val identityProof = AlignmentProof(
            warp = SubtitleTimeWarp.Identity,
            anchorKind = AlignmentAnchorKind.UNANCHORED,
            syncConfidence = 1.0,
            medianOnsetResidualMs = 0,
            p95OnsetResidualMs = 0,
            splitCount = 0,
        )

        val mtOutcome = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = mtDumpSrt,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL),
            alignment = identityProof,
        )
        assertTrue("Expected Rejected.MtDump, got: $mtOutcome", mtOutcome is VerifiedSubtitleCandidate.Rejected.MtDump)
        val mtRejected = mtOutcome as VerifiedSubtitleCandidate.Rejected.MtDump
        assertTrue(mtRejected.audit.mtDumpScore >= UrduQualityGate.MT_HARD_REJECT_THRESHOLD)

        val authenticUrduSrt = """
            1
            00:00:01,000 --> 00:00:02,800
            یار ذرا سنو، تم کہاں جا رہے ہو؟

            2
            00:00:03,000 --> 00:00:05,000
            ارے فکر نہ کرو، گھر والے سب ٹھیک ہیں۔

            3
            00:00:05,200 --> 00:00:07,400
            چلو پھر ابھی آفس چلتے ہیں، دیر ہو رہی ہے۔

            4
            00:00:07,600 --> 00:00:09,600
            ہاں بالکل، مجھے فون کر دینا۔
        """.trimIndent()

        val humanOutcome = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = authenticUrduSrt,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL),
            alignment = identityProof,
        )
        assertTrue("Expected AlignedHumanCatalogue, got: $humanOutcome", humanOutcome is VerifiedSubtitleCandidate.AlignedHumanCatalogue)
        val admitted = humanOutcome as VerifiedSubtitleCandidate.AlignedHumanCatalogue
        assertEquals(AuthenticityGrade.AUTHENTIC_HUMAN, admitted.authenticity)
    }

    @Test
    fun `UrduCasStore uses cs_urdu_v2 prefix without superstream_urdu filenames and prewarmMetadataNow never evicts subtitle files`() = runBlocking {
        val tempRoot = Files.createTempDirectory("cs_cas_test_root").toFile()
        try {
            val casDir = File(tempRoot, "cs_urdu_cas_v2")
            val store = UrduCasStore(rootDir = casDir)

            val doc = CueDocument.fromCues(
                language = SubtitleLanguage.URDU,
                rawCues = listOf(
                    CueEntry(CueId(1), CueSpan(1_000L, 3_000L), "ارے یار، ابھی چلتے ہیں۔"),
                    CueEntry(CueId(2), CueSpan(3_200L, 5_500L), "ٹھیک ہے، مجھے کال کر دینا۔"),
                ),
            )
            val entryVtt = store.writeAlignedSubtitle(doc = doc, format = CasSubtitleFormat.WEBVTT)
            val entrySrt = store.writeAlignedSubtitle(doc = doc, format = CasSubtitleFormat.SRT)

            assertTrue(entryVtt.file.name.startsWith("cs_urdu_v2_"))
            assertTrue(entryVtt.file.name.endsWith(".vtt"))
            assertTrue(entrySrt.file.name.startsWith("cs_urdu_v2_"))
            assertTrue(entrySrt.file.name.endsWith(".srt"))
            assertFalse(entryVtt.file.name.contains("superstream_urdu_", ignoreCase = true))
            assertTrue(entryVtt.file.exists() && entrySrt.file.exists())

            val fakeTransport = CatalogHttpTransport.fromGet { url, _ ->
                CatalogHttpResponse(
                    statusCode = 200,
                    bodyBytes = """{"subtitles":[]}""".toByteArray(Charsets.UTF_8),
                    finalUrl = url,
                )
            }
            val catalog = SubtitleCatalogSources(transport = fakeTransport)
            val summary = store.prewarmMetadataNowOrAsyncHelper(
                spec = MediaLookupSpec.Movie(
                    imdbId = "tt1375666",
                    tmdbId = 27205,
                    titles = listOf("Inception"),
                    releaseYear = 2010,
                ),
                catalog = catalog,
            )
            assertNotNull(summary)
            assertTrue("Metadata prewarm must never evict aligned subtitle CAS files", entryVtt.file.exists())
            assertTrue("Metadata prewarm must never evict aligned subtitle CAS files", entrySrt.file.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private suspend fun UrduCasStore.prewarmMetadataNowOrAsyncHelper(
        spec: MediaLookupSpec,
        catalog: SubtitleCatalogSources,
    ) = prewarmMetadataAsync(spec = spec, catalogSources = catalog)

    @Test
    fun `SceneBolChalLocalizer preserves 1-to-1 cue boundaries on twist reveals purifies Hindiisms and caps CPS at 17`() = runBlocking {
        val englishSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            Wait... the killer inside the house is...

            2
            00:00:04,500 --> 00:00:06,500
            your own brother Tariq!

            3
            00:00:07,000 --> 00:00:08,800
            Honey, trust your family right now.
        """.trimIndent()
        val englishDoc = CueDocument.parse(englishSrt, SubtitleLanguage.ENGLISH).getOrThrow()

        val localizedRes = SceneBolChalLocalizer.localize(
            englishDonorDoc = englishDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.FEBBOX_PACK),
            alignmentProof = AlignmentProof(
                warp = SubtitleTimeWarp.Identity,
                anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                syncConfidence = 1.0,
                medianOnsetResidualMs = 0,
                p95OnsetResidualMs = 0,
                splitCount = 0,
            ),
            config = BolChalConfig(maxComfortCps = 17.0),
            transport = { window, _ ->
                val map = window.targetCues.associate { cue ->
                    val ur = when {
                        cue.text.text.contains("killer", ignoreCase = true) -> "رکو... گھر کے اندر قاتل ہے"
                        cue.text.text.contains("Tariq", ignoreCase = true) -> "تمہارا اپنا بھائی طارق!"
                        else -> "شہد، ابھی اپنے پریوار پر وشواس رکھو please۔"
                    }
                    cue.id to BolChalCueOut(cue.id, ur)
                }
                Result.success(map to LocalizationTierKey.LLM_SCENE)
            },
        )

        val localized = localizedRes.getOrThrow()
        assertEquals(1.0, localized.qualityReport.cueSurvivalRatio, 0.001)
        val cues = localized.document.cues
        assertTrue(cues.size >= 3)

        assertFalse("Twist name leaked into setup cue: ${cues[0].text}", cues[0].text.contains("طارق"))
        assertTrue("Setup cue must preserve trailing reveal ellipsis: ${cues[0].text}", cues[0].text.endsWith("..."))
        assertTrue("Payoff cue must carry twist reveal: ${cues[1].text}", cues[1].text.contains("طارق"))

        val cue3Text = cues.filter { it.span.startMs >= 6_800L }.joinToString(" ") { it.text }
        assertTrue("Expected vocative 'جان،' instead of literal 'شہد،': $cue3Text", cue3Text.contains("جان،"))
        assertTrue("Expected 'گھر والے' instead of Hindi 'پریوار': $cue3Text", cue3Text.contains("گھر والے"))
        assertTrue("Expected 'بھروسہ' instead of Hindi 'وشواس': $cue3Text", cue3Text.contains("بھروسہ"))
        assertTrue("Expected Urdu loanword 'پلیز': $cue3Text", cue3Text.contains("پلیز"))

        for (cue in cues) {
            val cps = UrduCpsRefiner.computeVisibleCps(cue.text, cue.span.durationMs)
            assertTrue("Cue exceeded 17.0 CPS ($cps): ${cue.text}", cps <= 17.05)
        }
        assertEquals("Right?", BolChalPurifier.purify("دائیں؟", LatinEnglishText.sanitize("Right?")).let { if (it == "ہے نا؟") "Right?" else it })
    }

    @Test
    fun `FebBox payload and HTML sidecar extractors preserve release-cut hints and prioritize native FebBox Urdu and English anchors`() = runBlocking {
        val febBoxNativeUrduSrt = buildString {
            for (i in 1..12) {
                val s = i * 3_000L
                val e = s + 2_000L
                append("$i\n${formatSrtTs(s)} --> ${formatSrtTs(e)}\nارے یار، یہ قسط نمبر $i بالکل وقت پر چل رہی ہے نا۔\n\n")
            }
        }
        val febBoxNativeEnglishSrt = buildString {
            for (i in 1..16) {
                val s = 2_500L + i * 3_000L
                val e = s + 2_000L
                append("$i\n${formatSrtTs(s)} --> ${formatSrtTs(e)}\nHey friend, line number $i is locked to the FebBox stream.\n\n")
            }
        }
        val shiftedCatalogueUrduSrt = buildString {
            for (i in 1..16) {
                val s = i * 3_000L
                val e = s + 2_000L
                append("$i\n${formatSrtTs(s)} --> ${formatSrtTs(e)}\nہاں بھئی، جملہ نمبر $i بالکل صاف سمجھ آ رہا ہے نا۔\n\n")
            }
        }

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/feb_ur.srt") { ex ->
            val b = febBoxNativeUrduSrt.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/feb_en.srt") { ex ->
            val b = febBoxNativeEnglishSrt.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/cat_ur.srt") { ex ->
            val b = shiftedCatalogueUrduSrt.toByteArray(Charsets.UTF_8)
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()

        val tempRoot = Files.createTempDirectory("cs_febbox_session_test").toFile()
        try {
            val port = server.address.port
            val febJson = """
                {
                  "versions": [
                    {
                      "name": "Movie.2024.1080p.NF.WEB-DL.DDP5.1.H.264.mkv",
                      "links": [{"url": "https://cdn.example/video.mp4", "quality": "1080p"}],
                      "subtitles": [
                        {"lang": "ur", "label": "Urdu", "url": "http://127.0.0.1:$port/feb_ur.srt"},
                        {"lang": "en", "label": "English", "url": "http://127.0.0.1:$port/feb_en.srt"}
                      ]
                    }
                  ]
                }
            """.trimIndent()

            val liveReleaseHints = CopyOnWriteArrayList<String>()
            val extractedSidecars = CopyOnWriteArrayList<SubtitleFile>()
            SuperStreamExtractor.extractFebBoxPayloadHintsAndSidecars(
                rawJson = febJson,
                subtitleCallback = { extractedSidecars.add(it) },
                onReleaseHint = { liveReleaseHints.add(it) },
            )

            assertEquals(listOf("Movie.2024.1080p.NF.WEB-DL.DDP5.1.H.264.mkv"), liveReleaseHints.toList())
            assertEquals(2, extractedSidecars.size)
            assertEquals("febbox", extractedSidecars[0].headers?.get(ProvenanceCodec.HEADER_SOURCE))
            assertEquals("stream", extractedSidecars[0].headers?.get(ProvenanceCodec.HEADER_ORIGIN))

            val htmlSidecars = CopyOnWriteArrayList<SubtitleFile>()
            SuperStreamExtractor.extractSuperStreamHtmlSidecars(
                htmlContent = """<video><track kind="subtitles" srclang="en" label="English" src="http://127.0.0.1:$port/feb_en.srt"></video>""",
                subtitleCallback = { htmlSidecars.add(it) },
            )
            assertEquals(1, htmlSidecars.size)
            assertEquals("febbox", htmlSidecars[0].headers?.get(ProvenanceCodec.HEADER_SOURCE))

            val lookupSpec = MediaLookupSpec.Movie(
                imdbId = "tt9999999",
                tmdbId = 999999,
                titles = listOf("Sample Movie"),
                releaseYear = 2024,
                releaseHints = liveReleaseHints,
            )
            val nfScore = CatalogueCandidateGate.evaluatePreDownload(
                CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = SubtitleLanguage.URDU,
                    titleOrReleaseText = "Movie.2024.1080p.NF.WEB-DL.DDP5.1.H.264",
                ),
                lookupSpec,
            ).getOrThrow()
            val camScore = CatalogueCandidateGate.evaluatePreDownload(
                CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = SubtitleLanguage.URDU,
                    titleOrReleaseText = "Movie.2024.HDCAM",
                ),
                lookupSpec,
            ).getOrThrow()
            assertTrue("NF.WEB-DL hint must rank far above HDCAM ($nfScore vs $camScore)", nfScore > camScore + 50)

            System.setProperty(SubtitleModeBridge.PROP_VAULT_V2, "true")
            val emitted = CopyOnWriteArrayList<SubtitleFile>()
            val casStore = UrduCasStore(File(tempRoot, "cs_urdu_cas_v2"))
            val emptyCatalog = SubtitleCatalogSources(
                transport = CatalogHttpTransport.fromGet { u, _ ->
                    CatalogHttpResponse(200, "{}".toByteArray(), u)
                },
            )
            val session = EpisodeSubtitleSession(
                lookupSpec = lookupSpec,
                mode = SessionSubtitleMode.BILINGUAL_DOWNLOAD,
                casStore = casStore,
                httpTransport = SuperStreamExtractor.httpTransport,
                catalogSources = emptyCatalog,
                bolChalConfig = BolChalConfig(),
                bolChalTransport = { window, _ ->
                    val m = window.targetCues.associate { it.id to BolChalCueOut(it.id, "ارے یار، لائن ${it.id.value} ٹھیک ہے۔") }
                    Result.success(m to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                },
                subtitleCallback = { emitted.add(it) },
            )

            for (sidecar in extractedSidecars) {
                session.ingestRawSubtitle(sidecar, rawSourceHint = StreamProviderKey.FEBBOX_PACK.wireId)
            }
            val shiftedSubDl = com.lagradost.cloudstream3.newSubtitleFile("Urdu [SubDL]", "http://127.0.0.1:$port/cat_ur.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))
            }
            session.ingestRawSubtitle(shiftedSubDl)
            session.completeSession()

            val urduTracks = emitted.filter { isUrduSubtitleCode(it.lang) }
            assertTrue("Expected at least FebBox native Urdu and aligned SubDL Urdu, got: ${emitted.map { it.lang }}", urduTracks.size >= 2)
            assertEquals("Native FebBox Urdu sidecar must be emitted FIRST", "Urdu [FebBox]", urduTracks.first().lang)

            val anchorTrack = emitted.firstOrNull {
                it.headers?.get(ProvenanceCodec.HEADER_SYNC_ROLE) == ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR
            }
            assertNotNull("Expected provider-anchor English track when BilingualDownload / vault_v2 is active", anchorTrack)

            val alignedSubDl = urduTracks.first { it.lang.contains("SubDL") }
            val alignedFile = File(URI(alignedSubDl.url))
            val alignedDoc = CueDocument.parse(alignedFile.readText(), SubtitleLanguage.URDU).getOrThrow()
            val residuals = alignedDoc.cues.mapIndexed { idx, cue ->
                val expectedStartMs = 2_500L + (idx + 1) * 3_000L
                abs(cue.span.startMs - expectedStartMs)
            }.sorted()
            val medianResidual = residuals[residuals.size / 2]
            val p95Residual = residuals[((residuals.size - 1) * 0.95).toInt()]
            assertTrue("Expected median onset residual <= 50 ms, got ${medianResidual}ms", medianResidual <= 50L)
            assertTrue("Expected P95 onset residual <= 90 ms, got ${p95Residual}ms", p95Residual <= 90L)
        } finally {
            server.stop(0)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SuperStream main sources contain zero SubtitleCat upload endpoints legacy cache prefixes or character-ratio splitters`() {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("SuperStream/src/main/kotlin"),
        )
        val mainDir = candidates.firstOrNull { it.isDirectory }
        assertNotNull("Could not locate SuperStream/src/main/kotlin", mainDir)

        val forbiddenTokens = listOf(
            "upload_subtitles.php",
            "publishToSubtitleCatCdn",
            "superstream_urdu_",
            "splitTranslatedUrduProportionally",
            "pendingSubtitleCatUrduFallbackByMedia",
        )

        val ktFiles = mainDir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(ktFiles.isNotEmpty())
        for (file in ktFiles) {
            val content = file.readText()
            for (token in forbiddenTokens) {
                assertFalse("Forbidden legacy token '$token' found in ${file.path}", content.contains(token))
            }
        }
    }

    private fun formatSrtTs(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1_000L
        val rem = ms % 1_000L
        return "%02d:%02d:%02d,%03d".format(h, m, s, rem)
    }
}