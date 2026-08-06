package ai.withmurph.companion.meal

import androidx.work.Operation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor

@OptIn(ExperimentalCoroutinesApi::class)
class MealPhotoWorkCancellationTest {
    @Test
    fun cancellationDoesNotReportSuccessUntilWorkManagerCompletesItsOperation() = runTest {
        val operation = PendingOperation()

        val cancellation = async { awaitMealPhotoWorkCancellation { operation.value } }
        runCurrent()

        assertFalse(cancellation.isCompleted)
        operation.complete()
        assertTrue(cancellation.await())
    }

    @Test
    fun enqueueAndCancellationFailuresAreReported() = runTest {
        val enqueue = PendingOperation().also {
            it.fail(IllegalStateException("enqueue failed"))
        }
        val cancellation = PendingOperation().also {
            it.fail(IllegalStateException("cancellation failed"))
        }

        assertFalse(awaitMealPhotoWorkOperation { enqueue.value })
        assertFalse(awaitMealPhotoWorkCancellation { cancellation.value })
    }

    /**
     * The app receives only Guava's lightweight listenable-future ABI transitively; its concrete
     * test utilities are intentionally absent. A dynamic proxy exercises WorkManager's real
     * Operation await path without adding a dependency solely for a fake.
     */
    private class PendingOperation {
        private val lock = Any()
        private val listeners = mutableListOf<Pair<Runnable, Executor>>()
        private var completed = false
        private var failure: Throwable? = null
        private val futureInterface = Class.forName(
            "com.google.common.util.concurrent.ListenableFuture",
        )
        private val future = Proxy.newProxyInstance(
            futureInterface.classLoader,
            arrayOf(futureInterface),
        ) { proxy, method, arguments ->
            when (method.name) {
                "addListener" -> {
                    val listener = arguments?.get(0) as Runnable
                    val executor = arguments[1] as Executor
                    val executeNow = synchronized(lock) {
                        if (completed) true else {
                            listeners += listener to executor
                            false
                        }
                    }
                    if (executeNow) executor.execute(listener)
                    null
                }
                "isDone" -> synchronized(lock) { completed }
                "isCancelled" -> false
                "cancel" -> false
                "get" -> synchronized(lock) {
                    check(completed) { "Future read before completion" }
                    failure?.let { throw ExecutionException(it) }
                    Operation.SUCCESS
                }
                "toString" -> "PendingMealPhotoOperation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                else -> error("Unexpected future method ${method.name}")
            }
        }

        val value: Operation = Proxy.newProxyInstance(
            Operation::class.java.classLoader,
            arrayOf(Operation::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getResult" -> future
                "getState" -> error("Operation state is not consulted by await")
                "toString" -> "PendingMealPhotoOperation"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.get(0)
                else -> error("Unexpected operation method ${method.name}")
            }
        } as Operation

        fun complete() = finish(null)

        fun fail(error: Throwable) = finish(error)

        private fun finish(error: Throwable?) {
            val pending = synchronized(lock) {
                if (completed) return
                failure = error
                completed = true
                listeners.toList().also { listeners.clear() }
            }
            pending.forEach { (listener, executor) -> executor.execute(listener) }
        }
    }
}
