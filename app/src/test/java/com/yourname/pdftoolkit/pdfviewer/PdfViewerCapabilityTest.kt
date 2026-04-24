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
