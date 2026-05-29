package com.homestock.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.homestock.data.repository.HomeStockRepository
import com.homestock.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic background check: notifies about objects expiring within 3 days.
 * Reuses [ExpirationNotifier] and the repository's Room-backed query, so it
 * works without a live NAS connection.
 */
@HiltWorker
class ExpirationCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: HomeStockRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: ExpirationNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.notificationsEnabled) return Result.success()
        // Refresh from the NAS when reachable, but never fail the job if offline.
        runCatching { repository.refreshAll() }
        val expiring = repository.expiringWithin(3)
        notifier.notify(expiring)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "homestock_expiration_check"
    }
}
