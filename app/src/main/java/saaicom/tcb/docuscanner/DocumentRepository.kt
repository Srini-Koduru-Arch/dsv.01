package saaicom.tcb.docuscanner

import android.graphics.Bitmap

/**
 * A simple singleton object to hold the list of scanned bitmaps
 * during a scanning session. This persists across composable lifecycles.
 */
object DocumentRepository {
    private var pageBitmaps = mutableListOf<Bitmap>()

    /**
     * Adds a new page to the end of the document list.
     */
    fun addPage(bitmap: Bitmap) {
        pageBitmaps.add(bitmap)
    }

    /**
     * Replaces an existing page at a specific index.
     * This is used after a manual re-crop.
     */
    fun replacePage(index: Int, bitmap: Bitmap) {
        if (index >= 0 && index < pageBitmaps.size) {
            pageBitmaps[index] = bitmap
        }
    }

    /**
     * Gets a specific page.
     */
    fun getPage(index: Int): Bitmap? {
        return pageBitmaps.getOrNull(index)
    }

    /**
     * Gets the total number of pages.
     */
    fun getPageCount(): Int {
        return pageBitmaps.size
    }

    /**
     * Gets the complete list of all scanned pages.
     */
    fun getAllPages(): List<Bitmap> {
        return pageBitmaps.toList() // Return an immutable copy
    }

    /**
     * Clears the repository for a new scanning session.
     */
    fun clear() {
        pageBitmaps.clear()
    }
}
