package ru.kelemnfno.anime.data.tsuyu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Удобный доступ к JSON без динамической типизации. */
public final class J {

    private J() {
    }

    public static JsonObject obj(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    public static JsonObject obj(JsonObject parent, String key) {
        if (parent == null) return new JsonObject();
        JsonElement el = parent.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    public static JsonArray arr(JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    public static JsonArray arr(JsonObject parent, String key) {
        if (parent == null) return new JsonArray();
        JsonElement el = parent.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
    }

    public static List<JsonObject> list(JsonObject parent, String key) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement el : arr(parent, key)) {
            if (el != null && el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        return out;
    }

    public static List<JsonObject> list(JsonArray array) {
        List<JsonObject> out = new ArrayList<>();
        if (array == null) return out;
        for (JsonElement el : array) {
            if (el != null && el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        return out;
    }

    public static String str(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        if (el.isJsonPrimitive()) return el.getAsString();
        return el.toString();
    }

    public static String str(JsonObject parent, String key) {
        return parent == null ? "" : str(parent.get(key));
    }

    public static double num(JsonElement el) {
        if (el == null || el.isJsonNull()) return 0;
        try {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) return el.getAsDouble();
            return Double.parseDouble(str(el).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static double num(JsonObject parent, String key) {
        return parent == null ? 0 : num(parent.get(key));
    }

    public static int intOf(JsonObject parent, String key) {
        return (int) Math.round(num(parent, key));
    }

    /** Числовое значение из нескольких ключей подряд (первое ненулевое). */
    public static int firstNum(JsonObject parent, String... keys) {
        for (String k : keys) {
            double v = num(parent, k);
            if (v != 0) return (int) Math.round(v);
        }
        return 0;
    }

    public static String firstStr(JsonObject parent, String... keys) {
        for (String k : keys) {
            String v = str(parent, k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    public static boolean has(JsonObject parent, String key) {
        return parent != null && parent.has(key) && !parent.get(key).isJsonNull();
    }

    /** Обход всех строковых значений объекта (например, sources { "720": "http…" }). */
    public static List<Map.Entry<String, JsonElement>> entries(JsonObject parent) {
        List<Map.Entry<String, JsonElement>> out = new ArrayList<>();
        if (parent != null) for (Map.Entry<String, JsonElement> e : parent.entrySet()) out.add(e);
        return out;
    }
}
