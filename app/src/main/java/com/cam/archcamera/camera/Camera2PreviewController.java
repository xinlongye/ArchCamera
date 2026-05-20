package com.cam.archcamera.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.preview.PreviewSizeLabel;
import com.cam.archcamera.settings.PhotoSavePrefs;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 preview: ImageReader (YUV_420_888) → NV21 → {@link PreviewFrameSink}.
 */
public final class Camera2PreviewController {

    private static final String TAG = "Camera2Preview";
    private static final int IMAGE_READER_MAX_IMAGES = 4;

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(false);

    @Nullable private HandlerThread cameraThread;
    @Nullable private Handler cameraHandler;
    @Nullable private CameraDevice cameraDevice;
    @Nullable private CameraCaptureSession captureSession;
    @Nullable private ImageReader imageReader;
    @Nullable private PreviewImageAvailableListener imageListener;

    @Nullable private Context appContext;
    @Nullable private String activeCameraId;
    @Nullable private Size activeStreamSize;
    @Nullable private PreviewFrameSink activeSink;

    public boolean isStarting() {
        return starting.get();
    }

    public void start(
            @NonNull Context context,
            @NonNull String cameraId,
            @NonNull Size streamSize,
            @NonNull PreviewFrameSink sink) {
        Size even = PreviewStreamSizeResolver.ensureEven(streamSize);
        Size resolved =
                PreviewStreamSizeResolver.resolve(
                        context, cameraId, even);
        if (activeCameraId != null
                && activeCameraId.equals(cameraId)
                && activeStreamSize != null
                && activeStreamSize.equals(resolved)
                && (open.get() || starting.get())) {
            applyDisplayTransform(context, cameraId, sink);
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            return;
        }
        sink.setPreviewStreaming(false);
        sink.invalidatePreviewFrames();
        stopInternal();
        appContext = context.getApplicationContext();
        activeCameraId = cameraId;
        activeStreamSize = resolved;
        activeSink = sink;

        sink.setStreamSize(resolved.getWidth(), resolved.getHeight());
        applyDisplayTransform(context, cameraId, sink);

        ensureCameraThread();

        imageReader =
                ImageReader.newInstance(
                        resolved.getWidth(),
                        resolved.getHeight(),
                        android.graphics.ImageFormat.YUV_420_888,
                        IMAGE_READER_MAX_IMAGES);
        imageListener =
                new PreviewImageAvailableListener(
                        sink, resolved.getWidth(), resolved.getHeight());
        imageReader.setOnImageAvailableListener(imageListener, cameraHandler);

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            starting.set(false);
            return;
        }
        try {
            manager.openCamera(
                    cameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            if (!starting.get() || activeCameraId == null) {
                                camera.close();
                                return;
                            }
                            cameraDevice = camera;
                            createSession(camera);
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            Log.w(TAG, "Camera disconnected");
                            stop();
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            Log.e(TAG, "Camera error: " + error);
                            stop();
                        }
                    },
                    cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed", e);
            stop();
        }
    }

    private void createSession(@NonNull CameraDevice device) {
        ImageReader reader = imageReader;
        Handler handler = cameraHandler;
        if (reader == null || handler == null) {
            starting.set(false);
            return;
        }
        Surface surface = reader.getSurface();
        try {
            device.createCaptureSession(
                    Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice != device) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        device.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(surface);
                                builder.set(
                                        CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO);
                                applyPreviewTargetFps(builder);
                                session.setRepeatingRequest(
                                        builder.build(), null, handler);
                                open.set(true);
                                starting.set(false);
                                PreviewFrameSink streamingSink = activeSink;
                                if (streamingSink != null) {
                                    streamingSink.setPreviewStreaming(true);
                                }
                                Log.i(
                                        TAG,
                                        "Preview started "
                                                + activeStreamSize.getWidth()
                                                + "x"
                                                + activeStreamSize.getHeight());
                            } catch (CameraAccessException | IllegalStateException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                                stop();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Session configure failed");
                            stop();
                        }
                    },
                    handler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
            stop();
        }
    }

    private static void applyDisplayTransform(
            @NonNull Context context,
            @NonNull String cameraId,
            @NonNull PreviewFrameSink sink) {
        CameraPreviewTransform.Result t = CameraPreviewTransform.compute(context, cameraId);
        sink.setDisplayTransform(t.rotationDegrees, t.mirrorHorizontal);
    }

    public void restartIfConfigChanged(
            @NonNull Context context,
            @Nullable String cameraId,
            @Nullable Size streamSize,
            @NonNull PreviewFrameSink sink) {
        if (cameraId == null) {
            stop();
            return;
        }
        Size preferred =
                streamSize != null
                        ? streamSize
                        : PreviewSizeLabel.parseOrFallback(
                                PhotoSavePrefs.getPreviewSizeLabel(context));
        start(context, cameraId, preferred, sink);
    }

    public void stop() {
        open.set(false);
        starting.set(false);
        stopInternal();
    }

    /** Releases camera resources; keeps the background thread alive for the next {@link #start}. */
    private void stopInternal() {
        PreviewFrameSink sink = activeSink;
        if (sink != null) {
            sink.setPreviewStreaming(false);
            sink.invalidatePreviewFrames();
        }

        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "stopRepeating ignored", e);
            }
            try {
                session.abortCaptures();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "abortCaptures ignored", e);
            }
            try {
                session.close();
            } catch (IllegalStateException e) {
                Log.w(TAG, "session.close ignored", e);
            }
        }

        CameraDevice device = cameraDevice;
        cameraDevice = null;
        if (device != null) {
            try {
                device.close();
            } catch (IllegalStateException e) {
                Log.w(TAG, "device.close ignored", e);
            }
        }

        ImageReader reader = imageReader;
        imageReader = null;
        imageListener = null;
        if (reader != null) {
            reader.close();
        }

        activeCameraId = null;
        activeStreamSize = null;
        activeSink = null;
        appContext = null;
    }

    private void applyPreviewTargetFps(@NonNull CaptureRequest.Builder builder) {
        Context ctx = appContext;
        String cameraId = activeCameraId;
        if (ctx == null || cameraId == null) {
            return;
        }
        CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Range<Integer>[] available =
                    chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> target = PreviewTargetFps.pickAeTargetFpsRange(available);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, target);
            int maxAe = PreviewTargetFps.maxAeFpsUpperBound(available);
            Log.i(
                    TAG,
                    "Preview AE available="
                            + PreviewTargetFps.formatAeRanges(available)
                            + " selected="
                            + target
                            + " (device max AE "
                            + maxAe
                            + " fps"
                            + (PreviewTargetFps.supportsTargetAeFps(available)
                                    ? ", 60 supported"
                                    : ", 60 not in HAL list")
                            + ")");
            Size stream = activeStreamSize;
            StreamConfigurationMap map =
                    PreviewTargetFps.getStreamConfigurationMap(chars);
            if (stream != null && map != null) {
                float streamMax = PreviewTargetFps.maxYuvOutputFps(map, stream);
                Log.i(
                        TAG,
                        "Preview YUV "
                                + stream.getWidth()
                                + "x"
                                + stream.getHeight()
                                + " HAL max ~"
                                + String.format(java.util.Locale.US, "%.1f", streamMax)
                                + " fps");
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "applyPreviewTargetFps failed", e);
        }
    }

    /** Stops preview and tears down the background thread (e.g. activity destroy). */
    public void shutdown() {
        stop();
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private synchronized void ensureCameraThread() {
        if (cameraThread != null && cameraHandler != null) {
            return;
        }
        cameraThread = new HandlerThread("CameraPreview");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraHandler.post(
                () ->
                        Process.setThreadPriority(
                                Process.myTid(), Process.THREAD_PRIORITY_URGENT_DISPLAY));
    }

    public boolean isOpen() {
        return open.get();
    }
}
