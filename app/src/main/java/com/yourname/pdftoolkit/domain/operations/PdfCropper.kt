package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * A rectangle expressed as fractions (0..1) of the page *as it is displayed*
 * (i.e. after the page's /Rotate is applied), with the origin at the top-left corner.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Returns a copy with sorted edges clamped to the 0..1 range. */
    fun normalized(): NormalizedRect {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 1f)
        val b = maxOf(top, bottom).coerceIn(0f, 1f)
        return NormalizedRect(l, t, r, b)
    }

    /** True when the rectangle is large enough to produce a usable page. */
    fun isUsable(): Boolean {
        val n = normalized()
        return n.width >= MIN_SIZE && n.height >= MIN_SIZE
    }

    /** True when the rectangle covers the whole page (nothing to crop). */
    fun isFullPage(): Boolean {
        val n = normalized()
        return n.left <= EPSILON && n.top <= EPSILON && n.right >= 1f - EPSILON && n.bottom >= 1f - EPSILON
    }

    companion object {
        /** Smallest allowed side, as a fraction of the page (2%). */
        const val MIN_SIZE = 0.02f
        private const val EPSILON = 0.0005f

        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

/** Direction of the line used to split a page in two. */
enum class SplitOrientation {
    /** Vertical line: left half and right half. */
    VERTICAL,
    /** Horizontal line: top half and bottom half. */
    HORIZONTAL
}

/**
 * What to do with the document.
 */
sealed class CropPlan {
    /** Crop every page with the same rectangle. */
    data class Uniform(val rect: NormalizedRect) : CropPlan()

    /**
     * Crop odd pages (1, 3, 5…) and even pages (2, 4, 6…) with different rectangles.
     * A null rectangle leaves those pages untouched.
     */
    data class OddEven(val odd: NormalizedRect?, val even: NormalizedRect?) : CropPlan()

    /**
     * Crop each page with its own rectangle.
     * Keys are 0-based page indices; pages without an entry are left untouched.
     */
    data class PerPage(val rects: Map<Int, NormalizedRect>) : CropPlan()

    /**
     * Turn every page into two pages. Odd pages (1, 3, 5…) use [odd], even pages use [even]
     * (the same areas by default). With [reverseOrder] the second area is placed first
     * (right-to-left documents).
     */
    data class Split(
        val odd: SplitAreas,
        val even: SplitAreas = odd,
        val reverseOrder: Boolean = false
    ) : CropPlan()
}

/** The two areas a page is split into, in reading order. */
data class SplitAreas(val first: NormalizedRect, val second: NormalizedRect) {
    fun isUsable(): Boolean = first.isUsable() && second.isUsable()
}

/** Outcome of a crop operation. */
data class CropResult(
    val inputPages: Int,
    val outputPages: Int,
    val croppedPages: Int
)

/**
 * Lossless PDF cropping.
 *
 * Cropping sets the page CropBox: page content is not
 * rasterized and text stays selectable. Content outside the crop area is hidden,
 * not removed from the file.
 */
class PdfCropper {

    /**
     * Apply [plan] to the PDF at [inputUri] and write the result to [outputStream].
     */
    suspend fun crop(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        plan: CropPlan,
        onProgress: (Float) -> Unit = {}
    ): Result<CropResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        try {
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open input file"))

            document = inputStream.use {
                PDDocument.load(it, MemoryUsageSetting.setupTempFileOnly())
            }
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                return@withContext Result.failure(IllegalStateException("PDF has no pages"))
            }

            val result = when (plan) {
                is CropPlan.Split -> splitPages(document, plan, onProgress)
                else -> cropPages(document, plan, onProgress)
            }

            ensureActive()
            document.save(outputStream)
            outputStream.flush()
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun cropPages(
        document: PDDocument,
        plan: CropPlan,
        onProgress: (Float) -> Unit
    ): CropResult {
        val totalPages = document.numberOfPages
        var cropped = 0
        for (index in 0 until totalPages) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val rect = rectForPage(plan, index)
            if (rect != null && !rect.isFullPage()) {
                require(rect.isUsable()) { "Crop area on page ${index + 1} is too small" }
                applyCrop(document.getPage(index), rect)
                cropped++
            }
            onProgress((index + 1).toFloat() / totalPages)
        }
        return CropResult(inputPages = totalPages, outputPages = totalPages, croppedPages = cropped)
    }

    private suspend fun splitPages(
        document: PDDocument,
        plan: CropPlan.Split,
        onProgress: (Float) -> Unit
    ): CropResult {
        require(plan.odd.isUsable() && plan.even.isUsable()) { "Split areas are too small" }

        val originals = document.pages.toList()
        val totalPages = originals.size
        originals.forEachIndexed { index, page ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val areas = if (index % 2 == 0) plan.odd else plan.even
            val (firstRect, secondRect) = if (plan.reverseOrder) {
                areas.second to areas.first
            } else {
                areas.first to areas.second
            }
            val copy = duplicatePage(page)
            document.pages.insertAfter(copy, page)

            // Map both rectangles against the original geometry before changing anything.
            applyCrop(page, firstRect)
            applyCrop(copy, secondRect)

            onProgress((index + 1).toFloat() / totalPages)
        }
        return CropResult(
            inputPages = totalPages,
            outputPages = totalPages * 2,
            croppedPages = totalPages * 2
        )
    }

    /**
     * Shallow copy of [page]: content streams and resources are shared (written once),
     * inherited attributes are made explicit, and annotations are dropped because an
     * annotation dictionary can only belong to one page.
     */
    private fun duplicatePage(page: PDPage): PDPage {
        val dict = COSDictionary(page.cosObject)
        dict.removeItem(COSName.PARENT)
        dict.removeItem(COSName.ANNOTS)
        dict.removeItem(COSName.STRUCT_PARENTS)
        dict.removeItem(COSName.B)
        val copy = PDPage(dict)
        copy.mediaBox = page.mediaBox
        copy.cropBox = page.cropBox
        copy.rotation = page.rotation
        page.resources?.let { copy.resources = it }
        return copy
    }

    private fun applyCrop(page: PDPage, rect: NormalizedRect) {
        page.cropBox = toPdfRectangle(rect, page.cropBox, page.rotation)
    }

    companion object {
        /** Returns the rectangle for 0-based page [index], or null to leave the page untouched. */
        fun rectForPage(plan: CropPlan, index: Int): NormalizedRect? = when (plan) {
            is CropPlan.Uniform -> plan.rect
            is CropPlan.OddEven -> if (index % 2 == 0) plan.odd else plan.even
            is CropPlan.PerPage -> plan.rects[index]
            is CropPlan.Split -> null
        }

        /**
         * Split the displayed page at [position] (0..1) and return the two areas
         * (left/right for [SplitOrientation.VERTICAL], top/bottom for [SplitOrientation.HORIZONTAL]).
         */
        fun splitAt(orientation: SplitOrientation, position: Float): SplitAreas {
            val p = position.coerceIn(NormalizedRect.MIN_SIZE, 1f - NormalizedRect.MIN_SIZE)
            return when (orientation) {
                SplitOrientation.VERTICAL ->
                    SplitAreas(NormalizedRect(0f, 0f, p, 1f), NormalizedRect(p, 0f, 1f, 1f))
                SplitOrientation.HORIZONTAL ->
                    SplitAreas(NormalizedRect(0f, 0f, 1f, p), NormalizedRect(0f, p, 1f, 1f))
            }
        }

        /**
         * Convert [rect] (fractions of the displayed page, top-left origin) into PDF user space.
         *
         * @param visibleBox the page's current CropBox (the area the user saw)
         * @param rotation the page's /Rotate value in degrees (clockwise)
         */
        fun toPdfRectangle(rect: NormalizedRect, visibleBox: PDRectangle, rotation: Int): PDRectangle {
            val r = rect.normalized()
            // Map two opposite corners from displayed space to unrotated space
            // (fractions of the unrotated box, top-left origin).
            val (p1, q1) = unrotate(r.left, r.top, rotation)
            val (p2, q2) = unrotate(r.right, r.bottom, rotation)
            val pMin = minOf(p1, p2)
            val pMax = maxOf(p1, p2)
            val qMin = minOf(q1, q2)
            val qMax = maxOf(q1, q2)

            val llx = visibleBox.lowerLeftX
            val lly = visibleBox.lowerLeftY
            val w = visibleBox.width
            val h = visibleBox.height

            // PDF y axis points up, so the top edge (qMin) has the larger y.
            val x0 = llx + pMin * w
            val x1 = llx + pMax * w
            val y0 = lly + (1f - qMax) * h
            val y1 = lly + (1f - qMin) * h
            return PDRectangle(x0, y0, x1 - x0, y1 - y0)
        }

        /** Inverse of the clockwise display rotation for a normalized point. */
        private fun unrotate(u: Float, v: Float, rotation: Int): Pair<Float, Float> =
            when (((rotation % 360) + 360) % 360) {
                90 -> v to (1f - u)
                180 -> (1f - u) to (1f - v)
                270 -> (1f - v) to u
                else -> u to v
            }
    }
}
