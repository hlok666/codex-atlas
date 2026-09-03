package com.codexatlas.mobile

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BridgePreferences {
    private const val PREFS = "atlas_bridge"
    private const val CACHED_SNAPSHOT = "cachedSnapshot"
    private const val SYNC_CURSOR = "syncCursor"
    private const val SYNC_EPOCH = "syncEpoch"
    private const val SYNC_SEQ = "syncSeq"
    private const val DEVICES = "devices"
    private const val SELECTED_DEVICE = "selectedDevice"
    private const val SEND_MODE = "sendMode"
    private val json = Json { ignoreUnknownKeys = true }
    fun url(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "http://127.0.0.1:15730") ?: ""
    fun token(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""
    fun sessionId(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sessionId", "") ?: ""
    fun tunnelUrl(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tunnelUrl", "") ?: ""
    fun preferTunnel(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("preferTunnel", false)
    fun connectionRoute(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("connectionRoute", if (preferTunnel(context)) "server" else "auto") ?: "auto"
    fun readRepliesAloud(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("readRepliesAloud", false)
    fun sendMode(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(SEND_MODE, AtlasMessageMode.Queue.key) ?: AtlasMessageMode.Queue.key
    fun save(context: Context, url: String, token: String, sessionId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", url.trim())
            .putString("token", token.trim())
            .putString("sessionId", sessionId.trim())
            .apply()
        AtlasWidgetReceiver.requestRefresh(context)
    }

    fun saveReadRepliesAloud(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("readRepliesAloud", enabled).apply()
    }

    fun saveSendMode(context: Context, mode: AtlasMessageMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SEND_MODE, mode.key).apply()
    }
    fun savePairing(context: Context, lanUrl: String, tunnelUrl: String, token: String, preferTunnel: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", lanUrl.trim())
            .putString("tunnelUrl", tunnelUrl.trim())
            .putString("token", token.trim())
            .putBoolean("preferTunnel", preferTunnel)
            .putString("connectionRoute", if (preferTunnel) "server" else "auto")
            .commit()
        AtlasWidgetReceiver.requestRefresh(context)
    }

    fun devices(context: Context): List<AtlasDeviceProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(DEVICES, "").orEmpty()
        val stored = raw.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString<List<AtlasDeviceProfile>>(it) }.getOrNull() }
            .orEmpty()
        if (stored.isNotEmpty()) return stored
        val legacyLan = prefs.getString("url", "").orEmpty().trim()
        val legacyToken = prefs.getString("token", "").orEmpty().trim()
        if (legacyLan.isBlank() || legacyToken.isBlank()) return emptyList()
        val legacy = AtlasDeviceProfile(
            id = legacyDeviceId(legacyLan, prefs.getString("tunnelUrl", "").orEmpty()),
            name = "Codex Atlas",
            kind = "desktop",
            lanUrl = legacyLan,
            tunnelUrl = prefs.getString("tunnelUrl", "").orEmpty().trim(),
            token = legacyToken,
            preferTunnel = prefs.getBoolean("preferTunnel", false),
            route = prefs.getString("connectionRoute", "auto") ?: "auto",
        )
        persistDevices(context, listOf(legacy), legacy.id)
        return listOf(legacy)
    }

    fun selectedDeviceId(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED_DEVICE, "").orEmpty()
        return stored.ifBlank { devices(context).firstOrNull()?.id.orEmpty() }
    }

    fun selectedDevice(context: Context): AtlasDeviceProfile? = devices(context)
        .firstOrNull { it.id == selectedDeviceId(context) }

    fun saveDevice(context: Context, profile: AtlasDeviceProfile, select: Boolean = true) {
        val next = devices(context).filterNot { it.id == profile.id } + profile
        persistDevices(context, next, if (select) profile.id else selectedDeviceId(context))
        if (select) applyDevice(context, profile.id)
        AtlasWidgetReceiver.requestRefresh(context)
    }

    fun selectDevice(context: Context, deviceId: String): Boolean {
        val profile = devices(context).firstOrNull { it.id == deviceId } ?: return false
        persistDevices(context, devices(context), profile.id)
        applyDevice(context, profile.id)
        AtlasWidgetReceiver.requestRefresh(context)
        return true
    }

    fun removeDevice(context: Context, deviceId: String) {
        val remaining = devices(context).filterNot { it.id == deviceId }
        val nextSelected = remaining.firstOrNull()?.id.orEmpty()
        persistDevices(context, remaining, nextSelected)
        if (nextSelected.isNotBlank()) applyDevice(context, nextSelected)
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("url").remove("tunnelUrl").remove("token").remove(SELECTED_DEVICE).apply()
        AtlasWidgetReceiver.requestRefresh(context)
    }

    private fun applyDevice(context: Context, deviceId: String) {
        val profile = devices(context).firstOrNull { it.id == deviceId } ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", profile.lanUrl)
            .putString("tunnelUrl", profile.tunnelUrl)
            .putString("token", profile.token)
            .putBoolean("preferTunnel", profile.preferTunnel)
            .putString("connectionRoute", profile.route)
            .putString(SELECTED_DEVICE, profile.id)
            .apply()
    }

    private fun persistDevices(context: Context, profiles: List<AtlasDeviceProfile>, selectedId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DEVICES, json.encodeToString(profiles))
            .putString(SELECTED_DEVICE, selectedId)
            .apply()
    }

    private fun legacyDeviceId(lanUrl: String, tunnelUrl: String): String =
        "desktop-${Integer.toUnsignedString((lanUrl.trim() + "|" + tunnelUrl.trim()).hashCode(), 16)}"

    fun saveConnectionRoute(context: Context, route: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("connectionRoute", route)
            .putBoolean("preferTunnel", route == "server")
            .apply()
        val selectedId = selectedDeviceId(context)
        val updated = devices(context).map { profile ->
            if (profile.id == selectedId) profile.copy(route = route, preferTunnel = route == "server") else profile
        }
        persistDevices(context, updated, selectedId)
        AtlasWidgetReceiver.requestRefresh(context)
    }

    fun saveCachedSnapshot(context: Context, snapshot: AtlasSnapshot) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CACHED_SNAPSHOT, json.encodeToString(snapshot))
            .putString("$CACHED_SNAPSHOT:${selectedDeviceId(context)}", json.encodeToString(snapshot))
            .apply()
    }

    fun cachedSnapshot(context: Context): AtlasSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("$CACHED_SNAPSHOT:${selectedDeviceId(context)}", null)
            ?: prefs.getString(CACHED_SNAPSHOT, null).takeIf { devices(context).size <= 1 }
            .orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<AtlasSnapshot>(it) }.getOrNull() }
    }

    private fun scopedKey(base: String, context: Context): String = "$base:${selectedDeviceId(context)}"

    fun syncCursor(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(scopedKey(SYNC_CURSOR, context), if (devices(context).size <= 1) prefs.getLong(SYNC_CURSOR, 0L) else 0L)
    }

    fun saveSyncCursor(context: Context, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SYNC_CURSOR, value)
            .putLong(scopedKey(SYNC_CURSOR, context), value)
            .apply()
    }

    fun syncEpoch(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(scopedKey(SYNC_EPOCH, context), if (devices(context).size <= 1) prefs.getString(SYNC_EPOCH, "") else "") ?: ""
    }

    fun syncSeq(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(scopedKey(SYNC_SEQ, context), if (devices(context).size <= 1) prefs.getLong(SYNC_SEQ, 0L) else 0L)
    }

    fun saveSyncPosition(context: Context, epoch: String, seq: Long, cursorMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SYNC_EPOCH, epoch)
            .putLong(SYNC_SEQ, seq)
            .putLong(SYNC_CURSOR, cursorMs)
            .putString(scopedKey(SYNC_EPOCH, context), epoch)
            .putLong(scopedKey(SYNC_SEQ, context), seq)
            .putLong(scopedKey(SYNC_CURSOR, context), cursorMs)
            .apply()
    }

    fun clearSyncPosition(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(SYNC_EPOCH)
            .putLong(SYNC_SEQ, 0L)
            .putLong(SYNC_CURSOR, 0L)
            .remove(scopedKey(SYNC_EPOCH, context))
            .putLong(scopedKey(SYNC_SEQ, context), 0L)
            .putLong(scopedKey(SYNC_CURSOR, context), 0L)
            .apply()
    }

    fun dictationSeq(context: Context, sessionId: String): Long = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong("dictationSeq:${sessionId.trim()}", 0L)

    fun saveDictationSeq(context: Context, sessionId: String, value: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("dictationSeq:${sessionId.trim()}", value)
            .apply()
    }
}
