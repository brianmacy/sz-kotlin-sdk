// Why: explain why two records resolved together, and why two entities are related but not
// resolved.
//
// Run: ./gradlew -q :examples:runWhy

package io.github.brianmacy.sz.examples

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.getEntity
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzWhyResponse
import io.github.brianmacy.sz.models.SzWhyResult
import io.github.brianmacy.sz.testsupport.ExampleFailure
import io.github.brianmacy.sz.testsupport.ExampleRepository
import io.github.brianmacy.sz.testsupport.Truthset
import io.github.brianmacy.sz.whyEntities
import io.github.brianmacy.sz.whyRecords
import kotlin.system.exitProcess

/** The match level, why key and best score per feature of a why result. */
private fun describe(result: SzWhyResult): String {
    val info = result.matchInfo
    val scores =
        info.featureScores
            .orEmpty()
            .toSortedMap()
            .mapNotNull { (feature, scores) -> scores.firstOrNull()?.score?.let { "$feature=$it" } }
    return "${info.matchLevelCode ?: "?"} via ${info.whyKey ?: "?"} [${scores.joinToString(", ")}]"
}

private fun run() {
    val repository = ExampleRepository()
    try {
        SzCoreEnvironment(instanceName = "example_why", settings = repository.settings).use { environment ->
            val engine = environment.getEngine()
            Truthset.load(engine)

            val records = engine.whyRecords("CUSTOMERS", "1001", "CUSTOMERS", "1002", SzWhyResponse.serializer())
            val recordsWhy =
                records.whyResults.firstOrNull()
                    ?: throw ExampleFailure("no why result for CUSTOMERS 1001 / 1002")
            println("Why CUSTOMERS 1001 and 1002: ${describe(recordsWhy)}")

            val entity = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer())
            val entityId = entity.resolvedEntity.entityId
            val related =
                entity.relatedEntities?.firstOrNull()
                    ?: throw ExampleFailure("entity $entityId has no related entities")
            val entities = engine.whyEntities(entityId, related.entityId, SzWhyResponse.serializer())
            val entitiesWhy =
                entities.whyResults.firstOrNull()
                    ?: throw ExampleFailure("no why result for the related entities")
            println("Why entity $entityId and ${related.entityId}: ${describe(entitiesWhy)}")

            // The full explanation is the engine's JSON; the typed models cover the common parts.
            val how = engine.howEntity(entityId)
            println("How entity $entityId was built: ${how.toByteArray().size} bytes of JSON")
        }
    } finally {
        repository.remove()
    }
}

fun main() {
    try {
        run()
    } catch (e: Exception) {
        System.err.println("example_why failed: $e")
        exitProcess(1)
    }
}
