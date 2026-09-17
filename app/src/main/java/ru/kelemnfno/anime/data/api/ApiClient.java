package ru.kelemnfno.anime.data.api;

import android.content.Context;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Единая точка доступа к API: кэш, таймауты, «человеческий» User-Agent. */
public final class ApiClient {

    public static final String API_BASE = "https://api.yani.tv/";
    public static final String STATIC_BASE = "https://static.yani.tv";
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 Kelemnfno/1.2";

    private static volatile YummyApi api;
    private static volatile OkHttpClient client;

    private ApiClient() {
    }

    public static OkHttpClient http(Context context) {
        if (client == null) {
            synchronized (ApiClient.class) {
                if (client == null) {
                    Cache cache = new Cache(new File(context.getCacheDir(), "http"), 40L * 1024 * 1024);
                    client = new OkHttpClient.Builder()
                            .cache(cache)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .addInterceptor(chain -> {
                                Request req = chain.request().newBuilder()
                                        .header("User-Agent", UA)
                                        .header("Accept", "application/json, text/plain, */*")
                                        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
                                        .build();
                                return chain.proceed(req);
                            })
                            .build();
                }
            }
        }
        return client;
    }

    public static YummyApi api(Context context) {
        if (api == null) {
            synchronized (ApiClient.class) {
                if (api == null) {
                    api = new Retrofit.Builder()
                            .baseUrl(API_BASE)
                            .client(http(context))
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(YummyApi.class);
                }
            }
        }
        return api;
    }
}
