package com.yourname.pdftoolkit.util

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/**
 * Print utility for PDF documents.
 *
 * Provides a simple way to print PDFs using Android's PrintManager.
 * Works with any PDF URI (content://, file://, etc.).
 */
object PrintUtils {

    /**
     * Print a PDF document using the system print dialog.
     *
     * @param context Application context
     * @param uri PDF document URI to print
     * @param documentName Optional document name shown in print dialog (defaults to "PDF Document")
     * @return true if print job was started successfully, false otherwise
     */
    fun printPdf(context: Context, uri: Uri, documentName: String = "PDF Document"): Boolean {
        return try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: return false

            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback,
                    extras: Bundle?
                ) {
                    try {
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onLayoutCancelled()
                            return
                        }

                        val info = PrintDocumentInfo.Builder(documentName)
                            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                            .build()

                        callback.onLayoutFinished(info, true)
                    } catch (e: Exception) {
                        try {
                            callback.onLayoutFailed(e.message ?: "Print layout failed")
                        } catch (_: Exception) {
                        }
                    }
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback.onWriteCancelled()
                        return
                    }

                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: run {
                                callback.onWriteFailed("Cannot open document for printing")
                                return
                            }
                        input.use {
                            // Flush only: destination fd is owned by the print framework.
                            val output = FileOutputStream(destination.fileDescriptor)
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                if (cancellationSignal?.isCanceled == true) {
                                    callback.onWriteCancelled()
                                    return
                                }
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            return
                        }
                        callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: Exception) {
                        try {
                            callback.onWriteFailed(e.message ?: "Print failed")
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            printManager.print(documentName, printAdapter, PrintAttributes.Builder().build())
            true
        } catch (e: Exception) {
            // Print failed silently — do not crash
            false
        }
    }
}
