package com.phisher98.subtitles

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.phisher98.UrduSubtitleEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Pr21ReviewFixesTest {

    private fun makeEnglishSrt(cueCount: Int, startSec: Int = 10, stepSec: Int = 4, offsetMs: Long = 0L, prefix: String = "Hello line"): String {
        val sb = StringBuilder()
        for (i in 1..cueCount) {
            val sMs = (startSec + (i - 1) * stepSec) * 1000L + offsetMs
            val eMs = sMs + 2000L
            sb.append("$i\n")
            sb.append("${formatSrtTime(sMs)} --> ${formatSrtTime(eMs)}\n")
            sb.append("$prefix $i with the character here.\n\n")
        }
        return sb.toString()
    }

    private fun makeUrduSrt(cueCount: Int, startSec: Int = 10, stepSec: Int = 4, offsetMs: Long = 0L): String {
        val lines = listOf(
            "یار بات سنو، ابھی ہمارے پاس وقت بہت کم ہے۔",
            "تم نے دروازہ ٹھیک سے بند کیا تھا نا؟",
            "میری بات غور سے سنو، اس فائل کا کسی کو پتا نہیں۔",
            "گاڑی کے پاس رکو اور کوئی آئے تو فوراً فون کرنا۔",
            "کل رات تم نے وعدہ کیا تھا کہ اب یہ سب ختم ہے۔",
        )
        val sb = StringBuilder()
        for (i in 1..cueCount) {
            val sMs = (startSec + (i - 1) * stepSec) * 1000L + offsetMs
            val eMs = sMs + 2000L
            sb.append("$i\n")
            sb.append("${formatSrtTime(sMs)} --> ${formatSrtTime(eMs)}\n")
            sb.append("${lines[(i - 1) % lines.size]} ($i)\n\n")
        }
        return sb.toString()
    }

    private fun formatSrtTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val mins = (safe % 3_600_000L) / 60_000L
        val secs = (safe % 60_000L) / 1000L
        val rem = safe % 1000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, mins, secs, rem)
    }

    @Test
    fun catalogHttpTransportBlocksPrivateLoopbackAndNonHttpSchemes() = runBlocking {
        val defaultTransport = DefaultCatalogHttpTransport()
        val blockedUrls = listOf(
            "http://127.0.0.1:8080/sub.srt",
            "http://localhost:9000/sub.srt",
            "http://foo.local/sub.srt",
            "http://service.internal/sub.srt",
            "http://10.0.0.1/sub.srt",
            "http://172.16.0.1/sub.srt",
            "http://192.168.1.50/sub.srt",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/sub.srt",
            "http://[::1]:8080/sub.srt",
            "http://[::ffff:127.0.0.1]/sub.srt",
            "http://[fc00::1]/sub.srt",
            "file:///etc/passwd",
        )
        for (bad in blockedUrls) {
            val getResult = defaultTransport.get(bad)
            assertTrue("Expected failure for blocked URL $bad", getResult.isFailure)
        }
        // Public IP literal must pass validation (no DNS lookup required)
        assertEquals("https://8.8.8.8/sub.srt", defaultTransport.validateDestinationUrl("https://8.8.8.8/sub.srt"))

        // Opt-in test override permits local HttpServer
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/ok.srt") { ex ->
            val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello world\n".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val permissive = DefaultCatalogHttpTransport(allowPrivateNetworks = true)
            val localUrl = "http://127.0.0.1:${server.address.port}/ok.srt"
            val ok = permissive.get(localUrl)
            assertTrue("Expected local test transport to succeed, got ${ok.exceptionOrNull()}", ok.isSuccess)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun composedWarpRoundTripsAndPwlCompositionPreservesBothTransforms() {
        val inner = SubtitleTimeWarp.PiecewiseLinear.createValidated(
            baseFpsRatio = FpsRatio.PAL_25_TO_NTSC_23976,
            knots = listOf(WarpKnot(0L, 1_000_000L), WarpKnot(60_000_000L, 62_000_000L)),
        ).getOrThrow()
        val outer = SubtitleTimeWarp.Affine(FpsRatio.NTSC_23976_TO_PAL_25, 1_500L)
        val composed = SubtitleTimeWarp.compose(inner, outer)
        val serialized = composed.serialize()
        assertTrue("Expected cmp(...) serialization, got $serialized", serialized.startsWith("cmp("))

        val reparsed = SubtitleTimeWarp.parse(serialized).getOrThrow()
        for (tUs in listOf(0L, 12_000_000L, 45_000_000L, 120_000_000L)) {
            assertEquals(composed.forwardUs(tUs), reparsed.forwardUs(tUs))
        }

        // Nested composition
        val doubleComposed = SubtitleTimeWarp.compose(
            composed,
            SubtitleTimeWarp.PiecewiseLinear.createValidated(
                baseFpsRatio = FpsRatio.UNITY,
                knots = listOf(WarpKnot(0L, -500_000L), WarpKnot(100_000_000L, 99_500_000L)),
            ).getOrThrow()
        )
        val reparsedNested = SubtitleTimeWarp.parse(doubleComposed.serialize()).getOrThrow()
        assertEquals(doubleComposed.forwardUs(24_000_000L), reparsedNested.forwardUs(24_000_000L))

        val proof = AlignmentProof(
            warp = composed,
            anchorKind = AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR,
            syncConfidence = 0.92,
            medianOnsetResidualMs = 15,
            p95OnsetResidualMs = 30,
            splitCount = 0,
        )
        val decodedProof = ProvenanceCodec.decodeSyncProofOrNull(ProvenanceCodec.encodeSyncProof(proof))
        assertNotNull(decodedProof)
        assertEquals(composed.forwardUs(24_000_000L), decodedProof!!.warp.forwardUs(24_000_000L))
    }

    @Test
    fun warpedToMediaReturnsEmptyDocumentWhenWarpDiscardsAllCues() {
        val doc = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(
                CueEntry(CueId(1), CueSpan(1_000L, 2_000L), "Early cue 1"),
                CueEntry(CueId(2), CueSpan(2_500L, 3_500L), "Early cue 2"),
            ),
        )
        val shifted = doc.warpedToMedia(SubtitleTimeWarp.Affine(FpsRatio.UNITY, -10_000L))
        assertTrue("Expected empty CueDocument when all cues map <= 0ms", shifted.cues.isEmpty())
    }

    @Test
    fun dialogueFingerprintIgnoresAssAndXmlMarkupTags() {
        val plain = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(CueEntry(CueId(1), CueSpan(1_000L, 3_000L), "Hello there, friend!")),
        )
        val html = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(CueEntry(CueId(1), CueSpan(1_000L, 3_000L), "<i><b>Hello there, friend!</b></i>")),
        )
        val ass = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(CueEntry(CueId(1), CueSpan(1_000L, 3_000L), "{\\an8\\pos(320,50)}Hello there, friend!")),
        )
        assertEquals(plain.dialogueFingerprint, html.dialogueFingerprint)
        assertEquals(plain.dialogueFingerprint, ass.dialogueFingerprint)
    }

    @Test
    fun parseGestdownShowIdRejectsUnmatchedTitlesUnlessExplicitlyPermitted() {
        val sources = SubtitleCatalogSources()
        val json = """{"shows":[{"id":"show-wrong-1","name":"Totally Different Show"},{"id":"show-wrong-2","name":"Another Series"}]}"""
        assertNull(sources.parseGestdownShowId(json, "Breaking Bad", allowUnmatchedFallback = false))
        assertEquals("show-wrong-1", sources.parseGestdownShowId(json, "Breaking Bad", allowUnmatchedFallback = true))

        val matchedJson = """{"shows":[{"id":"show-1","name":"Breaking News"},{"id":"show-bb","name":"Breaking Bad"}]}"""
        assertEquals("show-bb", sources.parseGestdownShowId(matchedJson, "Breaking Bad", allowUnmatchedFallback = false))
    }

    @Test
    fun bolChalWindowCacheKeyIncludesPromptContextRejectsPartialAndSurvivesOwnerCancel() = runBlocking {
        BolChalWindowCache.clearForTesting()
        val baseCues = listOf(
            LatinSourceCue(
                id = CueId(1),
                span = CueSpan(1000L, 3000L),
                maxVisibleChars = 42,
                text = LatinEnglishText.sanitize("Hi there."),
                endsWithRevealEllipsis = false,
            ),
            LatinSourceCue(
                id = CueId(2),
                span = CueSpan(4000L, 6000L),
                maxVisibleChars = 42,
                text = LatinEnglishText.sanitize("How are you?"),
                endsWithRevealEllipsis = false,
            ),
        )
        val baseCard = SpeakerContextCard(
            dominantSceneRegister = AddressRegister.TUM,
            speakerGenderHint = GenderHint.MASCULINE,
            characters = mapOf("Ali" to CharacterRegisterProfile("Ali", "علی", GenderHint.MASCULINE, AddressRegister.TUM)),
            genreToneHint = "thriller",
        )
        val window1 = SceneWindow(0, emptyList(), baseCues, emptyList(), baseCard)
        val llmCfg = BolChalConfig(llmEndpointUrl = "https://api.example.com/v1", llmModelId = "gpt-test", llmApiKey = "k")

        val keyBase = BolChalWindowCache.buildWindowKey(window1, llmCfg)
        val keyDiffTone = BolChalWindowCache.buildWindowKey(
            window1.copy(speakerCard = baseCard.copy(genreToneHint = "comedy")),
            llmCfg,
        )
        val keyDiffChars = BolChalWindowCache.buildWindowKey(
            window1.copy(
                speakerCard = baseCard.copy(
                    characters = mapOf("Zara" to CharacterRegisterProfile("Zara", "زارا", GenderHint.FEMININE, AddressRegister.AAP))
                )
            ),
            llmCfg,
        )
        val keyDiffMaxChars = BolChalWindowCache.buildWindowKey(
            window1.copy(targetCues = listOf(baseCues[0].copy(maxVisibleChars = 24), baseCues[1])),
            llmCfg,
        )
        assertNotEquals(keyBase, keyDiffTone)
        assertNotEquals(keyBase, keyDiffChars)
        assertNotEquals(keyBase, keyDiffMaxChars)

        // Incomplete translation must NOT be cached
        val expectedIds = setOf(CueId(1), CueId(2))
        val computeCount = AtomicInteger(0)
        val partialResult = BolChalWindowCache.getOrTranslateSingleFlight(keyBase, expectedIds) {
            computeCount.incrementAndGet()
            Result.success(mapOf(CueId(1) to BolChalCueOut(CueId(1), "سلام")) to LocalizationTierKey.LLM_SCENE)
        }
        assertTrue(partialResult.isSuccess)
        assertNull("Incomplete window must not enter cache", BolChalWindowCache.peek(keyBase))

        val fullResult = BolChalWindowCache.getOrTranslateSingleFlight(keyBase, expectedIds) {
            computeCount.incrementAndGet()
            Result.success(
                mapOf(
                    CueId(1) to BolChalCueOut(CueId(1), "سلام"),
                    CueId(2) to BolChalCueOut(CueId(2), "کیسے ہو؟"),
                ) to LocalizationTierKey.LLM_SCENE
            )
        }
        assertTrue(fullResult.isSuccess)
        assertEquals(2, computeCount.get())
        assertNotNull(BolChalWindowCache.peek(keyBase))

        // Owner cancellation must let concurrent waiter retry and succeed
        val retryKey = "owner-cancel-test-${System.nanoTime()}"
        val ownerEntered = CompletableDeferred<Unit>()
        val waiterResult = CompletableDeferred<Result<Pair<Map<CueId, BolChalCueOut>, LocalizationTierKey>>>()
        val calls = AtomicInteger(0)

        val ownerJob = async {
            BolChalWindowCache.getOrTranslateSingleFlight(retryKey, setOf(CueId(1))) {
                calls.incrementAndGet()
                ownerEntered.complete(Unit)
                delay(10_000L)
                Result.success(mapOf(CueId(1) to BolChalCueOut(CueId(1), "never")) to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
            }
        }
        ownerEntered.await()
        val waiterJob = async {
            val res = BolChalWindowCache.getOrTranslateSingleFlight(retryKey, setOf(CueId(1))) {
                calls.incrementAndGet()
                Result.success(mapOf(CueId(1) to BolChalCueOut(CueId(1), "دوبارہ کوشش")) to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
            }
            waiterResult.complete(res)
        }
        delay(40L)
        ownerJob.cancelAndJoin()
        val res = withTimeout(5_000L) { waiterResult.await() }
        waiterJob.await()
        assertTrue("Active waiter should recover after owner cancellation", res.isSuccess)
        assertEquals("دوبارہ کوشش", res.getOrThrow().first[CueId(1)]?.urduText)
        assertEquals(2, calls.get())
    }

    @Test
    fun prewarmSeparatesTitleOnlyEpisodesEnforcesCharBudgetAndSurvivesOwnerCancel() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-prewarm-test").toFile()
        val casStore = UrduCasStore(rootDir = tempRoot, maxPrewarmTotalChars = 600)
        val specShowA = MediaLookupSpec.Episode(
            imdbId = null,
            tmdbId = null,
            titles = listOf("Alpha Mystery"),
            coordinate = EpisodeCoordinate.VerifiedSeasonEpisode(1, 1),
            seriesPremiereYear = 2024,
            seasonAirYear = 2024,
        )
        val specShowB = specShowA.copy(titles = listOf("Beta Comedy"))

        val transport = CatalogHttpTransport.fromGet { url, _ ->
            CatalogHttpResponse(200, "{}".toByteArray(), url)
        }
        val sources = SubtitleCatalogSources(transport = transport)
        val bundleA = casStore.prewarmSubtitlesAsync(specShowA, sources, warmBolChalWindows = false)
        assertNotNull(casStore.getPrewarmedBundle(specShowA))
        assertNull("Different title without IMDb/TMDB must NOT hit Show A prewarm bundle", casStore.getPrewarmedBundle(specShowB))
        assertNotEquals(bundleA.summary.lookupKey, casStore.prewarmMetadataAsync(specShowB, sources).lookupKey)

        // Aggregate character budget eviction (450 + 450 > 600 evicts eldest bundle)
        casStore.rememberPlaybackDiscovery(specShowA, bundleA.discovery, mapOf("en|a" to "a".repeat(450)))
        casStore.rememberPlaybackDiscovery(specShowB, bundleA.discovery, mapOf("en|b" to "b".repeat(450)))
        assertNull("Eldest bundle should be evicted when aggregate prewarm char budget is exceeded", casStore.getPrewarmedBundle(specShowA))
        assertNotNull(casStore.getPrewarmedBundle(specShowB))

        // Owner cancellation in prewarmSubtitlesAsync lets active waiter retry & complete
        val blockingEntered = CompletableDeferred<Unit>()
        val callNum = AtomicInteger(0)
        val slowSources = SubtitleCatalogSources(
            transport = object : CatalogHttpTransport {
                override val supportsExtendedLiveScrapers = false
                override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long): Result<CatalogHttpResponse> {
                    if (url.contains("yifysubtitles") || url.contains("subdl")) {
                        if (callNum.incrementAndGet() == 1) {
                            blockingEntered.complete(Unit)
                            delay(10_000L)
                        }
                    }
                    return Result.success(CatalogHttpResponse(200, "{}".toByteArray(), url))
                }
            }
        )
        val prewarmSpec = MediaLookupSpec.Movie(imdbId = "tt7770001", tmdbId = 7770001, titles = listOf("Prewarm Test"), releaseYear = 2024)
        val owner = async { casStore.prewarmSubtitlesAsync(prewarmSpec, slowSources, warmBolChalWindows = false) }
        blockingEntered.await()
        val waiter = async {
            casStore.prewarmSubtitlesAsync(prewarmSpec, slowSources, warmBolChalWindows = false)
        }
        delay(40L)
        owner.cancelAndJoin()
        val waiterBundle = withTimeout(5_000L) { waiter.await() }
        assertTrue(waiterBundle.isFullPayloadPrewarm)
    }

    @Test
    fun englishMirrorsDoNotExhaustAdmissionBeforeUniqueTracks() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-mirror-cap").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val identicalEn = makeEnglishSrt(10, prefix = "Identical mirror dialogue")
        val uniqueEn = makeEnglishSrt(10, prefix = "Brand new unique dialogue")
        server.createContext("/mirror.srt") { ex ->
            val bytes = identicalEn.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/unique.srt") { ex ->
            val bytes = uniqueEn.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val session = EpisodeSubtitleSession(
                lookupSpec = MediaLookupSpec.Movie(imdbId = "tt1112223", tmdbId = null, titles = listOf("Mirror Cap Test"), releaseYear = 2024),
                mode = SessionSubtitleMode.BILINGUAL_DOWNLOAD,
                casStore = UrduCasStore(tempRoot),
                httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
                targetUrduDiversityCount = 0,
                targetEnglishCatalogCount = 6,
                progressiveBilingualMode = false,
                subtitleCallback = { emitted.add(it) },
            )
            // Send 8 formatting/mirror variants from HumanCatalogue
            for (i in 1..8) {
                val sub = newSubtitleFile("English [SubDL]", "http://127.0.0.1:$port/mirror.srt?v=$i") {
                    this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL, "m$i"))
                }
                session.ingestRawSubtitle(
                    sub,
                    rawBytesOverride = (if (i % 2 == 0) identicalEn else identicalEn.replace("Identical", "<i>Identical</i>")).toByteArray(),
                )
            }
            val uniqueSub = newSubtitleFile("English [SubSource]", "http://127.0.0.1:$port/unique.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSOURCE))
            }
            session.ingestRawSubtitle(uniqueSub, rawBytesOverride = uniqueEn.toByteArray())
            session.completeSession()

            val englishTracks = emitted.filter { it.lang.startsWith("English") }
            assertEquals("Expected 1 deduplicated mirror + 1 unique track = 2 emitted English tracks, got ${englishTracks.map { it.lang }}", 2, englishTracks.size)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun deferredAnchorsAreNotFlushedWithoutActiveVideoAndEvictedDonorIsRejected() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-donor-evict").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val inTranslate = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Movie(imdbId = "tt3334445", tmdbId = null, titles = listOf("Evict Donor"), releaseYear = 2024),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(tempRoot),
            httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
            multiProviderVideoSession = true,
            progressiveBilingualMode = true,
            bolChalTransport = { window, _ ->
                inTranslate.complete(Unit)
                allowFinish.await()
                val m = window.targetCues.associate { it.id to BolChalCueOut(it.id, "یہ اردو ترجمہ ہے نمبر ${it.id.value}") }
                Result.success(m to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
            },
            subtitleCallback = { emitted.add(it) },
        )

        val englishSrt = makeEnglishSrt(12, startSec = 10, stepSec = 4)
        // Foreign stream provider donor without native pack and NO registered active video
        val foreignDonor = newSubtitleFile("English [KickAss]", "https://cdn.example.invalid/kaa.srt") {
            this.headers = mapOf(
                "X-StreamPlay-Source" to "vidsrc",
                "X-StreamPlay-Foreign-Sidecar" to "true",
            )
        }
        session.ingestRawSubtitle(foreignDonor, rawSourceHint = "vidsrc", rawBytesOverride = englishSrt.toByteArray())

        val completeJob = async { session.completeSession() }
        inTranslate.await()

        // Attach a speech timeline located 30 minutes later so reconciliation evicts the buffered foreign donor
        val farTimeline = SpeechActivityTimeline(
            speechSpans = (0 until 10).map { i -> SpeechSpan(1_800_000L + i * 4_000L, 1_802_000L + i * 4_000L, 1.0f) },
            observedWindows = listOf(ObservedWindow(1_790_000L, 1_900_000L)),
            mediaDurationMs = 2_000_000L,
        )
        session.attachSpeechTimeline(farTimeline)
        allowFinish.complete(Unit)
        val report = withTimeout(5_000L) { completeJob.await() }

        assertEquals("No provider anchors may flush when activeVideos is empty", 0, report.emittedProviderAnchors)
        assertTrue(
            "Evicted foreign donor localization must be rejected instead of emitted with stale alignment",
            report.emittedUrduCandidates.isEmpty()
        )
    }

    @Test
    fun softDonorKeepsUnanchoredProofAndRetainsMismatchedPendingUrdu() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-soft-anchor").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val session = EpisodeSubtitleSession(
            lookupSpec = MediaLookupSpec.Movie(imdbId = "tt5556667", tmdbId = null, titles = listOf("Soft Anchor"), releaseYear = 2024),
            mode = SessionSubtitleMode.URDU,
            casStore = UrduCasStore(tempRoot),
            httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
            progressiveBilingualMode = true,
            subtitleCallback = { emitted.add(it) },
        )

        // Catalogue Urdu at 10s..130s
        val urduSrt = makeUrduSrt(16, startSec = 10, stepSec = 6)
        val urSub = newSubtitleFile("Urdu [SubDL]", "https://example.invalid/ur.srt") {
            this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))
        }
        session.ingestRawSubtitle(urSub, rawBytesOverride = urduSrt.toByteArray())
        assertTrue("Urdu track stays pending before any donor", emitted.isEmpty())

        // Early soft English donor with completely incompatible cadence (1s interval at 500s) -> must NOT discard pending Urdu
        val mismatchEnSrt = makeEnglishSrt(16, startSec = 500, stepSec = 1)
        val badEnSub = newSubtitleFile("English [Wyzie]", "https://example.invalid/en_bad.srt") {
            this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.WYZIE))
        }
        session.ingestRawSubtitle(badEnSub, rawBytesOverride = mismatchEnSrt.toByteArray())
        assertTrue("Mismatched soft donor must not emit or discard Urdu track", emitted.none { it.lang.startsWith("Urdu") })

        // Later compatible soft English donor (same 6s step, shifted by 1s)
        val matchingEnSrt = makeEnglishSrt(16, startSec = 11, stepSec = 6)
        val goodEnSub = newSubtitleFile("English [SubSource]", "https://example.invalid/en_good.srt") {
            this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBSOURCE))
        }
        session.ingestRawSubtitle(goodEnSub, rawBytesOverride = matchingEnSrt.toByteArray())
        session.completeSession()

        val emittedUrdu = emitted.firstOrNull { it.lang.startsWith("Urdu [SubDL") }
        assertNotNull("Retained pending Urdu track should flush once a compatible later soft donor arrives", emittedUrdu)
        assertNull(
            "Soft catalogue-to-catalogue alignment must keep UNANCHORED and omit X-StreamPlay-Sync header",
            emittedUrdu!!.headers?.get(ProvenanceCodec.HEADER_SYNC)
        )
    }

    @Test
    fun progressiveWindowUpdatesEmitPartialSnapshots() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-progressive").toFile()
        val emitted = CopyOnWriteArrayList<SubtitleFile>()
        val prevProp = System.getProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
        System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, "true")
        try {
            val session = EpisodeSubtitleSession(
                lookupSpec = MediaLookupSpec.Movie(imdbId = "tt7778889", tmdbId = null, titles = listOf("Progressive"), releaseYear = 2024),
                mode = SessionSubtitleMode.URDU,
                casStore = UrduCasStore(tempRoot),
                httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
                progressiveBilingualMode = true,
                bolChalConfig = BolChalConfig(maxConcurrentWindows = 1),
                bolChalTransport = { window, _ ->
                    delay(15L)
                    val m = window.targetCues.associate { it.id to BolChalCueOut(it.id, "ترجمہ نمبر ${it.id.value} مکمل ہوا") }
                    Result.success(m to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                },
                subtitleCallback = { emitted.add(it) },
            )
            // 80 cues => multiple scene windows => window0 + partial:k/N + full track
            val enSrt = makeEnglishSrt(80, startSec = 5, stepSec = 3)
            val enSub = newSubtitleFile("English [SubDL]", "https://example.invalid/en80.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBDL))
            }
            session.ingestRawSubtitle(enSub, rawBytesOverride = enSrt.toByteArray())
            session.completeSession()

            val progressiveHeaders = emitted.mapNotNull { it.headers?.get(ProvenanceCodec.HEADER_PROGRESSIVE) }
            assertTrue("Expected window0 emission in $progressiveHeaders", "window0" in progressiveHeaders)
            assertTrue(
                "Expected partial progressive update snapshot in $progressiveHeaders",
                progressiveHeaders.any { it.startsWith("partial:") }
            )
        } finally {
            if (prevProp == null) {
                System.clearProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
            } else {
                System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, prevProp)
            }
        }
    }

    @Test
    fun sidecarLanguageClassifierAndUnverifiedEpisodeOnlyGateAndZipScoring() = runBlocking {
        assertEquals(SubtitleLanguage.ENGLISH, EpisodeSubtitleSession.classifySidecarLanguageOrNull(newSubtitleFile("English", "https://x/sub.srt")))
        assertEquals(SubtitleLanguage.URDU, EpisodeSubtitleSession.classifySidecarLanguageOrNull(newSubtitleFile("Urdu [CC]", "https://x/ur.vtt")))
        assertNull(EpisodeSubtitleSession.classifySidecarLanguageOrNull(newSubtitleFile("Spanish", "https://x/es.srt")))
        assertNull(EpisodeSubtitleSession.classifySidecarLanguageOrNull(newSubtitleFile("Arabic", "https://x/ar.srt")))

        val spec = MediaLookupSpec.Episode(
            imdbId = "tt1234567",
            tmdbId = null,
            titles = listOf("Anthology"),
            coordinate = EpisodeCoordinate.UnverifiedEpisodeOnly(5),
            seriesPremiereYear = 2024,
            seasonAirYear = 2024,
        )
        val wrongEp = CatalogueCandidateMeta(
            catalogue = CatalogueSourceKey.SUBDL,
            language = SubtitleLanguage.URDU,
            titleOrReleaseText = "Anthology.S01E06.1080p",
        )
        assertTrue(CatalogueCandidateGate.evaluatePreDownload(wrongEp, spec).isFailure)
        val rightEp = CatalogueCandidateMeta(
            catalogue = CatalogueSourceKey.SUBDL,
            language = SubtitleLanguage.URDU,
            titleOrReleaseText = "Anthology.S02E05.1080p",
        )
        assertTrue(CatalogueCandidateGate.evaluatePreDownload(rightEp, spec).isSuccess)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("Anthology.S01E06.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:02,000\nWrong episode\n".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("Anthology.E05.srt"))
            zos.write("1\n00:00:01,000 --> 00:00:02,000\nRight episode 5\n".toByteArray())
            zos.closeEntry()
        }
        val decoded = SubtitleArchiveDecoder.decode(baos.toByteArray(), spec).getOrThrow()
        assertTrue("Expected Episode 5 zip entry to win over Episode 6", decoded.contains("Right episode 5"))
    }

    @Test
    fun urduSubtitleEnginePlaybackScopeIsolatesDownloadsAndStaleCancel() {
        val activePlayback = UrduSubtitleEngine.openPlaybackScope(isDownloadRequest = false)
        val downloadScope = UrduSubtitleEngine.openPlaybackScope(isDownloadRequest = true)
        assertTrue("Download scope must NOT cancel active playback job", activePlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

        val newerPlayback = UrduSubtitleEngine.openPlaybackScope(isDownloadRequest = false)
        assertFalse("Newer playback supersedes older playback", activePlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertTrue(newerPlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

        UrduSubtitleEngine.cancelPlayback(activePlayback)
        assertTrue("Cancelling stale scope must NOT cancel newer active playback", newerPlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

        UrduSubtitleEngine.cancelPlayback(downloadScope)
        assertFalse(downloadScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertTrue("Cancelling download scope must NOT cancel active playback", newerPlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)

        UrduSubtitleEngine.cancelPlayback(newerPlayback)
        assertFalse(newerPlayback.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun prewarmWindowZeroMatchesFullLocalizeKeyAndReusesCachedWindowZero() = runBlocking {
        BolChalWindowCache.clearForTesting()
        val tempRoot = Files.createTempDirectory("pr21-prewarm-w0").toFile()
        try {
            val casStore = UrduCasStore(rootDir = tempRoot)
            val enSrt = makeEnglishSrt(45, startSec = 10, stepSec = 4, prefix = "Window zero parity line")
            val parsedFull = CueDocument.parse(enSrt, SubtitleLanguage.ENGLISH).getOrThrow()

            val transport = CatalogHttpTransport.fromGet { url, _ ->
                when {
                    url.contains("/search?") && url.contains("language=en") ->
                        CatalogHttpResponse(
                            200,
                            """[{"url":"https://wyzie.mock/en0.srt","language":"en","release":"Parity.Movie.2024.1080p.WEB-DL"}]""".toByteArray(),
                            url,
                        )
                    url == "https://wyzie.mock/en0.srt" ->
                        CatalogHttpResponse(200, enSrt.toByteArray(), url)
                    else -> CatalogHttpResponse(404, ByteArray(0), url)
                }
            }
            val sources = SubtitleCatalogSources(
                transport = transport,
                apiKeys = CatalogApiKeys(wyzieApiKey = "test-wyzie-key"),
            )
            val spec = MediaLookupSpec.Movie(
                imdbId = "tt9100001",
                tmdbId = null,
                titles = listOf("Parity Movie"),
                releaseYear = 2024,
            )

            val seenWindowIndices = CopyOnWriteArrayList<Int>()
            val countingBolChal = BolChalTranslationTransport { window, cfg ->
                val cacheKey = BolChalWindowCache.buildWindowKey(window, cfg)
                val expectedIds = window.targetCues.mapTo(HashSet(window.targetCues.size * 2)) { it.id }
                BolChalWindowCache.getOrTranslateSingleFlight(cacheKey, expectedIds) {
                    seenWindowIndices.add(window.windowIndex)
                    val out = window.targetCues.associate { cue ->
                        cue.id to BolChalCueOut(cue.id, "یہ ونڈو ${window.windowIndex} کی لائن ${cue.id.value} ہے۔")
                    }
                    Result.success(out to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                }
            }

            val bundle = casStore.prewarmSubtitlesAsync(
                spec = spec,
                catalogSources = sources,
                bolChalConfig = BolChalConfig(maxConcurrentWindows = 1),
                bolChalTransport = countingBolChal,
                warmBolChalWindows = true,
            )
            assertEquals("Prewarm should warm only window 0", 1, bundle.prewarmedWindowCount)
            assertEquals("Prewarm should invoke translation transport once for window 0", listOf(0), seenWindowIndices.toList())

            seenWindowIndices.clear()
            val fullRes = SceneBolChalLocalizer.localize(
                englishDonorDoc = parsedFull,
                donorProvenance = DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                alignmentProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.UNANCHORED, 0.8, 0, 0, 0),
                config = BolChalConfig(maxConcurrentWindows = 1),
                transport = countingBolChal,
            )
            assertTrue("Expected full localization to succeed, got ${fullRes.exceptionOrNull()}", fullRes.isSuccess)
            assertFalse("Window 0 must be served from cache without re-translating", 0 in seenWindowIndices)
            assertTrue("Expected remaining windows to be translated", seenWindowIndices.isNotEmpty())
        } finally {
            BolChalWindowCache.clearForTesting()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun prewarmBundleAndPlaybackDiscoveryEnforceWantedLanguagesCoverage() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-lang-coverage").toFile()
        try {
            val casStore = UrduCasStore(rootDir = tempRoot)
            val enSrt = makeEnglishSrt(10, startSec = 10, stepSec = 4, prefix = "Coverage dialogue")
            val urSrt = makeUrduSrt(10, startSec = 10, stepSec = 4)
            val specA = MediaLookupSpec.Movie(imdbId = "tt9200001", tmdbId = null, titles = listOf("Coverage A"), releaseYear = 2024)

            val discoveryCalls = AtomicInteger(0)
            val transport = CatalogHttpTransport.fromGet { url, _ ->
                if (url.contains("/search?")) discoveryCalls.incrementAndGet()
                when {
                    url.contains("/search?") && url.contains("language=en") ->
                        CatalogHttpResponse(
                            200,
                            """[{"url":"https://wyzie.mock/cov_en.srt","language":"en","release":"Coverage.A.2024.WEB-DL"}]""".toByteArray(),
                            url,
                        )
                    url.contains("/search?") && url.contains("language=ur") ->
                        CatalogHttpResponse(
                            200,
                            """[{"url":"https://wyzie.mock/cov_ur.srt","language":"ur","release":"Coverage.A.2024.WEB-DL"}]""".toByteArray(),
                            url,
                        )
                    url == "https://wyzie.mock/cov_en.srt" -> CatalogHttpResponse(200, enSrt.toByteArray(), url)
                    url == "https://wyzie.mock/cov_ur.srt" -> CatalogHttpResponse(200, urSrt.toByteArray(), url)
                    else -> CatalogHttpResponse(404, ByteArray(0), url)
                }
            }
            val sources = SubtitleCatalogSources(
                transport = transport,
                apiKeys = CatalogApiKeys(wyzieApiKey = "test-wyzie-key"),
            )

            // 1. Prewarm full bilingual bundle {URDU, ENGLISH}
            val bilingualPrewarm = casStore.prewarmSubtitlesAsync(specA, sources, warmBolChalWindows = false)
            assertEquals(setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH), bilingualPrewarm.wantedLanguages)

            // 2. DEFAULT session {ENGLISH} consumes discovery without downgrading existing {URDU, ENGLISH} bundle
            val defaultEmitted = CopyOnWriteArrayList<SubtitleFile>()
            val defaultSession = EpisodeSubtitleSession(
                lookupSpec = specA,
                mode = SessionSubtitleMode.DEFAULT,
                casStore = casStore,
                catalogSources = sources,
                subtitleCallback = { defaultEmitted.add(it) },
            )
            defaultSession.discoverAndIngestCatalog()
            defaultSession.completeSession()
            val afterDefault = casStore.getPrewarmedBundle(specA)
            assertNotNull(afterDefault)
            assertEquals(
                "ENGLISH-only session must preserve broader {URDU, ENGLISH} prewarm bundle",
                setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH),
                afterDefault!!.wantedLanguages,
            )

            // 3. Narrow {ENGLISH}-only bundle must NOT satisfy an URDU session {URDU, ENGLISH}
            val specB = MediaLookupSpec.Movie(imdbId = "tt9200002", tmdbId = null, titles = listOf("Coverage B"), releaseYear = 2024)
            val candB = CatalogDiscoveredCandidate(
                meta = CatalogueCandidateMeta(CatalogueSourceKey.WYZIE, SubtitleLanguage.ENGLISH, "Coverage.B.2024.WEB-DL"),
                downloadUrl = "https://wyzie.mock/cov_en.srt",
                preDownloadScore = 100,
            )
            casStore.rememberPlaybackDiscovery(
                spec = specB,
                discovery = CatalogDiscoveryResult(
                    admittedCandidates = listOf(candB),
                    rejectedPreDownload = emptyList(),
                ),
                decodedPayloads = mapOf(UrduCasStore.candidateCacheKey(candB) to enSrt),
                wantedLanguages = setOf(SubtitleLanguage.ENGLISH),
            )
            val urduEmitted = CopyOnWriteArrayList<SubtitleFile>()
            discoveryCalls.set(0)
            val urduSession = EpisodeSubtitleSession(
                lookupSpec = specB,
                mode = SessionSubtitleMode.URDU,
                casStore = casStore,
                catalogSources = sources,
                subtitleCallback = { urduEmitted.add(it) },
            )
            urduSession.discoverAndIngestCatalog()
            urduSession.completeSession()
            assertTrue("URDU session must query catalogs instead of reusing ENGLISH-only bundle", discoveryCalls.get() > 0)
            assertTrue("URDU session must emit discovered Urdu track", urduEmitted.any { it.lang.startsWith("Urdu") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun discoverAndIngestCatalogHonorsEnglishMirrorCapAndUrduDonorLimit() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-quota-discovery").toFile()
        try {
            val casStore = UrduCasStore(rootDir = tempRoot)
            val mirrorDialogue = makeEnglishSrt(10, startSec = 10, stepSec = 4, prefix = "Identical mirror text")
            val unique1 = makeEnglishSrt(10, startSec = 10, stepSec = 4, prefix = "Unique alpha dialogue")
            val unique2 = makeEnglishSrt(10, startSec = 10, stepSec = 4, prefix = "Unique beta dialogue")
            val unique3 = makeEnglishSrt(10, startSec = 10, stepSec = 4, prefix = "Unique gamma dialogue")

            val fetchedUrls = CopyOnWriteArrayList<String>()
            val progWyzieJson = """
                [
                  {"url":"https://wyzie.mock/m1.srt","language":"en","release":"Quota.2024.1080p.WEB-DL.m1"},
                  {"url":"https://wyzie.mock/m2.srt","language":"en","release":"Quota.2024.1080p.WEB-DL.m2"},
                  {"url":"https://wyzie.mock/u1.srt","language":"en","release":"Quota.2024.720p.WEB-DL.u1"},
                  {"url":"https://wyzie.mock/u2.srt","language":"en","release":"Quota.2024.720p.WEB-DL.u2"}
                ]
            """.trimIndent()
            val progSubDlJson = """
                {
                  "status": true,
                  "subtitles": [
                    {"lang":"en","release_name":"Quota.2024.1080p.WEB-DL.m3","url":"https://wyzie.mock/m3.srt"},
                    {"lang":"en","release_name":"Quota.2024.1080p.WEB-DL.m4","url":"https://wyzie.mock/m4.srt"},
                    {"lang":"en","release_name":"Quota.2024.480p.HDRip.u3","url":"https://wyzie.mock/u3.srt"}
                  ]
                }
            """.trimIndent()
            val urduWyzieJson = buildString {
                append("[")
                for (i in 1..5) {
                    if (i > 1) append(",")
                    append("""{"url":"https://wyzie.mock/m$i.srt","language":"en","release":"Quota.2024.1080p.WEB-DL.m$i"}""")
                }
                append("]")
            }
            val urduSubDlJson = """
                {
                  "status": true,
                  "subtitles": [
                    {"lang":"en","release_name":"Quota.2024.1080p.WEB-DL.m6","url":"https://wyzie.mock/m6.srt"},
                    {"lang":"en","release_name":"Quota.2024.720p.WEB-DL.u1","url":"https://wyzie.mock/u1.srt"},
                    {"lang":"en","release_name":"Quota.2024.480p.HDRip.u2","url":"https://wyzie.mock/u2.srt"},
                    {"lang":"en","release_name":"Quota.2024.480p.HDRip.u3","url":"https://wyzie.mock/u3.srt"}
                  ]
                }
            """.trimIndent()
            val transport = CatalogHttpTransport.fromGet { url, _ ->
                fetchedUrls.add(url)
                when {
                    url.contains("subdl.com/api/v1/subtitles") && url.contains("tt9300001") -> {
                        delay(120L)
                        CatalogHttpResponse(200, progSubDlJson.toByteArray(), url)
                    }
                    url.contains("subdl.com/api/v1/subtitles") && url.contains("tt9300002") ->
                        CatalogHttpResponse(200, urduSubDlJson.toByteArray(), url)
                    url.contains("/search?") && url.contains("language=en") && url.contains("tt9300001") ->
                        CatalogHttpResponse(200, progWyzieJson.toByteArray(), url)
                    url.contains("/search?") && url.contains("language=en") && url.contains("tt9300002") ->
                        CatalogHttpResponse(200, urduWyzieJson.toByteArray(), url)
                    url.contains("/search?") -> CatalogHttpResponse(200, "[]".toByteArray(), url)
                    url.startsWith("https://wyzie.mock/m") -> CatalogHttpResponse(200, mirrorDialogue.toByteArray(), url)
                    url == "https://wyzie.mock/u1.srt" -> CatalogHttpResponse(200, unique1.toByteArray(), url)
                    url == "https://wyzie.mock/u2.srt" -> CatalogHttpResponse(200, unique2.toByteArray(), url)
                    url == "https://wyzie.mock/u3.srt" -> CatalogHttpResponse(200, unique3.toByteArray(), url)
                    else -> CatalogHttpResponse(404, ByteArray(0), url)
                }
            }
            val sources = SubtitleCatalogSources(
                transport = transport,
                apiKeys = CatalogApiKeys(wyzieApiKey = "test-wyzie-key", subDlApiKey = "test-subdl-key"),
            )

            // Part A: Progressive BILINGUAL_DOWNLOAD with targetEnglishCatalogCount = 3 stops at 3 unique (1 mirror + u1 + u2), NOT fetching u3
            val emittedProg = CopyOnWriteArrayList<SubtitleFile>()
            val specProg = MediaLookupSpec.Movie(imdbId = "tt9300001", tmdbId = null, titles = listOf("Quota Prog"), releaseYear = 2024)
            val progSession = EpisodeSubtitleSession(
                lookupSpec = specProg,
                mode = SessionSubtitleMode.BILINGUAL_DOWNLOAD,
                casStore = casStore,
                catalogSources = sources,
                targetUrduDiversityCount = 0,
                targetEnglishCatalogCount = 3,
                progressiveBilingualMode = true,
                subtitleCallback = { emittedProg.add(it) },
            )
            progSession.discoverAndIngestCatalog(enabledSources = setOf(CatalogueSourceKey.WYZIE, CatalogueSourceKey.SUBDL))
            progSession.completeSession()
            assertEquals("Expected exactly 3 unique English tracks", 3, emittedProg.count { it.lang.startsWith("English") })
            assertFalse("u3 should not be fetched after reaching 3 unique English tracks", "https://wyzie.mock/u3.srt" in fetchedUrls)

            // Part B: Non-progressive URDU mode caps distinct non-fallback catalogue donors at 2 without stacking mirror duplicates
            fetchedUrls.clear()
            val emittedUrdu = CopyOnWriteArrayList<SubtitleFile>()
            val specUrdu = MediaLookupSpec.Movie(imdbId = "tt9300002", tmdbId = null, titles = listOf("Quota Urdu"), releaseYear = 2024)
            val urduSession = EpisodeSubtitleSession(
                lookupSpec = specUrdu,
                mode = SessionSubtitleMode.URDU,
                casStore = casStore,
                catalogSources = sources,
                targetUrduDiversityCount = 1,
                progressiveBilingualMode = false,
                subtitleCallback = { emittedUrdu.add(it) },
            )
            urduSession.discoverAndIngestCatalog(enabledSources = setOf(CatalogueSourceKey.WYZIE, CatalogueSourceKey.SUBDL))
            val donors = urduSession.rankedEnglishDonorsSnapshot()
            assertEquals("Non-progressive URDU mode must cap catalogue donors at 2 distinct entries (got ${donors.size})", 2, donors.size)
            assertFalse("u2 should not be fetched after 2 distinct donors (mirror group + u1)", "https://wyzie.mock/u2.srt" in fetchedUrls)
            assertFalse("u3 should not be fetched in URDU mode", "https://wyzie.mock/u3.srt" in fetchedUrls)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun evictedCatalogueDonorRejectsWindowZeroProgressiveEmission() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-w0-evict").toFile()
        val prevProp = System.getProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
        System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, "true")
        try {
            val emitted = CopyOnWriteArrayList<SubtitleFile>()
            val inTranslate = CompletableDeferred<Unit>()
            val allowFinish = CompletableDeferred<Unit>()
            val session = EpisodeSubtitleSession(
                lookupSpec = MediaLookupSpec.Movie(imdbId = "tt9400001", tmdbId = null, titles = listOf("W0 Evict"), releaseYear = 2024),
                mode = SessionSubtitleMode.URDU,
                casStore = UrduCasStore(tempRoot),
                httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
                multiProviderVideoSession = false,
                progressiveBilingualMode = true,
                bolChalConfig = BolChalConfig(maxConcurrentWindows = 1),
                bolChalTransport = { window, _ ->
                    inTranslate.complete(Unit)
                    allowFinish.await()
                    val m = window.targetCues.associate { it.id to BolChalCueOut(it.id, "یہ اردو ترجمہ ہے نمبر ${it.id.value}") }
                    Result.success(m to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                },
                subtitleCallback = { emitted.add(it) },
            )

            val enSrt = makeEnglishSrt(25, startSec = 10, stepSec = 4)
            val catDonor = newSubtitleFile("English [Wyzie]", "https://example.invalid/cat_w0.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.WYZIE))
            }
            session.ingestRawSubtitle(catDonor, rawBytesOverride = enSrt.toByteArray())

            val completeJob = async { session.completeSession() }
            inTranslate.await()

            // Evict the buffered CatalogueDonor via mismatched speech timeline BEFORE window-0 finishes
            val farTimeline = SpeechActivityTimeline(
                speechSpans = (0 until 10).map { i -> SpeechSpan(1_800_000L + i * 4_000L, 1_802_000L + i * 4_000L, 1.0f) },
                observedWindows = listOf(ObservedWindow(1_790_000L, 1_900_000L)),
                mediaDurationMs = 2_000_000L,
            )
            session.attachSpeechTimeline(farTimeline)
            allowFinish.complete(Unit)

            val report = withTimeout(5_000L) { completeJob.await() }
            assertTrue("Evicted donor must NOT emit any window-0 or final Urdu callback", emitted.none { it.lang.startsWith("Urdu") })
            assertTrue("Evicted donor must NOT admit any Urdu candidate", report.emittedUrduCandidates.isEmpty())
        } finally {
            if (prevProp == null) System.clearProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
            else System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, prevProp)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun redirectStripsCredentialHeadersAcrossOriginsPreservesSameOrigin() = runBlocking {
        val sameHostCaptured = java.util.concurrent.ConcurrentHashMap<String, String>()
        val crossOriginGetCaptured = java.util.concurrent.ConcurrentHashMap<String, String>()
        val crossOriginPostCaptured = java.util.concurrent.ConcurrentHashMap<String, String>()

        val serverB = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        serverB.createContext("/final-get") { ex ->
            ex.requestHeaders.forEach { (k, v) -> crossOriginGetCaptured[k.lowercase()] = v.firstOrNull().orEmpty() }
            val body = "cross-get-ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        serverB.createContext("/final-post") { ex ->
            ex.requestHeaders.forEach { (k, v) -> crossOriginPostCaptured[k.lowercase()] = v.firstOrNull().orEmpty() }
            val body = "cross-post-ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        serverB.start()

        val serverA = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val portB = serverB.address.port
        serverA.createContext("/same") { ex ->
            val portA = serverA.address.port
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portA/same-final")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        serverA.createContext("/same-final") { ex ->
            ex.requestHeaders.forEach { (k, v) -> sameHostCaptured[k.lowercase()] = v.firstOrNull().orEmpty() }
            val body = "same-ok".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        serverA.createContext("/cross-get") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portB/final-get")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        serverA.createContext("/cross-post") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portB/final-post")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        serverA.start()

        try {
            val portA = serverA.address.port
            val transport = DefaultCatalogHttpTransport(allowPrivateNetworks = true)
            val headers = mapOf(
                "X-API-Key" to "api-secret-1",
                "api-key" to "api-secret-2",
                "Authorization" to "Bearer secret-tok",
                "Cookie" to "session=abc",
                "User-Agent" to "Pr21RedirectTest/1.0",
            )

            val sameRes = transport.get("http://127.0.0.1:$portA/same", headers)
            assertTrue(sameRes.isSuccess)
            assertEquals("api-secret-1", sameHostCaptured["x-api-key"])
            assertEquals("Bearer secret-tok", sameHostCaptured["authorization"])
            assertEquals("session=abc", sameHostCaptured["cookie"])

            val crossGetRes = transport.get("http://127.0.0.1:$portA/cross-get", headers)
            assertTrue(crossGetRes.isSuccess)
            assertNull("X-API-Key must be stripped on cross-origin GET redirect", crossOriginGetCaptured["x-api-key"])
            assertNull("api-key must be stripped on cross-origin GET redirect", crossOriginGetCaptured["api-key"])
            assertNull("Authorization must be stripped on cross-origin GET redirect", crossOriginGetCaptured["authorization"])
            assertNull("Cookie must be stripped on cross-origin GET redirect", crossOriginGetCaptured["cookie"])
            assertEquals("Pr21RedirectTest/1.0", crossOriginGetCaptured["user-agent"])

            val crossPostRes = transport.postForm("http://127.0.0.1:$portA/cross-post", mapOf("k" to "v"), headers)
            assertTrue(crossPostRes.isSuccess)
            assertNull("X-API-Key must be stripped on cross-origin POST redirect", crossOriginPostCaptured["x-api-key"])
            assertNull("api-key must be stripped on cross-origin POST redirect", crossOriginPostCaptured["api-key"])
            assertNull("Authorization must be stripped on cross-origin POST redirect", crossOriginPostCaptured["authorization"])
            assertNull("Cookie must be stripped on cross-origin POST redirect", crossOriginPostCaptured["cookie"])
        } finally {
            serverA.stop(0)
            serverB.stop(0)
        }
    }

    @Test
    fun corruptSidecarReturnsArchiveDecodeFailedWhileBadSmtReturnsWrongScriptOrBrokenSmt() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-decode-class").toFile()
        try {
            val session = EpisodeSubtitleSession(
                lookupSpec = MediaLookupSpec.Movie(imdbId = "tt9500001", tmdbId = null, titles = listOf("Decode Class"), releaseYear = 2024),
                mode = SessionSubtitleMode.URDU,
                casStore = UrduCasStore(tempRoot),
                subtitleCallback = {},
            )
            val corruptZip = newSubtitleFile("English", "https://example.invalid/bad.zip")
            val decodeOutcome = session.ingestRawSubtitle(corruptZip, rawBytesOverride = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))
            assertTrue(
                "Corrupt archive must return Rejected.ArchiveDecodeFailed, got $decodeOutcome",
                decodeOutcome is VerifiedSubtitleCandidate.Rejected.ArchiveDecodeFailed,
            )

            val brokenUrdu = newSubtitleFile("Urdu", "https://example.invalid/bad_ur.srt")
            val validBytes = makeEnglishSrt(10, prefix = "Not Urdu at all").toByteArray()
            val smtOutcome = session.ingestRawSubtitle(brokenUrdu, rawBytesOverride = validBytes)
            assertTrue(
                "Non-Urdu text under Urdu label must return Rejected.WrongScriptOrBrokenSmt, got $smtOutcome",
                smtOutcome is VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt,
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun compactRewritePreservesFirstPersonPluralMeaning() {
        val doc = CueDocument.fromCues(
            SubtitleLanguage.ENGLISH,
            listOf(CueEntry(CueId(1), CueSpan(10_000L, 12_500L), "We have to get out of here right now.")),
        )
        var captured = ""
        runBlocking {
            val localized = SceneBolChalLocalizer.localize(
                englishDonorDoc = doc,
                donorProvenance = DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                alignmentProof = AlignmentProof(SubtitleTimeWarp.Identity, AlignmentAnchorKind.UNANCHORED, 0.8, 0, 0, 0),
                config = BolChalConfig(editionProfile = BolChalEditionProfile.COMPACT_FAST),
                transport = { window, _ ->
                    Result.success(
                        window.targetCues.associate { it.id to BolChalCueOut(it.id, "ہمیں یہاں سے نکلنا ہو گا فوراً") } to
                            LocalizationTierKey.KEYLESS_BOLCHAL_NMT
                    )
                },
            ).getOrThrow()
            captured = localized.document.cues.single().text
        }
        assertFalse("Compact lexical rewrite must not convert 1st-person plural into 2nd-person imperative: $captured", captured.contains("نکلو"))
        assertTrue("Expected obligation rewrite 'یہاں سے نکلنا ہے' in: $captured", captured.contains("یہاں سے نکلنا ہے"))
    }

    @Test
    fun mirrorReplacementDuringTranslationPreservesBufferIdentityAndCommitsWinner() = runBlocking {
        val tempRoot = Files.createTempDirectory("pr21-mirror-inflight").toFile()
        val prevProp = System.getProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
        System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, "true")
        try {
            val emitted = CopyOnWriteArrayList<SubtitleFile>()
            val inTranslate = CompletableDeferred<Unit>()
            val allowFinish = CompletableDeferred<Unit>()
            val session = EpisodeSubtitleSession(
                lookupSpec = MediaLookupSpec.Movie(imdbId = "tt9600001", tmdbId = null, titles = listOf("Mirror Replace"), releaseYear = 2024),
                mode = SessionSubtitleMode.URDU,
                casStore = UrduCasStore(tempRoot),
                httpTransport = DefaultCatalogHttpTransport(allowPrivateNetworks = true),
                targetUrduDiversityCount = 1,
                progressiveBilingualMode = true,
                bolChalConfig = BolChalConfig(maxConcurrentWindows = 1),
                bolChalTransport = { window, _ ->
                    inTranslate.complete(Unit)
                    allowFinish.await()
                    val m = window.targetCues.associate { it.id to BolChalCueOut(it.id, "یہ اردو ترجمہ ہے نمبر ${it.id.value}") }
                    Result.success(m to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
                },
                subtitleCallback = { emitted.add(it) },
            )

            val enSrt = makeEnglishSrt(25, startSec = 10, stepSec = 4)
            // 1. Ingest lower-tier mirror (SubtitleCat, non-orig tier 1)
            val lowMirror = newSubtitleFile("English [SubtitleCat]", "https://example.invalid/mirror_subcat-en.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.SUBTITLECAT))
            }
            session.ingestRawSubtitle(lowMirror, rawBytesOverride = enSrt.toByteArray())

            // 2. Start translation and pause before window 0 finishes
            val completeJob = async { session.completeSession() }
            inTranslate.await()

            // 3. Ingest higher-ranked mirror (Wyzie, tier 2) with identical cues while translation is running
            val highMirror = newSubtitleFile("English [Wyzie]", "https://example.invalid/mirror_wyzie.srt") {
                this.headers = ProvenanceCodec.encodeHeaders(ProvenanceIdentity.HumanCatalogue(CatalogueSourceKey.WYZIE))
            }
            session.ingestRawSubtitle(highMirror, rawBytesOverride = enSrt.toByteArray())
            allowFinish.complete(Unit)

            val report = withTimeout(5_000L) { completeJob.await() }
            assertEquals("Replaced mirror donor must still count as 1 entry", 1, session.rankedEnglishDonorsSnapshot().size)
            assertTrue(
                "Window-0 Instant track must NOT be dropped after mirror replacement",
                emitted.any { it.headers?.get(ProvenanceCodec.HEADER_PROGRESSIVE) == "window0" },
            )
            assertEquals("Final localized Urdu track must still commit", 1, report.emittedUrduCandidates.size)
            assertEquals(
                "Committed localization must attribute the winning higher-ranked mirror",
                DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE),
                report.localizedDonorUsed,
            )
        } finally {
            if (prevProp == null) System.clearProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT)
            else System.setProperty(SubtitleModeBridge.PROP_PROGRESSIVE_VTT, prevProp)
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun catalogHttpTransportBlocksDnsRebindingAtConnectionTimeAcrossGetAndPost() = runBlocking {
        val hitCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/rebind") { ex ->
            hitCount.incrementAndGet()
            val bytes = "1\n00:00:01,000 --> 00:00:02,000\nSecret\n".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val dnsCalls = AtomicInteger(0)
            val publicIp = InetAddress.getByAddress(
                "rebind.example.test",
                byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()),
            )
            val rebindingTransport = DefaultCatalogHttpTransport(
                allowPrivateNetworks = false,
                dnsLookup = { host ->
                    if (host == "rebind.example.test" && dnsCalls.getAndIncrement() % 2 == 0) {
                        // Preflight validateDestinationUrl sees a public IP
                        listOf(publicIp)
                    } else {
                        // Socket connection-time DNS resolution rebinds to 127.0.0.1
                        listOf(InetAddress.getLoopbackAddress())
                    }
                },
            )

            val targetUrl = "http://rebind.example.test:${server.address.port}/rebind"
            val getRes = rebindingTransport.get(targetUrl)
            assertTrue("GET must block connection-time DNS rebinding, got $getRes", getRes.isFailure)
            assertTrue(getRes.exceptionOrNull() is SecurityException)

            val formRes = rebindingTransport.postForm(targetUrl, mapOf("q" to "test"))
            assertTrue("postForm must block connection-time DNS rebinding, got $formRes", formRes.isFailure)
            assertTrue(formRes.exceptionOrNull() is SecurityException)

            val jsonRes = rebindingTransport.postJson(targetUrl, """{"q":"test"}""")
            assertTrue("postJson must block connection-time DNS rebinding, got $jsonRes", jsonRes.isFailure)
            assertTrue(jsonRes.exceptionOrNull() is SecurityException)

            assertEquals("No HTTP request may ever reach the local server via DNS rebinding", 0, hitCount.get())
        } finally {
            server.stop(0)
        }
    }
}

