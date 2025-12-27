package com.yourname.pdftoolkit.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider

/**
 * Utility for opening files with external applications.
 */
object FileOpener {
    
    /**
     * Open a PDF file with the default PDF viewer.
     * 
     * @param context Android context
     * @param uri URI of the PDF file
     * @return true if intent was launched successfully
     */
    fun openPdf(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                // Try with a chooser
                val chooser = Intent.createChooser(intent, "Open PDF with...")
                chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(chooser)
                true
            }
        } catch (e: Exception) {
            Toast.makeText(context, "No PDF viewer available", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * Open an image file with the default image viewer.
     * 
     * @param context Android context
     * @param uri URI of the image file
     * @return true if intent was launched successfully
     */
    fun openImage(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "No image viewer available", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * Open a text file with the default text viewer.
     * 
     * @param context Android context
     * @param uri URI of the text file
     * @return true if intent was launched successfully
     */
    fun openTextFile(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/plain")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "No text viewer available", Toast.LENGTH_SHORT).show()
            false
        }
    }
    
    /**
     * Share a file using the system share dialog.
     * 
     * @param context Android context
     * @param uri URI of the file
     * @param mimeType MIME type of the file
     * @param title Title for the share dialog
     */
    fun shareFile(context: Context, uri: Uri, mimeType: String, title: String = "Share") {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            val chooser = Intent.createChooser(intent, title).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show()
        }
    }
}
