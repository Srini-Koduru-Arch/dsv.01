package saaicom.tcb.docuscanner.screens.settings

import android.app.Activity // Add for Sign-in result
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult // Add for Sign-in
import androidx.activity.result.contract.ActivityResultContracts // Add for Sign-in
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
import androidx.compose.material.icons.filled.Cloud
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
import com.google.android.gms.auth.api.signin.GoogleSignIn // Add for Sign-in
import com.google.android.gms.common.api.ApiException // Add for Sign-in
import kotlinx.coroutines.launch
import saaicom.tcb.docuscanner.DriveRepository
import com.google.api.services.drive.model.File as DriveFile
import java.io.File
import android.os.Environment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportFilesScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope() // Scope for launching coroutines
    val driveService by DriveRepository.driveService.collectAsState()

    // --- Navigation State ---
    // Start explicitly at "root" (My Drive) when signed in
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var currentFolderName by remember { mutableStateOf("My Drive") } // Default to My Drive for top level
    var navigationStack by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    // --- Data State ---
    var allFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var filteredFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Map<String, DriveFile>>(emptyMap()) }

    var searchQuery by remember { mutableStateOf("") }
    // Initial loading state only active if we are actively trying to load files after sign-in
    var isLoading by remember { mutableStateOf(driveService != null) }

    // Launcher for Google Sign-In
    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    scope.launch {
                        DriveRepository.initialize(context, account)
                        // Initialization will update driveService, triggering LaunchedEffect(driveService)
                    }
                }
            } catch (e: ApiException) {
                Toast.makeText(context, "Sign-in failed: ${e.statusCode}", Toast.LENGTH_LONG).show()
                isLoading = false
            }
        } else {
            Toast.makeText(context, "Sign-in cancelled.", Toast.LENGTH_SHORT).show()
            isLoading = false
        }
    }


    // 1. Initial Load: Try to set currentFolderId to "root" or the dedicated folder.
    LaunchedEffect(driveService) {
        val service = driveService
        if (service != null && currentFolderId == null) {
            isLoading = true
            // Setting currentFolderId to "root" to start browsing My Drive, as "Import" might be general.
            currentFolderId = "root"
            currentFolderName = "My Drive"
        } else if (service == null) {
            isLoading = false
        }
    }

    // 2. Load Files when currentFolderId is set
    LaunchedEffect(driveService, currentFolderId) {
        val service = driveService
        val folderId = currentFolderId
        if (service != null && folderId != null) {
            isLoading = true
            // Use loadDriveFiles which filters for folders and PDFs
            allFiles = DriveRepository.loadDriveFiles(service, folderId)
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
        val folderId = currentFolderId
        if (folder.id != null && folderId != null) {
            navigationStack = navigationStack + (folderId to currentFolderName)
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
                    if (driveService != null) { // Only show Select All if connected
                        TextButton(onClick = { toggleSelectAll() }) {
                            Text("Select All")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (driveService != null && selectedFiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        val filesToImport = selectedFiles.values.toList()

                        // Use the existing logic to find the local file target folder
                        val targetFolder = context.getExternalFilesDir(null)
                            ?: File(context.filesDir, ".")

                        // Start the import process
                        DriveRepository.startImport(filesToImport, targetFolder)

                        Toast.makeText(context, "Importing ${filesToImport.size} files...", Toast.LENGTH_SHORT).show()
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
            if (driveService == null) {
                // *** DISPLAY SIGN-IN BUTTON IF NOT CONNECTED ***
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Connect to Google Drive to import files.",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Button(
                            onClick = {
                                isLoading = true // Show loading while auth flow is active
                                val signInClient = DriveRepository.getGoogleSignInClient(context)
                                googleSignInLauncher.launch(signInClient.signInIntent)
                            },
                            // Use the cloud icon for connecting
                            content = {
                                Icon(Icons.Default.Cloud, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Sign in with Google")
                            }
                        )
                    }
                }
            } else {
                // *** DISPLAY FILE LIST IF CONNECTED ***
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text("Search files...") },
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
                        Text("No files or folders found.")
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
}