package saaicom.tcb.docuscanner.screens.edit

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource // Added import
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import saaicom.tcb.docuscanner.DocumentRepository
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.Scanner
import saaicom.tcb.docuscanner.utils.FileUtils // Make sure this matches your package
import kotlin.math.min

// --- Constants ---
private const val ANALYSIS_WIDTH = 720.0
private const val ANALYSIS_HEIGHT = 1280.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedDocumentEditScreen(
    navController: NavController,
    imageUri: Uri,
    cornersJson: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) } // Current page DISPLAY bitmap
    var cornerPoints by remember { mutableStateOf<List<Offset>?>(null) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    // --- UPDATED: Use URIs instead of Bitmaps for the list ---
    var scannedPageUris by remember { mutableStateOf<List<Uri>>(DocumentRepository.getAllPages()) }
    var currentPageIndex by remember { mutableStateOf(DocumentRepository.getPageCount() - 1) }

    var pageHasBeenAdded by remember { mutableStateOf(false) }


    LaunchedEffect(imageUri, cornersJson) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                // 1. Load the full-resolution bitmap for EDITING
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val legacyBitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                    legacyBitmap.copy(Bitmap.Config.ARGB_8888, true)
                }

                // 2. Scaling Logic (Fixes handle position on high-res photos)
                val bitmapWidth = bitmap.width.toDouble()
                val bitmapHeight = bitmap.height.toDouble()
                val scaleX = bitmapWidth / ANALYSIS_WIDTH
                val scaleY = bitmapHeight / ANALYSIS_HEIGHT

                val detectedCorners: List<Offset> = if (cornersJson != null) {
                    try {
                        cornersJson.split(",").map {
                            val parts = it.split(":")
                            val lowResX = parts[0].toFloat()
                            val lowResY = parts[1].toFloat()
                            Offset((lowResX * scaleX).toFloat(), (lowResY * scaleY).toFloat())
                        }
                    } catch (e: Exception) { null }
                } else { null } ?: listOf(
                    Offset(0f, 0f),
                    Offset(bitmapWidth.toFloat(), 0f),
                    Offset(bitmapWidth.toFloat(), bitmapHeight.toFloat()),
                    Offset(0f, bitmapHeight.toFloat())
                )

                // 3. Set Initial State
                originalBitmap = bitmap
                croppedBitmap = null // Start in edit mode
                cornerPoints = detectedCorners
                pageHasBeenAdded = false

                // Update URI list state
                scannedPageUris = DocumentRepository.getAllPages()
                currentPageIndex = DocumentRepository.getPageCount()

            } catch (e: Exception) {
                Log.e("EditScreen", "Failed to load image.", e)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                }
            }
        }
        isLoading = false
    }

    // --- Dialog with Debug Logs ---
    if (showPdfDialog) {
        SavePdfDialog(
            onDismiss = { showPdfDialog = false },
            onSave = { fileName ->
                android.util.Log.e("SaveDebug", "1. CALLBACK: onSave lambda triggered with: $fileName")
                showPdfDialog = false
                isLoading = true
                scope.launch {
                    android.util.Log.e("SaveDebug", "2. SCOPE: Coroutine launched. Calling FileActions...")

                    // --- UPDATED: Pass list of URIs ---
                    FileActions.saveBitmapsAsPdf(
                        uris = scannedPageUris, // Using URIs now
                        fileName = fileName,
                        context = context,
                        onComplete = { success ->
                            android.util.Log.e("SaveDebug", "3. RESULT: onComplete received. Success=$success")
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, "Saved Successfully!", Toast.LENGTH_LONG).show()
                                DocumentRepository.clear()
                                navController.navigate(Routes.FILES) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            } else {
                                Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().height(42.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (scannedPageUris.isNotEmpty()) { // Updated variable
                            "Page ${currentPageIndex + 1} of ${scannedPageUris.size}"
                        } else if (croppedBitmap == null) {
                            "Adjust Edges"
                        } else {
                            "Document Ready"
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().height(42.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                if (croppedBitmap == null && originalBitmap != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        // --- CROP BUTTON (Updated Logic) ---
                        Button(onClick = {
                            val ob = originalBitmap
                            val cp = cornerPoints
                            if (ob != null && cp != null) {
                                isLoading = true
                                scope.launch(Dispatchers.IO) {
                                    Log.e("CropDebug", "1. Starting Crop...")

                                    // 1. Perform Crop
                                    val scanner = Scanner()
                                    val ocvPoints = cp.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()

                                    // Sort points to prevent flipping
                                    val scannedData = Scanner.ScannedData(
                                        original = ob,
                                        corners = org.opencv.core.MatOfPoint2f(*ocvPoints)
                                    )
                                    val finalData = scanner.applyPerspectiveTransform(scannedData)
                                    val newBitmap = finalData.scanned ?: finalData.original

                                    // --- NEW: Save to Disk Immediately ---
                                    val newUri = FileUtils.saveBitmapToTempFile(context, newBitmap)

                                    // 2. Add to Repo Logic
                                    val currentRepoSize = DocumentRepository.getPageCount()

                                    if (currentRepoSize == 0) {
                                        DocumentRepository.addPage(newUri)
                                        pageHasBeenAdded = true
                                    } else if (!pageHasBeenAdded) {
                                        DocumentRepository.addPage(newUri)
                                        pageHasBeenAdded = true
                                    } else {
                                        if (currentPageIndex < currentRepoSize) {
                                            DocumentRepository.replacePage(currentPageIndex, newUri)
                                        } else {
                                            DocumentRepository.addPage(newUri)
                                        }
                                    }

                                    // 3. Refresh State
                                    val allPages = DocumentRepository.getAllPages()
                                    val newIndex = if (allPages.isNotEmpty()) allPages.size - 1 else 0

                                    withContext(Dispatchers.Main) {
                                        // We keep newBitmap in memory just for display
                                        croppedBitmap = newBitmap
                                        scannedPageUris = allPages // Update list state

                                        if (pageHasBeenAdded && currentPageIndex != newIndex) {
                                            currentPageIndex = newIndex
                                        }
                                        isLoading = false
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Crop")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("CROP")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { navController.navigate(Routes.CAMERA) }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        // --- PREVIOUS BUTTON (Updated to load Bitmap) ---
                        IconButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    isLoading = true
                                    scope.launch(Dispatchers.IO) {
                                        currentPageIndex--
                                        val uri = DocumentRepository.getPage(currentPageIndex)
                                        if (uri != null) {
                                            // Load the bitmap for display
                                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                val src = ImageDecoder.createSource(context.contentResolver, uri)
                                                ImageDecoder.decodeBitmap(src) { d, _, _ -> d.isMutableRequired = true }
                                            } else {
                                                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                            }
                                            withContext(Dispatchers.Main) {
                                                croppedBitmap = bmp
                                                isLoading = false
                                            }
                                        } else {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = "Previous Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        // --- NEXT BUTTON (Updated to load Bitmap) ---
                        IconButton(
                            onClick = {
                                if (currentPageIndex < scannedPageUris.size - 1) {
                                    isLoading = true
                                    scope.launch(Dispatchers.IO) {
                                        currentPageIndex++
                                        val uri = DocumentRepository.getPage(currentPageIndex)
                                        if (uri != null) {
                                            // Load the bitmap for display
                                            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                val src = ImageDecoder.createSource(context.contentResolver, uri)
                                                ImageDecoder.decodeBitmap(src) { d, _, _ -> d.isMutableRequired = true }
                                            } else {
                                                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                            }
                                            withContext(Dispatchers.Main) {
                                                croppedBitmap = bmp
                                                isLoading = false
                                            }
                                        } else {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = currentPageIndex < scannedPageUris.size - 1
                        ) {
                            Icon(Icons.Default.NavigateNext, contentDescription = "Next Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        // --- PDF SAVE BUTTON ---
                        IconButton(onClick = {
                            android.util.Log.e("GroundZero", "1. PDF Icon Clicked!")
                            if (scannedPageUris.isNotEmpty()) { // Updated check
                                showPdfDialog = true
                            } else {
                                android.util.Log.e("GroundZero", "2. ERROR: Page list is empty!")
                            }
                        }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Generate PDF", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (croppedBitmap != null) {
                Image(
                    bitmap = croppedBitmap!!.asImageBitmap(),
                    contentDescription = "Final Cropped Document",
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null, // No ripple
                            onClick = {
                                // Optional: Tap to re-edit.
                                // Note: To fully implement re-edit from a saved file,
                                // you'd need to reload original + corners, which is complex.
                                // For now, clicking just clears the view (back to crop mode IF original is set)
                                // But typically "croppedBitmap = null" returns to the crop view of the *current* original.
                                croppedBitmap = null
                            }
                        )
                )
            } else if (originalBitmap != null) {
                AdjustableCropView(
                    bitmap = originalBitmap!!,
                    points = cornerPoints ?: emptyList(),
                    onPointsChanged = { newPoints -> cornerPoints = newPoints }
                )
            }
        }
    }
}

// --- Dialog Composable ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavePdfDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("DocuScan-${System.currentTimeMillis()}.pdf") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save PDF") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    android.util.Log.e("SaveDebug", "0. INSIDE DIALOG: Save Button Clicked! Text: '$text'")
                    if (text.isNotBlank()) onSave(text)
                },
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

// --- AdjustableCropView Composables ---
@Composable
fun AdjustableCropView(
    bitmap: ImageBitmap,
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var draggedCornerIndex by remember { mutableStateOf<Int?>(null) }
    var magnifierCenter by remember { mutableStateOf<Offset?>(null) }
    val touchRadius = 100f

    val transformedPoints = remember { mutableStateListOf<Offset>() }

    LaunchedEffect(points, viewSize, bitmap) {
        if (viewSize.width == 0 || viewSize.height == 0 || points.isEmpty()) {
            transformedPoints.clear()
            return@LaunchedEffect
        }
        val scaleX = viewSize.width.toFloat() / bitmap.width
        val scaleY = viewSize.height.toFloat() / bitmap.height
        val scale = min(scaleX, scaleY)
        val offsetX = (viewSize.width - bitmap.width * scale) / 2
        val offsetY = (viewSize.height - bitmap.height * scale) / 2

        val newTransformedPoints = points.map { Offset(it.x * scale + offsetX, it.y * scale + offsetY) }

        if (transformedPoints.size == newTransformedPoints.size) {
            newTransformedPoints.forEachIndexed { index, point ->
                if (transformedPoints[index] != point) transformedPoints[index] = point
            }
        } else {
            transformedPoints.clear()
            transformedPoints.addAll(newTransformedPoints)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(viewSize, bitmap) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            draggedCornerIndex = transformedPoints.indices
                                .minByOrNull { i -> (transformedPoints[i] - startOffset).getDistance() }
                                ?.takeIf { i -> (transformedPoints[i] - startOffset).getDistance() < touchRadius }
                        },
                        onDrag = { change, _ ->
                            draggedCornerIndex?.let { index ->
                                val scaleX = viewSize.width.toFloat() / bitmap.width
                                val scaleY = viewSize.height.toFloat() / bitmap.height
                                val scale = min(scaleX, scaleY)
                                val offsetX = (viewSize.width - bitmap.width * scale) / 2
                                val offsetY = (viewSize.height - bitmap.height * scale) / 2

                                val imageRectX = offsetX..(offsetX + bitmap.width * scale)
                                val imageRectY = offsetY..(offsetY + bitmap.height * scale)

                                val newPosition = change.position
                                val clampedPosition = Offset(
                                    newPosition.x.coerceIn(imageRectX),
                                    newPosition.y.coerceIn(imageRectY)
                                )

                                transformedPoints[index] = clampedPosition
                                magnifierCenter = newPosition
                            }
                        },
                        onDragEnd = {
                            draggedCornerIndex?.let { index ->
                                val scaleX = viewSize.width.toFloat() / bitmap.width
                                val scaleY = viewSize.height.toFloat() / bitmap.height
                                val scale = min(scaleX, scaleY)
                                val offsetX = (viewSize.width - bitmap.width * scale) / 2
                                val offsetY = (viewSize.height - bitmap.height * scale) / 2

                                val finalScreenPos = transformedPoints[index]
                                val bitmapX = (finalScreenPos.x - offsetX) / scale
                                val bitmapY = (finalScreenPos.y - offsetY) / scale

                                val newPoints = points.toMutableList()
                                newPoints[index] = Offset(bitmapX, bitmapY)
                                onPointsChanged(newPoints)
                            }
                            draggedCornerIndex = null
                            magnifierCenter = null
                        },
                        onDragCancel = {
                            draggedCornerIndex = null
                            magnifierCenter = null
                        }
                    )
                }
        ) {
            val scaleX = size.width / bitmap.width
            val scaleY = size.height / bitmap.height
            val scale = min(scaleX, scaleY)
            val offsetX = (size.width - bitmap.width * scale) / 2
            val offsetY = (size.height - bitmap.height * scale) / 2

            drawImage(
                image = bitmap,
                dstSize = IntSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
                dstOffset = IntOffset(offsetX.toInt(), offsetY.toInt())
            )

            if (transformedPoints.isNotEmpty()) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(transformedPoints[0].x, transformedPoints[0].y)
                    lineTo(transformedPoints[1].x, transformedPoints[1].y)
                    lineTo(transformedPoints[2].x, transformedPoints[2].y)
                    lineTo(transformedPoints[3].x, transformedPoints[3].y)
                    close()
                }
                drawPath(path, color = Color(0f, 1f, 0.4f), style = Stroke(width = 5f))
                transformedPoints.forEachIndexed { index, point ->
                    val color = if (index == draggedCornerIndex) Color.Yellow else Color.White
                    drawCircle(color, radius = 30f, center = point)
                    drawCircle(Color(0f, 1f, 0.4f), radius = 30f, center = point, style = Stroke(width = 8f))
                }
            }
        }

        magnifierCenter?.let { center ->
            draggedCornerIndex?.let {
                val magnifierSize = 150.dp
                val magnifierSizePx = with(LocalDensity.current) { magnifierSize.toPx() }
                val zoomFactor = 4.0f

                val viewCenterX = viewSize.width / 2f
                val padding = with(LocalDensity.current) { 16.dp.toPx() }

                val magnifierX = if (center.x < viewCenterX) viewSize.width - magnifierSizePx - padding else padding
                val magnifierY = (viewSize.height / 2f) - (magnifierSizePx / 2)
                val magnifierOffset = Offset(magnifierX, magnifierY)

                val scaleX = viewSize.width.toFloat() / bitmap.width
                val scaleY = viewSize.height.toFloat() / bitmap.height
                val scale = min(scaleX, scaleY)
                val offsetX = (viewSize.width - bitmap.width * scale) / 2
                val offsetY = (viewSize.height - bitmap.height * scale) / 2
                val bitmapX = (center.x - offsetX) / scale
                val bitmapY = (center.y - offsetY) / scale

                val srcWidth = magnifierSizePx / zoomFactor
                val srcHeight = magnifierSizePx / zoomFactor
                val srcLeft = (bitmapX - srcWidth / 2).coerceIn(0f, bitmap.width - srcWidth)
                val srcTop = (bitmapY - srcHeight / 2).coerceIn(0f, bitmap.height - srcHeight)

                Canvas(
                    modifier = Modifier
                        .offset { IntOffset(magnifierOffset.x.toInt(), magnifierOffset.y.toInt()) }
                        .size(magnifierSize)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                ) {
                    clipRect {
                        drawImage(
                            image = bitmap,
                            srcOffset = IntOffset(srcLeft.toInt(), srcTop.toInt()),
                            srcSize = IntSize(srcWidth.toInt(), srcHeight.toInt()),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                    val crosshairCenter = Offset(size.width / 2, size.height / 2)
                    drawLine(Color.Red.copy(alpha = 0.8f), start = Offset(crosshairCenter.x, 0f), end = Offset(crosshairCenter.x, size.height), strokeWidth = 3f)
                    drawLine(Color.Red.copy(alpha = 0.8f), start = Offset(0f, crosshairCenter.y), end = Offset(size.width, crosshairCenter.y), strokeWidth = 3f)
                }
            }
        }
    }
}


@Composable
fun AdjustableCropView(
    bitmap: Bitmap,
    points: List<Offset>,
    onPointsChanged: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier
) {
    AdjustableCropView(
        bitmap = bitmap.asImageBitmap(),
        points = points,
        onPointsChanged = onPointsChanged,
        modifier = modifier
    )
}