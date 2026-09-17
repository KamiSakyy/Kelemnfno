package ru.kelemnfno.anime.data.tsuyu;

/**
 * Адреса сервиса не лежат в dex открытым текстом: строки хранятся в коде
 * побитово инвертированными и собираются в рантайме.
 */
public final class Secrets {

    private static final int K = 90;

    private Secrets() {
    }

    private static String d(String s) {
        char[] c = s.toCharArray();
        StringBuilder sb = new StringBuilder(c.length);
        for (int i = 0; i < c.length; i++) sb.append((char) (c[i] ^ K));
        return sb.toString();
    }

    private static final String API = d("2..*)`uu;*3t#;43t.,u");
    private static final String STATIC_HOST = d("2..*)`uu).;.39t#;43t.,");
    private static final String SHIKI = d("2..*)`uu)231375(3t54?");

    /** База API: https://…/ */
    public static String apiBase() {
        return API;
    }

    /** Хост картинок без завершающего слэша. */
    public static String staticBase() {
        return STATIC_HOST;
    }

    /** Хост скриншотов. */
    public static String shikimori() {
        return SHIKI;
    }
    private static final String REFERER = d("2..*)`uu#;43t.,u");
    private static final String ALLOHA = d("2..*)`uu;6652;t#;43t.,");
    private static final String BARE = d("#;43t.,");

    /** Referer для запросов к плеерам. */
    public static String referer() {
        return REFERER;
    }

    /** Хост одного из встроенных плееров. */
    public static String alloha() {
        return ALLOHA;
    }

    /** Домен сервиса без схемы. */
    public static String bareHost() {
        return BARE;
    }
}
