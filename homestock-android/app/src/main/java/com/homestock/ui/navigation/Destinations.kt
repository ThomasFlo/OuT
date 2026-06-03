package com.homestock.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val SEARCH = "search"
    const val ZONES = "zones"
    const val CATEGORIES = "categories"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val WINE = "wine"
    const val ADD_WINE = "wine/add"
    const val ADD = "add"
    const val ZONE_DETAIL = "zone/{zoneId}"
    const val CATEGORY_DETAIL = "category/{categorie}"
    const val OBJET_DETAIL = "objet/{localId}"
    const val OBJET_EDIT = "objet/{localId}/edit"

    fun addPrefill(
        nom: String? = null,
        zoneId: Long? = null,
        emplacement: String? = null,
        quantite: Int? = null,
    ): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val params = buildList {
            nom?.takeIf { it.isNotBlank() }?.let { add("nom=${enc(it)}") }
            zoneId?.let { add("zoneId=$it") }
            emplacement?.takeIf { it.isNotBlank() }?.let { add("emp=${enc(it)}") }
            quantite?.let { add("qty=$it") }
        }
        return if (params.isEmpty()) ADD else "$ADD?${params.joinToString("&")}"
    }

    fun zoneDetail(zoneId: Long) = "zone/$zoneId"
    fun categoryDetail(cat: String) = "category/${java.net.URLEncoder.encode(cat, "UTF-8")}"
    fun objetDetail(localId: Long) = "objet/$localId"
    fun editObjet(localId: Long) = "objet/$localId/edit"
}

enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    SEARCH(Routes.SEARCH, "Rechercher", Icons.Filled.Search),
    ZONES(Routes.ZONES, "Zones", Icons.Filled.Home),
    CATEGORIES(Routes.CATEGORIES, "Catégories", Icons.Filled.Category),
    SETTINGS(Routes.SETTINGS, "Paramètres", Icons.Filled.Settings),
}
