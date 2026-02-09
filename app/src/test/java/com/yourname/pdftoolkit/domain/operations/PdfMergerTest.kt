package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        pdfMerger = PdfMerger()

        // Ensure cache dir exists
        context.cacheDir.mkdirs()
    }

    @Test
    fun benchmarkMergePdfs() {
        runBlocking {
            // 1. Setup - Create 10 simple PDFs
            val uris = mutableListOf<Uri>()
            val pageCountPerPdf = 5
            val numberOfPdfs = 10

            for (i in 0 until numberOfPdfs) {
                val file = File(context.cacheDir, "test_pdf_$i.pdf")
                createTestPdf(file, pageCountPerPdf)
                testFiles.add(file)

                val uri = Uri.fromFile(file)
                uris.add(uri)
            }

            // Output file
            val outputFile = File(context.cacheDir, "merged_output.pdf")
            val outputStream = FileOutputStream(outputFile)

            // 2. Measure
            val start = System.nanoTime()
            val result = pdfMerger.mergePdfs(context, uris, outputStream)
            val end = System.nanoTime()

            outputStream.close()

            // 3. Verify
            assert(result.isSuccess)

            // Verify page count
            val mergedDoc = PDDocument.load(outputFile)
            assertEquals(numberOfPdfs * pageCountPerPdf, mergedDoc.numberOfPages)
            mergedDoc.close()

            println("Merge execution time: ${(end - start) / 1_000_000} ms")

            // Cleanup
            testFiles.forEach { it.delete() }
            outputFile.delete()
        }
    }

    private fun createTestPdf(file: File, pages: Int) {
        val document = PDDocument()
        repeat(pages) {
            document.addPage(PDPage())
        }
        document.save(file)
        document.close()
    }
}
