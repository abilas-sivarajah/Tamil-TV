package com.tvclient.app.data.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.tvclient.app.BuildConfig
import com.tvclient.app.data.remote.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Selbst-Update ueber GitHub Releases:
 * 1. [checkForUpdate] fragt das neueste Release ab und vergleicht dessen Tag (v1.2.3)
 *    mit der installierten Version.
 * 2. [startUpdate] laedt die APK, prueft Groesse, SHA-256 und Paketnamen und uebergibt sie
 *    an den PackageInstaller. Das Ergebnis kommt im [UpdateInstallReceiver] an.
 *
 * Die APK haengt der Workflow .github/workflows/release.yml an jedes neue Release.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val USER_AGENT = "Tamil-TV-Updater"
    /** Dateiname der APK im Release - muss zum Release-Workflow passen. */
    private const val APK_ASSET_NAME = "Tamil_TV.apk"
    private const val SNOOZE_DURATION_MS = 24L * 60 * 60 * 1000
    /** Automatische Pruefung hoechstens so oft - GitHub erlaubt ohne Login 60 Anfragen pro Stunde. */
    private const val AUTO_CHECK_INTERVAL_MS = 5L * 60 * 1000
    internal const val ACTION_INSTALL_STATUS = "com.tvclient.app.UPDATE_INSTALL_STATUS"

    private const val PREFS = "updater"
    private const val PREF_SNOOZED_TAG = "snoozed_tag"
    private const val PREF_SNOOZED_UNTIL = "snoozed_until"

    /** Normale Zertifikatspruefung - hier kommt Code an, der installiert wird. Siehe [execute]. */
    private val strictHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    @Volatile private var pendingApk: File? = null
    @Volatile private var lastAutoCheckAt = 0L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String
        get() = BuildConfig.VERSION_NAME

    /**
     * Fuer den Startbildschirm: hoechstens alle [AUTO_CHECK_INTERVAL_MS], Fehler bleiben still.
     * Liefert nur dann ein Release, wenn der Update-Dialog erscheinen soll.
     */
    suspend fun autoCheck(context: Context): AppRelease? {
        val now = System.currentTimeMillis()
        if (_state.value != UpdateState.Idle || now - lastAutoCheckAt < AUTO_CHECK_INTERVAL_MS) return null
        lastAutoCheckAt = now
        return when (val result = checkForUpdate(context)) {
            is UpdateCheckResult.Available -> result.release
            is UpdateCheckResult.Failed -> {
                Log.w(TAG, "Automatische Update-Pruefung: ${result.message}")
                null
            }
            UpdateCheckResult.UpToDate -> null
        }
    }

    /**
     * Prueft bei GitHub Releases, ob eine neuere Version verfuegbar ist.
     * @param force true ignoriert ein "Spaeter" des Nutzers (manuelle Suche)
     */
    suspend fun checkForUpdate(context: Context, force: Boolean = false): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            cleanupDownloads(context)

            val repo = BuildConfig.UPDATE_REPO
            if (!repo.contains('/')) {
                return@withContext UpdateCheckResult.Failed("Kein gültiges GitHub-Repo konfiguriert ($repo).")
            }

            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()

            val body = try {
                execute(request).use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub API HTTP ${response.code}")
                        val message = when (response.code) {
                            404 -> "Noch kein Release auf GitHub veröffentlicht."
                            403, 429 -> "GitHub-Anfragelimit erreicht. Bitte später erneut versuchen."
                            else -> "GitHub antwortet nicht wie erwartet (HTTP ${response.code})."
                        }
                        return@withContext UpdateCheckResult.Failed(message)
                    }
                    response.body?.string()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Verbindung zu GitHub Releases fehlgeschlagen", e)
                return@withContext UpdateCheckResult.Failed("Keine Verbindung zu GitHub. Bitte Internetverbindung prüfen.")
            } ?: return@withContext UpdateCheckResult.Failed("Leere Antwort von GitHub.")

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext UpdateCheckResult.Failed("Antwort von GitHub konnte nicht gelesen werden.")

            val tagName = json.stringOrNull("tag_name")
                ?: return@withContext UpdateCheckResult.Failed("Release ohne Versionsnummer gefunden.")
            val remoteVersion = tagName.trim().removePrefix("v").removePrefix("V")
            Log.d(TAG, "Installiert=$currentVersion, Release=$remoteVersion")

            if (!isNewerVersion(remoteVersion, currentVersion)) {
                return@withContext UpdateCheckResult.UpToDate
            }
            if (!force && isSnoozed(context, tagName)) {
                return@withContext UpdateCheckResult.UpToDate
            }

            // Direkt nach dem Veroeffentlichen baut der Workflow die APK noch - dann gibt es noch keine
            val assetList = json.optJSONArray("assets")
            val apks = (0 until (assetList?.length() ?: 0))
                .mapNotNull { assetList?.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            val asset = apks.firstOrNull { it.optString("name").equals(APK_ASSET_NAME, ignoreCase = true) }
                ?: apks.singleOrNull()
            val downloadUrl = asset?.stringOrNull("browser_download_url")
                ?: return@withContext UpdateCheckResult.Failed(
                    "Version $remoteVersion wird gerade noch gebaut. Bitte in ein paar Minuten erneut versuchen."
                )

            UpdateCheckResult.Available(
                AppRelease(
                    tagName = tagName,
                    versionName = remoteVersion,
                    title = json.stringOrNull("name") ?: tagName,
                    changelog = cleanChangelog(json.stringOrNull("body").orEmpty()),
                    downloadUrl = downloadUrl,
                    assetName = asset.optString("name"),
                    fileSize = asset.optLong("size", 0L),
                    sha256 = asset.stringOrNull("digest")
                        ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.lowercase()
                )
            )
        }

    /** optString liefert bei JSON-null den Text "null" - daher eigene Variante. */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifBlank { null }

    /** Entfernt Markdown-Syntax und GitHub-Boilerplate, damit die Notizen auf dem TV lesbar sind. */
    internal fun cleanChangelog(raw: String): String {
        val cleaned = raw.lineSequence()
            .map { it.trimEnd() }
            .filterNot { it.trimStart().startsWith("**Full Changelog**", ignoreCase = true) }
            .filterNot { it.trimStart().startsWith("<!--") }
            .map { line ->
                line.replace(Regex("^\\s*#{1,6}\\s*"), "")
                    .replace(Regex("^\\s*[-*+]\\s+"), "• ")
                    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                    .replace(Regex(" by @\\S+ in https?://\\S+"), "")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return cleaned.ifBlank { "Allgemeine Verbesserungen und Fehlerbehebungen." }
    }

    /** Vergleicht zwei Versionen wie "1.2.0" und "1.1.9" Stelle fuer Stelle. */
    internal fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteParts = remote.split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteParts.size, currentParts.size)) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }
        return false
    }

    /**
     * Erst mit normaler Zertifikatspruefung. Nur wenn ein altes Fire OS die Zertifikatskette
     * von GitHub nicht kennt, ueber den toleranten App-Client - eine untergeschobene APK
     * scheitert trotzdem, denn Android installiert nur Updates mit derselben Signatur.
     */
    private fun execute(request: Request): Response = try {
        strictHttp.newCall(request).execute()
    } catch (e: SSLException) {
        Log.w(TAG, "TLS-Pruefung fehlgeschlagen, nutze toleranten Client", e)
        ApiClient.okHttp.newCall(request).execute()
    }

    // ------------------- "Spaeter" -------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Blendet den Hinweis fuer dieses Release 24 Stunden lang aus. */
    fun snooze(context: Context, release: AppRelease) {
        prefs(context).edit {
            putString(PREF_SNOOZED_TAG, release.tagName)
            putLong(PREF_SNOOZED_UNTIL, System.currentTimeMillis() + SNOOZE_DURATION_MS)
        }
    }

    private fun isSnoozed(context: Context, tagName: String): Boolean {
        val prefs = prefs(context)
        return prefs.getString(PREF_SNOOZED_TAG, null) == tagName &&
            System.currentTimeMillis() < prefs.getLong(PREF_SNOOZED_UNTIL, 0L)
    }

    // ------------------- Download & Installation -------------------

    /**
     * Laedt die APK herunter, prueft Groesse, SHA-256 und Paketnamen und uebergibt sie an den
     * PackageInstaller. Der Fortschritt wird ueber [state] gemeldet.
     */
    fun startUpdate(context: Context, release: AppRelease) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        _state.value = UpdateState.Downloading(0L, release.fileSize)
        downloadJob = scope.launch {
            try {
                val apk = downloadVerified(appContext, release)
                pendingApk = apk
                installOrRequestPermission(appContext, apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Idle
                throw e
            } catch (e: UpdateException) {
                Log.w(TAG, "Update fehlgeschlagen: ${e.message}")
                _state.value = UpdateState.Failed(e.message ?: "Update fehlgeschlagen.")
            } catch (e: Exception) {
                Log.e(TAG, "Fehler beim Update", e)
                val reason = if (e is IOException) "Verbindung unterbrochen" else (e.localizedMessage ?: e.javaClass.simpleName)
                _state.value = UpdateState.Failed("Download fehlgeschlagen: $reason")
            }
        }
    }

    fun cancelUpdate() {
        downloadJob?.cancel()
        downloadJob = null
        pendingApk = null
        _state.value = UpdateState.Idle
    }

    /** Setzt Fehler- und Wartezustaende zurueck, z. B. wenn der Dialog geschlossen wird. */
    fun resetIfFinished() {
        if (_state.value is UpdateState.Failed || _state.value is UpdateState.NeedsInstallPermission) {
            pendingApk = null
            _state.value = UpdateState.Idle
        }
    }

    /** Aus onResume: setzt die Installation fort, nachdem der Nutzer die Berechtigung erteilt hat. */
    fun resumePendingInstall(context: Context) {
        val apk = pendingApk ?: return
        if (_state.value == UpdateState.NeedsInstallPermission && canInstallPackages(context)) {
            scope.launch { installApk(context.applicationContext, apk) }
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Manche Fire-TV-Versionen haben diese Seite nicht: direkt den System-Installer fragen lassen
            Log.w(TAG, "Einstellung fuer unbekannte Quellen nicht verfuegbar", e)
            pendingApk?.let { apk -> scope.launch { installApk(context.applicationContext, apk) } }
        }
    }

    private class UpdateException(message: String) : Exception(message)

    private suspend fun downloadVerified(context: Context, release: AppRelease): File {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val destFile = File(updatesDir, release.assetName)

        // Bereits vollstaendig heruntergeladen (z. B. beim erneuten Versuch nach einem Abbruch)?
        if (destFile.exists() && isValidDownload(destFile, release)) {
            return destFile
        }
        destFile.delete()

        val partFile = File(updatesDir, "${release.assetName}.part")
        partFile.delete()

        val request = Request.Builder()
            .url(release.downloadUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        execute(request).use { response ->
            if (!response.isSuccessful) {
                throw UpdateException("Download fehlgeschlagen (HTTP ${response.code}).")
            }
            val body = response.body ?: throw UpdateException("Leere Antwort vom Update-Server.")
            val totalBytes = if (release.fileSize > 0) release.fileSize else body.contentLength()
            var downloaded = 0L
            var lastReport = 0L

            body.byteStream().use { input ->
                partFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 100) {
                            lastReport = now
                            _state.value = UpdateState.Downloading(downloaded, totalBytes)
                        }
                    }
                }
            }
            _state.value = UpdateState.Downloading(downloaded, totalBytes)
        }

        _state.value = UpdateState.Verifying
        if (!isValidDownload(partFile, release)) {
            partFile.delete()
            throw UpdateException("Die heruntergeladene Datei ist beschädigt. Bitte erneut versuchen.")
        }
        if (!partFile.renameTo(destFile)) {
            throw UpdateException("Update konnte nicht gespeichert werden.")
        }

        val archivePackage = readArchivePackageName(context, destFile)
        if (archivePackage != context.packageName) {
            destFile.delete()
            throw UpdateException("Die APK gehört zu einer anderen App ($archivePackage).")
        }
        return destFile
    }

    private fun isValidDownload(file: File, release: AppRelease): Boolean {
        if (release.fileSize > 0 && file.length() != release.fileSize) return false
        val expected = release.sha256 ?: return file.length() > 0
        return sha256Of(file) == expected
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun readArchivePackageName(context: Context, apk: File): String? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName }.getOrNull()

    private fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun installOrRequestPermission(context: Context, apk: File) {
        if (!canInstallPackages(context)) {
            _state.value = UpdateState.NeedsInstallPermission
            return
        }
        installApk(context, apk)
    }

    /**
     * Installiert die APK ueber eine PackageInstaller-Session. Das funktioniert ab Android 5
     * ohne FileProvider und liefert ein Ergebnis zurueck (siehe [UpdateInstallReceiver]).
     * Ab Android 12 kann sich die App so auch ohne Rueckfrage selbst aktualisieren.
     */
    private fun installApk(context: Context, apk: File) {
        _state.value = UpdateState.Installing
        val installer = context.packageManager.packageInstaller
        // Reste frueherer, nicht abgeschlossener Versuche verwerfen
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }

        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                    session.fsync(out)
                }
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                // MUTABLE: das System traegt das Ergebnis als Extras in den Intent ein
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Konnte Installation nicht starten", e)
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            _state.value = UpdateState.Failed(
                "Installation konnte nicht gestartet werden: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    internal fun onInstallStatus(status: Int, systemMessage: String?) {
        Log.d(TAG, "Installationsstatus $status: $systemMessage")
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                pendingApk = null
                UpdateState.Idle
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Failed("Installation abgebrochen.")
            PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateState.Failed("Die Installation wurde vom System blockiert.")
            PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateState.Failed(
                "Das Update ist anders signiert als die installierte App. " +
                    "Bitte die App einmal deinstallieren und die neue Version manuell installieren."
            )
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateState.Failed("Das Update ist mit diesem Gerät nicht kompatibel.")
            PackageInstaller.STATUS_FAILURE_INVALID -> UpdateState.Failed("Die heruntergeladene APK ist ungültig.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Nicht genug Speicherplatz für das Update.")
            else -> UpdateState.Failed("Installation fehlgeschlagen${systemMessage?.let { ": $it" } ?: "."}")
        }
    }

    /** Loescht alte Update-Dateien, sofern gerade kein Update laeuft. */
    private fun cleanupDownloads(context: Context) {
        if (_state.value != UpdateState.Idle) return
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
}

/** Empfaengt das Ergebnis der PackageInstaller-Session. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateManager.ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirmIntent == null) {
                UpdateManager.onInstallStatus(PackageInstaller.STATUS_FAILURE, "Bestätigungsdialog fehlt")
                return
            }
            try {
                context.startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                UpdateManager.onInstallStatus(PackageInstaller.STATUS_FAILURE, e.localizedMessage)
            }
            return
        }
        UpdateManager.onInstallStatus(status, message)
    }
}
