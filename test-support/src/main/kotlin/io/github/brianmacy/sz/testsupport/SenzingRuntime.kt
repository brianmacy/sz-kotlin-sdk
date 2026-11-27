package io.github.brianmacy.sz.testsupport

import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * The installed Senzing runtime the tests and examples use: its `er` directory comes from the
 * `senzing.dir` system property (set by the build from `SENZING_DIR` or
 * `senzing-runtime.yaml`); the layout rules come from `senzing-test.yaml`.
 */
public class SenzingRuntime(
    public val erDirectory: File,
) {
    private val layout: Map<String, Any> = Yaml().load(resourceText("senzing-test.yaml"))

    private fun candidates(key: String): List<File> =
        (layout.getValue(key) as List<*>).map { path ->
            val text = path.toString()
            if (text.startsWith("/")) File(text) else erDirectory.resolve(text)
        }

    /** `PIPELINE.RESOURCEPATH`. */
    public val resourcePath: File = erDirectory.resolve(layout.getValue("resourcePath").toString())

    /** `PIPELINE.SUPPORTPATH`. */
    public val supportPath: File = candidates("supportPath").firstOrNull(File::isDirectory) ?: erDirectory.resolve("data")

    /** `PIPELINE.CONFIGPATH`. */
    public val configPath: File =
        run {
            val linuxLayout = erDirectory.path == layout.getValue("linuxErDirectory").toString()
            candidates("configPath").firstOrNull { candidate ->
                (linuxLayout || !candidate.path.startsWith("/etc/")) && candidate.isDirectory
            } ?: erDirectory.resolve("etc")
        }

    /** The SQLite schema script that creates a repository. */
    public val sqliteSchema: File = erDirectory.resolve(layout.getValue("sqliteSchema").toString())

    /** The engine settings JSON for a repository at [connection] (a Senzing `SQL.CONNECTION`). */
    public fun settings(connection: String): String {
        fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """{"PIPELINE":{"CONFIGPATH":${quote(configPath.path)},"RESOURCEPATH":${quote(resourcePath.path)},""" +
            """"SUPPORTPATH":${quote(supportPath.path)}},"SQL":{"CONNECTION":${quote(connection)}}}"""
    }

    public companion object {
        /** The runtime named by the `senzing.dir` system property. */
        public val current: SenzingRuntime by lazy {
            val dir =
                System.getProperty("senzing.dir")?.takeIf { it.isNotBlank() }
                    ?: error("senzing.dir is not set: run through Gradle, which finds the runtime")
            SenzingRuntime(File(dir))
        }

        internal fun resourceText(name: String): String =
            requireNotNull(SenzingRuntime::class.java.classLoader.getResource(name)) { "missing resource $name" }
                .readText()
    }
}
