package com.example.benchapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.example.bench.ArtBaseline
import com.example.bench.BenchmarkRunner

/** Runs ART's baseline, then the same workloads (and Lucene) on NJA's HotSpot; results go to logcat (tag NJA-BENCH). */
class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            val log: (String) -> Unit = { line -> Log.i("NJA-BENCH", line) }
            try {
                // Two rounds each, alternated: the second ones are the steady state (both JITs warm, no
                // interference from the other runtime's background compilation)
                val hotspot = BenchmarkRunner("android-hotspot", report = log)
                val art = BenchmarkRunner("android-art", report = log)
                hotspot.runAll()
                ArtBaseline.runAll(art)
                hotspot.libraries()
                ArtBaseline.runAll(art)
            } catch (e: Throwable) {
                Log.e("NJA-BENCH", "failed", e)
            }
            log("BENCH|all|finished||")
        }, "bench").start()
    }
}
