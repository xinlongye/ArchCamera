package com.cam.archcamera.camera;

import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

/**
 * Converts {@link ImageReader} YUV_420_888 frames into NV21 and hands them to a {@link PreviewFrameSink}.
 */
final class PreviewImageAvailableListener implements ImageReader.OnImageAvailableListener {

    private static final String TAG = "PreviewImageAvail";

    private final PreviewFrameSink sink;
    private final int streamWidth;
    private final int streamHeight;

    PreviewImageAvailableListener(
            @NonNull PreviewFrameSink sink, int streamWidth, int streamHeight) {
        this.sink = sink;
        this.streamWidth = streamWidth;
        this.streamHeight = streamHeight;
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        try {
            if (image.getFormat() != android.graphics.ImageFormat.YUV_420_888) {
                return;
            }
            Rect crop = image.getCropRect();
            int w = crop.width();
            int h = crop.height();
            if (w != streamWidth || h != streamHeight) {
                Log.w(
                        TAG,
                        "Frame crop "
                                + w
                                + "x"
                                + h
                                + " != stream "
                                + streamWidth
                                + "x"
                                + streamHeight);
                return;
            }
            ByteBuffer write = sink.getWriteBuffer();
            if (write == null || w <= 0 || h <= 0) {
                return;
            }
            Yuv420888ToNv21.convert(image, w, h, write);
            if (!PreviewFrameValidator.hasRenderableLumaOrChroma(write, w, h)) {
                return;
            }
            sink.onPreviewFrameAvailable();
        } catch (RuntimeException e) {
            Log.e(TAG, "Frame conversion failed", e);
        } finally {
            image.close();
        }
    }
}
