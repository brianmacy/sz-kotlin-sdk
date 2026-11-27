# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this is

`sz-kotlin-sdk` is an unofficial Kotlin SDK for Senzing V4, for the JVM and
Android. It follows the C# SDK's object model with Kotlin naming, and its shape
matches sz-swift-sdk. [docs/api-mapping.md](docs/api-mapping.md) is the C# to
Kotlin contract. The repository is public: no internal details, unreleased
Senzing features, or private build information.

## Architecture

Kotlin (`sdk/`) → Rust JNI crate (`native/`, the `jni` crate) →
[sz-rust-sdk](https://github.com/brianmacy/sz-rust-sdk) → libSz's public C API.
The SDK binds only public surfaces: never libSz's `Java_com_senzing_sdk_*`
entry points (the Java SDK's internal contract).

## Layout

| Path | Contents |
|---|---|
| `sdk/src/main/kotlin/io/github/brianmacy/sz/*.kt` | Public interfaces, `SzException`, `SzFlags` (generated), logging types, `SzAsyncEngine`, typed overloads (`SzTyped.kt`). |
| `sdk/.../sz/core` | `SzCore*` classes. |
| `sdk/.../sz/internal` | `Native` (the JNI methods), `EnvironmentState` (lifecycle lock), `EngineWorkerPool`, `SzJson`, `ConfigComment`. |
| `sdk/.../sz/models` | `@Serializable` response models. |
| `native/` | The JNI crate; `build.rs` generates the error table from `szerrors.json` with sz-rust-sdk-ffi's generator. |
| `test-support/` | Repository provisioning, truth set (shared by tests and examples). |
| `examples/` | Example programs (`./gradlew :examples:run<Name>`). |
| `tools/` | `GenerateFlags.kt`: `SzFlags.kt` from `szflags.json`. |
| `senzing-runtime.yaml`, `android-build.yaml` | Where the build finds the runtime; Android ABI and API level. |

## Commands

```bash
export SENZING_DIR=<runtime er directory>          # else senzing-runtime.yaml candidates
./gradlew :sdk:test                                # real engine; needs sqlite3
./gradlew :sdk:test :sdk:koverVerify               # 100% line coverage gate
./gradlew ktlintCheck                              # ktlintFormat to fix
./gradlew :tools:generateFlags                     # regenerate SzFlags.kt
./gradlew :sdk:cargoBuildAndroid                   # ANDROID_NDK_HOME; SENZING_ANDROID_DIR optional
./gradlew :sdk:dokkaGeneratePublicationHtml
(cd native && cargo fmt --check && cargo clippy --all-targets --all-features -- -D warnings && cargo test)
```

Use rustup's cargo (`/opt/homebrew/opt/rustup/bin` on this Mac), not
Homebrew's: mixing them in one target directory fails. The Gradle cargo tasks
take `$CARGO`, else the first `cargo` on the build's PATH.

## Rules

- **Every native call runs inside `EnvironmentState.execute`** (shared lock);
  `destroy()` takes it exclusively. A call from inside an SDK call (a log
  callback) throws instead of deadlocking; `destroy()` from there is deferred.
- **One environment per process**: `SzCoreEnvironment` enforces it, and the JNI
  crate keeps the one `SzEnvironmentCore`.
- **The JNI crate holds no SDK policy**: lifecycle, defaults, null-for-none and
  result shaping live in Kotlin. Errors cross as a `szerrors.json` class name,
  code and message (`Native.raise`).
- **`SzAsyncEngine` runs calls on its own worker threads**, never a coroutine
  dispatcher's shared pool: the engine keeps working state per OS thread.
  Every `SzEngine` method needs a `suspend` counterpart (a test checks).
- **`SzLogging` is process-wide**, outside any environment's lifetime. Sink
  changes inside a callback throw `SzConfigurationException`.
- **Adding a native method**: an `external fun` in `internal/Native.kt` and a
  `Java_io_github_brianmacy_sz_internal_Native_<name>` in `native/src`; the
  JNI symbols test checks both directions.
- **No mocks.** Tests use the real engine (see TEST_STANDARDS.md).
- **100% line coverage** of `sdk`; ktlint, rustfmt and clippy
  (`--all-targets --all-features -- -D warnings`) clean.
- **Generated files** (`SzFlags.kt`) change only through the generator; CI
  fails on drift.
- **Dependencies** from git are pinned by full commit SHA.
- **No AI attribution** in commits, PR bodies or files. Commits are signed.
- Docs: README, CHANGELOG (Keep a Changelog), KDoc and `docs/api-mapping.md`
  change with the API.
