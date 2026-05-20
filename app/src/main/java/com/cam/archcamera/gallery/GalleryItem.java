package com.cam.archcamera.gallery;

import android.net.Uri;

import androidx.annotation.NonNull;

public final class GalleryItem {

    private final long id;
    @NonNull private final Uri contentUri;

    public GalleryItem(long id, @NonNull Uri contentUri) {
        this.id = id;
        this.contentUri = contentUri;
    }

    public long getId() {
        return id;
    }

    @NonNull
    public Uri getContentUri() {
        return contentUri;
    }
}
