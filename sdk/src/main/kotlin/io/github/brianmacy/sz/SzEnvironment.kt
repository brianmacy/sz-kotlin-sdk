package io.github.brianmacy.sz

/**
 * The entry point and lifecycle owner of the Senzing subsystems, mirroring the C#
 * `SzEnvironment` interface.
 *
 * Each `get...` accessor lazily initializes its native subsystem and returns the same instance
 * on later calls. After [destroy] every subsystem method throws
 * [SzEnvironmentDestroyedException]. [io.github.brianmacy.sz.core.SzCoreEnvironment] is the
 * implementation over the native engine. [close] destroys, so an environment works with `use`.
 */
public interface SzEnvironment : AutoCloseable {
    /** Returns the product subsystem (version and license information). */
    public fun getProduct(): SzProduct

    /** Returns the engine subsystem (records, entities, search, why/how). */
    public fun getEngine(): SzEngine

    /** Returns the configuration manager subsystem. */
    public fun getConfigManager(): SzConfigManager

    /** Returns the diagnostic subsystem. */
    public fun getDiagnostic(): SzDiagnostic

    /**
     * Returns the process-wide logging controls.
     *
     * Logging is not tied to the environment's lifetime: it works before the first subsystem
     * is initialized and after [destroy], so log callbacks can be installed before the engine
     * starts.
     */
    public fun getLogging(): SzLogging

    /** Returns the ID of the configuration the engine is using. */
    public fun getActiveConfigId(): Long

    /** Reinitializes the initialized subsystems with another configuration. */
    public fun reinitialize(configId: Long)

    /**
     * Destroys the environment and its subsystems. Idempotent.
     *
     * Waits for in-flight calls to finish; later calls throw [SzEnvironmentDestroyedException].
     */
    public fun destroy()

    /** Whether [destroy] has been called. */
    public val isDestroyed: Boolean

    /** Destroys the environment ([destroy]). */
    override fun close(): Unit = destroy()
}
