package saaicom.tcb.docuscanner.screens.home

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.SignatureRepository
import saaicom.tcb.docuscanner.UserData
import saaicom.tcb.docuscanner.UserDataStore
import saaicom.tcb.docuscanner.models.FileItem // *** UPDATED IMPORT ***
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import saaicom.tcb.docuscanner.ui.components.LocalFileRow // *** UPDATED IMPORT ***
import saaicom.tcb.docuscanner.ui.components.RenameFileDialog
import saaicom.tcb.docuscanner.ui.components.SelectFileDialog
import saaicom.tcb.docuscanner.utils.FileUtils.loadLocalFiles // *** UPDATED IMPORT ***
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun HomeScreen(
    navController: NavController,
    hasStoragePermission: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userDataStore = remember { UserDataStore(context) }
    val userData by userDataStore.userDataFlow.collectAsState(
        initial = UserData(firstName = "", lastName = "", termsAccepted = false)
    )

    var localFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    var showAddSigDialog by remember { mutableStateOf(false) }
    var showSelectFileDialog by remember { mutableStateOf(false) }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var filesToDelete by remember { mutableStateOf<List<FileItem>>(emptyList()) }

    var fileToRename by remember { mutableStateOf<FileItem?>(null) }

    // Function to reload files
    val reloadFiles = {
        scope.launch {
            if (hasStoragePermission) {
                localFiles = loadLocalFiles(context)
            }
        }
    }

    LaunchedEffect(hasStoragePermission) {
        reloadFiles()
    }

    val filteredFiles = remember(searchQuery, localFiles) {
        if (searchQuery.isBlank()) {
            emptyList()
        } else {
            localFiles.filter {
                it.name?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    // --- DIALOGS ---

    if (showAddSigDialog) {
        AlertDialog(
            onDismissRequest = { showAddSigDialog = false },
            title = { Text("No Signature Found") },
            text = { Text("You must add a signature first before you can sign a document.") },
            confirmButton = {
                TextButton(onClick = {
                    showAddSigDialog = false
                    navController.navigate(Routes.SIGN)
                }) {
                    Text("Add Signature")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddSigDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showSelectFileDialog) {
        SelectFileDialog(
            localFiles = localFiles,
            onDismiss = { showSelectFileDialog = false },
            onFileSelected = { fileUri ->
                showSelectFileDialog = false
                val encodedUri = URLEncoder.encode(fileUri.toString(), StandardCharsets.UTF_8.toString())
                navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
            }
        )
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
                        reloadFiles()
                    } else {
                        Toast.makeText(context, "Error deleting file(s)", Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = false
                    filesToDelete = emptyList()
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
                        reloadFiles()
                    } else {
                        Toast.makeText(context, "Error renaming file", Toast.LENGTH_SHORT).show()
                    }
                    fileToRename = null
                }
            }
        )
    }

    if (!userData.termsAccepted) {
        TermsAndConditionsScreen(
            onAccept = {
                scope.launch {
                    userDataStore.saveUserData(userData.copy(termsAccepted = true))
                }
            }
        )
    } else {
        MainDashboard(
            navController = navController,
            localFiles = localFiles,
            filteredFiles = filteredFiles,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onSignClick = {
                val signatures = SignatureRepository.getSavedSignatures(context)
                if (signatures.isEmpty()) {
                    showAddSigDialog = true
                } else {
                    showSelectFileDialog = true
                }
            },
            onDeleteFile = { fileItem ->
                filesToDelete = listOf(fileItem)
                showDeleteDialog = true
            },
            onRenameFile = { fileItem ->
                fileToRename = fileItem
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboard(
    navController: NavController,
    localFiles: List<FileItem>,
    filteredFiles: List<FileItem>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSignClick: () -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    onRenameFile: (FileItem) -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopActionButtons(navController = navController, onSignClick = onSignClick)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search local files") },
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

        Spacer(modifier = Modifier.height(16.dp))
        Divider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                if (filteredFiles.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No files found matching '$searchQuery'.")
                        }
                    }
                } else {
                    items(filteredFiles, key = { it.uri }) { file ->
                        LocalFileRow(
                            fileItem = file,
                            navController = navController,
                            selectionMode = false,
                            isSelected = false,
                            onToggleSelection = {},
                            onLongPress = {},
                            onDelete = { onDeleteFile(file) },
                            onSign = {
                                val encodedUri = URLEncoder.encode(file.uri.toString(), StandardCharsets.UTF_8.toString())
                                navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
                            },
                            onRename = { onRenameFile(file) }
                        )
                        Divider(modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Recently Scanned Files", style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { navController.navigate(Routes.FILES) }) {
                            Text("View All")
                        }
                    }
                }

                if (localFiles.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No local files found. Tap 'Scan' to start!")
                        }
                    }
                } else {
                    items(localFiles.take(14), key = { it.uri }) { file ->
                        LocalFileRow(
                            fileItem = file,
                            navController = navController,
                            selectionMode = false,
                            isSelected = false,
                            onToggleSelection = {},
                            onLongPress = {},
                            onDelete = { onDeleteFile(file) },
                            onSign = {
                                val encodedUri = URLEncoder.encode(file.uri.toString(), StandardCharsets.UTF_8.toString())
                                navController.navigate("${Routes.PDF_SIGN.split('/')[0]}/$encodedUri")
                            },
                            onRename = { onRenameFile(file) }
                        )
                        Divider(modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TopActionButtons(
    navController: NavController,
    onSignClick: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.Top
    ) {
        ActionButton(
            text = "Scan",
            icon = Icons.Default.CameraAlt,
            onClick = { navController.navigate(Routes.CAMERA) }
        )
        ActionButton(
            text = "Sign",
            icon = Icons.Default.Draw,
            onClick = onSignClick
        )
        ActionButton(
            text = "Import",
            icon = Icons.Default.Description,
            onClick = {
                Toast.makeText(context, "Import Files - Coming Soon!", Toast.LENGTH_SHORT).show()
            }
        )
        ActionButton(
            text = "Share",
            icon = Icons.Default.Share,
            onClick = {
                Toast.makeText(context, "Share - Coming Soon!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick)
            .padding(4.dp)
            .width(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
    }
}


@Composable
fun TermsAndConditionsScreen(onAccept: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Terms and Conditions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Welcome to DocuScanner! This is a free-to-use application provided by Saaicom. " +
                    "By using this app, you agree that Saaicom holds no liability for any data loss or issues that may arise from its use. " +
                    "This app displays advertisements to support its development and maintenance. " +
                    "Please indicate your acceptance to continue.",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onAccept)
        ) {
            Checkbox(
                checked = false,
                onCheckedChange = { onAccept() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("I accept the Terms and Conditions")
        }
    }
}

// *** REMOVED ALL DUPLICATE HELPER FUNCTIONS ***