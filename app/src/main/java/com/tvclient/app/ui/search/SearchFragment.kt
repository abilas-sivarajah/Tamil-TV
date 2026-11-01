package com.tvclient.app.ui.search

import android.os.Bundle
import android.view.View
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.remote.MediaRepository
import com.tvclient.app.ui.browse.CardPresenter
import com.tvclient.app.ui.player.PlayerActivity
import com.tvclient.app.ui.series.ChannelActivity
import com.tvclient.app.ui.series.SeriesActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Suche mit der Bildschirmtastatur bzw. dem Fire-TV-Mikrofon.
 * Tastatureingaben werden entprellt, damit nicht pro Buchstabe eine Anfrage rausgeht.
 */
class SearchFragment : SearchSupportFragment(), SearchSupportFragment.SearchResultProvider {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val resultsAdapter = ArrayObjectAdapter(CardPresenter())
    private var searchJob: Job? = null
    private var lastQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSearchResultProvider(this)
        setOnItemViewClickedListener(
            OnItemViewClickedListener { _, item, _, _ ->
                (item as? Movie)?.let {
                    when {
                        it.isChannelFolder -> startActivity(ChannelActivity.intent(requireContext(), it))
                        it.isSeriesFolder -> startActivity(SeriesActivity.intent(requireContext(), it))
                        else -> startActivity(PlayerActivity.intent(requireContext(), it))
                    }
                }
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // In Leanback ist das der Platzhalter in der leeren Suchleiste
        title = getString(R.string.search_hint)
        setSearchQuery("", false)
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        scheduleSearch(newQuery.orEmpty(), DEBOUNCE_MS)
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        // Bewusstes Abschicken sucht immer neu - so laesst sich eine Suche wiederholen
        scheduleSearch(query.orEmpty(), 0L, force = true)
        return true
    }

    private fun scheduleSearch(query: String, debounceMs: Long, force: Boolean = false) {
        val trimmed = query.trim()
        searchJob?.cancel()

        if (trimmed.length < MIN_QUERY_LENGTH) {
            lastQuery = ""
            rowsAdapter.clear()
            return
        }
        // lastQuery = Treffer, die gerade angezeigt werden. Erst nach Erfolg setzen, sonst
        // blockiert eine abgebrochene oder fehlgeschlagene Suche jede Wiederholung.
        if (trimmed == lastQuery && !force) return

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            MediaRepository.search(trimmed)
                .onSuccess { response ->
                    lastQuery = trimmed
                    showResults(trimmed, response.items)
                }
                .onFailure {
                    lastQuery = ""
                    rowsAdapter.clear()
                    showHeader(getString(R.string.search_failed))
                }
        }
    }

    private fun showResults(query: String, items: List<Movie>) {
        resultsAdapter.clear()
        if (items.isEmpty()) {
            rowsAdapter.clear()
            showHeader(getString(R.string.search_no_results, query))
            return
        }
        resultsAdapter.addAll(0, items)
        rowsAdapter.clear()
        rowsAdapter.add(
            ListRow(
                HeaderItem(0, getString(R.string.search_results, query, items.size)),
                resultsAdapter
            )
        )
    }

    /** Zeile ohne Inhalt, nur als Textmeldung ueber der leeren Trefferliste. */
    private fun showHeader(text: String) {
        rowsAdapter.add(ListRow(HeaderItem(0, text), ArrayObjectAdapter(CardPresenter())))
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val MIN_QUERY_LENGTH = 2
    }
}
