package ru.kelemnfno.anime.data.model;

import java.util.ArrayList;
import java.util.List;

/** Дорожка озвучки: источник + название + список серий + максимум качества. */
public class Track implements java.io.Serializable {

    public String id = "";
    public String voice = "";
    public Site site;
    public List<Integer> episodes = new ArrayList<>();
    public int total;
    public int maxQuality;

    public boolean hasEpisode(int ep) {
        for (int e : episodes) if (e == ep) return true;
        return false;
    }

    /** Ближайшая доступная серия (для «Смотреть»). */
    public int firstEpisode() {
        int best = Integer.MAX_VALUE;
        for (int e : episodes) if (e < best) best = e;
        return best == Integer.MAX_VALUE ? 1 : best;
    }
}
