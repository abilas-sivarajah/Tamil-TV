package com.tvclient.app.data.update

import java.io.Serializable

/** Ein GitHub-Release mit passender APK. Serializable, weil es per Intent an den Update-Dialog geht. */
data class AppRelease(
    val tagName: String,
    val versionName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val assetName: String,
    val fileSize: Long,
    /** SHA-256 (hex) aus dem "digest"-Feld der GitHub API, falls vorhanden. */
    val sha256: String?
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

sealed interface UpdateCheckResult {
    data class Available(val release: AppRelease) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdateState {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : UpdateState
    /** Android 8+: Die App darf noch keine APKs installieren ("Unbekannte Apps installieren"). */
    data object NeedsInstallPermission : UpdateState
    /** Installation an das System uebergeben, wartet ggf. auf Bestaetigung durch den Nutzer. */
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}
