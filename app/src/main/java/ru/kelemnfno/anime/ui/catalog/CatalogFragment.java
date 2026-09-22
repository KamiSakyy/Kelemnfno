package ru.kelemnfno.anime.ui.catalog;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.flexbox.FlexboxLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.Genre;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.databinding.FragmentCatalogBinding;
import ru.kelemnfno.anime.databinding.SheetFiltersBinding;
import ru.kelemnfno.anime.databinding.ViewEmptyBinding;
import ru.kelemnfno.anime.ui.AnimeCardAdapter;
import ru.kelemnfno.anime.ui.CardModel;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Каталог: фильтры (статус/год/тип/жанры), сортировки, бесконечная лента. */
public class CatalogFragment extends Fragment {

    private static final int PAGE = 35;
    private static final int MAX_EMPTY_PAGES = 12;

    private static final String[] TYPE_LABELS = {"ТВ сериал", "Фильм", "OVA", "ONA", "Спешл"};
    private static final int[] TYPE_VALUES = {1, 2, 3, 7, 5};
    private static final String[] STATUSES = {"", "ongoing", "released", "announcement"};
    private static final String[] STATUS_LABELS = {"Любой", "Онгоинг", "Вышел", "Анонс"};
    private static final String[] SORTS = {"top", "views", "rating"};
    private static final String[] SORT_LABELS = {"Популярное", "По просмотрам", "По рейтингу"};
    private static final int[][] YEAR_PRESETS = {
            {2026, 2100}, {2025, 2025}, {2024, 2024}, {2023, 2023}, {2022, 2022},
            {2020, 2021}, {2015, 2019}, {2010, 2014}, {2000, 2009}, {1900, 1999}};
    private static final String[] YEAR_LABELS = {"2026", "2025", "2024", "2023", "2022", "2020–2021",
            "2015–2019", "2010–2014", "2000-е", "До 2000"};
    private static final List<String> ADULT = Arrays.asList("etti", "erotica", "garem", "garem-dlya-devochek",
            "sukkuby", "lolikon", "trap", "sedze-aj", "snenen-aj");


    private FragmentCatalogBinding b;
    private AnimeCardAdapter adapter;
    private final List<AnimeItem> items = new ArrayList<>();
    private List<Genre> genres = new ArrayList<>();

    private String sort = "top";
    private String status = "";
    private int yearFrom;
    private int yearTo;
    private final List<Integer> genreIds = new ArrayList<>();
    private final List<Integer> typeIds = new ArrayList<>();

    private int offset;
    private boolean hasMore = true;
    private boolean loading;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCatalogBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new AnimeCardAdapter(0);
        adapter.setListener(new AnimeCardAdapter.OnCardClick() {
            @Override
            public void onClick(CardModel model) {
                DetailActivity.openWith(requireContext(), model.slug, model.title, model.poster);
            }

            @Override
            public void onLongClick(CardModel model) {
            }
        });
        b.grid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        b.grid.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(requireContext(), 3));
        b.grid.setAdapter(adapter);
        Ui.tuneList(b.grid, true);
        b.grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int last = lm.findLastVisibleItemPosition();
                if (last >= items.size() - 12 && hasMore && !loading) loadMore();
            }
        });

        ViewEmptyBinding empty = b.empty;
        empty.emptyIcon.setImageResource(R.drawable.ic_search);
        empty.emptyText.setText("Ничего не найдено. Попробуйте ослабить фильтры.");

        renderQuickFilters();
        loadGenres();
        loadMore();
    }

    private void loadGenres() {
        AppExecutors.get().run(() -> AnimeRepository.get(requireContext()).genres().genres, (value, error) -> {
            if (value != null) genres = value;
        });
    }

    /** Вызывается с главной при тапе «Все» у ряда. */
    public void applyQuery(String query) {
        if (query == null || query.isEmpty()) {
            reset();
            return;
        }
        resetState();
        for (String part : query.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) continue;
            String key = part.substring(0, i);
            String value;
            try {
                value = java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8");
            } catch (Exception e) {
                value = part.substring(i + 1);
            }
            switch (key) {
                case "sort":
                    sort = value;
                    break;
                case "status":
                    status = value;
                    break;
                case "genres":
                    try {
                        genreIds.add(Integer.parseInt(value));
                    } catch (Exception ignored) {
                    }
                    break;
                case "types":
                    try {
                        typeIds.add(Integer.parseInt(value));
                    } catch (Exception ignored) {
                    }
                    break;
                case "ys":
                    yearFrom = parseInt(value);
                    break;
                case "ye":
                    yearTo = parseInt(value);
                    break;
                default:
                    break;
            }
        }
        renderQuickFilters();
        loadMore();
    }

    private int parseInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private void reset() {
        resetState();
        renderQuickFilters();
        loadMore();
    }

    private void resetState() {
        sort = "top";
        status = "";
        yearFrom = 0;
        yearTo = 0;
        genreIds.clear();
        typeIds.clear();
        items.clear();
        offset = 0;
        hasMore = true;
        loading = false;
    }

    private void renderQuickFilters() {
        if (b == null) return;
        b.quickFilters.removeAllViews();
        // Отступы ряда даёт HorizontalScrollView в разметке — внутренний контейнер
        // не добавляет своих, иначе первый чип уезжает в сторону от заголовка.
        b.quickFilters.setPadding(0, 0, 0, 0);
        b.quickFilters.setClipToPadding(false);
        int active = genreIds.size() + typeIds.size() + (status.isEmpty() ? 0 : 1)
                + (yearFrom != 0 || yearTo != 0 ? 1 : 0);

        Chips.add(b.quickFilters, active > 0 ? "Фильтры · " + active : "Фильтры",
                active > 0, v -> openFilters());

        for (int i = 0; i < SORTS.length; i++) {
            final String value = SORTS[i];
            Chips.add(b.quickFilters, SORT_LABELS[i], sort.equals(value), v -> {
                sort = value;
                restart();
            });
        }
        renderActiveFilters();
    }

    private void renderActiveFilters() {
        b.activeFilters.removeAllViews();
        int active = genreIds.size() + typeIds.size() + (status.isEmpty() ? 0 : 1)
                + (yearFrom != 0 || yearTo != 0 ? 1 : 0);
        b.activeWrap.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
        if (active == 0) return;

        if (!status.isEmpty()) {
            b.activeFilters.addView(activeChip(statusLabel(status), () -> {
                status = "";
                restart();
            }));
        }
        if (yearFrom != 0 || yearTo != 0) {
            b.activeFilters.addView(activeChip(yearLabel(), () -> {
                yearFrom = 0;
                yearTo = 0;
                restart();
            }));
        }
        for (int t : new ArrayList<>(typeIds)) {
            b.activeFilters.addView(activeChip(typeLabel(t), () -> {
                typeIds.remove(Integer.valueOf(t));
                restart();
            }));
        }
        for (int g : new ArrayList<>(genreIds)) {
            b.activeFilters.addView(activeChip(genreTitle(g), () -> {
                genreIds.remove(Integer.valueOf(g));
                restart();
            }));
        }
        TextView reset = Chips.chip(requireContext(), "Сбросить всё", false, v -> reset());
        b.activeFilters.addView(reset);
    }

    /** Чип активного фильтра. Важно: НЕ добавляем его в контейнер здесь —
     *  это делает вызывающий код, иначе view получает второго родителя и падает. */
    private TextView activeChip(String text, Runnable onRemove) {
        TextView chip = Chips.chip(requireContext(), text + "  ✕", true, v -> onRemove.run());
        android.view.ViewGroup.MarginLayoutParams lp = new android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = ru.kelemnfno.anime.util.Ui.dp(requireContext(), 8);
        lp.setMargins(0, 0, gap, gap);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void restart() {
        items.clear();
        offset = 0;
        hasMore = true;
        adapter.submit(new ArrayList<>());
        renderQuickFilters();
        loadMore();
    }

    /**
     * Первая страница показывается из кэша сразу — экран не пустой,
     * пока идёт запрос. На мобильном интернете свежий кэш не обновляем:
     * экономим трафик.
     */
    private boolean renderFromCache() {
        if (offset != 0 || adapter.getItemCount() > 0) return false;
        AnimeRepository repo = AnimeRepository.get(requireContext());
        Map<String, String> p = pageParams(0);
        List<AnimeItem> cached = repo.listCached(p);
        if (cached == null || cached.isEmpty()) return false;
        List<AnimeItem> shown = new ArrayList<>();
        for (AnimeItem a : cached) if (yearOk(a)) shown.add(a);
        if (shown.isEmpty()) return false;
        // Показываем сохранённое и НЕ трогаем offset: сеть обновит первую
        // страницу целиком, повторы отбросит фильтр по animeId.
        items.addAll(shown);
        adapter.addAll(models(shown));
        b.empty.getRoot().setVisibility(View.GONE);
        return false;
    }

    private Map<String, String> pageParams(int cursor) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sort", sort);
        if (!status.isEmpty()) p.put("status", status);
        p.put("limit", String.valueOf(PAGE));
        p.put("offset", String.valueOf(cursor));
        for (int g : genreIds) p.put("genres", p.containsKey("genres") ? p.get("genres") + "," + g : String.valueOf(g));
        for (int t : typeIds) p.put("types", p.containsKey("types") ? p.get("types") + "," + t : String.valueOf(t));
        return p;
    }

    private void loadMore() {
        if (loading || !hasMore) return;
        if (renderFromCache()) return;
        loading = true;
        final int from = offset;
        AppExecutors.get().run(() -> {
            List<AnimeItem> collected = new ArrayList<>();
            int cursor = from;
            int added = 0;
            int attempts = 0;
            boolean more = true;
            while (attempts < MAX_EMPTY_PAGES) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("sort", sort);
                if (!status.isEmpty()) p.put("status", status);
                p.put("limit", String.valueOf(PAGE));
                p.put("offset", String.valueOf(cursor));
                for (int g : genreIds) p.put("genres", p.containsKey("genres") ? p.get("genres") + "," + g : String.valueOf(g));
                for (int t : typeIds) p.put("types", p.containsKey("types") ? p.get("types") + "," + t : String.valueOf(t));
                List<AnimeItem> page = AnimeRepository.get(requireContext()).list(p);
                cursor += page.size();
                for (AnimeItem a : page) {
                    if (!yearOk(a)) continue;
                    // топы/просмотры/рейтинг не должны показывать невышедшие анонсы
                    if (status.isEmpty() && a.animeStatus != null && a.animeStatus.isAnnouncement()) continue;
                    collected.add(a);
                }
                added += collected.size();
                if (page.size() < PAGE) {
                    more = false;
                    break;
                }
                if (added > 0) break;
                attempts++;
            }
            List<Object> result = new ArrayList<>();
            result.add(collected);
            result.add(cursor);
            result.add(more);
            return result;
        }, (value, error) -> {
            loading = false;
            if (b == null) return;
            if (error != null) {
                Ui.toast(requireContext(), "Ошибка: " + error.getMessage());
                return;
            }
            applyPage(value);
        });
    }

    /** Добавляет страницу в список, отбрасывая уже показанные тайтлы. */
    private void applyPage(List<Object> value) {
        @SuppressWarnings("unchecked")
        List<AnimeItem> collected = (List<AnimeItem>) value.get(0);
        offset = (Integer) value.get(1);
        hasMore = (Boolean) value.get(2);
        List<Integer> seen = new ArrayList<>();
        for (AnimeItem a : items) seen.add(a.animeId);
        List<AnimeItem> fresh = new ArrayList<>();
        for (AnimeItem a : collected) if (!seen.contains(a.animeId)) fresh.add(a);
        items.addAll(fresh);
        adapter.addAll(models(fresh));
        b.empty.getRoot().setVisibility(items.isEmpty() && !hasMore ? View.VISIBLE : View.GONE);
    }

    private boolean yearOk(AnimeItem a) {
        if (yearFrom == 0 && yearTo == 0) return true;
        if (a.year <= 0) return false;
        return a.year >= yearFrom && a.year <= (yearTo == 0 ? 2200 : yearTo);
    }

    private List<CardModel> models(List<AnimeItem> rows) {
        List<CardModel> out = new ArrayList<>();
        for (AnimeItem a : rows) {
            CardModel m = new CardModel(a.animeUrl, a.title, Fmt.posterUrl(a, "big"));
            m.animeId = a.animeId;
            m.rating = a.rating == null ? 0 : a.rating.average;
            m.subtitle = Fmt.join(Arrays.asList(a.year > 0 ? String.valueOf(a.year) : "",
                    a.type == null ? "" : a.type.shortname), " · ");
            m.ongoing = a.animeStatus != null && a.animeStatus.isOngoing();
            out.add(m);
        }
        return out;
    }

    /* ---------------- Bottom sheet с фильтрами ---------------- */

    /** Лист фильтров как на сайте: тёмный, 88% высоты, раскрыт сразу, контент скроллится. */
    private void expandSheet(BottomSheetDialog dialog) {
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.88f);
        android.view.ViewGroup.LayoutParams lp = sheet.getLayoutParams();
        lp.height = max;
        sheet.setLayoutParams(lp);
        com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
    }

    private void openFilters() {
        BottomSheetDialog dialog = new BottomSheetDialog(requireContext(), R.style.Theme_Kelemnfno_BottomSheet);
        SheetFiltersBinding s = SheetFiltersBinding.inflate(getLayoutInflater());
        dialog.setContentView(s.getRoot());
        expandSheet(dialog);

        s.statusGroup.removeAllViews();
        for (int i = 0; i < STATUSES.length; i++) {
            final String value = STATUSES[i];
            Chips.add(s.statusGroup, STATUS_LABELS[i], status.equals(value), v -> {
                status = value;
                openFilters();
                dialog.dismiss();
                restart();
            });
        }

        for (int i = 0; i < YEAR_PRESETS.length; i++) {
            final int from = YEAR_PRESETS[i][0];
            final int to = YEAR_PRESETS[i][1];
            final String label = YEAR_LABELS[i];
            Chips.add(s.yearGroup, label, yearFrom == from && yearTo == to, v -> {
                if (yearFrom == from && yearTo == to) {
                    yearFrom = 0;
                    yearTo = 0;
                } else {
                    yearFrom = from;
                    yearTo = to;
                }
                dialog.dismiss();
                restart();
            });
        }

        for (int i = 0; i < TYPE_VALUES.length; i++) {
            final int value = TYPE_VALUES[i];
            Chips.add(s.typeGroup, TYPE_LABELS[i], typeIds.contains(value), v -> {
                if (typeIds.contains(value)) typeIds.remove(Integer.valueOf(value));
                else typeIds.add(value);
                dialog.dismiss();
                restart();
            });
        }

        Runnable renderGenres = () -> {
            s.genreGroup.removeAllViews();
            s.adultGroup.removeAllViews();
            // отдельный настоящий жанр «хентай» — каталог из AniLibria, вход через 18+
            Chips.add(s.adultGroup, "Хентай", false, v -> openHentaiGate());
            String q = s.genreQuery.getText() == null ? "" : s.genreQuery.getText().toString().trim().toLowerCase();
            boolean adultAllowed = Prefs.get(requireContext()).settings().showAdult;
            for (Genre g : genres) {
                boolean adult = ADULT.contains(g.href);
                // Настройка «Показывать жанры 18+» раньше ни на что не влияла.
                if (adult && !adultAllowed) continue;
                if (!q.isEmpty() && !matches(g, q)) continue;
                FlexboxLayout target = adult ? s.adultGroup : s.genreGroup;
                Chips.add(target, g.title, genreIds.contains(g.value), v -> {
                    if (genreIds.contains(g.value)) genreIds.remove(Integer.valueOf(g.value));
                    else genreIds.add(g.value);
                    dialog.dismiss();
                    restart();
                });
            }
            s.adultLabel.setVisibility(s.adultGroup.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        };
        renderGenres.run();
        s.genreQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence c, int a, int b2, int d) {
            }

            @Override
            public void onTextChanged(CharSequence c, int a, int b2, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                renderGenres.run();
            }
        });

        s.reset.setOnClickListener(v -> {
            dialog.dismiss();
            reset();
        });
        s.apply.setOnClickListener(v -> {
            dialog.dismiss();
            restart();
        });
        dialog.show();
    }

    /** Диалог возраста: «Тебе есть 18 лет?» Да — открыть каталог, Нет — закрыть. */
    private void openHentaiGate() {
        ru.kelemnfno.anime.data.prefs.Prefs prefs = ru.kelemnfno.anime.data.prefs.Prefs.get(requireContext());
        if (prefs.settings().adultConfirmed) {
            HentaiActivity.start(requireContext());
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Тебе есть 18 лет?")
                .setMessage("Раздел «Хентай» доступен только взрослым.")
                .setPositiveButton("Да", (d, w) -> {
                    ru.kelemnfno.anime.data.model.AppSettings st = prefs.settings();
                    st.adultConfirmed = true;
                    prefs.saveSettings(st);
                    HentaiActivity.start(requireContext());
                })
                .setNegativeButton("Нет", null)
                .show();
    }

    private boolean matches(Genre g, String q) {
        if (g.title != null && g.title.toLowerCase().contains(q)) return true;
        if (g.moreTitles != null) {
            for (String t : g.moreTitles) if (t != null && t.toLowerCase().contains(q)) return true;
        }
        return g.href != null && g.href.contains(q);
    }

    private String statusLabel(String value) {
        for (int i = 0; i < STATUSES.length; i++) if (STATUSES[i].equals(value)) return STATUS_LABELS[i];
        return value;
    }

    private String typeLabel(int value) {
        for (int i = 0; i < TYPE_VALUES.length; i++) if (TYPE_VALUES[i] == value) return TYPE_LABELS[i];
        return String.valueOf(value);
    }

    private String yearLabel() {
        for (int i = 0; i < YEAR_PRESETS.length; i++) {
            if (YEAR_PRESETS[i][0] == yearFrom && YEAR_PRESETS[i][1] == yearTo) return YEAR_LABELS[i];
        }
        return yearFrom + "–" + yearTo;
    }

    private String genreTitle(int id) {
        for (Genre g : genres) if (g.value == id) return g.title;
        return "#" + id;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
