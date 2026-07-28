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
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer
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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

data class PageTextData(val text: String, val positions: List<TextPosition>)

// Moved from PdfViewerScreen.kt
enum class AnnotationTool(val displayName: String) {
    NONE("Select"),
    HIGHLIGHTER("Highlighter"),
    MARKER("Marker"),
    UNDERLINE("Underline"),
    ERASER("Eraser")
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

open class PdfViewerViewModel : ViewModel() {

    companion object {
        const val RENDER_SCALE = 1.5f  // ~108 DPI for text-based PDFs
        private const val MAX_RENDER_DIMENSION_PX = 2048
    }

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Idle)
    open val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _toolState = MutableStateFlow<PdfTool>(PdfTool.None)
    open val toolState: StateFlow<PdfTool> = _toolState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    open val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    open val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private val _selectedAnnotationTool = MutableStateFlow(AnnotationTool.NONE)
    open val selectedAnnotationTool: StateFlow<AnnotationTool> = _selectedAnnotationTool.asStateFlow()

    private val _selectedColor = MutableStateFlow(Color.Yellow)
    open val selectedColor: StateFlow<Color> = _selectedColor.asStateFlow()

    private val _annotations = MutableStateFlow<List<AnnotationStroke>>(emptyList())
    open val annotations: StateFlow<List<AnnotationStroke>> = _annotations.asStateFlow()

    // Stroke width configurations for each drawing tool
    private val _highlighterWidth = MutableStateFlow(20f)
    open val highlighterWidth: StateFlow<Float> = _highlighterWidth.asStateFlow()

    private val _markerWidth = MutableStateFlow(8f)
    open val markerWidth: StateFlow<Float> = _markerWidth.asStateFlow()

    private val _underlineWidth = MutableStateFlow(4f)
    open val underlineWidth: StateFlow<Float> = _underlineWidth.asStateFlow()

    private val _eraserWidth = MutableStateFlow(15f)
    open val eraserWidth: StateFlow<Float> = _eraserWidth.asStateFlow()

    fun setHighlighterWidth(width: Float) {
        _highlighterWidth.value = width
    }

    fun setMarkerWidth(width: Float) {
        _markerWidth.value = width
    }

    fun setUnderlineWidth(width: Float) {
        _underlineWidth.value = width
    }

    fun setEraserWidth(width: Float) {
        _eraserWidth.value = width
    }

    // Document management
    private var document: PDDocument? = null
    private var pdfRenderer: PDFRenderer? = null
    private var androidPdfRenderer: AndroidPdfRenderer? = null
    private var androidPdfPfd: ParcelFileDescriptor? = null
    private val documentMutex = Mutex()
    private var tempFile: File? = null

    // Search Cache with LRU eviction (max 20 pages) to prevent OOM
    private val extractedTextCache = object : LinkedHashMap<Int, PageTextData>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, PageTextData>) = size > 20
    }

    // Search Job Control
    private var searchJob: Job? = null
    
    // Page state tracking for error handling
    sealed class PageRenderState {
        object Idle : PageRenderState()
        object Loading : PageRenderState()
        data class Loaded(val bitmap: Bitmap) : PageRenderState()
        data class Error(val pageIndex: Int, val message: String) : PageRenderState()
    }
    private val _pageStates = MutableStateFlow<Map<Int, PageRenderState>>(emptyMap())
    
    // Current page tracking for memory management
    private var _currentPage: Int = 0
    
    // Safe bitmap lifecycle management - prevents recycled bitmap crashes
    private val activeBitmaps = Collections.synchronizedSet(mutableSetOf<Bitmap>())
    private val uiBitmapRefs = mutableMapOf<Int, Bitmap>()
    
    private fun safeRecycle(bitmap: Bitmap?) {
        bitmap ?: return
        if (!bitmap.isRecycled && !activeBitmaps.contains(bitmap)) {
            bitmap.recycle()
        }
    }
    
    private fun registerActiveBitmap(pageIndex: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled) {
            Log.w("PdfViewerVM", "Skipping active registration for recycled bitmap on page $pageIndex")
            bitmapCache.remove(pageIndex)
            return
        }

        synchronized(activeBitmaps) {
            // Remove old bitmap from active set if exists
            uiBitmapRefs[pageIndex]?.let { oldBitmap ->
                if (oldBitmap !== bitmap) {
                    activeBitmaps.remove(oldBitmap)
                    // Do not call safeRecycle(oldBitmap) here to prevent throwIfCannotDraw crashes,
                    // as Compose may still be drawing the old bitmap asynchronously.
                    // GC will naturally reclaim it once Compose drops its reference.
                }
            }
            // Register new bitmap
            activeBitmaps.add(bitmap)
            uiBitmapRefs[pageIndex] = bitmap
        }
    }
    
    private fun unregisterBitmap(pageIndex: Int) {
        synchronized(activeBitmaps) {
            uiBitmapRefs.remove(pageIndex)?.let { oldBitmap ->
                activeBitmaps.remove(oldBitmap)
            }
        }
    }

    fun releasePage(pageIndex: Int) {
        unregisterBitmap(pageIndex)
    }

    fun loadPdf(context: Context, uri: Uri, password: String = "", savedPage: Int = 0) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                // Pre-open memory check
                val runtime = Runtime.getRuntime()
                val availableMemMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1048576
                if (availableMemMb < 50) {
                    Log.w("PdfViewerVM", "Low memory before opening PDF: ${availableMemMb}MB, triggering GC")
                    System.gc()
                    delay(100)
                }

                withContext(Dispatchers.IO) {
                    closeDocument() // Close existing if any, MUST be in IO dispatcher

                    // Use a temp file to load the PDF to avoid OOM with large files
                    // PDDocument.load(File, MemoryUsageSetting) allows using disk instead of RAM
                    val fileToLoad: File
                    var createdTempFile: File? = null

                    try {
                        if (uri.scheme == "file" && uri.path != null) {
                            fileToLoad = File(uri.path!!)
                        } else {
                            // For content URIs, copy to a temp file
                            // Create a unique temp file in cache dir
                            val temp = File.createTempFile("pdf_view_", ".pdf", context.cacheDir)

                            context.contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(temp).use { output ->
                                    input.copyTo(output)
                                }
                            } ?: throw Exception("Cannot open URI")

                            fileToLoad = temp
                            createdTempFile = temp // Track locally
                        }

                        // Document open with timeout for large PDFs
                        val doc = withTimeoutOrNull(30000) {
                            if (password.isNotEmpty()) {
                                PDDocument.load(fileToLoad, password, MemoryUsageSetting.setupTempFileOnly())
                            } else {
                                PDDocument.load(fileToLoad, MemoryUsageSetting.setupTempFileOnly())
                            }
                        } ?: throw Exception("PDF too large to open - timed out after 30 seconds")

                        val pageCount = doc.numberOfPages
                        Log.d("PdfViewerVM", "Loaded PDF with $pageCount pages")

                        documentMutex.withLock {
                            document = doc
                            pdfRenderer = PDFRenderer(doc)
                            tempFile = createdTempFile // Transfer ownership to instance
                            try {
                                androidPdfPfd = ParcelFileDescriptor.open(fileToLoad, ParcelFileDescriptor.MODE_READ_ONLY)
                                androidPdfRenderer = AndroidPdfRenderer(androidPdfPfd!!)
                            } catch (e: Exception) {
                                Log.e("PdfViewerVM", "Error initializing AndroidPdfRenderer", e)
                            }
                        }

                        _currentPage = savedPage.coerceIn(0, pageCount - 1)
                        _uiState.value = PdfViewerUiState.Loaded(pageCount)
                    } catch (e: Exception) {
                        // Clean up any temp file created if loading failed
                        createdTempFile?.delete()
                        throw e // Rethrow to outer catch
                    }
                }
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Error loading PDF", e)
                _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }
    
    // Update current page for memory management
    fun updateCurrentPage(pageIndex: Int) {
        _currentPage = pageIndex
    }
    
    // Retry a failed page render
    fun retryPage(pageIndex: Int) {
        unregisterBitmap(pageIndex)
        bitmapCache.remove(pageIndex)
    }
    
    fun getPageState(pageIndex: Int): PageRenderState {
        return _pageStates.value[pageIndex] ?: PageRenderState.Idle
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
        if (_annotations.value.size > 500) throw OutOfMemoryError("PDF has too many annotations to process at once")
        val currentList = _annotations.value.toMutableList()
        currentList.add(stroke)
        _annotations.value = currentList
    }

fun undoAnnotation() {
    if (_annotations.value.size > 500) throw OutOfMemoryError("PDF has too many annotations to process at once")
    val currentList = _annotations.value.toMutableList()
    if (currentList.isNotEmpty()) {
        currentList.removeAt(currentList.lastIndex)
        _annotations.value = currentList
    }
}

fun eraseAnnotations(pageIndex: Int, eraserPoints: List<Offset>, eraserNormWidth: Float) {
    val currentList = _annotations.value.toMutableList()
    val threshold = (eraserNormWidth * 3f).coerceAtLeast(0.01f)
    currentList.removeAll { stroke ->
        if (stroke.pageIndex != pageIndex) return@removeAll false
        stroke.points.any { sp ->
            eraserPoints.any { ep ->
                val dx = sp.x - ep.x
                val dy = sp.y - ep.y
                dx * dx + dy * dy < threshold * threshold
            }
        }
    }
    _annotations.value = currentList
}

    fun clearAnnotations() {
        _annotations.value = emptyList()
    }

    // Bitmap cache dynamically sized to 1/8th of the device's maximum available heap memory
    private val cacheSize = try {
        val maxMemory = Runtime.getRuntime().maxMemory()
        val optimalSize = (maxMemory / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        optimalSize.coerceAtLeast(30 * 1024 * 1024) // Fallback to 30 MB minimum
    } catch (e: Exception) {
        30 * 1024 * 1024
    }
    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount
        }
        
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !activeBitmaps.contains(oldValue)) {
                safeRecycle(oldValue)
            }
        }
    }
    
    private var loadJob: Job? = null

    private fun calculateCappedRenderScale(pageIndex: Int): Float {
        val page = document?.getPage(pageIndex) ?: return RENDER_SCALE
        val box = page.cropBox ?: page.mediaBox ?: return RENDER_SCALE
        val targetWidth = box.width * RENDER_SCALE
        val targetHeight = box.height * RENDER_SCALE
        val largestDimension = maxOf(targetWidth, targetHeight)

        return if (largestDimension > MAX_RENDER_DIMENSION_PX) {
            RENDER_SCALE * (MAX_RENDER_DIMENSION_PX / largestDimension)
        } else {
            RENDER_SCALE
        }
    }
    
    suspend fun loadPage(pageIndex: Int): Bitmap? {
        val totalPages = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return null
        if (pageIndex < 0 || pageIndex >= totalPages) return null

        // Check cache first
        bitmapCache.get(pageIndex)?.let { cached ->
            if (!cached.isRecycled) {
                registerActiveBitmap(pageIndex, cached)
                return if (!cached.isRecycled) cached else null
            }
            bitmapCache.remove(pageIndex)
        }

        // Render directly - no inner launch/join, no deadlock
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                documentMutex.withLock {
                    // Double-check cache inside lock
                    bitmapCache.get(pageIndex)?.let { cached ->
                        if (!cached.isRecycled) {
                            return@withLock cached
                        }
                        bitmapCache.remove(pageIndex)
                    }

                    val androidRenderer = androidPdfRenderer
                    if (androidRenderer != null) {
                        try {
                            val page = androidRenderer.openPage(pageIndex)
                            val renderScale = calculateCappedRenderScale(pageIndex)
                            val width = (page.width * renderScale).toInt().coerceAtLeast(1)
                            val height = (page.height * renderScale).toInt().coerceAtLeast(1)
                            val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bm.eraseColor(android.graphics.Color.WHITE)
                            page.render(bm, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            if (!bm.isRecycled) {
                                bitmapCache.put(pageIndex, bm)
                                bm
                            } else {
                                null
                            }
                        } catch (oom: OutOfMemoryError) {
                            Log.e("PdfViewerVM", "OOM rendering page $pageIndex, clearing cache and retrying at lower scale", oom)
                            bitmapCache.evictAll()
                            System.gc()
                            try {
                                val page = androidRenderer.openPage(pageIndex)
                                val renderScale = calculateCappedRenderScale(pageIndex) * 0.5f
                                val width = (page.width * renderScale).toInt().coerceAtLeast(1)
                                val height = (page.height * renderScale).toInt().coerceAtLeast(1)
                                val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bm.eraseColor(android.graphics.Color.WHITE)
                                page.render(bm, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                page.close()

                                if (!bm.isRecycled) {
                                    bitmapCache.put(pageIndex, bm)
                                    bm
                                } else {
                                    null
                                }
                            } catch (t: Throwable) {
                                Log.e("PdfViewerVM", "Failed to render page $pageIndex even at lower scale", t)
                                null
                            }
                        } catch (e: Exception) {
                            Log.e("PdfViewerVM", "Render failed page $pageIndex: ${e.message}")
                            null
                        }
                    } else {
                        val renderer = pdfRenderer ?: return@withLock null
                        try {
                            val renderScale = calculateCappedRenderScale(pageIndex)
                            var bmResult: Bitmap? = null
                            renderer.renderImage(pageIndex, renderScale)?.also { bm ->
                                if (!bm.isRecycled) {
                                    bitmapCache.put(pageIndex, bm)
                                    bmResult = bm
                                }
                            }
                            bmResult
                        } catch (e: Exception) {
                            Log.e("PdfViewerVM", "PDFBox Render failed page $pageIndex: ${e.message}")
                            null
                        }
                    }
                }
            }
            if (bitmap == null || bitmap.isRecycled) {
                if (bitmap?.isRecycled == true) {
                    bitmapCache.remove(pageIndex)
                }
                return null
            }
            registerActiveBitmap(pageIndex, bitmap)
            if (!bitmap.isRecycled) bitmap else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PdfViewerVM", "loadPage error page $pageIndex: ${e.message}")
            null
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        val currentState = _searchState.value
        if (currentState.isLoading) {
            _searchState.value = currentState.copy(isLoading = false)
        }
    }

    /**
     * Internal text extraction helper. Assumes documentMutex lock is already held.
     */
    private suspend fun getPageTextInternal(doc: PDDocument, pageIndex: Int): PageTextData? {
        var pageData = extractedTextCache[pageIndex]
        if (pageData == null) {
            pageData = withTimeoutOrNull(5000) {
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

                val pageText = stripper.getText(doc)
                val cleanedText = pageText.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                
                PageTextData(cleanedText, textPositions)
            }
            if (pageData != null) {
                extractedTextCache[pageIndex] = pageData
            }
        }
        return pageData
    }

    /**
     * Exposes page text data in a thread-safe way. Lock-safe for external selection.
     */
    suspend fun getPageText(pageIndex: Int): PageTextData? {
        return withContext(Dispatchers.IO) {
            documentMutex.withLock {
                val doc = document ?: return@withLock null
                getPageTextInternal(doc, pageIndex)
            }
        }
    }

    /**
     * Check if a page has extractable text (not a scanned/image PDF)
     */
    private fun hasExtractableText(doc: PDDocument, pageIndex: Int): Boolean {
        return try {
            val page = doc.getPage(pageIndex)
            val contentStream = page.contentStreams
            contentStream != null && contentStream.hasNext()
        } catch (e: Exception) {
            false
        }
    }

    fun search(query: String) {
        // Cancel previous search
        stopSearch()

        if (query.length < 2) {
            _searchState.value = SearchState(query = query)
            return
        }

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            if (e is OutOfMemoryError) {
                _uiState.update {
                    PdfViewerUiState.Error("Not enough memory. Close other apps and try again.")
                }
            } else throw e
        }
        searchJob = viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            _searchState.value = _searchState.value.copy(query = query, isLoading = true)

            val matches = mutableListOf<SearchMatch>()
            val scannedPages = mutableListOf<Int>()

            documentMutex.withLock {
                val doc = document ?: return@withLock
                val totalPages = doc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    ensureActive() // Allow cancellation
                    yield()

                    try {
                        val lowerQuery = query.lowercase()

                        // Check if page has extractable text
                        if (!hasExtractableText(doc, pageIndex)) {
                            scannedPages.add(pageIndex)
                            continue // Skip pages that can't be searched
                        }

                        val pageData = getPageTextInternal(doc, pageIndex)
                        if (pageData == null) {
                            Log.w("PdfViewerVM", "Text extraction timed out for page $pageIndex")
                            continue
                        }

                        if (!pageData.text.lowercase().contains(lowerQuery)) {
                            continue
                        }

                        val sb = StringBuilder()
                        val positionMap = mutableListOf<Int>()

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

                                    val scale = RENDER_SCALE
                                    val x = tp.xDirAdj * scale
                                    val y = tp.yDirAdj * scale
                                    val w = tp.widthDirAdj * scale
                                    val h = tp.heightDir * scale

                                    matchRects.add(RectF(x, y - h, x + w, y + h * 0.2f))
                                }
                            }

                            if (matchRects.isNotEmpty()) {
                                matches.add(SearchMatch(pageIndex, matchRects))
                            }
                            pos = found + 1
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("PdfViewerVM", "Error searching page $pageIndex", e)
                    }
                }
            }

            // Log scanned pages that couldn't be searched
            if (scannedPages.isNotEmpty()) {
                Log.d("PdfViewerVM", "Search skipped ${scannedPages.size} scanned pages: $scannedPages")
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

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            if (e is OutOfMemoryError) {
                _uiState.update {
                    PdfViewerUiState.Error("Not enough memory. Close other apps and try again.")
                }
            } else throw e
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
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
                    val rawStream = context.contentResolver.openOutputStream(outputUri)
                    outputStream = BufferedOutputStream(rawStream)

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
                            // destDoc.addPage(importedPage) // Removed to prevent duplicate pages
                        } else if (rotation == 0) {
                            // VECTOR INJECTION: Preserve text and vectors for upright pages
                            val importedPage = destDoc.importPage(sourcePage)
                            // importedPage is owned by destDoc, so we don't need to manually copy mediaBox from source.
                            // destDoc.addPage(importedPage) // Removed to prevent duplicate pages

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

                                    // Set line width (normalized to PDF points)
                                    val pageWidth = importedPage.mediaBox.width
                                    val pdfStrokeWidth = annotation.strokeWidth * pageWidth

                                    cs.setLineWidth(pdfStrokeWidth)
                                    cs.setLineCapStyle(1) // Round Cap
                                    cs.setLineJoinStyle(1) // Round Join

                                    if (annotation.points.isNotEmpty()) {
                                        val first = annotation.points.first()
                                        // Coordinate Transform: Normalized -> PDF Bottom-Left
                                        // X_pdf = X_norm * PageWidth
                                        // Y_pdf = PageHeight - (Y_norm * PageHeight)

                                        val startX = first.x * pageWidth
                                        val startY = pageHeight - (first.y * pageHeight)

                                        cs.moveTo(startX, startY)

                                        for (i in 1 until annotation.points.size) {
                                            val p = annotation.points[i]
                                            val px = p.x * pageWidth
                                            val py = pageHeight - (p.y * pageHeight)
                                            cs.lineTo(px, py)
                                        }
                                        cs.stroke()
                                    }
                                }
                            }
                        } else {
                            // RASTER FALLBACK: For rotated pages, use safer bitmap rasterization to guarantee alignment
                            // Render and flatten

                            // Render fresh and ensure mutable copy
                            val androidRenderer = androidPdfRenderer
                            var rendered: Bitmap? = null
                            if (androidRenderer != null) {
                                try {
                                    val page = androidRenderer.openPage(pageIndex)
                                    val width = (page.width * RENDER_SCALE).toInt().coerceAtLeast(1)
                                    val height = (page.height * RENDER_SCALE).toInt().coerceAtLeast(1)
                                    val bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    bm.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bm, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    rendered = bm
                                } catch (e: Exception) {
                                    rendered = pdfRenderer?.renderImage(pageIndex, RENDER_SCALE)
                                }
                            } else {
                                rendered = pdfRenderer?.renderImage(pageIndex, RENDER_SCALE)
                            }

                            val workingBitmap = rendered?.let { bitmap ->
                                // Check available memory before creating a copy (doubles memory usage)
                                val runtime = Runtime.getRuntime()
                                val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                                val bitmapSize = bitmap.byteCount.toLong()

                                if (availableMemory < bitmapSize * 2) {
                                    // Not enough memory for copy - use original bitmap directly
                                    // This may not support drawing but prevents OOM
                                    Log.w("PdfViewerVM", "Low memory: skipping bitmap copy for page $pageIndex")
                                    bitmap
                                } else {
                                    // Safe to create mutable copy
                                    bitmap.copy(Bitmap.Config.ARGB_8888, true).also {
                                        // Safe recycle - check if bitmap is still in use
                                        safeRecycle(bitmap)
                                    }
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
                                        // Denormalize stroke width for bitmap
                                        paint.strokeWidth = annotation.strokeWidth * workingBitmap.width

                                        if (annotation.points.isNotEmpty()) {
                                            val path = android.graphics.Path()
                                            val first = annotation.points.first()
                                            path.moveTo(first.x * workingBitmap.width, first.y * workingBitmap.height)

                                            for (i in 1 until annotation.points.size) {
                                                val p = annotation.points[i]
                                                path.lineTo(p.x * workingBitmap.width, p.y * workingBitmap.height)
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
                                    // Safe recycle - check if bitmap is still in use by UI
                                    safeRecycle(workingBitmap)
                                }
                            }
                        }

                        // Update Progress
                        val progress = (pageIndex + 1).toFloat() / totalPages
                        _saveState.value = SaveState.Saving(progress)
                    }

                    destDoc.save(outputStream)
                    outputStream.close()
                    outputStream = null
                    destDoc.close()

                    _saveState.value = SaveState.Success(outputUri)

                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error saving PDF", e)
                    _saveState.value = SaveState.Error(e.message ?: "Unknown error")
                } finally {
                    try { destDoc.close() } catch (_: Exception) {}
                    outputStream?.close()
                }
            }
        }
    }

    private suspend fun closeDocument() {
        documentMutex.withLock {
            try {
                // Clear any in-memory bitmap/page cache
                bitmapCache.evictAll()
                _annotations.value = emptyList()
                extractedTextCache.clear()

                // GC Hint
                System.gc()

                document?.close()
            } catch (e: Throwable) {
                Log.e("PdfViewerVM", "Error closing document", e)
            } finally {
                 document = null
                 pdfRenderer = null
                 try {
                     androidPdfRenderer?.close()
                     androidPdfRenderer = null
                 } catch (e: Exception) {}
                 try {
                     androidPdfPfd?.close()
                     androidPdfPfd = null
                 } catch (e: Exception) {}
                 extractedTextCache.clear()
                 // When navigating away from a PDF, trim cache to 0 immediately
                 bitmapCache.trimToSize(0)
                 
                 // Clean up temp file
                try {
                    if (tempFile?.exists() == true) {
                        tempFile?.delete()
                    }
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error deleting temp file", e)
                }
                tempFile = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            if (e is OutOfMemoryError) {
                _uiState.update {
                    PdfViewerUiState.Error("Not enough memory. Close other apps and try again.")
                }
            } else throw e
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            synchronized(activeBitmaps) {
                uiBitmapRefs.values.forEach { if (!it.isRecycled) it.recycle() }
                uiBitmapRefs.clear()
                activeBitmaps.clear()
            }
            bitmapCache.evictAll()
            closeDocument()
        }
    }
}
