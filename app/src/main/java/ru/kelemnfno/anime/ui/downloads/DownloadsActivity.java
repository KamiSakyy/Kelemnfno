package ru.kelemnfno.anime.ui.downloads;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.databinding.ActivityDownloadsBinding;
import ru.kelemnfno.anime.databinding.ItemDownloadBinding;
import ru.kelemnfno.anime.download.DownloadBus;
import ru.kelemnfno.anime.download.DownloadService;
import ru.kelemnfno.anime.download.DownloadStore;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Загрузки: очередь, прогресс, пауза/продолжение, открытие и экспорт файла. */
public class DownloadsActivity extends AppCompatActivity {

    private ActivityDownloadsBinding b;
    private final List<DownloadEntity> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityDownloadsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.back.setOnClickListener(v -> finish());
        b.clear.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Удалить завершённые?")
                .setMessage("Файлы останутся в папке приложения, но список очистится.")
                .setPositiveButton(R.string.delete, (d, w) -> {
                    AppExecutors.get().io().execute(() ->
                            AppDatabase.get(DownloadsActivity.this).downloadDao().clearFinished());
                    Ui.toast(this, "Список очищен");
                })
                .setNegativeButton(R.string.cancel, null)
                .show());
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(adapter);
        b.empty.emptyIcon.setImageResource(R.drawable.ic_download);
        b.empty.emptyText.setText(R.string.empty_downloads);
        b.storage.setText("Свободно: " + Fmt.formatBytes(DownloadStore.freeBytes(this))
                + " · папка Kelemnfno/Movies");

        AppDatabase.get(this).downloadDao().observeAll().observe(this, list -> {
            rows.clear();
            if (list != null) rows.addAll(list);
            adapter.notifyDataSetChanged();
            boolean empty = rows.isEmpty();
            b.empty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
            b.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        DownloadBus.events().observe(this, event -> adapter.notifyDataSetChanged());
    }

    /** Просмотр скачанного файла во встроенном плеере. */
    private void watch(DownloadEntity d) {
        if (d.path == null || d.path.isEmpty() || !new File(d.path).exists()) {
            Ui.toast(this, "Файл не найден");
            return;
        }
        ru.kelemnfno.anime.ui.player.PlayerActivity.startFile(this,
                d.title + " · серия " + d.episode, d.path);
    }

    private void open(DownloadEntity d) {
        if (d.path == null || d.path.isEmpty()) return;
        File file = new File(d.path);
        if (!file.exists()) {
            Ui.toast(this, "Файл не найден");
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, file.getName().endsWith(".mp4") ? "video/mp4" : "video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Открыть в плеере"));
        } catch (Exception e) {
            Ui.toast(this, "Нет приложения для просмотра");
        }
    }

    private void share(DownloadEntity d) {
        if (d.path == null) return;
        File file = new File(d.path);
        if (!file.exists()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Поделиться"));
    }

    private void export(DownloadEntity d) {
        if (d.path == null) return;
        Ui.toast(this, "Копируем в «Загрузки»…");
        new Thread(() -> {
            Uri uri = DownloadStore.exportToDownloads(this, new File(d.path));
            Ui.post(() -> Ui.toast(this, uri == null
                    ? "Не удалось скопировать"
                    : "Сохранено в Загрузки/Kelemnfno"));
        }).start();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemDownloadBinding.inflate(getLayoutInflater(), parent, false));
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
            private final ItemDownloadBinding b;

            Holder(ItemDownloadBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(DownloadEntity d) {
                Ui.poster(b.poster, d.poster, 10);
                b.title.setText(d.title);
                b.subtitle.setText("Серия " + d.episode + (d.voice == null || d.voice.isEmpty() ? "" : " · " + d.voice)
                        + (d.quality > 0 ? " · " + d.quality + "p" : ""));

                boolean active = d.status == DownloadEntity.RUNNING || d.status == DownloadEntity.QUEUED
                        || d.status == DownloadEntity.PAUSED;
                b.progressBlock.setVisibility(active ? View.VISIBLE : View.GONE);
                if (active) {
                    b.progressText.setText(d.progress + "% · " + Fmt.formatBytes(d.sizeBytes));
                    b.progress.post(() -> {
                        int w = b.progressBlock.getWidth();
                        b.progress.getLayoutParams().width = Math.max(2, Math.round(w * 0.68f * d.progress / 100f));
                        b.progress.requestLayout();
                    });
                }

                switch (d.status) {
                    case DownloadEntity.DONE:
                        b.actionPrimary.setText(R.string.watch);
                        b.actionPrimary.setOnClickListener(v -> watch(d));
                        b.actionSecondary.setText(R.string.share);
                        b.actionSecondary.setOnClickListener(v -> share(d));
                        break;
                    case DownloadEntity.RUNNING:
                        b.actionPrimary.setText(R.string.pause);
                        b.actionPrimary.setOnClickListener(v -> DownloadService.pause(DownloadsActivity.this, d.id));
                        b.actionSecondary.setText(R.string.cancel);
                        b.actionSecondary.setOnClickListener(v -> DownloadService.cancel(DownloadsActivity.this, d.id));
                        break;
                    case DownloadEntity.PAUSED:
                        b.actionPrimary.setText(R.string.resume);
                        b.actionPrimary.setOnClickListener(v -> DownloadService.resume(DownloadsActivity.this, d.id));
                        b.actionSecondary.setText(R.string.cancel);
                        b.actionSecondary.setOnClickListener(v -> DownloadService.cancel(DownloadsActivity.this, d.id));
                        break;
                    case DownloadEntity.QUEUED:
                        b.actionPrimary.setText("В очереди");
                        b.actionPrimary.setOnClickListener(v -> {
                        });
                        b.actionSecondary.setText(R.string.cancel);
                        b.actionSecondary.setOnClickListener(v -> DownloadService.cancel(DownloadsActivity.this, d.id));
                        break;
                    default:
                        b.actionPrimary.setText(R.string.retry);
                        b.actionPrimary.setOnClickListener(v -> DownloadService.resume(DownloadsActivity.this, d.id));
                        b.actionSecondary.setText(R.string.to_downloads_folder);
                        b.actionSecondary.setVisibility(View.GONE);
                        break;
                }
                if (d.status == DownloadEntity.DONE) {
                    b.actionSecondary.setVisibility(View.VISIBLE);
                    b.actionSecondary.setOnLongClickListener(v -> {
                        export(d);
                        return true;
                    });
                }
                b.actionDelete.setOnClickListener(v -> {
                    DownloadService.cancel(DownloadsActivity.this, d.id);
                    if (d.path != null && !d.path.isEmpty()) DownloadStore.delete(new File(d.path));
                    AppExecutors.get().io().execute(() ->
                            AppDatabase.get(DownloadsActivity.this).downloadDao().deleteById(d.id));
                });
            }
        }
    }
}
