package io.dossier.app.domain.discovery

import io.dossier.app.data.web.DiscoveryHttpPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.net.URI
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ProviderExecutionResult(
    val decision: ProviderResponseDecision,
    val statusCode: Int?,
    val requestedUrl: String,
    val finalUrl: String?,
    val bodyText: String,
    val latencyMs: Long,
    val attemptCount: Int
)

/** Only a directly classified public page may enter the optional renderer. */
internal object ProviderRendererPolicy {
    fun allows(state: ProviderVerificationState): Boolean =
        state == ProviderVerificationState.Present
}

class ProviderExecutionRuntime(
    private val client: OkHttpClient = defaultClient(),
    val scheduler: ProviderRequestScheduler = ProviderRequestScheduler(8),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
    private val diagnosticsRecorder: (String, ProviderOutcome, Long) -> Unit =
        ProviderDiagnosticsRuntime::record
) {
    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun setCooldown(providerId: String, durationMs: Long) {
        if (durationMs > 0L) {
            cooldowns[normalize(providerId)] = nowMillis() + durationMs
        }
    }

    fun isCooldownActive(providerId: String): Boolean {
        val until = cooldowns[normalize(providerId)] ?: return false
        val now = nowMillis()
        if (now >= until) {
            cooldowns.remove(normalize(providerId))
            return false
        }
        return true
    }

    fun clearCooldowns() {
        cooldowns.clear()
    }

    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(
        provider: ProviderDefinition,
        url: String,
        scanId: ScanId = checkNotNull(ScanCoordinatorRuntime.activeScanId()) {
            "Provider execution requires an explicitly claimed active scan"
        },
        schedulingKey: String = normalize(provider.id),
        classifier: (ProviderDefinition, ProviderResponseObservation) -> ProviderResponseDecision = ProviderResponseClassifier::classify,
        maxBodyChars: Int = MAX_BODY_CHARS
    ): ProviderExecutionResult {
        val providerId = normalize(provider.id)
        val safeSchedulingKey = normalizeSchedulingKey(schedulingKey, providerId)
        val cappedBodyChars = maxBodyChars.coerceIn(1, MAX_BODY_CHARS)

        if (isCooldownActive(providerId)) {
            val decision = ProviderResponseDecision(
                state = ProviderVerificationState.RateLimited,
                explanation = "Provider is in active cooldown"
            )
            val latency = 0L
            diagnosticsRecorder(providerId, ProviderOutcome.RateLimited, latency)
            ScanCoordinatorRuntime.onProviderUnavailable(providerId, decision.state, latency, scanId)
            return ProviderExecutionResult(
                decision = decision,
                statusCode = 429,
                requestedUrl = url,
                finalUrl = null,
                bodyText = "",
                latencyMs = latency,
                attemptCount = 0
            )
        }

        val policy = provider.requestPolicy
        val maxAttempts = (1 + policy.retryBudget).coerceIn(1, 5)
        var lastException: Throwable? = null
        var finalDecision: ProviderResponseDecision? = null
        var finalStatusCode: Int? = null
        var finalUrl: String? = null
        var finalBody = ""
        var totalLatencyMs = 0L
        var completedAttempt = 0

        for (attempt in 1..maxAttempts) {
            completedAttempt = attempt
            ScanCoordinatorRuntime.onProviderStarted(providerId, attempt, scanId)
            val startNano = nanoTime()
            lastException = null
            finalDecision = null
            finalStatusCode = null
            finalUrl = null
            finalBody = ""

            try {
                scheduler.execute(safeSchedulingKey, policy.minimumIntervalMs) providerRequest@{
                    if (isCooldownActive(providerId)) {
                        finalStatusCode = 429
                        finalDecision = ProviderResponseDecision(
                            ProviderVerificationState.RateLimited,
                            "Provider is in active cooldown"
                        )
                        return@providerRequest
                    }
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.8,*/*;q=0.4")
                        .header("Accept-Language", "en-US,en;q=0.8")
                        .build()

                    val callClient = client.newBuilder()
                        .connectTimeout(minOf(policy.timeoutMs, 5_000L), TimeUnit.MILLISECONDS)
                        .readTimeout(policy.timeoutMs, TimeUnit.MILLISECONDS)
                        .callTimeout(policy.timeoutMs, TimeUnit.MILLISECONDS)
                        .followRedirects(provider.existenceRules?.followRedirects != false)
                        .followSslRedirects(provider.existenceRules?.followRedirects != false)
                        .build()

                    val call = callClient.newCall(request)
                    val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion(
                        onCancelling = true,
                        invokeImmediately = true
                    ) {
                        call.cancel()
                    }
                    try {
                        call.execute().use { response ->
                            finalStatusCode = response.code
                            finalUrl = response.request.url.toString()
                            val rawBody = readBoundedBody(response.body, cappedBodyChars)
                            finalBody = rawBody
                            val observation = ProviderResponseObservation(
                                statusCode = response.code,
                                requestedUrl = url,
                                finalUrl = finalUrl,
                                bodyText = rawBody
                            )
                            finalDecision = classifier(provider, observation)
                        }
                    } finally {
                        cancellationHandle?.dispose()
                    }
                }

                currentCoroutineContext().ensureActive()
                val latency = ((nanoTime() - startNano) / 1_000_000L).coerceAtLeast(0L)
                totalLatencyMs += latency

                val decision = finalDecision
                if (decision != null) {
                    if (decision.state == ProviderVerificationState.AutomationChallenged ||
                        decision.state == ProviderVerificationState.RateLimited
                    ) {
                        setCooldown(providerId, policy.cooldownMs)
                    }

                    val isTransient = decision.state == ProviderVerificationState.UnexpectedStatus &&
                        finalStatusCode != null &&
                        DiscoveryHttpPolicy.isTransientHttpStatus(finalStatusCode!!)
                    if (isTransient && attempt < maxAttempts) {
                        val retryDelay = DiscoveryHttpPolicy.retryDelayMillis(attempt, null, policy.minimumIntervalMs)
                        delay(retryDelay)
                        continue
                    }
                    break
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ioe: IOException) {
                if (!currentCoroutineContext().isActive) {
                    currentCoroutineContext().ensureActive()
                }
                val latency = ((nanoTime() - startNano) / 1_000_000L).coerceAtLeast(0L)
                totalLatencyMs += latency
                lastException = ioe
                if (attempt < maxAttempts) {
                    val retryDelay = DiscoveryHttpPolicy.retryDelayMillis(attempt, null, policy.minimumIntervalMs)
                    delay(retryDelay)
                    continue
                }
                finalDecision = when (ioe) {
                    is SocketTimeoutException, is InterruptedIOException -> ProviderResponseDecision(
                        ProviderVerificationState.Timeout,
                        "Request timed out"
                    )
                    is UnknownHostException, is ConnectException -> ProviderResponseDecision(
                        ProviderVerificationState.NetworkUnavailable,
                        "Provider network is unavailable"
                    )
                    else -> ProviderResponseDecision(
                        ProviderVerificationState.InvalidResponse,
                        "Provider request failed"
                    )
                }
            } catch (t: Exception) {
                if (!currentCoroutineContext().isActive) {
                    currentCoroutineContext().ensureActive()
                }
                val latency = ((nanoTime() - startNano) / 1_000_000L).coerceAtLeast(0L)
                totalLatencyMs += latency
                lastException = t
                finalDecision = ProviderResponseDecision(
                    ProviderVerificationState.InvalidResponse,
                    "Execution failure: ${t.javaClass.simpleName}"
                )
                break
            }
        }

        val decision = finalDecision ?: ProviderResponseDecision(
            ProviderVerificationState.InvalidResponse,
            "Provider execution failed"
        )

        val outcome = mapToOutcome(decision.state, finalStatusCode, lastException)
        diagnosticsRecorder(providerId, outcome, totalLatencyMs)

        if (decision.state in setOf(
                ProviderVerificationState.Present,
                ProviderVerificationState.NotFound,
                ProviderVerificationState.SoftNotFound
            )
        ) {
            ScanCoordinatorRuntime.onProviderCompleted(providerId, decision.state, totalLatencyMs, scanId)
        } else {
            ScanCoordinatorRuntime.onProviderUnavailable(providerId, decision.state, totalLatencyMs, scanId)
        }

        return ProviderExecutionResult(
            decision = decision,
            statusCode = finalStatusCode,
            requestedUrl = url,
            finalUrl = finalUrl,
            bodyText = finalBody,
            latencyMs = totalLatencyMs,
            attemptCount = completedAttempt
        )
    }

    private fun mapToOutcome(
        state: ProviderVerificationState,
        statusCode: Int?,
        exception: Throwable?
    ): ProviderOutcome = when {
        exception is SocketTimeoutException ||
            exception is InterruptedIOException ||
            exception is TimeoutCancellationException -> ProviderOutcome.Timeout
        exception is UnknownHostException || exception is ConnectException -> ProviderOutcome.NetworkFailure
        statusCode == 429 -> ProviderOutcome.RateLimited
        state == ProviderVerificationState.RateLimited -> ProviderOutcome.RateLimited
        state == ProviderVerificationState.Timeout -> ProviderOutcome.Timeout
        state == ProviderVerificationState.NetworkUnavailable -> ProviderOutcome.NetworkFailure
        state == ProviderVerificationState.Present -> ProviderOutcome.Success
        state == ProviderVerificationState.NotFound -> ProviderOutcome.NotFound
        state == ProviderVerificationState.SoftNotFound -> ProviderOutcome.SoftNotFound
        state == ProviderVerificationState.AuthenticationRequired -> ProviderOutcome.AuthenticationRequired
        state == ProviderVerificationState.AutomationChallenged -> ProviderOutcome.UnsupportedAutomation
        state == ProviderVerificationState.RedirectedOutsideProvider -> ProviderOutcome.ProviderChanged
        state == ProviderVerificationState.UnexpectedStatus -> ProviderOutcome.ProviderChanged
        state == ProviderVerificationState.InvalidResponse -> ProviderOutcome.ParseFailure
        else -> ProviderOutcome.NetworkFailure
    }

    private fun normalize(value: String): String {
        val normalized = value.trim().lowercase()
        return normalized.takeIf {
            it.length in 1..160 && providerIdPattern.matches(it)
        } ?: "unknown"
    }

    companion object {
        const val USER_AGENT = "Dossier/0.1 authorized-assessment"
        const val MAX_BODY_CHARS = 1_000_000
        private val providerIdPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        private val schedulingKeyPattern = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")

        /**
         * Build a bounded declarative definition for an explicitly supplied
         * public URL that is not present in the reviewed provider catalog.
         *
         * This is an execution adapter, not a catalog entry: the host is
         * hashed into the lifecycle ID, so coordinator events never expose a
         * user URL or inflate catalog breadth/health counts.  The definition
         * exists only for the duration of the request and keeps the same
         * scheduler, retry, timeout, body-bound and redirect policy as a
         * catalog-backed provider.
         */
        fun uncataloguedProfileDefinition(url: String): ProviderDefinition? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme !in setOf("http", "https")) return null
            val host = uri.host
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.removePrefix("www.")
                ?.takeIf(String::isNotBlank)
                ?: return null
            val providerId = "unmapped-${sha256(host).take(20)}"
            return ProviderDefinition(
                id = providerId,
                displayName = "Uncatalogued public host",
                category = ProviderCategory.PersonalWebsite,
                queryCapabilities = setOf(QueryCapability.Url),
                existenceRules = ExistenceRules(
                    requiredStatus = setOf(200),
                    notFoundStatus = setOf(404, 410),
                    softNotFoundText = listOf(
                        "page not found",
                        "profile not found",
                        "user not found",
                        "account not found",
                        "does not exist",
                        "doesn't exist"
                    ),
                    authenticationText = listOf(
                        "sign in to continue",
                        "log in to continue",
                        "login required"
                    ),
                    challengeText = listOf(
                        "verify you are human",
                        "checking your browser",
                        "unusual traffic"
                    )
                ),
                priority = 0,
                reliability = SourceReliability.DirectPersonalWebsite,
                requestPolicy = ProviderRequestPolicy(
                    maxConcurrency = 1,
                    minimumIntervalMs = 750,
                    timeoutMs = 5_000,
                    retryBudget = 1,
                    cooldownMs = 30_000
                ),
                legacyTemplateCompatible = false,
                approvedHosts = setOf(host)
            )
        }

        /** Stable, URL-free lifecycle ID for an uncatalogued public host. */
        fun uncataloguedProviderId(url: String): String? =
            uncataloguedProfileDefinition(url)?.id

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

        internal fun normalizeSchedulingKey(value: String, fallbackProviderId: String): String {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return normalized.takeIf {
                schedulingKeyPattern.matches(it) && ".." !in it
            } ?: fallbackProviderId
        }

        fun readBoundedBody(body: ResponseBody?, maxChars: Int = MAX_BODY_CHARS): String {
            if (body == null) return ""
            return body.charStream().use { reader ->
                val buffer = CharArray(8192)
                val out = StringBuilder()
                var total = 0
                while (total < maxChars) {
                    val toRead = minOf(buffer.size, maxChars - total)
                    val read = reader.read(buffer, 0, toRead)
                    if (read < 0) break
                    out.append(buffer, 0, read)
                    total += read
                }
                out.toString()
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
