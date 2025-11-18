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
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import saaicom.tcb.docuscanner.ui.components.LocalFileRow
import saaicom.tcb.docuscanner.utils.FileUtils.loadLocalFiles
import kotlinx.coroutines.delay
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.ui.components.RenameFileDialog
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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


    // Function to reload files
    val loadFiles: (String?) -> Unit = { nameFilter ->
        scope.launch {
            isLoading = true
            files = loadLocalFiles(context, nameFilter)
            isLoading = false
        }
    }

    LaunchedEffect(searchQuery) {
        isLoading = true
        if (searchQuery.isNotBlank()) {
            delay(300)
        }
        files = loadLocalFiles(context, searchQuery.ifBlank { null })
        isLoading = false
    }

    LaunchedEffect(searchQuery) {
        selectionMode = false
        selectedFiles = emptySet()
    }

    val allFilesSelected = remember(files, selectedFiles) {
        files.isNotEmpty() && selectedFiles.size == files.size
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = filesToDelete.size,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    val success = FileActions.deleteLocalFiles(context, filesToDelete)
                    if (success) {
                        Toast.makeText(context, "File(s) deleted", Toast.LENGTH_SHORT).show()
                        loadFiles(searchQuery.ifBlank { null })
                    } else {
                        Toast.makeText(context, "Error deleting file(s)", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = false
                    filesToDelete = emptyList()
                    selectionMode = false
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
                        loadFiles(searchQuery.ifBlank { null })
                    } else {
                        Toast.makeText(context, "Error renaming file", Toast.LENGTH_SHORT).show()
                    }
                    fileToRename = null
                }
            }
        )
    }

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
            onCloseSelection = { selectionMode = false },
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
                Toast.makeText(context, "Share ${selectedFiles.size} items (Not Implemented)", Toast.LENGTH_SHORT).show()
                selectionMode = false
            }
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
                        }
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

// *** REMOVED: All duplicate helper functions ***