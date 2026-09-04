package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PdfRotatorTest {

    private lateinit var context: Context
    private lateinit var pdfRotator: PdfRotator

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        pdfRotator = PdfRotator()
        context.cacheDir.mkdirs()
    }

    private fun createTestPdf(file: File, pageCount: Int): Uri {
        val document = PDDocument()
        repeat(pageCount) {
            document.addPage(PDPage())
        }
        document.save(file)
        document.close()
        return Uri.fromFile(file)
    }

    @Test
    fun testRotateSpecificPagesWithDegrees() {
        runBlocking {
            val testFile = File(context.cacheDir, "rot_test.pdf")
            val uri = createTestPdf(testFile, 5)

            val outputFile = File(context.cacheDir, "rot_out.pdf")
            val outputStream = FileOutputStream(outputFile)

            // Rotate page 1 by 90°, page 3 by 180°, page 4 by 270°
            val rotations = mapOf(
                1 to 90,
                3 to 180,
                4 to 270
            )

            val result = pdfRotator.rotateSpecificPagesWithDegrees(
                context = context,
                inputUri = uri,
                outputStream = outputStream,
                rotations = rotations
            )

            outputStream.close()

            assertTrue(result.isSuccess)
            assertEquals(3, result.getOrNull())

            // Verify rotations in generated PDF
            val doc = PDDocument.load(outputFile)
            assertEquals(5, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals(0, doc.getPage(1).rotation)
            assertEquals(180, doc.getPage(2).rotation)
            assertEquals(270, doc.getPage(3).rotation)
            assertEquals(0, doc.getPage(4).rotation)
            doc.close()

            testFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun testRotateAllPages() {
        runBlocking {
            val testFile = File(context.cacheDir, "rot_all_test.pdf")
            val uri = createTestPdf(testFile, 3)

            val outputFile = File(context.cacheDir, "rot_all_out.pdf")
            val outputStream = FileOutputStream(outputFile)

            val result = pdfRotator.rotateAllPages(
                context = context,
                inputUri = uri,
                outputStream = outputStream,
                angle = RotationAngle.ROTATE_180
            )

            outputStream.close()

            assertTrue(result.isSuccess)
            assertEquals(3, result.getOrNull())

            val doc = PDDocument.load(outputFile)
            assertEquals(180, doc.getPage(0).rotation)
            assertEquals(180, doc.getPage(1).rotation)
            assertEquals(180, doc.getPage(2).rotation)
            doc.close()

            testFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun testRotateWithNegativeOrModuloDegrees() {
        runBlocking {
            val testFile = File(context.cacheDir, "rot_mod_test.pdf")
            val uri = createTestPdf(testFile, 2)

            val outputFile = File(context.cacheDir, "rot_mod_out.pdf")
            val outputStream = FileOutputStream(outputFile)

            val rotations = mapOf(
                1 to -90, // equivalent to +270°
                2 to 450  // equivalent to +90°
            )

            val result = pdfRotator.rotateSpecificPagesWithDegrees(
                context = context,
                inputUri = uri,
                outputStream = outputStream,
                rotations = rotations
            )

            outputStream.close()

            assertTrue(result.isSuccess)

            val doc = PDDocument.load(outputFile)
            assertEquals(270, doc.getPage(0).rotation)
            assertEquals(90, doc.getPage(1).rotation)
            doc.close()

            testFile.delete()
            outputFile.delete()
        }
    }
}
