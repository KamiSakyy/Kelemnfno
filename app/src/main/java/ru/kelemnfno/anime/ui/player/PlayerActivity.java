package ru.kelemnfno.anime.ui.player;

import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.db.AppDatabase;
import ru.kelemnfno.anime.data.db.HistoryEntity;
import ru.kelemnfno.anime.data.model.AppSettings;
import ru.kelemnfno.anime.data.model.StreamSource;
import ru.kelemnfno.anime.data.model.Track;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.data.tsuyu.TsuyuEngine;
import ru.kelemnfno.anime.databinding.ActivityPlayerBinding;
import ru.kelemnfno.anime.player.PlaybackService;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.util.AppExecutors;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/**
 * Собственный плеер на Media3/ExoPlayer: перехват и разрешение потоков,
 * жесты, смена озвучки и качества, PiP, автопереход к следующей серии.
 */
@UnstableApi
public class PlayerActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_SLUG = "slug";
    public static final String EXTRA_ANIME_ID = "anime_id";
    public static final String EXTRA_POSTER = "poster";
    public static final String EXTRA_TRACK_ID = "track_id";
    public static final String EXTRA_EPISODE = "episode";
    public static final String EXTRA_VOICE = "voice";
    public static final String EXTRA_TRACKS = "tracks";
    public static final String EXTRA_FILE = "file";

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final long AUTO_HIDE_MS = 2600L;
    private static final long OPENING_SKIP_MS = 90_000L;

    private ActivityPlayerBinding b;
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String title = "";
    private String slug = "";
    private int animeId;
    private String poster = "";
    private String trackId = "";
    private String voice = "";
    private int episode = 1;
    private int total;
    private List<Track> tracks = new ArrayList<>();
    private final List<StreamSource> sources = new ArrayList<>();
    private int quality;
    private boolean locked;
    private boolean autoNext;
    private boolean gestures = true;
    private boolean nextShown;
    private int errorAttempts;
    private long savedPosition;

    private AudioManager audio;
    private int maxVolume = 15;

    public static void start(Context context, String title, String slug, int animeId, String poster,
                             String trackId, int episode, String voice, List<Track> tracks) {
        Intent intent = new Intent(context, PlayerActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SLUG, slug)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_POSTER, poster)
                .putExtra(EXTRA_TRACK_ID, trackId)
                .putExtra(EXTRA_EPISODE, episode)
                .putExtra(EXTRA_VOICE, voice)
                .putExtra(EXTRA_TRACKS, (Serializable) new ArrayList<>(tracks));
        context.startActivity(intent);
    }

    /** Запуск локального файла из загрузок. */
    public static void startFile(Context context, String title, String path) {
        context.startActivity(new Intent(context, PlayerActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FILE, path));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityPlayerBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        title = str(EXTRA_TITLE);
        slug = str(EXTRA_SLUG);
        animeId = getIntent().getIntExtra(EXTRA_ANIME_ID, 0);
        poster = str(EXTRA_POSTER);
        trackId = str(EXTRA_TRACK_ID);
        voice = str(EXTRA_VOICE);
        episode = Math.max(1, getIntent().getIntExtra(EXTRA_EPISODE, 1));
        Serializable raw = getIntent().getSerializableExtra(EXTRA_TRACKS);
        if (raw instanceof ArrayList) {
            for (Object o : (ArrayList<?>) raw) if (o instanceof Track) tracks.add((Track) o);
        }
        total = maxEpisode();

        AppSettings settings = Prefs.get(this).settings();
        autoNext = settings.autoNext;
        gestures = settings.gestures;
        quality = settings.downloadQuality;

        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) maxVolume = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));

        b.ctrlTitle.setText(title);
        b.ctrlSubtitle.setText(episodeLabel());
        b.nextText.setText("Следующая серия");
        b.playerView.setUseController(false);
        b.ctrlSkip.setVisibility(settings.skipOpening ? View.VISIBLE : View.GONE);
        wireControls();
        wireGestures();
        applyFullscreen(true);

        connect();
    }

    private String str(String key) {
        String v = getIntent().getStringExtra(key);
        return v == null ? "" : v;
    }

    private int maxEpisode() {
        int max = episode;
        for (Track t : tracks) {
            if (t.id.equals(trackId)) {
                for (int e : t.episodes) max = Math.max(max, e);
                return max;
            }
        }
        return max;
    }

    private String episodeLabel() {
        return "Серия " + episode + (total > 0 ? " из " + total : "")
                + (voice.isEmpty() ? "" : " · " + voice);
    }

    /* ---------------- Подключение к медиа-сервису ---------------- */

    private void connect() {
        SessionToken token = PlaybackService.token(this);
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
            } catch (Exception e) {
                showError("Не удалось запустить плеер");
                return;
            }
            controller.addListener(playerListener);
            b.playerView.setPlayer(controller);
            controller.setPlaybackSpeed(Prefs.get(PlayerActivity.this).settings().speed);
            String file = str(EXTRA_FILE);
            if (!file.isEmpty()) {
                StreamSource local = new StreamSource();
                local.url = file;
                local.kind = "mp4";
                local.voice = "Локально";
                local.label = "Файл";
                apply(local);
            } else {
                resolve(episode, quality, false);
            }
        }, MoreExecutors.directExecutor());
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            b.buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            updatePlayIcon();
            if (state == Player.STATE_ENDED) {
                saveProgress();
                if (autoNext && episode < total) showNext(true);
            }
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayIcon();
            if (isPlaying) {
                errorAttempts = 0;
                b.errorPanel.setVisibility(View.GONE);
                scheduleHide();
            } else {
                handler.removeCallbacks(hideControls);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            handleError(error.getMessage());
        }
    };

    /* ---------------- Разрешение потока ---------------- */

    /** Перехват: по дорожке и серии получаем прямые потоки и выбираем качество. */
    private void resolve(final int ep, final int wantedQuality, final boolean retry) {
        b.buffering.setVisibility(View.VISIBLE);
        b.errorPanel.setVisibility(View.GONE);
        AppExecutors.get().heavy().execute(() -> {
            List<StreamSource> found;
            String message = null;
            try {
                found = TsuyuEngine.streams(trackId, ep, retry);
            } catch (Throwable t) {
                found = new ArrayList<>();
                message = t.getMessage() == null ? "Поток недоступен" : t.getMessage();
            }
            final List<StreamSource> result = found;
            final String error = message;
            AppExecutors.get().post(() -> {
                sources.clear();
                sources.addAll(result);
                if (sources.isEmpty()) {
                    handleError(error == null ? "Поток недоступен" : error);
                    return;
                }
                StreamSource picked = pick(wantedQuality);
                quality = picked.quality > 0 ? picked.quality : quality;
                apply(picked);
            });
        });
    }

    private StreamSource pick(int wanted) {
        StreamSource best = sources.get(0);
        for (StreamSource s : sources) {
            if (s.quality == wanted) return s;
            if (Math.abs(s.quality - wanted) < Math.abs(best.quality - wanted)) best = s;
        }
        return best;
    }

    private void apply(StreamSource source) {
        if (controller == null) return;
        long keep = controller.getCurrentPosition();
        PlaybackService.applyHeaders(source.referer, null);
        MediaItem item = new MediaItem.Builder()
                .setUri(source.url)
                .setMediaId(slug + ":" + episode)
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title + " — серия " + episode)
                        .setArtist(voice.isEmpty() ? source.voice : voice)
                        .build())
                .build();
        b.buffering.setVisibility(View.VISIBLE);
        controller.setMediaItem(item, keep > 3000 ? keep : 0);
        controller.prepare();
        controller.play();
        b.ctrlSubtitle.setText(episodeLabel()
                + (source.label == null || source.label.isEmpty() ? "" : " · " + source.label));
        b.ctrlQuality.setText(source.quality > 0 ? source.quality + "p" : "Авто");
        saveHistory();
    }

    private void handleError(String message) {
        b.buffering.setVisibility(View.GONE);
        errorAttempts++;
        if (errorAttempts <= 2 && !str(EXTRA_FILE).isEmpty()) {
            // локальный файл — повторять нечего
        } else if (errorAttempts <= 3) {
            handler.postDelayed(() -> resolve(episode, quality, errorAttempts > 1), 800L * errorAttempts);
            return;
        }
        b.errorPanel.setVisibility(View.VISIBLE);
        b.errorText.setText(message == null ? "Не удалось воспроизвести" : message);
    }

    /** «Другой источник» — следующая дорожка озвучки. */
    private void otherSource() {
        b.errorPanel.setVisibility(View.GONE);
        if (tracks.size() < 2) {
            Ui.toast(this, "Больше источников нет");
            return;
        }
        int index = 0;
        for (int i = 0; i < tracks.size(); i++) if (tracks.get(i).id.equals(trackId)) index = i;
        Track next = tracks.get((index + 1) % tracks.size());
        trackId = next.id;
        voice = next.voice;
        errorAttempts = 0;
        b.ctrlVoice.setText(next.voice);
        resolve(episode, quality, true);
    }

    /* ---------------- Управление ---------------- */

    private void wireControls() {
        b.ctrlBack.setOnClickListener(v -> finish());
        b.ctrlPlay.setOnClickListener(v -> togglePlay());
        b.ctrlBack10.setOnClickListener(v -> seekRelative(-10_000));
        b.ctrlForward10.setOnClickListener(v -> seekRelative(10_000));
        b.ctrlPrev.setOnClickListener(v -> changeEpisode(episode - 1));
        b.ctrlNext.setOnClickListener(v -> changeEpisode(episode + 1));
        b.controls.setOnClickListener(v -> {
        });
        b.playerView.setOnClickListener(v -> toggleControls());
        b.ctrlLock.setOnClickListener(v -> {
            locked = !locked;
            b.ctrlLock.setImageResource(locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
            if (locked) hideNow();
            else showControls();
            Ui.toast(this, locked ? "Управление заблокировано" : "Управление разблокировано");
        });
        b.ctrlPip.setOnClickListener(v -> enterPip());
        b.ctrlVoice.setOnClickListener(v -> voiceSheet());
        b.ctrlQuality.setOnClickListener(v -> qualitySheet());
        b.ctrlSpeed.setOnClickListener(v -> speedSheet());
        b.ctrlSkip.setOnClickListener(v -> seekRelative(OPENING_SKIP_MS));
        b.ctrlEpisodes.setOnClickListener(v -> episodesSheet());
        b.nextNow.setOnClickListener(v -> changeEpisode(episode + 1));
        b.errorOtherSource.setOnClickListener(v -> otherSource());
        b.errorRetry.setOnClickListener(v -> {
            errorAttempts = 0;
            resolve(episode, quality, true);
        });

        b.ctrlSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) b.ctrlPosition.setText(Fmt.clock(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                dragging = false;
                if (controller != null) controller.seekTo(seekBar.getProgress());
                scheduleHide();
            }
        });
    }

    private void togglePlay() {
        if (controller == null) return;
        if (controller.isPlaying()) controller.pause();
        else controller.play();
        showControls();
    }

    private void seekRelative(long delta) {
        if (controller == null) return;
        controller.seekTo(Math.max(0, controller.getCurrentPosition() + delta));
        showControls();
    }

    private void changeEpisode(int ep) {
        if (ep < 1 || (total > 0 && ep > total)) {
            Ui.toast(this, ep < 1 ? "Это первая серия" : "Это последняя серия");
            return;
        }
        saveProgress();
        episode = ep;
        nextShown = false;
        b.nextCard.setVisibility(View.GONE);
        b.ctrlSubtitle.setText(episodeLabel());
        saveHistory();
        String file = str(EXTRA_FILE);
        if (!file.isEmpty()) {
            finish();
            return;
        }
        resolve(episode, quality, false);
    }

    private void updatePlayIcon() {
        boolean playing = controller != null && controller.isPlaying();
        b.ctrlPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    /* ---------------- Панели управления ---------------- */

    private final Runnable hideControls = this::hideNow;

    private void showControls() {
        if (locked) return;
        b.controls.setVisibility(View.VISIBLE);
        b.controls.setAlpha(1f);
        handler.removeCallbacks(hideControls);
        if (controller != null && controller.isPlaying()) scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, AUTO_HIDE_MS);
    }

    private void hideNow() {
        b.controls.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            b.controls.setVisibility(View.GONE);
            b.controls.setAlpha(1f);
        }).start();
    }

    private void toggleControls() {
        if (b.controls.getVisibility() == View.VISIBLE) hideNow();
        else showControls();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (controller != null && !isFinishing()) {
                long position = controller.getCurrentPosition();
                long duration = Math.max(0, controller.getDuration());
                b.ctrlPosition.setText(Fmt.clock(position));
                b.ctrlDuration.setText(duration > 0 ? Fmt.clock(duration) : "--:--");
                if (duration > 0) {
                    b.ctrlSeek.setMax((int) duration);
                    if (!b.ctrlSeek.isPressed()) b.ctrlSeek.setProgress((int) position);
                    long left = duration - position;
                    if (left < 25_000 && left > 0 && !nextShown && autoNext && episode < total) {
                        showNext(false);
                    }
                    if (b.ctrlSkip.getVisibility() == View.VISIBLE && position > 180_000) {
                        b.ctrlSkip.setVisibility(View.GONE);
                    }
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    private void showNext(boolean immediate) {
        nextShown = true;
        b.nextCard.setVisibility(View.VISIBLE);
        b.nextText.setText("Серия " + (episode + 1) + " через несколько секунд");
        if (immediate) {
            handler.postDelayed(() -> changeEpisode(episode + 1), 1200);
        }
        Ui.fadeIn(b.nextCard, 200);
    }

    /* ---------------- Жесты ---------------- */

    private void wireGestures() {
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                toggleControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (!gestures || locked) return false;
                int w = b.playerView.getWidth();
                if (e.getX() < w / 3f) seekRelative(-10_000);
                else if (e.getX() > w * 2f / 3f) seekRelative(10_000);
                else togglePlay();
                return true;
            }

            @Override
            public boolean onScroll(@NonNull MotionEvent e1, @NonNull MotionEvent e2,
                                    float distanceX, float distanceY) {
                if (!gestures || locked || e1 == null) return false;
                float delta = (e1.getY() - e2.getY()) / b.playerView.getHeight();
                if (Math.abs(delta) < 0.01f) return false;
                if (e1.getX() < b.playerView.getWidth() / 2f) adjustBrightness(delta);
                else adjustVolume(delta);
                return true;
            }
        });

        b.playerView.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return true;
        });
    }

    private float brightnessBase = -1f;

    private void adjustBrightness(float delta) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (brightnessBase < 0) brightnessBase = lp.screenBrightness < 0 ? 0.5f : lp.screenBrightness;
        brightnessBase = Math.min(1f, Math.max(0.02f, brightnessBase + delta));
        lp.screenBrightness = brightnessBase;
        getWindow().setAttributes(lp);
        indicator(b.brightnessPanel, b.brightnessValue, Math.round(brightnessBase * 100) + "%");
    }

    private float volumeBase = -1f;

    private void adjustVolume(float delta) {
        if (audio == null) return;
        if (volumeBase < 0) volumeBase = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeBase = Math.min(maxVolume, Math.max(0, volumeBase + delta * maxVolume));
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(volumeBase), 0);
        indicator(b.volumePanel, b.volumeValue, Math.round(volumeBase / maxVolume * 100) + "%");
    }

    private long indicatorUntil;

    private void indicator(View panel, TextView value, String text) {
        value.setText(text);
        panel.setVisibility(View.VISIBLE);
        indicatorUntil = System.currentTimeMillis() + 700;
        handler.removeCallbacks(hideIndicator);
        handler.postDelayed(hideIndicator, 700);
    }

    private final Runnable hideIndicator = () -> {
        if (System.currentTimeMillis() >= indicatorUntil) {
            b.volumePanel.setVisibility(View.GONE);
            b.brightnessPanel.setVisibility(View.GONE);
        }
    };

    /* ---------------- Листы выбора ---------------- */

    private BottomSheetDialog sheet(String title, View content) {
        BottomSheetDialog dialog = new BottomSheetDialog(this, R.style.Theme_Kelemnfno_BottomSheet);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 24));
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(getColor(R.color.text));
        header.setTextSize(16);
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        dialog.setContentView(root);
        dialog.show();
        return dialog;
    }

    private void voiceSheet() {
        if (tracks.isEmpty()) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final BottomSheetDialog[] holder = new BottomSheetDialog[1];
        for (Track t : tracks) {
            final Track track = t;
            TextView row = new TextView(this);
            row.setText(track.voice + " · " + track.episodes.size() + " сер."
                    + (track.maxQuality > 0 ? " · до " + track.maxQuality + "p" : ""));
            row.setTextColor(getColor(track.id.equals(trackId) ? R.color.accent : R.color.text));
            row.setTextSize(14);
            row.setBackgroundResource(R.drawable.bg_ripple_rounded);
            row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (!track.id.equals(trackId)) {
                    trackId = track.id;
                    voice = track.voice;
                    b.ctrlVoice.setText(track.voice);
                    saveProgress();
                    resolve(episode, quality, false);
                }
            });
            box.addView(row);
        }
        holder[0] = sheet("Озвучка", box);
    }

    private void qualitySheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        com.google.android.flexbox.FlexboxLayout flex = new com.google.android.flexbox.FlexboxLayout(this);
        List<Integer> heights = new ArrayList<>();
        for (StreamSource s : sources) {
            if (s.quality > 0 && !heights.contains(s.quality)) heights.add(s.quality);
        }
        java.util.Collections.sort(heights, java.util.Collections.reverseOrder());
        if (heights.isEmpty()) heights.add(0);
        final BottomSheetDialog[] holder = new BottomSheetDialog[1];
        for (int h : heights) {
            Chips.add(flex, h > 0 ? h + "p" : "Авто", h == quality, v -> {
                quality = h;
                if (holder[0] != null) holder[0].dismiss();
                if (h == 0 || sources.isEmpty()) {
                    resolve(episode, 0, true);
                } else {
                    StreamSource target = null;
                    for (StreamSource s : sources) if (s.quality == h) target = s;
                    if (target != null) apply(target);
                }
            });
        }
        box.addView(flex);
        holder[0] = sheet("Качество", box);
    }

    private void speedSheet() {
        com.google.android.flexbox.FlexboxLayout flex = new com.google.android.flexbox.FlexboxLayout(this);
        final BottomSheetDialog[] holder = new BottomSheetDialog[1];
        float current = controller == null ? 1f : controller.getPlaybackParameters().speed;
        for (float s : SPEEDS) {
            Chips.add(flex, s + "x", Math.abs(s - current) < 0.01f, v -> {
                if (controller != null) controller.setPlaybackSpeed(s);
                if (holder[0] != null) holder[0].dismiss();
            });
        }
        holder[0] = sheet("Скорость", flex);
    }

    private void episodesSheet() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final BottomSheetDialog[] holder = new BottomSheetDialog[1];
        int count = total > 0 ? total : episode;
        for (int i = 1; i <= count; i++) {
            final int ep = i;
            TextView row = new TextView(this);
            row.setText("Серия " + i);
            row.setTextColor(getColor(i == episode ? R.color.accent : R.color.text));
            row.setTextSize(14);
            row.setBackgroundResource(R.drawable.bg_ripple_rounded);
            row.setPadding(0, Ui.dp(this, 11), 0, Ui.dp(this, 11));
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                changeEpisode(ep);
            });
            box.addView(row);
        }
        holder[0] = sheet("Серии", box);
    }

    /* ---------------- История и позиция ---------------- */

    private void saveHistory() {
        if (slug.isEmpty()) return;
        final HistoryEntity entity = new HistoryEntity();
        entity.slug = slug;
        entity.animeId = animeId;
        entity.title = title;
        entity.poster = poster;
        entity.episode = String.valueOf(episode);
        entity.dubbing = voice;
        entity.total = total;
        entity.updatedAt = System.currentTimeMillis();
        AppExecutors.get().io().execute(() -> {
            HistoryEntity old = AppDatabase.get(this).historyDao().bySlug(slug);
            entity.positionMs = old == null ? 0 : old.positionMs;
            entity.durationMs = old == null ? 0 : old.durationMs;
            AppDatabase.get(this).historyDao().upsert(entity);
            AppDatabase.get(this).watchedDao().mark(slug, String.valueOf(episode));
        });
    }

    private void saveProgress() {
        if (controller == null || slug.isEmpty()) return;
        final long position = controller.getCurrentPosition();
        final long duration = controller.getDuration();
        if (position <= 0 || duration <= 0) return;
        savedPosition = position;
        AppExecutors.get().io().execute(() -> {
            HistoryEntity old = AppDatabase.get(this).historyDao().bySlug(slug);
            HistoryEntity entity = old == null ? new HistoryEntity() : old;
            entity.slug = slug;
            entity.animeId = animeId;
            entity.title = title;
            entity.poster = poster;
            entity.episode = String.valueOf(episode);
            entity.dubbing = voice;
            entity.total = total;
            entity.positionMs = position;
            entity.durationMs = duration;
            entity.updatedAt = System.currentTimeMillis();
            AppDatabase.get(this).historyDao().upsert(entity);
            if (duration - position > 60_000) {
                AppDatabase.get(this).watchedDao().mark(slug, String.valueOf(episode));
            }
        });
    }

    /* ---------------- PiP и система ---------------- */

    private void applyFullscreen(boolean on) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), !on);
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(getWindow(), b.getRoot());
        if (on) {
            insets.hide(WindowInsetsCompat.Type.systemBars());
            insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Ui.toast(this, "PiP доступен с Android 8");
            return;
        }
        try {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        } catch (Exception e) {
            Ui.toast(this, "Не удалось включить PiP");
        }
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Prefs.get(this).settings().pipOnLeave && controller != null && controller.isPlaying()) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (inPip) hideNow();
        else showControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        handler.post(ticker);
        if (controller != null) controller.play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
        saveProgress();
        if (controller != null && !isInPictureInPictureMode()) controller.pause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (controller != null) {
            controller.removeListener(playerListener);
            controller.release();
            controller = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        super.onDestroy();
    }

    private void showError(String message) {
        b.errorPanel.setVisibility(View.VISIBLE);
        b.errorText.setText(message);
    }
}
