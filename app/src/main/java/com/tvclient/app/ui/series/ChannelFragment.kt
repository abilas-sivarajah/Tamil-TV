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
import com.tvclient.app.data.model.Category
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.remote.MediaRepository
import com.tvclient.app.ui.browse.CardPresenter
import com.tvclient.app.ui.browse.CatalogRow
import com.tvclient.app.ui.browse.friendlyMessage
import com.tvclient.app.ui.player.PlayerActivity
import com.tvclient.app.ui.search.SearchActivity
import kotlinx.coroutines.launch

/**
 * Zeigt innerhalb eines TV-Senders (z. B. Vijay TV, Sun TV, Zee Tamil):
 * 1. "Serien" (alle laufenden Serien des Senders -> Klick oeffnet Folgen)
 * 2. "TV-Shows" (alle Shows des Senders -> Klick oeffnet Folgen)
 * 3. "Neueste Folgen" (direkt die aktuellsten Folgen des Senders, endlos scrollbar)
 */
class ChannelFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val cardPresenter = CardPresenter()
    private val rowStates = mutableMapOf<ArrayObjectAdapter, CatalogRow>()

    private var channel: Movie? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channel = arguments?.let {
            BundleCompat.getSerializable(it, ARG_CHANNEL, Movie::class.java)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUi()
        adapter = rowsAdapter
        setupChannelRows()
    }

    private fun setupUi() {
        title = channel?.title ?: getString(R.string.app_name)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.brand_accent)

        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val clicked = item as? Movie ?: return@OnItemViewClickedListener
            when {
                clicked.isChannelFolder -> startActivity(ChannelActivity.intent(requireContext(), clicked))
                clicked.isSeriesFolder -> startActivity(SeriesActivity.intent(requireContext(), clicked))
                else -> startActivity(PlayerActivity.intent(requireContext(), clicked))
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            maybeLoadMore(item, row)
        }
    }

    private fun setupChannelRows() {
        val sections = listOf(
            Category("serials", "Serien"),
            Category("shows", "TV-Shows"),
            Category("latest", "Neueste Folgen")
        )

        sections.forEachIndexed { index, cat ->
            val listAdapter = ArrayObjectAdapter(cardPresenter)
            val state = CatalogRow(cat, listAdapter)
            rowStates[listAdapter] = state
            rowsAdapter.add(ListRow(HeaderItem(index.toLong(), cat.title), listAdapter))
            loadNextPage(state)
        }
    }

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
        val targetChannel = channel ?: return
        if (state.isLoading || !state.hasMore) return
        state.isLoading = true
        val requestedPage = state.nextPage

        viewLifecycleOwner.lifecycleScope.launch {
            MediaRepository.channelRow(targetChannel.id, state.category.id, requestedPage)
                .onSuccess { response ->
                    if (response.items.isEmpty()) {
                        state.exhaust()
                        if (!state.isLoaded) state.onPageLoaded(requestedPage, hasMore = false)
                        return@onSuccess
                    }
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
                    if (!state.isLoaded && isAdded) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_row, state.category.title, it.friendlyMessage()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    companion object {
        private const val ARG_CHANNEL = "arg_channel"
        private const val PREFETCH_DISTANCE = 6

        fun newInstance(channel: Movie): ChannelFragment =
            ChannelFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CHANNEL, channel)
                }
            }
    }
}
