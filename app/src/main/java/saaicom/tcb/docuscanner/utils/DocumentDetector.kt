package saaicom.tcb.docuscanner.utils

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

object DocumentDetector {

    fun detectLargestDocument(frame: Mat): MatOfPoint? {
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()

        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        Imgproc.Canny(blurred, edges, 75.0, 200.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var largestContour: MatOfPoint? = null
        var largestArea = 0.0

        for (contour in contours) {
            val contour2f = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * perimeter, true)

            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                val area = abs(Imgproc.contourArea(approx))
                if (area > largestArea) {
                    largestArea = area
                    largestContour = MatOfPoint(*approx.toArray())
                }
            }
        }

        gray.release()
        blurred.release()
        edges.release()
        hierarchy.release()

        return largestContour
    }
}
