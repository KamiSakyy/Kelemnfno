package ru.kelemnfno.anime.data.repo;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * Дисковый кэш ответов API.
 * Экран рисуется из него мгновенно — даже до первого запроса и без сети,
 * а свежие данные подтягиваются фоном и подменяют картинку, если изменились.
 */
public final class DiskCache {

    private static final long MAX_BYTES = 8L * 1024L * 1024L;

    private final File dir;

    public DiskCache(Context context) {
        dir = new File(context.getCacheDir(), "api");
        if (!dir.isDirectory()) dir.mkdirs();
    }

    /** Содержимое без проверки срока; null, если записи нет. */
    public synchronized String get(String key) {
        File file = fileFor(key);
        if (!file.isFile()) return null;
        try {
            byte[] raw = read(file);
            int nl = indexOf(raw);
            if (nl < 0) return null;
            return new String(raw, nl + 1, raw.length - nl - 1, Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Сколько миллисекунд назад записано; -1, если записи нет. */
    public synchronized long age(String key) {
        File file = fileFor(key);
        if (!file.isFile()) return -1L;
        try {
            byte[] raw = read(file);
            int nl = indexOf(raw);
            if (nl < 0) return -1L;
            long at = Long.parseLong(new String(raw, 0, nl, Charset.forName("UTF-8")).trim());
            return System.currentTimeMillis() - at;
        } catch (Throwable t) {
            return -1L;
        }
    }

    public synchronized void put(String key, String value) {
        if (value == null) return;
        File file = fileFor(key);
        Writer out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream(file), Charset.forName("UTF-8"));
            out.write(Long.toString(System.currentTimeMillis()));
            out.write('\n');
            out.write(value);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
        trim();
    }

    public synchronized void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    private void trim() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_BYTES) return;
        java.util.Arrays.sort(files, (a, b2) -> Long.compare(a.lastModified(), b2.lastModified()));
        for (File f : files) {
            if (total <= MAX_BYTES) break;
            total -= f.length();
            f.delete();
        }
    }

    private static void close(Writer out) {
        if (out == null) return;
        try {
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private static int indexOf(byte[] raw) {
        for (int i = 0; i < raw.length; i++) if (raw[i] == '\n') return i;
        return -1;
    }

    private static byte[] read(File file) throws java.io.IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buffer = new byte[(int) file.length()];
            int done = 0;
            while (done < buffer.length) {
                int n = in.read(buffer, done, buffer.length - done);
                if (n < 0) break;
                done += n;
            }
            return buffer;
        } finally {
            in.close();
        }
    }

    private File fileFor(String key) {
        return new File(dir, hash(key) + ".json");
    }

    private static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] sum = digest.digest(key.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder();
            for (byte b : sum) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Throwable t) {
            return Integer.toHexString(key.hashCode());
        }
    }
}
