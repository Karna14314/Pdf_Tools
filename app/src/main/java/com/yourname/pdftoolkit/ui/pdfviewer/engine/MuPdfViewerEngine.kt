package com.yourname.pdftoolkit.ui.pdfviewer.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MuPDF implementation for F-Droid flavor.
 * Uses reflection to load MuPDF classes for cross-flavor compatibility.
 */
class MuPdfViewerEngine(
    private val context: Context,
    private val uri: Uri
) : PdfViewerEngine {

    private var document: Any? = null // MuPDF Document (reflection)
    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0
    private var isLoaded: Boolean = false

    // Cache for rendered pages
    private val pageCache = mutableMapOf<Int, Bitmap>()

    // Reflection classes
    private val documentClass by lazy { tryLoadClass("com.artifex.mupdf.fitz.Document") }
    private val pageClass by lazy { tryLoadClass("com.artifex.mupdf.fitz.Page") }
    private val androidDrawDeviceClass by lazy { tryLoadClass("com.artifex.mupdf.fitz.android.AndroidDrawDevice") }
    private val matrixClass by lazy { tryLoadClass("com.artifex.mupdf.fitz.Matrix") }

    override fun load(uri: Uri) {
        if (documentClass == null) return

        try {
            val pfd: ParcelFileDescriptor? = context.contentResolver.openFileDescriptor(uri, "r")
            pfd?.use { descriptor ->
                val openDocumentMethod = documentClass?.getMethod("openDocument", String::class.java)
                document = openDocumentMethod?.invoke(null, ":/proc/self/fd/${descriptor.fd}")

                val countPagesMethod = documentClass?.getMethod("countPages")
                pageCount = (countPagesMethod?.invoke(document) as? Int) ?: 0
                isLoaded = pageCount > 0
            }
        } catch (e: Exception) {
            isLoaded = false
        }
    }

    @Composable
    override fun render() {
        if (!isAvailable()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        val renderedPages = remember { mutableStateListOf<Bitmap?>() }

        DisposableEffect(Unit) {
            scope.launch(Dispatchers.IO) {
                load(uri)
                // Pre-render first few pages
                for (i in 0 until kotlin.math.min(3, pageCount)) {
                    val bitmap = renderPage(i)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            renderedPages.add(bitmap)
                        }
                    }
                }
            }
            onDispose {
                cleanup()
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            items(pageCount) { index ->
                val pageBitmap = pageCache[index]
                if (pageBitmap != null && !pageBitmap.isRecycled) {
                    Image(
                        bitmap = pageBitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Render on demand
                    androidx.compose.runtime.LaunchedEffect(index) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val bitmap = renderPage(index)
                            if (bitmap != null) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    pageCache[index] = bitmap
                                }
                            }
                        }
                    }
                    // Show placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    private fun renderPage(pageIndex: Int): Bitmap? {
        return try {
            val loadPageMethod = documentClass?.getMethod("loadPage", Int::class.java)
            val page = loadPageMethod?.invoke(document, pageIndex) ?: return null

            val fitPageMethod = androidDrawDeviceClass?.getMethod(
                "fitPage",
                pageClass,
                Int::class.java,
                Int::class.java
            )
            val matrix = fitPageMethod?.invoke(null, page, 1080, 1920)

            val drawPageMethod = androidDrawDeviceClass?.getMethod(
                "drawPage",
                pageClass,
                matrixClass
            )
            val bitmap = drawPageMethod?.invoke(null, page, matrix) as? Bitmap

            // Cleanup page
            val destroyMethod = pageClass?.getMethod("destroy")
            destroyMethod?.invoke(page)

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    override fun search(query: String): List<SearchResult> {
        // MuPDF search requires PDFDocument class and search method
        // Implementation omitted for simplicity - use PdfBoxFallbackEngine for search
        return emptyList()
    }

    override fun navigateToPage(pageIndex: Int) {
        currentPageIndex = pageIndex.coerceIn(0, pageCount - 1)
    }

    override fun getCurrentPage(): Int = currentPageIndex

    override fun getPageCount(): Int = pageCount

    override fun cleanup() {
        pageCache.values.forEach { it.recycle() }
        pageCache.clear()

        // Destroy document via reflection
        document?.let { doc ->
            try {
                val destroyMethod = documentClass?.getMethod("destroy")
                destroyMethod?.invoke(doc)
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
        document = null
        isLoaded = false
    }

    override fun isAvailable(): Boolean {
        return try {
            Class.forName("com.artifex.mupdf.fitz.Document")
            isLoaded && pageCount > 0
        } catch (e: ClassNotFoundException) {
            false
        }
    }

    private fun tryLoadClass(className: String): Class<*>? {
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            null
        }
    }
}
