package io.github.brianmacy.sz

/**
 * An in-memory configuration, mirroring the C# `SzConfig` interface.
 *
 * Obtain one from [SzConfigManager.createConfig]. Changes stay in memory until the exported
 * definition is registered with [SzConfigManager].
 */
public interface SzConfig {
    /** Returns the configuration definition as JSON. */
    public fun export(): String

    /** Returns the registered data sources as JSON. */
    public fun getDataSourceRegistry(): String

    /** Registers a data source and returns the registration result as JSON. */
    public fun registerDataSource(dataSourceCode: String): String

    /** Unregisters a data source. */
    public fun unregisterDataSource(dataSourceCode: String)
}
