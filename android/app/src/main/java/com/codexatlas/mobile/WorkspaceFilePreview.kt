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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
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
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val OFFICE_XML_ENTRY_LIMIT = 8 * 1024 * 1024
private const val OFFICE_PREVIEW_CHAR_LIMIT = 200_000
private const val XLSX_PREVIEW_ROW_LIMIT = 250
private const val XLSX_PREVIEW_COLUMN_LIMIT = 50
private const val PDF_MAX_RENDER_WIDTH = 1_280
private const val PDF_MAX_RENDER_HEIGHT = 1_920
private const val PDF_MAX_RENDER_PIXELS = 1_800_000L
private const val IMAGE_MAX_PREVIEW_WIDTH = 2_048
private const val IMAGE_MAX_PREVIEW_HEIGHT = 2_048
private const val IMAGE_MAX_PREVIEW_PIXELS = 2_000_000L

internal data class PreviewDimensions(val width: Int, val height: Int)

internal fun pdfPreviewDimensions(sourceWidth: Int, sourceHeight: Int): PreviewDimensions {
    val safeWidth = sourceWidth.coerceAtLeast(1)
    val safeHeight = sourceHeight.coerceAtLeast(1)
    val sourcePixels = safeWidth.toDouble() * safeHeight.toDouble()
    // A modest 2x source scale keeps normal letter-sized pages crisp while
    // the pixel budget remains the hard ceiling for unusually large pages.
    val pixelScale = sqrt(PDF_MAX_RENDER_PIXELS.toDouble() / sourcePixels)
    val scale = min(
        2.0,
        min(
            pixelScale,
            min(
                PDF_MAX_RENDER_WIDTH.toDouble() / safeWidth.toDouble(),
                PDF_MAX_RENDER_HEIGHT.toDouble() / safeHeight.toDouble(),
            ),
        ),
    )
    return PreviewDimensions(
        width = (safeWidth.toDouble() * scale).roundToInt().coerceIn(1, PDF_MAX_RENDER_WIDTH),
        height = (safeHeight.toDouble() * scale).roundToInt().coerceIn(1, PDF_MAX_RENDER_HEIGHT),
    )
}

internal enum class WorkspacePreviewKind {
    Text,
    Image,
    Svg,
    Pdf,
    Docx,
    Xlsx,
    External,
}

/** Stable semantic categories keep the file list readable even before a preview is opened. */
internal enum class WorkspaceFileCategory {
    Directory,
    Pdf,
    Image,
    Document,
    Spreadsheet,
    Code,
    Archive,
    Audio,
    Video,
    Generic,
}

internal fun workspaceFileCategory(
    name: String,
    mime: String,
    kind: String = "file",
): WorkspaceFileCategory {
    if (kind.equals("directory", ignoreCase = true)) return WorkspaceFileCategory.Directory
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val normalizedMime = mime.substringBefore(';').lowercase(Locale.ROOT)
    return when {
        extension == "pdf" || normalizedMime == "application/pdf" -> WorkspaceFileCategory.Pdf
        normalizedMime.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg") -> WorkspaceFileCategory.Image
        normalizedMime.startsWith("audio/") || extension in setOf("mp3", "wav", "m4a", "flac", "ogg") -> WorkspaceFileCategory.Audio
        normalizedMime.startsWith("video/") || extension in setOf("mp4", "mov", "mkv", "webm", "avi") -> WorkspaceFileCategory.Video
        normalizedMime == "application/zip" || normalizedMime == "application/gzip" ||
            extension in setOf("zip", "gz", "bz2", "7z", "rar", "tar") -> WorkspaceFileCategory.Archive
        normalizedMime.contains("spreadsheet") || normalizedMime == "application/vnd.ms-excel" ||
            extension in setOf("xls", "xlsx", "csv", "tsv") -> WorkspaceFileCategory.Spreadsheet
        normalizedMime.contains("word") || normalizedMime == "application/msword" ||
            extension in setOf("doc", "docx", "odt", "rtf") -> WorkspaceFileCategory.Document
        normalizedMime.startsWith("text/") || normalizedMime == "application/json" ||
            extension in setOf("txt", "md", "markdown", "log", "toml", "ini", "conf", "json", "yaml", "yml", "xml", "html", "htm", "css", "js", "jsx", "ts", "tsx", "py", "rb", "php", "java", "kt", "kts", "rs", "go", "c", "h", "cpp", "hpp", "cs", "swift", "sh", "bash", "zsh", "fish", "ps1", "sql", "env") -> WorkspaceFileCategory.Code
        else -> WorkspaceFileCategory.Generic
    }
}

internal fun workspaceFileTypeLabel(category: WorkspaceFileCategory, name: String, chinese: Boolean): String {
    if (category == WorkspaceFileCategory.Directory) return if (chinese) "文件夹" else "Folder"
    val extension = name.substringAfterLast('.', "").uppercase(Locale.ROOT)
    return when (category) {
        WorkspaceFileCategory.Pdf -> "PDF"
        WorkspaceFileCategory.Image -> extension.ifBlank { if (chinese) "图片" else "Image" }
        WorkspaceFileCategory.Document -> extension.ifBlank { if (chinese) "文档" else "Document" }
        WorkspaceFileCategory.Spreadsheet -> extension.ifBlank { if (chinese) "表格" else "Sheet" }
        WorkspaceFileCategory.Code -> extension.ifBlank { if (chinese) "文本" else "Text" }
        WorkspaceFileCategory.Archive -> extension.ifBlank { if (chinese) "压缩包" else "Archive" }
        WorkspaceFileCategory.Audio -> extension.ifBlank { if (chinese) "音频" else "Audio" }
        WorkspaceFileCategory.Video -> extension.ifBlank { if (chinese) "视频" else "Video" }
        WorkspaceFileCategory.Generic, WorkspaceFileCategory.Directory -> extension.ifBlank { if (chinese) "文件" else "File" }
    }
}

@Composable
internal fun WorkspaceFileIcon(
    name: String,
    mime: String,
    kind: String,
    chinese: Boolean,
) {
    val category = workspaceFileCategory(name, mime, kind)
    val (icon, tint, background) = when (category) {
        WorkspaceFileCategory.Directory -> Triple(Icons.Filled.Folder, Color(0xFFB17A2E), Color(0xFFFFF4DE))
        WorkspaceFileCategory.Pdf -> Triple(Icons.Filled.PictureAsPdf, Color(0xFFC94F4A), Color(0xFFFFECEA))
        WorkspaceFileCategory.Image -> Triple(Icons.Filled.Photo, Color(0xFF3E82C4), Color(0xFFEAF4FF))
        WorkspaceFileCategory.Document -> Triple(Icons.Filled.Description, Color(0xFF4C70B5), Color(0xFFEDF2FF))
        WorkspaceFileCategory.Spreadsheet -> Triple(Icons.Filled.TableChart, Color(0xFF2F8752), Color(0xFFEAF8EF))
        WorkspaceFileCategory.Code -> Triple(Icons.Filled.Code, Color(0xFF68727D), Color(0xFFF0F2F4))
        WorkspaceFileCategory.Archive -> Triple(Icons.Filled.Archive, Color(0xFFB17A2E), Color(0xFFFFF4DE))
        WorkspaceFileCategory.Audio -> Triple(Icons.Filled.AudioFile, Color(0xFFA34B83), Color(0xFFFFEEF8))
        WorkspaceFileCategory.Video -> Triple(Icons.Filled.VideoFile, Color(0xFFB65A38), Color(0xFFFFF0EA))
        WorkspaceFileCategory.Generic -> Triple(Icons.Filled.InsertDriveFile, Color(0xFF6C816F), Color(0xFFF0F3F0))
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(background, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = workspaceFileTypeLabel(category, name, chinese),
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
    }
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
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
        val decoded = withContext(Dispatchers.IO) {
            runCatching { decodePreviewBitmap(file.bytes) }.getOrNull()
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

private fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) return null
    val sourcePixels = width.toDouble() * height.toDouble()
    val scale = min(
        min(
            IMAGE_MAX_PREVIEW_WIDTH.toDouble() / width.toDouble(),
            IMAGE_MAX_PREVIEW_HEIGHT.toDouble() / height.toDouble(),
        ),
        if (sourcePixels > IMAGE_MAX_PREVIEW_PIXELS) {
            sqrt(IMAGE_MAX_PREVIEW_PIXELS.toDouble() / sourcePixels)
        } else {
            1.0
        },
    ).coerceAtMost(1.0)
    val desiredWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val desiredHeight = (height * scale).roundToInt().coerceAtLeast(1)
    var sample = 1
    while (width / sample > desiredWidth || height / sample > desiredHeight) {
        sample = (sample shl 1).takeIf { it > 0 } ?: Int.MAX_VALUE
        if (sample == Int.MAX_VALUE) break
    }
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            // Preserve alpha for screenshots and diagrams while the pixel
            // budget keeps the allocation bounded.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
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
    private var closed = false
    val pageCount: Int = renderer.pageCount

    fun render(index: Int): Bitmap = synchronized(lock) {
        check(!closed) { "PDF renderer is closed" }
        renderer.openPage(index).use { page ->
            val dimensions = pdfPreviewDimensions(page.width, page.height)
            Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
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
    val fileLength = file.length()
    val fileModified = file.lastModified()
    var handle by remember(pathKey, fileLength, fileModified) { mutableStateOf<PdfPreviewHandle?>(null) }
    var openError by remember(pathKey, fileLength, fileModified) { mutableStateOf<String?>(null) }
    LaunchedEffect(pathKey, fileLength, fileModified) {
        handle = null
        openError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                require(file.isFile && file.length() > 0L) { "PDF file is empty" }
                PdfPreviewHandle.open(file)
            }
        }
        result.onSuccess { opened -> handle = opened }
            .onFailure { error -> openError = error.message ?: "PDF preview failed" }
    }
    var pageIndex by remember(pathKey) { mutableStateOf(0) }
    var bitmap by remember(pathKey) { mutableStateOf<Bitmap?>(null) }
    var renderError by remember(pathKey) { mutableStateOf<String?>(null) }
    val currentBitmap = rememberUpdatedState(bitmap)
    val handleForEffect = handle
    DisposableEffect(handle) {
        onDispose { handleForEffect?.close() }
    }
    DisposableEffect(pathKey) {
        onDispose {
            currentBitmap.value?.let { if (!it.isRecycled) it.recycle() }
        }
    }
    if (openError != null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                openError.orEmpty(),
                color = Color(0xFF68736B),
                fontSize = 16.sp,
            )
        }
        return
    }
    if (handle == null) {
        WorkspacePreviewLoading()
        return
    }
    if (handle!!.pageCount == 0) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                if (chinese) "PDF 中没有可显示的页面" else "This PDF has no displayable pages",
                color = Color(0xFF68736B),
                fontSize = 16.sp,
            )
        }
        return
    }
    LaunchedEffect(handle, pageIndex) {
        val activeHandle = handle ?: return@LaunchedEffect
        val safePageIndex = pageIndex.coerceIn(0, activeHandle.pageCount - 1)
        if (safePageIndex != pageIndex) {
            pageIndex = safePageIndex
            return@LaunchedEffect
        }
        val previous = bitmap
        bitmap = null
        if (previous != null && !previous.isRecycled) previous.recycle()
        renderError = null
        var rendered: Bitmap? = null
        try {
            rendered = withContext(Dispatchers.IO) { activeHandle.render(safePageIndex) }
            check(currentCoroutineContext().isActive) { "PDF render cancelled" }
            bitmap = rendered
            rendered = null
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            renderError = when (error) {
                is OutOfMemoryError -> if (chinese) "PDF 页面过大，已降低预览分辨率后仍无法显示" else "This PDF page is too large to render on this device"
                else -> error.message ?: "PDF render failed"
            }
        }
        rendered?.let { if (!it.isRecycled) it.recycle() }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).padding(8.dp), contentAlignment = Alignment.Center) {
            when {
                bitmap != null && !bitmap!!.isRecycled -> Image(
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
                "${pageIndex + 1} / ${handle!!.pageCount}",
                modifier = Modifier.padding(horizontal = 12.dp),
                color = Color(0xFF26332A),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = { pageIndex = (pageIndex + 1).coerceAtMost(handle!!.pageCount - 1) },
                enabled = pageIndex < handle!!.pageCount - 1,
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
