package io.github.brianmacy.sz

import io.github.brianmacy.sz.models.SzWithInfoResponse
import io.github.brianmacy.sz.testsupport.Truthset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A contact as an app would model it, encoded with Senzing attribute names. */
@Serializable
private data class Contact(
    @SerialName("NAME_FULL") val fullName: String,
    @SerialName("EMAIL_ADDRESS") val email: String? = null,
    @SerialName("PHONE_NUMBER") val phone: String? = null,
)

class SzEngineWriteTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `test_addRecord returns info only with WITH_INFO`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            assertNull(engine.addRecord("CUSTOMERS", "1", person("Ann Lee")))
            val info =
                engine.addRecord(
                    "CUSTOMERS",
                    "2",
                    person("Ann Lee", mapOf("EMAIL_ADDRESS" to "ann@example.com")),
                    SzFlags.WITH_INFO,
                )
            assertTrue(assertNotNull(info).contains("\"RECORD_ID\":\"2\""), info)

            val typed =
                engine.addRecord(
                    "CUSTOMERS",
                    "3",
                    person("Ann Lee", mapOf("EMAIL_ADDRESS" to "ann@example.com")),
                    SzWithInfoResponse.serializer(),
                )
            assertEquals("CUSTOMERS", typed.dataSourceCode)
            assertEquals("3", typed.recordId)
            assertEquals(1, typed.affectedEntities.size)
            assertEquals(fixture.entityId("CUSTOMERS", "2"), fixture.entityId("CUSTOMERS", "3"))
        }
    }

    @Test
    fun `test_addRecord encodes a serializable record`() {
        EngineFixture().use { fixture ->
            val contact = Contact(fullName = "Robert Smith", email = "bob@example.com", phone = "702-555-0100")
            assertNull(fixture.engine.addRecord("CUSTOMERS", "C1", contact, Contact.serializer()))
            val record = fixture.engine.getRecord("CUSTOMERS", "C1")
            assertTrue(record.contains("bob@example.com"), record)
            val withInfo = fixture.engine.addRecord("CUSTOMERS", "C3", Contact("Robert Smith"), Contact.serializer(), SzFlags.WITH_INFO)
            assertTrue(assertNotNull(withInfo).contains("AFFECTED_ENTITIES"), withInfo)
            assertFailsWith<SzJsonException> {
                fixture.engine.addRecord(
                    "CUSTOMERS",
                    "C2",
                    mapOf("N" to Double.NaN),
                    MapSerializer(String.serializer(), Double.serializer()),
                )
            }
        }
    }

    @Test
    fun `test_addRecord rejects bad input`() {
        EngineFixture().use { fixture ->
            val unknown = assertFailsWith<SzUnknownDataSourceException> { fixture.engine.addRecord("NOPE", "1", person("A B")) }
            assertEquals(2207L, unknown.errorCode, unknown.toString())
            assertFailsWith<SzBadInputException> { fixture.engine.addRecord("CUSTOMERS", "1", "{bad") }
            assertFailsWith<SzBadInputException> { fixture.engine.addRecord("CUSTOMERS", "1", "{bad", SzFlags.WITH_INFO) }
        }
    }

    @Test
    fun `test_deleteRecord with and without info`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            for (id in listOf("1", "2", "3")) engine.addRecord("CUSTOMERS", id, person("Zed $id"))
            assertNull(engine.deleteRecord("CUSTOMERS", "1"))
            val info = engine.deleteRecord("CUSTOMERS", "2", SzFlags.WITH_INFO)
            assertTrue(assertNotNull(info).contains("AFFECTED_ENTITIES"), info)
            val typed = engine.deleteRecord("CUSTOMERS", "3", SzWithInfoResponse.serializer())
            assertEquals("3", typed.recordId)
            assertFailsWith<SzNotFoundException> { engine.getRecord("CUSTOMERS", "1") }
            assertFailsWith<SzUnknownDataSourceException> { engine.deleteRecord("NOPE", "1") }
            assertFailsWith<SzUnknownDataSourceException> { engine.deleteRecord("NOPE", "1", SzFlags.WITH_INFO) }
        }
    }

    @Test
    fun `test_reevaluate records and entities`() {
        EngineFixture(loadTruthset = true).use { fixture ->
            val engine = fixture.engine
            val entityId = fixture.entityId("CUSTOMERS", "1001")
            assertNull(engine.reevaluateRecord("CUSTOMERS", "1001"))
            val recordInfo = engine.reevaluateRecord("CUSTOMERS", "1001", SzFlags.WITH_INFO)
            assertTrue(assertNotNull(recordInfo).contains("\"RECORD_ID\":\"1001\""), recordInfo)
            assertNull(engine.reevaluateEntity(entityId))
            val entityInfo = assertNotNull(engine.reevaluateEntity(entityId, SzFlags.WITH_INFO))
            val decoded = json.decodeFromString(SzWithInfoResponse.serializer(), entityInfo)
            assertNull(decoded.recordId)
            assertTrue(entityId in decoded.affectedEntities.map { it.entityId }, entityInfo)
            // Reevaluating a record that does not exist is a no-op in the engine.
            assertNull(engine.reevaluateRecord("CUSTOMERS", "NOPE"))
            assertFailsWith<SzUnknownDataSourceException> { engine.reevaluateRecord("NOPE", "1") }
            assertFailsWith<SzUnknownDataSourceException> { engine.reevaluateRecord("NOPE", "1", SzFlags.WITH_INFO) }
        }
    }

    @Test
    fun `test_redo records are counted, fetched and processed`() {
        EngineFixture().use { fixture ->
            val engine = fixture.engine
            for (record in Truthset.allRecords()) engine.addRecord(record.dataSourceCode, record.recordId, record.definition)
            assertTrue(engine.countRedoRecords() > 0)
            val first = assertNotNull(engine.getRedoRecord())
            val info = engine.processRedoRecord(first, SzFlags.WITH_INFO)
            assertTrue(assertNotNull(info).contains("AFFECTED_ENTITIES"), info)
            while (true) assertNull(engine.processRedoRecord(engine.getRedoRecord() ?: break))
            assertEquals(0L, engine.countRedoRecords())
            assertNull(engine.getRedoRecord())
            assertFailsWith<SzBadInputException> { engine.processRedoRecord("{bad") }
            assertFailsWith<SzBadInputException> { engine.processRedoRecord("{bad", SzFlags.WITH_INFO) }
        }
    }

    @Test
    fun `test_primeEngine and getStats`() {
        EngineFixture().use { fixture ->
            fixture.engine.primeEngine()
            fixture.engine.addRecord("CUSTOMERS", "1", person("Ann Lee"))
            val stats = fixture.engine.getStats()
            assertTrue(stats.contains("workload"), stats)
        }
    }
}
