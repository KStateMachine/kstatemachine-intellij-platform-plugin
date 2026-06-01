package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

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
 */
class MermaidRenderer {

    private val supported: Boolean = JBCefApp.isSupported()

    private val browser: JBCefBrowser? = if (supported) JBCefBrowser() else null

    /** Last successfully captured SVG markup, updated by the in-page JS bridge. */
    @Volatile
    var currentSvg: String? = null
        private set

    private val svgCaptureQuery: JBCefJSQuery? = browser?.let { b ->
        // The JBCefBrowserBase overload is the recommended one but the
        // JBCefBrowser overload still works and the alternative requires
        // platform-version-specific reflection. Acceptable deprecation cost.
        @Suppress("DEPRECATION", "UnstableApiUsage")
        JBCefJSQuery.create(b).apply {
            addHandler { svg ->
                currentSvg = svg
                null
            }
        }
    }

    private val placeholder: JLabel = JLabel(
        "JCEF runtime is not available in this IDE — Mermaid rendering is disabled.",
        SwingConstants.CENTER,
    )

    val component: JComponent get() = browser?.component ?: placeholder

    fun render(source: String, dark: Boolean) {
        val b = browser ?: return
        val html = buildHtml(source, dark)
        b.loadHTML(html)
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
        val mermaidJs = loadBundledMermaid()
        val theme = if (dark) "dark" else "default"
        val captureCall = svgCaptureQuery?.let { q ->
            // Inject the IPC callback so the rendered SVG flows back to Kotlin.
            "function captureSvg(svg) { ${q.inject("svg")} }"
        } ?: "function captureSvg() {}"

        // Escape the source for safe embedding in a <pre> block. Mermaid reads
        // it as text content, so we only need to escape `&`, `<`, `>`.
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
                html, body { margin: 0; padding: 0; ${bodyStyle(dark)} }
                .mermaid { padding: 12px; }
                .err { padding: 16px; color: ${if (dark) "#ff8585" else "#b00020"};
                       font-family: monospace; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <pre class="mermaid">$escapedSource</pre>
              <div id="err" class="err" style="display:none"></div>
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
