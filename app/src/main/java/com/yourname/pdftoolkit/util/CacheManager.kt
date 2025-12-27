package com.yourname.pdftoolkit.util

import android.content.Context
import java.io.File

/**
 * Manages app cache to prevent storage bloat.
 * Cleans up temporary files from PDF operations, camera captures, and scans.
 */
object CacheManager {
    
    private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val MAX_CACHE_SIZE_MB = 100L // Maximum cache size in MB
    
    /**
     * Clear all app cache.
     * @return Size of cleared cache in bytes
     */
    fun clearAllCache(context: Context): Long {
        var clearedSize = 0L
        
        // Clear main cache directory
        context.cacheDir.listFiles()?.forEach { file ->
            clearedSize += deleteRecursively(file)
        }
        
        // Clear external cache if available
        context.externalCacheDir?.listFiles()?.forEach { file ->
            clearedSize += deleteRecursively(file)
        }
        
        return clearedSize
    }
    
    /**
     * Clear old cache files (older than MAX_CACHE_AGE).
     * @return Size of cleared cache in bytes
     */
    fun clearOldCache(context: Context): Long {
        val cutoffTime = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        var clearedSize = 0L
        
        // Clear old files from main cache
        clearedSize += clearOldFilesInDir(context.cacheDir, cutoffTime)
        
        // Clear old files from external cache
        context.externalCacheDir?.let {
            clearedSize += clearOldFilesInDir(it, cutoffTime)
        }
        
        return clearedSize
    }
    
    /**
     * Clear specific cache directories used by PDF operations.
     */
    fun clearPdfOperationsCache(context: Context): Long {
        var clearedSize = 0L
        
        // Clear scans directory
        val scansDir = File(context.cacheDir, "scans")
        if (scansDir.exists()) {
            clearedSize += deleteRecursively(scansDir)
            scansDir.mkdirs() // Recreate empty directory
        }
        
        // Clear camera captures (capture_*.jpg files)
        context.cacheDir.listFiles()?.filter { 
            it.name.startsWith("capture_") && it.name.endsWith(".jpg")
        }?.forEach { file ->
            clearedSize += file.length()
            file.delete()
        }
        
        // Clear temp PDF files
        context.cacheDir.listFiles()?.filter {
            it.name.endsWith(".pdf") || it.name.startsWith("temp_")
        }?.forEach { file ->
            clearedSize += file.length()
            file.delete()
        }
        
        return clearedSize
    }
    
    /**
     * Get total cache size.
     */
    fun getCacheSize(context: Context): Long {
        var totalSize = 0L
        
        totalSize += getDirSize(context.cacheDir)
        context.externalCacheDir?.let {
            totalSize += getDirSize(it)
        }
        
        return totalSize
    }
    
    /**
     * Get formatted cache size string.
     */
    fun getFormattedCacheSize(context: Context): String {
        val sizeBytes = getCacheSize(context)
        return formatSize(sizeBytes)
    }
    
    /**
     * Check if cache is too large and should be cleaned.
     */
    fun shouldCleanCache(context: Context): Boolean {
        val cacheSizeMB = getCacheSize(context) / (1024 * 1024)
        return cacheSizeMB > MAX_CACHE_SIZE_MB
    }
    
    /**
     * Auto-clean cache if it exceeds the maximum size.
     * Call this on app startup or after operations.
     */
    fun autoCleanIfNeeded(context: Context): Long {
        return if (shouldCleanCache(context)) {
            clearOldCache(context)
        } else {
            0L
        }
    }
    
    private fun clearOldFilesInDir(dir: File, cutoffTime: Long): Long {
        var clearedSize = 0L
        
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                clearedSize += clearOldFilesInDir(file, cutoffTime)
                // Remove empty directories
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                }
            } else if (file.lastModified() < cutoffTime) {
                clearedSize += file.length()
                file.delete()
            }
        }
        
        return clearedSize
    }
    
    private fun deleteRecursively(file: File): Long {
        var size = 0L
        
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                size += deleteRecursively(child)
            }
        } else {
            size = file.length()
        }
        
        file.delete()
        return size
    }
    
    private fun getDirSize(dir: File): Long {
        var size = 0L
        
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) {
                getDirSize(file)
            } else {
                file.length()
            }
        }
        
        return size
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
