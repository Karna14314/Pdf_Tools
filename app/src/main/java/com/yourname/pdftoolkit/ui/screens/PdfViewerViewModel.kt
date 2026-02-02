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
import android.graphics.Paint
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
import java.io.IOException
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
    object Annotate : PdfTool()
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
    data class Results(val matches: List<Pair<Int, List<RectF>>>, val currentMatchIndex: Int) : SearchState()
    object NoMatches : SearchState()
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

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow.copy(alpha = 0.5f))
    val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

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
        // Reset specific annotation tool if we leave Annotate mode
        if (tool != PdfTool.Annotate) {
            _selectedAnnotationTool.value = AnnotationTool.NONE
        } else {
            // Default to Highlighter when entering Annotate mode
            if (_selectedAnnotationTool.value == AnnotationTool.NONE) {
                _selectedAnnotationTool.value = AnnotationTool.HIGHLIGHTER
            }
        }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _selectedAnnotationTool.value = tool
        if (tool != AnnotationTool.NONE) {
            _toolState.value = PdfTool.Annotate
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

    fun saveAnnotations(contentResolver: android.content.ContentResolver, outputUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveState.value = SaveState.Saving
            try {
                val outputStream = contentResolver.openOutputStream(outputUri)
                    ?: throw IOException("Could not open output stream")

                // Grab total pages from current doc securely
                val totalPages = documentMutex.withLock {
                     document?.numberOfPages ?: 0
                }

                if (totalPages == 0) {
                     _saveState.value = SaveState.Error("Document is empty or not loaded")
                     outputStream.close()
                     return@launch
                }

                // Create new PDF document
                val newDocument = PDDocument()

                try {
                    val currentAnnotations = _annotations.value

                    for (pageIndex in 0 until totalPages) {
                        // Fetch page from ViewModel (forces render if not cached)
                        // This calls mutex internally, so we are safe.
                        val originalBitmap = loadPage(pageIndex)
                            ?: throw Exception("Failed to render page $pageIndex")

                        val pageAnnotations = currentAnnotations.filter { it.pageIndex == pageIndex }

                        // Create a mutable copy of the bitmap to draw annotations on
                        val annotatedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                        val canvas = android.graphics.Canvas(annotatedBitmap)

                        // Draw annotations on the bitmap
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

                                val path = android.graphics.Path()
                                path.moveTo(annotation.points[0].x, annotation.points[0].y)
                                for (i in 1 until annotation.points.size) {
                                    path.lineTo(annotation.points[i].x, annotation.points[i].y)
                                }
                                canvas.drawPath(path, paint)
                            }
                        }

                        // Create PDF page with the annotated bitmap
                        val width = annotatedBitmap.width.toFloat()
                        val height = annotatedBitmap.height.toFloat()

                        // Scale to reasonable PDF page size (72 DPI equivalent)
                        val scaleFactor = 72f / 150f // Original render was at 150 DPI
                        val pageWidth = width * scaleFactor
                        val pageHeight = height * scaleFactor

                        val page = PDPage(PDRectangle(pageWidth, pageHeight))
                        newDocument.addPage(page)

                        // Create image from bitmap
                        val pdImage = LosslessFactory.createFromImage(newDocument, annotatedBitmap)

                        // Draw image on page
                        PDPageContentStream(newDocument, page).use { contentStream ->
                            contentStream.drawImage(pdImage, 0f, 0f, pageWidth, pageHeight)
                        }

                        // Recycle the annotated bitmap (not the original!)
                        annotatedBitmap.recycle()
                    }

                    // Save the document
                    newDocument.save(outputStream)
                    _saveState.value = SaveState.Success
                } finally {
                    newDocument.close()
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error saving annotated PDF: ${e.message}", e)
                _saveState.value = SaveState.Error(e.message ?: "Failed to save")
            }
        }
    }

    fun search(query: String) {
        if (query.length < 2) {
            _searchState.value = SearchState.Idle
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _searchState.value = SearchState.Searching

            val matches = mutableListOf<Pair<Int, List<RectF>>>()
            val lowerQuery = query.lowercase()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages
                // Limit to 20 pages for performance as per original implementation,
                // but since we cache, we could do more. For now, stick to safe limit or iterate all?
                // Iterating all might be slow for large docs.
                // Let's try to search all but check cancellation?
                // Or just stick to the first 20 pages or so as a "preview" search if huge.
                // Let's go with max 50 pages for better utility.
                val pagesToSearch = totalPages.coerceAtMost(50)

                for (pageIndex in 0 until pagesToSearch) {
                    try {
                        // Use cached positions if available
                        var textPositions = textPositionsCache[pageIndex]
                        if (textPositions == null) {
                            val positions = mutableListOf<TextPosition>()
                            val stripper = object : PDFTextStripper() {
                                override fun processTextPosition(text: TextPosition) {
                                    super.processTextPosition(text)
                                    positions.add(text)
                                }
                            }
                            stripper.sortByPosition = true
                            stripper.startPage = pageIndex + 1
                            stripper.endPage = pageIndex + 1
                            stripper.writeText(doc, object : java.io.Writer() {
                                override fun write(cbuf: CharArray, off: Int, len: Int) {}
                                override fun flush() {}
                                override fun close() {}
                            }) // triggers processTextPosition

                            textPositions = positions
                            textPositionsCache[pageIndex] = positions
                        }

                        // Perform search on textPositions
                        if (textPositions.isNotEmpty()) {
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
                                        // Scale to 150 DPI equivalent as per Screen logic (150f / 72f)
                                        // The original code used: val scale = 150f / 72f
                                        val scale = 150f / 72f

                                        val x = tp.xDirAdj * scale
                                        val y = tp.yDirAdj * scale
                                        val w = tp.widthDirAdj * scale
                                        val h = tp.heightDir * scale

                                        matchRects.add(RectF(x, y, x + w, y + h))
                                    }
                                }

                                if (matchRects.isNotEmpty()) {
                                    matches.add(pageIndex to matchRects)
                                }

                                pos = found + 1
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error searching page $pageIndex", e)
                    }
                }
            }

            if (matches.isNotEmpty()) {
                _searchState.value = SearchState.Results(matches, 0)
            } else {
                _searchState.value = SearchState.NoMatches
            }
        }
    }

    fun nextSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            val nextIndex = if (currentState.currentMatchIndex < currentState.matches.size - 1)
                currentState.currentMatchIndex + 1 else currentState.currentMatchIndex
            _searchState.value = currentState.copy(currentMatchIndex = nextIndex)
        }
    }

    fun previousSearchResult() {
        val currentState = _searchState.value
        if (currentState is SearchState.Results) {
            val prevIndex = if (currentState.currentMatchIndex > 0)
                currentState.currentMatchIndex - 1 else 0
            _searchState.value = currentState.copy(currentMatchIndex = prevIndex)
        }
    }

    fun clearSearch() {
        _searchState.value = SearchState.Idle
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
