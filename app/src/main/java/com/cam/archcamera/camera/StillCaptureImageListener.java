package com.cam.archcamera.camera;

import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/** Delivers one JPEG payload from a still-capture {@link ImageReader}. */
final class StillCaptureImageListener implements ImageReader.OnImageAvailableListener {

    private static final String TAG = "StillCaptureImage";

    @NonNull private final Consumer<byte[]> onJpegAvailable;

    StillCaptureImageListener(@NonNull Consumer<byte[]> onJpegAvailable) {
        this.onJpegAvailable = onJpegAvailable;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            if (image.getFormat() != android.graphics.ImageFormat.JPEG) {
                Log.w(TAG, "Unexpected format " + image.getFormat());
                return;
            }
            byte[] jpeg = readJpegBytes(image);
            if (jpeg.length > 0) {
                onJpegAvailable.accept(jpeg);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Still JPEG read failed", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @NonNull
    private static byte[] readJpegBytes(@NonNull Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
