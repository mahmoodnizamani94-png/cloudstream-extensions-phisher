package com.phisher98

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeroAllocParserTest {

    // Helper simulating Jsoup DOM parsing baseline for SuperStream
    private fun parseSuperStreamFileQualitiesWithJsoup(html: String): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<Triple<String, String, String>>()
        doc.select("div.file_quality").forEach { el ->
            val url = el.attr("data-url").takeIf { it.isNotEmpty() } ?: return@forEach
            val quality = el.attr("data-quality").takeIf { it.isNotEmpty() } ?: return@forEach
            val size = el.selectFirst(".size")?.text()?.takeIf { it.isNotEmpty() } ?: return@forEach
            results.add(Triple(url, quality, size))
        }
        return results
    }

    // ==================== Parity Tests ====================

    @Test
    fun testSuperStreamHtmlParityWithJsoup() {
        val sampleHtml = """
            <div class="container">
                <div class="file_quality" data-url="https://cdn.febbox.com/video_1080p.mp4" data-quality="1080p">
                    <span class="badge">HQ</span>
                    <span class="size">2.45 GB</span>
                </div>
                <div class="file_quality" data-url="https://cdn.febbox.com/video_720p.mp4" data-quality="720p">
                    <span class="size">1.10 GB</span>
                </div>
                <div class="file_quality" data-url="https://cdn.febbox.com/video_4k.mkv" data-quality="ORG">
                    <span class="size">6.80 GB</span>
                </div>
            </div>
        """.trimIndent()

        val jsoupResult = parseSuperStreamFileQualitiesWithJsoup(sampleHtml)
        val zeroAllocResult = ZeroAllocParser.extractFileQualities(sampleHtml)

        assertEquals(3, zeroAllocResult.size)
        assertEquals(jsoupResult, zeroAllocResult)
        assertEquals("https://cdn.febbox.com/video_1080p.mp4", zeroAllocResult[0].first)
        assertEquals("1080p", zeroAllocResult[0].second)
        assertEquals("2.45 GB", zeroAllocResult[0].third)

        // Also test extractSuperStreamQualities with ORG resolution
        val superStreamQualities = ZeroAllocParser.extractSuperStreamQualities(sampleHtml)
        assertEquals(3, superStreamQualities.size)
        assertEquals("1080p", superStreamQualities[0].quality)
        assertEquals("720p", superStreamQualities[1].quality)
        assertEquals("2160p", superStreamQualities[2].quality) // ORG resolved to 2160p default
    }

    @Test
    fun testIframeExtractionParityWithJsoup() {
        val html1 = """<iframe src="https://embed.streamtape.com/e/12345" width="100%" height="100%"></iframe>"""
        val html2 = """<IFRAME class="embed-player" SRC="https://vidplay.online/e/xyz890" allowfullscreen></IFRAME>"""
        val html3 = """<div><p>No iframe here</p></div>"""
        val html4 = """<iframe data-src="https://lazy.com/player/999" width="500"></iframe>"""

        // Jsoup baseline
        val jsoup1 = Jsoup.parse(html1).select("iframe").attr("src")
        val jsoup2 = Jsoup.parse(html2).select("iframe").attr("src")

        assertEquals(jsoup1, ZeroAllocParser.extractIframeSrc(html1))
        assertEquals(jsoup2, ZeroAllocParser.extractIframeSrc(html2))
        assertNull(ZeroAllocParser.extractIframeSrc(html3))
        assertEquals("https://lazy.com/player/999", ZeroAllocParser.extractIframeSrc(html4))
    }

    @Test
    fun testScriptExtractionParityWithJsoup() {
        val html = """
            <html>
                <head>
                    <script type="text/javascript">var config = { mode: 'dark' };</script>
                </head>
                <body>
                    <script>
                        var playerInstance = jwplayer("vplayer");
                        playerInstance.setup({
                            sources: [{"file":"https://cdn.stream.com/hls/master.m3u8","type":"hls"}]
                        });
                    </script>
                </body>
            </html>
        """.trimIndent()

        val jsoupData = Jsoup.parse(html).selectFirst("script:containsData(sources:)")?.data()?.trim()
        val zeroAllocData = ZeroAllocParser.extractScriptContaining(html, "sources:")?.trim()

        assertNotNull(jsoupData)
        assertNotNull(zeroAllocData)
        assertEquals(jsoupData, zeroAllocData)
        assertTrue(zeroAllocData!!.contains("https://cdn.stream.com/hls/master.m3u8"))
    }

    @Test
    fun testAttributeExtractionAndWhere() {
        val html = """
            <meta http-equiv="refresh" content="0; url=https://destination.com/target">
            <a id="vd" class="download-btn" href="https://direct.download/file.zip">Download</a>
        """.trimIndent()

        val metaRefresh = ZeroAllocParser.extractMetaRefreshUrl(html)
        assertEquals("https://destination.com/target", metaRefresh)

        val href = ZeroAllocParser.extractAttributeWhere(html, "a", "href", "id", "vd")
        assertEquals("https://direct.download/file.zip", href)
    }

    @Test
    fun testJsAssignmentAndBlocks() {
        val js = """
            var token = 'xyz_secret_token_123';
            const streamData = {
                "tracks": [{"kind": "captions", "file": "https://sub.com/en.vtt"}],
                "details": { "duration": 120 }
            };
            let list = [1, 2, [3, 4], 5];
        """.trimIndent()

        val token = ZeroAllocParser.extractJsAssignment(js, "token")
        assertEquals("xyz_secret_token_123", token)

        val jsonBlock = ZeroAllocParser.extractJsonBlock(js, "streamData")
        assertNotNull(jsonBlock)
        assertTrue(jsonBlock!!.startsWith("{"))
        assertTrue(jsonBlock.endsWith("}"))
        assertTrue(jsonBlock.contains("tracks"))

        val arrayBlock = ZeroAllocParser.extractArrayBlock(js, "list")
        assertNotNull(arrayBlock)
        assertEquals("[1, 2, [3, 4], 5]", arrayBlock)
    }

    @Test
    fun testHtmlEntityDecoding() {
        val html = """<iframe src="https://example.com/play?id=123&amp;key=abc&quot;def&quot;&apos;ghi&apos;&lt;xyz&gt;"></iframe>"""
        val src = ZeroAllocParser.extractIframeSrc(html)
        assertEquals("https://example.com/play?id=123&key=abc\"def\"'ghi'<xyz>", src)
    }

    @Test
    fun testMalformedHtmlHandling() {
        val malformedHtml = """
            <div class="file_quality" data-url=https://cdn.febbox.com/test.mp4 data-quality="480p">
                <span class="size">450 MB</span>
        """.trimIndent()

        val extracted = ZeroAllocParser.extractFileQualities(malformedHtml)
        assertEquals(1, extracted.size)
        assertEquals("https://cdn.febbox.com/test.mp4", extracted[0].first)
        assertEquals("480p", extracted[0].second)
        assertEquals("450 MB", extracted[0].third)
    }

    @Test
    fun testEmptyAndBoundaryHtmlInputs() {
        assertTrue(ZeroAllocParser.extractFileQualities("").isEmpty())
        assertTrue(ZeroAllocParser.extractFileQualities("   \n\t  ").isEmpty())
        assertTrue(ZeroAllocParser.extractFileQualities("<div><span>hello</span></div>").isEmpty())
        assertNull(ZeroAllocParser.extractIframeSrc(""))
        assertNull(ZeroAllocParser.extractScriptContaining("", "marker"))
    }

    @Test
    fun testBenchmarkZeroDomAllocations() {
        val largeHtml = buildString {
            append("<html><body><div class='wrapper'>")
            for (i in 1..200) {
                append("<div class='filler'><p>Filler text $i</p></div>")
            }
            for (i in 1..20) {
                append("<div class='file_quality' data-url='https://cdn.stream.com/file_$i.mp4' data-quality='${i * 100}p'><span class='size'>$i GB</span></div>")
            }
            append("</div></body></html>")
        }

        // Run warmup
        for (i in 1..50) {
            ZeroAllocParser.extractFileQualities(largeHtml)
        }

        val start = System.currentTimeMillis()
        val result = ZeroAllocParser.extractFileQualities(largeHtml)
        val duration = System.currentTimeMillis() - start

        assertEquals(20, result.size)
        assertTrue("Linear zero-allocation parse completed in under 50ms (took ${duration}ms)", duration < 50L)
    }

    @Test
    fun testExtractFileQualitiesWithHtmlEntitiesParity() {
        val htmlWithEntities = """
            <div class="file_quality" data-url="https://domain.com/video.mp4?sig=abc&amp;expires=123&quot;&amp;key=val" data-quality="1080p&amp;60fps">
                <span class="size">2.5 GB</span>
            </div>
            <div class="file_quality" data-url="https://domain.com/video_720p.mp4?user=test&amp;id=99" data-quality="720p">
                <span class="size">1.2 GB</span>
            </div>
        """.trimIndent()

        val jsoupResult = parseSuperStreamFileQualitiesWithJsoup(htmlWithEntities)
        val zeroAllocResult = ZeroAllocParser.extractFileQualities(htmlWithEntities)

        assertEquals(jsoupResult.size, zeroAllocResult.size)
        assertEquals(2, zeroAllocResult.size)
        for (i in jsoupResult.indices) {
            assertEquals("URL parity mismatch at $i", jsoupResult[i].first, zeroAllocResult[i].first)
            assertEquals("Quality parity mismatch at $i", jsoupResult[i].second, zeroAllocResult[i].second)
            assertEquals("Size parity mismatch at $i", jsoupResult[i].third, zeroAllocResult[i].third)
        }
        assertEquals("https://domain.com/video.mp4?sig=abc&expires=123\"&key=val", zeroAllocResult[0].first)
        assertEquals("1080p&60fps", zeroAllocResult[0].second)
    }
}
