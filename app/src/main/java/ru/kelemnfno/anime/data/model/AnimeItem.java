package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Карточка аниме из каталога/поиска/главной. */
public class AnimeItem {
    public String description;
    public Poster poster;
    public String title;
    @SerializedName("anime_url")
    public String animeUrl;
    @SerializedName("anime_id")
    public int animeId;
    public Rating rating;
    public List<GenreShort> genres;
    public int year;
    @SerializedName("min_age")
    public MinAge minAge;
    public int views;
    public int season;
    @SerializedName("anime_status")
    public AnimeStatus animeStatus;
    public AnimeType type;
    public TopInfo top;
    @SerializedName("blocked_in")
    public List<String> blockedIn;
    @SerializedName("remote_ids")
    public RemoteIds remoteIds;
}
