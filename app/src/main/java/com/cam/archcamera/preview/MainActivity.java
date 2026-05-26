package com.cam.archcamera.preview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Outline;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.cam.archcamera.R;
import com.cam.archcamera.camera.AspectResolutionSelector;
import com.cam.archcamera.camera.Camera2Enum;
import com.cam.archcamera.camera.Camera2PreviewController;
import com.cam.archcamera.camera.Camera3ACapabilities;
import com.cam.archcamera.camera.Camera3ADisplayValues;
import com.cam.archcamera.camera.Camera3ASettings;
import com.cam.archcamera.camera.PreviewStreamSizeResolver;
import com.cam.archcamera.databinding.ActivityMainBinding;
import com.cam.archcamera.gallery.GalleryActivity;
import com.cam.archcamera.gallery.GalleryItem;
import com.cam.archcamera.gallery.GalleryMediaRepository;
import com.cam.archcamera.gallery.GalleryThumbGlide;
import com.cam.archcamera.settings.PhotoSavePrefs;
import com.cam.archcamera.settings.SettingsActivity;
import com.cam.archcamera.util.WindowInsetsHelper;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("archcamera");
    }

    private enum PreviewAspect {
        ONE_ONE,
        THREE_FOUR,
        NINE_SIXTEEN,
        FULL
    }

    /** 策略 key 中显示区总像素的量化步长，减轻 1px 抖动导致的 prefs 重算。 */
    private static final long RESOLUTION_POLICY_DISPLAY_PIXEL_BUCKET = 50_000L;

    /** 布局连续变化时合并分辨率策略更新，避免相机反复 open/close。 */
    private static final long RESOLUTION_POLICY_DEBOUNCE_MS = 150L;

    private ActivityMainBinding binding;
    private final Camera2PreviewController previewController = new Camera2PreviewController();
    private Camera3APanelController camera3aPanelController;
    @Nullable private Camera3ADisplayValues lastLive3a;
    private PreviewAspect aspect = PreviewAspect.THREE_FOUR;
    private boolean backCamera = true;

    /** 上次已按策略写入 prefs 的 key（摄像头、比例、全屏目标比、显示区像素桶），避免覆盖用户在设置里的手改。 */
    @Nullable private String lastResolutionPolicyKey;

    private final Runnable resolutionPolicyRunnable =
            () -> applyResolutionSelectionForCurrentState(false);

    /** 合并布局回调，在 layout pass 结束后再改 LayoutParams，避免 requestLayout 警告。 */
    private final Runnable applyPreviewGeometryRunnable = this::applyPreviewGeometry;

    private final ActivityResultLauncher<String> requestReadImages =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            refreshGalleryThumbnail();
                        } else {
                            clearGalleryThumbnail();
                        }
                    });

    private final ActivityResultLauncher<String> requestCamera =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startPreviewIfReady();
                        } else {
                            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG)
                                    .show();
                        }
                    });

    public native String stringFromJNI();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowInsetsHelper.padTopBarForStatusBar(this, binding.topBar);

        camera3aPanelController =
                new Camera3APanelController(
                        binding.camera3aPanel,
                        new Camera3APanelController.Listener() {
                            @Override
                            public void onSettingsChanged(@NonNull Camera3ASettings settings) {
                                MainActivity.this.onCamera3AChanged(settings);
                            }

                            @Override
                            public void onDisplayChanged() {
                                MainActivity.this.refresh3aHud();
                            }
                        });
        camera3aPanelController.setup();
        previewController.setPreview3AListener(this::onPreview3AUpdated);

        binding.cameraStage.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    scheduleApplyPreviewGeometry();
                    scheduleResolutionSelectionIfPolicyChanged();
                });

        binding.topBar.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        scheduleApplyPreviewGeometry());

        binding.aspectGroup.addOnButtonCheckedListener(this::onAspectChecked);
        binding.modeGroup.addOnButtonCheckedListener(this::onModeChecked);
        setupFocalExclusiveSelection();

        binding.btnSettings.setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        binding.btnOpenGallery.setOnClickListener(
                v -> startActivity(new Intent(this, GalleryActivity.class)));
        setupGalleryThumbnailOutline();

        binding.btnShutter.setOnClickListener(v -> takePhoto());

        binding.btnFlipCamera.setOnClickListener(
                v -> {
                    if (binding.modeGroup.getCheckedButtonId() == R.id.mode_pro) {
                        return;
                    }
                    backCamera = !backCamera;
                    refreshCameraAndModeUi();
                    applySelectedCameraIdForFacing(backCamera);
                    // 立即按新摄像头重选分辨率并刷新 HUD；勿仅 scheduleResolutionSelection，
                    // 否则 startPreview 触发的连续 layout 会反复重置 150ms 防抖，策略可能一直不落地。
                    applyResolutionSelectionForCurrentState(true);
                    if (hasCameraPermission()) {
                        startPreviewIfReady();
                    } else {
                        applyPreviewStreamSizeFromPrefsOnly();
                    }
                    Toast.makeText(
                                    this,
                                    backCamera ? R.string.camera_back : R.string.camera_front,
                                    Toast.LENGTH_SHORT)
                            .show();
                });

        restoreAspectFromPrefs();
        syncBackCameraFromPrefs();
        refreshCameraAndModeUi();

        binding.cameraStage.post(
                () -> {
                    applyPreviewGeometry();
                    applyResolutionSelectionForCurrentState(false);
                });

        binding.previewGl.setPreviewFpsListener(this::onPreviewFpsUpdated);
    }

    private void onPreviewFpsUpdated(float fps) {
        binding.previewFpsHudText.setText(
                getString(R.string.preview_fps_hud_format, fps));
    }

    private void resetPreviewFpsHud() {
        binding.previewFpsHudText.setText(R.string.preview_fps_hud_unknown);
    }

    private void reset3aHud() {
        lastLive3a = null;
        binding.camera3aHudText.setText(R.string.camera_3a_hud_unknown);
    }

    private void onPreview3AUpdated(@NonNull Camera3ADisplayValues live) {
        lastLive3a = live;
        if (isProfessionalMode()) {
            camera3aPanelController.syncAutoRowsFromLive(live);
        }
        refresh3aHud();
    }

    private void refresh3aHud() {
        Camera3ADisplayValues live = lastLive3a;
        if (live == null) {
            if (isProfessionalMode()) {
                binding.camera3aHudText.setText(
                        camera3aPanelController.buildDisplayValuesFromUi().formatHudLines(this));
            } else {
                binding.camera3aHudText.setText(R.string.camera_3a_hud_unknown);
            }
            return;
        }
        Camera3ADisplayValues display;
        if (isProfessionalMode()) {
            Camera3ASettings s = camera3aPanelController.getSettings();
            Camera3ADisplayValues panel = camera3aPanelController.buildDisplayValuesFromUi();
            display =
                    live.merge(
                            panel,
                            s.focusManual,
                            s.evManual,
                            s.isoManual,
                            s.shutterManual,
                            s.wbManual,
                            true);
        } else {
            display = live;
        }
        binding.camera3aHudText.setText(display.formatHudLines(this));
    }

    @Override
    protected void onPause() {
        previewController.stop();
        binding.previewGl.onPause();
        resetPreviewFpsHud();
        reset3aHud();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        binding.cameraStage.removeCallbacks(applyPreviewGeometryRunnable);
        binding.cameraStage.removeCallbacks(resolutionPolicyRunnable);
        previewController.shutdown();
        binding.previewGl.release();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.previewGl.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED) {
            refreshGalleryThumbnail();
        } else {
            clearGalleryThumbnail();
            requestReadImages.launch(Manifest.permission.READ_MEDIA_IMAGES);
        }
        syncBackCameraFromPrefs();
        refreshCameraAndModeUi();
        scheduleResolutionSelectionIfPolicyChanged();
        ensureCameraPermissionAndPreview();
    }

    private void ensureCameraPermissionAndPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA);
            applyPreviewStreamSizeFromPrefsOnly();
            return;
        }
        // Camera is always stopped in onPause; restart preview after GL surface is resumed.
        startPreviewIfReady();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startPreviewIfReady() {
        String camId = resolveCameraIdForResolution();
        if (camId == null) {
            applyPreviewStreamSizeFromPrefsOnly();
            return;
        }
        bindCamera3ACapabilities(camId);
        Size labelSize =
                PreviewSizeLabel.parseOrFallback(PhotoSavePrefs.getPreviewSizeLabel(this));
        Size streamSize = PreviewStreamSizeResolver.resolve(this, camId, labelSize);
        binding.previewGl.invalidatePreviewFrames();
        binding.previewGl.setPreviewSize(streamSize.getWidth(), streamSize.getHeight());
        previewController.start(this, camId, streamSize, binding.previewGl.getFrameSink());
        applyCamera3AToController();
    }

    private void bindCamera3ACapabilities(@NonNull String cameraId) {
        Camera3ACapabilities caps = Camera3ACapabilities.tryFrom(this, cameraId);
        previewController.setCamera3ACapabilities(caps);
        camera3aPanelController.bindCapabilities(caps);
    }

    private void onCamera3AChanged(@NonNull Camera3ASettings settings) {
        applyCamera3AToController();
        refresh3aHud();
    }

    private boolean isProfessionalMode() {
        return binding.modeGroup.getCheckedButtonId() == R.id.mode_pro;
    }

    private void applyCamera3AToController() {
        previewController.setCamera3A(
                camera3aPanelController.getSettings(), isProfessionalMode());
    }

    /** Updates GL buffers when camera is not running (no permission / no device). */
    private void applyPreviewStreamSizeFromPrefsOnly() {
        Size size =
                PreviewSizeLabel.parseOrFallback(PhotoSavePrefs.getPreviewSizeLabel(this));
        binding.previewGl.invalidatePreviewFrames();
        binding.previewGl.setPreviewSize(size.getWidth(), size.getHeight());
    }

    /** Writes prefs to the first camera id for BACK / FRONT (system camera id list order). */
    private void applySelectedCameraIdForFacing(boolean useBackCamera) {
        int facing =
                useBackCamera
                        ? CameraCharacteristics.LENS_FACING_BACK
                        : CameraCharacteristics.LENS_FACING_FRONT;
        String id = Camera2Enum.firstCameraIdForFacing(this, facing);
        if (id != null) {
            PhotoSavePrefs.setSelectedCameraId(this, id);
        }
    }

    private void syncBackCameraFromPrefs() {
        String saved = PhotoSavePrefs.getSelectedCameraId(this);
        if (saved == null || !Camera2Enum.listCameraIds(this).contains(saved)) {
            return;
        }
        Integer facing = Camera2Enum.getLensFacing(this, saved);
        if (facing == null) {
            return;
        }
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            backCamera = false;
        } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
            backCamera = true;
        }
    }

    private void setupGalleryThumbnailOutline() {
        float cornerPx = getResources().getDimension(R.dimen.arch_corner_btn);
        binding.btnOpenGallery.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(
                                0, 0, view.getWidth(), view.getHeight(), cornerPx);
                    }
                });
        binding.btnOpenGallery.setClipToOutline(true);
    }

    private void clearGalleryThumbnail() {
        GalleryThumbGlide.clear(binding.btnOpenGallery);
        binding.btnOpenGallery.setImageDrawable(null);
    }

    private void takePhoto() {
        if (!hasCameraPermission()) {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!previewController.isOpen()) {
            Toast.makeText(this, R.string.photo_capture_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        binding.btnShutter.setEnabled(false);
        previewController.captureStill(
                this,
                new Camera2PreviewController.StillCaptureCallback() {
                    @Override
                    public void onSuccess(@NonNull Uri uri) {
                        binding.btnShutter.setEnabled(true);
                        GalleryThumbGlide.intoBottomBar(binding.btnOpenGallery, uri);
                        Toast.makeText(MainActivity.this, R.string.photo_saved, Toast.LENGTH_SHORT)
                                .show();
                    }

                    @Override
                    public void onFailure(@NonNull String message) {
                        binding.btnShutter.setEnabled(true);
                        Toast.makeText(
                                        MainActivity.this,
                                        R.string.photo_capture_failed,
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                });
    }

    private void refreshGalleryThumbnail() {
        GalleryItem latest =
                GalleryMediaRepository.queryLatestImageInSaveDirectory(
                        this, PhotoSavePrefs.getSaveDirectoryAbsolute(this));
        if (latest == null) {
            clearGalleryThumbnail();
            return;
        }
        GalleryThumbGlide.intoBottomBar(binding.btnOpenGallery, latest.getContentUri());
    }

    /** 焦段为独立圆角按钮（非 ToggleGroup），在此维持单选。 */
    private void setupFocalExclusiveSelection() {
        MaterialButton[] focal = {binding.focal06, binding.focal10, binding.focal20};
        for (int i = 0; i < focal.length; i++) {
            final int index = i;
            focal[i].setOnClickListener(
                    v -> {
                        for (MaterialButton b : focal) {
                            b.setChecked(false);
                        }
                        focal[index].setChecked(true);
                    });
        }
    }

    private void onAspectChecked(com.google.android.material.button.MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
        if (!isChecked) {
            return;
        }
        updateAspectFromButtonId(checkedId);
        PhotoSavePrefs.setPreviewAspect(this, aspect.name());
        applyPreviewGeometry();
        applyResolutionSelectionForCurrentState(true);
    }

    private void onModeChecked(com.google.android.material.button.MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
        if (!isChecked) {
            return;
        }
        if (checkedId == R.id.mode_pro && !backCamera) {
            binding.modeGroup.check(R.id.mode_normal);
            return;
        }
        refreshCameraAndModeUi();
    }

    /**
     * 前摄：仅普通模式（强制切回普通，且禁用「专业」）。专业模式：仅后摄，禁用前后摄切换。
     */
    private void refreshCameraAndModeUi() {
        if (!backCamera) {
            binding.modeGroup.check(R.id.mode_normal);
        }

        boolean pro = isProfessionalMode();

        binding.sectionCamera3a.setVisibility(pro ? View.VISIBLE : View.GONE);
        binding.modePro.setEnabled(backCamera);
        binding.btnFlipCamera.setEnabled(!pro);
        binding.focalScroll.setVisibility(backCamera ? View.VISIBLE : View.GONE);

        if (!pro) {
            camera3aPanelController.resetToAutoDefaults();
        }
        applyCamera3AToController();
    }

    private void restoreAspectFromPrefs() {
        String saved = PhotoSavePrefs.getPreviewAspect(this);
        if (saved != null) {
            int buttonId = aspectButtonIdFromName(saved);
            if (buttonId != View.NO_ID) {
                binding.aspectGroup.check(buttonId);
            }
        }
        syncAspectFromToggle();
    }

    private void syncAspectFromToggle() {
        updateAspectFromButtonId(binding.aspectGroup.getCheckedButtonId());
    }

    private void updateAspectFromButtonId(int buttonId) {
        if (buttonId == R.id.aspect_1_1) {
            aspect = PreviewAspect.ONE_ONE;
        } else if (buttonId == R.id.aspect_3_4) {
            aspect = PreviewAspect.THREE_FOUR;
        } else if (buttonId == R.id.aspect_9_16) {
            aspect = PreviewAspect.NINE_SIXTEEN;
        } else if (buttonId == R.id.aspect_full) {
            aspect = PreviewAspect.FULL;
        }
    }

    private static int aspectButtonIdFromName(@NonNull String aspectName) {
        switch (aspectName) {
            case "ONE_ONE":
                return R.id.aspect_1_1;
            case "THREE_FOUR":
                return R.id.aspect_3_4;
            case "NINE_SIXTEEN":
                return R.id.aspect_9_16;
            case "FULL":
                return R.id.aspect_full;
            default:
                return View.NO_ID;
        }
    }

    private void scheduleApplyPreviewGeometry() {
        binding.cameraStage.removeCallbacks(applyPreviewGeometryRunnable);
        binding.cameraStage.post(applyPreviewGeometryRunnable);
    }

    private void applyPreviewGeometry() {
        FrameLayout stage = binding.cameraStage;
        FrameLayout previewHost = binding.previewHost;
        int W = stage.getWidth();
        int H = stage.getHeight();
        int topBarH = binding.topBar.getHeight();
        if (W <= 0 || H <= 0) {
            refreshResolutionHud();
            return;
        }

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewHost.getLayoutParams();
        int hostGravity = Gravity.TOP | Gravity.START;
        int displayW;
        int displayH;
        int topMargin;
        int leftMargin;
        if (aspect == PreviewAspect.FULL) {
            displayW = PreviewStreamSizeResolver.ensureEvenDimension(W);
            displayH = PreviewStreamSizeResolver.ensureEvenDimension(H);
            topMargin = 0;
            leftMargin = 0;
        } else {
            float ratioLongPerShort;
            if (aspect == PreviewAspect.ONE_ONE) {
                ratioLongPerShort = 1f;
            } else if (aspect == PreviewAspect.THREE_FOUR) {
                ratioLongPerShort = 4f / 3f;
            } else {
                ratioLongPerShort = 16f / 9f;
            }
            displayW = PreviewStreamSizeResolver.ensureEvenDimension(W);
            displayH =
                    PreviewStreamSizeResolver.ensureEvenDimension(
                            Math.round(displayW * ratioLongPerShort));
            leftMargin = 0;
            if (aspect == PreviewAspect.NINE_SIXTEEN) {
                topMargin = Math.max(0, (H - displayH) / 2);
            } else {
                topMargin = topBarH;
            }
        }
        boolean hostUnchanged =
                lp.width == displayW
                        && lp.height == displayH
                        && lp.topMargin == topMargin
                        && lp.leftMargin == leftMargin
                        && lp.gravity == hostGravity;
        if (!hostUnchanged) {
            lp.gravity = hostGravity;
            lp.width = displayW;
            lp.height = displayH;
            lp.topMargin = topMargin;
            lp.leftMargin = leftMargin;
            previewHost.setLayoutParams(lp);
        }
        syncPreviewGlDisplaySize(displayW, displayH);
        refreshResolutionHud();
    }

    /** 与预览宿主偶数显示区一致，供 GLES YUV 绘制使用。 */
    private void syncPreviewGlDisplaySize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        FrameLayout.LayoutParams glLp =
                (FrameLayout.LayoutParams) binding.previewGl.getLayoutParams();
        if (glLp.width == width
                && glLp.height == height
                && glLp.gravity == (Gravity.TOP | Gravity.START)
                && glLp.topMargin == 0
                && glLp.leftMargin == 0) {
            return;
        }
        glLp.width = width;
        glLp.height = height;
        glLp.gravity = Gravity.TOP | Gravity.START;
        glLp.topMargin = 0;
        glLp.leftMargin = 0;
        binding.previewGl.setLayoutParams(glLp);
    }

    private void scheduleResolutionSelectionIfPolicyChanged() {
        binding.cameraStage.removeCallbacks(resolutionPolicyRunnable);
        binding.cameraStage.postDelayed(
                resolutionPolicyRunnable, RESOLUTION_POLICY_DEBOUNCE_MS);
    }

    /**
     * 根据当前摄像头 ID、预览比例与预览显示区像素规模，自动挑选预览与拍照分辨率并写入 prefs。
     *
     * @param forceFromAspectChange 用户切换比例时为 true，始终重选并写入；布局抖动时为 false，策略未变则保留设置里的手改。
     */
    private void applyResolutionSelectionForCurrentState(boolean forceFromAspectChange) {
        String camId = resolveCameraIdForResolution();
        if (camId == null) {
            lastResolutionPolicyKey = null;
            refreshResolutionHud();
            return;
        }
        long displayPixels = computePreviewHostDisplayPixelCount();
        if (displayPixels <= 0L) {
            refreshResolutionHud();
            binding.previewHost.post(
                    () -> applyResolutionSelectionForCurrentState(forceFromAspectChange));
            return;
        }
        if (PhotoSavePrefs.getSelectedCameraId(this) == null) {
            PhotoSavePrefs.setSelectedCameraId(this, camId);
        }
        String key = buildResolutionPolicyKey(camId);
        @Nullable String previousKey = lastResolutionPolicyKey;
        boolean policyChanged = forceFromAspectChange || !key.equals(previousKey);
        @Nullable String previousCamId = cameraIdFromPolicyKey(previousKey);
        boolean selectedCameraChanged =
                previousCamId != null && !previousCamId.equals(camId);
        String previousPreviewLabel = PhotoSavePrefs.getPreviewSizeLabel(this);
        String previousCaptureLabel = PhotoSavePrefs.getCaptureSizeLabel(this);
        Size previousStream =
                PreviewStreamSizeResolver.resolve(
                        this,
                        camId,
                        PreviewSizeLabel.parseOrFallback(previousPreviewLabel));
        boolean previewStreamChanged = false;
        boolean captureStreamChanged = false;
        if (policyChanged) {
            lastResolutionPolicyKey = key;
            float target = computeTargetLongPerShortForCurrentAspect();
            Size[] previewSizes = Camera2Enum.getPreviewSizes(this, camId);
            Size[] captureSizes = Camera2Enum.getJpegCaptureSizes(this, camId);
            Size p =
                    AspectResolutionSelector.pickPreviewForTargetRatio(
                            previewSizes, target, displayPixels);
            Size c = AspectResolutionSelector.pickCaptureForTargetRatio(captureSizes, target);
            if (p != null) {
                String label = Camera2Enum.formatSize(p);
                PhotoSavePrefs.setPreviewSizeLabel(this, label);
                Size resolved = PreviewStreamSizeResolver.resolve(this, camId, p);
                previewStreamChanged =
                        forceFromAspectChange
                                || !resolved.equals(previousStream)
                                || !label.equals(previousPreviewLabel);
            }
            if (c != null) {
                String captureLabel = Camera2Enum.formatSize(c);
                captureStreamChanged =
                        previousCaptureLabel == null
                                || !captureLabel.equals(previousCaptureLabel.trim());
                PhotoSavePrefs.setCaptureSizeLabel(this, captureLabel);
            }
        }
        refreshResolutionHud();
        if (previewStreamChanged || selectedCameraChanged || captureStreamChanged) {
            if (hasCameraPermission()) {
                startPreviewIfReady();
            } else {
                applyPreviewStreamSizeFromPrefsOnly();
            }
        }
    }

    /** {@link #buildResolutionPolicyKey} 中摄像头 ID 为第一个 {@code #} 之前的段。 */
    @Nullable
    private static String cameraIdFromPolicyKey(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        int sep = key.indexOf('#');
        return sep < 0 ? key : key.substring(0, sep);
    }

    /**
     * HUD：相机预览/拍照流分辨率（prefs）+ 屏幕上预览宿主区域宽高（随比例与布局变化；与所选预览分辨率可不同）。
     */
    private void refreshResolutionHud() {
        String previewLabel = PhotoSavePrefs.getPreviewSizeLabel(this);
        String captureLabel = PhotoSavePrefs.getCaptureSizeLabel(this);
        String pl =
                previewLabel != null && !previewLabel.isBlank() ? previewLabel.trim() : "—";
        String cl =
                captureLabel != null && !captureLabel.isBlank() ? captureLabel.trim() : "—";

        int[] wh = new int[2];
        readPreviewHostSizePx(wh);
        int dispW = wh[0];
        int dispH = wh[1];
        String area =
                dispW > 0 && dispH > 0
                        ? dispW + "×" + dispH
                        : "—";
        binding.resolutionHudText.setText(
                String.format(
                        Locale.getDefault(),
                        "预览 %s\n拍照 %s\n显示区 %s px",
                        pl,
                        cl,
                        area));
    }

    /** 将预览宿主宽高写入 {@code out[0]}/{@code out[1]}，未知时为 0。 */
    private void readPreviewHostSizePx(@NonNull int[] out) {
        out[0] = 0;
        out[1] = 0;
        ViewGroup.LayoutParams vp = binding.previewHost.getLayoutParams();
        if (vp != null && vp.width > 0 && vp.height > 0) {
            out[0] = vp.width;
            out[1] = vp.height;
            return;
        }
        int aw = binding.previewHost.getWidth();
        int ah = binding.previewHost.getHeight();
        if (aw > 0 && ah > 0) {
            out[0] = aw;
            out[1] = ah;
        }
    }

    private long computePreviewHostDisplayPixelCount() {
        int[] wh = new int[2];
        readPreviewHostSizePx(wh);
        return wh[0] > 0 && wh[1] > 0 ? (long) wh[0] * (long) wh[1] : 0L;
    }

    @Nullable
    private String resolveCameraIdForResolution() {
        String id = PhotoSavePrefs.getSelectedCameraId(this);
        if (id != null && Camera2Enum.listCameraIds(this).contains(id)) {
            return id;
        }
        int facing =
                backCamera
                        ? CameraCharacteristics.LENS_FACING_BACK
                        : CameraCharacteristics.LENS_FACING_FRONT;
        return Camera2Enum.firstCameraIdForFacing(this, facing);
    }

    @NonNull
    private String buildResolutionPolicyKey(@NonNull String camId) {
        float t = computeTargetLongPerShortForCurrentAspect();
        long px = computePreviewHostDisplayPixelCount();
        int bucket = px > 0 ? (int) (px / RESOLUTION_POLICY_DISPLAY_PIXEL_BUCKET) : 0;
        return camId + "#" + aspect.name() + "#" + Math.round(t * 1000f) + "#" + bucket;
    }

    private float computeTargetLongPerShortForCurrentAspect() {
        switch (aspect) {
            case ONE_ONE:
                return 1f;
            case THREE_FOUR:
                return 4f / 3f;
            case NINE_SIXTEEN:
                return 16f / 9f;
            case FULL:
                int sw = binding.cameraStage.getWidth();
                int sh = binding.cameraStage.getHeight();
                if (sw > 0 && sh > 0) {
                    int min = Math.min(sw, sh);
                    int max = Math.max(sw, sh);
                    return min > 0 ? (float) max / (float) min : 16f / 9f;
                }
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int w = dm.widthPixels;
                int h = dm.heightPixels;
                int min2 = Math.min(w, h);
                int max2 = Math.max(w, h);
                return min2 > 0 ? (float) max2 / (float) min2 : 16f / 9f;
            default:
                return 4f / 3f;
        }
    }

}
