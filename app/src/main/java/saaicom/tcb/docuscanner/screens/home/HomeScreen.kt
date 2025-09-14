package saaicom.tcb.docuscanner.screens.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult // Import for rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList // Import for SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



// --- Data Classes for Home Screen ---
data class ScannedDocument(
    val id: String,
    val name: String,
    val dateScanned: Long, // Timestamp in milliseconds
    val path: String, // Path to the actual file
    val thumbnailUrl: String? = null // For displaying a small preview
)

sealed class FileItem {
    abstract val name: String // Declare name as an abstract property

    data class Folder(val id: String, override val name: String, val path: String, val itemCount: Int) : FileItem()
    data class DocumentFile(val id: String, override val name: String, val path: String, val size: String, val dateModified: Long) : FileItem()
}

@Composable
fun HomeScreen(hasStoragePermission: Boolean) {
    val context = LocalContext.current
    // State to hold the lists of documents and files
    val recentDocuments = remember { mutableStateListOf<ScannedDocument>() }
    val localFilesAndFolders = remember { mutableStateListOf<FileItem>() }
    val hasStoragePermission = remember { mutableStateOf(false) }

    // Request storage permission if not granted
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasStoragePermission.value = isGranted
        if (isGranted) {
            Log.d("HomeScreen", "READ_EXTERNAL_STORAGE permission granted.")
            // Reload files after permission is granted
            loadLocalFiles(context, recentDocuments, localFilesAndFolders)
        } else {
            Log.w("HomeScreen", "READ_EXTERNAL_STORAGE permission denied.")
            // Optionally, show a message to the user about why permission is needed
        }
    }

    // Check and request permission on app start or when HomeScreen is composed
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            hasStoragePermission.value = true
            loadLocalFiles(context, recentDocuments, localFilesAndFolders)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.background) // Use theme background color
    ) {
        if (!hasStoragePermission.value) {
            // Show a message if permission is not granted
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Storage permission is required to display files.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        } else {
            // Section 1: Recently Scanned Documents
            Text(
                text = "Recently Scanned Documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
            Card(
                modifier = Modifier
                    .weight(0.5f) // Takes half of the remaining screen height
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // Use theme surface color
            ) {
                if (recentDocuments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No recent documents found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(recentDocuments) { document ->
                            RecentDocumentItem(document = document) { clickedDoc ->
                                // TODO: Handle document click (e.g., open document viewer)
                                Log.d("HomeScreen", "Clicked recent document: ${clickedDoc.name} at path: ${clickedDoc.path}")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp)) // Space between sections

            // Section 2: Local Folders and Files
            Text(
                text = "Local Files and Folders",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
            Card(
                modifier = Modifier
                    .weight(0.5f) // Takes the other half of the remaining screen height
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface) // Use theme surface color
            ) {
                if (localFilesAndFolders.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No local files or folders found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(localFilesAndFolders) { item ->
                            when (item) {
                                is FileItem.Folder -> FolderItem(folder = item) { clickedFolder ->
                                    // TODO: Handle folder click (e.g., navigate into folder)
                                    Log.d("HomeScreen", "Clicked folder: ${clickedFolder.name} at path: ${clickedFolder.path}")
                                }
                                is FileItem.DocumentFile -> DocumentFileItem(document = item) { clickedFile ->
                                    // TODO: Handle document file click (e.g., open file)
                                    Log.d("HomeScreen", "Clicked document file: ${clickedFile.name} at path: ${clickedFile.path}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentDocumentItem(document: ScannedDocument, onClick: (ScannedDocument) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        onClick = { onClick(document) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // Lighter background for items
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail/Icon
            Icon(
                imageVector = Icons.Outlined.Description, // Placeholder icon
                contentDescription = "Document icon",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scanned: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(document.dateScanned))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Add more actions like share, delete etc.
            IconButton(onClick = { /* TODO: Implement more options */ }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
        }
    }
}

@Composable
fun FolderItem(folder: FileItem.Folder, onClick: (FileItem.Folder) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        onClick = { onClick(folder) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // Lighter background for items
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = "Folder icon",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${folder.itemCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { /* TODO: Implement more options */ }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
        }
    }
}

@Composable
fun DocumentFileItem(document: FileItem.DocumentFile, onClick: (FileItem.DocumentFile) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        onClick = { onClick(document) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant) // Lighter background for items
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.InsertDriveFile, // Generic document icon
                contentDescription = "Document file icon",
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${document.size} • Modified: ${SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(document.dateModified))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { /* TODO: Implement more options */ }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
        }
    }
}

/**
 * Function to load local files from a specified directory.
 * This is a simplified example. In a real app, you'd handle various storage types
 * and potentially use a more robust file management library or ContentResolver.
 */
private fun loadLocalFiles(
    context: android.content.Context, // Use android.content.Context explicitly
    recentDocuments: SnapshotStateList<ScannedDocument>,
    localFilesAndFolders: SnapshotStateList<FileItem>
) {
    recentDocuments.clear()
    localFilesAndFolders.clear()

    // Define the directory to scan. For simplicity, let's target the Documents directory.
    // NOTE: Accessing external storage (like DIRECTORY_DOCUMENTS) requires READ_EXTERNAL_STORAGE permission.
    // For app-specific files, context.filesDir or context.getExternalFilesDir() is preferred.
    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    Log.d("HomeScreen", "Scanning directory: ${documentsDir.absolutePath}")

    if (documentsDir.exists() && documentsDir.isDirectory) {
        val allFilesAndFolders = documentsDir.listFiles()?.toList() ?: emptyList()

        val tempRecentDocuments = mutableListOf<ScannedDocument>()
        val tempLocalFilesAndFolders = mutableListOf<FileItem>()

        allFilesAndFolders.forEach { file ->
            if (file.isDirectory) {
                tempLocalFilesAndFolders.add(
                    FileItem.Folder(
                        id = file.absolutePath,
                        name = file.name, // Access name here
                        path = file.absolutePath,
                        itemCount = file.listFiles()?.size ?: 0
                    )
                )
            } else if (file.isFile) {
                val fileName = file.name
                val fileExtension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())

                // Example: Only consider PDF and image files as "scanned documents" for the recent list
                if (fileExtension == "pdf" || fileExtension == "jpg" || fileExtension == "jpeg" || fileExtension == "png") {
                    tempRecentDocuments.add(
                        ScannedDocument(
                            id = file.absolutePath,
                            name = fileName,
                            dateScanned = file.lastModified(),
                            path = file.absolutePath
                        )
                    )
                }

                tempLocalFilesAndFolders.add(
                    FileItem.DocumentFile(
                        id = file.absolutePath,
                        name = fileName, // Access name here
                        path = file.absolutePath,
                        size = formatFileSize(file.length()),
                        dateModified = file.lastModified()
                    )
                )
            }
        }

        // Sort and add to the observable lists
        recentDocuments.addAll(tempRecentDocuments.sortedByDescending { it.dateScanned })
        localFilesAndFolders.addAll(tempLocalFilesAndFolders.sortedBy { it.name }) // 'it.name' is now accessible

        Log.d("HomeScreen", "Found ${recentDocuments.size} recent documents and ${localFilesAndFolders.size} local files/folders.")

    } else {
        Log.w("HomeScreen", "Documents directory does not exist or is not a directory: ${documentsDir.absolutePath}")
    }
}

/**
 * Helper function to format file size for display.
 */
private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}