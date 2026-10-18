package com.phisher98

import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.animexParseSourceJson
import com.phisher98.StreamPlayExtractor.cinebyDecryptMvm1
import com.phisher98.StreamPlayExtractor.cinebyMix32
import com.phisher98.StreamPlayExtractor.cinebyParseDecryptedResult
import com.phisher98.StreamPlayExtractor.cinebyQualityOf
import com.phisher98.StreamPlayExtractor.cinebyRotl32
import com.phisher98.StreamPlayExtractor.cinesrcBase64UrlEncode
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

    // ==================== 1. Cineby Protocol & Offline mvm1 Stream Cipher ====================

    @Test
    fun testCinebyOfflineMvm1StreamCipherDecryptsRoundTrip() {
        val seed = "test_seed_dbs_62715"
        val mediaId = 62715
        val plaintextJson = """{"sources":[{"url":"https://sun.paleoak.top/hls2/01/master.m3u8","quality":"1080p"}],"subtitles":[{"url":"https://sub.videasy.net/en.vtt","language":"English","lang":"en"}]}"""
        val rawPlain = ("mvm1" + plaintextJson).toByteArray(Charsets.UTF_8)
        val encrypted = rawPlain.copyOf()

        // Encrypt using identical keystream (XOR stream cipher is symmetric)
        var fnv = 2166136261L.toInt()
        for (i in seed.indices) {
            fnv = (fnv xor seed[i].code) * 16777619
        }
        val golden = 2654435769L.toInt()
        var a = cinebyMix32(cinebyMix32(fnv) xor cinebyMix32(mediaId xor golden))
        val s = IntArray(61)
        val sPresent = BooleanArray(61)
        for (e in 0 until 8) {
            val t = ((a.toLong() and 0xFFFFFFFFL) % 61L).toInt()
            a = cinebyRotl32(a + golden, 7 + (7 and e))
            s[t] = a xor cinebyMix32(a)
            sPresent[t] = true
            a = cinebyMix32(a + t)
        }
        var acc = cinebyMix32(2779096485L.toInt() xor a)
        var ctr = 0
        var idx = 0
        while (idx < encrypted.size) {
            val o = acc
            val n = ((o.toLong() and 0xFFFFFFFFL) % 61L).toInt()
            val iMask = if (sPresent[n]) -1 else 0
            val d = if (sPresent[n]) s[n] else 0
            val aval = d xor (golden * (ctr + 1))
            val l = (o xor aval) or (o and aval and iMask)
            val nextO = cinebyMix32(
                (cinebyRotl32(l + o, 31 and n) xor cinebyRotl32(o, 31 and (n * 7))) + golden
            )
            s[n] = nextO
            sPresent[n] = true
            acc = nextO
            ctr++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (idx < encrypted.size) {
                    encrypted[idx] = (encrypted[idx].toInt() xor ((nextO ushr shift) and 0xFF)).toByte()
                    idx++
                }
            }
        }
        val cipherBase64Url = cinesrcBase64UrlEncode(encrypted)
        val decrypted = cinebyDecryptMvm1(cipherBase64Url, seed, mediaId)
        assertEquals(plaintextJson, decrypted)

        val parsed = cinebyParseDecryptedResult(decrypted!!)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.sources.size)
        assertEquals("https://sun.paleoak.top/hls2/01/master.m3u8", parsed.sources[0].url)
        assertEquals(1, parsed.subtitles.size)
        assertEquals("English" to "https://sub.videasy.net/en.vtt", parsed.subtitles[0])

        // Wrong mediaId or seed must fail magic header verification and return null
        assertNull(cinebyDecryptMvm1(cipherBase64Url, seed, mediaId + 1))
        assertNull(cinebyDecryptMvm1(cipherBase64Url, "wrong_seed", mediaId))
    }

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

    // ==================== 5. Cinemeta Array Deserialization & StreamPlayAnime Self-Contained Models ====================

    @Test
    fun testCinemetaResDeserializesArrayWriterAndDirectorWithoutFailing() {
        val cinemetaJson = """{
            "meta": {
                "id": "tt4644488",
                "type": "series",
                "name": "Dragon Ball Super",
                "year": "2015–2018",
                "director": ["Kimitoshi Chioka", "Morio Hatano"],
                "writer": ["Akira Toriyama"],
                "videos": [
                    {
                        "id": "tt4644488:1:1",
                        "name": "The Peace Prize: Who'll Get the 100 Million Zeni?!",
                        "season": 1,
                        "number": 1,
                        "episode": 1,
                        "firstAired": "2015-07-05T00:00:00.000Z",
                        "released": "2015-07-05T00:00:00.000Z",
                        "description": "Goku works as a farmer."
                    }
                ]
            }
        }"""
        val parsed = mapper.readValue(cinemetaJson, CinemetaRes::class.java)
        assertNotNull(parsed.meta)
        assertEquals("Dragon Ball Super", parsed.meta!!.name)
        assertEquals(1, parsed.meta!!.videos?.size)
        assertEquals(1, parsed.meta!!.videos?.first()?.episode)
    }

    @Test
    fun testStreamPlayAnimeSelfContainedAnilistModelsParseRecommendationsWithoutAniListApiStubs() {
        val anilistJson = """{
            "data": {
                "Media": {
                    "id": 21175,
                    "idMal": 30694,
                    "title": {"romaji": "Dragon Ball Super", "english": "Dragon Ball Super"},
                    "startDate": {"year": 2015},
                    "genres": ["Action", "Adventure", "Comedy"],
                    "description": "Seven years after the events of Dragon Ball Z...",
                    "averageScore": 73,
                    "status": "FINISHED",
                    "bannerImage": "https://s4.anilist.co/file/anilistcdn/media/anime/banner/21175.jpg",
                    "coverImage": {"extraLarge": "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx21175.jpg"},
                    "episodes": 131,
                    "format": "TV",
                    "recommendations": {
                        "edges": [
                            {
                                "node": {
                                    "id": 1001,
                                    "mediaRecommendation": {
                                        "id": 813,
                                        "title": {"romaji": "Dragon Ball Z", "english": "Dragon Ball Z"},
                                        "coverImage": {"large": "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx813.jpg"}
                                    }
                                }
                            }
                        ]
                    }
                }
            }
        }"""
        val parsed = mapper.readValue(anilistJson, StreamPlayAnime.AnilistAPIResponse::class.java)
        val media = parsed.data?.media
        assertNotNull(media)
        assertEquals(21175, media!!.id)
        assertEquals(30694, media.idMal)
        assertEquals(131, media.totalEpisodes())
        assertEquals("Dragon Ball Super", media.getTitle())
        val rec = media.recommendations?.edges?.firstOrNull()?.node?.mediaRecommendation
        assertNotNull(rec)
        assertEquals(813, rec!!.id)
        assertEquals("Dragon Ball Z", rec.title?.english)
    }

    // ==================== 6. VidRock Decommissioning Contract ====================

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
