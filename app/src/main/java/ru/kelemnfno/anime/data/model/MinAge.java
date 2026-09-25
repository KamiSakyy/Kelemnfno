package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

public class MinAge {
    public int value;
    public String title;
    @SerializedName("title_long")
    public String titleLong;
}
