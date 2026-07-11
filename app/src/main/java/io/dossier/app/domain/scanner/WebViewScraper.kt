package io.dossier.app.domain.scanner

import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Loads a candidate URL in the embedded browser and returns the *rendered* DOM so
 * the scanner can authoritatively decide whether a profile really exists.
 *
 * This is the authority for existence — OkHttp HTML sniffing only pre-filters
 * obvious non-existence; everything that *might* exist is confirmed here.
 */
class WebViewScraper(private val context: Context) {

    /**
     * Polled outcome of a render attempt.
     *
     * - [Rendered]: real, stable DOM is available for verification.
     * - [ChallengeDetected]: Cloudflare / bot-check / login wall that hides the
     *   real content — the profile is *unverifiable*, neither found nor not-found.
     * - [TimedOut]: render never stabilized within the budget.
     * - [Failed]: load error with no usable DOM.
     */
    sealed class Result {
        data class Rendered(val html: String, val text: String) : Result()
        data class ChallengeDetected(val reason: String) : Result()
        data class TimedOut(val reason: String = "Render did not stabilize") : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun scrape(url: String): Result = withContext(Dispatchers.Main) {
        val rendered = CompletableDeferred<Pair<String, String>>()
        var failureReason: String? = null

        val webView = WebView(context)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Kick off the SPA settle loop — onPageFinished fires before many
                // single-page apps (Instagram/X/TikTok/YouTube) finish hydrating.
                launchSettleLoop(webView, rendered)
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                // Resource-level errors don't fail the whole page; only treat as
                // fatal if no usable body was ever produced.
                if (!rendered.isCompleted) {
                    failureReason = "Load error: $description (Code $errorCode)"
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                // Sub-resource HTTP errors are common and non-fatal; the page body
                // may still render. Do not complete the deferred here.
            }
        }

        webView.loadUrl(url)

        val outcome: Result = try {
            kotlinx.coroutines.withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                rendered.await()
            }?.let { (html, text) ->
                classifyRendered(html, text)
            } ?: when {
                failureReason != null -> Result.Failed(failureReason!!)
                else -> Result.TimedOut()
            }
        } catch (e: Exception) {
            Result.Failed("Render failed: ${e.localizedMessage}")
        } finally {
            webView.destroy()
        }
        outcome
    }

    // ---- Render stabilization -------------------------------------------------

    private fun launchSettleLoop(
        webView: WebView,
        rendered: CompletableDeferred<Pair<String, String>>
    ) {
        // Poll document.body.innerText.length until two consecutive reads match
        // (content stable) or we exhaust the poll budget — then snapshot.
        val checkOnce: () -> Unit = fun(): Unit {
            webView.evaluateJavascript(
                "(function(){try{return (document.body && document.body.innerText)? document.body.innerText.length : 0;}catch(e){return -1;}})();"
            ) { rawLen ->
                val len = rawLen?.trim()?.toIntOrNull()
                if (len == null || len < 0) {
                    // JS eval failed — but we may still have something usable on the
                    // second attempt; retry once more before giving up.
                    scheduleNextPoll(webView, rendered, attempts = 1, lastLen = null)
                } else {
                    scheduleNextPoll(webView, rendered, attempts = 1, lastLen = len)
                }
            }
        }
        checkOnce()
    }

    private fun scheduleNextPoll(
        webView: WebView,
        rendered: CompletableDeferred<Pair<String, String>>,
        attempts: Int,
        lastLen: Int?
    ) {
        if (rendered.isCompleted) return
        if (attempts > MAX_SETTLE_POLLS) {
            // Budget exhausted — snapshot whatever we have.
            snapshot(webView, rendered)
            return
        }
        webView.postDelayed({
            if (rendered.isCompleted) return@postDelayed
            webView.evaluateJavascript(
                "(function(){try{return (document.body && document.body.innerText)? document.body.innerText.length : 0;}catch(e){return -1;}})();"
            ) { rawLen ->
                val len = rawLen?.trim()?.toIntOrNull() ?: -1
                if (len == lastLen && len >= 0) {
                    // Two consecutive equal readings → content stable → snapshot.
                    snapshot(webView, rendered)
                } else {
                    scheduleNextPoll(webView, rendered, attempts + 1, len)
                }
            }
        }, SETTLE_POLL_INTERVAL_MS)
    }

    private fun snapshot(webView: WebView, rendered: CompletableDeferred<Pair<String, String>>) {
        if (rendered.isCompleted) return
        webView.evaluateJavascript("document.documentElement.outerHTML") { htmlResult ->
            webView.evaluateJavascript("document.body && document.body.innerText") { textResult ->
                val html = unescapeJsonString(htmlResult ?: "")
                val text = unescapeJsonString(textResult ?: "")
                rendered.complete(html to text)
            }
        }
    }

    // ---- Classification -------------------------------------------------------

    private fun classifyRendered(html: String, text: String): Result {
        if (isChallenge(html, text)) {
            return Result.ChallengeDetected("Bot-check / login wall detected")
        }
        return Result.Rendered(html, text)
    }

    private fun isChallenge(html: String, text: String): Boolean {
        val lowerHtml = html.lowercase()
        val lowerText = text.lowercase()
        val challengeMarkers = listOf(
            "just a moment",
            "checking your browser",
            "verify you are human",
            "cloudflare",
            "ddos protection",
            "attention required",
            "cf-challenge",
            "recaptcha",
            "are you a robot",
            "access denied"
        )
        if (challengeMarkers.any { lowerHtml.contains(it) || lowerText.contains(it) }) {
            return true
        }
        // A "please enable javascript" shell with effectively no rendered text is
        // also a sign we're looking at a loader/wall, not a profile.
        val enableJsShell = lowerHtml.contains("enable javascript") && text.replace("\\s".toRegex(), "").length < 150
        if (enableJsShell) return true
        return false
    }

    private fun unescapeJsonString(json: String): String {
        if (json.startsWith("\"") && json.endsWith("\"") && json.length >= 2) {
            val s = json.substring(1, json.length - 1)
            return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
        }
        return json
    }

    companion object {
        // Overall budget for a single render (load + settle).
        // Slightly longer budget for JS-heavy social profiles (demo reliability).
        private const val RENDER_TIMEOUT_MS = 14_000L
        // How long to wait between content-stability polls.
        private const val SETTLE_POLL_INTERVAL_MS = 500L
        // Max stability polls before we snapshot whatever we have.
        private const val MAX_SETTLE_POLLS = 12
    }
}
