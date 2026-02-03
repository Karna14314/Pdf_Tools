package com.yourname.pdftoolkit.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33]) // Use a recent SDK
class PdfViewerViewModelTest {

    @Test
    fun `initial state is correct`() {
        val viewModel = PdfViewerViewModel()
        assertEquals(PdfTool.None, viewModel.toolState.value)
        assertEquals(SearchState.Idle, viewModel.searchState.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `setTool updates toolState`() {
        val viewModel = PdfViewerViewModel()

        viewModel.setTool(PdfTool.Search)
        assertEquals(PdfTool.Search, viewModel.toolState.value)

        viewModel.setTool(PdfTool.Edit)
        assertEquals(PdfTool.Edit, viewModel.toolState.value)

        viewModel.setTool(PdfTool.Merge)
        assertEquals(PdfTool.Merge, viewModel.toolState.value)
    }

    @Test
    fun `setTool None resets SearchState`() {
        val viewModel = PdfViewerViewModel()

        viewModel.setTool(PdfTool.Search)
        viewModel.onSearchQueryChange("test")
        // We can't easily assert SearchState.Searching because it happens in coroutine
        // and we are not controlling dispatchers here easily without kotlinx-coroutines-test.
        // But we can check if switching away resets it.

        viewModel.setTool(PdfTool.None)
        assertEquals(SearchState.Idle, viewModel.searchState.value)
        assertEquals("", viewModel.searchQuery.value)
    }

    @Test
    fun `setAnnotationTool updates toolState to Edit`() {
        val viewModel = PdfViewerViewModel()

        viewModel.setAnnotationTool(AnnotationTool.HIGHLIGHTER)
        assertEquals(AnnotationTool.HIGHLIGHTER, viewModel.selectedAnnotationTool.value)
        assertEquals(PdfTool.Edit, viewModel.toolState.value)
    }

    @Test
    fun `setTool Search keeps AnnotationTool but changes toolState`() {
        val viewModel = PdfViewerViewModel()

        viewModel.setAnnotationTool(AnnotationTool.HIGHLIGHTER)
        viewModel.setTool(PdfTool.Search)

        assertEquals(PdfTool.Search, viewModel.toolState.value)
        // Check if selectedAnnotationTool is preserved or reset?
        // Code says: if (tool != PdfTool.Edit) { _selectedAnnotationTool.value = AnnotationTool.NONE }
        // So it should be NONE.
        assertEquals(AnnotationTool.NONE, viewModel.selectedAnnotationTool.value)
    }
}
