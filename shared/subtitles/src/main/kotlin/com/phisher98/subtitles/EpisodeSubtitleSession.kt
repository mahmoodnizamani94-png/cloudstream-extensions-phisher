package com.phisher98.subtitles

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class CasSubtitleFormat(val extension: String) {
    WEBVTT("vtt"),
    SRT("srt"),
}

data class CasStoredSubtitle(
    val file: File,
    val fileUri: String,
    val contentSha256: ContentSha256,
    val cueCount: Int,
    val appliedWarp: SubtitleTimeWarp,
)

data class PrewarmMetadataSummary(
    val lookupKey: String,
    val discoveredUrduCount: Int,
    val discoveredEnglishDonorCount: Int,
    val topUrduCatalogues: List<CatalogueSourceKey>,
    val topEnglishCatalogues: List<CatalogueSourceKey>,
)

class UrduCasStore(
    private val rootDir: File = File(System.getProperty("java.io.tmpdir"), "cs_urdu_cas_v2"),
    val maxActiveSlots: Int = 256,
    val maxPrewarmMetadataSlots: Int = 128,
    private val pinLeaseTtlMs: Long = DEFAULT_PIN_LEASE_TTL_MS,
) {
    init {
        require(maxActiveSlots >= 1) { "maxActiveSlots must be >= 1" }
        require(maxPrewarmMetadataSlots >= 1) { "maxPrewarmMetadataSlots must be >= 1" }
        require(pinLeaseTtlMs >= 1_000L) { "pinLeaseTtlMs must be >= 1000" }
        require(!rootDir.name.contains("superstream", ignoreCase = true)) {
            "UrduCasStore directory name cannot contain provider-specific 'superstream'"
        }
    }

    private val canonicalRootPath: String =
        runCatching { rootDir.canonicalPath }.getOrElse { rootDir.absolutePath }

    private val sharedState: SharedRootState =
        SHARED_ROOT_STATES[canonicalRootPath]
            ?: SharedRootState().let { fresh ->
                SHARED_ROOT_STATES.putIfAbsent(canonicalRootPath, fresh) ?: fresh
            }

    fun writeAlignedSubtitle(
        doc: CueDocument,
        warp: SubtitleTimeWarp = SubtitleTimeWarp.Identity,
        videoDurationMs: Long? = null,
        format: CasSubtitleFormat = CasSubtitleFormat.WEBVTT,
        pinActive: Boolean = false,
    ): CasStoredSubtitle {
        val baked = when (format) {
            CasSubtitleFormat.WEBVTT -> WebVttBaker.bake(doc, warp, videoDurationMs)
            CasSubtitleFormat.SRT -> WebVttBaker.bakeSrt(doc, warp, videoDurationMs, includeUtf8Bom = true)
        }
        val serializedText = baked.utf8Content
        val expectedBytes = serializedText.toByteArray(StandardCharsets.UTF_8)
        val cueCount = baked.cueCount
        val contentSha = baked.bakedContentSha256

        val shaPrefix24 = contentSha.hex.take(24)
        val fileName = "${CAS_FILE_PREFIX}${shaPrefix24}.${format.extension}"
        check(!fileName.contains("superstream", ignoreCase = true))

        val targetFile = synchronized(sharedState.lock) {
            val nowMs = System.currentTimeMillis()
            pruneExpiredPinsLocked(nowMs)
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            val out = File(rootDir, fileName)
            val existingMatchesSha = out.isFile &&
                out.length() == expectedBytes.size.toLong() &&
                runCatching { ContentSha256.sha256Hex(out.readBytes()) == contentSha.hex }.getOrDefault(false)

            if (!existingMatchesSha) {
                val tmp = File(rootDir, ".tmp_${UUID.randomUUID()}_$fileName")
                try {
                    tmp.writeBytes(expectedBytes)
                    if (!tmp.renameTo(out)) {
                        tmp.copyTo(out, overwrite = true)
                        tmp.delete()
                    }
                    val actualReadbackSha = ContentSha256.sha256Hex(out.readBytes())
                    check(actualReadbackSha == contentSha.hex) {
                        "UrduCasStore SHA-256 readback verification failed for $fileName: expected ${contentSha.hex}, got $actualReadbackSha"
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } else {
                out.setLastModified(nowMs)
            }
            if (pinActive) {
                sharedState.pinExpiryMsByName[fileName] = nowMs + pinLeaseTtlMs
            }
            evictExcessActiveFilesLocked(protectedName = fileName, nowMs = nowMs)
            out
        }

        return CasStoredSubtitle(
            file = targetFile,
            fileUri = targetFile.toURI().toString(),
            contentSha256 = contentSha,
            cueCount = cueCount,
            appliedWarp = warp,
        )
    }

    fun unpin(fileName: String) {
        synchronized(sharedState.lock) {
            sharedState.pinExpiryMsByName.remove(fileName)
        }
    }

    fun readOwnedCasFileBytesOrNull(fileUrl: String): ByteArray? {
        if (!fileUrl.startsWith("file:", ignoreCase = true)) return null
        val candidate = runCatching { File(URI(fileUrl.trim())) }.getOrElse {
            val rawPath = fileUrl.trim().removePrefix("file://").removePrefix("file:")
            File(rawPath)
        }
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val canonicalRoot = runCatching { rootDir.canonicalFile }.getOrElse { rootDir.absoluteFile }
        if (canonicalCandidate.parentFile != canonicalRoot) return null
        if (!canonicalCandidate.isFile) return null
        val name = canonicalCandidate.name
        if (!name.startsWith(CAS_FILE_PREFIX) || (!name.endsWith(".vtt") && !name.endsWith(".srt"))) {
            return null
        }
        val expectedShaPrefix = name.removePrefix(CAS_FILE_PREFIX).substringBeforeLast('.')
        if (expectedShaPrefix.length != 24 ||
            !expectedShaPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            return null
        }
        val len = canonicalCandidate.length()
        if (len <= 0L || len > SubtitleArchiveDecoder.MAX_DECODED_BYTES.toLong()) return null
        return synchronized(sharedState.lock) {
            val bytes = runCatching { canonicalCandidate.readBytes() }.getOrNull() ?: return@synchronized null
            if (!ContentSha256.sha256Hex(bytes).startsWith(expectedShaPrefix, ignoreCase = true)) {
                return@synchronized null
            }
            bytes
        }
    }

    fun listActiveSubtitleFiles(): List<File> = synchronized(sharedState.lock) {
        rootDir.listFiles { f ->
            f.isFile && f.name.startsWith(CAS_FILE_PREFIX) &&
                (f.name.endsWith(".vtt") || f.name.endsWith(".srt"))
        }?.toList().orEmpty()
    }

    suspend fun prewarmMetadataAsync(
        spec: MediaLookupSpec,
        catalogSources: SubtitleCatalogSources,
    ): PrewarmMetadataSummary {
        val key = buildLookupKey(spec)
        synchronized(sharedState.lock) {
            sharedState.prewarmMetadataLru[key]?.let { return it }
        }
        val discovery = catalogSources.discoverCandidates(
            spec = spec,
            languages = setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH),
        )
        val urCands = discovery.candidatesFor(SubtitleLanguage.URDU)
        val enCands = discovery.candidatesFor(SubtitleLanguage.ENGLISH)
        val summary = PrewarmMetadataSummary(
            lookupKey = key,
            discoveredUrduCount = urCands.size,
            discoveredEnglishDonorCount = enCands.size,
            topUrduCatalogues = urCands.map { it.catalogue }.distinct(),
            topEnglishCatalogues = enCands.map { it.catalogue }.distinct(),
        )
        synchronized(sharedState.lock) {
            sharedState.prewarmMetadataLru[key] = summary
            while (sharedState.prewarmMetadataLru.size > maxPrewarmMetadataSlots) {
                val eldestKey = sharedState.prewarmMetadataLru.keys.firstOrNull() ?: break
                sharedState.prewarmMetadataLru.remove(eldestKey)
            }
        }
        return summary
    }

    fun getPrewarmedMetadata(spec: MediaLookupSpec): PrewarmMetadataSummary? = synchronized(sharedState.lock) {
        sharedState.prewarmMetadataLru[buildLookupKey(spec)]
    }

    private fun pruneExpiredPinsLocked(nowMs: Long) {
        val iter = sharedState.pinExpiryMsByName.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value <= nowMs) {
                iter.remove()
            }
        }
    }

    private fun evictExcessActiveFilesLocked(protectedName: String, nowMs: Long) {
        pruneExpiredPinsLocked(nowMs)
        val activeFiles = rootDir.listFiles { f ->
            f.isFile && f.name.startsWith(CAS_FILE_PREFIX) &&
                (f.name.endsWith(".vtt") || f.name.endsWith(".srt"))
        }?.toMutableList() ?: return

        if (activeFiles.size <= maxActiveSlots) return
        activeFiles.sortBy { it.lastModified() }
        var remaining = activeFiles.size
        for (candidate in activeFiles) {
            if (remaining <= maxActiveSlots) break
            val expiry = sharedState.pinExpiryMsByName[candidate.name]
            val isPinned = expiry != null && expiry > nowMs
            if (candidate.name == protectedName || isPinned) continue
            if (candidate.delete()) {
                remaining--
            }
        }
    }

    private fun buildLookupKey(spec: MediaLookupSpec): String = when (spec) {
        is MediaLookupSpec.Movie ->
            "movie|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|${spec.titles.firstOrNull().orEmpty()}|${spec.releaseYear ?: 0}"
        is MediaLookupSpec.Episode -> {
            val coordKey = when (val c = spec.coordinate) {
                is EpisodeCoordinate.VerifiedSeasonEpisode -> "S${c.season}E${c.episode}"
                is EpisodeCoordinate.UnverifiedAnimeCour -> "COUR:${c.courTitles.firstOrNull().orEmpty()}:E${c.episodeInCour}"
            }
            "tv|${spec.imdbId.orEmpty()}|${spec.tmdbId ?: 0}|$coordKey|${spec.seriesPremiereYear ?: 0}"
        }
    }

    private class SharedRootState {
        val lock = Any()
        val pinExpiryMsByName = HashMap<String, Long>()
        val prewarmMetadataLru = LinkedHashMap<String, PrewarmMetadataSummary>(32, 0.75f, true)
    }

    companion object {
        const val CAS_FILE_PREFIX = "cs_urdu_v2_"
        const val DEFAULT_PIN_LEASE_TTL_MS = 6L * 3_600_000L
        private val SHARED_ROOT_STATES = ConcurrentHashMap<String, SharedRootState>()
    }
}

private val NEXT_BUFFERED_DONOR_ENTRY_ID = AtomicLong(0L)

data class BufferedEnglishDonor(
    val donor: DonorProvenance,
    val document: CueDocument,
    val alignment: AlignmentProof,
    val priorityTier: Int,
    val intraTierScore: Int,
    val isSubtitleCatOrigFallback: Boolean = false,
    val isForeignSidecar: Boolean = false,
    val sourceSubtitleFile: SubtitleFile? = null,
    internal val reconciledTimeline: SpeechActivityTimeline? = null,
    internal val bufferEntryId: Long = NEXT_BUFFERED_DONOR_ENTRY_ID.incrementAndGet(),
)

data class SessionCompletionReport(
    val emittedUrduCandidates: List<VerifiedSubtitleCandidate.Admitted>,
    val rejectedCandidates: List<VerifiedSubtitleCandidate.Rejected>,
    val emittedProviderAnchors: Int,
    val localizedDonorUsed: DonorProvenance?,
    val storedCasFiles: List<CasStoredSubtitle>,
)

class EpisodeSubtitleSession(
    val lookupSpec: MediaLookupSpec,
    val mode: SessionSubtitleMode,
    val casStore: UrduCasStore = UrduCasStore(),
    private val httpTransport: CatalogHttpTransport = DefaultCatalogHttpTransport(),
    val catalogSources: SubtitleCatalogSources = SubtitleCatalogSources(transport = httpTransport),
    val bolChalConfig: BolChalConfig = BolChalConfig(),
    val bolChalTransport: BolChalTranslationTransport = DefaultBolChalTranslationTransport(),
    val casFormat: CasSubtitleFormat = CasSubtitleFormat.WEBVTT,
    val targetUrduDiversityCount: Int = 2,
    val targetEnglishCatalogCount: Int = 5,
    val multiProviderVideoSession: Boolean = false,
    initialSpeechTimeline: SpeechActivityTimeline? = null,
    private val callbackIdentityToken: Any? = null,
    private val subtitleCallback: (SubtitleFile) -> Unit,
) {
    private val sessionMutex = Mutex()

    private val admittedUrduTracks = ArrayList<VerifiedSubtitleCandidate.Admitted>()
    private val rejectedTracks = ArrayList<VerifiedSubtitleCandidate.Rejected>()
    private val pendingNativeUrdu = ArrayList<PendingNativeUrduEntry>()
    private val pendingCatalogueUrdu = ArrayList<PendingCatalogueUrduEntry>()
    private val englishDonors = ArrayList<BufferedEnglishDonor>()
    private val seenUrduDiversityAndContent = HashSet<Pair<String, ContentSha256>>()
    private val seenEnglishCatalogSources = HashSet<CatalogueSourceKey>()
    private val seenEnglishCatalogContent = HashSet<ContentSha256>()
    private val seenProviderAnchorContent = HashSet<Pair<StreamProviderKey, ContentSha256>>()
    private val storedCasOutputs = ArrayList<CasStoredSubtitle>()
    private val activeVideoProviders = LinkedHashSet<StreamProviderKey>()
    private val nonForeignStreamProviders = LinkedHashSet<StreamProviderKey>()
    private var speechTimeline: SpeechActivityTimeline? = initialSpeechTimeline
    private var emittedAnchorCount = 0
    private var localizedDonorSelected: DonorProvenance? = null

    private data class PendingNativeUrduEntry(
        val candidate: VerifiedSubtitleCandidate.StreamSyncedNative,
        val isForeignSidecar: Boolean,
    )

    private data class PendingCatalogueUrduEntry(
        val candidate: VerifiedSubtitleCandidate.AlignedHumanCatalogue,
        val meta: CatalogueCandidateMeta?,
    )

    fun registerActiveVideoProvider(provider: StreamProviderKey) {
        synchronized(activeVideoProviders) {
            activeVideoProviders.add(provider)
        }
    }

    fun registerActiveVideoProviderHint(rawHint: String?) {
        StreamProviderKey.fromTextOrHost(rawHint)?.let { registerActiveVideoProvider(it) }
    }

    fun snapshotActiveVideoProviders(): Set<StreamProviderKey> =
        synchronized(activeVideoProviders) { activeVideoProviders.toSet() }

    suspend fun attachSpeechTimeline(timeline: SpeechActivityTimeline) {
        sessionMutex.withLock {
            speechTimeline = timeline
            reconcileStreamDonorProofsLocked()
            if (!multiProviderVideoSession && bestStreamAnchorDonorLocked() == null && pendingCatalogueUrdu.isNotEmpty()) {
                flushPendingCatalogueUrduLocked()
            }
        }
    }

    suspend fun ingestRawSubtitle(
        sub: SubtitleFile,
        rawSourceHint: String? = null,
        rawBytesOverride: ByteArray? = null,
    ): VerifiedSubtitleCandidate? {
        val headers = sub.headers.orEmpty()
        headers.entries.firstOrNull { it.key.equals(HEADER_SPEECH_TIMELINE_B64, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { b64 ->
                SpeechTimelineCodec.decodeCompact(b64).getOrNull()?.let { decodedTimeline ->
                    sessionMutex.withLock {
                        if (speechTimeline == null) {
                            speechTimeline = decodedTimeline
                            reconcileStreamDonorProofsLocked()
                        }
                    }
                }
            }

        val resolvedProvenance = resolveRawSubtitleProvenance(sub, rawSourceHint, headers)
        val isForeignSidecar = isForeignRawSidecar(rawSourceHint, headers, resolvedProvenance)
        val detectedLanguage = resolveSubtitleLanguage(sub, headers)
            ?: return null

        val rawBytes = when {
            rawBytesOverride != null -> rawBytesOverride
            sub.url.startsWith("file:", ignoreCase = true) -> casStore.readOwnedCasFileBytesOrNull(sub.url)
            else -> httpTransport.get(sub.url, headers).getOrNull()?.bodyBytes
        } ?: return null

        val decodedText = SubtitleArchiveDecoder.decode(rawBytes, lookupSpec).getOrElse { err ->
            val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "archive_decode_failed")
            sessionMutex.withLock { rejectedTracks.add(rej) }
            return rej
        }

        return sessionMutex.withLock {
            when (detectedLanguage) {
                SubtitleLanguage.URDU -> ingestDecodedUrduLocked(
                    decodedText = decodedText,
                    provenance = resolvedProvenance,
                    meta = null,
                    isForeignSidecar = isForeignSidecar,
                )
                SubtitleLanguage.ENGLISH -> ingestDecodedEnglishLocked(
                    decodedText = decodedText,
                    provenance = resolvedProvenance,
                    sourceSub = sub,
                    preDownloadScore = 0,
                    isSubtitleCatOrigFallback = isSubtitleCatOrigHeaderOrUrl(headers, sub.url),
                    isForeignSidecar = isForeignSidecar,
                )
            }
        }
    }

    suspend fun ingestCatalogCandidate(
        discovered: CatalogDiscoveredCandidate,
        rawBytesOverride: ByteArray? = null,
    ): VerifiedSubtitleCandidate {
        val gateResult = CatalogueCandidateGate.evaluatePreDownload(discovered.meta, lookupSpec)
        if (gateResult.isFailure) {
            val rej = (gateResult.exceptionOrNull() as? CandidateGateRejectedException)?.rejection
                ?: VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader("catalog_gate_rejected")
            sessionMutex.withLock { rejectedTracks.add(rej) }
            return rej
        }

        val rawBytes = rawBytesOverride
            ?: catalogSources.downloadPayload(discovered).getOrElse { err ->
                val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "download_failed")
                sessionMutex.withLock { rejectedTracks.add(rej) }
                return rej
            }

        val decodedText = SubtitleArchiveDecoder.decode(
            rawBytes = rawBytes,
            lookupSpec = lookupSpec,
            preferredFileHint = discovered.preferredZipEntryHint,
        ).getOrElse { err ->
            val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "decode_failed")
            sessionMutex.withLock { rejectedTracks.add(rej) }
            return rej
        }

        val provenance = ProvenanceIdentity.HumanCatalogue(discovered.catalogue)
        return sessionMutex.withLock {
            when (discovered.language) {
                SubtitleLanguage.URDU -> ingestDecodedUrduLocked(
                    decodedText = decodedText,
                    provenance = provenance,
                    meta = discovered.meta,
                    isForeignSidecar = false,
                )
                SubtitleLanguage.ENGLISH -> {
                    val ingested = ingestDecodedEnglishLocked(
                        decodedText = decodedText,
                        provenance = provenance,
                        sourceSub = null,
                        preDownloadScore = discovered.preDownloadScore,
                        isSubtitleCatOrigFallback = discovered.isSubtitleCatOrigDonor,
                        isForeignSidecar = false,
                    )
                    if (ingested is VerifiedSubtitleCandidate.Admitted &&
                        (mode == SessionSubtitleMode.DEFAULT || mode == SessionSubtitleMode.BILINGUAL_DOWNLOAD) &&
                        !discovered.isSubtitleCatOrigDonor &&
                        seenEnglishCatalogContent.add(ingested.document.contentSha256)
                    ) {
                        val exactStreamDurationMs = speechTimeline?.mediaDurationMs?.takeIf { it > 0L }
                        val stored = casStore.writeAlignedSubtitle(
                            doc = ingested.document,
                            warp = ingested.alignment.warp,
                            videoDurationMs = exactStreamDurationMs,
                            format = casFormat,
                            pinActive = true,
                        )
                        storedCasOutputs.add(stored)
                        seenEnglishCatalogSources.add(discovered.catalogue)
                        val effectiveSyncProof = ingested.alignment.takeIf { it.anchorKind != AlignmentAnchorKind.UNANCHORED }
                        val enHeaders = ProvenanceCodec.encodeHeaders(
                            provenance = provenance,
                            syncProof = effectiveSyncProof,
                        )
                        subtitleCallback(
                            newSubtitleFile("English [${discovered.catalogue.displayBadge}]", stored.fileUri) {
                                this.headers = enHeaders
                            }
                        )
                    }
                    ingested
                }
            }
        }
    }

    suspend fun discoverAndIngestCatalog(
        enabledSources: Set<CatalogueSourceKey> = SubtitleCatalogSources.DEFAULT_ACTIVE_SOURCES,
    ): CatalogDiscoveryResult {
        val wantedLanguages = when (mode) {
            SessionSubtitleMode.DEFAULT -> setOf(SubtitleLanguage.ENGLISH)
            SessionSubtitleMode.URDU, SessionSubtitleMode.BILINGUAL_DOWNLOAD ->
                setOf(SubtitleLanguage.URDU, SubtitleLanguage.ENGLISH)
        }
        val discovery = catalogSources.discoverCandidates(
            spec = lookupSpec,
            languages = wantedLanguages,
            enabledSources = enabledSources,
        )
        sessionMutex.withLock {
            rejectedTracks.addAll(discovery.rejectedPreDownload)
        }

        for (urCand in discovery.candidatesFor(SubtitleLanguage.URDU)) {
            val enoughUrdu = sessionMutex.withLock {
                distinctUrduDiversityCountLocked() >= targetUrduDiversityCount
            }
            if (enoughUrdu) break
            ingestCatalogCandidate(urCand)
        }

        val needsEnglishDonor = sessionMutex.withLock {
            mode.wantsEnglishTracks ||
                englishDonors.none { it.donor is DonorProvenance.StreamDonor }
        }
        if (needsEnglishDonor) {
            val maxEnglishToAdmit = if (mode == SessionSubtitleMode.URDU) {
                2
            } else {
                targetEnglishCatalogCount.coerceAtLeast(1)
            }
            var ingestedCatalogueDonors = 0
            val englishCandidates = discovery.candidatesFor(SubtitleLanguage.ENGLISH)
            val distinctPass = englishCandidates.distinctBy { it.catalogue }
            val remainderPass = englishCandidates.filter { it !in distinctPass }
            for (enCand in (distinctPass + remainderPass)) {
                if (ingestedCatalogueDonors >= maxEnglishToAdmit) break
                val res = ingestCatalogCandidate(enCand)
                if (res is VerifiedSubtitleCandidate.Admitted) {
                    ingestedCatalogueDonors++
                }
            }
        }
        return discovery
    }

    private data class DonorIdentityKey(
        val donor: DonorProvenance,
        val contentSha256: ContentSha256,
    )

    private fun needsBolChalLocked(forceBolChalLocalization: Boolean): Boolean {
        if (!mode.wantsUrduTracks) return false
        return forceBolChalLocalization ||
            admittedUrduTracks.isEmpty() ||
            (admittedUrduTracks.none {
                it is VerifiedSubtitleCandidate.StreamSyncedNative &&
                    it.alignment.anchorKind != AlignmentAnchorKind.UNANCHORED
            } &&
                admittedDistinctUrduDiversityCountLocked() < targetUrduDiversityCount)
    }

    suspend fun completeSession(
        forceBolChalLocalization: Boolean = false,
    ): SessionCompletionReport {
        sessionMutex.withLock {
            reconcileStreamDonorProofsLocked(allowSinglePackInMultiSession = true)
            flushDeferredProviderAnchorsLocked()
            flushPendingNativeUrduLocked(allowSinglePackInMultiSession = true)
            flushPendingCatalogueUrduLocked(allowSinglePackInMultiSession = true)
        }

        val failedDonors = HashSet<DonorIdentityKey>()
        var fallbackLocalized: Triple<Long, Long?, VerifiedSubtitleCandidate.BolChalLocalized>? = null
        val maxLocalizationAttempts = 4
        var attempt = 0
        while (attempt < maxLocalizationAttempts) {
            val (donorToLocalize, exactStreamDurationMs) = sessionMutex.withLock {
                reconcileStreamDonorProofsLocked(allowSinglePackInMultiSession = true)
                flushDeferredProviderAnchorsLocked()
                flushPendingNativeUrduLocked(allowSinglePackInMultiSession = true)
                flushPendingCatalogueUrduLocked(allowSinglePackInMultiSession = true)
                val chosen = if (!needsBolChalLocked(forceBolChalLocalization)) {
                    null
                } else {
                    selectBestEnglishDonorLocked(excluding = failedDonors)
                }
                val exactDur = speechTimeline?.mediaDurationMs?.takeIf { it > 0L }
                chosen to exactDur
            }
            if (donorToLocalize == null) break
            attempt++

            val donorKey = DonorIdentityKey(
                donor = donorToLocalize.donor,
                contentSha256 = donorToLocalize.document.contentSha256,
            )
            val localizedResult = SceneBolChalLocalizer.localize(
                englishDonorDoc = donorToLocalize.document,
                donorProvenance = donorToLocalize.donor,
                alignmentProof = donorToLocalize.alignment,
                config = bolChalConfig,
                transport = bolChalTransport,
                videoDurationMs = exactStreamDurationMs,
            )

            val shouldRetry = sessionMutex.withLock {
                reconcileStreamDonorProofsLocked(allowSinglePackInMultiSession = true)
                flushDeferredProviderAnchorsLocked()
                flushPendingNativeUrduLocked(allowSinglePackInMultiSession = true)
                flushPendingCatalogueUrduLocked(allowSinglePackInMultiSession = true)

                localizedResult.fold(
                    onFailure = { err ->
                        if (err is CancellationException) throw err
                        failedDonors.add(donorKey)
                        val rejection = (err as? CandidateGateRejectedException)?.rejection
                            ?: VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(
                                err.message ?: "bolchal_localization_failed"
                            )
                        rejectedTracks.add(rejection)
                        needsBolChalLocked(forceBolChalLocalization) &&
                            attempt < maxLocalizationAttempts &&
                            selectBestEnglishDonorLocked(excluding = failedDonors) != null
                    },
                    onSuccess = { localized ->
                        if (!needsBolChalLocked(forceBolChalLocalization)) {
                            false
                        } else {
                            val currentDonor = findBufferedEnglishDonorLocked(donorToLocalize.bufferEntryId)
                            val latestBestDonor = selectBestEnglishDonorLocked(excluding = failedDonors)
                            val latestExactDur = speechTimeline?.mediaDurationMs?.takeIf { it > 0L }
                            val bestDonorShifted = latestBestDonor != null &&
                                latestBestDonor.bufferEntryId != donorToLocalize.bufferEntryId
                            val durationStale = latestExactDur != exactStreamDurationMs
                            val alignmentStale = currentDonor != null &&
                                currentDonor.alignment != donorToLocalize.alignment

                            if (currentDonor == null) {
                                attempt < maxLocalizationAttempts && latestBestDonor != null
                            } else if (durationStale) {
                                attempt < maxLocalizationAttempts
                            } else if ((bestDonorShifted || alignmentStale) && attempt < maxLocalizationAttempts) {
                                fallbackLocalized = Triple(currentDonor.bufferEntryId, exactStreamDurationMs, localized)
                                true
                            } else {
                                fallbackLocalized = null
                                val refreshed = if (currentDonor.alignment != localized.alignment) {
                                    localized.copy(alignment = currentDonor.alignment)
                                } else {
                                    localized
                                }
                                val dedupKey = refreshed.provenance.diversityKey to refreshed.document.contentSha256
                                if (seenUrduDiversityAndContent.add(dedupKey)) {
                                    emitBakedUrduTrackLocked(refreshed)
                                    admittedUrduTracks.add(refreshed)
                                    localizedDonorSelected = refreshed.donor
                                }
                                false
                            }
                        }
                    },
                )
            }
            if (!shouldRetry) break
        }

        return sessionMutex.withLock {
            reconcileStreamDonorProofsLocked(allowSinglePackInMultiSession = true)
            val fallback = fallbackLocalized
            if (fallback != null && needsBolChalLocked(forceBolChalLocalization)) {
                val (fbEntryId, fbDurMs, fbLocalized) = fallback
                val fbDonor = findBufferedEnglishDonorLocked(fbEntryId)
                val latestExactDur = speechTimeline?.mediaDurationMs?.takeIf { it > 0L }
                if (fbDonor != null &&
                    latestExactDur == fbDurMs &&
                    DonorIdentityKey(fbDonor.donor, fbDonor.document.contentSha256) !in failedDonors
                ) {
                    val refreshed = if (fbDonor.alignment != fbLocalized.alignment) {
                        fbLocalized.copy(alignment = fbDonor.alignment)
                    } else {
                        fbLocalized
                    }
                    val dedupKey = refreshed.provenance.diversityKey to refreshed.document.contentSha256
                    if (seenUrduDiversityAndContent.add(dedupKey)) {
                        emitBakedUrduTrackLocked(refreshed)
                        admittedUrduTracks.add(refreshed)
                        localizedDonorSelected = refreshed.donor
                    }
                }
            }
            SessionCompletionReport(
                emittedUrduCandidates = admittedUrduTracks.toList(),
                rejectedCandidates = rejectedTracks.toList(),
                emittedProviderAnchors = emittedAnchorCount,
                localizedDonorUsed = localizedDonorSelected,
                storedCasFiles = storedCasOutputs.toList(),
            )
        }
    }

    fun rankedEnglishDonorsSnapshot(): List<BufferedEnglishDonor> =
        synchronized(englishDonors) {
            englishDonors.sortedWith(englishDonorComparator())
        }

    private suspend fun ingestDecodedUrduLocked(
        decodedText: String,
        provenance: ProvenanceIdentity,
        meta: CatalogueCandidateMeta?,
        isForeignSidecar: Boolean,
    ): VerifiedSubtitleCandidate {
        if (provenance is ProvenanceIdentity.StreamProvider && !isForeignSidecar) {
            nonForeignStreamProviders.add(provenance.provider)
        }
        reconcileStreamDonorProofsLocked()

        val referenceEnglish = selectBestEnglishDonorLocked()?.document
        val initialProof = when (provenance) {
            is ProvenanceIdentity.StreamProvider -> {
                if (isVerifiedSamePackProviderLocked(provenance.provider, isForeignSidecar)) {
                    AlignmentProof(
                        warp = SubtitleTimeWarp.Identity,
                        anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                        syncConfidence = 1.0,
                        medianOnsetResidualMs = 0,
                        p95OnsetResidualMs = 0,
                        splitCount = 0,
                    )
                } else {
                    AlignmentProof(
                        warp = SubtitleTimeWarp.Identity,
                        anchorKind = AlignmentAnchorKind.UNANCHORED,
                        syncConfidence = 0.5,
                        medianOnsetResidualMs = 0,
                        p95OnsetResidualMs = 0,
                        splitCount = 0,
                    )
                }
            }
            else -> AlignmentProof(
                warp = SubtitleTimeWarp.Identity,
                anchorKind = AlignmentAnchorKind.UNANCHORED,
                syncConfidence = 0.5,
                medianOnsetResidualMs = 0,
                p95OnsetResidualMs = 0,
                splitCount = 0,
            )
        }

        val gated = UrduQualityGate.admitNativeOrCatalogue(
            rawDecodedText = decodedText,
            language = SubtitleLanguage.URDU,
            provenance = provenance,
            alignment = initialProof,
            meta = meta,
            lookupSpec = lookupSpec,
            referenceEnglishDoc = referenceEnglish,
        )

        when (gated) {
            is VerifiedSubtitleCandidate.Rejected -> {
                rejectedTracks.add(gated)
                return gated
            }
            is VerifiedSubtitleCandidate.StreamSyncedNative -> {
                if (multiProviderVideoSession && gated.alignment.anchorKind != AlignmentAnchorKind.NATIVE_STREAM_PACK) {
                    pendingNativeUrdu.add(PendingNativeUrduEntry(gated, isForeignSidecar))
                    return gated
                }
                val effectiveNative = if (gated.alignment.anchorKind == AlignmentAnchorKind.UNANCHORED && speechTimeline != null) {
                    when (val outcome = AlassAligner.alignToSparseSpeech(gated.document, speechTimeline!!)) {
                        is AlignmentOutcome.Aligned -> gated.copy(alignment = outcome.proof)
                        is AlignmentOutcome.RejectedMismatch -> gated
                    }
                } else {
                    gated
                }
                val dedupKey = effectiveNative.provenance.diversityKey to effectiveNative.document.contentSha256
                if (mode.wantsUrduTracks && seenUrduDiversityAndContent.add(dedupKey)) {
                    emitBakedUrduTrackLocked(effectiveNative)
                    admittedUrduTracks.add(effectiveNative)
                }
                return effectiveNative
            }
            is VerifiedSubtitleCandidate.AlignedHumanCatalogue -> {
                if (multiProviderVideoSession && speechTimeline == null) {
                    pendingCatalogueUrdu.add(PendingCatalogueUrduEntry(gated, meta))
                    return gated
                }
                val bestStreamAnchor = bestStreamAnchorDonorLocked()
                if (bestStreamAnchor != null) {
                    return alignAndEmitHumanCatalogueUrduLocked(gated, bestStreamAnchor)
                }
                val timeline = speechTimeline
                if (timeline != null) {
                    return alignToSpeechAndEmitHumanCatalogueUrduLocked(gated, timeline)
                }
                pendingCatalogueUrdu.add(PendingCatalogueUrduEntry(gated, meta))
                return gated
            }
            is VerifiedSubtitleCandidate.BolChalLocalized -> return gated
        }
    }

    private suspend fun ingestDecodedEnglishLocked(
        decodedText: String,
        provenance: ProvenanceIdentity,
        sourceSub: SubtitleFile?,
        preDownloadScore: Int,
        isSubtitleCatOrigFallback: Boolean,
        isForeignSidecar: Boolean,
    ): VerifiedSubtitleCandidate {
        val parsed = CueDocument.parse(decodedText, SubtitleLanguage.ENGLISH).getOrElse { err ->
            val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "english_parse_failed")
            rejectedTracks.add(rej)
            return rej
        }

        val requiresStrictEnglishCheck = isSubtitleCatOrigFallback ||
            (provenance is ProvenanceIdentity.HumanCatalogue && provenance.catalogue == CatalogueSourceKey.SUBTITLECAT)
        if (requiresStrictEnglishCheck) {
            UrduOrthographyGate.verifyEnglishDonorDocument(parsed).exceptionOrNull()?.let { err ->
                val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "non_english_donor")
                rejectedTracks.add(rej)
                return rej
            }
        }

        return when (provenance) {
            is ProvenanceIdentity.StreamProvider -> {
                if (!isForeignSidecar) {
                    nonForeignStreamProviders.add(provenance.provider)
                }
                reconcileStreamDonorProofsLocked()

                val samePack = isVerifiedSamePackProviderLocked(provenance.provider, isForeignSidecar)
                val currentTimeline = speechTimeline
                val proof = if (samePack) {
                    AlignmentProof(
                        warp = SubtitleTimeWarp.Identity,
                        anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                        syncConfidence = 1.0,
                        medianOnsetResidualMs = 0,
                        p95OnsetResidualMs = 0,
                        splitCount = 0,
                    )
                } else {
                    computeFallbackDonorProofLocked(parsed)
                }
                val donor = DonorProvenance.StreamDonor(provenance.provider)
                val rank = provenance.provider.rankFor(lookupSpec.isAnime)
                synchronized(englishDonors) {
                    englishDonors.add(
                        BufferedEnglishDonor(
                            donor = donor,
                            document = parsed,
                            alignment = proof,
                            priorityTier = DONOR_TIER_STREAM_PROVIDER,
                            intraTierScore = rank,
                            isSubtitleCatOrigFallback = false,
                            isForeignSidecar = isForeignSidecar,
                            sourceSubtitleFile = sourceSub,
                            reconciledTimeline = if (samePack) null else currentTimeline,
                        )
                    )
                }

                val activeVideos = snapshotActiveVideoProviders()
                val canServeAsProviderAnchor = !isForeignSidecar &&
                    (activeVideos.isEmpty() || provenance.provider in activeVideos)
                val shouldEmitAnchorImmediately = !multiProviderVideoSession &&
                    shouldForwardProviderAnchor() &&
                    canServeAsProviderAnchor
                val effectiveSyncProof = proof.takeIf { it.anchorKind != AlignmentAnchorKind.UNANCHORED }
                if (shouldEmitAnchorImmediately && sourceSub != null) {
                    emitProviderAnchorSidecarLocked(
                        provider = provenance.provider,
                        document = parsed,
                        syncProof = effectiveSyncProof,
                        sourceSub = sourceSub,
                    )
                } else if (!shouldForwardProviderAnchor() && mode == SessionSubtitleMode.DEFAULT && sourceSub != null) {
                    val mergedHeaders = LinkedHashMap<String, String>()
                    sourceSub.headers?.let { mergedHeaders.putAll(sanitizePublicSubtitleHeaders(it)) }
                    mergedHeaders.putAll(ProvenanceCodec.encodeHeaders(provenance, effectiveSyncProof))
                    subtitleCallback(
                        newSubtitleFile(sourceSub.lang.ifBlank { "English [${provenance.provider.displayBadge}]" }, sourceSub.url) {
                            this.headers = mergedHeaders
                        }
                    )
                }

                if (!multiProviderVideoSession && pendingCatalogueUrdu.isNotEmpty() && bestStreamAnchorDonorLocked() != null) {
                    flushPendingCatalogueUrduLocked()
                }

                VerifiedSubtitleCandidate.StreamSyncedNative(
                    provider = provenance.provider,
                    language = SubtitleLanguage.ENGLISH,
                    document = parsed,
                    alignment = proof,
                )
            }

            is ProvenanceIdentity.HumanCatalogue -> {
                val isSubCat = provenance.catalogue == CatalogueSourceKey.SUBTITLECAT
                val tier = if (isSubCat) DONOR_TIER_SUBTITLECAT_LAST_RESORT else DONOR_TIER_HUMAN_CATALOGUE
                val intraScore = when {
                    isSubCat && isSubtitleCatOrigFallback -> 10
                    isSubCat -> 50
                    preDownloadScore > 0 -> preDownloadScore
                    else -> provenance.catalogue.intraCatalogueRank
                }

                val bestStreamAnchor = bestStreamAnchorDonorLocked()
                val currentTimeline = speechTimeline
                val donorAlignment = when {
                    bestStreamAnchor != null -> {
                        when (val outcome = AlassAligner.alignToAnchorCues(
                            candidate = parsed,
                            anchor = bestStreamAnchor.document,
                            anchorKind = AlignmentAnchorKind.CROSS_PROVIDER_SIDECAR,
                            videoDurationMs = lookupSpec.expectedDurationMs,
                        )) {
                            is AlignmentOutcome.Aligned -> outcome.proof
                            is AlignmentOutcome.RejectedMismatch -> {
                                val rej = VerifiedSubtitleCandidate.Rejected.SyncMismatch(outcome.bestScore, outcome.residualMs)
                                rejectedTracks.add(rej)
                                return rej
                            }
                        }
                    }
                    currentTimeline != null -> {
                        when (val outcome = AlassAligner.alignToSparseSpeech(
                            candidate = parsed,
                            timeline = currentTimeline,
                        )) {
                            is AlignmentOutcome.Aligned -> outcome.proof
                            is AlignmentOutcome.RejectedMismatch -> {
                                val rej = VerifiedSubtitleCandidate.Rejected.SyncMismatch(outcome.bestScore, outcome.residualMs)
                                rejectedTracks.add(rej)
                                return rej
                            }
                        }
                    }
                    else -> {
                        AlignmentProof(
                            warp = SubtitleTimeWarp.Identity,
                            anchorKind = AlignmentAnchorKind.UNANCHORED,
                            syncConfidence = 0.55,
                            medianOnsetResidualMs = 0,
                            p95OnsetResidualMs = 0,
                            splitCount = 0,
                        )
                    }
                }

                synchronized(englishDonors) {
                    englishDonors.add(
                        BufferedEnglishDonor(
                            donor = DonorProvenance.CatalogueDonor(provenance.catalogue),
                            document = parsed,
                            alignment = donorAlignment,
                            priorityTier = tier,
                            intraTierScore = intraScore,
                            isSubtitleCatOrigFallback = isSubtitleCatOrigFallback,
                            isForeignSidecar = false,
                            sourceSubtitleFile = sourceSub,
                            reconciledTimeline = if (bestStreamAnchor == null) currentTimeline else null,
                        )
                    )
                }

                VerifiedSubtitleCandidate.AlignedHumanCatalogue(
                    catalogue = provenance.catalogue,
                    language = SubtitleLanguage.ENGLISH,
                    authenticity = AuthenticityGrade.AUTHENTIC_HUMAN,
                    mtAudit = null,
                    releaseAffinityScore = intraScore,
                    document = parsed,
                    alignment = donorAlignment,
                )
            }

            else -> {
                val fallbackCat = CatalogueSourceKey.WYZIE
                val proof = AlignmentProof(
                    warp = SubtitleTimeWarp.Identity,
                    anchorKind = AlignmentAnchorKind.UNANCHORED,
                    syncConfidence = 0.5,
                    medianOnsetResidualMs = 0,
                    p95OnsetResidualMs = 0,
                    splitCount = 0,
                )
                englishDonors.add(
                    BufferedEnglishDonor(
                        donor = DonorProvenance.CatalogueDonor(fallbackCat),
                        document = parsed,
                        alignment = proof,
                        priorityTier = DONOR_TIER_HUMAN_CATALOGUE,
                        intraTierScore = 80,
                        isForeignSidecar = isForeignSidecar,
                        sourceSubtitleFile = sourceSub,
                    )
                )
                VerifiedSubtitleCandidate.AlignedHumanCatalogue(
                    catalogue = fallbackCat,
                    language = SubtitleLanguage.ENGLISH,
                    authenticity = AuthenticityGrade.AUTHENTIC_HUMAN,
                    mtAudit = null,
                    releaseAffinityScore = 80,
                    document = parsed,
                    alignment = proof,
                )
            }
        }
    }

    private suspend fun emitProviderAnchorSidecarLocked(
        provider: StreamProviderKey,
        document: CueDocument,
        syncProof: AlignmentProof?,
        sourceSub: SubtitleFile,
    ) {
        val anchorDedup = provider to document.contentSha256
        if (!seenProviderAnchorContent.add(anchorDedup)) return
        val provenance = ProvenanceIdentity.StreamProvider(provider)
        val mergedHeaders = LinkedHashMap<String, String>()
        sourceSub.headers?.let { mergedHeaders.putAll(sanitizePublicSubtitleHeaders(it)) }
        mergedHeaders.putAll(
            ProvenanceCodec.encodeHeaders(
                provenance = provenance,
                syncProof = syncProof,
                syncRole = ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR,
            )
        )
        val label = if (sourceSub.lang.isNotBlank()) sourceSub.lang else "English [${provider.displayBadge}]"
        subtitleCallback(
            newSubtitleFile(label, sourceSub.url) {
                this.headers = mergedHeaders
            }
        )
        emittedAnchorCount++
    }

    private suspend fun flushDeferredProviderAnchorsLocked() {
        if (!shouldForwardProviderAnchor()) return
        val activeVideos = snapshotActiveVideoProviders()
        val donorsSnapshot = synchronized(englishDonors) { englishDonors.toList() }
        for (donorEntry in donorsSnapshot) {
            val sd = donorEntry.donor as? DonorProvenance.StreamDonor ?: continue
            val sourceSub = donorEntry.sourceSubtitleFile ?: continue
            if (donorEntry.isForeignSidecar) continue
            if (activeVideos.isNotEmpty() && sd.provider !in activeVideos) continue
            val effectiveSyncProof = donorEntry.alignment.takeIf { it.anchorKind != AlignmentAnchorKind.UNANCHORED }
            emitProviderAnchorSidecarLocked(
                provider = sd.provider,
                document = donorEntry.document,
                syncProof = effectiveSyncProof,
                sourceSub = sourceSub,
            )
        }
    }

    private suspend fun flushPendingNativeUrduLocked(
        allowSinglePackInMultiSession: Boolean = false,
    ) {
        if (pendingNativeUrdu.isEmpty()) return
        val currentTimeline = speechTimeline
        val entries = pendingNativeUrdu.toList()
        pendingNativeUrdu.clear()

        for (entry in entries) {
            val certified = when {
                isVerifiedSamePackProviderLocked(
                    provider = entry.candidate.provider,
                    isForeignSidecar = entry.isForeignSidecar,
                    allowSinglePackInMultiSession = allowSinglePackInMultiSession,
                ) -> entry.candidate.copy(
                    alignment = AlignmentProof(
                        warp = SubtitleTimeWarp.Identity,
                        anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                        syncConfidence = 1.0,
                        medianOnsetResidualMs = 0,
                        p95OnsetResidualMs = 0,
                        splitCount = 0,
                    )
                )
                entry.candidate.alignment.anchorKind == AlignmentAnchorKind.UNANCHORED && currentTimeline != null -> {
                    when (val outcome = AlassAligner.alignToSparseSpeech(entry.candidate.document, currentTimeline)) {
                        is AlignmentOutcome.Aligned -> entry.candidate.copy(alignment = outcome.proof)
                        is AlignmentOutcome.RejectedMismatch -> entry.candidate
                    }
                }
                else -> entry.candidate
            }
            val dedupKey = certified.provenance.diversityKey to certified.document.contentSha256
            if (mode.wantsUrduTracks && seenUrduDiversityAndContent.add(dedupKey)) {
                emitBakedUrduTrackLocked(certified)
                admittedUrduTracks.add(certified)
            }
        }
    }

    private suspend fun flushPendingCatalogueUrduLocked(
        allowSinglePackInMultiSession: Boolean = false,
    ) {
        if (pendingCatalogueUrdu.isEmpty()) return
        val bestAnchor = bestStreamAnchorDonorLocked(allowSinglePackInMultiSession = allowSinglePackInMultiSession)
        val currentTimeline = speechTimeline
        val entries = pendingCatalogueUrdu.toList()
        pendingCatalogueUrdu.clear()

        for (entry in entries) {
            if (admittedDistinctUrduDiversityCountLocked() >= targetUrduDiversityCount) continue
            when {
                bestAnchor != null -> alignAndEmitHumanCatalogueUrduLocked(entry.candidate, bestAnchor)
                currentTimeline != null -> alignToSpeechAndEmitHumanCatalogueUrduLocked(entry.candidate, currentTimeline)
                else -> {
                    val dedupKey = entry.candidate.provenance.diversityKey to entry.candidate.document.contentSha256
                    val alreadySeenIdenticalBytes = admittedUrduTracks.any {
                        it is VerifiedSubtitleCandidate.AlignedHumanCatalogue &&
                            it.document.contentSha256 == entry.candidate.document.contentSha256
                    }
                    if (!alreadySeenIdenticalBytes && mode.wantsUrduTracks && seenUrduDiversityAndContent.add(dedupKey)) {
                        emitBakedUrduTrackLocked(entry.candidate)
                        admittedUrduTracks.add(entry.candidate)
                    }
                }
            }
        }
    }

    private suspend fun alignAndEmitHumanCatalogueUrduLocked(
        candidate: VerifiedSubtitleCandidate.AlignedHumanCatalogue,
        streamAnchor: BufferedEnglishDonor,
    ): VerifiedSubtitleCandidate {
        val anchorKind = AlignmentAnchorKind.MATCHED_PROVIDER_SIDECAR
        val outcome = AlassAligner.alignToAnchorCues(
            candidate = candidate.document,
            anchor = streamAnchor.document,
            anchorKind = anchorKind,
            videoDurationMs = lookupSpec.expectedDurationMs,
        )
        return commitAlignedCatalogueUrduOutcomeLocked(candidate, outcome)
    }

    private suspend fun alignToSpeechAndEmitHumanCatalogueUrduLocked(
        candidate: VerifiedSubtitleCandidate.AlignedHumanCatalogue,
        timeline: SpeechActivityTimeline,
    ): VerifiedSubtitleCandidate {
        val outcome = AlassAligner.alignToSparseSpeech(
            candidate = candidate.document,
            timeline = timeline,
        )
        return commitAlignedCatalogueUrduOutcomeLocked(candidate, outcome)
    }

    private suspend fun commitAlignedCatalogueUrduOutcomeLocked(
        candidate: VerifiedSubtitleCandidate.AlignedHumanCatalogue,
        outcome: AlignmentOutcome,
    ): VerifiedSubtitleCandidate {
        return when (outcome) {
            is AlignmentOutcome.RejectedMismatch -> {
                val rej = VerifiedSubtitleCandidate.Rejected.SyncMismatch(outcome.bestScore, outcome.residualMs)
                rejectedTracks.add(rej)
                rej
            }
            is AlignmentOutcome.Aligned -> {
                val alignedCandidate = candidate.copy(alignment = outcome.proof)
                val dedupKey = alignedCandidate.provenance.diversityKey to alignedCandidate.document.contentSha256
                val alreadySeenIdenticalBytes = admittedUrduTracks.any {
                    it is VerifiedSubtitleCandidate.AlignedHumanCatalogue &&
                        it.document.contentSha256 == alignedCandidate.document.contentSha256
                }
                if (!alreadySeenIdenticalBytes &&
                    mode.wantsUrduTracks &&
                    admittedDistinctUrduDiversityCountLocked() < targetUrduDiversityCount &&
                    seenUrduDiversityAndContent.add(dedupKey)
                ) {
                    emitBakedUrduTrackLocked(alignedCandidate)
                    admittedUrduTracks.add(alignedCandidate)
                }
                alignedCandidate
            }
        }
    }

    private suspend fun emitBakedUrduTrackLocked(admitted: VerifiedSubtitleCandidate.Admitted) {
        val exactStreamDurationMs = speechTimeline?.mediaDurationMs?.takeIf { it > 0L }
        val stored = casStore.writeAlignedSubtitle(
            doc = admitted.document,
            warp = admitted.alignment.warp,
            videoDurationMs = exactStreamDurationMs,
            format = casFormat,
            pinActive = true,
        )
        storedCasOutputs.add(stored)

        val effectiveSyncProof = admitted.alignment.takeIf { it.anchorKind != AlignmentAnchorKind.UNANCHORED }
        val headers = ProvenanceCodec.encodeHeaders(
            provenance = admitted.provenance,
            syncProof = effectiveSyncProof,
        )
        val badge = admitted.provenance.sidecarFileBadge
        val label = "Urdu [$badge]"
        subtitleCallback(
            newSubtitleFile(label, stored.fileUri) {
                this.headers = headers
            }
        )
    }

    private fun admittedDistinctUrduDiversityCountLocked(): Int =
        admittedUrduTracks.asSequence().map { it.provenance.diversityKey }.distinct().count()

    private fun distinctUrduDiversityCountLocked(): Int =
        (admittedUrduTracks.asSequence().map { it.provenance.diversityKey } +
            pendingNativeUrdu.asSequence().map { it.candidate.provenance.diversityKey } +
            pendingCatalogueUrdu.asSequence().map { it.candidate.provenance.diversityKey })
            .distinct()
            .count()

    private fun sanitizePublicSubtitleHeaders(headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(headers.size)
        for ((k, v) in headers) {
            if (k.equals("X-API-Key", ignoreCase = true) ||
                k.equals("api-key", ignoreCase = true) ||
                k.equals("Authorization", ignoreCase = true) ||
                k.equals("Proxy-Authorization", ignoreCase = true) ||
                k.equals("Cookie", ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_SYNC, ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_SYNC_ROLE, ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_SOURCE, ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_ORIGIN, ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_DONOR, ignoreCase = true) ||
                k.equals(ProvenanceCodec.HEADER_ENGINE, ignoreCase = true)
            ) {
                continue
            }
            out[k] = v
        }
        return out
    }

    private fun isVerifiedSamePackProviderLocked(
        provider: StreamProviderKey,
        isForeignSidecar: Boolean,
        allowSinglePackInMultiSession: Boolean = false,
    ): Boolean {
        if (isForeignSidecar) return false
        val activeVideos = snapshotActiveVideoProviders()
        if (activeVideos.isNotEmpty()) {
            if (multiProviderVideoSession && !allowSinglePackInMultiSession) return false
            return activeVideos.size == 1 && provider in activeVideos
        }
        if (multiProviderVideoSession) return false
        return nonForeignStreamProviders.size == 1 && provider in nonForeignStreamProviders
    }

    private fun computeFallbackDonorProofLocked(doc: CueDocument): AlignmentProof {
        val currentTimeline = speechTimeline
        if (currentTimeline != null) {
            when (val outcome = AlassAligner.alignToSparseSpeech(doc, currentTimeline)) {
                is AlignmentOutcome.Aligned -> return outcome.proof
                is AlignmentOutcome.RejectedMismatch -> Unit
            }
        }
        return AlignmentProof(
            warp = SubtitleTimeWarp.Identity,
            anchorKind = AlignmentAnchorKind.UNANCHORED,
            syncConfidence = 0.5,
            medianOnsetResidualMs = 0,
            p95OnsetResidualMs = 0,
            splitCount = 0,
        )
    }

    private fun reconcileStreamDonorProofsLocked(
        allowSinglePackInMultiSession: Boolean = false,
    ) {
        val currentTimeline = speechTimeline
        synchronized(englishDonors) {
            var i = 0
            while (i < englishDonors.size) {
                val existing = englishDonors[i]
                when (val donor = existing.donor) {
                    is DonorProvenance.StreamDonor -> {
                        val samePack = isVerifiedSamePackProviderLocked(
                            provider = donor.provider,
                            isForeignSidecar = existing.isForeignSidecar,
                            allowSinglePackInMultiSession = allowSinglePackInMultiSession,
                        )
                        if (samePack && !existing.isForeignSidecar) {
                            if (existing.alignment.anchorKind != AlignmentAnchorKind.NATIVE_STREAM_PACK) {
                                englishDonors[i] = existing.copy(
                                    alignment = AlignmentProof(
                                        warp = SubtitleTimeWarp.Identity,
                                        anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                                        syncConfidence = 1.0,
                                        medianOnsetResidualMs = 0,
                                        p95OnsetResidualMs = 0,
                                        splitCount = 0,
                                    ),
                                    reconciledTimeline = null,
                                )
                            }
                            i++
                        } else if (existing.alignment.anchorKind == AlignmentAnchorKind.NATIVE_STREAM_PACK) {
                            englishDonors[i] = existing.copy(
                                alignment = computeFallbackDonorProofLocked(existing.document),
                                reconciledTimeline = currentTimeline,
                            )
                            i++
                        } else if (
                            currentTimeline != null &&
                            existing.reconciledTimeline !== currentTimeline &&
                            (existing.alignment.anchorKind == AlignmentAnchorKind.UNANCHORED ||
                                existing.alignment.anchorKind == AlignmentAnchorKind.SPARSE_AUDIO_VAD)
                        ) {
                            val refreshed = computeFallbackDonorProofLocked(existing.document)
                            englishDonors[i] = existing.copy(
                                alignment = refreshed,
                                reconciledTimeline = currentTimeline,
                            )
                            i++
                        } else {
                            i++
                        }
                    }
                    is DonorProvenance.CatalogueDonor -> {
                        if (
                            currentTimeline != null &&
                            existing.reconciledTimeline !== currentTimeline &&
                            (existing.alignment.anchorKind == AlignmentAnchorKind.UNANCHORED ||
                                existing.alignment.anchorKind == AlignmentAnchorKind.SPARSE_AUDIO_VAD)
                        ) {
                            when (val outcome = AlassAligner.alignToSparseSpeech(existing.document, currentTimeline)) {
                                is AlignmentOutcome.Aligned -> {
                                    englishDonors[i] = existing.copy(
                                        alignment = outcome.proof,
                                        reconciledTimeline = currentTimeline,
                                    )
                                    i++
                                }
                                is AlignmentOutcome.RejectedMismatch -> {
                                    englishDonors.removeAt(i)
                                    rejectedTracks.add(
                                        VerifiedSubtitleCandidate.Rejected.SyncMismatch(outcome.bestScore, outcome.residualMs)
                                    )
                                }
                            }
                        } else {
                            i++
                        }
                    }
                }
            }
        }
    }

    private fun bestStreamAnchorDonorLocked(
        allowSinglePackInMultiSession: Boolean = false,
    ): BufferedEnglishDonor? {
        reconcileStreamDonorProofsLocked(allowSinglePackInMultiSession = allowSinglePackInMultiSession)
        return synchronized(englishDonors) {
            englishDonors
                .asSequence()
                .filter { donorEntry ->
                    val sd = donorEntry.donor as? DonorProvenance.StreamDonor ?: return@filter false
                    isVerifiedSamePackProviderLocked(
                        provider = sd.provider,
                        isForeignSidecar = donorEntry.isForeignSidecar,
                        allowSinglePackInMultiSession = allowSinglePackInMultiSession,
                    )
                }
                .maxByOrNull { it.intraTierScore }
        }
    }

    private fun findBufferedEnglishDonorLocked(bufferEntryId: Long): BufferedEnglishDonor? =
        synchronized(englishDonors) {
            englishDonors.firstOrNull { it.bufferEntryId == bufferEntryId }
        }

    private fun selectBestEnglishDonorLocked(
        excluding: Set<DonorIdentityKey> = emptySet(),
    ): BufferedEnglishDonor? =
        synchronized(englishDonors) {
            val pool = if (excluding.isEmpty()) {
                englishDonors
            } else {
                englishDonors.filterNot {
                    DonorIdentityKey(it.donor, it.document.contentSha256) in excluding
                }
            }
            pool.minWithOrNull(englishDonorComparator())
        }

    private fun englishDonorComparator(): Comparator<BufferedEnglishDonor> {
        val activeVideos = snapshotActiveVideoProviders()
        return compareByDescending<BufferedEnglishDonor> { it.priorityTier }
            .thenByDescending { donor ->
                val sd = donor.donor as? DonorProvenance.StreamDonor
                if (sd == null) {
                    false
                } else {
                    !donor.isForeignSidecar && (activeVideos.isEmpty() || sd.provider in activeVideos)
                }
            }
            .thenByDescending { !it.isForeignSidecar }
            .thenByDescending { !it.isSubtitleCatOrigFallback }
            .thenByDescending { it.intraTierScore }
            .thenByDescending { it.document.cues.size }
    }

    private fun shouldForwardProviderAnchor(): Boolean =
        mode == SessionSubtitleMode.BILINGUAL_DOWNLOAD ||
            SubtitleModeBridge.isBilingualDownloadCallback(callbackIdentityToken ?: subtitleCallback) ||
            (mode == SessionSubtitleMode.URDU && SubtitleModeBridge.isVaultV2Enabled())

    private fun resolveRawSubtitleProvenance(
        sub: SubtitleFile,
        rawSourceHint: String?,
        headers: Map<String, String>,
    ): ProvenanceIdentity {
        val explicitSource = headers.entries
            .firstOrNull { it.key.equals(ProvenanceCodec.HEADER_SOURCE, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotEmpty() }
        val explicitOrigin = headers.entries
            .firstOrNull { it.key.equals(ProvenanceCodec.HEADER_ORIGIN, ignoreCase = true) }
            ?.value?.trim()
        val isTranslated = explicitOrigin.equals(ProvenanceCodec.ORIGIN_BOLCHAL, ignoreCase = true) ||
            explicitSource?.startsWith(ProvenanceCodec.SOURCE_URDU_TRANSLATED, ignoreCase = true) == true

        if (!isTranslated && explicitSource != null) {
            StreamProviderKey.fromTextOrHost(explicitSource)?.let {
                return ProvenanceIdentity.StreamProvider(it)
            }
            CatalogueSourceKey.fromTextOrHost(explicitSource)?.let {
                return ProvenanceIdentity.HumanCatalogue(it)
            }
        }

        if (!rawSourceHint.isNullOrBlank() && !isTranslated) {
            StreamProviderKey.fromTextOrHost(rawSourceHint)?.let { providerKey ->
                return ProvenanceIdentity.StreamProvider(providerKey)
            }
            CatalogueSourceKey.fromTextOrHost(rawSourceHint)?.let { catKey ->
                return ProvenanceIdentity.HumanCatalogue(catKey)
            }
        }
        return ProvenanceCodec.decode(
            headers = headers,
            displayName = sub.lang,
            urlOrFileName = sub.url,
        )
    }

    private fun isForeignRawSidecar(
        rawSourceHint: String?,
        headers: Map<String, String>,
        resolvedProvenance: ProvenanceIdentity,
    ): Boolean {
        if (ProvenanceCodec.isForeignSidecarHeader(headers)) return true
        val resolvedStream = resolvedProvenance as? ProvenanceIdentity.StreamProvider ?: return false
        val hintedStream = rawSourceHint?.takeIf { it.isNotBlank() }?.let { StreamProviderKey.fromTextOrHost(it) }
        return hintedStream != null && hintedStream != resolvedStream.provider
    }

    private fun resolveSubtitleLanguage(sub: SubtitleFile, headers: Map<String, String>): SubtitleLanguage? {
        SubtitleLanguage.fromTagOrName(sub.lang)?.let { return it }
        val urlLower = sub.url.substringBefore('?').lowercase(Locale.ROOT)
        if (urlLower.endsWith("-ur.srt") || urlLower.endsWith(".ur.srt") || urlLower.endsWith(".ur.vtt")) {
            return SubtitleLanguage.URDU
        }
        if (urlLower.endsWith("-en.srt") || urlLower.endsWith("-orig.srt") || urlLower.endsWith(".en.vtt") || urlLower.endsWith(".en.srt")) {
            return SubtitleLanguage.ENGLISH
        }
        if (headers.keys.any { it.equals("X-SubtitleCat-Orig-Url", ignoreCase = true) }) {
            return SubtitleLanguage.ENGLISH
        }
        return null
    }

    private fun isSubtitleCatOrigHeaderOrUrl(headers: Map<String, String>, url: String): Boolean {
        if (url.substringBefore('?').endsWith("-orig.srt", ignoreCase = true)) return true
        return headers.entries.any { (k, v) ->
            k.equals("X-SubtitleCat-Orig-Url", ignoreCase = true) && v.endsWith("-orig.srt", ignoreCase = true)
        }
    }

    companion object {
        const val DONOR_TIER_STREAM_PROVIDER = 3
        const val DONOR_TIER_HUMAN_CATALOGUE = 2
        const val DONOR_TIER_SUBTITLECAT_LAST_RESORT = 1
        const val HEADER_SPEECH_TIMELINE_B64 = "X-Speech-Timeline-B64"
    }
}
