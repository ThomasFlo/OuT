package com.homestock.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.homestock.data.remote.dto.ObjetDto

/**
 * Single event from the /vins/{id}/enrich/stream NDJSON stream.
 *
 * The server sends one JSON object per line; this sealed class models the
 * three event types the client cares about. Anything we don't recognise is
 * mapped to ``null`` so the Flow simply skips it.
 */
sealed interface WineEnrichEvent {
    /** Partial sommelier summary, growing as the model emits more tokens. */
    data class SummaryDelta(val text: String) : WineEnrichEvent

    /** Terminal event with the persisted DTO ready to be displayed. */
    data class Done(val objet: ObjetDto) : WineEnrichEvent

    /** Terminal event when the server (or the LLM) failed. */
    data class Failed(val message: String) : WineEnrichEvent
}

fun parseWineEnrichEvent(line: String, gson: Gson): WineEnrichEvent? {
    val root: JsonObject = runCatching {
        JsonParser.parseString(line).asJsonObject
    }.getOrNull() ?: return null
    return when (root.get("type")?.asString) {
        "summary" -> WineEnrichEvent.SummaryDelta(
            root.get("text")?.asString.orEmpty(),
        )
        "done" -> {
            val objet = root.getAsJsonObject("objet") ?: return null
            val dto = runCatching { gson.fromJson(objet, ObjetDto::class.java) }
                .getOrNull() ?: return null
            WineEnrichEvent.Done(dto)
        }
        "error" -> WineEnrichEvent.Failed(
            root.get("message")?.asString ?: "Erreur inconnue",
        )
        else -> null
    }
}
