// NJA Android bridge: runs HotSpot inside the app's process, next to ART.
//
// HotSpot and ART can't share a thread (both keep their thread state in TLS and guard zones on the stack), so every
// ART thread that calls into the JVM is paired with a HotSpot thread of its own. The pair shares a channel: a buffer
// and a futex word telling which side holds it. Messages strictly alternate between the two sides, so a callback
// (JVM -> app) and a nested call made from it (app -> JVM) are served recursively by the same pair, like a JNI call
// on a single thread would be.
//
// This library also holds HotSpot and the JDK's natives (linked in from their static archives). It is loaded
// RTLD_LOCAL by System.loadLibrary: its symbols never reach the process' global scope, where ART's live.

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <linux/futex.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#define TAG "NJA"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

enum {
    OWNER_CLIENT = 0, // the app's thread holds the buffer (writing a request, reading a reply)
    OWNER_SERVER = 1, // the JVM's thread holds it
    OWNER_EXIT = 2,   // the app's thread is gone: the JVM's one ends
};

// Spins before sleeping: a call into the JVM usually comes back within a few microseconds, far less than a futex wake
// up costs
#define SPIN_NANOS 60000

typedef struct Channel {
    _Atomic int owner;
    _Atomic int sleeping; // a side sleeps on the futex and must be woken
    int length;
    int capacity;
    uint8_t* buffer;
} Channel;

static JavaVM* hs_vm;  // HotSpot
static JavaVM* art_vm; // ART
static jclass hs_link_class;
static jmethodID hs_worker_loop;
static pthread_key_t channel_key;
static pthread_mutex_t start_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t start_cond = PTHREAD_COND_INITIALIZER;
static int start_state; // 0 starting, 1 started, -1 failed
static char start_error[512];

static long futex(_Atomic int* word, int op, int value) {
    return syscall(SYS_futex, (int*)word, op | FUTEX_PRIVATE_FLAG, value, NULL, NULL, 0);
}

static inline int64_t now_nanos(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

static inline void cpu_relax(void) {
#if defined(__aarch64__)
    __asm__ volatile("yield");
#elif defined(__x86_64__)
    __asm__ volatile("pause");
#endif
}

// Waits until the channel's owner isn't `current` anymore, returns the new owner
static int await_change(Channel* channel, int current) {
    int64_t deadline = 0;
    for (int i = 0;; i++) {
        int owner = atomic_load(&channel->owner);
        if (owner != current) return owner;
        cpu_relax();
        if ((i & 255) == 255) {
            int64_t now = now_nanos();
            if (deadline == 0) {
                deadline = now + SPIN_NANOS;
            } else if (now > deadline) {
                break;
            }
        }
    }
    for (;;) {
        // Sequentially consistent with hand_over: either it sees the sleeper, or the sleeper sees the new owner
        atomic_fetch_add(&channel->sleeping, 1);
        if (atomic_load(&channel->owner) == current) futex(&channel->owner, FUTEX_WAIT, current);
        atomic_fetch_sub(&channel->sleeping, 1);
        int owner = atomic_load(&channel->owner);
        if (owner != current) return owner;
    }
}

static void hand_over(Channel* channel, int owner) {
    atomic_store(&channel->owner, owner);
    if (atomic_load(&channel->sleeping) > 0) futex(&channel->owner, FUTEX_WAKE, 1);
}

static int ensure_capacity(Channel* channel, int length) {
    if (length <= channel->capacity) return 1;
    int capacity = channel->capacity;
    while (capacity < length) capacity *= 2;
    uint8_t* buffer = realloc(channel->buffer, (size_t)capacity);
    if (buffer == NULL) return 0;
    channel->buffer = buffer;
    channel->capacity = capacity;
    return 1;
}

// ---------------------------------------------------------------------------------------------------------------------
// The JVM's side

// The HotSpot thread paired with an app's thread: runs the server's worker loop on the channel until the app's thread
// is gone
static void* worker_main(void* arg) {
    Channel* channel = arg;
    JNIEnv* env;
    JavaVMAttachArgs attach = {JNI_VERSION_21, "nja-worker", NULL};
    if ((*hs_vm)->AttachCurrentThreadAsDaemon(hs_vm, (void**)&env, &attach) != JNI_OK) {
        LOGE("Can't attach a worker to the JVM");
        hand_over(channel, OWNER_CLIENT);
        return NULL;
    }
    (*env)->CallStaticVoidMethod(env, hs_link_class, hs_worker_loop, (jlong)(intptr_t)channel);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*hs_vm)->DetachCurrentThread(hs_vm);
    free(channel->buffer);
    free(channel);
    return NULL;
}

// NativeLink.await(channel): the request (or the callback's result), null once the app's thread is gone
static jbyteArray JNICALL hs_await(JNIEnv* env, jclass cls, jlong handle) {
    Channel* channel = (Channel*)(intptr_t)handle;
    int owner = await_change(channel, OWNER_CLIENT);
    if (owner == OWNER_EXIT) return NULL;
    jbyteArray message = (*env)->NewByteArray(env, channel->length);
    if (message != NULL) (*env)->SetByteArrayRegion(env, message, 0, channel->length, (const jbyte*)channel->buffer);
    return message;
}

// NativeLink.send(channel, message, length): hands the reply (or a callback's request) to the app's thread
static void JNICALL hs_send(JNIEnv* env, jclass cls, jlong handle, jbyteArray message, jint length) {
    Channel* channel = (Channel*)(intptr_t)handle;
    if (!ensure_capacity(channel, length)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/OutOfMemoryError"), "NJA channel buffer");
        return;
    }
    (*env)->GetByteArrayRegion(env, message, 0, length, (jbyte*)channel->buffer);
    channel->length = length;
    hand_over(channel, OWNER_CLIENT);
}

// ---------------------------------------------------------------------------------------------------------------------
// The app's side

static void channel_destructor(void* value) {
    Channel* channel = value;
    // The worker owns the channel from here on and frees it
    hand_over(channel, OWNER_EXIT);
}

static Channel* current_channel(void) {
    Channel* channel = pthread_getspecific(channel_key);
    if (channel != NULL) return channel;
    channel = calloc(1, sizeof(Channel));
    channel->capacity = 4096;
    channel->buffer = malloc((size_t)channel->capacity);
    atomic_store(&channel->owner, OWNER_CLIENT);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 8 * 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    int error = pthread_create(&thread, &attr, worker_main, channel);
    pthread_attr_destroy(&attr);
    if (error != 0) {
        free(channel->buffer);
        free(channel);
        return NULL;
    }
    pthread_setspecific(channel_key, channel);
    return channel;
}

static void throw_art(JNIEnv* env, const char* message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

// AndroidJvm.transact(message, length): sends a message to this thread's JVM worker, returns its answer (a reply or a
// callback's request)
static jbyteArray JNICALL art_transact(JNIEnv* env, jclass cls, jbyteArray message, jint length) {
    if (hs_vm == NULL) {
        throw_art(env, "The JVM isn't started");
        return NULL;
    }
    Channel* channel = current_channel();
    if (channel == NULL) {
        throw_art(env, "Can't start a JVM worker thread");
        return NULL;
    }
    if (!ensure_capacity(channel, length)) {
        throw_art(env, "Out of memory for the NJA channel");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, message, 0, length, (jbyte*)channel->buffer);
    channel->length = length;
    hand_over(channel, OWNER_SERVER);
    int owner = await_change(channel, OWNER_SERVER);
    if (owner != OWNER_CLIENT) {
        throw_art(env, "The JVM worker is gone");
        return NULL;
    }
    jbyteArray answer = (*env)->NewByteArray(env, channel->length);
    if (answer != NULL) (*env)->SetByteArrayRegion(env, answer, 0, channel->length, (const jbyte*)channel->buffer);
    return answer;
}

typedef struct StartArgs {
    char* class_path;
    char* link_class;
    int option_count;
    char** options;
} StartArgs;

static void start_finished(int state, const char* error) {
    pthread_mutex_lock(&start_lock);
    start_state = state;
    if (error != NULL) strncpy(start_error, error, sizeof(start_error) - 1);
    pthread_cond_broadcast(&start_cond);
    pthread_mutex_unlock(&start_lock);
}

static void describe_exception(JNIEnv* env, char* out, size_t size) {
    jthrowable throwable = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (throwable == NULL) return;
    jclass object = (*env)->FindClass(env, "java/lang/Object");
    jmethodID to_string = (*env)->GetMethodID(env, object, "toString", "()Ljava/lang/String;");
    jstring text = (*env)->CallObjectMethod(env, throwable, to_string);
    if (text != NULL) {
        const char* chars = (*env)->GetStringUTFChars(env, text, NULL);
        snprintf(out, size, "%s", chars);
        (*env)->ReleaseStringUTFChars(env, text, chars);
    }
    (*env)->ExceptionClear(env);
}

// The VM's output (warnings, crash reports...) goes to logcat: an app's standard streams go nowhere
static jint JNICALL vm_vfprintf(FILE* stream, const char* format, va_list args) {
    return __android_log_vprint(stream == stderr ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, "NJA-JVM", format, args);
}

static void JNICALL vm_exit(jint code) {
    LOGE("The JVM exits the process (code %d)", code);
}

static void JNICALL vm_abort(void) {
    LOGE("The JVM aborts the process");
}

static JNINativeMethod hs_methods[] = {
    {"await", "(J)[B", (void*)hs_await},
    {"send", "(J[BI)V", (void*)hs_send},
};

// The thread that creates HotSpot: one of ours (not ART's), with a large stack. It stays parked once the VM is up, as
// HotSpot's main thread
static void* vm_main(void* arg) {
    StartArgs* start = arg;
    int count = start->option_count + 4;
    JavaVMOption* options = calloc((size_t)count, sizeof(JavaVMOption));
    size_t length = strlen(start->class_path) + 32;
    char* class_path = malloc(length);
    snprintf(class_path, length, "-Djava.class.path=%s", start->class_path);
    options[0].optionString = class_path;
    for (int i = 0; i < start->option_count; i++) options[i + 1].optionString = start->options[i];
    options[count - 3].optionString = "vfprintf";
    options[count - 3].extraInfo = (void*)vm_vfprintf;
    options[count - 2].optionString = "exit";
    options[count - 2].extraInfo = (void*)vm_exit;
    options[count - 1].optionString = "abort";
    options[count - 1].extraInfo = (void*)vm_abort;
    JavaVMInitArgs init = {JNI_VERSION_21, count, options, JNI_FALSE};
    JNIEnv* env;
    JavaVM* vm;
    // The HotSpot linked into this library (bound locally: ART's JNI_CreateJavaVM is another one)
    jint result = JNI_CreateJavaVM(&vm, (void**)&env, &init);
    if (result != JNI_OK) {
        char error[64];
        snprintf(error, sizeof(error), "JNI_CreateJavaVM failed: %d", result);
        start_finished(-1, error);
        return NULL;
    }
    char error[512] = "";
    jclass link = (*env)->FindClass(env, start->link_class);
    if (link == NULL) {
        describe_exception(env, error, sizeof(error));
        start_finished(-1, error[0] ? error : "Can't find the NJA link class");
        return NULL;
    }
    if ((*env)->RegisterNatives(env, link, hs_methods, 2) != JNI_OK) {
        describe_exception(env, error, sizeof(error));
        start_finished(-1, error[0] ? error : "Can't register the NJA natives");
        return NULL;
    }
    hs_worker_loop = (*env)->GetStaticMethodID(env, link, "workerLoop", "(J)V");
    jmethodID boot = (*env)->GetStaticMethodID(env, link, "boot", "()V");
    if (hs_worker_loop == NULL || boot == NULL) {
        describe_exception(env, error, sizeof(error));
        start_finished(-1, error[0] ? error : "The NJA link class lacks workerLoop or boot");
        return NULL;
    }
    (*env)->CallStaticVoidMethod(env, link, boot);
    if ((*env)->ExceptionCheck(env)) {
        describe_exception(env, error, sizeof(error));
        start_finished(-1, error);
        return NULL;
    }
    hs_link_class = (*env)->NewGlobalRef(env, link);
    hs_vm = vm;
    start_finished(1, NULL);
    // HotSpot's main thread: never returns (destroying the VM isn't supported)
    for (;;) pause();
    return NULL;
}

// AndroidJvm.start(javaHome, classPath, linkClass, options): creates the JVM (once per process), returns null or the
// reason it failed
static jstring JNICALL art_start(JNIEnv* env, jclass cls, jstring java_home, jstring class_path, jstring link_class,
                                 jobjectArray options) {
    pthread_mutex_lock(&start_lock);
    if (start_state == 1) {
        pthread_mutex_unlock(&start_lock);
        return NULL;
    }
    if (start_state == -1) {
        pthread_mutex_unlock(&start_lock);
        return (*env)->NewStringUTF(env, start_error);
    }
    pthread_mutex_unlock(&start_lock);

    // The mobile port's java.home (see openjdk-mobile.patch, os_linux.cpp)
    const char* home = (*env)->GetStringUTFChars(env, java_home, NULL);
    setenv("JAVA_HOME", home, 1);

    (*env)->ReleaseStringUTFChars(env, java_home, home);

    StartArgs* start = calloc(1, sizeof(StartArgs));
    const char* chars = (*env)->GetStringUTFChars(env, class_path, NULL);
    start->class_path = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, class_path, chars);
    chars = (*env)->GetStringUTFChars(env, link_class, NULL);
    start->link_class = strdup(chars);
    (*env)->ReleaseStringUTFChars(env, link_class, chars);
    start->option_count = (*env)->GetArrayLength(env, options);
    start->options = calloc((size_t)start->option_count + 1, sizeof(char*));
    for (int i = 0; i < start->option_count; i++) {
        jstring option = (*env)->GetObjectArrayElement(env, options, i);
        chars = (*env)->GetStringUTFChars(env, option, NULL);
        start->options[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, option, chars);
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    int error = pthread_create(&thread, &attr, vm_main, start);
    pthread_attr_destroy(&attr);
    if (error != 0) return (*env)->NewStringUTF(env, "Can't create the JVM's thread");

    pthread_mutex_lock(&start_lock);
    while (start_state == 0) pthread_cond_wait(&start_cond, &start_lock);
    int state = start_state;
    pthread_mutex_unlock(&start_lock);
    return state == 1 ? NULL : (*env)->NewStringUTF(env, start_error);
}

static JNINativeMethod art_methods[] = {
    {"start", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;",
     (void*)art_start},
    {"transact", "([BI)[B", (void*)art_transact},
};

// What the VM writes to the standard streams before its vfprintf hook applies (early errors) reaches logcat too: an
// app's standard streams go nowhere
static void* forward_stream(void* arg) {
    int fd = (int)(intptr_t)arg;
    char buffer[1024];
    size_t used = 0;
    for (;;) {
        ssize_t n = read(fd, buffer + used, sizeof(buffer) - 1 - used);
        if (n <= 0) return NULL;
        used += (size_t)n;
        buffer[used] = 0;
        char* start = buffer;
        char* newline;
        while ((newline = strchr(start, '\n')) != NULL) {
            *newline = 0;
            __android_log_write(ANDROID_LOG_WARN, "NJA-JVM", start);
            start = newline + 1;
        }
        used = strlen(start);
        if (used == sizeof(buffer) - 1) {
            __android_log_write(ANDROID_LOG_WARN, "NJA-JVM", start);
            used = 0;
        } else {
            memmove(buffer, start, used);
        }
    }
}

static void forward_standard_streams(void) {
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(fds[1], STDOUT_FILENO);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    pthread_t thread;
    if (pthread_create(&thread, NULL, forward_stream, (void*)(intptr_t)fds[0]) == 0) pthread_detach(thread);
}

// Called by ART's System.loadLibrary
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    art_vm = vm;
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "dev/nucleusframework/nja/AndroidJvm");
    if (cls == NULL) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, art_methods, 2) != JNI_OK) return JNI_ERR;
    pthread_key_create(&channel_key, channel_destructor);
    forward_standard_streams();
    return JNI_VERSION_1_6;
}
