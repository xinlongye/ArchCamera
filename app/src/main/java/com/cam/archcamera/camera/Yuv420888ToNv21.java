package com.cam.archcamera.camera;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

/**
 * Copies {@link ImageFormat#YUV_420_888} into compact NV21 (Y + interleaved VU).
 * Respects {@link Image#getCropRect()} and per-plane row/pixel stride (required for Camera2).
 */
public final class Yuv420888ToNv21 {

    static {
        System.loadLibrary("archcamera");
    }

    private Yuv420888ToNv21() {}

    /**
     * @param outWidth  expected width (must match {@link Image#getCropRect()})
     * @param outHeight expected height
     * @param out       direct buffer with capacity at least {@code outWidth * outHeight * 3 / 2}
     */
    public static void convert(
            @NonNull Image image, int outWidth, int outHeight, @NonNull ByteBuffer out) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Expected YUV_420_888");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length < 3) {
            throw new IllegalArgumentException("Expected 3 planes");
        }

        Rect crop = image.getCropRect();
        int width = crop.width();
        int height = crop.height();
        if (width != outWidth || height != outHeight) {
            throw new IllegalArgumentException(
                    "Crop "
                            + width
                            + "x"
                            + height
                            + " != expected "
                            + outWidth
                            + "x"
                            + outHeight);
        }

        out.clear();
        int ySize = width * height;

        Image.Plane yPlane = planes[0];
        Image.Plane uPlane = planes[1];
        Image.Plane vPlane = planes[2];

        ByteBuffer yBuf = yPlane.getBuffer();
        ByteBuffer uBuf = uPlane.getBuffer();
        ByteBuffer vBuf = vPlane.getBuffer();
        yBuf.rewind();
        uBuf.rewind();
        vBuf.rewind();

        if (yBuf.isDirect() && uBuf.isDirect() && vBuf.isDirect() && out.isDirect()) {
            nativeConvert(
                    yBuf,
                    yPlane.getRowStride(),
                    yPlane.getPixelStride(),
                    uBuf,
                    uPlane.getRowStride(),
                    uPlane.getPixelStride(),
                    vBuf,
                    vPlane.getRowStride(),
                    vPlane.getPixelStride(),
                    crop.left,
                    crop.top,
                    width,
                    height,
                    out);
            out.position(0);
            out.limit(ySize + ySize / 2);
            return;
        }

        copyY(
                yBuf,
                out,
                crop,
                width,
                height,
                yPlane.getRowStride(),
                yPlane.getPixelStride());
        out.position(ySize);
        copyVu(
                uBuf,
                vBuf,
                out,
                crop,
                width,
                height,
                uPlane.getRowStride(),
                uPlane.getPixelStride(),
                vPlane.getRowStride(),
                vPlane.getPixelStride());
        out.position(0);
        out.limit(ySize + ySize / 2);
    }

    private static native void nativeConvert(
            ByteBuffer y,
            int yRowStride,
            int yPixelStride,
            ByteBuffer u,
            int uRowStride,
            int uPixelStride,
            ByteBuffer v,
            int vRowStride,
            int vPixelStride,
            int cropLeft,
            int cropTop,
            int width,
            int height,
            ByteBuffer out);

    private static void copyY(
            ByteBuffer y,
            ByteBuffer out,
            Rect crop,
            int width,
            int height,
            int rowStride,
            int pixelStride) {
        byte[] row = new byte[width];
        int yOffset = rowStride * crop.top + pixelStride * crop.left;
        for (int rowIdx = 0; rowIdx < height; rowIdx++) {
            int rowBase = yOffset + rowIdx * rowStride;
            if (pixelStride == 1) {
                y.position(rowBase);
                y.get(row, 0, width);
            } else {
                for (int col = 0; col < width; col++) {
                    row[col] = y.get(rowBase + col * pixelStride);
                }
            }
            out.put(row, 0, width);
        }
    }

    /** NV21 chroma: interleaved V then U per 2x2 block. */
    private static void copyVu(
            ByteBuffer u,
            ByteBuffer v,
            ByteBuffer out,
            Rect crop,
            int width,
            int height,
            int uRowStride,
            int uPixelStride,
            int vRowStride,
            int vPixelStride) {
        int uvHeight = height / 2;
        int uvWidth = width / 2;
        int uvLeft = crop.left / 2;
        int uvTop = crop.top / 2;

        for (int row = 0; row < uvHeight; row++) {
            int vRowBase = (uvTop + row) * vRowStride + uvLeft * vPixelStride;
            int uRowBase = (uvTop + row) * uRowStride + uvLeft * uPixelStride;
            for (int col = 0; col < uvWidth; col++) {
                int vIndex = vRowBase + col * vPixelStride;
                int uIndex = uRowBase + col * uPixelStride;
                out.put(v.get(vIndex));
                out.put(u.get(uIndex));
            }
        }
    }
}
