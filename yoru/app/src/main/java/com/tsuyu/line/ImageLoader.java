package com.tsuyu.line;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.widget.ImageView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ImageLoader {
    private static final String CHROME = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36";
    private static final long MAX_DISK_SIZE = 75L * 1024L * 1024L; // 75MB disk LRU limit
    private static final long TRIM_TARGET_SIZE = 50L * 1024L * 1024L; // 50MB target after trim

    private final Context context;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            24, 48, 15L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "yoru-image");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );
    private final ConcurrentHashMap<String, ArrayList<ImageView>> waiters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> fixTried = new ConcurrentHashMap<>();
    private final Object waitLock = new Object();
    private final AtomicLong currentDiskUsage = new AtomicLong(-1);
    private volatile int generation;
    private volatile long lastTrim;

    public ImageLoader(Context c) {
        context = c.getApplicationContext();
        pool.allowCoreThreadTimeOut(true);
        deleteOldDiskCache();
        ensureCacheDir();
        initDiskUsage();
    }

    private void initDiskUsage() {
        pool.execute(() -> {
            try {
                File dir = cacheDir();
                File[] files = dir.listFiles();
                long total = 0;
                if (files != null) {
                    for (File f : files) {
                        if (!f.getName().equals(".nomedia")) total += f.length();
                    }
                }
                currentDiskUsage.set(total);
                if (total > MAX_DISK_SIZE) trimDiskLru();
            } catch (Exception ignored) {}
        });
    }

    public void clear() {
        generation++;
        synchronized (waitLock) {
            waiters.clear();
        }
        erase(cacheDir());
        ensureCacheDir();
        currentDiskUsage.set(0);
    }

    public void load(ImageView view, Anime a) {
        if (a != null && (a.poster == null || a.poster.isEmpty()) && a.malId > 0) {
            String fixKey = a.key();
            ApiRepository.posterFix(a, () -> {
                try {
                    if (view != null && fixKey.equals(view.getTag())) {
                        loadInternal(view, a.poster, a.key(), assetFor(a), a);
                    }
                } catch (Exception ignored) {}
            });
        }
        loadInternal(view, a == null ? "" : a.poster, a == null ? "yoru" : a.key(), assetFor(a), a);
    }

    public void load(ImageView view, String url, String fallback) {
        loadInternal(view, url, fallback, "", null);
    }

    private String assetFor(Anime a) {
        return "";
    }

    private void loadInternal(ImageView view, String raw, String fallback, String asset, Anime a) {
        if (view == null) return;
        String url = ApiRepository.safeUrl(raw);
        String key = !url.isEmpty() ? url : (fallback == null || fallback.isEmpty() ? "yoru" : fallback);
        Object oldTag = view.getTag();
        boolean sameKey = key.equals(oldTag);

        // If this exact image key is already loaded on the view, do not reload or flash!
        if (sameKey && view.getDrawable() != null) {
            return;
        }

        view.setTag(key);

        if (!sameKey) {
            // Clear previous image so recycled view does not show stale image from another item,
            // but DO NOT flash ic_tsuyu! The dark card background will show cleanly.
            view.setImageDrawable(null);
        }

        boolean leader = false;
        synchronized (waitLock) {
            ArrayList<ImageView> list = waiters.get(key);
            if (list == null) {
                list = new ArrayList<>();
                waiters.put(key, list);
                leader = true;
            }
            list.add(view);
        }
        if (!leader) return;

        int gen = generation;
        try {
            pool.execute(() -> {
                Bitmap ready = null;
                try {
                    File file = cacheFile(key);
                    if (file.exists()) {
                        Bitmap b = decodeFileFast(file);
                        if (b != null) {
                            file.setLastModified(System.currentTimeMillis());
                            ready = b;
                        } else {
                            long len = file.length();
                            if (file.delete()) currentDiskUsage.addAndGet(-len);
                        }
                    }
                    // Asset posters removed to reduce APK size and conceal sources
                    YoruApp app = YoruApp.app();
                    boolean online = app != null && app.traffic != null && app.traffic.connected();
                    if (ready == null && !url.isEmpty() && online) {
                        boolean hasActive = false;
                        synchronized (waitLock) {
                            ArrayList<ImageView> list = waiters.get(key);
                            if (list != null) {
                                for (ImageView iv : list) {
                                    if (iv != null && key.equals(iv.getTag())) {
                                        hasActive = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (!hasActive) return;

                        byte[] bytes = downloadBytes(url);
                        if (bytes != null && bytes.length >= 64) {
                            saveRaw(file, bytes);
                            ready = decodeBytesFast(bytes);
                        }
                    }
                    if (ready == null && !url.isEmpty() && a != null && a.malId > 0 && online
                            && fixTried.putIfAbsent("fail:" + a.malId, Boolean.TRUE) == null) {
                        try {
                            ApiRepository.posterFixReplace(a, () -> {
                                try {
                                    if (view != null) loadInternal(view, a.poster, a.key(), assetFor(a), a);
                                } catch (Exception ignored) {}
                            });
                        } catch (Exception ignored) {}
                    }
                    if (gen != generation) ready = null;
                } catch (Exception ignored) {
                } finally {
                    ArrayList<ImageView> targets;
                    synchronized (waitLock) {
                        targets = waiters.remove(key);
                    }
                    Bitmap bitmap = ready;
                    YoruApp current = YoruApp.app();
                    if (targets != null && current != null) {
                        current.main.post(() -> {
                            if (gen != generation) return;
                            for (ImageView target : targets) {
                                if (target != null && key.equals(target.getTag())) {
                                    if (bitmap != null) {
                                        target.setImageBitmap(bitmap);
                                    } else {
                                        target.setImageResource(R.drawable.ic_tsuyu);
                                    }
                                }
                            }
                        });
                    }
                }
            });
        } catch (RejectedExecutionException rejected) {
            synchronized (waitLock) {
                waiters.remove(key);
            }
        }
    }

    private static Bitmap decodeFileFast(File file) {
        try {
            if (file == null || !file.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = calculateInSampleSize(o, 420, 640);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inDither = true;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap decodeBytesFast(byte[] data) {
        if (data == null || data.length < 64) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inSampleSize = calculateInSampleSize(o, 420, 640);
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inDither = true;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    private byte[] downloadBytes(String url) {
        for (String candidate : imageCandidates(url)) {
            try {
                return fetchBytes(candidate);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private ArrayList<String> imageCandidates(String url) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String safe = ApiRepository.safeUrl(url);
        if (safe.isEmpty()) return new ArrayList<>();
        out.add(safe);
        YoruApp app = YoruApp.app();
        boolean saveMobile = app != null && app.traffic != null && (app.traffic.mobile() || app.savingMobile());
        if (!saveMobile) {
            String high = highQuality(safe);
            out.add(high);
            String low = safe.toLowerCase(Locale.ROOT);
            if (low.contains("cover.cdnlibs.org") && low.endsWith(".jpg")) {
                out.add(safe.replace("_thumb.jpg", ".jpg"));
            }
            if (low.contains("?size=min")) {
                out.add(safe.replace("?size=min", ""));
                out.add(safe.replace("size=min", "size=orig"));
                out.add(safe.replace("size=min", "size=max"));
            }
        }
        return new ArrayList<>(out);
    }

    private static String highQuality(String safe) {
        String s = safe.replace("_thumb.jpg", ".jpg")
                .replace("/thumbs/", "/")
                .replace("/resized/preview/", "/uploads/")
                .replace("/resized/preview_main/", "/uploads/");
        s = s.replace("?size=min", "").replace("size=min", "size=orig");
        return s;
    }

    private byte[] fetchBytes(String url) throws Exception {
        HashMap<String, String> h = new HashMap<>();
        String referer = refererFor(url);
        if (!referer.isEmpty()) h.put("Referer", referer);
        try {
            byte[] data = Net.bytes(url, h, "image/avif,image/webp,image/apng,image/*,*/*;q=0.8", CHROME, "image/", 2200, 3600, 12 * 1024 * 1024);
            if (data.length < 64) throw new IOException("image");
            return data;
        } catch (Net.HttpCode e) {
            throw new IOException("image");
        }
    }

    private static byte[] readBytes(InputStream in, int limit) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[16384];
            int n;
            while ((n = input.read(bytes)) != -1) {
                if (out.size() + n > limit) break;
                out.write(bytes, 0, n);
            }
            return out.toByteArray();
        }
    }

    private File cacheDir() {
        return new File(context.getCacheDir(), "yoru-image-cache");
    }

    private void ensureCacheDir() {
        try {
            File dir = cacheDir();
            if (!dir.exists()) dir.mkdirs();
            new File(dir, ".nomedia").createNewFile();
        } catch (Exception ignored) {}
    }

    private File cacheFile(String key) {
        String hash = Integer.toHexString(key.hashCode());
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            hash = out.toString();
        } catch (Exception ignored) {}
        return new File(cacheDir(), hash + ".bin");
    }

    private void saveRaw(File file, byte[] raw) {
        try {
            ensureCacheDir();
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                out.write(raw);
            }
            long oldLen = file.exists() ? file.length() : 0;
            if (file.exists()) file.delete();
            if (tmp.renameTo(file)) {
                long added = file.length() - oldLen;
                long total = currentDiskUsage.addAndGet(added);
                if (total > MAX_DISK_SIZE) {
                    trimMaybe();
                }
            }
        } catch (Exception ignored) {}
    }

    private static String refererFor(String url) {
        String host = host(url);
        if (host.contains("cdnlibs.org")) return "https://anilib.me/";
        if (host.contains("shikimori")) return "https://shikimori.one/";
        return originFor(url) + "/";
    }

    private static String originFor(String url) {
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme(), host = u.getHost();
            return scheme == null || host == null ? "" : scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private void trimMaybe() {
        long now = System.currentTimeMillis();
        if (now - lastTrim < 30_000L) return;
        lastTrim = now;
        pool.execute(this::trimDiskLru);
    }

    private void trimDiskLru() {
        try {
            File[] files = cacheDir().listFiles();
            if (files == null || files.length == 0) return;
            long size = 0;
            for (File f : files) {
                if (!f.getName().equals(".nomedia")) size += f.length();
            }
            currentDiskUsage.set(size);
            if (size <= MAX_DISK_SIZE) return;

            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                if (f.getName().equals(".nomedia")) continue;
                long n = f.length();
                if (f.delete()) {
                    size -= n;
                    currentDiskUsage.addAndGet(-n);
                }
                if (size <= TRIM_TARGET_SIZE) break;
            }
        } catch (Exception ignored) {}
    }

    private void deleteOldDiskCache() {
        erase(new File(context.getCacheDir(), "covers"));
        erase(new File(context.getFilesDir(), "yoru-image-cache"));
    }

    private static void erase(File f) {
        try {
            if (f == null || !f.exists()) return;
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) erase(k);
            }
            f.delete();
        } catch (Exception ignored) {}
    }
}
