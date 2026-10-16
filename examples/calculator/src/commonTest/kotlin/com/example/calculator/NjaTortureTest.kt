package com.example.calculator

import com.example.torture.JvmTorture
import com.example.torture.Node
import dev.nucleusframework.nja.JvmException
import dev.nucleusframework.nja.NjaInternal
import dev.nucleusframework.nja.NjaRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * NJA's own torture: what NNA's suites don't reach, specific to a JVM inside the app's process: the JVM's faults
 * next to the app runtime's, nested calls across the two, the JVM's threads, and the bridge's lifecycles.
 */
@OptIn(NjaInternal::class)
class NjaTortureTest {
    private val jvm = JvmTorture()

    private val isAndroid = jvm.vm().name.contains("Server")

    @Test
    fun `the JVM is the expected one`() {
        val vm = jvm.vm()
        println("NJA torture on ${vm.name} ${vm.version} (${vm.os}/${vm.arch}, ${vm.processors} cpus)")
        assertTrue(vm.version.startsWith("27"), "$vm")
        assertTrue(vm.name.contains("Server") || vm.name.contains("Zero"), "$vm")
    }

    @Test
    fun `signal storm - JVM null checks and stack overflows on 16 threads while the app faults and collects`() = deadlockGuard(600) {
        val rounds = if (isAndroid) 150 else 30
        runParallel(threads = 16, iterations = rounds) { t, i ->
            when ((t + i) % 6) {
                0 -> assertEquals(500, jvm.npeStorm(500))
                1 -> assertTrue(jvm.stackOverflow() > 500)
                2 -> assertEquals(2_000, appFaultStorm(2_000))
                3 -> jvm.churn(1)
                4 -> if (i % 10 == 0) {
                    System.gc()
                    jvm.gc()
                }
                else -> assertEquals(1_000, jvm.recurse(1_000))
            }
        }
    }

    @Test
    fun `JIT deoptimization storm under concurrent calls`() = deadlockGuard(300) {
        runParallel(threads = 8, iterations = if (isAndroid) 20 else 2) { _, _ ->
            assertTrue(jvm.deoptStorm(if (isAndroid) 40 else 4) > 0)
        }
    }

    private fun pingPong(depth: Int): Int = jvm.pingPong(depth) { pingPong(it) }

    @Test
    fun `nested ping-pong JVM to app to JVM 300 levels deep`() = deadlockGuard(120) {
        assertEquals(300, pingPong(300))
    }

    @Test
    fun `nested ping-pong on 16 threads at once`() = deadlockGuard(300) {
        runParallel(threads = 16, iterations = 20) { t, _ -> assertEquals(50 + t, pingPong(50 + t)) }
    }

    @Test
    fun `an exception deep in nested calls surfaces as itself`() = deadlockGuard(120) {
        class Deep(message: String) : RuntimeException(message)
        fun dive(depth: Int): Int = jvm.pingPong(depth) { if (it == 0) throw Deep("bottom") else dive(it) }
        repeat(50) { assertEquals("bottom", assertFailsWith<Deep> { dive(40) }.message) }
        assertEquals(10, pingPong(10))
    }

    @Test
    fun `a callback invoked from another JVM thread is rejected cleanly`() {
        repeat(100) {
            val outcome = jvm.callbackFromAnotherThread { it + 1 }
            assertTrue(outcome.startsWith("rejected: NJA: a callback can only be invoked during the call"), outcome)
        }
        assertEquals(7, jvm.pingPong(7) { pingPong(it) })
    }

    @Test
    fun `a callback invoked after its call is rejected cleanly`() {
        jvm.storeCallback { it * 2 }
        val outcome = jvm.invokeStored()
        assertTrue(outcome.startsWith("rejected: NJA: a callback can only be invoked during the call"), outcome)
    }

    @Test
    fun `JVM exceptions keep their class and message`() {
        repeat(200) {
            val io = assertFailsWith<JvmException> { jvm.failWithIo("io $it") }
            assertEquals("java.io.IOException", io.javaClassName)
            assertEquals("io $it", io.message)
        }
        val deep = assertFailsWith<JvmException> { jvm.failDeep(800) }
        assertEquals("java.lang.UnsupportedOperationException", deep.javaClassName)
        assertTrue(deep.javaStackTrace.contains("failDeep"), deep.javaStackTrace)
    }

    @Test
    fun `JVM thread pools work while the app hammers the bridge`() = deadlockGuard(300) {
        var sum = 0L
        val pool = Thread { sum = jvm.parallelSum(32, if (isAndroid) 2_000_000 else 50_000) }
        pool.start()
        runParallel(threads = 8, iterations = 2_000) { _, i -> assertEquals(i % 50, jvm.recurse(i % 50)) }
        pool.join()
        val per = (0 until (if (isAndroid) 2_000_000 else 50_000)).sumOf { (it % 7).toLong() }
        assertEquals(per * 32, sum)
    }

    @Test
    fun `thread churn - 1000 short-lived app threads no JVM thread leak`() = deadlockGuard(600) {
        val before = jvm.counters().threads
        repeat(50) {
            List(20) { t -> thread { repeat(5) { assertEquals(t, jvm.recurse(t)) } } }.forEach { it.join() }
        }
        // Their JVM side (Android: the paired workers; iOS: the attached threads) goes with them
        var after = jvm.counters().threads
        repeat(100) {
            if (after <= before + 8) return@repeat
            Thread.sleep(50)
            after = jvm.counters().threads
        }
        assertTrue(after <= before + 8, "JVM threads: $before before, $after after")
    }

    @Test
    fun `huge payloads cross intact`() {
        // Each crossing copies the payload a few times: ART's heap is capped (192 MB here), Zero hashes slowly
        val size = if (isAndroid) 24 else 8
        val bytes = jvm.bigBytes(size)
        assertEquals(size * 1024 * 1024, bytes.size)
        assertEquals((12345 * 31).toByte(), bytes[12345])
        val echoed = jvm.echo(bytes)
        assertContentEquals(bytes, echoed)
        assertEquals(jvm.sha256(bytes), jvm.sha256(echoed))
        val text = "日本語 😀 é".repeat(200_000)
        assertEquals(text, jvm.echoString(text))
    }

    @Test
    fun `100k JVM objects are released by close and by collection`() {
        val baseline = NjaRuntime.stats().first
        repeat(100) { jvm.nodes(1_000).forEach { it.close() } }
        assertTrue(NjaRuntime.stats().first <= baseline + 10, "handles after close: ${NjaRuntime.stats().first}, baseline $baseline")
        fun dropped() = repeat(50) { jvm.nodes(1_000) }
        dropped()
        var live = NjaRuntime.stats().first
        repeat(100) {
            if (live <= baseline + 1_000) return@repeat
            System.gc()
            Thread.sleep(50)
            live = NjaRuntime.stats().first
        }
        assertTrue(live <= baseline + 1_000, "handles after collection: $live, baseline $baseline")
    }

    @Test
    fun `a 50k long object chain is walked in the JVM`() {
        val head: Node = jvm.chain(if (isAndroid) 5_000 else 1_000)
        assertEquals(if (isAndroid) 5_000 else 1_000, head.depth())
        var node: Node? = head
        var count = 0
        while (node != null && count < 200) {
            count++
            node = node.next
        }
        assertEquals(200, count)
    }

    @Test
    fun `cancellation storm - no pending call left in the JVM`() = runBlocking<Unit> {
        val results = List(3_000) { i ->
            async(Dispatchers.Default) { withTimeoutOrNull(Random.nextLong(0, 3)) { jvm.sleepy((i % 5).toLong()) } }
        }.awaitAll()
        assertTrue(results.any { it == null } || results.all { it != null })
        var pending = NjaRuntime.stats().second
        repeat(100) {
            if (pending == 0) return@repeat
            delay(20)
            pending = NjaRuntime.stats().second
        }
        assertEquals(0, pending, "async calls still pending in the JVM")
    }

    @Test
    fun `flows - 200k elements and a slow collector loses nothing`() = runBlocking<Unit> {
        val n = if (isAndroid) 200_000 else 20_000
        assertEquals(n, jvm.ticks(n).count())
        val slow = jvm.slowTicks(100, 1).toList()
        assertEquals(List(100) { it }, slow)
        val collected = ArrayList<Long>()
        jvm.ticks(5_000).collect {
            if (it % 500 == 0L) delay(5)
            collected += it
        }
        assertEquals(List(5_000) { it.toLong() }, collected)
    }

    @Test
    fun `chaos soak - 24 threads of random operations`() = deadlockGuard(900) {
        val seconds = if (isAndroid) 20 else 15
        val deadline = TimeSource.Monotonic.markNow()
        val ops = AtomicInteger()
        runParallel(threads = 24, iterations = 1) { t, _ ->
            val rnd = Random(7_000 + t)
            while (deadline.elapsedNow().inWholeSeconds < seconds) {
                when (rnd.nextInt(14)) {
                    0 -> assertEquals(100, jvm.npeStorm(100))
                    1 -> rnd.nextInt(0, 30).let { d -> assertEquals(d, pingPong(d)) }
                    2 -> jvm.churn(1)
                    3 -> assertEquals(1_000, appFaultStorm(1_000))
                    4 -> runBlocking { withTimeoutOrNull(rnd.nextLong(0, 3)) { jvm.sleepy(rnd.nextLong(0, 4)) } }
                    5 -> runBlocking { assertEquals(300, jvm.ticks(300).count()) }
                    6 -> jvm.nodes(rnd.nextInt(0, 300)).forEach { if (rnd.nextBoolean()) it.close() }
                    7 -> assertFailsWith<JvmException> { jvm.failWithIo("chaos") }
                    8 -> rnd.nextBytes(rnd.nextInt(0, 200_000)).let { assertContentEquals(it, jvm.echo(it)) }
                    9 -> if (rnd.nextInt(50) == 0) System.gc()
                    10 -> assertTrue(jvm.callbackFromAnotherThread { it }.startsWith("rejected"))
                    11 -> Calculator(t).use { calc -> assertEquals(t + 1, calc.add(1)) }
                    12 -> assertTrue(jvm.stackOverflow() > 100)
                    else -> rnd.nextInt(0, 200).let { d -> assertEquals(d, jvm.recurse(d)) }
                }
                ops.incrementAndGet()
            }
        }
        println("chaos soak: ${ops.get()} operations in ${seconds}s")
        assertTrue(ops.get() > 1_000)
    }
}
