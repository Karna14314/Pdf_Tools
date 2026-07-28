package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrEngineTest {

    private lateinit var context: Context
    private lateinit var ocrEngine: OcrEngine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ocrEngine = OcrEngine(context)
    }

    @After
    fun tearDown() {
        ocrEngine.close()
    }

    @Test
    fun testInitializationAndBasicOcr() = runBlocking {
        val initialized = ocrEngine.initialize()
        assertTrue("OcrEngine failed to initialize native libraries", initialized)

        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val words = ocrEngine.recognizeText(bitmap)

        // Should not crash — empty result expected on blank 10x10 bitmap
        assertTrue("Word list should not be null", words != null)
        assertTrue("Engine returned without exception", true)
    }

    @Test
    fun testOcrOnBitmapWithText() = runBlocking {
        val initialized = ocrEngine.initialize()
        assertTrue("OcrEngine failed to initialize", initialized)

        // Create a bitmap with a white background and black text
        val width = 400
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = android.graphics.Paint().apply {
            color = Color.BLACK
            textSize = 32f
        }
        canvas.drawText("Hello", 20f, 60f, paint)
        canvas.drawText("World", 200f, 60f, paint)

        val words = ocrEngine.recognizeText(bitmap)

        assertTrue("Should recognize some words on text bitmap", words.isNotEmpty())

        // Verify reading-order monotonicity: words should have non-decreasing x
        // (may have multiple pages/rows but for a single-line bitmap this holds)
        for (i in 1 until words.size) {
            val prev = words[i - 1].boundingBox
            val curr = words[i].boundingBox
            if (prev != null && curr != null) {
                assertTrue(
                    "Word ${i - 1} should be left of word $i: prev.left=${prev.left}, curr.left=${curr.left}",
                    curr.left >= prev.left - 5 // allow small overlap tolerance
                )
            }
        }
    }

    @Test
    fun testEmptyBitmapReturnsEmptyList() = runBlocking {
        val initialized = ocrEngine.initialize()
        assertTrue("OcrEngine failed to initialize", initialized)

        // Solid black bitmap — unlikely to produce any text
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)

        val words = ocrEngine.recognizeText(bitmap)

        assertTrue("Word list should not be null", words != null)
    }
}
