package ru.kelemnfno.anime.download;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Локальный .m3u8 рядом со скачанным видео.
 * Плейлист с #EXTINF даёт плееру настоящую длительность и честную перемотку
 * даже для старых загрузок, где остался только слитый .ts без индекса:
 * такой файл один раз нарезается на сегменты по границам TS-пакетов.
 */
public final class LocalPlaylist {

    private static final int PKT = 188;
    private static final long CHUNK = 6L * 1024 * 1024; // ~6 МБ на сегмент

    private LocalPlaylist() {
    }

    /** Готовый плейлист для видео; создаёт при отсутствии. Null, если не нужен (mp4). */
    public static File ensure(File video) {
        try {
            if (video == null || !video.exists()) return null;
            File pl = new File(video.getAbsolutePath() + ".m3u8");
            if (pl.exists() && pl.length() > 32) return pl;

            String name = video.getName();
            File parts = new File(video.getParentFile(), ".parts_" + name);
            File[] segs = parts.exists()
                    ? parts.listFiles(f -> f.getName().endsWith(".ts") && f.length() > 0)
                    : null;

            long totalMs = TsProbe.durationMs(video);

            if (segs != null && segs.length > 0) {
                Arrays.sort(segs, Comparator.comparing(File::getName));
                if (totalMs <= 0) totalMs = segs.length * 6000L;
                write(pl, segs, durations(segs, totalMs));
                return pl;
            }

            if (!TsProbe.isTs(video)) return null; // у mp4 свой индекс
            if (totalMs <= 0) return null;
            if (!parts.exists() && !parts.mkdirs()) return null;

            List<File> chunks = split(video, parts);
            if (chunks.isEmpty()) return null;
            write(pl, chunks.toArray(new File[0]),
                    durations(chunks.toArray(new File[0]), totalMs));
            return pl;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Длительности сегментов пропорционально их размеру. */
    private static long[] durations(File[] files, long totalMs) {
        long total = 0;
        for (File f : files) total += f.length();
        long[] out = new long[files.length];
        for (int i = 0; i < files.length; i++) {
            out[i] = total > 0 ? Math.max(500, files[i].length() * totalMs / total) : 6000;
        }
        return out;
    }

    private static void write(File pl, File[] files, long[] durs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:30\n#EXT-X-PLAYLIST-TYPE:VOD\n");
        for (int i = 0; i < files.length; i++) {
            sb.append("#EXTINF:")
                    .append(String.format(Locale.US, "%.3f", durs[i] / 1000f))
                    .append(",\nfile://")
                    .append(files[i].getAbsolutePath()).append('\n');
        }
        sb.append("#EXT-X-ENDLIST\n");
        Writer w = new FileWriter(pl);
        w.write(sb.toString());
        w.close();
    }

    /** Режет .ts на куски ~CHUNK байт строго по границам пакетов 188 байт. */
    private static List<File> split(File video, File parts) throws IOException {
        List<File> out = new ArrayList<>();
        RandomAccessFile in = new RandomAccessFile(video, "r");
        try {
            long len = in.length();
            int sync = TsProbe.syncOffset(in, len);
            if (sync < 0) return out;
            byte[] buf = new byte[PKT * 512];
            long pos = sync;
            int idx = 0;
            while (pos < len) {
                File chunk = new File(parts, String.format(Locale.US, "seg_%06d.ts", idx++));
                OutputStream os = new FileOutputStream(chunk);
                long written = 0;
                try {
                    while (pos < len && written < CHUNK) {
                        int n = (int) Math.min(buf.length, len - pos);
                        in.seek(pos);
                        in.readFully(buf, 0, n);
                        int use = n - (n % PKT);
                        if (use == 0) use = n; // хвост меньше пакета
                        os.write(buf, 0, use);
                        pos += use;
                        written += use;
                        if (use != n) break;
                    }
                } finally {
                    os.close();
                }
                if (chunk.length() > 0) out.add(chunk);
                else if (!chunk.delete()) break;
            }
        } finally {
            in.close();
        }
        return out;
    }
}
