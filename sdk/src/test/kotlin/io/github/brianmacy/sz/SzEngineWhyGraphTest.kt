package io.github.brianmacy.sz

import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzWhyResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SzEngineWhyGraphTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test_whyRecords explains a resolution`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val why = fixture.engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "1002", SzWhyResponse.serializer())
            val result = why.whyResults.first()
            assertEquals("RESOLVED", result.matchInfo.matchLevelCode)
            assertTrue(result.matchInfo.whyKey?.contains("NAME") == true, result.matchInfo.toString())
            assertTrue(result.focusRecords?.contains(SzRecordKey.of("CUSTOMERS", "1001")) == true, result.toString())
            assertTrue(result.focusRecords2?.contains(SzRecordKey.of("CUSTOMERS", "1002")) == true, result.toString())
            val dobScores = assertNotNull(result.matchInfo.featureScores?.get("DOB"))
            assertNotNull(dobScores.first().scoreBucket)
            assertTrue(why.entities.isNotEmpty())
            val raw = fixture.engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "1002")
            assertTrue(raw.contains("WHY_RESULTS"), raw)
            assertFailsWith<SzNotFoundException> { fixture.engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "NOPE") }
        }
    }

    @Test
    fun `test_whyEntities and whyRecordInEntity`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val entity = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
            val related = assertNotNull(entity.relatedEntities).first()
            val why = engine.whyEntities(entity.resolvedEntity.entityId, related.entityId, SzWhyResponse.serializer())
            val result = why.whyResults.first()
            assertEquals(entity.resolvedEntity.entityId, result.entityId)
            assertEquals(related.entityId, result.entityId2)
            assertEquals(related.matchLevelCode, result.matchInfo.matchLevelCode)
            assertTrue(engine.whyEntities(result.entityId, related.entityId).contains("WHY_RESULTS"))

            val inEntity = engine.whyRecordInEntity("CUSTOMERS", "1001", SzWhyResponse.serializer())
            assertEquals(entity.resolvedEntity.entityId, inEntity.whyResults.first().entityId)
            assertTrue(engine.whyRecordInEntity("CUSTOMERS", "1001").contains("WHY_RESULTS"))
            assertFailsWith<SzNotFoundException> { engine.whyEntities(999_998L, 999_999L) }
        }
    }

    @Test
    fun `test_whySearch and howEntity`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val entityId = fixture.entityId("CUSTOMERS", "1001")
            val query = person("Robert Smith", mapOf("DATE_OF_BIRTH" to "12/11/1978"))
            assertTrue(engine.whySearch(query, entityId).contains("WHY_RESULTS"))
            assertTrue(engine.whySearch(query, entityId, searchProfile = "SEARCH").contains("WHY_RESULTS"))
            assertTrue(engine.whySearch(query, entityId, searchProfile = "").contains("WHY_RESULTS"))
            assertTrue(engine.howEntity(entityId).contains("RESOLUTION_STEPS"))
            assertFailsWith<SzNotFoundException> { engine.howEntity(999_999L) }
        }
    }

    @Test
    fun `test_getVirtualEntity combines records without writing`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val virtual =
                fixture.engine.getVirtualEntity(setOf(SzRecordKey.of("CUSTOMERS", "1001"), SzRecordKey.of("CUSTOMERS", "1002")))
            val entity = json.decodeFromString(SzEntityResponse.serializer(), virtual)
            assertEquals(2, entity.resolvedEntity.records?.size)
            assertFailsWith<SzNotFoundException> { fixture.engine.getVirtualEntity(setOf(SzRecordKey.of("CUSTOMERS", "NOPE"))) }
        }
    }

    @Test
    fun `test_findPath by entity and by record, with avoids and required sources`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val start = fixture.entityId("CUSTOMERS", "1001")
            val end = fixture.entityId("WATCHLIST", "1006")
            assertTrue(engine.findPath(start, end, 3).contains("ENTITY_PATHS"))
            assertTrue(engine.findPath(start, end, 3, avoidEntityIds = setOf(999_999L)).contains("ENTITY_PATHS"))
            assertTrue(engine.findPath(start, end, 3, requiredDataSources = setOf("WATCHLIST")).contains("ENTITY_PATHS"))
            val byRecord = engine.findPath("CUSTOMERS", "1001", "WATCHLIST", "1006", 3)
            assertTrue(byRecord.contains("\"START_ENTITY_ID\":$start"), byRecord)
            val byRecordAvoid =
                engine.findPath(
                    "CUSTOMERS",
                    "1001",
                    "WATCHLIST",
                    "1006",
                    3,
                    avoidRecordKeys = setOf(SzRecordKey.of("CUSTOMERS", "1005")),
                )
            assertTrue(byRecordAvoid.contains("ENTITY_PATHS"), byRecordAvoid)
            val byRecordRequired =
                engine.findPath("CUSTOMERS", "1001", "WATCHLIST", "1006", 3, requiredDataSources = setOf("WATCHLIST"))
            assertTrue(byRecordRequired.contains("ENTITY_PATHS"), byRecordRequired)
            assertFailsWith<SzNotFoundException> { engine.findPath(start, 999_999L, 2) }
        }
    }

    @Test
    fun `test_findNetwork and findInterestingEntities`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val first = fixture.entityId("CUSTOMERS", "1001")
            val second = fixture.entityId("WATCHLIST", "1006")
            val byEntity = engine.findNetwork(setOf(first, second), 2, 1, 10)
            assertTrue(byEntity.contains("ENTITY_NETWORK_LINKS"), byEntity)
            val byRecord = engine.findNetworkByRecordKeys(setOf(SzRecordKey.of("CUSTOMERS", "1001")), 1, 1, 10)
            assertTrue(byRecord.contains("ENTITY_NETWORK_LINKS"), byRecord)
            assertTrue(engine.findInterestingEntities(first).contains("INTERESTING_ENTITIES"))
            assertTrue(engine.findInterestingEntities("CUSTOMERS", "1001").contains("INTERESTING_ENTITIES"))
            assertFailsWith<SzNotFoundException> { engine.findNetwork(setOf(999_999L), 1, 1, 1) }
            assertFailsWith<SzNotFoundException> { engine.findInterestingEntities(999_999L) }
        }
    }
}
