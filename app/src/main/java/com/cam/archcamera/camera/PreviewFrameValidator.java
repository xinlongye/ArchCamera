package com.cam.archcamera.camera;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Detects uninitialized NV21 (Y=0 and VU=0) that the GLES shader would show as solid green.
 */
public final class PreviewFrameValidator {

    private PreviewFrameValidator() {}

    /**
     * @return true if the buffer looks like real camera output, not a zero-filled placeholder.
     */
    public static boolean hasRenderableLumaOrChroma(
            @Nullable ByteBuffer nv21, int width, int height) {
        if (nv21 == null || width <= 0 || height <= 0) {
            return false;
        }
        int ySize = width * height;
        int nv21Size = ySize + ySize / 2;
        if (nv21.capacity() < nv21Size) {
            return false;
        }
        int pos = nv21.position();
        try {
            nv21.position(0);
            int yStep = Math.max(1, ySize / 512);
            for (int i = 0; i < ySize; i += yStep) {
                if ((nv21.get(i) & 0xFF) != 0) {
                    return true;
                }
            }
            int cStep = Math.max(1, ySize / 512);
            for (int i = ySize; i < ySize + ySize / 2; i += cStep) {
                if ((nv21.get(i) & 0xFF) != 0) {
                    return true;
                }
            }
            return false;
        } finally {
            nv21.position(pos);
        }
    }
}
