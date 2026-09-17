package ru.kelemnfno.anime.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/** Контейнер 16:9 — под встроенный плеер на экране тайтла. */
public class RatioFrameLayout extends FrameLayout {

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public RatioFrameLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioFrameLayout(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int w = getMeasuredWidth();
        int h = Math.round(w * 9f / 16f);
        if (h > 0) super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
