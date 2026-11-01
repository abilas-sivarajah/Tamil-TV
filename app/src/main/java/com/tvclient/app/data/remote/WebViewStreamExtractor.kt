package com.tvclient.app.data.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tvclient.app.TvApp
import com.tvclient.app.data.model.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Loest den verschluesselten Video-Player (z. B. playallu / JWPlayer) direkt auf dem
 * Fire TV Stick mit einer unsichtbaren Hintergrund-WebView auf.
 *
 * Sobald die .m3u8-Stream-URL abgegriffen wurde, wird die WebView sofort vollstaendig
 * beendet (destroy()), sodass waehrend der eigentlichen Filmwiedergabe im ExoPlayer
 * 0 MB RAM und 0 % CPU durch die WebView belegt werden.
 */
object WebViewStreamExtractor {

    private const val STREAM_CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 Stunden
    private const val EXTRACT_TIMEOUT_MS = 18_000L

    private val streamCache = ConcurrentHashMap<String, Pair<Long, StreamResponse>>()

    private val JS_JWPLAYER_HOOK = """
        (function() {
            if (window.__tgJwHooked) return;
            window.__tgJwHooked = true;
            var origJw = window.jwplayer;
            function wrapJw(val) {
                if (typeof val !== 'function') return val;
                var fn = function() {
                    var inst = val.apply(this, arguments);
                    if (inst && inst.setup && !inst.__tgSetupHooked) {
                        var origSetup = inst.setup;
                        inst.setup = function(cfg) {
                            try {
                                if (cfg && cfg.sources && cfg.sources.length) {
                                    for (var i = 0; i < cfg.sources.length; i++) {
                                        if (cfg.sources[i].file) {
                                            AndroidBridge.onStreamFound(cfg.sources[i].file);
                                        }
                                    }
                                }
                                if (cfg && cfg.file) {
                                    AndroidBridge.onStreamFound(cfg.file);
                                }
                            } catch (e) {}
                            return origSetup.apply(this, arguments);
                        };
                        inst.__tgSetupHooked = true;
                    }
                    return inst;
                };
                for (var k in val) {
                    try { fn[k] = val[k]; } catch (e) {}
                }
                return fn;
            }
            if (origJw) {
                window.jwplayer = wrapJw(origJw);
            } else {
                try {
                    Object.defineProperty(window, 'jwplayer', {
                        get: function() { return origJw; },
                        set: function(val) { origJw = wrapJw(val); },
                        configurable: true
                    });
                } catch (e) {}
            }
        })();
    """.trimIndent()

    fun invalidate(movieId: String) {
        streamCache.remove(movieId)
    }

    suspend fun resolveStream(movieId: String): StreamResponse {
        val now = System.currentTimeMillis()
        streamCache[movieId]?.let { (ts, cached) ->
            if (now - ts < STREAM_CACHE_TTL_MS) return cached
        }

        val pageInfo = withContext(Dispatchers.IO) {
            TamilgunScraper.fetchMoviePageInfo(movieId)
        }

        if (pageInfo.playerUrls.isEmpty()) {
            throw IllegalStateException("Kein Video-Player auf der Filmseite gefunden")
        }

        for (playerUrl in pageInfo.playerUrls) {
            val streamUrl = extractFromPlayerUrl(playerUrl)
            if (!streamUrl.isNullOrBlank()) {
                val isHls = streamUrl.contains(".m3u8", ignoreCase = true) ||
                    streamUrl.contains("/m3u8/", ignoreCase = true)
                val referer = if (playerUrl.contains("playallu", ignoreCase = true)) {
                    "https://play.playallu.xyz/"
                } else {
                    "https://tamilgun.now/"
                }

                val response = StreamResponse(
                    url = streamUrl,
                    type = if (isHls) "hls" else "mp4",
                    headers = mapOf(
                        "User-Agent" to ApiClient.DESKTOP_USER_AGENT,
                        "Referer" to referer
                    ),
                    title = pageInfo.title,
                    startPositionMs = 0L,
                    subtitles = emptyList()
                )
                streamCache[movieId] = now to response
                return response
            }
        }

        throw IllegalStateException("Stream-URL konnte nicht aus dem Player extrahiert werden")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun extractFromPlayerUrl(playerUrl: String): String? =
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val completed = AtomicBoolean(false)
                        val mainHandler = Handler(Looper.getMainLooper())

                        fun finishWith(url: String?) {
                            if (completed.compareAndSet(false, true)) {
                                mainHandler.post {
                                    try {
                                        webView?.stopLoading()
                                        webView?.loadUrl("about:blank")
                                        webView?.destroy()
                                        webView = null
                                    } catch (_: Throwable) {}
                                }
                                if (cont.isActive) {
                                    cont.resume(url)
                                }
                            }
                        }

                        val wv = WebView(TvApp.instance).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                blockNetworkImage = true // Spart massiv Bandbreite & RAM
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = ApiClient.DESKTOP_USER_AGENT
                            }

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onStreamFound(url: String?) {
                                        if (!url.isNullOrBlank() && url.startsWith("http")) {
                                            finishWith(url.trim())
                                        }
                                    }
                                },
                                "AndroidBridge"
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    view?.evaluateJavascript(JS_JWPLAYER_HOOK, null)
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    super.onLoadResource(view, url)
                                    view?.evaluateJavascript(JS_JWPLAYER_HOOK, null)
                                    if (url != null && isDirectStreamUrl(url)) {
                                        finishWith(url)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    view?.evaluateJavascript(JS_JWPLAYER_HOOK, null)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val reqUrl = request?.url?.toString() ?: return null

                                    if (isDirectStreamUrl(reqUrl)) {
                                        finishWith(reqUrl)
                                        // Den eigentlichen Video-Download in der WebView blockieren,
                                        // damit ExoPlayer sofort die volle Bandbreite bekommt!
                                        return emptyResponse()
                                    }

                                    if (shouldBlockResource(reqUrl)) {
                                        return emptyResponse()
                                    }

                                    return super.shouldInterceptRequest(view, request)
                                }

                                @SuppressLint("WebViewClientOnReceivedSslError")
                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    handler?.proceed()
                                }
                            }
                        }

                        webView = wv

                        cont.invokeOnCancellation {
                            mainHandler.post {
                                try {
                                    webView?.stopLoading()
                                    webView?.loadUrl("about:blank")
                                    webView?.destroy()
                                    webView = null
                                } catch (_: Throwable) {}
                            }
                        }

                        val headers = mapOf("Referer" to "https://tamilgun.now/")
                        wv.loadUrl(playerUrl, headers)
                    }
                }
            } finally {
                try {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.destroy()
                } catch (_: Throwable) {}
                // Sonst ruft das noch ausstehende Aufraeumen aus invokeOnCancellation
                // (Timeout) Methoden auf der bereits zerstoerten WebView auf
                webView = null
            }
        }

    private fun isDirectStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/m3u8/") ||
            (lower.contains(".mp4") && !lower.contains("sample"))
    }

    private fun shouldBlockResource(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".gif") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".woff") ||
            lower.endsWith(".woff2") ||
            lower.endsWith(".ttf") ||
            lower.contains("google-analytics") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication")
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
