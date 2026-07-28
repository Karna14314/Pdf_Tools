package com.yourname.pdftoolkit.domain.operations

import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

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
        val sourceFile = createSimplePdf()
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

        val verifiedDoc = PDDocument.load(outputFile)
        assertEquals("Should have 1 page", 1, verifiedDoc.numberOfPages)
        verifiedDoc.close()
    }

    @Test
    fun `already flat pdf reports zero annotations and forms`() = runBlocking {
        val sourceFile = createSimplePdf()
        val sourceUri = Uri.fromFile(sourceFile)
        val outputFile = File(tempDir, "already_flat.pdf")
        val outputUri = Uri.fromFile(outputFile)

        val result = flattener.flattenPdf(
            context = RuntimeEnvironment.getApplication(),
            inputUri = sourceUri,
            outputUri = outputUri
        )

        assertTrue("Flatten should succeed", result.success)
        assertEquals("Annotations should be 0", 0, result.annotationsFlattened)
        assertEquals("Forms should be 0", 0, result.formsFlattened)
    }

    @Test
    fun `annotation with scaled bbox writes correct cm matrix`() = runBlocking {
        // PDRectangle(x, y, w, h) — w=100, h=100. BBox w=50, h=50.
        // Expected: scaleX = 100/50 = 2.0, scaleY = 100/50 = 2.0
        // tx = 10 - 0*2 = 10, ty = 10 - 0*2 = 10
        // Expected cm: "2 0 0 2 10 10 cm"
        val sourceFile = createPdfWithScaledAnnotation()
        val sourceUri = Uri.fromFile(sourceFile)
        val outputFile = File(tempDir, "annot_scaled.pdf")
        val outputUri = Uri.fromFile(outputFile)

        val result = flattener.flattenPdf(
            context = RuntimeEnvironment.getApplication(),
            inputUri = sourceUri,
            outputUri = outputUri,
            config = FlattenConfig(
                flattenAnnotations = true,
                flattenForms = false,
                removeJavaScript = false,
                removeEmbeddedFiles = false
            )
        )

        assertTrue("Annotation flatten should succeed", result.success)
        assertTrue("Should detect the annotation", result.annotationsFlattened > 0)

        val doc = PDDocument.load(outputFile)
        val page = doc.getPage(0)

        // Annotation must be removed from page
        val remainingAnnotations = page.annotations
        assertTrue(
            "Annotations should be removed after flatten",
            remainingAnnotations == null || remainingAnnotations.isEmpty()
        )

        // Parse the content stream to verify the cm matrix values
        val content = page.contents.bufferedReader().readText()
        val cmPattern = Regex("""(-?[\d.]+(?:[eE][+-]?\d+)?)\s+(-?[\d.]+(?:[eE][+-]?\d+)?)\s+(-?[\d.]+(?:[eE][+-]?\d+)?)\s+(-?[\d.]+(?:[eE][+-]?\d+)?)\s+(-?[\d.]+(?:[eE][+-]?\d+)?)\s+(-?[\d.]+(?:[eE][+-]?\d+)?)\s+cm""")
        val cmMatch = cmPattern.find(content)
        assertNotNull("Content stream must contain a cm transformation", cmMatch)

        val (a, b, c, d, e, f) = cmMatch!!.destructured
        assertClose("scaleX", 2.0, a.toDouble())
        assertClose("skew b", 0.0, b.toDouble())
        assertClose("skew c", 0.0, c.toDouble())
        assertClose("scaleY", 2.0, d.toDouble())
        assertClose("translateX", 10.0, e.toDouble())
        assertClose("translateY", 10.0, f.toDouble())

        doc.close()
    }

    @Test
    fun `rasterizeContent true should succeed`() = runBlocking {
        val sourceFile = createSimplePdf()
        val sourceUri = Uri.fromFile(sourceFile)
        val outputFile = File(tempDir, "rasterized.pdf")
        val outputUri = Uri.fromFile(outputFile)

        val result = flattener.flattenPdf(
            context = RuntimeEnvironment.getApplication(),
            inputUri = sourceUri,
            outputUri = outputUri,
            config = FlattenConfig(rasterizeContent = true)
        )

        assertTrue("Rasterize flatten should succeed", result.success)
        assertTrue("Output should exist", outputFile.exists())
        assertTrue("Output should not be empty", outputFile.length() > 0)

        val doc = PDDocument.load(outputFile)
        assertEquals("Should still have 1 page", 1, doc.numberOfPages)
        doc.close()
    }

    // --- helpers ---

    private fun createSimplePdf(): File {
        val file = File(tempDir, "source_${System.nanoTime()}.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        FileOutputStream(file).use { doc.save(it) }
        doc.close()
        return file
    }

    private fun createPdfWithScaledAnnotation(): File {
        val file = File(tempDir, "annot_${System.nanoTime()}.pdf")
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        // Appearance BBox: 50x50. Annotation Rect: 100x100 at (10,10).
        // Expected scaling fix: scaleX = 100/50 = 2, scaleY = 100/50 = 2,
        // tx = 10 - 0*2 = 10, ty = 10 - 0*2 = 10
        val appearanceStream = PDAppearanceStream(doc)
        appearanceStream.bBox = PDRectangle(0f, 0f, 50f, 50f)

        PDPageContentStream(doc, appearanceStream).use { cs ->
            cs.setNonStrokingColor(255, 0, 0)
            cs.addRect(0f, 0f, 50f, 50f)
            cs.fill()
        }

        val annotation = PDAnnotationRubberStamp()
        annotation.rectangle = PDRectangle(10f, 10f, 100f, 100f)

        val appearanceDict = PDAppearanceDictionary()
        appearanceDict.setNormalAppearance(appearanceStream)
        annotation.appearance = appearanceDict
        page.annotations = listOf(annotation)

        FileOutputStream(file).use { doc.save(it) }
        doc.close()
        return file
    }

    private fun assertClose(message: String, expected: Double, actual: Double, tolerance: Double = 0.01) {
        assertTrue("$message — expected $expected ±$tolerance but got $actual", abs(expected - actual) <= tolerance)
    }
}
