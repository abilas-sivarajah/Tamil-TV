package com.tvclient.app.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.leanback.widget.Row
import androidx.lifecycle.lifecycleScope
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.remote.MediaRepository
import com.tvclient.app.ui.player.PlayerActivity
import com.tvclient.app.ui.search.SearchActivity
import com.tvclient.app.ui.series.ChannelActivity
import com.tvclient.app.ui.series.SeriesActivity
import kotlinx.coroutines.launch

/**
 * Startbildschirm: eine horizontale Zeile pro Kategorie, komplett per Steuerkreuz
 * bedienbar. Weitere Seiten werden nachgeladen, sobald der Fokus sich dem Ende
 * einer Zeile naehert (Endless Scrolling ohne sichtbaren Ladebalken).
 */
class BrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()

    /** Zeilen-Zustand, nachschlagbar ueber den Adapter der jeweiligen ListRow. */
    private val rowStates = mutableMapOf<ArrayObjectAdapter, CatalogRow>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        adapter = rowsAdapter
        loadCatalog()
    }

    private fun setupUi() {
        title = getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            (item as? Movie)?.let { openPlayer(it) }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            maybeLoadMore(item, row)
        }
    }

    private fun openPlayer(movie: Movie) {
        when {
            movie.isChannelFolder -> startActivity(ChannelActivity.intent(requireContext(), movie))
            movie.isSeriesFolder -> startActivity(SeriesActivity.intent(requireContext(), movie))
            else -> startActivity(PlayerActivity.intent(requireContext(), movie))
        }
    }

    private fun loadCatalog() {
        viewLifecycleOwner.lifecycleScope.launch {
            MediaRepository.categories()
                .onFailure { showError(getString(R.string.error_categories, it.friendlyMessage())) }
                .onSuccess { categories ->
                    if (categories.isEmpty()) {
                        showError(getString(R.string.error_empty_catalog))
                        return@onSuccess
                    }
                    categories.forEachIndexed { index, category ->
                        val listAdapter = ArrayObjectAdapter(cardPresenter)
                        rowStates[listAdapter] = CatalogRow(category, listAdapter)
                        rowsAdapter.add(
                            ListRow(HeaderItem(index.toLong(), category.title), listAdapter)
                        )
                    }
                    // Nur die erste Zeile sofort laden; der Rest folgt beim Durchnavigieren.
                    rowStates.values.take(INITIAL_ROWS_TO_PREFETCH).forEach { loadNextPage(it) }
                }
        }
    }

    /**
     * Laedt die naechste Seite, wenn der Fokus weniger als [PREFETCH_DISTANCE]
     * Karten vom Zeilenende entfernt ist - oder die Zeile noch leer ist.
     */
    private fun maybeLoadMore(item: Any?, row: Row?) {
        val listRow = row as? ListRow ?: return
        val listAdapter = listRow.adapter as? ArrayObjectAdapter ?: return
        val state = rowStates[listAdapter] ?: return

        if (!state.isLoaded) {
            loadNextPage(state)
            return
        }
        val index = if (item == null) 0 else listAdapter.indexOf(item)
        if (index >= listAdapter.size() - PREFETCH_DISTANCE) {
            loadNextPage(state)
        }
    }

    private fun loadNextPage(state: CatalogRow) {
        if (state.isLoading || !state.hasMore) return
        state.isLoading = true
        val requestedPage = state.nextPage

        viewLifecycleOwner.lifecycleScope.launch {
            MediaRepository.movies(state.category.id, requestedPage)
                .onSuccess { response ->
                    if (response.items.isEmpty()) {
                        state.exhaust()
                        if (!state.isLoaded) state.onPageLoaded(requestedPage, hasMore = false)
                        return@onSuccess
                    }
                    // Duplikate vermeiden, falls das Backend ueberlappende Seiten liefert
                    val known = (0 until state.adapter.size())
                        .mapNotNull { (state.adapter.get(it) as? Movie)?.id }
                        .toHashSet()
                    val fresh = response.items.filter { it.id !in known }
                    if (fresh.isNotEmpty()) {
                        state.adapter.addAll(state.adapter.size(), fresh)
                    }
                    state.onPageLoaded(requestedPage, response.hasMore)
                }
                .onFailure {
                    state.onPageFailed()
                    if (!state.isLoaded) {
                        showError(
                            getString(R.string.error_row, state.category.title, it.friendlyMessage())
                        )
                    }
                }
        }
    }

    private fun showError(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PREFETCH_DISTANCE = 6
        private const val INITIAL_ROWS_TO_PREFETCH = 5
    }
}

/** Kurze, lesbare Fehlermeldung statt eines Stacktraces auf dem Fernseher. */
internal fun Throwable.friendlyMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName
