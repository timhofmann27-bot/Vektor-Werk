package de.vektorwerk.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Gleiche Endpunkte wie der PC-Server (vektorwerk/app/main.py), nur ohne HTTP:
 * die Weboberfläche ruft sie über die JavaScript-Brücke auf (siehe android-bridge.js).
 */
class Api(filesDir: File) {

    class Request(val method: String, val path: String, val fields: Map<String, String>,
                  val files: Map<String, ByteArray>, val body: String?)

    class Response(val status: Int, val type: String, val text: String? = null, val bytes: ByteArray? = null,
                   val headers: Map<String, String> = emptyMap())

    private class HttpError(val status: Int, msg: String) : Exception(msg)

    private val projects = File(filesDir, "projekte")
    private val idRe = Regex("^[a-z0-9][a-z0-9-]{0,79}$")

    private fun json(o: Any, status: Int = 200) = Response(status, "application/json", o.toString())

    fun handle(r: Request): Response = try {
        route(r)
    } catch (e: HttpError) {
        json(JSONObject().put("detail", e.message), e.status)
    } catch (e: OutOfMemoryError) {
        json(JSONObject().put("detail", "Zu wenig Arbeitsspeicher – Bild verkleinern oder weniger Farben wählen."), 507)
    } catch (e: Exception) {
        json(JSONObject().put("detail", e.message ?: e.javaClass.simpleName), 500)
    }

    private fun route(r: Request): Response {
        val path = r.path.substringBefore('?')
        return when {
            path == "/api/presets" && r.method == "GET" -> json(Engine.presetsJson())
            path == "/api/vectorize" && r.method == "POST" -> vectorize(r)
            path == "/api/export" && r.method == "POST" -> export(r)
            path == "/api/zip" && r.method == "POST" -> zip(r)
            path == "/api/projects" && r.method == "GET" -> listProjects()
            path.startsWith("/api/projects/") -> {
                val id = path.removePrefix("/api/projects/")
                if (!idRe.matches(id)) throw HttpError(400, "Ungültige Projekt-ID.")
                when (r.method) {
                    "GET" -> getProject(id)
                    "PUT" -> saveProject(id, r.body ?: "")
                    "DELETE" -> { File(projects, "$id.json").delete(); File(projects, "$id.meta.json").delete(); json(JSONObject().put("ok", true)) }
                    else -> throw HttpError(405, "Methode nicht erlaubt.")
                }
            }
            else -> throw HttpError(404, "Nicht gefunden.")
        }
    }

    // ------------------------------------------------------------ Vektorisieren

    private fun <T> list(raw: String?, name: String, conv: (Any) -> T): List<T>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val a = JSONArray(raw); require(a.length() <= 64)
            (0 until a.length()).map { conv(a.get(it)) }
        } catch (_: Exception) { throw HttpError(400, "Ungültiger Parameter „$name“.") }
    }

    private fun bool(v: String?) = v != null && v.lowercase() in setOf("true", "1", "on", "yes")
    private fun dbl(v: String?): Double? = v?.takeIf { it.isNotBlank() }?.toDoubleOrNull()

    private fun vectorize(r: Request): Response {
        val f = r.fields
        val data = r.files["file"] ?: throw HttpError(400, "Kein Bild übergeben.")
        if (data.size > 40 * 1024 * 1024) throw HttpError(413, "Datei zu groß (max. 40 MB).")
        val preset = f["preset"] ?: "siebdruck"
        if (preset !in Engine.PRESETS) throw HttpError(400, "Unbekanntes Preset.")
        val background = f["background"] ?: "outside"
        if (background !in setOf("outside", "all", "keep")) throw HttpError(400, "Unbekannter Hintergrund-Modus.")
        val opts = Engine.Options(
            preset = preset,
            colors = (dbl(f["colors"]) ?: 0.0).toInt().coerceIn(0, 32),
            smoothing = dbl(f["smoothing"])?.coerceIn(0.0, 6.0),
            speckle = dbl(f["speckle"])?.toInt()?.coerceIn(1, 60),
            background = background,
            widthCm = (dbl(f["width_cm"]) ?: 28.0).coerceIn(1.0, 200.0),
            palette = list(f["palette"], "palette") { it.toString() },
            merge = list(f["merge"], "merge") { (it as Number).toInt() },
            hidden = list(f["hidden"], "hidden") { (it as Number).toInt() } ?: emptyList(),
            underbase = bool(f["underbase"]),
            chokeMm = (dbl(f["choke_mm"]) ?: 0.3).coerceIn(0.0, 3.0),
            paint = r.files["paint"],
            minLineMm = dbl(f["min_line_mm"])?.coerceIn(0.05, 5.0),
            fixThin = bool(f["fix_thin"]),
        )
        val res = try { Engine.vectorize(data, opts) } catch (e: IllegalArgumentException) {
            throw HttpError(400, e.message ?: "Ungültige Eingabe.")
        }
        return json(Engine.resultJson(res))
    }

    // ------------------------------------------------------------ Export

    private fun export(r: Request): Response {
        val svg = r.fields["svg"] ?: throw HttpError(400, "SVG fehlt.")
        val fmt = r.fields["fmt"] ?: ""
        if (fmt !in Exporter.SUFFIX) throw HttpError(400, "Unbekanntes Format.")
        val base = Exporter.safeName(r.fields["name"] ?: "motiv")
        val width = (dbl(r.fields["width_cm"]) ?: 28.0).coerceIn(1.0, 200.0)
        val body = try { Exporter.convert(svg, fmt, width, base) } catch (e: Exception) {
            throw HttpError(500, "Export fehlgeschlagen: ${e.message}")
        }
        return Response(200, Exporter.MIME.getValue(fmt), bytes = body,
            headers = mapOf("Content-Disposition" to "attachment; filename=\"$base${Exporter.SUFFIX[fmt]}\""))
    }

    private fun zip(r: Request): Response {
        val items: JSONArray; val formats: List<String>
        try {
            items = JSONArray(r.fields["items"] ?: "")
            val f = JSONArray(r.fields["formats"] ?: "")
            formats = (0 until f.length()).map { f.getString(it) }.filter { it in Exporter.SUFFIX }
            require(items.length() > 0 && formats.isNotEmpty())
        } catch (_: Exception) { throw HttpError(400, "Ungültige Anfrage.") }
        val body = try { Exporter.zip(items, formats) } catch (e: Exception) { throw HttpError(500, "ZIP fehlgeschlagen: ${e.message}") }
        return Response(200, "application/zip", bytes = body,
            headers = mapOf("Content-Disposition" to "attachment; filename=\"Vektorwerk-Motive.zip\""))
    }

    // ------------------------------------------------------------ Projekte

    private fun listProjects(): Response {
        projects.mkdirs()
        val metas = projects.listFiles { f -> f.name.endsWith(".meta.json") }.orEmpty().mapNotNull {
            try { JSONObject(it.readText()) } catch (_: Exception) { null }
        }.sortedByDescending { it.optDouble("updated", 0.0) }
        return json(JSONArray(metas))
    }

    private fun getProject(id: String): Response {
        val f = File(projects, "$id.json")
        if (!f.exists()) throw HttpError(404, "Projekt nicht gefunden.")
        return Response(200, "application/json", f.readText())
    }

    private fun saveProject(id: String, body: String): Response {
        if (body.length > 300 * 1024 * 1024) throw HttpError(413, "Projekt zu groß.")
        val doc = try { JSONObject(body).also { it.getJSONArray("files") } } catch (_: Exception) {
            throw HttpError(400, "Ungültige Projektdaten.")
        }
        projects.mkdirs()
        val files = doc.getJSONArray("files")
        var thumb: Any = JSONObject.NULL
        for (i in 0 until files.length()) {
            val t = files.optJSONObject(i)?.optString("thumb", "") ?: ""
            if (t.isNotEmpty()) { thumb = t; break }
        }
        val meta = JSONObject().put("id", id).put("name", doc.optString("name", id).ifEmpty { id }.take(120))
            .put("updated", System.currentTimeMillis() / 1000.0).put("files", files.length())
            .put("autosave", id == "autosave").put("thumb", thumb)
        val tmp = File(projects, "$id.tmp"); tmp.writeText(body)
        if (!tmp.renameTo(File(projects, "$id.json"))) { File(projects, "$id.json").writeText(body); tmp.delete() }
        File(projects, "$id.meta.json").writeText(meta.toString())
        return json(meta)
    }

    companion object {
        /** Antwort für die Brücke: Text direkt, Binärdaten als Base64. */
        fun encode(r: Response): String {
            val o = JSONObject().put("status", r.status).put("type", r.type).put("headers", JSONObject(r.headers))
            if (r.bytes != null) o.put("b64", Base64.encodeToString(r.bytes, Base64.NO_WRAP)) else o.put("text", r.text ?: "")
            return o.toString()
        }
    }
}
