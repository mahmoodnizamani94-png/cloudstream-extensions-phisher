package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SuperStreamOptimizerParityTest {

    @Suppress("DEPRECATION")
    private fun createLink(url: String, quality: Int): ExtractorLink = ExtractorLink(
        source = "Vidlink",
        name = "Vidlink DASH",
        url = url,
        referer = "",
        quality = quality,
        type = ExtractorLinkType.DASH
    )

    /** Locates the repository root by walking up from the module working directory. */
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Repository root with settings.gradle.kts not found from ${System.getProperty("user.dir")}")
    }

    @Suppress("DEPRECATION")
    private fun createSourceLink(source: String, name: String, url: String, quality: Int, type: ExtractorLinkType): ExtractorLink =
        ExtractorLink(
            source = source,
            name = name,
            url = url,
            referer = "",
            quality = quality,
            type = type
        )

    @Test
    fun testBcdnxwPreservesFilmboomRefererWhileBcdnAndSacdnStripReferer() {
        val bcdnxwUrl = "https://bcdnxw.hakunaymatata.com/resource/abc123.mp4?Expires=1793973608"
        val bcdnxwHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf(
                "Referer" to "https://filmboom.top/",
                "Origin" to "https://filmboom.top"
            ),
            url = bcdnxwUrl,
            referer = "https://filmboom.top/",
            linkType = ExtractorLinkType.VIDEO,
            source = "Vidlink",
            name = "Vidlink [720p]"
        )
        assertEquals("https://filmboom.top/", bcdnxwHeaders["Referer"])
        assertEquals("https://filmboom.top", bcdnxwHeaders["Origin"])
        assertEquals("bytes", bcdnxwHeaders["Accept-Ranges"])
        assertEquals(
            "https://filmboom.top/",
            StreamLinkOptimizer.getEffectiveReferer(
                url = bcdnxwUrl,
                referer = "https://filmboom.top/",
                headers = bcdnxwHeaders,
                source = "Vidlink",
                name = "Vidlink [720p]"
            )
        )

        val sacdnUrl = "https://sacdn.hakunaymatata.com/dash/movie/index_web.mpd#vid=720p"
        val sacdnHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf(
                "Cookie" to "CloudFront-Policy=abc;CloudFront-Signature=def",
                "Referer" to "https://vidlink.pro/"
            ),
            url = sacdnUrl,
            referer = "",
            linkType = ExtractorLinkType.DASH,
            source = "Vidlink",
            name = "Vidlink DASH [720p]"
        )
        assertNull("sacdn must strip Referer", sacdnHeaders["Referer"])
        assertNull("sacdn must strip Origin", sacdnHeaders["Origin"])
        assertEquals("CloudFront-Policy=abc;CloudFront-Signature=def", sacdnHeaders["Cookie"])
        assertEquals("", StreamLinkOptimizer.getEffectiveReferer(sacdnUrl, "", sacdnHeaders, "Vidlink", "Vidlink DASH [720p]"))
    }

    @Test
    fun testVidLinkCdnPolicyFullBehaviorMatrixMatchesStreamPlayTwin() {
        // The policy module twin must behave identically to the StreamPlay twin for every host
        // family x supplied-referer combination (the files themselves are proven byte-identical
        // by testTwinSourceFilesAreByteIdenticalWithStreamPlay).
        assertEquals(VidLinkCdnPolicy.RefererMode.REQUIRE_FILMBOOM, VidLinkCdnPolicy.modeFor("https://bcdnxw.hakunaymatata.com/r.mp4"))
        assertEquals(VidLinkCdnPolicy.RefererMode.STRIP, VidLinkCdnPolicy.modeFor("https://bcdn.hakunaymatata.com/r.mp4"))
        assertEquals(VidLinkCdnPolicy.RefererMode.STRIP, VidLinkCdnPolicy.modeFor("https://sacdn.hakunaymatata.com/r.mpd"))
        assertEquals(VidLinkCdnPolicy.RefererMode.THIRD_PARTY, VidLinkCdnPolicy.modeFor("https://cdn.vendor.example/v.mp4"))

        // bcdnxw with a supplied vidlink.pro referer must fall back to the filmboom proven default
        assertEquals(
            "https://filmboom.top/",
            StreamLinkOptimizer.getEffectiveReferer("https://bcdnxw.hakunaymatata.com/720.mp4", "https://vidlink.pro/", emptyMap(), "vidlink", "Vidlink")
        )
        // Third-party host keeps only genuine third-party referers
        assertEquals(
            "",
            StreamLinkOptimizer.getEffectiveReferer("https://cdn.vendor.example/v.mp4", "https://vidlink.pro/", emptyMap(), "vidlink", "Vidlink")
        )
        assertEquals(
            "https://mirror.example/v.mp4",
            StreamLinkOptimizer.getEffectiveReferer("https://cdn.vendor.example/v.mp4", "https://mirror.example/v.mp4", emptyMap(), "vidlink", "Vidlink")
        )

        val thirdPartyHeaders = StreamLinkOptimizer.buildDownloadHeaders(
            existingHeaders = mapOf("Referer" to "https://filmboom.top/"),
            url = "https://cdn.vendor.example/v.mp4",
            referer = "https://filmboom.top/",
            linkType = ExtractorLinkType.VIDEO,
            source = "vidlink",
            name = "Vidlink [720p]"
        )
        assertNull("filmboom artifacts must be stripped on third-party hosts", thirdPartyHeaders["Referer"])
    }

    @Test
    fun testWidescreenAspectRatioRegexParityAndDashVidFragmentCanonicalKeys() {
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie 1440x720"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie 1720x720"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Movie 2160x1080"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("Movie 2580x1080"))

        val key720 = StreamLinkOptimizer.canonicalStreamKey(
            createLink("https://sacdn.hakunaymatata.com/dash/m/index_web.mpd#vid=720p", Qualities.P720.value)
        )
        val key1080 = StreamLinkOptimizer.canonicalStreamKey(
            createLink("https://sacdn.hakunaymatata.com/dash/m/index_web.mpd#vid=1080p", Qualities.P1080.value)
        )
        assertNotEquals("DASH #vid=720p and #vid=1080p must not collide in canonicalStreamKey", key720, key1080)
    }

    @Test
    fun testDimensionClassificationAgreesWithResolutionTruthBands() {
        // Exact WxH text claims route through the shared ResolutionTruth bands — identical to the
        // manifest layer — so endpoints like 1850x720, 2650x1080, 2048x1038, 856x480 and 3440x1440
        // classify identically in both modules and both layers.
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("1850x720"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("2650x1080"))
        assertEquals(Qualities.P1080.value, StreamLinkOptimizer.extractQualityFromText("2048x1038"))
        assertEquals(Qualities.P480.value, StreamLinkOptimizer.extractQualityFromText("856x480"))
        assertEquals(Qualities.P1440.value, StreamLinkOptimizer.extractQualityFromText("3440x1440"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("1366x768"))
        assertEquals(Qualities.P480.value, StreamLinkOptimizer.extractQualityFromText("800x350"))
        assertEquals("2.57:1 ultrawide letterbox falls to the legacy long-dimension band", Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("1200x466"))

        val widths = listOf(344, 640, 700, 854, 920, 1024, 1280, 1366, 1720, 1850, 1920, 2048, 2560, 2650, 3440, 3840)
        val heights = listOf(144, 240, 268, 350, 356, 360, 372, 430, 468, 480, 534, 600, 690, 720, 768, 800, 1038, 1080, 1440, 2160)
        for (w in widths) {
            for (h in heights) {
                val expected = ResolutionTruth.classifyDimensions(w, h)
                // `x` is alphanumeric, so the explicit-numeric regex never fires inside a WxH
                // claim: the text layer classifies EVERY exact dimension through ResolutionTruth.
                assertEquals("Text layer must classify ${w}x${h} through ResolutionTruth", expected, StreamLinkOptimizer.extractQualityFromText("${w}x${h}"))
            }
        }
        // Explicit numeric tokens outside a WxH pair still fire before delegation.
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("abc 720"))
        assertEquals(Qualities.P720.value, StreamLinkOptimizer.extractQualityFromText("Movie 720p 1280x534"))
        assertEquals(Qualities.P360.value, StreamLinkOptimizer.extractQualityFromText("Movie 360p"))
    }

    @Test
    fun testIsBetterThanFollowsV27_720pFirstHierarchy() {
        fun link(source: String, quality: Int, sizeMb: Int? = null, type: ExtractorLinkType = ExtractorLinkType.M3U8): ExtractorLink =
            createSourceLink(
                source = source,
                name = buildString {
                    append("$source [")
                    append(if (quality == Qualities.P720.value) "720p" else "1080p")
                    append("]")
                    if (sizeMb != null) append(" [$sizeMb MB]")
                },
                url = "https://cdn.example/${source.lowercase()}/${quality}.m3u8",
                quality = quality,
                type = type
            )

        val vidlink720 = link("Vidlink", Qualities.P720.value, 700)
        val hexasu720 = link("HexaSU", Qualities.P720.value, 690)
        val vidlink1080 = link("Vidlink", Qualities.P1080.value, 2500)
        val autoembed720 = link("AutoEmbed", Qualities.P720.value, 680)
        val vidfast720 = link("VidFast", Qualities.P720.value, 670)
        val vidsrc720 = link("VidSrc", Qualities.P720.value, 660)
        val hexasu1080 = link("HexaSU", Qualities.P1080.value, 2400)
        val vidsrc1080 = link("VidSrc", Qualities.P1080.value, 2300)
        val hexasu480 = link("HexaSU", Qualities.P480.value, 400)

        // #1 VidLink 720p > #2 VidLink 1080p > #3 other top-tier 720p > #4 other top-tier 1080p > #5 SD
        assertTrue("VidLink 720p must beat VidLink 1080p", StreamLinkOptimizer.isBetterThan(vidlink720, vidlink1080))
        assertTrue("VidLink 1080p must beat every other top-tier 720p", StreamLinkOptimizer.isBetterThan(vidlink1080, hexasu720))
        assertTrue("HexaSU 720p must beat AutoEmbed 720p", StreamLinkOptimizer.isBetterThan(hexasu720, autoembed720))
        assertTrue("AutoEmbed 720p must beat VidFast 720p", StreamLinkOptimizer.isBetterThan(autoembed720, vidfast720))
        assertTrue("VidFast 720p must beat VidSrc 720p", StreamLinkOptimizer.isBetterThan(vidfast720, vidsrc720))
        assertTrue("VidSrc 720p must beat HexaSU 1080p", StreamLinkOptimizer.isBetterThan(vidsrc720, hexasu1080))
        assertTrue("HexaSU 1080p must beat VidSrc 1080p", StreamLinkOptimizer.isBetterThan(hexasu1080, vidsrc1080))
        assertTrue("VidSrc 1080p must beat HexaSU 480p", StreamLinkOptimizer.isBetterThan(vidsrc1080, hexasu480))
        assertFalse("1080p from a lower-tier source must not outrank a higher-tier source's 720p", StreamLinkOptimizer.isBetterThan(hexasu1080, autoembed720))
    }

    @Test
    fun testTwinSourceFilesAreByteIdenticalWithStreamPlay() {
        // The ResolutionTruth and VidLinkCdnPolicy single-source-of-truth modules must be byte
        // identical across the StreamPlay and SuperStream twins so the parity proven here at the
        // behavior level can never drift at the source level.
        val streamPlayPkg = "StreamPlay/src/main/kotlin/com/Phisher98"
        val superStreamPkg = "SuperStream/src/main/kotlin/com/phisher98"
        for (name in listOf("ResolutionTruth.kt", "VidLinkCdnPolicy.kt")) {
            val streamPlayFile = File(repoRoot(), "$streamPlayPkg/$name")
            val superStreamFile = File(repoRoot(), "$superStreamPkg/$name")
            assertTrue("$name must exist in the StreamPlay twin", streamPlayFile.isFile)
            assertTrue("$name must exist in the SuperStream twin", superStreamFile.isFile)
            assertEquals(
                "$name must be byte-identical across both twins",
                streamPlayFile.readBytes().toList(),
                superStreamFile.readBytes().toList()
            )
        }
    }

    @Test
    fun testResolutionSynthesisIsFullyRemovedFromSuperStream() {
        // AGENTS.md §5 Resolution Honesty Contract: a provider link is one piece of media and may
        // never be cloned into 720p/1080p companions sharing the same URL. The synthesis machinery
        // (createQualityCompanion + the cloning emit paths) must be gone from the SuperStream twin.
        val optimizerSource = File(repoRoot(), "SuperStream/src/main/kotlin/com/phisher98/StreamLinkOptimizer.kt").readText()
        assertFalse("createQualityCompanion must be deleted", optimizerSource.contains("createQualityCompanion"))
        assertFalse("no quality companion cloning may remain", optimizerSource.contains("comp720") || optimizerSource.contains("comp1080"))
        // The honest emitters must be present and route only genuine links
        assertTrue(optimizerSource.contains("fun processTopTierDualQualityStream"))
        assertTrue(optimizerSource.contains("fun emitTopTierDualQualityStreamLinks"))
    }
}
