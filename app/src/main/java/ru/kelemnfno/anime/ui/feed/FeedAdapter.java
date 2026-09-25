package ru.kelemnfno.anime.ui.feed;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import ru.kelemnfno.anime.databinding.ItemFeedBinding;

/** Страницы ленты. Плеер один на всю ленту — страница только показывает его. */
public class FeedAdapter extends RecyclerView.Adapter<FeedAdapter.Holder> {

    public interface Actions {
        void onOpen(FeedClip clip);

        void onMute(Holder holder);
    }

    private final List<FeedClip> clips;
    private final Actions actions;

    public FeedAdapter(List<FeedClip> clips, Actions actions) {
        this.clips = clips;
        this.actions = actions;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemFeedBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final FeedClip clip = clips.get(position);
        holder.b.feedTitle.setText(clip.title);
        holder.b.feedMeta.setText("Серия " + clip.episode
                + (clip.voice == null || clip.voice.isEmpty() ? "" : " · " + clip.voice));
        holder.b.feedPlayer.setPlayer(null);
        holder.b.feedLoading.setVisibility(View.VISIBLE);
        holder.b.feedOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onOpen(clip);
            }
        });
        final Holder self = holder;
        holder.b.feedMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.onMute(self);
            }
        });
    }

    @Override
    public int getItemCount() {
        return clips.size();
    }

    public static class Holder extends RecyclerView.ViewHolder {

        public final ItemFeedBinding b;

        Holder(ItemFeedBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}
