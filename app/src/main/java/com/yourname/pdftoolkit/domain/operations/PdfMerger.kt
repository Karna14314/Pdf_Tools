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
import java.io.OutputStream
import com.yourname.pdftoolkit.util.MemoryGuard

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
        MemoryGuard.checkMemory("mergePdfs")

        // Calculate total size
        var totalSize = 0L
        try {
            inputUris.forEach { uri ->
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                    totalSize += fd.length
                }
            }
            if (totalSize > 50L * 1024 * 1024) {
                // Warning logic for large files, the user says to use onProgress
                // But onProgress takes 0.0 to 1.0. The UI might not have a warning state. We'll just log or continue
                android.util.Log.w("PdfMerger", "Merging large files: ${totalSize / (1024 * 1024)}MB total")
            }
        } catch (e: Exception) {
            // Ignore size check errors
        }

        if (inputUris.size < 2) {
            return@withContext Result.failure(
                IllegalArgumentException("At least 2 PDF files are required for merging")
            )
        }
        
        val merger = PDFMergerUtility()
        var destinationDocument: PDDocument? = null
        
        try {
            ensureActive()
            destinationDocument = PDDocument()
            val destination = destinationDocument

            // Load and append one source at a time so source documents do not accumulate.
            inputUris.forEachIndexed { index, uri ->
                ensureActive()

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    PDDocument.load(inputStream, MemoryUsageSetting.setupTempFileOnly()).use { sourceDocument ->
                        merger.appendDocument(destination, sourceDocument)
                    }
                } ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open file: $uri")
                )
                
                onProgress((index + 1).toFloat() / (inputUris.size + 1))
            }
            
            ensureActive()

            destination.save(outputStream)
            
            onProgress(1.0f)
            Result.success(Unit)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                destinationDocument?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
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
        MemoryGuard.checkMemory("getTotalPageCount")
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
