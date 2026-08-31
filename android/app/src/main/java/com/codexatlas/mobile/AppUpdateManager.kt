package com.codexatlas.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

sealed interface InstallerResult {
    data object Opened : InstallerResult
    data object NeedsUnknownSources : InstallerResult
    data class SignatureConflict(val message: String) : InstallerResult
    data class Failure(val message: String) : InstallerResult
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
                val apk = assets
                    .mapNotNull { asset ->
                        val objectValue = asset.jsonObject
                        val name = objectValue["name"]?.toString()?.trim('"').orEmpty()
                        val url = objectValue["browser_download_url"]?.toString()?.trim('"')
                        if (name.endsWith(".apk", ignoreCase = true) && !url.isNullOrBlank()) name to url else null
                    }
                    .sortedByDescending { (name, _) -> name.equals("codex-atlas-android.apk", ignoreCase = true) }
                    .firstOrNull()
                    ?.second
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
            val target = updateFile(update)
            if (isUsableApk(target, update)) {
                onProgress(100)
                return@runCatching target
            }
            val partial = File(target.parentFile, "${target.name}.part")
            val existing = partial.length().takeIf { partial.isFile } ?: 0L
            val requestBuilder = Request.Builder().url(url).header("User-Agent", "Codex-Atlas-Android").get()
            if (existing > 0L) requestBuilder.header("Range", "bytes=$existing-")
            val request = requestBuilder.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download returned HTTP ${response.code}")
                val body = response.body ?: error("GitHub returned an empty APK")
                val append = existing > 0L && response.code == 206
                if (!append && existing > 0L) partial.delete()
                val copiedStart = if (append) existing else 0L
                val total = body.contentLength().let { length -> if (length > 0L) length + copiedStart else 0L }
                partial.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = copiedStart
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress(((copied * 100L) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            target.delete()
            if (!partial.renameTo(target)) error("无法保存下载的 APK")
            if (!isUsableApk(target, update)) {
                target.delete()
                error("下载的 APK 无效或版本不匹配")
            }
            onProgress(100)
            target
        }
    }

    fun openInstaller(file: File): InstallerResult {
        if (!isUsableApk(file, null)) return InstallerResult.Failure("下载的 APK 无效或已损坏，请重新下载")
        if (!isSignatureCompatible(file)) {
            return InstallerResult.SignatureConflict("当前安装包签名与已安装版本不同。Android 不允许覆盖安装，请先卸载旧版后再重新安装。")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            return InstallerResult.NeedsUnknownSources
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(installIntent)
            InstallerResult.Opened
        }.getOrElse { InstallerResult.Failure(it.message ?: "无法打开系统安装器") }
    }

    private fun updateFile(update: AtlasUpdate): File =
        File(context.cacheDir, "updates/codex-atlas-${normalizeVersion(update.latestVersion)}.apk")

    private fun isUsableApk(file: File, update: AtlasUpdate?): Boolean {
        if (!file.isFile || file.length() <= 0) return false
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return false
        if (info.packageName != context.packageName) return false
        val current = update?.currentVersion?.let(::normalizeVersion)
        // The GitHub release tag tracks the desktop package. Android has its
        // own versionName, so validate that the APK is newer than this app
        // instead of requiring it to equal the desktop tag.
        return current.isNullOrBlank() || compareVersions(info.versionName.orEmpty(), current) > 0
    }

    private fun isSignatureCompatible(file: File): Boolean {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, flags) }.getOrNull() ?: return true
        val archive = runCatching { context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull() ?: return false
        return certificateDigests(installed) == certificateDigests(archive)
    }

    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION") info.signatures?.toList().orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
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
