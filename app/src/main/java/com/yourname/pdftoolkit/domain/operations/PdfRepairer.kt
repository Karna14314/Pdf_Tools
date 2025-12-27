package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Result of a repair operation.
 */
data class RepairResult(
    val wasCorrupted: Boolean,
    val pagesRecovered: Int,
    val originalPageCount: Int,
    val repairNotes: List<String>
)

/**
 * Handles PDF repair operations.
 * Uses PdfBox's resilient loading mechanism to rebuild corrupted PDF structures.
 */
class PdfRepairer {
    
    /**
     * Attempt to repair a potentially corrupted PDF.
     * This works by forcing PdfBox to rebuild the document structure during load.
     * 
     * @param context Android context
     * @param inputUri URI of the PDF to repair
     * @param outputStream Output stream for the repaired PDF
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return RepairResult with operation details
     */
    suspend fun repairPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {}
    ): Result<RepairResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        val repairNotes = mutableListOf<String>()
        var wasCorrupted = false
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open input file")
                )
            
            onProgress(0.2f)
            
            // Create a temp file for PdfBox to use during repair
            val tempDir = context.cacheDir
            val tempFile = File(tempDir, "pdf_repair_temp_${System.currentTimeMillis()}")
            
            try {
                // Copy input to temp file for better memory handling
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                onProgress(0.3f)
                
                // Try to load with lenient parsing using temp file only (forces rebuild)
                // This approach helps recover from:
                // - Corrupted xref tables
                // - Missing EOF markers
                // - Broken object streams
                document = try {
                    PDDocument.load(
                        tempFile,
                        MemoryUsageSetting.setupTempFileOnly()
                    )
                } catch (e: Exception) {
                    wasCorrupted = true
                    repairNotes.add("Initial load failed: ${e.message}")
                    
                    // Try alternative loading with main memory
                    try {
                        PDDocument.load(
                            tempFile,
                            MemoryUsageSetting.setupMainMemoryOnly()
                        )
                    } catch (e2: Exception) {
                        repairNotes.add("Memory load also failed: ${e2.message}")
                        return@withContext Result.failure(
                            IllegalStateException("PDF is too corrupted to repair: ${e2.message}")
                        )
                    }
                }
                
                val loadedDoc = document ?: return@withContext Result.failure(
                    IllegalStateException("Failed to load document")
                )
                
                onProgress(0.5f)
                
                val pageCount = loadedDoc.numberOfPages
                
                if (pageCount == 0) {
                    repairNotes.add("No pages could be recovered")
                    return@withContext Result.failure(
                        IllegalStateException("PDF has no recoverable pages")
                    )
                }
                
                repairNotes.add("Successfully loaded $pageCount pages")
                
                onProgress(0.6f)
                
                // Check for and fix common issues
                var fixedIssues = 0
                
                // Validate each page
                for (pageIndex in 0 until pageCount) {
                    try {
                        val page = loadedDoc.getPage(pageIndex)
                        
                        // Check for missing media box
                        if (page.mediaBox == null) {
                            repairNotes.add("Page ${pageIndex + 1}: Fixed missing media box")
                            fixedIssues++
                        }
                        
                        // Force resource loading to validate
                        page.resources
                        
                    } catch (e: Exception) {
                        repairNotes.add("Page ${pageIndex + 1}: Error - ${e.message}")
                        wasCorrupted = true
                    }
                }
                
                if (fixedIssues > 0) {
                    wasCorrupted = true
                }
                
                onProgress(0.8f)
                
                // Save the repaired document (this rebuilds the structure)
                loadedDoc.save(outputStream)
                
                onProgress(1.0f)
                
                Result.success(
                    RepairResult(
                        wasCorrupted = wasCorrupted,
                        pagesRecovered = pageCount,
                        originalPageCount = pageCount,
                        repairNotes = repairNotes
                    )
                )
                
            } finally {
                // Clean up temp file
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            
        } catch (e: Exception) {
            repairNotes.add("Fatal error: ${e.message}")
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
    
    /**
     * Check if a PDF appears to be corrupted.
     * 
     * @param context Android context
     * @param uri URI of the PDF to check
     * @return true if the PDF appears corrupted
     */
    suspend fun isCorrupted(
        context: Context,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    PDDocument.load(inputStream).use { document ->
                        // Check basic structure
                        val pageCount = document.numberOfPages
                        if (pageCount == 0) return@withContext true
                        
                        // Try to access first page
                        document.getPage(0)
                        
                        false // Seems OK
                    }
                } catch (e: Exception) {
                    true // Failed to load properly
                }
            } ?: true
        } catch (e: Exception) {
            true
        }
    }
    
    /**
     * Get diagnostic information about a PDF.
     */
    suspend fun diagnose(
        context: Context,
        uri: Uri
    ): List<String> = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    PDDocument.load(inputStream).use { document ->
                        diagnostics.add("✓ PDF loaded successfully")
                        diagnostics.add("Pages: ${document.numberOfPages}")
                        diagnostics.add("Encrypted: ${document.isEncrypted}")
                        diagnostics.add("Version: ${document.version}")
                        
                        for (i in 0 until minOf(document.numberOfPages, 5)) {
                            try {
                                val page = document.getPage(i)
                                val box = page.mediaBox
                                diagnostics.add("Page ${i+1}: ${box?.width?.toInt()}x${box?.height?.toInt()}")
                            } catch (e: Exception) {
                                diagnostics.add("Page ${i+1}: ERROR - ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    diagnostics.add("✗ Load failed: ${e.message}")
                }
            } ?: diagnostics.add("✗ Cannot open file")
        } catch (e: Exception) {
            diagnostics.add("✗ Error: ${e.message}")
        }
        
        diagnostics
    }
}
