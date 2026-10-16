package com.example.torture

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class VmInfo(val name: String, val version: String, val os: String, val arch: String, val processors: Int)

data class Counters(val threads: Int, val heapUsedMb: Long)

class Node(val id: Int) {
    var next: Node? = null
    fun depth(): Int = 1 + (next?.depth() ?: 0)
}

class JvmTorture {
    private var holder: Any? = null

    fun vm(): VmInfo = VmInfo(
        System.getProperty("java.vm.name"), Runtime.version().toString(), System.getProperty("os.name"),
        System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
    )

    fun counters(): Counters {
        val runtime = Runtime.getRuntime()
        return Counters(Thread.activeCount(), (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
    }

    /** Implicit null checks: each one a SIGSEGV HotSpot turns into a NullPointerException. */
    fun npeStorm(count: Int): Int {
        var caught = 0
        repeat(count) {
            try {
                holder!!.hashCode()
                (holder as String).length
            } catch (e: NullPointerException) {
                caught++
            }
        }
        return caught
    }

    /** Stack banging into the guard zone: a SIGSEGV HotSpot turns into a StackOverflowError. */
    fun stackOverflow(): Int {
        var depth = 0
        fun dive(): Int {
            depth++
            return dive() + 1
        }
        try {
            dive()
        } catch (e: StackOverflowError) {
            return depth
        }
        error("no stack overflow")
    }

    fun recurse(depth: Int): Int = if (depth == 0) 0 else 1 + recurse(depth - 1)

    /** Garbage, for the JVM's GC to run while the app's does. */
    fun churn(megabytes: Int): Long {
        var checksum = 0L
        repeat(megabytes * 64) { i ->
            val chunk = ByteArray(16 * 1024)
            chunk[i % chunk.size] = i.toByte()
            checksum += chunk[i % chunk.size]
        }
        return checksum
    }

    fun gc() = System.gc()

    /** Work on JVM threads of its own while the app calls in. */
    fun parallelSum(threads: Int, perThread: Long): Long {
        val pool = Executors.newFixedThreadPool(threads)
        val total = AtomicLong()
        try {
            (0 until threads).map { pool.submit { var s = 0L; for (i in 0 until perThread) s += i % 7; total.addAndGet(s) } }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return total.get()
    }

    fun failWithIo(message: String): String = throw java.io.IOException(message)

    fun failDeep(depth: Int): Int = if (depth == 0) throw UnsupportedOperationException("deep $depth") else failDeep(depth - 1) + 1

    /** JVM -> app -> JVM ... : the app's callback calls back in, [depth] levels deep. */
    fun pingPong(depth: Int, callback: (Int) -> Int): Int = if (depth == 0) 0 else callback(depth - 1) + 1

    /** A callback is only valid on the call's thread: invoked from another JVM thread, it must fail cleanly. */
    fun callbackFromAnotherThread(callback: (Int) -> Int): String {
        var outcome = "not run"
        val thread = Thread {
            outcome = try {
                "returned ${callback(1)}"
            } catch (e: IllegalStateException) {
                "rejected: ${e.message}"
            }
        }
        thread.start()
        thread.join()
        return outcome
    }

    /** A callback stored and invoked after its call: must fail cleanly too. */
    private var stored: ((Int) -> Int)? = null

    fun storeCallback(callback: (Int) -> Int) {
        stored = callback
    }

    fun invokeStored(): String = try {
        "returned ${stored!!(1)}"
    } catch (e: IllegalStateException) {
        "rejected: ${e.message}"
    }

    fun echo(bytes: ByteArray): ByteArray = bytes

    fun bigBytes(megabytes: Int): ByteArray = ByteArray(megabytes * 1024 * 1024) { (it * 31).toByte() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun echoString(s: String): String = s

    fun chain(length: Int): Node {
        val head = Node(0)
        var current = head
        for (i in 1 until length) {
            val n = Node(i)
            current.next = n
            current = n
        }
        return head
    }

    fun nodes(count: Int): List<Node> = List(count) { Node(it) }

    suspend fun sleepy(millis: Long): Long {
        delay(millis)
        return millis
    }

    fun ticks(count: Int): Flow<Long> = flow { repeat(count) { emit(it.toLong()) } }

    fun slowTicks(count: Int, millis: Long): Flow<Int> = flow {
        repeat(count) {
            delay(millis)
            emit(it)
        }
    }

    fun deoptStorm(rounds: Int): Long {
        // A call site that turns megamorphic: the JIT's compiled code gets invalidated and recompiled
        val shapes: List<() -> Long> = listOf({ 1L }, { 2L }, { 3L }, { 4L }, { 5L }, { 6L })
        var sum = 0L
        for (r in 0 until rounds) {
            val active = if (r < rounds / 2) 1 else shapes.size
            for (i in 0 until 10_000) sum += shapes[i % active]()
        }
        return sum
    }
}
