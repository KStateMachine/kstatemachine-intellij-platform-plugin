package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * Encodes diagram source text into the URL-safe formats expected by plantuml.com
 * and mermaid.live, using only the JDK's built-in [Deflater] and [Base64].
 */
object DiagramEncoder {

    /**
     * Encodes a PlantUML source string into the format used by
     * `https://www.plantuml.com/plantuml/uml/<encoded>`.
     *
     * Algorithm: UTF-8 → raw DEFLATE (nowrap=true) → PlantUML 6-bit custom base64.
     */
    fun encodePlantUml(source: String): String {
        val compressed = rawDeflate(source.toByteArray(Charsets.UTF_8))
        return encode6bit(compressed)
    }

    /**
     * Encodes a Mermaid source string into the pako format used by
     * `https://mermaid.live/edit#pako:<encoded>`.
     *
     * Algorithm: JSON-wrap → UTF-8 → zlib DEFLATE (with zlib header, matching
     * JavaScript's pako.deflate default) → URL-safe base64 without padding.
     */
    fun encodeMermaid(source: String): String {
        val json = """{"code":${source.toJsonString()},"mermaid":{"theme":"default"}}"""
        val compressed = zlibDeflate(json.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private fun zlibDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, /* nowrap = */ false)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size)
        val buf = ByteArray(1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    // PlantUML's custom base64: 3 bytes → 4 characters from this 64-char alphabet
    private const val PLANTUML_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

    private fun encode6bit(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            sb.append(PLANTUML_ALPHABET[(b0 shr 2) and 0x3F])
            sb.append(PLANTUML_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            sb.append(PLANTUML_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
            sb.append(PLANTUML_ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }
}

private fun String.toJsonString(): String {
    val sb = StringBuilder(length + 8).append('"')
    for (c in this) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
