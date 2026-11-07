package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class PdfCropperTest {

    private lateinit var context: Context
    private lateinit var cropper: PdfCropper

    private val page = PDRectangle(0f, 0f, 600f, 800f)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        PDFBoxResourceLoader.init(context)
        cropper = PdfCropper()
        context.cacheDir.mkdirs()
    }

    // ---------- Geometry ----------

    private fun assertRect(expected: PDRectangle, actual: PDRectangle) {
        val delta = 0.01f
        assertEquals("llx", expected.lowerLeftX, actual.lowerLeftX, delta)
        assertEquals("lly", expected.lowerLeftY, actual.lowerLeftY, delta)
        assertEquals("urx", expected.upperRightX, actual.upperRightX, delta)
        assertEquals("ury", expected.upperRightY, actual.upperRightY, delta)
    }

    @Test
    fun noRotation_topLeftQuarter_mapsToUpperLeftInPdfSpace() {
        val result = PdfCropper.toPdfRectangle(NormalizedRect(0f, 0f, 0.5f, 0.5f), page, 0)
        assertRect(PDRectangle(0f, 400f, 300f, 400f), result)
    }

    @Test
    fun rotation90_displayedLeftHalf_isUnrotatedBottomHalf() {
        val result = PdfCropper.toPdfRectangle(NormalizedRect(0f, 0f, 0.5f, 1f), page, 90)
        assertRect(PDRectangle(0f, 0f, 600f, 400f), result)
    }

    @Test
    fun rotation180_displayedTopLeft_isUnrotatedBottomRight() {
        val result = PdfCropper.toPdfRectangle(NormalizedRect(0f, 0f, 0.5f, 0.5f), page, 180)
        assertRect(PDRectangle(300f, 0f, 300f, 400f), result)
    }

    @Test
    fun rotation270_displayedLeftHalf_isUnrotatedTopHalf() {
        val result = PdfCropper.toPdfRectangle(NormalizedRect(0f, 0f, 0.5f, 1f), page, 270)
        assertRect(PDRectangle(0f, 400f, 600f, 400f), result)
    }

    @Test
    fun negativeRotation_isNormalized() {
        val a = PdfCropper.toPdfRectangle(NormalizedRect(0.1f, 0.2f, 0.6f, 0.9f), page, -90)
        val b = PdfCropper.toPdfRectangle(NormalizedRect(0.1f, 0.2f, 0.6f, 0.9f), page, 270)
        assertRect(b, a)
    }

    @Test
    fun existingCropBox_isUsedAsReference() {
        val visible = PDRectangle(100f, 50f, 400f, 600f) // x 100..500, y 50..650
        val result = PdfCropper.toPdfRectangle(NormalizedRect(0.25f, 0.5f, 0.75f, 1f), visible, 0)
        assertRect(PDRectangle(200f, 50f, 200f, 300f), result)
    }

    @Test
    fun invertedRect_isNormalizedBeforeMapping() {
        val a = PdfCropper.toPdfRectangle(NormalizedRect(0.8f, 0.9f, 0.2f, 0.1f), page, 0)
        val b = PdfCropper.toPdfRectangle(NormalizedRect(0.2f, 0.1f, 0.8f, 0.9f), page, 0)
        assertRect(b, a)
    }

    @Test
    fun splitAt_verticalAndHorizontal() {
        val (left, right) = PdfCropper.splitAt(SplitOrientation.VERTICAL, 0.4f)
        assertEquals(NormalizedRect(0f, 0f, 0.4f, 1f), left)
        assertEquals(NormalizedRect(0.4f, 0f, 1f, 1f), right)

        val (top, bottom) = PdfCropper.splitAt(SplitOrientation.HORIZONTAL, 0.5f)
        assertEquals(NormalizedRect(0f, 0f, 1f, 0.5f), top)
        assertEquals(NormalizedRect(0f, 0.5f, 1f, 1f), bottom)
    }

    @Test
    fun rectForPage_oddEvenAndPerPage() {
        val odd = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f)
        val even = NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f)
        val plan = CropPlan.OddEven(odd, even)
        assertEquals(odd, PdfCropper.rectForPage(plan, 0))  // page 1
        assertEquals(even, PdfCropper.rectForPage(plan, 1)) // page 2
        assertEquals(odd, PdfCropper.rectForPage(plan, 2))  // page 3

        val perPage = CropPlan.PerPage(mapOf(1 to odd))
        assertNull(PdfCropper.rectForPage(perPage, 0))
        assertEquals(odd, PdfCropper.rectForPage(perPage, 1))
    }

    @Test
    fun normalizedRect_usabilityAndFullPage() {
        assertTrue(NormalizedRect.FULL.isFullPage())
        assertFalse(NormalizedRect(0f, 0f, 0.01f, 1f).isUsable())
        assertTrue(NormalizedRect(0f, 0f, 0.5f, 0.5f).isUsable())
    }

    // ---------- Documents ----------

    private fun createTestPdf(name: String, pageCount: Int, rotation: Int = 0, withAnnotation: Boolean = false): Uri {
        val file = File(context.cacheDir, name)
        PDDocument().use { doc ->
            repeat(pageCount) {
                val p = PDPage(PDRectangle(600f, 800f))
                p.rotation = rotation
                if (withAnnotation) {
                    val annotation = PDAnnotationText()
                    annotation.rectangle = PDRectangle(10f, 10f, 20f, 20f)
                    p.annotations.add(annotation)
                }
                doc.addPage(p)
            }
            doc.save(file)
        }
        return Uri.fromFile(file)
    }

    private fun runCrop(input: Uri, outName: String, plan: CropPlan, shrink: Boolean = false): Pair<CropResult, File> {
        val out = File(context.cacheDir, outName)
        val result = runBlocking {
            FileOutputStream(out).use { stream ->
                cropper.crop(context, input, stream, plan, shrinkMediaBox = shrink)
            }
        }
        assertTrue("crop failed: ${result.exceptionOrNull()}", result.isSuccess)
        return result.getOrThrow() to out
    }

    @Test
    fun uniformCrop_setsCropBoxOnEveryPage_andKeepsMediaBox() {
        val input = createTestPdf("crop_uniform.pdf", 3)
        val (result, out) = runCrop(input, "crop_uniform_out.pdf", CropPlan.Uniform(NormalizedRect(0f, 0f, 0.5f, 0.5f)))

        assertEquals(3, result.croppedPages)
        PDDocument.load(out).use { doc ->
            assertEquals(3, doc.numberOfPages)
            doc.pages.forEach { p ->
                assertRect(PDRectangle(0f, 400f, 300f, 400f), p.cropBox)
                assertRect(page, p.mediaBox)
            }
        }
    }

    @Test
    fun shrinkMediaBox_setsMediaBoxToo() {
        val input = createTestPdf("crop_shrink.pdf", 1)
        val (_, out) = runCrop(
            input, "crop_shrink_out.pdf",
            CropPlan.Uniform(NormalizedRect(0.5f, 0.5f, 1f, 1f)),
            shrink = true
        )
        PDDocument.load(out).use { doc ->
            val p = doc.getPage(0)
            assertRect(PDRectangle(300f, 0f, 300f, 400f), p.mediaBox)
            assertRect(PDRectangle(300f, 0f, 300f, 400f), p.cropBox)
        }
    }

    @Test
    fun perPage_leavesUnlistedPagesUntouched() {
        val input = createTestPdf("crop_perpage.pdf", 3)
        val rect = NormalizedRect(0f, 0f, 1f, 0.5f)
        val (result, out) = runCrop(input, "crop_perpage_out.pdf", CropPlan.PerPage(mapOf(1 to rect)))

        assertEquals(1, result.croppedPages)
        PDDocument.load(out).use { doc ->
            assertRect(page, doc.getPage(0).cropBox)
            assertRect(PDRectangle(0f, 400f, 600f, 400f), doc.getPage(1).cropBox)
            assertRect(page, doc.getPage(2).cropBox)
        }
    }

    @Test
    fun split_doublesPages_inLeftRightOrder_andDropsAnnotationsOnCopy() {
        val input = createTestPdf("crop_split.pdf", 2, withAnnotation = true)
        val (left, right) = PdfCropper.splitAt(SplitOrientation.VERTICAL, 0.5f)
        val (result, out) = runCrop(input, "crop_split_out.pdf", CropPlan.Split(left, right))

        assertEquals(4, result.outputPages)
        PDDocument.load(out).use { doc ->
            assertEquals(4, doc.numberOfPages)
            for (i in 0 until 4 step 2) {
                assertRect(PDRectangle(0f, 0f, 300f, 800f), doc.getPage(i).cropBox)
                assertRect(PDRectangle(300f, 0f, 300f, 800f), doc.getPage(i + 1).cropBox)
                assertEquals(1, doc.getPage(i).annotations.size)
                assertEquals(0, doc.getPage(i + 1).annotations.size)
            }
        }
    }

    @Test
    fun split_reverseOrder_putsSecondAreaFirst() {
        val input = createTestPdf("crop_split_rev.pdf", 1)
        val (left, right) = PdfCropper.splitAt(SplitOrientation.VERTICAL, 0.5f)
        val (_, out) = runCrop(input, "crop_split_rev_out.pdf", CropPlan.Split(left, right, reverseOrder = true))

        PDDocument.load(out).use { doc ->
            assertRect(PDRectangle(300f, 0f, 300f, 800f), doc.getPage(0).cropBox)
            assertRect(PDRectangle(0f, 0f, 300f, 800f), doc.getPage(1).cropBox)
        }
    }

    @Test
    fun split_onRotatedPage_keepsRotationOnBothCopies() {
        val input = createTestPdf("crop_split_rot.pdf", 1, rotation = 90)
        val (top, bottom) = PdfCropper.splitAt(SplitOrientation.HORIZONTAL, 0.5f)
        val (_, out) = runCrop(input, "crop_split_rot_out.pdf", CropPlan.Split(top, bottom))

        PDDocument.load(out).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals(90, doc.getPage(1).rotation)
            // Displayed top half of a 90° page = unrotated left half.
            assertRect(PDRectangle(0f, 0f, 300f, 800f), doc.getPage(0).cropBox)
            assertRect(PDRectangle(300f, 0f, 300f, 800f), doc.getPage(1).cropBox)
        }
    }
}
