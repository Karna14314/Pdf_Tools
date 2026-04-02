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
 * Tests for PDF Annotation functionality.
 * Verifies that annotation mode doesn't cause BaseCanvas crashes.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AnnotationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun annotation_screenLoads_noCrash() {
        // Navigate to Annotate screen
        composeTestRule.onNodeWithText("Annotate PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify annotation screen loads without crash
        composeTestRule.onNodeWithText("Annotate").assertIsDisplayed()
    }

    @Test
    fun annotation_selectPdfButtonVisible() {
        // Navigate to Annotate screen
        composeTestRule.onNodeWithText("Annotate PDF", useUnmergedTree = true)
            .performClick()
        
        composeTestRule.waitForIdle()
        
        // Verify Select PDF button is visible
        composeTestRule.onNodeWithText("Select PDF", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
