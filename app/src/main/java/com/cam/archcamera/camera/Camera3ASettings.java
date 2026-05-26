package com.cam.archcamera.camera;

import androidx.annotation.NonNull;

/** User-facing 3A (AF / AE / AWB) settings for professional mode. */
public final class Camera3ASettings {

    public boolean focusManual;
    /** 0 = infinity, 1 = closest focus. */
    public float focusDistanceNorm = 0.5f;

    public boolean evManual;
    public float evCompensation;

    public boolean isoManual;
    public int iso = 400;

    public boolean shutterManual;
    public int shutterIndex = 12;
    public long exposureTimeNs;

    public boolean wbManual;
    public int colorTemperatureK = 5200;

    @NonNull
    public static Camera3ASettings autoDefaults() {
        Camera3ASettings s = new Camera3ASettings();
        s.focusManual = false;
        s.focusDistanceNorm = 0.5f;
        s.evManual = false;
        s.evCompensation = 0f;
        s.isoManual = false;
        s.iso = 400;
        s.shutterManual = false;
        s.shutterIndex = 12;
        s.exposureTimeNs = ShutterStopTable.indexToExposureTimeNs(12, null);
        s.wbManual = false;
        s.colorTemperatureK = 5200;
        return s;
    }

    @NonNull
    public static Camera3ASettings copyOf(@NonNull Camera3ASettings other) {
        Camera3ASettings s = new Camera3ASettings();
        s.focusManual = other.focusManual;
        s.focusDistanceNorm = other.focusDistanceNorm;
        s.evManual = other.evManual;
        s.evCompensation = other.evCompensation;
        s.isoManual = other.isoManual;
        s.iso = other.iso;
        s.shutterManual = other.shutterManual;
        s.shutterIndex = other.shutterIndex;
        s.exposureTimeNs = other.exposureTimeNs;
        s.wbManual = other.wbManual;
        s.colorTemperatureK = other.colorTemperatureK;
        return s;
    }

    public boolean isManualExposureActive() {
        return isoManual || shutterManual;
    }
}
