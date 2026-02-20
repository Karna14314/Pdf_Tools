package com.yourname.pdftoolkit

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.yourname.pdftoolkit.util.CacheManager
import com.yourname.pdftoolkit.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Application class for PDF Toolkit.
 * Initializes PdfBox-Android on startup and manages cache cleanup.
 */
class PdfToolkitApplication : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize PdfBox-Android
        PDFBoxResourceLoader.init(applicationContext)
        
        // Initialize theme synchronously to avoid flicker
        runBlocking {
            val themeMode = ThemeManager.getThemeMode(applicationContext).first()
            ThemeManager.applyTheme(themeMode)
            Log.d("PdfToolkit", "Theme initialized: $themeMode")
        }
        
        // Auto-clean cache on startup (runs in background)
        applicationScope.launch {
            try {
                // Clean old cache files (older than 24 hours)
                val cleanedBytes = CacheManager.clearOldCache(applicationContext)
                if (cleanedBytes > 0) {
                    Log.d("PdfToolkit", "Auto-cleaned ${cleanedBytes / 1024} KB from cache")
                }
                
                // Also clean PDF operation temp files
                CacheManager.clearPdfOperationsCache(applicationContext)
            } catch (e: Exception) {
                Log.e("PdfToolkit", "Cache cleanup failed", e)
            }
        }
    }
}