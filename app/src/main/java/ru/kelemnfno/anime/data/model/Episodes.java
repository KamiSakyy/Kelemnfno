package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

/** Счётчик серий онгоинга + даты выхода (unix-секунды). */
public class Episodes {
    public int count;
    public int aired;
    @SerializedName("next_date")
    public long nextDate;
    @SerializedName("prev_date")
    public long prevDate;

    public long nextDateMs() {
        return nextDate * 1000L;
    }

    /** Источник иногда врёт: «вышло 15 из 14». Число вышедших не может превышать общее. */
    public int safeAired() {
        return count > 0 ? Math.min(aired, count) : aired;
    }

    public long prevDateMs() {
        return prevDate * 1000L;
    }
}
