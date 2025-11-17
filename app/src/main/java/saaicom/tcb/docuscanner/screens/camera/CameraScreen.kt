package saaicom.tcb.docuscanner.screens.camera

import android.content.Context
import android.graphics.ImageFormat
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.Point
import org.opencv.core.Mat
import org.opencv.android.Utils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import android.graphics.Bitmap
import android.graphics.YuvImage
import android.graphics.Rect

import java.io.ByteArrayOutputStream

import saaicom.tcb.docuscanner.utils.DocumentDetector


// --- NOTE: This placeholder requires a robust YUV_420_888 to Bitmap conversion implementation.
// Please ensure you implement or import a correct `ImageProxy.toBitmap()` utility.
private fun ImageProxy.toBitmap(): Bitmap? {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}

// --- END NOTE ---


@Composable
fun CameraScreen(
    navController: NavController,
        requestCameraPermission: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val coroutineScope = rememberCoroutineScope()

    // State to hold the detected corners (in ImageAnalysis resolution, 720x1280)
    var detectedCorners by remember { mutableStateOf<List<Point>?>(null) }
    // Stable reference to the latest detected corners for use in the capture lambda
    val currentDetectedCorners by rememberUpdatedState(detectedCorners)

    // Request camera permission when this composable is first launched.
    LaunchedEffect(Unit) {
        requestCameraPermission()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val previewView = PreviewView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    bindCameraUseCases(
                        cameraProvider,
                        lifecycleOwner,
                        previewView,
                        imageCapture,
                        onCornersDetected = { corners -> detectedCorners = corners }
                    )
                }, ContextCompat.getMainExecutor(context))
                previewView
            }
        )

        // Overlay to draw the detected edges on top of the preview
        DrawEdgeOverlay(detectedCorners = detectedCorners)

        // Capture button at the bottom center
        Button(
            onClick = {
                coroutineScope.launch {
                    takePhoto(context, cameraExecutor, imageCapture) { savedUri ->
                        Log.d("CameraScreen", "Photo captured: $savedUri")

                        // Dispatch navigation to the main thread
                        coroutineScope.launch(Dispatchers.Main) {
                            val encodedUri = Uri.encode(savedUri.toString())

                            // Format the detected corners into a URL-safe string
                            // Format: x1:y1,x2:y2,x3:y3,x4:y4 or "null"
                            val cornersJson = currentDetectedCorners?.joinToString(separator = ",") { "${it.x}:${it.y}" } ?: "null"
                            val encodedCorners = Uri.encode(cornersJson)

                            // Pass the detected corners to the edit screen
                            navController.navigate("edit_document/$encodedUri?corners=$encodedCorners")
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = "Capture Document"
            )
            Spacer(Modifier.width(8.dp))
            Text("Capture")
        }
    }
}

private fun bindCameraUseCases(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture,
    onCornersDetected: (List<Point>) -> Unit // Added callback for live corner detection
) {
    val preview = Preview.Builder()
        .build()
        .also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

    val imageAnalysis = ImageAnalysis.Builder()
        // Use a standard target resolution for analysis. This is the coordinate space for the detected points.
        .setTargetResolution(Size(720, 1280))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    // Executor for analysis should be single-threaded for ordered processing
    val analysisExecutor = Executors.newSingleThreadExecutor()
    imageAnalysis.setAnalyzer(
        analysisExecutor,
        DocumentEdgeAnalyzer(
            onCornersDetected = onCornersDetected
        )
    )

    val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA

    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageCapture,
            imageAnalysis // Bind the analysis use case
        )
    } catch (exc: Exception) {
        Log.e("CameraScreen", "Use case binding failed", exc)
        analysisExecutor.shutdown() // Ensure executor is stopped on failure
    }
}

// Custom Analyzer class to run OpenCV edge detection
private class DocumentEdgeAnalyzer(
    private val onCornersDetected: (List<Point>) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastRunTime = 0L
    private val frameSkipInterval = 200 // every 200ms

    override fun analyze(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastRunTime < frameSkipInterval) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            if (bitmap != null) {
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)

                // ✅ Rotate to match PreviewView (portrait mode)
                //Core.rotate(mat, mat, Core.ROTATE_90_CLOCKWISE)

                // (Optional) Flip horizontally if you’re using the front camera
                // Core.flip(mat, mat, 1)

                val contour = DocumentDetector.detectLargestDocument(mat)
                if (contour != null && contour.toArray().size == 4) {
                    onCornersDetected(contour.toArray().toList())
                }

                mat.release()
            }
        } catch (e: Exception) {
            Log.e("DocumentEdgeAnalyzer", "OpenCV processing failed", e)
        } finally {
            imageProxy.close()
            lastRunTime = currentTime
        }
    }
}


// Composable to draw the quadrilateral overlay on top of the camera preview
@Composable
private fun DrawEdgeOverlay(detectedCorners: List<Point>?) {
    if (detectedCorners == null || detectedCorners.size != 4) return

    val density = LocalDensity.current
    var composableSize by remember { mutableStateOf(ComposeSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { composableSize = it.toSize() }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = size
            if (canvasSize.width == 0f || canvasSize.height == 0f) return@Canvas

            // The analysis target resolution. OpenCV points are in this space.
            val analysisWidth = 720f
            val analysisHeight = 1280f
            val analysisAspectRatio = analysisWidth / analysisHeight // 0.5625

            val viewAspectRatio = canvasSize.width / canvasSize.height

            val scale: Float
            val dx: Float
            val dy: Float

            // --- Coordinate Transformation Logic (CenterCrop / FILL_CENTER) ---
            val imageWidth = analysisWidth
            val imageHeight = analysisHeight

            if (viewAspectRatio > analysisAspectRatio) {
                // View is wider than image: Scale height to match, center horizontally
                scale = canvasSize.height / imageHeight
                dx = (canvasSize.width - imageWidth * scale) / 2
                dy = 0f
            } else {
                // View is taller than image: Scale width to match, center vertically
                scale = canvasSize.width / imageWidth
                dx = 0f
                dy = (canvasSize.height - imageHeight * scale) / 2
            }
            // --- End of CenterCrop Logic ---


            // Map OpenCV Points to Canvas Offsets and draw the quadrilateral path
            val path = Path().apply {
                detectedCorners.forEachIndexed { index, point ->

                    // Transformation: 90-degree clockwise rotation (X, Y) -> (Y, W-X)
                    // This is the most common fix for the PreviewView/ImageAnalysis coordinate mismatch
                    val rotatedX = point.y.toFloat()
                    val rotatedY = analysisWidth - point.x.toFloat()

                    val x = (rotatedX * scale) + dx
                    val y = (rotatedY * scale) + dy
                    val cornerOffset = Offset(x, y)

                    if (index == 0) moveTo(x, y) else lineTo(x, y)

                    // Draw corner circles
                    drawCircle(
                        color = Color.Yellow,
                        radius = with(density) { 8.dp.toPx() },
                        center = cornerOffset
                    )
                }
                close()
            }

            // Draw the border path
            drawPath(
                path = path,
                color = Color.Yellow,
                style = Stroke(width = with(density) { 3.dp.toPx() })
            )
        }
    }
}


private fun takePhoto(
    context: Context,
    executor: Executor,
    imageCapture: ImageCapture,
    onPhotoCaptured: (Uri) -> Unit,
) {
    val outputDirectory = getOutputDirectory(context)
    val photoFile = File(
        outputDirectory,
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()) + ".jpg"
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraScreen", "Photo capture failed: ${exc.message}", exc)
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = Uri.fromFile(photoFile)
                onPhotoCaptured(savedUri)
            }
        }
    )
}

private fun getOutputDirectory(context: Context): File {
    val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
        File(it, "DocuScanner").apply { mkdirs() }
    }
    return if (mediaDir != null && mediaDir.exists()) mediaDir else context.filesDir
}