package saaicom.tcb.docuscanner.screens.sign

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory // <-- NEW IMPORT
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.SignatureRepository // <-- NEW IMPORT
import java.io.File // <-- NEW IMPORT
import java.io.IOException

// UPDATED: This data class now holds the File and Bitmap
data class Signature(val file: File, val bitmap: Bitmap)

// This is a plain AndroidViewModel, NO Hilt
class PdfSignViewModel(application: Application) : AndroidViewModel(application) {

    private var pdfRenderer: PdfRenderer? = null
    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var currentPage: PdfRenderer.Page? = null

    // --- PDF Page State ---
    private val _uiState = MutableStateFlow(PdfPageScreenState())
    val uiState = _uiState.asStateFlow()

    // --- Signature List State ---
    private val _signatures = MutableStateFlow<List<Signature>>(emptyList())
    val signatures = _signatures.asStateFlow()

    fun loadPdf(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                closePdf()

                parcelFileDescriptor = getApplication<Application>()
                    .contentResolver
                    .openFileDescriptor(uri, "r")

                if (parcelFileDescriptor == null) {
                    throw IOException("Could not open file descriptor")
                }

                pdfRenderer = PdfRenderer(parcelFileDescriptor!!)
                val pageCount = pdfRenderer!!.pageCount

                if (pageCount > 0) {
                    _uiState.update { it.copy(pageCount = pageCount) }
                    openPage(0)
                } else {
                    _uiState.update { it.copy(errorMessage = "PDF is empty") }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { it.copy(errorMessage = "Error loading PDF: ${e.message}") }
            }
        }
    }

    private fun openPage(pageIndex: Int) {
        if (pdfRenderer == null || pageIndex < 0 || pageIndex >= pdfRenderer!!.pageCount) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            currentPage?.close()

            currentPage = pdfRenderer!!.openPage(pageIndex)

            val bitmap = Bitmap.createBitmap(
                currentPage!!.width,
                currentPage!!.height,
                Bitmap.Config.ARGB_8888
            )

            currentPage!!.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            _uiState.update {
                it.copy(
                    pageBitmap = bitmap.asImageBitmap(),
                    pageNum = pageIndex,
                    errorMessage = null
                )
            }
        }
    }

    fun goToNextPage() {
        val next = _uiState.value.pageNum + 1
        if (next < _uiState.value.pageCount) {
            openPage(next)
        }
    }

    fun goToPreviousPage() {
        val prev = _uiState.value.pageNum - 1
        if (prev >= 0) {
            openPage(prev)
        }
    }

    // --- Signature Loading Logic ---
    // *** THIS IS NOW IMPLEMENTED ***
    fun loadSignatures() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            // 1. Get the list of files from the repository
            val signatureFiles = SignatureRepository.getSavedSignatures(context)

            // 2. Load each file into a Bitmap
            val signatureData = signatureFiles.mapNotNull { file ->
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        Signature(file, bitmap)
                    } else {
                        null // Skip if bitmap is null
                    }
                } catch (e: Exception) {
                    null // Skip if file is corrupt
                }
            }
            _signatures.value = signatureData
        }
    }

    // --- Cleanup ---
    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.IO) {
            closePdf()
        }
    }

    private fun closePdf() {
        try {
            currentPage?.close()
            pdfRenderer?.close()
            parcelFileDescriptor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        currentPage = null
        pdfRenderer = null
        parcelFileDescriptor = null
    }
}