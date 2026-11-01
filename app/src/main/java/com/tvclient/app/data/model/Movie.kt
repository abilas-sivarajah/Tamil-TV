package com.tvclient.app.data.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Ein Eintrag im Katalog (Film, Folge, Video ...).
 * Nur [id] und [title] sind zwingend, alles andere darf fehlen bzw. null sein.
 */
data class Movie(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("thumbnail") val thumbnail: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("year") val year: String? = null,
    @SerializedName("duration") val durationSeconds: Long? = null,
    /** Optional: wenn das Backend die Stream-URL direkt mitliefert, entfaellt /api/stream. */
    @SerializedName("stream") val stream: StreamResponse? = null
) : Serializable {

    /** True, wenn dieser Eintrag ein TV-Sender ist (Klick oeffnet Sender-Ansicht mit Serien/Shows/Neueste). */
    val isChannelFolder: Boolean
        get() = id.startsWith("td_channel:")

    /** True, wenn dieser Eintrag ein Live-TV-Sender ist (Klick startet sofort den Live-Stream). */
    val isLiveTv: Boolean
        get() = id.startsWith("live_tv:")

    /** True, wenn dieser Eintrag ein Serien-Ordner ist (Klick oeffnet Episoden-Uebersicht). */
    val isSeriesFolder: Boolean
        get() = id.startsWith("td_series:")

    /** Zweite Zeile auf der Karte, z. B. "2024 - 2h 11m". */
    val subtitleLine: String
        get() = listOfNotNull(
            year?.takeIf { it.isNotBlank() },
            durationSeconds?.takeIf { it > 0 }?.let { formatDuration(it) }
        ).joinToString(" - ")

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** Eine Kategorie = eine Zeile in der TV-Oberflaeche. */
data class Category(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String
)

data class CategoriesResponse(
    @SerializedName("categories") val categories: List<Category> = emptyList()
)

/** Seitenweises Ergebnis fuer Katalog und Suche. */
data class MovieListResponse(
    @SerializedName("items") val items: List<Movie> = emptyList(),
    @SerializedName("page") val page: Int = 1,
    @SerializedName("has_more") val hasMore: Boolean = false
)
