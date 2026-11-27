package io.github.brianmacy.sz.internal

import io.github.brianmacy.sz.SzJsonException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/** JSON conversion for typed records and results; failures throw [SzJsonException]. */
internal object SzJson {
    /** Lenient on input (engine documents grow new fields); omits nulls on output. */
    val format: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /** Decodes [text]; null (no info returned) decodes as `{}`. */
    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        text: String?,
    ): T {
        try {
            return format.decodeFromString(deserializer, text ?: "{}")
        } catch (e: SerializationException) {
            throw SzJsonException("Cannot decode ${deserializer.descriptor.serialName}: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw SzJsonException("Cannot decode ${deserializer.descriptor.serialName}: ${e.message}", e)
        }
    }

    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): String {
        try {
            return format.encodeToString(serializer, value)
        } catch (e: SerializationException) {
            throw SzJsonException("Cannot encode ${serializer.descriptor.serialName}: ${e.message}", e)
        }
    }
}
