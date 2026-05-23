package com.homestock.voice

import com.homestock.data.local.ZoneEntity

/** Result of parsing a spoken "add" command. */
data class ParsedCommand(
    val nom: String,
    val zoneId: Long?,
    val zoneNom: String?,
    val emplacement: String?,
    val quantite: Int?,
)

/**
 * Lightweight rule-based NLP for French voice commands. No ML needed:
 * we match "range X dans Y" / "mets X dans Y" patterns and fuzzy-match the
 * spoken zone against the configured zone names.
 */
class VoiceParser {

    private val locationPrepositions = listOf(
        " dans le ", " dans la ", " dans les ", " dans l'", " dans ",
        " sur le ", " sur la ", " sur ", " au ", " à la ", " à l'", " en ",
    )
    private val leadingVerbs = listOf(
        "range les ", "range le ", "range la ", "range l'", "range ",
        "ranger les ", "ranger ", "mets les ", "mets le ", "mets la ", "mets ",
        "ajoute les ", "ajoute le ", "ajoute la ", "ajoute ", "place les ", "place ",
    )

    fun parseAddCommand(rawText: String, zones: List<ZoneEntity>): ParsedCommand {
        var text = rawText.trim().lowercase()
        for (verb in leadingVerbs) {
            if (text.startsWith(verb)) {
                text = text.removePrefix(verb)
                break
            }
        }

        val quantite = extractQuantity(text)

        // Split object vs. location on the first location preposition found.
        var nom = text
        var locationPart: String? = null
        for (prep in locationPrepositions) {
            val idx = text.indexOf(prep)
            if (idx >= 0) {
                nom = text.substring(0, idx).trim()
                locationPart = text.substring(idx + prep.length).trim()
                break
            }
        }

        val matchedZone = locationPart?.let { matchZone(it, zones) }
            ?: matchZone(text, zones)

        return ParsedCommand(
            nom = nom.replaceFirstChar { it.uppercase() }.ifBlank { rawText },
            zoneId = matchedZone?.id,
            zoneNom = matchedZone?.nom,
            emplacement = locationPart?.replaceFirstChar { it.uppercase() },
            quantite = quantite,
        )
    }

    /** Best fuzzy match of a fragment against zone names (token overlap). */
    fun matchZone(fragment: String, zones: List<ZoneEntity>): ZoneEntity? {
        val frag = normalize(fragment)
        var best: ZoneEntity? = null
        var bestScore = 0
        for (zone in zones) {
            val zoneTokens = normalize(zone.nom).split(" ").filter { it.length > 2 }
            val score = zoneTokens.count { frag.contains(it) }
            if (score > bestScore) {
                bestScore = score
                best = zone
            }
        }
        return if (bestScore > 0) best else null
    }

    private fun extractQuantity(text: String): Int? {
        val number = Regex("\\b(\\d+)\\b").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) return number
        val words = mapOf(
            "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4,
            "cinq" to 5, "six" to 6, "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10,
        )
        return words.entries.firstOrNull { text.contains(" ${it.key} ") }?.value
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace("[éèê]".toRegex(), "e")
        .replace("[àâ]".toRegex(), "a")
        .replace("[ùû]".toRegex(), "u")
        .replace("[ôö]".toRegex(), "o")
        .replace("ç", "c")
}
