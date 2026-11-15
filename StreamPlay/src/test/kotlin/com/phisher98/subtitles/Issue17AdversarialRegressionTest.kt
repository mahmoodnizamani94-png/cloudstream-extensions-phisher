package com.phisher98.subtitles

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

class Issue17AdversarialRegressionTest {

    private val mapper = ObjectMapper()

    private fun nmtJson(translatedPayload: String, src: String): String {
        val outer = mapper.createArrayNode()
        val sentenceArr = outer.addArray()
        val seg = sentenceArr.addArray()
        seg.add(translatedPayload)
        seg.add(src)
        return mapper.writeValueAsString(outer)
    }

    private fun buildEpisodeFixture(cueCount: Int): Pair<CueDocument, CueDocument> {
        val anchorCues = ArrayList<CueEntry>(cueCount)
        val candCues = ArrayList<CueEntry>(cueCount)
        var cursorMs = 30_000L
        var state = 0x5DEECE66DL
        val act1Split = cueCount / 3
        val act2Split = (cueCount * 2) / 3
        for (i in 0 until cueCount) {
            state = (state * 25214903917L + 11L) and ((1L shl 48) - 1L)
            val u = ((state ushr 16).toInt() and 0x7FFF).toDouble() / 32768.0
            val gapMs = if (i > 0 && i % 10 == 0) (3_500L + (u * 2_000.0).toLong()) else (700L + (u * 1_100.0).toLong())
            if (i == act1Split || i == act2Split) {
                cursorMs += 90_000L
            } else if (i > 0) {
                cursorMs += gapMs
            }
            val durMs = 1_400L + ((i * 313L) % 1_800L)
            val aStart = cursorMs
            val aEnd = aStart + durMs
            anchorCues.add(CueEntry(CueId(i + 1), CueSpan(aStart, aEnd), "Dialogue line ${i + 1}"))

            val actOffsetMs = when {
                i < act1Split -> 1_200L
                i < act2Split -> 91_200L
                else -> 181_200L
            }
            val cStart = (aStart - actOffsetMs).coerceAtLeast(0L)
            val cEnd = cStart + durMs
            candCues.add(CueEntry(CueId(i + 1), CueSpan(cStart, cEnd), "مکالمہ نمبر ${i + 1}"))
        }
        return CueDocument.fromCues(SubtitleLanguage.ENGLISH, anchorCues) to
            CueDocument.fromCues(SubtitleLanguage.URDU, candCues)
    }

    @Test
    fun alassSolverStaysUnder50msCpuBudgetOn900CueEpisode() {
        val (anchor, candidate) = buildEpisodeFixture(cueCount = 900)
        repeat(3) {
            AlassAligner.alignToAnchorCues(candidate, anchor, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR)
        }
        val samplesMs = DoubleArray(5) {
            val t0 = System.nanoTime()
            val out = AlassAligner.alignToAnchorCues(candidate, anchor, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR)
            assertTrue(out is AlignmentOutcome.Aligned)
            (System.nanoTime() - t0) / 1_000_000.0
        }.sortedArray()
        val medianCpuMs = samplesMs[samplesMs.size / 2]
        val minCpuMs = samplesMs.first()
        println(
            String.format(
                Locale.ROOT,
                "REGRESSION_EXT_CPU | cues=900 | min_cpu_ms=%.2f | median_cpu_ms=%.2f | budget_ms=50.00",
                minCpuMs,
                medianCpuMs,
            )
        )
        assertTrue(
            "Expected AlassAligner solver CPU < 50ms on 900-cue episode, got median=${"%.2f".format(Locale.ROOT, medianCpuMs)}ms (min=${"%.2f".format(Locale.ROOT, minCpuMs)}ms)",
            medianCpuMs < 50.0,
        )
    }

    @Test
    fun crossRepoHostSyncProofTimeWarpTimelineAndProviderWireParity() {
        val hostLowerHeader = "matched_provider_sidecar|0.9400|12|28|0|aff:25/23.976:900"
        val hostUpperHeader = "MATCHED_PROVIDER_SIDECAR|0.9400|12|28|0|aff:25/23.976:900"
        val legacyThreeField = "MATCHED_PROVIDER_SIDECAR|0.940|affine:1:1:-850"

        val decodedLower = ProvenanceCodec.decodeSyncProofOrNull(hostLowerHeader)
        val decodedUpper = ProvenanceCodec.decodeSyncProofOrNull(hostUpperHeader)
        val decodedLegacy = ProvenanceCodec.decodeSyncProofOrNull(legacyThreeField)

        assertNotNull(decodedLower)
        assertNotNull(decodedUpper)
        assertNotNull(decodedLegacy)
        assertEquals(AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR, decodedLower!!.anchorKind)
        assertEquals(0.94, decodedLower.syncConfidence, 1e-6)
        assertEquals(12, decodedLower.medianOnsetResidualMs)
        assertEquals(28, decodedLower.p95OnsetResidualMs)
        assertEquals(0, decodedLower.splitCount)
        assertEquals("aff:25/23.976:900", decodedLower.warp.serialize())
        assertEquals("aff:25/23.976:900", decodedUpper!!.warp.serialize())
        assertEquals(hostLowerHeader, ProvenanceCodec.encodeSyncProof(decodedLower))
        assertEquals("aff:1:1:-850", decodedLegacy!!.warp.serialize())

        assertEquals("id", SubtitleTimeWarp.Identity.serialize())
        assertEquals(SubtitleTimeWarp.Identity, SubtitleTimeWarp.parse("id").getOrThrow())
        assertEquals(SubtitleTimeWarp.Identity, SubtitleTimeWarp.parse("identity").getOrThrow())
        assertEquals(
            SubtitleTimeWarp.Affine(FpsRatio.UNITY, 450L),
            SubtitleTimeWarp.parse("aff:1:1:450").getOrThrow(),
        )
        assertEquals(
            SubtitleTimeWarp.Affine(FpsRatio.PAL_25_TO_NTSC_23976, 900L),
            SubtitleTimeWarp.parse("affine:25/23.976:900").getOrThrow(),
        )

        val hostSpeechBytes = byteArrayOf(
            1, 1,
            0xC0.toByte(), 0xA9.toByte(), 0x07,
            1, 0, 0xB0.toByte(), 0xEA.toByte(), 0x01,
            2,
            0xE8.toByte(), 0x07, 0xDC.toByte(), 0x0B, 0xFF.toByte(),
            0xA0.toByte(), 0x1F, 0xD0.toByte(), 0x0F, 0xFF.toByte(),
        )
        val hostStandardB64 = Base64.getEncoder().encodeToString(hostSpeechBytes)
        val hostUrlSafeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hostSpeechBytes)
        val extDecodedStd = SpeechTimelineCodec.decodeCompact(hostStandardB64).getOrThrow()
        val extDecodedUrl = SpeechTimelineCodec.decodeCompact(hostUrlSafeB64).getOrThrow()

        val extTimeline = SpeechActivityTimeline(
            speechSpans = listOf(SpeechSpan(1_000L, 2_500L, 1.0f), SpeechSpan(5_000L, 7_000L, 1.0f)),
            observedWindows = listOf(ObservedWindow(0L, 30_000L)),
            mediaDurationMs = 120_000L,
        )
        val extEncodedBlob = SpeechTimelineCodec.encodeCompact(extTimeline)

        assertEquals(120_000L, extDecodedStd.mediaDurationMs)
        assertEquals(listOf(ObservedWindow(0L, 30_000L)), extDecodedStd.observedWindows)
        assertEquals(2, extDecodedStd.speechSpans.size)
        assertEquals(1_000L, extDecodedStd.speechSpans[0].startMs)
        assertEquals(2_500L, extDecodedStd.speechSpans[0].endMs)
        assertEquals(1.0f, extDecodedStd.speechSpans[0].confidenceWeight, 1e-4f)
        assertEquals(5_000L, extDecodedStd.speechSpans[1].startMs)
        assertEquals(7_000L, extDecodedStd.speechSpans[1].endMs)
        assertEquals(extDecodedStd, extDecodedUrl)
        assertEquals(hostStandardB64, extEncodedBlob)

        val fractionalTimeline = SpeechActivityTimeline(
            speechSpans = listOf(SpeechSpan(1_000L, 2_500L, 0.95f), SpeechSpan(5_000L, 7_000L, 0.50f)),
            observedWindows = listOf(ObservedWindow(0L, 30_000L)),
            mediaDurationMs = 120_000L,
        )
        val fractionalDecoded = SpeechTimelineCodec.decodeCompact(
            SpeechTimelineCodec.encodeCompact(fractionalTimeline)
        ).getOrThrow()
        assertEquals(0.95f, fractionalDecoded.speechSpans[0].confidenceWeight, 1f / 255f)
        assertEquals(0.50f, fractionalDecoded.speechSpans[1].confidenceWeight, 1f / 255f)

        assertNull(ProvenanceCodec.decodeSyncProofOrNull("SPARSE_AUDIO_VAD|0.875|bogus:warp:spec"))
        assertNull(ProvenanceCodec.decodeSyncProofOrNull("UNANCHORED|0.500|id"))

        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromTextOrHost("febbox"))
        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromTextOrHost("superstream"))
        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromTextOrHost("showbox"))
        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromTextOrHost("https://cdn.hakunaymatata.com/sub/en.vtt"))
        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromWireId("superstream"))
        assertEquals(StreamProviderKey.FEBBOX, StreamProviderKey.fromWireId("febbox"))
    }

    @Test
    fun episodeSessionOmitsSyncHeaderOnUnanchoredHumanUrdu() = runBlocking {
        val casDir = Files.createTempDirectory("regression-unanchored-cas").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Movie(
                imdbId = "tt9999999",
                tmdbId = 999999,
                titles = listOf("Test Movie"),
                releaseYear = 2024,
                expectedDurationMs = 5_400_000L,
            ),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(casDir),
            subtitleCallback = { emitted.add(it) },
        )

        val sb = StringBuilder()
        val lines = listOf(
            "یار بات سنو، ابھی ہمارے پاس وقت بہت کم ہے۔",
            "تم نے دروازہ ٹھیک سے بند کیا تھا نا؟",
            "میری بات غور سے سنو، اس فائل کا کسی کو پتا نہیں۔",
            "گاڑی کے پاس رکو اور کوئی آئے تو فوراً فون کرنا۔",
            "کل رات تم نے وعدہ کیا تھا کہ اب یہ سب ختم ہے۔",
        )
        for (i in 0 until 20) {
            val s = 10 + i * 4
            val e = s + 2
            sb.append("${i + 1}\n")
            sb.append(String.format(Locale.ROOT, "00:%02d:%02d,000 --> 00:%02d:%02d,500\n", s / 60, s % 60, e / 60, e % 60))
            sb.append(lines[i % lines.size]).append("\n\n")
        }
        val rawBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val rawSub = com.lagradost.cloudstream3.newSubtitleFile("Urdu", "https://sub.wyzie.ru/c/ur/test.srt") {
            this.headers = mapOf("X-StreamPlay-Source" to "wyzie")
        }
        session.ingestRawSubtitle(rawSub, rawSourceHint = "wyzie", rawBytesOverride = rawBytes)
        session.completeSession()

        assertEquals(1, emitted.size)
        val outHeaders = emitted[0].headers.orEmpty()
        assertNull(outHeaders[ProvenanceCodec.HEADER_SYNC])
    }

    @Test
    fun coldOpenPlusOpJumpSparseVadAndMovieSolverCpuMeetPSync1() {
        val animeRng = Random(7L)
        val animeCues = ArrayList<CueEntry>(140)
        var aMs = 3_000L
        for (i in 0 until 140) {
            val dur = 1_400L + animeRng.nextInt(1_400)
            animeCues.add(CueEntry(CueId(i), CueSpan(aMs, aMs + dur), "Anime $i"))
            aMs += dur + 4_200L + animeRng.nextInt(4_400)
        }
        val animeCand = CueDocument.fromCues(SubtitleLanguage.ENGLISH, animeCues)
        val animeTrueCues = animeCand.cues.map { c ->
            val shift = if (c.span.startMs < 45_000L) 0L else 85_000L
            c.copy(span = CueSpan(c.span.startMs + shift, c.span.endMs + shift))
        }
        val obsWins = listOf(
            ObservedWindow(5_000L, 30_000L),
            ObservedWindow(150_000L, 175_000L),
            ObservedWindow(450_000L, 475_000L),
            ObservedWindow(800_000L, 825_000L),
            ObservedWindow(1_150_000L, 1_175_000L),
        )
        val speechSpans = animeTrueCues.filter { c ->
            obsWins.any { w -> c.span.endMs > w.startMs && c.span.startMs < w.endMs }
        }.map { c -> SpeechSpan(c.span.startMs, c.span.endMs, 0.95f) }
        val sparseTimeline = SpeechActivityTimeline(
            speechSpans = speechSpans,
            observedWindows = obsWins,
            mediaDurationMs = 1_450_000L,
        )
        val coldOpenVadOut = AlassAligner.alignToSparseSpeech(animeCand, sparseTimeline) as AlignmentOutcome.Aligned
        val errs = animeCand.cues.indices.map { i ->
            abs(coldOpenVadOut.proof.warp.forwardMs(animeCand.cues[i].span.startMs) - animeTrueCues[i].span.startMs)
        }.sorted()
        val medianMs = errs[errs.size / 2]
        val p95Ms = errs[(errs.size * 95) / 100]

        val rng = Random(42L)
        val movieCues = ArrayList<CueEntry>(900)
        var mMs = 5_000L
        for (i in 0 until 900) {
            val dur = 1_500L + rng.nextInt(1_500)
            movieCues.add(CueEntry(CueId(i), CueSpan(mMs, mMs + dur), "Movie $i"))
            mMs += dur + 3_500L + rng.nextInt(3_500)
        }
        val movieCand = CueDocument.fromCues(SubtitleLanguage.ENGLISH, movieCues)
        val movieMedia = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            movieCand.cues.map { c ->
                val shift = when {
                    c.span.startMs < 1_800_000L -> 0L
                    c.span.startMs < 3_600_000L -> 45_000L
                    else -> 92_000L
                }
                c.copy(span = CueSpan(c.span.startMs + shift, c.span.endMs + shift))
            }
        )
        repeat(2) { AlassAligner.alignToAnchorCues(movieCand, movieMedia, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR) }
        val t0 = System.nanoTime()
        val movieAligned = AlassAligner.alignToAnchorCues(movieCand, movieMedia, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR)
        val solverCpuMs = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue(movieAligned is AlignmentOutcome.Aligned)

        println(
            String.format(
                Locale.ROOT,
                "REGRESSION_PSYNC1 | coldOpenVadSplits=%d | medianMs=%d | p95Ms=%d | movie900CpuMs=%.2f",
                coldOpenVadOut.proof.splitCount,
                medianMs,
                p95Ms,
                solverCpuMs,
            )
        )
        assertTrue(
            "Expected splits >= 1, median <= 50ms, P95 <= 90ms, solverCpu < 50ms; got splits=${coldOpenVadOut.proof.splitCount}, median=${medianMs}ms, P95=${p95Ms}ms, cpu=${solverCpuMs}ms",
            coldOpenVadOut.proof.splitCount >= 1 && medianMs <= 50L && p95Ms <= 90L && solverCpuMs < 50.0,
        )
    }

    @Test
    fun subtitleCatScraperRejectsBannedUploaderAndHearingImpairedUrdu() = runBlocking {
        val transport = CatalogHttpTransport.fromGet { url, _ ->
            val html = when {
                url.contains("index.php") -> """
                    <html><body><table>
                      <tr><td><a href="/subs/777/Inception.2010.1080p.BluRay.html">Inception 2010 1080p BluRay</a></td></tr>
                    </table></body></html>
                """.trimIndent()
                url.contains("/subs/777/") -> """
                    <html><body>
                      <span class="sub-author">blackgold</span>
                      <span class="hi-badge" title="Hearing Impaired">SDH / Hearing Impaired</span>
                      <a id="download_ur" href="/subs/777/Inception.2010.1080p.BluRay.urdu.srt">Download Urdu</a>
                    </body></html>
                """.trimIndent()
                else -> ""
            }
            CatalogHttpResponse(statusCode = 200, bodyBytes = html.toByteArray(Charsets.UTF_8), finalUrl = url)
        }
        val sources = SubtitleCatalogSources(transport = transport)
        val spec = MediaLookupSpec.Movie(
            imdbId = "tt1375666",
            tmdbId = 27205,
            titles = listOf("Inception"),
            releaseYear = 2010,
            releaseHints = listOf("Inception.2010.1080p.BluRay"),
        )
        val discovered = sources.discoverCandidates(
            spec = spec,
            languages = setOf(SubtitleLanguage.URDU),
            enabledSources = setOf(CatalogueSourceKey.SUBTITLECAT),
        )
        assertTrue(discovered.admittedCandidates.isEmpty())
        assertTrue(discovered.rejectedPreDownload.isNotEmpty())
    }

    @Test
    fun keylessBolChalLocksSceneSpeakerRegisterAcrossPeerDialogue() = runBlocking {
        val dummyProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0)
        val registerTransport = DefaultBolChalTranslationTransport(
            httpGetOverride = { _, params, _ ->
                val q = params["q"].orEmpty()
                val payload = q.lines().joinToString("\n") { line ->
                    val marker = line.substringBefore(' ')
                    when {
                        line.contains("listen to me") -> "$marker ارے بھائی، تم میری بات سنو!"
                        line.contains("doing here") -> "$marker آپ یہاں کیا کر رہے ہیں؟"
                        line.contains("Give me the key") -> "$marker تم ابھی مجھے چابی دو!"
                        line.contains("Where are you going") -> "$marker آپ کہاں جا رہے ہیں؟"
                        line.contains("Wait for me") -> "$marker یار، تم میرا انتظار کرو!"
                        line.contains("hiding the truth") -> "$marker آپ سچ کیوں چھپا رہے ہیں؟"
                        else -> "$marker یہ مکالمہ واضح ہے۔"
                    }
                }
                nmtJson(payload, q)
            }
        )
        val peerEnglishDoc = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(
                CueEntry(CueId(0), CueSpan(1_000L, 2_800L), "Hey bro, listen to me!"),
                CueEntry(CueId(1), CueSpan(3_200L, 5_000L), "What are you doing here?"),
                CueEntry(CueId(2), CueSpan(5_400L, 7_200L), "Give me the key right now!"),
                CueEntry(CueId(3), CueSpan(7_600L, 9_400L), "Where are you going?"),
                CueEntry(CueId(4), CueSpan(9_800L, 11_600L), "Wait for me, yaar!"),
                CueEntry(CueId(5), CueSpan(12_000L, 13_800L), "Why are you hiding the truth?"),
            )
        )
        val regResult = SceneBolChalLocalizer.localize(
            englishDonorDoc = peerEnglishDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(),
            transport = registerTransport,
        ).getOrThrow()
        val regAudit = UrduMtDumpDetector.audit(regResult.document)
        assertEquals(0.0, regAudit.pronounWhiplashRate, 1e-6)
        assertFalse(MtSignalFamily.PRONOUN_REGISTER_WHIPLASH in regAudit.triggeredFamilies)
    }

    @Test
    fun rtlTrailingBracketIdTriggersBisectInsteadOfShiftingTwistReveal() = runBlocking {
        val dummyProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0)
        var nmtCalls = 0
        val rtlShiftTransport = DefaultBolChalTranslationTransport(
            httpGetOverride = { _, params, _ ->
                nmtCalls++
                val q = params["q"].orEmpty()
                if (q.lines().size == 1) {
                    if (q.contains("⟦0⟧")) nmtJson("⟦0⟧ باہر انتظار کرو۔", q)
                    else nmtJson("⟦1⟧ تمہارا بھائی ہی قاتل ہے۔", q)
                } else {
                    nmtJson("باہر انتظار کرو ⟦0⟧\nتمہارا بھائی ہی قاتل ہے ⟦1⟧۔", q)
                }
            }
        )
        val rtlDoc = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(
                CueEntry(CueId(0), CueSpan(1_000L, 3_000L), "Wait outside."),
                CueEntry(CueId(1), CueSpan(4_000L, 6_500L), "Your brother is the killer."),
            )
        )
        val rtlResult = SceneBolChalLocalizer.localize(
            englishDonorDoc = rtlDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(),
            transport = rtlShiftTransport,
        ).getOrThrow()
        assertTrue(nmtCalls >= 3)
        assertEquals("باہر انتظار کرو۔", rtlResult.document.cues[0].text)
        assertEquals("تمہارا بھائی ہی قاتل ہے۔", rtlResult.document.cues[1].text)
    }

    @Test
    fun urduCpsRefinerStaysWithin17CpsAndPreservesCompoundVerbsAndPostpositions() {
        val cpsDoc = listOf(
            CueEntry(CueId(0), CueSpan(1_000L, 2_500L), "تم نے مجھے پہلے سچ کیوں نہیں بتایا تھا؟"),
            CueEntry(CueId(1), CueSpan(2_600L, 4_600L), "کیونکہ وہاں سب لوگ تمہاری ہر ایک حرکت پر مسلسل نظر رکھے ہوئے تھے اور خطرہ بہت زیادہ تھا۔"),
            CueEntry(CueId(2), CueSpan(4_700L, 6_500L), "اب خاموش رہو۔"),
        )
        val refinedCps = UrduCpsRefiner.refineDocument(cpsDoc)
        val maxCps = refinedCps.maxOf { UrduCpsRefiner.computeVisibleCps(it.text, it.span.durationMs) }

        val wrapInputs = listOf(
            CueEntry(CueId(10), CueSpan(10_000L, 14_000L), "اب یہاں خطرہ مسلسل بڑھتا جا رہا ہے ہمارے سب دوستوں کے لیے"),
            CueEntry(CueId(11), CueSpan(15_000L, 19_000L), "اس مشکل وقت میں ہمیشہ میرے ساتھ کھڑے رہنا میرے پیارے دوست"),
        )
        val wrapped = UrduCpsRefiner.refineDocument(wrapInputs)
        println(
            String.format(
                Locale.ROOT,
                "REGRESSION_CPS | maxCps=%.2f | wrap0=%s | wrap1=%s",
                maxCps,
                wrapped[0].text.replace("\n", " | "),
                wrapped[1].text.replace("\n", " | "),
            )
        )
        assertTrue("Expected maxCps <= 17.0, got $maxCps", maxCps <= 17.0)
        assertFalse(wrapped[0].text.contains("بڑھتا\nجا"))
        assertFalse(wrapped[1].text.contains("میرے\nساتھ"))
    }
}
