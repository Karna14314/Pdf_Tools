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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
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
        const val RENDER_SCALE = 1.5f  // ~108 DPI for text-based PDFs
        const val RENDER_SCALE_SCANNED = 2.5f  // ~180 DPI for scanned/image-heavy PDFs
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
    private var tempFile: File? = null

    // Search Cache with LRU eviction (max 20 pages) to prevent OOM
    private val extractedTextCache = object : LinkedHashMap<Int, PageTextData>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, PageTextData>) = size > 20
    }

    // Search Job Control
    private var searchJob: Job? = null
    
    // Render job tracking to cancel in-flight renders for same page
    private val renderJobs = mutableMapOf<Int, Job>()
    
    // Page state tracking for error handling
    sealed class PageRenderState {
        object Idle : PageRenderState()
        object Loading : PageRenderState()
        data class Loaded(val bitmap: Bitmap) : PageRenderState()
        data class Error(val pageIndex: Int, val message: String) : PageRenderState()
    }
    private val _pageStates = mutableMapOf<Int, PageRenderState>()
    
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
        synchronized(activeBitmaps) {
            // Remove old bitmap from active set if exists
            uiBitmapRefs[pageIndex]?.let { oldBitmap ->
                activeBitmaps.remove(oldBitmap)
                safeRecycle(oldBitmap)
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
                safeRecycle(oldBitmap)
            }
        }
    }

    fun loadPdf(context: Context, uri: Uri, password: String = "", savedPage: Int = 0) {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            try {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }

                closeDocument() // Close existing if any
                
                // Pre-open memory check
                val runtime = Runtime.getRuntime()
                val availableMemMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1048576
                if (availableMemMb < 50) {
                    Log.w("PdfViewerVM", "Low memory before opening PDF: ${availableMemMb}MB, triggering GC")
                    System.gc()
                    delay(100)
                }

                withContext(Dispatchers.IO) {
                    // Use a temp file to load the PDF to avoid OOM with large files
                    // PDDocument.load(File, MemoryUsageSetting) allows using disk instead of RAM

                    val fileSize = if (uri.scheme == "file") File(uri.path!!).length() else {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (cursor.moveToFirst() && sizeIndex != -1) cursor.getLong(sizeIndex) else 0L
                        } ?: 0L
                    }

                    if (fileSize > 50 * 1024 * 1024) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "This file is large and may take a moment to open", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }

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
                        } ?: throw Exception("PDF too large to open - timed out after 30 seconds. Try a smaller file.")

                        val pageCount = doc.numberOfPages
                        Log.d("PdfViewerVM", "Loaded PDF with $pageCount pages")

                        documentMutex.withLock {
                            document = doc
                            pdfRenderer = PDFRenderer(doc)
                            tempFile = createdTempFile // Transfer ownership to instance
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
        
        // Evict pages far from current to save memory
        viewModelScope.launch(Dispatchers.IO) {
            val totalPages = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return@launch
            if (totalPages > 10) {
                // Remove pages more than 5 pages away from current
                for (i in 0 until totalPages) {
                    if (kotlin.math.abs(i - pageIndex) > 5) {
                        bitmapCache.remove(i)
                    }
                }
            }
        }
    }
    
    // Retry a failed page render
    fun retryPage(pageIndex: Int) {
        viewModelScope.launch {
            // 1. Cancel any in-flight render job for this page
            renderJobs[pageIndex]?.cancel()
            // DO NOT recycle - let GC handle cancelled job's bitmap
            renderJobs.remove(pageIndex)
            
            // 2. Unregister old bitmap from active set and clear state
            unregisterBitmap(pageIndex)
            _pageStates[pageIndex] = PageRenderState.Loading
            
            // 3. Remove from cache - cache.entryRemoved does NOT recycle
            bitmapCache.remove(pageIndex)
            
            // 4. Start fresh render
            loadPage(pageIndex)
        }
    }
    
    fun getPageState(pageIndex: Int): PageRenderState {
        return _pageStates[pageIndex] ?: PageRenderState.Idle
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

    // Bitmap cache for rendered pages
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8
    private val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
        
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            // DO NOT recycle here - only drop the reference
            // The bitmap lifecycle is managed by activeBitmaps reference counting
            // Recycling happens through safeRecycle() only when not in use by UI
        }
    }
    
    private var prefetchJob: Job? = null
    
    /**
     * Detect if a page is likely a scanned/image-heavy page by checking for XObject images
     * in the page resources. Returns true if the page has embedded images.
     */
    private fun isScannedPage(pageIndex: Int): Boolean {
        return try {
            val doc = document ?: return false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return false
            
            val page = doc.getPage(pageIndex)
            val resources = page.resources
            
            // Check if page has XObject images
            val xObjectNames = resources.xObjectNames?.toList() ?: emptyList()
            xObjectNames.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun loadPage(pageIndex: Int): Bitmap? {
        // Page bounds check
        val totalPages = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return null
        if (pageIndex < 0 || pageIndex >= totalPages) {
            Log.w("PdfViewerVM", "Invalid page index: $pageIndex, total pages: $totalPages")
            return null
        }
        
        // Check cache first - register as active for UI
        bitmapCache.get(pageIndex)?.let { cachedBitmap ->
            registerActiveBitmap(pageIndex, cachedBitmap)
            _pageStates[pageIndex] = PageRenderState.Loaded(cachedBitmap)
            return cachedBitmap
        }
        
        // Cancel any existing render job for this page
        renderJobs[pageIndex]?.cancel()
        // DO NOT recycle cancelled job's bitmap - let GC handle it
        renderJobs.remove(pageIndex)
        
        // Set loading state
        _pageStates[pageIndex] = PageRenderState.Loading
        
        // Create new render job
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = renderPageInternal(pageIndex)
                if (bitmap != null) {
                    registerActiveBitmap(pageIndex, bitmap)
                    _pageStates[pageIndex] = PageRenderState.Loaded(bitmap)
                } else {
                    _pageStates[pageIndex] = PageRenderState.Error(pageIndex, "Failed to render page")
                }
            } catch (e: CancellationException) {
                // Re-throw cancellation - do not recycle, let GC handle
                throw e
            } catch (e: Exception) {
                Log.e("PdfViewerVM", "Render failed for page $pageIndex: ${e.message}", e)
                _pageStates[pageIndex] = PageRenderState.Error(pageIndex, e.message ?: "Render error")
            } finally {
                renderJobs.remove(pageIndex)
            }
        }
        
        renderJobs[pageIndex] = job
        
        // Wait for the render job to complete
        job.join()
        
        // Trigger prefetch for adjacent pages
        prefetchPages(pageIndex)
        
        return bitmapCache.get(pageIndex)?.also { registerActiveBitmap(pageIndex, it) }
    }
    
    private suspend fun renderPageInternal(pageIndex: Int): Bitmap? {
        return withContext(Dispatchers.IO) {
            ensureActive()
            
            documentMutex.withLock {
                ensureActive()
                
                try {
                    // Double check cache inside lock
                    bitmapCache.get(pageIndex)?.let { return@withLock it }
                    
                    val renderer = pdfRenderer ?: return@withLock null
                    
                    // Use higher scale for scanned/image-heavy pages
                    // Scale rendered bitmaps based on available heap
                    val runtime = Runtime.getRuntime()
                    val availableMemMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1048576
                    val dynamicRenderScale = if (availableMemMb < 100) 0.5f else if (availableMemMb < 250) 0.75f else RENDER_SCALE

                    val scale = if (isScannedPage(pageIndex)) {
                        Log.d("PdfViewerVM", "Page $pageIndex has images, using higher render scale: $RENDER_SCALE_SCANNED")
                        if (availableMemMb < 150) RENDER_SCALE else RENDER_SCALE_SCANNED
                    } else {
                        dynamicRenderScale
                    }
                    
                    ensureActive()
                    
                    val bitmap = renderer.renderImage(pageIndex, scale)
                    
                    if (bitmap != null) {
                        bitmapCache.put(pageIndex, bitmap)
                    }
                    bitmap
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("PdfViewerVM", "Error rendering page $pageIndex", e)
                    null
                }
            }
        }
    }
    
    private fun prefetchPages(currentPage: Int) {
        val totalPages = (_uiState.value as? PdfViewerUiState.Loaded)?.totalPages ?: return
        
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            // Calculate prefetch count based on available memory
            val runtime = Runtime.getRuntime()
            val availableMemMb = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1048576
            val prefetchCount = when {
                availableMemMb > 200 -> 3
                availableMemMb > 100 -> 2
                else -> 1
            }
            
            val range = (-prefetchCount..prefetchCount).filter { it != 0 }.map { currentPage + it }
            
            for (page in range) {
                if (page in 0 until totalPages) {
                    if (bitmapCache.get(page) == null && _pageStates[page] !is PageRenderState.Error) {
                        yield()
                        
                        try {
                            documentMutex.withLock {
                                ensureActive()
                                if (bitmapCache.get(page) == null) {
                                    pdfRenderer?.renderImage(page, RENDER_SCALE)?.let { bitmap ->
                                        bitmapCache.put(page, bitmap)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Ignore prefetch errors silently
                            Log.d("PdfViewerVM", "Prefetch failed for page $page: ${e.message}")
                        }
                    }
                }
            }
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

        searchJob = viewModelScope.launch(Dispatchers.IO) {
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

                        // Check if page is scanned (image-based)
                        if (!hasExtractableText(doc, pageIndex) || isScannedPage(pageIndex)) {
                            scannedPages.add(pageIndex)
                            continue // Skip scanned pages - can't search them
                        }

                        // Check cache first
                        var pageData = extractedTextCache[pageIndex]

                        if (pageData == null) {
                            // Extract with timeout to prevent hanging on large/complex pages
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
                                // Clean up extracted text
                                val cleanedText = pageText.lines()
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                                    .joinToString("\n")
                                
                                PageTextData(cleanedText.lowercase(), textPositions)
                            }
                            
                            if (pageData != null) {
                                extractedTextCache[pageIndex] = pageData
                            } else {
                                Log.w("PdfViewerVM", "Text extraction timed out for page $pageIndex")
                                continue
                            }
                        }

                        if (!pageData.text.contains(lowerQuery)) {
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

                                    matchRects.add(RectF(x, y, x + w, y + h))
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
                            val rendered = pdfRenderer?.renderImage(pageIndex, RENDER_SCALE)
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
                                    val canvas = if (!workingBitmap.isRecycled) Canvas(workingBitmap) else continue
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
                extractedTextCache.clear()

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
        // Cancel all render jobs first
        renderJobs.values.forEach { it.cancel() }
        renderJobs.clear()
        
        // Use Handler to defer cleanup - allows Compose to finish drawing current frame
        Handler(Looper.getMainLooper()).postDelayed({
            viewModelScope.launch(Dispatchers.IO) {
                // Unregister all active bitmaps before recycling
                synchronized(activeBitmaps) {
                    uiBitmapRefs.values.forEach { bitmap ->
                        activeBitmaps.remove(bitmap)
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                    uiBitmapRefs.clear()
                    activeBitmaps.clear()
                }
                
                // Clear cache without recycling (already handled above)
                bitmapCache.evictAll()
                
                closeDocument()
            }
        }, 500) // 500ms delay ensures Compose is done drawing
        
        super.onCleared()
    }
}
