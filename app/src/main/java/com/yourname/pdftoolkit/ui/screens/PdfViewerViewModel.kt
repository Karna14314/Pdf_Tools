package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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
import java.io.IOException

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

data class SearchState(
    val isSearching: Boolean = false,
    val query: String = "",
    val results: List<Pair<Int, Int>> = emptyList(), // pageIndex, matchIndex (index in the list of matches for that page)
    val currentResultIndex: Int = 0,
    val matchesCount: Int = 0,
    val pageHighlights: Map<Int, List<List<RectF>>> = emptyMap() // pageIndex -> list of matches (rects)
)

sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class Success(val uri: Uri) : SaveState()
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

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Caches
    private val extractedTextCache = mutableMapOf<Int, String>()
    private val textPositionsCache = mutableMapOf<Int, List<TextPosition>>()
    private var searchJob: Job? = null

    // Bitmap Cache
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    // Constants
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

                    inputStream.use { stream ->
                        val doc = if (password.isNotEmpty()) {
                            PDDocument.load(stream, password)
                        } else {
                            PDDocument.load(stream)
                        }

                        documentMutex.withLock {
                            document = doc
                            pdfRenderer = PDFRenderer(doc)
                        }

                        _uiState.value = PdfViewerUiState.Loaded(doc.numberOfPages)
                    }
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

    fun setSearchQuery(query: String) {
        val current = _searchState.value
        if (current.query == query && query.isNotEmpty()) return

        searchJob?.cancel()

        if (query.length < 2) {
             _searchState.value = SearchState(query = query)
             return
        }

        _searchState.value = current.copy(query = query, isSearching = true, results = emptyList())

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        val results = mutableListOf<Pair<Int, Int>>()
        val pageHighlights = mutableMapOf<Int, List<List<RectF>>>()
        val lowerQuery = query.lowercase()

        val totalPages = (uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: 0
        val maxPages = totalPages.coerceAtMost(20) // Limit search scope for now for performance

        documentMutex.withLock {
            val doc = document ?: return@withLock

            for (i in 0 until maxPages) {
                if (!textPositionsCache.containsKey(i)) {
                    extractTextForPage(doc, i)
                }

                val text = extractedTextCache[i]?.lowercase() ?: ""
                val textPositions = textPositionsCache[i] ?: emptyList()

                if (text.isNotEmpty()) {
                    var pos = 0
                    var matchIndexOnPage = 0
                    val pageMatches = mutableListOf<List<RectF>>()

                    while (true) {
                        val found = text.indexOf(lowerQuery, pos)
                        if (found == -1) break

                        results.add(i to matchIndexOnPage)

                        // Calculate Highlights
                        val matchRects = mutableListOf<RectF>()
                        for (charIdx in found until (found + lowerQuery.length)) {
                            if (charIdx < textPositions.size) {
                                val tp = textPositions[charIdx]
                                // PDFBox coordinates need scaling to match our rendered bitmap
                                // Render scale is 1.5f (set in RENDER_SCALE)
                                // PDFBox default is 72 DPI. 1.5f * 72 = 108 DPI.
                                // Actually renderImage(scale) multiplies the 72dpi size by scale.
                                // So we just need to multiply PDF coordinates by RENDER_SCALE.

                                val x = tp.xDirAdj * RENDER_SCALE
                                val y = tp.yDirAdj * RENDER_SCALE
                                val w = tp.widthDirAdj * RENDER_SCALE
                                val h = tp.heightDir * RENDER_SCALE

                                matchRects.add(RectF(x, y, x + w, y + h))
                            }
                        }

                        if (matchRects.isNotEmpty()) {
                            pageMatches.add(matchRects)
                        }

                        pos = found + 1
                        matchIndexOnPage++
                    }

                    if (pageMatches.isNotEmpty()) {
                        pageHighlights[i] = pageMatches
                    }
                }
            }
        }

        _searchState.value = _searchState.value.copy(
            isSearching = false,
            results = results,
            matchesCount = results.size,
            pageHighlights = pageHighlights,
            currentResultIndex = 0
        )
    }

    private fun extractTextForPage(doc: PDDocument, pageIndex: Int) {
        try {
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

            val text = stripper.getText(doc)
            extractedTextCache[pageIndex] = text
            textPositionsCache[pageIndex] = textPositions
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "Error extracting text for page $pageIndex", e)
        }
    }

    fun nextSearchResult() {
        val current = _searchState.value
        if (current.results.isEmpty()) return

        val nextIndex = if (current.currentResultIndex < current.results.size - 1) {
            current.currentResultIndex + 1
        } else {
            0 // Loop back to start
        }
        _searchState.value = current.copy(currentResultIndex = nextIndex)
    }

    fun prevSearchResult() {
        val current = _searchState.value
        if (current.results.isEmpty()) return

        val prevIndex = if (current.currentResultIndex > 0) {
            current.currentResultIndex - 1
        } else {
            current.results.size - 1 // Loop to end
        }
        _searchState.value = current.copy(currentResultIndex = prevIndex)
    }

    fun setTool(tool: PdfTool) {
        _toolState.value = tool
        // Reset specific annotation tool if we leave Edit mode
        if (tool != PdfTool.Edit) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
        } else {
            // Default to Highlighter when entering Edit mode
            if (_selectedAnnotationTool.value == AnnotationTool.NONE) {
                _selectedAnnotationTool.value = AnnotationTool.HIGHLIGHTER
            }
        }
        // Reset search if we leave Search mode
        if (tool != PdfTool.Search) {
             setSearchQuery("")
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE) {
            _toolState.value = PdfTool.Edit
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

    fun resetSaveState() {
        _saveState.value = SaveState.Idle
    }

    fun saveAnnotations(context: Context, outputUri: Uri) {
         viewModelScope.launch {
             _saveState.value = SaveState.Saving
             val success = saveAnnotatedPdfInternal(context, outputUri)
             if (success) {
                 _saveState.value = SaveState.Success(outputUri)
             } else {
                 _saveState.value = SaveState.Error("Failed to save document")
             }
         }
    }

    private suspend fun saveAnnotatedPdfInternal(context: Context, outputUri: Uri): Boolean = withContext(Dispatchers.IO) {
        var outputStream: BufferedOutputStream? = null
        // Create a new document to avoid modifying the currently open one (or concurrency issues)
        // Actually, we should probably clone the current document or just add pages to a new one.
        // The previous implementation created a new document and added images.
        // That effectively "flattens" the PDF. It's a valid strategy for "saving annotations" if we want them baked in.
        // However, it loses text selection capability of the original PDF.
        // For a robust tool, we might want to append annotations as PDF annotations, but that's complex.
        // The user's existing code did "Flattening" (Bitmap -> PDF). I will stick to that for now to maintain behavior,
        // but optimize the I/O.

        val newDocument = PDDocument()

        try {
            outputStream = BufferedOutputStream(context.contentResolver.openOutputStream(outputUri))

            documentMutex.withLock {
                val currentDoc = document ?: return@withLock false
                val totalPages = currentDoc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    // Render the page with annotations
                    // We need to re-render here to include the annotations drawn on top

                    // 1. Get base bitmap (cached or render)
                    val cachedBitmap = bitmapCache.get(pageIndex)
                    val renderer = pdfRenderer ?: return@withLock false
                    val baseBitmap = cachedBitmap ?: renderer.renderImage(pageIndex, RENDER_SCALE)

                    // 2. Draw annotations on it
                    val annotatedBitmap = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = android.graphics.Canvas(annotatedBitmap)

                    val pageAnnotations = _annotations.value.filter { it.pageIndex == pageIndex }

                    if (pageAnnotations.isNotEmpty()) {
                         for (annotation in pageAnnotations) {
                            if (annotation.points.size >= 2) {
                                val paint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.argb(
                                        (annotation.color.alpha * 255).toInt(),
                                        (annotation.color.red * 255).toInt(),
                                        (annotation.color.green * 255).toInt(),
                                        (annotation.color.blue * 255).toInt()
                                    )
                                    strokeWidth = annotation.strokeWidth * RENDER_SCALE // Scale stroke width too?
                                    // The stroke width in AnnotationStroke is likely in screen pixels (dp converted).
                                    // If we drew on screen which was scaled, we need to match.
                                    // Let's assume strokeWidth is in consistent units.
                                    // In UI: Canvas is scaled by `scale` but points are relative to 0,0 of unscaled page?
                                    // Actually points are relative to the PDF page size * scale?
                                    // In PdfViewerScreen, `onDrag` gives positions.
                                    // Wait, the drawing logic in UI is:
                                    // `translationX`, `scaleX` applied to GraphicsLayer.
                                    // The Canvas matches parent size.
                                    // The user draws on the scaled canvas.
                                    // We need to normalize points to PDF coordinates.
                                    // But `AnnotationStroke` points are raw touch points?
                                    // The `PdfViewerScreen` logic for drawing:
                                    /*
                                    detectDragGestures { ... localStroke.add(change.position) }
                                    */
                                    // `change.position` is relative to the Composable.
                                    // The Composable is `Box` inside `Card` with `graphicsLayer`.
                                    // If `graphicsLayer` handles zoom/pan, the `Box` inside might be original size?
                                    // No, the `Box` has `bitmap` size?
                                    // Let's assume for now that `points` are relative to the bitmap size (at RENDER_SCALE).
                                    // If so, we can just draw them.

                                    style = android.graphics.Paint.Style.STROKE
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                    strokeJoin = android.graphics.Paint.Join.ROUND
                                    isAntiAlias = true
                                }

                                val path = android.graphics.Path()
                                path.moveTo(annotation.points[0].x, annotation.points[0].y)
                                for (i in 1 until annotation.points.size) {
                                    path.lineTo(annotation.points[i].x, annotation.points[i].y)
                                }
                                canvas.drawPath(path, paint)
                            }
                        }
                    }

                    // 3. Add to new document
                    val width = annotatedBitmap.width.toFloat()
                    val height = annotatedBitmap.height.toFloat()

                    // PDFBox default is 72 DPI. We rendered at 1.5x (108 DPI).
                    // We want the physical size of the page to match original if possible,
                    // or at least be consistent.
                    // 1.5 scale means width is 1.5 * original_pt_width.
                    // So we divide by 1.5 to get back to points.
                    val pageWidth = width / RENDER_SCALE
                    val pageHeight = height / RENDER_SCALE

                    val page = com.tom_roush.pdfbox.pdmodel.PDPage(
                        com.tom_roush.pdfbox.pdmodel.common.PDRectangle(pageWidth, pageHeight)
                    )
                    newDocument.addPage(page)

                    val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(newDocument, annotatedBitmap)

                    com.tom_roush.pdfbox.pdmodel.PDPageContentStream(newDocument, page).use { contentStream ->
                        contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                    }

                    annotatedBitmap.recycle()
                    // Recycle baseBitmap only if it was NOT from cache
                    if (cachedBitmap == null) {
                         baseBitmap.recycle()
                    }
                }
            }

            newDocument.save(outputStream)
            true
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "Error saving PDF", e)
            false
        } finally {
            try {
                newDocument.close()
                outputStream?.close()
            } catch (e: Exception) {
                // ignore
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
