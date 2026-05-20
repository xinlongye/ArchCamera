package com.cam.archcamera.gallery;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cam.archcamera.R;
import com.cam.archcamera.databinding.ActivityGalleryBinding;
import com.cam.archcamera.settings.PhotoSavePrefs;
import com.cam.archcamera.util.WindowInsetsHelper;
import com.cam.archcamera.imageviewer.ImageViewerActivity;

import java.util.List;

public final class GalleryActivity extends AppCompatActivity {

    private ActivityGalleryBinding binding;
    private GalleryAdapter adapter;

    private final ActivityResultLauncher<String> requestReadImages =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), granted -> refreshGalleryUi());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGalleryBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        WindowInsetsHelper.padTopBarForStatusBar(this, binding.topBar);

        binding.btnBack.setOnClickListener(v -> finish());

        adapter =
                new GalleryAdapter(
                        getLayoutInflater(),
                        item -> {
                            Intent i = new Intent(this, ImageViewerActivity.class);
                            i.setData(item.getContentUri());
                            startActivity(i);
                        });
        RecyclerView rv = binding.recyclerGallery;
        rv.setLayoutManager(new GridLayoutManager(this, 3));
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                == PackageManager.PERMISSION_GRANTED) {
            refreshGalleryUi();
        } else {
            requestReadImages.launch(Manifest.permission.READ_MEDIA_IMAGES);
        }
    }

    private void refreshGalleryUi() {
        boolean granted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                        == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            adapter.submit(List.of());
            binding.recyclerGallery.setVisibility(View.GONE);
            binding.emptyGallery.setVisibility(View.VISIBLE);
            binding.emptyGallery.setText(R.string.gallery_permission_required);
            return;
        }

        String dir = PhotoSavePrefs.getSaveDirectoryAbsolute(this);
        List<GalleryItem> items = GalleryMediaRepository.queryImagesInSaveDirectory(this, dir);
        adapter.submit(items);
        if (items.isEmpty()) {
            binding.recyclerGallery.setVisibility(View.GONE);
            binding.emptyGallery.setVisibility(View.VISIBLE);
            binding.emptyGallery.setText(R.string.gallery_empty);
        } else {
            binding.recyclerGallery.setVisibility(View.VISIBLE);
            binding.emptyGallery.setVisibility(View.GONE);
        }
    }
}
