package io.github.brianmacy.sz

import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.testsupport.TestRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An external fixture set of synthetic records, loaded through this SDK into a fresh repository,
 * resolved, and checked against its planted truth. The file names are in `end-to-end.yaml`; the
 * directory comes from the `resolution.fixtures.dir` system property, and without it the test is
 * skipped.
 */
class EndToEndTest {
    @Test
    fun `test_synthetic records resolve to the planted truth`() {
        val fixturesDir = System.getProperty(FIXTURES_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(fixturesDir != null, "$FIXTURES_PROPERTY is not set: no resolution fixtures")
        val fixtures = checkNotNull(fixturesDir)
        val files = Yaml().load<Map<String, String>>(checkNotNull(javaClass.getResource("/end-to-end.yaml")).readText())
        val records =
            fixtures
                .resolve(files.getValue("records"))
                .readLines()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
        val expected = Yaml().load<Map<String, Any>>(fixtures.resolve(files.getValue("expected")).readText())
        val repository = TestRepository(dataSources = records.map { it.string("DATA_SOURCE") }.distinct())
        try {
            repository.makeEnvironment("sz-kotlin-sdk-end-to-end").use { environment ->
                assertTrue(environment.getProduct().getVersion().contains("VERSION"))
                val engine = environment.getEngine()
                for (record in records) engine.addRecord(record.string("DATA_SOURCE"), record.string("RECORD_ID"), record.toString())
                while (true) engine.processRedoRecord(engine.getRedoRecord() ?: break)
                val entities = engine.exportJsonEntityReport(SzEntityResponse.serializer())
                check(Resolution(entities), expected)
                assertEquals(expected["records"], entities.sumOf { it.recordIds().size })
            }
        } finally {
            repository.remove()
        }
    }

    /** Groups (entities of more than one record) and possible matches, as record-ID sets. */
    private class Resolution(
        entities: List<SzEntityResponse>,
    ) {
        private val recordsById = entities.associate { it.resolvedEntity.entityId to it.recordIds() }
        val groups = recordsById.values.filter { it.size > 1 }.toSet()
        val possible =
            entities
                .flatMap { entity ->
                    entity.relatedEntities
                        .orEmpty()
                        .filter { it.matchLevelCode == "POSSIBLY_SAME" }
                        .map { entity.recordIds() + recordsById.getValue(it.entityId) }
                }.toSet()
    }

    private fun check(
        results: Resolution,
        expected: Map<String, Any>,
    ) {
        fun pairs(key: String) = (expected.getValue(key) as List<*>).map { (it as List<*>).map(Any?::toString).toSet() }
        val groups = pairs("groups")
        val linked = pairs("linked")
        for (group in groups) assertTrue(group in results.groups, "expected group $group; got ${results.groups}")
        val unexpected = results.groups - groups.toSet() - linked.toSet()
        assertTrue(unexpected.isEmpty(), "unexpected groups $unexpected")
        for (pair in linked) {
            assertTrue(pair in results.groups || pair in results.possible, "$pair neither resolved nor possible")
        }
        for (pair in pairs("possible_matches")) {
            assertTrue(pair in results.possible, "$pair not a possible match; got ${results.possible}")
        }
        for (pair in pairs("never_together")) {
            assertTrue(results.groups.none { it.containsAll(pair) }, "$pair resolved together")
        }
    }

    private companion object {
        const val FIXTURES_PROPERTY = "resolution.fixtures.dir"

        fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

        fun SzEntityResponse.recordIds(): Set<String> =
            resolvedEntity.records
                .orEmpty()
                .map { it.recordId }
                .toSet()
    }
}
