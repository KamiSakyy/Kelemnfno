package ru.kelemnfno.anime.ui;

/** Данные карточки аниме для списков и рядов. */
public class CardModel {
    public String slug = "";
    public int animeId;
    public String title = "";
    public String poster = "";
    public String subtitle = "";
    public double rating;
    public String badge;
    public boolean ongoing;
    /** 0..1 прогресс просмотра, -1 — не показывать. */
    public float progress = -1f;
    public boolean downloaded;
    /** Произвольная нагрузка: серия/озвучка для «Продолжить просмотр». */
    public String episode;
    public String dubbing;

    public CardModel() {
    }

    public CardModel(String slug, String title, String poster) {
        this.slug = slug;
        this.title = title;
        this.poster = poster;
    }
}
