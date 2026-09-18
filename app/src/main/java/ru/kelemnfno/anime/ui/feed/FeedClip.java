package ru.kelemnfno.anime.ui.feed;

/** Один клип ленты: случайный кусок серии длиной 15–30 секунд. */
public class FeedClip {

    public String title = "";
    public String slug = "";
    public int episode;
    public String url = "";
    public String referer = "";
    public String voice = "";
    public String poster = "";
    /** С какой секунды серии начинаем. */
    public long startMs;
    /** Сколько показываем. */
    public long clipMs = 20_000L;
}
