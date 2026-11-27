# Test standards

## Real engine only

There are no mocks, fakes or stubs of the native engine. Every engine test runs
against the installed Senzing runtime over a real repository (SQLite). The one stub in the repository is the
Android link-time `libSz.so` (`native/tools/android-link-stub.sh`), which only
lets the Android JNI library link and is never run.

## Isolation

- Each test creates its own `TestRepository` (`test-support/`): a new temporary
  directory, a SQLite database built from the runtime's
  `szcore-schema-sqlite-create.sql`, and a default configuration registered
  through this SDK. `EngineFixture` wraps it with an environment; `close()`
  deletes everything.
- The native engine is one per process: test classes run one at a time in one
  JVM, and each test closes its environment before the next starts.
- Test data is the Senzing demo truth set in
  `test-support/src/main/resources/truthset`, plus small inline records.

## Naming and failure rules

- `` `test_...` ``: tests that fail on any error. A test never prints a failure
  and passes: assert, or let the exception propagate.
- Examples (`examples/`, `./gradlew :examples:run<Name>`) must succeed
  completely; they exit non-zero when anything fails or the result is wrong.
- Expected engine errors are asserted with `assertFailsWith<SzXxxException>`,
  which fails if nothing is thrown or the class is wrong.
- Expectations about engine behavior come from observed engine output, not from
  assumptions; when the engine's behavior is surprising, the test says so.

## Verification pattern for changes that persist

For anything that registers or creates state:

1. The operation succeeds.
2. The result is readable immediately.
3. It survives persistence (for example, `createConfig(configId)` after
   `setDefaultConfig`).
4. The engine can use it (for example, add a record to a new data source).

## Coverage

100% line coverage of the `sdk` module (Kover, `./gradlew :sdk:koverVerify`),
and `cargo test` for the JNI crate's own logic.
