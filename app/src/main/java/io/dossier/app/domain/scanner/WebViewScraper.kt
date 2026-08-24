package io.dossier.app.domain.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.dossier.app.data.web.StableProfileApiResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure, JVM-testable privacy, security, and verification policies for [WebViewScraper].
 */
object WebViewScraperPolicy {
    /**
     * Non-impersonating generic Dossier user agent indicating authorized public self-audit.
     */
    const val USER_AGENT =
        "Dossier/0.1 public-self-audit (+https://github.com/palaashatri/dossier)"

    /**
     * Maximum settle polls allowed before capturing a snapshot.
     */
    const val MAX_SETTLE_POLLS = 14

    /**
     * Initial settle delay before starting signature polling.
     */
    const val INITIAL_SETTLE_DELAY_MS = 700L

    /**
     * Interval between signature polling attempts.
     */
    const val SETTLE_POLL_INTERVAL_MS = 500L

    /**
     * Overall timeout for browser rendering before failing closed.
     */
    const val RENDER_TIMEOUT_MS = 16_000L

    /**
     * JavaScript snippet to compute DOM body stability signature.
     */
    const val BODY_SIGNATURE_SCRIPT =
        "(function(){try{var b=document.body;if(!b)return '';var t=b.innerText||'';return t.length+':'+t.slice(-120);}catch(e){return '';}})();"

    /**
     * Enforces fail-closed URL validation: only public HTTP and HTTPS schemes with valid hosts
     * are permitted for scraping. Local files, content providers, data URIs, javascript schemes,
     * and malformed URLs are strictly rejected.
     */
    fun isAllowedUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return false
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * A scrape may render one public profile host only. Top-level redirects to
     * login brokers, tracking domains, local hosts, or HTTPS downgrade targets
     * are blocked before WebView can follow them.
     */
    fun isAllowedNavigation(initialUrl: String, targetUrl: String): Boolean {
        if (!isAllowedUrl(initialUrl) || !isAllowedUrl(targetUrl)) return false
        return runCatching {
            val initial = URI(initialUrl.trim())
            val target = URI(targetUrl.trim())
            val initialScheme = initial.scheme?.lowercase() ?: return@runCatching false
            val targetScheme = target.scheme?.lowercase() ?: return@runCatching false
            if (initialScheme == "https" && targetScheme != "https") return@runCatching false
            normalizeHost(initial.host) == normalizeHost(target.host)
        }.getOrDefault(false)
    }

    private fun normalizeHost(host: String?): String? = host
        ?.trim()
        ?.lowercase()
        ?.removePrefix("www.")
        ?.takeIf(String::isNotBlank)

    /**
     * Classifies rendered DOM text and HTML into a structured [WebViewScraper.Result].
     */
    fun classifyRendered(html: String, text: String): WebViewScraper.Result {
        if (isChallenge(html, text)) return WebViewScraper.Result.ChallengeDetected("Bot-check / login wall detected")
        if (html.isBlank() && text.isBlank()) return WebViewScraper.Result.Failed("Browser produced an empty document")
        return WebViewScraper.Result.Rendered(html, text)
    }

    /**
     * Checks if the rendered page contains bot challenges, DDoS protection walls, or login walls.
     */
    fun isChallenge(html: String, text: String): Boolean {
        val lowerHtml = html.lowercase()
        val lowerText = text.lowercase()
        val challengeMarkers = listOf(
            "just a moment", "checking your browser", "verify you are human", "unusual traffic",
            "cloudflare", "ddos protection", "attention required", "cf-challenge", "recaptcha",
            "are you a robot", "access denied"
        )
        if (challengeMarkers.any { lowerHtml.contains(it) || lowerText.contains(it) }) return true

        val compactTextLength = text.count { !it.isWhitespace() }
        val enableJsShell = lowerHtml.contains("enable javascript") && compactTextLength < 150
        val loginWall = compactTextLength < 180 &&
            (lowerHtml.contains("authwall") || lowerText.contains("log in to continue"))
        return enableJsShell || loginWall
    }

    /**
     * Unescapes JSON string literals returned by WebView `evaluateJavascript`.
     */
    fun unescapeJsonString(json: String): String {
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
}

/** Renders a candidate URL and returns stable DOM text for profile verification. */
class WebViewScraper(private val context: Context) {

    sealed class Result {
        data class Rendered(val html: String, val text: String) : Result()
        data class ChallengeDetected(val reason: String) : Result()
        data class TimedOut(val reason: String = "Render did not stabilize") : Result()
        data class Failed(val reason: String) : Result()
    }

    private val stableResolver = StableProfileApiResolver()

    suspend fun scrape(url: String): Result {
        currentCoroutineContext().ensureActive()
        if (!WebViewScraperPolicy.isAllowedUrl(url)) {
            return Result.Failed("Invalid or disallowed URL: URL must use HTTP or HTTPS scheme with a valid host")
        }
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
        currentCoroutineContext().ensureActive()
        return scrapeWithBrowser(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun scrapeWithBrowser(url: String): Result = withContext(Dispatchers.Main) {
        currentCoroutineContext().ensureActive()
        if (!WebViewScraperPolicy.isAllowedUrl(url)) {
            return@withContext Result.Failed("Invalid or disallowed URL: URL must use HTTP or HTTPS scheme with a valid host")
        }

        val rendered = CompletableDeferred<Pair<String, String>>()
        var failureReason: String? = null
        val settleStarted = AtomicBoolean(false)
        val mainFrameFinished = AtomicBoolean(false)

        val cookieManager = CookieManager.getInstance()
        val previousAcceptCookie = cookieManager.acceptCookie()
        // Public profile rendering must not participate in authenticated or
        // tracking sessions. The scrape is deliberately cookie-free even when
        // the platform page would otherwise offer a consent/login cookie. Do
        // not clear the process-global cookie jar or WebStorage here: the app's
        // separate evidence browser may legitimately own that state.
        cookieManager.setAcceptCookie(false)

        val webView = WebView(context)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            saveFormData = false
            loadsImagesAutomatically = false
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = USER_AGENT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                mainFrameFinished.set(false)
                settleStarted.set(false)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                mainFrameFinished.set(true)
                if (settleStarted.compareAndSet(false, true)) launchSettleLoop(webView, rendered)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString().orEmpty()
                if (!WebViewScraperPolicy.isAllowedNavigation(url, targetUrl)) {
                    if (!rendered.isCompleted) {
                        failureReason = "Blocked cross-host, downgraded, or non-HTTP(S) navigation"
                    }
                    return true
                }
                return false
            }

            @Deprecated("Deprecated by Android WebView")
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                failingUrl: String?
            ): Boolean {
                if (!WebViewScraperPolicy.isAllowedNavigation(url, failingUrl.orEmpty())) {
                    if (!rendered.isCompleted) {
                        failureReason = "Blocked cross-host, downgraded, or non-HTTP(S) navigation"
                    }
                    return true
                }
                return false
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
                if (!rendered.isCompleted) failureReason = "TLS error while loading profile"
            }
        }

        webView.loadUrl(url)

        try {
            kotlinx.coroutines.withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                rendered.await()
            }?.let { (html, text) -> WebViewScraperPolicy.classifyRendered(html, text) } ?: when {
                failureReason != null -> Result.Failed(failureReason!!)
                else -> Result.TimedOut()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.Failed("Render failed: ${error.localizedMessage}")
        } finally {
            if (!rendered.isCompleted) rendered.cancel()
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                @Suppress("DEPRECATION")
                webView.clearFormData()
                webView.clearSslPreferences()
                webView.clearCache(true)
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Throwable) {}
            runCatching { cookieManager.setAcceptCookie(previousAcceptCookie) }
        }
    }

    private fun launchSettleLoop(
        webView: WebView,
        rendered: CompletableDeferred<Pair<String, String>>
    ) {
        webView.postDelayed(
            { pollForStableBody(webView, rendered, attempts = 0, lastSignature = null) },
            INITIAL_SETTLE_DELAY_MS
        )
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
            val signature = WebViewScraperPolicy.unescapeJsonString(rawSignature.orEmpty())
            if (signature.isNotBlank() && signature == lastSignature) {
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
                        WebViewScraperPolicy.unescapeJsonString(htmlResult.orEmpty()) to
                            WebViewScraperPolicy.unescapeJsonString(textResult.orEmpty())
                    )
                }
            }
        }
    }

    companion object {
        const val USER_AGENT = WebViewScraperPolicy.USER_AGENT
        const val RENDER_TIMEOUT_MS = WebViewScraperPolicy.RENDER_TIMEOUT_MS
        const val INITIAL_SETTLE_DELAY_MS = WebViewScraperPolicy.INITIAL_SETTLE_DELAY_MS
        const val SETTLE_POLL_INTERVAL_MS = WebViewScraperPolicy.SETTLE_POLL_INTERVAL_MS
        const val MAX_SETTLE_POLLS = WebViewScraperPolicy.MAX_SETTLE_POLLS
        const val BODY_SIGNATURE_SCRIPT = WebViewScraperPolicy.BODY_SIGNATURE_SCRIPT
    }
}
