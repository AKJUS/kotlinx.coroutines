@file:Suppress("DeferredResultUnused")

package kotlinx.coroutines

import kotlinx.coroutines.testing.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.junit.*
import org.junit.Test
import org.junit.rules.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.*

class FailFastOnStartTest : TestBase() {

    @Rule
    @JvmField
    public val timeout: Timeout = Timeout.seconds(5)

    @Test
    fun testLaunch() = runTest(expected = ::mainException) {
        launch(Dispatchers.Main) {}
    }

    @Test
    fun testLaunchLazy() = runTest(expected = ::mainException) {
        val job = launch(Dispatchers.Main, start = CoroutineStart.LAZY) { fail() }
        job.join()
    }

    @Test
    fun testLaunchUndispatched() = runTest(expected = ::mainException) {
        launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
            yield()
            fail()
        }
    }

    @Test
    fun testAsync() = runTest(expected = ::mainException) {
        async(Dispatchers.Main) {}
    }

    @Test
    fun testAsyncLazy() = runTest(expected = ::mainException) {
        val job = async(Dispatchers.Main, start = CoroutineStart.LAZY) { fail() }
        job.await()
    }

    @Test
    fun testWithContext() = runTest(expected = ::mainException) {
        withContext(Dispatchers.Main) {
            fail()
        }
    }

    @Test
    fun testProduce() = runTest(expected = ::mainException) {
        produce<Int>(Dispatchers.Main) { fail() }
    }

    @Test
    fun testActor() = runTest(expected = ::mainException) {
        actor<Int>(Dispatchers.Main) { fail() }
    }

    @Test
    fun testActorLazy() = runTest(expected = ::mainException) {
        val actor = actor<Int>(Dispatchers.Main, start = CoroutineStart.LAZY) { fail() }
        actor.send(1)
    }

    private fun mainException(e: Throwable): Boolean {
        return e is IllegalStateException && e.message?.contains("Module with the Main dispatcher is missing") ?: false
    }

    @Test
    fun testProduceNonChild() = runTest(expected = ::mainException) {
        produce<Int>(Job() + Dispatchers.Main) { fail() }
    }

    @Test
    fun testAsyncNonChild() = runTest(expected = ::mainException) {
        async<Int>(Job() + Dispatchers.Main) { fail() }
    }

    @Test
    fun testFlowOn() {
        // See #4142, this test ensures that `coroutineScope { produce(failingDispatcher, ATOMIC) }`
        // rethrows an exception. It does not help with the completion of such a coroutine though.
        // `suspend {}` + start coroutine with custom `completion` to avoid waiting for test completion
        expect(1)
        val caller = suspend {
            try {
                emptyFlow<Int>().flowOn(Dispatchers.Main).collect { fail() }
            } catch (e: Throwable) {
                assertTrue(mainException(e))
                expect(2)
            }
        }

        caller.startCoroutine(Continuation(EmptyCoroutineContext) {
            finish(3)
        })
    }
}
