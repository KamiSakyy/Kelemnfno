package ru.kelemnfno.anime.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.DownloadEntity;
import ru.kelemnfno.anime.data.db.HistoryEntity;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.databinding.FragmentHomeBinding;
import ru.kelemnfno.anime.databinding.ViewErrorBinding;
import ru.kelemnfno.anime.databinding.ViewHeroBinding;
import ru.kelemnfno.anime.databinding.ViewSectionBinding;
import ru.kelemnfno.anime.ui.AnimeCardAdapter;
import ru.kelemnfno.anime.ui.CardModel;
import ru.kelemnfno.anime.ui.MainActivity;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Главная: герой-карусель онгоингов + ряды «Топ», «Фильмы», «Анонсы», «Продолжить». */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding b;
    private final List<AnimeItem> heroes = new ArrayList<>();
    private int heroIndex = 0;
    private final Runnable heroTick = new Runnable() {
        @Override
        public void run() {
            if (heroes.size() < 2 || b == null) return;
            heroIndex = (heroIndex + 1) % heroes.size();
            renderHero();
            if (getView() != null) getView().postDelayed(this, 8000);
        }
    };
    private ViewHeroBinding hero;
    private final List<View> sectionViews = new ArrayList<>();
    private final List<String> downloadedSlugs = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentHomeBinding.inflate(inflater, container, false);
        b.refresh.setColorSchemeResources(R.color.accent);
        b.refresh.setProgressBackgroundColorSchemeResource(R.color.surface);
        b.refresh.setOnRefreshListener(this::load);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        observeHistory();
        observeDownloads();
        load();
    }

    /** Секция не важна для экрана: при ошибке показываем её пустой. */
    private static List<AnimeItem> quietly(java.util.concurrent.Future<List<AnimeItem>> future) {
        try {
            return future.get();
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private void load() {
        b.refresh.setRefreshing(true);
        b.content.removeAllViews();
        sectionViews.clear();
        AppExecutors.get().run(() -> {
            final AnimeRepository repo = AnimeRepository.get(requireContext());
            final Map<String, String> p1 = new LinkedHashMap<>();
            p1.put("sort", "top");
            p1.put("limit", "20");

            final Map<String, String> p2 = new LinkedHashMap<>();
            p2.put("sort", "top");
            p2.put("status", "ongoing");
            p2.put("limit", "20");

            final Map<String, String> p3 = new LinkedHashMap<>();
            p3.put("sort", "top");
            p3.put("types", "2");
            p3.put("limit", "16");

            final Map<String, String> p4 = new LinkedHashMap<>();
            p4.put("sort", "views");
            p4.put("status", "announcement");
            p4.put("limit", "16");

            // Все четыре секции запрашиваются одновременно, а не по очереди.
            java.util.concurrent.ExecutorService pool = AppExecutors.get().heavy();
            java.util.concurrent.Future<List<AnimeItem>> f1 = pool.submit(() -> repo.list(p1));
            java.util.concurrent.Future<List<AnimeItem>> f2 = pool.submit(() -> repo.list(p2));
            java.util.concurrent.Future<List<AnimeItem>> f3 = pool.submit(() -> repo.list(p3));
            java.util.concurrent.Future<List<AnimeItem>> f4 = pool.submit(() -> repo.list(p4));

            List<AnimeItem> top = f1.get();
            List<AnimeItem> ongoing = f2.get();
            List<AnimeItem> movies = quietly(f3);
            List<AnimeItem> announce = quietly(f4);

            List<List<AnimeItem>> result = new ArrayList<>();
            result.add(top);
            result.add(ongoing);
            result.add(movies);
            result.add(announce);
            return result;
        }, (value, error) -> {
            if (b == null) return;
            b.refresh.setRefreshing(false);
            if (error != null) {
                showError(error.getMessage());
                return;
            }
            List<List<AnimeItem>> data = value;
            render(data.get(1), data.get(0), data.get(2), data.get(3));
        });
    }

    private void showError(String message) {
        b.content.removeAllViews();
        ViewErrorBinding err = ViewErrorBinding.inflate(getLayoutInflater(), b.content, false);
        err.errorText.setText("Не удалось загрузить данные: " + (message == null ? "ошибка сети" : message));
        err.errorRetry.setOnClickListener(v -> load());
        b.content.addView(err.getRoot());
        Ui.fadeUp(err.getRoot());
    }

    private void render(List<AnimeItem> ongoing, List<AnimeItem> top, List<AnimeItem> movies,
                        List<AnimeItem> announce) {
        b.content.removeAllViews();
        sectionViews.clear();
        heroes.clear();
        heroes.addAll(ongoing.size() > 5 ? ongoing.subList(0, 5) : ongoing);
        heroIndex = 0;

        hero = ViewHeroBinding.inflate(getLayoutInflater(), b.content, false);
        b.content.addView(hero.getRoot());
        renderHero();
        if (heroes.size() > 1) hero.getRoot().postDelayed(heroTick, 8000);

        if (heroes.isEmpty() && ongoing.isEmpty()) {
            // герой пуст — показываем хотя бы топ
        }

        addSection("Популярные онгоинги", null, cards(ongoing, null), "status=ongoing");
        addSection("Топ аниме", "Лучшее по оценкам зрителей", cards(top, "rank"), "sort=top");
        if (!movies.isEmpty()) {
            addSection("Полнометражные фильмы", null, cards(movies, null), "types=2");
        }
        if (!announce.isEmpty()) {
            addSection("Скоро выйдет", "Самые ожидаемые анонсы", cards(announce, "анонс"),
                    "status=announcement&sort=views");
        }
        for (View v : sectionViews) Ui.fadeUp(v);
    }

    private void renderHero() {
        if (hero == null || heroes.isEmpty()) return;
        AnimeItem h = heroes.get(heroIndex);
        Ui.image(hero.heroImage, Fmt.posterUrl(h, "fullsize"));
        StringBuilder meta = new StringBuilder("Онгоинг");
        if (h.year > 0) meta.append(" · ").append(h.year);
        if (h.type != null && h.type.name != null) meta.append(" · ").append(h.type.name);
        if (h.rating != null && h.rating.average > 0) {
            meta.append(" · ★ ").append(String.format(Locale.US, "%.1f", h.rating.average));
        }
        hero.heroMeta.setText(meta.toString());
        hero.heroTitle.setText(h.title);
        hero.heroDesc.setText(Fmt.cleanDescription(h.description));
        hero.heroWatch.setOnClickListener(v -> DetailActivity.open(requireContext(), h.animeUrl));
        hero.heroOngoings.setOnClickListener(v -> openCatalog("status=ongoing"));

        hero.heroDots.removeAllViews();
        for (int i = 0; i < heroes.size(); i++) {
            View dot = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Ui.dp(requireContext(), i == heroIndex ? 26 : 8), Ui.dp(requireContext(), 4));
            lp.setMargins(0, 0, Ui.dp(requireContext(), 6), 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(i == heroIndex ? R.drawable.bg_pill_white : R.drawable.bg_pill);
            final int index = i;
            dot.setOnClickListener(v -> {
                heroIndex = index;
                renderHero();
            });
            hero.heroDots.addView(dot);
        }
        Ui.fadeIn(hero.heroTitle);
    }

    private void addSection(String title, String subtitle, List<CardModel> models, String catalogQuery) {
        if (models.isEmpty()) return;
        ViewSectionBinding s = ViewSectionBinding.inflate(getLayoutInflater(), b.content, false);
        s.sectionTitle.setText(title);
        if (subtitle == null) {
            s.sectionSubtitle.setVisibility(View.GONE);
        } else {
            s.sectionSubtitle.setText(subtitle);
        }
        s.sectionMore.setOnClickListener(v -> openCatalog(catalogQuery));
        AnimeCardAdapter adapter = new AnimeCardAdapter(118);
        adapter.setListener(cardListener());
        adapter.submit(models);
        s.sectionList.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(requireContext(), 0));
        s.sectionList.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        s.sectionList.setAdapter(adapter);
        s.sectionList.setNestedScrollingEnabled(false);
        sectionViews.add(s.getRoot());
        b.content.addView(s.getRoot());
    }

    /** Раздел «Продолжить просмотр» строится из локальной истории. */
    private void observeHistory() {
        AppDatabase.get(requireContext()).historyDao().observeAll().observe(getViewLifecycleOwner(), rows -> {
            if (b == null) return;
            View existing = b.content.findViewWithTag("history_section");
            if (existing != null) b.content.removeView(existing);
            if (rows == null || rows.isEmpty() || hero == null) return;

            List<CardModel> models = new ArrayList<>();
            int limit = Math.min(rows.size(), 15);
            for (int i = 0; i < limit; i++) {
                HistoryEntity h = rows.get(i);
                CardModel m = new CardModel(h.slug, h.title, h.poster);
                m.animeId = h.animeId;
                m.subtitle = "Серия " + h.episode + (h.total > 0 ? " из " + h.total : "") + " · " + h.dubbing;
                m.episode = h.episode;
                m.dubbing = h.dubbing;
                if (h.total > 0) {
                    try {
                        m.progress = Float.parseFloat(h.episode) / h.total;
                    } catch (Exception ignored) {
                    }
                }
                m.downloaded = downloadedSlugs.contains(h.slug);
                models.add(m);
            }

            ViewSectionBinding s = ViewSectionBinding.inflate(getLayoutInflater(), b.content, false);
            s.getRoot().setTag("history_section");
            s.sectionTitle.setText(R.string.continue_watching);
            s.sectionSubtitle.setVisibility(View.GONE);
            s.sectionMore.setText("Вся");
            s.sectionMore.setOnClickListener(v ->
                    startActivity(new Intent(requireContext(), ru.kelemnfno.anime.ui.history.HistoryActivity.class)));
            AnimeCardAdapter adapter = new AnimeCardAdapter(118);
            adapter.setListener(cardListener());
            adapter.submit(models);
            s.sectionList.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(requireContext(), 0));
        s.sectionList.setLayoutManager(
                    new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            s.sectionList.setAdapter(adapter);
            // вставляем сразу после героя
            int index = hero == null ? 0 : b.content.indexOfChild(hero.getRoot()) + 1;
            b.content.addView(s.getRoot(), Math.max(0, index));
            Ui.fadeUp(s.getRoot());
        });
    }

    private void observeDownloads() {
        AppDatabase.get(requireContext()).downloadDao().observeAll().observe(getViewLifecycleOwner(), rows -> {
            downloadedSlugs.clear();
            if (rows == null) return;
            for (DownloadEntity d : rows) {
                if (d.status == DownloadEntity.DONE) downloadedSlugs.add(d.slug);
            }
        });
    }

    private List<CardModel> cards(List<AnimeItem> items, String badgeMode) {
        List<CardModel> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            AnimeItem a = items.get(i);
            CardModel m = new CardModel(a.animeUrl, a.title, Fmt.posterUrl(a, "big"));
            m.animeId = a.animeId;
            m.rating = a.rating == null ? 0 : a.rating.average;
            String year = a.year > 0 ? String.valueOf(a.year) : "";
            String type = a.type == null || a.type.shortname == null ? "" : a.type.shortname;
            m.subtitle = Fmt.join(java.util.Arrays.asList(year, type), " · ");
            m.ongoing = a.animeStatus != null && a.animeStatus.isOngoing();
            if ("rank".equals(badgeMode) && i < 3) m.badge = "#" + (i + 1);
            else if (badgeMode != null && !"rank".equals(badgeMode)) m.badge = badgeMode;
            m.downloaded = downloadedSlugs.contains(a.animeUrl);
            out.add(m);
        }
        return out;
    }

    private AnimeCardAdapter.OnCardClick cardListener() {
        return new AnimeCardAdapter.OnCardClick() {
            @Override
            public void onClick(CardModel model) {
                DetailActivity.open(requireContext(), model.slug, model.episode, model.dubbing);
            }

            @Override
            public void onLongClick(CardModel model) {
                Ui.toast(requireContext(), model.title);
            }
        };
    }

    private void openCatalog(String query) {
        MainActivity activity = (MainActivity) requireActivity();
        activity.select(MainActivity.TAB_CATALOG);
        Fragment f = getSupportFragmentManagerInternal(activity);
        if (f instanceof ru.kelemnfno.anime.ui.catalog.CatalogFragment) {
            ((ru.kelemnfno.anime.ui.catalog.CatalogFragment) f).applyQuery(query);
        }
    }

    private Fragment getSupportFragmentManagerInternal(MainActivity activity) {
        return activity.getSupportFragmentManager().findFragmentByTag(MainActivity.TAB_CATALOG);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (b != null) b.getRoot().removeCallbacks(heroTick);
        b = null;
        hero = null;
    }
}
