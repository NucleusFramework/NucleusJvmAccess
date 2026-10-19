# Benchmarks

Two sets of measurements:

- [Java libraries](#java-libraries) and [the bridge](#the-bridge): NJA's own examples, on jdk-mobile's JVM (2026-10-16).
- [A real app](#a-real-app-lucene-search-in-zayit): Zayit's search, a Lucene 10 index of 7M documents, on each runtime
  NJA offers: jdk-mobile's JVM, a GraalVM native image, and ART, compared with Lucene patched to run on ART
  (2026-10-17).

Measured on an Apple Silicon Mac (10 cores):

- **Android**: the `zayit35` emulator (API 35, arm64, 4 vCPUs, hypervisor-backed), with the release, non-debuggable app
  `examples/benchmark-app`. That means ART's JIT is not slowed down by debugging.
- **iOS**: the iPhone 17 Pro simulator (iOS 26.2), with the release Kotlin/Native executable `examples/benchmark`
  (`bench.kexe`).
- **Desktop reference**: HotSpot 25.0.3 on the host, running the same workloads (`:examples:benchmark-facade:runHostBenchmark`).

Emulators and simulators run on the host's cores: these numbers compare the runtimes with each other; they are not
measurements of a phone. Every workload is a library call, timed inside the runtime that runs it. Each value is the
median of the measured runs, taken after a warmup.

## Java libraries

The Android numbers are the steady state: the second round, after one alternating round of each runtime (see below).

| Workload | ART (Android baseline) | NJA HotSpot (Android) | NJA Zero (iOS) | HotSpot (desktop) |
|---|---:|---:|---:|---:|
| Jackson 2.22: 1,000 orders → JSON → objects | 150k orders/s | **497k orders/s** (3.3×) | 3.8k orders/s | 336k orders/s |
| Commons Math 3.6: FFT 2^14 forward + inverse | 953 /s | **1,680 /s** (1.8×) | 27 /s | 1,510 /s |
| Commons Math 3.6: LU inversion of a 100×100 matrix | 194 /s | **1,510 /s** (7.8×) | 5.0 /s | 1,290 /s |
| Lucene 10.5: indexing (in memory, standard analyzer) | — (needs Java 21) | **99.7k docs/s** | 823 docs/s | 89k docs/s |
| Lucene 10.5: term / boolean / phrase search | — (needs Java 21) | **92.8k queries/s** | 1.7k queries/s | 94k queries/s |

- **Android**: HotSpot (C2) in the app's process runs these libraries 1.8–7.8× faster than ART, at desktop HotSpot's
  level. Lucene 10 can't run on ART at all.
- **iOS**: Zero is an interpreter, because iOS forbids generating code. It runs 60–260× slower than a JIT. That is fine
  for occasional calls (parsing, a search over a modest index); it is not suited to CPU-bound work.
- **Warmup on Android**: HotSpot's first, cold round, measured right after the JVM starts, is lower (FFT 73/s, matrix
  828/s, Lucene 60k docs/s). C2 needs a few hundred runs to get there.

## The bridge

Each call is measured from the app, round trip included.

| | NJA Android (ART → HotSpot) | NJA iOS (K/N → Zero) |
|---|---:|---:|
| JVM start (first launch includes extracting the runtime) | 0.8 s (1.9 s on first launch) | 0.9 s |
| Empty call | 3.05 M calls/s (0.33 µs) | 412k calls/s (2.4 µs) |
| Call `Int` → `Int` | 3.06 M calls/s | 398k calls/s |
| Callback (JVM → app → JVM) | 2.67 M /s | 441k /s |
| `ByteArray` 1 KB round trip | 514k calls/s | 261k calls/s |
| `ByteArray` 1 MB round trip | 1.1 GB/s | 1.1 GB/s |
| `List<String>` of 1,000 round trip | 3.3k calls/s | 264 calls/s |
| `suspend` completing without suspending | 1.63 M calls/s | 62k calls/s |
| `Flow<Int>` elements | 298k /s | 174k /s |

- **Android cost**: switching from the ART thread to its paired HotSpot thread and back. The bridge spins for about
  60 µs before sleeping on its futex.
- **iOS cost**: the dispatch code that Zero interprets on the JVM side.
- **Collections of objects** (here 1,000 strings) are encoded element by element in the JVM. On Zero that cost is
  significant: prefer `ByteArray` or a few large values for bulk data.
- **`suspend`**: a JVM function that completes without suspending answers within the call itself, with no event and no
  extra thread. Before this optimization: 4.9k calls/s on Android and 13k on iOS. A call that actually suspends goes
  through the events: the JVM's coroutine, then the app's polling thread.

## A real app: Lucene search in Zayit

[Zayit](https://github.com/kdroidFilter/Zayit)'s search runs Lucene 10 through NJA: its façade is SeforimLibrary's
`search-facade`. The index holds 7M documents (Hebrew texts, with int8 384-dimension vectors for semantic search). The
whole search is measured: Lucene through NJA, plus the app's Kotlin around it (SQLite, snippets, highlights, and the ONNX
model for hybrid search).

- **Android**: the release benchmark app on the `zayit35` emulator (Android 15, arm64). Each value is the median of 3
  runs, with the page cache dropped before each. The GraalVM image was built on Linux, the APK on macOS.
- **iOS**: the release test executable in the iPhone 17 Pro simulator.

"Hybrid" is lexical and semantic search fused; "lexical" is lexical only. ART here is the app's former approach, an
older Lucene patched to run on ART; NJA's `ART` runtime can't run Lucene 10 (it needs Java 21).

### Android

| | Lucene patched on ART | NJA `JVM` (HotSpot) | NJA `GRAALVM` |
|---|---:|---:|---:|
| First search, cold (runtime start included), hybrid | **1.41 s** | 2.11 s | 1.51 s |
| First search, cold, lexical | 0.92 s | 1.55 s | **0.91 s** |
| 5 warm rounds, total, hybrid | 5.82 s | **5.27 s** | 6.01 s |
| 5 warm rounds, total, lexical | 7.29 s | **5.75 s** | 7.78 s |
| Search, first page, hybrid | 83 ms | **75 ms** | 85 ms |
| Search, first page, lexical | 75 ms | **59 ms** | 79 ms |
| Search, next page, lexical | 75 ms | **58 ms** | 76 ms |
| Facets, hybrid | 8.0 ms | 8.1 ms | 7.8 ms |
| Peak memory (PSS), hybrid | **426 MB** | 559 MB | 481 MB |
| Peak memory (PSS), lexical | 337 MB | 332 MB | **329 MB** |
| App per arm64 device (installed) | **42.7 MB** | ~98 MB | 58.1 MB |
| Of which Lucene's runtime | ~3 MB (dex) | ~58 MB (HotSpot, `java.home`) | ~19 MB (`libnja.so`, ~7 MB compressed) |

- **GraalVM** starts as fast as ART, with about the same speed and memory, and runs the real Lucene 10.
- **HotSpot** is the fastest once warm (C2), but starts ~0.6 s later and is the heaviest.
- The index is read through NIO on Android: memory-mapped, its pages would count in the app's PSS (1.4 GB peak in
  hybrid search), which Android's low-memory killer watches.

### iOS

HotSpot can't run on iOS (no JIT allowed): NJA's `JVM` runtime there is Zero, an interpreter.

| | NJA `JVM` (Zero) | NJA `GRAALVM` |
|---|---:|---:|
| First search, cold (runtime start included), hybrid | 4.63 s | **0.82 s** |
| First search, cold, lexical | 4.15 s | **0.52 s** |
| Search, first page, hybrid | 1.18 s | **144 ms** |
| Search, first page, lexical | 1.11 s | **142 ms** |
| Search, next page, lexical | 1.11 s | **127 ms** |
| Facets | 148 ms | **18 ms** |
| Book suggestions | 67 ms | **48 ms** |
| Memory after warmup, hybrid (peak) | 386 MB (422) | 390 MB (430) |
| Memory after warmup, lexical (peak) | 148 MB (163) | **124 MB (155)** |
| Added to the app | ~50 MB (Zero, `java.home` bundled) | **~20 MB** (one object, ~7 MB compressed) |

- **GraalVM** is about 8× faster than Zero and starts 5–8× sooner, at the same memory (the ONNX model dominates hybrid
  search) and less than half the size.
- The search's results are byte-identical to the desktop's JVM, hybrid search included.

### Lucene alone

The same façade's searches, without the app around them, warm:

| | HotSpot (desktop) | `GRAALVM` (iOS simulator) | `GRAALVM` (Android emulator) |
|---|---:|---:|---:|
| Phrase search | 1–2 ms | 1–2 ms | 1–3 ms |
| Facets | 1–3 ms | 1–4 ms | 1–4 ms |
| KNN (50 nearest), warm page cache | ~2 ms | ~1.5 ms | 3–10 ms |
| KNN, cold page cache | 130–250 ms | 130–250 ms | 130–500 ms |
| Memory | – | 61 MB | 63 MB (RSS) |

A native image runs Lucene at HotSpot's speed. KNN depends on the page cache, not on the runtime.

### Correctness

The example's suites (2008 tests) pass on every runtime: `GRAALVM` on the iOS simulator and an Android x86_64 emulator
(CI), `JVM` and `ART` on an Android 15 emulator, and the JVM target.

## Reproduce

The runtimes are jdk-mobile's latest release, which the plugin downloads.

```bash
./gradlew :examples:benchmark-facade:runHostBenchmark
```

```bash
./gradlew :examples:benchmark:linkBenchReleaseExecutableIosSimulatorArm64
```

```bash
xcrun simctl spawn booted examples/benchmark/build/bin/iosSimulatorArm64/benchReleaseExecutable/bench.kexe
```

```bash
./gradlew :examples:benchmark-app:installRelease
```

```bash
adb shell am start -n com.example.benchapp/.BenchmarkActivity && adb logcat -s NJA-BENCH
```

APK size: 58 MB. That covers `libnja.so` (25 MB: HotSpot and the JDK's natives) and the runtime plus class path in the
assets (28 MB compressed, Lucene and Jackson included).
