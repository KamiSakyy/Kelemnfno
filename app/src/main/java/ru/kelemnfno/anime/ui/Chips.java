package ru.kelemnfno.anime.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import ru.kelemnfno.anime.R;
import ru.kelemnfno.anime.databinding.ItemChipBinding;
import ru.kelemnfno.anime.util.Ui;

/** Фабрика чипов в фирменном стиле: белый активный, полупрозрачный неактивный. */
public final class Chips {

    private Chips() {
    }

    public static TextView chip(Context context, String text, boolean active, View.OnClickListener onClick) {
        ItemChipBinding b = ItemChipBinding.inflate(LayoutInflater.from(context));
        TextView view = b.getRoot();
        view.setText(text);
        view.setBackgroundResource(active ? R.drawable.bg_chip_on : R.drawable.bg_chip);
        view.setTextColor(context.getColor(active ? R.color.bg : R.color.text_dim));
        if (onClick != null) view.setOnClickListener(onClick);
        return view;
    }

    /** Добавляет чип с отступами в произвольный контейнер. */
    public static TextView add(ViewGroup parent, String text, boolean active, View.OnClickListener onClick) {
        TextView chip = chip(parent.getContext(), text, active, onClick);
        int gap = Ui.dp(parent.getContext(), 8);
        // Во FlexboxLayout нужны его собственные параметры, иначе чип сжимается
        // и текст не помещается в кнопку.
        if (parent instanceof com.google.android.flexbox.FlexboxLayout) {
            com.google.android.flexbox.FlexboxLayout.LayoutParams lp =
                    new com.google.android.flexbox.FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setFlexShrink(0f);
            lp.setMargins(0, 0, gap, gap);
            parent.addView(chip, lp);
            return chip;
        }
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, gap, gap);
        parent.addView(chip, lp);
        return chip;
    }

    /** Чип с иконкой слева. */
    public static TextView iconChip(Context context, String text, int iconRes, boolean active, View.OnClickListener onClick) {
        TextView chip = chip(context, text, active, onClick);
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(chip, iconRes, 0, 0, 0);
        chip.setCompoundDrawablePadding(Ui.dp(context, 6));
        return chip;
    }
}
