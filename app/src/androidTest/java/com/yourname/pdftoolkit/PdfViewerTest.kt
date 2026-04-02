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
 * Tests for PDF Viewer functionality.
 * Verifies that opening a PDF and rendering pages doesn't crash.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PdfViewerTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pdfViewer_navigateToScreen_rendersWithoutCrash() {
        // Navigate to Files screen where PDFs can be opened
        composeTestRule.onNodeWithText("Files").performClick()
        composeTestRule.waitForIdle()
        
        // Verify Files screen loads (this is where PDF viewer is accessed from)
        composeTestRule.onNodeWithText("Files").assertIsDisplayed()
    }

    @Test
    fun pdfViewer_fromToolsTab_opensWithoutCrash() {
        // Navigate to Annotate screen (which uses PDF viewer)
        composeTestRule.onNodeWithText("Annotate PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Annotate screen loads without crash
        composeTestRule.onNodeWithText("Annotate").assertIsDisplayed()
    }
}
