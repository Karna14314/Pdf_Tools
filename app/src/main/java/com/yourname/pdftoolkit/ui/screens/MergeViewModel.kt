package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.domain.operations.MergeItem
import com.yourname.pdftoolkit.domain.operations.MergeItemType
import com.yourname.pdftoolkit.domain.operations.MergePageItem
import com.yourname.pdftoolkit.domain.operations.PdfMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class MergeViewMode {
    FILE_VIEW,
    PAGE_VIEW
}

class MergeViewModel : ViewModel() {

    private val pdfMerger = PdfMerger()

    private val _selectedItems = MutableStateFlow<List<MergeItem>>(emptyList())
    val selectedItems: StateFlow<List<MergeItem>> = _selectedItems.asStateFlow()

    private val _viewMode = MutableStateFlow(MergeViewMode.FILE_VIEW)
    val viewMode: StateFlow<MergeViewMode> = _viewMode.asStateFlow()

    private val _pageItems = MutableStateFlow<List<MergePageItem>>(emptyList())
    val pageItems: StateFlow<List<MergePageItem>> = _pageItems.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    // Thumbnail LruCache (50 bitmaps)
    private val thumbnailCache = object : LruCache<String, Bitmap>(50) {
        override fun entryRemoved(evicted: Boolean, key: String?, oldValue: Bitmap?, newValue: Bitmap?) {
            if (evicted && oldValue != newValue && oldValue?.isRecycled == false) {
                oldValue.recycle()
            }
        }
    }

    private val renderSemaphore = Semaphore(3)

    fun setViewMode(mode: MergeViewMode) {
        _viewMode.value = mode
    }

    fun addFiles(context: Context, uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val newItems = uris.mapNotNull { uri ->
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val isPdf = mimeType == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)
                val isImage = mimeType.startsWith("image/") ||
                        uri.toString().endsWith(".jpg", ignoreCase = true) ||
                        uri.toString().endsWith(".jpeg", ignoreCase = true) ||
                        uri.toString().endsWith(".png", ignoreCase = true) ||
                        uri.toString().endsWith(".webp", ignoreCase = true)

                if (!isPdf && !isImage) return@mapNotNull null

                val fileInfo = FileManager.getFileInfo(context, uri)
                val name = fileInfo?.name ?: uri.lastPathSegment ?: "File"
                val size = fileInfo?.size ?: 0L
                val formattedSize = fileInfo?.formattedSize ?: ""

                val type = if (isImage) MergeItemType.IMAGE else MergeItemType.PDF
                val pageCount = if (isPdf) {
                    pdfMerger.getPageCount(context, uri, isImage = false)
                } else 1

                MergeItem(
                    uri = uri,
                    name = name,
                    size = size,
                    formattedSize = formattedSize,
                    type = type,
                    pageCount = pageCount
                )
            }

            val updated = _selectedItems.value + newItems
            _selectedItems.value = updated
            updatePageItems(updated)
        }
    }

    fun removeItem(index: Int) {
        val current = _selectedItems.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _selectedItems.value = current
            updatePageItems(current)
        }
    }

    fun moveItemUp(index: Int) {
        val current = _selectedItems.value.toMutableList()
        if (index > 0 && index in current.indices) {
            val item = current.removeAt(index)
            current.add(index - 1, item)
            _selectedItems.value = current
            updatePageItems(current)
        }
    }

    fun moveItemDown(index: Int) {
        val current = _selectedItems.value.toMutableList()
        if (index < current.lastIndex && index >= 0) {
            val item = current.removeAt(index)
            current.add(index + 1, item)
            _selectedItems.value = current
            updatePageItems(current)
        }
    }

    fun clearItems() {
        _selectedItems.value = emptyList()
        _pageItems.value = emptyList()
        thumbnailCache.evictAll()
    }

    private fun updatePageItems(items: List<MergeItem>) {
        _pageItems.value = pdfMerger.buildPageItems(items)
    }

    suspend fun getPageThumbnail(context: Context, pageItem: MergePageItem): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "${pageItem.sourceItem.uri}_${pageItem.sourcePageIndex}"
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            return@withContext cached
        }

        renderSemaphore.withPermit {
            val bitmap = if (pageItem.isImage) {
                renderImageThumbnail(context, pageItem.sourceItem.uri)
            } else {
                renderPdfThumbnail(context, pageItem.sourceItem.uri, pageItem.sourcePageIndex)
            }

            if (bitmap != null) {
                thumbnailCache.put(cacheKey, bitmap)
            }
            bitmap
        }
    }

    private fun renderImageThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)

                var sampleSize = 1
                val targetDim = 300
                while (options.outWidth / sampleSize > targetDim || options.outHeight / sampleSize > targetDim) {
                    sampleSize *= 2
                }

                context.contentResolver.openInputStream(uri)?.use { stream2 ->
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream2, null, decodeOptions)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun renderPdfThumbnail(context: Context, uri: Uri, pageIndex: Int): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        return try {
            pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
            page = renderer.openPage(pageIndex)

            val width = 240
            val height = (width * (page.height.toFloat() / page.width.toFloat())).toInt().coerceIn(180, 360)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            null
        } finally {
            try {
                page?.close()
                renderer?.close()
                pfd?.close()
            } catch (e: Exception) {
                // Ignore cleanup error
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        thumbnailCache.evictAll()
    }
}
