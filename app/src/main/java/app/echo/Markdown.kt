package app.echo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Markdown + LaTeX 的纯 Compose 渲染。
 *
 * 文件分两层：
 * 1. 纯 Kotlin 解析器（MdParser / MathParser 与它们的数据模型）——不依赖 Compose，
 *    可以直接放进 JVM 单测（app/src/test/java/app/echo/MarkdownTest.kt）。
 * 2. Compose 渲染层（MarkdownText 与内部组件）——标题、粗斜体、行内代码、围栏代码、
 *    列表、引用、分隔线、链接、行内公式与块级公式；块级公式用 Canvas + TextMeasurer
 *    自绘，不上 WebView、不加第三方依赖。
 *
 * 流式安全：按「空行 / 非围栏非公式内」切段，稳定段用 remember(segment.text) 缓存解析结果，
 * 只有最后一段随 token 变化；超长单段退化为纯文本直出，避免每 token 全量排版。
 */

// ---------------------------------------------------------------------------
// 一、纯 Kotlin：块级模型
// ---------------------------------------------------------------------------

internal sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Styled(
        val children: List<MdInline>,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
    ) : MdInline
    data class Code(val text: String) : MdInline
    data class Math(val latex: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
}

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Fence(val lang: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Numbered(val start: Int, val items: List<String>) : MdBlock
    data object Rule : MdBlock
    data class Math(val latex: String) : MdBlock
}

internal data class MdSegment(val start: Int, val text: String)

// ---------------------------------------------------------------------------
// 二、纯 Kotlin：Markdown 解析器
// ---------------------------------------------------------------------------

internal object MdParser {

    private const val TICK = "\u0060"

    private val escapes = "\\" + TICK + "*_{}[]()#+-.!~|$><"

    fun parse(src: String): List<MdBlock> {
        val text = normalize(src)
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\n")
        val out = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trim = line.trim()
            if (trim.isEmpty()) { i++; continue }

            val fence = fenceInfo(line)
            if (fence != null) {
                val marker = fence.first
                val lang = fence.second
                val body = ArrayList<String>()
                i++
                while (i < lines.size) {
                    if (isFenceClose(lines[i], marker)) { i++; break }
                    body.add(lines[i])
                    i++
                }
                out.add(MdBlock.Fence(lang, body.joinToString("\n")))
                continue
            }

            val math = readMathBlock(lines, i)
            if (math != null) {
                out.add(MdBlock.Math(math.second))
                i = math.first
                continue
            }

            val level = headingLevel(line)
            if (level > 0) {
                out.add(MdBlock.Heading(level, line.trimStart().substring(level).trim()))
                i++
                continue
            }
            if (isRule(trim)) {
                out.add(MdBlock.Rule)
                i++
                continue
            }
            if (line.trimStart().startsWith(">")) {
                val inner = ArrayList<String>()
                while (i < lines.size) {
                    val candidate = lines[i]
                    val candidateTrim = candidate.trimStart()
                    if (candidateTrim.startsWith(">")) {
                        var rest = candidateTrim.substring(1)
                        if (rest.startsWith(" ")) rest = rest.substring(1)
                        inner.add(rest)
                        i++
                    } else if (candidateTrim.isEmpty() && i + 1 < lines.size && lines[i + 1].trimStart().startsWith(">")) {
                        inner.add("")
                        i++
                    } else {
                        break
                    }
                }
                out.add(MdBlock.Quote(parse(inner.joinToString("\n"))))
                continue
            }
            if (bulletContent(line) != null) {
                i = parseList(lines, i, ordered = false, start = 1, out = out)
                continue
            }
            val ordered = orderedContent(line)
            if (ordered != null) {
                i = parseList(lines, i, ordered = true, start = ordered.first, out = out)
                continue
            }

            val para = ArrayList<String>()
            while (i < lines.size) {
                val candidate = lines[i]
                if (candidate.trim().isEmpty()) break
                if (para.isNotEmpty() && startsBlock(candidate)) break
                para.add(candidate.trim())
                i++
            }
            if (para.isEmpty()) { i++; continue }
            out.add(MdBlock.Paragraph(para.joinToString("\n")))
        }
        return out
    }

    /** 只在不进入代码围栏 / 块级公式的位置切段，保证前缀稳定。 */
    fun splitSegments(src: String): List<MdSegment> {
        val text = normalize(src)
        if (text.isEmpty()) return emptyList()
        val lines = text.split("\n")
        val out = ArrayList<MdSegment>()
        var segStart = 0
        var offset = 0
        var fenceMark: String? = null
        var mathOpen = false
        for (index in lines.indices) {
            val line = lines[index]
            val lineStart = offset
            offset += line.length + 1
            val trim = line.trim()
            if (fenceMark != null) {
                if (trim.startsWith(fenceMark)) fenceMark = null
                continue
            }
            if (mathOpen) {
                if (trim == "$$" || trim.endsWith("\\]") || (trim.startsWith("$$") && trim.length > 2)) mathOpen = false
                continue
            }
            val fence = fenceInfo(line)
            if (fence != null) { fenceMark = fence.first; continue }
            if (trim == "$$" || (trim.startsWith("$$") && !trim.endsWith("$$"))) { mathOpen = true; continue }
            if (trim.startsWith("\\[") && !trim.contains("\\]")) { mathOpen = true; continue }
            if (trim.isEmpty() && index < lines.size - 1) {
                val end = lineStart + line.length + 1
                if (end > segStart && end <= text.length) {
                    out.add(MdSegment(segStart, text.substring(segStart, end)))
                    segStart = end
                }
            }
        }
        if (segStart < text.length) out.add(MdSegment(segStart, text.substring(segStart)))
        if (out.isEmpty()) out.add(MdSegment(0, text))
        return out
    }

    // -- 行内 --

    fun parseInline(src: String): List<MdInline> = parseInlineDepth(src, 0)

    private fun parseInlineDepth(src: String, depth: Int): List<MdInline> {
        if (src.isEmpty()) return emptyList()
        if (depth > 8) return listOf(MdInline.Text(src))
        val out = ArrayList<MdInline>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(MdInline.Text(buf.toString()))
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val next = src[i + 1]
                if (next == '(') {
                    val end = src.indexOf("\\)", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MdInline.Math(src.substring(i + 2, end)))
                        i = end + 2
                        continue
                    }
                }
                if (escapes.indexOf(next) >= 0) {
                    buf.append(next)
                    i += 2
                    continue
                }
                buf.append(c)
                i++
                continue
            }
            if (c == TICK[0]) {
                var run = 0
                while (i + run < src.length && src[i + run] == TICK[0]) run++
                val token = TICK.repeat(run)
                val end = src.indexOf(token, i + run)
                if (end >= 0) {
                    flush()
                    var code = src.substring(i + run, end)
                    if (code.length > 2 && code.startsWith(" ") && code.endsWith(" ")) {
                        code = code.substring(1, code.length - 1)
                    }
                    out.add(MdInline.Code(code))
                    i = end + run
                } else {
                    buf.append(src, i, i + run)
                    i += run
                }
                continue
            }
            if (c == '$') {
                val end = findUnescaped(src, i + 1, '$', 400)
                if (end > i + 1) {
                    val body = src.substring(i + 1, end)
                    if (body.isNotBlank() && !body.startsWith(" ") && !body.endsWith(" ") && !body.contains('\n')) {
                        flush()
                        out.add(MdInline.Math(body))
                        i = end + 1
                        continue
                    }
                }
                buf.append(c)
                i++
                continue
            }
            if (c == '*' || c == '_') {
                val double = i + 1 < src.length && src[i + 1] == c
                if (c == '_' && i > 0 && src[i - 1].isLetterOrDigit()) {
                    buf.append(c)
                    i++
                    continue
                }
                val token = if (double) "" + c + c else "" + c
                val close = findDelimiter(src, i + token.length, token)
                if (close > i + token.length) {
                    val inner = src.substring(i + token.length, close)
                    if (inner.isNotBlank() && !inner.startsWith(" ") && !inner.endsWith(" ")) {
                        flush()
                        val children = parseInlineDepth(inner, depth + 1)
                        out.add(
                            if (double) MdInline.Styled(children, bold = true)
                            else MdInline.Styled(children, italic = true),
                        )
                        i = close + token.length
                        continue
                    }
                }
                buf.append(c)
                i++
                continue
            }
            if (c == '~' && i + 1 < src.length && src[i + 1] == '~') {
                val close = findDelimiter(src, i + 2, "~~")
                if (close > i + 2) {
                    val inner = src.substring(i + 2, close)
                    if (inner.isNotBlank()) {
                        flush()
                        out.add(MdInline.Styled(parseInlineDepth(inner, depth + 1), strike = true))
                        i = close + 2
                        continue
                    }
                }
                buf.append(c)
                i++
                continue
            }
            if (c == '[') {
                val closeLabel = src.indexOf("](", i + 1)
                if (closeLabel > i + 1) {
                    val closeUrl = src.indexOf(')', closeLabel + 2)
                    if (closeUrl > closeLabel + 2) {
                        val label = src.substring(i + 1, closeLabel)
                        val url = src.substring(closeLabel + 2, closeUrl).trim()
                        if (url.isNotEmpty() && !url.contains(' ')) {
                            flush()
                            out.add(MdInline.Link(parseInlineDepth(label, depth + 1), url))
                            i = closeUrl + 1
                            continue
                        }
                    }
                }
                buf.append(c)
                i++
                continue
            }
            buf.append(c)
            i++
        }
        flush()
        return out
    }

    fun plainText(parts: List<MdInline>): String {
        val sb = StringBuilder()
        fun put(list: List<MdInline>) {
            list.forEach { part ->
                when (part) {
                    is MdInline.Text -> sb.append(part.text)
                    is MdInline.Styled -> put(part.children)
                    is MdInline.Code -> sb.append(part.text)
                    is MdInline.Math -> sb.append(MathParser.toUnicodeInline(part.latex))
                    is MdInline.Link -> put(part.children)
                }
            }
        }
        put(parts)
        return sb.toString()
    }

    // -- 行内辅助 --

    private fun findUnescaped(src: String, from: Int, ch: Char, limit: Int): Int {
        val end = (from + limit).coerceAtMost(src.length)
        var i = from
        while (i < end) {
            if (src[i] == ch && (i == 0 || src[i - 1] != '\\')) return i
            i++
        }
        return -1
    }

    private fun findDelimiter(src: String, from: Int, token: String): Int {
        var i = from
        val last = src.length - token.length
        while (i <= last) {
            if (src.startsWith(token, i) && (i == 0 || src[i - 1] != '\\')) {
                if (i > from) return i
            }
            i++
        }
        return -1
    }

    // -- 块级辅助 --

    private fun normalize(src: String): String = src.replace("\r\n", "\n").replace('\r', '\n')

    private fun fenceInfo(line: String): Pair<String, String>? {
        val t = line.trimStart()
        if (t.length < 3) return null
        val marker = if (t.startsWith(TICK + TICK + TICK)) TICK + TICK + TICK
        else if (t.startsWith("~~~")) "~~~" else return null
        val lang = t.substring(marker.length).trim().split(" ").firstOrNull()?.trim().orEmpty()
        return marker to lang
    }

    private fun isFenceClose(line: String, marker: String): Boolean {
        val t = line.trimStart()
        return t.startsWith(marker)
    }

    private fun headingLevel(line: String): Int {
        val t = line.trimStart()
        var count = 0
        while (count < t.length && t[count] == '#') count++
        if (count == 0 || count > 6) return 0
        if (count < t.length && t[count] != ' ') return 0
        return count
    }

    private fun isRule(trim: String): Boolean {
        if (trim.length < 3) return false
        val c = trim[0]
        if (c != '-' && c != '*' && c != '_') return false
        for (ch in trim) if (ch != c) return false
        return true
    }

    private fun bulletContent(line: String): String? {
        val t = line.trimStart()
        if (t.length < 2) return null
        val c = t[0]
        if (c != '-' && c != '*' && c != '+') return null
        if (t[1] != ' ') return null
        return t.substring(2)
    }

    private fun orderedContent(line: String): Pair<Int, String>? {
        val t = line.trimStart()
        var i = 0
        while (i < t.length && t[i].isDigit()) i++
        if (i == 0 || i > 9 || i >= t.length) return null
        val dot = t[i]
        if (dot != '.' && dot != ')') return null
        if (i + 1 >= t.length || t[i + 1] != ' ') return null
        val start = t.substring(0, i).toIntOrNull() ?: return null
        return start to t.substring(i + 2)
    }

    private fun startsBlock(line: String): Boolean {
        val trim = line.trim()
        if (trim.isEmpty()) return true
        if (fenceInfo(line) != null) return true
        if (headingLevel(line) > 0) return true
        if (trim.startsWith(">")) return true
        if (bulletContent(line) != null) return true
        if (orderedContent(line) != null) return true
        if (isRule(trim)) return true
        if (trim == "$$" || trim.startsWith("$$")) return true
        if (trim.startsWith("\\[")) return true
        return false
    }

    private fun parseList(
        lines: List<String>,
        from: Int,
        ordered: Boolean,
        start: Int,
        out: MutableList<MdBlock>,
    ): Int {
        val items = ArrayList<String>()
        var i = from
        while (i < lines.size) {
            val line = lines[i]
            val content = if (ordered) orderedContent(line)?.second else bulletContent(line)
            if (content == null) break
            var item = content.trim()
            i++
            while (i < lines.size) {
                val candidate = lines[i]
                val t = candidate.trim()
                if (t.isEmpty()) break
                if (bulletContent(candidate) != null) break
                if (orderedContent(candidate) != null) break
                if (startsBlock(candidate)) break
                item = item + "\n" + t
                i++
            }
            items.add(item)
            var next = i
            while (next < lines.size && lines[next].trim().isEmpty()) next++
            if (next >= lines.size) break
            val nextLine = lines[next]
            val same = if (ordered) orderedContent(nextLine) != null else bulletContent(nextLine) != null
            if (!same) break
            i = next
        }
        if (items.isNotEmpty()) {
            out.add(if (ordered) MdBlock.Numbered(start, items) else MdBlock.Bullet(items))
        }
        return i
    }

    private fun readMathBlock(lines: List<String>, from: Int): Pair<Int, String>? {
        val trim = lines[from].trim()
        if (trim.startsWith("$$")) {
            val rest = trim.substring(2).trim()
            if (rest.endsWith("$$") && rest.length > 2) {
                return from + 1 to rest.substring(0, rest.length - 2).trim()
            }
            if (rest.isNotEmpty() && trim.endsWith("$$")) {
                return from + 1 to rest.removeSuffix("$$").trim()
            }
            val body = ArrayList<String>()
            var i = from + 1
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t == "$$" || t.endsWith("$$")) {
                    if (t != "$$") body.add(t.removeSuffix("$$"))
                    return i + 1 to body.joinToString("\n").trim()
                }
                body.add(lines[i])
                i++
            }
            return lines.size to body.joinToString("\n").trim()
        }
        if (trim.startsWith("\\[")) {
            val rest = trim.substring(2).trim()
            if (rest.endsWith("\\]") && rest.length > 2) {
                return from + 1 to rest.substring(0, rest.length - 2).trim()
            }
            val body = ArrayList<String>()
            var i = from + 1
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.endsWith("\\]")) {
                    body.add(t.removeSuffix("\\]"))
                    return i + 1 to body.joinToString("\n").trim()
                }
                body.add(lines[i])
                i++
            }
            return lines.size to body.joinToString("\n").trim()
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// 三、纯 Kotlin：LaTeX 解析与行内降级
// ---------------------------------------------------------------------------

internal enum class MathKind { Normal, Letter, Text, Big, Operator }

internal sealed interface MathNode {
    data class Run(val text: String, val kind: MathKind = MathKind.Normal) : MathNode
    data class Row(val children: List<MathNode>) : MathNode
    data class Frac(val num: List<MathNode>, val den: List<MathNode>) : MathNode
    data class Sqrt(val index: List<MathNode>?, val body: List<MathNode>) : MathNode
    data class Script(
        val base: List<MathNode>,
        val sup: List<MathNode>? = null,
        val sub: List<MathNode>? = null,
    ) : MathNode
    data class Big(
        val symbol: String,
        val above: List<MathNode>? = null,
        val below: List<MathNode>? = null,
    ) : MathNode
    data class Space(val em: Float) : MathNode
}

internal object MathParser {

    private val greek: Map<String, String> = mapOf(
        "alpha" to "\u03b1", "beta" to "\u03b2", "gamma" to "\u03b3", "delta" to "\u03b4",
        "epsilon" to "\u03f5", "varepsilon" to "\u03b5", "zeta" to "\u03b6", "eta" to "\u03b7",
        "theta" to "\u03b8", "vartheta" to "\u03d1", "iota" to "\u03b9", "kappa" to "\u03ba",
        "lambda" to "\u03bb", "mu" to "\u03bc", "nu" to "\u03bd", "xi" to "\u03be",
        "omicron" to "\u03bf", "pi" to "\u03c0", "varpi" to "\u03d6", "rho" to "\u03c1",
        "varrho" to "\u03f1", "sigma" to "\u03c3", "varsigma" to "\u03c2", "tau" to "\u03c4",
        "upsilon" to "\u03c5", "phi" to "\u03d5", "varphi" to "\u03c6", "chi" to "\u03c7",
        "psi" to "\u03c8", "omega" to "\u03c9",
        "Gamma" to "\u0393", "Delta" to "\u0394", "Theta" to "\u0398", "Lambda" to "\u039b",
        "Xi" to "\u039e", "Pi" to "\u03a0", "Sigma" to "\u03a3", "Upsilon" to "\u03a5",
        "Phi" to "\u03a6", "Psi" to "\u03a8", "Omega" to "\u03a9",
    )

    private val operators: Map<String, String> = mapOf(
        "times" to "\u00d7", "div" to "\u00f7", "cdot" to "\u00b7", "pm" to "\u00b1",
        "mp" to "\u2213", "le" to "\u2264", "leq" to "\u2264", "ge" to "\u2265",
        "geq" to "\u2265", "neq" to "\u2260", "ne" to "\u2260", "approx" to "\u2248",
        "equiv" to "\u2261", "sim" to "\u223c", "propto" to "\u221d", "to" to "\u2192",
        "rightarrow" to "\u2192", "leftarrow" to "\u2190", "Rightarrow" to "\u21d2",
        "Leftarrow" to "\u21d0", "leftrightarrow" to "\u2194", "infty" to "\u221e",
        "partial" to "\u2202", "nabla" to "\u2207", "in" to "\u2208", "notin" to "\u2209",
        "subset" to "\u2282", "subseteq" to "\u2286", "cup" to "\u222a", "cap" to "\u2229",
        "forall" to "\u2200", "exists" to "\u2203", "emptyset" to "\u2205", "angle" to "\u2220",
        "degree" to "\u00b0", "prime" to "\u2032", "dots" to "\u2026", "ldots" to "\u2026",
        "cdots" to "\u22ef", "ast" to "\u2217", "star" to "\u22c6", "circ" to "\u2218",
        "bullet" to "\u2022", "neg" to "\u00ac", "land" to "\u2227", "lor" to "\u2228",
        "therefore" to "\u2234", "because" to "\u2235", "perp" to "\u22a5", "parallel" to "\u2225",
    )

    private val bigs: Map<String, String> = mapOf(
        "sum" to "\u2211", "prod" to "\u220f", "coprod" to "\u2210",
        "int" to "\u222b", "iint" to "\u222c", "iiint" to "\u222d", "oint" to "\u222e",
        "lim" to "lim", "max" to "max", "min" to "min", "sup" to "sup", "inf" to "inf",
    )

    fun parse(src: String): List<MathNode> {
        val clean = src.replace("\r", " ").replace("\n", " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length > 4000) return listOf(MathNode.Run(clean))
        return Parser(clean).sequence(stopAtBrace = false)
    }

    fun toUnicodeInline(src: String): String {
        val nodes = parse(src)
        val sb = StringBuilder()
        renderInline(nodes, sb)
        return sb.toString()
    }

    private fun renderInline(nodes: List<MathNode>, sb: StringBuilder) {
        nodes.forEach { node ->
            when (node) {
                is MathNode.Run -> sb.append(node.text)
                is MathNode.Row -> renderInline(node.children, sb)
                is MathNode.Space -> if (node.em > 0f) sb.append(' ')
                is MathNode.Frac -> {
                    val num = inlineOf(node.num)
                    val den = inlineOf(node.den)
                    sb.append(if (num.length > 1) "(" + num + ")" else num)
                    sb.append('\u2044')
                    sb.append(if (den.length > 1) "(" + den + ")" else den)
                }
                is MathNode.Sqrt -> {
                    val body = inlineOf(node.body)
                    sb.append('\u221a')
                    sb.append(if (body.length > 1) "(" + body + ")" else body)
                }
                is MathNode.Script -> {
                    renderInline(node.base, sb)
                    node.sup?.let {
                        val text = inlineOf(it)
                        val sup = superscript(text)
                        if (sup != null) sb.append(sup) else sb.append("^(").append(text).append(')')
                    }
                    node.sub?.let {
                        val text = inlineOf(it)
                        val sub = subscript(text)
                        if (sub != null) sb.append(sub) else sb.append("_(").append(text).append(')')
                    }
                }
                is MathNode.Big -> {
                    sb.append(node.symbol)
                    val pieces = ArrayList<String>()
                    node.below?.let { pieces.add(inlineOf(it)) }
                    node.above?.let { pieces.add(inlineOf(it)) }
                    if (pieces.isNotEmpty()) sb.append('(').append(pieces.joinToString("\u2192")).append(')')
                }
            }
        }
    }

    private fun inlineOf(nodes: List<MathNode>): String {
        val sb = StringBuilder()
        renderInline(nodes, sb)
        return sb.toString().trim()
    }

    private fun superscript(text: String): String? {
        if (text.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in text) {
            val mapped = when (ch) {
                '0' -> "\u2070"; '1' -> "\u00b9"; '2' -> "\u00b2"; '3' -> "\u00b3"; '4' -> "\u2074"
                '5' -> "\u2075"; '6' -> "\u2076"; '7' -> "\u2077"; '8' -> "\u2078"; '9' -> "\u2079"
                '+' -> "\u207a"; '-' -> "\u207b"; '=' -> "\u207c"; '(' -> "\u207d"; ')' -> "\u207e"
                'n' -> "\u207f"; 'i' -> "\u2071"; 'x' -> "\u02e3"; 'a' -> "\u1d43"; 'b' -> "\u1d47"
                'c' -> "\u1d9c"; 'd' -> "\u1d48"; 'e' -> "\u1d49"; 'k' -> "\u1d4f"; 'm' -> "\u1d50"
                'p' -> "\u1d56"; 't' -> "\u1d57"; 'u' -> "\u1d58"; 'v' -> "\u1d5b"; 'w' -> "\u02b7"
                else -> return null
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    private fun subscript(text: String): String? {
        if (text.isEmpty()) return null
        val sb = StringBuilder()
        for (ch in text) {
            val mapped = when (ch) {
                '0' -> "\u2080"; '1' -> "\u2081"; '2' -> "\u2082"; '3' -> "\u2083"; '4' -> "\u2084"
                '5' -> "\u2085"; '6' -> "\u2086"; '7' -> "\u2087"; '8' -> "\u2088"; '9' -> "\u2089"
                '+' -> "\u208a"; '-' -> "\u208b"; '=' -> "\u208c"; '(' -> "\u208d"; ')' -> "\u208e"
                'a' -> "\u2090"; 'e' -> "\u2091"; 'i' -> "\u1d62"; 'j' -> "\u2c7c"; 'k' -> "\u2096"
                'n' -> "\u2099"; 'o' -> "\u2092"; 'p' -> "\u209a"; 'r' -> "\u1d63"; 's' -> "\u209b"
                't' -> "\u209c"; 'u' -> "\u1d64"; 'v' -> "\u1d65"; 'x' -> "\u2093"
                else -> return null
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    private class Parser(private val s: String) {

        private var i = 0

        fun sequence(stopAtBrace: Boolean): List<MathNode> {
            val out = ArrayList<MathNode>()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '}' && stopAtBrace -> { i++; break }
                    c == '{' -> {
                        i++
                        out.add(MathNode.Row(sequence(stopAtBrace = true)))
                    }
                    c == ' ' || c == '\n' || c == '\t' -> { i++; space(out, .18f) }
                    c == '\\' -> out.add(command())
                    c == '^' || c == '_' -> script(out, c)
                    c == '\'' -> {
                        i++
                        val primes = StringBuilder("\u2032")
                        while (i < s.length && s[i] == '\'') { primes.append('\u2032'); i++ }
                        scriptText(out, primes.toString())
                    }
                    c.isDigit() -> {
                        val start = i
                        while (i < s.length && s[i].isDigit()) i++
                        out.add(MathNode.Run(s.substring(start, i)))
                    }
                    c.isLetter() && c.code < 128 -> {
                        val start = i
                        while (i < s.length && s[i].isLetter() && s[i].code < 128) i++
                        out.add(MathNode.Run(s.substring(start, i), MathKind.Letter))
                    }
                    c in "+-=<>|/" -> {
                        space(out, .14f)
                        i++
                        out.add(MathNode.Run(c.toString(), MathKind.Operator))
                        space(out, .14f)
                    }
                    c == ',' || c == ';' -> {
                        i++
                        out.add(MathNode.Run(c.toString(), MathKind.Operator))
                        space(out, .22f)
                    }
                    else -> { i++; out.add(MathNode.Run(c.toString())) }
                }
            }
            return out
        }

        private fun space(out: MutableList<MathNode>, em: Float) {
            val last = out.lastOrNull()
            if (last is MathNode.Space) out[out.size - 1] = MathNode.Space(last.em + em)
            else out.add(MathNode.Space(em))
        }

        private fun command(): MathNode {
            i++
            if (i >= s.length) return MathNode.Run("\\")
            if (!s[i].isLetter()) {
                val ch = s[i]
                i++
                return when (ch) {
                    ',', ':', ';' -> MathNode.Space(.18f)
                    '!' -> MathNode.Space(0f)
                    ' ' -> MathNode.Space(.3f)
                    '\\' -> MathNode.Space(.5f)
                    else -> MathNode.Run(ch.toString())
                }
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when {
                name == "frac" || name == "dfrac" || name == "tfrac" -> {
                    MathNode.Frac(argument(), argument())
                }
                name == "sqrt" -> MathNode.Sqrt(bracketArgument(), argument())
                bigs.containsKey(name) -> big(name)
                name == "text" || name == "mathrm" || name == "mathbf" || name == "mathit" ||
                    name == "operatorname" || name == "mbox" -> {
                    val raw = rawArgument()
                    MathNode.Run(raw, if (name == "mathbf") MathKind.Big else MathKind.Text)
                }
                greek.containsKey(name) -> MathNode.Run(greek.getValue(name))
                operators.containsKey(name) -> MathNode.Run(operators.getValue(name), MathKind.Operator)
                name == "quad" -> MathNode.Space(1f)
                name == "qquad" -> MathNode.Space(1.8f)
                name == "space" -> MathNode.Space(.3f)
                name == "left" || name == "right" -> {
                    skipSpaces()
                    if (i >= s.length) MathNode.Run("")
                    else if (s[i] == '\\') command() else { val ch = s[i]; i++; MathNode.Run(ch.toString()) }
                }
                name == "begin" || name == "end" -> { rawArgument(); MathNode.Run("") }
                name == "displaystyle" || name == "textstyle" || name == "scriptstyle" -> MathNode.Run("")
                else -> MathNode.Run(name)
            }
        }

        private fun bracketArgument(): List<MathNode>? {
            skipSpaces()
            if (i >= s.length || s[i] != '[') return null
            i++
            val start = i
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '{') depth++
                else if (c == '}') depth--
                else if (c == ']' && depth <= 0) break
                i++
            }
            val raw = s.substring(start, i)
            if (i < s.length) i++
            return MathParser.parse(raw)
        }

        private fun big(name: String): MathNode {
            val symbol = bigs.getValue(name)
            var above: List<MathNode>? = null
            var below: List<MathNode>? = null
            while (true) {
                skipSpaces()
                if (i >= s.length) break
                val c = s[i]
                if (c != '^' && c != '_') break
                i++
                val arg = argument()
                if (c == '^') above = arg else below = arg
            }
            return MathNode.Big(symbol, above, below)
        }

        private fun script(out: MutableList<MathNode>, marker: Char) {
            i++
            val arg = argument()
            if (out.isEmpty()) {
                out.add(MathNode.Script(emptyList(), if (marker == '^') arg else null, if (marker == '_') arg else null))
                return
            }
            val base = out.removeAt(out.size - 1)
            val previous = base as? MathNode.Script
            out.add(
                if (previous != null) {
                    previous.copy(
                        sup = if (marker == '^') arg else previous.sup,
                        sub = if (marker == '_') arg else previous.sub,
                    )
                } else {
                    MathNode.Script(
                        listOf(base),
                        if (marker == '^') arg else null,
                        if (marker == '_') arg else null,
                    )
                },
            )
        }

        private fun scriptText(out: MutableList<MathNode>, text: String) {
            val node = MathNode.Run(text)
            if (out.isEmpty()) {
                out.add(MathNode.Script(emptyList(), listOf(node)))
                return
            }
            val base = out.removeAt(out.size - 1)
            val previous = base as? MathNode.Script
            out.add(
                if (previous != null) previous.copy(sup = (previous.sup ?: emptyList()) + node)
                else MathNode.Script(listOf(base), listOf(node)),
            )
        }

        private fun argument(): List<MathNode> {
            skipSpaces()
            if (i >= s.length) return emptyList()
            if (s[i] == '{') {
                i++
                return sequence(stopAtBrace = true)
            }
            if (s[i] == '\\') return listOf(command())
            if (s[i].isDigit()) {
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                return listOf(MathNode.Run(s.substring(start, i)))
            }
            if (s[i].isLetter() && s[i].code < 128) {
                i++
                return listOf(MathNode.Run(s[i - 1].toString(), MathKind.Letter))
            }
            val ch = s[i]
            i++
            return listOf(MathNode.Run(ch.toString()))
        }

        private fun rawArgument(): String {
            skipSpaces()
            if (i >= s.length) return ""
            if (s[i] != '{') {
                if (s[i] == '\\') {
                    val node = command()
                    return when (node) {
                        is MathNode.Run -> node.text
                        else -> ""
                    }
                }
                val ch = s[i]
                i++
                return ch.toString()
            }
            i++
            val sb = StringBuilder()
            var depth = 1
            while (i < s.length && depth > 0) {
                val ch = s[i]
                when (ch) {
                    '{' -> { depth++; sb.append(ch) }
                    '}' -> { depth--; if (depth > 0) sb.append(ch) }
                    '\\' -> {
                        i++
                        if (i < s.length) {
                            val next = s[i]
                            if (next == ' ' || next == '\\' || next == '_' || next == '^') sb.append(next)
                            else { sb.append('\\'); sb.append(next) }
                        }
                    }
                    else -> sb.append(ch)
                }
                i++
            }
            return sb.toString()
        }

        private fun skipSpaces() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\t')) i++
        }
    }
}

// ---------------------------------------------------------------------------
// 四、Compose 渲染：Markdown 文本
// ---------------------------------------------------------------------------

private data class MdCaret(val alpha: Float, val color: Color)

@Composable
internal fun MarkdownText(text: String, live: Boolean = false, accent: Color = C.Blue) {
    if (text.isBlank()) return
    val base = TextStyle(
        color = C.Ivory,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        fontFamily = FontFamily.Default,
    )
    if (!live) {
        val blocks = remember(text) { MdParser.parse(text) }
        Column(Modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                MdBlockView(
                    block = block,
                    first = index == 0,
                    caret = false,
                    accent = accent,
                    base = base,
                )
            }
        }
        return
    }
    val segments = remember(text) { MdParser.splitSegments(text) }
    Column(Modifier.fillMaxWidth()) {
        segments.forEachIndexed { segmentIndex, segment ->
            key(segment.start) {
                if (segment.text.length > 8000) {
                    PlainStreamText(segment.text, accent)
                } else {
                    val blocks = remember(segment.text) { MdParser.parse(segment.text) }
                    val lastSegment = segmentIndex == segments.lastIndex
                    blocks.forEachIndexed { blockIndex, block ->
                        MdBlockView(
                            block = block,
                            first = blockIndex == 0 && segmentIndex == 0,
                            caret = lastSegment && blockIndex == blocks.lastIndex,
                            accent = accent,
                            base = base,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlainStreamText(text: String, accent: Color) {
    val alpha = blinkAlpha()
    val annotated = remember(text, alpha) {
        buildAnnotatedString {
            append(text)
            withStyle(SpanStyle(color = accent.copy(alpha = alpha))) { append("\u258d") }
        }
    }
    Text(annotated, color = C.Ivory, fontSize = 16.sp, lineHeight = 26.sp, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun blinkAlpha(): Float {
    val value = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            value.animateTo(.12f, tween(110))
            delay(500)
            value.animateTo(1f, tween(110))
        }
    }
    return value.value
}

@Composable
private fun MdBlockView(
    block: MdBlock,
    first: Boolean,
    caret: Boolean,
    accent: Color,
    base: TextStyle,
) {
    val top = if (first) 0.dp else 10.dp
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 21.sp
                2 -> 19.sp
                3 -> 17.sp
                else -> 16.sp
            }
            val style = base.copy(
                fontSize = size,
                lineHeight = (size.value * 1.4f).sp,
                fontWeight = FontWeight.SemiBold,
            )
            val parts = remember(block.text) { MdParser.parseInline(block.text) }
            val caretMark = if (caret) MdCaret(blinkAlpha(), accent) else null
            Text(
                text = buildInlineText(parts, caretMark),
                style = style,
                modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 16.dp, bottom = 2.dp),
            )
        }

        is MdBlock.Paragraph -> {
            val parts = remember(block.text) { MdParser.parseInline(block.text) }
            val caretMark = if (caret) MdCaret(blinkAlpha(), accent) else null
            Text(
                text = buildInlineText(parts, caretMark),
                style = base,
                modifier = Modifier.fillMaxWidth().padding(top = top),
            )
        }

        is MdBlock.Fence -> {
            Surface(
                color = C.Panel,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 12.dp),
            ) {
                Column(Modifier.padding(vertical = 9.dp)) {
                    if (block.lang.isNotBlank()) {
                        Text(
                            block.lang.uppercase(),
                            color = C.Muted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                    }
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp)) {
                        Text(
                            block.code,
                            color = C.Ivory,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = false,
                        )
                    }
                    if (caret) {
                        Text(
                            "\u258d",
                            color = accent.copy(alpha = blinkAlpha()),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        is MdBlock.Quote -> {
            Row(
                Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 12.dp).height(IntrinsicSize.Min),
            ) {
                Box(
                    Modifier.width(2.dp).fillMaxHeight().background(C.Muted.copy(alpha = .5f)),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    block.blocks.forEachIndexed { index, child ->
                        MdBlockView(
                            block = child,
                            first = index == 0,
                            caret = caret && index == block.blocks.lastIndex,
                            accent = accent,
                            base = base,
                        )
                    }
                }
            }
        }

        is MdBlock.Bullet -> {
            Column(
                Modifier.fillMaxWidth().padding(top = top),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                block.items.forEachIndexed { index, item ->
                    ListRow(
                        marker = "\u2022",
                        item = item,
                        caret = caret && index == block.items.lastIndex,
                        accent = accent,
                        base = base,
                    )
                }
            }
        }

        is MdBlock.Numbered -> {
            Column(
                Modifier.fillMaxWidth().padding(top = top),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                block.items.forEachIndexed { index, item ->
                    ListRow(
                        marker = (block.start + index).toString() + ".",
                        item = item,
                        caret = caret && index == block.items.lastIndex,
                        accent = accent,
                        base = base,
                    )
                }
            }
        }

        MdBlock.Rule -> {
            Box(
                Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 12.dp, bottom = 4.dp)
                    .height(1.dp).background(C.Hair),
            )
        }

        is MdBlock.Math -> {
            MathBlockView(
                latex = block.latex,
                first = first,
                caret = caret,
                accent = accent,
            )
        }
    }
}

@Composable
private fun ListRow(
    marker: String,
    item: String,
    caret: Boolean,
    accent: Color,
    base: TextStyle,
) {
    val parts = remember(item) { MdParser.parseInline(item) }
    val caretMark = if (caret) MdCaret(blinkAlpha(), accent) else null
    Row(Modifier.fillMaxWidth()) {
        Text(
            marker,
            color = accent,
            fontSize = 16.sp,
            lineHeight = 26.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = buildInlineText(parts, caretMark),
            style = base,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun buildInlineText(parts: List<MdInline>, caret: MdCaret?): AnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = C.Blue, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        fun put(list: List<MdInline>, span: SpanStyle) {
            list.forEach { part ->
                when (part) {
                    is MdInline.Text -> withStyle(span) { append(part.text) }
                    is MdInline.Styled -> {
                        var next = span
                        if (part.bold) next = next.merge(SpanStyle(fontWeight = FontWeight.SemiBold))
                        if (part.italic) next = next.merge(SpanStyle(fontStyle = FontStyle.Italic))
                        if (part.strike) next = next.merge(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        put(part.children, next)
                    }
                    is MdInline.Code -> withStyle(
                        span.merge(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                background = C.PanelHigh,
                            ),
                        ),
                    ) { append(part.text) }
                    is MdInline.Math -> withStyle(
                        span.merge(
                            SpanStyle(
                                fontFamily = FontFamily.Serif,
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                            ),
                        ),
                    ) { append(MathParser.toUnicodeInline(part.latex)) }
                    is MdInline.Link -> {
                        if (part.url.isBlank()) {
                            put(part.children, span)
                        } else {
                            withLink(LinkAnnotation.Url(part.url, linkStyles)) {
                                put(part.children, span)
                            }
                        }
                    }
                }
            }
        }
        put(parts, SpanStyle())
        if (caret != null) {
            withStyle(SpanStyle(color = caret.color.copy(alpha = caret.alpha))) { append("\u258d") }
        }
    }
}

// ---------------------------------------------------------------------------
// 五、Compose 渲染：块级公式（Canvas 自绘基线排版）
// ---------------------------------------------------------------------------

private class MBody(
    val width: Float,
    val ascent: Float,
    val descent: Float,
    val draw: (DrawScope, Float, Float) -> Unit,
) {
    val height: Float get() = ascent + descent
}

private class MathLayout(
    private val measurer: TextMeasurer,
    private val base: Float,
    private val color: Color,
    private val density: Density,
) {

    private val empty = MBody(0f, 0f, 0f) { _, _, _ -> }

    fun build(latex: String): MBody = row(MathParser.parse(latex), 1f)

    private fun row(nodes: List<MathNode>, scale: Float): MBody {
        val children = ArrayList<MBody>(nodes.size)
        nodes.forEach { node ->
            val body = one(node, scale)
            if (body.width > 0.01f || body.ascent > 0.01f || body.descent > 0.01f) children.add(body)
        }
        if (children.isEmpty()) return empty
        var width = 0f
        var ascent = 0f
        var descent = 0f
        children.forEach {
            width += it.width
            ascent = max(ascent, it.ascent)
            descent = max(descent, it.descent)
        }
        return MBody(width, ascent, descent) { canvas, x, baseline ->
            var cursor = x
            children.forEach {
                it.draw(canvas, cursor, baseline)
                cursor += it.width
            }
        }
    }

    private fun one(node: MathNode, scale: Float): MBody = when (node) {
        is MathNode.Run -> text(node.text, scale, node.kind)
        is MathNode.Row -> row(node.children, scale)
        is MathNode.Frac -> frac(node, scale)
        is MathNode.Sqrt -> sqrt(node, scale)
        is MathNode.Script -> script(node, scale)
        is MathNode.Big -> big(node, scale)
        is MathNode.Space -> MBody((node.em * base * scale).coerceAtLeast(0f), 0f, 0f) { _, _, _ -> }
    }

    private fun text(raw: String, scale: Float, kind: MathKind): MBody {
        if (raw.isEmpty()) return empty
        val style = TextStyle(
            color = color,
            fontSize = (base * scale / (density.density * density.fontScale)).sp,
            fontFamily = FontFamily.Serif,
            fontStyle = if (kind == MathKind.Letter) FontStyle.Italic else FontStyle.Normal,
            fontWeight = when (kind) {
                MathKind.Big -> FontWeight.Medium
                MathKind.Operator -> FontWeight.Normal
                else -> FontWeight.Normal
            },
        )
        val result = measurer.measure(
            text = AnnotatedString(raw),
            style = style,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(),
        )
        val width = result.size.width.toFloat()
        val baseline = result.getLineBaseline(0)
        val height = result.size.height.toFloat()
        val ascent = baseline
        val descent = (height - baseline).coerceAtLeast(0f)
        return MBody(width, ascent, descent) { canvas, x, y ->
            with(canvas) { drawText(result, topLeft = Offset(x, y - baseline)) }
        }
    }

    private fun frac(node: MathNode.Frac, scale: Float): MBody {
        val num = row(node.num, scale * .96f)
        val den = row(node.den, scale * .96f)
        val gap = base * scale * .10f
        val thick = (base * scale * .05f).coerceAtLeast(1f)
        val axis = base * scale * .26f
        val width = max(num.width, den.width) + base * scale * .18f
        val numBaseline = -axis - thick / 2f - gap - num.descent
        val denBaseline = -axis + thick / 2f + gap + den.ascent
        val ascent = axis + thick / 2f + gap + num.height
        val descent = max(-axis + thick / 2f + gap + den.height, base * scale * .10f)
        return MBody(width, ascent, descent) { canvas, x, baseline ->
            val left = x + (width - max(num.width, den.width)) / 2f
            canvas.drawLine(
                color = color.copy(alpha = .9f),
                start = Offset(left, baseline - axis),
                end = Offset(left + max(num.width, den.width), baseline - axis),
                strokeWidth = thick,
            )
            num.draw(canvas, x + (width - num.width) / 2f, baseline + numBaseline)
            den.draw(canvas, x + (width - den.width) / 2f, baseline + denBaseline)
        }
    }

    private fun sqrt(node: MathNode.Sqrt, scale: Float): MBody {
        val body = row(node.body, scale)
        val index = node.index?.let { row(it, scale * .55f) }
        val pad = base * scale * .09f
        val indexWidth = index?.width ?: 0f
        val radicalWidth = base * scale * .5f
        val stroke = (base * scale * .055f).coerceAtLeast(1f)
        val ascent = body.ascent + pad * 1.6f
        val descent = body.descent + pad * .3f
        val width = indexWidth + radicalWidth + body.width + base * scale * .18f
        return MBody(width, ascent, descent) { canvas, x, baseline ->
            val start = x + indexWidth
            val top = baseline - ascent + stroke
            val path = Path().apply {
                moveTo(start + radicalWidth * .05f, baseline - body.ascent * .34f)
                lineTo(start + radicalWidth * .34f, baseline - body.ascent * .08f)
                lineTo(start + radicalWidth, top)
                lineTo(x + width, top)
            }
            canvas.drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            body.draw(canvas, start + radicalWidth, baseline)
            index?.let { it.draw(canvas, x, baseline - ascent + it.height) }
        }
    }

    private fun script(node: MathNode.Script, scale: Float): MBody {
        val baseBody = row(node.base, scale)
        val sup = node.sup?.let { row(it, scale * .68f) }
        val sub = node.sub?.let { row(it, scale * .68f) }
        val shiftUp = base * scale * .42f
        val shiftDown = base * scale * .30f
        val tailWidth = max(sup?.width ?: 0f, sub?.width ?: 0f)
        val width = baseBody.width + tailWidth + base * scale * .03f
        val ascent = max(baseBody.ascent, shiftUp + (sup?.ascent ?: 0f) + (sup?.descent ?: 0f))
        val descent = max(baseBody.descent, (sub?.height ?: 0f) - shiftDown)
        return MBody(width, ascent, descent) { canvas, x, baseline ->
            baseBody.draw(canvas, x, baseline)
            sup?.draw(canvas, x + baseBody.width, baseline - shiftUp)
            sub?.draw(canvas, x + baseBody.width, baseline + shiftDown)
        }
    }

    private fun big(node: MathNode.Big, scale: Float): MBody {
        val symbol = text(node.symbol, scale * 1.5f, MathKind.Big)
        val above = node.above?.let { row(it, scale * .6f) }
        val below = node.below?.let { row(it, scale * .6f) }
        val gap = base * scale * .08f
        val innerWidth = max(symbol.width, max(above?.width ?: 0f, below?.width ?: 0f)) + base * scale * .12f
        val ascent = (above?.height ?: 0f) + (if (above != null) gap else 0f) + symbol.ascent
        val descent = symbol.descent + (if (below != null) gap else 0f) + (below?.height ?: 0f)
        return MBody(innerWidth, ascent, descent) { canvas, x, baseline ->
            symbol.draw(canvas, x + (innerWidth - symbol.width) / 2f, baseline)
            above?.draw(canvas, x + (innerWidth - above.width) / 2f, baseline - symbol.ascent - gap - above.descent)
            below?.draw(canvas, x + (innerWidth - below.width) / 2f, baseline + symbol.descent + gap + below.ascent)
        }
    }
}

@Composable
private fun MathBlockView(latex: String, first: Boolean, caret: Boolean, accent: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val color = C.Ivory
    val basePx = with(density) { 17.sp.toPx() }
    val layout = remember(latex, color, basePx, density) {
        MathLayout(measurer, basePx, color, density).build(latex)
    }
    Column(Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 12.dp)) {
        if (layout.width > 0.5f && layout.height > 0.5f) {
            val widthDp = with(density) { layout.width.toDp() }
            val heightDp = with(density) { layout.height.toDp() }
            val scroll = rememberScrollState()
            Box(
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(vertical = 6.dp),
            ) {
                Canvas(Modifier.size(widthDp, heightDp)) {
                    layout.draw(this, 0f, layout.ascent)
                }
            }
        } else {
            val parts = remember(latex) { listOf<MdInline>(MdInline.Math(latex)) }
            Text(buildInlineText(parts, null), color = C.Ivory, fontSize = 16.sp)
        }
        if (caret) {
            Text(
                "\u258d",
                color = accent.copy(alpha = blinkAlpha()),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
