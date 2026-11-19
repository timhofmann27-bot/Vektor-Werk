package de.vektorwerk.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Binärmaske -> glatte SVG-Pfade (Ersatz für vtracer, reines Kotlin, ohne Android-Abhängigkeit).
 *
 * Ablauf je Maske:
 *  1. Fleckenfilter: Inseln und Löcher kleiner als speckle² Pixel entfernen
 *  2. Konturen exakt entlang der Pixelkanten verfolgen (8er-Nachbarschaft für das Motiv)
 *  3. Treppenstufen glätten (Kantenmitten + gleitender Mittelwert), Ecken bleiben scharf
 *  4. Abschnitte zwischen Ecken mit kubischen Béziers annähern (Schneider-Verfahren)
 *
 * Konturen eines zusammenhängenden Teils (Außenrand + Löcher) landen in einem Pfad,
 * damit fill-rule="evenodd" die Löcher korrekt freistellt.
 */
object Tracer {

    class Params(
        val speckle: Int,           // Mindestgröße (Kantenlänge in px) für Inseln und Löcher
        val cornerDeg: Double,      // Knick ab diesem Winkel gilt als Ecke
        val smoothIter: Int = 2,    // Glättungsdurchgänge entlang der Kontur
        val maxError: Double = 0.9, // max. Abweichung der Kurve in px
    )

    class Traced(val paths: List<String>, val nodes: Int)

    // Richtungen: 0 = Ost, 1 = Süd, 2 = West, 3 = Nord (Bildkoordinaten, y nach unten)
    private val DX = intArrayOf(1, 0, -1, 0)
    private val DY = intArrayOf(0, 1, 0, -1)

    fun trace(mask: BooleanArray, w: Int, h: Int, p: Params): Traced {
        val m = mask.copyOf()
        val minArea = max(1, p.speckle * p.speckle)
        if (minArea > 1) {
            removeSmall(m, w, h, minArea, value = true, eight = true)   // Krümel
            removeSmall(m, w, h, minArea, value = false, eight = false) // kleine Löcher
        }
        val comp = IntArray(w * h) { -1 }
        val nComp = labelComponents(m, w, h, comp)
        if (nComp == 0) return Traced(emptyList(), 0)

        val loops = traceLoops(m, w, h, comp)
        val byComp = HashMap<Int, MutableList<IntArray>>()
        for ((c, pts) in loops) byComp.getOrPut(c) { ArrayList() }.add(pts)

        val paths = ArrayList<String>()
        var nodes = 0
        for (c in 0 until nComp) {
            val ls = byComp[c] ?: continue
            val sb = StringBuilder()
            for (pts in ls) nodes += fitLoop(pts, p, sb)
            if (sb.isNotEmpty()) paths.add(sb.toString().trim())
        }
        return Traced(paths, nodes)
    }

    // ------------------------------------------------------------ Fleckenfilter

    private fun removeSmall(m: BooleanArray, w: Int, h: Int, minArea: Int, value: Boolean, eight: Boolean) {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val members = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start] || m[start] != value) continue
            var sp = 0; var cnt = 0; var touchesBorder = false
            stack[sp++] = start; seen[start] = true
            while (sp > 0) {
                val i = stack[--sp]
                members[cnt++] = i
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesBorder = true
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!eight && dx != 0 && dy != 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (!seen[j] && m[j] == value) { seen[j] = true; stack[sp++] = j }
                }
            }
            // Hintergrund, der den Bildrand berührt, ist kein Loch
            if (cnt < minArea && !(!value && touchesBorder)) {
                for (k in 0 until cnt) m[members[k]] = !value
            }
        }
    }

    /** Zusammenhangskomponenten des Motivs (8er-Nachbarschaft). */
    private fun labelComponents(m: BooleanArray, w: Int, h: Int, comp: IntArray): Int {
        val stack = IntArray(w * h)
        var n = 0
        for (start in 0 until w * h) {
            if (!m[start] || comp[start] >= 0) continue
            var sp = 0
            stack[sp++] = start; comp[start] = n
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (m[j] && comp[j] < 0) { comp[j] = n; stack[sp++] = j }
                }
            }
            n++
        }
        return n
    }

    // ------------------------------------------------------------ Konturverfolgung

    /**
     * Verfolgt alle Grenzen zwischen Motiv und Hintergrund auf dem Gitter der Pixelecken.
     * Jede Kante ist so gerichtet, dass das Motivpixel rechts liegt. An Sattelpunkten
     * (schachbrettartige 2×2-Blöcke) wird links abgebogen -> diagonale Pixel bleiben verbunden.
     * Gibt je Schleife (Komponente, Punktliste x0,y0,x1,y1,...) zurück.
     */
    private fun traceLoops(m: BooleanArray, w: Int, h: Int, comp: IntArray): List<Pair<Int, IntArray>> {
        val gw = w + 1
        val out = ByteArray(gw * (h + 1)) // Bitmaske der ausgehenden Richtungen je Gitterpunkt
        fun fg(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        fun mark(i: Int, bit: Int) { out[i] = (out[i].toInt() or bit).toByte() }
        for (y in 0 until h) for (x in 0 until w) {
            if (!m[y * w + x]) continue
            if (!fg(x, y - 1)) mark(y * gw + x, 1)                 // oben: Ost ab (x,y)
            if (!fg(x + 1, y)) mark(y * gw + x + 1, 2)             // rechts: Süd ab (x+1,y)
            if (!fg(x, y + 1)) mark((y + 1) * gw + x + 1, 4)       // unten: West ab (x+1,y+1)
            if (!fg(x - 1, y)) mark((y + 1) * gw + x, 8)           // links: Nord ab (x,y+1)
        }
        val loops = ArrayList<Pair<Int, IntArray>>()
        val buf = IntArrayBuilder()
        for (start in out.indices) {
            while (out[start].toInt() != 0) {
                var d = Integer.numberOfTrailingZeros(out[start].toInt())
                var px = start % gw; var py = start / gw
                val c = comp[pixelRightOf(px, py, d, w)] // Pixel rechts der ersten Kante -> Komponente
                buf.clear()
                buf.add(px); buf.add(py)
                while (true) {
                    val idx = py * gw + px
                    out[idx] = (out[idx].toInt() and (1 shl d).inv()).toByte()
                    px += DX[d]; py += DY[d]
                    val bits = out[py * gw + px].toInt()
                    if (bits == 0) break // Schleife geschlossen
                    val left = (d + 3) and 3; val right = (d + 1) and 3
                    d = when {
                        bits and (1 shl left) != 0 -> left
                        bits and (1 shl d) != 0 -> d
                        else -> right
                    }
                    buf.add(px); buf.add(py)
                }
                if (buf.size >= 8) loops.add(c to buf.toArray())
            }
        }
        return loops
    }

    private fun pixelRightOf(px: Int, py: Int, d: Int, w: Int): Int = when (d) {
        0 -> py * w + px
        1 -> py * w + (px - 1)
        2 -> (py - 1) * w + (px - 1)
        else -> (py - 1) * w + px
    }

    // ------------------------------------------------------------ Glätten + Kurven

    /** Schleife (Gitterpunkte) -> Pfad-Teilstück "M … C … Z". Gibt die Zahl der Knoten zurück. */
    private fun fitLoop(raw: IntArray, p: Params, sb: StringBuilder): Int {
        val n = raw.size / 2
        if (n < 4) return 0
        // 1. Kantenmitten: aus Treppen werden Diagonalen
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (i in 0 until n) {
            val j = (i + 1) % n
            xs[i] = (raw[2 * i] + raw[2 * j]) / 2.0
            ys[i] = (raw[2 * i + 1] + raw[2 * j + 1]) / 2.0
        }

        // 2. Ecken finden (Winkel zwischen Sehnen über k Punkte, nur lokale Maxima)
        val k = max(2, min(5, n / 8))
        val ang = DoubleArray(n)
        for (i in 0 until n) {
            val a = (i - k + n) % n; val b = (i + k) % n
            val ax = xs[i] - xs[a]; val ay = ys[i] - ys[a]
            val bx = xs[b] - xs[i]; val by = ys[b] - ys[i]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            ang[i] = if (la < 1e-9 || lb < 1e-9) 0.0
            else Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)))
        }
        val corners = ArrayList<Int>()
        for (i in 0 until n) {
            if (ang[i] < p.cornerDeg) continue
            var isMax = true
            for (o in -k..k) {
                if (o == 0) continue
                val j = (i + o + n) % n
                if (ang[j] > ang[i] || (ang[j] == ang[i] && o < 0)) { isMax = false; break }
            }
            if (isMax) corners.add(i)
        }

        // 3. Glätten (Ecken bleiben fest)
        val fixed = BooleanArray(n); for (c in corners) fixed[c] = true
        val sx = xs.copyOf(); val sy = ys.copyOf()
        val tx = DoubleArray(n); val ty = DoubleArray(n)
        repeat(p.smoothIter) {
            for (i in 0 until n) {
                if (fixed[i]) { tx[i] = sx[i]; ty[i] = sy[i]; continue }
                val a = (i - 1 + n) % n; val b = (i + 1) % n
                tx[i] = (sx[a] + 2 * sx[i] + sx[b]) / 4.0
                ty[i] = (sy[a] + 2 * sy[i] + sy[b]) / 4.0
            }
            System.arraycopy(tx, 0, sx, 0, n); System.arraycopy(ty, 0, sy, 0, n)
        }

        // 4. In Abschnitte zerlegen und Béziers anpassen
        val smoothCuts = corners.isEmpty()
        val cuts: List<Int> = if (smoothCuts) listOf(0, n / 3, 2 * n / 3) else corners
        sb.append('M').append(fmt(sx[cuts[0]])).append(' ').append(fmt(sy[cuts[0]])).append(' ')
        var nodes = 1
        val curves = ArrayList<DoubleArray>()
        for (ci in cuts.indices) {
            val s = cuts[ci]; val e = cuts[(ci + 1) % cuts.size]
            val len = ((e - s + n) % n).let { if (it == 0) n else it }
            val px = DoubleArray(len + 1); val py = DoubleArray(len + 1)
            for (t in 0..len) { val idx = (s + t) % n; px[t] = sx[idx]; py[t] = sy[idx] }
            val t1: DoubleArray; val t2: DoubleArray
            if (smoothCuts) { t1 = tangentAt(sx, sy, s, n, forward = true); t2 = tangentAt(sx, sy, e, n, forward = false) }
            else { t1 = leftTangent(px, py); t2 = rightTangent(px, py) }
            curves.clear()
            fitCubic(px, py, 0, len, t1, t2, p.maxError * p.maxError, curves, 0)
            for (c in curves) {
                sb.append("C ").append(fmt(c[2])).append(' ').append(fmt(c[3])).append(' ')
                    .append(fmt(c[4])).append(' ').append(fmt(c[5])).append(' ')
                    .append(fmt(c[6])).append(' ').append(fmt(c[7])).append(' ')
                nodes++
            }
        }
        sb.append("Z ")
        return nodes
    }

    private fun tangentAt(xs: DoubleArray, ys: DoubleArray, i: Int, n: Int, forward: Boolean): DoubleArray {
        val r = min(4, max(1, n / 10))
        val a = (i - r + n) % n; val b = (i + r) % n
        val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
        return if (forward) norm(dx, dy) else norm(-dx, -dy)
    }

    private fun leftTangent(x: DoubleArray, y: DoubleArray): DoubleArray {
        val j = min(x.size - 1, 2)
        return norm(x[j] - x[0], y[j] - y[0])
    }

    private fun rightTangent(x: DoubleArray, y: DoubleArray): DoubleArray {
        val l = x.size - 1; val j = max(0, l - 2)
        return norm(x[j] - x[l], y[j] - y[l])
    }

    private fun norm(dx: Double, dy: Double): DoubleArray {
        val l = hypot(dx, dy)
        return if (l < 1e-12) doubleArrayOf(0.0, 0.0) else doubleArrayOf(dx / l, dy / l)
    }

    // ------------------------------------------------------------ Schneider (Graphics Gems I)

    private fun fitCubic(x: DoubleArray, y: DoubleArray, first: Int, last: Int, tHat1: DoubleArray, tHat2: DoubleArray,
                         errSq: Double, out: MutableList<DoubleArray>, depth: Int) {
        val nPts = last - first + 1
        if (nPts <= 2 || depth > 24) {
            val dist = hypot(x[last] - x[first], y[last] - y[first]) / 3.0
            out.add(doubleArrayOf(x[first], y[first],
                x[first] + tHat1[0] * dist, y[first] + tHat1[1] * dist,
                x[last] + tHat2[0] * dist, y[last] + tHat2[1] * dist,
                x[last], y[last]))
            return
        }
        var u = chordLength(x, y, first, last)
        var bz = generateBezier(x, y, first, last, u, tHat1, tHat2)
        var r = maxError(x, y, first, last, bz, u)
        if (r.first < errSq) { out.add(bz); return }
        if (r.first < errSq * 4) {
            repeat(4) {
                val uPrime = reparameterize(x, y, first, last, u, bz)
                bz = generateBezier(x, y, first, last, uPrime, tHat1, tHat2)
                r = maxError(x, y, first, last, bz, uPrime)
                if (r.first < errSq) { out.add(bz); return }
                u = uPrime
            }
        }
        val split = r.second.coerceIn(first + 1, last - 1)
        val tc = centerTangent(x, y, split)
        fitCubic(x, y, first, split, tHat1, tc, errSq, out, depth + 1)
        fitCubic(x, y, split, last, doubleArrayOf(-tc[0], -tc[1]), tHat2, errSq, out, depth + 1)
    }

    private fun centerTangent(x: DoubleArray, y: DoubleArray, c: Int): DoubleArray {
        val a = max(0, c - 1); val b = min(x.size - 1, c + 1)
        val t = norm(x[a] - x[b], y[a] - y[b])
        return if (t[0] == 0.0 && t[1] == 0.0) norm(x[a] - x[c], y[a] - y[c]) else t
    }

    private fun chordLength(x: DoubleArray, y: DoubleArray, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in first + 1..last) u[i - first] = u[i - first - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        val total = u[last - first]
        if (total > 0) for (i in u.indices) u[i] /= total
        return u
    }

    private fun generateBezier(x: DoubleArray, y: DoubleArray, first: Int, last: Int, u: DoubleArray,
                               t1: DoubleArray, t2: DoubleArray): DoubleArray {
        val n = last - first + 1
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        val fx = x[first]; val fy = y[first]; val lx = x[last]; val ly = y[last]
        for (i in 0 until n) {
            val t = u[i]; val mt = 1 - t
            val b0 = mt * mt * mt; val b1 = 3 * t * mt * mt; val b2 = 3 * t * t * mt; val b3 = t * t * t
            val a1x = t1[0] * b1; val a1y = t1[1] * b1
            val a2x = t2[0] * b2; val a2y = t2[1] * b2
            c00 += a1x * a1x + a1y * a1y
            c01 += a1x * a2x + a1y * a2y
            c11 += a2x * a2x + a2y * a2y
            val tmpx = x[first + i] - (fx * (b0 + b1) + lx * (b2 + b3))
            val tmpy = y[first + i] - (fy * (b0 + b1) + ly * (b2 + b3))
            x0 += a1x * tmpx + a1y * tmpy
            x1 += a2x * tmpx + a2y * tmpy
        }
        val det = c00 * c11 - c01 * c01
        var alphaL = if (abs(det) < 1e-12) 0.0 else (x0 * c11 - c01 * x1) / det
        var alphaR = if (abs(det) < 1e-12) 0.0 else (c00 * x1 - c01 * x0) / det
        val segLen = hypot(lx - fx, ly - fy)
        val eps = 1e-6 * segLen
        if (alphaL < eps || alphaR < eps || alphaL > segLen * 2 || alphaR > segLen * 2) {
            alphaL = segLen / 3.0; alphaR = segLen / 3.0
        }
        return doubleArrayOf(fx, fy, fx + t1[0] * alphaL, fy + t1[1] * alphaL,
            lx + t2[0] * alphaR, ly + t2[1] * alphaR, lx, ly)
    }

    private fun maxError(x: DoubleArray, y: DoubleArray, first: Int, last: Int, b: DoubleArray, u: DoubleArray): Pair<Double, Int> {
        var maxD = 0.0; var split = (last - first + 1) / 2 + first
        for (i in first + 1 until last) {
            val t = u[i - first]; val mt = 1 - t
            val a = mt * mt * mt; val c = 3 * mt * mt * t; val d = 3 * mt * t * t; val e = t * t * t
            val dx = a * b[0] + c * b[2] + d * b[4] + e * b[6] - x[i]
            val dy = a * b[1] + c * b[3] + d * b[5] + e * b[7] - y[i]
            val dist = dx * dx + dy * dy
            if (dist >= maxD) { maxD = dist; split = i }
        }
        return maxD to split
    }

    private fun reparameterize(x: DoubleArray, y: DoubleArray, first: Int, last: Int, u: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(u.size)
        for (i in first..last) out[i - first] = newtonRoot(b, x[i], y[i], u[i - first])
        return out
    }

    private fun newtonRoot(b: DoubleArray, px: Double, py: Double, u: Double): Double {
        val mt = 1 - u
        val qx = mt * mt * mt * b[0] + 3 * mt * mt * u * b[2] + 3 * mt * u * u * b[4] + u * u * u * b[6]
        val qy = mt * mt * mt * b[1] + 3 * mt * mt * u * b[3] + 3 * mt * u * u * b[5] + u * u * u * b[7]
        val d1x = 3 * (mt * mt * (b[2] - b[0]) + 2 * mt * u * (b[4] - b[2]) + u * u * (b[6] - b[4]))
        val d1y = 3 * (mt * mt * (b[3] - b[1]) + 2 * mt * u * (b[5] - b[3]) + u * u * (b[7] - b[5]))
        val d2x = 6 * (mt * (b[4] - 2 * b[2] + b[0]) + u * (b[6] - 2 * b[4] + b[2]))
        val d2y = 6 * (mt * (b[5] - 2 * b[3] + b[1]) + u * (b[7] - 2 * b[5] + b[3]))
        val num = (qx - px) * d1x + (qy - py) * d1y
        val den = d1x * d1x + d1y * d1y + (qx - px) * d2x + (qy - py) * d2y
        if (abs(den) < 1e-12) return u
        return (u - num / den).coerceIn(0.0, 1.0)
    }

    /** Zahl mit max. 2 Nachkommastellen, ohne überflüssige Nullen (wie _fmt in der PC-Engine). */
    fun fmt(v: Double): String {
        val r = (v * 100).roundToInt()
        if (r == 0) return "0"
        val a = abs(r)
        val ip = a / 100; val fp = a % 100
        val s = when {
            fp == 0 -> "$ip"
            fp % 10 == 0 -> "$ip.${fp / 10}"
            fp < 10 -> "$ip.0$fp"
            else -> "$ip.$fp"
        }
        return if (r < 0) "-$s" else s
    }

    private class IntArrayBuilder {
        var data = IntArray(1024); var size = 0
        fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
        fun clear() { size = 0 }
        fun toArray(): IntArray = data.copyOf(size)
    }
}
