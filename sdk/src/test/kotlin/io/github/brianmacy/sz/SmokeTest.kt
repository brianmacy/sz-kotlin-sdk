package io.github.brianmacy.sz

import io.github.brianmacy.sz.testsupport.TestRepository
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun `test_end to end through the JNI library`() {
        val repository = TestRepository()
        try {
            repository.makeEnvironment().use { environment ->
                assertTrue(environment.getProduct().getVersion().contains("VERSION"))
                val engine = environment.getEngine()
                assertNull(engine.addRecord("CUSTOMERS", "1", """{"NAME_FULL":"Robert Smith","DATE_OF_BIRTH":"1978-12-11"}"""))
                val info = engine.addRecord("CUSTOMERS", "2", """{"NAME_FULL":"Bob Smith","DATE_OF_BIRTH":"1978-12-11"}""", SzFlags.WITH_INFO)
                assertTrue(info!!.contains("AFFECTED_ENTITIES"), info)
                val entity = engine.getEntity("CUSTOMERS", "1")
                assertTrue(entity.contains("\"RECORD_ID\":\"2\""), entity)
                val error = assertFailsWith<SzNotFoundException> { engine.getRecord("CUSTOMERS", "missing") }
                assertEquals(33L, error.errorCode, error.toString())
                assertEquals(repository.defaultConfigId, environment.getActiveConfigId())
            }
        } finally {
            repository.remove()
        }
    }
}
