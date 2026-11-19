"""Export: Formate, Siebdruck-Filme (mehrseitiges PDF) und ZIP-Pakete."""

from __future__ import annotations

import io
import re
import zipfile
import xml.etree.ElementTree as ET
from datetime import date
from xml.sax.saxutils import escape

import cairosvg
from pypdf import PdfReader, PdfWriter

SVG_NS = "http://www.w3.org/2000/svg"

MARGIN = 25.0  # mm Rand um das Motiv
MARK_GAP = 9.0  # mm Abstand Passkreuz <-> Motiv
MARK_R = 4.0  # mm Radius Passkreuz
FOOT = 12.0  # mm Platz für die Fußzeile
FONT = 'font-family="DejaVu Sans, Arial, sans-serif"'


def _parse(svg: str):
    root = ET.fromstring(svg.encode("utf-8"))
    vb = [float(v) for v in root.get("viewBox", "0 0 100 100").replace(",", " ").split()]
    groups = [g for g in root.iter(f"{{{SVG_NS}}}g") if g.get("data-color") and g.get("data-role") != "cut"]
    return vb, groups


# Schnittlinie (Sticker/Aufnäher/Button) ist eine Ebene mit data-role="cut": im PDF/EPS/SVG bleibt sie
# als Volltonfarbe „CutContour“ erhalten, im PNG und in den Filmen wird sie nicht mitgedruckt.
_CUT = re.compile(r'<g\b[^>]*data-role="cut"[^>]*>.*?</g>', re.S)


def strip_cut(svg: str) -> str:
    return _CUT.sub("", svg)


def _paths(g) -> str:
    return "".join(f'<path d="{escape(p.get("d"))}"/>' for p in g.iter(f"{{{SVG_NS}}}path") if p.get("d"))


def _marks(w: float, h: float) -> str:
    """Passkreuze mittig an allen vier Seiten + Eckmarken (alles in mm)."""
    ox = oy = MARGIN
    cx, cy = ox + w / 2, oy + h / 2
    r = MARK_R
    out = ['<g fill="none" stroke="#000" stroke-width="0.25">']
    for x, y in [(cx, oy - MARK_GAP), (cx, oy + h + MARK_GAP), (ox - MARK_GAP, cy), (ox + w + MARK_GAP, cy)]:
        out.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{r * 0.6:.2f}"/>'
                   f'<path d="M{x - r:.2f} {y:.2f}H{x + r:.2f}M{x:.2f} {y - r:.2f}V{y + r:.2f}"/>')
    L = 6.0
    for x, y, sx, sy in [(ox, oy, -1, -1), (ox + w, oy, 1, -1), (ox, oy + h, -1, 1), (ox + w, oy + h, 1, 1)]:
        out.append(f'<path d="M{x + sx * 3:.2f} {y:.2f}h{sx * L:.2f}M{x:.2f} {y + sy * 3:.2f}v{sy * L:.2f}"/>')
    out.append("</g>")
    return "".join(out)


def _page(inner: str, vb, w: float, h: float, footer: str, extra: str = "", extra_h: float = 0) -> str:
    pw, ph = w + 2 * MARGIN, h + 2 * MARGIN + extra_h + FOOT
    return (
        f'<svg xmlns="{SVG_NS}" width="{pw:.2f}mm" height="{ph:.2f}mm" viewBox="0 0 {pw:.2f} {ph:.2f}">'
        f'<rect width="100%" height="100%" fill="#fff"/>'
        f'<svg x="{MARGIN}" y="{MARGIN}" width="{w:.2f}" height="{h:.2f}" '
        f'viewBox="{" ".join(f"{v:g}" for v in vb)}" preserveAspectRatio="none">{inner}</svg>'
        f"{_marks(w, h)}{extra}"
        f'<text x="{MARGIN}" y="{ph - FOOT / 2:.2f}" {FONT} font-size="3.4" fill="#000">{footer}</text>'
        f"</svg>"
    )


def _labels(groups) -> list[tuple[str, str]]:
    out, n = [], 0
    for g in groups:
        hexc = (g.get("data-color") or "#000000").upper()
        pms = g.get("data-pantone") or ""
        role = g.get("data-role")
        if role == "underbase":
            out.append(("Unterleger Weiß", hexc))
        elif role in ("bg", "border"):
            out.append(((g.get("{http://www.inkscape.org/namespaces/inkscape}label") or "Hintergrund").split(" #")[0], hexc))
        else:
            n += 1
            out.append((f"Farbe {n}", f"{pms} ({hexc})" if pms else hexc))
    return out


def films_pdf(svg: str, name: str, width_cm: float) -> bytes:
    """Seite 1: farbige Übersicht mit Farbliste in Druckreihenfolge. Danach ein schwarzer Film pro Ebene."""
    vb, groups = _parse(svg)
    if not groups:
        raise ValueError("Keine Farbebenen im SVG.")
    w = width_cm * 10.0
    h = w * vb[3] / vb[2]
    size = f"{w / 10:.1f} × {h / 10:.1f} cm".replace(".", ",")
    today = date.today().strftime("%d.%m.%Y")
    title = escape(name)
    labels = _labels(groups)
    n = len(groups)

    # Übersicht: farbig, mit Legende
    inner = "".join(f'<g fill="{escape(g.get("data-color"))}" fill-rule="evenodd">{_paths(g)}</g>' for g in groups)
    row_h = 6.5
    legend = [f'<text x="{MARGIN}" y="{MARGIN + h + MARGIN - 4:.2f}" {FONT} font-size="3.6" '
              f'font-weight="bold">Druckreihenfolge</text>']
    for i, ((lab, val), g) in enumerate(zip(labels, groups)):
        y = MARGIN + h + MARGIN + 2 + i * row_h
        legend.append(f'<rect x="{MARGIN}" y="{y:.2f}" width="4.5" height="4.5" '
                      f'fill="{escape(g.get("data-color"))}" stroke="#000" stroke-width="0.2"/>'
                      f'<text x="{MARGIN + 7:.2f}" y="{y + 3.6:.2f}" {FONT} font-size="3.4">'
                      f"{i + 1}. {escape(lab)} – {escape(val)}</text>")
    pages = [_page(inner, vb, w, h, f"{title} · Übersicht · {n} Filme · {size} · {today}",
                   "".join(legend), extra_h=n * row_h + 4)]

    for i, ((lab, val), g) in enumerate(zip(labels, groups), 1):
        inner = f'<g fill="#000" fill-rule="evenodd">{_paths(g)}</g>'
        pages.append(_page(inner, vb, w, h,
                           f"{title} · Film {i}/{n} · {escape(lab)} · {escape(val)} · {size} · {today}"))

    writer = PdfWriter()
    for p in pages:
        for page in PdfReader(io.BytesIO(cairosvg.svg2pdf(bytestring=p.encode("utf-8")))).pages:
            writer.add_page(page)
    buf = io.BytesIO()
    writer.write(buf)
    return buf.getvalue()


def convert(svg: str, fmt: str, width_cm: float, name: str = "motiv") -> tuple[bytes, str]:
    raw = svg.encode("utf-8")
    if fmt == "svg":
        return raw, "image/svg+xml"
    if fmt == "pdf":
        return cairosvg.svg2pdf(bytestring=raw), "application/pdf"
    if fmt == "eps":
        return cairosvg.svg2eps(bytestring=raw), "application/postscript"
    if fmt == "png":  # 300 dpi in Druckbreite, transparent
        px = int(round(width_cm / 2.54 * 300))
        return cairosvg.svg2png(bytestring=strip_cut(svg).encode("utf-8"), output_width=min(px, 12000)), "image/png"
    if fmt == "films":
        return films_pdf(svg, name, width_cm), "application/pdf"
    raise ValueError("Unbekanntes Format.")


SUFFIX = {"svg": ".svg", "pdf": ".pdf", "eps": ".eps", "png": ".png", "films": "_Filme.pdf"}
_SAFE = re.compile(r"[^\w.\- ]+")


def safe_name(name: str) -> str:
    return _SAFE.sub("_", name).strip() or "motiv"


def build_zip(items: list[dict], formats: list[str]) -> bytes:
    buf = io.BytesIO()
    used: set[str] = set()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for it in items:
            base = safe_name(str(it.get("name", "motiv")))
            folder, k = base, 2
            while folder in used:
                folder, k = f"{base}_{k}", k + 1
            used.add(folder)
            for fmt in formats:
                body, _ = convert(it["svg"], fmt, float(it.get("width_cm", 28)), base)
                z.writestr(f"{folder}/{base}{SUFFIX[fmt]}", body)
            cut = it.get("cut_svg")
            if isinstance(cut, str) and cut.strip():  # nur Schnittlinie, für Schneideplotter
                z.writestr(f"{folder}/{base}_Schnittlinie.svg", cut.encode("utf-8"))
                z.writestr(f"{folder}/{base}_Schnittlinie.pdf", convert(cut, "pdf", float(it.get("width_cm", 28)))[0])
    return buf.getvalue()
