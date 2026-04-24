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
