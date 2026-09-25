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
import ru.kelemnfno.anime.data.api.RemoteApi;
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

    private final RemoteApi api;
    private final DiskCache disk;
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    private AnimeRepository(Context context) {
        api = ApiClient.api(context);
        disk = new DiskCache(context);
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
        // Кэш памяти удалён: данные всегда свежие из сети.
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
        List<AnimeItem> data = unwrap(api.list(query));
        try {
            disk.put(key, GSON.toJson(data));
        } catch (Throwable ignored) {
        }
        return data;
    }

    /**
     * Кэш без сети: память, затем диск. Экран рисуется мгновенно,
     * свежие данные подтягиваются фоном.
     */
    public List<AnimeItem> listCached(Map<String, String> params) {
        return readItems(listKey(params));
    }

    /** Возраст кэша списка в миллисекундах; -1, если записи нет. */
    public long listAge(Map<String, String> params) {
        return disk.age(listKey(params));
    }

    private List<AnimeItem> readItems(String key) {
        String json = disk.get(key);
        if (json == null) return null;
        try {
            AnimeItem[] items = GSON.fromJson(json, AnimeItem[].class);
            if (items == null || items.length == 0) return null;
            return new ArrayList<>(java.util.Arrays.asList(items));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String listKey(Map<String, String> params) {
        Map<String, String> query = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) query.put(e.getKey(), e.getValue());
            }
        }
        return "list:" + query.toString();
    }

    public List<AnimeItem> search(String q, int limit) throws ApiException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("q", q);
        p.put("limit", String.valueOf(limit));
        return unwrap(api.list(p));
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
        List<ScheduleItem> data = unwrap(api.schedule());
        try {
            disk.put("schedule", GSON.toJson(data));
        } catch (Throwable ignored) {
        }
        return data;
    }

    /** Расписание из кэша, без сети. */
    public List<ScheduleItem> scheduleCached() {
        String json = disk.get("schedule");
        if (json == null) return null;
        try {
            ScheduleItem[] items = GSON.fromJson(json, ScheduleItem[].class);
            if (items == null || items.length == 0) return null;
            return new ArrayList<>(java.util.Arrays.asList(items));
        } catch (Throwable t) {
            return null;
        }
    }

    public GenresData genres() throws ApiException {
        return unwrap(api.genres());
    }

    public AnimeFull anime(String slugOrId) throws ApiException {
        String key = "anime:" + slugOrId;
        AnimeFull data = unwrap(api.anime(slugOrId, "true"));
        if (data.videos == null || data.videos.isEmpty()) {
            try {
                data.videos = unwrap(api.videos(data.animeId));
            } catch (Exception ignored) {
                data.videos = new ArrayList<>();
            }
        }
        try {
            disk.put(key, GSON.toJson(data));
        } catch (Throwable ignored) {
        }
        return data;
    }

    /** Полная карточка из кэша, без сети. */
    public AnimeFull animeCached(String slugOrId) {
        String key = "anime:" + slugOrId;
        String json = disk.get(key);
        if (json == null) return null;
        try {
            AnimeFull full = GSON.fromJson(json, AnimeFull.class);
            return full;
        } catch (Throwable t) {
            return null;
        }
    }

    public List<VideoItem> videos(int animeId) throws ApiException {
        return unwrap(api.videos(animeId));
    }
}
