package ru.kelemnfno.anime.data.resolver;

/**
 * Отпечаток открытого ключа API для SSL-пиннинга.
 * Значение подставляет сборочный конвейер после проверки живым запросом;
 * пустая строка означает, что пиннинг выключен.
 */
public final class Pins {

    public static final String API = "";

    /** Отпечаток промежуточного центра: переживает замену листового сертификата. */
    public static final String API_CA = "";

    private Pins() {
    }
}
