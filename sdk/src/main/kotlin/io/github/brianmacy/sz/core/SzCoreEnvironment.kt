package io.github.brianmacy.sz.core

import io.github.brianmacy.sz.SzConfigManager
import io.github.brianmacy.sz.SzDiagnostic
import io.github.brianmacy.sz.SzEngine
import io.github.brianmacy.sz.SzEnvironment
import io.github.brianmacy.sz.SzException
import io.github.brianmacy.sz.SzLogging
import io.github.brianmacy.sz.SzProduct
import io.github.brianmacy.sz.internal.EnvironmentState
import io.github.brianmacy.sz.internal.Native

/**
 * The [SzEnvironment] over the native engine, mirroring the C# `SzCoreEnvironment`.
 *
 * Only one environment may be active per process (the native engine is a process-wide
 * singleton); creating a second before destroying the first throws [SzException]. Destroy it
 * with [destroy], [close] or `use`:
 *
 * ```kotlin
 * SzCoreEnvironment(instanceName = "my-app", settings = settings).use { environment ->
 *     val engine = environment.getEngine()
 *     engine.addRecord("CUSTOMERS", "1001", """{"NAME_FULL":"Robert Smith"}""")
 * }
 * ```
 *
 * @param instanceName The name the engine uses in its logs; blank means [DEFAULT_INSTANCE_NAME].
 * @param settings The engine settings JSON; blank means [DEFAULT_SETTINGS].
 * @param verboseLogging Whether the engine logs verbosely.
 * @param configId The registered configuration the engine and diagnostic start with, instead of
 *   the repository's default. An unregistered ID is reported by the first call that starts the
 *   engine or diagnostic.
 */
public class SzCoreEnvironment(
    instanceName: String = DEFAULT_INSTANCE_NAME,
    settings: String = DEFAULT_SETTINGS,
    public val verboseLogging: Boolean = false,
    configId: Long? = null,
) : SzEnvironment {
    /** The instance name, normalized. */
    public val instanceName: String = normalize(instanceName, DEFAULT_INSTANCE_NAME)

    /** The engine settings JSON, normalized. */
    public val settings: String = normalize(settings, DEFAULT_SETTINGS)

    internal val state = EnvironmentState(configId)

    /** The initialized subsystems; guarded by itself. */
    private val subsystems = Subsystems()

    private class Subsystems {
        var product: SzCoreProduct? = null
        var engine: SzCoreEngine? = null
        var configManager: SzCoreConfigManager? = null
        var diagnostic: SzCoreDiagnostic? = null
    }

    init {
        synchronized(activeLock) {
            if (active != null) {
                throw SzException(
                    "Only one active SzEnvironment is permitted per process; destroy the existing instance first.",
                )
            }
            Native.envCreate(this.instanceName, this.settings, verboseLogging, configId != null, configId ?: 0)
            active = this
        }
    }

    /** The configuration ID set at creation or by [reinitialize], or null for the default. */
    public val configId: Long? get() = state.configId

    override fun getProduct(): SzProduct =
        initializeOnce({ it.product }) {
            Native.envInitProduct()
            SzCoreProduct(state).also { product -> it.product = product }
        }

    override fun getEngine(): SzEngine =
        initializeOnce({ it.engine }) {
            Native.envInitEngine()
            SzCoreEngine(state).also { engine -> it.engine = engine }
        }

    override fun getConfigManager(): SzConfigManager =
        initializeOnce({ it.configManager }) {
            Native.envInitConfigManager()
            SzCoreConfigManager(state).also { manager -> it.configManager = manager }
        }

    override fun getDiagnostic(): SzDiagnostic =
        initializeOnce({ it.diagnostic }) {
            Native.envInitDiagnostic()
            SzCoreDiagnostic(state).also { diagnostic -> it.diagnostic = diagnostic }
        }

    override fun getLogging(): SzLogging = SzCoreLogging

    override fun getActiveConfigId(): Long = state.execute { Native.envGetActiveConfigId() }

    override fun reinitialize(configId: Long): Unit =
        state.execute {
            // Starts the engine first if needed (as in the C# SDK), so the ID is always checked.
            Native.envReinitialize(configId)
            // Recorded only on success.
            state.configId = configId
        }

    override fun destroy() {
        state.destroy {
            synchronized(subsystems) {
                Native.envDestroy()
                subsystems.product = null
                subsystems.engine = null
                subsystems.configManager = null
                subsystems.diagnostic = null
            }
            synchronized(activeLock) {
                if (active === this) active = null
            }
        }
    }

    override val isDestroyed: Boolean get() = state.isDestroyed

    private fun <T : Any> initializeOnce(
        existing: (Subsystems) -> T?,
        create: (Subsystems) -> T,
    ): T = state.execute { synchronized(subsystems) { existing(subsystems) ?: create(subsystems) } }

    /** Builds an [SzCoreEnvironment], mirroring the C# `SzCoreEnvironment.Builder`. */
    public class Builder internal constructor() {
        private var instanceName = DEFAULT_INSTANCE_NAME
        private var settings = DEFAULT_SETTINGS
        private var verboseLogging = false
        private var configId: Long? = null

        /** Sets the instance name. */
        public fun instanceName(instanceName: String): Builder = apply { this.instanceName = instanceName }

        /** Sets the engine settings JSON. */
        public fun settings(settings: String): Builder = apply { this.settings = settings }

        /** Sets verbose engine logging. */
        public fun verboseLogging(verboseLogging: Boolean): Builder = apply { this.verboseLogging = verboseLogging }

        /** Sets the configuration to run with (null: the repository's default). */
        public fun configId(configId: Long?): Builder = apply { this.configId = configId }

        /** Creates the environment. */
        public fun build(): SzCoreEnvironment = SzCoreEnvironment(instanceName, settings, verboseLogging, configId)
    }

    public companion object {
        /** The instance name used when none is given (as in the C# SDK). */
        public const val DEFAULT_INSTANCE_NAME: String = "Senzing Instance"

        /** The settings used when none are given (as in the C# SDK). */
        public const val DEFAULT_SETTINGS: String = "{ }"

        private val activeLock = Any()
        private var active: SzCoreEnvironment? = null

        /** Returns a new [Builder]. */
        @JvmStatic
        public fun newBuilder(): Builder = Builder()

        /**
         * Returns the active environment, or null. If a [destroy] is in progress, waits for it
         * and returns null.
         */
        @JvmStatic
        public fun getActiveInstance(): SzCoreEnvironment? {
            val environment = synchronized(activeLock) { active } ?: return null
            if (environment.state.isDestroyBegun) {
                environment.state.waitForDestroy()
                return null
            }
            return environment
        }

        private fun normalize(
            value: String,
            fallback: String,
        ): String = value.trim().ifEmpty { fallback }
    }
}
