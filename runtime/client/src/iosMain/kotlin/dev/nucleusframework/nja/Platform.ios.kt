@file:OptIn(ExperimentalForeignApi::class, NjaInternal::class, ObsoleteWorkersApi::class, ExperimentalNativeApi::class)

package dev.nucleusframework.nja

import dev.nucleusframework.nja.jni.JNIEnvVar
import dev.nucleusframework.nja.jni.JNINativeMethod
import dev.nucleusframework.nja.jni.JNI_CreateJavaVM
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
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.ThreadLocal
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.ref.createCleaner

internal actual class NjaLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
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

internal actual fun startTransport(): Transport = IosJvm.apply { start() }

/**
 * The JVM (jdk-mobile's Zero, linked into the app) in the app's process: the app's threads call it through JNI,
 * attached on their first call. Its java.home is `<app>/lib` (the VM sets it itself), the façades' class path
 * `<app>/nja/classpath`.
 */
private object IosJvm : Transport {
    private const val LINK_CLASS = "dev/nucleusframework/nja/server/IosLink"

    private var vm: CPointer<JavaVMVar>? = null
    private var linkClass: jclass? = null
    private var dispatch: jmethodID? = null

    fun start() {
        val bundle = NSBundle.mainBundle.bundlePath
        val classPathDir = "$bundle/nja/classpath"
        @Suppress("UNCHECKED_CAST")
        val jars = (NSFileManager.defaultManager.contentsOfDirectoryAtPath(classPathDir, null) as? List<String>).orEmpty()
            .filter { it.endsWith(".jar") }.sorted().joinToString(":") { "$classPathDir/$it" }
        val options = listOf("-Djava.class.path=$jars") + readOptions("$bundle/nja/jvm.options")
        memScoped {
            val vmOptions = allocArray<JavaVMOption>(options.size)
            options.forEachIndexed { i, option -> vmOptions[i].optionString = option.cstr.ptr }
            val args = alloc<JavaVMInitArgs>()
            args.version = JNI_VERSION_21
            args.nOptions = options.size
            args.options = vmOptions
            args.ignoreUnrecognized = 0u
            val vmOut = alloc<kotlinx.cinterop.CPointerVar<JavaVMVar>>()
            val envOut = alloc<COpaquePointerVar>()
            val result = JNI_CreateJavaVM(vmOut.ptr, envOut.ptr, args.ptr)
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
            checkException(env, "NJA: IosLink.dispatch is missing")
            fns.DeleteLocalRef!!(env, local)
        }
    }

    private fun readOptions(path: String): List<String> {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return emptyList()
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

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

/** IosLink.upcall: the JVM calls one of the app's callbacks, on the thread of the call that passed it. */
private fun upcall(env: CPointer<JNIEnvVar>?, @Suppress("UNUSED_PARAMETER") cls: jclass?, request: jbyteArray?): jbyteArray? {
    val e = env!!
    val fns = e.pointed.value!!.pointed
    val answer = try {
        NjaRuntime.serveCallback(toByteArray(e, request))
    } catch (t: Throwable) {
        val output = WireWriter(64)
        output.writeByte(Protocol.CALLBACK_THROW)
        output.writeLong(0)
        output.writeString(className(t))
        output.writeString(t.message)
        output.toByteArray()
    }
    val result = fns.NewByteArray!!(e, answer.size)
    if (answer.isNotEmpty()) answer.usePinned { fns.SetByteArrayRegion!!(e, result, 0, answer.size, it.addressOf(0)) }
    return result
}
