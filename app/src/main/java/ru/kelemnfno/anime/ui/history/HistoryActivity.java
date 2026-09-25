package ru.kelemnfno.anime.ui.history;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.HistoryEntity;
import ru.kelemnfno.anime.databinding.ActivityHistoryBinding;
import ru.kelemnfno.anime.databinding.ItemHistoryBinding;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** История просмотра с позицией для продолжения. */
public class HistoryActivity extends AppCompatActivity {

    private ActivityHistoryBinding b;
    private final List<HistoryEntity> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityHistoryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.back.setOnClickListener(v -> finish());
        b.clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Очистить историю?")
                .setMessage("Прогресс просмотра будет удалён.")
                .setPositiveButton(R.string.delete, (d, w) -> AppExecutors.get().io().execute(
                        () -> AppDatabase.get(HistoryActivity.this).historyDao().clear()))
                .setNegativeButton(R.string.cancel, null)
                .show());
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        b.empty.emptyIcon.setImageResource(R.drawable.ic_history);
        b.empty.emptyText.setText(R.string.empty_history);

        AppDatabase.get(this).historyDao().observeAll().observe(this, list -> {
            rows.clear();
            if (list != null) rows.addAll(list);
            adapter.notifyDataSetChanged();
            boolean empty = rows.isEmpty();
            b.empty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
            b.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemHistoryBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ItemHistoryBinding b;

            Holder(ItemHistoryBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(HistoryEntity h) {
                Ui.poster(b.poster, h.poster, 10);
                b.title.setText(h.title);
                b.subtitle.setText("Серия " + h.episode + (h.total > 0 ? " из " + h.total : "") + " · " + h.dubbing);
                float progress = h.durationMs > 0 ? (float) h.positionMs / h.durationMs : 0;
                b.progressBlock.setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
                if (progress > 0) {
                    b.progressText.setText(Math.round(progress * 100) + "% · " + Fmt.clock(h.positionMs));
                    b.progress.post(() -> {
                        int w = b.progressBlock.getWidth();
                        b.progress.getLayoutParams().width = Math.max(2, Math.round(w * 0.72f * progress));
                        b.progress.requestLayout();
                    });
                }
                b.actionPrimary.setText("Продолжить");
                b.actionPrimary.setOnClickListener(v ->
                        DetailActivity.open(HistoryActivity.this, h.slug, h.episode, h.dubbing));
                b.actionSecondary.setText("Открыть");
                b.actionSecondary.setOnClickListener(v -> DetailActivity.openWith(HistoryActivity.this, h.slug, h.title, h.poster));
                b.actionDelete.setOnClickListener(v -> AppExecutors.get().io().execute(
                        () -> AppDatabase.get(HistoryActivity.this).historyDao().deleteBySlug(h.slug)));
            }
        }
    }
}
