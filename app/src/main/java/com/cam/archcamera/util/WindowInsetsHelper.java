package com.cam.archcamera.util;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Applies status-bar height as extra top padding on a header row (edge-to-edge safe). */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {}

    /**
     * Draws behind system bars, then pads {@code topBar} so its content clears the status bar.
     * Preserves the view's XML horizontal/bottom padding; top becomes {@code xmlPaddingTop +
     * statusBarsInset}.
     */
    public static void padTopBarForStatusBar(@NonNull Activity activity, @NonNull View topBar) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        final int padLeft = topBar.getPaddingLeft();
        final int padTopBase = topBar.getPaddingTop();
        final int padRight = topBar.getPaddingRight();
        final int padBottom = topBar.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(
                topBar,
                (v, windowInsets) -> {
                    Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars());
                    v.setPadding(padLeft, padTopBase + bars.top, padRight, padBottom);
                    return windowInsets;
                });
        ViewCompat.requestApplyInsets(topBar);
    }
}
