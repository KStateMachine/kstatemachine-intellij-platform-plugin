package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.diagnostic.thisLogger

/**
 * JCEF-backed Mermaid renderer. Loads a tiny HTML page that includes the
 * bundled `mermaid.min.js` (UMD build, exposes `mermaid` as a window global)
 * and renders the supplied source via `mermaid.run`.
 *
 * Source updates and theme toggles regenerate the page through `loadHTML`
 * — Mermaid's runtime API doesn't expose a clean "replace the SVG" entry
 * point for arbitrary new source, and reloading the whole page is fast
 * enough for the use case (sub-100 ms in practice for our diagrams).
 */
class MermaidRenderer : JcefDiagramRenderer("Mermaid") {

    override fun render(source: String, dark: Boolean) {
        val b = browser ?: return
        showCover()
        val notifyZoomCall = zoomSyncQuery?.inject("String(Math.round(zoom * 100))") ?: ""
        b.loadHTML(buildHtml(source, dark, notifyZoomCall))
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
                #canvas { position: absolute; top: 0; left: 0; padding: 12px; }
                /* Off-screen + hidden host where mermaid.run does its work. The
                   raw source text and Mermaid's intermediate render passes
                   never enter the visible viewport, so the first paint inside
                   #canvas is the final SVG that we move there ourselves. */
                #mermaid-host {
                  position: absolute;
                  left: -10000px;
                  top: -10000px;
                  width: 1px;
                  height: 1px;
                  overflow: hidden;
                  visibility: hidden;
                }
                #err { position: fixed; top: 0; left: 0; right: 0; z-index: 9; display: none;
                       padding: 16px; color: ${if (dark) "#ff8585" else "#b00020"};
                       background: ${if (dark) "#2B2B2B" else "#FFFFFF"};
                       font-family: monospace; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <div id="vp"><div id="canvas"></div></div>
              <div id="err"></div>
              <div id="mermaid-host"><pre class="mermaid">$escapedSource</pre></div>
              <script>$mermaidJs</script>
              <script>
                $captureCall
                mermaid.initialize({ startOnLoad: false, theme: '$theme', securityLevel: 'loose' });
                (async () => {
                  try {
                    await mermaid.run({ querySelector: '#mermaid-host .mermaid' });
                    var svgEl = document.querySelector('#mermaid-host svg');
                    if (svgEl) {
                      // Move the final SVG into the visible canvas in a single
                      // DOM operation. Centering/sizing happens synchronously
                      // in __ksmDiagramReady before the browser paints, so
                      // the very first visible frame is the final state.
                      document.getElementById('canvas').appendChild(svgEl);
                      captureSvg(svgEl.outerHTML);
                      window.__ksmDiagramReady && window.__ksmDiagramReady();
                    }
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
