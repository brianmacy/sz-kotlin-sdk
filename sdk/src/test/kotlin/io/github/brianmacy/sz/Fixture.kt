package io.github.brianmacy.sz

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.testsupport.TestRepository
import io.github.brianmacy.sz.testsupport.Truthset

/**
 * A fresh repository and environment for one test. No mocks: the real engine over a new
 * SQLite repository, deleted again by [close]. The native engine is a process-wide singleton,
 * so tests run one at a time (JUnit's default) and each closes its fixture.
 *
 * @param loadTruthset Whether to load the whole truth set (and process its redo records).
 */
class EngineFixture(
    loadTruthset: Boolean = false,
    dataSources: List<String> = Truthset.dataSources,
) : AutoCloseable {
    val repository = TestRepository(dataSources)
    val environment: SzCoreEnvironment = repository.makeEnvironment()
    val engine: SzEngine = environment.getEngine()

    init {
        if (loadTruthset) Truthset.load(engine)
    }

    override fun close() {
        environment.destroy()
        repository.remove()
    }

    /** The entity ID of the entity that contains a record. */
    fun entityId(
        dataSourceCode: String,
        recordId: String,
    ): Long = engine.getEntity(dataSourceCode, recordId, SzEntityResponse.serializer()).resolvedEntity.entityId
}

/** A record definition with a full name and optional extra attributes (keys sorted). */
fun person(
    name: String,
    extra: Map<String, String> = emptyMap(),
): String =
    (mapOf("NAME_FULL" to name) + extra).toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":\"$value\""
    }
