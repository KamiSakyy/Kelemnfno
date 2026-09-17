package ru.kelemnfno.anime.data.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Внутренний маршрут серии: прямой поток по качествам либо embed плеера. */
public class VariantRow {
    public String voice = "";
    public String embed;
    public Map<Integer, String> streams = new LinkedHashMap<>();
    public String source = "";
    public int quality;

    public String routeKey() {
        if (embed != null && !embed.isEmpty()) return embed;
        return streams.toString();
    }
}
