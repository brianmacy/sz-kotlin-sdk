package io.github.brianmacy.sz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An opaque handle to an export report from [SzEngine.exportJsonEntityReport] or
 * [SzEngine.exportCsvEntityReport]. Read it with [SzEngine.fetchNext] and always release it with
 * [SzEngine.closeExportReport].
 *
 * @property value The native handle value.
 */
@JvmInline
public value class SzExportHandle(
    public val value: Long,
)

/**
 * Identifies a record by its data source code and record ID (C# `SzRecordKey`).
 *
 * @property dataSourceCode The data source code, for example `"CUSTOMERS"`.
 * @property recordId The record ID within the data source.
 */
@Serializable
public data class SzRecordKey(
    @SerialName("DATA_SOURCE") public val dataSourceCode: String,
    @SerialName("RECORD_ID") public val recordId: String,
) : Comparable<SzRecordKey> {
    override fun compareTo(other: SzRecordKey): Int = compareValuesBy(this, other, { it.dataSourceCode }, { it.recordId })

    override fun toString(): String = "$dataSourceCode:$recordId"

    public companion object {
        /** Creates a record key, mirroring C# `SzRecordKey.Of`. */
        public fun of(
            dataSourceCode: String,
            recordId: String,
        ): SzRecordKey = SzRecordKey(dataSourceCode, recordId)
    }
}

/**
 * Entity resolution: add and delete records, get entities, search, find paths and networks,
 * and explain results. Mirrors the C# `SzEngine` interface.
 *
 * Every method takes a `flags` argument that defaults to the method's `..._DEFAULT_FLAGS`
 * constant, as the C# SDK's overloads do. Results are the engine's JSON documents; the
 * extension functions in `SzEngineTyped.kt` decode them into `@Serializable` types such as
 * [io.github.brianmacy.sz.models.SzEntityResponse].
 *
 * Calls block the calling thread while the native engine works. The engine is thread-safe, so
 * calls may run concurrently from many threads; from coroutines, use [SzAsyncEngine].
 */
public interface SzEngine {
    /** Loads the engine's internal caches ahead of the first request. */
    public fun primeEngine()

    /** Returns workload statistics since the last call, as JSON. */
    public fun getStats(): String

    /**
     * Adds or replaces a record. Returns the info JSON when [flags] contains
     * [SzFlags.WITH_INFO], otherwise null.
     */
    public fun addRecord(
        dataSourceCode: String,
        recordId: String,
        recordDefinition: String,
        flags: SzFlags = SzFlags.ADD_RECORD_DEFAULT_FLAGS,
    ): String?

    /** Returns the features the engine would extract from a record definition, as JSON. */
    public fun getRecordPreview(
        recordDefinition: String,
        flags: SzFlags = SzFlags.RECORD_PREVIEW_DEFAULT_FLAGS,
    ): String

    /**
     * Deletes a record. Returns the info JSON when [flags] contains [SzFlags.WITH_INFO],
     * otherwise null.
     */
    public fun deleteRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.DELETE_RECORD_DEFAULT_FLAGS,
    ): String?

    /**
     * Re-resolves a record. Returns the info JSON when [flags] contains [SzFlags.WITH_INFO],
     * otherwise null.
     */
    public fun reevaluateRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.REEVALUATE_RECORD_DEFAULT_FLAGS,
    ): String?

    /**
     * Re-resolves an entity. Returns the info JSON when [flags] contains [SzFlags.WITH_INFO],
     * otherwise null.
     */
    public fun reevaluateEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.REEVALUATE_ENTITY_DEFAULT_FLAGS,
    ): String?

    /**
     * Searches for entities that match the attributes of a record definition. A null (or
     * empty) search profile uses the default profile.
     */
    public fun searchByAttributes(
        attributes: String,
        searchProfile: String? = null,
        flags: SzFlags = SzFlags.SEARCH_BY_ATTRIBUTES_DEFAULT_FLAGS,
    ): String

    /** Explains why an entity is, or is not, a search result. */
    public fun whySearch(
        attributes: String,
        entityId: Long,
        searchProfile: String? = null,
        flags: SzFlags = SzFlags.WHY_SEARCH_DEFAULT_FLAGS,
    ): String

    /** Returns an entity by its entity ID, as JSON. */
    public fun getEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
    ): String

    /** Returns the entity that contains a record, as JSON. */
    public fun getEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
    ): String

    /** Returns a record, as JSON. */
    public fun getRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.RECORD_DEFAULT_FLAGS,
    ): String

    /** Returns interesting entities related to an entity, as JSON. */
    public fun findInterestingEntities(
        entityId: Long,
        flags: SzFlags = SzFlags.FIND_INTERESTING_ENTITIES_DEFAULT_FLAGS,
    ): String

    /** Returns interesting entities related to the entity of a record, as JSON. */
    public fun findInterestingEntities(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.FIND_INTERESTING_ENTITIES_DEFAULT_FLAGS,
    ): String

    /** Finds a relationship path between two entities. */
    public fun findPath(
        startEntityId: Long,
        endEntityId: Long,
        maxDegrees: Int,
        avoidEntityIds: Set<Long> = emptySet(),
        requiredDataSources: Set<String> = emptySet(),
        flags: SzFlags = SzFlags.FIND_PATH_DEFAULT_FLAGS,
    ): String

    /** Finds a relationship path between the entities of two records. */
    public fun findPath(
        startDataSourceCode: String,
        startRecordId: String,
        endDataSourceCode: String,
        endRecordId: String,
        maxDegrees: Int,
        avoidRecordKeys: Set<SzRecordKey> = emptySet(),
        requiredDataSources: Set<String> = emptySet(),
        flags: SzFlags = SzFlags.FIND_PATH_DEFAULT_FLAGS,
    ): String

    /** Finds the network of relationships among entities. */
    public fun findNetwork(
        entityIds: Set<Long>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags = SzFlags.FIND_NETWORK_DEFAULT_FLAGS,
    ): String

    /** Finds the network of relationships among the entities of records. */
    public fun findNetworkByRecordKeys(
        recordKeys: Set<SzRecordKey>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags = SzFlags.FIND_NETWORK_DEFAULT_FLAGS,
    ): String

    /** Explains why a record belongs to its entity. */
    public fun whyRecordInEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.WHY_RECORD_IN_ENTITY_DEFAULT_FLAGS,
    ): String

    /** Explains why two records resolved, or did not resolve, together. */
    public fun whyRecords(
        dataSourceCode1: String,
        recordId1: String,
        dataSourceCode2: String,
        recordId2: String,
        flags: SzFlags = SzFlags.WHY_RECORDS_DEFAULT_FLAGS,
    ): String

    /** Explains why two entities did not resolve into one. */
    public fun whyEntities(
        entityId1: Long,
        entityId2: Long,
        flags: SzFlags = SzFlags.WHY_ENTITIES_DEFAULT_FLAGS,
    ): String

    /** Explains how an entity was built, step by step. */
    public fun howEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.HOW_ENTITY_DEFAULT_FLAGS,
    ): String

    /** Returns the entity the given records would form, without changing the repository. */
    public fun getVirtualEntity(
        recordKeys: Set<SzRecordKey>,
        flags: SzFlags = SzFlags.VIRTUAL_ENTITY_DEFAULT_FLAGS,
    ): String

    /** Starts a JSON export of the entities in the repository. */
    public fun exportJsonEntityReport(flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS): SzExportHandle

    /** Starts a CSV export of the entities in the repository. */
    public fun exportCsvEntityReport(
        csvColumnList: String,
        flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS,
    ): SzExportHandle

    /** Returns the next line of an export report, or null at the end. */
    public fun fetchNext(exportHandle: SzExportHandle): String?

    /** Releases an export report. */
    public fun closeExportReport(exportHandle: SzExportHandle)

    /**
     * Processes a redo record. Returns the info JSON when [flags] contains
     * [SzFlags.WITH_INFO], otherwise null.
     */
    public fun processRedoRecord(
        redoRecord: String,
        flags: SzFlags = SzFlags.REDO_DEFAULT_FLAGS,
    ): String?

    /** Returns the next pending redo record, or null when there is none. */
    public fun getRedoRecord(): String?

    /** Returns the number of pending redo records. */
    public fun countRedoRecords(): Long
}
