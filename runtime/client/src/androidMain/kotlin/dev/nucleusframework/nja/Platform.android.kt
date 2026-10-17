package dev.nucleusframework.nja

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
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

/** Phantom references to the proxies' cleaner tokens, released by a daemon thread (java.lang.ref.Cleaner is API 33). */
private object ProxyCleaner {
    private val queue = ReferenceQueue<Any>()
    private val live = ConcurrentHashMap.newKeySet<Reference>()

    private class Reference(token: Any, val ref: JvmRef) : PhantomReference<Any>(token, queue)

    init {
        startDaemon("nja-cleaner") {
            while (true) {
                val reference = queue.remove() as Reference
                live.remove(reference)
                reference.ref.close()
            }
        }
    }

    fun register(ref: JvmRef): Any {
        val token = Any()
        live += Reference(token, ref)
        return token
    }
}

internal actual fun createCleaner(ref: JvmRef): Any = ProxyCleaner.register(ref)

internal actual fun startTransport(): Transport {
    if (AndroidJvm.onArt()) return ArtTransport
    AndroidJvm.start()
    return AndroidTransport
}

/**
 * The façades on ART itself (the app's own dependencies, dexed with it), as on a JVM target: a call is a plain call into
 * the server's dispatcher on the calling thread. The server is the app's dependency, not the client's: reached by name.
 */
private object ArtTransport : Transport {
    private val server = Class.forName("dev.nucleusframework.nja.server.NjaServer")
    private val linkClass = Class.forName("dev.nucleusframework.nja.server.Link")
    private val handle = server.getMethod("handle", ByteArray::class.java, linkClass)
    private val link = java.lang.reflect.Proxy.newProxyInstance(linkClass.classLoader, arrayOf(linkClass)) { proxy, method, args ->
        when (method.name) {
            "exchange" -> NjaRuntime.serveCallback(args!![0] as ByteArray)
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> "NjaLink"
        }
    }

    override fun transact(message: ByteArray, length: Int): ByteArray =
        handle.invoke(null, if (length == message.size) message else message.copyOf(length), link) as ByteArray
}

/** One round trip per message: the JVM's callbacks come back as answers, served here until the reply. */
private object AndroidTransport : Transport {
    override fun transact(message: ByteArray, length: Int): ByteArray {
        var answer = AndroidJvm.transact(message, length)
        while (answer[0] == Protocol.CALLBACK) {
            val result = NjaRuntime.serveCallback(answer)
            answer = AndroidJvm.transact(result, result.size)
        }
        return answer
    }
}
