# Vektorisierer

Bild rein, SVG raus. Der Vektorisierer wandelt Bilder direkt im Browser in Vektorgrafiken um, auch auf dem Handy. Kein Bild verlässt das Gerät: Die Seite rechnet alles selbst und schickt nichts ins Netz.

## Was er kann

- **Farbe, Graustufen oder Schwarz-Weiss**, Farbanzahl von 2 bis 250
- **Ebenen:** Jede Farbe einzeln umfärben oder ausblenden; ins Bild tippen findet die passende Farbe
- **Hintergrund entfernen:** erkennt den Hintergrund vom Rand her (auch Schachbrettmuster und Verläufe), entfernt nur zusammenhängende Flächen, dazu Zauberstab zum Wegnehmen und Zurückholen
- **Pixelart:** Pixelraster von 64 bis 256 Pixeln oder Original; jedes Pixel wird ein exaktes Quadrat in der SVG
- **Nahtlos:** keine Löcher zwischen Farbflächen; die PNG wird überall voll deckend versiegelt
- **Formtreue:** rechnet die SVG zurück in Pixel und zeigt, wie viele exakt in ihrer Farbfläche liegen
- **Vergleich:** Trennlinie zwischen Vektor und Original direkt im Bild ziehen, Zoom bis 8×
- **Export:** SVG (jede Farbe als eigener, benannter Pfad), PNG oder SVG-Code kopieren

## Benutzen

Die Seite ist eine einzige Datei: `index.html` im Browser öffnen, fertig. Mit GitHub Pages läuft sie unter `https://ui-edi.github.io/vektorisierer/`.

## Android-App

Im Ordner `android/` steckt die App: Sie bringt die Seite eingebaut mit und läuft komplett offline, ganz ohne Internet-Berechtigung. Dazu kann sie, was der Browser nicht kann:

- Bilder über «Teilen» oder «Öffnen mit» empfangen
- PNG direkt in Bilder/Vektorisierer, SVG in Downloads/Vektorisierer speichern
- kein automatisches Umfärben durch den Dunkelmodus

GitHub Actions baut die APK bei jeder Änderung (Workflow «Android-App bauen»). Damit sich neue Versionen als Update installieren lassen, braucht das Repo zwei Secrets: `VK_KEYSTORE_BASE64` und `VK_KEYSTORE_PASSWORD`. Ohne sie entsteht eine APK mit Test-Signatur.

## Aufbau

| Datei | Zweck |
| --- | --- |
| `index.html` | Fertige Seite, wird aus den Quellen gebaut |
| `src/vektorisierer.html` | Quelltext: Oberfläche, Farbsuche, Freisteller, Pixelraster |
| `vendor/imagetracer_v1.2.6.js` | Nachzeichen-Motor |
| `build.py` | Setzt Quelltext und Motor zu `index.html` zusammen: `python3 build.py` |
| `android/` | Android-App (Java, ohne Zusatzbibliotheken) |
| `.github/workflows/android.yml` | Baut die APK |

## Danke

- Nachzeichen-Motor: [imagetracer.js](https://github.com/jankovicsandras/imagetracerjs) von András Jankovics, gemeinfrei (Unlicense, siehe `vendor/imagetracer-LICENSE.txt`). Stand: Commit `cb0c84a`, 2023-11-09.
- Geplant als zweiter Motor: [vtracer](https://github.com/visioncortex/vtracer) (MIT).

## Lizenz

MIT, siehe `LICENSE`. Der Motor in `vendor/` ist gemeinfrei und behält seine eigene Lizenz.
