package saaicom.tcb.docuscanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.models.FileItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

object FileActions {

    private const val FILE_PROVIDER_AUTHORITY = "saaicom.tcb.docuscanner.provider"

    // A4 Dimensions in PostScript points (1/72 inch)
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    // OPTIMIZATION SETTING:
    // 1024px is roughly 120-130 DPI for A4.
    // This allows for clear text and color while keeping file size low (typically ~300KB-500KB per page in Color).
    private const val MAX_PRINT_DIMENSION = 1024

    private fun getFileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    /**
     * Loads a bitmap resized to fit the A4 page efficiently.
     * Uses RGB_565 to reduce memory usage by 50% compared to standard ARGB_8888.
     */
    private fun loadOptimizedBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            // 1. Decode bounds to check size
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            // 2. Calculate optimal scale
            var inSampleSize = 1
            if (options.outHeight > MAX_PRINT_DIMENSION || options.outWidth > MAX_PRINT_DIMENSION) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= MAX_PRINT_DIMENSION && (halfWidth / inSampleSize) >= MAX_PRINT_DIMENSION) {
                    inSampleSize *= 2
                }
            }

            // 3. Decode with RGB_565 (High quality for photos/docs, but half the RAM of ARGB)
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inJustDecodeBounds = false
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            }
        } catch (e: Exception) {
            Log.e("FileActions", "Error loading bitmap", e)
            null
        }
    }

    suspend fun saveBitmapsAsPdf(
        uris: List<Uri>,
        fileName: String,
        context: Context,
        targetDirectory: File? = null, // <--- NEW PARAMETER (Defaults to root if null)
        onComplete: (Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val PAGE_MARGIN = 27f

        // ... (Keep existing Paint and Footer setup) ...
        val footerPaint = Paint().apply {
            color = android.graphics.Color.DKGRAY
            textSize = 10f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }
        val footerText = "Scanned with SaaiCom's DocuScanner App"

        try {
            uris.forEachIndexed { index, uri ->
                // ... (Keep existing bitmap loading and drawing logic exacty as is) ...
                val bitmap = loadOptimizedBitmap(context, uri) ?: return@forEachIndexed
                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                canvas.drawColor(Color.WHITE)

                val contentWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
                val contentHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN
                val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val contentRatio = contentWidth / contentHeight
                val destRect = RectF()

                if (bitmapRatio > contentRatio) {
                    val scaledHeight = contentWidth / bitmapRatio
                    val top = PAGE_MARGIN + (contentHeight - scaledHeight) / 2
                    destRect.set(PAGE_MARGIN, top, PAGE_MARGIN + contentWidth, top + scaledHeight)
                } else {
                    val scaledWidth = contentHeight * bitmapRatio
                    val left = PAGE_MARGIN + (contentWidth - scaledWidth) / 2
                    destRect.set(left, PAGE_MARGIN, left + scaledWidth, PAGE_MARGIN + contentHeight)
                }

                canvas.drawBitmap(bitmap, null, destRect, null)
                canvas.drawText(footerText, PAGE_MARGIN, PAGE_HEIGHT - 20f, footerPaint)
                pdfDocument.finishPage(page)
                bitmap.recycle()
            }

            // --- UPDATED SAVE LOGIC ---
            val safeName = if (fileName.endsWith(".pdf", true)) fileName else "$fileName.pdf"

            // Use the provided targetDirectory, or fallback to the default root
            val dir = targetDirectory ?: context.getExternalFilesDir(null)

            if (dir != null && !dir.exists()) dir.mkdirs()
            val file = File(dir, safeName)

            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }
            // ---------------------------

            Log.d("FileActions", "PDF Saved. Size: ${file.length() / 1024} KB")
            withContext(Dispatchers.Main) { onComplete(true) }

        } catch (e: Exception) {
            Log.e("FileActions", "Error saving PDF", e)
            withContext(Dispatchers.Main) { onComplete(false) }
        } finally {
            pdfDocument.close()
        }
    }

    // --- UTILITIES (Delete, Rename, Share, Print) ---
    // (These remain unchanged)

    suspend fun deleteLocalFiles(context: Context, filesToDelete: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        try {
            var allDeleted = true
            val dir = context.getExternalFilesDir(null) ?: return@withContext false
            filesToDelete.forEach { item ->
                val file = File(dir, item.name ?: return@forEach)
                if (file.exists()) {
                    if (!file.delete()) allDeleted = false
                }
            }
            allDeleted
        } catch (e: Exception) { false }
    }

    suspend fun renameLocalFile(context: Context, uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return@withContext false
            val files = dir.listFiles() ?: emptyArray()
            var targetFile: File? = null
            for (f in files) {
                if (getFileProviderUri(context, f) == uri) {
                    targetFile = f
                    break
                }
            }
            if (targetFile != null && targetFile.exists()) {
                val finalName = if (newName.endsWith(".pdf", true)) newName else "$newName.pdf"
                val newFile = File(dir, finalName)
                return@withContext targetFile.renameTo(newFile)
            }
            false
        } catch (e: Exception) { false }
    }

    fun sharePdfFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF Files"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share files", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareSinglePdfFile(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    fun emailPdfFile(context: Context, subject: String, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Send PDF via Email"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    fun printPdfFile(context: Context, jobName: String, uri: Uri) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                Toast.makeText(context, "Print Service Unavailable", Toast.LENGTH_SHORT).show()
                return
            }
            val printAdapter = object : PrintDocumentAdapter() {
                override fun onLayout(
                    oldAttributes: PrintAttributes?,
                    newAttributes: PrintAttributes,
                    cancellationSignal: CancellationSignal?,
                    callback: LayoutResultCallback?,
                    extras: Bundle?
                ) {
                    if (cancellationSignal?.isCanceled == true) {
                        callback?.onLayoutCancelled()
                        return
                    }
                    val info = PrintDocumentInfo.Builder("$jobName.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build()
                    callback?.onLayoutFinished(info, true)
                }

                override fun onWrite(
                    pages: Array<out PageRange>?,
                    destination: ParcelFileDescriptor?,
                    cancellationSignal: CancellationSignal?,
                    callback: WriteResultCallback?
                ) {
                    var inputStream: InputStream? = null
                    var outputStream: OutputStream? = null
                    try {
                        inputStream = context.contentResolver.openInputStream(uri)
                        outputStream = FileOutputStream(destination?.fileDescriptor)

                        if (inputStream == null || outputStream == null) {
                            callback?.onWriteFailed("Could not open streams.")
                            return
                        }
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } >= 0) {
                            if (cancellationSignal?.isCanceled == true) {
                                callback?.onWriteCancelled()
                                return
                            }
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    } catch (e: IOException) {
                        callback?.onWriteFailed(e.message)
                    } finally {
                        try {
                            inputStream?.close()
                            outputStream?.close()
                        } catch (e: IOException) { }
                    }
                }
            }
            printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            Toast.makeText(context, "Could not start print job", Toast.LENGTH_SHORT).show()
        }
    }

    // Add inside object FileActions in FileActions.kt

    fun createFolder(parentDir: java.io.File, folderName: String): Boolean {
        try {
            val newFolder = java.io.File(parentDir, folderName)
            if (!newFolder.exists()) {
                return newFolder.mkdirs()
            }
            return false // Folder already exists
        } catch (e: Exception) {
            return false
        }
    }
}