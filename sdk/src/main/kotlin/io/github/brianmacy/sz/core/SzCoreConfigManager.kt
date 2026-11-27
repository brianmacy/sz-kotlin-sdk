package io.github.brianmacy.sz.core

import io.github.brianmacy.sz.SzConfig
import io.github.brianmacy.sz.SzConfigManager
import io.github.brianmacy.sz.internal.EnvironmentState
import io.github.brianmacy.sz.internal.Native

/** The [SzConfigManager] over the native engine. Obtain it from [SzCoreEnvironment.getConfigManager]. */
public class SzCoreConfigManager internal constructor(
    internal val state: EnvironmentState,
) : SzConfigManager {
    override fun createConfig(): SzConfig = SzCoreConfig(state, state.execute { Native.configTemplate() })

    override fun createConfig(configDefinition: String): SzConfig =
        SzCoreConfig(state, state.execute { Native.configNormalize(configDefinition) })

    override fun createConfig(configId: Long): SzConfig = SzCoreConfig(state, state.execute { Native.configFromId(configId) })

    override fun registerConfig(
        configDefinition: String,
        configComment: String,
    ): Long = state.execute { Native.registerConfig(configDefinition, configComment) }

    override fun getConfigRegistry(): String = state.execute { Native.getConfigRegistry() }

    override fun getDefaultConfigId(): Long = state.execute { Native.getDefaultConfigId() }

    override fun replaceDefaultConfigId(
        currentDefaultConfigId: Long,
        newDefaultConfigId: Long,
    ): Unit = state.execute { Native.replaceDefaultConfigId(currentDefaultConfigId, newDefaultConfigId) }

    override fun setDefaultConfigId(configId: Long): Unit = state.execute { Native.setDefaultConfigId(configId) }

    override fun setDefaultConfig(
        configDefinition: String,
        configComment: String,
    ): Long {
        val configId = registerConfig(configDefinition, configComment)
        setDefaultConfigId(configId)
        return configId
    }
}

/**
 * The [SzConfig] over the native engine. It holds its configuration definition; each operation
 * loads it into a native configuration and keeps the definition the operation leaves.
 */
public class SzCoreConfig internal constructor(
    internal val state: EnvironmentState,
    definition: String,
) : SzConfig {
    private var definition = definition

    override fun export(): String = state.execute { synchronized(this) { definition } }

    override fun getDataSourceRegistry(): String = state.execute { Native.configDataSourceRegistry(current()) }

    override fun registerDataSource(dataSourceCode: String): String =
        edit { Native.configRegisterDataSource(it, dataSourceCode) }

    override fun unregisterDataSource(dataSourceCode: String) {
        edit { Native.configUnregisterDataSource(it, dataSourceCode) }
    }

    private fun current(): String = synchronized(this) { definition }

    /** Applies an edit that returns `[result, updated definition]`, keeping the update. */
    private fun edit(operation: (String) -> Array<String>): String =
        state.execute {
            synchronized(this) {
                val (result, updated) = operation(definition)
                definition = updated
                result
            }
        }
}
