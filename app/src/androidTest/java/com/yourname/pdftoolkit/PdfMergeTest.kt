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
 * Tests for PDF Merge functionality.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PdfMergeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mergePdf_screenLoads_noCrash() {
        // Navigate to merge screen
        composeTestRule.onNodeWithText("Merge PDFs", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify merge UI loads without crash
        composeTestRule.onNodeWithText("Merge PDFs").assertIsDisplayed()
    }

    @Test
    fun mergePdf_addFilesButtonVisible() {
        // Navigate to merge screen
        composeTestRule.onNodeWithText("Merge PDFs", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Add Files button is visible
        composeTestRule.onNodeWithText("Add Files", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
