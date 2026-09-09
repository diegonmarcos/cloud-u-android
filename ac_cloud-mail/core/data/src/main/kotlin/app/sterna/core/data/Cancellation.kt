package app.sterna.core.data

import kotlinx.coroutines.CancellationException

/**
 * [Result.getOrElse] for suspending work: `runCatching` treats [CancellationException] as an
 */
inline fun <R, T : R> Result<T>.getOrElseUnlessCancelled(onFailure: (Throwable) -> R): R {
    val error = exceptionOrNull() ?: return getOrThrow()
    if (error is CancellationException) throw error
    return onFailure(error)
}

/**
 * Same rule when the [Result] itself is the value, not unwrapped: [getOrElseUnlessCancelled]
 */
fun <T> Result<T>.rethrowIfCancelled(): Result<T> {
    val error = exceptionOrNull()
    if (error is CancellationException) throw error
    return this
}
