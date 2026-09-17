package ru.kelemnfno.anime.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Точный отсчёт до выхода серии: «через 1 ч 17 мин». */
public final class Countdown {

    private Countdown() {
    }

    public static String format(long targetMs, long nowMs) {
        long diff = targetMs - nowMs;
        if (diff <= 0) return "прямо сейчас";
        long m = diff / 60000L;
        if (m < 1) return "менее минуты";
        if (m < 60) return "через " + m + " мин";
        long h = m / 60;
        long rm = m % 60;
        if (h < 24) return rm > 0 ? "через " + h + " ч " + rm + " мин" : "через " + h + " ч";
        long d = h / 24;
        long rh = h % 24;
        return rh > 0 ? "через " + d + " д " + rh + " ч" : "через " + d + " д";
    }

    public static String format(long targetMs) {
        return format(targetMs, System.currentTimeMillis());
    }

    public static String dateTime(long ts) {
        if (ts <= 0) return "";
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(ts));
        String day = new SimpleDateFormat("d MMM", new Locale("ru")).format(c.getTime());
        String wd = new SimpleDateFormat("EEE", new Locale("ru")).format(c.getTime());
        String time = new SimpleDateFormat("HH:mm", new Locale("ru")).format(c.getTime());
        return wd + ", " + day + ", " + time;
    }

    /** Понедельник текущей недели, 00:00. */
    public static Calendar startOfWeek(Calendar base) {
        Calendar x = (Calendar) base.clone();
        x.set(Calendar.HOUR_OF_DAY, 0);
        x.set(Calendar.MINUTE, 0);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        int day = (x.get(Calendar.DAY_OF_WEEK) + 5) % 7; // пн = 0
        x.add(Calendar.DAY_OF_YEAR, -day);
        return x;
    }

    public static int weekdayIndex(Calendar c) {
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }
}
