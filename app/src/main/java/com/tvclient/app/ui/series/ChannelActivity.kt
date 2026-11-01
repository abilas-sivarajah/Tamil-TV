package com.tvclient.app.ui.series

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie

/**
 * Zeigt das Innere eines ausgewaehlten TV-Senders (Vijay TV, Sun TV, Zee Tamil)
 * mit den Bereichen: "Serien", "TV-Shows" und "Neueste Folgen".
 */
class ChannelActivity : FragmentActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val channel = IntentCompat.getSerializableExtra(intent, EXTRA_CHANNEL, Movie::class.java)
            if (channel == null) {
                finish()
                return
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, ChannelFragment.newInstance(channel))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_CHANNEL = "extra_channel"

        fun intent(context: Context, channel: Movie): Intent =
            Intent(context, ChannelActivity::class.java).putExtra(EXTRA_CHANNEL, channel)
    }
}
