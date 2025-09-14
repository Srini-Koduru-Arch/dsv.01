package saaicom.tcb.docuscanner.screens.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import saaicom.tcb.docuscanner.Scanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedDocumentEditScreen(
    navController: NavController,
    imageUri: Uri,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scanner = remember { Scanner() }

    // State to hold the original and processed bitmaps
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load the bitmap from the URI and process it
    LaunchedEffect(imageUri) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val bitmap = imageUri.toBitmap(context)
            originalBitmap = bitmap

            // Perform edge detection and perspective transform
            val scannedData = scanner.detectEdges(bitmap)
            val finalData = scanner.applyPerspectiveTransform(scannedData)

            processedBitmap = finalData.scanned ?: bitmap // Fallback to original if scan fails
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Document") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { /* TODO: Save functionality */ }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch {
                            isLoading = true
                            withContext(Dispatchers.IO) {
                                originalBitmap?.let {
                                    val scannedData = scanner.detectEdges(it)
                                    val finalData = scanner.applyPerspectiveTransform(scannedData)
                                    processedBitmap = finalData.scanned ?: it
                                }
                            }
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-scan")
                    }
                    // TODO: Add more editing actions like rotate, filter, etc.
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                processedBitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Scanned Document",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentScale = ContentScale.Fit
                    )
                } ?: run {
                    Text("Failed to load image.")
                }
            }
        }
    }
}

// Extension function to convert Uri to Bitmap
private fun Uri.toBitmap(context: Context): Bitmap {
    return if (Build.VERSION.SDK_INT < 28) {
        MediaStore.Images.Media.getBitmap(context.contentResolver, this)
    } else {
        val source = ImageDecoder.createSource(context.contentResolver, this)
        ImageDecoder.decodeBitmap(source)
    }.copy(Bitmap.Config.ARGB_8888, true)
}
