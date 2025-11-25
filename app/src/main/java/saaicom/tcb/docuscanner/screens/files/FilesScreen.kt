package saaicom.tcb.docuscanner.screens.files

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import saaicom.tcb.docuscanner.ui.components.LocalFileRow
import saaicom.tcb.docuscanner.ui.components.RenameFileDialog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.File // *** Added explicit import for File ***

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<Uri>>(emptySet()) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var filesToDelete by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    var fileToRename by remember { mutableStateOf<FileItem?>(null) }

    // --- Loading Logic using App Internal Storage ---
    val loadFiles: (String?) -> Unit = { nameFilter ->
        scope.launch {
            isLoading = true

            // 1. Target the Internal App Storage (Same as Import/Backup)
            val dir = context.getExternalFilesDir(null)

            // 2. Filter for PDFs
            val rawFiles = dir?.listFiles()?.filter {
                it.isFile && it.name.endsWith(".pdf", ignoreCase = true)
            } ?: emptyList()

            // 3. Apply Name Search Filter
            val filteredFiles = if (nameFilter.isNullOrBlank()) {
                rawFiles
            } else {
                rawFiles.filter { it.name.contains(nameFilter, ignoreCase = true) }
            }

            // 4. Map to FileItem
            // *** FIX: Sort files by date FIRST, then map to FileItem ***
            files = filteredFiles
                .sortedByDescending { it.lastModified() }
                .map { file ->
                    // Generate secure URI for internal file
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file
                    )

                    // Construct FileItem using only the properties that exist
                    FileItem(
                        name = file.name,
                        uri = uri,
                        // Removed 'lastModified' parameter as it doesn't exist in your model
                        sizeInBytes = file.length()
                    )
                }

            isLoading = false
        }
    }

    // Load files on initial launch and when search query changes
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            delay(300) // Debounce
        }
        loadFiles(searchQuery.ifBlank { null })
    }

    // Reset selection when searching
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            selectionMode = false
            selectedFiles = emptySet()
        }
    }

    val allFilesSelected = remember(files, selectedFiles) {
        files.isNotEmpty() && selectedFiles.size == files.size
    }

    // --- Dialogs ---

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = filesToDelete.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    val success = FileActions.deleteLocalFiles(context, filesToDelete)
                    if (success) {
                        Toast.makeText(context, "File(s) deleted", Toast.LENGTH_SHORT).show()
                        loadFiles(searchQuery.ifBlank { null }) // Refresh list
                    } else {
                        Toast.makeText(context, "Error deleting file(s)", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = false
                    filesToDelete = emptyList()
                    selectionMode = false
                    selectedFiles = emptySet()
                }
            }
        )
    }

    fileToRename?.let { file ->
        RenameFileDialog(
            currentName = file.name ?: "file.pdf",
            onDismiss = { fileToRename = null },
            onRename = { newName ->
                scope.launch {
                    val success = FileActions.renameLocalFile(context, file.uri, newName)
                    if (success) {
                        Toast.makeText(context, "File renamed", Toast.LENGTH_SHORT).show()
                        loadFiles(searchQuery.ifBlank { null }) // Refresh list
                    } else {
                        Toast.makeText(context, "Error renaming file", Toast.LENGTH_SHORT).show()
                    }
                    fileToRename = null
                }
            }
        )
    }

    // --- UI Layout ---

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        SearchOrSelectionBar(
            selectionMode = selectionMode,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            selectedCount = selectedFiles.size,
            allFilesSelected = allFilesSelected,
            onCloseSelection = {
                selectionMode = false
                selectedFiles = emptySet()
            },
            onSelectAll = {
                selectedFiles = if (allFilesSelected) {
                    emptySet()
                } else {
                    files.map { it.uri }.toSet()
                }
            },
            onDelete = {
                filesToDelete = files.filter { selectedFiles.contains(it.uri) }
                if (filesToDelete.isNotEmpty()) {
                    showDeleteDialog = true
                }
            },
            onShare = {
                val urisToShare = files
                    .filter { selectedFiles.contains(it.uri) }
                    .map { it.uri }

                if (urisToShare.isNotEmpty()) {
                    FileActions.sharePdfFiles(context, urisToShare)
                } else {
                    Toast.makeText(context, "No files selected.", Toast.LENGTH_SHORT).show()
                }
                selectionMode = false
                selectedFiles = emptySet()
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isBlank()) "No files found."
                    else "No results found for '$searchQuery'."
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(files, key = { it.uri }) { file ->
                    val isSelected = selectedFiles.contains(file.uri)

                    LocalFileRow(
                        fileItem = file,
                        navController = navController,
                        isSelected = isSelected,
                        selectionMode = selectionMode,
                        onToggleSelection = {
                            selectedFiles = if (isSelected) {
                                selectedFiles - file.uri
                            } else {
                                selectedFiles + file.uri
                            }
                            if (selectionMode && selectedFiles.isEmpty()) {
                                selectionMode = false
                            }
                        },
                        onLongPress = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedFiles = setOf(file.uri)
                            }
                        },
                        onDelete = {
                            filesToDelete = listOf(file)
                            showDeleteDialog = true
                        },
                        onSign = {
                            val encodedUri = URLEncoder.encode(file.uri.toString(), StandardCharsets.UTF_8.toString())
                            navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
                        },
                        onRename = {
                            fileToRename = file
                        },
                        onShare = { FileActions.shareSinglePdfFile(context, file.uri) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOrSelectionBar(
    selectionMode: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCount: Int,
    allFilesSelected: Boolean,
    onCloseSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            // Selection Mode UI
            IconButton(onClick = onCloseSelection) {
                Icon(Icons.Default.Close, contentDescription = "Close selection")
            }
            RadioButton(
                selected = allFilesSelected,
                onClick = onSelectAll
            )
            Text(
                text = "$selectedCount selected",
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share selected")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete selected")
            }

        } else {
            // Standard Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search files by name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}