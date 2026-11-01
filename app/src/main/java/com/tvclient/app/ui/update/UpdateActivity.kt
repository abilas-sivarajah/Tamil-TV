package com.tvclient.app.ui.update

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tvclient.app.R
import com.tvclient.app.data.update.AppRelease
import com.tvclient.app.data.update.UpdateManager
import com.tvclient.app.data.update.UpdateState
import com.tvclient.app.databinding.ActivityUpdateBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Update-Dialog: zeigt Version und Aenderungen eines neuen Releases und fuehrt durch
 * Download, Berechtigung und Installation. Den eigentlichen Ablauf steuert [UpdateManager],
 * diese Activity zeigt nur dessen [UpdateState] an.
 */
class UpdateActivity : FragmentActivity() {

    private lateinit var binding: ActivityUpdateBinding
    private lateinit var release: AppRelease

    private var primaryAction: (() -> Unit)? = null
    private var secondaryAction: (() -> Unit)? = null
    private var renderedStateType: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        release = IntentCompat.getSerializableExtra(intent, EXTRA_RELEASE, AppRelease::class.java)
            ?: run { finish(); return }

        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setFinishOnTouchOutside(false)
        // Auf dem TV feste Breite, auf schmalen Handys nicht breiter als der Bildschirm
        val metrics = resources.displayMetrics
        val width = minOf((DIALOG_WIDTH_DP * metrics.density).toInt(), (metrics.widthPixels * 0.92f).toInt())
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        binding.versionLine.text = buildString {
            append(getString(R.string.update_version, release.versionName))
            if (release.fileSize > 0) append(" · ").append(formatMegabytes(release.fileSize))
            append(" · ").append(getString(R.string.update_installed, UpdateManager.currentVersion))
        }
        binding.changelog.text = release.changelog
        binding.changelog.movementMethod = ScrollingMovementMethod()

        binding.primaryButton.setOnClickListener { primaryAction?.invoke() }
        binding.secondaryButton.setOnClickListener { secondaryAction?.invoke() }
        onBackPressedDispatcher.addCallback(this) { onBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpdateManager.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Zurueck aus "Unbekannte Apps installieren": Installation fortsetzen
        UpdateManager.resumePendingInstall(this)
    }

    private fun render(state: UpdateState) {
        binding.errorText.isVisible = state is UpdateState.Failed
        if (state is UpdateState.Failed) binding.errorText.text = state.message

        when (state) {
            is UpdateState.Downloading -> {
                val label = if (state.totalBytes > 0) {
                    getString(
                        R.string.update_downloading_of,
                        formatMegabytes(state.downloadedBytes),
                        formatMegabytes(state.totalBytes),
                        (state.progress * 100).toInt()
                    )
                } else {
                    getString(R.string.update_downloading, formatMegabytes(state.downloadedBytes))
                }
                showProgress(label, progress = state.progress.takeIf { state.totalBytes > 0 })
                setButtons(primary = getString(R.string.update_cancel) to { UpdateManager.cancelUpdate() })
            }
            UpdateState.Verifying -> {
                showProgress(getString(R.string.update_verifying), progress = null)
                setButtons()
            }
            UpdateState.NeedsInstallPermission -> {
                hideProgress()
                showStatus(getString(R.string.update_needs_permission, getString(R.string.app_name)))
                setButtons(
                    primary = getString(R.string.update_open_settings) to {
                        UpdateManager.openInstallPermissionSettings(this)
                    },
                    secondary = getString(R.string.update_cancel) to ::dismissLater
                )
            }
            UpdateState.Installing -> {
                showProgress(getString(R.string.update_installing), progress = null)
                setButtons(primary = getString(R.string.update_close) to ::finish)
            }
            UpdateState.Idle, is UpdateState.Failed -> {
                hideProgress()
                showStatus(null)
                val primaryText = if (state is UpdateState.Failed) R.string.update_retry else R.string.update_now
                setButtons(
                    primary = getString(primaryText) to { UpdateManager.startUpdate(this, release) },
                    secondary = getString(R.string.update_later) to ::dismissLater
                )
            }
        }

        // Die Buttons wechseln mit dem Zustand: Fokus neu setzen, sonst reagiert die Fernbedienung nicht
        if (state.javaClass != renderedStateType) {
            renderedStateType = state.javaClass
            if (binding.primaryButton.isVisible) binding.primaryButton.requestFocus()
        }
    }

    private fun setButtons(
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null
    ) {
        binding.primaryButton.isVisible = primary != null
        binding.primaryButton.text = primary?.first
        primaryAction = primary?.second
        binding.secondaryButton.isVisible = secondary != null
        binding.secondaryButton.text = secondary?.first
        secondaryAction = secondary?.second
    }

    /** [progress] null = unbestimmter Balken. */
    private fun showProgress(label: String, progress: Float?) {
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = progress == null
        if (progress != null) binding.progress.progress = (progress * binding.progress.max).toInt()
        showStatus(label)
    }

    private fun hideProgress() {
        binding.progress.visibility = View.GONE
    }

    private fun showStatus(text: String?) {
        binding.statusText.isVisible = text != null
        binding.statusText.text = text
    }

    private fun onBack() {
        when (UpdateManager.state.value) {
            is UpdateState.Downloading, UpdateState.Verifying -> UpdateManager.cancelUpdate()
            UpdateState.Installing -> finish()
            else -> dismissLater()
        }
    }

    /** "Spaeter": Hinweis fuer dieses Release 24 Stunden lang nicht mehr automatisch zeigen. */
    private fun dismissLater() {
        UpdateManager.snooze(this, release)
        UpdateManager.resetIfFinished()
        finish()
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0))

    companion object {
        private const val EXTRA_RELEASE = "extra_release"
        private const val DIALOG_WIDTH_DP = 580

        fun intent(context: Context, release: AppRelease): Intent =
            Intent(context, UpdateActivity::class.java).putExtra(EXTRA_RELEASE, release)
    }
}
