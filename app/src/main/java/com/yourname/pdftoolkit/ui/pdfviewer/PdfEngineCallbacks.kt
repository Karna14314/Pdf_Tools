package com.yourname.pdftoolkit.ui.pdfviewer

interface PdfEngineCallbacks {
    fun onError(error: Throwable)
    fun onFallbackRequired()
    fun onPageChanged(current: Int, total: Int)
}
