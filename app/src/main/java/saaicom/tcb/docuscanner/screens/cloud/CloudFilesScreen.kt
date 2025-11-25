package saaicom.tcb.docuscanner.screens.cloud

import android.app.Activity
import android.content.Context // *** ADDED: Import ***
import android.content.Intent // *** ADDED: Import ***
import android.net.Uri // *** ADDED: Import ***
import android.util.Log
import android.widget.Toast // *** ADDED: Import ***
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable // *** ADDED: Import ***
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager // *** ADDED: Import ***
import androidx.compose.ui.text.input.ImeAction // *** ADDED: Import ***
import androidx.compose.ui.text.style.TextOverflow // *** ADDED: Import ***
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider // *** ADDED: Import ***
import androidx.navigation.NavController
import com.google.android.gms.auth.api.signin.GoogleSignIn // *** FIX HERE: Import ***
import com.google.android.gms.common.api.ApiException // *** FIX HERE: Import ***
import com.google.api.services.drive.Drive // *** ADDED: Import ***
import com.google.api.services.drive.model.File as DriveFile // Alias Drive File
import kotlinx.coroutines.Dispatchers // *** ADDED: Import ***
import kotlinx.coroutines.Job // *** ADDED: Import ***
import kotlinx.coroutines.delay // *** ADDED: Import ***
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext // *** ADDED: Import ***
import saaicom.tcb.docuscanner.DriveRepository // Import DriveRepository
import saaicom.tcb.docuscanner.FileActions // *** ADDED: Import ***
import saaicom.tcb.docuscanner.Routes
import java.io.File // *** ADDED: Import ***
import java.io.FileOutputStream // *** ADDED: Import ***


// Define actions for downloaded files
private enum class FileAction { PRINT, EMAIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudFilesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val driveService by DriveRepository.driveService.collectAsState()
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) } // General loading state
    var isDownloading by remember { mutableStateOf(false) } // Specific state for download action
    // *** REMOVED: showAddOptions and showCreateFolderDialog states ***
    var currentFolderId by remember { mutableStateOf<String?>(null) } // To track current folder
    var searchQuery by remember { mutableStateOf("") } // *** ADDED: State for search query ***
    var searchJob by remember { mutableStateOf<Job?>(null) } // *** ADDED: For debouncing search ***
    val focusManager = LocalFocusManager.current // *** ADDED: To dismiss keyboard ***


    // Function to load files for the current folder, now includes search
    val loadFiles: (String?, String?) -> Unit = { folderId, nameFilter ->
        searchJob?.cancel() // Cancel previous search job if active
        searchJob = scope.launch {
            if (nameFilter != null) {
                delay(300) // Debounce: wait 300ms after typing stops
            }
            isLoading = true
            driveService?.let { service ->
                val idToLoad = folderId ?: DriveRepository.findOrCreateDocuScannerFolder(service) ?: "root"
                // Only update currentFolderId when navigating, not just searching
                if (nameFilter == null) {
                    currentFolderId = idToLoad
                }
                files = DriveRepository.loadDriveFiles(service, idToLoad, nameFilter)
            }
            isLoading = false
        }
    }

    // Load initial files or when navigating into a folder
    LaunchedEffect(driveService, currentFolderId) {
        if (driveService != null) {
            // Load files for the current folder ID (or root if null) without search filter
            loadFiles(currentFolderId, null)
        }
    }

    // Trigger search when searchQuery changes (debounced in loadFiles)
    LaunchedEffect(searchQuery) {
        if (driveService != null) {
            loadFiles(currentFolderId, searchQuery.ifBlank { null })
        }
    }


    // Launcher for Google Sign-In
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("CloudScreen", "Sign-in activity finished with result code: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    Log.d("CloudScreen", "Google sign in successful for: ${account.email}")
                    scope.launch {
                        DriveRepository.initialize(context, account)
                        // Files will be loaded by LaunchedEffect(driveService)
                    }
                } else {
                    Log.w("CloudScreen", "Google sign in returned null account.")
                }
            } catch (e: ApiException) {
                Log.e("CloudScreen", "Google sign in failed with status code: ${e.statusCode}", e)
                Toast.makeText(context, "Sign-in failed. Please check connection or configuration.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("CloudScreen", "Error processing sign in result", e)
                Toast.makeText(context, "An unexpected error occurred during sign-in.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Log.w("CloudScreen", "Sign-in flow was cancelled or failed. Result Code: ${result.resultCode}")
            Toast.makeText(context, "Sign-in cancelled or failed.", Toast.LENGTH_SHORT).show()
        }
    }

    // Download file and perform action
    val downloadAndPerformAction: (DriveFile, FileAction) -> Unit = { file, action ->
        scope.launch {
            if (file.id == null || file.name == null) {
                Toast.makeText(context, "Cannot process file without ID or name", Toast.LENGTH_SHORT).show()
                return@launch
            }
            isDownloading = true
            driveService?.let { service ->
                val tempFile = File(context.cacheDir, file.name)
                val success = DriveRepository.downloadDriveFile(service, file.id, FileOutputStream(tempFile))
                if (success) {
                    val fileUri = FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", tempFile
                    )
                    context.grantUriPermission(context.packageName, fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Log.d("CloudScreen", "Temporary file created at: $fileUri")
                    when (action) {
                        FileAction.PRINT -> FileActions.printPdfFile(context, file.name, fileUri)
                        FileAction.EMAIL -> FileActions.emailPdfFile(context, file.name, fileUri)
                    }
                } else {
                    Toast.makeText(context, "Failed to download file: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Toast.makeText(context, "Not signed in to Google Drive", Toast.LENGTH_SHORT).show()
            }
            isDownloading = false
        }
    }

    // *** REMOVED: Create Folder Dialog call ***

    Scaffold(
        // *** REMOVED: floatingActionButton ***
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 14.dp), // Only horizontal padding for Column
                // *** REMOVED: Centering alignment for the whole column ***
                // horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (driveService == null) {
                    // Center the button if not signed in
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Button(onClick = {
                            val signInClient = DriveRepository.getGoogleSignInClient(context)
                            googleSignInLauncher.launch(signInClient.signInIntent)
                        }) {
                            Text("Sign in with Google")
                        }
                    }
                } else {
                    // *** ADDED: Title ***
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Scanned files saved to Google Drive",
                        style = MaterialTheme.typography.titleLarge, // Use a smaller headline style
                        // *** ADDED: Left align the title ***
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(7.dp))

                    // *** ADDED: Search Bar ***
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
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }) // Dismiss keyboard on search
                    )

                    // *** REMOVED: "Current Folder: ..." Text ***
                    // Text("Current Folder: ...", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 7.dp))

                    if (isLoading && !isDownloading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { // Center loading
                            CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
                        }
                    } else if (files.isEmpty() && !isDownloading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { // Center empty text
                            Text(
                                if (searchQuery.isBlank()) "No files or folders found." else "No results found for '$searchQuery'.",
                                modifier = Modifier.padding(top = 32.dp)
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(files) { file ->
                                DriveFileItemRow(
                                    driveFile = file,
                                    onOpen = {
                                        if (file.mimeType == "application/vnd.google-apps.folder") {
                                            searchQuery = "" // Clear search when navigating
                                            currentFolderId = file.id // Update current folder ID directly
                                            // LaunchedEffect will trigger loadFiles
                                        } else {
                                            Toast.makeText(context, "Open/Download: ${file.name}", Toast.LENGTH_SHORT).show()
                                            // downloadAndPerformAction(file, /* some OPEN action */)
                                        }
                                    },
                                    onPrint = { downloadAndPerformAction(file, FileAction.PRINT) },
                                    onEmail = { downloadAndPerformAction(file, FileAction.EMAIL) }
                                )
                                Divider(modifier = Modifier.padding(vertical = 2.dp)) // Add padding to divider
                            }
                        }
                    }
                }
            }
            // Overlay Progress Indicator during download
            if (isDownloading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}


// Displays a row for a Google Drive file or folder with action icons.
@Composable
fun DriveFileItemRow(
    driveFile: DriveFile,
    onOpen: () -> Unit,
    onPrint: () -> Unit,
    onEmail: () -> Unit
) {
    val isFolder = driveFile.mimeType == "application/vnd.google-apps.folder"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onOpen)
            // *** UPDATED: Reduced vertical padding ***
            .padding(vertical = 4.dp, horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.PictureAsPdf,
            contentDescription = if (isFolder) "Folder" else "PDF File",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = driveFile.name ?: "Unnamed Item",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!isFolder) {
            // *** UPDATED: Slightly smaller touch target ***
            IconButton(onClick = onPrint, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Print, contentDescription = "Print")
            }
            // *** UPDATED: Slightly smaller touch target ***
            IconButton(onClick = onEmail, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Email, contentDescription = "Email")
            }
        }
    }
}


// *** REMOVED: CreateFolderDialog composable ***

