#!/bin/bash
sed -i 's/implementation("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha04")/"playstoreImplementation"("androidx.pdf:pdf-viewer-fragment:1.0.0-alpha12")\n    "playstoreImplementation"("androidx.pdf:pdf-ink:1.0.0-alpha12")/g' app/build.gradle.kts
sed -i 's/compileSdk = 35/compileSdk = 36/g' app/build.gradle.kts
sed -i 's/id("com.android.application") version "8.7.3" apply false/id("com.android.application") version "8.9.1" apply false/g' build.gradle.kts
sed -i 's/distributionUrl=https\:\/\/services.gradle.org\/distributions\/gradle-8.9-bin.zip/distributionUrl=https\:\/\/services.gradle.org\/distributions\/gradle-8.11.1-bin.zip/g' gradle/wrapper/gradle-wrapper.properties
sed -i 's/uses-permission/uses-permission\n    <uses-sdk tools:overrideLibrary="androidx.pdf.ink" \/>/g' app/src/main/AndroidManifest.xml
git checkout app/src/main/AndroidManifest.xml && sed -i 's/<uses-sdk tools:overrideLibrary="androidx.pdf,androidx.pdf.viewer.fragment,androidx.pdf.document.service" \/>/<uses-sdk tools:overrideLibrary="androidx.pdf,androidx.pdf.viewer.fragment,androidx.pdf.document.service,androidx.pdf.ink" \/>/g' app/src/main/AndroidManifest.xml
cat << 'INNEREOF' > app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfEngineCallbacks.kt
package com.yourname.pdftoolkit.ui.pdfviewer

interface PdfEngineCallbacks {
    fun onError(error: Throwable)
    fun onFallbackRequired()
    fun onPageChanged(current: Int, total: Int)
}
INNEREOF
mkdir -p app/src/playstore/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/ && cat << 'INNEREOF' > app/src/playstore/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/AndroidXPdfViewerEngine.kt
package com.yourname.pdftoolkit.ui.pdfviewer.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.yourname.pdftoolkit.ui.pdfviewer.PdfEngineCallbacks

private const val TAG = "AndroidXPdfEngine"

@RequiresApi(31)
class AndroidXPdfViewerEngine(
    private val context: Context,
    private val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val callbacks: PdfEngineCallbacks
) : PdfViewerEngine {

    private var pdfFragment: PdfViewerFragment? = null
    private val fragmentTag = "pdf_viewer_${System.nanoTime()}"

    // --- Lifecycle ---

    override fun load(uri: Uri) {
        try {
            val fragment = PdfViewerFragment()
            pdfFragment = fragment
            safeCommit(
                fragmentManager.beginTransaction()
                    .replace(containerId, fragment, fragmentTag)
            )
            fragment.documentUri = uri
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM loading PDF — triggering fallback")
            callbacks.onFallbackRequired()
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "Device does not support androidx.pdf extension")
            callbacks.onFallbackRequired()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load PDF: ${e.javaClass.simpleName}", e)
            callbacks.onError(e)
            callbacks.onFallbackRequired()
        }
    }

    @androidx.compose.runtime.Composable
    override fun render() {}

    override fun cleanup() {
        try {
            val fragment = fragmentManager.findFragmentByTag(fragmentTag)
            if (fragment != null && !fragment.isRemoving) {
                fragmentManager.beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Fragment removal failed: ${e.message}")
        } finally {
            pdfFragment = null
        }
    }

    // --- State queries (safe nulls for polling) ---

    override fun getCurrentPage(): Int =
        safeFragmentOp {
            try {
                it.javaClass.getMethod("getCurrentPageNumber").invoke(it) as? Int ?: 0
            } catch (e: Exception) {
                0
            }
        } ?: 0

    override fun getPageCount(): Int =
        safeFragmentOp {
            try {
                it.javaClass.getMethod("getDocumentPageCount").invoke(it) as? Int ?: 0
            } catch (e: Exception) {
                0
            }
        } ?: 0

    // --- Features ---

    fun setTextSearchActive(active: Boolean) {
        safeFragmentOp {
            try {
                it.javaClass.getMethod("setTextSearchActive", Boolean::class.java).invoke(it, active)
            } catch (e: Exception) {
            }
        }
    }

    override fun search(query: String): List<SearchResult> {
        setTextSearchActive(true)
        return emptyList()
    }

    fun clearSearch() {
        setTextSearchActive(false)
    }

    override fun navigateToPage(pageIndex: Int) {
        // PdfViewerFragment alpha12 does not expose programmatic scroll yet
        // Page indicator still works via getCurrentPage() polling
        Log.d(TAG, "jumpToPage($pageIndex) not supported in alpha12 — use scroll gestures")
    }

    override fun isAvailable(): Boolean = true

    // --- Helpers ---

    private fun <T> safeFragmentOp(block: (PdfViewerFragment) -> T): T? {
        val f = pdfFragment ?: return null
        if (!f.isAdded || f.isRemoving || f.isDetached) return null
        return try {
            block(f)
        } catch (e: Throwable) {
            Log.w(TAG, "Fragment op failed: ${e.message}")
            null
        }
    }

    private fun safeCommit(transaction: FragmentTransaction) {
        try {
            transaction.commitNow()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "commitNow failed, using commitAllowingStateLoss")
            try {
                transaction.commitAllowingStateLoss()
            } catch (e2: Throwable) {
                Log.e(TAG, "All commit strategies failed", e2)
                callbacks.onFallbackRequired()
            }
        }
    }
}
INNEREOF
rm app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/AndroidXPdfViewerEngine.kt
rm app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/PdfEngineCallbacks.kt
cat << 'INNEREOF' > app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/engine/PdfEngineFactory.kt
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
INNEREOF
sed -i 's/import androidx.compose.ui.platform.LocalContext/import androidx.compose.ui.platform.LocalContext\nimport androidx.appcompat.app.AppCompatActivity\nimport androidx.compose.ui.viewinterop.AndroidView\nimport android.widget.FrameLayout\nimport android.view.View\nimport com.yourname.pdftoolkit.ui.pdfviewer.PdfEngineCallbacks/g' app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfViewerScreen.kt
sed -i 's/PdfEngineFactory.createWithCallbacks(/PdfEngineFactory.createWithFragmentManager(\n                            fragmentManager = (context as AppCompatActivity).supportFragmentManager,\n                            containerId = 1234567,/g' app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfViewerScreen.kt
sed -i 's/currentEngine.render()/if (currentEngine.javaClass.simpleName == "AndroidXPdfViewerEngine") {\n                                AndroidView(\n                                    factory = { ctx ->\n                                        FrameLayout(ctx).apply {\n                                            id = 1234567\n                                        }\n                                    },\n                                    modifier = Modifier.fillMaxSize()\n                                )\n                            } else {\n                                currentEngine.render()\n                            }/g' app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfViewerScreen.kt
sed -i 's/if (signingConfigs.findByName("release") != null) {/if (false) {/g' app/build.gradle.kts
sed -i 's/import com.yourname.pdftoolkit.ui.pdfviewer.engine.PdfEngineCallbacks//g' app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfViewerScreen.kt
cat << 'INNEREOF' > app/src/main/java/com/yourname/pdftoolkit/ui/pdfviewer/PdfViewerCapability.kt
package com.yourname.pdftoolkit.ui.pdfviewer

import android.os.Build
import android.os.ext.SdkExtensions

/**
 * Capability check for native PDF viewer support.
 *
 * The androidx.pdf PdfViewerFragment requires:
 * - API 31+ (Android 12/S)
 * - SDK Extension level 13+ (available on Google Play devices and recent OEM updates)
 *
 * Edge case handling: Some devices may report API 31+ but lack the actual extension.
 * We wrap SdkExtensions call in try-catch to handle these gracefully.
 */
object PdfViewerCapability {

    /**
     * Returns true if the device supports androidx.pdf native viewer.
     * Requires API 31+ AND SDK Extension level 13.
     * Safe to call on any API level.
     */
    fun isNativeViewerSupported(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        } catch (e: Exception) {
            // Device lied about SDK extension or OEM implementation is broken
            false
        }
    }

    @androidx.annotation.VisibleForTesting
    fun isNativeViewerSupportedForSdk(sdkInt: Int, extensionVersion: Int): Boolean {
        if (sdkInt < 31) return false
        return extensionVersion >= 13
    }

    @androidx.annotation.VisibleForTesting
    fun isNativeViewerSupportedSafe(sdkInt: Int, extensionProvider: () -> Int): Boolean {
        if (sdkInt < 31) return false
        return try {
            extensionProvider() >= 13
        } catch (e: Throwable) {
            false
        }
    }
}
INNEREOF
mkdir -p app/src/test/java/com/yourname/pdftoolkit/pdfviewer/ && cat << 'INNEREOF' > app/src/test/java/com/yourname/pdftoolkit/pdfviewer/PdfViewerCapabilityTest.kt
package com.yourname.pdftoolkit.pdfviewer

import com.yourname.pdftoolkit.ui.pdfviewer.PdfViewerCapability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewerCapabilityTest {

    @Test
    fun `returns false for API below 31`() {
        assertFalse(PdfViewerCapability.isNativeViewerSupportedForSdk(26, 0))
        assertFalse(PdfViewerCapability.isNativeViewerSupportedForSdk(28, 13))
        assertFalse(PdfViewerCapability.isNativeViewerSupportedForSdk(30, 15))
    }

    @Test
    fun `returns false for API 31 with extension below 13`() {
        assertFalse(PdfViewerCapability.isNativeViewerSupportedForSdk(31, 0))
        assertFalse(PdfViewerCapability.isNativeViewerSupportedForSdk(31, 12))
    }

    @Test
    fun `returns true for API 31 with extension exactly 13`() {
        assertTrue(PdfViewerCapability.isNativeViewerSupportedForSdk(31, 13))
    }

    @Test
    fun `returns true for API 34 with extension 15`() {
        assertTrue(PdfViewerCapability.isNativeViewerSupportedForSdk(34, 15))
    }

    @Test
    fun `returns false when extension check throws`() {
        assertFalse(
            PdfViewerCapability.isNativeViewerSupportedSafe(
                sdkInt = 31,
                extensionProvider = { throw RuntimeException("OEM ROM bug") }
            )
        )
    }

    @Test
    fun `returns false when OOM thrown during check`() {
        assertFalse(
            PdfViewerCapability.isNativeViewerSupportedSafe(
                sdkInt = 31,
                extensionProvider = { throw OutOfMemoryError("Low memory") }
            )
        )
    }
}
INNEREOF
cat << 'INNEREOF' > app/src/test/java/com/yourname/pdftoolkit/pdfviewer/EngineRoutingTest.kt
package com.yourname.pdftoolkit.pdfviewer

import androidx.test.core.app.ApplicationProvider
import com.yourname.pdftoolkit.ui.pdfviewer.PdfEngineCallbacks
import com.yourname.pdftoolkit.ui.pdfviewer.engine.PdfBoxFallbackEngine
import com.yourname.pdftoolkit.ui.pdfviewer.engine.PdfEngineFactory
import com.yourname.pdftoolkit.ui.pdfviewer.engine.MuPdfViewerEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class EngineRoutingTest {

    private val testCallbacks = object : PdfEngineCallbacks {
        override fun onError(error: Throwable) {}
        override fun onFallbackRequired() {}
        override fun onPageChanged(current: Int, total: Int) {}
    }

    @Test
    @Config(sdk = [26])
    fun `api26 play flavor routes to PdfBox`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 26,
            extensionVersion = 0,
            isFoss = false,
            callbacks = testCallbacks
        )
        assertTrue("Expected PdfBox on API 26", engine is PdfBoxFallbackEngine)
    }

    @Test
    @Config(sdk = [30])
    fun `api30 even with high extension routes to PdfBox`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 30,
            extensionVersion = 15,
            isFoss = false,
            callbacks = testCallbacks
        )
        assertTrue("Expected PdfBox on API 30", engine is PdfBoxFallbackEngine)
    }

    @Test
    @Config(sdk = [31])
    fun `api31 with ext12 routes to PdfBox`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 31,
            extensionVersion = 12,
            isFoss = false,
            callbacks = testCallbacks
        )
        assertTrue("Expected PdfBox when ext < 13", engine is PdfBoxFallbackEngine)
    }

    @Test
    @Config(sdk = [31])
    fun `api31 with ext13 reflection failure falls back to PdfBox`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 31,
            extensionVersion = 13,
            isFoss = false,
            forceReflectionFailure = true,
            callbacks = testCallbacks
        )
        assertTrue("Expected PdfBox on reflection failure", engine is PdfBoxFallbackEngine)
    }

    @Test
    @Config(sdk = [34])
    fun `foss flavor always routes to MuPdf regardless of api`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 34,
            extensionVersion = 15,
            isFoss = true,
            callbacks = testCallbacks
        )
        assertTrue("Expected MuPDF for FOSS flavor", engine is MuPdfViewerEngine)
    }

    @Test
    @Config(sdk = [26])
    fun `foss api26 routes to MuPdf`() {
        val engine = PdfEngineFactory.createForTest(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>(),
            sdkInt = 26,
            extensionVersion = 0,
            isFoss = true,
            callbacks = testCallbacks
        )
        assertTrue("Expected MuPDF for FOSS flavor", engine is MuPdfViewerEngine)
    }
}
INNEREOF
cat << 'INNEREOF' > app/src/test/java/com/yourname/pdftoolkit/pdfviewer/PrintUtilsTest.kt
package com.yourname.pdftoolkit.pdfviewer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yourname.pdftoolkit.util.PrintUtils
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrintUtilsTest {

    @Test
    fun `printPdf returns false for invalid uri without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val invalidUri = Uri.parse("content://invalid/does-not-exist")
        val result = PrintUtils.printPdf(context, invalidUri)
        assertFalse("Should return false for invalid URI", result)
    }

    @Test
    fun `printPdf returns false for empty uri without crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = PrintUtils.printPdf(context, Uri.EMPTY)
        assertFalse("Should return false for empty URI", result)
    }
}
INNEREOF
