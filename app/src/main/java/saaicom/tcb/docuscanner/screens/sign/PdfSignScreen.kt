package saaicom.tcb.docuscanner.screens.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.FileActions
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.SignatureRepository
import saaicom.tcb.docuscanner.utils.FileUtils
import saaicom.tcb.docuscanner.utils.calculateInSampleSize // Ensure BitmapUtils.kt still exists, or move this function
import java.io.File
import kotlin.math.min

/**
 * A data class to hold the state of a placed signature on the document.
 */
private data class PlacedSignature(
    val id: Long = System.currentTimeMillis(),
    val bitmap: ImageBitmap,
    val pageIndex: Int,
    var offset: Offset, // Offset from the (0,0) of the PDF page
    var scale: Float = 1f,
    var rotation: Float = 0f
)

/**
 * A screen to view a PDF and add signatures using Android's built-in PdfRenderer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignScreen(
    navController: NavController,
    pdfUri: Uri
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }

    // --- PDF Renderer State ---
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var parcelFileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var currentPage by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var pdfPageSize by remember { mutableStateOf(IntSize(0, 0)) }

    // --- Signature Sheet State ---
    val sheetState = rememberModalBottomSheetState()
    var showSignatureSheet by remember { mutableStateOf(false) }
    var savedSignatures by remember { mutableStateOf<List<File>>(emptyList()) }

    // --- Transformation State ---
    val allPlacedSignatures = remember { mutableStateListOf<PlacedSignature>() }
    val currentPageSignatures by remember(currentPage, allPlacedSignatures.size) {
        derivedStateOf {
            allPlacedSignatures.filter { it.pageIndex == currentPage }
        }
    }

    var pageScale by remember { mutableStateOf(1f) }
    var pageOffset by remember { mutableStateOf(Offset.Zero) }
    var composableSize by remember { mutableStateOf(IntSize.Zero) }

    // --- Gesture Handling State ---
    var selectedSignatureId by remember { mutableStateOf<Long?>(null) }

    var showSaveDialog by remember { mutableStateOf(false) }

    // --- START: Helper Functions ---

    fun getPageTransformations(pdfPageSize: IntSize, composableSize: IntSize): Triple<Float, Float, Float> {
        if (pdfPageSize.width == 0 || composableSize.width == 0) {
            return Triple(1f, 0f, 0f)
        }
        val scaleToFit = min(
            composableSize.width.toFloat() / pdfPageSize.width,
            composableSize.height.toFloat() / pdfPageSize.height
        )
        val scaledImageWidth = pdfPageSize.width * scaleToFit
        val scaledImageHeight = pdfPageSize.height * scaleToFit
        val offsetX = (composableSize.width - scaledImageWidth) / 2f
        val offsetY = (composableSize.height - scaledImageHeight) / 2f
        return Triple(scaleToFit, offsetX, offsetY)
    }

    suspend fun flattenBitmap(
        baseBitmap: ImageBitmap?,
        signatures: List<PlacedSignature>,
        pdfPageSize: IntSize,
        composableSize: IntSize,
        pageScale: Float,
        pageOffset: Offset
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (baseBitmap == null || composableSize.width == 0 || composableSize.height == 0) {
            Log.e("Flatten", "Base bitmap or composable size is null/zero.")
            return@withContext null
        }

        val finalBitmap = Bitmap.createBitmap(pdfPageSize.width, pdfPageSize.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalBitmap)

        // 1. Draw the PDF page as the background
        canvas.drawBitmap(baseBitmap.asAndroidBitmap(), 0f, 0f, null)

        // 2. Calculate coordinate mapping
        val (scaleToFit, offsetX, offsetY) = getPageTransformations(pdfPageSize, composableSize)

        signatures.forEach { sig ->
            try {
                val sigBitmap = sig.bitmap.asAndroidBitmap()
                val sigWidth = sigBitmap.width.toFloat()
                val sigHeight = sigBitmap.height.toFloat()

                val matrix = Matrix()

                // 1. Start with the signature's own scale/rotation
                matrix.postScale(sig.scale, sig.scale, sigWidth / 2, sigHeight / 2)
                matrix.postRotate(sig.rotation, sigWidth / 2, sigHeight / 2)

                // 2. Map the signature's composable-space offset back to bitmap-space
                val sigCenterInComposable = Offset(
                    composableSize.width / 2f + sig.offset.x,
                    composableSize.height / 2f + sig.offset.y
                )

                val sigCenterOnScreen = (sigCenterInComposable * pageScale) + pageOffset
                val sigCenterOnScaledImage = sigCenterOnScreen - Offset(offsetX * pageScale, offsetY * pageScale)

                val bitmapX = sigCenterOnScaledImage.x / (scaleToFit * pageScale)
                val bitmapY = sigCenterOnScaledImage.y / (scaleToFit * pageScale)

                val scaledSigWidth = sigWidth * sig.scale
                val scaledSigHeight = sigHeight * sig.scale
                matrix.postTranslate(bitmapX - (scaledSigWidth / 2f), bitmapY - (scaledSigHeight / 2f))

                // 3. Draw the signature onto the final bitmap
                canvas.drawBitmap(sigBitmap, matrix, null)

            } catch (e: Exception) {
                Log.e("Flatten", "Error drawing signature to bitmap", e)
            }
        }

        return@withContext finalBitmap
    }


    suspend fun loadFullSignature(filePath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            BitmapFactory.decodeFile(filePath)?.asImageBitmap()
        } catch (e: Exception) {
            Log.e("PdfSignScreen", "Failed to load full signature bitmap", e)
            null
        }
    }

    @Composable
    fun SignatureSelectItem(file: File, onClick: () -> Unit) {
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
                    Log.e("SignatureSelectItem", "Failed to load bitmap", e)
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = onClick
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .height(50.dp)
                        .width(100.dp)
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
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
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
    fun SavePdfDialog(
        onDismiss: () -> Unit,
        onSave: (String) -> Unit
    ) {
        var text by remember { mutableStateOf("Signed-DocuScan-${System.currentTimeMillis()}.pdf") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Save Signed PDF") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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

    // --- END OF HELPER FUNCTIONS ---


    // --- Load PDF ---
    LaunchedEffect(pdfUri) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    parcelFileDescriptor = pfd
                    pdfRenderer = renderer
                    pageCount = renderer.pageCount
                    currentPage = 0
                }
            } catch (e: Exception) {
                Log.e("PdfSignScreen", "Error opening PDF", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error opening PDF", Toast.LENGTH_LONG).show()
                    navController.popBackStack()
                }
            }
        }
    }

    // --- Render Current Page ---
    LaunchedEffect(pdfRenderer, currentPage) {
        pdfRenderer?.let {
            isLoading = true
            pageScale = 1f
            pageOffset = Offset.Zero

            withContext(Dispatchers.IO) {
                val page = it.openPage(currentPage)
                pdfPageSize = IntSize(page.width, page.height)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                currentPageBitmap = bitmap.asImageBitmap()
                page.close()
            }
            isLoading = false
        }
    }

    // --- Clean up ---
    DisposableEffect(Unit) {
        onDispose {
            Log.d("PdfSignScreen", "Disposing PDF Renderer")
            currentPageBitmap = null
            try {
                pdfRenderer?.close()
                parcelFileDescriptor?.close()
            } catch (e: Exception) {
                Log.e("PdfSignScreen", "Error closing PDF renderer", e)
            }
        }
    }

    // --- Load saved signatures when the sheet is requested ---
    LaunchedEffect(showSignatureSheet) {
        if (showSignatureSheet) {
            savedSignatures = SignatureRepository.getSavedSignatures(context)
        }
    }

    if (showSaveDialog) {
        SavePdfDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { fileName ->
                showSaveDialog = false
                isLoading = true
                scope.launch {
                    // --- *** FIXED SAVE LOGIC *** ---
                    // Instead of List<Bitmap>, we now collect List<Uri> (temp files)
                    val tempUris = mutableListOf<Uri>()

                    withContext(Dispatchers.IO) {
                        if (pdfRenderer == null) {
                            Log.e("PdfSignScreen", "Save failed: PdfRenderer is null")
                            return@withContext
                        }
                        for (i in 0 until pageCount) {
                            // 1. Render original page
                            val page = pdfRenderer!!.openPage(i)
                            val baseBitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            baseBitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(baseBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            // 2. Get signatures for this page
                            val sigsForThisPage = allPlacedSignatures.filter { it.pageIndex == i }

                            // 3. Flatten
                            val finalBitmap = flattenBitmap(
                                baseBitmap = baseBitmap.asImageBitmap(),
                                signatures = sigsForThisPage,
                                pdfPageSize = pdfPageSize,
                                composableSize = composableSize,
                                pageScale = 1f,
                                pageOffset = Offset.Zero
                            )

                            // 4. Save to Temp File immediately
                            val bitmapToSave = finalBitmap ?: baseBitmap
                            val uri = FileUtils.saveBitmapToTempFile(context, bitmapToSave)
                            tempUris.add(uri)

                            // 5. Recycle immediately to free memory
                            if (finalBitmap != null) finalBitmap.recycle()
                            baseBitmap.recycle()
                        }
                    }

                    // 4. Pass URIs to FileActions
                    FileActions.saveBitmapsAsPdf(
                        uris = tempUris, // <--- PASSING URIS NOW
                        fileName = fileName,
                        context = context,
                        onComplete = { success ->
                            isLoading = false
                            if (success) {
                                Toast.makeText(context, "Signed PDF saved!", Toast.LENGTH_LONG).show()
                                navController.navigate(Routes.FILES) {
                                    popUpTo(Routes.HOME)
                                    launchSingleTop = true
                                }
                            } else {
                                Toast.makeText(context, "Failed to save PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign Document") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedSignatureId != null) {
                        IconButton(onClick = {
                            allPlacedSignatures.removeAll { it.id == selectedSignatureId }
                            selectedSignatureId = null
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Signature", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(
                        onClick = { showSaveDialog = true },
                        // Enable save if we have loaded the PDF (don't require a signature to save)
                        enabled = pageCount > 0
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save Document")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                actions = {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous Page")
                    }
                    if (pageCount > 0) {
                        Text("Page ${currentPage + 1} of $pageCount")
                    }
                    IconButton(
                        onClick = { if (currentPage < pageCount - 1) currentPage++ },
                        enabled = currentPage < pageCount - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next Page")
                    }
                },
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { showSignatureSheet = true },
                        icon = { Icon(Icons.Default.Add, contentDescription = "Add Signature") },
                        text = { Text("Add Signature") },
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Gray)
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else if (currentPageBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { composableSize = it }
                        .aspectRatio(currentPageBitmap!!.width.toFloat() / currentPageBitmap!!.height.toFloat())
                ) {
                    Image(
                        bitmap = currentPageBitmap!!,
                        contentDescription = "PDF Page ${currentPage + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = pageScale,
                                scaleY = pageScale,
                                translationX = pageOffset.x,
                                translationY = pageOffset.y
                            ),
                        contentScale = ContentScale.Fit
                    )

                    Canvas(modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, pan, zoom, rotation ->
                                val sigIndex = selectedSignatureId?.let { id ->
                                    allPlacedSignatures.indexOfFirst { it.id == id }
                                }

                                if (sigIndex != null && sigIndex != -1) {
                                    val sig = allPlacedSignatures[sigIndex]
                                    val (scaleToFit, _, _) = getPageTransformations(pdfPageSize, composableSize)
                                    val pageTotalScale = scaleToFit * pageScale

                                    val bitmapPan = pan / pageTotalScale

                                    allPlacedSignatures[sigIndex] = sig.copy(
                                        scale = sig.scale * zoom,
                                        rotation = sig.rotation + rotation,
                                        offset = sig.offset + bitmapPan
                                    )
                                } else {
                                    val oldScale = pageScale
                                    val newScale = (pageScale * zoom).coerceIn(1f, 5f)

                                    val (scaleToFit, offsetX, offsetY) = getPageTransformations(pdfPageSize, composableSize)
                                    val scaledWidth = pdfPageSize.width * scaleToFit * newScale
                                    val scaledHeight = pdfPageSize.height * scaleToFit * newScale

                                    val maxX = (scaledWidth - composableSize.width).coerceAtLeast(0f) / 2f
                                    val maxY = (scaledHeight - composableSize.height).coerceAtLeast(0f) / 2f

                                    val newOffset = (pageOffset + centroid / oldScale) - (centroid / newScale) + (pan / oldScale)

                                    pageOffset = Offset(
                                        x = newOffset.x.coerceIn(-maxX, maxX),
                                        y = newOffset.y.coerceIn(-maxY, maxY)
                                    )
                                    pageScale = newScale
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { tapOffset ->
                                    val (scaleToFit, offsetX, offsetY) = getPageTransformations(pdfPageSize, composableSize)

                                    val tapOnScreen = tapOffset
                                    val tapOnPage = (tapOnScreen - pageOffset - Offset(offsetX, offsetY)) / (pageScale * scaleToFit)

                                    val tappedSig = currentPageSignatures.findLast { sig ->
                                        val sigRect = Rect(
                                            sig.offset.x,
                                            sig.offset.y,
                                            sig.offset.x + sig.bitmap.width * sig.scale,
                                            sig.offset.y + sig.bitmap.height * sig.scale
                                        )
                                        sigRect.contains(tapOnPage)
                                    }

                                    selectedSignatureId = tappedSig?.id
                                }
                            )
                        }
                    ) {
                        withTransform({
                            translate(left = pageOffset.x, top = pageOffset.y)
                            scale(scaleX = pageScale, scaleY = pageScale, pivot = Offset.Zero)

                            val (scaleToFit, offsetX, offsetY) = getPageTransformations(pdfPageSize, size.toIntSize())

                            translate(left = offsetX, top = offsetY)
                            scale(scaleX = scaleToFit, scaleY = scaleToFit, pivot = Offset.Zero)

                        }) {
                            currentPageSignatures.forEach { sig ->
                                val sigWidth = sig.bitmap.width.toFloat()
                                val sigHeight = sig.bitmap.height.toFloat()

                                withTransform({
                                    translate(left = sig.offset.x, top = sig.offset.y)
                                    rotate(sig.rotation, pivot = Offset(sigWidth / 2, sigHeight / 2))
                                    scale(scaleX = sig.scale, scaleY = sig.scale, pivot = Offset(sigWidth / 2, sigHeight / 2))
                                }) {
                                    drawImage(sig.bitmap)

                                    if (sig.id == selectedSignatureId) {
                                        val borderSize = Size(sigWidth, sigHeight)
                                        val (scaleToFit, _, _) = getPageTransformations(pdfPageSize, composableSize)
                                        drawRect(
                                            color = Color.Blue,
                                            topLeft = Offset.Zero,
                                            size = borderSize,
                                            style = Stroke(width = 8f / (sig.scale * pageScale * scaleToFit))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text("Failed to load PDF.")
            }
        }

        if (showSignatureSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSignatureSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "Select a Signature",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    if (savedSignatures.isEmpty()) {
                        Text(
                            "No signatures found. Please add a signature from the 'Sign' tab.",
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedSignatures, key = { it.absolutePath }) { file ->
                                SignatureSelectItem(
                                    file = file,
                                    onClick = {
                                        scope.launch {
                                            val bitmap = loadFullSignature(file.absolutePath)
                                            if (bitmap != null) {
                                                val (scaleToFit, offsetX, offsetY) = getPageTransformations(pdfPageSize, composableSize)
                                                val composableCenter = Offset(composableSize.width / 2f, composableSize.height / 2f)
                                                val centerOnPage = (composableCenter - pageOffset - Offset(offsetX, offsetY)) / (pageScale * scaleToFit)

                                                val newSig = PlacedSignature(
                                                    bitmap = bitmap,
                                                    pageIndex = currentPage,
                                                    offset = Offset(
                                                        centerOnPage.x - (bitmap.width / 2f),
                                                        centerOnPage.y - (bitmap.height / 2f)
                                                    ),
                                                    scale = 1f / pageScale
                                                )

                                                allPlacedSignatures.add(newSig)
                                                selectedSignatureId = newSig.id
                                                sheetState.hide()
                                            } else {
                                                Toast.makeText(context, "Failed to load signature", Toast.LENGTH_SHORT).show()
                                            }
                                        }.invokeOnCompletion {
                                            if (!sheetState.isVisible) {
                                                showSignatureSheet = false
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}