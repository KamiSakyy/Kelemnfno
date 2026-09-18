package ru.kelemnfno.anime.data.tsuyu;

/**
 * Все адреса и домены хранятся инвертированными и собираются в рантайме.
 * Ключ дешифровки лежит в нативной библиотеке, в dex его нет.
 */
public final class Secrets {

    private static final String[] T = {
            "2..*)`uu;*3t#;43t.,u",
            "2..*)`uu).;.39t#;43t.,",
            "2..*)`uu)231375(3t54?",
            "2..*)`uu#;43t.,u",
            "2..*)`uu;6652;t#;43t.,",
            "#;43t.,",
            "9>4t7#;437?63).t4?.u37;=?)u;437?",
            "2..*)`uu;7>t54634?",
            "2..*)`uu;438557t54?u",
            "2..*)`uu;43638t7?u",
            "2..*)`uu;43638(3;t.5*u;*3u,ku;437?u9;.;65=u(?6?;)?)",
            "2..*)`uu;43638(3;t.5*u;*3u,ku;437?u(?6?;)?)u",
            "2..*)`uu;437?=5t5(=u",
            "2..*)`uu;437?.1;t957",
            "2..*)`uu;437?.1;t957u",
            "2..*)`uu;437?.1;t957u;*3u;437?u",
            "2..*)`uu;437?.1;t957u;*3u;437?u*6;#63).",
            "2..*)`uu;437?.1;t957u;*3u;437?u)?;(92",
            "2..*)`uu;*3w)t;43\u0022)?1;3t957",
            "2..*)`uu;*3t;665(3=34)t-34u(;-e/(6g",
            "2..*)`uu;*3t;437?,5).t5(=u,ku*6;#63).",
            "2..*)`uu;*3t;437?,5).t5(=u,ku)?;(92",
            "2..*)`uu;*3t;43\u0022)?1;3t957",
            "2..*)`uu;*3t9>4638)t5(=u;*3u;437?",
            "2..*)`uu;*3t9>4638)t5(=u;*3u?*3)5>?)u",
            "2..*)`uu;*3t9>4638)t5(=u;*3u?*3)5>?)e;437?\u00053>g",
            "2..*)`uu;*3t031;4t75?u,nu;437?u",
            "2..*)`uu;*3t031;4t75?u,nu;437?e+g",
            "2..*)`uu;*3t)231375(3t7?u;437?)u",
            "2..*)`uu95()*(5\u0022#t35ue/(6g",
            "2..*)`uu=(;*2+6t;4363).t95u",
            "2..*)`uu2;437?t.,",
            "2..*)`uu2;437?t.,u",
            "2..*)`uu2;437?t.,u;*3u,bu,3>?5e3>g",
            "2..*)`uu15>31*6;#?(t957u<.5(",
            "2..*)`uu7#;437?63).t4?.",
            "2..*)`uu7#;437?63).t4?.u;437?u",
            "2..*)`uu54634?t;43>/8t957",
            "2..*)`uu54634?t;43>/8t957u",
            "2..*)`uu*6;*3t9>4,3>?52/8t957u;*3u,ku*6;#?(u),u*6;#63).e*/8gmno|3>g",
            "2..*)`uu*6;*3t9>4,3>?52/8t957u;*3u,ku*6;#?(u),u,3>?5u",
            "2..*)`uu(/t#/77#;43t7?",
            "2..*)`uu(/t#/77#;43t7?u",
            "2..*)`uu(/./8?t(/",
            "2..*)`uu(/./8?t(/u",
            "2..*)`uu(/./8?t(/u;*3u*6;#u5*.354)u",
            "2..*)`uu)?;(92t2.,w)?(,39?)t957u",
            "2..*)`uu)231375(3t7?u;437?)u",
            "2..*)`uu)231375(3t54?u",
            "2..*)`uu)231375(3t54?u;437?)u",
            "2..*)`uu)231375(3t54?u;*3u;437?)u",
            "2..*)`uu,3>?5t)384?.t(/",
            "2..*)`uu,3>?5t)384?.t(/u",
            "2..*)`uu,1t957",
            "*6;#?(t;1)5(t.,",
            ",3>?5t)384?.t(/",
            ",3>?5kt;43638t7?",
            ",3>?5ht;43638t7?"
    };

    private static final String[] CACHE = new String[T.length];
    private static final int K = key();

    private Secrets() {
    }

    private static int key() {
        try {
            System.loadLibrary("kelemnfno");
            int k = nativeKey();
            if (k != 0) return k;
        } catch (Throwable ignored) {
        }
        // Запасной вариант: ключ выводится из имени приложения, литералом не лежит.
        return "Kelemnfno".length() * 10;
    }

    private static native int nativeKey();

    /** Расшифрованная строка из таблицы. */
    public static String s(int i) {
        if (i < 0 || i >= T.length) return "";
        String hit = CACHE[i];
        if (hit != null) return hit;
        char[] c = T[i].toCharArray();
        StringBuilder sb = new StringBuilder(c.length);
        for (int j = 0; j < c.length; j++) sb.append((char) (c[j] ^ K));
        hit = sb.toString();
        CACHE[i] = hit;
        return hit;
    }

    /** База API. */
    public static String apiBase() {
        return s(0);
    }

    /** Хост картинок. */
    public static String staticBase() {
        return s(1);
    }

    /** Хост скриншотов. */
    public static String shikimori() {
        return s(2);
    }

    /** Referer для встроенных плееров. */
    public static String referer() {
        return s(3);
    }

    /** Хост одного из встроенных плееров. */
    public static String alloha() {
        return s(4);
    }

    /** Домен сервиса без схемы. */
    public static String bareHost() {
        return s(5);
    }
}
