package com.yourname.pdftoolkit.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfViewerReproductionTest {

    private lateinit var viewModel: PdfViewerViewModel

    @Before
    fun setup() {
        viewModel = PdfViewerViewModel()
    }

    @Test
    fun `switching tool from Search to Edit should clear search state`() {
        // 1. Start a search (short query to set state synchronously without async complexity)
        viewModel.search("a")
        assertEquals("a", viewModel.searchState.value.query)

        // 2. Switch tool to Edit
        viewModel.setTool(PdfTool.Edit)

        // 3. Verify search state is cleared (This is the expected behavior after fix)
        // Currently this will fail because setTool doesn't clear search state.
        assertEquals("", viewModel.searchState.value.query)
    }
}
