# Vektorwerk

Macht aus Logos und Motiven (PNG/JPG/WEBP) druckfertige Vektordateien – für T-Shirts (Siebdruck, Flex/Flock, DTF), Sticker, Aufnäher und Buttons.

- **PC-App** (`vektorwerk/`): läuft lokal im Browser, es werden keine Bilder hochgeladen.
- **Android-App** (`android/`): komplett offline, gleiche Oberfläche, eigene Kotlin-Engine.

Funktionen, Bedienung und Export: siehe [vektorwerk/README.md](vektorwerk/README.md).

## Schnellstart

PC (Linux/macOS, braucht [uv](https://docs.astral.sh/uv/) und Cairo):

```bash
cd vektorwerk && ./start.sh
```

Android: die APK unter **Releases** herunterladen und installieren (Android 10+, arm64).

Selbst bauen (JDK 21, Android SDK):

```bash
cd android && ./gradlew :app:assembleRelease
```

Ohne eigenen Signaturschlüssel wird die APK mit dem Debug-Schlüssel signiert. Für einen eigenen Schlüssel siehe `android/signing.properties.example`.

## Lizenz

MIT – siehe [LICENSE](LICENSE). Fremde Bestandteile: [THIRD_PARTY.md](THIRD_PARTY.md).
