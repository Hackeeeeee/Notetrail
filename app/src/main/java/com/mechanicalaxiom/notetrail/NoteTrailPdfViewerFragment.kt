package com.mechanicalaxiom.notetrail

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresExtension
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment

@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
class NoteTrailPdfViewerFragment : PdfViewerFragment() {
    
    var onDocumentLoaded: (() -> Unit)? = null
    var onDocumentError: ((Throwable) -> Unit)? = null

    @SuppressLint("RestrictedApi")
    fun getPublicPdfView(): PdfView? {
        return try {
            pdfView
        } catch (e: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isDarkMode = arguments?.getBoolean("is_dark_mode", false) ?: false
        // Fragment-level theme logic if needed, but primarily used for argument passing
    }

    override fun onLoadDocumentSuccess() {
        super.onLoadDocumentSuccess()
        onDocumentLoaded?.invoke()
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        onDocumentError?.invoke(error)
    }
}
