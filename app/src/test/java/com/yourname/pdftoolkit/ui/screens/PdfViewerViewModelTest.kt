package com.yourname.pdftoolkit.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfViewerViewModelTest {

    private lateinit var viewModel: PdfViewerViewModel

    @Before
    fun setup() {
        viewModel = PdfViewerViewModel()
    }

    @Test
    fun `initial state is correct`() {
        assertEquals(PdfTool.None, viewModel.toolState.value)
        assertEquals("", viewModel.searchState.value.query)
        assertEquals(SaveState.Idle, viewModel.saveState.value)
        assertEquals(AnnotationTool.NONE, viewModel.selectedAnnotationTool.value)
    }

    @Test
    fun `search with short query updates query but does not load`() = runBlocking {
        viewModel.search("a")
        // search checks length synchronously
        assertEquals("a", viewModel.searchState.value.query)
        assertFalse(viewModel.searchState.value.isLoading)
        assertTrue(viewModel.searchState.value.matches.isEmpty())
    }

    @Test
    fun `setTool updates toolState`() {
        viewModel.setTool(PdfTool.Search)
        assertTrue(viewModel.toolState.value is PdfTool.Search)

        viewModel.setTool(PdfTool.None)
        assertTrue(viewModel.toolState.value is PdfTool.None)
    }

    @Test
    fun `setTool Edit does not set default annotation tool`() {
        // Initial state
        assertEquals(AnnotationTool.NONE, viewModel.selectedAnnotationTool.value)

        viewModel.setTool(PdfTool.Edit)

        // Should NOT default to HIGHLIGHTER anymore (stays NONE)
        assertEquals(AnnotationTool.NONE, viewModel.selectedAnnotationTool.value)
        assertTrue(viewModel.toolState.value is PdfTool.Edit)
    }

    @Test
    fun `setAnnotationTool updates toolState to Edit`() {
        viewModel.setAnnotationTool(AnnotationTool.MARKER)

        assertEquals(AnnotationTool.MARKER, viewModel.selectedAnnotationTool.value)
        assertTrue(viewModel.toolState.value is PdfTool.Edit)
    }

    @Test
    fun `clearSearch resets state`() {
        viewModel.search("test")
        viewModel.clearSearch()

        assertEquals("", viewModel.searchState.value.query)
        assertTrue(viewModel.searchState.value.matches.isEmpty())
    }
}
