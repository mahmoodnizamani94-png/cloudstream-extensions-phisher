package com.phisher98.subtitles

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

enum class AddressRegister {
    TUM,
    AAP,
    TU,
}

enum class GenderHint {
    MASCULINE,
    FEMININE,
    UNKNOWN,
}

data class CharacterRegisterProfile(
    val canonicalName: String,
    val urduTransliteration: String,
    val gender: GenderHint = GenderHint.UNKNOWN,
    val defaultRegisterToPeers: AddressRegister = AddressRegister.TUM,
)

data class SpeakerContextCard(
    val characters: Map<String, CharacterRegisterProfile> = emptyMap(),
    val dominantSceneRegister: AddressRegister = AddressRegister.TUM,
    val speakerGenderHint: GenderHint = GenderHint.UNKNOWN,
    val genreToneHint: String? = null,
)

data class LatinSourceCue(
    val id: CueId,
    val span: CueSpan,
    val maxVisibleChars: Int,
    val text: LatinEnglishText,
    val endsWithRevealEllipsis: Boolean,
)

data class SceneWindow(
    val windowIndex: Int,
    val contextPrev: List<LatinSourceCue>,
    val targetCues: List<LatinSourceCue>,
    val contextNext: List<LatinSourceCue>,
    val speakerCard: SpeakerContextCard,
)

data class BolChalCueOut(
    val id: CueId,
    val urduText: String,
    val tierOrigin: LocalizationTierKey? = null,
)

data class BolChalConfig(
    val maxComfortCps: Double = 17.0,
    val minCueDurationMs: Long = 833L,
    val minSplittableCueDurationMs: Long = 1_800L,
    val interCueBlinkGapMs: Long = 84L,
    val maxTrailingSilenceExtendMs: Long = 2_800L,
    val maxLeadingSilenceExtendMs: Long = 200L,
    val sceneGapSplitMs: Long = 2_500L,
    val minRequiredCueCoverage: Double = 0.98,
    val maxConcurrentWindows: Int = 6,
    val llmEndpointUrl: String? = null,
    val llmApiKey: String? = null,
    val llmModelId: String? = null,
) {
    val hasByokLlm: Boolean
        get() = !llmEndpointUrl.isNullOrBlank() && !llmApiKey.isNullOrBlank() && !llmModelId.isNullOrBlank()

    val configHash: String
        get() = ContentSha256.sha256Hex(
            "cps=$maxComfortCps|llm=${llmEndpointUrl?.trim().orEmpty()}|model=${llmModelId?.trim().orEmpty()}"
        ).take(16)

    override fun toString(): String {
        val redactedKey = if (llmApiKey.isNullOrBlank()) "null" else "<redacted>"
        return "BolChalConfig(maxComfortCps=$maxComfortCps, minCueDurationMs=$minCueDurationMs, " +
            "minSplittableCueDurationMs=$minSplittableCueDurationMs, interCueBlinkGapMs=$interCueBlinkGapMs, " +
            "maxTrailingSilenceExtendMs=$maxTrailingSilenceExtendMs, maxLeadingSilenceExtendMs=$maxLeadingSilenceExtendMs, " +
            "sceneGapSplitMs=$sceneGapSplitMs, minRequiredCueCoverage=$minRequiredCueCoverage, " +
            "maxConcurrentWindows=$maxConcurrentWindows, " +
            "llmEndpointUrl=$llmEndpointUrl, llmApiKey=$redactedKey, llmModelId=$llmModelId)"
    }
}

fun interface BolChalTranslationTransport {
    suspend fun translateWindow(
        window: SceneWindow,
        config: BolChalConfig,
    ): Result<Pair<Map<CueId, BolChalCueOut>, LocalizationTierKey>>
}

typealias BolChalTransport = BolChalTranslationTransport

object BolChalPurifier {
    val SPOKEN_URDU_ENGLISH_LOANWORDS: Set<String> = setOf(
        "فون", "کال", "میسج", "اوکے", "سوری", "پلیز", "تھینکس",
        "ڈاکٹر", "پولیس", "آفس", "کار", "ٹکٹ", "چیک", "ویٹ",
        "منٹ", "سیکنڈ", "باس", "پلان", "پرابلم",
    )

    private val URDU_AAP_KA_ISTIQBAL_REGEX = Regex("""آپ\s+کا\s+استقبال\s+ہے""")
    private val WELCOME_GREETING_EN_REGEX = Regex(
        """(?i)(?:^\s*welcome\s*[.!]*\s*$|\bwelcome\s+(?:to|back|home|aboard|in|inside|here|everyone|all|folks|friends|ladies|gentlemen|sir|ma'am|madam)\b)"""
    )

    private val HINDI_AND_CALQUE_REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("""یہ\s+ایک\s+اچھا\s+خیال\s+ہے""") to "اچھا خیال ہے",
        Regex("""وہ\s+ایک\s+اچھا\s+آدمی\s+ہے""") to "وہ اچھا آدمی ہے",
        Regex("""(?:مقدس|بیل\s+کی)\s+گندگی""") to "کیا بکواس ہے",
        Regex("""کیا\s+جہنم(?:\s+ہے)?""") to "یہ کیا مصیبت ہے",
        Regex("""کوئی\s+راستہ\s+نہیں""") to "ناممکن",
        Regex("""میرا\s+برا(?=[۔؟!\s]|$)""") to "میری غلطی",
        Regex("""چلو\s+پر(?=[۔؟!\s]|$)""") to "چلو بھی",
        Regex("""(?<=^|\s)پریوار(?=\s|[۔؟!،]|$)""") to "گھر والے",
        Regex("""(?<=^|\s)وشواس(?=\s|[۔؟!،]|$)""") to "بھروسہ",
        Regex("""(?<=^|\s)سمے(?=\s|[۔؟!،]|$)""") to "وقت",
        Regex("""(?<=^|\s)کرپیا(?=\s|[۔؟!،]|$)""") to "پلیز",
        Regex("""(?<=^|\s)دھنیواد(?=\s|[۔؟!،]|$)""") to "شکریہ",
        Regex("""(?<=^|\s)(?:بھگوان|ایشور|پرمیشور)(?=\s|[۔؟!،]|$)""") to "خدا",
        Regex("""(?<=^|\s)سواگت(?=\s|[۔؟!،]|$)""") to "خوش آمدید",
        Regex("""(?<=^|\s)(?:نمسکار|نمستے)(?=\s|[۔؟!،]|$)""") to "سلام",
        Regex("""(?<=^|\s)چنتا(?=\s|[۔؟!،]|$)""") to "فکر",
        Regex("""(?<=^|\s)(?:پرنتو|کنتو)(?=\s|[۔؟!،]|$)""") to "لیکن",
        Regex("""(?<=^|\s)برقی\s+پیغام(?=\s|[۔؟!،]|$)""") to "میسج",
        Regex("""(?<=^|\s)ہاتف(?=\s|[۔؟!،]|$)""") to "فون",
        Regex("""(?<=^|\s)شفا\s*خانہ(?=\s|[۔؟!،]|$)""") to "ہسپتال",
    )

    private val LEFTOVER_LATIN_LOANWORD_REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bokay\b|\bok\b""") to "اوکے",
        Regex("""(?i)\bsorry\b""") to "سوری",
        Regex("""(?i)\bplease\b""") to "پلیز",
        Regex("""(?i)\bthanks\b|\bthank\s+you\b""") to "تھینکس",
        Regex("""(?i)\bdoctor\b""") to "ڈاکٹر",
        Regex("""(?i)\bpolice\b""") to "پولیس",
        Regex("""(?i)\boffice\b""") to "آفس",
        Regex("""(?i)\bphone\b""") to "فون",
        Regex("""(?i)\bcall\b""") to "کال",
        Regex("""(?i)\bmessage\b""") to "میسج",
        Regex("""(?i)\bticket\b""") to "ٹکٹ",
        Regex("""(?i)\bcheck\b""") to "چیک",
        Regex("""(?i)\bwait\b""") to "ویٹ",
        Regex("""(?i)\bminute\b""") to "منٹ",
        Regex("""(?i)\bsecond\b""") to "سیکنڈ",
        Regex("""(?i)\bboss\b""") to "باس",
        Regex("""(?i)\bplan\b""") to "پلان",
        Regex("""(?i)\bproblem\b""") to "پرابلم",
        Regex("""(?i)\bcar\b""") to "کار",
    )

    private val ENGLISH_RIGHT_REGEX = Regex("""(?i)^\s*right\s*([.?!]*)\s*$""")
    private val ENGLISH_FINE_REGEX = Regex("""(?i)^\s*(?:i'?m\s+)?fine\s*([.?!]*)\s*$""")
    private val ENGLISH_COOL_REGEX = Regex("""(?i)^\s*cool\s*([.?!]*)\s*$""")
    private val ENGLISH_HONEY_VOCATIVE_REGEX = Regex("""(?i)\bhoney\s*,""")
    private val URDU_SHAHAD_VOCATIVE_REGEX = Regex("""شہد\s*[،,]""")

    data class PurifiedCueResult(
        val text: String,
        val registerRewrittenByRegex: Boolean,
    )

    private val CUE_FORMAL_OVERRIDE_REGEX = Regex(
        """(?i)\b(?:sir|ma'am|madam|your\s+honor|your\s+majesty|officer|detective|professor|doctor|dr\.|mr\.|mrs\.|president|general|father|mother|boss)\b"""
    )
    private val CUE_INFORMAL_OVERRIDE_REGEX = Regex(
        """(?i)\b(?:you\s+bastard|you\s+idiot|you\s+coward|you\s+traitor|shut\s+up|get\s+lost|go\s+to\s+hell|how\s+dare\s+you|bro|yaar|buddy|dude|mate|honey|darling)\b"""
    )
    private val CUE_TU_OVERRIDE_REGEX = Regex(
        """(?i)\b(?:you\s+bastard|you\s+idiot|you\s+coward|you\s+traitor|shut\s+up|get\s+lost|go\s+to\s+hell|damn\s+you)\b"""
    )
    private val ENGLISH_FIRST_PERSON_SUBJECT_REGEX = Regex(
        """(?i)(?:^|[,;.!?]\s+(?:(?:and|but|so)\s+)?)i(?:'m|'ll|'ve|'d|\s+(?:am|was|will|can|could|want|know|think|have|need|went|came))\b"""
    )
    internal val CUE_FEMININE_SELF_REGEX = Regex(
        """(?i)(?:\bi'?m\s+a\s+(?:woman|girl|mother|sister|wife|daughter|queen)\b|(?:^|[,;.!?]\s*)as\s+(?:his|her|your|their)\s+(?:wife|sister|mother|daughter)\s*,\s*i\b)"""
    )
    internal val CUE_MASCULINE_SELF_REGEX = Regex(
        """(?i)(?:\bi'?m\s+a\s+(?:man|boy|father|brother|husband|son|king)\b|(?:^|[,;.!?]\s*)as\s+(?:his|her|your|their)\s+(?:husband|brother|father|son)\s*,\s*i\b)"""
    )

    private const val POSTPOSITION_GUARD = """(?!\s+(?:کا|کی|کے|کو|نے|سے|پر|میں|تک)(?=\s|[۔؟!،]|$))"""
    private const val INTRA_CLAUSE_SPAN =
        """((?:\s+(?!(?:کہ|اور|مگر|لیکن|کیونکہ|جب|جو|اگر|ورنہ|یا|کا|کی|کے|کو|نے|وہ|ہم)(?=\s|[۔؟!،]|$))[^\s\n۔؟!،]+)*)"""

    private val AAP_TO_TUM_REWRITES: List<Pair<Regex, String>> = listOf(
        // 1. Possessive and oblique postpositions MUST run first so non-nominative آپ never retargets 3rd-person auxiliaries.
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کا|تیرا)(?=\s|[۔؟!،]|$)""") to "تمہارا",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کی|تیری)(?=\s|[۔؟!،]|$)""") to "تمہاری",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کے|تیرے)(?=\s|[۔؟!،]|$)""") to "تمہارے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کو|تجھے|تجھ\s+کو)(?=\s|[۔؟!،]|$)""") to "تمہیں",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+نے|تو\s+نے)(?=\s|[۔؟!،]|$)""") to "تم نے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+سے|تجھ\s+سے)(?=\s|[۔؟!،]|$)""") to "تم سے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+پر|تجھ\s+پر)(?=\s|[۔؟!،]|$)""") to "تم پر",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+میں|تجھ\s+میں)(?=\s|[۔؟!،]|$)""") to "تم میں",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+تک|تجھ\s+تک)(?=\s|[۔؟!،]|$)""") to "تم تک",
        // 2. Strictly nominative intra-clause verb agreement (never crosses کا/کی/کے/کو/نے or کہ/اور/جب/جو).
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 رہے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 رہی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 سکتے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 سکتی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 کرتے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 کرتی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 چاہتے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 چاہتی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 جانتے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 جانتی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گئے\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 گئے ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گئی\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 گئی ہو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+ہیں(?=[۔؟!،\s]|$)""") to "تم$1 ہو",
        // 3. Honorific imperatives and standalone nominative آپ.
        Regex("""(?<=^|\s|[،,])بتائیے(?=\s|[۔؟!،]|$)""") to "بتاؤ",
        Regex("""(?<=^|\s|[،,])سنیے(?=\s|[۔؟!،]|$)""") to "سنو",
        Regex("""(?<=^|\s|[،,])دیکھیے(?=\s|[۔؟!،]|$)""") to "دیکھو",
        Regex("""(?<=^|\s|[،,])دیجیے(?=\s|[۔؟!،]|$)""") to "دو",
        Regex("""(?<=^|\s|[،,])کیجیے(?=\s|[۔؟!،]|$)""") to "کرو",
        Regex("""(?<=^|\s|[،,])آپ$POSTPOSITION_GUARD(?=\s|[۔؟!،]|$)""") to "تم",
    )

    private val TO_TU_REWRITES: List<Pair<Regex, String>> = listOf(
        // 1. Possessive and oblique postpositions first.
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کا|تمہارا)(?=\s|[۔؟!،]|$)""") to "تیرا",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کی|تمہاری)(?=\s|[۔؟!،]|$)""") to "تیری",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کے|تمہارے)(?=\s|[۔؟!،]|$)""") to "تیرے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+کو|تمہیں|تم\s+کو)(?=\s|[۔؟!،]|$)""") to "تجھے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+نے|تم\s+نے)(?=\s|[۔؟!،]|$)""") to "تو نے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+سے|تم\s+سے)(?=\s|[۔؟!،]|$)""") to "تجھ سے",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+پر|تم\s+پر)(?=\s|[۔؟!،]|$)""") to "تجھ پر",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+میں|تم\s+میں)(?=\s|[۔؟!،]|$)""") to "تجھ میں",
        Regex("""(?<=^|\s|[،,])(?:آپ\s+تک|تم\s+تک)(?=\s|[۔؟!،]|$)""") to "تجھ تک",
        // 2. Strictly nominative intra-clause verb agreement for تو.
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 رہا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 رہی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 سکتا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 سکتی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 کرتا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 کرتی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 چاہتا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 چاہتی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 جانتا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 جانتی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گئے\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 گیا ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گئی\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 گئی ہے",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+(?:ہیں|ہو)(?=[۔؟!،\s]|$)""") to "تو$1 ہے",
        // 3. Intimate/angry imperatives and standalone nominative تو.
        Regex("""(?<=^|\s|[،,])چپ\s+(?:رہیے|رہو)(?=\s|[۔؟!،]|$)""") to "چپ رہ",
        Regex("""(?<=^|\s|[،,])بند\s+(?:کیجیے|کرو)(?=\s|[۔؟!،]|$)""") to "بند کر",
        Regex("""(?<=^|\s|[،,])دفع\s+ہو\s+(?:جائیے|جاؤ)(?=\s|[۔؟!،]|$)""") to "دفع ہو جا",
        Regex("""(?<=^|\s|[،,])(?:بتائیے|بتاؤ)(?=\s|[۔؟!،]|$)""") to "بتا",
        Regex("""(?<=^|\s|[،,])(?:سنیے|سنو)(?=\s|[۔؟!،]|$)""") to "سن",
        Regex("""(?<=^|\s|[،,])(?:دیکھیے|دیکھو)(?=\s|[۔؟!،]|$)""") to "دیکھ",
        Regex("""(?<=^|\s|[،,])دیجیے(?=\s|[۔؟!،]|$)""") to "دے",
        Regex("""(?<=^|\s|[،,])(?:کیجیے|کرو)(?=\s|[۔؟!،]|$)""") to "کر",
        Regex("""(?<=^|\s|[،,])(?:آپ|تم)$POSTPOSITION_GUARD(?=\s|[۔؟!،]|$)""") to "تو",
    )

    private val TUM_TO_AAP_REWRITES: List<Pair<Regex, String>> = listOf(
        // 1. Possessive and oblique postpositions first.
        Regex("""(?<=^|\s|[،,])(?:تمہارا|تیرا)(?=\s|[۔؟!،]|$)""") to "آپ کا",
        Regex("""(?<=^|\s|[،,])(?:تمہاری|تیری)(?=\s|[۔؟!،]|$)""") to "آپ کی",
        Regex("""(?<=^|\s|[،,])(?:تمہارے|تیرے)(?=\s|[۔؟!،]|$)""") to "آپ کے",
        Regex("""(?<=^|\s|[،,])(?:تمہیں|تجھے|تم\s+کو|تجھ\s+کو)(?=\s|[۔؟!،]|$)""") to "آپ کو",
        Regex("""(?<=^|\s|[،,])(?:تم\s+نے|تو\s+نے)(?=\s|[۔؟!،]|$)""") to "آپ نے",
        Regex("""(?<=^|\s|[،,])(?:تم\s+سے|تجھ\s+سے)(?=\s|[۔؟!،]|$)""") to "آپ سے",
        Regex("""(?<=^|\s|[،,])(?:تم\s+پر|تجھ\s+پر)(?=\s|[۔؟!،]|$)""") to "آپ پر",
        Regex("""(?<=^|\s|[،,])(?:تم\s+میں|تجھ\s+میں)(?=\s|[۔؟!،]|$)""") to "آپ میں",
        Regex("""(?<=^|\s|[،,])(?:تم\s+تک|تجھ\s+تک)(?=\s|[۔؟!،]|$)""") to "آپ تک",
        // 2. Strictly nominative intra-clause verb agreement.
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہے\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 رہے ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہی\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 رہی ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتے\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 سکتے ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتی\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 سکتی ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتے\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 کرتے ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتی\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 کرتی ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتے\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 چاہتے ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتی\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 چاہتی ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتے\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 جانتے ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتی\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 جانتی ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+ہو(?=[۔؟!،\s]|$)""") to "آپ$1 ہیں",
        Regex("""(?<=^|\s|[،,])تم$POSTPOSITION_GUARD(?=\s|[۔؟!،]|$)""") to "آپ",
    )

    private val FIRST_PERSON_MASC_TO_FEM_REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 رہی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہا\s+تھا(?=[۔؟!،\s]|$)""") to "میں$1 رہی تھی",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 سکتی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 کرتی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 چاہتی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 جانتی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سوچتا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 سوچتی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گیا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 گئی ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+آیا\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 آئی ہوں",
    )

    private val FIRST_PERSON_FEM_TO_MASC_REWRITES: List<Pair<Regex, String>> = listOf(
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 رہا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+رہی\s+تھی(?=[۔؟!،\s]|$)""") to "میں$1 رہا تھا",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سکتی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 سکتا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+کرتی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 کرتا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+چاہتی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 چاہتا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+جانتی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 جانتا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+سوچتی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 سوچتا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+گئی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 گیا ہوں",
        Regex("""(?<=^|\s|[،,])میں$POSTPOSITION_GUARD$INTRA_CLAUSE_SPAN\s+آئی\s+ہوں(?=[۔؟!،\s]|$)""") to "میں$1 آیا ہوں",
    )

    fun purify(
        rawUrdu: String,
        sourceEnglish: LatinEnglishText? = null,
        speakerCard: SpeakerContextCard? = null,
        allowRegexGenderRewrite: Boolean = true,
    ): String = purifyDetailed(rawUrdu, sourceEnglish, speakerCard, allowRegexGenderRewrite).text

    fun purifyDetailed(
        rawUrdu: String,
        sourceEnglish: LatinEnglishText? = null,
        speakerCard: SpeakerContextCard? = null,
        allowRegexGenderRewrite: Boolean = true,
    ): PurifiedCueResult {
        var text = UrduOrthographyGate.normalizeUrduTypography(
            rawUrdu.replace("\u200F", "").replace("\u200E", "")
        )
        if (text.isEmpty()) return PurifiedCueResult(text, registerRewrittenByRegex = false)

        val en = sourceEnglish?.text?.trim().orEmpty()
        if (URDU_AAP_KA_ISTIQBAL_REGEX.containsMatchIn(text)) {
            val welcomeRewrite = if (en.isNotEmpty() && WELCOME_GREETING_EN_REGEX.containsMatchIn(en)) {
                "خوش آمدید"
            } else {
                "کوئی بات نہیں"
            }
            text = URDU_AAP_KA_ISTIQBAL_REGEX.replace(text, welcomeRewrite)
        }
        if (en.isNotEmpty()) {
            ENGLISH_RIGHT_REGEX.matchEntire(en)?.let { m ->
                if (text.contains("دائیں")) {
                    val q = m.groupValues[1].contains('?')
                    text = if (q) "ہے نا؟" else "ٹھیک ہے۔"
                }
            }
            if (ENGLISH_FINE_REGEX.matches(en) && text.contains("جرمانہ")) {
                text = "ٹھیک ہے۔"
            }
            if (ENGLISH_COOL_REGEX.matches(en) && text.contains("ٹھنڈا")) {
                text = "زبردست!"
            }
            if (ENGLISH_HONEY_VOCATIVE_REGEX.containsMatchIn(en)) {
                text = URDU_SHAHAD_VOCATIVE_REGEX.replace(text, "جان،")
            }
        }

        for ((pattern, replacement) in HINDI_AND_CALQUE_REWRITES) {
            text = pattern.replace(text, replacement)
        }
        for ((pattern, replacement) in LEFTOVER_LATIN_LOANWORD_REWRITES) {
            text = pattern.replace(text, replacement)
        }

        var registerRewritten = false
        if (speakerCard != null) {
            val hasFormalOverride = en.isNotEmpty() && CUE_FORMAL_OVERRIDE_REGEX.containsMatchIn(en)
            val hasInformalOverride = en.isNotEmpty() && CUE_INFORMAL_OVERRIDE_REGEX.containsMatchIn(en)
            val hasTuOverride = !hasFormalOverride && en.isNotEmpty() && CUE_TU_OVERRIDE_REGEX.containsMatchIn(en)
            val effectiveRegister = when {
                hasFormalOverride -> AddressRegister.AAP
                hasTuOverride -> AddressRegister.TU
                speakerCard.dominantSceneRegister == AddressRegister.AAP && hasInformalOverride -> AddressRegister.TUM
                else -> speakerCard.dominantSceneRegister
            }
            val beforeRegister = text
            val table = when (effectiveRegister) {
                AddressRegister.TUM -> AAP_TO_TUM_REWRITES
                AddressRegister.TU -> TO_TU_REWRITES
                AddressRegister.AAP -> TUM_TO_AAP_REWRITES
            }
            for ((pat, rep) in table) {
                text = pat.replace(text, rep)
            }
            if (text != beforeRegister) {
                registerRewritten = true
            }
        }

        if (allowRegexGenderRewrite && en.isNotEmpty() && ENGLISH_FIRST_PERSON_SUBJECT_REGEX.containsMatchIn(en)) {
            val femMatch = CUE_FEMININE_SELF_REGEX.containsMatchIn(en)
            val mascMatch = CUE_MASCULINE_SELF_REGEX.containsMatchIn(en)
            val effectiveGender = when {
                femMatch && !mascMatch -> GenderHint.FEMININE
                mascMatch && !femMatch -> GenderHint.MASCULINE
                else -> speakerCard?.speakerGenderHint ?: GenderHint.UNKNOWN
            }
            when (effectiveGender) {
                GenderHint.FEMININE -> {
                    for ((pat, rep) in FIRST_PERSON_MASC_TO_FEM_REWRITES) {
                        text = pat.replace(text, rep)
                    }
                }
                GenderHint.MASCULINE -> {
                    for ((pat, rep) in FIRST_PERSON_FEM_TO_MASC_REWRITES) {
                        text = pat.replace(text, rep)
                    }
                }
                GenderHint.UNKNOWN -> Unit
            }
        }

        if (sourceEnglish != null && en.endsWith("...") && !text.endsWith("...") && !text.endsWith("…")) {
            text = text.trimEnd('۔', '.', ' ') + "..."
        }

        return PurifiedCueResult(
            text = UrduOrthographyGate.normalizeUrduTypography(text),
            registerRewrittenByRegex = registerRewritten,
        )
    }
}

object UrduCpsRefiner {
    private val TAG_CLEAN_REGEX = Regex("""(?:\{[^}]*\}|<[^>]*>)""")

    private val FILLER_CONDENSATIONS: List<Pair<Regex, String>> = listOf(
        Regex("""میں\s+یہ\s+کہہ\s+رہ[اےی]\s+تھ[اےی]\s+کہ\s*""") to "",
        Regex("""میرا\s+مطلب\s+(?:یہ\s+)?ہے\s+کہ\s*""") to "",
        Regex("""دراصل\s+بات\s+یہ\s+ہے\s+کہ\s*""") to "بات یہ ہے ",
        Regex("""میرے\s+خیال\s+(?:میں|سے)""") to "لگتا ہے",
        Regex("""(?:براہ|برائے)\s+مہربانی""") to "پلیز",
        Regex("""مہربانی\s+فرما\s+کر""") to "پلیز",
        Regex("""معاف\s+کیجیے(?:\s+گا)?""") to "سوری",
        Regex("""بالکل\s+بھی\s+نہیں""") to "بالکل نہیں",
    )

    private val TIGHT_HEMMED_CONDENSATIONS: List<Pair<Regex, String>> = listOf(
        Regex("""وہاں\s+سب\s+لوگ\s+""") to "سب ",
        Regex("""سب\s+لوگ\s+""") to "سب ",
        Regex("""ہر\s+ایک\s+""") to "ہر ",
        Regex("""(?<=^|\s)مسلسل\s+""") to "",
        Regex("""(?<=^|\s)(?:درحقیقت|حقیقت\s+میں|یقینی\s+طور\s+پر)\s+""") to "",
        Regex("""فوری\s+طور\s+پر""") to "فوراً",
        Regex("""رکھے\s+ہوئے\s+تھے""") to "رکھے تھے",
        Regex("""رکھی\s+ہوئی\s+تھی""") to "رکھی تھی",
        Regex("""رکھا\s+ہوا\s+تھا""") to "رکھا تھا",
        Regex("""تمہاری\s+ہر\s+حرکت\s+پر\s+نظر""") to "تم پر نظر",
        Regex("""آپ\s+کی\s+ہر\s+حرکت\s+پر\s+نظر""") to "آپ پر نظر",
        Regex("""((?:^|\s)تم\s+نے\s+)مجھے\s+پہلے\s+(?=سچ\s+کیوں)""") to "$1",
        Regex("""(?<=^|\s)پہلے\s+(?=سچ\s+کیوں)""") to "",
        Regex("""((?:^|\s)(?:بتایا|سنایا|دکھایا|پوچھا|بھیجا))\s+تھا(?=[؟!۔]|$)""") to "$1",
    )

    private val OPTIONAL_MODIFIER_WORDS = setOf(
        "مسلسل", "بالکل", "یقیناً", "ہمیشہ", "بہت", "زیادہ", "پہلے", "وہاں", "یہاں", "شدید", "سارا", "سارے", "پورا",
    )

    private val URDU_POSTPOSITIONS_AND_PARTICLES = setOf(
        "میں", "نے", "کو", "سے", "کا", "کی", "کے", "پر", "پہ",
        "تک", "بھر", "والا", "والی", "والے", "ہی", "بھی", "تو", "نا",
        "ساتھ", "لیے", "بعد", "پہلے", "طرح", "بارے", "خاطر", "بغیر",
        "علاوہ", "اندر", "باہر", "اوپر", "نیچے", "پاس", "سامنے",
    )

    private val URDU_COMPOUND_VERB_AUXILIARIES = setOf(
        "رہا", "رہی", "رہے", "ہے", "ہیں", "ہوں", "ہو",
        "تھا", "تھی", "تھے", "گا", "گی", "گے",
        "گیا", "گئی", "گئے", "دیا", "دی", "دیے",
        "لیا", "لی", "لیے", "چکا", "چکی", "چکے",
        "سکتا", "سکتی", "سکتے", "سکا", "سکی", "سکے",
        "پڑا", "پڑی", "پڑے", "بیٹھا", "بیٹھی", "بیٹھے",
        "جائے", "جائیں", "جاؤں", "ہوتا", "ہوتی", "ہوتے",
        "ہوا", "ہوئی", "ہوئے", "کرنا", "کرتا", "کرتی", "کرتے",
        "جا", "آ", "پڑ", "اٹھ", "بیٹھ", "چل", "نکل", "رکھ",
        "لیتا", "لیتی", "لیتے", "دیتا", "دیتی", "دیتے",
        "جانا", "آنا", "رہنا", "پایا", "پائی", "پائے",
    )

    private val NEGATION_PREFIX_WORDS = setOf("نہیں", "مت", "نہ")

    private val CLAUSE_CONJUNCTIONS = setOf(
        "کہ", "لیکن", "مگر", "کیونکہ", "ورنہ", "تاکہ", "جبکہ", "اگر", "پھر", "اور", "یا",
    )

    private val TRAILING_POSTPOSITIONS = setOf("میں", "سے", "پر", "پہ", "کو", "تک", "لیے", "بعد", "ساتھ")

    fun visibleCharCount(text: String): Int {
        val noTags = TAG_CLEAN_REGEX.replace(text, "")
        var count = 0
        for (ch in noTags) {
            when (ch) {
                '\u200F', '\u200E', '\u200C', '\u200D', '\uFEFF', '\n', '\r' -> Unit
                in '\u202A'..'\u202E', in '\u2066'..'\u2069' -> Unit
                else -> count++
            }
        }
        return count
    }

    fun computeVisibleCps(text: String, durationMs: Long): Double {
        val dur = durationMs.coerceAtLeast(1L)
        return (visibleCharCount(text).toDouble() * 1_000.0) / dur.toDouble()
    }

    fun condenseFillers(text: String): String {
        var out = text
        for ((pat, rep) in FILLER_CONDENSATIONS) {
            out = pat.replace(out, rep)
        }
        return UrduOrthographyGate.normalizeUrduTypography(out)
    }

    private fun condenseHemmedToComfortBudget(text: String, durationMs: Long, maxComfortCps: Double): String {
        val maxChars = ((durationMs.coerceAtLeast(1L).toDouble() * maxComfortCps) / 1_000.0).toInt().coerceAtLeast(8)
        var cur = text
        if (visibleCharCount(cur) <= maxChars) return cur

        for ((pat, rep) in TIGHT_HEMMED_CONDENSATIONS) {
            cur = UrduOrthographyGate.normalizeUrduTypography(pat.replace(cur, rep))
            if (visibleCharCount(cur) <= maxChars) return cur
        }

        val terminalPunct = cur.lastOrNull()?.takeIf { it == '۔' || it == '؟' || it == '!' }?.toString().orEmpty()
        val coordCommaRegex = Regex("""(?:\s*،)?\s+اور\s+(?=[^\s۔؟!\n])""")
        if (coordCommaRegex.containsMatchIn(cur)) {
            cur = UrduOrthographyGate.normalizeUrduTypography(coordCommaRegex.replace(cur, "، "))
            if (visibleCharCount(cur) <= maxChars) return cur
        }

        val words = cur.split(' ').filter { it.isNotEmpty() }.toMutableList()
        var idx = 0
        while (idx < words.size && words.size > 3 && visibleCharCount(words.joinToString(" ")) > maxChars) {
            val clean = stripPunctuation(words[idx])
            if (clean in OPTIONAL_MODIFIER_WORDS) {
                words.removeAt(idx)
            } else {
                idx++
            }
        }
        cur = UrduOrthographyGate.normalizeUrduTypography(words.joinToString(" "))
        if (visibleCharCount(cur) <= maxChars) return cur

        while (words.size > 3 && visibleCharCount(words.joinToString(" ")) > maxChars) {
            val firstClean = stripPunctuation(words.first())
            if (firstClean == "کیونکہ" || firstClean == "دراصل" || firstClean == "ویسے" || firstClean == "خیر") {
                words.removeAt(0)
                continue
            }
            break
        }
        cur = UrduOrthographyGate.normalizeUrduTypography(words.joinToString(" "))
        if (terminalPunct.isNotEmpty() && !cur.endsWith(terminalPunct) && !cur.endsWith("...")) {
            cur = cur.trimEnd('،', ',', ' ') + terminalPunct
        }
        return cur
    }

    private fun stripPunctuation(word: String): String =
        word.trim('،', ',', '۔', '.', '؟', '?', '!', '—', '-', '؛', ';', '"', '\'', '(', ')', '\u200F', '\u200E')

    private fun endsWithClausePunctuation(word: String): Boolean {
        val trimmed = word.trimEnd('\u200F', '\u200E', '"', '\'')
        val last = trimmed.lastOrNull() ?: return false
        return last == '،' || last == ',' || last == '—' || last == '؛' || last == ';' ||
            last == '۔' || last == '؟' || last == '!'
    }

    internal fun findBestSyntacticSplitIndex(words: List<String>): Int? {
        if (words.size < 4) return null
        val lengths = IntArray(words.size) { visibleCharCount(words[it]).coerceAtLeast(1) }
        val totalChars = lengths.sum() + (words.size - 1)
        if (totalChars < 12) return null

        var prefixChars = 0
        var bestIdx: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (b in 0 until words.size - 1) {
            prefixChars += lengths[b] + if (b > 0) 1 else 0
            val ratio = prefixChars.toDouble() / totalChars.toDouble()
            if (ratio < 0.30 || ratio > 0.70) continue

            val leftWord = words[b]
            val leftClean = stripPunctuation(leftWord)
            val rightClean = stripPunctuation(words[b + 1])
            val hasPunct = endsWithClausePunctuation(leftWord)

            if (!hasPunct) {
                if (rightClean in URDU_POSTPOSITIONS_AND_PARTICLES) continue
                if (rightClean in URDU_COMPOUND_VERB_AUXILIARIES) continue
                if (leftClean in NEGATION_PREFIX_WORDS) continue
                if (leftClean == "کا" || leftClean == "کی" || leftClean == "کے") continue
                if (leftClean == "ہر" || leftClean == "سب" || leftClean == "میرے" || leftClean == "تمہارے" || leftClean == "اپنے" || leftClean == "ہمارے") continue
            }

            var score = -abs(ratio - 0.50) * 35.0
            when {
                hasPunct -> score += 100.0
                rightClean in CLAUSE_CONJUNCTIONS -> score += 80.0
                leftClean in TRAILING_POSTPOSITIONS -> score += 55.0
                else -> score += 10.0
            }

            if (score > bestScore) {
                bestScore = score
                bestIdx = b
            }
        }
        return bestIdx
    }

    private fun wrapBalancedLines(singleLineText: String, maxLineVisibleChars: Int = 42): String {
        val flat = singleLineText.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (visibleCharCount(flat) <= maxLineVisibleChars) return flat
        val words = flat.split(' ').filter { it.isNotEmpty() }
        val splitIdx = findBestSyntacticSplitIndex(words) ?: return flat
        val line1 = words.subList(0, splitIdx + 1).joinToString(" ")
        val line2 = words.subList(splitIdx + 1, words.size).joinToString(" ")
        return "$line1\n$line2"
    }

    fun refineDocument(
        cues: List<CueEntry>,
        config: BolChalConfig = BolChalConfig(),
        videoDurationMs: Long? = null,
    ): List<CueEntry> {
        if (cues.isEmpty()) return emptyList()
        val working = cues.sortedBy { it.span.startMs }.toMutableList()
        val output = ArrayList<CueEntry>(working.size + 8)
        var nextSyntheticId = (working.maxOfOrNull { it.id.value } ?: 0) + 10_000
        var prevFinalEndMs = -config.interCueBlinkGapMs

        for (i in working.indices) {
            val cur = working[i]
            val nextStartMs = working.getOrNull(i + 1)?.span?.startMs
                ?: videoDurationMs
                ?: (cur.span.endMs + config.maxTrailingSilenceExtendMs + config.interCueBlinkGapMs)

            var text = cur.text.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
            var startMs = max(cur.span.startMs, prevFinalEndMs + config.interCueBlinkGapMs)
            var endMs = max(startMs + 120L, cur.span.endMs)

            val maxAllowedEnd = (nextStartMs - config.interCueBlinkGapMs).coerceAtLeast(startMs + 120L)
            if (endMs > maxAllowedEnd) {
                endMs = maxAllowedEnd
            }

            val maxLeadBorrowMs = config.maxLeadingSilenceExtendMs.coerceAtLeast(0L)
            val minEarliestStart = max(0L, max(cur.span.startMs - maxLeadBorrowMs, prevFinalEndMs + config.interCueBlinkGapMs))
            val maxUnoccupiedSilenceEnd = min(maxAllowedEnd, cur.span.endMs + config.maxTrailingSilenceExtendMs.coerceAtLeast(0L))

            if (endMs - startMs < config.minCueDurationMs) {
                endMs = min(maxUnoccupiedSilenceEnd, startMs + config.minCueDurationMs)
            }

            if (computeVisibleCps(text, endMs - startMs) > config.maxComfortCps) {
                val neededDurationMs = ((visibleCharCount(text).toDouble() * 1_000.0) / config.maxComfortCps).roundToLong() + 8L
                if (startMs > minEarliestStart) {
                    val leadGain = min(startMs - minEarliestStart, (neededDurationMs - (endMs - startMs)).coerceAtLeast(0L))
                    startMs -= leadGain
                }
                if (endMs < maxUnoccupiedSilenceEnd && computeVisibleCps(text, endMs - startMs) > config.maxComfortCps) {
                    val stillNeeded = (neededDurationMs - (endMs - startMs)).coerceAtLeast(0L)
                    endMs = min(maxUnoccupiedSilenceEnd, endMs + stillNeeded)
                }
            }

            if (computeVisibleCps(text, endMs - startMs) > config.maxComfortCps) {
                val condensed = condenseFillers(text)
                if (condensed.isNotBlank()) {
                    text = condensed
                }
                if (computeVisibleCps(text, endMs - startMs) > config.maxComfortCps && endMs < maxUnoccupiedSilenceEnd) {
                    val neededAfterCondenseMs = ((visibleCharCount(text).toDouble() * 1_000.0) / config.maxComfortCps).roundToLong() + 12L
                    val stillNeeded = (neededAfterCondenseMs - (endMs - startMs)).coerceAtLeast(0L)
                    endMs = min(maxUnoccupiedSilenceEnd, endMs + stillNeeded)
                }
            }

            val exceedsTwoLineBudget = visibleCharCount(text) > 84
            val minEachSplitCueMs = max(1_200L, config.minCueDurationMs)
            if (exceedsTwoLineBudget) {
                val words = text.split(' ').filter { it.isNotEmpty() }
                val splitIdx = findBestSyntacticSplitIndex(words)
                if (splitIdx != null) {
                    val part1 = words.subList(0, splitIdx + 1).joinToString(" ")
                    val part2 = words.subList(splitIdx + 1, words.size).joinToString(" ")
                    val c1 = visibleCharCount(part1).coerceAtLeast(1)
                    val c2 = visibleCharCount(part2).coerceAtLeast(1)
                    val needed1Ms = ((c1.toDouble() * 1_000.0) / config.maxComfortCps).roundToLong() + 8L
                    val needed2Ms = ((c2.toDouble() * 1_000.0) / config.maxComfortCps).roundToLong() + 8L
                    val neededSplitTotalMs = needed1Ms + needed2Ms + config.interCueBlinkGapMs
                    if (endMs - startMs < neededSplitTotalMs && endMs < maxUnoccupiedSilenceEnd) {
                        endMs = min(maxUnoccupiedSilenceEnd, startMs + neededSplitTotalMs)
                    }
                    val preSplitCps = computeVisibleCps(text, endMs - startMs)
                    val usableDur = (endMs - startMs) - config.interCueBlinkGapMs
                    if (usableDur >= minEachSplitCueMs * 2L) {
                        val rawPart1Dur = ((usableDur.toDouble() * c1.toDouble()) / (c1 + c2).toDouble()).roundToLong()
                        val part1Dur = rawPart1Dur.coerceIn(
                            minEachSplitCueMs,
                            usableDur - minEachSplitCueMs,
                        )
                        val splitEnd1 = startMs + part1Dur
                        val splitStart2 = splitEnd1 + config.interCueBlinkGapMs
                        val cps1 = computeVisibleCps(part1, splitEnd1 - startMs)
                        val cps2 = computeVisibleCps(part2, endMs - splitStart2)
                        if ((cps1 <= config.maxComfortCps && cps2 <= config.maxComfortCps) || max(cps1, cps2) < preSplitCps) {
                            val finalPart1 = if (cps1 > config.maxComfortCps) {
                                condenseHemmedToComfortBudget(part1, splitEnd1 - startMs, config.maxComfortCps)
                            } else {
                                part1
                            }
                            val finalPart2 = if (cps2 > config.maxComfortCps) {
                                condenseHemmedToComfortBudget(part2, endMs - splitStart2, config.maxComfortCps)
                            } else {
                                part2
                            }
                            output.add(CueEntry(cur.id, CueSpan(startMs, splitEnd1), wrapBalancedLines(finalPart1)))
                            output.add(CueEntry(CueId(nextSyntheticId++), CueSpan(splitStart2, endMs), wrapBalancedLines(finalPart2)))
                            prevFinalEndMs = endMs
                            continue
                        }
                    }
                }
            }

            if (computeVisibleCps(text, endMs - startMs) > config.maxComfortCps) {
                text = condenseHemmedToComfortBudget(text, endMs - startMs, config.maxComfortCps)
            }

            output.add(CueEntry(cur.id, CueSpan(startMs, endMs), wrapBalancedLines(text)))
            prevFinalEndMs = endMs
        }
        return output
    }
}

class DefaultBolChalTranslationTransport(
    private val httpGetOverride: (suspend (url: String, params: Map<String, String>, headers: Map<String, String>) -> String)? = null,
    private val httpPostJsonOverride: (suspend (url: String, jsonBody: String, headers: Map<String, String>) -> String)? = null,
) : BolChalTranslationTransport {

    private val mapper = ObjectMapper()

    override suspend fun translateWindow(
        window: SceneWindow,
        config: BolChalConfig,
    ): Result<Pair<Map<CueId, BolChalCueOut>, LocalizationTierKey>> {
        if (window.targetCues.isEmpty()) {
            return Result.success(emptyMap<CueId, BolChalCueOut>() to LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
        }

        if (config.hasByokLlm) {
            val llmAttempt = catchNonCancellation { translateViaOpenAiCompatibleLlm(window, config) }
            llmAttempt.exceptionOrNull()?.let { err ->
                if (err is SecurityException) {
                    return Result.failure(err)
                }
            }
            val llmRes = llmAttempt.getOrNull()
            if (llmRes != null && llmRes.size == window.targetCues.size) {
                return Result.success(llmRes to LocalizationTierKey.LLM_SCENE)
            }
            if (llmRes != null && llmRes.isNotEmpty()) {
                val missing = window.targetCues.filter { it.id !in llmRes }
                val patched = LinkedHashMap(llmRes)
                val fallbackMap = catchNonCancellation { translateWithStrictBisect(missing) }.getOrNull()
                if (fallbackMap != null) {
                    patched.putAll(fallbackMap)
                }
                if (patched.size == window.targetCues.size) {
                    return Result.success(patched to LocalizationTierKey.LLM_SCENE)
                }
            }
        }

        return catchNonCancellation {
            val translated = translateKeylessWindowWithDiscourseContext(window)
            if (translated.isEmpty()) {
                throw IllegalStateException("Keyless Bol-Chal NMT returned 0 cues for window ${window.windowIndex}")
            }
            translated to LocalizationTierKey.KEYLESS_BOLCHAL_NMT
        }
    }

    private suspend fun translateKeylessWindowWithDiscourseContext(
        window: SceneWindow,
    ): Map<CueId, BolChalCueOut> {
        if (window.contextPrev.isEmpty() && window.contextNext.isEmpty()) {
            return translateWithStrictBisect(window.targetCues)
        }
        val maxId = window.targetCues.maxOfOrNull { it.id.value }?.coerceAtLeast(0) ?: 0
        val prevSynthetic = window.contextPrev.mapIndexed { idx, cue ->
            cue.copy(id = CueId(maxId + 1_000 + idx))
        }
        val nextSynthetic = window.contextNext.mapIndexed { idx, cue ->
            cue.copy(id = CueId(maxId + 2_000 + idx))
        }
        val combined = ArrayList<LatinSourceCue>(prevSynthetic.size + window.targetCues.size + nextSynthetic.size).apply {
            addAll(prevSynthetic)
            addAll(window.targetCues)
            addAll(nextSynthetic)
        }
        val joined = combined.joinToString("\n") { cue ->
            "⟦${cue.id.value}⟧ ${cue.text.text.replace('\n', ' ')}"
        }
        val translatedBlock = catchNonCancellation { requestGoogleKeylessNmt(joined) }.getOrNull().orEmpty()
        val parsed = parseBracketedIdOutput(translatedBlock, combined)
        if (parsed.size == combined.size) {
            val targetIds = window.targetCues.mapTo(HashSet(window.targetCues.size * 2)) { it.id }
            val filtered = LinkedHashMap<CueId, BolChalCueOut>(window.targetCues.size)
            for (cue in window.targetCues) {
                val out = parsed[cue.id] ?: continue
                if (cue.id in targetIds) {
                    filtered[cue.id] = out
                }
            }
            if (filtered.size == window.targetCues.size) {
                return filtered
            }
        }
        return translateWithStrictBisect(window.targetCues)
    }

    private suspend fun translateViaOpenAiCompatibleLlm(
        window: SceneWindow,
        config: BolChalConfig,
    ): Map<CueId, BolChalCueOut> {
        val rawEndpoint = config.llmEndpointUrl!!.trim().trimEnd('/')
        val fullUrl = when {
            rawEndpoint.endsWith("/chat/completions", ignoreCase = true) -> rawEndpoint
            rawEndpoint.endsWith("/v1", ignoreCase = true) -> "$rawEndpoint/chat/completions"
            else -> "$rawEndpoint/chat/completions"
        }
        val parsedUri = URI(fullUrl)
        val scheme = parsedUri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = parsedUri.host?.lowercase(Locale.ROOT)?.trim('[', ']').orEmpty()
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && isLoopback)) {
            throw SecurityException("Refusing to send BYOK LLM Bearer token over non-HTTPS endpoint: $fullUrl")
        }

        val root = mapper.createObjectNode()
        root.put("model", config.llmModelId!!.trim())
        root.put("temperature", 0.2)
        val messages = root.putArray("messages")

        val card = window.speakerCard
        val registerGuidance = when (card.dominantSceneRegister) {
            AddressRegister.AAP -> "AAP (formal/respectful: آپ, آپ کا/کی/کے, آپ کو, رہے ہیں)"
            AddressRegister.TU -> "TU (intimate/angry: تو, تیرا/تیری/تیرے, تجھے, رہا ہے/رہی ہے)"
            AddressRegister.TUM -> "TUM (peer conversational: تم, تمہارا/تمہاری/تمہارے, تمہیں, رہے ہو)"
        }
        val genderGuidance = when (card.speakerGenderHint) {
            GenderHint.FEMININE -> "Active first-person speaker gender: FEMININE (use رہی ہوں, کرتی ہوں, گئی for 'I'). "
            GenderHint.MASCULINE -> "Active first-person speaker gender: MASCULINE (use رہا ہوں, کرتا ہوں, گیا for 'I'). "
            GenderHint.UNKNOWN -> ""
        }
        val sysPrompt = buildString {
            append("You localize movie/TV/anime dialogue into natural conversational Pakistani/Hindustani spoken Urdu (bol-chal) in Urdu Nastaliq script. ")
            append("Return ONLY a valid JSON array of objects [{\"id\": <int>, \"ur\": \"<urdu>\"}] matching EVERY target cue id 1-to-1. ")
            append("NEVER merge adjacent cues or move plot reveals across '...' boundaries. ")
            append("Dominant scene second-person address register: $registerGuidance. ")
            append(genderGuidance)
            if (!card.genreToneHint.isNullOrBlank()) {
                append("Genre/tone: ${card.genreToneHint}. ")
            }
            append("Avoid Hindi/Sanskritized words (use گھر والے, بھروسہ, وقت, پلیز, شکریہ, خدا) and keep natural Urdu-script English loanwords (فون، کال، میسج، اوکے، سوری، پلیز، ڈاکٹر، پولیس، آفس).")
        }
        messages.addObject().apply {
            put("role", "system")
            put("content", sysPrompt)
        }

        val userPayload = mapper.createObjectNode()
        userPayload.putObject("speaker_card").apply {
            put("dominant_register", card.dominantSceneRegister.name)
            put("speaker_gender", card.speakerGenderHint.name)
            if (!card.genreToneHint.isNullOrBlank()) {
                put("genre_tone", card.genreToneHint)
            }
            if (card.characters.isNotEmpty()) {
                val charsObj = putObject("characters")
                for ((name, prof) in card.characters) {
                    charsObj.putObject(name).apply {
                        put("urdu_name", prof.urduTransliteration)
                        put("gender", prof.gender.name)
                        put("peer_register", prof.defaultRegisterToPeers.name)
                    }
                }
            }
        }
        if (window.contextPrev.isNotEmpty()) {
            val prevArr = userPayload.putArray("context_prev_readonly")
            window.contextPrev.forEach { prevArr.add(it.text.text) }
        }
        val targetsArr = userPayload.putArray("targets")
        for (cue in window.targetCues) {
            targetsArr.addObject().apply {
                put("id", cue.id.value)
                put("max_chars", cue.maxVisibleChars)
                put("en", cue.text.text)
            }
        }
        if (window.contextNext.isNotEmpty()) {
            val nextArr = userPayload.putArray("context_next_readonly")
            window.contextNext.forEach { nextArr.add(it.text.text) }
        }

        messages.addObject().apply {
            put("role", "user")
            put("content", mapper.writeValueAsString(userPayload))
        }

        val headers = mapOf(
            "Authorization" to "Bearer ${config.llmApiKey!!.trim()}",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        val rawResp = executePostJson(fullUrl, mapper.writeValueAsString(root), headers)
        val respTree = mapper.readTree(rawResp)
        val contentRaw = respTree.path("choices").path(0).path("message").path("content").asText("")
        val cleanJson = contentRaw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val arrNode = mapper.readTree(cleanJson)
        if (!arrNode.isArray) return emptyMap()

        val validTargetIds = window.targetCues.associateBy { it.id }
        val out = LinkedHashMap<CueId, BolChalCueOut>(window.targetCues.size)
        for (item in arrNode) {
            val idVal = item.path("id").asInt(Int.MIN_VALUE)
            val urVal = item.path("ur").asText("").trim()
            val cueId = CueId(idVal)
            if (cueId in validTargetIds && urVal.isNotEmpty()) {
                out[cueId] = BolChalCueOut(cueId, urVal, LocalizationTierKey.LLM_SCENE)
            }
        }
        return out
    }

    private suspend fun translateWithStrictBisect(cues: List<LatinSourceCue>): Map<CueId, BolChalCueOut> {
        if (cues.isEmpty()) return emptyMap()
        if (cues.size == 1) {
            val single = cues[0]
            val raw = catchNonCancellation {
                requestGoogleKeylessNmt("⟦${single.id.value}⟧ ${single.text.text.replace('\n', ' ')}")
            }.getOrNull().orEmpty()
            val stripped = MARKER_EXTRACT_REGEX.replace(raw, "").trim()
            return if (stripped.isNotEmpty()) {
                mapOf(single.id to BolChalCueOut(single.id, stripped, LocalizationTierKey.KEYLESS_BOLCHAL_NMT))
            } else {
                emptyMap()
            }
        }

        val joined = cues.joinToString("\n") { cue ->
            "⟦${cue.id.value}⟧ ${cue.text.text.replace('\n', ' ')}"
        }
        val translatedBlock = catchNonCancellation { requestGoogleKeylessNmt(joined) }.getOrNull().orEmpty()
        val parsed = parseBracketedIdOutput(translatedBlock, cues)
        if (parsed.size == cues.size) {
            return parsed
        }

        val mid = cues.size / 2
        val out = LinkedHashMap<CueId, BolChalCueOut>(cues.size)
        out.putAll(parsed)
        val missingLeft = cues.subList(0, mid).filter { it.id !in out }
        val missingRight = cues.subList(mid, cues.size).filter { it.id !in out }
        if (missingLeft.isNotEmpty()) out.putAll(translateWithStrictBisect(missingLeft))
        if (missingRight.isNotEmpty()) out.putAll(translateWithStrictBisect(missingRight))
        return out
    }

    private fun parseBracketedIdOutput(
        translatedText: String,
        expectedCues: List<LatinSourceCue>,
    ): Map<CueId, BolChalCueOut> {
        if (translatedText.isBlank()) return emptyMap()
        val matches = MARKER_EXTRACT_REGEX.findAll(translatedText).toList()
        if (matches.size != expectedCues.size) return emptyMap()

        val leadingPrefix = translatedText.substring(0, matches[0].range.first)
        if (leadingPrefix.count { Character.isLetter(it) } >= 2) {
            return emptyMap()
        }

        val out = LinkedHashMap<CueId, BolChalCueOut>(expectedCues.size)
        for (idx in matches.indices) {
            val m = matches[idx]
            val idDigits = normalizeDigitsToAscii(m.groupValues[1])
            val idInt = idDigits.toIntOrNull() ?: return emptyMap()
            val cueId = CueId(idInt)
            if (cueId != expectedCues[idx].id || cueId in out) return emptyMap()

            if (idx > 0 && m.range.first > 0) {
                val prevMarkerEnd = matches[idx - 1].range.last + 1
                val lineStart = translatedText.lastIndexOf('\n', m.range.first - 1)
                    .let { if (it < prevMarkerEnd) prevMarkerEnd else it + 1 }
                if (lineStart > prevMarkerEnd) {
                    val sameLineBeforeMarker = translatedText.substring(lineStart, m.range.first)
                    if (sameLineBeforeMarker.count { Character.isLetter(it) } >= 2) {
                        return emptyMap()
                    }
                }
            }

            val bodyStart = m.range.last + 1
            val bodyEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else translatedText.length
            val segText = translatedText.substring(bodyStart, bodyEnd).trim()
            if (segText.isEmpty()) return emptyMap()
            val sourceHasLetters = expectedCues[idx].text.text.any { Character.isLetter(it) }
            if (sourceHasLetters && !segText.any { Character.isLetter(it) }) {
                return emptyMap()
            }
            out[cueId] = BolChalCueOut(cueId, segText, LocalizationTierKey.KEYLESS_BOLCHAL_NMT)
        }
        return if (out.size == expectedCues.size) out else emptyMap()
    }

    private suspend fun requestGoogleKeylessNmt(sourceText: String): String {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json",
        )
        val gtxParams = mapOf(
            "client" to "gtx",
            "sl" to "en",
            "tl" to "ur",
            "dt" to "t",
            "q" to sourceText,
        )
        val primaryExtracted = catchNonCancellation {
            val raw = executeGet("https://translate.googleapis.com/translate_a/single", gtxParams, headers)
            if (raw.isBlank()) "" else extractGoogleSingleSentences(mapper.readTree(raw))
        }.getOrNull()
        if (!primaryExtracted.isNullOrBlank()) return primaryExtracted

        val fallbackParams = mapOf(
            "client" to "dict-chrome-ex",
            "sl" to "en",
            "tl" to "ur",
            "q" to sourceText,
        )
        val secondaryJson = executeGet("https://clients5.google.com/translate_a/t", fallbackParams, headers)
        val node = mapper.readTree(secondaryJson)
        return when {
            node.isArray && node.size() > 0 && node[0].isTextual -> node[0].asText("")
            else -> extractGoogleSingleSentences(node)
        }
    }

    private fun extractGoogleSingleSentences(root: JsonNode?): String {
        if (root == null || !root.isArray || root.size() == 0) return ""
        val first = root[0]
        if (!first.isArray) return ""
        val sb = StringBuilder()
        for (seg in first) {
            if (seg.isArray && seg.size() > 0 && !seg[0].isNull) {
                sb.append(seg[0].asText(""))
            }
        }
        return sb.toString().trim()
    }

    private suspend fun executeGet(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        httpGetOverride?.let { return it(url, params, headers) }
        val niceResult = catchNonCancellation {
            app.get(url, headers = headers, params = params, timeout = 6L).text
        }.getOrNull()
        if (!niceResult.isNullOrBlank()) return niceResult

        return executeFallbackGet(url, params, headers)
    }

    internal suspend fun executeFallbackGet(
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
    ): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val full = if (query.isEmpty()) url else "$url?$query"
        return runCancellableHttpIo(
            openConnection = {
                (URL(full).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 6_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            },
            ioBlock = { conn, isCancelled ->
                conn.connect()
                if (isCancelled()) {
                    conn.disconnect()
                    throw CancellationException("Fallback GET cancelled")
                }
                conn.inputStream.use { readBoundedUtf8(it, MAX_TRANSLATION_RESPONSE_BYTES, isCancelled) }
            },
        )
    }

    private suspend fun executePostJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String {
        httpPostJsonOverride?.let { return it(url, jsonBody, headers) }
        val niceResult = catchNonCancellation {
            val bodyMap = mapper.readValue(
                jsonBody,
                object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any?>>() {},
            )
            app.post(url, headers = headers, json = bodyMap, timeout = 12L).text
        }.getOrNull()
        if (!niceResult.isNullOrBlank()) return niceResult

        return executeFallbackPostJson(url, jsonBody, headers)
    }

    internal suspend fun executeFallbackPostJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): String {
        val payload = jsonBody.toByteArray(StandardCharsets.UTF_8)
        return runCancellableHttpIo(
            openConnection = {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            },
            ioBlock = { conn, isCancelled ->
                conn.connect()
                if (isCancelled()) {
                    conn.disconnect()
                    throw CancellationException("Fallback POST cancelled")
                }
                conn.outputStream.use { it.write(payload) }
                if (isCancelled()) {
                    conn.disconnect()
                    throw CancellationException("Fallback POST cancelled")
                }
                conn.inputStream.use { readBoundedUtf8(it, MAX_TRANSLATION_RESPONSE_BYTES, isCancelled) }
            },
        )
    }

    private suspend fun <T> runCancellableHttpIo(
        openConnection: () -> HttpURLConnection,
        ioBlock: (HttpURLConnection, () -> Boolean) -> T,
    ): T {
        val cancelled = AtomicBoolean(false)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val ioThreadRef = AtomicReference<Thread?>(null)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                cancelled.set(true)
                ioThreadRef.get()?.interrupt()
                val activeConn = connRef.get()
                if (activeConn != null) {
                    HTTP_FALLBACK_EXECUTOR.execute {
                        runCatching { activeConn.disconnect() }
                    }
                }
            }
            if (!cont.isActive) return@suspendCancellableCoroutine
            HTTP_FALLBACK_EXECUTOR.execute {
                if (cancelled.get() || !cont.isActive) return@execute
                ioThreadRef.set(Thread.currentThread())
                var conn: HttpURLConnection? = null
                try {
                    conn = openConnection()
                    connRef.set(conn)
                    if (cancelled.get() || !cont.isActive) {
                        runCatching { conn.disconnect() }
                        return@execute
                    }
                    val result = ioBlock(conn) { cancelled.get() || !cont.isActive }
                    if (!cancelled.get() && cont.isActive) {
                        cont.resume(result)
                    }
                } catch (t: Throwable) {
                    if (!cancelled.get() && cont.isActive) {
                        cont.resumeWithException(t)
                    }
                } finally {
                    ioThreadRef.set(null)
                    Thread.interrupted()
                    runCatching { conn?.disconnect() }
                }
            }
        }
    }

    private fun readBoundedUtf8(
        stream: InputStream,
        maxBytes: Int,
        isCancelled: () -> Boolean = { false },
    ): String {
        val out = ByteArrayOutputStream(min(maxBytes, 8_192))
        val buf = ByteArray(4_096)
        var total = 0
        while (true) {
            if (isCancelled()) throw CancellationException("Fallback response read cancelled")
            val read = stream.read(buf)
            if (read <= 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalStateException("Translation response exceeded $maxBytes bytes")
            }
            out.write(buf, 0, read)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private inline fun <T> catchNonCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }

    companion object {
        private const val MAX_TRANSLATION_RESPONSE_BYTES = 2 * 1024 * 1024

        private val HTTP_FALLBACK_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "bolchal-http-fallback").apply { isDaemon = true }
        }

        // Matches ⟦12⟧ even if NMT inserts whitespace or localizes digits into Urdu/Arabic-Indic numerals.
        private val MARKER_EXTRACT_REGEX = Regex("""[⟦\u27E6]\s*([0-9\u06F0-\u06F9\u0660-\u0669]+)\s*[⟧\u27E7]""")

        private fun normalizeDigitsToAscii(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                when (ch) {
                    in '0'..'9' -> sb.append(ch)
                    in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - 0x06F0)).toChar())
                    in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - 0x0660)).toChar())
                }
            }
            return sb.toString()
        }
    }
}

object SceneBolChalLocalizer {
    private val REVEAL_ELLIPSIS_REGEX = Regex("""(?:\.\.\.|…)\s*$""")
    private val FORMAL_HONORIFIC_REGEX = Regex(
        """(?i)\b(?:sir|ma'am|madam|your\s+honor|your\s+majesty|officer|detective|professor|doctor|dr\.|mr\.|mrs\.|president|general|father|mother)\b"""
    )
    private val INTIMATE_ANGER_REGEX = Regex(
        """(?i)\b(?:you\s+bastard|you\s+idiot|you\s+coward|you\s+traitor|shut\s+up|get\s+lost|go\s+to\s+hell|how\s+dare\s+you|damn\s+you)\b"""
    )
    private val PROPER_NAME_VOCATIVE_REGEX = Regex("""(?:^|[.!?]\s+|,\s*)([A-Z][a-z]{2,14})(?=\s*[,!?])""")

    fun normalizeDonorCues(englishDoc: CueDocument): List<LatinSourceCue> {
        val out = ArrayList<LatinSourceCue>(englishDoc.cues.size)
        for (cue in englishDoc.cues) {
            val latin = LatinEnglishText.normalizeFromRawCue(cue.text).getOrNull() ?: continue
            val durSec = (cue.span.durationMs.coerceAtLeast(833L).toDouble() / 1_000.0)
            val maxChars = (durSec * 17.0).toInt().coerceIn(14, 84)
            out.add(
                LatinSourceCue(
                    id = cue.id,
                    span = cue.span,
                    maxVisibleChars = maxChars,
                    text = latin,
                    endsWithRevealEllipsis = REVEAL_ELLIPSIS_REGEX.containsMatchIn(latin.text),
                )
            )
        }
        return out
    }

    fun buildSceneWindows(
        sourceCues: List<LatinSourceCue>,
        useLlmWindowSizes: Boolean,
        sceneGapSplitMs: Long = 2_500L,
        initialCard: SpeakerContextCard = SpeakerContextCard(),
    ): List<SceneWindow> {
        if (sourceCues.isEmpty()) return emptyList()
        val minSize = if (useLlmWindowSizes) 25 else 15
        val targetSize = if (useLlmWindowSizes) 30 else 18
        val maxSize = if (useLlmWindowSizes) 35 else 20

        val windows = ArrayList<SceneWindow>((sourceCues.size / targetSize) + 2)
        var cursor = 0
        var winIdx = 0
        var runningCard = initialCard

        while (cursor < sourceCues.size) {
            val remaining = sourceCues.size - cursor
            val endExclusive = if (remaining <= maxSize) {
                sourceCues.size
            } else {
                var splitAt = -1
                val minEnd = cursor + minSize
                val maxEnd = min(sourceCues.size, cursor + maxSize)
                for (i in minEnd until maxEnd) {
                    val gapMs = sourceCues[i].span.startMs - sourceCues[i - 1].span.endMs
                    if (gapMs >= sceneGapSplitMs) {
                        splitAt = i
                        break
                    }
                }
                if (splitAt > cursor) splitAt else min(sourceCues.size, cursor + targetSize)
            }

            val prevStart = (cursor - 3).coerceAtLeast(0)
            val contextPrev = sourceCues.subList(prevStart, cursor)
            val targets = sourceCues.subList(cursor, endExclusive)
            val nextEnd = min(sourceCues.size, endExclusive + 2)
            val contextNext = sourceCues.subList(endExclusive, nextEnd)

            runningCard = evolveSpeakerCard(runningCard, targets)
            windows.add(
                SceneWindow(
                    windowIndex = winIdx++,
                    contextPrev = contextPrev,
                    targetCues = targets,
                    contextNext = contextNext,
                    speakerCard = runningCard,
                )
            )
            cursor = endExclusive
        }
        return windows
    }

    private fun evolveSpeakerCard(
        previous: SpeakerContextCard,
        cues: List<LatinSourceCue>,
    ): SpeakerContextCard {
        var formalHits = 0
        var angerHits = 0
        val updatedChars = LinkedHashMap(previous.characters)

        for (cue in cues) {
            val t = cue.text.text
            if (FORMAL_HONORIFIC_REGEX.containsMatchIn(t)) formalHits++
            if (INTIMATE_ANGER_REGEX.containsMatchIn(t)) angerHits++

            for (m in PROPER_NAME_VOCATIVE_REGEX.findAll(t)) {
                val name = m.groupValues[1]
                if (name.lowercase(Locale.ROOT) !in COMMON_NON_NAMES && name !in updatedChars) {
                    updatedChars[name] = CharacterRegisterProfile(
                        canonicalName = name,
                        urduTransliteration = name,
                        gender = GenderHint.UNKNOWN,
                        defaultRegisterToPeers = AddressRegister.TUM,
                    )
                }
            }
        }

        val dominantRegister = when {
            formalHits >= 2 -> AddressRegister.AAP
            angerHits >= 2 && formalHits == 0 -> AddressRegister.TU
            else -> AddressRegister.TUM
        }

        return previous.copy(
            characters = updatedChars,
            dominantSceneRegister = dominantRegister,
        )
    }

    private val COMMON_NON_NAMES = setOf(
        "what", "wait", "well", "yeah", "yes", "hey", "look", "listen",
        "stop", "come", "impossible", "please", "sorry", "okay", "right",
    )

    private fun isAcceptablePurifiedUrduCue(purified: String, sourceCue: LatinSourceCue): Boolean {
        if (purified.isBlank()) return false
        val srcHasLetters = sourceCue.text.text.any { Character.isLetter(it) }
        if (!srcHasLetters) return true
        return purified.any { ch ->
            val code = ch.code
            code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF
        }
    }

    suspend fun localize(
        englishDonorDoc: CueDocument,
        donorProvenance: DonorProvenance,
        alignmentProof: AlignmentProof,
        config: BolChalConfig = BolChalConfig(),
        transport: BolChalTranslationTransport = DefaultBolChalTranslationTransport(),
        videoDurationMs: Long? = null,
        initialSpeakerCard: SpeakerContextCard = SpeakerContextCard(),
        onWindowZeroReady: (suspend (CueDocument) -> Unit)? = null,
    ): Result<VerifiedSubtitleCandidate.BolChalLocalized> {
        val sourceCues = normalizeDonorCues(englishDonorDoc)
        if (sourceCues.isEmpty()) {
            return Result.failure(IllegalArgumentException("English donor contained 0 translatable dialogue cues"))
        }

        val windows = buildSceneWindows(
            sourceCues = sourceCues,
            useLlmWindowSizes = config.hasByokLlm,
            sceneGapSplitMs = config.sceneGapSplitMs,
            initialCard = initialSpeakerCard,
        )

        val translatedById = LinkedHashMap<CueId, String>(sourceCues.size)
        val regexOnlyRegisterCueIds = HashSet<CueId>()
        var effectiveTier = LocalizationTierKey.KEYLESS_BOLCHAL_NMT
        var mergedAnyWindow = false
        var firstWindowError: Throwable? = null
        val sourceById = sourceCues.associateBy { it.id }

        fun mergeWindowResult(window: SceneWindow, windowMap: Map<CueId, BolChalCueOut>, tier: LocalizationTierKey) {
            if (!mergedAnyWindow || tier.tierRank > effectiveTier.tierRank) {
                effectiveTier = tier
            }
            mergedAnyWindow = true
            for ((cueId, outCue) in windowMap) {
                val srcCue = sourceById[cueId] ?: continue
                val cueTier = outCue.tierOrigin ?: tier
                val allowRegexGender = cueTier != LocalizationTierKey.LLM_SCENE
                val detailed = BolChalPurifier.purifyDetailed(
                    rawUrdu = outCue.urduText,
                    sourceEnglish = srcCue.text,
                    speakerCard = window.speakerCard,
                    allowRegexGenderRewrite = allowRegexGender,
                )
                if (isAcceptablePurifiedUrduCue(detailed.text, srcCue)) {
                    translatedById[cueId] = detailed.text
                    if (allowRegexGender && detailed.registerRewrittenByRegex) {
                        regexOnlyRegisterCueIds.add(cueId)
                    } else {
                        regexOnlyRegisterCueIds.remove(cueId)
                    }
                }
            }
        }

        fun recordWindowFailure(err: Throwable): Throwable? {
            if (err is CancellationException) throw err
            if (err is SecurityException) return err
            if (firstWindowError == null) firstWindowError = err
            return null
        }

        val concurrency = config.maxConcurrentWindows.coerceIn(1, 16)
        if (onWindowZeroReady != null && windows.isNotEmpty()) {
            val win0 = windows[0]
            transport.translateWindow(win0, config).fold(
                onSuccess = { (win0Map, win0Tier) ->
                    mergeWindowResult(win0, win0Map, win0Tier)
                    if (translatedById.isNotEmpty()) {
                        val win0Entries = win0.targetCues.mapNotNull { sc ->
                            translatedById[sc.id]?.let { ur -> CueEntry(sc.id, sc.span, ur) }
                        }
                        val refinedWin0 = UrduCpsRefiner.refineDocument(win0Entries, config, videoDurationMs)
                        if (refinedWin0.isNotEmpty()) {
                            onWindowZeroReady(CueDocument.fromCues(SubtitleLanguage.URDU, refinedWin0))
                        }
                    }
                },
                onFailure = { err ->
                    recordWindowFailure(err)?.let { return Result.failure(it) }
                },
            )
            if (windows.size > 1) {
                val tailWindows = windows.subList(1, windows.size)
                val sem = Semaphore(concurrency)
                val tailResults = coroutineScope {
                    tailWindows.map { win ->
                        async {
                            sem.withPermit {
                                win to transport.translateWindow(win, config)
                            }
                        }
                    }.awaitAll()
                }
                for ((win, res) in tailResults) {
                    res.fold(
                        onSuccess = { (winMap, tier) -> mergeWindowResult(win, winMap, tier) },
                        onFailure = { err -> recordWindowFailure(err)?.let { return Result.failure(it) } },
                    )
                }
            }
        } else if (windows.size == 1 || concurrency == 1) {
            for (window in windows) {
                transport.translateWindow(window, config).fold(
                    onSuccess = { (windowMap, tier) -> mergeWindowResult(window, windowMap, tier) },
                    onFailure = { err -> recordWindowFailure(err)?.let { return Result.failure(it) } },
                )
            }
        } else {
            val sem = Semaphore(concurrency)
            val allResults = coroutineScope {
                windows.map { win ->
                    async {
                        sem.withPermit {
                            win to transport.translateWindow(win, config)
                        }
                    }
                }.awaitAll()
            }
            for ((win, res) in allResults) {
                res.fold(
                    onSuccess = { (winMap, tier) -> mergeWindowResult(win, winMap, tier) },
                    onFailure = { err -> recordWindowFailure(err)?.let { return Result.failure(it) } },
                )
            }
        }

        val fatalErr = firstWindowError
        if (translatedById.isEmpty() && fatalErr != null) {
            return Result.failure(fatalErr)
        }

        val survivalRatio = translatedById.size.toDouble() / sourceCues.size.toDouble()
        if (survivalRatio < config.minRequiredCueCoverage) {
            return Result.failure(
                IllegalStateException(
                    "Cue survival ratio ${"%.3f".format(Locale.ROOT, survivalRatio)} below required ${config.minRequiredCueCoverage}",
                    firstWindowError,
                )
            )
        }

        val unrefinedUrduCues = sourceCues.mapNotNull { sc ->
            translatedById[sc.id]?.let { ur -> CueEntry(sc.id, sc.span, ur) }
        }
        val refinedCues = UrduCpsRefiner.refineDocument(
            cues = unrefinedUrduCues,
            config = config,
            videoDurationMs = videoDurationMs,
        )
        val finalDoc = CueDocument.fromCues(SubtitleLanguage.URDU, refinedCues)
        if (finalDoc.cues.isEmpty()) {
            return Result.failure(IllegalStateException("Localized Urdu document empty after CPS refinement"))
        }

        val cpsValues = finalDoc.cues.map {
            UrduCpsRefiner.computeVisibleCps(it.text, it.span.durationMs)
        }.sorted()
        val maxNormalCps = cpsValues.lastOrNull() ?: 0.0
        val p95Idx = ((cpsValues.size - 1) * 0.95).toInt().coerceIn(0, cpsValues.size - 1)
        val p95Cps = cpsValues[p95Idx]

        val mtAudit = UrduMtDumpDetector.audit(
            urduDoc = finalDoc,
            referenceEnglishDoc = englishDonorDoc,
        )
        if (mtAudit.mtDumpScore >= UrduQualityGate.MT_HARD_REJECT_THRESHOLD) {
            return Result.failure(
                CandidateGateRejectedException(
                    VerifiedSubtitleCandidate.Rejected.MtDump(audit = mtAudit)
                )
            )
        }
        val regexPatchedCount = regexOnlyRegisterCueIds.count { it in translatedById }
        val registerVerifiedRatio = if (translatedById.isEmpty()) {
            0.0
        } else {
            ((translatedById.size - regexPatchedCount).coerceAtLeast(0).toDouble() / translatedById.size.toDouble())
                .coerceIn(0.0, 1.0)
        }
        val regexRegisterPenalty = ((1.0 - registerVerifiedRatio) * 0.15).coerceIn(0.0, 0.15)
        val cpsPenalty = if (p95Cps <= config.maxComfortCps) 0.0 else ((p95Cps - config.maxComfortCps) * 0.03).coerceAtMost(0.25)
        val qeScore = (survivalRatio * (1.0 - (mtAudit.mtDumpScore / 200.0)) - cpsPenalty - regexRegisterPenalty).coerceIn(0.0, 1.0)

        val report = BolChalQualityReport(
            qeScore = qeScore,
            cueSurvivalRatio = survivalRatio,
            maxNormalDialogueCps = maxNormalCps,
            p95VisibleCps = p95Cps,
            mtResidualScore = mtAudit.mtDumpScore,
            regexPatchedRegisterCues = regexPatchedCount,
            registerVerifiedCueRatio = registerVerifiedRatio,
        )

        return Result.success(
            VerifiedSubtitleCandidate.BolChalLocalized(
                donor = donorProvenance,
                tier = effectiveTier,
                qualityReport = report,
                document = finalDoc,
                alignment = alignmentProof,
            )
        )
    }
}
