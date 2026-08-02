package io.dossier.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.dossier.app.ui.theme.NeuralTheme

/** Restricted evidence viewer. It is not a general-purpose authenticated browser. */
@Composable
fun WebBrowserScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val initialUrl = remember(url) { url.takeIf(::isAllowedWebUrl) }
    var progress by remember { mutableStateOf(0f) }
    var isLoading by remember { mutableStateOf(initialUrl != null) }
    var currentUrl by remember { mutableStateOf(initialUrl.orEmpty()) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var javaScriptEnabled by remember { mutableStateOf(false) }
    var pageError by remember {
        mutableStateOf(
            if (initialUrl == null) "Dossier blocked a non-HTTP(S) or malformed evidence link." else null
        )
    }

    fun navigateBack() {
        val webView = webViewInstance
        if (webView?.canGoBack() == true) webView.goBack() else onBack()
    }

    fun openExternally() {
        val target = currentUrl.takeIf(::isAllowedWebUrl) ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.onFailure {
            pageError = "No external browser could open this link."
        }
    }

    fun copyCurrentUrl() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Evidence URL", currentUrl))
    }

    BackHandler(onBack = ::navigateBack)

    DisposableEffect(webViewInstance) {
        val view = webViewInstance
        onDispose {
            view?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                clearFormData()
                clearCache(true)
                removeAllViews()
                destroy()
            }
        }
    }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(NeuralTheme.BackgroundStart, NeuralTheme.BackgroundEnd)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .background(NeuralTheme.CardBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::navigateBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back in browser or close evidence viewer",
                    tint = NeuralTheme.TextPrimary
                )
            }

            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = currentUrl.toDisplayHost().ifBlank { "Blocked link" },
                    color = NeuralTheme.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentUrl,
                    color = NeuralTheme.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = ::copyCurrentUrl,
                enabled = currentUrl.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy evidence URL",
                    tint = NeuralTheme.TextPrimary
                )
            }
            IconButton(
                onClick = { webViewInstance?.reload() },
                enabled = initialUrl != null
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reload evidence page",
                    tint = NeuralTheme.TextPrimary
                )
            }
            IconButton(
                onClick = ::openExternally,
                enabled = currentUrl.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Open in external browser",
                    tint = NeuralTheme.TextPrimary
                )
            }
        }

        HorizontalDivider(color = NeuralTheme.BorderColor, thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NeuralTheme.CardBackground)
                .padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Restricted evidence viewer",
                    color = NeuralTheme.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "JavaScript and persistent page storage are off by default.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 10.5.sp
                )
            }
            Text(
                text = if (javaScriptEnabled) "JavaScript on" else "JavaScript off",
                color = if (javaScriptEnabled) NeuralTheme.Amber else NeuralTheme.Emerald,
                fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Switch(
                checked = javaScriptEnabled,
                onCheckedChange = { enabled ->
                    javaScriptEnabled = enabled
                    webViewInstance?.settings?.javaScriptEnabled = enabled
                    webViewInstance?.reload()
                },
                colors = SwitchDefaults.colors(
                    checkedTrackColor = NeuralTheme.Cobalt,
                    checkedThumbColor = NeuralTheme.OnAccent,
                    uncheckedTrackColor = NeuralTheme.BorderColor,
                    uncheckedThumbColor = NeuralTheme.TextSecondary
                )
            )
        }

        if (isLoading) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = NeuralTheme.Cobalt,
                trackColor = NeuralTheme.CardBackground
            )
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }

        pageError?.let { message ->
            Text(
                text = message,
                color = NeuralTheme.Crimson,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NeuralTheme.CardBackground)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        if (initialUrl != null) {
            AndroidView(
                factory = { webContext ->
                    WebView(webContext).apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, nextUrl: String?, favicon: Bitmap?) {
                                isLoading = true
                                progress = 0.05f
                                pageError = null
                                nextUrl?.let { currentUrl = it }
                            }

                            override fun onPageFinished(view: WebView?, nextUrl: String?) {
                                isLoading = false
                                progress = 1f
                                nextUrl?.let { currentUrl = it }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val next = request?.url?.toString().orEmpty()
                                if (!isAllowedWebUrl(next)) {
                                    pageError = "Blocked navigation to a non-HTTP(S) link."
                                    return true
                                }
                                return false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    pageError = "Page load failed: ${error?.description ?: "unknown error"}"
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }

                        settings.apply {
                            javaScriptEnabled = false
                            javaScriptCanOpenWindowsAutomatically = false
                            domStorageEnabled = false
                            databaseEnabled = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            safeBrowsingEnabled = true
                            setSupportMultipleWindows(false)
                            mediaPlaybackRequiresUserGesture = true
                            cacheMode = WebSettings.LOAD_NO_CACHE
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setGeolocationEnabled(false)
                        }

                        webViewInstance = this
                        loadUrl(initialUrl)
                    }
                },
                update = { webView ->
                    if (webView.settings.javaScriptEnabled != javaScriptEnabled) {
                        webView.settings.javaScriptEnabled = javaScriptEnabled
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Return to the report and choose a valid HTTPS evidence source.",
                    color = NeuralTheme.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

private fun isAllowedWebUrl(value: String): Boolean = runCatching {
    val parsed = Uri.parse(value)
    parsed.scheme.equals("https", ignoreCase = true) ||
        parsed.scheme.equals("http", ignoreCase = true)
}.getOrDefault(false) && Uri.parse(value).host?.isNotBlank() == true

private fun String.toDisplayHost(): String = runCatching {
    Uri.parse(this).host.orEmpty().removePrefix("www.")
}.getOrDefault("")
