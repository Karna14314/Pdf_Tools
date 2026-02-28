package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PdfCompressorTest {

    private lateinit var context: Context
    private lateinit var pdfCompressor: PdfCompressor

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        pdfCompressor = PdfCompressor()

        // Ensure cache dir exists
        context.cacheDir.mkdirs()
    }

    @Test
    fun testCompressPdf_basic() = runBlocking {
        // Create a simple PDF
        val inputFile = File(context.cacheDir, "test_input.pdf")
        createTestPdf(inputFile)

        val outputFile = File(context.cacheDir, "test_output.pdf")
        val outputStream = FileOutputStream(outputFile)

        val result = pdfCompressor.compressPdf(
            context = context,
            inputUri = Uri.fromFile(inputFile),
            outputStream = outputStream,
            level = CompressionLevel.MEDIUM
        )

        outputStream.close()

        assertTrue("Compression should succeed", result.isSuccess)
        val compressionResult = result.getOrNull()!!

        // Verify output exists and is valid PDF
        assertTrue(outputFile.exists())
        val outputDoc = PDDocument.load(outputFile)
        assertEquals(1, outputDoc.numberOfPages)
        outputDoc.close()
    }

    @Test
    fun testCompressPdf_cancellation() = runBlocking {
        // Create a PDF large enough to take some time
        val inputFile = File(context.cacheDir, "test_large.pdf")
        // Increase pages to ensure it runs long enough
        createLargeTestPdf(inputFile, pages = 1000)

        val outputFile = File(context.cacheDir, "test_cancelled.pdf")
        val outputStream = FileOutputStream(outputFile)

        val job = async {
            pdfCompressor.compressPdf(
                context = context,
                inputUri = Uri.fromFile(inputFile),
                outputStream = outputStream,
                level = CompressionLevel.HIGH
            )
        }

        // Let it start, but not finish
        kotlinx.coroutines.delay(50)

        // Cancel
        job.cancel()

        try {
            val result = job.await()
            // If we are here, it returned a result (swallowed cancellation)
            val exception = result.exceptionOrNull()
            if (exception is CancellationException) {
                throw AssertionError("CancellationException was caught and wrapped in Result. It should be rethrown.")
            }

            if (result.isSuccess) {
                 println("Warning: Compression finished before cancellation could take effect. Test inconclusive but valid behavior.")
                 // We can't fail here because it's a timing issue, not a bug.
                 // But we want to ensure fix is verified.
                 // With 1000 pages, it should definitely not finish in 50ms.
                 throw AssertionError("Compression finished too fast! Increase workload.")
            }

             throw AssertionError("Should have thrown CancellationException but returned $result")
        } catch (e: CancellationException) {
            // Correct behavior!
            println("Cancellation verification successful.")
        }

        outputStream.close()
    }

    @Test
    fun testCompressPdf_sharedImages_deduplication() = runBlocking {
        // Create a PDF with shared images
        val inputFile = File(context.cacheDir, "test_shared.pdf")
        createSharedImagePdf(inputFile, pages = 3) // 3 pages, same image

        val outputFile = File(context.cacheDir, "test_shared_output.pdf")
        val outputStream = FileOutputStream(outputFile)

        val result = pdfCompressor.compressPdf(
            context = context,
            inputUri = Uri.fromFile(inputFile),
            outputStream = outputStream,
            level = CompressionLevel.MEDIUM
        )
        outputStream.close()

        assertTrue(result.isSuccess)

        val outputDoc = PDDocument.load(outputFile)
        val uniqueImages = mutableSetOf<com.tom_roush.pdfbox.cos.COSBase>()

        for (page in outputDoc.pages) {
            val resources = page.resources
            resources.xObjectNames.forEach { name ->
                 val xObject = resources.getXObject(name)
                 if (xObject is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                     uniqueImages.add(xObject.cosObject)
                 }
            }
        }

        outputDoc.close()

        println("Total unique images found: ${uniqueImages.size}")
        println("Strategy used: ${result.getOrNull()?.strategyUsed}")

        assertEquals("Should reuse shared images", 1, uniqueImages.size)
    }

    private fun createTestPdf(file: File) {
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            // Draw a rectangle instead of text to avoid font loading issues in Robolectric
            content.setNonStrokingColor(0, 0, 0)
            content.addRect(100f, 700f, 100f, 50f)
            content.fill()
        }
        doc.save(file)
        doc.close()
    }

    private fun createLargeTestPdf(file: File, pages: Int) {
        val doc = PDDocument()
        repeat(pages) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.setNonStrokingColor(0, 0, 0)
                content.addRect(100f, 700f, 100f, 50f)
                content.fill()
            }
        }
        doc.save(file)
        doc.close()
    }

    private fun createSharedImagePdf(file: File, pages: Int) {
        val doc = PDDocument()

        // Create a bitmap image
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)

        // Create PDImageXObject once
        val pdImage = JPEGFactory.createFromImage(doc, bitmap, 0.8f)

        repeat(pages) {
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.drawImage(pdImage, 50f, 50f)
            }
        }

        doc.save(file)
        doc.close()
        bitmap.recycle()
    }
}
