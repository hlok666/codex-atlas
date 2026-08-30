package com.codexatlas.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AtlasBridgeClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val http = OkHttpClient()
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
        val candidates = listOf(baseUrl, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        var failure: Throwable? = null
        for (candidate in candidates) {
            try { return AtlasBridgeClient(candidate, token).snapshot() } catch (error: Throwable) { failure = error }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun syncAny(sinceMs: Long, fallbackUrl: String = ""): AtlasSyncResponse {
        val candidates = listOf(baseUrl, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate + "/v1/sync?since=" + sinceMs)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun listSessionsAny(fallbackUrl: String = ""): List<AtlasSession> {
        val candidates = listOf(baseUrl, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
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
                failure = error
            }
        }
        throw failure ?: IllegalStateException("No Atlas Bridge URL configured")
    }

    fun messagesAny(sessionId: String, fallbackUrl: String = ""): List<AtlasMessage> {
        val candidates = listOf(baseUrl, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder()
                    .url(candidate + "/v1/sessions/" + java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()) + "/messages")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
                    return json.decodeFromString(response.body?.string().orEmpty())
                }
            } catch (error: Throwable) {
                failure = error
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

    fun sendMessageAny(sessionId: String, text: String, fallbackUrl: String = "") =
        postAny("/v1/sessions/$sessionId/message", json.encodeToString(mapOf("text" to text)), fallbackUrl)

    fun importAllPaseoAny(fallbackUrl: String = "") =
        postAny("/v1/paseo/import-all", "{}", fallbackUrl)

    private fun postAny(path: String, body: String, fallbackUrl: String) {
        val candidates = listOf(baseUrl, fallbackUrl).map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct()
        var failure: Throwable? = null
        for (candidate in candidates) {
            try {
                AtlasBridgeClient(candidate, token).post(path, body)
                return
            } catch (error: Throwable) {
                failure = error
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
            if (!response.isSuccessful) error("Atlas Bridge returned HTTP ${response.code}")
        }
    }

    private fun sessionIdPath(sessionId: String, suffix: String): String =
        "/v1/sessions/" + java.net.URLEncoder.encode(sessionId, Charsets.UTF_8.name()) + suffix
}
