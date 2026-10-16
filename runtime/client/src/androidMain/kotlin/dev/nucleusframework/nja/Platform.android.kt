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
    AndroidJvm.start()
    return AndroidTransport
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
