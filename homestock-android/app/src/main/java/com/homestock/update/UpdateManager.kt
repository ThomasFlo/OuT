package com.homestock.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.homestock.BuildConfig
import com.homestock.data.remote.ApiService
import com.homestock.data.remote.dto.AppVersionDto
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.ResponseBody
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the in-app self-update flow.
 *
 * Lifecycle of an update:
 * 1. [checkForUpdate] hits ``GET /app/version`` and decides whether the
 *    server's ``versionCode`` is strictly greater than ours.
 * 2. If so, the UI prompts the user. On consent, [downloadApk] streams the
 *    APK to ``cacheDir/updates/homestock-latest.apk`` and verifies the
 *    SHA-256 against the metadata so a corrupt or swapped binary is caught
 *    BEFORE the system installer ever sees it.
 * 3. [installApk] hands the verified file to the system PackageInstaller
 *    via a content:// URI exposed by our FileProvider.
 *
 * Security boundary: Android itself rejects any APK that is not signed
 * with the same keystore as the currently installed app, so even if our
 * SHA check is bypassed, a tampered APK cannot replace HomeStock.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Application,
    private val api: ApiService,
) {

    private val tag = "UpdateManager"

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** Returns metadata for a newer release, or ``null`` if we're up to date. */
    suspend fun checkForUpdate(): AppVersionDto? {
        val info = runCatching { api.appVersion() }.getOrElse { e ->
            Log.d(tag, "Update check failed: ${e.message}")
            return null
        }
        if (!info.available) return null
        return info.takeIf { it.versionCode > currentVersionCode }
    }

    /**
     * Streams the APK into the app cache and verifies the SHA-256.
     * Returns the local file or throws if the hash doesn't match.
     */
    suspend fun downloadAndVerifyApk(expectedSha256: String?): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Use a fresh filename each time to avoid serving a stale partial.
        val tmp = File(dir, "homestock-latest.apk.part")
        val target = File(dir, "homestock-latest.apk")
        tmp.delete()
        target.delete()

        val body: ResponseBody = api.downloadApk()
        body.byteStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }

        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256(tmp)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                throw SecurityException(
                    "Empreinte SHA-256 inattendue (téléchargement corrompu ou altéré)",
                )
            }
        }

        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }

    /** True if the user has already granted "install from unknown sources" for us. */
    fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /**
     * Opens the system settings page where the user grants the
     * "Install unknown apps" permission to HomeStock. The caller should
     * re-check [canInstallPackages] after the user returns.
     */
    fun openInstallSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Hands [apk] to the system PackageInstaller. The user sees Android's
     * standard "install this update?" UI; signature checks are enforced by
     * the OS at that point.
     */
    fun installApk(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        context.startActivity(intent)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
