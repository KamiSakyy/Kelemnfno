package ru.kelemnfno.anime.data.repo;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.Response;
import ru.kelemnfno.anime.data.api.ApiClient;
import ru.kelemnfno.anime.data.api.YummyApi;
import ru.kelemnfno.anime.data.model.AnimeFull;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.ApiEnvelope;
import ru.kelemnfno.anime.data.model.GenresData;
import ru.kelemnfno.anime.data.model.ScheduleItem;
import ru.kelemnfno.anime.data.model.VideoItem;

/** Доступ к каталогу Yummy. Все методы блокирующие — вызывать из AppExecutors.io(). */
public final class AnimeRepository {

    private static final long TTL_LIST = 5 * 60_000L;
    private static final long TTL_DETAIL = 10 * 60_000L;
    private static final long TTL_SCHEDULE = 30 * 60_000L;
    private static final long TTL_GENRES = 12 * 60 * 60_000L;

    private static volatile AnimeRepository instance;

    private final YummyApi api;
    private final MemCache cache = new MemCache();

    private AnimeRepository(Context context) {
        api = ApiClient.api(context);
    }

    public static AnimeRepository get(Context context) {
        if (instance == null) {
            synchronized (AnimeRepository.class) {
                if (instance == null) instance = new AnimeRepository(context);
            }
        }
        return instance;
    }

    public void invalidate() {
        cache.clear();
    }

    private <T> T unwrap(Call<ApiEnvelope<T>> call) throws ApiException {
        try {
            Response<ApiEnvelope<T>> response = call.execute();
            if (!response.isSuccessful()) {
                throw new ApiException("HTTP " + response.code());
            }
            ApiEnvelope<T> body = response.body();
            if (body == null) throw new ApiException("Пустой ответ сервера");
            if (body.hasError()) throw new ApiException(body.message());
            if (body.response == null) throw new ApiException("Сервер вернул пустые данные");
            return body.response;
        } catch (IOException e) {
            throw new ApiException("Нет соединения с сервером", e);
        }
    }

    /** Параметры каталога: sort/status/genres/types/limit/offset/q. */
    public List<AnimeItem> list(Map<String, String> params) throws ApiException {
        Map<String, String> query = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) query.put(e.getKey(), e.getValue());
            }
        }
        String key = "list:" + query.toString();
        List<AnimeItem> hit = cache.get(key, TTL_LIST);
        if (hit != null) return hit;
        List<AnimeItem> data = unwrap(api.list(query));
        cache.put(key, data);
        return data;
    }

    public List<AnimeItem> search(String q, int limit) throws ApiException {
        String key = "search:" + q + ":" + limit;
        List<AnimeItem> hit = cache.get(key, TTL_LIST);
        if (hit != null) return hit;
        Map<String, String> p = new LinkedHashMap<>();
        p.put("q", q);
        p.put("limit", String.valueOf(limit));
        List<AnimeItem> data = unwrap(api.list(p));
        cache.put(key, data);
        return data;
    }

    public List<AnimeItem> byIds(List<Integer> ids) throws ApiException {
        StringBuilder sb = new StringBuilder();
        for (int i : ids) {
            if (sb.length() > 0) sb.append(",");
            sb.append(i);
        }
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ids", sb.toString());
        return unwrap(api.list(p));
    }

    public List<ScheduleItem> schedule() throws ApiException {
        List<ScheduleItem> hit = cache.get("schedule", TTL_SCHEDULE);
        if (hit != null) return hit;
        List<ScheduleItem> data = unwrap(api.schedule());
        cache.put("schedule", data);
        return data;
    }

    public GenresData genres() throws ApiException {
        GenresData hit = cache.get("genres", TTL_GENRES);
        if (hit != null) return hit;
        GenresData data = unwrap(api.genres());
        cache.put("genres", data);
        return data;
    }

    public AnimeFull anime(String slugOrId) throws ApiException {
        String key = "anime:" + slugOrId;
        AnimeFull hit = cache.get(key, TTL_DETAIL);
        if (hit != null) return hit;
        AnimeFull data = unwrap(api.anime(slugOrId, "true"));
        if (data.videos == null || data.videos.isEmpty()) {
            try {
                data.videos = unwrap(api.videos(data.animeId));
            } catch (Exception ignored) {
                data.videos = new ArrayList<>();
            }
        }
        cache.put(key, data);
        return data;
    }

    public List<VideoItem> videos(int animeId) throws ApiException {
        String key = "videos:" + animeId;
        List<VideoItem> hit = cache.get(key, TTL_DETAIL);
        if (hit != null) return hit;
        List<VideoItem> data = unwrap(api.videos(animeId));
        cache.put(key, data);
        return data;
    }
}
