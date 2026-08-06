package com.yourname.pdftoolkit.domain.operations

import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.PDDocument

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfFormFillerTest {

    @Test
    fun testAnalyzeForm_fieldTypesAndFallback() {
        val filler = PdfFormFiller()

        // Since pdfbox-android resources fail to load under Robolectric,
        // we test the private conversion method which doesn't trigger font loading.
        val method = PdfFormFiller::class.java.getDeclaredMethod("convertToFormField", PDField::class.java)
        method.isAccessible = true

        // Use a dummy PDDocument for field instantiation
        val doc = PDDocument()
        val acroForm = PDAcroForm(doc)

        val textField = PDTextField(acroForm)
        textField.partialName = "SampleField"

        val textResult = method.invoke(filler, textField) as FormField

        assertTrue(textResult is FormField.TextField)
        assertEquals("SampleField", textResult.name)

        val sigField = PDSignatureField(acroForm)
        sigField.partialName = "SigField"

        val sigResult = method.invoke(filler, sigField) as FormField

        assertTrue(sigResult is FormField.UnknownField)
        assertEquals("Signature", (sigResult as FormField.UnknownField).fieldType)
        assertEquals("SigField", sigResult.name)

        doc.close()
    }
}
