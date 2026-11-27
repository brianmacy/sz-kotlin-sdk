package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEngine
import io.github.brianmacy.sz.internal.EngineJob
import io.github.brianmacy.sz.internal.EngineWorkerPool
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SzEngine] for coroutines: every engine method as a `suspend` function that runs on a fixed
 * pool of worker threads, and exports and the redo queue as [Flow]s.
 *
 * The Senzing engine is synchronous: each call blocks its thread until it finishes.
 * `SzAsyncEngine` is a Kotlin convenience, with no counterpart in the official Senzing SDKs,
 * that keeps that blocking work off the main thread and off the shared coroutine dispatchers.
 * Suspending does not make a call faster or non-blocking.
 *
 * ```kotlin
 * val engine = SzAsyncEngine(environment.getEngine())
 * engine.addRecord("CUSTOMERS", "1001", json)
 * val entity = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
 * engine.exportJsonEntityReport().collect { line -> println(line) }
 * ```
 *
 * ## Worker threads
 *
 * The engine builds working state per OS thread on the thread's first call and releases it
 * when the thread exits. `SzAsyncEngine` therefore creates [maxConcurrentCalls] long-lived
 * worker threads once and runs every call on one of them, in submission order. At most that
 * many calls run at once, in parallel inside the engine, which is thread-safe. The workers are
 * joined when the environment is destroyed (before native teardown) or on [shutdown].
 *
 * ## Cancellation
 *
 * A call whose coroutine is cancelled before a worker picks it up throws
 * `CancellationException` and never reaches the engine. A call a worker has started cannot be
 * interrupted: it runs to completion and its changes are committed, but the cancelled
 * coroutine still resumes with `CancellationException`.
 *
 * ## Errors
 *
 * The functions throw the engine's [SzException]s; after the environment is destroyed,
 * [SzEnvironmentDestroyedException]; after [shutdown], [SzException].
 *
 * @param engine The engine to run calls on.
 * @param maxConcurrentCalls The number of worker threads (at least 1); defaults to
 *   [DEFAULT_MAX_CONCURRENT_CALLS].
 */
public class SzAsyncEngine(
    public val engine: SzEngine,
    maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT_CALLS,
) : AutoCloseable {
    /** The number of worker threads: the most calls that run at once. */
    public val maxConcurrentCalls: Int = maxConcurrentCalls.coerceAtLeast(1)

    private val workers = EngineWorkerPool(this.maxConcurrentCalls, "sz-async-engine")

    init {
        (engine as? SzCoreEngine)?.state?.register(workers)
    }

    /**
     * Stops the worker threads: queued calls fail, running calls finish, and the workers are
     * joined before this returns. Later calls throw. The environment's `destroy()` does this
     * for you. Blocks until the running calls finish. Idempotent.
     */
    public fun shutdown(): Unit = workers.shutdown(shutdownError())

    /** Shuts down ([shutdown]). */
    override fun close(): Unit = shutdown()

    /** Whether the workers are shut down (by [shutdown] or the environment's `destroy()`). */
    public val isShutDown: Boolean get() = workers.isShutDown

    /**
     * Runs [operation] with the engine on a worker thread and returns its result.
     *
     * @throws CancellationException if the coroutine is cancelled.
     */
    public suspend fun <T> perform(operation: (SzEngine) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val job =
                EngineJob(
                    work = {
                        val result = runCatching { operation(engine) }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    },
                    abandon = continuation::resumeWithException,
                )
            continuation.invokeOnCancellation { job.fail(it ?: shutdownError()) }
            workers.submit(job)
        }

    /**
     * Processes pending redo records, on one worker, until none remain.
     *
     * [SzEngine.getRedoRecord] dequeues each record, so one that fails to process is not lost:
     * it is returned in [SzRedoReport.failed] with its error, and the drain continues. Retry
     * those whose error [SzException.isRetryable].
     *
     * @throws SzException if fetching the next redo record fails.
     */
    public suspend fun processRedoRecords(): SzRedoReport =
        perform { engine -> SzRedoReport.drain(engine::getRedoRecord) { engine.processRedoRecord(it) } }

    /** The pending redo records, each dequeued as it is collected; completes when none remain. */
    public fun redoRecords(): Flow<String> =
        flow {
            while (true) emit(perform { it.getRedoRecord() } ?: break)
        }

    /**
     * The lines of a JSON export of the entities in the repository. The export handle is
     * closed when collection ends, fails or is cancelled.
     */
    public fun exportJsonEntityReport(flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS): Flow<String> =
        export { it.exportJsonEntityReport(flags) }

    /**
     * The lines of a CSV export of the entities in the repository (header first). The export
     * handle is closed when collection ends, fails or is cancelled.
     */
    public fun exportCsvEntityReport(
        csvColumnList: String,
        flags: SzFlags = SzFlags.EXPORT_DEFAULT_FLAGS,
    ): Flow<String> = export { it.exportCsvEntityReport(csvColumnList, flags) }

    private fun export(open: (SzEngine) -> SzExportHandle): Flow<String> =
        flow {
            val handle = perform(open)
            var failure: Throwable? = null
            try {
                while (true) emit(perform { it.fetchNext(handle) } ?: break)
            } catch (e: Throwable) {
                failure = e
                throw e
            } finally {
                // Close even when cancelled; a read or collector failure takes precedence.
                withContext(NonCancellable) {
                    try {
                        perform { it.closeExportReport(handle) }
                    } catch (e: Throwable) {
                        failure?.addSuppressed(e) ?: throw e
                    }
                }
            }
        }

    // Every SzEngine method, as a suspend function (SzAsyncEngineTest checks the parity).

    /** See [SzEngine.primeEngine]. */
    public suspend fun primeEngine(): Unit = perform { it.primeEngine() }

    /** See [SzEngine.getStats]. */
    public suspend fun getStats(): String = perform { it.getStats() }

    /** See [SzEngine.addRecord]. */
    public suspend fun addRecord(
        dataSourceCode: String,
        recordId: String,
        recordDefinition: String,
        flags: SzFlags = SzFlags.ADD_RECORD_DEFAULT_FLAGS,
    ): String? = perform { it.addRecord(dataSourceCode, recordId, recordDefinition, flags) }

    /** See [SzEngine.getRecordPreview]. */
    public suspend fun getRecordPreview(
        recordDefinition: String,
        flags: SzFlags = SzFlags.RECORD_PREVIEW_DEFAULT_FLAGS,
    ): String = perform { it.getRecordPreview(recordDefinition, flags) }

    /** See [SzEngine.deleteRecord]. */
    public suspend fun deleteRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.DELETE_RECORD_DEFAULT_FLAGS,
    ): String? = perform { it.deleteRecord(dataSourceCode, recordId, flags) }

    /** See [SzEngine.reevaluateRecord]. */
    public suspend fun reevaluateRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.REEVALUATE_RECORD_DEFAULT_FLAGS,
    ): String? = perform { it.reevaluateRecord(dataSourceCode, recordId, flags) }

    /** See [SzEngine.reevaluateEntity]. */
    public suspend fun reevaluateEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.REEVALUATE_ENTITY_DEFAULT_FLAGS,
    ): String? = perform { it.reevaluateEntity(entityId, flags) }

    /** See [SzEngine.searchByAttributes]. */
    public suspend fun searchByAttributes(
        attributes: String,
        searchProfile: String? = null,
        flags: SzFlags = SzFlags.SEARCH_BY_ATTRIBUTES_DEFAULT_FLAGS,
    ): String = perform { it.searchByAttributes(attributes, searchProfile, flags) }

    /** See [SzEngine.whySearch]. */
    public suspend fun whySearch(
        attributes: String,
        entityId: Long,
        searchProfile: String? = null,
        flags: SzFlags = SzFlags.WHY_SEARCH_DEFAULT_FLAGS,
    ): String = perform { it.whySearch(attributes, entityId, searchProfile, flags) }

    /** See [SzEngine.getEntity]. */
    public suspend fun getEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
    ): String = perform { it.getEntity(entityId, flags) }

    /** See [SzEngine.getEntity]. */
    public suspend fun getEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.ENTITY_DEFAULT_FLAGS,
    ): String = perform { it.getEntity(dataSourceCode, recordId, flags) }

    /** See [SzEngine.getRecord]. */
    public suspend fun getRecord(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.RECORD_DEFAULT_FLAGS,
    ): String = perform { it.getRecord(dataSourceCode, recordId, flags) }

    /** See [SzEngine.findInterestingEntities]. */
    public suspend fun findInterestingEntities(
        entityId: Long,
        flags: SzFlags = SzFlags.FIND_INTERESTING_ENTITIES_DEFAULT_FLAGS,
    ): String = perform { it.findInterestingEntities(entityId, flags) }

    /** See [SzEngine.findInterestingEntities]. */
    public suspend fun findInterestingEntities(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.FIND_INTERESTING_ENTITIES_DEFAULT_FLAGS,
    ): String = perform { it.findInterestingEntities(dataSourceCode, recordId, flags) }

    /** See [SzEngine.findPath]. */
    public suspend fun findPath(
        startEntityId: Long,
        endEntityId: Long,
        maxDegrees: Int,
        avoidEntityIds: Set<Long> = emptySet(),
        requiredDataSources: Set<String> = emptySet(),
        flags: SzFlags = SzFlags.FIND_PATH_DEFAULT_FLAGS,
    ): String = perform { it.findPath(startEntityId, endEntityId, maxDegrees, avoidEntityIds, requiredDataSources, flags) }

    /** See [SzEngine.findPath]. */
    public suspend fun findPath(
        startDataSourceCode: String,
        startRecordId: String,
        endDataSourceCode: String,
        endRecordId: String,
        maxDegrees: Int,
        avoidRecordKeys: Set<SzRecordKey> = emptySet(),
        requiredDataSources: Set<String> = emptySet(),
        flags: SzFlags = SzFlags.FIND_PATH_DEFAULT_FLAGS,
    ): String =
        perform {
            it.findPath(
                startDataSourceCode,
                startRecordId,
                endDataSourceCode,
                endRecordId,
                maxDegrees,
                avoidRecordKeys,
                requiredDataSources,
                flags,
            )
        }

    /** See [SzEngine.findNetwork]. */
    public suspend fun findNetwork(
        entityIds: Set<Long>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags = SzFlags.FIND_NETWORK_DEFAULT_FLAGS,
    ): String = perform { it.findNetwork(entityIds, maxDegrees, buildOutDegrees, buildOutMaxEntities, flags) }

    /** See [SzEngine.findNetworkByRecordKeys]. */
    public suspend fun findNetworkByRecordKeys(
        recordKeys: Set<SzRecordKey>,
        maxDegrees: Int,
        buildOutDegrees: Int,
        buildOutMaxEntities: Int,
        flags: SzFlags = SzFlags.FIND_NETWORK_DEFAULT_FLAGS,
    ): String = perform { it.findNetworkByRecordKeys(recordKeys, maxDegrees, buildOutDegrees, buildOutMaxEntities, flags) }

    /** See [SzEngine.whyRecordInEntity]. */
    public suspend fun whyRecordInEntity(
        dataSourceCode: String,
        recordId: String,
        flags: SzFlags = SzFlags.WHY_RECORD_IN_ENTITY_DEFAULT_FLAGS,
    ): String = perform { it.whyRecordInEntity(dataSourceCode, recordId, flags) }

    /** See [SzEngine.whyRecords]. */
    public suspend fun whyRecords(
        dataSourceCode1: String,
        recordId1: String,
        dataSourceCode2: String,
        recordId2: String,
        flags: SzFlags = SzFlags.WHY_RECORDS_DEFAULT_FLAGS,
    ): String = perform { it.whyRecords(dataSourceCode1, recordId1, dataSourceCode2, recordId2, flags) }

    /** See [SzEngine.whyEntities]. */
    public suspend fun whyEntities(
        entityId1: Long,
        entityId2: Long,
        flags: SzFlags = SzFlags.WHY_ENTITIES_DEFAULT_FLAGS,
    ): String = perform { it.whyEntities(entityId1, entityId2, flags) }

    /** See [SzEngine.howEntity]. */
    public suspend fun howEntity(
        entityId: Long,
        flags: SzFlags = SzFlags.HOW_ENTITY_DEFAULT_FLAGS,
    ): String = perform { it.howEntity(entityId, flags) }

    /** See [SzEngine.getVirtualEntity]. */
    public suspend fun getVirtualEntity(
        recordKeys: Set<SzRecordKey>,
        flags: SzFlags = SzFlags.VIRTUAL_ENTITY_DEFAULT_FLAGS,
    ): String = perform { it.getVirtualEntity(recordKeys, flags) }

    /** See [SzEngine.fetchNext]. */
    public suspend fun fetchNext(exportHandle: SzExportHandle): String? = perform { it.fetchNext(exportHandle) }

    /** See [SzEngine.closeExportReport]. */
    public suspend fun closeExportReport(exportHandle: SzExportHandle): Unit = perform { it.closeExportReport(exportHandle) }

    /** See [SzEngine.processRedoRecord]. */
    public suspend fun processRedoRecord(
        redoRecord: String,
        flags: SzFlags = SzFlags.REDO_DEFAULT_FLAGS,
    ): String? = perform { it.processRedoRecord(redoRecord, flags) }

    /** See [SzEngine.getRedoRecord]. */
    public suspend fun getRedoRecord(): String? = perform { it.getRedoRecord() }

    /** See [SzEngine.countRedoRecords]. */
    public suspend fun countRedoRecords(): Long = perform { it.countRedoRecords() }

    public companion object {
        /**
         * The default [maxConcurrentCalls]: the available processors, at most 4.
         *
         * Each engine thread holds its own native working state (Senzing's published guidance
         * sizes it at 0.5–1 GB per engine thread), so the default stays small enough for
         * desktop and mobile memory. A server loading data should raise it: Senzing's guidance
         * for loading is 2–8 threads per CPU core, tuned to the storage. More threads do not
         * raise throughput on SQLite, which allows one writer at a time.
         */
        public val DEFAULT_MAX_CONCURRENT_CALLS: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        private fun shutdownError() = SzException("The SzAsyncEngine has been shut down.")
    }
}
