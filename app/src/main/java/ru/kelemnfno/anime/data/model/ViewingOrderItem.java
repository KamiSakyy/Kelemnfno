package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

public class ViewingOrderItem {
    @SerializedName("anime_id")
    public int animeId;
    @SerializedName("anime_url")
    public String animeUrl;
    public OrderData data;
    public Poster poster;
    public String title;
    @SerializedName("anime_status")
    public AnimeStatus animeStatus;
    public AnimeType type;
    public int year;
    public String description;
    public double rating;

    public static class OrderData {
        public String text;
        public int id;
        public int index;
    }
}
