package com.cam.archcamera.camera;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.R;

/** Live or UI-derived 3A values for HUD and panel display. */
public final class Camera3ADisplayValues {

    public static final int UNKNOWN_FOCUS = -1;
    public static final float UNKNOWN_EV = Float.NaN;
    public static final int UNKNOWN_ISO = -1;
    public static final int UNKNOWN_SHUTTER_INDEX = -1;
    public static final int UNKNOWN_WB_K = -1;

    public enum WbSource {
        NONE,
        DNG,
        FALLBACK
    }

    public final int focusProgress;
    public final float ev;
    public final int iso;
    public final int shutterIndex;
    public final int colorTemperatureK;
    @NonNull public final WbSource wbSource;

    public Camera3ADisplayValues(
            int focusProgress,
            float ev,
            int iso,
            int shutterIndex,
            int colorTemperatureK) {
        this(focusProgress, ev, iso, shutterIndex, colorTemperatureK, WbSource.NONE);
    }

    public Camera3ADisplayValues(
            int focusProgress,
            float ev,
            int iso,
            int shutterIndex,
            int colorTemperatureK,
            @NonNull WbSource wbSource) {
        this.focusProgress = focusProgress;
        this.ev = ev;
        this.iso = iso;
        this.shutterIndex = shutterIndex;
        this.colorTemperatureK = colorTemperatureK;
        this.wbSource = wbSource;
    }

    public boolean isComplete() {
        return focusProgress >= 0
                && !Float.isNaN(ev)
                && iso > 0
                && shutterIndex >= 0
                && colorTemperatureK > 0;
    }

    /** True if any field changed beyond display thresholds. */
    public boolean changedMeaningfully(@Nullable Camera3ADisplayValues other) {
        if (other == null) {
            return true;
        }
        if (focusProgress >= 0
                && other.focusProgress >= 0
                && Math.abs(focusProgress - other.focusProgress) >= 1) {
            return true;
        }
        if (!Float.isNaN(ev)
                && !Float.isNaN(other.ev)
                && Math.abs(ev - other.ev) >= 0.05f) {
            return true;
        }
        if (iso > 0 && other.iso > 0 && Math.abs(iso - other.iso) >= 1) {
            return true;
        }
        if (shutterIndex >= 0
                && other.shutterIndex >= 0
                && shutterIndex != other.shutterIndex) {
            return true;
        }
        if (colorTemperatureK > 0
                && other.colorTemperatureK > 0
                && Math.abs(colorTemperatureK - other.colorTemperatureK) >= 100) {
            return true;
        }
        if (wbSource != other.wbSource) {
            return true;
        }
        return false;
    }

    @NonNull
    public Camera3ADisplayValues merge(
            @NonNull Camera3ADisplayValues panel,
            boolean proFocusManual,
            boolean proEvManual,
            boolean proIsoManual,
            boolean proShutterManual,
            boolean proWbManual,
            boolean professionalMode) {
        if (!professionalMode) {
            return this;
        }
        return new Camera3ADisplayValues(
                proFocusManual && panel.focusProgress >= 0 ? panel.focusProgress : focusProgress,
                proEvManual && !Float.isNaN(panel.ev) ? panel.ev : ev,
                proIsoManual && panel.iso > 0 ? panel.iso : iso,
                proShutterManual && panel.shutterIndex >= 0 ? panel.shutterIndex : shutterIndex,
                proWbManual && panel.colorTemperatureK > 0
                        ? panel.colorTemperatureK
                        : colorTemperatureK,
                proWbManual ? panel.wbSource : wbSource);
    }

    @NonNull
    public String formatHudLines(@NonNull Context context) {
        return context.getString(
                R.string.camera_3a_hud_format,
                Camera3AFormatters.formatFocus(focusProgress),
                Camera3AFormatters.formatEv(ev),
                Camera3AFormatters.formatIso(iso),
                Camera3AFormatters.formatShutter(shutterIndex),
                Camera3AFormatters.formatWb(colorTemperatureK, wbSource));
    }
}
