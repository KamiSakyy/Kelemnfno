package ru.kelemnfno.anime.ui.catalog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.api.AniLibriaApi;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Ui;

/**
 * Каталог жанра «хентай» из AniLibria. Открывается только после
 * подтверждения возраста. Просмотр уходит в обычные источники приложения.
 */
public class HentaiActivity extends AppCompatActivity {

    public static void start(Context context) {
        context.startActivity(new Intent(context, HentaiActivity.class));
    }

    private final List<AniLibriaApi.Title> items = new ArrayList<>();
    private Adapter adapter;
    private int page = 1;
    private boolean loading;
    private boolean hasMore = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hentai);
        findViewById(R.id.back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.list);
        GridLayoutManager lm = new GridLayoutManager(this, 3);
        list.setLayoutManager(lm);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                int last = lm.findLastVisibleItemPosition();
                if (last >= items.size() - 6) loadMore();
            }
        });
        loadMore();
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        loading = true;
        final int want = page;
        AppExecutors.get().run(() -> AniLibriaApi.hentai(want), (rows, error) -> {
            loading = false;
            if (isFinishing()) return;
            if (error != null || rows == null || rows.isEmpty()) {
                hasMore = false;
                if (items.isEmpty()) Ui.toast(this, "Каталог сейчас недоступен");
                return;
            }
            page = want + 1;
            items.addAll(rows);
            adapter.notifyDataSetChanged();
        });
    }

    /** Тайтл AniLibria -> наша карточка через поиск в основном каталоге. */
    private void open(AniLibriaApi.Title t) {
        AppExecutors.get().run(() -> {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("search", t.name);
            p.put("limit", "1");
            List<AnimeItem> found = AnimeRepository.get(this).list(p);
            return found.isEmpty() ? null : found.get(0);
        }, (item, error) -> {
            if (isFinishing()) return;
            if (item == null) {
                Ui.toast(this, "Тайтл не найден в каталоге");
                return;
            }
            DetailActivity.open(this, item.animeUrl);
        });
    }

    private class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hentai, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            AniLibriaApi.Title t = items.get(position);
            h.title.setText(t.name);
            Glide.with(h.poster).load(t.poster).placeholder(R.drawable.ph_poster).into(h.poster);
            h.itemView.setOnClickListener(v -> open(t));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView poster;
        final TextView title;

        Holder(@NonNull View v) {
            super(v);
            poster = v.findViewById(R.id.poster);
            title = v.findViewById(R.id.title);
        }
    }
}
