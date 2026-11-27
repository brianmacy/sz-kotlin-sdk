// LoadTruthset: create a repository, register data sources, load the Senzing truth set, and
// report how the records resolved.
//
// Run: ./gradlew -q :examples:runLoadTruthset
//
// Uses the repository in SENZING_ENGINE_CONFIGURATION_JSON when it is set (its default
// configuration must already register CUSTOMERS, REFERENCE and WATCHLIST); otherwise creates a
// throwaway SQLite repository.

package io.github.brianmacy.sz.examples

import io.github.brianmacy.sz.addRecord
import io.github.brianmacy.sz.core.SzCoreEnvironment
import io.github.brianmacy.sz.exportJsonEntityReport
import io.github.brianmacy.sz.getEntity
import io.github.brianmacy.sz.models.SzEntityResponse
import io.github.brianmacy.sz.models.SzWithInfoResponse
import io.github.brianmacy.sz.testsupport.ExampleFailure
import io.github.brianmacy.sz.testsupport.ExampleRepository
import io.github.brianmacy.sz.testsupport.Truthset
import kotlin.system.exitProcess

private fun run() {
    val repository = ExampleRepository()
    try {
        SzCoreEnvironment(instanceName = "example_load_truthset", settings = repository.settings).use { environment ->
            val engine = environment.getEngine()

            val records = Truthset.allRecords()
            val affected = mutableSetOf<Long>()
            for (record in records) {
                val info =
                    engine.addRecord(
                        record.dataSourceCode,
                        record.recordId,
                        record.definition,
                        SzWithInfoResponse.serializer(),
                    )
                affected += info.affectedEntities.map { it.entityId }
            }
            // Loading defers some re-resolution work as redo records; finish it.
            var redone = 0
            while (true) {
                engine.processRedoRecord(engine.getRedoRecord() ?: break)
                redone += 1
            }

            val entities = engine.exportJsonEntityReport(SzEntityResponse.serializer())
            val multiRecord = entities.count { (it.resolvedEntity.records?.size ?: 0) > 1 }
            println("Loaded ${records.size} records, affecting ${affected.size} entities ($redone redo records processed).")
            println("They resolved into ${entities.size} entities; $multiRecord have more than one record.")

            val robert = engine.getEntity("CUSTOMERS", "1001", SzEntityResponse.serializer()).resolvedEntity
            val members =
                robert.records
                    .orEmpty()
                    .map { it.recordKey.toString() }
                    .sorted()
            println("Entity ${robert.entityId} (${robert.entityName ?: "?"}): $members")
            if (members.size < 4) {
                throw ExampleFailure("expected CUSTOMERS 1001-1004 to resolve together, got $members")
            }
        }
    } finally {
        repository.remove()
    }
}

fun main() {
    try {
        run()
    } catch (e: Exception) {
        System.err.println("example_load_truthset failed: $e")
        exitProcess(1)
    }
}
