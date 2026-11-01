package com.tvclient.app.ui.browse

import androidx.leanback.widget.ArrayObjectAdapter
import com.tvclient.app.data.model.Category

/**
 * Haelt den Pagination-Zustand einer Katalogzeile.
 * Eine Instanz pro Kategorie, erzeugt beim Aufbau der Oberflaeche.
 */
class CatalogRow(
    val category: Category,
    val adapter: ArrayObjectAdapter
) {
    var nextPage: Int = 1
        private set
    var hasMore: Boolean = true
        private set
    var isLoading: Boolean = false
    /** true, sobald mindestens eine Seite erfolgreich geladen wurde. */
    var isLoaded: Boolean = false
        private set

    fun onPageLoaded(loadedPage: Int, hasMore: Boolean) {
        nextPage = loadedPage + 1
        this.hasMore = hasMore
        isLoaded = true
        isLoading = false
    }

    fun onPageFailed() {
        isLoading = false
    }

    /** Keine weiteren Seiten anfordern, z. B. nach einem leeren Ergebnis. */
    fun exhaust() {
        hasMore = false
        isLoading = false
    }
}
