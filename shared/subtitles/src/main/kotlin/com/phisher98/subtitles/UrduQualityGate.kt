package com.phisher98.subtitles

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

data class CatalogueCandidateMeta(
    val catalogue: CatalogueSourceKey,
    val language: SubtitleLanguage,
    val titleOrReleaseText: String,
    val uploaderName: String? = null,
    val isHearingImpairedFlag: Boolean = false,
    val isMachineTranslatedFlag: Boolean = false,
    val declaredFps: Double? = null,
    val declaredDurationMs: Long? = null,
    val ratingScore: Double = 0.0,
    val downloadCount: Int = 0,
    val releaseComment: String? = null,
)

class CandidateGateRejectedException(
    val rejection: VerifiedSubtitleCandidate.Rejected,
) : IllegalArgumentException(rejection.toString())

object CatalogueCandidateGate {
    val BANNED_MT_UPLOADERS: Set<String> = setOf(
        "blackgold",
        "iamdesigora",
        "akhaliq_655",
        "saad.jan",
        "tryray",
        "chatgpt",
        "gpt-4",
        "deepl",
        "googletranslate",
        "subtitleedit_mt",
    )

    private val HI_MARKER_REGEX = Regex(
        """(?i)(?:\.hi-subtitle\b|\[cc\]|\(cc\)|\[sdh\]|\(sdh\)|\[hi\]|\(hi\)|\bhearing[\s._-]*impaired\b|(?:^|[.\s_\-\[(])sdh(?:$|[.\s_\-\])]))"""
    )
    private val MT_MARKER_REGEX = Regex(
        """(?i)(?:\bai[\s._-]*translated\b|\bmachine[\s._-]*translated\b|\bauto[\s._-]*translated\b|\bgoogle\s*translate\b|\bsubtitle\s*edit\s*mt\b|\btranslated\s+by\s+(?:ai|chatgpt|gpt|deepl|google)\b)"""
    )
    private val SUBTITLECAT_MT_SUFFIX_REGEX = Regex("""(?i)-ur\.srt(?:\b|$)""")
    private val SXX_EXX_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])s(\d{1,2})\s*[._\-x]?\s*e(\d{1,3})(?:$|[^a-zA-Z0-9])""")
    private val NX_NN_REGEX = Regex("""(?i)(?:^|[^a-zA-Z0-9])(\d{1,2})x(\d{1,3})(?:$|[^a-zA-Z0-9])""")
    private val YEAR_REGEX = Regex("""(?:^|[^a-zA-Z0-9])(19\d{2}|20\d{2})(?:$|[^a-zA-Z0-9])""")

    private val STREAM_WEB_LINEAGE_REGEX = Regex("""(?i)\b(?:web-?dl|webrip|web|amzn|nf|netflix|dsnp|atvp|hmax|pcok|cr|crunchyroll)\b""")
    private val HDTV_LINEAGE_REGEX = Regex("""(?i)\b(?:hdtv|pdtv)\b""")
    private val BLURAY_LINEAGE_REGEX = Regex("""(?i)\b(?:blu-?ray|bdrip|brrip|bdremux)\b""")
    private val CAM_LINEAGE_REGEX = Regex("""(?i)\b(?:hdcam|camrip|cam|hdts|telesync|workprint)\b""")

    fun matchHearingImpairedMarker(text: String): String? =
        HI_MARKER_REGEX.find(text)?.value

    fun matchBannedUploader(uploaderName: String?, auxiliaryText: String = ""): String? {
        val normUploader = uploaderName?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normUploader.isNotEmpty()) {
            BANNED_MT_UPLOADERS.firstOrNull { banned ->
                normUploader == banned || normUploader.contains(banned)
            }?.let { return it }
        }
        val normAux = auxiliaryText.lowercase(Locale.ROOT)
        if (normAux.isNotEmpty()) {
            BANNED_MT_UPLOADERS.firstOrNull { banned -> normAux.contains(banned) }?.let { return it }
        }
        return null
    }

    fun evaluatePreDownload(
        meta: CatalogueCandidateMeta,
        spec: MediaLookupSpec,
    ): Result<Int> {
        val combinedMetaText = buildString {
            append(meta.titleOrReleaseText)
            if (!meta.releaseComment.isNullOrBlank()) append(' ').append(meta.releaseComment)
        }

        if (meta.isHearingImpairedFlag) {
            return Result.failure(
                CandidateGateRejectedException(VerifiedSubtitleCandidate.Rejected.HearingImpaired("hearing_impaired=true"))
            )
        }
        matchHearingImpairedMarker(combinedMetaText)?.let { marker ->
            return Result.failure(
                CandidateGateRejectedException(VerifiedSubtitleCandidate.Rejected.HearingImpaired(marker))
            )
        }

        matchBannedUploader(meta.uploaderName, combinedMetaText)?.let { banned ->
            return Result.failure(
                CandidateGateRejectedException(VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader(banned))
            )
        }

        if (meta.isMachineTranslatedFlag ||
            MT_MARKER_REGEX.containsMatchIn(combinedMetaText) ||
            (meta.catalogue == CatalogueSourceKey.SUBTITLECAT &&
                meta.language == SubtitleLanguage.URDU &&
                SUBTITLECAT_MT_SUFFIX_REGEX.containsMatchIn(meta.titleOrReleaseText))
        ) {
            return Result.failure(
                CandidateGateRejectedException(
                    VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader("machine_translated_metadata")
                )
            )
        }

        validateEpisodeAndYear(meta.titleOrReleaseText, spec)?.let { mismatch ->
            return Result.failure(CandidateGateRejectedException(mismatch))
        }

        var score = meta.catalogue.intraCatalogueRank

        val hintsLower = spec.releaseHints.map { it.trim().lowercase(Locale.ROOT) }.filter { it.length >= 2 }
        val releaseLower = combinedMetaText.lowercase(Locale.ROOT)
        val prefersBluray = hintsLower.any { BLURAY_LINEAGE_REGEX.containsMatchIn(it) }

        score += when {
            CAM_LINEAGE_REGEX.containsMatchIn(releaseLower) -> -45
            prefersBluray && BLURAY_LINEAGE_REGEX.containsMatchIn(releaseLower) -> 38
            STREAM_WEB_LINEAGE_REGEX.containsMatchIn(releaseLower) -> 35
            HDTV_LINEAGE_REGEX.containsMatchIn(releaseLower) -> 16
            BLURAY_LINEAGE_REGEX.containsMatchIn(releaseLower) -> 6
            else -> 0
        }

        for (hint in hintsLower) {
            if (hint.length >= 3 && releaseLower.contains(hint)) {
                score += 22
            }
        }

        val expectedDur = spec.expectedDurationMs
        val declaredDur = meta.declaredDurationMs
        if (expectedDur != null && expectedDur > 0L && declaredDur != null && declaredDur > 0L) {
            val deltaMs = abs(expectedDur - declaredDur)
            score += when {
                deltaMs <= 15_000L -> 40
                deltaMs <= 60_000L -> 22
                deltaMs <= 150_000L -> 5
                deltaMs > 300_000L -> -45
                else -> -15
            }
        }

        val fps = meta.declaredFps
        if (fps != null && fps > 0.0) {
            val palHinted = hintsLower.any { it.contains("25fps") || it.contains("pal") || it.contains("hdtv") }
            score += when {
                abs(fps - 23.976) <= 0.03 || abs(fps - 24.0) <= 0.03 -> 18
                abs(fps - 25.0) <= 0.05 -> if (palHinted) 20 else -8
                abs(fps - 29.97) <= 0.05 -> 6
                else -> 0
            }
        }

        if (meta.ratingScore > 0.0) {
            score += (meta.ratingScore.coerceIn(0.0, 10.0) * 1.2).toInt()
        }
        if (meta.downloadCount > 0) {
            score += (ln(meta.downloadCount.toDouble() + 1.0) * 1.5).toInt().coerceAtMost(15)
        }

        return Result.success(score)
    }

    private fun validateEpisodeAndYear(
        releaseText: String,
        spec: MediaLookupSpec,
    ): VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch? {
        if (releaseText.isBlank()) return null
        when (spec) {
            is MediaLookupSpec.Movie -> {
                if (SXX_EXX_REGEX.containsMatchIn(releaseText)) {
                    return VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch("Movie", releaseText)
                }
                val expectedYear = spec.releaseYear
                if (expectedYear != null) {
                    val years = YEAR_REGEX.findAll(releaseText).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    if (years.isNotEmpty() && years.none { abs(it - expectedYear) <= 1 }) {
                        return VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch(
                            expected = expectedYear.toString(),
                            actualRelease = releaseText,
                        )
                    }
                }
            }
            is MediaLookupSpec.Episode -> {
                when (val coord = spec.coordinate) {
                    is EpisodeCoordinate.VerifiedSeasonEpisode -> {
                        val m = SXX_EXX_REGEX.find(releaseText) ?: NX_NN_REGEX.find(releaseText)
                        if (m != null) {
                            val s = m.groupValues[1].toIntOrNull()
                            val e = m.groupValues[2].toIntOrNull()
                            if (s != coord.season || e != coord.episode) {
                                return VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch(
                                    expected = "S%02dE%02d".format(Locale.ROOT, coord.season, coord.episode),
                                    actualRelease = releaseText,
                                )
                            }
                        }
                    }
                    is EpisodeCoordinate.UnverifiedAnimeCour -> {
                        val m = SXX_EXX_REGEX.find(releaseText)
                        if (m != null) {
                            val e = m.groupValues[2].toIntOrNull()
                            if (e != coord.episodeInCour && (coord.absoluteEpisode == null || e != coord.absoluteEpisode)) {
                                return VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch(
                                    expected = "E${coord.episodeInCour}",
                                    actualRelease = releaseText,
                                )
                            }
                        }
                    }
                }
                val allowedYears = listOfNotNull(spec.seriesPremiereYear, spec.seasonAirYear)
                if (allowedYears.isNotEmpty()) {
                    val years = YEAR_REGEX.findAll(releaseText).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    if (years.isNotEmpty() && years.none { y -> allowedYears.any { abs(it - y) <= 1 } }) {
                        return VerifiedSubtitleCandidate.Rejected.EpisodeOrYearMismatch(
                            expected = allowedYears.joinToString("/"),
                            actualRelease = releaseText,
                        )
                    }
                }
            }
        }
        return null
    }
}

object UrduOrthographyGate {
    val URDU_ARABIC_SCRIPT_REGEX =
        Regex("""[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]""")

    val URDU_DISTINCTIVE_CHARS_REGEX =
        Regex("""[\u0679\u0688\u0691\u06BA\u06D2\u06BE]""")

    val URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،,.!?؟;:'\(\)\[\]\-۔…])(آؤ|آپ|اور|اپنا|اپنی|اپنے|بھی|تاکہ|تھا|تھی|تھے|جاؤ|جائیں|رھا|رھی|رھے|ساتھ|سے|لیے|میں|نھیں|وہ|پھر|پھلے|کا|کرو|کریں|کو|کوئی|کچھ|کہاں|کیا|کیجئے|کیسے|کیونکہ|کیوں|کے|گئی|گئے|گا|گی|گیا|گے|ہوں|ہیں|ہے|یہ|رہا|رہی|رہے|نہیں|پہلے)(?:$|[\s\u200F\u200E،,.!?؟;:'\(\)\[\]\-۔…])""")

    val RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،,.!?;:'\(\)۔؟…])(آره|آمدم|آمدی|آنجا|آنها|أبدا|أحيانا|أسبوع|أمس|أن|أنا|أنت|أنتم|أو|أي|أيضا|أين|إله|إلى|إن|اتاق|است|الآن|الان|التي|الذي|الذين|امرأة|امروز|انقدر|اون|اونا|اونو|ایشان|این|اینجا|اینو|باشد|باشه|باید|برای|بشه|بفرمایید|بودی|تكون|تموم|توی|حقا|حياة|خونه|دارد|دارم|دارند|داره|داری|داریم|دخترم|دقیقه|دون|دیدم|دیدی|دیروز|دیگه|ذلك|رأس|ربما|رجل|رفتم|رفتی|ساعة|سنة|سوف|سيارة|شدند|شما|شود|عند|عندما|غدا|غدًا|فردا|قرمز|قليلا|كانت|كانوا|كثيرا|كذلك|كلمة|لكن|لم|لماذا|لن|ليس|ليست|ليلة|ماذا|مدينة|مرحبا|مرحبًا|مرسی|منذ|منم|می\u200C|میبینمت|میخواد|میخوام|میخوای|میدونم|میشه|ناس|نحن|نمی|نمی\u200C|نمیدونم|نمیشه|نیست|های|هایی|هذا|هذه|هستم|هستند|هستی|هنا|هناك|هیچ|يد|يكون|پسرم|چطور|چقدر|چیزی|چیه|کجا|کدام|کردم|کردند|کردی|کردیم|کنم|کنند|کنی|کنیم|گفتم|گفتند|گفتی)(?=$|[\s\u200F\u200E،,.!?;:'\(\)۔؟…])""")

    val PERSIAN_ARABIC_EXCLUSIVE_REGEX = Regex(
        RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.pattern
            .replace("\u0647", "\u06C1")
            .replace("\u0643", "\u06A9")
            .replace("\u064A", "\u06CC")
            .replace("\u0649", "\u06CC")
    )

    val PERSIAN_ZWNJ_VERB_PREFIX_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،,.!?؟;:'\(\)\[\]\-۔…])(?:می|نمی)\u200C""")
    val PERSIAN_YEH_RAW_HEH_REGEX =
        Regex("""(?:^|[\s\u200F\u200E،,.!?؟;:'\(\)\[\]\-۔…])\u06CC\u0647(?=$|[\s\u200F\u200E،,.!?؟;:'\(\)\[\]\-۔…])""")
    val ARABIC_ORTHOGRAPHY_REGEX = Regex("""[\u0623\u0625\u0629]""")

    private val URDU_EXCLUSIVE_CODEPOINTS = setOf(
        '\u0679', '\u0688', '\u0691', '\u06BA', '\u06C1',
        '\u06C2', '\u06C3', '\u06BE', '\u06D2', '\u06D3',
    )

    private val ARABIC_FOREIGN_LETTER_CODEPOINTS = setOf(
        '\u064A', '\u0649', '\u0643', '\u0629', '\u0647',
    )

    private val PERSIAN_PLURAL_SUFFIX_REGEX = Regex("""[آ-ی]{2,}هایی(?:\b|$)""")
    private val TIMESTAMP_LINE_REGEX = Regex(
        """^\s*(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?\s*-->\s*(?:\d{1,2}:)?\d{1,2}:\d{2}(?:[,.]\d{1,3})?.*$"""
    )
    private val VTT_META_BLOCK_HEADER_REGEX = Regex(
        """^(?:NOTE|STYLE|REGION)(?:\s.*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMESTAMP_OR_TAG_LINE_REGEX = Regex(
        """(?m)^(?:\s*\d+\s*|\s*WEBVTT.*|\s*\d{1,3}:\d{1,2}:\d{2}[,.]\d{1,3}\s*-->.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val INLINE_MARKUP_REGEX = Regex("""(?:\{\\[^}]*\}|<[^>]*>)""")
    private val BROKEN_SMT_EMBEDDED_ASCII_REGEX = Regex("""[\u0600-\u06FF]+\s+[a-z]{3,}\s+[\u0600-\u06FF]+""")
    private val BROKEN_SMT_INLINE_ASCII_REGEX =
        Regex("""(?:[\u0600-\u06FF]{2,}\s+[a-z]{3,}|[a-z]{3,}\s+[\u0600-\u06FF]{2,})""")
    private val BROKEN_SMT_TOKENS = listOf(
        "ہںس",
        "واپس سربراہ چاہئے",
        "واپس حاصل",
        "سر واپس چاہئے",
        "کے ripping",
        "ripping کے",
        "wildlings اس",
    )
    private val ARABIC_PRESENTATION_FORMS_REGEX = Regex("""[\uFB50-\uFDFF\uFE70-\uFEFF]+""")

    private val ASS_DIALOGUE_DRAWING_REGEX = Regex("""\{\\[^}]*\\p[1-9][^}]*\}""")
    private val ASS_LINEBREAK_REGEX = Regex("""\\[Nn]""")
    private val TTML_RAW_P_REGEX = Regex("""(?is)<p\b[^>]*>(.*?)</p>""")
    private val TTML_RAW_BR_REGEX = Regex("""(?i)<br\s*/?>""")
    private val ENGLISH_DONOR_FUNCTION_WORDS_REGEX = Regex(
        """(?i)\b(?:the|and|you|that|this|what|with|have|for|not|are|was|but|from|they|your|just|know|don't|it's|i'm|we're|can't|here|there|where|when|would|could|should)\b"""
    )

    private fun extractAssDialogueCueTexts(cleaned: String): List<String> {
        val lines = cleaned.split('\n')
        var textColIndex = 9
        val out = ArrayList<String>()
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                val cols = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                val idx = cols.indexOf("text")
                if (idx >= 0) textColIndex = idx
            } else if (trimmed.startsWith("Dialogue:", ignoreCase = true)) {
                val payload = trimmed.substringAfter(':')
                var commaPos = -1
                var commasFound = 0
                for (k in payload.indices) {
                    if (payload[k] == ',') {
                        commasFound++
                        if (commasFound == textColIndex) {
                            commaPos = k
                            break
                        }
                    }
                }
                if (commaPos >= 0 && commaPos + 1 <= payload.length) {
                    val rawText = payload.substring(commaPos + 1)
                    if (ASS_DIALOGUE_DRAWING_REGEX.containsMatchIn(rawText)) continue
                    val stripped = INLINE_MARKUP_REGEX.replace(
                        ASS_LINEBREAK_REGEX.replace(rawText, "\n"),
                        ""
                    ).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    if (stripped.isNotEmpty()) out.add(stripped)
                }
            }
        }
        return out
    }

    private fun extractTtmlRawCueTexts(cleaned: String): List<String> {
        val out = ArrayList<String>()
        for (m in TTML_RAW_P_REGEX.findAll(cleaned)) {
            val inner = m.groupValues[1]
            val body = LatinEnglishText.decodeHtmlEntities(
                INLINE_MARKUP_REGEX.replace(TTML_RAW_BR_REGEX.replace(inner, "\n"), "")
            ).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (body.isNotEmpty()) out.add(body)
        }
        return out
    }

    fun extractRawCueTexts(rawContent: String): List<String> {
        if (rawContent.isBlank()) return emptyList()
        val cleaned = rawContent.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val head = cleaned.take(4096)
        if (head.contains("[Events]", ignoreCase = true) ||
            cleaned.contains("\nDialogue:", ignoreCase = true) ||
            cleaned.startsWith("Dialogue:", ignoreCase = true)
        ) {
            val assCues = extractAssDialogueCueTexts(cleaned)
            if (assCues.isNotEmpty()) return assCues
        }
        if ((head.contains("<tt", ignoreCase = true) || head.contains("<body", ignoreCase = true)) &&
            cleaned.contains("<p", ignoreCase = true)
        ) {
            val ttmlCues = extractTtmlRawCueTexts(cleaned)
            if (ttmlCues.isNotEmpty()) return ttmlCues
        }
        val lines = cleaned.split('\n')
        val cues = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT", ignoreCase = true)) {
                i++
                continue
            }
            if (VTT_META_BLOCK_HEADER_REGEX.matches(trimmed)) {
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) i++
                continue
            }
            var tsIdx = -1
            if (TIMESTAMP_LINE_REGEX.matches(trimmed)) {
                tsIdx = i
            } else if (i + 1 < lines.size && TIMESTAMP_LINE_REGEX.matches(lines[i + 1].trim())) {
                tsIdx = i + 1
            }
            if (tsIdx >= 0) {
                i = tsIdx + 1
                val buf = ArrayList<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val lineCleaned = INLINE_MARKUP_REGEX.replace(lines[i], "").trim()
                    if (lineCleaned.isNotEmpty()) buf.add(lineCleaned)
                    i++
                }
                if (buf.isNotEmpty()) {
                    cues.add(buf.joinToString("\n"))
                }
            } else {
                i++
            }
        }
        return cues
    }

    fun isBrokenSmtCueTexts(rawCueTexts: List<String>): Boolean {
        if (rawCueTexts.isEmpty()) return false
        var embeddedAsciiCount = 0
        var inlineAsciiCount = 0
        var explicitSmtCount = 0
        for (raw in rawCueTexts) {
            val text = INLINE_MARKUP_REGEX.replace(raw, " ")
            if (BROKEN_SMT_TOKENS.any { token -> text.contains(token, ignoreCase = true) }) {
                explicitSmtCount++
            }
            if (BROKEN_SMT_EMBEDDED_ASCII_REGEX.containsMatchIn(text)) {
                embeddedAsciiCount++
            } else if (BROKEN_SMT_INLINE_ASCII_REGEX.containsMatchIn(text)) {
                inlineAsciiCount++
            }
        }
        if (explicitSmtCount >= 1 && (embeddedAsciiCount + inlineAsciiCount + explicitSmtCount) >= 2) {
            return true
        }
        if (rawCueTexts.size <= 4 && embeddedAsciiCount >= 1 && (embeddedAsciiCount + inlineAsciiCount + explicitSmtCount) >= 2) {
            return true
        }
        val totalCorrupted = embeddedAsciiCount + explicitSmtCount
        return totalCorrupted >= 3 || (rawCueTexts.size >= 5 && totalCorrupted * 100 >= rawCueTexts.size * 4)
    }

    fun containsVerifiedUrduCueTexts(rawCueTexts: List<String>): Boolean {
        if (rawCueTexts.isEmpty()) return false
        val urduCues = rawCueTexts.count { URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(it) }
        if (urduCues < 1 || (rawCueTexts.size > 4 && urduCues * 100 < rawCueTexts.size * 15)) {
            return false
        }

        val rawText = rawCueTexts.joinToString("\n")
        val combinedText = normalizeUrduTypography(rawText)
        val urduDistinctiveCount = URDU_DISTINCTIVE_CHARS_REGEX.findAll(combinedText).count()
        val urduUnambiguousFunctionCount = URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(rawText).count()
        val persianArabicExclusiveCount = PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(combinedText).count()
        val rawPersianArabicExclusiveCount = RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(rawText).count()

        if (urduDistinctiveCount < 1 && urduUnambiguousFunctionCount < 1) {
            return false
        }
        if (rawPersianArabicExclusiveCount >= 1 && urduDistinctiveCount == 0) {
            return false
        }
        if (persianArabicExclusiveCount >= 1 &&
            (urduDistinctiveCount == 0 || persianArabicExclusiveCount > (urduDistinctiveCount + urduUnambiguousFunctionCount))
        ) {
            return false
        }
        if (urduDistinctiveCount == 0 &&
            (PERSIAN_ZWNJ_VERB_PREFIX_REGEX.containsMatchIn(rawText) ||
                PERSIAN_YEH_RAW_HEH_REGEX.containsMatchIn(rawText) ||
                ARABIC_ORTHOGRAPHY_REGEX.containsMatchIn(rawText) ||
                PERSIAN_PLURAL_SUFFIX_REGEX.containsMatchIn(rawText))
        ) {
            return false
        }

        if (rawCueTexts.size >= 2) {
            var urduEvidenceCues = 0
            var faArabicLeaningCues = 0
            for (cueText in rawCueTexts) {
                val cueNormalized = normalizeUrduTypography(cueText)
                if (URDU_DISTINCTIVE_CHARS_REGEX.containsMatchIn(cueNormalized) ||
                    URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn(cueText)
                ) {
                    urduEvidenceCues++
                }
                if (RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.containsMatchIn(cueText) ||
                    PERSIAN_ARABIC_EXCLUSIVE_REGEX.containsMatchIn(cueNormalized) ||
                    PERSIAN_ZWNJ_VERB_PREFIX_REGEX.containsMatchIn(cueText) ||
                    PERSIAN_YEH_RAW_HEH_REGEX.containsMatchIn(cueText) ||
                    ARABIC_ORTHOGRAPHY_REGEX.containsMatchIn(cueText) ||
                    PERSIAN_PLURAL_SUFFIX_REGEX.containsMatchIn(cueText)
                ) {
                    faArabicLeaningCues++
                }
            }
            if (faArabicLeaningCues > urduEvidenceCues) {
                return false
            }
            val shareFloor = if (faArabicLeaningCues > 0) 40 else 30
            if (urduEvidenceCues * 100 < urduCues * shareFloor) {
                return false
            }
        }
        return true
    }

    fun containsVerifiedUrduScript(rawContent: String): Boolean {
        if (rawContent.isBlank()) return false
        val extractedCues = extractRawCueTexts(rawContent)
        if (extractedCues.isNotEmpty()) {
            return containsVerifiedUrduCueTexts(extractedCues)
        }
        val cleaned = INLINE_MARKUP_REGEX.replace(rawContent, " ")
        val normalized = normalizeUrduTypography(cleaned)
        if (!URDU_ARABIC_SCRIPT_REGEX.containsMatchIn(normalized)) return false
        val urduDistinctiveCount = URDU_DISTINCTIVE_CHARS_REGEX.findAll(normalized).count()
        val urduUnambiguousFunctionCount = URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.findAll(cleaned).count()
        if (urduDistinctiveCount < 1 && urduUnambiguousFunctionCount < 1) return false
        val rawFaArCount = RAW_PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(cleaned).count()
        val normFaArCount = PERSIAN_ARABIC_EXCLUSIVE_REGEX.findAll(normalized).count()
        if (rawFaArCount >= 1 && urduDistinctiveCount == 0) return false
        if (normFaArCount >= 1 && (urduDistinctiveCount == 0 || normFaArCount > urduDistinctiveCount + urduUnambiguousFunctionCount)) {
            return false
        }
        if (urduDistinctiveCount == 0 &&
            (PERSIAN_ZWNJ_VERB_PREFIX_REGEX.containsMatchIn(cleaned) ||
                PERSIAN_YEH_RAW_HEH_REGEX.containsMatchIn(cleaned) ||
                ARABIC_ORTHOGRAPHY_REGEX.containsMatchIn(cleaned) ||
                PERSIAN_PLURAL_SUFFIX_REGEX.containsMatchIn(cleaned))
        ) {
            return false
        }
        return true
    }

    fun verifyUrduOrthography(rawDecodedText: String): Result<Unit> {
        val cueTexts = extractRawCueTexts(rawDecodedText)
        val stripped = if (cueTexts.isNotEmpty()) {
            INLINE_MARKUP_REGEX.replace(cueTexts.joinToString("\n"), " ")
        } else {
            INLINE_MARKUP_REGEX.replace(
                TIMESTAMP_OR_TAG_LINE_REGEX.replace(rawDecodedText, " "),
                " "
            )
        }
        var arabicScriptLetters = 0
        var latinLetters = 0
        var urduExclusiveCount = 0
        var arabicForeignLetterCount = 0

        for (ch in stripped) {
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' -> latinLetters++
                LatinEnglishText.isArabicOrNastaliqCodePoint(ch.code) && ch.isLetter() -> {
                    arabicScriptLetters++
                    if (ch in URDU_EXCLUSIVE_CODEPOINTS) urduExclusiveCount++
                    if (ch in ARABIC_FOREIGN_LETTER_CODEPOINTS) arabicForeignLetterCount++
                }
            }
        }

        val totalLetters = arabicScriptLetters + latinLetters
        if (arabicScriptLetters < 4 || totalLetters == 0) {
            return Result.failure(IllegalArgumentException("Insufficient Urdu script characters ($arabicScriptLetters)"))
        }
        if (arabicScriptLetters.toDouble() / totalLetters.toDouble() < 0.55) {
            return Result.failure(IllegalArgumentException("Excessive embedded Latin script in Urdu candidate"))
        }

        val smtSandwichHits = BROKEN_SMT_EMBEDDED_ASCII_REGEX.findAll(stripped).count()
        if (smtSandwichHits >= 3 || isBrokenSmtCueTexts(cueTexts)) {
            return Result.failure(IllegalArgumentException("Broken phrase-SMT Urdu with embedded English tokens ($smtSandwichHits)"))
        }

        if (arabicForeignLetterCount > urduExclusiveCount * 3 &&
            !URDU_UNAMBIGUOUS_FUNCTION_WORDS_REGEX.containsMatchIn(stripped)
        ) {
            return Result.failure(IllegalArgumentException("Rejected non-Urdu Arabic/Persian orthography"))
        }

        if (!containsVerifiedUrduScript(rawDecodedText)) {
            return Result.failure(IllegalArgumentException("Rejected non-Urdu Persian/Arabic or unverified script"))
        }
        return Result.success(Unit)
    }

    fun verifyEnglishDonorDocument(doc: CueDocument): Result<Unit> {
        if (doc.cues.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty English donor document"))
        }
        val combined = doc.cues.joinToString("\n") { it.text }
        var asciiLatinLetters = 0
        var otherLetters = 0
        for (ch in combined) {
            if (ch in 'A'..'Z' || ch in 'a'..'z') {
                asciiLatinLetters++
            } else if (ch.isLetter()) {
                otherLetters++
            }
        }
        val totalLetters = asciiLatinLetters + otherLetters
        if (asciiLatinLetters < 6 || totalLetters == 0) {
            return Result.failure(IllegalArgumentException("Insufficient Latin script in English donor"))
        }
        if (asciiLatinLetters.toDouble() / totalLetters.toDouble() < 0.80) {
            return Result.failure(IllegalArgumentException("Non-English script ratio in donor candidate"))
        }
        val enFunctionMatches = ENGLISH_DONOR_FUNCTION_WORDS_REGEX.findAll(combined).count()
        if (doc.cues.size >= 4 && enFunctionMatches < 2) {
            return Result.failure(IllegalArgumentException("Non-English Latin donor (missing English function words)"))
        }
        if (doc.cues.size in 2..3 && enFunctionMatches < 1) {
            return Result.failure(IllegalArgumentException("Non-English short Latin donor"))
        }
        return Result.success(Unit)
    }

    private val TERMINAL_ASCII_PERIOD_REGEX = Regex("""(?<=[\u0600-\u06FF\u0750-\u077F])\.(?=\s|$)""")
    private val INLINE_ASCII_COMMA_REGEX = Regex("""(?<=[\u0600-\u06FF\u0750-\u077F]),(?=\s|$)""")
    private val INLINE_ASCII_QUESTION_REGEX = Regex("""(?<=[\u0600-\u06FF\u0750-\u077F])\?(?=\s|$)""")
    private val HORIZONTAL_WS_REGEX = Regex("""[^\S\n]+""")

    fun normalizeUrduTypography(raw: String): String {
        if (raw.isEmpty()) return raw
        val preCleaned = raw
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val decomposed = if (ARABIC_PRESENTATION_FORMS_REGEX.containsMatchIn(preCleaned)) {
            ARABIC_PRESENTATION_FORMS_REGEX.replace(preCleaned) { match ->
                Normalizer.normalize(match.value, Normalizer.Form.NFKC)
            }
        } else {
            preCleaned
        }
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            when (ch) {
                '\u0643' -> sb.append('\u06A9')
                '\u064A', '\u0649' -> sb.append('\u06CC')
                '\u0647', '\u0629', '\u06D5' -> sb.append('\u06C1')
                '\u06C0' -> sb.append('\u06C2')
                '\u06D3' -> sb.append('\u06D2')
                '\u0640' -> Unit
                else -> sb.append(ch)
            }
        }
        var out = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
        out = TERMINAL_ASCII_PERIOD_REGEX.replace(out, "\u06D4")
        out = INLINE_ASCII_COMMA_REGEX.replace(out, "\u060C")
        out = INLINE_ASCII_QUESTION_REGEX.replace(out, "\u061F")
        return out.lines()
            .map { HORIZONTAL_WS_REGEX.replace(it, " ").trim() }
            .joinToString("\n")
            .trim()
    }
}

object UrduMtDumpDetector {
    private val STANDALONE_POLYSEMY_CALQUES = listOf(
        Regex("""^\s*[\u200F\u200E]*دائیں[۔.!؟?\s]*$"""),
        Regex("""^\s*[\u200F\u200E]*جرمانہ[۔.!؟?\s]*$"""),
        Regex("""^\s*[\u200F\u200E]*ٹھنڈا[!۔.?؟\s]*$"""),
        Regex("""^\s*[\u200F\u200E]*بطخ[!۔.?؟\s]*$"""),
    )

    private val PHRASE_CALQUES = listOf(
        Regex("""شہد\s*[،,]"""),
        Regex("""آپ\s+کا\s+استقبال\s+ہے"""),
        Regex("""(?:مقدس|بیل\s+کی)\s+گندگی"""),
        Regex("""بھری\s+ہوئی\s+بلی"""),
        Regex("""کیا\s+جہنم(?:\s+ہے)?"""),
        Regex("""(?:تم|آپ)\s+مجھ\s+سے\s+مذاق\s+کر\s+رہے\s+(?:ہو|ہیں)"""),
        Regex("""کوئی\s+راستہ\s+نہیں"""),
        Regex("""میرا\s+برا\b"""),
        Regex("""کوئی\s+جرم\s+نہیں"""),
        Regex("""اپنے\s+دماغ\s+سے\s+باہر"""),
        Regex("""چلو\s+پر\b"""),
        Regex("""اوہ\s*[،,]?\s*میرے\s+خدا"""),
    )

    private val HASIL_OVERUSE_REGEX = Regex("""\bحاصل\s+(?:کرنا|کیا|کرتا|کرتی|کریں|کرو|ہوا)\b""")
    private val NEWSROOM_PASSIVE_REGEX = Regex("""کی\s+طرف\s+سے\s+[^\n۔؟!]{1,40}(?:گیا|گئی|گئے|جانا|جاتا|جاتی)""")
    private val ROBOTIC_COPULA_REGEX = Regex("""(?:^|[۔؟!،\s])(?:یہ|وہ|میں|تم|آپ)\s+ایک\s+[\u0600-\u06FF\s]{2,28}\s+(?:ہے|ہیں|ہوں|ہو|تھا|تھی)(?=[۔؟!\s]|$)""")

    private val AAP_REGISTER_REGEX = Regex("""(?:^|\s)(?:آپ|تشریف|فرمائیں|دیجیے|کیجیے|بتائیں|جائیں|آئیں|سنیں|دیکھیں)(?=\s|[۔؟!،]|$)""")
    private val TUM_REGISTER_REGEX = Regex("""(?:^|\s)(?:تم|تمہارا|تمہاری|تمہارے|تمہیں|کرو|بتاؤ|جاؤ|آؤ|سنو|دیکھو)(?=\s|[۔؟!،]|$)""")
    private val TU_REGISTER_REGEX = Regex("""(?:^|\s)(?:تو\s+نے|تجھے|تجھ\s+سے|تیرا|تیری|تیرے)(?=\s|[۔؟!،]|$)""")

    private val BOLCHAL_PARTICLE_OR_COMPOUND_REGEX = Regex(
        """(?:(?:^|\s)(?:یار|بھئی|بھائی|ارے|ابے|اوئے|ذرا|خیر|بھلا|ویسے|چلو|ہاں\s+نا|ہے\s+نا|تو\s+سہی|حد\s+ہو\s+گئی|کمال\s+ہے|بالکل|پلیز|سوری|شکریہ|ٹھیک\s+ہے)(?=\s|[۔؟!،]|$)|نا[؟!۔\s]*$|(?:کر|دے|لے|چلا|چلی|چلے|بول|مار|بیٹھ|پڑ|اٹھ|ڈال)\s+(?:دیا|دی|دیے|لیا|لی|لیے|گیا|گئی|گئے|بیٹھا|بیٹھی|پڑا|پڑی|چکا|چکی))"""
    )

    private val ENGLISH_TERMINAL_PUNCT_REGEX = Regex("""[.!?"'\u201D\u2019)\]\u2026—-]\s*$""")
    private val ENGLISH_CONTINUATION_TAIL_REGEX = Regex(
        """(?i)\b(?:to|the|a|an|and|or|but|that|which|who|because|if|when|while|of|in|on|at|for|with|from|into|shouldn't|couldn't|wouldn't|don't|didn't|won't|can't|isn't|aren't|wasn't|weren't|have|has|had|will|would|could|should|must|can|am|is|are|was|were)\s*$"""
    )
    private val URDU_HARD_STOP_REGEX = Regex("""[۔.]\s*$""")

    fun audit(
        urduDoc: CueDocument,
        referenceEnglishDoc: CueDocument? = null,
        uploaderName: String? = null,
        releaseComment: String? = null,
    ): MtAuditResult {
        val cues = urduDoc.cues
        if (cues.isEmpty()) {
            return MtAuditResult(
                mtDumpScore = 100,
                triggeredFamilies = setOf(MtSignalFamily.CATALOGUE_MT_METADATA),
                pronounWhiplashRate = 0.0,
                bolChalParticleRate = 0.0,
            )
        }

        val triggered = LinkedHashSet<MtSignalFamily>()
        var totalScore = 0
        val n = cues.size

        var polysemyHits = 0
        var hasilCueHits = 0
        for (cue in cues) {
            val clean = cue.text.replace("\u200F", "").replace("\u200E", "").trim()
            if (STANDALONE_POLYSEMY_CALQUES.any { it.matches(clean) }) {
                polysemyHits++
            }
            for (pat in PHRASE_CALQUES) {
                if (pat.containsMatchIn(clean)) polysemyHits++
            }
            if (HASIL_OVERUSE_REGEX.containsMatchIn(clean)) {
                hasilCueHits++
            }
        }
        val hasilOveruse = n >= 15 && (hasilCueHits.toDouble() / n.toDouble()) > 0.03
        if (polysemyHits > 0 || hasilOveruse) {
            triggered.add(MtSignalFamily.ZERO_CONTEXT_POLYSEMY_CALQUE)
            val calquePts = (polysemyHits * 22 + if (hasilOveruse) 14 else 0).coerceAtMost(72)
            totalScore += calquePts
        }

        var passiveHits = 0
        var copulaHits = 0
        for (cue in cues) {
            val clean = cue.text.replace("\u200F", "").trim()
            if (NEWSROOM_PASSIVE_REGEX.containsMatchIn(clean)) passiveHits++
            if (ROBOTIC_COPULA_REGEX.containsMatchIn(clean)) copulaHits++
        }
        val passiveRate = passiveHits.toDouble() / n.toDouble()
        val copulaRate = copulaHits.toDouble() / n.toDouble()
        val highPassive = (n >= 10 && passiveRate > 0.015) || passiveHits >= 2
        val highCopula = (n >= 10 && copulaRate > 0.03) || (copulaHits >= 2 && copulaRate >= 0.08)
        if (highPassive || highCopula) {
            triggered.add(MtSignalFamily.NEWSROOM_PASSIVE_AND_COPULA)
            if (highPassive) totalScore += 20
            if (highCopula) totalScore += 18
        }

        var registerPairs = 0
        var registerFlips = 0
        var prevReg: AddressRegister? = null
        var prevEndMs = Long.MIN_VALUE
        for (cue in cues) {
            val clean = cue.text.replace("\u200F", "").trim()
            val hasSpeakerTurnDash = clean.startsWith("-") || clean.startsWith("–") || clean.startsWith("—") ||
                clean.contains("\n-")
            val curReg = detectCueAddressRegister(clean)
            val gapMs = cue.span.startMs - prevEndMs
            if (curReg != null) {
                if (!hasSpeakerTurnDash && prevReg != null && gapMs in 0L..1_800L) {
                    registerPairs++
                    if (curReg != prevReg) {
                        registerFlips++
                    }
                }
                prevReg = curReg
            } else if (gapMs > 2_500L || hasSpeakerTurnDash) {
                prevReg = null
            }
            prevEndMs = cue.span.endMs
        }
        val whiplashRate = if (registerPairs > 0) registerFlips.toDouble() / registerPairs.toDouble() else 0.0
        if (registerPairs >= 3 && whiplashRate > 0.28) {
            triggered.add(MtSignalFamily.PRONOUN_REGISTER_WHIPLASH)
            totalScore += if (whiplashRate > 0.45) 26 else 22
        } else if (registerPairs >= 2 && registerFlips >= 2 && whiplashRate >= 0.50) {
            triggered.add(MtSignalFamily.PRONOUN_REGISTER_WHIPLASH)
            totalScore += 20
        }

        val shatteredHits = countContinuationShatters(cues, referenceEnglishDoc)
        if (shatteredHits >= 2) {
            triggered.add(MtSignalFamily.CONTINUATION_SHATTERING)
            totalScore += (14 + shatteredHits * 4).coerceAtMost(26)
        }

        var particleCueHits = 0
        for (cue in cues) {
            val clean = cue.text.replace("\u200F", "").trim()
            if (BOLCHAL_PARTICLE_OR_COMPOUND_REGEX.containsMatchIn(clean)) {
                particleCueHits++
            }
        }
        val particleRate = particleCueHits.toDouble() / n.toDouble()
        if (n >= 40 && particleRate < 0.01) {
            triggered.add(MtSignalFamily.BOLCHAL_PARTICLE_DEFICIT)
            totalScore += 20
        }

        val auxMeta = "${uploaderName.orEmpty()} ${releaseComment.orEmpty()}"
        if (CatalogueCandidateGate.matchBannedUploader(uploaderName, releaseComment.orEmpty()) != null) {
            triggered.add(MtSignalFamily.CATALOGUE_MT_METADATA)
            totalScore += 65
        } else if (auxMeta.contains("google translate", ignoreCase = true) ||
            auxMeta.contains("machine_translated", ignoreCase = true) ||
            auxMeta.contains("ai_translated", ignoreCase = true) ||
            auxMeta.contains("subtitle edit", ignoreCase = true)
        ) {
            triggered.add(MtSignalFamily.CATALOGUE_MT_METADATA)
            totalScore += 40
        }

        return MtAuditResult(
            mtDumpScore = totalScore.coerceIn(0, 100),
            triggeredFamilies = triggered,
            pronounWhiplashRate = whiplashRate,
            bolChalParticleRate = particleRate,
        )
    }

    private fun detectCueAddressRegister(cleanText: String): AddressRegister? {
        val hasAap = AAP_REGISTER_REGEX.containsMatchIn(cleanText)
        val hasTum = TUM_REGISTER_REGEX.containsMatchIn(cleanText)
        val hasTu = TU_REGISTER_REGEX.containsMatchIn(cleanText)
        val count = (if (hasAap) 1 else 0) + (if (hasTum) 1 else 0) + (if (hasTu) 1 else 0)
        if (count != 1) return null
        return when {
            hasAap -> AddressRegister.AAP
            hasTum -> AddressRegister.TUM
            else -> AddressRegister.TU
        }
    }

    private fun countContinuationShatters(
        urduCues: List<CueEntry>,
        referenceEnglishDoc: CueDocument?,
    ): Int {
        if (urduCues.size < 2) return 0
        var shatters = 0
        if (referenceEnglishDoc != null && referenceEnglishDoc.cues.isNotEmpty()) {
            val enCues = referenceEnglishDoc.cues
            val sameCardinality = urduCues.size == enCues.size
            var enIdx = 0
            for (i in 0 until urduCues.size - 1) {
                val urCue = urduCues[i]
                val enCue = if (sameCardinality) {
                    enCues[i]
                } else {
                    while (enIdx + 1 < enCues.size &&
                        abs(enCues[enIdx + 1].span.startMs - urCue.span.startMs) <
                        abs(enCues[enIdx].span.startMs - urCue.span.startMs)
                    ) {
                        enIdx++
                    }
                    enCues[enIdx].takeIf { abs(it.span.startMs - urCue.span.startMs) <= 800L }
                } ?: continue

                val enClean = LatinEnglishText.sanitize(enCue.text).text.trim()
                if (enClean.isEmpty()) continue
                val enContinues = !ENGLISH_TERMINAL_PUNCT_REGEX.containsMatchIn(enClean) ||
                    ENGLISH_CONTINUATION_TAIL_REGEX.containsMatchIn(enClean)
                if (!enContinues) continue

                val urClean = urCue.text.replace("\u200F", "").trim()
                if (URDU_HARD_STOP_REGEX.containsMatchIn(urClean)) {
                    shatters++
                }
            }
            return shatters
        }

        for (i in 0 until urduCues.size - 1) {
            val cur = urduCues[i]
            val next = urduCues[i + 1]
            if (next.span.startMs - cur.span.endMs > 500L) continue
            val curClean = cur.text.replace("\u200F", "").trim()
            val nextClean = next.text.replace("\u200F", "").trim()
            if (URDU_HARD_STOP_REGEX.containsMatchIn(curClean) &&
                URDU_HARD_STOP_REGEX.containsMatchIn(nextClean) &&
                curClean.split(' ').size <= 2 &&
                nextClean.split(' ').size <= 2
            ) {
                shatters++
            }
        }
        return shatters
    }
}

object UrduQualityGate {
    const val MT_HARD_REJECT_THRESHOLD = 60
    const val MT_AUTHENTIC_CEILING = 30

    fun verifyUrduOrthography(rawDecodedText: String): Result<Unit> =
        UrduOrthographyGate.verifyUrduOrthography(rawDecodedText)

    fun normalizeUrduTypography(raw: String): String =
        UrduOrthographyGate.normalizeUrduTypography(raw)

    fun auditMtNaturalness(
        urduDoc: CueDocument,
        referenceEnglishDoc: CueDocument? = null,
        uploaderName: String? = null,
        releaseComment: String? = null,
    ): MtAuditResult = UrduMtDumpDetector.audit(
        urduDoc = urduDoc,
        referenceEnglishDoc = referenceEnglishDoc,
        uploaderName = uploaderName,
        releaseComment = releaseComment,
    )

    fun admitNativeOrCatalogue(
        rawDecodedText: String,
        language: SubtitleLanguage,
        provenance: ProvenanceIdentity,
        alignment: AlignmentProof,
        meta: CatalogueCandidateMeta? = null,
        lookupSpec: MediaLookupSpec? = null,
        referenceEnglishDoc: CueDocument? = null,
    ): VerifiedSubtitleCandidate {
        var releaseAffinity = 0
        if (meta != null) {
            if (lookupSpec != null) {
                val gateRes = CatalogueCandidateGate.evaluatePreDownload(meta, lookupSpec)
                if (gateRes.isFailure) {
                    val cause = gateRes.exceptionOrNull()
                    if (cause is CandidateGateRejectedException) {
                        return cause.rejection
                    }
                    return VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader(
                        cause?.message ?: "pre_download_gate_rejected"
                    )
                }
                releaseAffinity = gateRes.getOrDefault(0)
            } else {
                if (meta.isHearingImpairedFlag) {
                    return VerifiedSubtitleCandidate.Rejected.HearingImpaired("hearing_impaired=true")
                }
                CatalogueCandidateGate.matchHearingImpairedMarker(meta.titleOrReleaseText)?.let {
                    return VerifiedSubtitleCandidate.Rejected.HearingImpaired(it)
                }
                CatalogueCandidateGate.matchBannedUploader(meta.uploaderName, meta.titleOrReleaseText)?.let {
                    return VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader(it)
                }
                if (meta.isMachineTranslatedFlag) {
                    return VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader("machine_translated=true")
                }
                releaseAffinity = meta.catalogue.intraCatalogueRank
            }
        }

        val textForParsing = if (language == SubtitleLanguage.URDU) {
            val ortho = verifyUrduOrthography(rawDecodedText)
            if (ortho.isFailure) {
                return VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(
                    ortho.exceptionOrNull()?.message ?: "urdu_orthography_rejected"
                )
            }
            normalizeUrduTypography(rawDecodedText)
        } else {
            rawDecodedText
        }

        val parsedDoc = CueDocument.parse(textForParsing, language).getOrElse { err ->
            return VerifiedSubtitleCandidate.Rejected.WrongScriptOrBrokenSmt(
                err.message ?: "cue_parse_failed"
            )
        }

        val normalizedDoc = if (language == SubtitleLanguage.URDU) {
            CueDocument.fromCues(
                language = SubtitleLanguage.URDU,
                rawCues = parsedDoc.cues.map { cue ->
                    cue.copy(text = normalizeUrduTypography(cue.text))
                },
            )
        } else {
            parsedDoc
        }

        val mtAudit = if (language == SubtitleLanguage.URDU) {
            auditMtNaturalness(
                urduDoc = normalizedDoc,
                referenceEnglishDoc = referenceEnglishDoc,
                uploaderName = meta?.uploaderName,
                releaseComment = meta?.releaseComment ?: meta?.titleOrReleaseText,
            )
        } else {
            null
        }

        if (mtAudit != null && mtAudit.mtDumpScore >= MT_HARD_REJECT_THRESHOLD) {
            return VerifiedSubtitleCandidate.Rejected.MtDump(
                audit = mtAudit,
                uploader = meta?.uploaderName,
            )
        }

        return when (provenance) {
            is ProvenanceIdentity.StreamProvider -> VerifiedSubtitleCandidate.StreamSyncedNative(
                provider = provenance.provider,
                language = language,
                document = normalizedDoc,
                alignment = alignment,
            )
            is ProvenanceIdentity.HumanCatalogue -> {
                val grade = if (mtAudit == null || mtAudit.mtDumpScore < MT_AUTHENTIC_CEILING) {
                    AuthenticityGrade.AUTHENTIC_HUMAN
                } else {
                    AuthenticityGrade.POST_EDITED_STIFF
                }
                VerifiedSubtitleCandidate.AlignedHumanCatalogue(
                    catalogue = provenance.catalogue,
                    language = language,
                    authenticity = grade,
                    mtAudit = mtAudit,
                    releaseAffinityScore = releaseAffinity,
                    document = normalizedDoc,
                    alignment = alignment,
                )
            }
            is ProvenanceIdentity.BolChalTranslated -> VerifiedSubtitleCandidate.Rejected.BannedSourceOrUploader(
                "Translated tracks must be admitted via SceneBolChalLocalizer, not admitNativeOrCatalogue"
            )
            is ProvenanceIdentity.UnknownExternal -> VerifiedSubtitleCandidate.AlignedHumanCatalogue(
                catalogue = CatalogueSourceKey.SUBTITLECAT,
                language = language,
                authenticity = if (mtAudit == null || mtAudit.mtDumpScore < MT_AUTHENTIC_CEILING) {
                    AuthenticityGrade.AUTHENTIC_HUMAN
                } else {
                    AuthenticityGrade.POST_EDITED_STIFF
                },
                mtAudit = mtAudit,
                releaseAffinityScore = releaseAffinity,
                document = normalizedDoc,
                alignment = alignment,
            )
        }
    }
}
