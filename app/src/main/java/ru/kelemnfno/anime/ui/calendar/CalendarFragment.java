package ru.kelemnfno.anime.ui.calendar;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.FavoriteEntity;
import ru.kelemnfno.anime.data.model.ScheduleItem;
import ru.kelemnfno.anime.data.repo.AnimeRepository;
import ru.kelemnfno.anime.databinding.FragmentCalendarBinding;
import ru.kelemnfno.anime.databinding.ItemCalendarRowBinding;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.ui.detail.DetailActivity;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Countdown;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Календарь выхода серий: неделя, дни, отсчёт до релиза, фильтр «только избранное». */
public class CalendarFragment extends Fragment {

    private static final String[] WEEKDAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
    private static final String[] WEEKDAYS_FULL = {"понедельник", "вторник", "среда", "четверг",
            "пятница", "суббота", "воскресенье"};

    private FragmentCalendarBinding b;
    private List<ScheduleItem> items = new ArrayList<>();
    private final List<String> favSlugs = new ArrayList<>();
    private final java.util.Map<String, FavoriteEntity> favBySlug = new java.util.HashMap<>();
    private int selectedDay = -1;
    private boolean onlyFav;
    private final List<Row> rows = new ArrayList<>();

    private static class Row {
        ScheduleItem item;
        long at;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCalendarBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        selectedDay = Countdown.weekdayIndex(Calendar.getInstance());

        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(new Adapter());
        b.empty.emptyIcon.setImageResource(R.drawable.ic_calendar);
        b.empty.emptyText.setText("На этот день релизов нет");

        b.onlyFav.setOnClickListener(v -> {
            onlyFav = !onlyFav;
            b.onlyFav.setBackgroundResource(onlyFav ? R.drawable.bg_pill_white : R.drawable.bg_chip);
            b.onlyFav.setTextColor(requireContext().getColor(onlyFav ? R.color.bg : R.color.text));
            renderWeekdays();
        });

        AppDatabase.get(requireContext()).favoriteDao().observeAll().observe(getViewLifecycleOwner(), favs -> {
            favSlugs.clear();
            favBySlug.clear();
            if (favs != null) for (FavoriteEntity f : favs) {
                favSlugs.add(f.slug);
                favBySlug.put(f.slug, f);
            }
            renderWeekdays();
        });

        load();
    }

    private void load() {
        AppExecutors.get().run(() -> AnimeRepository.get(requireContext()).schedule(), (value, error) -> {
            if (b == null) return;
            if (error != null) {
                Ui.toast(requireContext(), "Не удалось загрузить расписание");
                return;
            }
            items = new ArrayList<>();
            for (ScheduleItem s : value) {
                if (s.episodes == null) continue;
                // Сезон завершён (вышло столько, сколько всего) — это не будущий релиз,
                // иначе календарь врёт «серия 13 из 12 — уже вышла».
                if (s.episodes.count > 0 && s.episodes.safeAired() >= s.episodes.count) continue;
                items.add(s);
            }
            renderRange();
            renderNext();
            renderWeekdays();
        });
    }

    private void renderRange() {
        Calendar start = Countdown.startOfWeek(Calendar.getInstance());
        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_YEAR, 6);
        String left = new java.text.SimpleDateFormat("d MMM", new Locale("ru")).format(start.getTime());
        String right = new java.text.SimpleDateFormat("d MMM", new Locale("ru")).format(end.getTime());
        b.range.setText(left + " — " + right + " · " + items.size() + " релизов");
    }

    private void renderNext() {
        long now = System.currentTimeMillis();
        // Сначала ищем ближайшую серию из избранного — её и показываем крупно.
        ScheduleItem best = null;
        long bestTs = Long.MAX_VALUE;
        boolean fromFavorites = false;
        for (ScheduleItem it : items) {
            if (!favSlugs.contains(it.animeUrl)) continue;
            long ts = nextTs(it);
            if (ts > now && ts < bestTs) {
                best = it;
                bestTs = ts;
                fromFavorites = true;
            }
        }
        if (best == null) {
            for (ScheduleItem it : items) {
                if (onlyFav || !favSlugs.contains(it.animeUrl)) continue;
                long ts = nextTs(it);
                if (ts > now && ts < bestTs) {
                    best = it;
                    bestTs = ts;
                }
            }
        }
        if (best == null) {
            b.nextRelease.setVisibility(View.GONE);
            return;
        }
        b.nextRelease.setVisibility(View.VISIBLE);
        final ScheduleItem target = best;
        int number = airedShown(target) + 1;
        b.nextReleaseText.setText((fromFavorites ? "В избранном: " : "Следующий релиз: ")
                + target.title + " · серия " + number
                + " — " + Countdown.format(bestTs, now));
        b.nextReleaseText.setTextColor(requireContext().getColor(
                fromFavorites ? R.color.accent : R.color.text));
        b.nextRelease.setOnClickListener(v -> DetailActivity.open(requireContext(), target.animeUrl));
    }

    /** Не утверждать больше вышедших серий, чем проверено: для избранного — факт из карточки. */
    private int airedShown(ScheduleItem it) {
        int aired = it.episodes.safeAired();
        FavoriteEntity fe = favBySlug.get(it.animeUrl);
        if (fe != null && fe.episodeCount > 0 && fe.episodeCount < aired) aired = fe.episodeCount;
        return aired;
    }

    private long nextTs(ScheduleItem it) {
        if (it.episodes.nextDate > 0) return it.episodes.nextDateMs();
        if (it.episodes.prevDate > 0 && it.episodes.aired < it.episodes.count) {
            return it.episodes.prevDateMs() + 7L * 86400_000L;
        }
        return 0;
    }

    private void renderWeekdays() {
        if (b == null) return;
        b.weekdays.removeAllViews();
        addWeekdayChip(Chips.chip(requireContext(), "Все", selectedDay == -1, v -> {
            selectedDay = -1;
            renderWeekdays();
        }));
        for (int i = 0; i < 7; i++) {
            final int day = i;
            int count = countFor(day);
            addWeekdayChip(Chips.chip(requireContext(), WEEKDAYS[i] + (count > 0 ? " · " + count : ""),
                    day == selectedDay, v -> {
                selectedDay = day;
                renderWeekdays();
            }));
        }
        renderList();
    }

    /** Одинаковый отступ между чипами дней. */
    private void addWeekdayChip(TextView chip) {
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        int gap = Ui.dp(requireContext(), 8);
        lp.setMargins(0, 0, gap, 0);
        chip.setLayoutParams(lp);
        b.weekdays.addView(chip);
    }

    private int countFor(int day) {
        int count = 0;
        for (ScheduleItem it : items) {
            if (onlyFav && !favSlugs.contains(it.animeUrl)) continue;
            long ts = nextTs(it);
            if (ts <= 0) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ts);
            if (Countdown.weekdayIndex(c) == day) count++;
        }
        return count;
    }

    private void renderList() {
        rows.clear();
        for (ScheduleItem it : items) {
            if (onlyFav && !favSlugs.contains(it.animeUrl)) continue;
            long ts = nextTs(it);
            if (ts <= 0) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(ts);
            if (selectedDay >= 0 && Countdown.weekdayIndex(c) != selectedDay) continue;
            Row r = new Row();
            r.item = it;
            r.at = ts;
            rows.add(r);
        }
        rows.sort((x, y) -> Long.compare(x.at, y.at));
        b.list.getAdapter().notifyDataSetChanged();
        b.empty.getRoot().setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        b.list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        b.empty.emptyText.setText(selectedDay == -1
                ? "На этой неделе релизов нет" : "На этот день релизов нет");
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(ItemCalendarRowBinding.inflate(getLayoutInflater(), parent, false));
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
            private final ItemCalendarRowBinding b;

            Holder(ItemCalendarRowBinding binding) {
                super(binding.getRoot());
                this.b = binding;
            }

            void bind(Row r) {
                ScheduleItem it = r.item;
                Ui.poster(b.poster, Fmt.posterUrl(it.poster, "big"), 10);
                b.title.setText(it.title);
                String time = new java.text.SimpleDateFormat("HH:mm", new Locale("ru")).format(new java.util.Date(r.at));
                Calendar rc = Calendar.getInstance();
                rc.setTimeInMillis(r.at);
                b.meta.setText("Серия " + (airedShown(it) + 1)
                        + (it.episodes.count > 0 ? " из " + it.episodes.count : "") + " · " + time
                        + " · " + WEEKDAYS_FULL[Countdown.weekdayIndex(rc)]);
                long now = System.currentTimeMillis();
                boolean fav = favSlugs.contains(it.animeUrl);
                String state;
                if (r.at > now) state = Countdown.format(r.at, now);
                else if (now - r.at <= 3L * 86400_000L) state = "уже вышла";
                else state = "дата уточняется";
                b.countdown.setText((fav ? "\u2605 " : "") + state);
                b.countdown.setTextColor(requireContext().getColor(
                        fav ? R.color.accent : R.color.text_mute));
                b.meta.setText((fav ? "В избранном · " : "") + b.meta.getText());
                b.getRoot().setOnClickListener(v -> DetailActivity.open(requireContext(), it.animeUrl));
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
