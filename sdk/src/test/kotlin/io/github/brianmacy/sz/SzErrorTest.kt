package io.github.brianmacy.sz

import io.github.brianmacy.sz.testsupport.SenzingRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SzErrorTest {
    /** The SDK exception class for each `szerrors.json` class name. */
    private val expectedClasses: Map<String, KClass<out Exception>> =
        mapOf(
            "SzError" to SzException::class,
            "SzBadInputError" to SzBadInputException::class,
            "SzNotFoundError" to SzNotFoundException::class,
            "SzUnknownDataSourceError" to SzUnknownDataSourceException::class,
            "SzConfigurationError" to SzConfigurationException::class,
            "SzReplaceConflictError" to SzReplaceConflictException::class,
            "SzRetryableError" to SzRetryableException::class,
            "SzDatabaseConnectionLostError" to SzDatabaseConnectionLostException::class,
            "SzDatabaseTransientError" to SzDatabaseTransientException::class,
            "SzRetryTimeoutExceededError" to SzRetryTimeoutExceededException::class,
            "SzUnrecoverableError" to SzUnrecoverableException::class,
            "SzDatabaseError" to SzDatabaseException::class,
            "SzLicenseError" to SzLicenseException::class,
            "SzNotInitializedError" to SzNotInitializedException::class,
            "SzUnhandledError" to SzUnhandledException::class,
            "SzEnvironmentDestroyedError" to SzEnvironmentDestroyedException::class,
        )

    private fun KClass<*>.isKindOf(parent: KClass<*>): Boolean = parent.java.isAssignableFrom(java)

    @Test
    fun `test_hierarchy mirrors the C# exception tree`() {
        assertTrue(SzNotFoundException::class.isKindOf(SzBadInputException::class))
        assertTrue(SzNotFoundException::class.isKindOf(SzException::class))
        assertEquals<Class<*>>(SzBadInputException::class.java, SzUnknownDataSourceException::class.java.superclass)
        assertTrue(SzDatabaseTransientException::class.isKindOf(SzRetryableException::class))
        assertTrue(SzRetryTimeoutExceededException::class.isKindOf(SzRetryableException::class))
        assertTrue(SzDatabaseConnectionLostException::class.isKindOf(SzRetryableException::class))
        assertTrue(SzLicenseException::class.isKindOf(SzUnrecoverableException::class))
        assertTrue(SzDatabaseException::class.isKindOf(SzUnrecoverableException::class))
        assertTrue(SzNotInitializedException::class.isKindOf(SzUnrecoverableException::class))
        assertTrue(SzUnhandledException::class.isKindOf(SzUnrecoverableException::class))
        assertEquals<Class<*>>(SzException::class.java, SzConfigurationException::class.java.superclass)
        assertEquals<Class<*>>(SzException::class.java, SzReplaceConflictException::class.java.superclass)
        assertEquals<Class<*>>(Exception::class.java, SzException::class.java.superclass)
        assertFalse(SzBadInputException::class.isKindOf(SzNotFoundException::class))
        assertFalse(SzEnvironmentDestroyedException::class.isKindOf(SzException::class))
        assertTrue(SzEnvironmentDestroyedException::class.isKindOf(IllegalStateException::class))
        assertFalse(SzJsonException::class.isKindOf(SzException::class))
    }

    @Test
    fun `test_every engine class descends from SzException`() {
        for (name in SzException.classNames - "SzEnvironmentDestroyedError") {
            assertIs<SzException>(SzException.create(name, 1, "m"), name)
        }
    }

    @Test
    fun `test_create maps every class name and falls back to SzException`() {
        assertEquals(expectedClasses.keys, SzException.classNames)
        for ((name, expected) in expectedClasses) {
            val error = SzException.create(name, 7, "message $name")
            assertEquals(expected, error::class, name)
            assertEquals("message $name", error.message)
            if (error is SzException) assertEquals(7L, error.errorCode, name)
        }
        val unknown = SzException.create("NoSuchError", null, "x")
        assertEquals(SzException::class, unknown::class)
        assertNull((unknown as SzException).errorCode)
    }

    @Test
    fun `test_error codes map to classes per szerrors json`() {
        // Codes and classes observed from the real engine (see the engine read tests).
        assertEquals("SzNotFoundError", SzException.classNameForErrorCode(33))
        assertEquals("SzNotFoundError", SzException.classNameForErrorCode(37))
        assertEquals("SzBadInputError", SzException.classNameForErrorCode(88))
        assertEquals("SzUnknownDataSourceError", SzException.classNameForErrorCode(2207))
        assertEquals("SzReplaceConflictError", SzException.classNameForErrorCode(7245))
        assertEquals("SzConfigurationError", SzException.classNameForErrorCode(7221))
        assertEquals("SzError", SzException.classNameForErrorCode(-1))
        assertEquals("SzError", SzException.classNameForErrorCode(999_999))
        // Beyond the native code range.
        assertEquals("SzError", SzException.classNameForErrorCode(1L shl 40))

        val notFound = SzException.forErrorCode(33, "Unknown record")
        assertIs<SzNotFoundException>(notFound)
        assertEquals(33L, notFound.errorCode)
        assertEquals("Unknown record", notFound.message)
        assertEquals(SzException::class, SzException.forErrorCode(999_999, "x")::class)
        assertIs<SzUnknownDataSourceException>(SzException.forErrorCode(2207, "x"))
    }

    @Test
    fun `test_generated table matches the installed szerrors json`() {
        val file = SenzingRuntime.current.erDirectory.resolve("sdk/szerrors.json")
        val entries = Json.parseToJsonElement(file.readText()).jsonObject
        assertTrue(entries.isNotEmpty())
        for ((code, entry) in entries) {
            val className =
                entry.jsonObject
                    .getValue("class")
                    .jsonPrimitive.content
            assertEquals(className, SzException.classNameForErrorCode(code.toLong()), "code $code")
            assertTrue(className in SzException.classNames, "class $className of code $code")
            assertEquals(expectedClasses[className], SzException.forErrorCode(code.toLong(), "m")::class, "code $code")
        }
    }

    @Test
    fun `test_SzException properties and description`() {
        val error = SzNotFoundException("Unknown record", 33)
        assertEquals(33L, error.errorCode)
        assertEquals("Unknown record", error.message)
        assertEquals("SzNotFoundException(33): Unknown record", error.toString())
        assertFalse(error.isRetryable)
        assertFalse(error.isUnrecoverable)
        assertIs<SzBadInputException>(error)

        val plain = SzLicenseException("expired")
        assertNull(plain.errorCode)
        assertEquals("SzLicenseException: expired", plain.toString())
        assertTrue(plain.isUnrecoverable)
        assertFalse(plain.isRetryable)
        assertTrue(SzDatabaseTransientException("x").isRetryable)

        val cause = IllegalArgumentException("inner")
        val wrapped = SzException("outer", 5, cause)
        assertSame(cause, wrapped.cause)
        assertEquals("SzException(5): outer", wrapped.toString())

        assertEquals(
            "The Senzing environment has already been destroyed.",
            SzEnvironmentDestroyedException().message,
        )
        assertEquals("gone", SzEnvironmentDestroyedException("gone").message)
        val json = SzJsonException("bad", cause)
        assertSame(cause, json.cause)
        assertNull(SzJsonException("bad").cause)
    }

    @Test
    fun `test_every exception class takes an optional error code`() {
        val withoutCode: List<SzException> =
            listOf(
                SzException("m"),
                SzBadInputException("m"),
                SzNotFoundException("m"),
                SzUnknownDataSourceException("m"),
                SzConfigurationException("m"),
                SzReplaceConflictException("m"),
                SzRetryableException("m"),
                SzDatabaseConnectionLostException("m"),
                SzDatabaseTransientException("m"),
                SzRetryTimeoutExceededException("m"),
                SzUnrecoverableException("m"),
                SzDatabaseException("m"),
                SzLicenseException("m"),
                SzNotInitializedException("m"),
                SzUnhandledException("m"),
            )
        for (error in withoutCode) {
            assertNull(error.errorCode, error.toString())
            assertNull(error.cause)
            assertEquals("${error.javaClass.simpleName}: m", error.toString())
        }
        assertEquals(3L, SzException("m", 3).errorCode)
    }

    @Test
    fun `test_catch clauses match through the hierarchy`() {
        val caught =
            listOf("SzNotFoundError", "SzDatabaseConnectionLostError", "SzUnhandledError", "SzEnvironmentDestroyedError")
                .map { name ->
                    try {
                        throw SzException.create(name, null, "test")
                    } catch (_: SzBadInputException) {
                        "badInput"
                    } catch (_: SzRetryableException) {
                        "retryable"
                    } catch (_: SzUnrecoverableException) {
                        "unrecoverable"
                    } catch (_: Exception) {
                        "other"
                    }
                }
        assertEquals(listOf("badInput", "retryable", "unrecoverable", "other"), caught)
    }
}
