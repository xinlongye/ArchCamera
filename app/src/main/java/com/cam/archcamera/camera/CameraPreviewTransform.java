package com.cam.archcamera.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.settings.PhotoSavePrefs;

/**
 * Preview display rotation (degrees) and front-camera mirror for native texture transform.
 */
public final class CameraPreviewTransform {

    private CameraPreviewTransform() {}

    public static final class Result {
        public final int rotationDegrees;
        public final boolean mirrorHorizontal;

        public Result(int rotationDegrees, boolean mirrorHorizontal) {
            this.rotationDegrees = rotationDegrees;
            this.mirrorHorizontal = mirrorHorizontal;
        }
    }

    @NonNull
    public static Result compute(@NonNull Context context, @NonNull String cameraId) {
        int sensorOrientation = 0;
        Integer facing = null;
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm != null) {
            try {
                CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
                Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (so != null) {
                    sensorOrientation = so;
                }
                facing = chars.get(CameraCharacteristics.LENS_FACING);
            } catch (Exception ignored) {
            }
        }

        int displayRotation = Surface.ROTATION_0;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            displayRotation = wm.getDefaultDisplay().getRotation();
        }
        int displayDegrees;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                displayDegrees = 90;
                break;
            case Surface.ROTATION_180:
                displayDegrees = 180;
                break;
            case Surface.ROTATION_270:
                displayDegrees = 270;
                break;
            default:
                displayDegrees = 0;
                break;
        }

        // Same as Android computeRelativeRotation(): front sign=+1, back sign=-1.
        int sign =
                facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT ? 1 : -1;
        int rotation = (sensorOrientation - displayDegrees * sign + 360) % 360;

        boolean mirror =
                facing != null
                        && facing == CameraCharacteristics.LENS_FACING_FRONT
                        && PhotoSavePrefs.getMirrorFrontEnabled(context);

        // Horizontal mirror is applied in GLES after rotation; invert rotation so preview stays upright.
        if (mirror) {
            rotation = (360 - rotation) % 360;
        }

        return new Result(rotation, mirror);
    }
}
