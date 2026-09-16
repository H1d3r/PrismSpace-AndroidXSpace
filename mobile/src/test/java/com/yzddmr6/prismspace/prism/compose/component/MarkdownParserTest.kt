package com.yzddmr6.prismspace.prism.compose.component

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {

    @Test fun `headings bullets and numbered items become blocks`() {
        val blocks = parseMarkdownBlocks("## 修复\n- 第一项\n- 第二项\n1. 一步\n2. 二步")
        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, listOf(MarkdownSpan.Text("修复"))),
                MarkdownBlock.Bullet(listOf(MarkdownSpan.Text("第一项"))),
                MarkdownBlock.Bullet(listOf(MarkdownSpan.Text("第二项"))),
                MarkdownBlock.Numbered(1, listOf(MarkdownSpan.Text("一步"))),
                MarkdownBlock.Numbered(2, listOf(MarkdownSpan.Text("二步"))),
            ),
            blocks,
        )
    }

    @Test fun `consecutive plain lines merge into one paragraph until blank line`() {
        val blocks = parseMarkdownBlocks("第一行\n第二行\n\n下一段")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph(listOf(MarkdownSpan.Text("第一行 第二行"))),
                MarkdownBlock.Paragraph(listOf(MarkdownSpan.Text("下一段"))),
            ),
            blocks,
        )
    }

    @Test fun `divider quote and star-bullet are recognized`() {
        val blocks = parseMarkdownBlocks("---\n> 引用\n* 星号项")
        assertEquals(
            listOf(
                MarkdownBlock.Divider,
                MarkdownBlock.Quote(listOf(MarkdownSpan.Text("引用"))),
                MarkdownBlock.Bullet(listOf(MarkdownSpan.Text("星号项"))),
            ),
            blocks,
        )
    }

    @Test fun `inline bold italic code strike and link`() {
        val spans = parseMarkdownSpans("普通 **粗体** *斜体* `代码` ~~删除~~ [链接](https://example.test)")
        assertEquals(
            listOf(
                MarkdownSpan.Text("普通 "),
                MarkdownSpan.Bold("粗体"),
                MarkdownSpan.Text(" "),
                MarkdownSpan.Italic("斜体"),
                MarkdownSpan.Text(" "),
                MarkdownSpan.Code("代码"),
                MarkdownSpan.Text(" "),
                MarkdownSpan.Strike("删除"),
                MarkdownSpan.Text(" "),
                MarkdownSpan.Link("链接", "https://example.test"),
            ),
            spans,
        )
    }

    @Test fun `unterminated markers stay literal`() {
        assertEquals(
            listOf(MarkdownSpan.Text("没有闭合 **加粗")),
            parseMarkdownSpans("没有闭合 **加粗"),
        )
        assertEquals(
            listOf(MarkdownSpan.Text("空标记 **** 保留")),
            parseMarkdownSpans("空标记 **** 保留"),
        )
    }

    @Test fun `bold wins over italic at the same position`() {
        assertEquals(
            listOf(MarkdownSpan.Bold("粗"), MarkdownSpan.Italic("斜")),
            parseMarkdownSpans("**粗***斜*"),
        )
    }

    @Test fun `crlf input is normalized`() {
        val blocks = parseMarkdownBlocks("一\r\n二\r\n")
        assertEquals(listOf(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Text("一 二")))), blocks)
    }

    @Test fun `real world v0 style release notes`() {
        val md = "本次为紧急修复版本。\n\n## 修复\n\n- **修复小米设备问题。** 详细说明见 [PR #9](https://github.com/yzddmr6/PrismSpace/pull/9)。\n\n## 改进\n\n- 诊断日志增强"
        val blocks = parseMarkdownBlocks(md)
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownSpan.Text("本次为紧急修复版本。"))), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, listOf(MarkdownSpan.Text("修复"))), blocks[1])
        val bullet = blocks[2] as MarkdownBlock.Bullet
        assertEquals(MarkdownSpan.Bold("修复小米设备问题。"), bullet.spans[0])
        assertEquals(
            MarkdownSpan.Link("PR #9", "https://github.com/yzddmr6/PrismSpace/pull/9"),
            bullet.spans[2],
        )
    }
}
