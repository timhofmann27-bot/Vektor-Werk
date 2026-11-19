"""Vektorwerk – lokaler Webserver.

Start:  uv run uvicorn app.main:app --port 8765
"""

from __future__ import annotations

import base64
import json
import re
import threading
import time
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from .engine import PRESETS, Options, vectorize
from .export import SUFFIX, build_zip, convert, safe_name

STATIC = Path(__file__).parent / "static"
PROJECTS = Path(__file__).resolve().parent.parent / "projekte"
MAX_PROJECT = 300 * 1024 * 1024
MAX_UPLOAD = 40 * 1024 * 1024
MAX_SVG = 60 * 1024 * 1024

app = FastAPI(title="Vektorwerk")
_WORK = threading.Lock()


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC / "index.html")


@app.get("/api/presets")
def presets() -> dict:
    return PRESETS


def _json_list(raw: str | None, kind, name: str, max_len: int = 64):
    if not raw:
        return None
    try:
        val = json.loads(raw)
        assert isinstance(val, list) and len(val) <= max_len
        return [kind(v) for v in val]
    except Exception as e:
        raise HTTPException(400, f"Ungültiger Parameter „{name}“.") from e


@app.post("/api/vectorize")
def api_vectorize(
    file: UploadFile = File(...),
    preset: str = Form("siebdruck"),
    colors: int = Form(0),
    smoothing: float | None = Form(None),
    speckle: int | None = Form(None),
    background: str = Form("outside"),
    width_cm: float = Form(28.0),
    palette: str | None = Form(None),
    merge: str | None = Form(None),
    hidden: str | None = Form(None),
    underbase: bool = Form(False),
    choke_mm: float = Form(0.3),
    paint: UploadFile | None = File(None),
    min_line_mm: float | None = Form(None),
    fix_thin: bool = Form(False),
) -> JSONResponse:
    data = file.file.read(MAX_UPLOAD + 1)
    paint_png = paint.file.read(MAX_UPLOAD + 1) if paint is not None else None
    if paint_png is not None and len(paint_png) > MAX_UPLOAD:
        raise HTTPException(413, "Pinsel-Daten zu groß.")
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "Datei zu groß (max. 40 MB).")
    if preset not in PRESETS:
        raise HTTPException(400, "Unbekanntes Preset.")
    if background not in ("outside", "all", "keep"):
        raise HTTPException(400, "Unbekannter Hintergrund-Modus.")
    opts = Options(
        preset=preset,
        colors=max(0, min(int(colors), 32)),
        smoothing=None if smoothing is None else max(0.0, min(float(smoothing), 6.0)),
        speckle=None if speckle is None else max(1, min(int(speckle), 60)),
        background=background,
        width_cm=max(1.0, min(float(width_cm), 200.0)),
        palette=_json_list(palette, str, "palette"),
        merge=_json_list(merge, int, "merge"),
        hidden=_json_list(hidden, int, "hidden") or [],
        underbase=bool(underbase),
        choke_mm=max(0.0, min(float(choke_mm), 3.0)),
        paint=paint_png or None,
        min_line_mm=None if min_line_mm is None else max(0.05, min(float(min_line_mm), 5.0)),
        fix_thin=bool(fix_thin),
    )
    try:
        # vtracer/OpenCV parallel in mehreren Threads -> Absturz (Segfault) beobachtet; lokal reicht seriell
        with _WORK:
            r = vectorize(data, opts)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    except Exception as e:  # z. B. kein gültiges Bild
        raise HTTPException(422, f"Bild konnte nicht verarbeitet werden: {e}") from e
    return JSONResponse({
        "svg": r.svg,
        "width": r.width,
        "height": r.height,
        "background": r.background,
        "warnings": r.warnings,
        "palette": r.palette,
        "check": r.check,
        "check_png": ("data:image/png;base64," + base64.b64encode(r.check_png).decode()) if r.check_png else None,
        "layers": [
            {"hex": l.hex, "share": float(l.share), "paths": len(l.paths), "nodes": l.nodes,
             "is_bg": l.is_bg, "pi": l.pi, "role": l.role}
            for l in r.layers
        ],
    })


@app.post("/api/export")
def api_export(svg: str = Form(...), fmt: str = Form(...), name: str = Form("motiv"),
               width_cm: float = Form(28.0)) -> Response:
    if len(svg) > MAX_SVG:
        raise HTTPException(413, "SVG zu groß.")
    if fmt not in SUFFIX:
        raise HTTPException(400, "Unbekanntes Format.")
    base = safe_name(name)
    try:
        with _WORK:
            body, mime = convert(svg, fmt, max(1.0, min(float(width_cm), 200.0)), base)
    except Exception as e:
        raise HTTPException(500, f"Export fehlgeschlagen: {e}") from e
    return Response(body, media_type=mime,
                    headers={"Content-Disposition": f'attachment; filename="{base}{SUFFIX[fmt]}"'})


@app.post("/api/zip")
def api_zip(items: str = Form(...), formats: str = Form(...)) -> Response:
    if len(items) > MAX_SVG * 2:
        raise HTTPException(413, "Zu viele Daten.")
    try:
        its = json.loads(items)
        fmts = [f for f in json.loads(formats) if f in SUFFIX]
        assert isinstance(its, list) and its and fmts
        for it in its:
            assert isinstance(it, dict) and isinstance(it.get("svg"), str)
    except Exception as e:
        raise HTTPException(400, "Ungültige Anfrage.") from e
    try:
        with _WORK:
            body = build_zip(its, fmts)
    except Exception as e:
        raise HTTPException(500, f"ZIP fehlgeschlagen: {e}") from e
    return Response(body, media_type="application/zip",
                    headers={"Content-Disposition": 'attachment; filename="Vektorwerk-Motive.zip"'})


# ---------------------------------------------------------------- Projekte
_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,79}$")


def _pfile(pid: str, meta: bool = False) -> Path:
    if not _ID.fullmatch(pid):
        raise HTTPException(400, "Ungültige Projekt-ID.")
    return PROJECTS / (f"{pid}.meta.json" if meta else f"{pid}.json")


@app.get("/api/projects")
def list_projects() -> list:
    PROJECTS.mkdir(exist_ok=True)
    out = []
    for m in PROJECTS.glob("*.meta.json"):
        try:
            out.append(json.loads(m.read_text("utf-8")))
        except Exception:
            continue
    return sorted(out, key=lambda x: -x.get("updated", 0))


@app.get("/api/projects/{pid}")
def get_project(pid: str) -> FileResponse:
    f = _pfile(pid)
    if not f.exists():
        raise HTTPException(404, "Projekt nicht gefunden.")
    return FileResponse(f, media_type="application/json")


@app.put("/api/projects/{pid}")
async def save_project(pid: str, request: Request) -> dict:
    f = _pfile(pid)
    body = await request.body()
    if len(body) > MAX_PROJECT:
        raise HTTPException(413, "Projekt zu groß.")
    try:
        doc = json.loads(body)
        assert isinstance(doc, dict) and isinstance(doc.get("files"), list)
    except Exception as e:
        raise HTTPException(400, "Ungültige Projektdaten.") from e
    PROJECTS.mkdir(exist_ok=True)
    meta = {"id": pid, "name": str(doc.get("name") or pid)[:120], "updated": time.time(),
            "files": len(doc["files"]), "autosave": pid == "autosave",
            "thumb": next((x.get("thumb") for x in doc["files"] if isinstance(x, dict) and x.get("thumb")), None)}
    tmp = f.with_suffix(".tmp")
    tmp.write_bytes(body)
    tmp.replace(f)
    _pfile(pid, True).write_text(json.dumps(meta), "utf-8")
    return meta


@app.delete("/api/projects/{pid}")
def delete_project(pid: str) -> dict:
    for f in (_pfile(pid), _pfile(pid, True)):
        f.unlink(missing_ok=True)
    return {"ok": True}


app.mount("/static", StaticFiles(directory=STATIC), name="static")
