package com.cam.archcamera.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Receives NV21 frames from the camera thread for GL rendering.
 */
public interface PreviewFrameSink {

    /** Buffer to write the next NV21 frame into (camera thread). */
    @Nullable
    ByteBuffer getWriteBuffer();

    /** Called after a frame is written; swaps buffers and schedules a GL draw. */
    void onPreviewFrameAvailable();

    /** Stream dimensions for the current NV21 layout. */
    int getStreamWidth();

    int getStreamHeight();

    /** Updates stream size and reallocates direct buffers (may be called from any thread). */
    void setStreamSize(int width, int height);

    void setDisplayTransform(int rotationDegrees, boolean mirrorHorizontal);

    /** Camera2 repeating preview is active; do not treat frames as valid before this is true. */
    void setPreviewStreaming(boolean streaming);

    /** Drops cached frames (call when preview stops or stream size changes). */
    void invalidatePreviewFrames();
}
