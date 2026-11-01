package com.tvclient.app.data.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Antwort von GET /api/stream?id=...
 *
 * [headers] wird 1:1 an jede HTTP-Anfrage des Players gehaengt - damit laufen
 * auch Quellen, die Referer- oder Token-Header verlangen.
 */
data class StreamResponse(
    @SerializedName("url") val url: String,
    /** "hls" | "dash" | "mp4" | "progressive" | null (dann raet der Player anhand der Endung) */
    @SerializedName("type") val type: String? = null,
    @SerializedName("headers") val headers: Map<String, String>? = null,
    @SerializedName("subtitles") val subtitles: List<SubtitleTrack>? = null,
    /** Optionaler Startpunkt in Millisekunden (z. B. "weiterschauen"). */
    @SerializedName("start_position_ms") val startPositionMs: Long? = null,
    @SerializedName("title") val title: String? = null
) : Serializable {

    val userAgent: String?
        get() = headers?.entries?.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value

    /** Header ohne User-Agent - der wird in Media3 separat gesetzt. */
    val extraHeaders: Map<String, String>
        get() = headers?.filterKeys { !it.equals("User-Agent", ignoreCase = true) } ?: emptyMap()

    companion object {
        private const val serialVersionUID = 1L
    }
}

data class SubtitleTrack(
    @SerializedName("url") val url: String,
    /** BCP-47, z. B. "de", "en", "ta" */
    @SerializedName("lang") val language: String? = null,
    @SerializedName("label") val label: String? = null,
    /** "text/vtt" (Standard), "application/x-subrip", "text/x-ssa" */
    @SerializedName("mime") val mimeType: String? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
