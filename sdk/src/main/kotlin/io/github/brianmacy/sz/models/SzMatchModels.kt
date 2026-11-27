package io.github.brianmacy.sz.models

import io.github.brianmacy.sz.SzRecordKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The response of `SzEngine.searchByAttributes`.
 *
 * @property resolvedEntities The matching entities, best first (empty when absent or null).
 */
@Serializable
public data class SzSearchResponse(
    @Serializable(with = NullAsEmptyListSerializer::class)
    @SerialName("RESOLVED_ENTITIES")
    public val resolvedEntities: List<SzSearchResult> = emptyList(),
)

/**
 * One search result: an entity and how it matched the search attributes.
 *
 * @property matchInfo How the entity matched.
 * @property entity The entity.
 */
@Serializable
public data class SzSearchResult(
    @SerialName("MATCH_INFO") public val matchInfo: SzMatchInfo,
    @SerialName("ENTITY") public val entity: SzEntityResponse,
)

/**
 * How two things matched: match level, key, rule, and feature scores.
 *
 * @property matchLevelCode The match level, for example `RESOLVED` or `POSSIBLY_SAME`.
 * @property matchKey The match key, for example `+NAME+DOB`.
 * @property resolutionRuleCode The rule that matched.
 * @property whyKey The key that explains a why result.
 * @property whyResolutionRuleCode The rule that explains a why result.
 * @property featureScores The feature comparisons by feature type (present with
 *   `SzFlags.INCLUDE_FEATURE_SCORES`).
 * @property candidateKeys The candidate keys the match shares, by key type.
 */
@Serializable
public data class SzMatchInfo(
    @SerialName("MATCH_LEVEL_CODE") public val matchLevelCode: String? = null,
    @SerialName("MATCH_KEY") public val matchKey: String? = null,
    @SerialName("ERRULE_CODE") public val resolutionRuleCode: String? = null,
    @SerialName("WHY_KEY") public val whyKey: String? = null,
    @SerialName("WHY_ERRULE_CODE") public val whyResolutionRuleCode: String? = null,
    @SerialName("FEATURE_SCORES") public val featureScores: Map<String, List<SzFeatureScore>>? = null,
    @SerialName("CANDIDATE_KEYS") public val candidateKeys: Map<String, List<SzCandidateKey>>? = null,
)

/**
 * The comparison of one inbound feature with one candidate feature.
 *
 * @property inboundFeatureId The library feature ID of the inbound feature. See also [inboundFeatureRef].
 * @property inboundFeatureRef The library feature ID of the inbound feature as a reference (`INBOUND_FEAT_REF`), when the engine
 *   reports one instead of `INBOUND_FEAT_ID`: an integer or an opaque string, as text.
 * @property inboundFeatureDescription The description of the inbound feature.
 * @property inboundFeatureUsageType The usage type of the inbound feature, for example `HOME`.
 * @property candidateFeatureId The library feature ID of the candidate feature. See also [candidateFeatureRef].
 * @property candidateFeatureRef The library feature ID of the candidate feature as a reference (`CANDIDATE_FEAT_REF`), when the engine
 *   reports one instead of `CANDIDATE_FEAT_ID`: an integer or an opaque string, as text.
 * @property candidateFeatureDescription The description of the candidate feature.
 * @property candidateFeatureUsageType The usage type of the candidate feature, for example `HOME`.
 * @property score The score, 0 to 100.
 * @property scoreBucket The score bucket, for example `SAME`, `CLOSE` or `NO_CHANCE`.
 * @property scoreBehavior The comparison behavior code.
 * @property additionalScores Component scores, for example `GNR_FN` for names.
 */
@Serializable
public data class SzFeatureScore(
    @SerialName("INBOUND_FEAT_ID") public val inboundFeatureId: Long? = null,
    @SerialName("INBOUND_FEAT_REF") @Serializable(with = SzFeatureRefSerializer::class) public val inboundFeatureRef: String? = null,
    @SerialName("INBOUND_FEAT_DESC") public val inboundFeatureDescription: String? = null,
    @SerialName("INBOUND_FEAT_USAGE_TYPE") public val inboundFeatureUsageType: String? = null,
    @SerialName("CANDIDATE_FEAT_ID") public val candidateFeatureId: Long? = null,
    @SerialName("CANDIDATE_FEAT_REF") @Serializable(with = SzFeatureRefSerializer::class) public val candidateFeatureRef: String? = null,
    @SerialName("CANDIDATE_FEAT_DESC") public val candidateFeatureDescription: String? = null,
    @SerialName("CANDIDATE_FEAT_USAGE_TYPE") public val candidateFeatureUsageType: String? = null,
    @SerialName("SCORE") public val score: Int? = null,
    @SerialName("SCORE_BUCKET") public val scoreBucket: String? = null,
    @SerialName("SCORE_BEHAVIOR") public val scoreBehavior: String? = null,
    @SerialName("ADDITIONAL_SCORES") public val additionalScores: Map<String, Int>? = null,
)

/**
 * A candidate key shared by a match.
 *
 * @property featureId The library feature ID of the key. See also [featureRef].
 * @property featureRef The library feature ID of the key as a reference (`FEAT_REF`), when the engine
 *   reports one instead of `FEAT_ID`: an integer or an opaque string, as text.
 * @property featureDescription The description of the key.
 */
@Serializable
public data class SzCandidateKey(
    @SerialName("FEAT_ID") public val featureId: Long? = null,
    @SerialName("FEAT_REF") @Serializable(with = SzFeatureRefSerializer::class) public val featureRef: String? = null,
    @SerialName("FEAT_DESC") public val featureDescription: String? = null,
)

/**
 * The response of the why methods: `SzEngine.whyEntities`, `SzEngine.whyRecords` and
 * `SzEngine.whyRecordInEntity`.
 *
 * @property whyResults The explanations (empty when absent or null).
 * @property entities The entities involved (empty when absent or null).
 */
@Serializable
public data class SzWhyResponse(
    @Serializable(with = NullAsEmptyListSerializer::class)
    @SerialName("WHY_RESULTS")
    public val whyResults: List<SzWhyResult> = emptyList(),
    @Serializable(with = NullAsEmptyListSerializer::class)
    @SerialName("ENTITIES")
    public val entities: List<SzEntityResponse> = emptyList(),
)

/**
 * One why explanation, between one side and (for two-sided whys) another.
 *
 * @property internalId The internal ID of the first side's observation.
 * @property entityId The entity of the first side.
 * @property focusRecords The records of the first side.
 * @property internalId2 The internal ID of the second side's observation (two-sided whys).
 * @property entityId2 The entity of the second side (two-sided whys).
 * @property focusRecords2 The records of the second side (two-sided whys).
 * @property matchInfo How the two sides matched, or why they did not.
 */
@Serializable
public data class SzWhyResult(
    @SerialName("INTERNAL_ID") public val internalId: SzInternalId? = null,
    @SerialName("ENTITY_ID") public val entityId: Long,
    @SerialName("FOCUS_RECORDS") public val focusRecords: List<SzRecordKey>? = null,
    @SerialName("INTERNAL_ID_2") public val internalId2: SzInternalId? = null,
    @SerialName("ENTITY_ID_2") public val entityId2: Long? = null,
    @SerialName("FOCUS_RECORDS_2") public val focusRecords2: List<SzRecordKey>? = null,
    @SerialName("MATCH_INFO") public val matchInfo: SzMatchInfo,
)
