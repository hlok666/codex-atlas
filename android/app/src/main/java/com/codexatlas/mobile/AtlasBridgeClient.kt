package com.codexatlas.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private object BridgeTransport {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Workspace downloads can legitimately take longer than an API poll on a
    // mobile connection. Keep the normal client strict while allowing a
    // bounded window for a large PDF to finish streaming to disk.
    val fileHttp: OkHttpClient = http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    // SSE is intentionally long-lived. Keep a finite watchdog so a dead TCP
    // socket is eventually retried, but leave enough room for a proxy to
    // coalesce a small heartbeat before it reaches the handset.
    val eventsHttp: OkHttpClient = http.newBuilder()
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    private val successful = ConcurrentHashMap.newKeySet<String>()
    private val failedUntilMs = ConcurrentHashMap<String, Long>()

    fun order(candidates: List<String>): List<String> {
        val now = System.currentTimeMillis()
        return candidates.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>>(
                    { candidate ->
                        when {
                            successful.contains(candidate.value) -> 0
                            failedUntilMs.getOrDefault(candidate.value, 0L) > now -> 2
                            else -> 1
                        }
                    },
                    { it.index },
                ),
            )
            .map { it.value }
    }

    fun succeeded(candidate: String) {
        failedUntilMs.remove(candidate)
        successful.add(candidate)
    }

    fun failed(candidate: String) {
        successful.remove(candidate)
        failedUntilMs[candidate] = System.currentTimeMillis() + 30_000L
    }
}

private fun bridgeCandidates(primary: String, fallback: String): List<String> {
    val bases = listOf(primary, fallback)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    return BridgeTransport.order(buildList {
        bases.forEach { candidate ->
            add(candidate)
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return@forEach
            if (uri.host.isNullOrBlank() || !uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return@forEach
            if (!uri.path.isNullOrBlank() && uri.path != "/") return@forEach

            // Older desktop builds exposed a reverse-proxy endpoint as a
            // bare host/port. Keep the original first, then recover the
            // current `/codex-atlas` route when the bare endpoint is 502.
            val pathPort = runCatching {
                URI(uri.scheme, uri.userInfo, uri.host, uri.port, "/codex-atlas", uri.query, uri.fragment).toString()
            }.getOrNull()
            if (!pathPort.isNullOrBlank()) add(pathPort)

            // The old QR sometimes contained the public server's internal
            // bridge port. The reverse proxy is normally served on 80/443.
            if (uri.port == 15730) {
                val publicPath = runCatching {
                    URI(uri.scheme, uri.userInfo, uri.host, -1, "/codex-atlas", uri.query, uri.fragment).toString()
                }.getOrNull()
                if (!publicPath.isNullOrBlank()) add(publicPath)
            }
        }
    }.distinct())
}

private fun workspaceResponseName(response: Response, relativePath: String): String {
    // The bridge header is ASCII-safe and older builds replace non-ASCII
    // characters with underscores. The path came from the JSON listing, so its
    // basename is the most faithful display/download name.
    val pathName = relativePath
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
    return pathName.ifBlank {
        response.header("X-Atlas-File-Name")?.trim().orEmpty()
    }.ifBlank { "workspace-file" }
}

class AtlasBridgeClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val http = BridgeTransport.http
    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(): AtlasSnapshot {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
            return json.decodeFromString(response.body?.string().orEmpty())
        }
    }

    fun snapshotAny(fallbackUrl: String = ""): AtlasSnapshot {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val snapshot = AtlasBridgeClient(candidate, token).snapshot()
                BridgeTransport.succeeded(candidate)
                return snapshot
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun syncAny(
        sinceMs: Long,
        fallbackUrl: String = "",
        waitMs: Long = 0,
        epoch: String = "",
        afterSeq: Long = 0,
        sessionId: String = "",
        includeEvents: Boolean = true,
    ): AtlasSyncResponse {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val query = buildString {
                    append("?since=").append(sinceMs)
                    if (waitMs > 0) append("&wait=").append(waitMs)
                    if (epoch.isNotBlank()) {
                        append("&epoch=")
                            .append(java.net.URLEncoder.encode(epoch, Charsets.UTF_8.name()))
                    }
                    if (afterSeq > 0) append("&after=").append(afterSeq)
                    if (sessionId.isNotBlank()) {
                        append("&session=")
                            .append(java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()))
                    }
                    if (!includeEvents) append("&events=0")
                }
                val request = Request.Builder()
                    .url(candidate + "/v1/sync" + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    val result = json.decodeFromString<AtlasSyncResponse>(response.body?.string().orEmpty())
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    /**
     * Keeps a lightweight server-sent event stream open. The desktop sends a
     * wake-only event whenever Codex changes; callers immediately use their
     * normal sequence-aware sync request to retrieve the durable payload.
     */
    suspend fun streamEventsAny(
        fallbackUrl: String = "",
        onEvent: (String) -> Unit,
    ) {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                streamEvents(candidate, onEvent)
                BridgeTransport.succeeded(candidate)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    private suspend fun streamEvents(candidate: String, onEvent: (String) -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val request = Request.Builder()
                .url(candidate + "/v1/events")
                .header("Authorization", "Bearer $token")
                .header("Accept", "text/event-stream")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            val call = BridgeTransport.eventsHttp.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: java.io.IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) {
                                error("Atlas Bridge returned HTTP ${it.code}")
                            }
                            val body = it.body ?: error("Atlas Bridge returned an empty event stream")
                            val reader = body.charStream().buffered()
                            val data = StringBuilder()
                            while (true) {
                                val line = reader.readLine() ?: break
                                when {
                                    line.startsWith("data:") -> {
                                        data.append(line.substring(5).trimStart()).append('\n')
                                    }
                                    line.isEmpty() && data.isNotEmpty() -> {
                                        onEvent(data.toString().trim())
                                        data.clear()
                                    }
                                }
                            }
                            if (data.isNotEmpty()) onEvent(data.toString().trim())
                        }
                        if (continuation.isActive) continuation.resume(Unit)
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    fun listSessionsAny(fallbackUrl: String = ""): List<AtlasSession> {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate + "/v1/sessions")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    val result = json.decodeFromString<List<AtlasSession>>(response.body?.string().orEmpty())
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun balanceAny(fallbackUrl: String = "", refresh: Boolean = false): AtlasBalance {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate + "/v1/balance" + if (refresh) "?refresh=1" else "")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    val result = json.decodeFromString<AtlasBalance>(response.body?.string().orEmpty())
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun messagesAny(
        sessionId: String,
        fallbackUrl: String = "",
        afterSeq: Long = 0,
        limit: Int = 200,
    ): List<AtlasMessage> {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val query = buildString {
                    if (afterSeq > 0) append("?after=").append(afterSeq)
                    if (limit > 0) append(if (isNotEmpty()) "&" else "?").append("limit=").append(limit)
                }
                val request = Request.Builder()
                    .url(candidate + "/v1/sessions/" + java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()) + "/messages" + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    val result = json.decodeFromString<List<AtlasMessage>>(response.body?.string().orEmpty())
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun workspaceAny(
        sessionId: String,
        relativePath: String = "",
        fallbackUrl: String = "",
    ): AtlasWorkspaceListing {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val query = "?path=" + java.net.URLEncoder.encode(relativePath, Charsets.UTF_8.name())
                val request = Request.Builder()
                    .url(candidate + sessionIdPath(sessionId, "/workspace") + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(bridgeError(response.code, body))
                    val result = json.decodeFromString<AtlasWorkspaceListing>(body)
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun workspacePreviewAny(
        sessionId: String,
        relativePath: String,
        fallbackUrl: String = "",
    ): AtlasWorkspaceFile {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val query = "?path=" + java.net.URLEncoder.encode(relativePath, Charsets.UTF_8.name()) + "&preview=1"
                val request = Request.Builder()
                    .url(candidate + sessionIdPath(sessionId, "/workspace/file") + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        error(bridgeError(response.code, body))
                    }
                    val body = response.body?.bytes() ?: error("Atlas Bridge returned an empty workspace file")
                    val name = workspaceResponseName(response, relativePath)
                    val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                        .orEmpty().ifBlank { "application/octet-stream" }
                    BridgeTransport.succeeded(candidate)
                    return AtlasWorkspaceFile(name, mime, body)
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun downloadWorkspaceFileAny(
        sessionId: String,
        relativePath: String,
        target: File,
        fallbackUrl: String = "",
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        preview: Boolean = false,
    ): AtlasWorkspaceDownload {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val query = buildString {
                    append("?path=")
                    append(java.net.URLEncoder.encode(relativePath, Charsets.UTF_8.name()))
                    if (preview) append("&preview=1")
                }
                val request = Request.Builder()
                    .url(candidate + sessionIdPath(sessionId, "/workspace/file") + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                BridgeTransport.fileHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        error(bridgeError(response.code, body))
                    }
                    val body = response.body ?: error("Atlas Bridge returned an empty workspace file")
                    target.parentFile?.mkdirs()
                    var downloaded = 0L
                    val total = body.contentLength().coerceAtLeast(0L)
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                output.write(buffer, 0, count)
                                downloaded += count
                                onProgress(downloaded, total)
                            }
                            output.flush()
                        }
                    }
                    val name = workspaceResponseName(response, relativePath)
                    val mime = response.header("Content-Type")?.substringBefore(';')?.trim()
                        .orEmpty().ifBlank { "application/octet-stream" }
                    BridgeTransport.succeeded(candidate)
                    return AtlasWorkspaceDownload(name, mime, downloaded, target)
                }
            } catch (error: Throwable) {
                target.delete()
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun createSessionAny(cwd: String, prompt: String, model: String, permission: String, fallbackUrl: String = "") {
        val body = json.encodeToString(mapOf("cwd" to cwd, "prompt" to prompt, "model" to model, "permission" to permission))
        postAny("/v1/sessions", body, fallbackUrl)
    }

    fun activate(sessionId: String) = post("/v1/sessions/$sessionId/activate", "{}")

    fun inputContinue(sessionId: String) = post("/v1/sessions/$sessionId/input", "{\"text\":\"继续\"}")

    fun input(sessionId: String, text: String) =
        post(sessionIdPath(sessionId, "/input"), json.encodeToString(mapOf("text" to text)))

    fun activateAny(sessionId: String, fallbackUrl: String = "") =
        postAny("/v1/sessions/$sessionId/activate", "{}", fallbackUrl)

    fun inputContinueAny(sessionId: String, fallbackUrl: String = "") =
        postAny("/v1/sessions/$sessionId/input", "{\"text\":\"继续\"}", fallbackUrl)

    fun inputAny(sessionId: String, text: String, fallbackUrl: String = "") =
        postAny(sessionIdPath(sessionId, "/input"), json.encodeToString(mapOf("text" to text)), fallbackUrl)

    fun sendMessageAny(
        sessionId: String,
        text: String,
        fallbackUrl: String = "",
        clientMessageId: String = "",
        mode: String = AtlasMessageMode.Queue.key,
    ) {
        val payload = buildMap {
            put("text", text)
            if (clientMessageId.isNotBlank()) put("clientMessageId", clientMessageId)
            put("mode", AtlasMessageMode.fromKey(mode).key)
        }
        postAny(sessionIdPath(sessionId, "/message"), json.encodeToString(payload), fallbackUrl)
    }

    fun sendDictationChunkAny(
        sessionId: String,
        seq: Long,
        text: String,
        finalChunk: Boolean,
        fallbackUrl: String = "",
    ): AtlasDictationAck {
        val body = json.encodeToString(
            AtlasDictationChunk(
                seq = seq,
                text = text,
                finalChunk = finalChunk,
                clientMessageId = "dictation:$sessionId:$seq",
            ),
        )
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate + "/v1/sessions/" + java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()) + "/dictation")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody())
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    val result = json.decodeFromString<AtlasDictationAck>(response.body?.string().orEmpty())
                    BridgeTransport.succeeded(candidate)
                    return result
                }
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun importAllPaseoAny(fallbackUrl: String = "") =
        postAny("/v1/paseo/import-all", "{}", fallbackUrl)

    private fun postAny(path: String, body: String, fallbackUrl: String) {
        val candidates = bridgeCandidates(baseUrl, fallbackUrl)
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                AtlasBridgeClient(candidate, token).post(path, body)
                BridgeTransport.succeeded(candidate)
                return
            } catch (error: Throwable) {
                BridgeTransport.failed(candidate)
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    private fun bridgeError(code: Int, body: String): String {
        val detail = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return buildString {
            append("Atlas Bridge returned HTTP ").append(code)
            if (!detail.isNullOrBlank()) append(": ").append(detail)
        }
    }

    private fun post(path: String, body: String) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody())
            .build()
        http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                error(
                    buildString {
                        append("Atlas Bridge returned HTTP ").append(response.code)
                        if (!detail.isNullOrBlank()) append(": ").append(detail)
                    },
                )
            }
        }
    }

    private fun sessionIdPath(sessionId: String, suffix: String): String =
        "/v1/sessions/" + java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()) + suffix
}
