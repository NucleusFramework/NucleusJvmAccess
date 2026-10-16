package dev.nucleusframework.nja

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The JVM's suspend functions and flows, seen from the app: the call starts them in the JVM and returns at once,
 * their outcome comes back as events that a daemon thread polls and dispatches by call id.
 */
@OptIn(ExperimentalAtomicApi::class)
@NjaInternal
object NjaAsync {
    /** Elements a flow may emit ahead of its collector. */
    private const val WINDOW = 64

    private sealed class Pending {
        /** Its outcome may come (as an event) before the caller suspends: kept until then. */
        class Call(val decode: (WireReader) -> Any?) : Pending() {
            val lock = NjaLock()
            var continuation: CancellableContinuation<Any?>? = null
            var outcome: Result<Any?>? = null

            fun complete(result: Result<Any?>) {
                val waiting = lock.withLock {
                    continuation ?: run {
                        outcome = result
                        null
                    }
                }
                waiting?.resumeWith(result)
            }
        }
        class Stream(val channel: Channel<Any?>, val decode: (WireReader) -> Any?) : Pending()
    }

    private val nextId = AtomicLong(1)
    private val lock = NjaLock()
    private val pending = HashMap<Long, Pending>()
    private val pumping = AtomicBoolean(false)

    private fun register(id: Long, entry: Pending) {
        lock.withLock { pending[id] = entry }
        if (!pumping.load() && pumping.compareAndSet(false, true)) startDaemon("nja-events", ::pump)
    }

    private fun take(id: Long): Pending? = lock.withLock { pending.remove(id) }

    private fun peek(id: Long): Pending? = lock.withLock { pending[id] }

    /**
     * Calls a suspend function of the JVM. When it completes without suspending there, its outcome is the call's reply
     * and this doesn't suspend either; otherwise it suspends until the JVM's event.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> call(
        module: Int,
        member: Int,
        encode: (WireWriter, CallScope) -> Unit,
        decode: (WireReader) -> T,
    ): T {
        val id = nextId.fetchAndIncrement()
        val entry = Pending.Call(decode)
        register(id, entry)
        val inline: Result<Any?>? = try {
            NjaRuntime.call(module, member, { w, s ->
                w.writeLong(id)
                encode(w, s)
            }) { r, _ ->
                when (r.readByte().toInt()) {
                    0 -> null
                    1 -> Result.success(decode(r))
                    2 -> Result.failure(NjaRuntime.readThrowable(r, null))
                    else -> Result.failure(CancellationException("Cancelled in the JVM"))
                }
            }
        } catch (e: Throwable) {
            take(id)
            throw e
        }
        if (inline != null) {
            take(id)
            return inline.getOrThrow() as T
        }
        return suspendCancellableCoroutine { continuation ->
            val early = entry.lock.withLock {
                entry.outcome ?: run {
                    entry.continuation = continuation as CancellableContinuation<Any?>
                    null
                }
            }
            if (early != null) {
                continuation.resumeWith(early as Result<T>)
            } else {
                continuation.invokeOnCancellation { if (take(id) != null) cancelRemote(id) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> flow(
        module: Int,
        member: Int,
        encode: (WireWriter, CallScope) -> Unit,
        decode: (WireReader) -> T,
    ): Flow<T> = flow {
        val id = nextId.fetchAndIncrement()
        val channel = Channel<Any?>(Channel.UNLIMITED)
        register(id, Pending.Stream(channel, decode))
        var completed = false
        try {
            NjaRuntime.call(module, member, { w, s ->
                w.writeLong(id)
                w.writeInt(WINDOW)
                encode(w, s)
            }) { _, _ -> }
            var consumed = 0
            for (element in channel) {
                emit(element as T)
                if (++consumed >= WINDOW / 2) {
                    grant(id, consumed)
                    consumed = 0
                }
            }
            completed = true
        } finally {
            if (take(id) != null && !completed) cancelRemote(id)
        }
    }

    private fun grant(id: Long, count: Int) {
        NjaRuntime.call(Protocol.RUNTIME_MODULE, Protocol.GRANT, { w, _ ->
            w.writeLong(id)
            w.writeInt(count)
        }) { _, _ -> }
    }

    private fun cancelRemote(id: Long) {
        try {
            NjaRuntime.call(Protocol.RUNTIME_MODULE, Protocol.CANCEL, { w, _ -> w.writeLong(id) }) { _, _ -> }
        } catch (_: Throwable) {
            // Cancellation is best effort: the JVM drops the outcome of an unknown call
        }
    }

    private fun pump() {
        while (true) {
            val events = try {
                NjaRuntime.call(Protocol.RUNTIME_MODULE, Protocol.POLL_EVENTS, { w, _ -> w.writeInt(1000) }) { r, _ ->
                    List(r.readInt()) { r.readBytes()!! }
                }
            } catch (e: Throwable) {
                failAll(e)
                return
            }
            for (event in events) dispatch(WireReader(event))
        }
    }

    private fun dispatch(reader: WireReader) {
        val kind = reader.readByte()
        val id = reader.readLong()
        when (kind) {
            Protocol.EVENT_RESULT -> when (val entry = take(id)) {
                is Pending.Call -> entry.complete(runCatching { entry.decode(reader) })
                is Pending.Stream, null -> Unit
            }
            Protocol.EVENT_ERROR -> {
                val error = NjaRuntime.readThrowable(reader, null)
                when (val entry = take(id)) {
                    is Pending.Call -> entry.complete(Result.failure(error))
                    is Pending.Stream -> entry.channel.close(error)
                    null -> Unit
                }
            }
            Protocol.EVENT_CANCELLED -> when (val entry = take(id)) {
                is Pending.Call -> entry.complete(Result.failure(CancellationException("Cancelled in the JVM")))
                is Pending.Stream -> entry.channel.close(CancellationException("Cancelled in the JVM"))
                null -> Unit
            }
            Protocol.EVENT_FLOW_NEXT -> when (val entry = peek(id)) {
                is Pending.Stream -> try {
                    entry.channel.trySend(entry.decode(reader))
                } catch (e: Throwable) {
                    take(id)
                    entry.channel.close(e)
                    cancelRemote(id)
                }
                else -> Unit
            }
            Protocol.EVENT_FLOW_DONE -> when (val entry = peek(id)) {
                is Pending.Stream -> entry.channel.close()
                else -> Unit
            }
        }
    }

    private fun failAll(e: Throwable) {
        val all = lock.withLock { pending.values.toList().also { pending.clear() } }
        pumping.store(false)
        for (entry in all) when (entry) {
            is Pending.Call -> entry.complete(Result.failure(e))
            is Pending.Stream -> entry.channel.close(e)
        }
    }
}
