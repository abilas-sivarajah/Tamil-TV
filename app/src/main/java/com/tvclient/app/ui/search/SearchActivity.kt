package com.tvclient.app.ui.search

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.tvclient.app.R

class SearchActivity : FragmentActivity(R.layout.activity_search) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.search_container, SearchFragment())
                .commit()
        }
    }
}
