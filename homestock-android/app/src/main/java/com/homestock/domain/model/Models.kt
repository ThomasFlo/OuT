package com.homestock.domain.model

import com.homestock.data.local.ObjetEntity

/** Enriched search result shown in the UI (object + where it lives + score). */
data class SearchResult(
    val objet: ObjetEntity,
    val zoneNom: String?,
    val emplacementNom: String?,
    val score: Double,
    val emplacementPhotoUrl: String? = null,
)

object Categories {
    const val WINE = "Boissons & cave à vins"

    val ALL = listOf(
        "Outillage",
        "Électricité & plomberie",
        "Jardinage",
        "Sport & loisirs",
        "Vêtements & chaussures",
        "Ski & montagne",
        "Équipement de camping",
        "Décoration & saisonnier",
        "Alimentation & épicerie",
        WINE,
        "Produits ménagers",
        "Pharmacie & santé",
        "Papiers & documents",
        "Électronique & câbles",
        "Jouets & jeux",
        "Livres & médias",
        "Bricolage & visserie",
        "Autre",
    )

    val FOOD = setOf("Alimentation & épicerie", "Pharmacie & santé")
}

object WineTypes {
    val ALL = listOf("Rouge", "Blanc", "Rosé", "Champagne", "Autre")
}

object EtatOptions {
    val ALL = listOf("Neuf", "Bon état", "Usé", "À remplacer")
}
