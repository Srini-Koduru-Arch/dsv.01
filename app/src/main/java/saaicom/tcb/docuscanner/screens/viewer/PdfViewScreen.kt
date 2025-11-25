package saaicom.tcb.docuscanner.screens.viewer

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.scroll.DefaultScrollHandle
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A screen to view a PDF with continuous scrolling, zoom, and pan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewScreen(
    navController: NavController,
    pdfUri: Uri
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var fileName by remember { mutableStateOf("Document") }
    var pageCount by remember { mutableStateOf(0) }
    var currentPage by remember { mutableStateOf(0) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) } // *** ADDED: State to manage deletion ***

    // Load file name
    LaunchedEffect(pdfUri) {
        fileName = getFileName(context, pdfUri)
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = 1,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                // *** UPDATED: Set deleting state and close dialog first ***
                isDeleting = true
                showDeleteDialog = false

                scope.launch {
                    val fileToDelete = FileItem(name = fileName, sizeInBytes = 0L, uri = pdfUri)
                    val success = FileActions.deleteLocalFiles(context, listOf(fileToDelete))

                    if (success) {
                        Toast.makeText(context, "File deleted", Toast.LENGTH_SHORT).show()
                        navController.navigate(Routes.FILES) {
                            popUpTo(Routes.HOME)
                            launchSingleTop = true
                        }
                    } else {
                        Toast.makeText(context, "Error deleting file", Toast.LENGTH_SHORT).show()
                        isDeleting = false // Reset state on failure
                    }
                    // No need to set showDeleteDialog = false here, already done
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                //modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
                // FIX 1: Remove Top Padding (Status Bar inset)
                windowInsets = WindowInsets(0.dp)
            )
        },
        bottomBar = {
            BottomAppBar(
                //modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                windowInsets = WindowInsets(0.dp)
            ) {
                if (pageCount > 0) {
                    Text(
                        "Page ${currentPage + 1} of $pageCount",
                        modifier = Modifier.padding(horizontal = 7.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = {
                    val encodedUri = URLEncoder.encode(pdfUri.toString(), StandardCharsets.UTF_8.toString())
                    navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
                }) {
                    Icon(Icons.Default.Draw, contentDescription = "Sign")
                }
                IconButton(onClick = {
                    FileActions.sharePdfFiles(context, listOf(pdfUri))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
                IconButton(onClick = {
                    FileActions.printPdfFile(context, fileName, pdfUri)
                }) {
                    Icon(Icons.Default.Print, contentDescription = "Print")
                }
                IconButton(onClick = {
                    showDeleteDialog = true
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Gray),
            contentAlignment = Alignment.Center
        ) {
            // *** UPDATED: Only show PDFView if not deleting ***
            if (!isDeleting) {
                AndroidView(
                    factory = { ctx ->
                        PDFView(ctx, null).apply {
                            this.setBackgroundColor(AndroidColor.GRAY)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { pdfView ->
                        // Add a final check
                        if (isDeleting) return@AndroidView

                        pdfView.fromUri(pdfUri)
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .defaultPage(0)
                            .enableAnnotationRendering(true)
                            .scrollHandle(DefaultScrollHandle(context))
                            .onLoad { nbPages ->
                                isLoading = false
                                pageCount = nbPages
                                Log.d("PdfViewScreen", "PDF loaded. Page count: $nbPages")
                            }
                            .onPageChange { page, _ ->
                                currentPage = page
                            }
                            .onError { throwable ->
                                isLoading = false
                                // Don't navigate away if we are already deleting
                                if (!isDeleting) {
                                    Log.e("PdfViewScreen", "Error loading PDF", throwable)
                                    Toast.makeText(context, "Error opening PDF", Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            }
                            .load()
                    }
                )
            }

            // *** UPDATED: Show loading spinner if loading OR deleting ***
            if (isLoading || isDeleting) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Helper to get file name from URI
 */
private fun getFileName(context: Context, uri: Uri): String {
    var name: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PdfViewScreen", "Error getting display name", e)
        }
    }
    return name ?: uri.lastPathSegment ?: "Document"
}