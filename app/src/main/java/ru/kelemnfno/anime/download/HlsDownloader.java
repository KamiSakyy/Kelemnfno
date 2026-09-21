package ru.kelemnfno.anime.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ru.kelemnfno.anime.data.resolver.Net;
import ru.kelemnfno.anime.data.resolver.SourceUtil;

/**
 * Настоящее скачивание: HLS качается сегментами (4 потока) и склеивается в один файл,
 * MP4 — одним потоком с прогрессом. Поддержаны AES-128 и докачка после обрыва.
 * Порт src/lib/downloader.ts.
 */
public final class HlsDownloader {

    private static final int CONCURRENCY = 4;
    private static final Pattern RESOLUTION = Pattern.compile("RESOLUTION=\\d+x(\\d+)");
    private static final Pattern BANDWIDTH = Pattern.compile("BANDWIDTH=(\\d+)");
    private static final Pattern METHOD = Pattern.compile("METHOD=([A-Z0-9-]+)");
    private static final Pattern KEY_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern KEY_IV = Pattern.compile("IV=0[xX]([0-9a-fA-F]+)");

    private HlsDownloader() {
    }

    public interface Listener {
        void onProgress(int percent, long loadedBytes, int segmentsDone, int segmentsTotal);
    }

    public static class Cancel {
        private final AtomicBoolean value = new AtomicBoolean(false);

        public void cancel() {
            value.set(true);
        }

        public boolean isCancelled() {
            return value.get();
        }
    }

    public static class Result {
        public File file;
        public long bytes;
        public int segments;
    }

    private static class Segment {
        final String url;
        final int index;
        String keyUri;
        String keyIv;
        int sequence;
        float dur;

        Segment(String url, int index) {
            this.url = url;
            this.index = index;
        }
    }

    /* ---------------- Точка входа ---------------- */

    public static Result download(OkHttpClient client, String url, String referer, int wantedQuality,
                                  File target, Cancel cancel, Listener listener) throws IOException {
        File parts = new File(target.getParentFile(), ".parts_" + target.getName());
        if (!parts.exists() && !parts.mkdirs()) throw new IOException("Не удалось создать папку загрузки");

        if (isMp4(url)) {
            Result r = new Result();
            r.file = target;
            r.bytes = downloadFile(client, url, referer, target, cancel, listener, 1, 1);
            r.segments = 1;
            cleanup(parts);
            return r;
        }

        String playlistUrl = url;
        String listText = fetchText(client, url, referer);
        if (listText.contains("#EXT-X-STREAM-INF")) {
            String picked = pickVariant(listText, url, wantedQuality);
            if (!picked.isEmpty()) {
                playlistUrl = picked;
                listText = fetchText(client, playlistUrl, referer);
            }
        }

        List<Segment> segments;
        if (DashParser.looksLikeDash(playlistUrl, listText)) {
            // DASH: манифест разбирается в плоский список, init-сегмент первым.
            List<String> urls = DashParser.segments(listText, playlistUrl, wantedQuality);
            segments = new ArrayList<>();
            for (int i = 0; i < urls.size(); i++) segments.add(new Segment(urls.get(i), i));
            if (segments.isEmpty()) throw new IOException("В манифесте нет сегментов");
        } else {
            segments = parsePlaylist(listText, playlistUrl);
        }
        if (segments.isEmpty()) throw new IOException("В плейлисте нет сегментов");

        int total = segments.size();
        final AtomicInteger done = new AtomicInteger(0);
        final AtomicLong bytes = new AtomicLong(0);
        for (File f : safeList(parts)) if (f.getName().endsWith(".ts")) bytes.addAndGet(f.length());

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<?>> futures = new ArrayList<>();
        for (final Segment seg : segments) {
            futures.add(pool.submit((java.util.concurrent.Callable<Void>) () -> {
                if (cancel.isCancelled()) return null;
                File out = new File(parts, String.format("seg_%06d.ts", seg.index));
                if (out.exists() && out.length() > 0) {
                    report(listener, total, done, bytes);
                    return null;
                }
                byte[] data = fetchBytes(client, seg.url, referer);
                if (cancel.isCancelled()) return null;
                if (seg.keyUri != null && !"NONE".equals(seg.keyUri)) {
                    data = decrypt(client, seg, referer, data);
                }
                File tmp = new File(parts, out.getName() + ".tmp");
                write(tmp, data);
                if (!tmp.renameTo(out)) throw new IOException("Не удалось сохранить сегмент");
                bytes.addAndGet(data.length);
                report(listener, total, done, bytes);
                return null;
            }));
        }

        IOException failure = null;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                if (failure == null) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    failure = cause instanceof IOException ? (IOException) cause : new IOException(cause.getMessage());
                }
            }
        }
        pool.shutdownNow();

        if (cancel.isCancelled()) throw new IOException("cancelled");
        if (failure != null) throw failure;

        long written = merge(parts, target, total);
        // Сегменты не удаляем: локальный .m3u8 по ним даёт плееру длительность
        // и честную перемотку скачанного видео (слитый .ts индекса не имеет).
        writeLocalPlaylist(parts, target, segments);
        Result r = new Result();
        r.file = target;
        r.bytes = written;
        r.segments = total;
        if (listener != null) listener.onProgress(100, written, total, total);
        return r;
    }

    private static void report(Listener listener, int total, AtomicInteger done, AtomicLong bytes) {
        int finished = done.incrementAndGet();
        if (listener != null) {
            listener.onProgress(Math.min(99, Math.round(finished * 100f / Math.max(1, total))),
                    bytes.get(), finished, total);
        }
    }

    private static boolean isMp4(String url) {
        String lower = url == null ? "" : url.toLowerCase();
        return lower.contains(".mp4?") || lower.endsWith(".mp4");
    }

    /* ---------------- Плейлист ---------------- */

    /**
     * Все варианты мастер-плейлиста: высота и адрес.
     * По ним строится честный список качеств, включая 360p и ниже.
     */
    public static List<String[]> variants(String master, String baseUrl) {
        List<String[]> out = new ArrayList<>();
        if (master == null) return out;
        String[] lines = master.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            int height = 0;
            Matcher m = RESOLUTION.matcher(l);
            if (m.find()) height = Integer.parseInt(m.group(1));
            if (height == 0) {
                Matcher bw = BANDWIDTH.matcher(l);
                if (bw.find()) height = Math.round(Integer.parseInt(bw.group(1)) / 1500f);
            }
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                out.add(new String[]{String.valueOf(height), SourceUtil.absolute(baseUrl, next)});
                break;
            }
        }
        return out;
    }

    /** Выбор варианта мастер-плейлиста под запрошенное качество. */
    static String pickVariant(String master, String baseUrl, int wantedQuality) {
        String[] lines = master.split("\\r?\\n");
        String bestUrl = "";
        int bestHeight = 0;
        int bestDistance = Integer.MAX_VALUE;
        String firstUrl = "";
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
            int height = 0;
            Matcher m = RESOLUTION.matcher(l);
            if (m.find()) height = Integer.parseInt(m.group(1));
            if (height == 0) {
                Matcher b = BANDWIDTH.matcher(l);
                if (b.find()) height = Math.round(Integer.parseInt(b.group(1)) / 1500f);
            }
            String candidate = "";
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.isEmpty() || next.startsWith("#")) continue;
                candidate = SourceUtil.absolute(baseUrl, next);
                break;
            }
            if (candidate.isEmpty()) continue;
            if (firstUrl.isEmpty()) firstUrl = candidate;
            int distance = Math.abs(height - wantedQuality);
            if (bestUrl.isEmpty() || distance < bestDistance || (distance == bestDistance && height > bestHeight)) {
                bestUrl = candidate;
                bestHeight = height;
                bestDistance = distance;
            }
        }
        return bestUrl.isEmpty() ? firstUrl : bestUrl;
    }

    /** Медиа-плейлист → сегменты + параметры шифрования. */
    static List<Segment> parsePlaylist(String text, String baseUrl) {
        List<Segment> out = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        String keyUri = null;
        String keyIv = null;
        int sequence = 0;
        Matcher seqMatcher = Pattern.compile("#EXT-X-MEDIA-SEQUENCE:(\\d+)").matcher(text);
        if (seqMatcher.find()) sequence = Integer.parseInt(seqMatcher.group(1));

        int index = 0;
        float pendingDur = 0;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.startsWith("#EXT-X-KEY")) {
                Matcher m = METHOD.matcher(l);
                String method = m.find() ? m.group(1) : "NONE";
                if ("NONE".equals(method)) {
                    keyUri = null;
                    keyIv = null;
                } else if ("AES-128".equals(method)) {
                    Matcher u = KEY_URI.matcher(l);
                    keyUri = u.find() ? SourceUtil.absolute(baseUrl, u.group(1)) : null;
                    Matcher iv = KEY_IV.matcher(l);
                    keyIv = iv.find() ? iv.group(1) : null;
                } else {
                    keyUri = method; // SAMPLE-AES и прочие — скачивание невозможно
                }
                continue;
            }
            if (l.startsWith("#EXTINF:")) {
                try {
                    pendingDur = Float.parseFloat(l.substring(8).split(",")[0].trim());
                } catch (Throwable t) {
                    pendingDur = 0;
                }
                continue;
            }
            if (l.isEmpty() || l.startsWith("#")) continue;
            Segment seg = new Segment(SourceUtil.absolute(baseUrl, l), index);
            seg.keyUri = keyUri;
            seg.keyIv = keyIv;
            seg.sequence = sequence + index;
            seg.dur = pendingDur;
            pendingDur = 0;
            out.add(seg);
            index++;
        }
        return out;
    }

    /* ---------------- Расшифровка ---------------- */

    private static byte[] decrypt(OkHttpClient client, Segment seg, String referer, byte[] data) throws IOException {
        if (seg.keyUri == null) return data;
        if (!"NONE".equals(seg.keyUri) && !seg.keyUri.toLowerCase().startsWith("http")) {
            throw new IOException("Поток зашифрован (" + seg.keyUri + ") — скачивание невозможно");
        }
        byte[] key = fetchBytes(client, seg.keyUri, referer);
        byte[] iv;
        if (seg.keyIv != null && seg.keyIv.length() >= 32) {
            iv = hexToBytes(seg.keyIv.substring(seg.keyIv.length() - 32));
        } else {
            iv = new byte[16];
            long s = seg.sequence;
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) ((s >> (8 * i)) & 0xff);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось расшифровать сегмент", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    /* ---------------- Сеть ---------------- */

    static String fetchText(OkHttpClient client, String url, String referer) throws IOException {
        Request.Builder b = new Request.Builder().url(url)
                .header("User-Agent", Net.CHROME)
                .header("Accept", "*/*");
        if (referer != null && !referer.isEmpty()) {
            b.header("Referer", referer);
            b.header("Origin", originOf(referer));
        }
        try (Response r = client.newCall(b.build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code() + " при чтении плейлиста");
            return r.body() == null ? "" : r.body().string();
        }
    }

    static byte[] fetchBytes(OkHttpClient client, String url, String referer) throws IOException {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", Net.CHROME);
        if (referer != null && !referer.isEmpty()) b.header("Referer", referer);
        try (Response r = client.newCall(b.build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code() + " сегмент");
            return r.body() == null ? new byte[0] : r.body().bytes();
        }
    }

    /** MP4/файл целиком — потоком, без загрузки в память. */
    static long downloadFile(OkHttpClient client, String url, String referer, File target, Cancel cancel,
                             Listener listener, int done, int total) throws IOException {
        Request.Builder b = new Request.Builder().url(url).header("User-Agent", Net.CHROME);
        if (referer != null && !referer.isEmpty()) b.header("Referer", referer);
        long size = 0;
        try (Response r = client.newCall(b.build()).execute()) {
            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
            long contentLength = r.body() == null ? -1 : r.body().contentLength();
            InputStream in = r.body() == null ? null : r.body().byteStream();
            if (in == null) throw new IOException("Пустой поток");
            OutputStream out = new FileOutputStream(target);
            byte[] buf = new byte[64 * 1024];
            int read;
            long lastReport = 0;
            while ((read = in.read(buf)) > 0) {
                if (cancel.isCancelled()) {
                    out.close();
                    throw new IOException("cancelled");
                }
                out.write(buf, 0, read);
                size += read;
                long now = System.currentTimeMillis();
                if (listener != null && now - lastReport > 250) {
                    lastReport = now;
                    int percent = contentLength > 0 ? Math.min(99, (int) (size * 100 / contentLength)) : 0;
                    listener.onProgress(percent, size, done, total);
                }
            }
            out.flush();
            out.close();
        }
        return size;
    }

    /* ---------------- Файлы ---------------- */

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    /** Локальный плейлист по скачанным сегментам: файл лежит рядом как «<имя>.m3u8». */
    private static void writeLocalPlaylist(File parts, File target, List<Segment> segments) {
        try {
            List<Segment> sorted = new ArrayList<>(segments);
            sorted.sort((a, b) -> Integer.compare(a.index, b.index));
            StringBuilder sb = new StringBuilder();
            sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:15\n");
            for (Segment s : sorted) {
                File f = new File(parts, String.format("seg_%06d.ts", s.index));
                if (!f.exists() || f.length() == 0) continue;
                sb.append("#EXTINF:")
                        .append(s.dur > 0 ? String.format(java.util.Locale.US, "%.3f", s.dur) : "6.000")
                        .append(",\nfile://")
                        .append(f.getAbsolutePath()).append('\n');
            }
            sb.append("#EXT-X-ENDLIST\n");
            java.io.Writer w = new java.io.FileWriter(new File(target.getAbsolutePath() + ".m3u8"));
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static long merge(File parts, File target, int total) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
            raf.setLength(0);
            for (int i = 0; i < total; i++) {
                File seg = new File(parts, String.format("seg_%06d.ts", i));
                if (!seg.exists()) throw new IOException("Потерян сегмент " + (i + 1));
                try (InputStream in = new java.io.FileInputStream(seg)) {
                    byte[] buf = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buf)) > 0) raf.write(buf, 0, read);
                }
            }
            return raf.length();
        }
    }

    private static void cleanup(File parts) {
        for (File f : safeList(parts)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        parts.delete();
    }

    private static File[] safeList(File dir) {
        File[] files = dir.listFiles();
        return files == null ? new File[0] : files;
    }

    private static String originOf(String referer) {
        try {
            java.net.URL u = new java.net.URL(referer);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** Доступ к списку сегментов (используется для оценки размера). */
    public static int segmentCount(String playlistText, String baseUrl) {
        return parsePlaylist(playlistText, baseUrl).size();
    }

    /** Заголовки источника (нужны плееру и загрузчику). */
    public static Map<String, String> headersFor(String referer) {
        return Net.baseHeaders(originOf(referer), referer);
    }
}
