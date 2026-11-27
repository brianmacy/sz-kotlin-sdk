// Serializable models of the engine's entity JSON.
//
// Which members are present depends on the flags of the call, so everything beyond the
// identifiers is optional. Members not modeled here remain available in the raw JSON returned
// by the untyped methods.

package io.github.brianmacy.sz.models

import io.github.brianmacy.sz.SzRecordKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The response of `SzEngine.getEntity` (by entity ID or by record key), and one line of a JSON
 * export.
 *
 * @property resolvedEntity The entity.
 * @property relatedEntities Related entities (present with the relation flags).
 */
@Serializable
public data class SzEntityResponse(
    @SerialName("RESOLVED_ENTITY") public val resolvedEntity: SzResolvedEntity,
    @SerialName("RELATED_ENTITIES") public val relatedEntities: List<SzRelatedEntity>? = null,
)

/**
 * A resolved entity.
 *
 * @property entityId The entity ID.
 * @property entityName The best name of the entity.
 * @property features The entity's features by feature type (for example `NAME`, `ADDRESS`).
 * @property recordSummary Record counts per data source.
 * @property records The records that make up the entity.
 */
@Serializable
public data class SzResolvedEntity(
    @SerialName("ENTITY_ID") public val entityId: Long,
    @SerialName("ENTITY_NAME") public val entityName: String? = null,
    @SerialName("FEATURES") public val features: Map<String, List<SzEntityFeature>>? = null,
    @SerialName("RECORD_SUMMARY") public val recordSummary: List<SzRecordSummary>? = null,
    @SerialName("RECORDS") public val records: List<SzEntityRecord>? = null,
)

/**
 * A feature of an entity, with the values it was built from.
 *
 * @property featureDescription The representative description of the feature.
 * @property libraryFeatureId The library feature ID.
 * @property usageType The usage type, for example `HOME` or `MAILING`.
 * @property values The values grouped under this feature.
 */
@Serializable
public data class SzEntityFeature(
    @SerialName("FEAT_DESC") public val featureDescription: String,
    @SerialName("LIB_FEAT_ID") public val libraryFeatureId: Long? = null,
    @SerialName("USAGE_TYPE") public val usageType: String? = null,
    @SerialName("FEAT_DESC_VALUES") public val values: List<SzFeatureValue>? = null,
)

/**
 * One value of an [SzEntityFeature].
 *
 * @property featureDescription The value's description.
 * @property libraryFeatureId The library feature ID.
 */
@Serializable
public data class SzFeatureValue(
    @SerialName("FEAT_DESC") public val featureDescription: String,
    @SerialName("LIB_FEAT_ID") public val libraryFeatureId: Long? = null,
)

/**
 * The number of records an entity has from one data source.
 *
 * @property dataSourceCode The data source code.
 * @property recordCount The number of records.
 */
@Serializable
public data class SzRecordSummary(
    @SerialName("DATA_SOURCE") public val dataSourceCode: String,
    @SerialName("RECORD_COUNT") public val recordCount: Int,
)

/**
 * A record within an entity, with how it matched.
 *
 * @property dataSourceCode The data source code.
 * @property recordId The record ID.
 * @property internalId The engine's internal ID for the record's observation.
 * @property matchKey The match key, for example `+NAME+DOB+PHONE` (empty for the first record).
 * @property matchLevelCode The match level, for example `RESOLVED` (empty for the first record).
 * @property resolutionRuleCode The resolution rule that matched (empty for the first record).
 */
@Serializable
public data class SzEntityRecord(
    @SerialName("DATA_SOURCE") public val dataSourceCode: String,
    @SerialName("RECORD_ID") public val recordId: String,
    @SerialName("INTERNAL_ID") public val internalId: SzInternalId? = null,
    @SerialName("MATCH_KEY") public val matchKey: String? = null,
    @SerialName("MATCH_LEVEL_CODE") public val matchLevelCode: String? = null,
    @SerialName("ERRULE_CODE") public val resolutionRuleCode: String? = null,
) {
    /** The record's key. */
    public val recordKey: SzRecordKey
        get() = SzRecordKey(dataSourceCode, recordId)
}

/**
 * An entity related to another, with how they relate.
 *
 * @property entityId The related entity's ID.
 * @property entityName The best name of the related entity.
 * @property matchLevelCode The match level, for example `POSSIBLY_SAME`.
 * @property matchKey The match key, for example `+NAME+ADDRESS-DOB`.
 * @property resolutionRuleCode The rule that related the entities.
 * @property isDisclosed `1` when the relationship was disclosed by the data.
 * @property isAmbiguous `1` when the relationship is ambiguous.
 * @property recordSummary Record counts per data source of the related entity.
 */
@Serializable
public data class SzRelatedEntity(
    @SerialName("ENTITY_ID") public val entityId: Long,
    @SerialName("ENTITY_NAME") public val entityName: String? = null,
    @SerialName("MATCH_LEVEL_CODE") public val matchLevelCode: String? = null,
    @SerialName("MATCH_KEY") public val matchKey: String? = null,
    @SerialName("ERRULE_CODE") public val resolutionRuleCode: String? = null,
    @SerialName("IS_DISCLOSED") public val isDisclosed: Int? = null,
    @SerialName("IS_AMBIGUOUS") public val isAmbiguous: Int? = null,
    @SerialName("RECORD_SUMMARY") public val recordSummary: List<SzRecordSummary>? = null,
)

/**
 * The info returned by record mutations when `SzFlags.WITH_INFO` is set.
 *
 * @property dataSourceCode The data source of the record (absent for entity reevaluation).
 * @property recordId The record ID (absent for entity reevaluation).
 * @property affectedEntities The entities the change affected (empty when absent or null).
 */
@Serializable
public data class SzWithInfoResponse(
    @SerialName("DATA_SOURCE") public val dataSourceCode: String? = null,
    @SerialName("RECORD_ID") public val recordId: String? = null,
    @Serializable(with = NullAsEmptyListSerializer::class)
    @SerialName("AFFECTED_ENTITIES")
    public val affectedEntities: List<SzEntityReference> = emptyList(),
)

/**
 * A reference to an entity by ID.
 *
 * @property entityId The entity ID.
 */
@Serializable
public data class SzEntityReference(
    @SerialName("ENTITY_ID") public val entityId: Long,
)

/**
 * A list that decodes JSON `null` as an empty list, so that, with the property's `emptyList()`
 * default for an absent key, it mirrors Swift's `decodeIfPresent(...) ?? []`.
 */
internal class NullAsEmptyListSerializer<T>(
    elementSerializer: KSerializer<T>,
) : KSerializer<List<T>> {
    private val listSerializer = ListSerializer(elementSerializer)

    override val descriptor: SerialDescriptor = listSerializer.nullable.descriptor

    override fun deserialize(decoder: Decoder): List<T> =
        if (decoder.decodeNotNullMark()) {
            decoder.decodeSerializableValue(listSerializer)
        } else {
            decoder.decodeNull()
            emptyList()
        }

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) {
        encoder.encodeSerializableValue(listSerializer, value)
    }
}
