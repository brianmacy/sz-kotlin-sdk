// AsyncEngine: load records concurrently from coroutines with SzAsyncEngine, then read the
// results with suspend functions and a Flow.
//
// Run: ./gradlew -q :examples:runAsyncEngine

package io.github.brianmacy.sz.examples

import io.github.brianmacy.sz.SzAsyncEngine
import io.github.brianmacy.sz.addRecord
import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.getEntity
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.testsupport.ExampleFailure
import io.github.brianmacy.sz.testsupport.ExampleRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.system.exitProcess

/** A contact as an app would model it, encoded with Senzing attribute names. */
@Serializable
private data class Contact(
    @SerialName("RECORD_ID") val id: String,
    @SerialName("NAME_FULL") val name: String,
    @SerialName("EMAIL_ADDRESS") val email: String? = null,
    @SerialName("PHONE_NUMBER") val phone: String? = null,
)

private val contacts =
    listOf(
        Contact("1", "Robert Smith", email = "bob@example.com", phone = "702-555-0100"),
        Contact("2", "Bob Smith", email = "bob@example.com"),
        Contact("3", "Rob Smith", phone = "702-555-0100"),
        Contact("4", "Jane Doe", email = "jane@example.com"),
    )

private suspend fun run() {
    val repository = ExampleRepository(dataSources = listOf("CONTACTS"))
    try {
        SzCoreEnvironment(instanceName = "example_async_engine", settings = repository.settings).use { environment ->
            val engine = SzAsyncEngine(environment.getEngine())

            // Each add suspends until a worker thread has run it; they run concurrently.
            coroutineScope {
                contacts
                    .map { contact ->
                        async { engine.addRecord("CONTACTS", contact.id, contact, Contact.serializer()) }
                    }.awaitAll()
            }
            val redo = engine.processRedoRecords()
            for (failure in redo.failed) println("Redo record failed: ${failure.error}")

            val groups = sortedMapOf<Long, MutableList<String>>()
            for (contact in contacts) {
                val entity = engine.getEntity("CONTACTS", contact.id, SzEntityResponse.serializer())
                groups.getOrPut(entity.resolvedEntity.entityId) { mutableListOf() } += contact.name
            }
            for ((entityId, names) in groups) println("Entity $entityId: ${names.joinToString(", ")}")
            if (groups.size >= contacts.size) throw ExampleFailure("expected some contacts to resolve together")
            println("${contacts.size} contacts are ${groups.size} people.")

            // Exports are Flows: the export handle closes when collection ends.
            val exported = engine.exportJsonEntityReport().count()
            if (exported < groups.size) throw ExampleFailure("exported $exported entities, expected at least ${groups.size}")
            println("The export streamed $exported entities.")
        }
    } finally {
        repository.remove()
    }
}

fun main() {
    try {
        runBlocking { run() }
    } catch (e: Exception) {
        System.err.println("example_async_engine failed: $e")
        exitProcess(1)
    }
}
