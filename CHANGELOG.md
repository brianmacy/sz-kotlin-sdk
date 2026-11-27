# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- The Kotlin SDK at parity with sz-swift-sdk: `SzCoreEnvironment`, `SzEngine`,
  `SzConfigManager`, `SzConfig`, `SzDiagnostic`, `SzProduct` and `SzLogging`
  over the native engine, through a Rust JNI library (`native/`) that calls
  sz-rust-sdk.
- `SzCoreEnvironment(configId = ...)` / `Builder.configId(...)` start the engine
  and diagnostic with a registered configuration instead of the repository's
  default; `reinitialize` switches both.
- `SzFlags` and `SzFlagUsageGroup`, generated from `szflags.json`
  (`./gradlew :tools:generateFlags`).
- The `SzException` hierarchy of the C# SDK; native error codes are classified
  by a table the JNI library generates from `szerrors.json` with sz-rust-sdk-ffi.
- `SzAsyncEngine`: `suspend` functions on a fixed pool of worker threads;
  exports and the redo queue as `Flow`.
- `kotlinx.serialization` models and typed overloads of the common calls.
  Feature models carry both the integer feature ID (`libraryFeatureId`, ...)
  and the feature reference (`libraryFeatureRef`, ...), whichever the engine
  reports; a reference may be an integer or an opaque string.
- Android: `./gradlew :sdk:cargoBuildAndroid` builds the arm64-v8a JNI library
  with cargo-ndk (Android 13, API 33).
- Real-engine tests, examples, Dokka API docs and CI.
