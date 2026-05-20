package com.cam.archcamera.camera;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * Picks Camera2 AE target FPS and stream sizes that can sustain {@link #TARGET_PREVIEW_FPS}.
 */
public final class PreviewTargetFps {

    public static final int TARGET_PREVIEW_FPS = 60;
    private static final long NS_PER_SECOND = 1_000_000_000L;

    private PreviewTargetFps() {}

    /** Highest FPS upper bound exposed in AE ranges (0 if none). */
    public static int maxAeFpsUpperBound(@Nullable Range<Integer>[] available) {
        if (available == null || available.length == 0) {
            return 0;
        }
        int max = 0;
        for (Range<Integer> r : available) {
            max = Math.max(max, r.getUpper());
        }
        return max;
    }

    public static boolean supportsTargetAeFps(@Nullable Range<Integer>[] available) {
        if (available == null) {
            return false;
        }
        for (Range<Integer> r : available) {
            if (r.getUpper() >= TARGET_PREVIEW_FPS && r.getLower() <= TARGET_PREVIEW_FPS) {
                return true;
            }
        }
        return false;
    }

    /** AE range preferring exactly 60 fps when the device exposes it. */
    @NonNull
    public static Range<Integer> pickAeTargetFpsRange(
            @Nullable Range<Integer>[] available) {
        if (available == null || available.length == 0) {
            return new Range<>(TARGET_PREVIEW_FPS, TARGET_PREVIEW_FPS);
        }
        Range<Integer> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Range<Integer> r : available) {
            if (r.getUpper() < TARGET_PREVIEW_FPS) {
                continue;
            }
            int score;
            if (r.getLower() <= TARGET_PREVIEW_FPS && r.getUpper() >= TARGET_PREVIEW_FPS) {
                if (r.getLower() == TARGET_PREVIEW_FPS && r.getUpper() == TARGET_PREVIEW_FPS) {
                    score = 1_000_000;
                } else {
                    score = 500_000 - (r.getUpper() - r.getLower());
                }
            } else {
                score = r.getUpper() - TARGET_PREVIEW_FPS;
            }
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (best != null) {
            return best;
        }
        return pickHighestFixedOrNarrowest(available);
    }

    /**
     * When 60 fps is unavailable: prefer {@code [30,30]} over {@code [10,30]} so preview targets a
     * stable peak rate instead of a wide variable range.
     */
    @NonNull
    private static Range<Integer> pickHighestFixedOrNarrowest(@NonNull Range<Integer>[] available) {
        int maxUpper = maxAeFpsUpperBound(available);
        for (Range<Integer> r : available) {
            if (r.getLower() == maxUpper && r.getUpper() == maxUpper) {
                return r;
            }
        }
        Range<Integer> best = available[0];
        int minSpan = Integer.MAX_VALUE;
        for (Range<Integer> r : available) {
            if (r.getUpper() != maxUpper) {
                continue;
            }
            int span = r.getUpper() - r.getLower();
            if (span < minSpan) {
                minSpan = span;
                best = r;
            }
        }
        return best;
    }

    @NonNull
    public static String formatAeRanges(@Nullable Range<Integer>[] available) {
        if (available == null || available.length == 0) {
            return "[]";
        }
        return Arrays.toString(available);
    }

    /** Max sustained output FPS for YUV at {@code size} per stream configuration map. */
    public static float maxYuvOutputFps(
            @Nullable StreamConfigurationMap map, @NonNull Size size) {
        if (map == null) {
            return 30f;
        }
        long minNs = map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size);
        if (minNs <= 0L) {
            return 30f;
        }
        return (float) (NS_PER_SECOND / (double) minNs);
    }

    /**
     * If {@code preferred} cannot reach {@link #TARGET_PREVIEW_FPS}, returns the largest supported
     * size (by area) with the same long/short aspect that can.
     */
    @NonNull
    public static Size ensureSupportsTargetFps(
            @NonNull Size[] supported,
            @Nullable StreamConfigurationMap map,
            @NonNull Size preferred) {
        Size even = PreviewStreamSizeResolver.ensureEven(preferred);
        if (maxYuvOutputFps(map, even) >= TARGET_PREVIEW_FPS - 0.5f) {
            return even;
        }
        float targetRatio = AspectResolutionSelector.longPerShortRatio(even);
        Size best = null;
        float bestArea = 0f;
        for (Size s : supported) {
            Size candidate = PreviewStreamSizeResolver.ensureEven(s);
            if (maxYuvOutputFps(map, candidate) < TARGET_PREVIEW_FPS - 0.5f) {
                continue;
            }
            float ratio = AspectResolutionSelector.longPerShortRatio(candidate);
            if (Math.abs(ratio - targetRatio) > 0.08f) {
                continue;
            }
            float area = (float) candidate.getWidth() * candidate.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = candidate;
            }
        }
        return best != null ? best : even;
    }

    @Nullable
    public static StreamConfigurationMap getStreamConfigurationMap(
            @NonNull CameraCharacteristics characteristics) {
        return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    }
}
