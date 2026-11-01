package com.tvclient.app.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
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
import com.tvclient.app.ui.browse.CardPresenter
import com.tvclient.app.ui.browse.friendlyMessage
import com.tvclient.app.ui.player.PlayerActivity
import com.tvclient.app.ui.search.SearchActivity
import kotlinx.coroutines.launch

/**
 * Episoden-Uebersicht fuer eine Serie (z. B. "Kayal", "Siragadikka Aasai", "Bigg Boss Tamil"):
 * - Erste Zeile zeigt die aktuellsten Folgen und laedt beim Rechts-Scrollen automatisch nach.
 * - Weitere Zeilen bieten direkten Schnellzugriff auf aeltere Seiten (je 12 Folgen pro Zeile),
 *   damit man mit der Fernbedienung auch schnell Wochen zurueckspringen kann.
 */
class SeriesFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()

    private var series: Movie? = null
    private val pageAdapters = mutableMapOf<Int, ArrayObjectAdapter>()
    private val pageRows = mutableMapOf<Int, ListRow>()
    private val loadedPages = mutableSetOf<Int>()
    private val loadingPages = mutableSetOf<Int>()
    /** Hoechste Seite, die es geben kann - schrumpft, sobald das Ende der Serie bekannt ist. */
    private var lastPage = MAX_PAGES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        series = arguments?.let {
            BundleCompat.getSerializable(it, ARG_SERIES, Movie::class.java)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        adapter = rowsAdapter
        setupInitialRows()
    }

    private fun setupUi() {
        title = series?.title ?: getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val clicked = item as? Movie ?: return@OnItemViewClickedListener
            if (clicked.isSeriesFolder) {
                startActivity(SeriesActivity.intent(requireContext(), clicked))
            } else {
                startActivity(PlayerActivity.intent(requireContext(), clicked))
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, _, _, row ->
            val listRow = row as? ListRow ?: return@OnItemViewSelectedListener
            val pageNum = listRow.headerItem?.id?.toInt() ?: return@OnItemViewSelectedListener
            loadPageIfNeeded(pageNum)
            // Naechste Seite schon mal vorbereiten, wenn man nach unten scrollt
            if (pageNum + 1 <= lastPage) {
                ensurePageRowExists(pageNum + 1)
                loadPageIfNeeded(pageNum + 1)
            }
        }
    }

    private fun setupInitialRows() {
        for (page in 1..INITIAL_PAGE_ROWS) {
            ensurePageRowExists(page)
        }
        loadPageIfNeeded(1)
        loadPageIfNeeded(2)
    }

    private fun ensurePageRowExists(page: Int) {
        if (pageAdapters.containsKey(page) || page > lastPage) return
        val rowTitle = if (page == 1) {
            "Neueste Folgen (Seite 1)"
        } else {
            "Ältere Folgen – Seite $page"
        }
        val listAdapter = ArrayObjectAdapter(cardPresenter)
        val row = ListRow(HeaderItem(page.toLong(), rowTitle), listAdapter)
        pageAdapters[page] = listAdapter
        pageRows[page] = row
        rowsAdapter.add(row)
    }

    /**
     * Ende der Serie bekannt: die vorab angelegten, leer gebliebenen Zeilen dahinter entfernen.
     * Seiten davor bleiben ladbar, auch wenn ihr erster Versuch fehlgeschlagen ist.
     */
    private fun trimToLastPage(page: Int) {
        if (page >= lastPage) return
        lastPage = page
        pageRows.keys.filter { it > page }.forEach { p ->
            pageRows.remove(p)?.let { rowsAdapter.remove(it) }
            pageAdapters.remove(p)
        }
    }

    private fun loadPageIfNeeded(page: Int) {
        val targetSeries = series ?: return
        if (page in loadedPages || page in loadingPages || page > lastPage) return

        val listAdapter = pageAdapters[page] ?: return
        loadingPages.add(page)

        viewLifecycleOwner.lifecycleScope.launch {
            MediaRepository.seriesEpisodes(targetSeries.id, page)
                .onSuccess { response ->
                    loadingPages.remove(page)
                    loadedPages.add(page)
                    if (response.items.isEmpty()) {
                        // Zeile 1 bleibt stehen, damit die Ansicht nicht komplett leer ist
                        trimToLastPage(maxOf(page - 1, 1))
                        return@onSuccess
                    }
                    listAdapter.addAll(0, response.items)
                    if (!response.hasMore) {
                        trimToLastPage(page)
                    } else if (page + 1 <= lastPage) {
                        ensurePageRowExists(page + 1)
                    }
                }
                .onFailure {
                    loadingPages.remove(page)
                    if (page == 1 && isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "Fehler beim Laden der Folgen: ${it.friendlyMessage()}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    companion object {
        private const val ARG_SERIES = "arg_series"
        private const val INITIAL_PAGE_ROWS = 5
        private const val MAX_PAGES = 30

        fun newInstance(series: Movie): SeriesFragment =
            SeriesFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_SERIES, series)
                }
            }
    }
}
