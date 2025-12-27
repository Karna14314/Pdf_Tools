package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Result of HTML to PDF conversion.
 */
data class HtmlConversionResult(
    val pageCount: Int,
    val sourceType: SourceType
)

enum class SourceType {
    URL,
    HTML_STRING
}

/**
 * Handles HTML to PDF conversion using Android's WebView.
 * This is an offline solution that renders HTML content to PDF.
 * 
 * Note: This must be called from the main thread initially to set up the WebView.
 */
class HtmlToPdfConverter {
    
    /**
     * Convert a URL to PDF.
     * Must be called from Main thread.
     * 
     * @param context Android context
     * @param url URL to convert
     * @param outputStream Output stream for the PDF
     * @param pageWidth Page width in points (default A4)
     * @param pageHeight Page height in points (default A4)
     * @param onProgress Progress callback
     * @return HtmlConversionResult
     */
    suspend fun convertUrlToPdf(
        context: Context,
        url: String,
        outputStream: OutputStream,
        pageWidth: Int = 595, // A4 width in points
        pageHeight: Int = 842, // A4 height in points
        onProgress: (Float) -> Unit = {}
    ): Result<HtmlConversionResult> = withContext(Dispatchers.Main) {
        try {
            onProgress(0.1f)
            
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
            }
            
            onProgress(0.2f)
            
            // Wait for page to load
            val loadSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        if (!continuation.isCompleted) {
                            continuation.resume(true)
                        }
                    }
                    
                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (!continuation.isCompleted) {
                            continuation.resumeWithException(
                                Exception("Failed to load URL: $description")
                            )
                        }
                    }
                }
                
                webView.loadUrl(url)
                
                continuation.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            
            if (!loadSuccess) {
                return@withContext Result.failure(Exception("Failed to load URL"))
            }
            
            onProgress(0.5f)
            
            // Create print adapter and generate PDF
            val result = generatePdfFromWebView(
                webView = webView,
                outputStream = outputStream,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onProgress = { progress ->
                    onProgress(0.5f + progress * 0.5f)
                }
            )
            
            webView.destroy()
            
            result.map {
                HtmlConversionResult(
                    pageCount = it,
                    sourceType = SourceType.URL
                )
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Convert raw HTML string to PDF.
     * Must be called from Main thread.
     * 
     * @param context Android context
     * @param htmlContent Raw HTML string
     * @param outputStream Output stream for the PDF
     * @param baseUrl Optional base URL for resolving relative links
     * @param pageWidth Page width in points (default A4)
     * @param pageHeight Page height in points (default A4)
     * @param onProgress Progress callback
     * @return HtmlConversionResult
     */
    suspend fun convertHtmlToPdf(
        context: Context,
        htmlContent: String,
        outputStream: OutputStream,
        baseUrl: String? = null,
        pageWidth: Int = 595,
        pageHeight: Int = 842,
        onProgress: (Float) -> Unit = {}
    ): Result<HtmlConversionResult> = withContext(Dispatchers.Main) {
        try {
            onProgress(0.1f)
            
            val webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
            }
            
            onProgress(0.2f)
            
            // Wait for content to load
            val loadSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!continuation.isCompleted) {
                            continuation.resume(true)
                        }
                    }
                }
                
                webView.loadDataWithBaseURL(
                    baseUrl,
                    htmlContent,
                    "text/html",
                    "UTF-8",
                    null
                )
                
                continuation.invokeOnCancellation {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
            
            if (!loadSuccess) {
                return@withContext Result.failure(Exception("Failed to load HTML"))
            }
            
            onProgress(0.5f)
            
            val result = generatePdfFromWebView(
                webView = webView,
                outputStream = outputStream,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                onProgress = { progress ->
                    onProgress(0.5f + progress * 0.5f)
                }
            )
            
            webView.destroy()
            
            result.map {
                HtmlConversionResult(
                    pageCount = it,
                    sourceType = SourceType.HTML_STRING
                )
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Generate PDF from a loaded WebView using its print adapter.
     */
    private suspend fun generatePdfFromWebView(
        webView: WebView,
        outputStream: OutputStream,
        pageWidth: Int,
        pageHeight: Int,
        onProgress: (Float) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            // Use Android's PdfDocument for simpler approach
            val pdfDocument = PdfDocument()
            
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            
            // Draw the WebView content onto the page canvas
            withContext(Dispatchers.Main) {
                webView.draw(page.canvas)
            }
            
            pdfDocument.finishPage(page)
            
            onProgress(0.8f)
            
            // Write to output stream
            pdfDocument.writeTo(outputStream)
            pdfDocument.close()
            
            onProgress(1.0f)
            
            Result.success(1) // Simple single-page approach
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
