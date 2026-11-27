package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.Native

/**
 * An error reported by the Senzing engine, mirroring the C# `SzException` hierarchy:
 *
 * ```
 * SzException
 * ├── SzBadInputException
 * │   ├── SzNotFoundException
 * │   └── SzUnknownDataSourceException
 * ├── SzConfigurationException
 * ├── SzReplaceConflictException
 * ├── SzRetryableException
 * │   ├── SzDatabaseConnectionLostException
 * │   ├── SzDatabaseTransientException
 * │   └── SzRetryTimeoutExceededException
 * └── SzUnrecoverableException
 *     ├── SzDatabaseException
 *     ├── SzLicenseException
 *     ├── SzNotInitializedException
 *     └── SzUnhandledException
 * ```
 *
 * Native error codes are assigned to classes by the `szerrors.json` file of the Senzing runtime
 * the SDK's JNI library is built against, with sz-rust-sdk-ffi's generator. Catch a class to
 * catch its subclasses too:
 *
 * ```kotlin
 * try {
 *     engine.getEntity("CUSTOMERS", "1001")
 * } catch (e: SzNotFoundException) {
 *     // no such record
 * } catch (e: SzRetryableException) {
 *     // retry later
 * }
 * ```
 *
 * Two errors sit outside this tree because the SDK raises them, not the engine:
 * [SzEnvironmentDestroyedException] and [SzJsonException].
 *
 * @property errorCode The native Senzing error code (the number in `SENZnnnn`), if any.
 */
public open class SzException(
    message: String,
    public val errorCode: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The error message (never null). */
    override val message: String get() = super.message.orEmpty()

    /** Whether retrying the operation may succeed. */
    public val isRetryable: Boolean get() = this is SzRetryableException

    /** Whether the engine cannot continue. */
    public val isUnrecoverable: Boolean get() = this is SzUnrecoverableException

    override fun toString(): String {
        val name = javaClass.simpleName
        return if (errorCode != null) "$name($errorCode): $message" else "$name: $message"
    }

    public companion object {
        /** The SDK exception class for each `szerrors.json` class name. */
        private val factories: Map<String, (String, Long?) -> Exception> =
            mapOf<String, (String, Long?) -> Exception>(
                "SzError" to { message, code -> SzException(message, code) },
                "SzBadInputError" to ::SzBadInputException,
                "SzNotFoundError" to ::SzNotFoundException,
                "SzUnknownDataSourceError" to ::SzUnknownDataSourceException,
                "SzConfigurationError" to ::SzConfigurationException,
                "SzReplaceConflictError" to ::SzReplaceConflictException,
                "SzRetryableError" to ::SzRetryableException,
                "SzDatabaseConnectionLostError" to ::SzDatabaseConnectionLostException,
                "SzDatabaseTransientError" to ::SzDatabaseTransientException,
                "SzRetryTimeoutExceededError" to ::SzRetryTimeoutExceededException,
                "SzUnrecoverableError" to ::SzUnrecoverableException,
                "SzDatabaseError" to ::SzDatabaseException,
                "SzLicenseError" to ::SzLicenseException,
                "SzNotInitializedError" to ::SzNotInitializedException,
                "SzUnhandledError" to ::SzUnhandledException,
                "SzEnvironmentDestroyedError" to { message, _ -> SzEnvironmentDestroyedException(message) },
            )

        /** The `szerrors.json` class names the SDK maps, in hierarchy order. */
        public val classNames: Set<String> get() = factories.keys

        /**
         * Creates the exception for a `szerrors.json` class name (unknown names give a plain
         * [SzException]).
         */
        public fun create(
            className: String,
            errorCode: Long?,
            message: String,
        ): Exception = (factories[className] ?: factories.getValue("SzError"))(message, errorCode)

        /** The `szerrors.json` class name assigned to a native error code. */
        public fun classNameForErrorCode(errorCode: Long): String = Native.classify(errorCode)

        /** Creates the exception that `szerrors.json` assigns to a native error code. */
        public fun forErrorCode(
            errorCode: Long,
            message: String,
        ): SzException = create(classNameForErrorCode(errorCode), errorCode, message) as SzException
    }
}

/** The input was invalid (C# `SzBadInputException`). */
public open class SzBadInputException(
    message: String,
    errorCode: Long? = null,
) : SzException(message, errorCode)

/** The requested entity or record does not exist (C# `SzNotFoundException`). */
public open class SzNotFoundException(
    message: String,
    errorCode: Long? = null,
) : SzBadInputException(message, errorCode)

/** The data source is not registered (C# `SzUnknownDataSourceException`). */
public open class SzUnknownDataSourceException(
    message: String,
    errorCode: Long? = null,
) : SzBadInputException(message, errorCode)

/** The configuration is missing or invalid (C# `SzConfigurationException`). */
public open class SzConfigurationException(
    message: String,
    errorCode: Long? = null,
) : SzException(message, errorCode)

/** The default configuration changed concurrently (C# `SzReplaceConflictException`). */
public open class SzReplaceConflictException(
    message: String,
    errorCode: Long? = null,
) : SzException(message, errorCode)

/** The operation may succeed if retried (C# `SzRetryableException`). */
public open class SzRetryableException(
    message: String,
    errorCode: Long? = null,
) : SzException(message, errorCode)

/** The database connection was lost (C# `SzDatabaseConnectionLostException`). */
public open class SzDatabaseConnectionLostException(
    message: String,
    errorCode: Long? = null,
) : SzRetryableException(message, errorCode)

/** A transient database error occurred (C# `SzDatabaseTransientException`). */
public open class SzDatabaseTransientException(
    message: String,
    errorCode: Long? = null,
) : SzRetryableException(message, errorCode)

/** A retry timeout was exceeded (C# `SzRetryTimeoutExceededException`). */
public open class SzRetryTimeoutExceededException(
    message: String,
    errorCode: Long? = null,
) : SzRetryableException(message, errorCode)

/** The engine cannot continue (C# `SzUnrecoverableException`). */
public open class SzUnrecoverableException(
    message: String,
    errorCode: Long? = null,
) : SzException(message, errorCode)

/** A non-transient database error occurred (C# `SzDatabaseException`). */
public open class SzDatabaseException(
    message: String,
    errorCode: Long? = null,
) : SzUnrecoverableException(message, errorCode)

/** The license is invalid or exceeded (C# `SzLicenseException`). */
public open class SzLicenseException(
    message: String,
    errorCode: Long? = null,
) : SzUnrecoverableException(message, errorCode)

/** The engine is not initialized (C# `SzNotInitializedException`). */
public open class SzNotInitializedException(
    message: String,
    errorCode: Long? = null,
) : SzUnrecoverableException(message, errorCode)

/** An unhandled engine error occurred (C# `SzUnhandledException`). */
public open class SzUnhandledException(
    message: String,
    errorCode: Long? = null,
) : SzUnrecoverableException(message, errorCode)

/**
 * The [SzEnvironment] was used after [SzEnvironment.destroy] (C# and Java
 * `SzEnvironmentDestroyedException`). Not an engine error, so not an [SzException].
 */
public class SzEnvironmentDestroyedException(
    message: String = "The Senzing environment has already been destroyed.",
) : IllegalStateException(message)

/**
 * A value could not be encoded to, or decoded from, the JSON the engine exchanges (typed
 * records and results). Not an engine error, so not an [SzException].
 */
public class SzJsonException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
