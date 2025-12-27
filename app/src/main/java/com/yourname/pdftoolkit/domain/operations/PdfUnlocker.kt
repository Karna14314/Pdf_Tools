package com.yourname.pdftoolkit.domain.operations

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Result of an unlock operation.
 */
data class UnlockResult(
    val wasEncrypted: Boolean,
    val successfullyUnlocked: Boolean,
    val originalPermissions: PdfPermissions? = null
)

/**
 * Represents the permissions that were on an encrypted PDF.
 */
data class PdfPermissions(
    val canPrint: Boolean,
    val canModify: Boolean,
    val canCopy: Boolean,
    val canAnnotate: Boolean,
    val canFillForms: Boolean
)

/**
 * Error types for unlock operations.
 */
sealed class UnlockError : Exception() {
    object FileNotFound : UnlockError() {
        private fun readResolve(): Any = FileNotFound
        override val message: String = "Cannot open input file"
    }
    object NotEncrypted : UnlockError() {
        private fun readResolve(): Any = NotEncrypted
        override val message: String = "PDF is not encrypted"
    }
    object IncorrectPassword : UnlockError() {
        private fun readResolve(): Any = IncorrectPassword
        override val message: String = "Incorrect password"
    }
    object EmptyDocument : UnlockError() {
        private fun readResolve(): Any = EmptyDocument
        override val message: String = "PDF has no pages"
    }
    data class GenericError(override val message: String) : UnlockError()
}

/**
 * Handles PDF unlocking/decryption operations.
 * Removes password protection from encrypted PDFs.
 */
class PdfUnlocker {
    
    /**
     * Unlock a password-protected PDF and save it without encryption.
     * 
     * @param context Android context
     * @param inputUri URI of the encrypted PDF
     * @param outputStream Output stream for the unlocked PDF
     * @param password Password to unlock the PDF (owner or user password)
     * @param onProgress Progress callback (0.0 to 1.0)
     * @return UnlockResult with operation details
     */
    suspend fun unlockPdf(
        context: Context,
        inputUri: Uri,
        outputStream: OutputStream,
        password: String,
        onProgress: (Float) -> Unit = {}
    ): Result<UnlockResult> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        
        try {
            onProgress(0.1f)
            
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: return@withContext Result.failure(UnlockError.FileNotFound)
            
            onProgress(0.2f)
            
            // Try to load with the provided password
            val loadedDoc = try {
                PDDocument.load(inputStream, password)
            } catch (e: Exception) {
                val message = e.message?.lowercase() ?: ""
                when {
                    message.contains("password") || message.contains("decrypt") -> {
                        return@withContext Result.failure(UnlockError.IncorrectPassword)
                    }
                    else -> throw e
                }
            }
            
            document = loadedDoc
            
            onProgress(0.4f)
            
            // Check if the document was actually encrypted
            val wasEncrypted = loadedDoc.isEncrypted
            
            if (!wasEncrypted) {
                // Try to detect if it was password-protected by checking access permissions
                val accessPermission = loadedDoc.currentAccessPermission
                val hadRestrictions = accessPermission?.let {
                    !it.canPrint() || !it.canModify() || !it.canExtractContent()
                } ?: false
                
                if (!hadRestrictions) {
                    loadedDoc.close()
                    return@withContext Result.failure(UnlockError.NotEncrypted)
                }
            }
            
            onProgress(0.5f)
            
            // Get original permissions for reporting
            val originalPermissions = loadedDoc.currentAccessPermission?.let { perm ->
                PdfPermissions(
                    canPrint = perm.canPrint(),
                    canModify = perm.canModify(),
                    canCopy = perm.canExtractContent(),
                    canAnnotate = perm.canModifyAnnotations(),
                    canFillForms = perm.canFillInForm()
                )
            }
            
            onProgress(0.6f)
            
            val pageCount = loadedDoc.numberOfPages
            if (pageCount == 0) {
                return@withContext Result.failure(UnlockError.EmptyDocument)
            }
            
            onProgress(0.7f)
            
            // Remove all security from the document
            loadedDoc.isAllSecurityToBeRemoved = true
            
            onProgress(0.85f)
            
            // Save the unlocked document
            loadedDoc.save(outputStream)
            
            onProgress(1.0f)
            
            Result.success(
                UnlockResult(
                    wasEncrypted = wasEncrypted,
                    successfullyUnlocked = true,
                    originalPermissions = originalPermissions
                )
            )
            
        } catch (e: UnlockError) {
            Result.failure(e)
        } catch (e: Exception) {
            val message = e.message?.lowercase() ?: ""
            when {
                message.contains("password") || message.contains("decrypt") -> {
                    Result.failure(UnlockError.IncorrectPassword)
                }
                else -> Result.failure(UnlockError.GenericError(e.message ?: "Unknown error"))
            }
        } finally {
            document?.close()
        }
    }
    
    /**
     * Check if a PDF is encrypted/password-protected.
     * 
     * @param context Android context
     * @param uri URI of the PDF to check
     * @return true if the PDF is encrypted, false otherwise
     */
    suspend fun isEncrypted(
        context: Context,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Try to load without password - if it fails with password error, it's encrypted
                try {
                    PDDocument.load(inputStream).use { doc ->
                        doc.isEncrypted
                    }
                } catch (e: Exception) {
                    // If loading fails with a password error, it's encrypted
                    e.message?.lowercase()?.contains("password") == true ||
                    e.message?.lowercase()?.contains("decrypt") == true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if a PDF requires a password to open (user password).
     * This is different from owner-password-only encryption.
     * 
     * @param context Android context
     * @param uri URI of the PDF to check
     * @return true if a password is required to open the PDF
     */
    suspend fun requiresPasswordToOpen(
        context: Context,
        uri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    PDDocument.load(inputStream).use { _ ->
                        // If we can load without password, no user password is required
                        false
                    }
                } catch (e: Exception) {
                    // If loading fails, a password is required to open
                    true
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Validate if a password is correct for a given PDF.
     * 
     * @param context Android context
     * @param uri URI of the encrypted PDF
     * @param password Password to validate
     * @return true if the password is correct
     */
    suspend fun validatePassword(
        context: Context,
        uri: Uri,
        password: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                try {
                    PDDocument.load(inputStream, password).use { _ ->
                        true // Password is correct
                    }
                } catch (e: Exception) {
                    false // Password is incorrect
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Get the encryption details of a PDF (if accessible).
     * 
     * @param context Android context
     * @param uri URI of the PDF
     * @param password Optional password to access encrypted PDF
     * @return PdfPermissions if accessible, null otherwise
     */
    suspend fun getPermissions(
        context: Context,
        uri: Uri,
        password: String? = null
    ): PdfPermissions? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val doc = if (password != null) {
                    PDDocument.load(inputStream, password)
                } else {
                    PDDocument.load(inputStream)
                }
                
                doc.use { document ->
                    document.currentAccessPermission?.let { perm ->
                        PdfPermissions(
                            canPrint = perm.canPrint(),
                            canModify = perm.canModify(),
                            canCopy = perm.canExtractContent(),
                            canAnnotate = perm.canModifyAnnotations(),
                            canFillForms = perm.canFillInForm()
                        )
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
