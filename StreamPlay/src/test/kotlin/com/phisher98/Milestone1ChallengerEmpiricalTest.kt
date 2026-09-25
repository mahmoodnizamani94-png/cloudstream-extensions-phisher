package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sign

/**
 * Empirical Adversarial Challenger Test Suite for Milestone 1.
 *
 * Verifies:
 * 1. Mathematical separation across permutations of bitrates (0 to 100,000 kbps),
 *    video scores (REMUX, BluRay, WEB-DL, DV, HDR10+, HDR, HEVC),
 *    audio scores (Atmos, TrueHD, DTS-HD, 7.1, 5.1), and header scores.
 * 2. 1080p stream with MAXIMAL badges & 100,000 kbps NEVER beats 720p stream with MINIMAL badges & 500 kbps.
 * 3. 4K stream with MAXIMAL badges NEVER beats 480p stream with MINIMAL badges.
 * 4. Anti-symmetry and transitivity of isBetterThan and STREAM_PRIORITY_COMPARATOR.
 */
class Milestone1ChallengerEmpiricalTest {

    private fun createLink(
        source: String = "VidLink",
        name: String = "Stream",
        url: String = "https://cdn.example.com/stream.m3u8",
        quality: Int = Qualities.P720.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "https://example.com/",
        headers: Map<String, String> = emptyMap(),
        extractorData: String? = null
    ): ExtractorLink {
        @Suppress("DEPRECATION")
        return ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = referer,
            quality = quality,
            type = type,
            headers = headers,
            extractorData = extractorData
        )
    }

    private val fullHeaders = mapOf(
        StreamLinkOptimizer.HEADER_USER_AGENT to StreamLinkOptimizer.MODERN_DESKTOP_UA,
        StreamLinkOptimizer.HEADER_ACCEPT to "*/*",
        StreamLinkOptimizer.HEADER_ACCEPT_ENCODING to "identity",
        StreamLinkOptimizer.HEADER_CONNECTION to "keep-alive",
        StreamLinkOptimizer.HEADER_SEC_FETCH_DEST to "video"
    )

    // =========================================================================
    // REQUIREMENT 1: Mathematical Separation Across All Permutations
    // =========================================================================

    @Test
    fun testReq1_MathematicalSeparationAcrossPermutations() {
        val bitrates = listOf(0L, 500L, 2500L, 5000L, 15000L, 50000L, 75000L, 100000L)
        val videoBadges = listOf(
            "",
            "[REMUX]",
            "[BluRay]",
            "[WEB-DL]",
            "[WEBRip]",
            "[DV]",
            "[HDR10+]",
            "[HDR]",
            "[HEVC]",
            "[AV1]",
            "[10-bit]",
            "[REMUX] [DV] [HDR10+] [HEVC] [AV1] [10-bit]"
        )
        val audioBadges = listOf(
            "",
            "[TrueHD]",
            "[DTS-HD]",
            "[Atmos]",
            "[DTS]",
            "[7.1]",
            "[5.1]",
            "[Dual Audio]",
            "[Multi Audio]",
            "[TrueHD] [DTS-HD] [Atmos] [7.1] [Dual Audio] [Multi Audio]"
        )
        val headerSets = listOf(
            emptyMap(),
            mapOf(StreamLinkOptimizer.HEADER_USER_AGENT to StreamLinkOptimizer.MODERN_DESKTOP_UA),
            mapOf(StreamLinkOptimizer.HEADER_ACCEPT_ENCODING to "identity"),
            fullHeaders
        )

        val topProviders = listOf("Vidlink", "Vidcore", "Vidup", "RiveStream", "CineJoy", "VidEasy")

        // 1. Verify tiebreaker is ALWAYS bounded strictly in [0.0f, 9.9f]
        for (b in bitrates) {
            for (v in videoBadges) {
                for (a in audioBadges) {
                    for (h in headerSets) {
                        val name = "Test $v $a" + (if (b > 0) " [${b}kbps]" else "")
                        val link = createLink(name = name, headers = h)
                        val score = StreamLinkOptimizer.getStreamCompositeScore(link)
                        val baseOnly = StreamLinkOptimizer.getStreamCompositeScore(createLink(name = "Test"))
                        val tiebreaker = score - baseOnly
                        assertTrue(
                            "Tiebreaker must be >= 0.0f, got $tiebreaker for $name",
                            tiebreaker >= -0.001f
                        )
                        assertTrue(
                            "Tiebreaker must be <= 9.9f, got $tiebreaker for $name",
                            tiebreaker <= 9.901f
                        )
                    }
                }
            }
        }

        // 2. Permutation separation across adjacent quality tiers for all top providers
        var minSeparation720to1080 = Float.MAX_VALUE
        var minSeparation1080to480 = Float.MAX_VALUE
        var minSeparation480toOtherSd = Float.MAX_VALUE
        var minSeparationOtherSdto4K = Float.MAX_VALUE

        for (pA in topProviders) {
            for (pB in topProviders) {
                // pA with minimal attributes vs pB with maximal attributes
                val link720Min = createLink(source = pA, name = "$pA [720p]", quality = Qualities.P720.value)
                val link1080Max = createLink(
                    source = pB,
                    name = "$pB [1080p] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [TrueHD] [7.1]",
                    quality = Qualities.P1080.value,
                    headers = fullHeaders
                )
                val link1080Min = createLink(source = pA, name = "$pA [1080p]", quality = Qualities.P1080.value)
                val link480Max = createLink(
                    source = pB,
                    name = "$pB [480p] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [TrueHD] [7.1]",
                    quality = Qualities.P480.value,
                    headers = fullHeaders
                )
                val link480Min = createLink(source = pA, name = "$pA [480p]", quality = Qualities.P480.value)
                val linkOtherSdMax = createLink(
                    source = pB,
                    name = "$pB [576p] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [TrueHD] [7.1]",
                    quality = 576,
                    headers = fullHeaders
                )
                val linkOtherSdMin = createLink(source = pA, name = "$pA [360p]", quality = Qualities.P360.value)
                val link4kMax = createLink(
                    source = pB,
                    name = "$pB [4K] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [TrueHD] [7.1]",
                    quality = Qualities.P2160.value,
                    headers = fullHeaders
                )

                val score720Min = StreamLinkOptimizer.getStreamCompositeScore(link720Min)
                val score1080Max = StreamLinkOptimizer.getStreamCompositeScore(link1080Max)
                val sep720to1080 = score720Min - score1080Max
                minSeparation720to1080 = minOf(minSeparation720to1080, sep720to1080)
                assertTrue("720p min must strictly beat 1080p max ($pA vs $pB)", sep720to1080 > 0)

                val score1080Min = StreamLinkOptimizer.getStreamCompositeScore(link1080Min)
                val score480Max = StreamLinkOptimizer.getStreamCompositeScore(link480Max)
                val sep1080to480 = score1080Min - score480Max
                minSeparation1080to480 = minOf(minSeparation1080to480, sep1080to480)
                assertTrue("1080p min must strictly beat 480p max ($pA vs $pB)", sep1080to480 > 0)

                val score480Min = StreamLinkOptimizer.getStreamCompositeScore(link480Min)
                val scoreOtherSdMax = StreamLinkOptimizer.getStreamCompositeScore(linkOtherSdMax)
                val sep480toOtherSd = score480Min - scoreOtherSdMax
                minSeparation480toOtherSd = minOf(minSeparation480toOtherSd, sep480toOtherSd)
                assertTrue("480p min must strictly beat other SD max ($pA vs $pB)", sep480toOtherSd > 0)

                val scoreOtherSdMin = StreamLinkOptimizer.getStreamCompositeScore(linkOtherSdMin)
                val score4kMax = StreamLinkOptimizer.getStreamCompositeScore(link4kMax)
                val sepOtherSdto4K = scoreOtherSdMin - score4kMax
                minSeparationOtherSdto4K = minOf(minSeparationOtherSdto4K, sepOtherSdto4K)
                assertTrue("Other SD min (360p) must strictly beat 4K max ($pA vs $pB)", sepOtherSdto4K > 0)
            }
        }

        assertTrue("Min separation 720p -> 1080p must exceed 1540", minSeparation720to1080 >= 1540.0f)
        assertTrue("Min separation 1080p -> 480p must exceed 1540", minSeparation1080to480 >= 1540.0f)
        assertTrue("Min separation 480p -> other SD must exceed 1340", minSeparation480toOtherSd >= 1340.0f)
        assertTrue("Min separation other SD -> 4K must exceed 1340", minSeparationOtherSdto4K >= 1340.0f)
    }

    // =========================================================================
    // REQUIREMENT 2: 1080p MAXIMAL NEVER beats 720p MINIMAL
    // =========================================================================

    @Test
    fun testReq2_1080pMaximalNeverBeats720pMinimal() {
        val providers = listOf("VidLink", "YFlix", "CineJoy", "VidFast", "VidEasy", "VidSrc", "MovieBox", "RiveStream")

        for (p1080 in providers) {
            val link1080Max = createLink(
                source = p1080,
                name = "$p1080 [1080p] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [AV1] [10-bit] [TrueHD] [DTS-HD] [Atmos] [7.1] [Dual Audio] [Multi Audio]",
                url = "https://$p1080.com/1080.mp4?download&stream=1",
                quality = Qualities.P1080.value,
                type = ExtractorLinkType.VIDEO,
                headers = fullHeaders
            )

            for (p720 in providers) {
                val link720Min = createLink(
                    source = p720,
                    name = "$p720 [720p] [500kbps]",
                    url = "https://$p720.com/720.m3u8",
                    quality = Qualities.P720.value,
                    type = ExtractorLinkType.M3U8,
                    headers = emptyMap()
                )

                // 1. isBetterThan: 1080p MUST NEVER beat 720p
                assertFalse(
                    "1080p MAX ($p1080) must NEVER beat 720p MIN ($p720) in isBetterThan",
                    StreamLinkOptimizer.isBetterThan(link1080Max, link720Min)
                )

                // 2. isBetterThan: 720p MIN MUST beat 1080p MAX
                assertTrue(
                    "720p MIN ($p720) MUST beat 1080p MAX ($p1080) in isBetterThan",
                    StreamLinkOptimizer.isBetterThan(link720Min, link1080Max)
                )

                // 3. STREAM_PRIORITY_COMPARATOR within same provider or top-tier
                val isBothTopTier = StreamLinkOptimizer.isTopTierSource(link1080Max) && StreamLinkOptimizer.isTopTierSource(link720Min)
                val isBothSecondary = !StreamLinkOptimizer.isTopTierSource(link1080Max) && !StreamLinkOptimizer.isTopTierSource(link720Min)
                if (isBothTopTier || isBothSecondary || p1080 == p720) {
                    val comp720 = StreamLinkOptimizer.getStreamCompositeScore(link720Min)
                    val comp1080 = StreamLinkOptimizer.getStreamCompositeScore(link1080Max)
                    assertTrue(
                        "720p MIN ($p720: $comp720) must beat 1080p MAX ($p1080: $comp1080) in comparator",
                        comp720 > comp1080
                    )
                    assertTrue(
                        "STREAM_PRIORITY_COMPARATOR: 720p must precede 1080p",
                        StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(link720Min, link1080Max) < 0
                    )
                }
            }
        }
    }

    // =========================================================================
    // REQUIREMENT 3: 4K MAXIMAL NEVER beats 480p MINIMAL
    // =========================================================================

    @Test
    fun testReq3_4kMaximalNeverBeats480pMinimal() {
        val providers = listOf("VidLink", "YFlix", "CineJoy", "VidFast", "VidEasy", "VidSrc", "MovieBox", "RiveStream")

        for (p4k in providers) {
            val link4kMax = createLink(
                source = p4k,
                name = "$p4k [4K] [100000kbps] [REMUX] [DV] [HDR10+] [HEVC] [AV1] [10-bit] [TrueHD] [DTS-HD] [Atmos] [7.1] [Dual Audio] [Multi Audio]",
                url = "https://$p4k.com/2160.mp4?download&stream=1",
                quality = Qualities.P2160.value,
                type = ExtractorLinkType.VIDEO,
                headers = fullHeaders
            )

            for (p480 in providers) {
                val link480Min = createLink(
                    source = p480,
                    name = "$p480 [480p] [500kbps]",
                    url = "https://$p480.com/480.m3u8",
                    quality = Qualities.P480.value,
                    type = ExtractorLinkType.M3U8,
                    headers = emptyMap()
                )

                // 1. isBetterThan: 4K MUST NEVER beat 480p
                assertFalse(
                    "4K MAX ($p4k) must NEVER beat 480p MIN ($p480) in isBetterThan",
                    StreamLinkOptimizer.isBetterThan(link4kMax, link480Min)
                )

                // 2. isBetterThan: 480p MIN MUST beat 4K MAX
                assertTrue(
                    "480p MIN ($p480) MUST beat 4K MAX ($p4k) in isBetterThan",
                    StreamLinkOptimizer.isBetterThan(link480Min, link4kMax)
                )

                // 3. STREAM_PRIORITY_COMPARATOR within same provider or top-tier
                val isBothTopTier = StreamLinkOptimizer.isTopTierSource(link4kMax) && StreamLinkOptimizer.isTopTierSource(link480Min)
                val isBothSecondary = !StreamLinkOptimizer.isTopTierSource(link4kMax) && !StreamLinkOptimizer.isTopTierSource(link480Min)
                if (isBothTopTier || isBothSecondary || p4k == p480) {
                    val comp480 = StreamLinkOptimizer.getStreamCompositeScore(link480Min)
                    val comp4k = StreamLinkOptimizer.getStreamCompositeScore(link4kMax)
                    assertTrue(
                        "480p MIN ($p480: $comp480) must beat 4K MAX ($p4k: $comp4k) in comparator",
                        comp480 > comp4k
                    )
                    assertTrue(
                        "STREAM_PRIORITY_COMPARATOR: 480p must precede 4K",
                        StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR.compare(link480Min, link4kMax) < 0
                    )
                }
            }
        }
    }

    // =========================================================================
    // REQUIREMENT 4: Anti-Symmetry & Transitivity Verification
    // =========================================================================

    @Test
    fun testReq4_StreamPriorityComparator_AntiSymmetryAndTransitivity() {
        val testStreams = listOf(
            createLink("VidLink", "VidLink [720p]", "https://vidlink.pro/720.m3u8", Qualities.P720.value),
            createLink("VidLink", "VidLink [1080p]", "https://vidlink.pro/1080.m3u8", Qualities.P1080.value),
            createLink("VidLink", "VidLink [480p]", "https://vidlink.pro/480.m3u8", Qualities.P480.value),
            createLink("VidLink", "VidLink [360p]", "https://vidlink.pro/360.m3u8", Qualities.P360.value),
            createLink("VidLink", "VidLink [4K]", "https://vidlink.pro/2160.m3u8", Qualities.P2160.value),
            createLink("VidSrc", "VidSrc [720p]", "https://vidsrc.xyz/720.m3u8", Qualities.P720.value),
            createLink("VidSrc", "VidSrc [1080p]", "https://vidsrc.xyz/1080.m3u8", Qualities.P1080.value),
            createLink("MovieBox", "MovieBox [720p]", "https://moviebox.com/720.m3u8", Qualities.P720.value),
            createLink("MovieBox", "MovieBox [1080p]", "https://moviebox.com/1080.m3u8", Qualities.P1080.value)
        )

        val cmp = StreamLinkOptimizer.STREAM_PRIORITY_COMPARATOR

        // Reflexivity & Anti-symmetry
        for (a in testStreams) {
            assertEquals("Comparator must be reflexive (compare(a, a) == 0)", 0, cmp.compare(a, a))
            for (b in testStreams) {
                val ab = cmp.compare(a, b)
                val ba = cmp.compare(b, a)
                assertEquals(
                    "Anti-symmetry violation: sgn(cmp(a, b)) must equal -sgn(cmp(b, a)) for $a vs $b",
                    ab.sign,
                    -ba.sign
                )
            }
        }

        // Transitivity
        for (a in testStreams) {
            for (b in testStreams) {
                for (c in testStreams) {
                    val ab = cmp.compare(a, b)
                    val bc = cmp.compare(b, c)
                    val ac = cmp.compare(a, c)
                    if (ab < 0 && bc < 0) {
                        assertTrue("Transitivity violation: a < b and b < c but not a < c", ac < 0)
                    }
                    if (ab > 0 && bc > 0) {
                        assertTrue("Transitivity violation: a > b and b > c but not a > c", ac > 0)
                    }
                    if (ab == 0 && bc == 0) {
                        assertEquals("Equivalence transitivity violation", 0, ac)
                    }
                }
            }
        }
    }

    /**
     * ADVERSARIAL STRESS TEST: isBetterThan Anti-Symmetry & Transitivity.
     *
     * Tests whether isBetterThan satisfies strict asymmetry:
     * If isBetterThan(a, b) is true, isBetterThan(b, a) MUST be false!
     *
     * Specifically challenges the bitrate comparison step:
     * Stream A has bitrate parsed from text ([5000kbps]).
     * Stream B has NO bitrate in text, but has a superior attribute in a later step
     * (e.g. endpoint "?download", "[REMUX]", direct MP4, TrueHD, headers, or higher resolution within bucket).
     */
    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateFallthroughCheck() {
        // Stream A: 720p, VidLink, has bitrate [5000kbps], but normal endpoint
        val streamWithBitrate = createLink(
            source = "VidLink",
            name = "VidLink [720p] [5000kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        // Stream B: 720p, VidLink, NO bitrate in name, but has "?download" endpoint
        val streamWithDownloadEndpoint = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = "https://cdn.example.com/stream.mp4?download",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamWithBitrate, streamWithDownloadEndpoint)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamWithDownloadEndpoint, streamWithBitrate)

        println("EMPIRICAL CHECK - streamWithBitrate isBetterThan streamWithDownloadEndpoint: $aBetterThanB")
        println("EMPIRICAL CHECK - streamWithDownloadEndpoint isBetterThan streamWithBitrate: $bBetterThanA")

        // In a strictly consistent partial order, both CANNOT be true simultaneously!
        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan! " +
                "Both streamWithBitrate and streamWithDownloadEndpoint claim to be better than each other! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }

    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateVsVideoBadgesCheck() {
        // Stream A: 720p, VidLink, [5000kbps], plain video
        val streamA = createLink(
            source = "VidLink",
            name = "VidLink [720p] [5000kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value
        )

        // Stream B: 720p, VidLink, NO bitrate, but [REMUX] [DV]
        val streamB = createLink(
            source = "VidLink",
            name = "VidLink [720p] [REMUX] [DV]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamA, streamB)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamB, streamA)

        println("EMPIRICAL CHECK - streamA (bitrate) isBetterThan streamB (REMUX/DV): $aBetterThanB")
        println("EMPIRICAL CHECK - streamB (REMUX/DV) isBetterThan streamA (bitrate): $bBetterThanA")

        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan between bitrate and video badges! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }

    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateVsAudioBadgesCheck() {
        // Stream A: 720p, VidLink, [5000kbps]
        val streamA = createLink(
            source = "VidLink",
            name = "VidLink [720p] [5000kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value
        )

        // Stream B: 720p, VidLink, NO bitrate, but [TrueHD] [Atmos]
        val streamB = createLink(
            source = "VidLink",
            name = "VidLink [720p] [TrueHD] [Atmos]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamA, streamB)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamB, streamA)

        println("EMPIRICAL CHECK - streamA (bitrate) isBetterThan streamB (TrueHD/Atmos): $aBetterThanB")
        println("EMPIRICAL CHECK - streamB (TrueHD/Atmos) isBetterThan streamA (bitrate): $bBetterThanA")

        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan between bitrate and audio badges! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }

    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateVsDirectVideoFormatCheck() {
        // Stream A: 720p, VidLink, [5000kbps], HLS m3u8
        val streamA = createLink(
            source = "VidLink",
            name = "VidLink [720p] [5000kbps]",
            url = "https://cdn.example.com/stream.m3u8",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )

        // Stream B: 720p, VidLink, NO bitrate, direct MP4
        val streamB = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.VIDEO
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamA, streamB)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamB, streamA)

        println("EMPIRICAL CHECK - streamA (bitrate M3U8) isBetterThan streamB (direct MP4): $aBetterThanB")
        println("EMPIRICAL CHECK - streamB (direct MP4) isBetterThan streamA (bitrate M3U8): $bBetterThanA")

        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan between bitrate and direct MP4! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }

    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateVsHeadersCheck() {
        // Stream A: 720p, VidLink, [5000kbps], no headers
        val streamA = createLink(
            source = "VidLink",
            name = "VidLink [720p] [5000kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value,
            headers = emptyMap()
        )

        // Stream B: 720p, VidLink, NO bitrate, full anti-throttling headers
        val streamB = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = "https://cdn.example.com/stream.mp4",
            quality = Qualities.P720.value,
            headers = fullHeaders
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamA, streamB)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamB, streamA)

        println("EMPIRICAL CHECK - streamA (bitrate) isBetterThan streamB (headers): $aBetterThanB")
        println("EMPIRICAL CHECK - streamB (headers) isBetterThan streamA (bitrate): $bBetterThanA")

        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan between bitrate and headers! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }

    @Test
    fun testReq4_IsBetterThan_AdversarialAntiSymmetry_BitrateVsIntraBucketResolutionCheck() {
        // Stream A: 718p widescreen (score 10000), VidLink, [5000kbps]
        val streamA = createLink(
            source = "VidLink",
            name = "VidLink [718p] [5000kbps]",
            url = "https://cdn.example.com/stream.mp4",
            quality = 718
        )

        // Stream B: 720p standard (score 10000), VidLink, NO bitrate
        val streamB = createLink(
            source = "VidLink",
            name = "VidLink [720p]",
            url = "https://cdn.example.com/stream.mp4",
            quality = 720
        )

        val aBetterThanB = StreamLinkOptimizer.isBetterThan(streamA, streamB)
        val bBetterThanA = StreamLinkOptimizer.isBetterThan(streamB, streamA)

        println("EMPIRICAL CHECK - streamA (718p with bitrate) isBetterThan streamB (720p no bitrate): $aBetterThanB")
        println("EMPIRICAL CHECK - streamB (720p no bitrate) isBetterThan streamA (718p with bitrate): $bBetterThanA")

        assertFalse(
            "CRITICAL FLAW: Anti-symmetry violated in isBetterThan between bitrate and intra-bucket resolution! " +
                "(aBetterThanB=$aBetterThanB, bBetterThanA=$bBetterThanA)",
            aBetterThanB && bBetterThanA
        )
    }
}
