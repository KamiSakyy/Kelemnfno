package com.tsuyu.line;

import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.*;

public final class ClipServer {
    private static final ClipServer INSTANCE = new ClipServer();
    public static ClipServer get() { return INSTANCE; }

    public static final class Clip {
        public final Anime anime;
        public final Anime.Episode episode;
        public final String streamUrl;
        public final long startMs;
        public final long endMs;
        public final int durationSec;
        public final String title;
        public final String timingText;
        public final String id;
        public final String voiceName;
        public int likes;
        public boolean liked;

        public Clip(Anime anime, Anime.Episode episode, String streamUrl, long startMs, long endMs, int durationSec, String title, String timingText, String voiceName) {
            this.anime = anime;
            this.episode = episode;
            this.streamUrl = streamUrl;
            this.startMs = startMs;
            this.endMs = endMs;
            this.durationSec = durationSec;
            this.title = title;
            this.timingText = timingText;
            this.voiceName = voiceName == null ? "" : voiceName;
            this.id = anime.key() + ":" + (episode != null ? episode.number : 1) + ":" + startMs;
            this.likes = 120 + (Math.abs(this.id.hashCode()) % 1500);
            this.liked = false;
        }
    }

    public interface ClipCallback {
        void onClipReady(Clip clip);
        void onClipFailed();
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentLinkedQueue<Clip> queue = new ConcurrentLinkedQueue<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private volatile boolean prefetching = false;
    private volatile String selectedVoice = "";
    private volatile Anime targetAnime = null;
    private volatile Anime.Playback cachedTargetPlayback = null;

    private ClipServer() {}

    public void setVoiceFilter(String voice) {
        this.selectedVoice = voice == null ? "" : voice.trim();
        queue.clear();
        triggerPrefetch();
    }

    public String getVoiceFilter() {
        return selectedVoice;
    }

    public void setTargetAnime(Anime anime) {
        this.targetAnime = anime;
        this.cachedTargetPlayback = null;
        queue.clear();
        triggerPrefetch();
    }

    public Anime getTargetAnime() {
        return targetAnime;
    }

    public void clearQueue() {
        queue.clear();
    }

    public void nextClip(ClipCallback cb) {
        Clip cached = queue.poll();
        if (cached != null) {
            mainHandler.post(() -> cb.onClipReady(cached));
            triggerPrefetch();
            return;
        }
        executor.submit(() -> {
            Clip sliced = sliceRandomClip();
            if (sliced != null) {
                mainHandler.post(() -> cb.onClipReady(sliced));
                triggerPrefetch();
            } else {
                mainHandler.post(cb::onClipFailed);
            }
        });
    }

    public void triggerPrefetch() {
        if (prefetching || queue.size() >= 4) return;
        prefetching = true;
        executor.submit(() -> {
            try {
                while (queue.size() < 4) {
                    Clip c = sliceRandomClip();
                    if (c != null) queue.add(c);
                    else break;
                }
            } finally {
                prefetching = false;
            }
        });
    }

    private Clip sliceRandomClip() {
        String filter = selectedVoice;
        Anime target = targetAnime;
        for (int attempt = 0; attempt < 22; attempt++) {
            try {
                Anime anime;
                Anime.Playback playback;
                if (target != null) {
                    anime = target;
                    if (cachedTargetPlayback == null) {
                        cachedTargetPlayback = YoruApp.app().api.playback(target, "auto");
                    }
                    playback = cachedTargetPlayback;
                } else {
                    anime = pickRandomAnime();
                    if (anime == null) continue;
                    playback = YoruApp.app().api.playback(anime, "auto");
                }

                if (playback == null || playback.video == null || playback.video.episodeList == null || playback.video.episodeList.isEmpty()) {
                    if (target != null && attempt > 2) return null;
                    continue;
                }
                ArrayList<Anime.Episode> validEps = new ArrayList<>();
                for (Anime.Episode ep : playback.video.episodeList) {
                    if (ep != null && !ep.future) validEps.add(ep);
                }
                if (validEps.isEmpty()) continue;

                Anime.Episode chosenEp = validEps.get(random.nextInt(validEps.size()));
                YoruApp.app().api.loadEpisode(chosenEp);

                String streamUrl = null;
                String matchedVoice = "";

                if (filter != null && !filter.isEmpty()) {
                    // Check variants for chosen voice
                    if (!chosenEp.variants.isEmpty()) {
                        for (Anime.Variant v : chosenEp.variants) {
                            String vName = v.name.isEmpty() ? v.label() : v.name;
                            String vTitle = ApiRepository.voiceTitle(vName);
                            if (ApiRepository.voiceMatches(filter, vName) || ApiRepository.voiceMatches(filter, vTitle)) {
                                try {
                                    TreeMap<Integer, String> resolved = YoruApp.app().api.resolveStreams(v.url);
                                    if (resolved != null && !resolved.isEmpty()) {
                                        if (resolved.containsKey(720)) streamUrl = resolved.get(720);
                                        else if (resolved.containsKey(480)) streamUrl = resolved.get(480);
                                        else streamUrl = resolved.firstEntry().getValue();
                                        matchedVoice = vTitle.isEmpty() ? vName : vTitle;
                                        break;
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                    // Check source direct streams if variants didn't have stream
                    if (streamUrl == null) {
                        String srcVoice = ApiRepository.sourceVoice(playback.video.source);
                        if (ApiRepository.voiceMatches(filter, srcVoice) || ApiRepository.voiceMatches(filter, chosenEp.name)) {
                            streamUrl = extractStreamUrl(chosenEp);
                            matchedVoice = filter;
                        }
                    }
                    if (streamUrl == null || streamUrl.isEmpty()) {
                        continue;
                    }
                } else {
                    streamUrl = extractStreamUrl(chosenEp);
                    if (streamUrl == null || streamUrl.isEmpty()) continue;
                    matchedVoice = !chosenEp.variants.isEmpty() ? ApiRepository.voiceTitle(chosenEp.variants.get(0).name) : ApiRepository.sourceVoice(playback.video.source);
                }

                int durationSec = chosenEp.duration > 180 ? chosenEp.duration : 1440;
                int minSec = Math.min(100, Math.max(60, durationSec / 6));
                int maxSec = Math.max(minSec + 40, durationSec - 140);
                int startSec = minSec + (maxSec > minSec ? random.nextInt(maxSec - minSec) : 0);
                int clipLen = 15 + random.nextInt(16); // 15 to 30 seconds
                int endSec = startSec + clipLen;

                long startMs = startSec * 1000L;
                long endMs = endSec * 1000L;
                String timing = Ui.time(startSec) + " - " + Ui.time(endSec) + " (" + clipLen + " сек)";
                String displayTitle = YoruBrain.title(anime);

                return new Clip(anime, chosenEp, streamUrl, startMs, endMs, clipLen, displayTitle, timing, matchedVoice);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String extractStreamUrl(Anime.Episode ep) {
        if (!ep.streams.isEmpty()) {
            if (ep.streams.containsKey(720)) return ep.streams.get(720);
            if (ep.streams.containsKey(480)) return ep.streams.get(480);
            return ep.streams.firstEntry().getValue();
        }
        if (!ep.variants.isEmpty()) {
            for (Anime.Variant v : ep.variants) {
                if (v == null || v.url == null || v.url.isEmpty()) continue;
                try {
                    TreeMap<Integer, String> resolved = YoruApp.app().api.resolveStreams(v.url);
                    if (resolved != null && !resolved.isEmpty()) {
                        if (resolved.containsKey(720)) return resolved.get(720);
                        if (resolved.containsKey(480)) return resolved.get(480);
                        return resolved.firstEntry().getValue();
                    }
                } catch (Exception ignored) {}
            }
        }
        if (ep.resolverUrl != null && !ep.resolverUrl.isEmpty()) {
            try {
                TreeMap<Integer, String> resolved = YoruApp.app().api.resolveStreams(ep.resolverUrl);
                if (resolved != null && !resolved.isEmpty()) {
                    return resolved.firstEntry().getValue();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Anime pickRandomAnime() {
        List<Anime> candidates = new ArrayList<>();
        String filter = selectedVoice;
        if (filter != null && !filter.isEmpty()) {
            String fLower = filter.toLowerCase(Locale.ROOT);
            String sourceKey = "";
            if (fLower.contains("anilibria") || fLower.contains("анилибрия")) sourceKey = "anilibria";
            else if (fLower.contains("animevost") || fLower.contains("анимевост")) sourceKey = "animevost";
            else if (fLower.contains("anidub") || fLower.contains("анидаб")) sourceKey = "anidub";
            else if (fLower.contains("animedia") || fLower.contains("анимедиа")) sourceKey = "animedia";
            if (!sourceKey.isEmpty()) {
                try {
                    int p = 1 + random.nextInt(8);
                    Anime.Page page = YoruApp.app().api.catalog(sourceKey, "", p, new ApiRepository.Filter());
                    if (page != null && !page.items.isEmpty()) candidates.addAll(page.items);
                } catch (Exception ignored) {}
            }
        }
        List<Anime> seed = YoruApp.app().api.seed();
        if (seed != null && !seed.isEmpty()) candidates.addAll(seed);
        List<Anime> favs = YoruApp.app().store.favorites();
        if (favs != null && !favs.isEmpty()) candidates.addAll(favs);
        List<Anime> recent = YoruApp.app().store.recent();
        if (recent != null && !recent.isEmpty()) candidates.addAll(recent);

        if (candidates.size() < 10 || random.nextInt(3) == 0) {
            try {
                int page = 1 + random.nextInt(15);
                ApiRepository.Filter filterObj = new ApiRepository.Filter();
                filterObj.sort = "POPULARITY";
                Anime.Page p = YoruApp.app().api.catalog("shikimori", "", page, filterObj);
                if (p != null && !p.items.isEmpty()) candidates.addAll(p.items);
            } catch (Exception ignored) {}
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }
}
