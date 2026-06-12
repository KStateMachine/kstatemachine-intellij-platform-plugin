package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * JCEF-backed PlantUML renderer using the official TeaVM-compiled PlantUML
 * (plantuml.js, ~7.3 MB) together with Viz.js (viz-global.js, ~1.5 MB —
 * Graphviz/dot compiled to WASM). Both assets ship under
 * src/main/resources/plantuml-js/.
 *
 * Replaces the previous `plantuml-mit` JAR + Smetana path. Smetana mis-laid
 * out parallel and composite state diagrams; Viz.js uses real Graphviz dot
 * and renders them correctly — matching plantuml.com's own in-browser editor.
 *
 * Delivery: a global CEF scheme handler at `https://kstatemachine.plantuml/`
 * serves three resources:
 *  - `/page-N.html` — the per-render bootstrap HTML (one URL per render so
 *    JCEF doesn't short-circuit on cache; the HTML is held in a static map
 *    keyed by the token until consumed).
 *  - `/plantuml.js` and `/viz-global.js` — bundled classpath assets.
 *
 * The page imports `./plantuml.js` as an ES module. Because the page itself
 * loads from `https://kstatemachine.plantuml/...`, that import resolves to
 * the same origin and the scheme handler answers it — no CORS, no
 * file:// null-origin block.
 *
 * **Drag handling** is intentionally done at the Java [AWTEventListener] level
 * (not in JavaScript) so that panning continues smoothly even after the cursor
 * leaves the JCEF component boundary into the rest of the IDE. The listener
 * converts physical-pixel deltas to logical (CSS) pixels via the component's
 * device-pixel ratio and forwards them to JS via [CefBrowser.executeJavaScript].
 *
 * **Zoom** is controlled by the [JSlider] at the bottom of the component.
 * The slider calls `window.__ksmSetZoom(value)` in JS, which scales around the
 * viewport centre. Double-click still fits the diagram; the slider is updated
 * via a [JBCefJSQuery] bridge so it stays in sync.
 */
class PlantUmlJsRenderer {

    private val supported: Boolean = JBCefApp.isSupported()
    private val browser: JBCefBrowser? = if (supported) JBCefBrowser() else null

    // ── Zoom slider ────────────────────────────────────────────────────────────

    private val zoomSlider = JSlider(SwingConstants.HORIZONTAL, 70, 200, 100).apply {
        preferredSize = Dimension(150, 20)
        isFocusable = false
    }
    private val zoomLabel = JLabel("100%").apply {
        preferredSize = Dimension(40, 16)
    }
    private val sliderPanel = JPanel(FlowLayout(FlowLayout.CENTER, 4, 2)).apply {
        add(JLabel("Zoom:"))
        add(zoomSlider)
        add(zoomLabel)
    }

    // ── Wrapper panel (browser + slider) ───────────────────────────────────────

    private val wrapper: JPanel? = browser?.let { b ->
        JPanel(BorderLayout()).apply {
            add(b.component, BorderLayout.CENTER)
            add(sliderPanel, BorderLayout.SOUTH)
        }
    }

    // ── Java-level drag infrastructure ────────────────────────────────────────

    @Volatile private var javaDragging = false
    private var javaDragLastX = 0
    private var javaDragLastY = 0

    private val awtMouseListener = AWTEventListener { event ->
        if (event !is MouseEvent) return@AWTEventListener
        val b = browser ?: return@AWTEventListener
        when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                if (event.button != MouseEvent.BUTTON1 || javaDragging) return@AWTEventListener
                if (!isEventWithinBrowser(event)) return@AWTEventListener
                javaDragging = true
                javaDragLastX = event.xOnScreen
                javaDragLastY = event.yOnScreen
                b.cefBrowser.executeJavaScript("window.__ksmStartPan&&window.__ksmStartPan()", "", 0)
            }
            MouseEvent.MOUSE_DRAGGED -> {
                if (!javaDragging) return@AWTEventListener
                val dx = event.xOnScreen - javaDragLastX
                val dy = event.yOnScreen - javaDragLastY
                javaDragLastX = event.xOnScreen
                javaDragLastY = event.yOnScreen
                if (dx == 0 && dy == 0) return@AWTEventListener
                b.cefBrowser.executeJavaScript(
                    "window.__ksmPan&&window.__ksmPan($dx,$dy)",
                    "", 0,
                )
            }
            MouseEvent.MOUSE_RELEASED -> {
                if (!javaDragging || event.button != MouseEvent.BUTTON1) return@AWTEventListener
                javaDragging = false
                b.cefBrowser.executeJavaScript("window.__ksmEndPan&&window.__ksmEndPan()", "", 0)
            }
        }
    }

    private fun isEventWithinBrowser(event: MouseEvent): Boolean {
        val comp = browser?.component ?: return false
        if (!comp.isShowing) return false
        return try {
            val loc = comp.locationOnScreen
            event.xOnScreen in loc.x until (loc.x + comp.width) &&
                event.yOnScreen in loc.y until (loc.y + comp.height)
        } catch (_: Exception) {
            false
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        if (browser != null) {
            registerSchemeIfNeeded()
            Toolkit.getDefaultToolkit().addAWTEventListener(
                awtMouseListener,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
            )
            zoomSlider.addChangeListener {
                val pct = zoomSlider.value
                zoomLabel.text = "$pct%"
                browser.cefBrowser.executeJavaScript(
                    "window.__ksmSetZoom&&window.__ksmSetZoom(${pct / 100.0})",
                    "", 0,
                )
            }
        }
    }

    /** Must be called when the owning panel is disposed to remove the global AWT listener. */
    fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtMouseListener)
        browser?.dispose()
    }

    // ── SVG capture bridge ────────────────────────────────────────────────────

    /** Last successfully captured SVG markup, updated by the in-page JS bridge. */
    @Volatile
    var currentSvg: String? = null
        private set

    private val svgCaptureQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { svg ->
                currentSvg = svg
                null
            }
        }
    }

    // ── Zoom sync bridge (JS → slider, fired by double-click fit) ─────────────

    private val zoomSyncQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { zoomStr ->
                val pct = (zoomStr.toDoubleOrNull() ?: 100.0).roundToInt().coerceIn(70, 200)
                SwingUtilities.invokeLater {
                    zoomSlider.value = pct
                    zoomLabel.text = "$pct%"
                }
                null
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private val placeholder: JLabel = JLabel(
        "JCEF runtime is not available in this IDE — PlantUML rendering is disabled.",
        SwingConstants.CENTER,
    )

    val component: JComponent get() = wrapper ?: placeholder

    fun render(source: String, dark: Boolean) {
        val b = browser ?: return
        SwingUtilities.invokeLater {
            zoomSlider.value = 100
            zoomLabel.text = "100%"
        }
        val token = pageCounter.incrementAndGet()
        val html = buildHtml(source, dark)
        pendingPages[token] = html
        b.loadURL("$BASE_URL$PAGE_PATH_PREFIX$token.html")
    }

    fun showPlaceholder(message: String) {
        val b = browser ?: return
        val html = """
            <html><body style="${bodyStyle(dark = false)}">
              <div style="color:#888;padding:24px;text-align:center;font-family:sans-serif;">$message</div>
            </body></html>
        """.trimIndent()
        b.loadHTML(html)
        currentSvg = null
    }

    private fun buildHtml(source: String, dark: Boolean): String {
        val captureCall = svgCaptureQuery?.let { q ->
            "function captureSvg(svg) { ${q.inject("svg")} }"
        } ?: "function captureSvg() {}"
        val notifyZoomCall = zoomSyncQuery?.inject("String(Math.round(zoom * 100))") ?: ""
        val sourceLiteral = source.toJsStringLiteral()
        val darkLiteral = if (dark) "true" else "false"

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>
                html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; ${bodyStyle(dark)} }
                #vp { position: absolute; top: 0; left: 0; right: 0; bottom: 0; overflow: hidden; cursor: grab; touch-action: none; }
                #canvas { position: absolute; top: 0; left: 0; transform-origin: 0 0; padding: 12px; will-change: transform; }
                #err { position: fixed; top: 0; left: 0; right: 0; z-index: 9; display: none;
                       padding: 16px; color: ${if (dark) "#ff8585" else "#b00020"};
                       background: ${if (dark) "#2B2B2B" else "#FFFFFF"};
                       font-family: monospace; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <div id="vp"><div id="canvas"><div id="out"></div></div></div>
              <div id="err"></div>
              <script src="viz-global.js"></script>
              <script type="module">
                $captureCall
                try {
                  const mod = await import('./plantuml.js');
                  mod.render($sourceLiteral.split(/\r\n|\r|\n/), 'out', {dark: $darkLiteral});
                  const svg = document.querySelector('#out svg');
                  if (svg) captureSvg(svg.outerHTML);
                } catch (e) {
                  const d = document.getElementById('err');
                  d.style.display = 'block';
                  d.textContent = 'PlantUML render error: ' + (e && e.message ? e.message : e);
                }
              </script>
              <script>${buildPanZoomScript(notifyZoomCall)}</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun bodyStyle(dark: Boolean): String =
        "background:${if (dark) "#2B2B2B" else "#FFFFFF"};" +
            "color:${if (dark) "#DDDDDD" else "#000000"};"

    companion object {
        private const val DOMAIN = "kstatemachine.plantuml"
        private const val BASE_URL = "https://$DOMAIN/"
        private const val PAGE_PATH_PREFIX = "page-"

        private val schemeRegistered = AtomicBoolean(false)
        internal val pageCounter = AtomicLong(0)
        internal val pendingPages: MutableMap<Long, String> = ConcurrentHashMap()

        /**
         * Registers a CEF scheme handler factory on the `https` scheme for
         * our synthetic domain `kstatemachine.plantuml`. Safe to call from
         * any thread, multiple times — runs the real work at most once per
         * JVM. Must be called after JBCefApp has started (creating any
         * `JBCefBrowser` triggers that).
         */
        private fun registerSchemeIfNeeded() {
            if (!schemeRegistered.compareAndSet(false, true)) return
            try {
                CefApp.getInstance().registerSchemeHandlerFactory("https", DOMAIN, BundledAssetFactory)
            } catch (t: Throwable) {
                schemeRegistered.set(false)
                thisLogger().warn("Failed to register PlantUML scheme handler", t)
            }
        }

        internal fun consumePendingPage(token: Long): String? = pendingPages.remove(token)
    }
}

/** Maps URL paths under `kstatemachine.plantuml` to bundled or pending content. */
private object BundledAssetFactory : CefSchemeHandlerFactory {
    private const val PAGE_PREFIX = "page-"
    private const val PAGE_SUFFIX = ".html"

    override fun create(
        browser: CefBrowser?,
        frame: CefFrame?,
        schemeName: String?,
        request: CefRequest?,
    ): CefResourceHandler? {
        val url = request?.url ?: return null
        val path = url
            .substringAfter("://", "")
            .substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        return when {
            path.startsWith(PAGE_PREFIX) && path.endsWith(PAGE_SUFFIX) -> {
                val token = path.removePrefix(PAGE_PREFIX).removeSuffix(PAGE_SUFFIX).toLongOrNull()
                    ?: return null
                val html = PlantUmlJsRenderer.consumePendingPage(token) ?: run {
                    thisLogger().warn("No pending PlantUML page for token $token (URL=$url)")
                    return null
                }
                BundledResourceHandler(html.toByteArray(Charsets.UTF_8), "text/html")
            }
            path == "plantuml.js" || path == "viz-global.js" -> {
                val bytes = BundledAssetFactory::class.java.getResourceAsStream("/plantuml-js/$path")
                    ?.use { it.readBytes() }
                    ?: run {
                        thisLogger().warn("Bundled asset not found on classpath: /plantuml-js/$path")
                        return null
                    }
                BundledResourceHandler(bytes, "application/javascript")
            }
            else -> null
        }
    }
}

private class BundledResourceHandler(
    private val bytes: ByteArray,
    private val mime: String,
) : CefResourceHandler {

    @Volatile
    private var offset = 0

    override fun processRequest(request: CefRequest?, callback: CefCallback?): Boolean {
        callback?.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.mimeType = mime
        response.status = 200
        response.setHeaderByName("Access-Control-Allow-Origin", "*", false)
        responseLength.set(bytes.size)
    }

    override fun readResponse(
        dataOut: ByteArray,
        bytesToRead: Int,
        bytesRead: IntRef,
        callback: CefCallback?,
    ): Boolean {
        if (offset >= bytes.size) {
            bytesRead.set(0)
            return false
        }
        val n = minOf(bytesToRead, bytes.size - offset)
        System.arraycopy(bytes, offset, dataOut, 0, n)
        offset += n
        bytesRead.set(n)
        return true
    }

    override fun cancel() {}
}

/**
 * Encode a Kotlin string as a JavaScript string literal — wraps with double
 * quotes and escapes characters that would terminate the literal or be
 * interpreted as line terminators by the JS parser.
 */
private fun String.toJsStringLiteral(): String {
    val sb = StringBuilder(length + 16).append('"')
    for (c in this) {
        when (c.code) {
            '\\'.code -> sb.append("\\\\")
            '"'.code -> sb.append("\\\"")
            '\n'.code -> sb.append("\\n")
            '\r'.code -> sb.append("\\r")
            '\t'.code -> sb.append("\\t")
            0x2028 -> sb.append("\\u2028") // LINE SEPARATOR
            0x2029 -> sb.append("\\u2029") // PARAGRAPH SEPARATOR
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun buildPanZoomScript(notifyZoomCall: String): String = """
(function() {
  var vp = document.getElementById('vp');
  var canvas = document.getElementById('canvas');
  var zoom = 1, panX = 0, panY = 0;

  function apply() {
    canvas.style.transform = 'translate('+panX+'px,'+panY+'px) scale('+zoom+')';
  }

  function notifyZoom() { $notifyZoomCall }

  function fitToView() {
    canvas.style.transform = 'none';
    var cw = canvas.offsetWidth, ch = canvas.offsetHeight;
    if (cw <= 0 || ch <= 0) { zoom = 1; panX = 0; panY = 0; apply(); return; }
    var vw = vp.clientWidth, vh = vp.clientHeight;
    var fit = Math.min(vw / cw, vh / ch);
    zoom = fit; panX = (vw - cw*fit)/2; panY = (vh - ch*fit)/2; apply();
    notifyZoom();
  }

  window.__ksmStartPan = function()       { vp.style.cursor = 'grabbing'; };
  window.__ksmPan      = function(dx, dy) { panX += dx; panY += dy; apply(); };
  window.__ksmEndPan   = function()       { vp.style.cursor = 'grab'; };
  window.__ksmSetZoom  = function(nz) {
    var vw = vp.clientWidth, vh = vp.clientHeight;
    panX = vw/2 + (panX - vw/2) * (nz/zoom);
    panY = vh/2 + (panY - vh/2) * (nz/zoom);
    zoom = Math.min(2, Math.max(0.7, nz));
    apply();
  };

  vp.addEventListener('dblclick', function() { fitToView(); });
})();
""".trimIndent()
