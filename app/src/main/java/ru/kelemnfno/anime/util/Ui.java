package ru.kelemnfno.anime.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.snackbar.Snackbar;

import ru.kelemnfno.anime.R;

/** Мелкие UI-хелперы: Glide с фирменным скруглением, тосты, анимации. */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    /**
     * CDN постеров отдаёт картинки только запросам, похожим на браузер, —
     * поэтому добавляем User-Agent и Referer к каждому запросу Glide.
     */
    private static GlideUrl withHeaders(String url) {
        return new GlideUrl(url, new LazyHeaders.Builder()
                .addHeader("User-Agent", ru.kelemnfno.anime.data.tsuyu.Net.CHROME)
                .addHeader("Referer", "https://yani.tv/")
                .addHeader("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .build());
    }

    public static void poster(ImageView view, String url, int radiusDp) {
        if (view == null) return;
        if (url == null || url.isEmpty()) {
            view.setImageResource(R.drawable.ph_poster);
            return;
        }
        RequestOptions opts = new RequestOptions()
                .transform(new CenterCrop(), new RoundedCorners(dp(view.getContext(), radiusDp)))
                .placeholder(R.drawable.ph_poster)
                .error(R.drawable.ph_poster);
        Glide.with(view.getContext()).load(withHeaders(url)).apply(opts).into(view);
    }

    public static void image(ImageView view, String url) {
        if (view == null) return;
        if (url == null || url.isEmpty()) {
            view.setImageResource(R.drawable.ph_poster);
            return;
        }
        Glide.with(view.getContext()).load(withHeaders(url))
                .apply(new RequestOptions().placeholder(R.drawable.ph_poster).error(R.drawable.ph_poster))
                .into(view);
    }

    public static void fadeIn(View v, int durationMs) {
        v.setAlpha(0f);
        v.setVisibility(View.VISIBLE);
        v.animate().alpha(1f).setDuration(durationMs).setInterpolator(
                new android.view.animation.DecelerateInterpolator()).start();
    }

    public static void fadeIn(View v) {
        if (v == null) return;
        v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.fade_in));
    }

    public static void fadeUp(View v) {
        if (v == null) return;
        v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.fade_up));
    }

    public static void pop(View v) {
        if (v == null) return;
        v.startAnimation(AnimationUtils.loadAnimation(v.getContext(), R.anim.pop));
    }

    /** Колбэк на главный поток из фонового. */
    public static void post(Runnable r) {
        AppExecutors.get().post(r);
    }

    public static void toast(Context c, String message) {
        if (c == null || message == null) return;
        Toast.makeText(c.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static void snack(View anchor, String message) {
        if (anchor == null) return;
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show();
    }

    public static void openUrl(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    public static void hideKeyboard(Activity a) {
        View v = a.getCurrentFocus();
        if (v == null) return;
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
