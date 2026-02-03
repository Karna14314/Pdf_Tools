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
    object Merge : PdfTool()
    object Compress : PdfTool()
    object Watermark : PdfTool()
}

sealed class PdfViewerUiState {
    object Idle : PdfViewerUiState()
    object Loading : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class Loaded(val totalPages: Int) : PdfViewerUiState()
}

sealed class SearchState {
    object Idle : SearchState()
    object Searching : SearchState()
    data class Results(val matches: List<Pair<Int, Int>>, val currentMatchIndex: Int) : SearchState()
    object NoMatch : SearchState()
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

    // Search State
    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private val documentMutex = Mutex()

    // Caches
    private val extractedTextCache = mutableMapOf<Int, String>()
    private val textPositionsCache = mutableMapOf<Int, List<TextPosition>>()

    // Bitmap Cache
    // Calculate cache size: Use 1/8th of the available memory for this memory cache.
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    companion object {
        const val RENDER_SCALE = 1.5f
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

        // Reset search if leaving search mode
        if (tool != PdfTool.Search) {
            _searchState.value = SearchState.Idle
            _searchQuery.value = ""
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

    // Search Functionality
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        if (query.length < 2) {
            _searchState.value = SearchState.Idle
            return
        }
        performSearch(query)
    }

    private fun performSearch(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.Searching

            // Wait for document to be ready
            val doc = documentMutex.withLock { document } ?: return@launch
            val totalPages = doc.numberOfPages
            val results = mutableListOf<Pair<Int, Int>>()
            val lowerQuery = query.lowercase()

            // Only search first 20 pages for performance or all if needed?
            // Let's stick to 20 for now as per original code logic but maybe safer to do all if fast enough?
            // Reusing document makes it faster. Let's try 50.
            val maxPages = totalPages.coerceAtMost(50)

            try {
                for (i in 0 until maxPages) {
                    val text = getPageText(i)
                    val lowerText = text.lowercase()
                    var pos = 0
                    while (true) {
                        val found = lowerText.indexOf(lowerQuery, pos)
                        if (found == -1) break
                        results.add(i to found)
                        pos = found + 1
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Search error", e)
            }

            if (results.isNotEmpty()) {
                _searchState.value = SearchState.Results(results, 0)
            } else {
                _searchState.value = SearchState.NoMatch
            }
        }
    }

    private suspend fun getPageText(pageIndex: Int): String {
        // Check cache
        if (extractedTextCache.containsKey(pageIndex)) {
            return extractedTextCache[pageIndex]!!
        }

        // Extract
        return documentMutex.withLock {
             val doc = document ?: return@withLock ""
             try {
                 val stripper = PDFTextStripper()
                 stripper.startPage = pageIndex + 1
                 stripper.endPage = pageIndex + 1
                 val text = stripper.getText(doc)
                 extractedTextCache[pageIndex] = text
                 text
             } catch (e: Exception) {
                 ""
             }
        }
    }

    fun nextSearchResult() {
        val state = _searchState.value
        if (state is SearchState.Results) {
            val nextIndex = (state.currentMatchIndex + 1).coerceAtMost(state.matches.size - 1)
            _searchState.value = state.copy(currentMatchIndex = nextIndex)
        }
    }

    fun previousSearchResult() {
        val state = _searchState.value
        if (state is SearchState.Results) {
            val prevIndex = (state.currentMatchIndex - 1).coerceAtLeast(0)
            _searchState.value = state.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchState.value = SearchState.Idle
    }

    suspend fun getSearchHighlights(pageIndex: Int): List<List<RectF>> {
        val query = _searchQuery.value
        if (query.length < 2) return emptyList()

        return withContext(Dispatchers.IO) {
            documentMutex.withLock {
                val doc = document ?: return@withLock emptyList()
                val allMatches = mutableListOf<List<RectF>>()

                try {
                    // We need text positions for this page
                    val textPositions = getTextPositions(doc, pageIndex)

                    // Reconstruct text from positions
                    val sb = StringBuilder()
                    textPositions.forEach { sb.append(it.unicode) }
                    val rawText = sb.toString().lowercase()
                    val lowerQuery = query.lowercase()

                    var pos = 0
                    while (true) {
                        val found = rawText.indexOf(lowerQuery, pos)
                        if (found == -1) break

                        val matchRects = mutableListOf<RectF>()

                        // Construct rect for this match
                        for (i in found until (found + lowerQuery.length)) {
                            if (i < textPositions.size) {
                                val tp = textPositions[i]
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
    }

    private fun getTextPositions(doc: PDDocument, pageIndex: Int): List<TextPosition> {
        if (textPositionsCache.containsKey(pageIndex)) {
            return textPositionsCache[pageIndex]!!
        }

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
        stripper.getText(doc) // This triggers processTextPosition

        textPositionsCache[pageIndex] = textPositions
        return textPositions
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
