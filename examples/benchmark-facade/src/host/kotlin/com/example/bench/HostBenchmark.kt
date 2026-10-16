package com.example.bench

/** The workloads on the desktop's HotSpot: the reference for the mobile JVMs' numbers. */
fun main() {
    val b = Benchmarks()
    fun bench(name: String, unit: String, perRun: Int, warmup: Int, runs: Int, block: () -> Sample) {
        repeat(warmup) { block() }
        val samples = List(runs) { block().nanos }.sorted()
        val median = samples[samples.size / 2]
        println("BENCH|host-hotspot|$name|${"%.1f".format(perRun * 1e9 / median)}|$unit")
    }
    println("BENCH|host-hotspot|vm|${b.vmName()}|")
    bench("jackson", "orders/s", 1_000, 20, 15) { b.jackson(1_000) }
    bench("fft", "fft/s (2^14)", 1, 30, 15) { b.fft(14) }
    bench("matrix", "inv/s (100x100)", 1, 30, 15) { b.matrix(100) }
    bench("lucene-index", "docs/s", 5_000, 3, 5) { b.luceneIndex(5_000) }
    bench("lucene-search", "queries/s", 2_000, 5, 9) { b.luceneSearch(2_000) }
}
