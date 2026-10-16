package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.VIDROCK_KEY_HEX
import com.phisher98.StreamPlayExtractor.vidrockParseLanes
import com.phisher98.StreamPlayExtractor.vidrockQualityOf
import com.phisher98.StreamPlayExtractor.vidrockUnwrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol acceptance for the VidRock (vidrock.net) SOTA integration.
 *
 * The VidRock API hands out one AES-256-GCM share token per playback lane; the app can never
 * rely on a foreign decrypt service (that is what turned the vidsrc.sh family into a
 * rejected workaround), so the unwrap and the lane parser must be offline-exact. These tests
 * pin down, with known-answer vectors produced by an independent implementation (Node
 * crypto, verified against the live site 2026-10-15):
 *
 *  1. token un-marshalling: base64url -> nonce[12] || ciphertext || tag[16] -> plaintext,
 *  2. integrity: bit flips / truncation / non-token inputs are rejected instead of surfacing
 *     a malformed URL to the player,
 *  3. lane parsing: direct manifests and JSON `{name,url}` ladder entries both resolve to
 *     concrete, quality-tagged URLs,
 *  4. the strict ranking contract the rest of the engine relies on.
 */
class StreamPlayVidRockProtocolTest {

    // ==================== 1. Un-marshalling ====================

    /**
     * KAT vectors are stored as hex of the raw token bytes (nonce[12] || ct || tag[16]) for
     * readability; the wire format the API hands out is their base64url encoding.
     */
    private fun tokenFromHex(hex: String): String =
        ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[2 * i], 16)
            val lo = Character.digit(hex[2 * i + 1], 16)
            ((hi shl 4) + lo).toByte()
        }.toB64Url()

    @Test
    fun testKnownAnswerVectorDirectManifestLane() {
        val plaintext = vidrockUnwrap(
            tokenFromHex(
                "b47427e5ee92c8a1adb71dc2830f4a0d738cd55a85bfc8a3b95408af356044501ece7bbfee5166d3f2afa8959a95e10e65fd6b4b5a8971a2bdef7dd5372b71734065719494ffade70704cfc4d28c91b1d1e5f202227f1ce9a2f403b51b2bd4c2cb4f02a6904367528e0380432836510a940c899655d717c130a432c600da7b7bebb7d7ce6fede69b1dad8c"
            )
        )
        assertEquals(
            "https://galacticvoyagers.xyz/file3/YTk2ZjNkZGY4YzUwZmUwZDc5NGExMjE0YTNkOWVmNTExNzkyMDgxNDg5NDEzNTM3/master.m3u8",
            plaintext
        )
    }

    @Test
    fun testKnownAnswerVectorTvLane() {
        assertEquals(
            "https://moonfirekeeper.lol/file2/NTdlZGRjZjZmNGRkNjZhOTkyOTgwNDg1ZTVmNzVhNzIxNzg5NjQyMjQ1OTUyNDM5/master.m3u8",
            vidrockUnwrap(
                tokenFromHex(
                    "a01bee0d7fbe316973e25fd2ef627fbb25a4ee0c897eacd3b1a4c82e21943b4fc757d619643a5ad90ad46fbd0647ef8c85855a10b6ccc9f283c2328418a30de601afeff3ac384994fc21eb9ab98e556be3d3059902b76e9118f53b2e1427caaa0904cc6327180e42f2881db40f4a1b7784ec8b18f97c0ce7259ab66a6b77bab545b5af0eb08a523156"
                )
            )
        )
    }

    @Test
    fun testKnownAnswerVectorProgressiveMp4Lane() {
        assertEquals(
            "https://galacticvoyagers.xyz/mp4/AbCdEf/sample.mp4",
            vidrockUnwrap(
                tokenFromHex(
                    "79a5e67f9651c54e7ef1c1cf5893901b656960c5be477c37ca59b97fc542c3b0ace163be32604e74d8483de987a71c89dda8b6daa13988c78402d10e55f2a2894dac727571220aaa434497f97dc4"
                )
            )
        )
    }

    // ==================== 2. Integrity ====================

    @Test
    fun testTamperedCiphertextIsRejected() {
        val vector = "b47427e5ee92c8a1adb71dc2830f4a0d738cd55a85bfc8a3b95408af356044501ece7bbfee5166d3f2afa8959a95e10e65fd6b4b5a8971a2bdef7dd5372b71734065719494ffade70704cfc4d28c91b1d1e5f202227f1ce9a2f403b51b2bd4c2cb4f02a6904367528e0380432836510a940c899655d717c130a432c600da7b7bebb7d7ce6fede69b1dad8c"
        val bytes = tokenFromHex(vector)
            .let { com.phisher98.b64UrlDecode(it) }
        val bodyLen = bytes.size - 16
        assertTrue("Baseline vector must unwrap", vidrockUnwrap(bytes.toB64Url())?.startsWith("https://") == true)

        // Flip one bit in the middle of the ciphertext body: AEAD must reject it.
        val corruptBody = bytes.clone()
        corruptBody[bodyLen / 2] = (corruptBody[bodyLen / 2].toInt() xor 0x1).toByte()
        assertNull("Single flipped ciphertext bit must be rejected", vidrockUnwrap(corruptBody.toB64Url()))

        // Zero out the authentication tag.
        val corruptTag = bytes.clone()
        for (i in bodyLen until bytes.size) corruptTag[i] = 0x00
        assertNull("Zeroed auth tag must be rejected", vidrockUnwrap(corruptTag.toB64Url()))

        // Zero out the nonce (a re-nonce'd replay of the same ciphertext).
        val corruptNonce = bytes.clone()
        for (i in 0 until 12) corruptNonce[i] = 0x00
        assertNull("Zeroed nonce must be rejected", vidrockUnwrap(corruptNonce.toB64Url()))
    }

    @Test
    fun testMalformedInputsNeverThrow() {
        assertNull("Blank", vidrockUnwrap(""))
        assertNull("Whitespace-only", vidrockUnwrap("   "))
        assertNull("Not base64", vidrockUnwrap("###"))
        assertNull("Too short", vidrockUnwrap("YWJjZA")) // 4 bytes: below the 28-byte floor
    }

    // ==================== 3. Lane parsing ====================

    @Test
    fun testJsonLadderLaneExpandsToVariantUrls() {
        val ladderJson = """[{"name":"1080p","url":"https://galacticvoyagers.xyz/l/1080.m3u8","language":"English"},
{"name":"720p","url":"https://galacticvoyagers.xyz/l/720.m3u8","language":"English"},
{"name":"480p","url":"https://moonfirekeeper.lol/l/480.m3u8","language":"English"}]"""
        val token = sealToken(ladderJson)

        val lanes = vidrockParseLanes("""{"Sora":{"url":"$token","type":"hls","language":"English"}}""")
        assertEquals("Exactly one usable lane", 1, lanes.size)
        val lane = lanes.first()
        assertEquals("Sora", lane.lane)
        assertEquals("hls", lane.kind)
        assertEquals("English", lane.language)
        assertEquals(3, lane.urls.size)
        assertEquals("Ladder order must be preserved", "https://galacticvoyagers.xyz/l/1080.m3u8", lane.urls[0].first)
        assertEquals(Qualities.P1080.value, lane.urls[0].second)
        assertEquals(Qualities.P720.value, lane.urls[1].second)
        assertEquals(Qualities.P480.value, lane.urls[2].second)
    }

    @Test
    fun testDirectManifestLaneKeepsMasterForExpansion() {
        val direct = "https://galacticvoyagers.xyz/file3/abcde/master.m3u8"
        val lines = vidrockParseLanes(
            """{"Orion":{"url":"${sealToken(direct)}","type":"hls","language":"English"}}"""
        )
        assertEquals(1, lines.size)
        assertEquals(listOf(direct to Qualities.Unknown.value), lines.first().urls)
    }

    @Test
    fun testUndecryptableAndEmptyLanesAreDropped() {
        val good = sealToken("https://galacticvoyagers.xyz/good.m3u8")
        val payload = """{
          "Orion": {"url": "$good", "type": "hls", "language": "English"},
          "Luna": {"url": "not-a-valid-token", "type": "hls", "language": "English"},
          "Atlas": {"url": "", "type": "hls", "language": "English"}
        }"""
        val lanes = vidrockParseLanes(payload)
        assertEquals(1, lanes.size)
        assertEquals("Orion", lanes.first().lane)
    }

    @Test
    fun testNonJsonAndGarbageBodiesYieldNoLanes() {
        assertTrue(vidrockParseLanes("<html>502 Bad Gateway</html>").isEmpty())
        assertTrue(vidrockParseLanes("").isEmpty())
        assertTrue(vidrockParseLanes("null").isEmpty())
    }

    @Test
    fun testQualityLabelParsing() {
        assertEquals(Qualities.P2160.value, vidrockQualityOf("2160p"))
        assertEquals(Qualities.P1440.value, vidrockQualityOf("1440p"))
        assertEquals(Qualities.P1080.value, vidrockQualityOf("1080p"))
        assertEquals(Qualities.P720.value, vidrockQualityOf("720p"))
        assertEquals(Qualities.P480.value, vidrockQualityOf("480p"))
        assertEquals(Qualities.P360.value, vidrockQualityOf("360p"))
        assertEquals(Qualities.Unknown.value, vidrockQualityOf("auto"))
        assertEquals(Qualities.Unknown.value, vidrockQualityOf(""))
        assertEquals(Qualities.Unknown.value, vidrockQualityOf(null))
        assertEquals(Qualities.Unknown.value, vidrockQualityOf("master"))
    }

    // ==================== 4. Ranking contract ====================

    @Test
    fun testVidrockLinksRankAt92BelowVidSrc() {
        val vidrockLink = ExtractorLink(
            source = "VidRock",
            name = "VidRock Orion (English) [720p]",
            url = "https://galacticvoyagers.xyz/file3/abc/master.m3u8",
            referer = "https://vidrock.net/",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8,
            headers = mapOf(
                "User-Agent" to "test-agent",
                "Referer" to "https://vidrock.net/",
                "Origin" to "https://vidrock.net"
            )
        )
        val vidsrcLink = ExtractorLink(
            source = "VidSrc",
            name = "VidSrc Direct (quasar) [720p]",
            url = "https://lit.cheaptruckrepairs.cc/h/master.m3u8",
            referer = "https://vidsrc.pm/",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8,
            headers = emptyMap()
        )

        assertEquals(92, StreamLinkOptimizer.getSourcePriorityRank(vidrockLink))
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(vidsrcLink))
        assertTrue(
            "VidSrc (95) must outrank VidRock (92)",
            StreamLinkOptimizer.getStreamCompositeScore(vidsrcLink) > StreamLinkOptimizer.getStreamCompositeScore(vidrockLink)
        )
    }

    @Test
    fun testVidrockRankSurvivesCdnRewrap() {
        // After dedup the label may be lost; the CDN host must still carry the tier.
        val rewrapped = ExtractorLink(
            source = "stream",
            name = "stream",
            url = "https://moonfirekeeper.lol/file2/master.m3u8",
            referer = "https://example.com/",
            quality = Qualities.P1080.value,
            type = ExtractorLinkType.M3U8
        )
        assertEquals(92, StreamLinkOptimizer.getSourcePriorityRank(rewrapped))
        assertTrue("Rewrapped VidRock link must remain top-tier", StreamLinkOptimizer.isTopTierSource(rewrapped))
    }

    // ==================== helpers ====================

    private val sealKey: ByteArray by lazy {
        ByteArray(VIDROCK_KEY_HEX.length / 2) { byteIndex ->
            val hi = Character.digit(VIDROCK_KEY_HEX[2 * byteIndex], 16)
            val lo = Character.digit(VIDROCK_KEY_HEX[2 * byteIndex + 1], 16)
            ((hi shl 4) + lo).toByte()
        }
    }

    /**
     * Produces a VidRock-shaped share token for [plain] with the production key:
     * base64url(nonce[12] || ciphertext || tag[16]). Fixed deterministic nonce so failures
     * are reproducible.
     */
    private fun sealToken(plain: String): String {
        val nonce = ByteArray(12) { 0x0f }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sealKey, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)) // ciphertext + tag
        val out = ByteArray(12 + ct.size)
        nonce.copyInto(out)
        ct.copyInto(out, 12)
        return out.toB64Url()
    }

    private fun ByteArray.toB64Url(): String {
        val b64 = String(java.util.Base64.getEncoder().encode(this))
        return b64.replace('+', '-').replace('/', '_').trimEnd('=')
    }
}
