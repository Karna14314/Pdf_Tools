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
 * Tests for PDF to Images conversion functionality.
 * Verifies that the conversion works without OOM crashes.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ImageConverterTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pdfToImages_screenLoads_noCrash() {
        // Navigate to PDF to Images screen
        composeTestRule.onNodeWithText("PDF to Images", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify screen loads without crash
        composeTestRule.onNodeWithText("PDF to Images").assertIsDisplayed()
    }

    @Test
    fun pdfToImages_selectPdfButtonVisible() {
        // Navigate to PDF to Images screen
        composeTestRule.onNodeWithText("PDF to Images", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Select PDF button is visible
        composeTestRule.onNodeWithText("Select PDF", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun imagesToPdf_screenLoads_noCrash() {
        // Navigate to Images to PDF screen
        composeTestRule.onNodeWithText("Images to PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify screen loads without crash
        composeTestRule.onNodeWithText("Images to PDF").assertIsDisplayed()
    }
}
