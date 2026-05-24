package com.cam.archcamera.camera;

import android.content.Context;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.preview.PreviewSizeLabel;

/** Resolves a prefs capture size to a supported JPEG stream size. */
public final class CaptureStreamSizeResolver {

    private CaptureStreamSizeResolver() {}

    @NonNull
    public static Size resolve(
            @NonNull Context context, @NonNull String cameraId, @Nullable Size preferred) {
        Size[] supported = Camera2Enum.getJpegCaptureSizes(context, cameraId);
        if (supported.length == 0) {
            return PreviewStreamSizeResolver.ensureEven(
                    preferred != null ? preferred : PreviewSizeLabel.fallback());
        }
        if (preferred != null) {
            Size even = PreviewStreamSizeResolver.ensureEven(preferred);
            for (Size s : supported) {
                if (s.getWidth() == even.getWidth() && s.getHeight() == even.getHeight()) {
                    return even;
                }
            }
            float target = AspectResolutionSelector.longPerShortRatio(even);
            Size picked = AspectResolutionSelector.pickCaptureForTargetRatio(supported, target);
            if (picked != null) {
                return PreviewStreamSizeResolver.ensureEven(picked);
            }
            return even;
        }
        Size fallback = AspectResolutionSelector.pickCaptureForTargetRatio(supported, 4f / 3f);
        return PreviewStreamSizeResolver.ensureEven(
                fallback != null ? fallback : supported[0]);
    }
}
