# Vektorwerk

Lokale App, die Logos und Motive (PNG/JPG/WEBP) automatisch in druckfertige Vektordateien umwandelt.

## Starten

```bash
./start.sh
```

Danach läuft die App unter http://localhost:8765. Alles bleibt lokal, es werden keine Bilder hochgeladen.

## So funktioniert es

1. **Hochskalieren und entrauschen:** Kleine oder stark komprimierte JPGs werden geglättet (Mean-Shift + Bilateralfilter).
2. **Hintergrund erkennen:** Einfarbiger Rand oder Transparenz wird entfernt.
3. **Farben finden:** k-Means im Lab-Farbraum, nur auf flachen Flächen. Kantenpixel und Mischfarben (Antialiasing-Säume) werden verworfen.
4. **Farben säubern:** Fast-Weiß wird zu `#FFFFFF`, Fast-Schwarz zu `#000000`.
5. **Kanten glätten:** Jede Farbmaske wird weichgezeichnet und dann per argmax zugeordnet, so entstehen keine Treppen und keine neuen Farben.
6. **Nachzeichnen:** Jede Farbe einzeln mit vtracer, Ergebnis ist eine Ebene pro Farbe (Inkscape/Illustrator-kompatibel).

## Presets

| Preset | Farben | Ebenen | Gedacht für |
|---|---|---|---|
| Siebdruck | bis 6 | nebeneinander + 1 px Überfüllung | ein Sieb pro Farbe |
| Flex / Flock | bis 3 | nebeneinander, stark vereinfacht | Plotter |
| DTF / Digitaldruck | bis 16 | gestapelt (keine Blitzer) | Digitaldruck |
| Maximale Details | bis 32 | gestapelt | Illustrationen |

## Werkzeuge für den Textildruck

- **Pantone:** Zu jeder Farbe wird der nächste Pantone-Ton (Solid Coated, CIEDE2000) angezeigt und lässt sich per Klick übernehmen. Die Hex-Werte sind Bildschirm-Näherungen, verbindlich ist der Farbfächer. Datenquelle: npm-Paket `pantone-colors` (MIT).
- **Farben zusammenlegen:** Eine Farbe lässt sich in eine andere aufnehmen. Die Engine rechnet dann mit fester Palette neu, deshalb entstehen keine Nahtstellen. Mit „Trennen“ wird das rückgängig gemacht.
- **Weiß-Unterleger:** Weiße Grundierung unter allen gedruckten Farben, um 0–1,5 mm geschrumpft. Sie wird als erster Film gedruckt.
- **Shirtfarbe aussparen (automatisch):** Ebenen in Shirtfarbe werden nach dem Vektorisieren und bei jedem Wechsel der Shirtfarbe automatisch ausgeblendet, dort scheint der Stoff durch. Weiß-auf-Schwarz-Motive werden so ohne Klick zu 1 Sieb. Manuell per Auge ein- oder ausgeblendete Ebenen bleiben unangetastet. Der Druck-Check zählt nur gedruckte Ebenen.
- **Mockup:** Das Motiv in echter Größe auf einem T-Shirt (S–3XL, vorne/hinten, Abstand zum Kragen), mit Warnung bei zu großem Motiv.

## Prüfen und Nachbessern

- **Druck-Check:** Findet je Farbe Linien und Details unter der Mindeststärke (Siebdruck 0,5 mm, Plotter 1,0 mm, DTF 0,3 mm, anpassbar) sowie zu enge Lücken zum Shirt hin, die beim Druck zulaufen. Die Stellen werden im Bild rot bzw. blau markiert. „Dünne Stellen verdicken“ bringt Linien automatisch auf Mindeststärke.
- **Nachbessern (Pinsel, Taste B):** Flächen direkt im Bild umfärben oder mit dem Radierer zu Hintergrund machen. Die Striche werden nach jedem Absetzen übernommen, und das Motiv wird mit fester Palette neu berechnet. `[` und `]` ändern die Pinselgröße, rechte Maustaste oder Leertaste verschiebt das Bild.
- **Rückgängig/Wiederholen:** `Strg+Z` / `Strg+Y` (oder `Strg+Umschalt+Z`) für alle Änderungen am Motiv, bis zu 50 Schritte pro Motiv.

## Bedienung

Linke Seitenleiste: Tabs **Motive** und **Einstellungen**. Rechte Seitenleiste: Tabs **Ebenen & Prüfen** und **Export**. In der Mitte liegt immer die Vorschau. In schmalen Fenstern (unter 900 px) wechselt die Oberfläche zur Handy-Ansicht mit einer Navigationsleiste unten.

## Hilfe für Einsteiger

- **Einführung:** Beim ersten Start erklären fünf kurze Schritte den Ablauf. Über **?** oben → „Einführung ansehen“ lässt sie sich wieder öffnen.
- **Hilfe (?):** Schnellstart, Begriffe von Vektor bis Pantone und häufige Fragen. Das kleine **?** neben einer Überschrift springt direkt zur passenden Erklärung.
- **Erste Schritte:** Eine Checkliste unter „Motive“ hakt sich beim Benutzen selbst ab: Bild laden → Shirtfarbe → Druck-Check → Speichern.

## Projekte

- **Speichern/Öffnen:** `Strg+S` oder „Speichern“ legt das Projekt im Ordner `projekte/` ab, mit Originalbildern, Ergebnissen, Farben, Pinselstrichen und Mockup-Einstellungen.
- **Automatische Sicherung:** Jede Änderung wird nach kurzer Zeit als „Letzte Sitzung“ gesichert. Beim nächsten Start lässt sie sich mit einem Klick wiederherstellen.

## Merch: Sticker, Aufnäher, Buttons

Unter **Einstellungen → Produkt** wählst du, was gedruckt wird. Die Form, die Schnittlinie und der Rand werden in der Oberfläche berechnet. Deshalb liefern PC und Handy dieselben Dateien.

- **T-Shirt & Textil:** wie bisher, mit Druckbreite, Shirt-Mockup und Siebdruck-Filmen.
- **Sticker:**
  - Formen: Kontur ums Motiv, Kreis, Rechteck oder abgerundet.
  - Einstellbar sind der Abstand zur Schnittlinie, eine bedruckte Hintergrundfarbe oder transparente Folie und der Beschnitt (1–3 mm).
- **Aufnäher:**
  - Formen: Kontur, Kreis, Oval, Rechteck oder abgerundet.
  - Einstellbar sind die Stofffarbe sowie der gestickte Rand mit Breite und Farbe.
  - Der Druck-Check prüft hier ab 1 mm.
  - Die Stickdatei (DST) erstellt der Hersteller aus dem PDF.
- **Button:**
  - Größen 25, 32, 38, 56 und 59 mm.
  - Die Druckvorlage enthält den Umschlag, der Ø ist anpassbar.
  - Das Motiv füllt die sichtbare Fläche.

Die **Motivbreite** ist die Breite des sichtbaren Motivs. Die Endgröße kommt mit Abstand und Rand dazu. Farben, die der Hintergrund- oder Stofffarbe entsprechen, werden automatisch ausgespart.

Jede Druckdatei (PDF/SVG/EPS) enthält die **Schnittlinie** als eigene Ebene `CutContour` in Magenta (0,1 mm). Im PNG und in den Filmen fehlt sie. Zusätzlich gibt es **Schnittlinie SVG/PDF** einzeln für Schneideplotter wie Cricut, Silhouette oder Summa. Die ZIP legt beide Schnittlinien-Dateien automatisch dazu.

Grenzen:
- Die Schnittlinie ist eine RGB-Magenta-Linie mit dem Ebenennamen „CutContour“, keine echte Sonderfarbe (Spot Color). Die meisten Online-Druckereien erkennen sie so. Manche verlangen die Sonderfarbe, dann muss sie in Illustrator oder Affinity umgestellt werden.
- Die Umschlag-Maße für Buttons sind Richtwerte und sollten mit dem Hersteller abgeglichen werden.

## Export

- **PDF / EPS** für die Druckerei, in echter Druckgröße (cm)
- **SVG** zum Weiterbearbeiten, jede Farbe als benannte Ebene
- **PNG** mit 300 dpi, transparent
- **Siebdruck-Filme:** mehrseitiges PDF. Seite 1 ist eine farbige Übersicht mit Druckreihenfolge, danach folgt pro Farbe ein schwarzer Film mit Passkreuzen, Eckmarken und Beschriftung (Farbe/Pantone, Größe, Datum).
- **ZIP:** alle Motive in den gewählten Formaten, ein Ordner pro Motiv

## Android-App

Die Android-App liegt in `../android` und läuft komplett offline. Sie nutzt dieselbe Oberfläche (`app/static/index.html`) in einer WebView. Die Engine ist nach Kotlin portiert (OpenCV für Android plus ein eigener Bézier-Tracer statt vtracer).

- **Installieren:** `../Vektorwerk.apk` aufs Handy kopieren und öffnen, oder mit USB-Debugging `adb install -r ../Vektorwerk.apk`. Voraussetzung: Android 10 oder neuer, arm64.
- **Bilder laden:** über „Bilder auswählen“ oder per „Teilen“ aus der Galerie direkt an Vektorwerk.
- **Exporte** landen in `Download/Vektorwerk`, Projekte im App-Speicher.
- **Bedienung:** Unten ist eine Leiste mit **Motive · Einstellen · Vorschau · Ebenen · Export**. Speichern, Öffnen und Neu liegen hinter ⋮. Die Zurück-Taste springt zur vorherigen Ansicht. Zoomen und Verschieben geht mit zwei Fingern.
- **Signatur:** Liegt `~/.config/vektorwerk/signing.properties` vor (siehe `../android/signing.properties.example`), wird die Release-APK damit signiert, sonst mit dem Debug-Schlüssel.
- **Bauen** (JDK 21, Android SDK):
  ```
  cd ../android && ./gradlew :app:assembleRelease
  ```
  Für den Emulator zusätzlich `-Pvektorwerk.abis=x86_64` angeben.
