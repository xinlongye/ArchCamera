package com.cam.archcamera.gallery;

import android.content.Context;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.cam.archcamera.R;

/**
 * Loads gallery thumbnails at a bounded decode size. Without {@code override}, Glide may decode
 * full-resolution stills (e.g. 12MP) into memory and crash.
 */
public final class GalleryThumbGlide {

    private GalleryThumbGlide() {}

    public static void intoBottomBar(
            @NonNull ImageView target, @Nullable Uri uri) {
        if (uri == null) {
            Glide.with(target).clear(target);
            target.setImageDrawable(null);
            return;
        }
        int px = bottomBarThumbPx(target.getContext());
        Glide.with(target)
                .load(uri)
                .apply(thumbOptions(px))
                .into(target);
    }

    public static void intoGridCell(@NonNull ImageView target, @NonNull Uri uri) {
        int px = gridCellThumbPx(target);
        Glide.with(target).load(uri).apply(thumbOptions(px)).into(target);
    }

    public static void clear(@NonNull ImageView target) {
        Glide.with(target).clear(target);
    }

    @NonNull
    private static RequestOptions thumbOptions(int px) {
        return new RequestOptions()
                .override(px, px)
                .centerCrop()
                .downsample(DownsampleStrategy.AT_MOST);
    }

    static int bottomBarThumbPx(@NonNull Context context) {
        float px = context.getResources().getDimension(R.dimen.arch_thumb_size);
        return Math.max(1, (int) (px + 0.5f));
    }

    static int gridCellThumbPx(@NonNull ImageView target) {
        int w = target.getWidth();
        int h = target.getHeight();
        if (w > 0 && h > 0) {
            return Math.max(w, h);
        }
        Context ctx = target.getContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        // 3-column grid with ~3dp padding per cell.
        return Math.max(1, dm.widthPixels / 3);
    }
}
