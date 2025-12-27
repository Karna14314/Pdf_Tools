package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Represents a form field in the PDF.
 */
sealed class FormField {
    abstract val name: String
    abstract val isReadOnly: Boolean
    abstract val isRequired: Boolean
    
    data class TextField(
        override val name: String,
        val value: String,
        val maxLength: Int?,
        val isMultiline: Boolean,
        override val isReadOnly: Boolean,
        override val isRequired: Boolean
    ) : FormField()
    
    data class CheckBoxField(
        override val name: String,
        val isChecked: Boolean,
        override val isReadOnly: Boolean,
        override val isRequired: Boolean
    ) : FormField()
    
    data class RadioButtonField(
        override val name: String,
        val options: List<String>,
        val selectedOption: String?,
        override val isReadOnly: Boolean,
        override val isRequired: Boolean
    ) : FormField()
    
    data class ComboBoxField(
        override val name: String,
        val options: List<String>,
        val selectedValue: String?,
        val isEditable: Boolean,
        override val isReadOnly: Boolean,
        override val isRequired: Boolean
    ) : FormField()
    
    data class ListBoxField(
        override val name: String,
        val options: List<String>,
        val selectedValues: List<String>,
        val isMultiSelect: Boolean,
        override val isReadOnly: Boolean,
        override val isRequired: Boolean
    ) : FormField()
    
    data class UnknownField(
        override val name: String,
        val fieldType: String,
        override val isReadOnly: Boolean = true,
        override val isRequired: Boolean = false
    ) : FormField()
}

/**
 * Form field update value.
 */
sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class CheckBox(val checked: Boolean) : FieldValue()
    data class Radio(val selected: String) : FieldValue()
    data class Combo(val selected: String) : FieldValue()
    data class ListValue(val selected: kotlin.collections.List<String>) : FieldValue()
}

/**
 * Form analysis result.
 */
data class FormAnalysisResult(
    val hasForm: Boolean,
    val fields: List<FormField>,
    val totalFields: Int,
    val fillableFields: Int,
    val errorMessage: String? = null
)

/**
 * Form fill result.
 */
data class FormFillResult(
    val success: Boolean,
    val fieldsUpdated: Int,
    val errorMessage: String? = null
)

/**
 * PDF Form Filler - Detects and fills PDF form fields.
 * Supports text fields, checkboxes, radio buttons, combo boxes, and list boxes.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfFormFiller {
    
    /**
     * Analyze a PDF to detect form fields.
     *
     * @param context Android context
     * @param pdfUri PDF file URI
     * @return FormAnalysisResult with detected fields
     */
    suspend fun analyzeForm(
        context: Context,
        pdfUri: Uri
    ): FormAnalysisResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            val inputStream = context.contentResolver.openInputStream(pdfUri)
                ?: return@withContext FormAnalysisResult(
                    hasForm = false,
                    fields = emptyList(),
                    totalFields = 0,
                    fillableFields = 0,
                    errorMessage = "Cannot open PDF file"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            val acroForm: PDAcroForm? = document.documentCatalog.acroForm
            
            if (acroForm == null || acroForm.fields.isEmpty()) {
                document.close()
                return@withContext FormAnalysisResult(
                    hasForm = false,
                    fields = emptyList(),
                    totalFields = 0,
                    fillableFields = 0
                )
            }
            
            val fields = mutableListOf<FormField>()
            var fillableCount = 0
            
            for (field in acroForm.fieldTree) {
                val formField = convertToFormField(field)
                fields.add(formField)
                
                if (!formField.isReadOnly) {
                    fillableCount++
                }
            }
            
            document.close()
            
            FormAnalysisResult(
                hasForm = true,
                fields = fields,
                totalFields = fields.size,
                fillableFields = fillableCount
            )
            
        } catch (e: IOException) {
            document?.close()
            FormAnalysisResult(
                hasForm = false,
                fields = emptyList(),
                totalFields = 0,
                fillableFields = 0,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            FormAnalysisResult(
                hasForm = false,
                fields = emptyList(),
                totalFields = 0,
                fillableFields = 0,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Fill a PDF form with provided values.
     *
     * @param context Android context
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param fieldValues Map of field name to value
     * @param flatten Whether to flatten the form after filling
     * @param progressCallback Progress callback
     * @return FormFillResult with operation status
     */
    suspend fun fillForm(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        fieldValues: Map<String, FieldValue>,
        flatten: Boolean = false,
        progressCallback: (Int) -> Unit = {}
    ): FormFillResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            progressCallback(0)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext FormFillResult(
                    success = false,
                    fieldsUpdated = 0,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            progressCallback(20)
            
            val acroForm: PDAcroForm? = document.documentCatalog.acroForm
            
            if (acroForm == null) {
                document.close()
                return@withContext FormFillResult(
                    success = false,
                    fieldsUpdated = 0,
                    errorMessage = "PDF has no form"
                )
            }
            
            // Ensure appearances are generated
            acroForm.setNeedAppearances(true)
            
            var fieldsUpdated = 0
            val entries = fieldValues.entries.toList()
            
            for ((index, entry) in entries.withIndex()) {
                val (fieldName, value) = entry
                val field = acroForm.getField(fieldName)
                
                if (field != null) {
                    val updated = updateField(field, value)
                    if (updated) fieldsUpdated++
                }
                
                val progress = 20 + ((index + 1) * 50 / entries.size)
                progressCallback(progress)
            }
            
            progressCallback(70)
            
            // Flatten if requested
            if (flatten) {
                acroForm.flatten()
            }
            
            progressCallback(85)
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            document.close()
            progressCallback(100)
            
            FormFillResult(
                success = true,
                fieldsUpdated = fieldsUpdated
            )
            
        } catch (e: IOException) {
            document?.close()
            FormFillResult(
                success = false,
                fieldsUpdated = 0,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            FormFillResult(
                success = false,
                fieldsUpdated = 0,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Convert PDFBox field to our FormField type.
     */
    private fun convertToFormField(field: PDField): FormField {
        val name = field.fullyQualifiedName ?: "Unknown"
        val isReadOnly = field.isReadOnly
        val isRequired = field.isRequired
        
        return when (field) {
            is PDTextField -> {
                FormField.TextField(
                    name = name,
                    value = field.value ?: "",
                    maxLength = field.maxLen.takeIf { it > 0 },
                    isMultiline = field.isMultiline,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
            
            is PDCheckBox -> {
                FormField.CheckBoxField(
                    name = name,
                    isChecked = field.isChecked,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
            
            is PDRadioButton -> {
                val options = field.onValues?.toList() ?: emptyList()
                FormField.RadioButtonField(
                    name = name,
                    options = options,
                    selectedOption = field.value,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
            
            is PDComboBox -> {
                FormField.ComboBoxField(
                    name = name,
                    options = field.options ?: emptyList(),
                    selectedValue = field.value?.firstOrNull(),
                    isEditable = field.isEdit,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
            
            is PDListBox -> {
                FormField.ListBoxField(
                    name = name,
                    options = field.options ?: emptyList(),
                    selectedValues = field.value ?: emptyList(),
                    isMultiSelect = field.isMultiSelect,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
            
            else -> {
                FormField.UnknownField(
                    name = name,
                    fieldType = field.javaClass.simpleName,
                    isReadOnly = isReadOnly,
                    isRequired = isRequired
                )
            }
        }
    }
    
    /**
     * Update a form field with a value.
     */
    private fun updateField(field: PDField, value: FieldValue): Boolean {
        return try {
            when {
                field is PDTextField && value is FieldValue.Text -> {
                    field.value = value.value
                    true
                }
                
                field is PDCheckBox && value is FieldValue.CheckBox -> {
                    if (value.checked) {
                        field.check()
                    } else {
                        field.unCheck()
                    }
                    true
                }
                
                field is PDRadioButton && value is FieldValue.Radio -> {
                    field.value = value.selected
                    true
                }
                
                field is PDComboBox && value is FieldValue.Combo -> {
                    field.setValue(value.selected)
                    true
                }
                
                field is PDListBox && value is FieldValue.ListValue -> {
                    // PDListBox expects a List<String>
                    val valueList = java.util.ArrayList(value.selected)
                    field.setValue(valueList)
                    true
                }
                
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
