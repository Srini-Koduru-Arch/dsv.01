package saaicom.tcb.docuscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import androidx.core.graphics.createBitmap

class Scanner {

    /**
     * A data class to hold the results of the scanning process.
     * @param original The original bitmap provided by the user.
     * @param corners The detected corners of the document.
     * @param scanned The final, perspective-corrected bitmap.
     */
    data class ScannedData(
        val original: Bitmap,
        val corners: MatOfPoint2f? = null,
        val scanned: Bitmap? = null
    )

    /**
     * Detects the edges of a document in a bitmap.
     * @param bitmap The input image containing a document.
     * @return ScannedData containing the original image and the detected corners.
     */
    fun detectEdges(bitmap: Bitmap): ScannedData {
        // 1. Standardize Bitmap Format to ARGB_8888 for compatibility with OpenCV
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val imageMat = Mat()
        Utils.bitmapToMat(mutableBitmap, imageMat)

        // Safety check: if conversion fails, return the original
        if (imageMat.empty()) {
            return ScannedData(original = bitmap)
        }

        // 2. Process the image: Grayscale -> Blur -> Canny Edge Detection
        val grayMat = Mat()
        Imgproc.cvtColor(imageMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val blurredMat = Mat()
        Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

        val cannyMat = Mat()
        Imgproc.Canny(blurredMat, cannyMat, 75.0, 200.0)

        // 3. Find contours
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(cannyMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        // If no contours found, clean up and return
        if (contours.isEmpty()) {
            imageMat.release()
            grayMat.release()
            blurredMat.release()
            cannyMat.release()
            hierarchy.release()
            return ScannedData(original = bitmap)
        }

        // Sort contours by area in descending order to find the largest one (likely the document)
        contours.sortByDescending { Imgproc.contourArea(it) }

        // 4. Find the largest 4-sided contour
        for (contour in contours) {
            val approxCurve = MatOfPoint2f()
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approxCurve, 0.02 * peri, true)

            if (approxCurve.total() == 4L) { // Check if it's a quadrilateral
                val points = approxCurve.toArray()
                val sortedPoints = sortPoints(points)

                // Clean up all Mats before returning
                imageMat.release()
                grayMat.release()
                blurredMat.release()
                cannyMat.release()
                hierarchy.release()
                contour.release()
                approxCurve.release()
                contour2f.release()

                return ScannedData(original = bitmap, corners = MatOfPoint2f(*sortedPoints))
            }
            // Release memory for contours that are not the target
            contour.release()
            approxCurve.release()
            contour2f.release()
        }

        // Clean up if no 4-sided contour is found
        imageMat.release()
        grayMat.release()
        blurredMat.release()
        cannyMat.release()
        hierarchy.release()

        return ScannedData(original = bitmap)
    }

    /**
     * Applies a perspective transform to the original image based on detected corners.
     * @param data The ScannedData object containing the original image and corners.
     * @return ScannedData object with the new, transformed bitmap included.
     */
    fun applyPerspectiveTransform(data: ScannedData): ScannedData {
        if (data.corners == null) return data

        val originalMat = Mat()
        Utils.bitmapToMat(data.original.copy(Bitmap.Config.ARGB_8888, true), originalMat)

        val corners = data.corners
        val sortedPoints = corners.toArray()

        val tl = sortedPoints[0] // Top-left
        val tr = sortedPoints[1] // Top-right
        val br = sortedPoints[2] // Bottom-right
        val bl = sortedPoints[3] // Bottom-left
        // Calculate the width of the new image
        val widthA = Math.sqrt(Math.pow(br.x - bl.x, 2.0) + Math.pow(br.y - bl.y, 2.0))
        val widthB = Math.sqrt(Math.pow(tr.x - tl.x, 2.0) + Math.pow(tr.y - tl.y, 2.0))
        val maxWidth = Math.max(widthA, widthB).toInt()

        // Calculate the height of the new image
        val heightA = Math.sqrt(Math.pow(tr.x - br.x, 2.0) + Math.pow(tr.y - br.y, 2.0))
        val heightB = Math.sqrt(Math.pow(tl.x - bl.x, 2.0) + Math.pow(tl.y - bl.y, 2.0))
        val maxHeight = Math.max(heightA, heightB).toInt()

        // Define the destination points for the perspective transform
        val destMat = Mat.zeros(maxHeight, maxWidth, CvType.CV_8UC4)
        val destPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )

        // Get the transformation matrix and apply it
        val transformMatrix = Imgproc.getPerspectiveTransform(corners, destPoints)
        Imgproc.warpPerspective(originalMat, destMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))

        // Convert the final Mat back to a Bitmap
        val scannedBitmap = createBitmap(destMat.cols(), destMat.rows())
        Utils.matToBitmap(destMat, scannedBitmap)

        // Release all Mats to free up memory
        originalMat.release()
        destMat.release()
        destPoints.release()
        transformMatrix.release()

        return data.copy(scanned = scannedBitmap)
    }


    /**
     * Sorts an array of 4 points into a consistent clockwise order:
     * Top-Left, Top-Right, Bottom-Right, Bottom-Left
     */
    private fun sortPoints(points: Array<Point>): Array<Point> {
        // Calculate the centroid of the points
        val center = Point(
            points.map { it.x }.average(),
            points.map { it.y }.average()
        )

        // Sort points based on the angle they make with the centroid
        val sortedPoints = points.sortedWith(compareBy {
            atan2(it.y - center.y, it.x - center.x)
        })

        // The sorted array is now in a consistent clockwise or counter-clockwise order.
        // We need to find the top-left corner to start the array with.
        // The top-left corner will have the smallest sum of x and y coordinates.
        val topLeft = sortedPoints.minByOrNull { it.x + it.y } ?: return sortedPoints.toTypedArray()
        val topLeftIndex = sortedPoints.indexOf(topLeft)

        // Rotate the array so that the top-left corner is the first element
        val result = Array(4) { Point() }
        for (i in 0 until 4) {
            result[i] = sortedPoints[(topLeftIndex + i) % 4]
        }

        // The result is now guaranteed to be in the order: TL, TR, BR, BL
        return result
    }
}

