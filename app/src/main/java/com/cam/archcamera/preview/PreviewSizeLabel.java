package com.cam.archcamera.preview;

import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Parses preview size labels persisted as {@code width×height} (e.g. from {@link
 * com.cam.archcamera.camera.Camera2Enum#formatSize}).
 */
public final class PreviewSizeLabel {

    private static final int FALLBACK_WIDTH = 1440;
    private static final int FALLBACK_HEIGHT = 1080;

    private PreviewSizeLabel() {}

    @NonNull
    public static Size fallback() {
        return new Size(FALLBACK_WIDTH, FALLBACK_HEIGHT);
    }

    /**
     * @param label e.g. {@code "1440×1080"} or {@code "1440x1080"}
     * @return parsed size or {@code null} if invalid
     */
    @Nullable
    public static Size parse(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        int sep = trimmed.indexOf('×');
        if (sep < 0) {
            sep = trimmed.indexOf('x');
        }
        if (sep < 0) {
            sep = trimmed.indexOf('X');
        }
        if (sep <= 0 || sep >= trimmed.length() - 1) {
            return null;
        }
        try {
            int w = Integer.parseInt(trimmed.substring(0, sep).trim());
            int h = Integer.parseInt(trimmed.substring(sep + 1).trim());
            if (w <= 0 || h <= 0) {
                return null;
            }
            return new Size(w, h);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NonNull
    public static Size parseOrFallback(@Nullable String label) {
        Size s = parse(label);
        return s != null ? s : fallback();
    }
}
