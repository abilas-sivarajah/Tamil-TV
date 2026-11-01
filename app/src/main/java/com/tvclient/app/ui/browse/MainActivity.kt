package com.tvclient.app.ui.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.tvclient.app.R
import com.tvclient.app.data.update.AppRelease
import com.tvclient.app.data.update.UpdateManager
import com.tvclient.app.ui.update.UpdateActivity
import kotlinx.coroutines.launch

/**
 * Host fuer das Leanback-Browse-Fragment. Prueft ausserdem bei jedem Zurueckkehren auf den
 * Startbildschirm (gedrosselt), ob auf GitHub ein neues Release liegt.
 */
class MainActivity : FragmentActivity(R.layout.activity_main) {

    /** Gefundenes Update, das erst erscheint, wenn der Startbildschirm wieder sichtbar ist. */
    private var pendingRelease: AppRelease? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, BrowseFragment())
                .commit()
        }
    }

    override fun onResume() {
        super.onResume()
        pendingRelease?.let {
            showUpdate(it)
            return
        }
        // onResume statt onCreate: Fire OS haelt die App oft tagelang im Hintergrund am Leben
        lifecycleScope.launch {
            val release = UpdateManager.autoCheck(this@MainActivity) ?: return@launch
            // Inzwischen in den Player gewechselt? Dann nicht dazwischenfunken, sondern spaeter zeigen
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                showUpdate(release)
            } else {
                pendingRelease = release
            }
        }
    }

    private fun showUpdate(release: AppRelease) {
        pendingRelease = null
        startActivity(UpdateActivity.intent(this, release))
    }
}
