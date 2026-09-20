package ru.kelemnfno.anime.data.api;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/** Единая точка доступа к API: кэш, таймауты, «человеческий» User-Agent. */
public final class ApiClient {

    public static final String API_BASE = ru.kelemnfno.anime.data.resolver.Cfg.apiBase();
    public static final String STATIC_BASE = ru.kelemnfno.anime.data.resolver.Cfg.staticBase();
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 Kelemnfno/1.2";

    private static volatile RemoteApi api;
    private static volatile OkHttpClient client;

    private ApiClient() {
    }

    public static OkHttpClient http(Context context) {
        if (client == null) {
            synchronized (ApiClient.class) {
                if (client == null) {
                    // HTTP-кэш отключён: текстовые данные всегда свежие из сети.
                    okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
                    dispatcher.setMaxRequestsPerHost(8);
                    client = new OkHttpClient.Builder()
                            .dispatcher(dispatcher)
                            .connectionPool(new okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
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

    /**
     * Прогрев соединения: DNS + TCP + TLS выполняются заранее,
     * поэтому первый экран не платит за рукопожатие.
     */
    public static void preconnect(Context context) {
        try {
            Request request = new Request.Builder().url(API_BASE).head().build();
            try (okhttp3.Response response = http(context).newCall(request).execute()) {
                response.code();
            }
        } catch (Throwable ignored) {
        }
    }

    public static RemoteApi api(Context context) {
        if (api == null) {
            synchronized (ApiClient.class) {
                if (api == null) {
                    api = new Retrofit.Builder()
                            .baseUrl(API_BASE)
                            .client(http(context))
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(RemoteApi.class);
                }
            }
        }
        return api;
    }
}
