package com.homestock.data.remote.dto

import com.google.gson.annotations.SerializedName

/** Server-side metadata about the latest published Android APK. */
data class AppVersionDto(
    @SerializedName("version_code") val versionCode: Int = 0,
    @SerializedName("version_name") val versionName: String = "0.0",
    val sha256: String? = null,
    @SerializedName("size_bytes") val sizeBytes: Long? = null,
    val notes: String? = null,
    val available: Boolean = false,
)

data class CategoryDto(
    val id: Long,
    val nom: String,
    val ordre: Int = 0,
    val protegee: Boolean = false,
    @SerializedName("nb_objets") val nbObjets: Int = 0,
)

data class CategoryRequest(val nom: String)

data class ZoneDto(
    val id: Long,
    val nom: String,
    val icone: String = "home",
    val couleur: String = "#4A90D9",
    val actif: Boolean = true,
    val ordre: Int = 0,
    @SerializedName("nb_objets") val nbObjets: Int = 0,
)

data class ZoneRequest(
    val nom: String,
    val icone: String = "home",
    val couleur: String = "#4A90D9",
    val actif: Boolean = true,
    val ordre: Int = 0,
)

data class EmplacementDto(
    val id: Long,
    @SerializedName("zone_id") val zoneId: Long,
    @SerializedName("nom_emplacement") val nomEmplacement: String,
    @SerializedName("photo_url") val photoUrl: String? = null,
    val description: String? = null,
)

data class EmplacementRequest(
    @SerializedName("zone_id") val zoneId: Long,
    @SerializedName("nom_emplacement") val nomEmplacement: String,
    @SerializedName("photo_url") val photoUrl: String? = null,
    val description: String? = null,
)

data class VinDto(
    val appellation: String? = null,
    val domaine: String? = null,
    val millesime: Int? = null,
    val type: String? = null,
    @SerializedName("nombre_bouteilles") val nombreBouteilles: Int? = null,
    @SerializedName("emplacement_rangee") val emplacementRangee: String? = null,
    @SerializedName("notes_degustation") val notesDegustation: String? = null,
    @SerializedName("prix_achat") val prixAchat: Double? = null,
    @SerializedName("a_boire_partir") val aBoirePartir: Int? = null,
)

data class ObjetDto(
    val id: Long,
    val nom: String,
    @SerializedName("emplacement_id") val emplacementId: Long,
    val categorie: String = "Autre",
    @SerializedName("sous_categorie") val sousCategorie: String? = null,
    val quantite: Int? = null,
    val unite: String? = null,
    val etat: String? = null,
    @SerializedName("date_expiration") val dateExpiration: String? = null,
    @SerializedName("photo_url") val photoUrl: String? = null,
    val notes: String? = null,
    @SerializedName("ajoute_par") val ajoutePar: String? = null,
    @SerializedName("date_ajout") val dateAjout: String? = null,
    @SerializedName("date_modification") val dateModification: String? = null,
    val emplacement: EmplacementDto? = null,
    val vin: VinDto? = null,
)

data class ObjetSearchResultDto(
    val id: Long,
    val nom: String,
    @SerializedName("emplacement_id") val emplacementId: Long,
    val categorie: String = "Autre",
    @SerializedName("sous_categorie") val sousCategorie: String? = null,
    val quantite: Int? = null,
    val unite: String? = null,
    val etat: String? = null,
    @SerializedName("photo_url") val photoUrl: String? = null,
    val notes: String? = null,
    val emplacement: EmplacementDto? = null,
    val vin: VinDto? = null,
    val score: Double = 0.0,
    @SerializedName("zone_nom") val zoneNom: String? = null,
)

data class ObjetRequest(
    val nom: String,
    @SerializedName("emplacement_id") val emplacementId: Long,
    val categorie: String = "Autre",
    @SerializedName("sous_categorie") val sousCategorie: String? = null,
    val quantite: Int? = null,
    val unite: String? = null,
    val etat: String? = null,
    @SerializedName("date_expiration") val dateExpiration: String? = null,
    @SerializedName("photo_url") val photoUrl: String? = null,
    val notes: String? = null,
    @SerializedName("ajoute_par") val ajoutePar: String? = null,
    val vin: VinDto? = null,
)

data class SearchRequest(
    val query: String,
    val limit: Int = 20,
    val threshold: Double = 0.4,
)

data class PhotoUploadResponse(
    @SerializedName("photo_url") val photoUrl: String,
)

data class WineStats(
    @SerializedName("total_bouteilles") val totalBouteilles: Int,
    @SerializedName("par_type") val parType: Map<String, Int> = emptyMap(),
)
