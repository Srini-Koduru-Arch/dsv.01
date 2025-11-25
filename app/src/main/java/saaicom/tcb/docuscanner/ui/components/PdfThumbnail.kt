package saaicom.tcb.docuscanner.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.ThumbnailRepository

/**
 * A reusable composable that loads and displays a PDF thumbnail
 * using the disk-caching ThumbnailRepository.
 */
@Composable
fun PdfThumbnail(uri: Uri, modifier: Modifier = Modifier) {
    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(uri) {
        isLoading = true
        thumbnail = null // Reset for new URI

        scope.launch {
            // *** UPDATED: getThumbnail now uses the LruCache internally ***
            val loadedBitmap = ThumbnailRepository.getThumbnail(context, uri)
            thumbnail = loadedBitmap?.asImageBitmap()
            isLoading = false
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!,
                contentDescription = "PDF Thumbnail",
                contentScale = ContentScale.Crop // Use Crop to fill the bounds
            )
        } else {
            // Fallback icon if rendering fails
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "PDF File",
                modifier = Modifier.size(24.dp),
                tint = Color.Gray
            )
        }
    }
}