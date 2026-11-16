@file:OptIn(ExperimentalForeignApi::class, NjaInternal::class, ObsoleteWorkersApi::class, ExperimentalNativeApi::class)

package dev.nucleusframework.nja

import dev.nucleusframework.nja.jni.JNIEnvVar
import dev.nucleusframework.nja.jni.JNINativeMethod
import dev.nucleusframework.nja.jni.JNI_OK
import dev.nucleusframework.nja.jni.JNI_VERSION_21
import dev.nucleusframework.nja.jni.JavaVMInitArgs
import dev.nucleusframework.nja.jni.JavaVMOption
import dev.nucleusframework.nja.jni.JavaVMVar
import dev.nucleusframework.nja.jni.jbyteArray
import dev.nucleusframework.nja.jni.jclass
import dev.nucleusframework.nja.jni.jmethodID
import dev.nucleusframework.nja.jni.jvalue
import dev.nucleusframework.nja.jni.nja_attach_current_thread
import dev.nucleusframework.nja.jni.nja_create_java_vm
import dev.nucleusframework.nja.jni.nja_executable_path
import dev.nucleusframework.nja.jni.nja_file_kind
import dev.nucleusframework.nja.jni.nja_getenv
import dev.nucleusframework.nja.jni.nja_graal_available
import dev.nucleusframework.nja.jni.nja_graal_call
import dev.nucleusframework.nja.jni.nja_graal_free_reply
import dev.nucleusframework.nja.jni.nja_graal_start
import dev.nucleusframework.nja.jni.nja_list_directory
import dev.nucleusframework.nja.jni.nja_load_runtime
import dev.nucleusframework.nja.jni.nja_lock_free
import dev.nucleusframework.nja.jni.nja_lock_lock
import dev.nucleusframework.nja.jni.nja_lock_new
import dev.nucleusframework.nja.jni.nja_lock_unlock
import dev.nucleusframework.nja.jni.nja_read_file
import dev.nucleusframework.nja.jni.nja_symbol
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner

internal actual class NjaLock actual constructor() {
    private val lock: COpaquePointer = nja_lock_new() ?: throw OutOfMemoryError()

    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(lock) { nja_lock_free(it) }

    actual fun lock() = nja_lock_lock(lock)
    actual fun unlock() = nja_lock_unlock(lock)
}

@ThreadLocal
private object ThreadState {
    val scopes = ArrayList<CallScope>(4)
    var env: CPointer<JNIEnvVar>? = null
}

internal actual fun currentScopes(): ArrayList<CallScope> = ThreadState.scopes

internal actual fun className(e: Throwable): String = e::class.qualifiedName ?: "Throwable"

internal actual fun startDaemon(name: String, body: () -> Unit) {
    Worker.start(name = name).execute(TransferMode.SAFE, { body }) { it() }
}

internal actual fun createCleaner(ref: JvmRef): Any = createCleaner(ref) { it.close() }

/**
 * The runtime: linked into the binary (iOS), or a shared library loaded from the runtime's directory (macOS, Linux,
 * Windows): the façades' native image if it is there, else a JDK's JVM.
 */
internal actual fun startTransport(): Transport {
    if (nja_graal_available() != 0) return NativeGraal.apply { start() }
    if (nja_symbol("JNI_CreateJavaVM") == null) {
        val image = "${RuntimeFiles.directory}/${RuntimeFiles.imageLibrary}"
        if (RuntimeFiles.isFile(image)) {
            RuntimeFiles.load(image, null)
            return NativeGraal.apply { start() }
        }
        val javaHome = RuntimeFiles.javaHome()
        RuntimeFiles.load("$javaHome/${RuntimeFiles.jvmLibrary}", if (RuntimeFiles.isWindows) "$javaHome/bin" else null)
    }
    return NativeJvm.apply { start() }
}

/**
 * Where the runtime is: `NJA_HOME`, or the `nja` directory next to the executable (an iOS app's bundle: the
 * executable is at its root). There, the façades' native image (`libnja.dylib`, `libnja.so`, `nja.dll`), or the JVM's
 * class path (`classpath/`), options (`jvm.options`) and, optionally, java.home (`runtime/`, else `JAVA_HOME`'s).
 */
private object RuntimeFiles {
    val isWindows = Platform.osFamily == OsFamily.WINDOWS

    val directory: String by lazy {
        getenv("NJA_HOME") ?: run {
            val executable = nja_executable_path()?.let { path -> path.toKString().also { free(path) } }
                ?: error("NJA: can't find the executable's path")
            "${executable.substring(0, executable.lastIndexOfAny(charArrayOf('/', '\\')))}/nja"
        }
    }

    val imageLibrary = when (Platform.osFamily) {
        OsFamily.MACOSX -> "libnja.dylib"
        OsFamily.WINDOWS -> "nja.dll"
        else -> "libnja.so"
    }

    val jvmLibrary = when (Platform.osFamily) {
        OsFamily.MACOSX -> "lib/server/libjvm.dylib"
        OsFamily.WINDOWS -> "bin/server/jvm.dll"
        else -> "lib/server/libjvm.so"
    }

    val classPathSeparator = if (isWindows) ";" else ":"

    fun javaHome(): String {
        val bundled = "$directory/runtime"
        if (nja_file_kind(bundled) == 2) return bundled
        return getenv("JAVA_HOME") ?: error(
            "NJA: no runtime in $directory: neither the façades' native image ($imageLibrary) nor a JVM (runtime/, or JAVA_HOME)",
        )
    }

    fun isFile(path: String) = nja_file_kind(path) == 1

    fun load(path: String, dependencies: String?) {
        val failure = nja_load_runtime(path, dependencies)
        check(failure == null) { "NJA: can't load $path: ${failure?.toKString()}" }
    }

    fun list(path: String): List<String> {
        val names = nja_list_directory(path) ?: return emptyList()
        try {
            return names.toKString().lines().filter { it.isNotEmpty() }
        } finally {
            free(names)
        }
    }

    fun read(path: String): String? {
        val content = nja_read_file(path) ?: return null
        try {
            return content.toKString()
        } finally {
            free(content)
        }
    }

    private fun getenv(name: String): String? {
        val value = nja_getenv(name) ?: return null
        try {
            return value.toKString().takeIf { it.isNotEmpty() }
        } finally {
            free(value)
        }
    }
}

/**
 * The façades compiled into a GraalVM native image, linked into the app or loaded: the app's threads call its entry
 * points, attached to its isolate on their first call, and it calls the app back on the same thread.
 */
private object NativeGraal : Transport {
    fun start() {
        val result = nja_graal_start(staticCFunction(::graalUpcall))
        check(result == 0) { "NJA: the native image's isolate failed to start: $result" }
    }

    override fun transact(message: ByteArray, length: Int): ByteArray = memScoped {
        val replyLength = alloc<IntVar>()
        val reply = message.usePinned { nja_graal_call(if (length > 0) it.addressOf(0) else null, length, replyLength.ptr) }
            ?: error("NJA: can't attach this thread to the native image")
        try {
            reply.reinterpret<ByteVar>().readBytes(replyLength.value)
        } finally {
            nja_graal_free_reply(reply)
        }
    }
}

/** The native image calls one of the app's callbacks: [request] is the image's (it frees it), the answer the app's. */
private fun graalUpcall(request: CPointer<ByteVar>?, length: Int, answerLength: CPointer<IntVar>?): CPointer<ByteVar>? {
    val answer = serveUpcall(if (length > 0) request!!.readBytes(length) else ByteArray(0))
    answerLength!!.pointed.value = answer.size
    val buffer = malloc(maxOf(answer.size, 1).toULong())!!.reinterpret<ByteVar>()
    if (answer.isNotEmpty()) answer.usePinned { memcpy(buffer, it.addressOf(0), answer.size.toULong()) }
    return buffer
}

/**
 * The JVM in the app's process: jdk-mobile's Zero linked into an iOS app, or a JDK's libjvm loaded on the desktop. The
 * app's threads call it through JNI, attached on their first call. Its java.home is the VM's own (an iOS app's
 * `<app>/lib`, the JDK's), the façades' class path the runtime directory's `classpath/`.
 */
private object NativeJvm : Transport {
    private const val LINK_CLASS = "dev/nucleusframework/nja/server/JniLink"

    private var vm: CPointer<JavaVMVar>? = null
    private var linkClass: jclass? = null
    private var dispatch: jmethodID? = null

    fun start() {
        val classPathDir = "${RuntimeFiles.directory}/classpath"
        val jars = RuntimeFiles.list(classPathDir).filter { it.endsWith(".jar") }.sorted()
            .joinToString(RuntimeFiles.classPathSeparator) { "$classPathDir/$it" }
        val options = listOf("-Djava.class.path=$jars") + readOptions("${RuntimeFiles.directory}/jvm.options")
        memScoped {
            val vmOptions = allocArray<JavaVMOption>(options.size)
            options.forEachIndexed { i, option -> vmOptions[i].optionString = option.cstr.ptr }
            val args = alloc<JavaVMInitArgs>()
            args.version = JNI_VERSION_21
            args.nOptions = options.size
            args.options = vmOptions
            args.ignoreUnrecognized = 0u
            val vmOut = alloc<CPointerVar<JavaVMVar>>()
            val envOut = alloc<COpaquePointerVar>()
            val result = nja_create_java_vm(vmOut.ptr, envOut.ptr, args.ptr)
            check(result == JNI_OK) { "NJA: JNI_CreateJavaVM failed: $result" }
            vm = vmOut.value
            val env: CPointer<JNIEnvVar> = envOut.value!!.reinterpret()
            ThreadState.env = env
            val fns = env.pointed.value!!.pointed
            val local = fns.FindClass!!(env, LINK_CLASS.cstr.ptr)
            checkException(env, "NJA: can't find $LINK_CLASS (is nja-server on the class path?)")
            linkClass = fns.NewGlobalRef!!(env, local)
            val methods = allocArray<JNINativeMethod>(1)
            methods[0].name = "upcall".cstr.ptr
            methods[0].signature = "([B)[B".cstr.ptr
            methods[0].fnPtr = staticCFunction(::upcall)
            check(fns.RegisterNatives!!(env, linkClass, methods, 1) == JNI_OK) { "NJA: can't register the upcall" }
            dispatch = fns.GetStaticMethodID!!(env, linkClass, "dispatch".cstr.ptr, "([B)[B".cstr.ptr)
            checkException(env, "NJA: JniLink.dispatch is missing")
            fns.DeleteLocalRef!!(env, local)
        }
    }

    private fun readOptions(path: String): List<String> =
        RuntimeFiles.read(path).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** This thread's JNIEnv, attaching it (as a daemon, detached when it ends) on its first call. */
    fun env(): CPointer<JNIEnvVar> {
        ThreadState.env?.let { return it }
        val vm = vm ?: error("NJA: the JVM isn't started")
        val env = nja_attach_current_thread(vm, "nja-app") ?: error("NJA: can't attach this thread to the JVM")
        ThreadState.env = env
        return env
    }

    override fun transact(message: ByteArray, length: Int): ByteArray {
        val env = env()
        val fns = env.pointed.value!!.pointed
        // The thread has no Java frame: its local references live until a frame pops
        fns.PushLocalFrame!!(env, 8)
        try {
            val request = fns.NewByteArray!!(env, length)
            if (length > 0) message.usePinned { fns.SetByteArrayRegion!!(env, request, 0, length, it.addressOf(0)) }
            val reply = memScoped {
                val args = allocArray<jvalue>(1)
                args[0].l = request
                fns.CallStaticObjectMethodA!!(env, linkClass, dispatch, args)
            }
            checkException(env, "NJA: the JVM's dispatch failed")
            return toByteArray(env, reply)
        } finally {
            fns.PopLocalFrame!!(env, null)
        }
    }

    private fun checkException(env: CPointer<JNIEnvVar>, message: String) {
        val fns = env.pointed.value!!.pointed
        if (fns.ExceptionCheck!!(env).toInt() != 0) {
            fns.ExceptionDescribe!!(env)
            fns.ExceptionClear!!(env)
            throw IllegalStateException(message)
        }
    }
}

private fun toByteArray(env: CPointer<JNIEnvVar>, array: jbyteArray?): ByteArray {
    val fns = env.pointed.value!!.pointed
    val length = fns.GetArrayLength!!(env, array)
    val bytes = ByteArray(length)
    if (length > 0) bytes.usePinned { fns.GetByteArrayRegion!!(env, array, 0, length, it.addressOf(0)) }
    return bytes
}

/** JniLink.upcall: the JVM calls one of the app's callbacks, on the thread of the call that passed it. */
private fun upcall(env: CPointer<JNIEnvVar>?, @Suppress("UNUSED_PARAMETER") cls: jclass?, request: jbyteArray?): jbyteArray? {
    val e = env!!
    val fns = e.pointed.value!!.pointed
    val answer = serveUpcall(toByteArray(e, request))
    val result = fns.NewByteArray!!(e, answer.size)
    if (answer.isNotEmpty()) answer.usePinned { fns.SetByteArrayRegion!!(e, result, 0, answer.size, it.addressOf(0)) }
    return result
}
