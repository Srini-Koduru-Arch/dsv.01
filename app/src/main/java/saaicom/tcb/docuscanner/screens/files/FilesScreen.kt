package saaicom.tcb.docuscanner.screens.files

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast // *** ADDED: Import ***
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions // *** ADDED: Import ***
import androidx.compose.foundation.text.KeyboardOptions // *** ADDED: Import ***
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // *** ADDED: Import all filled icons ***
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // *** ADDED: Import ***
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager // *** ADDED: Import ***
import androidx.compose.ui.text.input.ImeAction // *** ADDED: Import ***
import androidx.compose.ui.text.style.TextOverflow // *** ADDED: Import ***
import androidx.compose.ui.unit.dp
// *** REMOVED: NavController import - not needed in this version ***
import kotlinx.coroutines.CoroutineScope // *** ADDED: Import ***
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job // *** ADDED: Import ***
import kotlinx.coroutines.delay // *** ADDED: Import ***
import kotlinx.coroutines.launch // *** ADDED: Import ***
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.FileActions // *** ADDED: Import ***
// *** REMOVED: URLEncoder/Charset imports - not needed ***
import java.text.SimpleDateFormat // *** ADDED: Import ***
import java.util.Date // *** ADDED: Import ***
import java.util.Locale // *** ADDED: Import ***


// Data class to hold file information
data class FileItem(val name: String?, val sizeInBytes: Long, val uri: Uri)

@OptIn(ExperimentalMaterial3Api::class) // *** ADDED: Opt-in for TextField ***
@Composable
// *** UPDATED: Removed NavController parameter ***
fun FilesScreen(/*navController: NavController*/) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // Use the composable's scope
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") } // *** ADDED: State for search query ***
    var searchJob by remember { mutableStateOf<Job?>(null) } // *** ADDED: For debouncing search ***
    val focusManager = LocalFocusManager.current // *** ADDED: To dismiss keyboard ***

    // Function to load/reload files with search filter
    val loadFiles: (String?) -> Unit = { nameFilter ->
        searchJob?.cancel() // Cancel previous search job
        searchJob = scope.launch {
            if (!nameFilter.isNullOrBlank()) {
                delay(300)
            }
            isLoading = true
            files = loadLocalFiles(context, nameFilter)
            isLoading = false
        }
    }


    // Load initial files or when search query changes
    LaunchedEffect(searchQuery) {
        loadFiles(searchQuery)
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))
            // *** UPDATED: Changed font size to titleLarge ***
            Text("Local Scanned Files", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(7.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                label = { Text("Search files by name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
            )


            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (files.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (searchQuery.isBlank()) "No local files found in Downloads/DocuScanner."
                        else "No results found for '$searchQuery'."
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        FileItemRow(
                            fileItem = file,
                            // *** REVERTED: Call original openPdfFile ***
                            onOpen = { openPdfFile(context, file.uri) },
                            onPrint = { FileActions.printPdfFile(context, file.name ?: "Document", file.uri) },
                            onEmail = { FileActions.emailPdfFile(context, file.name ?: "Document", file.uri) }
                        )
                        Divider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }
    }
}

// Composable for displaying a single file item row with actions
@Composable
fun FileItemRow(
    fileItem: FileItem,
    onOpen: () -> Unit,
    onPrint: () -> Unit,
    onEmail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp, horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PictureAsPdf,
            contentDescription = "PDF File",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileItem.name ?: "Unnamed Item",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(fileItem.sizeInBytes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        IconButton(onClick = onPrint, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Print, contentDescription = "Print")
        }
        IconButton(onClick = onEmail, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Email, contentDescription = "Email")
        }
    }
}


// --- Helper Functions ---

private suspend fun loadLocalFiles(context: Context, nameFilter: String? = null): List<FileItem> = withContext(Dispatchers.IO) {
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

    Log.d("FilesScreen", "Querying MediaStore with selection: $selection, args: ${selectionArgs.joinToString()}")

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
    Log.d("FilesScreen", "Found ${files.size} local PDF files matching filter.")
    return@withContext files
}

// Function to open a PDF file using an Intent (External Viewer)
private fun openPdfFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) { // Catch generic exception for broader coverage
        Toast.makeText(context, "Could not open file. No PDF viewer app found?", Toast.LENGTH_SHORT).show()
        Log.e("FilesScreen", "Error opening file URI: $uri", e)
    }
}

// Helper function to format file size
private fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes < 1024) return "$sizeInBytes B"
    val kb = sizeInBytes / 1024
    if (kb < 1024) return "$kb KB"
    val mb = kb / 1024
    return "$mb MB"
}

