package com.homestock

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HomeStockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createExpirationChannel()
    }

    private fun createExpirationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXPIRATION_CHANNEL_ID,
                "Expirations",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Alertes de produits bientôt périmés" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXPIRATION_CHANNEL_ID = "homestock_expiration"
    }
}
