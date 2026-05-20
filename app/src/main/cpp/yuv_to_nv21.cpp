#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>

namespace {

void copy_y_tight(
        const uint8_t* src,
        int rowStride,
        uint8_t* dst,
        int width,
        int height,
        int cropLeft,
        int cropTop) {
    const int yOffset = rowStride * cropTop + cropLeft;
    for (int row = 0; row < height; ++row) {
        std::memcpy(
                dst + row * width,
                src + yOffset + row * rowStride,
                static_cast<size_t>(width));
    }
}

void copy_y_strided(
        const uint8_t* src,
        int rowStride,
        int pixelStride,
        uint8_t* dst,
        int width,
        int height,
        int cropLeft,
        int cropTop) {
    const int yOffset = rowStride * cropTop + pixelStride * cropLeft;
    for (int row = 0; row < height; ++row) {
        const int rowBase = yOffset + row * rowStride;
        uint8_t* outRow = dst + row * width;
        for (int col = 0; col < width; ++col) {
            outRow[col] = src[rowBase + col * pixelStride];
        }
    }
}

void copy_vu(
        const uint8_t* u,
        const uint8_t* v,
        uint8_t* dst,
        int width,
        int height,
        int uRowStride,
        int uPixelStride,
        int vRowStride,
        int vPixelStride,
        int cropLeft,
        int cropTop) {
    const int uvHeight = height / 2;
    const int uvWidth = width / 2;
    const int uvLeft = cropLeft / 2;
    const int uvTop = cropTop / 2;
    for (int row = 0; row < uvHeight; ++row) {
        const int vRowBase = (uvTop + row) * vRowStride + uvLeft * vPixelStride;
        const int uRowBase = (uvTop + row) * uRowStride + uvLeft * uPixelStride;
        uint8_t* outRow = dst + row * width;
        for (int col = 0; col < uvWidth; ++col) {
            const int vIndex = vRowBase + col * vPixelStride;
            const int uIndex = uRowBase + col * uPixelStride;
            outRow[col * 2] = v[vIndex];
            outRow[col * 2 + 1] = u[uIndex];
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_cam_archcamera_camera_Yuv420888ToNv21_nativeConvert(
        JNIEnv* env,
        jclass /*clazz*/,
        jobject yBuffer,
        jint yRowStride,
        jint yPixelStride,
        jobject uBuffer,
        jint uRowStride,
        jint uPixelStride,
        jobject vBuffer,
        jint vRowStride,
        jint vPixelStride,
        jint cropLeft,
        jint cropTop,
        jint width,
        jint height,
        jobject outBuffer) {
    if (width <= 0 || height <= 0) {
        return;
    }
    auto* yPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* outPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outBuffer));
    if (yPtr == nullptr || uPtr == nullptr || vPtr == nullptr || outPtr == nullptr) {
        return;
    }

    const int ySize = width * height;
    if (yPixelStride == 1) {
        copy_y_tight(yPtr, yRowStride, outPtr, width, height, cropLeft, cropTop);
    } else {
        copy_y_strided(
                yPtr, yRowStride, yPixelStride, outPtr, width, height, cropLeft, cropTop);
    }
    copy_vu(
            uPtr,
            vPtr,
            outPtr + ySize,
            width,
            height,
            uRowStride,
            uPixelStride,
            vRowStride,
            vPixelStride,
            cropLeft,
            cropTop);
}

}  // extern "C"
