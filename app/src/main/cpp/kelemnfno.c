#include <jni.h>

/*
 * Ключ дешифровки строк живёт только здесь. В dex приложения его нет:
 * Java-сторона получает число вызовом native-метода.
 */

static int material(void) {
    volatile int a = 0x2d;
    volatile int b = 0x77;
    volatile int c = a ^ b;
    return (int) c;
}

JNIEXPORT jint JNICALL
Java_ru_kelemnfno_anime_data_tsuyu_Secrets_nativeKey(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) material();
}
