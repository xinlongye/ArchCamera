package com.cam.archcamera.settings;

import android.os.Bundle;
import android.util.Size;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.cam.archcamera.R;
import com.cam.archcamera.camera.Camera2Enum;
import com.cam.archcamera.databinding.ActivitySettingsBinding;
import com.cam.archcamera.util.WindowInsetsHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private List<String> cameraIds = Collections.emptyList();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowInsetsHelper.padTopBarForStatusBar(this, binding.topBar);

        binding.btnBack.setOnClickListener(v -> finish());

        cameraIds = new ArrayList<>(Camera2Enum.listCameraIds(this));
        PhotoSavePrefs.migrateLegacyCameraIndexIfNeeded(this, cameraIds);

        setupCameraIdSpinner();
        setupSavePath();

        binding.switchMirrorFront.setChecked(PhotoSavePrefs.getMirrorFrontEnabled(this));
        binding.switchMirrorFront.setOnCheckedChangeListener(
                (buttonView, isChecked) ->
                        PhotoSavePrefs.setMirrorFrontEnabled(SettingsActivity.this, isChecked));

        if (!cameraIds.isEmpty()) {
            String id = resolveInitialCameraId();
            PhotoSavePrefs.setSelectedCameraId(this, id);
            refreshResolutionSpinners(id);
        } else {
            bindEmptyResolutionSpinners();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cameraIds.isEmpty()) {
            return;
        }
        String savedCam = PhotoSavePrefs.getSelectedCameraId(this);
        if (savedCam != null) {
            int idx = cameraIds.indexOf(savedCam);
            if (idx >= 0) {
                binding.spinnerCameraId.setSelection(idx);
            }
        }
        refreshResolutionSpinners(resolveInitialCameraId());
        binding.switchMirrorFront.setChecked(PhotoSavePrefs.getMirrorFrontEnabled(this));
    }

    private void setupSavePath() {
        binding.editSavePath.setText(PhotoSavePrefs.getSaveDirectoryAbsolute(this));

        binding.btnSavePath.setOnClickListener(
                v -> {
                    String path = binding.editSavePath.getText().toString().trim();
                    if (path.isEmpty()) {
                        Toast.makeText(this, R.string.settings_save_path_empty, Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    PhotoSavePrefs.setSaveDirectoryAbsolute(this, path);
                    binding.editSavePath.setText(PhotoSavePrefs.getSaveDirectoryAbsolute(this));
                    Toast.makeText(this, R.string.settings_save_path_saved, Toast.LENGTH_SHORT)
                            .show();
                });
    }

    @NonNull
    private String resolveInitialCameraId() {
        String saved = PhotoSavePrefs.getSelectedCameraId(this);
        if (saved != null && cameraIds.contains(saved)) {
            return saved;
        }
        return cameraIds.get(0);
    }

    private void setupCameraIdSpinner() {
        List<String> display =
                cameraIds.isEmpty()
                        ? Collections.singletonList(getString(R.string.settings_camera_none))
                        : cameraIds;

        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, R.layout.item_spinner, display);
        adapter.setDropDownViewResource(R.layout.item_spinner);
        binding.spinnerCameraId.setAdapter(adapter);
        binding.spinnerCameraId.setEnabled(!cameraIds.isEmpty());

        if (!cameraIds.isEmpty()) {
            int idx = Math.max(0, cameraIds.indexOf(resolveInitialCameraId()));
            binding.spinnerCameraId.setSelection(idx);
        }

        binding.spinnerCameraId.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (cameraIds.isEmpty()) {
                            return;
                        }
                        String camId = cameraIds.get(position);
                        PhotoSavePrefs.setSelectedCameraId(SettingsActivity.this, camId);
                        refreshResolutionSpinners(camId);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
    }

    private void bindEmptyResolutionSpinners() {
        List<String> empty = Collections.singletonList("—");
        ArrayAdapter<String> previewAdapter =
                new ArrayAdapter<>(this, R.layout.item_spinner, empty);
        previewAdapter.setDropDownViewResource(R.layout.item_spinner);
        binding.spinnerPreviewResolution.setAdapter(previewAdapter);
        binding.spinnerPreviewResolution.setSelection(0);
        binding.spinnerPreviewResolution.setEnabled(false);

        ArrayAdapter<String> captureAdapter =
                new ArrayAdapter<>(this, R.layout.item_spinner, empty);
        captureAdapter.setDropDownViewResource(R.layout.item_spinner);
        binding.spinnerCaptureResolution.setAdapter(captureAdapter);
        binding.spinnerCaptureResolution.setSelection(0);
        binding.spinnerCaptureResolution.setEnabled(false);
    }

    private void refreshResolutionSpinners(@NonNull String cameraId) {
        List<String> previewLabels = sizesToLabels(Camera2Enum.getPreviewSizes(this, cameraId));
        List<String> captureLabels = sizesToLabels(Camera2Enum.getJpegCaptureSizes(this, cameraId));

        bindResolutionSpinner(
                binding.spinnerPreviewResolution,
                previewLabels,
                PhotoSavePrefs.getPreviewSizeLabel(this),
                label -> PhotoSavePrefs.setPreviewSizeLabel(SettingsActivity.this, label));

        bindResolutionSpinner(
                binding.spinnerCaptureResolution,
                captureLabels,
                PhotoSavePrefs.getCaptureSizeLabel(this),
                label -> PhotoSavePrefs.setCaptureSizeLabel(SettingsActivity.this, label));
    }

    private interface ResolutionSelectionCallback {
        void onSelected(@NonNull String label);
    }

    private void bindResolutionSpinner(
            @NonNull Spinner spinner,
            @NonNull List<String> labels,
            @Nullable String savedLabel,
            @NonNull ResolutionSelectionCallback onUserChoice) {
        if (labels.isEmpty()) {
            List<String> placeholder = Collections.singletonList("—");
            ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(this, R.layout.item_spinner, placeholder);
            adapter.setDropDownViewResource(R.layout.item_spinner);
            spinner.setAdapter(adapter);
            spinner.setSelection(0);
            spinner.setEnabled(false);
            return;
        }

        spinner.setEnabled(true);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, R.layout.item_spinner, labels);
        adapter.setDropDownViewResource(R.layout.item_spinner);
        spinner.setAdapter(adapter);

        int idx = findSelectionIndex(labels, savedLabel);
        int selection = idx >= 0 ? idx : 0;

        final boolean[] ignoreSelectionCallback = {true};
        spinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (ignoreSelectionCallback[0]) {
                            return;
                        }
                        onUserChoice.onSelected(labels.get(position));
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });

        spinner.setSelection(selection);
        ignoreSelectionCallback[0] = false;
    }

    private static int findSelectionIndex(@NonNull List<String> labels, @Nullable String saved) {
        if (saved == null) {
            return -1;
        }
        return labels.indexOf(saved);
    }

    @NonNull
    private static List<String> sizesToLabels(@NonNull Size[] sizes) {
        List<String> out = new ArrayList<>(sizes.length);
        for (Size s : sizes) {
            out.add(Camera2Enum.formatSize(s));
        }
        return out;
    }
}
