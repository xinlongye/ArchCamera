package com.cam.archcamera.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.util.Range;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Device limits for 3A (AF/AE/AWB) controls from CameraCharacteristics. */
public final class Camera3ACapabilities {

    @NonNull public final Range<Integer> aeCompensationRange;
    @NonNull public final Rational aeCompensationStep;
    @NonNull public final Range<Integer> isoRange;
    @NonNull public final Range<Long> exposureTimeRange;
    public final float minimumFocusDistance;
    public final boolean manualFocusSupported;
    public final boolean manualWbSupported;
    @Nullable public final DngWbModel dngWbModel;
    public final int isoStep;
    public final int evSeekMax;
    public final int isoSeekMax;
    public final int wbSeekMax;

    private Camera3ACapabilities(
            @NonNull Range<Integer> aeCompensationRange,
            @NonNull Rational aeCompensationStep,
            @NonNull Range<Integer> isoRange,
            @NonNull Range<Long> exposureTimeRange,
            float minimumFocusDistance,
            boolean manualFocusSupported,
            boolean manualWbSupported,
            @Nullable DngWbModel dngWbModel) {
        this.aeCompensationRange = aeCompensationRange;
        this.aeCompensationStep = aeCompensationStep;
        this.isoRange = isoRange;
        this.exposureTimeRange = exposureTimeRange;
        this.minimumFocusDistance = minimumFocusDistance;
        this.manualFocusSupported = manualFocusSupported;
        this.manualWbSupported = manualWbSupported;
        this.dngWbModel = dngWbModel;

        float stepEv = aeCompensationStep.floatValue();
        int evSteps =
                Math.round(
                        (aeCompensationRange.getUpper() - aeCompensationRange.getLower())
                                * stepEv
                                / 0.1f);
        this.evSeekMax = Math.max(0, evSteps);

        int isoMin = isoRange.getLower();
        int isoMax = isoRange.getUpper();
        this.isoStep = 50;
        this.isoSeekMax = Math.max(0, (isoMax - isoMin) / isoStep);

        this.wbSeekMax = (10000 - 2000) / 100;
    }

    @NonNull
    public static Camera3ACapabilities from(@NonNull Context context, @NonNull String cameraId)
            throws CameraAccessException {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return defaults();
        }
        CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);

        Range<Integer> aeRange =
                chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (aeRange == null) {
            aeRange = new Range<>(-3, 3);
        }
        Rational aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (aeStep == null) {
            aeStep = new Rational(1, 3);
        }

        Range<Integer> iso =
                chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (iso == null) {
            iso = new Range<>(50, 6400);
        }

        Range<Long> exposure =
                chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (exposure == null) {
            exposure = new Range<>(125_000L, 30_000_000_000L);
        }

        Float minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        float minFocusDist = minFocus != null ? minFocus : 0f;

        int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        boolean manualAf =
                afModes != null
                        && contains(afModes, CaptureRequest.CONTROL_AF_MODE_OFF);

        int[] awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        boolean manualWb =
                awbModes != null
                        && contains(awbModes, CaptureRequest.CONTROL_AWB_MODE_OFF);

        return new Camera3ACapabilities(
                aeRange, aeStep, iso, exposure, minFocusDist, manualAf, manualWb, DngWbModel.tryFrom(chars));
    }

    @NonNull
    public static Camera3ACapabilities defaults() {
        return new Camera3ACapabilities(
                new Range<>(-3, 3),
                new Rational(1, 3),
                new Range<>(50, 6400),
                new Range<>(125_000L, 30_000_000_000L),
                0f,
                false,
                false,
                null);
    }

    @Nullable
    public static Camera3ACapabilities tryFrom(@NonNull Context context, @NonNull String cameraId) {
        try {
            return from(context, cameraId);
        } catch (CameraAccessException e) {
            return defaults();
        }
    }

    public boolean canManualFocus() {
        return manualFocusSupported && minimumFocusDistance > 0f;
    }

    public boolean canManualWb() {
        return manualWbSupported;
    }

    public int evToAeCompensationIndex(float ev) {
        float step = aeCompensationStep.floatValue();
        if (step <= 0f) {
            step = 1f / 3f;
        }
        int index = Math.round(ev / step);
        return Math.max(
                aeCompensationRange.getLower(),
                Math.min(aeCompensationRange.getUpper(), index));
    }

    public float aeCompensationIndexToEv(int index) {
        return index * aeCompensationStep.floatValue();
    }

    public int clampIso(int iso) {
        return Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), iso));
    }

    public long clampExposureTimeNs(long ns) {
        return Math.max(exposureTimeRange.getLower(), Math.min(exposureTimeRange.getUpper(), ns));
    }

    public float normToFocusDistance(float norm) {
        if (minimumFocusDistance <= 0f) {
            return 0f;
        }
        float n = Math.max(0f, Math.min(1f, norm));
        if (n <= 0.001f) {
            return 0f;
        }
        return (1f - n) * minimumFocusDistance;
    }

    /** Clamps settings to device ranges and syncs derived fields. */
    @NonNull
    public Camera3ASettings clampSettings(@NonNull Camera3ASettings settings) {
        Camera3ASettings s = Camera3ASettings.copyOf(settings);
        if (s.focusManual && !canManualFocus()) {
            s.focusManual = false;
        }
        s.focusDistanceNorm = Math.max(0f, Math.min(1f, s.focusDistanceNorm));

        int evIndex = evToAeCompensationIndex(s.evCompensation);
        s.evCompensation = aeCompensationIndexToEv(evIndex);

        s.iso = clampIso(s.iso);
        s.shutterIndex = ShutterStopTable.clampIndex(s.shutterIndex);
        s.exposureTimeNs =
                ShutterStopTable.indexToExposureTimeNs(s.shutterIndex, exposureTimeRange);
        s.exposureTimeNs = clampExposureTimeNs(s.exposureTimeNs);

        s.colorTemperatureK = Math.max(2000, Math.min(10000, s.colorTemperatureK));
        if (s.wbManual && !canManualWb()) {
            s.wbManual = false;
        }

        if (s.isManualExposureActive()) {
            s.evManual = false;
        }
        return s;
    }

    private static boolean contains(int[] array, int value) {
        for (int v : array) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }
}
