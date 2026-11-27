package io.github.brianmacy.sz

import io.github.brianmacy.sz.internal.ConfigComment

/** Manages the configurations persisted in the repository, mirroring the C# `SzConfigManager`. */
public interface SzConfigManager {
    /** Creates an in-memory configuration from the default template. */
    public fun createConfig(): SzConfig

    /** Creates an in-memory configuration from a configuration definition. */
    public fun createConfig(configDefinition: String): SzConfig

    /** Creates an in-memory configuration from a registered configuration. */
    public fun createConfig(configId: Long): SzConfig

    /**
     * Registers a configuration definition and returns its configuration ID. The default
     * comment is generated from its data sources (for example `Data Sources: CUSTOMERS`), as
     * the C# SDK does.
     */
    public fun registerConfig(
        configDefinition: String,
        configComment: String = ConfigComment.make(configDefinition),
    ): Long

    /** Returns the registered configurations as JSON. */
    public fun getConfigRegistry(): String

    /** Returns the default configuration ID, or `0` when none is set. */
    public fun getDefaultConfigId(): Long

    /**
     * Replaces the default configuration ID, failing with [SzReplaceConflictException] if it
     * is no longer [currentDefaultConfigId].
     */
    public fun replaceDefaultConfigId(
        currentDefaultConfigId: Long,
        newDefaultConfigId: Long,
    )

    /** Sets the default configuration ID. */
    public fun setDefaultConfigId(configId: Long)

    /**
     * Registers a configuration definition, makes it the default, and returns its ID. The
     * default comment is generated as for [registerConfig].
     */
    public fun setDefaultConfig(
        configDefinition: String,
        configComment: String = ConfigComment.make(configDefinition),
    ): Long
}
