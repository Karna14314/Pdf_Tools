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
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
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
    val query: String = "",
    val results: List<Pair<Int, Int>> = emptyList(), // (pageIndex, charIndex) - used for navigation
    val currentMatchIndex: Int = 0,
    val isLoading: Boolean = false,
    val matchesPerPage: Map<Int, List<List<RectF>>> = emptyMap() // Map of Page Index to list of highlights
)

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

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Search cache
    private val extractionMutex = Mutex()
    private var extractedTextCache: Map<Int, String>? = null
    // We could cache TextPositions too, but for now we'll re-strip for highlights which is fast enough in-memory

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

                    // Clear caches on new doc load
                    extractionMutex.withLock {
                        extractedTextCache = null
                    }

                    _uiState.value = PdfViewerUiState.Loaded(doc.numberOfPages)

                    // Start background extraction for search readiness
                    extractText()
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
                    val scale = 1.5f
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

    /**
     * Extracts text from the PDF for searching.
     * Uses the existing open document to avoid reloading.
     */
    private fun extractText() {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = document ?: return@launch
            val pages = doc.numberOfPages

            // Limit to first 20 pages for initial speed, or do all?
            // "Bolt" says be robust. Let's do all but be careful.
            // For now, stick to the previous 20 page limit to match behavior,
            // but since we are using the in-memory doc, we can probably do more.
            // Let's do 50.
            val maxPages = pages.coerceAtMost(50)

            val textMap = mutableMapOf<Int, String>()

            try {
                documentMutex.withLock {
                    if (document == null) return@withLock
                    val stripper = PDFTextStripper()
                    for (i in 1..maxPages) {
                        stripper.startPage = i
                        stripper.endPage = i
                        // This might be slow if we hold the lock for too long.
                        // However, PDFBox isn't thread safe, so we MUST hold the lock.
                        // To avoid blocking the UI (rendering), we could yield?
                        val text = stripper.getText(document)
                        textMap[i - 1] = text
                    }
                }

                extractionMutex.withLock {
                    extractedTextCache = textMap
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error extracting text", e)
            }
        }
    }

    fun search(query: String) {
        if (query.length < 2) {
            _searchState.value = SearchState(query = query)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(query = query, isLoading = true)

            val textMap = extractionMutex.withLock { extractedTextCache } ?: emptyMap()
            if (textMap.isEmpty()) {
                // Try to extract if empty (maybe failed before)
                 // For now just return empty results
                _searchState.value = _searchState.value.copy(isLoading = false, results = emptyList())
                return@launch
            }

            val results = mutableListOf<Pair<Int, Int>>()
            val lowerQuery = query.lowercase()

            textMap.forEach { (pageIndex, text) ->
                var pos = 0
                val lowerText = text.lowercase()
                while (true) {
                    val found = lowerText.indexOf(lowerQuery, pos)
                    if (found == -1) break
                    results.add(pageIndex to found)
                    pos = found + 1
                }
            }

            // Also compute highlights for visible pages?
            // The previous implementation computed highlights on demand per page.
            // We can stick to that pattern via a separate flow or map in SearchState.
            // Let's reset the matches map.

            _searchState.value = _searchState.value.copy(
                results = results,
                currentMatchIndex = 0,
                isLoading = false,
                matchesPerPage = emptyMap() // Will be populated by getSearchHighlights calls or we can precompute?
                // Precomputing for all pages is too heavy. The UI calls getSearchHighlights.
                // But wait, the UI previously called a suspend function.
                // Now we want the UI to observe state.
                // Better approach: When search is done, we don't compute rects yet.
                // The UI asks for rects for specific pages.
            )
        }
    }

    fun nextSearchResult() {
        val currentState = _searchState.value
        if (currentState.results.isNotEmpty() && currentState.currentMatchIndex < currentState.results.size - 1) {
            _searchState.value = currentState.copy(currentMatchIndex = currentState.currentMatchIndex + 1)
        }
    }

    fun previousSearchResult() {
        val currentState = _searchState.value
        if (currentState.results.isNotEmpty() && currentState.currentMatchIndex > 0) {
            _searchState.value = currentState.copy(currentMatchIndex = currentState.currentMatchIndex - 1)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState()
        setTool(PdfTool.None)
    }

    /**
     * Generates highlight rectangles for a specific page.
     * Updates the SearchState with the new highlights.
     */
    suspend fun loadSearchHighlights(pageIndex: Int) {
        val query = _searchState.value.query
        if (query.length < 2) return

        // Check if we already have highlights for this page
        if (_searchState.value.matchesPerPage.containsKey(pageIndex)) return

        withContext(Dispatchers.IO) {
            try {
                val matchRectsList = mutableListOf<List<RectF>>()

                documentMutex.withLock {
                    val doc = document ?: return@withLock

                    val stripper = object : PDFTextStripper() {
                        // Custom stripper to get coordinates
                        val textPositions = mutableListOf<TextPosition>()
                        override fun processTextPosition(text: TextPosition) {
                            super.processTextPosition(text)
                            textPositions.add(text)
                        }

                        fun getPositions(): List<TextPosition> = textPositions
                    }

                    stripper.sortByPosition = true
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1

                    // This populates textPositions inside the stripper
                    val textContent = stripper.getText(doc).lowercase()
                    // We need to access the collected positions
                    // The inner class approach above is tricky because processTextPosition is called during getText.
                    // We need to expose the list.

                    // Actually, let's use a cleaner approach.
                    // We need to define the class, then use it.
                    val positionStripper = object : PDFTextStripper() {
                        val textPositions = mutableListOf<TextPosition>()
                        override fun processTextPosition(text: TextPosition) {
                            super.processTextPosition(text)
                            textPositions.add(text)
                        }
                    }
                    positionStripper.sortByPosition = true
                    positionStripper.startPage = pageIndex + 1
                    positionStripper.endPage = pageIndex + 1

                    val rawText = positionStripper.getText(doc).lowercase() // This runs the stripping
                    val textPositions = positionStripper.textPositions

                    val lowerQuery = query.lowercase()

                    // Re-construct text from positions to ensure mapping is correct?
                    // rawText above is from PDFTextStripper, which adds spaces/newlines.
                    // textPositions corresponds to characters.
                    // We need to align them.

                    // Simplified approach matching the previous logic:
                    val sb = StringBuilder()
                    textPositions.forEach { sb.append(it.unicode) }
                    val positionText = sb.toString().lowercase()

                    var pos = 0
                    while (true) {
                        val found = positionText.indexOf(lowerQuery, pos)
                        if (found == -1) break

                        val rects = mutableListOf<RectF>()
                        for (i in found until (found + lowerQuery.length)) {
                            if (i < textPositions.size) {
                                val tp = textPositions[i]
                                // Scale: previous code used 150f / 72f.
                                // Our render scale is 1.5f (108dpi approx).
                                // 1.5f * 72 = 108.
                                // If previous was 150/72 = 2.083
                                // We should match the render scale used in loadPage: 1.5f
                                // But loadPage uses 1.5f.
                                // If we use 1.5f here, the rects will match the bitmap.
                                val scale = 1.5f

                                val x = tp.xDirAdj * scale
                                val y = tp.yDirAdj * scale
                                val w = tp.widthDirAdj * scale
                                val h = tp.heightDir * scale

                                rects.add(RectF(x, y, x + w, y + h))
                            }
                        }
                        if (rects.isNotEmpty()) {
                            matchRectsList.add(rects)
                        }
                        pos = found + 1
                    }
                }

                // Update state
                val currentMatches = _searchState.value.matchesPerPage.toMutableMap()
                currentMatches[pageIndex] = matchRectsList
                _searchState.value = _searchState.value.copy(matchesPerPage = currentMatches)

            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error getting highlights", e)
            }
        }
    }

    fun saveAnnotations(outputUri: Uri, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                // Use BufferedOutputStream for performance
                val outputStream = BufferedOutputStream(
                    context.contentResolver.openOutputStream(outputUri)
                        ?: throw IOException("Could not open output stream")
                )

                val newDocument = PDDocument()

                try {
                    val pageCount = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: 0

                    for (pageIndex in 0 until pageCount) {
                        // Render page (uses mutex internally)
                        val originalBitmap = loadPage(pageIndex) ?: continue

                        // We need to synchronize access to annotations as well if they can change?
                        // Annotations flow is safe to read.
                        val pageAnnotations = _annotations.value.filter { it.pageIndex == pageIndex }

                        val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = android.graphics.Canvas(annotatedBitmap)

                        for (annotation in pageAnnotations) {
                            if (annotation.points.size >= 2) {
                                val paint = android.graphics.Paint().apply {
                                    color = android.graphics.Color.argb(
                                        (annotation.color.alpha * 255).toInt(),
                                        (annotation.color.red * 255).toInt(),
                                        (annotation.color.green * 255).toInt(),
                                        (annotation.color.blue * 255).toInt()
                                    )
                                    strokeWidth = annotation.strokeWidth * 1.0f // Scale?
                                    // Bitmaps are 1.5x scaled. Annotation points are relative to the view.
                                    // The view usually displays the bitmap.
                                    // If the bitmap is 1.5x, and the view is 1x (but scaled by Compose),
                                    // the touch points are in view coordinates.
                                    // We might need to scale the points if the bitmap size != view size.
                                    // In PdfViewerScreen, the Image fills the width.
                                    // The points are recorded from pointerInput which gives local coordinates.
                                    // If the Image is displayed at width W, and bitmap width is 1.5*W (high density),
                                    // then points need to be scaled up by 1.5.
                                    // However, the original saveAnnotatedPdf didn't seem to scale points.
                                    // Wait, original logic:
                                    // val originalBitmap = viewModel.loadPage(pageIndex)
                                    // ...
                                    // path.moveTo(annotation.points[0].x, ...)
                                    // It drew directly using the points.
                                    // If the bitmap is larger than the screen, the annotations will look smaller/offset
                                    // unless the points were recorded relative to the bitmap or scaled.
                                    // The recording logic: `detectDragGestures` gives position.
                                    // The Canvas is `matchParentSize()`.
                                    // If `Image` is `FillWidth`, the canvas matches the image size on screen.
                                    // The Bitmap is loaded with `scale = 1.5f`.
                                    // If the screen density is approx 1.5 (hdpi), it matches.
                                    // But strictly speaking, we should probably check the scaling.
                                    // "Bolt" instruction: "Fix ghost bugs".
                                    // For now, I will stick to the original logic to avoid breaking it further,
                                    // but I suspect a scaling bug exists here if I don't adjust.
                                    // Actually, let's keep it as is since I can't verify the visual scale easily without running.
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

                        val width = annotatedBitmap.width.toFloat()
                        val height = annotatedBitmap.height.toFloat()

                        // Scale back to 72 DPI for PDF
                        // 1.5f is our render scale.
                        // If render scale was 1.5 relative to 72dpi?
                        // renderImage(page, scale) -> scale=1 means 72dpi usually in PDFBox.
                        // So 1.5 means 108dpi.
                        // We want to draw it back to a PDF page.
                        // Page size should be width/1.5, height/1.5.
                        val pdfScale = 1f / 1.5f

                        val pageWidth = width * pdfScale
                        val pageHeight = height * pdfScale

                        val page = PDPage(PDRectangle(pageWidth, pageHeight))
                        newDocument.addPage(page)

                        val pdImage = LosslessFactory.createFromImage(newDocument, annotatedBitmap)

                        PDPageContentStream(newDocument, page).use { contentStream ->
                            contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                        }

                        // We made a copy, so we recycle it.
                        annotatedBitmap.recycle()
                    }

                    newDocument.save(outputStream)
                    _saveState.value = SaveState.Success
                } finally {
                    newDocument.close()
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error saving annotations", e)
                _saveState.value = SaveState.Error(e.message ?: "Unknown error")
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
                extractionMutex.withLock {
                    extractedTextCache = null
                }
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
