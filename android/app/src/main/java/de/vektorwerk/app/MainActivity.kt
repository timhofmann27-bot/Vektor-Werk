package de.vektorwerk.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lädt die Vektorwerk-Weboberfläche (dieselbe wie am PC) aus den App-Assets.
 * Alle /api/…-Aufrufe der Oberfläche gehen über die Brücke "VwNative" an [Api] – alles läuft offline.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val ORIGIN = "https://$HOST"
    }

    private lateinit var web: WebView
    private lateinit var api: Api
    private val worker = Executors.newSingleThreadExecutor() // Engine nacheinander, wie am PC-Server
    private val results = ConcurrentHashMap<String, String>()
    private val shared = ArrayList<Uri>()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        fileCallback?.onReceiveValue(uris.toTypedArray()); fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) Toast.makeText(this, "OpenCV konnte nicht geladen werden.", Toast.LENGTH_LONG).show()
        api = Api(filesDir)

        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        // Edge-to-edge (targetSdk 35+): Container um Status-/Navigationsleiste einrücken (WebView selbst ignoriert Padding)
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFFF4F3F0.toInt()); addView(web) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom); androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = false
            setSupportZoom(false)
        }
        web.addJavascriptInterface(Bridge(), "VwNative")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                return serveAsset(u.path ?: "/")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                startActivity(Intent(Intent.ACTION_VIEW, request.url)) // externe Links im Browser
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickImages.launch("image/*")
                return true
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.vwBack&&vwBack())?'nav':(typeof state!=='undefined'&&state.dirty&&state.files.length>0)") { v ->
                    if (v == "\"nav\"") return@evaluateJavascript // Seite hat selbst zurück navigiert
                    if (v == "true") AlertDialog.Builder(this@MainActivity)
                        .setMessage("Es gibt ungespeicherte Änderungen. Die letzte Sitzung lässt sich beim nächsten Start wiederherstellen.")
                        .setPositiveButton("Beenden") { _, _ -> finish() }
                        .setNegativeButton("Abbrechen", null).show()
                    else finish()
                }
            }
        })

        collectShared(intent)
        if (savedInstanceState == null) web.loadUrl("$ORIGIN/") else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        collectShared(intent)
        web.evaluateJavascript("window.__vw&&__vw.pullShared()", null)
    }

    override fun onDestroy() { worker.shutdownNow(); web.destroy(); super.onDestroy() }

    // ------------------------------------------------------------ Assets

    private fun serveAsset(path: String): WebResourceResponse? {
        val name = when {
            path == "/" || path == "/index.html" -> "index.html"
            path.startsWith("/static/") -> path.removePrefix("/static/")
            path.startsWith("/bridge/") -> "bridge/" + path.removePrefix("/bridge/")
            else -> return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        if (name.contains("..")) return null
        return try {
            var bytes = assets.open(name).use { it.readBytes() }
            if (name == "index.html") {
                // Brücke vor allen Seitenskripten laden, damit fetch('/api/…') schon beim Start umgeleitet wird
                bytes = String(bytes).replaceFirst("<head>", "<head>\n<script src=\"/bridge/android-bridge.js\"></script>").toByteArray()
            }
            val mime = when (name.substringAfterLast('.')) {
                "html" -> "text/html"; "js" -> "application/javascript"; "json" -> "application/json"
                "css" -> "text/css"; "svg" -> "image/svg+xml"; "png" -> "image/png"; "woff2" -> "font/woff2"; else -> "application/octet-stream"
            }
            WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") || mime.endsWith("xml")) "utf-8" else null, 200, "OK", mapOf("Cache-Control" to "no-cache"), ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    // ------------------------------------------------------------ Teilen -> Vektorwerk

    private fun collectShared(intent: Intent?) {
        if (intent == null) return
        synchronized(shared) {
            when (intent.action) {
                Intent.ACTION_SEND -> uriExtra(intent)?.let { shared.add(it) }
                Intent.ACTION_SEND_MULTIPLE -> uriListExtra(intent)?.let { shared.addAll(it) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun uriExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun uriListExtra(i: Intent): List<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment ?: "bild"
    }

    // ------------------------------------------------------------ Speichern in "Download/Vektorwerk"

    private fun saveDownload(name: String, mime: String, data: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vektorwerk")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Download-Ordner nicht verfügbar.")
        contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun shareFile(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Datei teilen"))
    }

    // ------------------------------------------------------------ JavaScript-Brücke

    inner class Bridge {
        /** Startet einen API-Aufruf im Hintergrund; das Ergebnis holt die Seite mit take(id). */
        @JavascriptInterface
        fun request(id: String, method: String, path: String, payload: String) {
            worker.execute {
                val res = try {
                    val p = if (payload.isBlank()) JSONObject() else JSONObject(payload)
                    val fields = HashMap<String, String>(); val files = HashMap<String, ByteArray>()
                    p.optJSONObject("fields")?.let { o -> o.keys().forEach { k -> fields[k] = o.getString(k) } }
                    p.optJSONObject("files")?.let { o -> o.keys().forEach { k ->
                        files[k] = Base64.decode(o.getJSONObject(k).getString("b64"), Base64.DEFAULT) } }
                    val body = if (p.has("body")) p.getString("body") else null
                    Api.encode(api.handle(Api.Request(method.uppercase(), path, fields, files, body)))
                } catch (e: Throwable) {
                    JSONObject().put("status", 500).put("type", "application/json").put("headers", JSONObject())
                        .put("text", JSONObject().put("detail", e.message ?: e.javaClass.simpleName).toString()).toString()
                }
                results[id] = res
                runOnUiThread { web.evaluateJavascript("window.__vw&&__vw.done(${JSONObject.quote(id)})", null) }
            }
        }

        @JavascriptInterface
        fun take(id: String): String = results.remove(id) ?: ""

        /** Datei in Download/Vektorwerk speichern; share=true öffnet danach "Teilen". */
        @JavascriptInterface
        fun save(name: String, mime: String, b64: String, share: Boolean) {
            try {
                val uri = saveDownload(name, mime, Base64.decode(b64, Base64.DEFAULT))
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Gespeichert: Download/Vektorwerk/$name", Toast.LENGTH_SHORT).show()
                    if (share) shareFile(uri, mime)
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }

        /** Per "Teilen" an die App geschickte Bilder (einmalig abholen). */
        @JavascriptInterface
        fun pendingShared(): String {
            val list = synchronized(shared) { shared.toList().also { shared.clear() } }
            val arr = JSONArray()
            for (u in list) try {
                val bytes = contentResolver.openInputStream(u)!!.use { it.readBytes() }
                arr.put(JSONObject().put("name", displayName(u)).put("type", contentResolver.getType(u) ?: "image/*")
                    .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            } catch (_: Exception) { }
            return arr.toString()
        }
    }
}
