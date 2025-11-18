package saaicom.tcb.docuscanner.utils

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object DocumentDetector {

    fun detectLargestDocument(frame: Mat): MatOfPoint? {
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat() // <-- We are using Canny 'edges' again
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()

        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        // --- 1. RUN CANNY ON THE GRAYSCALE IMAGE ---
        // This is the classic way to find edges.
        Imgproc.Canny(blurred, edges, 75.0, 200.0)

        // --- 2. DILATE & ERODE (MORPH_CLOSE) ---
        // Canny's edges are thin. We'll "thicken" them (Dilate)
        // to connect any gaps, and then "thin" them (Erode)
        // to get back to the original shape, but now fully connected.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, edges, kernel)
        Imgproc.erode(edges, edges, kernel)
        kernel.release()

        // --- 3. FIND CONTOURS ON THE CONNECTED EDGES ---
        Imgproc.findContours(
            edges, // <-- Find contours on our Canny edges
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL, // Still only want the outermost shape
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        var largestContour: MatOfPoint? = null
        var largestArea = 0.0
        val minArea = frame.size().area() * 0.1
        // --- 4. NEW: ADD A MAX AREA CHECK ---
        // This will reject the "whole screen" contour if it's found
        val maxArea = frame.size().area() * 0.95

        // --- Defensive loop ---
        for (contour in contours) {
            if (contour.empty() || contour.toArray() == null) {
                contour.release()
                continue
            }

            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)

            if (approx.empty() || approx.toArray() == null || approx.toArray().isEmpty()) {
                contour.release()
                contour2f.release()
                approx.release()
                continue
            }

            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                val area = abs(Imgproc.contourArea(approx))

                // --- 5. USE ALL CHECKS ---
                if (area > minArea && area < maxArea && area > largestArea) {
                    largestArea = area
                    largestContour = MatOfPoint(*approx.toArray())
                }
            }

            contour.release()
            contour2f.release()
            approx.release()
        }
        // --- End of loop ---

        gray.release()
        blurred.release()
        edges.release() // <-- Release 'edges'
        hierarchy.release()

        // --- Logging (No change) ---
        val imageSize = frame.size()
        Log.d("DocumentDetector", "Contour Image Coords (WxH): ${imageSize.width} x ${imageSize.height}")

        if (largestContour != null && !largestContour.empty()) {
            try {
                val points = largestContour.toArray().toList()
                Log.d(
                    "DocumentDetector",
                    "RETURNING Contour: [${points[0]}], [${points[1]}], [${points[2]}], [${points[3]}]"
                )
            } catch (e: Exception) {
                Log.e("DocumentDetector", "Error logging largestContour", e)
            }
        } else {
            Log.d("DocumentDetector", "RETURNING null contour (no document found)")
        }

        return largestContour
    }
}