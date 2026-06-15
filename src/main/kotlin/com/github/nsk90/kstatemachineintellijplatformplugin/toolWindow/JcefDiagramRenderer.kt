package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
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
    private val sliderPanel = JPanel(WrapLayout(FlowLayout.CENTER, 4, 2)).apply {
        add(JLabel("Zoom:"))
        add(zoomSlider)
        add(zoomLabel)
    }
    // ── Cover panel ────────────────────────────────────────────────────────────
    // Opaque Swing panel swapped in via CardLayout to fully replace the JCEF
    // browser visually during render transitions. CardLayout (not JLayeredPane)
    // is used because JCEF browsers don't reliably compose under Swing's
    // Z-order across platforms — the cover would not actually mask the
    // browser. Hiding the browser via CardLayout works on every platform.
    // The CEF render process keeps rendering off-screen while the Swing
    // component is hidden, so when we swap back the new diagram is ready.

    private val coverPanel = JPanel().apply {
        isOpaque = true
        background = coverBackground()
    }

    private fun coverBackground(): Color =
        if (JBColor.isBright()) Color(0xFF, 0xFF, 0xFF) else Color(0x2B, 0x2B, 0x2B)

    private val browserHolderLayout = CardLayout()
    private val browserHolder: JPanel? = browser?.let { b ->
        JPanel(browserHolderLayout).apply {
            add(b.component, BROWSER_CARD)
            add(coverPanel, COVER_CARD)
            browserHolderLayout.show(this, BROWSER_CARD)
        }
    }

    private val wrapper: JPanel? = browserHolder?.let { holder ->
        JPanel(BorderLayout()).apply {
            add(holder, BorderLayout.CENTER)
            add(sliderPanel, BorderLayout.SOUTH)
        }
    }

    // Tracks the Swing-side known size of the JCEF browser. Used as a
    // fallback centering reference when vp.clientWidth in JS is still 0
    // (CEF can lag a few frames behind the Java resize). Updated whenever
    // the browser component is resized.
    @Volatile
    private var browserSwingSize: Dimension = Dimension(0, 0)

    init {
        browser?.component?.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val c = browser.component
                browserSwingSize = Dimension(c.width, c.height)
            }
        })
        // When the wrapper (i.e. the renderer's whole UI) becomes showing
        // — typically after the user switches back to the diagram/playground
        // tab from another tab — the tool window may have been resized while
        // we were hidden. Refresh JS-side state so the layout matches the
        // current viewport again.
        wrapper?.addHierarchyListener { e ->
            val mask = HierarchyEvent.SHOWING_CHANGED.toLong()
            if (e.changeFlags and mask != 0L && wrapper.isShowing) {
                SwingUtilities.invokeLater { refreshAfterTabActivation() }
            }
        }
    }

    private fun refreshAfterTabActivation() {
        val b = browser ?: return
        val c = b.component
        val w = c.width
        val h = c.height
        if (w <= 0 || h <= 0) return
        browserSwingSize = Dimension(w, h)
        b.cefBrowser.executeJavaScript(
            "if (window.__ksmRefreshVp) window.__ksmRefreshVp($w, $h)",
            "", 0,
        )
    }

    protected fun showCover() {
        val show: () -> Unit = {
            coverPanel.background = coverBackground()
            browserHolder?.let { browserHolderLayout.show(it, COVER_CARD) }
            Unit
        }
        if (SwingUtilities.isEventDispatchThread()) show() else SwingUtilities.invokeLater(show)
    }

    private fun hideCover() {
        SwingUtilities.invokeLater {
            browserHolder?.let { browserHolderLayout.show(it, BROWSER_CARD) }
        }
    }

    companion object {
        private const val BROWSER_CARD = "browser"
        private const val COVER_CARD = "cover"
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

    @Volatile
    private var onReadyOnce: (() -> Unit)? = null

    /**
     * Registers a one-shot callback fired the next time the engine signals
     * readiness. Used by the host panels to defer a parent CardLayout swap
     * until the new diagram is fully rendered, eliminating the flash through
     * the gray cover panel during a mode switch.
     */
    fun runOnNextReady(callback: () -> Unit) {
        onReadyOnce = callback
    }

    protected val readySignalQuery: JBCefJSQuery? = browser?.let { b ->
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { _ ->
                hideCover()
                val cb = onReadyOnce
                onReadyOnce = null
                if (cb != null) SwingUtilities.invokeLater(cb)
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
        // Swing-side known size of the browser. If the renderer was never
        // shown (browser size still 0), use a reasonable default so JS can
        // still center the diagram off-screen-correct relative to the
        // viewport it'll eventually have.
        val fallbackVpW = browserSwingSize.width.takeIf { it > 0 } ?: 800
        val fallbackVpH = browserSwingSize.height.takeIf { it > 0 } ?: 600
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

  // Swing-side known viewport size, baked in by Kotlin. Used as a fallback
  // when vp.clientWidth/Height in JS lag behind (CEF often reports 0 for the
  // first few frames after a card switch — the actual Swing component does
  // have a non-zero size).
  var FALLBACK_VP_W = $fallbackVpW;
  var FALLBACK_VP_H = $fallbackVpH;
  function effectiveVpW() { return vp.clientWidth  > 0 ? vp.clientWidth  : FALLBACK_VP_W; }
  function effectiveVpH() { return vp.clientHeight > 0 ? vp.clientHeight : FALLBACK_VP_H; }

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
    var margin = 50, vw = effectiveVpW(), vh = effectiveVpH();
    panX = Math.min(vw - margin, Math.max(margin - cw, panX));
    panY = Math.min(vh - margin, Math.max(margin - ch, panY));
  }

  function fitToView() {
    if (naturalW === 0) return;
    var vw = effectiveVpW(), vh = effectiveVpH();
    zoom = Math.min(2, Math.max(0.5, Math.min(vw / (naturalW + 24), vh / (naturalH + 24))));
    applyZoom();
    panX = Math.max(0, (vw - naturalW * zoom - 24) / 2);
    panY = Math.max(0, (vh - naturalH * zoom - 24) / 2);
    applyPan();
    userTouched = true;
    autoCentered = true;
    notifyZoom();
  }

  // Centers the diagram using the effective viewport size (which falls back
  // to the Swing-known size if vp.clientWidth is still 0). This way init
  // always centers correctly, even if the JCEF viewport hasn't updated yet.
  function centerIfPossible() {
    if (autoCentered || userTouched || naturalW === 0) return;
    var vw = effectiveVpW(), vh = effectiveVpH();
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

  // Called from Java when the renderer's wrapper becomes visible again
  // after a tab switch. The tool window may have been resized while we
  // were hidden; refresh the fallback viewport size and either re-center
  // (if user hasn't pinned a position) or just re-clamp the existing pan.
  window.__ksmRefreshVp = function(w, h) {
    FALLBACK_VP_W = w;
    FALLBACK_VP_H = h;
    if (naturalW <= 0) return;
    if (!userTouched) {
      panX = Math.max(0, (w - naturalW * zoom - 24) / 2);
      panY = Math.max(0, (h - naturalH * zoom - 24) / 2);
      applyPan();
      autoCentered = true;
    } else {
      clampPan();
      applyPan();
    }
  };

  window.__ksmStartPan = function()       { vp.style.cursor = 'grabbing'; };
  window.__ksmPan      = function(dx, dy) {
    panX += dx; panY += dy; userTouched = true; clampPan(); applyPan();
  };
  window.__ksmEndPan   = function()       { vp.style.cursor = 'grab'; };
  window.__ksmSetZoom  = function(nz) {
    var cx = effectiveVpW() / 2, cy = effectiveVpH() / 2;
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
