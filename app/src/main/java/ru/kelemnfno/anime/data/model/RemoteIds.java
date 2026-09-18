package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

/** Внешние идентификаторы: нужны для скриншотов и точного матчинга источников. */
public class RemoteIds {
    @SerializedName("worldart_id")
    public int worldartId;
    @SerializedName("shikimori_id")
    public int shikimoriId;
    @SerializedName("anidub_id")
    public int anidubId;
    @SerializedName("anilibria_alias")
    public String extAlias;
    @SerializedName("myanimelist_id")
    public int malId;
    @SerializedName("kp_id")
    public int kpId;
    @SerializedName("worldart_type")
    public String worldartType;
    @SerializedName("sr_id")
    public int srId;
}
