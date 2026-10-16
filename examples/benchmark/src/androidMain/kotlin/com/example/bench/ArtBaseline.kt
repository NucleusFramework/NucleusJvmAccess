package com.example.bench

/** The same workloads as the façade's, run by ART: what an Android app gets without NJA. */
object ArtBaseline {
    fun runAll(runner: BenchmarkRunner) {
        runner.throughput("jackson", "orders/s", 1_000, 20, 9) { nanos { Workloads.jackson(1_000) } }
        runner.throughput("fft", "fft/s (2^14)", 1, 30, 9) { nanos { Workloads.fft(14) } }
        runner.throughput("matrix", "inv/s (100x100)", 1, 30, 9) { nanos { Workloads.matrix(100) } }
    }

    private inline fun nanos(block: () -> Long): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }
}
