package com.codexatlas.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

private fun bridgeCandidates(primary: String, fallback: String): List<String> {
    val bases = listOf(primary, fallback)
        .map { it.trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()
    return buildList {
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
    }.distinct()
}

class AtlasBridgeClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
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
            try { return AtlasBridgeClient(candidate, token).snapshot() } catch (error: Throwable) {
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
                }
                val request = Request.Builder()
                    .url(candidate + "/v1/sync" + query)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
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
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun messagesAny(
        sessionId: String,
        fallbackUrl: String = "",
        afterSeq: Long = 0,
        limit: Int = 0,
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
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
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
    ) {
        val payload = buildMap {
            put("text", text)
            if (clientMessageId.isNotBlank()) put("clientMessageId", clientMessageId)
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
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
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
                return
            } catch (error: Throwable) {
                failure = IllegalStateException("$candidate: ${error.message}", error)
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
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
