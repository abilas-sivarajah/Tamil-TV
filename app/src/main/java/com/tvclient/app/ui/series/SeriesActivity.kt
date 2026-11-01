package com.tvclient.app.ui.series

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie

/**
 * Zeigt alle Episoden einer ausgewaehlten Serie (z. B. von Sun TV, Vijay TV, Zee Tamil).
 */
class SeriesActivity : FragmentActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val series = IntentCompat.getSerializableExtra(intent, EXTRA_SERIES, Movie::class.java)
            if (series == null) {
                finish()
                return
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, SeriesFragment.newInstance(series))
                .commit()
        }
    }

    companion object {
        private const val EXTRA_SERIES = "extra_series"

        fun intent(context: Context, series: Movie): Intent =
            Intent(context, SeriesActivity::class.java).putExtra(EXTRA_SERIES, series)
    }
}
