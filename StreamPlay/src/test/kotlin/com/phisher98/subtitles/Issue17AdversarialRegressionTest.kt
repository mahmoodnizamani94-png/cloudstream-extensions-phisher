package com.phisher98.subtitles

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.phisher98.SubtitleSourceLedger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class Issue17AdversarialRegressionTest {

    private val mapper = ObjectMapper()

    private fun currentThreadCpuTimeNs(): Long {
        val bean = runCatching { ManagementFactory.getThreadMXBean() }.getOrNull()
        if (bean != null && bean.isCurrentThreadCpuTimeSupported) {
            if (!bean.isThreadCpuTimeEnabled) {
                runCatching { bean.isThreadCpuTimeEnabled = true }
            }
            val cpu = bean.currentThreadCpuTime
            if (cpu > 0L) return cpu
        }
        return System.nanoTime()
    }

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
            val t0 = currentThreadCpuTimeNs()
            val out = AlassAligner.alignToAnchorCues(candidate, anchor, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR)
            assertTrue(out is AlignmentOutcome.Aligned)
            (currentThreadCpuTimeNs() - t0) / 1_000_000.0
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
            this.headers = mapOf(
                "X-StreamPlay-Source" to "wyzie",
                "Authorization" to "Bearer secret-token",
                "Cookie" to "sid=private",
            )
        }
        session.ingestRawSubtitle(rawSub, rawSourceHint = "wyzie", rawBytesOverride = rawBytes)
        session.completeSession()

        assertEquals(1, emitted.size)
        val outHeaders = emitted[0].headers.orEmpty()
        assertNull(outHeaders[ProvenanceCodec.HEADER_SYNC])
        assertNull(outHeaders["Authorization"])
        assertNull(outHeaders["Cookie"])
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
        val solverSamplesMs = DoubleArray(3) {
            val t0 = currentThreadCpuTimeNs()
            val movieAligned = AlassAligner.alignToAnchorCues(movieCand, movieMedia, AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR)
            assertTrue(movieAligned is AlignmentOutcome.Aligned)
            (currentThreadCpuTimeNs() - t0) / 1_000_000.0
        }.sortedArray()
        val solverCpuMs = solverSamplesMs[solverSamplesMs.size / 2]

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
    fun urduCpsRefinerStaysWithin17CpsAndPreservesCompoundVerbsPostpositionsAndClauses() {
        val cpsDoc = listOf(
            CueEntry(CueId(0), CueSpan(1_000L, 2_500L), "تم نے مجھے پہلے سچ کیوں نہیں بتایا تھا؟"),
            CueEntry(CueId(1), CueSpan(2_600L, 4_600L), "کیونکہ وہاں سب لوگ تمہاری ہر ایک حرکت پر مسلسل نظر رکھے ہوئے تھے اور خطرہ بہت زیادہ تھا۔"),
            CueEntry(CueId(2), CueSpan(4_700L, 6_500L), "اب خاموش رہو۔"),
        )
        val refinedCps = UrduCpsRefiner.refineDocument(cpsDoc)
        val maxCps = refinedCps.maxOf { UrduCpsRefiner.computeVisibleCps(it.text, it.span.durationMs) }

        val plotDoc = listOf(
            CueEntry(CueId(20), CueSpan(1_000L, 2_000L), "سنو۔"),
            CueEntry(CueId(21), CueSpan(2_100L, 3_600L), "وہ کمرے میں داخل ہوا اور اس نے خفیہ فائل جلا دی۔"),
            CueEntry(CueId(22), CueSpan(3_700L, 5_000L), "بھاگو۔"),
        )
        val refinedPlot = UrduCpsRefiner.refineDocument(plotDoc)

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
        assertTrue("Second coordinated clause must survive condensation", refinedCps.any { it.text.contains("خطرہ") })
        assertTrue("Plot action after 'اور' must survive condensation", refinedPlot.any { it.text.contains("فائل جلا دی") })
        assertFalse(wrapped[0].text.contains("بڑھتا\nجا"))
        assertFalse(wrapped[1].text.contains("میرے\nساتھ"))
    }

    @Test
    fun bolChalLocalizerRunsConcurrentWindowsAndRejectsUntranslatedLatinEcho() = runBlocking {
        val dummyProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0)
        val inFlight = AtomicInteger(0)
        val maxObservedInFlight = AtomicInteger(0)
        val concurrentTransport = BolChalTranslationTransport { window, _ ->
            val cur = inFlight.incrementAndGet()
            maxObservedInFlight.updateAndGet { prev -> maxOf(prev, cur) }
            try {
                delay(75L)
                val out = window.targetCues.associate { cue ->
                    cue.id to BolChalCueOut(id = cue.id, urduText = "ارے یار، ابھی ہمارے پاس وقت بہت کم ہے۔")
                }
                Result.success(out to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
            } finally {
                inFlight.decrementAndGet()
            }
        }

        val multiWindowCues = (0 until 45).map { idx ->
            CueEntry(
                id = CueId(idx),
                span = CueSpan(1_000L + idx * 3_500L, 2_800L + idx * 3_500L),
                text = "Listen carefully right now line $idx.",
            )
        }
        val multiDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, multiWindowCues)
        val localized = SceneBolChalLocalizer.localize(
            englishDonorDoc = multiDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(maxConcurrentWindows = 3),
            transport = concurrentTransport,
        ).getOrThrow()
        assertEquals(45, localized.document.cues.size)
        assertEquals(
            "Expected bounded concurrency of 3 concurrent windows",
            3,
            maxObservedInFlight.get(),
        )

        val latinEchoTransport = BolChalTranslationTransport { window, _ ->
            val echoed = window.targetCues.associate { cue ->
                cue.id to BolChalCueOut(id = cue.id, urduText = cue.text.text)
            }
            Result.success(echoed to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
        }
        val latinRes = SceneBolChalLocalizer.localize(
            englishDonorDoc = multiDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(),
            transport = latinEchoTransport,
        )
        assertTrue("Untranslated Latin echo must fail Urdu localization gate", latinRes.isFailure)
    }

    @Test
    fun episodeSessionRejectsNonEnglishSubtitleCatOrigDonor() = runBlocking {
        val casDir = Files.createTempDirectory("regression-orig-donor").toFile()
        val frenchSrt = buildString {
            for (i in 1..18) {
                val s = i * 4
                val e = s + 2
                append("$i\n")
                append(String.format(Locale.ROOT, "00:%02d:%02d,000 --> 00:%02d:%02d,500\n", s / 60, s % 60, e / 60, e % 60))
                append("Bonjour mon ami, nous devons partir maintenant avant la nuit sombre.\n\n")
            }
        }
        val transport = CatalogHttpTransport.fromGet { url, _ ->
            val body = when {
                url.contains("index.php") -> """
                    <html><body><table>
                      <tr><td><a href="/subs/888/Dark.2017.S01E01.html">Dark 2017 S01E01</a></td></tr>
                    </table></body></html>
                """.trimIndent()
                url.contains("/subs/888/Dark.2017.S01E01.html") -> """
                    <html><body>
                      <a href="/subs/888/Dark.2017.S01E01-orig.srt">Original Subtitle</a>
                    </body></html>
                """.trimIndent()
                url.endsWith("-orig.srt") -> frenchSrt
                else -> ""
            }
            CatalogHttpResponse(statusCode = 200, bodyBytes = body.toByteArray(Charsets.UTF_8), finalUrl = url)
        }
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Episode(
                imdbId = "tt5753856",
                tmdbId = 70523,
                titles = listOf("Dark"),
                seriesPremiereYear = 2017,
                seasonAirYear = 2017,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(1, 1),
            ),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(casDir),
            httpTransport = transport,
            catalogSources = SubtitleCatalogSources(transport = transport),
            bolChalTransport = BolChalTranslationTransport { window, _ ->
                val out = window.targetCues.associate { cue ->
                    cue.id to BolChalCueOut(id = cue.id, urduText = "ارے یار، ابھی ہمارے پاس وقت بہت کم ہے۔")
                }
                Result.success(out to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
            },
            subtitleCallback = { emitted.add(it) },
        )
        session.discoverAndIngestCatalog(enabledSources = setOf(CatalogueSourceKey.SUBTITLECAT))
        val summary = session.completeSession()
        assertEquals("French -orig.srt must not be localized as an English donor", 0, emitted.size)
        assertTrue(summary.emittedUrduCandidates.isEmpty())
        assertNull(summary.localizedDonorUsed)
    }

    @Test
    fun archiveDecoderEnforcesTotalInflatedBudgetOnSkippedEntriesAndMatchesUnderscoreEpisodes() {
        val spec = MediaLookupSpec.Episode(
            imdbId = "tt0903747",
            tmdbId = 1396,
            titles = listOf("Breaking Bad"),
            seriesPremiereYear = 2008,
            seasonAirYear = 2008,
            coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(1, 1),
        )

        val validUnderscoreZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("Breaking_Bad_S01E02_1080p.srt"))
                zos.write("1\n00:00:01,000 --> 00:00:03,000\nWrong episode\n\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("Breaking_Bad_S01E01_1080p.srt"))
                zos.write("1\n00:00:01,000 --> 00:00:03,000\nCorrect underscore episode\n\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val underscoreDecoded = SubtitleArchiveDecoder.decode(validUnderscoreZip, spec).getOrThrow()
        assertTrue(underscoreDecoded.contains("Correct underscore episode"))

        val threeMiBPayload = ByteArray(3 * 1024 * 1024) { 'A'.code.toByte() }
        val skippedBombZip = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                for (ep in 2..13) {
                    zos.putNextEntry(ZipEntry(String.format(Locale.ROOT, "Breaking_Bad_S01E%02d.srt", ep)))
                    zos.write(threeMiBPayload)
                    zos.closeEntry()
                }
                zos.putNextEntry(ZipEntry("Breaking_Bad_S01E01.srt"))
                zos.write("1\n00:00:01,000 --> 00:00:03,000\nAfter 36MiB skipped entries\n\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }
        val bombResult = SubtitleArchiveDecoder.decode(skippedBombZip, spec)
        assertTrue("Expected ZIP decoder to abort when skipped entries exceed 32 MiB inflated budget", bombResult.isFailure)
    }

    @Test
    fun subSourceApiKeyHeadersScopedStrictlyToTrustedSubSourceHosts() = runBlocking {
        val transport = CatalogHttpTransport.fromGet { url, _ ->
            val json = when {
                url.contains("/movies/search") -> """{"data":[{"movieId":42,"imdbId":"tt1375666","title":"Inception","releaseYear":2010}]}"""
                url.contains("/subtitles") -> """
                    {"data":[
                      {"subtitleId":101,"language":"english","releaseInfo":"Inception.2010.1080p.BluRay","downloadUrl":"https://api.subsource.net/v1/subtitles/download/101"},
                      {"subtitleId":102,"language":"english","releaseInfo":"Inception.2010.720p.BluRay","downloadUrl":"https://evil-collector.example.org/steal.srt"}
                    ]}
                """.trimIndent()
                else -> "{}"
            }
            CatalogHttpResponse(statusCode = 200, bodyBytes = json.toByteArray(Charsets.UTF_8), finalUrl = url)
        }
        val sources = SubtitleCatalogSources(
            transport = transport,
            apiKeys = CatalogApiKeys(subSourceApiKey = "secret-subsource-key"),
        )
        val discovered = sources.discoverCandidates(
            spec = MediaLookupSpec.Movie(
                imdbId = "tt1375666",
                tmdbId = 27205,
                titles = listOf("Inception"),
                releaseYear = 2010,
            ),
            languages = setOf(SubtitleLanguage.ENGLISH),
            enabledSources = setOf(CatalogueSourceKey.SUBSOURCE),
        )
        val trusted = discovered.admittedCandidates.firstOrNull { it.downloadUrl.contains("api.subsource.net") }
        val untrusted = discovered.admittedCandidates.firstOrNull { it.downloadUrl.contains("evil-collector.example.org") }
        assertNotNull(trusted)
        assertNotNull(untrusted)
        assertEquals("secret-subsource-key", trusted!!.requestHeaders["X-API-Key"])
        assertNull("Untrusted redirect/download host must never receive X-API-Key", untrusted!!.requestHeaders["X-API-Key"])
        assertNull("Untrusted redirect/download host must never receive api-key", untrusted.requestHeaders["api-key"])
    }

    @Test
    fun urduOrthographyGateAdmitsValidAssAndTtmlWithFrameRateOffsets() {
        val urduLines = listOf(
            "یار بات سنو، ابھی ہمارے پاس وقت بہت کم ہے۔",
            "تم نے دروازہ ٹھیک سے بند کیا تھا نا؟",
            "میری بات غور سے سنو، اس فائل کا کسی کو پتا نہیں۔",
            "گاڑی کے پاس رکو اور کوئی آئے تو فوراً فون کرنا۔",
            "کل رات تم نے وعدہ کیا تھا کہ اب یہ سب ختم ہے۔",
        )
        val assText = buildString {
            append("[Script Info]\nTitle: Urdu Test\n\n[Events]\n")
            append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            for (i in 0 until 15) {
                val s = 2 + i * 4
                val e = s + 2
                val line = urduLines[i % urduLines.size]
                append(String.format(Locale.ROOT, "Dialogue: 0,0:%02d:%02d.00,0:%02d:%02d.50,Default,,0,0,0,,{\\an8}%s\n", s / 60, s % 60, e / 60, e % 60, line))
            }
        }
        val ttmlText = buildString {
            append("""<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter" ttp:frameRate="24"><body><div>""")
            for (i in 0 until 15) {
                val startFrames = (2 + i * 4) * 24
                val endFrames = startFrames + 48
                val line = urduLines[i % urduLines.size]
                append("""<p begin="${startFrames}f" end="${endFrames}f">$line</p>""")
            }
            append("</div></body></tt>")
        }

        val unitProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.UNANCHORED, 1.0, 0, 0, 0)
        val assAdmitted = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = assText,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL),
            alignment = unitProof,
            lookupSpec = null,
        )
        val ttmlAdmitted = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = ttmlText,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL),
            alignment = unitProof,
            lookupSpec = null,
        )
        assertTrue("Valid Urdu ASS should be admitted: $assAdmitted", assAdmitted is VerifiedSubtitleCandidate.Admitted)
        assertTrue("Valid Urdu TTML should be admitted: $ttmlAdmitted", ttmlAdmitted is VerifiedSubtitleCandidate.Admitted)
        val ttmlDoc = (ttmlAdmitted as VerifiedSubtitleCandidate.Admitted).document
        assertEquals(2_000L, ttmlDoc.cues.first().span.startMs)
        assertEquals(4_000L, ttmlDoc.cues.first().span.endMs)
    }

    @Test
    fun subtitleSourceLedgerClassifiesBolChalCatalogueDonorsAsTranslated() = runBlocking {
        val ledger = SubtitleSourceLedger(targetEnglishSources = 2, targetUrduSources = 2)
        ledger.recordUrduDelivery("bolchal:cat:subtitlecat", "subtitlecat")
        val sub = com.lagradost.cloudstream3.newSubtitleFile("Urdu [Bol-Chal]", "file:///tmp/cs_urdu_v2_test.vtt") {
            this.headers = ProvenanceCodec.encodeHeaders(
                ProvenanceIdentity.BolChalTranslated(
                    donor = DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                    engine = LocalizationTierKey.KEYLESS_BOLCHAL_NMT,
                )
            )
        }
        ledger.recordSubtitleFile(sub)
        assertEquals(2, ledger.urduSourceCount())
        assertEquals("Translated catalogue donors must never count as native/human Urdu", 0, ledger.nativeOrHumanUrduSourceCount())
        assertTrue(ledger.needsUrduCompletion())
    }

    @Test
    fun episodeSessionAppliesSparseAudioVadSpeechTimelineToShiftedHumanUrdu() = runBlocking {
        val casDir = Files.createTempDirectory("regression-vad-session").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Episode(
                imdbId = "tt0903747",
                tmdbId = 1396,
                titles = listOf("Breaking Bad"),
                seriesPremiereYear = 2008,
                seasonAirYear = 2008,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(1, 1),
            ),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(casDir),
            subtitleCallback = { emitted.add(it) },
        )

        val urduLines = listOf(
            "یار بات سنو، ابھی ہمارے پاس وقت بہت کم ہے۔",
            "تم نے دروازہ ٹھیک سے بند کیا تھا نا؟",
            "میری بات غور سے سنو، اس فائل کا کسی کو پتا نہیں۔",
            "گاڑی کے پاس رکو اور کوئی آئے تو فوراً فون کرنا۔",
            "کل رات تم نے وعدہ کیا تھا کہ اب یہ سب ختم ہے۔",
        )
        val speechSpans = ArrayList<SpeechSpan>()
        val shiftedSrt = buildString {
            for (i in 0 until 24) {
                val candStartMs = 8_000L + i * 5_500L
                val candEndMs = candStartMs + 2_200L
                val trueSpeechStartMs = candStartMs + 3_200L
                val trueSpeechEndMs = candEndMs + 3_200L
                speechSpans.add(SpeechSpan(trueSpeechStartMs, trueSpeechEndMs, 0.95f))

                val sh = candStartMs / 3_600_000L
                val sm = (candStartMs % 3_600_000L) / 60_000L
                val ss = (candStartMs % 60_000L) / 1_000L
                val sms = candStartMs % 1_000L
                val eh = candEndMs / 3_600_000L
                val em = (candEndMs % 3_600_000L) / 60_000L
                val es = (candEndMs % 60_000L) / 1_000L
                val ems = candEndMs % 1_000L
                append("${i + 1}\n")
                append(String.format(Locale.ROOT, "%02d:%02d:%02d,%03d --> %02d:%02d:%02d,%03d\n", sh, sm, ss, sms, eh, em, es, ems))
                append(urduLines[i % urduLines.size]).append("\n\n")
            }
        }
        session.attachSpeechTimeline(
            SpeechActivityTimeline(
                speechSpans = speechSpans,
                observedWindows = listOf(ObservedWindow(5_000L, 150_000L)),
                mediaDurationMs = 180_000L,
            )
        )
        val rawSub = com.lagradost.cloudstream3.newSubtitleFile("Urdu", "https://sub.wyzie.ru/c/ur/shifted.srt") {
            this.headers = mapOf("X-StreamPlay-Source" to "wyzie")
        }
        session.ingestRawSubtitle(rawSub, rawSourceHint = "wyzie", rawBytesOverride = shiftedSrt.toByteArray(Charsets.UTF_8))
        session.completeSession()

        assertEquals(1, emitted.size)
        val syncHeader = emitted[0].headers?.get(ProvenanceCodec.HEADER_SYNC)
        assertNotNull("Expected SPARSE_AUDIO_VAD sync header on VAD-aligned human Urdu", syncHeader)
        val proof = ProvenanceCodec.decodeSyncProofOrNull(syncHeader)
        assertNotNull(proof)
        assertEquals(AlignmentAnchorKind.SPARSE_AUDIO_VAD, proof!!.anchorKind)
        val bakedText = File(URI(emitted[0].url)).readText(Charsets.UTF_8)
        val bakedDoc = CueDocument.parse(bakedText, SubtitleLanguage.URDU).getOrThrow()
        assertTrue(
            "Expected first cue shifted from 8000ms to ~11200ms, got ${bakedDoc.cues.first().span.startMs}",
            abs(bakedDoc.cues.first().span.startMs - 11_200L) <= 100L,
        )
    }

    @Test
    fun foreignCompanionSidecarAndMultiProviderSessionsNeverBakeWrongWarpIntoSharedUrdu() = runBlocking {
        val urduLines = listOf(
            "یار بات سنو، ابھی ہمارے پاس وقت بہت کم ہے۔",
            "تم نے دروازہ ٹھیک سے بند کیا تھا نا؟",
            "میری بات غور سے سنو، اس فائل کا کسی کو پتا نہیں۔",
            "گاڑی کے پاس رکو اور کوئی آئے تو فوراً فون کرنا۔",
            "کل رات تم نے وعدہ کیا تھا کہ اب یہ سب ختم ہے۔",
        )
        fun buildTimedSrt(shiftMs: Long, isUrdu: Boolean): ByteArray {
            val sb = StringBuilder()
            for (i in 0 until 20) {
                val startMs = 10_000L + i * 4_500L + shiftMs
                val endMs = startMs + 2_200L
                val sh = startMs / 3_600_000L
                val sm = (startMs % 3_600_000L) / 60_000L
                val ss = (startMs % 60_000L) / 1_000L
                val sms = startMs % 1_000L
                val eh = endMs / 3_600_000L
                val em = (endMs % 3_600_000L) / 60_000L
                val es = (endMs % 60_000L) / 1_000L
                val ems = endMs % 1_000L
                sb.append("${i + 1}\n")
                sb.append(String.format(Locale.ROOT, "%02d:%02d:%02d,%03d --> %02d:%02d:%02d,%03d\n", sh, sm, ss, sms, eh, em, es, ems))
                val text = if (isUrdu) urduLines[i % urduLines.size] else "Anime dialogue line ${i + 1}"
                sb.append(text).append("\n\n")
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        val offlineBolChalTransport = BolChalTranslationTransport { window, _ ->
            val out = window.targetCues.associate { cue ->
                cue.id to BolChalCueOut(cue.id, "ارے یار، ابھی ہمارے پاس وقت بہت کم ہے۔")
            }
            Result.success(out to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
        }

        // Case A: AnimePahe video pack + AniZone companion English sidecar shifted by +45s.
        val casDirAnime = Files.createTempDirectory("regression-foreign-sidecar").toFile()
        val animeEmitted = CopyOnWriteArrayList<SubtitleFile>()
        val animeSession = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Episode(
                imdbId = "tt2560140",
                tmdbId = 1429,
                titles = listOf("Attack on Titan"),
                seriesPremiereYear = 2013,
                seasonAirYear = 2013,
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(1, 1),
                isAnime = true,
            ),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(casDirAnime),
            bolChalTransport = offlineBolChalTransport,
            multiProviderVideoSession = true,
            subtitleCallback = { animeEmitted.add(it) },
        )
        animeSession.registerActiveVideoProvider(StreamProviderKey.ANIMEPAHE)

        val aniZoneCompanionSub = com.lagradost.cloudstream3.newSubtitleFile(
            "English [AniZone]",
            "https://vid-cdn.xyz/subs/aot-ep1-en.srt",
        ) {
            this.headers = mapOf(
                "X-StreamPlay-Source" to "anizone",
                ProvenanceCodec.HEADER_FOREIGN_SIDECAR to "true",
            )
        }
        val admittedCompanion = animeSession.ingestRawSubtitle(
            aniZoneCompanionSub,
            rawSourceHint = "animepahe",
            rawBytesOverride = buildTimedSrt(shiftMs = 45_000L, isUrdu = false),
        )
        assertTrue(admittedCompanion is VerifiedSubtitleCandidate.StreamSyncedNative)
        val companionNative = admittedCompanion as VerifiedSubtitleCandidate.StreamSyncedNative
        assertEquals(StreamProviderKey.ANIZONE, companionNative.provider)
        assertEquals(AlignmentAnchorKind.UNANCHORED, companionNative.alignment.anchorKind)

        val catalogUrduSub = com.lagradost.cloudstream3.newSubtitleFile(
            "Urdu",
            "https://sub.wyzie.ru/c/ur/aot-ep1.srt",
        ) {
            this.headers = mapOf("X-StreamPlay-Source" to "wyzie")
        }
        animeSession.ingestRawSubtitle(
            catalogUrduSub,
            rawSourceHint = "wyzie",
            rawBytesOverride = buildTimedSrt(shiftMs = 0L, isUrdu = true),
        )
        animeSession.completeSession()

        val wyzieEmitted = animeEmitted.firstOrNull { it.lang == "Urdu [Wyzie]" }
        assertNotNull("Expected human Wyzie Urdu track to emit", wyzieEmitted)
        assertNull(
            "Foreign AniZone companion sidecar must never stamp a baked sync proof onto AnimePahe playback",
            wyzieEmitted!!.headers?.get(ProvenanceCodec.HEADER_SYNC),
        )
        val animeUrduDoc = CueDocument.parse(
            File(URI(wyzieEmitted.url)).readText(Charsets.UTF_8),
            SubtitleLanguage.URDU,
        ).getOrThrow()
        assertEquals(
            "Catalogue Urdu must retain unshifted 10_000ms onset instead of +45s foreign AniZone shift",
            10_000L,
            animeUrduDoc.cues.first().span.startMs,
        )
        val fallbackBolChal = animeEmitted.firstOrNull { it.lang.contains("UrduAI") }
        assertNotNull("Foreign AniZone companion sidecar should still remain usable as a fallback Bol-Chal donor", fallbackBolChal)
        assertNull(
            "Fallback Bol-Chal track from foreign AniZone donor must also stay UNANCHORED (no NATIVE_STREAM_PACK header)",
            fallbackBolChal!!.headers?.get(ProvenanceCodec.HEADER_SYNC),
        )

        // Case B: Multi-provider video session (VidLink shifted +12s vs MeowTV at 0s).
        val casDirMulti = Files.createTempDirectory("regression-multi-provider").toFile()
        val multiEmitted = CopyOnWriteArrayList<SubtitleFile>()
        val multiSession = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Movie(
                imdbId = "tt1375666",
                tmdbId = 27205,
                titles = listOf("Inception"),
                releaseYear = 2010,
            ),
            mode = SessionSubtitleMode.BILINGUAL_DOWNLOAD,
            casStore = UrduCasStore(casDirMulti),
            bolChalTransport = offlineBolChalTransport,
            multiProviderVideoSession = true,
            subtitleCallback = { multiEmitted.add(it) },
        )
        multiSession.registerActiveVideoProvider(StreamProviderKey.VIDLINK)
        multiSession.registerActiveVideoProvider(StreamProviderKey.MEOWTV)

        val vidLinkEn = com.lagradost.cloudstream3.newSubtitleFile("English [VidLink]", "https://vodvidl.site/en.vtt") {
            this.headers = mapOf("X-StreamPlay-Source" to "vidlink")
        }
        val meowTvEn = com.lagradost.cloudstream3.newSubtitleFile("English [MeowTV]", "https://rezesubs.com/en.vtt") {
            this.headers = mapOf("X-StreamPlay-Source" to "meowtv")
        }
        multiSession.ingestRawSubtitle(vidLinkEn, rawSourceHint = "vidlink", rawBytesOverride = buildTimedSrt(shiftMs = 12_000L, isUrdu = false))
        multiSession.ingestRawSubtitle(meowTvEn, rawSourceHint = "meowtv", rawBytesOverride = buildTimedSrt(shiftMs = 0L, isUrdu = false))

        val movieUrdu = com.lagradost.cloudstream3.newSubtitleFile("Urdu", "https://dl.subdl.com/sub/inception-ur.srt") {
            this.headers = mapOf("X-StreamPlay-Source" to "subdl")
        }
        multiSession.ingestRawSubtitle(movieUrdu, rawSourceHint = "subdl", rawBytesOverride = buildTimedSrt(shiftMs = 0L, isUrdu = true))
        val report = multiSession.completeSession()

        assertTrue("Expected both active provider anchors to emit", report.emittedProviderAnchors >= 2)
        val emittedUrduTrack = multiEmitted.firstOrNull { it.lang == "Urdu [SubDL]" }
        assertNotNull(emittedUrduTrack)
        assertNull(
            "Multi-provider video session must not bake VidLink's +12s shift globally onto MeowTV",
            emittedUrduTrack!!.headers?.get(ProvenanceCodec.HEADER_SYNC),
        )
        val multiUrduDoc = CueDocument.parse(
            File(URI(emittedUrduTrack.url)).readText(Charsets.UTF_8),
            SubtitleLanguage.URDU,
        ).getOrThrow()
        assertEquals(10_000L, multiUrduDoc.cues.first().span.startMs)
    }

    @Test
    fun hiMarkerRegexAdmitsHiTitlesAndCommentsWhileRejectingExplicitHearingImpairedAndPreservingAcronyms() {
        val hiMomSpec = MediaLookupSpec.Movie(
            imdbId = "tt13364790",
            tmdbId = 760104,
            titles = listOf("Hi, Mom"),
            releaseYear = 2021,
        )
        val hiMomAdmitted = CatalogueCandidateGate.evaluatePreDownload(
            CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.SUBDL,
                language = SubtitleLanguage.URDU,
                titleOrReleaseText = "Hi.Mom.2021.1080p.WEB-DL.x264",
                releaseComment = "Hi everyone, synced cleanly to WEB-DL!",
            ),
            hiMomSpec,
        )
        assertTrue("Expected 'Hi.Mom' release and 'Hi everyone' comment to pass gate: $hiMomAdmitted", hiMomAdmitted.isSuccess)

        val hiLoSpec = MediaLookupSpec.Movie(
            imdbId = "tt0120699",
            tmdbId = 13909,
            titles = listOf("The Hi-Lo Country"),
            releaseYear = 1998,
        )
        val hiLoAdmitted = CatalogueCandidateGate.evaluatePreDownload(
            CatalogueCandidateMeta(
                catalogue = CatalogueSourceKey.OPENSUBTITLES,
                language = SubtitleLanguage.ENGLISH,
                titleOrReleaseText = "The.Hi.Lo.Country.1998.1080p.BluRay",
            ),
            hiLoSpec,
        )
        assertTrue("Expected 'The.Hi.Lo.Country' to pass gate: $hiLoAdmitted", hiLoAdmitted.isSuccess)

        val explicitHiMarkers = listOf(
            "Hi.Mom.2021.1080p.WEB-DL.[HI].srt",
            "Hi.Mom.2021.1080p.(SDH).srt",
            "Hi.Mom.2021.1080p.[CC].srt",
            "Hi.Mom.2021.1080p.SDH.WEB-DL.srt",
            "Hi.Mom.2021.hearing-impaired.srt",
            "Hi.Mom.2021.hi-subtitle.srt",
        )
        for (markerRelease in explicitHiMarkers) {
            val rejected = CatalogueCandidateGate.evaluatePreDownload(
                CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.SUBDL,
                    language = SubtitleLanguage.ENGLISH,
                    titleOrReleaseText = markerRelease,
                ),
                hiMomSpec,
            )
            assertTrue("Expected explicit HI marker '$markerRelease' to be rejected", rejected.isFailure)
        }

        val sanitized = LatinEnglishText.sanitize("The (NASA) probe launched! (DOOR SLAMS) (sighs)")
        assertEquals("The (NASA) probe launched!", sanitized.text)
    }

    @Test
    fun bolChalPurifierGuardsPossessivesSubordinateClausesTuRegisterGenderAndByokSpeakerCard() = runBlocking {
        val tumCard = SpeakerContextCard(dominantSceneRegister = AddressRegister.TUM)
        assertEquals(
            "تمہارے والدین کہاں ہیں؟",
            BolChalPurifier.purify(
                "آپ کے والدین کہاں ہیں؟",
                LatinEnglishText.sanitize("Where are your parents?"),
                tumCard,
            ),
        )
        assertEquals(
            "تمہارے دوست تمہارا انتظار کر رہے ہیں",
            BolChalPurifier.purify(
                "آپ کے دوست آپ کا انتظار کر رہے ہیں",
                LatinEnglishText.sanitize("Your friends are waiting for you"),
                tumCard,
            ),
        )
        assertEquals(
            "تمہیں معلوم ہے کہ وہ لوگ کون ہیں؟",
            BolChalPurifier.purify(
                "آپ کو معلوم ہے کہ وہ لوگ کون ہیں؟",
                LatinEnglishText.sanitize("Do you know who those people are?"),
                tumCard,
            ),
        )
        assertEquals(
            "تم نے کتابیں پڑھی ہیں",
            BolChalPurifier.purify(
                "آپ نے کتابیں پڑھی ہیں",
                LatinEnglishText.sanitize("You have read the books"),
                tumCard,
            ),
        )

        val tuCard = SpeakerContextCard(dominantSceneRegister = AddressRegister.TU)
        assertEquals(
            "تو یہاں کیا کر رہا ہے؟",
            BolChalPurifier.purify(
                "آپ یہاں کیا کر رہے ہیں؟",
                LatinEnglishText.sanitize("You traitor, what are you doing here?"),
                tuCard,
            ),
        )
        assertEquals(
            "تیرا نام کیا ہے؟",
            BolChalPurifier.purify(
                "تمہارا نام کیا ہے؟",
                LatinEnglishText.sanitize("What is your name?"),
                tuCard,
            ),
        )

        val femCard = SpeakerContextCard(
            dominantSceneRegister = AddressRegister.TUM,
            speakerGenderHint = GenderHint.FEMININE,
        )
        assertEquals(
            "میں ابھی گھر جا رہی ہوں۔",
            BolChalPurifier.purify(
                "میں ابھی گھر جا رہا ہوں۔",
                LatinEnglishText.sanitize("I am going home right now."),
                femCard,
            ),
        )

        // Verify BYOK LLM speaker_card JSON forwarding and non-HTTPS SecurityException enforcement.
        var capturedBodyJson = ""
        val byokTransport = DefaultBolChalTranslationTransport(
            httpPostJsonOverride = { _, body, _ ->
                capturedBodyJson = body
                val resp = mapper.createObjectNode()
                val choices = resp.putArray("choices")
                val msg = choices.addObject().putObject("message")
                msg.put("content", """[{"id":0,"ur":"میں ایک ماں ہوں اور ابھی آ رہی ہوں۔"},{"id":1,"ur":"زارا، تم کہاں جا رہی ہو؟"}]""")
                mapper.writeValueAsString(resp)
            }
        )
        val donorDoc = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(
                CueEntry(CueId(0), CueSpan(1_000L, 3_200L), "I'm a mother and I am coming right now."),
                CueEntry(CueId(1), CueSpan(3_600L, 5_800L), "Zara, where are you going?"),
            ),
        )
        val dummyProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0)
        val byokLocalized = SceneBolChalLocalizer.localize(
            englishDonorDoc = donorDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(
                llmEndpointUrl = "https://api.example-llm.org/v1",
                llmModelId = "gpt-4o-mini",
                llmApiKey = "sk-test-secret",
            ),
            transport = byokTransport,
            initialSpeakerCard = SpeakerContextCard(
                characters = mapOf(
                    "Zara" to CharacterRegisterProfile("Zara", "زارا", GenderHint.FEMININE, AddressRegister.TUM)
                ),
                dominantSceneRegister = AddressRegister.TUM,
                speakerGenderHint = GenderHint.FEMININE,
                genreToneHint = "crime-drama",
            ),
        ).getOrThrow()
        assertEquals(LocalizationTierKey.LLM_SCENE, byokLocalized.tier)
        assertTrue(capturedBodyJson.contains("speaker_card"))
        assertTrue(capturedBodyJson.contains("crime-drama"))
        assertTrue(capturedBodyJson.contains("FEMININE"))

        val cleartextAttempt = SceneBolChalLocalizer.localize(
            englishDonorDoc = donorDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(
                llmEndpointUrl = "http://remote-insecure.example.org/v1",
                llmModelId = "gpt-4o-mini",
                llmApiKey = "sk-test-secret",
            ),
            transport = byokTransport,
        )
        assertTrue(cleartextAttempt.exceptionOrNull() is SecurityException)

        // Keyless NMT regex-patched register cues must be tracked and discounted in qualityReport.
        val stiffNmtTransport = DefaultBolChalTranslationTransport(
            httpGetOverride = { _, params, _ ->
                val q = params["q"].orEmpty()
                val out = q.lines().joinToString("\n") { line ->
                    val marker = line.substringBefore(' ')
                    "$marker آپ یہاں کیا کر رہے ہیں؟"
                }
                nmtJson(out, q)
            }
        )
        val keylessPatched = SceneBolChalLocalizer.localize(
            englishDonorDoc = donorDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(),
            transport = stiffNmtTransport,
        ).getOrThrow()
        assertTrue(keylessPatched.qualityReport.regexPatchedRegisterCues > 0)
        assertTrue(keylessPatched.qualityReport.registerVerifiedCueRatio < 1.0)
    }

    @Test
    fun singleCueLeafFailureAndWindowIsolationSurviveUnder98PercentCoverageFloor() = runBlocking {
        val dummyProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0)
        val sixtyCues = (0 until 60).map { idx ->
            CueEntry(
                id = CueId(idx),
                span = CueSpan(1_000L + idx * 3_200L, 2_700L + idx * 3_200L),
                text = if (idx == 17) "POISON_SINGLE_LEAF_CUE" else "Peer dialogue line number $idx.",
            )
        }
        val donorDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, sixtyCues)

        val flakyLeafTransport = DefaultBolChalTranslationTransport(
            httpGetOverride = { _, params, _ ->
                val q = params["q"].orEmpty()
                if (q.contains("POISON_SINGLE_LEAF_CUE")) {
                    if (q.lines().size == 1) {
                        throw RuntimeException("Simulated transient HTTP 502 on single-cue leaf")
                    }
                    // Force bisection on the window containing cue 17 by omitting its marker.
                    val partial = q.lines()
                        .filterNot { it.contains("POISON_SINGLE_LEAF_CUE") }
                        .joinToString("\n") { line ->
                            val marker = line.substringBefore(' ')
                            "$marker ارے یار، جلدی چلو ابھی وقت کم ہے۔"
                        }
                    nmtJson(partial, q)
                } else {
                    val clean = q.lines().joinToString("\n") { line ->
                        val marker = line.substringBefore(' ')
                        "$marker ارے یار، جلدی چلو ابھی وقت کم ہے۔"
                    }
                    nmtJson(clean, q)
                }
            }
        )

        val localized = SceneBolChalLocalizer.localize(
            englishDonorDoc = donorDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = dummyProof,
            config = BolChalConfig(minRequiredCueCoverage = 0.98, maxConcurrentWindows = 4),
            transport = flakyLeafTransport,
        ).getOrThrow()

        assertTrue(
            "Expected >= 59/60 cues localized (got ${localized.document.cues.size}) with >= 0.98 survival",
            localized.document.cues.size >= 59 && localized.qualityReport.cueSurvivalRatio >= 0.98,
        )
    }

    @Test
    fun sparseAudioVadOn900CueFeatureFilmRetainsObservedWindowCuesWithoutSubsamplingDrop() {
        val rng = Random(1717L)
        val movieCues = ArrayList<CueEntry>(900)
        var cursorMs = 8_000L
        for (i in 0 until 900) {
            val dur = 1_400L + rng.nextInt(1_400)
            movieCues.add(CueEntry(CueId(i), CueSpan(cursorMs, cursorMs + dur), "Feature film line $i"))
            cursorMs += dur + 4_500L + rng.nextInt(3_500)
        }
        val candDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, movieCues)
        val constantShiftMs = 18_500L
        val trueSpans = candDoc.cues.map { c ->
            CueSpan(c.span.startMs + constantShiftMs, c.span.endMs + constantShiftMs)
        }
        val obsWindows = listOf(
            ObservedWindow(20_000L, 48_000L),
            ObservedWindow(900_000L, 928_000L),
            ObservedWindow(2_400_000L, 2_428_000L),
            ObservedWindow(4_200_000L, 4_228_000L),
            ObservedWindow(5_800_000L, 5_828_000L),
        )
        val speechSpans = trueSpans.filter { span ->
            obsWindows.any { w -> span.endMs > w.startMs && span.startMs < w.endMs }
        }.map { span -> SpeechSpan(span.startMs, span.endMs, 0.96f) }

        val timeline = SpeechActivityTimeline(
            speechSpans = speechSpans,
            observedWindows = obsWindows,
            mediaDurationMs = cursorMs + 60_000L,
        )
        val aligned = AlassAligner.alignToSparseSpeech(candDoc, timeline)
        assertTrue("Expected 900-cue sparse VAD alignment to succeed, got $aligned", aligned is AlignmentOutcome.Aligned)
        val proof = (aligned as AlignmentOutcome.Aligned).proof
        val residuals = candDoc.cues.indices.map { idx ->
            abs(proof.warp.forwardMs(candDoc.cues[idx].span.startMs) - trueSpans[idx].startMs)
        }.sorted()
        val medianMs = residuals[residuals.size / 2]
        val p95Ms = residuals[(residuals.size * 95) / 100]
        assertTrue(
            "Expected 900-cue sparse VAD median <= 50ms and P95 <= 90ms, got median=${medianMs}ms, P95=${p95Ms}ms",
            medianMs <= 50L && p95Ms <= 90L,
        )
    }

    @Test
    fun defaultEnglishModeMaterializesZipArchivesToCasAndVerifiesSha256ReadbackOnHit() = runBlocking {
        val casDir = Files.createTempDirectory("regression-default-en-cas").toFile()
        val casStore = UrduCasStore(casDir)
        val enSrt = buildString {
            for (i in 1..16) {
                val s = i * 4
                val e = s + 2
                append("$i\n")
                append(String.format(Locale.ROOT, "00:%02d:%02d,000 --> 00:%02d:%02d,500\n", s / 60, s % 60, e / 60, e % 60))
                append("Clear English dialogue line number $i for testing.\n\n")
            }
        }
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry("Inception.2010.1080p.BluRay.en.srt"))
                zos.write(enSrt.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val transport = CatalogHttpTransport.fromGet { url, _ ->
            CatalogHttpResponse(statusCode = 200, bodyBytes = zipBytes, finalUrl = url)
        }
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Movie(
                imdbId = "tt1375666",
                tmdbId = 27205,
                titles = listOf("Inception"),
                releaseYear = 2010,
            ),
            mode = SessionSubtitleMode.DEFAULT,
            casStore = casStore,
            httpTransport = transport,
            catalogSources = SubtitleCatalogSources(transport = transport),
            subtitleCallback = { emitted.add(it) },
        )
        session.ingestCatalogCandidate(
            CatalogDiscoveredCandidate(
                meta = CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.YIFY,
                    language = SubtitleLanguage.ENGLISH,
                    titleOrReleaseText = "Inception.2010.1080p.BluRay",
                ),
                downloadUrl = "https://yifysubtitles.ch/subtitle/inception-2010-english-yify-123.zip",
                preDownloadScore = 120,
            )
        )
        session.completeSession()

        assertEquals(1, emitted.size)
        assertTrue(
            "DEFAULT English mode must materialize .zip catalogue tracks to local file:// CAS, got ${emitted[0].url}",
            emitted[0].url.startsWith("file:"),
        )
        val materializedFile = File(URI(emitted[0].url))
        assertTrue(materializedFile.isFile)
        val materializedText = materializedFile.readText(Charsets.UTF_8)
        assertTrue(materializedText.startsWith("WEBVTT"))
        assertTrue(materializedText.contains("Clear English dialogue line number 1"))

        // Corrupt the cached CAS file on disk and verify writeAlignedSubtitle detects the SHA-256 mismatch and heals it.
        val doc = CueDocument.parse(enSrt, SubtitleLanguage.ENGLISH).getOrThrow()
        val stored1 = casStore.writeAlignedSubtitle(doc = doc)
        stored1.file.writeText("CORRUPTED_PARTIAL_DISK_CONTENT", Charsets.UTF_8)
        val stored2 = casStore.writeAlignedSubtitle(doc = doc)
        assertEquals(
            stored2.contentSha256.hex,
            ContentSha256.sha256Hex(stored2.file.readBytes()),
        )
        assertTrue(stored2.file.readText(Charsets.UTF_8).contains("Clear English dialogue line number 1"))
    }
}
