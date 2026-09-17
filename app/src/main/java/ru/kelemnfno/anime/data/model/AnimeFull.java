package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Полная карточка: /anime/{slug}?need_videos=true */
public class AnimeFull extends AnimeItem {
    public double duration;
    @SerializedName("other_titles")
    public List<String> otherTitles;
    public List<PersonRef> creators;
    public List<PersonRef> studios;
    public String original;
    @SerializedName("viewing_order")
    public List<ViewingOrderItem> viewingOrder;
    public List<VideoItem> videos;
    public Episodes episodes;
}
