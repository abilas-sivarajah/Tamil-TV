package com.tvclient.app.data.remote

import com.tvclient.app.data.model.Category
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.model.MovieListResponse
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Liest Katalog, Suche und Player-iFrames direkt auf dem Fire TV Stick
 * von tamilgun.now aus (komplett ohne PC-Backend).
 */
object TamilgunScraper {

    private const val BASE_URL = "https://tamilgun.now"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L // 10 Minuten

    private data class CategorySpec(val id: String, val title: String, val slug: String)

    private val categorySpecs = listOf(
        CategorySpec("movies-hd", "Filme – Neu & HD", "hd-movies"),
        CategorySpec("trending", "Filme – Trending", "trending")
    )

    private val catalogCache = ConcurrentHashMap<String, Pair<Long, MovieListResponse>>()

    private val videoSlugRegex = Regex("""/video/([^/]+)/?$""")
    private val yearRegex = Regex("""\b(20\d{2}|19\d{2})\b""")
    private val junkSuffixRegex = Regex("""-\s*TamilGun.*""", RegexOption.IGNORE_CASE)
    private val junkTagsRegex = Regex("""\b(HD|720p|1080p|DVD|Watch Online|Full Movie)\b""", RegexOption.IGNORE_CASE)

    fun getCategories(): List<Category> =
        categorySpecs.map { Category(id = it.id, title = it.title) }

    fun scrapeMovies(categoryId: String, page: Int): MovieListResponse {
        val cacheKey = "cat_${categoryId}_$page"
        val now = System.currentTimeMillis()
        catalogCache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val url = buildCategoryUrl(categoryId, page)
        val html = fetchHtml(url)
        val result = parseMovieList(html, page)
        if (result.items.isNotEmpty()) {
            catalogCache[cacheKey] = now to result
        }
        return result
    }

    fun searchMovies(query: String, page: Int): MovieListResponse {
        val cleanQuery = query.trim()
        val cacheKey = "search_${cleanQuery}_$page"
        val now = System.currentTimeMillis()
        catalogCache[cacheKey]?.let { (ts, cached) ->
            if (now - ts < CACHE_TTL_MS) return cached
        }

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val url = if (page <= 1) {
            "$BASE_URL/?s=$encoded"
        } else {
            "$BASE_URL/page/$page/?s=$encoded"
        }

        val html = fetchHtml(url)
        val result = parseMovieList(html, page)
        if (result.items.isNotEmpty()) {
            catalogCache[cacheKey] = now to result
        }
        return result
    }

    data class MoviePageInfo(
        val title: String,
        val playerUrls: List<String>
    )

    fun fetchMoviePageInfo(movieId: String): MoviePageInfo {
        val url = if (movieId.startsWith("http")) {
            movieId
        } else {
            "$BASE_URL/video/$movieId/"
        }

        val html = fetchHtml(url)
        val doc = Jsoup.parse(html, url)
        val rawTitle = doc.title().ifBlank { movieId }
        val title = cleanTitle(rawTitle).ifBlank { movieId }

        val iframes = doc.select("iframe")
            .mapNotNull { el ->
                // abs: loest auch protokoll-relative (//player...) und relative Quellen auf
                el.attr("abs:src").ifBlank { el.attr("abs:data-src") }.takeIf { it.startsWith("http") }
            }
            .distinct()

        // playallu bevorzugen, da der JWPlayer-Extractor dort den direkten HLS-Stream liefert
        val sortedPlayers = iframes.sortedByDescending { it.contains("playallu", ignoreCase = true) }

        return MoviePageInfo(
            title = title,
            playerUrls = sortedPlayers
        )
    }

    private fun buildCategoryUrl(categoryId: String, page: Int): String {
        val spec = categorySpecs.find { it.id == categoryId } ?: categorySpecs.first()
        return if (spec.slug == "trending") {
            if (page <= 1) "$BASE_URL/trending/" else "$BASE_URL/trending/page/$page/"
        } else {
            if (page <= 1) {
                "$BASE_URL/video-category/${spec.slug}/"
            } else {
                "$BASE_URL/video-category/${spec.slug}/page/$page/"
            }
        }
    }

    private fun parseMovieList(html: String, page: Int): MovieListResponse {
        val doc = Jsoup.parse(html, BASE_URL)
        val items = mutableListOf<Movie>()
        val seenIds = HashSet<String>()

        for (a in doc.select("a[href*=/video/]")) {
            val href = a.attr("href")
            val match = videoSlugRegex.find(href) ?: continue
            val movieId = match.groupValues[1]
            if (movieId.isBlank() || !seenIds.add(movieId)) continue

            val img = a.selectFirst("img")
            val rawTitle = a.attr("title")
                .ifBlank { img?.attr("alt").orEmpty() }
                .ifBlank { a.text().trim() }

            if (rawTitle.length < 2) continue

            val thumb = img?.imageUrl()

            val title = cleanTitle(rawTitle).ifBlank { movieId }
            val year = yearRegex.find(rawTitle)?.groupValues?.get(1)

            items.add(
                Movie(
                    id = movieId,
                    title = title,
                    thumbnail = thumb,
                    description = rawTitle,
                    year = year
                )
            )
        }

        // Pruefen, ob eine weitere Seite existiert
        var hasMore = doc.selectFirst(".page-numbers.next, a.next, .nav-previous a") != null
        if (!hasMore) {
            val currentNum = doc.selectFirst(".page-numbers.current")?.text()?.trim()?.toIntOrNull()
            if (currentNum != null) {
                hasMore = doc.select("a.page-numbers").any { el ->
                    val n = el.text().trim().toIntOrNull()
                    n != null && n > currentNum
                }
            }
        }

        return MovieListResponse(
            items = items,
            page = page,
            hasMore = hasMore
        )
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(junkSuffixRegex, "")
            .replace(junkTagsRegex, "")
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
