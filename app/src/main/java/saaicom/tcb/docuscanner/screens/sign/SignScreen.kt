package saaicom.tcb.docuscanner.screens.sign

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.SignatureRepository
import saaicom.tcb.docuscanner.models.FileItem
import saaicom.tcb.docuscanner.ui.components.DeleteConfirmationDialog
import saaicom.tcb.docuscanner.ui.components.SelectFileDialog
import saaicom.tcb.docuscanner.utils.calculateInSampleSize
import saaicom.tcb.docuscanner.utils.loadLocalFiles
import saaicom.tcb.docuscanner.utils.rotateBitmap
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import kotlin.math.min
import androidx.compose.foundation.LocalIndication

// DATA CLASS TO HOLD PATH AND ITS STYLE
private data class StyledPath(
    val path: Path,
    val color: Color,
    val width: Float
)

@Composable
fun SignScreen(
    navController: NavController,
    onDrawingChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDrawing by remember { mutableStateOf(false) }
    var signatures by remember { mutableStateOf<List<File>>(emptyList()) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedSignatures by remember { mutableStateOf<Set<File>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var signaturesToDelete by remember { mutableStateOf<List<File>>(emptyList()) }

    // Report state changes up
    LaunchedEffect(isDrawing) {
        onDrawingChange(isDrawing)
    }

    // Reset state if user navigates away
    DisposableEffect(Unit) {
        onDispose {
            onDrawingChange(false)
        }
    }

    // Manage orientation for this screen
    val activity = LocalContext.current as? Activity
    LaunchedEffect(isDrawing, activity) {
        if (isDrawing) {
            // When entering drawing mode, force landscape
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            // When in list mode (not drawing), force portrait
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun loadSignatures() {
        signatures = SignatureRepository.getSavedSignatures(context)
    }

    LaunchedEffect(isDrawing) {
        if (!isDrawing) {
            loadSignatures()
        }
    }

    LaunchedEffect(isDrawing) {
        if (!isDrawing) {
            selectionMode = false
            selectedSignatures = emptySet()
        }
    }

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            count = signaturesToDelete.size,
            itemType = "signature",
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                scope.launch {
                    signaturesToDelete.forEach { SignatureRepository.deleteSignature(it) }
                    loadSignatures()
                    showDeleteDialog = false
                    selectionMode = false
                    selectedSignatures = emptySet()
                }
            }
        )
    }

    if (isDrawing) {
        SignatureCanvas(
            onSave = { bitmap, fileName ->
                scope.launch {
                    SignatureRepository.saveSignature(context, bitmap, fileName)
                    isDrawing = false // This triggers the LaunchedEffect
                    loadSignatures()
                }
            },
            onCancel = {
                isDrawing = false // This triggers the LaunchedEffect
            }
        )
    } else {
        SignatureList(
            navController = navController,
            signatures = signatures,
            onAdd = { isDrawing = true },
            selectionMode = selectionMode,
            selectedSignatures = selectedSignatures,
            onToggleSelection = { file ->
                selectedSignatures = if (selectedSignatures.contains(file)) {
                    selectedSignatures - file
                } else {
                    selectedSignatures + file
                }
                if (selectionMode && selectedSignatures.isEmpty()) {
                    selectionMode = false
                }
            },
            onLongPress = { file ->
                if (!selectionMode) {
                    selectionMode = true
                    selectedSignatures = setOf(file)
                }
            },
            onDeleteSelected = {
                signaturesToDelete = selectedSignatures.toList()
                if (signaturesToDelete.isNotEmpty()) {
                    showDeleteDialog = true
                }
            },
            onSelectAll = {
                selectedSignatures = if (selectedSignatures.size == signatures.size) {
                    emptySet()
                } else {
                    signatures.toSet()
                }
            },
            onCloseSelection = {
                selectionMode = false
                selectedSignatures = emptySet()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureList(
    navController: NavController,
    signatures: List<File>,
    onAdd: () -> Unit,
    selectionMode: Boolean,
    selectedSignatures: Set<File>,
    onToggleSelection: (File) -> Unit,
    onLongPress: (File) -> Unit,
    onDeleteSelected: () -> Unit,
    onSelectAll: () -> Unit,
    onCloseSelection: () -> Unit
) {
    val context = LocalContext.current
    var showSelectFileDialog by remember { mutableStateOf(false) }
    var localFiles by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    val allSelected = signatures.isNotEmpty() && selectedSignatures.size == signatures.size
    val deleteEnabled = selectedSignatures.isNotEmpty()

    LaunchedEffect(Unit) {
        localFiles = loadLocalFiles(context)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (signatures.isNotEmpty()) {
                SignatureListHeader(
                    allSelected = allSelected,
                    onSelectAll = onSelectAll,
                    onDeleteSelected = onDeleteSelected,
                    deleteEnabled = deleteEnabled,
                    selectionMode = selectionMode,
                    onCloseSelection = onCloseSelection
                )
            }

            if (signatures.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Draw,
                            contentDescription = "No signatures",
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No signatures saved.",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Tap the '+' button to add a new signature.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 8.dp)
                ) {
                    items(signatures, key = { it.absolutePath }) { file ->
                        val isSelected = selectedSignatures.contains(file)
                        SignatureItem(
                            file = file,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onToggleSelection = { onToggleSelection(file) },
                            onLongPress = { onLongPress(file) }
                        )
                    }
                }
            }
        }

        if (!selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (signatures.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (localFiles.isEmpty()) {
                                Toast.makeText(context, "No local files found to sign.", Toast.LENGTH_SHORT).show()
                            } else {
                                showSelectFileDialog = true
                            }
                        },
                        icon = { Icon(Icons.Default.Draw, contentDescription = "Sign Document") },
                        text = { Text("Sign Document") },
                        modifier = Modifier.padding(start = 16.dp)
                    )
                } else {
                    Spacer(Modifier)
                }

                FloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.padding(end = 16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Signature")
                }
            }
        }
    }
}

@Composable
private fun SignatureListHeader(
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    deleteEnabled: Boolean,
    selectionMode: Boolean,
    onCloseSelection: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (selectionMode) 56.dp else 0.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            IconButton(onClick = onCloseSelection) {
                Icon(Icons.Default.Close, contentDescription = "Close Selection")
            }
            RadioButton(
                selected = allSelected,
                onClick = onSelectAll
            )
            Text("Select All")
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDeleteSelected, enabled = deleteEnabled) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete Selected",
                    tint = if (deleteEnabled) MaterialTheme.colorScheme.error else Color.Gray
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SignatureItem(
    file: File,
    isSelected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit
) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                options.inSampleSize = calculateInSampleSize(options, 150, 75)
                options.inJustDecodeBounds = false

                val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                imageBitmap = bitmap?.asImageBitmap()
            } catch (e: Exception) {
                Log.e("SignatureItem", "Failed to load bitmap", e)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = {
                    if (selectionMode) {
                        onToggleSelection()
                    }
                },
                onLongClick = {
                    if (!selectionMode) {
                        onLongPress()
                    }
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectionMode) {
                RadioButton(
                    selected = isSelected,
                    onClick = onToggleSelection
                )
            }

            Box(
                modifier = Modifier
                    .height(60.dp)
                    .width(120.dp)
                    .background(Color.White)
                    .border(1.dp, Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap!!,
                        contentDescription = "Saved Signature"
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text(
                text = file.nameWithoutExtension,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureCanvas(
    onSave: (Bitmap, String) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // STYLE STATE
    var currentWidth by remember { mutableStateOf(8f) }
    var currentColor by remember { mutableStateOf(Color.Black) }
    var showStyleDialog by remember { mutableStateOf(false) }

    // PATH STATE (now uses StyledPath)
    val completedPaths = remember { mutableStateListOf<StyledPath>() }
    var currentPath by remember { mutableStateOf(Path()) }

    var canvasSize by remember { mutableStateOf<IntSize?>(null) }
    var rotation by remember { mutableStateOf(0f) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var bitmapToSave by remember { mutableStateOf<Bitmap?>(null) }

    val hasDrawing = completedPaths.isNotEmpty() || !currentPath.isEmpty

    if (showSaveDialog && bitmapToSave != null) {
        SaveSignatureDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { fileName ->
                onSave(bitmapToSave!!, fileName)
                showSaveDialog = false
                bitmapToSave = null
            }
        )
    }

    // STYLE DIALOG
    if (showStyleDialog) {
        SignatureStyleDialog(
            initialColor = currentColor,
            initialWidth = currentWidth,
            onDismiss = { showStyleDialog = false },
            onStyleChange = { newColor, newWidth ->
                currentColor = newColor
                currentWidth = newWidth
                showStyleDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create New Signature") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // 1. Style Picker Button
                    IconButton(onClick = { showStyleDialog = true }) {
                        Icon(Icons.Default.Palette, contentDescription = "Styles")
                    }

                    // 2. Clear Button
                    IconButton(
                        onClick = {
                            completedPaths.clear()
                            currentPath = Path()
                        },
                        enabled = hasDrawing
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }

                    // 3. Rotate Left
                    IconButton(onClick = { rotation = (rotation - 90f) % 360 }) {
                        Icon(Icons.Default.RotateLeft, contentDescription = "Rotate Left")
                    }

                    // 4. Rotate Right
                    IconButton(onClick = { rotation = (rotation + 90f) % 360 }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate Right")
                    }

                    // 5. Save Button
                    IconButton(
                        onClick = {
                            canvasSize?.let { size ->
                                scope.launch {
                                    val pathsToSave = completedPaths.toMutableList()
                                    if (!currentPath.isEmpty) {
                                        pathsToSave.add(StyledPath(currentPath, currentColor, currentWidth))
                                    }
                                    val bitmap = captureSignature(size, pathsToSave)
                                    val finalBitmap = if (rotation != 0f) {
                                        rotateBitmap(bitmap, rotation)
                                    } else {
                                        bitmap
                                    }
                                    bitmapToSave = finalBitmap
                                    showSaveDialog = true
                                }
                            }
                        },
                        enabled = hasDrawing
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues), // Respects Scaffold's top bar
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .graphicsLayer { rotationZ = rotation }
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                val tapPath = Path().apply {
                                    moveTo(offset.x, offset.y)
                                    lineTo(offset.x + 0.01f, offset.y + 0.01f)
                                }
                                completedPaths.add(StyledPath(tapPath, currentColor, currentWidth))
                                currentPath = Path() // Reset current path
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                currentPath = Path().apply {
                                    moveTo(offset.x, offset.y)
                                }
                            },
                            onDrag = { change, _ ->
                                val oldPath = currentPath
                                currentPath = Path().apply {
                                    addPath(oldPath)
                                    lineTo(change.position.x, change.position.y)
                                }
                                change.consume()
                            },
                            onDragEnd = {
                                completedPaths.add(StyledPath(currentPath, currentColor, currentWidth))
                                currentPath = Path()
                            }
                        )
                    }
            ) {
                // Draw all completed, styled paths
                completedPaths.forEach { (path, color, width) ->
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = width,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
                // Draw the current, in-progress path
                drawPath(
                    path = currentPath,
                    color = currentColor,
                    style = Stroke(
                        width = currentWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        }
    }
}

// NEW COMPOSABLE FOR STYLE SELECTION
@Composable
private fun SignatureStyleDialog(
    initialColor: Color,
    initialWidth: Float,
    onDismiss: () -> Unit,
    onStyleChange: (Color, Float) -> Unit
) {
    var selectedColor by remember { mutableStateOf(initialColor) }
    var selectedWidth by remember { mutableStateOf(initialWidth) }
    val colors = listOf(Color.Black, Color.Red, Color.Blue, Color.Green)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Style") },
        text = {
            Column {
                Text("Color", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    colors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = 2.dp,
                                    color = if (selectedColor == color) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape
                                )
                                // <<< THIS IS THE FIX >>>
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = LocalIndication.current,
                                    onClick = { selectedColor = color }
                                )
                            // <<< ^^^ END OF FIX ^^^ >>>
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Thickness", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = selectedWidth,
                    onValueChange = { selectedWidth = it },
                    valueRange = 7f..21f,
                    steps = 7
                )
                Text("Width: ${selectedWidth.toInt()}", modifier = Modifier.align(Alignment.End))
            }
        },
        confirmButton = {
            Button(onClick = { onStyleChange(selectedColor, selectedWidth) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveSignatureDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf("My Signature") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Signature") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Signature Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
            )
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onSave(text) },
                enabled = text.isNotBlank()
            ) {
                Text("Save")
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
 * Captures the signature canvas as a bitmap.
 * THIS FUNCTION IS NOW UPDATED to handle StyledPath
 */
private suspend fun captureSignature(size: IntSize, paths: List<StyledPath>): Bitmap = withContext(Dispatchers.IO) {
    val originalBitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(originalBitmap)
    canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.SRC)

    // Use a single Paint object and reconfigure it for each path
    val paint = android.graphics.Paint().apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        isAntiAlias = true
    }

    paths.forEach { (path, color, width) ->
        paint.color = color.toArgb() // Convert Compose Color to Android Color
        paint.strokeWidth = width
        canvas.drawPath(path.asAndroidPath(), paint)
    }

    // --- Scaling logic remains the same ---
    val targetWidth = 210
    val targetHeight = 149
    val finalBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    finalBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
    val finalCanvas = android.graphics.Canvas(finalBitmap)

    // <<< HERE IS THE FIX >>>
    val aspectScale = min(
        targetWidth.toFloat() / originalBitmap.width,
        targetHeight.toFloat() / originalBitmap.height // Corrected 'originalDimen.width' to 'originalBitmap.height'
    )
    // <<< END OF FIX >>>

    val scaledWidth = (originalBitmap.width * aspectScale).toInt()
    val scaledHeight = (originalBitmap.height * aspectScale).toInt()
    val left = (targetWidth - scaledWidth) / 2f
    val top = (targetHeight - scaledHeight) / 2f
    val destRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
    finalCanvas.drawBitmap(originalBitmap, null, destRect, null)
    originalBitmap.recycle()

    return@withContext finalBitmap
}

// Helper function to convert Compose Color to Android's integer color
private fun Color.toArgb(): Int {
    return android.graphics.Color.argb(
        (this.alpha * 255).toInt(),
        (this.red * 255).toInt(),
        (this.green * 255).toInt(),
        (this.blue * 255).toInt()
    )
}