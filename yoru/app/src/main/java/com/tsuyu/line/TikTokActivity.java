package com.tsuyu.line;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.view.ScaleGestureDetector;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class TikTokActivity extends Activity {
    private FrameLayout root;
    private PlayerView playerView;
    private RecyclerView recycler;
    private ClipAdapter adapter;
    private LinearLayoutManager layoutManager;
    private PagerSnapHelper snapHelper;
    private ExoPlayer player;
    private final ArrayList<ClipServer.Clip> clips = new ArrayList<>();
    private int currentPosition = -1;
    private boolean autoScroll = false; // By default disabled
    private boolean isMuted = false;
    private boolean isZoomMode = false; // By default NOT expanded (Fit mode)
    private boolean destroyed = false;
    private boolean uiOverlayVisible = true;
    private View topBar;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private ClipHolder currentHolder;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (destroyed || player == null || currentHolder == null) return;
            if (currentPosition >= 0 && currentPosition < clips.size()) {
                ClipServer.Clip clip = clips.get(currentPosition);
                long pos = player.getCurrentPosition();
                long dur = clip.durationSec * 1000L;
                if (dur > 0 && currentHolder.progressBar != null) {
                    int percent = (int) Math.min(100, Math.max(0, (pos * 100L) / dur));
                    currentHolder.progressBar.setProgress(percent);
                }
            }
            progressHandler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Ui.allow(this)) return;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        hideNavigationBar();
        root.post(this::hideNavigationBar);

        View decor = getWindow().getDecorView();
        if (decor != null) {
            decor.setOnSystemUiVisibilityChangeListener(visibility -> {
                if ((visibility & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0) {
                    decor.postDelayed(this::hideNavigationBar, 300);
                }
            });
        }

        initPlayer();
        initRecycler();
        initTopBar();

        loadInitialClips();
    }

    private void initPlayer() {
        DefaultLoadControl control = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 8000, 250, 600)
                .setBackBuffer(2000, false)
                .build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(control)
                .build();

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setResizeMode(isZoomMode ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setPlayer(player);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (currentHolder != null) {
                    currentHolder.buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }
                if (state == Player.STATE_READY) {
                    if (currentHolder != null && currentHolder.poster != null) {
                        currentHolder.poster.setVisibility(View.GONE);
                    }
                }
                if (state == Player.STATE_ENDED) {
                    if (autoScroll && currentPosition + 1 < clips.size()) {
                        recycler.smoothScrollToPosition(currentPosition + 1);
                    } else {
                        player.seekTo(0);
                        player.play();
                    }
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (currentHolder != null && currentHolder.poster != null) {
                    currentHolder.poster.setVisibility(View.GONE);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (currentHolder != null && currentHolder.playPauseCenter != null) {
                    if (!isPlaying) {
                        currentHolder.playPauseCenter.setVisibility(View.VISIBLE);
                        currentHolder.playPauseCenter.setAlpha(1f);
                    } else {
                        currentHolder.playPauseCenter.animate()
                                .alpha(0f)
                                .setDuration(200)
                                .withEndAction(() -> {
                                    if (currentHolder != null && currentHolder.playPauseCenter != null) {
                                        currentHolder.playPauseCenter.setVisibility(View.GONE);
                                    }
                                })
                                .start();
                    }
                }
            }
        });
    }

    private void initRecycler() {
        recycler = new RecyclerView(this);
        layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        recycler.setLayoutManager(layoutManager);
        recycler.setItemViewCacheSize(0);

        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recycler);

        adapter = new ClipAdapter();
        recycler.setAdapter(adapter);

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    hideNavigationBar();
                    checkActiveSnap();
                }
            }
        });

        root.addView(recycler, new FrameLayout.LayoutParams(-1, -1));
    }

    public void setOverlayVisible(boolean visible) {
        if (uiOverlayVisible == visible) return;
        uiOverlayVisible = visible;
        float alpha = visible ? 1f : 0f;
        int vis = visible ? View.VISIBLE : View.GONE;

        if (topBar != null) {
            topBar.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                if (topBar != null) topBar.setVisibility(vis);
            }).start();
        }
        if (currentHolder != null) {
            currentHolder.setOverlayVisible(visible);
        }
    }

    private void showVoiceDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 22));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff121215);
        float r = Ui.dp(this, 20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Ui.dp(this, 1), 0x22ffffff);
        box.setBackground(bg);

        View handle = new View(this);
        handle.setBackground(Ui.shape(0x44ffffff, 3, this));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(this, 14);
        box.addView(handle, hp);

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Выбор озвучки", 18, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));

        Ui.Icon close = new Ui.Icon(this, "close");
        close.color = Color.WHITE;
        close.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        close.setOnClickListener(v -> d.dismiss());
        Ui.press(close);
        head.addView(close, Ui.lp(this, 32, 32));
        box.addView(head);

        Ui.space(box, 4);
        box.addView(Ui.text(this, "Показывать в ленте только аниме с этой озвучкой", 12, Ui.MUTED, false));
        Ui.space(box, 14);

        String[] voices = new String[]{
                "Все озвучки",
                "AniLibria",
                "Dream Cast",
                "StudioBand",
                "SHIZA Project",
                "AnimeVost",
                "AniDUB",
                "AniFilm",
                "AniStar",
                "JAM",
                "Субтитры",
                "Дубляж"
        };
        String current = ClipServer.get().getVoiceFilter();

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = Ui.column(this);

        for (int i = 0; i < voices.length; i++) {
            final String vName = voices[i];
            final String voiceVal = i == 0 ? "" : vName;
            boolean isSelected = i == 0 ? current.isEmpty() : ApiRepository.voiceMatches(current, vName);

            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            row.setBackground(isSelected ? Ui.shape(0x20ffffff, 12, this) : Ui.shape(0x00000000, 12, this));

            TextView label = Ui.text(this, vName, 14, isSelected ? Color.WHITE : 0xffcccccc, isSelected);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));

            if (isSelected) {
                TextView mark = Ui.text(this, "✓", 16, Ui.PURPLE, true);
                row.addView(mark, Ui.lp(this, -2, -2));
            }

            Ui.press(row);
            row.setOnClickListener(v -> {
                d.dismiss();
                onVoiceSelected(voiceVal);
            });

            list.addView(row, Ui.lp(this, -1, -2));
            Ui.space(list, 4);
        }

        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(Ui.dp(this, 360), Ui.dp(this, 50 * voices.length))));

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.65f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int width = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(width, Ui.dp(this, 560)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.setOnDismissListener(di -> hideNavigationBar());
        d.show();
    }

    private void onVoiceSelected(String voice) {
        ClipServer.get().setVoiceFilter(voice);
        clips.clear();
        adapter.notifyDataSetChanged();
        currentPosition = -1;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        initTopBar();
        Ui.toast(this, voice.isEmpty() ? "Все озвучки" : "Озвучка: " + voice);
        loadInitialClips();
    }

    private void showAnimeSearchDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 18), Ui.dp(this, 22));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xff121215);
        float r = Ui.dp(this, 20);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Ui.dp(this, 1), 0x22ffffff);
        box.setBackground(bg);

        View handle = new View(this);
        handle.setBackground(Ui.shape(0x44ffffff, 3, this));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(this, 14);
        box.addView(handle, hp);

        LinearLayout head = Ui.row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "Поиск аниме", 18, Color.WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));

        Ui.Icon close = new Ui.Icon(this, "close");
        close.color = Color.WHITE;
        close.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        close.setOnClickListener(v -> d.dismiss());
        Ui.press(close);
        head.addView(close, Ui.lp(this, 32, 32));
        box.addView(head);

        Ui.space(box, 4);
        box.addView(Ui.text(this, "Лента будет показывать нарезки только из выбранного тайтла", 12, Ui.MUTED, false));
        Ui.space(box, 12);

        Anime currentTarget = ClipServer.get().getTargetAnime();
        if (currentTarget != null) {
            LinearLayout banner = Ui.row(this);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            banner.setBackground(Ui.stroke(0x22ffffff, 12, this));

            TextView currLabel = Ui.text(this, "Сейчас: " + YoruBrain.title(currentTarget), 12, Color.WHITE, true);
            currLabel.setMaxLines(1);
            currLabel.setEllipsize(TextUtils.TruncateAt.END);
            banner.addView(currLabel, new LinearLayout.LayoutParams(0, -2, 1));

            TextView resetBtn = Ui.chip(this, "Сбросить ленту", true, () -> {
                d.dismiss();
                onAnimeSelected(null);
            });
            banner.addView(resetBtn, Ui.lp(this, -2, -2));
            box.addView(banner, Ui.lp(this, -1, -2));
            Ui.space(box, 10);
        }

        LinearLayout searchBox = Ui.row(this);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Ui.stroke(Ui.CARD, 12, this));
        searchBox.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), 0);

        Ui.Icon sIcon = new Ui.Icon(this, "search");
        sIcon.color = Ui.MUTED;
        searchBox.addView(sIcon, Ui.lp(this, 20, 20));

        EditText edit = new EditText(this);
        edit.setTextColor(Color.WHITE);
        edit.setHintTextColor(Ui.MUTED);
        edit.setHint("Найти тайтл...");
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setBackground(null);
        edit.setPadding(Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12));
        searchBox.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));

        Ui.Icon clearIcon = new Ui.Icon(this, "close");
        clearIcon.color = Ui.MUTED;
        clearIcon.setVisibility(View.GONE);
        clearIcon.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        clearIcon.setOnClickListener(v -> edit.setText(""));
        searchBox.addView(clearIcon, Ui.lp(this, 26, 26));

        box.addView(searchBox, Ui.lp(this, -1, Ui.dp(this, 46)));
        Ui.space(box, 12);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout listCol = Ui.column(this);
        scroll.addView(listCol, new LinearLayout.LayoutParams(-1, -2));
        box.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));

        renderAnimeList(listCol, d, getAnimePreviewList(""));

        Handler searchH = new Handler(Looper.getMainLooper());
        final Runnable[] searchTask = new Runnable[1];

        edit.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s == null ? "" : s.toString().trim();
                clearIcon.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (searchTask[0] != null) searchH.removeCallbacks(searchTask[0]);

                searchTask[0] = () -> {
                    List<Anime> local = getAnimePreviewList(q);
                    renderAnimeList(listCol, d, local);

                    if (q.length() >= 2) {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            try {
                                Anime.Page p = YoruApp.app().api.catalog("yoru", q, 1, null);
                                if (p != null && !p.items.isEmpty()) {
                                    searchH.post(() -> {
                                        LinkedHashMap<String, Anime> merged = new LinkedHashMap<>();
                                        for (Anime item : local) merged.put(item.key(), item);
                                        for (Anime item : p.items) {
                                            if (item != null && Anime.valid(item) && YoruBrain.visible(item)) {
                                                merged.put(item.key(), item);
                                            }
                                        }
                                        renderAnimeList(listCol, d, new ArrayList<>(merged.values()));
                                    });
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                };
                searchH.postDelayed(searchTask[0], q.isEmpty() ? 50 : 250);
            }
            public void afterTextChanged(Editable s) {}
        });

        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
            WindowManager.LayoutParams p = w.getAttributes();
            p.dimAmount = 0.65f;
            w.setAttributes(p);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            int width = getResources().getDisplayMetrics().widthPixels;
            w.setLayout(Math.min(width, Ui.dp(this, 560)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        d.setOnDismissListener(di -> hideNavigationBar());
        d.show();
    }

    private void renderAnimeList(LinearLayout listCol, Dialog d, List<Anime> list) {
        listCol.removeAllViews();
        if (list == null || list.isEmpty()) {
            TextView empty = Ui.text(this, "Ничего не найдено", 13, Ui.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 30), 0, 0);
            listCol.addView(empty);
            return;
        }
        for (Anime a : list) {
            if (a == null || !Anime.valid(a)) continue;
            LinearLayout item = Ui.row(this);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(Ui.dp(this, 8), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
            item.setBackground(Ui.shape(0x12ffffff, 10, this));

            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            poster.setBackground(Ui.shape(Ui.CARD, 6, this));
            poster.setClipToOutline(true);
            item.addView(poster, Ui.lp(this, 36, 50));
            YoruApp.app().images.load(poster, a);

            LinearLayout col = Ui.column(this);
            col.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 8), 0);
            TextView title = Ui.text(this, YoruBrain.title(a), 13, Color.WHITE, true);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title);

            String meta = (a.year > 0 ? a.year + "" : "") + (a.score > 0 ? " · ★ " + String.format(Locale.US, "%.1f", a.score) : "") + (!a.statusLabel().isEmpty() ? " · " + a.statusLabel() : "");
            meta = meta.replaceAll("^ · ", "");
            if (!meta.isEmpty()) {
                Ui.space(col, 2);
                col.addView(Ui.text(this, meta, 11, Ui.MUTED, false));
            }
            item.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

            Ui.Icon play = new Ui.Icon(this, "play");
            play.color = Ui.PURPLE;
            item.addView(play, Ui.lp(this, 18, 18));

            Ui.press(item);
            item.setOnClickListener(v -> {
                d.dismiss();
                onAnimeSelected(a);
            });

            listCol.addView(item, Ui.lp(this, -1, -2));
            Ui.space(listCol, 6);
        }
    }

    private List<Anime> getAnimePreviewList(String query) {
        String needle = ApiRepository.plainName(query);
        LinkedHashMap<String, Anime> map = new LinkedHashMap<>();
        ArrayList<Anime> pool = new ArrayList<>();
        pool.addAll(YoruApp.app().store.recent());
        pool.addAll(YoruApp.app().store.favorites());
        pool.addAll(YoruApp.app().api.seed());

        for (Anime a : pool) {
            if (a == null || !Anime.valid(a) || !YoruBrain.visible(a) || map.containsKey(a.key())) continue;
            if (needle.isEmpty()) {
                map.put(a.key(), a);
                if (map.size() >= 20) break;
            } else {
                String hay = ApiRepository.plainName(a.title + " " + a.original + " " + a.alias);
                if (hay.contains(needle)) {
                    map.put(a.key(), a);
                    if (map.size() >= 25) break;
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    private void onAnimeSelected(Anime anime) {
        ClipServer.get().setTargetAnime(anime);
        clips.clear();
        adapter.notifyDataSetChanged();
        currentPosition = -1;
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        initTopBar();
        Ui.toast(this, anime == null ? "Случайная лента включена" : "Лента: " + YoruBrain.title(anime));
        loadInitialClips();
    }

    private void initTopBar() {
        if (topBar != null) {
            root.removeView(topBar);
        }
        topBar = Ui.row(this);
        topBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 36), Ui.dp(this, 14), Ui.dp(this, 10));

        View back = Ui.iconButton(this, "back", "Назад", this::finish);
        ((LinearLayout) topBar).addView(back, Ui.lp(this, 40, 40));

        LinearLayout titleCol = Ui.column(this);
        titleCol.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 10), 0);
        titleCol.setGravity(Gravity.CENTER_VERTICAL);
        Anime target = ClipServer.get().getTargetAnime();
        String mainTitle = target != null ? YoruBrain.title(target) : "Лента аниме";
        TextView title = Ui.text(this, mainTitle, 15, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(title);

        String sub = "";
        String vFilter = ClipServer.get().getVoiceFilter();
        if (target != null) {
            sub = "Сбросить выбор";
            titleCol.setOnClickListener(v -> onAnimeSelected(null));
            Ui.press(titleCol);
        } else if (!vFilter.isEmpty()) {
            sub = "Озвучка: " + vFilter;
        }
        if (!sub.isEmpty()) {
            TextView subText = Ui.text(this, sub, 11, Ui.MUTED, false);
            subText.setSingleLine(true);
            titleCol.addView(subText);
        }
        ((LinearLayout) topBar).addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        Ui.Icon searchBtn = new Ui.Icon(this, "search");
        searchBtn.color = target != null ? Color.WHITE : Ui.MUTED;
        searchBtn.setContentDescription("Поиск аниме");
        searchBtn.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        searchBtn.setOnClickListener(v -> showAnimeSearchDialog());
        Ui.press(searchBtn);
        Ui.gap((LinearLayout) topBar, searchBtn, 36, 36, 8);

        Ui.Icon voiceBtn = new Ui.Icon(this, "mic");
        voiceBtn.color = vFilter.isEmpty() ? Ui.MUTED : Color.WHITE;
        voiceBtn.setContentDescription("Выбор озвучки");
        voiceBtn.setPadding(Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7), Ui.dp(this, 7));
        voiceBtn.setOnClickListener(v -> showVoiceDialog());
        Ui.press(voiceBtn);
        Ui.gap((LinearLayout) topBar, voiceBtn, 36, 36, 8);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(topBar, lp);
    }

    private void loadInitialClips() {
        for (int i = 0; i < 3; i++) {
            ClipServer.get().nextClip(new ClipServer.ClipCallback() {
                @Override
                public void onClipReady(ClipServer.Clip clip) {
                    clips.add(clip);
                    adapter.notifyItemInserted(clips.size() - 1);
                    if (currentPosition == -1) {
                        recycler.post(() -> checkActiveSnap());
                    }
                }

                @Override
                public void onClipFailed() {
                }
            });
        }
    }

    private void fetchMoreClips(boolean playImmediately) {
        ClipServer.get().nextClip(new ClipServer.ClipCallback() {
            @Override
            public void onClipReady(ClipServer.Clip clip) {
                int index = clips.size();
                clips.add(clip);
                adapter.notifyItemInserted(index);
                if (playImmediately) {
                    recycler.smoothScrollToPosition(index);
                }
            }

            @Override
            public void onClipFailed() {
            }
        });
    }

    private void checkActiveSnap() {
        View snapView = snapHelper.findSnapView(layoutManager);
        if (snapView == null) return;
        int pos = layoutManager.getPosition(snapView);
        if (pos == currentPosition || pos < 0 || pos >= clips.size()) return;

        RecyclerView.ViewHolder vh = recycler.getChildViewHolder(snapView);
        if (vh == null) {
            vh = recycler.findViewHolderForAdapterPosition(pos);
        }
        if (!(vh instanceof ClipHolder)) {
            recycler.post(this::checkActiveSnap);
            return;
        }

        if (currentHolder != null && currentHolder.poster != null) {
            currentHolder.poster.setAlpha(1f);
            currentHolder.poster.setVisibility(View.VISIBLE);
        }

        currentPosition = pos;
        playClip((ClipHolder) vh, clips.get(pos));

        if (pos >= clips.size() - 3) {
            fetchMoreClips(false);
            ClipServer.get().triggerPrefetch();
        }
    }

    private void playClip(ClipHolder holder, ClipServer.Clip clip) {
        if (player == null) return;
        progressHandler.removeCallbacks(progressTick);
        currentHolder = holder;

        holder.poster.setAlpha(1f);
        holder.poster.setVisibility(View.VISIBLE);
        holder.buffering.setVisibility(View.VISIBLE);
        holder.updateState();
        holder.applyOverlayState(uiOverlayVisible);

        player.stop();
        player.clearMediaItems();

        if (playerView != null) {
            playerView.setPlayer(null);
            playerView.setPlayer(player);
            playerView.setResizeMode(isZoomMode ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        }

        MediaItem.ClippingConfiguration clipping = new MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.startMs)
                .setEndPositionMs(clip.endMs)
                .build();

        MediaItem mediaItem = DownloadHub.item(clip.streamUrl).buildUpon()
                .setClippingConfiguration(clipping)
                .build();

        player.setMediaSource(new DefaultMediaSourceFactory(YoruApp.app().mediaCache.onlineFactory()).createMediaSource(mediaItem));
        player.setRepeatMode(autoScroll ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
        player.setVolume(isMuted ? 0f : 1f);
        player.prepare();
        player.play();

        // Safety fallback: ensure poster is cleared if already rendering or ready
        progressHandler.postDelayed(() -> {
            if (currentHolder != null && currentHolder == holder && player != null && player.isPlaying()) {
                if (currentHolder.poster != null) currentHolder.poster.setVisibility(View.GONE);
                if (currentHolder.buffering != null) currentHolder.buffering.setVisibility(View.GONE);
            }
        }, 350);

        progressHandler.post(progressTick);
    }

    private final class ClipAdapter extends RecyclerView.Adapter<ClipHolder> {
        @NonNull
        @Override
        public ClipHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout item = new FrameLayout(TikTokActivity.this);
            item.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));
            return new ClipHolder(item);
        }

        @Override
        public void onBindViewHolder(@NonNull ClipHolder h, int position) {
            ClipServer.Clip clip = clips.get(position);
            h.bind(clip);
        }

        @Override
        public int getItemCount() {
            return clips.size();
        }
    }

    final class ClipHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;
        final ImageView poster;
        final View topGrad;
        final View botGrad;
        final ProgressBar progressBar;
        final ProgressBar buffering;
        final ImageView playPauseCenter;
        final ImageView avatar;
        final TextView title;
        final TextView timing;
        final TextView details;
        final Ui.Icon bookmarkIcon;
        final Ui.Icon autoSwipeIcon;
        final Ui.Icon resizeIcon;
        final Ui.Icon muteIcon;
        final LinearLayout actions;
        final LinearLayout info;
        ClipServer.Clip clip;

        ClipHolder(@NonNull View itemView) {
            super(itemView);
            container = (FrameLayout) itemView;

            // Thumbnail / Poster background
            poster = new ImageView(TikTokActivity.this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            container.addView(poster, new FrameLayout.LayoutParams(-1, -1));

            // Top gradient
            topGrad = new View(TikTokActivity.this);
            topGrad.setBackground(Ui.gradient(0xa0000000, 0x00000000, 0, TikTokActivity.this));
            container.addView(topGrad, new FrameLayout.LayoutParams(-1, Ui.dp(TikTokActivity.this, 110), Gravity.TOP));

            // Bottom gradient
            botGrad = new View(TikTokActivity.this);
            botGrad.setBackground(Ui.gradient(0x00000000, 0xdf000000, 0, TikTokActivity.this));
            container.addView(botGrad, new FrameLayout.LayoutParams(-1, Ui.dp(TikTokActivity.this, 300), Gravity.BOTTOM));

            // Buffering spinner
            buffering = new ProgressBar(TikTokActivity.this);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(Ui.dp(TikTokActivity.this, 48), Ui.dp(TikTokActivity.this, 48), Gravity.CENTER);
            container.addView(buffering, bp);

            // Play / Pause center indicator
            playPauseCenter = new ImageView(TikTokActivity.this);
            playPauseCenter.setImageResource(android.R.drawable.ic_media_play);
            playPauseCenter.setColorFilter(Color.WHITE);
            playPauseCenter.setVisibility(View.GONE);
            playPauseCenter.setClickable(false);
            playPauseCenter.setFocusable(false);
            container.addView(playPauseCenter, new FrameLayout.LayoutParams(Ui.dp(TikTokActivity.this, 64), Ui.dp(TikTokActivity.this, 64), Gravity.CENTER));

            // Bottom Progress Bar
            progressBar = new ProgressBar(TikTokActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(100);
            progressBar.setProgress(0);
            FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, Ui.dp(TikTokActivity.this, 4), Gravity.BOTTOM);
            container.addView(progressBar, pp);

            // Right Action Column
            actions = Ui.column(TikTokActivity.this);
            actions.setGravity(Gravity.CENTER_HORIZONTAL);
            actions.setPadding(0, 0, Ui.dp(TikTokActivity.this, 14), Ui.dp(TikTokActivity.this, 30));

            // 1. Avatar / Poster
            FrameLayout avFrame = new FrameLayout(TikTokActivity.this);
            avFrame.setBackground(Ui.stroke(Ui.PURPLE, 24, TikTokActivity.this));
            avFrame.setClipToOutline(true);
            avatar = new ImageView(TikTokActivity.this);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avFrame.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
            avFrame.setOnClickListener(v -> {
                if (clip != null) Ui.openDetails(TikTokActivity.this, clip.anime);
            });
            actions.addView(avFrame, Ui.lp(TikTokActivity.this, 46, 46));

            Ui.space(actions, 22);

            // 2. Favorite / Collection Button (WITHOUT text, WITHOUT "В план")
            bookmarkIcon = new Ui.Icon(TikTokActivity.this, "bookmark");
            bookmarkIcon.color = Color.WHITE;
            bookmarkIcon.setContentDescription("В коллекцию");
            bookmarkIcon.setOnClickListener(v -> {
                if (clip != null) {
                    Ui.bucketDialog(TikTokActivity.this, clip.anime, this::updateFavoriteState);
                }
            });
            Ui.press(bookmarkIcon);
            actions.addView(bookmarkIcon, Ui.lp(TikTokActivity.this, 34, 34));

            Ui.space(actions, 20);

            // 3. Auto-swipe Button (Directly under favorite icon, WITHOUT text, WITHOUT emoji)
            autoSwipeIcon = new Ui.Icon(TikTokActivity.this, "autoswipe");
            autoSwipeIcon.color = autoScroll ? Ui.PURPLE : Color.WHITE;
            autoSwipeIcon.setContentDescription("Автосвайп");
            autoSwipeIcon.setOnClickListener(v -> {
                autoScroll = !autoScroll;
                if (player != null) {
                    player.setRepeatMode(autoScroll ? Player.REPEAT_MODE_OFF : Player.REPEAT_MODE_ONE);
                }
                updateAutoSwipeState();
                Ui.toast(TikTokActivity.this, autoScroll ? "Автосвайп включён" : "Автосвайп выключен");
            });
            Ui.press(autoSwipeIcon);
            actions.addView(autoSwipeIcon, Ui.lp(TikTokActivity.this, 32, 32));

            Ui.space(actions, 20);

            // 4. Expansion / Resize Button (Under auto-swipe icon, WITHOUT text, WITHOUT emoji)
            resizeIcon = new Ui.Icon(TikTokActivity.this, isZoomMode ? "shrink" : "expand");
            resizeIcon.color = Color.WHITE;
            resizeIcon.setContentDescription("Размер экрана");
            resizeIcon.setOnClickListener(v -> {
                isZoomMode = !isZoomMode;
                if (playerView != null) {
                    playerView.setResizeMode(isZoomMode ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
                }
                resizeIcon.setKind(isZoomMode ? "shrink" : "expand");
                Ui.toast(TikTokActivity.this, isZoomMode ? "Экран расширен" : "Исходный размер");
            });
            Ui.press(resizeIcon);
            actions.addView(resizeIcon, Ui.lp(TikTokActivity.this, 30, 30));

            Ui.space(actions, 20);

            // 5. Sound / Mute Toggle Button (WITHOUT text, WITHOUT emoji)
            muteIcon = new Ui.Icon(TikTokActivity.this, isMuted ? "mute" : "volume");
            muteIcon.color = Color.WHITE;
            muteIcon.setContentDescription("Звук");
            muteIcon.setOnClickListener(v -> {
                isMuted = !isMuted;
                if (player != null) player.setVolume(isMuted ? 0f : 1f);
                muteIcon.setKind(isMuted ? "mute" : "volume");
            });
            Ui.press(muteIcon);
            actions.addView(muteIcon, Ui.lp(TikTokActivity.this, 28, 28));

            FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
            container.addView(actions, ap);

            // Bottom Info Column
            info = Ui.column(TikTokActivity.this);
            info.setPadding(Ui.dp(TikTokActivity.this, 16), 0, Ui.dp(TikTokActivity.this, 84), Ui.dp(TikTokActivity.this, 24));

            title = Ui.text(TikTokActivity.this, "", 18, Color.WHITE, true);
            title.setMaxLines(2);
            title.setShadowLayer(6, 0, 2, 0xcc000000);
            title.setOnClickListener(v -> {
                if (clip != null) Ui.openDetails(TikTokActivity.this, clip.anime);
            });
            Ui.press(title);
            info.addView(title);

            Ui.space(info, 5);

            timing = Ui.text(TikTokActivity.this, "", 12, Ui.AMBER, true);
            timing.setShadowLayer(4, 0, 1, 0xcc000000);
            info.addView(timing);

            Ui.space(info, 5);

            details = Ui.text(TikTokActivity.this, "", 12, Ui.ZINC, false);
            details.setShadowLayer(4, 0, 1, 0xcc000000);
            info.addView(details);

            Ui.space(info, 12);

            TextView fullBtn = Ui.button(TikTokActivity.this, "▶ Смотреть серию полностью", true, () -> {
                if (clip != null) {
                    Ui.openPlayer(TikTokActivity.this, clip.anime, "yoru", clip.episode != null ? clip.episode.number : 1, clip.startMs);
                }
            });
            fullBtn.setBackground(Ui.shape(0xffa78bfa, 12, TikTokActivity.this));
            fullBtn.setTextColor(0xffffffff);
            info.addView(fullBtn, Ui.lp(TikTokActivity.this, -2, 42));

            FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -2, Gravity.START | Gravity.BOTTOM);
            container.addView(info, ip);

            // Pinch gesture to hide/show UI overlay
            ScaleGestureDetector scaleDetector = new ScaleGestureDetector(TikTokActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private float scaleAccumulator = 1f;

                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    scaleAccumulator = 1f;
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    scaleAccumulator *= detector.getScaleFactor();
                    if (scaleAccumulator > 1.12f) { // Spreading two fingers apart -> hide UI
                        setOverlayVisible(false);
                        scaleAccumulator = 1f;
                    } else if (scaleAccumulator < 0.88f) { // Pinching two fingers together -> show UI
                        setOverlayVisible(true);
                        scaleAccumulator = 1f;
                    }
                    return true;
                }
            });

            // Single tap gesture to pause / resume
            GestureDetector tapDetector = new GestureDetector(TikTokActivity.this, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    if (player != null) {
                        if (player.isPlaying()) player.pause();
                        else player.play();
                    }
                    return true;
                }
            });

            container.setOnTouchListener((v, event) -> {
                if (event.getPointerCount() >= 2) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    scaleDetector.onTouchEvent(event);
                    return true;
                } else {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                boolean handled = tapDetector.onTouchEvent(event);
                return handled || event.getAction() == MotionEvent.ACTION_DOWN;
            });
        }

        void setOverlayVisible(boolean visible) {
            float alpha = visible ? 1f : 0f;
            int vis = visible ? View.VISIBLE : View.GONE;
            if (actions != null) {
                actions.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                    if (actions != null) actions.setVisibility(vis);
                }).start();
            }
            if (info != null) {
                info.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                    if (info != null) info.setVisibility(vis);
                }).start();
            }
            if (progressBar != null) {
                progressBar.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                    if (progressBar != null) progressBar.setVisibility(vis);
                }).start();
            }
            if (topGrad != null) {
                topGrad.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                    if (topGrad != null) topGrad.setVisibility(vis);
                }).start();
            }
            if (botGrad != null) {
                botGrad.animate().alpha(alpha).setDuration(220).withEndAction(() -> {
                    if (botGrad != null) botGrad.setVisibility(vis);
                }).start();
            }
        }

        void applyOverlayState(boolean visible) {
            float alpha = visible ? 1f : 0f;
            int vis = visible ? View.VISIBLE : View.GONE;
            if (actions != null) { actions.setAlpha(alpha); actions.setVisibility(vis); }
            if (info != null) { info.setAlpha(alpha); info.setVisibility(vis); }
            if (progressBar != null) { progressBar.setAlpha(alpha); progressBar.setVisibility(vis); }
            if (topGrad != null) { topGrad.setAlpha(alpha); topGrad.setVisibility(vis); }
            if (botGrad != null) { botGrad.setAlpha(alpha); botGrad.setVisibility(vis); }
        }

        void bind(ClipServer.Clip c) {
            clip = c;
            title.setText(c.title);
            timing.setText("Серия " + (c.episode != null ? Ui.number(c.episode.number) : "1") + " • " + c.timingText);
            String genres = c.anime != null && c.anime.genres != null && !c.anime.genres.isEmpty()
                    ? String.join(", ", c.anime.genres.subList(0, Math.min(3, c.anime.genres.size())))
                    : "Аниме";
            String score = c.anime != null && c.anime.score > 0 ? String.format(java.util.Locale.US, "★ %.1f • ", c.anime.score) : "";
            String voiceLabel = c.voiceName != null && !c.voiceName.isEmpty() ? " • " + c.voiceName : "";
            details.setText(score + genres + voiceLabel);
            updateState();
            if (c.anime != null) {
                YoruApp.app().images.load(avatar, c.anime);
                YoruApp.app().images.load(poster, c.anime);
            }
            applyOverlayState(uiOverlayVisible);
        }

        void updateState() {
            updateFavoriteState();
            updateAutoSwipeState();
            if (resizeIcon != null) {
                resizeIcon.setKind(isZoomMode ? "shrink" : "expand");
            }
            if (muteIcon != null) {
                muteIcon.setKind(isMuted ? "mute" : "volume");
            }
        }

        void updateFavoriteState() {
            if (clip == null || bookmarkIcon == null) return;
            boolean isFav = YoruApp.app().store.favorite(clip.anime);
            bookmarkIcon.color = isFav ? Ui.PURPLE : Color.WHITE;
            bookmarkIcon.filled = isFav;
            bookmarkIcon.invalidate();
        }

        void updateAutoSwipeState() {
            if (autoSwipeIcon == null) return;
            autoSwipeIcon.color = autoScroll ? Ui.PURPLE : Color.WHITE;
            autoSwipeIcon.setAlpha(autoScroll ? 1.0f : 0.8f);
            autoSwipeIcon.invalidate();
        }
    }

    private void hideNavigationBar() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        hideNavigationBar();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideNavigationBar();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideNavigationBar();
        if (player != null && !player.isPlaying()) {
            player.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        progressHandler.removeCallbacks(progressTick);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
