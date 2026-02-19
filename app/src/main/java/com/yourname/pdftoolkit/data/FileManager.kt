package com.yourname.pdftoolkit.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.pow

/**
 * Data class representing a selected PDF file with metadata.
 */
data class PdfFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val formattedSize: String
)

/**
 * Sealed class representing file operation results.
 */
sealed class FileResult<out T> {
    data class Success<T>(val data: T) : FileResult<T>()
    data class Error(val message: String, val exception: Throwable? = null) : FileResult<Nothing>()
}

/**
 * Utility class for file operations using Storage Access Framework.
 */
object FileManager {
    
    /**
     * Get file information from a content URI.
     */
    fun getFileInfo(context: Context, uri: Uri): PdfFileInfo? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "Unknown.pdf"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    
                    PdfFileInfo(
                        uri = uri,
                        name = name,
                        size = size,
                        formattedSize = formatFileSize(size)
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Format file size to human-readable format.
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
        val index = digitGroups.coerceIn(0, units.size - 1)

        val value = bytes / 1024.0.pow(index.toDouble())

        return if (index < 2) {
            // B or KB
            if (index == 0) {
                "$bytes ${units[index]}"
            } else {
                // KB: Format to 2 decimal places, then strip trailing zeros
                val formatted = String.format(java.util.Locale.US, "%.2f", value)
                val cleanFormatted = formatted.trimEnd('0').trimEnd('.')
                "$cleanFormatted ${units[index]}"
            }
        } else {
            // MB, GB...: Keep 2 decimal places
            String.format(java.util.Locale.US, "%.2f %s", value, units[index])
        }
    }
    
    /**
     * Open an input stream for reading a file.
     */
    fun openInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Open an output stream for writing a file.
     */
    fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        return try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Copy a file to the app's cache directory.
     */
    suspend fun copyToCache(context: Context, uri: Uri, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val cacheFile = File(context.cacheDir, fileName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                cacheFile
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Clear the app's cache directory.
     */
    suspend fun clearCache(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                context.cacheDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
    
    /**
     * Get available storage space in bytes.
     */
    fun getAvailableStorage(context: Context): Long {
        return try {
            context.cacheDir.freeSpace
        } catch (e: Exception) {
            0L
        }
    }
    
    /**
     * Check if there's enough storage for an operation.
     */
    fun hasEnoughStorage(context: Context, requiredBytes: Long): Boolean {
        val available = getAvailableStorage(context)
        // Require at least double the needed space for safety
        return available > requiredBytes * 2
    }
    
    /**
     * Validate if a URI points to a valid PDF file.
     */
    fun isValidPdf(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType == "application/pdf"
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Generate a unique output file name.
     */
    fun generateOutputFileName(prefix: String, extension: String = "pdf"): String {
        val timestamp = System.currentTimeMillis()
        return "${prefix}_$timestamp.$extension"
    }
}
