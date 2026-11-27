// Typed (kotlinx.serialization) variants of the common SDK calls.
//
// Each function makes the same native call as its raw counterpart and decodes the JSON with the
// given deserializer: a model in io.github.brianmacy.sz.models (for example
// SzEntityResponse.serializer()) or your own @Serializable type. Decoding failures throw
// SzJsonException.

package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.SzJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

/**
 * Adds or replaces a record given as any serializable value, whose property names must be
 * Senzing attribute names (for example `NAME_FULL`, `EMAIL_ADDRESS`).
 */
public fun <R> SzEngine.addRecord(
    dataSourceCode: String,
    recordId: String,
    record: R,
    serializer: SerializationStrategy<R>,
    flags: SzFlags = SzFlags.ADD_RECORD_DEFAULT_FLAGS,
): String? = addRecord(dataSourceCode, recordId, SzJson.encode(serializer, record), flags)

/** Adds or replaces a record and returns the decoded info ([SzFlags.WITH_INFO] is added to [flags]). */
public fun <T> SzEngine.addRecord(
    dataSourceCode: String,
    recordId: String,
    recordDefinition: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.ADD_RECORD_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, addRecord(dataSourceCode, recordId, recordDefinition, flags or SzFlags.WITH_INFO))

/** Deletes a record and returns the decoded info ([SzFlags.WITH_INFO] is added to [flags]). */
public fun <T> SzEngine.deleteRecord(
    dataSourceCode: String,
    recordId: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.DELETE_RECORD_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, deleteRecord(dataSourceCode, recordId, flags or SzFlags.WITH_INFO))

/** Returns an entity by its entity ID, decoded. */
public fun <T> SzEngine.getEntity(
    entityId: Long,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, getEntity(entityId, flags))

/** Returns the entity that contains a record, decoded. */
public fun <T> SzEngine.getEntity(
    dataSourceCode: String,
    recordId: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, getEntity(dataSourceCode, recordId, flags))

/** Searches by attributes and returns the decoded results. */
public fun <T> SzEngine.searchByAttributes(
    attributes: String,
    deserializer: DeserializationStrategy<T>,
    searchProfile: String? = null,
    flags: SzFlags = SzFlags.SEARCH_BY_ATTRIBUTES_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, searchByAttributes(attributes, searchProfile, flags))

/** Explains why two entities did not resolve, decoded. */
public fun <T> SzEngine.whyEntities(
    entityId1: Long,
    entityId2: Long,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.WHY_ENTITIES_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, whyEntities(entityId1, entityId2, flags))

/** Explains why two records did, or did not, resolve, decoded. */
public fun <T> SzEngine.whyRecords(
    dataSourceCode1: String,
    recordId1: String,
    dataSourceCode2: String,
    recordId2: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.WHY_RECORDS_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, whyRecords(dataSourceCode1, recordId1, dataSourceCode2, recordId2, flags))

/** Explains why a record belongs to its entity, decoded. */
public fun <T> SzEngine.whyRecordInEntity(
    dataSourceCode: String,
    recordId: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.WHY_RECORD_IN_ENTITY_DEFAULT_FLAGS,
): T = SzJson.decode(deserializer, whyRecordInEntity(dataSourceCode, recordId, flags))

/**
 * Exports every entity and returns the raw JSON lines.
 *
 * Reads the whole report into memory and always closes the export handle. For very large
 * repositories, iterate with [SzEngine.exportJsonEntityReport] and [SzEngine.fetchNext], or
 * collect [SzAsyncEngine.exportJsonEntityReport].
 */
public fun SzEngine.exportJsonEntityLines(flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS): List<String> {
    val handle = exportJsonEntityReport(flags)
    val lines = mutableListOf<String>()
    var failure: Throwable? = null
    try {
        while (true) lines += fetchNext(handle) ?: break
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        // Always close the handle; a read error takes precedence over a close error.
        try {
            closeExportReport(handle)
        } catch (e: Throwable) {
            failure?.addSuppressed(e) ?: throw e
        }
    }
    return lines
}

/** Exports every entity, decoding each line (typically into `SzEntityResponse`). */
public fun <T> SzEngine.exportJsonEntityReport(
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS,
): List<T> = exportJsonEntityLines(flags).map { SzJson.decode(deserializer, it) }

/** Returns the registered configurations, decoded (typically into `SzConfigRegistry`). */
public fun <T> SzConfigManager.getConfigRegistry(deserializer: DeserializationStrategy<T>): T =
    SzJson.decode(deserializer, getConfigRegistry())

/** Returns the registered data sources, decoded (typically into `SzDataSourceRegistry`). */
public fun <T> SzConfig.getDataSourceRegistry(deserializer: DeserializationStrategy<T>): T =
    SzJson.decode(deserializer, getDataSourceRegistry())

/** Returns the version details, decoded (typically into `SzVersionInfo`). */
public fun <T> SzProduct.getVersion(deserializer: DeserializationStrategy<T>): T = SzJson.decode(deserializer, getVersion())

/** Returns the license details, decoded (typically into `SzLicenseInfo`). */
public fun <T> SzProduct.getLicense(deserializer: DeserializationStrategy<T>): T = SzJson.decode(deserializer, getLicense())

/** Async variants of the typed calls. */
public suspend fun <T> SzAsyncEngine.getEntity(
    entityId: Long,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
): T = perform { it.getEntity(entityId, deserializer, flags) }

/** Returns the entity that contains a record, decoded, on a worker thread. */
public suspend fun <T> SzAsyncEngine.getEntity(
    dataSourceCode: String,
    recordId: String,
    deserializer: DeserializationStrategy<T>,
    flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
): T = perform { it.getEntity(dataSourceCode, recordId, deserializer, flags) }

/** Searches by attributes and returns the decoded results, on a worker thread. */
public suspend fun <T> SzAsyncEngine.searchByAttributes(
    attributes: String,
    deserializer: DeserializationStrategy<T>,
    searchProfile: String? = null,
    flags: SzFlags = SzFlags.SEARCH_BY_ATTRIBUTES_DEFAULT_FLAGS,
): T = perform { it.searchByAttributes(attributes, deserializer, searchProfile, flags) }
