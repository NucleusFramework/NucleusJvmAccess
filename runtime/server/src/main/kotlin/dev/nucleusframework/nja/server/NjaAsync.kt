package dev.nucleusframework.nja.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The façade's suspend functions and flows, run on the JVM's own coroutines. Their outcome reaches the app as
 * events (see [Protocol]), which it polls from a thread of its own; the app cancels them by call id.
 */
object NjaAsync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val credits = ConcurrentHashMap<Long, Credits>()
    private val events = LinkedBlockingQueue<ByteArray>()

    val pending: Int get() = jobs.size

    /** Flow collection only emits against the credits the app granted, as its collector consumes elements. */
    private class Credits(private var available: Int) {
        private val grants = Channel<Int>(Channel.UNLIMITED)

        suspend fun acquire() {
            while (available <= 0) available += grants.receive()
            available--
        }

        fun grant(count: Int) {
            grants.trySend(count)
        }
    }

    /**
     * Runs a suspend function, started on the calling thread: when it completes without suspending, its outcome is the
     * call's reply ([output]: 1 value, 2 exception, 3 cancelled); otherwise the reply is 0 and the outcome an event.
     */
    fun <T> launchSuspend(callId: Long, output: WireWriter, block: suspend () -> T, encode: (WireWriter, T) -> Unit) {
        // 0: in the call, 1: completed in it, 2: detached (its outcome goes to the events)
        val state = java.util.concurrent.atomic.AtomicInteger(0)
        var inline: Result<T>? = null
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            val outcome = try {
                Result.success(block())
            } catch (e: Throwable) {
                Result.failure(e)
            }
            if (state.compareAndSet(0, 1)) inline = outcome else deliver(callId, outcome, encode)
        }
        if (state.compareAndSet(0, 2)) {
            output.writeByte(0)
            track(callId, job)
            return
        }
        val outcome = inline!!
        val error = outcome.exceptionOrNull()
        when {
            error == null -> {
                output.writeByte(1)
                @Suppress("UNCHECKED_CAST")
                encode(output, outcome.getOrNull() as T)
            }
            error is CancellationException -> output.writeByte(3)
            else -> {
                output.writeByte(2)
                NjaServer.writeThrowable(output, error)
            }
        }
    }

    private fun <T> deliver(callId: Long, outcome: Result<T>, encode: (WireWriter, T) -> Unit) {
        val error = outcome.exceptionOrNull()
        when {
            error == null -> {
                @Suppress("UNCHECKED_CAST")
                post(Protocol.EVENT_RESULT, callId) { encode(it, outcome.getOrNull() as T) }
            }
            error is CancellationException -> post(Protocol.EVENT_CANCELLED, callId) {}
            else -> post(Protocol.EVENT_ERROR, callId) { NjaServer.writeThrowable(it, error) }
        }
    }

    fun <T> launchFlow(callId: Long, initialCredits: Int, flow: Flow<T>, encode: (WireWriter, T) -> Unit) {
        val window = Credits(initialCredits)
        credits[callId] = window
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                flow.collect { value ->
                    window.acquire()
                    post(Protocol.EVENT_FLOW_NEXT, callId) { encode(it, value) }
                }
                post(Protocol.EVENT_FLOW_DONE, callId) {}
            } catch (e: CancellationException) {
                post(Protocol.EVENT_CANCELLED, callId) {}
            } catch (e: Throwable) {
                post(Protocol.EVENT_ERROR, callId) { NjaServer.writeThrowable(it, e) }
            }
        }
        track(callId, job)
    }

    /** Starts [job], forgotten once complete: a job cancelled before its body runs never reaches a finally block. */
    private fun track(callId: Long, job: Job) {
        jobs[callId] = job
        job.invokeOnCompletion {
            jobs.remove(callId)
            credits.remove(callId)
        }
        job.start()
    }

    fun cancel(callId: Long) {
        jobs[callId]?.cancel()
    }

    fun grant(callId: Long, count: Int) {
        credits[callId]?.grant(count)
    }

    private inline fun post(kind: Byte, callId: Long, body: (WireWriter) -> Unit) {
        val writer = WireWriter(64)
        writer.writeByte(kind)
        writer.writeLong(callId)
        try {
            body(writer)
        } catch (e: Throwable) {
            // An element the bridge can't encode fails the call instead of losing it
            writer.reset()
            writer.writeByte(Protocol.EVENT_ERROR)
            writer.writeLong(callId)
            NjaServer.writeThrowable(writer, e)
        }
        events.put(writer.toByteArray())
    }

    /** Waits up to [timeout] for events, then writes all those available: count, then each as length + bytes. */
    fun poll(timeout: Long, unit: TimeUnit, output: WireWriter) {
        val first = events.poll(timeout, unit)
        if (first == null) {
            output.writeInt(0)
            return
        }
        val batch = ArrayList<ByteArray>()
        batch += first
        events.drainTo(batch, 4095)
        output.writeInt(batch.size)
        for (event in batch) output.writeBytes(event)
    }
}
