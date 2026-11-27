package io.github.brianmacy.sz.core

import io.github.brianmacy.sz.SzBadInputException
import io.github.brianmacy.sz.SzConfigurationException
import io.github.brianmacy.sz.SzLogCallback
import io.github.brianmacy.sz.SzLogCallbackOptions
import io.github.brianmacy.sz.SzLogLevel
import io.github.brianmacy.sz.SzLogOutput
import io.github.brianmacy.sz.SzLogRecord
import io.github.brianmacy.sz.SzLogRegistration
import io.github.brianmacy.sz.SzLogging
import io.github.brianmacy.sz.SzLogFormat
import io.github.brianmacy.sz.internal.Native
import io.github.brianmacy.sz.internal.NativeLogSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The [SzLogging] over the native `libSzLogging` API (through sz-rust-sdk), mirroring the C#
 * `SzCoreLogging`. One instance per process, also returned by
 * [io.github.brianmacy.sz.SzEnvironment.getLogging].
 */
public object SzCoreLogging : SzLogging {
    override fun registerCallback(
        options: SzLogCallbackOptions,
        callback: SzLogCallback,
    ): SzLogRegistration {
        LogCallbackDepth.refuseInside("registering a log callback")
        val sink = LogSink(callback)
        val handle = Native.logRegister(options.minLevel.ordinal, options.format.ordinal, sink)
        return CoreLogRegistration(handle, sink)
    }

    override fun setDefaultCallback(
        options: SzLogCallbackOptions,
        callback: SzLogCallback,
    ) {
        LogCallbackDepth.refuseInside("setDefaultCallback")
        Native.logSetDefaultCallback(options.minLevel.ordinal, options.format.ordinal, LogSink(callback))
    }

    override fun setDefaultOutput(output: SzLogOutput) {
        LogCallbackDepth.refuseInside("setDefaultOutput")
        val path = output.path?.let(::writableLogFile)
        Native.logSetDefaultOutput(output.outputType.ordinal, path)
    }

    override fun setDefaultFormat(format: SzLogFormat) {
        LogCallbackDepth.refuseInside("setDefaultFormat")
        Native.logSetDefaultFormat(format.ordinal)
    }

    override fun setDefaultLogLevel(level: SzLogLevel) {
        Native.logSetDefaultLevel(level.ordinal)
    }

    override fun getEffectiveLogLevel(): SzLogLevel = SzLogLevel.entries[Native.logEffectiveLevel()]

    /**
     * Validates a log file path: creates its parent directories and opens it for appending,
     * so an unusable path is reported instead of native silently dropping output.
     */
    internal fun writableLogFile(path: String): String {
        if (path.isEmpty() || '\u0000' in path) {
            throw SzBadInputException("Log file path must be non-empty and contain no NUL: \"$path\"")
        }
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        try {
            FileOutputStream(file, true).close()
        } catch (e: IOException) {
            throw SzBadInputException("Cannot open log file \"$path\" for appending: ${e.message}")
        }
        return path
    }
}

/** One registration's callback, as the JNI library delivers to it. */
internal class LogSink(
    private val callback: SzLogCallback,
) : NativeLogSink {
    val active = AtomicBoolean(true)

    override fun deliver(
        level: Int,
        rawModule: String,
        message: String,
    ) {
        // Out of range, or OFF (a filter value, never a message level): drop.
        val logLevel = SzLogLevel.entries.getOrNull(level)
        if (!active.get() || logLevel == null || logLevel == SzLogLevel.OFF) return
        LogCallbackDepth.during {
            try {
                callback(SzLogRecord(logLevel, rawModule, message))
            } catch (e: Throwable) {
                // A callback has no caller to report to: hand its failure to the thread's
                // uncaught-exception handler, as for any thread's unhandled exception.
                val thread = Thread.currentThread()
                thread.uncaughtExceptionHandler?.uncaughtException(thread, e)
            }
        }
    }
}

/** A native log registration. */
internal class CoreLogRegistration(
    handle: Long,
    private val sink: LogSink,
) : SzLogRegistration {
    /** The native handle, or 0 once released. */
    private val handle = AtomicLong(handle)

    override val isRegistered: Boolean get() = sink.active.get()

    override fun unregister() {
        val taken = handle.getAndSet(0L)
        if (taken == 0L) return
        sink.active.set(false)
        if (LogCallbackDepth.isInside) {
            // Native cannot unregister from inside a callback: delivery is already off, and
            // the native registration stays for the process.
            Native.logLeak(taken)
            return
        }
        Native.logUnregister(taken)
    }

    override fun leak() {
        val taken = handle.getAndSet(0L)
        if (taken != 0L) Native.logLeak(taken)
    }
}

/** How deep the current thread is inside SDK log callbacks. */
internal object LogCallbackDepth {
    private val depth = ThreadLocal.withInitial { 0 }

    val isInside: Boolean get() = depth.get() > 0

    fun during(body: () -> Unit) {
        depth.set(depth.get() + 1)
        try {
            body()
        } finally {
            depth.set(depth.get() - 1)
        }
    }

    fun refuseInside(operation: String) {
        if (isInside) {
            throw SzConfigurationException("Cannot change log sinks from inside a log callback ($operation).")
        }
    }
}
