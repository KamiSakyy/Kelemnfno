package ru.kelemnfno.anime.data.model;

/** Пользовательские настройки приложения. */
public class AppSettings {
    /** Предпочитаемая озвучка. */
    public String preferredDub = "";
    /** Автопереход на следующую серию. */
    public boolean autoNext = true;
    /** Автоподбор рабочего источника при сбое. */
    public boolean autoSwitch = true;
    /** Уведомления о новых сериях избранного. */
    public boolean notifyNewEpisodes = true;
    /** Проверять только по Wi-Fi. */
    public boolean notifyWifiOnly = false;
    /** Период проверки в часах (минимум 1). */
    public int checkHours = 6;
    /** Качество по умолчанию для скачивания: 1080/720/480/360. */
    public int downloadQuality = 720;
    /** Скорость воспроизведения по умолчанию. */
    public float speed = 1f;
    /** Автозапуск без Wi-Fi предупреждения. */
    public boolean playOnMobile = true;
    /** Показывать 18+ жанры в фильтрах. */
    public boolean showAdult = true;
    /** Жесты в плеере. */
    public boolean gestures = true;
    /** Субтитры. */
    public boolean subtitles = true;
    /** Продолжать с последнего места. */
    public boolean resumePlayback = true;
    /** Пропускать опенинг автоматически. */
    public boolean skipOpening = false;
    /** ПиП при уходе из приложения. */
    public boolean pipOnLeave = true;
}
