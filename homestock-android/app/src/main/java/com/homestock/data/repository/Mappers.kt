package com.homestock.data.repository

import com.homestock.data.local.EmplacementEntity
import com.homestock.data.local.ObjetEntity
import com.homestock.data.local.ZoneEntity
import com.homestock.data.remote.dto.EmplacementDto
import com.homestock.data.remote.dto.ObjetDto
import com.homestock.data.remote.dto.ObjetRequest
import com.homestock.data.remote.dto.VinDto
import com.homestock.data.remote.dto.ZoneDto
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

internal fun parseIso(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .recoverCatching {
            // Backend may emit naive datetimes without offset.
            java.time.LocalDateTime.parse(value)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
        .getOrNull()
}

internal fun formatIso(epoch: Long?): String? =
    epoch?.let { DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(it)) }

fun ZoneDto.toEntity() = ZoneEntity(
    id = id, nom = nom, icone = icone, couleur = couleur,
    actif = actif, ordre = ordre, nbObjets = nbObjets,
)

fun EmplacementDto.toEntity() = EmplacementEntity(
    id = id, zoneId = zoneId, nomEmplacement = nomEmplacement,
    photoUrl = photoUrl, description = description,
)

fun ObjetDto.toEntity(existingLocalId: Long = 0): ObjetEntity = ObjetEntity(
    localId = existingLocalId,
    serverId = id,
    nom = nom,
    emplacementId = emplacementId,
    categorie = categorie,
    sousCategorie = sousCategorie,
    quantite = quantite,
    unite = unite,
    etat = etat,
    dateExpiration = parseIso(dateExpiration),
    photoUrl = photoUrl,
    notes = notes,
    ajoutePar = ajoutePar,
    dateAjout = parseIso(dateAjout) ?: System.currentTimeMillis(),
    dateModification = parseIso(dateModification) ?: System.currentTimeMillis(),
    vinAppellation = vin?.appellation,
    vinDomaine = vin?.domaine,
    vinMillesime = vin?.millesime,
    vinType = vin?.type,
    vinNombreBouteilles = vin?.nombreBouteilles,
    vinEmplacementRangee = vin?.emplacementRangee,
    vinNotesDegustation = vin?.notesDegustation,
    vinPrixAchat = vin?.prixAchat,
    vinABoirePartir = vin?.aBoirePartir,
    pendingSync = false,
    pendingDelete = false,
)

fun ObjetEntity.toVinDto(): VinDto? {
    val hasWine = listOf(
        vinAppellation, vinDomaine, vinType, vinEmplacementRangee, vinNotesDegustation,
    ).any { !it.isNullOrBlank() } ||
        listOf(vinMillesime, vinNombreBouteilles, vinABoirePartir).any { it != null } ||
        vinPrixAchat != null
    if (!hasWine) return null
    return VinDto(
        appellation = vinAppellation, domaine = vinDomaine, millesime = vinMillesime,
        type = vinType, nombreBouteilles = vinNombreBouteilles,
        emplacementRangee = vinEmplacementRangee, notesDegustation = vinNotesDegustation,
        prixAchat = vinPrixAchat, aBoirePartir = vinABoirePartir,
    )
}

fun ObjetEntity.toRequest() = ObjetRequest(
    nom = nom,
    emplacementId = emplacementId,
    categorie = categorie,
    sousCategorie = sousCategorie,
    quantite = quantite,
    unite = unite,
    etat = etat,
    dateExpiration = formatIso(dateExpiration),
    photoUrl = photoUrl,
    notes = notes,
    ajoutePar = ajoutePar,
    vin = toVinDto(),
)
