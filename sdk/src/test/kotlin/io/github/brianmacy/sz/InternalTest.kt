package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEngine
import io.github.brianmacy.sz.internal.ConfigComment
import io.github.brianmacy.sz.internal.EngineJob
import io.github.brianmacy.sz.internal.EngineWorkerPool
import io.github.brianmacy.sz.internal.EnvironmentState
import io.github.brianmacy.sz.internal.Native
import io.github.brianmacy.sz.internal.SzJson
import io.github.brianmacy.sz.internal.orNone
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzSearchResponse
import io.github.brianmacy.sz.models.SzWhyResponse
import io.github.brianmacy.sz.models.SzWithInfoResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InternalTest {
    /** A real deserializer whose input check rejects everything with IllegalArgumentException. */
    private object Rejecting : DeserializationStrategy<Int> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Rejecting", PrimitiveKind.INT)

        override fun deserialize(decoder: Decoder): Int = throw IllegalArgumentException("rejected")
    }

    private fun definition(codes: List<String>): String =
        codes.joinToString(",", """{"G2_CONFIG":{"CFG_DSRC":[""", "]}}") { """{"DSRC_ID":1,"DSRC_CODE":"$it"}""" }

    /** Waits up to 60 seconds for [condition]. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 60 seconds" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `test_config comments follow the C# rules`() {
        assertEquals(
            "Data Sources: CUSTOMERS, WATCHLIST",
            ConfigComment.make(definition(listOf("TEST", "SEARCH", "CUSTOMERS", "WATCHLIST"))),
        )
        assertEquals("Data Sources: [ NONE ]", ConfigComment.make(definition(emptyList())))
        assertEquals("Data Sources: [ ONLY DEFAULT ]", ConfigComment.make(definition(listOf("SEARCH", "TEST"))))
        assertEquals("Data Sources: [ SOME DEFAULT (TEST) ]", ConfigComment.make(definition(listOf("TEST"))))
        // An entry without a code is skipped.
        assertEquals(
            "Data Sources: [ NONE ]",
            ConfigComment.make("""{"G2_CONFIG":{"CFG_DSRC":[{"DSRC_ID":1},{"DSRC_ID":2,"DSRC_CODE":null}]}}"""),
        )
        // A code that is not a string is not a configuration.
        assertEquals("", ConfigComment.make("""{"G2_CONFIG":{"CFG_DSRC":[{"DSRC_CODE":{}}]}}"""))
        assertEquals("", ConfigComment.make("not json"))
        assertEquals("", ConfigComment.make("""{"OTHER":{}}"""))
        assertEquals("", ConfigComment.make("[]"))
        assertEquals("", ConfigComment.make("""{"G2_CONFIG":{}}"""))
    }

    @Test
    fun `test_JSON documents for the native parameters`() {
        assertEquals("[1,3]", SzCoreEngine.idsJson(setOf(3L, 1L)))
        assertEquals("[]", SzCoreEngine.idsJson(emptySet()))
        assertEquals(
            """[["A","1"],["B","2"]]""",
            SzCoreEngine.keysJson(setOf(SzRecordKey.of("B", "2"), SzRecordKey.of("A", "1"))),
        )
        assertEquals("""["C","W"]""", SzCoreEngine.stringsJson(setOf("W", "C")))
        assertEquals("""["X\"Y"]""", SzCoreEngine.stringsJson(setOf("X\"Y")))
    }

    @Test
    fun `test_JSON conversion errors are SzJsonException`() {
        val missing = assertFailsWith<SzJsonException> { SzJson.decode(SzEntityResponse.serializer(), "{}") }
        assertTrue(missing.message!!.startsWith("Cannot decode"), missing.message)
        assertFailsWith<SzJsonException> { SzJson.decode(SzEntityResponse.serializer(), "not json") }
        val rejected = assertFailsWith<SzJsonException> { SzJson.decode(Rejecting, "1") }
        assertIs<IllegalArgumentException>(rejected.cause)
        assertEquals("rejected", rejected.cause?.message)
        val encode = assertFailsWith<SzJsonException> { SzJson.encode(Double.serializer(), Double.NaN) }
        assertTrue(encode.message!!.startsWith("Cannot encode"), encode.message)
        assertTrue(SzJson.decode(SzWithInfoResponse.serializer(), null).affectedEntities.isEmpty())
        assertTrue(SzJson.format.configuration.ignoreUnknownKeys)
        assertFalse(SzJson.format.configuration.explicitNulls)
    }

    @Test
    fun `test_models tolerate absent optional sections`() {
        assertTrue(SzJson.decode(SzSearchResponse.serializer(), "{}").resolvedEntities.isEmpty())
        assertTrue(SzJson.decode(SzSearchResponse.serializer(), """{"RESOLVED_ENTITIES":null}""").resolvedEntities.isEmpty())
        val why = SzJson.decode(SzWhyResponse.serializer(), "{}")
        assertTrue(why.whyResults.isEmpty() && why.entities.isEmpty())
        val info = SzJson.decode(SzWithInfoResponse.serializer(), """{"UNKNOWN_FIELD":1}""")
        assertNull(info.dataSourceCode)
        assertTrue(info.affectedEntities.isEmpty())
    }

    @Test
    fun `test_SzRecordKey text, JSON and ordering`() {
        assertEquals("CUSTOMERS:1001", SzRecordKey.of("CUSTOMERS", "1001").toString())
        val key = SzJson.decode(SzRecordKey.serializer(), """{"DATA_SOURCE":"A","RECORD_ID":"1"}""")
        assertEquals(SzRecordKey("A", "1"), key)
        assertEquals(SzRecordKey.of("A", "1"), key)
        assertEquals("""{"DATA_SOURCE":"A","RECORD_ID":"1"}""", SzJson.encode(SzRecordKey.serializer(), key))
        val sorted = listOf(SzRecordKey("B", "1"), SzRecordKey("A", "2"), SzRecordKey("A", "10")).sorted()
        assertEquals(listOf(SzRecordKey("A", "10"), SzRecordKey("A", "2"), SzRecordKey("B", "1")), sorted)
        assertEquals(0, SzRecordKey("A", "1").compareTo(SzRecordKey("A", "1")))
    }

    @Test
    fun `test_Native raise throws the mapped exception`() {
        val coded = assertFailsWith<SzNotFoundException> { Native.raise("SzNotFoundError", true, 33, "gone") }
        assertEquals(33L, coded.errorCode)
        assertEquals("gone", coded.message)
        val uncoded = assertFailsWith<SzException> { Native.raise("SzError", false, 99, "plain") }
        assertNull(uncoded.errorCode)
        assertFailsWith<SzEnvironmentDestroyedException> { Native.raise("SzEnvironmentDestroyedError", false, 0, "d") }
        assertNull("".orNone())
        assertEquals("x", "x".orNone())
    }

    @Test
    fun `test_worker pool runs jobs in order and fails queued jobs on stop`() {
        val pool = EngineWorkerPool(1, "internal-test")
        val ran = Collections.synchronizedList(mutableListOf<Int>())
        val failed = Collections.synchronizedList(mutableListOf<Throwable>())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.submit(
            EngineJob({
                started.countDown()
                release.await()
                ran.add(0)
            }, failed::add),
        )
        assertTrue(started.await(60, TimeUnit.SECONDS))
        (1..3).forEach { index -> pool.submit(EngineJob({ ran.add(index) }, failed::add)) }
        assertFalse(pool.isShutDown)

        val stopError = IllegalStateException("stopped")
        pool.stop(stopError)
        pool.stop(IllegalStateException("again")) // idempotent
        assertTrue(pool.isShutDown)
        assertEquals(listOf<Throwable>(stopError, stopError, stopError), failed.toList())

        // Submitting after stop fails the job at once with the stop error.
        pool.submit(EngineJob({ ran.add(9) }, failed::add))
        assertSame(stopError, failed.last())

        release.countDown()
        pool.join()
        assertEquals(listOf(0), ran.toList(), "the running job finished; the queued ones never ran")
    }

    @Test
    fun `test_worker pool runs submitted jobs and joins from a worker`() {
        val pool = EngineWorkerPool(2, "internal-test")
        val count = AtomicInteger()
        val done = CountDownLatch(10)
        repeat(10) {
            pool.submit(
                EngineJob({
                    count.incrementAndGet()
                    done.countDown()
                }, { throw AssertionError(it) }),
            )
        }
        assertTrue(done.await(60, TimeUnit.SECONDS))
        assertEquals(10, count.get())
        // join() from a worker skips that worker instead of waiting on itself.
        val joined = CountDownLatch(1)
        pool.submit(
            EngineJob({
                pool.stop(IllegalStateException("stop"))
                pool.join()
                joined.countDown()
            }, { throw AssertionError(it) }),
        )
        assertTrue(joined.await(60, TimeUnit.SECONDS))
        pool.shutdown(IllegalStateException("shutdown"))
        assertTrue(pool.isShutDown)
    }

    @Test
    fun `test_a job runs or fails at most once`() {
        val events = mutableListOf<String>()
        val job = EngineJob({ events.add("run") }, { events.add("fail ${it.message}") })
        job.run()
        job.run()
        job.fail(IllegalStateException("late"))
        assertEquals(listOf("run"), events)

        val abandoned = mutableListOf<String>()
        val cancelled = EngineJob({ abandoned.add("run") }, { abandoned.add("fail ${it.message}") })
        cancelled.fail(IllegalStateException("first"))
        cancelled.fail(IllegalStateException("second"))
        cancelled.run()
        assertEquals(listOf("fail first"), abandoned)
    }

    @Test
    fun `test_environment state runs calls until destroyed`() {
        val state = EnvironmentState(null)
        assertNull(state.configId)
        state.configId = 7
        assertEquals(7L, state.configId)
        assertEquals(42, state.execute { 42 })
        assertFalse(state.isDestroyed)
        assertFalse(state.isDestroyBegun)

        val reentrant = assertFailsWith<SzException> { state.execute { state.execute { 1 } } }
        assertTrue(reentrant.message.startsWith("Re-entrant Senzing call"), reentrant.message)
        // The failed inner call leaves the thread free for the next call.
        assertEquals(1, state.execute { 1 })

        val teardowns = AtomicInteger()
        state.destroy { teardowns.incrementAndGet() }
        state.destroy { teardowns.incrementAndGet() }
        assertEquals(1, teardowns.get())
        assertTrue(state.isDestroyed)
        assertTrue(state.isDestroyBegun)
        state.waitForDestroy()
        assertFailsWith<SzEnvironmentDestroyedException> { state.execute { 1 } }
    }

    @Test
    fun `test_environment state defers a destroy from inside a call`() {
        val state = EnvironmentState(5)
        val destroyedDuringCall =
            state.execute {
                state.destroy {}
                state.isDestroyBegun
            }
        assertFalse(destroyedDuringCall, "the teardown waits for the call to return")
        assertTrue(state.isDestroyed)
        assertTrue(state.isDestroyBegun)
    }

    /** Registers a pool, shuts it down and drops it; returns a weak reference to it. */
    private fun registerUnreachablePool(state: EnvironmentState): WeakReference<EngineWorkerPool> {
        val pool = EngineWorkerPool(1, "internal-test-dropped")
        state.register(pool)
        pool.shutdown(IllegalStateException("dropped"))
        return WeakReference(pool)
    }

    @Test
    fun `test_environment state drops pools that were garbage collected`() {
        val state = EnvironmentState(null)
        val collected = registerUnreachablePool(state)
        eventually {
            System.gc()
            collected.get() == null
        }
        // Registering prunes the collected pool; destroy stops the live one.
        val live = EngineWorkerPool(1, "internal-test-live")
        state.register(live)
        state.destroy {}
        assertTrue(live.isShutDown)
    }

    @Test
    fun `test_environment state stops registered pools before teardown`() {
        val state = EnvironmentState(null)
        val pool = EngineWorkerPool(1, "internal-test")
        state.register(pool)
        state.register(EngineWorkerPool(1, "internal-test-second"))
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        pool.submit(
            EngineJob({
                started.countDown()
                release.await()
            }, failure::set),
        )
        pool.submit(EngineJob({}, failure::set))
        assertTrue(started.await(60, TimeUnit.SECONDS))

        val destroyer = thread { state.destroy {} }
        eventually { failure.get() != null }
        assertTrue(pool.isShutDown)
        assertIs<SzEnvironmentDestroyedException>(failure.get(), "the queued job was abandoned")
        assertFalse(state.isDestroyed, "teardown waits for the running job")
        release.countDown()
        destroyer.join()
        assertTrue(state.isDestroyed)

        // A pool registered after destroy is shut down at once.
        val late = EngineWorkerPool(1, "internal-test-late")
        state.register(late)
        assertTrue(late.isShutDown)
    }
}
