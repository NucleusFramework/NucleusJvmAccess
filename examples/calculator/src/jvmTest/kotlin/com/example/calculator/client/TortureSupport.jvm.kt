package com.example.calculator.client

actual class Thread actual constructor(body: () -> Unit) {
    // A JVM-like 8 MB stack, as the iOS threads (deep JVM <-> app nesting recurses on both sides)
    private val thread = java.lang.Thread(null, body, "test", 8L * 1024 * 1024)

    actual fun start() = thread.start()
    actual fun join() = thread.join()
    actual fun join(millis: Long): Boolean {
        thread.join(millis)
        return !thread.isAlive
    }

    actual companion object {
        actual fun sleep(millis: Long) = java.lang.Thread.sleep(millis)
        actual fun sleep(millis: Long, nanos: Int) = java.lang.Thread.sleep(millis, nanos)
        actual fun yield() = java.lang.Thread.yield()
    }
}

actual class WeakReference<T : Any> actual constructor(referent: T) {
    private val reference = java.lang.ref.WeakReference(referent)
    actual fun get(): T? = reference.get()
}

internal actual fun platformGc() {
    Runtime.getRuntime().gc()
    java.lang.System.runFinalization()
}

internal actual fun platformGetenv(name: String): String? = java.lang.System.getenv(name)

internal actual fun hostJvmVersion(): String? = Runtime.version().toString()

private val missing: String? get() = java.lang.System.getProperty("nja.missing.property")

internal actual fun appFaultStorm(count: Int): Int {
    var caught = 0
    repeat(count) {
        try {
            // A platform-typed receiver: no Kotlin null check, HotSpot's compiled code faults on the null (its own SIGSEGV)
            java.lang.System.getProperty("nja.missing.property").length
        } catch (e: NullPointerException) {
            caught++
        }
    }
    return caught + (if (missing == null) 0 else 0)
}
