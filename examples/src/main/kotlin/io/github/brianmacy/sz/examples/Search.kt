// Search: load the truth set and search it by attributes.
//
// Run: ./gradlew -q :examples:runSearch

package io.github.brianmacy.sz.examples

import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.models.SzSearchResponse
import io.github.brianmacy.sz.searchByAttributes
import io.github.brianmacy.sz.testsupport.ExampleFailure
import io.github.brianmacy.sz.testsupport.ExampleRepository
import io.github.brianmacy.sz.testsupport.Truthset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/** Search attributes, encoded with Senzing attribute names. */
@Serializable
private data class SearchAttributes(
    @SerialName("NAME_FULL") val name: String,
    @SerialName("DATE_OF_BIRTH") val dateOfBirth: String? = null,
)

/** Omits absent attributes instead of sending them as null. */
private val json = Json { explicitNulls = false }

private fun run() {
    val repository = ExampleRepository()
    try {
        SzCoreEnvironment(instanceName = "example_search", settings = repository.settings).use { environment ->
            val engine = environment.getEngine()
            Truthset.load(engine)

            val query = json.encodeToString(SearchAttributes.serializer(), SearchAttributes("Robert Smith", "1978-12-11"))
            val response = engine.searchByAttributes(query, SzSearchResponse.serializer())
            println("Search $query found ${response.resolvedEntities.size} entities:")
            for (result in response.resolvedEntities) {
                val entity = result.entity.resolvedEntity
                val level = result.matchInfo.matchLevelCode ?: "?"
                val key = result.matchInfo.matchKey.orEmpty()
                println("  entity ${entity.entityId} ${entity.entityName ?: "?"}: $level $key")
            }
            val best = response.resolvedEntities.firstOrNull()?.matchInfo
            if (best?.matchLevelCode != "RESOLVED") throw ExampleFailure("expected a RESOLVED best match")
        }
    } finally {
        repository.remove()
    }
}

fun main() {
    try {
        run()
    } catch (e: Exception) {
        System.err.println("example_search failed: $e")
        exitProcess(1)
    }
}
