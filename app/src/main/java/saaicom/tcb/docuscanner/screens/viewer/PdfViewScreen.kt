package saaicom.tcb.docuscanner.screens.viewer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.navigation.NavController
import androidx.pdf.viewer.fragment.PdfViewerFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewScreen(
    navController: NavController,
    pdfUri: Uri
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileName by remember { mutableStateOf("Document") }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    // Load file name
    LaunchedEffect(pdfUri) {
        fileName = getFileName(context, pdfUri)
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = 1,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
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
                        isDeleting = false
                    }
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
                windowInsets = WindowInsets(0.dp)
            )
        },
        bottomBar = {
            BottomAppBar(
                windowInsets = WindowInsets(0.dp)
            ) {
                // Spacer pushes the icons to the right
                Spacer(modifier = Modifier.weight(1f))

                // --- 1. RESTORED: Your Custom Sign Button ---
                IconButton(onClick = {
                    val encodedUri = URLEncoder.encode(pdfUri.toString(), StandardCharsets.UTF_8.toString())
                    // Fix: Use the route logic you had before
                    navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
                }) {
                    Icon(Icons.Default.Draw, contentDescription = "Sign")
                }

                // Share Button
                IconButton(onClick = {
                    FileActions.sharePdfFiles(context, listOf(pdfUri))
                }) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }

                // Print Button
                IconButton(onClick = {
                    FileActions.printPdfFile(context, fileName, pdfUri)
                }) {
                    Icon(Icons.Default.Print, contentDescription = "Print")
                }

                // Delete Button
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
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            if (!isDeleting) {
                AndroidView(
                    factory = { ctx ->
                        FragmentContainerView(ctx).apply {
                            id = View.generateViewId()
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { containerView ->
                        val activity = context as? FragmentActivity
                        if (activity == null) {
                            Log.e("PdfViewScreen", "Activity is not FragmentActivity!")
                            return@AndroidView
                        }

                        val fragmentTag = "pdf_fragment_${containerView.id}"
                        val fm = activity.supportFragmentManager

                        // --- 2. ADDED: Logic to hide the built-in Edit FAB ---
                        fm.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                                if (f is PdfViewerFragment) {
                                    hideFloatingActionButton(v)
                                }
                            }
                            override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                                if (f is PdfViewerFragment) {
                                    hideFloatingActionButton(f.view)
                                }
                            }
                        }, false)

                        // Find or Create Fragment
                        var fragment = fm.findFragmentByTag(fragmentTag) as? PdfViewerFragment

                        if (fragment == null) {
                            fragment = PdfViewerFragment().apply {
                                documentUri = pdfUri
                            }

                            fm.beginTransaction()
                                .setReorderingAllowed(true)
                                .replace(containerView.id, fragment!!, fragmentTag)
                                .commit()

                        } else {
                            if (fragment!!.documentUri != pdfUri) {
                                fragment!!.documentUri = pdfUri
                            }
                        }
                    }
                )
            }

            if (isDeleting) {
                CircularProgressIndicator()
            }
        }
    }
}

/**
 * Recursive function to find and PERSISTENTLY hide the FloatingActionButton.
 * We use a layout listener to ensure it stays hidden even if the library tries to show it.
 */
private fun hideFloatingActionButton(view: View?) {
    if (view == null) return

    // If we found the FAB
    if (view is FloatingActionButton) {
        // 1. Hide it immediately
        view.visibility = View.GONE

        // 2. Set its size to 0 to prevent it from taking up touch space if it flickers
        view.layoutParams.width = 0
        view.layoutParams.height = 0

        // 3. Attach a listener to fight back if the library tries to make it VISIBLE again
        view.viewTreeObserver.addOnGlobalLayoutListener {
            if (view.visibility == View.VISIBLE) {
                view.visibility = View.GONE
            }
        }
        return
    }

    // If it's a container, search its children
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            hideFloatingActionButton(view.getChildAt(i))
        }
    }
}

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