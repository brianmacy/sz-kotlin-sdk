package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.SzJson
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzSearchResponse
import io.github.brianmacy.sz.models.SzWhyResponse
import io.github.brianmacy.sz.models.SzWithInfoResponse
import io.github.brianmacy.sz.testsupport.Truthset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SzAsyncEngineTest {
    /** A record as a serializable value, with Senzing attribute names. */
    @Serializable
    private data class Contact(
        @SerialName("NAME_FULL") val fullName: String,
        @SerialName("EMAIL_ADDRESS") val email: String,
    )

    /** Holds a worker inside an operation until the test opens it. */
    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)

        /** Called on the worker: signals entry, then blocks until [open]. */
        fun hold() {
            entered.countDown()
            check(released.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "gate never opened" }
        }

        fun awaitHeld() {
            check(entered.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "no worker reached the gate" }
        }

        fun open() = released.countDown()
    }

    /**
     * The real engine, recording the export handles it opens and closes so a test can check
     * that an export flow released its handle. Every call reaches the real engine.
     */
    private class HandleRecordingEngine(
        private val real: SzEngine,
    ) : SzEngine by real {
        val opened: MutableList<SzExportHandle> = Collections.synchronizedList(mutableListOf())
        val closed: MutableList<SzExportHandle> = Collections.synchronizedList(mutableListOf())

        override fun exportJsonEntityReport(flags: SzFlags): SzExportHandle = real.exportJsonEntityReport(flags).also { opened += it }

        override fun exportCsvEntityReport(
            csvColumnList: String,
            flags: SzFlags,
        ): SzExportHandle = real.exportCsvEntityReport(csvColumnList, flags).also { opened += it }

        override fun closeExportReport(exportHandle: SzExportHandle) {
            real.closeExportReport(exportHandle)
            closed += exportHandle
        }

        /** Asserts every opened handle was closed exactly once. */
        fun assertAllClosed() {
            assertTrue(opened.isNotEmpty())
            assertEquals(opened.toList(), closed.toList())
        }
    }

    /** Adds the truth set without processing its redo records. */
    private fun addTruthsetSerially(engine: SzEngine) {
        for (record in Truthset.allRecords()) engine.addRecord(record.dataSourceCode, record.recordId, record.definition)
    }

    private fun Flow<String>.entities(): Flow<SzEntityResponse> = map { SzJson.decode(SzEntityResponse.serializer(), it) }

    @Test
    fun `test_concurrent async loading resolves the truth set`() {
        EngineFixture().use { fixture ->
            SzAsyncEngine(fixture.engine, maxConcurrentCalls = 3).use { engine ->
                assertEquals(3, engine.maxConcurrentCalls)
                val records = Truthset.allRecords()
                runBlocking(Dispatchers.Default) {
                    records
                        .map { record ->
                            async { engine.addRecord(record.dataSourceCode, record.recordId, record.definition) }
                        }.awaitAll()
                    assertTrue(engine.processRedoRecords().failed.isEmpty())
                    val entity = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
                    val keys =
                        entity.resolvedEntity.records!!
                            .map { it.recordKey }
                            .toSet()
                    assertTrue(
                        keys.containsAll(listOf("1002", "1003", "1004").map { SzRecordKey.of("CUSTOMERS", it) }),
                        keys.toString(),
                    )
                    val exported = engine.exportJsonEntityReport().entities().toList()
                    assertEquals(records.size, exported.sumOf { it.resolvedEntity.records?.size ?: 0 })
                }
            }
        }
    }

    @Test
    fun `test_maxConcurrentCalls is at least one and defaults to the processors, at most 4`() {
        EngineFixture().use { fixture ->
            SzAsyncEngine(fixture.engine, maxConcurrentCalls = 0).use { assertEquals(1, it.maxConcurrentCalls) }
            SzAsyncEngine(fixture.engine).use { engine ->
                assertEquals(SzAsyncEngine.DEFAULT_MAX_CONCURRENT_CALLS, engine.maxConcurrentCalls)
                assertSame(fixture.engine, engine.engine)
            }
            assertTrue(SzAsyncEngine.DEFAULT_MAX_CONCURRENT_CALLS in 1..4)
        }
    }

    @Test
    fun `test_processRedoRecords drains the pending redo records`() {
        EngineFixture().use { fixture ->
            addTruthsetSerially(fixture.engine)
            val pending = fixture.engine.countRedoRecords()
            assertTrue(pending > 0, "a serial truth-set load leaves redo records")
            SzAsyncEngine(fixture.engine).use { engine ->
                val report = runBlocking { engine.processRedoRecords() }
                assertTrue(report.processed >= pending, "$report, $pending pending")
                assertTrue(report.failed.isEmpty(), report.toString())
            }
            assertEquals(0, fixture.engine.countRedoRecords())
        }
    }

    @Test
    fun `test_redoRecords flow dequeues each pending redo record`() {
        EngineFixture().use { fixture ->
            addTruthsetSerially(fixture.engine)
            val pending = fixture.engine.countRedoRecords()
            assertTrue(pending > 0)
            SzAsyncEngine(fixture.engine).use { engine ->
                val redos =
                    runBlocking {
                        engine.redoRecords().toList().onEach { assertNull(engine.processRedoRecord(it)) }
                    }
                assertEquals(pending, redos.size.toLong())
                assertTrue(redos.all { it.startsWith("{") }, redos.first())
                assertEquals(0, runBlocking { engine.countRedoRecords() })
                assertEquals(emptyList(), runBlocking { engine.redoRecords().toList() }, "an empty queue completes at once")
            }
        }
    }

    @Test
    fun `test_redoFailures a redo record that fails is reported and the drain continues`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            // Real engine calls on malformed redo records: each fails, and the drain keeps going
            // until the queue is empty.
            val queue = ArrayDeque(listOf("{bad", "[1", "{bad"))
            val report = SzRedoReport.drain(next = { queue.removeFirstOrNull() }) { engine.processRedoRecord(it) }
            assertTrue(queue.isEmpty())
            assertEquals(0, report.processed)
            assertEquals(listOf("{bad", "[1", "{bad"), report.failed.map { it.redoRecord })
            assertTrue(report.failed.all { it.error is SzBadInputException }, report.failed.toString())

            // A mix: successes are counted, failures kept in fetch order.
            addTruthsetSerially(engine)
            val real = engine.getRedoRecord()!!
            val mixed = ArrayDeque(listOf(real, "{bad"))
            val mixedReport = SzRedoReport.drain(next = { mixed.removeFirstOrNull() }) { engine.processRedoRecord(it) }
            assertEquals(1, mixedReport.processed)
            assertEquals(listOf("{bad"), mixedReport.failed.map { it.redoRecord })
            assertEquals(SzRedoReport(), SzRedoReport.drain(next = { null }) { error("never called") })
        }
    }

    @Test
    fun `test_records every async record method matches the engine`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            SzAsyncEngine(fixture.engine).use { engine ->
                runBlocking {
                    engine.primeEngine()
                    assertTrue(engine.getStats().contains("workload"))
                    val info = engine.addRecord("CUSTOMERS", "A1", person("Ann Lee"), SzFlags.WITH_INFO)
                    assertTrue(info!!.contains("A1"), info)
                    assertNull(engine.addRecord("CUSTOMERS", "A3", Contact("Ann Lee", "ann@x.com"), Contact.serializer()))
                    val typedInfo =
                        engine.perform { it.addRecord("CUSTOMERS", "A2", person("Ann Lee"), SzWithInfoResponse.serializer()) }
                    assertEquals("A2", typedInfo.recordId)
                    assertTrue(engine.getRecord("CUSTOMERS", "A3").contains("ann@x.com"))
                    assertTrue(engine.getRecordPreview(person("Ann Lee")).contains("FEATURES"))
                    assertNull(engine.reevaluateRecord("CUSTOMERS", "A3"))
                    val entityId = fixture.entityId("CUSTOMERS", "A3")
                    assertNull(engine.reevaluateEntity(entityId))
                    assertNull(engine.deleteRecord("CUSTOMERS", "A3"))
                    val deleted = engine.deleteRecord("CUSTOMERS", "A2", SzFlags.WITH_INFO)
                    assertTrue(deleted!!.contains("A2"), deleted)

                    assertTrue(engine.countRedoRecords() >= 0)
                    while (true) engine.processRedoRecord(engine.getRedoRecord() ?: break)
                    assertEquals(0, engine.countRedoRecords())
                }
            }
        }
    }

    @Test
    fun `test_entities every async read method matches the engine`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val sync = fixture.engine
            SzAsyncEngine(sync).use { engine ->
                val entityId = fixture.entityId("CUSTOMERS", "1001")
                val other = fixture.entityId("WATCHLIST", "1006")
                runBlocking {
                    assertEquals(sync.getEntity(entityId), engine.getEntity(entityId))
                    assertEquals(entityId, engine.getEntity(entityId, SzEntityResponse.serializer()).resolvedEntity.entityId)
                    assertEquals(sync.getEntity("CUSTOMERS", "1001"), engine.getEntity("CUSTOMERS", "1001"))
                    val byRecord = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
                    assertEquals(entityId, byRecord.resolvedEntity.entityId)

                    val query = person("Robert Smith", mapOf("DATE_OF_BIRTH" to "12/11/1978"))
                    assertEquals(sync.searchByAttributes(query), engine.searchByAttributes(query))
                    val results = engine.searchByAttributes(query, SzSearchResponse.serializer())
                    assertEquals(
                        entityId,
                        results.resolvedEntities
                            .first()
                            .entity.resolvedEntity.entityId,
                    )
                    assertTrue(engine.whySearch(query, entityId).contains("WHY_RESULTS"))
                    assertTrue(engine.howEntity(entityId).contains("RESOLUTION_STEPS"))
                    assertTrue(engine.findInterestingEntities(entityId).contains("INTERESTING_ENTITIES"))
                    assertTrue(engine.findInterestingEntities("CUSTOMERS", "1001").contains("INTERESTING_ENTITIES"))
                    val keys = setOf(SzRecordKey.of("CUSTOMERS", "1001"), SzRecordKey.of("CUSTOMERS", "1002"))
                    assertTrue(engine.getVirtualEntity(keys).contains("RESOLVED_ENTITY"))

                    assertTrue(engine.findPath(entityId, other, 3).contains("ENTITY_PATHS"))
                    assertTrue(engine.findPath("CUSTOMERS", "1001", "WATCHLIST", "1006", 3).contains("ENTITY_PATHS"))
                    assertTrue(engine.findNetwork(setOf(entityId, other), 2, 1, 10).contains("ENTITY_NETWORK_LINKS"))
                    val network = engine.findNetworkByRecordKeys(setOf(SzRecordKey.of("CUSTOMERS", "1001")), 1, 1, 10)
                    assertTrue(network.contains("ENTITY_NETWORK_LINKS"))
                }
            }
        }
    }

    @Test
    fun `test_whyAndExport every async why and export method matches the engine`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            SzAsyncEngine(fixture.engine).use { engine ->
                val entityId = fixture.entityId("CUSTOMERS", "1001")
                val other = fixture.entityId("WATCHLIST", "1006")
                runBlocking {
                    assertTrue(engine.whyRecordInEntity("CUSTOMERS", "1001").contains("WHY"))
                    val whyRecords = engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "1002")
                    assertTrue(whyRecords.contains("WHY_RESULTS"))
                    assertEquals(
                        "RESOLVED",
                        SzJson
                            .decode(SzWhyResponse.serializer(), whyRecords)
                            .whyResults
                            .first()
                            .matchInfo.matchLevelCode,
                    )
                    val whyEntities = engine.whyEntities(entityId, other)
                    assertEquals(
                        other,
                        SzJson
                            .decode(SzWhyResponse.serializer(), whyEntities)
                            .whyResults
                            .first()
                            .entityId2,
                    )

                    val lines = fixture.engine.exportJsonEntityLines()
                    assertEquals(lines, engine.exportJsonEntityReport().toList())
                    assertEquals(
                        lines.size,
                        engine
                            .exportJsonEntityReport()
                            .entities()
                            .toList()
                            .size,
                    )
                    val handle = engine.perform { it.exportJsonEntityReport() }
                    var fetched = 0
                    while (engine.fetchNext(handle) != null) fetched += 1
                    engine.closeExportReport(handle)
                    assertEquals(lines.size, fetched)

                    val csv = engine.exportCsvEntityReport("*").toList()
                    assertTrue(csv.first().contains("RESOLVED_ENTITY_ID"), csv.first())
                    assertTrue(csv.size > 1)

                    assertFailsWith<SzNotFoundException> { engine.getEntity(999_999L) }
                }
            }
        }
    }

    @Test
    fun `test_exportFlows close the handle when complete, cancelled, or the collector throws`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val lineCount = fixture.engine.exportJsonEntityLines().size
            assertTrue(lineCount > 2)
            val recording = HandleRecordingEngine(fixture.engine)
            SzAsyncEngine(recording, maxConcurrentCalls = 2).use { engine ->
                runBlocking {
                    // Complete.
                    assertEquals(lineCount, engine.exportJsonEntityReport().toList().size)
                    assertTrue(engine.exportCsvEntityReport("RESOLVED_ENTITY_ID,RECORD_ID", SzFlags.EXPORT_DEFAULT_FLAGS).toList().size > 1)
                    recording.assertAllClosed()

                    // Cancelled mid-collection.
                    val received = CompletableDeferred<String>()
                    val collector =
                        launch {
                            engine.exportJsonEntityReport().collect { line ->
                                received.complete(line)
                                awaitCancellation()
                            }
                        }
                    assertTrue(received.await().startsWith("{"))
                    collector.cancelAndJoin()
                    assertEquals(3, recording.closed.size)
                    recording.assertAllClosed()

                    // Stopped by an operator after the first line.
                    assertTrue(engine.exportCsvEntityReport("*").first().contains("RESOLVED_ENTITY_ID"))
                    recording.assertAllClosed()

                    // The collector throws: its exception propagates, and the handle is closed.
                    val thrown =
                        assertFailsWith<IllegalStateException> {
                            engine.exportJsonEntityReport().collect { error("collector failed") }
                        }
                    assertEquals("collector failed", thrown.message)
                    assertTrue(thrown.suppressed.isEmpty())
                    recording.assertAllClosed()
                    assertEquals(5, recording.closed.size)
                }
            }
            // The real engine released each handle: a closed handle cannot be read.
            assertFailsWith<SzException> { fixture.engine.fetchNext(recording.closed.first()) }
        }
    }

    @Test
    fun `test_exportFlow a close that fails is suppressed into the collector's failure`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = SzAsyncEngine(fixture.engine)
            val thrown =
                assertFailsWith<IllegalStateException> {
                    runBlocking {
                        engine.exportJsonEntityReport().collect {
                            fixture.environment.destroy()
                            error("collector failed after destroy")
                        }
                    }
                }
            assertTrue(engine.isShutDown)
            assertEquals(1, thrown.suppressed.size, thrown.suppressed.toList().toString())
            assertTrue(thrown.suppressed[0] is SzEnvironmentDestroyedException, thrown.suppressed[0].toString())

            // Destroyed before the export starts: opening fails.
            assertFailsWith<SzEnvironmentDestroyedException> { runBlocking { engine.exportJsonEntityReport().toList() } }
        }
    }

    @Test
    fun `test_parity every SzEngine method has a suspend counterpart, exports as Flows`() {
        // Value-class parameters (SzFlags, SzExportHandle) mangle JVM names as `name-hash`.
        fun Method.baseName() = name.substringBefore('-')

        fun Method.isApi() = Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !isSynthetic && '$' !in name

        val flowMethods = setOf("exportJsonEntityReport", "exportCsvEntityReport")
        val asyncMethods = SzAsyncEngine::class.java.declaredMethods.filter { it.isApi() }
        val missing = mutableListOf<String>()
        val engineMethods = SzEngine::class.java.methods.filter { it.isApi() }
        assertTrue(engineMethods.size >= 30, "${engineMethods.size} SzEngine methods")
        for (method in engineMethods) {
            val parameters = method.parameterTypes.toList()
            val match =
                if (method.baseName() in flowMethods) {
                    asyncMethods.firstOrNull {
                        it.baseName() == method.baseName() &&
                            it.parameterTypes.toList() == parameters &&
                            it.returnType == Flow::class.java
                    }
                } else {
                    asyncMethods.firstOrNull {
                        it.baseName() == method.baseName() &&
                            it.parameterTypes.toList() == parameters + Continuation::class.java
                    }
                }
            if (match == null) missing += "${method.baseName()}(${parameters.joinToString { it.simpleName }})"
        }
        assertEquals(emptyList(), missing, "SzEngine methods without an SzAsyncEngine counterpart")
    }

    @Test
    fun `test_workers calls run on the same long-lived worker threads`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            SzAsyncEngine(fixture.engine, maxConcurrentCalls = 2).use { engine ->
                val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
                val caller = Thread.currentThread()
                // Two calls that wait for each other must run on both workers at once.
                val barrier = CyclicBarrier(2)
                runBlocking {
                    List(2) {
                        async {
                            engine.perform {
                                threads += Thread.currentThread()
                                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                            }
                        }
                    }.awaitAll()
                }
                assertEquals(2, threads.size)
                repeat(3) {
                    runBlocking(Dispatchers.Default) {
                        List(100) {
                            async {
                                engine.perform { engine ->
                                    threads += Thread.currentThread()
                                    engine.getEntity("CUSTOMERS", "1001")
                                }
                            }
                        }.awaitAll()
                    }
                }
                assertEquals(2, threads.size, "${threads.size} threads touched the engine")
                assertFalse(caller in threads)
                assertTrue(threads.all { it.name.startsWith("sz-async-engine-") && it.isDaemon }, threads.toString())
            }
        }
    }

    @Test
    fun `test_cancellation a call cancelled before a worker starts it never reaches the engine`() {
        EngineFixture().use { fixture ->
            SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1).use { engine ->
                val reached = AtomicInteger()
                val gate = Gate()
                runBlocking {
                    val blocker = async(Dispatchers.Default) { engine.perform { gate.hold() } }
                    gate.awaitHeld()

                    // Queued behind the held call: UNDISPATCHED submits before async returns.
                    val waiting = async(start = CoroutineStart.UNDISPATCHED) { engine.perform { reached.incrementAndGet() } }
                    waiting.cancel()
                    assertFailsWith<CancellationException> { waiting.await() }

                    // A coroutine cancelled before it calls the engine.
                    val early =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            currentCoroutineContext().job.cancel()
                            engine.perform { reached.incrementAndGet() }
                        }
                    assertFailsWith<CancellationException> { early.await() }

                    gate.open()
                    blocker.await()
                    // Runs after the abandoned jobs, which the single worker skips.
                    engine.perform { }
                }
                assertEquals(0, reached.get())
            }
        }
    }

    @Test
    fun `test_cancellation a call a worker has started runs to completion and commits`() {
        EngineFixture().use { fixture ->
            SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1).use { engine ->
                val gate = Gate()
                val finished = CountDownLatch(1)
                runBlocking {
                    val started =
                        async(Dispatchers.Default) {
                            engine.perform { engine ->
                                gate.hold()
                                engine.addRecord("CUSTOMERS", "C1", person("Cora Diaz"), SzFlags.WITH_INFO).also {
                                    finished.countDown()
                                }
                            }
                        }
                    gate.awaitHeld()
                    started.cancel()
                    // The cancelled coroutine resumes at once; the worker's call is not interrupted.
                    assertFailsWith<CancellationException> { started.await() }
                    gate.open()
                    assertTrue(finished.await(WAIT_SECONDS, TimeUnit.SECONDS))
                    // The single worker runs this only after the started call returns.
                    assertTrue(engine.perform { it.getRecord("CUSTOMERS", "C1") }.contains("C1"))
                }
                assertTrue(fixture.engine.getRecord("CUSTOMERS", "C1").contains("Cora Diaz"))
            }
        }
    }

    @Test
    fun `test_destroy queued calls fail, the running call finishes, nothing reaches the engine after`() {
        EngineFixture().use { fixture ->
            val engine = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1)
            val second = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1)
            val reached = AtomicInteger()
            val gate = Gate()
            val secondGate = Gate()
            val environment = fixture.environment
            runBlocking {
                supervisorScope {
                    val running =
                        async(Dispatchers.Default) {
                            engine.perform { engine ->
                                gate.hold()
                                engine.getStats()
                            }
                        }
                    val secondRunning = async(Dispatchers.Default) { second.perform { secondGate.hold() } }
                    gate.awaitHeld()
                    secondGate.awaitHeld()
                    val queued =
                        List(3) {
                            async(start = CoroutineStart.UNDISPATCHED) { engine.perform { reached.incrementAndGet() } }
                        }
                    val secondQueued = async(start = CoroutineStart.UNDISPATCHED) { second.perform { reached.incrementAndGet() } }

                    val destroyer = thread { environment.destroy() }
                    // Destroy stops every pool before joining any: the queued calls fail.
                    for (call in queued + secondQueued) assertFailsWith<SzEnvironmentDestroyedException> { call.await() }
                    assertTrue(engine.isShutDown && second.isShutDown)
                    assertFalse(environment.isDestroyed, "native teardown waits for the running calls")
                    gate.open()
                    secondGate.open()
                    assertTrue(running.await().contains("workload"))
                    secondRunning.await()
                    destroyer.join()
                    assertTrue(environment.isDestroyed)

                    assertFailsWith<SzEnvironmentDestroyedException> { engine.perform { reached.incrementAndGet() } }
                    assertFailsWith<SzEnvironmentDestroyedException> { engine.getStats() }
                }
            }
            assertEquals(0, reached.get())

            // An SzAsyncEngine created after destroy is shut down at once.
            val late = SzAsyncEngine(fixture.engine)
            assertTrue(late.isShutDown)
            assertFailsWith<SzEnvironmentDestroyedException> { runBlocking { late.getStats() } }
            late.shutdown()
            engine.close()
        }
    }

    @Test
    fun `test_shutdown explicit, idempotent, from a worker, and on close`() {
        EngineFixture().use { fixture ->
            val engine = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 2)
            assertFalse(engine.isShutDown)
            engine.shutdown()
            engine.shutdown()
            assertTrue(engine.isShutDown)
            val error = assertFailsWith<SzException> { runBlocking { engine.perform { } } }
            assertEquals(SzException::class, error::class)
            assertTrue(error.message.contains("shut down"), error.message)

            // A worker shutting down its own pool detaches itself (and joins the other worker).
            val selfStopping = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 2)
            runBlocking { selfStopping.perform { selfStopping.shutdown() } }
            assertTrue(selfStopping.isShutDown)

            // close() (and so `use`) shuts down and joins the workers.
            val closed = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1)
            val worker = runBlocking { closed.use { it.perform { Thread.currentThread() } } }
            assertTrue(closed.isShutDown)
            assertFalse(worker.isAlive, "close joined the worker")
            closed.close()

            // The environment is untouched by shutting down async engines.
            assertTrue(fixture.engine.getStats().contains("workload"))
            assertFalse(fixture.environment.isDestroyed)
        }
    }

    private companion object {
        const val WAIT_SECONDS = 60L
    }
}
