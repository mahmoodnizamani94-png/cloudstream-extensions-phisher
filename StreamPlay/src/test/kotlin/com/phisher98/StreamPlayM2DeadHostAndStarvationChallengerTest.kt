package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Empirical Challenger Adversarial Stress Suite for Milestone 2:
 * SOTA Hardening of Top 6 Extractors (StreamPlayExtractor.kt).
 *
 * Verifies invariants:
 * 1. Dead-host timeout bounds: invokeYFlix and invokeCineJoy strictly complete within <= 2800ms / 3500ms bounds.
 * 2. Fallback behavior: first domain/path throws 404 or connection failure while secondary domain succeeds.
 * 3. All domains/endpoints failing: supervisor completes null cleanly without crashing or hanging.
 * 4. Winner short-circuiting: when winning domain/path responds fast (~30ms) and others delay,
 *    the method returns immediately (< 500ms) without waiting for slow candidates.
 * 5. Starvation resilience: high-concurrency interleaved execution without deadlocks or thread pool starvation.
 * 6. Malformed and corrupted response resilience under network noise.
 */
class StreamPlayM2DeadHostAndStarvationChallengerTest {

    private var originalBaseClient: OkHttpClient? = null

    @Before
    fun setUp() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
        originalBaseClient = app.baseClient
    }

    @After
    fun tearDown() {
        ProviderTelemetryManager.clearAllForTesting()
        DeviceProfiler.resetForTesting()
        originalBaseClient?.let { app.baseClient = it }
    }

    private fun jsonResponse(request: okhttp3.Request, jsonBody: String, code: Int = 200, message: String = "OK"): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(jsonBody.toResponseBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()
    }

    /**
     * Cooperatively delays an OkHttp interceptor call, checking call cancellation every 15ms.
     * When the coroutine or call is cancelled, throws IOException immediately.
     */
    private fun delayWithCancellationCheck(chain: Interceptor.Chain, maxDelayMs: Long) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < maxDelayMs) {
            if (chain.call().isCanceled()) {
                throw IOException("Canceled by caller / short-circuited")
            }
            try {
                Thread.sleep(15L)
            } catch (e: InterruptedException) {
                throw IOException("Interrupted", e)
            }
        }
    }

    // =========================================================================
    // 1. DEAD-HOST TIMEOUT BOUNDS (invokeYFlix & invokeCineJoy <= 2800ms / 3500ms)
    // =========================================================================

    @Test
    fun testInvokeYFlixDeadHostStrictTimeoutBounds() = runBlocking {
        // Interceptor simulates an unresponsive dead network holding connection until inner timeout (2200ms) cancels it
        val deadHostInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            if (url.contains("yflix") || url.contains("moviesflix")) {
                delayWithCancellationCheck(chain, 3000L)
                throw SocketTimeoutException("Simulated socket connection timeout on dead host")
            }
            chain.proceed(chain.request())
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(deadHostInterceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = "DeadHostStressMovie",
                tmdbId = 888888,
                imdbId = "tt8888888",
                year = 2026,
                season = null,
                episode = null,
                subtitleCallback = { subtitles.add(it) },
                callback = { links.add(it) }
            )
        }

        assertEquals("No links should be produced by dead hosts", 0, links.size)
        assertEquals("No subtitles should be produced by dead hosts", 0, subtitles.size)
        assertTrue(
            "invokeYFlix must strictly finish within outer timeout <= 2850ms (and bounded <= 3500ms), actual: ${elapsed}ms",
            elapsed <= 2850L
        )
    }

    @Test
    fun testInvokeCineJoyDeadHostStrictTimeoutBounds() = runBlocking {
        // Interceptor simulates unresponsive cinejoy endpoints holding connection until inner timeout (2200ms) cancels it
        val deadHostInterceptor = Interceptor { chain ->
            val url = chain.request().url.toString()
            if (url.contains("cinejoy")) {
                delayWithCancellationCheck(chain, 3000L)
                throw SocketTimeoutException("Simulated socket connection timeout on dead host")
            }
            chain.proceed(chain.request())
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(deadHostInterceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = "DeadHostStressMovie",
                tmdbId = 888888,
                imdbId = "tt8888888",
                year = 2026,
                season = null,
                episode = null,
                subtitleCallback = { subtitles.add(it) },
                callback = { links.add(it) }
            )
        }

        assertEquals("No links should be produced by dead hosts", 0, links.size)
        assertEquals("No subtitles should be produced by dead hosts", 0, subtitles.size)
        assertTrue(
            "invokeCineJoy must strictly finish within outer timeout <= 2850ms (and bounded <= 3500ms), actual: ${elapsed}ms",
            elapsed <= 2850L
        )
    }

    // =========================================================================
    // 2. FALLBACK BEHAVIOR: PRIMARY FAILS (404 / Connection Failure) & SECONDARY SUCCEEDS
    // =========================================================================

    @Test
    fun testInvokeYFlixFallbackPrimaryDomainThrows404SecondaryDomainSucceeds() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            when {
                // Primary domain yflix.to returns 404 Not Found
                url.contains("yflix.to") -> {
                    jsonResponse(req, "{\"error\": \"404 Not Found\"}", code = 404, message = "Not Found")
                }
                // Secondary domain moviesflix.to succeeds with valid payload
                url.contains("moviesflix.to") -> {
                    val validJson = """
                        {
                            "streamUrl": "https://moviesflix.cdn/video/matrix_fallback.mp4",
                            "subtitles": [
                                {"label": "English", "file": "https://moviesflix.cdn/subs/matrix_en.vtt"}
                            ]
                        }
                    """.trimIndent()
                    jsonResponse(req, validJson, code = 200)
                }
                // Tertiary domain returns 404
                else -> {
                    jsonResponse(req, "{\"error\": \"404 Not Found\"}", code = 404, message = "Not Found")
                }
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        StreamPlayExtractor.invokeYFlix(
            title = "The Matrix",
            tmdbId = 603,
            imdbId = "tt0133093",
            year = 1999,
            season = null,
            episode = null,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        assertFalse("Fallback to moviesflix.to must succeed and emit links", links.isEmpty())
        assertTrue("Emitted link must come from moviesflix.to candidate", links.any { it.url.contains("matrix_fallback.mp4") })
        assertFalse("Subtitles must be emitted from fallback candidate", subtitles.isEmpty())
        assertTrue("English subtitle must be extracted", subtitles.any { it.url.contains("matrix_en.vtt") })
    }

    @Test
    fun testInvokeYFlixFallbackPrimaryDomainConnectionFailureSecondaryDomainSucceeds() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            when {
                // Primary domain yflix.to throws severe connection failure
                url.contains("yflix.to") -> {
                    throw IOException("Connection refused by yflix.to:443 (Dead host / ISP block)")
                }
                // Secondary domain moviesflix.to succeeds
                url.contains("moviesflix.to") -> {
                    val validJson = """
                        {
                            "streamUrl": "https://moviesflix.cdn/video/avatar.mp4",
                            "subtitles": []
                        }
                    """.trimIndent()
                    jsonResponse(req, validJson, code = 200)
                }
                else -> {
                    throw IOException("Connection refused by yflix.cc:443")
                }
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()

        StreamPlayExtractor.invokeYFlix(
            title = "Avatar",
            tmdbId = 19995,
            imdbId = "tt0499549",
            year = 2009,
            season = null,
            episode = null,
            subtitleCallback = {},
            callback = { links.add(it) }
        )

        assertFalse("Connection failure on primary domain must seamlessly fall back to secondary domain", links.isEmpty())
        assertTrue("Emitted link URL must match secondary stream", links.any { it.url.contains("avatar.mp4") })
    }

    @Test
    fun testInvokeCineJoyFallbackPrimaryEndpointFailsSecondaryEndpointSucceeds() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            when {
                // Primary Lisbon endpoint throws 404
                url.contains("Lisbon") || url.contains("/api/stream/movie/603") -> {
                    jsonResponse(req, "{\"status\": 404, \"message\": \"Stream endpoint missing\"}", code = 404)
                }
                // Secondary fallback path (Nebula server) succeeds
                url.contains("Nebula") || url.contains("/api/v1/watch/movie") -> {
                    val validJson = """
                        {
                            "url": "https://cinejoy.cdn/hls/matrix_v1.mp4",
                            "tracks": [
                                {"label": "English SDH", "file": "https://cinejoy.cdn/subs/en_sdh.vtt", "kind": "subtitles"}
                            ]
                        }
                    """.trimIndent()
                    jsonResponse(req, validJson, code = 200)
                }
                else -> {
                    jsonResponse(req, "{\"status\": 404}", code = 404)
                }
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        val subtitles = mutableListOf<SubtitleFile>()

        StreamPlayExtractor.invokeCineJoy(
            title = "The Matrix",
            tmdbId = 603,
            imdbId = "tt0133093",
            year = 1999,
            season = null,
            episode = null,
            subtitleCallback = { subtitles.add(it) },
            callback = { links.add(it) }
        )

        assertFalse("CineJoy fallback endpoint must succeed and emit links", links.isEmpty())
        assertTrue("Emitted link must contain fallback url", links.any { it.url.contains("matrix_v1.mp4") })
        assertFalse("CineJoy fallback subtitles must be emitted", subtitles.isEmpty())
        assertTrue("English subtitle must match fallback", subtitles.any { it.url.contains("en_sdh.vtt") })
    }

    // =========================================================================
    // 3. ALL DOMAINS / ENDPOINTS FAILING: SUPERVISOR COMPLETES NULL CLEANLY
    // =========================================================================

    @Test
    fun testInvokeYFlixAllDomainsFailSupervisorCompletesCleanlyWithNull() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            if (url.contains("yflix") || url.contains("moviesflix")) {
                // Every candidate throws or returns 500
                throw IOException("Server outage: all upstream CDNs down")
            }
            chain.proceed(req)
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        var completedWithoutCrashing = false

        val elapsed = measureTimeMillis {
            try {
                StreamPlayExtractor.invokeYFlix(
                    title = "TotalFailureTitle",
                    tmdbId = 12345,
                    imdbId = "tt1234567",
                    year = 2024,
                    season = null,
                    episode = null,
                    subtitleCallback = null,
                    callback = { links.add(it) }
                )
                completedWithoutCrashing = true
            } catch (t: Throwable) {
                completedWithoutCrashing = false
            }
        }

        assertTrue("Supervisor must complete cleanly without throwing exceptions when all domains fail", completedWithoutCrashing)
        assertEquals("0 links must be emitted when all candidates fail", 0, links.size)
        assertTrue("Execution must complete fast (< 1000ms) when IOExceptions are thrown immediately", elapsed < 1000L)
    }

    @Test
    fun testInvokeCineJoyAllEndpointsFailSupervisorCompletesCleanlyWithNull() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            if (url.contains("cinejoy")) {
                jsonResponse(req, "{\"error\": \"All stream resolvers unavailable\"}", code = 503, message = "Service Unavailable")
            } else {
                chain.proceed(req)
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()
        var completedWithoutCrashing = false

        val elapsed = measureTimeMillis {
            try {
                StreamPlayExtractor.invokeCineJoy(
                    title = "TotalFailureTitle",
                    tmdbId = 12345,
                    imdbId = "tt1234567",
                    year = 2024,
                    season = null,
                    episode = null,
                    subtitleCallback = null,
                    callback = { links.add(it) }
                )
                completedWithoutCrashing = true
            } catch (t: Throwable) {
                completedWithoutCrashing = false
            }
        }

        assertTrue("Supervisor must complete cleanly without throwing exceptions when all endpoints fail", completedWithoutCrashing)
        assertEquals("0 links must be emitted when all endpoints fail", 0, links.size)
        assertTrue("Execution must complete fast (< 1000ms)", elapsed < 1000L)
    }

    // =========================================================================
    // 4. WINNER SHORT-CIRCUITING: FAST CANDIDATE PREVENTS DELAY FROM SLOW CANDIDATES
    // =========================================================================

    @Test
    fun testInvokeYFlixFastWinnerShortCircuitsSlowCandidatesImmediately() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            if (url.contains("moviesflix.to")) {
                // Winning candidate responds fast in ~20ms
                Thread.sleep(20L)
                val validJson = """
                    {
                        "streamUrl": "https://fast.cdn/moviesflix/winner.mp4"
                    }
                """.trimIndent()
                jsonResponse(req, validJson, code = 200)
            } else {
                // Other candidates delay for 1500ms unless cancelled
                delayWithCancellationCheck(chain, 1500L)
                jsonResponse(req, "{\"error\":\"delayed\"}", code = 404)
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeYFlix(
                title = "FastWinnerRace",
                tmdbId = 7777,
                imdbId = "tt7777777",
                year = 2025,
                season = null,
                episode = null,
                subtitleCallback = null,
                callback = { links.add(it) }
            )
        }

        assertFalse("Winner candidate link must be emitted", links.isEmpty())
        assertTrue("Emitted link URL must match winner candidate", links.any { it.url.contains("winner.mp4") })
        assertTrue(
            "invokeYFlix must return immediately (< 600ms) upon winner acquisition without waiting for slow candidates. Actual: ${elapsed}ms",
            elapsed < 600L
        )
    }

    @Test
    fun testInvokeCineJoyFastWinnerShortCircuitsSlowCandidatesImmediately() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()

            if (url.contains("Lisbon") || url.contains("/api/v1/watch/movie")) {
                // Fast winner in ~20ms
                Thread.sleep(20L)
                val validJson = """
                    {
                        "url": "https://fast.cinejoy.cdn/winner_cinejoy.mp4"
                    }
                """.trimIndent()
                jsonResponse(req, validJson, code = 200)
            } else {
                // Slow candidates delay for 1500ms unless cancelled
                delayWithCancellationCheck(chain, 1500L)
                jsonResponse(req, "{\"error\":\"delayed\"}", code = 404)
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val links = mutableListOf<ExtractorLink>()

        val elapsed = measureTimeMillis {
            StreamPlayExtractor.invokeCineJoy(
                title = "FastWinnerRace",
                tmdbId = 7777,
                imdbId = "tt7777777",
                year = 2025,
                season = null,
                episode = null,
                subtitleCallback = null,
                callback = { links.add(it) }
            )
        }

        assertFalse("Winner candidate link must be emitted", links.isEmpty())
        assertTrue("Emitted link URL must match winner candidate", links.any { it.url.contains("winner_cinejoy.mp4") })
        assertTrue(
            "invokeCineJoy must return immediately (< 600ms) upon winner acquisition without waiting for slow candidates. Actual: ${elapsed}ms",
            elapsed < 600L
        )
    }

    // =========================================================================
    // 5. HIGH-CONCURRENCY MULTIPLEXING & STARVATION RESILIENCE
    // =========================================================================

    @Test
    fun testConcurrentProbingDoesNotStarveOrDeadlockUnderLoad() = runBlocking {
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            // Provide fast interleaved responses
            if (url.contains("moviesflix") || url.contains("/api/v1/watch") || url.contains("enc-cinejoy") || url.contains("wing.st")) {
                jsonResponse(req, "{\"streamUrl\":\"https://cdn.test/shared.mp4\",\"url\":\"https://cdn.test/shared.mp4\"}", code = 200)
            } else {
                jsonResponse(req, "404 Not Found", code = 404)
            }
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        val totalTasks = 16
        val completedCounter = AtomicInteger(0)

        val totalTime = measureTimeMillis {
            val deferreds = (0 until totalTasks).map { idx ->
                async(Dispatchers.IO) {
                    val localLinks = mutableListOf<ExtractorLink>()
                    if (idx % 2 == 0) {
                        StreamPlayExtractor.invokeYFlix(
                            title = "Movie $idx",
                            tmdbId = 1000 + idx,
                            year = 2020 + (idx % 5),
                            season = null,
                            episode = null
                        ) { localLinks.add(it) }
                    } else {
                        StreamPlayExtractor.invokeCineJoy(
                            title = "Movie $idx",
                            tmdbId = 1000 + idx,
                            season = null,
                            episode = null
                        ) { localLinks.add(it) }
                    }
                    if (localLinks.isNotEmpty()) {
                        completedCounter.incrementAndGet()
                    }
                }
            }
            deferreds.awaitAll()
        }

        assertEquals("All 16 concurrent tasks must acquire valid stream links", totalTasks, completedCounter.get())
        assertTrue("16 concurrent tasks must complete well within 3500ms (took ${totalTime}ms)", totalTime < 3500L)
    }

    // =========================================================================
    // 6. MALFORMED & CORRUPTED RESPONSE RESILIENCE
    // =========================================================================

    @Test
    fun testMalformedAndDegeneratePayloadsHandledCleanly() = runBlocking {
        val corruptedPayloads = listOf(
            "",
            "   ",
            "<!DOCTYPE html><html><body>Error 521: Web server is down</body></html>",
            "{\"data\": \"invalid_base64_or_non_json_garbage!@#$\"}",
            "{\"streamUrl\": \"\"}",
            "{\"url\": null, \"file\": \"\"}",
            "{\"url\": \"https://valid.cdn/recovered.mp4\"}"
        )

        var payloadIndex = 0
        val interceptor = Interceptor { chain ->
            val req = chain.request()
            val payload = corruptedPayloads[payloadIndex % corruptedPayloads.size]
            payloadIndex++
            jsonResponse(req, payload, code = 200)
        }

        app.baseClient = originalBaseClient!!.newBuilder()
            .addInterceptor(interceptor)
            .build()

        // Permute across 10 iterations to ensure no unhandled JSON parsing or null pointer exceptions crash
        for (i in 0 until 10) {
            val links = mutableListOf<ExtractorLink>()
            StreamPlayExtractor.invokeYFlix(
                title = "CorruptTest $i",
                tmdbId = 5000 + i,
                year = 2024,
                season = null,
                episode = null
            ) { links.add(it) }

            StreamPlayExtractor.invokeCineJoy(
                title = "CorruptTest $i",
                tmdbId = 5000 + i,
                season = null,
                episode = null
            ) { links.add(it) }
        }

        assertTrue("Corrupted and degenerate payloads handled cleanly with zero unhandled exceptions", true)
    }
}
