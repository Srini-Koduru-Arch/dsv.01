package saaicom.tcb.docuscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Manages loading and disk-caching PDF thumbnails.
 */
object ThumbnailRepository {

    private const val THUMBNAIL_DIR = "thumbnails"
    private const val THUMBNAIL_WIDTH = 150 // Target width for thumbnails
    private const val THUMBNAIL_HEIGHT = 200 // Target height for thumbnails

    // *** ADDED: In-memory cache for fast access ***
    // Store 50 thumbnails in memory
    private val thumbnailCache = LruCache<Uri, Bitmap>(50)


    // Gets the private cache directory for thumbnails
    private fun getThumbnailCacheDir(context: Context): File {
        val dir = File(context.cacheDir, THUMBNAIL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    // Creates a unique, safe filename for a thumbnail based on the PDF's URI
    private fun getThumbnailCacheFile(context: Context, pdfUri: Uri): File {
        // Use a hash of the URI string as a unique and safe filename
        val fileName = "${pdfUri.toString().hashCode()}.png"
        return File(getThumbnailCacheDir(context), fileName)
    }

    /**
     * Tries to load a thumbnail from the disk cache.
     * Returns the Bitmap if found, or null if not.
     */
    private suspend fun loadThumbnailFromCache(cacheFile: File): Bitmap? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) {
            return@withContext null
        }
        return@withContext try {
            BitmapFactory.decodeFile(cacheFile.absolutePath)
        } catch (e: Exception) {
            Log.e("ThumbnailRepository", "Failed to decode cached bitmap", e)
            null
        }
    }

    /**
     * Generates a new thumbnail from a PDF, saves it to the disk cache, and returns it.
     * This is the "slow path".
     */
    private suspend fun generateAndCacheThumbnail(context: Context, pdfUri: Uri, cacheFile: File): Bitmap? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(pdfUri, "r")?.use { pfd ->
                val renderer = PdfRenderer(pfd)
                val page = renderer.openPage(0)

                // Create a bitmap for the thumbnail
                val bitmap = Bitmap.createBitmap(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, Bitmap.Config.ARGB_8888)

                // Render the page onto the bitmap
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Clean up
                page.close()
                renderer.close()

                // Save the bitmap to the cache file
                try {
                    FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    Log.d("ThumbnailRepository", "Saved new thumbnail to cache: ${cacheFile.name}")
                } catch (e: IOException) {
                    Log.e("ThumbnailRepository", "Failed to save thumbnail to cache", e)
                }

                return@withContext bitmap
            }
        } catch (e: Exception) {
            Log.e("ThumbnailRepository", "Failed to render PDF thumbnail for URI: $pdfUri", e)
        }
        return@withContext null
    }

    /**
     * The main function to get a thumbnail.
     * It checks the in-memory cache, then disk cache, and only generates a new one if needed.
     */
    suspend fun getThumbnail(context: Context, pdfUri: Uri): Bitmap? {
        // 1. Check in-memory cache (Fastest)
        val memBitmap = thumbnailCache.get(pdfUri)
        if (memBitmap != null) {
            Log.d("ThumbnailRepository", "Cache HIT (Memory) for $pdfUri")
            return memBitmap
        }

        val cacheFile = getThumbnailCacheFile(context, pdfUri)

        // 2. Try to load from disk cache (Fast)
        val diskBitmap = loadThumbnailFromCache(cacheFile)
        if (diskBitmap != null) {
            Log.d("ThumbnailRepository", "Cache HIT (Disk) for ${cacheFile.name}")
            thumbnailCache.put(pdfUri, diskBitmap) // Add to memory cache
            return diskBitmap
        }

        // 3. Not in cache, generate, save, and return (Slow)
        Log.d("ThumbnailRepository", "Cache MISS for ${cacheFile.name}")
        val generatedBitmap = generateAndCacheThumbnail(context, pdfUri, cacheFile)
        if (generatedBitmap != null) {
            thumbnailCache.put(pdfUri, generatedBitmap) // Add to memory cache
        }
        return generatedBitmap
    }

    /**
     * *** NEW FUNCTION ***
     * Deletes a thumbnail from both the in-memory and disk cache.
     */
    suspend fun deleteThumbnail(context: Context, pdfUri: Uri) = withContext(Dispatchers.IO) {
        // 1. Remove from in-memory cache
        thumbnailCache.remove(pdfUri)

        // 2. Remove from disk cache
        try {
            val cacheFile = getThumbnailCacheFile(context, pdfUri)
            if (cacheFile.exists()) {
                cacheFile.delete()
                Log.d("ThumbnailRepository", "Deleted cached thumbnail: ${cacheFile.name}")
            } else {
                // *** FIX HERE: Added else block to satisfy compiler ***
            }
        } catch (e: Exception) {
            Log.e("ThumbnailRepository", "Error deleting cached thumbnail for $pdfUri", e)
        }
    }
}