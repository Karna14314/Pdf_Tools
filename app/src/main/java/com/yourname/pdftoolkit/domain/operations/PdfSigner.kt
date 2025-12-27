package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a signature with drawing data.
 */
data class SignatureData(
    val paths: List<SignaturePath>,
    val strokeWidth: Float = 3f,
    val strokeColor: Int = Color.BLACK
)

/**
 * Represents a path in the signature (continuous stroke).
 */
data class SignaturePath(
    val points: List<SignaturePoint>
)

/**
 * Represents a point in the signature path.
 */
data class SignaturePoint(
    val x: Float,
    val y: Float
)

/**
 * Signature placement configuration.
 */
data class SignaturePlacement(
    val pageIndex: Int, // 0-indexed
    val x: Float, // X position on page (PDF coordinates, origin at bottom-left)
    val y: Float, // Y position on page
    val width: Float = 200f,
    val height: Float = 100f
)

/**
 * Additional elements to add with signature.
 */
data class SignatureExtras(
    val addDate: Boolean = false,
    val dateFormat: String = "yyyy-MM-dd",
    val addName: Boolean = false,
    val name: String = "",
    val addInitials: Boolean = false,
    val initials: String = ""
)

/**
 * Saved signature for reuse.
 */
data class SavedSignature(
    val id: String,
    val name: String,
    val bitmapPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Result of signature operation.
 */
data class SignatureResult(
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * PDF Signer - Adds digital signatures (visual representation) to PDF documents.
 * This creates a visual signature image on the PDF, not a cryptographic signature.
 * Uses Apache PDFBox-Android (Apache 2.0 License).
 */
class PdfSigner(private val context: Context) {
    
    private val signaturesDir: File by lazy {
        File(context.filesDir, "signatures").also { it.mkdirs() }
    }
    
    /**
     * Add a signature to a PDF document.
     *
     * @param inputUri Source PDF file URI
     * @param outputUri Destination PDF file URI  
     * @param signatureData Signature drawing data
     * @param placement Where to place the signature
     * @param extras Optional date, name, initials
     * @param progressCallback Progress callback (0-100)
     * @return SignatureResult with operation status
     */
    suspend fun addSignature(
        inputUri: Uri,
        outputUri: Uri,
        signatureData: SignatureData,
        placement: SignaturePlacement,
        extras: SignatureExtras = SignatureExtras(),
        progressCallback: (Int) -> Unit = {}
    ): SignatureResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            progressCallback(0)
            
            // Load the PDF document
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            progressCallback(20)
            
            // Validate page index
            if (placement.pageIndex < 0 || placement.pageIndex >= document.numberOfPages) {
                document.close()
                return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Invalid page number"
                )
            }
            
            // Create signature bitmap from drawing data
            val signatureBitmap = createSignatureBitmap(
                signatureData,
                placement.width.toInt(),
                placement.height.toInt()
            )
            
            progressCallback(40)
            
            // Get the target page
            val page = document.getPage(placement.pageIndex)
            
            // Add signature image to the page
            addSignatureToPage(document, page, signatureBitmap, placement)
            
            progressCallback(60)
            
            // Add extras (date, name, initials)
            if (extras.addDate || extras.addName || extras.addInitials) {
                addSignatureExtras(document, page, placement, extras)
            }
            
            progressCallback(80)
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            signatureBitmap.recycle()
            document.close()
            progressCallback(100)
            
            SignatureResult(success = true)
            
        } catch (e: IOException) {
            document?.close()
            SignatureResult(
                success = false,
                errorMessage = "IO Error: ${e.message}"
            )
        } catch (e: Exception) {
            document?.close()
            SignatureResult(
                success = false,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Add signature from a saved signature.
     */
    suspend fun addSignatureFromSaved(
        inputUri: Uri,
        outputUri: Uri,
        savedSignature: SavedSignature,
        placement: SignaturePlacement,
        extras: SignatureExtras = SignatureExtras(),
        progressCallback: (Int) -> Unit = {}
    ): SignatureResult = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            progressCallback(0)
            
            // Load saved signature bitmap
            val signatureFile = File(savedSignature.bitmapPath)
            if (!signatureFile.exists()) {
                return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Saved signature not found"
                )
            }
            
            val signatureBitmap = android.graphics.BitmapFactory.decodeFile(signatureFile.absolutePath)
                ?: return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Cannot load saved signature"
                )
            
            progressCallback(20)
            
            // Load the PDF document
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Cannot open source PDF"
                )
            
            document = PDDocument.load(inputStream)
            inputStream.close()
            
            progressCallback(40)
            
            // Validate page index
            if (placement.pageIndex < 0 || placement.pageIndex >= document.numberOfPages) {
                signatureBitmap.recycle()
                document.close()
                return@withContext SignatureResult(
                    success = false,
                    errorMessage = "Invalid page number"
                )
            }
            
            val page = document.getPage(placement.pageIndex)
            
            // Scale bitmap to fit placement size
            val scaledBitmap = Bitmap.createScaledBitmap(
                signatureBitmap,
                placement.width.toInt(),
                placement.height.toInt(),
                true
            )
            signatureBitmap.recycle()
            
            progressCallback(60)
            
            // Add signature to page
            addSignatureToPage(document, page, scaledBitmap, placement)
            
            // Add extras
            if (extras.addDate || extras.addName || extras.addInitials) {
                addSignatureExtras(document, page, placement, extras)
            }
            
            progressCallback(80)
            
            // Save the document
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                document.save(outputStream)
            }
            
            scaledBitmap.recycle()
            document.close()
            progressCallback(100)
            
            SignatureResult(success = true)
            
        } catch (e: Exception) {
            document?.close()
            SignatureResult(
                success = false,
                errorMessage = "Error: ${e.message}"
            )
        }
    }
    
    /**
     * Save a signature for reuse.
     */
    suspend fun saveSignature(
        signatureData: SignatureData,
        name: String
    ): SavedSignature = withContext(Dispatchers.IO) {
        val id = System.currentTimeMillis().toString()
        val bitmap = createSignatureBitmap(signatureData, 400, 200)
        
        val signatureFile = File(signaturesDir, "signature_$id.png")
        FileOutputStream(signatureFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        bitmap.recycle()
        
        SavedSignature(
            id = id,
            name = name,
            bitmapPath = signatureFile.absolutePath
        )
    }
    
    /**
     * Get all saved signatures.
     */
    fun getSavedSignatures(): List<SavedSignature> {
        val signatures = mutableListOf<SavedSignature>()
        
        signaturesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("signature_") && file.name.endsWith(".png")) {
                val id = file.name.removePrefix("signature_").removeSuffix(".png")
                signatures.add(
                    SavedSignature(
                        id = id,
                        name = "Signature $id",
                        bitmapPath = file.absolutePath,
                        createdAt = file.lastModified()
                    )
                )
            }
        }
        
        return signatures.sortedByDescending { it.createdAt }
    }
    
    /**
     * Delete a saved signature.
     */
    fun deleteSignature(signature: SavedSignature): Boolean {
        return File(signature.bitmapPath).delete()
    }
    
    /**
     * Create a bitmap from signature drawing data.
     */
    private fun createSignatureBitmap(
        data: SignatureData,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Transparent background
        canvas.drawColor(Color.TRANSPARENT)
        
        val paint = Paint().apply {
            color = data.strokeColor
            strokeWidth = data.strokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        
        // Find bounds of the signature
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        
        for (path in data.paths) {
            for (point in path.points) {
                minX = minOf(minX, point.x)
                maxX = maxOf(maxX, point.x)
                minY = minOf(minY, point.y)
                maxY = maxOf(maxY, point.y)
            }
        }
        
        // Scale and center the signature
        val signatureWidth = maxX - minX
        val signatureHeight = maxY - minY
        
        val scaleX = if (signatureWidth > 0) (width - 20f) / signatureWidth else 1f
        val scaleY = if (signatureHeight > 0) (height - 20f) / signatureHeight else 1f
        val scale = minOf(scaleX, scaleY)
        
        val offsetX = (width - signatureWidth * scale) / 2 - minX * scale
        val offsetY = (height - signatureHeight * scale) / 2 - minY * scale
        
        // Draw paths
        for (signaturePath in data.paths) {
            if (signaturePath.points.isEmpty()) continue
            
            val path = Path()
            val firstPoint = signaturePath.points.first()
            path.moveTo(
                firstPoint.x * scale + offsetX,
                firstPoint.y * scale + offsetY
            )
            
            for (i in 1 until signaturePath.points.size) {
                val point = signaturePath.points[i]
                path.lineTo(
                    point.x * scale + offsetX,
                    point.y * scale + offsetY
                )
            }
            
            canvas.drawPath(path, paint)
        }
        
        return bitmap
    }
    
    /**
     * Add signature image to a PDF page.
     */
    private fun addSignatureToPage(
        document: PDDocument,
        page: PDPage,
        bitmap: Bitmap,
        placement: SignaturePlacement
    ) {
        val pdImage = LosslessFactory.createFromImage(document, bitmap)
        
        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )
        
        try {
            contentStream.drawImage(
                pdImage,
                placement.x,
                placement.y,
                placement.width,
                placement.height
            )
        } finally {
            contentStream.close()
        }
    }
    
    /**
     * Add signature extras (date, name, initials) below the signature.
     */
    private fun addSignatureExtras(
        document: PDDocument,
        page: PDPage,
        placement: SignaturePlacement,
        extras: SignatureExtras
    ) {
        val contentStream = PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        )
        
        try {
            val font = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
            val fontSize = 10f
            var yOffset = placement.y - 15f
            
            contentStream.setFont(font, fontSize)
            contentStream.setNonStrokingColor(0, 0, 0) // Black
            
            // Add name
            if (extras.addName && extras.name.isNotBlank()) {
                contentStream.beginText()
                contentStream.newLineAtOffset(placement.x, yOffset)
                contentStream.showText(extras.name)
                contentStream.endText()
                yOffset -= 12f
            }
            
            // Add date
            if (extras.addDate) {
                val dateFormat = SimpleDateFormat(extras.dateFormat, Locale.getDefault())
                val dateString = "Date: ${dateFormat.format(Date())}"
                
                contentStream.beginText()
                contentStream.newLineAtOffset(placement.x, yOffset)
                contentStream.showText(dateString)
                contentStream.endText()
                yOffset -= 12f
            }
            
            // Add initials
            if (extras.addInitials && extras.initials.isNotBlank()) {
                contentStream.beginText()
                contentStream.newLineAtOffset(placement.x, yOffset)
                contentStream.showText("Initials: ${extras.initials}")
                contentStream.endText()
            }
            
        } finally {
            contentStream.close()
        }
    }
}
