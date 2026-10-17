# Benchmarks

Measured on 2026-10-16, on an Apple Silicon Mac (10 cores):

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
