package ru.kelemnfno.anime.download;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Разбор манифеста DASH (MPD) в плоский список адресов сегментов.
 * Первым всегда идёт init-сегмент: склеенные init + media дают обычный
 * фрагментированный mp4, который плеер читает как локальный файл.
 *
 * Поддерживаются SegmentTemplate (с SegmentTimeline и без) и SegmentList.
 */
final class DashParser {

    private static final class Rep {
        String id = "";
        int bandwidth;
        int height;
        String init;
        String media;
        long startNumber = 1;
        long segDuration;
        long timescale = 1;
        final List<long[]> timeline = new ArrayList<>();
        final List<String> listUrls = new ArrayList<>();
        String listInit;
        String base;
        boolean video;
    }

    private DashParser() {
    }

    static boolean looksLikeDash(String url, String body) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.contains(".mpd")) return true;
        return body != null && body.contains("<MPD");
    }

    /** Адреса сегментов выбранного качества; init-сегмент первым. Пусто, если MPD не разобран. */
    static List<String> segments(String mpd, String baseUrl, int wantedQuality) {
        List<Rep> reps = new ArrayList<>();
        double totalSeconds = 0;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(mpd));

            String mpdBase = null;
            String periodBase = null;
            String setBase = null;
            String repBase = null;
            boolean videoSet = false;
            Rep setTemplate = new Rep();
            Rep current = null;
            boolean inTimeline = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("MPD".equals(tag)) {
                        totalSeconds = parseDuration(attr(parser, "mediaPresentationDuration"));
                    } else if ("BaseURL".equals(tag)) {
                        String text = text(parser);
                        if (current != null) repBase = text;
                        else if (setTemplate.video || videoSet) setBase = text;
                        else if (periodBase == null && mpdBase != null) periodBase = text;
                        else mpdBase = text;
                    } else if ("Period".equals(tag)) {
                        if (totalSeconds <= 0) totalSeconds = parseDuration(attr(parser, "duration"));
                    } else if ("AdaptationSet".equals(tag)) {
                        setTemplate = new Rep();
                        String mime = attr(parser, "mimeType");
                        String content = attr(parser, "contentType");
                        videoSet = (mime != null && mime.startsWith("video/"))
                                || "video".equals(content);
                        setTemplate.video = videoSet;
                        setTemplate.timescale = longOr(attr(parser, "timescale"), 1);
                    } else if ("SegmentTemplate".equals(tag)) {
                        Rep target = current != null ? current : setTemplate;
                        String init = attr(parser, "initialization");
                        String media = attr(parser, "media");
                        if (init != null) target.init = init;
                        if (media != null) target.media = media;
                        target.startNumber = longOr(attr(parser, "startNumber"), target.startNumber);
                        target.segDuration = longOr(attr(parser, "duration"), target.segDuration);
                        target.timescale = longOr(attr(parser, "timescale"), target.timescale);
                    } else if ("SegmentTimeline".equals(tag)) {
                        inTimeline = true;
                    } else if ("S".equals(tag) && inTimeline) {
                        Rep target = current != null ? current : setTemplate;
                        long d = longOr(attr(parser, "d"), 0);
                        long r = longOr(attr(parser, "r"), 0);
                        long t = longOr(attr(parser, "t"), -1);
                        for (long i = 0; i <= r; i++) target.timeline.add(new long[]{t, d});
                    } else if ("SegmentList".equals(tag)) {
                        if (current == null) current = new Rep();
                    } else if ("Initialization".equals(tag)) {
                        String source = attr(parser, "sourceURL");
                        if (source != null) {
                            if (current != null) current.listInit = source;
                            else setTemplate.listInit = source;
                        }
                    } else if ("SegmentURL".equals(tag)) {
                        String media = attr(parser, "media");
                        if (media != null) {
                            (current != null ? current : setTemplate).listUrls.add(media);
                        }
                    } else if ("Representation".equals(tag)) {
                        current = new Rep();
                        current.id = str(attr(parser, "id"));
                        current.bandwidth = (int) longOr(attr(parser, "bandwidth"), 0);
                        current.height = (int) longOr(attr(parser, "height"), 0);
                        current.video = videoSet;
                        current.init = setTemplate.init;
                        current.media = setTemplate.media;
                        current.startNumber = setTemplate.startNumber;
                        current.segDuration = setTemplate.segDuration;
                        current.timescale = setTemplate.timescale;
                        current.listInit = setTemplate.listInit;
                        current.listUrls.addAll(setTemplate.listUrls);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = parser.getName();
                    if ("Representation".equals(tag) && current != null) {
                        current.base = firstOf(repBase, setBase, periodBase, mpdBase);
                        reps.add(current);
                        current = null;
                        repBase = null;
                    } else if ("SegmentTimeline".equals(tag)) {
                        inTimeline = false;
                    } else if ("AdaptationSet".equals(tag)) {
                        setBase = null;
                    } else if ("Period".equals(tag)) {
                        periodBase = null;
                    }
                }
                event = parser.next();
            }
        } catch (Throwable t) {
            return new ArrayList<>();
        }

        Rep chosen = pick(reps, wantedQuality);
        if (chosen == null) return new ArrayList<>();
        return build(chosen, baseUrl, totalSeconds);
    }

    private static Rep pick(List<Rep> reps, int wantedQuality) {
        Rep best = null;
        for (Rep r : reps) {
            if (!r.video) continue;
            if (best == null) {
                best = r;
                continue;
            }
            if (wantedQuality <= 0) {
                if (r.height > best.height) best = r;
                continue;
            }
            if (Math.abs(r.height - wantedQuality) < Math.abs(best.height - wantedQuality)) best = r;
        }
        if (best == null && !reps.isEmpty()) best = reps.get(0);
        return best;
    }

    private static List<String> build(Rep rep, String baseUrl, double totalSeconds) {
        List<String> out = new ArrayList<>();
        String base = rep.base == null || rep.base.isEmpty() ? baseUrl : rep.base;

        String init = rep.init != null ? rep.init : rep.listInit;
        if (init != null && !init.isEmpty()) {
            out.add(fill(resolve(base, init), rep, rep.startNumber, 0));
        }

        if (!rep.listUrls.isEmpty()) {
            long number = rep.startNumber;
            for (String url : rep.listUrls) {
                out.add(fill(resolve(base, url), rep, number++, 0));
            }
            return out;
        }

        if (rep.media == null || rep.media.isEmpty()) return new ArrayList<>();

        if (!rep.timeline.isEmpty()) {
            long number = rep.startNumber;
            long time = 0;
            for (long[] step : rep.timeline) {
                if (step[0] >= 0) time = step[0];
                out.add(fill(resolve(base, rep.media), rep, number++, time));
                time += step[1];
            }
            return out;
        }

        if (rep.segDuration > 0 && totalSeconds > 0) {
            double secondsPerSegment = (double) rep.segDuration / Math.max(1, rep.timescale);
            int count = (int) Math.ceil(totalSeconds / secondsPerSegment);
            long number = rep.startNumber;
            for (int i = 0; i < count && i < 20000; i++) {
                out.add(fill(resolve(base, rep.media), rep, number++,
                        (long) (i * (double) rep.segDuration)));
            }
        }
        return out;
    }

    /** Подстановка шаблонов $RepresentationID$, $Bandwidth$, $Number%05d$, $Time$. */
    private static String fill(String url, Rep rep, long number, long time) {
        String out = url;
        out = out.replace("$RepresentationID$", rep.id);
        out = out.replace("$Bandwidth$", String.valueOf(rep.bandwidth));
        out = out.replace("$Number$", String.valueOf(number));
        out = out.replace("$Time$", String.valueOf(time));
        int dollar = out.indexOf("$Number%");
        while (dollar >= 0) {
            int end = out.indexOf("d$", dollar);
            if (end < 0) break;
            String spec = out.substring(dollar + 8, end);
            String formatted;
            try {
                formatted = String.format("%0" + spec + "d", number);
            } catch (Throwable t) {
                formatted = String.valueOf(number);
            }
            out = out.substring(0, dollar) + formatted + out.substring(end + 2);
            dollar = out.indexOf("$Number%");
        }
        return out;
    }

    private static String resolve(String base, String url) {
        if (url == null) return "";
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        if (base == null || base.isEmpty()) return url;
        try {
            return new java.net.URI(base).resolve(url).toString();
        } catch (Throwable t) {
            int slash = base.lastIndexOf('/');
            return (slash > 8 ? base.substring(0, slash + 1) : base) + url;
        }
    }

    private static String firstOf(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private static String str(String value) {
        return value == null ? "" : value;
    }

    private static long longOr(String value, long fallback) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        return parser.getAttributeValue(null, name);
    }

    private static String text(XmlPullParser parser) {
        try {
            return parser.nextText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** PT1H2M3.5S → секунды. */
    static double parseDuration(String value) {
        if (value == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?"
                            + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?")
                    .matcher(value.trim());
            if (!m.matches()) return Double.parseDouble(value.trim());
            double days = num(m.group(3)) + num(m.group(2)) * 30 + num(m.group(1)) * 365;
            return days * 86400 + num(m.group(4)) * 3600 + num(m.group(5)) * 60 + num(m.group(6));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double num(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Double.parseDouble(value);
        } catch (Throwable t) {
            return 0;
        }
    }
}
