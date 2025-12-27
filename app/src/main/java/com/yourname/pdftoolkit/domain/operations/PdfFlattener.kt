package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Flattening options for PDF.
 */
data class FlattenConfig(
    val flattenAnnotations: Boolean = true,
    val flattenForms: Boolean = true,
    val removeJavaScript: Boolean = true,
    val removeEmbeddedFiles: Boolean = false
)

/**
 * Result of flatten operation.
 */
data class FlattenResult(
    val success: Boolean,
    val annotationsFlattened: Int = 0,
    val formsFlattened: Int = 0,
    val errorMessage: String? = null
)

/**
 * PDF Flattener - Flattens annotations, forms, and interactive elements.
 * This makes the PDF suitable for printing and sharing where interactive
 * elements are not needed.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfFlattener {
    
    /**
     * Flatten a PDF document.
     * 
     * Flattening merges annotations and form fields into the page content,
     * making them non-editable and ensuring consistent appearance across viewers.
     *
     * @param context Android context for file operations
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI
     * @param config Flatten configuration options
     * @param progressCallback Progress callback (0-100)
     * @return FlattenResult with operation status
     */
    suspend fun flattenPdf(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        config: FlattenConfig = FlattenConfig(),
        progressCallback: (Int) -> Unit = {}
    ): FlattenResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var annotationsCount = 0
        var formsCount = 0
        
        try {
            progressCallback(0)
            
            // Load the PDF document
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext FlattenResult(
                    success = false,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            val totalPages = document.numberOfPages
            if (totalPages == 0) {
                document.close()
                return@withContext FlattenResult(
                    success = false,
                    errorMessage = "PDF has no pages"
                )
            }
            
            progressCallback(10)
            
            // Flatten forms if requested
            if (config.flattenForms) {
                formsCount = flattenForms(document)
                progressCallback(30)
            }
            
            // Flatten annotations on each page
            if (config.flattenAnnotations) {
                for (pageIndex in 0 until totalPages) {
                    val page = document.getPage(pageIndex)
                    annotationsCount += flattenPageAnnotations(document, page)
                    
                    val progress = 30 + ((pageIndex + 1) * 50 / totalPages)
                    progressCallback(progress)
                }
            }
            
            progressCallback(80)
            
            // Remove JavaScript if requested
            if (config.removeJavaScript) {
                removeJavaScript(document)
            }
            
            // Remove embedded files if requested
            if (config.removeEmbeddedFiles) {
                removeEmbeddedFiles(document)
            }
            
            progressCallback(90)
            
            // Save the flattened document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            document.close()
            progressCallback(100)
            
            FlattenResult(
                success = true,
                annotationsFlattened = annotationsCount,
                formsFlattened = formsCount
            )
            
        } catch (e: IOException) {
            document?.close()
            FlattenResult(
                success = false,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            FlattenResult(
                success = false,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Flatten all form fields in the document.
     * This renders the field values as regular text and removes the form interactivity.
     */
    private fun flattenForms(document: PDDocument): Int {
        val acroForm: PDAcroForm? = document.documentCatalog.acroForm
        
        if (acroForm == null || acroForm.fields.isEmpty()) {
            return 0
        }
        
        var count = 0
        
        try {
            // Set NeedAppearances to false to ensure appearances are generated
            acroForm.setNeedAppearances(false)
            
            // Flatten the form
            acroForm.flatten()
            count = acroForm.fields.size
            
        } catch (e: Exception) {
            // Form flattening may fail for some complex forms
            // Log and continue
        }
        
        return count
    }
    
    /**
     * Flatten annotations on a page.
     * This renders annotations into the page content stream.
     */
    private fun flattenPageAnnotations(document: PDDocument, page: PDPage): Int {
        val annotations = page.annotations ?: return 0
        
        if (annotations.isEmpty()) {
            return 0
        }
        
        var count = 0
        val annotationsToRemove = mutableListOf<PDAnnotation>()
        
        for (annotation in annotations) {
            try {
                // Get the annotation appearance
                val appearance = annotation.normalAppearanceStream
                
                if (appearance != null) {
                    // Draw the annotation appearance onto the page
                    val contentStream = PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    )
                    
                    try {
                        val rect = annotation.rectangle
                        if (rect != null) {
                            // Transform and draw the appearance stream
                            contentStream.saveGraphicsState()
                            contentStream.transform(
                                com.tom_roush.pdfbox.util.Matrix(
                                    1f, 0f, 0f, 1f,
                                    rect.lowerLeftX, rect.lowerLeftY
                                )
                            )
                            contentStream.drawForm(appearance)
                            contentStream.restoreGraphicsState()
                        }
                    } finally {
                        contentStream.close()
                    }
                    
                    annotationsToRemove.add(annotation)
                    count++
                }
            } catch (e: Exception) {
                // Skip annotations that can't be flattened
                continue
            }
        }
        
        // Remove the flattened annotations from page
        if (annotationsToRemove.isNotEmpty()) {
            try {
                val updatedAnnotations = annotations.toMutableList()
                updatedAnnotations.removeAll(annotationsToRemove.toSet())
                page.setAnnotations(updatedAnnotations)
            } catch (e: Exception) {
                // Ignore removal errors
            }
        }
        
        return count
    }
    
    /**
     * Remove JavaScript from the document.
     */
    private fun removeJavaScript(document: PDDocument) {
        try {
            // Remove document-level JavaScript
            val names = document.documentCatalog.names
            if (names != null) {
                // Try to set JavaScript to null
                try {
                    names.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.JAVA_SCRIPT)
                } catch (e: Exception) {
                    // Ignore if cannot remove
                }
            }
            
            // Remove OpenAction if it contains JavaScript
            try {
                document.documentCatalog.setOpenAction(null)
            } catch (e: Exception) {
                // Ignore if cannot remove
            }
            
        } catch (e: Exception) {
            // JavaScript removal may fail for some documents
        }
    }
    
    /**
     * Remove embedded files from the document.
     */
    private fun removeEmbeddedFiles(document: PDDocument) {
        try {
            val names = document.documentCatalog.names
            if (names != null) {
                names.embeddedFiles = null
            }
        } catch (e: Exception) {
            // Embedded files removal may fail
        }
    }
}
