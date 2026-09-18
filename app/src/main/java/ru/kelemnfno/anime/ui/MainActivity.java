package ru.kelemnfno.anime.ui;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.prefs.Prefs;
import ru.kelemnfno.anime.util.CrashGuard;
import android.content.ClipData;
import ru.kelemnfno.anime.databinding.ActivityMainBinding;
import ru.kelemnfno.anime.notify.NewEpisodeWorker;
import ru.kelemnfno.anime.ui.calendar.CalendarFragment;
import ru.kelemnfno.anime.ui.catalog.CatalogFragment;
import ru.kelemnfno.anime.ui.downloads.DownloadsActivity;
import ru.kelemnfno.anime.ui.favorites.FavoritesFragment;
import ru.kelemnfno.anime.ui.history.HistoryActivity;
import ru.kelemnfno.anime.ui.home.HomeFragment;
import ru.kelemnfno.anime.ui.search.SearchActivity;
import ru.kelemnfno.anime.ui.settings.SettingsActivity;
import ru.kelemnfno.anime.util.Ui;

/** Единственная «корневая» активность: нижняя навигация + четыре раздела. */
public class MainActivity extends AppCompatActivity {

    public static final String TAB_HOME = "home";
    public static final String TAB_CATALOG = "catalog";
    public static final String TAB_FEED = "feed";
    public static final String TAB_FAVORITES = "favorites";
    public static final String TAB_CALENDAR = "calendar";

    private ActivityMainBinding b;
    private String currentTab = TAB_HOME;

    private final ActivityResultLauncher<String> notifPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    NewEpisodeWorker.schedule(this);
                    Ui.toast(this, "Уведомления включены");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        b.btnSearch.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        b.btnHistory.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        b.btnDownloads.setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        b.btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        b.navHome.setOnClickListener(v -> select(TAB_HOME));
        b.navCatalog.setOnClickListener(v -> select(TAB_CATALOG));
        b.navFavorites.setOnClickListener(v -> select(TAB_FAVORITES));
        b.navCalendar.setOnClickListener(v -> select(TAB_CALENDAR));
        b.navFeed.setOnClickListener(v -> select(TAB_FEED));

        if (savedInstanceState == null) {
            select(TAB_HOME);
            askNotificationPermission();
        } else {
            currentTab = savedInstanceState.getString("tab", TAB_HOME);
            renderNav();
        }

    }


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("tab", currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderNav();
    }

    /** Запрос разрешения на уведомления (Android 13+) — один раз и ненавязчиво. */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Prefs.get(this).permissionAsked()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        Prefs.get(this).setPermissionAsked(true);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Уведомления о новых сериях")
                .setMessage(R.string.notifications_permission)
                .setPositiveButton(R.string.allow, (d, w) -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS))
                .setNegativeButton(R.string.later, null)
                .show();
    }

    public void select(String tab) {
        if (tab.equals(currentTab) && getSupportFragmentManager().findFragmentByTag(tab) != null) {
            renderNav();
            return;
        }
        currentTab = tab;
        Fragment fragment;
        switch (tab) {
            case TAB_CATALOG:
                fragment = new CatalogFragment();
                break;
            case TAB_FAVORITES:
                fragment = new FavoritesFragment();
                break;
            case TAB_CALENDAR:
                fragment = new CalendarFragment();
                break;
            case TAB_FEED:
                fragment = new ru.kelemnfno.anime.ui.feed.FeedFragment();
                break;
            default:
                fragment = new HomeFragment();
                break;
        }
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction();
        tx.setCustomAnimations(R.anim.fade_in, R.anim.fade_in);
        tx.replace(R.id.container, fragment, tab);
        tx.commit();
        renderNav();
    }

    /** Активный пункт подсвечиваем белой «пилюлей» — как на сайте. */
    private void renderNav() {
        bindNav(b.navHomeIcon, b.navHomeLabel, b.navHome, TAB_HOME, R.drawable.ic_home_filled, R.drawable.ic_home);
        bindNav(b.navCatalogIcon, b.navCatalogLabel, b.navCatalog, TAB_CATALOG, R.drawable.ic_grid, R.drawable.ic_grid);
        bindNav(b.navFavoritesIcon, b.navFavoritesLabel, b.navFavorites, TAB_FAVORITES,
                R.drawable.ic_heart_filled, R.drawable.ic_heart);
        bindNav(b.navCalendarIcon, b.navCalendarLabel, b.navCalendar, TAB_CALENDAR,
                R.drawable.ic_calendar, R.drawable.ic_calendar);
        bindNav(b.navFeedIcon, b.navFeedLabel, b.navFeed, TAB_FEED,
                R.drawable.ic_feed, R.drawable.ic_feed);
    }

    private void bindNav(android.widget.ImageView icon, android.widget.TextView label, View container,
                         String tab, int activeIcon, int inactiveIcon) {
        boolean active = currentTab.equals(tab);
        icon.setImageResource(active ? activeIcon : inactiveIcon);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getColor(active ? R.color.bg : R.color.text_mute)));
        icon.setBackgroundResource(active ? R.drawable.bg_pill_white : 0);
        label.setTextColor(getColor(active ? R.color.text : R.color.text_mute));
        container.setAlpha(active ? 1f : 0.9f);
        if (active) Ui.pop(icon);
    }
}
