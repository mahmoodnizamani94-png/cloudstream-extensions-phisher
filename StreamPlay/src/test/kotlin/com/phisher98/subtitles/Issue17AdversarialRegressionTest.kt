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
}
