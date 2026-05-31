package com.homestock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.homestock.ui.theme.ErrorRed
import com.homestock.ui.theme.SuccessGreen
import com.homestock.ui.theme.WarningOrange

/** Returns a callback that fires a confirmation haptic, for important actions. */
@Composable
fun rememberConfirmHaptic(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
}

@Composable
fun ConnectionDot(connected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (connected) SuccessGreen else ErrorRed),
    )
}

@Composable
fun PhotoThumbnail(url: String?, modifier: Modifier = Modifier, size: Int = 56) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

/** Classifies how close an object is to its expiration date. */
enum class ExpirationStatus { NONE, SOON, EXPIRED }

fun expirationStatus(dateExpiration: Long?, withinDays: Int = 7): ExpirationStatus {
    if (dateExpiration == null) return ExpirationStatus.NONE
    val now = System.currentTimeMillis()
    return when {
        dateExpiration < now -> ExpirationStatus.EXPIRED
        dateExpiration - now <= withinDays * 86_400_000L -> ExpirationStatus.SOON
        else -> ExpirationStatus.NONE
    }
}

@Composable
fun ExpirationBadge(status: ExpirationStatus) {
    if (status == ExpirationStatus.NONE) return
    val (label, color) = when (status) {
        ExpirationStatus.EXPIRED -> "Périmé" to ErrorRed
        ExpirationStatus.SOON -> "Bientôt périmé" to WarningOrange
        ExpirationStatus.NONE -> return
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * Search result / object list row. The full location is shown FIRST and LARGE,
 * because finding "where is X" is the primary use case. The thumbnail prefers
 * the emplacement photo (where the object lives) over the object's own photo.
 */
@Composable
fun ObjetResultCard(
    nom: String,
    categorie: String,
    zoneNom: String?,
    emplacementNom: String?,
    photoUrl: String?,
    quantite: Int?,
    unite: String?,
    etat: String?,
    dateExpiration: Long? = null,
    emplacementPhotoUrl: String? = null,
    score: Double? = null,
    showScore: Boolean = false,
    onClick: () -> Unit,
) {
    val thumbnail = emplacementPhotoUrl?.takeIf { it.isNotBlank() } ?: photoUrl
    val expStatus = expirationStatus(dateExpiration)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoThumbnail(thumbnail, size = 64)
            if (!thumbnail.isNullOrBlank()) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // Location, prominent.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = buildString {
                            append(zoneNom ?: "—")
                            if (!emplacementNom.isNullOrBlank()) append("  →  $emplacementNom")
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.padding(top = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = nom,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (expStatus != ExpirationStatus.NONE) {
                        Spacer(Modifier.width(8.dp))
                        ExpirationBadge(expStatus)
                    }
                }
                Text(
                    text = buildString {
                        append(categorie)
                        if (quantite != null) append(" • $quantite${unite?.let { " $it" } ?: ""}")
                        if (!etat.isNullOrBlank()) append(" • $etat")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (showScore && score != null) {
                    Text(
                        text = "score ${"%.2f".format(score)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

fun parseColor(hex: String): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(Color(0xFF4A90D9))

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirmer",
    dismissLabel: String = "Annuler",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmColor = if (destructive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = confirmColor),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

@Composable
fun ZoneCard(
    nom: String,
    couleur: String,
    nbObjets: Int,
    onClick: () -> Unit,
    icone: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(parseColor(couleur)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    ZoneIcons.vectorFor(icone),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nom, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$nbObjets objet${if (nbObjets > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
