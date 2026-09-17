package ru.kelemnfno.anime.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.snackbar.Snackbar;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.data.tsuyu.Net;

import android.graphics.drawable.Drawable;

import okhttp3.Request;
import okhttp3.Response;

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
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .thumbnail(0.15f)
                .format(DecodeFormat.PREFER_RGB_565)
                .placeholder(R.drawable.ph_poster)
                .error(R.drawable.ph_poster);
        view.setTag(url);
        Glide.with(view.getContext()).load(withHeaders(url)).apply(opts)
                .listener(new HttpFallback(view, url, radiusDp))
                .into(view);
    }

    public static void image(ImageView view, String url) {
        if (view == null) return;
        if (url == null || url.isEmpty()) {
            view.setImageResource(R.drawable.ph_poster);
            return;
        }
        view.setTag(url);
        Glide.with(view.getContext()).load(withHeaders(url))
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .thumbnail(0.15f)
                        .placeholder(R.drawable.ph_poster).error(R.drawable.ph_poster))
                .listener(new HttpFallback(view, url, 0))
                .into(view);
    }

    /**
     * Запасной путь: если Glide не смог получить картинку, тянем её тем же OkHttp,
     * которым приложение получает данные (он точно ходит в сеть).
     */
    private static final class HttpFallback implements RequestListener<Drawable> {
        private final ImageView view;
        private final String url;
        private final int radius;

        HttpFallback(ImageView view, String url, int radius) {
            this.view = view;
            this.url = url;
            this.radius = radius;
        }

        @Override
        public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target, boolean isFirst) {
            load();
            return false;
        }

        @Override
        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                       DataSource dataSource, boolean isFirst) {
            return false;
        }

        private void load() {
            AppExecutors.get().io().execute(() -> {
                try {
                    Request req = new Request.Builder()
                            .url(url)
                            .header("User-Agent", Net.CHROME)
                            .header("Referer", "https://yani.tv/")
                            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                            .build();
                    try (Response resp = Net.client().newCall(req).execute()) {
                        if (!resp.isSuccessful() || resp.body() == null) return;
                        byte[] data = resp.body().bytes();
                        Bitmap bmp = decode(data);
                        if (bmp == null) return;
                        view.post(() -> {
                            if (!url.equals(view.getTag())) return;
                            if (radius > 0) {
                                Glide.with(view.getContext()).load(bmp).apply(new RequestOptions()
                                        .transform(new CenterCrop(),
                                                new RoundedCorners(dp(view.getContext(), radius))))
                                        .into(view);
                            } else {
                                view.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                    // картинка просто останется на заглушке
                }
            });
        }

        private Bitmap decode(byte[] data) {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int wantW = view.getWidth() > 0 ? view.getWidth() : 512;
            int wantH = view.getHeight() > 0 ? view.getHeight() : 768;
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= wantW && probe.outHeight / (sample * 2) >= wantH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        }
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
