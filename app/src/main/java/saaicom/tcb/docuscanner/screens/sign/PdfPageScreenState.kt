package saaicom.tcb.docuscanner.screens.sign

import androidx.compose.ui.graphics.ImageBitmap

// Represents the state of our PDF renderer
data class PdfPageScreenState(
    val pageBitmap: ImageBitmap? = null,
    val pageNum: Int = 0,
    val pageCount: Int = 0,
    val errorMessage: String? = null
)