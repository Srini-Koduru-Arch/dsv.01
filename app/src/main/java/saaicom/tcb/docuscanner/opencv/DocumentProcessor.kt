package saaicom.tcb.docuscanner.opencv

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.MatOfPoint2f

/**
 * A Kotlin object to act as a bridge to native OpenCV functions.
 */
object DocumentProcessor{
    // A static initializer block to load the native library
    init {
        System.loadLibrary("native-lib")
    }

    /**
     * JNI function to process a document image using OpenCV.
     * @param matAddrSrc The memory address of the source Mat.
     * @param matAddrDest The memory address of the destination Mat.
     */
    external fun processDocumentNative(matAddrSrc: Long, matAddrDest: Long)

    /**
     * JNI function to detect the four corners of a document.
     * @param matAddrSrc The memory address of the source Mat.
     * @return A list of four Point objects representing the corners.
     */
    external fun detectDocumentCornersNative(matAddrSrc: Mat): List<Point>

    /**
     * JNI function to apply a perspective transform to a document.
     * @param matAddrSrc The memory address of the source Mat.
     * @param matAddrDest The memory address of the destination Mat.
     * @param corners The list of four corner points.
     */
    external fun applyPerspectiveTransformNative(matAddrSrc: Long, matAddrDest: Long, corners: MatOfPoint2f)
}
