package com.squareup.stoic.target.runtime

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Utility for executing code on the main thread with a timeout.
 */
object MainThreadExecutor {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Executes the given block on the main thread, blocking the calling thread until completion.
     *
     * If the main thread cannot be acquired within the timeout, captures the main thread's
     * stack trace and throws a TimeoutException with that stack trace as the cause.
     *
     * @param timeoutMillis Maximum time to wait for the main thread (default: 5000ms)
     * @param block The code to execute on the main thread
     * @return The result of executing the block
     * @throws TimeoutException if the main thread cannot be acquired within the timeout
     */
    fun <T> runOnMainThread(timeoutMillis: Long = 5000, block: () -> T): T {
        // If we're already on the main thread, just execute directly
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var exception: Throwable? = null

        mainHandler.post {
            try {
                result = block()
            } catch (e: Throwable) {
                exception = e
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)

        if (!completed) {
            // Timeout - capture main thread stack trace
            val mainThread = Looper.getMainLooper().thread
            val mainThreadStackTrace = mainThread.stackTrace

            val stackTraceException = Exception("Main thread stack trace at timeout").apply {
                stackTrace = mainThreadStackTrace
            }

            throw TimeoutException(
                "Failed to acquire main thread within ${timeoutMillis}ms. " +
                "Main thread may be blocked or busy."
            ).apply {
                initCause(stackTraceException)
            }
        }

        exception?.let { throw it }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
