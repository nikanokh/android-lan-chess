package com.example.core.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import javax.inject.Inject

/**
 * Android WebView-backed UCI transport running the bundled Stockfish WASM / asm.js Web Worker
 * offline from `assets/stockfish/`.
 */
class WebViewStockfishTransport @Inject constructor(
    @ApplicationContext private val context: Context
) : UciEngineTransport {

    private val _outputLines = MutableSharedFlow<String>(extraBufferCapacity = 512)
    override val outputLines: SharedFlow<String> = _outputLines.asSharedFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 32)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var webView: WebView? = null
    private var pageLoadedDeferred: CompletableDeferred<Unit>? = null

    private inner class Bridge {
        @JavascriptInterface
        fun onUciOutput(line: String?) {
            if (!line.isNullOrBlank()) {
                _outputLines.tryEmit(line)
            }
        }

        @JavascriptInterface
        fun onEngineError(message: String?) {
            _errors.tryEmit(message ?: "Stockfish WebView error")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun start() {
        val alreadyRunning = withContext(Dispatchers.Main) { webView != null }
        if (alreadyRunning) return

        val loaded = CompletableDeferred<Unit>()
        pageLoadedDeferred = loaded

        withContext(Dispatchers.Main) {
            val wv = WebView(context.applicationContext)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            wv.addJavascriptInterface(Bridge(), "AndroidStockfishBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    if (uri.host != ASSET_HOST) return null
                    val path = uri.path?.removePrefix("/") ?: return null
                    if (!path.startsWith("assets/")) return null
                    val assetPath = path.removePrefix("assets/")
                    return serveLocalAsset(assetPath)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!loaded.isCompleted) {
                        loaded.complete(Unit)
                    }
                }
            }
            webView = wv
            wv.loadUrl("https://$ASSET_HOST/assets/stockfish/stockfish.html")
        }

        loaded.await()
    }

    private fun serveLocalAsset(assetPath: String): WebResourceResponse? {
        return try {
            val stream = context.assets.open(assetPath)
            val mimeType = when {
                assetPath.endsWith(".html", ignoreCase = true) -> "text/html"
                assetPath.endsWith(".js", ignoreCase = true) -> "application/javascript"
                assetPath.endsWith(".wasm", ignoreCase = true) -> "application/wasm"
                else -> "application/octet-stream"
            }
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store"
            )
            WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream)
        } catch (_: Exception) {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    override suspend fun sendCommand(command: String) {
        val quoted = JSONObject.quote(command)
        withContext(Dispatchers.Main) {
            val wv = webView ?: throw IllegalStateException("Stockfish WebView is not initialized")
            wv.evaluateJavascript("window.sendUci($quoted);", null)
        }
    }

    override suspend fun restart() {
        withContext(Dispatchers.Main) {
            destroyWebViewOnMain()
        }
        start()
    }

    override fun close() {
        pageLoadedDeferred?.cancel()
        pageLoadedDeferred = null
        val wv = webView ?: return
        webView = null
        wv.post {
            try {
                wv.evaluateJavascript("window.terminateStockfish && window.terminateStockfish();", null)
                wv.removeJavascriptInterface("AndroidStockfishBridge")
                wv.stopLoading()
                wv.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun destroyWebViewOnMain() {
        pageLoadedDeferred?.cancel()
        pageLoadedDeferred = null
        val wv = webView ?: return
        webView = null
        try {
            wv.evaluateJavascript("window.terminateStockfish && window.terminateStockfish();", null)
            wv.removeJavascriptInterface("AndroidStockfishBridge")
            wv.stopLoading()
            wv.destroy()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val ASSET_HOST = "appassets.androidplatform.net"
    }
}
