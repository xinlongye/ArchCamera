package com.cam.archcamera.preview;

import android.opengl.GLSurfaceView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.camera.PreviewFrameSink;
import com.cam.archcamera.camera.PreviewFrameValidator;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

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

    private long nativeHandle;
    @Nullable private ByteBuffer buffer0;
    @Nullable private ByteBuffer buffer1;
    private final AtomicInteger writeIndex = new AtomicInteger(0);
    private final AtomicInteger readIndex = new AtomicInteger(0);
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
        buffer0 = null;
        buffer1 = null;
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
            buffer0 = ByteBuffer.allocateDirect(nv21Size);
            buffer1 = ByteBuffer.allocateDirect(nv21Size);
            writeIndex.set(0);
            readIndex.set(0);
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
            int wi = writeIndex.get();
            return wi == 0 ? buffer0 : buffer1;
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
            int wi = writeIndex.get();
            written = wi == 0 ? buffer0 : buffer1;
            if (written == null || w <= 0 || h <= 0) {
                hasFrame = false;
                return false;
            }
            if (!PreviewFrameValidator.hasRenderableLumaOrChroma(written, w, h)) {
                hasFrame = false;
                return false;
            }
            readIndex.set(wi);
            writeIndex.set(wi == 0 ? 1 : 0);
            hasFrame = true;
        }
        return true;
    }

    @Nullable
    private ByteBuffer getReadBuffer() {
        synchronized (this) {
            int ri = readIndex.get();
            return ri == 0 ? buffer0 : buffer1;
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
        Log.d(TAG, String.format("preview fps: %.1f", fps));
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
