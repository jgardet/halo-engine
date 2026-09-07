package halo.engine

import halo.engine.display.HaloFonts
import java.text.Normalizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Text layout for HSD scenes.
 *
 * HSD has no intrinsic text wrapping: a `text` element renders a single
 * horizontal run that is clipped at the scene edge, so an unwrapped payload
 * becomes a one-liner running off the display. [HsdText] wraps arbitrary text
 * into per-line `text` elements that fit the scene, using the real Dogica
 * glyph metrics from [HaloFonts] instead of character-count heuristics.
 *
 * Characters outside the renderable ASCII range are folded ([fold]) before
 * measuring so that what is measured is what the display will paint.
 */
object HsdText {

    /** Fallback truncation marker; only ASCII is renderable. */
    const val TRUNCATION_MARKER = "..."

    /** Candidate body sizes, largest first. `size` must be a multiple of 8. */
    private val BODY_SIZES = intArrayOf(16, 8)

    private const val DEFAULT_MARGIN_X = 24
    private const val DEFAULT_TOP = 28
    private const val DEFAULT_BOTTOM = 24
    private const val LINE_GAP = 4

    /** Pixel width of [text] at [size] (a multiple of 8) in [fontId]. */
    fun measureWidth(text: String, fontId: Int = 0, size: Int = 8): Int {
        val font = HaloFonts.FONT_LIST.getOrNull(fontId)?.second ?: return 0
        val mult = size / 8
        var width = 0
        for (c in text) {
            val code = c.code
            if (code in font.first..font.last) width += font.glyphs[code - font.first].xAdvance * mult
        }
        return width
    }

    /**
     * Fold [text] to the renderable ASCII range: combining marks are dropped
     * after NFD decomposition, a few common punctuation marks are mapped to
     * ASCII equivalents, and anything else becomes '?'.
     */
    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for (c in Normalizer.normalize(text, Normalizer.Form.NFD)) {
            when {
                c.code in 0x20..0x7E -> out.append(c)
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == '’' || c == '‘' || c == '‚' || c == '‛' -> out.append('\'')
                c == '“' || c == '”' || c == '„' || c == '«' || c == '»' -> out.append('"')
                c == '–' || c == '—' || c == '―' -> out.append('-')
                c == '…' -> out.append(TRUNCATION_MARKER)
                c == '°' -> out.append(" deg")
                c == '\t' || c == '\r' -> out.append(' ')
                else -> out.append('?')
            }
        }
        return out.toString()
    }

    /**
     * Word-wrap [text] into lines that fit [maxWidth] pixels at [size] in
     * [fontId]. Newlines start new lines; words wider than the line are broken
     * mid-word so every returned line measures at most [maxWidth] pixels.
     */
    fun wrap(text: String, maxWidth: Int, fontId: Int = 0, size: Int = 8): List<String> {
        require(maxWidth > 0) { "maxWidth must be positive" }
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            val words = fold(paragraph).split(' ').filter(String::isNotEmpty)
            if (words.isEmpty()) {
                lines.add("")
                continue
            }
            val line = StringBuilder()
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measureWidth(candidate, fontId, size) <= maxWidth) {
                    line.setLength(0)
                    line.append(candidate)
                    continue
                }
                if (line.isNotEmpty()) {
                    lines.add(line.toString())
                    line.setLength(0)
                }
                var rest = word
                while (measureWidth(rest, fontId, size) > maxWidth) {
                    var cut = rest.length
                    while (cut > 1 && measureWidth(rest.substring(0, cut), fontId, size) > maxWidth) cut--
                    lines.add(rest.substring(0, maxOf(cut, 1)))
                    rest = rest.substring(maxOf(cut, 1))
                }
                line.append(rest)
            }
            if (line.isNotEmpty()) lines.add(line.toString())
        }
        return lines
    }

    /**
     * Build a complete HSD document that renders [text] as wrapped lines
     * filling the scene. The largest body size whose wrapped content fits is
     * used; overflowing content is clipped to the visible page and the last
     * visible line ends with [TRUNCATION_MARKER] in [markerColor].
     */
    fun document(
        text: String,
        width: Int = StockHaloLimits.displayWidth,
        height: Int = StockHaloLimits.displayHeight,
        marginX: Int = DEFAULT_MARGIN_X,
        top: Int = DEFAULT_TOP,
        bottom: Int = DEFAULT_BOTTOM,
        fontId: Int = 0,
        color: String = "#FFFFFF",
        markerColor: String = "#B0B0B0",
    ): JsonObject {
        val maxWidth = width - 2 * marginX
        val usableHeight = height - top - bottom
        var size = BODY_SIZES.last()
        var lines = wrap(text, maxWidth, fontId, size)
        var truncated = true
        for (candidate in BODY_SIZES) {
            val maxLines = 1 + (usableHeight - candidate) / (candidate + LINE_GAP)
            val wrapped = wrap(text, maxWidth, fontId, candidate)
            size = candidate
            lines = wrapped
            if (wrapped.size <= maxLines) {
                truncated = false
                break
            }
        }
        val pitch = size + LINE_GAP
        val maxLines = 1 + (usableHeight - size) / pitch
        if (truncated && lines.size > maxLines) {
            val shown = lines.take(maxLines).toMutableList()
            var last = shown.last().trimEnd()
            while (last.isNotEmpty() && measureWidth("$last $TRUNCATION_MARKER", fontId, size) > maxWidth) {
                last = last.dropLast(1).trimEnd()
            }
            shown[shown.lastIndex] = if (last.isEmpty()) TRUNCATION_MARKER else "$last $TRUNCATION_MARKER"
            lines = shown
        }
        val shown = lines.take(maxLines)
        return buildJsonObject {
            put("version", "1.0")
            put("device", "halo")
            put("mode", "runtime")
            put("scene", buildJsonObject {
                put("width", width)
                put("height", height)
                put("bg", "#000000")
                put("children", buildJsonArray {
                    shown.forEachIndexed { index, line ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("x", marginX)
                            put("y", top + index * pitch)
                            put("text", line)
                            put("font", fontId)
                            put("size", size)
                            put("color", if (truncated && index == shown.lastIndex) markerColor else color)
                        })
                    }
                })
            })
        }
    }
}
