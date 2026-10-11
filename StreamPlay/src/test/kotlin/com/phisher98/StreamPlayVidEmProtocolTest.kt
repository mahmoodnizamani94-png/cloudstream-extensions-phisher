package com.phisher98

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Offline contract tests for the v19 curated registry and for the two pure functions that carry
 * all the risk in the VidEm integration: the embed-state scanner and the server-handle decoders.
 *
 * The network half of `invokeVidEm` was validated against live playback (movie + TV, multi-variant
 * HLS master, 10-11 caption tracks); what is pinned here is that the *parsing* half cannot silently
 * drift away from that protocol, which is the failure mode that would turn VidEm back into the
 * broken "sometimes returns nothing" source the v19 pass replaced.
 */
class StreamPlayVidEmProtocolTest {

    private val curated = setOf("vidlink", "videm", "animepahe", "animegg")

    @Before
    fun setUp() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
    }

    /**
     * A byte-faithful reduction of the real `/embed/movie/{tmdb}` page. It deliberately keeps the
     * two constructs that break naive parsers: an `emb` string containing escaped `\"` quotes and a
     * literal `}` that must not be mistaken for the end of the state object.
     */
    private val embedHtml = """
        <!doctype html><html lang="en"><head><title>VidEm Player</title></head>
        <body><div id="player"></div><script>
          (function(){
            var Q = {"type":"movie","id":"tt6263850","s":0,"e":0,"t":"eyJjIjoiYjcifQ.abc-123_XY","ap":false,"art":"https:\/\/image.tmdb.org\/t\/p\/w1280\/by8z9Fe8.jpg","title":"Deadpool & Wolverine","hide":[],"ssr":{"status":"ok","more":true,"title":"Deadpool & Wolverine","servers":[{"ref":"ref-1","k":"34fbe887e0b4","name":"Server SWM1","lang":null,"rm":true},{"ref":"ref-2","k":"f9d771f0a44b","name":"Server VNE","lang":null},{"ref":"ref-3","k":"911a0c004d1a","name":"Server English","lang":"English"}]},"emb":"<iframe src=\"https://videm.xyz/embed/movie/tt6263850\" width=\"100%\" height=\"100%\" frameborder=\"0\" scrolling=\"no\" allowfullscreen=\"true\"></iframe>"};
            function menusOpen(){return 0;}
          })();
        </script></body></html>
    """.trimIndent()

    // =========================================================================
    // 1. EMBED STATE SCANNER
    // =========================================================================

    @Test
    fun testExtractStateParsesNestedServersAndEscapedStrings() {
        val state = StreamPlayExtractor.videmExtractState(embedHtml)
        org.junit.Assert.assertNotNull("Embed state must parse", state)

        assertEquals("movie", state!!.optString("type"))
        assertEquals("tt6263850", state.optString("id"))
        assertEquals(0, state.optInt("s"))
        assertEquals(0, state.optInt("e"))
        assertEquals("eyJjIjoiYjcifQ.abc-123_XY", state.optString("t"))
        assertEquals("Deadpool & Wolverine", state.optString("title"))

        // The escaped-quote iframe payload must survive intact instead of truncating the scan.
        val embedSnippet = state.optString("emb")
        assertTrue("Share iframe must be preserved verbatim", embedSnippet.contains("https://videm.xyz/embed/movie/tt6263850"))
        assertTrue(embedSnippet.startsWith("<iframe"))
        assertTrue(embedSnippet.endsWith("</iframe>"))

        // A `}` inside a string literal must not be read as the end of the object.
        assertTrue("State must extend past the embedded '}'", state.has("emb"))
    }

    @Test
    fun testExtractStateReadsPreWarmedServerHandlesInOrder() {
        val state = StreamPlayExtractor.videmExtractState(embedHtml)!!
        assertEquals(listOf("ref-1", "ref-2", "ref-3"), StreamPlayExtractor.videmPreWarmedHandles(state))
    }

    @Test
    fun testExtractStateIsNullForAbsentEmptyAndMalformedState() {
        // No `var Q` marker at all (e.g. the engine's "Session expired" shell).
        assertNull(
            StreamPlayExtractor.videmExtractState(
                "<html><body><div>Session expired&hellip;</div><script>postMessage({type:\"VS_EXPIRED\"})</script></body></html>"
            )
        )
        // Marker present but the object never closes.
        assertNull(StreamPlayExtractor.videmExtractState("<script>var Q = {\"type\":\"movie\"</script>"))
        // Marker present but the body is not JSON at all.
        assertNull(StreamPlayExtractor.videmExtractState("<script>var Q = not-json;</script>"))
        assertNull(StreamPlayExtractor.videmExtractState(""))
    }

    @Test
    fun testExtractStateSurvivesAStateWithoutPreWarmedServers() {
        val html = """<script>var Q = {"type":"tv","id":"tt0944947","s":4,"e":1,"t":"tok.sig","ssr":null};</script>"""
        val state = StreamPlayExtractor.videmExtractState(html)!!
        assertEquals("tv", state.optString("type"))
        assertEquals(4, state.optInt("s"))
        assertEquals(1, state.optInt("e"))
        assertEquals("tok.sig", state.optString("t"))
        // A state with no warmed handles must degrade to an empty list, not to a crash or a
        // bogus handle, so the caller falls through to the refresh pass.
        assertTrue(StreamPlayExtractor.videmPreWarmedHandles(state).isEmpty())
    }

    // =========================================================================
    // 2. QUERY / HANDLE DECODERS
    // =========================================================================

    @Test
    fun testQueryMatchesThePlayersOwnWireFormat() {
        val state = JSONObject("""{"type":"tv","id":"tt0944947","s":4,"e":1,"t":"eyJjIjoiYjcifQ.abc-123_XY"}""")
        assertEquals(
            "type=tv&id=tt0944947&s=4&e=1&t=eyJjIjoiYjcifQ.abc-123_XY",
            StreamPlayExtractor.videmQuery(state)
        )
    }

    @Test
    fun testQueryPercentEncodesTokensAndMissingFieldsDegradeToZero() {
        val state = JSONObject("""{"type":"movie","id":"tt 62/63850","t":"a+b/c=d"}""")
        assertEquals(
            "type=movie&id=tt+62%2F63850&s=0&e=0&t=a%2Bb%2Fc%3Dd",
            StreamPlayExtractor.videmQuery(state)
        )
    }

    @Test
    fun testRefreshPayloadDecodesHandlesAndToleratesNonJson() {
        val payload = """
            {"status":"ok","more":true,"servers":[
              {"ref":"new-1","k":"aaaa","name":"Server VD3"},
              {"ref":"","k":"bbbb","name":"Blank must be dropped"},
              {"ref":"new-2","k":"cccc","name":"Server XPM1"}
            ]}
        """.trimIndent()
        assertEquals(
            listOf("new-1", "new-2"),
            StreamPlayExtractor.videmHandlesFromPayload(payload)
        )

        // The engine answers HTTP 200 with a JSON error body when every scraper is dry.
        assertTrue(StreamPlayExtractor.videmHandlesFromPayload("""{"error":"unavailable"}""").isEmpty())
        assertTrue(StreamPlayExtractor.videmHandlesFromPayload("""{"status":"none"}""").isEmpty())
        assertTrue(StreamPlayExtractor.videmHandlesFromPayload("<html>403</html>").isEmpty())
        assertTrue(StreamPlayExtractor.videmHandlesFromPayload("").isEmpty())
    }

    // =========================================================================
    // 3. v19 REGISTRY CONTRACT
    // =========================================================================

    @Test
    fun testRegistryExposesExactlyTheFourCuratedSourcesWithExpectedNames() {
        val providers = buildProviders()
        assertEquals(curated, providers.map { it.id }.toSet())
        assertEquals(curated, DEFAULT_TOP_TIER_PROVIDERS)

        val byId = providers.associateBy { it.id }
        assertEquals("VidLink", byId.getValue("vidlink").name)
        assertEquals("VidEm", byId.getValue("videm").name)
        assertEquals("AnimePahe", byId.getValue("animepahe").name)
        assertEquals("AnimeGG", byId.getValue("animegg").name)
        providers.forEach { assertEquals(ProviderKind.VIDEO, it.kind) }
    }

    @Test
    fun testRankLadderIsStrictlyDescendingAcrossAllFourSources() {
        val ladder = listOf("vidlink" to 100, "videm" to 95, "animepahe" to 90, "animegg" to 85)

        ladder.forEach { (id, expected) ->
            assertEquals("$id boost", expected.toFloat(), FAST_PROVIDER_BOOST.getValue(id))
            assertEquals("$id cold-start tier", LatencyTier.TIER_1, SpeculativePipeliner.classifyProvider(id))
        }

        val boosts = ladder.map { FAST_PROVIDER_BOOST.getValue(it.first) }
        assertEquals("Boosts must be strictly descending", boosts.sortedDescending(), boosts)
        assertTrue("Rank ladder must be strictly descending", (0 until boosts.size - 1).all { boosts[it] > boosts[it + 1] })

        // The lowest curated rank must still clear the top-tier threshold, otherwise AnimeGG would
        // silently be demoted to a secondary source and lose cross-source ordering.
        assertTrue(
            "Every curated boost must clear TOP_TIER_BOOST_THRESHOLD",
            boosts.all { it >= TOP_TIER_BOOST_THRESHOLD }
        )
    }

    @Test
    fun testNoCuratedSourceIsAlsoListedAsRemoved() {
        val providers = buildProviders()
        val retired = listOf("rivestream", "vidfast", "videasy", "vidflix", "vixsrc", "superstream", "HexaSU")

        // The registry and the retirement list must stay disjoint, otherwise the next settings
        // migration would scrub a source the registry is actively running.
        providers.forEach { provider ->
            assertFalse(
                "${provider.id} is registered and must therefore not be listed as removed",
                REMOVED_PROVIDER_IDS.contains(provider.id)
            )
            assertTrue("${provider.id} must be a top tier source", StreamLinkOptimizer.isTopTierProvider(provider.id))
        }

        retired.forEach { id ->
            assertTrue("$id must be listed as removed", REMOVED_PROVIDER_IDS.contains(id))
            assertFalse("$id must not be a top tier source", StreamLinkOptimizer.isTopTierProvider(id))
        }
    }

    @Test
    fun testContentGatingKeepsMovieTvAndAnimeHalvesDisjoint() {
        // Movie/TV sources must never run for anime and vice versa, which is what makes the shared
        // rank ladder safe: the two halves can never race each other.
        assertTrue(NON_ANIME_PROVIDERS.contains("vidlink"))
        assertTrue(NON_ANIME_PROVIDERS.contains("videm"))
        assertFalse("Anime sources must not be gated as Movie/TV", NON_ANIME_PROVIDERS.contains("animepahe"))
        assertFalse("Anime sources must not be gated as Movie/TV", NON_ANIME_PROVIDERS.contains("animegg"))
    }

    @Suppress("DEPRECATION")
    private fun link(
        source: String,
        name: String,
        url: String,
        quality: Int = Qualities.P1080.value,
        type: ExtractorLinkType = ExtractorLinkType.VIDEO,
        referer: String = "https://example.com/"
    ) = ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        type = type,
        headers = emptyMap()
    )

    @Test
    fun testVidEmAndAnimeggRankOnTheirOwnStreamHosts() {
        // VidEm's minted `/_stream?id=` URLs carry no product name, so the host itself has to be
        // enough to recover the rank; the same is true for AnimeGG's first-party `/play/` hosts.
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(
            link("Unknown", "stream", "https://videm.xyz/_stream?id=0f9a2024f78f77b49d548b94d9982a04", type = ExtractorLinkType.M3U8)
        ))
        assertEquals(95, StreamLinkOptimizer.getSourcePriorityRank(
            link("Unknown", "stream", "https://vidsrc.buzz/_stream?id=0f9a2024f78f77b49d548b94d9982a04", type = ExtractorLinkType.M3U8)
        ))
        assertEquals(85, StreamLinkOptimizer.getSourcePriorityRank(
            link(
                "AnimeGG",
                "AnimeGG SUB [1080p]",
                "https://www.animegg.org/play/459585/video.mp4?for=101791612341473",
                referer = "https://www.animegg.org/embed/133411"
            )
        ))
    }
}
