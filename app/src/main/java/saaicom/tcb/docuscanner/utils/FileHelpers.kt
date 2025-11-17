package saaicom.tcb.docuscanner.utils

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.models.FileItem

/**
 * Loads all local PDF files from the app's designated Downloads folder.
 * Can be filtered by name.
 */
suspend fun loadLocalFiles(context: Context, nameFilter: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
    val files = mutableListOf<FileItem>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    val selectionClauses = mutableListOf(
        "${MediaStore.Files.FileColumns.MIME_TYPE} = ?",
        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    )
    val selectionArgsList = mutableListOf(
        "application/pdf",
        "%Download/DocuScanner%"
    )

    if (!nameFilter.isNullOrBlank()) {
        selectionClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?")
        selectionArgsList.add("%$nameFilter%")
    }

    val selection = selectionClauses.joinToString(separator = " AND ")
    val selectionArgs = selectionArgsList.toTypedArray()
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

    try {
        context.contentResolver.query(
            collection,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE
            ),
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val contentUri: Uri = ContentUris.withAppendedId(collection, id)
                files.add(FileItem(name, size, contentUri))
            }
        }
        Log.d("FileHelpers", "Found ${files.size} local PDF files matching filter.")
    } catch (e: Exception) {
        Log.e("FileHelpers", "Error querying MediaStore", e)
    }
    return@withContext files
}

/**
 * Opens a PDF file using an external viewer app.
 */
fun openPdfFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file. No PDF viewer app found?", Toast.LENGTH_SHORT).show()
        Log.e("FileHelpers", "Error opening file URI: $uri", e)
    }
}

/**
 * Formats a file size in bytes into a human-readable string (B, KB, MB).
 */
fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes < 1024) return "$sizeInBytes B"
    val kb = sizeInBytes / 1024
    if (kb < 1024) return "$kb KB"
    val mb = kb / 1024
    return "$mb MB"
}