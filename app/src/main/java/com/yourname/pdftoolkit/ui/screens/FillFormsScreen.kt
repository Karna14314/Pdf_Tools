package com.yourname.pdftoolkit.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yourname.pdftoolkit.domain.operations.*
import com.yourname.pdftoolkit.util.FileOpener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Fill Forms Screen.
 */
class FillFormsViewModel : ViewModel() {
    private val _state = MutableStateFlow(FillFormsUiState())
    val state: StateFlow<FillFormsUiState> = _state.asStateFlow()
    
    private val formFiller = PdfFormFiller()
    
    fun setSourcePdf(uri: Uri, name: String, context: android.content.Context) {
        _state.value = _state.value.copy(
            sourceUri = uri, 
            sourceName = name,
            isAnalyzing = true
        )
        
        viewModelScope.launch {
            val result = formFiller.analyzeForm(context, uri)
            _state.value = _state.value.copy(
                isAnalyzing = false,
                hasForm = result.hasForm,
                fields = result.fields,
                totalFields = result.totalFields,
                fillableFields = result.fillableFields,
                analyzeError = result.errorMessage
            )
            
            // Initialize field values
            val initialValues = mutableMapOf<String, String>()
            result.fields.forEach { field ->
                when (field) {
                    is FormField.TextField -> initialValues[field.name] = field.value
                    is FormField.CheckBoxField -> initialValues[field.name] = if (field.isChecked) "true" else "false"
                    is FormField.RadioButtonField -> initialValues[field.name] = field.selectedOption ?: ""
                    is FormField.ComboBoxField -> initialValues[field.name] = field.selectedValue ?: ""
                    is FormField.ListBoxField -> initialValues[field.name] = field.selectedValues.firstOrNull() ?: ""
                    else -> {}
                }
            }
            _state.value = _state.value.copy(fieldValues = initialValues)
        }
    }
    
    fun updateFieldValue(fieldName: String, value: String) {
        val updatedValues = _state.value.fieldValues.toMutableMap()
        updatedValues[fieldName] = value
        _state.value = _state.value.copy(fieldValues = updatedValues)
    }
    
    fun toggleFlatten() {
        _state.value = _state.value.copy(flattenAfterFill = !_state.value.flattenAfterFill)
    }
    
    fun fillForm(
        context: android.content.Context,
        outputUri: Uri
    ) {
        val sourceUri = _state.value.sourceUri ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessing = true, progress = 0, error = null)
            
            // Convert string values to FieldValue objects
            val fieldValues = mutableMapOf<String, FieldValue>()
            
            _state.value.fields.forEach { field ->
                val value = _state.value.fieldValues[field.name] ?: return@forEach
                
                val fieldValue: FieldValue? = when (field) {
                    is FormField.TextField -> FieldValue.Text(value)
                    is FormField.CheckBoxField -> FieldValue.CheckBox(value == "true")
                    is FormField.RadioButtonField -> FieldValue.Radio(value)
                    is FormField.ComboBoxField -> FieldValue.Combo(value)
                    is FormField.ListBoxField -> FieldValue.ListValue(listOf(value))
                    else -> null
                }
                
                if (fieldValue != null) {
                    fieldValues[field.name] = fieldValue
                }
            }
            
            val result = formFiller.fillForm(
                context = context,
                inputUri = sourceUri,
                outputUri = outputUri,
                fieldValues = fieldValues,
                flatten = _state.value.flattenAfterFill,
                progressCallback = { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            )
            
            if (result.success) {
                com.yourname.pdftoolkit.data.SafUriManager.addRecentFile(context, outputUri)
            }
            
            _state.value = _state.value.copy(
                isProcessing = false,
                isComplete = result.success,
                error = result.errorMessage,
                fieldsUpdated = result.fieldsUpdated,
                resultUri = if (result.success) outputUri else null
            )
        }
    }
    
    fun reset() {
        _state.value = FillFormsUiState()
    }
}

data class FillFormsUiState(
    val sourceUri: Uri? = null,
    val sourceName: String = "",
    val isAnalyzing: Boolean = false,
    val hasForm: Boolean = false,
    val fields: List<FormField> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val totalFields: Int = 0,
    val fillableFields: Int = 0,
    val flattenAfterFill: Boolean = false,
    val isProcessing: Boolean = false,
    val progress: Int = 0,
    val isComplete: Boolean = false,
    val error: String? = null,
    val analyzeError: String? = null,
    val fieldsUpdated: Int = 0,
    val resultUri: Uri? = null
)

/**
 * Fill Forms Screen - Detect and fill PDF form fields.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillFormsScreen(
    onNavigateBack: () -> Unit,
    viewModel: FillFormsViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "Selected PDF"
            viewModel.setSourcePdf(it, name, context)
        }
    }
    
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { viewModel.fillForm(context, it) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fill PDF Forms") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Source PDF Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Select PDF Form",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    if (state.sourceUri != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = state.sourceName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (state.hasForm) {
                                    Text(
                                        text = "${state.fillableFields} fillable fields found",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }) {
                                Icon(Icons.Default.Edit, contentDescription = "Change")
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select PDF")
                        }
                    }
                }
            }
            
            // Analyzing State
            AnimatedVisibility(visible = state.isAnalyzing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Analyzing form fields...")
                    }
                }
            }
            
            // No Form Found
            AnimatedVisibility(visible = !state.isAnalyzing && state.sourceUri != null && !state.hasForm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "No Form Fields Found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "This PDF doesn't contain fillable form fields.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            
            // Form Fields
            AnimatedVisibility(
                visible = !state.isAnalyzing && state.hasForm && state.fields.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Card(
                    modifier = Modifier.fillMaxSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Form Fields",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(state.fields.filter { it !is FormField.UnknownField && !it.isReadOnly }) { field ->
                                FormFieldInput(
                                    field = field,
                                    value = state.fieldValues[field.name] ?: "",
                                    onValueChange = { viewModel.updateFieldValue(field.name, it) }
                                )
                            }
                        }
                    }
                }
            }
            
            // Options
            AnimatedVisibility(visible = state.hasForm) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Flatten After Filling",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Make fields non-editable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.flattenAfterFill,
                            onCheckedChange = { viewModel.toggleFlatten() }
                        )
                    }
                }
            }
            
            // Processing State
            AnimatedVisibility(visible = state.isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Filling form... ${state.progress}%")
                        LinearProgressIndicator(
                            progress = state.progress / 100f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // Success State
            AnimatedVisibility(visible = state.isComplete && !state.isProcessing) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Form Filled!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${state.fieldsUpdated} fields updated",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        state.resultUri?.let { uri ->
                            FilledTonalButton(
                                onClick = { FileOpener.openPdf(context, uri) }
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open")
                            }
                        }
                    }
                }
            }
            
            // Error State
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            
            // Fill Button
            Button(
                onClick = {
                    val fileName = "filled_${System.currentTimeMillis()}.pdf"
                    saveDocumentLauncher.launch(fileName)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.hasForm && !state.isProcessing && !state.isAnalyzing
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Filled Form")
            }
            
            // Reset Button
            if (state.isComplete) {
                OutlinedButton(
                    onClick = { viewModel.reset() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fill Another Form")
                }
            }
        }
    }
}

/**
 * Composable for individual form field input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormFieldInput(
    field: FormField,
    value: String,
    onValueChange: (String) -> Unit
) {
    when (field) {
        is FormField.TextField -> {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = !field.isMultiline,
                maxLines = if (field.isMultiline) 3 else 1,
                leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
            )
        }
        
        is FormField.CheckBoxField -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = value == "true",
                    onCheckedChange = { onValueChange(if (it) "true" else "false") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(field.name)
            }
        }
        
        is FormField.RadioButtonField -> {
            Column {
                Text(
                    text = field.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                field.options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == option,
                            onClick = { onValueChange(option) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(option)
                    }
                }
            }
        }
        
        is FormField.ComboBoxField -> {
            var expanded by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (field.isEditable) onValueChange(it) },
                    label = { Text(field.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    readOnly = !field.isEditable,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        
        is FormField.ListBoxField -> {
            var expanded by remember { mutableStateOf(false) }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {},
                    label = { Text(field.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    field.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValueChange(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        
        else -> {
            // Unsupported field type
        }
    }
}
