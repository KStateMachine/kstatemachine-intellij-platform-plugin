package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

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
 * Abstract base for JCEF-backed diagram renderers.
 *
 * Owns all shared infrastructure: the JCEF browser instance, Java-level
 * drag-to-pan, the zoom slider (70–200 %), the SVG capture bridge, and the
 * zoom-sync bridge that keeps the slider in sync when the user double-clicks
 * to fit. Subclasses only implement [render] and their own HTML-building logic.
 */
abstract class JcefDiagramRenderer(rendererName: String) {

    protected val supported: Boolean = JBCefApp.isSupported()
    protected val browser: JBCefBrowser? = if (supported) JBCefBrowser() else null

    // ── Zoom slider ────────────────────────────────────────────────────────────

    private val zoomSlider = JSlider(SwingConstants.HORIZONTAL, 50, 200, 100).apply {
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
                b.cefBrowser.executeJavaScript("window.__ksmPan&&window.__ksmPan($dx,$dy)", "", 0)
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

    fun dispose() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtMouseListener)
        browser?.dispose()
    }

    // ── SVG capture bridge ────────────────────────────────────────────────────

    @Volatile
    var currentSvg: String? = null
        private set

    protected val svgCaptureQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { svg -> currentSvg = svg; null }
        }
    }

    // ── Zoom sync bridge (JS → slider, fired by double-click fit) ─────────────

    protected val zoomSyncQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { zoomStr ->
                val pct = (zoomStr.toDoubleOrNull() ?: 100.0).roundToInt().coerceIn(50, 200)
                SwingUtilities.invokeLater {
                    zoomSlider.value = pct
                    zoomLabel.text = "$pct%"
                }
                null
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private val placeholder = JLabel(
        "JCEF runtime is not available in this IDE — $rendererName rendering is disabled.",
        SwingConstants.CENTER,
    )

    val component: JComponent get() = wrapper ?: placeholder

    fun showPlaceholder(message: String) {
        val b = browser ?: return
        b.loadHTML("""
            <html><body style="${bodyStyle(dark = false)}">
              <div style="color:#888;padding:24px;text-align:center;font-family:sans-serif;">$message</div>
            </body></html>
        """.trimIndent())
        currentSvg = null
    }

    // ── Shared helpers for subclasses ─────────────────────────────────────────

    protected fun resetZoom() {
        SwingUtilities.invokeLater {
            zoomSlider.value = 100
            zoomLabel.text = "100%"
        }
    }

    protected fun bodyStyle(dark: Boolean): String =
        "background:${if (dark) "#2B2B2B" else "#FFFFFF"};" +
            "color:${if (dark) "#DDDDDD" else "#000000"};"

    protected fun buildPanZoomScript(notifyZoomCall: String): String = """
(function() {
  var vp = document.getElementById('vp');
  var canvas = document.getElementById('canvas');
  var zoom = 1, panX = 0, panY = 0;

  // Keep at least 50 px of the diagram inside the viewport on every edge.
  function clampPan() {
    var cw = canvas.offsetWidth, ch = canvas.offsetHeight;
    if (cw <= 0 || ch <= 0) return;
    var margin = 50;
    var vw = vp.clientWidth, vh = vp.clientHeight;
    panX = Math.min(vw - margin, Math.max(margin - cw * zoom, panX));
    panY = Math.min(vh - margin, Math.max(margin - ch * zoom, panY));
  }

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
  window.__ksmPan      = function(dx, dy) { panX += dx; panY += dy; clampPan(); apply(); };
  window.__ksmEndPan   = function()       { vp.style.cursor = 'grab'; };
  window.__ksmSetZoom  = function(nz) {
    var vw = vp.clientWidth, vh = vp.clientHeight;
    panX = vw/2 + (panX - vw/2) * (nz/zoom);
    panY = vh/2 + (panY - vh/2) * (nz/zoom);
    zoom = Math.min(2, Math.max(0.5, nz));
    clampPan(); apply();
  };

  vp.addEventListener('dblclick', function() { fitToView(); });
})();
""".trimIndent()

    abstract fun render(source: String, dark: Boolean)
}
