package ru.kelemnfno.anime.data.resolver;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ru.kelemnfno.anime.data.model.EpisodeRow;
import ru.kelemnfno.anime.data.model.Lookup;
import ru.kelemnfno.anime.data.model.SourceResult;
import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.model.VariantRow;
import ru.kelemnfno.anime.data.repo.MemCache;

/**
 * Движок подбора: параллельно опрашивает все источники, объединяет маршруты
 * в «дорожки озвучки» и превращает их в прямые потоки.
 * Порт src/server/tsuyu/aggregate.ts.
 */
public final class SourceEngine {

    public static final String PREFIX = Cfg.s(470);

    /** Общий дедлайн опроса источников. */
    private static final long DEADLINE_MS = 14_000L;
    /** Источники с точным матчингом по ID — их маршруты надёжнее. */
    private static final Set<String> ID_SOURCES = new LinkedHashSet<>(
            java.util.Arrays.asList(Cfg.s(434), Cfg.s(210), Cfg.s(221), Cfg.s(222)));

    private static final Map<String, Bucket> ROUTES = new ConcurrentHashMap<>();
    private static final Map<String, Lookup> LOOKUPS = new ConcurrentHashMap<>();
    private static final MemCache TRACK_CACHE = new MemCache();
    private static final MemCache STREAM_CACHE = new MemCache();

    private SourceEngine() {
    }

    /** Дорожка озвучки вместе со скрытыми маршрутами. */
    private static class Bucket {
        final String voice;
        final String voiceKey;
        final String lookupKey;
        final Map<Integer, List<VariantRow>> routes = new TreeMap<>();
        int score;
        int maxQuality;

        Bucket(String voice, String voiceKey, String lookupKey) {
            this.voice = voice;
            this.voiceKey = voiceKey;
            this.lookupKey = lookupKey;
        }
    }

    public static String lookupKeyOf(Lookup l) {
        return sha1(l.malId + "|" + l.shikimoriId + "|" + l.sourceId + "|"
                + (l.title == null ? "" : l.title.toLowerCase()));
    }

    public static void clear() {
        ROUTES.clear();
        LOOKUPS.clear();
        TRACK_CACHE.clear();
        STREAM_CACHE.clear();
    }

    /* ---------------- Дорожки озвучки ---------------- */

    public static List<Track> tracks(final Lookup lookup) {
        if (Cfg.guarded()) return new java.util.ArrayList<>();
        String lookupKey = lookupKeyOf(lookup);
        List<Track> cached = TRACK_CACHE.get(Cfg.s(482) + lookupKey, 45 * 60_000L);
        if (cached != null) return cached;

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Scored>> futures = new ArrayList<>();
        for (final Sources.Source source : Sources.ALL) {
            if (source.adult() && !lookup.adult()) continue;
            futures.add(pool.submit(new Callable<Scored>() {
                @Override
                public Scored call() {
                    long started = System.currentTimeMillis();
                    SourceResult result = source.run(lookup.copy());
                    long ms = System.currentTimeMillis() - started;
                    boolean ok = result != null && !result.episodes.isEmpty();
                    return new Scored(source.id(), result == null ? SourceResult.of(source.id(), null) : result,
                            score(source.id(), ok, ms));
                }
            }));
        }

        for (Future<Scored> f : futures) {
            try {
                Scored s = f.get(DEADLINE_MS, TimeUnit.MILLISECONDS);
                if (s == null || s.result == null) continue;
                mergeInto(buckets, s, lookupKey);
            } catch (Exception ignored) {
                // источник промолчал — идём дальше
            }
        }
        pool.shutdownNow();

        List<Track> tracks = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
            Bucket b = e.getValue();
            if (b.routes.isEmpty()) continue;
            // Публикуем маршруты: именно отсюда streams() берёт плееры серий.
            // Без этой строки список озвучек есть, а поток получить нельзя.
            if (ROUTES.size() > 400) ROUTES.clear();
            ROUTES.put(e.getKey(), b);
            Track t = new Track();
            t.id = e.getKey();
            t.voice = b.voice;
            t.episodes = new ArrayList<>(b.routes.keySet());
            int max = 0;
            for (int n : t.episodes) max = Math.max(max, n);
            t.total = max;
            t.maxQuality = b.maxQuality;
            tracks.add(t);
        }
        // Сначала те, у кого больше серий; внутри — по надёжности источника.
        tracks.sort((a, b2) -> {
            int byCount = b2.episodes.size() - a.episodes.size();
            if (byCount != 0) return byCount;
            return score(b2.id) - score(a.id);
        });

        TRACK_CACHE.put(Cfg.s(482) + lookupKey, tracks);
        LOOKUPS.put(lookupKey, lookup);
        return tracks;
    }

    private static class Scored {
        final String source;
        final SourceResult result;
        final int score;

        Scored(String source, SourceResult result, int score) {
            this.source = source;
            this.result = result;
            this.score = score;
        }
    }

    /** Оценка надёжности: база + бонус источникам с точным ID-матчингом. */
    private static int score(String source, boolean ok, long ms) {
        Integer base = Sources.SOURCE_BASE.get(source);
        int v = base == null ? 45 : base;
        if (ok) v += 18;
        else v -= 24;
        if (ms > 6000) v -= 10;
        if (ID_SOURCES.contains(source)) v += 6;
        return v;
    }

    private static int score(String trackId) {
        Bucket b = ROUTES.get(trackId);
        return b == null ? 0 : b.score;
    }

    private static void mergeInto(Map<String, Bucket> buckets, Scored scored, String lookupKey) {
        for (EpisodeRow ep : scored.result.episodes) {
            for (VariantRow v : ep.variants) {
                String rawVoice = v.voice == null || v.voice.isEmpty() ? Cfg.s(438) : v.voice;
                String key = SourceUtil.voiceKey(rawVoice);
                if (key.isEmpty()) key = Cfg.s(479) + scored.source;
                String title = SourceUtil.voiceTitle(rawVoice);
                if (title.isEmpty()) title = rawVoice;

                Bucket bucket = buckets.get(key);
                if (bucket == null) {
                    bucket = new Bucket(title, key, lookupKey);
                    buckets.put(key, bucket);
                }
                bucket.score = Math.max(bucket.score, scored.score);
                List<VariantRow> list = bucket.routes.get(ep.number);
                if (list == null) {
                    list = new ArrayList<>();
                    bucket.routes.put(ep.number, list);
                }
                String routeKey = v.routeKey();
                boolean dup = false;
                for (VariantRow existing : list) {
                    if (routeKey.equals(existing.routeKey())) {
                        dup = true;
                        break;
                    }
                }
                if (dup) continue;
                list.add(v);
                if (v.quality > bucket.maxQuality) bucket.maxQuality = v.quality;
                if (v.streams != null) {
                    for (int q : v.streams.keySet()) bucket.maxQuality = Math.max(bucket.maxQuality, q);
                }
            }
        }
    }

    /* ---------------- Потоки серии ---------------- */

    /** Прямые потоки серии дорожки. При устаревших маршрутах — повторный подбор. */
    public static List<StreamSource> streams(String trackId, int episode, boolean retried) throws IOException {
        String key = trackId + ":" + episode;
        List<StreamSource> cached = STREAM_CACHE.get(key, 10 * 60_000L);
        if (cached != null && !cached.isEmpty()) return cached;

        Bucket bucket = ROUTES.get(trackId);
        if (bucket == null) throw new IOException(Cfg.s(486));
        List<VariantRow> variants = bucket.routes.get(episode);
        if (variants == null || variants.isEmpty()) throw new IOException(Cfg.s(439) + episode + Cfg.s(457));

        List<StreamSource> out = new ArrayList<>();
        IOException last = null;
        for (VariantRow v : variants) {
            try {
                if (v.streams != null && !v.streams.isEmpty()) {
                    TreeMap<Integer, String> sorted = new TreeMap<>(Collections.reverseOrder());
                    sorted.putAll(v.streams);
                    for (Map.Entry<Integer, String> e : sorted.entrySet()) {
                        out.add(source(e.getKey(), e.getValue(), refererFor(e.getValue()), bucket.voice));
                    }
                    if (!out.isEmpty()) break;
                } else if (v.embed != null && !v.embed.isEmpty()) {
                    Resolver.Resolved r = Resolver.resolveStreams(v.embed);
                    Map<Integer, String> verified = Resolver.verifyStreams(r.streams, r.referer);
                    if (verified.isEmpty() && !r.streams.isEmpty()) verified = r.streams;
                    for (Map.Entry<Integer, String> e : verified.entrySet()) {
                        out.add(source(e.getKey(), e.getValue(), r.referer, bucket.voice));
                    }
                    if (!out.isEmpty()) break;
                }
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                last = new IOException(e.getMessage());
            }
        }

        if (out.isEmpty()) {
            if (!retried) {
                Lookup lookup = LOOKUPS.get(bucket.lookupKey);
                if (lookup != null) {
                    TRACK_CACHE.clear();
                    STREAM_CACHE.clear();
                    List<Track> fresh = tracks(lookup);
                    Track same = null;
                    for (Track t : fresh) if (t.voice.equals(bucket.voice)) same = t;
                    if (same == null && !fresh.isEmpty()) same = fresh.get(0);
                    if (same != null) return streams(same.id, episode, true);
                }
            }
            throw last == null ? new IOException(Cfg.s(487)) : last;
        }

        STREAM_CACHE.put(key, out);
        return out;
    }

    public static List<StreamSource> streams(String trackId, int episode) throws IOException {
        return streams(trackId, episode, false);
    }

    /** Прямые потоки произвольного плеера (для скачивания и запасных маршрутов). */
    public static List<StreamSource> resolveEmbed(String embedUrl) throws IOException {
        List<StreamSource> cached = STREAM_CACHE.get(Cfg.s(468) + embedUrl, 10 * 60_000L);
        if (cached != null && !cached.isEmpty()) return cached;
        Resolver.Resolved r = Resolver.resolveStreams(embedUrl);
        Map<Integer, String> verified = Resolver.verifyStreams(r.streams, r.referer);
        if (verified.isEmpty() && !r.streams.isEmpty()) verified = r.streams;
        List<StreamSource> out = new ArrayList<>();
        for (Map.Entry<Integer, String> e : verified.entrySet()) {
            out.add(source(e.getKey(), e.getValue(), r.referer, ""));
        }
        if (out.isEmpty()) throw new IOException(Cfg.s(488));
        STREAM_CACHE.put(Cfg.s(468) + embedUrl, out);
        return out;
    }

    private static StreamSource source(int quality, String url, String referer, String voice) {
        StreamSource s = new StreamSource();
        s.quality = quality;
        s.url = url;
        s.referer = referer == null ? "" : referer;
        s.voice = voice == null ? "" : voice;
        s.kind = Resolver.kindOf(url);
        s.label = (quality > 0 ? quality + "p" : Cfg.s(485));
        return s;
    }

    private static String refererFor(String url) {
        String origin = SourceUtil.originOf(url);
        return origin.isEmpty() ? "" : origin + "/";
    }

    public static boolean isSrcRef(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /** Cfg.s(463) */
    public static String makeRef(String trackId, int episode) {
        return PREFIX + trackId + ":" + episode;
    }

    public static String trackOf(String ref) {
        if (!isSrcRef(ref)) return "";
        String rest = ref.substring(PREFIX.length());
        int i = rest.lastIndexOf(':');
        return i < 0 ? "" : rest.substring(0, i);
    }

    public static int episodeOf(String ref) {
        if (!isSrcRef(ref)) return -1;
        String rest = ref.substring(PREFIX.length());
        int i = rest.lastIndexOf(':');
        if (i < 0) return -1;
        try {
            return Integer.parseInt(rest.substring(i + 1));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Сравнение дорожек: у кого больше серий — тот и выше. */
    public static Comparator<Track> byCoverage() {
        return (a, b) -> b.episodes.size() - a.episodes.size();
    }

    private static String sha1(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance(Cfg.s(464));
            byte[] digest = md.digest(value.getBytes(Cfg.s(159)));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format(Cfg.s(458), b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Служебный доступ к маршрутам (нужно скачиванию и отладке). */
    public static Map<Integer, List<VariantRow>> routesOf(String trackId) {
        Bucket b = ROUTES.get(trackId);
        return b == null ? new TreeMap<>() : b.routes;
    }
}
