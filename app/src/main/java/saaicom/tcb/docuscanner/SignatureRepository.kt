package saaicom.tcb.docuscanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages saving, loading, and deleting signature files from the app's private storage.
 */
object SignatureRepository {

    private const val SIGNATURE_DIR = "signatures"

    private fun getSignatureStorageDir(context: Context): File {
        val dir = File(context.filesDir, SIGNATURE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns a list of all saved signature files, sorted by newest first.
     */
    fun getSavedSignatures(context: Context): List<File> {
        val dir = getSignatureStorageDir(context)
        return dir.listFiles { _, name -> name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Saves a signature bitmap to a new .png file with a specific name.
     */
    // *** UPDATED: Function signature now accepts a fileName ***
    suspend fun saveSignature(context: Context, bitmap: Bitmap, fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = getSignatureStorageDir(context)
            // *** UPDATED: Use provided name, ensure it ends with .png ***
            val finalName = if (fileName.endsWith(".png")) fileName else "$fileName.png"
            val file = File(dir, finalName)

            // Optional: Check if file already exists and handle (e.g., add number)
            // For now, we'll just overwrite, but a unique name is better
            // val file = File(dir, "${fileName}_${System.currentTimeMillis()}.png")

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d("SignatureRepository", "Signature saved to: ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e("SignatureRepository", "Failed to save signature", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save signature", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    /**
     * Deletes a specific signature file.
     */
    suspend fun deleteSignature(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) {
                file.delete()
            }
            true
        } catch (e: Exception) {
            Log.e("SignatureRepository", "Failed to delete signature", e)
            false
        }
    }
}