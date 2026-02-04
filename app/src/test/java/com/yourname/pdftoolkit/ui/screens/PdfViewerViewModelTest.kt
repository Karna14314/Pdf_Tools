package com.yourname.pdftoolkit.ui.screens

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PdfViewerViewModelTest {

    private lateinit var viewModel: PdfViewerViewModel
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        viewModel = PdfViewerViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test initial state`() = runTest {
        assertEquals(PdfTool.None, viewModel.toolState.value)
        assertEquals(PdfViewerUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `test set tool`() = runTest {
        viewModel.setTool(PdfTool.Search)
        assertEquals(PdfTool.Search, viewModel.toolState.value)

        viewModel.setTool(PdfTool.Edit)
        assertEquals(PdfTool.Edit, viewModel.toolState.value)
        assertEquals(AnnotationTool.HIGHLIGHTER, viewModel.selectedAnnotationTool.value) // Default to Highlighter
    }

    @Test
    fun `test search query update`() = runTest {
        viewModel.setSearchQuery("test")
        val searchState = viewModel.searchState.value
        assertEquals("test", searchState.query)
    }

    @Test
    fun `test save state flow`() = runTest {
        viewModel.resetSaveState()
        assertEquals(SaveState.Idle, viewModel.saveState.value)
    }

}
