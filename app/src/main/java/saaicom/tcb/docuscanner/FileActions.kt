package saaicom.tcb.docuscanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
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

object FileActions {

    // *** UPDATED: Matches the authority in AndroidManifest.xml ***
    // We used "${applicationId}.provider" in the manifest, so it resolves to this:
    private const val FILE_PROVIDER_AUTHORITY = "saaicom.tcb.docuscanner.provider"

    /**
     * Helper to get a secure Content URI for sharing a local file.
     */
    private fun getFileProviderUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
    }

    /**
     * SAVES the list of page URIs as a single PDF file to App Internal Storage.
     * This ensures the file appears immediately in FilesScreen.
     */
    suspend fun saveBitmapsAsPdf(
        uris: List<Uri>,
        fileName: String,
        context: Context,
        onComplete: (Boolean) -> Unit
    ) = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()
        val PAGE_WIDTH = 595
        val PAGE_HEIGHT = 842
        val PAGE_MARGIN = 36f

        val footerPaint = Paint().apply {
            color = android.graphics.Color.DKGRAY
            textSize = 10f
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }
        val footerText = "Created by using Saaicom's DocuScanner App"
        val footerMargin = 20f

        try {
            uris.forEachIndexed { index, uri ->
                val bitmap = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                            decoder.isMutableRequired = true
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    }
                } catch (e: Exception) {
                    return@forEachIndexed
                }

                val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas
                val contentWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
                val contentHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN
                val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val contentAspectRatio = contentWidth / contentHeight
                val destRect = RectF()

                if (bitmapAspectRatio > contentAspectRatio) {
                    val scaledHeight = contentWidth / bitmapAspectRatio
                    val top = PAGE_MARGIN + (contentHeight - scaledHeight) / 2
                    destRect.set(PAGE_MARGIN, top, PAGE_MARGIN + contentWidth, top + scaledHeight)
                } else {
                    val scaledWidth = contentHeight * bitmapAspectRatio
                    val left = PAGE_MARGIN + (contentWidth - scaledWidth) / 2
                    destRect.set(left, PAGE_MARGIN, left + scaledWidth, PAGE_MARGIN + contentHeight)
                }

                canvas.drawBitmap(bitmap, null, destRect, null)
                canvas.drawText(footerText, PAGE_MARGIN, PAGE_HEIGHT - footerMargin, footerPaint)
                pdfDocument.finishPage(page)
                // Don't recycle immediately if you plan to reuse, but for PDF gen it's usually safe
                // bitmap.recycle()
            }

            // --- SAVE TO INTERNAL STORAGE ---
            val safeName = if (fileName.endsWith(".pdf", true)) fileName else "$fileName.pdf"

            // Get the internal directory (same as FilesScreen)
            val dir = context.getExternalFilesDir(null)
            if (dir != null && !dir.exists()) dir.mkdirs()

            val file = File(dir, safeName)

            FileOutputStream(file).use { out ->
                pdfDocument.writeTo(out)
            }

            Log.d("FileActions", "PDF Saved to: ${file.absolutePath}")
            withContext(Dispatchers.Main) { onComplete(true) }

        } catch (e: Exception) {
            Log.e("FileActions", "Error saving PDF", e)
            withContext(Dispatchers.Main) { onComplete(false) }
        } finally {
            pdfDocument.close()
        }
    }

    // --- FILE MANAGEMENT (Delete/Rename) ---

    suspend fun deleteLocalFiles(context: Context, filesToDelete: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        try {
            var allDeleted = true
            val dir = context.getExternalFilesDir(null) ?: return@withContext false

            filesToDelete.forEach { item ->
                // Construct file object from name
                val file = File(dir, item.name ?: return@forEach)
                if (file.exists()) {
                    if (!file.delete()) allDeleted = false
                }
            }
            allDeleted
        } catch (e: Exception) {
            Log.e("FileActions", "Delete error", e)
            false
        }
    }

    suspend fun renameLocalFile(context: Context, uri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir(null) ?: return@withContext false

            // We need to find the specific file that matches the URI.
            // Since we generated the URI in FilesScreen, we iterate to match it.
            var targetFile: File? = null
            val files = dir.listFiles() ?: emptyArray()

            for (f in files) {
                val fUri = getFileProviderUri(context, f)
                if (fUri == uri) {
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
        } catch (e: Exception) {
            Log.e("FileActions", "Rename error", e)
            false
        }
    }

    // --- SHARING ---

    fun sharePdfFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return

        Log.d("FileActions", "Sharing ${uris.size} files")

        // Since we are using FileProvider URIs internally now, we can pass them directly.
        // But for safety, we ensure they are ArrayList
        try {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share PDF Files")
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share files", Toast.LENGTH_SHORT).show()
            Log.e("FileActions", "Error sharing multiple files", e)
        }
    }

    fun shareSinglePdfFile(context: Context, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share PDF")
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    // --- EMAIL ---
    fun emailPdfFile(context: Context, subject: String, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Send PDF via Email")
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    // --- PRINTING ---
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
                        Log.e("PrintAdapter", "Error writing PDF", e)
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
            Log.e("FileActions", "Error printing", e)
        }
    }
}