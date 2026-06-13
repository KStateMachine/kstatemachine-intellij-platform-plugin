package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.diagnostic.thisLogger
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * JCEF-backed PlantUML renderer using the official TeaVM-compiled PlantUML
 * (plantuml.js, ~7.3 MB) together with Viz.js (viz-global.js, ~1.5 MB —
 * Graphviz/dot compiled to WASM). Both assets ship under
 * src/main/resources/plantuml-js/.
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
 */
class PlantUmlJsRenderer : JcefDiagramRenderer("PlantUML") {

    init {
        if (browser != null) registerSchemeIfNeeded()
    }

    override fun render(source: String, dark: Boolean) {
        val b = browser ?: return
        showCover()
        val token = pageCounter.incrementAndGet()
        pendingPages[token] = buildHtml(source, dark)
        b.loadURL("$BASE_URL$PAGE_PATH_PREFIX$token.html")
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
                #canvas { position: absolute; top: 0; left: 0; padding: 12px; }
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
                  if (svg) {
                    captureSvg(svg.outerHTML);
                    window.__ksmDiagramReady && window.__ksmDiagramReady();
                  }
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
