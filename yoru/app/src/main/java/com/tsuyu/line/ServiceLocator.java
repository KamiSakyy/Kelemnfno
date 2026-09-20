package com.tsuyu.line;

public final class ServiceLocator {
    private static volatile ServiceLocator instance;
    private final YoruApp app;

    private ServiceLocator(YoruApp app) {
        this.app = app;
    }

    public static void init(YoruApp app) {
        instance = new ServiceLocator(app);
    }

    public static ServiceLocator get() {
        return instance;
    }

    public ApiRepository api() {
        return app.api;
    }

    public SecureStore store() {
        return app.store;
    }

    public YoruCache cache() {
        return app.cache;
    }

    public ImageLoader images() {
        return app.images;
    }

    public TrafficMeter traffic() {
        return app.traffic;
    }

    public MediaCache mediaCache() {
        return app.mediaCache;
    }

    public DownloadHub downloads() {
        return app.downloads();
    }
}
