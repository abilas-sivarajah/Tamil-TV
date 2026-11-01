package com.tvclient.app.ui.browse

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.tvclient.app.R

/**
 * Host fuer das Leanback-Browse-Fragment. Mehr macht die Activity bewusst nicht.
 */
class MainActivity : FragmentActivity(R.layout.activity_main) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.browse_container, BrowseFragment())
                .commit()
        }
    }
}
