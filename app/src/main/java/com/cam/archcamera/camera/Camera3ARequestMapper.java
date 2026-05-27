package com.cam.archcamera.camera;

import android.hardware.camera2.CaptureRequest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Maps {@link Camera3ASettings} to Camera2 {@link CaptureRequest} keys.
 *
 * <p>AE rules in professional mode:
 * <ul>
 *   <li>ISO and shutter both auto → {@code AE_MODE_ON}; EV manual applies compensation.</li>
 *   <li>Either ISO or shutter manual → {@code AE_MODE_OFF} with manual sensor values; EV ignored.</li>
 * </ul>
 */
public final class Camera3ARequestMapper {

    private Camera3ARequestMapper() {}

    public static void apply(
            @NonNull CaptureRequest.Builder builder,
            @NonNull Camera3ASettings settings,
            @Nullable Camera3ACapabilities caps,
            boolean professionalMode) {
        Camera3ACapabilities c = caps != null ? caps : Camera3ACapabilities.defaults();

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        if (!professionalMode) {
            applyAuto3A(builder);
            return;
        }

        applyFocus(builder, settings, c);
        applyExposure(builder, settings, c);
        applyWhiteBalance(builder, settings, c);
    }

    private static void applyAuto3A(@NonNull CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
    }

    private static void applyFocus(
            @NonNull CaptureRequest.Builder builder,
            @NonNull Camera3ASettings settings,
            @NonNull Camera3ACapabilities caps) {
        if (settings.focusManual && caps.canManualFocus()) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    caps.normToFocusDistance(settings.focusDistanceNorm));
        } else {
            builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    }

    private static void applyExposure(
            @NonNull CaptureRequest.Builder builder,
            @NonNull Camera3ASettings settings,
            @NonNull Camera3ACapabilities caps) {
        boolean manualExposure = settings.isoManual || settings.shutterManual;

        if (!manualExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (settings.evManual) {
                builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        caps.evToAeCompensationIndex(settings.evCompensation));
            } else {
                builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        caps.evToAeCompensationIndex(0f));
            }
            return;
        }

        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);

        if (settings.isoManual) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, caps.clampIso(settings.iso));
        } else {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, caps.clampIso(400));
        }

        long exposureNs = settings.exposureTimeNs;
        if (exposureNs <= 0L) {
            exposureNs =
                    ShutterStopTable.indexToExposureTimeNs(
                            settings.shutterIndex, caps.exposureTimeRange);
        }
        builder.set(
                CaptureRequest.SENSOR_EXPOSURE_TIME, caps.clampExposureTimeNs(exposureNs));
    }

    private static void applyWhiteBalance(
            @NonNull CaptureRequest.Builder builder,
            @NonNull Camera3ASettings settings,
            @NonNull Camera3ACapabilities caps) {
        if (settings.wbManual && caps.canManualWb()) {
            ColorTemperatureMapper.applyManualWb(builder, settings.colorTemperatureK, caps.dngWbModel);
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
    }
}
