package ru.kelemnfno.anime.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.databinding.ItemCardBinding;
import ru.kelemnfno.anime.util.Ui;

/** Универсальный адаптер карточек: и ряд, и сетка. */
public class AnimeCardAdapter extends RecyclerView.Adapter<AnimeCardAdapter.Holder> {

    public interface OnCardClick {
        void onClick(CardModel model);

        void onLongClick(CardModel model);
    }

    private final List<CardModel> items = new ArrayList<>();
    private OnCardClick listener;
    /** Ширина карточки в ряду (0 — на всю ширину ячейки сетки). */
    private final int rowWidthDp;

    public AnimeCardAdapter(int rowWidthDp) {
        this.rowWidthDp = rowWidthDp;
    }

    public void setListener(OnCardClick listener) {
        this.listener = listener;
    }

    public void submit(List<CardModel> models) {
        items.clear();
        if (models != null) items.addAll(models);
        notifyDataSetChanged();
    }

    public void addAll(List<CardModel> models) {
        if (models == null || models.isEmpty()) return;
        int start = items.size();
        items.addAll(models);
        notifyItemRangeInserted(start, models.size());
    }

    public List<CardModel> current() {
        return items;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemCardBinding binding = ItemCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        ViewGroup.LayoutParams lp = binding.getRoot().getLayoutParams();
        if (rowWidthDp > 0) {
            lp.width = Ui.dp(parent.getContext(), rowWidthDp);
        }
        binding.getRoot().setLayoutParams(lp);
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class Holder extends RecyclerView.ViewHolder {
        private final ItemCardBinding b;

        Holder(ItemCardBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }

        void bind(CardModel m) {
            Ui.poster(b.poster, m.poster, 12);
            b.title.setText(m.title);
            b.subtitle.setText(m.subtitle == null ? "" : m.subtitle);
            b.subtitle.setVisibility(m.subtitle == null || m.subtitle.isEmpty() ? View.GONE : View.VISIBLE);

            if (m.rating > 0) {
                b.rating.setVisibility(View.VISIBLE);
                b.rating.setText(String.format(java.util.Locale.US, "%.1f", m.rating));
            } else {
                b.rating.setVisibility(View.GONE);
            }
            if (m.badge != null && !m.badge.isEmpty()) {
                b.badge.setVisibility(View.VISIBLE);
                b.badge.setText(m.badge);
                b.ongoing.setVisibility(View.GONE);
            } else {
                b.badge.setVisibility(View.GONE);
                b.ongoing.setVisibility(m.ongoing ? View.VISIBLE : View.GONE);
            }
            b.downloaded.setVisibility(m.downloaded ? View.VISIBLE : View.GONE);
            if (m.progress > 0) {
                b.progressWrap.setVisibility(View.VISIBLE);
                b.progress.post(() -> {
                    int w = b.progressWrap.getWidth();
                    b.progress.getLayoutParams().width = Math.max(2, Math.round(w * Math.min(1f, m.progress)));
                    b.progress.requestLayout();
                });
            } else {
                b.progressWrap.setVisibility(View.GONE);
            }
            b.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onClick(m);
            });
            b.getRoot().setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onLongClick(m);
                    return true;
                }
                return false;
            });
            b.getRoot().setContentDescription(m.title);
            b.getRoot().setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                }
            });
        }
    }

    /** Карточка-заглушка для состояния загрузки. */
    public static View skeleton(ViewGroup parent) {
        return LayoutInflater.from(parent.getContext()).inflate(R.layout.item_card_skeleton, parent, false);
    }
}
