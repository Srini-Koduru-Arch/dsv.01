#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <opencv2/opencv.hpp>

#define TAG "DocuScanner-Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// JNI function to process a document image (the existing function)
extern "C" JNIEXPORT void JNICALL
Java_saaicom_tcb_docuscanner_opencv_DocumentProcessor_processDocumentNative(JNIEnv *env, jobject thiz, jlong matAddrSrc, jlong matAddrDest) {
    cv::Mat &src = *(cv::Mat *) matAddrSrc;
    cv::Mat &dest = *(cv::Mat *) matAddrDest;

    cv::cvtColor(src, dest, cv::COLOR_RGBA2GRAY);
    cv::threshold(dest, dest, 128, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
    LOGD("Image processed successfully.");
}

// JNI function to detect the four corners of a document
extern "C" JNIEXPORT jobject JNICALL
Java_saaicom_tcb_docuscanner_opencv_DocumentProcessor_detectDocumentCornersNative(JNIEnv *env, jobject thiz,
                                                                                     jobject matAddrSrc) {
    cv::Mat &src = *(cv::Mat *) matAddrSrc;
    cv::Mat gray, blurred, edged;

    // Convert to grayscale
    cv::cvtColor(src, gray, cv::COLOR_RGBA2GRAY);

    // Apply Gaussian blur to remove noise
    cv::GaussianBlur(gray, blurred, cv::Size(5, 5), 0);

    // Use Canny edge detection
    cv::Canny(blurred, edged, 75, 200);

    // Find contours in the edged image
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(edged, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    // Sort contours by area in descending order
    std::sort(contours.begin(), contours.end(), [](const std::vector<cv::Point>& a, const std::vector<cv::Point>& b) {
        return cv::contourArea(a) > cv::contourArea(b);
    });

    std::vector<cv::Point> approx;
    jclass pointClass = env->FindClass("org/opencv/core/Point");
    jmethodID pointConstructor = env->GetMethodID(pointClass, "<init>", "(DD)V");
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID arrayListConstructor = env->GetMethodID(arrayListClass, "<init>", "()V");
    jmethodID arrayListAddMethod = env->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");

    jobject cornersList = env->NewObject(arrayListClass, arrayListConstructor);

    for (const auto& contour : contours) {
        // Approximate the contour with a polygon
        double perimeter = cv::arcLength(contour, true);
        cv::approxPolyDP(contour, approx, 0.02 * perimeter, true);

        // If the approximated polygon has 4 vertices, it might be our document
        if (approx.size() == 4) {
            // Sort points to have a consistent order (top-left, top-right, etc.)
            cv::Point2f rect[4];
            std::vector<cv::Point2f> points;
            for(const auto& p : approx) {
                points.push_back(cv::Point2f(static_cast<float>(p.x), static_cast<float>(p.y)));
            }
            std::sort(points.begin(), points.end(), [](const cv::Point2f& a, const cv::Point2f& b) {
                return a.x + a.y < b.x + b.y;
            });
            rect[0] = points[0];
            rect[3] = points.back();
            points.erase(points.begin());
            points.pop_back();
            if (points[0].x > points[1].x) {
                rect[1] = points[0];
                rect[2] = points[1];
            } else {
                rect[1] = points[1];
                rect[2] = points[0];
            }

            for(int i = 0; i < 4; i++) {
                jobject cornerPoint = env->NewObject(pointClass, pointConstructor, (jdouble)rect[i].x, (jdouble)rect[i].y);
                env->CallBooleanMethod(cornersList, arrayListAddMethod, cornerPoint);
                env->DeleteLocalRef(cornerPoint);
            }
            return cornersList;
        }
    }

    // Return an empty list if no document is found
    return cornersList;
}

// JNI function to apply a perspective transform
extern "C" JNIEXPORT void JNICALL
Java_saaicom_tcb_docuscanner_opencv_DocumentProcessor_applyPerspectiveTransformNative(
        JNIEnv *env, jobject thiz, jlong matAddrSrc, jlong matAddrDest, jobject corners) {
    cv::Mat &src = *(cv::Mat *) matAddrSrc;
    cv::Mat &dest = *(cv::Mat *) matAddrDest;

    jclass arrayListClass = env->GetObjectClass(corners);
    jmethodID arrayListGetMethod = env->GetMethodID(arrayListClass, "get", "(I)Ljava/lang/Object;");
    jmethodID arrayListSizeMethod = env->GetMethodID(arrayListClass, "size", "()I");

    jint size = env->CallIntMethod(corners, arrayListSizeMethod);
    if (size != 4) {
        LOGE("Invalid number of corners for perspective transform: %d", size);
        return;
    }

    std::vector<cv::Point2f> srcPoints;
    for(int i = 0; i < size; i++) {
        jobject pointObject = env->CallObjectMethod(corners, arrayListGetMethod, i);
        jclass pointClass = env->GetObjectClass(pointObject);
        jfieldID xField = env->GetFieldID(pointClass, "x", "D");
        jfieldID yField = env->GetFieldID(pointClass, "y", "D");
        jdouble x = env->GetDoubleField(pointObject, xField);
        jdouble y = env->GetDoubleField(pointObject, yField);
        srcPoints.push_back(cv::Point2f(static_cast<float>(x), static_cast<float>(y)));
        env->DeleteLocalRef(pointObject);
    }

    // Determine the new width and height of the transformed image
    float widthA = cv::norm(srcPoints[1] - srcPoints[0]);
    float widthB = cv::norm(srcPoints[2] - srcPoints[3]);
    float maxWidth = std::max(widthA, widthB);

    float heightA = cv::norm(srcPoints[3] - srcPoints[0]);
    float heightB = cv::norm(srcPoints[2] - srcPoints[1]);
    float maxHeight = std::max(heightA, heightB);

    std::vector<cv::Point2f> dstPoints;
    dstPoints.push_back(cv::Point2f(0.0f, 0.0f));
    dstPoints.push_back(cv::Point2f(maxWidth, 0.0f));
    dstPoints.push_back(cv::Point2f(maxWidth, maxHeight));
    dstPoints.push_back(cv::Point2f(0.0f, maxHeight));

    cv::Mat M = cv::getPerspectiveTransform(srcPoints, dstPoints);
    cv::warpPerspective(src, dest, M, cv::Size(static_cast<int>(maxWidth), static_cast<int>(maxHeight)));

    LOGD("Perspective transform applied successfully. New size: %dx%d", dest.cols, dest.rows);
}
