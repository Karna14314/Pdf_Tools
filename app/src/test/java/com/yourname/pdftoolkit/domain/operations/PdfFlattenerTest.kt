package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class PdfFlattenerTest {

    private lateinit var flattener: PdfFlattener
    private lateinit var tempDir: File

    @Before
    fun setup() {
        flattener = PdfFlattener()
        tempDir = File(System.getProperty("java.io.tmpdir"), "pdf_test_dir")
        if (!tempDir.exists()) tempDir.mkdirs()
    }

    @Test
    fun `flattenPdf should produce valid pdf file`() = runBlocking {
        // Create a basic PDF
        val sourceFile = File(tempDir, "source.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        FileOutputStream(sourceFile).use {
            doc.save(it)
        }
        doc.close()

        val sourceUri = Uri.fromFile(sourceFile)
        val outputFile = File(tempDir, "output.pdf")
        val outputUri = Uri.fromFile(outputFile)

        val result = flattener.flattenPdf(
            context = RuntimeEnvironment.getApplication(),
            inputUri = sourceUri,
            outputUri = outputUri
        )

        assertTrue("Flatten operation should succeed", result.success)
        assertTrue("Output file should exist", outputFile.exists())
        assertTrue("Output file should not be empty", outputFile.length() > 0)

        // Verify it can be opened
        val verifiedDoc = PDDocument.load(outputFile)
        assertEquals("Should have 1 page", 1, verifiedDoc.numberOfPages)
        verifiedDoc.close()
    }
}
