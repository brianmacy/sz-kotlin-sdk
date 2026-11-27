package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.LogSink
import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.core.SzCoreLogging
import io.github.brianmacy.sz.testsupport.SenzingRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [SzLogging] against the real native `libSzLogging` (no mocks).
 *
 * Logging is process-wide, so each test changes it only while no engine is initialized (the
 * Senzing 4.5.0 usage rule), silences the default output on entry and restores the native
 * defaults (stderr, legacy format, ERROR) on exit.
 *
 * A deterministic native message without an engine: `SzConfigMgr_init` logs
 * `SzConfigMgr_init([<instance name>],[<settings>],[0])` at TRACE on the calling thread (when
 * some sink receives TRACE), before it rejects settings that are not JSON. The instance name is
 * each test's marker. (The SDK rejects an unopenable log file before native, so the native
 * "Failed to create file log sink" ERROR the other SDKs' tests use is out of reach here.)
 */
class SzLoggingTest {
    /** A thread-safe sink of the records a callback receives. */
    private class LogCollector : (SzLogRecord) -> Unit {
        private val storage = Collections.synchronizedList(mutableListOf<SzLogRecord>())

        val records: List<SzLogRecord> get() = synchronized(storage) { storage.toList() }

        override fun invoke(record: SzLogRecord) {
            storage += record
        }

        fun matching(marker: String): List<SzLogRecord> = records.filter { marker in it.message }
    }

    /** Process-wide logging state for one test; [close] restores the native defaults. */
    private class LoggingFixture : AutoCloseable {
        val logging: SzLogging = SzCoreLogging
        val directory: File = Files.createTempDirectory("sz-kotlin-sdk-logging-").toFile()

        /** A regular file: any path beneath it cannot be opened. */
        private val blocker = directory.resolve("blocker").apply { writeText("blocker") }

        init {
            logging.setDefaultOutput(SzLogOutput.Null)
            logging.setDefaultLogLevel(SzLogLevel.ERROR)
        }

        override fun close() {
            logging.setDefaultOutput(SzLogOutput.Stderr)
            logging.setDefaultFormat(SzLogFormat.LEGACY)
            logging.setDefaultLogLevel(SzLogLevel.ERROR)
            directory.deleteRecursively()
        }

        /** A path that cannot be opened, naming [marker]. */
        fun unopenablePath(marker: String): String = blocker.resolve("$marker.log").path

        /**
         * Makes native log a TRACE message naming [marker] on the calling thread, inside an SDK
         * call ([SzEnvironment.getConfigManager]); [during] runs while the environment is active.
         */
        fun emitTrace(
            marker: String,
            during: (SzCoreEnvironment) -> Unit = {},
        ) {
            SzCoreEnvironment(instanceName = marker, settings = "{not json").use { environment ->
                during(environment)
                assertFailsWith<SzException> { environment.getConfigManager() }
            }
        }

        /**
         * Makes native log `Exception: unable to open database file` at CRITICAL: the config
         * manager cannot open a repository in a directory that does not exist. Logged inside
         * an SDK call on the calling thread; [during] runs while the environment is active.
         */
        fun emitCritical(during: (SzCoreEnvironment) -> Unit = {}) {
            val settings = SenzingRuntime.current.settings("sqlite3://na:na@${unopenablePath("db")}/G2C.db")
            SzCoreEnvironment(instanceName = "sz-logging-critical", settings = settings).use { environment ->
                during(environment)
                assertFailsWith<SzDatabaseException> { environment.getConfigManager() }
            }
        }
    }

    private fun parseJson(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `test_levels parse and print their canonical names`() {
        for (level in SzLogLevel.entries) {
            assertEquals(level, SzLogLevel.parse(level.name))
            assertEquals(level, SzLogLevel.parse(" ${level.name.lowercase()}\n"))
        }
        assertEquals(listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "CRITICAL", "OFF"), SzLogLevel.entries.map { it.name })
        assertEquals(SzLogLevel.WARN, SzLogLevel.parse("Warning"))
        assertTrue(SzLogLevel.TRACE < SzLogLevel.DEBUG && SzLogLevel.CRITICAL < SzLogLevel.OFF)
        assertEquals((0..6).toList(), SzLogLevel.entries.map { it.ordinal })
        val error = assertFailsWith<SzBadInputException> { SzLogLevel.parse("LOUD") }
        assertTrue(error.message.contains("LOUD"), error.message)
    }

    @Test
    fun `test_formats parse and print their canonical names`() {
        for (format in SzLogFormat.entries) assertEquals(format, SzLogFormat.parse(format.name.lowercase()))
        assertEquals(listOf("LEGACY", "JSONL", "BARE", "DEFAULT"), SzLogFormat.entries.map { it.name })
        assertEquals(SzLogFormat.JSONL, SzLogFormat.parse(" json "))
        assertEquals((0..3).toList(), SzLogFormat.entries.map { it.ordinal })
        assertEquals(listOf("STDERR", "STDOUT", "FILE", "NULL"), SzLogOutputType.entries.map { it.name })
        val error = assertFailsWith<SzBadInputException> { SzLogFormat.parse("XML") }
        assertTrue(error.message.contains("XML"), error.message)
    }

    @Test
    fun `test_valueTypes options, outputs and records`() {
        val options = SzLogCallbackOptions(SzLogLevel.WARN)
        assertEquals(SzLogFormat.BARE, options.format)
        assertEquals(SzLogCallbackOptions(SzLogLevel.WARN, SzLogFormat.JSONL), options.copy(format = SzLogFormat.JSONL))

        assertEquals(SzLogOutputType.STDERR, SzLogOutput.Stderr.outputType)
        assertEquals(null, SzLogOutput.Stderr.path)
        assertEquals(SzLogOutputType.STDOUT, SzLogOutput.Stdout.outputType)
        assertEquals(null, SzLogOutput.Stdout.path)
        assertEquals(SzLogOutputType.NULL, SzLogOutput.Null.outputType)
        assertEquals(null, SzLogOutput.Null.path)
        assertEquals(SzLogOutputType.FILE, SzLogOutput.File("/x/y.log").outputType)
        assertEquals("/x/y.log", SzLogOutput.File("/x/y.log").path)

        val record = SzLogRecord(SzLogLevel.INFO, "a:b:82eb15f5", "hello")
        assertEquals("a:b", record.module)
        assertEquals("82eb15f5", record.threadHash)
        assertEquals(SzLogRecord(SzLogLevel.INFO, "a:b:82eb15f5", "hello"), record)
        val bare = SzLogRecord(SzLogLevel.INFO, "solo", "")
        assertEquals("solo", bare.module)
        assertEquals("", bare.threadHash)
    }

    @Test
    fun `test_getLogging works without and after an initialized environment`() {
        LoggingFixture().use {
            val environment = SzCoreEnvironment()
            assertSame(SzCoreLogging, environment.getLogging())
            environment.destroy()
            val logging = environment.getLogging()
            logging.setDefaultLogLevel(SzLogLevel.WARN)
            assertEquals(SzLogLevel.WARN, logging.getEffectiveLogLevel())
        }
    }

    @Test
    fun `test_registerCallback receives native messages in the bare format`() {
        LoggingFixture().use { fixture ->
            val collector = LogCollector()
            val registration = fixture.logging.registerCallback(SzLogLevel.TRACE, collector)
            fixture.emitTrace("register-bare")
            registration.unregister()

            val records = collector.matching("register-bare")
            assertEquals(1, records.size, collector.records.toString())
            val record = records.single()
            assertEquals(SzLogLevel.TRACE, record.level)
            assertTrue(record.module.isNotEmpty(), record.rawModule)
            // 4.5.0 appends `:threadHash` to the module; newer runtimes may not.
            assertEquals(listOf(record.module, record.threadHash).filter { it.isNotEmpty() }.joinToString(":"), record.rawModule)
            assertTrue(record.message.startsWith("SzConfigMgr_init([register-bare]"), "bare has no prefix: ${record.message}")
        }
    }

    @Test
    fun `test_interface defaults are JVM default methods for Java callers`() {
        val byLevel = SzLogging::class.java.getMethod("registerCallback", SzLogLevel::class.java, Function1::class.java)
        assertTrue(byLevel.isDefault)
        assertTrue(SzLogRegistration::class.java.getMethod("close").isDefault)
        assertTrue(SzEnvironment::class.java.getMethod("close").isDefault)
        assertFailsWith<ClassNotFoundException> { Class.forName("io.github.brianmacy.sz.SzLogging\$DefaultImpls") }
    }

    @Test
    fun `test_callbackFormats legacy, JSONL and default`() {
        LoggingFixture().use { fixture ->
            val legacy = LogCollector()
            val jsonl = LogCollector()
            val raw = LogCollector()
            val registrations =
                listOf(
                    fixture.logging.registerCallback(SzLogCallbackOptions(SzLogLevel.TRACE, SzLogFormat.LEGACY), legacy),
                    fixture.logging.registerCallback(SzLogCallbackOptions(SzLogLevel.TRACE, SzLogFormat.JSONL), jsonl),
                    fixture.logging.registerCallback(SzLogCallbackOptions(SzLogLevel.TRACE, SzLogFormat.DEFAULT), raw),
                )
            fixture.emitTrace("formats")
            registrations.forEach { it.close() }

            val legacyRecord = legacy.matching("formats").single()
            assertTrue("[${legacyRecord.module}" in legacyRecord.message, legacyRecord.message)
            assertFalse(legacyRecord.message.startsWith("SzConfigMgr_init"), legacyRecord.message)
            val jsonRecord = jsonl.matching("formats").single()
            val json = parseJson(jsonRecord.message)
            assertEquals("trace", json.getValue("severity").jsonPrimitive.content, jsonRecord.message)
            assertTrue("formats" in json.getValue("message").jsonPrimitive.content, jsonRecord.message)
            assertEquals(1, raw.matching("formats").size)
        }
    }

    @Test
    fun `test_minLevel filters lower-severity messages`() {
        LoggingFixture().use { fixture ->
            val debug = LogCollector()
            val trace = LogCollector()
            fixture.logging.registerCallback(SzLogLevel.DEBUG, debug).use {
                fixture.logging.registerCallback(SzLogLevel.TRACE, trace).use {
                    fixture.emitTrace("filtered")
                }
            }
            assertEquals(1, trace.matching("filtered").size)
            assertTrue(debug.matching("filtered").isEmpty())
            assertTrue(debug.records.none { it.level < SzLogLevel.DEBUG }, debug.records.toString())
        }
    }

    @Test
    fun `test_unregister stops delivery, is idempotent and runs on close`() {
        LoggingFixture().use { fixture ->
            val collector = LogCollector()
            val registration = fixture.logging.registerCallback(SzLogLevel.TRACE, collector)
            assertTrue(registration.isRegistered)
            fixture.emitTrace("unregister-1")
            registration.unregister()
            assertFalse(registration.isRegistered)
            registration.unregister()
            registration.leak()
            fixture.emitTrace("unregister-2")
            assertEquals(1, collector.matching("unregister-1").size)
            assertTrue(collector.matching("unregister-2").isEmpty())

            // A second TRACE sink keeps native logging TRACE while the scoped one is closed.
            val released = LogCollector()
            fixture.logging.registerCallback(SzLogLevel.TRACE, collector).use {
                fixture.logging.registerCallback(SzLogLevel.TRACE, released).use { scoped ->
                    fixture.emitTrace("released-1")
                    assertTrue(scoped.isRegistered)
                }
                fixture.emitTrace("released-2")
            }
            assertEquals(1, released.matching("released-1").size)
            assertTrue(released.matching("released-2").isEmpty())
            assertEquals(1, collector.matching("released-2").size)
        }
    }

    @Test
    fun `test_leak keeps a released callback registered`() {
        LoggingFixture().use { fixture ->
            val armed = AtomicBoolean(true)
            val delivered = AtomicInteger()
            // Stays registered for the rest of the process. CRITICAL lowers nothing (the native
            // default is ERROR), and it counts only while this test runs.
            val registration =
                fixture.logging.registerCallback(SzLogLevel.CRITICAL) { record ->
                    if (armed.get() && DATABASE_FAILURE in record.message) delivered.incrementAndGet()
                }
            registration.leak()
            assertTrue(registration.isRegistered)
            registration.unregister()
            assertTrue(registration.isRegistered, "a leaked registration cannot be unregistered")
            registration.close()
            fixture.emitCritical()
            armed.set(false)
            assertEquals(1, delivered.get())
        }
    }

    @Test
    fun `test_insideCallback refuses sink changes, SDK calls, and defers unregistration`() {
        LoggingFixture().use { fixture ->
            val logging = fixture.logging
            val outcomes = Collections.synchronizedList(mutableListOf<String>())
            val handle = AtomicReference<SzLogRegistration?>()
            val environment = AtomicReference<SzCoreEnvironment?>()
            val path = fixture.directory.resolve("x.log").path

            fun attempt(
                name: String,
                body: () -> Unit,
            ) {
                outcomes +=
                    try {
                        body()
                        "$name:ok"
                    } catch (e: SzException) {
                        "$name:${e::class.simpleName}:${e.message.substringBefore(' ')}"
                    }
            }
            val invocations = AtomicInteger()
            // ERROR, the native default: a registration released inside its callback stays
            // registered natively for the process, so a lower level would stay in effect.
            val registration =
                logging.registerCallback(SzLogLevel.ERROR) { record ->
                    if (DATABASE_FAILURE !in record.message) return@registerCallback
                    invocations.incrementAndGet()
                    attempt("register") { logging.registerCallback(SzLogLevel.ERROR) { } }
                    attempt("defaultCallback") { logging.setDefaultCallback(SzLogCallbackOptions(SzLogLevel.ERROR)) { } }
                    attempt("output") { logging.setDefaultOutput(SzLogOutput.File(path)) }
                    attempt("format") { logging.setDefaultFormat(SzLogFormat.JSONL) }
                    attempt("level") { logging.setDefaultLogLevel(SzLogLevel.ERROR) }
                    attempt("effective") { logging.getEffectiveLogLevel() }
                    attempt("sdk") { environment.get()!!.getProduct() }
                    handle.get()!!.unregister()
                }
            handle.set(registration)
            fixture.emitCritical { environment.set(it) }
            assertFalse(registration.isRegistered)
            registration.unregister()
            val later = LogCollector()
            logging.registerCallback(SzLogLevel.ERROR, later).use { fixture.emitCritical() }
            val refused = "${SzConfigurationException::class.simpleName}:Cannot"
            assertEquals(
                listOf(
                    "register:$refused",
                    "defaultCallback:$refused",
                    "output:$refused",
                    "format:$refused",
                    "level:ok",
                    "effective:ok",
                    "sdk:SzException:Re-entrant",
                ),
                outcomes.toList(),
            )
            assertFalse(File(path).exists())
            // The registration that unregistered itself received nothing more.
            assertEquals(1, invocations.get())
            assertEquals(1, later.matching(DATABASE_FAILURE).size)
        }
    }

    @Test
    fun `test_callbackException goes to the thread's uncaught-exception handler`() {
        LoggingFixture().use { fixture ->
            val uncaught = Collections.synchronizedList(mutableListOf<Pair<Thread, Throwable>>())
            val delivered = AtomicInteger()
            fixture.logging
                .registerCallback(SzLogLevel.TRACE) { record ->
                    if ("throwing" in record.message) {
                        delivered.incrementAndGet()
                        throw IllegalStateException("callback failed: ${record.message}")
                    }
                }.use {
                    val emitter =
                        thread(start = false) { fixture.emitTrace("throwing") }.apply {
                            setUncaughtExceptionHandler { thread, error -> uncaught += thread to error }
                        }
                    emitter.start()
                    emitter.join()
                    assertEquals(1, delivered.get())
                    val (thread, error) = uncaught.single()
                    assertSame(emitter, thread)
                    assertTrue(error is IllegalStateException && "throwing" in error.message!!, error.toString())
                }
        }
    }

    @Test
    fun `test_setDefaultCallback replaces the default sink until the output changes`() {
        LoggingFixture().use { fixture ->
            val bare = LogCollector()
            fixture.logging.setDefaultCallback(SzLogCallbackOptions(SzLogLevel.TRACE), bare)
            fixture.emitTrace("default-bare")
            val legacy = LogCollector()
            fixture.logging.setDefaultCallback(SzLogCallbackOptions(SzLogLevel.TRACE, SzLogFormat.LEGACY), legacy)
            fixture.emitTrace("default-legacy")
            fixture.logging.setDefaultOutput(SzLogOutput.Null)
            fixture.emitTrace("default-replaced")

            val bareRecord = bare.matching("default-bare").single()
            assertTrue(bareRecord.message.startsWith("SzConfigMgr_init([default-bare]"), bareRecord.message)
            val legacyRecord = legacy.matching("default-legacy").single()
            assertTrue("[${legacyRecord.module}" in legacyRecord.message, legacyRecord.message)
            assertFalse(legacyRecord.message.startsWith("SzConfigMgr_init"), legacyRecord.message)
            assertTrue(bare.matching("default-legacy").isEmpty())
            assertTrue(legacy.matching("default-replaced").isEmpty())
        }
    }

    @Test
    fun `test_defaultOutputFile writes the format set, and an output change truncates`() {
        LoggingFixture().use { fixture ->
            val logging = fixture.logging
            val file = fixture.directory.resolve("missing/nested/sz.log")
            logging.setDefaultOutput(SzLogOutput.File(file.path))
            assertTrue(file.isFile, "parent directories are created")
            logging.setDefaultFormat(SzLogFormat.JSONL)
            logging.setDefaultLogLevel(SzLogLevel.TRACE)
            fixture.emitTrace("file-jsonl")
            logging.setDefaultOutput(SzLogOutput.Null) // flushes and closes

            val line = file.readLines().first { "file-jsonl" in it }
            assertEquals("trace", parseJson(line).getValue("severity").jsonPrimitive.content, line)

            // Setting the output again truncates the file. (Senzing 4.5.0 also resets the format
            // to legacy; newer runtimes keep it, so set it.)
            logging.setDefaultOutput(SzLogOutput.File(file.path))
            logging.setDefaultFormat(SzLogFormat.LEGACY)
            fixture.emitTrace("file-legacy")
            logging.setDefaultOutput(SzLogOutput.Stdout)
            logging.setDefaultOutput(SzLogOutput.Stderr)
            logging.setDefaultOutput(SzLogOutput.Null)
            val text = file.readText()
            assertFalse("file-jsonl" in text, text)
            val legacy = text.lines().first { "file-legacy" in it }
            assertFalse(legacy.startsWith("{"), legacy)
        }
    }

    @Test
    fun `test_defaultOutputFile rejects unusable paths before native`() {
        LoggingFixture().use { fixture ->
            val logging = fixture.logging
            assertFailsWith<SzBadInputException> { logging.setDefaultOutput(SzLogOutput.File("")) }
            assertFailsWith<SzBadInputException> { logging.setDefaultOutput(SzLogOutput.File("a\u0000b.log")) }
            val error = assertFailsWith<SzBadInputException> { logging.setDefaultOutput(SzLogOutput.File(fixture.unopenablePath("bad"))) }
            assertTrue(error.message.contains("bad.log"), error.message)
            assertFailsWith<SzBadInputException> { logging.setDefaultOutput(SzLogOutput.File(fixture.directory.path)) }
        }
    }

    @Test
    fun `test_effectiveLogLevel follows the default level and registered callbacks`() {
        LoggingFixture().use { fixture ->
            val logging = fixture.logging
            logging.setDefaultLogLevel(SzLogLevel.WARN)
            assertEquals(SzLogLevel.WARN, logging.getEffectiveLogLevel())
            logging.setDefaultLogLevel(SzLogLevel.OFF)
            // At most ERROR: test_leak's and test_insideCallback's callbacks stay registered
            // natively, at CRITICAL and ERROR.
            assertTrue(logging.getEffectiveLogLevel() >= SzLogLevel.ERROR, logging.getEffectiveLogLevel().name)

            logging.registerCallback(SzLogLevel.DEBUG) { }.use {
                assertEquals(SzLogLevel.DEBUG, logging.getEffectiveLogLevel(), "lowered to the callback's level")
                logging.setDefaultLogLevel(SzLogLevel.ERROR)
                assertEquals(SzLogLevel.DEBUG, logging.getEffectiveLogLevel(), "kept for the callback")
            }
            logging.setDefaultLogLevel(SzLogLevel.ERROR)
            assertEquals(SzLogLevel.ERROR, logging.getEffectiveLogLevel())
        }
    }

    @Test
    fun `test_verboseLogging delivers engine trace messages to a callback`() {
        LoggingFixture().use { fixture ->
            val collector = LogCollector()
            fixture.logging.registerCallback(SzLogLevel.TRACE, collector).use {
                SzCoreEnvironment(instanceName = "logging-verbose", verboseLogging = true).use { environment ->
                    // No repository in the settings: initialization fails, after logging.
                    assertFailsWith<SzException> { environment.getEngine() }
                }
            }
            val trace = collector.records.filter { it.level == SzLogLevel.TRACE && "Sz_init(" in it.message }
            assertEquals(1, trace.size, collector.records.toString())
            assertTrue("logging-verbose" in trace.single().message, trace.single().message)
        }
    }

    @Test
    fun `test_nativeBoundary drops invalid levels and inactive deliveries`() {
        val collector = LogCollector()
        val sink = LogSink(collector)
        sink.deliver(SzLogLevel.OFF.ordinal, "m:h", "boundary")
        sink.deliver(99, "m:h", "boundary")
        sink.deliver(-1, "m:h", "boundary")
        sink.deliver(SzLogLevel.INFO.ordinal, "m:h", "boundary")
        sink.active.set(false)
        sink.deliver(SzLogLevel.INFO.ordinal, "m:h", "boundary")
        assertEquals(listOf(SzLogRecord(SzLogLevel.INFO, "m:h", "boundary")), collector.records)
    }

    private companion object {
        /** The CRITICAL message of [LoggingFixture.emitCritical]. */
        const val DATABASE_FAILURE = "unable to open database file"
    }
}
