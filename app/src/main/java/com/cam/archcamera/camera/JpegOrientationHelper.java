package com.cam.archcamera.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** {@link android.hardware.camera2.CaptureRequest#JPEG_ORIENTATION} for still capture. */
public final class JpegOrientationHelper {

    private static final Map<Integer, Integer> DISPLAY_TO_DEGREES = new HashMap<>();

    static {
        DISPLAY_TO_DEGREES.put(Surface.ROTATION_0, 0);
        DISPLAY_TO_DEGREES.put(Surface.ROTATION_90, 90);
        DISPLAY_TO_DEGREES.put(Surface.ROTATION_180, 180);
        DISPLAY_TO_DEGREES.put(Surface.ROTATION_270, 270);
    }

    private JpegOrientationHelper() {}

    public static int computeJpegOrientation(@NonNull Context context, @NonNull String cameraId) {
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
            } catch (CameraAccessException ignored) {
            }
        }

        int displayRotation = Surface.ROTATION_0;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            displayRotation = wm.getDefaultDisplay().getRotation();
        }
        Integer deviceRotation = DISPLAY_TO_DEGREES.get(displayRotation);
        int deviceDegrees = deviceRotation != null ? deviceRotation : 0;

        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
            int result = (sensorOrientation + deviceDegrees) % 360;
            return (360 - result) % 360;
        }
        return (sensorOrientation - deviceDegrees + 360) % 360;
    }
}
