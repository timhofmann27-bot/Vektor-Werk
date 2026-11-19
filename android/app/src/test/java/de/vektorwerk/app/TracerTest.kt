package de.vektorwerk.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class TracerTest {

    private fun params() = Tracer.Params(speckle = 6, cornerDeg = 60.0)

    @Test
    fun quadratMitLoch() {
        val w = 40; val h = 40
        val m = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 5..34 && y in 5..34 && !(x in 15..24 && y in 15..24) }
        val r = Tracer.trace(m, w, h, params())
        assertEquals("Außenrand + Loch gehören in einen Pfad", 1, r.paths.size)
        assertEquals("zwei Teilpfade", 2, Regex("M").findAll(r.paths[0]).count())
    }

    @Test
    fun kruemelWerdenEntfernt() {
        val w = 30; val h = 30
        val m = BooleanArray(w * h); m[15 * w + 15] = true; m[15 * w + 16] = true
        assertTrue(Tracer.trace(m, w, h, params()).paths.isEmpty())
    }

    @Test
    fun diagonalVerbundenBleibtEinTeil() {
        val w = 20; val h = 20
        val m = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; (x in 2..8 && y in 2..8) || (x in 9..15 && y in 9..15) }
        assertEquals(1, Tracer.trace(m, w, h, Tracer.Params(1, 60.0)).paths.size)
    }

    /** Echte Motive: Umgebungsvariable VW_MASKS=<Ordner mit *.png> schreibt <name>.svg daneben (weiß = Motiv). */
    @Test
    fun echteMasken() {
        val dir = System.getenv("VW_MASKS") ?: return
        for (f in File(dir).listFiles { x -> x.extension == "png" }!!.sorted()) {
            val img = ImageIO.read(f)
            val w = img.width; val h = img.height
            val m = BooleanArray(w * h) { i -> (img.getRGB(i % w, i / w) and 0xFF) > 127 }
            val t0 = System.nanoTime()
            val r = Tracer.trace(m, w, h, params())
            val ms = (System.nanoTime() - t0) / 1_000_000
            val svg = buildString {
                append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\">")
                append("<rect width=\"100%\" height=\"100%\" fill=\"#000\"/><g fill=\"#fff\" fill-rule=\"evenodd\">")
                for (p in r.paths) append("<path d=\"").append(p).append("\"/>")
                append("</g></svg>")
            }
            File(f.parentFile, f.nameWithoutExtension + ".svg").writeText(svg)
            File(f.parentFile, f.nameWithoutExtension + ".txt").writeText("${r.paths.size} Pfade, ${r.nodes} Knoten, $ms ms\n")
        }
    }
}
