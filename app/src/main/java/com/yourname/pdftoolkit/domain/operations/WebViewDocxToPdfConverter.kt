package com.yourname.pdftoolkit.domain.operations

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewDocxToPdfConverter {

    suspend fun convertDocxToPdf(source: File, out: File, context: Context): Result<Int> {
        return try {
            withTimeout(100_000) { renderAndCapture(source, out, null, context) }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Viewer render timed out", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun convertWithWebView(
        source: File,
        out: File,
        attachedWebView: WebView?,
        context: Context
    ): Result<Int> {
        return try {
            withTimeout(100_000) { renderAndCapture(source, out, attachedWebView, context) }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Viewer render timed out", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun renderAndCapture(
        source: File,
        out: File,
        attachedWebView: WebView?,
        context: Context
    ): Result<Int> = withContext(Dispatchers.Main) {
        if (!source.exists()) {
            return@withContext Result.failure(Exception("Source file not found"))
        }
        if (source.name.endsWith(".doc", ignoreCase = true) &&
            !source.name.endsWith(".docx", ignoreCase = true)
        ) {
            return@withContext Result.failure(Exception("Legacy .doc uses offline converter"))
        }
        val base64 = withContext(Dispatchers.IO) {
            android.util.Base64.encodeToString(source.readBytes(), android.util.Base64.NO_WRAP)
        }

        val ownsWebView = attachedWebView == null
        var webView: WebView? = attachedWebView
        try {
            if (webView == null) {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                }
            }
            val wv = webView!!

            val pageCount = suspendCancellableCoroutine<Int> { continuation ->
                var hasTriggered = false

                val bridge = object {
                    @JavascriptInterface
                    fun onRenderComplete(pages: Int) {
                        if (!continuation.isCompleted && !hasTriggered) {
                            hasTriggered = true
                            continuation.resume(pages.coerceAtLeast(1))
                        }
                    }
                }
                try {
                    wv.removeJavascriptInterface("AndroidBridge")
                } catch (_: Exception) { }
                wv.addJavascriptInterface(bridge, "AndroidBridge")

                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val target = view ?: return
                        injectAndRender(target, base64)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (!continuation.isCompleted && !hasTriggered) {
                            hasTriggered = true
                            continuation.resumeWithException(
                                Exception("Viewer page failed to load: $description")
                            )
                        }
                    }
                }

                wv.loadUrl("file:///android_asset/docx_viewer/viewer.html")

                continuation.invokeOnCancellation {
                    try {
                        wv.stopLoading()
                    } catch (_: Exception) { }
                }

                // Timeout guard run on Main scope
                @Suppress("DEPRECATION")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!continuation.isCompleted && !hasTriggered) {
                        hasTriggered = true
                        continuation.resumeWithException(Exception("Conversion timed out. Please try again."))
                    }
                }, 45_000)
            }

            val printed = suspendCancellableCoroutine<Int> { continuation ->
                try {
                    val pfd = ParcelFileDescriptor.open(
                        out,
                        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                    )
                    val printAdapter = wv.createPrintDocumentAdapter("DOCX_Conversion")
                    val printAttributes = PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build()
                    android.print.PrintAdapterHelper.print(printAdapter, printAttributes, pfd) { success, error ->
                        try {
                            pfd.close()
                        } catch (_: Exception) { }
                        if (success && out.exists() && out.length() > 0) {
                            if (!continuation.isCompleted) continuation.resume(pageCount)
                        } else {
                            if (!continuation.isCompleted) {
                                continuation.resumeWithException(
                                    Exception("Conversion failed: ${error ?: "Unknown error"}")
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!continuation.isCompleted) continuation.resumeWithException(e)
                }
            }

            // Small settle so Chromium finishes flushing the PFD
            delay(200)
            Result.success(printed)
        } finally {
            if (ownsWebView) {
                try {
                    webView?.stopLoading()
                } catch (_: Exception) { }
                try {
                    webView?.removeJavascriptInterface("AndroidBridge")
                } catch (_: Exception) { }
                try {
                    webView?.destroy()
                } catch (_: Exception) { }
                webView = null
            } else {
                try {
                    webView?.removeJavascriptInterface("AndroidBridge")
                } catch (_: Exception) { }
            }
        }
    }

    private fun injectAndRender(webView: WebView, base64: String) {
        if (base64.length < 500_000) {
            webView.evaluateJavascript("renderDocxBase64('$base64', true)", null)
            return
        }
        val sb = StringBuilder("clearDocxChunks();")
        var i = 0
        while (i < base64.length) {
            val end = minOf(i + 128_000, base64.length)
            sb.append("appendDocxChunk('").append(base64.substring(i, end)).append("');")
            i = end
        }
        sb.append("renderDocxFromChunks(true)")
        webView.evaluateJavascript(sb.toString(), null)
    }
}
