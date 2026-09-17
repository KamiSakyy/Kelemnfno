package ru.kelemnfno.anime.player;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionToken;

import java.util.HashMap;
import java.util.Map;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.tsuyu.Net;
import ru.kelemnfno.anime.ui.MainActivity;

/**
 * Фоновый медиа-сервис: держит ExoPlayer, чтобы звук продолжал играть
 * при сворачивании приложения и показывал системное уведомление.
 */
@UnstableApi
public class PlaybackService extends MediaSessionService {

    public static final String EXTRA_REFERER = "ru.kelemnfno.anime.REFERER";
    public static final String EXTRA_UA = "ru.kelemnfno.anime.UA";

    /**
     * Фабрика запросов общая для сервиса и экранов: плеер ставит сюда Referer/User-Agent
     * источника перед каждым запуском потока.
     */
    public static final DefaultHttpDataSource.Factory HTTP = new DefaultHttpDataSource.Factory()
            .setUserAgent(Net.CHROME)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000);

    private ExoPlayer player;
    private MediaSession session;

    public static SessionToken token(Context context) {
        return new SessionToken(context, new android.content.ComponentName(context, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();

        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this, HTTP);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 1_500, 3_000)
                .build();

        player = new ExoPlayer.Builder(this,
                new DefaultRenderersFactory(this).setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(pending)
                .setId("kelemnfno-playback")
                .build();
    }

    /** Устанавливает заголовки перед следующей загрузкой. */
    public static void applyHeaders(@Nullable String referer, @Nullable String userAgent) {
        Map<String, String> props = new HashMap<>();
        props.put("User-Agent", userAgent == null || userAgent.isEmpty() ? Net.CHROME : userAgent);
        if (referer != null && !referer.isEmpty()) props.put("Referer", referer);
        props.put("Origin", originOf(referer));
        HTTP.setDefaultRequestProperties(props);
    }

    private static String originOf(String referer) {
        if (referer == null) return "";
        int i = referer.indexOf("://");
        if (i < 0) return "";
        int j = referer.indexOf('/', i + 3);
        return j < 0 ? referer : referer.substring(0, j);
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    /** Команда «сменить источник»: сервис сам не умеет резолвить, её шлёт плеер. */
    public static MediaItem item(String uri, String title, @Nullable String poster, String tag) {
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(uri)
                .setMediaId(tag)
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtworkUri(poster == null ? null : android.net.Uri.parse(poster))
                        .build());
        return builder.build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (player == null || !player.getPlayWhenReady() || player.getMediaItemCount() == 0) {
            stopSelf();
            return;
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release();
            session = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    /** Иконка уведомления медиа-сессии. */
    public static int notificationIcon() {
        return R.drawable.ic_notification;
    }
}
