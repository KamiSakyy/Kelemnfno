package ru.kelemnfno.anime.data.model;

import java.util.ArrayList;
import java.util.List;

public class EpisodeRow {
    public int number;
    public String name;
    public String poster;
    public double duration;
    public List<VariantRow> variants = new ArrayList<>();
}
