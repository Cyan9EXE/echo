package app.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Markdown / LaTeX 解析器的 JVM 单测。只覆盖纯 Kotlin 解析层；
 * Compose 渲染层用真机/预览目测（不依赖 Android 运行时）。
 */
class MarkdownTest {

    private fun blocks(src: String): List<MdBlock> = MdParser.parse(src)
    private fun inlines(src: String): List<MdInline> = MdParser.parseInline(src)
    private fun plain(src: String): String = MdParser.plainText(inlines(src))

    // -- 块级 --

    @Test
    fun headingsOneToThree() {
        val result = blocks("# 一级\n\n## 二级\n\n### 三级\n\n正文")
        assertEquals(4, result.size)
        assertEquals(MdBlock.Heading(1, "一级"), result[0])
        assertEquals(MdBlock.Heading(2, "二级"), result[1])
        assertEquals(MdBlock.Heading(3, "三级"), result[2])
        assertEquals(MdBlock.Paragraph("正文"), result[3])
    }

    @Test
    fun paragraphKeepsSoftBreaks() {
        val result = blocks("第一行\n第二行\n\n下一段")
        assertEquals(2, result.size)
        assertEquals(MdBlock.Paragraph("第一行\n第二行"), result[0])
        assertEquals(MdBlock.Paragraph("下一段"), result[1])
    }

    @Test
    fun codeFenceKeepsLanguageAndContent() {
        val result = blocks("前文\n\n```kotlin\nval a = 1\nval b = 2\n```\n\n后文")
        assertEquals(3, result.size)
        val fence = result[1] as MdBlock.Fence
        assertEquals("kotlin", fence.lang)
        assertEquals("val a = 1\nval b = 2", fence.code)
    }

    @Test
    fun codeFenceContentIsNotParsedAsMarkdown() {
        val result = blocks("```\n**不是粗体** [也不是链接](x)\n```")
        val fence = result[0] as MdBlock.Fence
        assertEquals("**不是粗体** [也不是链接](x)", fence.code)
    }

    @Test
    fun unclosedFenceFallsBackToEnd() {
        val result = blocks("```\n未闭合的代码")
        val fence = result[0] as MdBlock.Fence
        assertEquals("", fence.lang)
        assertEquals("未闭合的代码", fence.code)
    }

    @Test
    fun bulletAndOrderedLists() {
        val bullet = blocks("- 苹果\n- 香蕉\n- 橙子")
        assertEquals(MdBlock.Bullet(listOf("苹果", "香蕉", "橙子")), bullet[0])

        val ordered = blocks("3. 第三\n4. 第四")
        assertEquals(MdBlock.Numbered(3, listOf("第三", "第四")), ordered[0])
    }

    @Test
    fun blockquoteRecursesIntoBlocks() {
        val result = blocks("> 引用第一行\n> 引用 **粗体**")
        assertEquals(1, result.size)
        val quote = result[0] as MdBlock.Quote
        val inner = quote.blocks[0] as MdBlock.Paragraph
        assertTrue(plain(inner.text).contains("引用"))
        assertTrue(inlines(inner.text).any { it is MdInline.Styled && it.bold })
    }

    @Test
    fun horizontalRule() {
        assertEquals(MdBlock.Rule, blocks("---")[0])
        assertEquals(MdBlock.Rule, blocks("***")[0])
        assertEquals(MdBlock.Rule, blocks("___")[0])
    }

    // -- 行内 --

    @Test
    fun boldItalicStrikeCode() {
        val parts = inlines("**粗体** 和 *斜体* 以及 ~~删除~~ 与 `代码`")
        assertTrue(parts.any { it is MdInline.Styled && it.bold })
        assertTrue(parts.any { it is MdInline.Styled && it.italic && !it.bold })
        assertTrue(parts.any { it is MdInline.Styled && it.strike })
        assertTrue(parts.any { it is MdInline.Code && it.text == "代码" })
        assertEquals("粗体 和 斜体 以及 删除 与 代码", MdParser.plainText(parts))
    }

    @Test
    fun nestedEmphasis() {
        val parts = inlines("**粗 *斜* 粗**")
        val bold = parts.filterIsInstance<MdInline.Styled>().first { it.bold }
        assertTrue(bold.children.any { it is MdInline.Styled && it.italic })
    }

    @Test
    fun linkCarriesUrl() {
        val parts = inlines("见 [官网](https://example.com) 说明")
        val link = parts.filterIsInstance<MdInline.Link>().first()
        assertEquals("https://example.com", link.url)
        assertEquals("见 官网 说明", MdParser.plainText(parts))
    }

    @Test
    fun headingInlineCode() {
        val result = blocks("## 用 `Markdown` 渲染")
        val heading = result[0] as MdBlock.Heading
        val parts = MdParser.parseInline(heading.text)
        assertTrue(parts.any { it is MdInline.Code && it.text == "Markdown" })
    }

    // -- 公式切分 --

    @Test
    fun inlineDollarMath() {
        val parts = inlines("公式 \$x^2 + 1\$ 结束")
        assertTrue(parts.any { it is MdInline.Math && it.latex == "x^2 + 1" })
        assertEquals("公式 x² + 1 结束", MdParser.plainText(parts))
    }

    @Test
    fun inlineParenMath() {
        val parts = inlines("行内 \\(a+b\\) 结束")
        assertTrue(parts.any { it is MdInline.Math && it.latex == "a+b" })
    }

    @Test
    fun unclosedDollarStaysLiteral() {
        val parts = inlines("价格是 \$5 元")
        assertFalse(parts.any { it is MdInline.Math })
        assertEquals("价格是 \$5 元", MdParser.plainText(parts))
    }

    @Test
    fun blockDollarMath() {
        val result = blocks("$$\n\\frac{a}{b}\n$$")
        assertEquals(1, result.size)
        val math = result[0] as MdBlock.Math
        assertEquals("\\frac{a}{b}", math.latex)
    }

    @Test
    fun blockBracketMath() {
        val result = blocks("\\[\n\\sqrt{x+1}\n\\]")
        val math = result[0] as MdBlock.Math
        assertEquals("\\sqrt{x+1}", math.latex)
    }

    @Test
    fun singleLineBlockMath() {
        val result = blocks("$$ E = mc^2 $$")
        assertEquals(MdBlock.Math("E = mc^2"), result[0])
    }

    @Test
    fun unclosedBlockMathConsumesTail() {
        val result = blocks("$$\na+b")
        assertEquals(MdBlock.Math("a+b"), result[0])
    }

    // -- LaTeX 结构 --

    @Test
    fun latexFractionSqrtScript() {
        val frac = MathParser.parse("\\frac{a}{b}")
        assertEquals(1, frac.size)
        assertTrue(frac[0] is MathNode.Frac)

        val sqrt = MathParser.parse("\\sqrt{x+1}")
        assertEquals(1, sqrt.size)
        assertTrue(sqrt[0] is MathNode.Sqrt)

        val script = MathParser.parse("x^2")
        assertEquals(1, script.size)
        assertTrue(script[0] is MathNode.Script)
    }

    @Test
    fun latexSumHasLimits() {
        val nodes = MathParser.parse("\\sum_{i=1}^{n} i")
        val big = nodes.filterIsInstance<MathNode.Big>().first()
        assertEquals("∑", big.symbol)
        assertTrue(big.below != null)
        assertTrue(big.above != null)
    }

    @Test
    fun latexGreekAndOperators() {
        assertEquals("α ≤ β", MathParser.toUnicodeInline("\\alpha \\le \\beta"))
        assertEquals("∞", MathParser.toUnicodeInline("\\infty"))
        assertEquals("30°", MathParser.toUnicodeInline("30\\degree"))
    }

    @Test
    fun latexInlineDegradation() {
        assertEquals("(a + b)⁄2", MathParser.toUnicodeInline("\\frac{a+b}{2}"))
        assertEquals("x²", MathParser.toUnicodeInline("x^2"))
        assertEquals("√x", MathParser.toUnicodeInline("\\sqrt{x}"))
        assertEquals("∫", MathParser.toUnicodeInline("\\int"))
    }

    // -- 流式切段 --

    @Test
    fun splitSegmentsAtBlankLines() {
        val segments = MdParser.splitSegments("a\n\nb\n\nc")
        assertEquals(3, segments.size)
        assertEquals(0, segments[0].start)
        assertEquals("c", segments[2].text.trim())
    }

    @Test
    fun splitSegmentsKeepsFenceTogether() {
        val segments = MdParser.splitSegments("```\na\n\nb\n```")
        assertEquals(1, segments.size)
    }

    @Test
    fun splitSegmentsKeepsMathTogether() {
        val segments = MdParser.splitSegments("$$\na\n\nb\n$$")
        assertEquals(1, segments.size)
    }

    // -- 容错 --

    @Test
    fun crlfAndEmptyInput() {
        val result = blocks("## 标题\r\n正文")
        assertEquals(MdBlock.Heading(2, "标题"), result[0])
        assertTrue(MdParser.parse("").isEmpty())
        assertTrue(MdParser.parse("   \n\n  ").isEmpty())
    }

    @Test
    fun markdownInsideListItem() {
        val result = blocks("- **重点**：`code`")
        val bullet = result[0] as MdBlock.Bullet
        val parts = MdParser.parseInline(bullet.items[0])
        assertTrue(parts.any { it is MdInline.Styled && it.bold })
        assertTrue(parts.any { it is MdInline.Code })
    }
    // -- 流式切段与尾部（P2-1/P3-1）--

    @Test
    fun streamSplitCommitsOnlyOnBlankLine() {
        val split = StreamSplit()
        val first = split.frame("第一行")
        assertTrue(first.stable.isEmpty())
        assertEquals("第一行", first.tail)

        val second = split.frame("第一行\n第二行")
        assertTrue(second.stable.isEmpty())
        assertEquals("第一行\n第二行", second.tail)

        val third = split.frame("第一行\n第二行\n\n第二段")
        assertEquals(1, third.stable.size)
        assertEquals("第一行\n第二行\n\n", third.stable[0].text)
        assertEquals("第二段", third.tail)
    }

    @Test
    fun streamSplitTreatsCrlfBlankLine() {
        val split = StreamSplit()
        val frame = split.frame("a\r\n\r\nb")
        assertEquals(1, frame.stable.size)
        assertEquals("b", frame.tail)
    }

    @Test
    fun streamSplitKeepsFenceTogether() {
        val split = StreamSplit()
        // 围栏内的空行不是提交点。
        assertTrue(split.frame("```\na\n\nb\n").stable.isEmpty())
        // 围栏闭合行本身可以提交整个围栏。
        val closed = split.frame("```\na\n\nb\n```\n")
        assertEquals(1, closed.stable.size)
        assertEquals("", closed.tail)
    }

    @Test
    fun streamSplitResetsWhenTextShrinks() {
        val split = StreamSplit()
        split.frame("a\n\nb")
        val shrunk = split.frame("a")
        assertTrue(shrunk.stable.isEmpty())
        assertEquals("a", shrunk.tail)
    }

    @Test
    fun chunkTextNeverCutsSurrogatePairs() {
        val s = "a\uD83D\uDE00b\uD83D\uDE00c"
        val chunks = chunkText(s, 2)
        assertEquals(s, chunks.joinToString(""))
        chunks.forEach { chunk ->
            assertFalse(chunk.last().isHighSurrogate())
            assertFalse(chunk.first().isLowSurrogate())
        }
        assertEquals("a\uD83D\uDE00", chunks[0])
    }

    @Test
    fun streamSplitAdvancesScanOnLongSingleLine() {
        // 8K 单行没有换行：扫描指针必须推进到末尾，下一 token 不能从头重扫（P2-2）。
        val split = StreamSplit()
        val line = "a".repeat(8000)
        val first = split.frame(line)
        assertEquals(8000, split.scannedLength)
        assertTrue(first.stable.isEmpty())
        assertEquals(line, first.tail)

        val second = split.frame(line + "x")
        assertEquals(8001, split.scannedLength)
        assertEquals(line + "x", second.tail)
        // 空行出现后整段提交，尾部只剩下一行。
        val third = split.frame(line + "x\n\nnext")
        assertEquals("next", third.tail)
        assertTrue(third.stable.isNotEmpty())
        assertTrue(third.stable[0].text.endsWith("\n\n"))
    }

    @Test
    fun streamSplitLongSingleLineStaysOneStableChunk() {
        // 长单行不触发逐 token 重切段：tail 始终是完整原文，不隐藏、不截断（P2-2）。
        val split = StreamSplit()
        val sb = StringBuilder()
        repeat(200) {
            sb.append("\u4e2d".repeat(40))
            val f = split.frame(sb.toString())
            assertEquals(sb.toString(), f.tail)
            assertTrue(f.stable.isEmpty())
        }
    }

    @Test
    fun streamStableBoundaryNeverSplitsSurrogatePair() {
        // 1200 边界正好落在 emoji 的代理对之间时，稳定前缀必须回退一格（R9-P2-4）。
        val emoji = "\uD83D\uDE00"
        val before = "a".repeat(1199) + emoji          // 长度为 1201，边界 1200 位于代理对内部
        assertEquals(1199, streamStableLength(before, 1200))
        val clean = "a".repeat(1200)                   // 边界正好在字符之间
        assertEquals(1200, streamStableLength(clean, 1200))
        val atBoundary = "a".repeat(1198) + emoji      // 边界 1200 落在代理对之后
        assertEquals(1200, streamStableLength(atBoundary, 1200))
        assertEquals(0, streamStableLength("abc", 1200))
        assertEquals(0, streamStableLength(before, 0))
    }
}
