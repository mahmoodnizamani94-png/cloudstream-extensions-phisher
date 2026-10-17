package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.animexParseSourceJson
import com.phisher98.StreamPlayExtractor.cinebyParseDecryptedResult
import com.phisher98.StreamPlayExtractor.cinebyQualityOf
import com.phisher98.StreamPlayExtractor.megaplayExtractFileId
import com.phisher98.StreamPlayExtractor.megaplayParseSourcesPayload
import com.phisher98.StreamPlayExtractor.parseHlsMasterVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol acceptance suite for the v24 SOTA sources (Cineby, MegaPlay, Animex)
 * and the HLS Master Variant / Demuxed Audio / Bitrate-Derived Size Estimation engine,
 * plus regression verification that VidRock is permanently decommissioned.
 */
class StreamPlayV24SourceProtocolTest {

    // ==================== 1. Cineby Protocol ====================

    @Test
    fun testCinebyDecryptedPayloadParsesMultiQualityAndSubtitles() {
        val raw = """{
            "sources": [
                {"url": "https://sun.paleoak.top/file2/abc/1080.m3u8", "quality": "1080p"},
                {"url": "https://sun.paleoak.top/file2/abc/720.m3u8", "quality": "720p"},
                {"url": "https://e2e.majorplay.net/file2/abc/480.m3u8", "quality": "480p"}
            ],
            "subtitles": [
                {"url": "https://sub.videasy.net/en.vtt", "language": "English", "lang": "en"},
                {"url": "https://sub.videasy.net/es.vtt", "language": "Spanish", "lang": "es"}
            ]
        }"""
        val res = cinebyParseDecryptedResult(raw)
        assertNotNull(res)
        assertEquals(3, res!!.sources.size)
        assertEquals(Qualities.P1080.value, res.sources[0].quality)
        assertEquals(Qualities.P720.value, res.sources[1].quality)
        assertEquals(Qualities.P480.value, res.sources[2].quality)
        assertEquals(2, res.subtitles.size)
        assertEquals("English", res.subtitles[0].first)
        assertEquals("Spanish", res.subtitles[1].first)
    }

    @Test
    fun testCinebyQualityLabelMapping() {
        assertEquals(Qualities.P2160.value, cinebyQualityOf("4K"))
        assertEquals(Qualities.P2160.value, cinebyQualityOf("2160p"))
        assertEquals(Qualities.P1080.value, cinebyQualityOf("1080p"))
        assertEquals(Qualities.P1080.value, cinebyQualityOf("FHD"))
        assertEquals(Qualities.P720.value, cinebyQualityOf("720p"))
        assertEquals(Qualities.P720.value, cinebyQualityOf("HD"))
        assertEquals(Qualities.P480.value, cinebyQualityOf("480p"))
        assertEquals(Qualities.P360.value, cinebyQualityOf("360p"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf("auto"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf(null))
    }

    @Test
    fun testCinebyMalformedPayloadsDegradeGracefully() {
        assertNull(cinebyParseDecryptedResult(""))
        assertNull(cinebyParseDecryptedResult("not-json"))
        assertNull(cinebyParseDecryptedResult("{}"))
    }

    // ==================== 2. MegaPlay Protocol ====================

    @Test
    fun testMegaPlayExtractsFileIdFromEmbedHtml() {
        val html1 = """<html><body><div id="megaplay-player" data-id="16498" data-ep="1"></div></body></html>"""
        assertEquals("16498", megaplayExtractFileId(html1))

        val html2 = """<title>File 141638</title>"""
        assertEquals("141638", megaplayExtractFileId(html2))

        assertNull(megaplayExtractFileId("<html><body>No player here</body></html>"))
        assertNull(megaplayExtractFileId(""))
    }

    @Test
    fun testMegaPlayParsesPlaintextAndDecryptedSourcesWithCaptions() {
        val json = """{
            "sources": [
                {"file": "https://fetch.nexabloom.top/hls/stream/master.m3u8", "type": "hls"}
            ],
            "tracks": [
                {"file": "https://broforgotsave.online/subs/eng-0.vtt", "label": "English", "kind": "captions", "default": true},
                {"file": "https://broforgotsave.online/subs/jpn-1.vtt", "label": "Japanese", "kind": "subtitles"},
                {"file": "https://broforgotsave.online/VTT/sprite.vtt", "kind": "thumbnails"}
            ]
        }"""
        val parsed = megaplayParseSourcesPayload(json)
        assertNotNull(parsed)
        assertEquals(listOf("https://fetch.nexabloom.top/hls/stream/master.m3u8"), parsed!!.directUrls)
        assertEquals(2, parsed.tracks.size)
        assertEquals("English" to "https://broforgotsave.online/subs/eng-0.vtt", parsed.tracks[0])
        assertEquals("Japanese" to "https://broforgotsave.online/subs/jpn-1.vtt", parsed.tracks[1])
    }

    // ==================== 3. Animex Multi-Provider & Subtitle Schema ====================

    @Test
    fun testAnimexTrackSupportsBothUrlAndFileProperties() {
        val payload = """{
            "sources": [
                {"url": "https://hls.krussdomi.com/manifest/master.m3u8", "quality": "auto", "isM3U8": true},
                {"url": "https://hlsxst2.echovideo.to/manifest/master.m3u8", "quality": "1080p", "isM3U8": true}
            ],
            "tracks": [
                {"url": "https://subst.krussdomi.com/sub/en.vtt", "lang": "English", "kind": "captions"},
                {"file": "https://subst.krussdomi.com/sub/es.vtt", "label": "Spanish", "kind": "subtitles"},
                {"url": "https://subst.krussdomi.com/thumb/sprite.vtt", "kind": "thumbnails"}
            ]
        }"""
        val res = animexParseSourceJson(payload)
        assertNotNull(res)
        assertEquals(2, res!!.sources?.size)
        val validCaptions = res.tracks.orEmpty().filter {
            !(it.url ?: it.file).isNullOrBlank() && !it.kind.equals("thumbnails", ignoreCase = true)
        }
        assertEquals(2, validCaptions.size)
        assertEquals("https://subst.krussdomi.com/sub/en.vtt", validCaptions[0].url ?: validCaptions[0].file)
        assertEquals("English", validCaptions[0].lang ?: validCaptions[0].label)
        assertEquals("https://subst.krussdomi.com/sub/es.vtt", validCaptions[1].url ?: validCaptions[1].file)
        assertEquals("Spanish", validCaptions[1].lang ?: validCaptions[1].label)
    }

    // ==================== 4. HLS Master Variant & Demuxed Audio & Size Estimation ====================

    @Test
    fun testHlsMasterVariantExpandsIndividualStreamsWhenAudioIsMuxed() {
        val muxedMaster = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,AVERAGE-BANDWIDTH=1000000,RESOLUTION=1280x720
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,AVERAGE-BANDWIDTH=2400000,RESOLUTION=1920x1080
            1080/index.m3u8
        """.trimIndent()

        val variants = parseHlsMasterVariants(
            masterUrl = "https://fetch.nexabloom.top/hls/99/master.m3u8",
            manifestText = muxedMaster
        )
        assertEquals(2, variants.size)

        val v720 = variants.first { it.quality == Qualities.P720.value }
        val v1080 = variants.first { it.quality == Qualities.P1080.value }

        assertEquals("https://fetch.nexabloom.top/hls/99/720/index.m3u8", v720.url)
        assertEquals("https://fetch.nexabloom.top/hls/99/1080/index.m3u8", v1080.url)
        assertTrue("720p must have lower estimated size than 1080p", v720.estimatedSizeMb!! < v1080.estimatedSizeMb!!)
    }

    @Test
    fun testHlsMasterVariantPreservesMasterWhenAudioIsDemuxed() {
        val demuxedMaster = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud1",NAME="Japanese",DEFAULT=YES,AUTOSELECT=YES,URI="a1/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=1280x720,AUDIO="aud1"
            v720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,AUDIO="aud1"
            v1080/index.m3u8
        """.trimIndent()

        val variants = parseHlsMasterVariants(
            masterUrl = "https://sun.paleoak.top/hls/stream/master.m3u8",
            manifestText = demuxedMaster
        )
        assertEquals(2, variants.size)
        val v720 = variants.first { it.quality == Qualities.P720.value }
        val v1080 = variants.first { it.quality == Qualities.P1080.value }

        assertEquals("https://sun.paleoak.top/hls/stream/master.m3u8#vid=720p", v720.url)
        assertEquals("https://sun.paleoak.top/hls/stream/master.m3u8#vid=1080p", v1080.url)
        assertTrue(v720.estimatedSizeMb!! < v1080.estimatedSizeMb!!)
    }

    // ==================== 5. VidRock Decommissioning Contract ====================

    @Test
    fun testVidrockIsCompletelyDecommissionedAndScoresZero() {
        @Suppress("DEPRECATION")
        val vidrockLink = ExtractorLink(
            source = "VidRock",
            name = "VidRock Orion [720p]",
            url = "https://galacticvoyagers.xyz/file3/abc/master.m3u8",
            referer = "https://vidrock.net/",
            quality = Qualities.P720.value,
            type = ExtractorLinkType.M3U8
        )
        assertEquals(0, StreamLinkOptimizer.getSourcePriorityRank(vidrockLink))
        assertFalse(StreamLinkOptimizer.isTopTierSource(vidrockLink))
        assertFalse(StreamLinkOptimizer.isTopTierProvider("vidrock"))
        assertTrue(REMOVED_PROVIDER_IDS.contains("vidrock"))
    }
}
