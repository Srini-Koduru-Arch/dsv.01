package saaicom.tcb.docuscanner

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * A simple singleton object to hold the list of scanned bitmaps
 * during a scanning session. This persists across composable lifecycles.
 */
object DocumentRepository {
    //private var pageBitmaps = mutableListOf<Bitmap>() // Changed this to Uri to optimize RAM usage
    private var pageUris = mutableListOf<Uri>()

    /**
     * Adds a new page to the end of the document list.
     */
    fun addPage(uri: Uri) {
        pageUris.add(uri)
    }

    /**
     * Replaces an existing page at a specific index.
     * This is used after a manual re-crop.
     */
    fun replacePage(index: Int, uri: Uri) {
        if (index >= 0 && index < pageUris.size) {
            pageUris[index] = uri
        }
    }

    /**
     * Gets a specific page.
     */
    fun getPage(index: Int): Uri? {
        return pageUris.getOrNull(index)
    }

    /**
     * Gets the total number of pages.
     */
    fun getPageCount(): Int {
        return pageUris.size
    }

    /**
     * Gets the complete list of all scanned pages.
     */
    fun getAllPages(): List<Uri> { // Returns List<Uri>
        return pageUris.toList()
    }

    /**
     * Clears the repository for a new scanning session.
     */
    fun clear() {
        // Optional: Clean up the actual files from disk to save space
        pageUris.forEach { uri ->
            try {
                val file = File(uri.path!!)
                if (file.exists()) file.delete()
            } catch (e: Exception) { }
        }
        pageUris.clear()
    }


}
