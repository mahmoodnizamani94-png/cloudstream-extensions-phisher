package com.phisher98

import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.phisher98.StreamPlayExtractor.animexParseSourceJson
import com.phisher98.StreamPlayExtractor.animexQualityOf
import com.phisher98.StreamPlayExtractor.cinebyDecryptMvm1
import com.phisher98.StreamPlayExtractor.cinebyMix32
import com.phisher98.StreamPlayExtractor.cinebyParseDecryptedResult
import com.phisher98.StreamPlayExtractor.cinebyQualityOf
import com.phisher98.StreamPlayExtractor.cinebyRotl32
import com.phisher98.StreamPlayExtractor.cinesrcBase64UrlEncode
import com.phisher98.StreamPlayExtractor.megaplayExtractFileId
import com.phisher98.StreamPlayExtractor.megaplayParseSourcesPayload
import com.phisher98.StreamPlayExtractor.normalizeHlsResolutionToQuality
import com.phisher98.StreamPlayExtractor.parseHlsMasterVariants
import com.phisher98.StreamPlayExtractor.parseVidRiftEmbedMeta
import com.phisher98.StreamPlayExtractor.parseVidRiftOrionSubtitles
import com.phisher98.StreamPlayExtractor.parseVidRiftSourceResponse
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
        assertEquals(Qualities.P480.value, cinebyQualityOf("480p"))
        assertEquals(Qualities.P360.value, cinebyQualityOf("360p"))
        // Exact dimensions are read from the height, not the width.
        assertEquals(Qualities.P720.value, cinebyQualityOf("1280x720"))
        assertEquals(Qualities.P1080.value, cinebyQualityOf("1920x1080"))
        assertEquals(Qualities.P2160.value, cinebyQualityOf("3840x2160"))
        // Marketing grades cannot prove a height; the host measures the stream instead.
        assertEquals(Qualities.Unknown.value, cinebyQualityOf("HD"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf("SD"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf("high"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf("auto"))
        assertEquals(Qualities.Unknown.value, cinebyQualityOf(null))
    }

    @Test
    fun testAnimexQualityLabelUsesTheSameHonestContract() {
        assertEquals(Qualities.P1080.value, animexQualityOf("1080p"))
        assertEquals(Qualities.P720.value, animexQualityOf("720p"))
        assertEquals(Qualities.P1080.value, animexQualityOf("FHD"))
        assertEquals(Qualities.P2160.value, animexQualityOf("4K"))
        assertEquals(Qualities.P720.value, animexQualityOf("1280x720"))
        assertEquals(Qualities.Unknown.value, animexQualityOf("HD"))
        assertEquals(Qualities.Unknown.value, animexQualityOf("default"))
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
        assertEquals("Dragon Ball Super", parsed.meta?.name)
        assertEquals(1, parsed.meta!!.videos?.size)
        assertEquals(1, parsed.meta.videos?.first()?.episode)
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

    // ==================== 7. VidRift Protocol & Cinema Widescreen HLS Normalization ====================

    @Test
    fun testHlsMasterVariantNormalizesCinemaWidescreenTo720And1080() {
        val widescreenMaster = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x268
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=950000,RESOLUTION=854x356
            480/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,AVERAGE-BANDWIDTH=1500000,RESOLUTION=1280x534
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4200000,AVERAGE-BANDWIDTH=3600000,RESOLUTION=1920x800
            1080/index.m3u8
        """.trimIndent()

        val variants = parseHlsMasterVariants(
            masterUrl = "https://edge.reelvault.click/proxy?u=abc",
            manifestText = widescreenMaster
        )
        assertEquals(4, variants.size)
        assertEquals(Qualities.P360.value, variants[0].quality)
        assertEquals(Qualities.P480.value, variants[1].quality)
        assertEquals(Qualities.P720.value, variants[2].quality)
        assertEquals(Qualities.P1080.value, variants[3].quality)

        // Canonical host-ladder parity for intermediate & QHD frames
        assertEquals(Qualities.P1440.value, normalizeHlsResolutionToQuality(3200, 1800, "1440/index.m3u8"))
        assertEquals(Qualities.P1440.value, normalizeHlsResolutionToQuality(2880, 1620, "1440/index.m3u8"))
        assertEquals(Qualities.P480.value, normalizeHlsResolutionToQuality(960, 540, "540/index.m3u8"))
        assertEquals(Qualities.P1080.value, normalizeHlsResolutionToQuality(1600, 900, "900/index.m3u8"))
    }

    @Test
    fun testVidRiftEmbedMetaAndMultiLaneSourceParsing() {
        val embedHtml = """
            <!doctype html><html><body>
            <script>
            const embedMeta = {
                "type": "movie",
                "tmdbId": "872585",
                "playbackToken": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.test_sig",
                "orionStreams": [
                    {
                        "index": 0,
                        "url": "/api/orion/s0/master.m3u8?exp=1774500000&sig=abc",
                        "name": "Orion · Original",
                        "subtitleListUrl": "/api/orion/s0/subtitles.json?exp=1774500000&sig=abc"
                    }
                ],
                "warmManifests": {
                    "/api/orion/s0/master.m3u8?exp=1774500000&sig=abc": "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720\nhttps://embed.vidrift.net/api/orion/s0/720/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080\nhttps://embed.vidrift.net/api/orion/s0/1080/index.m3u8"
                },
                "bigbangUrl": "https://bigbang.cymru/media/872585/master.m3u8.png?sig=1",
                "bigbangSubtitles": [
                    {"code": "en", "lang": "en", "label": "English · BigBang", "url": "https://bigbang.cymru/subs/872585/en.vtt.png"}
                ],
                "vexonUrl": "https://media.reelvault.click/vexon/media/872585/index.m3u8"
            };
            </script></body></html>
        """.trimIndent()

        val meta = parseVidRiftEmbedMeta(embedHtml)
        assertNotNull(meta)
        assertEquals("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.test_sig", meta!!.playbackToken)
        assertEquals(1, meta.orionStreams.size)
        assertEquals("https://embed.vidrift.net/api/orion/s0/master.m3u8?exp=1774500000&sig=abc", meta.orionStreams[0].url)
        assertNotNull(meta.orionStreams[0].warmManifest)
        assertEquals("https://bigbang.cymru/media/872585/master.m3u8.png?sig=1", meta.bigbangUrl)
        assertEquals(1, meta.bigbangSubtitles.size)
        assertEquals("English" to "https://bigbang.cymru/subs/872585/en.vtt.png", meta.bigbangSubtitles[0])
        assertEquals("https://media.reelvault.click/vexon/media/872585/index.m3u8", meta.vexonUrl)

        val titanJson = """{
            "success": true,
            "maxRes": 720,
            "streams": [
                {"url": "https://img1.hscow.com/v4/eb/720/index_287.m3u8?sig=1", "name": "English", "type": "hls", "direct": true},
                {"url": "https://img1.fcxmb.com/v4/eb/720/index_266.m3u8?sig=2", "name": "Multi-audio", "type": "hls", "direct": true}
            ]
        }"""
        val titanStreams = parseVidRiftSourceResponse("titan", titanJson, "https://embed.vidrift.net/embed/movie/872585")
        assertEquals(2, titanStreams.size)
        assertEquals(Qualities.P720.value, titanStreams[0].quality)
        assertTrue(titanStreams[0].isDirectSingleQuality)
        assertEquals("VidRift Titan (English)", titanStreams[0].name)

        val atlasJson = """{
            "success": true,
            "maxRes": 1080,
            "streams": [
                {"proxyUrl": "https://edge.reelvault.click/proxy?u=abc", "provider": "Atlas", "type": "hls"}
            ]
        }"""
        val atlasStreams = parseVidRiftSourceResponse("atlas", atlasJson, "https://embed.vidrift.net/embed/movie/872585")
        assertEquals(1, atlasStreams.size)
        assertEquals(Qualities.Unknown.value, atlasStreams[0].quality)
        assertFalse(atlasStreams[0].isDirectSingleQuality)
        assertEquals("VidRift Atlas", atlasStreams[0].name)

        val orionSubsJson = """{
            "tracks": [
                {"code": "eng", "label": "English · Orion", "lang": "en", "url": "/api/orion/s0/sub-0.vtt?sig=xyz"}
            ]
        }"""
        val orionSubs = parseVidRiftOrionSubtitles(orionSubsJson)
        assertEquals(1, orionSubs.size)
        assertEquals("English" to "https://embed.vidrift.net/api/orion/s0/sub-0.vtt?sig=xyz", orionSubs[0])

        // Unproxied server-IP-locked Lyra URLs (containing `:41.138.216.184:` token) must be rejected
        val lyraJson = """{
            "success": true,
            "maxRes": 720,
            "streams": [
                {"url": "https://i-arch-400.husvu445bey.com/stream2/i-arch-400/abc/def:1774500000:41.138.216.184:sig/index.m3u8", "type": "hls", "direct": true},
                {"proxyUrl": "https://edge.reelvault.click/proxy?u=lyra_proxied", "url": "https://i-arch-400.husvu445bey.com/stream2/i-arch-400/abc/def:1774500000:41.138.216.184:sig/index.m3u8", "type": "hls"}
            ]
        }"""
        val lyraStreams = parseVidRiftSourceResponse("lyra", lyraJson, "https://embed.vidrift.net/embed/movie/872585")
        assertEquals("Unproxied server-IP-locked Lyra URL must be filtered while proxied stream survives", 1, lyraStreams.size)
        assertEquals("https://edge.reelvault.click/proxy?u=lyra_proxied", lyraStreams[0].url)
    }

    @Test
    fun testVidRiftIntraLaneHierarchyPrioritizesHighSpeedH264LanesOverOrionAndBigBang() {
        @Suppress("DEPRECATION")
        fun vrLink(name: String, url: String, quality: Int = Qualities.P720.value) = ExtractorLink(
            source = "VidRift",
            name = name,
            url = url,
            referer = "https://embed.vidrift.net/embed/movie/872585",
            quality = quality,
            type = ExtractorLinkType.M3U8
        )

        val direct720 = vrLink("VidRift Direct [720p]", "https://media.reelvault.click/selfhost/720/index.m3u8")
        val vexon720 = vrLink("VidRift Vexon [720p] [429 MB]", "https://media.reelvault.click/vexon/media/abc/video_720p.m3u8")
        val evion720 = vrLink("VidRift Evion [720p] [181 MB]", "https://media.reelvault.click/evion/tv/1396/1/1/720p/index.m3u8")
        val titanEng720 = vrLink("VidRift Titan (English) [720p]", "https://img1.hscow.com/v4/eb/720/index_287.m3u8")
        val atlas720 = vrLink("VidRift Atlas [720p] [193 MB]", "https://edge.reelvault.click/proxy?u=atlas720")
        val titanMulti720 = vrLink("VidRift Titan (Multi-audio) [720p]", "https://img1.fcxmb.com/v4/eb/720/index_266.m3u8")
        val vela720 = vrLink("VidRift Vela [720p] [220 MB]", "https://edge.reelvault.click/proxy?u=vela720")
        val orion720 = vrLink("[720p] [500 kbps] VidRift Orion - Original [720p] [89 MB]", "https://embed.vidrift.net/api/orion/s0/master.m3u8#vid=720p")
        val bigbang720 = vrLink("[720p] [500 kbps] VidRift BigBang [720p] [89 MB]", "https://norway-canary.bigbang.cymru/media/master.m3u8.png#vid=720p")
        val lyra720 = vrLink("VidRift Lyra [720p]", "https://i-arch-400.deton444eik.com/stream2/index.m3u8")

        val ordered = listOf(direct720, vexon720, titanEng720, atlas720, titanMulti720, vela720, orion720, bigbang720, lyra720)
        for (i in 0 until ordered.size - 1) {
            val better = ordered[i]
            val worse = ordered[i + 1]
            assertTrue(
                "${better.name} must outscore ${worse.name} in getStreamCompositeScore",
                StreamLinkOptimizer.getStreamCompositeScore(better) > StreamLinkOptimizer.getStreamCompositeScore(worse)
            )
            assertTrue(
                "${better.name} must beat ${worse.name} in isBetterThan",
                StreamLinkOptimizer.isBetterThan(better, worse)
            )
            assertFalse(
                "${worse.name} must not beat ${better.name} in isBetterThan",
                StreamLinkOptimizer.isBetterThan(worse, better)
            )
        }

        // Evion (TV owned CDN) and Vexon (Movie owned CDN) share the same top owned-CDN tier
        assertEquals(
            StreamLinkOptimizer.getVidRiftLaneScore(vexon720),
            StreamLinkOptimizer.getVidRiftLaneScore(evion720)
        )

        // Resolution hierarchy (720p > 1080p > 480p) must still strictly dominate within VidRift
        val direct1080 = vrLink("VidRift Direct [1080p]", "https://media.reelvault.click/selfhost/1080/index.m3u8", Qualities.P1080.value)
        assertTrue(
            "720p must still outscore 1080p within VidRift",
            StreamLinkOptimizer.getStreamCompositeScore(lyra720) > StreamLinkOptimizer.getStreamCompositeScore(direct1080)
        )
    }
}
