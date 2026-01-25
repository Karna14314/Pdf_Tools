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
                settings.domStorageEnabled = true
                settings.blockNetworkImage = false
                settings.loadsImagesAutomatically = true
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
            
            onProgress(0.4f)
            
            // Wait for JavaScript content to render (important for dynamic pages)
            kotlinx.coroutines.delay(1500)
            
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
                settings.domStorageEnabled = true
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
            
            onProgress(0.4f)
            
            // Small delay for content to render
            kotlinx.coroutines.delay(500)
            
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
     * Generate PDF from a loaded WebView.
     * Properly measures and lays out the WebView before drawing to PDF.
     */
    private suspend fun generatePdfFromWebView(
        webView: WebView,
        outputStream: OutputStream,
        pageWidth: Int,
        pageHeight: Int,
        onProgress: (Float) -> Unit
    ): Result<Int> {
        return try {
            withContext(Dispatchers.Main) {
                // Calculate dimensions - use higher resolution for better quality
                val scale = 2.0f
                val widthPx = (pageWidth * scale).toInt()
                val heightPx = (pageHeight * scale).toInt()
                
                // CRITICAL: Measure and layout the WebView before drawing
                // Without this, the WebView has 0x0 dimensions and draws nothing
                val widthMeasureSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    widthPx, android.view.View.MeasureSpec.EXACTLY
                )
                val heightMeasureSpec = android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
                
                webView.measure(widthMeasureSpec, heightMeasureSpec)
                val measuredHeight = webView.measuredHeight.coerceAtLeast(heightPx)
                webView.layout(0, 0, widthPx, measuredHeight)
                
                // Force a draw pass to ensure content is rendered
                webView.requestLayout()
                
                // Small delay to ensure layout is complete
                kotlinx.coroutines.delay(200)
                
                onProgress(0.3f)
                
                // Calculate number of pages needed
                val totalContentHeight = webView.measuredHeight
                val numPages = ((totalContentHeight.toFloat() / heightPx) + 0.5f).toInt().coerceAtLeast(1)
                
                val pdfDocument = PdfDocument()
                
                for (pageNum in 0 until numPages) {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum + 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    
                    val canvas = page.canvas
                    
                    // Scale down to fit page dimensions
                    canvas.scale(1f / scale, 1f / scale)
                    
                    // Translate to show the correct portion of the content
                    canvas.translate(0f, -(pageNum * heightPx).toFloat())
                    
                    // Draw the WebView content
                    webView.draw(canvas)
                    
                    pdfDocument.finishPage(page)
                    
                    onProgress(0.3f + (0.6f * (pageNum + 1) / numPages))
                }
                
                // Write to output stream on IO thread
                withContext(Dispatchers.IO) {
                    pdfDocument.writeTo(outputStream)
                    pdfDocument.close()
                }
                
                onProgress(1.0f)
                
                Result.success(numPages)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
