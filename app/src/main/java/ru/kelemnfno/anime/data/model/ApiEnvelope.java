package ru.kelemnfno.anime.data.model;

import com.google.gson.annotations.SerializedName;

/** Все ответы API Yummy обёрнуты в { response: ... }. */
public class ApiEnvelope<T> {
    @SerializedName("response")
    public T response;
    @SerializedName("error")
    public String error;
    @SerializedName("error_title")
    public String errorTitle;

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }

    public String message() {
        if (errorTitle != null && !errorTitle.isEmpty()) return errorTitle;
        return error != null ? error : "Ошибка сети";
    }
}
