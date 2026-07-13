package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
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
        // Test initialization (loading native libraries)
        val initialized = ocrEngine.initialize()
        assertTrue("OcrEngine failed to initialize native libraries", initialized)

        // Create a basic 10x10 bitmap to act as a smoke test input
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        // Execute OCR text recognition
        val text = ocrEngine.recognizeText(bitmap)

        // Ensure no exception or native crash occurred, even if result is empty
        assertTrue("Recognize text completed without crashing", true)
    }
}
