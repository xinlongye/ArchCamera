package com.cam.archcamera.camera;

import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 按「短边:长边」对应的长边/短边比，从 Camera2 枚举到的预览 / JPEG 尺寸列表中挑选分辨率。
 *
 * <p>预览：先按长宽比归入「最佳匹配层」，再在层内选总像素数最接近 {@code targetDisplayPixelCount} 的尺寸（未知目标
 * 时在层内取最大面积，避免极小预览流）。
 *
 * <p>拍照：在同一匹配层中选面积最大。
 */
public final class AspectResolutionSelector {

    /** 与最佳长宽比的相对误差容差，用于归并「同等匹配」的候选。 */
    private static final float ASPECT_MATCH_SLACK = 0.02f;

    private AspectResolutionSelector() {}

    /** 长边 / 短边，竖屏 UI 下与文档中比例描述一致。 */
    public static float longPerShortRatio(@NonNull Size s) {
        int w = s.getWidth();
        int h = s.getHeight();
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        if (min <= 0) {
            return 0f;
        }
        return (float) max / (float) min;
    }

    /** 相对目标长宽比的偏差，0 表示完全匹配。 */
    public static float aspectDistance(@NonNull Size s, float targetLongPerShort) {
        if (targetLongPerShort <= 0f) {
            return Float.MAX_VALUE;
        }
        float r = longPerShortRatio(s);
        if (r <= 0f) {
            return Float.MAX_VALUE;
        }
        return Math.abs(r - targetLongPerShort) / targetLongPerShort;
    }

    /**
     * @param targetDisplayPixelCount 屏幕预览宿主区域像素总数；{@code <=0} 时在匹配层内取最大面积。
     */
    @Nullable
    public static Size pickPreviewForTargetRatio(
            @Nullable Size[] sizes, float targetLongPerShort, long targetDisplayPixelCount) {
        List<Size> tier = buildAspectMatchTier(sizes, targetLongPerShort);
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        if (targetDisplayPixelCount <= 0L) {
            return pickLargestArea(tier);
        }
        Size chosen = tier.get(0);
        long chosenArea = area(chosen);
        long bestGap = Math.abs(chosenArea - targetDisplayPixelCount);
        for (int i = 1; i < tier.size(); i++) {
            Size s = tier.get(i);
            long a = area(s);
            long gap = Math.abs(a - targetDisplayPixelCount);
            if (gap < bestGap) {
                bestGap = gap;
                chosen = s;
                chosenArea = a;
            } else if (gap == bestGap && a > chosenArea) {
                chosen = s;
                chosenArea = a;
            }
        }
        return chosen;
    }

    @Nullable
    public static Size pickCaptureForTargetRatio(
            @Nullable Size[] sizes, float targetLongPerShort) {
        List<Size> tier = buildAspectMatchTier(sizes, targetLongPerShort);
        if (tier == null || tier.isEmpty()) {
            return null;
        }
        return pickLargestArea(tier);
    }

    @Nullable
    private static List<Size> buildAspectMatchTier(
            @Nullable Size[] sizes, float targetLongPerShort) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        float bestDist = Float.MAX_VALUE;
        for (Size s : sizes) {
            float d = aspectDistance(s, targetLongPerShort);
            if (d < bestDist) {
                bestDist = d;
            }
        }
        if (bestDist >= Float.MAX_VALUE / 2f) {
            return null;
        }
        float cutoff = bestDist + Math.max(1e-4f, bestDist * ASPECT_MATCH_SLACK);
        List<Size> tier = new ArrayList<>();
        for (Size s : sizes) {
            if (aspectDistance(s, targetLongPerShort) <= cutoff) {
                tier.add(s);
            }
        }
        return tier.isEmpty() ? null : tier;
    }

    @NonNull
    private static Size pickLargestArea(@NonNull List<Size> tier) {
        Size chosen = tier.get(0);
        long bestArea = area(chosen);
        for (int i = 1; i < tier.size(); i++) {
            Size s = tier.get(i);
            long a = area(s);
            if (a > bestArea) {
                bestArea = a;
                chosen = s;
            }
        }
        return chosen;
    }

    private static long area(@NonNull Size s) {
        return (long) s.getWidth() * (long) s.getHeight();
    }
}
