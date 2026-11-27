package io.github.brianmacy.sz.testsupport

import io.github.brianmacy.sz.SzEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml

/**
 * A record from the Senzing truth set.
 *
 * @property definition The record definition (one JSON line).
 */
public data class TruthsetRecord(
    public val dataSourceCode: String,
    public val recordId: String,
    public val definition: String,
)

/** The Senzing demo truth set bundled with the tests (see `truthset/README.md`). */
public object Truthset {
    private val manifest: Map<String, List<String>> = Yaml().load(SenzingRuntime.resourceText("truthset/truthset.yaml"))

    /** The truth set's data sources. */
    public val dataSources: List<String> = manifest.getValue("dataSources")

    /** The truth set's record files, in load order. */
    public val files: List<String> = manifest.getValue("files")

    /** The records of one truth-set file (for example `customers.jsonl`). */
    public fun records(file: String): List<TruthsetRecord> =
        SenzingRuntime
            .resourceText("truthset/$file")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val record = Json.parseToJsonElement(line).jsonObject
                TruthsetRecord(
                    record.getValue("DATA_SOURCE").jsonPrimitive.content,
                    record.getValue("RECORD_ID").jsonPrimitive.content,
                    line,
                )
            }.toList()

    /** Every truth-set record, file by file in load order. */
    public fun allRecords(): List<TruthsetRecord> = files.flatMap(::records)

    /** Loads the truth set and processes the redo records it produces. */
    public fun load(engine: SzEngine) {
        for (record in allRecords()) engine.addRecord(record.dataSourceCode, record.recordId, record.definition)
        while (true) engine.processRedoRecord(engine.getRedoRecord() ?: break)
    }
}
