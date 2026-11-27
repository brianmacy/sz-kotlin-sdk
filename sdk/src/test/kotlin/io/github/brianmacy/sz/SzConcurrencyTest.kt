package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.core.SzCoreLogging
import io.github.brianmacy.sz.testsupport.TestRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SzConcurrencyTest {
    /** Runs `body(index)` on [count] threads at once and waits for all of them. */
    private fun onThreads(
        count: Int,
        body: (Int) -> Unit,
    ) {
        List(count) { index -> thread { body(index) } }.forEach(Thread::join)
    }

    /** Waits up to 60 seconds for [condition]. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met within 60 seconds" }
            Thread.sleep(5)
        }
    }

    /** Runs [body] with the default log output silenced, restoring the engine's defaults after. */
    private fun withQuietLogging(body: () -> Unit) {
        SzCoreLogging.setDefaultOutput(SzLogOutput.Null)
        SzCoreLogging.setDefaultLogLevel(SzLogLevel.ERROR)
        try {
            body()
        } finally {
            SzCoreLogging.setDefaultOutput(SzLogOutput.Stderr)
            SzCoreLogging.setDefaultFormat(SzLogFormat.LEGACY)
            SzCoreLogging.setDefaultLogLevel(SzLogLevel.ERROR)
        }
    }

    @Test
    fun `test_syncEngine many threads add, get and delete distinct and shared records`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            val failures = Collections.synchronizedList(mutableListOf<String>())
            onThreads(8) { worker ->
                for (index in 0 until 25) {
                    val own = "T$worker-$index"
                    val shared = "SHARED-${index % 5}"
                    try {
                        engine.addRecord("CUSTOMERS", own, person(own))
                        engine.addRecord("CUSTOMERS", shared, person("Shared $worker"))
                        engine.getRecord("CUSTOMERS", own)
                        try {
                            engine.getEntity("CUSTOMERS", shared)
                        } catch (_: SzNotFoundException) {
                            // Another thread is re-resolving this shared record's entity: the
                            // engine reports SENZ0037 for the moment.
                        }
                        engine.deleteRecord("CUSTOMERS", own)
                    } catch (e: Exception) {
                        failures.add("$own: $e")
                    }
                }
            }
            assertTrue(failures.isEmpty(), failures.toString())
            for (index in 0 until 5) {
                assertTrue(engine.getRecord("CUSTOMERS", "SHARED-$index").contains("Shared"))
            }
            assertFailsWith<SzNotFoundException> { engine.getRecord("CUSTOMERS", "T0-0") }
        }
    }

    @Test
    fun `test_destroyRace calls racing destroy succeed or fail with SzEnvironmentDestroyedException`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            val completed = AtomicInteger()
            val outcomes = Collections.synchronizedList(mutableListOf<Throwable>())
            val started = CountDownLatch(8)
            val workers =
                List(8) { worker ->
                    thread {
                        started.countDown()
                        var index = 0
                        while (true) {
                            try {
                                // A few writes, then reads: the evaluation license caps the
                                // record count.
                                val id = "R$worker-${minOf(index, 9)}"
                                if (index < 10) engine.addRecord("CUSTOMERS", id, person(id))
                                engine.getRecord("CUSTOMERS", id)
                                completed.incrementAndGet()
                                index += 1
                            } catch (e: Throwable) {
                                outcomes.add(e)
                                return@thread
                            }
                        }
                    }
                }
            started.await()
            eventually { completed.get() >= 40 }
            fixture.environment.destroy()
            workers.forEach(Thread::join)
            assertEquals(8, outcomes.size, outcomes.toString())
            assertTrue(outcomes.all { it is SzEnvironmentDestroyedException }, outcomes.toString())
        }
    }

    @Test
    fun `test_getActiveInstance waits for a destroy in progress, then returns null`() {
        EngineFixture().use { fixture ->
            val environment = fixture.environment
            assertSame(environment, SzCoreEnvironment.getActiveInstance())
            val engine = SzAsyncEngine(fixture.engine, maxConcurrentCalls = 1)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            runBlocking {
                val running =
                    async(Dispatchers.Default) {
                        engine.perform {
                            held.countDown()
                            release.await()
                        }
                    }
                held.await()
                // destroy() joins the held worker, so it stays in progress.
                thread { environment.destroy() }
                eventually { engine.isShutDown }
                val answered = CountDownLatch(1)
                val result = AtomicReference<SzCoreEnvironment?>(environment)
                thread {
                    result.set(SzCoreEnvironment.getActiveInstance())
                    answered.countDown()
                }
                assertFalse(answered.await(200, TimeUnit.MILLISECONDS), "waits while destroy() runs")
                release.countDown()
                running.await()
                assertTrue(answered.await(60, TimeUnit.SECONDS))
                assertNull(result.get())
                assertTrue(environment.isDestroyed)
            }
        }
    }

    @Test
    fun `test_reentrancy an SDK call from a log callback during an SDK call fails instead of deadlocking`() =
        withQuietLogging {
            val repository = TestRepository()
            try {
                val environment =
                    SzCoreEnvironment(instanceName = "reentrancy", settings = repository.settings, verboseLogging = true)
                val engine = environment.getEngine()
                val outcome = AtomicReference<String?>(null)
                val destroyed = CountDownLatch(1)
                val registration =
                    SzCoreLogging.registerCallback(SzLogLevel.TRACE) { record ->
                        if (record.message.startsWith("Sz_stats(") && outcome.compareAndSet(null, "pending")) {
                            // destroy() now waits for this call to finish.
                            thread {
                                environment.destroy()
                                destroyed.countDown()
                            }
                            Thread.sleep(200)
                            outcome.set(
                                try {
                                    engine.countRedoRecords()
                                    "reentered"
                                } catch (e: Exception) {
                                    e.toString()
                                },
                            )
                        }
                    }
                environment.use {
                    registration.use {
                        engine.getStats()
                        assertTrue(destroyed.await(60, TimeUnit.SECONDS), "destroy() finished after the call")
                    }
                    assertTrue(environment.isDestroyed)
                }
                val result = assertNotNull(outcome.get())
                assertTrue(result.contains("Re-entrant Senzing call"), result)
            } finally {
                repository.remove()
            }
        }

    @Test
    fun `test_destroyInsideCall destroy from a log callback runs when the SDK call returns`() =
        withQuietLogging {
            val repository = TestRepository()
            try {
                val environment =
                    SzCoreEnvironment(instanceName = "destroy-inside", settings = repository.settings, verboseLogging = true)
                val engine = environment.getEngine()
                val seenDuringCall = AtomicReference<Boolean?>(null)
                val registration =
                    SzCoreLogging.registerCallback(SzLogLevel.TRACE) { record ->
                        if (record.message.startsWith("Sz_stats(") && seenDuringCall.get() == null) {
                            environment.destroy() // returns at once: waiting here would deadlock
                            seenDuringCall.set(environment.isDestroyed)
                        }
                    }
                environment.use {
                    registration.use {
                        assertTrue(engine.getStats().contains("workload"), "the running call completes")
                    }
                    assertEquals(false, seenDuringCall.get(), "not destroyed while the call ran")
                    assertTrue(environment.isDestroyed, "destroyed when the call returned")
                    assertNull(SzCoreEnvironment.getActiveInstance())
                    assertFailsWith<SzEnvironmentDestroyedException> { engine.getStats() }
                }
            } finally {
                repository.remove()
            }
        }
}
