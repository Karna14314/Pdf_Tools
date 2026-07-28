package com.yourname.pdftoolkit.domain.operations

object OcrDpiUtil {
    private const val DEFAULT_DPI = 200f
    private const val MAX_PIXELS = 4_000_000

    fun getSafeOcrDpi(pageWidthPoints: Float, pageHeightPoints: Float): Float {
        val targetPixelsAtDefault = ((pageWidthPoints * DEFAULT_DPI / 72f) * (pageHeightPoints * DEFAULT_DPI / 72f)).toInt()
        if (targetPixelsAtDefault <= MAX_PIXELS) return DEFAULT_DPI

        val scale = kotlin.math.sqrt(MAX_PIXELS.toFloat() / targetPixelsAtDefault.toFloat())
        return (DEFAULT_DPI * scale).coerceIn(120f, DEFAULT_DPI)
    }
}
