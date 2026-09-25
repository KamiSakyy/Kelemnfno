package ru.kelemnfno.anime.util;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ловит необработанные исключения и сохраняет стектрейс в файл,
 * чтобы ошибку можно было показать пользователю на следующем запуске
 * (logcat на телефоне разработчику недоступен).
 */
public final class CrashGuard {

    private static final String NAME = "last_crash.txt";

    private CrashGuard() {
    }

    public static void install(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    save(context.getApplicationContext(), thread, error);
                } catch (Throwable ignored) {
                    // сам обработчик не должен ронять процесс
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    private static void save(Context context, Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String time = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", new Locale("ru")).format(new Date());
        pw.println("Kelemnfno crash " + time);
        pw.println("thread: " + thread.getName());
        pw.println("android: " + android.os.Build.VERSION.RELEASE + " (SDK "
                + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MODEL);
        pw.println();
        error.printStackTrace(pw);
        pw.flush();
        File file = file(context);
        FileOutputStream out = new FileOutputStream(file, false);
        try {
            OutputStreamWriter writer = new OutputStreamWriter(out, Charset.forName("UTF-8"));
            writer.write(sw.toString());
            writer.flush();
        } finally {
            out.close();
        }
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), NAME);
    }

    /** Читает и сразу удаляет сохранённый отчёт. */
    public static String readAndClear(Context context) {
        File file = file(context);
        if (!file.exists()) return null;
        try {
            byte[] data = new byte[(int) file.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally {
                in.close();
            }
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            String text = new String(data, Charset.forName("UTF-8")).trim();
            return text.isEmpty() ? null : text;
        } catch (Exception e) {
            return null;
        }
    }
}
