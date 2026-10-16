package com.example.bench

/** iOS (simulator): the benchmarks on Zero, in a release binary. */
fun main() {
    BenchmarkRunner("ios-zero", scale = 10).runAll()
}
