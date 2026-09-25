package ru.kelemnfno.anime.data.model;

/** Готовый к воспроизведению прямой поток. */
public class StreamSource {
    public int quality;
    public String url = "";
    /** hls — m3u8, mp4 — прямой файл, dash — mpd */
    public String kind = "hls";
    /** Referer, который требует источник. */
    public String referer = "";
    /** Название озвучки (для заголовка плеера). */
    public String voice = "";
    public String label = "";

    public boolean isHls() {
        return !"mp4".equals(kind) && !"dash".equals(kind);
    }
}
