package io.github.brianmacy.sz.internal

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A job for an [EngineWorkerPool]: [run] on a worker, or [abandon] with an error if the pool
 * stops (or the job is cancelled) before a worker starts it.
 */
internal class EngineJob(
    private val work: () -> Unit,
    private val abandon: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var state = State.QUEUED

    private enum class State { QUEUED, STARTED, DONE }

    /** Runs the job unless it was cancelled or abandoned. */
    fun run() {
        synchronized(lock) {
            if (state != State.QUEUED) return
            state = State.STARTED
        }
        work()
    }

    /** Abandons the job with [error] if no worker has started it. */
    fun fail(error: Throwable) {
        synchronized(lock) {
            if (state != State.QUEUED) return
            state = State.DONE
        }
        abandon(error)
    }
}

/**
 * Long-lived worker threads that run engine calls in submission order.
 *
 * The engine builds working state per OS thread on the thread's first call and releases it
 * when the thread exits, so the async engine runs every call on one of a fixed set of threads
 * rather than on a coroutine dispatcher's shared pool.
 */
internal class EngineWorkerPool(
    threadCount: Int,
    name: String,
) {
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val queue = ArrayDeque<EngineJob>()
    private var stoppedWith: Throwable? = null
    private val threads: List<Thread> =
        List(threadCount) { index ->
            Thread({ workLoop() }, "$name-$index").apply {
                isDaemon = true
                start()
            }
        }

    /** Whether the pool has stopped accepting work. */
    val isShutDown: Boolean get() = lock.withLock { stoppedWith != null }

    /** Queues a job, or fails it at once when the pool has stopped. */
    fun submit(job: EngineJob) {
        val error =
            lock.withLock {
                stoppedWith ?: run {
                    queue.addLast(job)
                    available.signal()
                    null
                }
            }
        error?.let(job::fail)
    }

    /** Stops accepting work and fails the queued jobs with [error]; running jobs finish. */
    fun stop(error: Throwable) {
        val abandoned =
            lock.withLock {
                if (stoppedWith != null) return
                stoppedWith = error
                available.signalAll()
                queue.toList().also { queue.clear() }
            }
        abandoned.forEach { it.fail(error) }
    }

    /** Waits for the worker threads to exit (unless called from one of them). */
    fun join() {
        val current = Thread.currentThread()
        threads.filter { it !== current }.forEach { it.join() }
    }

    /** [stop], then [join]. */
    fun shutdown(error: Throwable) {
        stop(error)
        join()
    }

    private fun workLoop() {
        while (true) {
            val job =
                lock.withLock {
                    while (queue.isEmpty() && stoppedWith == null) available.await()
                    queue.pollFirst()
                } ?: return
            job.run()
        }
    }
}
