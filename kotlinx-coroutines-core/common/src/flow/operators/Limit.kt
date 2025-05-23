@file:JvmMultifileClass
@file:JvmName("FlowKt")

package kotlinx.coroutines.flow

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.internal.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlinx.coroutines.flow.flow as safeFlow
import kotlinx.coroutines.flow.internal.unsafeFlow as flow

/**
 * Returns a flow that ignores first [count] elements.
 * Throws [IllegalArgumentException] if [count] is negative.
 */
public fun <T> Flow<T>.drop(count: Int): Flow<T> {
    require(count >= 0) { "Drop count should be non-negative, but had $count" }
    return flow {
        var skipped = 0
        collect { value ->
            if (skipped >= count) emit(value) else ++skipped
        }
    }
}

/**
 * Returns a flow containing all elements except first elements that satisfy the given predicate.
 */
public fun <T> Flow<T>.dropWhile(predicate: suspend (T) -> Boolean): Flow<T> = flow {
    var matched = false
    collect { value ->
        if (matched) {
            emit(value)
        } else if (!predicate(value)) {
            matched = true
            emit(value)
        }
    }
}

/**
 * Returns a flow that contains first [count] elements.
 * When [count] elements are consumed, the original flow is cancelled.
 * Throws [IllegalArgumentException] if [count] is not positive.
 */
public fun <T> Flow<T>.take(count: Int): Flow<T> {
    require(count > 0) { "Requested element count $count should be positive" }
    return flow {
        val ownershipMarker = Any()
        var consumed = 0
        try {
            collect { value ->
                // Note: this for take is not written via collectWhile on purpose.
                // It checks condition first and then makes a tail-call to either emit or emitAbort.
                // This way normal execution does not require a state machine, only a termination (emitAbort).
                // See "TakeBenchmark" for comparision of different approaches.
                if (++consumed < count) {
                    return@collect emit(value)
                } else {
                    return@collect emitAbort(value, ownershipMarker)
                }
            }
        } catch (e: AbortFlowException) {
            e.checkOwnership(owner = ownershipMarker)
        }
    }
}

private suspend fun <T> FlowCollector<T>.emitAbort(value: T, ownershipMarker: Any) {
    emit(value)
    throw AbortFlowException(ownershipMarker)
}

/**
 * Returns a flow that contains first elements satisfying the given [predicate].
 *
 * Note, that the resulting flow does not contain the element on which the [predicate] returned `false`.
 * See [transformWhile] for a more flexible operator.
 */
public fun <T> Flow<T>.takeWhile(predicate: suspend (T) -> Boolean): Flow<T> = flow {
    // This return is needed to work around a bug in JS BE: KT-39227
    return@flow collectWhile { value ->
        if (predicate(value)) {
            emit(value)
            true
        } else {
            false
        }
    }
}

/**
 * Applies [transform] function to each value of the given flow while this
 * function returns `true`.
 *
 * The receiver of the `transformWhile` is [FlowCollector] and thus `transformWhile` is a
 * flexible function that may transform emitted element, skip it or emit it multiple times.
 *
 * This operator generalizes [takeWhile] and can be used as a building block for other operators.
 * For example, a flow of download progress messages can be completed when the
 * download is done but emit this last message (unlike `takeWhile`):
 *
 * ```
 * fun Flow<DownloadProgress>.completeWhenDone(): Flow<DownloadProgress> =
 *     transformWhile { progress ->
 *         emit(progress) // always emit progress
 *         !progress.isDone() // continue while download is not done
 *     }
 * ```
 */
public fun <T, R> Flow<T>.transformWhile(
    @BuilderInference transform: suspend FlowCollector<R>.(value: T) -> Boolean
): Flow<R> =
    safeFlow { // Note: safe flow is used here, because collector is exposed to transform on each operation
        // This return is needed to work around a bug in JS BE: KT-39227
        return@safeFlow collectWhile { value ->
            transform(value)
        }
    }

// Internal building block for non-tailcalling flow-truncating operators
internal suspend inline fun <T> Flow<T>.collectWhile(crossinline predicate: suspend (value: T) -> Boolean) {
    val collector = object : FlowCollector<T> {
        override suspend fun emit(value: T) {
            // Note: we are checking predicate first, then throw. If the predicate does suspend (calls emit, for example)
            // the resulting code is never tail-suspending and produces a state-machine
            if (!predicate(value)) {
                throw AbortFlowException(this)
            }
        }
    }
    try {
        collect(collector)
    } catch (e: AbortFlowException) {
        e.checkOwnership(collector)
        // The task might have been cancelled before AbortFlowException was thrown.
        coroutineContext.ensureActive()
    }
}
