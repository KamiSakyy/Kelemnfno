package ru.kelemnfno.anime.ui.settings;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.model.AppSettings;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.databinding.ActivitySettingsBinding;
import ru.kelemnfno.anime.download.DownloadStore;
import ru.kelemnfno.anime.notify.NewEpisodeWorker;
import ru.kelemnfno.anime.ui.Chips;
import ru.kelemnfno.anime.util.CrashGuard;
import ru.kelemnfno.anime.util.Fmt;
import ru.kelemnfno.anime.util.Ui;

/** Настройки: уведомления, плеер, скачивание. */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding b;
    private AppSettings settings;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.back.setOnClickListener(v -> finish());
        settings = Prefs.get(this).settings();
        render();
    }

    private void render() {
        LinearLayout c = b.content;
        c.removeAllViews();

        section(c, "Уведомления");
        toggle(c, "Новые серии избранного", "Проверять расписание и присылать уведомление",
                settings.notifyNewEpisodes, v -> {
                    settings.notifyNewEpisodes = v;
                    save();
                    if (v) NewEpisodeWorker.schedule(this);
                    else NewEpisodeWorker.cancel(this);
                });
        toggle(c, "Только по Wi-Fi", "Не тратить мобильный трафик на проверки",
                settings.notifyWifiOnly, v -> {
                    settings.notifyWifiOnly = v;
                    save();
                    NewEpisodeWorker.schedule(this);
                });
        chips(c, "Период проверки", new String[]{"1 час", "3 часа", "6 часов", "12 часов", "24 часа"},
                new int[]{1, 3, 6, 12, 24}, settings.checkHours, value -> {
                    settings.checkHours = value;
                    save();
                    NewEpisodeWorker.schedule(this);
                    render();
                });

        section(c, "Плеер");
        toggle(c, "Автопереход к следующей серии", "Включать следующую серию автоматически",
                settings.autoNext, v -> {
                    settings.autoNext = v;
                    save();
                });
        toggle(c, "Автоподбор источника", "Менять озвучку/источник, если поток не запустился",
                settings.autoSwitch, v -> {
                    settings.autoSwitch = v;
                    save();
                });
        toggle(c, "Жесты в плеере", "Громкость, яркость, перемотка свайпом",
                settings.gestures, v -> {
                    settings.gestures = v;
                    save();
                });
        toggle(c, "Продолжать с последнего места", "Запоминать позицию просмотра",
                settings.resumePlayback, v -> {
                    settings.resumePlayback = v;
                    save();
                });
        toggle(c, "Пропускать опенинг", "Кнопка «Пропустить опенинг» в плеере",
                settings.skipOpening, v -> {
                    settings.skipOpening = v;
                    save();
                });
        toggle(c, "Картинка в картинке", "Уходить в PiP при сворачивании",
                settings.pipOnLeave, v -> {
                    settings.pipOnLeave = v;
                    save();
                });
        chips(c, "Скорость по умолчанию", new String[]{"0.75x", "1x", "1.25x", "1.5x", "2x"},
                new int[]{75, 100, 125, 150, 200}, Math.round(settings.speed * 100), value -> {
                    settings.speed = value / 100f;
                    save();
                    render();
                });

        section(c, "Скачивание");
        chips(c, "Качество по умолчанию", new String[]{"1080p", "720p", "480p", "360p"},
                new int[]{1080, 720, 480, 360}, settings.downloadQuality, value -> {
                    settings.downloadQuality = value;
                    save();
                    render();
                });
        info(c, "Папка: " + DownloadStore.root(this).getAbsolutePath()
                + "\nСвободно: " + Fmt.formatBytes(DownloadStore.freeBytes(this)));

        section(c, "Каталог");
        toggle(c, "Показывать жанры 18+", "Раздел «этти» и другие взрослые жанры в фильтрах",
                settings.showAdult, v -> {
                    settings.showAdult = v;
                    save();
                });
        toggle(c, "Воспроизведение в мобильной сети", "Не спрашивать подтверждение",
                settings.playOnMobile, v -> {
                    settings.playOnMobile = v;
                    save();
                });

        section(c, "О приложении");
        TextView about = info(c, "Kelemnfno 1.2.0 · Android-порт сайта\n"
                + "Экраны, анимации, скачивание, уведомления и собственный плеер на ExoPlayer (Media3).");
        about.setOnLongClickListener(v -> {
            showLastCrash();
            return true;
        });
        button(c, "Открыть сайт", v -> Ui.openUrl(this, "https://yani.tv"));
        button(c, "Очистить кэш изображений", v -> new Thread(() -> {
            com.bumptech.glide.Glide.get(this).clearDiskCache();
            Ui.post(() -> Ui.toast(this, "Кэш очищен"));
        }).start());
    }

    private void save() {
        Prefs.get(this).saveSettings(settings);
    }

    private void section(LinearLayout parent, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextAppearance(R.style.Widget_Kelemnfno_Label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 22);
        lp.bottomMargin = Ui.dp(this, 8);
        parent.addView(tv, lp);
    }

    /** Колбэк переключателя: один boolean, чтобы лямбды на месте вызова были однозначны. */
    public interface Toggle {
        void onChange(boolean on);
    }

    private void toggle(LinearLayout parent, String title, String subtitle, boolean value,
                        Toggle listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_ripple_rounded);
        row.setPadding(0, Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.text));
        t.setTextSize(15);
        texts.addView(t);
        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(getColor(R.color.text_mute));
        s.setTextSize(12);
        texts.addView(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(texts, lp);

        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((button, on) -> listener.onChange(on));
        row.addView(sw);
        parent.addView(row);
    }

    private interface IntConsumer {
        void accept(int value);
    }

    private void chips(LinearLayout parent, String title, String[] labels, int[] values, int current,
                       IntConsumer onSelect) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getColor(R.color.text_dim));
        tv.setTextSize(13);
        parent.addView(tv);

        com.google.android.flexbox.FlexboxLayout box = new com.google.android.flexbox.FlexboxLayout(this);
        com.google.android.flexbox.FlexboxLayout.LayoutParams blp =
                new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        parent.addView(box, blp);

        for (int i = 0; i < labels.length; i++) {
            final int value = values[i];
            Chips.add(box, labels[i], current == value, v -> onSelect.accept(value));
        }
    }

    private TextView info(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.text_mute));
        tv.setTextSize(12.5f);
        tv.setBackgroundResource(R.drawable.bg_surface_block);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        parent.addView(tv);
        return tv;
    }

    /** Скрытая диагностика: долгое нажатие на блоке «О приложении». */
    private void showLastCrash() {
        String report = CrashGuard.readAndClear(this);
        String text = report == null ? "Сбоев не зафиксировано"
                : (report.length() > 4000 ? report.substring(0, 4000) + "…" : report);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Техническая информация")
                .setMessage(text)
                .setPositiveButton(R.string.copy_crash, (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Kelemnfno", text));
                        Ui.toast(this, "Скопировано");
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void button(LinearLayout parent, String title, View.OnClickListener listener) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(getColor(R.color.text));
        tv.setTextSize(14);
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setPadding(Ui.dp(this, 18), Ui.dp(this, 13), Ui.dp(this, 18), Ui.dp(this, 13));
        tv.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        parent.addView(tv, lp);
    }
}
