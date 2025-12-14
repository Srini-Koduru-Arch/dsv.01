package saaicom.tcb.docuscanner.screens.files

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- STATE ---
    val rootDir = remember { context.getExternalFilesDir(null) ?: File(context.filesDir, ".") }
    var currentDir by remember { mutableStateOf(rootDir) }
    var isGridView by remember { mutableStateOf(true) }

    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog states
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<FileItem?>(null) }
    var folderNameInput by remember { mutableStateOf("") }

    // --- LOGIC ---
    fun loadFiles() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val resultList = if (searchQuery.isBlank()) {
                // MODE 1: NAVIGATION (Current Directory Only)
                val rawList = currentDir.listFiles()?.toList() ?: emptyList()
                rawList.filter { it.isDirectory || it.name.endsWith(".pdf", ignoreCase = true) }
            } else {
                // MODE 2: RECURSIVE SEARCH (Deep Search)
                rootDir.walk()
                    .filter { file ->
                        file.name.contains(searchQuery, ignoreCase = true) &&
                                (file.isDirectory || file.name.endsWith(".pdf", ignoreCase = true))
                    }
                    .toList()
            }

            // SORTING: Folders first, then by Date (Newest first)
            val sortedList = resultList.sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenByDescending { it.lastModified() }
            ).map { file ->
                val uri = if (file.isDirectory) Uri.fromFile(file) else FileProvider.getUriForFile(
                    context, "${context.packageName}.provider", file
                )
                FileItem(
                    name = file.name,
                    uri = uri,
                    sizeInBytes = file.length(),
                    isDirectory = file.isDirectory,
                    lastModified = file.lastModified()
                )
            }

            withContext(Dispatchers.Main) {
                files = sortedList
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentDir, searchQuery) { loadFiles() }

    BackHandler(enabled = currentDir != rootDir && searchQuery.isBlank()) {
        currentDir = currentDir.parentFile ?: rootDir
    }

    // --- UI ---
    Scaffold(
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = { Text("Search all files...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        // *** ADDED: Clear Button (X) ***
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                        )
                    )
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle View"
                        )
                    }
                }

                if (currentDir != rootDir && searchQuery.isBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentDir.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (searchQuery.isBlank()) {
                FloatingActionButton(onClick = { showCreateFolderDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "New Folder")
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (files.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.FolderOff, null, Modifier.size(64.dp), tint = Color.LightGray)
                    Text(
                        if(searchQuery.isNotBlank()) "No files found" else "Empty Folder",
                        color = Color.Gray
                    )
                }
            } else {
                if (isGridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3), // Industry Standard 3-Column
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(files) { item ->
                            FileGridItem(
                                item = item,
                                onClick = {
                                    if (item.isDirectory) {
                                        if (searchQuery.isNotBlank()) {
                                            searchQuery = ""
                                            currentDir = File(rootDir.path + "/" + item.name)
                                        } else {
                                            currentDir = File(currentDir, item.name)
                                        }
                                    } else {
                                        openFile(navController, item.uri)
                                    }
                                },
                                onDelete = { itemToDelete = item; showDeleteDialog = true }
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { item ->
                            FileListItem(
                                item = item,
                                onClick = {
                                    if (item.isDirectory) {
                                        if (searchQuery.isNotBlank()) {
                                            searchQuery = ""
                                        } else {
                                            currentDir = File(currentDir, item.name)
                                        }
                                    } else {
                                        openFile(navController, item.uri)
                                    }
                                },
                                onDelete = { itemToDelete = item; showDeleteDialog = true }
                            )
                            Divider(modifier = Modifier.padding(vertical = 4.dp), color = Color.LightGray.copy(alpha = 0.2f))
                        }
                    }
                }
            }
        }
    }

    // --- DIALOGS ---
    if (showCreateFolderDialog) {
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(value = folderNameInput, onValueChange = { folderNameInput = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (folderNameInput.isNotBlank()) {
                        FileActions.createFolder(currentDir, folderNameInput)
                        loadFiles()
                        folderNameInput = ""
                        showCreateFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDialog && itemToDelete != null) {
        DeleteConfirmationDialog(
            count = 1,
            onDismiss = { showDeleteDialog = false; itemToDelete = null },
            onConfirm = {
                scope.launch {
                    val file = if(searchQuery.isBlank()) File(currentDir, itemToDelete!!.name) else File(rootDir, itemToDelete!!.name)

                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                    loadFiles()
                    showDeleteDialog = false
                    itemToDelete = null
                }
            }
        )
    }
}

// --- HELPER FUNCTIONS & COMPONENTS ---

fun openFile(navController: NavController, uri: Uri) {
    val encoded = URLEncoder.encode(uri.toString(), StandardCharsets.UTF_8.toString())
    navController.navigate("${Routes.PDF_VIEW.split('/')[0]}/$encoded")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileGridItem(item: FileItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f) // Slightly taller aspect ratio for 3-column grid
            .combinedClickable(onClick = onClick, onLongClick = onDelete),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(if (item.isDirectory) MaterialTheme.colorScheme.secondaryContainer else Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (item.isDirectory) {
                    Icon(Icons.Default.Folder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    PdfThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())
                }
            }
            // Footer with Name
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp)
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListItem(item: FileItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDelete)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            modifier = Modifier.size(50.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = if (item.isDirectory) MaterialTheme.colorScheme.secondaryContainer else Color.LightGray)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (item.isDirectory) {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    PdfThumbnail(uri = item.uri, modifier = Modifier.fillMaxSize())
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (item.isDirectory) "Folder" else saaicom.tcb.docuscanner.utils.FileUtils.formatFileSize(item.sizeInBytes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.MoreVert, contentDescription = "Options")
        }
    }
}

@Composable
fun PdfThumbnail(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmapState = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = uri) {
        value = saaicom.tcb.docuscanner.ThumbnailRepository.getThumbnail(context, uri)
    }

    if (bitmapState.value != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmapState.value!!.asImageBitmap(),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = null,
                tint = Color.Gray
            )
        }
    }
}