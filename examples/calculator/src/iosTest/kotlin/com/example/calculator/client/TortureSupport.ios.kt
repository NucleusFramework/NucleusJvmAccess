@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, NativeRuntimeApi::class)

package com.example.calculator.client

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.getenv
import platform.posix.nanosleep
import platform.posix.pthread_attr_init
import platform.posix.pthread_attr_setstacksize
import platform.posix.pthread_attr_t
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import platform.posix.sched_yield
import platform.posix.timespec
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

private class Runner(val body: () -> Unit) {
    val done = AtomicBoolean(false)

    fun run() {
        try {
            body()
        } catch (e: Throwable) {
            println("Exception in thread: $e")
            e.printStackTrace()
        } finally {
            done.store(true)
        }
    }
}

/** A pthread with a JVM-like 8 MB stack (iOS' default for secondary threads is 512 KB). */
actual class Thread actual constructor(body: () -> Unit) {
    private val runner = Runner(body)
    private val handle = nativeHeap.alloc<pthread_tVar>()
    private var started = false
    private var joined = false

    actual fun start() {
        check(!started) { "Thread already started" }
        started = true
        memScoped {
            val attr = alloc<pthread_attr_t>()
            pthread_attr_init(attr.ptr)
            pthread_attr_setstacksize(attr.ptr, 8uL * 1024uL * 1024uL)
            val ref = StableRef.create(runner)
            val entry = staticCFunction { arg: COpaquePointer? ->
                val r = arg!!.asStableRef<Runner>()
                r.get().run()
                r.dispose()
                null as COpaquePointer?
            }
            check(pthread_create(handle.ptr, attr.ptr, entry, ref.asCPointer()) == 0) { "pthread_create failed" }
        }
    }

    actual fun join() {
        if (!started || joined) return
        pthread_join(handle.value, null)
        joined = true
    }

    actual fun join(millis: Long): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (!runner.done.load()) {
            if (System.currentTimeMillis() > deadline) return false
            sleep(1)
        }
        join()
        return true
    }

    actual companion object {
        actual fun sleep(millis: Long) = sleep(millis, 0)

        actual fun sleep(millis: Long, nanos: Int) {
            memScoped {
                val ts = alloc<timespec>()
                ts.tv_sec = millis / 1000
                ts.tv_nsec = (millis % 1000) * 1_000_000 + nanos
                nanosleep(ts.ptr, null)
            }
        }

        actual fun yield() {
            sched_yield()
        }
    }
}

actual class WeakReference<T : Any> actual constructor(referent: T) {
    private val reference = kotlin.native.ref.WeakReference(referent)
    actual fun get(): T? = reference.get()
}

internal actual fun platformGc() = GC.collect()

internal actual fun platformGetenv(name: String): String? = getenv(name)?.toKString()

internal actual fun hostJvmVersion(): String? = null

internal actual fun appFaultStorm(count: Int): Int {
    var caught = 0
    val holder: Any? = if (count < 0) "never" else null
    repeat(count) {
        try {
            holder!!.hashCode()
        } catch (e: NullPointerException) {
            caught++
        }
    }
    return caught
}
