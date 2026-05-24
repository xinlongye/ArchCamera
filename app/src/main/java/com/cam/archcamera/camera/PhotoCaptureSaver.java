package com.cam.archcamera.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.cam.archcamera.gallery.GalleryMediaRepository;
import com.cam.archcamera.settings.PhotoSavePrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Writes still JPEG to storage, patches EXIF, and indexes in MediaStore / media scanner. */
public final class PhotoCaptureSaver {

    private static final String TAG = "PhotoCaptureSaver";
    private static final ExecutorService IO_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "PhotoCaptureSaver");
                t.setPriority(Thread.NORM_PRIORITY);
                return t;
            });

    public interface SaveCallback {
        void onSuccess(@NonNull Uri uri);

        void onFailure(@NonNull String message);
    }

    private PhotoCaptureSaver() {}

    public static void saveAsync(
            @NonNull Context context,
            @NonNull byte[] jpeg,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera,
            @NonNull SaveCallback callback) {
        Context app = context.getApplicationContext();
        IO_EXECUTOR.execute(
                () -> {
                    try {
                        Uri uri =
                                saveToStorage(
                                        app, jpeg, captureResult, mirrorHorizontal, isFrontCamera);
                        if (uri != null) {
                            callback.onSuccess(uri);
                        } else {
                            callback.onFailure("save failed");
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "save failed", e);
                        callback.onFailure(e.getMessage() != null ? e.getMessage() : "io error");
                    } catch (RuntimeException e) {
                        Log.e(TAG, "save failed", e);
                        callback.onFailure(e.getMessage() != null ? e.getMessage() : "error");
                    }
                });
    }

    @Nullable
    private static Uri saveToStorage(
            @NonNull Context context,
            @NonNull byte[] jpeg,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        String fileName = buildFileName();
        String saveDir = PhotoSavePrefs.getSaveDirectoryAbsolute(context);
        String relativePath = GalleryMediaRepository.toRelativePathForMediaStore(saveDir);
        if (relativePath != null) {
            return saveViaMediaStore(
                    context,
                    jpeg,
                    fileName,
                    relativePath,
                    captureResult,
                    mirrorHorizontal,
                    isFrontCamera);
        }
        return saveViaFileAndScan(
                context,
                jpeg,
                fileName,
                saveDir,
                captureResult,
                mirrorHorizontal,
                isFrontCamera);
    }

    @Nullable
    private static Uri saveViaMediaStore(
            @NonNull Context context,
            @NonNull byte[] jpeg,
            @NonNull String fileName,
            @NonNull String relativePath,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            return null;
        }
        try (OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) {
                resolver.delete(uri, null, null);
                return null;
            }
            out.write(jpeg);
        }
        writeExifForUri(context, uri, jpeg, captureResult, mirrorHorizontal, isFrontCamera);

        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, done, null, null);
        return uri;
    }

    @Nullable
    private static Uri saveViaFileAndScan(
            @NonNull Context context,
            @NonNull byte[] jpeg,
            @NonNull String fileName,
            @NonNull String saveDir,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        File dir = new File(saveDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create directory " + saveDir);
        }
        File file = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(jpeg);
        }
        writeExifForFile(file.getAbsolutePath(), captureResult, mirrorHorizontal, isFrontCamera);

        final Uri[] outUri = new Uri[1];
        CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(
                context,
                new String[] {file.getAbsolutePath()},
                new String[] {"image/jpeg"},
                (path, uri) -> {
                    outUri[0] = uri;
                    latch.countDown();
                });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return outUri[0];
    }

    private static void writeExifForUri(
            @NonNull Context context,
            @NonNull Uri uri,
            @NonNull byte[] jpeg,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        try (ParcelFileDescriptor pfd =
                context.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (pfd == null) {
                return;
            }
            ExifInterface exif = new ExifInterface(pfd.getFileDescriptor());
            applyCaptureExif(exif, jpeg, captureResult, mirrorHorizontal, isFrontCamera);
            exif.saveAttributes();
        }
    }

    private static void writeExifForFile(
            @NonNull String absolutePath,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        ExifInterface exif = new ExifInterface(absolutePath);
        applyCaptureExif(exif, null, captureResult, mirrorHorizontal, isFrontCamera);
        exif.saveAttributes();
    }

    private static void applyCaptureExif(
            @NonNull ExifInterface exif,
            @Nullable byte[] jpeg,
            @Nullable TotalCaptureResult captureResult,
            boolean mirrorHorizontal,
            boolean isFrontCamera)
            throws IOException {
        if (jpeg != null) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                exif.setAttribute(
                        ExifInterface.TAG_IMAGE_WIDTH, String.valueOf(opts.outWidth));
                exif.setAttribute(
                        ExifInterface.TAG_IMAGE_LENGTH, String.valueOf(opts.outHeight));
            }
        }
        String now =
                new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date());
        exif.setAttribute(ExifInterface.TAG_DATETIME, now);
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, now);

        if (captureResult == null) {
            int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
            exifOrientation = adjustExifOrientationForSave(exifOrientation);
            if (mirrorHorizontal) {
                exifOrientation = mirrorExifOrientation(exifOrientation);
            }
            exifOrientation = applyFrontCameraOrientationFix(exifOrientation, isFrontCamera);
            exif.setAttribute(
                    ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
            return;
        }
        Long exposureNs = captureResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (exposureNs != null && exposureNs > 0L) {
            double sec = exposureNs / 1_000_000_000.0;
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, formatExposureTime(sec));
        }
        Integer iso = captureResult.get(CaptureResult.SENSOR_SENSITIVITY);
        if (iso != null && iso > 0) {
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, String.valueOf(iso));
        }
        Float aperture = captureResult.get(CaptureResult.LENS_APERTURE);
        if (aperture != null && aperture > 0f) {
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, String.valueOf(aperture));
        }
        Float focal = captureResult.get(CaptureResult.LENS_FOCAL_LENGTH);
        if (focal != null && focal > 0f) {
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, focal + "/1");
        }
        Integer wb = captureResult.get(CaptureResult.CONTROL_AWB_MODE);
        if (wb != null) {
            if (wb == android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_OFF) {
                exif.setAttribute(
                        ExifInterface.TAG_WHITE_BALANCE,
                        String.valueOf(ExifInterface.WHITEBALANCE_MANUAL));
            } else {
                exif.setAttribute(
                        ExifInterface.TAG_WHITE_BALANCE,
                        String.valueOf(ExifInterface.WHITEBALANCE_AUTO));
            }
        }

        int exifOrientation = ExifInterface.ORIENTATION_NORMAL;
        Integer jpegOrientation = captureResult.get(CaptureResult.JPEG_ORIENTATION);
        if (jpegOrientation != null) {
            exifOrientation = jpegDegreesToExifOrientation(jpegOrientation);
        }
        exifOrientation = adjustExifOrientationForSave(exifOrientation);
        if (mirrorHorizontal) {
            exifOrientation = mirrorExifOrientation(exifOrientation);
        }
        exifOrientation = applyFrontCameraOrientationFix(exifOrientation, isFrontCamera);
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
    }

    /** Front camera saved upright vs preview needs an extra 180° in EXIF. */
    private static int applyFrontCameraOrientationFix(int orientation, boolean isFrontCamera) {
        return isFrontCamera ? rotateExifOrientation180(orientation) : orientation;
    }

    /** Compensates JPEG stream aspect vs upright display (90° CCW in EXIF). Mirror applied after. */
    private static int adjustExifOrientationForSave(int orientation) {
        return rotateExifOrientationCCW90(orientation);
    }

    /** Maps {@link CaptureResult#JPEG_ORIENTATION} degrees to {@link ExifInterface} constants. */
    private static int jpegDegreesToExifOrientation(int degrees) {
        int norm = ((degrees % 360) + 360) % 360;
        switch (norm) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    /** Applies an additional 180° rotation to EXIF orientation. */
    private static int rotateExifOrientation180(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return ExifInterface.ORIENTATION_FLIP_VERTICAL;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return ExifInterface.ORIENTATION_NORMAL;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return ExifInterface.ORIENTATION_TRANSVERSE;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return ExifInterface.ORIENTATION_ROTATE_270;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return ExifInterface.ORIENTATION_TRANSPOSE;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return ExifInterface.ORIENTATION_ROTATE_90;
            default:
                return ExifInterface.ORIENTATION_ROTATE_180;
        }
    }

    /** Applies an additional 90° counter-clockwise rotation to EXIF orientation. */
    private static int rotateExifOrientationCCW90(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return ExifInterface.ORIENTATION_ROTATE_270;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return ExifInterface.ORIENTATION_TRANSPOSE;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return ExifInterface.ORIENTATION_TRANSVERSE;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return ExifInterface.ORIENTATION_NORMAL;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return ExifInterface.ORIENTATION_FLIP_VERTICAL;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return ExifInterface.ORIENTATION_ROTATE_180;
            default:
                return ExifInterface.ORIENTATION_ROTATE_270;
        }
    }

    /** Horizontal mirror composed into EXIF orientation (no full-image decode). */
    private static int mirrorExifOrientation(int orientation) {
        switch (orientation) {
            case ExifInterface.ORIENTATION_NORMAL:
                return ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return ExifInterface.ORIENTATION_NORMAL;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return ExifInterface.ORIENTATION_FLIP_VERTICAL;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return ExifInterface.ORIENTATION_TRANSPOSE;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return ExifInterface.ORIENTATION_TRANSVERSE;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
        }
    }

    @NonNull
    private static String formatExposureTime(double seconds) {
        if (seconds >= 1.0) {
            return String.format(Locale.US, "%.1f", seconds);
        }
        double denom = Math.round(1.0 / seconds);
        if (denom <= 0) {
            return String.format(Locale.US, "%.6f", seconds);
        }
        return "1/" + (long) denom;
    }

    @NonNull
    private static String buildFileName() {
        String stamp =
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "IMG_" + stamp + ".jpg";
    }
}
