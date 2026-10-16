package dev.nucleusframework.nja

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlinx.coroutines.CancellationException

/** Marks the API the generated proxies use; not meant to be called by hand. */
@RequiresOptIn("NJA's generated code uses this API; it isn't meant to be called directly.")
@Retention(AnnotationRetention.BINARY)
annotation class NjaInternal

/** An exception thrown by the JVM side, rethrown in the app. [javaClassName] is the JVM exception's class. */
open class JvmException(
    message: String?,
    val javaClassName: String,
    val javaStackTrace: String,
) : RuntimeException(message)

/** Talks to the JVM: one message (a call) out, its reply back, serving the JVM's callbacks meanwhile. */
internal interface Transport {
    /** Sends [length] bytes of [message] and returns the final reply (RETURN or THROW). */
    fun transact(message: ByteArray, length: Int): ByteArray
}

/** Starts the JVM of this platform (once) and returns its transport. */
internal expect fun startTransport(): Transport

/** A blocking lock (the runtime's few shared structures). */
internal expect class NjaLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> NjaLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/** This thread's stack of calls in progress (innermost last), to route the JVM's callbacks. */
internal expect fun currentScopes(): ArrayList<CallScope>

/** The class name of [e] as the JVM side reports it (no reflection on Android: kotlin.jvm.internal.ClassReference). */
internal expect fun className(e: Throwable): String

/** Runs [body] on a daemon thread of its own. */
internal expect fun startDaemon(name: String, body: () -> Unit)

/**
 * The callbacks (app lambdas) passed to one call, valid while it runs. The JVM invokes them by id; an exception one
 * throws is kept here so that, when it unwinds the call, the app gets it back as itself.
 */
@NjaInternal
class CallScope @PublishedApi internal constructor() {
    @OptIn(ExperimentalAtomicApi::class)
    internal val id: Long = nextScope.fetchAndIncrement()
    private var callbacks: ArrayList<(WireReader, WireWriter) -> Unit>? = null
    private var thrown: HashMap<Long, Throwable>? = null
    private var leases: ArrayList<JvmRef>? = null

    /**
     * The handle of an object the call passes: leased until the call ends, so that closing it meanwhile (from another
     * thread, or a callback) releases it only once the JVM is done with it. Fails if it is already closed.
     */
    fun lease(ref: JvmRef): Long {
        ref.acquire()
        (leases ?: ArrayList<JvmRef>(2).also { leases = it }) += ref
        return ref.handle
    }

    @PublishedApi
    internal fun end() {
        leases?.let { list ->
            leases = null
            for (ref in list) ref.releaseLease()
        }
    }

    /** Registers a callback, returns its id (never 0, the null callback). */
    fun register(adapter: (WireReader, WireWriter) -> Unit): Long {
        val list = callbacks ?: ArrayList<(WireReader, WireWriter) -> Unit>(2).also { callbacks = it }
        list += adapter
        return (id shl 16) or list.size.toLong()
    }

    internal fun owns(callbackId: Long): Boolean = (callbackId ushr 16) == id

    internal fun serve(callbackId: Long, input: WireReader): ByteArray {
        val adapter = callbacks?.getOrNull((callbackId and 0xffff).toInt() - 1)
            ?: return throwAnswer(0, IllegalStateException("NJA: unknown callback $callbackId"))
        val output = WireWriter(64)
        output.writeByte(Protocol.CALLBACK_RETURN)
        return try {
            adapter(input, output)
            output.toByteArray()
        } catch (e: Throwable) {
            val token = (thrown?.size ?: 0) + 1L
            (thrown ?: HashMap<Long, Throwable>().also { thrown = it })[token] = e
            throwAnswer((id shl 16) or token, e)
        }
    }

    internal fun thrown(token: Long): Throwable? = if ((token ushr 16) == id) thrown?.get(token and 0xffff) else null

    private fun throwAnswer(token: Long, e: Throwable): ByteArray {
        val output = WireWriter(64)
        output.writeByte(Protocol.CALLBACK_THROW)
        output.writeLong(token)
        output.writeString(className(e))
        output.writeString(e.message)
        return output.toByteArray()
    }

    @OptIn(ExperimentalAtomicApi::class)
    private companion object {
        val nextScope = AtomicLong(1)
    }
}

@NjaInternal
object NjaRuntime {
    private val transport: Transport by lazy { startTransport() }
    private val moduleLock = NjaLock()
    private val modules = HashMap<String, Int>()

    /** Starts the JVM now rather than on the first call. */
    fun start() {
        transport
    }

    /** The index of a façade's module in the JVM. */
    fun module(name: String): Int = moduleLock.withLock { modules[name] } ?: run {
        val index = call(Protocol.RUNTIME_MODULE, Protocol.RESOLVE_MODULE, { w, _ -> w.writeString(name) }) { r, _ -> r.readInt() }
        check(index > 0) { "NJA: the JVM has no module '$name' (is its façade on the class path?)" }
        moduleLock.withLock { modules[name] = index }
        index
    }

    /** Calls [member] of [module]: [encode] writes the arguments, [decode] reads the result. */
    inline fun <R> call(
        module: Int,
        member: Int,
        encode: (WireWriter, CallScope) -> Unit,
        decode: (WireReader, CallScope) -> R,
    ): R {
        val scope = CallScope()
        try {
            val writer = WireWriter(64)
            writer.writeByte(1) // Protocol.CALL
            writer.writeInt(module)
            writer.writeInt(member)
            encode(writer, scope)
            val reader = exchange(writer, scope)
            return decode(reader, scope)
        } finally {
            scope.end()
        }
    }

    @PublishedApi
    internal fun exchange(writer: WireWriter, scope: CallScope): WireReader {
        val scopes = currentScopes()
        scopes += scope
        val reply = try {
            transport.transact(writer.buffer, writer.size)
        } finally {
            scopes.removeAt(scopes.size - 1)
        }
        val reader = WireReader(reply)
        when (reader.readByte()) {
            Protocol.RETURN -> return reader
            Protocol.THROW -> throw readThrowable(reader, scope)
            else -> throw IllegalStateException("NJA: unexpected reply")
        }
    }

    /** Serves a callback request of the JVM (CALLBACK, id, arguments) on this thread, returns its answer. */
    internal fun serveCallback(request: ByteArray): ByteArray {
        val reader = WireReader(request)
        reader.readByte()
        val id = reader.readLong()
        val scopes = currentScopes()
        for (i in scopes.indices.reversed()) {
            val scope = scopes[i]
            if (scope.owns(id)) return scope.serve(id, reader)
        }
        val output = WireWriter(64)
        output.writeByte(Protocol.CALLBACK_THROW)
        output.writeLong(0)
        output.writeString("IllegalStateException")
        output.writeString("NJA: callback $id isn't valid on this thread anymore")
        return output.toByteArray()
    }

    internal fun readThrowable(reader: WireReader, scope: CallScope?): Throwable {
        val token = reader.readLong()
        val className = reader.readString() ?: "java.lang.Throwable"
        val message = reader.readString()
        val stack = reader.readString().orEmpty()
        if (token != 0L) {
            scope?.thrown(token)?.let { return it }
            for (s in currentScopes()) s.thrown(token)?.let { return it }
        }
        if (className.endsWith("CancellationException")) return CancellationException(message)
        return JvmException(message, className, stack)
    }

    /** Releases the app's reference to a JVM object. */
    fun release(handle: Long) {
        try {
            call(Protocol.RUNTIME_MODULE, Protocol.RELEASE, { w, _ ->
                w.writeInt(1)
                w.writeLong(handle)
            }) { _, _ -> }
        } catch (_: Throwable) {
            // The JVM is gone or never started: nothing to release
        }
    }

    /** Round trip with no work in the JVM: the bridge's own cost. */
    fun ping(payload: ByteArray): ByteArray =
        call(Protocol.RUNTIME_MODULE, Protocol.PING, { w, _ -> w.writeBytes(payload) }) { r, _ -> r.readBytes()!! }

    /** The JVM's live handle count, pending async calls and VM name. */
    fun stats(): Triple<Int, Int, String> =
        call(Protocol.RUNTIME_MODULE, Protocol.STATS, { _, _ -> }) { r, _ -> Triple(r.readInt(), r.readInt(), r.readString()!!) }
}
