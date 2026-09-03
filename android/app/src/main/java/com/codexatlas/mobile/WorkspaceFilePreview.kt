package com.codexatlas.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlin.math.roundToInt

private const val OFFICE_XML_ENTRY_LIMIT = 8 * 1024 * 1024
private const val OFFICE_PREVIEW_CHAR_LIMIT = 200_000
private const val XLSX_PREVIEW_ROW_LIMIT = 250
private const val XLSX_PREVIEW_COLUMN_LIMIT = 50

internal enum class WorkspacePreviewKind {
    Text,
    Image,
    Svg,
    Pdf,
    Docx,
    Xlsx,
    External,
}

internal fun workspacePreviewKind(name: String, mime: String): WorkspacePreviewKind {
    val normalizedMime = mime.substringBefore(';').lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        normalizedMime == "image/svg+xml" || extension == "svg" -> WorkspacePreviewKind.Svg
        normalizedMime == "application/pdf" || extension == "pdf" -> WorkspacePreviewKind.Pdf
        extension == "docx" ||
            normalizedMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
            WorkspacePreviewKind.Docx
        extension == "xlsx" ||
            normalizedMime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
            WorkspacePreviewKind.Xlsx
        normalizedMime.startsWith("text/") || normalizedMime == "application/json" -> WorkspacePreviewKind.Text
        normalizedMime.startsWith("image/") -> WorkspacePreviewKind.Image
        else -> WorkspacePreviewKind.External
    }
}

@Composable
internal fun WorkspaceFilePreview(
    file: AtlasWorkspaceFile,
    cacheFile: File,
    pathKey: String,
    chinese: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.White)
            .border(1.dp, Color(0xFFE6EAE6)),
    ) {
        when (workspacePreviewKind(file.name, file.mime)) {
            WorkspacePreviewKind.Text -> WorkspaceTextPreview(file.bytes)
            WorkspacePreviewKind.Image -> WorkspaceImagePreview(file, pathKey, chinese)
            WorkspacePreviewKind.Svg -> WorkspaceSvgPreview(file.bytes, pathKey)
            WorkspacePreviewKind.Pdf -> WorkspacePdfPreview(cacheFile, pathKey, chinese)
            WorkspacePreviewKind.Docx -> WorkspaceOfficeTextPreview(
                pathKey = pathKey,
                chinese = chinese,
                parse = { extractDocxPreview(file.bytes) },
            )
            WorkspacePreviewKind.Xlsx -> WorkspaceOfficeTextPreview(
                pathKey = pathKey,
                chinese = chinese,
                monospace = true,
                parse = { extractXlsxPreview(file.bytes) },
            )
            WorkspacePreviewKind.External -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (chinese) "此格式需要使用系统应用打开" else "Open this format with another app",
                    color = Color(0xFF68736B),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTextPreview(bytes: ByteArray) {
    val text = remember(bytes) { bytes.toString(Charsets.UTF_8) }
    SelectionContainer {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            color = Color(0xFF26332A),
            fontSize = 15.sp,
            lineHeight = 23.sp,
        )
    }
}

@Composable
private fun WorkspaceImagePreview(file: AtlasWorkspaceFile, pathKey: String, chinese: Boolean) {
    var bitmap by remember(pathKey) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(pathKey) { mutableStateOf(false) }
    LaunchedEffect(pathKey, file.bytes.size) {
        val decoded = withContext(Dispatchers.IO) {
            BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)
        }
        bitmap = decoded
        failed = decoded == null
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            contentScale = ContentScale.Fit,
        )
        failed -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (chinese) "图片预览失败，请使用系统应用打开" else "Image preview failed. Open it with another app.",
                color = Color(0xFF68736B),
                fontSize = 16.sp,
            )
        }
        else -> WorkspacePreviewLoading()
    }
}

@Composable
private fun WorkspaceSvgPreview(bytes: ByteArray, pathKey: String) {
    val context = LocalContext.current
    val encoded = remember(pathKey, bytes.size) { Base64.encodeToString(bytes, Base64.NO_WRAP) }
    val html = remember(pathKey, encoded) {
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            |<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:">
            |<style>html,body{height:100%;margin:0;background:#fff}body{display:flex;align-items:center;justify-content:center}
            |img{display:block;max-width:100%;max-height:100%;object-fit:contain}</style></head>
            |<body><img alt="" src="data:image/svg+xml;base64,$encoded"></body></html>""".trimMargin()
    }
    val webView = remember(pathKey) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.domStorageEnabled = false
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    AndroidView(
        factory = { webView },
        update = { view ->
            if (view.tag != pathKey) {
                view.tag = pathKey
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class PdfPreviewHandle private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    private val lock = Any()
    val pageCount: Int = renderer.pageCount

    fun render(index: Int): Bitmap = synchronized(lock) {
        renderer.openPage(index).use { page ->
            val sourceWidth = page.width.coerceAtLeast(1)
            val sourceHeight = page.height.coerceAtLeast(1)
            var targetWidth = (sourceWidth * 2).coerceIn(720, 1_600)
            var targetHeight = (sourceHeight.toDouble() * targetWidth / sourceWidth).roundToInt().coerceAtLeast(1)
            if (targetHeight > 2_400) {
                val scale = 2_400.0 / targetHeight
                targetWidth = (targetWidth * scale).roundToInt().coerceAtLeast(1)
                targetHeight = 2_400
            }
            Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    fun close() = synchronized(lock) {
        renderer.close()
        descriptor.close()
    }

    companion object {
        fun open(file: File): PdfPreviewHandle {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfPreviewHandle(descriptor, PdfRenderer(descriptor))
            } catch (error: Throwable) {
                descriptor.close()
                throw error
            }
        }
    }
}

@Composable
private fun WorkspacePdfPreview(file: File, pathKey: String, chinese: Boolean) {
    val handleResult = remember(pathKey, file.length(), file.lastModified()) { runCatching { PdfPreviewHandle.open(file) } }
    val handle = handleResult.getOrNull()
    DisposableEffect(handle) {
        onDispose { handle?.close() }
    }
    if (handle == null || handle.pageCount == 0) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                handleResult.exceptionOrNull()?.message
                    ?: if (chinese) "PDF 中没有可显示的页面" else "This PDF has no displayable pages",
                color = Color(0xFF68736B),
                fontSize = 16.sp,
            )
        }
        return
    }
    var pageIndex by remember(pathKey) { mutableStateOf(0) }
    var bitmap by remember(pathKey) { mutableStateOf<Bitmap?>(null) }
    var renderError by remember(pathKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(handle, pageIndex) {
        bitmap = null
        renderError = null
        runCatching { withContext(Dispatchers.IO) { handle.render(pageIndex) } }
            .onSuccess { bitmap = it }
            .onFailure { renderError = it.message ?: "PDF render failed" }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = if (chinese) "PDF 第 ${pageIndex + 1} 页" else "PDF page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                renderError != null -> Text(renderError.orEmpty(), color = Color(0xFFB44A45), fontSize = 15.sp)
                else -> WorkspacePreviewLoading()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                enabled = pageIndex > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (chinese) "上一页" else "Previous page")
            }
            Text(
                "${pageIndex + 1} / ${handle.pageCount}",
                modifier = Modifier.padding(horizontal = 12.dp),
                color = Color(0xFF26332A),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = { pageIndex = (pageIndex + 1).coerceAtMost(handle.pageCount - 1) },
                enabled = pageIndex < handle.pageCount - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = if (chinese) "下一页" else "Next page")
            }
        }
    }
}

@Composable
private fun WorkspaceOfficeTextPreview(
    pathKey: String,
    chinese: Boolean,
    monospace: Boolean = false,
    parse: () -> String,
) {
    var text by remember(pathKey) { mutableStateOf<String?>(null) }
    var error by remember(pathKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(pathKey) {
        runCatching { withContext(Dispatchers.IO) { parse() } }
            .onSuccess { text = it.ifBlank { if (chinese) "文档没有可显示的文字" else "The document contains no displayable text" } }
            .onFailure { error = it.message ?: if (chinese) "文档解析失败" else "Document preview failed" }
    }
    when {
        text != null -> SelectionContainer {
            Text(
                text = text.orEmpty(),
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState(), enabled = monospace)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                color = Color(0xFF26332A),
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                fontSize = if (monospace) 14.sp else 15.sp,
                lineHeight = if (monospace) 21.sp else 23.sp,
            )
        }
        error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(error.orEmpty(), color = Color(0xFFB44A45), fontSize = 15.sp)
        }
        else -> WorkspacePreviewLoading()
    }
}

@Composable
private fun WorkspacePreviewLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color(0xFF2F7C3B), strokeWidth = 2.dp)
    }
}

private fun readZipEntry(input: ZipInputStream, limit: Int = OFFICE_XML_ENTRY_LIMIT): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Office document content is too large to preview" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun secureXmlParse(bytes: ByteArray, handler: DefaultHandler) {
    val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
    listOf(
        "http://apache.org/xml/features/disallow-doctype-decl",
        "http://xml.org/sax/features/external-general-entities",
        "http://xml.org/sax/features/external-parameter-entities",
        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
    ).forEachIndexed { index, feature ->
        runCatching { factory.setFeature(feature, index == 0) }
    }
    val reader = factory.newSAXParser().xmlReader
    reader.contentHandler = handler
    reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
    reader.parse(InputSource(ByteArrayInputStream(bytes)))
}

private fun xmlTag(localName: String, qName: String): String =
    localName.ifBlank { qName.substringAfter(':') }.lowercase(Locale.ROOT)

internal fun extractDocxPreview(bytes: ByteArray): String {
    var documentXml: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && entry.name.equals("word/document.xml", ignoreCase = true)) {
                documentXml = readZipEntry(zip)
                break
            }
            zip.closeEntry()
        }
    }
    val xml = requireNotNull(documentXml) { "This Word document does not contain document.xml" }
    val output = StringBuilder()
    var captureText = false
    secureXmlParse(xml, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "t" -> captureText = true
                "tab" -> if (output.length < OFFICE_PREVIEW_CHAR_LIMIT) output.append('\t')
                "br", "cr" -> if (output.length < OFFICE_PREVIEW_CHAR_LIMIT) output.append('\n')
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (!captureText || output.length >= OFFICE_PREVIEW_CHAR_LIMIT) return
            output.append(ch, start, length.coerceAtMost(OFFICE_PREVIEW_CHAR_LIMIT - output.length))
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "t" -> captureText = false
                "tc" -> if (output.isNotEmpty() && output.last() !in "\n\t") output.append('\t')
                "p", "tr" -> if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            }
        }
    })
    return output.toString()
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun parseSharedStrings(xml: ByteArray?): List<String> {
    if (xml == null) return emptyList()
    val values = mutableListOf<String>()
    var current: StringBuilder? = null
    var captureText = false
    secureXmlParse(xml, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "si" -> current = StringBuilder()
                "t" -> captureText = current != null
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (captureText) current?.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "t" -> captureText = false
                "si" -> {
                    if (values.size < 100_000) values += current?.toString().orEmpty()
                    current = null
                }
            }
        }
    })
    return values
}

private fun xlsxColumnIndex(reference: String): Int {
    var value = 0
    for (character in reference) {
        if (!character.isLetter()) break
        value = value * 26 + (character.uppercaseChar() - 'A' + 1)
    }
    return (value - 1).coerceAtLeast(0)
}

private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): String {
    val output = StringBuilder()
    var rowCount = 0
    var rowCells = sortedMapOf<Int, String>()
    var cellReference = ""
    var cellType = ""
    var captureValue = false
    var captureInline = false
    val rawValue = StringBuilder()
    val inlineValue = StringBuilder()

    secureXmlParse(xml, object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "row" -> rowCells = sortedMapOf()
                "c" -> {
                    cellReference = attributes?.getValue("r").orEmpty()
                    cellType = attributes?.getValue("t").orEmpty()
                    rawValue.setLength(0)
                    inlineValue.setLength(0)
                }
                "v" -> captureValue = true
                "t" -> if (cellType == "inlineStr") captureInline = true
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (captureValue) rawValue.append(ch, start, length)
            if (captureInline) inlineValue.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (xmlTag(localName.orEmpty(), qName.orEmpty())) {
                "v" -> captureValue = false
                "t" -> captureInline = false
                "c" -> {
                    val raw = rawValue.toString()
                    val value = when (cellType) {
                        "s" -> raw.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
                        "inlineStr", "str" -> inlineValue.toString().ifBlank { raw }
                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                        else -> raw
                    }
                    val column = xlsxColumnIndex(cellReference)
                    if (column < XLSX_PREVIEW_COLUMN_LIMIT && value.isNotBlank()) rowCells[column] = value
                }
                "row" -> {
                    if (rowCount < XLSX_PREVIEW_ROW_LIMIT && rowCells.isNotEmpty()) {
                        val lastColumn = rowCells.lastKey().coerceAtMost(XLSX_PREVIEW_COLUMN_LIMIT - 1)
                        output.append((0..lastColumn).joinToString("\t") { rowCells[it].orEmpty() }).append('\n')
                        rowCount += 1
                    }
                }
            }
        }
    })
    return output.toString().take(OFFICE_PREVIEW_CHAR_LIMIT).trimEnd()
}

internal fun extractXlsxPreview(bytes: ByteArray): String {
    var sharedStringsXml: ByteArray? = null
    var worksheetXml: ByteArray? = null
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                when {
                    entry.name.equals("xl/sharedStrings.xml", ignoreCase = true) ->
                        sharedStringsXml = readZipEntry(zip)
                    worksheetXml == null &&
                        entry.name.startsWith("xl/worksheets/", ignoreCase = true) &&
                        entry.name.endsWith(".xml", ignoreCase = true) ->
                        worksheetXml = readZipEntry(zip)
                }
            }
            zip.closeEntry()
        }
    }
    val sheet = requireNotNull(worksheetXml) { "This Excel workbook does not contain a worksheet" }
    return parseWorksheet(sheet, parseSharedStrings(sharedStringsXml))
}
