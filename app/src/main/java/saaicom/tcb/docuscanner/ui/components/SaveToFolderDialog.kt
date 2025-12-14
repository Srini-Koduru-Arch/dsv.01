package saaicom.tcb.docuscanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToFolderDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String, File) -> Unit
) {
    val context = LocalContext.current
    val rootDir = remember { context.getExternalFilesDir(null) ?: File(context.filesDir, ".") }

    // State
    var fileName by remember { mutableStateOf(initialName) }
    var selectedFolder by remember { mutableStateOf(rootDir) }
    var currentPickerDir by remember { mutableStateOf(rootDir) }
    var isPickingFolder by remember { mutableStateOf(false) }

    // Create Folder State
    var showCreateFolderInput by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    if (isPickingFolder) {
        // --- VIEW 2: FOLDER NAVIGATOR (Sleek Version) ---
        AlertDialog(
            onDismissRequest = { isPickingFolder = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showCreateFolderInput) "New Folder" else "Select Location",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    // SLEEK CANCEL: Top-right 'X' icon
                    IconButton(onClick = {
                        if(showCreateFolderInput) showCreateFolderInput = false else isPickingFolder = false
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }
            },
            text = {
                Column {
                    if (showCreateFolderInput) {
                        OutlinedTextField(
                            value = newFolderName,
                            onValueChange = { newFolderName = it },
                            label = { Text("Folder Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        // SLEEK PATH HEADER
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                if (currentPickerDir != rootDir) {
                                    Icon(
                                        Icons.Default.ArrowBack,
                                        "Back",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable { currentPickerDir = currentPickerDir.parentFile ?: rootDir },
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(18.dp), tint = Color.Gray)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (currentPickerDir == rootDir) "Main" else currentPickerDir.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // FOLDER LIST
                        val subFolders = remember(currentPickerDir) {
                            currentPickerDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
                        }

                        Box(modifier = Modifier.height(250.dp)) {
                            if (subFolders.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Empty folder", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(subFolders) { folder ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { currentPickerDir = folder }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                                            Spacer(Modifier.width(16.dp))
                                            Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        Divider(color = Color.LightGray.copy(alpha = 0.1f))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (showCreateFolderInput) {
                    Button(
                        onClick = {
                            if (newFolderName.isNotBlank()) {
                                val newDir = File(currentPickerDir, newFolderName)
                                if (!newDir.exists()) newDir.mkdirs()
                                currentPickerDir = newDir
                                showCreateFolderInput = false
                                newFolderName = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Create Folder")
                    }
                } else {
                    // SLEEK BOTTOM BAR
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LEFT: New Folder Icon Button
                        FilledTonalIconButton(
                            onClick = { showCreateFolderInput = true }
                        ) {
                            Icon(Icons.Default.CreateNewFolder, "New Folder")
                        }

                        // RIGHT: Select Button (Compact)
                        Button(
                            onClick = {
                                selectedFolder = currentPickerDir
                                isPickingFolder = false
                            },
                            contentPadding = PaddingValues(horizontal = 20.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Select")
                        }
                    }
                }
            },
            dismissButton = null // We moved Dismiss to the top-right 'X'
        )
    } else {
        // --- VIEW 1: MAIN FORM (Sleek Version) ---
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Save As", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Gray)
                    }
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it },
                        label = { Text("File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Folder Selector Card
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                currentPickerDir = selectedFolder
                                isPickingFolder = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Location", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    text = selectedFolder.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (fileName.isNotBlank()) {
                            onSave(fileName, selectedFolder)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save PDF")
                }
            },
            dismissButton = null // Moved to top right
        )
    }
}