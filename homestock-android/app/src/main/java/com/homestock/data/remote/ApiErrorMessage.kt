package com.homestock.data.remote

import com.google.gson.JsonParser
import retrofit2.HttpException

/**
 * Pulls a human-readable French message out of a Retrofit HttpException
 * raised by our FastAPI backend.
 *
 * FastAPI serializes errors as ``{"detail": "..."}`` and Retrofit's default
 * exception message is just the status line (e.g. "HTTP 502 Bad Gateway"),
 * which hides the actual cause from the user. This helper reads the response
 * body, parses the JSON, and returns the ``detail`` field — falling back to
 * the raw body or the status line if neither is parseable.
 *
 * For non-HTTP exceptions (network, parse errors, etc.) the helper just
 * forwards the exception's own message.
 */
fun apiErrorMessage(throwable: Throwable): String? {
    val http = throwable as? HttpException ?: return throwable.message
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return "HTTP ${http.code()} ${http.message()}"
    return runCatching {
        JsonParser.parseString(raw).asJsonObject.get("detail")?.asString
    }.getOrNull() ?: raw.take(400)
}
