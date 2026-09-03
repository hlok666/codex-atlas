package com.codexatlas.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorkspaceFilePreviewTest {
    @Test
    fun docxPreviewPreservesParagraphsAndRuns() {
        val archive = zip(
            "word/document.xml" to
                """<?xml version="1.0" encoding="UTF-8"?>
                    |<w:document xmlns:w="urn:word"><w:body>
                    |<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>Atlas</w:t></w:r></w:p>
                    |<w:p><w:r><w:t>Second line</w:t></w:r></w:p>
                    |</w:body></w:document>""".trimMargin(),
        )

        assertEquals("Hello Atlas\nSecond line", extractDocxPreview(archive))
    }

    @Test
    fun xlsxPreviewResolvesSharedStringsAndColumns() {
        val archive = zip(
            "xl/sharedStrings.xml" to
                """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    |<si><t>Name</t></si><si><t>Atlas</t></si>
                    |</sst>""".trimMargin(),
            "xl/worksheets/sheet1.xml" to
                """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                    |<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>Count</t></is></c></row>
                    |<row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>3</v></c></row>
                    |</sheetData></worksheet>""".trimMargin(),
        )

        assertEquals("Name\tCount\nAtlas\t3", extractXlsxPreview(archive))
    }

    @Test
    fun previewKindCoversPortableFormatsAndExternalFallbacks() {
        assertEquals(WorkspacePreviewKind.Pdf, workspacePreviewKind("report.pdf", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.Svg, workspacePreviewKind("diagram.svg", "image/svg+xml"))
        assertEquals(WorkspacePreviewKind.Image, workspacePreviewKind("screen.png", "image/png"))
        assertEquals(WorkspacePreviewKind.Docx, workspacePreviewKind("notes.docx", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.Xlsx, workspacePreviewKind("table.xlsx", "application/octet-stream"))
        assertEquals(WorkspacePreviewKind.External, workspacePreviewKind("legacy.doc", "application/msword"))
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
