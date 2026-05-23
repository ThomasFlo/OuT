package com.homestock.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.homestock.HomeStockApp
import com.homestock.data.local.ObjetEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpirationNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notify(expiring: List<ObjetEntity>) {
        if (expiring.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val names = expiring.take(5).joinToString(", ") { it.nom }
        val notification = NotificationCompat.Builder(context, HomeStockApp.EXPIRATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${expiring.size} produit(s) bientôt périmé(s)")
            .setContentText(names)
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(EXPIRATION_NOTIF_ID, notification)
        }
    }

    private companion object {
        const val EXPIRATION_NOTIF_ID = 1001
    }
}
