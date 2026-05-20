package com.cam.archcamera.imageviewer;

import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;

import com.bumptech.glide.Glide;
import com.cam.archcamera.R;
import com.cam.archcamera.databinding.ActivityImageViewerBinding;
import com.cam.archcamera.util.WindowInsetsHelper;

import java.io.IOException;
import java.io.InputStream;

public final class ImageViewerActivity extends AppCompatActivity {

    private static final float SCALE_MIN = 0.5f;
    private static final float SCALE_MAX = 4f;
    private static final float ZOOM_STEP = 1.2f;

    private ActivityImageViewerBinding binding;
    private float scale = 1f;
    private ScaleGestureDetector scaleGestureDetector;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowInsetsHelper.padTopBarForStatusBar(this, binding.topBar);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }

        Glide.with(this).load(uri).fitCenter().into(binding.viewerImage);

        binding.exifText.setText(buildExifHud(uri));

        binding.btnBack.setOnClickListener(v -> finish());

        scaleGestureDetector =
                new ScaleGestureDetector(
                        this,
                        new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                            @Override
                            public boolean onScale(ScaleGestureDetector detector) {
                                scale *= detector.getScaleFactor();
                                clampAndApplyScale();
                                return true;
                            }
                        });

        binding.viewerImage.setOnTouchListener(
                (v, event) -> {
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                });

        binding.viewerStage.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldL, oldT, oldR, oldB) -> applyScale());

        binding.btnZoomIn.setOnClickListener(
                v -> {
                    scale *= ZOOM_STEP;
                    clampAndApplyScale();
                });

        binding.btnZoomOut.setOnClickListener(
                v -> {
                    scale /= ZOOM_STEP;
                    clampAndApplyScale();
                });

        binding.btnZoomReset.setOnClickListener(
                v -> {
                    scale = 1f;
                    binding.viewerImage.setTranslationX(0f);
                    binding.viewerImage.setTranslationY(0f);
                    applyScale();
                });
    }

    private void clampAndApplyScale() {
        scale = Math.max(SCALE_MIN, Math.min(scale, SCALE_MAX));
        applyScale();
    }

    private void applyScale() {
        int w = binding.viewerImage.getWidth();
        int h = binding.viewerImage.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        binding.viewerImage.setPivotX(w / 2f);
        binding.viewerImage.setPivotY(h / 2f);
        binding.viewerImage.setScaleX(scale);
        binding.viewerImage.setScaleY(scale);
    }

    @NonNull
    private String buildExifHud(@NonNull Uri uri) {
        String displayName = queryDisplayName(uri);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return fallbackHud(displayName);
            }
            ExifInterface exif = new ExifInterface(in);
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.exif_heading)).append("\n\n");
            appendField(sb, "文件", displayName);
            String w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH);
            String h = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH);
            if (w != null && h != null) {
                appendField(sb, "分辨率", w + " × " + h);
            }
            String fnumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
            if (fnumber != null) {
                appendField(sb, "光圈", "f/" + fnumber);
            }
            String exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exp != null) {
                appendField(sb, "快门", exp + " s");
            }
            String iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY);
            if (iso != null) {
                appendField(sb, "ISO", iso);
            }
            String focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
            if (focal != null) {
                appendField(sb, "焦距", focal);
            }
            appendField(sb, "白平衡", formatWhiteBalance(exif));
            String dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) {
                dt = exif.getAttribute(ExifInterface.TAG_DATETIME);
            }
            if (dt != null) {
                appendField(sb, "拍摄时间", dt);
            }
            return sb.toString();
        } catch (IOException e) {
            return fallbackHud(displayName);
        }
    }

    @NonNull
    private String fallbackHud(@NonNull String displayName) {
        return getString(R.string.exif_heading)
                + "\n\n"
                + getString(R.string.exif_none)
                + "\n\n文件  "
                + displayName;
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        sb.append(label).append("  ").append(value).append('\n');
    }

    @NonNull
    private String formatWhiteBalance(ExifInterface exif) {
        int wb = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1);
        if (wb == ExifInterface.WHITEBALANCE_AUTO) {
            return "自动";
        }
        if (wb == ExifInterface.WHITEBALANCE_MANUAL) {
            return "手动";
        }
        String attr = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE);
        return attr != null && !attr.isEmpty() ? attr : "—";
    }

    @NonNull
    private String queryDisplayName(@NonNull Uri uri) {
        try (Cursor c =
                getContentResolver()
                        .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String name = c.getString(i);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "";
    }
}
