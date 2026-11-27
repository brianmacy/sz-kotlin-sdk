package io.github.brianmacy.sz.core

import io.github.brianmacy.sz.SzEngine
import io.github.brianmacy.sz.SzExportHandle
import io.github.brianmacy.sz.SzFlags
import io.github.brianmacy.sz.SzRecordKey
import io.github.brianmacy.sz.internal.EnvironmentState
import io.github.brianmacy.sz.internal.Native
import io.github.brianmacy.sz.internal.orNone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** The [SzEngine] over the native engine. Obtain it from [SzCoreEnvironment.getEngine]. */
public class SzCoreEngine internal constructor(
    internal val state: EnvironmentState,
) : SzEngine {
    private fun <T> call(body: () -> T): T = state.execute(body)

    /** The info JSON of a write, or null when [flags] did not ask for it. */
    private fun info(
        flags: SzFlags,
        body: () -> String,
    ): String? = call(body).takeIf { SzFlags.WITH_INFO in flags }

    override fun primeEngine(): Unit = call { Native.primeEngine() }

    override fun getStats(): String = call { Native.getStats() }

    override fun addRecord(
        dataSourceCode: String,
        recordId: String,
        recordDefinition: String,
        flags: SzFlags,
    ): String? = info(flags) { Native.addRecord(dataSourceCode, recordId, recordDefinition, flags.value) }

    override fun getRecordPreview(
        recordDefinition: String,
        flags: SzFlags,
    ): String = call { Native.getRecordPreview(recordDefinition, flags.value) }

    override fun deleteRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String? = info(flags) { Native.deleteRecord(dataSourceCode, recordId, flags.value) }

    override fun reevaluateRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String? = info(flags) { Native.reevaluateRecord(dataSourceCode, recordId, flags.value) }

    override fun reevaluateEntity(
        entityId: Long,
        flags: SzFlags,
    ): String? = info(flags) { Native.reevaluateEntity(entityId, flags.value) }

    override fun searchByAttributes(
        attributes: String,
        searchProfile: String?,
        flags: SzFlags,
    ): String = call { Native.searchByAttributes(attributes, searchProfile?.ifEmpty { null }, flags.value) }

    override fun whySearch(
        attributes: String,
        entityId: Long,
        searchProfile: String?,
        flags: SzFlags,
    ): String = call { Native.whySearch(attributes, entityId, searchProfile?.ifEmpty { null }, flags.value) }

    override fun getEntity(
        entityId: Long,
        flags: SzFlags,
    ): String = call { Native.getEntityById(entityId, flags.value) }

    override fun getEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String = call { Native.getEntityByRecord(dataSourceCode, recordId, flags.value) }

    override fun getRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String = call { Native.getRecord(dataSourceCode, recordId, flags.value) }

    override fun findInterestingEntities(
        entityId: Long,
        flags: SzFlags,
    ): String = call { Native.findInterestingEntitiesById(entityId, flags.value) }

    override fun findInterestingEntities(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String = call { Native.findInterestingEntitiesByRecord(dataSourceCode, recordId, flags.value) }

    override fun findPath(
        startEntityId: Long,
        endEntityId: Long,
        maxDegrees: Int,
        avoidEntityIds: Set<Long>,
        requiredDataSources: Set<String>,
        flags: SzFlags,
    ): String =
        call {
            Native.findPathById(
                startEntityId,
                endEntityId,
                maxDegrees.toLong(),
                avoidEntityIds.takeIf { it.isNotEmpty() }?.let(::idsJson),
                requiredDataSources.takeIf { it.isNotEmpty() }?.let(::stringsJson),
                flags.value,
            )
        }

    override fun findPath(
        startDataSourceCode: String,
        startRecordId: String,
        endDataSourceCode: String,
        endRecordId: String,
        maxDegrees: Int,
        avoidRecordKeys: Set<SzRecordKey>,
        requiredDataSources: Set<String>,
        flags: SzFlags,
    ): String =
        call {
            Native.findPathByRecord(
                startDataSourceCode,
                startRecordId,
                endDataSourceCode,
                endRecordId,
                maxDegrees.toLong(),
                avoidRecordKeys.takeIf { it.isNotEmpty() }?.let(::keysJson),
                requiredDataSources.takeIf { it.isNotEmpty() }?.let(::stringsJson),
                flags.value,
            )
        }

    override fun findNetwork(
        entityIds: Set<Long>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags,
    ): String =
        call {
            Native.findNetworkById(
                idsJson(entityIds),
                maxDegrees.toLong(),
                buildOutDegrees.toLong(),
                buildOutMaxEntities.toLong(),
                flags.value,
            )
        }

    override fun findNetworkByRecordKeys(
        recordKeys: Set<SzRecordKey>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags,
    ): String =
        call {
            Native.findNetworkByRecord(
                keysJson(recordKeys),
                maxDegrees.toLong(),
                buildOutDegrees.toLong(),
                buildOutMaxEntities.toLong(),
                flags.value,
            )
        }

    override fun whyRecordInEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags,
    ): String = call { Native.whyRecordInEntity(dataSourceCode, recordId, flags.value) }

    override fun whyRecords(
        dataSourceCode1: String,
        recordId1: String,
        dataSourceCode2: String,
        recordId2: String,
        flags: SzFlags,
    ): String = call { Native.whyRecords(dataSourceCode1, recordId1, dataSourceCode2, recordId2, flags.value) }

    override fun whyEntities(
        entityId1: Long,
        entityId2: Long,
        flags: SzFlags,
    ): String = call { Native.whyEntities(entityId1, entityId2, flags.value) }

    override fun howEntity(
        entityId: Long,
        flags: SzFlags,
    ): String = call { Native.howEntity(entityId, flags.value) }

    override fun getVirtualEntity(
        recordKeys: Set<SzRecordKey>,
        flags: SzFlags,
    ): String = call { Native.getVirtualEntity(keysJson(recordKeys), flags.value) }

    override fun exportJsonEntityReport(flags: SzFlags): SzExportHandle =
        SzExportHandle(call { Native.exportJsonEntityReport(flags.value) })

    override fun exportCsvEntityReport(
        csvColumnList: String,
        flags: SzFlags,
    ): SzExportHandle = SzExportHandle(call { Native.exportCsvEntityReport(csvColumnList, flags.value) })

    override fun fetchNext(exportHandle: SzExportHandle): String? = call { Native.fetchNext(exportHandle.value) }.orNone()

    override fun closeExportReport(exportHandle: SzExportHandle): Unit = call { Native.closeExportReport(exportHandle.value) }

    override fun processRedoRecord(
        redoRecord: String,
        flags: SzFlags,
    ): String? = info(flags) { Native.processRedoRecord(redoRecord, flags.value) }

    override fun getRedoRecord(): String? = call { Native.getRedoRecord() }.orNone()

    override fun countRedoRecords(): Long = call { Native.countRedoRecords() }

    internal companion object {
        /** Entity IDs as the JSON array the JNI library takes, in ascending order. */
        fun idsJson(ids: Set<Long>): String = JsonArray(ids.sorted().map(::JsonPrimitive)).toString()

        /** Strings as a JSON array, in ascending order. */
        fun stringsJson(values: Set<String>): String = JsonArray(values.sorted().map(::JsonPrimitive)).toString()

        /** Record keys as `[["DATA_SOURCE","RECORD_ID"], ...]`, in ascending order. */
        fun keysJson(keys: Set<SzRecordKey>): String =
            Json.encodeToString(keys.sorted().map { listOf(it.dataSourceCode, it.recordId) })
    }
}
