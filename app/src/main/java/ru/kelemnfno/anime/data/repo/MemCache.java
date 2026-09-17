package ru.kelemnfno.anime.data.repo;

import java.util.LinkedHashMap;
import java.util.Map;

/** Простой LRU-кэш с TTL — экономим трафик между экранами. */
public final class MemCache {

    private static class Row {
        final Object value;
        final long at;

        Row(Object value, long at) {
            this.value = value;
            this.at = at;
        }
    }

    private final LinkedHashMap<String, Row> map = new LinkedHashMap<String, Row>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Row> eldest) {
            return size() > 400;
        }
    };

    @SuppressWarnings("unchecked")
    public synchronized <T> T get(String key, long ttlMs) {
        Row row = map.get(key);
        if (row == null) return null;
        if (System.currentTimeMillis() - row.at > ttlMs) {
            map.remove(key);
            return null;
        }
        return (T) row.value;
    }

    public synchronized void put(String key, Object value) {
        map.put(key, new Row(value, System.currentTimeMillis()));
    }

    public synchronized void clear() {
        map.clear();
    }
}
