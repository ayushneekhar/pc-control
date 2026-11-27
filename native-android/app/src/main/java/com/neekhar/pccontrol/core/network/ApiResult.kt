package com.neekhar.pccontrol.core.network

import java.io.IOException

/**
 * Typed network result wrapper returned by [PcApiClient] helpers.
 * `statusCode == 0` indicates a transport/timeout/decode failure rather than an HTTP response.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(
        val data: T,
        val statusCode: Int = 200,
    ) : ApiResult<T>

    data class Error(
        val statusCode: Int,
        val message: String,
        val rawBody: String? = null,
        val cause: Throwable? = null,
    ) : ApiResult<Nothing>
}

val <T> ApiResult<T>.isSuccess: Boolean
    get() = this is ApiResult.Success

val <T> ApiResult<T>.isError: Boolean
    get() = this is ApiResult.Error

fun <T> ApiResult<T>.getOrNull(): T? = when (this) {
    is ApiResult.Success -> data
    is ApiResult.Error -> null
}

fun <T> ApiResult<T>.errorOrNull(): ApiResult.Error? = when (this) {
    is ApiResult.Success -> null
    is ApiResult.Error -> this
}

fun <T> ApiResult<T>.getOrThrow(): T = when (this) {
    is ApiResult.Success -> data
    is ApiResult.Error -> throw ApiException(statusCode, message, rawBody, cause)
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), statusCode)
    is ApiResult.Error -> this
}

inline fun <T> ApiResult<T>.onSuccess(action: (T) -> Unit): ApiResult<T> {
    if (this is ApiResult.Success) action(data)
    return this
}

inline fun <T> ApiResult<T>.onError(action: (ApiResult.Error) -> Unit): ApiResult<T> {
    if (this is ApiResult.Error) action(this)
    return this
}

class ApiException(
    val statusCode: Int,
    override val message: String,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

data class SseEvent(
    val id: String? = null,
    val event: String = "message",
    val data: String,
)

sealed interface SseMessage {
    data object Connected : SseMessage
    data class Event(val event: SseEvent) : SseMessage
    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val error: String? = null,
    ) : SseMessage
}

data class ResolvedChatTarget(
    val baseUrl: String,
    val headers: Map<String, String>,
    val isCustom: Boolean,
)
