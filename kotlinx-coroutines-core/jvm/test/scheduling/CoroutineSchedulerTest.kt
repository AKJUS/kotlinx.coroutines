package kotlinx.coroutines.scheduling

import kotlinx.coroutines.testing.*
import org.junit.Test
import java.lang.Runnable
import java.util.concurrent.*
import kotlin.coroutines.*
import kotlin.test.*

class CoroutineSchedulerTest : TestBase() {
    private val contexts = listOf(NonBlockingContext, BlockingContext)

    @Test
    fun testModesExternalSubmission() { // Smoke
        CoroutineScheduler(1, 1).use {
            for (context in contexts) {
                val latch = CountDownLatch(1)
                it.dispatch(Runnable {
                    latch.countDown()
                }, context)

                latch.await()
            }
        }
    }

    @Test
    fun testModesInternalSubmission() { // Smoke
        CoroutineScheduler(2, 2).use {
            val latch = CountDownLatch(contexts.size)
            it.dispatch(Runnable {
                for (context in contexts) {
                    it.dispatch(Runnable {
                        latch.countDown()
                    }, context)
                }
            })

            latch.await()
        }
    }

    @Test
    fun testNonFairSubmission() {
        CoroutineScheduler(1, 1).use {
            val startLatch = CountDownLatch(1)
            val finishLatch = CountDownLatch(2)

            it.dispatch(Runnable {
                it.dispatch(Runnable {
                    expect(2)
                    finishLatch.countDown()
                })

                it.dispatch(Runnable {
                    expect(1)
                    finishLatch.countDown()
                })
            })

            startLatch.countDown()
            finishLatch.await()
            finish(3)
        }
    }

    @Test
    fun testFairSubmission() {
        CoroutineScheduler(1, 1).use {
            val startLatch = CountDownLatch(1)
            val finishLatch = CountDownLatch(2)

            it.dispatch(Runnable {
                it.dispatch(Runnable {
                    expect(1)
                    finishLatch.countDown()
                })

                it.dispatch(Runnable {
                    expect(2)
                    finishLatch.countDown()
                }, fair = true)
            })

            startLatch.countDown()
            finishLatch.await()
            finish(3)
        }
    }

    @Test
    fun testRngUniformDistribution() {
        CoroutineScheduler(1, 128).use { scheduler ->
            val worker = scheduler.Worker(1)
            testUniformDistribution(worker, 2)
            testUniformDistribution(worker, 4)
            testUniformDistribution(worker, 8)
            testUniformDistribution(worker, 12)
            testUniformDistribution(worker, 16)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNegativeCorePoolSize() {
        SchedulerCoroutineDispatcher(-1, 4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNegativeMaxPoolSize() {
        SchedulerCoroutineDispatcher(1, -4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCorePoolSizeGreaterThanMaxPoolSize() {
        SchedulerCoroutineDispatcher(4, 1)
    }

    @Test
    fun testSelfClose() {
        val dispatcher = SchedulerCoroutineDispatcher(1, 1)
        val latch = CountDownLatch(1)
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            dispatcher.close(); latch.countDown()
        })
        latch.await()
    }

    @Test
    fun testInterruptionCleanup() {
        SchedulerCoroutineDispatcher(1, 1).use {
            val executor = it.executor
            var latch = CountDownLatch(1)
            executor.execute {
                Thread.currentThread().interrupt()
                latch.countDown()
            }
            latch.await()
            Thread.sleep(100) // I am really sorry
            latch = CountDownLatch(1)
            executor.execute {
                try {
                    assertFalse(Thread.currentThread().isInterrupted)
                } finally {
                    latch.countDown()
                }
            }
            latch.await()
        }
    }

    private fun testUniformDistribution(worker: CoroutineScheduler.Worker, bound: Int) {
        val result = IntArray(bound)
        val iterations = 10_000_000
        repeat(iterations) {
            ++result[worker.nextInt(bound)]
        }

        val bucketSize = iterations / bound
        for (i in result) {
            val ratio = i.toDouble() / bucketSize
            // 10% deviation
            check(ratio <= 1.1)
            check(ratio >= 0.9)
        }
    }
}
