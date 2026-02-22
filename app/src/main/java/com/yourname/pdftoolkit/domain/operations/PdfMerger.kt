package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Handles PDF merge operations.
 * Combines multiple PDF files into a single document.
 */
class PdfMerger {
    
    /**
     * Merge multiple PDF files into one.
     * 
     * @param context Android context for content resolver access
     * @param inputUris List of URIs pointing to PDFs to merge (in order)
     * @param outputStream Output stream to write the merged PDF
     * @param onProgress Callback for progress updates (0.0 to 1.0)
     * @return Result indicating success or failure
     */
    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (inputUris.size < 2) {
            return@withContext Result.failure(
                IllegalArgumentException("At least 2 PDF files are required for merging")
            )
        }
        
        val merger = PDFMergerUtility()
        val inputStreams = mutableListOf<InputStream>()
        
        try {
            ensureActive()

            // Load all input documents
            inputUris.forEachIndexed { index, uri ->
                ensureActive()

                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(
                        IllegalStateException("Cannot open file: $uri")
                    )
                
                inputStreams.add(inputStream)
                merger.addSource(inputStream)
                
                onProgress((index + 1).toFloat() / (inputUris.size + 1))
            }
            
            ensureActive()

            // Perform merge
            merger.destinationStream = outputStream
            // Use temp file memory setting to prevent OOM during merge of large files
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            
            onProgress(1.0f)
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // Clean up all loaded streams
            inputStreams.forEach { stream ->
                try {
                    stream.close()
                } catch (e: Exception) {
                    // Ignore cleanup errors
                }
            }
        }
    }
    
    /**
     * Get the total page count of multiple PDFs.
     */
    suspend fun getTotalPageCount(
        context: Context,
        uris: List<Uri>
    ): Int = withContext(Dispatchers.IO) {
        var totalPages = 0
        
        uris.forEach { uri ->
            ensureActive()
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Use memory usage setting to avoid loading full document into RAM
                    PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                        totalPages += document.numberOfPages
                    }
                }
            } catch (e: Exception) {
                // Skip files that can't be read
            }
        }
        
        totalPages
    }
}
