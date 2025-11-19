package saaicom.tcb.docuscanner.screens.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import saaicom.tcb.docuscanner.DriveRepository
import com.google.api.services.drive.model.File as DriveFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFilesScreen(navController: NavController) {
    val context = LocalContext.current
    val driveService by DriveRepository.driveService.collectAsState()

    // --- Navigation State ---
    // 1. Start explicitly at "root" (My Drive)
    var currentFolderId by remember { mutableStateOf("root") }
    var currentFolderName by remember { mutableStateOf("My Drive") }
    var navigationStack by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    // --- Data State ---
    var allFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var filteredFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }

    // 2. Use a Map to store selected files (ID -> File Object)
    var selectedFiles by remember { mutableStateOf<Map<String, DriveFile>>(emptyMap()) }

    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    // 3. Load Files whenever currentFolderId changes
    LaunchedEffect(driveService, currentFolderId) {
        val service = driveService
        if (service != null) {
            isLoading = true
            // Load current folder (root or subfolder)
            allFiles = DriveRepository.loadDriveFiles(service, currentFolderId)
            filteredFiles = allFiles
            isLoading = false
        }
    }

    // Filter Logic
    LaunchedEffect(searchQuery, allFiles) {
        filteredFiles = if (searchQuery.isBlank()) {
            allFiles
        } else {
            allFiles.filter { it.name?.contains(searchQuery, ignoreCase = true) == true }
        }
    }

    // --- Actions ---

    fun toggleSelection(file: DriveFile) {
        val id = file.id ?: return
        selectedFiles = if (selectedFiles.containsKey(id)) {
            selectedFiles - id
        } else {
            selectedFiles + (id to file)
        }
    }

    fun toggleSelectAll() {
        val visibleFiles = filteredFiles.filter { it.mimeType != "application/vnd.google-apps.folder" }
        val visibleIds = visibleFiles.mapNotNull { it.id }
        val allVisibleSelected = visibleIds.all { selectedFiles.containsKey(it) }

        selectedFiles = if (allVisibleSelected) {
            selectedFiles.filterKeys { !visibleIds.contains(it) }
        } else {
            selectedFiles + visibleFiles.associateBy { it.id }
        }
    }

    fun navigateToFolder(folder: DriveFile) {
        if (folder.id != null) {
            navigationStack = navigationStack + (currentFolderId to currentFolderName)
            currentFolderId = folder.id
            currentFolderName = folder.name ?: "Folder"
            searchQuery = ""
        }
    }

    fun navigateBack() {
        if (navigationStack.isNotEmpty()) {
            val previous = navigationStack.last()
            navigationStack = navigationStack.dropLast(1)
            currentFolderId = previous.first
            currentFolderName = previous.second
            searchQuery = ""
        } else {
            navController.popBackStack()
        }
    }

    BackHandler(enabled = true) {
        navigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFolderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { toggleSelectAll() }) {
                        Text("Select All")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedFiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val filesToImport = selectedFiles.values.toList()
                        DriveRepository.startImport(filesToImport)
                        Toast.makeText(context, "Import started for ${filesToImport.size} files", Toast.LENGTH_SHORT).show()
                        navController.popBackStack()
                    },
                    icon = { Icon(Icons.Default.Download, null) },
                    text = { Text("Import (${selectedFiles.size})") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search in $currentFolderName...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                    }
                },
                singleLine = true
            )

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (filteredFiles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filteredFiles) { file ->
                        val isFolder = file.mimeType == "application/vnd.google-apps.folder"
                        val isSelected = selectedFiles.containsKey(file.id)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = LocalIndication.current
                                ) {
                                    if (isFolder) {
                                        navigateToFolder(file)
                                    } else {
                                        toggleSelection(file)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isFolder) {
                                Icon(Icons.Default.Folder, "Folder", tint = MaterialTheme.colorScheme.secondary)
                            } else {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = "Select",
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = file.name ?: "Unnamed",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}