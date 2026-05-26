package com.cam.archcamera.preview;

import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.R;
import com.cam.archcamera.camera.Camera3ACapabilities;
import com.cam.archcamera.camera.Camera3ADisplayValues;
import com.cam.archcamera.camera.Camera3AFormatters;
import com.cam.archcamera.camera.Camera3ASettings;
import com.cam.archcamera.camera.ShutterStopTable;
import com.cam.archcamera.databinding.ItemCamera3aRowBinding;

import java.util.ArrayList;
import java.util.List;

/** Binds the professional-mode 3A panel rows to {@link Camera3ASettings}. */
public final class Camera3APanelController {

    public interface Listener {
        void onSettingsChanged(@NonNull Camera3ASettings settings);

        /** Slider or live-sync UI refresh; does not imply a new Camera2 request. */
        void onDisplayChanged();
    }

    private enum RowKind {
        FOCUS,
        EV,
        ISO,
        SHUTTER,
        WB
    }

    private static final class Row {
        final RowKind kind;
        final ItemCamera3aRowBinding binding;

        Row(RowKind kind, ItemCamera3aRowBinding binding) {
            this.kind = kind;
            this.binding = binding;
        }
    }

    private final LinearLayout panel;
    private final Listener listener;
    private final List<Row> rows = new ArrayList<>();
    private Camera3ASettings settings = Camera3ASettings.autoDefaults();
    @Nullable private Camera3ACapabilities capabilities;
    private boolean suppressNotify;

    public Camera3APanelController(@NonNull LinearLayout panel, @NonNull Listener listener) {
        this.panel = panel;
        this.listener = listener;
    }

    public void setup() {
        panel.removeAllViews();
        rows.clear();
        LayoutInflater inflater = LayoutInflater.from(panel.getContext());
        addFocusRow(inflater);
        addEvRow(inflater);
        addIsoRow(inflater);
        addShutterRow(inflater);
        addWbRow(inflater);
        refreshEvRowEnabled();
    }

    @NonNull
    public Camera3ASettings getSettings() {
        return Camera3ASettings.copyOf(settings);
    }

    @NonNull
    public Camera3ADisplayValues buildDisplayValuesFromUi() {
        syncSettingsFromUi();
        return Camera3AFormatters.fromSettings(settings);
    }

    public void resetToAutoDefaults() {
        settings = Camera3ASettings.autoDefaults();
        if (capabilities != null) {
            settings = capabilities.clampSettings(settings);
        }
        applySettingsToUi();
        notifyChanged();
        notifyDisplayChanged();
    }

    public void bindCapabilities(@Nullable Camera3ACapabilities caps) {
        capabilities = caps;
        if (caps != null) {
            settings = caps.clampSettings(settings);
        }
        applySettingsToUi();
        refreshRowConstraints();
        refreshEvRowEnabled();
    }

    /** Updates auto (A) rows from preview capture results without re-applying Camera2. */
    public void syncAutoRowsFromLive(@NonNull Camera3ADisplayValues live) {
        suppressNotify = true;
        try {
            if (!settings.focusManual && live.focusProgress >= 0) {
                settings.focusDistanceNorm = live.focusProgress / 100f;
                applyLiveToRow(RowKind.FOCUS, live.focusProgress);
            }
            if (!settings.evManual && !Float.isNaN(live.ev)) {
                settings.evCompensation = live.ev;
                int progress = ShutterStopTable.progressFromEv(live.ev);
                applyLiveToRow(RowKind.EV, progress);
            }
            if (!settings.isoManual && live.iso > 0) {
                settings.iso = live.iso;
                applyLiveToRow(RowKind.ISO, isoToProgress(live.iso));
            }
            if (!settings.shutterManual && live.shutterIndex >= 0) {
                settings.shutterIndex = live.shutterIndex;
                if (capabilities != null) {
                    settings.exposureTimeNs =
                            ShutterStopTable.indexToExposureTimeNs(
                                    live.shutterIndex, capabilities.exposureTimeRange);
                }
                applyLiveToRow(RowKind.SHUTTER, live.shutterIndex);
            }
            if (!settings.wbManual && live.colorTemperatureK > 0) {
                settings.colorTemperatureK = live.colorTemperatureK;
                applyLiveToRow(
                        RowKind.WB, ShutterStopTable.progressFromWb(live.colorTemperatureK));
            }
        } finally {
            suppressNotify = false;
        }
    }

    private void applyLiveToRow(@NonNull RowKind kind, int progress) {
        for (Row row : rows) {
            if (row.kind == kind) {
                row.binding.camera3aSeek.setProgress(progress);
                updateValueLabel(kind, row.binding, progress);
                break;
            }
        }
    }

    private void addFocusRow(@NonNull LayoutInflater inflater) {
        ItemCamera3aRowBinding row = ItemCamera3aRowBinding.inflate(inflater, panel, false);
        row.camera3aName.setText(R.string.camera_3a_focus);
        row.camera3aSeek.setMax(100);
        wireRow(RowKind.FOCUS, row);
        panel.addView(row.getRoot());
    }

    private void addEvRow(@NonNull LayoutInflater inflater) {
        ItemCamera3aRowBinding row = ItemCamera3aRowBinding.inflate(inflater, panel, false);
        row.camera3aName.setText(R.string.camera_3a_ev);
        wireRow(RowKind.EV, row);
        panel.addView(row.getRoot());
    }

    private void addIsoRow(@NonNull LayoutInflater inflater) {
        ItemCamera3aRowBinding row = ItemCamera3aRowBinding.inflate(inflater, panel, false);
        row.camera3aName.setText(R.string.camera_3a_iso);
        wireRow(RowKind.ISO, row);
        panel.addView(row.getRoot());
    }

    private void addShutterRow(@NonNull LayoutInflater inflater) {
        ItemCamera3aRowBinding row = ItemCamera3aRowBinding.inflate(inflater, panel, false);
        row.camera3aName.setText(R.string.camera_3a_shutter);
        row.camera3aSeek.setMax(ShutterStopTable.count() - 1);
        wireRow(RowKind.SHUTTER, row);
        panel.addView(row.getRoot());
    }

    private void addWbRow(@NonNull LayoutInflater inflater) {
        ItemCamera3aRowBinding row = ItemCamera3aRowBinding.inflate(inflater, panel, false);
        row.camera3aName.setText(R.string.camera_3a_wb);
        wireRow(RowKind.WB, row);
        panel.addView(row.getRoot());
    }

    private void wireRow(@NonNull RowKind kind, @NonNull ItemCamera3aRowBinding row) {
        rows.add(new Row(kind, row));
        row.camera3aSeek.setEnabled(false);
        row.camera3aAutoManual.setText(R.string.camera_3a_auto);

        row.camera3aAutoManual.setOnClickListener(
                v -> {
                    if (!row.camera3aAutoManual.isEnabled()) {
                        return;
                    }
                    boolean manual = !row.camera3aSeek.isEnabled();
                    setRowManual(kind, manual);
                    syncSettingsFromUi();
                    refreshEvRowEnabled();
                    notifyChanged();
                    notifyDisplayChanged();
                });

        row.camera3aSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        updateValueLabel(kind, row, progress);
                        if (fromUser) {
                            syncSettingsFromUi();
                            notifyDisplayChanged();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        syncSettingsFromUi();
                        refreshEvRowEnabled();
                        notifyChanged();
                    }
                });
    }

    private void setRowManual(@NonNull RowKind kind, boolean manual) {
        for (Row row : rows) {
            if (row.kind == kind) {
                row.binding.camera3aSeek.setEnabled(manual);
                row.binding.camera3aAutoManual.setText(
                        manual ? R.string.camera_3a_manual : R.string.camera_3a_auto);
                break;
            }
        }
    }

    private void syncSettingsFromUi() {
        for (Row row : rows) {
            ItemCamera3aRowBinding b = row.binding;
            boolean manual = b.camera3aSeek.isEnabled();
            int progress = b.camera3aSeek.getProgress();
            switch (row.kind) {
                case FOCUS:
                    settings.focusManual = manual;
                    settings.focusDistanceNorm = progress / 100f;
                    break;
                case EV:
                    settings.evManual = manual;
                    settings.evCompensation = ShutterStopTable.evFromProgress(progress);
                    break;
                case ISO:
                    settings.isoManual = manual;
                    settings.iso = isoFromProgress(progress);
                    break;
                case SHUTTER:
                    settings.shutterManual = manual;
                    settings.shutterIndex = progress;
                    settings.exposureTimeNs =
                            ShutterStopTable.indexToExposureTimeNs(
                                    progress,
                                    capabilities != null
                                            ? capabilities.exposureTimeRange
                                            : null);
                    break;
                case WB:
                    settings.wbManual = manual;
                    settings.colorTemperatureK = ShutterStopTable.wbFromProgress(progress);
                    break;
                default:
                    break;
            }
        }
        if (capabilities != null) {
            settings = capabilities.clampSettings(settings);
        }
    }

    private void applySettingsToUi() {
        for (Row row : rows) {
            ItemCamera3aRowBinding b = row.binding;
            switch (row.kind) {
                case FOCUS:
                    setRowManual(RowKind.FOCUS, settings.focusManual);
                    b.camera3aSeek.setProgress(Math.round(settings.focusDistanceNorm * 100f));
                    updateValueLabel(RowKind.FOCUS, b, b.camera3aSeek.getProgress());
                    break;
                case EV:
                    setRowManual(RowKind.EV, settings.evManual);
                    b.camera3aSeek.setProgress(ShutterStopTable.progressFromEv(settings.evCompensation));
                    updateValueLabel(RowKind.EV, b, b.camera3aSeek.getProgress());
                    break;
                case ISO:
                    setRowManual(RowKind.ISO, settings.isoManual);
                    b.camera3aSeek.setProgress(isoToProgress(settings.iso));
                    updateValueLabel(RowKind.ISO, b, b.camera3aSeek.getProgress());
                    break;
                case SHUTTER:
                    setRowManual(RowKind.SHUTTER, settings.shutterManual);
                    b.camera3aSeek.setProgress(settings.shutterIndex);
                    updateValueLabel(RowKind.SHUTTER, b, b.camera3aSeek.getProgress());
                    break;
                case WB:
                    setRowManual(RowKind.WB, settings.wbManual);
                    b.camera3aSeek.setProgress(
                            ShutterStopTable.progressFromWb(settings.colorTemperatureK));
                    updateValueLabel(RowKind.WB, b, b.camera3aSeek.getProgress());
                    break;
                default:
                    break;
            }
        }
    }

    private void refreshRowConstraints() {
        Camera3ACapabilities caps = capabilities;
        if (caps == null) {
            return;
        }
        for (Row row : rows) {
            ItemCamera3aRowBinding b = row.binding;
            switch (row.kind) {
                case FOCUS:
                    b.camera3aAutoManual.setEnabled(caps.canManualFocus());
                    if (!caps.canManualFocus() && settings.focusManual) {
                        settings.focusManual = false;
                        setRowManual(RowKind.FOCUS, false);
                    }
                    break;
                case EV:
                    b.camera3aSeek.setMax(caps.evSeekMax);
                    break;
                case ISO:
                    b.camera3aSeek.setMax(caps.isoSeekMax);
                    break;
                case WB:
                    b.camera3aSeek.setMax(caps.wbSeekMax);
                    b.camera3aAutoManual.setEnabled(caps.canManualWb());
                    if (!caps.canManualWb() && settings.wbManual) {
                        settings.wbManual = false;
                        setRowManual(RowKind.WB, false);
                    }
                    break;
                case SHUTTER:
                    b.camera3aSeek.setMax(ShutterStopTable.count() - 1);
                    break;
                default:
                    break;
            }
        }
    }

    /** EV row disabled when ISO or shutter is manual (AE_MODE_OFF). */
    private void refreshEvRowEnabled() {
        boolean manualExposure = settings.isoManual || settings.shutterManual;
        for (Row row : rows) {
            if (row.kind == RowKind.EV) {
                boolean enable = !manualExposure;
                row.binding.camera3aAutoManual.setEnabled(enable);
                row.binding.camera3aSeek.setEnabled(enable && settings.evManual);
                if (manualExposure && settings.evManual) {
                    settings.evManual = false;
                    setRowManual(RowKind.EV, false);
                }
                break;
            }
        }
    }

    private void updateValueLabel(
            @NonNull RowKind kind, @NonNull ItemCamera3aRowBinding row, int progress) {
        switch (kind) {
            case FOCUS:
                row.camera3aValue.setText(Camera3AFormatters.formatFocus(progress));
                break;
            case EV:
                row.camera3aValue.setText(
                        Camera3AFormatters.formatEv(ShutterStopTable.evFromProgress(progress)));
                break;
            case ISO:
                row.camera3aValue.setText(Camera3AFormatters.formatIso(isoFromProgress(progress)));
                break;
            case SHUTTER:
                row.camera3aValue.setText(Camera3AFormatters.formatShutter(progress));
                break;
            case WB:
                row.camera3aValue.setText(
                        Camera3AFormatters.formatWb(ShutterStopTable.wbFromProgress(progress)));
                break;
            default:
                break;
        }
    }

    private int isoFromProgress(int progress) {
        if (capabilities != null) {
            return capabilities.clampIso(
                    capabilities.isoRange.getLower() + progress * capabilities.isoStep);
        }
        return ShutterStopTable.isoFromProgress(progress);
    }

    private int isoToProgress(int iso) {
        if (capabilities != null) {
            return Math.max(
                    0,
                    (capabilities.clampIso(iso) - capabilities.isoRange.getLower())
                            / capabilities.isoStep);
        }
        return ShutterStopTable.progressFromIso(iso);
    }

    private void notifyChanged() {
        if (suppressNotify) {
            return;
        }
        listener.onSettingsChanged(getSettings());
    }

    private void notifyDisplayChanged() {
        listener.onDisplayChanged();
    }
}
