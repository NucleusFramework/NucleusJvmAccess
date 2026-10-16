package dev.nucleusframework.nja

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The app's reference to a JVM object. Calls lease it for their duration: [close] marks it closed at once (later
 * calls fail with IllegalStateException) and releases the JVM object once the calls in flight are done.
 */
@OptIn(ExperimentalAtomicApi::class)
@NjaInternal
class JvmRef(val handle: Long) {
    // In-flight calls, plus CLOSED once closed
    private val state = AtomicInt(0)
    private val released = AtomicBoolean(false)

    fun acquire() {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0) throw IllegalStateException("JVM object has been closed")
            if (state.compareAndSet(current, current + 1)) return
        }
    }

    fun releaseLease() {
        if (state.addAndFetch(-1) == CLOSED) dispose()
    }

    fun close() {
        while (true) {
            val current = state.load()
            if (current and CLOSED != 0) return
            if (state.compareAndSet(current, current or CLOSED)) {
                if (current == 0) dispose()
                return
            }
        }
    }

    val isClosed: Boolean get() = state.load() and CLOSED != 0

    /** The handle, for an argument: the object must not be closed. */
    fun liveHandle(): Long {
        if (isClosed) throw IllegalStateException("JVM object has been closed")
        return handle
    }

    private fun dispose() {
        if (released.compareAndSet(false, true)) NjaRuntime.release(handle)
    }

    inline fun <R> use(block: (Long) -> R): R {
        acquire()
        try {
            return block(handle)
        } finally {
            releaseLease()
        }
    }

    private companion object {
        const val CLOSED = 1 shl 30
    }
}

/** Releases [ref] once the object holding the returned token is collected. */
internal expect fun createCleaner(ref: JvmRef): Any

/** An app-side object standing for a JVM object (generated proxies and the façade's interfaces). */
interface JvmProxy : AutoCloseable {
    @NjaInternal
    val njaRef: JvmRef
}

/**
 * Base of the generated proxies. Closing one (or its collection) releases the JVM object; two proxies of the same JVM
 * object are equal.
 */
abstract class JvmObject @NjaInternal constructor(@property:NjaInternal override val njaRef: JvmRef) : JvmProxy {
    @Suppress("unused")
    private val njaCleaner = createCleaner(njaRef)

    override fun close() = njaRef.close()

    override fun equals(other: Any?): Boolean = other is JvmProxy && other.njaRef.handle == njaRef.handle

    override fun hashCode(): Int = njaRef.handle.hashCode()
}
