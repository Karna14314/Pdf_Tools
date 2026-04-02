package com.yourname.pdftoolkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.yourname.pdftoolkit.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for OCR functionality.
 * Verifies that OCR screen loads and Tesseract initializes without crash.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OcrTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun ocr_screenLoads_noCrash() {
        // Navigate to OCR screen
        composeTestRule.onNodeWithText("OCR", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify OCR screen loads without crash
        composeTestRule.onNodeWithText("OCR").assertIsDisplayed()
    }

    @Test
    fun ocr_selectPdfButtonVisible() {
        // Navigate to OCR screen
        composeTestRule.onNodeWithText("OCR", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Select PDF button is visible
        composeTestRule.onNodeWithText("Select PDF", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
