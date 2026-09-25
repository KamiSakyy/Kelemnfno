# --- Модель данных (Gson) ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keep class ru.kelemnfno.anime.data.model.** { *; }
-keep class ru.kelemnfno.anime.data.api.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- OkHttp / Retrofit ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Media3 / ExoPlayer ---
-dontwarn androidx.media3.**
-keep class androidx.media3.session.** { *; }
-keep class ru.kelemnfno.anime.player.PlaybackService { *; }

# --- Glide ---
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }

# --- Jsoup ---
-dontwarn org.jsoup.**

# --- WorkManager ---
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# --- Нативные/сервисы ---
-keep class ru.kelemnfno.anime.download.DownloadService { *; }
-keep class ru.kelemnfno.anime.notify.** { *; }
-keepclassmembers class * extends android.app.Service { public <init>(); }

# Отладка: читаемые стектрейсы
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Защита от декомпиляции ---
# Все классы приложения переносятся в один пакет с короткими именами,
# ссылки переносятся через границы пакетов, логирование вырезается целиком.
-repackageclasses 'a'
-allowaccessmodification
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
}

# --- JNI ---
-keepclasseswithmembernames class * {
    native <methods>;
}
