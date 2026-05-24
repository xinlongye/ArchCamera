package com.cam.archcamera.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cam.archcamera.preview.PreviewSizeLabel;
import com.cam.archcamera.settings.PhotoSavePrefs;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Camera2 preview (YUV ImageReader) and still capture (JPEG ImageReader).
 */
public final class Camera2PreviewController {

    private static final String TAG = "Camera2Preview";
    private static final int PREVIEW_READER_MAX_IMAGES = 8;
    private static final int STILL_READER_MAX_IMAGES = 2;

    public interface StillCaptureCallback {
        void onSuccess(@NonNull Uri uri);

        void onFailure(@NonNull String message);
    }

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final AtomicBoolean captureInFlight = new AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Nullable private HandlerThread cameraThread;
    @Nullable private Handler cameraHandler;
    @Nullable private CameraDevice cameraDevice;
    @Nullable private CameraCaptureSession captureSession;
    @Nullable private ImageReader previewImageReader;
    @Nullable private PreviewImageAvailableListener previewListener;
    @Nullable private ImageReader stillImageReader;
    @Nullable private StillCaptureImageListener stillListener;

    @Nullable private Context appContext;
    @Nullable private String activeCameraId;
    @Nullable private Size activeStreamSize;
    @Nullable private Size activeCaptureSize;
    @Nullable private PreviewFrameSink activeSink;

    @Nullable private StillCaptureCallback pendingCaptureCallback;
    @Nullable private byte[] pendingJpeg;
    @Nullable private TotalCaptureResult pendingCaptureResult;

    public boolean isStarting() {
        return starting.get();
    }

    public void start(
            @NonNull Context context,
            @NonNull String cameraId,
            @NonNull Size streamSize,
            @NonNull PreviewFrameSink sink) {
        Size even = PreviewStreamSizeResolver.ensureEven(streamSize);
        Size resolved = PreviewStreamSizeResolver.resolve(context, cameraId, even);
        Size capturePreferred =
                PreviewSizeLabel.parseOrFallback(PhotoSavePrefs.getCaptureSizeLabel(context));
        Size captureResolved = CaptureStreamSizeResolver.resolve(context, cameraId, capturePreferred);

        if (activeCameraId != null
                && activeCameraId.equals(cameraId)
                && activeStreamSize != null
                && activeStreamSize.equals(resolved)
                && activeCaptureSize != null
                && activeCaptureSize.equals(captureResolved)
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
        activeCaptureSize = captureResolved;
        activeSink = sink;

        sink.setStreamSize(resolved.getWidth(), resolved.getHeight());
        applyDisplayTransform(context, cameraId, sink);

        ensureCameraThread();

        previewImageReader =
                ImageReader.newInstance(
                        resolved.getWidth(),
                        resolved.getHeight(),
                        ImageFormat.YUV_420_888,
                        PREVIEW_READER_MAX_IMAGES);
        previewListener =
                new PreviewImageAvailableListener(
                        sink, resolved.getWidth(), resolved.getHeight());
        previewImageReader.setOnImageAvailableListener(previewListener, cameraHandler);

        stillImageReader =
                ImageReader.newInstance(
                        captureResolved.getWidth(),
                        captureResolved.getHeight(),
                        ImageFormat.JPEG,
                        STILL_READER_MAX_IMAGES);
        stillListener = new StillCaptureImageListener(this::onStillJpegAvailable);
        stillImageReader.setOnImageAvailableListener(stillListener, cameraHandler);

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
        ImageReader previewReader = previewImageReader;
        ImageReader stillReader = stillImageReader;
        Handler handler = cameraHandler;
        Context ctx = appContext;
        String cameraId = activeCameraId;
        if (previewReader == null || stillReader == null || handler == null || ctx == null || cameraId == null) {
            starting.set(false);
            return;
        }
        Surface previewSurface = previewReader.getSurface();
        Surface stillSurface = stillReader.getSurface();
        try {
            device.createCaptureSession(
                    Arrays.asList(previewSurface, stillSurface),
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
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(previewSurface);
                                builder.set(
                                        CaptureRequest.CONTROL_MODE,
                                        CaptureRequest.CONTROL_MODE_AUTO);
                                applyPreviewTargetFps(builder);
                                session.setRepeatingRequest(builder.build(), null, handler);
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
                                                + activeStreamSize.getHeight()
                                                + " capture "
                                                + activeCaptureSize.getWidth()
                                                + "x"
                                                + activeCaptureSize.getHeight());
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

    /** Issues a single still capture; delivers result on the main thread. */
    public void captureStill(@NonNull Context context, @NonNull StillCaptureCallback callback) {
        if (!open.get() || captureInFlight.get()) {
            mainHandler.post(
                    () ->
                            callback.onFailure(
                                    captureInFlight.get() ? "capture in progress" : "camera not ready"));
            return;
        }
        Handler handler = cameraHandler;
        CameraCaptureSession session = captureSession;
        CameraDevice device = cameraDevice;
        ImageReader stillReader = stillImageReader;
        String cameraId = activeCameraId;
        if (handler == null
                || session == null
                || device == null
                || stillReader == null
                || cameraId == null) {
            mainHandler.post(() -> callback.onFailure("camera not ready"));
            return;
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            mainHandler.post(() -> callback.onFailure("capture in progress"));
            return;
        }
        pendingCaptureCallback = callback;
        pendingJpeg = null;
        pendingCaptureResult = null;

        handler.post(
                () -> {
                    try {
                        CaptureRequest.Builder stillBuilder =
                                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        stillBuilder.addTarget(stillReader.getSurface());
                        stillBuilder.set(
                                CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        stillBuilder.set(
                                CaptureRequest.JPEG_ORIENTATION,
                                JpegOrientationHelper.computeJpegOrientation(context, cameraId));
                        session.capture(
                                stillBuilder.build(),
                                new CameraCaptureSession.CaptureCallback() {
                                    @Override
                                    public void onCaptureCompleted(
                                            @NonNull CameraCaptureSession s,
                                            @NonNull CaptureRequest request,
                                            @NonNull TotalCaptureResult result) {
                                        pendingCaptureResult = result;
                                        tryCompleteStillCapture();
                                    }

                                    @Override
                                    public void onCaptureFailed(
                                            @NonNull CameraCaptureSession s,
                                            @NonNull CaptureRequest request,
                                            @NonNull CaptureFailure failure) {
                                        Log.e(TAG, "Still capture failed: " + failure.getReason());
                                        finishCaptureFailure("capture failed");
                                    }
                                },
                                handler);
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "capture still failed", e);
                        finishCaptureFailure("capture failed");
                    }
                });
    }

    private void onStillJpegAvailable(@NonNull byte[] jpeg) {
        pendingJpeg = jpeg;
        tryCompleteStillCapture();
    }

    private void tryCompleteStillCapture() {
        byte[] jpeg = pendingJpeg;
        TotalCaptureResult result = pendingCaptureResult;
        StillCaptureCallback callback = pendingCaptureCallback;
        Context ctx = appContext;
        String cameraId = activeCameraId;
        if (jpeg == null || result == null || callback == null || ctx == null || cameraId == null) {
            return;
        }

        pendingJpeg = null;
        pendingCaptureResult = null;
        pendingCaptureCallback = null;

        Integer facing = Camera2Enum.getLensFacing(ctx, cameraId);
        boolean isFrontCamera =
                facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        boolean mirror = isFrontCamera && PhotoSavePrefs.getMirrorFrontEnabled(ctx);

        final StillCaptureCallback deliver = callback;
        PhotoCaptureSaver.saveAsync(
                ctx,
                jpeg,
                result,
                mirror,
                isFrontCamera,
                new PhotoCaptureSaver.SaveCallback() {
                    @Override
                    public void onSuccess(@NonNull Uri uri) {
                        captureInFlight.set(false);
                        mainHandler.post(() -> deliver.onSuccess(uri));
                    }

                    @Override
                    public void onFailure(@NonNull String message) {
                        captureInFlight.set(false);
                        mainHandler.post(() -> deliver.onFailure(message));
                    }
                });
    }

    private void finishCaptureFailure(@NonNull String message) {
        StillCaptureCallback callback = pendingCaptureCallback;
        pendingJpeg = null;
        pendingCaptureResult = null;
        pendingCaptureCallback = null;
        captureInFlight.set(false);
        if (callback != null) {
            mainHandler.post(() -> callback.onFailure(message));
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
        captureInFlight.set(false);
        pendingJpeg = null;
        pendingCaptureResult = null;
        pendingCaptureCallback = null;
        stopInternal();
    }

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

        ImageReader previewReader = previewImageReader;
        previewImageReader = null;
        previewListener = null;
        if (previewReader != null) {
            previewReader.close();
        }

        ImageReader stillReader = stillImageReader;
        stillImageReader = null;
        stillListener = null;
        if (stillReader != null) {
            stillReader.close();
        }

        activeCameraId = null;
        activeStreamSize = null;
        activeCaptureSize = null;
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
            Size stream = activeStreamSize;
            android.hardware.camera2.params.StreamConfigurationMap map =
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
