package com.cam.archcamera.gallery;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.cam.archcamera.databinding.ItemGalleryCellBinding;

import java.util.List;

public final class GalleryAdapter extends RecyclerView.Adapter<GalleryAdapter.CellHolder> {

    public interface OnItemClickListener {
        void onGalleryItemClick(GalleryItem item);
    }

    private final LayoutInflater inflater;
    private final OnItemClickListener listener;
    private List<GalleryItem> items = List.of();

    public GalleryAdapter(LayoutInflater inflater, OnItemClickListener listener) {
        this.inflater = inflater;
        this.listener = listener;
    }

    public void submit(List<GalleryItem> next) {
        items = next.isEmpty() ? List.of() : List.copyOf(next);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CellHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGalleryCellBinding binding = ItemGalleryCellBinding.inflate(inflater, parent, false);
        return new CellHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CellHolder holder, int position) {
        GalleryItem item = items.get(position);
        Glide.with(holder.binding.galleryThumb)
                .load(item.getContentUri())
                .centerCrop()
                .into(holder.binding.galleryThumb);
        holder.binding.getRoot().setOnClickListener(v -> listener.onGalleryItemClick(item));
    }

    @Override
    public void onViewRecycled(@NonNull CellHolder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.binding.galleryThumb).clear(holder.binding.galleryThumb);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class CellHolder extends RecyclerView.ViewHolder {
        final ItemGalleryCellBinding binding;

        CellHolder(ItemGalleryCellBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
