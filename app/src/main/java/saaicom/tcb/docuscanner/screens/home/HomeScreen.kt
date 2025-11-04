package saaicom.tcb.docuscanner.screens.home

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Log
// import android.webkit.WebView // *** REMOVED: No longer needed ***
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow // *** ADDED: Import ***
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.DriveRepository // Import DriveRepository
import saaicom.tcb.docuscanner.FileActions // *** ADDED: Import FileActions ***
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.UserData
import saaicom.tcb.docuscanner.UserDataStore
import saaicom.tcb.docuscanner.screens.files.FileItem // Import FileItem
import saaicom.tcb.docuscanner.screens.files.FileItemRow // Import FileItemRow
import com.google.api.services.drive.model.File as DriveFile // Alias Drive File
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

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
    var cloudFiles by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    val driveService by DriveRepository.driveService.collectAsState()
    val isDriveConnected = driveService != null
    var isCloudLoading by remember { mutableStateOf(false) } // *** ADDED: Cloud loading state ***

    // Load local files only when permissions are granted
    LaunchedEffect(hasStoragePermission) {
        Log.d("HomeScreen", "Local file effect triggered. HasStorage: $hasStoragePermission")
        if (hasStoragePermission) {
            localFiles = loadLocalFiles(context)
        }
    }

    // Load cloud files ONLY when connection state changes
    LaunchedEffect(isDriveConnected) {
        Log.d("HomeScreen", "Drive service effect triggered. Connected: $isDriveConnected")
        if (isDriveConnected) {
            // *** ADDED: Set loading state ***
            isCloudLoading = true
            driveService?.let { service ->
                Log.d("HomeScreen", "Drive service available, loading cloud files.")
                val folderId = DriveRepository.findOrCreateDocuScannerFolder(service)
                if (folderId != null) {
                    Log.d("HomeScreen", "Found/Created DocuScanner folder: $folderId. Loading files...")
                    cloudFiles = DriveRepository.loadDriveFiles(service, folderId)
                    Log.d("HomeScreen", "Loaded ${cloudFiles.size} cloud files.")
                } else {
                    Log.e("HomeScreen", "Failed to find or create DocuScanner folder.")
                    cloudFiles = emptyList()
                }
            }
            // *** ADDED: Clear loading state ***
            isCloudLoading = false
        } else {
            Log.d("HomeScreen", "Drive service is null, clearing cloud files.")
            cloudFiles = emptyList()
            isCloudLoading = false // Ensure loading is false if not connected
        }
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
        // Show the main dashboard if terms are accepted
        FilesDashboard(
            navController = navController,
            localFiles = localFiles,
            cloudFiles = cloudFiles,
            isDriveConnected = isDriveConnected,
            isCloudLoading = isCloudLoading // *** ADDED: Pass loading state ***
        )
    }
}

// Extracted composable for the main file dashboard view
@Composable
fun FilesDashboard(
    navController: NavController,
    localFiles: List<FileItem>,
    cloudFiles: List<DriveFile>,
    isDriveConnected: Boolean,
    isCloudLoading: Boolean // *** ADDED: Receive loading state ***
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        // *** UPDATED: Reduced spacing between sections ***
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Local Files Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Local Files", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { navController.navigate(Routes.FILES) }) {
                    Text("View All")
                }
            }
            // Reduced space after header
            // Spacer(modifier = Modifier.height(8.dp))
        }
        if (localFiles.isEmpty()) {
            item { Text("No local files found in Downloads/DocuScanner.") }
        } else {
            // *** UPDATED: Show 5 items ***
            items(localFiles.take(5)) { file ->
                LocalFileItemRow(
                    fileItem = file,
                    onOpen = { openPdfFile(context, file.uri) },
                    onPrint = { FileActions.printPdfFile(context, file.name ?: "Document", file.uri) },
                    onEmail = { FileActions.emailPdfFile(context, file.name ?: "Document", file.uri) }
                )
                // Reduced space between file items
                Divider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        item {
            // Add space before next section divider
            Spacer(modifier = Modifier.height(8.dp))
            Divider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // Cloud Files Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Cloud Files (Google Drive)", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { navController.navigate(Routes.CLOUD_FILES) }) {
                    Text("View All")
                }
            }
            // Reduced space after header
            // Spacer(modifier = Modifier.height(8.dp))
        }
        // *** UPDATED: Logic to show loading indicator ***
        if (!isDriveConnected) {
            item {
                Text("Connect your Google Drive account from the 'Cloud' tab to view files here.")
            }
        } else if (isCloudLoading) { // Check loading state first
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (cloudFiles.isEmpty()) {
            item { Text("No files found in your Google Drive / DocuScanner folder.") }
        } else {
            // *** UPDATED: Show 5 items ***
            items(cloudFiles.take(5)) { file ->
                DriveFileItemRow(
                    driveFile = file,
                    onOpen = {
                        Toast.makeText(context, "Open/Download Cloud File: ${file.name}", Toast.LENGTH_SHORT).show()
                        Log.d("HomeScreen", "Request Open cloud file: ${file.name} (ID: ${file.id})")
                    },
                    onPrint = {
                        Toast.makeText(context, "Print Cloud File: ${file.name}", Toast.LENGTH_SHORT).show()
                        Log.d("HomeScreen", "Request Print cloud file: ${file.name} (ID: ${file.id})")
                    },
                    onEmail = {
                        Toast.makeText(context, "Email Cloud File: ${file.name}", Toast.LENGTH_SHORT).show()
                        Log.d("HomeScreen", "Request Email cloud file: ${file.name} (ID: ${file.id})")
                    }
                )
                // Reduced space between file items
                Divider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        item {
            // Moved "View All" button to the header row
        }
    }
}

/**
 * Displays a row for a local file with action buttons.
 */
@Composable
fun LocalFileItemRow(
    fileItem: FileItem,
    onOpen: () -> Unit,
    onPrint: () -> Unit,
    onEmail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            // *** UPDATED: Reduced vertical padding ***
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.PictureAsPdf,
            contentDescription = "PDF File",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = fileItem.name ?: "Unnamed Item",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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


/**
 * Displays a row for a Google Drive file or folder with action icons.
 */
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
            .clickable(onClick = onOpen)
            // *** UPDATED: Reduced vertical padding ***
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isFolder) Icons.Default.Folder else Icons.Default.PictureAsPdf,
            contentDescription = if (isFolder) "Folder" else "PDF File",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
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


// Extracted composable for the terms and conditions screen
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
            modifier = Modifier.clickable(onClick = onAccept)
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


// --- Helper Functions ---

// Function to load local PDF files from Downloads/DocuScanner
private suspend fun loadLocalFiles(context: Context): List<FileItem> = withContext(Dispatchers.IO) {
    val files = mutableListOf<FileItem>()
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
    val selectionArgs = arrayOf("application/pdf", "%Download/DocuScanner%")
    val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

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
    Log.d("HomeScreen", "Found ${files.size} local PDF files.")
    return@withContext files
}

// Function to open a PDF file using an Intent
private fun openPdfFile(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No PDF viewer app found", Toast.LENGTH_SHORT).show()
        Log.e("HomeScreen", "No PDF viewer app found for URI: $uri", e)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show()
        Log.e("HomeScreen", "Error opening file URI: $uri", e)
    }
}

// *** REMOVED printPdfFile and emailPdfFile functions - Moved to FileActions.kt ***

