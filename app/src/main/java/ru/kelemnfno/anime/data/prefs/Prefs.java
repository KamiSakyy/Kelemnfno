package ru.kelemnfno.anime.data.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.data.model.AppSettings;

/** Настройки и «недавние поиски» — SharedPreferences + Gson. */
public final class Prefs {

    private static final String FILE = "kelemnfno_prefs";
    private static final String SETTINGS = "settings_v1";
    private static final String RECENT = "recent_search_v1";
    private static final String PERMISSION_ASKED = "notif_permission_asked";

    private static volatile Prefs instance;

    private final SharedPreferences sp;
    private final Gson gson = new Gson();

    private Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        if (instance == null) {
            synchronized (Prefs.class) {
                if (instance == null) instance = new Prefs(context);
            }
        }
        return instance;
    }

    public AppSettings settings() {
        String raw = sp.getString(SETTINGS, null);
        if (raw == null) return new AppSettings();
        try {
            AppSettings s = gson.fromJson(raw, AppSettings.class);
            return s == null ? new AppSettings() : s;
        } catch (Exception e) {
            return new AppSettings();
        }
    }

    public void saveSettings(AppSettings settings) {
        sp.edit().putString(SETTINGS, gson.toJson(settings)).apply();
    }

    public AppSettings update(java.util.function.Consumer<AppSettings> patch) {
        AppSettings s = settings();
        patch.accept(s);
        saveSettings(s);
        return s;
    }

    public List<String> recentSearch() {
        String raw = sp.getString(RECENT, null);
        if (raw == null) return new ArrayList<>();
        try {
            String[] arr = gson.fromJson(raw, String[].class);
            List<String> out = new ArrayList<>();
            if (arr != null) for (String s : arr) out.add(s);
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public void addRecentSearch(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() < 2) return;
        List<String> next = new ArrayList<>();
        next.add(clean);
        for (String s : recentSearch()) {
            if (!s.equalsIgnoreCase(clean) && next.size() < 8) next.add(s);
        }
        sp.edit().putString(RECENT, gson.toJson(next)).apply();
    }

    public void clearRecentSearch() {
        sp.edit().remove(RECENT).apply();
    }

    public boolean permissionAsked() {
        return sp.getBoolean(PERMISSION_ASKED, false);
    }

    public void setPermissionAsked(boolean v) {
        sp.edit().putBoolean(PERMISSION_ASKED, v).apply();
    }
}
