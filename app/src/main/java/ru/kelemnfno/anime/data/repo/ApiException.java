package ru.kelemnfno.anime.data.repo;

/** Сетевая/серверная ошибка с человеко-понятным текстом. */
public class ApiException extends Exception {
    public ApiException(String message) {
        super(message);
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
