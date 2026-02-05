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
    val matches: List<Pair<Int, Int>> = emptyList(), // pageIndex, charIndex
    val currentMatchIndex: Int = -1
)

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

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()
    private val extractionMutex = Mutex()
    private val extractedTextCache = mutableMapOf<Int, String>()

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

    fun search(query: String) {
        val currentQuery = query.trim()
        if (currentQuery.length < 2) {
            _searchState.value = SearchState(isSearching = false, query = currentQuery)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = _searchState.value.copy(isSearching = true, query = currentQuery)

            extractText()

            val matches = mutableListOf<Pair<Int, Int>>()
            val lowerQuery = currentQuery.lowercase()

            extractedTextCache.forEach { (pageIndex, text) ->
                var pos = 0
                val lowerText = text.lowercase()
                while (true) {
                    val found = lowerText.indexOf(lowerQuery, pos)
                    if (found == -1) break
                    matches.add(pageIndex to found)
                    pos = found + 1
                }
            }

            _searchState.value = SearchState(
                isSearching = false,
                query = currentQuery,
                matches = matches,
                currentMatchIndex = if (matches.isNotEmpty()) 0 else -1
            )
        }
    }

    fun nextSearchResult() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val nextIndex = (currentState.currentMatchIndex + 1) % currentState.matches.size
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun previousSearchResult() {
        val currentState = _searchState.value
        if (currentState.matches.isNotEmpty()) {
            val prevIndex = if (currentState.currentMatchIndex - 1 < 0) currentState.matches.size - 1 else currentState.currentMatchIndex - 1
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState()
    }

    suspend fun getSearchHighlights(pageIndex: Int): List<List<RectF>> = withContext(Dispatchers.IO) {
        val query = _searchState.value.query
        if (query.length < 2) return@withContext emptyList()

        documentMutex.withLock {
            val doc = document ?: return@withLock emptyList()
            val allMatches = mutableListOf<List<RectF>>()

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
                stripper.getText(doc)

                val sb = StringBuilder()
                textPositions.forEach { sb.append(it.unicode) }
                val rawText = sb.toString().lowercase()
                val lowerQuery = query.lowercase()

                var pos = 0
                while (true) {
                    val found = rawText.indexOf(lowerQuery, pos)
                    if (found == -1) break

                    val matchRects = mutableListOf<RectF>()
                    for (i in found until (found + lowerQuery.length)) {
                        if (i < textPositions.size) {
                            val tp = textPositions[i]
                            // Scale matches to match render scale (1.5f)
                            val scale = 1.5f
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

            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error getting highlights", e)
            }
            allMatches
        }
    }

    suspend fun saveAnnotations(context: Context, outputUri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Ensure document exists
        val totalPages = documentMutex.withLock { document?.numberOfPages } ?: return@withContext false
        val annotationsList = _annotations.value

        try {
            val newDocument = PDDocument()
            try {
                for (pageIndex in 0 until totalPages) {
                    val originalBitmap = loadPage(pageIndex) ?: continue
                    val pageAnnotations = annotationsList.filter { it.pageIndex == pageIndex }

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
                                strokeWidth = annotation.strokeWidth
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

                    // Scale back to original PDF point size (approximate)
                    // Render scale was 1.5f.
                    val scaleFactor = 1f / 1.5f
                    val pageWidth = annotatedBitmap.width * scaleFactor
                    val pageHeight = annotatedBitmap.height * scaleFactor

                    val page = PDPage(PDRectangle(pageWidth, pageHeight))
                    newDocument.addPage(page)

                    val pdImage = LosslessFactory.createFromImage(newDocument, annotatedBitmap)
                    PDPageContentStream(newDocument, page).use { contentStream ->
                        contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                    }

                    annotatedBitmap.recycle()
                }

                context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    java.io.BufferedOutputStream(outputStream).use { bufferedStream ->
                        newDocument.save(bufferedStream)
                    }
                }
                true
            } finally {
                newDocument.close()
            }
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "Error saving annotations", e)
            false
        }
    }

    suspend fun extractText() = withContext(Dispatchers.IO) {
        extractionMutex.withLock {
            if (extractedTextCache.isNotEmpty()) return@withLock

            documentMutex.withLock {
                val doc = document ?: return@withLock
                try {
                    val stripper = PDFTextStripper()
                    val maxPages = doc.numberOfPages.coerceAtMost(20)

                    for (i in 1..maxPages) {
                        stripper.startPage = i
                        stripper.endPage = i
                        val text = stripper.getText(doc)
                        extractedTextCache[i - 1] = text
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error extracting text", e)
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
