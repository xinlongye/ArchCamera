package com.cam.archcamera.camera;

import androidx.annotation.NonNull;

/** Shared formatting for 3A panel value labels and HUD lines. */
public final class Camera3AFormatters {

    private Camera3AFormatters() {}

    @NonNull
    public static String formatFocus(int progress) {
        return progress >= 0 ? String.valueOf(progress) : "—";
    }

    @NonNull
    public static String formatEv(float ev) {
        return Float.isNaN(ev) ? "—" : ShutterStopTable.formatEv(ev);
    }

    @NonNull
    public static String formatIso(int iso) {
        return iso > 0 ? String.valueOf(iso) : "—";
    }

    @NonNull
    public static String formatShutter(int index) {
        return index >= 0 ? ShutterStopTable.label(index) : "—";
    }

    @NonNull
    public static String formatWb(int kelvin) {
        return kelvin > 0 ? kelvin + " K" : "—";
    }

    @NonNull
    public static String formatWb(int kelvin, @NonNull Camera3ADisplayValues.WbSource source) {
        if (kelvin <= 0) {
            return "—";
        }
        switch (source) {
            case DNG:
                return kelvin + " K (dng)";
            case FALLBACK:
                return kelvin + " K (fallback)";
            default:
                return kelvin + " K";
        }
    }

    @NonNull
    public static Camera3ADisplayValues fromSettings(@NonNull Camera3ASettings settings) {
        return new Camera3ADisplayValues(
                Math.round(settings.focusDistanceNorm * 100f),
                settings.evCompensation,
                settings.iso,
                settings.shutterIndex,
                settings.colorTemperatureK,
                Camera3ADisplayValues.WbSource.NONE);
    }
}
