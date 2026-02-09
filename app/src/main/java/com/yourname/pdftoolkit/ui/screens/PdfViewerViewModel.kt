package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
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
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream

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

data class SearchMatch(
    val pageIndex: Int,
    val text: String,
    val rects: List<RectF>
)

sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Results(
        val query: String,
        val matches: List<Pair<Int, Int>>, // pageIndex, matchIndex
        val currentMatchIndex: Int = 0
    ) : SearchState()
    object Empty : SearchState()
}

sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    object Success : SaveState()
    data class Error(val message: String) : SaveState()
}

class PdfViewerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toolState = MutableStateFlow<PdfTool>(PdfTool.None)
    val toolState: StateFlow<PdfTool> = _toolState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow.copy(alpha = 0.5f))
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Caches for search optimization
    private val extractedTextCache = mutableMapOf<Int, String>()
    private val textPositionsCache = mutableMapOf<Int, List<TextPosition>>()

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
             // Recycle bitmap if evicted to free native memory?
             // Android Bitmaps on newer versions (Honeycomb+) are managed by Dalvik/ART,
             // but recycling can still help with large images.
             // However, reusing them via an object pool would be better than recycling if we re-render.
             // For simplicity and safety against reusing recycled bitmaps, we won't manually recycle here
             // immediately unless we are sure it's not used.
             // Relying on GC is safer for ViewModels.
        }
    }

    private var searchJob: Job? = null
    private val RENDER_SCALE = 1.5f

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
        // Check cache first
        bitmapCache.get(pageIndex)?.let { return it }

        return withContext(Dispatchers.IO) {
            documentMutex.withLock {
                try {
                    val renderer = pdfRenderer ?: return@withLock null
                    // Render at 1.5x scale (approx 108 dpi) for good quality on mobile
                    val bitmap = renderer.renderImage(pageIndex, RENDER_SCALE)

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

    fun setTool(tool: PdfTool) {
        // Reset Search State if leaving search
        if (_toolState.value == PdfTool.Search && tool != PdfTool.Search) {
            _searchState.value = SearchState.Idle
            searchJob?.cancel()
        }

        _toolState.value = tool
        // Reset specific annotation tool if we leave Edit mode
        if (tool != PdfTool.Edit) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
            // Reset save state when leaving edit mode (optional, but good for cleanup)
            if (_saveState.value is SaveState.Success || _saveState.value is SaveState.Error) {
                _saveState.value = SaveState.Idle
            }
        } else {
            // Default to Highlighter when entering Edit mode
            if (_selectedAnnotationTool.value == AnnotationTool.NONE) {
                _selectedAnnotationTool.value = AnnotationTool.HIGHLIGHTER
            }
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE) {
            // Use setTool to ensure proper state transitions (e.g. clearing search)
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

    // Search Functionality
    fun performSearch(query: String) {
        if (query.length < 2) {
            _searchState.value = SearchState.Idle
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.Loading
            val results = mutableListOf<Pair<Int, Int>>()
            val lowerQuery = query.lowercase()

            val totalPages = (uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: 0
            if (totalPages == 0) return@launch

            // Limit search to first 50 pages for performance if needed, or search all
            val maxPages = totalPages.coerceAtMost(50)

            for (i in 0 until maxPages) {
                val text = getPageText(i)
                if (text.isNotEmpty()) {
                    val lowerText = text.lowercase()
                    var pos = 0
                    while (true) {
                        val found = lowerText.indexOf(lowerQuery, pos)
                        if (found == -1) break
                        results.add(i to found)
                        pos = found + 1
                    }
                }
            }

            if (results.isNotEmpty()) {
                _searchState.value = SearchState.Results(query, results)
            } else {
                _searchState.value = SearchState.Empty
            }
        }
    }

    fun nextSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            val nextIndex = (currentState.currentMatchIndex + 1) % currentState.matches.size
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun previousSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
             val prevIndex = if (currentState.currentMatchIndex - 1 < 0)
                 currentState.matches.size - 1
             else currentState.currentMatchIndex - 1
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState.Idle
        searchJob?.cancel()
    }

    private suspend fun getPageText(pageIndex: Int): String {
        return documentMutex.withLock {
            if (extractedTextCache.containsKey(pageIndex)) {
                return@withLock extractedTextCache[pageIndex] ?: ""
            }

            val doc = document ?: return@withLock ""
            try {
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(doc)
                extractedTextCache[pageIndex] = text
                text
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error extracting text page $pageIndex", e)
                ""
            }
        }
    }

    suspend fun getSearchHighlights(pageIndex: Int, query: String): List<List<RectF>> {
        if (query.length < 2) return emptyList()

        return withContext(Dispatchers.IO) {
            documentMutex.withLock {
                val doc = document ?: return@withLock emptyList<List<RectF>>()

                // Get or compute text positions
                val textPositions = if (textPositionsCache.containsKey(pageIndex)) {
                     textPositionsCache[pageIndex]!!
                } else {
                    val tPositions = mutableListOf<TextPosition>()
                    val stripper = object : PDFTextStripper() {
                        override fun processTextPosition(text: TextPosition) {
                            super.processTextPosition(text)
                            tPositions.add(text)
                        }
                    }
                    stripper.sortByPosition = true
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    try {
                        stripper.getText(doc) // This triggers processTextPosition
                        textPositionsCache[pageIndex] = tPositions
                        tPositions
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error getting highlights page $pageIndex", e)
                        emptyList()
                    }
                }

                // Find matches in text positions
                val allMatches = mutableListOf<List<RectF>>()
                val lowerQuery = query.lowercase()

                val sb = StringBuilder()
                textPositions.forEach { sb.append(it.unicode) }
                val rawText = sb.toString().lowercase()

                var pos = 0
                while (true) {
                    val found = rawText.indexOf(lowerQuery, pos)
                    if (found == -1) break

                    val matchRects = mutableListOf<RectF>()

                    for (i in found until (found + lowerQuery.length)) {
                        if (i < textPositions.size) {
                            val tp = textPositions[i]
                            val scale = RENDER_SCALE

                            val x = tp.xDirAdj * scale
                            val y = tp.yDirAdj * scale
                            val w = tp.widthDirAdj * scale
                            val h = tp.heightDir * scale

                            matchRects.add(RectF(x, y, x + w, y + h))
                        }
                    }

                    if (matchRects.isNotEmpty()) {
                        allMatches.add(matchRects)
                    }

                    pos = found + 1
                }
                allMatches
            }
        }
    }

    // Save Functionality
    fun saveAnnotations(context: Context, outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving
            try {
                documentMutex.withLock {
                    val originalDoc = document ?: throw IllegalStateException("Document not loaded")
                    val newDoc = PDDocument()

                    try {
                        for (i in 0 until originalDoc.numberOfPages) {
                            val pageAnnotations = _annotations.value.filter { it.pageIndex == i }

                            if (pageAnnotations.isEmpty()) {
                                // Optimization: Import original page directly (vector preservation)
                                val page = originalDoc.getPage(i)
                                newDoc.importPage(page)
                            } else {
                                // Rasterize and flatten annotations
                                val renderer = pdfRenderer ?: throw IllegalStateException("Renderer not ready")

                                // Check cache first or render
                                // Note: We cannot call loadPage here because it suspends and uses mutex (re-entrant lock is not supported by kotlinx Mutex?)
                                // Mutex is NOT re-entrant. So we must not call loadPage.
                                // Access cache directly.
                                var bitmap = bitmapCache.get(i)
                                if (bitmap == null) {
                                    bitmap = renderer.renderImage(i, RENDER_SCALE)
                                }

                                val annotatedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                val canvas = android.graphics.Canvas(annotatedBitmap)

                                // Draw annotations
                                for (annotation in pageAnnotations) {
                                    if (annotation.points.size >= 2) {
                                        val paint = Paint().apply {
                                            color = android.graphics.Color.argb(
                                                (annotation.color.alpha * 255).toInt(),
                                                (annotation.color.red * 255).toInt(),
                                                (annotation.color.green * 255).toInt(),
                                                (annotation.color.blue * 255).toInt()
                                            )
                                            strokeWidth = annotation.strokeWidth
                                            style = Paint.Style.STROKE
                                            strokeCap = Paint.Cap.ROUND
                                            strokeJoin = Paint.Join.ROUND
                                            isAntiAlias = true
                                        }

                                        val path = Path()
                                        path.moveTo(annotation.points[0].x, annotation.points[0].y)
                                        for (pIndex in 1 until annotation.points.size) {
                                            path.lineTo(annotation.points[pIndex].x, annotation.points[pIndex].y)
                                        }
                                        canvas.drawPath(path, paint)
                                    }
                                }

                                // Create PDF page from bitmap
                                val width = annotatedBitmap.width.toFloat()
                                val height = annotatedBitmap.height.toFloat()

                                // Calculate dimensions to match original PDF point size if possible, or just use bitmap size scaled down
                                // Original logic used 72/150 factor. Here we use RENDER_SCALE (1.5).
                                // 1.5 scale means 108 DPI. To get back to 72 DPI (PDF default user space), we divide by 1.5.
                                val pdfWidth = width / RENDER_SCALE
                                val pdfHeight = height / RENDER_SCALE

                                val page = PDPage(PDRectangle(pdfWidth, pdfHeight))
                                newDoc.addPage(page)

                                val pdImage = LosslessFactory.createFromImage(newDoc, annotatedBitmap)

                                PDPageContentStream(newDoc, page).use { contentStream ->
                                    contentStream.drawImage(pdImage, 0f, 0f, pdfWidth, pdfHeight)
                                }

                                annotatedBitmap.recycle()
                            }
                        }

                        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                            BufferedOutputStream(outputStream).use { bufferedStream ->
                                newDoc.save(bufferedStream)
                            }
                        }

                        _saveState.value = SaveState.Success
                    } finally {
                        newDoc.close()
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error saving PDF", e)
                _saveState.value = SaveState.Error(e.message ?: "Failed to save PDF")
            }
        }
    }

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
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
                textPositionsCache.clear()
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
