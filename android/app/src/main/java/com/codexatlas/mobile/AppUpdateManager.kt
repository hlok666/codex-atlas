package com.codexatlas.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

data class AtlasUpdate(
    val currentVersion: String,
    val latestVersion: String,
    val releaseName: String,
    val releaseUrl: String,
    val apkUrl: String?,
    val notes: String,
) {
    val available: Boolean get() = AppUpdateManager.compareVersions(latestVersion, currentVersion) > 0
}

/** Checks and downloads Android releases from the public GitHub release asset. */
class AppUpdateManager(private val context: Context) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(): Result<AtlasUpdate> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(ProjectLinks.latestReleaseApi)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Codex-Atlas-Android")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val tag = root["tag_name"]?.toString()?.trim('"').orEmpty().ifBlank { root["name"]?.toString()?.trim('"').orEmpty() }
                val latest = normalizeVersion(tag)
                if (latest.isBlank()) error("GitHub release has no version tag")
                val assets = root["assets"]?.jsonArray.orEmpty()
                val apk = assets.firstOrNull { asset ->
                    val name = asset.jsonObject["name"]?.toString()?.trim('"')?.lowercase().orEmpty()
                    name.endsWith(".apk")
                }?.jsonObject?.get("browser_download_url")?.toString()?.trim('"')
                AtlasUpdate(
                    currentVersion = normalizeVersion(BuildConfig.VERSION_NAME),
                    latestVersion = latest,
                    releaseName = root["name"]?.toString()?.trim('"').orEmpty().ifBlank { tag },
                    releaseUrl = root["html_url"]?.toString()?.trim('"').orEmpty().ifBlank { ProjectLinks.releasesUrl },
                    apkUrl = apk,
                    notes = root["body"]?.toString()?.trim('"').orEmpty(),
                )
            }
        }
    }

    suspend fun download(update: AtlasUpdate, onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = update.apkUrl ?: error("This release has no APK asset")
            val request = Request.Builder().url(url).header("User-Agent", "Codex-Atlas-Android").get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download returned HTTP ${response.code}")
                val body = response.body ?: error("GitHub returned an empty APK")
                val total = body.contentLength()
                val target = File(context.cacheDir, "updates/codex-atlas-${update.latestVersion}.apk")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                onProgress(100)
                target
            }
        }
    }

    fun openInstaller(file: File): Boolean {
        if (!file.exists()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(installIntent)
        return true
    }

    companion object {
        fun normalizeVersion(value: String): String = value.trim().removePrefix("v").substringBefore('+').substringBefore('-')

        fun compareVersions(left: String, right: String): Int {
            val a = normalizeVersion(left).split('.').map { it.toIntOrNull() ?: 0 }
            val b = normalizeVersion(right).split('.').map { it.toIntOrNull() ?: 0 }
            return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { index ->
                val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
                result.takeIf { it != 0 }
            } ?: 0
        }
    }
}
