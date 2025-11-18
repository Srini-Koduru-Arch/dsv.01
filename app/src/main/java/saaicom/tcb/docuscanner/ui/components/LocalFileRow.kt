package saaicom.tcb.docuscanner.ui.components

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.utils.FileUtils.formatFileSize
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A shared composable for displaying a single local file item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LocalFileRow(
    fileItem: FileItem,
    navController: NavController, // For navigation actions
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onSign: () -> Unit,
    onRename: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = {
                    if (selectionMode) {
                        onToggleSelection()
                    } else {
                        // Pass to a function that lives in a central place
                        val encodedUri = URLEncoder.encode(fileItem.uri.toString(), StandardCharsets.UTF_8.toString())
                        navController.navigate("${Routes.PDF_VIEW.split('/')[0]}/$encodedUri")
                    }
                },
                onLongClick = onLongPress
            )
            .padding(vertical = 4.dp, horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            RadioButton(
                selected = isSelected,
                onClick = onToggleSelection
            )
        } else {
            PdfThumbnail(
                uri = fileItem.uri,
                modifier = Modifier
                    .size(width = 40.dp, height = 50.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(4.dp))
            )
        }

        Spacer(modifier = Modifier.width(if (selectionMode) 4.dp else 16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileItem.name ?: "Unnamed Item",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val sizeString = formatFileSize(fileItem.sizeInBytes)
            Text(
                text = sizeString,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }

        if (!selectionMode) {
            IconButton(
                onClick = { FileActions.emailPdfFile(context, fileItem.name ?: "Document", fileItem.uri) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            Box {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Sign") },
                        onClick = {
                            menuExpanded = false
                            onSign()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Print") },
                        onClick = {
                            menuExpanded = false
                            FileActions.printPdfFile(context, fileItem.name ?: "Document", fileItem.uri)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}