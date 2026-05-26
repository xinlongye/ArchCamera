package com.cam.archcamera.camera;

import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.RggbChannelVector;
import android.util.Rational;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Maps color temperature (Kelvin) to Camera2 white-balance controls.
 *
 * <p>UI uses creative semantics: lower K = warmer (yellow), higher K = cooler (blue). Camera2
 * {@link CaptureRequest#COLOR_CORRECTION_GAINS} expect illuminant-correction gains (inverse of
 * black-body chromaticity), so manual apply inverts Kelvin before conversion.
 */
public final class ColorTemperatureMapper {

    private static final int KELVIN_MIN = 2000;
    private static final int KELVIN_MAX = 10000;
    private static final int KELVIN_SUM = KELVIN_MIN + KELVIN_MAX;

    private ColorTemperatureMapper() {}

    public static void applyManualWb(@NonNull CaptureRequest.Builder builder, int creativeKelvin) {
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF);
        builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
        RggbChannelVector gains =
                kelvinToCorrectionGains(creativeKelvinToPipelineKelvin(creativeKelvin));
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains);
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, identityTransform());
    }

    /** UI Kelvin → pipeline illuminant Kelvin (creative warm/cool is opposite of correction). */
    static int creativeKelvinToPipelineKelvin(int creativeKelvin) {
        int k = Math.max(KELVIN_MIN, Math.min(KELVIN_MAX, creativeKelvin));
        return KELVIN_SUM - k;
    }

    @NonNull
    private static ColorSpaceTransform identityTransform() {
        Rational one = new Rational(1, 1);
        Rational zero = new Rational(0, 1);
        return new ColorSpaceTransform(
                new Rational[] {
                    one, zero, zero,
                    zero, one, zero,
                    zero, zero, one
                });
    }

    /**
     * Camera2 AWB correction gains for an illuminant at {@code kelvin}. Green is normalized to
     * 1.0; R/B gains compensate the scene (low R gain under warm light, etc.).
     */
    @NonNull
    static RggbChannelVector kelvinToCorrectionGains(int kelvin) {
        float[] rgb = blackBodyRgb(kelvin);
        float red = rgb[0];
        float green = rgb[1];
        float blue = rgb[2];
        return new RggbChannelVector(
                green / red,
                1f,
                1f,
                green / blue);
    }

    /** Tanner Helland black-body RGB approximation; values in 0–255. */
    @NonNull
    private static float[] blackBodyRgb(int kelvin) {
        int k = Math.max(KELVIN_MIN, Math.min(KELVIN_MAX, kelvin));
        float temp = k / 100f;
        float red;
        float blue;
        if (temp <= 66f) {
            red = 255f;
            blue = temp <= 19f ? 0f : (138.517731f - 0.688129f * (temp - 10f));
        } else {
            red = 329.698727446f * (float) Math.pow(temp - 60f, -0.1332047592f);
            blue = 255f;
        }
        float green;
        if (temp <= 66f) {
            green = 99.4708025861f * (float) Math.log(temp) - 161.1195681661f;
        } else {
            green = 288.1221695283f * (float) Math.pow(temp - 60f, -0.0755148492f);
        }
        red = Math.max(1f, red);
        green = Math.max(1f, green);
        blue = Math.max(1f, blue);
        return new float[] {red, green, blue};
    }

    /**
     * Estimates scene illuminant Kelvin from AWB {@link CaptureRequest#COLOR_CORRECTION_GAINS}.
     */
    @Nullable
    public static Integer estimateKelvinFromGains(@NonNull RggbChannelVector gains) {
        float r = gains.getRed();
        float b = gains.getBlue();
        if (r <= 0f || b <= 0f) {
            return null;
        }

        int bestK = -1;
        double bestErr = Double.MAX_VALUE;
        for (int k = KELVIN_MIN; k <= KELVIN_MAX; k += 25) {
            RggbChannelVector candidate = kelvinToCorrectionGains(k);
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
}
