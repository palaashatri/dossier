package io.dossier.app.domain.scanner

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.dossier.app.data.web.StableProfileApiResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders a candidate URL and returns stable DOM text for profile verification.
 *
 * Before invoking a browser, supported profile URLs are resolved through stable,
 * unauthenticated public endpoints. Browser scraping remains the fallback for
 * platforms that expose no structured public endpoint.
 */
class WebViewScraper(private val context: android.content.Context) {

    sealed class Result {
        data class Rendered(val html: String, val text: String) : Result()
        data class ChallengeDetected(val reason: String) : Result()
        data class TimedOut(val reason: String = "Render did not stabilize") : Result()
        data class Failed(val reason: String) : Result()
    }

    private val stableResolver = StableProfileApiResolver()

    suspend fun scrape(url: String): Result {
        when (val structured = stableResolver.resolve(url)) {
            is StableProfileApiResolver.Resolution.Found ->
                return Result.Rendered(structured.html, structured.text)
            StableProfileApiResolver.Resolution.NotFound ->
                return Result.Rendered(
                    "<html><head><title>Profile not found</title></head><body>profile not found</body></html>",
                    "profile not found"
                )
            StableProfileApiResolver.Resolution.Unsupported,
            is StableProfileApiResolver.Resolution.Unavailable -> Unit
        }
        return scrapeWithBrowser(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithBrowser(url: String): Result = withContext(Dispatchers.Main) {
        val rendered = CompletableDeferred<Pair<String, String>>()
        var failureReason: String? = null
        val settleStarted = AtomicBoolean(false)
        val mainFrameFinished = AtomicBoolean(false)

        val webView = WebView(context)
        CookieManager.getInstance().setAcceptCookie(true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            userAgentString = USER_AGENT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameFinished.set(false)
                settleStarted.set(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                mainFrameFinished.set(true)
                if (settleStarted.compareAndSet(false, true)) {
                    launchSettleLoop(webView, rendered)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && !rendered.isCompleted) {
                    failureReason = "Load error: ${error?.description ?: "unknown"}"
                }
            }

            @Deprecated("Deprecated by Android WebView")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (!rendered.isCompleted && !mainFrameFinished.get()) {
                    failureReason = "Load error: $description (Code $errorCode)"
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                if (!rendered.isCompleted) {
                    failureReason = "TLS error while loading profile"
                }
            }
        }

        webView.loadUrl(url)

        try {
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
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun launchSettleLoop(
        webView: WebView,
        rendered: CompletableDeferred<Pair<String, String>>
    ) {
        webView.postDelayed({
            pollForStableBody(webView, rendered, attempts = 0, lastSignature = null)
        }, INITIAL_SETTLE_DELAY_MS)
    }

    private fun pollForStableBody(
        webView: WebView,
        rendered: CompletableDeferred<Pair<String, String>>,
        attempts: Int,
        lastSignature: String?
    ) {
        if (rendered.isCompleted) return
        if (attempts >= MAX_SETTLE_POLLS) {
            snapshot(webView, rendered)
            return
        }

        webView.evaluateJavascript(BODY_SIGNATURE_SCRIPT) { rawSignature ->
            val signature = unescapeJsonString(rawSignature.orEmpty())
            val isStable = signature.isNotBlank() && signature == lastSignature
            if (isStable) {
                snapshot(webView, rendered)
            } else {
                webView.postDelayed({
                    pollForStableBody(webView, rendered, attempts + 1, signature)
                }, SETTLE_POLL_INTERVAL_MS)
            }
        }
    }

    private fun snapshot(webView: WebView, rendered: CompletableDeferred<Pair<String, String>>) {
        if (rendered.isCompleted) return
        webView.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { htmlResult ->
            webView.evaluateJavascript("document.body ? document.body.innerText : ''") { textResult ->
                if (!rendered.isCompleted) {
                    rendered.complete(
                        unescapeJsonString(htmlResult.orEmpty()) to
                            unescapeJsonString(textResult.orEmpty())
                    )
                }
            }
        }
    }

    private fun classifyRendered(html: String, text: String): Result {
        if (isChallenge(html, text)) {
            return Result.ChallengeDetected("Bot-check / login wall detected")
        }
        if (html.isBlank() && text.isBlank()) {
            return Result.Failed("Browser produced an empty document")
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
            "unusual traffic",
            "cloudflare",
            "ddos protection",
            "attention required",
            "cf-challenge",
            "recaptcha",
            "are you a robot",
            "access denied"
        )
        if (challengeMarkers.any { lowerHtml.contains(it) || lowerText.contains(it) }) return true

        val compactTextLength = text.count { !it.isWhitespace() }
        val enableJsShell = lowerHtml.contains("enable javascript") && compactTextLength < 150
        val loginWall = compactTextLength < 180 &&
            (lowerHtml.contains("authwall") || lowerText.contains("log in to continue"))
        return enableJsShell || loginWall
    }

    private fun unescapeJsonString(json: String): String {
        if (json == "null") return ""
        if (json.startsWith("\"") && json.endsWith("\"") && json.length >= 2) {
            return json.substring(1, json.length - 1)
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
        }
        return json
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private const val RENDER_TIMEOUT_MS = 16_000L
        private const val INITIAL_SETTLE_DELAY_MS = 700L
        private const val SETTLE_POLL_INTERVAL_MS = 500L
        private const val MAX_SETTLE_POLLS = 14
        private const val BODY_SIGNATURE_SCRIPT =
            "(function(){try{var b=document.body;if(!b)return '';var t=b.innerText||'';return t.length+':'+t.slice(-120);}catch(e){return '';}})();"
    }
}
