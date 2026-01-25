package com.yourname.pdftoolkit.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom navigation tabs - 2 tabs only (Settings is in top bar).
 */
enum class BottomNavTab(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    TOOLS("tools", "Tools", Icons.Default.Build),
    FILES("files", "Files", Icons.Default.Folder)
}

/**
 * Sealed class representing all navigation destinations in the app.
 * Each screen has a unique route string for navigation.
 */
sealed class Screen(val route: String) {
    // Main tabs
    object Tools : Screen("tools")
    object Files : Screen("files")
    object Settings : Screen("settings")
    
    // PDF Viewer
    object PdfViewer : Screen("pdf_viewer?uri={uri}&name={name}") {
        fun createRoute(uri: String, name: String): String {
            return "pdf_viewer?uri=$uri&name=$name"
        }
    }
    
    // PDF Tools
    object Merge : Screen("merge")
    object Split : Screen("split")
    object Compress : Screen("compress")
    object Convert : Screen("convert")
    object PdfToImage : Screen("pdf_to_image")
    object Extract : Screen("extract")
    object Rotate : Screen("rotate")
    object Security : Screen("security")
    object Metadata : Screen("metadata")
    object PageNumber : Screen("page_number")
    object Organize : Screen("organize")
    object Reorder : Screen("reorder")
    object Unlock : Screen("unlock")
    object Repair : Screen("repair")
    object HtmlToPdf : Screen("html_to_pdf")
    object ExtractText : Screen("extract_text")
    object Watermark : Screen("watermark")
    object Flatten : Screen("flatten")
    object SignPdf : Screen("sign_pdf")
    object FillForms : Screen("fill_forms")
    object Annotate : Screen("annotate")
    object ScanToPdf : Screen("scan_to_pdf")
    object Ocr : Screen("ocr")
    object ImageTools : Screen("image_tools")
    
    // Legacy compatibility
    object Home : Screen("tools")
    
    companion object {
        /**
         * Returns the Screen object for a given tool ID.
         * Used to navigate from ToolsScreen tool cards.
         */
        fun fromToolId(toolId: String): Screen {
            return when (toolId) {
                "merge" -> Merge
                "split" -> Split
                "compress" -> Compress
                "image_to_pdf" -> Convert
                "pdf_to_image" -> PdfToImage
                "reorder" -> Reorder
                "delete_pages" -> Organize
                "rotate" -> Rotate
                "extract" -> Extract
                "html_to_pdf" -> HtmlToPdf
                "scan_to_pdf" -> ScanToPdf
                "ocr" -> Ocr
                "extract_text" -> ExtractText
                "lock" -> Security
                "unlock" -> Unlock
                "watermark" -> Watermark
                "sign" -> SignPdf
                "image_compress", "image_resize", "image_convert", "image_metadata" -> ImageTools
                "page_numbers" -> PageNumber
                "metadata" -> Metadata
                "flatten" -> Flatten
                "annotate" -> Annotate
                "fill_forms" -> FillForms
                "repair" -> Repair
                else -> Tools
            }
        }
        
        /**
         * Returns the Screen object for a given feature title.
         * Used for legacy HomeScreen compatibility.
         */
        fun fromFeatureTitle(title: String): Screen {
            return when (title) {
                "Merge PDFs" -> Merge
                "Split PDF" -> Split
                "Compress PDF" -> Compress
                "Images to PDF" -> Convert
                "PDF to Images" -> PdfToImage
                "Extract Pages" -> Extract
                "Rotate Pages" -> Rotate
                "Add Security" -> Security
                "View Metadata" -> Metadata
                "Page Numbers" -> PageNumber
                "Organize Pages" -> Organize
                "Unlock PDF" -> Unlock
                "Repair PDF" -> Repair
                "HTML to PDF" -> HtmlToPdf
                "Extract Text" -> ExtractText
                "Add Watermark" -> Watermark
                "Flatten PDF" -> Flatten
                "Sign PDF" -> SignPdf
                "Fill Forms" -> FillForms
                "Annotate PDF" -> Annotate
                "Scan to PDF" -> ScanToPdf
                "OCR" -> Ocr
                "Image Tools" -> ImageTools
                else -> Tools
            }
        }
    }
}
