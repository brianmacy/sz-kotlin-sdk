package io.github.brianmacy.sz.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes a feature reference (`LIB_FEAT_REF`, `INBOUND_FEAT_REF`, `CANDIDATE_FEAT_REF`,
 * `FEAT_REF`) as text. Depending on the repository, the engine reports one as a JSON integer or
 * as an opaque string; both keep their text. Encoding writes a string.
 */
internal object SzFeatureRefSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.brianmacy.sz.models.SzFeatureRef", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String =
        (decoder as? JsonDecoder)?.decodeJsonElement()?.jsonPrimitive?.content ?: decoder.decodeString()

    override fun serialize(
        encoder: Encoder,
        value: String,
    ) = encoder.encodeString(value)
}
