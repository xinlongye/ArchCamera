package com.cam.archcamera.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.preview.PreviewSizeLabel;

/**
 * Resolves a prefs / policy preview size to a supported YUV_420_888 stream size (even dimensions).
 */
public final class PreviewStreamSizeResolver {

    private PreviewStreamSizeResolver() {}

    /** YUV / NV21 平面要求像素尺寸为偶数；将单维向下取整到最近偶数。 */
    public static int ensureEvenDimension(int dim) {
        int even = dim & ~1;
        return even > 0 ? even : dim;
    }

    @NonNull
    public static Size ensureEven(@NonNull Size size) {
        int w = ensureEvenDimension(size.getWidth());
        int h = ensureEvenDimension(size.getHeight());
        if (w <= 0 || h <= 0) {
            return PreviewSizeLabel.fallback();
        }
        return new Size(w, h);
    }

    /**
     * Picks {@code preferred} if listed; otherwise closest aspect match in {@code supported}.
     */
    @NonNull
    public static Size resolve(
            @NonNull Context context,
            @NonNull String cameraId,
            @Nullable Size preferred) {
        Size[] supported = Camera2Enum.getYuv420888OutputSizes(context, cameraId);
        StreamConfigurationMap map = getStreamConfigurationMap(context, cameraId);
        if (supported.length == 0) {
            return ensureEven(preferred != null ? preferred : PreviewSizeLabel.fallback());
        }
        Size resolved;
        if (preferred != null) {
            Size even = ensureEven(preferred);
            for (Size s : supported) {
                if (s.getWidth() == even.getWidth() && s.getHeight() == even.getHeight()) {
                    resolved = even;
                    return PreviewTargetFps.ensureSupportsTargetFps(supported, map, resolved);
                }
            }
            float target = AspectResolutionSelector.longPerShortRatio(even);
            Size picked =
                    AspectResolutionSelector.pickPreviewForTargetRatio(supported, target, 0L);
            if (picked != null) {
                resolved = ensureEven(picked);
            } else {
                resolved = even;
            }
        } else {
            Size fallback =
                    AspectResolutionSelector.pickPreviewForTargetRatio(supported, 4f / 3f, 0L);
            resolved = ensureEven(fallback != null ? fallback : supported[0]);
        }
        return PreviewTargetFps.ensureSupportsTargetFps(supported, map, resolved);
    }

    @Nullable
    private static StreamConfigurationMap getStreamConfigurationMap(
            @NonNull Context context, @NonNull String cameraId) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return null;
        }
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            return PreviewTargetFps.getStreamConfigurationMap(chars);
        } catch (CameraAccessException e) {
            return null;
        }
    }
}
