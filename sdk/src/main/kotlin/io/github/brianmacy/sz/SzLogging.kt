package io.github.brianmacy.sz

/** The severity of a Senzing log message, in increasing order (native `SzLogLevel`). */
public enum class SzLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL,

    /** A filter value that disables logging; never the level of a message. */
    OFF,
    ;

    public companion object {
        /**
         * Parses a level name, ignoring case and surrounding blanks (`WARNING` means [WARN]).
         *
         * @throws SzBadInputException for an unknown name.
         */
        public fun parse(text: String): SzLogLevel {
            val name = text.trim().uppercase().let { if (it == "WARNING") "WARN" else it }
            return entries.firstOrNull { it.name == name }
                ?: throw SzBadInputException("Unknown log level: \"$text\"")
        }
    }
}

/** The layout of a Senzing log message (native `SzLogFormat`). */
public enum class SzLogFormat {
    /** The classic Senzing layout. */
    LEGACY,

    /** One JSON object per message. */
    JSONL,

    /** The message text only. */
    BARE,

    /** The engine's default layout. */
    DEFAULT,
    ;

    public companion object {
        /**
         * Parses a format name, ignoring case and surrounding blanks (`JSON` means [JSONL]).
         *
         * @throws SzBadInputException for an unknown name.
         */
        public fun parse(text: String): SzLogFormat {
            val name = text.trim().uppercase().let { if (it == "JSON") "JSONL" else it }
            return entries.firstOrNull { it.name == name }
                ?: throw SzBadInputException("Unknown log format: \"$text\"")
        }
    }
}

/** The kind of a default log output (native `SzLogOutputType`). */
public enum class SzLogOutputType { STDERR, STDOUT, FILE, NULL }

/** Where the default log sink writes. */
public sealed class SzLogOutput {
    /** The kind of this output. */
    public abstract val outputType: SzLogOutputType

    /** The file path of a [File] output, otherwise null. */
    public open val path: String? get() = null

    /** Standard error (the engine's initial default). */
    public data object Stderr : SzLogOutput() {
        override val outputType: SzLogOutputType get() = SzLogOutputType.STDERR
    }

    /** Standard output. */
    public data object Stdout : SzLogOutput() {
        override val outputType: SzLogOutputType get() = SzLogOutputType.STDOUT
    }

    /** Discards every message. */
    public data object Null : SzLogOutput() {
        override val outputType: SzLogOutputType get() = SzLogOutputType.NULL
    }

    /** A file, truncated when set; its parent directories are created. */
    public data class File(
        override val path: String,
    ) : SzLogOutput() {
        override val outputType: SzLogOutputType get() = SzLogOutputType.FILE
    }
}

/**
 * How a log callback receives messages.
 *
 * @property minLevel The lowest level delivered.
 * @property format The layout of each message (default [SzLogFormat.BARE]: the text only).
 */
public data class SzLogCallbackOptions(
    public val minLevel: SzLogLevel,
    public val format: SzLogFormat = SzLogFormat.BARE,
)

/**
 * One log message delivered to a callback.
 *
 * @property level The message's level.
 * @property rawModule The native module text, `module:threadHash`.
 * @property message The message, laid out in the registration's [SzLogFormat].
 */
public data class SzLogRecord(
    public val level: SzLogLevel,
    public val rawModule: String,
    public val message: String,
) {
    /** The module name ([rawModule] before its last `:`). */
    public val module: String get() = rawModule.substringBeforeLast(':', rawModule)

    /** The logging thread's hash ([rawModule] after its last `:`), or empty. */
    public val threadHash: String get() = if (':' in rawModule) rawModule.substringAfterLast(':') else ""
}

/**
 * Receives log records. Called on the thread that logged, possibly inside an SDK call: it must
 * not call the SDK (such calls fail) and should return quickly. An exception it throws goes to
 * that thread's uncaught-exception handler.
 */
public typealias SzLogCallback = (SzLogRecord) -> Unit

/** A log callback registration; [close] unregisters it, so it works with `use`. */
public interface SzLogRegistration : AutoCloseable {
    /**
     * Stops delivery and releases the callback. Idempotent. From inside a log callback,
     * delivery stops at once and the native registration is released for the process.
     */
    public fun unregister()

    /** Keeps the callback registered for the life of the process, releasing this handle. */
    public fun leak()

    /** Whether the callback still receives records. */
    public val isRegistered: Boolean

    /** Unregisters ([unregister]). */
    override fun close(): Unit = unregister()
}

/**
 * The process-wide Senzing log controls, mirroring the C# `SzLogging` interface.
 *
 * Logging is not tied to an [SzEnvironment]'s lifetime. Changing a sink (registering a
 * callback, or setting the default callback, output or format) from inside a log callback
 * throws [SzConfigurationException].
 */
public interface SzLogging {
    /** Registers a callback for messages from every thread. */
    public fun registerCallback(
        options: SzLogCallbackOptions,
        callback: SzLogCallback,
    ): SzLogRegistration

    /** Registers a callback for messages at [minLevel] and above, in [SzLogFormat.BARE]. */
    public fun registerCallback(
        minLevel: SzLogLevel,
        callback: SzLogCallback,
    ): SzLogRegistration = registerCallback(SzLogCallbackOptions(minLevel), callback)

    /** Replaces the default sink with a callback, for the rest of the process. */
    public fun setDefaultCallback(
        options: SzLogCallbackOptions,
        callback: SzLogCallback,
    )

    /** Replaces the default sink with an output. */
    public fun setDefaultOutput(output: SzLogOutput)

    /** Sets the default sink's message layout. */
    public fun setDefaultFormat(format: SzLogFormat)

    /** Sets the default sink's minimum level. */
    public fun setDefaultLogLevel(level: SzLogLevel)

    /** The lowest level any sink receives. */
    public fun getEffectiveLogLevel(): SzLogLevel
}
