# FireTVClient

Schlanker Streaming-Client für Fire TV Stick / Android TV.
Kotlin, AndroidX Leanback für die Oberfläche, AndroidX Media3 (ExoPlayer) für
die Wiedergabe. Die App bringt **kein** Backend mit – sie spricht die vier
Endpunkte aus [`API-SCHEMA.md`](API-SCHEMA.md), egal was dahintersteckt.

## Was drin ist

- **Zeilen-Oberfläche** – eine horizontale Poster-Zeile pro Kategorie, komplett
  per Steuerkreuz bedienbar, Fokus-Zoom und Nachladen beim Scrollen inklusive.
- **Suche** – Bildschirmtastatur und Fire-TV-Mikrofon, entprellt (400 ms).
- **Player** – echtes Vollbild, HLS/DASH/MP4, Hardware-Decoding mit
  Software-Fallback, Untertitel, frei konfigurierbare HTTP-Header pro Stream.
- **Fernbedienung** – OK = Play/Pause, links/rechts = ±10 s (gehalten ±60 s),
  dazu alle Medientasten. Position übersteht das Verlassen der App.

## Einrichten

**1. Backend-Adresse setzen** in `gradle.properties`:

```properties
tvclient.apiBaseUrl=http://192.168.178.50:8000/
```

Der abschließende Slash ist Pflicht (Retrofit-Anforderung; die App repariert ihn
notfalls selbst). Für den Emulator `http://10.0.2.2:8000/`, für den echten Stick
die **LAN-IP** deines Rechners – `localhost` zeigt dort auf den Stick selbst.

**2. Bauen.** Der Gradle-Wrapper liegt dabei, es ist nichts vorzubereiten:

```powershell
cd C:/FireTVClient
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew assembleDebug        # oder assembleRelease (R8, ~1,8 MB)
```

Das APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
Alternativ einfach in Android Studio öffnen.

Auf dieser Maschine verifiziert: **Gradle 9.8.2, AGP 9.4.1, JDK 25 (Studio-JBR),
compileSdk 37, build-tools 36.0.0** – `assembleDebug` und `assembleRelease`
bauen warnungsfrei durch.

**3. Auf den Stick bringen.** ADB-Debugging am Fire TV aktivieren
(*Einstellungen → Mein Fire TV → Entwickleroptionen → ADB-Debugging*), dann:

```powershell
adb connect 192.168.178.xxx:5555
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Die App erscheint als Kachel im Startbildschirm (`LEANBACK_LAUNCHER`).

## Updates veröffentlichen

Die App aktualisiert sich selbst über GitHub Releases dieses Repos:

1. Auf GitHub **Releases → Draft a new release**, neuen Tag im Format
   `vMAJOR.MINOR.PATCH` anlegen (z. B. `v1.1.1`), Notizen schreiben, **Publish**.
   Alternativ: `gh release create v1.1.1 --notes "Was neu ist"`.
2. Der Workflow [`release.yml`](.github/workflows/release.yml) baut die APK
   (Version = Tag), signiert sie und hängt sie als `Tamil_TV.apk` an das Release
   – dauert ca. 5 Minuten.
3. Jede installierte App prüft beim Öffnen und bei jeder Rückkehr auf den
   Startbildschirm (höchstens alle 5 Minuten), zeigt dann „Neues Update verfügbar“
   mit den Release-Notizen und installiert auf Knopfdruck. Manuell geht es über die
   Kachel **App → Nach Updates suchen**.

Beim ersten Update fragt Fire OS einmalig nach „Unbekannte Apps installieren“ für
diese App. „Später“ blendet ein Release 24 Stunden lang aus.

**Signierschlüssel:** Android installiert ein Update nur, wenn es mit demselben
Schlüssel signiert ist wie die installierte App. Der Workflow signiert mit dem
Keystore aus den Repo-Secrets `TV_KEYSTORE_BASE64`, `TV_KEYSTORE_PASSWORD`,
`TV_KEY_ALIAS` und `TV_KEY_PASSWORD` – aktuell der lokale Debug-Keystore, sodass
selbst gebaute und von GitHub geladene Versionen zueinander passen.

## Aufbau

```
app/src/main/java/com/tvclient/app/
├─ data/
│  ├─ model/Movie.kt            Katalog-Modelle (Movie, Category, Listen-Antwort)
│  ├─ model/StreamResponse.kt   Wiedergabe-Info inkl. Header und Untertitel
│  ├─ remote/
│  │  ├─ ApiService.kt          Retrofit-Interface = der Backend-Vertrag
│  │  ├─ ApiClient.kt           geteilter OkHttp-Client + Retrofit-Setup
│  │  └─ MediaRepository.kt     IO-Dispatcher, Fehler als Result
│  └─ update/UpdateManager.kt   Update-Prüfung, Download, Installation
└─ ui/
   ├─ browse/MainActivity.kt    Host
   ├─ browse/BrowseFragment.kt  Zeilen, Pagination, Klick → Player
   ├─ browse/CardPresenter.kt   Poster-Karte samt Fokus-Zustand
   ├─ browse/CatalogRow.kt      Pagination-Zustand einer Zeile
   ├─ search/SearchFragment.kt  Suche mit Debounce
   ├─ player/PlayerActivity.kt  ExoPlayer, Header-Injection, D-Pad
   └─ update/UpdateActivity.kt  Update-Dialog
```

Der einzige Ort, an dem das Backend-Format steckt, ist `data/` – die UI kennt nur
die Modelle.

## Stellschrauben

| Was | Wo |
|---|---|
| Sprungweite (10 s / 60 s) | `PlayerActivity.SEEK_STEP_MS`, `SEEK_STEP_LONG_MS` |
| Kartengröße | `CardPresenter.CARD_WIDTH_DP` / `CARD_HEIGHT_DP` |
| Nachlade-Abstand | `BrowseFragment.PREFETCH_DISTANCE` |
| Zeilen beim Start vorladen | `BrowseFragment.INITIAL_ROWS_TO_PREFETCH` |
| Default-User-Agent | `PlayerActivity.DEFAULT_USER_AGENT` |
| Update-Quelle (Repo) | `tvclient.updateRepo` in `gradle.properties` |
| Version lokaler Builds | `appVersionName` / `appVersionCode` in `gradle.properties` |
| Farben / Name | `res/values/colors.xml`, `res/values/strings.xml` |

## Noch zu tun, falls du weitermachst

- **Banner und Icon**: aktuell Platzhalter-Vektoren. Für den Fire-TV-Launcher
  gehört ein echtes **320×180-PNG** nach `res/drawable-xhdpi/app_banner.png`.
- **Detailseite**: `Movie.description` wird geliefert, aber noch nicht angezeigt.
  Ein `DetailsSupportFragment` zwischen Karte und Player wäre der nächste Schritt.
- **Weiterschauen**: `start_position_ms` wird ausgelesen; die Position speichert
  die App aber nur im Speicher, nicht persistent.
- **`usesCleartextTraffic`** steht auf `true`, damit HTTP im LAN funktioniert.
  Läuft dein Backend über HTTPS, nimm das Flag raus.

## Stolperstein, falls du Module ergänzt

AGP 9 kompiliert Kotlin **selbst**. Das übliche
`id("org.jetbrains.kotlin.android")` zusätzlich anzuwenden bricht den Build ab
mit *„Cannot add extension with name 'kotlin'"* – deshalb steht in
`build.gradle.kts` nur das Android-Plugin. Aus demselben Grund gibt es keinen
`kotlinOptions`-Block; das JVM-Ziel kommt aus `compileOptions`.
