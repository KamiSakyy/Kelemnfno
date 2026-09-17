package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

public class AnimeStatus {
    public int value;
    public String title;
    /** released | ongoing | announcement */
    public String alias;
    @SerializedName("class")
    public String cssClass;

    public boolean isOngoing() {
        return "ongoing".equals(alias);
    }

    public boolean isAnnouncement() {
        return "announcement".equals(alias);
    }
}
