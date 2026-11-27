package io.github.brianmacy.sz

import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzSearchResponse
import io.github.brianmacy.sz.models.SzVersionInfo
import io.github.brianmacy.sz.testsupport.Truthset
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SzEngineReadTest {
    @Test
    fun `test_truth set resolves as its answer key says`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            // The truth set's answer key puts CUSTOMERS 1001-1004 in one entity.
            val entity = fixture.engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
            val keys = assertNotNull(entity.resolvedEntity.records).map { it.recordKey }.toSet()
            for (id in listOf("1001", "1002", "1003", "1004")) {
                assertTrue(SzRecordKey.of("CUSTOMERS", id) in keys, "$id in $keys")
            }
            assertEquals("Robert Smith", entity.resolvedEntity.entityName)
            val summary = assertNotNull(entity.resolvedEntity.recordSummary)
            assertTrue(summary.any { it.dataSourceCode == "CUSTOMERS" && it.recordCount == 4 }, summary.toString())
            val features = assertNotNull(entity.resolvedEntity.features)
            assertFalse(features["NAME"].isNullOrEmpty())
            assertFalse(features["NAME"]?.first()?.values.isNullOrEmpty())
            val related = assertNotNull(entity.relatedEntities)
            assertTrue(related.any { it.matchLevelCode == "POSSIBLY_SAME" }, related.toString())
        }
    }

    @Test
    fun `test_getEntity by ID, by record, and with flags`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val entityId = fixture.entityId("CUSTOMERS", "1001")
            val byId = engine.getEntity(entityId, SzEntityResponse.serializer())
            assertEquals(entityId, byId.resolvedEntity.entityId)
            val internalIds = assertNotNull(byId.resolvedEntity.records).mapNotNull { it.internalId }
            assertTrue(internalIds.isNotEmpty() && internalIds.all { it.intValue != null }, "integer IDs: $internalIds")
            assertTrue(engine.getEntity(entityId).contains("RESOLVED_ENTITY"))
            val brief = engine.getEntity(entityId, SzEntityResponse.serializer(), SzFlags.NO_FLAGS)
            assertNull(brief.resolvedEntity.records)
            assertNull(brief.resolvedEntity.entityName)
            assertTrue(engine.getEntity("CUSTOMERS", "1001").contains("1001"))
            val named = engine.getEntity("CUSTOMERS", "1001", SzFlags.ENTITY_INCLUDE_ENTITY_NAME)
            assertTrue(named.contains("Robert Smith"), named)

            val missing = assertFailsWith<SzNotFoundException> { engine.getEntity(999_999L) }
            assertEquals(37L, missing.errorCode, missing.toString())
            assertFailsWith<SzNotFoundException> { engine.getEntity("CUSTOMERS", "NOPE") }
            assertFailsWith<SzUnknownDataSourceException> { engine.getEntity("NOPE", "1") }
            assertFailsWith<SzJsonException> { engine.getEntity(entityId, SzVersionInfo.serializer()) }
        }
    }

    @Test
    fun `test_getRecord and getRecordPreview`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val record = fixture.engine.getRecord("CUSTOMERS", "1001")
            assertTrue(record.contains("JSON_DATA"), record)
            val bare = fixture.engine.getRecord("CUSTOMERS", "1001", SzFlags.NO_FLAGS)
            assertFalse(bare.contains("JSON_DATA"), bare)
            val preview = fixture.engine.getRecordPreview(person("Robert Smith"))
            assertTrue(preview.contains("FEATURES"), preview)
            assertFailsWith<SzBadInputException> { fixture.engine.getRecordPreview("{bad") }
        }
    }

    @Test
    fun `test_searchByAttributes with default and named profiles`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val query = person("Robert Smith", mapOf("DATE_OF_BIRTH" to "12/11/1978"))
            val results = engine.searchByAttributes(query, SzSearchResponse.serializer())
            val best = results.resolvedEntities.first()
            assertEquals(fixture.entityId("CUSTOMERS", "1001"), best.entity.resolvedEntity.entityId)
            assertEquals("RESOLVED", best.matchInfo.matchLevelCode)
            val nameScores = assertNotNull(best.matchInfo.featureScores?.get("NAME"))
            assertEquals(100, nameScores.first().score)
            assertFalse(best.matchInfo.candidateKeys.isNullOrEmpty())

            assertTrue(engine.searchByAttributes(query, searchProfile = "SEARCH").contains("RESOLVED_ENTITIES"))
            assertTrue(engine.searchByAttributes(query, searchProfile = "").contains("RESOLVED_ENTITIES"))
            val minimal = engine.searchByAttributes(query, flags = SzFlags.SEARCH_BY_ATTRIBUTES_MINIMAL_STRONG)
            assertTrue(minimal.contains("RESOLVED_ENTITIES"), minimal)
            val none = engine.searchByAttributes(person("Xq Zzyzx Vv"), SzSearchResponse.serializer())
            assertTrue(none.resolvedEntities.isEmpty())

            val bogus = assertFailsWith<SzBadInputException> { engine.searchByAttributes(query, searchProfile = "BOGUS") }
            assertEquals(88L, bogus.errorCode, bogus.toString())
        }
    }

    @Test
    fun `test_JSON export returns every entity and closes its handle`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val lines = engine.exportJsonEntityLines()
            val entities = engine.exportJsonEntityReport(SzEntityResponse.serializer())
            assertEquals(lines.size, entities.size)
            assertTrue(entities.isNotEmpty())
            val recordCount = entities.sumOf { it.resolvedEntity.records?.size ?: 0 }
            assertEquals(Truthset.allRecords().size, recordCount)

            val handle = engine.exportJsonEntityReport()
            assertNotNull(engine.fetchNext(handle))
            engine.closeExportReport(handle)
            assertFailsWith<SzJsonException> { engine.exportJsonEntityReport(SzVersionInfo.serializer()) }
        }
    }

    @Test
    fun `test_JSON export lines reports a read failure and suppresses the close failure`() {
        EngineFixture().use { fixture ->
            fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"))
            fixture.engine.addRecord("CUSTOMERS", "2", person("Zed Quinn"))
            // The real engine, with the environment destroyed after the first line: the next
            // read and the close both fail with the SDK's real destroyed-environment error.
            val engine =
                object : SzEngine by fixture.engine {
                    override fun fetchNext(exportHandle: SzExportHandle): String? =
                        fixture.engine.fetchNext(exportHandle).also { fixture.environment.destroy() }
                }
            val failure = assertFailsWith<SzEnvironmentDestroyedException> { engine.exportJsonEntityLines() }
            val suppressed = failure.suppressed.single()
            assertTrue(suppressed is SzEnvironmentDestroyedException, suppressed.toString())
        }
    }

    @Test
    fun `test_JSON export lines reports a close failure after a complete read`() {
        EngineFixture().use { fixture ->
            fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"))
            // The environment is destroyed once the report is exhausted, so only the close fails.
            val engine =
                object : SzEngine by fixture.engine {
                    override fun fetchNext(exportHandle: SzExportHandle): String? =
                        fixture.engine.fetchNext(exportHandle) ?: null.also { fixture.environment.destroy() }
                }
            val failure = assertFailsWith<SzEnvironmentDestroyedException> { engine.exportJsonEntityLines() }
            assertTrue(failure.suppressed.isEmpty())
            assertTrue(fixture.environment.isDestroyed)
        }
    }

    @Test
    fun `test_CSV export starts with a header row`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val handle = fixture.engine.exportCsvEntityReport("*")
            val header = assertNotNull(fixture.engine.fetchNext(handle))
            assertTrue(header.startsWith("RESOLVED_ENTITY_ID"), header)
            var rows = 0
            while (fixture.engine.fetchNext(handle) != null) rows++
            assertTrue(rows > 0)
            fixture.engine.closeExportReport(handle)
        }
    }
}
