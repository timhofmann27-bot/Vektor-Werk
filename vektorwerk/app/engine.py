"""Vektorwerk-Engine: Rasterbild -> druckfertiges, nach Farben getrenntes SVG.

Pipeline:
  1. Laden (EXIF-Drehung, Transparenz)
  2. Auf Arbeitsauflösung skalieren (kleine Bilder werden hochskaliert)
  3. Kantenerhaltend entrauschen (JPEG-Artefakte weg)
  4. Hintergrund erkennen (Rand-Farbe, Flood-Fill von außen)
  5. Farben reduzieren (k-Means in Lab, automatische Farbanzahl)
  6. Kanten glätten (weichgezeichnete Masken -> argmax)
  7. Jede Farbe einzeln mit vtracer nachzeichnen -> eine Ebene pro Farbe
"""

from __future__ import annotations

import hashlib
import io
import re
import threading
from collections import OrderedDict
from dataclasses import dataclass, field

import cv2
import numpy as np
import vtracer
from PIL import Image, ImageOps

# ---------------------------------------------------------------- Presets ---

PRESETS: dict[str, dict] = {
    "siebdruck": {
        "label": "Siebdruck",
        "hint": "Volltonfarben, eine Ebene pro Sieb, 1 px Überfüllung",
        "max_colors": 6,
        "stacking": "cutout",
        "trap": 1.0,
        "smoothing": 1.6,
        "speckle": 6,
        "corner": 60,
        "min_line_mm": 0.5,
    },
    "plotter": {
        "label": "Flex / Flock (Plotter)",
        "hint": "Wenige saubere Flächen, stark vereinfachte Pfade",
        "max_colors": 3,
        "stacking": "cutout",
        "trap": 0.0,
        "smoothing": 2.2,
        "speckle": 10,
        "corner": 70,
        "min_line_mm": 1.0,
    },
    "dtf": {
        "label": "DTF / Digitaldruck",
        "hint": "Mehr Farben und Details, Ebenen gestapelt ohne Blitzer",
        "max_colors": 16,
        "stacking": "stacked",
        "trap": 0.0,
        "smoothing": 1.0,
        "speckle": 3,
        "corner": 60,
        "min_line_mm": 0.3,
    },
    "detail": {
        "label": "Maximale Details",
        "hint": "Bis 32 Farben, für Illustrationen",
        "max_colors": 32,
        "stacking": "stacked",
        "trap": 0.0,
        "smoothing": 0.6,
        "speckle": 2,
        "corner": 50,
        "min_line_mm": 0.3,
    },
}

WORK_LONG_SIDE = 2000  # Arbeitsauflösung in px (lange Seite)


@dataclass
class Options:
    preset: str = "siebdruck"
    colors: int = 0  # 0 = automatisch (bis max_colors des Presets)
    smoothing: float | None = None
    speckle: int | None = None
    background: str = "outside"  # outside | all | keep
    trap: float | None = None
    width_cm: float = 28.0
    # Feste Palette (Hex-Liste) statt automatischer Farbsuche – für reproduzierbare Neuberechnung
    palette: list[str] | None = None
    # merge[i] = Zielindex: Farbe i wird in Farbe merge[i] aufgenommen (nur mit palette)
    merge: list[int] | None = None
    hidden: list[int] = field(default_factory=list)  # Paletten-Indizes, die nicht gedruckt werden
    underbase: bool = False  # Weiß-Unterleger für dunkle Shirts
    choke_mm: float = 0.3  # Unterleger so viel kleiner als das Motiv
    paint: bytes | None = None  # Pinsel-Korrekturen als PNG in Arbeitsauflösung (nur mit palette)
    min_line_mm: float | None = None  # Druck-Check: Mindeststrichstärke (None = Preset)
    fix_thin: bool = False  # zu dünne Stellen automatisch verdicken

    def resolved(self) -> dict:
        p = dict(PRESETS.get(self.preset, PRESETS["siebdruck"]))
        if self.smoothing is not None:
            p["smoothing"] = float(self.smoothing)
        if self.speckle is not None:
            p["speckle"] = int(self.speckle)
        if self.trap is not None:
            p["trap"] = float(self.trap)
        return p


@dataclass
class Layer:
    hex: str
    share: float  # Flächenanteil am Motiv in %
    paths: list[str] = field(default_factory=list)
    nodes: int = 0
    is_bg: bool = False  # Farbe entspricht dem entfernten Hintergrund (z. B. weiße Buchstaben-Innenräume)
    pi: int = 0  # Index in der Palette (-1 = Unterleger)
    role: str = "color"  # color | underbase


@dataclass
class Result:
    svg: str
    layers: list[Layer]
    width: int
    height: int
    background: str | None
    warnings: list[str]
    palette: list[str] = field(default_factory=list)
    check: dict = field(default_factory=dict)  # Druck-Check-Zusammenfassung
    check_png: bytes = b""  # Overlay der Problemstellen (halbe Auflösung), leer wenn alles ok


# ----------------------------------------------------------- Farbhelfer ---

def _to_hex(rgb) -> str:
    r, g, b = (int(round(float(c))) for c in rgb)
    return f"#{r:02X}{g:02X}{b:02X}"


def _lab(rgb_u8: np.ndarray) -> np.ndarray:
    """RGB uint8 (N,3) -> echtes CIE-Lab float32 (N,3)."""
    f = rgb_u8.reshape(-1, 1, 3).astype(np.float32) / 255.0
    return cv2.cvtColor(f, cv2.COLOR_RGB2Lab).reshape(-1, 3)


def _snap(lab: np.ndarray) -> np.ndarray:
    """Fast-Weiß -> Weiß, Fast-Schwarz -> Schwarz, fast neutrale Grautöne -> neutral."""
    out = lab.copy()
    chroma = np.hypot(out[:, 1], out[:, 2])
    neutral = chroma < 7
    out[neutral, 1:] = 0
    out[neutral & (out[:, 0] > 85), 0] = 100
    out[neutral & (out[:, 0] < 18), 0] = 0
    return out


def _lab_to_rgb(lab: np.ndarray) -> np.ndarray:
    f = lab.reshape(-1, 1, 3).astype(np.float32)
    rgb = cv2.cvtColor(f, cv2.COLOR_Lab2RGB).reshape(-1, 3)
    return np.clip(rgb * 255.0, 0, 255)


# ------------------------------------------------------------ Schritte ---

def _load(data: bytes) -> tuple[np.ndarray, np.ndarray]:
    img = Image.open(io.BytesIO(data))
    img = ImageOps.exif_transpose(img)
    img = img.convert("RGBA")
    arr = np.array(img)
    return arr[:, :, :3].copy(), arr[:, :, 3].copy()


def _flatten(rgb: np.ndarray) -> np.ndarray:
    """Mean-Shift-Filter: macht Flächen einfarbig (JPEG-Blöcke, Rauschen), Kanten bleiben.

    Läuft auf max. 1200 px, damit es schnell bleibt; danach zurück auf Originalgröße.
    """
    h, w = rgb.shape[:2]
    s = min(1.0, 1200 / max(h, w))
    small = cv2.resize(rgb, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else rgb
    out = cv2.pyrMeanShiftFiltering(small, sp=5, sr=22)
    if s < 1:
        # Nur die Farbkorrektur übernehmen, Kantenschärfe vom Original behalten
        diff = out.astype(np.int16) - small.astype(np.int16)
        diff = cv2.resize(diff.astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)
        return np.clip(rgb.astype(np.float32) + diff, 0, 255).astype(np.uint8)
    return out


def _rescale(rgb: np.ndarray, alpha: np.ndarray) -> tuple[np.ndarray, np.ndarray, float]:
    h, w = rgb.shape[:2]
    scale = WORK_LONG_SIDE / max(h, w)
    if abs(scale - 1) < 0.05:
        return rgb, alpha, 1.0
    interp = cv2.INTER_CUBIC if scale > 1 else cv2.INTER_AREA
    size = (max(1, round(w * scale)), max(1, round(h * scale)))
    return cv2.resize(rgb, size, interpolation=interp), cv2.resize(alpha, size, interpolation=interp), scale


def _denoise(rgb: np.ndarray, upscaled: bool) -> np.ndarray:
    out = cv2.bilateralFilter(rgb, 9, 40, 7)
    if upscaled:  # hochskalierte Bilder haben weiche, verrauschte Kanten
        out = cv2.bilateralFilter(out, 9, 30, 7)
    return out


def _detect_background(rgb: np.ndarray, alpha: np.ndarray, mode: str) -> tuple[np.ndarray, str | None]:
    """Gibt Maske (True = Hintergrund) und die erkannte Hintergrundfarbe zurück."""
    h, w = alpha.shape
    transparent = alpha < 128
    if mode == "keep":
        return transparent, None
    if transparent.mean() > 0.02:  # echtes PNG mit Transparenz -> das ist der Hintergrund
        return transparent, None

    border = np.concatenate([rgb[0], rgb[-1], rgb[:, 0], rgb[:, -1]]).astype(np.int32)
    q = border // 16
    keys = q[:, 0] * 256 + q[:, 1] * 16 + q[:, 2]
    vals, counts = np.unique(keys, return_counts=True)
    top = vals[np.argmax(counts)]
    if counts.max() / len(keys) < 0.5:
        return transparent, None  # kein einheitlicher Rand -> kein Hintergrund
    bg = np.median(border[keys == top], axis=0)

    lab = _lab(rgb).reshape(h, w, 3)
    bg_lab = _lab(np.array([bg], np.uint8))[0]
    close = np.linalg.norm(lab - bg_lab, axis=2) < 12.0

    if mode == "all":
        return close | transparent, _to_hex(bg)

    # "outside": nur zusammenhängende Flächen, die den Rand berühren
    _, labels = cv2.connectedComponents(close.astype(np.uint8), connectivity=4)
    edge = np.unique(np.concatenate([labels[0], labels[-1], labels[:, 0], labels[:, -1]]))
    edge = edge[edge != 0]
    mask = np.isin(labels, edge)
    return mask | transparent, _to_hex(bg)


def _merge(centers, counts, k_target: int | None, delta_e: float):
    """Agglomeratives Zusammenlegen ähnlicher Farben.

    k_target=None: so lange zusammenlegen, wie zwei Farben näher als delta_e sind.
    k_target=n:    so lange, bis nur noch n Farben übrig sind (kleine Flächen zuerst).
    """
    centers = [c.astype(np.float64) for c in centers]
    counts = [float(c) for c in counts]
    while len(centers) > 1:
        if k_target is not None and len(centers) <= k_target:
            break
        best, pair = np.inf, None
        for i in range(len(centers)):
            for j in range(i + 1, len(centers)):
                d = np.linalg.norm(centers[i] - centers[j])
                if k_target is None:
                    if d < delta_e and d < best:
                        best, pair = d, (i, j)
                else:
                    cost = d * min(counts[i], counts[j]) ** 0.5
                    if cost < best:
                        best, pair = cost, (i, j)
        if pair is None:
            break
        i, j = pair
        tot = counts[i] + counts[j]
        centers[i] = (centers[i] * counts[i] + centers[j] * counts[j]) / tot
        counts[i] = tot
        del centers[j], counts[j]
    return np.array(centers, np.float32), np.array(counts)


def _quantize(rgb: np.ndarray, fg: np.ndarray, k_req: int, k_max: int) -> tuple[np.ndarray, np.ndarray, list[str]]:
    """k-Means in Lab. Gibt Label-Karte (-1 = Hintergrund), Lab-Zentren und Warnungen zurück."""
    warnings: list[str] = []
    h, w = fg.shape
    labels = np.full((h, w), -1, np.int32)
    pix = rgb[fg]
    if len(pix) == 0:
        return labels, np.zeros((0, 3), np.float32), ["Kein Motiv gefunden – nur Hintergrund erkannt."]

    lab_img = _lab(rgb).reshape(h, w, 3)
    lab = lab_img[fg]

    # Farben nur aus "flachen" Pixeln lernen: Kanten (Antialiasing, JPEG-Ringe) sind
    # Mischfarben und würden sonst eigene, falsche Farben erzeugen.
    k3 = np.ones((3, 3), np.uint8)
    grad = np.max(cv2.dilate(lab_img, k3) - cv2.erode(lab_img, k3), axis=2)
    flat = fg & (cv2.dilate(grad, k3) < 10.0)
    src = lab_img[flat] if flat.sum() > 0.2 * fg.sum() else lab
    rng = np.random.default_rng(0)
    sample = np.ascontiguousarray(src[rng.choice(len(src), min(len(src), 150_000), replace=False)])

    auto = k_req <= 0
    k0 = int(min(max(k_max * 2, 8) if auto else k_req, 40, len(sample)))
    crit = (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 40, 0.2)
    cv2.setRNGSeed(0)
    _, lbl, centers = cv2.kmeans(sample, k0, None, crit, 3, cv2.KMEANS_PP_CENTERS)
    counts = np.bincount(lbl.ravel(), minlength=k0).astype(np.float64)

    if auto:
        centers, counts = _merge(centers, counts, None, delta_e=14.0)
        share = counts / counts.sum()
        keep = share >= 0.004
        if keep.any():
            centers, counts = centers[keep], counts[keep]
        if len(centers) > k_max:
            warnings.append(
                f"Das Motiv hat mehr als {k_max} deutlich verschiedene Farben (Verläufe oder Foto?). "
                f"Es wurde auf {k_max} reduziert. Für Siebdruck/Plotter das Motiv ggf. vereinfachen "
                f"oder „DTF“ wählen."
            )
            centers, counts = _merge(centers, counts, k_max, 0)

    # Alle Vordergrundpixel dem nächsten Zentrum zuordnen (blockweise, speicherschonend)
    assign = np.empty(len(lab), np.int32)
    step = 400_000
    for i in range(0, len(lab), step):
        d = np.linalg.norm(lab[i:i + step, None, :] - centers[None, :, :], axis=2)
        assign[i:i + step] = d.argmin(1)
    # Zentren als echten Mittelwert der zugeordneten Pixel neu berechnen
    centers = np.array([lab[assign == j].mean(0) if np.any(assign == j) else centers[j]
                        for j in range(len(centers))], np.float32)
    labels[fg] = assign

    if auto and len(centers) > 2:
        labels, centers = _drop_transition_colors(labels, centers, lab_img, fg)
    return labels, centers, warnings


def _drop_transition_colors(labels, centers, lab_img, fg):
    """Entfernt Mischfarben, die nur als schmaler Saum zwischen zwei anderen Farben vorkommen
    (Antialiasing, Unschärfe). Kriterien: Farbe liegt im Lab-Raum nahe der Verbindungslinie
    zweier anderer Farben UND ihre Fläche ist dünn (verschwindet fast beim Erodieren)."""
    k = len(centers)
    areas = np.array([(labels == j).sum() for j in range(k)], np.float64)
    total = areas.sum()
    r = max(2, int(round(max(labels.shape) / 700)))
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    drop = []
    for c in range(k):
        if areas[c] / total > 0.08:
            continue
        mixed = False
        for a in range(k):
            for b in range(a + 1, k):
                if c in (a, b) or areas[a] < areas[c] or areas[b] < areas[c]:
                    continue
                ab = centers[b] - centers[a]
                t = float(np.dot(centers[c] - centers[a], ab) / max(1e-6, np.dot(ab, ab)))
                if 0.1 < t < 0.9 and np.linalg.norm(centers[a] + t * ab - centers[c]) < 9.0:
                    mixed = True
                    break
            if mixed:
                break
        if not mixed:
            continue
        m = (labels == c).astype(np.uint8)
        if cv2.erode(m, ker).sum() < 0.25 * m.sum():  # dünner Saum
            drop.append(c)
    if not drop:
        return labels, centers
    keep = [j for j in range(k) if j not in drop]
    new_centers = centers[keep]
    remap = {old: new for new, old in enumerate(keep)}
    out = labels.copy()
    affected = np.isin(labels, drop)
    d = np.linalg.norm(lab_img[affected][:, None, :] - new_centers[None, :, :], axis=2)
    out[affected] = d.argmin(1)
    for old, new in remap.items():
        out[labels == old] = new
    return out, new_centers


def _smooth_labels(labels: np.ndarray, k: int, sigma: float) -> np.ndarray:
    """Weichgezeichnete Masken je Farbe -> argmax. Glättet Treppen, ohne neue Farben zu erzeugen."""
    if sigma <= 0:
        return labels
    h, w = labels.shape
    best = np.full((h, w), -1.0, np.float32)
    out = np.full((h, w), -1, np.int32)
    for j in range(-1, k):
        m = cv2.GaussianBlur((labels == j).astype(np.float32), (0, 0), sigma)
        better = m > best
        out[better] = j
        best[better] = m[better]
    return out


_NUM = r"-?\d+(?:\.\d+)?(?:[eE]-?\d+)?"
_TOKEN = re.compile(r"[MLCZ]|" + _NUM)
_PATH = re.compile(r'<path d="([^"]+)"[^>]*?(?:transform="translate\(([-\d.]+),([-\d.]+)\)")?\s*/>')


def _fmt(v: float) -> str:
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _trace_mask(mask: np.ndarray, opts: dict, scale: float) -> tuple[list[str], int]:
    """Binärmaske (True = Farbe) -> Liste von Pfad-d-Strings (absolute Koordinaten)."""
    h, w = mask.shape
    gray = np.where(mask, 0, 255).astype(np.uint8)
    # Übergabe als PNG statt als Python-Pixelliste: convert_pixels_to_svg hat mit Millionen
    # Tupeln den Interpreter-Speicher beschädigt (Segfault in späteren Aufrufen).
    ok, png = cv2.imencode(".png", gray)
    if not ok:
        raise RuntimeError("Maske konnte nicht kodiert werden.")
    speckle = max(1, int(round(opts["speckle"] * max(1.0, scale ** 0.5))))
    svg = vtracer.convert_raw_image_to_svg(
        png.tobytes(), img_format="png",
        colormode="binary",
        mode="spline",
        filter_speckle=speckle,
        corner_threshold=opts["corner"],
        length_threshold=4.0,
        max_iterations=10,
        splice_threshold=45,
        path_precision=3,
    )
    paths, nodes = [], 0
    for m in _PATH.finditer(svg):
        d, tx, ty = m.group(1), float(m.group(2) or 0), float(m.group(3) or 0)
        out, ci = [], 0
        for t in _TOKEN.findall(d):  # Verschiebung direkt in die Koordinaten einrechnen
            if t in "MLCZ":
                out.append(t)
                ci = 0
                if t != "Z":
                    nodes += 1
                continue
            out.append(_fmt(float(t) + (tx if ci % 2 == 0 else ty)))
            ci += 1
        paths.append(" ".join(out))
    return paths, nodes


# ------------------------------------------------------------- Haupt-API ---

_CACHE: OrderedDict[tuple, tuple] = OrderedDict()
_CACHE_LOCK = threading.Lock()


def _prepare(data: bytes, flatten: bool, background: str):
    """Vorverarbeitung (teuer) – gecacht, damit Neuberechnungen schnell sind."""
    key = (hashlib.sha1(data).hexdigest(), flatten, background)
    with _CACHE_LOCK:
        if key in _CACHE:
            _CACHE.move_to_end(key)
            return _CACHE[key]
    rgb, alpha = _load(data)
    if flatten:  # für Volltonmotive: Flächen vor dem Skalieren glätten
        rgb = _flatten(rgb)
    rgb, alpha, scale = _rescale(rgb, alpha)
    rgb = _denoise(rgb, upscaled=scale > 1.05)
    bg_mask, bg_hex = _detect_background(rgb, alpha, background)
    out = (rgb, scale, bg_mask, bg_hex)
    with _CACHE_LOCK:
        _CACHE[key] = out
        while len(_CACHE) > 8:
            _CACHE.popitem(last=False)
    return out


def _hex_to_rgb(h: str) -> list[int]:
    h = h.strip().lstrip("#")
    if not re.fullmatch(r"[0-9a-fA-F]{6}", h):
        raise ValueError(f"Ungültige Farbe: {h!r}")
    return [int(h[i:i + 2], 16) for i in (0, 2, 4)]


def _assign_palette(rgb, fg, palette: list[str], merge: list[int] | None):
    """Feste Palette: jedes Pixel bekommt die nächste Palettenfarbe, danach Zusammenlegen."""
    h, w = fg.shape
    centers = _lab(np.array([_hex_to_rgb(p) for p in palette], np.uint8))
    labels = np.full((h, w), -1, np.int32)
    lab = _lab(rgb[fg])
    assign = np.empty(len(lab), np.int32)
    step = 400_000
    for i in range(0, len(lab), step):
        assign[i:i + step] = np.linalg.norm(lab[i:i + step, None, :] - centers[None], axis=2).argmin(1)
    if merge:
        if len(merge) != len(palette):
            raise ValueError("merge muss genauso lang sein wie palette")
        target = np.arange(len(palette))
        for i, t in enumerate(merge):
            if not 0 <= int(t) < len(palette):
                raise ValueError("Ungültiger merge-Index")
            target[i] = int(t)
        for _ in range(len(palette)):  # Ketten auflösen (a->b->c)
            target = target[target]
        assign = target[assign]
    else:
        target = np.arange(len(palette))
    labels[fg] = assign
    return labels, centers, target


PAINT_ERASE = 255  # Pinsel-Code: zu Hintergrund machen; 1..64 = Palettenindex + 1


def _apply_paint(labels: np.ndarray, paint_png: bytes, target: np.ndarray) -> np.ndarray:
    """Pinsel-Korrekturen übernehmen. PNG: Rotkanal = Code, Alpha > 0 = bemalt."""
    h, w = labels.shape
    img = np.array(Image.open(io.BytesIO(paint_png)).convert("RGBA"))
    if img.shape[:2] != (h, w):
        img = cv2.resize(img, (w, h), interpolation=cv2.INTER_NEAREST)
    code = img[:, :, 0].astype(np.int32)
    painted = img[:, :, 3] > 0
    out = labels.copy()
    out[painted & (code == PAINT_ERASE)] = -1
    for c in np.unique(code[painted]):
        if 1 <= c <= len(target):
            out[painted & (code == c)] = int(target[c - 1])
    return out


def _thin_parts(mask: np.ndarray, r: int) -> np.ndarray:
    """Teile einer Maske, die schmaler als 2r sind (verschwinden beim Öffnen mit Radius r)."""
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    m = mask.astype(np.uint8)
    thin = (m & ~cv2.morphologyEx(m, cv2.MORPH_OPEN, ker).astype(bool)).astype(np.uint8)
    thin = cv2.morphologyEx(thin, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))  # Eckenkrümel weg
    return _drop_small(thin, max(6, r * r))


def _drop_small(m: np.ndarray, min_area: int) -> np.ndarray:
    n, lab, stats, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
    keep = np.zeros(n, bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= min_area
    return keep[lab]


def _print_check(labels: np.ndarray, k: int, hidden: set[int], px_per_mm: float, min_mm: float,
                 out_hex: list[str]) -> tuple[dict, np.ndarray]:
    """Findet zu dünne Linien/Details und zu enge Lücken (zum Shirt hin) je Farbe.

    Gibt eine Zusammenfassung und eine RGBA-Overlay-Karte (rot = zu dünn, blau = Lücke zu eng) zurück.
    """
    h, w = labels.shape
    r = max(1, int(round(min_mm * px_per_mm / 2)))
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    overlay = np.zeros((h, w, 4), np.uint8)
    issues = []
    for j in range(k):
        if j in hidden:
            continue
        mask = labels == j
        if not mask.any():
            continue
        thin = _thin_parts(mask, r)
        m = mask.astype(np.uint8)
        gap = cv2.morphologyEx(m, cv2.MORPH_CLOSE, ker).astype(bool) & ~mask & (labels == -1)
        gap = _drop_small(cv2.morphologyEx(gap.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)),
                          max(6, r * r))
        n_thin = cv2.connectedComponents(thin.astype(np.uint8))[0] - 1
        n_gap = cv2.connectedComponents(gap.astype(np.uint8))[0] - 1
        if n_thin or n_gap:
            issues.append({"pi": j, "hex": out_hex[j], "thin": int(n_thin), "gaps": int(n_gap)})
        overlay[thin] = (255, 40, 40, 230)
        overlay[gap] = (20, 120, 255, 210)
    # Markierungen etwas dicker zeichnen, damit sie auch verkleinert sichtbar sind
    a = cv2.dilate(overlay[:, :, 3], np.ones((5, 5), np.uint8))
    rgb = cv2.dilate(overlay[:, :, :3], np.ones((5, 5), np.uint8))
    overlay = np.dstack([rgb, a])
    return ({"min_mm": min_mm, "issues": issues,
             "total": int(sum(i["thin"] + i["gaps"] for i in issues))}, overlay)


def _thicken(labels: np.ndarray, k: int, hidden: set[int], px_per_mm: float, min_mm: float) -> np.ndarray:
    """Verdickt zu dünne Linien auf die Mindeststärke (wächst in Hintergrund und Nachbarfarben)."""
    r = max(1, int(round(min_mm * px_per_mm / 2)))
    ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    out = labels.copy()
    areas = [(labels == j).sum() for j in range(k)]
    for j in sorted(range(k), key=lambda j: -areas[j]):  # große Flächen zuerst, feine Linien gewinnen
        if j in hidden or areas[j] == 0:
            continue
        thin = _thin_parts(labels == j, r)
        if thin.any():
            grow = cv2.dilate(thin.astype(np.uint8), ker).astype(bool)
            out[grow] = j
    return out


def vectorize(data: bytes, options: Options) -> Result:
    opts = options.resolved()
    rgb, scale, bg_mask, bg_hex = _prepare(data, opts["max_colors"] <= 16, options.background)
    h, w = bg_mask.shape

    if options.palette:
        labels, centers, target = _assign_palette(rgb, ~bg_mask, options.palette, options.merge)
        warnings: list[str] = []
        out_hex = [p.upper() if p.startswith("#") else "#" + p.upper() for p in options.palette]
        if options.paint:
            labels = _apply_paint(labels, options.paint, target)
    else:
        labels, centers, warnings = _quantize(rgb, ~bg_mask, options.colors, opts["max_colors"])
        out_hex = [_to_hex(c) for c in _lab_to_rgb(_snap(centers))] if len(centers) else []
    k = len(centers)
    labels = _smooth_labels(labels, k, opts["smoothing"] * max(1.0, scale ** 0.5))

    px_per_mm = w / (options.width_cm * 10.0)
    min_mm = options.min_line_mm if options.min_line_mm is not None else opts["min_line_mm"]
    hidden = set(options.hidden)
    if options.fix_thin:
        labels = _thicken(labels, k, hidden, px_per_mm, min_mm)
    check, overlay = _print_check(labels, k, hidden, px_per_mm, min_mm, out_hex)
    buf = io.BytesIO()
    small = cv2.resize(overlay, (max(1, w // 2), max(1, h // 2)), interpolation=cv2.INTER_AREA)
    Image.fromarray(small, "RGBA").save(buf, "PNG", optimize=True)
    check_png = buf.getvalue() if check["total"] else b""

    bg_lab = _lab(np.array([_hex_to_rgb(bg_hex)], np.uint8))[0] if bg_hex else None
    areas = np.array([(labels == j).sum() for j in range(k)], np.float64)
    fg_total = max(1.0, areas.sum())
    order = [int(j) for j in np.argsort(-areas)]  # größte Fläche unten

    kernel = None
    trap_px = opts["trap"] * max(1.0, scale)
    if trap_px > 0:
        r = max(1, int(round(trap_px)))
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))

    layers: list[Layer] = []
    for pos, j in enumerate(order):
        if areas[j] == 0:
            continue
        if opts["stacking"] == "stacked":
            # Untere Ebene reicht unter alle darüberliegenden -> keine Blitzer
            mask = np.isin(labels, order[pos:])
        else:
            mask = labels == j
            if kernel is not None and pos > 0:
                # Überfüllung: obere Farben wachsen leicht in die darunterliegenden hinein
                mask = cv2.dilate(mask.astype(np.uint8), kernel).astype(bool) & (labels >= 0)
        paths, nodes = _trace_mask(mask, opts, scale)
        if paths:
            is_bg = bg_lab is not None and float(np.linalg.norm(centers[j] - bg_lab)) < 16.0
            layers.append(Layer(hex=out_hex[j], share=round(100 * areas[j] / fg_total, 1),
                                paths=paths, nodes=nodes, is_bg=is_bg, pi=j))

    if options.underbase:
        visible = [j for j in range(k) if areas[j] > 0 and j not in set(options.hidden)]
        mask = np.isin(labels, visible).astype(np.uint8)
        choke_px = options.choke_mm / 10.0 / options.width_cm * w
        r = int(round(choke_px))
        if r >= 1:
            mask = cv2.erode(mask, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1)))
        paths, nodes = _trace_mask(mask.astype(bool), opts, scale)
        if paths:
            layers.insert(0, Layer(hex="#FFFFFF", share=round(100 * mask.sum() / fg_total, 1), paths=paths,
                                   nodes=nodes, pi=-1, role="underbase"))

    total_nodes = sum(l.nodes for l in layers)
    if total_nodes > 60_000:
        warnings.append(f"Sehr viele Ankerpunkte ({total_nodes:,}). Glättung oder Fleckenfilter erhöhen."
                        .replace(",", "."))

    return Result(svg=build_svg(layers, w, h, options.width_cm), layers=layers, width=w, height=h,
                  background=bg_hex, warnings=warnings, palette=out_hex, check=check, check_png=check_png)


def build_svg(layers: list[Layer], w: int, h: int, width_cm: float) -> str:
    height_cm = width_cm * h / w
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" '
        f'xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" '
        f'width="{width_cm:.2f}cm" height="{height_cm:.2f}cm" viewBox="0 0 {w} {h}">',
        "<!-- Erstellt mit Vektorwerk -->",
    ]
    n = 0
    for layer in layers:
        if layer.role == "underbase":
            gid, name = "unterleger", "Unterleger Weiß"
        else:
            n += 1
            gid, name = f"farbe-{n}", f"Farbe {n} {layer.hex}"
        parts.append(f'<g id="{gid}" inkscape:groupmode="layer" inkscape:label="{name}" '
                     f'data-color="{layer.hex}" data-pi="{layer.pi}" data-role="{layer.role}" '
                     f'fill="{layer.hex}" fill-rule="evenodd">')
        parts.extend(f'<path d="{d}"/>' for d in layer.paths)
        parts.append("</g>")
    parts.append("</svg>")
    return "\n".join(parts)
