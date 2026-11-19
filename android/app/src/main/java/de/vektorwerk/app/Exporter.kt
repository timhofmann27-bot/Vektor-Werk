package de.vektorwerk.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Xml
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Export – Port von vektorwerk/app/export.py: SVG, PDF, EPS, PNG (300 dpi), Siebdruck-Filme und ZIP.
 * Liest das (in der Oberfläche bearbeitete) SVG wieder ein; PDF und EPS bleiben echte Vektoren.
 */
object Exporter {

    val SUFFIX = linkedMapOf("svg" to ".svg", "pdf" to ".pdf", "eps" to ".eps", "png" to ".png", "films" to "_Filme.pdf")
    val MIME = mapOf("svg" to "image/svg+xml", "pdf" to "application/pdf", "eps" to "application/postscript",
        "png" to "image/png", "films" to "application/pdf")

    private const val MARGIN = 25.0  // mm Rand um das Motiv
    private const val MARK_GAP = 9.0 // mm Abstand Passkreuz <-> Motiv
    private const val MARK_R = 4.0   // mm Radius Passkreuz
    private const val FOOT = 12.0    // mm Platz für die Fußzeile
    private const val PT_PER_MM = 72.0 / 25.4

    // ------------------------------------------------------------ SVG einlesen

    /** Pfadsegmente absolut: 'M' x y, 'L' x y, 'C' x1 y1 x2 y2 x y, 'Z'. */
    class Seg(val op: Char, val v: DoubleArray)
    class Group(val color: Int, val hex: String, val pantone: String?, val role: String, val paths: List<List<Seg>>,
                val strokeW: Double = 0.0)  // > 0: Linie statt Fläche (Schnittlinie „CutContour“)
    class Doc(val vbX: Double, val vbY: Double, val vbW: Double, val vbH: Double, val groups: List<Group>) {
        /** Ohne Schnittlinie – für PNG und Siebdruck-Filme (die Linie wird nicht gedruckt). */
        fun printable() = Doc(vbX, vbY, vbW, vbH, groups.filter { it.role != "cut" })
    }

    fun parse(svg: String): Doc {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(svg.reader())
        var vb = doubleArrayOf(0.0, 0.0, 100.0, 100.0)
        val groups = ArrayList<Group>()
        var cur: MutableList<List<Seg>>? = null
        var hex = "#000000"; var pms: String? = null; var role = "color"; var sw = 0.0
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "svg" -> if (groups.isEmpty() && cur == null) p.getAttributeValue(null, "viewBox")?.let { s ->
                        val v = s.trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
                        if (v.size == 4) vb = v.toDoubleArray()
                    }
                    "g" -> p.getAttributeValue(null, "data-color")?.let { c ->
                        hex = (p.getAttributeValue(null, "fill") ?: c).uppercase()
                        pms = p.getAttributeValue(null, "data-pantone")
                        role = p.getAttributeValue(null, "data-role") ?: "color"
                        sw = 0.0
                        if (role == "cut") {
                            hex = c.uppercase()
                            sw = p.getAttributeValue(null, "stroke-width")?.toDoubleOrNull() ?: 0.0
                            if (sw <= 0) sw = 0.01
                        }
                        cur = ArrayList()
                    }
                    "path" -> cur?.let { list -> p.getAttributeValue(null, "d")?.let { list.add(parsePath(it)) } }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "g" && cur != null) {
                val c = try { Color.parseColor(hex) } catch (_: Exception) { Color.BLACK }
                groups.add(Group(c, hex, pms, role, cur!!, sw)); cur = null
            }
        }
        return Doc(vb[0], vb[1], vb[2], vb[3], groups)
    }

    private val TOK = Regex("[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parsePath(d: String): List<Seg> {
        val t = TOK.findAll(d).map { it.value }.toList()
        val out = ArrayList<Seg>()
        var i = 0; var cmd = 'M'; var x = 0.0; var y = 0.0; var sx = 0.0; var sy = 0.0
        fun num(): Double = t[i++].toDouble()
        while (i < t.size) {
            if (t[i][0].isLetter()) { cmd = t[i][0]; i++ }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'Z' -> { out.add(Seg('Z', DoubleArray(0))); x = sx; y = sy; continue }
                'M' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }
                    x = nx; y = ny; sx = x; sy = y; out.add(Seg('M', doubleArrayOf(x, y))); cmd = if (rel) 'l' else 'L' }
                'L' -> { var nx = num(); var ny = num(); if (rel) { nx += x; ny += y }; x = nx; y = ny; out.add(Seg('L', doubleArrayOf(x, y))) }
                'H' -> { var nx = num(); if (rel) nx += x; x = nx; out.add(Seg('L', doubleArrayOf(x, y))) }
                'V' -> { var ny = num(); if (rel) ny += y; y = ny; out.add(Seg('L', doubleArrayOf(x, y))) }
                'C' -> { val v = DoubleArray(6) { num() }
                    if (rel) for (k in 0 until 6) v[k] += if (k % 2 == 0) x else y
                    x = v[4]; y = v[5]; out.add(Seg('C', v)) }
                else -> i++
            }
        }
        return out
    }

    private fun toPath(segs: List<Seg>): Path {
        val p = Path(); p.fillType = Path.FillType.EVEN_ODD
        for (s in segs) when (s.op) {
            'M' -> p.moveTo(s.v[0].toFloat(), s.v[1].toFloat())
            'L' -> p.lineTo(s.v[0].toFloat(), s.v[1].toFloat())
            'C' -> p.cubicTo(s.v[0].toFloat(), s.v[1].toFloat(), s.v[2].toFloat(), s.v[3].toFloat(), s.v[4].toFloat(), s.v[5].toFloat())
            'Z' -> p.close()
        }
        return p
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    /** Zeichnet das Motiv in das Rechteck (x, y, w, h) der aktuellen Canvas-Einheit. */
    private fun drawDoc(c: Canvas, doc: Doc, x: Double, y: Double, w: Double, h: Double, groups: List<Group> = doc.groups,
                        override: Int? = null) {
        c.save()
        c.translate(x.toFloat(), y.toFloat())
        c.scale((w / doc.vbW).toFloat(), (h / doc.vbH).toFloat())
        c.translate(-doc.vbX.toFloat(), -doc.vbY.toFloat())
        for (g in groups) {
            val paint = if (g.strokeW > 0) Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = g.strokeW.toFloat(); color = override ?: g.color
                strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
            } else fillPaint(override ?: g.color)
            for (segs in g.paths) c.drawPath(toPath(segs), paint)
        }
        c.restore()
    }

    // ------------------------------------------------------------ Formate

    fun convert(svg: String, fmt: String, widthCm: Double, name: String): ByteArray = when (fmt) {
        "svg" -> svg.toByteArray()
        "pdf" -> pdf(parse(svg), widthCm)
        "eps" -> eps(parse(svg), widthCm)
        "png" -> png(parse(svg).printable(), widthCm)
        "films" -> films(parse(svg).printable(), name, widthCm)
        else -> throw IllegalArgumentException("Unbekanntes Format.")
    }

    private fun pdf(doc: Doc, widthCm: Double): ByteArray {
        val wPt = widthCm / 2.54 * 72; val hPt = wPt * doc.vbH / doc.vbW
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(ceil(wPt).toInt(), ceil(hPt).toInt(), 1).create())
        drawDoc(page.canvas, doc, 0.0, 0.0, wPt, hPt)
        pdf.finishPage(page)
        return ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()
    }

    private fun eps(doc: Doc, widthCm: Double): ByteArray {
        val wPt = widthCm / 2.54 * 72; val hPt = wPt * doc.vbH / doc.vbW
        val s = wPt / doc.vbW
        fun f(v: Double) = Tracer.fmt(v)
        val sb = StringBuilder()
        sb.append("%!PS-Adobe-3.0 EPSF-3.0\n%%BoundingBox: 0 0 ${ceil(wPt).toInt()} ${ceil(hPt).toInt()}\n")
        sb.append("%%HiResBoundingBox: 0 0 ${f(wPt)} ${f(hPt)}\n%%Creator: Vektorwerk\n%%Pages: 1\n%%EndComments\n")
        sb.append("gsave\n0 ${f(hPt)} translate ${"%.6f".format(Locale.ROOT, s)} ${"%.6f".format(Locale.ROOT, -s)} scale ${f(-doc.vbX)} ${f(-doc.vbY)} translate\n")
        for (g in doc.groups) {
            val r = Color.red(g.color) / 255.0; val gr = Color.green(g.color) / 255.0; val b = Color.blue(g.color) / 255.0
            sb.append("%.4f %.4f %.4f setrgbcolor\n".format(Locale.ROOT, r, gr, b))
            if (g.strokeW > 0) sb.append("${f(g.strokeW)} setlinewidth 1 setlinejoin\n")
            for (segs in g.paths) {
                sb.append("newpath\n")
                for (sg in segs) when (sg.op) {
                    'M' -> sb.append(f(sg.v[0])).append(' ').append(f(sg.v[1])).append(" moveto\n")
                    'L' -> sb.append(f(sg.v[0])).append(' ').append(f(sg.v[1])).append(" lineto\n")
                    'C' -> { for (k in 0 until 6) sb.append(f(sg.v[k])).append(' '); sb.append("curveto\n") }
                    'Z' -> sb.append("closepath\n")
                }
                sb.append(if (g.strokeW > 0) "stroke\n" else "eofill\n")
            }
        }
        sb.append("grestore\nshowpage\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** PNG mit 300 dpi in Druckbreite, transparent (auf Handy-Speicher begrenzt). */
    private fun png(doc: Doc, widthCm: Double): ByteArray {
        var pw = (widthCm / 2.54 * 300).roundToInt()
        var ph = (pw * doc.vbH / doc.vbW).roundToInt()
        val limit = min(12000.0 / max(pw, ph), sqrt(36e6 / (pw.toDouble() * ph)))
        if (limit < 1) { pw = (pw * limit).toInt(); ph = (ph * limit).toInt() }
        val bmp = Bitmap.createBitmap(max(1, pw), max(1, ph), Bitmap.Config.ARGB_8888)
        drawDoc(Canvas(bmp), doc, 0.0, 0.0, pw.toDouble(), ph.toDouble())
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle()
        return out.toByteArray()
    }

    // ------------------------------------------------------------ Siebdruck-Filme

    private fun labels(groups: List<Group>): List<Pair<String, String>> {
        var n = 0
        return groups.map { g ->
            if (g.role == "underbase") "Unterleger Weiß" to g.hex
            else if (g.role == "bg") "Hintergrund" to g.hex
            else if (g.role == "border") "Rand" to g.hex
            else { n++; "Farbe $n" to (if (!g.pantone.isNullOrEmpty()) "${g.pantone} (${g.hex})" else g.hex) }
        }
    }

    /** Seite 1: farbige Übersicht mit Druckreihenfolge. Danach ein schwarzer Film pro Ebene. */
    private fun films(doc: Doc, name: String, widthCm: Double): ByteArray {
        require(doc.groups.isNotEmpty()) { "Keine Farbebenen im SVG." }
        val w = widthCm * 10.0; val h = w * doc.vbH / doc.vbW
        val size = "%.1f × %.1f cm".format(Locale.ROOT, w / 10, h / 10).replace(".", ",")
        val today = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date())
        val labs = labels(doc.groups); val n = doc.groups.size
        val pdf = PdfDocument()
        val rowH = 6.5

        fun page(no: Int, extraH: Double, footer: String, body: (Canvas) -> Unit) {
            val pw = w + 2 * MARGIN; val ph = h + 2 * MARGIN + extraH + FOOT
            val pg = pdf.startPage(PdfDocument.PageInfo.Builder(ceil(pw * PT_PER_MM).toInt(), ceil(ph * PT_PER_MM).toInt(), no).create())
            val c = pg.canvas
            c.scale(PT_PER_MM.toFloat(), PT_PER_MM.toFloat()) // ab hier alles in mm
            c.drawColor(Color.WHITE)
            body(c)
            marks(c, w, h)
            c.drawText(footer, MARGIN.toFloat(), (ph - FOOT / 2).toFloat(), text(3.4f))
            pdf.finishPage(pg)
        }

        page(1, n * rowH + 4, "$name · Übersicht · $n Filme · $size · $today") { c ->
            drawDoc(c, doc, MARGIN, MARGIN, w, h)
            c.drawText("Druckreihenfolge", MARGIN.toFloat(), (MARGIN + h + MARGIN - 4).toFloat(), text(3.6f, bold = true))
            doc.groups.forEachIndexed { i, g ->
                val y = (MARGIN + h + MARGIN + 2 + i * rowH).toFloat(); val x = MARGIN.toFloat()
                c.drawRect(x, y, x + 4.5f, y + 4.5f, fillPaint(g.color))
                c.drawRect(x, y, x + 4.5f, y + 4.5f, stroke(0.2f))
                c.drawText("${i + 1}. ${labs[i].first} – ${labs[i].second}", x + 7f, y + 3.6f, text(3.4f))
            }
        }
        doc.groups.forEachIndexed { i, g ->
            page(i + 2, 0.0, "$name · Film ${i + 1}/$n · ${labs[i].first} · ${labs[i].second} · $size · $today") { c ->
                drawDoc(c, doc, MARGIN, MARGIN, w, h, listOf(g), override = Color.BLACK)
            }
        }
        return ByteArrayOutputStream().also { pdf.writeTo(it); pdf.close() }.toByteArray()
    }

    private fun text(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textSize = size; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun stroke(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; color = Color.BLACK }

    /** Passkreuze mittig an allen vier Seiten + Eckmarken (alles in mm). */
    private fun marks(c: Canvas, w: Double, h: Double) {
        val p = stroke(0.25f)
        val ox = MARGIN; val oy = MARGIN; val cx = ox + w / 2; val cy = oy + h / 2; val r = MARK_R
        for ((x, y) in listOf(cx to oy - MARK_GAP, cx to oy + h + MARK_GAP, ox - MARK_GAP to cy, ox + w + MARK_GAP to cy)) {
            c.drawCircle(x.toFloat(), y.toFloat(), (r * 0.6).toFloat(), p)
            c.drawLine((x - r).toFloat(), y.toFloat(), (x + r).toFloat(), y.toFloat(), p)
            c.drawLine(x.toFloat(), (y - r).toFloat(), x.toFloat(), (y + r).toFloat(), p)
        }
        val l = 6.0
        for ((pt, s) in listOf((ox to oy) to (-1 to -1), (ox + w to oy) to (1 to -1), (ox to oy + h) to (-1 to 1), (ox + w to oy + h) to (1 to 1))) {
            val (x, y) = pt; val (sx, sy) = s
            c.drawLine((x + sx * 3).toFloat(), y.toFloat(), (x + sx * (3 + l)).toFloat(), y.toFloat(), p)
            c.drawLine(x.toFloat(), (y + sy * 3).toFloat(), x.toFloat(), (y + sy * (3 + l)).toFloat(), p)
        }
    }

    // ------------------------------------------------------------ ZIP

    private val SAFE = Regex("[^\\p{L}\\p{N}._ -]+")
    fun safeName(name: String): String = SAFE.replace(name, "_").trim().ifEmpty { "motiv" }

    fun zip(items: JSONArray, formats: List<String>): ByteArray {
        val buf = ByteArrayOutputStream()
        val used = HashSet<String>()
        ZipOutputStream(buf).use { z ->
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                val base = safeName(it.optString("name", "motiv"))
                var folder = base; var k = 2
                while (folder in used) folder = "${base}_${k++}"
                used.add(folder)
                for (fmt in formats) {
                    val body = convert(it.getString("svg"), fmt, it.optDouble("width_cm", 28.0), base)
                    z.putNextEntry(ZipEntry("$folder/$base${SUFFIX[fmt]}")); z.write(body); z.closeEntry()
                }
                val cut = it.optString("cut_svg", "")
                if (cut.isNotBlank()) {  // nur Schnittlinie, für Schneideplotter
                    z.putNextEntry(ZipEntry("$folder/${base}_Schnittlinie.svg")); z.write(cut.toByteArray()); z.closeEntry()
                    z.putNextEntry(ZipEntry("$folder/${base}_Schnittlinie.pdf"))
                    z.write(convert(cut, "pdf", it.optDouble("width_cm", 28.0), base)); z.closeEntry()
                }
            }
        }
        return buf.toByteArray()
    }
}
