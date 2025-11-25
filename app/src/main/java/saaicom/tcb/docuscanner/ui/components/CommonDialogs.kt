package saaicom.tcb.docuscanner.ui.components

import android.net.Uri
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import saaicom.tcb.docuscanner.models.FileItem

/**
 * Dialog to select a local file for signing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectFileDialog(
    localFiles: List<FileItem>,
    onDismiss: () -> Unit,
    onFileSelected: (Uri) -> Unit
) {
    var fileSearchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val filteredFiles = remember(fileSearchQuery, localFiles) {
        if (fileSearchQuery.isBlank()) {
            localFiles
        } else {
            localFiles.filter {
                it.name?.contains(fileSearchQuery, ignoreCase = true) == true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select a File to Sign") },
        text = {
            Column(modifier = Modifier.fillMaxHeight(0.7f)) {
                OutlinedTextField(
                    value = fileSearchQuery,
                    onValueChange = { fileSearchQuery = it },
                    label = { Text("Search files") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (fileSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { fileSearchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                if (filteredFiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No files found.")
                    }
                } else {
                    LazyColumn {
                        items(filteredFiles) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // <<< FIX: Use explicit interactionSource and indication >>>
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = LocalIndication.current,
                                        onClick = { onFileSelected(file.uri) }
                                    )
                                    // <<< END FIX >>>
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF")
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = file.name ?: "Unnamed",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog to confirm deletion of one or more items.
 */
@Composable
fun DeleteConfirmationDialog(
    count: Int,
    itemType: String = "file", // Default item type
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val itemPlural = if (count > 1) "${itemType}s" else itemType
    val title = "Delete $itemPlural?"
    val text = "Are you sure you want to permanently delete $count selected $itemPlural?"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * A dialog box that prompts the user to rename a file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameFileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    // Pre-fill the text field without the ".pdf" extension
    var newName by remember { mutableStateOf(currentName.removeSuffix(".pdf")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename File") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New file name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onRename(newName) // Pass the new name up
                    }
                },
                // Disable button if name is blank or unchanged
                enabled = newName.isNotBlank() && newName != currentName.removeSuffix(".pdf")
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}