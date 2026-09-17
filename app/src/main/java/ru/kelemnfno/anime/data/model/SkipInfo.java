package ru.kelemnfno.anime.data.model;

/** Опенинг/эндинг: старт и длительность в секундах. */
public class SkipInfo {
    public double time;
    public double length;

    public double end() {
        return time + length;
    }
}
