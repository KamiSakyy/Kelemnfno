package ru.kelemnfno.anime.data.model;

/** Источник озвучки — имя, ключ, домен и вес приоритета. */
public class Site implements java.io.Serializable {

    public final String id;
    public final String name;
    public final String url;
    public final String color;
    public final int weight;

    public Site(String id, String name, String url, String color, int weight) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.color = color;
        this.weight = weight;
    }
}
