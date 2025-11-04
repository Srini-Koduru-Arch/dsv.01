package saaicom.tcb.docuscanner.screens.edit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint // Import Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface // Import Typeface
import android.graphics.pdf.PdfDocument
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.NavigateBefore // Import icon for "Back"
import androidx.compose.material.icons.filled.NavigateNext // Import icon for "Next"
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign // Import TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import saaicom.tcb.docuscanner.DocumentRepository // *** FIX HERE: Import the repository ***
import saaicom.tcb.docuscanner.DriveRepository // *** FIX HERE: Added missing import ***
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.Scanner
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.math.min

// Define save destinations
private sealed class SaveDestination {
    object Local : SaveDestination()
    object Cloud : SaveDestination()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannedDocumentEditScreen(
    navController: NavController,
    imageUri: Uri
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var croppedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cornerPoints by remember { mutableStateOf<List<Offset>?>(null) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    var scannedPageBitmaps by remember { mutableStateOf<List<Bitmap>>(DocumentRepository.getAllPages()) }
    var currentPageIndex by remember { mutableStateOf(DocumentRepository.getPageCount() - 1) }

    val driveService by DriveRepository.driveService.collectAsState()
    val isCloudSaveEnabled = driveService != null

    LaunchedEffect(imageUri) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
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

                val scanner = Scanner()
                val edgeData = scanner.detectEdges(bitmap)
                val detectedCorners = edgeData.corners?.toArray()

                val finalData = scanner.applyPerspectiveTransform(edgeData)
                val autoCroppedBitmap = finalData.scanned ?: finalData.original

                DocumentRepository.addPage(autoCroppedBitmap)
                val allPages = DocumentRepository.getAllPages()
                val newPageIndex = allPages.size - 1

                originalBitmap = bitmap
                croppedBitmap = null // Start in edit mode
                cornerPoints = if (detectedCorners != null && detectedCorners.size == 4) {
                    detectedCorners.map { Offset(it.x.toFloat(), it.y.toFloat()) }
                } else {
                    listOf(
                        Offset(0f, 0f),
                        Offset(bitmap.width.toFloat(), 0f),
                        Offset(bitmap.width.toFloat(), bitmap.height.toFloat()),
                        Offset(0f, bitmap.height.toFloat())
                    )
                }

                scannedPageBitmaps = allPages
                currentPageIndex = newPageIndex

            } catch (e: Exception) {
                Log.e("EditScreen", "Failed to load or process image.", e)
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                    navController.popBackStack()
                }
            }
        }
        isLoading = false
    }

    if (showPdfDialog) {
        SavePdfDialog(
            isCloudSaveEnabled = isCloudSaveEnabled,
            onDismiss = { showPdfDialog = false },
            onSave = { fileName, destination -> // Capture destination here
                showPdfDialog = false
                isLoading = true
                scope.launch(Dispatchers.IO) {
                    val jobName = when (destination) {
                        is SaveDestination.Local -> "Local PDF"
                        is SaveDestination.Cloud -> "Cloud PDF"
                    }
                    val onComplete: (Boolean) -> Unit = { success ->
                        scope.launch(Dispatchers.Main) {
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, "$jobName saved successfully", Toast.LENGTH_LONG).show()
                                DocumentRepository.clear()
                                // *** FIX HERE: Conditional Navigation ***
                                val targetRoute = when (destination) {
                                    is SaveDestination.Local -> Routes.FILES
                                    is SaveDestination.Cloud -> Routes.CLOUD_FILES
                                }
                                navController.navigate(targetRoute) {
                                    popUpTo(Routes.HOME) // Clear back stack to Home
                                    launchSingleTop = true // Avoid multiple instances
                                }
                            } else {
                                Toast.makeText(context, "Failed to save $jobName", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    when (destination) {
                        is SaveDestination.Local -> {
                            saveBitmapsAsPdf_Local(
                                bitmaps = scannedPageBitmaps,
                                fileName = fileName,
                                context = context,
                                onComplete = onComplete
                            )
                        }
                        is SaveDestination.Cloud -> {
                            saveBitmapsAsPdf_Cloud(
                                driveService = driveService,
                                bitmaps = scannedPageBitmaps,
                                fileName = fileName,
                                onComplete = onComplete
                            )
                        }
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (scannedPageBitmaps.size > 1) {
                            "Page ${currentPageIndex + 1} of ${scannedPageBitmaps.size}"
                        } else if (croppedBitmap == null) { // Show "Adjust Edges" only in crop mode
                            "Adjust Edges"
                        } else {
                            "Document Ready" // Show when viewing cropped single page
                        },
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                if (croppedBitmap == null && originalBitmap != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = {
                            val ob = originalBitmap
                            val cp = cornerPoints
                            if (ob != null && cp != null) {
                                isLoading = true
                                scope.launch(Dispatchers.IO) {
                                    val scanner = Scanner()
                                    val ocvPoints = cp.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
                                    val scannedData = Scanner.ScannedData(
                                        original = ob,
                                        corners = org.opencv.core.MatOfPoint2f(*ocvPoints)
                                    )
                                    val finalData = scanner.applyPerspectiveTransform(scannedData)

                                    val newBitmap = finalData.scanned ?: finalData.original
                                    DocumentRepository.replacePage(currentPageIndex, newBitmap)
                                    // Update local state immediately for smoother transition
                                    scannedPageBitmaps = DocumentRepository.getAllPages()
                                    croppedBitmap = newBitmap // Switch view to cropped
                                    isLoading = false
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
                        IconButton(onClick = {
                            navController.navigate(Routes.CAMERA)
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        IconButton(
                            onClick = {
                                if (currentPageIndex > 0) {
                                    currentPageIndex--
                                    croppedBitmap = DocumentRepository.getPage(currentPageIndex)
                                }
                            },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = "Previous Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }

                        IconButton(
                            onClick = {
                                if (currentPageIndex < scannedPageBitmaps.size - 1) {
                                    currentPageIndex++
                                    croppedBitmap = DocumentRepository.getPage(currentPageIndex)
                                }
                            },
                            enabled = currentPageIndex < scannedPageBitmaps.size - 1
                        ) {
                            Icon(Icons.Default.NavigateNext, contentDescription = "Next Page", tint = MaterialTheme.colorScheme.onPrimary)
                        }


                        IconButton(onClick = {
                            if (scannedPageBitmaps.isNotEmpty()) {
                                showPdfDialog = true
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
                        .clickable {
                            // Allow re-cropping by going back to Adjust view
                            croppedBitmap = null
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavePdfDialog(
    isCloudSaveEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, SaveDestination) -> Unit
) {
    var text by remember { mutableStateOf("DocuScan-${System.currentTimeMillis()}.pdf") } // Updated default name
    var selectedDestination by remember { mutableStateOf<SaveDestination>(SaveDestination.Local) }

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
                Spacer(modifier = Modifier.height(16.dp))
                Text("Save Location:", style = MaterialTheme.typography.bodyMedium)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (selectedDestination is SaveDestination.Local),
                            onClick = { selectedDestination = SaveDestination.Local },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedDestination is SaveDestination.Local),
                        onClick = null
                    )
                    Text(
                        text = "Local Downloads Folder",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = (selectedDestination is SaveDestination.Cloud),
                            onClick = { selectedDestination = SaveDestination.Cloud },
                            role = Role.RadioButton,
                            enabled = isCloudSaveEnabled
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedDestination is SaveDestination.Cloud),
                        onClick = null,
                        enabled = isCloudSaveEnabled
                    )
                    Text(
                        text = "Google Drive / DocuScanner",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp),
                        color = if (isCloudSaveEnabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                    )
                }
                if (!isCloudSaveEnabled) {
                    Text(
                        text = "Sign in from the 'Cloud' tab to enable",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 56.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (text.isNotBlank()) onSave(text, selectedDestination) },
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

private fun saveBitmapsAsPdf_Local(
    bitmaps: List<Bitmap>,
    fileName: String,
    context: android.content.Context,
    onComplete: (Boolean) -> Unit
) {
    val pdfDocument = PdfDocument()

    val PAGE_WIDTH = 595
    val PAGE_HEIGHT = 842
    val PAGE_MARGIN = 36f

    val footerPaint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        textSize = 10f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        isAntiAlias = true
    }
    val footerText = "Created by using Saaicom's DocuScanner App"
    val footerMargin = 20f

    try {
        bitmaps.forEachIndexed { index, bitmap ->
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val contentWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
            val contentHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN
            val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val contentAspectRatio = contentWidth / contentHeight
            val destRect = RectF()
            if (bitmapAspectRatio > contentAspectRatio) {
                val scaledHeight = contentWidth / bitmapAspectRatio
                val top = PAGE_MARGIN + (contentHeight - scaledHeight) / 2
                destRect.set(PAGE_MARGIN, top, PAGE_MARGIN + contentWidth, top + scaledHeight)
            } else {
                val scaledWidth = contentHeight * bitmapAspectRatio
                val left = PAGE_MARGIN + (contentWidth - scaledWidth) / 2
                destRect.set(left, PAGE_MARGIN, left + scaledWidth, PAGE_MARGIN + contentHeight)
            }
            canvas.drawBitmap(bitmap, null, destRect, null)
            val xPos = (PAGE_WIDTH / 2).toFloat()
            val yPos = PAGE_HEIGHT - footerMargin
            canvas.drawText(footerText, xPos, yPos, footerPaint)
            pdfDocument.finishPage(page)
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DocuScanner")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            onComplete(true)
        } else {
            onComplete(false)
        }
    } catch (e: IOException) {
        Log.e("SavePdf", "Error writing PDF", e)
        onComplete(false)
    } finally {
        pdfDocument.close()
    }
}

private suspend fun saveBitmapsAsPdf_Cloud(
    driveService: Drive?,
    bitmaps: List<Bitmap>,
    fileName: String,
    onComplete: (Boolean) -> Unit
) {
    if (driveService == null) {
        onComplete(false)
        return
    }

    val pdfDocument = PdfDocument()
    val outputStream = ByteArrayOutputStream()

    val PAGE_WIDTH = 595
    val PAGE_HEIGHT = 842
    val PAGE_MARGIN = 36f

    val footerPaint = Paint().apply {
        color = android.graphics.Color.DKGRAY
        textSize = 10f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        isAntiAlias = true
    }
    val footerText = "Created by using Saaicom's DocuScanner App"
    val footerMargin = 20f

    try {
        val folderId = DriveRepository.findOrCreateDocuScannerFolder(driveService)
        if (folderId == null) {
            Log.e("SavePdfCloud", "Could not find or create DocuScanner folder")
            onComplete(false)
            return
        }

        bitmaps.forEachIndexed { index, bitmap ->
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas
            val contentWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
            val contentHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN
            val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val contentAspectRatio = contentWidth / contentHeight
            val destRect = RectF()
            if (bitmapAspectRatio > contentAspectRatio) {
                val scaledHeight = contentWidth / bitmapAspectRatio
                val top = PAGE_MARGIN + (contentHeight - scaledHeight) / 2
                destRect.set(PAGE_MARGIN, top, PAGE_MARGIN + contentWidth, top + scaledHeight)
            } else {
                val scaledWidth = contentHeight * bitmapAspectRatio
                val left = PAGE_MARGIN + (contentWidth - scaledWidth) / 2
                destRect.set(left, PAGE_MARGIN, left + scaledWidth, PAGE_MARGIN + contentHeight)
            }
            canvas.drawBitmap(bitmap, null, destRect, null)
            val xPos = (PAGE_WIDTH / 2).toFloat()
            val yPos = PAGE_HEIGHT - footerMargin
            canvas.drawText(footerText, xPos, yPos, footerPaint)
            pdfDocument.finishPage(page)
        }

        pdfDocument.writeTo(outputStream)

        val mediaContent = ByteArrayContent("application/pdf", outputStream.toByteArray())
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf"
            parents = listOf(folderId)
        }

        driveService.files().create(fileMetadata, mediaContent)
            .setFields("id")
            .execute()

        onComplete(true)

    } catch (e: Exception) {
        Log.e("SavePdfCloud", "Error saving PDF to Drive", e)
        onComplete(false)
    } finally {
        pdfDocument.close()
        outputStream.close()
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

