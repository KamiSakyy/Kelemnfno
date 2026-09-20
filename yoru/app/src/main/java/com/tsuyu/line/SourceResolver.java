package com.tsuyu.line;

import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class SourceResolver {
    private static String publicToken = "";
    private static long tokenAt;
    private static final long TOKEN_TTL = 4L * 60L * 60L * 1000L;

    private SourceResolver() {}

    private static String readString(File f) {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[512];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeString(File f, String s) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    public static synchronized void invalidateToken() {
        publicToken = "";
        tokenAt = 0;
        try {
            YoruApp app = YoruApp.app();
            if (app != null && app.getCacheDir() != null) {
                new File(app.getCacheDir(), "kodik_tok.dat").delete();
            }
        } catch (Exception ignored) {}
    }

    public static void prewarm(ApiRepository repo) {
        YoruApp app = YoruApp.app();
        if (app == null || app.discovery == null) return;
        app.discovery.execute(() -> {
            try {
                token(repo);
            } catch (Exception ignored) {}
        });
    }

    public static synchronized String token(ApiRepository repo) throws Exception {
        if (!publicToken.isEmpty() && System.currentTimeMillis() - tokenAt < TOKEN_TTL) return publicToken;
        YoruApp app = YoruApp.app();
        if (app != null && app.getCacheDir() != null) {
            File f = new File(app.getCacheDir(), "kodik_tok.dat");
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < TOKEN_TTL) {
                String saved = readString(f).trim();
                if (saved.length() >= 16 && saved.length() <= 120) {
                    publicToken = saved;
                    tokenAt = f.lastModified();
                    return publicToken;
                }
            }
        }
        String script = repo.request("https://kodik-add.com/add-players.min.js?v=2", "GET", null, false);
        String[] patterns = {
                "token\\s*=\\s*[\"\\\']([A-Za-z0-9]{16,120})[\"\\\']",
                "[\"\\\']token[\"\\\']\\s*:\\s*[\"\\\']([A-Za-z0-9]{16,120})[\"\\\']",
                "token=\\\\?[\"\\\']([A-Za-z0-9]{16,120})[\"\\\']"
        };
        for (String pattern : patterns) {
            Matcher m = Pattern.compile(pattern).matcher(script);
            if (m.find()) {
                publicToken = m.group(1);
                tokenAt = System.currentTimeMillis();
                if (app != null && app.getCacheDir() != null) {
                    writeString(new File(app.getCacheDir(), "kodik_tok.dat"), publicToken);
                }
                return publicToken;
            }
        }
        throw new IOException("Просмотр временно недоступен");
    }

    public static String directKodik(int mal, ApiRepository repo) throws Exception {
        if (mal <= 0) throw new IOException("Не удалось найти аниме в этом каталоге");
        String q = repo.query("https://kodik-api.com/get-player", repo.params("title", "Player", "hasPlayer", "false", "url", "https://kodikdb.com/find-player?shikimoriID=" + mal, "token", token(repo), "shikimoriID", String.valueOf(mal)));
        JSONObject j = repo.get(q);
        if (j.has("error") || !j.optBoolean("found")) {
            invalidateToken();
            String q2 = repo.query("https://kodik-api.com/get-player", repo.params("title", "Player", "hasPlayer", "false", "url", "https://kodikdb.com/find-player?shikimoriID=" + mal, "token", token(repo), "shikimoriID", String.valueOf(mal)));
            j = repo.get(q2);
            if (j.has("error") || !j.optBoolean("found")) throw new IOException("У этого каталога пока нет просмотра");
        }
        if (j.has("allowed") && j.optInt("allowed", 1) == 0) throw new IOException("Просмотр временно ограничен");
        String u = embed(j.optString("link"));
        if (u.isEmpty()) throw new IOException("Просмотр временно недоступен");
        return u;
    }

    public static Anime kodikShell(Anime base) {
        Anime a = new Anime();
        a.source = "kodik";
        a.id = String.valueOf(base.malId > 0 ? base.malId : base.id.matches("\\d+") ? Integer.parseInt(base.id) : (int) (1L + (Integer.toUnsignedLong(base.key().hashCode()) % 999999999L)));
        a.title = base.title;
        a.original = base.original;
        a.alias = base.alias;
        a.poster = base.poster;
        a.description = base.description;
        a.year = base.year;
        a.type = base.type;
        a.status = base.status;
        a.age = base.age;
        a.episodes = base.episodes;
        a.malId = base.malId > 0 ? base.malId : CalendarApi.parseInt(base.id);
        a.anilistId = base.anilistId;
        a.kpId = base.kpId;
        a.libriaAlias = base.libriaAlias;
        a.score = base.score;
        a.trailerUrl = base.trailerUrl;
        a.airedDate = base.airedDate;
        a.screenshots.addAll(base.screenshots);
        a.genres.addAll(base.genres);
        return a;
    }

    public static Anime kodikDetails(Anime base, boolean episodes, ApiRepository repo) throws Exception {
        Anime shell = kodikShell(base);
        if (!episodes && (shell.description.isEmpty() || shell.poster.isEmpty())) {
            try {
                Anime sh = new Anime();
                sh.source = "shikimori";
                sh.id = String.valueOf(shell.malId > 0 ? shell.malId : CalendarApi.parseInt(shell.id));
                sh.title = shell.title;
                sh.original = shell.original;
                Anime full = repo.details(sh, false);
                shell = kodikShell(full);
            } catch (Exception ignored) {}
        }
        if (episodes) {
            int mal = shell.malId > 0 ? shell.malId : CalendarApi.parseInt(shell.id);
            String url = directKodik(mal, repo);
            int count = Math.max(1, Math.min(250, shell.episodes > 0 ? shell.episodes : (shell.episodesAired > 0 ? shell.episodesAired : (base.episodes > 0 ? base.episodes : (base.episodesAired > 0 ? base.episodesAired : 12)))));
            for (int i = 1; i <= count; i++) {
                Anime.Episode ep = new Anime.Episode();
                ep.id = shell.id + "-" + i;
                ep.number = i;
                ep.name = "";
                ep.lazy = "kodik";
                ep.resolverUrl = Uri.parse(url).buildUpon().appendQueryParameter("episode", String.valueOf(i)).build().toString();
                ep.variants.add(new Anime.Variant("", "Tsuyu", ep.resolverUrl));
                shell.episodeList.add(ep);
            }
        }
        return repo.remember(shell);
    }

    public static String embed(String input) {
        if (input == null) return "";
        String s = input.trim().replace("&amp;", "&");
        Matcher html = Pattern.compile("src=[\"']([^\"']+)", Pattern.CASE_INSENSITIVE).matcher(s);
        if (html.find()) s = html.group(1);
        if (s.startsWith("//")) s = "https:" + s;
        try {
            Uri u = Uri.parse(s);
            String host = u.getHost();
            if (host != null && Arrays.asList("aniqit.com", "kodik.info", "kodik.cc", "kodik.biz").contains(host)) {
                s = u.buildUpon().scheme("https").authority("kodikplayer.com").build().toString();
            }
            return ApiRepository.safeUrl(s);
        } catch (Exception e) {
            return "";
        }
    }
}
