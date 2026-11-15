package com.phisher98.subtitles

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

class UrduSyncAndBolChalBenchmarkTest {

    private data class GroundTruthFixture(
        val anchorDoc: CueDocument,
        val trueStartsMs: LongArray,
        val trueEndsMs: LongArray,
    )

    private data class SyncBenchmarkMetrics(
        val label: String,
        val medianAbsErrorMs: Long,
        val p95AbsErrorMs: Long,
        val cpuMillis: Double,
        val proof: AlignmentProof,
    )

    private fun srtTime(msTotal: Long): String {
        val clamped = msTotal.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val mins = (clamped % 3_600_000L) / 60_000L
        val secs = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, mins, secs, millis)
    }

    private fun buildRealistic120CueFixture(
        cueCount: Int = 120,
        initialStartMs: Long = 92_000L,
        targetSpanMs: Long = 6_920_000L,
    ): GroundTruthFixture {
        val rawWeights = DoubleArray(cueCount)
        var weightSum = 0.0
        var state = 0x5DEECE66DL
        for (i in 0 until cueCount) {
            state = (state * 25214903917L + 11L) and ((1L shl 48) - 1L)
            val u = ((state ushr 16).toInt() and 0x7FFF).toDouble() / 32768.0
            val scenePause = if (i > 0 && i % 9 == 0) 3.4 else 0.45
            val w = scenePause + u * 1.35
            rawWeights[i] = w
            weightSum += w
        }

        val cues = ArrayList<CueEntry>(cueCount)
        val starts = LongArray(cueCount)
        val ends = LongArray(cueCount)
        var cursorMs = initialStartMs

        val sampleLines = listOf(
            "We don't have much time left before the train leaves.",
            "Did you check the upstairs hallway before locking up?",
            "Listen to me carefully, nobody else knows about the file.",
            "Wait here by the car and call me if anyone shows up.",
            "You promised me last night that we were done with this.",
            "Hand over the keys right now and step away from the desk.",
        )

        val usableGapBudgetMs = (targetSpanMs - cueCount * 2_200L).coerceAtLeast(240_000L)
        for (i in 0 until cueCount) {
            if (i > 0) {
                val gapMs = ((rawWeights[i] / weightSum) * usableGapBudgetMs)
                    .toLong()
                    .coerceAtLeast(620L)
                cursorMs += gapMs
            }
            val durMs = 1_250L + ((i * 379L + (i * i * 53L)) % 2_150L)
            val startMs = cursorMs
            val endMs = startMs + durMs
            starts[i] = startMs
            ends[i] = endMs
            cues.add(
                CueEntry(
                    id = CueId(i + 1),
                    span = CueSpan(startMs, endMs),
                    text = sampleLines[i % sampleLines.size],
                )
            )
            cursorMs = endMs
        }

        return GroundTruthFixture(
            anchorDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, cues),
            trueStartsMs = starts,
            trueEndsMs = ends,
        )
    }

    private fun buildShiftedCandidate(
        fixture: GroundTruthFixture,
        inverseTransformStartEndUs: (startUs: Long, endUs: Long, index: Int) -> Pair<Long, Long>,
    ): CueDocument {
        val out = ArrayList<CueEntry>(fixture.anchorDoc.cues.size)
        for (i in fixture.anchorDoc.cues.indices) {
            val jitterMs = ((i % 5) - 2) * 6L
            val trueStartUs = (fixture.trueStartsMs[i] + jitterMs) * 1_000L
            val trueEndUs = (fixture.trueEndsMs[i] + jitterMs) * 1_000L
            val (candStartUs, candEndUs) = inverseTransformStartEndUs(trueStartUs, trueEndUs, i)
            val sMs = (candStartUs / 1_000L).coerceAtLeast(0L)
            val eMs = (candEndUs / 1_000L).coerceAtLeast(sMs + 250L)
            out.add(
                CueEntry(
                    id = CueId(i + 1),
                    span = CueSpan(sMs, eMs),
                    text = "مکالمہ نمبر ${i + 1}، ذرا دھیان سے سنو۔",
                )
            )
        }
        return CueDocument.fromCues(SubtitleLanguage.URDU, out)
    }

    private fun evaluateCueToCueRegime(
        label: String,
        candidate: CueDocument,
        fixture: GroundTruthFixture,
    ): SyncBenchmarkMetrics {
        val t0 = System.nanoTime()
        val outcome = AlassAligner.alignToAnchorCues(
            candidate = candidate,
            anchor = fixture.anchorDoc,
            anchorKind = AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR,
        )
        val cpuMs = (System.nanoTime() - t0) / 1_000_000.0
        assertTrue("$label failed to align: $outcome", outcome is AlignmentOutcome.Aligned)
        val proof = (outcome as AlignmentOutcome.Aligned).proof

        val absErrors = LongArray(candidate.cues.size) { idx ->
            val warpedStartMs = proof.warp.forwardMs(candidate.cues[idx].span.startMs)
            abs(warpedStartMs - fixture.trueStartsMs[idx])
        }
        absErrors.sort()
        val medianMs = absErrors[absErrors.size / 2]
        val p95Idx = ((absErrors.size - 1) * 0.95).toInt().coerceIn(0, absErrors.size - 1)
        val p95Ms = absErrors[p95Idx]

        assertTrue("$label median ${medianMs}ms exceeded 50ms", medianMs <= 50L)
        assertTrue("$label P95 ${p95Ms}ms exceeded 90ms", p95Ms <= 90L)
        assertTrue("$label proof median ${proof.medianOnsetResidualMs}ms exceeded 50ms", proof.medianOnsetResidualMs <= 50)
        assertTrue("$label proof P95 ${proof.p95OnsetResidualMs}ms exceeded 90ms", proof.p95OnsetResidualMs <= 90)
        assertTrue("$label solver CPU ${"%.2f".format(Locale.ROOT, cpuMs)}ms exceeded 50ms", cpuMs < 50.0)

        return SyncBenchmarkMetrics(
            label = label,
            medianAbsErrorMs = medianMs,
            p95AbsErrorMs = p95Ms,
            cpuMillis = cpuMs,
            proof = proof,
        )
    }

    @Test
    fun pSync1_allFourCueVsCueRegimesAndSparseVadMeetLatencyAndResidualSlo() {
        val fixture = buildRealistic120CueFixture()
        assertEquals(120, fixture.anchorDoc.cues.size)

        val warmupCand = buildShiftedCandidate(fixture) { sUs, eUs, _ -> (sUs - 200_000L) to (eUs - 200_000L) }
        val warmupSplitCand = buildShiftedCandidate(fixture) { sUs, eUs, idx ->
            val shiftUs = if (idx < 60) 0L else 8_000_000L
            (sUs - shiftUs) to (eUs - shiftUs)
        }
        repeat(6) {
            AlassAligner.alignToAnchorCues(warmupCand, fixture.anchorDoc)
            AlassAligner.alignToAnchorCues(warmupSplitCand, fixture.anchorDoc)
        }

        val metrics = ArrayList<SyncBenchmarkMetrics>()

        val lipPlus350 = buildShiftedCandidate(fixture) { sUs, eUs, _ -> (sUs - 350_000L) to (eUs - 350_000L) }
        metrics += evaluateCueToCueRegime("lip_sync_+350ms", lipPlus350, fixture)

        val lipMinus900 = buildShiftedCandidate(fixture) { sUs, eUs, _ -> (sUs + 900_000L) to (eUs + 900_000L) }
        metrics += evaluateCueToCueRegime("lip_sync_-900ms", lipMinus900, fixture)

        val opPlus65s = buildShiftedCandidate(fixture) { sUs, eUs, _ -> (sUs - 65_000_000L) to (eUs - 65_000_000L) }
        metrics += evaluateCueToCueRegime("cold_open_+65000ms", opPlus65s, fixture)

        val opMinus89_9s = buildShiftedCandidate(fixture) { sUs, eUs, _ -> (sUs + 89_900_000L) to (eUs + 89_900_000L) }
        metrics += evaluateCueToCueRegime("anime_op_-89900ms", opMinus89_9s, fixture)

        val pal25To23976 = buildShiftedCandidate(fixture) { sUs, eUs, _ ->
            val baseSubStart = FpsRatio.PAL_25_TO_NTSC_23976.inverseScaleUs(sUs - 420_000L)
            val baseSubEnd = FpsRatio.PAL_25_TO_NTSC_23976.inverseScaleUs(eUs - 420_000L)
            baseSubStart to baseSubEnd
        }
        val rawPalEndDriftMs = abs(fixture.trueStartsMs.last() - pal25To23976.cues.last().span.startMs)
        assertTrue("Expected ~295s PAL drift over 2h, got ${rawPalEndDriftMs}ms", rawPalEndDriftMs >= 285_000L)
        val palMetrics = evaluateCueToCueRegime("fps_25_to_23976", pal25To23976, fixture)
        assertTrue(
            "Expected PAL_25_TO_NTSC_23976 affine warp, got ${palMetrics.proof.warp.serialize()}",
            palMetrics.proof.warp.serialize().contains(FpsRatio.PAL_25_TO_NTSC_23976.id)
        )
        metrics += palMetrics

        val film24To23976 = buildShiftedCandidate(fixture) { sUs, eUs, _ ->
            val baseSubStart = FpsRatio.FILM_24_TO_NTSC_23976.inverseScaleUs(sUs + 250_000L)
            val baseSubEnd = FpsRatio.FILM_24_TO_NTSC_23976.inverseScaleUs(eUs + 250_000L)
            baseSubStart to baseSubEnd
        }
        val filmMetrics = evaluateCueToCueRegime("fps_24_to_23976", film24To23976, fixture)
        assertTrue(
            "Expected FILM_24_TO_NTSC_23976 warp, got ${filmMetrics.proof.warp.serialize()}",
            filmMetrics.proof.warp.serialize().contains(FpsRatio.FILM_24_TO_NTSC_23976.id)
        )
        metrics += filmMetrics

        val baseWebCut = buildRealistic120CueFixture(
            cueCount = 120,
            initialStartMs = 30_000L,
            targetSpanMs = 3_200_000L,
        )
        val actShiftsMs = longArrayOf(0L, 9_200L, 19_400L)
        val broadcastCues = baseWebCut.anchorDoc.cues.mapIndexed { idx, cue ->
            val shiftMs = actShiftsMs[(idx / 40).coerceIn(0, 2)]
            cue.copy(span = CueSpan(cue.span.startMs + shiftMs, cue.span.endMs + shiftMs))
        }
        val multiActFixture = GroundTruthFixture(
            anchorDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, broadcastCues),
            trueStartsMs = LongArray(120) { broadcastCues[it].span.startMs },
            trueEndsMs = LongArray(120) { broadcastCues[it].span.endMs },
        )
        val multiActCandidate = buildShiftedCandidate(baseWebCut) { sUs, eUs, _ -> sUs to eUs }
        val multiActMetrics = evaluateCueToCueRegime("commercial_3_acts", multiActCandidate, multiActFixture)
        assertTrue(
            "Expected piecewise linear warp for 3 commercial-break acts, got ${multiActMetrics.proof.warp}",
            multiActMetrics.proof.warp is SubtitleTimeWarp.PiecewiseLinear
        )
        assertTrue("Expected >= 2 split segments, got ${multiActMetrics.proof.splitCount}", multiActMetrics.proof.splitCount >= 2)
        metrics += multiActMetrics

        val observedWindows = (0 until 5).map { w ->
            val winStart = w * 925_000L
            ObservedWindow(winStart, winStart + 25_000L)
        }
        for (w in 0 until 4) {
            assertEquals(900_000L, observedWindows[w + 1].startMs - observedWindows[w].endMs)
        }

        val vadTrueCues = ArrayList<CueEntry>(120)
        val vadSpeechSpans = ArrayList<SpeechSpan>(30)
        var nextCueId = 1
        for ((wIdx, win) in observedWindows.withIndex()) {
            val offsetsInWin = longArrayOf(900L, 4_300L, 8_600L, 12_400L, 16_900L, 20_800L)
            val dursInWin = longArrayOf(2_100L, 2_650L, 1_950L, 2_800L, 2_250L, 2_400L)
            for (k in offsetsInWin.indices) {
                val cs = win.startMs + offsetsInWin[k] + (wIdx * 30L)
                val ce = cs + dursInWin[k]
                vadTrueCues.add(CueEntry(CueId(nextCueId++), CueSpan(cs, ce), "Observed window dialogue $nextCueId"))
                vadSpeechSpans.add(SpeechSpan(startMs = cs + 35L, endMs = ce + 25L, confidenceWeight = 1.0f))
            }
            if (wIdx < 4) {
                val gapStart = win.endMs + 28_000L
                for (g in 0 until 22) {
                    val gs = gapStart + g * 37_500L + ((g % 3) * 1_100L)
                    val ge = gs + 1_800L + ((g % 4) * 350L)
                    vadTrueCues.add(CueEntry(CueId(nextCueId++), CueSpan(gs, ge), "Unobserved gap dialogue $nextCueId"))
                }
            } else {
                val tailStart = win.endMs + 15_000L
                for (g in 0 until 2) {
                    val gs = tailStart + g * 20_000L
                    vadTrueCues.add(CueEntry(CueId(nextCueId++), CueSpan(gs, gs + 2_000L), "Tail dialogue $nextCueId"))
                }
            }
        }
        vadTrueCues.sortBy { it.span.startMs }
        assertEquals(120, vadTrueCues.size)

        val vadGroundTruth = GroundTruthFixture(
            anchorDoc = CueDocument.fromCues(SubtitleLanguage.ENGLISH, vadTrueCues),
            trueStartsMs = LongArray(120) { vadTrueCues[it].span.startMs },
            trueEndsMs = LongArray(120) { vadTrueCues[it].span.endMs },
        )
        val vadCandidate = buildShiftedCandidate(vadGroundTruth) { sUs, eUs, _ ->
            (sUs - 1_350_000L) to (eUs - 1_350_000L)
        }
        val rawTimeline = SpeechActivityTimeline(
            speechSpans = vadSpeechSpans,
            observedWindows = observedWindows,
            mediaDurationMs = 3_800_000L,
        )
        val encodedTimeline = SpeechTimelineCodec.encodeCompact(rawTimeline)
        val decodedTimeline = SpeechTimelineCodec.decodeCompact(encodedTimeline).getOrThrow()
        assertEquals(5, decodedTimeline.observedWindows.size)

        AlassAligner.alignToSparseSpeech(vadCandidate, decodedTimeline)
        val vadT0 = System.nanoTime()
        val vadOutcome = AlassAligner.alignToSparseSpeech(vadCandidate, decodedTimeline)
        val vadCpuMs = (System.nanoTime() - vadT0) / 1_000_000.0
        assertTrue("Sparse VAD alignment failed: $vadOutcome", vadOutcome is AlignmentOutcome.Aligned)
        val vadProof = (vadOutcome as AlignmentOutcome.Aligned).proof
        assertEquals("Expected zero false splits across unobserved 15-min gaps", 0, vadProof.splitCount)

        val vadErrors = LongArray(120) { idx ->
            abs(vadProof.warp.forwardMs(vadCandidate.cues[idx].span.startMs) - vadGroundTruth.trueStartsMs[idx])
        }.also { it.sort() }
        val vadMedianMs = vadErrors[60]
        val vadP95Ms = vadErrors[(119 * 0.95).toInt()]
        assertTrue("Sparse VAD median ${vadMedianMs}ms exceeded 50ms", vadMedianMs <= 50L)
        assertTrue("Sparse VAD P95 ${vadP95Ms}ms exceeded 90ms", vadP95Ms <= 90L)
        assertTrue("Sparse VAD CPU ${vadCpuMs}ms exceeded 50ms", vadCpuMs < 50.0)

        metrics += SyncBenchmarkMetrics("sparse_vad_5x25s", vadMedianMs, vadP95Ms, vadCpuMs, vadProof)

        val summary = metrics.joinToString(" | ") { m ->
            "${m.label}:med=${m.medianAbsErrorMs}ms,p95=${m.p95AbsErrorMs}ms,cpu=${"%.2f".format(Locale.ROOT, m.cpuMillis)}ms,splits=${m.proof.splitCount}"
        }
        println("BENCHMARK_P_SYNC_1 | $summary")
    }

    @Test
    fun pSync2_donorPriorityNativeStreamProvenanceAndAnchorForwarding() = runBlocking {
        val tempCasDir = Files.createTempDirectory("cs_urdu_cas_test_sync2").toFile()
        try {
            val casStore = UrduCasStore(rootDir = tempCasDir, maxActiveSlots = 32)
            val spec = MediaLookupSpec.Episode(
                imdbId = "tt0903747",
                tmdbId = 1396,
                titles = listOf("Breaking Bad"),
                seriesPremiereYear = 2008,
                seasonAirYear = 2010,
                expectedDurationMs = 2_820_000L,
                releaseHints = listOf("WEB-DL"),
                coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season = 3, episode = 7),
                isAnime = false,
            )

            val enSrt = buildString {
                for (i in 1..12) {
                    val s = i * 4_000L
                    val e = s + 2_200L
                    append(i).append('\n')
                    append(srtTime(s)).append(" --> ").append(srtTime(e)).append('\n')
                    append("Stay close to the door and listen for my phone call.\n\n")
                }
            }
            val enBytes = enSrt.toByteArray(StandardCharsets.UTF_8)

            val emittedInUrduVaultMode = Collections.synchronizedList(ArrayList<SubtitleFile>())
            val prevVaultProp = System.getProperty(SubtitleModeBridge.PROP_VAULT_V2)
            try {
                System.setProperty(SubtitleModeBridge.PROP_VAULT_V2, "true")
                val deterministicBolChal = BolChalTranslationTransport { window, _ ->
                    val map = window.targetCues.associate { cue ->
                        cue.id to BolChalCueOut(cue.id, "دروازے کے پاس رہو اور میری فون کال کا انتظار کرو۔")
                    }
                    Result.success(map to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                }

                val session = EpisodeSubtitleSession(
                    lookupSpec = spec,
                    mode = SessionSubtitleMode.URDU,
                    casStore = casStore,
                    bolChalTransport = deterministicBolChal,
                    subtitleCallback = { emittedInUrduVaultMode.add(it) },
                )

                session.ingestCatalogCandidate(
                    discovered = CatalogDiscoveredCandidate(
                        meta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.SUBTITLECAT,
                            language = SubtitleLanguage.ENGLISH,
                            titleOrReleaseText = "Breaking.Bad.S03E07-orig.srt",
                        ),
                        downloadUrl = "https://www.subtitlecat.com/subs/120/Breaking.Bad.S03E07-orig.srt",
                        preDownloadScore = 99,
                        isSubtitleCatOrigDonor = true,
                    ),
                    rawBytesOverride = enBytes,
                )
                session.ingestCatalogCandidate(
                    discovered = CatalogDiscoveredCandidate(
                        meta = CatalogueCandidateMeta(
                            catalogue = CatalogueSourceKey.WYZIE,
                            language = SubtitleLanguage.ENGLISH,
                            titleOrReleaseText = "Breaking.Bad.S03E07.WEB-DL",
                        ),
                        downloadUrl = "https://sub.wyzie.io/c/en/1",
                        preDownloadScore = 145,
                    ),
                    rawBytesOverride = enBytes,
                )
                session.ingestRawSubtitle(
                    sub = newSubtitleFile("English", "https://rezesubs.hscow.com/subs/bb_s03e07_en.vtt"),
                    rawSourceHint = "MeowTV",
                    rawBytesOverride = enBytes,
                )
                session.ingestRawSubtitle(
                    sub = newSubtitleFile("English", "https://vodvidl.site/subs/bb_s03e07_en.vtt"),
                    rawSourceHint = "VidLink",
                    rawBytesOverride = enBytes,
                )

                val ranked = session.rankedEnglishDonorsSnapshot()
                assertEquals(4, ranked.size)
                assertEquals(DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK), ranked[0].donor)
                assertEquals(DonorProvenance.StreamDonor(StreamProviderKey.MEOWTV), ranked[1].donor)
                assertEquals(DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE), ranked[2].donor)
                assertEquals(DonorProvenance.CatalogueDonor(CatalogueSourceKey.SUBTITLECAT), ranked[3].donor)

                val report = session.completeSession(forceBolChalLocalization = true)
                assertEquals(DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK), report.localizedDonorUsed)
                assertTrue("Expected >= 2 provider anchors forwarded under vault_v2=true", report.emittedProviderAnchors >= 2)

                val vidlinkAnchor = emittedInUrduVaultMode.firstOrNull {
                    it.url.contains("vodvidl.site") &&
                        it.headers?.get(ProvenanceCodec.HEADER_SYNC_ROLE) == ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR
                }
                assertNotNull("Expected VidLink English anchor with X-StreamPlay-Sync-Role=provider-anchor", vidlinkAnchor)

                val localizedSub = emittedInUrduVaultMode.firstOrNull { it.lang == "Urdu [UrduAI VidLink]" }
                assertNotNull("Expected honest Urdu [UrduAI VidLink] badge", localizedSub)
                assertEquals(ProvenanceCodec.SOURCE_URDU_TRANSLATED, localizedSub!!.headers?.get(ProvenanceCodec.HEADER_SOURCE))
                assertEquals(ProvenanceCodec.ORIGIN_BOLCHAL, localizedSub.headers?.get(ProvenanceCodec.HEADER_ORIGIN))
                assertEquals("stream:vidlink", localizedSub.headers?.get(ProvenanceCodec.HEADER_DONOR))
                assertEquals(LocalizationTierKey.KEYLESS_BOLCHAL_NMT.wireId, localizedSub.headers?.get(ProvenanceCodec.HEADER_ENGINE))
            } finally {
                if (prevVaultProp == null) {
                    System.clearProperty(SubtitleModeBridge.PROP_VAULT_V2)
                } else {
                    System.setProperty(SubtitleModeBridge.PROP_VAULT_V2, prevVaultProp)
                }
            }

            val bilingualEmitted = Collections.synchronizedList(ArrayList<SubtitleFile>())
            val bilingualToken = object {
                override fun toString(): String = "BilingualDownloadSubtitleCallback(${SubtitleModeBridge.BILINGUAL_CALLBACK_TOKEN})"
            }
            val resolvedMode = SubtitleModeBridge.resolveSessionMode(subtitleCallback = bilingualToken)
            assertEquals(SessionSubtitleMode.BILINGUAL_DOWNLOAD, resolvedMode)

            val nativeUrduSrt = buildString {
                for (i in 1..12) {
                    val s = i * 4_000L
                    val e = s + 2_200L
                    append(i).append('\n')
                    append(srtTime(s)).append(" --> ").append(srtTime(e)).append('\n')
                    append("دروازے کے پاس کھڑے رہو اور ذرا میری بات غور سے سنو یار۔\n\n")
                }
            }
            val shiftedCatUrduSrt = buildString {
                for (i in 1..12) {
                    val s = i * 4_000L - 1_250L
                    val e = s + 2_200L
                    append(i).append('\n')
                    append(srtTime(s)).append(" --> ").append(srtTime(e)).append('\n')
                    append("تم یہیں رکو، میں ابھی اندر جا کر ساری صورتحال دیکھتا ہوں نا۔\n\n")
                }
            }

            val bilingualSession = EpisodeSubtitleSession(
                lookupSpec = spec,
                mode = resolvedMode,
                casStore = casStore,
                targetUrduDiversityCount = 3,
                callbackIdentityToken = bilingualToken,
                subtitleCallback = { bilingualEmitted.add(it) },
            )

            val nativeCand = bilingualSession.ingestRawSubtitle(
                sub = newSubtitleFile("Urdu", "https://vodvidl.site/subs/bb_s03e07_ur.vtt"),
                rawSourceHint = "VidLink",
                rawBytesOverride = nativeUrduSrt.toByteArray(StandardCharsets.UTF_8),
            )
            assertTrue("Expected StreamSyncedNative for VidLink Urdu", nativeCand is VerifiedSubtitleCandidate.StreamSyncedNative)
            assertEquals(
                ProvenanceIdentity.StreamProvider(StreamProviderKey.VIDLINK),
                (nativeCand as VerifiedSubtitleCandidate.StreamSyncedNative).provenance,
            )

            bilingualSession.ingestRawSubtitle(
                sub = newSubtitleFile("English", "https://vodvidl.site/subs/bb_s03e07_en.vtt"),
                rawSourceHint = "VidLink",
                rawBytesOverride = enBytes,
            )

            val catBytes = shiftedCatUrduSrt.toByteArray(StandardCharsets.UTF_8)
            val wyzieAligned = bilingualSession.ingestCatalogCandidate(
                discovered = CatalogDiscoveredCandidate(
                    meta = CatalogueCandidateMeta(
                        catalogue = CatalogueSourceKey.WYZIE,
                        language = SubtitleLanguage.URDU,
                        titleOrReleaseText = "Breaking.Bad.S03E07.WEB-DL",
                        uploaderName = "humantranslator",
                    ),
                    downloadUrl = "https://sub.wyzie.io/c/ur/99",
                    preDownloadScore = 140,
                ),
                rawBytesOverride = catBytes,
            )
            assertTrue("Expected AlignedHumanCatalogue", wyzieAligned is VerifiedSubtitleCandidate.AlignedHumanCatalogue)
            val wyzieProof = (wyzieAligned as VerifiedSubtitleCandidate.AlignedHumanCatalogue).alignment
            assertTrue("Catalogue Urdu must align < 20ms against VidLink anchor, got ${wyzieProof.medianOnsetResidualMs}ms", wyzieProof.medianOnsetResidualMs < 20)

            bilingualSession.ingestCatalogCandidate(
                discovered = CatalogDiscoveredCandidate(
                    meta = CatalogueCandidateMeta(
                        catalogue = CatalogueSourceKey.SUBDL,
                        language = SubtitleLanguage.URDU,
                        titleOrReleaseText = "Breaking.Bad.S03E07.WEB-DL",
                        uploaderName = "humantranslator",
                    ),
                    downloadUrl = "https://dl.subdl.com/subtitle/333-444.zip",
                    preDownloadScore = 138,
                ),
                rawBytesOverride = catBytes,
            )

            val bilingualReport = bilingualSession.completeSession()
            val humanCatEmitted = bilingualReport.emittedUrduCandidates.count { it is VerifiedSubtitleCandidate.AlignedHumanCatalogue }
            assertEquals("Byte-identical Urdu across Wyzie and SubDL must count as 1 human catalogue source", 1, humanCatEmitted)
            assertTrue(
                "Bilingual callback must receive VidLink provider-anchor header",
                bilingualEmitted.any { it.headers?.get(ProvenanceCodec.HEADER_SYNC_ROLE) == ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR },
            )
        } finally {
            tempCasDir.deleteRecursively()
        }
    }

    @Test
    fun pSync2_unverifiedAnimeCourAndPremiereYearAndConcurrentSessionIsolation() = runBlocking {
        val requestedUrls = Collections.synchronizedList(ArrayList<String>())
        val fakeTransport = CatalogHttpTransport.fromGet { url, _ ->
            requestedUrls.add(url)
            val body = when {
                url.contains("subtitlecat.com/index.php?search=") -> """
                    <html><body>
                    <table class="sub-table"><tbody>
                    <tr><td><a href="/subs/901/Bleach.Thousand.Year.Blood.War.The.Conflict.E04.1080p.html">Bleach: Thousand-Year Blood War - The Conflict - 04 (1080p CR)</a></td></tr>
                    </tbody></table>
                    </body></html>
                """.trimIndent()
                url.contains("subtitlecat.com/subs/901/") -> """
                    <html><body>
                    <div class="sub-single"><span>Urdu</span><a href="/subs/901/Bleach.The.Conflict.E04.human_urdu.srt">Download</a></div>
                    <div class="sub-single"><span>Urdu</span><a href="/subs/901/Bleach.The.Conflict.E04-ur.srt">Download</a></div>
                    <div class="sub-single"><span>Original</span><a href="/subs/901/Bleach.The.Conflict.E04-orig.srt">Download</a></div>
                    </body></html>
                """.trimIndent()
                url.contains("api3.subdl.com/auto") -> """
                    {"results":[
                      {"name":"The Boys","type":"tv","year":2019,"link":"/subtitle/sd999/the-boys"},
                      {"name":"The Boys Remake","type":"tv","year":2024,"link":"/subtitle/sd000/the-boys-remake"}
                    ]}
                """.trimIndent()
                url.contains("subdl.com/subtitle/sd999/the-boys/third-season") -> """
                    <html><head><script id="__NEXT_DATA__" type="application/json">
                    {"props":{"pageProps":{"groupedSubtitles":{"ur":[
                      {"id":501,"language":"urdu","title":"The.Boys.S03E05.1080p.AMZN.WEB-DL","link":"/subtitle/501-99.zip","author":"paksubber","hi":false}
                    ]}}}}
                    </script></head><body></body></html>
                """.trimIndent()
                url.contains("subdl.com/subtitle/sd999/the-boys") -> """
                    <html><body><a href="/subtitle/sd999/the-boys/third-season">Third Season</a></body></html>
                """.trimIndent()
                else -> throw IllegalStateException("Unexpected URL in fake transport: $url")
            }
            CatalogHttpResponse(statusCode = 200, bodyBytes = body.toByteArray(StandardCharsets.UTF_8), finalUrl = url)
        }

        val catalog = SubtitleCatalogSources(
            transport = fakeTransport,
            apiKeys = CatalogApiKeys(wyzieApiKey = "test-wyzie", subDlApiKey = "test-subdl", subSourceApiKey = "test-subsource"),
        )

        val animeSpec = MediaLookupSpec.Episode(
            imdbId = "tt0409591",
            tmdbId = 30984,
            titles = listOf("Bleach"),
            seriesPremiereYear = 2004,
            seasonAirYear = 2024,
            coordinate = EpisodeCoordinate.UnverifiedAnimeCour(
                courTitles = listOf("Bleach: Thousand-Year Blood War - The Conflict"),
                episodeInCour = 4,
            ),
            isAnime = true,
        )

        val animeDiscovery = catalog.discoverCandidates(animeSpec)
        val seasonIndexedLeak = requestedUrls.filter { url ->
            url.contains("wyzie", true) ||
                url.contains("strem.io", true) ||
                url.contains("opensubtitles", true) ||
                url.contains("subdl", true) ||
                url.contains("subsource", true) ||
                url.contains("addic7ed", true) ||
                url.contains("subsense", true) ||
                url.contains("season=1", true) ||
                url.contains(":1:", true)
        }
        assertEquals("UnverifiedAnimeCour must issue 0 season-indexed or season=1 requests", emptyList<String>(), seasonIndexedLeak)
        assertTrue("SubtitleCat must be queried with cour title + episode 04", requestedUrls.any { it.contains("The+Conflict+04") || it.contains("The%20Conflict%2004") })
        assertEquals(1, animeDiscovery.candidatesFor(SubtitleLanguage.URDU).size)
        assertTrue(
            "SubtitleCat -ur.srt MT suffix must be rejected pre-download while human .srt survives",
            animeDiscovery.candidatesFor(SubtitleLanguage.URDU).single().downloadUrl.endsWith("human_urdu.srt")
        )
        assertTrue(
            "SubtitleCat -orig.srt must be recorded as last-resort English donor",
            animeDiscovery.candidatesFor(SubtitleLanguage.ENGLISH).any { it.isSubtitleCatOrigDonor }
        )

        requestedUrls.clear()
        val keylessCatalog = SubtitleCatalogSources(
            transport = fakeTransport,
            apiKeys = CatalogApiKeys(wyzieApiKey = null, subDlApiKey = null, subSourceApiKey = null),
        )
        val season3Spec = MediaLookupSpec.Episode(
            imdbId = "tt1190634",
            tmdbId = 76479,
            titles = listOf("The Boys"),
            seriesPremiereYear = 2019,
            seasonAirYear = 2022,
            coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(season = 3, episode = 5),
        )
        val s3Discovery = keylessCatalog.discoverCandidates(
            spec = season3Spec,
            languages = setOf(SubtitleLanguage.URDU),
            enabledSources = setOf(CatalogueSourceKey.SUBDL),
        )
        assertEquals("Season 3 lookup must match 2019 premiere-year SubDL slug", 1, s3Discovery.candidatesFor(SubtitleLanguage.URDU).size)

        val decodedWyzieAi = ProvenanceCodec.decode(
            headers = emptyMap(),
            displayName = "UrduAI Wyzie",
            urlOrFileName = "file:///tmp/cs_urdu_v2_abcdef0123456789abcdef01.vtt",
        )
        assertEquals(
            ProvenanceIdentity.BolChalTranslated(
                donor = DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                engine = LocalizationTierKey.KEYLESS_BOLCHAL_NMT,
            ),
            decodedWyzieAi,
        )
        val reEncodedHeaders = ProvenanceCodec.encodeHeaders(decodedWyzieAi)
        assertEquals(ProvenanceCodec.SOURCE_URDU_TRANSLATED, reEncodedHeaders[ProvenanceCodec.HEADER_SOURCE])

        val sharedCasDir = Files.createTempDirectory("cs_urdu_cas_isolation").toFile()
        try {
            val casStore = UrduCasStore(rootDir = sharedCasDir, maxActiveSlots = 4, maxPrewarmMetadataSlots = 8)
            val showASpec = MediaLookupSpec.Movie(
                imdbId = "tt1375666",
                tmdbId = 27205,
                titles = listOf("Inception"),
                releaseYear = 2010,
            )
            val showBSpec = MediaLookupSpec.Movie(
                imdbId = "tt0816692",
                tmdbId = 157336,
                titles = listOf("Interstellar"),
                releaseYear = 2014,
            )
            val showAUrdu = """
                1
                00:00:02,000 --> 00:00:04,500
                خواب کے اندر وقت بہت تیزی سے گزرتا ہے نا یار۔
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            val showBUrdu = """
                1
                00:00:05,000 --> 00:00:08,000
                ہمیں اس سیارے سے ابھی نکلنا پڑے گا ورنہ دیر ہو جائے گی۔
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            val outA = Collections.synchronizedList(ArrayList<SubtitleFile>())
            val outB = Collections.synchronizedList(ArrayList<SubtitleFile>())

            coroutineScope {
                val jobA = async(Dispatchers.Default) {
                    val sessionA = EpisodeSubtitleSession(
                        lookupSpec = showASpec,
                        mode = SessionSubtitleMode.URDU,
                        casStore = casStore,
                        subtitleCallback = { outA.add(it) },
                    )
                    sessionA.ingestRawSubtitle(
                        sub = newSubtitleFile("Urdu", "https://vodvidl.site/inception_ur.vtt"),
                        rawSourceHint = "VidLink",
                        rawBytesOverride = showAUrdu,
                    )
                    sessionA.completeSession()
                }
                val jobB = async(Dispatchers.Default) {
                    val sessionB = EpisodeSubtitleSession(
                        lookupSpec = showBSpec,
                        mode = SessionSubtitleMode.URDU,
                        casStore = casStore,
                        subtitleCallback = { outB.add(it) },
                    )
                    sessionB.ingestRawSubtitle(
                        sub = newSubtitleFile("Urdu", "https://rezesubs.hscow.com/interstellar_ur.vtt"),
                        rawSourceHint = "MeowTV",
                        rawBytesOverride = showBUrdu,
                    )
                    sessionB.completeSession()
                }
                awaitAll(jobA, jobB)
            }

            assertEquals(1, outA.size)
            assertEquals(1, outB.size)
            assertEquals("Urdu [VidLink]", outA.single().lang)
            assertEquals("Urdu [MeowTV]", outB.single().lang)
            assertTrue(outA.single().url != outB.single().url)

            val activeFilesBeforePrewarm = casStore.listActiveSubtitleFiles()
            assertEquals(2, activeFilesBeforePrewarm.size)
            for (file in activeFilesBeforePrewarm) {
                assertTrue(file.name.startsWith("cs_urdu_v2_"))
                assertFalse("CAS filename must never contain superstream: ${file.name}", file.name.contains("superstream", true))
            }

            val noopCatalog = SubtitleCatalogSources(
                transport = CatalogHttpTransport.fromGet { url, _ ->
                    CatalogHttpResponse(200, "{}".toByteArray(StandardCharsets.UTF_8), url)
                },
                apiKeys = CatalogApiKeys(null, null, null),
            )
            for (idx in 1..40) {
                casStore.prewarmMetadataAsync(
                    spec = MediaLookupSpec.Movie("tt9999$idx", idx, listOf("Prewarm Movie $idx"), 2024),
                    catalogSources = noopCatalog,
                )
            }

            val activeFilesAfterPrewarm = casStore.listActiveSubtitleFiles()
            assertEquals("Detail-page prewarm must never evict active CAS files", 2, activeFilesAfterPrewarm.size)
            for (f in activeFilesBeforePrewarm) {
                assertTrue("Active CAS file ${f.name} must remain intact on disk", f.isFile && f.length() > 0L)
            }
        } finally {
            sharedCasDir.deleteRecursively()
        }
    }

    @Test
    fun pLang_latinSanitizerQualityGatesMtDetectorAndOrthography() {
        val rawCue = "MAN: [door slams] What the hell, hold on, you're kidding me! (sighs) اردو"
        val sanitized = LatinEnglishText.sanitize(rawCue)
        assertFalse("LatinEnglishText must contain 0 Nastaliq codepoints", LatinEnglishText.containsArabicOrNastaliq(sanitized.text))
        assertFalse(sanitized.text.contains("MAN:"))
        assertFalse(sanitized.text.contains("[door slams]"))
        assertFalse(sanitized.text.contains("(sighs)"))
        assertEquals("What on earth, wait a moment, you are joking!", sanitized.text)

        val refEnSrt = """
            1
            00:00:01,000 --> 00:00:02,500
            Right?

            2
            00:00:02,700 --> 00:00:04,200
            Fine!

            3
            00:00:04,400 --> 00:00:06,500
            Honey, what the hell is going on?

            4
            00:00:06,700 --> 00:00:08,500
            You know that I was trying to

            5
            00:00:08,600 --> 00:00:10,500
            save your life from them.

            6
            00:00:10,800 --> 00:00:13,000
            Please tell me where you went.

            7
            00:00:13,200 --> 00:00:15,500
            Why did you hide the truth?
        """.trimIndent()
        val refEnDoc = CueDocument.parse(refEnSrt, SubtitleLanguage.ENGLISH).getOrThrow()

        val googleMtDumpSrt = """
            1
            00:00:01,000 --> 00:00:02,500
            دائیں؟

            2
            00:00:02,700 --> 00:00:04,200
            جرمانہ!

            3
            00:00:04,400 --> 00:00:06,500
            شہد، کیا جہنم ہو رہا ہے؟

            4
            00:00:06,700 --> 00:00:08,500
            آپ جانتے ہیں کہ یہ میرے نیند میں کیا گیا تھا۔

            5
            00:00:08,600 --> 00:00:10,500
            تم نے اپنی زندگی کو ان کے ذریعے بچایا گیا تھا۔

            6
            00:00:10,800 --> 00:00:13,000
            تو کہاں گیا تھا، یہ ایک اچھا خیال ہے۔

            7
            00:00:13,200 --> 00:00:15,500
            آپ کا استقبال ہے، مقدس گندگی۔
        """.trimIndent()

        val mtDoc = CueDocument.parse(googleMtDumpSrt, SubtitleLanguage.URDU).getOrThrow()
        val mtAudit = UrduMtDumpDetector.audit(urduDoc = mtDoc, referenceEnglishDoc = refEnDoc)
        assertTrue("Google Translate calque dump must score >= 60, got ${mtAudit.mtDumpScore}", mtAudit.mtDumpScore >= 60)

        val mtGateVerdict = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = googleMtDumpSrt,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.OPENSUBTITLES),
            alignment = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.UNANCHORED, 0.8, 0, 0, 0),
            referenceEnglishDoc = refEnDoc,
        )
        assertTrue("MT dump must be rejected by UrduQualityGate", mtGateVerdict is VerifiedSubtitleCandidate.Rejected.MtDump)

        val authenticHumanUrduSrt = """
            1
            00:00:01,000 --> 00:00:02,500
            ہے نا؟

            2
            00:00:02,700 --> 00:00:04,200
            ٹھیک ہے بھئی!

            3
            00:00:04,400 --> 00:00:06,500
            جان، یہ آخر چل کیا رہا ہے یار؟

            4
            00:00:06,700 --> 00:00:08,500
            تمہیں تو پتا ہے میں پوری کوشش کر رہا تھا کہ

            5
            00:00:08,600 --> 00:00:10,500
            کسی طرح تمہاری جان ان لوگوں سے بچا لوں۔

            6
            00:00:10,800 --> 00:00:13,000
            ذرا مجھے سچ بتاؤ نا کہ تم کہاں گئے تھے؟

            7
            00:00:13,200 --> 00:00:15,500
            تم نے مجھ سے اتنی بڑی بات کیوں چھپائی بھلا؟
        """.trimIndent()
        val authenticVerdict = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = authenticHumanUrduSrt,
            language = SubtitleLanguage.URDU,
            provenance = ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.WYZIE),
            alignment = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.UNANCHORED, 0.9, 0, 0, 0),
            referenceEnglishDoc = refEnDoc,
        )
        assertTrue("Authentic conversational Urdu must be admitted, got $authenticVerdict", authenticVerdict is VerifiedSubtitleCandidate.AlignedHumanCatalogue)
        val admittedHuman = authenticVerdict as VerifiedSubtitleCandidate.AlignedHumanCatalogue
        val humanScore = admittedHuman.mtAudit!!.mtDumpScore
        assertTrue("Authentic conversational Urdu must score < 30, got $humanScore", humanScore < 30)
        assertEquals(AuthenticityGrade.AUTHENTIC_HUMAN, admittedHuman.authenticity)

        val movieSpec = MediaLookupSpec.Movie("tt1375666", 27205, listOf("Inception"), 2010)
        val hiRes = CatalogueCandidateGate.evaluatePreDownload(
            CatalogueCandidateMeta(CatalogueSourceKey.SUBDL, SubtitleLanguage.URDU, "Inception.2010.1080p.[SDH].srt"),
            movieSpec,
        )
        assertTrue(hiRes.isFailure)
        assertTrue((hiRes.exceptionOrNull() as CandidateGateRejectedException).rejection is VerifiedSubtitleCandidate.Rejected.HearingImpaired)

        for (banned in listOf("blackgold", "akhaliq_655", "saad.jan")) {
            val bannedRes = CatalogueCandidateGate.evaluatePreDownload(
                CatalogueCandidateMeta(
                    catalogue = CatalogueSourceKey.OPENSUBTITLES,
                    language = SubtitleLanguage.URDU,
                    titleOrReleaseText = "Inception.2010.1080p.BluRay",
                    uploaderName = banned,
                ),
                movieSpec,
            )
            assertTrue("Expected $banned to be rejected pre-download", bannedRes.isFailure)
            assertTrue((bannedRes.exceptionOrNull() as CandidateGateRejectedException).rejection is VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader)
        }

        val persianTwoCue = """
            1
            00:00:01,000 --> 00:00:03,000
            من به خانه می‌روم و فردا برمی‌گردم.

            2
            00:00:04,000 --> 00:00:06,000
            این فیلم بسیار زیبا است و دوستانم اینجا هستند.
        """.trimIndent()
        assertTrue("Persian 2-cue track must fail UrduOrthographyGate", UrduOrthographyGate.verifyUrduOrthography(persianTwoCue).isFailure)

        val arabicTwoCue = """
            1
            00:00:01,000 --> 00:00:03,000
            أنا ذاهب إلى البيت الآن ولا أريد التحدث.

            2
            00:00:04,000 --> 00:00:06,000
            هذا الفيلم رائع جدا في هذه الليلة.
        """.trimIndent()
        assertTrue("Arabic 2-cue track must fail UrduOrthographyGate", UrduOrthographyGate.verifyUrduOrthography(arabicTwoCue).isFailure)

        println("BENCHMARK_P_LANG_GATES | mt_dump_score=${mtAudit.mtDumpScore} | authentic_human_score=$humanScore")
    }

    @Test
    fun pLang_sceneBolChalOneToOneCueIdBisectRegisterCalquePurifierAndCps() = runBlocking {
        val sixteenCueSrt = buildString {
            val lines = listOf(
                "I was forced to...",
                "kill my own father.",
                "Sir, Detective Khan is waiting outside the office.",
                "Officer, please listen to the witness carefully.",
                "My family trusted you from the very beginning.",
                "Wait a minute, call the doctor right now!",
                "We cannot leave the house while they are watching.",
                "Tell me everything you saw at the station.",
                "Nobody knew where the second key was hidden.",
                "Stay calm and keep your phone in your pocket.",
                "Did you check the back door before coming upstairs?",
                "He has been working on this plan all week.",
                "Please don't say a word until the lawyer arrives.",
                "I told you yesterday that this road was closed.",
                "Everything will be fine once we cross the bridge.",
                "Thank you for standing by my family tonight.",
            )
            for (i in lines.indices) {
                val startMs = (i + 1) * 3_500L
                val endMs = startMs + 2_200L
                append(i + 1).append('\n')
                append(srtTime(startMs)).append(" --> ").append(srtTime(endMs)).append('\n')
                append(lines[i]).append("\n\n")
            }
        }
        val donorDoc = CueDocument.parse(sixteenCueSrt, SubtitleLanguage.ENGLISH).getOrThrow()
        assertEquals(16, donorDoc.cues.size)

        val lineTranslations = mapOf(
            "I was forced to..." to "مجھے مجبور کیا گیا کہ...",
            "kill my own father." to "اپنے ہی والد کو مار ڈالوں۔",
            "Sir, Detective Khan is waiting outside the office." to "سر، ڈیٹیکٹو خان آفس کے باہر آپ کا انتظار کر رہے ہیں۔",
            "Officer, please listen to the witness carefully." to "آفیسر، کرپیا گواہ کی بات دھیان سے سنیے۔",
            "My family trusted you from the very beginning." to "میرے پریوار نے شروع سے آپ پر وشواس کیا تھا۔",
            "Wait a minute, call the doctor right now!" to "ایک منٹ رکو، ابھی doctor کو phone کرو!",
            "We cannot leave the house while they are watching." to "جب تک وہ دیکھ رہے ہیں ہم گھر میں رہیں گے۔",
            "Tell me everything you saw at the station." to "اسٹیشن پر جو کچھ دیکھا وہ سب مجھے سچ بتاؤ۔",
            "Nobody knew where the second key was hidden." to "کسی کو پتا نہیں تھا کہ دوسری چابی کہاں چھپائی گئی ہے۔",
            "Stay calm and keep your phone in your pocket." to "پرسکون رہو اور اپنا فون اپنی جیب میں رکھو۔",
            "Did you check the back door before coming upstairs?" to "اوپر آنے سے پہلے پچھلا دروازہ چیک کیا تھا نا؟",
            "He has been working on this plan all week." to "وہ پورا ہفتہ اس پلان پر کام کر رہا ہے۔",
            "Please don't say a word until the lawyer arrives." to "جب تک وکیل نہ آئے کرپیا کچھ مت بولنا۔",
            "I told you yesterday that this road was closed." to "کل ہی تمہیں بتایا تھا کہ یہ راستہ بند ہے۔",
            "Everything will be fine once we cross the bridge." to "پل پار کرتے ہی سب ٹھیک ہو جائے گا۔",
            "Thank you for standing by my family tonight." to "آج رات میرے پریوار کا ساتھ دینے کے لیے دھنیواد۔",
        )

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val brokenBatchTransport = DefaultBolChalTranslationTransport(
            httpGetOverride = { _, params, _ ->
                val q = params["q"].orEmpty()
                val lines = q.split('\n').filter { it.isNotBlank() }
                val translatedPayload = if (lines.size >= 8) {
                    "یہ ایک ٹوٹا ہوا بیچ ترجمہ ہے جس میں مارکرز غائب ہو چکے ہیں۔"
                } else {
                    lines.joinToString("\n") { line ->
                        val markerMatch = Regex("""^(⟦\d+⟧)\s*(.*)$""").find(line.trim())
                        if (markerMatch != null) {
                            val marker = markerMatch.groupValues[1]
                            val en = markerMatch.groupValues[2].trim()
                            val ur = lineTranslations[en] ?: "یہ مکالمہ بالکل واضح ہے۔"
                            "$marker $ur"
                        } else {
                            lineTranslations[line.trim()] ?: "یہ مکالمہ بالکل واضح ہے۔"
                        }
                    }
                }
                val outer = mapper.createArrayNode()
                val sentenceArr = outer.addArray()
                val seg = sentenceArr.addArray()
                seg.add(translatedPayload)
                seg.add(q)
                mapper.writeValueAsString(outer)
            }
        )

        val localized = SceneBolChalLocalizer.localize(
            englishDonorDoc = donorDoc,
            donorProvenance = DonorProvenance.StreamDonor(StreamProviderKey.VIDLINK),
            alignmentProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.NATIVE_STREAM_PACK, 1.0, 0, 0, 0),
            config = BolChalConfig(maxComfortCps = 17.0, minRequiredCueCoverage = 0.98),
            transport = brokenBatchTransport,
        ).getOrThrow()

        assertTrue(
            "Bisect must recover >= 98% of cues on broken 16-cue batch, got ${localized.qualityReport.cueSurvivalRatio}",
            localized.qualityReport.cueSurvivalRatio >= 0.98
        )
        assertEquals(1.0, localized.qualityReport.cueSurvivalRatio, 1e-6)

        val cue1 = localized.document.cues.first { it.id == CueId(0) }
        val cue2 = localized.document.cues.first { it.id == CueId(1) }
        assertFalse("Twist reveal ('والد'/'مار') must NEVER invert into Cue 1: ${cue1.text}", cue1.text.contains("والد") || cue1.text.contains("مار"))
        assertTrue("Cue 1 must retain suspense ellipsis '...': ${cue1.text}", cue1.text.endsWith("..."))
        assertTrue("Cue 2 must carry the reveal ('والد'): ${cue2.text}", cue2.text.contains("والد"))

        val allLocalizedText = localized.document.cues.joinToString(" ") { it.text }
        assertFalse("Hindi calque پریوار must be purified", allLocalizedText.contains("پریوار"))
        assertFalse("Hindi calque کرپیا must be purified", allLocalizedText.contains("کرپیا"))
        assertFalse("Hindi calque دھنیواد must be purified", allLocalizedText.contains("دھنیواد"))
        assertFalse("Hindi calque وشواس must be purified", allLocalizedText.contains("وشواس"))
        assertTrue(allLocalizedText.contains("گھر والے"))
        assertTrue(allLocalizedText.contains("پلیز"))
        assertTrue(allLocalizedText.contains("شکریہ"))
        assertTrue(allLocalizedText.contains("بھروسہ"))
        assertTrue(allLocalizedText.contains("ڈاکٹر"))
        assertTrue(allLocalizedText.contains("فون"))

        val formalSrt = buildString {
            for (i in 1..20) {
                val s = i * 3_000L
                val e = s + 2_000L
                append(i).append('\n')
                append(srtTime(s)).append(" --> ").append(srtTime(e)).append('\n')
                if (i <= 16) {
                    append("Sir, Detective Malik is waiting for your orders in the office.\n\n")
                } else {
                    append("Hey Samir, let's grab tea outside.\n\n")
                }
            }
        }
        val windows = SceneBolChalLocalizer.buildSceneWindows(
            sourceCues = SceneBolChalLocalizer.normalizeDonorCues(CueDocument.parse(formalSrt, SubtitleLanguage.ENGLISH).getOrThrow()),
            useLlmWindowSizes = false,
        )
        assertEquals(AddressRegister.AAP, windows.first().speakerCard.dominantSceneRegister)

        val peerSrt = """
            1
            00:00:01,000 --> 00:00:03,000
            Hey Tariq, where did you park the car?

            2
            00:00:03,500 --> 00:00:05,500
            Come inside quickly before it starts raining.
        """.trimIndent()
        val peerWindows = SceneBolChalLocalizer.buildSceneWindows(
            sourceCues = SceneBolChalLocalizer.normalizeDonorCues(CueDocument.parse(peerSrt, SubtitleLanguage.ENGLISH).getOrThrow()),
            useLlmWindowSizes = false,
        )
        assertEquals(AddressRegister.TUM, peerWindows.first().speakerCard.dominantSceneRegister)

        val denseCues = listOf(
            CueEntry(
                id = CueId(1),
                span = CueSpan(1_000L, 2_800L),
                text = "میں یہ کہہ رہا تھا کہ وہ اپنے گھر میں بیٹھ کر سارا کام کر رہا ہے",
            ),
            CueEntry(
                id = CueId(2),
                span = CueSpan(4_200L, 6_600L),
                text = "براہ مہربانی اس وقت آپ اپنے گھر میں رہیں کیونکہ باہر پولیس سارے راستے بند کر رہی ہے",
            ),
            CueEntry(
                id = CueId(3),
                span = CueSpan(9_500L, 16_000L),
                text = "جب تک یہ شدید طوفان نہیں رکتا آپ اپنے گھر میں محفوظ رہیں، کیونکہ باہر پولیس ہر بڑی سڑک پر پہرہ دے کر اپنا کام کر رہی ہے",
            ),
        )
        val refined = UrduCpsRefiner.refineDocument(
            cues = denseCues,
            config = BolChalConfig(maxComfortCps = 17.0),
            videoDurationMs = 20_000L,
        )
        assertTrue("Expected two-clause turn to split into >= 4 refined cues, got ${refined.size}", refined.size >= 4)
        var maxMeasuredCps = 0.0
        for (cue in refined) {
            val cps = UrduCpsRefiner.computeVisibleCps(cue.text, cue.span.durationMs)
            if (cps > maxMeasuredCps) maxMeasuredCps = cps
            assertTrue("Refined cue CPS ${"%.2f".format(Locale.ROOT, cps)} exceeded 17.0: '${cue.text}'", cps <= 17.0)
            for (line in cue.text.split('\n')) {
                val trimmed = line.trim()
                assertFalse("Line must never start with orphaned postposition 'میں': '$trimmed'", trimmed.startsWith("میں ") || trimmed == "میں")
                assertFalse("Line must never split inside compound verb 'کر | رہا ہے': '$trimmed'", trimmed.startsWith("رہا ہے") || trimmed.startsWith("رہی ہے"))
            }
        }

        val trickyWords = listOf("وہ", "اپنے", "گھر", "میں", "کام", "کر", "رہا", "ہے")
        val splitIdx = UrduCpsRefiner.findBestSyntacticSplitIndex(trickyWords)
        assertNotNull(splitIdx)
        val leftPart = trickyWords.subList(0, splitIdx!! + 1)
        val rightPart = trickyWords.subList(splitIdx + 1, trickyWords.size)
        assertFalse("Must not split 'گھر | میں'", leftPart.last() == "گھر" && rightPart.first() == "میں")
        assertFalse("Must not split 'کر | رہا'", leftPart.last() == "کر" && rightPart.first() == "رہا")
        assertFalse("Must not split 'رہا | ہے'", leftPart.last() == "رہا" && rightPart.first() == "ہے")

        println(
            "BENCHMARK_P_LANG_BOLCHAL | survival=${localized.qualityReport.cueSurvivalRatio} | " +
                "max_localized_cps=${"%.2f".format(Locale.ROOT, localized.qualityReport.maxNormalDialogueCps)} | " +
                "max_dense_refined_cps=${"%.2f".format(Locale.ROOT, maxMeasuredCps)}"
        )
    }
}
