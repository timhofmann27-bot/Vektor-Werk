# Fremde Bestandteile

Vektorwerk steht unter der MIT-Lizenz (siehe `LICENSE`). Diese Bestandteile stammen von Dritten:

| Bestandteil | Wo | Lizenz |
|---|---|---|
| Noto Sans (Regular, Bold) | `android/app/src/main/assets/bridge/fonts/` | SIL Open Font License 1.1 (`OFL.txt` liegt daneben) |
| Pantone-Farbtabelle (Hex-Näherungen) | `vektorwerk/app/static/pantone.json` | aus dem npm-Paket `pantone-colors`, MIT |
| OpenCV 4.12 für Android | Maven-Abhängigkeit der APK | Apache-2.0 |
| AndroidX Activity, ExifInterface | Maven-Abhängigkeiten der APK | Apache-2.0 |
| FastAPI, Uvicorn, python-multipart, NumPy, OpenCV (Python), Pillow, vtracer, CairoSVG, pypdf | Python-Abhängigkeiten (`vektorwerk/pyproject.toml`) | jeweils eigene freie Lizenzen (MIT, BSD, Apache-2.0, LGPL für CairoSVG, HPND für Pillow) |

**Hinweis zu Pantone:** PANTONE® ist eine Marke der Pantone LLC. Vektorwerk ist nicht mit Pantone verbunden. Die angezeigten Pantone-Töne sind Bildschirm-Näherungen. Verbindlich ist immer der gedruckte Farbfächer.
