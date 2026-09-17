package ru.kelemnfno.anime.data.model;

import java.util.ArrayList;
import java.util.List;

/** Что мы знаем об аниме — по этому ищутся озвучки во всех источниках. */
public class Lookup {
    public String title = "";
    public String original;
    public int year;
    public int malId;
    public int shikimoriId;
    public int yummyId;
    public int kpId;
    public String anilibriaAlias;
    public List<String> genres = new ArrayList<>();

    public Lookup copy() {
        Lookup l = new Lookup();
        l.title = title;
        l.original = original;
        l.year = year;
        l.malId = malId;
        l.shikimoriId = shikimoriId;
        l.yummyId = yummyId;
        l.kpId = kpId;
        l.anilibriaAlias = anilibriaAlias;
        l.genres = genres == null ? new ArrayList<>() : new ArrayList<>(genres);
        return l;
    }

    public boolean adult() {
        if (genres == null) return false;
        for (String g : genres) {
            String v = g == null ? "" : g.toLowerCase();
            if (v.contains("этти") || v.contains("эроти") || v.contains("18") || v.contains("hentai")) return true;
        }
        return false;
    }
}
