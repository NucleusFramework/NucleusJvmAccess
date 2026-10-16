package com.example.calculator

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.fail
import kotlin.time.TimeSource

// NNA's test suites, ported from its JVM tests to the common tests of both mobile targets: the few JVM APIs they use
// have common stand-ins here (same-package declarations win over the JVM's default imports, so the tests are
// unchanged). Their KotlinNativeException is NJA's JvmException: the façade's exceptions, rethrown in the app.

typealias KotlinNativeException = dev.nucleusframework.nja.JvmException

/** A platform thread (java.lang.Thread on Android, a pthread on iOS). An uncaught exception is reported, as on the JVM. */
expect class Thread(body: () -> Unit) {
    fun start()
    fun join()

    /** Waits up to [millis], returns whether the thread ended. */
    fun join(millis: Long): Boolean

    companion object {
        fun sleep(millis: Long)
        fun sleep(millis: Long, nanos: Int)
        fun yield()
    }
}

fun thread(body: () -> Unit): Thread = Thread(body).also { it.start() }

expect class WeakReference<T : Any>(referent: T) {
    fun get(): T?
}

internal expect fun platformGc()

internal expect fun platformGetenv(name: String): String?

object System {
    private val origin = TimeSource.Monotonic.markNow()

    fun gc() = platformGc()

    fun currentTimeMillis(): Long = origin.elapsedNow().inWholeMilliseconds

    fun nanoTime(): Long = origin.elapsedNow().inWholeNanoseconds

    fun getenv(name: String): String? = platformGetenv(name)
}

class AtomicInteger(initial: Int = 0) {
    private val value = AtomicInt(initial)
    fun get(): Int = value.load()
    fun set(v: Int) = value.store(v)
    fun incrementAndGet(): Int = value.addAndFetch(1)
    fun getAndIncrement(): Int = value.fetchAndAdd(1)
    fun decrementAndGet(): Int = value.addAndFetch(-1)
    fun addAndGet(delta: Int): Int = value.addAndFetch(delta)
    override fun toString(): String = get().toString()
}

/** A spin lock: the tests' shared structures are tiny and short-held. */
class SpinLock {
    private val held = AtomicBoolean(false)

    inline fun <T> withLock(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    fun lock() {
        while (!held.compareAndSet(false, true)) Thread.yield()
    }

    fun unlock() = held.store(false)
}

class ConcurrentHashMap<K, V> {
    private val lock = SpinLock()
    private val map = LinkedHashMap<K, V>()

    fun computeIfAbsent(key: K, compute: (K) -> V): V = lock.withLock { map.getOrPut(key) { compute(key) } }
    operator fun get(key: K): V? = lock.withLock { map[key] }
    fun getValue(key: K): V = lock.withLock { map.getValue(key) }
    operator fun set(key: K, value: V) = lock.withLock { map[key] = value }
    fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }
    val size: Int get() = lock.withLock { map.size }
    val keys: Set<K> get() = lock.withLock { map.keys.toSet() }
    val values: List<V> get() = lock.withLock { map.values.toList() }

    companion object {
        fun <T> newKeySet(): ConcurrentSet<T> = ConcurrentSet()
    }
}

class ConcurrentSet<T> : Iterable<T> {
    private val lock = SpinLock()
    private val set = LinkedHashSet<T>()

    fun add(value: T): Boolean = lock.withLock { set.add(value) }
    operator fun plusAssign(value: T) {
        add(value)
    }
    val size: Int get() = lock.withLock { set.size }
    fun isEmpty(): Boolean = size == 0
    fun firstOrNull(): T? = lock.withLock { set.firstOrNull() }
    override fun iterator(): Iterator<T> = lock.withLock { set.toList() }.iterator()
    override fun toString(): String = lock.withLock { set.toString() }
}

class CopyOnWriteArrayList<T>(initial: Collection<T> = emptyList()) : AbstractMutableList<T>() {
    private val lock = SpinLock()
    private var items: List<T> = initial.toList()

    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
    override fun add(index: Int, element: T) = lock.withLock { items = items.toMutableList().apply { add(index, element) } }
    override fun removeAt(index: Int): T = lock.withLock {
        val copy = items.toMutableList()
        copy.removeAt(index).also { items = copy }
    }
    override fun set(index: Int, element: T): T = lock.withLock {
        val copy = items.toMutableList()
        copy.set(index, element).also { items = copy }
    }
    override fun iterator(): MutableIterator<T> = items.toMutableList().iterator()
}

class CyclicBarrier(private val parties: Int) {
    private val arrived = AtomicInt(0)
    private val generation = AtomicInt(0)

    fun await() {
        val gen = generation.load()
        if (arrived.addAndFetch(1) == parties) {
            arrived.store(0)
            generation.addAndFetch(1)
            return
        }
        while (generation.load() == gen) Thread.yield()
    }
}

/** Runs [block] on a thread of its own and fails if it does not finish in time (a probable deadlock). */
internal fun deadlockGuard(seconds: Long, block: () -> Unit) {
    var failure: Throwable? = null
    val worker = Thread {
        try {
            block()
        } catch (e: Throwable) {
            failure = e
        }
    }
    worker.start()
    if (!worker.join(seconds * 1000)) fail("Probable deadlock (no completion in ${seconds}s)")
    failure?.let { throw it }
}

/** Starts [threads] threads behind a barrier, each running [body] [iterations] times; rethrows the first failure. */
internal fun runParallel(threads: Int, iterations: Int, body: (thread: Int, iteration: Int) -> Unit) {
    val barrier = CyclicBarrier(threads)
    val errors = ConcurrentHashMap.newKeySet<Throwable>()
    List(threads) { t ->
        thread {
            try {
                barrier.await()
                repeat(iterations) { i -> body(t, i) }
            } catch (e: Throwable) {
                errors += e
            }
        }
    }.forEach { it.join() }
    errors.firstOrNull()?.let { throw AssertionError("${errors.size} thread(s) failed", it) }
}

// Other JVM APIs the suites use

object Math {
    const val PI: Double = kotlin.math.PI
}

fun String(bytes: ByteArray): String = bytes.decodeToString()

fun <K : Comparable<K>, V> sortedMapOf(vararg pairs: Pair<K, V>): Map<K, V> = pairs.sortedBy { it.first }.toMap()

fun assert(value: Boolean) {
    if (!value) throw AssertionError("Assertion failed")
}

fun assert(value: Boolean, lazyMessage: () -> Any) {
    if (!value) throw AssertionError(lazyMessage().toString())
}

/** The app's own faults meanwhile: on Android ART's implicit null checks (its SIGSEGVs), on iOS Kotlin exceptions. */
internal expect fun appFaultStorm(count: Int): Int
