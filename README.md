# Nucleus JVM Access

Use **Java libraries from Kotlin Multiplatform iOS and Android apps, on a real JVM** — [jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s
OpenJDK 27 — running **in the app's own process**. The other way round from
[Nucleus Native Access](https://github.com/NucleusFramework/NucleusNativeAccess): there, Kotlin/Native code used from the
JVM; here, JVM code used from Kotlin Multiplatform, with the same programming model.

```
Façade (Kotlin/JVM, Java 25)         The plugin generates                  The app (commonMain) sees
───────────────────────────         ────────────────────                  ─────────────────────────
class Search(dir: String) {    →    JVM: a dispatcher, compiled with  →   class Search : AutoCloseable {
  fun query(q: String):               the façade (no reflection)            fun query(q: String): List<Hit>
      List<Hit>                     App: proxies + a binary codec,          suspend fun index(...)
  suspend fun index(...)              one transport per platform            fun watch(): Flow<Event>
  fun watch(): Flow<Event>                                                 }
}                                                                          // backed by the JVM, in-process
```

You write a **façade**: a plain Kotlin/JVM module using any Java library (Lucene 10, Jackson, …). Its public API —
classes, interfaces, data classes, enums, collections, lambdas, `suspend`, `Flow` — becomes a Kotlin Multiplatform API
your iOS and Android code calls as if it were local.

## How it works

| | iOS | Android |
|---|---|---|
| JVM | Zero (jdk-mobile's interpreter: iOS forbids JIT) | HotSpot server VM (C2 JIT) |
| Where | Linked into the app (`libjvm.a`, static) | `libnja.so`: HotSpot + the JDK's natives + the bridge, loaded by ART |
| Calls | JNI from the calling Kotlin/Native thread (attached on first use, detached when it ends) | Handed from the ART thread to a HotSpot thread paired with it (futex channel, spin-then-sleep) |
| Callbacks (JVM → app) | JNI upcall on the same thread | The answer of the channel, served on the ART thread; nested calls recurse on the same pair |
| Fallback process | none | none |

**One process on Android.** ART and HotSpot can't share a *thread* (both keep their state in TLS and guard zones on the
stack), but they can share a *process*:

- **Symbols**: HotSpot and the JDK's natives are linked into one shared library, `-Bsymbolic`, loaded `RTLD_LOCAL` by
  `System.loadLibrary`: its `JNI_CreateJavaVM` never meets ART's. A small JDK patch
  ([`jdk-mobile/inprocess.patch`](jdk-mobile/inprocess.patch), on top of jdk-mobile's) makes HotSpot look its natives up
  in that library rather than in the process' main handle (`app_process`, where ART's `libopenjdk` lives).
- **Signals**: ART's `libsigchain` intercepts `sigaction`: HotSpot's `SIGSEGV` handler (implicit null checks, safepoint
  polls, stack banging) is chained behind ART's fault manager, which forwards the faults outside ART's code. `-Xrs`
  leaves `SIGQUIT` and the other signals to ART.
- **Threads**: each ART thread calling the JVM gets a HotSpot thread of its own (created by the bridge, 8 MB stack).
  Messages strictly alternate on their channel, so a callback and the calls it makes are served recursively by the same
  pair — the semantics of a JNI call on one thread. A round trip costs ~0.4 µs.

The VM's runtime (java.home, ~50 MB, per ABI) and the façades' class path ship in the APK's assets and are extracted
once per version; the VM's output goes to logcat (`NJA-JVM`).

**iOS** links jdk-mobile's static libraries into the app and starts the VM in-process; `java.home` is `<app>/lib`, the
class path `<app>/nja/classpath`.

## Quick start

```kotlin
// settings.gradle.kts: includeBuild the plugin (not published yet)

// search-facade/build.gradle.kts — the façade, on the JVM
plugins {
    kotlin("jvm")
    id("dev.nucleusframework.nja.facade")
}
kotlin { jvmToolchain(25) }
njaFacade { libName = "search" }
dependencies { implementation("org.apache.lucene:lucene-core:10.5.2") }

// shared/build.gradle.kts — the KMP module using it
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("dev.nucleusframework.nja")
}
kotlin {
    android { namespace = "com.example.shared"; compileSdk = 35; minSdk = 29 }
    iosArm64()
    iosSimulatorArm64()
}
nucleusJvm {
    facade(project(":search-facade"))
    jdkMobile = layout.projectDirectory.dir("jdk-mobile")   // see "jdk-mobile" below
    jvmOptions.addAll("-Xmx512m")
}
```

```kotlin
// commonMain — the façade's API, on both platforms
Search("/path/to/index").use { search ->
    val hits = search.query("kotlin")          // a call into the JVM
    search.watch().take(10).collect { ... }    // a JVM Flow, with backpressure
}
```

On iOS, test executables and executables get the runtime next to them automatically; an app copies
`build/nja/ios/<target>/` into its bundle (Run Script phase) and links with the options the plugin applies to the
Kotlin binaries (`-force_load` of jdk-mobile's static libraries, `-export_dynamic`).

## What crosses the bridge

The façade's public API, parsed from its sources with Kotlin PSI (NNA's parser), with NNA's type matrix: `Int`,
`Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String` (length-prefixed UTF-8: NUL included), `ByteArray`,
enums (verbatim, bodies included), data classes (verbatim), classes (proxies, `AutoCloseable`, released on `close()` or
when collected), open/abstract/sealed classes and interfaces (hierarchy mirrored, objects wrapped in their most specific
exported type), nested classes (`Outer_Inner`), companion objects, extension and top-level functions, nullable types,
`List`/`Set`/`Map` (nested), lambdas as parameters (callbacks, valid during the call, exceptions rethrown as themselves)
and as return values (JVM lambdas the app calls), `suspend` functions (cancellable both ways) and `Flow` (credit-based
backpressure).

JVM exceptions arrive as `JvmException` (with the JVM class name, message and stack); exceptions an app callback throws
come back out of the call as themselves. A closed proxy fails with `IllegalStateException`; `close()` releases the JVM
object once the calls using it are done (receiver *and* arguments are leased).

## Tests

`examples/calculator` runs **NNA's whole test suite** (its 46 files, 1,977 tests: types, callbacks, suspend, flows,
inheritance, load, concurrency, monkey and torture tests) against NNA's calculator example as a JVM façade — on iOS
and on Android, from `commonTest` — plus NJA's own torture suite (`NjaTortureTest`): JVM null checks and stack
overflows on 16 threads while the app faults and collects, JIT deoptimization storms, 300-level JVM ↔ app nesting,
callbacks invoked from the wrong JVM thread, thread churn (1,000 threads, no JVM thread leak), 24–48 MB payloads,
100k handles released by close and by collection, cancellation storms, 200k-element flows, a 20 s chaos soak on 24
threads.

```bash
./gradlew :examples:calculator:iosSimulatorArm64Test
```

```bash
./gradlew :examples:calculator:connectedAndroidDeviceTest
```

The suites needed only the edits a different runtime implies: JVM-only APIs replaced by common stand-ins
(`TortureSupport.kt`), test names restricted to what Kotlin/Native and DEX accept, `KotlinNativeException` →
`JvmException`, and three assertions that encoded NNA's limits or a race of the test itself (documented in place).

## Performance

See [BENCHMARKS.md](BENCHMARKS.md). In short, on Android NJA's HotSpot runs Jackson and Commons Math 1.8–7.8× faster
than ART, and Lucene 10 (Java 21, out of ART's reach) at desktop speed; an empty call costs 0.33 µs. On iOS, Zero (an
interpreter) suits occasional calls, not CPU-bound work; an empty call costs 2.4 µs.

## jdk-mobile

The plugin reads jdk-mobile's runtimes from `nucleusJvm.jdkMobile`, one directory per target (`android-aarch64`,
`android-x64`, `ios-simulator-aarch64`, `ios-device-aarch64`), each with `static/`, `include/` and `lib/` (java.home).
[jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s releases carry all of them (from `jdk-mobile-27b35.3`,
whose Android runtimes include the static libraries built with `inprocess.patch`):

```bash
./scripts/stage-jdk-mobile.sh .jdk-mobile/stage --release jdk-mobile-27b35.3
```

It downloads the four runtimes, checks their SHA-256 and lays them out. The patch NJA needs is in jdk-mobile's recipe
(`inprocess.patch`); [`jdk-mobile/inprocess.patch`](jdk-mobile/inprocess.patch) is a copy of it.

## Layout

- `plugin-build/` — the Gradle plugins (`dev.nucleusframework.nja.facade`, `dev.nucleusframework.nja`): PSI analysis
  (from NNA), member table, server and client generators, Android linking/packaging, iOS linking/bundling.
- `runtime/server` — runs in the mobile JVM: dispatch, handles, async (suspend/Flow), the Android and iOS links.
- `runtime/client` — KMP: codec, call scopes and leases, proxies' base, async, the Android and iOS transports.
- `runtime/android-native` — the ART ↔ HotSpot bridge (C).
- `examples/` — `calculator` (NNA's suites), `torture-facade`, `benchmark*`.
