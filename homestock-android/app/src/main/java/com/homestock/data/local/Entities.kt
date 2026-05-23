package com.homestock.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: Long,
    val nom: String,
    val icone: String = "home",
    val couleur: String = "#4A90D9",
    val actif: Boolean = true,
    val ordre: Int = 0,
    val nbObjets: Int = 0,
)

@Entity(
    tableName = "emplacements",
    indices = [Index("zoneId")],
)
data class EmplacementEntity(
    @PrimaryKey val id: Long,
    val zoneId: Long,
    val nomEmplacement: String,
    val photoUrl: String? = null,
    val description: String? = null,
)

@Entity(
    tableName = "objets",
    indices = [Index("emplacementId"), Index("categorie")],
)
data class ObjetEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val serverId: Long? = null,
    val nom: String,
    val emplacementId: Long,
    val categorie: String = "Autre",
    val sousCategorie: String? = null,
    val quantite: Int? = null,
    val unite: String? = null,
    val etat: String? = null,
    val dateExpiration: Long? = null,
    val photoUrl: String? = null,
    val notes: String? = null,
    val ajoutePar: String? = null,
    val dateAjout: Long = System.currentTimeMillis(),
    val dateModification: Long = System.currentTimeMillis(),
    // Wine extension (flattened; only used when categorie is the wine one).
    val vinAppellation: String? = null,
    val vinDomaine: String? = null,
    val vinMillesime: Int? = null,
    val vinType: String? = null,
    val vinNombreBouteilles: Int? = null,
    val vinEmplacementRangee: String? = null,
    val vinNotesDegustation: String? = null,
    val vinPrixAchat: Double? = null,
    val vinABoirePartir: Int? = null,
    // Offline-first bookkeeping.
    val pendingSync: Boolean = false,
    val pendingDelete: Boolean = false,
)
