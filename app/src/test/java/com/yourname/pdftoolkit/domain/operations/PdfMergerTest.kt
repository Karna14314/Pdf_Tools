package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
class PdfMergerTest {

    private lateinit var context: Context
    private lateinit var pdfMerger: PdfMerger
    private val testFiles = mutableListOf<File>()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        pdfMerger = PdfMerger()
        context.cacheDir.mkdirs()
    }

    @Test
    fun testPdfOnlyMerge() {
        runBlocking {
            val pdf1 = File(context.cacheDir, "test1.pdf").also { createTestPdf(it, 3) }
            val pdf2 = File(context.cacheDir, "test2.pdf").also { createTestPdf(it, 2) }
            testFiles.addAll(listOf(pdf1, pdf2))

            val items = listOf(
                MergeItem(uri = Uri.fromFile(pdf1), name = pdf1.name, size = pdf1.length(), formattedSize = "10 KB", type = MergeItemType.PDF, pageCount = 3),
                MergeItem(uri = Uri.fromFile(pdf2), name = pdf2.name, size = pdf2.length(), formattedSize = "8 KB", type = MergeItemType.PDF, pageCount = 2)
            )

            val outputFile = File(context.cacheDir, "output_pdf_only.pdf")
            val outputStream = FileOutputStream(outputFile)

            val result = pdfMerger.mergeItems(context, items, outputStream)
            outputStream.close()

            assertTrue(result.isSuccess)
            val mergedDoc = PDDocument.load(outputFile)
            assertEquals(5, mergedDoc.numberOfPages)
            mergedDoc.close()
            outputFile.delete()
        }
    }

    @Test
    fun testImageOnlyMerge() {
        runBlocking {
            val img1 = File(context.cacheDir, "img1.png").also { createTestImage(it) }
            val img2 = File(context.cacheDir, "img2.jpg").also { createTestImage(it) }
            testFiles.addAll(listOf(img1, img2))

            val items = listOf(
                MergeItem(uri = Uri.fromFile(img1), name = img1.name, size = img1.length(), formattedSize = "5 KB", type = MergeItemType.IMAGE, pageCount = 1),
                MergeItem(uri = Uri.fromFile(img2), name = img2.name, size = img2.length(), formattedSize = "5 KB", type = MergeItemType.IMAGE, pageCount = 1)
            )

            val outputFile = File(context.cacheDir, "output_img_only.pdf")
            val outputStream = FileOutputStream(outputFile)

            val result = pdfMerger.mergeItems(context, items, outputStream)
            outputStream.close()

            assertTrue(result.isSuccess)
            val mergedDoc = PDDocument.load(outputFile)
            assertEquals(2, mergedDoc.numberOfPages)
            mergedDoc.close()
            outputFile.delete()
        }
    }

    @Test
    fun testMixedPdfAndImageMerge() {
        runBlocking {
            val pdf = File(context.cacheDir, "doc.pdf").also { createTestPdf(it, 2) }
            val img = File(context.cacheDir, "pic.jpg").also { createTestImage(it) }
            testFiles.addAll(listOf(pdf, img))

            val items = listOf(
                MergeItem(uri = Uri.fromFile(pdf), name = pdf.name, size = pdf.length(), formattedSize = "10 KB", type = MergeItemType.PDF, pageCount = 2),
                MergeItem(uri = Uri.fromFile(img), name = img.name, size = img.length(), formattedSize = "5 KB", type = MergeItemType.IMAGE, pageCount = 1)
            )

            val outputFile = File(context.cacheDir, "output_mixed.pdf")
            val outputStream = FileOutputStream(outputFile)

            val result = pdfMerger.mergeItems(context, items, outputStream)
            outputStream.close()

            assertTrue(result.isSuccess)
            val mergedDoc = PDDocument.load(outputFile)
            assertEquals(3, mergedDoc.numberOfPages)
            mergedDoc.close()
            outputFile.delete()
        }
    }

    @Test
    fun testBuildPageItemsOrder() {
        val pdfItem = MergeItem(uri = Uri.parse("file:///pdf"), name = "Doc.pdf", size = 100, formattedSize = "100 B", type = MergeItemType.PDF, pageCount = 2)
        val imgItem = MergeItem(uri = Uri.parse("file:///img"), name = "Photo.jpg", size = 50, formattedSize = "50 B", type = MergeItemType.IMAGE, pageCount = 1)

        val pageItems = pdfMerger.buildPageItems(listOf(pdfItem, imgItem))

        assertEquals(3, pageItems.size)
        assertEquals(1, pageItems[0].overallPageNumber)
        assertEquals("Doc.pdf (p. 1)", pageItems[0].sourceLabel)
        assertEquals(2, pageItems[1].overallPageNumber)
        assertEquals("Doc.pdf (p. 2)", pageItems[1].sourceLabel)
        assertEquals(3, pageItems[2].overallPageNumber)
        assertEquals("Photo.jpg", pageItems[2].sourceLabel)
        assertTrue(pageItems[2].isImage)
    }

    private fun createTestPdf(file: File, pages: Int) {
        val document = PDDocument()
        repeat(pages) {
            document.addPage(PDPage())
        }
        document.save(file)
        document.close()
    }

    private fun createTestImage(file: File) {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
    }
}
