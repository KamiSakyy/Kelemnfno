package ru.kelemnfno.anime.data.model;

import java.util.ArrayList;
import java.util.List;

/** Результат работы источника: серии + собранные озвучки. */
public class SourceResult implements java.io.Serializable {

    public String source = "";
    public List<EpisodeRow> episodes = new ArrayList<>();
    public List<Track> tracks = new ArrayList<>();

    public static SourceResult of(String source, List<EpisodeRow> eps) {
        SourceResult r = new SourceResult();
        r.source = source;
        r.episodes = eps == null ? new ArrayList<>() : eps;
        return r;
    }
}
