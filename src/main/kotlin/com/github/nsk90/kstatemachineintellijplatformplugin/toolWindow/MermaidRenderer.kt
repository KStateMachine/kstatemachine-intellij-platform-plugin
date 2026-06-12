package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * JCEF-backed Mermaid renderer. Loads a tiny HTML page that includes the
 * bundled `mermaid.min.js` (UMD build, exposes `mermaid` as a window global)
 * and renders the supplied source via `mermaid.run`.
 *
 * Source updates and theme toggles regenerate the page through `loadHTML`
 * — Mermaid's runtime API doesn't expose a clean "replace the SVG" entry
 * point for arbitrary new source, and reloading the whole page is fast
 * enough for the use case (sub-100 ms in practice for our diagrams).
 *
 * Exposes the rendered SVG via a JS-to-Kotlin bridge ([currentSvg]) so the
 * Export action can save it without round-tripping through screenshot APIs.
 *
 * **Drag handling** is done at the Java [AWTEventListener] level so that
 * panning works even when the cursor leaves the JCEF component — see the
 * equivalent note in [PlantUmlJsRenderer] for full details.
 *
 * **Zoom** is controlled by the [JSlider] at the bottom of the component,
 * mirroring the implementation in [PlantUmlJsRenderer].
 */
class MermaidRenderer {

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
        "JCEF runtime is not available in this IDE — Mermaid rendering is disabled.",
        SwingConstants.CENTER,
    )

    val component: JComponent get() = wrapper ?: placeholder

    fun render(source: String, dark: Boolean) {
        browser ?: return
        SwingUtilities.invokeLater {
            zoomSlider.value = 100
            zoomLabel.text = "100%"
        }
        val notifyZoomCall = zoomSyncQuery?.inject("String(Math.round(zoom * 100))") ?: ""
        browser.loadHTML(buildHtml(source, dark, notifyZoomCall))
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

    private fun buildHtml(source: String, dark: Boolean, notifyZoomCall: String): String {
        val mermaidJs = loadBundledMermaid()
        val theme = if (dark) "dark" else "default"
        val captureCall = svgCaptureQuery?.let { q ->
            "function captureSvg(svg) { ${q.inject("svg")} }"
        } ?: "function captureSvg() {}"

        val escapedSource = source
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8" />
              <style>
                html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; ${bodyStyle(dark)} }
                #vp { position: absolute; top: 0; left: 0; right: 0; bottom: 0; overflow: hidden; cursor: grab; touch-action: none; }
                #canvas { position: absolute; top: 0; left: 0; transform-origin: 0 0; padding: 12px; will-change: transform; }
                .mermaid svg { max-width: none !important; }
                #err { position: fixed; top: 0; left: 0; right: 0; z-index: 9; display: none;
                       padding: 16px; color: ${if (dark) "#ff8585" else "#b00020"};
                       background: ${if (dark) "#2B2B2B" else "#FFFFFF"};
                       font-family: monospace; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <div id="vp"><div id="canvas"><pre class="mermaid">$escapedSource</pre></div></div>
              <div id="err"></div>
              <script>$mermaidJs</script>
              <script>
                $captureCall
                mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'loose' });
                (async () => {
                  try {
                    await mermaid.run({ querySelector: '.mermaid' });
                    var svgEl = document.querySelector('.mermaid svg');
                    if (svgEl) captureSvg(svgEl.outerHTML);
                  } catch (e) {
                    var d = document.getElementById('err');
                    d.style.display = 'block';
                    d.textContent = 'Mermaid render error: ' + (e && e.message ? e.message : e);
                  }
                })();
              </script>
              <script>${buildPanZoomScript(notifyZoomCall)}</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun bodyStyle(dark: Boolean): String =
        "background:${if (dark) "#2B2B2B" else "#FFFFFF"};" +
            "color:${if (dark) "#DDDDDD" else "#000000"};"

    private fun loadBundledMermaid(): String =
        cachedMermaidJs ?: run {
            val js = MermaidRenderer::class.java.getResourceAsStream("/mermaid/mermaid.min.js")
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: run {
                    thisLogger().warn("Bundled mermaid.min.js not found on classpath")
                    return@run ""
                }
            cachedMermaidJs = js
            js
        }

    companion object {
        @Volatile
        private var cachedMermaidJs: String? = null
    }
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
