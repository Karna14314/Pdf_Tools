package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.BufferedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class PageTextData(val text: String, val positions: List<TextPosition>)

// Moved from PdfViewerScreen.kt
enum class AnnotationTool(val displayName: String) {
    NONE("Select"),
    HIGHLIGHTER("Highlighter"),
    MARKER("Marker"),
    UNDERLINE("Underline")
}

data class AnnotationStroke(
    val pageIndex: Int,
    val tool: AnnotationTool,
    val color: Color,
    val points: List<Offset>,
    val strokeWidth: Float
)

data class SearchMatch(
    val pageIndex: Int,
    val rects: List<RectF>
)

data class SearchState(
    val query: String = "",
    val matches: List<SearchMatch> = emptyList(),
    val currentMatchIndex: Int = 0,
    val isLoading: Boolean = false
)

sealed class SaveState {
    object Idle : SaveState()
    data class Saving(val progress: Float) : SaveState()
    data class Success(val uri: Uri) : SaveState()
    data class Error(val message: String) : SaveState()
}

// Sealed class for mutually exclusive tool states
sealed class PdfTool {
    object None : PdfTool()
    object Search : PdfTool()
    object Edit : PdfTool() // General Edit mode (shows annotation toolbar)
}

sealed class PdfViewerUiState {
    object Idle : PdfViewerUiState()
    object Loading : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class Loaded(val totalPages: Int) : PdfViewerUiState()
}

class PdfViewerViewModel : ViewModel() {

    companion object {
        const val RENDER_SCALE = 1.5f
    }

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toolState = MutableStateFlow<PdfTool>(PdfTool.None)
    val toolState: StateFlow<PdfTool> = _toolState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow)
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Search Cache
    private val extractedTextCache = mutableMapOf<Int, PageTextData>()

    // Search Job Control
    private var searchJob: Job? = null

    // Prefetch Job Control
    private var prefetchJob: Job? = null

    // Bitmap Cache
    // Calculate cache size: Use 1/8th of the available memory for this memory cache.
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            // The cache size will be measured in kilobytes rather than number of items.
            return bitmap.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
             // Bolt: Safety Check - Do not recycle immediately as UI might be using it.
             // Relying on GC is safer for ViewModels unless we implement a strict BitmapPool.
        }
    }

    fun loadPdf(context: Context, uri: Uri, password: String = "") {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                closeDocument() // Close existing if any

                withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open URI")

                    val doc = if (password.isNotEmpty()) {
                        PDDocument.load(inputStream, password)
                    } else {
                        PDDocument.load(inputStream)
                    }
                    // inputStream is closed by PDDocument.load usually, but check source.
                    // PDDocument.load(InputStream) reads the stream.

                    documentMutex.withLock {
                        document = doc
                        pdfRenderer = PDFRenderer(doc)
                    }

                    _uiState.value = PdfViewerUiState.Loaded(doc.numberOfPages)
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error loading PDF", e)
                _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    suspend fun loadPage(pageIndex: Int): Bitmap? {
        // Check cache first (Main Thread check is fast)
        bitmapCache.get(pageIndex)?.let { return it }

        // Bolt: Smart Prefetch - Trigger load for adjacent pages
        prefetchPages(pageIndex)

        return withContext(Dispatchers.IO) {
            // Bolt: Cancellation Check - Ensure we stop waiting if job is cancelled
            ensureActive()

            documentMutex.withLock {
                try {
                    // Double check cache inside lock
                    bitmapCache.get(pageIndex)?.let { return@withLock it }

                    val renderer = pdfRenderer ?: return@withLock null
                    // Render at 1.5x scale (approx 108 dpi) for good quality on mobile
                    val scale = RENDER_SCALE

                    // Bolt: Ensure cancellation before heavy rendering
                    ensureActive()

                    val bitmap = renderer.renderImage(pageIndex, scale)

                    if (bitmap != null) {
                        bitmapCache.put(pageIndex, bitmap)
                    }
                    bitmap
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error rendering page $pageIndex", e)
                    null
                }
            }
        }
    }

    private fun prefetchPages(currentPage: Int) {
        // Bolt: Smart Prefetch implementation
        // Only prefetch if we have a valid document
        val totalPages = (uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return

        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val range = listOf(currentPage + 1, currentPage - 1)

            for (page in range) {
                if (page in 0 until totalPages) {
                    if (bitmapCache.get(page) == null) {
                        // Try to acquire lock without blocking user interaction too much
                        // Since we are on IO, we can wait, but we yield to prioritize loadPage
                        yield()

                        try {
                            documentMutex.withLock {
                                // Double check inside lock
                                if (bitmapCache.get(page) == null) {
                                    pdfRenderer?.renderImage(page, RENDER_SCALE)?.let { bitmap ->
                                        bitmapCache.put(page, bitmap)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore prefetch errors
                        }
                    }
                }
            }
        }
    }

    fun setTool(tool: PdfTool) {
        // Bolt: Logic Conflict Fix - Ensure state cleanup on transition

        // 1. If leaving Search mode
        if (_toolState.value is PdfTool.Search && tool !is PdfTool.Search) {
            stopSearch() // Stop any active search
        }

        // 2. If entering Search mode
        if (tool is PdfTool.Search) {
            // Ensure edit tools are deactivated to prevent ghost interactions
            _selectedAnnotationTool.value = AnnotationTool.NONE
        }

        // 3. If entering Edit mode
        if (tool is PdfTool.Edit) {
            clearSearch() // Clear search results entirely
        }

        // 4. Update tool state
        _toolState.value = tool

        // 5. Reset specific annotation tool if we leave Edit mode
        if (tool !is PdfTool.Edit) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE && _toolState.value !is PdfTool.Edit) {
            setTool(PdfTool.Edit)
        }
    }

    fun setColor(color: Color) {
        _selectedColor.value = color
    }

    fun addAnnotation(stroke: AnnotationStroke) {
        val currentList = _annotations.value.toMutableList()
        currentList.add(stroke)
        _annotations.value = currentList
    }

    fun undoAnnotation() {
        val currentList = _annotations.value.toMutableList()
        if (currentList.isNotEmpty()) {
            currentList.removeAt(currentList.lastIndex)
            _annotations.value = currentList
        }
    }

    fun clearAnnotations() {
        _annotations.value = emptyList()
    }

    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        val currentState = _searchState.value
        if (currentState.isLoading) {
            _searchState.value = currentState.copy(isLoading = false)
        }
    }

    fun search(query: String) {
        // Cancel previous search
        stopSearch()

        if (query.length < 2) {
            _searchState.value = SearchState(query = query)
            return
        }

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(query = query, isLoading = true)

            val matches = mutableListOf<SearchMatch>()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    ensureActive() // Allow cancellation
                    yield()

                    try {
                        val lowerQuery = query.lowercase()

                        // Check cache first
                        var pageData = extractedTextCache[pageIndex]

                        if (pageData == null) {
                            // Extract if not cached
                            val textPositions = mutableListOf<TextPosition>()
                            val stripper = object : PDFTextStripper() {
                                override fun processTextPosition(text: TextPosition) {
                                    super.processTextPosition(text)
                                    textPositions.add(text)
                                }
                            }
                            stripper.sortByPosition = true
                            stripper.startPage = pageIndex + 1
                            stripper.endPage = pageIndex + 1

                            // This populates textPositions and returns text
                            val pageText = stripper.getText(doc).lowercase()
                            pageData = PageTextData(pageText, textPositions)
                            extractedTextCache[pageIndex] = pageData
                        }

                        if (!pageData.text.contains(lowerQuery)) {
                            continue
                        }

                        val sb = StringBuilder()
                        val positionMap = mutableListOf<Int>() // Map char index in sb to index in textPositions

                        pageData.positions.forEachIndexed { index, tp ->
                            sb.append(tp.unicode)
                            repeat(tp.unicode.length) {
                                positionMap.add(index)
                            }
                        }

                        val rawText = sb.toString().lowercase()
                        var pos = 0

                        while (true) {
                            val found = rawText.indexOf(lowerQuery, pos)
                            if (found == -1) break

                            val matchRects = mutableListOf<RectF>()

                            for (i in found until (found + lowerQuery.length)) {
                                if (i < positionMap.size) {
                                    val tpIndex = positionMap[i]
                                    val tp = pageData.positions[tpIndex]

                                    // Scale 1.5f (Matches render scale)
                                    val scale = RENDER_SCALE
                                    val x = tp.xDirAdj * scale
                                    val y = tp.yDirAdj * scale
                                    val w = tp.widthDirAdj * scale
                                    val h = tp.heightDir * scale

                                    matchRects.add(RectF(x, y, x + w, y + h))
                                }
                            }

                            if (matchRects.isNotEmpty()) {
                                matches.add(SearchMatch(pageIndex, matchRects))
                            }
                            pos = found + 1
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error searching page $pageIndex", e)
                    }
                }
            }

            _searchState.value = SearchState(
                query = query,
                matches = matches,
                isLoading = false
            )
        }
    }

    fun nextMatch() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val nextIndex = (currentState.currentMatchIndex + 1) % currentState.matches.size
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun prevMatch() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val prevIndex = if (currentState.currentMatchIndex > 0) currentState.currentMatchIndex - 1 else currentState.matches.size - 1
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        _searchState.value = SearchState()
        // Optionally keep tool state or reset it.
        // If we clear search, we likely exit search mode.
        // But maybe user just wants to clear text.
        // Screen logic handles "Close search" via setTool(PdfTool.None).
    }

    fun saveAnnotations(context: Context, outputUri: Uri) {
        val currentAnnotations = _annotations.value

        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving(0f)

            documentMutex.withLock {
                val sourceDoc = document
                if (sourceDoc == null) {
                    _saveState.value = SaveState.Error("Document is not loaded")
                    return@withLock
                }

                val destDoc = PDDocument()
                var outputStream: BufferedOutputStream? = null

                try {
                    outputStream = BufferedOutputStream(context.contentResolver.openOutputStream(outputUri))

                    val totalPages = sourceDoc.numberOfPages

                    for (pageIndex in 0 until totalPages) {
                        ensureActive() // Allow cancellation
                        yield() // Bolt: Allow UI updates

                        val pageAnnotations = currentAnnotations.filter { it.pageIndex == pageIndex }
                        val sourcePage = sourceDoc.getPage(pageIndex)
                        val rotation = sourcePage.rotation

                        if (pageAnnotations.isEmpty()) {
                            // OPTIMIZATION: Fast copy for pages without annotations
                            val importedPage = destDoc.importPage(sourcePage)
                            // PDDocument.importPage returns the imported page, which belongs to destDoc but isn't added yet
                            // We must call addPage.
                            destDoc.addPage(importedPage)
                        } else if (rotation == 0) {
                            // VECTOR INJECTION: Preserve text and vectors for upright pages
                            val importedPage = destDoc.importPage(sourcePage)
                            // importedPage is owned by destDoc, so we don't need to manually copy mediaBox from source.
                            destDoc.addPage(importedPage)

                            // Append content stream to draw on top
                            PDPageContentStream(destDoc, importedPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                val pageHeight = importedPage.mediaBox.height
                                var currentAlpha = -1f // Initialize with impossible alpha

                                pageAnnotations.forEach { annotation ->
                                    // Set color and alpha
                                    cs.setStrokingColor(annotation.color.red, annotation.color.green, annotation.color.blue)

                                    if (currentAlpha != annotation.color.alpha) {
                                        currentAlpha = annotation.color.alpha
                                        val graphicsState = PDExtendedGraphicsState()
                                        graphicsState.strokingAlphaConstant = currentAlpha
                                        cs.setGraphicsStateParameters(graphicsState)
                                    }

                                    // Set line width (scaled from Android pixels to PDF points)
                                    // Android Scale: 1.5f (108 DPI). PDF: 72 DPI.
                                    // Width_PDF = Width_Android / 1.5f
                                    val pdfStrokeWidth = annotation.strokeWidth / RENDER_SCALE
                                    cs.setLineWidth(pdfStrokeWidth)
                                    cs.setLineCapStyle(1) // Round Cap
                                    cs.setLineJoinStyle(1) // Round Join

                                    if (annotation.points.isNotEmpty()) {
                                        val first = annotation.points.first()
                                        // Coordinate Transform: Android Top-Left -> PDF Bottom-Left
                                        // X_pdf = X_android / 1.5
                                        // Y_pdf = PageHeight - (Y_android / 1.5)

                                        val startX = first.x / RENDER_SCALE
                                        val startY = pageHeight - (first.y / RENDER_SCALE)

                                        cs.moveTo(startX, startY)

                                        for (i in 1 until annotation.points.size) {
                                            val p = annotation.points[i]
                                            val px = p.x / RENDER_SCALE
                                            val py = pageHeight - (p.y / RENDER_SCALE)
                                            cs.lineTo(px, py)
                                        }
                                        cs.stroke()
                                    }
                                }
                            }
                        } else {
                            // RASTER FALLBACK: For rotated pages, use safer bitmap rasterization to guarantee alignment
                            // Render and flatten
                            val cachedBitmap = bitmapCache.get(pageIndex)
                            // Use cached bitmap copy if available, otherwise render fresh
                            // Bolt: Fix - Ensure workingBitmap is mutable for Canvas
                            val workingBitmap = if (cachedBitmap != null && !cachedBitmap.isRecycled) {
                                cachedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            } else {
                                // Render fresh and ensure mutable copy
                                val rendered = pdfRenderer?.renderImage(pageIndex, RENDER_SCALE)
                                rendered?.copy(Bitmap.Config.ARGB_8888, true).also {
                                    // Recycle the immutable rendered one if it was created just for this
                                    rendered?.recycle()
                                }
                            }

                            if (workingBitmap != null) {
                                try {
                                    val canvas = Canvas(workingBitmap)
                                    val paint = Paint().apply {
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        isAntiAlias = true
                                    }

                                    pageAnnotations.forEach { annotation ->
                                        val red = (annotation.color.red * 255).toInt()
                                        val green = (annotation.color.green * 255).toInt()
                                        val blue = (annotation.color.blue * 255).toInt()

                                        if (annotation.tool == AnnotationTool.HIGHLIGHTER) {
                                            // Keep text readable under highlights in exported PDF.
                                            paint.color = android.graphics.Color.argb(90, red, green, blue)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                paint.blendMode = BlendMode.MULTIPLY
                                            } else {
                                                @Suppress("DEPRECATION")
                                                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
                                            }
                                        } else {
                                            // Marker/underline should remain solid in exported output.
                                            paint.color = android.graphics.Color.argb(255, red, green, blue)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                paint.blendMode = null
                                            } else {
                                                @Suppress("DEPRECATION")
                                                paint.xfermode = null
                                            }
                                        }
                                        paint.strokeWidth = annotation.strokeWidth

                                        if (annotation.points.isNotEmpty()) {
                                            val path = android.graphics.Path()
                                            path.moveTo(annotation.points.first().x, annotation.points.first().y)
                                            for (i in 1 until annotation.points.size) {
                                                path.lineTo(annotation.points[i].x, annotation.points[i].y)
                                            }
                                            canvas.drawPath(path, paint)
                                        }
                                    }

                                    // Scale back to PDF points (72 DPI)
                                    // Render scale is 1.5f (approx 108 DPI)
                                    // PDF point is 1/72 inch.
                                    // 108 / 72 = 1.5.
                                    val scaleFactor = RENDER_SCALE
                                    val pageWidth = workingBitmap.width / scaleFactor
                                    val pageHeight = workingBitmap.height / scaleFactor

                                    val page = PDPage(PDRectangle(pageWidth, pageHeight))
                                    destDoc.addPage(page)

                                    val pdImage = LosslessFactory.createFromImage(destDoc, workingBitmap)
                                    PDPageContentStream(destDoc, page).use { cs ->
                                        cs.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                                    }
                                } finally {
                                    // Ensure bitmap is recycled immediately to free native memory
                                    workingBitmap.recycle()
                                }
                            }
                        }

                        // Update Progress
                        val progress = (pageIndex + 1).toFloat() / totalPages
                        _saveState.value = SaveState.Saving(progress)
                    }

                    destDoc.save(outputStream)
                    _saveState.value = SaveState.Success(outputUri)

                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error saving PDF", e)
                    _saveState.value = SaveState.Error(e.message ?: "Unknown error")
                } finally {
                    destDoc.close()
                    outputStream?.close()
                }
            }
        }
    }

    private suspend fun closeDocument() {
        documentMutex.withLock {
            try {
                document?.close()
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error closing document", e)
            } finally {
                document = null
                pdfRenderer = null
                bitmapCache.evictAll()
                extractedTextCache.clear()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            closeDocument()
        }
    }
}
