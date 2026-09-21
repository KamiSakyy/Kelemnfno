package ru.kelemnfno.anime.download;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Служебные чтения MPEG-TS: начало потока и длительность по PCR.
 * Нужно, чтобы у скачанного видео были честная длительность и перемотка.
 */
public final class TsProbe {

    private static final int PKT = 188;

    private TsProbe() {
    }

    public static boolean isTs(File f) {
        return f != null && f.getName().toLowerCase(Locale.US).endsWith(".ts");
    }

    /** Смещение первого пакета (синхробайт 0x47, подтверждённый следующими). */
    public static int syncOffset(RandomAccessFile in, long len) throws IOException {
        int probe = (int) Math.min(len, 64 * 1024);
        byte[] head = new byte[probe];
        in.seek(0);
        in.readFully(head);
        for (int i = 0; i + PKT * 5 < probe; i++) {
            if (head[i] != 0x47) continue;
            boolean ok = true;
            for (int k = 1; k < 5; k++) {
                if (head[i + k * PKT] != 0x47) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    /** Длительность .ts в миллисекундах по первой и последней PCR; -1, если не вышло. */
    public static long durationMs(File video) {
        if (!isTs(video)) return -1;
        RandomAccessFile in = null;
        try {
            long len = video.length();
            in = new RandomAccessFile(video, "r");
            int sync = syncOffset(in, len);
            if (sync < 0) return -1;
            long first = firstPcr(in, sync, len);
            long last = lastPcr(in, sync, len);
            if (first < 0 || last < 0) return -1;
            long delta = last - first;
            if (delta < 0) delta += (1L << 33); // обёртывание 33-битного счётчика
            return delta / 90; // PCR 90 кГц -> мс
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static long firstPcr(RandomAccessFile in, int sync, long len) throws IOException {
        byte[] pkt = new byte[PKT];
        long limit = Math.min(len - PKT, 8L * 1024 * 1024);
        for (long pos = sync; pos <= limit; pos += PKT) {
            in.seek(pos);
            in.readFully(pkt);
            long v = pcrOf(pkt);
            if (v >= 0) return v;
        }
        return -1;
    }

    private static long lastPcr(RandomAccessFile in, int sync, long len) throws IOException {
        byte[] pkt = new byte[PKT];
        long window = 8L * 1024 * 1024;
        long start = sync;
        if (len - sync > window) start = sync + ((len - window - sync) / PKT) * PKT;
        long found = -1;
        for (long pos = start; pos + PKT <= len; pos += PKT) {
            in.seek(pos);
            in.readFully(pkt);
            long v = pcrOf(pkt);
            if (v >= 0) found = v;
        }
        return found;
    }

    /** PCR base (90 кГц) из пакета, если у него есть адаптационное поле с PCR. */
    private static long pcrOf(byte[] p) {
        if (p[0] != 0x47) return -1;
        int afc = (p[3] >> 4) & 0x3;
        if (afc != 2 && afc != 3) return -1;
        int afLen = p[4] & 0xFF;
        if (afLen < 7) return -1;
        if ((p[5] & 0x10) == 0) return -1;
        return ((long) (p[6] & 0xFF) << 25)
                | ((long) (p[7] & 0xFF) << 17)
                | ((long) (p[8] & 0xFF) << 9)
                | ((long) (p[9] & 0xFF) << 1)
                | ((p[10] >> 7) & 0x1);
    }
}
