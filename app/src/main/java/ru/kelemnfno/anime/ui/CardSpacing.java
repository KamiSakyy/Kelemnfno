package ru.kelemnfno.anime.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import ru.kelemnfno.anime.util.Ui;

/**
 * Отступы карточек как на сайте: 12px между колонками, 24px между рядами,
 * в горизонтальном ряду — 12px между карточками.
 */
public class CardSpacing extends RecyclerView.ItemDecoration {

    private final int gap;
    private final int rowGap;
    private final int columns;

    /** columns = 0 — горизонтальный ряд. */
    public CardSpacing(Context context, int columns) {
        this.columns = columns;
        this.gap = Ui.dp(context, 12);
        this.rowGap = Ui.dp(context, 24);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                               @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int position = parent.getChildAdapterPosition(view);
        if (position < 0) return;
        if (columns <= 0) {
            outRect.left = position == 0 ? 0 : gap;
            outRect.right = 0;
            outRect.top = 0;
            outRect.bottom = Ui.dp(view.getContext(), 4);
            return;
        }
        int column = position % columns;
        outRect.left = gap - column * gap / columns;
        outRect.right = (column + 1) * gap / columns;
        outRect.top = position < columns ? 0 : rowGap;
    }
}
