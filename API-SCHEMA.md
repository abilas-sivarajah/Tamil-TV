# Backend-Vertrag

Die App spricht genau vier GET-Endpunkte. Basis-URL steht in `gradle.properties`
unter `tvclient.apiBaseUrl`. Alles `Content-Type: application/json`, UTF-8.

Felder, die unten als *optional* markiert sind, dürfen fehlen oder `null` sein –
die App fällt dann auf sinnvolle Defaults zurück. Unbekannte Zusatzfelder werden
ignoriert, du kannst die Antworten also gefahrlos erweitern.

---

## 1. `GET /api/categories`

Definiert die Zeilen des Startbildschirms, in genau dieser Reihenfolge.

```json
{
  "categories": [
    { "id": "recent",    "title": "Neu dazugekommen" },
    { "id": "movies-hd", "title": "Filme in HD" },
    { "id": "series",    "title": "Serien" }
  ]
}
```

| Feld    | Typ    | Pflicht | Bedeutung                                        |
|---------|--------|---------|--------------------------------------------------|
| `id`    | string | ja      | wird als `?category=` an `/api/movies` gehängt   |
| `title` | string | ja      | Überschrift der Zeile auf dem TV                 |

---

## 2. `GET /api/movies?category={id}&page={n}`

`page` beginnt bei **1**. Die App lädt die nächste Seite automatisch, sobald der
Fokus sechs Karten vor dem Zeilenende steht.

```json
{
  "page": 1,
  "has_more": true,
  "items": [
    {
      "id": "a1b2c3",
      "title": "Beispieltitel",
      "thumbnail": "http://192.168.178.50:8000/thumbs/a1b2c3.jpg",
      "description": "Kurzbeschreibung für die Detailansicht.",
      "year": "2024",
      "duration": 7860
    }
  ]
}
```

| Feld                | Typ     | Pflicht  | Bedeutung                                                   |
|---------------------|---------|----------|-------------------------------------------------------------|
| `page`              | int     | optional | zurückgegebene Seitennummer (Default 1)                     |
| `has_more`          | bool    | optional | `false` ⇒ App fragt keine weitere Seite an (Default `false`) |
| `items[].id`        | string  | ja       | Schlüssel für `/api/stream?id=`                             |
| `items[].title`     | string  | ja       | Kartentitel                                                 |
| `items[].thumbnail` | string  | optional | Poster-URL, idealerweise 2:3 (z. B. 360×520)                |
| `items[].description` | string | optional | derzeit nicht angezeigt, für Detailseite vorgesehen        |
| `items[].year`      | string  | optional | erste Hälfte der Kartenunterzeile                           |
| `items[].duration`  | int     | optional | Sekunden, wird als `2h 11m` formatiert                      |
| `items[].stream`    | object  | optional | kompletter `/api/stream`-Payload (siehe 4.) – spart den Roundtrip |

> **Tipp:** Liefere `items[].stream` direkt mit, wenn die Auflösung bei dir
> billig ist. Die App überspringt dann `/api/stream` und startet messbar
> schneller.

Leeres `items` **und** `has_more: false` ⇒ Zeile gilt als vollständig geladen.

---

## 3. `GET /api/search?q={query}&page={n}`

Identisches Antwortformat wie `/api/movies`. Die App entprellt Tastatureingaben
um 400 ms und sucht erst ab zwei Zeichen.

---

## 4. `GET /api/stream?id={id}`

Die eigentliche Wiedergabe-Info.

```json
{
  "url": "http://192.168.178.50:8000/hls/a1b2c3/master.m3u8",
  "type": "hls",
  "title": "Beispieltitel",
  "start_position_ms": 0,
  "headers": {
    "User-Agent": "Mozilla/5.0 ...",
    "Referer": "http://192.168.178.50:8000/"
  },
  "subtitles": [
    { "url": "http://.../de.vtt", "lang": "de", "label": "Deutsch", "mime": "text/vtt" }
  ]
}
```

| Feld                | Typ    | Pflicht  | Bedeutung                                                      |
|---------------------|--------|----------|----------------------------------------------------------------|
| `url`               | string | ja       | direkt abspielbare URL                                         |
| `type`              | string | optional | `hls` \| `dash` \| `mp4` \| `progressive`; fehlt ⇒ Media3 rät anhand der Endung |
| `headers`           | object | optional | wird an **jede** HTTP-Anfrage gehängt, auch an HLS-Segmente     |
| `subtitles[]`       | array  | optional | `mime` Default `text/vtt`; auch `application/x-subrip`, `text/x-ssa` |
| `start_position_ms` | int    | optional | Startposition, z. B. für „Weiterschauen“                       |
| `title`             | string | optional | Fallback für das Titel-Overlay                                 |

### Fehlerfälle

Die App behandelt jeden Status ≥ 400 und jeden Verbindungsfehler als Fehler und
zeigt einen Hinweis mit „Erneut versuchen“-Button. Ein Fehlertext hilft:

```json
{ "detail": "Quelle derzeit nicht verfügbar" }
```

Für `/api/stream` ist **404** der richtige Code, wenn die ID unbekannt ist, und
**503**, wenn die Auflösung temporär scheitert.
