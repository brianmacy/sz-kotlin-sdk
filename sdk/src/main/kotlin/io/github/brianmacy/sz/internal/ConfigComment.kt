package io.github.brianmacy.sz.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The configuration comment the C# SDK generates from a definition's data sources. */
internal object ConfigComment {
    /** The data sources in the default configuration template. */
    private val defaultDataSources = setOf("TEST", "SEARCH")

    private const val PREFIX = "Data Sources: "

    fun make(configDefinition: String): String {
        val codes = dataSourceCodes(configDefinition) ?: return ""
        val custom = codes.filter { it !in defaultDataSources }
        val defaults = codes.filter { it in defaultDataSources }.sorted()
        return when {
            custom.isNotEmpty() -> PREFIX + custom.joinToString(", ")
            defaults.isEmpty() -> PREFIX + "[ NONE ]"
            defaults.toSet() == defaultDataSources -> PREFIX + "[ ONLY DEFAULT ]"
            else -> PREFIX + "[ SOME DEFAULT (" + defaults.joinToString(", ") + ") ]"
        }
    }

    /** The `DSRC_CODE`s of a definition, or null when it is not a configuration. */
    private fun dataSourceCodes(configDefinition: String): List<String>? =
        runCatching {
            val root = Json.parseToJsonElement(configDefinition).jsonObject
            val config = root["G2_CONFIG"] as? JsonObject ?: return null
            config.getValue("CFG_DSRC").jsonArray.mapNotNull {
                it.jsonObject["DSRC_CODE"]?.jsonPrimitive?.contentOrNull
            }
        }.getOrNull()
}
