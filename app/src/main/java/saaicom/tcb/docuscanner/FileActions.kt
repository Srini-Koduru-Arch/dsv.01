package saaicom.tcb.docuscanner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo // *** FIX HERE: Corrected typo ***
import android.print.PrintManager
import android.util.Log
import android.widget.Toast
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
}