package saaicom.tcb.docuscanner

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap // *** ADDED ***
import android.graphics.Paint // *** ADDED ***
import android.graphics.RectF // *** ADDED ***
import android.graphics.Typeface // *** ADDED ***
import android.graphics.pdf.PdfDocument // *** ADDED ***
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.models.FileItem
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Utility object containing actions for handling local PDF files.
 */
object FileActions {

    /**
     * Sends a PDF file to the Android Print Framework using a custom adapter.
     */
// ... (printPdfFile function is unchanged) ...
    fun printPdfFile(context: Context, jobName: String, uri: Uri) {
        try {
            val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            if (printManager == null) {
                Toast.makeText(context, "Could not access Print Service", Toast.LENGTH_SHORT).show()
                Log.e("FileActions", "PrintManager is null")
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
                    val info = PrintDocumentInfo.Builder("${jobName}.pdf")
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
                            Log.e("PrintAdapter", "Could not open input or output stream.")
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
                        Log.e("PrintAdapter", "Error writing PDF to print output", e)
                        callback?.onWriteFailed(e.message)
                    } finally {
                        try {
                            inputStream?.close()
                            outputStream?.close()
                        } catch (e: IOException) {
                            Log.e("PrintAdapter", "Error closing streams", e)
                        }
                    }
                }
            }

            printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())

        } catch (e: Exception) {
            Toast.makeText(context, "Could not start print job", Toast.LENGTH_SHORT).show()
            Log.e("FileActions", "Error printing file URI: $uri", e)
        }
    }


    /**
     * Shares a PDF file using an ACTION_SEND Intent (common for Email).
     */
// ... (emailPdfFile function is unchanged) ...
    fun emailPdfFile(context: Context, subject: String, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Important for content URIs
            }
            val chooser = Intent.createChooser(intent, "Send PDF using...")
            context.startActivity(chooser)

        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to handle sending PDF", Toast.LENGTH_SHORT).show()
            Log.e("FileActions", "No app found for ACTION_SEND with PDF type", e)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
            Log.e("FileActions", "Error emailing file URI: $uri", e)
        }
    }

    /**
     * Renames a file in the MediaStore.
     */
// ... (renameLocalFile function is unchanged) ...
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

            if (rowsUpdated > 0) {
                Log.d("FileActions", "File renamed successfully to: $finalName")
                return@withContext true
            } else {
                Log.w("FileActions", "File rename failed (rowsUpdated = 0) for: $fileUri")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("FileActions", "Error renaming file: $fileUri", e)
            return@withContext false
        }
    }


    /**
     * Deletes a list of local PDF files from MediaStore and their cached thumbnails.
     */
// ... (deleteLocalFiles function is unchanged) ...
    suspend fun deleteLocalFiles(context: Context, filesToDelete: List<FileItem>): Boolean = withContext(Dispatchers.IO) {
        var allSuccess = true
        for (fileItem in filesToDelete) {
            try {
                val rowsDeleted = context.contentResolver.delete(fileItem.uri, null, null)

                if (rowsDeleted > 0) {
                    Log.d("FileActions", "Successfully deleted PDF from MediaStore: ${fileItem.uri}")
                    ThumbnailRepository.deleteThumbnail(context, fileItem.uri)
                } else {
                    Log.w("FileActions", "File not found or not deleted from MediaStore: ${fileItem.uri}")
                    ThumbnailRepository.deleteThumbnail(context, fileItem.uri)
                    allSuccess = false
                }
            } catch (e: Exception) {
                Log.e("FileActions", "Error deleting file: ${fileItem.uri}", e)
                allSuccess = false
                ThumbnailRepository.deleteThumbnail(context, fileItem.uri)
            }
        }
        return@withContext allSuccess
    }

    /**
     * *** MOVED & UPDATED: Saves a list of bitmaps (pages) to a single PDF file. ***
     */
    suspend fun saveBitmapsAsPdf(
        bitmaps: List<Bitmap>,
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
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            isAntiAlias = true
        }
        val footerText = "Created by using Saaicom's DocuScanner App"
        val footerMargin = 20f

        try {
            bitmaps.forEachIndexed { index, bitmap ->
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
                val xPos = (PAGE_WIDTH / 2).toFloat()
                val yPos = PAGE_HEIGHT - footerMargin
                canvas.drawText(footerText, xPos, yPos, footerPaint)
                pdfDocument.finishPage(page)
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DocuScanner")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    pdfDocument.writeTo(outputStream)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                withContext(Dispatchers.Main) { onComplete(true) }
            } else {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        } catch (e: IOException) {
            Log.e("SavePdf", "Error writing PDF", e)
            withContext(Dispatchers.Main) { onComplete(false) }
        } finally {
            pdfDocument.close()
        }
    }
}