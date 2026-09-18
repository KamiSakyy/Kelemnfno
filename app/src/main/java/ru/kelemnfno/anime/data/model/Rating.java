package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

public class Rating {
    public double average;
    public int counters;
    @SerializedName("kp_rating")
    public double kpRating;
    @SerializedName("anidub_rating")
    public double extRatingA;
    @SerializedName("myanimelist_rating")
    public double malRating;
    @SerializedName("worldart_rating")
    public double worldartRating;
    @SerializedName("shikimori_rating")
    public double shikimoriRating;
}
