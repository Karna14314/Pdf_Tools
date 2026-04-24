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
