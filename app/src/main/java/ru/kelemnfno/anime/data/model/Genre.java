package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

public class Genre {
    public String title;
    public String href;
    public int value;
    @SerializedName("more_titles")
    public List<String> moreTitles = new ArrayList<>();
    @SerializedName("group_id")
    public int groupId;
}
