package com.tvclient.app.data.remote

import com.tvclient.app.data.model.Category
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.model.MovieListResponse
import com.tvclient.app.data.model.StreamResponse
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Scraper fuer TamilDhool (tamildhool.tech) mit 3-stufiger Navigation:
 * 1. Hauptmenue "Serien" -> zeigt die Sender (Vijay TV, Sun TV, Zee Tamil)
 * 2. Klick auf einen Sender -> zeigt im Sender die Zeilen "Serien", "TV-Shows" und "Neueste Folgen"
 * 3. Klick auf eine Serie/Show -> zeigt alle Folgen dieser Serie (seitenweise)
 */
object TamildhoolScraper {

    const val PREFIX_CHANNEL = "td_channel:"
    const val PREFIX_SERIES = "td_series:"
    const val PREFIX_EPISODE = "td_ep:"

    private const val BASE_URL = "https://www.tamildhool.tech"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    data class ChannelSpec(
        val id: String,
        val title: String,
        val subtitle: String,
        val logoUrl: String,
        val programsUrl: String,
        val latestUrl: String
    )

    val channels = listOf(
        ChannelSpec(
            id = "vijaytv",
            title = "Vijay TV",
            subtitle = "Serien & TV-Shows",
            logoUrl = "$BASE_URL/wp-content/uploads/2026/01/vijay.jpg",
            programsUrl = "$BASE_URL/vijay-tv-programs/",
            latestUrl = "$BASE_URL/vijay-tv/"
        ),
        ChannelSpec(
            id = "suntv",
            title = "Sun TV",
            subtitle = "Serien & TV-Shows",
            logoUrl = "$BASE_URL/wp-content/uploads/2026/01/suntv.jpg",
            programsUrl = "$BASE_URL/sun-tv-programs/",
            latestUrl = "$BASE_URL/sun-tv/"
        ),
        ChannelSpec(
            id = "zeetamil",
            title = "Zee Tamil",
            subtitle = "Serien & TV-Shows",
            logoUrl = "$BASE_URL/wp-content/uploads/2026/01/zeetamil.jpg",
            programsUrl = "$BASE_URL/zee-tamil-programs/",
            latestUrl = "$BASE_URL/zee-tamil/"
        )
    )

    private val cache = ConcurrentHashMap<String, Pair<Long, MovieListResponse>>()
    private val streamCache = ConcurrentHashMap<String, Pair<Long, StreamResponse>>()

    private val dateRegex = Regex("""\b(\d{2}-\d{2}-\d{4})\b""")
    private val videoIdParamRegex = Regex("""video=([a-f0-9]{12,24})""", RegexOption.IGNORE_CASE)
    private val thumbIdRegex = Regex("""vibrantpulselab\.com/thumbs/([a-f0-9]{12,24})""", RegexOption.IGNORE_CASE)

    /** Auf dem Startbildschirm nur der Hauptpunkt "Serien" (zeigt die Sender-Kacheln). */
    fun getMainCategories(): List<Category> = listOf(
        Category(id = "td_channels", title = "Serien")
    )

    fun isTamildhoolCategory(categoryId: String): Boolean =
        categoryId.startsWith("td_")

    fun findChannel(channelIdOrPrefixed: String): ChannelSpec? {
        val cleanId = channelIdOrPrefixed.removePrefix(PREFIX_CHANNEL)
        return channels.find { it.id == cleanId }
    }

    fun scrapeCategory(categoryId: String, page: Int): MovieListResponse {
        if (categoryId == "td_channels") {
            if (page > 1) return MovieListResponse(emptyList(), page, false)
            val items = channels.map { spec ->
                Movie(
                    id = "$PREFIX_CHANNEL${spec.id}",
                    title = spec.title,
                    thumbnail = spec.logoUrl,
                    description = "${spec.title} – Serien, Shows & Neueste Folgen",
                    year = spec.subtitle
                )
            }
            return MovieListResponse(items = items, page = 1, hasMore = false)
        }
        return MovieListResponse(emptyList(), page, false)
    }

    /**
     * Liest fuer einen Sender entweder die "Serien" (-serial/), die "TV-Shows" (-show/)
     * oder die "Neuesten Folgen" aus.
     */
    fun scrapeChannelRow(channelId: String, rowType: String, page: Int): MovieListResponse {
        val spec = findChannel(channelId) ?: return MovieListResponse(emptyList(), page, false)
        return when (rowType) {
            "serials" -> {
                if (page > 1) return MovieListResponse(emptyList(), page, false)
                val all = scrapeChannelAllPrograms(spec)
                MovieListResponse(
                    items = all.items.filter { it.id.contains("-serial/") },
                    page = 1,
                    hasMore = false
                )
            }
            "shows" -> {
                if (page > 1) return MovieListResponse(emptyList(), page, false)
                val all = scrapeChannelAllPrograms(spec)
                MovieListResponse(
                    items = all.items.filter { it.id.contains("-show/") },
                    page = 1,
                    hasMore = false
                )
            }
            "latest" -> scrapeEpisodesList(spec.latestUrl, page)
            else -> MovieListResponse(emptyList(), page, false)
        }
    }

    fun scrapeChannelAllPrograms(spec: ChannelSpec): MovieListResponse {
        val cacheKey = "prog_${spec.id}"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val html = fetchHtml(spec.programsUrl)
        val doc = Jsoup.parse(html, BASE_URL)
        val items = mutableListOf<Movie>()
        val seenUrls = HashSet<String>()

        for (a in doc.select(".entry-content a[href], main a[href], #content a[href]")) {
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (!href.contains("-serial/") && !href.contains("-show/")) continue
            if (!seenUrls.add(href)) continue

            val img = a.selectFirst("img")
            val title = a.text().trim()
                .ifBlank { img?.attr("alt").orEmpty().trim() }
            if (title.isBlank()) continue

            val thumb = img?.imageUrl()

            val kind =if (href.contains("-show/")) "TV-Show" else "Serie"

            items.add(
                Movie(
                    id = "$PREFIX_SERIES$href",
                    title = title,
                    thumbnail = thumb,
                    description = "${spec.title} $kind – Klicke für alle Folgen",
                    year = "${spec.title} • $kind"
                )
            )
        }

        val result = MovieListResponse(items = items, page = 1, hasMore = false)
        if (items.isNotEmpty()) {
            cache[cacheKey] = now to result
        }
        return result
    }

    fun scrapeEpisodesList(baseSeriesUrl: String, page: Int): MovieListResponse {
        val cleanBase = baseSeriesUrl.removePrefix(PREFIX_SERIES).trimEnd('/')
        val cacheKey = "eps_${cleanBase}_$page"
        val now = System.currentTimeMillis()
        cache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val url = if (page <= 1) "$cleanBase/" else "$cleanBase/page/$page/"
        val html = fetchHtml(url)
        val doc = Jsoup.parse(html, BASE_URL)

        val items = parseArticlesToEpisodes(doc)
        val hasMore = doc.selectFirst(".next.page-numbers, a.next, .nav-previous a") != null

        val result = MovieListResponse(items = items, page = page, hasMore = hasMore)
        if (items.isNotEmpty()) {
            cache[cacheKey] = now to result
        }
        return result
    }

    fun search(query: String, page: Int): MovieListResponse {
        val cleanQuery = query.trim()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = if (page <= 1) {
            "$BASE_URL/?s=$encoded"
        } else {
            "$BASE_URL/page/$page/?s=$encoded"
        }

        return try {
            val html = fetchHtml(url)
            val doc = Jsoup.parse(html, BASE_URL)
            val episodes = parseArticlesToEpisodes(doc)

            val matchingSeries = if (page == 1) {
                channels.flatMap { spec ->
                    runCatching { scrapeChannelAllPrograms(spec).items }.getOrDefault(emptyList())
                }.filter { it.title.contains(cleanQuery, ignoreCase = true) }
            } else {
                emptyList()
            }

            val hasMore = doc.selectFirst(".next.page-numbers, a.next") != null
            MovieListResponse(
                items = matchingSeries + episodes,
                page = page,
                hasMore = hasMore
            )
        } catch (_: Throwable) {
            MovieListResponse(emptyList(), page, false)
        }
    }

    fun invalidateStream(movieId: String) {
        streamCache.remove(movieId.removePrefix(PREFIX_EPISODE))
    }

    fun resolveEpisodeStream(movieId: String): StreamResponse {
        val epUrl = movieId.removePrefix(PREFIX_EPISODE)
        val now = System.currentTimeMillis()
        streamCache[epUrl]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val html = fetchHtml(epUrl)
        val doc = Jsoup.parse(html, BASE_URL)
        val rawTitle = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.title().ifBlank { "Episode" }

        val videoId = videoIdParamRegex.find(html)?.groupValues?.get(1)
            ?: thumbIdRegex.find(html)?.groupValues?.get(1)

        if (!videoId.isNullOrBlank()) {
            val streamUrl = "https://media.vibrantpulselab.com/$videoId/playlist.m3u8"
            val response = StreamResponse(
                url = streamUrl,
                type = "hls",
                headers = mapOf(
                    "User-Agent" to ApiClient.DESKTOP_USER_AGENT,
                    "Referer" to "https://player.vibrantpulselab.com/"
                ),
                title = cleanEpisodeTitle(rawTitle),
                startPositionMs = 0L,
                subtitles = emptyList()
            )
            streamCache[epUrl] = now to response
            return response
        }

        throw IllegalStateException("Keine direkte Stream-ID auf der Episodenseite gefunden")
    }

    private fun parseArticlesToEpisodes(doc: org.jsoup.nodes.Document): List<Movie> {
        val items = mutableListOf<Movie>()
        val seenUrls = HashSet<String>()

        for (art in doc.select("article")) {
            val a = art.selectFirst("h2.entry-title a[href], .entry-title a[href], a[href]") ?: continue
            val href = a.attr("abs:href").ifBlank { a.attr("href") }
            if (href.isBlank() || !seenUrls.add(href)) continue

            // Fallbacks auch dann, wenn der Artikel gar kein Titel-Element hat
            val rawTitle = art.selectFirst(".entry-title, h2, h3")?.text()?.trim().orEmpty()
                .ifBlank { a.attr("title").trim() }
                .ifBlank { a.text().trim() }
            if (rawTitle.length < 2) continue

            val thumb = art.selectFirst("img")?.imageUrl()

            val dateStr = dateRegex.find(rawTitle)?.groupValues?.get(1)
            val cleanTitle = cleanEpisodeTitle(rawTitle)

            items.add(
                Movie(
                    id = "$PREFIX_EPISODE$href",
                    title = cleanTitle,
                    thumbnail = thumb,
                    description = rawTitle,
                    year = dateStr ?: "Episode"
                )
            )
        }
        return items
    }

    private fun cleanEpisodeTitle(raw: String): String =
        raw.replace(Regex("""\|\s*(Sun Tv|Vijay Tv|Zee Tamil)\s*(Serial|Show).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*"""), " – ")
            .trim(' ', '-', '–', '|')

    private fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ApiClient.DESKTOP_USER_AGENT)
            .header("Referer", "$BASE_URL/")
            .build()

        ApiClient.okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} bei $url")
            }
            return resp.body?.string().orEmpty()
        }
    }
}
