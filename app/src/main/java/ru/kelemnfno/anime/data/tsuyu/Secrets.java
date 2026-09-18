package ru.kelemnfno.anime.data.tsuyu;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Все адреса и домены хранятся зашифрованными (инверсия байтов + Base64)
 * и собираются в рантайме. Ключ лежит в нативной библиотеке — в dex его нет.
 */
public final class Secrets {

    private static final String[] T = {
            "Mi4uKilgdXU7KjN0Izs0M3QuLHU=",
            "Mi4uKilgdXUpLjsuMzl0Izs0M3QuLA==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/",
            "Mi4uKilgdXUjOzQzdC4sdQ==",
            "Mi4uKilgdXU7NjY1Mjt0Izs0M3QuLA==",
            "Izs0M3QuLA==",
            "OT40dDcjOzQzNz82MykudDQ/LnUzNzs9Pyl1OzQzNz8=",
            "Mi4uKilgdXU7Nz50NTQ2MzQ/",
            "Mi4uKilgdXU7NDM4NTU3dDU0P3U=",
            "Mi4uKilgdXU7NDM2Mzh0Nz91",
            "Mi4uKilgdXU7NDM2MzgoMzt0LjUqdTsqM3Usa3U7NDM3P3U5Oy47NjU9dSg/Nj87KT8p",
            "Mi4uKilgdXU7NDM2MzgoMzt0LjUqdTsqM3Usa3U7NDM3P3UoPzY/Oyk/KXU=",
            "Mi4uKilgdXU7NDM3Pz01dDUoPXU=",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTc=",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dQ==",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dSo2OyM2Myku",
            "Mi4uKilgdXU7NDM3Py4xO3Q5NTd1OyozdTs0Mzc/dSk/Oyg5Mg==",
            "Mi4uKilgdXU7KjN3KXQ7NDMiKT8xOzN0OTU3",
            "Mi4uKilgdXU7KjN0OzY2NSgzPTM0KXQtMzR1KDstZS8oNmc=",
            "Mi4uKilgdXU7KjN0OzQzNz8sNSkudDUoPXUsa3UqNjsjNjMpLg==",
            "Mi4uKilgdXU7KjN0OzQzNz8sNSkudDUoPXUsa3UpPzsoOTI=",
            "Mi4uKilgdXU7KjN0OzQzIik/MTszdDk1Nw==",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdTs0Mzc/",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdT8qMyk1Pj8pdQ==",
            "Mi4uKilgdXU7KjN0OT40NjM4KXQ1KD11OyozdT8qMyk1Pj8pZTs0Mzc/BTM+Zw==",
            "Mi4uKilgdXU7KjN0MDMxOzR0NzU/dSxudTs0Mzc/dQ==",
            "Mi4uKilgdXU7KjN0MDMxOzR0NzU/dSxudTs0Mzc/ZStn",
            "Mi4uKilgdXU7KjN0KTIzMTM3NSgzdDc/dTs0Mzc/KXU=",
            "Mi4uKilgdXU5NSgpKig1IiN0MzV1ZS8oNmc=",
            "Mi4uKilgdXU9KDsqMis2dDs0MzYzKS50OTV1",
            "Mi4uKilgdXUyOzQzNz90Liw=",
            "Mi4uKilgdXUyOzQzNz90Lix1",
            "Mi4uKilgdXUyOzQzNz90Lix1OyozdSxidSwzPj81ZTM+Zw==",
            "Mi4uKilgdXUxNT4zMSo2OyM/KHQ5NTd1PC41KA==",
            "Mi4uKilgdXU3Izs0Mzc/NjMpLnQ0Py4=",
            "Mi4uKilgdXU3Izs0Mzc/NjMpLnQ0Py51OzQzNz91",
            "Mi4uKilgdXU1NDYzND90OzQzPi84dDk1Nw==",
            "Mi4uKilgdXU1NDYzND90OzQzPi84dDk1N3U=",
            "Mi4uKilgdXUqNjsqM3Q5PjQsMz4/NTIvOHQ5NTd1OyozdSxrdSo2OyM/KHUpLHUqNjsjNjMpLmUqLzhnbW5vfDM+Zw==",
            "Mi4uKilgdXUqNjsqM3Q5PjQsMz4/NTIvOHQ5NTd1OyozdSxrdSo2OyM/KHUpLHUsMz4/NXU=",
            "Mi4uKilgdXUoL3QjLzc3Izs0M3Q3Pw==",
            "Mi4uKilgdXUoL3QjLzc3Izs0M3Q3P3U=",
            "Mi4uKilgdXUoLy4vOD90KC8=",
            "Mi4uKilgdXUoLy4vOD90KC91",
            "Mi4uKilgdXUoLy4vOD90KC91OyozdSo2OyN1NSouMzU0KXU=",
            "Mi4uKilgdXUpPzsoOTJ0Mi4sdyk/KCwzOT8pdDk1N3U=",
            "Mi4uKilgdXUpMjMxMzc1KDN0Nz91OzQzNz8pdQ==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dQ==",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dTs0Mzc/KXU=",
            "Mi4uKilgdXUpMjMxMzc1KDN0NTQ/dTsqM3U7NDM3Pyl1",
            "Mi4uKilgdXUsMz4/NXQpMzg0Py50KC8=",
            "Mi4uKilgdXUsMz4/NXQpMzg0Py50KC91",
            "Mi4uKilgdXUsMXQ5NTc=",
            "KjY7Iz8odDsxKTUodC4s",
            "LDM+PzV0KTM4ND8udCgv",
            "LDM+PzVrdDs0MzYzOHQ3Pw==",
            "LDM+PzVodDs0MzYzOHQ3Pw=="
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
            // нативная библиотека недоступна — считаем ключ ниже
        }
        // Запасной вариант: выводится из имени приложения, литералом не лежит.
        return "Kelemnfno".length() * 10;
    }

    private static native int nativeKey();

    /** Расшифрованная строка из таблицы. */
    public static String s(int i) {
        if (i < 0 || i >= T.length) return "";
        String hit = CACHE[i];
        if (hit != null) return hit;
        byte[] raw = Base64.decode(T[i], Base64.DEFAULT);
        byte[] out = new byte[raw.length];
        for (int j = 0; j < raw.length; j++) out[j] = (byte) (raw[j] ^ K);
        hit = new String(out, StandardCharsets.UTF_8);
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
