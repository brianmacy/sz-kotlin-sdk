# C# SDK to Kotlin mapping

The Kotlin SDK follows the object model of the official C# SDK (as do
[sz-swift-sdk](https://github.com/brianmacy/sz-swift-sdk),
[sz-cpp-sdk](https://github.com/brianmacy/sz-cpp-sdk) and
[sz-rust-sdk](https://github.com/brianmacy/sz-rust-sdk)). Type names are the
same; members use Kotlin naming. Its shape matches sz-swift-sdk.

## Rules

| C# | Kotlin |
|---|---|
| Interface `SzEngine`, class `SzCoreEngine` | Interface `SzEngine`, class `SzCoreEngine` in `io.github.brianmacy.sz.core` (same for every subsystem) |
| Method `GetEntity` | Method `getEntity` (`getEntity(dataSourceCode, recordId, flags)`) |
| `string` (JSON) | `String`; the common calls also take a `DeserializationStrategy` to decode into a `@Serializable` type |
| `long` IDs | `Long` (`entityId`, `configId`: `Id`, not `ID`) |
| `int` degrees, counts, seconds | `Int` |
| `string` that is `null` without `SzWithInfo` | `String?` (null without `SzFlags.WITH_INFO`) |
| `ISet<long>`, `ISet<string>` | `Set<Long>`, `Set<String>` (empty = none) |
| `ISet<(string, string)>` record keys | `Set<SzRecordKey>` |
| `IntPtr` export handle | `SzExportHandle` (value class) |
| `FetchNext` returning `null` at the end | `fetchNext` returning null |
| `SzFlag?` with a per-method default | `flags: SzFlags = SzFlags.<METHOD>_DEFAULT_FLAGS` (Kotlin default parameters) |
| `SzFlag.SzEntityIncludeEntityName` | `SzFlags.ENTITY_INCLUDE_ENTITY_NAME` (the `SZ_` symbol without its prefix); combine with `or` |
| `SzException` and subclasses | `SzException` and subclasses (same tree, same names) |
| `SzEnvironmentDestroyedException` | `SzEnvironmentDestroyedException`, an `IllegalStateException` (outside the engine tree, as in C#) |
| `SzCoreEnvironment.NewBuilder()...Build()` | `SzCoreEnvironment.newBuilder()...build()`, or `SzCoreEnvironment(instanceName, settings, verboseLogging, configId)` |
| `IDisposable` / `Destroy()` | `AutoCloseable`: `use { }` and `close()` destroy |

## SzEnvironment

| C# | Kotlin |
|---|---|
| `GetProduct()` | `getProduct()` |
| `GetEngine()` | `getEngine()` |
| `GetConfigManager()` | `getConfigManager()` |
| `GetDiagnostic()` | `getDiagnostic()` |
| `GetActiveConfigID()` | `getActiveConfigId()` |
| `Reinitialize(configID)` | `reinitialize(configId)` |
| `Destroy()` | `destroy()`, `close()` |
| `IsDestroyed()` | `isDestroyed` |
| `SzCoreEnvironment.GetActiveInstance()` | `SzCoreEnvironment.getActiveInstance()` |
| `GetLogging()` (C++/Rust SDKs) | `getLogging()` |

The builder's `configId` starts the engine and the diagnostic with that
registered configuration (`Sz_initWithConfigID`, `SzDiagnostic_initWithConfigID`),
as in the C# SDK. `reinitialize` starts the engine first if needed, then switches
the engine and, when started, the diagnostic.

## SzEngine

| C# | Kotlin |
|---|---|
| `PrimeEngine()` | `primeEngine()` |
| `GetStats()` | `getStats()` |
| `AddRecord(ds, id, def, flags)` | `addRecord(dataSourceCode, recordId, recordDefinition, flags)`; also a serializable `record` + `serializer`, and a `deserializer` for the info |
| `GetRecordPreview(def, flags)` | `getRecordPreview(recordDefinition, flags)` |
| `DeleteRecord(ds, id, flags)` | `deleteRecord(dataSourceCode, recordId, flags)`; also a `deserializer` |
| `ReevaluateRecord(ds, id, flags)` | `reevaluateRecord(dataSourceCode, recordId, flags)` |
| `ReevaluateEntity(id, flags)` | `reevaluateEntity(entityId, flags)` |
| `SearchByAttributes(attrs, profile, flags)` | `searchByAttributes(attributes, searchProfile, flags)`; also a `deserializer` |
| `WhySearch(attrs, id, profile, flags)` | `whySearch(attributes, entityId, searchProfile, flags)` |
| `GetEntity(id, flags)` | `getEntity(entityId, flags)`; also a `deserializer` |
| `GetEntity(ds, id, flags)` | `getEntity(dataSourceCode, recordId, flags)`; also a `deserializer` |
| `GetRecord(ds, id, flags)` | `getRecord(dataSourceCode, recordId, flags)` |
| `FindInterestingEntities(id, flags)` | `findInterestingEntities(entityId, flags)` |
| `FindInterestingEntities(ds, id, flags)` | `findInterestingEntities(dataSourceCode, recordId, flags)` |
| `FindPath(start, end, degrees, avoid, required, flags)` | `findPath(startEntityId, endEntityId, maxDegrees, avoidEntityIds, requiredDataSources, flags)` |
| `FindPath(ds1, id1, ds2, id2, degrees, avoid, required, flags)` | `findPath(startDataSourceCode, startRecordId, endDataSourceCode, endRecordId, maxDegrees, avoidRecordKeys, requiredDataSources, flags)` |
| `FindNetwork(ids, degrees, buildOut, maxEntities, flags)` | `findNetwork(entityIds, maxDegrees, buildOutDegrees, buildOutMaxEntities, flags)` |
| `FindNetwork(keys, degrees, buildOut, maxEntities, flags)` | `findNetworkByRecordKeys(recordKeys, ...)`: a distinct name, as `Set<Long>` and `Set<SzRecordKey>` erase to the same JVM type |
| `WhyRecordInEntity(ds, id, flags)` | `whyRecordInEntity(dataSourceCode, recordId, flags)`; also a `deserializer` |
| `WhyRecords(ds1, id1, ds2, id2, flags)` | `whyRecords(dataSourceCode1, recordId1, dataSourceCode2, recordId2, flags)`; also a `deserializer` |
| `WhyEntities(id1, id2, flags)` | `whyEntities(entityId1, entityId2, flags)`; also a `deserializer` |
| `HowEntity(id, flags)` | `howEntity(entityId, flags)` |
| `GetVirtualEntity(keys, flags)` | `getVirtualEntity(recordKeys, flags)` |
| `ExportJsonEntityReport(flags)` | `exportJsonEntityReport(flags)`; also `exportJsonEntityLines(flags)` and `exportJsonEntityReport(deserializer, flags)` |
| `ExportCsvEntityReport(columns, flags)` | `exportCsvEntityReport(csvColumnList, flags)` |
| `FetchNext(handle)` | `fetchNext(exportHandle)` |
| `CloseExportReport(handle)` | `closeExportReport(exportHandle)` |
| `ProcessRedoRecord(redo, flags)` | `processRedoRecord(redoRecord, flags)` |
| `GetRedoRecord()` | `getRedoRecord()` (null when none is pending) |
| `CountRedoRecords()` | `countRedoRecords()` |

## SzConfigManager and SzConfig

| C# | Kotlin |
|---|---|
| `CreateConfig()` | `createConfig()` |
| `CreateConfig(definition)` | `createConfig(configDefinition)` |
| `CreateConfig(configID)` | `createConfig(configId)` |
| `RegisterConfig(definition, comment)` | `registerConfig(configDefinition, configComment)` |
| `RegisterConfig(definition)` | `registerConfig(configDefinition)` (comment generated as in C#) |
| `GetConfigRegistry()` | `getConfigRegistry()`; also a `deserializer` |
| `GetDefaultConfigID()` | `getDefaultConfigId()` |
| `ReplaceDefaultConfigID(current, new)` | `replaceDefaultConfigId(currentDefaultConfigId, newDefaultConfigId)` |
| `SetDefaultConfigID(configID)` | `setDefaultConfigId(configId)` |
| `SetDefaultConfig(definition, comment)` | `setDefaultConfig(configDefinition, configComment)` |
| `SetDefaultConfig(definition)` | `setDefaultConfig(configDefinition)` |
| `SzConfig.Export()` | `export()` |
| `SzConfig.GetDataSourceRegistry()` | `getDataSourceRegistry()`; also a `deserializer` |
| `SzConfig.RegisterDataSource(code)` | `registerDataSource(dataSourceCode)` |
| `SzConfig.UnregisterDataSource(code)` | `unregisterDataSource(dataSourceCode)` |

## SzDiagnostic and SzProduct

| C# | Kotlin |
|---|---|
| `GetRepositoryInfo()` | `getRepositoryInfo()` |
| `CheckRepositoryPerformance(seconds)` | `checkRepositoryPerformance(secondsToRun)` |
| `PurgeRepository()` | `purgeRepository()` |
| `GetFeature(featureID)` | `getFeature(featureId)` |
| `GetLicense()` | `getLicense()`; also a `deserializer` |
| `GetVersion()` | `getVersion()`; also a `deserializer` |

## SzLogging

The C# SDK has no logging API. `SzLogging` follows the Senzing C++ and Rust
SDKs over the 4.5 `libSzLogging.h` C API (through sz-rust-sdk).

| C++ SDK | Kotlin |
|---|---|
| `SzCoreLogging::GetInstance()` | `SzCoreLogging` (an `object`) |
| `RegisterCallback(options, callback)` | `registerCallback(options, callback)`, `registerCallback(minLevel, callback)` |
| `SetDefaultCallback(options, callback)` | `setDefaultCallback(options, callback)` |
| `SetDefaultOutput(output)` | `setDefaultOutput(output)` |
| `SetDefaultFormat(format)` | `setDefaultFormat(format)` |
| `SetDefaultLogLevel(level)` | `setDefaultLogLevel(level)` |
| `GetEffectiveLogLevel()` | `getEffectiveLogLevel()` |
| `SzLogRegistration::Unregister()` / `Leak()` | `unregister()` / `leak()`; `close()` unregisters |
| `SzLogLevel`, `SzLogFormat`, `SzLogOutputType` | enums of the same names; `parse(text)` |
| `SzLogOutput::Stderr()` ... `File(path)` | `SzLogOutput.Stderr` ... `SzLogOutput.File(path)` |
| `SzLogCallbackOptions(minLevel).WithFormat(f)` | `SzLogCallbackOptions(minLevel, format)` |
| `SzLogRecord` | `SzLogRecord` |

Not wrapped: thread-local callbacks (the 4.5 runtime accepts the registration
but delivers no messages).

## Kotlin additions

- `SzAsyncEngine`: a `suspend` counterpart of every `SzEngine` method, plus
  `perform`, `processRedoRecords()` (an `SzRedoReport`), `redoRecords()` and the
  exports as `Flow<String>`, run on a fixed pool of long-lived worker threads.
  No official Senzing SDK has it.
- `SzFlagUsageGroup`: the flag usage groups from `szflags.json`, with
  `allFlags` for each.
- `SzRecordKey`: the record-key value type used in sets.
- `kotlinx.serialization` models of the common responses
  (`io.github.brianmacy.sz.models`).
