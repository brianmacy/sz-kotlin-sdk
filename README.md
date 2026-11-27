# sz-kotlin-sdk

[![CI](https://github.com/brianmacy/sz-kotlin-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/brianmacy/sz-kotlin-sdk/actions/workflows/ci.yml)

An idiomatic Kotlin SDK for [Senzing](https://senzing.com) V4 entity resolution,
for the JVM (macOS and Linux) and Android. It follows the object model of the
official C# SDK (`SzEnvironment` → `SzEngine`, `SzConfigManager`, `SzConfig`,
`SzDiagnostic`, `SzProduct`), like its siblings
[sz-swift-sdk](https://github.com/brianmacy/sz-swift-sdk),
[sz-cpp-sdk](https://github.com/brianmacy/sz-cpp-sdk) and
[sz-rust-sdk](https://github.com/brianmacy/sz-rust-sdk), with Kotlin types:
default parameters, an exception hierarchy, coroutines and `Flow` in
`SzAsyncEngine`, and `kotlinx.serialization` results.

All entity resolution is done by the native Senzing engine (`libSz`). This SDK
binds only its public C API: Kotlin calls a small Rust JNI library
(`native/`), which calls [sz-rust-sdk](https://github.com/brianmacy/sz-rust-sdk).

> This is an unofficial SDK; it is not supported by Senzing, Inc. Licensed
> under Apache-2.0. It requires a separate Senzing installation, which has its
> own license terms.

## Requirements

| | |
|---|---|
| JDK | 17 or later |
| Kotlin | 2.4 |
| Senzing | 4.5 or later, installed separately |
| macOS | Apple silicon, the Senzing Homebrew cask |
| Linux | x86-64 or arm64 with the Senzing apt packages (CI: Ubuntu 24.04 arm64) |
| Android | 13 (API 33) or later, arm64-v8a, with an Android `libSz.so` you provide: see [Android](#android) |
| Build | Rust (stable, via rustup) to build the JNI library; `sqlite3` for the tests |

## Install Senzing

**macOS** (Homebrew):

```bash
brew tap senzing/senzingsdk https://github.com/Senzing/homebrew-senzingsdk
brew trust --tap senzing/senzingsdk
brew install --cask senzingsdk
```

**Linux** (Debian/Ubuntu, Senzing's apt repository):

```bash
wget https://senzing-production-apt.s3.amazonaws.com/senzingrepo_2.0.2-1_all.deb
sudo apt-get install -y ./senzingrepo_2.0.2-1_all.deb
sudo apt-get update
sudo apt-get install -y senzingsdk-runtime senzingsdk-setup sqlite3
```

Installing Senzing requires accepting the
[Senzing EULA](https://senzing.com/end-user-license-agreement); see each
installer for the non-interactive acceptance setting.

## Build

The SDK is not published to a package repository: build it from source.
The build finds the runtime through `$SENZING_DIR` (the runtime's `er`
directory), then the locations in [senzing-runtime.yaml](senzing-runtime.yaml)
(the Homebrew cask, then `/opt/senzing/er`).

```bash
./gradlew :sdk:build      # JNI library (cargo) + SDK jar + tests
./gradlew :sdk:jar        # the SDK jar only
```

The JNI library is `native/target/debug/libsz_kotlin_jni.{dylib,so}`
(`cargo build --release` in `native/` for a release build). At run time:

- **The JNI library goes beside `libSz`**, in the runtime's `lib` directory, or
  anywhere on `java.library.path`. It looks for `libSz` in its own directory
  first (rpath `@loader_path` / `$ORIGIN`).
- **`libSz` must be found by the loader**: `LD_LIBRARY_PATH=/opt/senzing/er/lib`
  on Linux (as for every Senzing SDK), or `DYLD_LIBRARY_PATH` on macOS when the
  JNI library is not in the runtime's `lib` directory.

```bash
java -Djava.library.path=/opt/senzing/er/lib -cp app.jar:sz-kotlin-sdk.jar MainKt
```

## Quick start

```kotlin
import io.github.brianmacy.sz.SzNotFoundException
import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.getEntity
import io.github.brianmacy.sz.models.SzEntityResponse

fun main() {
    // The engine settings come from the environment, not the code.
    val settings = System.getenv("SENZING_ENGINE_CONFIGURATION_JSON").orEmpty()

    SzCoreEnvironment(instanceName = "quick-start", settings = settings).use { environment ->
        val engine = environment.getEngine()
        engine.addRecord("CUSTOMERS", "1001", """{"NAME_FULL":"Robert Smith","DATE_OF_BIRTH":"1978-12-11"}""")
        engine.addRecord("CUSTOMERS", "1002", """{"NAME_FULL":"Bob Smith","DATE_OF_BIRTH":"1978-11-12"}""")

        try {
            val entity = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
            val resolved = entity.resolvedEntity
            println("${resolved.entityName.orEmpty()} ${resolved.records.orEmpty().map { it.recordId }}")
        } catch (e: SzNotFoundException) {
            println("no such record")
        }
    }
}
```

`SENZING_ENGINE_CONFIGURATION_JSON` holds the engine settings. On Linux:

```json
{"PIPELINE":{"CONFIGPATH":"/etc/opt/senzing","RESOURCEPATH":"/opt/senzing/er/resources",
 "SUPPORTPATH":"/opt/senzing/data"},"SQL":{"CONNECTION":"sqlite3://na:na@/tmp/G2C.db"}}
```

On macOS with the Homebrew cask, the same paths are under
`$(brew --prefix)/opt/senzing`: `er/etc` (`CONFIGPATH`), `er/resources` and
`data`.

The repository needs its schema and a default configuration that registers
`CUSTOMERS`; the examples and the tests create a fresh SQLite repository that
way (`test-support/`).

## The API

| Type | Role |
|---|---|
| `SzEnvironment` / `SzCoreEnvironment` | Entry point and lifecycle; lazily creates each subsystem. One per process. `AutoCloseable`. |
| `SzEngine` | Records, entities, search, why/how, paths, networks, export, redo. |
| `SzConfigManager`, `SzConfig` | Register data sources; manage and set the default configuration. |
| `SzDiagnostic`, `SzProduct` | Repository diagnostics; version and license. |
| `SzAsyncEngine` | Every engine method as a `suspend` function on a fixed pool of worker threads; exports and the redo queue as `Flow`. |
| `SzLogging` | Native log callbacks, default output, format and level (`environment.getLogging()`). |
| `SzFlags` | A value class over every flag in `szflags.json`; each method defaults to its `..._DEFAULT_FLAGS` (for getting started: production code should pass the flags it needs). |
| `SzException` and subclasses | The C# exception hierarchy; native error codes are classified by `szerrors.json`. |

Names follow the C# SDK in Kotlin form: `AddRecord` → `addRecord`,
`SzEntityDefaultFlags` → `SzFlags.ENTITY_DEFAULT_FLAGS`,
`SzNotFoundException` → `SzNotFoundException`.
[docs/api-mapping.md](docs/api-mapping.md) has the full mapping.

**Raw JSON or typed.** Every engine method returns the engine's JSON. The
common calls also take a `DeserializationStrategy` to decode into the models in
`io.github.brianmacy.sz.models` (`SzEntityResponse`, `SzSearchResponse`,
`SzWhyResponse`, `SzWithInfoResponse`, ...) or any `@Serializable` type of your
own, and `addRecord` accepts any serializable record whose property names are
Senzing attribute names.

**Errors.** Catch a class to catch its subclasses: `SzBadInputException` also
catches `SzNotFoundException` and `SzUnknownDataSourceException`. Every
`SzException` has its native `errorCode` and `isRetryable`. Use after
`destroy()` throws `SzEnvironmentDestroyedException`, and typed decoding
failures throw `SzJsonException`; neither is an `SzException`, as in C#.

**Which API should I use?** The Senzing engine is synchronous and thread-safe:
each call blocks its thread, and calls from many threads run in parallel.

- CLI tools, servers, threads you own: the synchronous `SzEngine`. Call it from
  long-lived threads you reuse; the engine builds working state per thread.
- Coroutines and Android UI code: `SzAsyncEngine`. Never call the synchronous
  API on the main thread.

`SzAsyncEngine` is a Kotlin convenience with no counterpart in the official
Senzing SDKs. Suspending does not make calls faster or non-blocking: it runs
them on a fixed pool of long-lived worker threads (`maxConcurrentCalls`,
default: processor count capped at 4) so the blocking work stays off the main
thread and the shared dispatchers. A coroutine cancelled before its call
starts never reaches the engine; a started call runs to completion. More
concurrency does not raise throughput on SQLite (one writer at a time).

```kotlin
val engine = SzAsyncEngine(environment.getEngine())
engine.addRecord("CONTACTS", contact.id, contact, Contact.serializer())
val results = engine.searchByAttributes(query, SzSearchResponse.serializer())
engine.exportJsonEntityReport().collect { line -> println(line) }
```

**Logging.** `environment.getLogging()` receives the engine's native log
messages in a callback or redirects its default output. Register before the
first `getEngine()` and unregister after `destroy()`:

```kotlin
val logging = environment.getLogging()
logging.registerCallback(SzLogLevel.WARN) { record -> println("[${record.level}] ${record.message}") }.use {
    logging.setDefaultOutput(SzLogOutput.Null)
    // ...
}
```

## Examples

| Task | Shows |
|---|---|
| `runLoadTruthset` | Register data sources, load the Senzing truth set, summarize the entities. |
| `runSearch` | Search by attributes with a serializable query; decoded results. |
| `runWhy` | Why two records resolved; why two entities are related. |
| `runAsyncEngine` | Concurrent loading with `SzAsyncEngine`; an export as a `Flow`. |
| `runLogging` | Capture native log messages with an `SzLogging` callback. |

```bash
./gradlew :examples:runLoadTruthset
```

Each creates a throwaway SQLite repository unless
`SENZING_ENGINE_CONFIGURATION_JSON` is set.

## Build and test

```bash
./gradlew :sdk:test                      # real engine; needs sqlite3
./gradlew :sdk:test :sdk:koverVerify     # with the 100% line coverage gate
./gradlew ktlintCheck
./gradlew :sdk:dokkaGeneratePublicationHtml   # API docs in sdk/build/dokka/html
(cd native && cargo clippy --all-targets --all-features -- -D warnings && cargo test)
```

The tests run against the real engine, with no mocks: each test creates its own
SQLite repository from the schema shipped with the runtime (with the `sqlite3`
tool) and deletes it afterwards. Test classes run one at a time because the
native engine is one per process. See [TEST_STANDARDS.md](TEST_STANDARDS.md).

`SzFlags` is generated from the runtime's `szflags.json`; the JNI library's
error table is generated from `szerrors.json` at build time, with the generator
sz-rust-sdk uses (`sz-rust-sdk-ffi`):

```bash
./gradlew :tools:generateFlags
```

CI regenerates `SzFlags` against the installed runtime and fails on any drift.
CI runs lint, the full Linux suite (Rust and Kotlin tests, 100% coverage,
examples, generated-code drift, Dokka) on Linux ARM runners, and builds the
Android JNI library.

## Android

The Kotlin code is plain JVM Kotlin and runs on Android as is; the app ships
`libsz_kotlin_jni.so` and an Android `libSz.so` (arm64-v8a) in its native
libraries. Build the JNI library with [cargo-ndk](https://github.com/bbqsrc/cargo-ndk):

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
export ANDROID_NDK_HOME=/path/to/ndk
SENZING_ANDROID_DIR=/path/to/android-senzing ./gradlew :sdk:cargoBuildAndroid
```

The output is `native/target/aarch64-linux-android/release/libsz_kotlin_jni.so`.
`SENZING_ANDROID_DIR` is an Android Senzing runtime with `lib/libSz.so`. Without
it, the build links against a stub generated from the C headers
(`native/tools/android-link-stub.sh`), so the library builds before an Android
`libSz` is at hand; at run time it loads the app's real `libSz.so`. Settings
for the API level and ABI are in [android-build.yaml](android-build.yaml).
Packaging (an AAR with the native libraries) is not set up yet.

## License

Apache-2.0; see [LICENSE](LICENSE). The bundled truth-set test data comes from
[Senzing/truth-sets](https://github.com/Senzing/truth-sets) under Apache-2.0.
Senzing itself is installed separately under its own license terms.
