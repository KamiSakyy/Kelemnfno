package ru.kelemnfno.anime.ui.favorites;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.FavoriteEntity;
import ru.kelemnfno.anime.databinding.FragmentFavoritesBinding;
import ru.kelemnfno.anime.notify.NewEpisodeWorker;
import ru.kelemnfno.anime.notify.NotificationHelper;
import ru.kelemnfno.anime.ui.AnimeCardAdapter;
import ru.kelemnfno.anime.ui.CardModel;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Countdown;
import ru.kelemnfno.anime.util.Ui;

/** Избранное: список тайтлов + ручная проверка новых серий. */
public class FavoritesFragment extends Fragment {

    private FragmentFavoritesBinding b;
    private AnimeCardAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentFavoritesBinding.inflate(inflater, container, false);
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
                AppExecutors.get().io().execute(() ->
                        AppDatabase.get(requireContext()).favoriteDao().deleteBySlug(model.slug));
                Ui.toast(requireContext(), "Удалено из избранного");
            }
        });
        b.grid.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        b.grid.addItemDecoration(new ru.kelemnfno.anime.ui.CardSpacing(requireContext(), 3));
        b.grid.setAdapter(adapter);
        b.empty.emptyIcon.setImageResource(R.drawable.ic_heart);
        b.empty.emptyText.setText(R.string.empty_favorites);

        b.check.setOnClickListener(v -> {
            if (!NotificationHelper.canNotify(requireContext())) {
                Ui.toast(requireContext(), "Разрешите уведомления в настройках системы");
            }
            NewEpisodeWorker.checkNow(requireContext());
            Ui.toast(requireContext(), "Проверяем новые серии…");
        });

        AppDatabase.get(requireContext()).favoriteDao().observeAll()
                .observe(getViewLifecycleOwner(), this::render);
    }

    private void render(List<FavoriteEntity> rows) {
        if (b == null) return;
        List<CardModel> models = new ArrayList<>();
        if (rows != null) {
            for (FavoriteEntity f : rows) {
                CardModel m = new CardModel(f.slug, f.title, f.poster);
                m.animeId = f.animeId;
                StringBuilder sb = new StringBuilder();
                if (f.year > 0) sb.append(f.year);
                if (f.episodeCount > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(f.episodeCount).append(" сер.");
                }
                if (f.nextDate > System.currentTimeMillis()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(Countdown.format(f.nextDate));
                }
                m.subtitle = sb.toString();
                models.add(m);
            }
        }
        adapter.submit(models);
        boolean empty = models.isEmpty();
        b.empty.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
        b.grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.notifyHint.setText(NotificationHelper.canNotify(requireContext())
                ? "Уведомления о новых сериях включены — проверка каждые несколько часов"
                : "Уведомления системы выключены: разрешите их, чтобы узнавать о новых сериях");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
