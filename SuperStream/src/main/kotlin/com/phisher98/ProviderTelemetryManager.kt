package com.phisher98

import android.content.SharedPreferences
import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.regex.Pattern
import javax.net.ssl.SSLException

/**
 * State-Of-The-Art Adaptive Provider Telemetry & Scoring Engine for SuperStream
 */
object ProviderTelemetryManager {

    private const val TAG = "ProviderTelemetryManager"

    // ==================== EWMA & Scoring Constants ====================
    const val ALPHA: Float = 0.25f
    const val ONE_MINUS_ALPHA: Float = 0.75f

    const val DEFAULT_INITIAL_LATENCY_MS: Float = 2000f
    const val DEFAULT_INITIAL_SUCCESS_RATE: Float = 1.0f

    const val MIN_CLAMPED_DURATION_MS: Long = 50L
    const val MAX_CLAMPED_DURATION_MS: Long = 30_000L
    const val MAX_TIMEOUT_RECORDED_MS: Long = 15_000L
    const val MAX_TRACKED_PROVIDERS: Int = 100
    const val DEFAULT_SAVE_INTERVAL_MS: Long = 60_000L
    var saveIntervalMs: Long = DEFAULT_SAVE_INTERVAL_MS

    var masterKey: String = "SUPERSTREAM_TELEMETRY_V2"

    // ==================== Error Classification ====================
    enum class ErrorClassification {
        SUCCESS,              // Yielded >= 1 verified streaming links
        EMPTY_LINKS,          // HTTP 200 OK, but 0 links extracted
        HTTP_NOT_FOUND,       // HTTP 404 / 410 (content missing, not infrastructure dead)
        HTTP_CLIENT_ERROR,    // HTTP 401, 403, 429, Cloudflare challenge
        HTTP_SERVER_ERROR,    // HTTP 500, 502, 503, 504
        NETWORK_TIMEOUT,      // SocketTimeoutException, withTimeoutOrNull timeout
        NETWORK_IO_ERROR,     // UnknownHost, ConnectException, SSL failure
        CANCELLED             // Coroutine CancellationException (MUST NOT be recorded)
    }

    // ==================== Telemetry State Model ====================
    data class TelemetryStats(
        val providerId: String = "",
        val executionCount: Int = 0,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val latencyEwma: Float = DEFAULT_INITIAL_LATENCY_MS,
        val successRateEwma: Float = DEFAULT_INITIAL_SUCCESS_RATE,
        val consecutiveFailures: Int = 0,
        val lastExecutionMs: Long = 0L,
        val lastFailureAtMs: Long = 0L,
        val lastSuccessAtMs: Long = 0L,
        val circuitTripCount: Int = 0,
        val lastErrorType: ErrorClassification? = null
    ) {
        val successRate: Float
            get() = if (executionCount == 0) 0f else successRateEwma

        val avgTimeMs: Long
            get() = latencyEwma.toLong()

        val isCircuitBroken: Boolean
            get() = ProviderTelemetryManager.isCircuitBroken(providerId)

        val isRecovering: Boolean
            get() = ProviderTelemetryManager.isRecovering(providerId)
    }

    // Dependencies (injectable for unit tests)
    var timeProvider: TimeProvider = SystemTimeProvider
        private set

    var circuitBreaker: CircuitBreaker = CircuitBreaker(timeProvider = SystemTimeProvider)
        private set

    // Bounded in-memory LRU cache (capped at 100 entries)
    private val lruLock = Any()
    private val lruMap = object : LinkedHashMap<String, TelemetryStats>(MAX_TRACKED_PROVIDERS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TelemetryStats>?): Boolean {
            return size > MAX_TRACKED_PROVIDERS
        }
    }

    // Debounced asynchronous persistence with dirty key tracking
    private val persistenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debounceJob: Job? = null
    private val saveLock = Any()
    private val dirtyProviderKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var isDirty: Boolean = false
    @Volatile var lastSaveTimeMs: Long = 0L

    fun setTimeProviderForTesting(provider: TimeProvider) {
        timeProvider = provider
        if (circuitBreaker.timeProvider != provider) {
            circuitBreaker = CircuitBreaker(timeProvider = provider)
        }
    }

    fun setCircuitBreakerForTesting(breaker: CircuitBreaker) {
        circuitBreaker = breaker
        timeProvider = breaker.timeProvider
    }

    // ==================== Core Telemetry Recording ====================

    fun recordExecution(
        providerId: String,
        success: Boolean,
        durationMs: Long,
        errorType: ErrorClassification? = null
    ) {
        if (errorType == ErrorClassification.CANCELLED) {
            circuitBreaker.releaseCanaryPermit(providerId)
            return
        }

        val now = timeProvider.currentTimeMillis()
        val clampedDuration = durationMs.coerceIn(MIN_CLAMPED_DURATION_MS, MAX_CLAMPED_DURATION_MS).toFloat()
        val resolvedError = errorType ?: if (success) ErrorClassification.SUCCESS else ErrorClassification.EMPTY_LINKS

        synchronized(lruLock) {
            val current = lruMap[providerId] ?: TelemetryStats(providerId = providerId)
            val isFirstExecution = current.executionCount == 0

            val effectiveDuration: Float = when (resolvedError) {
                ErrorClassification.NETWORK_TIMEOUT -> minOf(durationMs, MAX_TIMEOUT_RECORDED_MS).toFloat()
                else -> clampedDuration
            }

            val updatedLatency: Float = when {
                resolvedError == ErrorClassification.HTTP_NOT_FOUND -> current.latencyEwma
                isFirstExecution -> effectiveDuration
                else -> ALPHA * effectiveDuration + ONE_MINUS_ALPHA * current.latencyEwma
            }

            val sampleSuccess = if (success) 1.0f else 0.0f
            val updatedSuccessRate: Float = if (isFirstExecution) {
                sampleSuccess
            } else {
                (ALPHA * sampleSuccess + ONE_MINUS_ALPHA * current.successRateEwma).coerceIn(0.0f, 1.0f)
            }

            if (success) {
                circuitBreaker.recordSuccess(providerId, durationMs)
            } else {
                if (resolvedError != ErrorClassification.HTTP_NOT_FOUND) {
                    circuitBreaker.recordFailure(providerId, durationMs)
                } else {
                    circuitBreaker.releaseCanaryPermit(providerId)
                }
            }

            val updatedConsecutiveFailures = circuitBreaker.getConsecutiveFailures(providerId)
            val updatedTripCount = circuitBreaker.getTripCount(providerId)

            val updated = current.copy(
                executionCount = current.executionCount + 1,
                successCount = current.successCount + if (success) 1 else 0,
                failureCount = current.failureCount + if (success) 0 else 1,
                latencyEwma = updatedLatency,
                successRateEwma = updatedSuccessRate,
                consecutiveFailures = updatedConsecutiveFailures,
                lastExecutionMs = durationMs,
                lastFailureAtMs = if (!success) now else current.lastFailureAtMs,
                lastSuccessAtMs = if (success) now else current.lastSuccessAtMs,
                circuitTripCount = updatedTripCount,
                lastErrorType = resolvedError
            )

            lruMap[providerId] = updated
            dirtyProviderKeys.add(providerId)
            isDirty = true
        }
    }

    fun recordFailure(providerId: String, durationMs: Long, throwable: Throwable?) {
        val classification = classifyThrowable(throwable)
        recordExecution(
            providerId = providerId,
            success = false,
            durationMs = durationMs,
            errorType = classification
        )
    }

    fun classifyThrowable(throwable: Throwable?): ErrorClassification {
        if (throwable == null) return ErrorClassification.EMPTY_LINKS
        if (throwable is CancellationException) return ErrorClassification.CANCELLED

        if (throwable is SocketTimeoutException || throwable is TimeoutCancellationException) {
            return ErrorClassification.NETWORK_TIMEOUT
        }

        val msg = throwable.message?.lowercase() ?: ""
        return when {
            throwable is UnknownHostException ||
            throwable is ConnectException ||
            throwable is NoRouteToHostException ||
            throwable is SSLException -> ErrorClassification.NETWORK_IO_ERROR

            msg.contains("404") || msg.contains("not found") -> ErrorClassification.HTTP_NOT_FOUND
            msg.contains("403") || msg.contains("401") || msg.contains("429") ||
                msg.contains("cloudflare") || msg.contains("blocked") -> ErrorClassification.HTTP_CLIENT_ERROR
            msg.contains("500") || msg.contains("502") || msg.contains("503") ||
                msg.contains("504") -> ErrorClassification.HTTP_SERVER_ERROR
            else -> ErrorClassification.NETWORK_IO_ERROR
        }
    }

    // ==================== Priority Scoring ====================

    fun getPriorityScore(providerId: String): Float {
        val state = circuitBreaker.getState(providerId)
        if (state == CircuitBreaker.CircuitState.OPEN) return -1000.0f
        if (state == CircuitBreaker.CircuitState.HALF_OPEN) return -25.0f

        val stats = synchronized(lruLock) { lruMap[providerId] } ?: return 0.0f
        if (stats.executionCount == 0) return 0.0f

        val timePenalty = (stats.latencyEwma / 1000f).coerceIn(0.0f, 30.0f)
        return (stats.successRateEwma * 100.0f) - timePenalty
    }

    // ==================== Circuit Breaker & Canary Probing ====================

    fun canExecute(providerId: String): Boolean {
        return circuitBreaker.canExecute(providerId)
    }

    fun isCanary(providerId: String): Boolean {
        return circuitBreaker.isCanaryActive(providerId)
    }

    fun releaseCanaryPermit(providerId: String) {
        circuitBreaker.releaseCanaryPermit(providerId)
    }

    fun isCircuitBroken(providerId: String): Boolean {
        return circuitBreaker.getState(providerId) == CircuitBreaker.CircuitState.OPEN
    }

    fun isRecovering(providerId: String): Boolean {
        return circuitBreaker.getState(providerId) == CircuitBreaker.CircuitState.HALF_OPEN
    }

    fun getCircuitState(providerId: String): CircuitBreaker.CircuitState {
        return circuitBreaker.getState(providerId)
    }

    fun getCooldownRemainingMs(providerId: String): Long {
        return circuitBreaker.getCooldownRemainingMs(providerId)
    }

    // ==================== Telemetry Getters ====================

    fun getStats(providerId: String): TelemetryStats {
        return synchronized(lruLock) {
            lruMap[providerId] ?: TelemetryStats(providerId = providerId)
        }
    }

    fun getLatencyEwma(providerId: String): Float {
        return synchronized(lruLock) {
            lruMap[providerId]?.latencyEwma ?: DEFAULT_INITIAL_LATENCY_MS
        }
    }

    fun getSuccessRateEwma(providerId: String): Float {
        return synchronized(lruLock) {
            lruMap[providerId]?.successRateEwma ?: DEFAULT_INITIAL_SUCCESS_RATE
        }
    }

    fun getTrackedProvidersCount(): Int {
        return synchronized(lruLock) { lruMap.size }
    }

    // ==================== Single-Key Persistence & Migration ====================

    fun getDirtyKeys(): Set<String> = dirtyProviderKeys.toSet()
    fun isKeyDirty(providerId: String): Boolean = dirtyProviderKeys.contains(providerId)

    fun scheduleSave(prefs: SharedPreferences?) {
        if (prefs == null || !isDirty) return
        val now = timeProvider.currentTimeMillis()
        synchronized(saveLock) {
            if (now - lastSaveTimeMs >= saveIntervalMs) {
                flushSync(prefs)
            } else if (debounceJob == null || debounceJob?.isCompleted == true) {
                val delayMs = (saveIntervalMs - (now - lastSaveTimeMs)).coerceIn(100L, saveIntervalMs)
                debounceJob = persistenceScope.launch {
                    delay(delayMs)
                    flushSync(prefs)
                }
            }
        }
    }

    fun flushSync(prefs: SharedPreferences?) {
        if (prefs == null) return
        synchronized(saveLock) {
            debounceJob?.cancel()
            debounceJob = null
            val json = serializeToJson()
            prefs.edit().putString(masterKey, json).apply()
            dirtyProviderKeys.clear()
            isDirty = false
            lastSaveTimeMs = timeProvider.currentTimeMillis()
        }
    }

    fun onCleanShutdown(prefs: SharedPreferences?) {
        flushSync(prefs)
    }

    fun loadPersistedStats(prefs: SharedPreferences?) {
        if (prefs == null) return

        if (prefs.contains(masterKey)) {
            val json = prefs.getString(masterKey, null)
            if (!json.isNullOrBlank()) {
                deserializeFromJson(json)
                return
            }
        }

        // Legacy SharedPreferences migration
        val allEntries = prefs.all
        val legacyKeys = allEntries.keys.filter { it.startsWith("provider_stats_") }
        if (legacyKeys.isNotEmpty()) {
            val editor = prefs.edit()
            synchronized(lruLock) {
                for (key in legacyKeys) {
                    val raw = allEntries[key] as? String ?: continue
                    val providerId = key.removePrefix("provider_stats_")
                    val parts = raw.split(",")
                    if (parts.size >= 4) {
                        try {
                            val succCount = parts[0].toInt()
                            val failCount = parts[1].toInt()
                            val totalTime = parts[2].toLong()
                            val consecFailures = parts[3].toInt()
                            val lastFailureAt = parts.getOrNull(4)?.toLongOrNull() ?: 0L
                            val lastExec = parts.getOrNull(5)?.toLongOrNull() ?: 0L

                            val priorLatency = if (succCount > 0) totalTime.toFloat() / succCount else DEFAULT_INITIAL_LATENCY_MS
                            val totalExec = succCount + failCount
                            val priorSuccess = if (totalExec > 0) succCount.toFloat() / totalExec else DEFAULT_INITIAL_SUCCESS_RATE

                            val stats = TelemetryStats(
                                providerId = providerId,
                                executionCount = totalExec,
                                successCount = succCount,
                                failureCount = failCount,
                                latencyEwma = priorLatency,
                                successRateEwma = priorSuccess,
                                consecutiveFailures = consecFailures,
                                lastExecutionMs = lastExec,
                                lastFailureAtMs = lastFailureAt,
                                lastSuccessAtMs = 0L,
                                circuitTripCount = if (consecFailures >= 3) 1 else 0
                            )
                            lruMap[providerId] = stats

                            if (consecFailures >= 3) {
                                val snapshot = CircuitBreaker.CircuitSnapshot(
                                    state = CircuitBreaker.CircuitState.OPEN,
                                    consecutiveFailures = consecFailures,
                                    tripCount = 1,
                                    lastTripTimestampMs = lastFailureAt,
                                    totalRequests = totalExec.toLong(),
                                    totalFailures = failCount.toLong(),
                                    lastExecutionDurationMs = lastExec
                                )
                                circuitBreaker.restoreSnapshot(providerId, snapshot)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed migrating legacy key $key: ${e.message}")
                        }
                    }
                    editor.remove(key)
                }
            }
            val json = serializeToJson()
            editor.putString(masterKey, json)
            editor.apply()
            Log.d(TAG, "Migrated ${legacyKeys.size} legacy provider_stats_* keys to $masterKey")
        }
    }

    fun serializeToJson(): String {
        val records: List<TelemetryStats> = synchronized(lruLock) {
            lruMap.values.toList()
        }

        val sb = StringBuilder(2048)
        sb.append("""{"v":2,"ts":""").append(timeProvider.currentTimeMillis()).append(""","p":[""")
        records.forEachIndexed { index, stat ->
            if (index > 0) sb.append(",")
            val snapshot = circuitBreaker.getSnapshot(stat.providerId)
            sb.append("""{""")
            sb.append(""""id":"""").append(escapeJson(stat.providerId)).append("""",""")
            sb.append(""""lat":""").append(stat.latencyEwma).append(""",""")
            sb.append(""""succ":""").append(stat.successRateEwma).append(""",""")
            sb.append(""""fail":""").append(stat.consecutiveFailures).append(""",""")
            sb.append(""""st":""").append(snapshot.state.ordinal).append(""",""")
            sb.append(""""trip":""").append(stat.circuitTripCount).append(""",""")
            sb.append(""""lastTrip":""").append(snapshot.lastTripTimestampMs).append(""",""")
            sb.append(""""totS":""").append(stat.successCount).append(""",""")
            sb.append(""""totF":""").append(stat.failureCount).append(""",""")
            sb.append(""""up":""").append(stat.lastExecutionMs)
            sb.append("""}""")
        }
        sb.append("""]}""")
        return sb.toString()
    }

    fun deserializeFromJson(json: String) {
        val objPattern = Pattern.compile("""\{[^{}]*\}""")
        val matcher = objPattern.matcher(json)

        val pairPattern = Pattern.compile(""""(\w+)":\s*("([^"\\]*(\\.[^"\\]*)*)"|[\d.-]+|true|false|null)""")

        synchronized(lruLock) {
            while (matcher.find()) {
                val objStr = matcher.group()
                if (!objStr.contains("\"id\"")) continue

                val map = mutableMapOf<String, String>()
                val pairMatcher = pairPattern.matcher(objStr)
                while (pairMatcher.find()) {
                    val k = pairMatcher.group(1) ?: continue
                    val rawV = pairMatcher.group(2) ?: ""
                    val cleanV = if (rawV.startsWith("\"") && rawV.endsWith("\"") && rawV.length >= 2) {
                        rawV.substring(1, rawV.length - 1)
                    } else rawV
                    map[k] = cleanV
                }

                val id = map["id"] ?: continue
                val lat = map["lat"]?.toFloatOrNull() ?: DEFAULT_INITIAL_LATENCY_MS
                val succ = map["succ"]?.toFloatOrNull() ?: DEFAULT_INITIAL_SUCCESS_RATE
                val fail = map["fail"]?.toIntOrNull() ?: 0
                val stOrd = map["st"]?.toIntOrNull() ?: 0
                val trip = map["trip"]?.toIntOrNull() ?: 0
                val lastTrip = map["lastTrip"]?.toLongOrNull() ?: 0L
                val totS = map["totS"]?.toIntOrNull() ?: 0
                val totF = map["totF"]?.toIntOrNull() ?: 0
                val up = map["up"]?.toLongOrNull() ?: 0L

                val st = CircuitBreaker.CircuitState.values().getOrElse(stOrd) { CircuitBreaker.CircuitState.CLOSED }
                val snapshot = CircuitBreaker.CircuitSnapshot(
                    state = st,
                    consecutiveFailures = fail,
                    tripCount = trip,
                    lastTripTimestampMs = lastTrip,
                    canaryInFlight = false,
                    canaryStartedAtMs = 0L,
                    totalRequests = (totS + totF).toLong(),
                    totalFailures = totF.toLong(),
                    lastExecutionDurationMs = up
                )
                circuitBreaker.restoreSnapshot(id, snapshot)

                val stats = TelemetryStats(
                    providerId = id,
                    executionCount = totS + totF,
                    successCount = totS,
                    failureCount = totF,
                    latencyEwma = lat,
                    successRateEwma = succ,
                    consecutiveFailures = fail,
                    lastExecutionMs = up,
                    lastFailureAtMs = if (fail > 0) lastTrip else 0L,
                    lastSuccessAtMs = 0L,
                    circuitTripCount = trip,
                    lastErrorType = if (fail > 0) ErrorClassification.EMPTY_LINKS else ErrorClassification.SUCCESS
                )
                lruMap[id] = stats
            }
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    fun clearAllForTesting() {
        synchronized(lruLock) {
            lruMap.clear()
        }
        circuitBreaker.resetAll()
        dirtyProviderKeys.clear()
        isDirty = false
        lastSaveTimeMs = 0L
        saveIntervalMs = DEFAULT_SAVE_INTERVAL_MS
    }
}
