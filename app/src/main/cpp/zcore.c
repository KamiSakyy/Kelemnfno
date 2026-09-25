#include <jni.h>

/*
 * Вспомогательный JNI-модуль. Имя класса, метода и сигнатура не хранятся
 * открытым текстом и не экспортируются как Java_...-символы: регистрация
 * идёт через JNI_OnLoad, строки собираются в рантайме. Ключ дешифровки
 * лежит в volatile-переменной, поэтому компилятор не может вычислить
 * строки на этапе сборки.
 */

static volatile int g_kx;

static char g_cls[37];
static char g_mth[10];
static char g_sig[4];

__attribute__((noinline))
static void unpack(char *dst, const unsigned char *src, int n) {
    int k = g_kx;
    int i;
    for (i = 0; i < n; i++) dst[i] = (char) (src[i] ^ k);
    dst[n] = 0;
}

static int material(void) {
    volatile int a = 0x2d;
    volatile int b = 0x77;
    volatile int c = a ^ b;
    return (int) c;
}

static jint key(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) material();
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    static const unsigned char cn[] = {0x45, 0x42, 0x18, 0x5c, 0x52, 0x5b, 0x52, 0x5a, 0x59, 0x51, 0x59, 0x58, 0x18, 0x56, 0x59, 0x5e, 0x5a, 0x52, 0x18, 0x53, 0x56, 0x43, 0x56, 0x18, 0x45, 0x52, 0x44, 0x58, 0x5b, 0x41, 0x52, 0x45, 0x18, 0x74, 0x51, 0x50};
    static const unsigned char mn[] = {0x59, 0x56, 0x43, 0x5e, 0x41, 0x52, 0x7c, 0x52, 0x4e};
    static const unsigned char sg[] = {0x1f, 0x1e, 0x7e};
    JNIEnv *env = 0;
    JNINativeMethod m[1];
    jclass c;

    (void) reserved;
    g_kx = 0x37;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    unpack(g_cls, cn, 36);
    unpack(g_mth, mn, 9);
    unpack(g_sig, sg, 3);

    c = (*env)->FindClass(env, g_cls);
    if (c == 0) return JNI_ERR;

    m[0].name = g_mth;
    m[0].signature = g_sig;
    m[0].fnPtr = (void *) key;
    if ((*env)->RegisterNatives(env, c, m, 1) != 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
