package com.phisher98.subtitles

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

@JvmInline
value class CueId(val value: Int) : Comparable<CueId> {
    override fun compareTo(other: CueId): Int = value.compareTo(other.value)
}

@JvmInline
value class ContentSha256 private constructor(val hex: String) {
    init {
        require(hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ContentSha256 requires 64 lowercase hex chars"
        }
    }

    override fun toString(): String = hex

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = CharArray(64)
            for (i in digest.indices) {
                val v = digest[i].toInt() and 0xFF
                out[i * 2] = HEX_CHARS[v ushr 4]
                out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
            }
            return String(out)
        }

        fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())

        fun ofBytes(bytes: ByteArray): ContentSha256 = ContentSha256(sha256Hex(bytes))

        fun ofUtf8(text: String): ContentSha256 = ofBytes(text.encodeToByteArray())

        fun parseOrNull(raw: String?): ContentSha256? {
            val trimmed = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (trimmed.length != 64) return null
            for (ch in trimmed) {
                if (ch !in '0'..'9' && ch !in 'a'..'f') return null
            }
            return ContentSha256(trimmed)
        }
    }
}

@JvmInline
value class LatinEnglishText private constructor(val text: String) {
    init {
        // Splicing Nastaliq into sl=en&tl=ur NMT requests corrupts encoder cross-attention.
        require(!containsArabicOrNastaliq(text)) {
            "LatinEnglishText cannot contain Arabic or Nastaliq script characters"
        }
    }

    val isBlank: Boolean get() = text.isBlank()

    override fun toString(): String = text

    companion object {
        private val ASS_TAG_REGEX = Regex("""\{[^}]*\}""")
        private val HTML_BREAK_REGEX = Regex("""(?i)<br\s*/?>""")
        private val HTML_NON_ITALIC_TAG_REGEX = Regex("""(?i)</?(?!i\b)[a-z0-9:_-]+(?:\s+[^>]*)?>""")
        private val HTML_ALL_TAG_REGEX = Regex("""<[^>]*>""")
        private val MUSIC_LINE_REGEX = Regex("""(?m)^\s*[♪♫♬♩].*[♪♫♬♩]?\s*$""")
        private val MUSIC_GLYPH_REGEX = Regex("""[♪♫♬♩]+""")
        private val BRACKET_SDH_REGEX = Regex("""\[[^\]\n]{1,90}\]""")
        private val PAREN_PURE_LINE_SDH_REGEX = Regex("""(?m)^\s*\([^)\n]{1,80}\)\s*$""")
        private val PAREN_INLINE_SDH_REGEX = Regex(
            """\((?:(?i:[^)\n]{0,45}\b(?:sighs?|laughs?|gasps?|groans?|grunts?|chuckles?|coughs?|sniffs?|sobs?|screams?|whispers?|shouts?|yells?|panting|breathing|music|applause|cheering|footsteps|door|phone|ringing|buzzing|beeping|clicking|knocking|gunshot|gunfire|explosion|thunder|wind|rain|siren|horn|barking|crying|weeping|clears\s+throat|inaudible|indistinct|speaking|singing|narrator|voiceover|on\s+tv|on\s+radio)\b[^)\n]{0,35})|(?=[A-Z0-9\s:'_-]*[A-Z])(?=[A-Z0-9\s:'_-]*[\s:])[A-Z0-9\s:'_-]{4,45})\)"""
        )
        private val SPEAKER_LABEL_REGEX = Regex("""(?m)^(\s*(?:-\s*)?)[A-Z][A-Z0-9 .#'_-]{0,24}:\s*""")
        private val MULTI_SPACE_REGEX = Regex("""[^\S\n]+""")
        private val BLANK_LINES_REGEX = Regex("""\n{2,}""")

        private data class IdiomRewrite(val pattern: Regex, val replacement: String)

        private val IDIOM_REWRITES = listOf(
            IdiomRewrite(Regex("""(?i)\bwhat\s+the\s+hell\b"""), "what on earth"),
            IdiomRewrite(Regex("""(?i)\bhold\s+on\b"""), "wait a moment"),
            IdiomRewrite(Regex("""(?i)\bare\s+you\s+kidding(?:\s+me)?\b"""), "are you joking"),
            IdiomRewrite(Regex("""(?i)\byou(?:'re|\u2019re|\s+are)?\s+kidding\s+me\b"""), "you are joking"),
            IdiomRewrite(Regex("""(?i)\bno\s+way\b"""), "impossible"),
            IdiomRewrite(Regex("""(?i)\bmy\s+bad\b"""), "my mistake"),
            IdiomRewrite(Regex("""(?i)\bbeat\s+it\b"""), "go away"),
        )

        fun isArabicOrNastaliqCodePoint(codePoint: Int): Boolean =
            codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF

        fun containsArabicOrNastaliq(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val cp = value.codePointAt(i)
                if (isArabicOrNastaliqCodePoint(cp)) return true
                i += Character.charCount(cp)
            }
            return false
        }

        private fun isBidiControl(cp: Int): Boolean =
            cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069

        private fun stripNastaliqAndBidi(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val cp = input.codePointAt(i)
                if (!isArabicOrNastaliqCodePoint(cp) && !isBidiControl(cp)) {
                    sb.appendCodePoint(cp)
                }
                i += Character.charCount(cp)
            }
            return sb.toString()
        }

        internal fun decodeHtmlEntities(raw: String): String =
            raw.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")

        private fun applyCasePreservingRewrite(source: String, rewrite: IdiomRewrite): String =
            rewrite.pattern.replace(source) { match ->
                val matched = match.value
                val firstAlpha = matched.firstOrNull { it.isLetter() }
                if (firstAlpha != null && firstAlpha.isUpperCase()) {
                    rewrite.replacement.replaceFirstChar { it.uppercaseChar() }
                } else {
                    rewrite.replacement
                }
            }

        fun sanitize(rawText: String, preserveItalicTags: Boolean = false): LatinEnglishText {
            var working = rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\\N", "\n")
                .replace("\\n", "\n")
                .replace("\\h", " ")
            working = ASS_TAG_REGEX.replace(working, "")
            working = HTML_BREAK_REGEX.replace(working, "\n")
            working = if (preserveItalicTags) {
                HTML_NON_ITALIC_TAG_REGEX.replace(working, "")
            } else {
                HTML_ALL_TAG_REGEX.replace(working, "")
            }
            working = decodeHtmlEntities(working)
            working = stripNastaliqAndBidi(working)
            working = MUSIC_LINE_REGEX.replace(working, "")
            working = MUSIC_GLYPH_REGEX.replace(working, "")
            working = BRACKET_SDH_REGEX.replace(working, " ")
            working = PAREN_PURE_LINE_SDH_REGEX.replace(working, "")
            working = PAREN_INLINE_SDH_REGEX.replace(working, " ")
            working = SPEAKER_LABEL_REGEX.replace(working) { m ->
                val prefix = m.groupValues[1]
                if (prefix.contains('-')) "- " else ""
            }
            for (rewrite in IDIOM_REWRITES) {
                working = applyCasePreservingRewrite(working, rewrite)
            }
            working = working.lines()
                .map { MULTI_SPACE_REGEX.replace(it, " ").trim() }
                .filter { it.isNotEmpty() && it != "-" && it != "<i></i>" }
                .joinToString("\n")
            working = BLANK_LINES_REGEX.replace(working, "\n").trim()
            return LatinEnglishText(working)
        }

        fun normalizeFromRawCue(
            rawText: String,
            preserveItalicTags: Boolean = false,
        ): Result<LatinEnglishText> {
            val cleaned = sanitize(rawText, preserveItalicTags)
            return if (cleaned.text.isBlank()) {
                Result.failure(IllegalArgumentException("Cue empty after SDH/tag/Nastaliq normalization"))
            } else {
                Result.success(cleaned)
            }
        }
    }
}

enum class SubtitleLanguage(val ietfTag: String, val displayName: String) {
    ENGLISH("en", "English"),
    URDU("ur", "Urdu"),
    ;

    companion object {
        fun fromTagOrName(raw: String?): SubtitleLanguage? {
            val norm = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when {
                norm == "ur" || norm == "urd" || norm.startsWith("ur-") || norm.contains("urdu") || norm.contains("اردو") -> URDU
                norm == "en" || norm == "eng" || norm.startsWith("en-") || norm.contains("english") -> ENGLISH
                else -> null
            }
        }
    }
}

enum class StreamProviderKey(
    val wireId: String,
    val displayBadge: String,
    val universalRank: Int,
    val animeRank: Int,
    val isAnimeExclusive: Boolean = false,
    val cdnHostTokens: List<String> = emptyList(),
) {
    VIDLINK("vidlink", "VidLink", universalRank = 100, animeRank = 114, cdnHostTokens = listOf("vidlink", "megacloud", "megafiles", "vodvidl")),
    MEOWTV("meowtv", "MeowTV", universalRank = 98, animeRank = 98, cdnHostTokens = listOf("meowtv", "hscow", "vidjoy", "fcxmb", "krico443jez", "rezesubs", "fastcloud")),
    CINEBY("cineby", "Cineby", universalRank = 95, animeRank = 113, cdnHostTokens = listOf("cineby", "paleoak", "majorplay", "wecollege", "dramahot", "goldenkite", "finalsignal", "vidy.st")),
    CINESRC("cinesrc", "CineSrc", universalRank = 94, animeRank = 115, cdnHostTokens = listOf("cinesrc", "bright67", "lightspeed")),
    VIDFAST("vidfast", "VidFast", universalRank = 93, animeRank = 93, cdnHostTokens = listOf("vidfast", "zenoak")),
    MEGAPLAY("megaplay", "MegaPlay", universalRank = 92, animeRank = 126, cdnHostTokens = listOf("megaplay", "broforgotsave", "nexabloom", "embermeadow", "ivorysummit")),
    VIDRIFT("vidrift", "VidRift", universalRank = 91, animeRank = 91, cdnHostTokens = listOf("vidrift", "reelvault", "bigbangcymru", "bigbangobserver", "deton444eik")),
    ANIZONE("anizone", "AniZone", universalRank = 89, animeRank = 130, isAnimeExclusive = true, cdnHostTokens = listOf("anizone", "xin-cdn", "vid-cdn")),
    ANIMEPAHE("animepahe", "AnimePahe", universalRank = 88, animeRank = 128, isAnimeExclusive = true, cdnHostTokens = listOf("animepahe", "uwucdn")),
    ZOKOANIME("zokoanime", "ZokoAnime", universalRank = 88, animeRank = 124, isAnimeExclusive = true, cdnHostTokens = listOf("zokoanime")),
    KICKASSANIME("kickassanime", "KickAssAnime", universalRank = 88, animeRank = 122, isAnimeExclusive = true, cdnHostTokens = listOf("kickassanime", "kaalt")),
    ANIMEX("animex", "Animex", universalRank = 87, animeRank = 120, isAnimeExclusive = true, cdnHostTokens = listOf("animex", "krussdomi", "echovideo", "zokoanimevideo", "dpopdrop89", "megavid")),
    FEBBOX_PACK("febbox", "FebBox", universalRank = 96, animeRank = 96, cdnHostTokens = listOf("febbox", "showbox", "superstream", "hakunaymatata")),
    ;

    val displayLabel: String get() = displayBadge

    fun rankFor(isAnime: Boolean): Int = if (isAnime) animeRank else universalRank

    companion object {
        val FEBBOX: StreamProviderKey get() = FEBBOX_PACK

        private val FEBBOX_WIRE_ALIASES = setOf("febbox", "febbox_pack", "superstream", "showbox", "hakunaymatata")

        fun fromWireId(raw: String?): StreamProviderKey? {
            val norm = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (norm.isEmpty()) return null
            if (norm in FEBBOX_WIRE_ALIASES) return FEBBOX_PACK
            return entries.firstOrNull {
                it.wireId == norm ||
                    it.displayBadge.lowercase(Locale.ROOT) == norm ||
                    it.name.lowercase(Locale.ROOT) == norm
            }
        }

        fun matchInText(text: String?): StreamProviderKey? = fromTextOrHost(text)

        fun fromTextOrHost(text: String?): StreamProviderKey? {
            val norm = text?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (norm.isEmpty()) return null
            fromWireId(norm)?.let { return it }
            for (entry in entries) {
                if (containsTokenBoundary(norm, entry.wireId) ||
                    containsTokenBoundary(norm, entry.displayBadge.lowercase(Locale.ROOT))
                ) {
                    return entry
                }
            }
            for (entry in entries) {
                if (entry.cdnHostTokens.any { norm.contains(it) }) {
                    return entry
                }
            }
            return null
        }
    }
}

enum class CatalogueSourceKey(
    val wireId: String,
    val displayBadge: String,
    val intraCatalogueRank: Int,
    val wireAliases: List<String> = emptyList(),
    val hostTokens: List<String> = emptyList(),
) {
    WYZIE("wyzie", "Wyzie", 110, wireAliases = listOf("wyziesub"), hostTokens = listOf("wyzie")),
    SUBTITLE_API("subtitleapi", "SubtitleAPI", 105, wireAliases = listOf("subtitle-api", "subtitlesapi"), hostTokens = listOf("subtitleapi")),
    OPENSUBTITLES("opensubtitles", "OpenSubtitles", 104, wireAliases = listOf("opensub"), hostTokens = listOf("opensubtitles")),
    STREMIO("stremio", "Stremio", 104, hostTokens = listOf("stremio")),
    SUBDL("subdl", "SubDL", 104, hostTokens = listOf("subdl", "dlsubdlcom", "apisubdlcom", "api3subdlcom")),
    ADDIC7ED("addic7ed", "Addic7ed", 103, hostTokens = listOf("addic7ed")),
    YIFY("yify", "YIFY", 103, wireAliases = listOf("yifysub", "yts", "yts-subs", "ytssubs"), hostTokens = listOf("yify", "yifysubtitles", "ytssubs")),
    SUBSOURCE("subsource", "SubSource", 103, hostTokens = listOf("subsource", "apisubsourcenet")),
    SUBSENSE("subsense", "SubSense", 103, hostTokens = listOf("subsense", "subsensenepirawcom")),
    WATCHSOMUCH("watchsomuch", "WatchSoMuch", 102, hostTokens = listOf("watchsomuch")),
    SUBF2M("subf2m", "Subf2m", 101, hostTokens = listOf("subf2m", "subf2mco")),
    SUBTITLECAT("subtitlecat", "SubtitleCat", 99, hostTokens = listOf("subtitlecat")),
    ;

    val displayLabel: String get() = displayBadge
    val catalogueQualityRank: Int get() = intraCatalogueRank

    companion object {
        fun fromWireId(raw: String?): CatalogueSourceKey? {
            val norm = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (norm.isEmpty()) return null
            return entries.firstOrNull { key ->
                key.wireId == norm ||
                    key.displayBadge.lowercase(Locale.ROOT) == norm ||
                    key.wireAliases.any { it == norm }
            }
        }

        fun fromTextOrHost(text: String?): CatalogueSourceKey? {
            val norm = text?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (norm.isEmpty()) return null
            fromWireId(norm)?.let { return it }
            for (entry in entries) {
                if (containsTokenBoundary(norm, entry.wireId) ||
                    containsTokenBoundary(norm, entry.displayBadge.lowercase(Locale.ROOT)) ||
                    entry.wireAliases.any { containsTokenBoundary(norm, it) }
                ) {
                    return entry
                }
            }
            for (entry in entries) {
                if (entry.hostTokens.any { norm.contains(it) }) {
                    return entry
                }
            }
            return null
        }
    }
}

private fun containsTokenBoundary(haystackLower: String, needleLower: String): Boolean {
    if (needleLower.isEmpty()) return false
    var idx = haystackLower.indexOf(needleLower)
    while (idx >= 0) {
        val leftOk = idx == 0 || !haystackLower[idx - 1].isLetterOrDigit()
        val end = idx + needleLower.length
        val rightOk = end >= haystackLower.length || !haystackLower[end].isLetterOrDigit()
        if (leftOk && rightOk) return true
        idx = haystackLower.indexOf(needleLower, idx + 1)
    }
    return false
}

enum class LocalizationTierKey(val wireId: String, val badgeLabel: String, val tierRank: Int) {
    LLM_SCENE("llm-scene", "BolChal AI", tierRank = 3),
    KEYLESS_BOLCHAL_NMT("keyless-bolchal", "BolChal", tierRank = 2),
    MLKIT_OFFLINE("mlkit-offline", "Offline AI", tierRank = 1),
    ;

    val displayBadge: String get() = badgeLabel

    companion object {
        fun fromWireOrText(raw: String?): LocalizationTierKey? {
            val norm = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
            return when {
                norm == LLM_SCENE.wireId || norm.contains("bolchal ai") || norm.contains("llm") -> LLM_SCENE
                norm == MLKIT_OFFLINE.wireId || norm.contains("offline ai") || norm.contains("mlkit") -> MLKIT_OFFLINE
                norm == KEYLESS_BOLCHAL_NMT.wireId || norm.contains("bolchal") || norm.contains("urduai") || norm.contains("urdu-neural-fallback") -> KEYLESS_BOLCHAL_NMT
                else -> null
            }
        }
    }
}

sealed interface DonorProvenance {
    val wireDonorId: String
    val displayBadge: String
    val displayLabel: String get() = displayBadge
    val isStreamSyncedDonor: Boolean

    data class StreamDonor(val provider: StreamProviderKey) : DonorProvenance {
        override val wireDonorId: String get() = "stream:${provider.wireId}"
        override val displayBadge: String get() = provider.displayBadge
        override val isStreamSyncedDonor: Boolean get() = true
    }

    data class CatalogueDonor(val catalogue: CatalogueSourceKey) : DonorProvenance {
        override val wireDonorId: String get() = "cat:${catalogue.wireId}"
        override val displayBadge: String get() = catalogue.displayBadge
        override val isStreamSyncedDonor: Boolean get() = false
    }
}

sealed interface ProvenanceIdentity {
    val diversityKey: String
    val sidecarFileBadge: String

    data class StreamProvider(val provider: StreamProviderKey) : ProvenanceIdentity {
        override val diversityKey: String get() = "stream:${provider.wireId}"
        override val sidecarFileBadge: String get() = provider.displayBadge
    }

    data class HumanCatalogue(
        val catalogue: CatalogueSourceKey,
        val variantTag: String? = null,
    ) : ProvenanceIdentity {
        override val diversityKey: String
            get() = if (variantTag.isNullOrBlank()) {
                "cat:${catalogue.wireId}"
            } else {
                "cat:${catalogue.wireId}:${variantTag.lowercase(Locale.ROOT)}"
            }
        override val sidecarFileBadge: String
            get() = if (variantTag.isNullOrBlank()) {
                catalogue.displayBadge
            } else {
                "${catalogue.displayBadge} · $variantTag"
            }
    }

    data class BolChalTranslated(
        val donor: DonorProvenance,
        val engine: LocalizationTierKey = LocalizationTierKey.KEYLESS_BOLCHAL_NMT,
        val editionTag: String? = null,
    ) : ProvenanceIdentity {
        override val diversityKey: String
            get() = if (editionTag.isNullOrBlank()) {
                "bolchal:${donor.wireDonorId}"
            } else {
                "bolchal:${donor.wireDonorId}:${editionTag.lowercase(Locale.ROOT)}"
            }
        override val sidecarFileBadge: String
            get() = if (editionTag.isNullOrBlank()) {
                "UrduAI ${donor.displayBadge}"
            } else {
                "UrduAI ${donor.displayBadge} · $editionTag"
            }
    }

    data class UnknownExternal(val registrableHost: String) : ProvenanceIdentity {
        override val diversityKey: String get() = "ext:$registrableHost"
        override val sidecarFileBadge: String get() = registrableHost
    }
}

object ProvenanceCodec {
    const val HEADER_SOURCE = "X-StreamPlay-Source"
    const val HEADER_ORIGIN = "X-StreamPlay-Origin"
    const val HEADER_DONOR = "X-StreamPlay-Donor"
    const val HEADER_ENGINE = "X-StreamPlay-Engine"
    const val HEADER_EDITION = "X-StreamPlay-Edition"
    const val HEADER_VARIANT = "X-StreamPlay-Variant"
    const val HEADER_SYNC_ROLE = "X-StreamPlay-Sync-Role"
    const val HEADER_FOREIGN_SIDECAR = "X-StreamPlay-Foreign-Sidecar"
    const val HEADER_SYNC = "X-StreamPlay-Sync"
    const val HEADER_SYNC_CERT = HEADER_SYNC
    const val HEADER_PROGRESSIVE = "X-StreamPlay-Progressive"

    const val SOURCE_URDU_TRANSLATED = "urdu-neural-fallback"
    const val ORIGIN_STREAM = "stream"
    const val ORIGIN_NATIVE_ALIAS = "native"
    const val ORIGIN_CATALOGUE = "catalogue"
    const val ORIGIN_BOLCHAL = "bolchal"
    const val SYNC_ROLE_PROVIDER_ANCHOR = "provider-anchor"

    private val TRANSLATED_MARKER_REGEX = Regex(
        """(?i)(?:\burdu\s*ai\b|urdu-neural-fallback(?::[a-z0-9_.-]+)?|\bbol[\s-]*chal(?:\s+ai)?\b|\bcontextual\s+spoken\s+ai\b|\boffline\s+ai\b)"""
    )

    private fun Map<String, String>.headerIgnoreCase(name: String): String? =
        this[name] ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun isProviderAnchorHeader(headers: Map<String, String>): Boolean =
        headers.headerIgnoreCase(HEADER_SYNC_ROLE)?.trim()?.equals(SYNC_ROLE_PROVIDER_ANCHOR, ignoreCase = true) == true

    fun isForeignSidecarHeader(headers: Map<String, String>?): Boolean =
        headers?.headerIgnoreCase(HEADER_FOREIGN_SIDECAR)?.trim()?.let {
            it.equals("true", ignoreCase = true) || it == "1"
        } == true

    fun encodeSyncProof(proof: AlignmentProof): String =
        "${proof.anchorKind.wireId}|${String.format(Locale.US, "%.4f", proof.syncConfidence.coerceIn(0.0, 1.0))}|" +
            "${proof.medianOnsetResidualMs}|${proof.p95OnsetResidualMs}|${proof.splitCount}|${proof.warp.serialize()}"

    fun decodeSyncProofOrNull(raw: String?): AlignmentProof? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parts = trimmed.split('|')
        if (parts.size != 3 && parts.size < 6) return null
        val kind = AlignmentAnchorKind.fromWireOrNull(parts[0]) ?: return null
        if (kind == AlignmentAnchorKind.UNANCHORED) return null
        val conf = parts[1].trim().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: return null
        if (parts.size == 3) {
            val warp = SubtitleTimeWarp.parse(parts[2].trim()).getOrNull() ?: return null
            return AlignmentProof(
                warp = warp,
                anchorKind = kind,
                syncConfidence = conf,
                medianOnsetResidualMs = 0,
                p95OnsetResidualMs = 0,
                splitCount = if (warp is SubtitleTimeWarp.PiecewiseLinear) (warp.knots.size - 1).coerceAtLeast(0) else 0,
            )
        }
        val medianMs = parts[2].trim().toIntOrNull() ?: return null
        val p95Ms = parts[3].trim().toIntOrNull() ?: return null
        val splits = parts[4].trim().toIntOrNull() ?: return null
        val warpSpec = parts.subList(5, parts.size).joinToString("|").trim()
        val warp = SubtitleTimeWarp.parse(warpSpec).getOrNull() ?: return null
        return AlignmentProof(
            warp = warp,
            anchorKind = kind,
            syncConfidence = conf,
            medianOnsetResidualMs = medianMs,
            p95OnsetResidualMs = p95Ms,
            splitCount = splits,
        )
    }

    fun encodeHeaders(
        provenance: ProvenanceIdentity,
        syncProof: AlignmentProof? = null,
        syncRole: String? = null,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>(8)
        when (provenance) {
            is ProvenanceIdentity.StreamProvider -> {
                out[HEADER_SOURCE] = provenance.provider.wireId
                out[HEADER_ORIGIN] = ORIGIN_STREAM
            }
            is ProvenanceIdentity.HumanCatalogue -> {
                out[HEADER_SOURCE] = provenance.catalogue.wireId
                out[HEADER_ORIGIN] = ORIGIN_CATALOGUE
                if (!provenance.variantTag.isNullOrBlank()) {
                    out[HEADER_VARIANT] = provenance.variantTag
                }
            }
            is ProvenanceIdentity.BolChalTranslated -> {
                out[HEADER_SOURCE] = SOURCE_URDU_TRANSLATED
                out[HEADER_ORIGIN] = ORIGIN_BOLCHAL
                out[HEADER_DONOR] = provenance.donor.wireDonorId
                out[HEADER_ENGINE] = provenance.engine.wireId
                if (!provenance.editionTag.isNullOrBlank()) {
                    out[HEADER_EDITION] = provenance.editionTag
                }
            }
            is ProvenanceIdentity.UnknownExternal -> {
                out[HEADER_SOURCE] = provenance.registrableHost.ifBlank { "external" }
                out[HEADER_ORIGIN] = "external"
            }
        }
        if (!syncRole.isNullOrBlank()) {
            out[HEADER_SYNC_ROLE] = syncRole
        }
        if (syncProof != null && syncProof.anchorKind != AlignmentAnchorKind.UNANCHORED) {
            out[HEADER_SYNC] = encodeSyncProof(syncProof)
        }
        return out
    }

    private fun parseDonorToken(donorHeader: String?, fallbackText: String): DonorProvenance {
        val headerNorm = donorHeader?.trim()?.lowercase(Locale.ROOT)
        if (!headerNorm.isNullOrEmpty()) {
            if (headerNorm.startsWith("stream:")) {
                StreamProviderKey.fromWireId(headerNorm.removePrefix("stream:"))?.let {
                    return DonorProvenance.StreamDonor(it)
                }
            }
            if (headerNorm.startsWith("cat:")) {
                CatalogueSourceKey.fromWireId(headerNorm.removePrefix("cat:"))?.let {
                    return DonorProvenance.CatalogueDonor(it)
                }
            }
            StreamProviderKey.fromTextOrHost(headerNorm)?.let { return DonorProvenance.StreamDonor(it) }
            CatalogueSourceKey.fromTextOrHost(headerNorm)?.let { return DonorProvenance.CatalogueDonor(it) }
        }
        val colonDonor = Regex("""(?i)urdu-neural-fallback:([a-z0-9_.-]+)""")
            .find(fallbackText)?.groupValues?.getOrNull(1)
        if (!colonDonor.isNullOrEmpty()) {
            StreamProviderKey.fromTextOrHost(colonDonor)?.let { return DonorProvenance.StreamDonor(it) }
            CatalogueSourceKey.fromTextOrHost(colonDonor)?.let { return DonorProvenance.CatalogueDonor(it) }
        }
        val stripped = TRANSLATED_MARKER_REGEX.replace(fallbackText, " ")
        StreamProviderKey.fromTextOrHost(stripped)?.let { return DonorProvenance.StreamDonor(it) }
        CatalogueSourceKey.fromTextOrHost(stripped)?.let { return DonorProvenance.CatalogueDonor(it) }
        return DonorProvenance.CatalogueDonor(CatalogueSourceKey.WYZIE)
    }

    fun decode(
        headers: Map<String, String> = emptyMap(),
        displayName: String = "",
        urlOrFileName: String = "",
    ): ProvenanceIdentity {
        val originHeader = headers.headerIgnoreCase(HEADER_ORIGIN)?.trim()?.lowercase(Locale.ROOT)
        val sourceHeader = headers.headerIgnoreCase(HEADER_SOURCE)?.trim()?.lowercase(Locale.ROOT)
        val donorHeader = headers.headerIgnoreCase(HEADER_DONOR)?.trim()
        val engineHeader = headers.headerIgnoreCase(HEADER_ENGINE)?.trim()
        val editionHeader = headers.headerIgnoreCase(HEADER_EDITION)?.trim()?.takeIf { it.isNotEmpty() }
        val variantHeader = headers.headerIgnoreCase(HEADER_VARIANT)?.trim()?.takeIf { it.isNotEmpty() }
        val combinedText = "$displayName $urlOrFileName".trim()

        val isTranslated = originHeader == ORIGIN_BOLCHAL ||
            sourceHeader?.startsWith(SOURCE_URDU_TRANSLATED) == true ||
            !donorHeader.isNullOrBlank() ||
            TRANSLATED_MARKER_REGEX.containsMatchIn(combinedText)

        if (isTranslated) {
            val donor = parseDonorToken(donorHeader, "${sourceHeader.orEmpty()} $combinedText")
            val tier = LocalizationTierKey.fromWireOrText(engineHeader)
                ?: LocalizationTierKey.fromWireOrText(combinedText)
                ?: LocalizationTierKey.KEYLESS_BOLCHAL_NMT
            return ProvenanceIdentity.BolChalTranslated(donor = donor, engine = tier, editionTag = editionHeader)
        }

        if (originHeader == ORIGIN_STREAM || originHeader == ORIGIN_NATIVE_ALIAS) {
            StreamProviderKey.fromTextOrHost(sourceHeader)?.let { return ProvenanceIdentity.StreamProvider(it) }
        }
        if (originHeader == ORIGIN_CATALOGUE) {
            CatalogueSourceKey.fromTextOrHost(sourceHeader)?.let {
                return ProvenanceIdentity.HumanCatalogue(it, variantTag = variantHeader)
            }
        }
        if (!sourceHeader.isNullOrBlank()) {
            StreamProviderKey.fromWireId(sourceHeader)?.let { return ProvenanceIdentity.StreamProvider(it) }
            CatalogueSourceKey.fromWireId(sourceHeader)?.let {
                return ProvenanceIdentity.HumanCatalogue(it, variantTag = variantHeader)
            }
        }

        CatalogueSourceKey.fromTextOrHost(displayName)?.let {
            return ProvenanceIdentity.HumanCatalogue(it, variantTag = variantHeader)
        }
        StreamProviderKey.fromTextOrHost(displayName)?.let { return ProvenanceIdentity.StreamProvider(it) }
        CatalogueSourceKey.fromTextOrHost(urlOrFileName)?.let {
            return ProvenanceIdentity.HumanCatalogue(it, variantTag = variantHeader)
        }
        StreamProviderKey.fromTextOrHost(urlOrFileName)?.let { return ProvenanceIdentity.StreamProvider(it) }

        return ProvenanceIdentity.UnknownExternal(extractRegistrableHost(urlOrFileName, displayName))
    }

    private fun extractRegistrableHost(urlOrFileName: String, displayName: String): String {
        val candidate = urlOrFileName.trim()
        if (candidate.contains("://")) {
            val host = runCatching { URI(candidate).host }.getOrNull()?.lowercase(Locale.ROOT)?.removePrefix("www.")
            if (!host.isNullOrBlank()) return host
        }
        val cleanName = displayName.trim().lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9._-]+"""), "-")
            .trim('-')
        return cleanName.ifEmpty { "external" }
    }
}

sealed interface EpisodeCoordinate {
    data class VerifiedSeasonEpisode(val season: Int, val episode: Int) : EpisodeCoordinate {
        init {
            require(season >= 0 && episode >= 1) { "Invalid S${season}E${episode}" }
        }
    }

    data class UnverifiedAnimeCour(
        val courTitles: List<String>,
        val episodeInCour: Int,
        val absoluteEpisode: Int? = null,
    ) : EpisodeCoordinate {
        init {
            require(courTitles.isNotEmpty() && episodeInCour >= 1) {
                "UnverifiedAnimeCour requires non-empty courTitles and episodeInCour >= 1"
            }
        }
    }

    data class UnverifiedEpisodeOnly(
        val episode: Int,
    ) : EpisodeCoordinate {
        init {
            require(episode >= 1) { "UnverifiedEpisodeOnly requires episode >= 1" }
        }
    }
}

sealed interface MediaLookupSpec {
    val imdbId: String?
    val tmdbId: Int?
    val titles: List<String>
    val expectedDurationMs: Long?
    val releaseHints: List<String>
    val isAnime: Boolean

    data class Movie(
        override val imdbId: String?,
        override val tmdbId: Int?,
        override val titles: List<String>,
        val releaseYear: Int?,
        override val expectedDurationMs: Long? = null,
        override val releaseHints: List<String> = emptyList(),
        override val isAnime: Boolean = false,
    ) : MediaLookupSpec

    data class Episode(
        override val imdbId: String?,
        override val tmdbId: Int?,
        val tvdbId: Int? = null,
        override val titles: List<String>,
        val seriesPremiereYear: Int?,
        val seasonAirYear: Int?,
        override val expectedDurationMs: Long? = null,
        override val releaseHints: List<String> = emptyList(),
        val coordinate: EpisodeCoordinate,
        override val isAnime: Boolean = false,
    ) : MediaLookupSpec
}

data class CueSpan(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "Cue endMs ($endMs) < startMs ($startMs)" }
    }

    val durationMs: Long get() = endMs - startMs
}

data class CueEntry(
    val id: CueId,
    val span: CueSpan,
    val text: String,
)

data class CueDocument(
    val language: SubtitleLanguage,
    val cues: List<CueEntry>,
    val timingSha256: ContentSha256,
    val contentSha256: ContentSha256,
    val dialogueFingerprint: ContentSha256 = computeDialogueFingerprint(cues, contentSha256),
) {
    companion object {
        private val SRT_VTT_ARROW_REGEX = Regex(
            """^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})(?:\s+.*)?$"""
        )
        private val ASS_TIME_REGEX = Regex("""^\s*(\d{1,3}):(\d{1,2}):(\d{1,2})[.:](\d{1,3})\s*$""")
        private val ASS_DRAWING_REGEX = Regex("""\{\\[^}]*\\p[1-9][^}]*\}""")
        private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*\}""")
        private val TTML_TICK_RATE_REGEX = Regex("""(?i)\btickRate\s*=\s*["'](\d+)["']""")
        private val TTML_FRAME_RATE_REGEX = Regex("""(?i)\bframeRate\s*=\s*["'](\d+(?:\.\d+)?)["']""")
        private val TTML_FRAME_RATE_MULT_REGEX = Regex("""(?i)\bframeRateMultiplier\s*=\s*["'](\d+)\s+(\d+)["']""")
        private val TTML_P_REGEX = Regex("""(?is)<p\b([^>]*)>(.*?)</p>""")
        private val TTML_ATTR_REGEX = Regex("""(?i)\b(begin|end|dur)\s*=\s*["']([^"']+)["']""")
        private val TTML_CLOCK_REGEX = Regex("""^(\d{1,3}):(\d{2}):(\d{2})(?:([.:])(\d{1,3}))?$""")
        private val TTML_OFFSET_REGEX = Regex("""^(\d+(?:\.\d+)?)(h|m|s|ms|f|t)$""", RegexOption.IGNORE_CASE)
        private val XML_BR_REGEX = Regex("""(?i)<br\s*/?>""")
        private val XML_TAG_REGEX = Regex("""<[^>]+>""")
        private val PROMO_WATERMARK_CUE_REGEX = Regex(
            """(?is)(?:you'?re\s+on\s+the\s+free\s+plan|sub\.wyzie\.(?:io|ru)|wyzie\.(?:io|ru)/\S*|advertise\s+your\s+product\s+or\s+brand|support\s+us\s+and\s+become\s+vip\s+member|(?:subtitles?\s+(?:by|downloaded\s+from)|sync(?:ed)?\s*(?:and|&)\s*corrected\s+by)\b[^\n]*(?:opensubtitles|addic7ed|subscene|yifysubtitles|subdl|subf2m)|^\s*(?:https?://)?(?:www\.)?(?:opensubtitles\.(?:org|com)|yifysubtitles\.(?:ch|org|com)|yts\.(?:mx|lt|am)|subdl\.com|subf2m\.co)\s*$)"""
        )

        fun isPromotionalWatermarkCue(text: String): Boolean {
            val cleaned = XML_TAG_REGEX.replace(ASS_OVERRIDE_REGEX.replace(text, ""), "").trim()
            return cleaned.isNotEmpty() && PROMO_WATERMARK_CUE_REGEX.containsMatchIn(cleaned)
        }

        fun computeDialogueFingerprint(cues: List<CueEntry>, fallbackSha: ContentSha256): ContentSha256 {
            if (cues.isEmpty()) return fallbackSha
            val sb = StringBuilder(cues.size * 32)
            for (cue in cues) {
                val plain = XML_TAG_REGEX.replace(ASS_OVERRIDE_REGEX.replace(cue.text, ""), "")
                for (ch in plain) {
                    val mapped = when (ch) {
                        '\u0643' -> '\u06A9'
                        '\u064A', '\u0649' -> '\u06CC'
                        '\u0647', '\u0629', '\u06D5' -> '\u06C1'
                        '\u06C0' -> '\u06C2'
                        '\u06D3' -> '\u06D2'
                        in 'A'..'Z' -> (ch.code + 32).toChar()
                        else -> ch
                    }
                    if (mapped.isLetterOrDigit()) {
                        sb.append(mapped)
                    }
                }
            }
            return if (sb.isEmpty()) fallbackSha else ContentSha256.ofUtf8(sb.toString())
        }

        fun fromCues(language: SubtitleLanguage, rawCues: List<CueEntry>): CueDocument {
            val sorted = rawCues
                .filter { it.span.durationMs > 0L && it.text.isNotBlank() && !isPromotionalWatermarkCue(it.text) }
                .sortedWith(compareBy<CueEntry> { it.span.startMs }.thenBy { it.span.endMs }.thenBy { it.id.value })
            val timingBuilder = StringBuilder(sorted.size * 20)
            val contentBuilder = StringBuilder(sorted.size * 64)
            for (cue in sorted) {
                timingBuilder.append(cue.span.startMs).append(':').append(cue.span.endMs).append('\n')
                contentBuilder.append(cue.span.startMs).append(':').append(cue.span.endMs)
                    .append(':').append(cue.text).append('\n')
            }
            val contentSha = ContentSha256.ofUtf8(contentBuilder.toString())
            return CueDocument(
                language = language,
                cues = sorted,
                timingSha256 = ContentSha256.ofUtf8(timingBuilder.toString()),
                contentSha256 = contentSha,
                dialogueFingerprint = computeDialogueFingerprint(sorted, contentSha),
            )
        }

        fun parse(rawDecodedText: String, language: SubtitleLanguage): Result<CueDocument> {
            val cleaned = rawDecodedText.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            if (cleaned.isBlank()) {
                return Result.failure(IllegalArgumentException("Empty subtitle document"))
            }
            val head = cleaned.take(4096)
            val parsedEntries = when {
                head.contains("[Events]", ignoreCase = true) ||
                    cleaned.contains("\nDialogue:", ignoreCase = true) ||
                    cleaned.startsWith("Dialogue:", ignoreCase = true) -> parseAssOrSsa(cleaned)
                (head.contains("<tt", ignoreCase = true) || head.contains("<body", ignoreCase = true)) &&
                    cleaned.contains("<p", ignoreCase = true) -> parseTtml(cleaned)
                else -> parseSrtOrWebVtt(cleaned)
            }
            val doc = fromCues(language, parsedEntries)
            return if (doc.cues.isEmpty()) {
                Result.failure(IllegalArgumentException("No valid timed cues parsed"))
            } else {
                Result.success(doc)
            }
        }

        private fun parseMillisFraction(frac: String): Long = when (frac.length) {
            1 -> frac.toLong() * 100L
            2 -> frac.toLong() * 10L
            else -> frac.take(3).toLong()
        }

        private fun parseSrtOrWebVtt(text: String): List<CueEntry> {
            val lines = text.split('\n')
            val out = ArrayList<CueEntry>(lines.size / 3 + 1)
            var i = 0
            var nextId = 0
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT", ignoreCase = true)) {
                    i++
                    continue
                }
                if (trimmed.startsWith("NOTE", ignoreCase = true) ||
                    trimmed.startsWith("STYLE", ignoreCase = true) ||
                    trimmed.startsWith("REGION", ignoreCase = true)
                ) {
                    i++
                    while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                    continue
                }
                val match = SRT_VTT_ARROW_REGEX.matchEntire(trimmed)
                if (match == null) {
                    i++
                    continue
                }
                val sh = match.groupValues[1].toLongOrNull() ?: 0L
                val sm = match.groupValues[2].toLong()
                val ss = match.groupValues[3].toLong()
                val sms = parseMillisFraction(match.groupValues[4])
                val eh = match.groupValues[5].toLongOrNull() ?: 0L
                val em = match.groupValues[6].toLong()
                val es = match.groupValues[7].toLong()
                val ems = parseMillisFraction(match.groupValues[8])
                val startMs = ((sh * 3600L + sm * 60L + ss) * 1000L) + sms
                val endMs = ((eh * 3600L + em * 60L + es) * 1000L) + ems
                i++
                val textLines = ArrayList<String>(3)
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    if (SRT_VTT_ARROW_REGEX.matches(lines[i].trim())) break
                    textLines.add(lines[i].trim())
                    i++
                }
                if (endMs > startMs && textLines.isNotEmpty()) {
                    val cueText = LatinEnglishText.decodeHtmlEntities(textLines.joinToString("\n")).trim()
                    if (cueText.isNotEmpty()) {
                        out.add(CueEntry(CueId(nextId++), CueSpan(startMs, endMs), cueText))
                    }
                }
            }
            return out
        }

        private fun parseAssTimestampMs(raw: String): Long? {
            val m = ASS_TIME_REGEX.matchEntire(raw) ?: return null
            val h = m.groupValues[1].toLongOrNull() ?: return null
            val min = m.groupValues[2].toLongOrNull() ?: return null
            val sec = m.groupValues[3].toLongOrNull() ?: return null
            val frac = m.groupValues[4]
            val ms = when (frac.length) {
                1 -> (frac.toLongOrNull() ?: return null) * 100L
                2 -> (frac.toLongOrNull() ?: return null) * 10L
                else -> (frac.take(3).toLongOrNull() ?: return null)
            }
            return ((h * 3600L + min * 60L + sec) * 1000L) + ms
        }

        private fun parseAssOrSsa(text: String): List<CueEntry> {
            val lines = text.split('\n')
            val out = ArrayList<CueEntry>()
            var inEvents = !text.contains("[Events]", ignoreCase = true)
            var startCol = 1
            var endCol = 2
            var textCol = 9
            var fieldCount = 10
            var nextId = 0

            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith(';')) continue
                if (line.startsWith('[') && line.endsWith(']')) {
                    inEvents = line.equals("[Events]", ignoreCase = true)
                    continue
                }
                if (!inEvents) continue
                if (line.startsWith("Format:", ignoreCase = true)) {
                    val cols = line.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                    if (cols.size >= 4) {
                        fieldCount = cols.size
                        startCol = cols.indexOf("start").takeIf { it >= 0 } ?: 1
                        endCol = cols.indexOf("end").takeIf { it >= 0 } ?: 2
                        textCol = cols.indexOf("text").takeIf { it >= 0 } ?: (cols.size - 1)
                    }
                    continue
                }
                if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
                val payload = line.substringAfter(':')
                val parts = payload.split(',', limit = fieldCount)
                if (parts.size <= maxOf(startCol, endCol, textCol)) continue
                val startMs = parseAssTimestampMs(parts[startCol]) ?: continue
                val endMs = parseAssTimestampMs(parts[endCol]) ?: continue
                if (endMs <= startMs) continue
                val rawBody = parts[textCol]
                if (ASS_DRAWING_REGEX.containsMatchIn(rawBody)) continue
                val body = ASS_OVERRIDE_REGEX.replace(rawBody, "")
                    .replace("\\N", "\n")
                    .replace("\\n", "\n")
                    .replace("\\h", " ")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")
                if (body.isNotEmpty()) {
                    out.add(CueEntry(CueId(nextId++), CueSpan(startMs, endMs), body))
                }
            }
            return out
        }

        private fun parseTtmlTimeMs(raw: String, tickRate: Long, frameRate: Double = 24.0): Long? {
            val trimmed = raw.trim()
            val safeFps = if (frameRate > 0.0) frameRate else 24.0
            TTML_CLOCK_REGEX.matchEntire(trimmed)?.let { m ->
                val h = m.groupValues[1].toLong()
                val min = m.groupValues[2].toLong()
                val sec = m.groupValues[3].toLong()
                val sep = m.groupValues[4]
                val frac = m.groupValues[5]
                val subSecMs = when {
                    frac.isEmpty() -> 0L
                    sep == ":" && frac.length <= 2 -> {
                        val frames = frac.toLongOrNull() ?: 0L
                        ((frames.toDouble() * 1_000.0) / safeFps).toLong()
                    }
                    else -> parseMillisFraction(frac)
                }
                return ((h * 3600L + min * 60L + sec) * 1000L) + subSecMs
            }
            TTML_OFFSET_REGEX.matchEntire(trimmed)?.let { m ->
                val value = m.groupValues[1].toDoubleOrNull() ?: return null
                return when (m.groupValues[2].lowercase(Locale.ROOT)) {
                    "h" -> (value * 3_600_000.0).toLong()
                    "m" -> (value * 60_000.0).toLong()
                    "s" -> (value * 1_000.0).toLong()
                    "ms" -> value.toLong()
                    "f" -> (value * (1_000.0 / safeFps)).toLong()
                    "t" -> ((value * 1_000.0) / tickRate.coerceAtLeast(1L).toDouble()).toLong()
                    else -> null
                }
            }
            return null
        }

        private fun parseTtml(text: String): List<CueEntry> {
            val tickRate = TTML_TICK_RATE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 10_000_000L
            val baseFrameRate = TTML_FRAME_RATE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 24.0
            val multMatch = TTML_FRAME_RATE_MULT_REGEX.find(text)
            val multNum = multMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val multDen = multMatch?.groupValues?.getOrNull(2)?.toDoubleOrNull()
            val effectiveFrameRate = if (multNum != null && multDen != null && multNum > 0.0 && multDen > 0.0) {
                baseFrameRate * (multNum / multDen)
            } else {
                baseFrameRate
            }
            val out = ArrayList<CueEntry>()
            var nextId = 0
            for (pMatch in TTML_P_REGEX.findAll(text)) {
                val attrs = pMatch.groupValues[1]
                val inner = pMatch.groupValues[2]
                var beginMs: Long? = null
                var endMs: Long? = null
                var durMs: Long? = null
                for (attr in TTML_ATTR_REGEX.findAll(attrs)) {
                    val name = attr.groupValues[1].lowercase(Locale.ROOT)
                    val parsed = parseTtmlTimeMs(attr.groupValues[2], tickRate, effectiveFrameRate) ?: continue
                    when (name) {
                        "begin" -> beginMs = parsed
                        "end" -> endMs = parsed
                        "dur" -> durMs = parsed
                    }
                }
                val start = beginMs ?: continue
                val end = endMs ?: durMs?.let { start + it } ?: continue
                if (end <= start) continue
                val body = LatinEnglishText.decodeHtmlEntities(
                    XML_TAG_REGEX.replace(XML_BR_REGEX.replace(inner, "\n"), "")
                ).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                if (body.isNotEmpty()) {
                    out.add(CueEntry(CueId(nextId++), CueSpan(start, end), body))
                }
            }
            return out
        }
    }
}

enum class AlignmentAnchorKind {
    NATIVE_STREAM_PACK,
    MATCHED_PROVIDER_SIDECAR,
    CROSS_PROVIDER_SIDECAR,
    SPARSE_AUDIO_VAD,
    LIVE_AUDIO_SINK_VAD,
    UNANCHORED;

    val wireId: String
        get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromWireOrNull(raw: String?): AlignmentAnchorKind? {
            val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }?.let { return it }
            return when (cleaned.lowercase(Locale.ROOT).replace('-', '_')) {
                "native_stream_pack", "native" -> NATIVE_STREAM_PACK
                "matched_provider_sidecar", "matched_provider" -> MATCHED_PROVIDER_SIDECAR
                "cross_provider_sidecar", "cross_provider" -> CROSS_PROVIDER_SIDECAR
                "sparse_audio_vad", "sparse_vad" -> SPARSE_AUDIO_VAD
                "live_audio_sink_vad", "live_vad" -> LIVE_AUDIO_SINK_VAD
                "unanchored", "none" -> UNANCHORED
                else -> null
            }
        }
    }
}

data class AlignmentProof(
    val warp: SubtitleTimeWarp,
    val anchorKind: AlignmentAnchorKind,
    val syncConfidence: Double,
    val medianOnsetResidualMs: Int,
    val p95OnsetResidualMs: Int,
    val splitCount: Int,
)

enum class AuthenticityGrade {
    AUTHENTIC_HUMAN,
    POST_EDITED_STIFF,
}

enum class MtSignalFamily {
    ZERO_CONTEXT_POLYSEMY_CALQUE,
    NEWSROOM_PASSIVE_AND_COPULA,
    PRONOUN_REGISTER_WHIPLASH,
    CONTINUATION_SHATTERING,
    BOLCHAL_PARTICLE_DEFICIT,
    CATALOGUE_MT_METADATA,
}

data class MtAuditResult(
    val mtDumpScore: Int,
    val triggeredFamilies: Set<MtSignalFamily>,
    val pronounWhiplashRate: Double,
    val bolChalParticleRate: Double,
)

data class BolChalQualityReport(
    val qeScore: Double,
    val cueSurvivalRatio: Double,
    val maxNormalDialogueCps: Double,
    val p95VisibleCps: Double,
    val mtResidualScore: Int,
    val regexPatchedRegisterCues: Int = 0,
    val registerVerifiedCueRatio: Double = 1.0,
)

sealed interface VerifiedSubtitleCandidate {

    sealed interface Admitted : VerifiedSubtitleCandidate {
        val language: SubtitleLanguage
        val provenance: ProvenanceIdentity
        val document: CueDocument
        val alignment: AlignmentProof
    }

    data class StreamSyncedNative(
        val provider: StreamProviderKey,
        override val language: SubtitleLanguage,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
    ) : Admitted {
        override val provenance: ProvenanceIdentity get() = ProvenanceIdentity.StreamProvider(provider)
    }

    data class AlignedHumanCatalogue(
        val catalogue: CatalogueSourceKey,
        override val language: SubtitleLanguage,
        val authenticity: AuthenticityGrade,
        val mtAudit: MtAuditResult?,
        val releaseAffinityScore: Int,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
        val variantTag: String? = null,
    ) : Admitted {
        override val provenance: ProvenanceIdentity
            get() = ProvenanceIdentity.HumanCatalogue(catalogue, variantTag)
    }

    data class BolChalLocalized(
        val donor: DonorProvenance,
        val tier: LocalizationTierKey,
        val qualityReport: BolChalQualityReport,
        override val document: CueDocument,
        override val alignment: AlignmentProof,
        val editionTag: String? = null,
    ) : Admitted {
        override val language: SubtitleLanguage get() = SubtitleLanguage.URDU
        override val provenance: ProvenanceIdentity
            get() = ProvenanceIdentity.BolChalTranslated(donor, tier, editionTag)
    }

    sealed interface Rejected : VerifiedSubtitleCandidate {
        data class MtDump(val audit: MtAuditResult, val uploader: String? = null) : Rejected
        data class HearingImpaired(val matchedMarker: String) : Rejected
        data class WrongScriptOrBrokenSmt(val reason: String) : Rejected
        data class ArchiveDecodeFailed(val reason: String) : Rejected
        data class BannedSourceOrUploader(val identifier: String) : Rejected
        data class EpisodeOrYearMismatch(val expected: String, val actualRelease: String) : Rejected
        data class SyncMismatch(val bestScore: Double, val residualMs: Int) : Rejected
    }
}

enum class SessionSubtitleMode {
    DEFAULT,
    URDU,
    BILINGUAL_DOWNLOAD,
    ;

    val wantsUrduTracks: Boolean get() = this == URDU || this == BILINGUAL_DOWNLOAD
    val wantsEnglishTracks: Boolean get() = this == DEFAULT || this == BILINGUAL_DOWNLOAD
}

data class ByokLlmConfig(
    val endpointUrl: String,
    val modelId: String,
    val apiKey: String,
) {
    val nonSecretConfigHash: String
        get() = ContentSha256.sha256Hex("${endpointUrl.trim()}|${modelId.trim()}").take(16)

    override fun toString(): String =
        "ByokLlmConfig(endpointUrl=$endpointUrl, modelId=$modelId, apiKey=<redacted>)"
}

object SubtitleModeBridge {
    const val BILINGUAL_CALLBACK_TOKEN = "cloudstream.bilingual_download_subtitles=true"
    const val PROP_VAULT_V2 = "cloudstream.subtitle.vault_v2"
    const val PROP_PROGRESSIVE_VTT = "cloudstream.subtitle.progressive_vtt"
    const val PROP_SUBTITLE_MODE = "cloudstream.subtitle.mode"
    const val PREFS_URDU_BOLCHAL = "cloudstream_urdu_bolchal"
    const val PREFS_LEGACY_MODE = "cloudstream_subtitle_mode_prefs"
    const val PROP_LLM_ENDPOINT = "cloudstream.urdu.llm.endpoint"
    const val PROP_LLM_MODEL = "cloudstream.urdu.llm.model"
    const val PROP_LLM_KEY = "cloudstream.urdu.llm.key"

    fun isBilingualDownloadCallback(subtitleCallback: Any?): Boolean =
        subtitleCallback?.toString()?.contains(BILINGUAL_CALLBACK_TOKEN) == true

    fun isVaultV2Enabled(): Boolean =
        System.getProperty(PROP_VAULT_V2)?.trim()?.equals("true", ignoreCase = true) == true

    fun isProgressiveVttEnabled(): Boolean =
        System.getProperty(PROP_PROGRESSIVE_VTT)?.trim()?.equals("true", ignoreCase = true) == true

    fun shouldEmitProviderAnchor(mode: SessionSubtitleMode, subtitleCallback: Any? = null): Boolean =
        isBilingualDownloadCallback(subtitleCallback) || (mode == SessionSubtitleMode.URDU && isVaultV2Enabled())

    fun resolveSessionMode(
        subtitleCallback: Any? = null,
        pluginFallbackUrduEnabled: Boolean = false,
        prefsLookup: ((prefsName: String, key: String) -> String?)? = null,
    ): SessionSubtitleMode {
        if (isBilingualDownloadCallback(subtitleCallback)) {
            return SessionSubtitleMode.BILINGUAL_DOWNLOAD
        }
        val sysProp = System.getProperty(PROP_SUBTITLE_MODE)?.trim()?.lowercase(Locale.ROOT)
        if (sysProp == "urdu" || sysProp == "ur") return SessionSubtitleMode.URDU
        if (sysProp == "default" || sysProp == "english" || sysProp == "en") return SessionSubtitleMode.DEFAULT

        val reader = prefsLookup ?: ::readAndroidSharedPrefOrNull
        val prefMode = (
            reader(PREFS_URDU_BOLCHAL, "subtitle_mode")
                ?: reader(PREFS_URDU_BOLCHAL, PROP_SUBTITLE_MODE)
                ?: reader(PREFS_LEGACY_MODE, "subtitle_mode")
            )?.trim()?.lowercase(Locale.ROOT)
        if (prefMode == "urdu" || prefMode == "ur") return SessionSubtitleMode.URDU
        if (prefMode == "default" || prefMode == "english" || prefMode == "en") return SessionSubtitleMode.DEFAULT

        return if (pluginFallbackUrduEnabled) SessionSubtitleMode.URDU else SessionSubtitleMode.DEFAULT
    }

    fun resolveByokLlmConfig(
        prefsLookup: ((prefsName: String, key: String) -> String?)? = null,
    ): ByokLlmConfig? {
        val reader = prefsLookup ?: ::readAndroidSharedPrefOrNull
        val endpoint = (
            System.getProperty(PROP_LLM_ENDPOINT)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, PROP_LLM_ENDPOINT)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "llm_endpoint")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "bolchal_llm_endpoint")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "endpoint")?.takeIf { it.isNotBlank() }
            )?.trim() ?: return null
        val model = (
            System.getProperty(PROP_LLM_MODEL)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, PROP_LLM_MODEL)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "llm_model")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "bolchal_llm_model")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "model")?.takeIf { it.isNotBlank() }
            )?.trim() ?: return null
        val key = (
            System.getProperty(PROP_LLM_KEY)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, PROP_LLM_KEY)?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "llm_api_key")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "bolchal_llm_api_key")?.takeIf { it.isNotBlank() }
                ?: reader(PREFS_URDU_BOLCHAL, "key")?.takeIf { it.isNotBlank() }
            )?.trim() ?: return null
        return ByokLlmConfig(endpointUrl = endpoint, modelId = model, apiKey = key)
    }

    private fun readAndroidSharedPrefOrNull(prefsName: String, key: String): String? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val app = activityThreadClass.getMethod("currentApplication").invoke(null) ?: return null
        val contextClass = Class.forName("android.content.Context")
        val prefs = contextClass.getMethod("getSharedPreferences", String::class.java, Int::class.javaPrimitiveType)
            .invoke(app, prefsName, 0) ?: return null
        val prefsClass = Class.forName("android.content.SharedPreferences")
        prefsClass.getMethod("getString", String::class.java, String::class.java)
            .invoke(prefs, key, null) as? String
    }.getOrNull()
}
