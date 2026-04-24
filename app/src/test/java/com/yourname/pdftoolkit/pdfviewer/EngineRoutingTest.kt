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
