package com.homestock.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Deck
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Fireplace
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalLaundryService
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.OutdoorGrill
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Roofing
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material.icons.filled.Yard
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated catalog of zone icons. Each entry has a stable string [key] (stored
 * server-side in zones.icone) and a Compose [vector] for rendering.
 *
 * The keys are chosen to be human-meaningful and stable; backend seed names
 * that differ (e.g. "door-front") are translated by [ALIASES] so pre-seeded
 * zones still show a sensible icon.
 */
data class ZoneIcon(val key: String, val label: String, val vector: ImageVector)

object ZoneIcons {
    val CATALOG: List<ZoneIcon> = listOf(
        ZoneIcon("home", "Maison", Icons.Filled.Home),
        ZoneIcon("door", "Entrée", Icons.Filled.DoorFront),
        ZoneIcon("room", "Pièce", Icons.Filled.MeetingRoom),
        ZoneIcon("weekend", "Salon", Icons.Filled.Weekend),
        ZoneIcon("chair", "Bureau", Icons.Filled.Chair),
        ZoneIcon("dining", "Salle à manger", Icons.Filled.Restaurant),
        ZoneIcon("kitchen", "Cuisine", Icons.Filled.Kitchen),
        ZoneIcon("shelves", "Etageres", Icons.Filled.Storage),
        ZoneIcon("bed", "Chambre", Icons.Filled.Bed),
        ZoneIcon("bathtub", "Salle de bain", Icons.Filled.Bathtub),
        ZoneIcon("wc", "WC", Icons.Filled.Wc),
        ZoneIcon("closet", "Dressing", Icons.Filled.Checkroom),
        ZoneIcon("laundry", "Buanderie", Icons.Filled.LocalLaundryService),
        ZoneIcon("stairs", "Escalier", Icons.Filled.Stairs),
        ZoneIcon("roofing", "Grenier", Icons.Filled.Roofing),
        ZoneIcon("cellar", "Cave", Icons.Filled.WineBar),
        ZoneIcon("garage", "Garage", Icons.Filled.Garage),
        ZoneIcon("workshop", "Atelier", Icons.Filled.Handyman),
        ZoneIcon("warehouse", "Abri / Remise", Icons.Filled.Warehouse),
        ZoneIcon("firewood", "Abri à bois", Icons.Filled.Fireplace),
        ZoneIcon("box", "Stockage", Icons.Filled.Inventory2),
        ZoneIcon("deck", "Terrasse", Icons.Filled.Deck),
        ZoneIcon("grill", "Barbecue", Icons.Filled.OutdoorGrill),
        ZoneIcon("pool", "Cabanon piscine", Icons.Filled.Pool),
        ZoneIcon("yard", "Jardin", Icons.Filled.Yard),
    )

    private val BY_KEY: Map<String, ZoneIcon> = CATALOG.associateBy { it.key }

    /** Backend seed names (and a few synonyms) → catalog keys. */
    private val ALIASES: Map<String, String> = mapOf(
        "door-front" to "door",
        "horizontal-rule" to "room",
        "desk" to "chair",
        "local-laundry-service" to "laundry",
        "outdoor-grill" to "grill",
        "bathroom" to "bathtub",
        "couch" to "weekend",
        "sofa" to "weekend",
    )

    const val DEFAULT_KEY = "home"

    /** Resolves any stored icon string to a renderable vector, with fallback. */
    fun vectorFor(key: String?): ImageVector {
        if (key == null) return Icons.Filled.Place
        BY_KEY[key]?.let { return it.vector }
        ALIASES[key]?.let { alias -> BY_KEY[alias]?.let { return it.vector } }
        return Icons.Filled.Place
    }
}

/**
 * Curated zone colour swatches (hex). The first two match the backend's
 * seeded defaults (indoor blue, outdoor teal).
 */
val ZONE_COLORS: List<String> = listOf(
    "#4A90D9", // blue (indoor default)
    "#00897B", // teal (outdoor default)
    "#E53935", // red
    "#FB8C00", // orange
    "#FDD835", // yellow
    "#43A047", // green
    "#8E24AA", // purple
    "#D81B60", // pink
    "#6D4C41", // brown
    "#546E7A", // blue grey
    "#1E88E5", // bright blue
    "#000000", // black
)
