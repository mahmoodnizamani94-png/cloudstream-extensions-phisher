package com.phisher98.subtitles

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

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
) {
    init {
        require(maxActiveSlots >= 1) { "maxActiveSlots must be >= 1" }
        require(maxPrewarmMetadataSlots >= 1) { "maxPrewarmMetadataSlots must be >= 1" }
        require(!rootDir.name.contains("superstream", ignoreCase = true)) {
            "UrduCasStore directory name cannot contain provider-specific 'superstream'"
        }
    }

    private val storeLock = Any()
    private val pinnedFileNames = HashSet<String>()

    private val prewarmMetadataLru = object : LinkedHashMap<String, PrewarmMetadataSummary>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PrewarmMetadataSummary>?): Boolean =
            size > maxPrewarmMetadataSlots
    }

    fun writeAlignedSubtitle(
        doc: CueDocument,
        warp: SubtitleTimeWarp = SubtitleTimeWarp.Identity,
        videoDurationMs: Long? = null,
        format: CasSubtitleFormat = CasSubtitleFormat.WEBVTT,
        pinActive: Boolean = false,
    ): CasStoredSubtitle {
        val serializedText: String
        val cueCount: Int
        val contentSha: ContentSha256

        when (format) {
            CasSubtitleFormat.WEBVTT -> {
                val baked = WebVttBaker.bake(doc, warp, videoDurationMs)
                serializedText = baked.utf8Content
                cueCount = baked.cueCount
                contentSha = baked.bakedContentSha256
            }
            CasSubtitleFormat.SRT -> {
                val srtBody = WebVttBaker.serializeSrt(doc, warp, videoDurationMs)
                serializedText = "\uFEFF$srtBody"
                cueCount = doc.cues.size
                contentSha = ContentSha256.ofUtf8(srtBody)
            }
        }

        val shaPrefix24 = contentSha.hex.take(24)
        val fileName = "${CAS_FILE_PREFIX}${shaPrefix24}.${format.extension}"
        check(!fileName.contains("superstream", ignoreCase = true))

        val targetFile = synchronized(storeLock) {
            if (!rootDir.exists()) {
                rootDir.mkdirs()
            }
            val out = File(rootDir, fileName)
            if (!out.isFile || out.length() == 0L) {
                val tmp = File(rootDir, ".tmp_${UUID.randomUUID()}_$fileName")
                try {
                    tmp.writeBytes(serializedText.toByteArray(StandardCharsets.UTF_8))
                    runCatching {
                        Files.move(
                            tmp.toPath(),
                            out.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    }.getOrElse {
                        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            } else {
                out.setLastModified(System.currentTimeMillis())
            }
            if (pinActive) {
                pinnedFileNames.add(fileName)
            }
            evictExcessActiveFilesLocked(protectedName = fileName)
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
        synchronized(storeLock) {
            pinnedFileNames.remove(fileName)
        }
    }

    fun listActiveSubtitleFiles(): List<File> = synchronized(storeLock) {
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
        synchronized(storeLock) {
            prewarmMetadataLru[key]?.let { return it }
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
        synchronized(storeLock) {
            prewarmMetadataLru[key] = summary
        }
        return summary
    }

    fun getPrewarmedMetadata(spec: MediaLookupSpec): PrewarmMetadataSummary? = synchronized(storeLock) {
        prewarmMetadataLru[buildLookupKey(spec)]
    }

    private fun evictExcessActiveFilesLocked(protectedName: String) {
        val activeFiles = rootDir.listFiles { f ->
            f.isFile && f.name.startsWith(CAS_FILE_PREFIX) &&
                (f.name.endsWith(".vtt") || f.name.endsWith(".srt"))
        }?.toMutableList() ?: return

        if (activeFiles.size <= maxActiveSlots) return
        activeFiles.sortBy { it.lastModified() }
        var remaining = activeFiles.size
        for (candidate in activeFiles) {
            if (remaining <= maxActiveSlots) break
            if (candidate.name == protectedName || candidate.name in pinnedFileNames) continue
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

    companion object {
        const val CAS_FILE_PREFIX = "cs_urdu_v2_"
    }
}

data class BufferedEnglishDonor(
    val donor: DonorProvenance,
    val document: CueDocument,
    val alignment: AlignmentProof,
    val priorityTier: Int,
    val intraTierScore: Int,
    val isSubtitleCatOrigFallback: Boolean = false,
    val sourceSubtitleFile: SubtitleFile? = null,
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
    private val callbackIdentityToken: Any? = null,
    private val subtitleCallback: (SubtitleFile) -> Unit,
) {
    private val sessionMutex = Mutex()

    private val admittedUrduTracks = ArrayList<VerifiedSubtitleCandidate.Admitted>()
    private val rejectedTracks = ArrayList<VerifiedSubtitleCandidate.Rejected>()
    private val pendingCatalogueUrdu = ArrayList<PendingCatalogueUrduEntry>()
    private val englishDonors = ArrayList<BufferedEnglishDonor>()
    private val seenUrduDiversityAndContent = HashSet<Pair<String, ContentSha256>>()
    private val seenProviderAnchorContent = HashSet<Pair<StreamProviderKey, ContentSha256>>()
    private val storedCasOutputs = ArrayList<CasStoredSubtitle>()
    private var emittedAnchorCount = 0
    private var localizedDonorSelected: DonorProvenance? = null

    private data class PendingCatalogueUrduEntry(
        val candidate: VerifiedSubtitleCandidate.AlignedHumanCatalogue,
        val meta: CatalogueCandidateMeta?,
    )

    suspend fun ingestRawSubtitle(
        sub: SubtitleFile,
        rawSourceHint: String? = null,
        rawBytesOverride: ByteArray? = null,
    ): VerifiedSubtitleCandidate? {
        val headers = sub.headers.orEmpty()
        val resolvedProvenance = resolveRawSubtitleProvenance(sub, rawSourceHint, headers)
        val detectedLanguage = resolveSubtitleLanguage(sub, headers)
            ?: return null

        val rawBytes = rawBytesOverride
            ?: httpTransport.get(sub.url, headers).getOrNull()?.bodyBytes
            ?: return null

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
                )
                SubtitleLanguage.ENGLISH -> ingestDecodedEnglishLocked(
                    decodedText = decodedText,
                    provenance = resolvedProvenance,
                    sourceSub = sub,
                    preDownloadScore = 0,
                    isSubtitleCatOrigFallback = isSubtitleCatOrigHeaderOrUrl(headers, sub.url),
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
                )
                SubtitleLanguage.ENGLISH -> {
                    if (mode == SessionSubtitleMode.DEFAULT || mode == SessionSubtitleMode.BILINGUAL_DOWNLOAD) {
                        if (!discovered.isSubtitleCatOrigDonor) {
                            val enHeaders = discovered.requestHeaders + ProvenanceCodec.encodeHeaders(provenance)
                            subtitleCallback(
                                newSubtitleFile("English", discovered.downloadUrl) {
                                    this.headers = enHeaders
                                }
                            )
                        }
                    }
                    ingestDecodedEnglishLocked(
                        decodedText = decodedText,
                        provenance = provenance,
                        sourceSub = null,
                        preDownloadScore = discovered.preDownloadScore,
                        isSubtitleCatOrigFallback = discovered.isSubtitleCatOrigDonor,
                    )
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
                admittedUrduTracks.size + pendingCatalogueUrdu.size >= targetUrduDiversityCount
            }
            if (enoughUrdu) break
            ingestCatalogCandidate(urCand)
        }

        val needsEnglishDonor = sessionMutex.withLock {
            mode.wantsEnglishTracks ||
                englishDonors.none { it.donor is DonorProvenance.StreamDonor }
        }
        if (needsEnglishDonor) {
            var ingestedCatalogueDonors = 0
            for (enCand in discovery.candidatesFor(SubtitleLanguage.ENGLISH)) {
                if (ingestedCatalogueDonors >= 2 && mode == SessionSubtitleMode.URDU) break
                val res = ingestCatalogCandidate(enCand)
                if (res is VerifiedSubtitleCandidate.Admitted) {
                    ingestedCatalogueDonors++
                }
            }
        }
        return discovery
    }

    suspend fun completeSession(
        forceBolChalLocalization: Boolean = false,
    ): SessionCompletionReport {
        sessionMutex.withLock {
            flushPendingCatalogueUrduLocked()
        }

        val donorToLocalize: BufferedEnglishDonor? = sessionMutex.withLock {
            val wantsUrdu = mode.wantsUrduTracks
            val needsBolChal = wantsUrdu && (
                forceBolChalLocalization ||
                    admittedUrduTracks.isEmpty() ||
                    (admittedUrduTracks.none { it is VerifiedSubtitleCandidate.StreamSyncedNative } &&
                        admittedUrduTracks.size < targetUrduDiversityCount)
                )
            if (!needsBolChal) {
                null
            } else {
                selectBestEnglishDonorLocked()
            }
        }

        if (donorToLocalize != null) {
            val localizedResult = SceneBolChalLocalizer.localize(
                englishDonorDoc = donorToLocalize.document,
                donorProvenance = donorToLocalize.donor,
                alignmentProof = donorToLocalize.alignment,
                config = bolChalConfig,
                transport = bolChalTransport,
                videoDurationMs = lookupSpec.expectedDurationMs,
            )
            sessionMutex.withLock {
                localizedResult.fold(
                    onSuccess = { localized ->
                        val dedupKey = localized.provenance.diversityKey to localized.document.contentSha256
                        if (seenUrduDiversityAndContent.add(dedupKey)) {
                            emitBakedUrduTrackLocked(localized)
                            admittedUrduTracks.add(localized)
                            localizedDonorSelected = localized.donor
                        }
                    },
                    onFailure = { err ->
                        rejectedTracks.add(
                            VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(
                                err.message ?: "bolchal_localization_failed"
                            )
                        )
                    },
                )
            }
        }

        return sessionMutex.withLock {
            SessionCompletionReport(
                emittedUrduCandidates = admittedUrduTracks.toList(),
                rejectedCandidates = rejectedTracks.toList(),
                emittedProviderAnchors = emittedAnchorCount,
                localizedDonorUsed = localizedDonorSelected,
                storedCasFiles = storedCasOutputs.toList(),
            )
        }
    }

    fun rankedEnglishDonorsSnapshot(): List<BufferedEnglishDonor> {
        return englishDonors.sortedWith(englishDonorComparator())
    }

    private suspend fun ingestDecodedUrduLocked(
        decodedText: String,
        provenance: ProvenanceIdentity,
        meta: CatalogueCandidateMeta?,
    ): VerifiedSubtitleCandidate {
        val referenceEnglish = selectBestEnglishDonorLocked()?.document
        val initialProof = when (provenance) {
            is ProvenanceIdentity.StreamProvider -> AlignmentProof(
                warp = SubtitleTimeWarp.Identity,
                anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                syncConfidence = 1.0,
                medianOnsetResidualMs = 0,
                p95OnsetResidualMs = 0,
                splitCount = 0,
            )
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
                val dedupKey = gated.provenance.diversityKey to gated.document.contentSha256
                if (mode.wantsUrduTracks && seenUrduDiversityAndContent.add(dedupKey)) {
                    emitBakedUrduTrackLocked(gated)
                    admittedUrduTracks.add(gated)
                }
                return gated
            }
            is VerifiedSubtitleCandidate.AlignedHumanCatalogue -> {
                val bestStreamAnchor = bestStreamAnchorDonorLocked()
                if (bestStreamAnchor != null) {
                    return alignAndEmitHumanCatalogueUrduLocked(gated, bestStreamAnchor)
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
    ): VerifiedSubtitleCandidate {
        val parsed = CueDocument.parse(decodedText, SubtitleLanguage.ENGLISH).getOrElse { err ->
            val rej = VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(err.message ?: "english_parse_failed")
            rejectedTracks.add(rej)
            return rej
        }

        return when (provenance) {
            is ProvenanceIdentity.StreamProvider -> {
                val proof = AlignmentProof(
                    warp = SubtitleTimeWarp.Identity,
                    anchorKind = AlignmentAnchorKind.NATIVE_STREAM_PACK,
                    syncConfidence = 1.0,
                    medianOnsetResidualMs = 0,
                    p95OnsetResidualMs = 0,
                    splitCount = 0,
                )
                val donor = DonorProvenance.StreamDonor(provenance.provider)
                val rank = provenance.provider.rankFor(lookupSpec.isAnime)
                englishDonors.add(
                    BufferedEnglishDonor(
                        donor = donor,
                        document = parsed,
                        alignment = proof,
                        priorityTier = DONOR_TIER_STREAM_PROVIDER,
                        intraTierScore = rank,
                        isSubtitleCatOrigFallback = false,
                        sourceSubtitleFile = sourceSub,
                    )
                )

                val shouldEmitAnchor = shouldForwardProviderAnchor()
                if (shouldEmitAnchor && sourceSub != null) {
                    val anchorDedup = provenance.provider to parsed.contentSha256
                    if (seenProviderAnchorContent.add(anchorDedup)) {
                        val mergedHeaders = LinkedHashMap<String, String>()
                        sourceSub.headers?.let { mergedHeaders.putAll(it) }
                        mergedHeaders.putAll(
                            ProvenanceCodec.encodeHeaders(
                                provenance = provenance,
                                syncProof = proof,
                                syncRole = ProvenanceCodec.SYNC_ROLE_PROVIDER_ANCHOR,
                            )
                        )
                        val label = if (sourceSub.lang.isNotBlank()) sourceSub.lang else "English [${provenance.provider.displayBadge}]"
                        subtitleCallback(
                            newSubtitleFile(label, sourceSub.url) {
                                this.headers = mergedHeaders
                            }
                        )
                        emittedAnchorCount++
                    }
                } else if (mode == SessionSubtitleMode.DEFAULT && sourceSub != null) {
                    val mergedHeaders = LinkedHashMap<String, String>()
                    sourceSub.headers?.let { mergedHeaders.putAll(it) }
                    mergedHeaders.putAll(ProvenanceCodec.encodeHeaders(provenance, proof))
                    subtitleCallback(
                        newSubtitleFile(sourceSub.lang.ifBlank { "English" }, sourceSub.url) {
                            this.headers = mergedHeaders
                        }
                    )
                }

                if (pendingCatalogueUrdu.isNotEmpty()) {
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
                val donorAlignment = if (bestStreamAnchor != null) {
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
                } else {
                    AlignmentProof(
                        warp = SubtitleTimeWarp.Identity,
                        anchorKind = AlignmentAnchorKind.UNANCHORED,
                        syncConfidence = 0.55,
                        medianOnsetResidualMs = 0,
                        p95OnsetResidualMs = 0,
                        splitCount = 0,
                    )
                }

                englishDonors.add(
                    BufferedEnglishDonor(
                        donor = DonorProvenance.CatalogueDonor(provenance.catalogue),
                        document = parsed,
                        alignment = donorAlignment,
                        priorityTier = tier,
                        intraTierScore = intraScore,
                        isSubtitleCatOrigFallback = isSubtitleCatOrigFallback,
                        sourceSubtitleFile = sourceSub,
                    )
                )

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

    private suspend fun flushPendingCatalogueUrduLocked() {
        if (pendingCatalogueUrdu.isEmpty()) return
        val bestAnchor = bestStreamAnchorDonorLocked()
        val entries = pendingCatalogueUrdu.toList()
        pendingCatalogueUrdu.clear()

        for (entry in entries) {
            if (admittedUrduTracks.size >= targetUrduDiversityCount) continue
            if (bestAnchor != null) {
                alignAndEmitHumanCatalogueUrduLocked(entry.candidate, bestAnchor)
            } else {
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
                    admittedUrduTracks.size < targetUrduDiversityCount &&
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
        val stored = casStore.writeAlignedSubtitle(
            doc = admitted.document,
            warp = admitted.alignment.warp,
            videoDurationMs = lookupSpec.expectedDurationMs,
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

    private fun bestStreamAnchorDonorLocked(): BufferedEnglishDonor? =
        englishDonors
            .asSequence()
            .filter { it.donor is DonorProvenance.StreamDonor }
            .maxByOrNull { it.intraTierScore }

    private fun selectBestEnglishDonorLocked(): BufferedEnglishDonor? =
        englishDonors.minWithOrNull(englishDonorComparator())

    private fun englishDonorComparator(): Comparator<BufferedEnglishDonor> =
        compareByDescending<BufferedEnglishDonor> { it.priorityTier }
            .thenByDescending { !it.isSubtitleCatOrigFallback }
            .thenByDescending { it.intraTierScore }
            .thenByDescending { it.document.cues.size }

    private fun shouldForwardProviderAnchor(): Boolean =
        mode == SessionSubtitleMode.BILINGUAL_DOWNLOAD ||
            SubtitleModeBridge.isBilingualDownloadCallback(callbackIdentityToken ?: subtitleCallback) ||
            (mode == SessionSubtitleMode.URDU && SubtitleModeBridge.isVaultV2Enabled())

    private fun resolveRawSubtitleProvenance(
        sub: SubtitleFile,
        rawSourceHint: String?,
        headers: Map<String, String>,
    ): ProvenanceIdentity {
        if (!rawSourceHint.isNullOrBlank()) {
            StreamProviderKey.fromTextOrHost(rawSourceHint)?.let { providerKey ->
                val isTranslated = headers[ProvenanceCodec.HEADER_ORIGIN].equals(ProvenanceCodec.ORIGIN_BOLCHAL, true) ||
                    headers[ProvenanceCodec.HEADER_SOURCE]?.startsWith(ProvenanceCodec.SOURCE_URDU_TRANSLATED, true) == true
                if (!isTranslated) {
                    return ProvenanceIdentity.StreamProvider(providerKey)
                }
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
    }
}
