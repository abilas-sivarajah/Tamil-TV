package com.tvclient.app.data.remote

import com.tvclient.app.data.model.Category
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.model.MovieListResponse
import com.tvclient.app.data.model.StreamResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 100 % Standalone-Repository:
 * - Startbildschirm:
 *   1. "Live TV" -> direkte Tamil Live-Streams (Sun TV HD, Star Vijay, Zee Tamil HD, Colors Tamil HD, ...)
 *   2. "Serien" -> zeigt die Sender (Vijay TV, Sun TV, Zee Tamil)
 *   3. "Filme – Neu & HD" (Tamilgun)
 *   4. "Filme – Trending" (Tamilgun)
 * - Im Sender: "Serien", "TV-Shows", "Neueste Folgen"
 * - In einer Serie: Alle Episoden seitenweise
 */
object MediaRepository {

    suspend fun categories(): Result<List<Category>> = io {
        TamilLiveTvScraper.getCategories() +
            TamildhoolScraper.getMainCategories() +
            TamilgunScraper.getCategories()
    }

    suspend fun movies(categoryId: String, page: Int): Result<MovieListResponse> = io {
        when {
            TamilLiveTvScraper.isLiveTvCategory(categoryId) ->
                TamilLiveTvScraper.getLiveChannels(page)
            categoryId.startsWith(TamildhoolScraper.PREFIX_SERIES) ->
                TamildhoolScraper.scrapeEpisodesList(categoryId, page)
            TamildhoolScraper.isTamildhoolCategory(categoryId) ->
                TamildhoolScraper.scrapeCategory(categoryId, page)
            else ->
                TamilgunScraper.scrapeMovies(categoryId, page)
        }
    }

    suspend fun channelRow(channelId: String, rowType: String, page: Int): Result<MovieListResponse> = io {
        TamildhoolScraper.scrapeChannelRow(channelId, rowType, page)
    }

    suspend fun seriesEpisodes(seriesId: String, page: Int): Result<MovieListResponse> = io {
        TamildhoolScraper.scrapeEpisodesList(seriesId, page)
    }

    suspend fun search(query: String, page: Int = 1): Result<MovieListResponse> = io {
        coroutineScope {
            val liveMatches = if (page == 1) TamilLiveTvScraper.search(query) else emptyList()
            val tdDeferred = async {
                runCatching { TamildhoolScraper.search(query, page) }
                    .getOrDefault(MovieListResponse(emptyList(), page, false))
            }
            val tgDeferred = async {
                runCatching { TamilgunScraper.searchMovies(query, page) }
                    .getOrDefault(MovieListResponse(emptyList(), page, false))
            }
            val td = tdDeferred.await()
            val tg = tgDeferred.await()

            MovieListResponse(
                items = liveMatches + td.items + tg.items,
                page = page,
                hasMore = td.hasMore || tg.hasMore
            )
        }
    }

    suspend fun stream(movie: Movie): Result<StreamResponse> {
        movie.stream?.let { return Result.success(it) }
        return try {
            if (movie.id.startsWith(TamildhoolScraper.PREFIX_EPISODE)) {
                val info = withContext(Dispatchers.IO) {
                    TamildhoolScraper.resolveEpisodeStream(movie.id)
                }
                Result.success(info)
            } else {
                Result.success(WebViewStreamExtractor.resolveStream(movie.id))
            }
        } catch (e: CancellationException) {
            // Player verlassen: Abbruch weiterreichen statt ihn als Fehler anzuzeigen
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Gemerkte Stream-URL verwerfen, z. B. weil sie abgelaufen ist und nicht mehr spielt. */
    fun invalidateStream(movie: Movie) {
        if (movie.id.startsWith(TamildhoolScraper.PREFIX_EPISODE)) {
            TamildhoolScraper.invalidateStream(movie.id)
        } else {
            WebViewStreamExtractor.invalidate(movie.id)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
}
