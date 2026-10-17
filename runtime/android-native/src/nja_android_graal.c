// NJA Android bridge for a GraalVM native image: the façades compiled ahead of time, linked into this library with
// Substrate VM. Unlike HotSpot, an isolate shares the app's threads: ART's threads attach to it and call its entry
// points directly, and it calls the app back on the same thread, through JNI.
//
// Same natives as nja_android.c (AndroidJvm.start and transact): the app's side doesn't change.

#include <jni.h>

#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "NJA"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct graal_isolate_t graal_isolate_t;
typedef struct graal_isolatethread_t graal_isolatethread_t;

extern int graal_create_isolate(void* params, graal_isolate_t** isolate, graal_isolatethread_t** thread);
extern int graal_attach_thread(graal_isolate_t* isolate, graal_isolatethread_t** thread);
extern graal_isolatethread_t* graal_get_current_thread(graal_isolate_t* isolate);
extern int graal_detach_thread(graal_isolatethread_t* thread);
extern void* nja_graal_dispatch(graal_isolatethread_t* thread, const void* message, int length, int* reply_length);
extern void nja_graal_set_upcall(graal_isolatethread_t* thread, void* upcall);

static JavaVM* art_vm;
static jclass jvm_class;      // AndroidJvm
static jmethodID upcall_method; // AndroidJvm.upcall(byte[]): byte[]
static graal_isolate_t* isolate;
static pthread_mutex_t start_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_key_t thread_key; // the thread's isolate thread, detached when it exits

static void detach_thread(void* thread) {
    graal_detach_thread((graal_isolatethread_t*)thread);
}

// The calling thread's isolate thread, attaching it on its first call; NULL on failure
static graal_isolatethread_t* current_thread(void) {
    graal_isolatethread_t* thread = graal_get_current_thread(isolate);
    if (thread != NULL) return thread;
    if (graal_attach_thread(isolate, &thread) != 0) return NULL;
    pthread_setspecific(thread_key, thread);
    return thread;
}

// The image calls one of the app's callbacks, on the thread of the call: AndroidJvm.upcall. The request is the image's,
// the answer (malloc'ed) the image's to free.
static void* upcall(const void* request, int length, int* answer_length) {
    JNIEnv* env;
    if ((*art_vm)->GetEnv(art_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("A callback on a thread unknown to ART");
        abort();
    }
    jbyteArray array = (*env)->NewByteArray(env, length);
    (*env)->SetByteArrayRegion(env, array, 0, length, (const jbyte*)request);
    jbyteArray answer = (jbyteArray)(*env)->CallStaticObjectMethod(env, jvm_class, upcall_method, array);
    (*env)->DeleteLocalRef(env, array);
    if ((*env)->ExceptionCheck(env)) {
        // AndroidJvm.upcall encodes its callbacks' exceptions: this one is NJA's own
        (*env)->ExceptionDescribe(env);
        abort();
    }
    jsize size = (*env)->GetArrayLength(env, answer);
    void* buffer = malloc(size > 0 ? size : 1);
    (*env)->GetByteArrayRegion(env, answer, 0, size, (jbyte*)buffer);
    (*env)->DeleteLocalRef(env, answer);
    *answer_length = size;
    return buffer;
}

// AndroidJvm.start: creates the isolate (once); its arguments are the JVM's, unused. Null, or the error.
static jstring JNICALL art_start(JNIEnv* env, jclass cls, jstring java_home, jstring class_path, jstring link_class,
                                 jobjectArray options) {
    pthread_mutex_lock(&start_lock);
    if (isolate == NULL) {
        graal_isolatethread_t* thread = NULL;
        int result = graal_create_isolate(NULL, &isolate, &thread);
        if (result != 0) {
            isolate = NULL;
            pthread_mutex_unlock(&start_lock);
            char error[64];
            snprintf(error, sizeof(error), "graal_create_isolate failed: %d", result);
            return (*env)->NewStringUTF(env, error);
        }
        pthread_setspecific(thread_key, thread);
        nja_graal_set_upcall(thread, (void*)upcall);
    }
    pthread_mutex_unlock(&start_lock);
    return NULL;
}

// AndroidJvm.transact: serves a call on this thread; callbacks are served within it, through upcall
static jbyteArray JNICALL art_transact(JNIEnv* env, jclass cls, jbyteArray message, jint length) {
    graal_isolatethread_t* thread = current_thread();
    if (thread == NULL) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "NJA: can't attach to the isolate");
        return NULL;
    }
    void* request = malloc(length > 0 ? length : 1);
    (*env)->GetByteArrayRegion(env, message, 0, length, (jbyte*)request);
    int reply_length = 0;
    void* reply = nja_graal_dispatch(thread, request, length, &reply_length);
    free(request);
    jbyteArray result = (*env)->NewByteArray(env, reply_length);
    (*env)->SetByteArrayRegion(env, result, 0, reply_length, (const jbyte*)reply);
    free(reply);
    return result;
}

static JNINativeMethod art_methods[] = {
    {"start", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;", (void*)art_start},
    {"transact", "([BI)[B", (void*)art_transact},
};

// Called by ART's System.loadLibrary
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    art_vm = vm;
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "dev/nucleusframework/nja/AndroidJvm");
    if (cls == NULL) return JNI_ERR;
    jvm_class = (jclass)(*env)->NewGlobalRef(env, cls);
    upcall_method = (*env)->GetStaticMethodID(env, cls, "upcall", "([B)[B");
    if (upcall_method == NULL) return JNI_ERR;
    if ((*env)->RegisterNatives(env, cls, art_methods, 2) != JNI_OK) return JNI_ERR;
    pthread_key_create(&thread_key, detach_thread);
    return JNI_VERSION_1_6;
}
