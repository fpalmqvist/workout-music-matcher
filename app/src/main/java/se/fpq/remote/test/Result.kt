package se.fpq.remote.test

/**
 * Simple Result wrapper for success/failure handling
 */
sealed class Result<T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure<T>(val exception: Exception) : Result<T>()

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure
    
    fun getOrNull(): T? = when (this) {
        is Success -> value
        is Failure -> null
    }
    
    fun exceptionOrNull(): Exception? = when (this) {
        is Success -> null
        is Failure -> exception
    }

    companion object {
        fun <T> success(value: T): Result<T> = Success(value)
        fun <T> failure(exception: Exception): Result<T> = Failure(exception)
    }
}

fun <T> Result<T>.getOrNull(): T? = when (this) {
    is Result.Success -> this.value
    is Result.Failure -> null
}

inline fun <T> Result<T>.onSuccess(block: (T) -> Unit): Result<T> = apply {
    if (this is Result.Success) {
        block(value)
    }
}

inline fun <T> Result<T>.onFailure(block: (Exception) -> Unit): Result<T> = apply {
    if (this is Result.Failure) {
        block(exception)
    }
}
