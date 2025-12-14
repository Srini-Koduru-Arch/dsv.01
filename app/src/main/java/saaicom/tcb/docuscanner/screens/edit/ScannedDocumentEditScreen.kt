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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import saaicom.tcb.docuscanner.DocumentRepository
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.Scanner
import saaicom.tcb.docuscanner.utils.FileUtils
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
    val scanner = remember { Scanner() }

    // --- State ---
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var baseCroppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cornerPoints by remember { mutableStateOf<List<Offset>?>(null) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var currentFilter by remember { mutableStateOf(Scanner.FilterType.Original) }

    // Repository / Pagination State
    var scannedPageUris by remember { mutableStateOf<List<Uri>>(DocumentRepository.getAllPages()) }
    var currentPageIndex by remember { mutableStateOf(DocumentRepository.getPageCount() - 1) }
    var pageHasBeenAdded by remember { mutableStateOf(false) }

    // Helper: Load Bitmap
    fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
            } else {
                @Suppress("DEPRECATION")
                val legacy = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                legacy.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Helper: Apply Filter
    fun applyFilterToDisplay(type: Scanner.FilterType) {
        if (baseCroppedBitmap == null) return
        isLoading = true
        currentFilter = type
        scope.launch(Dispatchers.Default) {
            val filtered = scanner.applyFilter(baseCroppedBitmap!!, type)
            withContext(Dispatchers.Main) {
                displayedBitmap = filtered
                isLoading = false
            }
        }
    }

    // Helper: Save Changes
    suspend fun saveCurrentChanges() {
        if (displayedBitmap == null) return
        withContext(Dispatchers.IO) {
            // Only save if we have a valid page index
            if (currentPageIndex >= 0 && currentPageIndex < DocumentRepository.getPageCount()) {
                val newUri = FileUtils.saveBitmapToTempFile(context, displayedBitmap!!)
                DocumentRepository.replacePage(currentPageIndex, newUri)
                scannedPageUris = DocumentRepository.getAllPages()
            } else if (!pageHasBeenAdded) {
                // Edge case: User captured, auto-cropped, but hit "Save" before page was formally added to repo list?
                // We handle this below by ensuring auto-crop adds the page.
            }
        }
    }

    // --- INITIAL LOAD & AUTO-CROP ---
    LaunchedEffect(imageUri, cornersJson) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val bitmap = loadBitmapFromUri(imageUri) ?: return@withContext

                val bitmapWidth = bitmap.width.toDouble()
                val bitmapHeight = bitmap.height.toDouble()
                val scaleX = bitmapWidth / ANALYSIS_WIDTH
                val scaleY = bitmapHeight / ANALYSIS_HEIGHT

                val detectedCorners: List<Offset> = if (cornersJson != null && cornersJson != "null") {
                    try {
                        cornersJson.split(",").map {
                            val parts = it.split(":")
                            Offset((parts[0].toFloat() * scaleX).toFloat(), (parts[1].toFloat() * scaleY).toFloat())
                        }
                    } catch (e: Exception) { null }
                } else { null } ?: listOf(
                    Offset(0f, 0f),
                    Offset(bitmapWidth.toFloat(), 0f),
                    Offset(bitmapWidth.toFloat(), bitmapHeight.toFloat()),
                    Offset(0f, bitmapHeight.toFloat())
                )

                originalBitmap = bitmap
                cornerPoints = detectedCorners

                // --- AUTO-CROP LOGIC ---
                // If we have 4 valid points, crop immediately
                if (detectedCorners.size == 4) {
                    val ocvPoints = detectedCorners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
                    val data = Scanner.ScannedData(bitmap, org.opencv.core.MatOfPoint2f(*ocvPoints))
                    val result = scanner.applyPerspectiveTransform(data)
                    val cropped = result.scanned ?: result.original

                    // Set State
                    baseCroppedBitmap = cropped
                    displayedBitmap = cropped // Default to Original filter

                    // SAVE TO REPO IMMEDIATELY (So it counts as Page 1)
                    val newUri = FileUtils.saveBitmapToTempFile(context, cropped)
                    if (!pageHasBeenAdded) {
                        DocumentRepository.addPage(newUri)
                        pageHasBeenAdded = true
                    } else {
                        DocumentRepository.addPage(newUri)
                    }

                    // Update Paging State
                    scannedPageUris = DocumentRepository.getAllPages()
                    currentPageIndex = scannedPageUris.size - 1
                } else {
                    // Fallback to manual crop if detection failed
                    baseCroppedBitmap = null
                    displayedBitmap = null
                }

            } catch (e: Exception) {
                Log.e("EditScreen", "Failed to load image.", e)
            }
        }
        isLoading = false
    }

    // --- PDF DIALOG ---
    if (showPdfDialog) {
        // Ensure this import is present at the top of your file:
        // import saaicom.tcb.docuscanner.ui.components.SaveToFolderDialog

        saaicom.tcb.docuscanner.ui.components.SaveToFolderDialog(
            initialName = "DocuScan-${System.currentTimeMillis()}",
            onDismiss = { showPdfDialog = false },
            onSave = { fileName, targetFolder ->
                showPdfDialog = false
                isLoading = true
                scope.launch {
                    saveCurrentChanges() // Ensure any last-second edits are saved to temp

                    // Call the UPDATED save function with the target folder
                    FileActions.saveBitmapsAsPdf(
                        uris = DocumentRepository.getAllPages(),
                        fileName = fileName,
                        context = context,
                        targetDirectory = targetFolder, // <--- PASSING THE FOLDER HERE
                        onComplete = { success ->
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, "Saved to ${targetFolder.name}!", Toast.LENGTH_LONG).show()
                                DocumentRepository.clear()
                                // Navigate to Files tab and clear back stack
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
                color = Color.White,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // LEFT: Navigation Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    scope.launch {
                                        saveCurrentChanges()
                                        isLoading = true
                                        currentPageIndex--
                                        val uri = DocumentRepository.getPage(currentPageIndex)
                                        val loaded = if (uri != null) withContext(Dispatchers.IO) { loadBitmapFromUri(uri) } else null
                                        baseCroppedBitmap = loaded
                                        displayedBitmap = loaded
                                        currentFilter = Scanner.FilterType.Original
                                        // Disable re-crop for previous pages as we don't store originals
                                        originalBitmap = null
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.NavigateBefore, "Prev")
                        }

                        val total = if (scannedPageUris.isEmpty()) 1 else scannedPageUris.size
                        Text(
                            text = "${currentPageIndex + 1} / $total",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = {
                                if (currentPageIndex < scannedPageUris.size - 1) {
                                    scope.launch {
                                        saveCurrentChanges()
                                        isLoading = true
                                        currentPageIndex++
                                        val uri = DocumentRepository.getPage(currentPageIndex)
                                        val loaded = if (uri != null) withContext(Dispatchers.IO) { loadBitmapFromUri(uri) } else null
                                        baseCroppedBitmap = loaded
                                        displayedBitmap = loaded
                                        currentFilter = Scanner.FilterType.Original
                                        originalBitmap = null
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = currentPageIndex < scannedPageUris.size - 1
                        ) {
                            Icon(Icons.Default.NavigateNext, "Next")
                        }
                    }

                    // RIGHT: Tools (Crop, Add, Save)
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        // *** NEW: Re-Crop Button ***
                        // Only visible if we have the original bitmap in memory
                        if (originalBitmap != null && displayedBitmap != null) {
                            IconButton(onClick = {
                                // Enter Manual Crop Mode
                                baseCroppedBitmap = null
                                displayedBitmap = null
                            }) {
                                Icon(Icons.Default.Crop, contentDescription = "Re-Crop", tint = Color.Black)
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Add Page
                        OutlinedButton(
                            onClick = {
                                scope.launch { saveCurrentChanges() }
                                navController.navigate(Routes.CAMERA)
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add", fontSize = 13.sp) // Shortened to "Add" to save space
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Save PDF
                        Button(
                            onClick = {
                                if (scannedPageUris.isNotEmpty()) {
                                    showPdfDialog = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
            ) {
                if (baseCroppedBitmap == null && originalBitmap != null) {
                    // CROP MODE
                    Button(
                        onClick = {
                            val ob = originalBitmap
                            val cp = cornerPoints
                            if (ob != null && cp != null) {
                                isLoading = true
                                scope.launch(Dispatchers.IO) {
                                    val ocvPoints = cp.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
                                    val data = Scanner.ScannedData(ob, org.opencv.core.MatOfPoint2f(*ocvPoints))
                                    val result = scanner.applyPerspectiveTransform(data)
                                    val cropped = result.scanned ?: result.original

                                    val newUri = FileUtils.saveBitmapToTempFile(context, cropped)

                                    // If we are re-cropping, we replace the current page
                                    if (currentPageIndex >= 0 && currentPageIndex < DocumentRepository.getPageCount()) {
                                        DocumentRepository.replacePage(currentPageIndex, newUri)
                                    } else {
                                        DocumentRepository.addPage(newUri)
                                        pageHasBeenAdded = true
                                    }

                                    val allPages = DocumentRepository.getAllPages()
                                    withContext(Dispatchers.Main) {
                                        baseCroppedBitmap = cropped
                                        displayedBitmap = cropped
                                        scannedPageUris = allPages
                                        // Keep current index (don't jump to end if we are just editing)
                                        if (currentPageIndex < 0) currentPageIndex = allPages.size - 1
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .height(50.dp)
                    ) {
                        Text("CONFIRM CROP")
                    }
                } else if (displayedBitmap != null) {
                    // FILTER MODE
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(Scanner.FilterType.values()) { filter ->
                            FilterItem(
                                filter = filter,
                                sourceBitmap = baseCroppedBitmap,
                                isSelected = filter == currentFilter,
                                onClick = { applyFilterToDisplay(filter) }
                            )
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
                .background(Color(0xFFF5F5F5)),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (displayedBitmap != null) {
                Image(
                    bitmap = displayedBitmap!!.asImageBitmap(),
                    contentDescription = "Edited Document",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentScale = ContentScale.Fit
                )
            } else if (originalBitmap != null) {
                AdjustableCropView(
                    bitmap = originalBitmap!!,
                    points = cornerPoints ?: emptyList(),
                    onPointsChanged = { cornerPoints = it }
                )
            }
        }
    }
}

// --- FilterItem & Dialogs (Keep existing) ---
@Composable
fun FilterItem(
    filter: Scanner.FilterType,
    sourceBitmap: Bitmap?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val scanner = remember { Scanner() }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filter, sourceBitmap) {
        if (sourceBitmap != null) {
            withContext(Dispatchers.Default) {
                val ratio = 100.0 / sourceBitmap.width
                val w = 100
                val h = (sourceBitmap.height * ratio).toInt()
                val small = Bitmap.createScaledBitmap(sourceBitmap, w, h, true)
                thumbnail = scanner.applyFilter(small, filter)
            }
        }
    }

    Box(
        modifier = Modifier
            .height(70.dp)
            .width(50.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                shape = RoundedCornerShape(6.dp)
            )
            .background(Color.White)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = filter.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

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
                val padding = with(LocalDensity.current) { 16.dp.toPx() }
                val magnifierX = if (center.x < viewSize.width / 2f) viewSize.width - magnifierSizePx - padding else padding
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