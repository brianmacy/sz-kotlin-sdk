package io.github.brianmacy.sz.internal

import io.github.brianmacy.sz.SzException

/**
 * The native methods of the JNI library (`sz_kotlin_jni`, built from `native/`), which calls
 * libSz's C API through sz-rust-sdk.
 *
 * Strings the engine returns pass through unchanged; an empty string means "none" (no info,
 * no redo record, end of an export). ID sets and record keys are passed as JSON. Every method
 * throws the [SzException] subclass that `szerrors.json` assigns to the native error.
 */
internal object Native {
    /** The JNI library's name, as `System.loadLibrary` takes it. */
    const val LIBRARY_NAME: String = "sz_kotlin_jni"

    init {
        System.loadLibrary(LIBRARY_NAME)
    }

    /** Called by the JNI library to raise a Senzing error; always throws. */
    @JvmStatic
    fun raise(
        className: String,
        hasCode: Boolean,
        code: Long,
        message: String,
    ): Unit = throw SzException.create(className, if (hasCode) code else null, message)

    // Environment
    @JvmStatic external fun envCreate(
        instanceName: String,
        settings: String,
        verbose: Boolean,
    )

    @JvmStatic external fun envDestroy()

    @JvmStatic external fun envInitEngine()

    @JvmStatic external fun envInitProduct()

    @JvmStatic external fun envInitConfigManager()

    @JvmStatic external fun envGetActiveConfigId(): Long

    @JvmStatic external fun envReinitialize(configId: Long)

    // Engine
    @JvmStatic external fun primeEngine()

    @JvmStatic external fun getStats(): String

    @JvmStatic external fun addRecord(
        dataSourceCode: String,
        recordId: String,
        recordDefinition: String,
        flags: Long,
    ): String

    @JvmStatic external fun getRecordPreview(
        recordDefinition: String,
        flags: Long,
    ): String

    @JvmStatic external fun deleteRecord(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun reevaluateRecord(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun reevaluateEntity(
        entityId: Long,
        flags: Long,
    ): String

    @JvmStatic external fun searchByAttributes(
        attributes: String,
        searchProfile: String?,
        flags: Long,
    ): String

    @JvmStatic external fun whySearch(
        attributes: String,
        entityId: Long,
        searchProfile: String?,
        flags: Long,
    ): String

    @JvmStatic external fun getEntityById(
        entityId: Long,
        flags: Long,
    ): String

    @JvmStatic external fun getEntityByRecord(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun getRecord(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun findInterestingEntitiesById(
        entityId: Long,
        flags: Long,
    ): String

    @JvmStatic external fun findInterestingEntitiesByRecord(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun findPathById(
        startEntityId: Long,
        endEntityId: Long,
        maxDegrees: Long,
        avoidEntityIds: String?,
        requiredDataSources: String?,
        flags: Long,
    ): String

    @JvmStatic external fun findPathByRecord(
        startDataSourceCode: String,
        startRecordId: String,
        endDataSourceCode: String,
        endRecordId: String,
        maxDegrees: Long,
        avoidRecordKeys: String?,
        requiredDataSources: String?,
        flags: Long,
    ): String

    @JvmStatic external fun findNetworkById(
        entityIds: String,
        maxDegrees: Long,
        buildOutDegrees: Long,
        buildOutMaxEntities: Long,
        flags: Long,
    ): String

    @JvmStatic external fun findNetworkByRecord(
        recordKeys: String,
        maxDegrees: Long,
        buildOutDegrees: Long,
        buildOutMaxEntities: Long,
        flags: Long,
    ): String

    @JvmStatic external fun whyRecordInEntity(
        dataSourceCode: String,
        recordId: String,
        flags: Long,
    ): String

    @JvmStatic external fun whyRecords(
        dataSourceCode1: String,
        recordId1: String,
        dataSourceCode2: String,
        recordId2: String,
        flags: Long,
    ): String

    @JvmStatic external fun whyEntities(
        entityId1: Long,
        entityId2: Long,
        flags: Long,
    ): String

    @JvmStatic external fun howEntity(
        entityId: Long,
        flags: Long,
    ): String

    @JvmStatic external fun getVirtualEntity(
        recordKeys: String,
        flags: Long,
    ): String

    @JvmStatic external fun exportJsonEntityReport(flags: Long): Long

    @JvmStatic external fun exportCsvEntityReport(
        csvColumnList: String,
        flags: Long,
    ): Long

    @JvmStatic external fun fetchNext(exportHandle: Long): String

    @JvmStatic external fun closeExportReport(exportHandle: Long)

    @JvmStatic external fun processRedoRecord(
        redoRecord: String,
        flags: Long,
    ): String

    @JvmStatic external fun getRedoRecord(): String

    @JvmStatic external fun countRedoRecords(): Long

    // Configuration
    @JvmStatic external fun configTemplate(): String

    @JvmStatic external fun configFromId(configId: Long): String

    @JvmStatic external fun configNormalize(definition: String): String

    @JvmStatic external fun configDataSourceRegistry(definition: String): String

    /** Returns `[result, updated definition]`. */
    @JvmStatic external fun configRegisterDataSource(
        definition: String,
        dataSourceCode: String,
    ): Array<String>

    /** Returns `["", updated definition]`. */
    @JvmStatic external fun configUnregisterDataSource(
        definition: String,
        dataSourceCode: String,
    ): Array<String>

    @JvmStatic external fun registerConfig(
        definition: String,
        comment: String,
    ): Long

    @JvmStatic external fun getConfigRegistry(): String

    @JvmStatic external fun getDefaultConfigId(): Long

    @JvmStatic external fun replaceDefaultConfigId(
        currentDefaultConfigId: Long,
        newDefaultConfigId: Long,
    )

    @JvmStatic external fun setDefaultConfigId(configId: Long)

    // Product and diagnostic
    @JvmStatic external fun getLicense(): String

    @JvmStatic external fun getVersion(): String

    @JvmStatic external fun getRepositoryInfo(): String

    @JvmStatic external fun checkRepositoryPerformance(secondsToRun: Long): String

    @JvmStatic external fun purgeRepository()

    @JvmStatic external fun getFeature(featureId: Long): String

    /** The `szerrors.json` class name of a native error code. */
    @JvmStatic external fun classify(code: Long): String

    // Logging
    @JvmStatic external fun logRegister(
        minLevel: Int,
        format: Int,
        sink: NativeLogSink,
    ): Long

    @JvmStatic external fun logUnregister(handle: Long)

    @JvmStatic external fun logLeak(handle: Long)

    @JvmStatic external fun logSetDefaultCallback(
        minLevel: Int,
        format: Int,
        sink: NativeLogSink,
    )

    @JvmStatic external fun logSetDefaultOutput(
        outputType: Int,
        path: String?,
    )

    @JvmStatic external fun logSetDefaultFormat(format: Int)

    @JvmStatic external fun logSetDefaultLevel(level: Int)

    @JvmStatic external fun logEffectiveLevel(): Int
}

/** The receiver of native log records (called by the JNI library on the logging thread). */
internal fun interface NativeLogSink {
    /** Delivers one record; must not throw. */
    fun deliver(
        level: Int,
        rawModule: String,
        message: String,
    )
}

/** An engine string result where empty means "none". */
internal fun String.orNone(): String? = ifEmpty { null }
