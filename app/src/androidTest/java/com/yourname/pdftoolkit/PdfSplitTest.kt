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
 * Tests for PDF Split functionality.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PdfSplitTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun splitPdf_screenLoads_noCrash() {
        // Navigate to split screen
        composeTestRule.onNodeWithText("Split PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify split UI loads without crash
        composeTestRule.onNodeWithText("Split PDF").assertIsDisplayed()
    }

    @Test
    fun splitPdf_selectFileButtonVisible() {
        // Navigate to split screen
        composeTestRule.onNodeWithText("Split PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Select PDF button or similar is visible
        composeTestRule.onNodeWithText("Select PDF", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
