package com.cam.archcamera.gallery;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class GalleryMediaRepository {

    private GalleryMediaRepository() {}

    /**
     * Lists images under {@code saveDirectoryAbsolute} that appear in MediaStore (RELATIVE_PATH match).
     */
    @NonNull
    public static List<GalleryItem> queryImagesInSaveDirectory(
            @NonNull Context context, @NonNull String saveDirectoryAbsolute) {
        List<GalleryItem> out = new ArrayList<>();
        String relative = toRelativePathForMediaStore(saveDirectoryAbsolute);
        if (relative == null) {
            return out;
        }

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Images.Media._ID,
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ?";
        String[] selectionArgs = {relative};
        String sortOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        try (Cursor cursor =
                resolver.query(collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor == null) {
                return out;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                out.add(new GalleryItem(id, uri));
            }
        }
        return out;
    }

    /**
     * Newest image under {@code saveDirectoryAbsolute} in MediaStore (same rules as {@link
     * #queryImagesInSaveDirectory}), or null if none.
     */
    @Nullable
    public static GalleryItem queryLatestImageInSaveDirectory(
            @NonNull Context context, @NonNull String saveDirectoryAbsolute) {
        String relative = toRelativePathForMediaStore(saveDirectoryAbsolute);
        if (relative == null) {
            return null;
        }

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Images.Media._ID,
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " = ?";
        String[] selectionArgs = {relative};
        String sortOrder = MediaStore.MediaColumns.DATE_MODIFIED + " DESC";

        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        try (Cursor cursor =
                resolver.query(collection, projection, selection, selectionArgs, sortOrder)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            long id = cursor.getLong(idCol);
            return new GalleryItem(id, ContentUris.withAppendedId(collection, id));
        }
    }

    /**
     * MediaStore RELATIVE_PATH segment for a save directory under primary external storage, e.g.
     * {@code DCIM/Camera/} (trailing slash required).
     */
    @Nullable
    public static String toRelativePathForMediaStore(@NonNull String saveDirectoryAbsolute) {
        File dir;
        try {
            dir = new File(saveDirectoryAbsolute.trim()).getCanonicalFile();
        } catch (IOException e) {
            dir = new File(saveDirectoryAbsolute.trim());
        }
        File root;
        try {
            root = Environment.getExternalStorageDirectory().getCanonicalFile();
        } catch (IOException e) {
            root = Environment.getExternalStorageDirectory();
        }
        String dirPath = dir.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        if (!(dirPath.equals(rootPath) || dirPath.startsWith(rootPath + "/"))) {
            return null;
        }
        String suffix = dirPath.substring(rootPath.length()).replace('\\', '/').replaceFirst("^/+", "");
        if (suffix.isEmpty()) {
            return null;
        }
        if (!suffix.endsWith("/")) {
            suffix = suffix + "/";
        }
        return suffix;
    }
}
