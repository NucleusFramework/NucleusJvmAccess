# Nucleus JVM Access

> [!WARNING]
> **Highly experimental.** This project is a proof of concept: its APIs, its Gradle plugins and the runtimes it relies
> on can change at any time, and it may not be maintained. Don't depend on it for production apps.

Use **Java libraries from Kotlin Multiplatform iOS and Android apps, on a real JVM** — [jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s
OpenJDK 27 — running **in the app's own process**.

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

| | iOS | Android | JVM (desktop) |
|---|---|---|---|
| JVM | Zero (jdk-mobile's interpreter: iOS forbids JIT) | HotSpot server VM (C2 JIT) | The app's own |
| Where | Linked into the app (`libjvm.a`, static) | `libnja.so`: HotSpot + the JDK's natives + the bridge, loaded by ART | The façades on the app's class path |
| Calls | JNI from the calling Kotlin/Native thread (attached on first use, detached when it ends) | Handed from the ART thread to a HotSpot thread paired with it (futex channel, spin-then-sleep) | A plain call into the dispatcher, on the calling thread |
| Callbacks (JVM → app) | JNI upcall on the same thread | The answer of the channel, served on the ART thread; nested calls recurse on the same pair | A plain call back, on the same thread |
| Fallback process | none | none | none |

**A JVM target** (`jvm()`, a desktop app) sees the same proxies as the mobile ones, with the façades in its own JVM:
the plugin puts them on the target's runtime class path, and the messages are still encoded (the app's code is the
same everywhere). Both the façades and their proxies being on that class path, the proxies need a package of their own:
set `njaFacade { clientPackage = "…" }` (the plugin checks it before the JVM target compiles).

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
    jvmOptions.addAll("-Xmx512m")
    // The JVM's runtimes: jdk-mobile's latest release, downloaded (see "jdk-mobile" below)
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

The façade's public API, parsed from its sources with Kotlin PSI: `Int`,
`Long`, `Double`, `Float`, `Boolean`, `Byte`, `Short`, `String` (length-prefixed UTF-8: NUL included), `ByteArray`,
`IntArray`, `LongArray`, `FloatArray`, `DoubleArray`, enums (verbatim, bodies included), data classes (verbatim), sealed
value hierarchies (a sealed interface or class whose nested cases are all data classes or objects: verbatim, by value,
recursive ones included), classes (proxies, released on `close()` or when collected; an `AutoCloseable`'s `close()`
closes the JVM object, then releases it), default arguments (overloads: the JVM applies the defaults), open/abstract/sealed classes and interfaces (hierarchy mirrored, objects wrapped in their most specific
exported type), nested classes (`Outer_Inner`), companion objects, extension and top-level functions, nullable types,
`List`/`Set`/`Map` (nested), lambdas as parameters (callbacks, valid during the call, exceptions rethrown as themselves)
and as return values (JVM lambdas the app calls), `suspend` functions (cancellable both ways) and `Flow` (credit-based
backpressure).

Private and internal declarations stay the JVM's, as does a constructor that is private, internal, or takes what can't
cross; a function whose parameters or result can't cross is left out.

JVM exceptions arrive as `JvmException` (with the JVM class name, message and stack); exceptions an app callback throws
come back out of the call as themselves. A closed proxy fails with `IllegalStateException`; `close()` releases the JVM
object once the calls using it are done (receiver *and* arguments are leased).

## Tests

`examples/calculator` runs a suite of 2,008 tests (types, arrays, sealed values, callbacks, suspend, flows, inheritance,
load, concurrency, monkey and torture tests) against a calculator façade, on iOS, on Android and on the JVM, from
`commonTest`, plus NJA's own
torture suite (`NjaTortureTest`): JVM null checks and stack overflows on 16 threads while the app faults and
collects, JIT deoptimization storms, 300-level JVM ↔ app nesting, callbacks invoked from the wrong JVM thread, thread
churn (1,000 threads, no JVM thread leak), 24–48 MB payloads, 100k handles released by close and by collection,
cancellation storms, 200k-element flows, a 20 s chaos soak on 24 threads.

```bash
./gradlew :examples:calculator:iosSimulatorArm64Test
```

```bash
./gradlew :examples:calculator:connectedAndroidDeviceTest
```

```bash
./gradlew :examples:calculator:jvmTest
```

## Performance

See [BENCHMARKS.md](BENCHMARKS.md). In short, on Android NJA's HotSpot runs Jackson and Commons Math 1.8–7.8× faster
than ART, and Lucene 10 (Java 21, out of ART's reach) at desktop speed; an empty call costs 0.33 µs. On iOS, Zero (an
interpreter) suits occasional calls, not CPU-bound work; an empty call costs 2.4 µs. A GraalVM native image (`GRAALVM`) runs at HotSpot's
speed on both: on a real app's Lucene search, 8× faster than Zero on iOS, and on par with Lucene patched for ART on
Android, at about its memory.

## jdk-mobile

The JVM's runtimes are [jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s releases (from
`jdk-mobile-27b35.3`, whose Android runtimes include the static libraries built with `inprocess.patch`). The plugin
downloads those the module needs (the Android ABIs it ships, its iOS targets), checks them against the `.sha256`
published with them, keeps them in Gradle's cache (`~/.gradle/caches/nja/jdk-mobile`: a `clean` downloads nothing) and
extracts them into `build/nja/jdkMobile`; the tasks reading them depend on it (`njaJdkMobile<Target>`).

```kotlin
nucleusJvm {
    jdkMobileRelease = "jdk-mobile-27b35.3"          // default: latest (resolved once a day; offline, the last one)
    jdkMobileRepository = "NucleusFramework/openjdk-mobile"  // the default
    jdkMobile = layout.projectDirectory.dir("my-jdk") // instead: runtimes of one's own, used as they are
}
```

With `latest`, a new jdk-mobile release reaches the next build: pin `jdkMobileRelease` for builds that must not move.
Runtimes of one's own are one directory per target (`android-aarch64`, `android-x64`, `ios-simulator-aarch64`,
`ios-device-aarch64`), each with `static/`, `include/` and `lib/` (java.home); `scripts/stage-jdk-mobile.sh` lays out
a local OpenJDK build that way.

### How jdk-mobile is built

[NucleusFramework/openjdk-mobile](https://github.com/NucleusFramework/openjdk-mobile) is a recipe, not a fork of the
JDK: it builds a stable OpenJDK (the 27 line, from [openjdk/jdk27u](https://github.com/openjdk/jdk27u)'s GA releases)
with two patches on top:

- `openjdk-mobile.patch`: [openjdk/mobile](https://github.com/openjdk/mobile)'s iOS and Android ports, carried over to
  the stable line, with the fixes they need there (Zero's W^X handling on iOS, bionic and an NDK build from a macOS host
  on Android);
- `inprocess.patch`: what NJA needs to run HotSpot inside an app's process on Android — the VM looks its natives up in
  the shared library holding it (loaded `RTLD_LOCAL` by ART) rather than in the process' own handle.

Its `build.sh` builds one target from an OpenJDK checkout: Zero (the interpreter) as static libraries for iOS, the
server VM (C2 JIT) for Android — as static libraries, which NJA links into `libnja.so`, and as a launcher for apps that
run Java in a process of its own. Its CI follows the JDK's line: every day, and on each change to the recipe, it builds
the latest GA for the four targets, smoke-tests the Android runtime on an emulator and publishes a release named
`jdk-mobile-<version>b<build>.<revision>` (`jdk-mobile-27b35.3`: JDK 27, its GA build 35, the recipe's third release
on it).

To build a runtime yourself (autoconf, a boot JDK 26 or 27; Xcode for iOS, the Android NDK for Android):

```bash
git clone https://github.com/NucleusFramework/openjdk-mobile.git
```

```bash
git clone --depth 1 --branch jdk-27-ga https://github.com/openjdk/jdk27u.git jdk
```

```bash
openjdk-mobile/build.sh android-aarch64 jdk   # or android-x64, ios-simulator, ios-device
```

The result (`dist/jdk-mobile-<target>.tar.gz`) has the release's layout; extract it into `<stage>/<target>` for
`nucleusJvm.jdkMobile`. [`jdk-mobile/inprocess.patch`](jdk-mobile/inprocess.patch) is a copy of the recipe's.

## Runtimes

What runs the façades is chosen per platform: `runtime` (both), `iosRuntime`, `androidRuntime`
(`import dev.nucleusframework.nja.plugin.NjaRuntimeType`).

| `NjaRuntimeType` | iOS | Android | The plugin downloads |
|---|---|---|---|
| `JVM` (default) | Zero, linked into the app | HotSpot in the app's process (Android 10+) | jdk-mobile's runtimes |
| `GRAALVM` | the façades' native image | the façades' native image (Android 10+, built on Linux) | graalvm-mobile's kit, GraalVM CE |
| `ART` | – (iOS keeps `JVM`) | the façades dexed with the app, on ART itself | nothing |

`ART` is for Java libraries ART runs as they are, whose module targets iOS through NJA: the façades and NJA's server
become the app's own dependencies, called in process like on a JVM target; no runtime in the app, and the app's own
`minSdk`.

## GraalVM native images

Instead of jdk-mobile's JVM, the façades can run compiled ahead of time into a GraalVM native image, linked into the
app: no interpreter on iOS (Zero runs searches 100× slower than an image), no JIT nor class metadata on Android.

```kotlin
nucleusJvm {
    runtime = NjaRuntimeType.GRAALVM            // iOS and Android; default: NjaRuntimeType.JVM
    androidRuntime = NjaRuntimeType.JVM         // or per platform (iosRuntime, androidRuntime)
    graalvmMobileRelease = "graalvm-mobile-25.4.4.1.1b1.1"  // default: latest
    nativeImageOptions.add("-H:+ReportExceptionStackTraces")
}
```

(`import dev.nucleusframework.nja.plugin.NjaRuntimeType` in the build script.) The plugin downloads, into Gradle's
cache, [graalvm-mobile](https://github.com/NucleusFramework/graalvm-mobile)'s kit for each target (GraalVM CE's static
libraries for iOS and Android, rebuilt by its CI for each GraalVM CE release) and the GraalVM CE release it is for, for
the build host; `njaNativeImage<Target>` compiles the façades with it into an object, which the iOS binaries and
Android's `libnja.so` link. The app's code doesn't change: the client finds the image's entry points at run time.

- **Reachability metadata**: libraries reaching classes by reflection or resources bring their native-image metadata
  in their jars (`META-INF/native-image/<group>/<artifact>/reachability-metadata.json`), as GraalVM's own
  [reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/) requires; trace it
  with the native-image agent on a JVM (`-agentlib:native-image-agent=config-output-dir=...`). NJA's dispatchers need
  none.
- **Options**: `jvmOptions`' `-Xmx` becomes the image's maximum heap (`-R:MaxHeapSize`), its `-D` system properties
  are kept; `nativeImageOptions` go to native-image as they are.
- **Android**: arm64 and x86_64 (the emulators). native-image compiles its host's Java classes, so an Android image
  builds on Linux only (a CI); on macOS, keep `androidRuntime = NjaRuntimeType.JVM` for local builds. native-image runs
  small C programs for the target: on a Linux host of another architecture (arm64 images on x64), through qemu-user
  (`qemu-user-static` with binfmt). GraalVM has no FFM
  API (`java.lang.foreign`) for Android: the libraries must do without it.

## Layout

- `plugin-build/` — the Gradle plugins (`dev.nucleusframework.nja.facade`, `dev.nucleusframework.nja`): PSI analysis,
  member table, server and client generators, Android linking/packaging, iOS linking/bundling.
- `runtime/server` — runs in the mobile JVM: dispatch, handles, async (suspend/Flow), the Android and iOS links.
- `runtime/client` — KMP: codec, call scopes and leases, proxies' base, async, the Android and iOS transports.
- `runtime/android-native` — the ART ↔ HotSpot bridge (C).
- `examples/` — `calculator` (the test suites), `torture-facade`, `benchmark*`.
