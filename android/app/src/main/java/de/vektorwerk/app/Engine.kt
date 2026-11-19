package de.vektorwerk.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Vektorwerk-Engine für Android – Port von vektorwerk/app/engine.py (PC-Version).
 * Rasterbild -> druckfertiges, nach Farben getrenntes SVG. Gleiche Schritte, gleiche Parameter;
 * nur das Nachzeichnen übernimmt [Tracer] statt vtracer.
 */
object Engine {

    class Preset(
        val label: String, val hint: String, val maxColors: Int, val stacking: String, val trap: Double,
        val smoothing: Double, val speckle: Int, val corner: Int, val minLineMm: Double,
    )

    val PRESETS = linkedMapOf(
        "siebdruck" to Preset("Siebdruck", "Volltonfarben, eine Ebene pro Sieb, 1 px Überfüllung", 6, "cutout", 1.0, 1.6, 6, 60, 0.5),
        "plotter" to Preset("Flex / Flock (Plotter)", "Wenige saubere Flächen, stark vereinfachte Pfade", 3, "cutout", 0.0, 2.2, 10, 70, 1.0),
        "dtf" to Preset("DTF / Digitaldruck", "Mehr Farben und Details, Ebenen gestapelt ohne Blitzer", 16, "stacked", 0.0, 1.0, 3, 60, 0.3),
        "detail" to Preset("Maximale Details", "Bis 32 Farben, für Illustrationen", 32, "stacked", 0.0, 0.6, 2, 50, 0.3),
    )

    fun presetsJson(): JSONObject {
        val o = JSONObject()
        for ((k, p) in PRESETS) o.put(k, JSONObject()
            .put("label", p.label).put("hint", p.hint).put("max_colors", p.maxColors).put("stacking", p.stacking)
            .put("trap", p.trap).put("smoothing", p.smoothing).put("speckle", p.speckle).put("corner", p.corner)
            .put("min_line_mm", p.minLineMm))
        return o
    }

    private const val WORK_LONG_SIDE = 2000
    private const val PAINT_ERASE = 255

    class Options(
        val preset: String = "siebdruck",
        val colors: Int = 0,
        val smoothing: Double? = null,
        val speckle: Int? = null,
        val background: String = "outside",
        val widthCm: Double = 28.0,
        val palette: List<String>? = null,
        val merge: List<Int>? = null,
        val hidden: List<Int> = emptyList(),
        val underbase: Boolean = false,
        val chokeMm: Double = 0.3,
        val paint: ByteArray? = null,
        val minLineMm: Double? = null,
        val fixThin: Boolean = false,
    )

    private class Resolved(val p: Preset, val smoothing: Double, val speckle: Int)

    class Layer(val hex: String, val share: Double, val paths: List<String>, val nodes: Int,
                val isBg: Boolean = false, val pi: Int = 0, val role: String = "color")

    class Issue(val pi: Int, val hex: String, val thin: Int, val gaps: Int)
    class Check(val minMm: Double, val issues: List<Issue>, val total: Int)

    class Result(val svg: String, val layers: List<Layer>, val width: Int, val height: Int, val background: String?,
                 val warnings: List<String>, val palette: List<String>, val check: Check, val checkPng: ByteArray)

    // ------------------------------------------------------------ Farbhelfer

    private fun toHex(r: Double, g: Double, b: Double): String {
        fun c(v: Double) = v.roundToInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(c(r), c(g), c(b))
    }

    /** RGB-Bytes (n×3) -> CIE-Lab float (n×3), wie cv2.cvtColor auf float32/255. */
    private fun lab(rgb: ByteArray, n: Int): FloatArray {
        val f = FloatArray(n * 3)
        for (i in 0 until n * 3) f[i] = (rgb[i].toInt() and 0xFF) / 255f
        val m = Mat(n, 1, CvType.CV_32FC3); m.put(0, 0, f)
        val o = Mat(); Imgproc.cvtColor(m, o, Imgproc.COLOR_RGB2Lab)
        o.get(0, 0, f); m.release(); o.release()
        return f
    }

    private fun labOfRgb(r: Int, g: Int, b: Int): FloatArray = lab(byteArrayOf(r.toByte(), g.toByte(), b.toByte()), 1)

    private fun labToRgb(l: Float, a: Float, b: Float): DoubleArray {
        val m = Mat(1, 1, CvType.CV_32FC3); m.put(0, 0, floatArrayOf(l, a, b))
        val o = Mat(); Imgproc.cvtColor(m, o, Imgproc.COLOR_Lab2RGB)
        val f = FloatArray(3); o.get(0, 0, f); m.release(); o.release()
        return DoubleArray(3) { (f[it] * 255.0).coerceIn(0.0, 255.0) }
    }

    /** Fast-Weiß -> Weiß, Fast-Schwarz -> Schwarz, fast neutrale Grautöne -> neutral. */
    private fun snap(c: FloatArray): FloatArray {
        val o = c.copyOf()
        if (hypot(o[1].toDouble(), o[2].toDouble()) < 7) {
            o[1] = 0f; o[2] = 0f
            if (o[0] > 85) o[0] = 100f
            if (o[0] < 18) o[0] = 0f
        }
        return o
    }

    private fun hexToRgb(h: String): IntArray {
        val s = h.trim().removePrefix("#")
        require(Regex("[0-9a-fA-F]{6}").matches(s)) { "Ungültige Farbe: $h" }
        return IntArray(3) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    private fun dist(a: FloatArray, ai: Int, b: FloatArray, bi: Int): Float {
        val d0 = a[ai] - b[bi]; val d1 = a[ai + 1] - b[bi + 1]; val d2 = a[ai + 2] - b[bi + 2]
        return sqrt(d0 * d0 + d1 * d1 + d2 * d2)
    }

    // ------------------------------------------------------------ Mat-Helfer

    private fun u8(mask: BooleanArray, w: Int, h: Int): Mat {
        val b = ByteArray(w * h) { if (mask[it]) 1 else 0 }
        return Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, b) }
    }

    private fun bools(m: Mat): BooleanArray {
        val b = ByteArray(m.rows() * m.cols()); m.get(0, 0, b)
        return BooleanArray(b.size) { b[it].toInt() != 0 }
    }

    private fun ellipse(r: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(2.0 * r + 1, 2.0 * r + 1))
    private fun ones(n: Int): Mat = Mat.ones(n, n, CvType.CV_8U)

    private fun morph(mask: BooleanArray, w: Int, h: Int, op: Int, ker: Mat): BooleanArray {
        val m = u8(mask, w, h); val o = Mat()
        Imgproc.morphologyEx(m, o, op, ker)
        val r = bools(o); m.release(); o.release(); return r
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, ker: Mat): BooleanArray {
        val m = u8(mask, w, h); val o = Mat(); Imgproc.dilate(m, o, ker)
        val r = bools(o); m.release(); o.release(); return r
    }

    private fun erodeCount(mask: BooleanArray, w: Int, h: Int, ker: Mat): Int {
        val m = u8(mask, w, h); val o = Mat(); Imgproc.erode(m, o, ker)
        val n = Core.countNonZero(o); m.release(); o.release(); return n
    }

    /** Anzahl Zusammenhangskomponenten (ohne Hintergrund). */
    private fun countComponents(mask: BooleanArray, w: Int, h: Int): Int {
        val m = u8(mask, w, h); val l = Mat()
        val n = Imgproc.connectedComponents(m, l)
        m.release(); l.release(); return n - 1
    }

    private fun dropSmall(mask: BooleanArray, w: Int, h: Int, minArea: Int): BooleanArray {
        val m = u8(mask, w, h); val lab = Mat(); val stats = Mat(); val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(m, lab, stats, cent, 8, CvType.CV_32S)
        val keep = BooleanArray(n)
        val area = IntArray(1)
        for (i in 1 until n) { stats.get(i, Imgproc.CC_STAT_AREA, area); keep[i] = area[0] >= minArea }
        val labels = IntArray(w * h); lab.get(0, 0, labels)
        m.release(); lab.release(); stats.release(); cent.release()
        return BooleanArray(w * h) { keep[labels[it]] }
    }

    // ------------------------------------------------------------ Schritte

    private class Img(var rgb: ByteArray, var alpha: ByteArray, var w: Int, var h: Int)

    private fun load(data: ByteArray): Img {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Kein gültiges Bild." }
        // Riesige Fotos schon beim Dekodieren verkleinern (Arbeitsauflösung ist ohnehin 2000 px)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_LONG_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inSampleSize = sample
        }
        var bmp = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: throw IllegalArgumentException("Kein gültiges Bild.")
        try {
            val exif = ExifInterface(ByteArrayInputStream(data))
            val rot = exif.rotationDegrees; val flip = exif.isFlipped
            if (rot != 0 || flip) {
                val mx = Matrix(); if (flip) mx.postScale(-1f, 1f); mx.postRotate(rot.toFloat())
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            }
        } catch (_: Exception) { /* keine EXIF-Daten */ }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = ByteArray(w * h * 3); val alpha = ByteArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
            alpha[i] = (c ushr 24).toByte()
        }
        return Img(rgb, alpha, w, h)
    }

    private fun matRgb(rgb: ByteArray, w: Int, h: Int) = Mat(h, w, CvType.CV_8UC3).also { it.put(0, 0, rgb) }
    private fun bytesOf(m: Mat): ByteArray = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

    /** Mean-Shift: macht Flächen einfarbig (JPEG-Blöcke, Rauschen), Kanten bleiben. Auf max. 1200 px. */
    private fun flatten(img: Img) {
        val w = img.w; val h = img.h
        val s = min(1.0, 1200.0 / max(w, h))
        val src = matRgb(img.rgb, w, h)
        val small = if (s < 1) Mat().also { Imgproc.resize(src, it, Size((w * s).roundToInt().toDouble(), (h * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA) } else src
        val out = Mat(); Imgproc.pyrMeanShiftFiltering(small, out, 5.0, 22.0)
        if (s < 1) {
            // Nur die Farbkorrektur übernehmen, Kantenschärfe vom Original behalten
            val a = Mat(); val b = Mat(); out.convertTo(a, CvType.CV_32FC3); small.convertTo(b, CvType.CV_32FC3)
            val diff = Mat(); Core.subtract(a, b, diff)
            val big = Mat(); Imgproc.resize(diff, big, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val f = Mat(); src.convertTo(f, CvType.CV_32FC3); Core.add(f, big, f)
            val res = Mat(); f.convertTo(res, CvType.CV_8UC3)
            img.rgb = bytesOf(res)
            listOf(a, b, diff, big, f, res, small).forEach { it.release() }
        } else img.rgb = bytesOf(out)
        out.release(); src.release()
    }

    private fun rescale(img: Img): Double {
        val scale = WORK_LONG_SIDE.toDouble() / max(img.w, img.h)
        if (abs(scale - 1) < 0.05) return 1.0
        val interp = if (scale > 1) Imgproc.INTER_CUBIC else Imgproc.INTER_AREA
        val nw = max(1, (img.w * scale).roundToInt()); val nh = max(1, (img.h * scale).roundToInt())
        val size = Size(nw.toDouble(), nh.toDouble())
        val r = matRgb(img.rgb, img.w, img.h); val ro = Mat(); Imgproc.resize(r, ro, size, 0.0, 0.0, interp)
        val a = Mat(img.h, img.w, CvType.CV_8UC1).also { it.put(0, 0, img.alpha) }; val ao = Mat()
        Imgproc.resize(a, ao, size, 0.0, 0.0, interp)
        img.rgb = bytesOf(ro); img.alpha = bytesOf(ao); img.w = nw; img.h = nh
        listOf(r, ro, a, ao).forEach { it.release() }
        return scale
    }

    private fun denoise(img: Img, upscaled: Boolean) {
        val m = matRgb(img.rgb, img.w, img.h)
        var o = Mat(); Imgproc.bilateralFilter(m, o, 9, 40.0, 7.0)
        if (upscaled) { val o2 = Mat(); Imgproc.bilateralFilter(o, o2, 9, 30.0, 7.0); o.release(); o = o2 }
        img.rgb = bytesOf(o); m.release(); o.release()
    }

    /** Maske (true = Hintergrund) und erkannte Hintergrundfarbe. */
    private fun detectBackground(img: Img, lab: FloatArray, mode: String): Pair<BooleanArray, String?> {
        val w = img.w; val h = img.h; val n = w * h
        val transparent = BooleanArray(n) { (img.alpha[it].toInt() and 0xFF) < 128 }
        if (mode == "keep") return transparent to null
        if (transparent.count { it } > 0.02 * n) return transparent to null

        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { border.add(x); border.add((h - 1) * w + x) }
        for (y in 0 until h) { border.add(y * w); border.add(y * w + w - 1) }
        fun ch(i: Int, c: Int) = img.rgb[i * 3 + c].toInt() and 0xFF
        val keys = border.map { i -> (ch(i, 0) / 16) * 256 + (ch(i, 1) / 16) * 16 + ch(i, 2) / 16 }
        val counts = keys.groupingBy { it }.eachCount()
        val top = counts.maxByOrNull { it.value }!!
        if (top.value.toDouble() / keys.size < 0.5) return transparent to null
        val sel = border.filterIndexed { k, _ -> keys[k] == top.key }
        fun median(c: Int): Double {
            val v = sel.map { ch(it, c) }.sorted(); val m = v.size / 2
            return if (v.size % 2 == 1) v[m].toDouble() else (v[m - 1] + v[m]) / 2.0
        }
        val bg = DoubleArray(3) { median(it) }
        val bgHex = toHex(bg[0], bg[1], bg[2])
        val bgLab = labOfRgb(bg[0].toInt(), bg[1].toInt(), bg[2].toInt())
        val close = BooleanArray(n) { dist(lab, it * 3, bgLab, 0) < 12f }
        if (mode == "all") return BooleanArray(n) { close[it] || transparent[it] } to bgHex

        // "outside": nur zusammenhängende Flächen, die den Rand berühren
        val m = u8(close, w, h); val lm = Mat()
        Imgproc.connectedComponents(m, lm, 4, CvType.CV_32S)
        val labels = IntArray(n); lm.get(0, 0, labels); m.release(); lm.release()
        val edge = HashSet<Int>(); for (i in border) if (labels[i] != 0) edge.add(labels[i])
        return BooleanArray(n) { (labels[it] != 0 && labels[it] in edge) || transparent[it] } to bgHex
    }

    /** Agglomeratives Zusammenlegen ähnlicher Farben (wie _merge). */
    private fun merge(centers0: List<FloatArray>, counts0: List<Double>, kTarget: Int?, deltaE: Double): Pair<MutableList<FloatArray>, MutableList<Double>> {
        val centers = centers0.map { c -> DoubleArray(3) { c[it].toDouble() } }.toMutableList()
        val counts = counts0.toMutableList()
        while (centers.size > 1) {
            if (kTarget != null && centers.size <= kTarget) break
            var best = Double.POSITIVE_INFINITY; var pi = -1; var pj = -1
            for (i in centers.indices) for (j in i + 1 until centers.size) {
                val d = sqrt((0..2).sumOf { (centers[i][it] - centers[j][it]).pow(2) })
                if (kTarget == null) { if (d < deltaE && d < best) { best = d; pi = i; pj = j } }
                else { val cost = d * sqrt(min(counts[i], counts[j])); if (cost < best) { best = cost; pi = i; pj = j } }
            }
            if (pi < 0) break
            val tot = counts[pi] + counts[pj]
            centers[pi] = DoubleArray(3) { (centers[pi][it] * counts[pi] + centers[pj][it] * counts[pj]) / tot }
            counts[pi] = tot
            centers.removeAt(pj); counts.removeAt(pj)
        }
        return centers.map { c -> FloatArray(3) { c[it].toFloat() } }.toMutableList() to counts
    }

    private class Quant(val labels: IntArray, val centers: FloatArray, val k: Int, val warnings: List<String>)

    private fun quantize(lab: FloatArray, w: Int, h: Int, fg: BooleanArray, kReq: Int, kMax: Int): Quant {
        val n = w * h
        val labels = IntArray(n) { -1 }
        val fgIdx = (0 until n).filter { fg[it] }.toIntArray()
        if (fgIdx.isEmpty()) return Quant(labels, FloatArray(0), 0, listOf("Kein Motiv gefunden – nur Hintergrund erkannt."))

        // Farben nur aus "flachen" Pixeln lernen (Kanten sind Mischfarben)
        val lm = Mat(h, w, CvType.CV_32FC3).also { it.put(0, 0, lab) }
        val dil = Mat(); val ero = Mat(); val k3 = ones(3)
        Imgproc.dilate(lm, dil, k3); Imgproc.erode(lm, ero, k3)
        val df = FloatArray(n * 3); val ef = FloatArray(n * 3); dil.get(0, 0, df); ero.get(0, 0, ef)
        val grad = FloatArray(n) { i -> max(df[i * 3] - ef[i * 3], max(df[i * 3 + 1] - ef[i * 3 + 1], df[i * 3 + 2] - ef[i * 3 + 2])) }
        val gm = Mat(h, w, CvType.CV_32FC1).also { it.put(0, 0, grad) }; val gd = Mat(); Imgproc.dilate(gm, gd, k3)
        val gdil = FloatArray(n); gd.get(0, 0, gdil)
        listOf(lm, dil, ero, gm, gd, k3).forEach { it.release() }
        val flatIdx = fgIdx.filter { gdil[it] < 10f }.toIntArray()
        val src = if (flatIdx.size > 0.2 * fgIdx.size) flatIdx else fgIdx

        val rnd = java.util.Random(0)
        val ns = min(src.size, 150_000)
        val pool = src.copyOf()
        for (i in 0 until ns) { val j = i + rnd.nextInt(pool.size - i); val t = pool[i]; pool[i] = pool[j]; pool[j] = t }
        val sample = FloatArray(ns * 3)
        for (i in 0 until ns) { val p = pool[i] * 3; sample[i * 3] = lab[p]; sample[i * 3 + 1] = lab[p + 1]; sample[i * 3 + 2] = lab[p + 2] }

        val auto = kReq <= 0
        val k0 = minOf(if (auto) max(kMax * 2, 8) else kReq, 40, ns)
        val data = Mat(ns, 3, CvType.CV_32F).also { it.put(0, 0, sample) }
        val best = Mat(); val cm = Mat()
        Core.setRNGSeed(0)
        Core.kmeans(data, k0, best, TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 40, 0.2), 3, Core.KMEANS_PP_CENTERS, cm)
        val lbl = IntArray(ns); best.get(0, 0, lbl)
        val cf = FloatArray(k0 * 3); cm.get(0, 0, cf)
        listOf(data, best, cm).forEach { it.release() }
        val cnt = DoubleArray(k0); for (l in lbl) cnt[l] += 1.0
        var centers = (0 until k0).map { j -> floatArrayOf(cf[j * 3], cf[j * 3 + 1], cf[j * 3 + 2]) }.toMutableList()
        var counts = cnt.toMutableList()
        val warnings = ArrayList<String>()

        if (auto) {
            merge(centers, counts, null, 14.0).let { centers = it.first; counts = it.second }
            val tot = counts.sum()
            val keep = counts.indices.filter { counts[it] / tot >= 0.004 }
            if (keep.isNotEmpty()) { centers = keep.map { centers[it] }.toMutableList(); counts = keep.map { counts[it] }.toMutableList() }
            if (centers.size > kMax) {
                warnings.add("Das Motiv hat mehr als $kMax deutlich verschiedene Farben (Verläufe oder Foto?). " +
                    "Es wurde auf $kMax reduziert. Für Siebdruck/Plotter das Motiv ggf. vereinfachen oder „DTF“ wählen.")
                merge(centers, counts, kMax, 0.0).let { centers = it.first; counts = it.second }
            }
        }

        var k = centers.size
        var cflat = FloatArray(k * 3).also { a -> centers.forEachIndexed { j, c -> c.copyInto(a, j * 3) } }
        // Alle Vordergrundpixel dem nächsten Zentrum zuordnen, Zentren als echten Mittelwert neu berechnen
        val sum = DoubleArray(k * 3); val num = IntArray(k)
        for (i in fgIdx) {
            var bj = 0; var bd = Float.MAX_VALUE
            for (j in 0 until k) { val d = dist(lab, i * 3, cflat, j * 3); if (d < bd) { bd = d; bj = j } }
            labels[i] = bj; num[bj]++
            sum[bj * 3] += lab[i * 3].toDouble(); sum[bj * 3 + 1] += lab[i * 3 + 1].toDouble(); sum[bj * 3 + 2] += lab[i * 3 + 2].toDouble()
        }
        for (j in 0 until k) if (num[j] > 0) for (c in 0..2) cflat[j * 3 + c] = (sum[j * 3 + c] / num[j]).toFloat()

        if (auto && k > 2) {
            val r = dropTransitionColors(labels, cflat, k, lab, w, h)
            cflat = r.first; k = r.second
        }
        return Quant(labels, cflat, k, warnings)
    }

    /** Entfernt Mischfarben, die nur als schmaler Saum zwischen zwei anderen Farben vorkommen. */
    private fun dropTransitionColors(labels: IntArray, centers: FloatArray, k: Int, lab: FloatArray, w: Int, h: Int): Pair<FloatArray, Int> {
        val areas = DoubleArray(k); for (l in labels) if (l >= 0) areas[l] += 1.0
        val total = areas.sum()
        val r = max(2, (max(w, h) / 700.0).roundToInt())
        val ker = ellipse(r)
        val drop = ArrayList<Int>()
        fun c(j: Int, i: Int) = centers[j * 3 + i].toDouble()
        for (cc in 0 until k) {
            if (areas[cc] / total > 0.08) continue
            var mixed = false
            loop@ for (a in 0 until k) for (b in a + 1 until k) {
                if (cc == a || cc == b || areas[a] < areas[cc] || areas[b] < areas[cc]) continue
                val ab = DoubleArray(3) { c(b, it) - c(a, it) }
                val dot = (0..2).sumOf { (c(cc, it) - c(a, it)) * ab[it] }
                val t = dot / max(1e-6, (0..2).sumOf { ab[it] * ab[it] })
                val off = sqrt((0..2).sumOf { (c(a, it) + t * ab[it] - c(cc, it)).pow(2) })
                if (t > 0.1 && t < 0.9 && off < 9.0) { mixed = true; break@loop }
            }
            if (!mixed) continue
            val m = BooleanArray(labels.size) { labels[it] == cc }
            if (erodeCount(m, w, h, ker) < 0.25 * m.count { it }) drop.add(cc)
        }
        ker.release()
        if (drop.isEmpty()) return centers to k
        val keep = (0 until k).filter { it !in drop }
        val nc = FloatArray(keep.size * 3); keep.forEachIndexed { ni, old -> for (i in 0..2) nc[ni * 3 + i] = centers[old * 3 + i] }
        val remap = IntArray(k) { -1 }; keep.forEachIndexed { ni, old -> remap[old] = ni }
        for (p in labels.indices) {
            val l = labels[p]; if (l < 0) continue
            if (remap[l] >= 0) labels[p] = remap[l]
            else {
                var bj = 0; var bd = Float.MAX_VALUE
                for (j in keep.indices) { val d = dist(lab, p * 3, nc, j * 3); if (d < bd) { bd = d; bj = j } }
                labels[p] = bj
            }
        }
        return nc to keep.size
    }

    /** Weichgezeichnete Masken je Farbe -> argmax. Glättet Treppen, ohne neue Farben zu erzeugen. */
    private fun smoothLabels(labels: IntArray, k: Int, sigma: Double, w: Int, h: Int): IntArray {
        if (sigma <= 0) return labels
        val n = w * h
        val best = FloatArray(n) { -1f }; val out = IntArray(n) { -1 }
        val f = FloatArray(n); val src = Mat(h, w, CvType.CV_32FC1); val dst = Mat()
        for (j in -1 until k) {
            for (i in 0 until n) f[i] = if (labels[i] == j) 1f else 0f
            src.put(0, 0, f); Imgproc.GaussianBlur(src, dst, Size(0.0, 0.0), sigma); dst.get(0, 0, f)
            for (i in 0 until n) if (f[i] > best[i]) { best[i] = f[i]; out[i] = j }
        }
        src.release(); dst.release()
        return out
    }

    private fun traceMask(mask: BooleanArray, w: Int, h: Int, r: Resolved, scale: Double): Tracer.Traced {
        val speckle = max(1, (r.speckle * max(1.0, sqrt(scale))).roundToInt())
        return Tracer.trace(mask, w, h, Tracer.Params(speckle, r.p.corner.toDouble()))
    }

    // ------------------------------------------------------------ Vorverarbeitung (gecacht)

    private class Prepared(val lab: FloatArray, val w: Int, val h: Int, val scale: Double,
                           val bg: BooleanArray, val bgHex: String?)

    private val cache = object : LinkedHashMap<String, Prepared>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prepared>?) = size > 3
    }

    private fun prepare(data: ByteArray, flatten: Boolean, background: String): Prepared {
        val hash = MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }
        val key = "$hash|$flatten|$background"
        synchronized(cache) { cache[key]?.let { return it } }
        val img = load(data)
        if (flatten) flatten(img)
        val scale = rescale(img)
        denoise(img, scale > 1.05)
        val lab = lab(img.rgb, img.w * img.h)
        val (bg, bgHex) = detectBackground(img, lab, background)
        val p = Prepared(lab, img.w, img.h, scale, bg, bgHex)
        synchronized(cache) { cache[key] = p }
        return p
    }

    // ------------------------------------------------------------ Palette, Pinsel, Druck-Check

    private fun assignPalette(pr: Prepared, fg: BooleanArray, palette: List<String>, merge: List<Int>?): Triple<IntArray, FloatArray, IntArray> {
        val k = palette.size
        val centers = FloatArray(k * 3)
        palette.forEachIndexed { j, p -> val c = hexToRgb(p); labOfRgb(c[0], c[1], c[2]).copyInto(centers, j * 3) }
        val target = IntArray(k) { it }
        if (merge != null) {
            require(merge.size == k) { "merge muss genauso lang sein wie palette" }
            merge.forEachIndexed { i, t -> require(t in 0 until k) { "Ungültiger merge-Index" }; target[i] = t }
            repeat(k) { for (i in 0 until k) target[i] = target[target[i]] } // Ketten auflösen (a->b->c)
        }
        val labels = IntArray(pr.w * pr.h) { -1 }
        for (i in labels.indices) {
            if (!fg[i]) continue
            var bj = 0; var bd = Float.MAX_VALUE
            for (j in 0 until k) { val d = dist(pr.lab, i * 3, centers, j * 3); if (d < bd) { bd = d; bj = j } }
            labels[i] = target[bj]
        }
        return Triple(labels, centers, target)
    }

    /** Pinsel-Korrekturen übernehmen. PNG: Rotkanal = Code, Alpha > 0 = bemalt. */
    private fun applyPaint(labels: IntArray, w: Int, h: Int, png: ByteArray, target: IntArray) {
        val opts = BitmapFactory.Options().apply { inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bmp = BitmapFactory.decodeByteArray(png, 0, png.size, opts) ?: return
        if (bmp.width != w || bmp.height != h) bmp = Bitmap.createScaledBitmap(bmp, w, h, false)
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            if ((c ushr 24) == 0) continue
            val code = (c shr 16) and 0xFF
            if (code == PAINT_ERASE) labels[i] = -1
            else if (code in 1..target.size) labels[i] = target[code - 1]
        }
    }

    /** Teile einer Maske, die schmaler als 2r sind (verschwinden beim Öffnen mit Radius r). */
    private fun thinParts(mask: BooleanArray, w: Int, h: Int, r: Int, ker: Mat): BooleanArray {
        val open = morph(mask, w, h, Imgproc.MORPH_OPEN, ker)
        var thin = BooleanArray(mask.size) { mask[it] && !open[it] }
        val o3 = ones(3); thin = morph(thin, w, h, Imgproc.MORPH_OPEN, o3); o3.release() // Eckenkrümel weg
        return dropSmall(thin, w, h, max(6, r * r))
    }

    private fun printCheck(labels: IntArray, k: Int, hidden: Set<Int>, w: Int, h: Int, pxPerMm: Double, minMm: Double,
                           outHex: List<String>): Pair<Check, ByteArray> {
        val n = w * h
        val r = max(1, (minMm * pxPerMm / 2).roundToInt())
        val ker = ellipse(r)
        val ov = ByteArray(n * 4)
        val issues = ArrayList<Issue>()
        val bgMask = BooleanArray(n) { labels[it] == -1 }
        for (j in 0 until k) {
            if (j in hidden) continue
            val mask = BooleanArray(n) { labels[it] == j }
            if (mask.none { it }) continue
            val thin = thinParts(mask, w, h, r, ker)
            val closed = morph(mask, w, h, Imgproc.MORPH_CLOSE, ker)
            var gap = BooleanArray(n) { closed[it] && !mask[it] && bgMask[it] }
            val o3 = ones(3); gap = morph(gap, w, h, Imgproc.MORPH_OPEN, o3); o3.release()
            gap = dropSmall(gap, w, h, max(6, r * r))
            val nThin = countComponents(thin, w, h); val nGap = countComponents(gap, w, h)
            if (nThin > 0 || nGap > 0) issues.add(Issue(j, outHex[j], nThin, nGap))
            for (i in 0 until n) {
                if (thin[i]) { ov[i * 4] = 255.toByte(); ov[i * 4 + 1] = 40; ov[i * 4 + 2] = 40; ov[i * 4 + 3] = 230.toByte() }
                if (gap[i]) { ov[i * 4] = 20; ov[i * 4 + 1] = 120; ov[i * 4 + 2] = 255.toByte(); ov[i * 4 + 3] = 210.toByte() }
            }
        }
        ker.release()
        val total = issues.sumOf { it.thin + it.gaps }
        val check = Check(minMm, issues, total)
        if (total == 0) return check to ByteArray(0)
        // Markierungen etwas dicker, halbe Auflösung, als PNG (OpenCV erwartet BGRA)
        val m = Mat(h, w, CvType.CV_8UC4).also { it.put(0, 0, ov) }
        val d = Mat(); val k5 = ones(5); Imgproc.dilate(m, d, k5)
        val s = Mat(); Imgproc.resize(d, s, Size(max(1, w / 2).toDouble(), max(1, h / 2).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bgra = Mat(); Imgproc.cvtColor(s, bgra, Imgproc.COLOR_RGBA2BGRA)
        val buf = MatOfByte(); Imgcodecs.imencode(".png", bgra, buf)
        val png = buf.toArray()
        listOf(m, d, k5, s, bgra, buf).forEach { it.release() }
        return check to png
    }

    /** Verdickt zu dünne Linien auf die Mindeststärke (wächst in Hintergrund und Nachbarfarben). */
    private fun thicken(labels: IntArray, k: Int, hidden: Set<Int>, w: Int, h: Int, pxPerMm: Double, minMm: Double): IntArray {
        val r = max(1, (minMm * pxPerMm / 2).roundToInt())
        val ker = ellipse(r)
        val out = labels.copyOf()
        val areas = IntArray(k); for (l in labels) if (l >= 0) areas[l]++
        for (j in (0 until k).sortedByDescending { areas[it] }) { // große Flächen zuerst, feine Linien gewinnen
            if (j in hidden || areas[j] == 0) continue
            val thin = thinParts(BooleanArray(labels.size) { labels[it] == j }, w, h, r, ker)
            if (thin.any { it }) { val grow = dilate(thin, w, h, ker); for (i in grow.indices) if (grow[i]) out[i] = j }
        }
        ker.release()
        return out
    }

    // ------------------------------------------------------------ Haupt-API

    fun vectorize(data: ByteArray, o: Options): Result {
        val p = PRESETS[o.preset] ?: PRESETS.getValue("siebdruck")
        val res = Resolved(p, o.smoothing ?: p.smoothing, o.speckle ?: p.speckle)
        val pr = prepare(data, p.maxColors <= 16, o.background)
        val w = pr.w; val h = pr.h; val n = w * h
        val fg = BooleanArray(n) { !pr.bg[it] }

        var labels: IntArray; val centers: FloatArray; val k: Int
        val warnings = ArrayList<String>(); val outHex: List<String>
        if (!o.palette.isNullOrEmpty()) {
            val (l, c, target) = assignPalette(pr, fg, o.palette, o.merge)
            labels = l; centers = c; k = o.palette.size
            outHex = o.palette.map { (if (it.startsWith("#")) it else "#$it").uppercase() }
            o.paint?.let { applyPaint(labels, w, h, it, target) }
        } else {
            val q = quantize(pr.lab, w, h, fg, o.colors, p.maxColors)
            labels = q.labels; centers = q.centers; k = q.k; warnings.addAll(q.warnings)
            outHex = (0 until k).map { j ->
                val s = snap(floatArrayOf(centers[j * 3], centers[j * 3 + 1], centers[j * 3 + 2]))
                val rgb = labToRgb(s[0], s[1], s[2]); toHex(rgb[0], rgb[1], rgb[2])
            }
        }
        labels = smoothLabels(labels, k, res.smoothing * max(1.0, sqrt(pr.scale)), w, h)

        val pxPerMm = w / (o.widthCm * 10.0)
        val minMm = o.minLineMm ?: p.minLineMm
        val hidden = o.hidden.toSet()
        if (o.fixThin) labels = thicken(labels, k, hidden, w, h, pxPerMm, minMm)
        val (check, checkPng) = printCheck(labels, k, hidden, w, h, pxPerMm, minMm, outHex)

        val bgLab = pr.bgHex?.let { val c = hexToRgb(it); labOfRgb(c[0], c[1], c[2]) }
        val areas = IntArray(k); for (l in labels) if (l >= 0) areas[l]++
        val fgTotal = max(1.0, areas.sum().toDouble())
        val order = (0 until k).sortedByDescending { areas[it] } // größte Fläche unten

        val trapPx = p.trap * max(1.0, pr.scale)
        val trapKer = if (trapPx > 0) ellipse(max(1, trapPx.roundToInt())) else null

        val layers = ArrayList<Layer>()
        for ((pos, j) in order.withIndex()) {
            if (areas[j] == 0) continue
            val mask: BooleanArray = if (p.stacking == "stacked") {
                // Untere Ebene reicht unter alle darüberliegenden -> keine Blitzer
                val upper = BooleanArray(k); for (q in order.subList(pos, order.size)) upper[q] = true
                BooleanArray(n) { labels[it] >= 0 && upper[labels[it]] }
            } else {
                val m = BooleanArray(n) { labels[it] == j }
                if (trapKer != null && pos > 0) {
                    // Überfüllung: obere Farben wachsen leicht in die darunterliegenden hinein
                    val g = dilate(m, w, h, trapKer); BooleanArray(n) { g[it] && labels[it] >= 0 }
                } else m
            }
            val t = traceMask(mask, w, h, res, pr.scale)
            if (t.paths.isNotEmpty()) {
                val isBg = bgLab != null && dist(centers, j * 3, bgLab, 0) < 16f
                layers.add(Layer(outHex[j], round1(100.0 * areas[j] / fgTotal), t.paths, t.nodes, isBg, j))
            }
        }
        trapKer?.release()

        if (o.underbase) {
            val visible = BooleanArray(k) { areas[it] > 0 && it !in hidden }
            var mask = BooleanArray(n) { labels[it] >= 0 && visible[labels[it]] }
            val r = (o.chokeMm / 10.0 / o.widthCm * w).roundToInt()
            if (r >= 1) {
                val ker = ellipse(r); val m = u8(mask, w, h); val e = Mat(); Imgproc.erode(m, e, ker)
                mask = bools(e); listOf(ker, m, e).forEach { it.release() }
            }
            val t = traceMask(mask, w, h, res, pr.scale)
            if (t.paths.isNotEmpty()) {
                layers.add(0, Layer("#FFFFFF", round1(100.0 * mask.count { it } / fgTotal), t.paths, t.nodes, pi = -1, role = "underbase"))
            }
        }

        val totalNodes = layers.sumOf { it.nodes }
        if (totalNodes > 60_000) warnings.add("Sehr viele Ankerpunkte (${"%,d".format(java.util.Locale.GERMANY, totalNodes)}). Glättung oder Fleckenfilter erhöhen.")

        return Result(buildSvg(layers, w, h, o.widthCm), layers, w, h, pr.bgHex, warnings, outHex, check, checkPng)
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    fun buildSvg(layers: List<Layer>, w: Int, h: Int, widthCm: Double): String {
        val heightCm = widthCm * h / w
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:inkscape=\"http://www.inkscape.org/namespaces/inkscape\" ")
        sb.append("width=\"%.2fcm\" height=\"%.2fcm\" viewBox=\"0 0 %d %d\">\n".format(java.util.Locale.ROOT, widthCm, heightCm, w, h))
        sb.append("<!-- Erstellt mit Vektorwerk -->\n")
        var n = 0
        for (l in layers) {
            val (gid, name) = if (l.role == "underbase") "unterleger" to "Unterleger Weiß" else { n++; "farbe-$n" to "Farbe $n ${l.hex}" }
            sb.append("<g id=\"$gid\" inkscape:groupmode=\"layer\" inkscape:label=\"$name\" data-color=\"${l.hex}\" ")
            sb.append("data-pi=\"${l.pi}\" data-role=\"${l.role}\" fill=\"${l.hex}\" fill-rule=\"evenodd\">\n")
            for (d in l.paths) sb.append("<path d=\"").append(d).append("\"/>\n")
            sb.append("</g>\n")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    fun resultJson(r: Result): JSONObject {
        val layers = JSONArray()
        for (l in r.layers) layers.put(JSONObject().put("hex", l.hex).put("share", l.share).put("paths", l.paths.size)
            .put("nodes", l.nodes).put("is_bg", l.isBg).put("pi", l.pi).put("role", l.role))
        val issues = JSONArray()
        for (i in r.check.issues) issues.put(JSONObject().put("pi", i.pi).put("hex", i.hex).put("thin", i.thin).put("gaps", i.gaps))
        return JSONObject()
            .put("svg", r.svg).put("width", r.width).put("height", r.height)
            .put("background", r.background ?: JSONObject.NULL)
            .put("warnings", JSONArray(r.warnings)).put("palette", JSONArray(r.palette))
            .put("check", JSONObject().put("min_mm", r.check.minMm).put("issues", issues).put("total", r.check.total))
            .put("check_png", if (r.checkPng.isNotEmpty()) "data:image/png;base64," +
                android.util.Base64.encodeToString(r.checkPng, android.util.Base64.NO_WRAP) else JSONObject.NULL)
            .put("layers", layers)
    }
}
