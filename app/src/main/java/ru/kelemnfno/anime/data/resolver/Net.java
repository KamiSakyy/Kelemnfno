package ru.kelemnfno.anime.data.resolver;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Сетевой слой подбора источников: заголовки, таймауты, память-кэш GET-ответов. */
public final class Net {

    public static final String CHROME =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36";
    public static final String ANIX_UA = "Anixart/8.5.2 (Android 13; Pixel 7)";

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded; charset=UTF-8");
    private static final MediaType JSON = MediaType.get("application/json; charset=UTF-8");

    private static final int MEM_LIMIT = 600;
    private static final LinkedHashMap<String, Row> MEMORY = new LinkedHashMap<>();

    private static volatile OkHttpClient client;

    private Net() {
    }

    private static class Row {
        final String text;
        final long at;

        Row(String text, long at) {
            this.text = text;
            this.at = at;
        }
    }

    public static OkHttpClient client() {
        if (client == null) {
            synchronized (Net.class) {
                if (client == null) {
                    okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
                    dispatcher.setMaxRequests(24);
                    dispatcher.setMaxRequestsPerHost(10);
                    client = pinned(new OkHttpClient.Builder())
                            .cache(diskCache())
                            .dispatcher(dispatcher)
                            .connectionPool(new okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
                            .dns(cachedDns())
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .callTimeout(45, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .addNetworkInterceptor(Net::cacheable)
                            .build();
                }
            }
        }
        return client;
    }

    /** Пиннинг сертификата API: подмена сертификата перехватчиком не проходит. */
    private static OkHttpClient.Builder pinned(OkHttpClient.Builder builder) {
        try {
            String pin = Pins.API;
            String ca = Pins.API_CA;
            if ((pin == null || pin.isEmpty()) && (ca == null || ca.isEmpty())) return builder;
            String base = Cfg.apiBase();
            int from = base.indexOf("://");
            int to = base.indexOf('/', from + 3);
            String host = to > from ? base.substring(from + 3, to) : base.substring(from + 3);
            if (host.isEmpty()) return builder;
            okhttp3.CertificatePinner.Builder cb = new okhttp3.CertificatePinner.Builder();
            if (pin != null && !pin.isEmpty()) cb.add(host, pin);
            if (ca != null && !ca.isEmpty()) cb.add(host, ca);
            return builder.certificatePinner(cb.build());
        } catch (Throwable t) {
            return builder;
        }
    }

    /**
     * Прогрев соединения: DNS + TCP + TLS до первого запроса экрана.
     * Стоит несколько сотен байт, зато список открывается без рукопожатий.
     */
    public static void preconnect(String url) {
        try {
            Request request = new Request.Builder().url(url).head().build();
            try (Response response = client().newCall(request).execute()) {
                response.code();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Кэш DNS на 5 минут: повторные запросы не резолвят хост заново. */
    private static okhttp3.Dns cachedDns() {
        return hostname -> {
            long now = System.currentTimeMillis();
            synchronized (DNS) {
                DnsRow hit = DNS.get(hostname);
                if (hit != null && now - hit.at < 5 * 60_000L) return hit.addresses;
            }
            java.util.List<java.net.InetAddress> found = okhttp3.Dns.SYSTEM.lookup(hostname);
            synchronized (DNS) {
                DNS.put(hostname, new DnsRow(found, now));
                if (DNS.size() > 64) DNS.remove(DNS.keySet().iterator().next());
            }
            return found;
        };
    }

    private static final class DnsRow {
        final java.util.List<java.net.InetAddress> addresses;
        final long at;

        DnsRow(java.util.List<java.net.InetAddress> addresses, long at) {
            this.addresses = addresses;
            this.at = at;
        }
    }

    private static final java.util.Map<String, DnsRow> DNS = new java.util.LinkedHashMap<>();

    /**
     * Если сервер не прислал директив кэширования, разрешаем дисковому кэшу
     * хранить ответ 5 минут — повторный вход в приложение не качает заново.
     */
    private static Response cacheable(okhttp3.Interceptor.Chain chain) throws java.io.IOException {
        Response response = chain.proceed(chain.request());
        if (!"GET".equals(chain.request().method())) return response;
        String control = response.header("Cache-Control");
        if (control != null && !control.isEmpty()) return response;
        if (response.header("Expires") != null) return response;
        return response.newBuilder()
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    /** Дисковый кэш ответов: повторные открытия экранов не ходят в сеть. */
    private static okhttp3.Cache diskCache() {
        try {
            java.io.File dir = new java.io.File(
                    ru.kelemnfno.anime.AnimeApp.get().getCacheDir(), "http");
            return new okhttp3.Cache(dir, 50L * 1024L * 1024L);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Map<String, String> baseHeaders(String origin, String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", CHROME);
        h.put("Accept", "*/*");
        h.put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.6");
        if (origin != null && !origin.isEmpty()) h.put("Origin", origin);
        if (referer != null && !referer.isEmpty()) h.put("Referer", referer);
        return h;
    }

    /** GET с память-кэшем. */
    public static String get(String url, Map<String, String> headers) throws IOException {
        return get(url, headers, 15_000);
    }

    public static String get(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        String key = "GET " + url + " " + (headers == null ? "" : headers.toString());
        long now = System.currentTimeMillis();
        synchronized (MEMORY) {
            Row hit = MEMORY.get(key);
            if (hit != null && now - hit.at < ttlFor(url)) return hit.text;
        }
        String text = execute(new Request.Builder().url(url).headers(Headers.of(headers)).get().build(), timeoutMs);
        synchronized (MEMORY) {
            MEMORY.put(key, new Row(text, System.currentTimeMillis()));
            trim();
        }
        return text;
    }

    public static String postForm(String url, String body, Map<String, String> headers) throws IOException {
        return execute(new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(RequestBody.create(body == null ? "" : body, FORM))
                .build(), 20_000);
    }

    public static String postJson(String url, String body, Map<String, String> headers) throws IOException {
        return execute(new Request.Builder()
                .url(url)
                .headers(Headers.of(headers))
                .post(RequestBody.create(body == null ? "" : body, JSON))
                .build(), 20_000);
    }

    private static String execute(Request request, int timeoutMs) throws IOException {
        OkHttpClient c = client().newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
        try (Response response = c.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " " + request.url());
            String body = response.body() == null ? "" : response.body().string();
            if (body.isEmpty()) throw new IOException("Пустой ответ " + request.url());
            return body;
        }
    }

    private static long ttlFor(String url) {
        String v = url.toLowerCase();
        if (v.contains("playlist") || v.contains("ftor") || v.contains("get-player")) return 3 * 60_000L;
        if (v.contains("/episode") || v.contains("videos")) return 5 * 60_000L;
        return 10 * 60_000L;
    }

    private static void trim() {
        if (MEMORY.size() <= MEM_LIMIT) return;
        int excess = MEMORY.size() - MEM_LIMIT;
        java.util.Iterator<String> it = MEMORY.keySet().iterator();
        while (excess-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public static JsonObject getJson(String url, Map<String, String> headers) throws IOException {
        Map<String, String> h = new LinkedHashMap<>(headers == null ? baseHeaders(null, null) : headers);
        h.put("Accept", "application/json, text/plain, */*");
        return parse(get(url, h));
    }

    public static JsonObject postFormJson(String url, Map<String, String> fields, Map<String, String> headers)
            throws IOException {
        Map<String, String> h = new LinkedHashMap<>(headers == null ? baseHeaders(null, null) : headers);
        h.put("Accept", "application/json, text/plain, */*");
        return parse(postForm(url, formBody(fields), h));
    }

    public static JsonObject parse(String text) throws IOException {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            throw new IOException("Не JSON: " + safe(e.getMessage()), e);
        }
    }

    public static String formBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue() == null ? "" : e.getValue()));
        }
        return sb.toString();
    }

    /** Kodik часть полей требует уже в url-encoded виде — повторно кодировать нельзя. */
    public static String formBodyRaw(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (sb.length() > 0) sb.append("&");
            String v = pairs[i + 1] == null ? "" : pairs[i + 1];
            sb.append(pairs[i]).append("=").append(looksEncoded(v) ? v : enc(v));
        }
        return sb.toString();
    }

    public static boolean looksEncoded(String value) {
        return value != null && java.util.regex.Pattern.compile("%[0-9a-fA-F]{2}").matcher(value).find();
    }

    public static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return v;
        }
    }

    public static String query(String base, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("&");
            sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
        }
        if (sb.length() == 0) return base;
        return base + (base.contains("?") ? "&" : "?") + sb;
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    /** okhttp.Headers из Map. */
    static final class Headers {
        static okhttp3.Headers of(Map<String, String> map) {
            okhttp3.Headers.Builder b = new okhttp3.Headers.Builder();
            if (map != null) {
                for (Map.Entry<String, String> e : map.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) b.set(e.getKey(), e.getValue());
                }
            }
            return b.build();
        }
    }
}
