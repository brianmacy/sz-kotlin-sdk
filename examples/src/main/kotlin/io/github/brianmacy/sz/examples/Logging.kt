// Logging: capture the engine's native log messages with SzLogging.
//
// The lifecycle the logging usage rule asks for:
//   1. getLogging() and register a callback BEFORE the engine is initialized, so initialization
//      messages are captured;
//   2. silence the default output (the callback is the only sink);
//   3. use the engine; setDefaultLogLevel is safe at any time;
//   4. destroy the environment, THEN unregister and restore the default output.
//
// The callback only updates counters under a lock: callbacks must be fast and must never call
// back into the engine.
//
// Run: ./gradlew -q :examples:runLogging

package io.github.brianmacy.sz.examples

import io.github.brianmacy.sz.SzLogLevel
import io.github.brianmacy.sz.SzLogOutput
import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.testsupport.ExampleFailure
import io.github.brianmacy.sz.testsupport.ExampleRepository
import kotlin.system.exitProcess

/** What the callback collects: counts per level and the first few messages. */
private class LogStats {
    val byLevel = sortedMapOf<SzLogLevel, Int>()
    val firstMessages = mutableListOf<String>()
}

private fun run() {
    val repository = ExampleRepository()
    try {
        val stats = LogStats()
        SzCoreEnvironment(
            instanceName = "example_logging",
            settings = repository.settings,
            verboseLogging = true,
        ).use { environment ->
            // (1) + (2): before the first getEngine().
            val logging = environment.getLogging()
            val registration =
                logging.registerCallback(SzLogLevel.TRACE) { record ->
                    synchronized(stats) {
                        stats.byLevel.merge(record.level, 1, Int::plus)
                        if (stats.firstMessages.size < 3) {
                            stats.firstMessages += "[${record.level}] ${record.module}: ${record.message.take(100)}"
                        }
                    }
                }
            registration.use {
                logging.setDefaultOutput(SzLogOutput.Null)
                println("Effective log level with a TRACE callback: ${logging.getEffectiveLogLevel()}")

                // (3): initialization and engine calls reach the callback.
                val engine = environment.getEngine()
                engine.addRecord("CUSTOMERS", "LOG1", """{"NAME_FULL":"Robert Smith","PHONE_NUMBER":"702-919-1300"}""")
                logging.setDefaultLogLevel(SzLogLevel.WARN)
                engine.addRecord("CUSTOMERS", "LOG2", """{"NAME_FULL":"Bob Smith","PHONE_NUMBER":"702-919-1300"}""")

                // (4): nothing else is logging after destroy(), so sinks may change.
                environment.destroy()
            }
            logging.setDefaultOutput(SzLogOutput.Stderr)
            logging.setDefaultLogLevel(SzLogLevel.ERROR)
        }

        synchronized(stats) {
            val total = stats.byLevel.values.sum()
            if (total == 0) throw ExampleFailure("the callback received no log messages")
            println("Captured $total messages:")
            for ((level, count) in stats.byLevel) println("  $level: $count")
            println("First messages:")
            stats.firstMessages.forEach { println("  $it") }
        }
    } finally {
        repository.remove()
    }
}

fun main() {
    try {
        run()
    } catch (e: Exception) {
        System.err.println("example_logging failed: $e")
        exitProcess(1)
    }
}
