package io.github.brianmacy.sz.internal

import io.github.brianmacy.sz.SzEnvironmentDestroyedException
import io.github.brianmacy.sz.SzException
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * The lifecycle shared by an environment and its subsystems.
 *
 * Every native call runs inside [execute] (a shared lock); [destroy] takes the lock
 * exclusively, so teardown waits for in-flight calls and later calls fail with
 * [SzEnvironmentDestroyedException]. A call from inside an SDK call on the same thread (for
 * example from a log callback) fails instead of deadlocking; a [destroy] from there runs when
 * the outer call returns.
 */
internal class EnvironmentState(
    configId: Long?,
) {
    private val lock = ReentrantReadWriteLock()
    private var destroyed = false
    private val configIdValue = AtomicReference(configId)

    /** The async-engine worker pools to stop before teardown; null once destroy has begun. */
    private var workerPools: MutableList<WeakReference<EngineWorkerPool>>? = mutableListOf()
    private val poolsLock = Any()
    private val destroyGate = ReentrantLock()
    private val deferredTeardown = AtomicReference<(() -> Unit)?>(null)

    /** The configuration ID the engine uses (null: the repository's default). */
    var configId: Long?
        get() = configIdValue.get()
        set(value) = configIdValue.set(value)

    val isDestroyed: Boolean get() = lock.read { destroyed }

    /** Whether [destroy] has begun (its worker pools are being stopped). */
    val isDestroyBegun: Boolean get() = synchronized(poolsLock) { workerPools == null }

    fun <T> execute(body: () -> T): T {
        if (callDepth.get() > 0) {
            throw SzException(
                "Re-entrant Senzing call: the SDK cannot be called from inside an SDK call " +
                    "(for example, from a log callback).",
            )
        }
        try {
            callDepth.set(1)
            return lock.read {
                if (destroyed) throw SzEnvironmentDestroyedException()
                body()
            }
        } finally {
            callDepth.set(0)
            runDeferredTeardown()
        }
    }

    /** Registers an async-engine pool, stopped and joined by [destroy]. */
    fun register(pool: EngineWorkerPool) {
        val registered =
            synchronized(poolsLock) {
                workerPools?.let { pools ->
                    pools.removeAll { it.get() == null }
                    pools.add(WeakReference(pool))
                } != null
            }
        if (!registered) pool.shutdown(SzEnvironmentDestroyedException())
    }

    /** Runs [teardown] once, after stopping the worker pools and waiting for in-flight calls. */
    fun destroy(teardown: () -> Unit) {
        if (callDepth.get() > 0) {
            deferredTeardown.set(teardown)
            return
        }
        destroyGate.withLock {
            val pools =
                synchronized(poolsLock) {
                    val current = workerPools.orEmpty().mapNotNull { it.get() }
                    workerPools = null
                    current
                }
            // Stop every pool before joining any, so no queued call waits on another pool's
            // running call.
            pools.forEach { it.stop(SzEnvironmentDestroyedException()) }
            pools.forEach { it.join() }
            lock.write {
                if (!destroyed) {
                    destroyed = true
                    teardown()
                }
            }
        }
    }

    /** Waits for a destroy in progress. */
    fun waitForDestroy() {
        destroyGate.withLock {}
    }

    private fun runDeferredTeardown() {
        deferredTeardown.getAndSet(null)?.let(::destroy)
    }

    private companion object {
        /** Whether the current thread is inside an SDK call. */
        val callDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }
    }
}
