package io.github.brianmacy.sz.testsupport

import io.github.brianmacy.sz.core.SzCoreEnvironment
import java.io.File
import java.nio.file.Files

/**
 * A fresh, isolated SQLite repository in its own temporary directory.
 *
 * No mocks: the repository is created from the schema script shipped with the installed
 * runtime (with the `sqlite3` command-line tool), and its default configuration is registered
 * through this SDK. [remove] deletes it.
 *
 * @param dataSources The data sources registered in the default configuration.
 */
public class TestRepository(
    public val dataSources: List<String> = Truthset.dataSources,
) {
    private val runtime = SenzingRuntime.current

    /** The temporary directory that holds the repository. */
    public val directory: File = Files.createTempDirectory("sz-kotlin-sdk-").toFile()

    /** The SQLite database file. */
    public val databasePath: String = directory.resolve("G2C.db").path

    /** The engine settings JSON for this repository. */
    public val settings: String = runtime.settings("sqlite3://na:na@$databasePath")

    /** The default configuration ID registered when the repository was created. */
    public val defaultConfigId: Long

    init {
        check(runtime.sqliteSchema.isFile) {
            "Missing ${runtime.sqliteSchema}: install the Senzing runtime " +
                "(on Linux, the senzingsdk-runtime and senzingsdk-setup packages)."
        }
        applySchema(runtime.sqliteSchema, databasePath)
        defaultConfigId = registerDefaultConfig()
    }

    /** Creates an environment over this repository. */
    public fun makeEnvironment(instanceName: String = "sz-kotlin-sdk-test"): SzCoreEnvironment =
        SzCoreEnvironment(instanceName = instanceName, settings = settings)

    /** Deletes the repository's directory. */
    public fun remove() {
        directory.deleteRecursively()
    }

    private fun registerDefaultConfig(): Long =
        SzCoreEnvironment(instanceName = "sz-kotlin-sdk-bootstrap", settings = settings).use { environment ->
            val configManager = environment.getConfigManager()
            val config = configManager.createConfig()
            dataSources.forEach(config::registerDataSource)
            configManager.setDefaultConfig(config.export())
        }

    private companion object {
        fun applySchema(
            schema: File,
            databasePath: String,
        ) {
            val process =
                ProcessBuilder("sqlite3", databasePath)
                    .redirectInput(schema)
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            val status = process.waitFor()
            check(status == 0) { "sqlite3 failed ($status) creating $databasePath: $output" }
        }
    }
}
