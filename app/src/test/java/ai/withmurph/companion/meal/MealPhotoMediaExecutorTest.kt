package ai.withmurph.companion.meal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MealPhotoMediaExecutorTest {
    @Test
    fun healthyBackToBackWorkUsesDirectHandoffWithoutAQueue() {
        val executor = newMealPhotoMediaExecutor("meal-media-handoff-test")
        val firstEntered = CountDownLatch(1)
        try {
            executor.execute {
                firstEntered.countDown()
                Thread.sleep(5)
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

            repeat(100) {
                val completed = CountDownLatch(1)
                executeMealPhotoMediaTask(
                    executor = executor,
                    task = Runnable(completed::countDown),
                )
                assertTrue(completed.await(2, TimeUnit.SECONDS))
            }
            assertTrue(executor.queue.isEmpty())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun singleDaemonLaneRejectsInsteadOfQueuingBehindBlockedProviderWork() {
        val executor = newMealPhotoMediaExecutor("meal-media-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val daemon = AtomicBoolean(false)
        try {
            executor.execute {
                daemon.set(Thread.currentThread().isDaemon)
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(daemon.get())
            assertFalse(executor.queue.offer {})
            val startedAt = System.nanoTime()
            try {
                executeMealPhotoMediaTask(
                    executor = executor,
                    task = Runnable {},
                    handoffGraceNanos = TimeUnit.MILLISECONDS.toNanos(2),
                )
                fail("Expected the occupied no-backlog lane to reject new work")
            } catch (_: RejectedExecutionException) {
                // Expected.
            }
            assertTrue(System.nanoTime() - startedAt < TimeUnit.SECONDS.toNanos(1))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
