package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/**
 * Abstract base for JCEF-backed diagram renderers.
 *
 * Owns all shared infrastructure: the JCEF browser instance, Java-level
 * drag-to-pan, the zoom slider, the SVG capture bridge, and the zoom-sync
 * bridge that keeps the slider in sync when the user double-clicks to fit.
 * Subclasses only implement [render] and their own HTML-building logic.
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
    // ── Cover panel ────────────────────────────────────────────────────────────
    // Opaque Swing panel laid on top of the JCEF browser via JLayeredPane.
    // Shown while render() is loading new HTML so the user never sees the
    // old page's last frame or the engine's intermediate render passes;
    // hidden again when JS signals via [readySignalQuery] that the diagram
    // is sized and centered.

    private val coverPanel = JPanel().apply {
        isOpaque = true
        background = coverBackground()
        isVisible = false
    }

    private fun coverBackground(): Color =
        if (JBColor.isBright()) Color(0xFF, 0xFF, 0xFF) else Color(0x2B, 0x2B, 0x2B)

    private val browserStack: JLayeredPane? = browser?.let { b ->
        val bc = b.component
        val pane = JLayeredPane()
        pane.add(bc, JLayeredPane.DEFAULT_LAYER)
        pane.add(coverPanel, JLayeredPane.PALETTE_LAYER)
        pane.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = pane.width; val h = pane.height
                bc.setBounds(0, 0, w, h)
                coverPanel.setBounds(0, 0, w, h)
            }
        })
        pane
    }

    private val wrapper: JPanel? = browserStack?.let { stack ->
        JPanel(BorderLayout()).apply {
            add(stack, BorderLayout.CENTER)
            add(sliderPanel, BorderLayout.SOUTH)
        }
    }

    protected fun showCover() {
        val show = {
            coverPanel.background = coverBackground()
            coverPanel.isVisible = true
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    private fun hideCover() {
        SwingUtilities.invokeLater { coverPanel.isVisible = false }
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

    // ── Zoom sync bridge (JS → slider) ────────────────────────────────────────

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

    // ── Ready signal bridge (JS → Java hideCover) ────────────────────────────

    protected val readySignalQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { _ -> hideCover(); null }
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

    protected fun bodyStyle(dark: Boolean): String =
        "background:${if (dark) "#2B2B2B" else "#FFFFFF"};" +
            "color:${if (dark) "#DDDDDD" else "#000000"};"

    protected fun buildPanZoomScript(notifyZoomCall: String): String {
        // Seed the JS page with the renderer's current slider position so the
        // user's chosen zoom survives across re-renders (machine switch, mode
        // switch). Each renderer instance has its own slider, so PlantUML and
        // Mermaid values are naturally tracked separately.
        val initialZoom = zoomSlider.value / 100.0
        val notifyReadyCall = readySignalQuery?.inject("") ?: ""
        return """
(function() {
  var vp = document.getElementById('vp');
  var canvas = document.getElementById('canvas');
  // Hide the canvas during the engine's render passes so the user doesn't see
  // intermediate states (Mermaid lays out its SVG several times; PlantUML's
  // raw SVG briefly appears unscaled). Revealed once tryInit() succeeds.
  canvas.style.visibility = 'hidden';

  var panX = 0, panY = 0, zoom = $initialZoom;
  var naturalW = 0, naturalH = 0;
  var autoCentered = false;
  var userTouched = false;
  var revealed = false;
  var notifiedReady = false;
  var pollStarted = false;

  function getSvg() { return canvas.querySelector('svg'); }

  // Zoom by resizing the SVG element directly so the browser re-renders the
  // vector at the new pixel size — no rasterisation, lossless at any zoom.
  function applyZoom() {
    var svg = getSvg();
    if (!svg || naturalW === 0) return;
    svg.style.width     = (naturalW * zoom) + 'px';
    svg.style.height    = (naturalH * zoom) + 'px';
    svg.style.maxWidth  = 'none';
    svg.style.maxHeight = 'none';
  }

  function applyPan() {
    canvas.style.transform = 'translate(' + panX + 'px,' + panY + 'px)';
  }

  function notifyZoom() { $notifyZoomCall }

  // Tells Java that the diagram is sized + centered, so the Swing cover panel
  // can be removed. Idempotent — multiple calls are no-ops after the first.
  function notifyReady() {
    if (notifiedReady) return;
    notifiedReady = true;
    $notifyReadyCall
  }

  // Polls for the viewport size becoming non-zero so centering can succeed.
  // The JCEF browser's viewport often lags by a few frames after the Swing
  // component is laid out (especially for the previously-hidden Mermaid
  // card on first mode switch). setTimeout is independent of paint events,
  // so this fires even when the browser hasn't repainted yet.
  function pollAndNotify(attemptsLeft) {
    if (notifiedReady) return;
    centerIfPossible();
    if (autoCentered || userTouched) { notifyReady(); return; }
    if (attemptsLeft <= 0)           { notifyReady(); return; }
    setTimeout(function() { pollAndNotify(attemptsLeft - 1); }, 50);
  }

  function startPolling() {
    if (pollStarted) return;
    pollStarted = true;
    pollAndNotify(60); // up to ~3 seconds of 50 ms ticks
  }

  // Safety net: even if no SVG ever appears (engine error) or polling never
  // converges, reveal the canvas and lift the cover after 5 seconds so the
  // user isn't stuck looking at a blank panel.
  setTimeout(function() {
    if (!revealed) { canvas.style.visibility = 'visible'; revealed = true; }
    notifyReady();
  }, 5000);

  // Keep at least 50 px of the diagram inside the viewport on every edge.
  function clampPan() {
    var cw = canvas.offsetWidth, ch = canvas.offsetHeight;
    if (cw <= 0 || ch <= 0) return;
    var margin = 50, vw = vp.clientWidth, vh = vp.clientHeight;
    panX = Math.min(vw - margin, Math.max(margin - cw, panX));
    panY = Math.min(vh - margin, Math.max(margin - ch, panY));
  }

  function fitToView() {
    if (naturalW === 0) return;
    var vw = vp.clientWidth, vh = vp.clientHeight;
    zoom = Math.min(2, Math.max(0.5, Math.min(vw / (naturalW + 24), vh / (naturalH + 24))));
    applyZoom();
    panX = Math.max(0, (vw - naturalW * zoom - 24) / 2);
    panY = Math.max(0, (vh - naturalH * zoom - 24) / 2);
    applyPan();
    userTouched = true;
    autoCentered = true;
    notifyZoom();
  }

  // Centers the diagram only if the user hasn't already touched it and the
  // viewport has a real size. Called on init and again when the viewport
  // gains size (e.g. the previously-hidden Mermaid card becomes visible).
  function centerIfPossible() {
    if (autoCentered || userTouched || naturalW === 0) return;
    var vw = vp.clientWidth, vh = vp.clientHeight;
    if (vw <= 0 || vh <= 0) return;
    panX = Math.max(0, (vw - naturalW * zoom - 24) / 2);
    panY = Math.max(0, (vh - naturalH * zoom - 24) / 2);
    applyPan();
    autoCentered = true;
  }

  // viewBox is the engine's intended drawing region in user units (= CSS px
  // by default). Both PlantUML/Graphviz and Mermaid encode the natural pixel
  // size there, so trusting viewBox gives a viewport-independent answer.
  function tryInit() {
    var svg = getSvg();
    if (!svg) return false;
    var vb = svg.viewBox && svg.viewBox.baseVal;
    var nw = (vb && vb.width  > 0) ? vb.width  : 0;
    var nh = (vb && vb.height > 0) ? vb.height : 0;
    if (nw <= 0 || nh <= 0) {
      var r = svg.getBoundingClientRect();
      nw = r.width; nh = r.height;
    }
    if (nw <= 0 || nh <= 0) return false;

    // Strip engine-applied attributes so applyZoom's explicit pixel
    // dimensions take effect cleanly (Mermaid sets width="100%" inline).
    svg.removeAttribute('width');
    svg.removeAttribute('height');

    naturalW = nw;
    naturalH = nh;
    applyZoom();
    centerIfPossible();
    if (!revealed) {
      canvas.style.visibility = 'visible';
      revealed = true;
    }
    notifyZoom();
    startPolling(); // keep trying to center until viewport gains size
    return true;
  }

  // Render script calls this once the engine reports the SVG is final.
  // It's authoritative and idempotent — safe to call after MutationObserver
  // already initialised on an intermediate Mermaid SVG.
  window.__ksmDiagramReady = tryInit;

  // Fallback: observe for SVG appearance in case the render script doesn't
  // signal us.
  if (!tryInit()) {
    var mo = new MutationObserver(function() {
      if (tryInit()) mo.disconnect();
    });
    mo.observe(canvas, { childList: true, subtree: true });
  }

  // Additional backup: ResizeObserver also nudges centering if the viewport
  // changes from zero to non-zero. Redundant with pollAndNotify but cheap.
  if (window.ResizeObserver) {
    var ro = new ResizeObserver(function() {
      centerIfPossible();
      if (autoCentered) {
        notifyReady();
        try { ro.disconnect(); } catch (_) {}
      }
    });
    ro.observe(vp);
  }

  window.__ksmStartPan = function()       { vp.style.cursor = 'grabbing'; };
  window.__ksmPan      = function(dx, dy) {
    panX += dx; panY += dy; userTouched = true; clampPan(); applyPan();
  };
  window.__ksmEndPan   = function()       { vp.style.cursor = 'grab'; };
  window.__ksmSetZoom  = function(nz) {
    var cx = vp.clientWidth / 2, cy = vp.clientHeight / 2;
    panX = cx + (panX - cx) * (nz / zoom);
    panY = cy + (panY - cy) * (nz / zoom);
    zoom = Math.min(2, Math.max(0.5, nz));
    userTouched = true;
    applyZoom(); clampPan(); applyPan();
  };

  vp.addEventListener('dblclick', function() { fitToView(); });
})();
""".trimIndent()
    }

    abstract fun render(source: String, dark: Boolean)
}
