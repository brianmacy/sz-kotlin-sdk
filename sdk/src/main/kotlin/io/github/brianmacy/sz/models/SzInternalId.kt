package io.github.brianmacy.sz.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The engine's internal ID for a record's observation (`INTERNAL_ID`).
 *
 * The engine reports it as a JSON integer or as an opaque string; both decode, and encoding
 * writes the value back in the form it was read.
 *
 * @property rawValue The ID as text: the integer's decimal digits, or the string as given.
 * @property intValue The ID as an integer, when the engine reported an integer.
 */
@Serializable(with = SzInternalIdSerializer::class)
public class SzInternalId private constructor(
    public val rawValue: String,
    public val intValue: Long?,
) {
    /** An integer ID. */
    public constructor(value: Long) : this(value.toString(), value)

    /** A string ID. */
    public constructor(value: String) : this(value, null)

    override fun equals(other: Any?): Boolean = other is SzInternalId && rawValue == other.rawValue && intValue == other.intValue

    override fun hashCode(): Int = 31 * rawValue.hashCode() + intValue.hashCode()

    /** The ID as text ([rawValue]). */
    override fun toString(): String = rawValue
}

/**
 * Decodes `INTERNAL_ID` from a JSON integer or a JSON string, and encodes it back in the form it
 * was read. A JSON string of digits stays a string ID, as in the Swift SDK.
 */
internal object SzInternalIdSerializer : KSerializer<SzInternalId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.brianmacy.sz.models.SzInternalId", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SzInternalId {
        if (decoder !is JsonDecoder) return SzInternalId(decoder.decodeString())
        val element = decoder.decodeJsonElement()
        if (element is JsonPrimitive) {
            if (element.isString) return SzInternalId(element.content)
            element.longOrNull?.let { return SzInternalId(it) }
        }
        throw SerializationException("INTERNAL_ID must be an integer or a string, not $element")
    }

    override fun serialize(
        encoder: Encoder,
        value: SzInternalId,
    ) {
        val intValue = value.intValue
        if (intValue != null) encoder.encodeLong(intValue) else encoder.encodeString(value.rawValue)
    }
}
