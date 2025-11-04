package saaicom.tcb.docuscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Scanner {

    // A simple data class to hold the results of our processing steps
    data class ScannedData(
        val original: Bitmap,
        val corners: MatOfPoint2f? = null,
        val scanned: Bitmap? = null
    )

    fun detectEdges(bitmap: Bitmap): ScannedData {
        // --- 1. Bitmap to Mat ---
        // Ensure the bitmap is mutable and in ARGB_8888
        val mutableBitmap = if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val imageMat = Mat()
        Utils.bitmapToMat(mutableBitmap, imageMat)

        // --- 2. Image Pre-processing ---
        val grayMat = Mat()
        Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

        val cannyMat = Mat()
        Imgproc.Canny(blurredMat, cannyMat, 75.0, 200.0)

        // --- 3. Find Contours ---
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        // --- 4. Find the Best Candidate (the document) ---
        var bestCandidate: MatOfPoint2f? = null
        var maxArea = 0.0
        val minArea = imageMat.size().area() * 0.1 // Require at least 10% of image area

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

        // Clean up intermediate matrices
        grayMat.release()
        blurredMat.release()
        cannyMat.release()
        hierarchy.release()
        imageMat.release() // No longer need imageMat, only the original bitmap

        return ScannedData(
            original = mutableBitmap,
            corners = bestCandidate?.let { sortPoints(it) }
        )
    }

    private fun sortPoints(points: MatOfPoint2f): MatOfPoint2f {
        val pts = points.toArray()
        if (pts.isEmpty()) return MatOfPoint2f()

        // 1. Find the center of the quadrilateral
        var centerX = 0.0
        var centerY = 0.0
        for (point in pts) {
            centerX += point.x
            centerY += point.y
        }
        val center = Point(centerX / 4, centerY / 4)

        // 2. Sort by angle relative to the center
        val sortedPoints = pts.sortedWith { a, b ->
            val angleA = kotlin.math.atan2(a.y - center.y, a.x - center.x)
            val angleB = kotlin.math.atan2(b.y - center.y, b.x - center.x)
            angleA.compareTo(angleB)
        }

        // 3. Find the top-left corner
        // The top-left corner will have the smallest sum of x and y
        var tlIndex = 0
        var minSum = Double.MAX_VALUE
        for (i in sortedPoints.indices) {
            val sum = sortedPoints[i].x + sortedPoints[i].y
            if (sum < minSum) {
                minSum = sum
                tlIndex = i
            }
        }

        // 4. Re-order the list to start with top-left
        val finalPoints = arrayOfNulls<Point>(4)
        for (i in 0..3) {
            finalPoints[i] = sortedPoints[(tlIndex + i) % 4]
        }

        return MatOfPoint2f(*finalPoints)
    }

    fun applyPerspectiveTransform(data: ScannedData): ScannedData {
        val originalBitmap = data.original
        val corners = data.corners ?: return data // No corners, return original data

        // --- 1. Define Source Points ---
        val srcPoints = corners.toArray()
        if (srcPoints.size < 4) return data

        // --- 2. Define Destination Dimensions based on corners ---
        val (tl, tr, br, bl) = srcPoints
        val topWidth = sqrt((tr.x - tl.x).pow(2) + (tr.y - tl.y).pow(2))
        val bottomWidth = sqrt((br.x - bl.x).pow(2) + (br.y - bl.y).pow(2))
        val leftHeight = sqrt((tl.x - bl.x).pow(2) + (tl.y - bl.y).pow(2))
        val rightHeight = sqrt((tr.x - br.x).pow(2) + (tr.y - br.y).pow(2))

        val maxWidth = max(topWidth, bottomWidth)
        val maxHeight = max(leftHeight, rightHeight)

        // --- 3. Define Destination Points for the cropped image ---
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),                     // Top-left
            Point(maxWidth - 1, 0.0),            // Top-right
            Point(maxWidth - 1, maxHeight - 1),  // Bottom-right
            Point(0.0, maxHeight - 1)            // Bottom-left
        )

        // --- 4. Create destination Mat for the cropped image ---
        val croppedMat = Mat(Size(maxWidth, maxHeight), CvType.CV_8UC4)

        // --- 5. Warp Document ---
        val transform = Imgproc.getPerspectiveTransform(corners, dstPoints)
        val originalMat = Mat()
        Utils.bitmapToMat(originalBitmap, originalMat)

        Imgproc.warpPerspective(originalMat, croppedMat, transform, croppedMat.size(), Imgproc.INTER_LINEAR)

        // --- 6. Enhance Document ---
        val enhancedMat = enhanceDocument(croppedMat) // Enhance the cropped image

        // --- 7. Convert back to Bitmap ---
        val scannedBitmap = Bitmap.createBitmap(enhancedMat.cols(), enhancedMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(enhancedMat, scannedBitmap)

        // Clean up
        corners.release()
        dstPoints.release()
        transform.release()
        originalMat.release()
        croppedMat.release() // Release the intermediate cropped mat
        enhancedMat.release()

        return data.copy(scanned = scannedBitmap)
    }

    private fun enhanceDocument(colorMat: Mat): Mat {
        // --- 1. Convert to LAB color space ---
        // LAB separates Brightness (L) from Color (A, B)
        val labMat = Mat()
        Imgproc.cvtColor(colorMat, labMat, Imgproc.COLOR_RGB2Lab)

        // --- 2. Extract the L (Lightness) channel ---
        val channels = ArrayList<Mat>(3)
        Core.split(labMat, channels)
        val lChannel = channels[0]

        // --- 3. Apply adaptive threshold to the L channel ---
        // This enhances contrast and removes shadows without affecting color
        val clahe = Imgproc.createCLAHE()
        clahe.clipLimit = 2.0
        clahe.apply(lChannel, lChannel)

        // --- 4. Merge the enhanced L channel back with the original A and B channels ---
        Core.merge(channels, labMat)

        // --- 5. Convert back to RGB ---
        val enhancedMat = Mat()
        Imgproc.cvtColor(labMat, enhancedMat, Imgproc.COLOR_Lab2RGB)

        // Clean up
        labMat.release()
        channels.forEach { it.release() }
        lChannel.release()

        return enhancedMat
    }
}