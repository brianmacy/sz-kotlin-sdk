package io.github.brianmacy.sz

import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzInternalId
import io.github.brianmacy.sz.models.SzWhyResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SzInternalIdTest {
    @Test
    fun `test_decode accepts integer and string INTERNAL_ID values and round-trips them`() {
        val line =
            """{"RESOLVED_ENTITY":{"ENTITY_ID":7,"RECORDS":[""" +
                """{"DATA_SOURCE":"CUSTOMERS","RECORD_ID":"1","INTERNAL_ID":1001},""" +
                """{"DATA_SOURCE":"CUSTOMERS","RECORD_ID":"2","INTERNAL_ID":"1002:4B73984B1A8BBCDA"}]}}"""
        val entity = Json.decodeFromString(SzEntityResponse.serializer(), line)
        val ids = assertNotNull(entity.resolvedEntity.records).map { it.internalId }
        assertEquals(listOf(SzInternalId(1001L), SzInternalId("1002:4B73984B1A8BBCDA")), ids)
        assertEquals(1001L, ids[0]?.intValue)
        assertEquals("1001", ids[0]?.rawValue)
        assertEquals("1001", ids[0].toString())
        assertNull(ids[1]?.intValue)
        assertEquals("1002:4B73984B1A8BBCDA", ids[1].toString())

        val encoded = Json.encodeToString(ListSerializer(SzInternalId.serializer().nullable), ids)
        assertEquals("""[1001,"1002:4B73984B1A8BBCDA"]""", encoded)

        val why =
            """{"WHY_RESULTS":[{"INTERNAL_ID":"5:AA","ENTITY_ID":1,"INTERNAL_ID_2":6,"ENTITY_ID_2":2,"MATCH_INFO":{}}]}"""
        val result = Json.decodeFromString(SzWhyResponse.serializer(), why).whyResults.first()
        assertEquals(SzInternalId("5:AA"), result.internalId)
        assertEquals(SzInternalId(6L), result.internalId2)
        assertFailsWith<SerializationException> { Json.decodeFromString(SzInternalId.serializer(), "true") }
    }
}
