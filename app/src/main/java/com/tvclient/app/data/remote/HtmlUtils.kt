package com.tvclient.app.data.remote

import org.jsoup.nodes.Element

/**
 * Bild-URL eines <img>, auch bei Lazy-Loading: dort steht in "src" oft nur ein
 * data:-Platzhalter, das echte Bild in data-src / data-lazy-src. "abs:" macht
 * relative und protokoll-relative Pfade (//cdn...) gegen die Seiten-URL absolut.
 */
internal fun Element.imageUrl(): String? =
    sequenceOf("data-src", "data-lazy-src", "src")
        .map { attr("abs:$it").ifBlank { attr(it) }.trim() }
        .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
