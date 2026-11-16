package com.phisher98.subtitles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

enum class FpsRatio(val id: String, val num: Long, val den: Long) {
    UNITY("1:1", 1L, 1L),
    FILM_24_TO_NTSC_23976("24/23.976", 1001L, 1000L),
    NTSC_23976_TO_FILM_24("23.976/24", 1000L, 1001L),
    PAL_25_TO_NTSC_23976("25/23.976", 1001L, 960L),
    NTSC_23976_TO_PAL_25("23.976/25", 960L, 1001L),
    PAL_25_TO_FILM_24("25/24", 25L, 24L),
    FILM_24_TO_PAL_25("24/25", 24L, 25L),
    NTSC_2997_TO_NTSC_23976("29.97/23.976", 5L, 4L),
    NTSC_23976_TO_NTSC_2997("23.976/29.97", 4L, 5L),
    ;

    fun scaleUs(timeUs: Long): Long = (timeUs / den) * num + ((timeUs % den) * num) / den

    fun inverseScaleUs(mediaUs: Long): Long = (mediaUs / num) * den + ((mediaUs % num) * den) / num

    companion object {
        fun fromIdOrNull(raw: String?): FpsRatio? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull {
                it.id.equals(trimmed, ignoreCase = true) ||
                    it.name.equals(trimmed, ignoreCase = true) ||
                    "${it.num}:${it.den}" == trimmed
            }
        }
    }
}

data class WarpKnot(
    val subTimeUs: Long,
    val mediaTimeUs: Long,
)

private fun mulDivExactLong(a: Long, b: Long, den: Long): Long {
    require(den != 0L) { "Zero denominator in mulDivExactLong" }
    if (a == 0L || b == 0L) return 0L
    if (a != Long.MIN_VALUE && b != Long.MIN_VALUE && abs(a) <= 3_037_000_499L && abs(b) <= 3_037_000_499L) {
        return (a * b) / den
    }
    return BigInteger.valueOf(a)
        .multiply(BigInteger.valueOf(b))
        .divide(BigInteger.valueOf(den))
        .toLong()
}

sealed interface SubtitleTimeWarp {
    val isIdentity: Boolean

    fun forwardUs(subTimeUs: Long): Long
    fun inverseUs(mediaTimeUs: Long): Long
    fun forwardMs(subTimeMs: Long): Long = forwardUs(subTimeMs * 1_000L) / 1_000L
    fun inverseMs(mediaTimeMs: Long): Long = inverseUs(mediaTimeMs * 1_000L) / 1_000L
    fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp
    fun serialize(): String

    data object Identity : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = true
        override fun forwardUs(subTimeUs: Long): Long = subTimeUs
        override fun inverseUs(mediaTimeUs: Long): Long = mediaTimeUs
        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp =
            if (deltaMs == 0L) Identity else Affine(FpsRatio.UNITY, deltaMs)
        override fun serialize(): String = "id"
    }

    data class Affine(
        val fpsRatio: FpsRatio,
        val offsetMs: Long,
    ) : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = fpsRatio == FpsRatio.UNITY && offsetMs == 0L
        override fun forwardUs(subTimeUs: Long): Long = fpsRatio.scaleUs(subTimeUs) + offsetMs * 1_000L
        override fun inverseUs(mediaTimeUs: Long): Long = fpsRatio.inverseScaleUs(mediaTimeUs - offsetMs * 1_000L)
        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp =
            copy(offsetMs = offsetMs + deltaMs).let { if (it.isIdentity) Identity else it }
        override fun serialize(): String =
            if (isIdentity) "id" else "aff:${fpsRatio.id}:$offsetMs"
    }

    class PiecewiseLinear private constructor(
        val baseFpsRatio: FpsRatio,
        val knots: List<WarpKnot>,
    ) : SubtitleTimeWarp {
        override val isIdentity: Boolean get() = false

        override fun forwardUs(subTimeUs: Long): Long {
            val first = knots.first()
            if (subTimeUs <= first.subTimeUs) {
                return first.mediaTimeUs + baseFpsRatio.scaleUs(subTimeUs - first.subTimeUs)
            }
            val last = knots.last()
            if (subTimeUs >= last.subTimeUs) {
                return last.mediaTimeUs + baseFpsRatio.scaleUs(subTimeUs - last.subTimeUs)
            }
            var lo = 0
            var hi = knots.size - 2
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (knots[mid].subTimeUs <= subTimeUs) {
                    lo = mid
                } else {
                    hi = mid - 1
                }
            }
            val k0 = knots[lo]
            val k1 = knots[lo + 1]
            val ds = k1.subTimeUs - k0.subTimeUs
            val dm = k1.mediaTimeUs - k0.mediaTimeUs
            return k0.mediaTimeUs + mulDivExactLong(subTimeUs - k0.subTimeUs, dm, ds)
        }

        override fun inverseUs(mediaTimeUs: Long): Long {
            val first = knots.first()
            if (mediaTimeUs <= first.mediaTimeUs) {
                return first.subTimeUs + baseFpsRatio.inverseScaleUs(mediaTimeUs - first.mediaTimeUs)
            }
            val last = knots.last()
            if (mediaTimeUs >= last.mediaTimeUs) {
                return last.subTimeUs + baseFpsRatio.inverseScaleUs(mediaTimeUs - last.mediaTimeUs)
            }
            var lo = 0
            var hi = knots.size - 2
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (knots[mid].mediaTimeUs <= mediaTimeUs) {
                    lo = mid
                } else {
                    hi = mid - 1
                }
            }
            val k0 = knots[lo]
            val k1 = knots[lo + 1]
            val dm = k1.mediaTimeUs - k0.mediaTimeUs
            val ds = k1.subTimeUs - k0.subTimeUs
            return k0.subTimeUs + mulDivExactLong(mediaTimeUs - k0.mediaTimeUs, ds, dm)
        }

        override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp {
            if (deltaMs == 0L) return this
            val shiftUs = deltaMs * 1_000L
            return PiecewiseLinear(
                baseFpsRatio = baseFpsRatio,
                knots = knots.map { it.copy(mediaTimeUs = it.mediaTimeUs + shiftUs) },
            )
        }

        override fun serialize(): String = buildString {
            append("pwl:").append(baseFpsRatio.id).append(':')
            knots.joinTo(this, separator = ",") { "${it.subTimeUs}@${it.mediaTimeUs}" }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is PiecewiseLinear && baseFpsRatio == other.baseFpsRatio && knots == other.knots)

        override fun hashCode(): Int = 31 * baseFpsRatio.hashCode() + knots.hashCode()

        override fun toString(): String = serialize()

        companion object {
            fun createValidated(
                baseFpsRatio: FpsRatio,
                knots: List<WarpKnot>,
            ): Result<PiecewiseLinear> {
                if (knots.size < 2) {
                    return Result.failure(IllegalArgumentException("PiecewiseLinear requires at least 2 knots"))
                }
                for (i in 0 until knots.size - 1) {
                    val a = knots[i]
                    val b = knots[i + 1]
                    if (a.subTimeUs >= b.subTimeUs || a.mediaTimeUs >= b.mediaTimeUs) {
                        return Result.failure(
                            IllegalArgumentException("Non-monotonic warp knots at index $i: $a -> $b")
                        )
                    }
                }
                return Result.success(PiecewiseLinear(baseFpsRatio, knots.toList()))
            }
        }
    }

    companion object {
        fun parse(spec: String): Result<SubtitleTimeWarp> {
            val clean = spec.trim()
            if (clean.isEmpty()) {
                return Result.failure(IllegalArgumentException("Empty SubtitleTimeWarp spec"))
            }
            if (clean.equals("id", ignoreCase = true) || clean.equals("identity", ignoreCase = true)) {
                return Result.success(Identity)
            }
            val lower = clean.lowercase(Locale.ROOT)
            if (lower.startsWith("aff:") || lower.startsWith("affine:")) {
                val parts = clean.split(':')
                if (parts.size < 3) {
                    return Result.failure(IllegalArgumentException("Malformed affine warp: $spec"))
                }
                val offsetMs = parts.last().toLongOrNull()
                    ?: return Result.failure(IllegalArgumentException("Invalid affine offsetMs in: $spec"))
                val ratioToken = parts.subList(1, parts.lastIndex).joinToString(":")
                val ratio = FpsRatio.fromIdOrNull(ratioToken)
                    ?: return Result.failure(IllegalArgumentException("Unknown FpsRatio '$ratioToken' in: $spec"))
                val affine = Affine(ratio, offsetMs)
                return Result.success(if (affine.isIdentity) Identity else affine)
            }
            if (lower.startsWith("pwl:")) {
                val body = clean.substring(4)
                val sepIdx = body.lastIndexOf(':')
                if (sepIdx <= 0 || sepIdx >= body.length - 1) {
                    return Result.failure(IllegalArgumentException("Malformed pwl warp: $spec"))
                }
                val ratio = FpsRatio.fromIdOrNull(body.substring(0, sepIdx))
                    ?: return Result.failure(IllegalArgumentException("Unknown FpsRatio in pwl warp: $spec"))
                val knotTokens = body.substring(sepIdx + 1).split(',')
                val knots = ArrayList<WarpKnot>(knotTokens.size)
                for (tok in knotTokens) {
                    val at = tok.indexOf('@')
                    if (at <= 0 || at >= tok.length - 1) {
                        return Result.failure(IllegalArgumentException("Malformed knot '$tok' in: $spec"))
                    }
                    val subUs = tok.substring(0, at).toLongOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid knot subTimeUs in: $tok"))
                    val medUs = tok.substring(at + 1).toLongOrNull()
                        ?: return Result.failure(IllegalArgumentException("Invalid knot mediaTimeUs in: $tok"))
                    knots.add(WarpKnot(subUs, medUs))
                }
                return PiecewiseLinear.createValidated(ratio, knots)
            }
            return Result.failure(IllegalArgumentException("Unrecognized SubtitleTimeWarp spec: $spec"))
        }

        fun parseOrIdentity(spec: String?): SubtitleTimeWarp {
            val trimmed = spec?.trim()?.takeIf { it.isNotEmpty() } ?: return Identity
            return parse(trimmed).getOrElse { Identity }
        }

        fun compose(inner: SubtitleTimeWarp, outer: SubtitleTimeWarp): SubtitleTimeWarp {
            if (outer.isIdentity) return inner
            if (inner.isIdentity) return outer
            if (inner is Affine && outer is Affine) {
                if (outer.fpsRatio == FpsRatio.UNITY) {
                    return Affine(inner.fpsRatio, inner.offsetMs + outer.offsetMs).let {
                        if (it.isIdentity) Identity else it
                    }
                }
                if (inner.fpsRatio == FpsRatio.UNITY) {
                    val scaledOffsetMs = (outer.fpsRatio.scaleUs(inner.offsetMs * 1_000L) + 500L).floorDiv(1_000L)
                    return Affine(outer.fpsRatio, scaledOffsetMs + outer.offsetMs).let {
                        if (it.isIdentity) Identity else it
                    }
                }
            }
            return ComposedWarp(inner, outer)
        }
    }
}

private data class ComposedWarp(
    val inner: SubtitleTimeWarp,
    val outer: SubtitleTimeWarp,
) : SubtitleTimeWarp {
    override val isIdentity: Boolean get() = inner.isIdentity && outer.isIdentity
    override fun forwardUs(subTimeUs: Long): Long = outer.forwardUs(inner.forwardUs(subTimeUs))
    override fun inverseUs(mediaTimeUs: Long): Long = inner.inverseUs(outer.inverseUs(mediaTimeUs))
    override fun withAdditionalOffsetMs(deltaMs: Long): SubtitleTimeWarp =
        SubtitleTimeWarp.compose(inner, outer.withAdditionalOffsetMs(deltaMs))
    override fun serialize(): String = outer.serialize().takeIf { inner.isIdentity } ?: inner.serialize()
}

fun CueDocument.warpedToMedia(warp: SubtitleTimeWarp): CueDocument {
    if (warp.isIdentity || cues.isEmpty()) return this
    val mapped = ArrayList<CueEntry>(cues.size)
    for (cue in cues) {
        val sMs = (warp.forwardUs(cue.span.startMs * 1_000L) + 500L).floorDiv(1_000L).coerceAtLeast(0L)
        val eMs = (warp.forwardUs(cue.span.endMs * 1_000L) + 500L).floorDiv(1_000L)
        if (eMs > sMs) {
            mapped.add(CueEntry(cue.id, CueSpan(sMs, eMs), cue.text))
        }
    }
    return if (mapped.isEmpty()) this else CueDocument.fromCues(language, mapped)
}

object SubtitleArchiveDecoder {
    const val MAX_DECODED_BYTES: Int = 8 * 1024 * 1024
    const val MAX_ZIP_ENTRIES: Int = 512
    const val MAX_TOTAL_ZIP_INFLATED_BYTES: Long = 32L * 1024L * 1024L

    private val SUBTITLE_EXTENSIONS = listOf(".vtt", ".srt", ".ass", ".ssa", ".ttml", ".dfxp", ".xml", ".sub")
    private val HI_FILENAME_REGEX = Regex("""(?i)(?:^|[._\-\s\[(])(?:hi|sdh|cc|hearing[._\-\s]*impaired)(?:$|[._\-\s\])])""")
    private val SXX_EXX_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])s(\d{1,2})\s*[._\-x]?\s*e(\d{1,3})(?:$|[^a-zA-Z0-9])""")
    private val NX_NN_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(\d{1,2})x(\d{1,3})(?:$|[^a-zA-Z0-9])""")
    private val EP_ONLY_REGEX = Regex("""(?i)(?:^|[._\-\s\[(])(?:ep?|episode)\s*0*(\d{1,4})(?:$|[._\-\s\])])""")
    private val ANIME_DASH_EP_REGEX = Regex("""(?:-\s*|\[)0*(\d{1,4})(?:\s*(?:v\d)?\s*[\]._\-\s])""")

    private val CP1256_URDU_SPECIFIC_BYTES = intArrayOf(
        0x81,
        0x8A,
        0x8D,
        0x8E,
        0x8F,
        0x90,
        0x98,
        0x9F,
        0xA1,
        0xAA,
        0xBA,
        0xC0,
    ).let { arr ->
        BooleanArray(256).also { table -> for (b in arr) table[b] = true }
    }

    private fun isCp1256ArabicOrUrduLetterByte(unsignedByte: Int): Boolean {
        if (CP1256_URDU_SPECIFIC_BYTES[unsignedByte]) return true
        return unsignedByte in 0xC1..0xDA ||
            unsignedByte in 0xDC..0xDF ||
            unsignedByte == 0xE1 ||
            unsignedByte in 0xE3..0xE6 ||
            unsignedByte == 0xEC ||
            unsignedByte == 0xED
    }

    fun decode(
        rawBytes: ByteArray,
        lookupSpec: MediaLookupSpec? = null,
        preferredFileHint: String? = null,
    ): Result<String> {
        if (rawBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty subtitle payload"))
        }
        val unwrappedResult = when {
            isZipMagic(rawBytes) -> extractBestZipEntry(rawBytes, lookupSpec, preferredFileHint)
            isGzipMagic(rawBytes) -> gunzipBounded(rawBytes)
            rawBytes.size > MAX_DECODED_BYTES ->
                Result.failure(IllegalArgumentException("Subtitle payload exceeds 8 MiB ceiling (${rawBytes.size} bytes)"))
            else -> Result.success(rawBytes)
        }
        val payload = unwrappedResult.getOrElse { return Result.failure(it) }
        if (payload.isEmpty()) {
            return Result.failure(IllegalArgumentException("Decompressed subtitle payload is empty"))
        }
        return Result.success(decodeCharsetToString(payload))
    }

    private fun isZipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte()) &&
            (bytes[3] == 0x04.toByte() || bytes[3] == 0x06.toByte())

    private fun isGzipMagic(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()

    private fun readBounded(stream: InputStream, maxBytes: Int = MAX_DECODED_BYTES): Result<ByteArray> {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                return Result.failure(IllegalArgumentException("Decompressed subtitle exceeds $maxBytes bytes"))
            }
            out.write(buf, 0, read)
        }
        return Result.success(out.toByteArray())
    }

    private fun drainBounded(stream: InputStream, maxRemainingBytes: Long): Long {
        val buf = ByteArray(8192)
        var drained = 0L
        while (true) {
            val read = stream.read(buf)
            if (read < 0) break
            drained += read.toLong()
            if (drained > maxRemainingBytes) {
                throw IllegalArgumentException("ZIP archive exceeded cumulative inflation budget")
            }
        }
        return drained
    }

    private fun gunzipBounded(rawBytes: ByteArray): Result<ByteArray> =
        runCatching {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gz ->
                readBounded(gz, MAX_DECODED_BYTES).getOrThrow()
            }
        }

    internal fun scoreZipEntryName(
        entryName: String,
        lookupSpec: MediaLookupSpec?,
        preferredFileHint: String?,
    ): Int? {
        val normalized = entryName.replace('\\', '/').trim()
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower.startsWith("__macosx/") || lower.contains("/._") || lower.startsWith("._")) return null
        val extIndex = SUBTITLE_EXTENSIONS.indexOfFirst { lower.endsWith(it) }
        if (extIndex < 0) return null

        var score = when {
            lower.endsWith(".vtt") -> 60
            lower.endsWith(".srt") -> 58
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> 45
            else -> 30
        }
        if (HI_FILENAME_REGEX.containsMatchIn(lower)) {
            score -= 120
        }
        if (!preferredFileHint.isNullOrBlank()) {
            val hintLower = preferredFileHint.lowercase(Locale.ROOT)
            if (lower.contains(hintLower)) score += 90
        }
        val hints = lookupSpec?.releaseHints.orEmpty()
        for (hint in hints) {
            val tok = hint.trim().lowercase(Locale.ROOT)
            if (tok.length >= 3 && lower.contains(tok)) score += 25
        }

        if (lookupSpec is MediaLookupSpec.Episode) {
            when (val coord = lookupSpec.coordinate) {
                is EpisodeCoordinate.VerifiedSeasonEpisode -> {
                    val sMatch = SXX_EXX_REGEX.find(lower) ?: NX_NN_REGEX.find(lower)
                    if (sMatch != null) {
                        val s = sMatch.groupValues[1].toIntOrNull()
                        val e = sMatch.groupValues[2].toIntOrNull()
                        if (s == coord.season && e == coord.episode) {
                            score += 600
                        } else {
                            return null
                        }
                    } else {
                        val epMatch = EP_ONLY_REGEX.find(lower) ?: ANIME_DASH_EP_REGEX.find(lower)
                        if (epMatch != null) {
                            val e = epMatch.groupValues[1].toIntOrNull()
                            if (e == coord.episode) {
                                score += 350
                            } else {
                                return null
                            }
                        }
                    }
                }
                is EpisodeCoordinate.UnverifiedAnimeCour -> {
                    val epMatch = EP_ONLY_REGEX.find(lower) ?: ANIME_DASH_EP_REGEX.find(lower)
                    if (epMatch != null) {
                        val e = epMatch.groupValues[1].toIntOrNull()
                        score += when {
                            e == coord.episodeInCour -> 500
                            coord.absoluteEpisode != null && e == coord.absoluteEpisode -> 450
                            else -> return null
                        }
                    }
                }
            }
        }
        return score
    }

    private fun extractBestZipEntry(
        rawBytes: ByteArray,
        lookupSpec: MediaLookupSpec?,
        preferredFileHint: String?,
    ): Result<ByteArray> = runCatching {
        var bestScore = Int.MIN_VALUE
        var bestBytes: ByteArray? = null
        var entryCount = 0
        var totalInflatedBytes = 0L
        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw IllegalArgumentException("ZIP archive exceeds $MAX_ZIP_ENTRIES entries")
                }
                if (entry.size > MAX_TOTAL_ZIP_INFLATED_BYTES) {
                    throw IllegalArgumentException("ZIP entry declared size exceeds safety budget (${entry.size})")
                }
                val remainingArchiveBudget = (MAX_TOTAL_ZIP_INFLATED_BYTES - totalInflatedBytes).coerceAtLeast(0L)
                if (!entry.isDirectory) {
                    val score = scoreZipEntryName(entry.name, lookupSpec, preferredFileHint)
                    if (score != null && score > bestScore) {
                        val entryCap = minOf(MAX_DECODED_BYTES.toLong(), remainingArchiveBudget).toInt()
                        val candidateBytes = readBounded(zis, entryCap).getOrThrow()
                        totalInflatedBytes += candidateBytes.size.toLong()
                        if (candidateBytes.isNotEmpty()) {
                            bestScore = score
                            bestBytes = candidateBytes
                        }
                    } else {
                        totalInflatedBytes += drainBounded(zis, remainingArchiveBudget)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        bestBytes ?: throw IllegalArgumentException("ZIP archive contained no supported subtitle entry")
    }

    fun decodeCharsetToString(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        if (bytes.size >= 8) {
            val sampleLen = minOf(bytes.size, 4096)
            var evenZero = 0
            var oddZero = 0
            var pairs = 0
            var i = 0
            while (i + 1 < sampleLen) {
                if (bytes[i] == 0.toByte()) evenZero++
                if (bytes[i + 1] == 0.toByte()) oddZero++
                pairs++
                i += 2
            }
            if (pairs >= 4) {
                if (oddZero.toDouble() / pairs > 0.35 && evenZero.toDouble() / pairs < 0.05) {
                    return String(bytes, StandardCharsets.UTF_16LE)
                }
                if (evenZero.toDouble() / pairs > 0.35 && oddZero.toDouble() / pairs < 0.05) {
                    return String(bytes, StandardCharsets.UTF_16BE)
                }
            }
        }

        val strictUtf8 = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8Decoded = runCatching { strictUtf8.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (utf8Decoded != null) {
            return utf8Decoded.removePrefix("\uFEFF")
        }

        var highBytes = 0
        var cp1256Letters = 0
        var urduSpecificHighBytes = 0
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u >= 0x80) {
                highBytes++
                if (isCp1256ArabicOrUrduLetterByte(u)) cp1256Letters++
                if (CP1256_URDU_SPECIFIC_BYTES[u]) urduSpecificHighBytes++
            }
        }
        val charset = if (highBytes > 0 &&
            (cp1256Letters.toDouble() / highBytes >= 0.35 ||
                (urduSpecificHighBytes >= 2 && cp1256Letters.toDouble() / highBytes >= 0.20))
        ) {
            runCatching { Charset.forName("windows-1256") }.getOrElse { StandardCharsets.ISO_8859_1 }
        } else {
            runCatching { Charset.forName("windows-1252") }.getOrElse { StandardCharsets.ISO_8859_1 }
        }
        return String(bytes, charset).removePrefix("\uFEFF")
    }
}

data class BakedWebVtt(
    val utf8Content: String,
    val cueCount: Int,
    val preWarpTimingSha256: ContentSha256,
    val bakedContentSha256: ContentSha256,
    val appliedWarp: SubtitleTimeWarp,
)

object WebVttBaker {
    const val RLM: Char = '\u200F'

    fun wrapUrduLinesRlm(text: String): String =
        text.split('\n').joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                ""
            } else if (trimmed[0] == RLM) {
                trimmed
            } else {
                "$RLM$trimmed"
            }
        }

    private fun warpAndFilterCues(
        doc: CueDocument,
        warp: SubtitleTimeWarp,
        videoDurationMs: Long?,
    ): List<CueEntry> {
        val forceRtl = doc.language == SubtitleLanguage.URDU
        val out = ArrayList<CueEntry>(doc.cues.size)
        for (cue in doc.cues) {
            var startMs = (warp.forwardUs(cue.span.startMs * 1_000L) + 500L).floorDiv(1_000L)
            var endMs = (warp.forwardUs(cue.span.endMs * 1_000L) + 500L).floorDiv(1_000L)
            if (endMs <= 0L) continue
            if (videoDurationMs != null && videoDurationMs > 0L) {
                if (startMs >= videoDurationMs) continue
                if (endMs > videoDurationMs) endMs = videoDurationMs
            }
            if (startMs < 0L) startMs = 0L
            if (endMs <= startMs) continue
            val formattedText = if (forceRtl) wrapUrduLinesRlm(cue.text) else cue.text.trim()
            if (formattedText.isEmpty()) continue
            out.add(CueEntry(cue.id, CueSpan(startMs, endMs), formattedText))
        }
        return out
    }

    private fun formatTimestamp(msTotal: Long, decimalSep: Char): String {
        val clamped = msTotal.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val mins = (clamped % 3_600_000L) / 60_000L
        val secs = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, mins, secs, decimalSep, millis)
    }

    fun bake(
        doc: CueDocument,
        warp: SubtitleTimeWarp = SubtitleTimeWarp.Identity,
        videoDurationMs: Long? = null,
    ): BakedWebVtt {
        val warped = warpAndFilterCues(doc, warp, videoDurationMs)
        val sb = StringBuilder(warped.size * 80 + 16)
        sb.append("WEBVTT\n\n")
        for (cue in warped) {
            sb.append(formatTimestamp(cue.span.startMs, '.'))
                .append(" --> ")
                .append(formatTimestamp(cue.span.endMs, '.'))
                .append('\n')
                .append(cue.text)
                .append("\n\n")
        }
        val content = sb.toString()
        return BakedWebVtt(
            utf8Content = content,
            cueCount = warped.size,
            preWarpTimingSha256 = doc.timingSha256,
            bakedContentSha256 = ContentSha256.ofUtf8(content),
            appliedWarp = warp,
        )
    }

    fun bakeSrt(
        doc: CueDocument,
        warp: SubtitleTimeWarp = SubtitleTimeWarp.Identity,
        videoDurationMs: Long? = null,
        includeUtf8Bom: Boolean = false,
    ): BakedWebVtt {
        val warped = warpAndFilterCues(doc, warp, videoDurationMs)
        val sb = StringBuilder(warped.size * 84 + 2)
        if (includeUtf8Bom) {
            sb.append('\uFEFF')
        }
        for ((index, cue) in warped.withIndex()) {
            sb.append(index + 1).append('\n')
                .append(formatTimestamp(cue.span.startMs, ','))
                .append(" --> ")
                .append(formatTimestamp(cue.span.endMs, ','))
                .append('\n')
                .append(cue.text)
                .append("\n\n")
        }
        val content = sb.toString()
        return BakedWebVtt(
            utf8Content = content,
            cueCount = warped.size,
            preWarpTimingSha256 = doc.timingSha256,
            bakedContentSha256 = ContentSha256.ofUtf8(content),
            appliedWarp = warp,
        )
    }

    fun serializeSrt(
        doc: CueDocument,
        warp: SubtitleTimeWarp = SubtitleTimeWarp.Identity,
        videoDurationMs: Long? = null,
    ): String = bakeSrt(doc, warp, videoDurationMs, includeUtf8Bom = false).utf8Content
}
