#include <jni.h>

#include "preview_renderer.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
    return reinterpret_cast<jlong>(new PreviewRendererNative());
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeDestroy(JNIEnv* /*env*/,
                                                              jclass /*clazz*/,
                                                              jlong handle) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->release();
        delete r;
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeResize(JNIEnv* /*env*/,
                                                             jclass /*clazz*/,
                                                             jlong handle,
                                                             jint frameWidth,
                                                             jint frameHeight) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->resize(frameWidth, frameHeight);
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeSetDisplayTransform(JNIEnv* /*env*/,
                                                                          jclass /*clazz*/,
                                                                          jlong handle,
                                                                          jint rotationDegrees,
                                                                          jboolean mirrorHorizontal) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->setDisplayTransform(rotationDegrees, mirrorHorizontal);
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeOnSurfaceCreated(JNIEnv* /*env*/,
                                                                       jclass /*clazz*/,
                                                                       jlong handle) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->onSurfaceCreated();
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeOnSurfaceChanged(JNIEnv* /*env*/,
                                                                       jclass /*clazz*/,
                                                                       jlong handle,
                                                                       jint viewWidth,
                                                                       jint viewHeight) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->onSurfaceChanged(viewWidth, viewHeight);
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeClearView(JNIEnv* /*env*/,
                                                                jclass /*clazz*/,
                                                                jlong handle) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r != nullptr) {
        r->clearView();
    }
}

JNIEXPORT void JNICALL
Java_com_cam_archcamera_preview_PreviewRenderer_nativeOnDrawFrame(JNIEnv* env,
                                                                  jclass /*clazz*/,
                                                                  jlong handle,
                                                                  jobject nv21Buffer,
                                                                  jint frameWidth,
                                                                  jint frameHeight) {
    auto* r = reinterpret_cast<PreviewRendererNative*>(handle);
    if (r == nullptr || nv21Buffer == nullptr) {
        return;
    }
    auto* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(nv21Buffer));
    if (data == nullptr) {
        return;
    }
    r->draw(data, frameWidth, frameHeight);
}

}  // extern "C"
