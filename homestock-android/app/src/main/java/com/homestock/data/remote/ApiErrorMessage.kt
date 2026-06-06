package com.homestock.data.remote

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Pulls a human-readable French message out of a Retrofit / OkHttp exception.
 *
 * FastAPI serializes errors as ``{"detail": "..."}`` and Retrofit's default
 * exception message is just the status line (e.g. "HTTP 502 Bad Gateway"),
 * which hides the actual cause from the user. This helper reads the response
 * body, parses the JSON, and returns the ``detail`` field — falling back to
 * the raw body or the status line if neither is parseable.
 *
 * For ConnectException (wrong IP/port) we surface the target address and
 * suggest checking Settings so the user doesn't waste time debugging.
 */
fun apiErrorMessage(throwable: Throwable, nasAddress: String? = null): String? {
    // Unwrap OkHttp wrappers to reach the root cause.
    val cause = generateSequence(throwable) { it.cause }.firstOrNull {
        it is ConnectException || it is SocketTimeoutException
    } ?: throwable

    if (cause is ConnectException) {
        val addr = nasAddress?.let { " ($it)" } ?: ""
        return "Impossible de joindre le serveur NAS$addr.\n" +
            "Vérifie l'adresse IP et le port dans Paramètres → Serveur NAS."
    }

    val http = throwable as? HttpException ?: return throwable.message
    val raw = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return "HTTP ${http.code()} ${http.message()}"
    return runCatching {
        JsonParser.parseString(raw).asJsonObject.get("detail")?.asString
    }.getOrNull() ?: raw.take(400)
}
