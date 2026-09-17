package ru.kelemnfno.anime.data.api;

import java.util.List;
import java.util.Map;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.QueryMap;
import ru.kelemnfno.anime.data.model.AnimeFull;
import ru.kelemnfno.anime.data.model.AnimeItem;
import ru.kelemnfno.anime.data.model.ApiEnvelope;
import ru.kelemnfno.anime.data.model.GenresData;
import ru.kelemnfno.anime.data.model.ScheduleItem;
import ru.kelemnfno.anime.data.model.VideoItem;

/** Публичное API Yummy Anime — https://api.yani.tv (Swagger: https://yummy-anime.ru/api/swagger). */
public interface YummyApi {

    @GET("anime/schedule")
    Call<ApiEnvelope<List<ScheduleItem>>> schedule();

    @GET("anime/genres")
    Call<ApiEnvelope<GenresData>> genres();

    @GET("anime/{id}/videos")
    Call<ApiEnvelope<List<VideoItem>>> videos(@Path("id") int id);

    @GET("anime/{slug}")
    Call<ApiEnvelope<AnimeFull>> anime(@Path(value = "slug", encoded = true) String slug,
                                       @Query("need_videos") String needVideos);

    @GET("anime")
    Call<ApiEnvelope<List<AnimeItem>>> list(@QueryMap Map<String, String> params);

    @GET("search")
    Call<ApiEnvelope<List<AnimeItem>>> quickSearch(@Query("q") String q);
}
