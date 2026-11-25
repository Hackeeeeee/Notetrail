package com.mechanicalaxiom.notetrail

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.mechanicalaxiom.notetrail.databinding.ActivityPdfViewerBinding
import com.mechanicalaxiom.notetrail.ui.theme.GitHubScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding

    private var pdfRenderer: PdfRenderer? = null
    private var currentPage: PdfRenderer.Page? = null
    private var currentPageNumber = 0
    private var pdfFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.pdfToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val url = intent.getStringExtra("pdf_url")
        val title = intent.getStringExtra("pdf_title")

        supportActionBar?.title = title ?: "PDF Viewer"

        if (url != null) {
            binding.pdfProgressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                pdfFile = downloadPdf(url)
                pdfFile?.let {
                    openPdfRenderer(it)
                    showPage(currentPageNumber)
                }
                binding.pdfProgressBar.visibility = View.GONE
            }
        } else {
            Toast.makeText(this, "Error: PDF URL is missing.", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun downloadPdf(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = url.substringAfterLast("/")
            val file = File(cacheDir, "temp.pdf")
            val client = OkHttpClient()
            val request = GitHubScanner.getAuthenticatedRequest(fileName)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun openPdfRenderer(file: File) {
        try {
            val fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(fileDescriptor)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load PDF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPage(pageIndex: Int) {
        if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pdfRenderer!!.pageCount) return

        currentPage?.close()
        currentPage = pdfRenderer?.openPage(pageIndex)
        currentPageNumber = pageIndex

        currentPage?.let { page ->
            val bitmap = createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            binding.pdfImageView.setImageBitmap(bitmap)
        }
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.action_previous_page)?.isEnabled = currentPageNumber > 0
        menu?.findItem(R.id.action_next_page)?.isEnabled =
            pdfRenderer?.let { currentPageNumber < it.pageCount - 1 } ?: false
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_previous_page -> { showPage(currentPageNumber - 1); true }
            R.id.action_next_page -> { showPage(currentPageNumber + 1); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentPage?.close()
        pdfRenderer?.close()
    }
}