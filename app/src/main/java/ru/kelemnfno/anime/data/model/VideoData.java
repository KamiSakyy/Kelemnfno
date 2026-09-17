package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

public class VideoData {
    public String player;
    public String dubbing;
    @SerializedName("player_id")
    public int playerId;
}
