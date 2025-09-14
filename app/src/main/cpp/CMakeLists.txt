# CMakeLists.txt

cmake_minimum_required(VERSION 3.22.1)

project("docuscanner-native-lib")

# Set C++ standard
set(CMAKE_CXX_STANDARD 14)

# Find OpenCV headers and libraries
# The paths are relative to the CMakeLists.txt location (app/src/main/cpp/)
set(OpenCV_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../../../../opencv/native/jni/include)
set(OpenCV_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../opencv/native/libs)

# Add headers to the include path
include_directories(
        ${OpenCV_INCLUDE_DIR}
)

# Add OpenCV library
add_library(
        opencv_java4
        SHARED
        IMPORTED
)

set_target_properties(
        opencv_java4
        PROPERTIES IMPORTED_LOCATION
        ${OpenCV_LIB_DIR}/${ANDROID_ABI}/libopencv_java4.so
)

# Add a C++ library to the build.
add_library(
        native-lib
        SHARED
        docuscanner.cpp
)

# Link native-lib to OpenCV library
target_link_libraries(
        native-lib
        opencv_java4
        android
        log
)
