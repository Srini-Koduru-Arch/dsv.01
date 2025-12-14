package saaicom.tcb.docuscanner

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class Scanner {

    // Define the available filters
    enum class FilterType {
        Original, Magic, BW, Grayscale, Photo, Denoise
    }

    data class ScannedData(
        val original: Bitmap,
        val corners: MatOfPoint2f? = null,
        val scanned: Bitmap? = null
    )

    fun detectEdges(bitmap: Bitmap): ScannedData {
        // ... (Keep your existing detectEdges logic exactly as is) ...
        // For brevity, I am not repeating the full detectEdges code here,
        // please ensure you keep the implementation from your uploaded file.

        // --- 1. Bitmap to Mat ---
        val mutableBitmap = if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val imageMat = Mat()
        Utils.bitmapToMat(mutableBitmap, imageMat)

        val grayMat = Mat()
        Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)
        val cannyMat = Mat()
        Imgproc.Canny(blurredMat, cannyMat, 75.0, 200.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var bestCandidate: MatOfPoint2f? = null
        var maxArea = 0.0
        val minArea = imageMat.size().area() * 0.1

        for (contour in contours) {
            val contourArea = Imgproc.contourArea(contour)
            if (contourArea > minArea) {
                val approxCurve = MatOfPoint2f()
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                Imgproc.approxPolyDP(contour2f, approxCurve, 0.02 * peri, true)

                if (approxCurve.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approxCurve.toArray()))) {
                    if (contourArea > maxArea) {
                        maxArea = contourArea
                        bestCandidate = approxCurve
                    }
                }
                contour2f.release()
            }
            contour.release()
        }

        grayMat.release()
        blurredMat.release()
        cannyMat.release()
        hierarchy.release()
        imageMat.release()

        return ScannedData(
            original = mutableBitmap,
            corners = bestCandidate?.let { sortPoints(it) }
        )
    }

    // ... (Keep sortPoints exactly as is) ...
    private fun sortPoints(points: MatOfPoint2f): MatOfPoint2f {
        val pts = points.toArray()
        if (pts.isEmpty()) return MatOfPoint2f()
        var centerX = 0.0
        var centerY = 0.0
        for (point in pts) {
            centerX += point.x
            centerY += point.y
        }
        val center = Point(centerX / 4, centerY / 4)
        val sortedPoints = pts.sortedWith { a, b ->
            val angleA = kotlin.math.atan2(a.y - center.y, a.x - center.x)
            val angleB = kotlin.math.atan2(b.y - center.y, b.x - center.x)
            angleA.compareTo(angleB)
        }
        var tlIndex = 0
        var minSum = Double.MAX_VALUE
        for (i in sortedPoints.indices) {
            val sum = sortedPoints[i].x + sortedPoints[i].y
            if (sum < minSum) {
                minSum = sum
                tlIndex = i
            }
        }
        val finalPoints = arrayOfNulls<Point>(4)
        for (i in 0..3) {
            finalPoints[i] = sortedPoints[(tlIndex + i) % 4]
        }
        return MatOfPoint2f(*finalPoints)
    }

    // Updated to just perform the crop. We apply filters separately.
    fun applyPerspectiveTransform(data: ScannedData): ScannedData {
        val originalBitmap = data.original
        val corners = data.corners ?: return data
        val sortedCorners = sortPoints(corners)
        val srcPoints = sortedCorners.toArray()
        if (srcPoints.size < 4) {
            sortedCorners.release()
            return data
        }

        val (tl, tr, br, bl) = srcPoints
        val topWidth = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val bottomWidth = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val leftHeight = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        val rightHeight = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))

        val maxWidth = max(topWidth, bottomWidth)
        val maxHeight = max(leftHeight, rightHeight)

        if (maxWidth < 1 || maxHeight < 1) {
            sortedCorners.release()
            return data.copy(scanned = originalBitmap)
        }

        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1, 0.0),
            Point(maxWidth - 1, maxHeight - 1),
            Point(0.0, maxHeight - 1)
        )

        val croppedMat = Mat(Size(maxWidth, maxHeight), CvType.CV_8UC4)
        val transform = Imgproc.getPerspectiveTransform(sortedCorners, dstPoints)
        val originalMat = Mat()
        val scannedBitmap: Bitmap

        try {
            Utils.bitmapToMat(originalBitmap, originalMat)
            Imgproc.warpPerspective(originalMat, croppedMat, transform, croppedMat.size(), Imgproc.INTER_LINEAR)

            // NOTE: We do NOT apply enhanceDocument here anymore.
            // We return the raw cropped image so the user can choose the filter.

            scannedBitmap = Bitmap.createBitmap(croppedMat.cols(), croppedMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(croppedMat, scannedBitmap)

        } catch (e: Exception) {
            Log.e("Scanner", "Warp failed", e)
            return data.copy(scanned = originalBitmap)
        } finally {
            corners.release()
            dstPoints.release()
            transform.release()
            originalMat.release()
            croppedMat.release()
        }

        return data.copy(scanned = scannedBitmap)
    }

    /**
     * Applies a specific filter to a bitmap and returns a new bitmap.
     */
    fun applyFilter(bitmap: Bitmap, filterType: FilterType): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)

        // Convert to RGB for processing if needed (Bitmap is RGBA)
        val rgbMat = Mat()
        Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_RGBA2RGB)

        val processedMat = Mat()

        when (filterType) {
            FilterType.Original -> {
                // Just copy
                rgbMat.copyTo(processedMat)
            }
            FilterType.Grayscale -> {
                Imgproc.cvtColor(rgbMat, processedMat, Imgproc.COLOR_RGB2GRAY)
                // Convert back to RGB so it displays correctly on Bitmap
                Imgproc.cvtColor(processedMat, processedMat, Imgproc.COLOR_GRAY2RGB)
            }
            FilterType.BW -> {
                // Grayscale -> Adaptive Threshold
                val gray = Mat()
                Imgproc.cvtColor(rgbMat, gray, Imgproc.COLOR_RGB2GRAY)
                // Adaptive Threshold for "Document Scan" look
                Imgproc.adaptiveThreshold(
                    gray, processedMat, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY, 11, 2.0
                )
                gray.release()
                // Convert back to RGB
                val temp = Mat()
                processedMat.copyTo(temp)
                Imgproc.cvtColor(temp, processedMat, Imgproc.COLOR_GRAY2RGB)
                temp.release()
            }
            FilterType.Magic -> {
                // Your previous "enhanceDocument" logic (CLAHE)
                enhanceDocument(rgbMat).copyTo(processedMat)
            }
            FilterType.Photo -> {
                // Mild enhancement: Slight saturation/contrast boost
                // Convert to HSV -> Increase S -> Back to RGB
                val hsv = Mat()
                Imgproc.cvtColor(rgbMat, hsv, Imgproc.COLOR_RGB2HSV)
                val channels = ArrayList<Mat>()
                Core.split(hsv, channels)
                // Scale saturation by 1.2
                channels[1].convertTo(channels[1], -1, 1.2, 0.0)
                Core.merge(channels, hsv)
                Imgproc.cvtColor(hsv, processedMat, Imgproc.COLOR_HSV2RGB)
                hsv.release()
                channels.forEach { it.release() }
            }
            FilterType.Denoise -> {
                // Bilateral Filter keeps edges sharp but removes noise
                // (Faster than Non-Local Means)
                Imgproc.bilateralFilter(rgbMat, processedMat, 9, 75.0, 75.0)
            }
        }

        // Convert back to RGBA for Bitmap
        val finalMat = Mat()
        Imgproc.cvtColor(processedMat, finalMat, Imgproc.COLOR_RGB2RGBA)

        val resultBitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalMat, resultBitmap)

        // Cleanup
        srcMat.release()
        rgbMat.release()
        processedMat.release()
        finalMat.release()

        return resultBitmap
    }

    private fun enhanceDocument(colorMat: Mat): Mat {
        val labMat = Mat()
        Imgproc.cvtColor(colorMat, labMat, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(labMat, channels)
        val lChannel = channels[0]
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
        clahe.apply(lChannel, lChannel)
        Core.merge(channels, labMat)
        val enhancedMat = Mat()
        Imgproc.cvtColor(labMat, enhancedMat, Imgproc.COLOR_Lab2RGB)
        labMat.release()
        channels.forEach { it.release() }
        lChannel.release()
        return enhancedMat
    }
}