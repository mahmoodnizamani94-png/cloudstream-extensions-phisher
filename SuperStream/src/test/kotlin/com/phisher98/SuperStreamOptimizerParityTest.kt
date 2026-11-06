package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
