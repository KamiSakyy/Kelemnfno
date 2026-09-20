package com.tsuyu.line;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

public final class Net {
    static final String BG = "yoru-bg";
    static final String FG = "yoru-fg";
    private static final Dispatcher DISPATCHER;
    private static final ConnectionPool POOL = new ConnectionPool(128, 15L, TimeUnit.MINUTES);
    private static final CertificatePinner PINNER = new CertificatePinner.Builder()
            .add("shikimori.one", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("shikimori.one", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.shikimori.one", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.shikimori.one", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("shikimori.me", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("shikimori.me", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.shikimori.me", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.shikimori.me", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("kodik.cc", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("kodik.cc", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.kodik.cc", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.kodik.cc", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("kodik.info", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("kodik.info", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.kodik.info", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.kodik.info", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("anilibria.tv", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("anilibria.tv", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.anilibria.tv", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.anilibria.tv", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("anilibria.top", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("anilibria.top", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .add("*.anilibria.top", "sha256/47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=")
            .add("*.anilibria.top", "sha256/sRHdihwgkaib1P1BgX84DkuKvQGTFsZH4SXqGRkH4Rg=")
            .build();
    private static final OkHttpClient BASE;
    private static final ConcurrentHashMap<String, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> BG_FLAG = new ThreadLocal<>();
    private static volatile String focusScreen = "";
    private static volatile Cache diskCache;

    public static final class FastDns implements Dns {
        private static final ConcurrentHashMap<String, DnsEntry> CACHE = new ConcurrentHashMap<>();
        private static final long TTL = 24L * 60L * 60L * 1000L;
        private static final Object DISK_LOCK = new Object();
        private static volatile boolean diskLoaded;

        static final class DnsEntry {
            final List<InetAddress> addresses;
            final long expiresAt;
            DnsEntry(List<InetAddress> addresses, long expiresAt) {
                this.addresses = addresses;
                this.expiresAt = expiresAt;
            }
        }

        private static void ensureDiskLoaded() {
            if (diskLoaded) return;
            synchronized (DISK_LOCK) {
                if (diskLoaded) return;
                diskLoaded = true;
                try {
                    YoruApp app = YoruApp.app();
                    if (app == null || app.getCacheDir() == null) return;
                    File f = new File(app.getCacheDir(), "dns_cache.properties");
                    if (!f.exists()) return;
                    Properties p = new Properties();
                    try (FileInputStream fis = new FileInputStream(f)) {
                        p.load(fis);
                    }
                    for (String host : p.stringPropertyNames()) {
                        String raw = p.getProperty(host, "");
                        if (raw.isEmpty()) continue;
                        String[] parts = raw.split("\\|", 2);
                        if (parts.length < 2) continue;
                        long exp = 0;
                        try { exp = Long.parseLong(parts[1]); } catch (Exception ignored) {}
                        String[] ips = parts[0].split(",");
                        ArrayList<InetAddress> addrs = new ArrayList<>();
                        for (String ipStr : ips) {
                            ipStr = ipStr.trim();
                            if (ipStr.isEmpty()) continue;
                            try {
                                byte[] bytes = InetAddress.getByName(ipStr).getAddress();
                                addrs.add(InetAddress.getByAddress(host, bytes));
                            } catch (Exception ignored) {}
                        }
                        if (!addrs.isEmpty()) {
                            CACHE.put(host, new DnsEntry(addrs, exp));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        private static void persistToDisk() {
            YoruApp app = YoruApp.app();
            if (app == null || app.discovery == null || app.getCacheDir() == null) return;
            app.discovery.execute(() -> {
                synchronized (DISK_LOCK) {
                    try {
                        File f = new File(app.getCacheDir(), "dns_cache.properties");
                        Properties p = new Properties();
                        for (Map.Entry<String, DnsEntry> entry : CACHE.entrySet()) {
                            StringBuilder sb = new StringBuilder();
                            for (InetAddress addr : entry.getValue().addresses) {
                                if (sb.length() > 0) sb.append(',');
                                sb.append(addr.getHostAddress());
                            }
                            sb.append('|').append(entry.getValue().expiresAt);
                            p.setProperty(entry.getKey(), sb.toString());
                        }
                        try (FileOutputStream fos = new FileOutputStream(f)) {
                            p.store(fos, null);
                        }
                    } catch (Exception ignored) {}
                }
            });
        }

        private static void triggerAsyncRefresh(String hostname) {
            YoruApp app = YoruApp.app();
            if (app != null && app.discovery != null) {
                app.discovery.execute(() -> {
                    try {
                        List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
                        if (!addresses.isEmpty()) {
                            CACHE.put(hostname, new DnsEntry(addresses, System.currentTimeMillis() + TTL));
                            persistToDisk();
                        }
                    } catch (Exception ignored) {}
                });
            }
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            if (hostname == null) throw new UnknownHostException("hostname == null");
            ensureDiskLoaded();
            long now = System.currentTimeMillis();
            DnsEntry entry = CACHE.get(hostname);
            if (entry != null && now < entry.expiresAt) {
                return entry.addresses;
            }
            if (entry != null && !entry.addresses.isEmpty()) {
                triggerAsyncRefresh(hostname);
                return entry.addresses;
            }
            try {
                List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(hostname));
                if (!addresses.isEmpty()) {
                    CACHE.put(hostname, new DnsEntry(addresses, now + TTL));
                    persistToDisk();
                    return addresses;
                }
            } catch (Exception e) {
                if (entry != null && !entry.addresses.isEmpty()) {
                    return entry.addresses;
                }
                if (e instanceof UnknownHostException) throw (UnknownHostException) e;
                UnknownHostException uhe = new UnknownHostException("DNS lookup failed: " + hostname);
                uhe.initCause(e);
                throw uhe;
            }
            throw new UnknownHostException("No address found for " + hostname);
        }

        public static void prewarm(String... hosts) {
            if (hosts == null) return;
            ensureDiskLoaded();
            for (String h : hosts) {
                if (h == null || h.isEmpty()) continue;
                DnsEntry entry = CACHE.get(h);
                if (entry != null && System.currentTimeMillis() < entry.expiresAt) continue;
                YoruApp app = YoruApp.app();
                if (app != null && app.io != null) {
                    app.io.execute(() -> {
                        try {
                            List<InetAddress> addresses = Arrays.asList(InetAddress.getAllByName(h));
                            if (!addresses.isEmpty()) {
                                CACHE.put(h, new DnsEntry(addresses, System.currentTimeMillis() + TTL));
                                persistToDisk();
                            }
                        } catch (Exception ignored) {}
                    });
                }
            }
        }
    }

    public static void prewarmConnections(String... urls) {
        if (urls == null) return;
        YoruApp app = YoruApp.app();
        boolean mobile = app != null && app.traffic != null && (app.traffic.mobile() || app.traffic.metered());
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            if (mobile && !url.contains("kodik") && !url.contains("shiki")) continue;
            try {
                Request req = new Request.Builder().url(url).head().tag(String.class, BG).build();
                BASE.newCall(req).enqueue(new Callback() {
                    @Override public void onFailure(Call call, IOException e) {}
                    @Override public void onResponse(Call call, Response response) {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    private static Cache getCache() {
        if (diskCache == null) {
            synchronized (Net.class) {
                if (diskCache == null) {
                    try {
                        YoruApp app = YoruApp.app();
                        if (app != null && app.getCacheDir() != null) {
                            File dir = new File(app.getCacheDir(), "http-cache");
                            diskCache = new Cache(dir, 50L * 1024L * 1024L);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return diskCache;
    }

    private static final Interceptor REWRITE_RESPONSE_INTERCEPTOR = chain -> {
        Request request = chain.request();
        Response originalResponse = chain.proceed(request);
        if (!"GET".equalsIgnoreCase(request.method()) || !originalResponse.isSuccessful()) {
            return originalResponse;
        }
        String path = request.url().encodedPath();
        if (path.endsWith(".m3u8") || path.endsWith(".ts")) {
            return originalResponse;
        }
        YoruApp app = YoruApp.app();
        boolean mobile = app != null && app.traffic != null && (app.traffic.mobile() || app.traffic.metered());
        String contentType = originalResponse.header("Content-Type");
        if (contentType != null && contentType.startsWith("image/")) {
            return originalResponse.newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Vary")
                    .removeHeader("Cache-Control")
                    .header("Cache-Control", "public, max-age=604800")
                    .build();
        }
        int maxAge = mobile ? 600 : 180;
        return originalResponse.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Vary")
                .removeHeader("Cache-Control")
                .header("Cache-Control", "public, max-age=" + maxAge)
                .build();
    };

    private static final Interceptor OFFLINE_INTERCEPTOR = chain -> {
        Request request = chain.request();
        YoruApp app = YoruApp.app();
        boolean connected = app == null || app.traffic == null || app.traffic.connected();
        if (!connected && "GET".equalsIgnoreCase(request.method())) {
            request = request.newBuilder()
                    .header("Cache-Control", "public, only-if-cached, max-stale=604800")
                    .build();
        }
        return chain.proceed(request);
    };

    static {
        ThreadPoolExecutor dispatcherPool = new ThreadPoolExecutor(
                32, 128, 30L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "yoru-net-disp");
                    t.setDaemon(true);
                    return t;
                }
        );
        DISPATCHER = new Dispatcher(dispatcherPool);
        DISPATCHER.setMaxRequests(512);
        DISPATCHER.setMaxRequestsPerHost(128);
        BASE = new OkHttpClient.Builder()
                .dispatcher(DISPATCHER)
                .connectionPool(POOL)
                .dns(new FastDns())
                .certificatePinner(PINNER)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .addInterceptor(OFFLINE_INTERCEPTOR)
                .addNetworkInterceptor(REWRITE_RESPONSE_INTERCEPTOR)
                .connectTimeout(2000, TimeUnit.MILLISECONDS)
                .readTimeout(4500, TimeUnit.MILLISECONDS)
                .writeTimeout(4500, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }
    private Net() {
    }
    public static final class HttpCode extends IOException {
        public final int code;
        HttpCode(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }
    public static final class Page {
        public String text = "";
        public String cookies = "";
    }
    public static final class Body implements Closeable {
        private final Response response;
        private final InputStream input;
        Body(Response response, InputStream input) {
            this.response = response;
            this.input = input;
        }
        public InputStream input() {
            return input;
        }
        @Override
        public void close() {
            try {
                input.close();
            } catch (Exception ignored) {
            }
            try {
                response.close();
            } catch (Exception ignored) {
            }
        }
    }
    public static void bg(boolean value) {
        if (value) BG_FLAG.set(Boolean.TRUE);
        else BG_FLAG.remove();
    }
    public static boolean isBg() {
        return Boolean.TRUE.equals(BG_FLAG.get());
    }
    public static void runBg(Runnable work) {
        bg(true);
        try {
            work.run();
        } finally {
            bg(false);
        }
    }
    public static String focus() {
        return focusScreen;
    }
    public static void focus(String screen) {
        focusScreen = screen == null ? "" : screen;
        if ("details".equals(focusScreen) || "player".equals(focusScreen)) cancelBackground();
    }
    public static void cancelBackground() {
        try {
            for (okhttp3.Call call : DISPATCHER.queuedCalls())
                if (BG.equals(call.request().tag(String.class))) call.cancel();
        } catch (Exception ignored) {
        }
        try {
            for (okhttp3.Call call : DISPATCHER.runningCalls())
                if (BG.equals(call.request().tag(String.class))) call.cancel();
        } catch (Exception ignored) {
        }
    }
    private static OkHttpClient client(int connectMs, int readMs, boolean follow) {
        String key = connectMs + "|" + readMs + "|" + (follow ? 1 : 0);
        OkHttpClient cached = CLIENTS.get(key);
        Cache c = getCache();
        if (cached != null && (cached.cache() != null || c == null)) return cached;
        OkHttpClient.Builder b = BASE.newBuilder()
                .connectTimeout(Math.max(600, connectMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1000, readMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1000, readMs), TimeUnit.MILLISECONDS)
                .followRedirects(follow)
                .followSslRedirects(follow);
        if (c != null) b.cache(c);
        OkHttpClient built = b.build();
        CLIENTS.put(key, built);
        return built;
    }
    private static RequestBody bodyOf(String body, boolean form) {
        if (body == null) return null;
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return null;
            }
            @Override
            public void writeTo(okio.BufferedSink sink) throws IOException {
                sink.write(bytes);
            }
        };
    }
    private static Request build(String url, String method, String body, boolean form, Map<String, String> headers, String accept, String ua) {
        Request.Builder builder = new Request.Builder().url(url).tag(String.class, isBg() ? BG : FG);
        if (accept != null && !accept.isEmpty()) builder.header("Accept", accept);
        builder.header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
        builder.header("Connection", "keep-alive");
        if (ua != null && !ua.isEmpty()) builder.header("User-Agent", ua);
        if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet())
            if (entry.getKey() != null && entry.getValue() != null) builder.header(entry.getKey(), entry.getValue());
        String verb = method == null || method.isEmpty() ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        if ("GET".equals(verb)) builder.get();
        else if ("HEAD".equals(verb)) builder.head();
        else {
            builder.header("Content-Type", form ? "application/x-www-form-urlencoded; charset=utf-8" : "application/json; charset=utf-8");
            builder.method(verb, bodyOf(body == null ? "" : body, form));
        }
        return builder.build();
    }
    private static String resolve(String base, String location) {
        try {
            return new URL(new URL(base), location).toString();
        } catch (Exception e) {
            return "";
        }
    }
    private static byte[] readCapped(InputStream input, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = input.read(buffer)) != -1) {
            try {
                TaskQueue.check();
            } catch (java.io.InterruptedIOException interrupted) {
                throw interrupted;
            }
            if (out.size() + n > max) throw new IOException("Too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public static String text(String url, String method, String body, boolean form, Map<String, String> headers, String accept, String ua, int connectMs, int readMs, int max) throws IOException {
        String current = url;
        String verb = method;
        String payload = body;
        OkHttpClient http = client(connectMs, readMs, false);
        IOException last = null;
        for (int redirect = 0; redirect < 5; redirect++) {
            TaskQueue.check();
            if (current == null || current.isEmpty()) throw new IOException("Bad address");
            Request request = build(current, verb, payload, form, headers, accept, ua);
            try (Response response = http.newCall(request).execute()) {
                int code = response.code();
                if (code >= 300 && code < 400) {
                    String location = response.header("Location");
                    if (location == null || location.isEmpty()) throw new HttpCode(code);
                    current = resolve(current, location);
                    if (current.isEmpty()) throw new HttpCode(code);
                    if (code != 307 && code != 308) {
                        verb = "GET";
                        payload = null;
                    }
                    continue;
                }
                if (code < 200 || code >= 300) throw new HttpCode(code);
                ResponseBody responseBody = response.body();
                if (responseBody == null) throw new IOException("Empty");
                try (InputStream input = responseBody.byteStream()) {
                    return new String(readCapped(input, max), StandardCharsets.UTF_8);
                }
            } catch (HttpCode e) {
                throw e;
            } catch (IOException e) {
                last = e;
                if (isInterrupted(e)) throw e;
                throw e;
            }
        }
        if (last != null) throw last;
        throw new IOException("No response");
    }
    public static byte[] bytes(String url, Map<String, String> headers, String accept, String ua, String contentPrefix, int connectMs, int readMs, int max) throws IOException {
        TaskQueue.check();
        OkHttpClient http = client(connectMs, readMs, true);
        Request request = build(url, "GET", null, false, headers, accept, ua);
        try (Response response = http.newCall(request).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) throw new HttpCode(code);
            if (contentPrefix != null && !contentPrefix.isEmpty()) {
                String type = response.header("Content-Type");
                String lower = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
                if (!lower.isEmpty() && !lower.startsWith(contentPrefix) && !lower.contains("octet-stream"))
                    throw new IOException("Bad content");
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) throw new IOException("Empty");
            try (InputStream input = responseBody.byteStream()) {
                return readCapped(input, max);
            }
        }
    }
    public static Page page(String url, String referer, String ua, String accept, int connectMs, int readMs, int max) throws IOException {
        TaskQueue.check();
        OkHttpClient http = client(connectMs, readMs, true);
        Request.Builder builder = new Request.Builder().url(url).tag(String.class, isBg() ? BG : FG).get();
        if (accept != null && !accept.isEmpty()) builder.header("Accept", accept);
        builder.header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
        builder.header("Connection", "keep-alive");
        if (ua != null && !ua.isEmpty()) builder.header("User-Agent", ua);
        if (referer != null && !referer.isEmpty()) builder.header("Referer", referer);
        try (Response response = http.newCall(builder.build()).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) throw new HttpCode(code);
            Page result = new Page();
            ResponseBody responseBody = response.body();
            if (responseBody != null) try (InputStream input = responseBody.byteStream()) {
                result.text = new String(readCapped(input, max), StandardCharsets.UTF_8);
            }
            List<String> set = response.headers("Set-Cookie");
            if (set != null) {
                StringBuilder cookies = new StringBuilder();
                for (String row : set) {
                    String one = row.split(";", 2)[0];
                    if (one.isEmpty()) continue;
                    if (cookies.length() > 0) cookies.append("; ");
                    cookies.append(one);
                }
                result.cookies = cookies.toString();
            }
            return result;
        }
    }
    public static Body open(String url, Map<String, String> headers, String accept, String ua, int connectMs, int readMs) throws IOException {
        TaskQueue.check();
        OkHttpClient http = client(connectMs, readMs, true);
        Request request = build(url, "GET", null, false, headers, accept, ua);
        Response response = http.newCall(request).execute();
        int code = response.code();
        if (code < 200 || code >= 300) {
            try {
                response.close();
            } catch (Exception ignored) {
            }
            throw new HttpCode(code);
        }
        ResponseBody responseBody = response.body();
        if (responseBody == null) {
            try {
                response.close();
            } catch (Exception ignored) {
            }
            throw new IOException("Empty");
        }
        return new Body(response, responseBody.byteStream());
    }
    private static boolean isInterrupted(IOException e) {
        if (e instanceof java.io.InterruptedIOException) return true;
        String message = e.getMessage();
        return message != null && (message.contains("Canceled") || message.contains("cancelled") || message.contains("Socket closed"));
    }
}
