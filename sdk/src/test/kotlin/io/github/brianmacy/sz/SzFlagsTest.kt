package io.github.brianmacy.sz

import io.github.brianmacy.sz.testsupport.SenzingRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SzFlagsTest {
    /** The installed szflags.json: the source of truth for the constants. */
    private val installedFlags: List<JsonObject> by lazy {
        val file = SenzingRuntime.current.erDirectory.resolve("sdk/szflags.json")
        Json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonObject }
    }

    private val JsonObject.value: Long get() = getValue("value").jsonPrimitive.long

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    /**
     * The named constants of [SzFlags], by name, read through their public getters (on the JVM,
     * `SzFlags.Companion.getNAME-<hash>()` returning the unboxed bits).
     */
    private val constants: Map<String, Long> by lazy {
        SzFlags.Companion::class.java.declaredMethods
            .filter { method ->
                Modifier.isPublic(method.modifiers) &&
                    method.name.startsWith("get") &&
                    method.parameterCount == 0 &&
                    method.returnType == Long::class.javaPrimitiveType
            }.associate { method ->
                method.name.removePrefix("get").substringBefore('-') to method.invoke(SzFlags.Companion) as Long
            }
    }

    @Test
    fun `test_constants match the installed szflags json`() {
        val expected =
            mapOf(
                "SzNoFlags" to SzFlags.NO_FLAGS,
                "SzWithInfo" to SzFlags.WITH_INFO,
                "SzEntityDefaultFlags" to SzFlags.ENTITY_DEFAULT_FLAGS,
                "SzEntityIncludeEntityName" to SzFlags.ENTITY_INCLUDE_ENTITY_NAME,
                "SzExportDefaultFlags" to SzFlags.EXPORT_DEFAULT_FLAGS,
                "SzSearchByAttributesDefaultFlags" to SzFlags.SEARCH_BY_ATTRIBUTES_DEFAULT_FLAGS,
                "SzWhyEntitiesDefaultFlags" to SzFlags.WHY_ENTITIES_DEFAULT_FLAGS,
                "SzFindPathDefaultFlags" to SzFlags.FIND_PATH_DEFAULT_FLAGS,
                "SzIncludeFeatureScores" to SzFlags.INCLUDE_FEATURE_SCORES,
                "SzRecordDefaultFlags" to SzFlags.RECORD_DEFAULT_FLAGS,
            )
        var seen = 0
        for (entry in installedFlags) {
            val flags = expected[entry.text("altSymbol")] ?: continue
            assertEquals(entry.value, flags.value, entry.toString())
            seen += 1
        }
        assertEquals(expected.size, seen)
    }

    @Test
    fun `test_every szflags json symbol is a constant with its value`() {
        assertEquals(installedFlags.size + 1, constants.size, "every symbol plus REDO_DEFAULT_FLAGS: $constants")
        for (entry in installedFlags) {
            val name = entry.text("symbol").removePrefix("SZ_")
            val value = assertNotNull(constants[name], "missing constant $name")
            assertEquals(entry.value, value, name)
        }
        assertEquals(0L, constants["REDO_DEFAULT_FLAGS"])
    }

    @Test
    fun `test_usage groups cover every flag that names them`() {
        val union = mutableMapOf<SzFlagUsageGroup, SzFlags>()
        for (entry in installedFlags) {
            for (group in entry.strings("altGroups")) {
                val usage = assertNotNull(SzFlagUsageGroup.fromAltName(group), "unknown group $group")
                assertTrue(SzFlags(entry.value) in usage.allFlags, "$group: $entry")
                union[usage] = (union[usage] ?: SzFlags.NO_FLAGS) or SzFlags(entry.value)
            }
        }
        for (usage in SzFlagUsageGroup.entries) {
            assertEquals(union[usage] ?: SzFlags.NO_FLAGS, usage.allFlags, usage.altName)
        }
        assertTrue(SzFlags.ENTITY_INCLUDE_ENTITY_NAME in SzFlagUsageGroup.ENTITY.allFlags)
        assertFalse(SzFlags.WITH_INFO in SzFlagUsageGroup.ENTITY.allFlags)
        assertEquals(SzFlags.WITH_INFO, SzFlagUsageGroup.ADD_RECORD.allFlags)
        assertNull(SzFlagUsageGroup.fromAltName("SzNoSuchFlags"))
    }

    @Test
    fun `test_flag operators and description`() {
        val flags = SzFlags.ENTITY_INCLUDE_ENTITY_NAME or SzFlags.ENTITY_INCLUDE_RECORD_DATA
        assertTrue(SzFlags.ENTITY_INCLUDE_ENTITY_NAME in flags)
        assertFalse(SzFlags.WITH_INFO in flags)
        assertEquals(flags, SzFlags.ENTITY_INCLUDE_ENTITY_NAME + SzFlags.ENTITY_INCLUDE_RECORD_DATA)
        assertEquals(SzFlags.ENTITY_INCLUDE_RECORD_DATA, flags and SzFlags.ENTITY_INCLUDE_RECORD_DATA)
        assertEquals(SzFlags.ENTITY_INCLUDE_ENTITY_NAME, flags - SzFlags.ENTITY_INCLUDE_RECORD_DATA)
        assertTrue(SzFlags.NO_FLAGS.isEmpty())
        assertFalse(flags.isEmpty())
        assertTrue(SzFlags.REDO_DEFAULT_FLAGS.isEmpty())
        assertEquals("SzFlags(0x1000)", SzFlags(0x1000).toString())
        assertEquals("SzFlags(0x4000000000000000)", SzFlags.WITH_INFO.toString())
        assertTrue(SzFlags.ENTITY_CORE_FLAGS in SzFlags.ENTITY_DEFAULT_FLAGS)
        // Boxed (as Any or in a collection), a flag value keeps its value semantics.
        val boxed: Any = SzFlags.WITH_INFO
        assertEquals("SzFlags(0x4000000000000000)", boxed.toString())
        assertEquals(setOf(SzFlags(0x4000_0000_0000_0000L)), setOf(SzFlags.WITH_INFO, SzFlags.WITH_INFO))
        assertEquals(SzFlags.WITH_INFO.value, (boxed as SzFlags).value)
        // Java sees the boxed class: its getValue() returns the bits.
        assertEquals(SzFlags.WITH_INFO.value, SzFlags::class.java.getMethod("getValue").invoke(boxed))
    }
}
