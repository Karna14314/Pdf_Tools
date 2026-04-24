package com.yourname.pdftoolkit.ui.pdfviewer.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentManager
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.ui.pdfviewer.PdfViewerCapability
import com.yourname.pdftoolkit.ui.pdfviewer.PdfEngineCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Factory for creating the appropriate PDF viewer engine based on build flavor and device capability.
 *
 * Routing logic:
 * - F-Droid/FOSS flavor: Always use MuPDF (no proprietary dependencies)
 * - Play Store flavor, API 31+ with SDK Extension 13: Use AndroidX PdfViewerFragment
 * - Play Store flavor, older devices: Use PdfBoxFallbackEngine
 *
 * CRITICAL: AndroidXPdfViewerEngine is instantiated via reflection to avoid ClassNotFoundException
 * in FOSS flavors that don't include androidx.pdf dependency.
 */
object PdfEngineFactory {

    /**
     * Create a PDF viewer engine based on build configuration.
     * Falls back to PdfBox if primary engine fails to initialize.
     *
     * Note: This is the legacy signature for simple usage. For AndroidX engine,
     * use createWithFragmentManager() instead.
     */
    fun create(context: Context, uri: Uri): PdfViewerEngine {
        return when (BuildConfig.FLAVOR) {
            "fdroid", "opensource" -> createMuPdfEngine(context, uri)
            else -> createPdfBoxEngine(context, uri)
        }
    }

    /**
     * Create with automatic fallback to PdfBox on failure.
     *
     * Legacy signature - always returns an engine that doesn't require FragmentManager.
     * For native AndroidX support with FragmentManager, use createWithFragmentManager().
     */
    fun createWithFallback(context: Context, uri: Uri): PdfViewerEngine {
        val primaryEngine = create(context, uri)

        return if (primaryEngine.isAvailable()) {
            primaryEngine
        } else {
            PdfBoxFallbackEngine(context, uri)
        }
    }

    suspend fun createWithFragmentManager(
        context: Context,
        uri: Uri,
        fragmentManager: FragmentManager,
        containerId: Int,
        callbacks: PdfEngineCallbacks
    ): PdfViewerEngine {
        // FOSS flavors always use MuPDF (no proprietary Google libraries)
        if (BuildConfig.FLAVOR == "fdroid" || BuildConfig.FLAVOR == "opensource") {
            return createMuPdfEngine(context, uri)
        }

        // Play Store flavor: try native first if supported
        return if (PdfViewerCapability.isNativeViewerSupported()) {
            try {
                withContext(Dispatchers.Default) {
                    createAndroidXEngineViaReflection(context, fragmentManager, containerId, callbacks)
                }
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) to handle OOM, LinkageError, etc.
                Log.e("PdfEngineFactory", "AndroidX engine creation failed: ${e.javaClass.simpleName}", e)
                callbacks.onError(e)
                callbacks.onFallbackRequired()
                createPdfBoxEngine(context, uri)
            }
        } else {
            createPdfBoxEngine(context, uri)
        }
    }

    /**
     * Check if native AndroidX PDF viewer is available on this device.
     * Convenience method wrapping PdfViewerCapability.
     */
    fun isNativeViewerAvailable(): Boolean {
        return PdfViewerCapability.isNativeViewerSupported()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @Suppress("UNCHECKED_CAST")
    private fun createAndroidXEngineViaReflection(
        context: Context,
        fragmentManager: FragmentManager,
        containerId: Int,
        callbacks: PdfEngineCallbacks
    ): PdfViewerEngine {
        // Load the AndroidXPdfViewerEngine class via reflection
        val engineClass = Class.forName(
            "com.yourname.pdftoolkit.ui.pdfviewer.engine.AndroidXPdfViewerEngine"
        )

        // Find the constructor: (Context, FragmentManager, Int, PdfEngineCallbacks)
        val constructor = engineClass.getConstructor(
            Context::class.java,
            FragmentManager::class.java,
            Int::class.java,
            PdfEngineCallbacks::class.java
        )

        // Instantiate the engine
        return constructor.newInstance(
            context,
            fragmentManager,
            containerId,
            callbacks
        ) as PdfViewerEngine
    }

    private fun createPdfBoxEngine(context: Context, uri: Uri): PdfViewerEngine {
        return PdfBoxFallbackEngine(context, uri)
    }

    private fun createMuPdfEngine(context: Context, uri: Uri): PdfViewerEngine {
        return try {
            // Try MuPDF first for F-Droid
            MuPdfViewerEngine(context, uri)
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) for OOM, etc.
            Log.e("PdfEngineFactory", "MuPDF engine failed, falling back to PdfBox: ${e.message}")
            PdfBoxFallbackEngine(context, uri)
        }
    }

    @androidx.annotation.VisibleForTesting
    fun createForTest(
        context: Context,
        sdkInt: Int,
        extensionVersion: Int,
        isFoss: Boolean,
        callbacks: PdfEngineCallbacks,
        forceReflectionFailure: Boolean = false
    ): PdfViewerEngine {
        val dummyUri = Uri.EMPTY
        if (isFoss) return MuPdfViewerEngine(context, dummyUri)
        val isSupported = sdkInt >= 31 && extensionVersion >= 13
        if (!isSupported) return PdfBoxFallbackEngine(context, dummyUri)
        if (forceReflectionFailure) return PdfBoxFallbackEngine(context, dummyUri)
        return PdfBoxFallbackEngine(context, dummyUri)
    }
}
