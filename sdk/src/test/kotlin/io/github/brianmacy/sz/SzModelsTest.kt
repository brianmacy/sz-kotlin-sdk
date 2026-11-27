package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.SzJson
import io.github.brianmacy.sz.models.NullAsEmptyListSerializer
import io.github.brianmacy.sz.models.SzConfigRegistry
import io.github.brianmacy.sz.models.SzDataSourceRegistry
import io.github.brianmacy.sz.models.SzEntityReference
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzInternalId
import io.github.brianmacy.sz.models.SzInternalIdSerializer
import io.github.brianmacy.sz.models.SzLicenseInfo
import io.github.brianmacy.sz.models.SzSearchResponse
import io.github.brianmacy.sz.models.SzVersionInfo
import io.github.brianmacy.sz.models.SzWhyResponse
import io.github.brianmacy.sz.models.SzWithInfoResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Decoding of the `models` types from real engine output, and the custom serializers with their
 * failure paths. Each model decoded from the engine is also encoded and decoded again, which
 * must give an equal value.
 */
class SzModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Encodes [value] and decodes it again; the result must equal [value]. */
    private fun <T> assertRoundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
    }

    @Test
    fun `test_entity models decode from getEntity`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val flags = SzFlags.ENTITY_DEFAULT_FLAGS or SzFlags.ENTITY_INCLUDE_ALL_FEATURES
            val entity = fixture.engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer(), flags)
            val resolved = entity.resolvedEntity
            assertTrue(resolved.entityId > 0)
            assertEquals("Robert Smith", resolved.entityName)

            val features = assertNotNull(resolved.features)
            val name = assertNotNull(features["NAME"]).first()
            assertTrue(name.featureDescription.isNotEmpty())
            val value = assertNotNull(name.values).first()
            assertTrue(value.featureDescription.isNotEmpty())
            assertTrue(features.values.flatten().any { it.usageType != null }, features.toString())

            assertEquals(4, assertNotNull(resolved.recordSummary).single { it.dataSourceCode == "CUSTOMERS" }.recordCount)
            val records = assertNotNull(resolved.records)
            assertTrue(records.all { it.internalId?.intValue != null }, records.toString())
            val matched = records.first { !it.matchKey.isNullOrEmpty() }
            assertEquals("RESOLVED", matched.matchLevelCode)
            assertFalse(matched.resolutionRuleCode.isNullOrEmpty(), matched.toString())
            assertEquals(SzRecordKey(matched.dataSourceCode, matched.recordId), matched.recordKey)

            val related = assertNotNull(entity.relatedEntities).first { it.matchLevelCode == "POSSIBLY_SAME" }
            assertTrue(related.entityId > 0)
            assertFalse(related.entityName.isNullOrEmpty(), related.toString())
            assertFalse(related.matchKey.isNullOrEmpty(), related.toString())
            assertFalse(related.resolutionRuleCode.isNullOrEmpty(), related.toString())
            assertEquals(0, related.isDisclosed)
            assertEquals(0, related.isAmbiguous)
            assertFalse(related.recordSummary.isNullOrEmpty(), related.toString())

            assertRoundTrips(SzEntityResponse.serializer(), entity)
        }
    }

    @Test
    fun `test_feature IDs decode from engine output`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val flags = SzFlags.ENTITY_DEFAULT_FLAGS or SzFlags.ENTITY_INCLUDE_ALL_FEATURES
            val entityJson = fixture.engine.getEntity("CUSTOMERS", "1001", flags)
            // A runtime that reports feature references (*_FEAT_REF) instead has no IDs to decode.
            assumeTrue("\"LIB_FEAT_ID\"" in entityJson, "this runtime reports no LIB_FEAT_ID")
            val entity = SzJson.decode(SzEntityResponse.serializer(), entityJson)
            val name = assertNotNull(entity.resolvedEntity.features?.get("NAME")).first()
            assertNotNull(name.libraryFeatureId)
            assertNotNull(assertNotNull(name.values).first().libraryFeatureId)

            val query = person("Robert Smith", mapOf("DATE_OF_BIRTH" to "12/11/1978"))
            val search = fixture.engine.searchByAttributes(query, SzSearchResponse.serializer())
            val match = search.resolvedEntities.first().matchInfo
            val score = assertNotNull(match.featureScores?.get("NAME")).first()
            assertNotNull(score.inboundFeatureId)
            assertNotNull(score.candidateFeatureId)
            val keys = assertNotNull(match.candidateKeys).values.flatten()
            assertNotNull(keys.first().featureId)
        }
    }

    @Test
    fun `test_with-info models decode from record mutations`() {
        EngineFixture().use { fixture ->
            val added = fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"), SzWithInfoResponse.serializer())
            assertEquals("CUSTOMERS", added.dataSourceCode)
            assertEquals("1", added.recordId)
            val reference = SzEntityReference(fixture.entityId("CUSTOMERS", "1"))
            assertEquals(SzWithInfoResponse("CUSTOMERS", "1", listOf(reference)), added)
            assertRoundTrips(SzWithInfoResponse.serializer(), added)
        }
    }

    @Test
    fun `test_null-as-empty lists decode null, absent and present values`() {
        val nullInfo = json.decodeFromString(SzWithInfoResponse.serializer(), """{"AFFECTED_ENTITIES":null}""")
        assertEquals(SzWithInfoResponse(), nullInfo)
        val absent = json.decodeFromString(SzWithInfoResponse.serializer(), """{"DATA_SOURCE":"CUSTOMERS"}""")
        assertEquals(emptyList(), absent.affectedEntities)
        assertNull(absent.recordId)
        val present = json.decodeFromString(SzWithInfoResponse.serializer(), """{"AFFECTED_ENTITIES":[{"ENTITY_ID":3}]}""")
        assertEquals(listOf(SzEntityReference(3)), present.affectedEntities)

        assertEquals(SzSearchResponse(), json.decodeFromString(SzSearchResponse.serializer(), """{"RESOLVED_ENTITIES":null}"""))
        assertEquals(SzWhyResponse(), json.decodeFromString(SzWhyResponse.serializer(), """{"WHY_RESULTS":null,"ENTITIES":null}"""))

        val serializer = NullAsEmptyListSerializer(SzEntityReference.serializer())
        assertTrue(serializer.descriptor.isNullable)
        assertEquals("""[{"ENTITY_ID":4}]""", json.encodeToString(serializer, listOf(SzEntityReference(4))))
        // A non-list value is a decoding failure, not an empty list.
        assertFailsWith<SerializationException> {
            json.decodeFromString(SzWithInfoResponse.serializer(), """{"AFFECTED_ENTITIES":7}""")
        }
    }

    @Test
    fun `test_search models decode from searchByAttributes`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val query = person("Robert Smith", mapOf("DATE_OF_BIRTH" to "12/11/1978"))
            val search = fixture.engine.searchByAttributes(query, SzSearchResponse.serializer())
            val best = search.resolvedEntities.first()
            assertEquals(fixture.entityId("CUSTOMERS", "1001"), best.entity.resolvedEntity.entityId)
            val match = best.matchInfo
            assertEquals("RESOLVED", match.matchLevelCode)
            assertFalse(match.matchKey.isNullOrEmpty(), match.toString())
            assertFalse(match.resolutionRuleCode.isNullOrEmpty(), match.toString())

            val score = assertNotNull(match.featureScores?.get("NAME")).first()
            assertFalse(score.inboundFeatureDescription.isNullOrEmpty(), score.toString())
            assertFalse(score.candidateFeatureDescription.isNullOrEmpty(), score.toString())
            assertEquals(100, score.score)
            assertFalse(score.scoreBucket.isNullOrEmpty(), score.toString())
            assertFalse(score.scoreBehavior.isNullOrEmpty(), score.toString())
            assertFalse(score.additionalScores.isNullOrEmpty(), score.toString())

            val key = assertNotNull(match.candidateKeys).values.flatten().first()
            assertFalse(key.featureDescription.isNullOrEmpty(), key.toString())

            assertRoundTrips(SzSearchResponse.serializer(), search)
        }
    }

    @Test
    fun `test_why models decode from whyRecords and whyEntities`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val records = fixture.engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "1002", SzWhyResponse.serializer())
            val result = records.whyResults.first()
            assertNotNull(result.internalId?.intValue)
            assertNotNull(result.internalId2?.intValue)
            assertNotNull(result.entityId2)
            assertEquals(listOf(SzRecordKey.of("CUSTOMERS", "1001")), result.focusRecords)
            assertEquals(listOf(SzRecordKey.of("CUSTOMERS", "1002")), result.focusRecords2)
            assertFalse(result.matchInfo.whyKey.isNullOrEmpty(), result.toString())
            assertFalse(result.matchInfo.whyResolutionRuleCode.isNullOrEmpty(), result.toString())
            // Usage types appear where the compared features have them: 1001's HOME phone, 1002's MOBILE.
            val phone = assertNotNull(result.matchInfo.featureScores?.get("PHONE")).first()
            assertEquals("HOME", phone.inboundFeatureUsageType)
            assertEquals("MOBILE", phone.candidateFeatureUsageType)
            assertTrue(records.entities.isNotEmpty())
            assertRoundTrips(SzWhyResponse.serializer(), records)

            val first = fixture.entityId("CUSTOMERS", "1001")
            val other = fixture.entityId("WATCHLIST", "1006")
            assertRoundTrips(SzWhyResponse.serializer(), fixture.engine.whyEntities(first, other, SzWhyResponse.serializer()))
        }
    }

    @Test
    fun `test_product and configuration models decode`() {
        EngineFixture().use { fixture ->
            val product = fixture.environment.getProduct()
            val version = product.getVersion(SzVersionInfo.serializer())
            assertTrue(version.buildDate.isNotEmpty() && version.buildNumber.isNotEmpty(), version.toString())
            val schema = assertNotNull(version.schemaVersion)
            assertTrue(schema.minimumRequiredSchemaVersion.isNotEmpty() && schema.maximumRequiredSchemaVersion.isNotEmpty())
            assertRoundTrips(SzVersionInfo.serializer(), version)

            val license = product.getLicense(SzLicenseInfo.serializer())
            assertFalse(license.licenseType.isNullOrEmpty(), license.toString())
            assertFalse(license.issueDate.isNullOrEmpty(), license.toString())
            assertFalse(license.expireDate.isNullOrEmpty(), license.toString())
            // The engine reports every license field, empty when it does not apply.
            for (field in listOf(license.customer, license.contract, license.licenseLevel, license.billing)) {
                assertNotNull(field, license.toString())
            }
            assertRoundTrips(SzLicenseInfo.serializer(), license)

            val configManager = fixture.environment.getConfigManager()
            val registry = configManager.getConfigRegistry(SzConfigRegistry.serializer())
            assertEquals(fixture.repository.defaultConfigId, registry.configs.single().configId)
            assertRoundTrips(SzConfigRegistry.serializer(), registry)

            val dataSources = configManager.createConfig().getDataSourceRegistry(SzDataSourceRegistry.serializer())
            assertEquals(listOf("TEST", "SEARCH"), dataSources.dataSourceCodes)
            assertRoundTrips(SzDataSourceRegistry.serializer(), dataSources)
        }
    }

    @Test
    fun `test_record keys order, print and serialize`() {
        val keys = listOf(SzRecordKey.of("WATCHLIST", "1"), SzRecordKey.of("CUSTOMERS", "2"), SzRecordKey.of("CUSTOMERS", "10"))
        assertEquals(listOf("CUSTOMERS:10", "CUSTOMERS:2", "WATCHLIST:1"), keys.sorted().map { it.toString() })
        val encoded = json.encodeToString(SzRecordKey.serializer(), keys[0])
        assertEquals("""{"DATA_SOURCE":"WATCHLIST","RECORD_ID":"1"}""", encoded)
        assertEquals(keys[0], json.decodeFromString(SzRecordKey.serializer(), encoded))
    }

    @Test
    fun `test_internal IDs compare by form and value`() {
        val number = SzInternalId(1001L)
        assertEquals(SzInternalId(1001L), number)
        assertEquals(SzInternalId(1001L).hashCode(), number.hashCode())
        // A string of digits stays a string ID, distinct from the integer ID.
        val digits = json.decodeFromString(SzInternalIdSerializer, "\"1001\"")
        assertNull(digits.intValue)
        assertEquals("1001", digits.rawValue)
        assertNotEquals(number, digits)
        assertNotEquals(number.hashCode(), digits.hashCode())
        assertNotEquals<Any>(number, "1001")
        assertEquals("\"1001\"", json.encodeToString(SzInternalIdSerializer, digits))
        assertEquals("1001", json.encodeToString(SzInternalIdSerializer, number))
    }

    @Test
    fun `test_internal ID decoding rejects values that are neither integers nor strings`() {
        for (bad in listOf("true", "1.5", "null", "[1]", """{"ID":1}""")) {
            assertFailsWith<SerializationException>(bad) { json.decodeFromString(SzInternalIdSerializer, bad) }
        }
        // Through a typed call (an entity document is an object) the failure is an SzJsonException.
        EngineFixture().use { fixture ->
            fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"))
            val error =
                assertFailsWith<SzJsonException> {
                    fixture.engine.getEntity("CUSTOMERS", "1", SzInternalIdSerializer)
                }
            assertTrue(error.message.orEmpty().contains("INTERNAL_ID"), error.message)
        }
    }

    @Test
    fun `test_internal ID decodes from a non-JSON decoder as a string`() {
        val id = SzInternalIdSerializer.deserialize(StringDecoder("7:AB"))
        assertEquals(SzInternalId("7:AB"), id)
        assertNull(id.intValue)
    }

    @Test
    fun `test_the SDK's JSON format ignores fields the models do not have`() {
        val reference = SzJson.format.decodeFromString(SzEntityReference.serializer(), """{"ENTITY_ID":1,"NEW_FIELD":2}""")
        assertEquals(SzEntityReference(1), reference)
    }

    @Test
    fun `test_typed decoding wraps a deserializer's argument failure`() {
        EngineFixture().use { fixture ->
            fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"))
            val error =
                assertFailsWith<SzJsonException> {
                    fixture.engine.getEntity("CUSTOMERS", "1", RequiringNegativeEntityId)
                }
            assertTrue(error.cause is IllegalArgumentException, error.toString())
        }
    }

    /** A user deserializer whose validation fails with [IllegalArgumentException], as `require` does. */
    private object RequiringNegativeEntityId : DeserializationStrategy<SzEntityResponse> {
        override val descriptor: SerialDescriptor = SzEntityResponse.serializer().descriptor

        override fun deserialize(decoder: Decoder): SzEntityResponse =
            SzEntityResponse.serializer().deserialize(decoder).also { entity ->
                require(entity.resolvedEntity.entityId < 0) { "entity ID ${entity.resolvedEntity.entityId} is not negative" }
            }
    }

    /** A single-string, non-JSON decoder: the form a non-JSON format gives the serializer. */
    @OptIn(ExperimentalSerializationApi::class)
    private class StringDecoder(
        private val value: String,
    ) : AbstractDecoder() {
        override val serializersModule: SerializersModule = EmptySerializersModule()

        override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE

        override fun decodeString(): String = value
    }

    @Test
    fun `test_list serializer of models encodes engine export lines`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val entities = fixture.engine.exportJsonEntityReport(SzEntityResponse.serializer())
            assertRoundTrips(ListSerializer(SzEntityResponse.serializer()), entities)
        }
    }
}
