package dev.nucleusframework.nja

import dev.nucleusframework.nja.server.Link
import dev.nucleusframework.nja.server.NjaServer
import java.lang.ref.Cleaner
import java.util.concurrent.locks.ReentrantLock

internal actual class NjaLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}

private val scopes = ThreadLocal.withInitial { ArrayList<CallScope>(4) }

internal actual fun currentScopes(): ArrayList<CallScope> = scopes.get()!!

internal actual fun className(e: Throwable): String = e.javaClass.name

internal actual fun startDaemon(name: String, body: () -> Unit) {
    Thread(body, name).apply { isDaemon = true }.start()
}

private val cleaner = Cleaner.create()

internal actual fun createCleaner(ref: JvmRef): Any = Any().also { token -> cleaner.register(token) { ref.close() } }

internal actual fun startTransport(): Transport = JvmTransport

/**
 * A JVM app (the desktop): the façades run in this very JVM, on its class path, so a call is a plain call into the
 * server's dispatcher on the calling thread, and a callback a plain call back into the app's, as on iOS without JNI.
 * The messages are still encoded: the app sees the same proxies as on the mobile platforms.
 */
private object JvmTransport : Transport {
    private val link = Link { request -> NjaRuntime.serveCallback(request) }

    override fun transact(message: ByteArray, length: Int): ByteArray =
        NjaServer.handle(if (length == message.size) message else message.copyOf(length), link)
}
