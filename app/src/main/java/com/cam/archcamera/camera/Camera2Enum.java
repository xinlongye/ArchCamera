package com.cam.archcamera.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class Camera2Enum {

    private static final Comparator<Size> BY_AREA_DESC =
            (a, b) ->
                    Long.compare(
                            (long) b.getWidth() * b.getHeight(),
                            (long) a.getWidth() * a.getHeight());

    private Camera2Enum() {}

    @NonNull
    public static List<String> listCameraIds(@NonNull Context context) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return Collections.emptyList();
        }
        try {
            return Arrays.asList(cm.getCameraIdList());
        } catch (CameraAccessException e) {
            return Collections.emptyList();
        }
    }

    /** {@link CameraCharacteristics#LENS_FACING} value, or null if unavailable. */
    @Nullable
    public static Integer getLensFacing(@NonNull Context context, @NonNull String cameraId) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return null;
        }
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            return facing;
        } catch (CameraAccessException e) {
            return null;
        }
    }

    /**
     * First id from {@link CameraManager#getCameraIdList()} whose facing matches {@code lensFacing}.
     */
    @Nullable
    public static String firstCameraIdForFacing(@NonNull Context context, int lensFacing) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return null;
        }
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics chars = cm.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    return id;
                }
            }
        } catch (CameraAccessException ignored) {
        }
        return null;
    }

    @NonNull
    public static Size[] getPreviewSizes(@NonNull Context context, @NonNull String cameraId) {
        return getYuv420888OutputSizes(context, cameraId);
    }

    /** Sizes supported for {@link android.media.ImageReader} preview (YUV_420_888). */
    @NonNull
    public static Size[] getYuv420888OutputSizes(
            @NonNull Context context, @NonNull String cameraId) {
        return getOutputSizesForFormat(context, cameraId, ImageFormat.YUV_420_888);
    }

    @NonNull
    public static Size[] getJpegCaptureSizes(@NonNull Context context, @NonNull String cameraId) {
        return getOutputSizesForFormat(context, cameraId, ImageFormat.JPEG);
    }

    @NonNull
    public static String formatSize(@NonNull Size size) {
        return size.getWidth() + "×" + size.getHeight();
    }

    @NonNull
    private static Size[] getOutputSizesForFormat(
            @NonNull Context context, @NonNull String cameraId, int format) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            return new Size[0];
        }
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return new Size[0];
            }
            Size[] sizes = map.getOutputSizes(format);
            if (sizes == null || sizes.length == 0) {
                return new Size[0];
            }
            return sortedCopyOrEmpty(sizes);
        } catch (CameraAccessException e) {
            return new Size[0];
        }
    }

    @NonNull
    private static Size[] sortedCopyOrEmpty(@NonNull Size[] sizes) {
        Size[] copy = Arrays.copyOf(sizes, sizes.length);
        Arrays.sort(copy, BY_AREA_DESC);
        return copy;
    }
}
