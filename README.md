# Nucleus JVM Access

> [!WARNING]
> **Highly experimental.** This project is a proof of concept: its APIs, its Gradle plugins and the runtimes it relies
> on can change at any time, and it may not be maintained. Don't depend on it for production apps.

Use **Java libraries from Kotlin Multiplatform apps** (iOS, Android, macOS, Linux, Windows) **in the app's own
process**. By default the libraries are compiled ahead of time into a **GraalVM native image**. A real JVM can run them
instead: [jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s OpenJDK on mobile, a JDK's HotSpot on the
desktop.

```
Façade (Kotlin/JVM, Java 25)         The plugin generates                  The app (commonMain) sees
───────────────────────────         ────────────────────                  ─────────────────────────
class Search(dir: String) {    →    Runtime: a dispatcher, compiled   →   class Search : AutoCloseable {
  fun query(q: String):               with the façade (no reflection)       fun query(q: String): List<Hit>
      List<Hit>                     App: proxies + a binary codec,          suspend fun index(...)
  suspend fun index(...)              one transport per platform            fun watch(): Flow<Event>
  fun watch(): Flow<Event>                                                 }
}                                                                          // in-process, no server
```

You write a **façade**: a plain Kotlin/JVM module using any Java library (Lucene 10, Jackson, …). Its public API
(classes, interfaces, data classes, enums, collections, lambdas, `suspend`, `Flow`) becomes a Kotlin Multiplatform API
your common code calls as if it were local. The app's code is the same whatever runs the façades.

## Platforms and runtimes

| Platform | `GRAALVM` (default) | `JVM` | `ART` |
|---|---|---|---|
| iOS (arm64, simulator arm64) | The façades' native image, linked into the app | jdk-mobile's Zero (an interpreter: iOS forbids JIT), linked into the app | – |
| Android (arm64, x86_64), 10+ | The façades' native image in `libnja.so`, loaded by ART | jdk-mobile's HotSpot (C2 JIT) in `libnja.so`, next to ART | The façades dexed with the app, on ART itself |
| macOS, Linux, Windows (Kotlin/Native) | The façades' native image, a shared library next to the executable | A JDK's HotSpot, loaded from the app's `nja/runtime` or `JAVA_HOME` | – |
| JVM target (`jvm()`) | The façades on the app's own class path | same | same |

Choose per platform with `runtime` (all), `iosRuntime`, `androidRuntime`, `desktopRuntime`
(`import dev.nucleusframework.nja.plugin.NjaRuntimeType`):

```kotlin
nucleusJvm {
    runtime = NjaRuntimeType.GRAALVM          // the default
    androidRuntime = NjaRuntimeType.JVM       // e.g. HotSpot's JIT on Android
}
```

- **Why GraalVM by default**: no interpreter on iOS (Zero runs Lucene searches about 100× slower than an image), no
  JIT warmup and no class metadata on Android, no JVM to ship on the desktop, and a fast start everywhere.
- **What it costs**: an image builds on its own platform's host (iOS and macOS images on macOS, Android and Linux ones
  on Linux, Windows ones on Windows), libraries reaching classes by reflection need their reachability metadata, and
  Android has no FFM API (`java.lang.foreign`).
- **Android on a non-Linux host**: an Android native image builds on Linux only, so on macOS (a local build) Android
  runs the JVM unless `androidRuntime` is set. Set `androidRuntime = NjaRuntimeType.GRAALVM` to get an error rather than
  that fallback.
- **`ART`** is for Java libraries ART runs as they are, in a module that also targets iOS through NJA. The façades and
  NJA's server become the app's own dependencies, called in-process as on a JVM target, with the app's own `minSdk`.
  The other platforms then run `GRAALVM`.

## Quick start

```kotlin
// settings.gradle.kts: includeBuild the plugin (not published yet)

// search-facade/build.gradle.kts: the façade, on the JVM
plugins {
    kotlin("jvm")
    id("dev.nucleusframework.nja.facade")
}
kotlin { jvmToolchain(25) }
njaFacade { libName = "search" }
dependencies { implementation("org.apache.lucene:lucene-core:10.5.2") }

// shared/build.gradle.kts: the KMP module using it
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("dev.nucleusframework.nja")
}
kotlin {
    android { namespace = "com.example.shared"; compileSdk = 35; minSdk = 29 }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()
    linuxX64()
    mingwX64()
}
nucleusJvm {
    facade(project(":search-facade"))
    jvmOptions.addAll("-Xmx512m")   // a native image keeps -Xmx (its maximum heap) and -D
}
```

```kotlin
// commonMain: the façade's API, on every platform
Search("/path/to/index").use { search ->
    val hits = search.query("kotlin")          // a call into the runtime
    search.watch().take(10).collect { ... }    // a JVM Flow, with backpressure
}
```

- **iOS**: binaries link the runtime. An app built with Xcode links with the options the plugin applies to the Kotlin
  binaries. With the JVM, it also copies `build/nja/ios/<target>/` (java.home, class path) into its bundle (Run Script
  phase).
- **Android**: the plugin adds `libnja.so` and the runtime's assets to the app's variants.
- **Desktop**: executables and test executables get the runtime in `nja/` next to them (see
  [Kotlin/Native on the desktop](#kotlinnative-on-the-desktop)).

## GraalVM native images

`njaNativeImage<Target>` (mobile) and `njaNativeLibrary` (desktop) compile the façades, with NJA's server, into a native
image with GraalVM CE's native-image. The app's code doesn't change: the client finds the image's entry points at run
time.

```kotlin
nucleusJvm {
    graalvmMobileRelease = "graalvm-mobile-25.5.5.0.3b1.2"  // mobile: graalvm-mobile's kit (default: latest)
    graalvmRelease = "graal-25.5.5.0.3"                   // desktop: GraalVM CE's release (default: latest)
    nativeImageOptions.add("-H:+ReportExceptionStackTraces")
}
```

- **Mobile**: GraalVM CE ships no static libraries for iOS or Android.
  [graalvm-mobile](https://github.com/NucleusFramework/graalvm-mobile)'s kits rebuild them for each GraalVM CE release
  and name the release they are for. The plugin downloads the kit for each target and that GraalVM CE for the build host
  into Gradle's cache. native-image cross-compiles a relocatable object, which the iOS binaries and Android's
  `libnja.so` link.
- **Desktop**: native-image builds a shared library for its own host (`libnja.dylib`, `libnja.so`, `nja.dll`). On
  Windows it needs Visual Studio's C toolchain (the "x64 Native Tools" environment, or an installation native-image
  finds).
- **Reachability metadata**: libraries reaching classes by reflection or resources bring their native-image metadata in
  their jars (`META-INF/native-image/<group>/<artifact>/reachability-metadata.json`), as GraalVM's own
  [reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/) requires. Trace it with
  the native-image agent on a JVM (`-agentlib:native-image-agent=config-output-dir=...`). NJA's dispatchers need none.
- **Options**: the `-Xmx` in `jvmOptions` becomes the image's maximum heap (`-R:MaxHeapSize`), and its `-D` system
  properties are kept. `nativeImageOptions` go to native-image as they are.
- **Android**: arm64 and x86_64 (the emulators). native-image compiles its host's Java classes, so an Android image
  builds on Linux only (a CI runner, a Linux container). native-image also runs small C programs for the target: on a
  Linux host of another architecture (arm64 images on x64), they run through qemu-user (`qemu-user-static` with binfmt).
  The kit's Linux libraries are built against glibc: its `libglibc_compat.a` adapts them to bionic, including the
  resolver's `struct addrinfo` (from `graalvm-mobile-25.5.5.0.3b1.2`: with earlier kits, `InetAddress` lookups crash).
- **Virtual threads**: `StackValue` throws on a virtual thread. In code virtual threads may run, pass native memory
  through `UnmanagedMemory` or pinned arrays.

## Kotlin/Native on the desktop

macOS (`macosArm64`, `macosX64`), Linux (`linuxX64`, `linuxArm64`) and Windows (`mingwX64`) binaries don't link the
runtime: they load it when the app first calls a façade, from the `nja` directory next to the executable, or from
`NJA_HOME` when it is set (a library embedded in another program):

| Runtime | `nja/` holds | The plugin puts it there |
|---|---|---|
| `GRAALVM` | `libnja.dylib`, `libnja.so` or `nja.dll` | For executables and tests of the build host's target |
| `JVM` | `classpath/` (the façades and NJA's server), `jvm.options`, optionally `runtime/` (a JDK or a `jlink`ed runtime, Java 25+; else `JAVA_HOME`'s) | For all executables and tests; `runtime/` is yours to add |

- **Cross-compiling**: Kotlin/Native builds Linux and Windows binaries on macOS, but native-image builds for its own
  host only. An executable built for another platform gets no native image (a warning says so). Build it on its own OS,
  or ship the library built there in its `nja/`.
- **Linux arm64**: Kotlin/Native doesn't run on Linux arm64 hosts, so `linuxArm64` binaries are always cross-compiled.
  With `GRAALVM`, build the native image on a Linux arm64 machine (`native-image --shared` of the façades' class path,
  as `njaNativeLibrary` does) and ship it in `nja/`; the `JVM` runtime needs nothing built there.
- **The JVM's tests** run on Gradle's Java 25 toolchain (their `JAVA_HOME`).
- **Shipping**: copy `nja/` next to the app's executable, or set `NJA_HOME`. A shared library or framework the app
  links finds `nja/` next to the executable that loaded it.
- The binaries need nothing else: on Linux, `libdl` and `libpthread`, which the client's klib adds to their link.

## The JVM runtime

### How it works

| | iOS | Android | Desktop (Kotlin/Native) |
|---|---|---|---|
| JVM | jdk-mobile's Zero (iOS forbids JIT) | jdk-mobile's HotSpot server VM (C2 JIT) | A JDK's HotSpot |
| Where | Linked into the app (`libjvm.a`, static) | `libnja.so`: HotSpot + the JDK's natives + the bridge, loaded by ART | `libjvm` loaded at run time |
| Calls | JNI from the calling Kotlin/Native thread (attached on first use, detached when it ends) | Handed from the ART thread to a HotSpot thread paired with it (futex channel, spin-then-sleep) | As on iOS |
| Callbacks (JVM → app) | JNI upcall on the same thread | The answer of the channel, served on the ART thread; nested calls recurse on the same pair | As on iOS |

A native image works like the iOS column on every platform: the app's threads attach to the image's isolate and call
its entry points, and the image calls the app back on the same thread.

**One process on Android.** ART and HotSpot can't share a *thread* (both keep their state in TLS and guard zones on the
stack), but they can share a *process*:

- **Symbols**: HotSpot and the JDK's natives are linked into one shared library, `-Bsymbolic`, loaded `RTLD_LOCAL` by
  `System.loadLibrary`: its `JNI_CreateJavaVM` never meets ART's. A small JDK patch
  ([`jdk-mobile/inprocess.patch`](jdk-mobile/inprocess.patch), on top of jdk-mobile's) makes HotSpot look its natives up
  in that library rather than in the process' main handle (`app_process`, where ART's `libopenjdk` lives).
- **Signals**: ART's `libsigchain` intercepts `sigaction`. HotSpot's `SIGSEGV` handler (implicit null checks, safepoint
  polls, stack banging) is chained behind ART's fault manager, which forwards the faults outside ART's code. `-Xrs`
  leaves `SIGQUIT` and the other signals to ART.
- **Threads**: each ART thread calling the JVM gets a HotSpot thread of its own (created by the bridge, 8 MB stack).
  Messages strictly alternate on their channel, so a callback and the calls it makes are served recursively by the same
  pair, with the semantics of a JNI call on one thread. A round trip costs about 0.4 µs.

The VM's runtime (java.home, ~50 MB, per ABI) and the façades' class path ship in the APK's assets and are extracted
once per version; the VM's output goes to logcat (`NJA-JVM`). On iOS, `java.home` is `<app>/lib` and the class path is
`<app>/nja/classpath`.

### jdk-mobile

The mobile JVMs are [jdk-mobile](https://github.com/NucleusFramework/openjdk-mobile)'s releases (from
`jdk-mobile-27b35.3`, whose Android runtimes include the static libraries built with `inprocess.patch`). The plugin
downloads those the module needs (the Android ABIs it ships, its iOS targets), checks them against the `.sha256`
published with them, keeps them in Gradle's cache (`~/.gradle/caches/nja/jdk-mobile`: a `clean` downloads nothing) and
extracts them into `build/nja/jdkMobile`.

```kotlin
nucleusJvm {
    jdkMobileRelease = "jdk-mobile-27b35.3"          // default: latest (resolved once a day; offline, the last one)
    jdkMobileRepository = "NucleusFramework/openjdk-mobile"  // the default
    jdkMobile = layout.projectDirectory.dir("my-jdk") // instead: runtimes of one's own, used as they are
}
```

With `latest`, a new release reaches the next build: pin `jdkMobileRelease` (and `graalvmMobileRelease`,
`graalvmRelease`) for builds that must not move. Runtimes of one's own are one directory per target (`android-aarch64`,
`android-x64`, `ios-simulator-aarch64`, `ios-device-aarch64`), each with `static/`, `include/` and `lib/` (java.home);
`scripts/stage-jdk-mobile.sh` lays out a local OpenJDK build that way.

[NucleusFramework/openjdk-mobile](https://github.com/NucleusFramework/openjdk-mobile) is a recipe, not a fork of the
JDK. It builds a stable OpenJDK (the 27 line, from [openjdk/jdk27u](https://github.com/openjdk/jdk27u)'s GA releases)
with two patches on top:

- `openjdk-mobile.patch`: [openjdk/mobile](https://github.com/openjdk/mobile)'s iOS and Android ports, carried over to
  the stable line, with the fixes they need there (Zero's W^X handling on iOS, bionic and an NDK build from a macOS host
  on Android);
- `inprocess.patch`: what NJA needs to run HotSpot inside an app's process on Android. The VM looks its natives up in
  the shared library holding it (loaded `RTLD_LOCAL` by ART) rather than in the process' own handle.

Its `build.sh` builds one target from an OpenJDK checkout: Zero (the interpreter) as static libraries for iOS, and the
server VM (C2 JIT) for Android, both as static libraries (which NJA links into `libnja.so`) and as a launcher for apps
that run Java in a process of its own. Its CI follows the JDK's line: every day, and on each change to the recipe, it
builds the latest GA for the four targets, smoke-tests the Android runtime on an emulator and publishes a release named
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

The result (`dist/jdk-mobile-<target>.tar.gz`) has the release's layout: extract it into `<stage>/<target>` for
`nucleusJvm.jdkMobile`. [`jdk-mobile/inprocess.patch`](jdk-mobile/inprocess.patch) is a copy of the recipe's.

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

**A JVM target** (`jvm()`, a desktop app on the JVM) sees the same proxies, with the façades in its own JVM: the plugin
puts them on the target's runtime class path, and the messages are still encoded (the app's code is the same
everywhere). Both the façades and their proxies being on that class path, the proxies need a package of their own: set
`njaFacade { clientPackage = "…" }` (the plugin checks it before the JVM target compiles).

## Tests

`examples/calculator` runs a suite of 2,008 tests (types, arrays, sealed values, callbacks, suspend, flows, inheritance,
load, concurrency, monkey and torture tests) against a calculator façade, from `commonTest`, on every platform. It also
runs NJA's own torture suite (`NjaTortureTest`): JVM null checks and stack overflows on 16 threads while the app faults
and collects, JIT deoptimization storms, 300-level JVM ↔ app nesting, callbacks invoked from the wrong JVM thread,
thread churn (1,000 threads, no JVM thread leak), 24–48 MB payloads, 100k handles released by close and by collection,
cancellation storms, 200k-element flows, a 20 s chaos soak on 24 threads.

```bash
./gradlew :examples:calculator:iosSimulatorArm64Test
```

```bash
./gradlew :examples:calculator:connectedAndroidDeviceTest
```

```bash
./gradlew :examples:calculator:macosArm64Test   # or linuxX64Test, linuxArm64Test, mingwX64Test, on that OS
```

```bash
./gradlew :examples:calculator:jvmTest
```

`-Pnja.runtime=JVM` runs the same suites on the JVM runtime.

## Performance

See [BENCHMARKS.md](BENCHMARKS.md). In short, on Android NJA's HotSpot runs Jackson and Commons Math 1.8–7.8× faster
than ART, and Lucene 10 (Java 21, out of ART's reach) at desktop speed; an empty call costs 0.33 µs. On iOS, Zero (an
interpreter) suits occasional calls, not CPU-bound work (an empty call costs 2.4 µs). A GraalVM native image (`GRAALVM`,
the default) runs at HotSpot's speed on both: on a real app's Lucene search, 8× faster than Zero on iOS, and on par with
Lucene patched for ART on Android, at about its memory.

## Layout

- `plugin-build/`: the Gradle plugins (`dev.nucleusframework.nja.facade`, `dev.nucleusframework.nja`): PSI analysis,
  member table, server and client generators, native images, Android linking/packaging, iOS linking/bundling, the
  desktop's runtime directory.
- `runtime/server`: runs in the JVM or the native image: dispatch, handles, async (suspend/Flow), the platforms' links.
- `runtime/client`: KMP: codec, call scopes and leases, proxies' base, async, the transports (Android, Kotlin/Native,
  the JVM).
- `runtime/android-native`: the ART ↔ HotSpot bridge and the ART ↔ native image bridge (C).
- `examples/`: `calculator` (the test suites), `torture-facade`, `benchmark*`.
