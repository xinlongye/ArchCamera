package com.cam.archcamera.preview;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.camera.PreviewFrameSink;

/**
 * Preview display using native GLES3 NV21 (Y + VU) shader rendering.
 */
public class PreviewGlSurfaceView extends GLSurfaceView implements PreviewFrameSink {

    private static final String TAG = "PreviewGlSurfaceView";

    private final PreviewRenderer renderer;

    public PreviewGlSurfaceView(@NonNull Context context) {
        this(context, null);
    }

    public PreviewGlSurfaceView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(3);
        renderer = new PreviewRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @NonNull
    public PreviewFrameSink getFrameSink() {
        return this;
    }

    public void setPreviewFpsListener(@Nullable PreviewRenderer.PreviewFpsListener listener) {
        renderer.setPreviewFpsListener(
                listener == null ? null : fps -> post(() -> listener.onPreviewFpsUpdated(fps)));
    }

    /** Sets NV21 frame dimensions and direct buffers (placeholder until camera delivers frames). */
    public void setPreviewSize(int width, int height) {
        renderer.setStreamSize(width, height);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            int nv21Bytes = width * height * 3 / 2;
            Log.d(
                    TAG,
                    "setPreviewSize frame="
                            + width
                            + "x"
                            + height
                            + " nv21Bytes="
                            + nv21Bytes);
        }
        // Do not requestRender here: empty NV21 (Y=0, UV=0) renders as solid green in native shader.
    }

    @Override
    @Nullable
    public java.nio.ByteBuffer getWriteBuffer() {
        return renderer.getWriteBuffer();
    }

    @Override
    public void onPreviewFrameAvailable() {
        if (renderer.acceptPreviewFrameIfValid()) {
            // requestRender is thread-safe; avoids queueEvent latency on the camera thread.
            requestRender();
        }
    }

    @Override
    public int getStreamWidth() {
        return renderer.getStreamWidth();
    }

    @Override
    public int getStreamHeight() {
        return renderer.getStreamHeight();
    }

    @Override
    public void setStreamSize(int width, int height) {
        setPreviewSize(width, height);
    }

    @Override
    public void setDisplayTransform(int rotationDegrees, boolean mirrorHorizontal) {
        renderer.setDisplayTransform(rotationDegrees, mirrorHorizontal);
    }

    @Override
    public void setPreviewStreaming(boolean streaming) {
        renderer.setPreviewStreaming(streaming);
        if (!streaming) {
            queueEvent(this::requestRender);
        }
    }

    @Override
    public void invalidatePreviewFrames() {
        renderer.invalidatePreviewFrames();
        queueEvent(this::requestRender);
    }

    public void submitPreviewFrame() {
        onPreviewFrameAvailable();
    }

    public void release() {
        renderer.destroy();
    }

    @Override
    public void onPause() {
        renderer.resetFpsStats();
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (renderer.isPreviewStreaming()) {
            queueEvent(this::requestRender);
        }
    }
}
