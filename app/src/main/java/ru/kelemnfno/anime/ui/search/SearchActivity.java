package ru.kelemnfno.anime.ui.search;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.databinding.ActivitySearchBinding;
import ru.kelemnfno.anime.databinding.ItemSearchResultBinding;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Ui;

/** Полноэкранный поиск: живые результаты, недавние запросы, «часто ищут». */
public class SearchActivity extends AppCompatActivity {

    private static final String[] TRENDING = {"Наруто", "Ванпис", "Магическая битва", "Атака титанов",
            "Клинок, рассекающий демонов", "Тетрадь смерти", "Мой герой", "Фрирен"};
    private static final String[] SORTS = {"relevance", "rating", "year", "views", "name"};
    private static final String[] SORT_LABELS = {"Релевантность", "Рейтинг", "Новые", "Популярные", "А–Я"};

    private ActivitySearchBinding b;
    private final List<AnimeItem> results = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private String sort = "relevance";
    private Runnable pending;
    private long searchToken;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.back.setOnClickListener(v -> finish());
        b.clear.setOnClickListener(v -> b.query.setText(""));
        b.results.setLayoutManager(new LinearLayoutManager(this));
        b.results.setAdapter(adapter);

        renderStart();
        renderSorts();

        b.query.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                b.clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                schedule();
            }
        });
        b.query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                String q = text();
                if (q.length() >= 2) {
                    Prefs.get(this).addRecentSearch(q);
                    renderStart();
                }
                Ui.hideKeyboard(this);
                return true;
            }
            return false;
        });
    }

    private String text() {
        return b.query.getText() == null ? "" : b.query.getText().toString().trim();
    }

    /** Дебаунс 320 мс — как на сайте. */
    private void schedule() {
        if (pending != null) b.getRoot().removeCallbacks(pending);
        pending = this::search;
        b.getRoot().postDelayed(pending, 320);
    }

    private void search() {
        String q = text();
        boolean showStart = q.length() < 2;
        b.startState.setVisibility(showStart ? View.VISIBLE : View.GONE);
        b.results.setVisibility(showStart ? View.GONE : View.VISIBLE);
        b.sortWrap.setVisibility(showStart ? View.GONE : View.VISIBLE);
        if (showStart) {
            results.clear();
            adapter.notifyDataSetChanged();
            return;
        }
        final long token = ++searchToken;
        AppExecutors.get().run(() -> AnimeRepository.get(this).search(q, 70), (value, error) -> {
            if (token != searchToken) return;
            results.clear();
            if (value != null) results.addAll(value);
            applySort();
            final long token2 = token;
            AppExecutors.get().run(() -> anilibriaSearch(q), (extra, err) -> {
                if (token2 != searchToken || extra == null || extra.isEmpty()) return;
                for (ru.kelemnfno.anime.data.model.AnimeItem ex : extra) {
                    boolean dup = false;
                    for (ru.kelemnfno.anime.data.model.AnimeItem r : results)
                        if (String.valueOf(r.title).equalsIgnoreCase(String.valueOf(ex.title))) { dup = true; break; }
                    if (!dup) results.add(ex);
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private static String sGet(String url) throws java.io.IOException {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", ru.kelemnfno.anime.data.resolver.Net.CHROME)
                .build();
        try (okhttp3.Response res = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true).followSslRedirects(true).build().newCall(req).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new java.io.IOException("HTTP " + res.code());
            return res.body().string();
        }
    }

    /** Результаты AniLibria API v1, привязанные к карточкам Shikimori. */
    private java.util.List<ru.kelemnfno.anime.data.model.AnimeItem> anilibriaSearch(String q) {
        java.util.List<ru.kelemnfno.anime.data.model.AnimeItem> out = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonElement se = com.google.gson.JsonParser.parseString(sGet(
                    "https://anilibria.top/api/v1/app/search/releases?query="
                            + java.net.URLEncoder.encode(q, "UTF-8") + "&limit=8"));
            com.google.gson.JsonArray arr = se.isJsonArray() ? se.getAsJsonArray()
                    : (se.isJsonObject() && se.getAsJsonObject().has("data")
                    ? se.getAsJsonObject().getAsJsonArray("data") : null);
            if (arr == null) return out;
            for (com.google.gson.JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject o = e.getAsJsonObject();
                String en = "";
                if (o.has("name") && o.get("name").isJsonObject()) {
                    com.google.gson.JsonObject n = o.getAsJsonObject("name");
                    en = n.has("english") && n.get("english").isJsonPrimitive() ? n.get("english").getAsString()
                            : (n.has("main") ? n.get("main").getAsString() : "");
                }
                if (en.isEmpty()) continue;
                try {
                    com.google.gson.JsonElement sr = com.google.gson.JsonParser.parseString(sGet(
                            "https://shikimori.io/api/animes?search=" + java.net.URLEncoder.encode(en, "UTF-8") + "&limit=1"));
                    if (!sr.isJsonArray() || sr.getAsJsonArray().size() == 0) continue;
                    com.google.gson.JsonObject a = sr.getAsJsonArray().get(0).getAsJsonObject();
                    ru.kelemnfno.anime.data.model.AnimeItem it = new ru.kelemnfno.anime.data.model.AnimeItem();
                    int id = a.has("id") ? a.get("id").getAsInt() : 0;
                    if (id <= 0) continue;
                    it.animeUrl = "shiki:" + id;
                    it.animeId = id;
                    String ru = a.has("russian") && a.get("russian").isJsonPrimitive() ? a.get("russian").getAsString() : "";
                    it.title = ru.isEmpty() ? en : ru;
                    it.original = en;
                    it.year = 0;
                    String iso = a.has("aired_on") && a.get("aired_on").isJsonPrimitive()
                            ? a.get("aired_on").getAsString() : "";
                    if (iso.length() >= 4) try { it.year = Integer.parseInt(iso.substring(0, 4)); } catch (Exception ignored) { }
                    it.poster = new ru.kelemnfno.anime.data.model.Poster();
                    String img = "";
                    if (a.has("image") && a.get("image").isJsonObject()) {
                        com.google.gson.JsonObject im = a.getAsJsonObject("image");
                        img = im.has("original") && im.get("original").isJsonPrimitive()
                                ? im.get("original").getAsString() : "";
                        if (!img.isEmpty() && !img.startsWith("http")) img = "https://shikimori.io" + img;
                    }
                    it.poster.big = img;
                    it.poster.huge = img;
                    it.poster.fullsize = img;
                    if (a.has("score") && a.get("score").isJsonPrimitive() && a.get("score").getAsDouble() > 0) {
                        it.rating = new ru.kelemnfno.anime.data.model.Rating();
                        it.rating.average = a.get("score").getAsDouble();
                    }
                    out.add(it);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void applySort() {
        List<AnimeItem> rows = new ArrayList<>(results);
        String q = norm(text());
        switch (sort) {
            case "rating":
                rows.sort((x, y) -> Double.compare(rating(y), rating(x)));
                break;
            case "year":
                rows.sort((x, y) -> Integer.compare(y.year, x.year));
                break;
            case "views":
                rows.sort((x, y) -> Integer.compare(y.views, x.views));
                break;
            case "name":
                rows.sort((x, y) -> String.valueOf(x.title).compareToIgnoreCase(String.valueOf(y.title)));
                break;
            default:
                rows.sort((x, y) -> Integer.compare(rank(x, q), rank(y, q)));
                break;
        }
        results.clear();
        results.addAll(rows);
        adapter.notifyDataSetChanged();
    }

    private int rank(AnimeItem a, String q) {
        String t = norm(a.title);
        if (t.equals(q)) return 0;
        if (t.startsWith(q)) return 1;
        if (t.contains(q)) return 2;
        return 3;
    }

    private double rating(AnimeItem a) {
        return a.rating == null ? 0 : a.rating.average;
    }

    private String norm(String v) {
        return v == null ? "" : v.toLowerCase().replace("ё", "е").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void renderSorts() {
        b.sorts.removeAllViews();
        for (int i = 0; i < SORTS.length; i++) {
            final String value = SORTS[i];
            Chips.add(b.sorts, SORT_LABELS[i], sort.equals(value), v -> {
                sort = value;
                renderSorts();
                applySort();
            });
        }
    }

    private void renderStart() {
        b.recent.removeAllViews();
        for (String r : Prefs.get(this).recentSearch()) {
            TextView row = new TextView(this);
            row.setText(r);
            row.setTextColor(getColor(R.color.text));
            row.setTextSize(14);
            row.setBackgroundResource(R.drawable.bg_ripple_rounded);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_clock, 0, 0, 0);
            row.setCompoundDrawablePadding(Ui.dp(this, 12));
            row.setOnClickListener(v -> choose(r));
            b.recent.addView(row);
        }
        if (b.recent.getChildCount() == 0) {
            TextView hint = new TextView(this);
            hint.setText("Здесь появятся ваши последние запросы");
            hint.setTextColor(getColor(R.color.text_mute));
            hint.setTextSize(12);
            b.recent.addView(hint);
        }
        b.trending.removeAllViews();
        for (String t : TRENDING) {
            Chips.add(b.trending, t, false, v -> choose(t));
        }
    }

    private void choose(String value) {
        b.query.setText(value);
        b.query.setSelection(value.length());
        Prefs.get(this).addRecentSearch(value);
        search();
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemSearchResultBinding.inflate(getLayoutInflater(), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(results.get(position));
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ItemSearchResultBinding b;

            Holder(ItemSearchResultBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(AnimeItem a) {
                Ui.poster(b.poster, ru.kelemnfno.anime.util.Fmt.posterUrl(a, "small"), 8);
                b.title.setText(a.title);
                List<String> parts = new ArrayList<>();
                if (a.year > 0) parts.add(String.valueOf(a.year));
                if (a.type != null && a.type.shortname != null) parts.add(a.type.shortname);
                if (a.rating != null && a.rating.average > 0) {
                    parts.add("★ " + String.format(Locale.US, "%.1f", a.rating.average));
                }
                b.subtitle.setText(ru.kelemnfno.anime.util.Fmt.join(parts, " · "));
                b.ongoing.setVisibility(a.animeStatus != null && a.animeStatus.isOngoing()
                        ? View.VISIBLE : View.GONE);
                b.getRoot().setOnClickListener(v -> {
                    Prefs.get(SearchActivity.this).addRecentSearch(text());
                    if (a.animeUrl != null && a.animeUrl.startsWith("shiki:")) {
                        int sid = 0;
                        try { sid = Integer.parseInt(a.animeUrl.substring(6)); } catch (Exception ignored) { }
                        DetailActivity.openShiki(SearchActivity.this, sid, a.title,
                                a.original == null ? "" : a.original, a.year,
                                ru.kelemnfno.anime.util.Fmt.posterUrl(a, "big"));
                        return;
                    }
                    DetailActivity.open(SearchActivity.this, a.animeUrl);
                    finish();
                });
            }
        }
    }

    /** Экспорт «часто ищут» — используется и в настройках. */
    public static List<String> trending() {
        return Collections.unmodifiableList(Arrays.asList(TRENDING));
    }
}
