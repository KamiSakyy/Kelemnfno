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
            if (favs != null) for (FavoriteEntity f : favs) favSlugs.add(f.slug);
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
            for (ScheduleItem s : value) if (s.episodes != null) items.add(s);
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
        ScheduleItem best = null;
        long bestTs = Long.MAX_VALUE;
        for (ScheduleItem it : items) {
            if (onlyFav && !favSlugs.contains(it.animeUrl)) continue;
            long ts = nextTs(it);
            if (ts > now && ts < bestTs) {
                best = it;
                bestTs = ts;
            }
        }
        if (best == null) {
            b.nextRelease.setVisibility(View.GONE);
            return;
        }
        b.nextRelease.setVisibility(View.VISIBLE);
        final ScheduleItem target = best;
        int number = target.episodes.aired + 1;
        b.nextReleaseText.setText("Следующий релиз: " + target.title + " · серия " + number
                + " — " + Countdown.format(bestTs, now));
        b.nextRelease.setOnClickListener(v -> DetailActivity.open(requireContext(), target.animeUrl));
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
        for (int i = 0; i < 7; i++) {
            final int day = i;
            int count = countFor(day);
            TextView chip = Chips.chip(requireContext(), WEEKDAYS[i] + (count > 0 ? " · " + count : ""),
                    day == selectedDay, v -> {
                selectedDay = day;
                renderWeekdays();
            });
            b.weekdays.addView(chip);
        }
        renderList();
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
            if (Countdown.weekdayIndex(c) != selectedDay) continue;
            Row r = new Row();
            r.item = it;
            r.at = ts;
            rows.add(r);
        }
        rows.sort((x, y) -> Long.compare(x.at, y.at));
        b.list.getAdapter().notifyDataSetChanged();
        b.empty.getRoot().setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        b.list.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
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
                b.meta.setText("Серия " + (it.episodes.aired + 1)
                        + (it.episodes.count > 0 ? " из " + it.episodes.count : "") + " · " + time
                        + " · " + WEEKDAYS_FULL[selectedDay]);
                long now = System.currentTimeMillis();
                b.countdown.setText(r.at > now ? Countdown.format(r.at, now) : "уже вышла");
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
