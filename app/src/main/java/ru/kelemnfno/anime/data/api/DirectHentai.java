package ru.kelemnfno.anime.data.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import ru.kelemnfno.anime.data.model.Site;
import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.model.Track;

/**
 * Прямые HLS-потоки AniLibria для раздела 18+: плеер получает серии
 * без повторного подбора источников.
 */
public final class DirectHentai {

    private static final Map<String, Map<Integer, List<StreamSource>>> REG = new ConcurrentHashMap<>();

    private DirectHentai() {
    }

    /** Публикует серии (номер -> качество -> url) и возвращает дорожку для плеера. */
    public static Track publish(Map<Integer, Map<Integer, String>> epQualities) {
        String id = "dh" + System.nanoTime();
        Map<Integer, List<StreamSource>> m = new HashMap<>();
        Track t = new Track();
        t.id = id;
        t.voice = "AniLibria";
        t.site = new Site("anilibria", "AniLibria.TV", "https://anilibria.top", "", 100);
        int maxQ = 0;
        for (Map.Entry<Integer, Map<Integer, String>> e : new TreeMap<>(epQualities).entrySet()) {
            List<StreamSource> list = new ArrayList<>();
            for (Map.Entry<Integer, String> q : new TreeMap<>(e.getValue()).entrySet()) {
                StreamSource s = new StreamSource();
                s.quality = q.getKey();
                s.url = q.getValue();
                s.kind = "hls";
                s.voice = "AniLibria";
                s.label = q.getKey() + "p";
                list.add(s);
                maxQ = Math.max(maxQ, q.getKey());
            }
            m.put(e.getKey(), list);
            t.episodes.add(e.getKey());
        }
        t.total = t.episodes.isEmpty() ? 0 : t.episodes.get(t.episodes.size() - 1);
        t.maxQuality = maxQ;
        REG.put(id, m);
        return t;
    }

    /** Прямые потоки для дорожки; null — если дорожка не отсюда. */
    public static List<StreamSource> streams(String trackId, int ep) {
        Map<Integer, List<StreamSource>> m = REG.get(trackId);
        if (m == null) return null;
        List<StreamSource> got = m.get(ep);
        return got == null ? new ArrayList<>() : got;
    }
}
