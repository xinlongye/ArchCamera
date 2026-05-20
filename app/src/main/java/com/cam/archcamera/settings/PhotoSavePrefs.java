package com.cam.archcamera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class PhotoSavePrefs {

    private static final String PREFS = "arch_camera_settings";
    private static final String KEY_SAVE_DIR = "photo_save_directory_abs";
    private static final String KEY_SELECTED_CAMERA_ID = "selected_camera_id";
    private static final String KEY_PREVIEW_SIZE_LABEL = "preview_size_label";
    private static final String KEY_CAPTURE_SIZE_LABEL = "capture_size_label";
    /** Legacy int slot 0–3; removed after {@link #migrateLegacyCameraIndexIfNeeded}. */
    private static final String KEY_CAMERA_ID_INDEX = "camera_id_index";

    private PhotoSavePrefs() {}

    @NonNull
    public static String defaultSaveDirectory() {
        return new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                        "Camera")
                .getAbsolutePath();
    }

    @NonNull
    public static String getSaveDirectoryAbsolute(@NonNull Context context) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_SAVE_DIR, null);
        if (raw == null || raw.isBlank()) {
            return defaultSaveDirectory();
        }
        try {
            return new File(raw.trim()).getCanonicalPath();
        } catch (IOException e) {
            return raw.trim();
        }
    }

    public static void setSaveDirectoryAbsolute(@NonNull Context context, @NonNull String absolutePath) {
        SharedPreferences p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String normalized = absolutePath.trim();
        try {
            normalized = new File(normalized).getCanonicalPath();
        } catch (IOException ignored) {
        }
        p.edit().putString(KEY_SAVE_DIR, normalized).apply();
    }

    /**
     * If there is no persisted camera id but legacy index exists, map it to {@code cameraIds} and
     * drop the old key.
     */
    public static void migrateLegacyCameraIndexIfNeeded(
            @NonNull Context context, @NonNull List<String> cameraIds) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(KEY_SELECTED_CAMERA_ID)) {
            return;
        }
        if (!p.contains(KEY_CAMERA_ID_INDEX)) {
            return;
        }
        int idx = p.getInt(KEY_CAMERA_ID_INDEX, 0);
        SharedPreferences.Editor ed = p.edit().remove(KEY_CAMERA_ID_INDEX);
        if (!cameraIds.isEmpty()) {
            int clamped = Math.max(0, Math.min(cameraIds.size() - 1, idx));
            ed.putString(KEY_SELECTED_CAMERA_ID, cameraIds.get(clamped));
        }
        ed.apply();
    }

    @Nullable
    public static String getSelectedCameraId(@NonNull Context context) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_SELECTED_CAMERA_ID, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public static void setSelectedCameraId(@NonNull Context context, @NonNull String cameraId) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_SELECTED_CAMERA_ID, cameraId.trim()).apply();
    }

    @Nullable
    public static String getPreviewSizeLabel(@NonNull Context context) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_PREVIEW_SIZE_LABEL, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public static void setPreviewSizeLabel(@NonNull Context context, @NonNull String label) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_PREVIEW_SIZE_LABEL, label.trim()).apply();
    }

    @Nullable
    public static String getCaptureSizeLabel(@NonNull Context context) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = p.getString(KEY_CAPTURE_SIZE_LABEL, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    public static void setCaptureSizeLabel(@NonNull Context context, @NonNull String label) {
        SharedPreferences p =
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY_CAPTURE_SIZE_LABEL, label.trim()).apply();
    }
}
