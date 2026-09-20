package com.tsuyu.line;

import android.content.Context;

import java.util.ArrayList;

/**
 * Защитный подбор адресов. В исходной сборке файл был пустым — здесь
 * минимальная рабочая реализация: отдаёт исходный адрес без зеркал,
 * счётчики успеха/провала не ведутся. Этого достаточно, чтобы сеть
 * ходила напрямую, а сборка и выбор озвучек работали.
 */
public final class YoruShield {

    public YoruShield(Context context) {
    }

    /** Список адресов для попытки: только исходный. */
    public ArrayList<String> routes(String url) {
        ArrayList<String> out = new ArrayList<>();
        if (url != null && !url.isEmpty()) out.add(url);
        return out;
    }

    public void ok(String url) {
    }

    public void fail(String url) {
    }

    public void refresh(boolean force) {
    }
}
