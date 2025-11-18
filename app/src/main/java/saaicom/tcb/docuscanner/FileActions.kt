package saaicom.tcb.docuscanner

import android.content.ActivityNotFoundException
import android.content.ContentValues
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
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.models.FileItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object FileActions {

    /**
     * Sends a PDF file to the Android Print Framework.
     */
    fun printPdfFile(context: Context, jobName: String, uri: Uri) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                Toast.makeText(context, "Could not access Print Service", Toast.LENGTH_SHORT).show()
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

    fun emailPdfFile(context: Context, subject: String, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Send PDF using...")
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun renameLocalFile(context: Context, fileUri: Uri, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val finalName = if (!newName.endsWith(".pdf")) "$newName.pdf" else newName
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
            }
            val rowsUpdated = context.contentResolver.update(fileUri, contentValues, null, null)
            return@withContext rowsUpdated > 0
        } catch (e: Exception) {
            Log.e("FileActions", "Error renaming file", e)
            return@withContext false
        }
    }

    suspend fun deleteLocalFiles(context: Context, filesToDelete: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        var allSuccess = true
        for (fileItem in filesToDelete) {
            try {
                val rowsDeleted = context.contentResolver.delete(fileItem.uri, null, null)
                if (rowsDeleted == 0) allSuccess = false
                // Note: We are not calling ThumbnailRepository here to avoid circular dependencies if not needed,
                // but you can uncomment if you have access to it.
            } catch (e: Exception) {
                allSuccess = false
            }
        }
        return@withContext allSuccess
    }

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
                bitmap.recycle()
            }

            val resolver = context.contentResolver
            var uri: Uri? = null
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            }

            var outputStream: OutputStream? = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DocuScanner")
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1)
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) outputStream = resolver.openOutputStream(uri)
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val appDir = File(downloadsDir, "DocuScanner")
                    if (!appDir.exists()) appDir.mkdirs()
                    val file = File(appDir, fileName)
                    contentValues.put(MediaStore.MediaColumns.DATA, file.absolutePath)
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    outputStream = FileOutputStream(file)
                }

                if (outputStream == null) throw IOException("Failed to create output stream.")

                outputStream.use { pdfDocument.writeTo(it) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                withContext(Dispatchers.Main) { onComplete(true) }

            } catch (e: Throwable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
                    try { resolver.delete(uri, null, null) } catch (x: Exception) {}
                }
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.Main) { onComplete(false) }
        } finally {
            pdfDocument.close()
        }
    }
}