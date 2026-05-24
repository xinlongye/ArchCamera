package com.cam.archcamera.preview;

import android.opengl.GLSurfaceView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.camera.PreviewFrameSink;
import com.cam.archcamera.camera.PreviewFrameValidator;

import java.nio.ByteBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Forwards {@link GLSurfaceView.Renderer} callbacks to native GLES YUV drawing.
 */
final class PreviewRenderer implements GLSurfaceView.Renderer, PreviewFrameSink {

    private static final String TAG = "PreviewRenderer";

    static {
        System.loadLibrary("archcamera");
    }

    private static final int NV21_BUFFER_COUNT = 3;

    private long nativeHandle;
    @Nullable private ByteBuffer[] nv21Buffers;
    private int displayIndex;
    private int writeIndex = 1;
    private int prevDisplayIndex = -1;
    private int frameWidth;
    private int frameHeight;
    private int rotationDegrees;
    private boolean mirrorHorizontal;
    private volatile boolean pendingNativeResize;
    private volatile boolean hasFrame;
    private volatile boolean previewStreaming;

    @FunctionalInterface
    interface PreviewFpsListener {
        void onPreviewFpsUpdated(float fps);
    }

    @Nullable private PreviewFpsListener fpsListener;
    private long fpsFrameCount;
    private long fpsWindowStartNs;

    PreviewRenderer() {
        nativeHandle = nativeCreate();
    }

    void destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0L;
        }
        nv21Buffers = null;
        resetFpsStats();
    }

    void setPreviewFpsListener(@Nullable PreviewFpsListener listener) {
        fpsListener = listener;
    }

    void resetFpsStats() {
        fpsFrameCount = 0L;
        fpsWindowStartNs = 0L;
    }

    void setPreviewSize(int width, int height) {
        setStreamSize(width, height);
    }

    @Override
    public void setStreamSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        synchronized (this) {
            frameWidth = width;
            frameHeight = height;
            int nv21Size = width * height * 3 / 2;
            nv21Buffers = new ByteBuffer[NV21_BUFFER_COUNT];
            for (int i = 0; i < NV21_BUFFER_COUNT; i++) {
                nv21Buffers[i] = ByteBuffer.allocateDirect(nv21Size);
            }
            displayIndex = 0;
            writeIndex = 1;
            prevDisplayIndex = -1;
            hasFrame = false;
        }
        pendingNativeResize = true;
    }

    @Override
    public void invalidatePreviewFrames() {
        synchronized (this) {
            hasFrame = false;
        }
    }

    @Override
    public void setPreviewStreaming(boolean streaming) {
        previewStreaming = streaming;
        if (!streaming) {
            invalidatePreviewFrames();
        }
    }

    boolean isPreviewStreaming() {
        return previewStreaming;
    }

    private void applyPendingNativeState() {
        if (nativeHandle == 0L) {
            return;
        }
        if (pendingNativeResize && frameWidth > 0 && frameHeight > 0) {
            nativeResize(nativeHandle, frameWidth, frameHeight);
            pendingNativeResize = false;
        }
        nativeSetDisplayTransform(nativeHandle, rotationDegrees, mirrorHorizontal);
    }

    @Override
    public void setDisplayTransform(int rotationDegrees, boolean mirrorHorizontal) {
        this.rotationDegrees = rotationDegrees;
        this.mirrorHorizontal = mirrorHorizontal;
    }

    @Override
    public int getStreamWidth() {
        return frameWidth;
    }

    @Override
    public int getStreamHeight() {
        return frameHeight;
    }

    @Nullable
    @Override
    public ByteBuffer getWriteBuffer() {
        synchronized (this) {
            ByteBuffer[] buffers = nv21Buffers;
            if (buffers == null) {
                return null;
            }
            return buffers[writeIndex];
        }
    }

    @Override
    public void onPreviewFrameAvailable() {
        acceptPreviewFrameIfValid();
    }

    /** @return true when a valid frame was queued for GL draw. */
    boolean acceptPreviewFrameIfValid() {
        if (!previewStreaming) {
            return false;
        }
        ByteBuffer written;
        int w;
        int h;
        synchronized (this) {
            w = frameWidth;
            h = frameHeight;
            ByteBuffer[] buffers = nv21Buffers;
            if (buffers == null || w <= 0 || h <= 0) {
                hasFrame = false;
                return false;
            }
            written = buffers[writeIndex];
            if (written == null) {
                hasFrame = false;
                return false;
            }
            if (!PreviewFrameValidator.hasRenderableLumaOrChroma(written, w, h)) {
                hasFrame = false;
                return false;
            }
            prevDisplayIndex = displayIndex;
            displayIndex = writeIndex;
            writeIndex = pickWriteIndex(displayIndex, prevDisplayIndex);
            hasFrame = true;
        }
        return true;
    }

    /** Picks a buffer index that is not currently displayed or the previous display slot. */
    private static int pickWriteIndex(int displayIndex, int prevDisplayIndex) {
        for (int i = 0; i < NV21_BUFFER_COUNT; i++) {
            if (i != displayIndex && i != prevDisplayIndex) {
                return i;
            }
        }
        return (displayIndex + 1) % NV21_BUFFER_COUNT;
    }

    @Nullable
    private ByteBuffer getReadBuffer() {
        synchronized (this) {
            ByteBuffer[] buffers = nv21Buffers;
            if (buffers == null) {
                return null;
            }
            return buffers[displayIndex];
        }
    }

    int getFrameWidth() {
        return frameWidth;
    }

    int getFrameHeight() {
        return frameHeight;
    }

    @Override
    public void onSurfaceCreated(@Nullable GL10 gl, @Nullable EGLConfig config) {
        if (nativeHandle != 0L) {
            nativeOnSurfaceCreated(nativeHandle);
            pendingNativeResize = true;
            applyPendingNativeState();
        }
    }

    @Override
    public void onSurfaceChanged(@Nullable GL10 gl, int width, int height) {
        if (nativeHandle != 0L) {
            nativeOnSurfaceChanged(nativeHandle, width, height);
        }
    }

    @Override
    public void onDrawFrame(@Nullable GL10 gl) {
        if (nativeHandle == 0L) {
            return;
        }
        applyPendingNativeState();
        if (!previewStreaming || !hasFrame) {
            nativeClearView(nativeHandle);
            return;
        }
        ByteBuffer read = getReadBuffer();
        if (read == null
                || frameWidth <= 0
                || frameHeight <= 0
                || !PreviewFrameValidator.hasRenderableLumaOrChroma(
                        read, frameWidth, frameHeight)) {
            nativeClearView(nativeHandle);
            return;
        }
        nativeOnDrawFrame(nativeHandle, read, frameWidth, frameHeight);
        recordDrawnFrame();
    }

    private void recordDrawnFrame() {
        fpsFrameCount++;
        long now = System.nanoTime();
        if (fpsWindowStartNs == 0L) {
            fpsWindowStartNs = now;
            return;
        }
        long elapsed = now - fpsWindowStartNs;
        if (elapsed < 500_000_000L) {
            return;
        }
        float fps = (float) (fpsFrameCount * 1_000_000_000L / (double) elapsed);
        fpsFrameCount = 0L;
        fpsWindowStartNs = now;
        PreviewFpsListener listener = fpsListener;
        if (listener != null) {
            listener.onPreviewFpsUpdated(fps);
        }
    }

    private static native long nativeCreate();

    private static native void nativeDestroy(long handle);

    private static native void nativeResize(long handle, int frameWidth, int frameHeight);

    private static native void nativeSetDisplayTransform(
            long handle, int rotationDegrees, boolean mirrorHorizontal);

    private static native void nativeOnSurfaceCreated(long handle);

    private static native void nativeOnSurfaceChanged(long handle, int viewWidth, int viewHeight);

    private static native void nativeClearView(long handle);

    private static native void nativeOnDrawFrame(
            long handle, @NonNull ByteBuffer nv21, int frameWidth, int frameHeight);
}
