package com.codexatlas.mobile

import android.content.Context

object BridgePreferences {
    private const val PREFS = "atlas_bridge"
    fun url(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "http://127.0.0.1:15730") ?: ""
    fun token(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""
    fun sessionId(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("sessionId", "") ?: ""
    fun tunnelUrl(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tunnelUrl", "") ?: ""
    fun preferTunnel(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("preferTunnel", false)
    fun readRepliesAloud(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("readRepliesAloud", false)
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
    fun savePairing(context: Context, lanUrl: String, tunnelUrl: String, token: String, preferTunnel: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", lanUrl.trim())
            .putString("tunnelUrl", tunnelUrl.trim())
            .putString("token", token.trim())
            .putBoolean("preferTunnel", preferTunnel)
            .apply()
        AtlasWidgetReceiver.requestRefresh(context)
    }
}
