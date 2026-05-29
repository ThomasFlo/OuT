package com.homestock.util

/** Lowercases and strips French accents for case/diacritic-insensitive matching. */
fun normalizeForSearch(s: String): String = s.lowercase()
    .replace("[éèêë]".toRegex(), "e")
    .replace("[àâä]".toRegex(), "a")
    .replace("[ùûü]".toRegex(), "u")
    .replace("[ôö]".toRegex(), "o")
    .replace("[îï]".toRegex(), "i")
    .replace("ç", "c")

/** True if every whitespace-separated term in [query] appears in [haystack]. */
fun matchesAllTerms(haystack: String, query: String): Boolean {
    val hay = normalizeForSearch(haystack)
    val terms = normalizeForSearch(query).split(" ").filter { it.isNotBlank() }
    return terms.isNotEmpty() && terms.all { hay.contains(it) }
}
