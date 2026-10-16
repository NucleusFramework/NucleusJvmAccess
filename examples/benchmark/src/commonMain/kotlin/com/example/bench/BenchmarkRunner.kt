package com.example.bench

import dev.nucleusframework.nja.NjaRuntime
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/**
 * Runs the benchmarks on NJA's JVM and reports one line per result: `BENCH|<runtime>|<name>|<value>|<unit>`.
 * [scale] shrinks the workloads (Zero, an interpreter, is far slower than a JIT).
 */
class BenchmarkRunner(private val runtime: String, private val scale: Int = 1, private val report: (String) -> Unit = ::println) {
    private val b by lazy { Benchmarks() }

    fun emit(name: String, value: Double, unit: String) = report("BENCH|$runtime|$name|${format(value)}|$unit")

    private fun format(v: Double): String = if (v >= 100) v.toLong().toString() else ((v * 100).toLong() / 100.0).toString()

    /** Median of [runs] measured runs after [warmup] ones; [perRun] units per run. */
    fun throughput(name: String, unit: String, perRun: Int, warmup: Int, runs: Int, block: () -> Long) {
        repeat(warmup) { block() }
        val nanos = List(runs) { block() }.sorted()[runs / 2]
        emit(name, perRun * 1e9 / nanos, unit)
    }

    private inline fun timed(block: () -> Unit): Long {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow().inWholeNanoseconds
    }

    fun runAll() {
        val start = TimeSource.Monotonic.markNow()
        NjaRuntime.start()
        report("BENCH|$runtime|jvm-start|${start.elapsedNow().inWholeMilliseconds}|ms")
        report("BENCH|$runtime|vm|${b.vmName()}|")
        libraries()
        bridge()
        report("BENCH|$runtime|done|${start.elapsedNow().inWholeSeconds}|s")
    }

    /** Throughput over a long run, by windows: how fast the JIT gets there. */
    fun trend(name: String, windows: Int, perWindow: Int, block: () -> Long) {
        repeat(windows) { w ->
            val nanos = List(perWindow) { block() }.sorted()[perWindow / 2]
            emit("$name-trend-$w", 1e9 / nanos, "runs/s")
        }
    }

    /** Known Java libraries, timed inside the JVM. */
    fun libraries() {
        val s = scale
        throughput("jackson", "orders/s", 1_000 / s, 20 / s + 2, 9) { b.jackson(1_000 / s).nanos }
        throughput("fft", "fft/s (2^14)", 1, 30 / s + 2, 9) { b.fft(14).nanos }
        throughput("matrix", "inv/s (100x100)", 1, 30 / s + 2, 9) { b.matrix(100).nanos }
        throughput("lucene-index", "docs/s", 5_000 / s, 3, 5) { b.luceneIndex(5_000 / s).nanos }
        throughput("lucene-search", "queries/s", 2_000 / s, 3, 7) { b.luceneSearch(2_000 / s).nanos }
    }

    /** The bridge's own costs, timed in the app (round trips included). */
    fun bridge() {
        val n = 200_000 / scale
        throughput("call-noop", "calls/s", n, 2, 5) { timed { repeat(n) { b.noop() } } }
        throughput("call-int", "calls/s", n, 2, 5) { timed { repeat(n) { b.echoInt(it) } } }
        val kb = ByteArray(1024)
        throughput("echo-1KB", "calls/s", n / 4, 2, 5) { timed { repeat(n / 4) { b.echoBytes(kb) } } }
        val mb = ByteArray(1 shl 20)
        throughput("echo-1MB", "MB/s", 64 / scale + 4, 2, 5) { timed { repeat(64 / scale + 4) { b.echoBytes(mb) } } }
        val strings = List(1_000) { "item-$it" }
        throughput("echo-1000-strings", "calls/s", 500 / scale + 10, 2, 5) { timed { repeat(500 / scale + 10) { b.echoStrings(strings) } } }
        throughput("callback", "callbacks/s", n / 2, 2, 5) { timed { b.callbacks(n / 2) { it } } }
        throughput("suspend", "calls/s", 20_000 / scale, 2, 5) { timed { runBlocking { repeat(20_000 / scale) { b.suspendEcho(it) } } } }
        throughput("flow", "elements/s", n, 2, 5) { timed { runBlocking { b.numbers(n).count() } } }
    }
}
