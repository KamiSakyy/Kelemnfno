#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/ptrace.h>
#include <time.h>
#include <pthread.h>

#define KEY_LEN 18
#define DUMMY_SIZE 1024

// Mask components split
static const unsigned char M_A[KEY_LEN] = {0x54,0x32,0x11,0x78,0x22,0x65,0x43,0x19,0x88,0x71,0x24,0x90,0x55,0x17,0x33,0x44,0x55,0x66};
static const unsigned char M_B[KEY_LEN] = {0x5a,0x1b,0x3e,0x5b,0x0d,0x6c,0x7c,0x20,0xa0,0x4e,0x0a,0x81,0x6a,0x34,0x5b,0x2e,0x3d,0x0a};
static const unsigned char M_C[KEY_LEN] = {0x12,0x34,0x56,0x78,0x9a,0xbc,0xde,0xf0,0x0f,0xed,0xcb,0xa9,0x87,0x65,0x43,0x21,0x10,0x00};
static const unsigned char M_D[KEY_LEN] = {0x1c,0x5d,0x2b,0x5b,0xbf,0xdf,0xb1,0x17,0x27,0xc8,0xe5,0xb8,0xa2,0x0c,0x6b,0x0b,0x42,0x54};

// Obfuscated paths
static const unsigned char P_SU1[] = {0x08,0x54,0x5e,0x54,0x53,0x42,0x4a,0x08,0x45,0x4e,0x49,0x08,0x54,0x52,0x00};
static const unsigned char P_SU2[] = {0x08,0x54,0x5e,0x54,0x53,0x42,0x4a,0x08,0x5f,0x45,0x4e,0x49,0x08,0x54,0x52,0x00};
static const unsigned char P_SU3[] = {0x08,0x54,0x45,0x4e,0x49,0x08,0x54,0x52,0x00};
static const unsigned char P_FR1[] = {0x08,0x43,0x46,0x53,0x46,0x08,0x4b,0x48,0x44,0x46,0x4b,0x08,0x53,0x4a,0x57,0x08,0x41,0x55,0x4e,0x43,0x46,0x0a,0x54,0x42,0x55,0x51,0x42,0x55,0x00};
static const unsigned char P_DBG1[] = {0x08,0x53,0x46,0x4a,0x42,0x08,0x54,0x53,0x4a,0x53,0x52,0x54,0x00};
static const unsigned char P_DBG2[] = {0x08,0x53,0x46,0x4a,0x42,0x08,0x54,0x53,0x4a,0x53,0x52,0x54,0x08,0x53,0x4a,0x57,0x00};

// Dummy data to inflate size and mimic libc++
static const unsigned char DUMMY_DATA_1[DUMMY_SIZE] = {
    0x7f,0x45,0x4c,0x46,0x02,0x01,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x03,0x00,0x3e,0x00,0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x40,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,
    0x00,0x00,0x00,0x00,0x40,0x00,0x38,0x00,0x0a,0x00,0x40,0x00,0x1e,0x00,0x1d,0x00
};
static const char DUMMY_STR_1[] = "std::__1::basic_string<char, std::__1::char_traits<char>, std::__1::allocator<char> >";
static const char DUMMY_STR_2[] = "std::__1::vector<int, std::__1::allocator<int> >";
static const char DUMMY_STR_3[] = "std::__1::__shared_ptr_emplace<YoruGuard, std::__1::allocator<YoruGuard> >";
static const char DUMMY_STR_4[] = "libc++_shared.so - LLVM libc++ - NDK r26 - built 2024-03-12";
static const char DUMMY_STR_5[] = "__cxa_throw::__cxa_begin_catch::__cxa_end_catch::__gxx_personality_v0";
static const char DUMMY_STR_6[] = "_ZNSt3__16vectorIiNS_9allocatorIiEEE9push_backERKi";
static const char DUMMY_STR_7[] = "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEC1Ev";

static volatile int g_counter = 0;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

static void __attribute__((noinline)) derive_key(unsigned char *out) {
    volatile unsigned char v1 = 0x5a;
    volatile unsigned char v2 = 0x27;
    volatile unsigned char v3 = 0x33;
    for (int i = 0; i < KEY_LEN; i++) {
        unsigned char a = M_A[i] ^ M_B[i] ^ v1;
        unsigned char b = M_C[i] ^ M_D[i] ^ v2;
        out[i] = (a ^ b ^ v3 ^ (i * 7)) & 0xff;
        g_counter += out[i];
    }
}

static void __attribute__((noinline)) derive_key_v2(unsigned char *out, int seed) {
    unsigned char tmp[KEY_LEN];
    derive_key(tmp);
    for (int i = 0; i < KEY_LEN; i++) {
        out[i] = (tmp[i] ^ (seed & 0xff) ^ ((seed >> 8) & 0xff) ^ i) & 0xff;
    }
}

static void decode_path(const unsigned char *src, char *dst) {
    volatile unsigned char key = 0x27;
    int i = 0;
    while (src[i] != 0x00) {
        dst[i] = (char)(src[i] ^ key);
        i++;
    }
    dst[i] = '\0';
}

static int hex_val(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

// Anti-debug: check tracer
static int __attribute__((noinline)) check_tracer() {
    char path[64];
    decode_path(P_DBG1, path);
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    char buf[1024];
    ssize_t len = read(fd, buf, sizeof(buf)-1);
    close(fd);
    if (len <= 0) return 0;
    buf[len] = '\0';
    char *tracer = strstr(buf, "TracerPid:");
    if (!tracer) return 0;
    int pid = atoi(tracer + 10);
    return pid != 0;
}

// Anti-debug: ptrace
static int __attribute__((noinline)) check_ptrace() {
    if (ptrace(PTRACE_TRACEME, 0, 1, 0) == -1) {
        return 1;
    }
    ptrace(PTRACE_DETACH, 0, 1, 0);
    return 0;
}

static int __attribute__((noinline)) check_timing() {
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    volatile int sum = 0;
    for (int i = 0; i < 100000; i++) sum += i;
    clock_gettime(CLOCK_MONOTONIC, &end);
    long diff = (end.tv_sec - start.tv_sec) * 1000000000L + (end.tv_nsec - start.tv_nsec);
    return diff > 50000000L;
}

// Dummy functions to inflate and mimic libc++
void __attribute__((visibility("default"))) _ZNSt3__16vectorIiNS_9allocatorIiEEE9push_backERKi() { pthread_mutex_lock(&g_mutex); g_counter++; pthread_mutex_unlock(&g_mutex); }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEC1Ev() { volatile char *p = (char*)DUMMY_STR_1; (void)p; }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED1Ev() { volatile char *p = (char*)DUMMY_STR_2; (void)p; }
void __attribute__((visibility("default"))) _ZNSt3__119__shared_weak_count4__getEv() { volatile int x = g_counter; (void)x; }
void __attribute__((visibility("default"))) _ZNKSt3__16vectorIiNS_9allocatorIiEEE4sizeEv() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorIiNS_9allocatorIiEEE5clearEv() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorIiNS_9allocatorIiEEE7reserveEm() { }
void __attribute__((visibility("default"))) __cxa_throw() { }
void __attribute__((visibility("default"))) __cxa_begin_catch() { }
void __attribute__((visibility("default"))) __cxa_end_catch() { }
void __attribute__((visibility("default"))) __gxx_personality_v0() { }
void __attribute__((visibility("default"))) _ZSt9terminatev() { }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6appendEPKc() { }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE6assignEPKc() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorIcNS_9allocatorIcEEE9push_backERKc() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorIcNS_9allocatorIcEEE4sizeEv() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorIcNS_9allocatorIcEEE5clearEv() { }
void __attribute__((visibility("default"))) _ZNSt3__16__shared_ptr_emplaceINS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEES4_E5__getEv() { }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEaSERKS5_() { }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEC2ERKS5_() { }
void __attribute__((visibility("default"))) _ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEED2Ev() { }
void __attribute__((visibility("default"))) _ZNSt3__16vectorINS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEENS4_IS6_EEE9push_backERKS6_() { }

static jstring JNICALL native_decrypt(JNIEnv *env, jclass clazz, jstring hexStr) {
    (void)clazz;
    if (!hexStr) return NULL;
    const char *chars = (*env)->GetStringUTFChars(env, hexStr, NULL);
    if (!chars) return NULL;
    size_t in_len = strlen(chars);
    char *clean = (char *)malloc(in_len + 1);
    if (!clean) { (*env)->ReleaseStringUTFChars(env, hexStr, chars); return NULL; }
    size_t clean_len = 0;
    for (size_t i = 0; i < in_len; i++) if (chars[i] != '-') clean[clean_len++] = chars[i];
    clean[clean_len] = '\0';
    (*env)->ReleaseStringUTFChars(env, hexStr, chars);
    if (clean_len == 0 || (clean_len % 2) != 0) { free(clean); return (*env)->NewStringUTF(env, ""); }
    size_t out_len = clean_len / 2;
    unsigned char *buf = (unsigned char *)malloc(out_len + 1);
    if (!buf) { free(clean); return NULL; }
    unsigned char key[KEY_LEN];
    derive_key(key);
    for (size_t i = 0; i < out_len; i++) {
        int h = hex_val(clean[i*2]);
        int l = hex_val(clean[i*2+1]);
        if (h < 0 || l < 0) { free(clean); free(buf); return (*env)->NewStringUTF(env, ""); }
        unsigned char b = (unsigned char)((h << 4) | l);
        buf[i] = (unsigned char)(b ^ key[i % KEY_LEN] ^ (i & 0x0f));
    }
    buf[out_len] = '\0';
    free(clean);
    // Additional obfuscation: reverse XOR with second key for long strings
    if (out_len > 10) {
        unsigned char key2[KEY_LEN];
        derive_key_v2(key2, out_len);
        for (size_t i = 0; i < out_len; i++) {
            if (i % 3 == 0) buf[i] ^= key2[i % KEY_LEN] & 0x0f;
        }
        // undo for now (keep compatibility) - actually we want simple XOR for compat
        for (size_t i = 0; i < out_len; i++) {
            if (i % 3 == 0) buf[i] ^= key2[i % KEY_LEN] & 0x0f;
        }
    }
    jstring result = (*env)->NewStringUTF(env, (const char *)buf);
    // Secure clear
    memset(buf, 0, out_len);
    free(buf);
    return result;
}

static jboolean JNICALL native_check(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    char path[128];
    const unsigned char *enc_paths[] = {P_SU1,P_SU2,P_SU3,P_FR1,P_DBG2,NULL};
    for (int i = 0; enc_paths[i] != NULL; i++) {
        decode_path(enc_paths[i], path);
        struct stat st;
        if (stat(path, &st) == 0) return JNI_FALSE;
    }
    if (check_tracer()) return JNI_FALSE;
    // Timing check disabled for compatibility (can cause false positives)
    // if (check_timing()) return JNI_FALSE;
    return JNI_TRUE;
}

static jboolean JNICALL native_check_debug(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (check_ptrace()) return JNI_FALSE;
    if (check_tracer()) return JNI_FALSE;
    return JNI_TRUE;
}

static jstring JNICALL native_dummy1(JNIEnv *env, jclass clazz, jstring s) {
    (void)clazz;
    // Dummy that looks like string op but does nothing
    return s;
}

static jint JNICALL native_dummy2(JNIEnv *env, jclass clazz, jint a, jint b) {
    (void)env; (void)clazz;
    return (a ^ b) + g_counter;
}

static jstring JNICALL native_get_lib_info(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, DUMMY_STR_4);
}

static const JNINativeMethod gMethods[] = {
    {"x", "(Ljava/lang/String;)Ljava/lang/String;", (void*)native_decrypt},
    {"c", "()Z", (void*)native_check},
    {"d", "()Z", (void*)native_check_debug},
    {"a", "(Ljava/lang/String;)Ljava/lang/String;", (void*)native_dummy1},
    {"b", "(II)I", (void*)native_dummy2},
    {"g", "()Ljava/lang/String;", (void*)native_get_lib_info}
};

__attribute__((visibility("default")))
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_VERSION_1_6;
    // Inflate counter with dummy data to avoid optimization
    for (int i = 0; i < DUMMY_SIZE; i++) g_counter += DUMMY_DATA_1[i % 64];
    g_counter += (int)strlen(DUMMY_STR_1) + (int)strlen(DUMMY_STR_2) + (int)strlen(DUMMY_STR_3);
    // Anti-debug early
    if (check_tracer()) {
        // Don't crash, just set flag
        g_counter = -1;
    }
    jclass cls = (*env)->FindClass(env, "com/tsuyu/line/Sec");
    if (cls != NULL) {
        (*env)->RegisterNatives(env, cls, gMethods, sizeof(gMethods)/sizeof(gMethods[0]));
        (*env)->DeleteLocalRef(env, cls);
    }
    // Also register dummy class to mimic libc++ symbols
    jclass dummy = (*env)->FindClass(env, "java/lang/String");
    (void)dummy;
    return JNI_VERSION_1_6;
}
