package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.Native
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The JNI library exports exactly the native methods `Native` declares. */
class JniSymbolsTest {
    private val prefix = "Java_" + Native::class.java.name.replace('.', '_') + "_"

    private val isMac = System.getProperty("os.name").startsWith("Mac")

    /** The built JNI library, found on `java.library.path` as `System.loadLibrary` would. */
    private fun library(): File {
        val name = System.mapLibraryName(Native.LIBRARY_NAME)
        val path = System.getProperty("java.library.path").orEmpty()
        val found =
            path
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { File(it, name) }
                .firstOrNull(File::isFile)
        return assertNotNull(found, "$name not on java.library.path ($path)")
    }

    /** The defined, exported symbols of [library] (without macOS's leading `_`). */
    private fun exportedSymbols(library: File): Set<String> {
        val command =
            if (isMac) listOf("nm", "-gU", library.path) else listOf("nm", "-D", "--defined-only", library.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "$command failed: $output")
        return output
            .lineSequence()
            .map { line -> line.trim().substringAfterLast(' ') }
            .filter { it.isNotEmpty() }
            .map { if (isMac) it.removePrefix("_") else it }
            .toSet()
    }

    @Test
    fun `test_every native method has a JNI export and every export a native method`() {
        val declared =
            Native::class.java.declaredMethods
                .filter { Modifier.isNative(it.modifiers) }
                .map { method ->
                    assertTrue('_' !in method.name, "JNI-mangle ${method.name} before comparing")
                    prefix + method.name
                }.toSet()
        assertTrue(declared.isNotEmpty())
        val exported = exportedSymbols(library()).filter { it.startsWith(prefix) }.toSet()
        assertEquals(emptySet(), declared - exported, "native methods with no JNI export")
        assertEquals(emptySet(), exported - declared, "JNI exports with no native method")
    }
}
