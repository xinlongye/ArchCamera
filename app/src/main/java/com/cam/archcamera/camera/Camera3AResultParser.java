package com.cam.archcamera.camera;

import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Reads actual 3A state from a preview {@link TotalCaptureResult}. */
public final class Camera3AResultParser {

    private Camera3AResultParser() {}

    @NonNull
    public static Camera3ADisplayValues parse(
            @NonNull TotalCaptureResult result, @Nullable Camera3ACapabilities caps) {
        Camera3ACapabilities c = caps != null ? caps : Camera3ACapabilities.defaults();

        int focusProgress = Camera3ADisplayValues.UNKNOWN_FOCUS;
        Float focusDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (focusDist != null && c.minimumFocusDistance > 0f) {
            float d = focusDist;
            if (d <= 0f) {
                focusProgress = 0;
            } else {
                float norm = 1f - d / c.minimumFocusDistance;
                focusProgress = Math.round(Math.max(0f, Math.min(1f, norm)) * 100f);
            }
        }

        float ev = Camera3ADisplayValues.UNKNOWN_EV;
        Integer evIndex = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (evIndex != null) {
            ev = c.aeCompensationIndexToEv(evIndex);
        }

        int iso = Camera3ADisplayValues.UNKNOWN_ISO;
        Integer sensitivity = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sensitivity != null && sensitivity > 0) {
            iso = c.clampIso(sensitivity);
        }

        int shutterIndex = Camera3ADisplayValues.UNKNOWN_SHUTTER_INDEX;
        Long exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exposureNs != null && exposureNs > 0L) {
            shutterIndex =
                    ShutterStopTable.exposureTimeNsToIndex(exposureNs, c.exposureTimeRange);
        }

        int wbK = Camera3ADisplayValues.UNKNOWN_WB_K;
        if (Build.VERSION.SDK_INT >= 36) {
            Integer cct = result.get(CaptureResult.COLOR_CORRECTION_COLOR_TEMPERATURE);
            if (cct != null && cct >= 2000) {
                wbK = Math.max(2000, Math.min(10000, cct));
            }
        }
        if (wbK <= 0) {
            RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (gains != null) {
                Integer estimated = ColorTemperatureMapper.estimateKelvinFromGains(gains);
                if (estimated != null) {
                    wbK = estimated;
                }
            }
        }

        return new Camera3ADisplayValues(focusProgress, ev, iso, shutterIndex, wbK);
    }
}
