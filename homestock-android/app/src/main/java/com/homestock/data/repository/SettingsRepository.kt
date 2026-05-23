package com.homestock.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "homestock_settings")

data class AppSettings(
    val nasHost: String = "192.168.1.100",
    val nasPort: Int = 8080,
    val user1: String = "Utilisateur 1",
    val user2: String = "Utilisateur 2",
    val currentUser: String = "Utilisateur 1",
    val voiceLanguage: String = "fr-FR",
    val notificationsEnabled: Boolean = true,
    val setupCompleted: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("nas_host")
        val PORT = intPreferencesKey("nas_port")
        val USER1 = stringPreferencesKey("user1")
        val USER2 = stringPreferencesKey("user2")
        val CURRENT = stringPreferencesKey("current_user")
        val LANG = stringPreferencesKey("voice_lang")
        val NOTIF = booleanPreferencesKey("notifications")
        val SETUP = booleanPreferencesKey("setup_completed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            nasHost = p[Keys.HOST] ?: "192.168.1.100",
            nasPort = p[Keys.PORT] ?: 8080,
            user1 = p[Keys.USER1] ?: "Utilisateur 1",
            user2 = p[Keys.USER2] ?: "Utilisateur 2",
            currentUser = p[Keys.CURRENT] ?: (p[Keys.USER1] ?: "Utilisateur 1"),
            voiceLanguage = p[Keys.LANG] ?: "fr-FR",
            notificationsEnabled = p[Keys.NOTIF] ?: true,
            setupCompleted = p[Keys.SETUP] ?: false,
        )
    }

    suspend fun setNas(host: String, port: Int) = context.dataStore.edit {
        it[Keys.HOST] = host
        it[Keys.PORT] = port
    }

    suspend fun setUsers(user1: String, user2: String) = context.dataStore.edit {
        it[Keys.USER1] = user1
        it[Keys.USER2] = user2
    }

    suspend fun setCurrentUser(name: String) = context.dataStore.edit {
        it[Keys.CURRENT] = name
    }

    suspend fun setVoiceLanguage(lang: String) = context.dataStore.edit {
        it[Keys.LANG] = lang
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = context.dataStore.edit {
        it[Keys.NOTIF] = enabled
    }

    suspend fun setSetupCompleted(done: Boolean) = context.dataStore.edit {
        it[Keys.SETUP] = done
    }
}
