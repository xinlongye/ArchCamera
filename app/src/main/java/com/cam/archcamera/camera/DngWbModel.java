package com.cam.archcamera.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * CCT -> manual WB gains using DNG static metadata (scheme C).
 *
 * <p>We use the full matrix chain when available:
 * <ul>
 *   <li>{@code SENSOR_FORWARD_MATRIX*}</li>
 *   <li>{@code SENSOR_COLOR_TRANSFORM*}</li>
 *   <li>{@code SENSOR_CALIBRATION_TRANSFORM*}</li>
 * </ul>
 *
 * <p>If some matrices are missing, we degrade to available pieces.
 */
public final class DngWbModel {

    private static final int KELVIN_MIN = 2000;
    private static final int KELVIN_MAX = 10000;
    private static final int KELVIN_SUM = KELVIN_MIN + KELVIN_MAX;

    // Standard illuminant D50 chromaticity.
    // Source: commonly used in color science references.
    private static final float D50_X = 0.34567f;
    private static final float D50_Y = 0.35850f;
    private static final float D50_Z = 0.29677f; // 1 - X - Y (for sanity)

    private final float[][] cameraToXyz1;
    private final float[][] cameraToXyz2;
    private final Integer reference1Kelvin; // may be null / out-of-range
    private final Integer reference2Kelvin; // may be null / out-of-range
    private final boolean creativeKelvinNeedsInversion;

    private DngWbModel(
            @NonNull float[][] cameraToXyz1,
            @NonNull float[][] cameraToXyz2,
            @Nullable Integer reference1Kelvin,
            @Nullable Integer reference2Kelvin,
            boolean creativeKelvinNeedsInversion) {
        this.cameraToXyz1 = cameraToXyz1;
        this.cameraToXyz2 = cameraToXyz2;
        this.reference1Kelvin = reference1Kelvin;
        this.reference2Kelvin = reference2Kelvin;
        this.creativeKelvinNeedsInversion = creativeKelvinNeedsInversion;
    }

    @Nullable
    public static DngWbModel tryFrom(@NonNull CameraCharacteristics chars) {
        ColorSpaceTransform f1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1);
        ColorSpaceTransform f2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2);
        if (f1 == null || f2 == null) {
            return null;
        }
        float[][] forward1 = toFloatMatrix(f1);
        float[][] forward2 = toFloatMatrix(f2);

        float[][] color1 = toFloatMatrixOrIdentity(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        float[][] color2 = toFloatMatrixOrIdentity(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        float[][] calib1 = toFloatMatrixOrIdentity(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        float[][] calib2 = toFloatMatrixOrIdentity(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));

        // Build cameraRGB -> XYZ matrices.
        float[][] cameraToXyz1 = buildCameraToXyz(forward1, color1, calib1);
        float[][] cameraToXyz2 = buildCameraToXyz(forward2, color2, calib2);
        if (cameraToXyz1 == null || cameraToXyz2 == null) {
            return null;
        }

        Integer ref1 = illuminantToKelvin(readIlluminantAsInt(chars, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1));
        Integer ref2 = illuminantToKelvin(readIlluminantAsInt(chars, CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2));

        boolean needsInversion = guessCreativeInversion(cameraToXyz1, cameraToXyz2, ref1, ref2);
        return new DngWbModel(cameraToXyz1, cameraToXyz2, ref1, ref2, needsInversion);
    }

    public boolean isSupported() {
        return true;
    }

    /**
     * Creative Kelvin => Camera2 {@link CaptureRequest#COLOR_CORRECTION_GAINS}.
     *
     * <p>Note: this method may return null if matrix inversion fails.
     */
    @Nullable
    public RggbChannelVector correctionGainsForCreativeKelvin(int creativeKelvin) {
        int kCreative = clamp(creativeKelvin, KELVIN_MIN, KELVIN_MAX);
        int pipelineKelvin =
                creativeKelvinNeedsInversion ? (KELVIN_SUM - kCreative) : kCreative;
        return correctionGainsForPipelineKelvin(pipelineKelvin);
    }

    @Nullable
    public Integer estimateCreativeKelvinFromCorrectionGains(@NonNull RggbChannelVector gains) {
        float r = gains.getRed();
        float b = gains.getBlue();
        if (r <= 0f || b <= 0f) {
            return null;
        }
        int bestK = -1;
        double bestErr = Double.MAX_VALUE;
        for (int k = KELVIN_MIN; k <= KELVIN_MAX; k += 25) {
            RggbChannelVector candidate = correctionGainsForCreativeKelvin(k);
            if (candidate == null) {
                continue;
            }
            double err =
                    Math.abs(candidate.getRed() - r)
                            + Math.abs(candidate.getBlue() - b);
            if (err < bestErr) {
                bestErr = err;
                bestK = k;
            }
        }
        return bestK >= KELVIN_MIN ? bestK : null;
    }

    @Nullable
    private RggbChannelVector correctionGainsForPipelineKelvin(int pipelineKelvin) {
        float alpha = computeAlpha(pipelineKelvin);
        float[][] cameraToXyz = lerp(cameraToXyz1, cameraToXyz2, alpha);
        float[][] inv = invert3x3(cameraToXyz);
        if (inv == null) {
            return null;
        }

        // Solve for white-balanced camera RGB that maps to D50.
        float[] xyzD50 = xyzD50();
        float[] wbRgb = mulMat3Vec(inv, xyzD50);

        float r = wbRgb[0];
        float g = wbRgb[1];
        float b = wbRgb[2];

        if (!(r > 0f && g > 0f && b > 0f) || Float.isNaN(r + g + b)) {
            return null;
        }

        // CONTROL_AWB_MODE_OFF + COLOR_CORRECTION_GAINS.
        float redGain = g / r;
        float blueGain = g / b;
        if (!(Float.isFinite(redGain) && Float.isFinite(blueGain))) {
            return null;
        }

        return new RggbChannelVector(redGain, 1f, 1f, blueGain);
    }

    private float computeAlpha(int pipelineKelvin) {
        float k = clamp(pipelineKelvin, KELVIN_MIN, KELVIN_MAX);

        Integer ref1 = reference1Kelvin;
        Integer ref2 = reference2Kelvin;
        boolean refOk =
                ref1 != null
                        && ref2 != null
                        && ref1 >= KELVIN_MIN
                        && ref1 <= KELVIN_MAX
                        && ref2 >= KELVIN_MIN
                        && ref2 <= KELVIN_MAX
                        && ref1.intValue() != ref2.intValue();

        if (refOk) {
            float a = (k - ref1) / (float) (ref2 - ref1);
            return clamp01(a);
        }

        // Fallback: linear interpolation across the same Kelvin range we expose to users.
        float a = (k - KELVIN_MIN) / (float) (KELVIN_MAX - KELVIN_MIN);
        return clamp01(a);
    }

    private static boolean guessCreativeInversion(
            @NonNull float[][] cameraToXyz1,
            @NonNull float[][] cameraToXyz2,
            @Nullable Integer ref1,
            @Nullable Integer ref2) {
        Float invLow =
                correctionGainsScoreForCreative(
                        kCreative(KELVIN_MIN), true, cameraToXyz1, cameraToXyz2, ref1, ref2);
        Float invHigh =
                correctionGainsScoreForCreative(
                        kCreative(KELVIN_MAX), true, cameraToXyz1, cameraToXyz2, ref1, ref2);
        Float dirLow =
                correctionGainsScoreForCreative(
                        kCreative(KELVIN_MIN), false, cameraToXyz1, cameraToXyz2, ref1, ref2);
        Float dirHigh =
                correctionGainsScoreForCreative(
                        kCreative(KELVIN_MAX), false, cameraToXyz1, cameraToXyz2, ref1, ref2);

        boolean invOk = invLow != null && invHigh != null && invLow > invHigh;
        boolean dirOk = dirLow != null && dirHigh != null && dirLow > dirHigh;

        // Prefer the inversion that matches "low K => warmer".
        if (invOk && !dirOk) {
            return true;
        }
        if (!invOk && dirOk) {
            return false;
        }
        // Default to the historic app behavior (inversion) if ambiguous.
        return true;
    }

    private static int kCreative(int k) {
        return clamp(k, KELVIN_MIN, KELVIN_MAX);
    }

    @Nullable
    private static Float correctionGainsScoreForCreative(
            int creativeK,
            boolean useInversion,
            @NonNull float[][] cameraToXyz1,
            @NonNull float[][] cameraToXyz2,
            @Nullable Integer ref1,
            @Nullable Integer ref2) {
        int pipelineKelvin = useInversion ? (KELVIN_SUM - creativeK) : creativeK;
        float alpha = computeAlphaStatic(pipelineKelvin, ref1, ref2);
        float[][] cameraToXyz = lerp(cameraToXyz1, cameraToXyz2, alpha);
        float[][] inv = invert3x3(cameraToXyz);
        if (inv == null) {
            return null;
        }
        float[] xyzD50 = xyzD50Static();
        float[] wbRgb = mulMat3Vec(inv, xyzD50);
        float r = wbRgb[0];
        float g = wbRgb[1];
        float b = wbRgb[2];
        if (!(r > 0f && g > 0f && b > 0f) || Float.isNaN(r + g + b)) {
            return null;
        }
        // warmer -> larger Rgain/Bgain.
        return b / r;
    }

    private static float computeAlphaStatic(
            int pipelineKelvin, @Nullable Integer ref1, @Nullable Integer ref2) {
        float k = clamp(pipelineKelvin, KELVIN_MIN, KELVIN_MAX);
        boolean refOk =
                ref1 != null
                        && ref2 != null
                        && ref1 >= KELVIN_MIN
                        && ref1 <= KELVIN_MAX
                        && ref2 >= KELVIN_MIN
                        && ref2 <= KELVIN_MAX
                        && ref1.intValue() != ref2.intValue();
        if (refOk) {
            float a = (k - ref1) / (float) (ref2 - ref1);
            return clamp01(a);
        }
        return (k - KELVIN_MIN) / (float) (KELVIN_MAX - KELVIN_MIN);
    }

    private static float[][] lerp(@NonNull float[][] a, @NonNull float[][] b, float alpha) {
        float[][] out = new float[3][3];
        float oneMinus = 1f - alpha;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r][c] = oneMinus * a[r][c] + alpha * b[r][c];
            }
        }
        return out;
    }

    @Nullable
    private static float[][] invert3x3(@NonNull float[][] m) {
        float a = m[0][0];
        float b = m[0][1];
        float c = m[0][2];
        float d = m[1][0];
        float e = m[1][1];
        float f = m[1][2];
        float g = m[2][0];
        float h = m[2][1];
        float i = m[2][2];

        float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-9f) {
            return null;
        }
        float invDet = 1f / det;

        float[][] inv = new float[3][3];
        inv[0][0] = (e * i - f * h) * invDet;
        inv[0][1] = (c * h - b * i) * invDet;
        inv[0][2] = (b * f - c * e) * invDet;

        inv[1][0] = (f * g - d * i) * invDet;
        inv[1][1] = (a * i - c * g) * invDet;
        inv[1][2] = (c * d - a * f) * invDet;

        inv[2][0] = (d * h - e * g) * invDet;
        inv[2][1] = (b * g - a * h) * invDet;
        inv[2][2] = (a * e - b * d) * invDet;

        return inv;
    }

    @NonNull
    private static float[] mulMat3Vec(@NonNull float[][] m, @NonNull float[] v) {
        float x = m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2];
        float y = m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2];
        float z = m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2];
        return new float[] {x, y, z};
    }

    @NonNull
    private static float[] xyzD50() {
        return xyzD50Static();
    }

    @NonNull
    private static float[] xyzD50Static() {
        // Y normalized to 1.0.
        // If x,y are the chromaticity, then:
        // X = x/y * Y, Z = (1-x-y)/y * Y
        float Y = 1f;
        float X = D50_X / D50_Y * Y;
        float Z = (1f - D50_X - D50_Y) / D50_Y * Y;
        return new float[] {X, Y, Z};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    @NonNull
    private static float[][] toFloatMatrix(@NonNull ColorSpaceTransform t) {
        float[][] out = new float[3][3];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                Rational el = t.getElement(col, row);
                out[row][col] = el != null ? el.floatValue() : 0f;
            }
        }
        return out;
    }

    @Nullable
    private static float[][] buildCameraToXyz(
            @NonNull float[][] forward,
            @NonNull float[][] colorTransform,
            @NonNull float[][] calibrationTransform) {
        float[][] colorCalib = mulMat3(colorTransform, calibrationTransform);
        float[][] invColorCalib = invert3x3(colorCalib);
        if (invColorCalib == null) {
            return null;
        }
        return mulMat3(forward, invColorCalib);
    }

    @NonNull
    private static float[][] toFloatMatrixOrIdentity(@Nullable ColorSpaceTransform t) {
        if (t == null) {
            return identity3x3();
        }
        return toFloatMatrix(t);
    }

    @NonNull
    private static float[][] identity3x3() {
        return new float[][] {
            {1f, 0f, 0f},
            {0f, 1f, 0f},
            {0f, 0f, 1f}
        };
    }

    @NonNull
    private static float[][] mulMat3(@NonNull float[][] a, @NonNull float[][] b) {
        float[][] out = new float[3][3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r][c] =
                        a[r][0] * b[0][c]
                                + a[r][1] * b[1][c]
                                + a[r][2] * b[2][c];
            }
        }
        return out;
    }

    @Nullable
    private static Integer readIlluminantAsInt(
            @NonNull CameraCharacteristics chars, @NonNull CameraCharacteristics.Key<?> key) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        CameraCharacteristics.Key raw = (CameraCharacteristics.Key) key;
        Object v = chars.get(raw);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return null;
    }

    @Nullable
    private static Integer illuminantToKelvin(@Nullable Integer dngIlluminantCode) {
        if (dngIlluminantCode == null) {
            return null;
        }
        // DNG EXIF LightSource values used by SENSOR_REFERENCE_ILLUMINANT*.
        switch (dngIlluminantCode) {
            case 1: // Daylight
                return 5500;
            case 2: // Fluorescent
                return 4200;
            case 3: // Tungsten
                return 2850;
            case 4: // Flash
                return 5500;
            case 9: // Fine weather
                return 5500;
            case 10: // Cloudy weather
                return 6500;
            case 11: // Shade
                return 7500;
            case 12: // Daylight fluorescent
                return 6400;
            case 13: // Day white fluorescent
                return 5000;
            case 14: // Cool white fluorescent
                return 4000;
            case 15: // White fluorescent
                return 3500;
            case 17: // Standard light A
                return 2856;
            case 19: // D65
                return 6504;
            case 20: // D75
                return 7504;
            case 21: // D50
                return 5003;
            default:
                return null;
        }
    }
}

