package saaicom.tcb.docuscanner.screens.sign

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import saaicom.tcb.docuscanner.Routes
import saaicom.tcb.docuscanner.SignatureRepository
import saaicom.tcb.docuscanner.utils.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

// --- DATA MODEL ---
data class PlacedSignature(
    val id: Long = System.currentTimeMillis(),
    val bitmap: ImageBitmap,
    val pageIndex: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSignScreen(
    navController: NavController,
    pdfUri: Uri
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- PDF State ---
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var parcelFileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    val pdfMutex = remember { Mutex() }

    // --- Signatures State ---
    val allSignatures = remember { mutableStateListOf<PlacedSignature>() }
    var selectedSignatureId by remember { mutableStateOf<Long?>(null) }

    // --- UI State ---
    val listState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState()
    var showSignatureSheet by remember { mutableStateOf(false) }
    var savedSignatures by remember { mutableStateOf<List<File>>(emptyList()) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // --- Zoom/Pan State ---
    val scaleAnim = remember { Animatable(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // --- 1. Load PDF ---
    LaunchedEffect(pdfUri) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                if (pfd != null) {
                    parcelFileDescriptor = pfd
                    pdfRenderer = PdfRenderer(pfd)
                    pageCount = pdfRenderer?.pageCount ?: 0
                }
                isLoading = false
            } catch (e: Exception) {
                Log.e("PdfSignScreen", "Error loading PDF", e)
            }
        }
    }

    // --- 2. Cleanup ---
    DisposableEffect(Unit) {
        onDispose {
            try { pdfRenderer?.close(); parcelFileDescriptor?.close() } catch (e: Exception) { }
        }
    }

    // --- 3. Load Saved Signatures ---
    LaunchedEffect(showSignatureSheet) {
        if (showSignatureSheet) savedSignatures = SignatureRepository.getSavedSignatures(context)
    }

    // --- 4. Save Logic ---
    fun saveDocument(fileName: String) {
        isSaving = true
        scope.launch {
            val tempUris = mutableListOf<Uri>()
            withContext(Dispatchers.IO) {
                val renderer = pdfRenderer ?: return@withContext
                for (i in 0 until pageCount) {
                    pdfMutex.withLock {
                        try {
                            val page = renderer.openPage(i)
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()

                            val canvas = Canvas(bitmap)
                            val pageSignatures = allSignatures.filter { it.pageIndex == i }
                            pageSignatures.forEach { sig ->
                                val sigBmp = sig.bitmap.asAndroidBitmap()
                                val matrix = Matrix()

                                val scaleX = sig.width / sigBmp.width
                                val scaleY = sig.height / sigBmp.height

                                matrix.postScale(scaleX, scaleY)
                                matrix.postRotate(sig.rotation, (sigBmp.width * scaleX) / 2, (sigBmp.height * scaleY) / 2)
                                matrix.postTranslate(sig.x, sig.y)

                                canvas.drawBitmap(sigBmp, matrix, null)
                            }
                            tempUris.add(FileUtils.saveBitmapToTempFile(context, bitmap))
                            bitmap.recycle()
                        } catch (e: Exception) { Log.e("Save", "Error saving page $i", e) }
                    }
                }
            }

            if (tempUris.isNotEmpty()) {
                saveCleanPdf(context, tempUris, fileName) { success ->
                    isSaving = false
                    if (success) {
                        Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
                        navController.navigate(Routes.FILES) { popUpTo(Routes.HOME); launchSingleTop = true }
                    } else {
                        Toast.makeText(context, "Save failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } else { isSaving = false }
        }
    }

    if (showSaveDialog) {
        SavePdfDialog(onDismiss = { showSaveDialog = false }, onSave = { name -> showSaveDialog = false; saveDocument(name) })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign Document") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if(selectedSignatureId != null) {
                        IconButton(onClick = { selectedSignatureId = null }) {
                            Icon(Icons.Default.Check, "Done")
                        }
                    }
                    IconButton(onClick = { showSaveDialog = true }, enabled = !isLoading && !isSaving) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(24.dp)) else Icon(Icons.Default.SaveAs, "Save")
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        },
        floatingActionButton = {
            if (selectedSignatureId == null) {
                ExtendedFloatingActionButton(
                    onClick = { showSignatureSheet = true },
                    icon = { Icon(Icons.Default.Add, "Add") },
                    text = { Text("Signature") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .background(Color.LightGray)
                .onSizeChanged { containerSize = it }
                // --- TAP GESTURES (Background) ---
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { selectedSignatureId = null },
                        onDoubleTap = {
                            scope.launch {
                                val current = scaleAnim.value
                                val target = if (current < 1.5f) 2f else if (current < 2.5f) 3f else 1f
                                scaleAnim.animateTo(target, animationSpec = tween(300))
                                if (target == 1f) offsetX = 0f
                            }
                        }
                    )
                }
                // --- ZOOM & PAN GESTURES (Background) ---
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        do {
                            val event = awaitPointerEvent()
                            val isConsumed = event.changes.any { it.isConsumed }

                            if (!isConsumed) {
                                if (event.changes.size >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    if (zoomChange != 1f) {
                                        val newScale = (scaleAnim.value * zoomChange).coerceIn(1f, 3f)
                                        scope.launch { scaleAnim.snapTo(newScale) }
                                        event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                    }
                                }

                                if (scaleAnim.value > 1f) {
                                    val panChange = event.calculatePan()
                                    val maxOffsetX = (containerSize.width * scaleAnim.value - containerSize.width) / 2f
                                    val newX = offsetX + panChange.x
                                    offsetX = newX.coerceIn(-maxOffsetX, maxOffsetX)
                                } else {
                                    offsetX = 0f
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scaleAnim.value
                            scaleY = scaleAnim.value
                            translationX = offsetX
                        },
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    userScrollEnabled = true
                ) {
                    items(count = pageCount) { pageIndex ->
                        PdfPageItem(
                            pageIndex = pageIndex,
                            pdfRenderer = pdfRenderer,
                            pdfMutex = pdfMutex,
                            signatures = allSignatures.filter { it.pageIndex == pageIndex },
                            selectedSignatureId = selectedSignatureId,
                            onSelect = { selectedSignatureId = it },
                            onUpdate = { updated ->
                                val idx = allSignatures.indexOfFirst { it.id == updated.id }
                                if (idx != -1) allSignatures[idx] = updated
                            },
                            onDelete = { idToDelete ->
                                allSignatures.removeAll { it.id == idToDelete }
                                selectedSignatureId = null
                            }
                        )
                    }
                }
            }
        }

        if (showSignatureSheet) {
            ModalBottomSheet(onDismissRequest = { showSignatureSheet = false }, sheetState = sheetState) {
                SignatureSelectionSheet(
                    signatures = savedSignatures,
                    onSelect = { file ->
                        scope.launch {
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                            if (bitmap != null) {
                                // Center Placement Logic
                                val layoutInfo = listState.layoutInfo
                                val viewportCenter = layoutInfo.viewportEndOffset / 2
                                val centerItem = layoutInfo.visibleItemsInfo.minByOrNull { item ->
                                    val itemCenter = item.offset + (item.size / 2)
                                    abs(itemCenter - viewportCenter)
                                }
                                val targetPage = centerItem?.index ?: 0

                                var pdfPageW = 595f
                                var pdfPageH = 842f
                                withContext(Dispatchers.IO) {
                                    pdfMutex.withLock {
                                        try {
                                            val page = pdfRenderer?.openPage(targetPage)
                                            if (page != null) {
                                                pdfPageW = page.width.toFloat()
                                                pdfPageH = page.height.toFloat()
                                                page.close()
                                            }
                                        } catch (e: Exception) { }
                                    }
                                }

                                val screenPageW = centerItem?.size?.toFloat() ?: 1080f
                                val scaleFactor = screenPageW / pdfPageW
                                val visualCenterYOnPage = (layoutInfo.viewportSize.height / 2) - (centerItem?.offset ?: 0)
                                val pdfCenterY = visualCenterYOnPage / scaleFactor

                                val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
                                val width = 200f
                                val height = width * aspectRatio

                                val startX = (pdfPageW - width) / 2
                                val startY = pdfCenterY - (height / 2)

                                val newSig = PlacedSignature(
                                    bitmap = bitmap,
                                    pageIndex = targetPage,
                                    x = startX, y = startY,
                                    width = width, height = height
                                )
                                allSignatures.add(newSig)
                                selectedSignatureId = newSig.id
                                showSignatureSheet = false
                            }
                        }
                    },
                    // *** NEW: Handle "Create New Signature" Click ***
                    onCreateNew = {
                        showSignatureSheet = false
                        navController.navigate(Routes.SIGN)
                    }
                )
            }
        }
    }
}

@Composable
fun PdfPageItem(
    pageIndex: Int,
    pdfRenderer: PdfRenderer?,
    pdfMutex: Mutex,
    signatures: List<PlacedSignature>,
    selectedSignatureId: Long?,
    onSelect: (Long) -> Unit,
    onUpdate: (PlacedSignature) -> Unit,
    onDelete: (Long) -> Unit
) {
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pdfPageSize by remember { mutableStateOf(IntSize(0, 0)) }

    LaunchedEffect(pageIndex) {
        if (pdfRenderer != null) {
            withContext(Dispatchers.IO) {
                pdfMutex.withLock {
                    try {
                        val page = pdfRenderer.openPage(pageIndex)
                        pdfPageSize = IntSize(page.width, page.height)
                        val w = (page.width * 2).toInt()
                        val h = (page.height * 2).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        withContext(Dispatchers.Main) { pageBitmap = bmp }
                        page.close()
                    } catch (e: Exception) { }
                }
            }
        }
    }

    if (pageBitmap != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(pdfPageSize.width.toFloat() / pdfPageSize.height.toFloat())
                .background(Color.White)
        ) {
            Image(
                bitmap = pageBitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val viewWidth = maxWidth.value * LocalDensity.current.density
                val scaleFactor = if (pdfPageSize.width > 0) viewWidth / pdfPageSize.width else 1f

                signatures.forEach { sig ->
                    SignatureView(
                        signature = sig,
                        scaleFactor = scaleFactor,
                        pdfPageSize = pdfPageSize,
                        isSelected = (sig.id == selectedSignatureId),
                        onSelect = { onSelect(sig.id) },
                        onUpdate = onUpdate,
                        onDelete = { onDelete(sig.id) }
                    )
                }
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
fun SignatureView(
    signature: PlacedSignature,
    scaleFactor: Float,
    pdfPageSize: IntSize,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (PlacedSignature) -> Unit,
    onDelete: () -> Unit
) {
    val screenX = signature.x * scaleFactor
    val screenY = signature.y * scaleFactor
    val screenW = signature.width * scaleFactor
    val screenH = signature.height * scaleFactor

    val currentSignature by rememberUpdatedState(signature)
    val currentOnUpdate by rememberUpdatedState(onUpdate)
    val currentOnSelect by rememberUpdatedState(onSelect)

    Box(
        modifier = Modifier
            .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
            .size(
                width = with(LocalDensity.current) { screenW.toDp() },
                height = with(LocalDensity.current) { screenH.toDp() }
            )
            .graphicsLayer { rotationZ = signature.rotation }
            .zIndex(if(isSelected) 10f else 1f)
            // Gestures for Move (Center) and Pinch (Center)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    // Note: We ignore 'rotation' here because we use the handle for that
                    currentOnSelect()
                    val sig = currentSignature

                    // 1. Pan
                    val dx = pan.x / scaleFactor
                    val dy = pan.y / scaleFactor

                    // 2. Zoom
                    val newW = (sig.width * zoom).coerceAtLeast(20f)
                    val newH = (sig.height * zoom).coerceAtLeast(20f)
                    val wChange = newW - sig.width
                    val hChange = newH - sig.height

                    var rawX = sig.x + dx - (wChange / 2)
                    var rawY = sig.y + dy - (hChange / 2)

                    val maxX = pdfPageSize.width - newW
                    val maxY = pdfPageSize.height - newH
                    val safeX = rawX.coerceIn(0f, maxX.coerceAtLeast(0f))
                    val safeY = rawY.coerceIn(0f, maxY.coerceAtLeast(0f))

                    currentOnUpdate(sig.copy(x = safeX, y = safeY, width = newW, height = newH))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnSelect() })
            }
    ) {
        // 1. The Image
        Image(
            bitmap = signature.bitmap,
            contentDescription = "Signature",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds
        )

        if (isSelected) {
            // 2. Blue Border (Behind icons)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Color.Blue)
            )

            // 3. RESIZE HANDLE (Top Left) - 1-Finger Drag to Resize
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-12).dp, y = (-12).dp)
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .clip(CircleShape)
                    .zIndex(2f)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            currentOnSelect()
                            val sig = currentSignature

                            // Logic: Dragging Top-Left away from center (Up/Left) increases size.
                            // To keep aspect ratio, we average the X and Y drag.
                            // Inverted logic: Dragging left (-x) should grow width (+w).
                            val delta = -(dragAmount.x + dragAmount.y) / 2 / scaleFactor
                            val growth = delta * 2 // Grow both sides to stay centered

                            val newW = (sig.width + growth).coerceAtLeast(20f)
                            val newH = (sig.height + growth * (sig.height/sig.width)).coerceAtLeast(20f)

                            val wChange = newW - sig.width
                            val hChange = newH - sig.height

                            // Adjust X/Y to keep center fixed (Center Zoom)
                            val newX = sig.x - (wChange / 2)
                            val newY = sig.y - (hChange / 2)

                            currentOnUpdate(sig.copy(width = newW, height = newH, x = newX, y = newY))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Resize",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp).rotate(90f)
                )
            }

            // 4. ROTATE HANDLE (Bottom Center) - 1-Finger Drag to Rotate
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 12.dp)
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
                    .clip(CircleShape)
                    .zIndex(2f)
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            currentOnSelect()
                            val sig = currentSignature
                            // Dragging X rotates
                            val rotationDelta = dragAmount.x / 2f
                            currentOnUpdate(sig.copy(rotation = sig.rotation - rotationDelta))
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Rotate",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 5. CLOSE BUTTON (Top Right) - Tap to Delete
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(24.dp)
                    .shadow(2.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .clip(CircleShape)
                    .zIndex(2f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Delete",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SignatureSelectionSheet(
    signatures: List<File>,
    onSelect: (File) -> Unit,
    onCreateNew: () -> Unit // New parameter for creating signatures
) {
    Column(Modifier.padding(16.dp).fillMaxWidth()) {
        Text("Select Signature", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        if (signatures.isEmpty()) {
            // --- EMPTY STATE ---
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("No saved signatures found.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Create New Signature")
                }
            }
        } else {
            LazyColumn {
                items(signatures.size) { i ->
                    val file = signatures[i]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelect(file) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bmp = remember(file) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
                        if (bmp != null) {
                            Image(bmp, null, Modifier.size(50.dp, 30.dp).background(Color.White).border(1.dp, Color.Gray))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(file.name)
                    }
                }
                // Option to add new at bottom of list too
                item {
                    OutlinedButton(
                        onClick = onCreateNew,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Create New")
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SavePdfDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("Signed-Doc.pdf") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Signed PDF") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("File Name") }) },
        confirmButton = { Button(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- SAVE FUNCTION (Updated to App Specific Storage) ---
private suspend fun saveCleanPdf(context: Context, uris: List<Uri>, fileName: String, onComplete: (Boolean) -> Unit) = withContext(Dispatchers.IO) {
    val pdfDocument = PdfDocument()
    try {
        uris.forEachIndexed { index, uri ->
            val bitmap = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.isMutableRequired = true }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) { return@forEachIndexed }

            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDocument.finishPage(page)
            bitmap.recycle()
        }

        // --- SAVE TO APP SPECIFIC FILES (file_paths.xml 'app_files') ---
        // "app_files" path="." maps to getExternalFilesDir(null)
        val baseDir = context.getExternalFilesDir(null)
        if (baseDir != null && !baseDir.exists()) {
            baseDir.mkdirs()
        }

        val finalFile = File(baseDir, if (fileName.endsWith(".pdf")) fileName else "$fileName.pdf")
        val outputStream = FileOutputStream(finalFile)

        if (outputStream != null) {
            outputStream.use { pdfDocument.writeTo(it) }
            withContext(Dispatchers.Main) { onComplete(true) }
        } else {
            withContext(Dispatchers.Main) { onComplete(false) }
        }
    } catch (e: Exception) {
        Log.e("SavePdf", "Error", e)
        withContext(Dispatchers.Main) { onComplete(false) }
    } finally {
        pdfDocument.close()
    }
}