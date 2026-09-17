package ru.kelemnfno.anime.download;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

import ru.kelemnfno.anime.util.Fmt;

/** Где лежат скачанные серии и как отдать файл наружу. */
public final class DownloadStore {

    private DownloadStore() {
    }

    public static File root(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(context.getFilesDir(), "Movies");
        File dir = new File(base, "Kelemnfno");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File animeDir(Context context, String title) {
        File dir = new File(root(context), Fmt.sanitizeFilename(title));
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return dir;
    }

    public static File fileFor(Context context, String title, String episode, int quality, String kind) {
        String ext = "mp4".equals(kind) ? "mp4" : "ts";
        String name = Fmt.sanitizeFilename(title) + " " + Fmt.episodeTag(episode)
                + (quality > 0 ? " " + quality + "p" : "") + "." + ext;
        return new File(animeDir(context, title), name.trim());
    }

    public static long freeBytes(Context context) {
        try {
            StatFs fs = new StatFs(root(context).getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Копия в системную папку «Загрузки» (Android 10+ без разрешений). */
    public static Uri exportToDownloads(Context context, File file) {
        if (file == null || !file.exists()) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Uri.fromFile(file);
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Downloads.MIME_TYPE, file.getName().endsWith(".mp4") ? "video/mp4" : "video/mp2t");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kelemnfno");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    public static void delete(File file) {
        if (file == null) return;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
