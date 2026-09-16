package com.yourname.pdftoolkit

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.yourname.pdftoolkit.ui.screens.PdfViewerUiState
import com.yourname.pdftoolkit.ui.screens.PdfViewerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedPdfViewerTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun encryptedPdf_withoutPassword_setsPasswordRequiredNotIncorrect(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encryptedFile = File("test_pdfs/encrypted_simple.pdf")
        if (!encryptedFile.exists()) return@runBlocking

        val uri = Uri.fromFile(encryptedFile)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected PasswordRequired, got $state", state is PdfViewerUiState.PasswordRequired)
        val pwdState = state as PdfViewerUiState.PasswordRequired
        assertEquals(false, pwdState.isIncorrect)
    }

    @Test
    fun encryptedPdf_withWrongPassword_setsPasswordRequiredIncorrect(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encryptedFile = File("test_pdfs/encrypted_simple.pdf")
        if (!encryptedFile.exists()) return@runBlocking

        val uri = Uri.fromFile(encryptedFile)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "wrong_password")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected PasswordRequired, got $state", state is PdfViewerUiState.PasswordRequired)
        val pwdState = state as PdfViewerUiState.PasswordRequired
        assertEquals(true, pwdState.isIncorrect)
    }

    @Test
    fun encryptedPdf_withCorrectPassword_loadsSuccessfully(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encryptedFile = File("test_pdfs/encrypted_simple.pdf")
        if (!encryptedFile.exists()) return@runBlocking

        val uri = Uri.fromFile(encryptedFile)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "secret123")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Loaded, got $state", state is PdfViewerUiState.Loaded)
        val loadedState = state as PdfViewerUiState.Loaded
        assertTrue("Expected at least 1 page", loadedState.totalPages >= 1)
    }

    @Test
    fun encryptedPdf_withUnicodePassword_loadsSuccessfully(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encryptedFile = File("test_pdfs/encrypted_unicode_pwd.pdf")
        if (!encryptedFile.exists()) return@runBlocking

        val uri = Uri.fromFile(encryptedFile)
        val viewModel = PdfViewerViewModel()

        viewModel.loadPdf(context, uri, password = "pässwörd123")

        val state = viewModel.uiState.first { it !is PdfViewerUiState.Loading && it !is PdfViewerUiState.Idle }
        assertTrue("Expected Loaded, got $state", state is PdfViewerUiState.Loaded)
        val loadedState = state as PdfViewerUiState.Loaded
        assertTrue("Expected at least 1 page", loadedState.totalPages >= 1)
    }
}
