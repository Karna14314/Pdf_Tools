package com.yourname.pdftoolkit.domain.operations

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class PdfMergerTest {

    @Mock
    lateinit var context: Context

    @Mock
    lateinit var contentResolver: ContentResolver

    @Mock
    lateinit var uri1: Uri
    @Mock
    lateinit var uri2: Uri
    @Mock
    lateinit var uri3: Uri

    private lateinit var pdfMerger: PdfMerger
    private lateinit var autoCloseable: AutoCloseable

    @Before
    fun setup() {
        autoCloseable = MockitoAnnotations.openMocks(this)
        pdfMerger = PdfMerger()

        // Setup context to return content resolver
        `when`(context.contentResolver).thenReturn(contentResolver)
    }

    @After
    fun tearDown() {
        autoCloseable.close()
    }

    private fun createDummyPdfBytes(pageCount: Int): ByteArray {
        val document = PDDocument()
        repeat(pageCount) {
            document.addPage(PDPage())
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun testMergePdfs_PerformanceAndCorrectness() = runTest {
        // Prepare dummy PDFs
        val pdf1Bytes = createDummyPdfBytes(1)
        val pdf2Bytes = createDummyPdfBytes(2)
        val pdf3Bytes = createDummyPdfBytes(3)
        val totalExpectedPages = 1 + 2 + 3

        // Mock openInputStream to return fresh streams for the PDFs
        // Note: The current implementation calls openInputStream multiple times for the same URI.
        // We need to return a NEW InputStream every time.

        `when`(contentResolver.openInputStream(uri1)).thenAnswer { ByteArrayInputStream(pdf1Bytes) }
        `when`(contentResolver.openInputStream(uri2)).thenAnswer { ByteArrayInputStream(pdf2Bytes) }
        `when`(contentResolver.openInputStream(uri3)).thenAnswer { ByteArrayInputStream(pdf3Bytes) }

        val inputUris = listOf(uri1, uri2, uri3)
        val outputStream = ByteArrayOutputStream()

        val startTime = System.nanoTime()

        val result = pdfMerger.mergePdfs(context, inputUris, outputStream)

        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000.0

        println("Merge execution time: $durationMs ms")

        result.onFailure {
            println("Merge failed with exception: ${it.message}")
            it.printStackTrace()
        }
        assertTrue("Result should be success: ${result.exceptionOrNull()?.message}", result.isSuccess)

        // Verify output
        val mergedBytes = outputStream.toByteArray()
        assertTrue("Merged PDF should not be empty", mergedBytes.isNotEmpty())

        val mergedDoc = PDDocument.load(mergedBytes)
        assertEquals("Merged PDF should have correct page count", totalExpectedPages, mergedDoc.numberOfPages)
        mergedDoc.close()
    }
}
