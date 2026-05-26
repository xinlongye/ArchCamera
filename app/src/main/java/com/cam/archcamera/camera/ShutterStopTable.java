package com.cam.archcamera.camera;

import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Maps shutter stop labels to exposure times in nanoseconds. */
public final class ShutterStopTable {

    public static final String[] LABELS = {
        "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250", "1/125", "1/60",
        "1/30", "1/15", "1/8", "1/4", "1/2", "1\"", "2\"", "4\"", "8\"", "15\"", "30\"", "B"
    };

    private static final long[] EXPOSURE_NS = {
        125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L,
        16_666_667L, 33_333_333L, 66_666_667L, 125_000_000L, 250_000_000L, 500_000_000L,
        1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L,
        15_000_000_000L, 30_000_000_000L, -1L
    };

    private ShutterStopTable() {}

    public static int count() {
        return LABELS.length;
    }

    @NonNull
    public static String label(int index) {
        int i = clampIndex(index);
        return LABELS[i] + "s";
    }

    public static long indexToExposureTimeNs(int index, @Nullable Range<Long> exposureRange) {
        int i = clampIndex(index);
        long ns = EXPOSURE_NS[i];
        if (ns < 0L) {
            if (exposureRange != null) {
                return exposureRange.getUpper();
            }
            return 30_000_000_000L;
        }
        if (exposureRange != null) {
            ns = Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), ns));
        }
        return ns;
    }

    public static int exposureTimeNsToIndex(long ns, @Nullable Range<Long> exposureRange) {
        long clamped = ns;
        if (exposureRange != null) {
            clamped =
                    Math.max(exposureRange.getLower(), Math.min(exposureRange.getUpper(), ns));
        }
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < EXPOSURE_NS.length; i++) {
            long candidate = indexToExposureTimeNs(i, exposureRange);
            long diff = Math.abs(candidate - clamped);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    public static int clampIndex(int index) {
        return Math.max(0, Math.min(index, LABELS.length - 1));
    }

    public static float evFromProgress(int progress) {
        return -3f + progress * 0.1f;
    }

    public static int progressFromEv(float ev) {
        return Math.round((ev + 3f) / 0.1f);
    }

    @NonNull
    public static String formatEv(float ev) {
        return String.format(Locale.US, "%.1f EV", ev);
    }

    public static int isoFromProgress(int progress) {
        return 50 + progress * 50;
    }

    public static int progressFromIso(int iso) {
        return Math.max(0, (iso - 50) / 50);
    }

    public static int wbFromProgress(int progress) {
        return 2000 + progress * 100;
    }

    public static int progressFromWb(int kelvin) {
        return Math.max(0, (kelvin - 2000) / 100);
    }
}
